package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Per-link facts for the 社群 tiles, the 社群详情 sheet and the 今天 goal lines: confirmed rank,
 * forecast end rank, when the target is reached, and the one fact that changes what the player
 * does when it is not (the goal the time went to, or a rescue to record).
 */
public final class LinkStatus {
    public final int link;
    /** Rank the player confirmed (0 = 未解锁). */
    public final int observed;
    /** Forecast rank at the end of the plan. */
    public final int projected;
    /** Target rank (10 = MAX). */
    public final int target;
    /** Slot the target is reached, -1 if never within the plan. */
    public final int targetSlot;
    public final boolean story;
    public final boolean lost;
    /** The link is a goal (想要 / 必须). */
    public final boolean goal;
    /** True when there is a plan to forecast from. */
    public final boolean planned;
    /** The arcana of the goal this plan kept instead of this one (goals only), else null. */
    public final String blocker;
    /** The rescue this link waits on when it is {@link #lost}, else null. */
    public final Rules.Rescue rescue;
    /** The rescue was recorded as failed (not just missing from the record). */
    public final boolean rescueFailed;
    /**
     * The tile's one footer line, or "" when the rank line already says it: 「11月20日 升到 MAX」,
     * 「时间先给了皇帝」 (a goal), 「救出北村文吉了吗？」 / 「没救出失踪者，无法继续」, or 「剧情自动推进」
     * for a story link.
     */
    public final String footZh;
    /** Compact chip text, e.g. "皇帝 6 → MAX · 11月20日", "女教皇 5 → 9", "皇帝 MAX ✓". */
    public final String chipZh;

    private LinkStatus(int link, int observed, int projected, int target, int targetSlot, boolean story,
                       boolean lost, boolean goal, boolean planned, String blocker, Rules.Rescue rescue,
                       boolean rescueFailed, String footZh, String chipZh) {
        this.link = link;
        this.observed = observed;
        this.projected = projected;
        this.target = target;
        this.targetSlot = targetSlot;
        this.story = story;
        this.lost = lost;
        this.goal = goal;
        this.planned = planned;
        this.blocker = blocker;
        this.rescue = rescue;
        this.rescueFailed = rescueFailed;
        this.footZh = footZh;
        this.chipZh = chipZh;
    }

    /** Computes the status of {@code linkIdx}; the plan and observed state may be null. */
    public static LinkStatus of(Rules rules, Profile profile, SimState observed, Plan plan, int linkIdx) {
        Rules.Link link = rules.links[linkIdx];
        int obs = observed == null ? 0 : observed.rank[linkIdx];
        int target = profile == null ? 10 : profile.target[linkIdx];
        int projected = obs;
        int targetSlot = -1;
        if (plan != null) {
            projected = Math.max(obs, plan.summary.endRanks[linkIdx]);
            targetSlot = plan.summary.maxSlot[linkIdx];
        }
        boolean story = link.kind == Rules.LinkKind.STORY;
        Rules.Rescue missed = missedRescue(rules, observed, linkIdx);
        boolean lost = missed != null;
        boolean failed = lost && failed(rules, observed, missed);
        boolean goal = profile != null && profile.isGoal(linkIdx);
        String name = link.arcanaZh;
        String goalText = target >= 10 ? "MAX" : Zh.rank(target);
        String blocker = null;
        String foot;
        String chip;
        if (obs >= target) {
            foot = "";
            chip = name + " " + (target >= 10 ? "MAX" : Zh.rank(target)) + " ✓";
        } else if (lost) {
            foot = failed ? "没救出失踪者，无法继续" : "救出" + missed.zh + "了吗？";
            chip = name + " 无法继续";
        } else if (targetSlot >= 0) {
            String when = Zh.date(Rules.dayOf(targetSlot));
            foot = reachLine(when, goalText);
            chip = name + " " + Zh.rankValue(obs) + " → " + Zh.rankValue(target) + " · " + when;
        } else if (story) {
            foot = LinksPage.STORY_TAG;
            chip = name + " " + Zh.rankValue(obs) + " · " + LinksPage.STORY_TAG;
        } else if (plan == null) {
            foot = "";
            chip = name + " " + Zh.rankValue(obs) + " → " + Zh.rankValue(target);
        } else if (projected >= target) {
            // A goal gets the line; elsewhere 「6 → MAX」 already says it.
            foot = !goal ? "" : target >= 10 ? "1月31日前升到 MAX" : "升到 " + Zh.rank(target);
            chip = name + " " + Zh.rankValue(obs) + " → " + Zh.rankValue(target);
        } else if (!goal) {
            // Not a goal: the rank line (「未解锁 → RANK 7」) is all there is to say.
            foot = "";
            chip = projected > obs ? name + " " + Zh.rankValue(obs) + " → " + Zh.rankValue(projected)
                : name + " " + Zh.rankValue(obs);
        } else {
            blocker = higherPriorityBlocker(rules, plan, linkIdx);
            foot = blocker != null ? tradedLine(blocker) : "";
            chip = name + " " + Zh.rankValue(obs) + " → " + Zh.rankValue(projected);
        }
        return new LinkStatus(linkIdx, obs, projected, target, targetSlot, story, lost, goal, plan != null,
            blocker, missed, failed, foot, chip);
    }

