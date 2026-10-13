package app.p3r.dayplanner.v3.ui;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/** One-line summary of what a replan changed ("已重新规划：覆盖至 1月31日 · 调整 3 处"). */
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

    /** Summary for the 今天 page, or null when there is nothing to compare. */
    public static String summary(Rules rules, Plan before, Plan after, int fromSlot) {
        if (after == null) {
            return null;
        }
        String coverage = "覆盖至 " + Zh.date(Rules.dayOf(after.toSlot));
        if (before == null) {
            return "已排好日程：" + coverage;
        }
        int changed = changedSlots(before, after, fromSlot);
        if (changed == 0) {
            return "已重新规划：" + coverage + " · 后面的安排不变";
        }
        return "已重新规划：" + coverage + " · 调整 " + changed + " 处";
    }
}
