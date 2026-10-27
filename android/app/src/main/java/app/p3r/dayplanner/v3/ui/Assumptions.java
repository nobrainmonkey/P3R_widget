package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Plain-Chinese explanations of planning assumptions. Plan steps carry assumption ids; the UI
 * never shows the ids themselves; unknown ids are omitted. {@link #settings} is 规划说明's
 * 「当前设置」: the player's choices as short tags, not an explanation of the planner.
 */
public final class Assumptions {
    private static final Map<String, String> TEXT = new LinkedHashMap<>();

    static {
        // Ids emitted by the engine (Kernel.ASSUMPTION_IDS) and by the UI itself.
        put("普通见面预计好感 +10（约 ♪♪）。",
                "ordinary_gain_estimate", "ordinary_gain");
        put("升级对话按最佳回答安排。", "best_answers", "answers_best");
        TEXT.put("answers_none", "已关闭升级对话的好感加成。");
        put("按见面时带着同阿尔卡那的人格面具计算，点数 ×1.51。", "persona_bonus", "persona");
        TEXT.put("charm_bonus", "魅力达到 MAX 后，社群点数按 ×1.51 计算。");
        TEXT.put("goal_buffer", "目标社群每次升级都多留一次见面的余量。");
        put("打工显示基础工资。", "job_pay_minimum", "job_min_pay");
        put("塔尔塔罗斯的收入按你最近几次的记录估计。", "tower_income_estimate", "tower_income");
        put("这次探索要救出失踪者。", "rescue_on_visit", "rescue");
        TEXT.put("dating_note_gain", "结缘网站的字条预计好感 +10。");
        TEXT.put("invite", "这是休息日邀约，赴约会占用白天。");
        TEXT.put("invite_gain_unmodeled", "休息日邀约只加好感，不直接升级。");
        TEXT.put("stat_lower_bound", "使用已同步的个人能力等级。");
        put("剧情推进前这个社群先升不了，这次只加点数。", "rank_hold", "hold");
        put("结缘网站的字条增加好感，不直接升级。", "dating_note", "dating_note_gain", "note_gain");
        put("已为选中的羁绊剧情留出时段。", "episode", "link_episode");
    }

    private static void put(String text, String... ids) {
        for (String id : ids) TEXT.put(id, text);
    }

    private Assumptions() {}

    /** Explanation for one id, or null when unknown. */
    public static String describe(String id) {
        return id == null ? null : TEXT.get(id);
    }

    /** Explanations for a step's ids; unknown ids have no visible copy. */
    public static List<String> describeAll(List<String> ids) {
        List<String> out = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                String text = describe(id);
                if (text != null && !out.contains(text)) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    /** {@link #describeAll(List)}; {@code estimate} plays no part (the step's one 「预计」 tag says it). */
    public static List<String> describeAll(List<String> ids, boolean estimate) {
        return describeAll(ids);
    }

    /**
     * 规划说明's 「当前设置」 as short tags: 「尽量都体验」 「人格面具：不带」 「平安夜：都婉拒了」
     * 「塔尔塔罗斯：每个区块 2 次」, plus the switches that are on and change what is scheduled.
     */
    public static List<String> settings(Profile profile, Rules rules) {
        Profile p = profile == null ? new Profile() : profile;
        List<String> out = new ArrayList<>();
        int style = Arrays.asList(SettingsPage.STYLES).indexOf(p.style);
        out.add(SettingsPage.STYLE_TEXT[Math.max(0, style)][0]);
        int persona = Arrays.asList(SettingsPage.PERSONA_MODES).indexOf(p.effectivePersonaMode());
        out.add("人格面具：" + SettingsPage.PERSONA_LABELS[Math.max(0, persona)]);
        out.add("平安夜：" + ChristmasChoice.shortAnswer(rules, p.christmasLink));
        out.add("塔尔塔罗斯：" + SettingsPage.towerSummary(rules, p));
        if (p.cashBuffer > 0) {
            out.add("手上至少留 " + Zh.yen(p.cashBuffer));
        }
        if (p.datingNotes && rules != null && rules.datingNote != null) {
            out.add("结缘网站的字条");
        }
        if (p.deepChanges) {
            out.add(SettingsPage.DEEP);
        }
        return out;
    }
}
