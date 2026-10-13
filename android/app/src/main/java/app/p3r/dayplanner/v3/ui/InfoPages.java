package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/** 规划说明 and 关于: read-only explanation pages. */
final class InfoPages {
    private InfoPages() {}

    /** 规划说明: what the 安排 covers, notes, the global assumptions in plain Chinese and sources. */
    static View explain(HomeHost host) {
        Context c = host.activity();
        UiData d = host.data();
        LinearLayout column = Ui.column(c);
        Plan plan = d.plan();
        if (plan != null) {
            column.addView(Ui.sectionHeader(c, "现在的安排", null));
            LinearLayout card = Ui.column(c);
            card.setBackground(Ui.card(c));
            Ui.pad(card, 16, 12, 16, 12);
            if (PlanDiff.finished(plan)) {
                card.addView(line(c, "日程已经全部结束。"));
            } else {
                card.addView(line(c, "从" + Zh.date(Rules.dayOf(plan.fromSlot)) + Zh.period(d.rules, plan.fromSlot)
                    + "排到" + Zh.date(Rules.dayOf(plan.toSlot))));
                String free = plan.summary.freeSlots > 0 ? "，自由活动 " + plan.summary.freeSlots + " 个时段" : "";
                card.addView(line(c, "要做的事 " + plan.summary.usefulActions + " 件，剧情占用 " + plan.summary.lockedSlots
                    + " 个时段" + free));
            }
            int maxed = 0;
            for (int r : plan.summary.endRanks) {
                if (r >= 10) {
                    maxed++;
                }
            }
            card.addView(line(c, "预计到最后 " + maxed + " 个社群升到 MAX"));
            String budget = budgetLine(plan);
            if (budget != null) {
                card.addView(line(c, budget));
            }
            List<String> notes = notesBelow(plan);
            column.addView(card, top(c, 4));
            if (!notes.isEmpty()) {
                column.addView(Ui.sectionHeader(c, "需要注意", null), top(c, 20));
                column.addView(bullets(c, notes), top(c, 4));
            }
        }
        column.addView(Ui.sectionHeader(c, "是怎么排的", null), top(c, plan == null ? 0 : 20));
        column.addView(bullets(c, Assumptions.global(d.profile(), d.rules)), top(c, 4));

        column.addView(Ui.sectionHeader(c, "资料来源", null), top(c, 20));
        LinearLayout sources = Ui.column(c);
        sources.setBackground(Ui.card(c));
        Ui.pad(sources, 16, 12, 16, 12);
        List<String> lines = sourceLines(d.rules.sources);
        if (lines.isEmpty()) {
            sources.addView(line(c, "没有附来源。"));
        }
        for (String text : lines) {
            sources.addView(line(c, text));
        }
        TextView pack = Ui.caption(c, "数据版本 " + d.rules.packVersion.replaceAll("[^0-9.]", ""));
        LinearLayout.LayoutParams pp = Ui.matchWrap();
        pp.topMargin = Ui.dp(c, 8);
        sources.addView(pack, pp);
        column.addView(sources, top(c, 4));
        return column;
    }

    /** How the plan's own note about running out of time starts (engine Labels.BUDGET_NOTE*). */
    static final String BUDGET_PREFIX = "这次没来得及算完";
    /** A note saying the one automatic re-run has already happened: only a new record replans. */
    private static final String RERUN_DONE = "记下一个结果后会重新安排";
    /** 现在的安排 line while the one automatic re-run is still to come. */
    static final String BUDGET_PENDING = "这次没来得及算完，稍后会自动再算一次。";
    /** 现在的安排 line once that re-run happened (or this plan is it). */
    static final String BUDGET_ROUGH = "这次没来得及算完，后半段是粗略安排；记下一个结果后会重新安排。";

    /** The 现在的安排 line of a plan whose time budget ran out, or null. */
    static String budgetLine(Plan plan) {
        if (plan == null || !plan.budgetExhausted) {
            return null;
        }
        return followUpPending(plan.budgetExhausted, isFollowUp(plan), plan.summary.notesZh) ? BUDGET_PENDING : BUDGET_ROUGH;
    }

    /**
     * Whether the service will still compute this plan once more by itself: its time ran out, it is
     * not itself that second computation ({@code followUp}), and none of its notes says the re-run
     * already happened (「…记下一个结果后会重新安排」). Never promises a run that will not come.
     */
    static boolean followUpPending(boolean budgetExhausted, boolean followUp, List<String> notes) {
        if (!budgetExhausted || followUp) {
            return false;
        }
        for (String note : notes) {
            if (note != null && note.startsWith(BUDGET_PREFIX) && note.contains(RERUN_DONE)) {
                return false;
            }
        }
        return true;
    }

    /** The plan's notes for 需要注意, without its time-budget note (the card above already says it). */
    static List<String> notesBelow(Plan plan) {
        List<String> out = new java.util.ArrayList<>();
        for (String note : plan.summary.notesZh) {
            if (!(plan.budgetExhausted && note != null && note.startsWith(BUDGET_PREFIX))) {
                out.add(note);
            }
        }
        return out;
    }

    /** Whether this plan is the service's one follow-up computation ({@code Plan.followUp}). */
    static boolean isFollowUp(Plan plan) {
        return plan != null && plan.followUp;
    }

    /** The installed versionName (「3.0.1」), or 「—」 when the package manager cannot say. */
    static String versionName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null || v.isEmpty() ? "—" : v;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unknown) {
            return "—";
        }
    }

    /** 关于. */
    static View about(HomeHost host) {
        Context c = host.activity();
        LinearLayout column = Ui.column(c);
        TextView name = Ui.text(c, "P3R 规划", 26, Ui.col(c, R.color.p3r_white), true);
        column.addView(name);
        column.addView(Ui.mixed(c, "版本 " + versionName(c), 14, Ui.col(c, R.color.p3r_cyan_300), false), top(c, 4));
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 16, 12);
        card.addView(line(c, "这是玩家自制的离线小工具，按你的目标为《女神异闻录3 Reload》安排每天的白天和晚上。"));
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

    /** Chinese name of a source kind, or null for kinds that are not listed. */
    static String sourceKind(String kind) {
        switch (kind == null ? "" : kind) {
            case "game_data":
                return "游戏数据";
            case "community_db":
                return "玩家整理的数据库";
            case "cn_guide":
                return "中文攻略";
            case "guide":
                return "攻略";
            case "official_text":
                return "官方文本";
            case "wiki":
                return "资料站";
            case "web":
            case "forum":
                return "网络资料";
            default:
                return null;
        }
    }

    /**
     * One line per kind of source with its count ("游戏数据 · 10 项"), in the order the kinds first
     * appear. Entries without a link (internal evidence notes) are not listed.
     */
    static List<String> sourceLines(List<Rules.Source> sources) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Rules.Source source : sources) {
            String kind = sourceKind(source.kind);
            if (source.url == null || source.url.trim().isEmpty()) {
                continue;
            }
            String name = kind == null ? "其他资料" : kind;
            counts.merge(name, 1, Integer::sum);
        }
        List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            out.add(e.getKey() + " · " + e.getValue() + " 项");
        }
        return out;
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
