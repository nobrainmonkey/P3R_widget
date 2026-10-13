package app.p3r.dayplanner.v3.widget;

import android.content.Context;

import app.p3r.dayplanner.R;

/**
 * Day (cyan surface) and night (deep-blue surface) palettes shared by both home-screen widgets.
 * Layout XML carries the day palette as its default; {@link CardRenderer} applies the night palette
 * at runtime through these resource ids and colours.
 */
public enum WidgetPalette {
    DAY(R.drawable.widget_surface_day, R.drawable.widget_plate_day, R.drawable.widget_weekday_day,
            R.drawable.widget_tag_day, R.drawable.widget_icon_pin_day, R.drawable.widget_sees_day,
            R.drawable.button_spec_current_day, R.drawable.button_spec_done_day,
            R.color.widget_day_ink, R.color.widget_day_ink_soft, R.color.widget_day_plate_ink,
            R.color.widget_day_chip_ink, R.color.widget_day_tag_ink, R.color.widget_day_accent,
            R.color.widget_day_ink, R.color.widget_day_button_ink, R.color.widget_day_button_ink_disabled,
            255, 255),
    NIGHT(R.drawable.widget_surface_night, R.drawable.widget_plate_night, R.drawable.widget_weekday_night,
            R.drawable.widget_tag_night, R.drawable.widget_icon_pin_night, R.drawable.widget_sees_night,
            R.drawable.button_spec_current_night, R.drawable.button_spec_done_night,
            R.color.widget_night_ink, R.color.widget_night_ink_soft, R.color.widget_night_plate_ink,
            R.color.widget_night_chip_ink, R.color.widget_night_tag_ink, R.color.widget_night_accent,
            R.color.widget_night_ghost_ink, R.color.widget_night_button_ink, R.color.widget_night_button_ink_disabled,
            150, 120);

    public final int surface;
    public final int plate;
    public final int weekdayChip;
    public final int tag;
    public final int pin;
    public final int wordmark;
    public final int currentButton;
    public final int doneButton;
    final int inkRes;
    final int inkSoftRes;
    final int plateInkRes;
    final int chipInkRes;
    final int tagInkRes;
    final int accentRes;
    final int ghostInkRes;
    final int buttonInkRes;
    final int buttonInkDisabledRes;
    /**
     * Image alpha (0..255) applied to the light shafts. The vectors carry the day strength in their
     * own fill alpha, so day is 255 and night only dims them.
     */
    public final int shaftImageAlpha;
    /** Image alpha applied to the outlined day-number watermark (day strength is baked in). */
    public final int watermarkImageAlpha;

    WidgetPalette(int surface, int plate, int weekdayChip, int tag, int pin, int wordmark,
                  int currentButton, int doneButton, int inkRes, int inkSoftRes, int plateInkRes,
                  int chipInkRes, int tagInkRes, int accentRes, int ghostInkRes, int buttonInkRes,
                  int buttonInkDisabledRes, int shaftImageAlpha, int watermarkImageAlpha) {
        this.surface = surface;
        this.plate = plate;
        this.weekdayChip = weekdayChip;
        this.tag = tag;
        this.pin = pin;
        this.wordmark = wordmark;
        this.currentButton = currentButton;
        this.doneButton = doneButton;
        this.inkRes = inkRes;
        this.inkSoftRes = inkSoftRes;
        this.plateInkRes = plateInkRes;
        this.chipInkRes = chipInkRes;
        this.tagInkRes = tagInkRes;
        this.accentRes = accentRes;
        this.ghostInkRes = ghostInkRes;
        this.buttonInkRes = buttonInkRes;
        this.buttonInkDisabledRes = buttonInkDisabledRes;
        this.shaftImageAlpha = shaftImageAlpha;
        this.watermarkImageAlpha = watermarkImageAlpha;
    }

    public static WidgetPalette of(boolean night) {
        return night ? NIGHT : DAY;
    }

    /** Primary body ink (title, progress). */
    public int ink(Context c) { return c.getColor(inkRes); }

    /** Secondary body ink (location, cue, dialogue). */
    public int inkSoft(Context c) { return c.getColor(inkSoftRes); }

    /** Ink on the calendar plate (numerals, period word, chevrons, moon). */
    public int plateInk(Context c) { return c.getColor(plateInkRes); }

    public int chipInk(Context c) { return c.getColor(chipInkRes); }

    public int tagInk(Context c) { return c.getColor(tagInkRes); }

    /** Accent for the progress tick of the shown slot. */
    public int accent(Context c) { return c.getColor(accentRes); }

    /** Label and icon colour of the ghost 当前 button. */
    public int ghostInk(Context c) { return c.getColor(ghostInkRes); }

    /** Label colour on the white 完成 face. */
    public int buttonInk(Context c, boolean enabled) {
        return c.getColor(enabled ? buttonInkRes : buttonInkDisabledRes);
    }
}
