package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Rules;

import java.util.Arrays;
import java.util.List;

/**
 * Simulation kernel (docs/DESIGN_v3.md §5.2): legality and transitions on a {@link SimState} that
 * is mutated <em>in place</em>. Actions are packed into ints so the planner's rollouts allocate
 * nothing per step. Callers that must not mutate their state copy it first ({@link Simulator}).
 *
 * <p>Code layout: {@code type << 24 | target << 8 | step}; 0 is FREE.
 */
final class Kernel {
    private Kernel() {}

    static final int FREE = 0;
    static final int T_MEET = 1;
    static final int T_UNLOCK = 2;
    static final int T_ACT = 3;
    static final int T_TOWER = 4;

    // Assumption bits (ids in ASSUMPTION_IDS, same order).
    static final int A_ORDINARY = 1;
    static final int A_BEST = 1 << 1;
    static final int A_PERSONA = 1 << 2;
    static final int A_CHARM = 1 << 3;
    static final int A_BUFFER = 1 << 4;
    static final int A_TOWER_INCOME = 1 << 5;
    static final int A_JOB_PAY = 1 << 6;
    static final int A_RESCUE = 1 << 7;
    static final int A_HOLD = 1 << 8;
    static final String[] ASSUMPTION_IDS = {
            "ordinary_gain_estimate", "best_answers", "persona_bonus", "charm_bonus", "goal_buffer",
            "tower_income_estimate", "job_pay_minimum", "rescue_on_visit", "rank_hold"};

    static int code(int type, int target, int step) {
        return (type << 24) | (target << 8) | (step & 0xff);
    }

    static int type(int code) { return code >>> 24; }

    static int target(int code) { return (code >>> 8) & 0xffff; }

    static int step(int code) { return code & 0xff; }

    static int encode(Action a) {
        switch (a.type) {
            case MEET: return code(T_MEET, a.target, 0);
            case UNLOCK_STEP: return code(T_UNLOCK, a.target, a.step);
            case ACTIVITY: return code(T_ACT, a.target, 0);
            case TARTARUS: return code(T_TOWER, a.target, 0);
            default: return FREE;
        }
    }

    static Action decode(int code) {
        switch (type(code)) {
            case T_MEET: return Action.meet(target(code));
            case T_UNLOCK: return Action.unlock(target(code), step(code));
            case T_ACT: return Action.activity(target(code));
            case T_TOWER: return Action.tower(target(code));
            default: return Action.free();
        }
    }

    /** Per-step result details, filled by {@link #effect} when a non-null instance is passed. */
    static final class Effect {
        int rankFrom = -1;
        int rankTo = -1;
        boolean rankUp;
        boolean certain;
        boolean estimate;
        boolean invite;
        boolean held;
        long cashBefore;
        long cashAfter;
        final int[] statDelta = new int[3];
        final int[] lvBefore = new int[3];
        final int[] lvAfter = new int[3];
        int assumptions;
        /** Tartarus: visit number in the block after this visit. */
        int towerVisit;
        /** Rescue index marked by a Tartarus visit, or -1. */
        int rescue = -1;
        /** Job pay (minimum tier). */
        int pay;
        /** Side steps done in this slot before the main action: link and step index, in order. */
        int sideCount;
        final int[] sideLink = new int[32];
        final int[] sideStep = new int[32];

        void reset() {
            sideCount = 0;
            rankFrom = -1;
            rankTo = -1;
            rankUp = false;
            certain = false;
            estimate = false;
            invite = false;
            held = false;
            cashBefore = 0;
            cashAfter = 0;
            Arrays.fill(statDelta, 0);
            Arrays.fill(lvBefore, 0);
            Arrays.fill(lvAfter, 0);
            assumptions = 0;
            towerVisit = 0;
            rescue = -1;
            pay = 0;
        }

        void addAssumptionIds(List<String> out) {
            for (int i = 0; i < ASSUMPTION_IDS.length; i++) {
                if ((assumptions & (1 << i)) != 0) out.add(ASSUMPTION_IDS[i]);
            }
        }
    }

    // ------------------------------------------------------------------ state helpers

