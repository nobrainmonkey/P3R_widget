package app.p3r.dayplanner.v3.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * Custom money keypad (no system keyboard): 0–9, 000, 退格, optional 不确定 and ± keys, live
 * thousands separators and the 日元 unit. The first digit typed replaces the prefilled value.
 *
 * <p>Two presentations: a bottom dialog with 确定 ({@link #show}), and an inline keypad embedded in
 * a page that reports every change immediately ({@link #inline}), which saves the player a tap.
 */
public final class CashPad {
    /** Result callback: the amount, or null when the player chose 不确定. */
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
    }

    private static final int MAX_DIGITS = 9;

    private final Dialog dialog;
    private final LinearLayout content;
    private final TextView display;
    private final Listener listener;
    private final boolean inline;
    private final StringBuilder digits = new StringBuilder();
    private boolean negative;
    private boolean replaceOnType;
    private boolean unknown;
    private TextView unknownChip;

    private CashPad(Context context, Options options, Listener listener, boolean inline) {
        this.listener = listener;
        this.inline = inline;
        content = Ui.column(context);
        if (!inline) {
            Ui.pad(content, 20, 20, 20, 16);
            content.addView(Ui.headline(context, options.title));
        }
        if (options.hint != null) {
            TextView hint = Ui.mixed(context, options.hint, 13, Ui.col(context, R.color.p3r_ink_muted), false);
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(context, 4);
            content.addView(hint, p);
        }
        display = Ui.text(context, "", 36, Ui.col(context, R.color.p3r_white), true);
        display.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        display.setMinHeight(Ui.dp(context, 72));
        Ui.pad(display, 16, 4, 20, 4);
        display.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(context, R.color.p3r_navy_950), 170))
            .stroke(Ui.col(context, R.color.p3r_cyan_400), Ui.dp(context, 1.5f))
            .skew(Ui.dp(context, 10)));
        LinearLayout.LayoutParams displayParams = Ui.matchWrap();
        displayParams.topMargin = Ui.dp(context, 12);
        displayParams.bottomMargin = Ui.dp(context, 4);
        content.addView(display, displayParams);

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        for (String[] row : rows) {
            content.addView(Pads.keyRow(context, digitKey(context, row[0]), digitKey(context, row[1]),
                digitKey(context, row[2])));
        }
        View back = Pads.iconKey(context, R.drawable.ui_ic_backspace, "退格");
        back.setOnClickListener(v -> backspace());
        content.addView(Pads.keyRow(context, digitKey(context, "000"), digitKey(context, "0"), back));

        LinearLayout actions = Ui.row(context);
        LinearLayout.LayoutParams actionsParams = Ui.matchWrap();
        actionsParams.topMargin = Ui.dp(context, 16);
        if (options.allowUnknown) {
            unknownChip = inline ? Ui.chip(context, "不确定", false) : Ui.secondary(context, "不确定");
            unknownChip.setOnClickListener(v -> markUnknown());
            actions.addView(unknownChip, inline ? Ui.wrap() : Ui.weight(1));
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
     * 不确定). {@code options.initial} null starts in the 不确定 state.
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
        digits.setLength(0);
        changed();
    }

    /** Current amount (0 when empty). */
    long value() {
        long v = digits.length() == 0 ? 0 : Long.parseLong(digits.toString());
        return negative ? -v : v;
    }

    private void changed() {
        render();
        if (inline && listener != null) {
            listener.onDone(unknown ? null : value());
        }
    }

    private void render() {
        Context context = display.getContext();
        if (unknown) {
            display.setText("不确定");
            display.setTextSize(22);
            display.setTextColor(Ui.col(context, R.color.p3r_cyan_300));
            display.setContentDescription("现金不确定");
        } else {
            long v = value();
            String text = Zh.number(Math.abs(v));
            display.setText(Ui.money(context, v < 0 ? "−" + text : text));
            display.setTextSize(36);
            display.setTextColor(Ui.col(context, R.color.p3r_white));
            display.setContentDescription(Zh.yen(v));
        }
        if (unknownChip != null && inline) {
            Ui.setChipSelected(unknownChip, unknown);
        }
    }

    private void finish(Long result) {
        dialog.dismiss();
        if (listener != null) {
            listener.onDone(result);
        }
    }
}
