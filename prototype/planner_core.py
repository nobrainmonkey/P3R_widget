"""Isolated planner foundation. No real P3R rules are included.

Slots, numeric fields, stage windows and action effects are supplied by a ruleset.
One action consumes one slot. Higher numeric goal priorities rank first. Forecasts
retain the observed revision; only an observed-state ledger may advance it.
Synthetic rules are for tests and must never be described as game guidance.
"""

from __future__ import annotations

from dataclasses import dataclass, field as dc_field
import hashlib
import json
from typing import Mapping, Sequence


def _int(value: object, name: str, minimum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    if minimum is not None and value < minimum:
        raise ValueError(f"{name} must be >= {minimum}")
    return value


def _identifier(value: object, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be a nonempty string")
    return value


@dataclass(frozen=True)
class Interval:
    """None is an unknown/unbounded endpoint, never a fabricated zero."""

    low: int | None
    high: int | None

    def __post_init__(self) -> None:
        if self.low is not None:
            _int(self.low, "low")
        if self.high is not None:
            _int(self.high, "high")
        if self.low is not None and self.high is not None and self.low > self.high:
            raise ValueError("Interval low exceeds high")

    @classmethod
    def exact(cls, value: int) -> Interval:
        return cls(value, value)

    @classmethod
    def unknown(cls) -> Interval:
        return cls(None, None)

    def plus(self, other: Interval) -> Interval:
        return Interval(
            None if self.low is None or other.low is None else self.low + other.low,
            None if self.high is None or other.high is None else self.high + other.high,
        )


@dataclass(frozen=True)
class Snapshot:
    run_id: str
    rules_version: str
    revision: int
    slot: int
    values: Mapping[str, Interval]
    visits: Mapping[str, int | None] = dc_field(default_factory=dict)

    def __post_init__(self) -> None:
        _identifier(self.run_id, "run_id")
        _identifier(self.rules_version, "rules_version")
        _int(self.revision, "revision", 0)
        _int(self.slot, "slot", 0)
        if any(not isinstance(v, Interval) for v in self.values.values()):
            raise ValueError("Snapshot values must be Interval instances")
        for name in self.values:
            _identifier(name, "state field")
        for name in self.visits:
            _identifier(name, "visit stage")
        for count in self.visits.values():
            if count is not None:
                _int(count, "visit count", 0)
        object.__setattr__(self, "values", dict(self.values))
        object.__setattr__(self, "visits", dict(self.visits))


@dataclass(frozen=True)
class Stage:
    id: str
    start: int
    end: int
    floor_ceiling: int

    def __post_init__(self) -> None:
        _int(self.start, "stage start", 0)
        _int(self.end, "stage end", 1)
        _int(self.floor_ceiling, "floor ceiling", 0)
        _identifier(self.id, "stage ID")
        if self.end <= self.start:
            raise ValueError("Stage needs an ID and nonempty [start, end) window")


@dataclass(frozen=True)
class Action:
    id: str
    slots: frozenset[int]
    requirements: Mapping[str, int] = dc_field(default_factory=dict)
    effects: Mapping[str, int] = dc_field(default_factory=dict)
    uncertain_effects: Mapping[str, Interval] = dc_field(default_factory=dict)
    costs: Mapping[str, int] = dc_field(default_factory=dict)
    dungeon_visit: bool = False
    target_floor: int | None = None
    verification: str = "synthetic"
    source: str = "synthetic"
    maximums: Mapping[str, int] = dc_field(default_factory=dict)

    def __post_init__(self) -> None:
        _identifier(self.id, "action ID")
        object.__setattr__(self, "slots", frozenset(self.slots))
        for slot in self.slots:
            _int(slot, "action slot", 0)
        for name in ("requirements", "effects", "costs", "maximums"):
            values = dict(getattr(self, name))
            for key in values:
                _identifier(key, "action field")
            for value in values.values():
                _int(value, name, 0 if name == "costs" else None)
            object.__setattr__(self, name, values)
        if any(not isinstance(v, Interval) for v in self.uncertain_effects.values()):
            raise ValueError("uncertain_effects must contain Interval instances")
        for name in self.uncertain_effects:
            _identifier(name, "uncertain effect field")
        object.__setattr__(self, "uncertain_effects", dict(self.uncertain_effects))
        if self.target_floor is not None:
            _int(self.target_floor, "target floor", 0)
            if not self.dungeon_visit:
                raise ValueError("Only a dungeon action may have a target floor")


@dataclass(frozen=True)
class Ruleset:
    version: str
    horizon: int
    fields: frozenset[str]
    actions: tuple[Action, ...]
    stages: tuple[Stage, ...] = ()
    fixed_actions: Mapping[int, str] = dc_field(default_factory=dict)
    coverage_complete: bool = True
    synthetic: bool = True

    def __post_init__(self) -> None:
        _identifier(self.version, "rules version")
        _int(self.horizon, "horizon", 0)
        object.__setattr__(self, "fields", frozenset(self.fields))
        for name in self.fields:
            _identifier(name, "declared field")
        object.__setattr__(self, "actions", tuple(self.actions))
        object.__setattr__(self, "stages", tuple(self.stages))
        object.__setattr__(self, "fixed_actions", dict(self.fixed_actions))
        ids = [a.id for a in self.actions]
        if len(ids) != len(set(ids)) or "__wait__" in ids:
            raise ValueError("Action IDs must be unique; __wait__ is reserved")
        stage_ids = [s.id for s in self.stages]
        if len(stage_ids) != len(set(stage_ids)):
            raise ValueError("Stage IDs must be unique")
        for slot, action_id in self.fixed_actions.items():
            _identifier(action_id, "fixed action ID")
            _int(slot, "fixed action slot", 0)
            if slot >= self.horizon:
                raise ValueError("Fixed action lies outside the horizon")


@dataclass(frozen=True)
class Constraints:
    visit_caps: Mapping[str, int] = dc_field(default_factory=dict)
    pinned_actions: Mapping[int, str] = dc_field(default_factory=dict)

    def __post_init__(self) -> None:
        for stage, cap in self.visit_caps.items():
            _identifier(stage, "capped stage")
            _int(cap, "visit cap", 0)
        for slot, action_id in self.pinned_actions.items():
            _int(slot, "pinned action slot", 0)
            _identifier(action_id, "pinned action ID")
        object.__setattr__(self, "visit_caps", dict(self.visit_caps))
        object.__setattr__(self, "pinned_actions", dict(self.pinned_actions))


@dataclass(frozen=True)
class Goal:
    field: str
    target: int
    priority: int = 0

    def __post_init__(self) -> None:
        _identifier(self.field, "goal field")
        _int(self.target, "goal target")
        _int(self.priority, "goal priority")


class RuleViolation(ValueError):
    def __init__(self, code: str, message: str = "") -> None:
        self.code = code
        super().__init__(message or code)


@dataclass(frozen=True)
class Transition:
    state: Snapshot
    assumptions: tuple[str, ...] = ()


@dataclass(frozen=True)
class Step:
    instance_id: str
    action_id: str
    slot: int

    def __post_init__(self) -> None:
        _identifier(self.instance_id, "instance ID")
        _identifier(self.action_id, "step action ID")
        _int(self.slot, "step slot", 0)


@dataclass(frozen=True)
class Plan:
    run_id: str
    rules_version: str
    state_revision: int
    steps: tuple[Step, ...]
    forecast: Snapshot
    status: str
    explanations: tuple[str, ...]
    expanded: int
    plan_id: str = ""


def _wait(slot: int) -> Action:
    return Action("__wait__", frozenset({slot}))


def _check_context(rules: Ruleset, state: Snapshot, constraints: Constraints) -> None:
    if state.rules_version != rules.version:
        raise RuleViolation("rules_version_mismatch")
    if state.slot > rules.horizon:
        raise RuleViolation("outside_horizon")
    if any(name not in rules.fields for name in state.values):
        raise RuleViolation("unsupported_field", "State contains a field absent from the ruleset")
    known_stages = {s.id for s in rules.stages}
    if any(name not in known_stages for name in constraints.visit_caps):
        raise RuleViolation("unsupported_stage", "A visit cap refers to an unknown stage")
    if any(slot >= rules.horizon for slot in constraints.pinned_actions):
        raise RuleViolation("outside_horizon", "Pinned action lies outside the horizon")


def _required_action(rules: Ruleset, slot: int, constraints: Constraints) -> str | None:
    fixed = rules.fixed_actions.get(slot)
    pinned = constraints.pinned_actions.get(slot)
    if fixed is not None and pinned is not None and fixed != pinned:
        raise RuleViolation("conflicting_pin", f"Slot {slot} conflicts with its fixed action")
    required = fixed if fixed is not None else pinned
    if required is not None and required != "__wait__" and not any(a.id == required for a in rules.actions):
        raise RuleViolation("unsupported_action", f"Missing required action: {required}")
    return required


def transition(rules: Ruleset, state: Snapshot, action: Action,
               constraints: Constraints) -> Transition:
    """Pure, one-slot forecast. Uncertain prerequisites are explicit assumptions.

    Costs are deducted before rewards: an action cannot fund its own upfront cost.
    Dungeon visits never imply earnings or floor gains. Those require explicit
    modeled effects or a later player-confirmed observation in the separate ledger.
    """
    _check_context(rules, state, constraints)
    if state.slot >= rules.horizon:
        raise RuleViolation("outside_horizon")
    if action.id == "__wait__":
        if action != _wait(state.slot):
            raise RuleViolation("unsupported_action", "Invalid implicit wait action")
    else:
        registered = next((a for a in rules.actions if a.id == action.id), None)
        if registered is None or registered != action:
            raise RuleViolation("unsupported_action", "Action is not the registered rule")
        if not action.source or action.verification not in {"verified", "corroborated", "synthetic"}:
            raise RuleViolation("unsupported_rule", f"Action {action.id} lacks verified rules")
        if action.verification == "synthetic" and not rules.synthetic:
            raise RuleViolation("unsupported_rule", "Synthetic action is not a real game rule")
    if state.slot not in action.slots:
        raise RuleViolation("unavailable", f"Action {action.id} unavailable at slot {state.slot}")
    required = _required_action(rules, state.slot, constraints)
    if required is not None and required != action.id:
        raise RuleViolation("required_action", f"Slot {state.slot} requires {required}")
    action_fields = (set(action.requirements) | set(action.maximums) | set(action.costs)
                     | set(action.effects) | set(action.uncertain_effects))
    if not action_fields <= rules.fields:
        raise RuleViolation("unsupported_field", "Action references an unmodeled field")
    values = dict(state.values)
    visits = dict(state.visits)
    assumptions: list[str] = []
    if action.dungeon_visit:
        active = [s for s in rules.stages if s.start <= state.slot < s.end]
        if len(active) != 1:
            raise RuleViolation("unsupported_stage", "Exactly one active unlock stage is required")
        stage = active[0]
        if action.target_floor is not None and action.target_floor > stage.floor_ceiling:
            raise RuleViolation("floor_locked")
        used = visits.get(stage.id)
        if stage.id in constraints.visit_caps:
            if used is None:
                raise RuleViolation("unknown_visit_count", f"Stage {stage.id} prior visit count is unknown")
            if used >= constraints.visit_caps[stage.id]:
                raise RuleViolation("visit_cap", f"Stage {stage.id} visit cap reached")
        # Charge the active time window, never the target floor's historical stage.
        visits[stage.id] = None if used is None else used + 1
    thresholds = dict(action.requirements)
    for name, cost in action.costs.items():
        thresholds[name] = max(thresholds.get(name, cost), cost)
    for name, threshold in sorted(thresholds.items()):
        current = values.get(name, Interval.unknown())
        if current.high is not None and current.high < threshold:
            raise RuleViolation("requirement", f"{name} cannot meet {threshold}")
        if current.low is None or current.low < threshold:
            assumptions.append(f"slot {state.slot}: {name} must be at least {threshold} before {action.id}")
            values[name] = Interval(threshold, current.high)
    for name, maximum in sorted(action.maximums.items()):
        current = values.get(name, Interval.unknown())
        if current.low is not None and current.low > maximum:
            raise RuleViolation("requirement", f"{name} exceeds the allowed maximum {maximum}")
        if current.high is None or current.high > maximum:
            assumptions.append(f"slot {state.slot}: {name} must be at most {maximum} before {action.id}")
            values[name] = Interval(current.low, maximum)
    for name, cost in action.costs.items():
        values[name] = values.get(name, Interval.unknown()).plus(Interval.exact(-cost))
    for name, amount in action.effects.items():
        values[name] = values.get(name, Interval.unknown()).plus(Interval.exact(amount))
    for name, amount in action.uncertain_effects.items():
        values[name] = values.get(name, Interval.unknown()).plus(amount)
    return Transition(Snapshot(state.run_id, state.rules_version, state.revision,
                               state.slot + 1, values, visits), tuple(assumptions))


def validate_plan(rules: Ruleset, initial: Snapshot, steps: Sequence[Step],
                  constraints: Constraints) -> Snapshot:
    """Replay from observed input without trusting the search's stored forecast.

    This checks temporal/action legality, not instance authenticity or whether
    assumptions are observed facts. A ledger must bind the plan ID/revision,
    reject conditional execution without actual outcomes, and check event IDs.
    """
    _check_context(rules, initial, constraints)
    state = Snapshot(initial.run_id, initial.rules_version, initial.revision,
                     initial.slot, initial.values, initial.visits)
    seen: set[str] = set()
    by_id = {a.id: a for a in rules.actions}
    for step in steps:
        if not step.instance_id or step.instance_id in seen:
            raise RuleViolation("duplicate_instance")
        seen.add(step.instance_id)
        if step.slot != state.slot:
            raise RuleViolation("wrong_slot")
        action = _wait(step.slot) if step.action_id == "__wait__" else by_id.get(step.action_id)
        if action is None:
            raise RuleViolation("unsupported_action")
        state = transition(rules, state, action, constraints).state
    return state


@dataclass(frozen=True)
class _Node:
    state: Snapshot
    steps: tuple[Step, ...] = ()
    assumptions: tuple[str, ...] = ()


def _request_id(rules: Ruleset, state: Snapshot, goals: Sequence[Goal], constraints: Constraints) -> str:
    # Version identifies the rules content. Callers must bump it after any rule edit.
    data = {
        "run": state.run_id, "rules": rules.version, "revision": state.revision,
        "slot": state.slot, "values": {k: [v.low, v.high] for k, v in state.values.items()},
        "visits": dict(state.visits), "goals": sorted((g.priority, g.field, g.target) for g in goals),
        "caps": dict(constraints.visit_caps), "pins": dict(constraints.pinned_actions),
    }
    return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(",", ":")).encode()).hexdigest()[:24]


