package app.p3r.dayplanner.v3.engine;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/** Actual engine labels at each dorm activity's third completion, for UI render regressions. */
public final class DormRenderCases {
    private DormRenderCases() {}

    public static List<Plan.Step> thirdCompletions(Rules rules) {
        Profile profile = new Profile();
        profile.otherLinks = false;
        profile.planDormGardening = true;
        Model model = new Model(rules, profile, null);
        List<Plan.Step> steps = new ArrayList<>();
        for (Rules.Activity activity : rules.activities) {
            if (!activity.dormActivity()) continue;
            SimState start = new SimState(rules.blocks.length, rules.activities.length);
            start.slot = Rules.slot(activity.fixedDays[0], true);
            for (Rules.Activity track : rules.activities) if (track.dormActivity()) start.used[track.idx] = 3;
            start.used[activity.idx] = 2;
            for (Rules.Stat stat : rules.stats) start.statPts[stat.idx] = stat.thresholds[2] - 1;
            start = Kernel.normalize(model, start);
            SimState after = start.copy();
            int code = Kernel.encode(Action.activity(activity.idx));
            Kernel.Effect effect = new Kernel.Effect();
            Kernel.begin(model, after, effect);
            Kernel.mainEffect(model, after, code, effect);
            Kernel.end(model, after, effect);
            Plan.Step step = new Plan.Step();
            step.slot = start.slot;
            step.action = Action.activity(activity.idx).key(rules);
            step.predicted = Simulator.predicted(effect);
            new Labels(model, new Heuristics(model, start, null)).describe(step, start, code, effect);
            steps.add(step);
        }
        return steps;
    }
}
