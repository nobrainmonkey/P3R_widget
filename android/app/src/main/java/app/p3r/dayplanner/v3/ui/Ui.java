package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * The v3 theme kit: spacing on an 8 dp grid, the type scale, the bundled numeral face, and
 * factories for the recurring P3R-styled pieces (slanted cards, parallelogram buttons, chips).
 * Screens build their views through these helpers so every surface shares one visual language.
 */
public final class Ui {
    /** Page side gutter (dp). */
    public static final int GUTTER = 16;
    /** Minimum height of primary touch targets (dp). */
    public static final int TOUCH = 48;

    private static volatile boolean animations = true;
    private static Typeface numeralItalic;
    private static Typeface numeralUpright;

    private Ui() {}

    // ------------------------------------------------------------------ basics

    /** Disables enter/exit animations (render tests). */
    public static void setAnimationsEnabled(boolean enabled) {
        animations = enabled;
    }

    public static boolean animationsEnabled() {
        return animations;
    }

    public static int dp(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
            context.getResources().getDisplayMetrics()));
    }

    public static int col(Context context, int colorRes) {
        return context.getColor(colorRes);
    }

    /** Colour with replaced alpha (0..255). */
    public static int alpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    // ------------------------------------------------------------------ type

    /** Bold condensed italic numerals (Barlow Condensed ExtraBold Italic). Latin text only. */
    public static synchronized Typeface numerals(Context context) {
        if (numeralItalic == null) {
            Typeface family = loadFamily(context);
            if (Build.VERSION.SDK_INT >= 28) {
                numeralItalic = Typeface.create(family, 800, true);
            } else {
                numeralItalic = Typeface.create(family, Typeface.BOLD_ITALIC);
            }
        }
        return numeralItalic;
    }

    /** Upright bold condensed face for small Latin labels. */
    public static synchronized Typeface numeralsUpright(Context context) {
        if (numeralUpright == null) {
            Typeface family = loadFamily(context);
            if (Build.VERSION.SDK_INT >= 28) {
                numeralUpright = Typeface.create(family, 700, false);
            } else {
                numeralUpright = Typeface.create(family, Typeface.BOLD);
            }
        }
        return numeralUpright;
    }

    private static Typeface loadFamily(Context context) {
        try {
            Typeface family = context.getResources().getFont(R.font.p3r_numerals);
            return family == null ? Typeface.DEFAULT_BOLD : family;
        } catch (RuntimeException missing) {
            return Typeface.DEFAULT_BOLD;
        }
    }

    /**
     * Plain text view (CJK-safe: never italic). Names, venues, words and short tags are kept on one
     * line ({@link Zh#keepTogether}), multipliers, ranks and levels ("×1.51", "RANK 7", "MAX",
     * "Lv.3") are always set in the numeral face, and no line ends with a " · " or "/" separator
     * ({@link ZhTextView}), also for text set later.
     */
    public static TextView text(Context context, CharSequence value, float sp, int color, boolean bold) {
        TextView view = new ZhTextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        view.setIncludeFontPadding(true);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    /** Text with its Latin/digit runs set in the numeral face ("皇帝 RANK 7"). */
    public static TextView mixed(Context context, CharSequence value, float sp, int color, boolean bold) {
        return text(context, latin(context, value), sp, color, bold);
    }

    /** Pure numeral text ("10/22", "RANK 6 → 7", "8,200"). */
    public static TextView numeral(Context context, CharSequence value, float sp, int color) {
        TextView view = text(context, value, sp, color, false);
        view.setTypeface(numerals(context));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0f, 1f);
        // Again in the numeral face: "Lv.4" needs no numeral spans here ({@link #prepare}).
        view.setText(value);
        return view;
    }

    /**
     * Applies the numeral face to Latin runs inside a mixed string; names, venues and short tags
     * are kept on one line ({@link Zh#keepTogether}).
     */
    public static CharSequence latin(Context context, CharSequence value) {
        return NumeralSpan.styleLatinRuns(Zh.keepTogether(value), numerals(context), 1.14f);
    }

    /**
     * Multipliers ("×1.51"), link ranks ("RANK 7", "RANK 6 → 7", "MAX") and stat levels ("Lv.3")
     * look the same in every text: numeral face, like in mixed text. Word joiners may sit between
     * any two characters ("R⁠A⁠N⁠K⁠ ⁠7", {@link Zh#keepTogether}).
     */
    private static final java.util.regex.Pattern NUMERAL_WORDS = java.util.regex.Pattern.compile(
        "×[\\s\\u2060]*[0-9][0-9.\\u2060]*"
            + "|(?<![A-Za-z])(?:R\\u2060?A\\u2060?N\\u2060?K|M\\u2060?A\\u2060?X|L\\u2060?v\\u2060?\\.)"
            + "(?:[ \\u2060]*(?:[0-9][0-9\\u2060]*|M\\u2060?A\\u2060?X|→|R\\u2060?A\\u2060?N\\u2060?K))*(?![A-Za-z])");

    /**
     * What a text view ({@link #text}) shows for {@code value}: kept-together names, and the
     * {@link #NUMERAL_WORDS} in the numeral face unless the whole view is set in it.
     */
    static CharSequence prepare(TextView view, CharSequence value) {
        CharSequence kept = Zh.keepTogether(value);
        if (kept == null || kept.length() == 0) {
            return kept;
        }
        Typeface face = view.getTypeface();
        if (face != null && (face == numeralItalic || face == numeralUpright)) {
            return kept;
        }
        java.util.regex.Matcher m = NUMERAL_WORDS.matcher(kept);
        android.text.SpannableString out = null;
        while (m.find()) {
            if (kept instanceof Spanned && ((Spanned) kept).getSpans(m.start(), m.end(), NumeralSpan.class).length > 0) {
                continue;
            }
            if (out == null) {
                out = new android.text.SpannableString(kept);
            }
            NumeralSpan.style(out, m.start(), m.end(), numerals(view.getContext()), 1.14f);
        }
        return out != null ? out : kept;
    }

    /**
     * "25,050 日元" with the number in the numeral face and a smaller upright cyan unit; the
     * view's own typeface stays the CJK default so the unit is never slanted.
     */
    public static CharSequence money(Context context, String number) {
        SpannableStringBuilder sb = new SpannableStringBuilder(latin(context, number));
        int start = sb.length();
        sb.append(" 日元");
        sb.setSpan(new RelativeSizeSpan(0.55f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(col(context, R.color.p3r_cyan_300)), start, sb.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    public static TextView title(Context context, CharSequence value) {
        return mixed(context, value, 24, col(context, R.color.p3r_white), true);
    }

    public static TextView headline(Context context, CharSequence value) {
        return mixed(context, value, 18, col(context, R.color.p3r_white), true);
    }

    public static TextView body(Context context, CharSequence value) {
        return text(context, value, 15, col(context, R.color.p3r_ice_100), false);
    }

    public static TextView muted(Context context, CharSequence value) {
        return text(context, value, 14, col(context, R.color.p3r_ink_muted), false);
    }

    public static TextView caption(Context context, CharSequence value) {
        return text(context, value, 13, col(context, R.color.p3r_ink_muted), false);
    }

    /**
     * Small slanted label ("社群", "必须", "今天别错过"): bold ink on a filled parallelogram.
     * {@code fill} and {@code ink} are colours, not resources.
     */
    public static TextView tag(Context context, CharSequence label, int fill, int ink) {
        TextView tag = text(context, latin(context, label), 13, ink, true);
        tag.setGravity(Gravity.CENTER);
        tag.setBackground(new SlantedPanelDrawable().fill(fill).skew(dp(context, 5)));
        pad(tag, 10, 2, 10, 3);
        tag.setMaxLines(1);
        return tag;
    }

    // ------------------------------------------------------------------ layout

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    /** Sets margins (dp) on a view whose LayoutParams are MarginLayoutParams. */
    public static <T extends View> T margins(T view, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        ViewGroup.MarginLayoutParams margin;
        if (params instanceof ViewGroup.MarginLayoutParams) {
            margin = (ViewGroup.MarginLayoutParams) params;
        } else {
            margin = matchWrap();
        }
        Context c = view.getContext();
        margin.setMargins(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
        view.setLayoutParams(margin);
        return view;
    }

    /** Sets padding in dp. */
    public static <T extends View> T pad(T view, int left, int top, int right, int bottom) {
        Context c = view.getContext();
        view.setPadding(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
        return view;
    }

    /** Fixed-size vertical gap (dp). */
    public static View gap(Context context, int dp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, dp)));
        return view;
    }

    // ------------------------------------------------------------------ surfaces

    /**
     * Standard panel: borderless navy glass (lighter top-left, deeper bottom-right) with the two
     * diagonal cuts and a short cyan highlight along the top-left cut instead of an outline.
     */
    public static SlantedPanelDrawable card(Context context) {
        int cut = dp(context, 14);
        return new SlantedPanelDrawable()
            .gradient(col(context, R.color.ui_glass_top), col(context, R.color.ui_glass_bottom))
            .cornerTick(alpha(col(context, R.color.p3r_cyan_400), 200), dp(context, 2))
            .cuts(cut, 0, cut, 0);
    }

    /** Panel that reacts to presses (list rows, menu entries): pressed it lights up in cyan. */
    public static SlantedPanelDrawable pressableCard(Context context) {
        return card(context).pressedFill(col(context, R.color.p3r_navy_700))
            .pressedStroke(col(context, R.color.p3r_cyan_400));
    }

    /** Flat slanted band without highlight (summary strips, list groups). */
    public static SlantedPanelDrawable band(Context context) {
        return new SlantedPanelDrawable()
            .fill(col(context, R.color.ui_band))
            .cuts(dp(context, 12), 0, dp(context, 12), 0);
    }

    /** Hero card: royal-to-navy gradient, a cyan blade on the left and a diagonal light slash. */
    public static SlantedPanelDrawable heroCard(Context context) {
        int cut = dp(context, 18);
        return new SlantedPanelDrawable()
            .gradient(col(context, R.color.ui_hero_start), col(context, R.color.ui_hero_end))
            .accent(col(context, R.color.p3r_cyan_400), dp(context, 6))
            .lightSlash(col(context, R.color.ui_slash))
            .cornerTick(col(context, R.color.p3r_white), dp(context, 2))
            .cuts(cut, 0, cut, 0);
    }

    // ------------------------------------------------------------------ buttons

    /**
     * Primary action, like the widget's 完成: white parallelogram with navy ink and a cyan slab
     * offset to the lower right, ≥ 48 dp tall. Pressed, the face turns cyan and the slab tucks in.
     */
    public static TextView primary(Context context, CharSequence label) {
        TextView button = baseButton(context, label);
        button.setTextColor(col(context, R.color.p3r_navy_950));
        int skew = dp(context, 10);
        button.setBackground(new SlantedPanelDrawable()
            .fill(col(context, R.color.p3r_white))
            .pressedFill(col(context, R.color.p3r_cyan_200))
            .skew(skew)
            .slab(col(context, R.color.p3r_cyan_400), dp(context, 4), dp(context, 4)));
        Motion.pressable(button);
        return button;
    }

    /** Secondary action: outlined parallelogram with white ink. */
    public static TextView secondary(Context context, CharSequence label) {
        TextView button = baseButton(context, label);
        button.setTextColor(col(context, R.color.p3r_white));
        int skew = dp(context, 10);
        button.setBackground(new SlantedPanelDrawable()
            .fill(alpha(col(context, R.color.p3r_navy_950), 110))
            .pressedFill(col(context, R.color.p3r_navy_700))
            .stroke(col(context, R.color.p3r_cyan_300), dp(context, 1.5f))
            .skew(skew));
        Motion.pressable(button);
        return button;
    }

    /** Low-emphasis text action in cyan (still a 48 dp target). */
    public static TextView quiet(Context context, CharSequence label) {
        TextView button = baseButton(context, label);
        button.setTextColor(col(context, R.color.p3r_cyan_300));
        button.setTypeface(Typeface.DEFAULT);
        button.setBackground(new SlantedPanelDrawable()
            .fill(0)
            .pressedFill(col(context, R.color.ui_cyan_soft))
            .skew(dp(context, 8)));
        return button;
    }

    /** Announces text-view buttons and chips as buttons (and their selected state) to TalkBack. */
    private static final View.AccessibilityDelegate BUTTON_ROLE = new View.AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.Button");
            info.setSelected(host.isSelected());
        }
    };

    private static TextView baseButton(Context context, CharSequence label) {
        TextView button = text(context, latin(context, label), 16, col(context, R.color.p3r_white), true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, TOUCH));
        button.setMinimumHeight(dp(context, TOUCH));
        button.setMaxLines(2);
        button.setEllipsize(TextUtils.TruncateAt.END);
        pad(button, 18, 8, 18, 8);
        button.setClickable(true);
        button.setFocusable(true);
        button.setAccessibilityDelegate(BUTTON_ROLE);
        return button;
    }

    /** Selectable chip (segments, tier chips, quick answers). */
    public static TextView chip(Context context, CharSequence label, boolean selected) {
        TextView chip = text(context, latin(context, label), 14, col(context, R.color.p3r_white), true);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(dp(context, 40));
        chip.setMinimumHeight(dp(context, 40));
        pad(chip, 14, 6, 14, 6);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setAccessibilityDelegate(BUTTON_ROLE);
        setChipSelected(chip, selected);
        return chip;
    }

    /** Restyles a chip for its selected state. */
    public static void setChipSelected(TextView chip, boolean selected) {
        Context context = chip.getContext();
        int skew = dp(context, 6);
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(skew);
        if (selected) {
            bg.fill(col(context, R.color.p3r_cyan_400)).pressedFill(col(context, R.color.p3r_cyan_200))
                .slab(alpha(col(context, R.color.p3r_white), 210), dp(context, 2), dp(context, 2));
            chip.setTextColor(col(context, R.color.p3r_navy_950));
        } else {
            bg.fill(alpha(col(context, R.color.p3r_navy_950), 140))
                .pressedFill(col(context, R.color.p3r_navy_700))
                .stroke(alpha(col(context, R.color.p3r_line), 150), dp(context, 1));
            chip.setTextColor(col(context, R.color.p3r_ice_100));
        }
        chip.setBackground(bg);
        chip.setSelected(selected);
    }

    // ------------------------------------------------------------------ icons and headers

    /** Vector icon tinted to {@code color}. */
    public static ImageView icon(Context context, int drawableRes, int color, int sizeDp) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(tinted(context, drawableRes, color));
        int size = dp(context, sizeDp);
        view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    public static Drawable tinted(Context context, int drawableRes, int color) {
        Drawable drawable = context.getDrawable(drawableRes).mutate();
        drawable.setTint(color);
        return drawable;
    }

    /**
     * Section header: a slanted cyan blade, the bold CJK title and a hairline that runs out to the
     * right and fades, like the underlines of the game's menus; optional muted text at the end.
     */
    public static LinearLayout sectionHeader(Context context, CharSequence title, CharSequence trailing) {
        LinearLayout row = row(context);
        View bar = new View(context);
        bar.setBackground(new SlantedPanelDrawable().fill(col(context, R.color.p3r_cyan_400)).skew(dp(context, 4)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(context, 8), dp(context, 20));
        barParams.rightMargin = dp(context, 10);
        row.addView(bar, barParams);
        TextView label = headline(context, title);
        row.addView(label, wrap());
        View rule = fadingRule(context);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(0, Math.max(1, dp(context, 1)), 1);
        ruleParams.leftMargin = dp(context, 12);
        ruleParams.rightMargin = trailing != null ? dp(context, 12) : 0;
        row.addView(rule, ruleParams);
        if (trailing != null) {
            TextView tail = mixed(context, trailing, 13, col(context, R.color.p3r_ink_muted), false);
            row.addView(tail, wrap());
        }
        pad(row, 0, 8, 0, 8);
        return row;
    }

    /** Hairline in cyan that fades out to the right. */
    public static View fadingRule(Context context) {
        View rule = new View(context);
        rule.setBackground(new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{alpha(col(context, R.color.p3r_cyan_400), 150), alpha(col(context, R.color.p3r_cyan_400), 0)}));
        rule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return rule;
    }

    /**
     * Tab page header in the game's menu style: a small cyan Latin kicker ("SOCIAL LINK") over a
     * large CJK title, a slanted cyan underline that fades to the right, and the kicker repeated as
     * a huge faint outlined watermark behind it. {@code trailing} (may be null) sits at the right
     * of the title line.
     */
    public static android.widget.FrameLayout pageHeader(Context context, CharSequence title, String kicker,
                                                        View trailing) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context);
        frame.setClipChildren(true);
        if (kicker != null) {
            TextView mark = new OutlinedText(context, kicker);
            android.widget.FrameLayout.LayoutParams markParams = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
            markParams.topMargin = -dp(context, 6);
            markParams.rightMargin = -dp(context, 20);
            frame.addView(mark, markParams);
        }
        LinearLayout column = column(context);
        if (kicker != null) {
            TextView en = numeral(context, kicker, 15, col(context, R.color.p3r_cyan_400));
            en.setLetterSpacing(0.14f);
            en.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            en.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            column.addView(en, wrap());
        }
        LinearLayout line = row(context);
        line.setGravity(Gravity.BOTTOM);
        TextView zh = mixed(context, title, 32, col(context, R.color.p3r_white), true);
        zh.setShadowLayer(0.01f, dp(context, 2), dp(context, 2), alpha(col(context, R.color.p3r_royal_500), 200));
        if (Build.VERSION.SDK_INT >= 28) {
            zh.setAccessibilityHeading(true);
        }
        line.addView(zh, weight(1));
        if (trailing != null) {
            line.addView(trailing, wrap());
        }
        column.addView(line, matchWrap());
        View underline = new View(context);
        underline.setBackground(new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{col(context, R.color.p3r_cyan_400), alpha(col(context, R.color.p3r_cyan_400), 0)}));
        underline.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams underlineParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 3));
        underlineParams.topMargin = dp(context, 6);
        underlineParams.rightMargin = dp(context, 48);
        column.addView(underline, underlineParams);
        frame.addView(column, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    /**
     * Title bar of a sub-page: the back chevron on a slanted outline plate, a bold title and a short
     * cyan underline fading to the right.
     */
    public static LinearLayout subPageBar(Context context, CharSequence title, View.OnClickListener onBack) {
        LinearLayout bar = column(context);
        pad(bar, 8, 6, GUTTER, 2);
        LinearLayout line = row(context);
        ImageView back = new ImageView(context);
        back.setImageDrawable(tinted(context, R.drawable.ui_ic_back, col(context, R.color.p3r_white)));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("返回");
        back.setBackground(new SlantedPanelDrawable()
            .fill(alpha(col(context, R.color.p3r_navy_950), 90))
            .pressedFill(col(context, R.color.ui_cyan_soft))
            .stroke(alpha(col(context, R.color.p3r_cyan_300), 160), dp(context, 1.2f))
            .skew(dp(context, 6)));
        back.setOnClickListener(onBack);
        int size = dp(context, TOUCH);
        line.addView(back, new LinearLayout.LayoutParams(size, dp(context, 40)));
        TextView heading = mixed(context, title, 24, col(context, R.color.p3r_white), true);
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        LinearLayout.LayoutParams hp = weight(1);
        hp.leftMargin = dp(context, 12);
        line.addView(heading, hp);
        bar.addView(line, matchWrap());
        View rule = new View(context);
        rule.setBackground(new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{col(context, R.color.p3r_cyan_400), alpha(col(context, R.color.p3r_cyan_400), 0)}));
        rule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 2));
        rp.topMargin = dp(context, 6);
        rp.leftMargin = dp(context, 8);
        rp.rightMargin = dp(context, 64);
        bar.addView(rule, rp);
        return bar;
    }

    /** A huge faint outlined Latin word, rotated like the game's background lettering (decoration). */
    static final class OutlinedText extends TextView {
        OutlinedText(Context context, String word) {
            super(context);
            setText(word);
            setTypeface(numerals(context));
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 84);
            setIncludeFontPadding(false);
            setMaxLines(1);
            // One line, cropped by the header like the game's lettering ("COMPENDIUM" never wraps).
            setHorizontallyScrolling(true);
            setTextColor(alpha(col(context, R.color.p3r_cyan_300), 34));
            getPaint().setStyle(android.graphics.Paint.Style.STROKE);
            getPaint().setStrokeWidth(dp(context, 1.5f));
            setRotation(-8f);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    /**
     * A list item: a small slanted cyan bullet beside {@code line}, centred on its first line
     * (规划说明, the estimates of the detail sheet).
     */
    public static LinearLayout bullet(Context context, TextView line) {
        LinearLayout row = row(context);
        row.setGravity(Gravity.TOP);
        View dot = new View(context);
        dot.setBackground(new SlantedPanelDrawable().fill(col(context, R.color.p3r_cyan_400)).skew(dp(context, 2)));
        int size = dp(context, 6);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size);
        // The middle of a first line of Chinese sits about 0.79 em below the top padding.
        p.topMargin = line.getPaddingTop() + Math.round(line.getTextSize() * 0.79f) - size / 2;
        p.rightMargin = dp(context, 10);
        row.addView(dot, p);
        row.addView(line, weight(1));
        return row;
    }

    /** Thin horizontal divider in the soft line colour. */
    public static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(col(context, R.color.p3r_line_soft));
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1))));
        return line;
    }
}
