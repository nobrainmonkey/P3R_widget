package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 规划说明 and 关于: read-only pages. 规划说明 is the plan at a glance — how many 社群 reach MAX, the
 * span, each goal's forecast, the real problems and the settings it was made with; nothing about how
 * the planner works. 关于 has the name, the version, the sources and the credits.
 */
final class InfoPages {
    private InfoPages() {}

    // ------------------------------------------------------------------ 规划说明

    /**
     * 规划说明: the hero, 目标, 需要注意 (only real problems) and 当前设置. Follows the data
     * ({@link SystemList#follow}): the first plan replaces 「正在安排…」, and a change made in the
     * 规划设置 pushed over it shows when the player comes back.
     */
    static View explain(HomeHost host) {
        LinearLayout column = Ui.column(host.activity());
        // The plan, the progress (by its revision: every snapshot is a fresh copy of it) and the
        // profile (as JSON) the page shows now. The page is rebuilt only when one of them changed,
        // so coming back to the app does not move TalkBack off the row it was reading.
        Object[] shown = new Object[3];
        java.util.function.Consumer<UiData> fill = d -> {
            Long revision = d.snap == null ? null : d.snap.stateRevision;
            String profile = profileKey(d.profile());
            if (column.getChildCount() > 0 && shown[0] == d.plan() && Objects.equals(shown[1], revision)
                && profile != null && profile.equals(shown[2])) {
                return;
            }
            shown[0] = d.plan();
            shown[1] = revision;
            shown[2] = profile;
            column.removeAllViews();
            fillExplain(host, column, d);
        };
        fill.accept(host.data());
        SystemList.follow(column, fill);
        return column;
    }

