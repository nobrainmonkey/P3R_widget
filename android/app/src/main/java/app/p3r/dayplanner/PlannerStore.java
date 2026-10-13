package app.p3r.dayplanner;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared, offline source of truth used by the full WebView UI and the launcher widget. */
public final class PlannerStore {
    private static final String PREFS = "p3r_native_progress_v1";
    private static final String KEY_COMPLETED = "completed";
    private static final String KEY_SUBSTEPS = "substeps";
    private static final String KEY_SELECTED = "selectedDate";
    private static final String KEY_THEME = "theme";
    private static final String KEY_WIDGET_CURSOR_PREFIX = "widgetCursor_";
    private static volatile List<Day> cachedDays;
    private static JSONObject displayMetadata = new JSONObject();

    public static final class Task {
        public final String id, title, period, kind, where, widgetTitle, locationLabel;
        public final boolean hasDetails;
        public final List<String> dialogueChoices;
        public final String dialogueCue;
        public final boolean hasSubsteps, requiresConfirmation;
        Task(JSONObject o) {
            id = o.optString("id", "");
            title = o.optString("title", "");
            period = o.optString("period", "");
            kind = o.optString("kind", "action");
            where = o.optString("where", "");
            JSONObject display = displayMetadata.optJSONObject(id);
            widgetTitle = display == null ? title : display.optString("widgetTitle", title);
            locationLabel = display == null ? where : display.optString("locationLabel", where);
            hasDetails = display != null && display.optBoolean("hasDetails", false);
            List<String> choices = new ArrayList<>();
            JSONArray choiceArray = display == null ? null : display.optJSONArray("dialogueChoices");
            if (choiceArray != null) for (int i = 0; i < choiceArray.length(); i++) choices.add(choiceArray.optString(i));
            dialogueChoices = Collections.unmodifiableList(choices);
            dialogueCue = display == null ? "" : display.optString("dialogueCue", "");
            JSONArray substeps = o.optJSONArray("substeps");
            hasSubsteps = substeps != null && substeps.length() > 0;
            JSONArray steps = o.optJSONArray("steps");
            requiresConfirmation = hasSubsteps || (steps != null && steps.length() > 1);
        }
        public boolean isCheckable() { return !"free".equals(kind) && !"auto".equals(kind) && !"note".equals(kind) && !"info".equals(kind); }
    }