    /** A copy of {@code s} with every array sized for the pack (never mutates {@code s}). */
    static SimState normalize(Model m, SimState s) {
        SimState c = s.copy();
        c.statPts = fit(c.statPts, SimState.STATS, 0);
        c.rank = fit(c.rank, SimState.LINKS, 0);
        c.pts = fit(c.pts, SimState.LINKS, 0);
        if (c.ready.length < SimState.LINKS) c.ready = Arrays.copyOf(c.ready, SimState.LINKS);
        c.unlockStep = fit(c.unlockStep, SimState.LINKS, 0);
        c.lastStepDay = fit(c.lastStepDay, SimState.LINKS, -1);
        c.heldRank = fit(c.heldRank, SimState.LINKS, 0);
        c.towerVisits = fit(c.towerVisits, m.t.nBlocks, 0);
        c.shifts = fit(c.shifts, m.t.nActs, 0);
        c.used = fit(c.used, m.t.nActs, 0);
        for (int l = 0; l < SimState.LINKS; l++) c.rank[l] = Math.max(0, Math.min(10, c.rank[l]));
        if (c.cash < 0) c.cash = 0;
        return c;
    }

    private static int[] fit(int[] a, int n, int fill) {
        if (a != null && a.length >= n) return a;
        int[] out = new int[n];
        Arrays.fill(out, fill);
        if (a != null) System.arraycopy(a, 0, out, 0, a.length);
        return out;
    }

    static int statLv(Model m, SimState st, int stat) {
        return m.rules.stats[stat].levelFor(st.statPts[stat]);
    }

    static boolean charmBonusActive(Model m, SimState st) {
        int ci = m.t.charmIdx;
        return m.charmMult > 1.0 && ci >= 0 && st.statPts[ci] >= m.t.statCap[ci];
    }

    static double mult(Model m, SimState st) {
        double x = m.personaMult;
        if (charmBonusActive(m, st)) x *= m.charmMult;
        return x;
    }

    /** Floor per meeting, with a small epsilon so 10 × 1.51 is 15 and 100 × 1.51 is 151. */
    static int scaled(int base, double mult) {
        return (int) Math.floor(base * mult + 1e-6);
    }

    static int ordinaryPts(Model m, double mult) {
        return Math.max(1, scaled(m.ordinaryGain, mult));
    }

    /** Points carried into the next rank after the rank-{@code rank} scene. */
    static int eventPts(Model m, int link, int rank, double mult) {
        if (!m.bestAnswers) return 0;
        int[] eg = m.eventGain[link];
        if (eg == null || rank >= eg.length) return 0;
        return scaled(eg[rank], mult);
    }

    /** Points needed while at {@code rank} to reach {@code rank+1}, goal buffer included. */
    static int need(Model m, int link, int rank) {
        int thr = m.rules.links[link].threshold[rank + 1];
        return thr + (bufferApplies(m, link, rank) ? m.ordinaryGain : 0);
    }

    static boolean bufferApplies(Model m, int link, int rank) {
        return m.goal[link] && m.goalBuffer && m.rules.links[link].threshold[rank + 1] > 0;
    }

    /** The rank-up into {@code into} is blocked on {@code day} by a rule hold or a player-reported hold. */
    static boolean held(Model m, SimState st, int link, int into, int day) {
        if (into < 1 || into > 10) return false;
        if (st.heldRank[link] == into) return true;
        int until = m.t.holdUntil[link][into];
        if (until == Tables.NO_HOLD) return false;
        return until == Tables.HOLD_FOREVER || day < until;
    }

    /** Whether the next meeting with {@code link} ranks up under the model. */
    static boolean ranksUp(Model m, SimState st, int link, int day) {
        int r = st.rank[link];
        if (r >= 10) return false;
        if (st.ready[link]) return true;
        if (held(m, st, link, r + 1, day)) return false;
        return st.pts[link] >= need(m, link, r);
    }

    /** Link lost for good: its missing person was not rescued in time. */
    static boolean lost(Model m, SimState st, int link, int day) {
        int ri = m.t.missRescue[link];
        if (ri < 0) return false;
        long bit = 1L << ri;
        if ((st.rescueFailed & bit) != 0) return true;
        return (st.rescued & bit) == 0 && day > m.t.missTo[link];
    }