    /** The profile as JSON text, to tell a changed one; null when it cannot be written. */
    private static String profileKey(Profile p) {
        try {
            return p.toJson().toString();
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static void fillExplain(HomeHost host, LinearLayout column, UiData d) {
        Context c = host.activity();
        Plan plan = d.plan();
        column.addView(hero(c, d, plan));
        List<String> problems = plan == null ? new ArrayList<>() : problems(plan);
        View goals = plan == null ? null : goals(c, d, plan, problems);
        if (goals != null) {
            column.addView(goals, top(c, 24));
        }
        if (!problems.isEmpty()) {
            LinearLayout box = Ui.column(c);
            box.addView(header(c, "需要注意", false));
            for (String text : problems) {
                box.addView(problemLine(c, text), top(c, 8));
            }
            column.addView(box, top(c, 24));
        }
        column.addView(settings(host, d), top(c, 24));
    }

    /** The big number: how many 社群 the plan takes to MAX, its span, and a note when it was rushed. */
    private static View hero(Context c, UiData d, Plan plan) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.heroCard(c));
        Ui.pad(card, 22, 14, 18, 18);
        if (plan == null) {
            card.addView(Ui.headline(c, "正在排到 1月31日…"));
            card.addView(new BusyStripe(c), top(c, 12));
            return card;
        }
        if (PlanDiff.finished(plan)) {
            card.addView(Ui.text(c, "日程全部结束", 22, Ui.col(c, R.color.p3r_white), true));
            return card;
        }
        int maxed = 0;
        for (int r : plan.summary.endRanks) {
            if (r >= 10) {
                maxed++;
            }
        }
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.BOTTOM);
        TextView number = Ui.numeral(c, String.valueOf(maxed), 12, Ui.col(c, R.color.p3r_cyan_400));
        number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 56);
        number.setShadowLayer(0.01f, Ui.dp(c, 2), Ui.dp(c, 2), Ui.alpha(Ui.col(c, R.color.p3r_royal_500), 230));
        number.setPadding(0, 0, Ui.dp(c, 6), Ui.dp(c, 2));
        row.addView(number, Ui.wrap());
        LinearLayout labels = Ui.column(c);
        labels.addView(Ui.tag(c, "预计", Ui.alpha(Ui.col(c, R.color.p3r_cyan_200), 40),
            Ui.col(c, R.color.p3r_cyan_200)), Ui.wrap());
        labels.addView(Ui.mixed(c, "个社群升到 MAX", 17, Ui.col(c, R.color.p3r_white), true), top(c, 4));
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.leftMargin = Ui.dp(c, 8);
        lp.bottomMargin = Ui.dp(c, 8);
        row.addView(labels, lp);
        row.setContentDescription("预计 " + maxed + " 个社群升到 MAX");
        card.addView(row);
        card.addView(Ui.mixed(c, spanLine(d.rules, plan), 14, Ui.col(c, R.color.p3r_ice_100), false), top(c, 6));
        String budget = budgetLine(plan);
        if (budget != null) {
            TextView chip = Ui.tag(c, budget, Ui.alpha(Ui.col(c, R.color.ui_gold), 40), Ui.col(c, R.color.ui_gold));
            chip.setMaxLines(2);
            LinearLayout.LayoutParams cp = Ui.wrap();
            cp.topMargin = Ui.dp(c, 10);
            card.addView(chip, cp);
        }
        return card;
    }

    /** 「从10月22日放学后排到1月31日」. */
    static String spanLine(Rules rules, Plan plan) {
        return "从" + Zh.date(Rules.dayOf(plan.fromSlot)) + Zh.period(rules, plan.fromSlot)
            + "排到" + Zh.date(Rules.dayOf(plan.toSlot));
    }

    /**
     * 目标: one row per goal, the arcana on the left and the forecast on the right (「11月20日 MAX」,
     * 「已经 MAX」); a goal the plan misses is gold, with the plan's own reason under it (taken out of
     * {@code problems}). Null without goals.
     */
    private static View goals(Context c, UiData d, Plan plan, List<String> problems) {
        Profile profile = d.profile();
        List<Integer> order = LinkStatus.goalOrder(profile);
        if (order.isEmpty()) {
            return null;
        }
        SimState obs = d.observed();
        LinearLayout box = Ui.column(c);
        box.addView(header(c, "目标", true));
        for (int link : order) {
            LinkStatus s = LinkStatus.of(d.rules, profile, obs, plan, link);
            String name = d.rules.links[link].arcanaZh;
            String reason = null;
            for (int i = 0; i < problems.size(); i++) {
                String note = problems.get(i);
                if (note.startsWith(name + "：")) {
                    reason = note.substring(name.length() + 1);
                    problems.remove(i);
                    break;
                }
            }
            boolean trouble = goalTrouble(s);
            String value = goalValue(s);
            reason = goalReason(value, reason);
            LinearLayout row = Ui.column(c);
            row.setBackground(new SystemList.Cursor(c, true).noSweep());
            row.setMinimumHeight(Ui.dp(c, 48));
            row.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(row, 4, 8, 4, 9);
            LinearLayout line = Ui.row(c);
            line.addView(Ui.text(c, name, 16, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
            line.addView(Ui.mixed(c, value, 16, Ui.col(c, trouble ? R.color.ui_gold : R.color.p3r_cyan_300), true),
                Ui.wrap());
            row.addView(line, Ui.matchWrap());
            if (reason != null) {
                row.addView(Ui.mixed(c, reason, 13, Ui.col(c, R.color.ui_gold), false), top(c, 2));
            }
            row.setContentDescription(name + "，" + value + (reason == null ? "" : "，" + reason));
            box.addView(row, Ui.matchWrap());
        }
        return box;
    }

    /**
     * The line under a goal: the plan's note without the forecast the row already shows (the value
     * 「停在 RANK 9」 over 「预计停在 RANK 9」, or over 「没排到 MAX，先保证了皇帝 · 预计停在 RANK 9」);
     * null when nothing is left.
     */
    static String goalReason(String value, String note) {
        if (note == null) {
            return null;
        }
        if (value.startsWith("停在 ")) {
            String forecast = "预计" + value;
            if (note.equals(forecast)) {
                return null;
            }
            String tail = " · " + forecast;
            return note.endsWith(tail) ? note.substring(0, note.length() - tail.length()) : note;
        }
        if (note.startsWith(value + "，")) {
            String sub = note.substring(value.length() + 1);
            int idx = sub.indexOf(" · 预计");
            return idx >= 0 ? sub.substring(0, idx) : sub;
        }
        return note;
    }

    /** A goal this plan does not reach (or that cannot continue). */
    static boolean goalTrouble(LinkStatus s) {
        return s.lost || !(s.observed >= s.target || s.targetSlot >= 0 || s.story || s.projected >= s.target);
    }

    /** The forecast of a goal: 「11月20日 MAX」「已经 MAX」「停在 RANK 9」「无法继续」. */
    static String goalValue(LinkStatus s) {
        String goal = s.target >= 10 ? "MAX" : Zh.rank(s.target);
        if (s.observed >= s.target) {
            return s.target >= 10 ? "已经 MAX" : "已到 " + goal;
        }
        if (s.lost) {
            return "无法继续";
        }
        if (s.targetSlot >= 0) {
            return Zh.date(Rules.dayOf(s.targetSlot)) + " " + goal;
        }
        if (s.story) {
            return "跟着剧情升";
        }
        if (s.projected >= s.target) {
            return goal;
        }
        return s.projected > s.observed ? "停在 " + Zh.rank(s.projected) : "没排到 " + goal;
    }

    /** A section title with an optional 「预计」 tag and the fading rule. */
    private static View header(Context c, String title, boolean estimate) {
        LinearLayout line = Ui.row(c);
        line.addView(Ui.headline(c, title), Ui.wrap());
        if (estimate) {
            TextView tag = Ui.tag(c, "预计", Ui.alpha(Ui.col(c, R.color.p3r_cyan_200), 40), Ui.col(c, R.color.p3r_cyan_200));
            LinearLayout.LayoutParams tp = Ui.wrap();
            tp.leftMargin = Ui.dp(c, 10);
            line.addView(tag, tp);
        }
        View rule = Ui.fadingRule(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, Math.max(1, Ui.dp(c, 1)), 1);
        rp.leftMargin = Ui.dp(c, 12);
        line.addView(rule, rp);
        return line;
    }

    /** A gold blade and the problem in plain words. */
    private static View problemLine(Context c, String text) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        TextView line = Ui.mixed(c, text, 14, Ui.col(c, R.color.p3r_ice_100), false);
        line.setLineSpacing(0f, 1.25f);
        View blade = new View(c);
        blade.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.ui_gold)).skew(Ui.dp(c, 2)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(c, 4), Ui.dp(c, 16));
        bp.topMargin = Ui.dp(c, 3);
        bp.rightMargin = Ui.dp(c, 10);
        row.addView(blade, bp);
        row.addView(line, Ui.weight(1));
        return row;
    }

    /** 当前设置: the profile as a few tags, and the way to change it. */
    private static View settings(HomeHost host, UiData d) {
        Context c = host.activity();
        LinearLayout box = Ui.column(c);
        box.addView(header(c, "当前设置", false));
        FlowLayout tags = new FlowLayout(c, 8, 8);
        for (String label : Assumptions.settings(d.profile(), d.rules)) {
            TextView tag = Ui.tag(c, label, Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 34), Ui.col(c, R.color.p3r_ice_100));
            // A long tag at a large font size wraps instead of losing its end.
            tag.setMaxLines(2);
            tags.addView(tag);
        }
        box.addView(tags, top(c, 10));
        TextView go = Ui.quiet(c, "去规划设置 ›");
        go.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        go.setOnClickListener(v -> host.pushPage("规划设置", SettingsPage.build(host)));
        box.addView(go, top(c, 4));
        return box;
    }

    /** How the plan's own note about running out of time starts (engine Labels.BUDGET_NOTE*). */
    static final String BUDGET_PREFIX = "这次没来得及算完";
    /** A note saying the one automatic re-run has already happened: only a new record replans. */
    private static final String RERUN_DONE = "记下一个结果后会重新安排";
    /** The hero's chip while the one automatic re-run is still to come. */
    static final String BUDGET_PENDING = "后半段还在细算，稍后自动更新";
    /** The hero's chip once that re-run happened (or this plan is it). */
    static final String BUDGET_ROUGH = "后半段先粗排，下次记录后再细算";

    /** The hero's chip for a plan whose time budget ran out, or null. */
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

    /** The plan's notes without its time-budget note (the hero's chip already says it). */
    static List<String> notesBelow(Plan plan) {
        List<String> out = new ArrayList<>();
        for (String note : plan.summary.notesZh) {
            if (note != null && !note.startsWith(BUDGET_PREFIX)) {
                out.add(note);
            }
        }
        return out;
    }

    /**
     * 需要注意: the notes that change what the player does — a goal or 羁绊剧情 the plan misses, a
     * 失踪者 not scheduled, too few nights for Tartarus, a last-evening rescue — as the plan words them
     * (its notes are written for this page). Forecasts (the 目标 list says them), settings restated and
     * the time budget are left out.
     */
    static List<String> problems(Plan plan) {
        List<String> out = new ArrayList<>();
        for (String note : notesBelow(plan)) {
            if (isProblem(note)) {
                out.add(note);
            }
        }
        return out;
    }

    /** Whether an engine note is a real problem rather than a forecast or a setting. */
    static boolean isProblem(String note) {
        if (note == null || note.trim().isEmpty()) {
            return false;
        }
        boolean shortfall = note.contains("没排") || note.contains("停在") || note.contains("没法继续")
            || note.contains("过了");
        if (!shortfall && (note.contains("：预计 ") || note.contains("：预计能看完") || note.contains("：已经"))) {
            return false;
        }
        return !(note.startsWith("其余社群：") || note.startsWith("预计有 ") || note.startsWith("优先留足")
            || note.contains("人格面具，点数") || note.contains("多留一次见面") || note.startsWith("普通见面预计")
            || (note.startsWith("安排了 ") && note.contains("结缘网站的字条")));
    }

    /** Whether this plan is the service's one follow-up computation ({@code Plan.followUp}). */
    static boolean isFollowUp(Plan plan) {
        return plan != null && plan.followUp;
    }

    // ------------------------------------------------------------------ 关于

    /** The installed versionName (「3.6.0」), or 「—」 when the package manager cannot say. */
    static String versionName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null || v.isEmpty() ? "—" : v;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unknown) {
            return "—";
        }
    }

    /** 关于: the name on the version band, the privacy line, sources, credits and the font licence. */
    static View about(HomeHost host) {
        Context c = host.activity();
        UiData d = host.data();
        LinearLayout column = Ui.column(c);

        FrameLayout splash = new FrameLayout(c);
        splash.setClipChildren(true);
        TextView mark = new Ui.OutlinedText(c, "S.E.E.S.");
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        mp.topMargin = Ui.dp(c, 4);
        mp.rightMargin = -Ui.dp(c, 24);
        splash.addView(mark, mp);
        LinearLayout head = Ui.column(c);
        TextView name = Ui.text(c, "P3R 规划", 32, Ui.col(c, R.color.p3r_white), true);
        name.setShadowLayer(0.01f, Ui.dp(c, 2), Ui.dp(c, 2), Ui.alpha(Ui.col(c, R.color.p3r_royal_500), 200));
        head.addView(name, Ui.wrap());
        String version = versionName(c);
        TextView band = Ui.numeral(c, version, 12, Ui.col(c, R.color.p3r_navy_950));
        band.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 26);
        band.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_cyan_400))
            .slab(Ui.col(c, R.color.p3r_white), Ui.dp(c, 3), Ui.dp(c, 3)).skew(Ui.dp(c, 10)));
        Ui.pad(band, 16, 4, 22, 6);
        band.setContentDescription("版本 " + version);
        LinearLayout.LayoutParams bp = Ui.wrap();
        bp.topMargin = Ui.dp(c, 8);
        head.addView(band, bp);
        TextView what = Ui.body(c, "为《女神异闻录3 Reload》安排日程、查提示、查人格面具和合体。");
        what.setLineSpacing(0f, 1.3f);
        head.addView(what, top(c, 16));
        head.addView(Ui.text(c, "全部在手机上计算，不联网，不收集数据。", 14, Ui.col(c, R.color.p3r_cyan_200), false),
            top(c, 6));
        splash.addView(head, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(splash, Ui.matchWrap());

        List<String> lines = d == null || d.rules == null ? new ArrayList<>() : sourceLines(d.rules.sources);
        if (!lines.isEmpty()) {
            LinearLayout sources = Ui.column(c);
            sources.addView(header(c, "资料来源", false));
            for (String text : lines) {
                sources.addView(line(c, text), top(c, 4));
            }
            String pack = d.rules.packVersion == null ? "" : d.rules.packVersion.replaceAll("[^0-9.]", "");
            if (!pack.isEmpty()) {
                sources.addView(Ui.caption(c, "数据版本 " + pack), top(c, 6));
            }
            column.addView(sources, top(c, 26));
        }

        LinearLayout credits = Ui.column(c);
        credits.addView(header(c, "全书与合体资料", false));
        credits.addView(sourceLink(c, "aqiu384 · Megaten Database", "https://github.com/aqiu384/megaten-database"),
            top(c, 4));
        credits.addView(sourceLink(c, "aqiu384 · Fusion Tools（Unlicense）", "https://github.com/aqiu384/megaten-fusion-tool"));
        credits.addView(line(c, "人格面具、技能与游戏文本：ATLUS / SEGA。"), top(c, 6));
        column.addView(credits, top(c, 26));

        TextView font = Ui.text(c, "数字字体：Barlow Condensed（SIL 开放字体许可证）。", 12,
            Ui.col(c, R.color.p3r_ink_faint), false);
        column.addView(font, top(c, 32));
        return column;
    }

    private static TextView sourceLink(Context c, String name, String url) {
        TextView text = line(c, name + " ↗");
        text.setTextColor(Ui.col(c, R.color.p3r_cyan_300));
        text.setMinHeight(Ui.dp(c, Ui.TOUCH));
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setOnClickListener(v -> {
            try {
                c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
            } catch (android.content.ActivityNotFoundException ignored) {
                android.widget.Toast.makeText(c, url, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        return text;
    }

    private static TextView line(Context c, String text) {
        TextView view = Ui.mixed(c, text, 14, Ui.col(c, R.color.p3r_ice_100), false);
        view.setLineSpacing(0f, 1.3f);
        Ui.pad(view, 0, 2, 0, 2);
        return view;
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
        List<String> out = new ArrayList<>();
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
