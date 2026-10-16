package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Rules;

import java.util.Arrays;
import java.util.List;

/**
 * Plans trips for every missing-person group, including people without a Social Link. The groups
 * remain reminder data: their forecast masks live only in memory, so old checkpoints keep their
 * existing rescue-bit meaning. Actual visits still require the player's separate Social Link rescue
 * confirmation.
 *
 * <p>A deadline-ordered interval cover chooses the latest enterable evening before each group's
 * planning deadline, covering every overlapping group on that evening. Thus compatible arrivals
 * share one trip, while locks and earlier deadlines naturally split incompatible windows. Latest
 * enterable evenings and coverage masks are precomputed; scoring does not allocate state copies.
 */
final class RescueBatches {
    private static final double RESCUE_PRIORITY = 200_000;
    private final Model m;
    private final int size;
    private final int[] from;
    private final int[] until;
    private final int[] safeUntil;
    private final int[] order;
    private final int[][] lastInBlock;
    private final long[] coverAt;
    private final long[] lateAt;
    private final long[] futureAt;
    private final long[] componentGroups;
    private final long[] safeGroupsInBlock;
    private final int[][] componentBlocks;
    private final int[][] componentOrder;
    private final long initial;
    private final long matters;
    private final ThreadLocal<Work> work;

    private static final long UNSET = Long.MIN_VALUE;

    /** One exact state key and its lazy results, reused by the three tower scoring queries. */
    private static final class Decision {
        int slot = Integer.MIN_VALUE;
        long done;
        final int[] visits;
        long now = UNSET, later = UNSET, afterVisit = UNSET, withCap = UNSET;
        boolean waitComputed;
        boolean wait;

        Decision(int blocks) { visits = new int[blocks]; }

        void reset(int slot, long done) {
            this.slot = slot;
            this.done = done;
            now = later = afterVisit = withCap = UNSET;
            waitComputed = false;
            wait = false;
        }
    }

    private static final class Work {
        final int[] used;
        final Decision[] decisions;

        Work(int[] componentCounts) {
            used = new int[componentCounts.length];
            decisions = new Decision[componentCounts.length];
            for (int b = 0; b < decisions.length; b++) decisions[b] = new Decision(componentCounts[b]);
        }
    }

