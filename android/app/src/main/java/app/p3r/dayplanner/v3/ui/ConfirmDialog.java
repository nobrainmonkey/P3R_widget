package app.p3r.dayplanner.v3.ui;

import android.app.Dialog;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * Bottom confirmation dialog in the app's style ("撤销这条记录？" [取消] [撤销]). Something that
 * cannot be taken back ({@link #showDestructive}) gets a red confirm and a quiet 「先不了」.
 */
final class ConfirmDialog {
    /** The quiet way out of a destructive confirmation. */
    static final String NOT_NOW = "先不了";

    private ConfirmDialog() {}

    static Dialog show(Context context, String title, String body, String confirmLabel, Runnable onConfirm) {
        return show(context, title, body, Ui.secondary(context, "取消"), Ui.primary(context, confirmLabel), onConfirm);
    }

    /** Replacing or deleting for good: red confirm with navy ink, cancel as a quiet 「先不了」. */
    static Dialog showDestructive(Context context, String title, String body, String confirmLabel, Runnable onConfirm) {
        return show(context, title, body, Ui.quiet(context, NOT_NOW), destructive(context, confirmLabel), onConfirm);
    }

    /** The destructive action button: a danger-red parallelogram with navy ink and a dark slab. */
    static TextView destructive(Context context, CharSequence label) {
        TextView button = Ui.primary(context, label);
        button.setBackground(new SlantedPanelDrawable()
            .fill(Ui.col(context, R.color.p3r_danger))
            .pressedFill(Ui.col(context, R.color.p3r_ice_100))
            .skew(Ui.dp(context, 10))
            .slab(Ui.col(context, R.color.p3r_navy_950), Ui.dp(context, 4), Ui.dp(context, 4)));
        return button;
    }

    private static Dialog show(Context context, String title, String body, TextView cancel, TextView ok,
                               Runnable onConfirm) {
        LinearLayout content = Ui.column(context);
        Ui.pad(content, 20, 22, 20, 16);
        content.addView(SheetChrome.title(context, title));
        if (body != null) {
            TextView text = Ui.body(context, body);
            text.setLineSpacing(0f, 1.3f);
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(context, 8);
            content.addView(text, p);
        }
        LinearLayout buttons = Ui.row(context);
        buttons.addView(cancel, Ui.weight(1));
        LinearLayout.LayoutParams okParams = Ui.weight(1);
        okParams.leftMargin = Ui.dp(context, 12);
        buttons.addView(ok, okParams);
        LinearLayout.LayoutParams bp = Ui.matchWrap();
        bp.topMargin = Ui.dp(context, 20);
        content.addView(buttons, bp);
        Dialog dialog = Pads.bottomDialog(context, content);
        cancel.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });
        dialog.show();
        return dialog;
    }
}
