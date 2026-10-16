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
    /** Label alternatives using recorded tower context; custom forecasters may keep the original behavior. */
    default List<app.p3r.dayplanner.v3.engine.Plan.Step> alternatives(Rules rules, Profile profile,
                                                                  SimState state, Engine.Options options) {
        return alternatives(rules, profile, state);
    }
    /** Legal productive actions at {@code state.slot}. */
    List<Action> legalActions(Rules rules, Profile profile, SimState state);

    /** Forecast of doing {@code action} at {@code state.slot}; must not mutate {@code state}. */
    Engine.Applied apply(Rules rules, Profile profile, SimState state, Action action);

    /**
     * Moves time forward to {@code slot} without acting; production ({@link Engine#advanceTo})
     * applies the day rollovers on the way. The default only moves the slot. Must not mutate
     * {@code state}.
     */
    default SimState advanceTo(Rules rules, Profile profile, SimState state, int slot) {
        SimState out = state.copy();
        out.slot = Math.max(state.slot, slot);
        return out;
    }

    /**
     * Fully labelled alternatives for {@code state.slot}, most useful first, or null when this
     * forecaster has none of its own (the service then labels {@link #legalActions} itself).
     */
    default List<app.p3r.dayplanner.v3.engine.Plan.Step> alternatives(Rules rules, Profile profile, SimState state) {
        return null;
    }

    /** {@link Engine#legalActions}, {@link Engine#apply}, {@link Engine#advanceTo} and {@link Engine#alternatives}. */
    Forecaster ENGINE = new Forecaster() {
        @Override
        public List<Action> legalActions(Rules rules, Profile profile, SimState state) {
            return Engine.legalActions(rules, profile, state);
        }

        @Override
        public Engine.Applied apply(Rules rules, Profile profile, SimState state, Action action) {
            return Engine.apply(rules, profile, state, action);
        }

        @Override
        public SimState advanceTo(Rules rules, Profile profile, SimState state, int slot) {
            return Engine.advanceTo(rules, profile, state, slot);
        }

        @Override
        public List<app.p3r.dayplanner.v3.engine.Plan.Step> alternatives(Rules rules, Profile profile, SimState state) {
            return Engine.alternatives(rules, profile, state);
        }

        @Override
        public List<app.p3r.dayplanner.v3.engine.Plan.Step> alternatives(Rules rules, Profile profile,
                                                                     SimState state, Engine.Options options) {
            return Engine.alternatives(rules, profile, state, options);
        }
    };
}
