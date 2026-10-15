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
        put("普通见面按每次约 +10 点估计（游戏里大约显示 ♪♪），这是估计值，没有官方数据。",
                "ordinary_gain_estimate", "ordinary_gain");
        put("按升级时都选让对方高兴的回答来估计。", "best_answers", "answers_best");
        TEXT.put("answers_none", "不计升级时回答带来的点数，估计更保守。");
        put("按见面时带着同阿尔卡那的人格面具计算，点数 ×1.51。", "persona_bonus", "persona");
        TEXT.put("charm_bonus", "魅力达到 MAX 后，社群点数按 ×1.51 计算。");
        TEXT.put("goal_buffer", "目标社群每次升级都多留一次见面的余量。");
        put("打工收入按最低档工资估计，实际可能更多。", "job_pay_minimum", "job_min_pay");
        put("塔尔塔罗斯的收入按你最近几次的记录估计。", "tower_income_estimate", "tower_income");
        put("假定这次探索能顺利救出失踪者。", "rescue_on_visit", "rescue");
        TEXT.put("dating_note_gain", "结缘网站的字条按每次 +10 点估计，不计人格面具和魅力加成。");
        TEXT.put("invite", "这是休息日邀约，赴约会占用白天。");
        TEXT.put("stat_lower_bound", "个人能力值按当前等级的最低点数估计。");
        put("剧情推进前这个社群暂时升不了，这次只加点数。", "rank_hold", "hold");
        put("结缘网站的字条加的点数是估计值，而且不会直接升级。", "dating_note", "dating_note_gain", "note_gain");
        put("羁绊剧情不加社群点数，只是按你的选择留出时段。", "episode", "link_episode");
    }

    private static void put(String text, String... ids) {
        for (String id : ids) TEXT.put(id, text);
    }

    /** Generic line for estimates without a known explanation. */
    public static final String GENERIC = "这一步含有估计值，实际结果可能不同。";

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
        out.add("社群点数在游戏里看不到，这里一律按保守的估计值计算，带「≈」的升级是估计。");
        out.add("普通见面按每次 +10 点估计；升级那次按选最好回答时的点数计算。");
        switch (p.effectivePersonaMode()) {
            case ALL:
                out.add("你选了见面时基本都带同阿尔卡那的人格面具：所有社群的点数都按 ×1.51 计算。");
                break;
            case GOALS:
                out.add("你选了只给目标社群带同阿尔卡那的人格面具：目标社群的点数按 ×1.51 计算，其余社群按不带算。"
                    + "安排里会提醒你带哪个阿尔卡那。");
                break;
            default:
                out.add("默认按不带同阿尔卡那的人格面具计算（带着时点数 ×1.51），可在「规划设置」里改。");
                break;
        }
        if (p.charmBonus) {
            out.add("预计魅力达到 MAX 后，社群点数按 ×1.51 计算。");
        }
        if (p.goalBuffer) {
            out.add("目标社群每次升级多留一次见面的余量，以防估计偏高。");
        }
        out.add("每个时段都挑最划算的事：能推进目标的见面优先，其次是打工、学习等有收获的事；"
            + "只有确实没有可做的事时才会留作自由活动。");
        if (p.datingNotes) {
            out.add("结缘网站的字条能给一个已解锁、还没 MAX 的社群加好感，但不会直接升级；买得到之后才会安排。");
        }
        if (p.episodeTracks.length > 0) {
            out.add("排进安排的羁绊剧情会在能看的日子留出时段（可在「社群」里勾选或取消）；它们不加社群点数。");
        }
        out.add("收入只计算打工（按最低档工资）；塔尔塔罗斯的收入默认不计。");
        out.add("课堂提问、上课犯困、保健室、考试名次等带来的能力值不在安排的预计里，回来时用「同步现状」更新就行。");
        out.add("课堂提问和考试的答案、电视购物、伊莉莎白的委托、黄昏的碎片这些不会消耗时间的事，还有失踪者的期限，"
            + "会在「今天别错过」和日程详情的「当天提醒」里提醒。");
        out.add("塔尔塔罗斯按「规划设置 → 塔尔塔罗斯」里每个区块想去几次、最多几次安排，救失踪者也算一次。");
        if (p.christmasLink == null) {
            out.add("12月24日按都婉拒了邀约安排，可在「规划设置 → 平安夜」里改。");
        } else {
            out.add("12月24日" + ChristmasChoice.answerText(rules, p.christmasLink)
                + "：这天白天和晚上都留给约会，不安排别的事。");
        }
        out.add("1月21日放学后先空出来（送过顺平「白色切花」的话会有剧情）。");
        out.add("这不一定是最好的排法，但每一步在游戏里都做得到，前后也接得上。");
        return out;
    }
}
