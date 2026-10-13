package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Per-link facts for the 社群 cards and goal chips: confirmed rank, forecast end rank, when the
 * target is reached, and a plain reason when it is not.
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
    /**
     * Short footer for the card, e.g. "预计 11月20日 升到 MAX" or "这次的安排里没排到 MAX（先保证了皇帝）".
     * A goal the plan does not reach is described as this plan's outcome, never as impossible: the
     * planner is a heuristic search and another arrangement might still get there.
     */
    public final String footZh;
    /** Compact chip text, e.g. "皇帝 6→MAX · 预计 11月20日". */
    public final String chipZh;

    private LinkStatus(int link, int observed, int projected, int target, int targetSlot, boolean story,
                       boolean lost, String footZh, String chipZh) {
        this.link = link;
        this.observed = observed;
        this.projected = projected;
        this.target = target;
        this.targetSlot = targetSlot;
        this.story = story;
        this.lost = lost;
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
        boolean goal = profile != null && profile.isGoal(linkIdx);
        String name = link.arcanaZh;
        String foot;
        String chip;
        if (obs >= target) {
            foot = target >= 10 ? "已经 MAX" : "已达到 " + Zh.rank(target);
            chip = name + " " + Zh.rankValue(obs) + " ✓";
        } else if (lost) {
            foot = failed(rules, observed, missed) ? "没能救出失踪者，这个社群无法继续"
                : "还没记录救出" + missed.zh + "，可以在「修改进度」里补上";
            chip = name + " 无法继续";
        } else if (targetSlot >= 0) {
            String when = Zh.date(Rules.dayOf(targetSlot));
            foot = "预计 " + when + " 升到 " + (target >= 10 ? "MAX" : Zh.rank(target));
            chip = name + " " + Zh.rankValue(obs) + "→" + Zh.rankValue(target) + " · 预计 " + when;
        } else if (story) {
            foot = "剧情自动推进";
            chip = name + " " + Zh.rankValue(obs) + " · 剧情自动推进";
        } else if (plan == null) {
            foot = "安排排好后显示";
            chip = name + " " + Zh.rankValue(obs) + "→" + Zh.rankValue(target);
        } else if (projected >= target) {
            foot = target >= 10 ? "预计1月31日前升到 MAX" : "预计升到 " + Zh.rank(target);
            chip = name + " " + Zh.rankValue(obs) + "→" + Zh.rankValue(target);
        } else if (!goal && projected <= obs) {
            foot = obs == 0 ? "暂时不安排解锁" : "暂时不安排提升";
            chip = name + " " + Zh.rankValue(obs);
        } else {
            String goalText = target >= 10 ? "MAX" : Zh.rank(target);
            String blocker = goal ? higherPriorityBlocker(rules, plan, linkIdx) : null;
            foot = shortfall(goalText, obs, projected, blocker);
            chip = name + " " + Zh.rankValue(obs) + "→" + Zh.rankValue(projected) + " · 没排到 " + goalText;
        }
        return new LinkStatus(linkIdx, obs, projected, target, targetSlot, story, lost, foot, chip);
    }

    /**
     * "这次的安排里没排到 MAX（先保证了皇帝、恋爱）" / "这次的安排里排到 RANK 9，没排到 MAX": what this plan
     * does, not a claim that the target cannot be reached.
     */
    static String shortfall(String goalText, int observed, int projected, String blocker) {
        if (blocker != null) {
            return "这次的安排里没排到 " + goalText + "（先保证了" + blocker + "）";
        }
        if (projected > observed) {
            return "这次的安排里排到 " + Zh.rank(projected) + "，没排到 " + goalText;
        }
        return "这次的安排里没排到 " + goalText;
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
     * null when the planner did not trade it away: then no plan it compared reached the goal, and the
     * footer names no reason it cannot back up.
     */
    static String higherPriorityBlocker(Rules rules, Plan plan, int linkIdx) {
        int[] traded = plan.summary.tradedFor;
        int other = traded != null && linkIdx < traded.length ? traded[linkIdx] : -1;
        return other >= 0 && other < rules.links.length && other != linkIdx ? rules.links[other].arcanaZh : null;
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
