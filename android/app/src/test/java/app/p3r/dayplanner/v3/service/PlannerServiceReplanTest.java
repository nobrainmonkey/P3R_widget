package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
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

/** Background replanning: revisions, staleness, coalescing, failures, listeners, tower income. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceReplanTest extends ServiceTestBase {
    private static final int MAY12_DAY = TestRules.slot(TestRules.MAY_12, false);
    private static final int MAY12_EVE = TestRules.slot(TestRules.MAY_12, true);

    @Test
    public void aWriteInstallsAPlanForTheCurrentRevisions() {
        onboard(MAY12_DAY);
        settle();

        PlannerApi.Snapshot s = service.snapshot();
        assertNotNull(s.plan);
        assertTrue(s.planCurrent);
        assertFalse(s.planning);
        assertNull(s.planErrorZh);
        assertEquals(1, s.plan.stateRevision);
        assertEquals(0, s.plan.profileRevision);
        assertEquals(rules.version, s.plan.rulesVersion);
        assertEquals(MAY12_DAY, s.plan.fromSlot);
        assertEquals(1, planner.calls.get());
        Engine.Options options = planner.options.get(0);
        assertEquals(50, options.timeBudgetMillis);
        assertEquals(0, options.towerIncomePerVisit);
        assertNotNull(options.cancelled);
        assertTrue("write + install refresh the widget", widgetRefreshes.get() >= 2);

        service.requestReplan();
        settle();
        assertEquals("a current plan is not recomputed", 1, planner.calls.get());
    }

    @Test
    public void aResultForAnOlderRevisionIsDiscardedAndRecomputed() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        planner.blockFirstCall = gate;
        onboard(MAY12_DAY);
        assertTrue(planner.firstCallEntered.await(10, TimeUnit.SECONDS));

        PlannerApi.Snapshot during = service.snapshot();
        assertTrue(during.planning);
        assertFalse(during.planCurrent);

        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue("the write cancels the computation in flight", planner.options.get(0).cancelled.get());
        gate.countDown();
        settle();

        PlannerApi.Snapshot after = service.snapshot();
        assertEquals(2, planner.calls.get());
        assertEquals(2, after.plan.stateRevision);
        assertEquals("fake-2", after.plan.planId);
        assertEquals(MAY12_EVE, after.plan.fromSlot);
        assertTrue(after.planCurrent);
        assertFalse(after.planning);
        assertEquals("the superseded result is not even validated",
                Collections.singletonList(2L), validator.validatedRevisions);
    }

    @Test
    public void aBurstOfWritesIsCoalescedIntoOneRecomputation() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        planner.blockFirstCall = gate;
        onboard(MAY12_DAY);
        assertTrue(planner.firstCallEntered.await(10, TimeUnit.SECONDS));
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(record(MAY12_EVE, "act:study_room", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(record(MAY12_EVE + 1, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        gate.countDown();
        settle();

        assertEquals("stale first result + one plan for the latest state", 2, planner.calls.get());
        assertEquals(4, service.snapshot().plan.stateRevision);
        assertTrue(service.snapshot().planCurrent);
    }

    @Test
    public void aRejectedPlanKeepsThePreviousOne() {
        onboard(MAY12_DAY);
        settle();
        Plan previous = service.snapshot().plan;

        validator.problems = List.of("slot 83 double-booked");
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        settle();
        PlannerApi.Snapshot s = service.snapshot();
        assertSame(previous, s.plan);
        assertFalse(s.planCurrent);
        assertFalse(s.planning);
        assertEquals("排程时出了问题，已保留上一版安排", s.planErrorZh);
        assertEquals("the previous plan was the stability hint", previous.planId, planner.previousIds.get(1));

        int calls = planner.calls.get();
        validator.problems = Collections.emptyList();
        service.requestReplan();
        settle();
        s = service.snapshot();
        assertEquals(calls + 1, planner.calls.get());
        assertTrue(s.planCurrent);
        assertNull(s.planErrorZh);
    }

    @Test
    public void aPlannerCrashWithoutAPreviousPlanSaysSo() {
        planner.failWith = new IllegalStateException("engine bug");
        onboard(MAY12_DAY);
        settle();
        PlannerApi.Snapshot s = service.snapshot();
        assertNull(s.plan);
        assertFalse(s.planning);
        assertEquals("排程时出了问题，暂时没能生成安排", s.planErrorZh);
    }

    @Test
    public void towerIncomeIsTheMedianOfRecordedReturns() {
        onboard(MAY12_EVE);
        assertTrue(tower(MAY12_EVE, 25, true, 14_000).ok);
        settle();
        assertEquals("one record is not enough", 0, lastOptions().towerIncomePerVisit);

        int day = TestRules.MAY_12 + 1;
        assertTrue(record(TestRules.slot(day, false), "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(tower(TestRules.slot(day, true), 26, false, 2_000).ok);
        assertTrue(record(TestRules.slot(day + 1, false), "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(tower(TestRules.slot(day + 1, true), 28, false, 9_000).ok);
        settle();
        assertEquals("median of 4,000 / 2,000 / 9,000", 4_000, lastOptions().towerIncomePerVisit);
    }

    @Test
    public void listenersAreCalledOnTheMainThread() {
        List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        List<PlannerApi.Snapshot> seen = Collections.synchronizedList(new ArrayList<>());
        PlannerApi.Listener listener = snapshot -> {
            threads.add(Thread.currentThread());
            seen.add(snapshot);
        };
        service.addListener(listener);
        onboard(MAY12_DAY);
        settle();

        assertFalse(seen.isEmpty());
        for (Thread t : threads) {
            assertSame(Looper.getMainLooper().getThread(), t);
        }
        PlannerApi.Snapshot last = seen.get(seen.size() - 1);
        assertNotNull(last.observed);
        assertTrue(last.planCurrent);

        service.removeListener(listener);
        int count = seen.size();
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        settle();
        assertEquals(count, seen.size());
    }

    @Test
    public void theStoredPlanIsReusedAfterARestart() {
        onboard(MAY12_DAY);
        settle();
        String planId = service.snapshot().plan.planId;
        restart();
        settle();
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals(planId, s.plan.planId);
        assertTrue(s.planCurrent);
        assertEquals("no recomputation needed", 1, planner.calls.get());
    }

    @Test
    public void aProfileChangeMakesThePlanStale() {
        onboard(MAY12_DAY);
        settle();
        app.p3r.dayplanner.v3.model.Profile profile = service.snapshot().profile;
        profile.tier[TestRules.EMPEROR] = app.p3r.dayplanner.v3.model.Profile.Tier.MUST;
        PlannerApi.Receipt r = service.saveProfile(id(), 0, profile);
        assertTrue(r.ok);
        assertEquals(1, r.profileRevision);
        assertEquals("state revision unchanged", 1, r.stateRevision);
        settle();
        PlannerApi.Snapshot s = service.snapshot();
        assertEquals(1, s.plan.profileRevision);
        assertTrue(s.planCurrent);
        assertEquals(app.p3r.dayplanner.v3.model.Profile.Tier.MUST, s.profile.tier[TestRules.EMPEROR]);
    }

    private Engine.Options lastOptions() {
        return planner.options.get(planner.options.size() - 1);
    }
}
