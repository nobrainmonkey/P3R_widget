package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 「从头重新安排？」: the confirmation sheet behind the 我的 → 安排 entry and the 规划设置 footer
 * (docs/DESIGN_v3.md §16). Confirming asks the service to re-read the records and settings and
 * compute a new plan without leaning on the current one ({@link HomeHost#requestFullReplan}).
 */
final class FullReplanSheet {
    static final String TITLE = "从头重新安排";
    static final String RUNNING = "正在从头重新安排…";

    private FullReplanSheet() {}

    /** There is a calendar left to plan: state recorded and the plan not finished. */
    static boolean available(UiData d) {
        return d != null && d.hasState() && !PlanDiff.finished(d.plan()) && !Slots.finished(d.rules, d.observed());
    }

    /** A request is running (or was just made). */
    static boolean running(UiData d) {
        return d != null && d.snap != null && d.snap.fullReplanning;
    }

    static String body(Rules rules) {
        return "会重新读取你记下的进度和规划设置，不参考现在的安排，从头排到" + Zh.date(rules.lastDay)
            + "。比平时多算一会儿，一般几秒，最多十来秒。接下来几天的安排可能会变。";
    }

    static void show(HomeHost host) {
        UiData d = host.data();
        if (!available(d)) {
            return;
        }
        Context c = host.activity();
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 20);
        sheet.addView(SheetChrome.title(c, TITLE + "？"));
        // The date in the numeral face, as everywhere else (规划说明 「排到1月31日」, 我的 「10月22日」).
        TextView body = Ui.mixed(c, body(d.rules), 15, Ui.col(c, R.color.p3r_ice_100), false);
        body.setLineSpacing(0f, 1.3f);
        sheet.addView(body, top(c, 8));
        if (running(d)) {
            // Already on its way: what it is doing instead of a second request.
            TextView running = Ui.text(c, RUNNING, 14, Ui.col(c, R.color.p3r_cyan_300), true);
            sheet.addView(running, top(c, 20));
            sheet.addView(new BusyStripe(c), top(c, 6));
            host.showSheet(sheet);
            return;
        }
        LinearLayout buttons = Ui.row(c);
        TextView later = Ui.secondary(c, "先不了");
        later.setOnClickListener(v -> host.dismissSheet());
        buttons.addView(later, Ui.weight(1));
        TextView go = Ui.primary(c, TITLE);
        go.setOnClickListener(v -> {
            // One request per sheet, however fast the taps come.
            if (!go.isEnabled()) {
                return;
            }
            go.setEnabled(false);
            host.dismissSheet();
            host.requestFullReplan();
        });
        LinearLayout.LayoutParams goParams = Ui.weight(1.3f);
        goParams.leftMargin = Ui.dp(c, 12);
        buttons.addView(go, goParams);
        sheet.addView(buttons, top(c, 20));
        host.showSheet(sheet);
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
