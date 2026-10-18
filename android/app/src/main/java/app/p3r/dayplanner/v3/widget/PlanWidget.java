package app.p3r.dayplanner.v3.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.SizeF;
import android.widget.RemoteViews;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * Home-screen plan widget (docs/DESIGN_v3.md §7.2): shows the plan item by item, ‹ › browse, 当前
 * returns to the pending item, 完成 records training/jobs directly (with a one-tap undo) and opens
 * the quick result sheet for everything else. Status cards (planning, error, finished) carry one
 * button instead that opens the app; a story-locked item, with nothing to record, offers 下一项.
 *
 * <p>Threading: every broadcast is handed to {@link WidgetWorker} under {@code goAsync()}, so no
 * SQLite read, outcome write or RemoteViews build ever runs on the main thread.
 *
 * <p>Safety: a direct record only happens when the tapped intent's plan id, state revision, slot and
 * action all still match the latest snapshot, and it uses a command id derived from all four, so a
 * stale or doubled tap can never record twice; it only refreshes the widget. An undo tap within
 * {@link PlanView#UNDO_ARM_MS} of the record is the second half of a double tap and is ignored.
 */
public class PlanWidget extends AppWidgetProvider {
    private static final String TAG = "P3RPlanWidget";
    static final FadeGuard FADES = new FadeGuard();

    /** Re-render every placed instance from the latest snapshot. Safe to call from any thread. */
    public static void refreshAll(Context context) {
        Context app = context.getApplicationContext();
        WidgetWorker.run(() -> renderAll(app, null));
    }

    // ------------------------------------------------------------------ framework callbacks

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        int[] targets = ids.clone();
        WidgetWorker.run(() -> {
            try {
                renderAll(app, targets);
            } finally {
                finish(pending);
            }
        });
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        WidgetWorker.run(() -> {
            try {
                renderAll(app, new int[]{id});
            } finally {
                finish(pending);
            }
        });
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        Context app = context.getApplicationContext();
        int[] targets = ids.clone();
        WidgetWorker.run(() -> {
            for (int id : targets) {
                FADES.forget(id);
                WidgetPrefs.forget(app, id);
            }
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!WidgetIntents.isWidgetAction(action)) {
            super.onReceive(context, intent);
            return;
        }
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        Intent tap = new Intent(intent);
        WidgetWorker.run(() -> {
            boolean handedOff = false;
            try {
                handedOff = handle(app, tap, pending);
            } catch (RuntimeException e) {
                Log.w(TAG, "widget action failed", e);
            } finally {
                if (!handedOff) finish(pending);
            }
        });
    }

    private static void finish(PendingResult pending) {
        if (pending != null) pending.finish();
    }

    // ------------------------------------------------------------------ actions (widget thread)

    /** Returns true when a delayed settle update took over {@code pending}. */
    static boolean handle(Context app, Intent tap, PendingResult pending) {
        String action = tap.getAction();
        if (WidgetIntents.ACTION_REFRESH.equals(action)) {
            renderAll(app, null);
            return false;
        }
        int id = tap.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return false;

        Loaded before = load(app, id);
        boolean stateChanged = false;
        if (WidgetIntents.ACTION_PREV.equals(action)) {
            browse(app, id, before.view, -1);
        } else if (WidgetIntents.ACTION_NEXT.equals(action)) {
            browse(app, id, before.view, 1);
        } else if (WidgetIntents.ACTION_CURRENT.equals(action)) {
            WidgetPrefs.clearCursor(app, id);
            WidgetPrefs.clearReceipt(app, id);
        } else if (WidgetIntents.ACTION_DONE.equals(action)) {
            stateChanged = commit(app, id, tap, before);
        } else if (WidgetIntents.ACTION_UNDO.equals(action)) {
            stateChanged = undo(app, id, tap, before);
        } else if (WidgetIntents.ACTION_STORY_CONTINUE.equals(action)) {
            stateChanged = before.api != null && before.api.acknowledgeStory(
                    tap.getStringExtra(WidgetIntents.EXTRA_STORY_SOURCE), tap.getIntExtra(Intents.EXTRA_SLOT, -1));
            if (stateChanged) {
                WidgetPrefs.clearCursor(app, id);
                WidgetPrefs.clearReceipt(app, id);
            }
        }

        Loaded after = load(app, id);
        boolean fade = FADES.allowFade(id, before.view.night() != after.view.night(), SystemClock.elapsedRealtime());
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        render(app, manager, id, after, fade);
        if (stateChanged) {
            for (int other : widgetIds(app, manager)) {
                if (other != id) render(app, manager, other, load(app, other), false);
            }
        }
        if (!fade) return false;
        long token = FADES.current(id);
        WidgetWorker.schedule(() -> {
            try {
                if (FADES.isCurrent(id, token)) render(app, manager, id, load(app, id), false);
            } finally {
                finish(pending);
            }
        }, FadeGuard.SETTLE_MS);
        return true;
    }

    private static void browse(Context app, int id, PlanView view, int delta) {
        if (view.mode == PlanView.Mode.RECEIPT) {
            // The receipt sits just before the first item: › leaves it, ‹ has nowhere to go.
            if (delta > 0) {
                WidgetPrefs.clearReceipt(app, id);
                WidgetPrefs.clearCursor(app, id);
            }
            return;
        }
        WidgetPrefs.clearReceipt(app, id);
        if (view.mode != PlanView.Mode.ITEM) return;
        int target = Math.max(0, Math.min(view.items.size() - 1, view.index + delta));
        WidgetPrefs.setCursor(app, id, view.planId(), view.items.get(target).slot);
    }

    /** Direct AS_PREDICTED record of the current training/job slot; returns true if state changed. */
    private static boolean commit(Context app, int id, Intent tap, Loaded loaded) {
        PlannerApi.Snapshot snap = loaded.snapshot;
        String planId = tap.getStringExtra(Intents.EXTRA_PLAN_ID);
        long revision = tap.getLongExtra(Intents.EXTRA_STATE_REV, -1L);
        int slot = tap.getIntExtra(Intents.EXTRA_SLOT, -1);
        String action = tap.getStringExtra(Intents.EXTRA_ACTION);
        if (loaded.api == null || snap == null || snap.observed == null || snap.plan == null || planId == null || action == null) {
            return false;
        }
        boolean current = snap.planCurrent
                && planId.equals(snap.plan.planId)
                && revision == snap.stateRevision
                && slot == snap.observed.slot;
        Plan.Step step = current ? snap.plan.at(slot) : null;
        if (step == null || !action.equals(step.action) || !PlanView.isDirect(step)) {
            return false; // stale or tampered tap: never record, just refresh
        }
        // The revision is part of the key: after an undo the same plan, slot and action is a new
        // record (the voided one must not answer it as a duplicate).
        String commandId = WidgetIntents.commitCommandId(planId, revision, slot, action);
        PlannerApi.Receipt receipt = loaded.api.recordOutcome(commandId, revision, slot, action, Outcome.of(Outcome.Code.AS_PREDICTED));
        if (receipt == null || !receipt.ok) return false;
        WidgetPrefs.clearCursor(app, id);
        if (receipt.eventSeq < 0) return !receipt.duplicate; // recorded, but nothing the widget could undo
        WidgetPrefs.Receipt saved = new WidgetPrefs.Receipt();
        saved.eventSeq = receipt.eventSeq;
        saved.stateRevision = receipt.stateRevision;
        saved.slot = slot;
        saved.action = action;
        saved.planId = planId;
        saved.commandId = commandId;
        saved.title = step.titleZh;
        saved.subtitle = step.subtitleZh;
        saved.location = step.locationZh;
        saved.kind = step.kind;
        saved.createdMillis = now();
        WidgetPrefs.setReceipt(app, id, saved);
        scheduleReceiptExpiry(app);
        return !receipt.duplicate;
    }

    /** Voids the widget's own last record if nothing else was recorded since; returns true if state changed. */
    private static boolean undo(Context app, int id, Intent tap, Loaded loaded) {
        long seq = tap.getLongExtra(WidgetIntents.EXTRA_EVENT_SEQ, -1L);
        long revision = tap.getLongExtra(Intents.EXTRA_STATE_REV, -1L);
        WidgetPrefs.Receipt saved = WidgetPrefs.receipt(app, id);
        PlannerApi.Snapshot snap = loaded.snapshot;
        if (loaded.api == null || snap == null || saved == null || seq < 0) return false;
        if (saved.eventSeq != seq || saved.stateRevision != revision || snap.stateRevision != revision) {
            return false; // stale tap or the state moved on: never void
        }
        long age = now() - saved.createdMillis;
        if (age >= 0 && age < PlanView.UNDO_ARM_MS) {
            return false; // second half of a double tap on 完成 that landed on 撤销: keep the record
        }
        PlannerApi.Receipt receipt = loaded.api.voidEvent(WidgetIntents.voidCommandId(seq, revision), revision, seq);
        if (receipt == null || !receipt.ok) return false;
        WidgetPrefs.clearReceipt(app, id);
        WidgetPrefs.clearCursor(app, id);
        return !receipt.duplicate;
    }

    private static void scheduleReceiptExpiry(Context app) {
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        long at = SystemClock.elapsedRealtime() + PlanView.RECEIPT_TTL_MS + 1000L;
        alarms.set(AlarmManager.ELAPSED_REALTIME, at, WidgetIntents.refresh(app));
    }

    // ------------------------------------------------------------------ loading and rendering

    /** Snapshot, optional rules and the resolved view of one widget. */
    static final class Loaded {
        PlannerApi api;
        PlannerApi.Snapshot snapshot;
        Rules rules;
        PlanView view;
    }

    static Loaded load(Context app, int id) {
        Loaded loaded = new Loaded();
        try {
            loaded.api = WidgetApi.get(app);
            loaded.snapshot = loaded.api.snapshot();
        } catch (RuntimeException e) {
            Log.w(TAG, "planner unavailable", e);
            loaded.api = null;
            loaded.snapshot = null;
            loaded.view = PlanView.unavailable();
            return loaded;
        }
        loaded.rules = rulesOrNull(loaded.api);
        PlannerApi.Snapshot snap = loaded.snapshot;
        String planId = snap != null && snap.plan != null ? snap.plan.planId : "";
        int cursor = WidgetPrefs.cursorSlot(app, id, planId);
        loaded.view = PlanView.resolve(snap, cursor, WidgetPrefs.receipt(app, id), now());
        return loaded;
    }

    /** The rule pack refines weekday/moon/school-day on the plate; the widget works without it. */
    private static Rules rulesOrNull(PlannerApi api) {
        try {
            return api.rules();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int[] widgetIds(Context app, AppWidgetManager manager) {
        return manager.getAppWidgetIds(new ComponentName(app, PlanWidget.class));
    }

    static void renderAll(Context app, int[] ids) {
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        int[] targets = ids != null ? ids : widgetIds(app, manager);
        for (int id : targets) render(app, manager, id, load(app, id), false);
    }

    private static void render(Context app, AppWidgetManager manager, int id, Loaded loaded, boolean fade) {
        FADES.invalidate(id);
        CardModel model = loaded.view.toCard(loaded.rules);
        Bundle options = manager.getAppWidgetOptions(id);
        List<SizeF> sizes = WidgetSize.reportedSizes(options);
        if (Build.VERSION.SDK_INT >= 31 && !sizes.isEmpty()) {
            Map<SizeF, RemoteViews> variants = new LinkedHashMap<>();
            for (SizeF size : sizes) {
                variants.put(size, host(app, id, loaded.view, model, size.getWidth(), size.getHeight(), fade));
            }
            manager.updateAppWidget(id, new RemoteViews(variants));
            return;
        }
        float[] size = WidgetSize.fallback(app, options);
        manager.updateAppWidget(id, host(app, id, loaded.view, model, size[0], size[1], fade));
    }

    private static RemoteViews host(Context app, int id, PlanView view, CardModel model, float width, float height, boolean fade) {
        return PaletteHost.build(app, model.night, fade, night -> card(app, id, view, model.withNight(night), width, height));
    }

    /** One card with its click targets. */
    static RemoteViews card(Context app, int id, PlanView view, CardModel model, float width, float height) {
        RemoteViews views = CardRenderer.render(app, model, width, height);
        if (view.mode == PlanView.Mode.EMPTY) {
            PendingIntent setup = WidgetIntents.onboarding(app, id);
            views.setOnClickPendingIntent(R.id.widget_root, setup);
            views.setOnClickPendingIntent(R.id.widget_task_area, setup);
            views.setOnClickPendingIntent(R.id.button_spec_done, setup);
            return views;
        }
        int slot = view.shownSlot();
        PendingIntent today = WidgetIntents.home(app, id, WidgetIntents.TAB_TODAY, -1);
        PendingIntent schedule = WidgetIntents.home(app, id, WidgetIntents.TAB_SCHEDULE, -1);
        views.setOnClickPendingIntent(R.id.widget_root, today);
        views.setOnClickPendingIntent(R.id.widget_date, schedule);
        boolean showsStep = view.mode == PlanView.Mode.ITEM || view.mode == PlanView.Mode.RECEIPT;
        views.setOnClickPendingIntent(R.id.widget_task_area,
                showsStep ? WidgetIntents.home(app, id, WidgetIntents.TAB_TODAY, slot) : today);
        if (!showsStep) {
            // Status cards: the one button opens the app where the card points (打开应用 / 查看日程).
            views.setOnClickPendingIntent(R.id.button_spec_done, view.done == PlanView.Done.OPEN_SCHEDULE ? schedule : today);
            return views;
        }
        views.setOnClickPendingIntent(R.id.widget_prev, WidgetIntents.navigate(app, id, WidgetIntents.ACTION_PREV));
        views.setOnClickPendingIntent(R.id.widget_next, WidgetIntents.navigate(app, id, WidgetIntents.ACTION_NEXT));
        views.setOnClickPendingIntent(R.id.button_spec_current, WidgetIntents.navigate(app, id, WidgetIntents.ACTION_CURRENT));
        PendingIntent done = view.done == PlanView.Done.OPEN_SCHEDULE ? schedule : doneIntent(app, id, view);
        if (done != null) views.setOnClickPendingIntent(R.id.button_spec_done, done);
        return views;
    }

    private static PendingIntent doneIntent(Context app, int id, PlanView view) {
        String planId = view.planId();
        long revision = view.stateRevision();
        switch (view.done) {
            case STORY_CONTINUE:
                return WidgetIntents.storyContinue(app, id, view.snapshot.storyDisplay.key, view.item.slot);
            case NEXT:
                // A story-locked item: the button goes on to the next item, like ›.
                return WidgetIntents.navigate(app, id, WidgetIntents.ACTION_NEXT);
            case COMMIT:
                return WidgetIntents.commit(app, id, planId, revision, view.item.slot, view.item.step.action);
            case RESULT:
                return WidgetIntents.result(app, id, Intents.MODE_RESULT, planId, revision, view.item.slot, view.item.step.action);
            case CATCHUP:
                return WidgetIntents.result(app, id, Intents.MODE_CATCHUP, planId, revision, view.item.slot, view.item.step.action);
            case UNDO:
                return WidgetIntents.undo(app, id, view.receipt.eventSeq, view.receipt.stateRevision);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ test hooks

    private static volatile LongSupplier clock = System::currentTimeMillis;

    static long now() {
        return clock.getAsLong();
    }

    /** Replaces the wall clock (null restores it). */
    static void setClockForTest(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }

    /** The settled card of widget {@code id} at a size, exactly as the provider would draw it. */
    static RemoteViews renderForTest(Context context, int id, float widthDp, float heightDp) {
        Context app = context.getApplicationContext();
        Loaded loaded = load(app, id);
        return card(app, id, loaded.view, loaded.view.toCard(loaded.rules), widthDp, heightDp);
    }
}
