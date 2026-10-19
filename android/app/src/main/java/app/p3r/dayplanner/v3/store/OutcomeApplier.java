package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Collections;
import java.util.List;

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

    /**
     * The side steps of {@code state.slot} beside {@code action}, with the keys applied (see
     * {@link Engine#sideSteps}): exactly {@code keys} when given (legality-checked; the rest are
     * listed as rejected), otherwise the ones the engine picks for {@code profile}. The result
     * stays at the same slot. Used when recording (to store what was done) and when replaying a
     * record that stored them. The default applies {@link #applySideSteps} for {@code null} (and
     * cannot name the keys) and nothing for a given list.
     */
    default Engine.SideSteps sideSteps(Rules rules, Profile profile, SimState state, Action action, List<String> keys) {
        Engine.SideSteps out = new Engine.SideSteps();
        out.state = keys == null ? applySideSteps(rules, profile, state, action) : state.copy();
        out.applied = Collections.emptyList();
        out.rejected = Collections.emptyList();
        return out;
    }

    /** {@link Engine#applyOutcome}, {@link Engine#advanceTo}, {@link Engine#applySideSteps} and {@link Engine#sideSteps}. */
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

        @Override
        public Engine.SideSteps sideSteps(Rules rules, Profile profile, SimState state, Action action,
                                          List<String> keys) {
            return Engine.sideSteps(rules, profile, state, action, keys);
        }
    };
}
