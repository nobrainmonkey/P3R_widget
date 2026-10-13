package app.p3r.dayplanner.v3.ui;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

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

    /** Reason id for "剧情原因暂时升不了" (docs/DESIGN_v3.md §10.2). */
    public static final String REASON_HOLD = "hold";
    /** Reason id for "做了列表以外的事". */
    public static final String REASON_OTHER = "other";

    private Outcomes() {}

    /** Everything went as the plan predicted; visible predicted values are recorded explicitly. */
    public static Outcome asPredicted(Plan.Step step) {
        return asPredicted(step, true);
    }

    /**
     * Everything went as the plan predicted. With {@code planCurrent} false the step comes from a
     * plan that lags behind the latest record, so its visible predictions (rank, stat Lv) may be
     * out of date: they are left out and the engine works the result out from the current state.
     */
    public static Outcome asPredicted(Plan.Step step, boolean planCurrent) {
        Outcome o = Outcome.of(Outcome.Code.AS_PREDICTED);
        if (step == null || step.predicted == null || !planCurrent) {
            return o;
        }
        StepKind kind = StepKind.of(step);
        if (kind.social() || kind == StepKind.UNLOCK) {
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
        return met(step, readyCue, true);
    }

    public static Outcome met(Plan.Step step, boolean readyCue, boolean planCurrent) {
        Outcome o = asPredicted(step, planCurrent);
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

    /** "剧情原因暂时升不了": story hold, the link is frozen until the player reports progress. */
    public static Outcome hold() {
        Outcome o = Outcome.of(Outcome.Code.NOT_DONE);
        o.reason = REASON_HOLD;
        return o;
    }

    /** Attempted but not completed (unlock step "没完成", note "没买到/没用", episode "没看成"). */
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

    /** Marks whether the slot's "顺便" side steps were done too (default true). */
    public static Outcome withSide(Outcome o, boolean sideDone) {
        o.sideDone = sideDone;
        return o;
    }

    /**
     * Sets {@code TowerReturn.sideDone} once the store's contract carries it. Until then the field
     * does not exist and the record keeps the store's default (side steps done).
     *
     * @return true when the value could be recorded
     */
    public static boolean setTowerSideDone(PlannerApi.TowerReturn tower, boolean sideDone) {
        try {
            Field field = PlannerApi.TowerReturn.class.getField("sideDone");
            field.setBoolean(tower, sideDone);
            return true;
        } catch (ReflectiveOperationException | IllegalArgumentException missing) {
            return false;
        }
    }

    /** Whether {@code TowerReturn} can carry the side-step answer (see {@link #setTowerSideDone}). */
    public static boolean towerSideSupported() {
        try {
            return PlannerApi.TowerReturn.class.getField("sideDone").getType() == boolean.class;
        } catch (NoSuchFieldException missing) {
            return false;
        }
    }

    /** The link a social / unlock / note step is about, or null. */
    static Rules.Link linkOf(Rules rules, Plan.Step step) {
        if (rules == null || step == null) {
            return null;
        }
        Action action = Action.parse(rules, step.action);
        if (action != null && (action.type == Action.Type.MEET || action.type == Action.Type.UNLOCK_STEP
            || action.type == Action.Type.NOTE) && action.target >= 0 && action.target < rules.links.length) {
            return rules.links[action.target];
        }
        return step.entity == null ? null : rules.link(step.entity);
    }

    /** The Link Episode track an episode step is about, or null. */
    static Rules.Episode episodeOf(Rules rules, Plan.Step step) {
        if (rules == null || step == null) {
            return null;
        }
        Action action = Action.parse(rules, step.action);
        if (action == null || action.type != Action.Type.EPISODE || action.target < 0
            || action.target >= rules.episodes.length) {
            return null;
        }
        return rules.episodes[action.target];
    }

    /** "羁绊剧情 · 伊织顺平". */
    static String episodeName(Rules.Episode track) {
        return "羁绊剧情 · " + track.personZh;
    }

    /** Short name used in summaries: the arcana for links, else the step title. */
    public static String shortName(Rules rules, Plan.Step step) {
        if (step == null) {
            return "";
        }
        StepKind kind = StepKind.of(step);
        if (kind == StepKind.EPISODE) {
            Rules.Episode track = episodeOf(rules, step);
            if (track != null) {
                return episodeName(track);
            }
        }
        if (kind.social() || kind == StepKind.UNLOCK || kind == StepKind.NOTE) {
            Rules.Link link = linkOf(rules, step);
            if (link != null) {
                return link.arcanaZh;
            }
        }
        String title = step.titleZh == null ? "" : step.titleZh;
        int dot = title.indexOf(" · ");
        return dot > 0 && "social".equals(step.kind) ? title.substring(0, dot) : title;
    }

    /** Undo-bar text after recording, e.g. "皇帝 RANK 7", "恋爱 · 见面", "跳过 · 对方不在". */
    public static String summary(Rules rules, Plan.Step step, Outcome outcome) {
        String text = mainSummary(rules, step, outcome);
        return outcome.sideDone || step == null || step.side.isEmpty() ? text : text + " · 顺便的事没做";
    }

    private static String mainSummary(Rules rules, Plan.Step step, Outcome outcome) {
        String name = shortName(rules, step);
        StepKind kind = StepKind.of(step);
        switch (outcome.code) {
            case RANK_UP:
                return name + " " + Zh.rank(outcome.rank);
            case NO_RANK_UP:
                return name + " · 没升级";
            case SKIPPED:
                if (REASON_OTHER.equals(outcome.reason)) {
                    return "做了列表以外的事";
                }
                String label = SKIP_REASONS.get(outcome.reason);
                return label == null ? "跳过 · " + name : "跳过 · " + label;
            case NOT_DONE:
                if (REASON_HOLD.equals(outcome.reason)) {
                    return name + " · 暂时升不了";
                }
                if (kind == StepKind.NOTE) {
                    return name + " · 没用字条";
                }
                if (kind == StepKind.EPISODE) {
                    return name + " · 没看成";
                }
                return name + " · 没完成";
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
                    return name + " · 见面";
                }
                if (kind == StepKind.NOTE) {
                    return name + " · 用了结缘网站的字条";
                }
                if (kind == StepKind.EPISODE) {
                    return name + " · 看完了";
                }
                return name;
        }
    }
}
