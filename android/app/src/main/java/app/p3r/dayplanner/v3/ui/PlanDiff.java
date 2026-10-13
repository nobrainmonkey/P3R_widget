package app.p3r.dayplanner.v3.ui;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/** One-line summary of what a replan changed ("已重新安排：后面有 3 处变动"). */
public final class PlanDiff {
    private PlanDiff() {}

    /** Number of slots both plans cover (from {@code fromSlot} on) whose action differs. */
    public static int changedSlots(Plan before, Plan after, int fromSlot) {
        if (before == null || after == null) {
            return 0;
        }
        int start = Math.max(fromSlot, Math.max(before.fromSlot, after.fromSlot));
        int end = Math.min(before.toSlot, after.toSlot);
        int changed = 0;
        for (int slot = start; slot <= end; slot++) {
            Plan.Step a = before.at(slot);
            Plan.Step b = after.at(slot);
            if (a == null || b == null) {
                continue;
            }
            if (!a.action.equals(b.action)) {
                changed++;
            }
        }
        return changed;
    }

    /** True when the plan has nothing left to schedule (the calendar is over). */
    public static boolean finished(Plan plan) {
        return plan != null && (plan.fromSlot > plan.toSlot || plan.steps.isEmpty());
    }

    /** Days ahead whose changes are worth calling out; later days are re-planned quietly. */
    static final int NEAR_DAYS = 7;

    /** Summary for the 今天 page, or null when there is nothing to compare or nothing left. */
    public static String summary(Rules rules, Plan before, Plan after, int fromSlot) {
        if (after == null || finished(after)) {
            return null;
        }
        if (before == null) {
            return "日程排好了，一直到" + Zh.date(Rules.dayOf(after.toSlot));
        }
        int nearEnd = Rules.slot(Rules.dayOf(fromSlot) + NEAR_DAYS, true);
        int near = changedSlots(before, after, fromSlot, nearEnd);
        int later = changedSlots(before, after, nearEnd + 1, Integer.MAX_VALUE);
        if (near == 0 && later == 0) {
            return "已重新安排：后面的安排没变";
        }
        if (near == 0) {
            return "已重新安排：这一周不变，之后的安排顺着调整了";
        }
        return "已重新安排：这一周有 " + near + " 处变动" + (later > 0 ? "，之后的也顺着调整了" : "");
    }

    /** Changed slots in [fromSlot, toSlot]. */
    static int changedSlots(Plan before, Plan after, int fromSlot, int toSlot) {
        if (before == null || after == null) {
            return 0;
        }
        int start = Math.max(fromSlot, Math.max(before.fromSlot, after.fromSlot));
        int end = Math.min(toSlot, Math.min(before.toSlot, after.toSlot));
        int changed = 0;
        for (int slot = start; slot <= end; slot++) {
            Plan.Step a = before.at(slot);
            Plan.Step b = after.at(slot);
            if (a != null && b != null && !a.action.equals(b.action)) {
                changed++;
            }
        }
        return changed;
    }
}
