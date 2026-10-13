package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Arrays;

/**
 * Normalisation helpers for observed states: array sizes that match the rule pack, and the
 * "next slot to act in" skipping slots the calendar locks.
 */
public final class StateShape {
    private StateShape() {}

    /**
     * Copy of {@code s} whose arrays have the sizes the rules expect (22 links, 3 stats, one entry
     * per Tartarus block and per activity). Missing entries are zero ({@code lastStepDay}: -1);
     * extra entries are dropped. Values are not clamped.
     */
    public static SimState fit(SimState s, Rules rules) {
        SimState out = s.copy();
        out.statPts = resize(s.statPts, SimState.STATS, 0);
        out.rank = resize(s.rank, SimState.LINKS, 0);
        out.pts = resize(s.pts, SimState.LINKS, 0);
        out.ready = Arrays.copyOf(s.ready, SimState.LINKS);
        out.unlockStep = resize(s.unlockStep, SimState.LINKS, 0);
        out.lastStepDay = resize(s.lastStepDay, SimState.LINKS, -1);
        out.towerVisits = resize(s.towerVisits, rules.blocks.length, 0);
        out.shifts = resize(s.shifts, rules.activities.length, 0);
        out.used = resize(s.used, rules.activities.length, 0);
        return out;
    }

    /**
     * First slot at or after {@code slot} that the calendar does not lock, or
     * {@code rules.lastSlot() + 1} when none is left (the calendar is over).
     */
    public static int firstOpenSlot(Rules rules, int slot) {
        int last = rules.lastSlot();
        int s = slot;
        while (s <= last && rules.lockOf(s) != null) {
            s++;
        }
        return Math.min(s, last + 1);
    }

    /** Number of unlocked slots in {@code [from, to)}. */
    public static int openSlotsBetween(Rules rules, int from, int to) {
        int count = 0;
        for (int s = Math.max(0, from); s < to && s <= rules.lastSlot(); s++) {
            if (rules.lockOf(s) == null) {
                count++;
            }
        }
        return count;
    }

    /** {@link #fit} plus {@link #firstOpenSlot}: the canonical observed form. */
    public static SimState normalize(SimState s, Rules rules) {
        SimState out = fit(s, rules);
        out.slot = firstOpenSlot(rules, out.slot);
        return out;
    }

    private static int[] resize(int[] values, int size, int fill) {
        int[] out = new int[size];
        Arrays.fill(out, fill);
        System.arraycopy(values, 0, out, 0, Math.min(size, values.length));
        return out;
    }
}
