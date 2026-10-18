package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.List;

/**
 * Public facade of the v3 planning engine (docs/DESIGN_v3.md §5). Pure Java + org.json; no
 * Android framework classes, so it runs in plain JVM unit tests and on a background thread.
 *
 * <p>All methods are deterministic and never mutate their {@code SimState} arguments.
 */
public final class Engine {
    private Engine() {}

    public static final class Options {
        /**
         * Soft wall-clock budget for the rollout phase; the plan is always complete. Default 2.5 s
         * (DESIGN §10.6). 0 disables the rollout (pure base policy).
         */
        public long timeBudgetMillis = 2500;
        /** Rollout candidates per slot. */
        public int candidates = 4;
        /** Optional progress callback (0..1), may be null. Called from the planning thread. */
        public Progress progress;
        /** Optional cancellation flag polled between slots. */
        public java.util.concurrent.atomic.AtomicBoolean cancelled;
        /** Forecast cash gained per Tartarus visit (estimate from the player's own records), default 0. */
        public int towerIncomePerVisit = 0;
        /**
         * This run is the one follow-up the service queues after a plan that ran out of time
         * ({@link Plan#budgetExhausted}): the plan is marked {@link Plan#followUp}, and if it runs out
         * too, its summary note promises no further automatic run. Does not change the plan itself.
         */
        public boolean followUp;
        /** Slot of the latest recorded Tartarus evening, for the 保健室 reminder; -1 when none. */
        public int lastTowerSlot = -1;
        /**
         * Test hook (package-private): when set, receives the end rank of every link for each
         * complete plan the planner compared (screened, fortified and strict-priority runs).
         */
        java.util.List<int[]> trace;
    }

    public interface Progress { void onProgress(float fraction); }

    /** Result of applying one action in the forecast. */
    public static final class Applied {
        public SimState state;
        public Plan.Predicted predicted;
        public boolean estimate;
        public List<String> assumptions;
        /** Side steps done in this slot before the action ("unlock:devil:0"), in order; empty if none. */
        public List<String> side;
    }

    /**
     * Actions offered for recording at {@code state.slot} (empty if locked). INVITE choices are
     * conditional on actually receiving the day's SMS; the automatic planner never uses them.
     */
    public static List<Action> legalActions(Rules rules, Profile profile, SimState state) {
        return Simulator.legalActions(rules, profile, state);
    }

    /** Forecast transition: returns a new state at {@code state.slot + 1}. */
    public static Applied apply(Rules rules, Profile profile, SimState state, Action action) {
        return Simulator.apply(rules, profile, state, action);
    }

    /**
     * Forecast transition using engine options (currently {@link Options#towerIncomePerVisit}, which
     * adds the estimated income of a Tartarus visit).
     */
    public static Applied apply(Rules rules, Profile profile, SimState state, Action action, Options options) {
        return Simulator.apply(rules, profile, state, action, options);
    }

    /**
     * Advance a copy of {@code state} to {@code slot} without acting (slots are skipped, day rollovers
     * apply story ranks, rescue deadlines and date-based unlocks). Returns a copy even when no slot is
     * skipped.
     */
    public static SimState advanceTo(Rules rules, Profile profile, SimState state, int slot) {
        return Simulator.advanceTo(rules, profile, state, slot);
    }

    /**
     * Apply a player-confirmed outcome for {@code action} taken at {@code state.slot} and advance
     * one slot. Used by the store to derive the observed state; visible values in the outcome
     * override model values (a recorded {@link Outcome#rank} is kept whatever the profile says,
     * also for {@link Outcome.Code#AS_PREDICTED}), hidden values are updated as estimates. Side
     * steps follow {@link Outcome#sideKeys} when given (see {@link #sideSteps}).
     */
    public static SimState applyOutcome(Rules rules, Profile profile, SimState state, Action action, Outcome outcome) {
        return Simulator.applyOutcome(rules, profile, state, action, outcome);
    }

