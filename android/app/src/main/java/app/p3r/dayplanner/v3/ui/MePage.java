package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * 我的 tab: entry points to 同步现状, 修改进度, 记录历史, 规划设置, 规划说明, 从头重新安排,
 * 备份与恢复, 添加桌面小组件 and 关于.
 */
final class MePage {
    /** Callbacks for the entries that need the activity. */
    interface Actions {
        default void openCompendium() {}

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
        Ui.pad(column, Ui.GUTTER, 8, Ui.GUTTER, 24);
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
        TextView title = Ui.text(c, "我的", 28, Ui.col(c, R.color.p3r_white), true);
        Ui.pad(title, 0, 8, 0, 0);
        column.addView(title);
        String status;
        if (!d.hasState()) {
            status = "还没有设置游戏进度";
        } else if (Slots.finished(d.rules, d.observed())) {
            status = "游戏进度：日程已经全部结束 · 已记录 " + d.snap.eventsSinceCheckpoint + " 条";
        } else {
            status = "游戏进度：" + Zh.slot(d.rules, d.currentSlot()) + " · 已记录 " + d.snap.eventsSinceCheckpoint + " 条";
        }
        column.addView(Ui.mixed(c, status, 13, Ui.col(c, R.color.p3r_ink_muted), false), spaced(4));

        column.addView(Ui.sectionHeader(c, "游戏进度", null), spaced(16));
        boolean has = d.hasState();
        column.addView(entry(R.drawable.ui_ic_sync, "同步现状", "离开一段时间后，对照游戏核对进度",
            has, () -> host.openState("sync")), spaced(4));
        column.addView(entry(R.drawable.ui_ic_edit, has ? "修改进度" : "开始设置",
            has ? "逐项核对日期、现金、个人能力值和社群" : "选目标并填写现在的游戏进度",
            true, () -> host.openState(has ? "edit" : "onboarding")), spaced(8));
        column.addView(entry(R.drawable.ui_ic_history, "记录历史", "撤销或修改之前的记录",
            has, actions::openHistory), spaced(8));

        // "规划" is only the app name and the two menu names below (docs/GLOSSARY_zh.md).
        column.addView(Ui.sectionHeader(c, "安排", null), spaced(16));
        column.addView(entry(R.drawable.ui_ic_settings, "规划设置", "安排风格、人格面具、塔尔塔罗斯、平安夜等",
            true, actions::openSettings), spaced(4));
        column.addView(entry(R.drawable.ui_ic_info, "规划说明", "怎么排的、数据从哪来",
            true, actions::openExplain), spaced(8));
        boolean replanning = FullReplanSheet.running(d);
        column.addView(entry(R.drawable.ui_ic_replan, FullReplanSheet.TITLE,
            replanning ? FullReplanSheet.runningText(d) : FullReplanSheet.SUBTITLE,
            FullReplanSheet.available(d) && !replanning, () -> FullReplanSheet.show(host)), spaced(8));

        column.addView(Ui.sectionHeader(c, "游戏工具", null), spaced(16));
        column.addView(entry(R.drawable.ui_ic_star, "图鉴与合成", "查面具、技能和合成配方",
            true, actions::openCompendium), spaced(4));

        column.addView(Ui.sectionHeader(c, "其他", null), spaced(16));
        column.addView(entry(R.drawable.ui_ic_backup, "备份与恢复", "导出全部记录，或从备份恢复",
            true, actions::openBackup), spaced(4));
        column.addView(entry(R.drawable.ui_ic_widget, "添加桌面小组件", "在桌面上看下一步，点「完成」直接记录",
            true, actions::addWidget), spaced(8));
        column.addView(entry(R.drawable.ui_ic_star, "关于", null, true, actions::openAbout), spaced(8));
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, topDp);
        return p;
    }

    /** A menu entry: icon, title, optional subtitle and a chevron; ≥ 56 dp tall. */
    static LinearLayout entry(Context c, int icon, String title, String subtitle, boolean enabled, Runnable action) {
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, 60));
        row.setBackground(Ui.pressableCard(c));
        Ui.pad(row, 16, 10, 12, 10);
        row.addView(Ui.icon(c, icon, Ui.col(c, R.color.p3r_cyan_400), 22));
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, title, 16, Ui.col(c, R.color.p3r_white), true));
        if (subtitle != null) {
            texts.addView(Ui.text(c, subtitle, 12, Ui.col(c, R.color.p3r_ink_muted), false));
        }
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 14);
        row.addView(texts, tp);
        row.addView(Ui.icon(c, R.drawable.ui_ic_chevron, Ui.col(c, R.color.p3r_ink_faint), 18));
        row.setClickable(true);
        row.setFocusable(true);
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.45f);
        row.setContentDescription(subtitle == null ? title : title + "，" + subtitle);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private LinearLayout entry(int icon, String title, String subtitle, boolean enabled, Runnable action) {
        return entry(c, icon, title, subtitle, enabled, action);
    }
}
