package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds labelled {@link Plan.Step}s for the 改做别的 list from a forecast transition, with the same
 * wording the plan uses: title "皇帝 · 小田桐秀利", subtitle "RANK 6 → 7" / "学力 +2" / "+2,500 日元".
 */
final class StepLabels {
    /** Location line for Tartarus visits. */
    static final String TOWER_LOCATION = "影时间 · 月光馆学园";

    private StepLabels() {}

    static Plan.Step step(Rules rules, SimState before, Action action, Engine.Applied applied) {
        Plan.Step step = new Plan.Step();
        step.slot = before.slot;
        step.action = action.key(rules);
        if (applied != null) {
            if (applied.predicted != null) {
                step.predicted = applied.predicted;
            }
            step.estimate = applied.estimate;
            if (applied.assumptions != null) {
                step.assumptions = new ArrayList<>(applied.assumptions);
            }
        }
        switch (action.type) {
            case MEET:
                labelMeeting(rules, before, action, step);
                break;
            case UNLOCK_STEP:
                labelUnlock(rules, action, step);
                break;
            case ACTIVITY:
                labelActivity(rules, action, step, applied != null && applied.predicted != null);
                break;
            case TARTARUS:
                labelTower(rules, before, action, step);
                break;
            default:
                step.kind = "free";
                step.titleZh = "自由安排";
                step.subtitleZh = "";
                break;
        }
        return step;
    }

    private static void labelMeeting(Rules rules, SimState before, Action action, Plan.Step step) {
        Rules.Link link = rules.links[action.target];
        int rank = before.rank[action.target];
        step.kind = "social";
        step.entity = link.id;
        step.titleZh = link.labelZh;
        step.locationZh = link.locationZh;
        step.subtitleZh = step.predicted.rankUp
                ? "RANK " + ZhText.rank(rank) + " → " + ZhText.rank(Math.min(10, rank + 1))
                : "RANK " + ZhText.rank(rank) + " · 普通会面";
    }

    private static void labelUnlock(Rules rules, Action action, Plan.Step step) {
        Rules.Link link = rules.links[action.target];
        Rules.Step unlock = action.step >= 0 && action.step < link.unlock.steps.size()
                ? link.unlock.steps.get(action.step)
                : null;
        step.kind = "unlock";
        step.entity = link.id;
        step.titleZh = link.labelZh;
        step.subtitleZh = unlock != null && unlock.zh != null ? unlock.zh : "解锁准备";
        step.locationZh = unlock != null && unlock.locationZh != null ? unlock.locationZh : link.locationZh;
    }

    /** {@code forecast}: use the forecast deltas; otherwise the activity's nominal gain and cost. */
    private static void labelActivity(Rules rules, Action action, Plan.Step step, boolean forecast) {
        Rules.Activity activity = rules.activities[action.target];
        boolean job = activity.kind == Rules.ActivityKind.JOB;
        step.kind = job ? "job" : "stat";
        step.entity = activity.id;
        step.titleZh = activity.zh;
        step.locationZh = activity.locationZh;
        long cashDelta = forecast ? step.predicted.cashDelta : -activity.cost;
        List<String> parts = new ArrayList<>();
        if (cashDelta > 0) {
            parts.add("+" + ZhText.yen(cashDelta));
        }
        for (int stat = 0; stat < SimState.STATS; stat++) {
            int delta = forecast ? step.predicted.statDelta[stat] : activity.gain[stat];
            String gain = ZhText.statGain(rules, stat, delta);
            if (gain != null) {
                parts.add(gain);
            }
        }
        if (cashDelta < 0) {
            parts.add("花费 " + ZhText.yen(-cashDelta));
        }
        step.subtitleZh = ZhText.join(parts.toArray(new String[0]));
    }

    private static void labelTower(Rules rules, SimState before, Action action, Plan.Step step) {
        step.kind = "tower";
        step.titleZh = "探索塔尔塔罗斯";
        step.locationZh = TOWER_LOCATION;
        if (action.target >= 0 && action.target < rules.blocks.length) {
            Rules.Block block = rules.blocks[action.target];
            int visit = action.target < before.towerVisits.length ? before.towerVisits[action.target] + 1 : 1;
            step.entity = block.id;
            step.subtitleZh = ZhText.join(block.zh, "本区块第 " + visit + " 次");
        } else {
            step.subtitleZh = "";
        }
    }
}
