package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.service.PlannerApi.FullReplanResult;
import app.p3r.dayplanner.v3.store.TestRules;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Manual deep search shares the full-replan lifecycle without changing automatic budgets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceDeepReplanTest extends ServiceTestBase {
    private static final int MAY12_DAY = TestRules.slot(TestRules.MAY_12, false);

    private void planned() {
        onboard(MAY12_DAY);
        settle();
        assertTrue(service.snapshot().planCurrent);
    }

    private CountDownLatch holdSecondCall() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        planner.blockedCall = 2;
        planner.blockFirstCall = gate;
        return gate;
    }

    private void awaitHeld() throws InterruptedException {
        assertTrue(planner.firstCallEntered.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void deepSearchUsesThirtySecondsAndAValidatedCurrentPlanWithoutAWrite() {
        planned();
        PlannerApi.Snapshot before = service.snapshot();
        int history = service.history(0).size();
        int validations = validator.calls.get();

        long ticket = service.requestDeepReplan();
        settle();

        assertEquals(2, planner.calls.get());
        Engine.Options options = planner.options.get(1);
        assertTrue(options.deepSearch);
        assertEquals(30_000, options.timeBudgetMillis);
        assertTrue(options.candidates >= 8);
        assertTrue("no automatic follow-up after a manual deep run", options.followUp);
        assertEquals("no stability bias", "", planner.previousIds.get(1));
        assertNotNull(options.incumbentPlan);
        assertNotSame("the comparison input is copied", before.plan, options.incumbentPlan);
        assertEquals(before.plan.planId, options.incumbentPlan.planId);
        assertEquals(before.stateRevision, options.incumbentPlan.stateRevision);
        assertEquals(before.profileRevision, options.incumbentPlan.profileRevision);
        assertEquals(before.observed.slot, options.incumbentPlan.fromSlot);
        assertEquals("current candidate and computed result are each checked", validations + 2, validator.calls.get());

        PlannerApi.Snapshot after = service.snapshot();
        assertEquals(before.stateRevision, after.stateRevision);
        assertEquals(before.profileRevision, after.profileRevision);
        assertEquals(history, service.history(0).size());
        assertEquals(ticket, after.fullReplanDoneTicket);
        assertTrue(after.fullReplanDeep);
        assertFalse(after.fullReplanning);
        assertEquals(FullReplanResult.UNCHANGED, after.fullReplanResult);

        service.requestFullReplan();
        settle();
        Engine.Options ordinary = planner.options.get(2);
        assertFalse(ordinary.deepSearch);
        assertNull(ordinary.incumbentPlan);
        assertEquals("ordinary full replan keeps its existing budget", 150, ordinary.timeBudgetMillis);
        assertFalse(service.snapshot().fullReplanDeep);
    }

    @Test
    public void aPlanFromAnOlderPolicyIsNeverADeepComparisonCandidate() {
        planned();
        service.snapshot().plan.policyVersion = Plan.CURRENT_POLICY_VERSION - 1;
        service.requestDeepReplan();
        settle();
        assertTrue(planner.options.get(1).deepSearch);
        assertNull(planner.options.get(1).incumbentPlan);
        assertTrue(service.snapshot().planCurrent);
    }

    @Test
    public void anInvalidCurrentPlanIsRemovedBeforeThePlannerSeesIt() {
        planned();
        validator.problems = List.of("invalid comparison fixture");
        service.requestDeepReplan();
        settle();
        assertNull(planner.options.get(1).incumbentPlan);
        assertEquals("the fresh output is independently rejected too", FullReplanResult.FAILED,
            service.snapshot().fullReplanResult);
    }

    @Test
    public void deepImprovementWithEqualGoalsIsInstalledEvenWhenTheSearchReachesItsBudget() {
        Profile profile = service.snapshot().profile.copy();
        profile.tier[TestRules.EMPEROR] = Profile.Tier.MUST;
        assertTrue(service.saveProfile(id(), service.snapshot().profileRevision, profile).ok);
        planner.shape = (plan, n) -> {
            plan.summary.endRanks[TestRules.EMPEROR] = 8;
            plan.summary.endRanks[TestRules.MAGICIAN] = n == 1 ? 2 : 3;
            if (n == 2) {
                plan.budgetExhausted = true;
                plan.steps.get(2).action = "free";
            }
        };
        planned();
        Plan before = service.snapshot().plan;
        service.requestDeepReplan();
        settle();

        PlannerApi.Snapshot after = service.snapshot();
        assertEquals("fake-2", after.plan.planId);
        assertEquals(8, after.plan.summary.endRanks[TestRules.EMPEROR]);
        assertEquals(3, after.plan.summary.endRanks[TestRules.MAGICIAN]);
        assertTrue(after.plan.budgetExhausted);
        assertTrue("a manual deep run does not start an automatic extension", after.plan.followUp);
        assertEquals(FullReplanResult.CHANGED, after.fullReplanResult);
        assertEquals(2, planner.calls.get());
        assertFalse("ordinary full replan retains its existing budget rule",
            PlannerService.acceptsFull(profile, after.plan, before));
    }

    @Test
    public void aPlanForADifferentObservedCursorCannotRejectTheNewDeepResult() {
        Profile profile = service.snapshot().profile.copy();
        profile.tier[TestRules.EMPEROR] = Profile.Tier.MUST;
        assertTrue(service.saveProfile(id(), service.snapshot().profileRevision, profile).ok);
        planner.shape = (plan, n) -> plan.summary.endRanks[TestRules.EMPEROR] = n == 1 ? 10 : 8;
        planned();
        // Its revision metadata alone looks current, but it starts at a different observed state.
        service.snapshot().plan.fromSlot++;
        service.requestDeepReplan();
        settle();
        assertNull(planner.options.get(1).incumbentPlan);
        assertEquals("fake-2", service.snapshot().plan.planId);
        assertEquals(8, service.snapshot().plan.summary.endRanks[TestRules.EMPEROR]);
        assertEquals(FullReplanResult.CHANGED, service.snapshot().fullReplanResult);
    }

    @Test
    public void cancellationKeepsThePlanAndTheNextAutomaticRunUsesItsNormalBudget() throws Exception {
        planned();
        CountDownLatch gate = holdSecondCall();
        long ticket = service.requestDeepReplan();
        awaitHeld();
        try {
            assertEquals("pending manual requests share one ticket", ticket, service.requestFullReplan());
            assertEquals(ticket, service.requestDeepReplan());
            assertTrue(service.snapshot().fullReplanDeep);
            service.cancelFullReplan();
            assertTrue(planner.options.get(1).cancelled.get());
            assertFalse(service.snapshot().fullReplanning);
            assertEquals(FullReplanResult.CANCELLED, service.snapshot().fullReplanResult);
        } finally {
            gate.countDown();
        }
        settle();
        assertEquals("fake-1", service.snapshot().plan.planId);
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        settle();
        Engine.Options automatic = planner.options.get(2);
        assertEquals(50, automatic.timeBudgetMillis);
        assertFalse(automatic.deepSearch);
        assertNull(automatic.incumbentPlan);
        assertEquals(ticket, service.snapshot().fullReplanDoneTicket);
    }

    @Test
    public void aRecordRestartsDeepSearchAtTheNewStateWithoutUsingTheStalePlan() throws Exception {
        planned();
        CountDownLatch gate = holdSecondCall();
        long ticket = service.requestDeepReplan();
        awaitHeld();
        try {
            assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
            assertTrue(planner.options.get(1).cancelled.get());
        } finally {
            gate.countDown();
        }
        settle();
        assertEquals(3, planner.calls.get());
        Engine.Options fresh = planner.options.get(2);
        assertTrue(fresh.deepSearch);
        assertEquals(30_000, fresh.timeBudgetMillis);
        assertNull("old revisions cannot seed the new search", fresh.incumbentPlan);
        assertEquals("", planner.previousIds.get(2));
        assertEquals(ticket, service.snapshot().fullReplanDoneTicket);
        assertTrue(service.snapshot().planCurrent);
    }
}
