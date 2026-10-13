package app.p3r.dayplanner.v3.ui;

import android.os.SystemClock;

/**
 * Hands the last record made on the quick result sheet back to the app screen, which then offers
 * "已记录：… · 撤销". In-process only: the sheet and the app share the process, and a record made
 * from the home-screen widget never produces an in-app undo bar.
 */
public final class UndoCenter {
    /** How long a record stays eligible for the in-app undo bar. */
    static final long MAX_AGE_MILLIS = 15_000;

    /** One saved record. */
    public static final class Recorded {
        public final long eventSeq;
        public final String summaryZh;
        public final long elapsedRealtime;

        Recorded(long eventSeq, String summaryZh, long elapsedRealtime) {
            this.eventSeq = eventSeq;
            this.summaryZh = summaryZh;
            this.elapsedRealtime = elapsedRealtime;
        }
    }

    private static Recorded pending;

    private UndoCenter() {}

    /** Called by the sheet after a successful in-app save. */
    public static synchronized void post(long eventSeq, String summaryZh) {
        pending = new Recorded(eventSeq, summaryZh, SystemClock.elapsedRealtime());
    }

    /** Returns and clears the pending record if it is recent enough, else null. */
    public static synchronized Recorded take() {
        Recorded r = pending;
        pending = null;
        if (r == null || SystemClock.elapsedRealtime() - r.elapsedRealtime > MAX_AGE_MILLIS) {
            return null;
        }
        return r;
    }

    /** Clears any pending record (tests). */
    public static synchronized void clear() {
        pending = null;
    }
}
