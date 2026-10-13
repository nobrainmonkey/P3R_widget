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
 *   <li>{@link EventKind#TOWER} rows first apply the slot's side steps (unless the record says
 *       they were not done), then count one visit for the block of that day, raise the highest
 *       floor, apply cash exactly once (new balance, or net change) and set the rescue bit. The
 *       latest non-voided {@link EventKind#TOWER_FIX} for a tower row replaces its values; a fix
 *       never adds a visit.</li>
 *   <li>Time only moves forward through {@link OutcomeApplier#advanceTo}, so every day rollover
 *       (story ranks, rescue deadlines, date-based unlocks) happens exactly as in the engine,
 *       whether the day ends with an outcome, a tower visit, a gap or locked slots.</li>
 *   <li>Each row is applied at its own slot, as long as that slot is not before the previous
 *       row's. Locked slots are skipped only after the last row, so a record stays in effect even
 *       when a later rule pack locks the slot it was recorded in. A row for a later slot moves time
 *       forward first (the slots in between were not recorded, e.g. after an earlier row was
 *       voided). A row for an earlier slot than the previous row's cannot be replayed; it is
 *       skipped and counted as a problem.</li>
 *   <li>Ids written with a row are trusted over its positions: a tower visit counts for the block
 *       the current pack gives its day ({@code tower:<index>} keys too), and an
 *       {@code unlock:<link>:<index>} key with a {@code stepId} is renumbered by that id.</li>
 * </ul>
 *
 * Replay never throws for stored data. A row the applier or the parser rejects is treated like a
 * slot that passed without effect; such rows, rows for passed slots, action keys the current pack
 * no longer knows and tower visits on a day without a block are counted in
 * {@link Result#problems} (the service then suggests 同步现状).
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
         * @param after state after the row (at the row's slot + 1)
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
        /** Rows that could not be applied as recorded (see the class comment). */
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

    /** Mutable state of one replay. */
    private static final class Run {
        final Map<Long, TowerRecord> fixes;
        final List<TowerGain> gains = new ArrayList<>();
        final Trace trace;
        int problems;

        Run(Map<Long, TowerRecord> fixes, Trace trace) {
            this.fixes = fixes;
            this.trace = trace;
        }
    }

    /**
     * Replays {@code rows} (one checkpoint's rows in sequence order; other kinds are ignored) on
     * top of {@code checkpoint} (already carried over to the current pack, see
     * {@link CheckpointRow#stateFor}).
     */
    public Result replay(SimState checkpoint, List<EventRow> rows, Trace trace) {
        Run run = new Run(effectiveFixes(rows), trace);
        SimState state = StateShape.fit(checkpoint, rules);
        for (EventRow row : rows) {
            if (!EventKind.isObserved(row.kind)) {
                continue;
            }
            if (row.voided) {
                traceVoided(state, row, run);
                continue;
            }
            try {
                SimState next = applyRow(state, row, run, run.trace);
                if (next == null) {
                    run.problems++;
                } else {
                    state = next;
                }
            } catch (JSONException | RuntimeException e) {
                Log.w(TAG, "row " + row.seq + " could not be replayed", e);
                run.problems++;
                int slot = row.slot();
                if (slot >= state.slot) {
                    state = advance(state, slot + 1);
                }
            }
        }
        state = advance(state, StateShape.firstOpenSlot(rules, state.slot));
        return new Result(state, run.gains, run.problems);
    }

    /**
     * Lets the trace see a voided row as if it had been applied at this point, without changing
     * the replayed state or the counts (history lines describe undone records too).
     */
    private void traceVoided(SimState state, EventRow row, Run run) {
        if (run.trace == null) {
            return;
        }
        try {
            Run scratch = new Run(run.fixes, run.trace);
            applyRow(state, row, scratch, run.trace);
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "voided row " + row.seq + " cannot be described", e);
        }
    }

    /** Applies one observed row; returns null when the row is for a slot already passed. */
    private SimState applyRow(SimState state, EventRow row, Run run, Trace trace) throws JSONException {
        int slot = row.payload().getInt("slot");
        if (slot < state.slot) {
            Log.w(TAG, "row " + row.seq + " is for slot " + slot + " but the state is at " + state.slot);
            return null;
        }
        SimState before = slot > state.slot ? advance(state, slot) : state;
        SimState after;
        TowerRecord tower = null;
        if (EventKind.TOWER.equals(row.kind)) {
            TowerRecord own = TowerRecord.fromJson(row.payload());
            tower = run.fixes.containsKey(row.seq) ? run.fixes.get(row.seq).copy() : own;
            tower.slot = own.slot;
            tower.block = blockOf(own.slot, own.block, run);
            tower.sideDone = own.sideDone;
            after = applyTower(before, tower, row.seq, run.gains);
        } else {
            OutcomeRecord record = OutcomeRecord.fromJson(row.payload());
            Action action = resolve(record, slot, run);
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

    /**
     * The action of a record under the current pack: unknown keys become a free slot (counted),
     * Tartarus keys use the block of the slot's day and unlock keys follow their step id.
     */
    private Action resolve(OutcomeRecord record, int slot, Run run) {
        Action action = Action.parse(rules, record.actionKey);
        if (action == null) {
            Log.w(TAG, "action " + record.actionKey + " is unknown to this rule pack");
            run.problems++;
            return Action.free();
        }
        switch (action.type) {
            case TARTARUS: {
                int block = blockOf(slot, action.target, run);
                return block >= 0 ? Action.tower(block) : Action.free();
            }
            case UNLOCK_STEP: {
                if (record.stepId == null) {
                    return action;
                }
                int step = stepIndex(rules.links[action.target], record.stepId);
                if (step < 0) {
                    Log.w(TAG, "unlock step " + record.stepId + " is unknown to this rule pack");
                    run.problems++;
                    return Action.free();
                }
                return Action.unlock(action.target, step);
            }
            default:
                return action;
        }
    }

    /**
     * Block of a tower visit: the one the current pack gives the visit's day; without one, the
     * stored index when it still exists. -1 (and a counted problem) when neither applies.
     */
    private int blockOf(int slot, int stored, Run run) {
        Rules.Block block = rules.blockOn(Rules.dayOf(slot));
        if (block != null) {
            return block.idx;
        }
        run.problems++;
        return stored >= 0 && stored < rules.blocks.length ? stored : -1;
    }

    private static int stepIndex(Rules.Link link, String stepId) {
        if (link.unlock == null) {
            return -1;
        }
        for (int k = 0; k < link.unlock.steps.size(); k++) {
            if (stepId.equals(link.unlock.steps.get(k).id)) {
                return k;
            }
        }
        return -1;
    }

    /**
     * One Tartarus visit at {@code before.slot}: side steps (unless not done), +1 visit, floor =
     * max, cash once, rescue bit. Stays at the visit's slot. Never mutates {@code before}.
     */
    private SimState applyTower(SimState before, TowerRecord rec, long seq, List<TowerGain> gains) {
        Action visit = rec.block >= 0 ? Action.tower(rec.block) : Action.free();
        SimState s = rec.sideDone
                ? StateShape.fit(applier.applySideSteps(rules, profile, before, visit), rules)
                : before.copy();
        s.slot = before.slot;
        long cashBefore = s.cash;
        boolean exactBefore = s.cashExact;
        if (rec.block >= 0 && rec.block < s.towerVisits.length) {
            s.towerVisits[rec.block]++;
        }
        s.floor = Math.max(s.floor, rec.floor);
        if (rec.balanceMode) {
            if (exactBefore) {
                gains.add(new TowerGain(seq, rec.block, rec.amount - cashBefore));
            }
            s.cash = rec.amount;
            s.cashExact = true;
        } else {
            gains.add(new TowerGain(seq, rec.block, rec.amount));
            s.cash = Math.max(0, cashBefore + rec.amount);
        }
        int rescue = rescueIndex(rec.rescuedId);
        if (rescue >= 0) {
            s.rescued |= 1L << rescue;
            s.rescueFailed &= ~(1L << rescue);
        }
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

    /**
     * Moves the state forward to {@code slot} through the applier (day rollovers included) and
     * re-fits array sizes; never moves it back.
     */
    private SimState advance(SimState state, int slot) {
        SimState moved = slot > state.slot ? applier.advanceTo(rules, profile, state, slot) : state;
        SimState out = StateShape.fit(moved, rules);
        out.slot = Math.max(slot, moved.slot);
        return out;
    }

    /** Target seq → values of the latest non-voided fix for that target. */
    static Map<Long, TowerRecord> effectiveFixes(List<EventRow> rows) {
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
