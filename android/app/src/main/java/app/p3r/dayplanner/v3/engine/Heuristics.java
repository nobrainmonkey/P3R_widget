package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Arrays;
import java.util.List;

/**
 * Base policy H and objective J of the planner (docs/DESIGN_v3.md §5.3–5.4), built once per plan.
 *
 * <p>Everything is measured in objective units: one RANK of a "其余社群" link is worth
 * {@link #W_OTHER}, MAX adds six ranks' worth, a 「必须」 goal weighs 100 000 per rank. That is
 * the 安排风格 「尽量多 MAX」; under 「尽量都体验」 (the default, DESIGN §20) the ranks of a
 * "其余社群" link have diminishing weights ({@link #BREADTH_RANK}: RANK 1 four ranks' worth, RANK 10
 * half a rank's) and MAX adds one, while goals weigh the same under both (only their earlier-MAX
 * bonus is halved, {@link #BREADTH_EARLY_SHARE}). The base
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
 *   <li><b>Dating-site notes</b> (evenings, from mid-November): the share of an ordinary meeting
 *       their points replace, valued where a saved meeting matters (a link short of its milestone,
 *       or an overbooked slot kind), minus the fees the price eats into.</li>
 *   <li><b>Link Episodes</b> of opted-in tracks: {@link #EPISODE_VALUE} each in the objective
 *       (plus {@link #EPISODE_FINISH} for the whole track); H scales that by the urgency of the
 *       episode's calendar window, so the last slot that keeps the track complete beats almost
 *       everything.</li>
 *   <li>FREE is never chosen while any productive action is legal.</li>
 * </ul>
 */
