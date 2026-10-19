package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * 「从头重新安排？」: the confirmation sheet behind the 我的 entry and the 规划设置 footer. One action,
 * 「重新安排」, runs the long search from the current progress and settings
 * ({@link HomeHost#requestDeepReplan}); it is measured against the plan in use, so it never ends
 * worse. While it runs the sheet says so and offers 「停下」.
 */
final class FullReplanSheet {
    static final String TITLE = "从头重新安排";
    static final String RUNNING = "正在从头重新安排…";
    static final String SUBTITLE = "按现在的进度重新排一遍";
    /** The sheet's one line: how long, and that recording goes on meanwhile. */
    static final String LINE = "最多半分钟，期间可以照常记录。";
    static final String GO = "重新安排";
    static final String NOT_NOW = "先不了";

    private FullReplanSheet() {}

    /** There is a calendar left to plan: state recorded and the plan not finished. */
    static boolean available(UiData d) {
        return d != null && d.hasState() && !PlanDiff.finished(d.plan()) && !Slots.finished(d.rules, d.observed());
    }

    /** A request is running (or was just made). */
    static boolean running(UiData d) {
        return d != null && d.snap != null && d.snap.fullReplanning;
    }

    /** What a running request is called (an ordinary or a long search alike). */
    static String runningText(UiData d) {
        return RUNNING;
    }

    static void show(HomeHost host) {
        UiData d = host.data();
        if (!available(d)) {
            return;
        }
        Context c = host.activity();
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 20);
        if (running(d)) {
            // Already on its way: what it is doing, and a way to stop it, instead of a second request.
            sheet.addView(SheetChrome.title(c, runningText(d)));
            sheet.addView(new BusyStripe(c), top(c, 10));
            TextView stop = Ui.quiet(c, TodayPage.STOP_LABEL);
            stop.setOnClickListener(v -> {
                host.dismissSheet();
                host.cancelFullReplan();
            });
            LinearLayout.LayoutParams sp = top(c, 12);
            sp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            sheet.addView(stop, sp);
            host.showSheet(sheet);
            return;
        }
        sheet.addView(SheetChrome.title(c, TITLE + "？"));
        TextView line = Ui.mixed(c, LINE, 15, Ui.col(c, R.color.p3r_ice_100), false);
        sheet.addView(line, top(c, 6));
        LinearLayout buttons = Ui.row(c);
        buttons.setBaselineAligned(false);
        TextView later = Ui.secondary(c, NOT_NOW);
        later.setOnClickListener(v -> host.dismissSheet());
        buttons.addView(later, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView go = Ui.primary(c, GO);
        go.setOnClickListener(v -> {
            // One request per sheet, however fast the taps come.
            if (!go.isEnabled()) {
                return;
            }
            go.setEnabled(false);
            later.setEnabled(false);
            host.dismissSheet();
            host.requestDeepReplan();
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f);
        gp.leftMargin = Ui.dp(c, 14);
        buttons.addView(go, gp);
        sheet.addView(buttons, top(c, 22));
        host.showSheet(sheet);
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
