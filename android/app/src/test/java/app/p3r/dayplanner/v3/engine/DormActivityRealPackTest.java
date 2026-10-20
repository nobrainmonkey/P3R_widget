package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Actual study nights, short teammate windows and full-calendar dorm progress. */
public class DormActivityRealPackTest {
    private static Rules rules;

    @BeforeClass
    public static void load() throws Exception {
        rules = RulesLoader.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/p3r/p3r_rules.json")), StandardCharsets.UTF_8));
    }

    @Test
    public void maySeventeenthUsesFreeWholeTeamStudyEvenWithoutMoney() throws Exception {
        SimState start = start(5, 17, true);
        start.cash = 0;
        start.statPts[0] = 0;
        Profile profile = isolated();
        Plan plan = plan(profile, start);
        assertEquals("act:group_study_05_17", plan.steps.get(0).action);
        assertEquals(5, plan.steps.get(0).predicted.statDelta[0]);
        assertEquals(0, plan.steps.get(0).predicted.cashDelta);
        assertFalse(Engine.legalActions(rules, profile, start(5, 18, true)).contains(Action.activity(rules.activityIndex("group_study_05_17"))));
    }

    @Test
    public void lastShinjiroCookingNightFinishesTraitEvenWithEveryStatMaxed() throws Exception {
        SimState start = start(10, 3, true);
        int cooking = rules.activityIndex("dorm_shinjiro_cooking");
        start.used[cooking] = 2;
        Profile profile = isolated();
        profile.dormTargets.put("dorm_shinjiro", Profile.DormTarget.UNLOCK);
        Plan plan = plan(profile, start);
        assertEquals("act:dorm_shinjiro_cooking", plan.steps.get(0).action);
        assertEquals("第 3 次，解锁荒垣真次郎的特性", plan.steps.get(0).whyZh);
        assertEquals(2, start.used[cooking]);
        assertEquals(0, plan.steps.get(0).predicted.cashDelta);
        assertEquals(3, Engine.apply(rules, profile, start, Action.activity(cooking)).state.used[cooking]);
    }

    @Test
    public void newGameWithSelectedTraitGoalsPlansFreeGainsAndMilestonesWithoutAssumingPlantedCrops() throws Exception {
        SimState start = start(4, 21, false);
        start.cash = 25_000;
        start.statPts = new int[3];
        Profile profile = new Profile();
        profile.applyNewProfileDefaults(rules);
        for (Rules.Activity activity : rules.activities) {
            if (activity.dormPartnerId() != null) profile.dormTargets.put(activity.dormPartnerId(), Profile.DormTarget.UPGRADE);
        }
        Plan plan = plan(profile, start);
        SimState end = start;
        int dorm = 0;
        int study = 0;
        int paidTraining = 0;
        for (Plan.Step step : plan.steps) {
            Action action = Action.parse(rules, step.action);
            if (action.type == Action.Type.ACTIVITY) {
                Rules.Activity activity = rules.activities[action.target];
                if (activity.dormActivity()) {
                    dorm++;
                    assertFalse("gardening needs explicit preparation", activity.requiresPlantedCrop);
                }
                if (activity.id.startsWith("group_study_")) study++;
                if (activity.cost > 0 && activity.kind == Rules.ActivityKind.STAT) paidTraining++;
            }
            end = Engine.apply(rules, profile, end, action).state;
        }
        int traits = 0;
        int upgraded = 0;
        Model model = new Model(rules, profile, null);
        for (int[] tracks : model.dormTracks) {
            boolean one = false;
            boolean both = tracks.length >= 2;
            for (int track : tracks) { one |= end.used[track] >= 3; both &= end.used[track] >= 3; }
            if (one) traits++;
            if (both) upgraded++;
        }
        System.out.println("DORM new game: activities=" + dorm + ", group study=" + study + ", traits=" + traits
                + ", upgraded=" + upgraded + ", paid training=" + paidTraining);
        assertTrue("dorm activities must actually enter the full-horizon plan", dorm >= 3);
        assertTrue("at least one teammate reaches a characteristic milestone", traits > 0);
        assertTrue("limited group study must enter the plan", study > 0);
        assertEquals(0, start.used[rules.activityIndex("dorm_yukari_dvd")]);
    }

    private static Profile isolated() {
        Profile profile = new Profile();
        profile.otherLinks = false;
        profile.towerVisitsPerBlock = profile.towerCapPerBlock = 0;
        return profile;
    }

    private static SimState start(int month, int day, boolean evening) {
        SimState state = new SimState(rules.blocks.length, rules.activities.length);
        state.slot = RulesFixtures.slot(month, day, evening);
        state.cash = 100_000;
        for (int s = 0; s < 3; s++) state.statPts[s] = rules.stats[s].thresholds[6];
        return state;
    }

    private static Plan plan(Profile profile, SimState start) throws Exception {
        String before = start.toJson().toString();
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 0;
        Plan plan = Engine.plan(rules, profile, start, null, options);
        assertEquals(before, start.toJson().toString());
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        return plan;
    }
}
