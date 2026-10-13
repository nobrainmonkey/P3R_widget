package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Arrays;
import java.util.List;

/**
 * Base policy H and objective J of the planner (docs/DESIGN_v3.md §5.3–5.4), built once per plan.
 *
 * <p>Everything is measured in objective units: one RANK of a "其余社群" link is worth
 * {@link #W_OTHER}, MAX adds six ranks' worth, a 「必须」 goal weighs 100 000 per rank. The base
 * policy is a greedy over H and must be a pure function of (state, slot): the fortified rollout
 * relies on "follow the base policy from here" reproducing the incumbent's tail exactly.
 *
 * <p>How H values each kind of action (the owner's rule of thumb: some things are simply more
 * efficient than others — a café shift beats idling, a scarce meeting beats a stat snack):
 * <ul>
 *   <li><b>Meeting / unlock step</b>: the objective value still to gain on that link divided by
 *       the meetings it needs (so links close to MAX are worth more per meeting), scaled by
 *       urgency: the share of the link's remaining calendar opportunities it still needs,
 *       multiplied by the contention of that slot kind (all links' needs ÷ open slots). Links that
 *       can no longer reach their milestone keep only their per-rank value.</li>
 *   <li><b>Stats</b> are worth what they unlock: link stat gates (the link's value, discounted by
 *       contention, with an urgency term from the training slots left before the link's last
 *       useful unlock) and the Charm Lv6 ×1.51 bonus, valued by the meetings it saves while the
 *       calendar is overbooked. Points above Lv6 are worthless.</li>
 *   <li><b>Money</b> has a shadow price (what paid training or a paid unlock buys) that applies
 *       only up to the near-term need (unlock costs whose other prerequisites are met + a small
 *       training float); spare cash is worth almost nothing, so jobs never crowd out meetings
 *       just to pile up yen.</li>
 *   <li><b>Tartarus</b>: required visits become mandatory as a block's open evenings run out; a
 *       visit inside a missing-person window is mandatory while the link still matters.</li>
 *   <li>FREE is never chosen while any productive action is legal.</li>
 * </ul>
 */
final class Heuristics {
    static final double W_OTHER = 300;
    static final double MAX_BONUS_RANKS = 6;
    static final double EARLY_PER_SLOT = 0.002;
    static final double PENALTY = 1e6;
    static final int INF = 10_000;
    /**
     * Score of a legal action the planner refuses to take (a Tartarus visit that would use up the
     * cap needed for a pending rescue in the same block). FREE beats it.
     */
    static final double VETO = -1e9;
    /**
     * Upper bound of the yen shadow price derived from paid training (a ¥2,500 shift is worth at most
     * ~3/4 of a scarce meeting). Fees of a wanted unlock are valued without this cap.
     */
    static final double MAX_STAT_YEN_VALUE = 0.12;
    /** Start saving for an unlock fee this many days before the link's earliest day. */
    static final int MONEY_LOOKAHEAD_DAYS = 21;

    /**
     * Knobs of the base policy. The planner runs a small portfolio of tunings and keeps the plan with
     * the best objective, which hedges against any single calibration fitting a rule pack badly.
     */
    static final class Tuning {
        /** Multiplier of stat-gate values (how eagerly stats are trained ahead of meetings). */
        final double statWeight;
        /** Multiplier of the yen shadow price (how eagerly jobs are taken). */
        final double moneyWeight;
        /** Urgency floor: value share kept by a meeting or gate that could also happen later. */
        final double patience;

        Tuning(double statWeight, double moneyWeight, double patience) {
            this.statWeight = statWeight;
            this.moneyWeight = moneyWeight;
            this.patience = patience;
        }

        static final Tuning DEFAULT = new Tuning(1.0, 1.0, 0.3);
    }

    final Model m;
    final Tables t;
    final Rules rules;
    final Tuning tuning;
    final int startSlot;
    final int lastSlot;

