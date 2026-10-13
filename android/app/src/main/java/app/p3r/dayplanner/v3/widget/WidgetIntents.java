package app.p3r.dayplanner.v3.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import app.p3r.dayplanner.v3.Intents;

/**
 * PendingIntents of the plan widget. Every intent carries a data URI that encodes everything it
 * acts on (widget id, plan id, state revision, slot, action), so no two different buttons ever share
 * a PendingIntent and an old tap can always be recognised as stale. All are FLAG_IMMUTABLE.
 */
final class WidgetIntents {
    static final String ACTION_PREV = "app.p3r.dayplanner.v3.widget.PREV";
    static final String ACTION_NEXT = "app.p3r.dayplanner.v3.widget.NEXT";
    static final String ACTION_CURRENT = "app.p3r.dayplanner.v3.widget.CURRENT";
    static final String ACTION_DONE = "app.p3r.dayplanner.v3.widget.DONE";
    static final String ACTION_UNDO = "app.p3r.dayplanner.v3.widget.UNDO";
    static final String ACTION_REFRESH = "app.p3r.dayplanner.v3.widget.REFRESH";
    /** Event sequence number to void (long). */
    static final String EXTRA_EVENT_SEQ = "p3r.widget.eventSeq";

    static final String HOME_ACTIVITY = "app.p3r.dayplanner.v3.ui.HomeActivity";
    static final String RESULT_ACTIVITY = "app.p3r.dayplanner.v3.ui.ResultActivity";
    static final String STATE_ACTIVITY = "app.p3r.dayplanner.v3.ui.StateActivity";
    static final String STATE_MODE_ONBOARDING = "onboarding";
    static final String TAB_TODAY = "today";
    static final String TAB_SCHEDULE = "schedule";

    private static final int FLAGS = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
    private static final String SCHEME = "p3r-widget";

    private WidgetIntents() {}

    static boolean isWidgetAction(String action) {
        return ACTION_PREV.equals(action) || ACTION_NEXT.equals(action) || ACTION_CURRENT.equals(action)
                || ACTION_DONE.equals(action) || ACTION_UNDO.equals(action) || ACTION_REFRESH.equals(action);
    }

    private static Uri.Builder uri(int widgetId, String what) {
        return new Uri.Builder().scheme(SCHEME).authority("plan").appendPath(String.valueOf(widgetId)).appendPath(what);
    }

    private static Intent broadcastIntent(Context c, int widgetId, String action, Uri data) {
        return new Intent(c, PlanWidget.class)
                .setAction(action)
                .setData(data)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
    }

    /** ‹ › 当前. */
    static PendingIntent navigate(Context c, int widgetId, String action) {
        Intent intent = broadcastIntent(c, widgetId, action, uri(widgetId, "nav").appendPath(action).build());
        return PendingIntent.getBroadcast(c, widgetId, intent, FLAGS);
    }

    /** 完成 on a training/job item: record AS_PREDICTED in the receiver. */
    static PendingIntent commit(Context c, int widgetId, String planId, long stateRevision, int slot, String action) {
        Uri data = uri(widgetId, "done").appendPath(planId).appendPath(String.valueOf(stateRevision))
                .appendPath(String.valueOf(slot)).appendPath(action).build();
        Intent intent = broadcastIntent(c, widgetId, ACTION_DONE, data)
                .putExtra(Intents.EXTRA_PLAN_ID, planId)
                .putExtra(Intents.EXTRA_STATE_REV, stateRevision)
                .putExtra(Intents.EXTRA_SLOT, slot)
                .putExtra(Intents.EXTRA_ACTION, action);
        return PendingIntent.getBroadcast(c, widgetId, intent, FLAGS);
    }

    /** 撤销 on the receipt card. */
    static PendingIntent undo(Context c, int widgetId, long eventSeq, long stateRevision) {
        Uri data = uri(widgetId, "undo").appendPath(String.valueOf(eventSeq)).appendPath(String.valueOf(stateRevision)).build();
        Intent intent = broadcastIntent(c, widgetId, ACTION_UNDO, data)
                .putExtra(EXTRA_EVENT_SEQ, eventSeq)
                .putExtra(Intents.EXTRA_STATE_REV, stateRevision);
        return PendingIntent.getBroadcast(c, widgetId, intent, FLAGS);
    }

    /** Wake-up that re-renders every plan widget (receipt expiry). */
    static PendingIntent refresh(Context c) {
        Intent intent = new Intent(c, PlanWidget.class).setAction(ACTION_REFRESH)
                .setData(new Uri.Builder().scheme(SCHEME).authority("plan").appendPath("refresh").build());
        return PendingIntent.getBroadcast(c, 0, intent, FLAGS);
    }

    /** Quick result sheet; {@code mode} is {@link Intents#MODE_RESULT} or {@link Intents#MODE_CATCHUP}. */
    static PendingIntent result(Context c, int widgetId, String mode, String planId, long stateRevision, int slot, String action) {
        Uri data = uri(widgetId, "result").appendPath(mode).appendPath(planId).appendPath(String.valueOf(stateRevision))
                .appendPath(String.valueOf(slot)).appendPath(action).build();
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(c, RESULT_ACTIVITY))
                .setData(data)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intents.EXTRA_MODE, mode)
                .putExtra(Intents.EXTRA_PLAN_ID, planId)
                .putExtra(Intents.EXTRA_STATE_REV, stateRevision)
                .putExtra(Intents.EXTRA_SLOT, slot)
                .putExtra(Intents.EXTRA_ACTION, action);
        return PendingIntent.getActivity(c, widgetId, intent, FLAGS);
    }

    /** Opens the app on a tab, optionally with the detail sheet of {@code slot} (-1 for none). */
    static PendingIntent home(Context c, int widgetId, String tab, int slot) {
        Uri.Builder data = uri(widgetId, "home").appendPath(tab);
        if (slot >= 0) data.appendPath(String.valueOf(slot));
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(c, HOME_ACTIVITY))
                .setData(data.build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Intents.EXTRA_TAB, tab);
        if (slot >= 0) intent.putExtra(Intents.EXTRA_OPEN_SLOT, slot);
        return PendingIntent.getActivity(c, widgetId, intent, FLAGS);
    }

    /** First-run setup. */
    static PendingIntent onboarding(Context c, int widgetId) {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(c, STATE_ACTIVITY))
                .setData(uri(widgetId, "onboarding").build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intents.EXTRA_STATE_MODE, STATE_MODE_ONBOARDING);
        return PendingIntent.getActivity(c, widgetId, intent, FLAGS);
    }

    /**
     * Idempotency key of a direct record: the same plan, slot and action always map to the same id,
     * so a double tap (or a retried broadcast) can never record twice.
     */
    static String commitCommandId(String planId, int slot, String action) {
        return "widget:" + sha256(planId + "|" + slot + "|" + action + "|AS_PREDICTED");
    }

    static String voidCommandId(long eventSeq, long stateRevision) {
        return "widget-void:" + sha256(eventSeq + "|" + stateRevision + "|VOID");
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xf, 16));
                hex.append(Character.forDigit(digest[i] & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
