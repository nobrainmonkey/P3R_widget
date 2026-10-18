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
import app.p3r.dayplanner.v3.service.PlannerApi.FullReplanResult;
import app.p3r.dayplanner.v3.store.Ledger;
import app.p3r.dayplanner.v3.store.PlanDb;
import app.p3r.dayplanner.v3.store.TestRules;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 从头重新安排 (docs/DESIGN_v3.md §16, spec §1.1): a full replan re-reads the records, computes with
 * no previous plan and three times the budget even when the plan is current, is not a write, and
 * ends every request exactly once with a result for the player.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceFullReplanTest extends ServiceTestBase {
    private static final int MAY12_DAY = TestRules.slot(TestRules.MAY_12, false);

    /** Onboards on 5/12 and waits for the first plan (fake-1). */
    private void planned() {
        onboard(MAY12_DAY);
        settle();
        assertEquals("fake-1", service.snapshot().plan.planId);
    }

    /** Holds planner call {@code n} (1-based) until the returned gate opens. */
    private CountDownLatch hold(int n) {
        CountDownLatch gate = new CountDownLatch(1);
        planner.blockedCall = n;
        planner.blockFirstCall = gate;
        return gate;
    }

    private void awaitHeld() throws InterruptedException {
        assertTrue("the held call did not start", planner.firstCallEntered.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void aCurrentPlanIsComputedAgainFromScratchWithoutAWrite() {
        planned();
        PlannerApi.Snapshot before = service.snapshot();
        int historyRows = service.history(0).size();
        int refreshes = widgetRefreshes.get();

        long ticket = service.requestFullReplan();
        assertTrue(ticket > 0);
        settle();

        assertEquals(2, planner.calls.get());
        assertEquals("no stability bias: computed without the installed plan", "", planner.previousIds.get(1));
        Engine.Options options = planner.options.get(1);
        assertEquals("three times the budget", 150, options.timeBudgetMillis);
        assertEquals("candidates stay (they are in the plan id)", 4, options.candidates);
        assertTrue("no automatic 2x run after it", options.followUp);

        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("not a write", before.stateRevision, s.stateRevision);
        assertEquals(before.profileRevision, s.profileRevision);
        assertEquals("nothing in 记录历史", historyRows, service.history(0).size());
        assertEquals("fake-2", s.plan.planId);
        assertTrue(s.planCurrent);
        assertFalse(s.planning);
        assertFalse(s.fullReplanning);
        assertNull(s.planErrorZh);
        assertEquals(ticket, s.fullReplanTicket);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertEquals("the same steps", FullReplanResult.UNCHANGED, s.fullReplanResult);
        assertTrue("widgets re-render with the result", widgetRefreshes.get() > refreshes);

        service.requestReplan();
        settle();
        assertEquals("the installed plan is current: nothing more runs", 2, planner.calls.get());
    }

    @Test
    public void aChangedPlanIsStoredAndSurvivesARestart() {
        planned();
        planner.shape = (plan, n) -> {
            if (n == 2) plan.steps.get(2).action = "free";
        };
        long ticket = service.requestFullReplan();
        settle();
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals(FullReplanResult.CHANGED, s.fullReplanResult);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertEquals("fake-2", s.plan.planId);

        restart();
        settle();
        s = service.snapshot();
        assertEquals("the stored plan is the new one", "fake-2", s.plan.planId);
        assertEquals("free", s.plan.at(MAY12_DAY + 2).action);
        assertTrue(s.planCurrent);
        assertEquals("not computed again after the restart", 2, planner.calls.get());
        assertFalse(s.fullReplanning);
        assertEquals("tickets are per process", 0, s.fullReplanDoneTicket);
        assertNull(s.fullReplanResult);
    }

    @Test
    public void aSecondRequestWhileOneIsPendingGetsTheSameTicketAndNoSecondComputation() throws Exception {
        planned();
        CountDownLatch gate = hold(2);
        long first = service.requestFullReplan();
        awaitHeld();
        long second = service.requestFullReplan();
        assertEquals(first, second);

        PlannerApi.Snapshot during = service.snapshot();
        assertTrue(during.fullReplanning);
        assertTrue("the installed plan stays current meanwhile", during.planCurrent);
        assertFalse("so this is not the ordinary 正在重新安排", during.planning);
        assertEquals(first, during.fullReplanTicket);
        assertEquals(0, during.fullReplanDoneTicket);
        assertNull(during.fullReplanResult);

        gate.countDown();
        settle();
        assertEquals(2, planner.calls.get());
        assertEquals(first, service.snapshot().fullReplanDoneTicket);

        long third = service.requestFullReplan();
        assertEquals("a new request afterwards is a new ticket", first + 1, third);
        settle();
        assertEquals(3, planner.calls.get());
        assertEquals(third, service.snapshot().fullReplanDoneTicket);
    }

    @Test
    public void stoppingKeepsTheCurrentPlan() throws Exception {
        planned();
        CountDownLatch gate = hold(2);
        long ticket = service.requestFullReplan();
        awaitHeld();

        service.cancelFullReplan();
        assertTrue("the computation is asked to stop", planner.options.get(1).cancelled.get());
        PlannerApi.Snapshot s = service.snapshot();
        assertFalse(s.fullReplanning);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertEquals(FullReplanResult.CANCELLED, s.fullReplanResult);

        gate.countDown();
        settle();
        s = service.snapshot();
        assertEquals("fake-1", s.plan.planId);
        assertTrue(s.planCurrent);
        assertNull(s.planErrorZh);
        assertEquals(FullReplanResult.CANCELLED, s.fullReplanResult);
        assertEquals("nothing else runs", 2, planner.calls.get());

        service.cancelFullReplan();
        assertEquals("nothing pending: no-op", ticket, service.snapshot().fullReplanDoneTicket);
    }

    /** An out-of-date plan with a failed replan behind it, before a full request (fake-1 stays installed). */
    private void staleWithAnError() {
        planned();
        validator.problems = List.of("slot 83 double-booked");
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        settle();
        validator.problems = Collections.emptyList();
        PlannerApi.Snapshot s = service.snapshot();
        assertFalse(s.planCurrent);
        assertNotNull(s.planErrorZh);
        assertEquals(2, planner.calls.get());
    }

    @Test
    public void stoppingWithAnOutOfDatePlanLetsAnOrdinaryReplanRun() throws Exception {
        staleWithAnError();
        CountDownLatch gate = hold(3);
        long ticket = service.requestFullReplan();
        awaitHeld();
        PlannerApi.Snapshot during = service.snapshot();
        assertTrue(during.fullReplanning);
        assertTrue(during.planning);

        service.cancelFullReplan();
        gate.countDown();
        settle();

        assertEquals(4, planner.calls.get());
        assertEquals("an ordinary replan: the installed plan is its hint again", "fake-1", planner.previousIds.get(3));
        assertEquals(50, planner.options.get(3).timeBudgetMillis);
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("fake-4", s.plan.planId);
        assertTrue(s.planCurrent);
        assertNull(s.planErrorZh);
        assertFalse(s.fullReplanning);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertNull("nothing to report: the ordinary replan shows its own summary", s.fullReplanResult);
    }

    /**
     * The full job claims the budget follow-up of its revisions when it is handed out. Stopped while
     * the plan is out of date it installs nothing, so the ordinary replan that runs instead keeps
     * its one 2× follow-up when it runs out of time (the 3.1.0 review found the note 「这次没来得及算完，
     * 稍后会自动再算一次」 left standing with no run behind it).
     */
    @Test
    public void stoppingWithAnOutOfDatePlanKeepsTheOrdinaryReplansFollowUp() throws Exception {
        staleWithAnError();
        planner.shape = (plan, n) -> exhaustedOn(plan, n, 4);
        CountDownLatch gate = hold(3);
        service.requestFullReplan();
        awaitHeld();
        service.cancelFullReplan();
        gate.countDown();
        settle();

        assertEquals("the stopped full job, the ordinary replan, its follow-up", 5, planner.calls.get());
        assertEquals(50, planner.options.get(3).timeBudgetMillis);
        assertTrue(planner.options.get(4).followUp);
        assertEquals("twice the budget", 100, planner.options.get(4).timeBudgetMillis);
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("fake-5", s.plan.planId);
        assertTrue(s.planCurrent);
        assertFalse("no promise left without a run behind it", s.plan.followUpPending());
    }

    /** As above for a full job that fails while the plan is out of date: 重试 gets its follow-up. */
    @Test
    public void aFullFailureWithAnOutOfDatePlanKeepsTheRetrysFollowUp() {
        staleWithAnError();
        planner.shape = (plan, n) -> {
            if (n == 3) throw new IllegalStateException("engine bug");
            exhaustedOn(plan, n, 4);
        };
        long ticket = service.requestFullReplan();
        settle();
        PlannerApi.Snapshot failed = service.snapshot();
        assertEquals(ticket, failed.fullReplanDoneTicket);
        assertEquals(FullReplanResult.FAILED, failed.fullReplanResult);
        assertNotNull("the ordinary failure card", failed.planErrorZh);

        service.requestReplan();
        settle();
        assertEquals("the failed full job, 重试, its follow-up", 5, planner.calls.get());
        assertTrue(planner.options.get(4).followUp);
        assertEquals(100, planner.options.get(4).timeBudgetMillis);
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("fake-5", s.plan.planId);
        assertFalse(s.plan.followUpPending());
        assertNull(s.planErrorZh);
    }

    /** Call {@code n} of the fake planner runs out of time, as the engine marks such a plan. */
    private static void exhaustedOn(Plan plan, int n, int exhausted) {
        if (n != exhausted) return;
        plan.budgetExhausted = true;
        plan.summary.notesZh.add(0, Plan.BUDGET_NOTE_PENDING);
    }

    @Test
    public void anOutOfDatePlanIsReplacedAsAnOrdinaryJobWouldReplaceIt() {
        staleWithAnError();
        long ticket = service.requestFullReplan();
        settle();

        assertEquals(3, planner.calls.get());
        assertEquals("", planner.previousIds.get(2));
        assertEquals(150, planner.options.get(2).timeBudgetMillis);
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("fake-3", s.plan.planId);
        assertEquals(2, s.plan.stateRevision);
        assertTrue(s.planCurrent);
        assertNull("the error is gone with the new plan", s.planErrorZh);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertEquals("it starts from the newer record", FullReplanResult.CHANGED, s.fullReplanResult);
    }

    @Test
    public void aRecordDuringTheRunCarriesTheRequestOverToTheNewRecords() throws Exception {
        planned();
        List<String> ends = Collections.synchronizedList(new ArrayList<>());
        service.addListener(snap -> ends.add(snap.fullReplanDoneTicket + ":" + snap.fullReplanResult));
        CountDownLatch gate = hold(2);
        long ticket = service.requestFullReplan();
        awaitHeld();

        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue("the record stops the computation for the old one", planner.options.get(1).cancelled.get());
        assertTrue("the request is still pending", service.snapshot().fullReplanning);
        gate.countDown();
        settle();

        assertEquals(3, planner.calls.get());
        Engine.Options last = planner.options.get(2);
        assertEquals("the last job is a full one again", 150, last.timeBudgetMillis);
        assertTrue(last.followUp);
        assertEquals("", planner.previousIds.get(2));
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("fake-3", s.plan.planId);
        assertEquals(2, s.plan.stateRevision);
        assertTrue(s.planCurrent);
        assertFalse(s.fullReplanning);
        assertEquals("no second ticket", ticket, s.fullReplanTicket);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertEquals(FullReplanResult.CHANGED, s.fullReplanResult);
        for (String end : ends) {
            assertTrue("the ticket ends once, with the plan for the new record: " + ends,
                end.equals("0:null") || end.equals(ticket + ":CHANGED"));
        }
    }

    @Test
    public void aFailureKeepsTheCurrentPlanWithoutAnError() {
        planned();
        planner.failWith = new IllegalStateException("engine bug");
        long ticket = service.requestFullReplan();
        settle();
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("fake-1", s.plan.planId);
        assertTrue(s.planCurrent);
        assertNull(s.planErrorZh);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertEquals(FullReplanResult.FAILED, s.fullReplanResult);

        planner.failWith = null;
        validator.problems = List.of("slot 83 double-booked");
        long rejected = service.requestFullReplan();
        settle();
        s = service.snapshot();
        assertEquals("a plan the validator rejects fails the same way", FullReplanResult.FAILED, s.fullReplanResult);
        assertEquals(rejected, s.fullReplanDoneTicket);
        assertEquals("fake-1", s.plan.planId);
        assertNull(s.planErrorZh);

        validator.problems = Collections.emptyList();
        service.requestFullReplan();
        settle();
        assertEquals("nothing blocks the next request", "fake-4", service.snapshot().plan.planId);
        assertEquals(FullReplanResult.UNCHANGED, service.snapshot().fullReplanResult);
    }

    /**
     * Worse on a goal: the plan stays (KEPT). The request superseded the follow-up of the
     * exhausted plan, so that plan must stop promising one.
     */
    @Test
    public void aPlanWorseOnTheGoalsIsNotInstalledAndTheFollowUpPromiseEnds() throws Exception {
        Profile goals = new Profile();
        goals.tier[TestRules.EMPEROR] = Profile.Tier.MUST;
        planner.shape = (plan, n) -> {
            plan.budgetExhausted = n == 1;
            plan.summary.endRanks[TestRules.EMPEROR] = n == 1 ? 10 : 6;
        };
        CountDownLatch gate = hold(2);
        PlannerApi.Receipt r = service.saveCheckpoint(id(), 0, TestRules.state(rules, MAY12_DAY), goals);
        assertTrue(r.errorZh, r.ok);
        awaitHeld();
        assertTrue("call 2 is the follow-up of the exhausted plan", planner.options.get(1).followUp);
        assertEquals(100, planner.options.get(1).timeBudgetMillis);

        long ticket = service.requestFullReplan();
        assertTrue("the full request supersedes the follow-up", planner.options.get(1).cancelled.get());
        gate.countDown();
        settle();

        assertEquals(3, planner.calls.get());
        assertEquals(150, planner.options.get(2).timeBudgetMillis);
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("fake-1", s.plan.planId);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertEquals(FullReplanResult.KEPT, s.fullReplanResult);
        assertTrue(s.plan.followUp);
        assertFalse(s.plan.followUpPending());
        assertEquals(Plan.BUDGET_NOTE_DONE, s.plan.budgetNoteZh());

        restart();
        settle();
        assertEquals("no follow-up after a restart either", 3, planner.calls.get());
        assertEquals("fake-1", service.snapshot().plan.planId);
        assertTrue(service.snapshot().plan.followUp);
    }

    @Test
    public void anExhaustedFullPlanGetsNoFollowUpAndSaysSo() {
        planner.shape = (plan, n) -> {
            plan.budgetExhausted = true;
            // As if the engine had not worded the note for a follow-up run: the service marks it.
            plan.summary.notesZh.add(0, Plan.BUDGET_NOTE_PENDING);
        };
        onboard(MAY12_DAY);
        settle();
        assertEquals("the plan and its follow-up (not better)", 2, planner.calls.get());
        assertEquals("fake-1", service.snapshot().plan.planId);

        long ticket = service.requestFullReplan();
        settle();
        assertEquals("no follow-up after the full run", 3, planner.calls.get());
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("tied on the goals, both ran out of time: the fresh plan is taken", "fake-3", s.plan.planId);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertEquals(FullReplanResult.UNCHANGED, s.fullReplanResult);
        Plan installed = s.plan;
        assertTrue(installed.budgetExhausted);
        assertTrue(installed.followUp);
        assertFalse(installed.followUpPending());
        assertEquals(Plan.BUDGET_NOTE_DONE, installed.budgetNoteZh());
        assertEquals(Plan.BUDGET_NOTE_DONE, installed.summary.notesZh.get(0));

        restart();
        settle();
        assertEquals(3, planner.calls.get());
        assertEquals("fake-3", service.snapshot().plan.planId);
        assertTrue(service.snapshot().plan.followUp);
    }

    /** The request re-reads what is stored, not the service's cached view (here: another connection's edit). */
    @Test
    public void theRequestReReadsTheRecordsAndTheSettings() throws Exception {
        planned();
        long profileRevision = service.snapshot().profileRevision;
        Profile edited = service.snapshot().profile;
        edited.tier[TestRules.EMPEROR] = Profile.Tier.MUST;
        try (Ledger other = new Ledger(context, PlanDb.FILE_NAME)) {
            other.putProfileJson(edited.toJson().toString());
        }
        assertEquals("the cached view does not know yet", Profile.Tier.NONE,
                service.snapshot().profile.tier[TestRules.EMPEROR]);

        service.requestFullReplan();
        settle();

        assertEquals(Profile.Tier.MUST, planner.profiles.get(1).tier[TestRules.EMPEROR]);
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals(Profile.Tier.MUST, s.profile.tier[TestRules.EMPEROR]);
        assertEquals(profileRevision, s.profileRevision);
        assertTrue(s.planCurrent);
    }

    @Test
    public void beforeOnboardingThereIsNothingToReplan() {
        assertEquals(0, service.requestFullReplan());
        settle();
        PlannerApi.Snapshot s = service.snapshot();
        assertFalse(s.fullReplanning);
        assertEquals(0, s.fullReplanTicket);
        assertEquals(0, planner.calls.get());
        service.cancelFullReplan();
        assertFalse(service.snapshot().fullReplanning);
    }

    @Test
    public void afterAFailedLoadTheRequestOpensTheDataAgain() {
        AtomicInteger loads = new AtomicInteger();
        PlannerService flaky = open("flaky.db", () -> {
            if (loads.incrementAndGet() == 1) {
                throw new IllegalStateException("asset missing");
            }
            return rules;
        });
        assertNotNull(flaky.snapshot().planErrorZh);

        assertEquals("no ticket: it works as 重试", 0, flaky.requestFullReplan());
        assertTrue(flaky.awaitIdle(10_000));
        assertEquals(2, loads.get());
        assertNull(flaky.snapshot().planErrorZh);
        assertNotNull(flaky.rules());
        assertFalse(flaky.snapshot().fullReplanning);
    }

    @Test
    public void aBackupImportEndsThePendingRequestWithoutAResult() throws Exception {
        planned();
        String backup = service.exportBackup();
        CountDownLatch gate = hold(2);
        long ticket = service.requestFullReplan();
        awaitHeld();

        PlannerApi.Receipt r = service.importBackup(id(), backup);
        assertTrue(r.errorZh, r.ok);
        PlannerApi.Snapshot s = service.snapshot();
        assertFalse(s.fullReplanning);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertNull("no message after an import", s.fullReplanResult);
        gate.countDown();
        settle();

        assertEquals(3, planner.calls.get());
        assertEquals("an ordinary plan for the restored records", 50, planner.options.get(2).timeBudgetMillis);
        assertFalse(planner.options.get(2).followUp);
        s = service.snapshot();
        assertEquals("fake-3", s.plan.planId);
        assertTrue(s.planCurrent);
        assertEquals(ticket, s.fullReplanDoneTicket);
        assertNull(s.fullReplanResult);
    }

    @Test
    public void goalsDecideAndATieIsTakenUnlessOnlyTheFreshPlanRanOutOfTime() {
        Profile p = new Profile();
        p.tier[3] = Profile.Tier.MUST;
        p.order[3] = 0;
        p.tier[5] = Profile.Tier.WANT;
        p.order[5] = 1;
        Plan current = plan(false);
        current.summary.endRanks[3] = 9;
        current.summary.endRanks[5] = 10;

        Plan firstGoalUp = plan(true);
        firstGoalUp.summary.endRanks[3] = 10;
        firstGoalUp.summary.endRanks[5] = 4;
        assertTrue("the first goal decides", PlannerService.compareGoals(p, firstGoalUp, current) > 0);
        assertTrue(PlannerService.compareGoals(p, current, firstGoalUp) < 0);
        assertTrue("better on the goals is taken even when it ran out of time",
                PlannerService.acceptsFull(p, firstGoalUp, current));
        assertFalse(PlannerService.acceptsFull(p, current, firstGoalUp));

        Plan otherLinks = plan(false);
        otherLinks.summary.endRanks = current.summary.endRanks.clone();
        otherLinks.summary.endRanks[12] = 10;
        assertEquals("other links do not count", 0, PlannerService.compareGoals(p, otherLinks, current));
        p.target[5] = 7;
        Plan pastTarget = plan(false);
        pastTarget.summary.endRanks = current.summary.endRanks.clone();
        pastTarget.summary.endRanks[5] = 7;
        assertEquals("past its target a goal ties", 0, PlannerService.compareGoals(p, pastTarget, current));

        Plan tie = plan(false);
        tie.summary.endRanks = current.summary.endRanks.clone();
        Plan tieExhausted = plan(true);
        tieExhausted.summary.endRanks = current.summary.endRanks.clone();
        Plan currentExhausted = plan(true);
        currentExhausted.summary.endRanks = current.summary.endRanks.clone();
        assertTrue("complete vs complete", PlannerService.acceptsFull(p, tie, current));
        assertTrue("complete vs ran out", PlannerService.acceptsFull(p, tie, currentExhausted));
        assertTrue("both ran out", PlannerService.acceptsFull(p, tieExhausted, currentExhausted));
        assertFalse("the tie case: only the fresh one ran out of time", PlannerService.acceptsFull(p, tieExhausted, current));

        assertEquals(7_500, PlannerService.fullReplanBudget(2_500));
        assertEquals(150, PlannerService.fullReplanBudget(50));
        assertEquals(7_998, PlannerService.fullReplanBudget(2_666));
        assertEquals(8_000, PlannerService.fullReplanBudget(2_667));
        assertEquals(8_000, PlannerService.fullReplanBudget(4_000));
        assertEquals(8_000, PlannerService.fullReplanBudget(Long.MAX_VALUE));
        assertEquals(0, PlannerService.fullReplanBudget(0));
        assertEquals(0, PlannerService.fullReplanBudget(Long.MIN_VALUE));
    }

    @Test
    public void sameStepsComparesActionAndSideLinesPerSlot() {
        Plan a = steps(10, "meet:emperor", "act:study_room", "meet:emperor");
        Plan b = steps(10, "meet:emperor", "act:study_room", "meet:emperor");
        b.planId = "other";
        assertTrue("the id does not matter", PlannerService.sameSteps(a, b));
        Plan later = steps(11, "act:study_room", "meet:emperor");
        assertTrue("a later start with the same steps", PlannerService.sameSteps(a, later));
        Plan side = steps(10, "meet:emperor", "act:study_room", "meet:emperor");
        side.steps.get(1).side.add("act:study_room");
        assertFalse(PlannerService.sameSteps(a, side));
        Plan action = steps(10, "meet:emperor", "free", "meet:emperor");
        assertFalse(PlannerService.sameSteps(a, action));
        Plan longer = steps(10, "meet:emperor", "act:study_room", "meet:emperor", "free");
        assertFalse(PlannerService.sameSteps(a, longer));
        Plan earlier = steps(9, "free", "meet:emperor", "act:study_room", "meet:emperor");
        assertFalse("a slot the plan before did not have", PlannerService.sameSteps(a, earlier));
        assertFalse(PlannerService.sameSteps(null, a));
        assertTrue(PlannerService.sameSteps(null, null));
    }

    private static Plan plan(boolean exhausted) {
        Plan plan = new Plan();
        plan.budgetExhausted = exhausted;
        return plan;
    }

    private static Plan steps(int from, String... actions) {
        Plan plan = new Plan();
        plan.fromSlot = from;
        plan.toSlot = from + actions.length - 1;
        for (int i = 0; i < actions.length; i++) {
            Plan.Step step = new Plan.Step();
            step.slot = from + i;
            step.action = actions[i];
            plan.steps.add(step);
        }
        return plan;
    }
}
