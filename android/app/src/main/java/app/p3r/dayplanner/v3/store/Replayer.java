package app.p3r.dayplanner.v3.store;

import android.util.Log;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the observed state: a checkpoint plus a replay of the rows recorded after it
 * (docs/DESIGN_v3.md §6).
 *
 * <ul>
 *   <li>Voided rows are ignored.</li>
 *   <li>{@link EventKind#OUTCOME} / {@link EventKind#SKIP} rows go through the injected
 *       {@link OutcomeApplier} (production: {@code Engine.applyOutcome}).</li>
 *   <li>{@link EventKind#TOWER} rows count one visit for their block, raise the highest floor and
 *       apply cash exactly once (new balance, or net change). The latest non-voided
 *       {@link EventKind#TOWER_FIX} for a tower row replaces its values; a fix never adds a
 *       visit.</li>
 *   <li>Before each row and at the end the state moves past calendar-locked slots, so
 *       {@code observed.slot} is always the next slot the player can act in.</li>
 *   <li>A row for a later slot than the state's moves time forward without effects (the slots in
 *       between were not recorded, e.g. after an earlier row was voided). A row for an earlier slot
 *       cannot be replayed and is skipped and counted as a problem.</li>
 * </ul>
 *
 * Replay never throws for stored data: a row the applier or the parser rejects is treated like a
 * slot that passed without effect, and counted in {@link Result#problems}.
 *
 * <p>Pure computation, no I/O. Instances are cheap and not thread-safe.
 */
public final class Replayer {
    private static final String TAG = "P3rReplay";

    /** Observer of every replayed row (history summaries, correction checks). */
    public interface Trace {
        /**
         * Called for every observed row in order. Voided rows are reported too, as if applied at
         * their position; they do not change the replayed state.
         *
         * @param row the row
         * @param before state the row was applied to (already at the row's slot)
         * @param after state after the row
         * @param tower effective tower values (after fixes) for tower rows, else null
         */
        void onRow(EventRow row, SimState before, SimState after, TowerRecord tower);
    }

    /** One tower visit whose net cash change is known exactly. */
    public static final class TowerGain {
        public final long seq;
        public final int block;
        public final long net;

        public TowerGain(long seq, int block, long net) {
            this.seq = seq;
            this.block = block;
            this.net = net;
        }
    }

    /** Replay output. */
    public static final class Result {
        /** Observed state, normalised (see {@link StateShape#normalize}). */
        public final SimState state;
        public final List<TowerGain> towerGains;
        /** Rows that could not be applied. */
        public final int problems;

        Result(SimState state, List<TowerGain> towerGains, int problems) {
            this.state = state;
            this.towerGains = Collections.unmodifiableList(towerGains);
            this.problems = problems;
        }
    }

    private final Rules rules;
    private final Profile profile;
    private final OutcomeApplier applier;

    public Replayer(Rules rules, Profile profile, OutcomeApplier applier) {
        this.rules = rules;
        this.profile = profile == null ? new Profile() : profile;
        this.applier = applier;
    }

    /**
     * Replays {@code rows} (one checkpoint's rows in sequence order; other kinds are ignored) on
     * top of {@code checkpoint}.
     */
    public Result replay(SimState checkpoint, List<EventRow> rows, Trace trace) {
        Map<Long, TowerRecord> fixes = effectiveFixes(rows);
        List<TowerGain> gains = new ArrayList<>();
        int problems = 0;
        SimState state = StateShape.normalize(checkpoint, rules);
        for (EventRow row : rows) {
            if (!EventKind.isObserved(row.kind)) {
                continue;
            }
            if (row.voided) {
                traceVoided(state, row, fixes, trace);
                continue;
            }
            try {
                SimState next = applyRow(state, row, fixes, gains, trace);
                if (next == null) {
                    problems++;
                } else {
                    state = next;
                }
            } catch (JSONException | RuntimeException e) {
                Log.w(TAG, "row " + row.seq + " could not be replayed", e);
                problems++;
                int slot = row.slot();
                if (slot >= state.slot) {
                    state = advance(state, slot + 1);
                }
            }
        }
        return new Result(state, gains, problems);
    }

    /**
     * Lets the trace see a voided row as if it had been applied at this point, without changing
     * the replayed state (history lines describe undone records too).
     */
    private void traceVoided(SimState state, EventRow row, Map<Long, TowerRecord> fixes, Trace trace) {
        if (trace == null) {
            return;
        }
        try {
            applyRow(state, row, fixes, new ArrayList<>(), trace);
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "voided row " + row.seq + " cannot be described", e);
        }
    }

    /** Applies one observed row; returns null when the row is for a slot already passed. */
    private SimState applyRow(SimState state, EventRow row, Map<Long, TowerRecord> fixes,
                              List<TowerGain> gains, Trace trace) throws JSONException {
        int slot = row.payload().getInt("slot");
        if (slot < state.slot) {
            Log.w(TAG, "row " + row.seq + " is for slot " + slot + " but the state is at " + state.slot);
            return null;
        }
        SimState before = state;
        if (slot > state.slot) {
            before = state.copy();
            before.slot = slot;
        }
        SimState after;
        TowerRecord tower = null;
        if (EventKind.TOWER.equals(row.kind)) {
            TowerRecord own = TowerRecord.fromJson(row.payload());
            tower = fixes.containsKey(row.seq) ? fixes.get(row.seq).copy() : own;
            tower.slot = own.slot;
            tower.block = own.block;
            after = applyTower(before, tower, row.seq, gains);
        } else {
            OutcomeRecord record = OutcomeRecord.fromJson(row.payload());
            Action action = Action.parse(rules, record.actionKey);
            if (action == null) {
                action = Action.free();
            }
            Outcome outcome = record.outcome;
            if (EventKind.SKIP.equals(row.kind)) {
                outcome.code = Outcome.Code.SKIPPED;
            }
            after = applier.apply(rules, profile, before.copy(), action, outcome);
        }
        after = advance(after, slot + 1);
        if (trace != null) {
            trace.onRow(row, before, after, tower);
        }
        return after;
    }

    /** One Tartarus visit: +1 visit, floor = max, cash once, rescue bit. Never mutates {@code before}. */
    private SimState applyTower(SimState before, TowerRecord rec, long seq, List<TowerGain> gains) {
        SimState s = before.copy();
        if (rec.block >= 0 && rec.block < s.towerVisits.length) {
            s.towerVisits[rec.block]++;
        }
        s.floor = Math.max(s.floor, rec.floor);
        if (rec.balanceMode) {
            if (before.cashExact) {
                gains.add(new TowerGain(seq, rec.block, rec.amount - before.cash));
            }
            s.cash = rec.amount;
            s.cashExact = true;
        } else {
            gains.add(new TowerGain(seq, rec.block, rec.amount));
            s.cash = Math.max(0, before.cash + rec.amount);
        }
        int rescue = rescueIndex(rec.rescuedId);
        if (rescue >= 0) {
            s.rescued |= 1L << rescue;
            s.rescueFailed &= ~(1L << rescue);
        }
        s.slot = rec.slot + 1;
        return s;
    }

    private int rescueIndex(String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < rules.rescues.length && i < 64; i++) {
            if (id.equals(rules.rescues[i].id)) {
                return i;
            }
        }
        return -1;
    }

    /** Moves the state to {@code slot}, then past locked slots; re-fits array sizes. */
    private SimState advance(SimState state, int slot) {
        SimState out = StateShape.fit(state, rules);
        out.slot = StateShape.firstOpenSlot(rules, slot);
        return out;
    }

    /** Target seq → values of the latest non-voided fix for that target. */
    private static Map<Long, TowerRecord> effectiveFixes(List<EventRow> rows) {
        Map<Long, TowerRecord> fixes = new HashMap<>();
        for (EventRow row : rows) {
            if (row.voided || !EventKind.TOWER_FIX.equals(row.kind)) {
                continue;
            }
            try {
                fixes.put(row.payload().getLong("target"),
                        TowerRecord.fromJson(row.payload().getJSONObject("tower")));
            } catch (JSONException e) {
                Log.w(TAG, "tower fix " + row.seq + " is malformed", e);
            }
        }
        return fixes;
    }
}
