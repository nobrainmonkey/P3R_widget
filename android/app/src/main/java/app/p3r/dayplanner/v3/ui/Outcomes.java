package app.p3r.dayplanner.v3.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Builds the {@link Outcome} each result button records, and the short Chinese summary shown in
 * the undo bar. Pure logic, unit-tested without views.
 */
public final class Outcomes {
    /** Skip reason ids (Outcome.reason) with their button labels, in display order. */
    public static final Map<String, String> SKIP_REASONS = new LinkedHashMap<>();

    static {
        SKIP_REASONS.put("story", "剧情占用了");
        SKIP_REASONS.put("absent", "对方不在");
        SKIP_REASONS.put("closed", "地点没开");
        SKIP_REASONS.put("money", "钱不够");
        SKIP_REASONS.put("unwilling", "我不想做");
    }

    /** Reason id for "游戏提示暂时无法升级" (docs/DESIGN_v3.md §10.2). */
    public static final String REASON_HOLD = "hold";
    /** Reason id for "做了别的事（不影响规划）". */
    public static final String REASON_OTHER = "other";

    private Outcomes() {}

    /** Everything went as the plan predicted; visible predicted values are recorded explicitly. */
    public static Outcome asPredicted(Plan.Step step) {
        Outcome o = Outcome.of(Outcome.Code.AS_PREDICTED);
        if (step == null || step.predicted == null) {
            return o;
        }
        if ("social".equals(step.kind) || "unlock".equals(step.kind)) {
            if (step.predicted.rankTo >= 0) {
                o.rank = step.predicted.rankTo;
            }
        }
        for (int i = 0; i < 3; i++) {
            if (step.predicted.statLvAfter[i] > 0) {
                o.statLv[i] = step.predicted.statLvAfter[i];
            }
        }
        return o;
    }

    /** Ordinary meeting done as planned (no rank-up), optionally with the "下次可升级" cue. */
    public static Outcome met(Plan.Step step, boolean readyCue) {
        Outcome o = asPredicted(step);
        o.readyCue = readyCue;
        return o;
    }

    /** The link reached {@code rank} (rank-up, as planned or unexpectedly). */
    public static Outcome rankUp(int rank) {
        Outcome o = Outcome.of(Outcome.Code.RANK_UP);
        o.rank = rank;
        return o;
    }

    /** The meeting happened without a rank-up. */
    public static Outcome noRankUp(int rankFrom, boolean readyCue) {
        Outcome o = Outcome.of(Outcome.Code.NO_RANK_UP);
        o.rank = rankFrom;
        o.readyCue = readyCue;
        return o;
    }

    /** Rank picked on "其他结果…": up, unchanged or an explicit correction. */
    public static Outcome observedRank(int rankFrom, int observed, boolean readyCue) {
        if (observed > rankFrom) {
            return rankUp(observed);
        }
        if (observed == rankFrom) {
            return noRankUp(rankFrom, readyCue);
        }
        Outcome o = Outcome.of(Outcome.Code.OTHER);
        o.rank = observed;
        return o;
    }

    /** "没见到": the slot passed without meeting the person. */
    public static Outcome notMet() {
        return skipped("absent", -1);
    }

    /** "游戏提示暂时无法升级": story hold, the link is frozen until the player reports progress. */
    public static Outcome hold() {
        Outcome o = Outcome.of(Outcome.Code.NOT_DONE);
        o.reason = REASON_HOLD;
        return o;
    }

    /** Attempted but not completed (unlock step "没完成"). */
    public static Outcome notDone() {
        return Outcome.of(Outcome.Code.NOT_DONE);
    }

    /** Could not do it; {@code cashBalance} ≥ 0 records the balance seen ("钱不够"). */
    public static Outcome skipped(String reason, long cashBalance) {
        Outcome o = Outcome.of(Outcome.Code.SKIPPED);
        o.reason = reason;
        o.cashBalance = cashBalance;
        return o;
    }

    /** Training/job with a different visible result: stat levels and/or balance. */
    public static Outcome different(int[] statLv, long cashBalance) {
        Outcome o = Outcome.of(Outcome.Code.OTHER);
        for (int i = 0; i < 3; i++) {
            o.statLv[i] = statLv == null ? -1 : statLv[i];
        }
        o.cashBalance = cashBalance;
        return o;
    }

    /** Short name used in summaries: the arcana for links, else the step title. */
    public static String shortName(Rules rules, Plan.Step step) {
        if (step == null) {
            return "";
        }
        if (rules != null && step.entity != null && ("social".equals(step.kind) || "unlock".equals(step.kind))) {
            Rules.Link link = rules.link(step.entity);
            if (link != null) {
                return link.arcanaZh;
            }
        }
        String title = step.titleZh == null ? "" : step.titleZh;
        int dot = title.indexOf(" · ");
        return dot > 0 && "social".equals(step.kind) ? title.substring(0, dot) : title;
    }

    /** Undo-bar text after recording, e.g. "皇帝 RANK 7", "皇帝 · 完成会面", "跳过 · 对方不在". */
    public static String summary(Rules rules, Plan.Step step, Outcome outcome) {
        String name = shortName(rules, step);
        StepKind kind = StepKind.of(step);
        switch (outcome.code) {
            case RANK_UP:
                return name + " " + Zh.rank(outcome.rank);
            case NO_RANK_UP:
                return name + " · 没升级";
            case SKIPPED:
                String label = SKIP_REASONS.get(outcome.reason);
                return label == null ? "跳过 · " + name : "跳过 · " + label;
            case NOT_DONE:
                return REASON_HOLD.equals(outcome.reason) ? name + " · 暂时无法升级" : name + " · 没完成";
            case OTHER:
                if (outcome.rank >= 0) {
                    return name + " " + Zh.rank(outcome.rank);
                }
                if (outcome.cashBalance >= 0) {
                    return name + " · 现金 " + Zh.yen(outcome.cashBalance);
                }
                return name + " · 已更正";
            case AS_PREDICTED:
            default:
                if (kind == StepKind.SOCIAL_RANK_UP && outcome.rank > 0) {
                    return name + " " + Zh.rank(outcome.rank);
                }
                if (kind == StepKind.SOCIAL_MEET) {
                    return name + " · 完成会面";
                }
                return name;
        }
    }
}
