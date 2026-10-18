package app.p3r.dayplanner.v3.engine;

import static app.p3r.dayplanner.v3.engine.RulesFixtures.ACADEMICS;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.A_ARCADE;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.A_JOB;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.A_STUDY;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.CHARM;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_DEVIL;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_EMPEROR;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_FOOL;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_HIEROPHANT;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_MAGICIAN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.List;

/** Forecast transitions and legality (docs/DESIGN_v3.md §5.2) on the small synthetic pack. */
public class SimulatorTest {
    private final Rules rules = RulesFixtures.small();
    private final Profile others = RulesFixtures.others();

    /** Thursday Apr 23 2009 (day 22): magician meets on Tue/Thu/Fri school days. */
    private static final int THU = 22;

    private SimState at(int day, boolean evening) {
        return RulesFixtures.newGame(rules, Rules.slot(day, evening));
    }

    private Engine.Applied meet(Profile p, SimState s, int link) {
        return Engine.apply(rules, p, s, Action.meet(link));
    }

    // ------------------------------------------------------------------ meetings

    @Test
    public void ordinaryMeetingAddsEstimatedPointsAndAdvancesOneSlot() {
        SimState s = at(THU, false);
        s.rank[S_MAGICIAN] = 4;
        Engine.Applied a = meet(others, s, S_MAGICIAN);
        assertEquals(4, a.state.rank[S_MAGICIAN]);
        assertEquals(10, a.state.pts[S_MAGICIAN]);
        assertEquals(s.slot + 1, a.state.slot);
        assertFalse(a.predicted.rankUp);
        assertEquals(4, a.predicted.rankFrom);
        assertEquals(4, a.predicted.rankTo);
        assertTrue(a.estimate);
        assertTrue(a.assumptions.contains("ordinary_gain_estimate"));
        assertEquals("input state must not change", 0, s.pts[S_MAGICIAN]);
    }

    @Test
    public void meetingRanksUpOncePointsReachTheThreshold() {
        SimState s = at(THU, false);
        s.rank[S_MAGICIAN] = 4;
        s.pts[S_MAGICIAN] = 15;
        Engine.Applied a = meet(others, s, S_MAGICIAN);
        assertEquals(5, a.state.rank[S_MAGICIAN]);
        assertEquals("best-answer points of the rank-5 scene", 15, a.state.pts[S_MAGICIAN]);
        assertTrue(a.predicted.rankUp);
        assertFalse("threshold > 0 → estimated", a.predicted.certain);
        assertTrue(a.assumptions.contains("best_answers"));
    }

    @Test
    public void zeroThresholdRankUpIsCertain() {
        SimState s = at(THU, false);
        s.rank[S_MAGICIAN] = 1;
        Engine.Applied a = meet(others, s, S_MAGICIAN);
        assertEquals(2, a.state.rank[S_MAGICIAN]);
        assertTrue(a.predicted.certain);
        assertFalse(a.estimate);
        assertEquals(10, a.state.pts[S_MAGICIAN]);
    }

    @Test
    public void personaAndCharmMultipliersFloorPerMeeting() {
        Profile persona = RulesFixtures.others();
        persona.personaBonus = true;
        SimState s = at(THU, false);
        s.rank[S_MAGICIAN] = 4;
        Engine.Applied a = meet(persona, s, S_MAGICIAN);
        assertEquals("floor(10 × 1.51)", 15, a.state.pts[S_MAGICIAN]);
        assertTrue(a.assumptions.contains("persona_bonus"));

        s.statPts[CHARM] = 100; // Charm Lv6
        a = meet(persona, s, S_MAGICIAN);
        assertEquals("floor(10 × 1.51 × 1.51)", 22, a.state.pts[S_MAGICIAN]);
        assertTrue(a.assumptions.contains("charm_bonus"));

        s.pts[S_MAGICIAN] = 15;
        a = meet(persona, s, S_MAGICIAN);
        assertEquals("rank-5 scene 15 × 1.51 × 1.51 floored", 34, a.state.pts[S_MAGICIAN]);

        Profile noCharm = RulesFixtures.others();
        noCharm.charmBonus = false;
        s.pts[S_MAGICIAN] = 0;
        assertEquals("charm bonus off", 10, meet(noCharm, s, S_MAGICIAN).state.pts[S_MAGICIAN]);
        s.statPts[CHARM] = 99; // Lv5
        assertEquals("charm Lv5 gives no bonus", 10, meet(others, s, S_MAGICIAN).state.pts[S_MAGICIAN]);
    }

