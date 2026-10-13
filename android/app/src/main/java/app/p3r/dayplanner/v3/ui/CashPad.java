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
    private final TextView display;
    private final Listener listener;
    private final StringBuilder digits = new StringBuilder();
    private boolean negative;
    private boolean replaceOnType;
    private TextView signKey;

    private CashPad(Context context, Options options, Listener listener) {
        this.listener = listener;
        LinearLayout content = Ui.column(context);
        Ui.pad(content, 20, 20, 20, 16);

        TextView title = Ui.headline(context, options.title);
        content.addView(title);
        if (options.hint != null) {
            TextView hint = Ui.mixed(context, options.hint, 13, Ui.col(context, R.color.p3r_ink_muted), false);
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(context, 4);
            content.addView(hint, p);
        }
        LinearLayout displayRow = Ui.row(context);
        displayRow.setGravity(Gravity.BOTTOM | Gravity.END);
        display = Ui.numeral(context, "0", 44, Ui.col(context, R.color.p3r_white));
        display.setGravity(Gravity.END);
        displayRow.addView(display, Ui.weight(1));
        TextView unit = Ui.text(context, "日元", 16, Ui.col(context, R.color.p3r_cyan_300), true);
        LinearLayout.LayoutParams unitParams = Ui.wrap();
        unitParams.leftMargin = Ui.dp(context, 8);
        unitParams.bottomMargin = Ui.dp(context, 6);
        displayRow.addView(unit, unitParams);
        Ui.pad(displayRow, 12, 8, 12, 8);
        displayRow.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(context, R.color.p3r_navy_950), 170))
            .stroke(Ui.col(context, R.color.p3r_cyan_400), Ui.dp(context, 1.5f))
            .skew(Ui.dp(context, 10)));
        LinearLayout.LayoutParams displayParams = Ui.matchWrap();
        displayParams.topMargin = Ui.dp(context, 12);
        displayParams.bottomMargin = Ui.dp(context, 4);
        content.addView(displayRow, displayParams);

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
            TextView unknown = Ui.secondary(context, "不确定");
            unknown.setOnClickListener(v -> finish(null));
            actions.addView(unknown, Ui.weight(1));
        }
        if (options.allowNegative) {
            signKey = Ui.secondary(context, "±");
            signKey.setContentDescription("切换正负");
            signKey.setOnClickListener(v -> toggleSign());
            actions.addView(signKey, Ui.weight(1));
        }
        TextView ok = Ui.primary(context, options.confirmLabel);
        ok.setOnClickListener(v -> finish(value()));
        LinearLayout.LayoutParams okParams = Ui.weight(1.6f);
        okParams.leftMargin = actions.getChildCount() > 0 ? Ui.dp(context, 12) : 0;
        actions.addView(ok, okParams);
        content.addView(actions, actionsParams);

        if (options.initial != null) {
            long v = options.initial;
            negative = v < 0;
            digits.append(Math.abs(v));
            replaceOnType = true;
        }
        render();
        dialog = Pads.bottomDialog(context, content);
    }

    /** Shows the keypad. */
    public static CashPad show(Context context, Options options, Listener listener) {
        CashPad pad = new CashPad(context, options, listener);
        pad.dialog.show();
        return pad;
    }

    private TextView digitKey(Context context, String label) {
        TextView key = Pads.key(context, label, false);
        key.setOnClickListener(v -> type(label));
        return key;
    }

    /** Types a key ("0"–"9", "000"); exposed for tests. */
    void type(String key) {
        if (replaceOnType) {
            digits.setLength(0);
            replaceOnType = false;
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
        render();
    }

    void backspace() {
        replaceOnType = false;
        if (digits.length() > 0) {
            digits.setLength(digits.length() - 1);
        }
        render();
    }

    private void toggleSign() {
        negative = !negative;
        render();
    }

    /** Current amount (0 when empty). */
    long value() {
        long v = digits.length() == 0 ? 0 : Long.parseLong(digits.toString());
        return negative ? -v : v;
    }

    private void render() {
        long v = value();
        String text = Zh.number(Math.abs(v));
        display.setText(v < 0 ? "−" + text : text);
        display.setContentDescription(Zh.yen(v));
    }

    private void finish(Long result) {
        dialog.dismiss();
        if (listener != null) {
            listener.onDone(result);
        }
    }

    /** The dialog (tests and screenshots). */
    Dialog dialog() {
        return dialog;
    }
}
