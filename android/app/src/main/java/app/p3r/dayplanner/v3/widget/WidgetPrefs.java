package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Per-widget UI state of the plan widget in SharedPreferences {@value #PREFS} (never the planner's
 * data): the browsing cursor, keyed by the plan id it belongs to, and the receipt of the last direct
 * record (what "再点撤销" would undo). Accessed only from the widget thread.
 */
final class WidgetPrefs {
    static final String PREFS = "p3r_v3_widget";
    private static final String CURSOR = "cursor_";
    private static final String CURSOR_PLAN = "cursorPlan_";
    private static final String RECEIPT = "receipt_";

    /** A record the widget made directly and can still undo. */
    static final class Receipt {
        long eventSeq;
        /** State revision right after the record; undo is only offered while it is still current. */
        long stateRevision;
        int slot;
        String action = "";
        String planId = "";
        String commandId = "";
        String title = "";
        String subtitle = "";
        String location;
        String kind = "";
        long createdMillis;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("seq", eventSeq);
            o.put("rev", stateRevision);
            o.put("slot", slot);
            o.put("action", action);
            o.put("planId", planId);
            o.put("commandId", commandId);
            o.put("title", title);
            o.put("subtitle", subtitle);
            if (location != null) o.put("location", location);
            o.put("kind", kind);
            o.put("created", createdMillis);
            return o;
        }

        static Receipt fromJson(JSONObject o) {
            Receipt r = new Receipt();
            r.eventSeq = o.optLong("seq", -1);
            r.stateRevision = o.optLong("rev", -1);
            r.slot = o.optInt("slot", -1);
            r.action = o.optString("action", "");
            r.planId = o.optString("planId", "");
            r.commandId = o.optString("commandId", "");
            r.title = o.optString("title", "");
            r.subtitle = o.optString("subtitle", "");
            r.location = o.has("location") ? o.optString("location") : null;
            r.kind = o.optString("kind", "");
            r.createdMillis = o.optLong("created", 0);
            return r;
        }
    }

    private WidgetPrefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Slot the widget is browsing in plan {@code planId}, or -1 (show the pending item). */
    static int cursorSlot(Context c, int widgetId, String planId) {
        SharedPreferences p = prefs(c);
        String stored = p.getString(CURSOR_PLAN + widgetId, null);
        if (stored == null || !stored.equals(planId)) return -1;
        return p.getInt(CURSOR + widgetId, -1);
    }

    static void setCursor(Context c, int widgetId, String planId, int slot) {
        prefs(c).edit().putString(CURSOR_PLAN + widgetId, planId).putInt(CURSOR + widgetId, slot).commit();
    }

    static void clearCursor(Context c, int widgetId) {
        prefs(c).edit().remove(CURSOR_PLAN + widgetId).remove(CURSOR + widgetId).commit();
    }

    static Receipt receipt(Context c, int widgetId) {
        String raw = prefs(c).getString(RECEIPT + widgetId, null);
        if (raw == null) return null;
        try {
            return Receipt.fromJson(new JSONObject(raw));
        } catch (JSONException e) {
            return null;
        }
    }

    static void setReceipt(Context c, int widgetId, Receipt receipt) {
        try {
            prefs(c).edit().putString(RECEIPT + widgetId, receipt.toJson().toString()).commit();
        } catch (JSONException e) {
            clearReceipt(c, widgetId);
        }
    }

    static void clearReceipt(Context c, int widgetId) {
        prefs(c).edit().remove(RECEIPT + widgetId).commit();
    }

    /** Removes everything stored for a deleted widget. */
    static void forget(Context c, int widgetId) {
        prefs(c).edit()
                .remove(CURSOR_PLAN + widgetId)
                .remove(CURSOR + widgetId)
                .remove(RECEIPT + widgetId)
                .commit();
    }
}
