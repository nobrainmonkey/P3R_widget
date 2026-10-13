package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/** 规划说明 and 关于: read-only explanation pages. */
final class InfoPages {
    private InfoPages() {}

    /** 规划说明: the plan's coverage, notes, the global assumptions in plain Chinese and sources. */
    static View explain(HomeHost host) {
        Context c = host.activity();
        UiData d = host.data();
        LinearLayout column = Ui.column(c);
        Plan plan = d.plan();
        if (plan != null) {
            column.addView(Ui.sectionHeader(c, "这份计划", null));
            LinearLayout card = Ui.column(c);
            card.setBackground(Ui.card(c));
            Ui.pad(card, 16, 12, 16, 12);
            card.addView(line(c, "覆盖 " + Zh.slot(d.rules, plan.fromSlot) + " → " + Zh.date(Rules.dayOf(plan.toSlot))));
            card.addView(line(c, "有效安排 " + plan.summary.usefulActions + " 项，剧情占用 " + plan.summary.lockedSlots
                + " 个时段，空闲 " + plan.summary.freeSlots + " 个时段"));
            int maxed = 0;
            for (int r : plan.summary.endRanks) {
                if (r >= 10) {
                    maxed++;
                }
            }
            card.addView(line(c, "预计到最后 " + maxed + " 个社群 MAX"));
            if (plan.budgetExhausted) {
                card.addView(line(c, "这次计算用完了时间，后半段按基础规则排，稍后会自动再算。"));
            }
            column.addView(card, top(c, 4));
            if (!plan.summary.notesZh.isEmpty()) {
                column.addView(Ui.sectionHeader(c, "需要注意", null), top(c, 20));
                column.addView(bullets(c, plan.summary.notesZh), top(c, 4));
            }
        }
        column.addView(Ui.sectionHeader(c, "怎么估算的", null), top(c, plan == null ? 0 : 20));
        column.addView(bullets(c, Assumptions.global(d.profile())), top(c, 4));

        column.addView(Ui.sectionHeader(c, "资料来源", null), top(c, 20));
        LinearLayout sources = Ui.column(c);
        sources.setBackground(Ui.card(c));
        Ui.pad(sources, 16, 12, 16, 12);
        if (d.rules.sources.isEmpty()) {
            sources.addView(line(c, "规则包未附来源列表。"));
        }
        for (Rules.Source source : d.rules.sources) {
            sources.addView(sourceLine(c, source));
        }
        TextView pack = Ui.caption(c, "规则包版本 " + d.rules.packVersion.replaceAll("[^0-9.]", ""));
        LinearLayout.LayoutParams pp = Ui.matchWrap();
        pp.topMargin = Ui.dp(c, 8);
        sources.addView(pack, pp);
        column.addView(sources, top(c, 4));
        return column;
    }

    /** 关于. */
    static View about(HomeHost host) {
        Context c = host.activity();
        LinearLayout column = Ui.column(c);
        TextView name = Ui.text(c, "P3R 规划", 26, Ui.col(c, R.color.p3r_white), true);
        column.addView(name);
        column.addView(Ui.mixed(c, "版本 3.0.0", 14, Ui.col(c, R.color.p3r_cyan_300), false), top(c, 4));
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 16, 12);
        card.addView(line(c, "这是玩家自制的离线规划工具，按你的目标为《女神异闻录3 Reload》安排每天的白天和晚上。"));
        card.addView(line(c, "与 ATLUS、世嘉没有任何关联。游戏名称与角色名称归各自权利人所有。"));
        card.addView(line(c, "全部计算都在手机上完成，不联网，不收集任何数据。"));
        card.addView(line(c, "数字字体：Barlow Condensed（SIL 开放字体许可证）。"));
        column.addView(card, top(c, 16));
        return column;
    }

    private static TextView line(Context c, String text) {
        TextView view = Ui.mixed(c, text, 14, Ui.col(c, R.color.p3r_ice_100), false);
        view.setLineSpacing(0f, 1.3f);
        Ui.pad(view, 0, 4, 0, 4);
        return view;
    }

    private static View bullets(Context c, List<String> lines) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 10, 16, 10);
        for (String text : lines) {
            LinearLayout row = Ui.row(c);
            row.setGravity(android.view.Gravity.TOP);
            View dot = new View(c);
            dot.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_cyan_400)).skew(Ui.dp(c, 2)));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(c, 6), Ui.dp(c, 6));
            dp.topMargin = Ui.dp(c, 12);
            dp.rightMargin = Ui.dp(c, 10);
            row.addView(dot, dp);
            row.addView(line(c, text), Ui.weight(1));
            card.addView(row);
        }
        return card;
    }

    private static View sourceLine(Context c, Rules.Source source) {
        String kind;
        switch (source.kind == null ? "" : source.kind) {
            case "game_data":
                kind = "游戏数据";
                break;
            case "guide":
                kind = "攻略";
                break;
            case "wiki":
                kind = "资料站";
                break;
            default:
                kind = "参考资料";
                break;
        }
        String host = "";
        try {
            host = source.url == null ? "" : Uri.parse(source.url).getHost();
        } catch (RuntimeException ignored) {
            host = "";
        }
        String text = kind + (host == null || host.isEmpty() ? "" : " · " + host);
        return line(c, text);
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