    RescueBatches(Model m, SimState start, int lastTowerSlot) {
        this.m = m;
        List<Rules.RescueGroup> groups = m.rules.reminders.rescueGroups;
        size = groups.size();
        if (size > 63) throw new IllegalArgumentException("too many rescue groups: " + size);
        from = new int[size];
        until = new int[size];
        safeUntil = new int[size];
        order = new int[size];
        int slots = m.t.lastSlot + 1;
        lastInBlock = new int[m.t.nBlocks][slots];
        for (int b = 0; b < m.t.nBlocks; b++) {
            int last = -1;
            for (int s = 0; s < slots; s++) {
                if (m.t.towerBlock[s] == b) last = s;
                lastInBlock[b][s] = last;
            }
        }
        coverAt = new long[slots];
        lateAt = new long[slots];
        futureAt = new long[slots];
        long done = start.plannedRescueGroups;
        long relevant = 0;
        for (int i = 0; i < size; i++) {
            Rules.RescueGroup g = groups.get(i);
            from[i] = Rules.slot(g.fromDay, true);
            until[i] = Math.min(m.t.lastSlot, Rules.slot(g.toDay, true));
            long bit = 1L << i;
            // A record inside a still-open window speaks for its group. A later record cannot
            // establish whether a group whose deadline already passed was rescued.
            if (lastTowerSlot >= from[i] && lastTowerSlot <= until[i] && lastTowerSlot < start.slot) done |= bit;
            if (fullyConfirmed(g, start)) done |= bit;
            if (g.toDay >= Rules.dayOf(start.slot)) relevant |= bit;
            int beforeLimit = Math.min(m.t.lastSlot, Rules.slot(g.toDay - 1, true));
            safeUntil[i] = latest(start, Math.max(start.slot, from[i]), beforeLimit, null, -1) >= 0
                    ? beforeLimit : until[i];
            order[i] = i;
            for (int s = Math.max(0, from[i]); s <= until[i]; s++) {
                if ((s & 1) == 0) continue;
                coverAt[s] |= bit;
                if (s > safeUntil[i]) lateAt[s] |= bit;
            }
        }
        // Stable insertion sort, once per planning run (groups are already date ordered in the pack).
        for (int i = 1; i < size; i++) {
            int value = order[i];
            int j = i;
            while (j > 0 && safeUntil[order[j - 1]] > safeUntil[value]) {
                order[j] = order[j - 1];
                j--;
            }
            order[j] = value;
        }
        initial = done;
        matters = relevant & ~done;

        // Independent Tartarus blocks do not compete for rescue capacity. All real-pack rescue
        // windows are local to one block, with at most three groups in that component. Connect
        // blocks only when an actual window crosses their boundary (synthetic/custom packs).
        componentGroups = new long[m.t.nBlocks];
        safeGroupsInBlock = new long[m.t.nBlocks];
        long[] directGroups = new long[m.t.nBlocks];
        for (int b = 0; b < m.t.nBlocks; b++) {
            for (int i = 0; i < size; i++) {
                long bit = 1L << i;
                if (latestInBlock(b, from[i], until[i]) >= 0) directGroups[b] |= bit;
                if (latestInBlock(b, from[i], safeUntil[i]) >= 0) safeGroupsInBlock[b] |= bit;
            }
            componentGroups[b] = directGroups[b];
        }
        boolean changed;
        do {
            changed = false;
            for (int b = 0; b < m.t.nBlocks; b++) {
                for (int c = b + 1; c < m.t.nBlocks; c++) {
                    if ((componentGroups[b] & componentGroups[c]) == 0) continue;
                    long merged = componentGroups[b] | componentGroups[c];
                    if (merged != componentGroups[b] || merged != componentGroups[c]) changed = true;
                    componentGroups[b] = componentGroups[c] = merged;
                }
            }
        } while (changed);
        componentBlocks = new int[m.t.nBlocks][];
        componentOrder = new int[m.t.nBlocks][];
        for (int b = 0; b < m.t.nBlocks; b++) {
            int blockCount = 0;
            for (long direct : directGroups) if ((direct & componentGroups[b]) != 0) blockCount++;
            componentBlocks[b] = new int[blockCount];
            int next = 0;
            for (int c = 0; c < m.t.nBlocks; c++) {
                if ((directGroups[c] & componentGroups[b]) != 0) componentBlocks[b][next++] = c;
            }
            componentOrder[b] = new int[Long.bitCount(componentGroups[b])];
            next = 0;
            for (int i : order) if ((componentGroups[b] & (1L << i)) != 0) componentOrder[b][next++] = i;
            componentGroups[b] &= matters;
        }
        for (int s = 0; s < slots; s++) {
            long future = 0;
            for (int i = 0; i < size; i++) if (from[i] > s) future |= 1L << i;
            futureAt[s] = future;
        }
        int[] componentCounts = new int[m.t.nBlocks];
        for (int b = 0; b < componentCounts.length; b++) componentCounts[b] = componentBlocks[b].length;
        work = ThreadLocal.withInitial(() -> new Work(componentCounts));
    }

    private boolean fullyConfirmed(Rules.RescueGroup group, SimState start) {
        // 舞子's arrival group contains another person: her bit alone never settles both people.
        if (group.rescueIds.size() < group.count) return false;
        for (String id : group.rescueIds) {
            int found = -1;
            for (int i = 0; i < m.rules.rescues.length; i++) {
                if (id.equals(m.rules.rescues[i].id)) { found = i; break; }
            }
            if (found < 0 || (start.rescued & (1L << found)) == 0) return false;
        }
        return !group.rescueIds.isEmpty();
    }

    boolean hasGroups() { return size > 0; }

    long initialMask() { return initial; }

    /** Groups present on this evening; the caller has already established that the visit is legal. */
    long coveredOn(int slot) {
        return slot >= 0 && slot < coverAt.length ? coverAt[slot] : 0;
    }

    /** Forecast-only progress, separate from the confirmed Social Link rescue bits. */
    void recordVisit(SimState state, int slot) {
        long fresh = coveredOn(slot) & ~state.plannedRescueGroups;
        state.plannedRescueGroups |= fresh;
        if (slot >= 0 && slot < lateAt.length) state.plannedRescueGroupsLate |= fresh & lateAt[slot];
    }

