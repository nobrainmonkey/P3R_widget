package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.model.Rules;

/**
 * Supplies the rule pack. Called once, on the planner service's worker thread, when the service
 * loads; may throw if the pack cannot be read.
 */
public interface RulesSource {
    Rules load();
}
