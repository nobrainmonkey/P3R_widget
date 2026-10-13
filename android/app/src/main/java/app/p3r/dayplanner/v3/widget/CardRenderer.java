package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import app.p3r.dayplanner.R;

/**
 * Turns a {@link CardModel} into the RemoteViews of one card (no click handling). Every call sets
 * every view it touches, so a card never shows the XML sample text or a stale palette.
 *
 * <p>Only RemoteViews-safe setters are used (API 26+): text, text colour, image resource, image
 * alpha, colour filter, background resource, compound drawables, visibility and enabled state.
 */
public final class CardRenderer {

    static final int[] TICK_IDS = {
            R.id.widget_tick_0, R.id.widget_tick_1, R.id.widget_tick_2, R.id.widget_tick_3,
            R.id.widget_tick_4, R.id.widget_tick_5, R.id.widget_tick_6, R.id.widget_tick_7};
    static final int[] DIGITS = {
            R.drawable.widget_digit_0, R.drawable.widget_digit_1, R.drawable.widget_digit_2,
            R.drawable.widget_digit_3, R.drawable.widget_digit_4, R.drawable.widget_digit_5,
            R.drawable.widget_digit_6, R.drawable.widget_digit_7, R.drawable.widget_digit_8,
            R.drawable.widget_digit_9};
    static final int[] MOONS = {
            R.drawable.widget_moon_0, R.drawable.widget_moon_1, R.drawable.widget_moon_2,
            R.drawable.widget_moon_3, R.drawable.widget_moon_4, R.drawable.widget_moon_5,
            R.drawable.widget_moon_6, R.drawable.widget_moon_7};
    static final int[] SHAFTS = {
            R.drawable.widget_shafts_0, R.drawable.widget_shafts_1, R.drawable.widget_shafts_2,
            R.drawable.widget_shafts_3};
    /** Image alpha of an arrow that cannot move further. */
    static final int DISABLED_ARROW_ALPHA = 80;

    /** Ticks available in the layout; days with more entries show only the text progress. */
    public static final int MAX_TICKS = 8;
    /** Smallest title size the layouts' autosize may pick (sp). */
    public static final float MIN_TITLE_SP = 12f;
    /** Gap above the tag/location row and above the cue line (dp). Mirrors the layout XML. */
    private static final float ROW_GAP_DP = 4f;

    private CardRenderer() {}

    /**
     * Fixed vertical chrome of a card in dp (top margin, plate, body padding, action row, bottom
     * padding), i.e. everything except the title/dialogue/meta/cue stack. Mirrors the layout XML.
     */
    public static float chromeHeightDp(WidgetSize size) {
        return size == WidgetSize.REGULAR ? 8 + 40 + 6 + 6 + 40 + 8 : 6 + 40 + 4 + 5 + 40 + 6;
    }

    /** Horizontal padding of the body text in dp. Mirrors the layout XML. */
    public static float bodyPaddingDp(WidgetSize size) {
        switch (size) {
            case REGULAR: return 14;
            case COMPACT: return 12;
            default: return 10;
        }
    }

    /** Top plus bottom padding of the body in dp. Mirrors the layout XML. */
    public static float bodyVerticalPaddingDp(WidgetSize size) {
        return size == WidgetSize.REGULAR ? 6 + 6 : 4 + 5;
    }

    /** Text size (sp) of the tag and location in a bucket. Mirrors the layout XML. */
    static float metaTextSp(WidgetSize size) {
        return size == WidgetSize.REGULAR ? 12f : 11f;
    }

    /** Text size (sp) of the cue line in a bucket. Mirrors the layout XML. */
    static float cueTextSp(WidgetSize size) {
        return size == WidgetSize.REGULAR ? 11f : 10.5f;
    }

    /**
     * Height in dp that {@code text} needs at {@code sp} (current font scale) when wrapped to
     * {@code widthDp}, limited to {@code maxLines}. Measured with the platform's text layout.
     */
    public static float textHeightDp(Context c, String text, float sp, boolean bold, float widthDp, int maxLines) {
        return textHeightDp(c, text, sp, bold, widthDp, maxLines, 0f);
    }

