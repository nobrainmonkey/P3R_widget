package app.p3r.dayplanner.v3.widget;

import java.util.HashMap;
import java.util.Map;

/**
 * Bookkeeping for the day/night palette crossfade of one widget provider: a per-widget generation
 * token (so a delayed settle update only runs if nothing newer was drawn) and a 300ms throttle (so a
 * burst of taps never queues several fades). Thread-safe.
 */
public final class FadeGuard {
    /** Minimum time between two animated palette changes of one widget. */
    public static final long THROTTLE_MS = 300L;
    /** Delay before the animated host is replaced by the static, settled tree. */
    public static final long SETTLE_MS = 1000L;

    private final Map<Integer, Long> generations = new HashMap<>();
    private final Map<Integer, Long> lastActions = new HashMap<>();
    private long generation;

    /** Starts a new drawing generation for {@code widgetId} and returns its token. */
    public synchronized long invalidate(int widgetId) {
        long next = ++generation;
        generations.put(widgetId, next);
        return next;
    }

    /** Token of the latest generation, or -1 if none. */
    public synchronized long current(int widgetId) {
        Long token = generations.get(widgetId);
        return token == null ? -1L : token;
    }

    public synchronized boolean isCurrent(int widgetId, long token) {
        Long latest = generations.get(widgetId);
        return latest != null && latest == token;
    }

    /**
     * Records a user action at {@code nowMillis} and returns whether its palette change (if
     * {@code changed}) may animate.
     */
    public synchronized boolean allowFade(int widgetId, boolean changed, long nowMillis) {
        Long previous = lastActions.put(widgetId, nowMillis);
        return changed && (previous == null || nowMillis - previous >= THROTTLE_MS);
    }

    /** Drops all state of a deleted widget (and cancels its pending settle). */
    public synchronized void forget(int widgetId) {
        invalidate(widgetId);
        lastActions.remove(widgetId);
    }
}