final class Heuristics {
    static final double W_OTHER = 300;
    static final double MAX_BONUS_RANKS = 6;
    /**
     * 「尽量都体验」 ({@link Profile.Style#BREADTH}): weight of reaching RANK r (index) of a
     * 「其余社群」 link, in units of {@link #W_OTHER}, with diminishing returns: unlocking a link
     * is worth the most, each further RANK a little less, so the plan unlocks and raises every
     * link before stacking a few. From RANK 6 on the weights fall off faster: with the flatter tail
     * first tried (1.15 … 0.9, MAX bonus 2) the 「尽量多 MAX」 plan scored higher under this objective
     * than the plan the breadth search returned (10/22 with 必须 皇帝 + 想要 女教皇; a new game with
     * every 羁绊剧情 track), so a better search would have turned 「尽量都体验」 into 「尽量多 MAX」
     * (DESIGN §20). 「尽量多 MAX」 weighs every RANK 1.
     */
    static final double[] BREADTH_RANK = {0, 4, 2, 1.6, 1.4, 1.25, 0.9, 0.8, 0.7, 0.6, 0.5};
    /**
     * MAX bonus of a 「其余社群」 link under 「尽量都体验」, in ranks' worth (instead of
     * {@link #MAX_BONUS_RANKS}): the MAX scene and its reward are content too, but must not
     * outweigh breadth.
     */
    static final double BREADTH_MAX_BONUS_RANKS = 1;
    static final double EARLY_PER_SLOT = 0.002;
    /**
     * Share of the earlier-MAX bonus of goals ({@link #EARLY_PER_SLOT}) kept in the objective under
     * 「尽量都体验」: with the whole bonus a goal finished a few days earlier outweighed meetings,
     * unlocks and MAX links of 「其余社群」 (10/22, 必须 ①星 ②正义 ③命运: daytime jobs on 21 days,
     * where 「尽量多 MAX」 has 1, to pay for the goals' dating-site notes; 1 with half the bonus).
     * Goals still reach what they reach (strict priority and {@link Planner.Pool#addMaxStylePlans});
     * only how early is worth less against the other links.
     */
    static final double BREADTH_EARLY_SHARE = 0.5;
    /**
     * 「尽量都体验」 with goals first ({@link Tuning#goalFirst}): factor on the score of a required
     * Tartarus visit that is not yet mandatory. An early 其余社群 unlock weighs up to
     * {@code BREADTH_RANK[1]} times a plain RANK there, and without the factor the required visits
     * are pushed to a block's last evenings, where they crowd out the goals' dating-site notes
     * (1/8, 必须 ①正义 ②力量: 力量 RANK 7 instead of 8). A constant of its own, so that re-tuning the
     * rank curve does not change the Tartarus behaviour of the goal-first tunings.
     */
    static final double BREADTH_GOAL_TOWER_KEEP = 4;
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
    /** Share of the next RANK a meeting is worth when the next meetings bring no RANK under the model. */
    static final double PROGRESS_SHARE = 0.1;
    /** A link must open while it still has this many times its needed meeting opportunities. */
    static final double HARD_SLACK = 1.3;
    /** Evenings in which Charm should reach Lv6, per needed training (the bonus is wanted soon). */
    static final double CHARM_EVENINGS_PER_TRAINING = 2.5;
    /** Score of a Tartarus visit beyond the required ones (under the cap). */
    static final double SPARE_TOWER = 2.0;
    /**
     * Objective value of each Link Episode watched in a track the player opted into: more than a
     * whole "其余社群" link per track, less than one RANK of a 「想要」 goal, so opted-in tracks are
     * completed whenever their windows allow without costing a goal anything.
     */
    static final double EPISODE_VALUE = 2_500;
    /** Extra objective value of watching every episode of an opted-in track. */
    static final double EPISODE_FINISH = 4_000;
    /** Share of a meeting's value a dating-site note keeps in H (its points are only an estimate). */
    static final double NOTE_SHARE = 0.9;
    /**
     * Robustness (handoff: "prefer less fragile routes"): a 「其余社群」 link planned to MAX on its
     * last calendar meeting opportunity loses the MAX if one meeting ranks up later than estimated;
     * with one opportunity to spare a replan can still catch up. Complete plans (the tunings of the
     * portfolio) are compared with this much subtracted per such fragile MAX: a fifth of a RANK, so
     * it decides between otherwise close plans and never trades a RANK for slack. It is left out of
     * the rollouts, where it would steer the search (measured on the real pack: fewer ranks, no
     * gain in a lower-gain replay). Goals have their own margin (Profile.goalBuffer) and an
     * earlier-MAX bonus.
     */
    static final double FRAGILE_MAX_PENALTY = 0.2 * W_OTHER;
    /**
     * Cash kept ahead of a mandatory unlock fee (Devil payments, the Hanged Man gift): the money
     * need of an upcoming fee is the fee plus this reserve (the pay of one café shift), so evening
     * jobs earn the fee and a little more. The reserve itself is a hard rule of the kernel
     * ({@link Kernel#FEE_RESERVE}): a fee keeps it unless waiting would cost the link its milestone,
     * optional spends never touch a fee that is due and affordable plus the reserve, and on a fee
     * day they keep the reserve.
     */
    static final long FEE_RESERVE = Kernel.FEE_RESERVE;
    private static final int MAX_GATES = 64;
    /**
     * Weight per RANK of the first 「必须」 goal is {@code MUST_BASE + MUST_STEP·(n−1)} for n
     * 「必须」 goals, and every later one weighs {@link #MUST_STEP} less, down to {@code MUST_BASE}
     * for the last: with the MAX bonus (6 ranks' worth) a goal's MAX outweighs the next goal's MAX
     * by {@code 7·MUST_STEP}, so when two goals compete for the same days the earlier one wins.
     * 「想要」 goals are spaced the same way far below (at most 50,000 for 22 goals).
     */
    static final double MUST_BASE = 100_000;
    static final double MUST_STEP = 25_000;
    static final double WANT_BASE = 8_000;
    static final double WANT_STEP = 2_000;
    /**
     * H of a meeting or unlock step that moves a goal towards its target under a
     * {@link Tuning#goalFirst} tuning: above a rescue visit (200,000), below a mandatory Tartarus
     * visit ({@link #MANDATORY}); each place in the goal order adds {@link #GOAL_FIRST_STEP}.
     */
    static final double GOAL_FIRST = 300_000;
    static final double GOAL_FIRST_STEP = 1_000;
    /** Like {@link #GOAL_FIRST} for a dating-site note that adds points to a goal (below a rescue visit). */
    static final double GOAL_FIRST_NOTE = 150_000;

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
        /**
         * Goals in strict priority order: a meeting or unlock step that moves a goal towards its
         * target beats every other action except a mandatory Tartarus visit, and a higher goal beats
         * a lower one ({@link #GOAL_FIRST}). Lexicographic, so the first goal gets every chance it
         * can use; the balanced tunings of the portfolio cover the plans where every goal fits.
         */
        final boolean goalFirst;