    /** Raw affinity to snapshot when confirming a dorm activity; no action or time is applied. */
    public static int activityAffinityGain(Rules rules, SimState state, int activity) {
        return activity < 0 || activity >= rules.activities.length ? 0
                : Kernel.activityAffinityGain(rules, state, rules.activities[activity]);
    }

    /**
     * The side steps of {@code state.slot} (pending non-consuming unlock steps of wanted links, see
     * {@link Plan.Step#side}) applied to a copy of {@code state} as if {@code action} were taken
     * there; the copy stays at the same slot and nothing else changes. For records that do not go
     * through {@link #applyOutcome}, e.g. a Tartarus visit recorded by the store: apply this first,
     * then the record. {@code applyOutcome} already applies side steps itself.
     */
    public static SimState applySideSteps(Rules rules, Profile profile, SimState state, Action action) {
        return Simulator.applySideSteps(rules, profile, state, action);
    }

    /** Result of {@link #sideSteps}. */
    public static final class SideSteps {
        /** State after the side steps, still at the same slot. */
        public SimState state;
        /** Keys of the side steps applied, in order ("unlock:devil:0"); empty if none. */
        public List<String> applied;
        /** Keys asked for that could not be applied as recorded (see {@link #sideSteps}); empty if none. */
        public List<String> rejected;
    }

    /**
     * The side steps of {@code state.slot} beside {@code action}, applied to a copy that stays at
     * the same slot, with the keys that were applied.
     *
     * <ul>
     *   <li>{@code keys == null}: the steps the engine picks for this profile, exactly as
     *       {@link #applySideSteps} and the forecast do (wanted links only, cash reserved for the
     *       main action).</li>
     *   <li>{@code keys != null}: exactly those steps ({@link Action#key} form), in order, as the
     *       player recorded them. The profile does not choose (adding or clearing a goal later
     *       never changes what was done) and the cash is not checked (the fee is paid, cash stays
     *       at least 0). A key is rejected when it is not an unlock step of this pack, is not its
     *       link's pending non-consuming step, its link already took a step in this slot, is the
     *       main unlock action's own link, or the slot's day, kind, prerequisites or minimum gap do
     *       not allow it.</li>
     * </ul>
     */
    public static SideSteps sideSteps(Rules rules, Profile profile, SimState state, Action action, List<String> keys) {
        return Simulator.sideSteps(rules, profile, state, action, keys);
    }

    /**
     * Every recordable action for {@code state.slot} as a labeled {@link Plan.Step} (Chinese
     * title, subtitle, location, reason, how-to and forecast), most useful first: ordered by the
     * objective of taking the action and following the base policy to the end of the calendar. For
     * "改做别的" (the caller marks the planned one); the list is empty when the slot is locked or
     * nothing productive is possible. Possible holiday invitations are appended with an explicit
     * "收到短信才选" condition; they are not guaranteed available or used by automatic planning.
     */
    public static List<Plan.Step> alternatives(Rules rules, Profile profile, SimState state) {
        return Planner.alternatives(rules, profile, state);
    }

    /** Alternatives with the same recorded tower history and income assumptions as normal planning. */
    public static List<Plan.Step> alternatives(Rules rules, Profile profile, SimState state, Options options) {
        return Planner.alternatives(rules, profile, state, options);
    }

    /** Plan every slot from {@code observed.slot} to the end of the calendar. */
    public static Plan plan(Rules rules, Profile profile, SimState observed, Plan previous, Options options) {
        return Planner.plan(rules, profile, observed, previous, options == null ? new Options() : options);
    }

    /** Independent replay check; empty list = valid. Messages are developer-facing (English). */
    public static List<String> validate(Rules rules, Profile profile, SimState observed, Plan plan) {
        return PlanValidator.validate(rules, profile, observed, plan);
    }

    /**
     * Warning-level findings of the independent replay (e.g. a FREE slot while a productive action
     * was legal). They do not make the plan invalid; errors are reported by {@link #validate}.
     */
    public static List<String> validationWarnings(Rules rules, Profile profile, SimState observed, Plan plan) {
        return PlanValidator.check(rules, profile, observed, plan).warnings;
    }
}
