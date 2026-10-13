package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/** Fortified rollout planner (docs/DESIGN_v3.md §5.4). STUB — implemented by the engine module. */
final class Planner {
    private Planner() {}

    static Plan plan(Rules rules, Profile profile, SimState observed, Plan previous, Engine.Options options) {
        throw new UnsupportedOperationException("engine not implemented yet");
    }
}
