package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.store.TestRules;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

/**
 * Audit F11 / fix (c): a plan that ran out of time ({@link Plan#budgetExhausted}) is installed at
 * once, then computed exactly once more for the same revisions with twice the budget; the result
 * replaces it only when it is complete or better. Its note promises that run, so the run must happen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceFollowUpTest extends ServiceTestBase {
    private static final int MAY12_DAY = TestRules.slot(TestRules.MAY_12, false);

    private void exhaustedCalls(Integer... calls) {
        java.util.List<Integer> list = Arrays.asList(calls);
        planner.shape = (plan, n) -> plan.budgetExhausted = list.contains(n);
    }

    @Test
    public void anExhaustedPlanGetsExactlyOneFollowUpWithTwiceTheBudget() {
        exhaustedCalls(1);
        long rev = onboard(MAY12_DAY);
        settle();

        assertEquals("the plan and its one follow-up", 2, planner.calls.get());
        Engine.Options first = planner.options.get(0);
        Engine.Options second = planner.options.get(1);
        assertEquals(50, first.timeBudgetMillis);
        assertFalse(first.followUp);
        assertEquals(100, second.timeBudgetMillis);
        assertTrue(second.followUp);
        assertEquals("the follow-up starts from the exhausted plan", "fake-1", planner.previousIds.get(1));

        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("the complete follow-up replaces it", "fake-2", s.plan.planId);
        assertFalse(s.plan.budgetExhausted);
        assertTrue("the service marks the follow-up plan", s.plan.followUp);
        assertNull(s.plan.budgetNoteZh());
        assertTrue(s.planCurrent);
        assertFalse(s.planning);
        assertEquals(rev, s.plan.stateRevision);

        service.requestReplan();
        settle();
        assertEquals("nothing more for these revisions", 2, planner.calls.get());

        restart();
        settle();
        assertEquals("the installed follow-up survives a restart", "fake-2", service.snapshot().plan.planId);
    }

    @Test
    public void aFollowUpThatRunsOutAgainIsNotRepeatedAndOnlyReplacesWhenBetter() {
        exhaustedCalls(1, 2, 3, 4);
        onboard(MAY12_DAY);
        settle();
        assertEquals(2, planner.calls.get());
        assertEquals("not better: the first plan stays", "fake-1", service.snapshot().plan.planId);
        assertTrue(service.snapshot().planCurrent);

        // A new write: one new plan, and one follow-up for the new revisions.
        planner.shape = (plan, n) -> {
            plan.budgetExhausted = true;
            if (n == 4) plan.summary.endRanks[TestRules.EMPEROR] = 10;
        };
        PlannerApi.Receipt r = record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED);
        assertTrue(r.errorZh, r.ok);
        settle();
        assertEquals(4, planner.calls.get());
        assertEquals(100, planner.options.get(3).timeBudgetMillis);
        assertEquals("better (one more MAX) although it ran out again", "fake-4", service.snapshot().plan.planId);
        Plan installed = service.snapshot().plan;
        assertTrue(installed.followUp);
        assertFalse("no further run is queued, and the plan says so", installed.followUpPending());
        assertEquals(Plan.BUDGET_NOTE_DONE, installed.budgetNoteZh());
    }

    /**
     * Audit F11: a follow-up that is not better leaves the first plan installed; that plan must no
     * longer promise 「稍后会自动再算一次」, in memory and after a restart, and no other run follows.
     */
    @Test
    public void aFollowUpThatDoesNotReplaceThePlanEndsItsPromiseOfAnotherRun() {
        planner.shape = (plan, n) -> {
            plan.budgetExhausted = true; // both runs run out; the second is no better
            plan.summary.notesZh.add(0, n == 1 ? Plan.BUDGET_NOTE_PENDING : Plan.BUDGET_NOTE_DONE);
        };
        onboard(MAY12_DAY);
        settle();
        assertEquals(2, planner.calls.get());
        Plan installed = service.snapshot().plan;
        assertEquals("fake-1", installed.planId);
        assertTrue(installed.followUp);
        assertFalse(installed.followUpPending());
        assertEquals(Plan.BUDGET_NOTE_DONE, installed.budgetNoteZh());
        assertEquals(Plan.BUDGET_NOTE_DONE, installed.summary.notesZh.get(0));
        assertFalse(installed.summary.notesZh.contains(Plan.BUDGET_NOTE_PENDING));

        restart();
        settle();
        assertEquals("the flag is stored: no second follow-up after a restart", 2, planner.calls.get());
        Plan reloaded = service.snapshot().plan;
        assertEquals("fake-1", reloaded.planId);
        assertTrue(reloaded.followUp);
        assertEquals(Plan.BUDGET_NOTE_DONE, reloaded.summary.notesZh.get(0));
    }

    /** A plan that ran out of time and has not had its follow-up yet still promises it. */
    @Test
    public void aPlanWaitingForItsFollowUpSaysSo() {
        Plan plan = new Plan();
        plan.budgetExhausted = true;
        assertTrue(plan.followUpPending());
        assertEquals(Plan.BUDGET_NOTE_PENDING, plan.budgetNoteZh());
    }

    @Test
    public void aCompletePlanNeedsNoFollowUp() {
        onboard(MAY12_DAY);
        settle();
        assertEquals(1, planner.calls.get());
        assertFalse(planner.options.get(0).followUp);
    }

    @Test
    public void aFailedFollowUpKeepsTheInstalledPlanWithoutAnError() {
        exhaustedCalls(1);
        planner.shape = (plan, n) -> {
            plan.budgetExhausted = n == 1;
            if (n == 2) validator.problems = Collections.singletonList("broken");
        };
        onboard(MAY12_DAY);
        settle();
        validator.problems = Collections.emptyList();
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals(2, planner.calls.get());
        assertEquals("fake-1", s.plan.planId);
        assertNull(s.planErrorZh);
        assertTrue(s.planCurrent);
        assertTrue("the failed follow-up still counts as the one follow-up", s.plan.followUp);
        assertFalse(s.plan.followUpPending());
    }

    @Test
    public void improvesComparesGoalsInPriorityOrderThenMaxedLinksThenRanks() {
        Profile p = new Profile();
        p.tier[3] = Profile.Tier.MUST;
        p.tier[5] = Profile.Tier.WANT;
        p.tier[7] = Profile.Tier.WANT;
        Plan current = exhausted(new int[22]);
        current.summary.endRanks[3] = 9;
        current.summary.endRanks[5] = 10;
        Plan firstGoalUp = exhausted(current.summary.endRanks.clone());
        firstGoalUp.summary.endRanks[3] = 10;
        firstGoalUp.summary.endRanks[5] = 6;
        assertTrue("the MUST goal decides", PlannerService.improves(p, firstGoalUp, current));
        assertFalse(PlannerService.improves(p, current, firstGoalUp));

        Plan moreMax = exhausted(current.summary.endRanks.clone());
        moreMax.summary.endRanks[12] = 10;
        moreMax.summary.endRanks[13] = 0;
        assertTrue(PlannerService.improves(p, moreMax, current));
        Plan same = exhausted(current.summary.endRanks.clone());
        assertFalse("equal is not better", PlannerService.improves(p, same, current));
        Plan complete = exhausted(current.summary.endRanks.clone());
        complete.budgetExhausted = false;
        assertTrue("with the goals tied a complete plan replaces", PlannerService.improves(p, complete, current));
        Plan completeButWorse = exhausted(new int[22]);
        completeButWorse.budgetExhausted = false;
        assertFalse("a complete plan never gives up a goal the installed one reaches",
                PlannerService.improves(p, completeButWorse, current));
        Plan beyondTarget = exhausted(current.summary.endRanks.clone());
        p.target[5] = 7;
        beyondTarget.summary.endRanks[5] = 9;
        current.summary.endRanks[5] = 7;
        current.summary.endRanks[12] = 10;
        assertFalse("past its target a goal ties; the other links decide", PlannerService.improves(p, beyondTarget, current));
        assertTrue(PlannerService.improves(p, same, null));
        assertFalse(PlannerService.improves(p, null, current));
        assertEquals(5_000, PlannerService.followUpBudget(2_500));
        assertEquals(100, PlannerService.followUpBudget(50));
        assertEquals(7_998, PlannerService.followUpBudget(3_999));
        assertEquals(8_000, PlannerService.followUpBudget(4_000));
        assertEquals(8_000, PlannerService.followUpBudget(4_001));
        assertEquals(8_000, PlannerService.followUpBudget(Long.MAX_VALUE));
        assertEquals(0, PlannerService.followUpBudget(0));
        assertEquals(0, PlannerService.followUpBudget(Long.MIN_VALUE));
    }

    private static Plan exhausted(int[] endRanks) {
        Plan plan = new Plan();
        plan.budgetExhausted = true;
        plan.summary.endRanks = endRanks;
        assertNotNull(plan.summary);
        return plan;
    }
}
