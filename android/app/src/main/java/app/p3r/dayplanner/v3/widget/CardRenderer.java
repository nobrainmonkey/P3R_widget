package app.p3r.dayplanner.v3.widget;

import android.annotation.SuppressLint;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.DialogueHint;

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
    /** Share of the palette's watermark alpha kept when text runs over the numeral. */
    static final float FAINT_WATERMARK = 0.4f;

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
    /** Narrowest location worth drawing (dp of text): three characters; below it the row drops it. */
    static final float MIN_LOCATION_DP = 33f;
    /** Pin (or the 人格面具 / 当日提醒 mark) in front of the location, hint and reminder rows plus its padding (dp). Mirrors the layout XML. */
    private static final float PIN_DP = 15f;
    /** Horizontal padding plus right margin of the tag chip (dp). Mirrors the layout XML. */
    private static final float TAG_PADDING_DP = 16f;
    private static final float TAG_MARGIN_DP = 8f;
    /** Smallest share of its size a chip may take to stay on one line before it wraps instead. */
    private static final float MIN_CHIP_SCALE = 0.85f;
    /**
     * Room (dp) a tall card's title box keeps beyond what the largest title size needs (measured as
     * the layout's autosize does it), so rounding never makes the autosize pick a smaller size.
     */
    private static final float TITLE_SLACK_DP = 2f;
    /** Share of a tall card's spare height that goes under the buttons, up to {@link #maxSpaceUnderButtonsDp}. */
    private static final float UNDER_BUTTONS_SHARE = 0.3f;
    /** Gap between the kind mark and the title (dp). Mirrors the layout XML. */
    private static final float TITLE_ICON_GAP_DP = 4f;
    /** Text size (sp), line spacing (dp) and top gap (dp) of the answers row. Mirrors the layout XML. */
    static final float DIALOGUE_SP = 12f;
    private static final float DIALOGUE_SPACING_DP = 1f;
    private static final float DIALOGUE_GAP_DP = 3f;
    /** The answers row's last text size step (sp, spec 3.1.0 §1.4.3 step 6). */
    static final float ANSWERS_SMALL_SP = 11f;
    /** Lines the answers row may take before the width ladder allows {@link #MORE_ANSWER_LINES}. */
    public static final int MAX_ANSWER_LINES = 2;
    public static final int MORE_ANSWER_LINES = 3;
    /** Alpha of a muted answers line (a meeting that may not rank up): about 4:1 on the day surface. */
    static final int MUTED_ANSWER_ALPHA = 0xD9;

    private CardRenderer() {}

    /**
     * Fixed vertical chrome of a card in dp (top margin, plate, body padding, action row, bottom
     * padding), i.e. everything except the title/dialogue/meta/cue stack. Mirrors the layout XML.
     */
    public static float chromeHeightDp(WidgetSize size) {
        return 2 * contentPaddingDp(size) + 40 + bodyVerticalPaddingDp(size) + 40;
    }

    /** Space above the plate and under the buttons in dp. Mirrors the layout XML. */
    static float contentPaddingDp(WidgetSize size) {
        return size == WidgetSize.REGULAR ? 8 : 6;
    }

    /** Most extra space (dp) a tall card adds under its buttons. */
    static float maxSpaceUnderButtonsDp(WidgetSize size) {
        return size == WidgetSize.REGULAR ? 6 : 4;
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
     * The title as the layout's autosize draws it in a box {@code boxDp} high: at the largest size
     * from {@link #maxTitleSp} down to {@link #MIN_TITLE_SP} whose text fits the box within
     * {@code maxLines} lines (at the smallest when none does), wrapped like the TextView wraps it.
     */
    static StaticLayout drawnTitle(Context c, String title, WidgetSize size, float widthDp, float boxDp, int maxLines) {
        float density = c.getResources().getDisplayMetrics().density;
        StaticLayout layout = null;
        for (float sp = maxTitleSp(size); sp >= MIN_TITLE_SP - 0.01f; sp -= 1f) {
            layout = autosizeLayout(c, title, sp, widthDp);
            if (layout.getLineCount() <= maxLines && layout.getHeight() / density <= boxDp) return layout;
        }
        return layout;
    }

    /**
     * {@code title} with every 「 · 」 at which the drawn title ({@link #drawnTitle}) wraps turned into a
     * line break, the dot dropped: 「结缘网站的字条」/「山岸风花」 rather than a line ending in 「·」. The
     * lines break where they did, so the title keeps its size and its lines.
     */
    static String wrapAtSeparators(Context c, String title, WidgetSize size, float widthDp, float boxDp, int maxLines) {
        if (isBlank(title) || title.indexOf('·') < 0) return title;
        StaticLayout layout = drawnTitle(c, title, size, widthDp, boxDp, maxLines);
        int lines = Math.min(layout.getLineCount(), Math.max(1, maxLines));
        int[] ends = new int[lines - 1];
        for (int i = 0; i < ends.length; i++) ends[i] = layout.getLineEnd(i);
        return CardText.breakAtSeparators(title, ends);
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
     * wide: a chip wrapped to two lines and text that has to be cut short are laid out like TextView
     * does it (with the taller CJK fallback line height), at the width they really get.
     */
    static float metaRowHeightDp(Context c, CardModel model, WidgetSize size, float innerWidthDp) {
        float sp = metaTextSp(size);
        float chip = chipWidthDp(c, model, size);
        boolean wraps = model.tagMaxLines > 1 && chip + TAG_MARGIN_DP > innerWidthDp;
        float chipText = wraps ? innerWidthDp - TAG_MARGIN_DP - TAG_PADDING_DP : 999;
        float tag = isBlank(model.tag) ? 0 : textHeightDp(c, model.tag, chipTextSp(model, size), numerals(c, Typeface.BOLD),
                chipText, Math.max(1, model.tagMaxLines), 0) + 3;
        float locationWidth = Math.max(1, innerWidthDp - (chip > 0 ? chip + TAG_MARGIN_DP : 0) - PIN_DP);
        float location = isBlank(model.location) ? 0 : textHeightDp(c, model.location, sp, false, locationWidth, 1);
        float row = Math.max(tag, location);
        float gap = size == WidgetSize.REGULAR ? ROW_GAP_DP + 1 : ROW_GAP_DP;
        return row == 0 ? 0 : row + gap;
    }

    /** Text size (sp) of the tag chip: the bucket's, or the smaller one {@link #fit} chose. */
    static float chipTextSp(CardModel model, WidgetSize size) {
        return model.tagTextSp > 0 ? model.tagTextSp : metaTextSp(size);
    }

    /** Width in dp of the tag chip on one line, padding included (0 without a tag). */
    static float chipWidthDp(Context c, CardModel model, WidgetSize size) {
        if (isBlank(model.tag)) return 0;
        float fontScale = c.getResources().getConfiguration().fontScale;
        return lineWidthDp(c, model.tag, chipTextSp(model, size) * fontScale, numerals(c, Typeface.BOLD)) + TAG_PADDING_DP;
    }

    /** Height in dp of the cue line including its top gap (0 if hidden). */
    public static float cueRowHeightDp(Context c, CardModel model, WidgetSize size) {
        return cueRowHeightDp(c, model, size, 999);
    }

    /** As {@link #cueRowHeightDp(Context, CardModel, WidgetSize)} for a body {@code widthDp} wide. */
    static float cueRowHeightDp(Context c, CardModel model, WidgetSize size, float widthDp) {
        if (isBlank(model.cue)) return 0;
        return textHeightDp(c, model.cue, cueTextSp(size), false, widthDp, Math.max(1, model.cueMaxLines)) + ROW_GAP_DP;
    }

    /** Height in dp of the 人格面具 reminder (with its 12dp mark) including its top gap (0 if hidden). */
    static float hintRowHeightDp(Context c, CardModel model, WidgetSize size, float widthDp) {
        if (isBlank(model.hint)) return 0;
        float text = textHeightDp(c, model.hint, cueTextSp(size), false, Math.max(1, widthDp - PIN_DP), 1);
        return Math.max(12f, text) + EXTRA_GAP_DP;
    }

    /** Height in dp of the side-step line ("顺便：…") including its top gap (0 if hidden). */
    static float sideRowHeightDp(Context c, CardModel model, WidgetSize size, float widthDp) {
        return isBlank(model.side) ? 0 : textHeightDp(c, model.side, cueTextSp(size), false, widthDp, 1) + EXTRA_GAP_DP;
    }

    /** Height in dp of the reminder row ({@code text}, with its 12dp mark) including its top gap (0 if none). */
    static float reminderRowHeightDp(Context c, String text, WidgetSize size, float widthDp) {
        if (isBlank(text)) return 0;
        float height = textHeightDp(c, text, cueTextSp(size), false, Math.max(1, widthDp - PIN_DP), 1);
        return Math.max(12f, height) + EXTRA_GAP_DP;
    }

    /**
     * The reminder row of {@code model} on a body {@code innerWidthDp} wide: its first 当日提醒 whole,
     * else in its short form, never cut with "…"; with more than one, 「 · 共 N 条」 follows (a separator
     * that cannot fuse with a line ending in a number or a bracket: 「学力 +2 · 共 2 条」, not
     * 「学力 +2（共 2 条）」; the short form alone is the last resort). Null when none fits one line.
     */
    static String fitReminder(Context c, CardModel model, WidgetSize size, float innerWidthDp) {
        if (model.reminders.isEmpty()) return null;
        CardModel.ReminderLine first = model.reminders.get(0);
        int count = model.reminders.size();
        String more = count > 1 ? " · 共 " + count + " 条" : "";
        List<String> forms = new ArrayList<>();
        if (!isBlank(first.text)) forms.add(first.text.trim() + more);
        if (!isBlank(first.shortText)) forms.add(first.shortText.trim() + more);
        if (count > 1 && !isBlank(first.shortText)) forms.add(first.shortText.trim());
        float sizeDp = layoutTextDp(c, cueTextSp(size));
        for (String form : forms) {
            if (PIN_DP + lineWidthDp(c, form, sizeDp, Typeface.DEFAULT) <= innerWidthDp - 1) return form; // 1dp for rounding
        }
        return null;
    }

    /** One way to draw the answers row: a rung of the width ladder (spec 3.1.0 §1.4.3). */
    static final class AnswerForm {
        /** Shaped text, one paragraph per answers line. */
        final String text;
        final float sp;
        /** Height including the row's top gap. */
        final float heightDp;
        /** Every line and every choice is there (no 「等 N 项」, no line left out). */
        final boolean whole;
        /** Lines drawn on a body as wide as the one it was made for. */
        final int lines;

        AnswerForm(String text, float sp, float heightDp, boolean whole, int lines) {
            this.text = text;
            this.sp = sp;
            this.heightDp = heightDp;
            this.whole = whole;
            this.lines = lines;
        }
    }

    /** {@link #answerForm}'s {@code shown} for a line kept whole. */
    private static final int ALL = Integer.MAX_VALUE;

    /**
     * The ways to draw {@code answers} on a body {@code widthDp} wide, best first, each shorter than
     * the one before. First the width ladder on whole lines: labels in full, 「或者」 and every choice
     * whole on at most {@link #MAX_ANSWER_LINES} lines (or one per answers line); then 「升级时答：」; then
     * 「或」; then up to {@link #MORE_ANSWER_LINES} lines; then a class answer without its 「（第 n 项）」
     * ({@link CardModel.AnswerLine#withoutPosition}: the answer names the option, so the position goes
     * before any choice wraps; it stays out of the forms after it); then choices that wrap inside
     * ({@link CardText#choicesLoose}); then {@link #ANSWERS_SMALL_SP} with every choice whole, then
     * wrapping inside. A choice wraps inside only where that saves a line over the same answers with
     * every choice whole at the same size ({@link #looseForm}): 「答：我会帮你的 / 这样的规矩没有意义」
     * never draws 「这样的规」/「矩没有意义」 where 「这样的规矩没有意义」 fits a line of its own and the
     * row takes no more lines that way (3.1.0 re-check). For a card short of height:
     * the lines cut to their first whole choices plus 「等 N 项」, a line only where that saves width
     * ({@link #answerForm}); then lines go, the least needed first ({@link #dropOrder}), each kept line
     * whole before it is cut; then a 朋友-route line's caution alone (「答：别选「…」」, then the
     * shorter 「答：别选表白」); last, for a card with room for one short line beside its title, a 课堂/考试
     * answer alone and whole, else the line that stays to the end with only its count
     * (「答：共 2 项，点开看看」, {@link CardText#countOnly}). The forms up to the cut ones are
     * {@link AnswerForm#whole}. The guide's 「（1）」 counts are already left out
     * ({@link app.p3r.dayplanner.v3.engine.DialogueHint#widgetChoices}, decision O3), and no choice
     * is ever cut with "…" or skipped in the middle. Empty without answers.
     */
    static List<AnswerForm> answerForms(Context c, List<CardModel.AnswerLine> answers, float widthDp) {
        List<AnswerForm> forms = new ArrayList<>();
        if (answers.isEmpty()) return forms;
        int few = Math.max(MAX_ANSWER_LINES, answers.size());
        int more = Math.max(MORE_ANSWER_LINES, answers.size());
        // {shortLabels, shortOr, lines}: the ladder's rungs on whole lines.
        Object[][] ladder = {{false, false, few}, {true, false, few}, {true, true, few}, {true, true, more}};
        AnswerForm[] rungs = new AnswerForm[ladder.length];
        for (int i = 0; i < ladder.length; i++) {
            rungs[i] = answerForm(c, answers, widthDp, (Boolean) ladder[i][0], (Boolean) ladder[i][1], false, (Integer) ladder[i][2],
                    DIALOGUE_SP, ALL);
        }
        for (int i = 0; i < rungs.length; i++) {
            // A label alone on a line (「要是升级了，答：」 / 「别选表白的回答」) gives way to the next rung when
            // that takes fewer lines (「升级时答：别选表白的回答」).
            AnswerForm next = i + 1 < rungs.length ? rungs[i + 1] : null;
            if (rungs[i] != null && next != null && next.lines < rungs[i].lines && strands(c, rungs[i], widthDp)) continue;
            addWhole(forms, rungs[i]);
        }
        // A class answer names its option itself: its position goes before any choice wraps or is cut.
        List<CardModel.AnswerLine> bare = new ArrayList<>(answers.size());
        for (CardModel.AnswerLine line : answers) bare.add(line.withoutPosition());
        if (!bare.equals(answers)) {
            answers = bare;
            addWhole(forms, answerForm(c, answers, widthDp, true, true, false, more, DIALOGUE_SP, ALL));
        }
        addWhole(forms, looseForm(c, answers, widthDp, more, DIALOGUE_SP, ALL));
        addWhole(forms, answerForm(c, answers, widthDp, true, true, false, more, ANSWERS_SMALL_SP, ALL));
        addWhole(forms, looseForm(c, answers, widthDp, more, ANSWERS_SMALL_SP, ALL));
        int most = mostChoices(answers);
        for (int shown = most - 1; shown >= 1; shown--) {
            AnswerForm big = answerForm(c, answers, widthDp, true, true, false, more, DIALOGUE_SP, shown);
            AnswerForm small = answerForm(c, answers, widthDp, true, true, false, more, ANSWERS_SMALL_SP, shown);
            // 「等 N 项」 alone on a line gives way to 11sp on fewer lines.
            if (big == null || small == null || small.lines >= big.lines || !strands(c, big, widthDp)) add(forms, big);
            add(forms, small);
        }
        // The shortest cards: lines go, the least needed first; a kept line stays whole where it can.
        List<List<CardModel.AnswerLine>> drops = dropOrder(answers);
        for (List<CardModel.AnswerLine> kept : drops) {
            add(forms, answerForm(c, kept, widthDp, true, true, false, kept.size(), ANSWERS_SMALL_SP, ALL));
            for (int shown = mostChoices(kept) - 1; shown >= 1; shown--) {
                add(forms, answerForm(c, kept, widthDp, true, true, false, kept.size(), ANSWERS_SMALL_SP, shown));
            }
            add(forms, looseForm(c, kept, widthDp, ALL, ANSWERS_SMALL_SP, ALL));
            for (int shown = mostChoices(kept) - 1; shown >= 1; shown--) {
                add(forms, looseForm(c, kept, widthDp, ALL, ANSWERS_SMALL_SP, shown));
            }
        }
        // Last, a 朋友-route line keeps its caution rather than its first choice (the confession picked by
        // mistake costs more than a weaker answer), the named answer to avoid or else the shorter
        // 「别选表白」 (the same warning).
        CardModel.AnswerLine own = answers.get(0);
        if (own.note != null && own.note.startsWith(DialogueHint.FRIEND_AVOID.substring(0, 2))) {
            List<String> notes = new ArrayList<>();
            if (!own.choices.isEmpty()) notes.add(own.note);
            if (!own.note.equals(DialogueHint.FRIEND_AVOID)) notes.add(DialogueHint.FRIEND_AVOID);
            for (String note : notes) {
                List<CardModel.AnswerLine> caution = Collections.singletonList(new CardModel.AnswerLine(own.label,
                        own.shortLabel, Collections.emptyList(), note, own.conditional));
                add(forms, answerForm(c, caution, widthDp, true, true, false, 1, ANSWERS_SMALL_SP, ALL));
            }
        }
        // Last, for a card with room for one short line beside its title (130dp high, a first choice wider
        // than the line): a 课堂/考试 answer that fits whole (certain, and missed for good) rather than a line
        // with no choice in it; else the line that stays to the end says how many choices it has,
        // 「答：共 2 项，点开看看」 (「考试：点开看看」 for one, and so a long label: 「升级时答：点开看看」); the
        // card's tap shows them.
        CardModel.AnswerLine kept = drops.isEmpty() ? answers.get(0) : drops.get(drops.size() - 1).get(0);
        for (CardModel.AnswerLine line : answers) {
            if (line != kept && certain(line)) {
                add(forms, answerForm(c, Collections.singletonList(line), widthDp, true, true, false, 1, ANSWERS_SMALL_SP, ALL));
            }
        }
        if (!kept.choices.isEmpty()) {
            for (String note : new LinkedHashSet<>(Arrays.asList(CardText.countOnly(kept.choices.size()), CardText.TAP_TO_SEE))) {
                List<CardModel.AnswerLine> count = Collections.singletonList(new CardModel.AnswerLine(kept.label, kept.shortLabel,
                        Collections.emptyList(), note, kept.conditional));
                add(forms, answerForm(c, count, widthDp, true, true, false, 1, ANSWERS_SMALL_SP, ALL));
            }
        }
        return forms;
    }

    /**
     * Whether a drawn line of {@code form} on a body {@code widthDp} wide holds only a label
     * (「要是升级了，答：」) or only 「等 N 项」: a line spent on a fragment (3.1.0 re-check).
     */
    private static boolean strands(Context c, AnswerForm form, float widthDp) {
        StaticLayout layout = layout(c, form.text, form.sp, Typeface.DEFAULT, widthDp, Integer.MAX_VALUE, DIALOGUE_SPACING_DP);
        for (int i = 0; i < layout.getLineCount(); i++) {
            String shown = CardText.plain(form.text.substring(layout.getLineStart(i), layout.getLineEnd(i))).trim();
            if (shown.endsWith("：") || shown.matches("等 \\d+ 项")) return true;
        }
        return false;
    }

    /** Adds {@code form} when there is one and it is shorter than the last form added. */
    private static void add(List<AnswerForm> forms, AnswerForm form) {
        if (form != null && (forms.isEmpty() || form.heightDp < forms.get(forms.size() - 1).heightDp)) forms.add(form);
    }

    /** As {@link #add} for a form that keeps every line and every choice ({@link AnswerForm#whole}). */
    private static void addWhole(List<AnswerForm> forms, AnswerForm form) {
        add(forms, form == null ? null : new AnswerForm(form.text, form.sp, form.heightDp, true, form.lines));
    }

    /**
     * {@code answers} at {@code sp} within {@code maxLines}, a choice wrapping inside
     * ({@link CardText#choicesLoose}) only where that takes fewer lines than every choice whole at the
     * same size and the same {@code shown}, or where no whole form fits the width (a choice, label or
     * note wider than a line); else every choice whole. Null when neither fits {@code maxLines}. Spec
     * 3.1.0 §1.4.3 step 5: a choice wraps inside only when it has to.
     */
    private static AnswerForm looseForm(Context c, List<CardModel.AnswerLine> answers, float widthDp, int maxLines, float sp,
                                        int shown) {
        AnswerForm loose = answerForm(c, answers, widthDp, true, true, true, maxLines, sp, shown);
        AnswerForm whole = answerForm(c, answers, widthDp, true, true, false, ALL, sp, shown);
        if (whole == null || loose != null && loose.lines < whole.lines) return loose;
        return whole.lines <= maxLines ? whole : null;
    }

    private static int mostChoices(List<CardModel.AnswerLine> lines) {
        int most = 0;
        for (CardModel.AnswerLine line : lines) most = Math.max(most, line.choices.size());
        return most;
    }

    /**
     * The answers with their lines going one by one, the least needed first (each list in the row's
     * order, the last one line long): the side steps' picks from the last; then a meeting's own line
     * when it is only 「要是升级了」 (it may not rank up) and a 课堂/考试 answer is there (certain, and
     * missed for good); then the 课堂/考试 answers from the last. The step's own line otherwise stays.
     */
    static List<List<CardModel.AnswerLine>> dropOrder(List<CardModel.AnswerLine> answers) {
        List<CardModel.AnswerLine> left = new ArrayList<>(answers);
        List<List<CardModel.AnswerLine>> out = new ArrayList<>();
        while (left.size() > 1) {
            int drop = -1;
            for (int i = left.size() - 1; i >= 1 && drop < 0; i--) if (!certain(left.get(i))) drop = i;
            if (drop < 0 && left.get(0).conditional && !certain(left.get(0))) drop = 0;
            if (drop < 0) drop = left.size() - 1;
            left.remove(drop);
            out.add(new ArrayList<>(left));
        }
        return out;
    }

    /** A 课堂/考试 answer of the card's slots (a reminder: certain, and missed for good). */
    static boolean certain(CardModel.AnswerLine line) {
        return line.label.startsWith(CardModel.CLASS_LABEL) || line.label.startsWith(CardModel.EXAM_LABEL);
    }

    /**
     * {@code answers} shaped for one rung, or null when it does not fit: more than {@code maxLines}
     * lines, or (unless {@code loose}) a choice, note or label wider than a line, which the line
     * breaker would cut wherever it likes. A line with more than {@code shown} choices keeps its first
     * {@code shown} and ends 「等 N 项」, unless the choices it would leave out are no wider than
     * 「等 N 项」 (then it stays whole); null when no line is cut at all for a {@code shown} below
     * {@link #ALL} (the whole lines are a form of their own).
     */
    private static AnswerForm answerForm(Context c, List<CardModel.AnswerLine> answers, float widthDp, boolean shortLabels,
                                         boolean shortOr, boolean loose, int maxLines, float sp, int shown) {
        int[] each = new int[answers.size()];
        boolean cut = false;
        float sizeDp = layoutTextDp(c, sp);
        for (int i = 0; i < each.length; i++) {
            CardModel.AnswerLine line = answers.get(i);
            each[i] = ALL;
            int n = line.choices.size();
            if (shown >= n) continue;
            List<String> left = new ArrayList<>();
            for (String choice : line.choices.subList(shown, n)) left.add(shortOr ? CardText.shortOr(choice) : choice);
            float leftDp = lineWidthDp(c, CardText.CHOICE_SEPARATOR + String.join(CardText.CHOICE_SEPARATOR, left), sizeDp, Typeface.DEFAULT);
            float etcDp = lineWidthDp(c, " " + CardText.etc(n), sizeDp, Typeface.DEFAULT);
            if (leftDp <= etcDp) continue;
            each[i] = shown;
            cut = true;
        }
        if (shown != ALL && !cut) return null;
        String text = CardText.answers(answers, shortLabels, shortOr, loose, each);
        StaticLayout layout = layout(c, text, sp, Typeface.DEFAULT, widthDp, Integer.MAX_VALUE, DIALOGUE_SPACING_DP);
        if (layout.getLineCount() > maxLines) return null;
        // A note that starts a line goes there without its 「·」 (the lines stay as they are).
        int[] starts = new int[layout.getLineCount()];
        for (int i = 0; i < starts.length; i++) starts[i] = layout.getLineStart(i);
        String broken = CardText.breakBeforeNotes(text, starts);
        if (!broken.equals(text)) {
            text = broken;
            layout = layout(c, text, sp, Typeface.DEFAULT, widthDp, Integer.MAX_VALUE, DIALOGUE_SPACING_DP);
        }
        if (!loose) {
            for (int i = 0; i < answers.size(); i++) {
                CardModel.AnswerLine line = answers.get(i);
                List<String> pieces = new ArrayList<>();
                pieces.add(shortLabels && line.shortLabel != null ? line.shortLabel : line.label);
                int count = Math.min(line.choices.size(), Math.max(1, each[i]));
                for (String choice : line.choices.subList(0, count)) pieces.add(shortOr ? CardText.shortOr(choice) : choice);
                if (count < line.choices.size()) pieces.add(CardText.etc(line.choices.size()));
                if (line.note != null) pieces.add(line.note);
                for (String piece : pieces) {
                    if (!isBlank(piece) && answerLines(c, piece, sp, widthDp) > 1) return null;
                }
            }
        }
        float height = DIALOGUE_GAP_DP + layout.getHeight() / c.getResources().getDisplayMetrics().density;
        return new AnswerForm(text, sp, height, false, layout.getLineCount());
    }

    /** Lines {@code text} wraps to in the answers row at {@code sp}. */
    private static int answerLines(Context c, String text, float sp, float widthDp) {
        return layout(c, text, sp, Typeface.DEFAULT, widthDp, Integer.MAX_VALUE, DIALOGUE_SPACING_DP).getLineCount();
    }

    /**
     * The longest form of the location ({@link CardText#locationForms}: the floor range, then the
     * mall or school, then the area go first) that fits whole beside the chip on a body
     * {@code innerWidthDp} wide; null when not even {@link #MIN_LOCATION_DP} is left or no form fits.
     */
    static String fitLocation(Context c, CardModel model, WidgetSize size, float innerWidthDp) {
        if (isBlank(model.location)) return null;
        float chip = chipWidthDp(c, model, size);
        float room = innerWidthDp - (chip > 0 ? chip + TAG_MARGIN_DP : 0) - PIN_DP - 1; // 1dp for rounding
        if (room < MIN_LOCATION_DP) return null;
        float sizeDp = layoutTextDp(c, metaTextSp(size));
        for (String form : CardText.locationForms(model.location)) {
            String shown = CardText.plain(form).equals(CardText.plain(model.location)) ? model.location : form;
            if (lineWidthDp(c, shown, sizeDp, Typeface.DEFAULT) <= room) return shown;
        }
        return null;
    }

    /**
     * The longest form of a one-line cue ({@link CardText#clauseForms}: the whole line, then its
     * leading whole clauses) that fits a body {@code innerWidthDp} wide without "…"; null when none
     * does. The whole line comes back as given.
     */
    static String fitCue(Context c, String cue, WidgetSize size, float innerWidthDp) {
        if (isBlank(cue)) return null;
        float sizeDp = layoutTextDp(c, cueTextSp(size));
        List<String> forms = CardText.clauseForms(cue);
        for (int i = 0; i < forms.size(); i++) {
            if (lineWidthDp(c, forms.get(i), sizeDp, Typeface.DEFAULT) <= innerWidthDp - 1) return i == 0 ? cue : forms.get(i); // 1dp for rounding
        }
        return null;
    }

    /** What a side-step line too wide for its row says instead: the card's tap shows the steps. */
    static String sideCount(int steps) {
        return steps > 1 ? "顺便还有 " + steps + " 件事，" + CardText.TAP_TO_SEE : "顺便还有一件事，" + CardText.TAP_TO_SEE;
    }

    /**
     * The side-step line (「顺便：在喷泉前把 20,000 日元交给田中社长」) on a body {@code innerWidthDp} wide,
     * never cut with "…" (3.1.0 re-check: 「顺便：把 20,000 日元交给穿西装…」): whole, else its short form
     * ({@code sideShort}: 「顺便：恶魔社群解锁前的准备」), else {@link #sideCount} (「顺便还有一件事，点开看看」);
     * null when none fits one line. The card reads the whole line out either way.
     */
    static String fitSide(Context c, String side, String sideShort, WidgetSize size, float innerWidthDp) {
        if (isBlank(side)) return null;
        float sizeDp = layoutTextDp(c, cueTextSp(size));
        int steps = 1;
        for (int i = 0; i < side.length(); i++) if (side.charAt(i) == '；') steps++; // StepCopy.sideLine joins them so
        for (String form : new String[]{side, sideShort, sideCount(steps)}) {
            if (!isBlank(form) && lineWidthDp(c, form, sizeDp, Typeface.DEFAULT) <= innerWidthDp - 1) return form; // 1dp for rounding
        }
        return null;
    }

    /**
     * The 人格面具 reminder (「记得带上「皇帝」阿尔卡那的人格面具」) beside its mark on a body
     * {@code innerWidthDp} wide, never cut with "…": whole, else its leading whole clauses
     * ({@link #fitCue}); null when none fits one line.
     */
    static String fitHint(Context c, String hint, WidgetSize size, float innerWidthDp) {
        return fitCue(c, hint, size, innerWidthDp - PIN_DP);
    }

    /**
     * Renders {@code model} for a host size in dp: picks the size bucket and, on short cards, drops
     * optional rows so the title keeps its lines (see {@link #fit}).
     */
    public static RemoteViews render(Context context, CardModel model, float widthDp, float heightDp) {
        WidgetSize size = WidgetSize.of(widthDp, heightDp);
        CardModel shaped = shaped(context, model, size, widthDp);
        CardModel fitted = fitHeader(context, fit(context, shaped, size, widthDp, heightDp), size, widthDp);
        return render(context, fitted, size);
    }

    /**
     * {@code model} with its wrapping text shaped for line breaks ({@link CardText}), so it is measured
     * exactly as it will wrap: the title (and its short forms) keeps numbers with their words and, with
     * {@link CardModel#keepWordsWhole}, names whole; the tag chip keeps every value whole. (The answers
     * row is shaped by {@link #fit}.) Returns {@code model} itself when nothing changes.
     */
    static CardModel shaped(Context c, CardModel model, WidgetSize size, float widthDp) {
        String title = shapedTitle(model, model.title);
        List<String> shortTitles = new ArrayList<>(model.shortTitles.size());
        for (String form : model.shortTitles) shortTitles.add(shapedTitle(model, form));
        String tag = CardText.chip(model.tag);
        String cue = model.keepWordsWhole && model.cueMaxLines > 1 ? CardText.sentence(model.cue) : model.cue;
        boolean same = Objects.equals(title, model.title) && Objects.equals(cue, model.cue)
                && shortTitles.equals(model.shortTitles) && Objects.equals(tag, model.tag);
        if (same) return model;
        CardModel shown = model.copy();
        shown.title = title;
        shown.shortTitles.clear();
        shown.shortTitles.addAll(shortTitles);
        shown.tag = tag;
        shown.cue = cue;
        return shown;
    }

    /** {@code title} shaped as {@link #shaped} shapes the title of {@code model}. */
    private static String shapedTitle(CardModel model, String title) {
        if (model.keepWordsWhole) return CardText.title(title, CardText.locationWords(model.location));
        return keepNumbersTogether(title);
    }

    /**
     * The title with the spaces beside a number made non-breaking, so 「羁绊剧情 · 伊织顺平（第 2 话）」
     * wraps before 「（」 and never as 「第 2」 / 「话）」.
     */
    static String keepNumbersTogether(String text) {
        return CardText.keepNumbersTogether(text);
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

    /**
     * Text size in dp that a text view sized {@code sp} in the layout XML draws at: the font scale
     * applied and, like {@code getDimensionPixelSize}, rounded to whole pixels (12sp at 115% on xhdpi
     * draws at 28px, not 27.6, and a long location measured at 27.6 came out 7dp short).
     */
    static float layoutTextDp(Context c, float sp) {
        DisplayMetrics metrics = c.getResources().getDisplayMetrics();
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)) / metrics.density;
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
     * Gives the title room before optional rows (docs/DESIGN_v3.md §18). Neither the title nor the
     * answers row is ever hidden: the row takes the first form of {@link #answerForms} that fits. The
     * cue line goes first (unless {@link CardModel#cueEssential}) when the title would otherwise drop
     * below a comfortable size. When the title would not fit even at its smallest size, the 当日提醒
     * row goes ({@link #fitReminder}: whole, short or not at all), then the 人格面具 reminder, then the
     * side-step line; then the body's padding, then the tag/location row, then the answers take their
     * whole shorter forms (choices that wrap inside, 11sp); then the title keeps one line, the longest
     * of its forms that fits it whole ({@link #oneLineTitle}); then the answers take the forms cut to
     * 「等 N 项」 or with lines left out (giving up the gap above them rather than a choice); then the
     * cue. On the shortest cards (130dp, two lines of text under the plate) that leaves one line for
     * the title and one for the answers. The body's padding also goes before an essential
     * instruction's second line. A title that would still end in "…" gives way to the first of its
     * {@link CardModel#shortTitles} that is whole, and a title that wraps at a 「 · 」 drops the dot
     * there ({@link #wrapAtSeparators}). The tag chip is never cut: the location beside
     * it takes the room that is left, in its longest form that fits whole ({@link #fitLocation}),
     * and a chip wider than the card takes a smaller size or, much wider, wraps after a 「、」. A
     * one-line cue shows whole, or its leading whole clauses ({@link #fitCue}), or goes (an essential
     * one stays). On a tall card the spare height is shared out above and below the text and under
     * the buttons.
     * Hides the day numeral behind a shown answers row and fades it behind other text. Returns
     * {@code model} itself when nothing changes.
     */
    static CardModel fit(Context c, CardModel model, WidgetSize size, float widthDp, float heightDp) {
        CardModel fitted = fitRows(c, model, size, widthDp, heightDp);
        if (model.shortTitles.isEmpty() || !titleCut(c, fitted, size, widthDp)) return fitted;
        for (int i = 0; i < model.shortTitles.size(); i++) {
            // The first short form that is whole: 「北村文吉、光子」, then 「北村文吉」, then 「教皇」.
            CardModel shorter = model.copy();
            shorter.title = model.shortTitles.get(i);
            shorter.shortTitles.clear();
            shorter.shortTitles.addAll(model.shortTitles.subList(i + 1, model.shortTitles.size()));
            CardModel refitted = fitRows(c, shorter, size, widthDp, heightDp);
            if (!titleCut(c, refitted, size, widthDp)) return refitted;
        }
        return fitted;
    }

    /** Whether the fitted title needs more lines than it keeps even at its smallest size (it ends in "…"). */
    static boolean titleCut(Context c, CardModel fitted, WidgetSize size, float widthDp) {
        if (isBlank(fitted.title)) return false;
        float titleWidth = titleWidthDp(fitted, size, widthDp - 2 * bodyPaddingDp(size));
        StaticLayout layout = layout(c, fitted.title, MIN_TITLE_SP, Typeface.DEFAULT_BOLD, titleWidth, Integer.MAX_VALUE, 0f);
        return layout.getLineCount() > Math.max(1, fitted.titleMaxLines);
    }

    private static CardModel edit(CardModel fitted, CardModel model) {
        return fitted == model ? model.copy() : fitted;
    }

    /**
     * Index of the first of {@code forms} (only its {@link AnswerForm#whole} ones when {@code wholeOnly})
     * that, less {@code gapDp}, fits {@code limitDp} beside {@code otherDp} of other rows; -1 for none.
     */
    private static int firstFit(List<AnswerForm> forms, float otherDp, float gapDp, float limitDp, boolean wholeOnly) {
        for (int i = 0; i < forms.size() && (!wholeOnly || forms.get(i).whole); i++) {
            if (otherDp + forms.get(i).heightDp - gapDp <= limitDp) return i;
        }
        return -1;
    }

    /**
     * The title of {@code model} for a card with room for one title line: the longest of the title and
     * its {@link CardModel#shortTitles} that fits one line whole at {@link #MIN_TITLE_SP} on a title
     * {@code titleWidthDp} wide (「教皇 · 北村文吉、光子」, 「北村文吉、光子」, 「北村文吉」, 「教皇」); the
     * shortest when none does.
     */
    static String oneLineTitle(Context c, CardModel model, float titleWidthDp) {
        List<String> forms = new ArrayList<>();
        forms.add(model.title);
        forms.addAll(model.shortTitles);
        for (String form : forms) {
            if (!isBlank(form) && titleNeedDp(c, form, MIN_TITLE_SP, titleWidthDp, 1) < Float.POSITIVE_INFINITY) return form;
        }
        return forms.get(forms.size() - 1);
    }

    private static CardModel fitRows(Context c, CardModel model, WidgetSize size, float widthDp, float heightDp) {
        float innerWidth = widthDp - 2 * bodyPaddingDp(size);
        float titleWidth = titleWidthDp(model, size, innerWidth);
        int lines = Math.max(1, model.titleMaxLines);
        float comfortable = textHeightDp(c, model.title, comfortableTitleSp(size), true, titleWidth, lines);
        float smallest = textHeightDp(c, model.title, MIN_TITLE_SP, true, titleWidth, lines);
        CardModel fitted = model;
        // The answers row is essential: the width ladder's first form that fits, shorter forms only
        // after every other row but the cue.
        List<AnswerForm> forms = answerForms(c, model.answers, innerWidth);
        int form = 0;
        String answersText = forms.isEmpty() ? null : forms.get(0).text;
        float answers = forms.isEmpty() ? 0 : forms.get(0).heightDp;
        // The 当日提醒 row: the first one whole or short, never cut.
        String reminderText = fitReminder(c, model, size, innerWidth);
        float reminder = reminderRowHeightDp(c, reminderText, size, innerWidth);
        // The chip is never cut short: a little wider than the card it takes a smaller size, much wider
        // it wraps, and the location makes way.
        float chip = chipWidthDp(c, model, size);
        boolean wide = !isBlank(model.tag) && chip + TAG_MARGIN_DP > innerWidth;
        float scale = wide ? (innerWidth - TAG_MARGIN_DP - TAG_PADDING_DP) / (chip - TAG_PADDING_DP) : 1;
        float tagSp = wide && scale >= MIN_CHIP_SCALE ? (float) Math.floor(metaTextSp(size) * scale * 2) / 2 : 0;
        int tagLines = wide && tagSp == 0 ? 2 : 1;
        if (tagLines != model.tagMaxLines || tagSp != model.tagTextSp) {
            fitted = edit(fitted, model);
            fitted.tagMaxLines = tagLines;
            fitted.tagTextSp = tagSp;
        }
        String location = wide ? null : fitLocation(c, model, size, innerWidth);
        if (!Objects.equals(location, model.location)) {
            fitted = edit(fitted, model);
            fitted.location = location;
        }
        // The 人格面具 reminder and the side step are never cut with "…" either (3.1.0 re-check): their
        // longest form that fits one line whole, else no row.
        String hintShown = fitHint(c, model.hint, size, innerWidth);
        String sideShown = fitSide(c, model.side, model.sideShort, size, innerWidth);
        if (!Objects.equals(hintShown, model.hint) || !Objects.equals(sideShown, model.side)) {
            fitted = edit(fitted, model);
            fitted.hint = hintShown;
            fitted.side = sideShown;
        }
        float padding = bodyVerticalPaddingDp(size);
        float body = heightDp - chromeHeightDp(size);
        float meta = metaRowHeightDp(c, fitted, size, innerWidth);
        float hint = hintRowHeightDp(c, fitted, size, innerWidth);
        float side = sideRowHeightDp(c, fitted, size, innerWidth);
        float cue = cueRowHeightDp(c, fitted, size, innerWidth);
        float reclaimed = 0; // body padding given up so an instruction keeps its lines, the chip its row, the answers their form
        float extras = hint + side + reminder; // the rows that go before the chip's
        if (cue > 0 && fitted.cueMaxLines > 1 && smallest + answers + meta + extras + cue > body) {
            float deficit = smallest + answers + meta + extras + cue - body;
            if (model.cueEssential && deficit <= padding) {
                // An instruction keeps its words: as much of the body's padding as it needs goes first.
                reclaimed = deficit;
                body += deficit;
            } else {
                // A wrapping cue that does not fit goes back to one line (ending in "…") before anything goes.
                fitted = edit(fitted, model);
                fitted.cueMaxLines = 1;
                cue = cueRowHeightDp(c, fitted, size, innerWidth);
            }
        }
        // A one-line cue is never cut inside a word or a sum with "…": it keeps its longest whole form
        // (whole clauses) or, unless it is the card's instruction, goes.
        if (cue > 0 && fitted.cueMaxLines <= 1) {
            String whole = fitCue(c, fitted.cue, size, innerWidth);
            if (whole == null && !model.cueEssential) {
                fitted = edit(fitted, model);
                fitted.cue = null;
                cue = 0;
            } else if (whole != null && !whole.equals(fitted.cue)) {
                fitted = edit(fitted, model);
                fitted.cue = whole;
            }
        }
        if (cue > 0 && !model.cueEssential && comfortable + answers + meta + extras + cue > body) {
            fitted = edit(fitted, model);
            fitted.cue = null;
            cue = 0;
        }
        // Then, only when the title would not fit even at its smallest: the 当日提醒 row, the 人格面具
        // reminder, the side step.
        if (reminder > 0 && smallest + answers + meta + hint + side + reminder + cue > body) {
            reminderText = null;
            reminder = 0;
        }
        if (hint > 0 && smallest + answers + meta + hint + side + cue > body) {
            fitted = edit(fitted, model);
            fitted.hint = null;
            hint = 0;
        }
        if (side > 0 && smallest + answers + meta + side + cue > body) {
            fitted = edit(fitted, model);
            fitted.side = null;
            side = 0;
        }
        // The chip is the step's key fact (RANK 4→5, 学力 +2) and the answers are essential: the body's
        // padding goes first, then the chip's row, then the answers take their shorter forms.
        float room = padding - reclaimed;
        float deficit = smallest + answers + meta + cue - body;
        boolean keyFacts = !isBlank(fitted.tag) || !forms.isEmpty();
        if (deficit > 0 && meta > 0 && keyFacts && deficit <= room) {
            reclaimed += deficit;
            body += deficit;
            deficit = 0;
        }
        if (deficit > 0 && meta > 0) {
            fitted = edit(fitted, model);
            fitted.tag = null;
            fitted.location = null;
            meta = 0;
            deficit = smallest + answers + cue - body;
        }
        room = padding - reclaimed;
        // Then the answers, which are never hidden, and the title, which never goes either (spec 3.1.0
        // §1.4.4), in this order: the answers' whole forms, every line and every choice (「升级时答：」,
        // 「或」, three lines, no 「（第 n 项）」, choices that wrap inside, 11sp); then the title keeps one
        // line, the longest of its forms that fits that line whole (「教皇 · 北村文吉、光子」, 「北村文吉、光子」,
        // 「北村文吉」, 「教皇」); last the answers' shorter forms (「等 N 项」, lines going the least needed
        // first, a 朋友-route caution alone, the count). The row gives up the gap above it rather than a
        // choice. The shortest cards (130dp high) hold two lines of text under the plate: one for the
        // title, one for the answers.
        String titleLine = null;
        boolean tight = false;
        if (deficit > 0 && !forms.isEmpty()) {
            float limit = body + room;
            form = firstFit(forms, smallest + cue, 0, limit, true);
            if (form < 0) {
                titleLine = oneLineTitle(c, fitted, titleWidth);
                smallest = titleHeightDp(c, titleLine, MIN_TITLE_SP, titleWidth, 1);
                form = firstFit(forms, smallest + cue, 0, limit, true);
            }
            if (form < 0) {
                form = firstFit(forms, smallest + cue, DIALOGUE_GAP_DP, limit, true);
                tight = form >= 0;
            }
            if (form < 0) form = firstFit(forms, smallest + cue, 0, limit, false);
            if (form < 0) {
                tight = true;
                form = firstFit(forms, smallest + cue, DIALOGUE_GAP_DP, limit, false);
            }
            if (form < 0) form = forms.size() - 1; // none fits: the last stays
            answers = forms.get(form).heightDp - (tight ? DIALOGUE_GAP_DP : 0);
            deficit = smallest + answers + cue - body;
            if (deficit > 0) {
                float taken = Math.min(deficit, room);
                reclaimed += taken;
                body += taken;
            }
        }
        if (!forms.isEmpty()) answersText = forms.get(form).text;
        float answersSp = forms.isEmpty() || forms.get(form).sp == DIALOGUE_SP ? 0 : forms.get(form).sp;
        if (cue > 0 && smallest + answers + cue > body) {
            fitted = edit(fitted, model);
            fitted.cue = null;
            cue = 0;
        }
        if (!Objects.equals(answersText, fitted.answersShown) || answersSp != fitted.answersTextSp
                || tight != fitted.answersTight || !Objects.equals(reminderText, fitted.reminderShown)) {
            fitted = edit(fitted, model);
            fitted.answersShown = answersText;
            fitted.answersTextSp = answersSp;
            fitted.answersTight = tight;
            fitted.reminderShown = reminderText;
        }
        if (titleLine != null && !titleLine.equals(fitted.title)) {
            fitted = edit(fitted, model);
            fitted.title = titleLine;
        }
        float rows = answers + meta + hint + side + reminder + cue;
        // On the shortest cards even the smallest title may not fit all its lines: keep the lines that
        // fit and let the last one end in "…" rather than be cut in half.
        int fitLines = titleLine != null ? 1 : lines;
        while (fitLines > 1 && titleHeightDp(c, fitted.title, MIN_TITLE_SP, titleWidth, fitLines) > body - rows) fitLines--;
        if (fitLines != fitted.titleMaxLines) {
            fitted = edit(fitted, model);
            fitted.titleMaxLines = fitLines;
        }
        // A tall card would leave a band between the plate and a title sitting on the buttons: the title
        // box shrinks to what the largest size needs (as the layout's autosize measures it, which may
        // be a little taller than the drawn text) and the text sits centred between plate and buttons.
        float sp = maxTitleSp(size);
        float drawn = textHeightDp(c, fitted.title, sp, true, titleWidth, fitLines);
        float inBox = Math.max(0, titleNeedDp(c, fitted.title, sp, titleWidth, fitLines) + TITLE_SLACK_DP - drawn);
        float spare = body - rows - drawn - inBox;
        float under = spare > 0 ? Math.min(spare * UNDER_BUTTONS_SHARE, maxSpaceUnderButtonsDp(size)) : 0;
        float box = spare > 0 ? drawn + inBox : body - rows;
        // A title that wraps at a 「 · 」 wraps there without the dot: no line ends in 「·」.
        String wrapped = wrapAtSeparators(c, fitted.title, size, titleWidth, box, fitLines);
        if (!Objects.equals(wrapped, fitted.title)) {
            fitted = edit(fitted, model);
            fitted.title = wrapped;
        }
        // A kind mark sits on the title's first line: a title that will wrap hangs from the top.
        boolean top = hasTitleIcon(fitted) && titleLines(c, fitted.title, size, titleWidth, box, fitLines) > 1;
        if (top != fitted.titleTop) {
            fitted = edit(fitted, model);
            fitted.titleTop = top;
        }
        float above = 0;
        float below = 0;
        if (spare > 0) {
            // The box's own slack shows on the side the text does not hang from (it hangs from the
            // top only where the title's gravity can be set).
            float half = (spare - under + inBox) / 2;
            boolean hangs = top && Build.VERSION.SDK_INT >= 31;
            float slackSide = Math.max(0, half - inBox);
            above = hangs ? spare - under - slackSide : slackSide;
            below = spare - under - above;
        }
        // Reclaimed padding comes from above the buttons first: the plate keeps a gap over the title.
        float reclaimedBelow = Math.min(reclaimed, padding / 2);
        above -= reclaimed - reclaimedBelow;
        below -= reclaimedBelow;
        if (above != fitted.spaceAboveDp || below != fitted.spaceBelowDp || under != fitted.spaceUnderButtonsDp) {
            fitted = edit(fitted, model);
            fitted.spaceAboveDp = above;
            fitted.spaceBelowDp = below;
            fitted.spaceUnderButtonsDp = under;
        }
        // Full-width answers stay clear of the outlined day numeral (they run the card's whole width); other
        // text that runs into it gets a fainter numeral.
        if (answers > 0 && fitted.watermarkDay >= 0) {
            fitted = edit(fitted, model);
            fitted.watermarkDay = -1;
        }
        boolean faint = textReachesWatermark(c, fitted, size, widthDp);
        if (faint != fitted.watermarkFaint) {
            fitted = edit(fitted, model);
            fitted.watermarkFaint = faint;
        }
        return fitted;
    }

    /**
     * Height in dp the title needs at {@code sp} within {@code maxLines}, measured as the layout's
     * autosize measures it (with the taller CJK fallback line height); infinite when it needs more lines.
     */
    static float titleNeedDp(Context c, String title, float sp, float widthDp, int maxLines) {
        if (isBlank(title)) return 0;
        StaticLayout layout = autosizeLayout(c, title, sp, widthDp);
        if (layout.getLineCount() > Math.max(1, maxLines)) return Float.POSITIVE_INFINITY;
        return layout.getHeight() / c.getResources().getDisplayMetrics().density;
    }

    /**
     * The bold title at {@code sp} wrapped to {@code widthDp} as the layout's autosize lays it out: the
     * TextView's high-quality line breaking and, on P+, the taller CJK fallback line height.
     */
    @SuppressLint("WrongConstant") // Layout's constant (API 23) is the value LineBreaker (API 29) names
    private static StaticLayout autosizeLayout(Context c, String title, float sp, float widthDp) {
        DisplayMetrics metrics = c.getResources().getDisplayMetrics();
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        int width = Math.max(1, Math.round(widthDp * metrics.density));
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(title, 0, title.length(), paint, width).setIncludePad(false)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true);
        return builder.build();
    }

    /** Height in dp of the outlined day numeral per bucket. Mirrors the layout XML. */
    static float watermarkHeightDp(WidgetSize size) {
        switch (size) {
            case REGULAR: return 112;
            case COMPACT: return 92;
            default: return 84;
        }
    }

    /** Width in dp of the outlined numeral of {@code day} (two digit slots, the second overlapping). */
    static float watermarkWidthDp(Context c, int day, WidgetSize size) {
        float height = watermarkHeightDp(size);
        float overlap = size == WidgetSize.REGULAR ? 14 : 11;
        int tens = day >= 10 ? day / 10 : day;
        int units = day >= 10 ? day % 10 : 0; // a lone digit keeps the second slot's width
        return digitWidthDp(c, tens, height) + digitWidthDp(c, units, height) - overlap;
    }

    private static float digitWidthDp(Context c, int digit, float heightDp) {
        android.graphics.drawable.Drawable d = c.getDrawable(DIGITS[digit]);
        if (d == null || d.getIntrinsicHeight() <= 0) return heightDp * 0.65f;
        return heightDp * d.getIntrinsicWidth() / d.getIntrinsicHeight();
    }

    /**
     * Whether a shown line of text reaches the outlined day numeral at the right edge (it spans the
     * body's height): the title at its largest size, the tag/location row, the reminder, side and cue.
     */
    static boolean textReachesWatermark(Context c, CardModel m, WidgetSize size, float widthDp) {
        if (m.watermarkDay < 1 || m.watermarkDay > 31) return false;
        float scale = c.getResources().getConfiguration().fontScale;
        float pad = bodyPaddingDp(size);
        float inner = widthDp - 2 * pad;
        float titleWidth = titleWidthDp(m, size, inner);
        float reach = (inner - titleWidth) + widestLineDp(c, m.title, maxTitleSp(size), Typeface.DEFAULT_BOLD, titleWidth,
                Math.max(1, m.titleMaxLines));
        float chip = Math.min(inner, chipWidthDp(c, m, size));
        float location = isBlank(m.location) ? 0 : PIN_DP + lineWidthDp(c, m.location, metaTextSp(size) * scale, Typeface.DEFAULT);
        reach = Math.max(reach, (chip > 0 ? chip + TAG_MARGIN_DP : 0) + location);
        float cueSp = cueTextSp(size);
        if (!isBlank(m.hint)) reach = Math.max(reach, PIN_DP + lineWidthDp(c, m.hint, cueSp * scale, Typeface.DEFAULT));
        if (!isBlank(m.side)) reach = Math.max(reach, lineWidthDp(c, m.side, cueSp * scale, Typeface.DEFAULT));
        if (!isBlank(m.reminderShown)) {
            reach = Math.max(reach, PIN_DP + lineWidthDp(c, m.reminderShown, cueSp * scale, Typeface.DEFAULT));
        }
        reach = Math.max(reach, widestLineDp(c, m.cue, cueSp, Typeface.DEFAULT, inner, Math.max(1, m.cueMaxLines)));
        float numeral = widthDp + WATERMARK_BLEED_DP - watermarkWidthDp(c, m.watermarkDay, size);
        return pad + Math.min(inner, reach) > numeral + WATERMARK_TOLERANCE_DP;
    }

    /** The numeral hangs this far past the right edge (dp). Mirrors the layout XML. */
    private static final float WATERMARK_BLEED_DP = 2f;
    /** Text may touch the numeral's (slanted, mostly empty) left edge by this much (dp). */
    private static final float WATERMARK_TOLERANCE_DP = 4f;

    /** Width in dp of the widest of the first {@code maxLines} lines of {@code text} wrapped to {@code widthDp}. */
    private static float widestLineDp(Context c, String text, float sp, Typeface typeface, float widthDp, int maxLines) {
        if (isBlank(text)) return 0;
        StaticLayout layout = layout(c, text, sp, typeface, widthDp, Integer.MAX_VALUE, 0f);
        float widest = 0;
        for (int i = 0; i < Math.min(maxLines, layout.getLineCount()); i++) widest = Math.max(widest, layout.getLineWidth(i));
        return widest / c.getResources().getDisplayMetrics().density;
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
        int alpha = model.watermarkFaint ? Math.round(palette.watermarkImageAlpha * FAINT_WATERMARK) : palette.watermarkImageAlpha;
        views.setInt(R.id.widget_watermark_tens, "setImageAlpha", alpha);
        views.setInt(R.id.widget_watermark_units, "setImageAlpha", alpha);
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
        // Body padding (none when tight) plus the spare height fit() shared out on a tall card, less
        // what it gave up for an instruction on a short one.
        float density = c.getResources().getDisplayMetrics().density;
        int side = Math.round(bodyPaddingDp(size) * density);
        float padding = bodyVerticalPaddingDp(size) / 2;
        views.setViewPadding(R.id.widget_task_area, side, Math.round(Math.max(0, padding + model.spaceAboveDp) * density),
                side, Math.round(Math.max(0, padding + model.spaceBelowDp) * density));
        views.setViewPadding(R.id.widget_content, 0, 0, 0,
                Math.round((contentPaddingDp(size) + model.spaceUnderButtonsDp) * density));
        int ink = palette.ink(c);
        int soft = palette.inkSoft(c);
        // The title is never hidden (a reapplied update may find the row of an older card's views).
        views.setViewVisibility(R.id.widget_title_row, View.VISIBLE);
        views.setTextViewText(R.id.widget_task, keepNumbersTogether(model.title));
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

        // The answers in the primary ink; the lines of a meeting that may not rank up are muted.
        setTextOrHide(views, R.id.widget_dialogue_choices, answersText(model, soft));
        views.setTextColor(R.id.widget_dialogue_choices, ink);
        views.setTextViewTextSize(R.id.widget_dialogue_choices, TypedValue.COMPLEX_UNIT_SP,
                model.answersTextSp > 0 ? model.answersTextSp : DIALOGUE_SP);
        views.setViewPadding(R.id.widget_dialogue_choices, 0, model.answersTight ? 0 : Math.round(DIALOGUE_GAP_DP * density), 0, 0);

        setTextOrHide(views, R.id.widget_reminder, model.reminderShown);
        views.setTextColor(R.id.widget_reminder, ink);
        views.setTextViewCompoundDrawables(R.id.widget_reminder,
                model.night ? R.drawable.widget_icon_remind_night : R.drawable.widget_icon_remind_day, 0, 0, 0);

        setTextOrHide(views, R.id.widget_tag, model.tag);
        views.setInt(R.id.widget_tag, "setBackgroundResource", palette.tag);
        views.setTextColor(R.id.widget_tag, palette.tagInk(c));
        views.setInt(R.id.widget_tag, "setMaxLines", Math.max(1, model.tagMaxLines));
        views.setTextViewTextSize(R.id.widget_tag, TypedValue.COMPLEX_UNIT_SP, chipTextSp(model, size));

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
        views.setInt(R.id.widget_detail_cue, "setMaxLines", Math.max(1, model.cueMaxLines));
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
            // Without the trailing glyph the label centres on the whole face. The narrow face keeps
            // just clear of its slanted ends so 「已经玩到这里」 fits on one line at font scale 1.15.
            int side = Math.round((size == WidgetSize.TINY ? 6 : 10) * c.getResources().getDisplayMetrics().density);
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

    private static void setTextOrHide(RemoteViews views, int id, CharSequence text) {
        boolean visible = text != null && !isBlank(text.toString());
        views.setViewVisibility(id, visible ? View.VISIBLE : View.GONE);
        views.setTextViewText(id, visible ? text : "");
    }

    /**
     * The answers row as drawn ({@link CardModel#answersShown}), an {@link CardModel.AnswerLine#conditional}
     * line in {@code soft} at {@link #MUTED_ANSWER_ALPHA}; null when hidden.
     */
    static CharSequence answersText(CardModel model, int soft) {
        String text = model.answersShown;
        if (isBlank(text)) return text;
        android.text.SpannableString spanned = new android.text.SpannableString(text);
        int muted = (soft & 0x00FFFFFF) | (MUTED_ANSWER_ALPHA << 24);
        int start = 0;
        for (int i = 0; i < model.answers.size() && start <= text.length(); i++) {
            int end = text.indexOf(CardText.ANSWER_LINE_BREAK, start);
            if (end < 0) end = text.length();
            if (model.answers.get(i).conditional && end > start) {
                spanned.setSpan(new android.text.style.ForegroundColorSpan(muted), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end + CardText.ANSWER_LINE_BREAK.length();
        }
        return spanned;
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
