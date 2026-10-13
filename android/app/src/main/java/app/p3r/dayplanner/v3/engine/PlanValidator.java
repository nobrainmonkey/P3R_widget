package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.List;

/** Independent replay validator (docs/DESIGN_v3.md §5.4). STUB — implemented by the engine module. */
final class PlanValidator {
    private PlanValidator() {}

    static List<String> validate(Rules rules, Profile profile, SimState observed, Plan plan) {
        throw new UnsupportedOperationException("engine not implemented yet");
    }
}