    int missed(SimState end) {
        return Long.bitCount(matters & ~end.plannedRescueGroups);
    }

    int late(SimState end) {
        return Long.bitCount(matters & end.plannedRescueGroupsLate);
    }

    /** Required trips strictly after this slot; compatible groups count as one trip. */
    int pendingTrips(SimState state, int block) {
        if (componentGroups[block] == 0) return 0;
        Decision d = decision(state, block);
        return requested(later(state, block, d));
    }

    /**
     * Required future trips after covering today's groups. The proposed visit is not subtracted
     * from the cap: callers can compare {@code visits + 1 + result} with the cap without hiding a
     * future rescue that would become impossible when today's visit consumes the last allowance.
     */
    int pendingTripsAfterVisit(SimState state, int block) {
        if (componentGroups[block] == 0) return 0;
        Decision d = decision(state, block);
        return requested(afterVisit(state, block, d));
    }

    /** True when waiting saves a trip or preserves rescue capacity without losing the safety margin. */
    boolean waitForLaterArrivals(SimState state, int block) {
        if (!canVisit(state, block)) return false;
        long future = componentGroups[block] & ~state.plannedRescueGroups
                & futureAt[state.slot] & safeGroupsInBlock[block];
        if (future == 0) return false;
        return waitForLaterArrivals(state, block, decision(state, block));
    }

    private boolean waitForLaterArrivals(SimState state, int block, Decision d) {
        if (d.waitComputed) return d.wait;
        d.waitComputed = true;
        if ((componentGroups[block] & ~d.done & futureAt[state.slot] & safeGroupsInBlock[block]) == 0) return false;
        long delayed = now(state, block, d);
        long projected = withCap(state, block, d);
        // An unrelated group may already be impossible (a previous block's cap was exhausted).
        // It must not disable batching for the rest of the calendar.
        if (missing(projected) != missing(delayed)) d.wait = missing(projected) > missing(delayed);
        else if (lateCount(projected) != lateCount(delayed)) d.wait = lateCount(projected) > lateCount(delayed);
        else d.wait = trips(projected) + 1 > trips(delayed);
        return d.wait;
    }

    /** Strong priority once arrivals can share a trip; mandatory on the last safe usable evening. */
    double urgency(SimState state, int block) {
        if (!canVisit(state, block)
                || (coveredOn(state.slot) & componentGroups[block] & ~state.plannedRescueGroups) == 0) return 0;
        Decision d = decision(state, block);
        long now = now(state, block, d);
        long later = later(state, block, d);
        if (missing(later) > missing(now) || lateCount(later) > lateCount(now)) return Heuristics.MANDATORY;
        if (waitForLaterArrivals(state, block, d)) return 0;
        return RESCUE_PRIORITY;
    }

    private Decision decision(SimState state, int block) {
        Decision d = work.get().decisions[block];
        long done = state.plannedRescueGroups & componentGroups[block];
        int[] blocks = componentBlocks[block];
        boolean matches = d.slot == state.slot && d.done == done;
        if (matches) {
            for (int i = 0; i < blocks.length; i++) {
                if (d.visits[i] != state.towerVisits[blocks[i]]) { matches = false; break; }
            }
        }
        if (!matches) {
            d.reset(state.slot, done);
            for (int i = 0; i < blocks.length; i++) d.visits[i] = state.towerVisits[blocks[i]];
        }
        return d;
    }

    private long now(SimState state, int block, Decision d) {
        if (d.now == UNSET) d.now = schedule(state, state.slot, d.done, -1, block);
        return d.now;
    }

    private long later(SimState state, int block, Decision d) {
        if (d.later == UNSET) d.later = schedule(state, state.slot + 1, d.done, -1, block);
        return d.later;
    }

    private long afterVisit(SimState state, int block, Decision d) {
        if (d.afterVisit == UNSET) {
            long covered = coveredOn(state.slot) & componentGroups[block];
            d.afterVisit = (covered & ~d.done) == 0 ? later(state, block, d)
                    : schedule(state, state.slot + 1, d.done | covered, -1, block);
        }
        return d.afterVisit;
    }

