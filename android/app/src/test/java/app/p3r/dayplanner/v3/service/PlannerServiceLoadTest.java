package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.store.TestRules;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Start-up: loading off the main thread, non-blocking peeks, load failures in Chinese. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceLoadTest extends ServiceTestBase {

    @Test
    public void peekSnapshotNeverWaitsForTheLoad() {
        settle();
        CountDownLatch release = new CountDownLatch(1);
        PlannerService slow = open("slow.db", () -> {
            Fakes.awaitQuietly(release);
            return rules;
        });
        AtomicInteger notified = new AtomicInteger();
        slow.addListener(snapshot -> notified.incrementAndGet());
        int refreshesBefore = widgetRefreshes.get();

        assertNull("still loading", slow.peekSnapshot());
        release.countDown();
        assertTrue(slow.awaitIdle(10_000));
        shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(slow.peekSnapshot());
        assertEquals("listeners hear about the finished load", 1, notified.get());
        assertTrue("widgets re-render after the load", widgetRefreshes.get() > refreshesBefore);
    }

    @Test
    public void aBrokenRulePackIsReportedInChinese() {
        PlannerService broken = open("broken.db", () -> {
            throw new IllegalStateException("asset missing");
        });
        PlannerApi.Snapshot s = broken.snapshot();
        assertNull(s.observed);
        assertNotNull(s.profile);
        assertFalse(s.planning);
        assertEquals("游戏资料读取失败，请重新打开应用", s.planErrorZh);
        assertNull(broken.rules());

        PlannerApi.Receipt r = broken.recordOutcome(id(), 0, TestRules.slot(TestRules.MAY_12, false),
                "meet:emperor", Outcome.of(Outcome.Code.AS_PREDICTED));
        assertFalse(r.ok);
        assertEquals("io", r.errorCode);
        assertEquals("游戏资料读取失败，请重新打开应用", r.errorZh);
        assertNull(broken.exportBackup());
        assertTrue(broken.history(0).isEmpty());
        assertTrue(broken.alternatives(TestRules.slot(TestRules.MAY_12, false)).isEmpty());
    }

    @Test
    public void getReturnsOneProductionInstancePerApplication() {
        PlannerApi first = PlannerService.get(context);
        assertSame(first, PlannerService.get(context.getApplicationContext()));
        // Loads with or without a bundled rule pack (a missing pack is reported, never thrown).
        assertNotNull(first.snapshot().profile);
        ((PlannerService) first).close();
        assertNotSame(first, PlannerService.get(context));
        ((PlannerService) PlannerService.get(context)).close();
    }

    @Test
    public void missingCommandIdIsRefused() {
        PlannerApi.Receipt r = service.saveCheckpoint(" ", 0, TestRules.state(rules, TestRules.slot(TestRules.MAY_12, false)), null);
        assertFalse(r.ok);
        assertEquals("invalid", r.errorCode);
        assertEquals("保存失败了，请再试一次", r.errorZh);
        assertNull(service.snapshot().observed);
    }
}