    /** As {@link #textHeightDp(Context, String, float, boolean, float, int)} with extra line spacing. */
    public static float textHeightDp(Context c, String text, float sp, boolean bold, float widthDp, int maxLines,
                                     float lineSpacingExtraDp) {
        if (isBlank(text)) return 0;
        DisplayMetrics metrics = c.getResources().getDisplayMetrics();
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        int width = Math.max(1, Math.round(widthDp * metrics.density));
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setIncludePad(false)
                .setLineSpacing(lineSpacingExtraDp * metrics.density, 1f)
                .setMaxLines(Math.max(1, maxLines));
        if (Build.VERSION.SDK_INT >= 28) {
            // TextView does the same for apps targeting P+: CJK fallback fonts make lines taller.
            builder.setUseLineSpacingFromFallbacks(true);
        }
        return builder.build().getHeight() / metrics.density;
    }

    /** Height in dp of the tag/location row of {@code model}, including its top gap (0 if hidden). */
    public static float metaRowHeightDp(Context c, CardModel model, WidgetSize size) {
        float sp = metaTextSp(size);
        float tag = isBlank(model.tag) ? 0 : textHeightDp(c, model.tag, sp, true, 999, 1) + 4;
        float location = isBlank(model.location) ? 0 : textHeightDp(c, model.location, sp, false, 999, 1);
        float row = Math.max(tag, location);
        return row == 0 ? 0 : row + ROW_GAP_DP + (size == WidgetSize.REGULAR ? 1 : 0);
    }

    /** Height in dp of the cue line including its top gap (0 if hidden). */
    public static float cueRowHeightDp(Context c, CardModel model, WidgetSize size) {
        return isBlank(model.cue) ? 0 : textHeightDp(c, model.cue, cueTextSp(size), false, 999, 1) + ROW_GAP_DP;
    }

    /**
     * Renders {@code model} for a host size in dp: picks the size bucket and, on short cards, drops
     * optional rows so the title keeps its lines (see {@link #fit}).
     */
    public static RemoteViews render(Context context, CardModel model, float widthDp, float heightDp) {
        WidgetSize size = WidgetSize.of(widthDp, heightDp);
        return render(context, fit(context, model, size, widthDp, heightDp), size);
    }

    /**
     * Drops optional rows that would squeeze the title below its smallest size: the cue line first
     * (unless {@link CardModel#cueEssential}), then the tag/location row. Returns {@code model}
     * itself when everything fits.
     */
    static CardModel fit(Context c, CardModel model, WidgetSize size, float widthDp, float heightDp) {
        float body = heightDp - chromeHeightDp(size) + (model.tightBody ? bodyVerticalPaddingDp(size) : 0);
        float innerWidth = widthDp - 2 * bodyPaddingDp(size);
        float title = textHeightDp(c, model.title, MIN_TITLE_SP, true, innerWidth, Math.max(1, model.titleMaxLines));
        float dialogue = isBlank(model.dialogue) ? 0 : 3 + textHeightDp(c, model.dialogue, 12, false, innerWidth, 20, 1);
        float meta = metaRowHeightDp(c, model, size);
        float cue = cueRowHeightDp(c, model, size);
        CardModel fitted = model;
        if (title + dialogue + meta + cue <= body) return fitted;
        if (cue > 0 && !model.cueEssential) {
            fitted = fitted.copy();
            fitted.cue = null;
            cue = 0;
            if (title + dialogue + meta <= body) return fitted;
        }
        if (meta > 0) {
            if (fitted == model) fitted = fitted.copy();
            fitted.tag = null;
            fitted.location = null;
            meta = 0;
        }
        if (cue > 0 && title + dialogue + cue > body) {
            if (fitted == model) fitted = fitted.copy();
            fitted.cue = null;
        }
        return fitted;
    }

    /** Renders {@code model} into the layout of {@code size}. */
    public static RemoteViews render(Context context, CardModel model, WidgetSize size) {
        RemoteViews views = new RemoteViews(context.getPackageName(), size.layout);
        WidgetPalette palette = model.palette();
        applyBackground(views, model, palette);
        applyPlate(context, views, model, palette, size);
        applyStatus(context, views, model, palette, size);
        applyBody(context, views, model, palette, size);
        applyActions(context, views, model, palette);
        return views;
    }

