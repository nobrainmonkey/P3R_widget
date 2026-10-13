package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Arrays;
import java.util.List;

/**
 * Base policy H and objective J of the planner (docs/DESIGN_v3.md §5.3–5.4), built once per plan.
 *
 * <p>All values are in objective units: one RANK of a "其余社群" link is worth {@link #W_OTHER}.
 * The base policy is a greedy over H that must be a pure function of (state, slot) so that the
 * fortified rollout's incumbent stays consistent: following the base policy from a state always
 * reproduces the incumbent's tail.
 *
 * <p>Design notes (the owner asked for "efficient" choices, e.g. a café shift beats idling):
 * <ul>
 *   <li>Meetings: weight × (rank-up ? 1 : 0.55) × (0.4 + scarcity). Scarcity is the share of the
 *       link's remaining calendar opportunities still needed to reach its target; links that can
 *       no longer make it fade out instead of hogging slots.</li>
 *   <li>Stats are valued only through what they unlock: link stat gates (value of the link, with
 *       an urgency term from the training evenings left before the link's last useful unlock day)
 *       and the Charm Lv6 ×1.51 bonus. Points above Lv6 are worthless.</li>
 *   <li>Money has a shadow price: what the best paid training could buy with it, full value while
 *       cash is below the near-term reserve (upcoming unlock costs + a training float), 15% above.</li>
 *   <li>Required Tartarus visits become mandatory as a block's open evenings run out; a rescue
 *       visit inside a missing-person window is mandatory when the link still matters.</li>
 *   <li>FREE is never chosen while any productive action is legal.</li>
 * </ul>
 */
final class Heuristics {
    static final double W_OTHER = 300;
    static final double MAX_BONUS_RANKS = 6;
    static final double EARLY_PER_SLOT = 0.002;
    static final double PENALTY = 1e6;
    static final int INF = 10_000;

    final Model m;
    final Tables t;
    final Rules rules;
    final int startSlot;
    final int lastSlot;

    /** Weight per rank up to the goal target (0 for non-goals). */
    final double[] wGoal;
    final double wOther;
    final int[] target;
    /** [link][rank] cumulative objective value of having that rank. */
    final double[][] val;
    final int[] goalLinks;

    /** [stat][slot] slots ≥ slot in which some allowed activity raises that stat. */
    final int[][] trainRemain;
    final double[] typicalGain;
    /** Best stat points per yen among paid activities, per stat. */
    final double[] ptsPerYen;
    final int[] cheapestPaid;
    /** Last slot at which unlocking the link still leaves enough meetings to reach its target. */
    final int[] latestUnlock;
    /** Objective value unlocked by establishing the link now (start of plan). */
    final double[] unlockValue;
    /** Meetings from a fresh rank 1 to the target (start multiplier). */
    final int[] meetsFromOne;
    final double charmGateValue;

    /** Required Tartarus visits per block (absolute visit count to reach), achievable from the start. */
    final int[] towerTarget;
    final boolean[] rescueMatters;

    /** Previous plan's action code per slot (stability bonus window only), or -1. */
    private final int[] prevCode;
    private final int stabilityEnd;

    // per-step scratch (the planner is single-threaded)
    private final int[] gateStat;
    private final double[] gateDeficit;
    private final double[] gateValue;
    private final double[] pointValue = new double[3];
    private int gates;
    private double yenValue;
    private long shortfall;
    private long reserve;
    private double mult;

