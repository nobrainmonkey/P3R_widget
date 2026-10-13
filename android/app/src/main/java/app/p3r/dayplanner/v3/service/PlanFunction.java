package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Computes a full-horizon plan. Runs on the planner service's worker thread and must not mutate
 * its arguments. {@code previousPlan} (may be null) is a stability hint. Production uses
 * {@link #ENGINE}.
 */
public interface PlanFunction {
    Plan plan(Rules rules, Profile profile, SimState observed, Plan previousPlan, Engine.Options options);

    /** {@link Engine#plan}. */
    PlanFunction ENGINE = Engine::plan;
}
