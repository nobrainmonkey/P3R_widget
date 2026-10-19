package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small per-device UI conveniences (never planner state): the last protagonist level typed on
 * the tower sheet, which record the return banner was dismissed for, whether 「更多选项」 is open
 * and which file the system picker is saving. Read these off the main thread where it matters;
 * writes use {@code apply()}.
 */
final class UiPrefs {
    static final String NAME = "p3r_v3_ui";
    private static final String KEY_LEVEL = "tower.level";
    private static final String KEY_BANNER = "banner.dismissedFor";
    private static final String KEY_LINKS_SECTION = "links.section";
    private static final String KEY_SETTINGS_MORE = "settings.moreOpen";
    private static final String KEY_PENDING_SAVE = "backup.pendingSave";

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

    /**
     * The 社群 tab's last sub-tab (社群 / 羁绊剧情 / 队友特性). Read on the first bind, after the
     * background load has already opened these preferences.
     */
    static int linksSection(Context context) {
        return prefs(context).getInt(KEY_LINKS_SECTION, 0);
    }

    static void setLinksSection(Context context, int section) {
        prefs(context).edit().putInt(KEY_LINKS_SECTION, section).apply();
    }

    /** Whether 规划设置's 「更多选项」 was left open (a small read; the file is loaded at start). */
    static boolean settingsMoreOpen(Context context) {
        return prefs(context).getBoolean(KEY_SETTINGS_MORE, false);
    }

    static void setSettingsMoreOpen(Context context, boolean open) {
        prefs(context).edit().putBoolean(KEY_SETTINGS_MORE, open).apply();
    }

    /**
     * What the document picked for {@link BackupPage#REQUEST_SAVE} is for ("backup", or null for the
     * old guide's ticks), kept across a recreated activity while the picker is open.
     */
    static String pendingSave(Context context) {
        return prefs(context).getString(KEY_PENDING_SAVE, null);
    }

    static void setPendingSave(Context context, String kind) {
        if (kind == null) {
            prefs(context).edit().remove(KEY_PENDING_SAVE).apply();
        } else {
            prefs(context).edit().putString(KEY_PENDING_SAVE, kind).apply();
        }
    }
}