    Heuristics(Model m, SimState start, Plan previous) {
        this.m = m;
        this.t = m.t;
        this.rules = m.rules;
        this.startSlot = start.slot;
        this.lastSlot = t.lastSlot;
        Profile p = m.profile;
        int n = t.nLinks;

        wGoal = new double[n];
        target = new int[n];
        wOther = p.otherLinks ? W_OTHER : 0;
        int[] mustOrder = tierOrder(p, Profile.Tier.MUST);
        int[] wantOrder = tierOrder(p, Profile.Tier.WANT);
        int goals = 0;
        for (int l = 0; l < n; l++) {
            target[l] = m.target(l);
            if (!m.goal[l]) continue;
            goals++;
            wGoal[l] = p.tier[l] == Profile.Tier.MUST ? 100_000 - 100.0 * mustOrder[l] : 8_000 - 10.0 * wantOrder[l];
        }
        goalLinks = new int[goals];
        for (int l = 0, i = 0; l < n; l++) if (m.goal[l]) goalLinks[i++] = l;
        val = new double[n][11];
        for (int l = 0; l < n; l++) {
            double v = 0;
            for (int r = 1; r <= 10; r++) {
                v += wRank(l, r);
                if (m.goal[l] && r == target[l]) v += MAX_BONUS_RANKS * wGoal[l];
                if (r == 10 && (!m.goal[l] || target[l] < 10)) v += MAX_BONUS_RANKS * wOther;
                val[l][r] = v;
            }
        }

        int ns = t.nStats;
        trainRemain = new int[ns][t.lastSlot + 2];
        typicalGain = new double[ns];
        ptsPerYen = new double[ns];
        cheapestPaid = new int[ns];
        buildStatTables();

        mult = Kernel.mult(m, start);
        meetsFromOne = new int[n];
        latestUnlock = new int[n];
        unlockValue = new double[n];
        double charmValue = 0;
        for (int l = 0; l < n; l++) {
            Rules.Link link = rules.links[l];
            if (link.kind != Rules.LinkKind.MANUAL) continue;
            int fromOne = meetingsBetween(l, 1, Kernel.eventPts(m, l, 1, mult), false, target[l], mult);
            meetsFromOne[l] = fromOne;
            int needSlots = fromOne + groupsLeft(l, 0);
            int s = t.lastSlot;
            while (s >= startSlot && t.meetRemain[l][s] < needSlots) s--;
            latestUnlock[l] = s;
            int remain = t.meetRemain[l][Math.max(startSlot, t.firstSlot)];
            double full = val[l][target[l]];
            unlockValue[l] = remain >= needSlots ? full : full * remain / Math.max(1.0, needSlots) * 0.6;
            if (start.rank[l] < target[l]) charmValue += val[l][target[l]] - val[l][start.rank[l]];
        }
        charmGateValue = 0.08 * charmValue;

        towerTarget = new int[t.nBlocks];
        for (int b = 0; b < t.nBlocks; b++) {
            int open = t.towerRemain[b][startSlot];
            int want = Math.min(p.towerVisitsPerBlock, m.towerCap);
            towerTarget[b] = Math.min(want, start.towerVisits[b] + open);
        }
        rescueMatters = new boolean[t.nRescues];
        for (int i = 0; i < t.nRescues; i++) {
            long bit = 1L << i;
            int l = t.rescueLink[i];
            boolean open = ((start.rescued | start.rescueFailed) & bit) == 0 && rescueEvenings(i) > 0;
            boolean linkMatters = l >= 0 && l < n && start.rank[l] < 10 && (m.goal[l] || wOther > 0);
            rescueMatters[i] = open && linkMatters;
        }

        prevCode = new int[t.lastSlot + 2];
        Arrays.fill(prevCode, -1);
        stabilityEnd = Math.min(t.lastSlot + 1, startSlot + 6);
        if (previous != null) loadPrevious(previous);

        gateStat = new int[n * 3 + 1];
        gateDeficit = new double[n * 3 + 1];
        gateValue = new double[n * 3 + 1];
    }

