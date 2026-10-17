package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Static calendar tables derived once from a {@link Rules} pack (docs/DESIGN_v3.md §5.4 "precompute
 * static tables once per plan"). Everything here depends only on the rule pack and on whether the
 * player accepted a 12/24 invitation (both slots of that day then carry the lock
 * {@link Rules#LOCK_CHRISTMAS}), never on the rest of the profile or the state, so one instance can
 * be shared by every simulation over the same pack and Christmas Eve choice.
 *
 * <p>Slot-indexed arrays use absolute slot numbers ({@code day*2 + evening}) and have length
 * {@code lastSlot + 2}, so suffix counts can be read at {@code lastSlot + 1} (= 0) without a bounds
 * check. Day-indexed arrays have length {@code lastDay + 1}.
 */
final class Tables {
    /** Sentinel in {@link #holdUntil}: no hold on the rank-up into that rank. */
    static final int NO_HOLD = Integer.MIN_VALUE;
    /** Sentinel in {@link #holdUntil}: held until the player reports progress. */
    static final int HOLD_FOREVER = Integer.MAX_VALUE;

    final Rules rules;
    /** Day whose both slots are taken by an accepted 12/24 invitation, or -1. */
    final int christmasDay;
    final int firstSlot;
    final int lastSlot;
    final int lastDay;
    final int nLinks;
    final int nActs;
    final int nBlocks;
    final int nStats;
    final int nRescues;
    /** Link Episode tracks ({@link Rules#episodes}). */
    final int nEps;

    /** Lock reason id per slot, null when the slot is free. */
    final String[] lock;
    /** Bit L set: link L has an ordinary meeting (O cell, matching slot kind) or an invitation (D cell, daytime). */
    final int[] meetMask;
    /** Bit L set: the meeting possible in this slot is a holiday invitation. */
    final int[] inviteMask;
    /** Activities allowed by the calendar in this slot (slot kind, weekday, day type, date range, lock). */
    final int[][] actsAt;
    /** [activity][slot] remaining calendar opportunities, including this slot. */
    final int[][] actRemain;
    /** Tartarus block index enterable in this (evening) slot, or -1. */
    final int[] towerBlock;
    /** [link][slot] calendar meeting opportunities in {@code [slot, lastSlot]}, missing windows excluded. */
    final int[][] meetRemain;
    /** [block][slot] enterable Tartarus evenings of that block in {@code [slot, lastSlot]}. */
    final int[][] towerRemain;

    /** Missing-person window per link (days, inclusive) or -1 when none. */
    final int[] missFrom;
    final int[] missTo;
    /** Rescue index that ends the link's missing window, or -1. */
    final int[] missRescue;
    final int[] rescueFrom;
    final int[] rescueTo;
    final int[] rescueLink;

    /** Story links: [link][day] rank granted by the story on or before that day; null for manual links. */
    final int[][] storyRankBy;
    /** [link][rank] hold on the rank-up into {@code rank}: {@link #NO_HOLD}, {@link #HOLD_FOREVER} or the first free day. */
    final int[][] holdUntil;

    /** Unlock steps per link. */
    final int[] stepCount;
    /** Index of the step that establishes the link (sets rank 1). */
    final int[] establishIdx;
    /**
     * [link][k] last index of the step group a slot-consuming unlock action starting at step k
     * covers, or -1 when step k can only happen as a side step. Non-consuming steps fold into the
     * next consuming step only when every later step of the chain has minGapDays 0 and the slot kind
     * of the consuming step, so the whole chain can happen in that one slot.
     */
    final int[][] groupEnd;
    /** [link][k] step k does not consume the slot (it is done "on the side" of another action). */
    final boolean[][] sideStep;
    /** Bit L set: link L has at least one non-consuming unlock step. */
    final int sideMask;
    /** [link][k] steps that consume a slot from step k to the end of the chain. */
    final int[][] slotStepsLeft;
    /** [link][k] minimum days from the day of step k-1 (or now) until the last step can happen. */
    final int[][] gapDaysLeft;
    /** [link][k][day] the step's own day rule and fromDay allow that day. */
    final boolean[][][] stepDayOk;
    /** Bit L set: some unlock step of link L costs money (an unlock fee). */
    final int feeMask;
    /**
     * Links with an unlock fee only ({@link #feeMask}, null for the others): [link][k][day] the first
     * day ≥ day on which step k's day rule allows it and the slot of its kind is open, or
     * {@code lastDay + 1} when none is left.
     */
    final int[][][] nextStepDay;
    /** Episodes per track (0 for a track without a calendar). */
    final int[] epCount;
    /** Track episodes are watched in the evening. */
    final boolean[] epEve;
    /**
     * [track][n][slot] open slots ≥ slot of the track's kind on days that allow episode n+1 (n is
     * 0-based; length count, each lastSlot + 2).
     */
    final int[][][] epRemain;
    /**
     * [track][n] latest slot for episode n+1 that still leaves every later episode a later slot
     * (backward earliest-deadline schedule). When the rest of the track cannot fit at all
     * ({@link #epChain} false), the latest slot of episode n+1 on its own; -1 when it has none.
     */
    final int[][] epLatest;
    /** [track][n] episodes n+1 … count can all still be watched in order when n+1 is watched by {@link #epLatest}. */
    final boolean[][] epChain;
    /** Index of the charm stat, or -1. */
    final int charmIdx;
    /** thresholds of each stat at Lv6 (points at which further gains are useless). */
    final int[] statCap;

    private static volatile Tables cached;
    private static volatile Tables cachedChristmas;

    /** Tables for a pack with every 12/24 invitation declined (see {@link #of(Rules, boolean)}). */
    static Tables of(Rules rules) {
        return of(rules, false);
    }

    /**
     * Tables for a pack, reusing the last instance when called again with the same pack object and
     * the same Christmas Eve choice. With {@code christmasTaken} both slots of 12/24 are locked
     * ({@link Rules#LOCK_CHRISTMAS}) unless the pack already locks them.
     */
    static Tables of(Rules rules, boolean christmasTaken) {
        Tables t = christmasTaken ? cachedChristmas : cached;
        if (t != null && t.rules == rules) return t;
        t = new Tables(rules, christmasTaken ? rules.christmasEveDay() : -1);
        if (christmasTaken) cachedChristmas = t;
        else cached = t;
        return t;
    }

    Tables(Rules rules) {
        this(rules, -1);
    }

    Tables(Rules rules, int christmasDay) {
        this.rules = rules;
        this.christmasDay = christmasDay;
        this.firstSlot = rules.firstSlot();
        this.lastSlot = rules.lastSlot();
        this.lastDay = rules.lastDay;
        this.nLinks = rules.links.length;
        this.nActs = rules.activities.length;
        this.nBlocks = rules.blocks.length;
        this.nStats = rules.stats.length;
        this.nRescues = rules.rescues.length;
        this.nEps = rules.episodes == null ? 0 : rules.episodes.length;
        if (nLinks > 30) throw new IllegalArgumentException("too many links: " + nLinks);
        if (nRescues > 62) throw new IllegalArgumentException("too many rescues: " + nRescues);

        int size = lastSlot + 2;
        lock = new String[size];
        meetMask = new int[size];
        inviteMask = new int[size];
        actsAt = new int[size][];
        towerBlock = new int[size];

        missFrom = new int[nLinks];
        missTo = new int[nLinks];
        missRescue = new int[nLinks];
        rescueFrom = new int[nRescues];
        rescueTo = new int[nRescues];
        rescueLink = new int[nRescues];
        buildRescues();

        int[] noActs = new int[0];
        List<Integer> tmp = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            towerBlock[slot] = -1;
            actsAt[slot] = noActs;
            if (slot > lastSlot || slot < firstSlot) {
                lock[slot] = "out_of_range";
                continue;
            }
            lock[slot] = rules.lockOf(slot);
            if (lock[slot] == null && christmasDay >= 0 && Rules.dayOf(slot) == christmasDay) lock[slot] = Rules.LOCK_CHRISTMAS;
            if (lock[slot] != null) continue;
            int day = Rules.dayOf(slot);
            boolean eve = Rules.isEvening(slot);
            Rules.Day d = rules.day(day);
            buildMeetCell(slot, day, eve);
            tmp.clear();
            for (Rules.Activity a : rules.activities) {
                if (activityOnCalendar(a, d, eve)) tmp.add(a.idx);
            }
            if (!tmp.isEmpty()) actsAt[slot] = toArray(tmp);
            if (eve && d.tartarus && d.block >= 0 && d.block < nBlocks) towerBlock[slot] = d.block;
        }

        actRemain = new int[nActs][size];
        for (int slot = lastSlot; slot >= 0; slot--) {
            for (int a = 0; a < nActs; a++) actRemain[a][slot] = actRemain[a][slot + 1];
            for (int a : actsAt[slot]) actRemain[a][slot]++;
        }
        meetRemain = new int[nLinks][size];
        for (int l = 0; l < nLinks; l++) {
            int[] rem = meetRemain[l];
            for (int slot = lastSlot; slot >= 0; slot--) {
                int day = Rules.dayOf(slot);
                boolean usable = (meetMask[slot] & (1 << l)) != 0 && !(missFrom[l] >= 0 && day >= missFrom[l] && day <= missTo[l]);
                rem[slot] = rem[slot + 1] + (usable ? 1 : 0);
            }
        }
        towerRemain = new int[nBlocks][size];
        for (int b = 0; b < nBlocks; b++) {
            int[] rem = towerRemain[b];
            for (int slot = lastSlot; slot >= 0; slot--) rem[slot] = rem[slot + 1] + (towerBlock[slot] == b ? 1 : 0);
        }

        storyRankBy = new int[nLinks][];
        holdUntil = new int[nLinks][11];
        stepCount = new int[nLinks];
        establishIdx = new int[nLinks];
        groupEnd = new int[nLinks][];
        sideStep = new boolean[nLinks][];
        slotStepsLeft = new int[nLinks][];
        gapDaysLeft = new int[nLinks][];
        stepDayOk = new boolean[nLinks][][];
        int side = 0;
        for (Rules.Link link : rules.links) {
            buildLink(link);
            for (boolean b : sideStep[link.idx]) if (b) side |= 1 << link.idx;
        }
        sideMask = side;
        int fees = 0;
        nextStepDay = new int[nLinks][][];
        for (Rules.Link link : rules.links) {
            List<Rules.Step> steps = link.unlock.steps;
            boolean paid = false;
            for (Rules.Step s : steps) paid |= s.cost > 0;
            if (!paid) continue;
            fees |= 1 << link.idx;
            int l = link.idx;
            nextStepDay[l] = new int[steps.size()][lastDay + 2];
            for (int k = 0; k < steps.size(); k++) {
                int[] next = nextStepDay[l][k];
                int kind = steps.get(k).evening ? 1 : 0;
                next[lastDay + 1] = lastDay + 1;
                for (int day = lastDay; day >= 0; day--) {
                    boolean ok = day < stepDayOk[l][k].length && stepDayOk[l][k][day] && open(day * 2 + kind);
                    next[day] = ok ? day : next[day + 1];
                }
            }
        }
        feeMask = fees;

        epCount = new int[nEps];
        epEve = new boolean[nEps];
        epRemain = new int[nEps][][];
        epLatest = new int[nEps][];
        epChain = new boolean[nEps][];
        for (int e = 0; e < nEps; e++) buildEpisodes(e, rules.episodes[e]);

        int ci = -1;
        statCap = new int[nStats];
        for (Rules.Stat s : rules.stats) {
            if ("charm".equals(s.id)) ci = s.idx;
            statCap[s.idx] = s.thresholds[6];
        }
        charmIdx = ci;
    }

    private void buildRescues() {
        for (int l = 0; l < nLinks; l++) {
            missFrom[l] = -1;
            missTo[l] = -1;
            missRescue[l] = -1;
        }
        for (int i = 0; i < nRescues; i++) {
            Rules.Rescue r = rules.rescues[i];
            rescueFrom[i] = r.fromDay;
            rescueTo[i] = r.toDay;
            rescueLink[i] = r.link;
        }
        for (Rules.Link link : rules.links) {
            int l = link.idx;
            if (link.missing != null) {
                missFrom[l] = link.missing.fromDay;
                missTo[l] = link.missing.toDay;
                missRescue[l] = rescueIndex(link.missing.rescueId);
            }
        }
        // A rescue that names a link but the link declares no window: use the rescue window.
        for (int i = 0; i < nRescues; i++) {
            int l = rescueLink[i];
            if (l >= 0 && l < nLinks && missFrom[l] < 0) {
                missFrom[l] = rescueFrom[i];
                missTo[l] = rescueTo[i];
                missRescue[l] = i;
            }
        }
    }

    private int rescueIndex(String id) {
        if (id == null) return -1;
        for (int i = 0; i < nRescues; i++) if (id.equals(rules.rescues[i].id)) return i;
        return -1;
    }

    private void buildMeetCell(int slot, int day, boolean eve) {
        for (Rules.Link link : rules.links) {
            if (link.kind != Rules.LinkKind.MANUAL) continue;
            int bit = 1 << link.idx;
            boolean ordinary = link.avail.get(day) && link.evening == eve;
            boolean invitation = !eve && link.invite.get(day);
            if (ordinary || invitation) meetMask[slot] |= bit;
            if (invitation && !ordinary) inviteMask[slot] |= bit;
        }
    }

    private static boolean activityOnCalendar(Rules.Activity a, Rules.Day d, boolean eve) {
        if (eve ? !a.eve : !a.day) return false;
        if (d.d < a.fromDay || d.d > a.toDay) return false;
        if (a.fixedDays != null && java.util.Arrays.binarySearch(a.fixedDays, d.d) < 0) return false;
        if (!a.allowsWeekday(d.wd)) return false;
        return a.dayTypes.isEmpty() || a.dayTypes.contains(d.type);
    }

    private void buildLink(Rules.Link link) {
        int l = link.idx;
        if (link.kind == Rules.LinkKind.STORY && link.storyRankDay != null) {
            int[] by = new int[lastDay + 1];
            for (int day = 0; day <= lastDay; day++) {
                int best = 0;
                for (int r = 1; r < link.storyRankDay.length && r <= 10; r++) {
                    int sd = link.storyRankDay[r];
                    if (sd >= 0 && sd <= day) best = r;
                }
                by[day] = best;
            }
            storyRankBy[l] = by;
        }
        java.util.Arrays.fill(holdUntil[l], NO_HOLD);
        for (Rules.Hold h : link.holds) {
            if (h.rank < 1 || h.rank > 10) continue;
            int until = h.untilDay < 0 ? HOLD_FOREVER : h.untilDay;
            holdUntil[l][h.rank] = Math.max(holdUntil[l][h.rank], until);
        }
        List<Rules.Step> steps = link.unlock.steps;
        int n = steps.size();
        stepCount[l] = n;
        int est = n - 1;
        for (int k = 0; k < n; k++) {
            if (steps.get(k).establishes) {
                est = k;
                break;
            }
        }
        establishIdx[l] = est;
        groupEnd[l] = new int[n];
        sideStep[l] = new boolean[n];
        for (int k = 0; k < n; k++) {
            // The last step always acts as the consuming one (it establishes the link).
            boolean consumes = steps.get(k).consumesSlot || k == n - 1;
            sideStep[l][k] = !consumes;
            int c = k;
            while (c < n - 1 && !steps.get(c).consumesSlot) c++;
            boolean foldable = true;
            for (int j = k + 1; j <= c; j++) {
                Rules.Step s = steps.get(j);
                if (s.minGapDays > 0 || steps.get(j - 1).evening != steps.get(c).evening) foldable = false;
            }
            groupEnd[l][k] = foldable ? c : -1;
        }
        slotStepsLeft[l] = new int[n + 1];
        gapDaysLeft[l] = new int[n + 1];
        for (int k = n - 1; k >= 0; k--) {
            slotStepsLeft[l][k] = slotStepsLeft[l][k + 1] + (sideStep[l][k] ? 0 : 1);
            gapDaysLeft[l][k] = gapDaysLeft[l][k + 1] + Math.max(0, steps.get(k).minGapDays);
        }
        stepDayOk[l] = new boolean[n][];
        for (int k = 0; k < n; k++) stepDayOk[l][k] = stepDays(link, steps.get(k));
    }

    private void buildEpisodes(int e, Rules.Episode ep) {
        int count = Math.max(0, Math.min(31, ep.count));
        int size = lastSlot + 2;
        epCount[e] = count;
        epEve[e] = ep.evening;
        epRemain[e] = new int[count][size];
        epLatest[e] = new int[count];
        int kind = ep.evening ? 1 : 0;
        for (int slot = lastSlot; slot >= 0; slot--) {
            boolean usable = (slot & 1) == kind && open(slot);
            int day = slot >> 1;
            int mask = usable && ep.dayMask != null && day < ep.dayMask.length ? ep.dayMask[day] : 0;
            for (int n = 0; n < count; n++) {
                epRemain[e][n][slot] = epRemain[e][n][slot + 1] + ((mask & (1 << n)) != 0 ? 1 : 0);
            }
        }
        epChain[e] = new boolean[count];
        boolean chain = true;
        for (int n = count - 1; n >= 0; n--) {
            int limit = chain && n + 1 < count ? epLatest[e][n + 1] - 1 : lastSlot;
            int latest = latestAllowed(e, n, limit);
            if (latest < 0 && limit < lastSlot) {
                chain = false; // the later episodes leave no room: fall back to this episode alone
                latest = latestAllowed(e, n, lastSlot);
            }
            if (latest < 0) chain = false;
            epLatest[e][n] = latest;
            epChain[e][n] = chain;
        }
    }

    /** Latest slot ≤ {@code limit} that allows episode n+1 of track e, or -1. */
    private int latestAllowed(int e, int n, int limit) {
        int[] rem = epRemain[e][n];
        for (int slot = Math.min(limit, lastSlot); slot >= firstSlot; slot--) {
            if (rem[slot] > rem[slot + 1]) return slot;
        }
        return -1;
    }

    /**
     * Slack of episode {@code n} (0-based) of track e at {@code slot}: its allowed open slots from
     * {@code slot} up to its latest slot that keeps the rest of the track possible (all its remaining
     * slots when that latest slot has passed). At least 1 when the episode is possible now.
     */
    int episodeSlack(int e, int n, int slot) {
        int[] rem = epRemain[e][n];
        int latest = epLatest[e][n];
        int all = rem[Math.min(slot, lastSlot + 1)];
        if (latest < slot) return all;
        return all - rem[Math.min(lastSlot + 1, latest + 1)];
    }

    private boolean[] stepDays(Rules.Link link, Rules.Step step) {
        boolean[] ok = new boolean[lastDay + 1];
        for (int day = Math.max(0, step.fromDay); day <= lastDay; day++) {
            Rules.Day d = rules.day(day);
            if (d == null) continue;
            switch (step.dayRule) {
                case LINK_AVAIL:
                    ok[day] = link.meetsOn(day);
                    break;
                case WEEKDAYS:
                    ok[day] = contains(step.weekdays, d.wd);
                    break;
                case FIXED:
                    ok[day] = contains(step.fixedDays, day);
                    break;
                default:
                    ok[day] = true;
                    break;
            }
        }
        return ok;
    }

    /** True if the slot is inside the calendar and not locked. */
    boolean open(int slot) {
        return slot >= firstSlot && slot <= lastSlot && lock[slot] == null;
    }

    static boolean contains(int[] values, int v) {
        for (int x : values) if (x == v) return true;
        return false;
    }

    private static int[] toArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
