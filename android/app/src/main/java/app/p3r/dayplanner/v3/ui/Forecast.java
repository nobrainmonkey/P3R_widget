package app.p3r.dayplanner.v3.ui;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * "If everything went as planned until {@code slot}, what would the game show?" — used to
 * prefill 同步现状 when the player answers "是". Only visible values are projected (cash, stat
 * Lv, RANK, unlock progress, tower visits, job shift counts); hidden points are reset to the
 * conservative lower bound of the projected level, never carried over from the forecast.
 */
public final class Forecast {
    private Forecast() {}

    /** Projected visible state at {@code slot}; {@code base} is never modified. */
    public static SimState visibleAt(Rules rules, SimState base, Plan plan, int slot) {
        SimState s = base.copy();
        s.slot = slot;
        if (plan == null || slot <= base.slot) {
            return s;
        }
        boolean cashTouched = false;
        for (int at = base.slot; at < slot; at++) {
            Plan.Step step = plan.at(at);
            if (step == null || !step.productive()) {
                continue;
            }
            if (step.predicted.cashDelta != 0) {
                s.cash = Math.max(0, s.cash + step.predicted.cashDelta);
                cashTouched = true;
            }
            for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
                int lv = step.predicted.statLvAfter[i];
                if (lv > rules.stats[i].levelFor(s.statPts[i])) {
                    s.statPts[i] = rules.stats[i].thresholds[lv];
                }
            }
            applyAction(rules, s, step, at);
        }
        if (cashTouched) {
            s.cashExact = false;
        }
        return s;
    }

    private static void applyAction(Rules rules, SimState s, Plan.Step step, int slot) {
        Action action = Action.parse(rules, step.action);
        if (action == null) {
            return;
        }
        switch (action.type) {
            case MEET:
                setRank(s, action.target, step.predicted.rankTo);
                break;
            case UNLOCK_STEP:
                int steps = rules.links[action.target].unlock.steps.size();
                s.unlockStep[action.target] = Math.min(steps, Math.max(s.unlockStep[action.target], action.step + 1));
                s.lastStepDay[action.target] = Rules.dayOf(slot);
                setRank(s, action.target, step.predicted.rankTo);
                break;
            case ACTIVITY:
                if (action.target < s.shifts.length && rules.activities[action.target].kind == Rules.ActivityKind.JOB) {
                    s.shifts[action.target]++;
                }
                if (action.target < s.used.length) {
                    s.used[action.target]++;
                }
                break;
            case TARTARUS:
                if (action.target >= 0 && action.target < s.towerVisits.length) {
                    s.towerVisits[action.target]++;
                }
                break;
            default:
                break;
        }
    }

    private static void setRank(SimState s, int link, int rankTo) {
        if (rankTo > s.rank[link]) {
            s.rank[link] = Math.min(10, rankTo);
            s.pts[link] = 0;
            s.ready[link] = false;
        }
    }
}
