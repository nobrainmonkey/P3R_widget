package app.p3r.dayplanner.v3.engine;

import static app.p3r.dayplanner.v3.engine.RulesFixtures.ACADEMICS;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.A_JOB;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.A_STUDY;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.CHARM;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_DEVIL;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_EMPEROR;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_HIEROPHANT;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_MAGICIAN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONException;
import org.junit.Test;

/** Player-confirmed outcomes (Engine.applyOutcome) for every {@link Outcome.Code}. */
public class OutcomeTest {
    private final Rules rules = RulesFixtures.small();
    private final Profile profile = RulesFixtures.others();

    /** Thursday, magician at rank 4 with 5 points (needs 15). */
    private SimState magician4() {
        SimState s = RulesFixtures.newGame(rules, Rules.slot(22, false));
        s.rank[S_MAGICIAN] = 4;
        s.pts[S_MAGICIAN] = 5;
        s.cash = 3_000;
        s.cashExact = true;
        return s;
    }

    private SimState outcome(SimState s, Action a, Outcome o) {
        return Engine.applyOutcome(rules, profile, s, a, o);
    }

    @Test
    public void asPredictedEqualsTheForecast() throws JSONException {
        SimState s = magician4();
        SimState forecast = Engine.apply(rules, profile, s, Action.meet(S_MAGICIAN)).state;
        SimState observed = outcome(s, Action.meet(S_MAGICIAN), Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals(forecast.toJson().toString(), observed.toJson().toString());
        assertEquals(s.slot + 1, observed.slot);
    }

    @Test
    public void rankUpSetsTheRankAndResetsPoints() {
        SimState up = outcome(magician4(), Action.meet(S_MAGICIAN), Outcome.of(Outcome.Code.RANK_UP));
        assertEquals(5, up.rank[S_MAGICIAN]);
        assertEquals("rank-5 scene estimate", 15, up.pts[S_MAGICIAN]);
        assertFalse(up.ready[S_MAGICIAN]);

        Outcome to7 = Outcome.of(Outcome.Code.RANK_UP);
        to7.rank = 7;
        assertEquals(7, outcome(magician4(), Action.meet(S_MAGICIAN), to7).rank[S_MAGICIAN]);
    }

    @Test
    public void noRankUpForecastsExactlyOneMoreOrdinaryMeeting() {
        SimState s = magician4();
        SimState after = outcome(s, Action.meet(S_MAGICIAN), Outcome.of(Outcome.Code.NO_RANK_UP));
        assertEquals(4, after.rank[S_MAGICIAN]);
        // max(5 + 10, 15 - 10) = 15 → the next meeting ranks up
        assertEquals(15, after.pts[S_MAGICIAN]);

        SimState low = magician4();
        low.rank[S_MAGICIAN] = 7; // needs 22
        low.pts[S_MAGICIAN] = 0;
        SimState lowAfter = outcome(low, Action.meet(S_MAGICIAN), Outcome.of(Outcome.Code.NO_RANK_UP));
        assertEquals("max(0 + 10, 22 - 10)", 12, lowAfter.pts[S_MAGICIAN]);
        SimState twoLater = Engine.advanceTo(rules, profile, lowAfter, Rules.slot(23, false));
        Engine.Applied next = Engine.apply(rules, profile, twoLater, Action.meet(S_MAGICIAN));
        assertFalse("one ordinary meeting first", next.predicted.rankUp);
        assertEquals(22, next.state.pts[S_MAGICIAN]);
    }

    @Test
    public void readyCueMakesTheNextMeetingACertainRankUp() {
        Outcome o = Outcome.of(Outcome.Code.NO_RANK_UP);
        o.readyCue = true;
        SimState after = outcome(magician4(), Action.meet(S_MAGICIAN), o);
        assertTrue(after.ready[S_MAGICIAN]);
        SimState fri = Engine.advanceTo(rules, profile, after, Rules.slot(23, false));
        Engine.Applied next = Engine.apply(rules, profile, fri, Action.meet(S_MAGICIAN));
        assertTrue(next.predicted.rankUp);
        assertTrue(next.predicted.certain);
    }

    @Test
    public void skippedAndNotDoneConsumeTheSlotWithoutGains() throws JSONException {
        SimState s = magician4();
        SimState skipped = outcome(s, Action.meet(S_MAGICIAN), Outcome.of(Outcome.Code.SKIPPED));
        SimState expected = Engine.advanceTo(rules, profile, s, s.slot + 1);
        assertEquals(expected.toJson().toString(), skipped.toJson().toString());
        SimState notDone = outcome(s, Action.activity(A_STUDY), Outcome.of(Outcome.Code.NOT_DONE));
        assertEquals(0, notDone.statPts[ACADEMICS]);
        assertEquals(s.slot + 1, notDone.slot);
    }

    @Test
    public void holdFreezesRankUpsUntilProgressIsReported() {
        SimState s = magician4();
        s.pts[S_MAGICIAN] = 30;
        Outcome hold = Outcome.of(Outcome.Code.NOT_DONE);
        hold.reason = "hold";
        SimState held = outcome(s, Action.meet(S_MAGICIAN), hold);
        assertEquals(5, held.heldRank[S_MAGICIAN]);

        SimState fri = Engine.advanceTo(rules, profile, held, Rules.slot(23, false));
        Engine.Applied meet = Engine.apply(rules, profile, fri, Action.meet(S_MAGICIAN));
        assertFalse(meet.predicted.rankUp);
        assertTrue(meet.assumptions.contains("rank_hold"));
        assertTrue("meetings stay possible", Engine.legalActions(rules, profile, fri).contains(Action.meet(S_MAGICIAN)));

        SimState progressed = Engine.applyOutcome(rules, profile, fri, Action.meet(S_MAGICIAN), Outcome.of(Outcome.Code.RANK_UP));
        assertEquals(5, progressed.rank[S_MAGICIAN]);
        assertEquals("progress clears the hold", 0, progressed.heldRank[S_MAGICIAN]);
    }

    @Test
    public void otherAppliesExplicitVisibleValues() {
        Outcome o = Outcome.of(Outcome.Code.OTHER);
        o.rank = 6;
        o.statLv = new int[]{2, -1, -1};
        o.cashBalance = 12_345;
        SimState after = outcome(magician4(), Action.meet(S_MAGICIAN), o);
        assertEquals(6, after.rank[S_MAGICIAN]);
        assertEquals("rank-6 scene estimate", 15, after.pts[S_MAGICIAN]);
        assertEquals(20, after.statPts[ACADEMICS]);
        assertEquals(12_345, after.cash);
        assertTrue(after.cashExact);

        Outcome same = Outcome.of(Outcome.Code.OTHER);
        same.rank = 4;
        SimState predictedUp = magician4();
        predictedUp.pts[S_MAGICIAN] = 15;
        SimState kept = outcome(predictedUp, Action.meet(S_MAGICIAN), same);
        assertEquals("player says no rank-up", 4, kept.rank[S_MAGICIAN]);
        assertEquals(25, kept.pts[S_MAGICIAN]);
    }

    @Test
    public void statLevelsClampHiddenPoints() {
        SimState s = RulesFixtures.newGame(rules, Rules.slot(26, true));
        s.statPts[ACADEMICS] = 18;
        Outcome o = Outcome.of(Outcome.Code.AS_PREDICTED);
        o.statLv = new int[]{1, 3, -1};
        SimState after = outcome(s, Action.activity(A_STUDY), o);
        assertEquals("forecast 20 but the Lv did not change: capped below Lv2", 19, after.statPts[ACADEMICS]);
        assertEquals("Charm Lv3 raises the lower bound", 30, after.statPts[CHARM]);
    }

    @Test
    public void cashBalanceSetsCashExactly() {
        SimState s = RulesFixtures.newGame(rules, Rules.slot(26, true));
        s.cash = 1_000;
        SimState job = outcome(s, Action.activity(A_JOB), Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals(3_500, job.cash);
        assertFalse("job pay is a minimum", job.cashExact);
        Outcome o = Outcome.of(Outcome.Code.AS_PREDICTED);
        o.cashBalance = 4_200;
        SimState exact = outcome(s, Action.activity(A_JOB), o);
        assertEquals(4_200, exact.cash);
        assertTrue(exact.cashExact);
    }

    @Test
    public void unlockOutcomes() {
        SimState s = RulesFixtures.newGame(rules, Rules.slot(31, true));
        s.cash = 20_000;
        s.statPts[CHARM] = 15;
        SimState paid = outcome(s, Action.unlock(S_DEVIL, 0), Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals(1, paid.unlockStep[S_DEVIL]);
        assertEquals(0, paid.cash);
        SimState notDone = outcome(s, Action.unlock(S_DEVIL, 0), Outcome.of(Outcome.Code.NOT_DONE));
        assertEquals(0, notDone.unlockStep[S_DEVIL]);
        assertEquals(20_000, notDone.cash);

        SimState emp = RulesFixtures.newGame(rules, Rules.slot(30, false));
        emp.statPts[ACADEMICS] = 20;
        emp.unlockStep[S_EMPEROR] = 1;
        emp.lastStepDay[S_EMPEROR] = 26;
        SimState established = outcome(emp, Action.unlock(S_EMPEROR, 1), Outcome.of(Outcome.Code.RANK_UP));
        assertEquals(1, established.rank[S_EMPEROR]);
    }

    @Test
    public void towerOutcomeCountsTheVisitAndConfirmedRescue() {
        SimState s = RulesFixtures.newGame(rules, Rules.slot(41, true));
        SimState visited = outcome(s, Action.tower(1), Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals(1, visited.towerVisits[1]);
        assertEquals(1L, visited.rescued & 1L);
        SimState skipped = outcome(s, Action.tower(1), Outcome.of(Outcome.Code.SKIPPED));
        assertEquals(0, skipped.towerVisits[1]);
        assertEquals(0L, skipped.rescued);
        SimState late = Engine.advanceTo(rules, profile, skipped, Rules.slot(46, false));
        assertEquals("not rescued by the deadline", 1L, late.rescueFailed & 1L);
        assertFalse(Engine.legalActions(rules, profile, late).contains(Action.meet(S_HIEROPHANT)));
    }

    /**
     * F1: the result sheet records 「升到 RANK 5 了」 and 「见面了」 as AS_PREDICTED with the rank the
     * player confirmed. Replaying such a row under another profile (here: magician becomes a goal,
     * so the goal buffer raises the points needed from 15 to 25, or stops being one) must keep the
     * confirmed rank, as OTHER does.
     */
    @Test
    public void asPredictedKeepsTheConfirmedRankWhateverTheProfileSays() {
        Profile goal = RulesFixtures.must(S_MAGICIAN);
        assertTrue(goal.goalBuffer);
        Action meet = Action.meet(S_MAGICIAN);

        // 「升到 RANK 5 了」 recorded without goals (15 points are enough)…
        SimState enough = magician4();
        enough.pts[S_MAGICIAN] = 15;
        Outcome up = Outcome.of(Outcome.Code.AS_PREDICTED);
        up.rank = 5;
        SimState recorded = outcome(enough, meet, up);
        assertEquals(5, recorded.rank[S_MAGICIAN]);
        // …replayed after magician became a 必须 goal (the forecast alone would say 25 are needed).
        assertEquals("without the confirmed rank the profile decides", 4,
                Engine.applyOutcome(rules, goal, enough, meet, Outcome.of(Outcome.Code.AS_PREDICTED)).rank[S_MAGICIAN]);
        SimState replayed = Engine.applyOutcome(rules, goal, enough, meet, up);
        assertEquals("the confirmed rank-up stays", 5, replayed.rank[S_MAGICIAN]);
        assertEquals("rank-5 scene estimate, as for RANK_UP", 15, replayed.pts[S_MAGICIAN]);
        assertEquals(enough.slot + 1, replayed.slot);

        // 「见面了」 (no rank-up) recorded while magician was a goal…
        Outcome met = Outcome.of(Outcome.Code.AS_PREDICTED);
        met.rank = 4;
        SimState asGoal = Engine.applyOutcome(rules, goal, enough, meet, met);
        assertEquals(4, asGoal.rank[S_MAGICIAN]);
        assertEquals(25, asGoal.pts[S_MAGICIAN]);
        // …replayed after the goal was cleared (the forecast alone would now rank up): no invented rank-up.
        SimState cleared = outcome(enough, meet, met);
        assertEquals(4, cleared.rank[S_MAGICIAN]);
        assertFalse(cleared.ready[S_MAGICIAN]);
        assertEquals("one more ordinary meeting", 25, cleared.pts[S_MAGICIAN]);

        // Same profile, same prediction: identical to the plain forecast.
        SimState plain = magician4();
        Outcome ordinary = Outcome.of(Outcome.Code.AS_PREDICTED);
        ordinary.rank = 4;
        assertEquals(Engine.apply(rules, profile, plain, meet).state.pts[S_MAGICIAN],
                outcome(plain, meet, ordinary).pts[S_MAGICIAN]);
        // A ready cue recorded with 「见面了」 still counts.
        Outcome cue = Outcome.of(Outcome.Code.AS_PREDICTED);
        cue.rank = 4;
        cue.readyCue = true;
        assertTrue(outcome(plain, meet, cue).ready[S_MAGICIAN]);
    }

    @Test
    public void asPredictedUnlockKeepsItsConfirmedRank() {
        SimState emp = RulesFixtures.newGame(rules, Rules.slot(30, false));
        emp.statPts[ACADEMICS] = 20;
        emp.unlockStep[S_EMPEROR] = 1;
        emp.lastStepDay[S_EMPEROR] = 26;
        Outcome o = Outcome.of(Outcome.Code.AS_PREDICTED);
        o.rank = 1;
        SimState established = outcome(emp, Action.unlock(S_EMPEROR, 1), o);
        assertEquals(1, established.rank[S_EMPEROR]);
        assertEquals(2, established.unlockStep[S_EMPEROR]);
        SimState forecast = Engine.apply(rules, profile, emp, Action.unlock(S_EMPEROR, 1)).state;
        assertEquals(forecast.pts[S_EMPEROR], established.pts[S_EMPEROR]);
    }

    @Test
    public void inputStateIsNeverMutated() throws JSONException {
        SimState s = magician4();
        String before = s.toJson().toString();
        for (Outcome.Code c : Outcome.Code.values()) outcome(s, Action.meet(S_MAGICIAN), Outcome.of(c));
        assertEquals(before, s.toJson().toString());
    }
}
