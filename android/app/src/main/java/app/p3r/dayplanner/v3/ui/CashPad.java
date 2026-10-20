package app.p3r.dayplanner.v3.ui;

import android.app.Dialog;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * Custom number keypad (no system keyboard): 0–9, 000, 退格, optional 不记得 and ± keys, live
 * thousands separators and a unit (日元 by default). A prefilled value is shown selected (a cyan
 * highlight): the first digit typed replaces it.
 *
 * <p>Two presentations: a bottom dialog with 确定 ({@link #show}), and an inline keypad embedded in
 * a page that reports every change immediately ({@link #inline}), which saves the player a tap.
 */
public final class CashPad {
    /** Result callback: the amount, or null when the player chose 不记得. */
    public interface Listener {
        void onDone(Long value);
    }

    /** Presentation options. */
    public static final class Options {
        public String title = "现金";
        public String hint;
        public Long initial;
        public boolean allowUnknown = true;
        public boolean allowNegative;
        public String confirmLabel = "确定";
        /** Unit after the number ("日元", "F"); null or empty for none. */
        public String unit = "日元";
        /** Largest value that can be typed (null: {@link #MAX_DIGITS} digits). */
        public Long max;
        /** Offer the 「000」 key (money); a small count gets a plain 0 key instead. */
        public boolean tripleZero = true;
        /** Small slanted tag beside a prefilled value (「预计」), dropped once the player types. */
        public CharSequence badge;
        /** Money known as a lower bound, shown dim while the amount is 不记得 (「290+ 日元」, inline only). */
        public Long lowerBound;
    }

    /** The answer for an amount the player does not remember. */
    static final String UNKNOWN_LABEL = "不记得";

    private static final int MAX_DIGITS = 9;

    private final Dialog dialog;
    private final LinearLayout content;
    private final TextView display;
    private final View badge;
    private final Listener listener;
    private final boolean inline;
    private final String unit;
    private final Long max;
    private final long lowerBound;
    private final StringBuilder digits = new StringBuilder();
    private boolean negative;
    private boolean replaceOnType;
    private boolean unknown;
    /** Still showing what the page prefilled (the badge goes with it). */
    private boolean prefilled;
    private TextView unknownButton;

    private CashPad(Context context, Options options, Listener listener, boolean inline) {
        this.listener = listener;
        this.inline = inline;
        this.unit = options.unit == null ? "" : options.unit;
        this.max = options.max;
        this.lowerBound = options.lowerBound == null ? 0 : Math.max(0, options.lowerBound);
        content = Ui.column(context);
        if (!inline) {
            Ui.pad(content, 20, 20, 20, 16);
            if (options.title != null) {
                content.addView(SheetChrome.title(context, options.title));
            }
        }
        if (options.hint != null) {
            TextView hint = Ui.mixed(context, options.hint, 13, Ui.col(context, R.color.p3r_ink_muted), false);
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(context, 4);
            content.addView(hint, p);
        }

        // The amount: a big numeral, right-aligned over a cyan hairline. The tag sits at the left in
        // a column of its own; a long amount (small phone, large text) is drawn smaller beside it.
        LinearLayout field = Ui.row(context);
        if (options.badge != null) {
            badge = Ui.tag(context, options.badge, Ui.alpha(Ui.col(context, R.color.p3r_cyan_200), 40),
                Ui.col(context, R.color.p3r_cyan_200));
            LinearLayout.LayoutParams bp = Ui.wrap();
            bp.rightMargin = Ui.dp(context, 8);
            field.addView(badge, bp);
        } else {
            badge = null;
        }
        display = Ui.text(context, "", 40, Ui.col(context, R.color.p3r_white), true);
        display.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Ui.pad(display, 12, 2, 12, 2);
        field.addView(new AmountSlot(context, display), Ui.weight(1));
        field.setMinimumHeight(Ui.dp(context, 68));
        LinearLayout.LayoutParams fieldParams = Ui.matchWrap();
        fieldParams.topMargin = Ui.dp(context, inline ? 4 : 12);
        content.addView(field, fieldParams);
        View rule = Ui.fadingRule(context);
        rule.setScaleX(-1f);
        content.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Math.max(1, Ui.dp(context, 2))));

        if (inline && options.allowUnknown) {
            // 「不记得」: a quiet text action under the amount, not a key.
            unknownButton = Ui.quiet(context, UNKNOWN_LABEL);
            unknownButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            unknownButton.setOnClickListener(v -> markUnknown());
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            up.gravity = Gravity.END;
            content.addView(unknownButton, up);
        }

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        for (int i = 0; i < rows.length; i++) {
            String[] row = rows[i];
            LinearLayout keys = Pads.keyRow(context, digitKey(context, row[0]), digitKey(context, row[1]),
                digitKey(context, row[2]));
            if (i == 0 && inline && !options.allowUnknown) {
                ((LinearLayout.LayoutParams) keys.getLayoutParams()).topMargin = Ui.dp(context, 14);
            }
            content.addView(keys);
        }
        View back = Pads.iconKey(context, R.drawable.ui_ic_backspace, "退格");
        back.setOnClickListener(v -> backspace());
        View first = options.tripleZero ? digitKey(context, "000") : new View(context);
        content.addView(Pads.keyRow(context, first, digitKey(context, "0"), back));

        LinearLayout actions = Ui.row(context);
        LinearLayout.LayoutParams actionsParams = Ui.matchWrap();
        actionsParams.topMargin = Ui.dp(context, 16);
        if (!inline && options.allowUnknown) {
            unknownButton = Ui.secondary(context, UNKNOWN_LABEL);
            unknownButton.setOnClickListener(v -> markUnknown());
            actions.addView(unknownButton, Ui.weight(1));
        }
        if (options.allowNegative) {
            TextView sign = Ui.secondary(context, "±");
            sign.setContentDescription("切换正负");
            sign.setOnClickListener(v -> toggleSign());
            LinearLayout.LayoutParams sp = Ui.weight(1);
            sp.leftMargin = actions.getChildCount() > 0 ? Ui.dp(context, 12) : 0;
            actions.addView(sign, sp);
        }
        if (!inline) {
            TextView ok = Ui.primary(context, options.confirmLabel);
            ok.setOnClickListener(v -> finish(value()));
            LinearLayout.LayoutParams okParams = Ui.weight(1.6f);
            okParams.leftMargin = actions.getChildCount() > 0 ? Ui.dp(context, 12) : 0;
            actions.addView(ok, okParams);
        }
        if (actions.getChildCount() > 0) {
            content.addView(actions, actionsParams);
        }

        if (options.initial != null) {
            long v = options.initial;
            negative = v < 0;
            digits.append(Math.abs(v));
            replaceOnType = true;
        } else {
            unknown = inline && options.allowUnknown;
        }
        prefilled = options.initial != null || (unknown && lowerBound > 0);
        render();
        dialog = inline ? null : Pads.bottomDialog(context, content);
    }

    /** Shows the keypad as a bottom dialog with 确定. */
    public static CashPad show(Context context, Options options, Listener listener) {
        CashPad pad = new CashPad(context, options, listener, false);
        pad.dialog.show();
        return pad;
    }

    /**
     * An inline keypad view; {@code listener} receives the amount after every key (null after
     * 不记得). {@code options.initial} null starts in the 不记得 state.
     */
    public static View inline(Context context, Options options, Listener listener) {
        return new CashPad(context, options, listener, true).content;
    }

    private TextView digitKey(Context context, String label) {
        TextView key = Pads.key(context, label, false);
        key.setOnClickListener(v -> type(label));
        return key;
    }

    /** Types a key ("0"–"9", "000"). */
    void type(String key) {
        if (replaceOnType || unknown) {
            digits.setLength(0);
            replaceOnType = false;
            unknown = false;
        }
        for (char c : key.toCharArray()) {
            if (digits.length() == 0 && c == '0') {
                continue;
            }
            if (digits.length() >= MAX_DIGITS) {
                break;
            }
            digits.append(c);
            if (max != null && Long.parseLong(digits.toString()) > max) {
                digits.setLength(0);
                digits.append(max);
                break;
            }
        }
        changed();
    }

    void backspace() {
        replaceOnType = false;
        unknown = false;
        if (digits.length() > 0) {
            digits.setLength(digits.length() - 1);
        }
        changed();
    }

    private void toggleSign() {
        negative = !negative;
        changed();
    }

    private void markUnknown() {
        if (!inline) {
            finish(null);
            return;
        }
        unknown = true;
        replaceOnType = false;
        digits.setLength(0);
        changed();
    }

    /** Current amount (0 when empty). */
    long value() {
        long v = digits.length() == 0 ? 0 : Long.parseLong(digits.toString());
        return negative ? -v : v;
    }

    /** What the 不记得 state or an amount looks like (tests). */
    CharSequence displayText() {
        return Zh.plain(display.getText());
    }

    private void changed() {
        prefilled = false;
        render();
        if (inline && listener != null) {
            listener.onDone(unknown ? null : value());
        }
    }

    private void render() {
        Context context = display.getContext();
        if (badge != null && !prefilled) {
            badge.setVisibility(View.GONE);
        }
        if (unknown) {
            // A dim 「— 日元」, or the known lower bound 「290+ 日元」; never the word 不确定 in the field.
            boolean bounded = lowerBound > 0;
            display.setText(amount(context, bounded ? Zh.number(lowerBound) + "+" : "—"));
            display.setTextColor(Ui.col(context, R.color.p3r_ink_faint));
            display.setBackground(null);
            display.setContentDescription(bounded ? "现金至少 " + Zh.yen(lowerBound) : "现金" + UNKNOWN_LABEL);
        } else {
            long v = value();
            String text = Zh.number(Math.abs(v));
            display.setText(amount(context, v < 0 ? "−" + text : text));
            display.setTextColor(Ui.col(context, R.color.p3r_white));
            // A prefilled amount is drawn selected: typing replaces it.
            display.setBackground(replaceOnType ? new SlantedPanelDrawable()
                .fill(Ui.col(context, R.color.ui_cyan_soft)).skew(Ui.dp(context, 8)) : null);
            display.setContentDescription(unit.isEmpty() ? String.valueOf(v)
                : "日元".equals(unit) ? Zh.yen(v) : v + unit);
        }
        if (unknownButton != null && inline) {
            unknownButton.setSelected(unknown);
            unknownButton.setTextColor(Ui.col(context, unknown ? R.color.p3r_white : R.color.p3r_cyan_300));
        }
    }

    /** "25,050 日元": the number in the numeral face, the unit smaller, upright and cyan. */
    private CharSequence amount(Context context, String number) {
        SpannableStringBuilder sb = new SpannableStringBuilder(Ui.latin(context, number));
        if (!unit.isEmpty()) {
            // A Latin unit joins the number in its face ("96F"); a Chinese one stays upright ("2,500 日元").
            boolean latin = unit.chars().allMatch(ch -> ch < 0x80);
            int start = sb.length();
            if (latin) {
                sb.append(Ui.latin(context, unit));
            } else {
                sb.append(" ").append(unit);
            }
            sb.setSpan(new RelativeSizeSpan(latin ? 0.6f : 0.45f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Ui.col(context, R.color.p3r_cyan_300)), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private void finish(Long result) {
        dialog.dismiss();
        if (listener != null) {
            listener.onDone(result);
        }
    }

    /**
     * Holds the amount at the right of the field. An amount wider than the room (a small phone at
     * large text) is drawn smaller to stay on one line: the number and its unit never break apart,
     * and the field keeps its height, so the keys below do not move while digits are typed.
     */
    private static final class AmountSlot extends ViewGroup {
        private final TextView amount;

        AmountSlot(Context context, TextView amount) {
            super(context);
            this.amount = amount;
            addView(amount);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            amount.measure(any, any);
            int w = amount.getMeasuredWidth();
            int h = amount.getMeasuredHeight();
            int room = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? w : MeasureSpec.getSize(widthSpec);
            float scale = Math.min(1f, (float) room / Math.max(1, w));
            amount.setPivotX(w);
            amount.setPivotY(h / 2f);
            amount.setScaleX(scale);
            amount.setScaleY(scale);
            setMeasuredDimension(resolveSize(Math.min(w, room), widthSpec), resolveSize(h, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // Right-aligned and centred; a scaled amount shrinks towards its right edge.
            int w = amount.getMeasuredWidth();
            int h = amount.getMeasuredHeight();
            int left = r - l - w;
            int top = (b - t - h) / 2;
            amount.layout(left, top, left + w, top + h);
        }
    }
}
