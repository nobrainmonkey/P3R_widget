package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    /** Plain text view (CJK-safe: never italic). */
    public static TextView text(Context context, CharSequence value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
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
        return view;
    }

    /** Applies the numeral face to Latin runs inside a mixed string. */
    public static CharSequence latin(Context context, CharSequence value) {
        return NumeralSpan.styleLatinRuns(value, numerals(context), 1.14f);
    }

    public static TextView title(Context context, CharSequence value) {
        return mixed(context, value, 22, col(context, R.color.p3r_white), true);
    }

    public static TextView headline(Context context, CharSequence value) {
        return mixed(context, value, 17, col(context, R.color.p3r_white), true);
    }

    public static TextView body(Context context, CharSequence value) {
        return text(context, value, 15, col(context, R.color.p3r_ice_100), false);
    }

    public static TextView muted(Context context, CharSequence value) {
        return text(context, value, 13, col(context, R.color.p3r_ink_muted), false);
    }

    public static TextView caption(Context context, CharSequence value) {
        return text(context, value, 12, col(context, R.color.p3r_ink_muted), false);
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

    /** Standard card: translucent navy, hairline outline, top-left and bottom-right cuts. */
    public static SlantedPanelDrawable card(Context context) {
        int cut = dp(context, 14);
        return new SlantedPanelDrawable()
            .fill(col(context, R.color.ui_card))
            .stroke(col(context, R.color.p3r_line_soft), dp(context, 1))
            .cuts(cut, 0, cut, 0);
    }

    /** Card that reacts to presses (list rows, menu entries). */
    public static SlantedPanelDrawable pressableCard(Context context) {
        return card(context).pressedFill(col(context, R.color.p3r_navy_700))
            .pressedStroke(col(context, R.color.p3r_cyan_400));
    }

    /** Hero card: royal-to-navy gradient with a cyan accent slash on the left. */
    public static SlantedPanelDrawable heroCard(Context context) {
        int cut = dp(context, 18);
        return new SlantedPanelDrawable()
            .gradient(col(context, R.color.ui_hero_start), col(context, R.color.ui_hero_end))
            .stroke(alpha(col(context, R.color.p3r_cyan_300), 120), dp(context, 1))
            .accent(col(context, R.color.p3r_cyan_400), dp(context, 5))
            .cuts(cut, 0, cut, 0);
    }

    // ------------------------------------------------------------------ buttons

    /** Primary action: cyan parallelogram with a white offset slab and navy ink, ≥ 48 dp tall. */
    public static TextView primary(Context context, CharSequence label) {
        TextView button = baseButton(context, label);
        button.setTextColor(col(context, R.color.p3r_navy_950));
        int skew = dp(context, 10);
        button.setBackground(new SlantedPanelDrawable()
            .fill(col(context, R.color.p3r_cyan_400))
            .pressedFill(col(context, R.color.p3r_cyan_200))
            .skew(skew)
            .slab(col(context, R.color.p3r_white), dp(context, 3), -dp(context, 3)));
        return button;
    }

    /** Secondary action: outlined parallelogram with white ink. */
    public static TextView secondary(Context context, CharSequence label) {
        TextView button = baseButton(context, label);
        button.setTextColor(col(context, R.color.p3r_white));
        int skew = dp(context, 10);
        button.setBackground(new SlantedPanelDrawable()
            .fill(alpha(col(context, R.color.p3r_navy_950), 90))
            .pressedFill(col(context, R.color.p3r_navy_700))
            .stroke(col(context, R.color.p3r_cyan_300), dp(context, 1.5f))
            .skew(skew));
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
        setChipSelected(chip, selected);
        return chip;
    }

    /** Restyles a chip for its selected state. */
    public static void setChipSelected(TextView chip, boolean selected) {
        Context context = chip.getContext();
        int skew = dp(context, 6);
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(skew);
        if (selected) {
            bg.fill(col(context, R.color.p3r_cyan_400)).pressedFill(col(context, R.color.p3r_cyan_200));
            chip.setTextColor(col(context, R.color.p3r_navy_950));
        } else {
            bg.fill(alpha(col(context, R.color.p3r_navy_950), 120))
                .pressedFill(col(context, R.color.p3r_navy_700))
                .stroke(col(context, R.color.p3r_line), dp(context, 1));
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

    /** Section header: bold CJK title with a short slanted cyan bar before it. */
    public static LinearLayout sectionHeader(Context context, CharSequence title, CharSequence trailing) {
        LinearLayout row = row(context);
        View bar = new View(context);
        bar.setBackground(new SlantedPanelDrawable().fill(col(context, R.color.p3r_cyan_400)).skew(dp(context, 3)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(context, 7), dp(context, 18));
        barParams.rightMargin = dp(context, 10);
        row.addView(bar, barParams);
        TextView label = headline(context, title);
        row.addView(label, weight(1));
        if (trailing != null) {
            TextView tail = mixed(context, trailing, 13, col(context, R.color.p3r_ink_muted), false);
            row.addView(tail, wrap());
        }
        pad(row, 0, 8, 0, 8);
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
