package app.p3r.dayplanner;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SizeF;
import android.widget.RemoteViews;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.v3.widget.CardModel;
import app.p3r.dayplanner.v3.widget.CardRenderer;
import app.p3r.dayplanner.v3.widget.FadeGuard;
import app.p3r.dayplanner.v3.widget.GameCalendar;
import app.p3r.dayplanner.v3.widget.PaletteHost;
import app.p3r.dayplanner.v3.widget.WidgetMotif;
import app.p3r.dayplanner.v3.widget.WidgetSize;

/**
 * Legacy fixed-guide home-screen widget (offline, native). Every control has a stable per-widget
 * identity. Behaviour (browse, Current, Done/undo with action revisions, preference keys) is
 * unchanged from 2.x; only the presentation uses the shared v3 card ({@link CardRenderer}).
 */
public class PlannerWidget extends AppWidgetProvider {
    public static final String ACTION_PREVIOUS = "app.p3r.dayplanner.action.PREVIOUS";
    public static final String ACTION_NEXT_DATE = "app.p3r.dayplanner.action.NEXT_DATE";
    public static final String ACTION_COMPLETE = "app.p3r.dayplanner.action.COMPLETE";
    public static final String ACTION_NEXT_TODO = "app.p3r.dayplanner.action.NEXT_TODO";
    private static final FadeGuard FADES = new FadeGuard();
    private static final int FLAGS = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
    /** The guide's "unmarked" period is an authoring note, never shown on the plate. */
    private static final String UNMARKED_PERIOD = "未标注";

    static boolean isCurrentGeneration(int id, long token) {
        return FADES.isCurrent(id, token);
    }

    static boolean allowPeriodFade(int id, boolean changed, long now) {
        return FADES.allowFade(id, changed, now);
    }

    public static void refreshAll(Context c) {
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        for (int id : manager.getAppWidgetIds(new ComponentName(c, PlannerWidget.class))) updateOne(c, manager, id);
    }

    private static PendingIntent broadcast(Context c, int widgetId, int offset, String action, String taskId) {
        Intent intent = new Intent(c, PlannerWidget.class).setAction(action);
        String identity = "p3r://widget/" + widgetId + "/" + offset + "/" + (taskId == null ? "none" : taskId);
        if (ACTION_COMPLETE.equals(action)) {
            PlannerStore.Task task = PlannerStore.taskById(c, taskId);
            boolean done = task != null && PlannerStore.isDone(PlannerStore.completed(c), task);
            long revision = PlannerStore.widgetActionRevision(c);
            // State and revision are part of identity, not merely mutable UPDATE_CURRENT extras.
            identity += "/" + revision + "/" + (done ? "undo" : "complete");
            intent.putExtra("expectedDone", done);
            intent.putExtra("actionRevision", revision);
        }
        intent.setData(Uri.parse(identity));
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        if (taskId != null) intent.putExtra("taskId", taskId);
        return PendingIntent.getBroadcast(c, widgetId * 16 + offset, intent, FLAGS);
    }

