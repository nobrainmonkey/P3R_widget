package app.p3r.dayplanner.v3.widget;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain-JVM checks of the crossfade bookkeeping: the tap throttle and the settle generations. */
public class FadeGuardTest {

    /** A burst of taps never queues several fades; only a palette change after a pause animates. */
    @Test
    public void repeatedRapidActionsDoNotQueueFades() {
        FadeGuard guard = new FadeGuard();
        assertTrue(guard.allowFade(961, true, 1000));
        assertFalse(guard.allowFade(961, true, 1100));
        assertFalse(guard.allowFade(961, true, 1200));
        // A tap without a palette change still counts as the latest action.
        assertFalse(guard.allowFade(961, false, 1800));
        assertTrue(guard.allowFade(961, true, 2200));
    }

    @Test
    public void theThrottleIsPerWidgetAndEndsAfterThreeHundredMillis() {
        FadeGuard guard = new FadeGuard();
        assertTrue(guard.allowFade(1, true, 5000));
        assertTrue("another widget is not throttled", guard.allowFade(2, true, 5010));
        assertFalse(guard.allowFade(1, true, 5000 + FadeGuard.THROTTLE_MS - 1));
        assertTrue(guard.allowFade(1, true, 5000 + 2 * FadeGuard.THROTTLE_MS - 1));
        assertFalse("no palette change, no fade", guard.allowFade(3, false, 9000));
    }

    @Test
    public void onlyTheLatestDrawingGenerationMaySettle() {
        FadeGuard guard = new FadeGuard();
        assertEquals(-1L, guard.current(7));
        long first = guard.invalidate(7);
        assertEquals(first, guard.current(7));
        assertTrue(guard.isCurrent(7, first));
        long second = guard.invalidate(7);
        assertTrue(second > first);
        assertFalse("a newer drawing cancels the pending settle", guard.isCurrent(7, first));
        assertTrue(guard.isCurrent(7, second));
        assertFalse("tokens are per widget", guard.isCurrent(8, second));
    }

    @Test
    public void forgettingAWidgetCancelsItsSettleAndThrottle() {
        FadeGuard guard = new FadeGuard();
        assertTrue(guard.allowFade(5, true, 1000));
        long token = guard.invalidate(5);
        guard.forget(5);
        assertFalse(guard.isCurrent(5, token));
        assertTrue("a recreated widget id fades at once", guard.allowFade(5, true, 1001));
    }
}
