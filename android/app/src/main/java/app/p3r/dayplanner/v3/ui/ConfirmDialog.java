package app.p3r.dayplanner.v3.ui;

import android.app.Dialog;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Bottom confirmation dialog in the app's style ("撤销这条记录？" [取消] [撤销]). */
final class ConfirmDialog {
    private ConfirmDialog() {}

    static Dialog show(Context context, String title, String body, String confirmLabel, Runnable onConfirm) {
        LinearLayout content = Ui.column(context);
        Ui.pad(content, 20, 22, 20, 16);
        content.addView(Ui.title(context, title));
        if (body != null) {
            TextView text = Ui.body(context, body);
            text.setLineSpacing(0f, 1.3f);
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(context, 8);
            content.addView(text, p);
        }
        LinearLayout buttons = Ui.row(context);
        TextView cancel = Ui.secondary(context, "取消");
        buttons.addView(cancel, Ui.weight(1));
        TextView ok = Ui.primary(context, confirmLabel);
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