def _guaranteed(state: Snapshot, goals: Sequence[Goal]) -> bool:
    return all((v := state.values.get(g.field, Interval.unknown())).low is not None
               and v.low >= g.target for g in goals)


def _possible(state: Snapshot, goals: Sequence[Goal]) -> bool:
    return all((v := state.values.get(g.field, Interval.unknown())).high is None
               or v.high >= g.target for g in goals)


def _score(node: _Node, goals: Sequence[Goal]) -> tuple:
    scores: list[int] = []
    for priority in sorted({g.priority for g in goals}, reverse=True):
        group = [g for g in goals if g.priority == priority]
        bounds = [(g, node.state.values.get(g.field, Interval.unknown()).low) for g in group]
        scores.extend((sum(low is not None and low >= g.target for g, low in bounds),
                       sum(min(g.target, low) if low is not None else -(10**30) for g, low in bounds)))
    # Prefer unconditional forecasts when goal progress is otherwise tied.
    return tuple(scores) + (-len(node.assumptions),)


def _sorted(nodes: Sequence[_Node], goals: Sequence[Goal]) -> list[_Node]:
    # Two stable sorts make action-ID ties deterministic without hash/set order.
    result = sorted(nodes, key=lambda n: tuple(s.action_id for s in n.steps))
    return sorted(result, key=lambda n: _score(n, goals), reverse=True)