    @Test
    public void goalBufferAppliesOnlyToGoalsAndOnlyWhenThresholdPositive() {
        SimState s = at(THU, false);
        s.rank[S_MAGICIAN] = 4;
        s.pts[S_MAGICIAN] = 15;
        Profile goal = RulesFixtures.must(S_MAGICIAN);
        Engine.Applied a = meet(goal, s, S_MAGICIAN);
        assertEquals("goal needs 15 + 10 buffer", 4, a.state.rank[S_MAGICIAN]);
        assertTrue(a.assumptions.contains("goal_buffer"));
        assertEquals(5, meet(others, s, S_MAGICIAN).state.rank[S_MAGICIAN]);

        goal.goalBuffer = false;
        assertEquals(5, meet(goal, s, S_MAGICIAN).state.rank[S_MAGICIAN]);

        Profile goal2 = RulesFixtures.must(S_MAGICIAN);
        s.rank[S_MAGICIAN] = 2;
        s.pts[S_MAGICIAN] = 0;
        assertEquals("threshold 0: no buffer", 3, meet(goal2, s, S_MAGICIAN).state.rank[S_MAGICIAN]);
    }

    @Test
    public void readyCueForcesRankUpAndClears() {
        SimState s = at(THU, false);
        s.rank[S_MAGICIAN] = 8;
        s.ready[S_MAGICIAN] = true;
        Engine.Applied a = meet(others, s, S_MAGICIAN);
        assertEquals(9, a.state.rank[S_MAGICIAN]);
        assertTrue(a.predicted.certain);
        assertFalse(a.state.ready[S_MAGICIAN]);
    }

    @Test
    public void answersNoneEarnsNoScenePoints() {
        Profile none = RulesFixtures.others();
        none.answers = Profile.AnswerPolicy.NONE;
        SimState s = at(THU, false);
        s.rank[S_MAGICIAN] = 4;
        s.pts[S_MAGICIAN] = 15;
        assertEquals(0, meet(none, s, S_MAGICIAN).state.pts[S_MAGICIAN]);
    }

    @Test
    public void romanceRouteUsesRomanceGains() {
        rules.links[S_MAGICIAN].eventGainRomance = new int[]{0, 0, 10, 20, 15, 40, 15, 15, 20, 15, 20};
        Profile romance = RulesFixtures.others();
        romance.route[S_MAGICIAN] = Profile.Route.ROMANCE;
        SimState s = at(THU, false);
        s.rank[S_MAGICIAN] = 4;
        s.pts[S_MAGICIAN] = 15;
        assertEquals(40, meet(romance, s, S_MAGICIAN).state.pts[S_MAGICIAN]);
        assertEquals(15, meet(others, s, S_MAGICIAN).state.pts[S_MAGICIAN]);
    }

    @Test
    public void meetingsOnlyOnTheLinksDaysAndSlotKind() {
        SimState thu = at(THU, false);
        thu.rank[S_MAGICIAN] = 3;
        assertTrue(Engine.legalActions(rules, others, thu).contains(Action.meet(S_MAGICIAN)));
        SimState thuEve = at(THU, true);
        thuEve.rank[S_MAGICIAN] = 3;
        assertFalse(Engine.legalActions(rules, others, thuEve).contains(Action.meet(S_MAGICIAN)));
        SimState sat = at(24, false);
        sat.rank[S_MAGICIAN] = 3;
        assertFalse(Engine.legalActions(rules, others, sat).contains(Action.meet(S_MAGICIAN)));
        SimState maxed = at(THU, false);
        maxed.rank[S_MAGICIAN] = 10;
        assertFalse(Engine.legalActions(rules, others, maxed).contains(Action.meet(S_MAGICIAN)));
        SimState notYet = at(THU, false);
        assertFalse("rank 0 needs the unlock step", Engine.legalActions(rules, others, notYet).contains(Action.meet(S_MAGICIAN)));
    }