    private static void applyBackground(RemoteViews views, CardModel model, WidgetPalette palette) {
        views.setInt(R.id.widget_root, "setBackgroundResource", palette.surface);
        views.setImageViewResource(R.id.widget_shafts, SHAFTS[Math.floorMod(model.motif, SHAFTS.length)]);
        views.setInt(R.id.widget_shafts, "setImageAlpha", palette.shaftImageAlpha);
        int day = model.watermarkDay;
        if (day < 1 || day > 31) {
            views.setViewVisibility(R.id.widget_watermark, View.GONE);
            return;
        }
        views.setViewVisibility(R.id.widget_watermark, View.VISIBLE);
        views.setViewVisibility(R.id.widget_watermark_tens, day >= 10 ? View.VISIBLE : View.GONE);
        if (day >= 10) {
            views.setImageViewResource(R.id.widget_watermark_tens, DIGITS[day / 10]);
            views.setInt(R.id.widget_watermark_tens, "setImageAlpha", palette.watermarkImageAlpha);
        }
        views.setImageViewResource(R.id.widget_watermark_units, DIGITS[day % 10]);
        views.setInt(R.id.widget_watermark_units, "setImageAlpha", palette.watermarkImageAlpha);
    }

    private static void applyPlate(Context c, RemoteViews views, CardModel model, WidgetPalette palette, WidgetSize size) {
        int plateInk = palette.plateInk(c);
        views.setInt(R.id.widget_plate, "setBackgroundResource", palette.plate);
        views.setTextViewText(R.id.widget_date, model.dateText);
        views.setTextColor(R.id.widget_date, plateInk);
        views.setContentDescription(R.id.widget_date, model.dateDescription);

        setTextOrHide(views, R.id.widget_weekday, model.weekdayZh);
        views.setInt(R.id.widget_weekday, "setBackgroundResource", palette.weekdayChip);
        views.setTextColor(R.id.widget_weekday, palette.chipInk(c));

        // The narrow plate has no room for the (decorative) moon next to 放学后.
        boolean moon = size != WidgetSize.TINY && model.moonPhase >= 0 && model.moonPhase < MOONS.length;
        views.setViewVisibility(R.id.widget_period_icon, moon ? View.VISIBLE : View.GONE);
        if (moon) {
            views.setImageViewResource(R.id.widget_period_icon, MOONS[model.moonPhase]);
            views.setInt(R.id.widget_period_icon, "setColorFilter", plateInk);
        }
        setTextOrHide(views, R.id.widget_period_word, model.periodZh);
        views.setTextColor(R.id.widget_period_word, plateInk);

        applyArrow(views, R.id.widget_prev, model.arrowsVisible, model.prevEnabled, plateInk);
        applyArrow(views, R.id.widget_next, model.arrowsVisible, model.nextEnabled, plateInk);
    }

    private static void applyArrow(RemoteViews views, int id, boolean visible, boolean enabled, int ink) {
        views.setViewVisibility(id, visible ? View.VISIBLE : View.INVISIBLE);
        views.setBoolean(id, "setEnabled", visible && enabled);
        views.setInt(id, "setColorFilter", ink);
        views.setInt(id, "setImageAlpha", enabled ? 255 : DISABLED_ARROW_ALPHA);
    }

    private static void applyStatus(Context c, RemoteViews views, CardModel model, WidgetPalette palette, WidgetSize size) {
        boolean anything = model.progressLabel != null || !model.ticks.isEmpty();
        boolean shown = size != WidgetSize.TINY && anything;
        views.setViewVisibility(R.id.widget_status, shown || size == WidgetSize.REGULAR ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_progress_row, shown ? View.VISIBLE : View.GONE);
        if (size == WidgetSize.REGULAR) {
            views.setImageViewResource(R.id.widget_sees_mark, palette.wordmark);
        }
        setTextOrHide(views, R.id.widget_progress, model.progressLabel);
        int ink = palette.ink(c);
        int accent = palette.accent(c);
        views.setTextColor(R.id.widget_progress, ink);
        views.setContentDescription(R.id.widget_progress_row, model.progressDescription);
        for (int i = 0; i < TICK_IDS.length; i++) {
            if (i >= model.ticks.size()) {
                views.setViewVisibility(TICK_IDS[i], View.GONE);
                continue;
            }
            CardModel.Tick tick = model.ticks.get(i);
            views.setViewVisibility(TICK_IDS[i], View.VISIBLE);
            views.setImageViewResource(TICK_IDS[i], tickDrawable(tick));
            views.setInt(TICK_IDS[i], "setColorFilter", tick == CardModel.Tick.CURRENT ? accent : ink);
        }
    }

