package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/** Rebuild one result form from the state before that record, without planning a future route. */
final class HistoricalOutcome {
    private HistoricalOutcome() {}

    static Plan.Step step(Rules rules, Profile profile, PlannerApi.OutcomeCorrection record) {
        Action action = Action.parse(rules, record.actionKey);
        if (action == null) throw new IllegalArgumentException("Unknown recorded action");
        Engine.Applied applied = Engine.apply(rules, profile, record.beforeState, action);
        Plan.Step step = new Plan.Step();
        step.slot = record.slot;
        step.action = record.actionKey;
        step.predicted = applied.predicted;
        step.side = new ArrayList<>(record.outcome.sideKeys == null ? applied.side : record.outcome.sideKeys);
        switch (action.type) {
            case MEET:
            case INVITE:
            case UNLOCK_STEP:
            case NOTE:
                Rules.Link link = rules.links[action.target];
                step.entity = link.id;
                step.titleZh = link.labelZh;
                step.kind = action.type == Action.Type.UNLOCK_STEP ? "unlock"
                        : action.type == Action.Type.NOTE ? "note" : "social";
                break;
            case ACTIVITY:
                Rules.Activity activity = rules.activities[action.target];
                step.entity = activity.id;
                step.titleZh = activity.zh;
                step.kind = activity.dormActivity() ? "dorm"
                        : activity.kind == Rules.ActivityKind.JOB ? "job" : "stat";
                break;
            case EPISODE: {
                step.entity = rules.episodes[action.target].id;
                // The episode this record was for, as 记录历史 names it: 「羁绊剧情 · 伊织顺平（第 2 话）」.
                int[] watched = record.beforeState == null ? null : record.beforeState.episodes;
                int number = watched != null && action.target < watched.length ? watched[action.target] + 1 : -1;
                step.titleZh = "羁绊剧情 · " + rules.episodes[action.target].personZh
                        + (number >= 1 ? "（第 " + number + " 话）" : "");
                step.kind = "episode";
                break;
            }
            case TARTARUS:
                // A skipped visit (「没去成」) is still the tower's slot, not a free one. The kind stays
                // "free": the tower form records a new visit, which a correction must never do.
                step.titleZh = "探索塔尔塔罗斯";
                if (action.target >= 0 && action.target < rules.blocks.length) {
                    step.subtitleZh = rules.blocks[action.target].zh;
                }
                step.kind = "free";
                break;
            case LOCKED: {
                String reason = rules.lockReasonZh(action.reason);
                step.titleZh = reason != null ? reason : "剧情";
                step.kind = "locked";
                break;
            }
            default:
                step.titleZh = "自由活动";
                step.kind = "free";
        }
        return step;
    }
}
