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
        TEXT.put("ordinary_gain", "普通会面按每次 +10 点估计（游戏里显示 ♪♪ 左右），实际常常更多。");
        TEXT.put("answers_best", "按每次都选让对方高兴的回答来估计升级所需的会面次数。");
        TEXT.put("answers_none", "不计回答带来的额外点数，估计更保守。");
        TEXT.put("persona_bonus", "按你约会时带着同阿尔卡那的人格面具计算，点数 ×1.51。");
        TEXT.put("charm_bonus", "魅力达到 Lv.6 后，社群点数按 ×1.51 计算。");
        TEXT.put("goal_buffer", "目标社群每次升级都多留一次会面的余量。");
        TEXT.put("job_min_pay", "打工收入按最低档工资估计。");
        TEXT.put("tower_income", "塔尔塔罗斯的收入按你最近几次的记录估计。");
        TEXT.put("rescue", "这次进塔要顺便营救失踪者。");
        TEXT.put("invite", "这是假日邀约，接受后会占用白天。");
        TEXT.put("stat_lower_bound", "个人能力值按当前等级的最低点数估计。");
        TEXT.put("hold", "剧情推进前这个社群暂时无法升级，这次只加点数。");
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
        List<String> out = new ArrayList<>();
        out.add("计划从你记录的当前时段开始，排到 1月31日 白天，每个白天和晚上都有安排。");
        out.add("社群好感点数在游戏里看不到，这里一律按保守的下限估计，带「≈」的升级是估计。");
        out.add("普通会面按每次 +10 点估计；升级场景按选最好回答时的点数计算。");
        if (profile != null && profile.personaBonus) {
            out.add("你选择了约会时带着同阿尔卡那的人格面具：点数 ×1.51。");
        } else {
            out.add("默认不计同阿尔卡那人格面具的加成（×1.51），可在「规划设置」里打开。");
        }
        if (profile == null || profile.charmBonus) {
            out.add("预计魅力达到 Lv.6 后，社群点数按 ×1.51 计算。");
        }
        if (profile == null || profile.goalBuffer) {
            out.add("目标社群每次升级多留一次会面的余量，以防估计偏高。");
        }
        out.add("收入只计算打工（按最低档工资）；塔尔塔罗斯的收入默认不计。");
        out.add("课堂答题、考试等剧情里的额外收获不计入预测，回来时用「同步现状」更新即可。");
        out.add("12月24日 默认不接受邀约；1月21日 白天按剧情占用处理。");
        out.add("计划不保证是最优，只保证每一步都合规、能接上后面的安排。");
        return out;
    }
}
