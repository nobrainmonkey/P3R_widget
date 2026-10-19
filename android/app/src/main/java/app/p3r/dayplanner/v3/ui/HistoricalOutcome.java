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
            case EPISODE:
                step.entity = rules.episodes[action.target].id;
                step.titleZh = "羁绊剧情 · " + rules.episodes[action.target].personZh;
                step.kind = "episode";
                break;
            default:
                step.titleZh = "自由活动";
                step.kind = "free";
        }
        return step;
    }
}
