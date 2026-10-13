package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
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
    /** Minimum gap between the plate's accent stripe and the status cluster (dp). */
    private static final float STATUS_GAP_DP = 8f;
    /** Right margin of the status cluster (dp). Mirrors the layout XML. */
    private static final float STATUS_MARGIN_DP = 12f;
    /** Gap above the tag/location row and above the cue line (dp). Mirrors the layout XML. */
    private static final float ROW_GAP_DP = 4f;
    /** Gap above the hint and side lines (dp). Mirrors the layout XML. */
    private static final float EXTRA_GAP_DP = 3f;
    /** Room a location keeps beside a long tag chip (dp): pin, its padding and a few characters. */
    static final float MIN_LOCATION_DP = 64f;
    /** Horizontal padding plus right margin of the tag chip (dp). Mirrors the layout XML. */
    private static final float TAG_PADDING_DP = 16f;
    private static final float TAG_MARGIN_DP = 8f;
    /** Gap between the kind mark and the title (dp). Mirrors the layout XML. */
    private static final float TITLE_ICON_GAP_DP = 4f;

    private CardRenderer() {}

    /**
     * Fixed vertical chrome of a card in dp (top margin, plate, body padding, action row, bottom
     * padding), i.e. everything except the title/dialogue/meta/cue stack. Mirrors the layout XML.
     */
    public static float chromeHeightDp(WidgetSize size) {
        return size == WidgetSize.REGULAR ? 8 + 40 + 6 + 6 + 40 + 8 : 6 + 40 + 3 + 3 + 40 + 6;
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
        return size == WidgetSize.REGULAR ? 6 + 6 : 3 + 3;
    }

    /** Text size (sp) of the tag and location in a bucket. Mirrors the layout XML. */
    static float metaTextSp(WidgetSize size) {
        return size == WidgetSize.REGULAR ? 12f : 11f;
    }

    /** Text size (sp) of the cue, hint and side lines in a bucket. Mirrors the layout XML. */
    static float cueTextSp(WidgetSize size) {
        return size == WidgetSize.REGULAR ? 11f : 10.5f;
    }

    /** Size (dp) of the kind mark left of the title. Mirrors the layout XML. */
    static float titleIconDp(WidgetSize size) {
        switch (size) {
            case REGULAR: return 16;
            case COMPACT: return 15;
            default: return 14;
        }
    }

    /** Largest title size (sp) the layout's autosize may pick. Mirrors the layout XML. */
    static float maxTitleSp(WidgetSize size) {
        switch (size) {
            case REGULAR: return 19f;
            case COMPACT: return 17f;
            default: return 15f;
        }
    }

    static boolean hasTitleIcon(CardModel model) {
        return model.titleIcon != null && model.titleIcon != CardModel.TitleIcon.NONE;
    }

    /** Width (dp) the title wraps to: the body width minus the kind mark, if one is shown. */
    static float titleWidthDp(CardModel model, WidgetSize size, float innerWidthDp) {
        if (!hasTitleIcon(model)) return innerWidthDp;
        return innerWidthDp - titleIconDp(size) - TITLE_ICON_GAP_DP;
    }

    /** Height (dp) of the first {@code lines} lines of the bold title at {@code sp} wrapped to {@code widthDp}. */
    static float titleHeightDp(Context c, String title, float sp, float widthDp, int lines) {
        if (isBlank(title)) return 0;
        StaticLayout layout = layout(c, title, sp, Typeface.DEFAULT_BOLD, widthDp, Integer.MAX_VALUE, 0f);
        int last = Math.max(0, Math.min(lines, layout.getLineCount()) - 1);
        return layout.getLineBottom(last) / c.getResources().getDisplayMetrics().density;
    }

    /**
     * Lines the title will take in a box {@code boxDp} high, following the layout's autosize: the
     * largest size from {@link #maxTitleSp} down to {@link #MIN_TITLE_SP} whose text fits the box
     * within {@code maxLines} lines.
     */
    static int titleLines(Context c, String title, WidgetSize size, float widthDp, float boxDp, int maxLines) {
        if (isBlank(title)) return 1;
        int lines = 1;
        for (float sp = maxTitleSp(size); sp >= MIN_TITLE_SP - 0.01f; sp -= 1f) {
            StaticLayout layout = layout(c, title, sp, Typeface.DEFAULT_BOLD, widthDp, Integer.MAX_VALUE, 0f);
            lines = layout.getLineCount();
            float height = layout.getHeight() / c.getResources().getDisplayMetrics().density;
            if (lines <= maxLines && height <= boxDp) return lines;
        }
        return Math.min(lines, maxLines);
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
        return textHeightDp(c, text, sp, bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT, widthDp, maxLines, lineSpacingExtraDp);
    }

    private static float textHeightDp(Context c, String text, float sp, Typeface typeface, float widthDp, int maxLines,
                                      float lineSpacingExtraDp) {
        if (isBlank(text)) return 0;
        return layout(c, text, sp, typeface, widthDp, maxLines, lineSpacingExtraDp).getHeight()
                / c.getResources().getDisplayMetrics().density;
    }

    private static StaticLayout layout(Context c, String text, float sp, Typeface typeface, float widthDp, int maxLines,
                                       float lineSpacingExtraDp) {
        DisplayMetrics metrics = c.getResources().getDisplayMetrics();
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics));
        paint.setTypeface(typeface);
        int width = Math.max(1, Math.round(widthDp * metrics.density));
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setIncludePad(false)
                .setLineSpacing(lineSpacingExtraDp * metrics.density, 1f)
                .setMaxLines(Math.max(1, maxLines));
        if (maxLines == 1) {
            // Single-line rows (tag, location, cue, hint, side) ellipsize like their TextViews.
            builder.setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(width);
        }
        boolean fitsOneLine = paint.measureText(text) <= width && text.indexOf('\n') < 0;
        if (Build.VERSION.SDK_INT >= 28 && !fitsOneLine) {
            // Like TextView for apps targeting P+: wrapped text (StaticLayout) gets the taller CJK
            // fallback line spacing, single-line text (BoringLayout) keeps the primary font's.
            builder.setUseLineSpacingFromFallbacks(true);
        }
        return builder.build();
    }

    /** Height in dp of the tag/location row of {@code model}, including its top gap (0 if hidden). */
    public static float metaRowHeightDp(Context c, CardModel model, WidgetSize size) {
        return metaRowHeightDp(c, model, size, 999);
    }

    /**
     * As {@link #metaRowHeightDp(Context, CardModel, WidgetSize)} for a body {@code innerWidthDp}
     * wide: text that has to be cut short is laid out like TextView does it (with the taller CJK
     * fallback line height), so its height is measured at the width it really gets.
     */
    static float metaRowHeightDp(Context c, CardModel model, WidgetSize size, float innerWidthDp) {
        float sp = metaTextSp(size);
        float chipText = 999;
        float chipWidth = 0;
        if (!isBlank(model.tag)) {
            float fontScale = c.getResources().getConfiguration().fontScale;
            chipWidth = lineWidthDp(c, model.tag, sp * fontScale, numerals(c, Typeface.BOLD)) + TAG_PADDING_DP;
            if (model.tagMaxWidthDp > 0 && model.tagMaxWidthDp < chipWidth) {
                chipWidth = model.tagMaxWidthDp;
                chipText = chipWidth - TAG_PADDING_DP;
            }
            chipWidth += TAG_MARGIN_DP;
        }
        float tag = isBlank(model.tag) ? 0 : textHeightDp(c, model.tag, sp, numerals(c, Typeface.BOLD), chipText, 1, 0) + 3;
        float locationWidth = Math.max(1, innerWidthDp - chipWidth - 15); // pin + its padding
        float location = isBlank(model.location) ? 0 : textHeightDp(c, model.location, sp, false, locationWidth, 1);
        float row = Math.max(tag, location);
        float gap = size == WidgetSize.REGULAR ? ROW_GAP_DP + 1 : ROW_GAP_DP;
        return row == 0 ? 0 : row + gap;
    }

    /** Height in dp of the cue line including its top gap (0 if hidden). */
    public static float cueRowHeightDp(Context c, CardModel model, WidgetSize size) {
        return cueRowHeightDp(c, model, size, 999);
    }

    /** As {@link #cueRowHeightDp(Context, CardModel, WidgetSize)} for a body {@code widthDp} wide. */
    static float cueRowHeightDp(Context c, CardModel model, WidgetSize size, float widthDp) {
        return isBlank(model.cue) ? 0 : textHeightDp(c, model.cue, cueTextSp(size), false, widthDp, 1) + ROW_GAP_DP;
    }

    /** Height in dp of the reminder line (with its 12dp mark) including its top gap (0 if hidden). */
    static float hintRowHeightDp(Context c, CardModel model, WidgetSize size, float widthDp) {
        if (isBlank(model.hint)) return 0;
        float text = textHeightDp(c, model.hint, cueTextSp(size), false, Math.max(1, widthDp - 15), 1);
        return Math.max(12f, text) + EXTRA_GAP_DP;
    }

    /** Height in dp of the side-step line ("顺便：…") including its top gap (0 if hidden). */
    static float sideRowHeightDp(Context c, CardModel model, WidgetSize size, float widthDp) {
        return isBlank(model.side) ? 0 : textHeightDp(c, model.side, cueTextSp(size), false, widthDp, 1) + EXTRA_GAP_DP;
    }

    /**
     * Widest the tag chip may be (dp, padding included) so a location beside it keeps
     * {@link #MIN_LOCATION_DP} (or what it needs, if less); 0 when the chip fits as it is.
     */
    static float tagMaxWidthDp(Context c, CardModel model, WidgetSize size, float innerWidthDp) {
        if (isBlank(model.tag)) return 0;
        float fontScale = c.getResources().getConfiguration().fontScale;
        float sizeDp = metaTextSp(size) * fontScale;
        float chip = lineWidthDp(c, model.tag, sizeDp, numerals(c, Typeface.BOLD)) + TAG_PADDING_DP;
        float room = innerWidthDp - TAG_MARGIN_DP;
        if (!isBlank(model.location)) {
            float location = lineWidthDp(c, model.location, sizeDp, Typeface.DEFAULT) + 15; // pin + padding
            room -= Math.min(location, MIN_LOCATION_DP);
        }
        return chip <= room ? 0 : Math.max(room, 40f);
    }

    /**
     * Renders {@code model} for a host size in dp: picks the size bucket and, on short cards, drops
     * optional rows so the title keeps its lines (see {@link #fit}).
     */
    public static RemoteViews render(Context context, CardModel model, float widthDp, float heightDp) {
        WidgetSize size = WidgetSize.of(widthDp, heightDp);
        CardModel fitted = fitHeader(context, fit(context, model, size, widthDp, heightDp), size, widthDp);
        return render(context, fitted, size);
    }

    /** Plate numeral size in dp per bucket. Mirrors the layout XML. */
    static float dateTextDp(WidgetSize size) {
        switch (size) {
            case REGULAR: return 27f;
            case COMPACT: return 24f;
            default: return 22f;
        }
    }

    /** The bundled numeral face (plate date, tag chip) in {@code style}; bold system face if unavailable. */
    static Typeface numerals(Context c, int style) {
        try {
            return Typeface.create(c.getResources().getFont(R.font.p3r_numerals), style);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT_BOLD;
        }
    }

    /** Width in dp of a single line of {@code text} at {@code sizeDp} (not scaled by font size). */
    static float lineWidthDp(Context c, String text, float sizeDp, Typeface typeface) {
        if (isBlank(text)) return 0;
        float density = c.getResources().getDisplayMetrics().density;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(sizeDp * density);
        paint.setTypeface(typeface);
        return paint.measureText(text) / density;
    }

    /** Estimated width in dp of the calendar plate for {@code model} (arrows, numerals, meta). */
    static float plateWidthDp(Context c, CardModel model, WidgetSize size) {
        float date = lineWidthDp(c, model.dateText, dateTextDp(size), numerals(c, Typeface.BOLD_ITALIC));
        float chip = isBlank(model.weekdayZh) ? 0 : lineWidthDp(c, model.weekdayZh, 10f, Typeface.DEFAULT_BOLD) + 14;
        boolean moon = size != WidgetSize.TINY && model.moonPhase >= 0;
        float period = lineWidthDp(c, model.periodZh, 10.5f, Typeface.DEFAULT_BOLD) + (moon ? 13 : 0);
        return 40 + date + 7 + Math.max(chip, period) + 40 + 10;
    }

    /** Width in dp of the progress label plus its ticks (0 when there is nothing to show). */
    static float statusWidthDp(Context c, CardModel model) {
        float fontScale = c.getResources().getConfiguration().fontScale;
        float label = isBlank(model.progressLabel) ? 0
                : lineWidthDp(c, model.progressLabel, 10f * fontScale, Typeface.DEFAULT_BOLD) + 4;
        float ticks = 0;
        for (CardModel.Tick tick : model.ticks) ticks += tick == CardModel.Tick.CURRENT ? 18 : 12;
        return label + ticks;
    }

    /**
     * Keeps the status cluster clear of the plate: drops the ticks when label and ticks do not fit
     * beside it, and the whole cluster when even the label does not.
     */
    static CardModel fitHeader(Context c, CardModel model, WidgetSize size, float widthDp) {
        if (size == WidgetSize.TINY || (isBlank(model.progressLabel) && model.ticks.isEmpty())) return model;
        float room = widthDp - plateWidthDp(c, model, size) - STATUS_GAP_DP - STATUS_MARGIN_DP;
        if (statusWidthDp(c, model) <= room) return model;
        CardModel fitted = model.copy();
        fitted.ticks.clear();
        if (statusWidthDp(c, fitted) > room) fitted.progressLabel = null;
        return fitted;
    }

    /** Title size (sp) worth keeping by giving up the optional cue line. */
    static float comfortableTitleSp(WidgetSize size) {
        switch (size) {
            case REGULAR: return 16f;
            case COMPACT: return 15f;
            default: return 14f;
        }
    }

    /**
     * Gives the title room before optional rows. The cue line goes first (unless
     * {@link CardModel#cueEssential}) when the title would otherwise drop below a comfortable size.
     * When the title would not fit even at its smallest size, the reminder line goes, then the
     * side-step line, then the tag/location row, then the cue. Also caps the tag chip so a location
     * beside it keeps some room. Returns {@code model} itself when nothing changes.
     */
    static CardModel fit(Context c, CardModel model, WidgetSize size, float widthDp, float heightDp) {
        float body = heightDp - chromeHeightDp(size) + (model.tightBody ? bodyVerticalPaddingDp(size) : 0);
        float innerWidth = widthDp - 2 * bodyPaddingDp(size);
        float titleWidth = titleWidthDp(model, size, innerWidth);
        int lines = Math.max(1, model.titleMaxLines);
        float comfortable = textHeightDp(c, model.title, comfortableTitleSp(size), true, titleWidth, lines);
        float smallest = textHeightDp(c, model.title, MIN_TITLE_SP, true, titleWidth, lines);
        float dialogue = isBlank(model.dialogue) ? 0 : 3 + textHeightDp(c, model.dialogue, 12, false, innerWidth, 20, 1);
        CardModel fitted = model;
        float tagMax = tagMaxWidthDp(c, model, size, innerWidth);
        if (tagMax != model.tagMaxWidthDp) {
            fitted = model.copy();
            fitted.tagMaxWidthDp = tagMax;
        }
        float meta = metaRowHeightDp(c, fitted, size, innerWidth);
        float hint = hintRowHeightDp(c, fitted, size, innerWidth);
        float side = sideRowHeightDp(c, fitted, size, innerWidth);
        float cue = cueRowHeightDp(c, fitted, size, innerWidth);
        if (cue > 0 && !model.cueEssential && comfortable + dialogue + meta + hint + side + cue > body) {
            if (fitted == model) fitted = fitted.copy();
            fitted.cue = null;
            cue = 0;
        }
        if (hint > 0 && smallest + dialogue + meta + hint + side + cue > body) {
            if (fitted == model) fitted = fitted.copy();
            fitted.hint = null;
            hint = 0;
        }
        if (side > 0 && smallest + dialogue + meta + side + cue > body) {
            if (fitted == model) fitted = fitted.copy();
            fitted.side = null;
            side = 0;
        }
        if (meta > 0 && smallest + dialogue + meta + cue > body) {
            if (fitted == model) fitted = fitted.copy();
            fitted.tag = null;
            fitted.location = null;
            meta = 0;
        }
        if (cue > 0 && smallest + dialogue + cue > body) {
            if (fitted == model) fitted = fitted.copy();
            fitted.cue = null;
            cue = 0;
        }
        // On the shortest cards even the smallest title may not fit all its lines: keep the lines that
        // fit and let the last one end in "…" rather than be cut in half.
        float box = body - dialogue - meta - hint - side - cue;
        int fitLines = lines;
        while (fitLines > 1 && titleHeightDp(c, fitted.title, MIN_TITLE_SP, titleWidth, fitLines) > box) fitLines--;
        if (fitLines != fitted.titleMaxLines) {
            if (fitted == model) fitted = fitted.copy();
            fitted.titleMaxLines = fitLines;
        }
        // A kind mark sits on the title's first line: a title that will wrap hangs from the top.
        boolean top = hasTitleIcon(fitted) && titleLines(c, fitted.title, size, titleWidth, box, fitLines) > 1;
        if (top != fitted.titleTop) {
            if (fitted == model) fitted = fitted.copy();
            fitted.titleTop = top;
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
        applyActions(context, views, model, palette, size);
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
        views.setInt(R.id.widget_watermark_tens, "setImageAlpha", palette.watermarkImageAlpha);
        views.setInt(R.id.widget_watermark_units, "setImageAlpha", palette.watermarkImageAlpha);
        if (day >= 10) {
            views.setImageViewResource(R.id.widget_watermark_tens, DIGITS[day / 10]);
            views.setImageViewResource(R.id.widget_watermark_units, DIGITS[day % 10]);
            views.setViewVisibility(R.id.widget_watermark_units, View.VISIBLE);
        } else {
            // A lone digit sits one numeral in from the edge (the units slot keeps its width), so a
            // narrow "1" reads as a numeral rather than a sliver on the border.
            views.setImageViewResource(R.id.widget_watermark_tens, DIGITS[day]);
            views.setImageViewResource(R.id.widget_watermark_units, DIGITS[0]);
            views.setViewVisibility(R.id.widget_watermark_units, View.INVISIBLE);
        }
        views.setViewVisibility(R.id.widget_watermark_tens, View.VISIBLE);
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
        int titleIcon = titleIconDrawable(model.titleIcon);
        views.setViewVisibility(R.id.widget_title_icon, titleIcon == 0 ? View.GONE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 31) {
            // TextView#setGravity is remotable from API 31; older launchers keep the mark on the last line.
            int vertical = model.titleTop ? Gravity.TOP : Gravity.BOTTOM;
            views.setInt(R.id.widget_title_row, "setGravity", vertical);
            views.setInt(R.id.widget_task, "setGravity", Gravity.LEFT | vertical);
        }
        if (titleIcon != 0) {
            views.setImageViewResource(R.id.widget_title_icon, titleIcon);
            views.setInt(R.id.widget_title_icon, "setColorFilter", ink);
        }

        setTextOrHide(views, R.id.widget_dialogue_choices, model.dialogue);
        views.setTextColor(R.id.widget_dialogue_choices, soft);

        setTextOrHide(views, R.id.widget_tag, model.tag);
        views.setInt(R.id.widget_tag, "setBackgroundResource", palette.tag);
        views.setTextColor(R.id.widget_tag, palette.tagInk(c));
        int tagMax = model.tagMaxWidthDp > 0
                ? Math.round(model.tagMaxWidthDp * c.getResources().getDisplayMetrics().density) : Integer.MAX_VALUE;
        views.setInt(R.id.widget_tag, "setMaxWidth", tagMax);

        setTextOrHide(views, R.id.widget_location, model.location);
        views.setTextColor(R.id.widget_location, soft);
        views.setTextViewCompoundDrawables(R.id.widget_location, palette.pin, 0, 0, 0);
        boolean meta = !isBlank(model.tag) || !isBlank(model.location);
        views.setViewVisibility(R.id.widget_meta_row, meta ? View.VISIBLE : View.GONE);

        setTextOrHide(views, R.id.widget_hint, model.hint);
        views.setTextColor(R.id.widget_hint, ink);
        views.setTextViewCompoundDrawables(R.id.widget_hint, palette.hintIcon, 0, 0, 0);

        setTextOrHide(views, R.id.widget_side, model.side);
        views.setTextColor(R.id.widget_side, ink);

        setTextOrHide(views, R.id.widget_detail_cue, model.cue);
        views.setTextColor(R.id.widget_detail_cue, soft);
    }

    /** Kind mark drawable (tinted with the ink at runtime), or 0 for none. */
    static int titleIconDrawable(CardModel.TitleIcon icon) {
        if (icon == null) return 0;
        switch (icon) {
            case NOTE: return R.drawable.widget_icon_note;
            case EPISODE: return R.drawable.widget_icon_episode;
            default: return 0;
        }
    }

    private static void applyActions(Context c, RemoteViews views, CardModel model, WidgetPalette palette, WidgetSize size) {
        // A 当前 that does nothing (status cards) reads dimmed like its outline, not just its glyph.
        int ghost = palette.ghostInk(c, model.currentEnabled);
        if (size == WidgetSize.TINY) {
            // The narrow 当前 button keeps its label centred and drops the return glyph.
            int side = Math.round(8 * c.getResources().getDisplayMetrics().density);
            int top = Math.round(3 * c.getResources().getDisplayMetrics().density);
            views.setViewVisibility(R.id.button_spec_return_icon, View.GONE);
            views.setViewPadding(R.id.button_spec_current_text, side, top, side, top);
        }
        views.setViewVisibility(R.id.button_spec_current, model.currentVisible ? View.VISIBLE : View.GONE);
        views.setInt(R.id.button_spec_current, "setBackgroundResource", palette.currentButton);
        views.setBoolean(R.id.button_spec_current, "setEnabled", model.currentEnabled);
        views.setTextViewText(R.id.button_spec_current_text, model.currentLabel);
        views.setTextColor(R.id.button_spec_current_text, ghost);
        views.setInt(R.id.button_spec_return_icon, "setColorFilter", palette.ghostInk(c));
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
        if (!icon) {
            // Without the trailing glyph the label centres on the whole face.
            int side = Math.round(10 * c.getResources().getDisplayMetrics().density);
            views.setViewPadding(R.id.button_spec_done_text, side, 0, side, 0);
        } else {
            views.setImageViewResource(R.id.button_spec_check,
                    model.doneIcon == CardModel.DoneIcon.UNDO ? R.drawable.widget_icon_undo : R.drawable.widget_icon_check);
            // The glyph matches the label: a translucent tint would blend with the glyph's own navy,
            // so tint with the opaque ink and carry its alpha as image alpha.
            views.setInt(R.id.button_spec_check, "setColorFilter", doneInk | 0xFF000000);
            views.setInt(R.id.button_spec_check, "setImageAlpha", doneInk >>> 24);
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