        Tuning(double statWeight, double moneyWeight, double patience, double openSlack, double eveShare, double feeWeight,
               double admitShare) {
            this(statWeight, moneyWeight, patience, openSlack, eveShare, feeWeight, admitShare, false);
        }

        Tuning(double statWeight, double moneyWeight, double patience, double openSlack, double eveShare, double feeWeight,
               double admitShare, boolean goalFirst) {
            this.statWeight = statWeight;
            this.moneyWeight = moneyWeight;
            this.patience = patience;
            this.openSlack = openSlack;
            this.eveShare = eveShare;
            this.feeWeight = feeWeight;
            this.admitShare = admitShare;
            this.goalFirst = goalFirst;
        }

        /** The same knobs with goals in strict priority order. */
        Tuning goalFirst() {
            return new Tuning(statWeight, moneyWeight, patience, openSlack, eveShare, feeWeight, admitShare, true);
        }

        /** The best all-round tuning on the real pack's benchmark states (first in the portfolio). */
        static final Tuning DEFAULT = new Tuning(0.7, 2.0, 0.5, 2.5, 1.0, 4.0, 0.9);

        @Override public String toString() {
            return "Tuning(" + statWeight + ", " + moneyWeight + ", " + patience + ", " + openSlack + ", " + eveShare + ", "
                    + feeWeight + ", " + admitShare + (goalFirst ? ", goalFirst" : "") + ")";
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
    /** Weight per rank of a 「其余社群」 link (0 when the player turned 其余社群 off). */
    final double wOther;
    /** 安排风格 「尽量都体验」: 「其余社群」 ranks weigh {@link #BREADTH_RANK}, MAX {@link #BREADTH_MAX_BONUS_RANKS}. */
    final boolean breadth;
    /** Share of the goals' earlier-MAX bonus in the objective (1, or {@link #BREADTH_EARLY_SHARE}). */
    private final double earlyShare;
    /**
     * Links counted as goals here: the profile's goals, or the ones of a goal order given to the
     * constructor (the planner's "top-k" and "goal first" variants); the profile's other goals
     * then count as 「其余社群」 links.
     */
    final boolean[] goal;
    /** Per link: weight per rank as a non-goal link ({@link #wOther}, or W_OTHER for a goal left out of the order). */
    private final double[] otherW;
    final int[] target;
    /** [link][rank] cumulative objective value of having that rank. */
    final double[][] val;
    final int[] goalLinks;
    /** Place of each goal in the priority order (「必须」 by order, then 「想要」 by order), -1 for non-goals. */
    final int[] goalRank;
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
        this(m, start, previous, tuning, null);
    }

    /**
     * Base policy and objective that count as goals only {@code goals}: goal links of the profile in
     * the priority order to plan by (null: every goal of the profile in its own order,
     * {@link #goalOrder}). The goal at place p weighs what the profile's p-th goal weighs (so a
     * leading prefix keeps its weights, and a goal moved to the front takes the first goal's
     * weight); goals of the profile left out count as 「其余社群」 links (MAX), even when 其余社群
     * is off. The planner runs these variants so that the first goals get plans of their own
     * (Planner.plan); legality and transitions still follow the whole profile ({@code m}).
     */
    Heuristics(Model m, SimState start, Plan previous, Tuning tuning, int[] goals) {
        this(m, start, previous, tuning, goals, m.profile.style == Profile.Style.BREADTH);
    }