    @Test
    public void holidayInvitationIsAConditionalDaytimeRecordingChoice() {
        SimState s = at(33, false); // Mon May 4, holiday with an invitation
        s.rank[S_MAGICIAN] = 2;
        List<Action> legal = Engine.legalActions(rules, others, s);
        assertTrue(legal.contains(Action.invite(S_MAGICIAN)));
        assertFalse(legal.contains(Action.meet(S_MAGICIAN)));
    }

    @Test
    public void lockedSlotOffersNothing() {
        assertTrue(Engine.legalActions(rules, others, at(21, false)).isEmpty());
        assertTrue(Engine.legalActions(rules, others, at(48, true)).isEmpty());
    }

    @Test
    public void storyLinksAdvanceWhenTheDayIsCrossed() {
        SimState s = at(37, true);
        s.rank[S_FOOL] = 1;
        Engine.Applied a = Engine.apply(rules, others, s, Action.free());
        assertEquals(Rules.slot(38, false), a.state.slot);
        assertEquals(2, a.state.rank[S_FOOL]);
        assertTrue(Engine.legalActions(rules, others, a.state).stream().noneMatch(x -> x.target == S_FOOL && x.type == Action.Type.MEET));
    }

    @Test
    public void manualLinkWithoutStepsIsEstablishedByDate() {
        rules.links[S_MAGICIAN].unlock.steps.clear();
        SimState s = at(21, true);
        Engine.Applied a = Engine.apply(rules, others, s, Action.free());
        assertEquals(1, a.state.rank[S_MAGICIAN]);
    }

    // ------------------------------------------------------------------ unlock steps

    @Test
    public void unlockStepsFollowOrderGapsAndRequirements() {
        SimState s = at(26, false); // Mon Apr 27, emperor earliest day
        assertFalse("needs Academics Lv2", Engine.legalActions(rules, others, s).contains(Action.unlock(S_EMPEROR, 0)));
        s.statPts[ACADEMICS] = 20;
        assertTrue(Engine.legalActions(rules, others, s).contains(Action.unlock(S_EMPEROR, 0)));
        assertFalse("steps in order", Engine.legalActions(rules, others, s).contains(Action.unlock(S_EMPEROR, 1)));
        SimState after = Engine.apply(rules, others, s, Action.unlock(S_EMPEROR, 0)).state;
        assertEquals(1, after.unlockStep[S_EMPEROR]);
        assertEquals(26, after.lastStepDay[S_EMPEROR]);
        assertEquals("not established yet", 0, after.rank[S_EMPEROR]);

        SimState tooSoon = Engine.advanceTo(rules, others, after, Rules.slot(26 + 1, false));
        assertFalse(Engine.legalActions(rules, others, tooSoon).contains(Action.unlock(S_EMPEROR, 1)));
        SimState gapOk = Engine.advanceTo(rules, others, after, Rules.slot(30, false)); // Fri, emperor day, 4 days later
        assertTrue(Engine.legalActions(rules, others, gapOk).contains(Action.unlock(S_EMPEROR, 1)));
        Engine.Applied est = Engine.apply(rules, others, gapOk, Action.unlock(S_EMPEROR, 1));
        assertEquals(1, est.state.rank[S_EMPEROR]);
        assertTrue(est.predicted.rankUp);
        assertTrue(est.predicted.certain);
        assertEquals("rank-1 scene has no points in the fixture", 0, est.state.pts[S_EMPEROR]);
        assertFalse("established links get no more unlock steps",
                Engine.legalActions(rules, others, est.state).stream().anyMatch(x -> x.type == Action.Type.UNLOCK_STEP && x.target == S_EMPEROR));
    }

