package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Raw dorm affinity can save a daytime meeting without changing recorded historical outcomes. */
public class DormAffinityTest {
    private static final int LINK = RulesFixtures.S_MAGICIAN;
    private static final int ACT = RulesFixtures.A_STUDY;

    private static Rules rules() {
        Rules rules = RulesFixtures.small();
        Rules.Activity a = rules.activities[ACT];
        a.id = "dorm_fuuka_reading";
        a.zh = "和风花读书";
        a.dormPartnerZh = "山岸风花";
        a.affinityLink = LINK;
        a.affinityPts = 1;
        Arrays.fill(a.gain, 0);
        a.fixedDays = new int[]{30, 31, 32, 35, 36, 37, 39, 40, 41};
        return rules;
    }

    private static Profile profile() {
        Profile p = new Profile();
        p.otherLinks = false;
        p.tier[LINK] = Profile.Tier.WANT;
        p.target[LINK] = 5;
        p.towerVisitsPerBlock = 0;
        p.towerCapPerBlock = 0;
        p.datingNotes = false;
        p.goalBuffer = false;
        return p;
    }

    private static SimState state(Rules rules, int gap) {
        SimState s = RulesFixtures.newGame(rules, Rules.slot(30, true));
        Arrays.fill(s.rank, 10);
        s.rank[LINK] = 4;
        s.pts[LINK] = rules.links[LINK].threshold[5] - gap;
        s.unlockStep[LINK] = rules.links[LINK].unlock.steps.size();
        for (int i = 0; i < 3; i++) s.statPts[i] = rules.stats[i].thresholds[6];
        s.cash = 100_000;
        s.used[ACT] = 3;
        return s;
    }

    private static double score(Rules rules, Profile p, SimState s) {
        Heuristics h = new Heuristics(new Model(rules, p, null), s, null);
        h.prepare(s);
        return h.score(s, Kernel.encode(Action.activity(ACT)));
    }

    @Test
    public void fourthCompletionAddsOneRawPointWithoutRankingUpOrMultipliers() {
        Rules rules = rules();
        Profile p = profile();
        p.personaMode = Profile.PersonaMode.ALL;
        p.charmBonus = true;
        SimState before = state(rules, 1);
        Engine.Applied done = Engine.apply(rules, p, before, Action.activity(ACT));
        assertEquals(15, done.state.pts[LINK]);
        assertEquals(4, done.state.rank[LINK]);
        assertEquals(4, done.state.used[ACT]);
        assertFalse(done.state.ready[LINK]);
        assertEquals(14, before.pts[LINK]);
        assertTrue(Kernel.ranksUp(new Model(rules, p, null), done.state, LINK, 36));
    }

    @Test
    public void aTwoPointGapStillNeedsMoreAffinityAfterOneCompletion() {
        Rules rules = rules();
        SimState after = Engine.apply(rules, profile(), state(rules, 2), Action.activity(ACT)).state;
        assertEquals(14, after.pts[LINK]);
        assertFalse(Kernel.ranksUp(new Model(rules, profile(), null), after, LINK, 36));
    }

    @Test
    public void unopenedMaxReadyAndFullLinksNeverBankPointsEvenWithAGoalBuffer() {
        Rules rules = rules();
        SimState s = state(rules, 0);
        Profile p = profile();
        p.goalBuffer = true;
        assertEquals(0, Engine.activityAffinityGain(rules, s, ACT));
        for (int rank : new int[]{0, 10}) {
            s = state(rules, 1);
            s.rank[LINK] = rank;
            assertEquals(0, Engine.activityAffinityGain(rules, s, ACT));
        }
        s = state(rules, 1);
        s.ready[LINK] = true;
        assertEquals(0, Engine.activityAffinityGain(rules, s, ACT));
    }

    @Test
    public void gardeningStillRequiresPreparedCropsAndExclusionsStillApply() {
        Rules rules = rules();
        rules.activities[ACT].requiresPlantedCrop = true;
        SimState s = state(rules, 1);
        Profile p = profile();
        assertFalse(Engine.legalActions(rules, p, s).contains(Action.activity(ACT)));
        p.planDormGardening = true;
        assertTrue(Engine.legalActions(rules, p, s).contains(Action.activity(ACT)));
        p.excludedActivities = new String[]{rules.activities[ACT].id};
        assertFalse(Engine.legalActions(rules, p, s).contains(Action.activity(ACT)));
    }

    @Test
    public void displayNamesAndOtherActivityKindsDoNotCreateAffinity() {
        Rules rules = rules();
        rules.activities[ACT].affinityLink = -1;
        rules.activities[ACT].affinityPts = 0;
        assertEquals(0, Engine.activityAffinityGain(rules, state(rules, 1), ACT));
        assertEquals(14, Engine.apply(rules, profile(), state(rules, 1), Action.activity(ACT)).state.pts[LINK]);
        assertEquals(0, Engine.activityAffinityGain(rules, state(rules, 1), RulesFixtures.A_JOB));
    }

