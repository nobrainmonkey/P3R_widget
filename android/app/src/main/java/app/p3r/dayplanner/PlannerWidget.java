package app.p3r.dayplanner;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashMap;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.graphics.Typeface;
import android.text.StaticLayout;
import android.text.Layout;
import android.text.TextPaint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.RemoteViews;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native, offline home-screen widget. Every control has a stable per-widget identity. */
public class PlannerWidget extends AppWidgetProvider {
    public static final String ACTION_PREVIOUS = "app.p3r.dayplanner.action.PREVIOUS";
    public static final String ACTION_NEXT_DATE = "app.p3r.dayplanner.action.NEXT_DATE";
    public static final String ACTION_COMPLETE = "app.p3r.dayplanner.action.COMPLETE";
    public static final String ACTION_NEXT_TODO = "app.p3r.dayplanner.action.NEXT_TODO";
    private static final long FADE_SETTLE_MS = 1000L;
    private static final Map<Integer,Long> UPDATE_GENERATIONS = new HashMap<>();
    private static final Map<Integer,Long> LAST_ACTION_UPDATES = new HashMap<>();
    private static long generation;
    private static synchronized long invalidateFade(int id) {
        long next = ++generation;
        UPDATE_GENERATIONS.put(id, next);
        return next;
    }
    static synchronized boolean isCurrentGeneration(int id, long token) {
        return UPDATE_GENERATIONS.containsKey(id) && UPDATE_GENERATIONS.get(id) == token;
    }
    static synchronized boolean allowPeriodFade(int id, boolean changed, long now) {
        Long previous = LAST_ACTION_UPDATES.put(id, now);
        return changed && (previous == null || now - previous >= 300L);
    }
    private static final int FLAGS = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
    private static final String[] MONTHS = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
    private static final String[] MOTIFS = {"clock","arcana","glass","chronicle","window","notebook","headphones","calendar","moon","tower","evoker"};

    public static void refreshAll(Context c) {
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        for (int id : manager.getAppWidgetIds(new ComponentName(c, PlannerWidget.class))) updateOne(c, manager, id);
    }

