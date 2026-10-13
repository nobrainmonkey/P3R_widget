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
    /** Rank the player confirmed (0 = 未建立). */
    public final int observed;
    /** Forecast rank at the end of the plan. */
    public final int projected;
    /** Target rank (10 = MAX). */
    public final int target;
    /** Slot the target is reached, -1 if never within the plan. */
    public final int targetSlot;
    public final boolean story;
    public final boolean lost;
    /** Short footer for the card, e.g. "预计 11/20 MAX" or "时间不够：优先级低于 恋爱". */
    public final String footZh;
    /** Compact chip text, e.g. "皇帝 6→MAX · 预计 11/20". */
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
        boolean lost = isLost(rules, observed, linkIdx);
        boolean goal = profile != null && profile.isGoal(linkIdx);
        String name = link.arcanaZh;
        String foot;
        String chip;
        if (obs >= target) {
            foot = target >= 10 ? "已 MAX" : "已达到 " + Zh.rank(target);
            chip = name + " " + Zh.rankValue(obs) + " ✓";
        } else if (lost) {
            foot = "失踪者没能获救，这个社群无法继续";
            chip = name + " 无法继续";
        } else if (targetSlot >= 0) {
            String when = Zh.shortDate(Rules.dayOf(targetSlot));
            foot = "预计 " + when + (target >= 10 ? " MAX" : " 到 " + Zh.rank(target));
            chip = name + " " + Zh.rankValue(obs) + "→" + Zh.rankValue(target) + " · 预计 " + when;
        } else if (story) {
            foot = "剧情自动推进";
            chip = name + " " + Zh.rankValue(obs) + " · 剧情推进";
        } else if (plan == null) {
            foot = "等待安排";
            chip = name + " " + Zh.rankValue(obs) + "→" + Zh.rankValue(target);
        } else if (projected >= target) {
            foot = target >= 10 ? "预计 1月31日前 MAX" : "预计达到 " + Zh.rank(target);
            chip = name + " " + Zh.rankValue(obs) + "→" + Zh.rankValue(target);
        } else if (!goal && projected <= obs) {
            foot = obs == 0 ? "这次计划里暂不建立" : "这次计划里暂不提升";
            chip = name + " " + Zh.rankValue(obs);
        } else {
            String end = "到 " + Zh.date(Rules.dayOf(plan.toSlot)) + " 预计 " + Zh.rank(projected);
            String blocker = goal ? higherPriorityBlocker(rules, profile, plan, linkIdx) : null;
            foot = blocker == null ? end : "时间不够：优先级低于 " + blocker;
            chip = name + " " + Zh.rankValue(obs) + "→" + Zh.rankValue(projected) + " · 时间不够";
        }
        return new LinkStatus(linkIdx, obs, projected, target, targetSlot, story, lost, foot, chip);
    }

    /** True when the link's rescue failed (the link cannot continue). */
    static boolean isLost(Rules rules, SimState observed, int linkIdx) {
        if (observed == null) {
            return false;
        }
        for (int i = 0; i < rules.rescues.length; i++) {
            if (rules.rescues[i].link == linkIdx && (observed.rescueFailed & (1L << i)) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Arcana names of higher-priority goals that do reach their target (the likely reason). */
    static String higherPriorityBlocker(Rules rules, Profile profile, Plan plan, int linkIdx) {
        List<Integer> goals = goalOrder(profile);
        List<String> names = new ArrayList<>();
        for (int other : goals) {
            if (other == linkIdx) {
                break;
            }
            if (plan.summary.maxSlot[other] >= 0) {
                names.add(rules.links[other].arcanaZh);
            }
            if (names.size() == 2) {
                break;
            }
        }
        return names.isEmpty() ? null : String.join("、", names);
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