    @Test
    public void scoreRewardsSavingARealMeetingAndStopsWhenTheGapOrGoalIsDone() {
        Rules rules = rules();
        Profile p = profile();
        double finishingGap = score(rules, p, state(rules, 1));
        double progressOnly = score(rules, p, state(rules, 2));
        assertTrue("crossing the threshold saves a whole ordinary meeting", finishingGap > progressOnly);
        assertTrue(progressOnly > 0);
        assertTrue(score(rules, p, state(rules, 0)) < 0);
        p.otherLinks = false;
        p.tier[LINK] = Profile.Tier.NONE;
        assertTrue("no generic social bonus for an unwanted link", score(rules, p, state(rules, 1)) < 0);
    }

    @Test
    public void readyAndPermanentHoldsHaveNoAffinityScoreOrEveningWasteWarning() {
        Rules rules = rules();
        SimState s = state(rules, 10);
        s.ready[LINK] = true;
        assertEquals(0, Engine.activityAffinityGain(rules, s, ACT));
        assertTrue(score(rules, profile(), s) < 0);
        assertNoEveningWaste(rules, profile(), s);

        s = state(rules, 1);
        s.heldRank[LINK] = 5;
        assertTrue(score(rules, profile(), s) < 0);
        assertNoEveningWaste(rules, profile(), s);
    }

    @Test
    public void anUnwantedOrUnreachableLinkDoesNotMakeFreeEveningsWasteful() {
        Rules rules = rules();
        Profile p = profile();
        p.tier[LINK] = Profile.Tier.NONE;
        SimState s = state(rules, 1);
        assertTrue("actual affinity can still be recorded manually", Engine.legalActions(rules, p, s).contains(Action.activity(ACT)));
        assertNoEveningWaste(rules, p, s);
        rules = rules();
        rules.links[LINK].avail.clear();
        assertTrue(score(rules, profile(), state(rules, 1)) < 0);
        assertNoEveningWaste(rules, profile(), state(rules, 1));
    }

    private static void assertNoEveningWaste(Rules rules, Profile p, SimState s) {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 0;
        Plan plan = Engine.plan(rules, p, s, null, options);
        assertEquals("free", plan.steps.get(0).kind);
        PlanValidator.Report report = PlanValidator.check(rules, p, s, plan);
        assertTrue(report.errors.toString(), report.errors.isEmpty());
        assertFalse(report.warnings.toString(), report.warnings.contains("slot " + s.slot + ": FREE while a productive action was legal"));
    }

    @Test
    public void theIndependentValidatorReplaysTheSavedDaytimeMeeting() {
        Rules rules = rules();
        Profile p = profile();
        SimState s = state(rules, 1);
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 0;
        Plan plan = Engine.plan(rules, p, s, null, options);
        assertEquals("act:dorm_fuuka_reading", plan.steps.get(0).action);
        List<String> errors = Engine.validate(rules, p, s, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        assertTrue(plan.summary.endRanks[LINK] >= 5);
        assertTrue(plan.steps.get(0).subtitleZh, plan.steps.get(0).subtitleZh.contains("好感 +1"));
    }

    @Test
    public void legacyOutcomesKeepEveryExistingEffectWithoutNewAffinity() throws Exception {
        Rules rules = rules();
        rules.activities[ACT].gain[0] = 2;
        SimState s = state(rules, 1);
        SimState expected = Engine.apply(rules, profile(), s, Action.activity(ACT)).state;
        expected.pts[LINK] = s.pts[LINK];
        SimState actual = Engine.applyOutcome(rules, profile(), s, Action.activity(ACT),
                Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals(expected.toJson().toString(), actual.toJson().toString());
    }

    @Test
    public void recordedAffinityUsesItsStableLinkEvenWhenActivityMetadataChanges() {
        Rules rules = rules();
        SimState s = state(rules, 1);
        Outcome saved = Outcome.of(Outcome.Code.AS_PREDICTED);
        saved.activityAffinityLink = rules.links[LINK].id;
        saved.activityAffinityPts = 1;
        rules.activities[ACT].affinityLink = RulesFixtures.S_EMPEROR;
        rules.activities[ACT].affinityPts = 20;
        int otherBefore = s.pts[RulesFixtures.S_EMPEROR];
        SimState replayed = Engine.applyOutcome(rules, profile(), s, Action.activity(ACT), saved);
        assertEquals(15, replayed.pts[LINK]);
        assertEquals(otherBefore, replayed.pts[RulesFixtures.S_EMPEROR]);
        rules.activities[ACT].affinityLink = -1;
        rules.activities[ACT].affinityPts = 0;
        assertEquals(15, Engine.applyOutcome(rules, profile(), s, Action.activity(ACT), saved).pts[LINK]);
    }

    @Test
    public void skippedAndNotDoneIgnoreEvenASavedAffinityDelta() {
        Rules rules = rules();
        for (Outcome.Code code : new Outcome.Code[]{Outcome.Code.SKIPPED, Outcome.Code.NOT_DONE}) {
            Outcome saved = Outcome.of(code);
            saved.activityAffinityLink = rules.links[LINK].id;
            saved.activityAffinityPts = 1;
            assertEquals(14, Engine.applyOutcome(rules, profile(), state(rules, 1), Action.activity(ACT), saved).pts[LINK]);
        }
    }
}