    /** Weight per rank up to the goal target (0 for non-goals). */
    final double[] wGoal;
    final double wOther;
    final int[] target;
    /** [link][rank] cumulative objective value of having that rank. */
    final double[][] val;
    final int[] goalLinks;

    /** [kind][slot] open (unlocked) slots of that kind (0 daytime, 1 evening) in [slot, lastSlot]. */
    final int[][] openRemain;
    /** [stat][slot] slots ≥ slot in which some allowed activity raises that stat. */
    final int[][] trainRemain;
    final double[] typicalGain;
    /** Best stat points per yen among paid activities, per stat. */
    final double[] ptsPerYen;
    final int[] cheapestPaid;
    /** Last slot at which unlocking the link still leaves enough meetings to reach its target. */
    final int[] latestUnlock;
    /** Objective value of establishing the link (seen from the start of the plan). */
    final double[] unlockValue;
    /** Meetings from a fresh rank 1 to the target (start multiplier). */
    final int[] meetsFromOne;

    /** Required Tartarus visits per block (absolute visit count to reach), achievable from the start. */
    final int[] towerTarget;
    final boolean[] rescueMatters;
    /** [rescue][block] last enterable evening of that block inside the rescue window, or -1. */
    private final int[][] rescueLastSlot;

    /** Previous plan's action code per slot (stability bonus window only), or -1. */
    private final int[] prevCode;
    private final int stabilityEnd;

    // ---- per-step scratch, filled by prepare() (the planner is single-threaded)
    private final int[] demand;
    private final double[] perMeeting;
    private final double[] load = new double[2];
    private final int[] gateStat;
    private final double[] gateDeficit;
    private final double[] gateValue;
    private final double[] pointValue = new double[3];
    private int gates;
    private double yenValue;
    private long shortfall;
    private long reserve;

    Heuristics(Model m, SimState start, Plan previous) {
        this(m, start, previous, Tuning.DEFAULT);
    }

