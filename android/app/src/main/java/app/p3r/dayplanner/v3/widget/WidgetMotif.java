package app.p3r.dayplanner.v3.widget;

/**
 * Picks the background light-shaft composition for a game date, keyed on the game-day ordinal (days
 * since 2009-04-01), so the same date always looks the same.
 */
public final class WidgetMotif {
    /** Number of shaft compositions ({@code widget_shafts_0..3}). */
    public static final int VARIANTS = 4;

    private WidgetMotif() {}

    /** Deterministic FNV-1a selection; no wall-clock randomness. */
    public static int forDay(int dayOrdinal) {
        String key = "P3R-shafts-v3:" + dayOrdinal;
        int hash = 0x811c9dc5;
        for (int i = 0; i < key.length(); i++) {
            hash = (hash ^ key.charAt(i)) * 16777619;
        }
        return (int) (Integer.toUnsignedLong(hash) % VARIANTS);
    }
}
