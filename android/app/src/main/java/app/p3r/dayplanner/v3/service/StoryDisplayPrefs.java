package app.p3r.dayplanner.v3.service;

import android.content.Context;
import android.content.SharedPreferences;

/** Acknowledged story cards. Kept separate from gameplay records and planning revisions. */
final class StoryDisplayPrefs {
    private final SharedPreferences prefs;
    private volatile Position position;

    private static final class Position {
        final String source;
        final int cursor;

        Position(String source, int cursor) {
            this.source = source;
            this.cursor = cursor;
        }
    }

    StoryDisplayPrefs(Context context, String databaseName) {
        prefs = context.getSharedPreferences("p3r_story_display_" + databaseName, Context.MODE_PRIVATE);
        position = new Position(prefs.getString("source", null), prefs.getInt("cursor", -1));
    }

    int cursor(String key, int from) {
        Position current = position;
        return key != null && key.equals(current.source) ? Math.max(from, current.cursor) : from;
    }

    boolean acknowledge(StoryDisplay story, String key, int slot) {
        if (story == null || !story.key.equals(key) || story.cursorSlot != slot) return false;
        int next = story.currentLastSlot() + 1;
        if (!prefs.edit().putString("source", key).putInt("cursor", next).commit()) return false;
        position = new Position(key, next);
        return true;
    }
}
