package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.List;

/**
 * The planner's one-day margin on missing-person rescues, checked on a finished plan without the
 * engine's own tables (DESIGN §10.3, §17.1). The game window [fromDay, toDay] is unchanged, the limit
 * evening included; but a plan must not depend on the limit evening: when the window has an evening
 * before the limit day on which the plan could visit Tartarus (from the plan's start, enterable, its
 * block's cap not used up at the start), every modelled rescue whose link still matters (wanted,
 * below MAX at the start) lands on or before toDay − 1. A rescue on the limit evening says so honestly
 * in its step ({@link Labels#LIMIT_EVENING_CAVEAT}).
 */
final class RescueMargin {
    private RescueMargin() {}

    /** Where one modelled rescue of a plan lands. */
    static final class Landing {
        final Rules.Rescue rescue;
        /** Slot of the Tartarus step that rescues them, or -1 when the plan does not. */
        final int slot;
        /** The window had an open Tartarus evening before the limit day that the plan could use. */
        final boolean earlier;
        /** The link is wanted by the profile and below MAX at the start. */
        final boolean matters;

        Landing(Rules.Rescue rescue, int slot, boolean earlier, boolean matters) {
            this.rescue = rescue;
            this.slot = slot;
            this.earlier = earlier;
            this.matters = matters;
        }

        boolean onLimitEvening() {
            return slot >= 0 && Rules.dayOf(slot) == rescue.toDay;
        }

        @Override public String toString() {
            return rescue.id + (slot < 0 ? " not rescued" : " rescued on day " + Rules.dayOf(slot) + " (limit " + rescue.toDay + ")")
                    + (earlier ? ", an earlier evening was open" : ", only the limit evening") + (matters ? "" : ", link does not matter");
        }
    }

    /** Every modelled rescue still open at the start whose window is not over. */
    static List<Landing> landings(Rules rules, Profile profile, SimState start, Plan plan) {
        List<Landing> out = new ArrayList<>();
        int startDay = Rules.dayOf(start.slot);
        for (int i = 0; i < rules.rescues.length; i++) {
            Rules.Rescue r = rules.rescues[i];
            long bit = 1L << i;
            if (((start.rescued | start.rescueFailed) & bit) != 0 || r.toDay < startDay) continue;
            int slot = -1;
            for (Plan.Step s : plan.steps) {
                int day = Rules.dayOf(s.slot);
                if (s.slot >= start.slot && day >= r.fromDay && day <= r.toDay && "tower".equals(s.kind)) {
                    slot = s.slot;
                    break;
                }
            }
            boolean matters = r.link >= 0 && r.link < rules.links.length && start.rank[r.link] < 10
                    && (profile.otherLinks || profile.isGoal(r.link));
            out.add(new Landing(r, slot, earlierEvening(rules, profile, start, r), matters));
        }
        return out;
    }

    /** An evening in [fromDay, toDay − 1] from the start on with a Tartarus visit possible and the block's cap not used up. */
    static boolean earlierEvening(Rules rules, Profile profile, SimState start, Rules.Rescue r) {
        for (int d = r.fromDay; d < r.toDay; d++) {
            int eve = Rules.slot(d, true);
            Rules.Day day = rules.day(d);
            if (eve < start.slot || day == null || !day.tartarus || day.block < 0 || day.block >= rules.blocks.length) continue;
            if (rules.lockOf(eve) != null) continue;
            if (profile.christmasTaken() && d == rules.christmasEveDay()) continue;
            if (start.towerVisits[day.block] < profile.capFor(rules.blocks[day.block].id)) return true;
        }
        return false;
    }

    /**
     * What breaks the margin, or null: a rescue that matters landing on the limit evening although an
     * earlier evening was open, or a step on the limit evening that does not say so honestly.
     */
    static String broken(Rules rules, Profile profile, SimState start, Plan plan) {
        for (Landing l : landings(rules, profile, start, plan)) {
            if (!l.onLimitEvening()) continue;
            if (l.matters && l.earlier) return l + ": the plan depends on the limit evening";
            Plan.Step step = plan.at(l.slot);
            if (step.subtitleZh.endsWith("救出" + l.rescue.zh) && (step.whyZh == null || !step.whyZh.contains(Labels.LIMIT_EVENING_CAVEAT))) {
                return l + ": the limit-evening step does not say it is unchecked: " + step.whyZh;
            }
        }
        return null;
    }
}
