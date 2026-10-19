package app.p3r.dayplanner.v3.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/** Shared plumbing for the bottom keypad dialogs ({@link CashPad}, {@link RankPad}). */
final class Pads {
    private Pads() {}

    /** Creates a full-width bottom dialog hosting {@code content} on the slanted sheet surface. */
    static Dialog bottomDialog(Context context, View content) {
        Dialog dialog = new Dialog(context, R.style.P3R_UiDialog);
        FrameLayout frame = new FrameLayout(context);
        int cut = Ui.dp(context, 24);
        content.setBackground(new SlantedPanelDrawable()
            .fill(Ui.col(context, R.color.ui_sheet))
            .stroke(Ui.alpha(Ui.col(context, R.color.p3r_cyan_400), 160), Ui.dp(context, 1))
            .cuts(cut, 0, 0, 0));
        frame.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        dialog.setContentView(frame);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
        return dialog;
    }

    /** One keypad key: a slanted tile with a numeral or CJK label, ≥ 52 dp tall. */
    static TextView key(Context context, CharSequence label, boolean emphasised) {
        TextView key = Ui.text(context, Ui.latin(context, label), 22, Ui.col(context, R.color.p3r_white), true);
        key.setGravity(Gravity.CENTER);
        key.setMinHeight(Ui.dp(context, 52));
        key.setClickable(true);
        key.setFocusable(true);
        int skew = Ui.dp(context, 8);
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(skew);
        if (emphasised) {
            bg.fill(Ui.col(context, R.color.p3r_royal_600)).pressedFill(Ui.col(context, R.color.p3r_royal_500));
        } else {
            bg.fill(Ui.alpha(Ui.col(context, R.color.p3r_navy_950), 150))
                .pressedFill(Ui.col(context, R.color.p3r_navy_700))
                .stroke(Ui.col(context, R.color.p3r_line_soft), Ui.dp(context, 1));
        }
        key.setBackground(bg);
        return key;
    }

    /** Icon key (退格). */
    static ImageView iconKey(Context context, int icon, String description) {
        ImageView key = new ImageView(context);
        key.setImageDrawable(Ui.tinted(context, icon, Ui.col(context, R.color.p3r_white)));
        key.setScaleType(ImageView.ScaleType.CENTER);
        key.setContentDescription(description);
        key.setMinimumHeight(Ui.dp(context, 52));
        key.setClickable(true);
        key.setFocusable(true);
        key.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(context, R.color.p3r_navy_950), 150))
            .pressedFill(Ui.col(context, R.color.p3r_navy_700))
            .stroke(Ui.col(context, R.color.p3r_line_soft), Ui.dp(context, 1))
            .skew(Ui.dp(context, 8)));
        return key;
    }

    /** A row of equally wide keys with 8 dp gaps. */
    static LinearLayout keyRow(Context context, View... keys) {
        LinearLayout row = Ui.row(context);
        for (int i = 0; i < keys.length; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(context, 52), 1);
            if (i > 0) {
                params.leftMargin = Ui.dp(context, 8);
            }
            row.addView(keys[i], params);
        }
        LinearLayout.LayoutParams rowParams = Ui.matchWrap();
        rowParams.topMargin = Ui.dp(context, 8);
        row.setLayoutParams(rowParams);
        return row;
    }
}