    /** "11月20日 升到 MAX". */
    static String reachLine(String when, String goalText) {
        return when + " 升到 " + goalText;
    }

    /** The tile footer of a goal the plan traded away: "时间先给了皇帝". */
    static String tradedLine(String blocker) {
        return "时间先给了" + blocker;
    }

    /**
     * The 社群详情 sheet's line for a goal the plan does not reach, with what to do about it:
     * "时间先给了女皇。想先冲它，就排到女皇前面" when the plan kept another goal, else
     * "这次排到 RANK 9。想冲 MAX，就把它往前排".
     */
    static String shortfall(String goalText, int observed, int projected, String blocker) {
        if (blocker != null) {
            return tradedLine(blocker) + "。想先冲它，就排到" + blocker + "前面";
        }
        String now = projected > observed ? "这次排到 " + Zh.rank(projected) : "这次没排上";
        return now + "。想冲 " + goalText + "，就把它往前排";
    }

    /** True when the link cannot continue: its rescue failed, or its window passed without one. */
    static boolean isLost(Rules rules, SimState observed, int linkIdx) {
        return missedRescue(rules, observed, linkIdx) != null;
    }

    /**
     * The rescue this link depends on when it is lost: marked failed, or its deadline has passed
     * and no rescue was recorded (the planner then never schedules the link). Null otherwise.
     */
    static Rules.Rescue missedRescue(Rules rules, SimState observed, int linkIdx) {
        if (observed == null) {
            return null;
        }
        int day = Rules.dayOf(observed.slot);
        for (int i = 0; i < rules.rescues.length; i++) {
            Rules.Rescue r = rules.rescues[i];
            if (r.link != linkIdx) {
                continue;
            }
            long bit = 1L << i;
            if ((observed.rescueFailed & bit) != 0) {
                return r;
            }
            if ((observed.rescued & bit) == 0 && day > r.toDay) {
                return r;
            }
        }
        return null;
    }

    private static boolean failed(Rules rules, SimState observed, Rules.Rescue rescue) {
        for (int i = 0; i < rules.rescues.length; i++) {
            if (rules.rescues[i] == rescue) {
                return (observed.rescueFailed & (1L << i)) != 0;
            }
        }
        return false;
    }

    /**
     * The arcana of the goal this plan kept instead of {@code linkIdx} (engine Summary.tradedFor), or
     * null when the planner did not trade it away: then no plan it compared reached the goal, and
     * no reason is claimed that it cannot back up.
     */
    static String higherPriorityBlocker(Rules rules, Plan plan, int linkIdx) {
        int[] traded = plan.summary.tradedFor;
        int other = traded != null && linkIdx < traded.length ? traded[linkIdx] : -1;
        return other >= 0 && other < rules.links.length && other != linkIdx ? rules.links[other].arcanaZh : null;
    }

    /** The numeral printed on the arcana card (the pack's, so 永劫 shows "XX"), else the arcana number's. */
    static String numeral(Rules.Link link) {
        return link.numeral != null && !link.numeral.trim().isEmpty() ? link.numeral.trim() : Zh.roman(link.arcanaNo);
    }

    /** Goal link indices, 必须 before 想要, each tier by its order then arcana order. */
    public static List<Integer> goalOrder(Profile profile) {
        List<Integer> out = new ArrayList<>();
        if (profile == null) {
            return out;
        }
        for (Profile.Tier tier : new Profile.Tier[]{Profile.Tier.MUST, Profile.Tier.WANT}) {
            List<Integer> inTier = new ArrayList<>();
            for (int i = 0; i < Profile.LINKS; i++) {
                if (profile.tier[i] == tier) {
                    inTier.add(i);
                }
            }
            inTier.sort((a, b) -> profile.order[a] != profile.order[b]
                ? Integer.compare(profile.order[a], profile.order[b]) : Integer.compare(a, b));
            out.addAll(inTier);
        }
        return out;
    }
}
