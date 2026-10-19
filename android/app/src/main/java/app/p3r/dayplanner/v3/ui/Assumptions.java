package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.v3.model.Profile;

/**
 * Plain-Chinese explanations of planning assumptions. Plan steps carry assumption ids; the UI
 * never shows the ids themselves — unknown ids collapse into one generic line.
 */
public final class Assumptions {
    private static final Map<String, String> TEXT = new LinkedHashMap<>();

    static {
        // Ids emitted by the engine (Kernel.ASSUMPTION_IDS) and by the UI itself.
        put("普通见面按每次 +10 点好感计算（约 ♪♪）。",
                "ordinary_gain_estimate", "ordinary_gain");
        put("升级事件按选加好感最多的回答计算。", "best_answers", "answers_best");
        TEXT.put("answers_none", "不计升级事件中的回答加成。");
        put("按见面时带着同阿尔卡那的人格面具计算，点数 ×1.51。", "persona_bonus", "persona");
        TEXT.put("charm_bonus", "魅力达到 MAX 后，社群点数按 ×1.51 计算。");
        TEXT.put("goal_buffer", "目标社群每次升级预留一次额外见面。");
        put("打工收入按基础工资计算。", "job_pay_minimum", "job_min_pay");
        put("塔尔塔罗斯的收入按你最近几次的记录计算。", "tower_income_estimate", "tower_income");
        put("假定这次探索能顺利救出失踪者。", "rescue_on_visit", "rescue");
        TEXT.put("dating_note_gain", "结缘网站的字条按每次 +10 点好感计算，不计人格面具和魅力加成。");
        TEXT.put("invite", "这是休息日邀约，赴约会占用白天。");
        TEXT.put("stat_lower_bound", "个人能力值按当前等级的起始点数计算。");
        put("剧情推进前这个社群暂时升不了，这次只加点数。", "rank_hold", "hold");
        put("结缘网站的字条只加好感，不会直接触发升级。", "dating_note", "dating_note_gain", "note_gain");
        put("羁绊剧情不加社群点数，只是按你的选择留出时段。", "episode", "link_episode");
    }

    private static void put(String text, String... ids) {
        for (String id : ids) TEXT.put(id, text);
    }

    /** Generic line for estimates without a known explanation. */
    public static final String GENERIC = "这一步的数值以游戏内实际结果为准。";

    private Assumptions() {}

    /** Explanation for one id, or null when unknown. */
    public static String describe(String id) {
        return id == null ? null : TEXT.get(id);
    }

    /** Explanations for a step's ids; unknown ids become one {@link #GENERIC} line. */
    public static List<String> describeAll(List<String> ids, boolean estimate) {
        List<String> out = new ArrayList<>();
        boolean unknown = false;
        if (ids != null) {
            for (String id : ids) {
                String text = describe(id);
                if (text == null) {
                    unknown = true;
                } else if (!out.contains(text)) {
                    out.add(text);
                }
            }
        }
        if ((unknown || (estimate && out.isEmpty())) && !out.contains(GENERIC)) {
            out.add(GENERIC);
        }
        return out;
    }

    /** The global assumptions for the 规划说明 page, reflecting the player's settings. */
    public static List<String> global(Profile profile) {
        return global(profile, null);
    }

    /** {@link #global(Profile)} with names from the rule pack (平安夜 invitation). */
    public static List<String> global(Profile profile, app.p3r.dayplanner.v3.model.Rules rules) {
        List<String> out = new ArrayList<>();
        Profile p = profile == null ? new Profile() : profile;
        out.add("从你最后记录的时段开始，一直排到1月31日白天，每个白天和晚上都有安排。");
        out.add("游戏内不显示具体好感点数，带「≈」的升级为预估。");
        out.add("普通见面按每次 +10 点计算，升级事件按选加好感最多的回答计算。");
        switch (p.effectivePersonaMode()) {
            case ALL:
                out.add("已选「基本都带」同阿尔卡那的人格面具：所有社群好感按 ×1.51 计算。");
                break;
            case GOALS:
                out.add("已选「只给目标社群带」同阿尔卡那的人格面具：目标社群好感按 ×1.51 计算，见面前会提醒携带。");
                break;
            default:
                out.add("当前按不带同阿尔卡那的人格面具计算（携带时好感 ×1.51），可在「规划设置」里修改。");
                break;
        }
        if (p.charmBonus) {
            out.add("预计魅力达到 MAX 后，社群点数按 ×1.51 计算。");
        }
        if (p.goalBuffer) {
            out.add("目标社群每次升级预留一次额外见面。");
        }
        out.add("优先安排推进目标的社群活动，其余空余时段安排提升个人能力值或打工。");
        if (p.datingNotes) {
            out.add("结缘网站的字条能给一个已解锁、还没 MAX 的社群加好感，但不会直接升级；买得到之后才会安排。");
        }
        if (p.episodeTracks.length > 0) {
            out.add("你勾选的羁绊剧情会在能看的日子留出时段；它们不加社群点数。");
        }
        out.add("收入只计算打工（按基础工资）；塔尔塔罗斯的收入按实际记录更新。");
        out.add("课堂回答、考试名次等额外能力值加成不预先计入，升级后在「同步现状」或「修改进度」中更新即可。");
        out.add("塔尔塔罗斯按「规划设置 → 塔尔塔罗斯」里每个区块想去几次、最多几次安排，救失踪者也算一次。");
        if (p.christmasLink == null) {
            out.add("12月24日按都婉拒了邀约安排，可在「规划设置 → 平安夜」里改。");
        } else {
            out.add("12月24日" + ChristmasChoice.answerText(rules, p.christmasLink)
                + "：这天白天和晚上都留给约会，不安排别的事。");
        }
        if (p.junpeiFlowers) {
            out.add("1月21日放学后先空出来（送过顺平「白色切花」的话会有剧情）。");
        } else {
            out.add("1月21日放学后按没有顺平的「白色切花」剧情正常安排。");
        }
        return out;
    }
}