    /** Link cannot be met or unlocked on {@code day} (missing and not yet rescued, or lost). */
    static boolean blocked(Model m, SimState st, int link, int day) {
        if (lost(m, st, link, day)) return true;
        Tables t = m.t;
        if (t.missFrom[link] < 0 || day < t.missFrom[link] || day > t.missTo[link]) return false;
        int ri = t.missRescue[link];
        return ri < 0 || (st.rescued & (1L << ri)) == 0;
    }

    static boolean prereqsMet(Model m, SimState st, int link) {
        int[] rs = m.reqStat[link];
        for (int i = 0; i < rs.length; i++) {
            if (rs[i] < m.t.nStats && statLv(m, st, rs[i]) < m.reqStatLv[link][i]) return false;
        }
        int[] rl = m.reqLink[link];
        for (int i = 0; i < rl.length; i++) {
            if (rl[i] < SimState.LINKS && st.rank[rl[i]] < m.reqLinkRank[link][i]) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ legality

    /** Writes the legal productive action codes for {@code st.slot} into {@code out}; returns the count. */
    static int legal(Model m, SimState st, int[] out) {
        Tables t = m.t;
        int slot = st.slot;
        if (!t.open(slot)) return 0;
        int day = slot >> 1;
        boolean eve = (slot & 1) == 1;
        int n = 0;
        int mask = t.meetMask[slot];
        for (int l = 0; mask != 0; l++, mask >>>= 1) {
            if ((mask & 1) != 0 && meetLegal(m, st, l, day)) out[n++] = code(T_MEET, l, 0);
        }
        for (int l = 0; l < t.nLinks; l++) {
            int c = unlockGroup(m, st, l, day, eve);
            if (c >= 0) out[n++] = code(T_UNLOCK, l, c);
        }
        for (int a : t.actsAt[slot]) {
            if (activityLegal(m, st, a)) out[n++] = code(T_ACT, a, 0);
        }
        int b = t.towerBlock[slot];
        if (b >= 0 && st.towerVisits[b] < m.towerCap) out[n++] = code(T_TOWER, b, 0);
        return n;
    }

    /** Upper bound of {@link #legal}'s output size. */
    static int maxLegal(Model m) {
        return m.t.nLinks * 2 + m.t.nActs + 1;
    }

    static boolean meetLegal(Model m, SimState st, int link, int day) {
        int r = st.rank[link];
        return r >= 1 && r < 10 && !blocked(m, st, link, day);
    }

    /**
     * The slot-consuming unlock action legal for a link on this day: returns the index of the
     * consuming step that ends the group (non-consuming steps of a same-slot chain before it are
     * folded in, see {@link Tables#groupEnd}), or -1. Non-consuming steps that cannot fold are never
     * a slot's action; they happen as side steps ({@link #side}). Unlock steps are offered only
     * while the link is not established (rank 0). Every step needs the unlock prerequisites
     * (earliest day, stat Lv, link ranks); costs are mandatory (no cash buffer).
     */
    static int unlockGroup(Model m, SimState st, int link, int day, boolean eve) {
        Tables t = m.t;
        int k = st.unlockStep[link];
        if (st.rank[link] != 0 || k < 0 || k >= t.stepCount[link]) return -1;
        Rules.Link l = m.rules.links[link];
        if (l.kind != Rules.LinkKind.MANUAL) return -1;
        int c = t.groupEnd[link][k];
        if (c < 0) return -1;
        List<Rules.Step> steps = l.unlock.steps;
        if (steps.get(c).evening != eve) return -1;
        if (day < l.unlock.earliestDay || blocked(m, st, link, day) || !prereqsMet(m, st, link)) return -1;
        long cost = 0;
        for (int j = k; j <= c; j++) {
            Rules.Step s = steps.get(j);
            if (!t.stepDayOk[link][j][day]) return -1;
            if (j == k && st.lastStepDay[link] >= 0 && day - st.lastStepDay[link] < s.minGapDays) return -1;
            cost += Math.max(0, s.cost);
        }
        return st.cash >= cost ? c : -1;
    }

    /** The link wants its unlock steps done on the side (a goal, or every link when 其余社群 is on). */
    static boolean wantsSide(Model m, int link) {
        return m.goal[link] || m.profile.otherLinks;
    }

    /**
     * The pending non-consuming step of {@code link} can happen on the side in this slot with
     * {@code cash} yen available: right slot kind, day rule and fromDay, earliest day, not missing,
     * prerequisites, minGapDays since the previous step, and the fee.
     */
    static boolean sideLegal(Model m, SimState st, int link, int day, boolean eve, long cash) {
        Tables t = m.t;
        int k = st.unlockStep[link];
        if (st.rank[link] != 0 || k < 0 || k >= t.stepCount[link] || !t.sideStep[link][k]) return false;
        Rules.Link l = m.rules.links[link];
        if (l.kind != Rules.LinkKind.MANUAL) return false;
        Rules.Step s = l.unlock.steps.get(k);
        if (s.evening != eve || !t.stepDayOk[link][k][day]) return false;
        if (day < l.unlock.earliestDay || blocked(m, st, link, day) || !prereqsMet(m, st, link)) return false;
        if (st.lastStepDay[link] >= 0 && day - st.lastStepDay[link] < s.minGapDays) return false;
        return cash >= Math.max(0, s.cost);
    }

    /** Cash the main action needs at the start of the slot (fees, plus the buffer for optional spends). */
    static long mainCashNeed(Model m, SimState st, int code) {
        switch (type(code)) {
            case T_ACT: {
                int cost = Math.max(0, m.rules.activities[target(code)].cost);
                return cost > 0 ? cost + m.cashBuffer : 0;
            }
            case T_UNLOCK: {
                int link = target(code);
                List<Rules.Step> steps = m.rules.links[link].unlock.steps;
                long cost = 0;
                for (int j = Math.max(0, st.unlockStep[link]); j <= step(code) && j < steps.size(); j++) {
                    cost += Math.max(0, steps.get(j).cost);
                }
                return cost;
            }
            default:
                return 0;
        }
    }

    /**
     * Side steps of this slot (REPORT §0: non-consuming unlock steps need a free slot of their kind
     * on an allowed day but leave the slot to another action): before the main action {@code code},
     * every wanted link (goal, or all when 其余社群 is on) whose next unlock step is non-consuming
     * and legal now does that one step (at most one step per link per slot). The main action's own
     * link is skipped (its unlock action already covers a foldable chain) and the main action's
     * fees are reserved, so the main action stays legal. Locked slots have no side steps.
     */
    static void side(Model m, SimState st, int code, Effect fx) {
        Tables t = m.t;
        int mask = t.sideMask;
        if (mask == 0 || !t.open(st.slot)) return;
        int day = st.slot >> 1;
        boolean eve = (st.slot & 1) == 1;
        int skip = type(code) == T_UNLOCK ? target(code) : -1;
        long reserve = -1;
        for (int l = 0; mask != 0; l++, mask >>>= 1) {
            if ((mask & 1) == 0 || l == skip || st.rank[l] != 0 || !wantsSide(m, l)) continue;
            if (reserve < 0) reserve = mainCashNeed(m, st, code);
            if (!sideLegal(m, st, l, day, eve, st.cash - reserve)) continue;
            int k = st.unlockStep[l];
            Rules.Step s = m.rules.links[l].unlock.steps.get(k);
            st.cash = Math.max(0, st.cash - Math.max(0, s.cost));
            st.unlockStep[l] = k + 1;
            st.lastStepDay[l] = day;
            if (k == t.establishIdx[l]) {
                st.rank[l] = 1;
                st.pts[l] = eventPts(m, l, 1, mult(m, st));
                st.ready[l] = false;
            }
            if (fx != null && fx.sideCount < fx.sideLink.length) {
                fx.sideLink[fx.sideCount] = l;
                fx.sideStep[fx.sideCount] = k;
                fx.sideCount++;
            }
        }
    }

    static boolean activityLegal(Model m, SimState st, int act) {
        if (m.excluded[act]) return false;
        Rules.Activity a = m.rules.activities[act];
        if (a.once && st.used[act] > 0) return false;
        for (java.util.Map.Entry<Integer, Integer> e : a.requiresLv.entrySet()) {
            if (e.getKey() < m.t.nStats && statLv(m, st, e.getKey()) < e.getValue()) return false;
        }
        int cost = Math.max(0, a.cost);
        if (st.cash < cost + (cost > 0 ? m.cashBuffer : 0)) return false;
        return a.kind == Rules.ActivityKind.JOB || usefulGain(m, st, a) > 0;
    }

    /** Stat points the activity would add below the Lv6 cap (points above it are worthless). */
    static int usefulGain(Model m, SimState st, Rules.Activity a) {
        int sum = 0;
        int n = Math.min(a.gain.length, m.t.nStats);
        for (int s = 0; s < n; s++) {
            if (a.gain[s] > 0) sum += Math.min(a.gain[s], Math.max(0, m.t.statCap[s] - st.statPts[s]));
        }
        return sum;
    }

    // ------------------------------------------------------------------ transitions

    /** Apply {@code code} (with this slot's side steps) at {@code st.slot} and advance one slot (day rollover included). */
    static void apply(Model m, SimState st, int code, Effect fx) {
        effect(m, st, code, fx);
        advance(m, st);
    }

    /** This slot's side steps, then the action's own effect, without advancing the slot. */
    static void effect(Model m, SimState st, int code, Effect fx) {
        begin(m, st, fx);
        side(m, st, code, fx);
        mainEffect(m, st, code, fx);
        end(m, st, fx);
    }

    /** Reset {@code fx} and record the pre-slot cash and stat levels. */
    static void begin(Model m, SimState st, Effect fx) {
        if (fx == null) return;
        fx.reset();
        fx.cashBefore = st.cash;
        for (int s = 0; s < m.t.nStats && s < 3; s++) fx.lvBefore[s] = statLv(m, st, s);
    }

    /** Record the post-slot cash and stat levels in {@code fx}. */
    static void end(Model m, SimState st, Effect fx) {
        if (fx == null) return;
        fx.cashAfter = st.cash;
        for (int s = 0; s < m.t.nStats && s < 3; s++) fx.lvAfter[s] = statLv(m, st, s);
    }

    /** The action's own effect only (no side steps, no reset of {@code fx}, no slot advance). */
    static void mainEffect(Model m, SimState st, int code, Effect fx) {
        int day = st.slot >> 1;
        switch (type(code)) {
            case T_MEET:
                meet(m, st, target(code), day, fx);
                break;
            case T_UNLOCK:
                unlock(m, st, target(code), step(code), day, fx);
                break;
            case T_ACT:
                activity(m, st, target(code), fx);
                break;
            case T_TOWER:
                tower(m, st, target(code), day, fx);
                break;
            default:
                break;
        }
    }

    private static void meet(Model m, SimState st, int link, int day, Effect fx) {
        int r = st.rank[link];
        if (r >= 10) return;
        double mult = mult(m, st);
        int extra = (m.personaMult > 1 ? A_PERSONA : 0) | (charmBonusActive(m, st) ? A_CHARM : 0);
        boolean wasReady = st.ready[link];
        boolean isHeld = !wasReady && held(m, st, link, r + 1, day);
        int need = need(m, link, r);
        boolean buffer = bufferApplies(m, link, r);
        if (fx != null) {
            fx.rankFrom = r;
            fx.invite = (m.t.inviteMask[st.slot] & (1 << link)) != 0;
            fx.held = isHeld;
        }
        if (!isHeld && (wasReady || st.pts[link] >= need)) {
            int to = r + 1;
            st.rank[link] = to;
            st.pts[link] = eventPts(m, link, to, mult);
            st.ready[link] = false;
            st.heldRank[link] = 0;
            if (fx != null) {
                fx.rankTo = to;
                fx.rankUp = true;
                fx.certain = wasReady || m.rules.links[link].threshold[to] == 0;
                fx.estimate = !fx.certain;
                int a = 0;
                if (!fx.certain) a |= A_ORDINARY | (buffer ? A_BUFFER : 0);
                if (st.pts[link] > 0) a |= A_BEST | extra;
                fx.assumptions = a;
            }
            return;
        }
        int gained = st.pts[link] + ordinaryPts(m, mult);
        if (isHeld) gained = Math.min(gained, Math.max(st.pts[link], need));
        st.pts[link] = gained;
        if (fx != null) {
            fx.rankTo = r;
            fx.estimate = true;
            fx.assumptions = A_ORDINARY | extra | (buffer ? A_BUFFER : 0) | (isHeld ? A_HOLD : 0);
        }
    }

    private static void unlock(Model m, SimState st, int link, int last, int day, Effect fx) {
        Tables t = m.t;
        List<Rules.Step> steps = m.rules.links[link].unlock.steps;
        int from = Math.max(0, st.unlockStep[link]);
        last = Math.min(last, steps.size() - 1);
        if (fx != null) fx.rankFrom = st.rank[link];
        boolean established = false;
        for (int j = from; j <= last; j++) {
            st.cash = Math.max(0, st.cash - Math.max(0, steps.get(j).cost));
            if (j == t.establishIdx[link]) established = true;
        }
        st.unlockStep[link] = Math.max(st.unlockStep[link], last + 1);
        st.lastStepDay[link] = day;
        if (established && st.rank[link] == 0) {
            st.rank[link] = 1;
            st.pts[link] = eventPts(m, link, 1, mult(m, st));
            st.ready[link] = false;
        }
        if (fx != null) {
            fx.rankTo = st.rank[link];
            fx.rankUp = fx.rankTo > fx.rankFrom;
            fx.certain = true;
        }
    }

    private static void activity(Model m, SimState st, int act, Effect fx) {
        Rules.Activity a = m.rules.activities[act];
        st.cash = Math.max(0, st.cash - Math.max(0, a.cost));
        if (a.kind == Rules.ActivityKind.JOB) {
            int pay = a.payFor(st.shifts[act] + 1);
            st.cash += pay;
            st.shifts[act]++;
            st.cashExact = false;
            if (fx != null) {
                fx.pay = pay;
                fx.estimate = true;
                fx.assumptions |= A_JOB_PAY;
            }
        }
        int n = Math.min(a.gain.length, m.t.nStats);
        for (int s = 0; s < n; s++) {
            st.statPts[s] += a.gain[s];
            if (fx != null && s < 3) fx.statDelta[s] = a.gain[s];
        }
        st.used[act]++;
    }

    private static void tower(Model m, SimState st, int block, int day, Effect fx) {
        if (block < 0 || block >= st.towerVisits.length) return;
        st.towerVisits[block]++;
        if (m.towerIncome > 0) {
            st.cash += m.towerIncome;
            st.cashExact = false;
        }
        Tables t = m.t;
        int rescue = -1;
        for (int i = 0; i < t.nRescues; i++) {
            long bit = 1L << i;
            if (day >= t.rescueFrom[i] && day <= t.rescueTo[i] && ((st.rescued | st.rescueFailed) & bit) == 0) {
                st.rescued |= bit;
                if (rescue < 0) rescue = i;
            }
        }
        if (fx != null) {
            fx.towerVisit = st.towerVisits[block];
            fx.rescue = rescue;
            fx.estimate = m.towerIncome > 0 || rescue >= 0;
            fx.assumptions = (m.towerIncome > 0 ? A_TOWER_INCOME : 0) | (rescue >= 0 ? A_RESCUE : 0);
        }
    }

    /** Move to the next slot; entering a new day applies story ranks, rescue deadlines and auto unlocks. */
    static void advance(Model m, SimState st) {
        st.slot++;
        if ((st.slot & 1) == 0) rollover(m, st, st.slot >> 1);
    }

    static void rollover(Model m, SimState st, int day) {
        Tables t = m.t;
        for (int i = 0; i < t.nRescues; i++) {
            long bit = 1L << i;
            if (day > t.rescueTo[i] && ((st.rescued | st.rescueFailed) & bit) == 0) st.rescueFailed |= bit;
        }
        if (day > t.lastDay) return;
        for (int l = 0; l < t.nLinks; l++) {
            int[] story = t.storyRankBy[l];
            if (story != null) {
                if (story[day] > st.rank[l]) st.rank[l] = story[day];
                continue;
            }
            if (st.rank[l] == 0 && t.stepCount[l] == 0 && autoUnlocks(m, st, l, day)) {
                st.rank[l] = 1;
                st.pts[l] = eventPts(m, l, 1, mult(m, st));
                st.ready[l] = false;
            }
        }
    }

    /** Manual link without unlock steps: the story establishes it once the date and prerequisites allow. */
    private static boolean autoUnlocks(Model m, SimState st, int link, int day) {
        Rules.Link l = m.rules.links[link];
        return l.kind == Rules.LinkKind.MANUAL && day >= l.unlock.earliestDay
                && !blocked(m, st, link, day) && prereqsMet(m, st, link);
    }
}
