package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.List;

/** Free evening activities compete for real slots and earn finite characteristic progress. */
public class DormActivityTest {
    @Test
    public void noGoalSkipsItemOnlyProjectsButStillUsesFreeStatsAndKeepsActualHistory() {
        Rules rules = dorms();
        Profile profile = new Profile();
        SimState start = capped(rules, 20);
        start.used[0] = 2;
        assertFalse("no implicit teammate goals", legal(rules, profile, start, 0));
        assertEquals(0, new Heuristics(new Model(rules, profile, null), start, null).dormValue(start), 0);
        rules.activities[0].gain[RulesFixtures.ACADEMICS] = 2;
        start.statPts[RulesFixtures.ACADEMICS]--;
        assertTrue("free stat gains still compete normally", legal(rules, profile, start, 0));
        Plan plan = plan(rules, profile, start);
        assertEquals("act:dorm_yukari_dvd", plan.steps.get(0).action);
        assertTrue(Engine.validate(rules, profile, start, plan).isEmpty());
        assertEquals(2, start.used[0]);
        assertEquals(3, Engine.applyOutcome(rules, profile, start, Action.activity(0), Outcome.of(Outcome.Code.AS_PREDICTED)).used[0]);
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UPGRADE);
        assertEquals("a preference does not rewrite observations", 2, start.used[0]);
    }

    @Test
    public void unlockGoalStopsAfterOneProjectAndDoesNotRewardSpreadingOrUpgrade() {
        Rules rules = dorms();
        Profile profile = new Profile();
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UNLOCK);
        SimState start = capped(rules, 20);
        Plan plan = plan(rules, profile, start);
        SimState end = finish(rules, profile, start, plan);
        assertEquals("one characteristic needs one project only", 3, end.used[0] + end.used[1]);
        assertTrue(end.used[0] == 3 || end.used[1] == 3);
        assertTrue(Engine.validate(rules, profile, start, plan).isEmpty());
        Heuristics h = new Heuristics(new Model(rules, profile, null), start, null);
        assertEquals(3 * Heuristics.DORM_PROGRESS + Heuristics.DORM_TRAIT, h.dormValue(end), 0);
        SimState bothPartial = start.copy();
        bothPartial.used[0] = bothPartial.used[1] = 2;
        assertEquals("partial progress is counted once for an unlock goal", 2 * Heuristics.DORM_PROGRESS, h.dormValue(bothPartial), 0);
        end.used[0] = end.used[1] = 3;
        assertEquals("an incidental upgrade adds no unselected goal value", 3 * Heuristics.DORM_PROGRESS + Heuristics.DORM_TRAIT, h.dormValue(end), 0);
    }

    @Test
    public void unlockGoalCanChangeProjectWhenLeadingProgressNoLongerHasEnoughDates() {
        Rules rules = dorms();
        rules.activities[0].fixedDays = new int[]{20};
        Profile profile = new Profile();
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UNLOCK);
        SimState start = capped(rules, 21);
        start.used[0] = 2;
        assertTrue("an unfinishable two-use project must not block the other project", legal(rules, profile, start, 1));
        Plan plan = plan(rules, profile, start);
        SimState end = finish(rules, profile, start, plan);
        assertEquals(2, end.used[0]);
        assertEquals(3, end.used[1]);
        assertTrue(Engine.validate(rules, profile, start, plan).isEmpty());
    }

    @Test
    public void excludedOrUnpreparedLeadingProjectDoesNotBlockAnotherUnlockProject() {
        Rules rules = dorms();
        Profile profile = new Profile();
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UNLOCK);
        profile.excludedActivities = new String[]{"dorm_yukari_dvd"};
        SimState start = capped(rules, 21);
        start.used[0] = 2;
        assertTrue(legal(rules, profile, start, 1));
        Plan plan = plan(rules, profile, start);
        assertTrue(Engine.validate(rules, profile, start, plan).isEmpty());

        rules = dorms();
        rules.activities[0].requiresPlantedCrop = true;
        profile.excludedActivities = new String[0];
        start = capped(rules, 21);
        start.used[0] = 2;
        assertTrue("gardening must first be explicitly prepared", legal(rules, profile, start, 1));
        profile.planDormGardening = true;
        assertFalse("when viable, finish the already-leading project", legal(rules, profile, start, 1));
        rules.activities[1].gain[RulesFixtures.ACADEMICS] = 2;
        start.statPts[RulesFixtures.ACADEMICS]--;
        assertTrue("a non-leading project's useful stats remain legal", legal(rules, profile, start, 1));
    }

    @Test
    public void tiedUnlockProjectsCanStartWhicheverIsAvailableToday() {
        Rules rules = dorms();
        Profile profile = new Profile();
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UNLOCK);
        rules.activities[0].fixedDays = new int[]{22, 24, 26};
        SimState start = capped(rules, 21);
        assertTrue("the first activity id must not force waiting for a later start", legal(rules, profile, start, 1));
        Plan plan = plan(rules, profile, start);
        assertEquals("act:dorm_yukari_read", plan.steps.get(0).action);
        SimState end = finish(rules, profile, start, plan);
        assertEquals(0, end.used[0]);
        assertEquals(3, end.used[1]);
        assertTrue(Engine.validate(rules, profile, start, plan).isEmpty());
    }

    @Test
    public void alreadyUnlockedGoalAndAlreadyUpgradedGoalNeverRepeatPureTraitProjects() {
        Rules rules = dorms();
        SimState start = capped(rules, 21);
        start.used[0] = 3;
        Profile profile = new Profile();
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UNLOCK);
        assertFalse(legal(rules, profile, start, 1));
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UPGRADE);
        assertTrue(legal(rules, profile, start, 1));
        start.used[1] = 3;
        assertFalse(legal(rules, profile, start, 1));
        rules.activities[1].gain[RulesFixtures.ACADEMICS] = 2;
        start.statPts[RulesFixtures.ACADEMICS]--;
        assertTrue("completed targets never forbid still-useful stat gains", legal(rules, profile, start, 1));
    }

    @Test
    public void onlySelectedStablePartnerGetsTraitValueEvenWhenDisplayNamesMatch() {
        Rules rules = dorms();
        Rules.Activity other = RulesFixtures.activity(2, "dorm_ken_read", "和天田读书", Rules.ActivityKind.STAT, false, true, 0, RulesFixtures.ACADEMICS, 0);
        other.dormPartnerZh = rules.activities[0].dormPartnerZh;
        other.fixedDays = new int[]{20, 22, 24};
        rules.activities = new Rules.Activity[]{rules.activities[0], rules.activities[1], other};
        Profile profile = upgrading();
        SimState start = capped(rules, 20);
        assertTrue(legal(rules, profile, start, 0));
        assertFalse(legal(rules, profile, start, 2));
        SimState end = start.copy();
        end.used[2] = 3;
        assertEquals("an unselected teammate's progress contributes no trait objective", 0, new Heuristics(new Model(rules, profile, null), start, null).dormValue(end), 0);
    }

    @Test
    public void validatorRejectsUnselectedPureTraitPlanAndGoalHashUsesCanonicalOrder() {
        Rules rules = dorms();
        SimState start = capped(rules, 20);
        Profile selected = upgrading();
        Plan selectedPlan = plan(rules, selected, start);
        Profile empty = new Profile();
        List<String> errors = Engine.validate(rules, empty, start, selectedPlan);
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("chosen characteristic progress")));
        String emptyHash = PlanHash.of(rules, empty, start, new Engine.Options(), selectedPlan);
        assertFalse(emptyHash.equals(PlanHash.of(rules, selected, start, new Engine.Options(), selectedPlan)));
        selected.dormTargets.put("dorm_ken", Profile.DormTarget.UNLOCK);
        Profile reordered = new Profile();
        reordered.dormTargets.put("dorm_ken", Profile.DormTarget.UNLOCK);
        reordered.dormTargets.put("dorm_yukari", Profile.DormTarget.UPGRADE);
        assertEquals(PlanHash.of(rules, selected, start, new Engine.Options(), selectedPlan), PlanHash.of(rules, reordered, start, new Engine.Options(), selectedPlan));
        empty.dormTargets.put("dorm_yukari", Profile.DormTarget.NONE);
        assertEquals("an explicit NONE has the same planning meaning as a missing goal", emptyHash, PlanHash.of(rules, empty, start, new Engine.Options(), selectedPlan));
    }

    @Test
    public void exactDatesRespectLocksAndStatCapDoesNotHideUnfinishedDormProjects() {
        Rules rules = dorms();
        SimState start = capped(rules, 20);
        assertTrue(legal(rules, upgrading(), start, 0));
        assertFalse(legal(rules, upgrading(), start, 1));
        SimState nextDay = start.copy();
        nextDay.slot = Rules.slot(21, true);
        assertFalse(legal(rules, upgrading(), nextDay, 0));
        assertTrue(legal(rules, upgrading(), nextDay, 1));
        nextDay.slot--;
        assertFalse("dorm activities consume an evening", legal(rules, upgrading(), nextDay, 1));

        rules = dorms();
        rules.days[20].eveLock = "story";
        assertFalse("a listed activity does not override story locks", legal(rules, upgrading(), capped(rules, 20), 0));
    }

    @Test
    public void plannerCompletesBothProjectsAtMaxStatsAndDoesNotInventObservedProgress() {
        Rules rules = dorms();
        SimState start = capped(rules, 20);
        Profile profile = upgrading();
        Plan plan = plan(rules, profile, start);
        SimState end = finish(rules, profile, start, plan);
        assertEquals(3, end.used[0]);
        assertEquals(3, end.used[1]);
        assertEquals(0, start.used[0]);
        assertEquals(0, start.used[1]);
        assertEquals(start.cash, end.cash);
        assertEquals("dorm", plan.steps.get(0).kind);
        assertEquals("特性进度 1/3", plan.steps.get(0).subtitleZh);
        assertTrue(first(plan, 24).whyZh, first(plan, 24).whyZh.startsWith("第 3 次，解锁"));
        assertTrue(first(plan, 25).whyZh, first(plan, 25).whyZh.startsWith("第 3 次，强化"));
        assertTrue(plan.steps.get(0).whyZh, plan.steps.get(0).whyZh.startsWith("再做 3 次就能"));
        // The how-to is the activity's own note: no rule lecture repeated on every evening.
        for (Plan.Step s : plan.steps) {
            if ("dorm".equals(s.kind)) assertTrue(s.detailZh, s.detailZh == null || !s.detailZh.contains("每次占用一个晚上"));
        }
        assertTrue(Engine.validate(rules, profile, start, plan).toString(), Engine.validate(rules, profile, start, plan).isEmpty());
        Model model = new Model(rules, profile, null);
        Heuristics h = new Heuristics(model, start, null, Heuristics.Tuning.DEFAULT);
        assertEquals(6 * Heuristics.DORM_PROGRESS + Heuristics.DORM_TRAIT + Heuristics.DORM_UPGRADE, h.dormValue(end), 0);
        end.used[0] = 99;
        assertEquals("extra uses cannot farm objective value", 6 * Heuristics.DORM_PROGRESS + Heuristics.DORM_TRAIT + Heuristics.DORM_UPGRADE, h.dormValue(end), 0);
    }

    @Test
    public void zeroStatProjectStopsAtThreeButUsefulStatProjectCanContinue() {
        Rules rules = dorms();
        SimState start = capped(rules, 20);
        start.used[0] = 3;
        assertFalse(legal(rules, upgrading(), start, 0));
        rules.activities[0].gain[RulesFixtures.ACADEMICS] = 2;
        start.statPts[RulesFixtures.ACADEMICS]--;
        assertTrue(legal(rules, upgrading(), start, 0));
        start.statPts[RulesFixtures.ACADEMICS]++;
        assertFalse(legal(rules, upgrading(), start, 0));
    }

    @Test
    public void gardeningRequiresPreparationOnlyForForecastAndRecordedCompletionStillReplays() throws Exception {
        Rules rules = dorms();
        rules.activities[0].requiresPlantedCrop = true;
        SimState start = capped(rules, 20);
        Profile profile = upgrading();
        assertFalse(legal(rules, profile, start, 0));
        profile.planDormGardening = true;
        assertTrue(legal(rules, profile, start, 0));
        assertTrue(Profile.fromJson(profile.toJson()).planDormGardening);
        profile.planDormGardening = false;
        SimState recorded = Engine.applyOutcome(rules, profile, start, Action.activity(0), Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals("a current preference cannot erase something the player recorded", 1, recorded.used[0]);
        SimState skipped = Engine.applyOutcome(rules, profile, start, Action.activity(0), Outcome.of(Outcome.Code.SKIPPED));
        assertEquals(0, skipped.used[0]);
    }

    @Test
    public void gardeningPermissionChangesPlanIdentityEvenWhenTheSelectedActionsMatch() {
        Rules rules = dorms();
        SimState start = capped(rules, 20);
        Profile profile = upgrading();
        Plan plan = plan(rules, profile, start);
        String without = PlanHash.of(rules, profile, start, new Engine.Options(), plan);
        profile.planDormGardening = true;
        String with = PlanHash.of(rules, profile, start, new Engine.Options(), plan);
        assertFalse(without.equals(with));
        profile.planDormGardening = false;
        assertEquals(without, PlanHash.of(rules, profile, start, new Engine.Options(), plan));
    }

    @Test
    public void unfinishedDormTraitCanBeatOrdinaryPaidTraining() {
        Rules rules = dorms();
        Rules.Activity paid = RulesFixtures.activity(2, "arcade", "游戏中心", Rules.ActivityKind.STAT, false, true, 1500, RulesFixtures.ACADEMICS, 4);
        rules.activities = new Rules.Activity[]{rules.activities[0], rules.activities[1], paid};
        rules.activities[0].gain[RulesFixtures.ACADEMICS] = 2;
        SimState start = capped(rules, 20);
        start.statPts[RulesFixtures.ACADEMICS] = 0;
        start.used[0] = 2;
        Plan plan = plan(rules, upgrading(), start);
        assertEquals("act:dorm_yukari_dvd", plan.steps.get(0).action);
    }

    @Test
    public void lastChanceMustGoalKeepsItsPriorityOverDormMilestone() {
        Rules rules = dorms();
        Rules.Link link = RulesFixtures.link(0, "goal", 0, "魔术师", "友近健二", Rules.LinkKind.MANUAL, true, "学校");
        link.avail.set(20);
        rules.links = new Rules.Link[]{link};
        SimState start = capped(rules, 20);
        start.rank[0] = 9;
        start.ready[0] = true;
        start.used[0] = 2;
        rules.activities[0].fixedDays = new int[]{20};
        Profile profile = upgrading();
        profile.tier[0] = Profile.Tier.MUST;
        Plan plan = plan(rules, profile, start);
        assertEquals("meet:goal", plan.steps.get(0).action);
        assertEquals(10, plan.summary.endRanks[0]);
    }

    @Test
    public void independentValidatorRejectsAnActivityOnAnUnlistedDate() {
        Rules rules = dorms();
        SimState start = capped(rules, 20);
        Plan plan = plan(rules, upgrading(), start);
        plan.steps.get(0).action = "act:dorm_yukari_read";
        List<String> errors = Engine.validate(rules, upgrading(), start, plan);
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("not available in this slot")));
    }

    @Test
    public void examGroupStudyReplacesPaidTrainingOnItsExactDate() {
        Rules rules = dorms();
        Rules.Activity paid = RulesFixtures.activity(0, "arcade", "游戏中心", Rules.ActivityKind.STAT, false, true, 1500, RulesFixtures.ACADEMICS, 4);
        Rules.Activity study = RulesFixtures.activity(1, "group_study", "考前集体学习", Rules.ActivityKind.STAT, false, true, 0, RulesFixtures.ACADEMICS, 5);
        study.fixedDays = new int[]{20};
        rules.activities = new Rules.Activity[]{paid, study};
        SimState start = capped(rules, 20);
        start.statPts[RulesFixtures.ACADEMICS] = 0;
        Model model = new Model(rules, upgrading(), null);
        Heuristics h = new Heuristics(model, start, null);
        assertEquals("rare group-study bonus must not inflate ordinary capacity", 4, model.maxGain[0]);
        assertEquals(3.0, h.eveGain[0], 0);
        Plan plan = plan(rules, upgrading(), start);
        assertEquals("act:group_study", plan.steps.get(0).action);
        assertEquals(5, plan.steps.get(0).predicted.statDelta[0]);
        start.slot = Rules.slot(21, true);
        assertFalse(legal(rules, upgrading(), start, 1));
        assertTrue(Engine.validate(rules, upgrading(), cappedForStudy(rules), plan).isEmpty());
    }

    private static SimState cappedForStudy(Rules rules) {
        SimState state = capped(rules, 20);
        state.statPts[RulesFixtures.ACADEMICS] = 0;
        return state;
    }

    private static Profile upgrading() {
        Profile profile = new Profile();
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UPGRADE);
        return profile;
    }

    private static Rules dorms() {
        Rules rules = RulesFixtures.small();
        rules.lastDay = 26;
        rules.links = new Rules.Link[0];
        rules.blocks = new Rules.Block[0];
        rules.rescues = new Rules.Rescue[0];
        for (Rules.Day day : rules.days) {
            if (day == null) continue;
            day.dayLock = day.eveLock = null;
            day.tartarus = false;
            day.block = -1;
        }
        Rules.Activity dvd = RulesFixtures.activity(0, "dorm_yukari_dvd", "和由加莉看 DVD", Rules.ActivityKind.STAT, false, true, 0, RulesFixtures.ACADEMICS, 0);
        dvd.dormPartnerZh = "岳羽由加莉";
        dvd.fixedDays = new int[]{20, 22, 24};
        Rules.Activity read = RulesFixtures.activity(1, "dorm_yukari_read", "和由加莉读书", Rules.ActivityKind.STAT, false, true, 0, RulesFixtures.ACADEMICS, 0);
        read.dormPartnerZh = dvd.dormPartnerZh;
        read.fixedDays = new int[]{21, 23, 25};
        rules.activities = new Rules.Activity[]{dvd, read};
        return rules;
    }

    private static SimState capped(Rules rules, int day) {
        SimState state = RulesFixtures.newGame(rules, Rules.slot(day, true));
        state.cash = 100_000;
        for (int s = 0; s < 3; s++) state.statPts[s] = rules.stats[s].thresholds[6];
        return state;
    }

    private static boolean legal(Rules rules, Profile profile, SimState state, int activity) {
        return Engine.legalActions(rules, profile, state).contains(Action.activity(activity));
    }

    private static Plan plan(Rules rules, Profile profile, SimState state) {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 0;
        return Engine.plan(rules, profile, state, null, options);
    }

    private static SimState finish(Rules rules, Profile profile, SimState state, Plan plan) {
        for (Plan.Step step : plan.steps) state = Engine.apply(rules, profile, state, Action.parse(rules, step.action)).state;
        return state;
    }

    private static Plan.Step first(Plan plan, int day) { return plan.at(Rules.slot(day, true)); }
}
