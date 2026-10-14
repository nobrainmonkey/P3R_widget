package app.p3r.dayplanner.v3.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * The widget's browsable list: plan steps from the observed slot onward that are productive, plus
 * story-locked slots shown as information items (e.g. 满月作战). Free slots are skipped except the
 * observed one (自由活动, so 完成 can record it instead of sending the player into catch-up), and runs
 * of consecutive locked slots with the same reason collapse into one item; a run splits where a new
 * day carries a 当日提醒, so each exam day with its answer is its own card.
 *
 * <p>Each 当日提醒 ({@link Plan#reminders}) from the observed slot on rides on the item covering its
 * slot or, for a free slot that is no item, on the next item of the same day; otherwise on none
 * (the app's 今天 page still lists it).
 */
public final class PlanItems {

    /** One card the arrows can step to. */
    public static final class Item {
        public final Plan.Step step;
        /** First slot of the item. */
        public final int slot;
        /** Last slot covered (same as {@link #slot} except for merged locked runs). */
        public final int lastSlot;
        /** The 当日提醒 this card carries, in plan order; empty when none. */
        public final List<Plan.Reminder> reminders = new ArrayList<>();

        Item(Plan.Step step, int slot, int lastSlot) {
            this.step = step;
            this.slot = slot;
            this.lastSlot = lastSlot;
        }

        public boolean locked() {
            return isLocked(step);
        }

        public boolean covers(int s) {
            return s >= slot && s <= lastSlot;
        }
    }

    private PlanItems() {}

    public static boolean isLocked(Plan.Step step) {
        return "locked".equals(step.kind) || step.action.startsWith("locked:");
    }

    /**
     * Whether the step is something to do: {@link Plan.Step#productive()}, and also a dating-site
     * note or a Link Episode whatever coarse kind the engine files it under.
     */
    public static boolean isProductive(Plan.Step step) {
        if (step == null || isLocked(step)) return false;
        String action = step.action == null ? "" : step.action;
        return step.productive() || action.startsWith("note:") || action.startsWith("episode:");
    }

    /** Items of {@code plan} at or after {@code fromSlot}, in slot order. */
    public static List<Item> of(Plan plan, int fromSlot) {
        if (plan == null || plan.steps == null) return Collections.emptyList();
        List<Item> items = new ArrayList<>();
        Plan.Step runStep = null;
        int runStart = -1;
        int runEnd = -1;
        for (Plan.Step step : plan.steps) {
            if (step == null || step.slot < fromSlot) continue;
            boolean locked = isLocked(step);
            boolean continuesRun = locked && runStep != null && step.slot == runEnd + 1 && step.action.equals(runStep.action)
                    && !(!Rules.isEvening(step.slot) && remindedDay(plan, Rules.dayOf(step.slot)));
            if (continuesRun) {
                runEnd = step.slot;
                continue;
            }
            if (runStep != null) {
                items.add(new Item(runStep, runStart, runEnd));
                runStep = null;
            }
            if (locked) {
                runStep = step;
                runStart = step.slot;
                runEnd = step.slot;
            } else if (isProductive(step) || step.slot == fromSlot) {
                items.add(new Item(step, step.slot, step.slot));
            }
        }
        if (runStep != null) items.add(new Item(runStep, runStart, runEnd));
        attachReminders(plan, fromSlot, items);
        return items;
    }

    /** Whether any 当日提醒 of {@code plan} falls on {@code day}. */
    private static boolean remindedDay(Plan plan, int day) {
        if (plan.reminders == null) return false;
        for (Plan.Reminder r : plan.reminders) {
            if (r != null && Rules.dayOf(r.slot) == day) return true;
        }
        return false;
    }

    /** Puts each 当日提醒 from {@code fromSlot} on the item that carries it (see the class comment). */
    private static void attachReminders(Plan plan, int fromSlot, List<Item> items) {
        if (plan.reminders == null) return;
        for (Plan.Reminder r : plan.reminders) {
            if (r == null || r.slot < fromSlot) continue;
            for (Item item : items) {
                if (item.covers(r.slot)) {
                    item.reminders.add(r);
                    break;
                }
                if (item.slot > r.slot) {
                    if (Rules.dayOf(item.slot) == Rules.dayOf(r.slot)) item.reminders.add(r);
                    break;
                }
            }
        }
    }

    /** Index of the item covering {@code slot}, or -1. */
    public static int indexOf(List<Item> items, int slot) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).covers(slot)) return i;
        }
        return -1;
    }
}