    private static PendingIntent open(Context c, int widgetId, int offset, String date, String taskId) {
        Intent intent = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_OPEN_TASK);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.setData(Uri.parse("p3r://open/" + widgetId + "/" + offset + "/" + (taskId == null ? "day" : taskId)));
        intent.putExtra(MainActivity.EXTRA_DATE, date);
        if (taskId != null) intent.putExtra(MainActivity.EXTRA_TASK_ID, taskId);
        return PendingIntent.getActivity(c, widgetId * 16 + offset, intent, FLAGS);
    }

    /** Background composition for a guide date; same key (game-day ordinal) as the plan widget. */
    public static int motifIndex(int month, int dayOfMonth) {
        return WidgetMotif.forDay(GameCalendar.ordinalOfGuideDate(month, dayOfMonth));
    }

    public static boolean isNightPeriod(String period) {
        return period != null && (period.contains("夜") || period.contains("晚"));
    }

    public static RemoteViews renderForTest(Context c, int widgetId, float widthDp, float heightDp) {
        return makeViews(c, widgetId, widthDp, heightDp);
    }

    private static RemoteViews makeViews(Context c, int widgetId, float width, float height) {
        return makeViews(c, widgetId, width, height, null);
    }

    private static RemoteViews makeViews(Context c, int widgetId, float width, float height, Boolean paletteNight) {
        PlannerStore.Task task = PlannerStore.viewedTask(c, widgetId);
        PlannerStore.Day day = PlannerStore.viewedDay(c, widgetId);
        WidgetSize size = WidgetSize.of(width, height);
        CardModel model = cardModel(c, widgetId, task, day, width, height, size);
        if (paletteNight != null) model = model.withNight(paletteNight);
        RemoteViews views = CardRenderer.render(c, model, width, height);
        String id = task == null ? null : task.id;
        views.setOnClickPendingIntent(R.id.widget_task_area, open(c, widgetId, 5, day.date, id));
        views.setOnClickPendingIntent(R.id.widget_date, open(c, widgetId, 4, day.date, id));
        views.setOnClickPendingIntent(R.id.widget_prev, broadcast(c, widgetId, 1, ACTION_PREVIOUS, id));
        views.setOnClickPendingIntent(R.id.widget_next, broadcast(c, widgetId, 2, ACTION_NEXT_DATE, id));
        views.setOnClickPendingIntent(R.id.button_spec_current, broadcast(c, widgetId, 7, ACTION_NEXT_TODO, id));
        views.setOnClickPendingIntent(R.id.button_spec_done, broadcast(c, widgetId, 6, ACTION_COMPLETE, id));
        return views;
    }

    /** Presentation of the viewed guide record as a shared v3 card. */
    private static CardModel cardModel(Context c, int widgetId, PlannerStore.Task task, PlannerStore.Day day,
                                       float width, float height, WidgetSize size) {
        CardModel m = new CardModel();
        m.night = task != null && isNightPeriod(task.period);
        int ordinal = GameCalendar.ordinalOfGuideDate(day.month, day.day);
        GameCalendar.Info calendar = GameCalendar.ofDay(ordinal, null);
        String period = task == null || UNMARKED_PERIOD.equals(task.period) || task.period.isEmpty() ? null : task.period;
        m.dateText = day.month + "/" + day.day;
        m.weekdayZh = calendar.weekdayZh();
        m.moonPhase = calendar.moonPhase;
        m.periodZh = period;
        m.dateDescription = day.month + "月" + day.day + "日 " + calendar.weekdayZh() + (period == null ? "" : " " + period);
        m.watermarkDay = day.day;
        m.motif = WidgetMotif.forDay(ordinal);

        org.json.JSONObject completed = PlannerStore.completed(c);
        int position = PlannerStore.viewedOrdinal(c, widgetId);
        int count = day.tasks.size();
        m.progressLabel = position + "/" + count;
        m.progressDescription = "当前任务 " + position + "，共 " + count + " 项";
        if (count <= CardRenderer.MAX_TICKS) {
            for (int index = 1; index <= count; index++) {
                PlannerStore.Task entry = day.tasks.get(index - 1);
                boolean done = PlannerStore.isDone(completed, entry);
                if (index == position) m.ticks.add(CardModel.Tick.CURRENT);
                else if (done) m.ticks.add(CardModel.Tick.DONE);
                else if (!entry.isCheckable()) m.ticks.add(CardModel.Tick.LOCKED);
                else m.ticks.add(CardModel.Tick.PENDING);
            }
        }

        boolean complete = task != null && PlannerStore.isDone(completed, task);
        boolean canComplete = task != null && task.isCheckable();
        m.title = task == null ? "暂无任务" : task.widgetTitle;
        m.location = task == null || task.locationLabel.isEmpty() ? null : task.locationLabel;
        m.titleMaxLines = size == WidgetSize.COMPACT ? 2 : 3;
        String choices = task == null ? "" : dialogueText(task);
        boolean showChoices = !choices.isEmpty() && dialogueFits(c, task, width, height, choices);
        m.dialogue = showChoices ? choices : null;
        m.cue = task != null && task.hasDetails ? "点开查看完整步骤" : null;
        if (task != null && (!task.dialogueChoices.isEmpty() || !task.dialogueCue.isEmpty())) {
            String cue = !task.dialogueCue.isEmpty() ? task.dialogueCue
                    : (!choices.isEmpty() && !showChoices ? "对话选项较长，点开查看" : "");
            m.cue = cue.isEmpty() ? null : cue;
            m.cueEssential = m.cue != null;
        }
        if (complete && canComplete) m.cue = "✓ 已完成 · 再点撤销";
        m.taskDescription = m.title + (complete ? "，已完成" : "") + "，点击查看完整步骤";

        m.doneEnabled = canComplete;
        m.doneLabel = complete && canComplete ? "撤销" : "完成";
        m.doneIcon = complete && canComplete ? CardModel.DoneIcon.UNDO : CardModel.DoneIcon.CHECK;
        m.doneDescription = canComplete ? (complete ? "撤销：取消这一项的完成状态" : "完成：完成这一项并进入下一项") : "此项无需打卡";
        m.currentDescription = "当前：回到最早未完成的任务";
        return m;
    }

    static String dialogueText(PlannerStore.Task task) {
        if (task.dialogueChoices.isEmpty()) return "";
        int from = 0;
        boolean alreadyInTitle = true;
        for (String choice : task.dialogueChoices) {
            int at = task.widgetTitle.indexOf(choice, from);
            if (at < 0) { alreadyInTitle = false; break; }
            from = at + choice.length();
        }
        return alreadyInTitle ? "" : android.text.TextUtils.join(" / ", task.dialogueChoices);
    }

    /**
     * Whether the title (at its smallest autosize step), the full choices and the rows that stay on
     * the card (location, dialogue cue) fit the card body.
     */
    static boolean dialogueFits(Context c, PlannerStore.Task task, float width, float height, String choices) {
        WidgetSize size = WidgetSize.of(width, height);
        float innerWidth = width - 2 * CardRenderer.bodyPaddingDp(size);
        CardModel rows = new CardModel();
        rows.location = task.locationLabel.isEmpty() ? null : task.locationLabel;
        rows.cue = task.dialogueCue.isEmpty() ? null : task.dialogueCue;
        float needed = CardRenderer.textHeightDp(c, task.widgetTitle, CardRenderer.MIN_TITLE_SP, true, innerWidth, 3)
                + 3 + CardRenderer.textHeightDp(c, choices, 12, false, innerWidth, 20, 1)
                + CardRenderer.metaRowHeightDp(c, rows, size)
                + CardRenderer.cueRowHeightDp(c, rows, size);
        return needed <= height - CardRenderer.chromeHeightDp(size);
    }

    /** Stable host tree: slot contents refresh, but the attached animator itself survives reapply. */
    static RemoteViews hostViews(Context c, int id, float width, float height, boolean animate) {
        PlannerStore.Task task = PlannerStore.viewedTask(c, id);
        boolean night = task != null && isNightPeriod(task.period);
        return PaletteHost.build(c, night, animate, forcedNight -> makeViews(c, id, width, height, forcedNight));
    }

    static RemoteViews transitionViews(Context c, int id, float width, float height) {
        return hostViews(c, id, width, height, true);
    }

    private static void updateOne(Context c, AppWidgetManager manager, int id) {
        updateOne(c, manager, id, false);
    }

    private static void updateOne(Context c, AppWidgetManager manager, int id, boolean fade) {
        FADES.invalidate(id);
        Bundle options = manager.getAppWidgetOptions(id);
        List<SizeF> sizes = WidgetSize.reportedSizes(options);
        if (!sizes.isEmpty()) {
            Map<SizeF, RemoteViews> variants = new LinkedHashMap<>();
            for (SizeF size : sizes) variants.put(size, hostViews(c, id, size.getWidth(), size.getHeight(), fade));
            manager.updateAppWidget(id, new RemoteViews(variants));
            return;
        }
        float[] size = WidgetSize.fallback(c, options);
        manager.updateAppWidget(id, hostViews(c, id, size[0], size[1], fade));
    }

    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        for (int id : ids) updateOne(c, manager, id);
    }

    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager manager, int id, Bundle options) {
        updateOne(c, manager, id);
    }

    @Override public void onDeleted(Context c, int[] ids) {
        for (int id : ids) {
            FADES.forget(id);
            PlannerStore.clearWidgetCursor(c, id);
        }
        super.onDeleted(c, ids);
    }

    @Override public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        if (intent == null) return;
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        if (id < 0) return;
        PlannerStore.Task before = PlannerStore.viewedTask(c, id);
        boolean wasNight = before != null && isNightPeriod(before.period);
        String action = intent.getAction();
        if (ACTION_PREVIOUS.equals(action)) { PlannerStore.invalidateWidgetActions(c); PlannerStore.browseViewed(c, id, -1); }
        else if (ACTION_NEXT_DATE.equals(action)) { PlannerStore.invalidateWidgetActions(c); PlannerStore.browseViewed(c, id, 1); }
        else if (ACTION_NEXT_TODO.equals(action)) { PlannerStore.invalidateWidgetActions(c); PlannerStore.jumpCurrent(c, id); }
        else if (ACTION_COMPLETE.equals(action)) PlannerStore.toggleViewed(c, id, intent.getStringExtra("taskId"),
                intent.getBooleanExtra("expectedDone", false), intent.getLongExtra("actionRevision", -1L));
        else return;
        PlannerStore.Task after = PlannerStore.viewedTask(c, id);
        boolean night = after != null && isNightPeriod(after.period);
        boolean fade = allowPeriodFade(id, wasNight != night, SystemClock.elapsedRealtime());
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        for (int other : manager.getAppWidgetIds(new ComponentName(c, PlannerWidget.class)))
            if (other != id) updateOne(c, manager, other);
        updateOne(c, manager, id, fade);
        if (fade) {
            final long token = FADES.current(id);
            final Context app = c.getApplicationContext();
            final PendingResult pending = goAsync();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (isCurrentGeneration(id, token)) updateOne(app, AppWidgetManager.getInstance(app), id);
                } finally { if (pending != null) pending.finish(); }
            }, FadeGuard.SETTLE_MS);
        }
    }
}
