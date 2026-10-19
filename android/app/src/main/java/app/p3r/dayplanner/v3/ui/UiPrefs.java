package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small per-device UI conveniences (never planner state): the last protagonist level typed on
 * the tower sheet and which record the return banner was dismissed for. Read these off the
 * main thread; writes use {@code apply()}.
 */
final class UiPrefs {
    static final String NAME = "p3r_v3_ui";
    private static final String KEY_LEVEL = "tower.level";
    private static final String KEY_BANNER = "banner.dismissedFor";

    private UiPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static int towerLevel(Context context) {
        return prefs(context).getInt(KEY_LEVEL, 1);
    }

    static void setTowerLevel(Context context, int level) {
        prefs(context).edit().putInt(KEY_LEVEL, level).apply();
    }

    static long bannerDismissedFor(Context context) {
        return prefs(context).getLong(KEY_BANNER, 0L);
    }

    static void setBannerDismissedFor(Context context, long lastRecordMillis) {
        prefs(context).edit().putLong(KEY_BANNER, lastRecordMillis).apply();
    }
}