    private static PendingIntent broadcast(Context c, int widgetId, int offset, String action, String taskId) {
        Intent intent = new Intent(c, PlannerWidget.class).setAction(action);
        String identity = "p3r://widget/" + widgetId + "/" + offset + "/" + (taskId == null ? "none" : taskId);
        if (ACTION_COMPLETE.equals(action)) {
            PlannerStore.Task task = PlannerStore.taskById(c,taskId);
            boolean done = task != null && PlannerStore.isDone(PlannerStore.completed(c),task);
            long revision = PlannerStore.widgetActionRevision(c);
            // State and revision are part of identity, not merely mutable UPDATE_CURRENT extras.
            identity += "/" + revision + "/" + (done ? "undo" : "complete");
            intent.putExtra("expectedDone",done);
            intent.putExtra("actionRevision",revision);
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

    /** Exactly the preview's unsigned FNV-1a date selection; no wall-clock randomness. */
    public static int motifIndex(String date) {
        int hash = 0x811c9dc5;
        String key = "P3R-lineart-v1:" + date;
        for (int i = 0; i < key.length(); i++) hash = (hash ^ key.charAt(i)) * 16777619;
        return (int)(Integer.toUnsignedLong(hash) % MOTIFS.length);
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
        boolean night = paletteNight != null ? paletteNight : task != null && isNightPeriod(task.period);
        boolean compact = width < 230 || height < 175;
        int layout = compact ? (night ? R.layout.widget_final_compact_night : R.layout.widget_final_compact_day) : (night ? R.layout.widget_final_night : R.layout.widget_final_day);
        if (width < 200) layout = night ? R.layout.widget_final_tiny_night : R.layout.widget_final_tiny_day;
        RemoteViews views = new RemoteViews(c.getPackageName(), layout);
        String motif = "widget_motif_" + MOTIFS[motifIndex(day.date)] + (night ? "_night" : "_day");
        int resource = c.getResources().getIdentifier(motif, "drawable", c.getPackageName());
        views.setImageViewResource(R.id.widget_motif, resource);
        views.setImageViewResource(R.id.widget_period_icon, night ? R.drawable.widget_moon : R.drawable.widget_sun);
        views.setTextViewText(R.id.widget_period_word, night ? "夜" : "昼");
        views.setTextViewText(R.id.widget_date, MONTHS[Math.max(0, Math.min(11, day.month - 1))]);
        views.setTextViewText(R.id.widget_day_number, String.valueOf(day.day));
        String title = task == null ? "暂无任务" : task.widgetTitle;
        views.setTextViewText(R.id.widget_task, title);
        views.setViewVisibility(R.id.widget_detail_cue, task != null && task.hasDetails ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.widget_location, task == null || task.locationLabel.isEmpty() ? "" : "⌖ " + task.locationLabel);
        int ordinal = PlannerStore.viewedOrdinal(c, widgetId), count = day.tasks.size();
        views.setTextViewText(R.id.widget_progress, ordinal + "/" + count);
        SpannableStringBuilder dots = new SpannableStringBuilder();
        org.json.JSONObject completedTasks = PlannerStore.completed(c);
        StringBuilder dotDescription = new StringBuilder();
        for (int index = 1; index <= count; index++) {
            if (index > 1) dots.append(' ');
            boolean done = PlannerStore.isDone(completedTasks, day.tasks.get(index - 1));
            boolean current = index == ordinal;
            int start = dots.length();
            dots.append(current ? (done ? '◉' : '◎') : (done ? '●' : '○'));
            dots.setSpan(new ForegroundColorSpan(done || current ? (night ? 0xffa8e7ff : 0xff1d384a) : (night ? 0xffa1b9d3 : 0xff1d384a)),start,dots.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            dotDescription.append(index).append(done ? "已完成" : "未完成").append(current ? "，当前；" : "；");
        }
        views.setTextViewText(R.id.widget_dots, dots);
        views.setContentDescription(R.id.widget_dots,dotDescription.toString());
        views.setTextViewTextSize(R.id.widget_dots, TypedValue.COMPLEX_UNIT_SP, count > 6 ? 5 : 7);
        views.setContentDescription(R.id.widget_progress, "当前任务 " + ordinal + "，共 " + count + " 项");
        boolean small = width < 260;
        float textSize = small ? (height < 180 ? 13f : 14f) : 17f;
        int lines = Math.max(1, Math.min(6, (int)((height - (task != null && task.hasDetails ? 128 : 114)) / (textSize * 1.2f))));
        String choices = task == null ? "" : dialogueText(task);
        boolean showChoices = !choices.isEmpty() && dialogueFits(c,task,width,height,textSize,choices);
        int taskPadding = showChoices ? 0 : Math.round(2*c.getResources().getDisplayMetrics().density);
        views.setViewPadding(R.id.widget_task_area,0,taskPadding,0,taskPadding);
        views.setViewVisibility(R.id.widget_dialogue_choices,showChoices ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.widget_dialogue_choices,showChoices ? choices : "");
        if (task != null && (!task.dialogueChoices.isEmpty() || !task.dialogueCue.isEmpty())) {
            String cue = !task.dialogueCue.isEmpty() ? task.dialogueCue : (!choices.isEmpty() && !showChoices ? "对话选项较长，点开查看" : "");
            views.setTextViewText(R.id.widget_detail_cue,cue);
            views.setViewVisibility(R.id.widget_detail_cue,cue.isEmpty() ? View.GONE : View.VISIBLE);
        }
        views.setTextViewTextSize(R.id.widget_task, TypedValue.COMPLEX_UNIT_SP, textSize);
        views.setInt(R.id.widget_task, "setMaxLines", Math.max(2, lines));
        views.setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_SP, small ? 17 : 21);
        views.setTextViewTextSize(R.id.widget_day_number, TypedValue.COMPLEX_UNIT_SP, small ? 19 : 23);
        views.setTextViewTextSize(R.id.widget_period_word, TypedValue.COMPLEX_UNIT_SP, width < 200 ? 14 : small ? 16 : 20);
        String id = task == null ? null : task.id;
        boolean complete = task != null && PlannerStore.isDone(PlannerStore.completed(c), task);
        views.setContentDescription(R.id.widget_task_area, title + (complete ? "，已完成" : "") + "，点击查看完整步骤");
        views.setOnClickPendingIntent(R.id.widget_task_area, open(c, widgetId, 5, day.date, id));
        views.setOnClickPendingIntent(R.id.widget_brand_wrap, open(c, widgetId, 3, day.date, id));
        views.setOnClickPendingIntent(R.id.widget_date, open(c, widgetId, 4, day.date, id));
        views.setOnClickPendingIntent(R.id.widget_day_number, open(c, widgetId, 4, day.date, id));
        views.setOnClickPendingIntent(R.id.widget_prev, broadcast(c, widgetId, 1, ACTION_PREVIOUS, id));
        views.setOnClickPendingIntent(R.id.widget_next, broadcast(c, widgetId, 2, ACTION_NEXT_DATE, id));
        views.setOnClickPendingIntent(R.id.button_spec_current, broadcast(c, widgetId, 7, ACTION_NEXT_TODO, id));
        views.setOnClickPendingIntent(R.id.button_spec_done, broadcast(c, widgetId, 6, ACTION_COMPLETE, id));
        boolean canComplete = task != null && task.isCheckable();
        views.setBoolean(R.id.button_spec_done, "setEnabled", canComplete);
        views.setTextColor(R.id.button_spec_done_text, canComplete ? 0xff101723 : 0x99101723);
        views.setContentDescription(R.id.button_spec_done, canComplete ? (complete ? "Done，撤销当前任务完成状态" : "Done，完成当前任务并进入下一项") : "此项无需打卡");
        return views;
    }

    static String dialogueText(PlannerStore.Task task) {
        if (task.dialogueChoices.isEmpty()) return "";
        int from = 0;
        boolean alreadyInTitle = true;
        for (String choice : task.dialogueChoices) {
            int at = task.widgetTitle.indexOf(choice,from);
            if (at < 0) { alreadyInTitle = false; break; }
            from = at + choice.length();
        }
        return alreadyInTitle ? "" : android.text.TextUtils.join(" / ",task.dialogueChoices);
    }

    private static int textHeight(Context c,String text,float sp,int width,boolean bold,float extra) {
        android.widget.TextView measure = new android.widget.TextView(c);
        measure.setTextSize(TypedValue.COMPLEX_UNIT_SP,sp);
        measure.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        measure.setIncludeFontPadding(false);
        measure.setLineSpacing(extra,1f);
        measure.setText(text);
        measure.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,width),View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        return measure.getMeasuredHeight();
    }

    static boolean dialogueFits(Context c,PlannerStore.Task task,float width,float height,float titleSp,String choices) {
        boolean compact = width < 230 || height < 175;
        float padding = compact ? 6 : 8, header = compact ? 28 : 32;
        float density = c.getResources().getDisplayMetrics().density;
        int innerWidth = Math.round((width-2*padding)*density);
        int available = Math.round((height-2*padding-header-1-23-4-40)*density);
        int needed = textHeight(c,task.widgetTitle,11,innerWidth,true,0)
            + textHeight(c,choices,12,innerWidth,false,density) + Math.round(2*density);
        if (!task.dialogueCue.isEmpty()) needed += Math.round(14*density);
        return needed <= available;
    }

    /** Stable host tree: slot contents refresh, but the attached animator itself survives reapply. */
    static RemoteViews hostViews(Context c, int id, float width, float height, boolean animate) {
        PlannerStore.Task task = PlannerStore.viewedTask(c,id);
        boolean night = task != null && isNightPeriod(task.period);
        RemoteViews host = new RemoteViews(c.getPackageName(),R.layout.widget_palette_host);
        // Retain the outgoing, already-laid-out child until the animation finishes.
        // Replacing it while GONE would leave its new content unlaid-out and fade from transparency.
        if(!animate || !night) {
            host.removeAllViews(R.id.widget_palette_day_slot);
            host.addView(R.id.widget_palette_day_slot,makeViews(c,id,width,height,false));
        }
        if(!animate || night) {
            host.removeAllViews(R.id.widget_palette_night_slot);
            host.addView(R.id.widget_palette_night_slot,makeViews(c,id,width,height,true));
        }
        host.removeAllViews(R.id.widget_palette_static);
        host.addView(R.id.widget_palette_static,makeViews(c,id,width,height));
        host.setDisplayedChild(R.id.widget_palette_flipper,night ? 1 : 0);
        host.setViewVisibility(R.id.widget_palette_flipper,animate ? View.VISIBLE : View.INVISIBLE);
        host.setViewVisibility(R.id.widget_palette_static,animate ? View.GONE : View.VISIBLE);
        return host;
    }

    static RemoteViews transitionViews(Context c, int id, float width, float height) {
        return hostViews(c,id,width,height,true);
    }

    private static float[] fallbackSize(Context c, Bundle options) {
        if (options == null) return new float[]{280,180};
        boolean landscape = c.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        int maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW);
        int minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180);
        int maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH);
        return new float[]{Math.max(180, landscape ? maxW : minW),Math.max(160,landscape ? minH : maxH)};
    }

    private static void updateOne(Context c, AppWidgetManager manager, int id) {
        updateOne(c, manager, id, false);
    }

    private static void updateOne(Context c, AppWidgetManager manager, int id, boolean fade) {
        invalidateFade(id);
        Bundle options = manager.getAppWidgetOptions(id);
        if (Build.VERSION.SDK_INT >= 31 && options != null) {
            List<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes != null && !sizes.isEmpty() && sizes.size() <= 12) {
                Map<SizeF,RemoteViews> variants = new LinkedHashMap<>();
                for (SizeF size : sizes) if (size.getWidth() > 0 && size.getHeight() > 0) variants.put(size,hostViews(c,id,size.getWidth(),size.getHeight(),fade));
                if (!variants.isEmpty()) {manager.updateAppWidget(id,new RemoteViews(variants));return;}
            }
        }
        float[] size = fallbackSize(c,options);
        manager.updateAppWidget(id,hostViews(c,id,size[0],size[1],fade));
    }

    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {for(int id:ids)updateOne(c,manager,id);}
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager manager, int id, Bundle options) {updateOne(c,manager,id);}
    @Override public void onDeleted(Context c, int[] ids) {for(int id:ids){invalidateFade(id);LAST_ACTION_UPDATES.remove(id);PlannerStore.clearWidgetCursor(c,id);}super.onDeleted(c,ids);}
    @Override public void onReceive(Context c, Intent intent) {
        super.onReceive(c,intent);
        if(intent==null)return;
        int id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1);
        if(id<0)return;
        PlannerStore.Task before = PlannerStore.viewedTask(c,id);
        boolean wasNight = before != null && isNightPeriod(before.period);
        String action=intent.getAction();
        if(ACTION_PREVIOUS.equals(action)) { PlannerStore.invalidateWidgetActions(c); PlannerStore.browseViewed(c,id,-1); }
        else if(ACTION_NEXT_DATE.equals(action)) { PlannerStore.invalidateWidgetActions(c); PlannerStore.browseViewed(c,id,1); }
        else if(ACTION_NEXT_TODO.equals(action)) { PlannerStore.invalidateWidgetActions(c); PlannerStore.jumpCurrent(c,id); }
        else if(ACTION_COMPLETE.equals(action))PlannerStore.toggleViewed(c,id,intent.getStringExtra("taskId"),
            intent.getBooleanExtra("expectedDone",false),intent.getLongExtra("actionRevision",-1L));
        else return;
        PlannerStore.Task after = PlannerStore.viewedTask(c,id);
        boolean night = after != null && isNightPeriod(after.period);
        boolean fade = allowPeriodFade(id, wasNight != night, SystemClock.elapsedRealtime());
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        for(int other:manager.getAppWidgetIds(new ComponentName(c,PlannerWidget.class)))
            if(other != id) updateOne(c,manager,other);
        updateOne(c,manager,id,fade);
        if(fade) {
            final long token = UPDATE_GENERATIONS.get(id);
            final Context app = c.getApplicationContext();
            final PendingResult pending = goAsync();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if(isCurrentGeneration(id,token)) updateOne(app,AppWidgetManager.getInstance(app),id);
                } finally { if(pending != null) pending.finish(); }
            },FADE_SETTLE_MS);
        }
    }
}
