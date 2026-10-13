package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.List;

/**
 * Independent check of a freshly computed plan before it is installed. An empty list means the
 * plan is valid; messages are developer-facing. Production uses {@link #ENGINE}.
 */
public interface PlanCheck {
    List<String> validate(Rules rules, Profile profile, SimState observed, Plan plan);

    /** {@link Engine#validate}. */
    PlanCheck ENGINE = Engine::validate;
}
