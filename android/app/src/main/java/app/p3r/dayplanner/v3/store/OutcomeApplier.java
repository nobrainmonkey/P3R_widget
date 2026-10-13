package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Applies one player-confirmed outcome to the observed state during replay. Must not mutate
 * {@code state}; returns the state at {@code state.slot + 1}. Production uses {@link #ENGINE}.
 */
public interface OutcomeApplier {
    SimState apply(Rules rules, Profile profile, SimState state, Action action, Outcome outcome);

    /** {@link Engine#applyOutcome}. */
    OutcomeApplier ENGINE = Engine::applyOutcome;
}
