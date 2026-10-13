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
 * <p>The calendar has two very different resources: daytime slots, where almost every link meets
 * and which are overbooked, and evenings, where only two links meet and which are the natural place
 * for stat training, Tartarus and the café job. H values each kind of action accordingly:
 * <ul>
 *   <li><b>Meeting / unlock step</b>: the objective value still to gain on that link divided by
 *       the meetings it needs (so links close to MAX are worth more per meeting), scaled by the
 *       link's urgency (the share of its remaining calendar opportunities it still needs) and by
 *       the contention of that slot kind. When a slot kind is overbooked, admission control keeps
 *       the links with the most value per needed meeting (greedy knapsack) as "planned to MAX"; the
 *       rest are valued by the ranks their next few meetings bring. Opening a link is worth more
 *       than a plain meeting (it opens the link's calendar). A meeting that brings a link to the
 *       RANK another wanted link needs to open (Justice ← Emperor 1, Strength ← Chariot 2, Moon ←
 *       Magician 3, Temperance ← Hierophant 3, Sun ← Hanged Man 3, Devil ← Hermit 4, Tower ←
 *       Strength 4, Priestess ← Fortune 1) carries part of that link's value.</li>
 *   <li><b>Stats</b> are worth what they unlock, against deadlines: a gated link should open while
 *       it still has {@link Tuning#openSlack}× its needed opportunities left (for scarce links that
 *       is their first day) and must open while it has {@link #HARD_SLACK}×. Gates are checked
 *       earliest deadline first against the evening capacity before each deadline (open evenings
 *       minus required Tartarus visits and evening-link meetings, times {@link Tuning#eveShare}).
 *       Evening training gets the gate's value with that urgency; daytime training only for the
 *       trainings evenings provably cannot fit before a hard deadline. The Charm Lv6 ×1.51 bonus is
 *       a gate with an "as soon as evenings allow" deadline and no hard one, valued by the meetings
 *       it saves while the daytime calendar is overbooked. Points above Lv6 are worthless.</li>
 *   <li><b>Money</b> is a list of near-term needs, each with its value per yen: fees of unlocks that
 *       are about to open (Devil ¥40,000, the Hanged Man gift) and a small float for paid training.
 *       Earnings are worth the needs they cover, most valuable first; spare cash is worth almost
 *       nothing, so jobs never pile up yen for its own sake. Paid training is charged only for the
 *       fees it would eat into. A daytime job while a wanted link could be met gets no money value.</li>
 *   <li><b>Tartarus</b>: required visits become mandatory as a block's open evenings run out (on the
 *       last possible evening they beat everything); a visit inside a missing-person window is a
 *       strong priority while the link still matters, and a must on the window's last evening.</li>
 *   <li>FREE is never chosen while any productive action is legal.</li>
 * </ul>
 */
final class Heuristics {
    static final double W_OTHER = 300;
    static final double MAX_BONUS_RANKS = 6;
    static final double EARLY_PER_SLOT = 0.002;
    static final double PENALTY = 1e6;
    /** Score of a required Tartarus visit on its last possible evening: beats everything but a veto. */
    static final double MANDATORY = PENALTY / 2;
    static final int INF = 10_000;
    /**
     * Score of a legal action the planner refuses to take (a Tartarus visit that would use up the
     * cap needed for a pending rescue in the same block). FREE beats it.
     */
    static final double VETO = -1e9;
    /** Upper bound of the yen shadow price derived from paid training (a ¥2,500 shift: at most 300). */
    static final double MAX_STAT_YEN_VALUE = 0.12;
    /** Start saving for an unlock fee this many days before the link can open. */
    static final int MONEY_LOOKAHEAD_DAYS = 21;
    /** Share of a daytime job's money value kept while a wanted link could be met in that slot (none). */
    static final double DAY_JOB_WITH_MEETING = 0.0;
    /** Upper bound of the yen shadow price of an unlock fee (a cheap fee is easy to earn later). */
    static final double MAX_FEE_YEN_VALUE = 1.0;
    /** Meetings over which a link that cannot reach its milestone is valued (its next ranks). */
    static final int PARTIAL_HORIZON = 6;
    /** A link must open while it still has this many times its needed meeting opportunities. */
    static final double HARD_SLACK = 1.3;
    /** Evenings in which Charm should reach Lv6, per needed training (the bonus is wanted soon). */
    static final double CHARM_EVENINGS_PER_TRAINING = 2.5;
    /** Score of a Tartarus visit beyond the required ones (under the cap). */
    static final double SPARE_TOWER = 2.0;
    private static final int MAX_GATES = 64;

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
        /** A gated link should open while it still has this many times its needed opportunities. */
        final double openSlack;
        /** Share of the free evenings counted as available for stat training (the rest: jobs, spare). */
        final double eveShare;
        /** Multiplier of the yen value of unlock fees (high: save up for a fee before paid training). */
        final double feeWeight;
        /** Share of a slot kind's open slots the admitted links' demand may fill (admission control). */
        final double admitShare;

        Tuning(double statWeight, double moneyWeight, double patience, double openSlack, double eveShare, double feeWeight,
               double admitShare) {
            this.statWeight = statWeight;
            this.moneyWeight = moneyWeight;
            this.patience = patience;
            this.openSlack = openSlack;
            this.eveShare = eveShare;
            this.feeWeight = feeWeight;
            this.admitShare = admitShare;
        }

        /** The best all-round tuning on the real pack's benchmark states (first in the portfolio). */
        static final Tuning DEFAULT = new Tuning(0.7, 2.0, 0.5, 2.5, 1.0, 4.0, 0.9);

        @Override public String toString() {
            return "Tuning(" + statWeight + ", " + moneyWeight + ", " + patience + ", " + openSlack + ", " + eveShare + ", "
                    + feeWeight + ", " + admitShare + ")";
        }
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
    /** The plan wants the link at all (a goal, or every link when 其余社群 is on). */
    final boolean[] wanted;

    /** [kind][slot] open (unlocked) slots of that kind (0 daytime, 1 evening) in [slot, lastSlot]. */
    final int[][] openRemain;
    /** [kind][stat][slot] slots of that kind ≥ slot in which some allowed activity raises that stat. */
    final int[][][] trainRemain;
    /** Points of a typical evening training per stat. */
    final double[] eveGain;
    /** Best stat points per yen among paid activities, per stat. */
    final double[] ptsPerYen;
    final int[] cheapestPaid;
    /** Last slot at which unlocking the link still leaves enough meetings to reach its target. */
    final int[] latestUnlock;
    /** Objective value of establishing the link (seen from the start of the plan). */
    final double[] unlockValue;
    /** Meetings from a fresh rank 1 to the target (the link's multiplier at the start). */
    final int[] meetsFromOne;
    /** First slot the link could open on (its own calendar and earliest day; requirements ignored). */
    final int[] openSlot;
    /**
     * Slot by which the link's requirements should be met: the last slot at which it still has
     * {@link Tuning#openSlack}× its needed opportunities left, never before {@link #openSlot}.
     */
    final int[] gateDeadline;
    /**
     * Slot by which the link must open at the latest to keep {@link #HARD_SLACK}× its needed
     * opportunities: stat training in daytime is worth something only for gates that evenings
     * cannot meet by this deadline.
     */
    final int[] hardDeadline;

    /** Required Tartarus visits per block (absolute visit count to reach), achievable from the start. */
    final int[] towerTarget;
    final boolean[] rescueMatters;
    /** [rescue][block] last enterable evening of that block inside the rescue window, or -1. */
    private final int[][] rescueLastSlot;

    /** Previous plan's action code per slot (stability bonus window only), or -1. */
    private final int[] prevCode;
    private final int stabilityEnd;

    // ---- memo of remainingMeetings per link, keyed by the inputs that can change during a plan
    //      (memoStep also encodes a player-reported hold on the next rank as a negative value)
    private final int[] memoRank;
    private final int[] memoPts;
    private final int[] memoStep;
    private final boolean[] memoReady;
    private final double[] memoMult;
    private final int[] memoNeed;

    // ---- per-step scratch, filled by prepare() (the planner is single-threaded)
    private final int[] demand;
    private final double[] perMeeting;
    /** The link is planned to reach its milestone (it fits its calendar and the admitted capacity). */
    private final boolean[] admitted;
    private final int[] order;
    private final double[] load = new double[2];
    /** Bonus per meeting (or unlock action) of a link for the gates it opens for other links. */
    private final double[] linkBonus;
    /** Affinity multiplier per link in the prepared state (its Persona × the Charm bonus). */
    private final double[] linkMult;
    /** Value per useful stat point of evening / daytime training. */
    private final double[] eveValue = new double[3];
    private final double[] dayValue = new double[3];
    private final int[] gStat = new int[MAX_GATES];
    private final double[] gDeficit = new double[MAX_GATES];
    private final int[] gDeadline = new int[MAX_GATES];
    private final int[] gHard = new int[MAX_GATES];
    private final double[] gValue = new double[MAX_GATES];
    private final int[] gOrder = new int[MAX_GATES];
    private final double[] cumNeed = new double[3];
    private int gates;
    private boolean linkMeetable;
    /** Money needs of the near term, highest value per yen first: amount and value per yen. */
    private final long[] needYen = new long[MAX_GATES];
    private final double[] needValue = new double[MAX_GATES];
    /** The need is an unlock fee (spending on training must not eat into it). */
    private final boolean[] needFee = new boolean[MAX_GATES];
    private int needs;
    /** Value per yen of cash beyond every near-term need. */
    private double spareYen;
    private long shortfall;

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
        wanted = new boolean[n];
        wOther = p.otherLinks ? W_OTHER : 0;
        int[] mustOrder = tierOrder(p, Profile.Tier.MUST);
        int[] wantOrder = tierOrder(p, Profile.Tier.WANT);
        int goals = 0;
        for (int l = 0; l < n; l++) {
            target[l] = m.target(l);
            wanted[l] = m.goal[l] || p.otherLinks;
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

        int size = t.lastSlot + 2;
        openRemain = new int[2][size];
        for (int s = t.lastSlot; s >= 0; s--) {
            openRemain[0][s] = openRemain[0][s + 1] + ((s & 1) == 0 && t.open(s) ? 1 : 0);
            openRemain[1][s] = openRemain[1][s + 1] + ((s & 1) == 1 && t.open(s) ? 1 : 0);
        }
        int ns = t.nStats;
        trainRemain = new int[2][ns][size];
        eveGain = new double[ns];
        ptsPerYen = new double[ns];
        cheapestPaid = new int[ns];
        buildStatTables();

        meetsFromOne = new int[n];
        latestUnlock = new int[n];
        unlockValue = new double[n];
        openSlot = new int[n];
        gateDeadline = new int[n];
        hardDeadline = new int[n];
        for (int l = 0; l < n; l++) {
            openSlot[l] = t.lastSlot + 1;
            gateDeadline[l] = t.lastSlot + 1;
            hardDeadline[l] = t.lastSlot + 1;
            if (rules.links[l].kind != Rules.LinkKind.MANUAL) continue;
            double mult = Kernel.mult(m, start, l);
            int fromOne = meetingsBetween(l, 1, Kernel.eventPts(m, l, 1, mult), false, target[l], mult);
            meetsFromOne[l] = fromOne;
            int needSlots = Math.min(INF, fromOne + t.slotStepsLeft[l][0]);
            int s = t.lastSlot;
            while (s >= startSlot && t.meetRemain[l][s] < needSlots) s--;
            latestUnlock[l] = s;
            int remain = t.meetRemain[l][Math.max(startSlot, t.firstSlot)];
            double full = val[l][target[l]];
            unlockValue[l] = remain >= needSlots ? full : wRank(l, 1) * target[l] * remain / Math.max(1.0, needSlots);
            openSlot[l] = firstOpenSlot(l);
            gateDeadline[l] = lastSlotKeeping(l, openSlot[l], tuning.openSlack * needSlots);
            hardDeadline[l] = lastSlotKeeping(l, openSlot[l], HARD_SLACK * needSlots);
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

        memoRank = new int[n];
        Arrays.fill(memoRank, -1);
        memoPts = new int[n];
        memoStep = new int[n];
        memoReady = new boolean[n];
        memoMult = new double[n];
        memoNeed = new int[n];
        demand = new int[n];
        perMeeting = new double[n];
        admitted = new boolean[n];
        order = new int[n];
        linkBonus = new double[n];
        linkMult = new double[n];
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
        double[] maxEve = new double[ns];
        Arrays.fill(cheapestPaid, Integer.MAX_VALUE);
        for (Rules.Activity a : rules.activities) {
            if (m.excluded[a.idx]) continue;
            for (int s = 0; s < ns && s < a.gain.length; s++) {
                if (a.gain[s] <= 0) continue;
                if (a.eve) maxEve[s] = Math.max(maxEve[s], a.gain[s]);
                if (a.cost > 0) {
                    ptsPerYen[s] = Math.max(ptsPerYen[s], a.gain[s] / (double) a.cost);
                    cheapestPaid[s] = Math.min(cheapestPaid[s], a.cost);
                }
            }
        }
        for (int s = 0; s < ns; s++) {
            eveGain[s] = Math.max(1.0, maxEve[s] * 0.75);
            for (int kind = 0; kind < 2; kind++) {
                int[] rem = trainRemain[kind][s];
                for (int slot = t.lastSlot; slot >= 0; slot--) {
                    boolean any = false;
                    if ((slot & 1) == kind) {
                        for (int a : t.actsAt[slot]) {
                            if (!m.excluded[a] && s < rules.activities[a].gain.length && rules.activities[a].gain[s] > 0) {
                                any = true;
                                break;
                            }
                        }
                    }
                    rem[slot] = rem[slot + 1] + (any ? 1 : 0);
                }
            }
        }
    }

    /**
     * The last slot ≥ {@code from} at which the link still has {@code keep} meeting opportunities
     * left (or everything it has at {@code from}, if fewer).
     */
    private int lastSlotKeeping(int l, int from, double keep) {
        from = Math.min(t.lastSlot + 1, from);
        int want = (int) Math.min(t.meetRemain[l][from], Math.ceil(keep));
        int d = from;
        while (d + 1 <= t.lastSlot && t.meetRemain[l][d + 1] >= want) d++;
        return d;
    }

    /** First slot ≥ start on which the link's establishing step (or automatic opening) could happen. */
    private int firstOpenSlot(int l) {
        Rules.Link link = rules.links[l];
        int n = t.stepCount[l];
        int est = n == 0 ? -1 : t.establishIdx[l];
        boolean eve = est >= 0 && link.unlock.steps.get(est).evening;
        int from = Math.max(startSlot >> 1, link.unlock.earliestDay);
        if (n > 0) from += t.gapDaysLeft[l][0] - Math.max(0, link.unlock.steps.get(0).minGapDays);
        for (int d = Math.max(0, from); d <= t.lastDay; d++) {
            int slot = d * 2 + (eve ? 1 : 0);
            if (slot < startSlot || !t.open(slot)) continue;
            if (est < 0 || t.stepDayOk[l][est][d]) return slot;
        }
        return t.lastSlot + 1;
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

    /** Slot-consuming unlock actions still needed from the link's current unlock progress. */
    private int unlockSlotsLeft(SimState st, int l) {
        return t.slotStepsLeft[l][Math.max(0, Math.min(t.stepCount[l], st.unlockStep[l]))];
    }

    /** Meetings (and unlock step groups) the link still needs to reach its milestone, or {@link #INF}. */
    int remainingMeetings(SimState st, int l, double mult) {
        int r = st.rank[l];
        int step = st.heldRank[l] == r + 1 ? -1 - st.unlockStep[l] : st.unlockStep[l];
        if (memoRank[l] == r && memoPts[l] == st.pts[l] && memoReady[l] == st.ready[l]
                && memoStep[l] == step && memoMult[l] == mult) {
            return memoNeed[l];
        }
        int goalRank = milestone(l, r);
        int need;
        if (goalRank <= r) {
            need = 0;
        } else if (step < 0 && !st.ready[l]) {
            need = INF; // the player reported that the next rank-up is on hold
        } else if (r == 0) {
            need = Math.min(INF, unlockSlotsLeft(st, l) + meetsFromOne[l]);
        } else {
            need = meetingsBetween(l, r, st.pts[l], st.ready[l], goalRank, mult);
        }
        memoRank[l] = r;
        memoPts[l] = st.pts[l];
        memoReady[l] = st.ready[l];
        memoStep[l] = step;
        memoMult[l] = mult;
        memoNeed[l] = need;
        return need;
    }

    /** Rank the link reaches after {@code k} more actions (its unlock actions first), under the model. */
    private int ranksAfter(SimState st, int l, int k, double mult) {
        int r = st.rank[l];
        int p = st.pts[l];
        boolean rdy = st.ready[l];
        if (r == 0) {
            k -= unlockSlotsLeft(st, l);
            if (k < 0) return 0;
            r = 1;
            p = Kernel.eventPts(m, l, 1, mult);
            rdy = false;
        }
        int g = Kernel.ordinaryPts(m, mult);
        for (; k > 0 && r < 10; k--) {
            if (!rdy && t.holdUntil[l][r + 1] == Tables.HOLD_FOREVER) break;
            if (rdy || p >= Kernel.need(m, l, r)) {
                r++;
                p = Kernel.eventPts(m, l, r, mult);
                rdy = false;
            } else {
                p += g;
            }
        }
        return r;
    }

    /** Share of the link's remaining calendar opportunities it still needs (≥ 0, INF when held). */
    double pressure(SimState st, int l) {
        int need = remainingMeetings(st, l, Kernel.mult(m, st, l));
        if (need >= INF) return INF;
        return need / (double) Math.max(1, t.meetRemain[l][st.slot]);
    }

    // ------------------------------------------------------------------ base policy

    /** Prepare per-step values (link demand, contention, gates, money) for {@code st.slot}. */
    void prepare(SimState st) {
        int slot = st.slot;
        int day = slot >> 1;
        for (int l = 0; l < t.nLinks; l++) linkMult[l] = Kernel.mult(m, st, l);
        prepareDemand(st, day);
        Arrays.fill(eveValue, 0);
        Arrays.fill(dayValue, 0);
        Arrays.fill(linkBonus, 0);
        gates = 0;
        needs = 0;
        for (int l = 0; l < t.nLinks; l++) {
            if (st.rank[l] != 0 || !wanted[l] || unlockValue[l] <= 0 || rules.links[l].kind != Rules.LinkKind.MANUAL) continue;
            if (Kernel.lost(m, st, l, day)) continue;
            boolean late = latestUnlock[l] < slot;
            double v = unlockValue[l] * (late ? 0.3 : admitted[l] ? 1.0 : 0.5);
            int deadline = Math.max(slot, Math.min(gateDeadline[l], Math.max(slot, latestUnlock[l])));
            // Daytime training only for links still planned to reach their milestone in time.
            int hard = late || !admitted[l] ? -1 : Math.max(slot, hardDeadline[l]);
            int[] rs = m.reqStat[l];
            double trainings = 0;
            for (int i = 0; i < rs.length; i++) {
                int s = rs[i];
                if (s < t.nStats) trainings += Math.max(0, rules.stats[s].thresholds[m.reqStatLv[l][i]] - st.statPts[s]) / eveGain[s];
            }
            int linkSteps = linkGateBonus(st, l, v, deadline, trainings);
            // The link's value needs its stat trainings, its gate meetings and its own meetings.
            double share = trainings / (trainings + linkSteps + Math.max(1, demand[l]));
            boolean statsMet = true;
            for (int i = 0; i < rs.length; i++) {
                int s = rs[i];
                if (s >= t.nStats) continue;
                int deficit = rules.stats[s].thresholds[m.reqStatLv[l][i]] - st.statPts[s];
                if (deficit <= 0) continue;
                statsMet = false;
                addGate(s, deficit, deadline, hard, v * share * tuning.statWeight);
            }
            int k = st.unlockStep[l];
            boolean soon = slot >= openSlot[l] - 2 * MONEY_LOOKAHEAD_DAYS || day >= rules.links[l].unlock.earliestDay - MONEY_LOOKAHEAD_DAYS;
            if (soon && linksMet(st, l) && k < t.stepCount[l]) {
                // Fees of an unlock whose link prerequisites are met: save up while the stats catch up.
                long cost = 0;
                List<Rules.Step> steps = rules.links[l].unlock.steps;
                for (int j = k; j < steps.size(); j++) cost += Math.max(0, steps.get(j).cost);
                if (cost > 0) {
                    double perYen = v / Math.max(1.0, load[kind(l)]) * (statsMet ? 1.0 : 0.6) / cost;
                    addNeed(cost, tuning.feeWeight * Math.min(MAX_FEE_YEN_VALUE, perYen), true);
                }
            }
        }
        addCharmGate(st);
        evaluateGates(st);
        double statYen = 0;
        long training = 0;
        for (int s = 0; s < t.nStats && s < 3; s++) {
            statYen = Math.max(statYen, eveValue[s] * ptsPerYen[s]);
            if (eveValue[s] > 0 && cheapestPaid[s] != Integer.MAX_VALUE) training = Math.max(training, 3L * cheapestPaid[s]);
        }
        double trainYen = tuning.moneyWeight * Math.max(0.0005, Math.min(MAX_STAT_YEN_VALUE, statYen));
        if (training > 0) addNeed(training, trainYen, false);
        if (m.cashBuffer > 0) addNeed(m.cashBuffer, trainYen, true);
        spareYen = 0.05 * trainYen;
        sortNeeds();
        long reserve = 0;
        for (int i = 0; i < needs; i++) reserve += needYen[i];
        shortfall = Math.max(0, reserve - st.cash);
        linkMeetable = (slot & 1) == 0 && wantedMeetable(st, slot, day);
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

    /** A wanted, unfinished link can be met in this slot. */
    private boolean wantedMeetable(SimState st, int slot, int day) {
        int mask = t.meetMask[slot];
        for (int l = 0; mask != 0; l++, mask >>>= 1) {
            if ((mask & 1) != 0 && demand[l] > 0 && Kernel.meetLegal(m, st, l, day)) return true;
        }
        return false;
    }

    /**
     * Link-rank gates of the not-yet-open link L: every prerequisite link below its required RANK
     * gets a bonus per meeting (or unlock action) worth a share of L's value, with urgency from the
     * prerequisite's own opportunities before L's gate deadline. Returns the prerequisite meetings
     * still needed.
     */
    private int linkGateBonus(SimState st, int l, double v, int deadline, double trainings) {
        int[] rl = m.reqLink[l];
        int total = 0;
        for (int i = 0; i < rl.length; i++) {
            int p = rl[i];
            if (p >= t.nLinks) continue;
            int need = m.reqLinkRank[l][i];
            int r = st.rank[p];
            if (r >= need || rules.links[p].kind != Rules.LinkKind.MANUAL || Kernel.lost(m, st, p, st.slot >> 1)) continue;
            double mult = linkMult[p];
            int meetings = r == 0
                    ? unlockSlotsLeft(st, p) + meetingsBetween(p, 1, Kernel.eventPts(m, p, 1, mult), false, need, mult)
                    : meetingsBetween(p, r, st.pts[p], st.ready[p], need, mult);
            if (meetings >= INF) continue;
            total += meetings;
            int before = t.meetRemain[p][st.slot] - t.meetRemain[p][Math.min(t.lastSlot + 1, deadline + 1)];
            double pressure = meetings / (double) Math.max(1, before);
            double share = meetings / (meetings + trainings + Math.max(1, demand[l]));
            linkBonus[p] += v * share * urgency(pressure) / Math.max(1, meetings);
        }
        return total;
    }

    /** Per-link remaining meetings and value per meeting, and the contention of each slot kind. */
    private void prepareDemand(SimState st, int day) {
        int slot = st.slot;
        double busy0 = 0;
        double busy1 = 0;
        for (int l = 0; l < t.nLinks; l++) {
            demand[l] = 0;
            perMeeting[l] = 0;
            admitted[l] = false;
            if (rules.links[l].kind != Rules.LinkKind.MANUAL || Kernel.lost(m, st, l, day)) continue;
            int r = st.rank[l];
            int goalRank = milestone(l, r);
            if (goalRank <= r) continue;
            double mult = linkMult[l];
            int need = remainingMeetings(st, l, mult);
            if (need >= INF) continue;
            int remain = t.meetRemain[l][slot];
            demand[l] = need;
            admitted[l] = need <= remain;
            perMeeting[l] = admitted[l] ? (val[l][goalRank] - val[l][r]) / Math.max(1, need) : partialValue(st, l, remain, mult);
            if (r == 0 && !Kernel.prereqsMet(m, st, l)) continue;
            if (rules.links[l].evening) busy1 += Math.min(need, remain);
            else busy0 += Math.min(need, remain);
        }
        load[0] = busy0 / Math.max(1, openRemain[0][slot]);
        load[1] = busy1 / Math.max(1, openRemain[1][slot]);
        admit(st, 0);
        admit(st, 1);
    }

    /** Value per meeting of a link that will not reach its milestone: the ranks its next few meetings bring. */
    private double partialValue(SimState st, int l, int remain, double mult) {
        int k = Math.max(1, Math.min(remain, PARTIAL_HORIZON));
        return (val[l][ranksAfter(st, l, k, mult)] - val[l][st.rank[l]]) / k;
    }

    /**
     * Admission control for one slot kind: when the links that could still reach their milestone
     * need more meetings than that kind has open slots ({@link Tuning#admitShare}), keep the densest
     * ones (objective value per needed meeting, greedy knapsack) and value the rest only by the
     * ranks their next meetings bring. Without this an overbooked calendar spreads its slots over
     * links that all fall short.
     */
    private void admit(SimState st, int kind) {
        int n = 0;
        double total = 0;
        for (int l = 0; l < t.nLinks; l++) {
            if (!admitted[l] || demand[l] <= 0 || kind(l) != kind) continue;
            order[n++] = l;
            total += demand[l];
        }
        double cap = tuning.admitShare * openRemain[kind][st.slot];
        if (total <= cap) return;
        for (int i = 1; i < n; i++) {
            int x = order[i];
            int j = i - 1;
            while (j >= 0 && perMeeting[order[j]] < perMeeting[x]) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = x;
        }
        double used = 0;
        for (int i = 0; i < n; i++) {
            int l = order[i];
            if (used + demand[l] <= cap) {
                used += demand[l];
                continue;
            }
            admitted[l] = false;
            perMeeting[l] = partialValue(st, l, t.meetRemain[l][st.slot], linkMult[l]);
        }
    }

    /**
     * Charm Lv6 multiplies all affinity gains: worth the meetings it saves, as far as the daytime
     * calendar is overbooked (otherwise the saved meetings would not be missed). Deadline: as soon
     * as a fair share of the evenings allows.
     */
    private void addCharmGate(SimState st) {
        int ci = t.charmIdx;
        if (ci < 0 || ci >= 3 || m.charmMult <= 1 || st.statPts[ci] >= t.statCap[ci]) return;
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
        double v = (Math.min(saved, overload) + 0.1 * saved) * avgPer * 0.5;
        if (v <= 0) return;
        int deficit = t.statCap[ci] - st.statPts[ci];
        double evenings = CHARM_EVENINGS_PER_TRAINING * deficit / eveGain[ci];
        // No hard deadline: the bonus is never worth a daytime meeting.
        addGate(ci, deficit, eveningSlotAfter(st.slot, evenings), -1, v * tuning.statWeight);
    }

    /** The slot by which {@code evenings} open evenings have passed (or the last slot). */
    private int eveningSlotAfter(int slot, double evenings) {
        int[] rem = openRemain[1];
        int want = rem[slot] - (int) Math.ceil(evenings);
        if (want <= 0) return t.lastSlot;
        int lo = slot;
        int hi = t.lastSlot;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (rem[mid] >= want) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private void addGate(int stat, double deficit, int deadline, int hard, double value) {
        if (gates >= MAX_GATES || value <= 0) return;
        gStat[gates] = stat;
        gDeficit[gates] = deficit;
        gDeadline[gates] = deadline;
        gHard[gates] = hard;
        gValue[gates] = value;
        gates++;
    }

    /**
     * Stat gates against evening capacity, twice. With the soft deadlines ({@link #gateDeadline}),
     * earliest deadline first: the trainings needed by each deadline (per stat, the largest deficit
     * among the gates due by then) against the evening capacity before it set the per-point value
     * of evening training (gate value × urgency). With the hard deadlines ({@link #hardDeadline}):
     * evenings are given to the gates worth the most per training; the trainings that evenings
     * provably cannot fit before a gate's hard deadline, next to the gates worth at least as much,
     * give daytime training its value, for that share only.
     */
    private void evaluateGates(SimState st) {
        if (gates == 0) return;
        sortGates(gDeadline);
        Arrays.fill(cumNeed, 0);
        for (int i = 0; i < gates; i++) {
            int g = gOrder[i];
            int s = gStat[g];
            cumNeed[s] = Math.max(cumNeed[s], gDeficit[g] / eveGain[s]);
            double total = cumNeed[0] + cumNeed[1] + cumNeed[2];
            double cap = tuning.eveShare * eveningCapacity(st, gDeadline[g]);
            double rho = total / Math.max(1.0, cap);
            eveValue[s] += gValue[g] * urgency(rho) / Math.max(1.0, gDeficit[g]);
        }
        for (int g = 0; g < gates; g++) {
            if (gHard[g] < 0) continue;
            int s = gStat[g];
            // Evenings go to the gates worth the most per training first: a gate only counts the
            // needs of gates due no later that are worth at least as much.
            double vpt = gValue[g] * eveGain[s] / Math.max(1.0, gDeficit[g]);
            Arrays.fill(cumNeed, 0);
            for (int h = 0; h < gates; h++) {
                if (gHard[h] < 0 || gHard[h] > gHard[g]) continue;
                int hs = gStat[h];
                if (h != g && gValue[h] * eveGain[hs] / Math.max(1.0, gDeficit[h]) < vpt) continue;
                cumNeed[hs] = Math.max(cumNeed[hs], gDeficit[h] / eveGain[hs]);
            }
            double total = cumNeed[0] + cumNeed[1] + cumNeed[2];
            double dayNeed = total - tuning.eveShare * eveningCapacity(st, gHard[g]);
            if (dayNeed <= 0) continue;
            int end = Math.min(t.lastSlot + 1, gHard[g] + 1);
            int dayAvail = trainRemain[0][s][st.slot] - trainRemain[0][s][end];
            double dayFrac = Math.min(1.0, dayNeed / Math.max(1.0, total));
            dayValue[s] += gValue[g] * dayFrac * urgency(dayNeed / Math.max(1, dayAvail)) / Math.max(1.0, gDeficit[g]);
        }
    }

    /** Gate indexes into {@link #gOrder} by {@code key} (negative keys last), ties by index. */
    private void sortGates(int[] key) {
        for (int i = 0; i < gates; i++) gOrder[i] = i;
        for (int i = 1; i < gates; i++) {
            int x = gOrder[i];
            long kx = key[x] < 0 ? Long.MAX_VALUE : key[x];
            int j = i - 1;
            while (j >= 0) {
                int y = gOrder[j];
                long ky = key[y] < 0 ? Long.MAX_VALUE : key[y];
                if (ky < kx || ky == kx && y < x) break;
                gOrder[j + 1] = y;
                j--;
            }
            gOrder[j + 1] = x;
        }
    }

    /**
     * Free evenings in [slot, deadline] left for training: open evenings minus the required
     * Tartarus visits and the evening-link meetings expected in that window.
     */
    private double eveningCapacity(SimState st, int deadline) {
        int slot = st.slot;
        int end = Math.min(t.lastSlot + 1, deadline + 1);
        if (end <= slot) return 0;
        double cap = openRemain[1][slot] - openRemain[1][end];
        for (int b = 0; b < t.nBlocks; b++) {
            int left = towerTarget[b] - st.towerVisits[b];
            int all = t.towerRemain[b][slot];
            if (left <= 0 || all <= 0) continue;
            int inside = all - t.towerRemain[b][end];
            cap -= left * Math.min(1.0, inside / (double) Math.max(1, Math.min(all, left * 3)));
        }
        for (int l = 0; l < t.nLinks; l++) {
            if (demand[l] <= 0 || !rules.links[l].evening) continue;
            int remain = t.meetRemain[l][slot];
            if (remain <= 0) continue;
            int inside = remain - t.meetRemain[l][end];
            cap -= Math.min(demand[l], remain) * inside / (double) remain;
        }
        return Math.max(0, cap);
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
        return linkScore(st, l) * (up ? 1.1 : 1.0) + linkBonus[l];
    }

    /** Opening a link: like its most urgent meeting, plus the value of opening its calendar early. */
    private double unlockScore(SimState st, int l) {
        return 1.5 * linkScore(st, l) + linkBonus[l];
    }

    /** Value per meeting × urgency (link scarcity × slot-kind contention). */
    private double linkScore(SimState st, int l) {
        if (demand[l] <= 0) return 0.05;
        int remain = Math.max(1, t.meetRemain[l][st.slot]);
        double ratio = admitted[l] ? Math.min(1.0, demand[l] / (double) remain) : 0;
        // Contention raises every meeting of that slot kind alike; the link's own scarcity orders them.
        return perMeeting[l] * urgency(ratio) * Math.sqrt(Math.max(1.0, load[kind(l)]));
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
        boolean eve = (st.slot & 1) == 1;
        double[] pv = eve ? eveValue : dayValue;
        double h = 0;
        int ns = Math.min(Math.min(a.gain.length, t.nStats), 3);
        for (int s = 0; s < ns; s++) {
            int gain = a.gain[s];
            if (gain <= 0) continue;
            int useful = Math.min(gain, Math.max(0, t.statCap[s] - st.statPts[s]));
            h += (0.2 + pv[s]) * useful;
        }
        if (a.cost > 0) h -= costPenalty(st.cash, a.cost);
        if (a.kind == Rules.ActivityKind.JOB) {
            double money = moneyValue(st.cash - Math.max(0, a.cost), a.payFor(st.shifts[act] + 1));
            if (!eve && linkMeetable) money *= DAY_JOB_WITH_MEETING;
            h += money + 0.25;
        }
        return h;
    }

    private double towerScore(SimState st, int block) {
        int day = st.slot >> 1;
        for (int i = 0; i < t.nRescues; i++) {
            long bit = 1L << i;
            if (rescueMatters[i] && day >= t.rescueFrom[i] && day <= t.rescueTo[i]
                    && ((st.rescued | st.rescueFailed) & bit) == 0) {
                // The last enterable evening of the window is a must; before that a strong priority.
                return rescueLastSlot[i][block] <= st.slot ? MANDATORY : 200_000;
            }
        }
        if (st.towerVisits[block] + 1 > m.towerCap - pendingRescues(st, block)) return VETO;
        double income = m.towerIncome > 0 ? moneyValue(st.cash, m.towerIncome) : 0;
        int remaining = towerTarget[block] - st.towerVisits[block];
        if (remaining > 0) {
            int open = Math.max(1, t.towerRemain[block][st.slot]);
            if (remaining >= open) return MANDATORY; // every evening left in the block is needed
            double ratio = remaining / (double) open;
            if (ratio >= 0.5) return 50_000 * ratio;
            return 120 + 400 * ratio + income;
        }
        // A spare visit under the cap (experience, items) beats a filler job that only piles up yen.
        return SPARE_TOWER + income;
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

    private void addNeed(long yen, double perYen, boolean fee) {
        if (yen <= 0 || needs >= needYen.length) return;
        needYen[needs] = yen;
        needValue[needs] = perYen * tuning.moneyWeight;
        needFee[needs] = fee;
        needs++;
    }

    /** Highest value per yen first (cash pays for the most valuable needs first); ties keep insertion order. */
    private void sortNeeds() {
        for (int i = 1; i < needs; i++) {
            long y = needYen[i];
            double v = needValue[i];
            boolean f = needFee[i];
            int j = i - 1;
            while (j >= 0 && needValue[j] < v) {
                needYen[j + 1] = needYen[j];
                needValue[j + 1] = needValue[j];
                needFee[j + 1] = needFee[j];
                j--;
            }
            needYen[j + 1] = y;
            needValue[j + 1] = v;
            needFee[j + 1] = f;
        }
    }

    /**
     * Value of holding {@code cash}: the near-term needs it covers, most valuable first, then spare
     * yen. With {@code feesOnly} the training float is left out (spending on training is what it is for).
     */
    private double covered(long cash, boolean feesOnly) {
        double v = 0;
        long left = Math.max(0, cash);
        for (int i = 0; i < needs && left > 0; i++) {
            if (feesOnly && !needFee[i]) continue;
            long take = Math.min(left, needYen[i]);
            v += take * needValue[i];
            left -= take;
        }
        return v + left * spareYen;
    }

    /** Value of receiving {@code yen} on top of {@code cash}: the near-term needs it covers, little beyond. */
    double moneyValue(long cash, long yen) {
        if (yen <= 0) return 0;
        return covered(cash + yen, false) - covered(cash, false) + 0.0001 * yen;
    }

    /** Value lost by spending {@code cost} out of {@code cash} on training: the fees it no longer covers. */
    private double costPenalty(long cash, long cost) {
        return covered(cash, true) - covered(cash - cost, true);
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
            if (st.rank[l] != 0 || !wanted[l] || unlockValue[l] <= 0 || Kernel.lost(m, st, l, day)) continue;
            int[] rs = m.reqStat[l];
            for (int i = 0; i < rs.length; i++) {
                if (rs[i] != stat) continue;
                int deficit = rules.stats[stat].thresholds[m.reqStatLv[l][i]] - st.statPts[stat];
                // Prefer the gate due first; ties by value.
                double key = unlockValue[l] / (1.0 + Math.max(0, gateDeadline[l] - st.slot) / 60.0);
                if (deficit > 0 && key > bestV) {
                    bestV = key;
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
