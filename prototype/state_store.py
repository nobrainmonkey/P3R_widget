"""Observed state only. SQLite transactions, idempotent returns, and checkpoint reentry.
No P3R availability, affinity, floor-unlock or reward rules are invented here.
"""
from __future__ import annotations
import copy
import json
import sqlite3
from dataclasses import dataclass
from typing import Any

class InvalidObservation(ValueError):
    pass

class StateConflict(RuntimeError):
    pass

@dataclass(frozen=True)
class Receipt:
    snapshot: dict
    duplicate: bool = False
    warnings: tuple[str, ...] = ()

def _json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)

def _integer(value, name, minimum=None, nullable=False):
    if nullable and value is None:
        return
    if type(value) is not int or (minimum is not None and value < minimum):
        raise InvalidObservation(f"{name} must be an integer" + (f" >= {minimum}" if minimum is not None else ""))

def _identifier(value, name):
    if not isinstance(value, str) or not value.strip():
        raise InvalidObservation(f"{name} must be a nonempty string")

def _baseline(value):
    if not isinstance(value, dict):
        raise InvalidObservation("baseline must be an object")
    required = {"rules_version", "game_slot", "cash", "highest_floor", "active_stage", "visits_by_stage", "facts"}
    if not required.issubset(value):
        raise InvalidObservation("baseline missing: " + ", ".join(sorted(required - value.keys())))
    if set(value) - required - {"legacy_guide_backup"}:
        raise InvalidObservation("unknown baseline field")
    out = copy.deepcopy(value)
    _identifier(out["rules_version"], "rules_version")
    _integer(out["game_slot"], "game_slot", 0)
    _integer(out["cash"], "cash", 0, True)
    _integer(out["highest_floor"], "highest_floor", 0, True)
    if out["active_stage"] is not None:
        _identifier(out["active_stage"], "active_stage")
    if not isinstance(out["visits_by_stage"], dict) or not isinstance(out["facts"], dict):
        raise InvalidObservation("visits_by_stage and facts must be objects")
    for stage, count in out["visits_by_stage"].items():
        _identifier(stage, "stage")
        _integer(count, "visit count", 0, True)
    for key, interval in out["facts"].items():
        _identifier(key, "fact")
        if not isinstance(interval, dict) or set(interval) != {"low", "high"}:
            raise InvalidObservation("facts require explicit low/high, with null for unknown")
        low, high = interval["low"], interval["high"]
        _integer(low, "low", nullable=True)
        _integer(high, "high", nullable=True)
        if low is not None and high is not None and low > high:
            raise InvalidObservation("inverted fact interval")
    _json(out)
    return out

