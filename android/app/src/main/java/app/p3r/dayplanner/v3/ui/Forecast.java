package app.p3r.dayplanner.v3.ui;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * "If everything went as planned until {@code slot}, what would the game show?" — used to
 * prefill 同步现状 when the player answers "是". Only visible values are projected (cash, stat
 * Lv, RANK, unlock progress incl. "顺便" side steps, tower visits and rescues, job shift counts,
 * watched 羁绊剧情); hidden points are reset to the conservative lower bound of the projected
 * level, never carried over from the forecast.
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
            if (step == null) {
                continue;
            }
            // Conditional SMS records are never guaranteed future actions, even in an old or
            // externally supplied plan; none of their predicted values or side steps are followed.
            if (step.action != null && step.action.startsWith("invite:")) continue;
            if (!step.productive() && !"locked".equals(step.kind)) continue;
            // Mandatory story gains can cross a visible level while the slot is locked.
            // They carry no player action, purchase or side-step progress to prefill.
            for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
                int lv = step.predicted.statLvAfter[i];
                if (lv <= 6 && lv > rules.stats[i].levelFor(s.statPts[i])) {
                    s.statPts[i] = rules.stats[i].thresholds[lv];
                }
            }
            if (!step.productive()) continue;
            if (step.predicted.cashDelta != 0) {
                s.cash = Math.max(0, s.cash + step.predicted.cashDelta);
                cashTouched = true;
            }
            applySide(rules, s, step, at);
            applyAction(rules, s, step, at);
        }
        if (cashTouched) {
            s.cashExact = false;
        }
        return s;
    }

    /** "顺便" side steps: non-consuming unlock steps done in the same slot. */
    private static void applySide(Rules rules, SimState s, Plan.Step step, int slot) {
        for (String key : step.side) {
            Action side = Action.parse(rules, key);
            if (side == null || side.type != Action.Type.UNLOCK_STEP) {
                continue;
            }
            int steps = rules.links[side.target].unlock.steps.size();
            s.unlockStep[side.target] = Math.min(steps, Math.max(s.unlockStep[side.target], side.step + 1));
            s.lastStepDay[side.target] = Rules.dayOf(slot);
            if (side.step + 1 >= steps && s.rank[side.target] == 0) {
                setRank(s, side.target, 1);
            }
        }
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
                markRescue(rules, s, Rules.dayOf(slot));
                break;
            case EPISODE:
                if (action.target >= 0 && action.target < s.episodes.length) {
                    s.episodes[action.target]++;
                }
                break;
            default:
                break;
        }
    }

    /** A planned visit inside an open rescue window saves the missing person (as the engine assumes). */
    private static void markRescue(Rules rules, SimState s, int day) {
        for (int i = 0; i < rules.rescues.length; i++) {
            Rules.Rescue r = rules.rescues[i];
            long bit = 1L << i;
            if (day >= r.fromDay && day <= r.toDay && ((s.rescued | s.rescueFailed) & bit) == 0) {
                s.rescued |= bit;
            }
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