    public static final class Day {
        public final String date;
        public final int month, day;
        public final List<Task> tasks;
        Day(JSONObject o) {
            date = o.optString("date", "");
            month = o.optInt("month", 4);
            day = o.optInt("day", 8);
            List<Task> list = new ArrayList<>();
            JSONArray entries = o.optJSONArray("items");
            if (entries != null) for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null) list.add(new Task(entry));
            }
            tasks = Collections.unmodifiableList(list);
        }
    }

    private PlannerStore() { }

    public static List<Day> days(Context context) {
        List<Day> current = cachedDays;
        if (current != null) return current;
        synchronized (PlannerStore.class) {
            if (cachedDays != null) return cachedDays;
            List<Day> list = new ArrayList<>();
            try (InputStream metadata = context.getAssets().open("display_metadata.json");
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = metadata.read(buffer)) != -1) bytes.write(buffer, 0, count);
                displayMetadata = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) { throw new IllegalStateException("Bundled display metadata could not be read", e); }
            try (InputStream input = context.getAssets().open("data.json");
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = input.read(buf)) != -1) bytes.write(buf, 0, n);
                JSONObject object = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                JSONArray blocks = object.getJSONArray("blocks");
                for (int i = 0; i < blocks.length(); i++) {
                    JSONObject block = blocks.optJSONObject(i);
                    if (block != null) list.add(new Day(block));
                }
            } catch (Exception e) { throw new IllegalStateException("Bundled P3R schedule could not be read", e); }
            cachedDays = Collections.unmodifiableList(list);
            return cachedDays;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject parseObject(String raw) {
        try { return new JSONObject(raw == null ? "{}" : raw); }
        catch (JSONException e) { return new JSONObject(); }
    }

    public static JSONObject completed(Context c) { return parseObject(prefs(c).getString(KEY_COMPLETED, "{}")); }

    public static boolean isDone(JSONObject completion, Task task) { return completion.optBoolean(task.id, false); }

    public static String theme(Context c) {
        return "night".equals(prefs(c).getString(KEY_THEME, "day")) ? "night" : "day";
    }

    public static boolean isValidDate(Context c, String date) {
        for (Day d : days(c)) if (d.date.equals(date)) return true;
        return false;
    }

    public static Day dayByDate(Context c, String date) {
        for (Day d : days(c)) if (d.date.equals(date)) return d;
        List<Day> all = days(c);
        if (all.isEmpty()) throw new IllegalStateException("No bundled dates");
        return all.get(0);
    }

    public static String selectedDate(Context c) {
        String date = prefs(c).getString(KEY_SELECTED, "");
        if (isValidDate(c, date)) return date;
        JSONObject done = completed(c);
        for (Day d : days(c)) if (firstPending(d, done) != null) return d.date;
        return days(c).get(0).date;
    }

    public static void selectDate(Context c, String date) {
        if (isValidDate(c, date)) prefs(c).edit().putString(KEY_SELECTED, date).apply();
    }

    public static void shiftDate(Context c, int delta) {
        List<Day> all = days(c);
        String current = selectedDate(c);
        int index = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).date.equals(current)) { index = i; break; }
        int next = Math.max(0, Math.min(all.size() - 1, index + delta));
        selectDate(c, all.get(next).date);
    }

    public static Task firstPending(Day date, JSONObject completion) {
        for (Task t : date.tasks) if (t.isCheckable() && !isDone(completion, t)) return t;
        return null;
    }

    /** The widget-only cursor lets NEXT show more tasks without marking any as completed. */
    public static String widgetCursor(Context c, int widgetId) {
        return prefs(c).getString(KEY_WIDGET_CURSOR_PREFIX + widgetId, "");
    }

    public static void setWidgetCursor(Context c, int widgetId, String taskId) {
        if (taskId == null || taskId.isEmpty()) clearWidgetCursor(c, widgetId);
        else prefs(c).edit().putString(KEY_WIDGET_CURSOR_PREFIX + widgetId, taskId).apply();
    }

    public static void clearWidgetCursor(Context c, int widgetId) {
        prefs(c).edit().remove(KEY_WIDGET_CURSOR_PREFIX + widgetId).apply();
    }

    public static Task currentWidgetTask(Context c, int widgetId, Day ignored, JSONObject completion) {
        return viewedTask(c, widgetId);
    }

    public static Task taskById(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Day d : days(c)) for (Task task : d.tasks) if (task.id.equals(id)) return task;
        return null;
    }

    /** Each widget has its own explicit cursor, including completed and informational entries. */
    public static Task viewedTask(Context c, int widgetId) {
        Task explicit = taskById(c, widgetCursor(c, widgetId));
        if (explicit != null) return explicit;
        Task pending = firstPendingGlobally(c);
        if (pending != null) return pending;
        for (Day day : days(c)) if (!day.tasks.isEmpty()) return day.tasks.get(0);
        return null;
    }

    public static Day viewedDay(Context c, int widgetId) {
        Task task = viewedTask(c, widgetId);
        return task == null ? dayByDate(c, selectedDate(c)) : dayForTask(c, task.id);
    }

    public static int viewedOrdinal(Context c, int widgetId) {
        Task task = viewedTask(c, widgetId);
        if (task == null) return 0;
        List<Task> entries = dayForTask(c, task.id).tasks;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(task.id)) return i + 1;
        return 0;
    }

    public static Task browseViewed(Context c, int widgetId, int delta) {
        List<Task> all = new ArrayList<>();
        for (Day day : days(c)) all.addAll(day.tasks);
        if (all.isEmpty()) return null;
        Task current = viewedTask(c, widgetId);
        int index = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(current == null ? "" : current.id)) { index = i; break; }
        Task next = all.get(Math.max(0, Math.min(all.size() - 1, index + delta)));
        setWidgetCursor(c, widgetId, next.id);
        return next;
    }

    public static Task jumpCurrent(Context c, int widgetId) {
        Task task = firstPendingGlobally(c);
        if (task != null) setWidgetCursor(c, widgetId, task.id);
        return viewedTask(c, widgetId);
    }

    private static final String KEY_WIDGET_ACTION_REVISION = "widgetActionRevision";

    public static synchronized long widgetActionRevision(Context c) {
        return prefs(c).getLong(KEY_WIDGET_ACTION_REVISION, 0L);
    }

    public static synchronized void invalidateWidgetActions(Context c) {
        prefs(c).edit().putLong(KEY_WIDGET_ACTION_REVISION, widgetActionRevision(c) + 1L).apply();
    }

    /** Consume one rendered button state; duplicate/stale PendingIntents cannot reverse it. */
    public static synchronized Task toggleViewed(Context c, int widgetId, String expectedTaskId,
                                                 boolean expectedDone, long expectedRevision) {
        Task shown = viewedTask(c, widgetId);
        if (expectedRevision != widgetActionRevision(c) || shown == null ||
            !shown.id.equals(expectedTaskId) || !shown.isCheckable() ||
            isDone(completed(c), shown) != expectedDone) return shown;
        invalidateWidgetActions(c);
        if (expectedDone) {
            JSONObject completion = completed(c);
            completion.remove(shown.id);
            // Match the app's parent checkbox: keep independently saved atomic substeps.
            prefs(c).edit().putString(KEY_COMPLETED, completion.toString()).apply();
            setWidgetCursor(c, widgetId, shown.id);
            return shown;
        }
        return completeViewed(c, widgetId, shown.id);
    }

    /** Complete the actually displayed task once, then move directly to the next pending task. */
    public static synchronized Task completeViewed(Context c, int widgetId, String expectedTaskId) {
        Task shown = viewedTask(c, widgetId);
        if (shown == null || !shown.id.equals(expectedTaskId) || !shown.isCheckable()) return shown;
        markCompleted(c, shown.id);
        Task next = nextPendingAfter(c, dayForTask(c, shown.id), shown.id, completed(c));
        if (next != null) setWidgetCursor(c, widgetId, next.id);
        else setWidgetCursor(c, widgetId, shown.id);
        return viewedTask(c, widgetId);
    }

    /** Chronological next unfinished task after the shown item, wrapping once at the end. */
    public static Task nextPendingAfter(Context c, Day currentDay, String currentTaskId,
                                        JSONObject completed) {
        List<Day> all = days(c);
        int currentIndex = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).date.equals(currentDay.date)) { currentIndex = i; break; }
        }
        int currentTaskIndex = -1;
        if (currentTaskId != null) {
            for (int i = 0; i < currentDay.tasks.size(); i++) {
                if (currentDay.tasks.get(i).id.equals(currentTaskId)) { currentTaskIndex = i; break; }
            }
        } else {
            // When a day is clear, jump to a pending task on a *later* day first.
            currentTaskIndex = currentDay.tasks.size() - 1;
        }
        for (int step = 0; step < all.size(); step++) {
            Day day = all.get((currentIndex + step) % all.size());
            int start = step == 0 ? currentTaskIndex + 1 : 0;
            for (int j = start; j < day.tasks.size(); j++) {
                Task task = day.tasks.get(j);
                if (task.isCheckable() && !isDone(completed, task)) return task;
            }
        }
        // If we wrapped past the current date, include earlier unfinished tasks on that day.
        for (int j = 0; j <= currentTaskIndex && j < currentDay.tasks.size(); j++) {
            Task task = currentDay.tasks.get(j);
            if (task.isCheckable() && !isDone(completed, task)) return task;
        }
        return null;
    }

    public static Task firstPendingGlobally(Context c) {
        JSONObject completion = completed(c);
        for (Day d : days(c)) {
            Task result = firstPending(d, completion);
            if (result != null) return result;
        }
        return null;
    }

    public static Day dayForTask(Context c, String id) {
        for (Day d : days(c)) for (Task t : d.tasks) if (t.id.equals(id)) return d;
        return dayByDate(c, selectedDate(c));
    }

    public static int countInDay(Day d) {
        int count = 0;
        for (Task t : d.tasks) if (t.isCheckable()) count++;
        return count;
    }

    public static int countCompleted(Day d, JSONObject completion) {
        int count = 0;
        for (Task t : d.tasks) if (t.isCheckable() && isDone(completion, t)) count++;
        return count;
    }

    public static void markCompleted(Context c, String taskId) {
        synchronized (PlannerStore.class) {
            Task target = taskById(c, taskId);
            if (target == null || !target.isCheckable()) return;
            JSONObject object = completed(c);
            try { object.put(taskId, true); } catch (JSONException ignored) { return; }
            prefs(c).edit().putString(KEY_COMPLETED, object.toString()).apply();
        }
    }

    public static synchronized void acceptWebState(Context c, String raw) {
        try {
            JSONObject state = new JSONObject(raw);
            JSONObject completion = state.optJSONObject(KEY_COMPLETED);
            JSONObject substeps = state.optJSONObject(KEY_SUBSTEPS);
            if (completion == null || substeps == null) return;
            SharedPreferences.Editor changes = prefs(c).edit();
            changes.putLong(KEY_WIDGET_ACTION_REVISION, widgetActionRevision(c) + 1L);
            changes.putString(KEY_COMPLETED, completion.toString());
            changes.putString(KEY_SUBSTEPS, substeps.toString());
            String selected = state.optString(KEY_SELECTED, "");
            if (isValidDate(c, selected)) changes.putString(KEY_SELECTED, selected);
            String theme = state.optString(KEY_THEME, "day");
            changes.putString(KEY_THEME, "night".equals(theme) ? "night" : "day");
            changes.apply();
        } catch (JSONException ignored) { /* Reject malformed JavaScript messages */ }
    }

    public static String snapshot(Context c) {
        JSONObject out = new JSONObject();
        try {
            out.put(KEY_COMPLETED, completed(c));
            out.put(KEY_SUBSTEPS, parseObject(prefs(c).getString(KEY_SUBSTEPS, "{}")));
            out.put(KEY_SELECTED, selectedDate(c));
            out.put(KEY_THEME, theme(c));
        } catch (JSONException ignored) { }
        return out.toString();
    }
}
