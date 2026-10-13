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

    /** Legal productive actions for {@code state.slot} (empty if the slot is locked). */
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
     * override model values, hidden values are updated as estimates.
     */
    public static SimState applyOutcome(Rules rules, Profile profile, SimState state, Action action, Outcome outcome) {
        return Simulator.applyOutcome(rules, profile, state, action, outcome);
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

    /**
     * Every legal productive action for {@code state.slot} as a labeled {@link Plan.Step} (Chinese
     * title, subtitle, location, reason and forecast), best first. For "改做别的"; the list is empty
     * when the slot is locked or nothing productive is possible.
     */
    public static List<Plan.Step> alternatives(Rules rules, Profile profile, SimState state) {
        return Planner.alternatives(rules, profile, state);
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
