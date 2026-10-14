package app.p3r.dayplanner.v3.ui;

import java.util.Objects;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * One-line summary of what a replan changed ("已重新安排：后面有 3 处变动"), and of how a
 * 从头重新安排 ended ({@link #fullSummary}).
 */
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
            return "安排好了，一直排到" + Zh.date(Rules.dayOf(after.toSlot));
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

    /** 从头重新安排 ended with the same steps as before (also when nothing visible changed). */
    static final String FULL_UNCHANGED = "重新算过了，安排没有变化";
    static final String FULL_KEPT = "重新算过了，没有比现在的安排更好，保留现在的安排";
    static final String FULL_FAILED = "这次没能从头重新安排，现在的安排不变";
    static final String FULL_CANCELLED = "已停下，现在的安排不变";

    /**
     * What a 从头重新安排 request ended with, for the message bar and the 今天 status line;
     * {@code before} is the plan shown when it was asked for (null if unknown), {@code after} the
     * plan now. Null when there is nothing to tell (no result: import, or stopped while the plan
     * was out of date).
     */
    public static String fullSummary(Rules rules, Plan before, Plan after, int fromSlot,
                                     PlannerApi.FullReplanResult result) {
        if (result == null) {
            return null;
        }
        switch (result) {
            case UNCHANGED:
                return FULL_UNCHANGED;
            case KEPT:
                return FULL_KEPT;
            case FAILED:
                return FULL_FAILED;
            case CANCELLED:
                return FULL_CANCELLED;
            default:
                break;
        }
        if (after == null || finished(after)) {
            return null;
        }
        if (before == null) {
            // The plan before is not known here (the screen was rebuilt meanwhile): no counts.
            return "从头重新安排好了，一直排到" + Zh.date(Rules.dayOf(after.toSlot));
        }
        int nearEnd = Rules.slot(Rules.dayOf(fromSlot) + NEAR_DAYS, true);
        int near = changedSlots(before, after, fromSlot, nearEnd);
        int later = changedSlots(before, after, nearEnd + 1, Integer.MAX_VALUE);
        if (near > 0) {
            return "从头重新安排好了：这一周有 " + near + " 处变动" + (later > 0 ? "，之后的也顺着调整了" : "");
        }
        if (later > 0) {
            return "从头重新安排好了：这一周不变，之后的安排顺着调整了";
        }
        if (changedSides(before, after, fromSlot) > 0) {
            return "从头重新安排好了：要做的事没变，顺便做的事有调整";
        }
        return FULL_UNCHANGED;
    }

    /** True for the results said with a neutral mark rather than a check (nothing was redone). */
    static boolean neutral(String summary) {
        return FULL_FAILED.equals(summary) || FULL_CANCELLED.equals(summary);
    }

    /** Slots from {@code fromSlot} on with the same action in both plans but other 顺便 lines. */
    static int changedSides(Plan before, Plan after, int fromSlot) {
        int start = Math.max(fromSlot, Math.max(before.fromSlot, after.fromSlot));
        int end = Math.min(before.toSlot, after.toSlot);
        int changed = 0;
        for (int slot = start; slot <= end; slot++) {
            Plan.Step a = before.at(slot);
            Plan.Step b = after.at(slot);
            if (a != null && b != null && a.action.equals(b.action) && !Objects.equals(a.side, b.side)) {
                changed++;
            }
        }
        return changed;
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