    private long withCap(SimState state, int block, Decision d) {
        if (d.withCap == UNSET) {
            long after = afterVisit(state, block, d);
            // In a single-block component the same schedule remains valid when the proposed
            // visit leaves enough allowances. This is every component in the real pack.
            d.withCap = componentBlocks[block].length == 1
                    && requested(after) + state.towerVisits[block] + 1 <= m.towerCap[block] ? after
                    : schedule(state, state.slot + 1, d.done | coveredOn(state.slot), block, block);
        }
        return d.withCap;
    }

    private boolean canVisit(SimState state, int block) {
        return block >= 0 && block < m.t.nBlocks && state.slot >= 0 && state.slot < coverAt.length
                && m.t.towerBlock[state.slot] == block && state.towerVisits[block] < m.towerCap[block];
    }

    /**
     * Packed result: total trips, trips in requested block, missing groups, late groups (six bits each).
     * Scratch counts are per thread because a planning portfolio may run in parallel.
     */
    private long schedule(SimState state, int first, long done, int proposedBlock, int requestedBlock) {
        long remaining = componentGroups[requestedBlock] & ~done;
        if (remaining == 0) return 0;
        if (componentBlocks[requestedBlock].length == 1) {
            return scheduleInBlock(state, first, remaining, proposedBlock, requestedBlock);
        }
        int[] used = work.get().used;
        Arrays.fill(used, 0);
        if (proposedBlock >= 0) used[proposedBlock] = 1;
        int total = 0, requested = 0, missing = 0, late = 0;
        for (int i : componentOrder[requestedBlock]) {
            long bit = 1L << i;
            if ((remaining & bit) == 0) continue;
            int at = latest(state, Math.max(first, from[i]), safeUntil[i], used, -1);
            if (at < 0) at = latest(state, Math.max(first, from[i]), until[i], used, -1);
            if (at < 0) {
                missing++;
                remaining &= ~bit;
                continue;
            }
            int b = m.t.towerBlock[at];
            used[b]++;
            total++;
            if (b == requestedBlock) requested++;
            long saved = remaining & coverAt[at];
            late += Long.bitCount(saved & lateAt[at]);
            remaining &= ~saved;
        }
        return total | ((long) requested << 6) | ((long) missing << 12) | ((long) late << 18);
    }

    /** No arrays, block scans or scratch clearing on the real-pack scoring path. */
    private long scheduleInBlock(SimState state, int first, long remaining, int proposedBlock, int block) {
        int allowances = m.towerCap[block] - state.towerVisits[block] - (proposedBlock == block ? 1 : 0);
        int total = 0, missing = 0, late = 0;
        for (int i : componentOrder[block]) {
            if (remaining == 0) break;
            if (allowances <= 0) { missing += Long.bitCount(remaining); break; }
            long bit = 1L << i;
            if ((remaining & bit) == 0) continue;
            int fromSlot = Math.max(first, from[i]);
            int at = latestInBlock(block, fromSlot, safeUntil[i]);
            if (at < 0) at = latestInBlock(block, fromSlot, until[i]);
            if (at < 0) {
                missing++;
                remaining &= ~bit;
                continue;
            }
            total++;
            allowances--;
            long saved = remaining & coverAt[at];
            late += Long.bitCount(saved & lateAt[at]);
            remaining &= ~saved;
        }
        return total | ((long) total << 6) | ((long) missing << 12) | ((long) late << 18);
    }

    private int latest(SimState state, int first, int limit, int[] used, int onlyBlock) {
        int best = -1;
        for (int b = 0; b < m.t.nBlocks; b++) {
            if (onlyBlock >= 0 && onlyBlock != b) continue;
            if (state.towerVisits[b] + (used == null ? 0 : used[b]) >= m.towerCap[b]) continue;
            best = Math.max(best, latestInBlock(b, first, limit));
        }
        return best;
    }

    private int latestInBlock(int block, int first, int limit) {
        int last = Math.min(m.t.lastSlot, limit);
        if (last < 0 || first > last) return -1;
        int at = lastInBlock[block][last];
        return at >= first ? at : -1;
    }

    private static int trips(long result) { return (int) (result & 63); }
    private static int requested(long result) { return (int) ((result >>> 6) & 63); }
    private static int missing(long result) { return (int) ((result >>> 12) & 63); }
    private static int lateCount(long result) { return (int) ((result >>> 18) & 63); }
}