class StateStore:
    def __init__(self, path=":memory:", stage_ceilings=None, stage_windows=None):
        self.stage_windows = dict(stage_windows or {})
        for stage, window in self.stage_windows.items():
            _identifier(stage, "stage")
            if not isinstance(window, (tuple, list)) or len(window) != 2:
                raise InvalidObservation("stage window needs [start,end)")
            _integer(window[0], "stage start", 0)
            _integer(window[1], "stage end", window[0] + 1)
        windows = sorted(self.stage_windows.values())
        if any(left[1] > right[0] for left, right in zip(windows, windows[1:])):
            raise InvalidObservation("overlapping stage windows")
        self.stage_ceilings = dict(stage_ceilings or {})
        for stage, ceiling in self.stage_ceilings.items():
            _identifier(stage, "stage")
            _integer(ceiling, "floor ceiling", 0)
        self.db = sqlite3.connect(path, timeout=10, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS runs (
            run_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, epoch INTEGER NOT NULL,
            snapshot TEXT NOT NULL, plan_revision INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS checkpoints (
            run_id TEXT NOT NULL REFERENCES runs(run_id), epoch INTEGER NOT NULL,
            payload TEXT NOT NULL, PRIMARY KEY(run_id,epoch));
        CREATE TABLE IF NOT EXISTS events (
            seq INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES runs(run_id),
            command_id TEXT NOT NULL, epoch INTEGER NOT NULL, kind TEXT NOT NULL,
            payload TEXT NOT NULL, UNIQUE(run_id,command_id));
        CREATE TABLE IF NOT EXISTS preferences (
            run_id TEXT PRIMARY KEY REFERENCES runs(run_id), payload TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS plans (
            run_id TEXT PRIMARY KEY REFERENCES runs(run_id), plan_revision INTEGER NOT NULL,
            state_revision INTEGER NOT NULL, rules_version TEXT NOT NULL, payload TEXT NOT NULL);
        ''')

    def close(self):
        self.db.close()

    def _run(self, run_id):
        row = self.db.execute("SELECT * FROM runs WHERE run_id=?", (run_id,)).fetchone()
        if row is None:
            raise InvalidObservation("unknown run")
        return row

    def get_snapshot(self, run_id):
        return json.loads(self._run(run_id)["snapshot"])

    def _check_observed_stage(self, baseline):
        known = next((stage for stage, (start,end) in self.stage_windows.items()
                      if start <= baseline["game_slot"] < end), None)
        if known is not None and baseline["active_stage"] != known:
            raise InvalidObservation("observed active stage disagrees with verified calendar stage")

    def create_run(self, run_id, baseline):
        _identifier(run_id, "run_id")
        baseline = _baseline(baseline)
        self._check_observed_stage(baseline)
        state = dict(baseline, run_id=run_id, revision=0, epoch=0, visits_since_baseline={})
        self.db.execute("BEGIN IMMEDIATE")
        try:
            self.db.execute("INSERT INTO runs(run_id,revision,epoch,snapshot) VALUES(?,0,0,?)", (run_id, _json(state)))
            self.db.execute("INSERT INTO checkpoints VALUES(?,0,?)", (run_id, _json(baseline)))
            self.db.execute("COMMIT")
        except Exception:
            self.db.execute("ROLLBACK")
            raise
        return state

    def _existing(self, run_id, command_id, kind, payload):
        row = self.db.execute("SELECT * FROM events WHERE run_id=? AND command_id=?", (run_id, command_id)).fetchone()
        if row is None:
            return None
        if row["kind"] != kind or row["payload"] != _json(payload):
            raise StateConflict("command ID reused with a different payload")
        return Receipt(self.get_snapshot(run_id), duplicate=True)

    def _revision(self, row, expected):
        _integer(expected, "expected_revision", 0)
        if row["revision"] != expected:
            raise StateConflict("observed state changed; reload before submitting")

    def _save(self, run_id, state):
        self.db.execute("UPDATE runs SET revision=?,epoch=?,snapshot=? WHERE run_id=?",
                        (state["revision"], state["epoch"], _json(state), run_id))
        self.db.execute("DELETE FROM plans WHERE run_id=?", (run_id,))

    def reenter(self, run_id, command_id, expected_revision, baseline):
        """Start directly from a newly observed checkpoint, without reconstructing history."""
        _identifier(command_id, "command_id")
        payload = _baseline(baseline)
        self._check_observed_stage(payload)
        self.db.execute("BEGIN IMMEDIATE")
        try:
            prior = self._existing(run_id, command_id, "baseline", payload)
            if prior:
                self.db.execute("COMMIT")
                return prior
            row = self._run(run_id)
            self._revision(row, expected_revision)
            epoch = row["epoch"] + 1
            state = dict(payload, run_id=run_id, revision=row["revision"] + 1, epoch=epoch, visits_since_baseline={})
            self.db.execute("INSERT INTO checkpoints VALUES(?,?,?)", (run_id, epoch, _json(payload)))
            self.db.execute("INSERT INTO events(run_id,command_id,epoch,kind,payload) VALUES(?,?,?,'baseline',?)",
                            (run_id, command_id, epoch, _json(payload)))
            self._save(run_id, state)
            self.db.execute("COMMIT")
            return Receipt(state)
        except Exception:
            self.db.execute("ROLLBACK")
            raise

    def _return_payload(self, stage_id, highest_floor, net_cash, absolute_balance, corrects_event_id):
        _identifier(stage_id, "stage_id")
        _integer(highest_floor, "highest_floor", 0)
        if (net_cash is None) == (absolute_balance is None):
            raise InvalidObservation("enter net cash change OR absolute balance, exactly one")
        if net_cash is not None:
            _integer(net_cash, "net_cash")
        else:
            _integer(absolute_balance, "absolute_balance", 0)
        if corrects_event_id is not None:
            _identifier(corrects_event_id, "corrects_event_id")
        if stage_id in self.stage_ceilings and highest_floor > self.stage_ceilings[stage_id]:
            raise InvalidObservation("floor exceeds the verified active-stage ceiling")
        return dict(stage_id=stage_id, highest_floor=highest_floor, net_cash=net_cash,
                    absolute_balance=absolute_balance, corrects_event_id=corrects_event_id)

    def _replay(self, run_id, epoch):
        checkpoint = self.db.execute("SELECT payload FROM checkpoints WHERE run_id=? AND epoch=?", (run_id, epoch)).fetchone()
        state = json.loads(checkpoint["payload"])
        state["visits_since_baseline"] = {}
        events = self.db.execute("SELECT * FROM events WHERE run_id=? AND epoch=? ORDER BY seq", (run_id, epoch)).fetchall()
        replacements = {}
        for event in events:
            if event["kind"] == "return_correction":
                payload = json.loads(event["payload"])
                replacements[payload["corrects_event_id"]] = payload
        for event in events:
            if event["kind"] != "dungeon_return":
                continue
            payload = replacements.get(event["command_id"], json.loads(event["payload"]))
            stage = payload["stage_id"]
            if payload["absolute_balance"] is not None:
                state["cash"] = payload["absolute_balance"]
            else:
                if state["cash"] is None:
                    raise InvalidObservation("current cash is unknown; enter absolute balance")
                state["cash"] += payload["net_cash"]
                if state["cash"] < 0:
                    raise InvalidObservation("cash cannot become negative")
            floor = payload["highest_floor"]
            # A revisit may stop below an earlier maximum; it still consumes a visit.
            state["highest_floor"] = floor if state["highest_floor"] is None else max(floor, state["highest_floor"])
            before = state["visits_by_stage"].get(stage)
            state["visits_by_stage"][stage] = None if before is None else before + 1
            state["visits_since_baseline"][stage] = state["visits_since_baseline"].get(stage, 0) + 1
            state["game_slot"] = payload["next_game_slot"]
            if self.stage_windows:
                state["active_stage"] = next((key for key, (start,end) in self.stage_windows.items()
                                               if start <= state["game_slot"] < end), None)
        return state

    def record_dungeon_return(self, run_id, command_id, expected_revision, *, stage_id,
                              highest_floor, net_cash=None, absolute_balance=None, corrects_event_id=None,
                              visit_slot=None, next_game_slot=None):
        """Confirm one actual visit. Corrections replace that visit, never count another.

        highest_floor is the highest floor reported for this visit; the observed run
        maximum retains any earlier higher floor. Net cash means the actual net change,
        not gross loot. This ledger records actual history even if a user's planning cap
        was exceeded; the planner must then forbid further proposed visits.
        """
        _identifier(command_id, "command_id")
        payload = self._return_payload(stage_id, highest_floor, net_cash, absolute_balance, corrects_event_id)
        kind = "return_correction" if corrects_event_id is not None else "dungeon_return"
        self.db.execute("BEGIN IMMEDIATE")
        try:
            row = self._run(run_id)
            observed = json.loads(row["snapshot"])
            existing = self.db.execute("SELECT payload FROM events WHERE run_id=? AND command_id=?", (run_id, command_id)).fetchone()
            target_slots = None
            if existing is not None:
                target_slots = json.loads(existing["payload"])
            elif corrects_event_id is not None:
                target = self.db.execute("SELECT payload FROM events WHERE run_id=? AND command_id=?", (run_id, corrects_event_id)).fetchone()
                if target is not None:
                    target_slots = json.loads(target["payload"])
            captured_slot = observed["game_slot"] if target_slots is None else target_slots.get("visit_slot", observed["game_slot"])
            payload["visit_slot"] = captured_slot if visit_slot is None else visit_slot
            captured_next = payload["visit_slot"] + 1 if target_slots is None else target_slots.get("next_game_slot", payload["visit_slot"] + 1)
            payload["next_game_slot"] = captured_next if next_game_slot is None else next_game_slot
            _integer(payload["visit_slot"], "visit_slot", 0)
            _integer(payload["next_game_slot"], "next_game_slot", payload["visit_slot"] + 1)
            prior = self._existing(run_id, command_id, kind, payload)
            if prior:
                self.db.execute("COMMIT")
                return prior
            row = self._run(run_id)
            self._revision(row, expected_revision)
            observed = json.loads(row["snapshot"])
            if corrects_event_id is not None:
                target = self.db.execute("SELECT * FROM events WHERE run_id=? AND command_id=?", (run_id, corrects_event_id)).fetchone()
                if target is None or target["kind"] != "dungeon_return" or target["epoch"] != row["epoch"]:
                    raise InvalidObservation("correction must target an original return in the current checkpoint")
                original = json.loads(target["payload"])
                if original["stage_id"] != stage_id:
                    raise InvalidObservation("a correction cannot move a visit to a different stage")
                if (original["visit_slot"], original["next_game_slot"]) != (payload["visit_slot"], payload["next_game_slot"]):
                    raise InvalidObservation("a correction cannot move the visit in time")
            else:
                if observed["active_stage"] != stage_id:
                    raise InvalidObservation("stage does not match the observed active unlock stage")
                if payload["visit_slot"] != observed["game_slot"]:
                    raise StateConflict("return form belongs to an earlier game slot")
                originals = self.db.execute("SELECT payload FROM events WHERE run_id=? AND epoch=? AND kind='dungeon_return'", (run_id, row["epoch"]))
                if any(json.loads(event["payload"])["visit_slot"] == payload["visit_slot"] for event in originals):
                    raise StateConflict("this game slot already has a confirmed dungeon visit")
            self.db.execute("INSERT INTO events(run_id,command_id,epoch,kind,payload) VALUES(?,?,?,?,?)",
                            (run_id, command_id, row["epoch"], kind, _json(payload)))
            state = self._replay(run_id, row["epoch"])
            state.update(run_id=run_id, revision=row["revision"] + 1, epoch=row["epoch"])
            self._save(run_id, state)
            warnings = () if state["visits_by_stage"].get(stage_id) is not None else (
                "Prior visit count is unknown; confirm it before promising a remaining allowance.",)
            self.db.execute("COMMIT")
            return Receipt(state, warnings=warnings)
        except Exception:
            self.db.execute("ROLLBACK")
            raise

    def set_preferences(self, run_id, command_id, expected_revision, *, goals, constraints):
        """Goal/cap changes share the revision guard, invalidating in-flight old searches."""
        _identifier(command_id, "command_id")
        if not isinstance(goals,list) or not isinstance(constraints,dict):
            raise InvalidObservation("goals must be a list and constraints an object")
        for goal in goals:
            if not isinstance(goal,dict) or set(goal)-{"field","target","priority"} or not {"field","target"}.issubset(goal):
                raise InvalidObservation("unsupported goal fields")
            _identifier(goal["field"],"goal field")
            _integer(goal["target"],"goal target")
            _integer(goal.get("priority",0),"goal priority")
        if set(constraints)-{"visit_caps","pinned_actions"}:
            raise InvalidObservation("unsupported constraint fields")
        for stage,cap in constraints.get("visit_caps",{}).items():
            _identifier(stage,"stage"); _integer(cap,"visit cap",0)
        for slot,action in constraints.get("pinned_actions",{}).items():
            if not str(slot).isdigit(): raise InvalidObservation("pinned slot must be nonnegative")
            _identifier(action,"action")
        payload = json.loads(_json(dict(goals=goals,constraints=constraints)))
        self.db.execute("BEGIN IMMEDIATE")
        try:
            prior=self._existing(run_id,command_id,"preferences",payload)
            if prior:
                self.db.execute("COMMIT"); return prior
            row=self._run(run_id); self._revision(row,expected_revision)
            state=json.loads(row["snapshot"]); state["revision"]+=1
            self.db.execute("INSERT OR REPLACE INTO preferences VALUES(?,?)",(run_id,_json(payload)))
            self.db.execute("INSERT INTO events(run_id,command_id,epoch,kind,payload) VALUES(?,?,?,'preferences',?)",
                            (run_id,command_id,row["epoch"],_json(payload)))
            self._save(run_id,state); self.db.execute("COMMIT")
            return Receipt(state)
        except Exception:
            self.db.execute("ROLLBACK"); raise

    def get_preferences(self, run_id):
        row=self.db.execute("SELECT payload FROM preferences WHERE run_id=?",(run_id,)).fetchone()
        return None if row is None else json.loads(row["payload"])

    def install_plan(self, run_id, expected_revision, rules_version, plan_payload):
        """Compare-and-swap: a slow computation cannot replace a newer observed state."""
        _identifier(rules_version, "rules_version")
        encoded = _json(plan_payload)
        self.db.execute("BEGIN IMMEDIATE")
        try:
            row = self._run(run_id)
            self._revision(row, expected_revision)
            if json.loads(row["snapshot"])["rules_version"] != rules_version:
                raise StateConflict("plan ruleset is stale")
            revision = row["plan_revision"] + 1
            self.db.execute("UPDATE runs SET plan_revision=? WHERE run_id=?", (revision, run_id))
            self.db.execute("INSERT OR REPLACE INTO plans VALUES(?,?,?,?,?)",
                            (run_id, revision, expected_revision, rules_version, encoded))
            self.db.execute("COMMIT")
            return revision
        except Exception:
            self.db.execute("ROLLBACK")
            raise

    def get_plan(self, run_id):
        row = self.db.execute("SELECT * FROM plans WHERE run_id=?", (run_id,)).fetchone()
        if row is None:
            return None
        result = dict(row)
        result["payload"] = json.loads(result["payload"])
        return result

    def export_run(self, run_id):
        """Separate planner backup. Never rewrites or interprets the legacy guide backup."""
        row = dict(self._run(run_id))
        row["snapshot"] = json.loads(row["snapshot"])
        checkpoints = [dict(x) for x in self.db.execute("SELECT * FROM checkpoints WHERE run_id=? ORDER BY epoch", (run_id,))]
        events = [dict(x) for x in self.db.execute("SELECT * FROM events WHERE run_id=? ORDER BY seq", (run_id,))]
        for item in checkpoints + events:
            item["payload"] = json.loads(item["payload"])
        return dict(format="p3r_planner_v2", schema_version=1, run=row, checkpoints=checkpoints,
                    events=events, plan=self.get_plan(run_id), preferences=self.get_preferences(run_id))
