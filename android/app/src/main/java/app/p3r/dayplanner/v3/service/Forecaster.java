package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.List;

/**
 * Legal actions and forecast transitions, used to list and label the 改做别的 alternatives.
 * Production uses {@link #ENGINE}.
 */
public interface Forecaster {
    /** Legal productive actions at {@code state.slot}. */
    List<Action> legalActions(Rules rules, Profile profile, SimState state);

    /** Forecast of doing {@code action} at {@code state.slot}; must not mutate {@code state}. */
    Engine.Applied apply(Rules rules, Profile profile, SimState state, Action action);

    /** {@link Engine#legalActions} and {@link Engine#apply}. */
    Forecaster ENGINE = new Forecaster() {
        @Override
        public List<Action> legalActions(Rules rules, Profile profile, SimState state) {
            return Engine.legalActions(rules, profile, state);
        }

        @Override
        public Engine.Applied apply(Rules rules, Profile profile, SimState state, Action action) {
            return Engine.apply(rules, profile, state, action);
        }
    };
}