    static int tickDrawable(CardModel.Tick tick) {
        switch (tick) {
            case DONE: return R.drawable.widget_tick_done;
            case CURRENT: return R.drawable.widget_tick_current;
            case LOCKED: return R.drawable.widget_tick_locked;
            default: return R.drawable.widget_tick_pending;
        }
    }

    private static void applyBody(Context c, RemoteViews views, CardModel model, WidgetPalette palette, WidgetSize size) {
        if (model.tightBody) {
            int side = Math.round(bodyPaddingDp(size) * c.getResources().getDisplayMetrics().density);
            views.setViewPadding(R.id.widget_task_area, side, 0, side, 0);
        }
        int ink = palette.ink(c);
        int soft = palette.inkSoft(c);
        views.setTextViewText(R.id.widget_task, model.title);
        views.setTextColor(R.id.widget_task, ink);
        views.setInt(R.id.widget_task, "setMaxLines", Math.max(1, model.titleMaxLines));
        views.setContentDescription(R.id.widget_task_area, model.taskDescription);

        setTextOrHide(views, R.id.widget_dialogue_choices, model.dialogue);
        views.setTextColor(R.id.widget_dialogue_choices, soft);

        setTextOrHide(views, R.id.widget_tag, model.tag);
        views.setInt(R.id.widget_tag, "setBackgroundResource", palette.tag);
        views.setTextColor(R.id.widget_tag, palette.tagInk(c));

        setTextOrHide(views, R.id.widget_location, model.location);
        views.setTextColor(R.id.widget_location, soft);
        views.setTextViewCompoundDrawables(R.id.widget_location, palette.pin, 0, 0, 0);
        boolean meta = !isBlank(model.tag) || !isBlank(model.location);
        views.setViewVisibility(R.id.widget_meta_row, meta ? View.VISIBLE : View.GONE);

        setTextOrHide(views, R.id.widget_detail_cue, model.cue);
        views.setTextColor(R.id.widget_detail_cue, soft);
    }

    private static void applyActions(Context c, RemoteViews views, CardModel model, WidgetPalette palette) {
        int ghost = palette.ghostInk(c);
        views.setViewVisibility(R.id.button_spec_current, model.currentVisible ? View.VISIBLE : View.GONE);
        views.setInt(R.id.button_spec_current, "setBackgroundResource", palette.currentButton);
        views.setBoolean(R.id.button_spec_current, "setEnabled", model.currentEnabled);
        views.setTextViewText(R.id.button_spec_current_text, model.currentLabel);
        views.setTextColor(R.id.button_spec_current_text, ghost);
        views.setInt(R.id.button_spec_return_icon, "setColorFilter", ghost);
        views.setInt(R.id.button_spec_return_icon, "setImageAlpha", model.currentEnabled ? 255 : DISABLED_ARROW_ALPHA);
        views.setContentDescription(R.id.button_spec_current, model.currentDescription);

        int doneInk = palette.buttonInk(c, model.doneEnabled);
        views.setInt(R.id.button_spec_done, "setBackgroundResource", palette.doneButton);
        views.setBoolean(R.id.button_spec_done, "setEnabled", model.doneEnabled);
        views.setTextViewText(R.id.button_spec_done_text, model.doneLabel);
        views.setTextColor(R.id.button_spec_done_text, doneInk);
        views.setContentDescription(R.id.button_spec_done, model.doneDescription);
        boolean icon = model.doneIcon != CardModel.DoneIcon.NONE;
        views.setViewVisibility(R.id.button_spec_check, icon ? View.VISIBLE : View.GONE);
        if (icon) {
            views.setImageViewResource(R.id.button_spec_check,
                    model.doneIcon == CardModel.DoneIcon.UNDO ? R.drawable.widget_icon_undo : R.drawable.widget_icon_check);
            views.setInt(R.id.button_spec_check, "setColorFilter", doneInk);
        }
    }

    private static void setTextOrHide(RemoteViews views, int id, String text) {
        boolean visible = !isBlank(text);
        views.setViewVisibility(id, visible ? View.VISIBLE : View.GONE);
        views.setTextViewText(id, visible ? text : "");
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
