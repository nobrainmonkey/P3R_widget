package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.List;

/** Forecast transitions and legality (docs/DESIGN_v3.md §5.2). STUB — implemented by the engine module. */
final class Simulator {
    private Simulator() {}

    static List<Action> legalActions(Rules rules, Profile profile, SimState state) {
        throw new UnsupportedOperationException("engine not implemented yet");
    }

    static Engine.Applied apply(Rules rules, Profile profile, SimState state, Action action) {
        throw new UnsupportedOperationException("engine not implemented yet");
    }

    static SimState applyOutcome(Rules rules, Profile profile, SimState state, Action action, Outcome outcome) {
        throw new UnsupportedOperationException("engine not implemented yet");
    }
}