    @Test
    public void paidUnlockStepsNeedCashAndCostIt() {
        SimState s = at(31, true); // Sat May 2 evening (devil day)
        s.statPts[CHARM] = 15;
        assertFalse("¥20,000 needed", Engine.legalActions(rules, others, s).contains(Action.unlock(S_DEVIL, 0)));
        s.cash = 25_000;
        s.cashExact = true;
        assertTrue(Engine.legalActions(rules, others, s).contains(Action.unlock(S_DEVIL, 0)));
        Engine.Applied a = Engine.apply(rules, others, s, Action.unlock(S_DEVIL, 0));
        assertEquals(5_000, a.state.cash);
        assertEquals(-20_000, a.predicted.cashDelta);
        assertTrue("an exact cost keeps exact cash", a.state.cashExact);

        Profile buffered = RulesFixtures.others();
        buffered.cashBuffer = 10_000;
        assertTrue("the cash buffer applies to optional purchases only",
                Engine.legalActions(rules, buffered, s).contains(Action.unlock(S_DEVIL, 0)));
    }

    // ------------------------------------------------------------------ activities and money

    @Test
    public void activitiesNeedCashPlusBufferAndRespectWeekdays() {
        SimState mon = at(26, true); // Monday evening: arcade (Mon/Thu) and job (Mon-Wed)
        List<Action> legal = Engine.legalActions(rules, others, mon);
        assertFalse("¥1,500 needed", legal.contains(Action.activity(A_ARCADE)));
        assertTrue(legal.contains(Action.activity(A_JOB)));
        assertTrue(legal.contains(Action.activity(A_STUDY)));
        mon.cash = 1_500;
        assertTrue(Engine.legalActions(rules, others, mon).contains(Action.activity(A_ARCADE)));
        Profile buffered = RulesFixtures.others();
        buffered.cashBuffer = 500;
        assertFalse(Engine.legalActions(rules, buffered, mon).contains(Action.activity(A_ARCADE)));
        Profile excluded = RulesFixtures.others();
        excluded.excludedActivities = new String[]{"study_room"};
        assertFalse(Engine.legalActions(rules, excluded, mon).contains(Action.activity(A_STUDY)));
        SimState tue = at(27, true);
        tue.cash = 10_000;
        assertFalse(Engine.legalActions(rules, others, tue).contains(Action.activity(A_ARCADE)));
    }

    @Test
    public void jobsPayTheTierOfTheNextShift() {
        SimState s = at(26, true);
        Engine.Applied first = Engine.apply(rules, others, s, Action.activity(A_JOB));
        assertEquals(2_500, first.state.cash);
        assertEquals(1, first.state.shifts[A_JOB]);
        assertFalse("pay is a minimum", first.state.cashExact);
        assertTrue(first.assumptions.contains("job_pay_minimum"));
        assertEquals(1, first.predicted.statDelta[CHARM]);
        s.shifts[A_JOB] = 4;
        assertEquals("5th shift tier", 3_000, Engine.apply(rules, others, s, Action.activity(A_JOB)).state.cash);
    }

    @Test
    public void statGainsReportLevelUps() {
        SimState s = at(26, true);
        s.statPts[ACADEMICS] = 18;
        Engine.Applied a = Engine.apply(rules, others, s, Action.activity(A_STUDY));
        assertEquals(20, a.state.statPts[ACADEMICS]);
        assertEquals(2, a.predicted.statLvAfter[ACADEMICS]);
        assertEquals(-1, a.predicted.statLvAfter[CHARM]);
    }

    @Test
    public void activitiesThatCanNoLongerHelpAreNotOffered() {
        SimState s = at(26, true);
        s.statPts[ACADEMICS] = 230; // Lv6: studying is pointless
        assertFalse(Engine.legalActions(rules, others, s).contains(Action.activity(A_STUDY)));
        assertTrue("jobs still pay", Engine.legalActions(rules, others, s).contains(Action.activity(A_JOB)));
    }

