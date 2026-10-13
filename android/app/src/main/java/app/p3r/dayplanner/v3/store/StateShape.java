package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Arrays;

/**
 * Normalisation helpers for observed states: array sizes that match the rule pack, the "next slot
 * to act in" skipping slots the calendar locks, and the rescue rule for typed-in states.
 */
public final class StateShape {
    private StateShape() {}

    /**
     * Copy of {@code s} whose arrays have the sizes the rules expect (22 links, 3 stats, one entry
     * per Tartarus block, activity and Link Episode track). Missing entries are zero
     * ({@code lastStepDay}: -1); extra entries are dropped. Values are not clamped.
     */
    public static SimState fit(SimState s, Rules rules) {
        SimState out = s.copy();
        out.statPts = resize(s.statPts, SimState.STATS, 0);
        out.rank = resize(s.rank, SimState.LINKS, 0);
        out.pts = resize(s.pts, SimState.LINKS, 0);
        out.ready = Arrays.copyOf(s.ready, SimState.LINKS);
        out.unlockStep = resize(s.unlockStep, SimState.LINKS, 0);
        out.lastStepDay = resize(s.lastStepDay, SimState.LINKS, -1);
        out.heldRank = resize(s.heldRank, SimState.LINKS, 0);
        out.towerVisits = resize(s.towerVisits, rules.blocks.length, 0);
        out.shifts = resize(s.shifts, rules.activities.length, 0);
        out.used = resize(s.used, rules.activities.length, 0);
        out.episodes = resize(s.episodes, rules.episodes.length, 0);
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

    /**
     * The rescue rule for a state the player typed in (onboarding, 同步现状, a full edit), which
     * cannot always say whether a missing person was rescued. For every rescue that is neither
     * rescued nor failed in {@code s}, whose window ended before the state's day
     * ({@code toDay < dayOf(s.slot)}) and whose linked Social Link is at RANK 1 or above, the
     * rescue is taken as done (the game would not have let the link go on otherwise). Nothing else
     * changes: an explicit failure ({@code rescueFailed}) is kept, an open window stays open, and
     * this rule never sets {@code rescueFailed}. Returns a copy; {@code s} is not modified.
     */
    public static SimState inferRescues(SimState s, Rules rules) {
        SimState out = s.copy();
        int day = Rules.dayOf(s.slot);
        for (int i = 0; i < rules.rescues.length && i < 64; i++) {
            Rules.Rescue rescue = rules.rescues[i];
            long bit = 1L << i;
            boolean settled = ((out.rescued | out.rescueFailed) & bit) != 0;
            boolean linked = rescue.link >= 0 && rescue.link < out.rank.length;
            if (!settled && linked && rescue.toDay < day && out.rank[rescue.link] >= 1) {
                out.rescued |= bit;
            }
        }
        return out;
    }

    private static int[] resize(int[] values, int size, int fill) {
        int[] out = new int[size];
        Arrays.fill(out, fill);
        if (values != null) {
            System.arraycopy(values, 0, out, 0, Math.min(size, values.length));
        }
        return out;
    }
}