    Heuristics(Model m, SimState start, Plan previous, Tuning tuning) {
        this.m = m;
        this.t = m.t;
        this.rules = m.rules;
        this.tuning = tuning;
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

        openRemain = new int[2][t.lastSlot + 2];
        for (int s = t.lastSlot; s >= 0; s--) {
            openRemain[0][s] = openRemain[0][s + 1] + ((s & 1) == 0 && t.open(s) ? 1 : 0);
            openRemain[1][s] = openRemain[1][s + 1] + ((s & 1) == 1 && t.open(s) ? 1 : 0);
        }
        int ns = t.nStats;
        trainRemain = new int[ns][t.lastSlot + 2];
        typicalGain = new double[ns];
        ptsPerYen = new double[ns];
        cheapestPaid = new int[ns];
        buildStatTables();

        double mult = Kernel.mult(m, start);
        meetsFromOne = new int[n];
        latestUnlock = new int[n];
        unlockValue = new double[n];
        for (int l = 0; l < n; l++) {
            if (rules.links[l].kind != Rules.LinkKind.MANUAL) continue;
            int fromOne = meetingsBetween(l, 1, Kernel.eventPts(m, l, 1, mult), false, target[l], mult);
            meetsFromOne[l] = fromOne;
            int needSlots = Math.min(INF, fromOne + groupsLeft(l, 0));
            int s = t.lastSlot;
            while (s >= startSlot && t.meetRemain[l][s] < needSlots) s--;
            latestUnlock[l] = s;
            int remain = t.meetRemain[l][Math.max(startSlot, t.firstSlot)];
            double full = val[l][target[l]];
            unlockValue[l] = remain >= needSlots ? full : wRank(l, 1) * target[l] * remain / Math.max(1.0, needSlots);
        }

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
        rescueLastSlot = new int[t.nRescues][t.nBlocks];
        for (int i = 0; i < t.nRescues; i++) {
            Arrays.fill(rescueLastSlot[i], -1);
            int to = Math.min(t.lastSlot, t.rescueTo[i] * 2 + 1);
            for (int s = Math.max(0, t.rescueFrom[i] * 2); s <= to; s++) {
                if (t.towerBlock[s] >= 0) rescueLastSlot[i][t.towerBlock[s]] = s;
            }
        }

        prevCode = new int[t.lastSlot + 2];
        Arrays.fill(prevCode, -1);
        stabilityEnd = Math.min(t.lastSlot + 1, startSlot + 6);
        if (previous != null) loadPrevious(previous);

        demand = new int[n];
        perMeeting = new double[n];
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

    /** Milestone rank the link is currently working towards (target, then MAX if others count). */
    int milestone(int l, int rank) {
        if (rank < target[l]) return target[l];
        return wOther > 0 ? 10 : rank;
    }

    /** Meetings (and unlock step groups) the link still needs to reach its milestone, or {@link #INF}. */
    int remainingMeetings(SimState st, int l, double mult) {
        int r = st.rank[l];
        int goalRank = milestone(l, r);
        if (goalRank <= r) return 0;
        if (r == 0) return Math.min(INF, groupsLeft(l, st.unlockStep[l]) + meetsFromOne[l]);
        return meetingsBetween(l, r, st.pts[l], st.ready[l], goalRank, mult);
    }

    /** Share of the link's remaining calendar opportunities it still needs (≥ 0, INF when held). */
    double pressure(SimState st, int l) {
        int need = remainingMeetings(st, l, Kernel.mult(m, st));
        if (need >= INF) return INF;
        return need / (double) Math.max(1, t.meetRemain[l][st.slot]);
    }

    // ------------------------------------------------------------------ base policy

    /** Prepare per-step values (link demand, contention, stat gates, money) for {@code st.slot}. */
    void prepare(SimState st) {
        double mult = Kernel.mult(m, st);
        int slot = st.slot;
        int day = slot >> 1;
        prepareDemand(st, mult, day);
        gates = 0;
        Arrays.fill(pointValue, 0);
        long unlockCosts = 0;
        double unlockYen = 0;
        for (int l = 0; l < t.nLinks; l++) {
            if (st.rank[l] != 0 || unlockValue[l] <= 0 || rules.links[l].kind != Rules.LinkKind.MANUAL) continue;
            if (Kernel.lost(m, st, l, day)) continue;
            double v = unlockValue[l] / Math.max(1.0, load[kind(l)]);
            int[] rs = m.reqStat[l];
            boolean statsMet = true;
            // The link's value needs its stat trainings and its meetings: credit the gate with its share.
            double trainings = 0;
            for (int i = 0; i < rs.length; i++) {
                int s = rs[i];
                if (s < t.nStats) trainings += Math.max(0, rules.stats[s].thresholds[m.reqStatLv[l][i]] - st.statPts[s]) / typicalGain[s];
            }
            double share = trainings / (trainings + Math.max(1, demand[l]));
            for (int i = 0; i < rs.length; i++) {
                int s = rs[i];
                if (s >= t.nStats) continue;
                int deficit = rules.stats[s].thresholds[m.reqStatLv[l][i]] - st.statPts[s];
                if (deficit <= 0) continue;
                statsMet = false;
                double need = deficit / typicalGain[s];
                int last = Math.max(slot, latestUnlock[l]);
                int avail = trainRemain[s][slot] - trainRemain[s][Math.min(t.lastSlot + 1, last + 1)];
                double pressure = latestUnlock[l] < slot ? 0.3 : need / Math.max(1, avail);
                double gate = v * share * urgency(pressure) * tuning.statWeight;
                addGate(s, deficit, gate);
                pointValue[s] += gate / Math.max(deficit, 4 * typicalGain[s]);
            }
            int k = st.unlockStep[l];
            boolean soon = day >= rules.links[l].unlock.earliestDay - MONEY_LOOKAHEAD_DAYS;
            if (soon && linksMet(st, l) && k < t.stepCount[l]) {
                // Fees of an unlock whose link prerequisites are met: save up while the stats catch up.
                long cost = 0;
                List<Rules.Step> steps = rules.links[l].unlock.steps;
                for (int j = k; j < steps.size(); j++) cost += Math.max(0, steps.get(j).cost);
                if (cost > 0) {
                    unlockCosts += cost;
                    unlockYen = Math.max(unlockYen, v * (statsMet ? 1.0 : 0.6) / cost);
                }
            }
        }
        prepareCharmGate(st, mult);
        double statYen = 0;
        long training = 0;
        for (int s = 0; s < t.nStats && s < 3; s++) {
            statYen = Math.max(statYen, pointValue[s] * ptsPerYen[s]);
            if (pointValue[s] > 0 && cheapestPaid[s] != Integer.MAX_VALUE) training = Math.max(training, 3L * cheapestPaid[s]);
        }
        yenValue = tuning.moneyWeight * Math.max(0.0005, Math.max(unlockYen, Math.min(MAX_STAT_YEN_VALUE, statYen)));
        reserve = unlockCosts + training + m.cashBuffer;
        shortfall = Math.max(0, reserve - st.cash);
    }

    private int kind(int l) {
        return rules.links[l].evening ? 1 : 0;
    }

    private boolean linksMet(SimState st, int l) {
        int[] rl = m.reqLink[l];
        for (int i = 0; i < rl.length; i++) {
            if (rl[i] < SimState.LINKS && st.rank[rl[i]] < m.reqLinkRank[l][i]) return false;
        }
        return true;
    }

    /** Per-link remaining meetings and value per meeting, and the contention of each slot kind. */
    private void prepareDemand(SimState st, double mult, int day) {
        int slot = st.slot;
        double busy0 = 0;
        double busy1 = 0;
        for (int l = 0; l < t.nLinks; l++) {
            demand[l] = 0;
            perMeeting[l] = 0;
            if (rules.links[l].kind != Rules.LinkKind.MANUAL || Kernel.lost(m, st, l, day)) continue;
            int r = st.rank[l];
            int goalRank = milestone(l, r);
            if (goalRank <= r) continue;
            int need = remainingMeetings(st, l, mult);
            if (need >= INF) continue;
            int remain = t.meetRemain[l][slot];
            demand[l] = need;
            if (need <= remain) {
                perMeeting[l] = (val[l][goalRank] - val[l][r]) / Math.max(1, need);
            } else {
                perMeeting[l] = wRank(l, r + 1) * (goalRank - r) / (double) Math.max(1, need);
            }
            if (r == 0 && !Kernel.prereqsMet(m, st, l)) continue;
            if (rules.links[l].evening) busy1 += Math.min(need, remain);
            else busy0 += Math.min(need, remain);
        }
        load[0] = busy0 / Math.max(1, openRemain[0][slot]);
        load[1] = busy1 / Math.max(1, openRemain[1][slot]);
    }

    /**
     * Charm Lv6 multiplies all affinity gains: worth the meetings it saves, as far as the calendar
     * is overbooked (otherwise the saved meetings would not be missed).
     */
    private void prepareCharmGate(SimState st, double mult) {
        int ci = t.charmIdx;
        if (ci < 0 || m.charmMult <= 1 || st.statPts[ci] >= t.statCap[ci]) return;
        double needed = 0;
        double valueSum = 0;
        for (int l = 0; l < t.nLinks; l++) {
            if (demand[l] <= 0) continue;
            needed += demand[l];
            valueSum += perMeeting[l] * demand[l];
        }
        if (needed <= 0) return;
        double saved = needed * (1 - 1 / m.charmMult) * 0.5;
        double open = openRemain[0][st.slot];
        double overload = Math.max(0, needed - open);
        double avgPer = valueSum / needed;
        double v = (Math.min(saved, overload) + 0.1 * saved) * avgPer;
        if (v <= 0) return;
        int deficit = t.statCap[ci] - st.statPts[ci];
        double trainings = deficit / typicalGain[ci];
        int avail = Math.max(1, trainRemain[ci][st.slot]);
        double gate = v * urgency(trainings / avail) * 0.5 * tuning.statWeight;
        addGate(ci, deficit, gate);
        pointValue[ci] += gate / Math.max(deficit, 4 * typicalGain[ci]);
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
                h = 1.2 * linkScore(st, Kernel.target(code), false);
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
        return vetoed(bestH) ? Kernel.FREE : best;
    }

    static boolean vetoed(double h) {
        return h <= VETO / 2;
    }

    private double meetScore(SimState st, int l) {
        int day = st.slot >> 1;
        boolean up = Kernel.ranksUp(m, st, l, day);
        int r = st.rank[l];
        if (!up && Kernel.held(m, st, l, r + 1, day) && st.pts[l] >= Kernel.need(m, l, r)) {
            // Points are already banked up to the threshold: another meeting during the hold adds nothing.
            return 0.02;
        }
        if (demand[l] <= 0) {
            // Held indefinitely, or nothing left to gain: harmless filler only.
            return up ? 0.5 * wRank(l, st.rank[l] + 1) : 0.02;
        }
        return linkScore(st, l, up) * (up ? 1.1 : 1.0);
    }

    /** Value per meeting × urgency (link scarcity × slot-kind contention). */
    private double linkScore(SimState st, int l, boolean rankUp) {
        if (demand[l] <= 0) return 0.05;
        int remain = Math.max(1, t.meetRemain[l][st.slot]);
        double ratio = demand[l] / (double) remain;
        if (ratio > 1) return perMeeting[l] * 0.5;
        return perMeeting[l] * urgency(ratio * Math.max(1.0, load[kind(l)]));
    }

    /**
     * Urgency transform shared by meetings and stat gates: the patience floor (~0.3) when there is
     * plenty of time, about 1 when half the remaining chances are needed, above 2 when every chance
     * counts.
     */
    double urgency(double pressure) {
        double e = Math.min(1.5, Math.max(0, pressure));
        return tuning.patience + e + e * e * e;
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
                h += gateValue[g] * Math.min(1.0, useful / gateDeficit[g]);
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
        if (st.towerVisits[block] + 1 > m.towerCap - pendingRescues(st, block)) return VETO;
        double income = m.towerIncome > 0 ? moneyValue(m.towerIncome) : 0;
        int remaining = towerTarget[block] - st.towerVisits[block];
        if (remaining > 0) {
            double ratio = remaining / (double) Math.max(1, t.towerRemain[block][st.slot]);
            if (ratio >= 0.5) return 50_000 * ratio;
            return 120 + 400 * ratio + income;
        }
        return 1 + income;
    }

    /** Rescues that still matter and can only happen later in this block: each needs one visit of the cap. */
    private int pendingRescues(SimState st, int block) {
        int pending = 0;
        for (int i = 0; i < t.nRescues; i++) {
            if (!rescueMatters[i] || ((st.rescued | st.rescueFailed) & (1L << i)) != 0) continue;
            if (rescueLastSlot[i][block] > st.slot) pending++;
        }
        return pending;
    }

    /** Value of receiving {@code yen}: full shadow price up to the near-term need, 5% beyond it. */
    double moneyValue(long yen) {
        if (yen <= 0) return 0;
        long urgent = Math.min(yen, shortfall);
        return yenValue * (urgent + 0.05 * (yen - urgent)) + 0.0001 * yen;
    }

    private double costPenalty(long cash, long cost) {
        long after = cash - cost;
        long intoReserve = Math.max(0, Math.min(cost, reserve - after));
        return yenValue * (intoReserve + 0.05 * (cost - intoReserve));
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

    /** The most valuable not-yet-unlocked link whose stat gate on {@code stat} is unmet, or -1. */
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

    /** The previous plan's action for {@code slot} inside the stability window (next 3 days), or -1. */
    int previousCode(int slot) {
        return slot >= startSlot && slot < stabilityEnd ? prevCode[slot] : -1;
    }
}
