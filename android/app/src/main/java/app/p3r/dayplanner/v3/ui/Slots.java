package app.p3r.dayplanner.v3.ui;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Open-slot arithmetic, following the store's rule: a slot the calendar locks (full moon, exams,
 * story days…) is never recorded; the store moves the observed slot past it on its own.
 */
final class Slots {
    private Slots() {}

    /** First slot at or after {@code slot} that is not locked, or {@code lastSlot + 1} when none is left. */
    static int firstOpen(Rules rules, int slot) {
        int last = rules.lastSlot();
        int s = slot;
        while (s <= last && rules.lockOf(s) != null) {
            s++;
        }
        return Math.min(s, last + 1);
    }

    /** Number of open (unlocked) slots in {@code [from, to)}. */
    static int openBetween(Rules rules, int from, int to) {
        int count = 0;
        for (int s = Math.max(0, from); s < to && s <= rules.lastSlot(); s++) {
            if (rules.lockOf(s) == null) {
                count++;
            }
        }
        return count;
    }

    /** True once every slot up to Jan 31 has been recorded (nothing is left to plan). */
    static boolean finished(Rules rules, SimState observed) {
        return rules != null && observed != null && observed.slot > rules.lastSlot();
    }
}