def plan(rules: Ruleset, state: Snapshot, goals: Sequence[Goal], constraints: Constraints,
         beam_width: int = 128, max_expansions: int = 10000) -> Plan:
    """Bounded deterministic beam search, always followed by an independent replay.

    `feasible_within_model` is a witness, never a claim of optimality. An incomplete
    ruleset or uncertain prerequisite produces `conditional`. Missing rules are
    `unsupported`; pruning is `unknown`; budget exhaustion is `search_limit`.
    Only exhaustive failure under complete supported rules can be proven.
    """
    _int(beam_width, "beam_width", 1)
    _int(max_expansions, "max_expansions", 0)
    goals = tuple(goals)
    token = _request_id(rules, state, goals, constraints)
    initial = _Node(state)
    expanded = 0

    def result(node: _Node, status: str, explanations: Sequence[str]) -> Plan:
        replay = validate_plan(rules, state, node.steps, constraints)
        if replay != node.state:
            raise AssertionError("Search forecast disagrees with independent replay")
        content = json.dumps({"request": token, "steps": [(s.slot, s.action_id) for s in node.steps]},
                             sort_keys=True, separators=(",", ":"))
        plan_id = hashlib.sha256(content.encode()).hexdigest()[:24]
        return Plan(state.run_id, rules.version, state.revision, node.steps, replay,
                    status, tuple(dict.fromkeys(explanations)), expanded, plan_id)

    try:
        _check_context(rules, state, constraints)
    except RuleViolation as error:
        # Invalid/missing input cannot support a proof of infeasibility.
        return Plan(state.run_id, rules.version, state.revision, (), state,
                    "unsupported", (str(error),), 0, token)
    if any(g.field not in rules.fields for g in goals):
        return result(initial, "unsupported", ("A goal field has no modeled rules",))
    frontier = [initial]
    best = initial
    pruned = False
    limited = False
    unsupported: list[str] = []
    unknown_inputs: list[str] = []
    conditional_seen = False
    final: list[_Node] = frontier if state.slot == rules.horizon else []
    for slot in range(state.slot, rules.horizon):
        candidates: list[_Node] = []
        try:
            required = _required_action(rules, slot, constraints)
        except RuleViolation as error:
            if error.code.startswith("unsupported"):
                unsupported.append(str(error))
            frontier = []
            break
        available = sorted((a for a in rules.actions if slot in a.slots), key=lambda a: a.id)
        available.append(_wait(slot))
        if required is not None:
            available = [a for a in available if a.id == required]
        for node in frontier:
            for action in available:
                if expanded >= max_expansions:
                    limited = True
                    break
                expanded += 1
                try:
                    changed = transition(rules, node.state, action, constraints)
                except RuleViolation as error:
                    if error.code.startswith("unsupported"):
                        unsupported.append(str(error))
                    elif error.code.startswith("unknown"):
                        unknown_inputs.append(str(error))
                    continue
                conditional_seen |= bool(changed.assumptions)
                identity = f"{token}:{slot}:{action.id}"
                step = Step(identity, action.id, slot)
                candidates.append(_Node(changed.state, node.steps + (step,),
                                        node.assumptions + changed.assumptions))
            if limited:
                break
        ordered = _sorted(candidates, goals)
        if ordered:
            best = ordered[0]
        if slot + 1 == rules.horizon:
            final = ordered
        if limited or not ordered:
            frontier = ordered
            break
        if slot + 1 < rules.horizon and len(ordered) > beam_width:
            pruned = True
        frontier = ordered[:beam_width]
    guaranteed = [n for n in final if _guaranteed(n.state, goals) and not n.assumptions]
    if guaranteed:
        chosen = _sorted(guaranteed, goals)[0]
        if not rules.coverage_complete:
            return result(chosen, "conditional", ("Rule coverage is incomplete; this is only a candidate",))
        return result(chosen, "feasible_within_model", ("All goals verified within the supplied model; optimality is not claimed",))
    conditional = [n for n in final if _possible(n.state, goals)]
    if conditional:
        chosen = _sorted(conditional, goals)[0]
        reasons = list(chosen.assumptions)
        if not _guaranteed(chosen.state, goals):
            reasons.append("Goal attainment depends on unknown or uncertain outcomes")
        if not rules.coverage_complete:
            reasons.append("Rule coverage is incomplete")
        return result(chosen, "conditional", reasons)
    if limited:
        return result(best, "search_limit", ("Expansion budget reached; no impossibility proof",))
    if unsupported:
        return result(best, "unsupported", unsupported)
    if pruned:
        return result(best, "unknown", ("Beam pruning discarded alternatives; no impossibility proof",))
    if unknown_inputs:
        return result(best, "unknown", unknown_inputs)
    if not rules.coverage_complete or conditional_seen:
        return result(best, "unknown", ("Incomplete rules or uncertain prerequisites prevent a proof",))
    return result(best, "proven_infeasible", ("Exhaustive search found no schedule meeting all goals within this model and horizon",))
