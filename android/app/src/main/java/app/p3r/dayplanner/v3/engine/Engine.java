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
        /** Soft wall-clock budget for the rollout phase; the plan is always complete. */
        public long timeBudgetMillis = 4000;
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
     * Apply a player-confirmed outcome for {@code action} taken at {@code state.slot} and advance
     * one slot. Used by the store to derive the observed state; visible values in the outcome
     * override model values, hidden values are updated as estimates.
     */
    public static SimState applyOutcome(Rules rules, Profile profile, SimState state, Action action, Outcome outcome) {
        return Simulator.applyOutcome(rules, profile, state, action, outcome);
    }

    /** Plan every slot from {@code observed.slot} to the end of the calendar. */
    public static Plan plan(Rules rules, Profile profile, SimState observed, Plan previous, Options options) {
        return Planner.plan(rules, profile, observed, previous, options == null ? new Options() : options);
    }

    /** Independent replay check; empty list = valid. Messages are developer-facing (English). */
    public static List<String> validate(Rules rules, Profile profile, SimState observed, Plan plan) {
        return PlanValidator.validate(rules, profile, observed, plan);
    }
}
