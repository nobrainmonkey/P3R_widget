package app.p3r.dayplanner.v3.ui;

import java.util.Objects;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * One-line summary of what a replan changed this week ("已重新安排：这周 3 处有变"), and of how a
 * 从头重新安排 ended ({@link #fullSummary}). Nothing is said when nothing a player would notice
 * changed.
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

    /**
     * First slot from {@code fromSlot} on that both plans cover and whose step differs (the action
     * or its 顺便 lines): where 「看看」 takes the player; -1 when nothing differs.
     */
    static int firstChangedSlot(Plan before, Plan after, int fromSlot) {
        if (before == null || after == null) {
            return -1;
        }
        int start = Math.max(fromSlot, Math.max(before.fromSlot, after.fromSlot));
        int end = Math.min(before.toSlot, after.toSlot);
        for (int slot = start; slot <= end; slot++) {
            Plan.Step a = before.at(slot);
            Plan.Step b = after.at(slot);
            if (a != null && b != null && (!a.action.equals(b.action) || !Objects.equals(a.side, b.side))) {
                return slot;
            }
        }
        return -1;
    }

    /** True when the plan has nothing left to schedule (the calendar is over). */
    public static boolean finished(Plan plan) {
        return plan != null && (plan.fromSlot > plan.toSlot || plan.steps.isEmpty());
    }

    /** Days ahead whose changes are worth calling out; later days are re-planned quietly. */
    static final int NEAR_DAYS = 7;

    /**
     * Message after an ordinary replan: the first plan, or how many steps of the coming week moved;
     * null when nothing this week changed (later days are re-planned quietly), when there is
     * nothing to compare or nothing left.
     */
    public static String summary(Rules rules, Plan before, Plan after, int fromSlot) {
        if (after == null || finished(after)) {
            return null;
        }
        if (before == null) {
            return "安排好了，一直排到" + Zh.date(Rules.dayOf(after.toSlot));
        }
        int nearEnd = Rules.slot(Rules.dayOf(fromSlot) + NEAR_DAYS, true);
        int near = changedSlots(before, after, fromSlot, nearEnd);
        return near == 0 ? null : "已重新安排：这周 " + near + " 处有变";
    }

    /** 从头重新安排 ended with the same steps as before (also when nothing better was found). */
    static final String FULL_UNCHANGED = "重新安排好了，和现在的一样";
    static final String FULL_KEPT = FULL_UNCHANGED;
    static final String FULL_FAILED = "这次没排好，安排先不变";
    static final String FULL_CANCELLED = "已停下，安排没变";
    /** CHANGED when the plan before is not known (followed by the plan's last day, 「1月31日」). */
    static final String FULL_CHANGED_UNCOUNTED = "重新安排好了，一直排到";

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
            // The plan before is not known here (the screen was made again with no plan to compare): no counts.
            return FULL_CHANGED_UNCOUNTED + Zh.date(Rules.dayOf(after.toSlot));
        }
        int nearEnd = Rules.slot(Rules.dayOf(fromSlot) + NEAR_DAYS, true);
        int near = changedSlots(before, after, fromSlot, nearEnd);
        int later = changedSlots(before, after, nearEnd + 1, Integer.MAX_VALUE);
        if (near > 0) {
            return "重新安排好了：这周 " + near + " 处有变" + (later > 0 ? "，之后也有调整" : "");
        }
        if (later > 0) {
            return "重新安排好了：这周不变，之后有调整";
        }
        if (changedSides(before, after, fromSlot) > 0) {
            return "重新安排好了：顺便的事有调整";
        }
        return FULL_UNCHANGED;
    }

    /** The same for an ordinary or a deep 从头重新安排: the player asked for one thing. */
    public static String fullSummary(Rules rules, Plan before, Plan after, int fromSlot,
                                     PlannerApi.FullReplanResult result, boolean deep) {
        return fullSummary(rules, before, after, fromSlot, result);
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