    // ------------------------------------------------------------------ Tartarus, missing persons, holds

    @Test
    public void tartarusRespectsEveningEntryAndCap() {
        SimState s = at(26, true);
        assertTrue(Engine.legalActions(rules, others, s).contains(Action.tower(0)));
        assertFalse(Engine.legalActions(rules, others, at(26, false)).contains(Action.tower(0)));
        assertFalse("no entry that night", Engine.legalActions(rules, others, at(30, true)).contains(Action.tower(0)));
        s.towerVisits[0] = 4;
        assertFalse(Engine.legalActions(rules, others, s).contains(Action.tower(0)));
        Profile cap0 = RulesFixtures.others();
        cap0.towerVisitsPerBlock = 0;
        cap0.towerCapPerBlock = 0;
        assertFalse(Engine.legalActions(rules, cap0, at(26, true)).contains(Action.tower(0)));
    }

    @Test
    public void tartarusIncomeIsAnOptionalEstimate() {
        SimState s = at(26, true);
        Engine.Applied plain = Engine.apply(rules, others, s, Action.tower(0));
        assertEquals(1, plain.state.towerVisits[0]);
        assertEquals(0, plain.state.cash);
        Engine.Options o = new Engine.Options();
        o.towerIncomePerVisit = 4_000;
        Engine.Applied income = Engine.apply(rules, others, s, Action.tower(0), o);
        assertEquals(4_000, income.state.cash);
        assertTrue(income.assumptions.contains("tower_income_estimate"));
    }

    @Test
    public void missingPersonBlocksTheLinkUntilRescuedAndIsLostAfterTheDeadline() {
        SimState s = at(41, false); // inside 40..45
        s.rank[S_HIEROPHANT] = 3;
        assertFalse(Engine.legalActions(rules, others, s).contains(Action.meet(S_HIEROPHANT)));

        SimState eve = Engine.advanceTo(rules, others, s, Rules.slot(41, true));
        Engine.Applied visit = Engine.apply(rules, others, eve, Action.tower(1));
        assertEquals(1L, visit.state.rescued & 1L);
        assertTrue(visit.assumptions.contains("rescue_on_visit"));
        SimState next = visit.state;
        assertTrue("rescued: available again", Engine.legalActions(rules, others, next).contains(Action.meet(S_HIEROPHANT)));

        SimState unrescued = Engine.advanceTo(rules, others, s, Rules.slot(46, false));
        assertEquals("deadline passed", 1L, unrescued.rescueFailed & 1L);
        assertFalse(Engine.legalActions(rules, others, unrescued).contains(Action.meet(S_HIEROPHANT)));
        SimState later = Engine.advanceTo(rules, others, unrescued, Rules.slot(52, false));
        assertFalse("lost for good", Engine.legalActions(rules, others, later).contains(Action.meet(S_HIEROPHANT)));
    }

    @Test
    public void ruleHoldBlocksRankUpUntilItsDayAndCapsPoints() {
        SimState s = at(46, false); // Sun May 17; hold on rank 5 until day 50
        s.rescued = 1;
        s.rank[S_HIEROPHANT] = 4;
        s.pts[S_HIEROPHANT] = 20;
        Engine.Applied a = Engine.apply(rules, others, s, Action.meet(S_HIEROPHANT));
        assertEquals(4, a.state.rank[S_HIEROPHANT]);
        assertEquals("points capped at the threshold during a hold", 20, a.state.pts[S_HIEROPHANT]);
        assertTrue(a.assumptions.contains("rank_hold"));
        SimState after = Engine.advanceTo(rules, others, s, Rules.slot(50, false));
        assertEquals(5, Engine.apply(rules, others, after, Action.meet(S_HIEROPHANT)).state.rank[S_HIEROPHANT]);
    }

    @Test
    public void illegalTargetsAreRejected() {
        SimState s = at(26, true);
        try {
            Engine.apply(rules, others, s, Action.activity(99));
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
