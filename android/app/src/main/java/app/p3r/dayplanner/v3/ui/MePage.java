package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * 我的 tab, after the game's SYSTEM menu: the page header, a progress hero like the home-screen
 * widget (the date plate of the next slot, [修改进度] [同步现状]; 「开始设置」 before onboarding), and
 * one flat list: 记录历史, 规划设置, 规划说明, 从头重新安排, 备份与恢复, 添加桌面小组件, 关于 (with the
 * version). Rows that cannot be used are left out instead of dimmed.
 */
final class MePage {
    /** Callbacks for the entries that need the activity. */
    interface Actions {
        void openSettings();

        void openHistory();

        void openBackup();

        void openExplain();

        void addWidget();

        void openAbout();
    }

    private final HomeHost host;
    private final Actions actions;
    private final Context c;
    private final ScrollView scroll;
    private final LinearLayout column;

    MePage(HomeHost host, Actions actions) {
        this.host = host;
        this.actions = actions;
        this.c = host.activity();
        scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        column = Ui.column(c);
        Ui.pad(column, Ui.GUTTER, 12, Ui.GUTTER, 24);
        scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    View view() {
        return scroll;
    }

    void setBottomInset(int px) {
        scroll.setPadding(0, 0, 0, px);
    }

    void bind(UiData d) {
        column.removeAllViews();
        column.addView(Ui.pageHeader(c, "我的", "SYSTEM", null), Ui.matchWrap());
        column.addView(progress(d), spaced(18));

        LinearLayout list = Ui.column(c);
        boolean has = d.hasState();
        if (has) {
            list.addView(SystemList.menuRow(c, R.drawable.ui_ic_history, "记录历史", actions::openHistory));
        }
        list.addView(SystemList.menuRow(c, R.drawable.ui_ic_settings, "规划设置", actions::openSettings));
        if (has) {
            list.addView(SystemList.menuRow(c, R.drawable.ui_ic_info, "规划说明", actions::openExplain));
        }
        View replan = replanRow(c, host, d);
        if (replan != null) {
            list.addView(replan);
        }
        list.addView(SystemList.menuRow(c, R.drawable.ui_ic_backup, "备份与恢复", actions::openBackup));
        list.addView(SystemList.menuRow(c, R.drawable.ui_ic_widget, "添加桌面小组件", actions::addWidget));
        list.addView(SystemList.menuRow(c, R.drawable.ui_ic_star, "关于", actions::openAbout)
            .setValue(InfoPages.versionName(c)));
        column.addView(list, spaced(14));
        // Pushed pages (规划设置, 塔尔塔罗斯) follow what is saved.
        SystemList.dispatch(c, d);
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, topDp);
        return p;
    }

    /**
     * The progress hero: 「游戏进度」 over the date plate of the next slot, then [修改进度] [同步现状];
     * 「还没设置进度」 + [开始设置] before onboarding; 「日程全部结束」 + [修改进度] after Jan 31.
     */
    private View progress(UiData d) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.heroCard(c));
        Ui.pad(card, 22, 14, 18, 18);
        TextView label = Ui.text(c, "游戏进度", 13, Ui.col(c, R.color.p3r_cyan_200), true);
        card.addView(label);
        if (!d.hasState()) {
            card.addView(Ui.text(c, "还没设置进度", 22, Ui.col(c, R.color.p3r_white), true), spaced(6));
            TextView start = Ui.primary(c, "开始设置");
            start.setOnClickListener(v -> host.openState("onboarding"));
            card.addView(start, spaced(16));
            return card;
        }
        LinearLayout buttons = Ui.row(c);
        buttons.setBaselineAligned(false);
        if (Slots.finished(d.rules, d.observed()) && d.snap.storyDisplay == null) {
            card.addView(Ui.text(c, "日程全部结束", 22, Ui.col(c, R.color.p3r_white), true), spaced(6));
            TextView edit = Ui.quiet(c, "修改进度");
            edit.setOnClickListener(v -> host.openState("edit"));
            buttons.addView(edit, Ui.wrap());
            card.addView(buttons, spaced(10));
            return card;
        }
        DatePlate plate = new DatePlate(c, false);
        plate.bind(d.rules, d.displaySlot());
        card.addView(plate, spaced(4));
        card.addView(Ui.text(c, "玩了一阵子？", 14, Ui.col(c, R.color.p3r_ink_muted), false), spaced(12));
        TextView edit = Ui.secondary(c, "修改进度");
        edit.setOnClickListener(v -> host.openState("edit"));
        buttons.addView(edit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView sync = Ui.primary(c, "同步现状");
        sync.setOnClickListener(v -> host.openState("sync"));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        sp.leftMargin = Ui.dp(c, 12);
        buttons.addView(sync, sp);
        card.addView(buttons, spaced(8));
        return card;
    }

    /**
     * The 从头重新安排 row (我的 and the foot of 规划设置): null when there is nothing left to plan;
     * while one runs it says so with the busy stripe and still opens the sheet, which shows it.
     */
    static SystemList.Row replanRow(Context c, HomeHost host, UiData d) {
        boolean running = FullReplanSheet.running(d);
        if (!running && !FullReplanSheet.available(d)) {
            return null;
        }
        SystemList.Row row = SystemList.menuRow(c, R.drawable.ui_ic_replan, FullReplanSheet.TITLE,
            () -> FullReplanSheet.show(host));
        row.setNote(running ? FullReplanSheet.runningText(d) : null);
        row.setBusy(running);
        return row;
    }
}