    /**
     * Like {@link #Heuristics(Model, SimState, Plan, Tuning, int[])} with the 安排风格 given:
     * {@code breadth} false is the 「尽量多 MAX」 policy and objective whatever the profile says
     * (the planner runs it under 「尽量都体验」 too, so goals reach what they reach under it).
     */
    Heuristics(Model m, SimState start, Plan previous, Tuning tuning, int[] goals, boolean breadth) {
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
        goal = new boolean[n];
        otherW = new double[n];
        goalRank = new int[n];
        Arrays.fill(goalRank, -1);
        wOther = p.otherLinks ? W_OTHER : 0;
        this.breadth = breadth;
        this.earlyShare = breadth ? BREADTH_EARLY_SHARE : 1;
        int[] profileOrder = goalOrder(m);
        int[] goalsInOrder = goals == null ? profileOrder : goals;
        int count = Math.min(goalsInOrder.length, profileOrder.length);
        int nMust = 0;
        for (int i = 0; i < count; i++) if (p.tier[profileOrder[i]] == Profile.Tier.MUST) nMust++;
        int nWant = count - nMust;
        for (int i = 0; i < count; i++) {
            int l = goalsInOrder[i];
            if (l < 0 || l >= n || !m.goal[l] || goal[l]) continue;
            goal[l] = true;
            goalRank[l] = i;
            boolean must = p.tier[profileOrder[i]] == Profile.Tier.MUST;
            wGoal[l] = must ? MUST_BASE + MUST_STEP * (nMust - 1 - i) : WANT_BASE + WANT_STEP * (nWant - 1 - (i - nMust));
        }
        int goalsCounted = 0;
        for (int l = 0; l < n; l++) {
            target[l] = goal[l] ? m.target(l) : 10;
            otherW[l] = m.goal[l] && !goal[l] ? W_OTHER : wOther;
            wanted[l] = goal[l] || otherW[l] > 0;
            if (goal[l]) goalsCounted++;
        }
        goalLinks = new int[goalsCounted];
        for (int l = 0, i = 0; l < n; l++) if (goal[l]) goalLinks[i++] = l;
        val = new double[n][11];
        for (int l = 0; l < n; l++) {
            double v = 0;
            for (int r = 1; r <= 10; r++) {
                v += wRank(l, r);
                if (goal[l] && r == target[l]) v += MAX_BONUS_RANKS * wGoal[l];
                if (r == 10 && (!goal[l] || target[l] < 10)) v += (breadth ? BREADTH_MAX_BONUS_RANKS : MAX_BONUS_RANKS) * otherW[l];
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
            unlockValue[l] = remain >= needSlots ? full
                    : breadth ? ranksValue(l, target[l] * remain / Math.max(1.0, needSlots))
                    : wRank(l, 1) * target[l] * remain / Math.max(1.0, needSlots);
            openSlot[l] = firstOpenSlot(l);
            gateDeadline[l] = lastSlotKeeping(l, openSlot[l], tuning.openSlack * needSlots);
            hardDeadline[l] = lastSlotKeeping(l, openSlot[l], HARD_SLACK * needSlots);
        }

        towerTarget = new int[t.nBlocks];
        for (int b = 0; b < t.nBlocks; b++) {
            int open = t.towerRemain[b][startSlot];
            towerTarget[b] = Math.min(m.towerWant[b], start.towerVisits[b] + open);
        }
        rescueMatters = new boolean[t.nRescues];
        for (int i = 0; i < t.nRescues; i++) {
            long bit = 1L << i;
            int l = t.rescueLink[i];
            boolean open = ((start.rescued | start.rescueFailed) & bit) == 0 && rescueEvenings(i) > 0;
            boolean linkMatters = l >= 0 && l < n && start.rank[l] < 10 && wanted[l];
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

    /** The profile's goal links in priority order: 「必须」 then 「想要」, each by {@link Profile#order}, ties by index. */
    static int[] goalOrder(Model m) {
        Profile p = m.profile;
        int n = m.t.nLinks;
        Integer[] idx = new Integer[n];
        int count = 0;
        for (int l = 0; l < n; l++) if (m.goal[l]) idx[count++] = l;
        Arrays.sort(idx, 0, count, (a, b) -> {
            int ta = p.tier[a] == Profile.Tier.MUST ? 0 : 1;
            int tb = p.tier[b] == Profile.Tier.MUST ? 0 : 1;
            if (ta != tb) return Integer.compare(ta, tb);
            if (p.order[a] != p.order[b]) return Integer.compare(p.order[a], p.order[b]);
            return Integer.compare(a, b);
        });
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = idx[i];
        return out;
    }

    /** Objective weight of reaching {@code rank} on link l. */
    double wRank(int l, int rank) {
        if (goal[l] && rank <= target[l]) return wGoal[l];
        return breadth ? otherW[l] * BREADTH_RANK[Math.max(0, Math.min(10, rank))] : otherW[l];
    }

    /** Rank weights of link l up to {@code ranks} (a fraction counts that share of the next RANK), no MAX bonus. */
    private double ranksValue(int l, double ranks) {
        double v = 0;
        int whole = (int) Math.min(10, Math.max(0, ranks));
        for (int r = 1; r <= whole; r++) v += wRank(l, r);
        if (whole < 10) v += (Math.max(0, ranks) - whole) * wRank(l, whole + 1);
        return v;
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
        return Kernel.meetingsBetween(m, l, rank, pts, ready, goalRank, mult);
    }

    /** Milestone rank the link is currently working towards (target, then MAX if others count). */
    int milestone(int l, int rank) {
        if (rank < target[l]) return target[l];
        return otherW[l] > 0 ? 10 : rank;
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
                    addNeed(cost + feeReserve(cost), tuning.feeWeight * Math.min(MAX_FEE_YEN_VALUE, perYen), true);
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
        if (tuning.goalFirst) addGoalNoteNeed(st, day);
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

    /** Reserve kept on top of unlock fees totalling {@code fee} yen ({@link #FEE_RESERVE}, none without a fee). */
    static long feeReserve(long fee) {
        return fee <= 0 ? 0 : FEE_RESERVE;
    }

    /** Dating-site notes a {@link Tuning#goalFirst} plan saves up for (about a week of evenings). */
    static final int GOAL_NOTES_AHEAD = 3;

    /**
     * {@link Tuning#goalFirst}: while a goal still needs points and dating-site notes are (about to
     * be) on sale, the price of the next few notes is a money need worth as much per yen as an
     * unlock fee, so evening jobs pay for the notes that bring the goals to their targets.
     */
    private void addGoalNoteNeed(SimState st, int day) {
        Rules.DatingNote note = m.note;
        if (note == null || note.cost <= 0 || day < note.fromDay - MONEY_LOOKAHEAD_DAYS || st.notesUsed >= m.noteMaxUses) return;
        for (int l : goalLinks) {
            if (st.rank[l] >= 1 && st.rank[l] < target[l] && demand[l] > 0) {
                addNeed((long) note.cost * GOAL_NOTES_AHEAD, MAX_FEE_YEN_VALUE, true);
                return;
            }
        }
    }

    private boolean linksMet(SimState st, int l) {
        int[] rl = m.reqLink[l];
        for (int i = 0; i < rl.length; i++) {
            if (rl[i] < SimState.LINKS && st.rank[rl[i]] < m.reqLinkRank[l][i]) return false;
        }
        return true;
    }

    /**
     * A wanted, unfinished link can be met in this slot (under {@link Tuning#goalFirst}: a goal
     * below its target, since only a goal's meeting would take the slot from a job there).
     */
    private boolean wantedMeetable(SimState st, int slot, int day) {
        int mask = t.meetMask[slot];
        for (int l = 0; mask != 0; l++, mask >>>= 1) {
            if ((mask & 1) == 0 || demand[l] <= 0) continue;
            if (tuning.goalFirst && !(goal[l] && st.rank[l] < target[l])) continue;
            if (Kernel.meetLegal(m, st, l, day)) return true;
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
        int r = st.rank[l];
        double v = (val[l][ranksAfter(st, l, k, mult)] - val[l][r]) / k;
        if (v <= 0 && r >= 1 && r < 10) {
            // The next meetings bring no RANK under the model, but their points still count (the
            // estimate may be low, and notes may add the rest): a small share of the next rank.
            v = PROGRESS_SHARE * (val[l][r + 1] - val[l][r]) / k;
        }
        return v;
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
        int due = eveningSlotAfter(st.slot, evenings);
        if (tuning.goalFirst) {
            // Goal meetings dominate avgPer here: count only the daytime left once Lv6 can be reached.
            v *= openRemain[0][Math.min(t.lastSlot + 1, eveningSlotAfter(st.slot, deficit / eveGain[ci]))]
                    / (double) Math.max(1, openRemain[0][st.slot]);
            if (v <= 0) return;
        }
        // No hard deadline: the bonus is never worth a daytime meeting.
        addGate(ci, deficit, due, -1, v * tuning.statWeight);
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
            case Kernel.T_NOTE:
                h = noteScore(st, Kernel.target(code));
                break;
            case Kernel.T_EPISODE:
                h = episodeScore(st, Kernel.target(code));
                break;
            default:
                return 0;
        }
        if (tuning.goalFirst) h = goalFirst(st, code, h);
        int slot = st.slot;
        if (slot < stabilityEnd && prevCode[slot] == code) h += 0.02 * Math.abs(h) + 1e-3;
        return h;
    }

    /**
     * {@link Tuning#goalFirst}: a meeting or unlock step that moves a goal towards its target, or
     * a dating-site note whose points still count for it, scores by the goal's place in the
     * priority order ({@link #GOAL_FIRST}, {@link #GOAL_FIRST_NOTE}); anything else keeps {@code h}.
     */
    private double goalFirst(SimState st, int code, double h) {
        int type = Kernel.type(code);
        if (type != Kernel.T_MEET && type != Kernel.T_UNLOCK && type != Kernel.T_NOTE) return h;
        int l = Kernel.target(code);
        if (!goal[l] || st.rank[l] >= target[l] || demand[l] <= 0 || h <= 0) return h;
        double base;
        if (type == Kernel.T_NOTE) {
            if (Kernel.need(m, l, st.rank[l]) - st.pts[l] <= 0) return h;
            base = GOAL_FIRST_NOTE;
        } else {
            if (h <= 1.0) return h; // a meeting that cannot add anything (banked points during a hold)
            base = GOAL_FIRST;
        }
        return base + GOAL_FIRST_STEP * (goalLinks.length - goalRank[l]) + Math.min(h, GOAL_FIRST_STEP - 1);
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

    /**
     * Value per meeting × urgency (link scarcity × slot-kind contention). A link that will not reach
     * its milestone has no scarcity of its own under 「尽量多 MAX」 (its meetings are filler); under
     * 「尽量都体验」 its next ranks are worth the most, so it is as urgent as the share of its
     * remaining opportunities its next {@link #PARTIAL_HORIZON} meetings need.
     */
    private double linkScore(SimState st, int l) {
        if (demand[l] <= 0) return 0.05;
        int remain = Math.max(1, t.meetRemain[l][st.slot]);
        double ratio = admitted[l] ? Math.min(1.0, demand[l] / (double) remain) : breadth ? nextRanksShare(l, remain) : 0;
        // Contention raises every meeting of that slot kind alike; the link's own scarcity orders them.
        return perMeeting[l] * urgency(ratio) * Math.sqrt(Math.max(1.0, load[kind(l)]));
    }

    /** 「尽量都体验」: share of the link's {@code remain} opportunities its next few meetings need (≤ 1). */
    private double nextRanksShare(int l, int remain) {
        return Math.min(1.0, Math.min(demand[l], PARTIAL_HORIZON) / (double) remain);
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
        if (st.towerVisits[block] + 1 > m.towerCap[block] - pendingRescues(st, block)) return VETO;
        double income = m.towerIncome > 0 ? moneyValue(st.cash, m.towerIncome) : 0;
        int remaining = towerTarget[block] - st.towerVisits[block];
        if (remaining > 0) {
            int open = Math.max(1, t.towerRemain[block][st.slot]);
            if (remaining >= open) return MANDATORY; // every evening left in the block is needed
            double ratio = remaining / (double) open;
            if (ratio >= 0.5) return 50_000 * ratio;
            double visit = 120 + 400 * ratio;
            // 「尽量都体验」 with goals first: the visit keeps its place against early 其余社群 unlocks.
            if (breadth && tuning.goalFirst) visit *= BREADTH_GOAL_TOWER_KEEP;
            return visit + income;
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

    /**
     * Dating-site note for link l (evening): worth the share of an ordinary meeting its points
     * replace (only points below the next rank-up count), at the value of that meeting where a saved
     * meeting matters: always for a link that will not reach its milestone (every point brings it
     * closer), for a link that will only as far as its slot kind is overbooked (the meeting it saves
     * goes to another link), plus a goal's earlier target. The price is charged like paid training.
     */
    private double noteScore(SimState st, int l) {
        Rules.DatingNote note = m.note;
        if (note == null || demand[l] <= 0 || !wanted[l]) return 0.01;
        int r = st.rank[l];
        int room = Kernel.need(m, l, r) - st.pts[l];
        if (room <= 0) return 0.01;
        double share = Math.min(1.0, Math.min(note.gain, room) / (double) Kernel.ordinaryPts(m, linkMult[l]));
        double worth;
        if (!admitted[l]) {
            worth = perMeeting[l];
        } else {
            double overbooked = Math.min(1.0, Math.max(0, load[kind(l)] - tuning.admitShare));
            int remain = Math.max(1, t.meetRemain[l][st.slot]);
            double early = goal[l] ? wGoal[l] * EARLY_PER_SLOT * (lastSlot - st.slot) / (double) remain : 0;
            worth = perMeeting[l] * overbooked + early;
        }
        double h = worth * share * NOTE_SHARE;
        if (note.cost > 0) h -= costPenalty(st.cash, note.cost);
        return Math.max(0.01, h);
    }

    /**
     * Next Link Episode of an opted-in track: its objective value (plus its share of finishing the
     * track while the rest of the track still fits), scaled by the urgency of its window: the last
     * slot that still allows it (and every later episode) beats almost everything. Stat points the
     * episode gives count like training in that slot.
     */
    private double episodeScore(SimState st, int e) {
        int n = st.episodes[e];
        int count = t.epCount[e];
        if (n >= count) return 0;
        int slack = Math.max(1, t.episodeSlack(e, n, st.slot));
        double v = EPISODE_VALUE + (t.epChain[e][n] ? EPISODE_FINISH / (count - n) : 0);
        double h = v * urgency(1.5 / slack);
        int[] gain = rules.episodes[e].gainOf(n + 1);
        double[] pv = (st.slot & 1) == 1 ? eveValue : dayValue;
        for (int s = 0; s < Math.min(Math.min(gain.length, t.nStats), 3); s++) {
            if (gain[s] > 0) h += (0.2 + pv[s]) * Math.min(gain[s], Math.max(0, t.statCap[s] - st.statPts[s]));
        }
        return h;
    }

    /** Objective value of the Link Episodes watched in opted-in tracks. */
    double episodeValue(SimState end) {
        double v = 0;
        for (int e = 0; e < t.nEps; e++) {
            if (!m.episodeWanted[e]) continue;
            int count = t.epCount[e];
            int seen = Math.min(count, e < end.episodes.length ? end.episodes[e] : 0);
            v += EPISODE_VALUE * seen + (count > 0 && seen >= count ? EPISODE_FINISH : 0);
        }
        return v;
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

    /**
     * Objective J of a complete trajectory ending in {@code end}; {@code reached} as kept by
     * {@link #track}: rank values (with MAX bonuses), earlier goal targets, stat levels, opted-in
     * Link Episodes, a little cash, minus a penalty per unmet Tartarus visit or missed rescue.
     * Complete plans are compared by this less {@link #FRAGILE_MAX_PENALTY} per {@link #fragileMaxes}.
     */
    double objective(SimState end, int[] reached) {
        double j = 0;
        for (int l = 0; l < t.nLinks; l++) j += val[l][end.rank[l]];
        int g = goalLinks.length;
        for (int i = 0; i < g; i++) {
            if (reached[i] >= 0) j += earlyShare * wGoal[goalLinks[i]] * EARLY_PER_SLOT * (lastSlot - reached[i]);
        }
        for (int s = 0; s < t.nStats; s++) j += 0.5 * Kernel.statLv(m, end, s);
        j += episodeValue(end);
        j += 0.0001 * end.cash;
        int unmet = 0;
        for (int b = 0; b < t.nBlocks; b++) unmet += Math.max(0, towerTarget[b] - end.towerVisits[b]);
        for (int i = 0; i < t.nRescues; i++) {
            if (rescueMatters[i] && (end.rescued & (1L << i)) == 0) unmet++;
        }
        return j - PENALTY * unmet;
    }

    /**
     * 「其余社群」 links the trajectory brings to MAX on their last calendar meeting opportunity
     * (none left after the MAX meeting); see {@link #FRAGILE_MAX_PENALTY}.
     */
    int fragileMaxes(SimState end, int[] reached) {
        int g = goalLinks.length;
        int n = 0;
        for (int l = 0; l < t.nLinks; l++) {
            int at = reached[g + l];
            if (at >= 0 && !goal[l] && otherW[l] > 0 && end.rank[l] >= 10 && t.meetRemain[l][Math.min(lastSlot + 1, at + 1)] <= 0) n++;
        }
        return n;
    }

    /**
     * Record, in {@code reached}, the slot at which each goal first reaches its target (first
     * {@code goalLinks.length} entries) and each link first reaches MAX (one entry per link after
     * them; {@link #MAX_BEFORE} when it was MAX before the plan).
     */
    void track(SimState st, int slot, int[] reached) {
        int g = goalLinks.length;
        for (int i = 0; i < g; i++) {
            if (reached[i] < 0 && st.rank[goalLinks[i]] >= target[goalLinks[i]]) reached[i] = slot;
        }
        for (int l = 0; l < t.nLinks; l++) {
            if (reached[g + l] == -1 && st.rank[l] >= 10) reached[g + l] = slot;
        }
    }

    /** Marks a link that was already MAX when the plan starts (no slack to keep). */
    static final int MAX_BEFORE = -2;

    /** Initial reached slots for a plan starting at {@code start} (see {@link #track}). */
    int[] initialReached(SimState start) {
        int g = goalLinks.length;
        int[] reached = new int[g + t.nLinks];
        for (int i = 0; i < g; i++) {
            reached[i] = start.rank[goalLinks[i]] >= target[goalLinks[i]] ? startSlot : -1;
        }
        for (int l = 0; l < t.nLinks; l++) reached[g + l] = start.rank[l] >= 10 ? MAX_BEFORE : -1;
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

    /** Yen the prepared state holds back for unlock fees (fees plus {@link #FEE_RESERVE}, and the cash buffer). */
    long feeNeeds() {
        long sum = 0;
        for (int i = 0; i < needs; i++) if (needFee[i]) sum += needYen[i];
        return sum;
    }

    /** The previous plan's action for {@code slot} inside the stability window (next 3 days), or -1. */
    int previousCode(int slot) {
        return slot >= startSlot && slot < stabilityEnd ? prevCode[slot] : -1;
    }
}
