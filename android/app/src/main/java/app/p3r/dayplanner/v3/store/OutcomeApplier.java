package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * The engine operations replay needs to derive the observed state. Production uses
 * {@link #ENGINE}; tests may substitute a deterministic fake. No method may mutate its
 * {@code state} argument.
 */
public interface OutcomeApplier {
    /** Applies one player-confirmed outcome at {@code state.slot}; returns the state at {@code state.slot + 1}. */
    SimState apply(Rules rules, Profile profile, SimState state, Action action, Outcome outcome);

    /**
     * Moves time forward to {@code slot} without acting. Production ({@link Engine#advanceTo})
     * applies every day rollover on the way (story ranks, rescue deadlines, date-based unlocks),
     * exactly like {@link #apply} does when it moves one slot. The default only moves the slot.
     */
    default SimState advanceTo(Rules rules, Profile profile, SimState state, int slot) {
        SimState out = state.copy();
        out.slot = Math.max(state.slot, slot);
        return out;
    }

    /**
     * The side steps of {@code state.slot} (non-consuming unlock steps done "顺便") for a record
     * that does not go through {@link #apply}, i.e. a Tartarus visit; the result stays at the same
     * slot. Production uses {@link Engine#applySideSteps}; the default applies none.
     */
    default SimState applySideSteps(Rules rules, Profile profile, SimState state, Action action) {
        return state.copy();
    }

    /** {@link Engine#applyOutcome}, {@link Engine#advanceTo} and {@link Engine#applySideSteps}. */
    OutcomeApplier ENGINE = new OutcomeApplier() {
        @Override
        public SimState apply(Rules rules, Profile profile, SimState state, Action action, Outcome outcome) {
            return Engine.applyOutcome(rules, profile, state, action, outcome);
        }

        @Override
        public SimState advanceTo(Rules rules, Profile profile, SimState state, int slot) {
            return Engine.advanceTo(rules, profile, state, slot);
        }

        @Override
        public SimState applySideSteps(Rules rules, Profile profile, SimState state, Action action) {
            return Engine.applySideSteps(rules, profile, state, action);
        }
    };
}