    private static int[] tierOrder(Profile p, Profile.Tier tier) {
        int n = p.tier.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> p.order[a] != p.order[b] ? Integer.compare(p.order[a], p.order[b]) : Integer.compare(a, b));
        int[] pos = new int[n];
        int k = 0;
        for (int i : idx) if (p.tier[i] == tier) pos[i] = k++;
        return pos;
    }

    /** Objective weight of reaching {@code rank} on link l. */
    double wRank(int l, int rank) {
        if (m.goal[l] && rank <= target[l]) return wGoal[l];
        return wOther;
    }

    private void buildStatTables() {
        int ns = t.nStats;
        double[] maxGain = new double[ns];
        Arrays.fill(cheapestPaid, Integer.MAX_VALUE);
        for (Rules.Activity a : rules.activities) {
            if (m.excluded[a.idx]) continue;
            for (int s = 0; s < ns && s < a.gain.length; s++) {
                if (a.gain[s] <= 0) continue;
                maxGain[s] = Math.max(maxGain[s], a.gain[s]);
                if (a.cost > 0) {
                    ptsPerYen[s] = Math.max(ptsPerYen[s], a.gain[s] / (double) a.cost);
                    cheapestPaid[s] = Math.min(cheapestPaid[s], a.cost);
                }
            }
        }
        for (int s = 0; s < ns; s++) {
            typicalGain[s] = Math.max(1.0, maxGain[s] * 0.75);
            int[] rem = trainRemain[s];
            for (int slot = t.lastSlot; slot >= 0; slot--) {
                boolean any = false;
                for (int a : t.actsAt[slot]) {
                    if (!m.excluded[a] && s < rules.activities[a].gain.length && rules.activities[a].gain[s] > 0) {
                        any = true;
                        break;
                    }
                }
                rem[slot] = rem[slot + 1] + (any ? 1 : 0);
            }
        }
    }

    private int rescueEvenings(int rescue) {
        int count = 0;
        int from = Math.max(startSlot, t.rescueFrom[rescue] * 2);
        int to = Math.min(t.lastSlot, t.rescueTo[rescue] * 2 + 1);
        for (int s = from; s <= to; s++) if (t.towerBlock[s] >= 0) count++;
        return count;
    }

    private void loadPrevious(Plan previous) {
        if (previous.steps == null) return;
        for (Plan.Step step : previous.steps) {
            if (step.slot < startSlot || step.slot >= stabilityEnd) continue;
            Action a = Action.parse(rules, step.action);
            if (a != null && a.isProductive()) prevCode[step.slot] = Kernel.encode(a);
        }
    }

    /** Consuming unlock step groups left for a link from step k. */
    int groupsLeft(int l, int k) {
        int n = t.stepCount[l];
        int groups = 0;
        while (k < n) {
            k = t.groupEnd[l][k] + 1;
            groups++;
        }
        return groups;
    }

    /**
     * Meetings needed from (rank, pts, ready) to reach {@code goalRank} under the model, or
     * {@link #INF} when an indefinite hold blocks the way.
     */
    int meetingsBetween(int l, int rank, int pts, boolean ready, int goalRank, double mult) {
        int g = Kernel.ordinaryPts(m, mult);
        int meetings = 0;
        int r = rank;
        int p = pts;
        boolean rdy = ready;
        while (r < goalRank) {
            if (!rdy && t.holdUntil[l][r + 1] == Tables.HOLD_FOREVER) return INF;
            int need = Kernel.need(m, l, r);
            if (rdy || p >= need) {
                meetings += 1;
            } else {
                meetings += (need - p + g - 1) / g + 1;
            }
            r++;
            p = Kernel.eventPts(m, l, r, mult);
            rdy = false;
        }
        return meetings;
    }

    // ------------------------------------------------------------------ base policy

    /** Prepare per-step values (gates, money) for scoring actions in {@code st.slot}. */
    void prepare(SimState st) {
        mult = Kernel.mult(m, st);
        int slot = st.slot;
        int day = slot >> 1;
        gates = 0;
        Arrays.fill(pointValue, 0);
        long unlockCosts = 0;
        double unlockYen = 0;
        for (int l = 0; l < t.nLinks; l++) {
            if (st.rank[l] != 0 || unlockValue[l] <= 0 || rules.links[l].kind != Rules.LinkKind.MANUAL) continue;
            if (Kernel.lost(m, st, l, day)) continue;
            double v = unlockValue[l];
            if (v <= 0) continue;
            int[] rs = m.reqStat[l];
            boolean statsMet = true;
            for (int i = 0; i < rs.length; i++) {
                int s = rs[i];
                if (s >= t.nStats) continue;
                int deficit = rules.stats[s].thresholds[m.reqStatLv[l][i]] - st.statPts[s];
                if (deficit <= 0) continue;
                statsMet = false;
                double need = deficit / typicalGain[s];
                int last = Math.max(slot, latestUnlock[l]);
                int avail = trainRemain[s][slot] - trainRemain[s][Math.min(t.lastSlot + 1, last + 1)];
                double urgency = latestUnlock[l] < slot ? 2 : Math.min(2, need / Math.max(1, avail));
                addGate(s, deficit, v * (1 + urgency));
                pointValue[s] += v * 0.9 / deficit;
            }
            int k = st.unlockStep[l];
            if (statsMet && k < t.stepCount[l]) {
                long cost = 0;
                List<Rules.Step> steps = rules.links[l].unlock.steps;
                for (int j = k; j < steps.size(); j++) cost += Math.max(0, steps.get(j).cost);
                if (cost > 0) {
                    unlockCosts += cost;
                    unlockYen = Math.max(unlockYen, v / cost);
                }
            }
        }
        int ci = t.charmIdx;
        if (ci >= 0 && m.charmMult > 1 && st.statPts[ci] < t.statCap[ci] && charmGateValue > 0) {
            int deficit = t.statCap[ci] - st.statPts[ci];
            double remainingShare = (t.lastSlot - slot) / (double) Math.max(1, t.lastSlot - startSlot);
            double v = charmGateValue * remainingShare;
            addGate(ci, deficit, v);
            pointValue[ci] += v * 0.9 / deficit;
        }
        double yen = unlockYen;
        long training = 0;
        for (int s = 0; s < t.nStats && s < 3; s++) {
            yen = Math.max(yen, (pointValue[s] + 0.2) * ptsPerYen[s]);
            if (pointValue[s] > 0 && cheapestPaid[s] != Integer.MAX_VALUE) training = Math.max(training, 3L * cheapestPaid[s]);
        }
        yenValue = Math.max(0.002, yen);
        reserve = unlockCosts + training + m.cashBuffer;
        shortfall = Math.max(0, reserve - st.cash);
    }

    private void addGate(int stat, int deficit, double value) {
        gateStat[gates] = stat;
        gateDeficit[gates] = deficit;
        gateValue[gates] = value;
        gates++;
    }

    /** H for one legal action; {@link #prepare} must have been called for this state. */
    double score(SimState st, int code) {
        double h;
        switch (Kernel.type(code)) {
            case Kernel.T_MEET:
                h = meetScore(st, Kernel.target(code));
                break;
            case Kernel.T_UNLOCK:
                h = unlockScore(st, Kernel.target(code));
                break;
            case Kernel.T_ACT:
                h = activityScore(st, Kernel.target(code));
                break;
            case Kernel.T_TOWER:
                h = towerScore(st, Kernel.target(code));
                break;
            default:
                return 0;
        }
        int slot = st.slot;
        if (slot < stabilityEnd && prevCode[slot] == code) h += 0.02 * Math.abs(h) + 1e-3;
        return h;
    }

    /** The base policy's choice among {@code n} legal codes (FREE when none). */
    int choose(SimState st, int[] codes, int n) {
        if (n == 0) return Kernel.FREE;
        prepare(st);
        int best = codes[0];
        double bestH = score(st, best);
        for (int i = 1; i < n; i++) {
            double h = score(st, codes[i]);
            if (h > bestH) {
                bestH = h;
                best = codes[i];
            }
        }
        return best;
    }

    /** Milestone rank the link is currently working towards (target, then MAX if others count). */
    int milestone(int l, int rank) {
        if (rank < target[l]) return target[l];
        return wOther > 0 ? 10 : rank;
    }

    /** Share of the link's remaining opportunities needed to reach its milestone (≥ 0). */
    double pressure(SimState st, int l) {
        int r = st.rank[l];
        int goalRank = milestone(l, r);
        if (goalRank <= r) return 0;
        int need;
        if (r == 0) {
            need = groupsLeft(l, st.unlockStep[l]) + meetsFromOne[l];
        } else {
            need = meetingsBetween(l, r, st.pts[l], st.ready[l], goalRank, Kernel.mult(m, st));
        }
        if (need >= INF) return INF;
        return need / (double) Math.max(1, t.meetRemain[l][st.slot]);
    }

    private static double scarcity(double ratio) {
        if (ratio <= 1) return ratio;
        return Math.max(0.25, 2 - ratio);
    }

    private double meetScore(SimState st, int l) {
        int r = st.rank[l];
        int goalRank = milestone(l, r);
        double w = wRank(l, r + 1);
        if (goalRank <= r || w <= 0) return 0.01;
        double ratio = pressure(st, l);
        boolean up = Kernel.ranksUp(m, st, l, st.slot >> 1);
        if (ratio >= INF) return up ? w * 0.5 : 0.02;
        double h = w * (up ? 1.0 : 0.55) * (0.4 + scarcity(ratio));
        if (up && r + 1 == goalRank) h *= 1.3;
        return h;
    }

    private double unlockScore(SimState st, int l) {
        double w = wRank(l, 1);
        if (w <= 0) return 0.01;
        double ratio = pressure(st, l);
        if (ratio >= INF) return 0.05;
        return w * 1.2 * (0.4 + scarcity(ratio));
    }

    private double activityScore(SimState st, int act) {
        Rules.Activity a = rules.activities[act];
        double h = 0;
        int ns = Math.min(a.gain.length, t.nStats);
        for (int s = 0; s < ns; s++) {
            int gain = a.gain[s];
            if (gain <= 0) continue;
            int useful = Math.min(gain, Math.max(0, t.statCap[s] - st.statPts[s]));
            h += 0.2 * useful;
            for (int g = 0; g < gates; g++) {
                if (gateStat[g] != s) continue;
                h += gateValue[g] * 0.9 * Math.min(1.0, gain / gateDeficit[g]);
            }
        }
        if (a.cost > 0) h -= costPenalty(st.cash, a.cost);
        if (a.kind == Rules.ActivityKind.JOB) {
            h += moneyValue(a.payFor(st.shifts[act] + 1)) + 0.25;
        }
        return h;
    }

    private double towerScore(SimState st, int block) {
        int day = st.slot >> 1;
        for (int i = 0; i < t.nRescues; i++) {
            long bit = 1L << i;
            if (rescueMatters[i] && day >= t.rescueFrom[i] && day <= t.rescueTo[i]
                    && ((st.rescued | st.rescueFailed) & bit) == 0) {
                return 200_000;
            }
        }
        double income = m.towerIncome > 0 ? moneyValue(m.towerIncome) : 0;
        int remaining = towerTarget[block] - st.towerVisits[block];
        if (remaining > 0) {
            double ratio = remaining / (double) Math.max(1, t.towerRemain[block][st.slot]);
            if (ratio >= 0.5) return 50_000 * ratio;
            return 120 + 400 * ratio + income;
        }
        return 3 + income;
    }

    double moneyValue(long yen) {
        if (yen <= 0) return 0;
        long urgent = Math.min(yen, shortfall);
        return yenValue * (urgent + 0.15 * (yen - urgent)) + 0.001 * yen;
    }

    private double costPenalty(long cash, long cost) {
        long after = cash - cost;
        long intoReserve = Math.max(0, Math.min(cost, reserve - after));
        return yenValue * (intoReserve + 0.15 * (cost - intoReserve));
    }

    // ------------------------------------------------------------------ objective

    /** Objective J of a complete trajectory ending in {@code end}; {@code reached[i]} per goal link. */
    double objective(SimState end, int[] reached) {
        double j = 0;
        for (int l = 0; l < t.nLinks; l++) j += val[l][end.rank[l]];
        for (int i = 0; i < goalLinks.length; i++) {
            if (reached[i] >= 0) j += wGoal[goalLinks[i]] * EARLY_PER_SLOT * (lastSlot - reached[i]);
        }
        for (int s = 0; s < t.nStats; s++) j += 0.5 * Kernel.statLv(m, end, s);
        j += 0.0001 * end.cash;
        int unmet = 0;
        for (int b = 0; b < t.nBlocks; b++) unmet += Math.max(0, towerTarget[b] - end.towerVisits[b]);
        for (int i = 0; i < t.nRescues; i++) {
            if (rescueMatters[i] && (end.rescued & (1L << i)) == 0) unmet++;
        }
        return j - PENALTY * unmet;
    }

    /** Record the slot at which each goal first reaches its target. */
    void track(SimState st, int slot, int[] reached) {
        for (int i = 0; i < goalLinks.length; i++) {
            if (reached[i] < 0 && st.rank[goalLinks[i]] >= target[goalLinks[i]]) reached[i] = slot;
        }
    }

    /** Initial goal-reached slots for a plan starting at {@code start}. */
    int[] initialReached(SimState start) {
        int[] reached = new int[goalLinks.length];
        for (int i = 0; i < reached.length; i++) {
            reached[i] = start.rank[goalLinks[i]] >= target[goalLinks[i]] ? startSlot : -1;
        }
        return reached;
    }

    // ------------------------------------------------------------------ explanations (used by Labels)

    /** Stat gates that made this activity attractive, strongest first: {stat, linkOrMinusOne} pairs. */
    int dominantGateLink(SimState st, int stat) {
        int day = st.slot >> 1;
        int best = -1;
        double bestV = 0;
        for (int l = 0; l < t.nLinks; l++) {
            if (st.rank[l] != 0 || unlockValue[l] <= 0 || Kernel.lost(m, st, l, day)) continue;
            int[] rs = m.reqStat[l];
            for (int i = 0; i < rs.length; i++) {
                if (rs[i] != stat) continue;
                int deficit = rules.stats[stat].thresholds[m.reqStatLv[l][i]] - st.statPts[stat];
                if (deficit > 0 && unlockValue[l] > bestV) {
                    bestV = unlockValue[l];
                    best = l;
                }
            }
        }
        return best;
    }

    long shortfall() { return shortfall; }

    long reserve() { return reserve; }
}
