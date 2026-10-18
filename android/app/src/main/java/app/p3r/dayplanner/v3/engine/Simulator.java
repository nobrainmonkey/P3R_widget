package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Forecast transitions and legality (docs/DESIGN_v3.md §5.2), exposed through {@link Engine}.
 * Every method works on a normalized copy of its input state; the caller's state is never mutated.
 */
final class Simulator {
    private Simulator() {}

    static List<Action> legalActions(Rules rules, Profile profile, SimState state) {
        Model m = new Model(rules, profile, null);
        SimState st = Kernel.normalize(m, state);
        int[] codes = new int[Kernel.maxLegal(m)];
        int n = Kernel.legal(m, st, codes);
        List<Action> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(Kernel.decode(codes[i]));
        // These are conditional recording choices, never inputs to the automatic planner.
        for (int l = 0; l < m.t.nLinks; l++) {
            if (Kernel.inviteCandidate(m, st, l)) out.add(Action.invite(l));
        }
        return out;
    }

    static Engine.Applied apply(Rules rules, Profile profile, SimState state, Action action) {
        return apply(rules, profile, state, action, null);
    }

    static Engine.Applied apply(Rules rules, Profile profile, SimState state, Action action, Engine.Options options) {
        Model m = new Model(rules, profile, options);
        SimState st = Kernel.normalize(m, state);
        checkTarget(m, action);
        if (action.type == Action.Type.TARTARUS) m.initializeBatches(st);
        Kernel.Effect fx = new Kernel.Effect();
        Kernel.apply(m, st, Kernel.encode(action), fx);
        Engine.Applied out = new Engine.Applied();
        out.state = st;
        out.predicted = predicted(fx);
        out.estimate = fx.estimate;
        List<String> ids = new ArrayList<>();
        fx.addAssumptionIds(ids);
        out.assumptions = Collections.unmodifiableList(ids);
        out.side = Collections.unmodifiableList(sideKeys(m, fx));
        return out;
    }

    /** Action keys of the side steps recorded in {@code fx}, in order ("unlock:devil:0"). */
    static List<String> sideKeys(Model m, Kernel.Effect fx) {
        List<String> keys = new ArrayList<>(fx.sideCount);
        for (int i = 0; i < fx.sideCount; i++) keys.add(Action.unlock(fx.sideLink[i], fx.sideStep[i]).key(m.rules));
        return keys;
    }

    /** Forecast prediction record from a kernel effect. */
    static Plan.Predicted predicted(Kernel.Effect fx) {
        Plan.Predicted p = new Plan.Predicted();
        p.rankFrom = fx.rankFrom;
        p.rankTo = fx.rankTo;
        p.rankUp = fx.rankUp;
        p.certain = fx.certain;
        p.cashDelta = fx.cashAfter - fx.cashBefore;
        p.cashAfter = fx.cashAfter;
        for (int s = 0; s < 3; s++) {
            p.statDelta[s] = fx.statDelta[s];
            p.statLvAfter[s] = fx.lvAfter[s] != fx.lvBefore[s] ? fx.lvAfter[s] : -1;
        }
        return p;
    }

    /** The slot's side steps for {@code action}, applied to a normalized copy that stays at its slot. */
    static SimState applySideSteps(Rules rules, Profile profile, SimState state, Action action) {
        Model m = new Model(rules, profile, null);
        SimState st = Kernel.normalize(m, state);
        if (action == null) action = Action.free();
        checkTarget(m, action);
        Kernel.side(m, st, Kernel.encode(action), null);
        return st;
    }

    /** See {@link Engine#sideSteps}. */
    static Engine.SideSteps sideSteps(Rules rules, Profile profile, SimState state, Action action, List<String> keys) {
        Model m = new Model(rules, profile, null);
        SimState st = Kernel.normalize(m, state);
        if (action == null) action = Action.free();
        checkTarget(m, action);
        int code = Kernel.encode(action);
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        if (keys == null) {
            Kernel.Effect fx = new Kernel.Effect();
            Kernel.side(m, st, code, fx);
            applied.addAll(sideKeys(m, fx));
        } else {
            recordedSide(m, st, code, keys, applied, rejected);
        }
        Engine.SideSteps out = new Engine.SideSteps();
        out.state = st;
        out.applied = Collections.unmodifiableList(applied);
        out.rejected = Collections.unmodifiableList(rejected);
        return out;
    }

    /**
     * Applies the recorded side-step {@code keys} in order (see {@link Engine#sideSteps}); each
     * key goes to {@code applied} or {@code rejected} when those lists are given. At most one step
     * per link in a slot, like {@code Kernel.side}. A fee larger than the known cash makes the cash
     * an estimate.
     */
    private static void recordedSide(Model m, SimState st, int code, List<String> keys,
                                     List<String> applied, List<String> rejected) {
        int day = st.slot >> 1;
        boolean[] done = new boolean[m.t.nLinks];
        for (String key : keys) {
            Action a = Action.parse(m.rules, key);
            boolean ok = a != null && a.type == Action.Type.UNLOCK_STEP && a.target >= 0 && a.target < m.t.nLinks
                    && !done[a.target] && Kernel.recordedSideLegal(m, st, code, a.target, a.step);
            if (!ok) {
                if (rejected != null) rejected.add(key);
                continue;
            }
            long cost = Math.max(0, m.rules.links[a.target].unlock.steps.get(a.step).cost);
            if (cost > st.cash) st.cashExact = false;
            Kernel.doSide(m, st, a.target, a.step, day);
            done[a.target] = true;
            if (applied != null) applied.add(a.key(m.rules));
        }
    }

    /** Advance without acting until {@code slot} (day rollovers applied). */
    static SimState advanceTo(Rules rules, Profile profile, SimState state, int slot) {
        Model m = new Model(rules, profile, null);
        SimState st = Kernel.normalize(m, state);
        while (st.slot < slot) Kernel.advance(m, st);
        return st;
    }

    /**
     * Apply a player-confirmed outcome (docs/DESIGN_v3.md §6/§7 and Outcome codes):
     * <ul>
     *   <li>AS_PREDICTED – the forecast transition; visible values become observed. A meeting or
     *       unlock step that carries the rank the player confirmed ({@code outcome.rank >= 0}) is
     *       reconciled to it exactly like OTHER, so replaying it under a changed profile (goal
     *       buffer, Persona mode, answers, Charm bonus) never moves a confirmed rank.</li>
     *   <li>RANK_UP – rank set to {@code outcome.rank} (or +1), points reset to the scene's estimate.</li>
     *   <li>NO_RANK_UP – rank kept; points raised to {@code max(pts + gain, need - ordinaryGain)}.</li>
     *   <li>SKIPPED / NOT_DONE – slot consumed with no effect; NOT_DONE with reason "hold" freezes
     *       the link's next rank-up until the player reports progress.</li>
     *   <li>OTHER – the forecast transition, then explicit visible values override it.</li>
     * </ul>
     * A dating-site note (NOTE) pays the price and adds its estimated points under AS_PREDICTED,
     * NO_RANK_UP and OTHER; it never ranks the link up unless the player reports a higher RANK. A
     * Link Episode (EPISODE) counts as watched unless it was skipped or not done.
     * In every case a ready cue sets {@code ready}, stat Lv values clamp the hidden points into
     * that Lv's range and a cash balance sets cash exactly. The slot always advances by one.
     *
     * <p>Side steps: unless {@link Outcome#sideDone} is false, the slot's side steps are applied
     * first, whatever happened to the main action: exactly {@link Outcome#sideKeys} when given
     * (see {@link #sideSteps}), otherwise the pending non-consuming unlock steps the plan wants
     * ({@code Kernel.side}), as the forecast did for this action.
     */
    static SimState applyOutcome(Rules rules, Profile profile, SimState state, Action action, Outcome outcome) {
        Model m = new Model(rules, profile, null);
        SimState st = Kernel.normalize(m, state);
        if (outcome == null) outcome = Outcome.of(Outcome.Code.AS_PREDICTED);
        if (action == null) action = Action.free();
        checkTarget(m, action);
        int code = Kernel.encode(action);
        int link = linkOf(action);
        if (outcome.sideDone) {
            if (outcome.sideKeys != null) recordedSide(m, st, code, outcome.sideKeys, null, null);
            else Kernel.side(m, st, code, null);
        }
        // A pack update must not grant newly modelled affinity to old activity records. Keep the
        // kernel's stat/cash/counter effects, then replace only its affinity with the saved delta.
        int predictedAffinityLink = action.type == Action.Type.ACTIVITY ? rules.activities[action.target].affinityLink : -1;
        int predictedAffinityBefore = predictedAffinityLink >= 0 ? st.pts[predictedAffinityLink] : 0;
        switch (outcome.code) {
            case RANK_UP:
                rankUp(m, st, action, link, outcome.rank);
                break;
            case NO_RANK_UP:
                if (action.type == Action.Type.MEET) noRankUp(m, st, link);
                else Kernel.mainEffect(m, st, code, null);
                break;
            case SKIPPED:
                break;
            case NOT_DONE:
                if ("hold".equals(outcome.reason) && link >= 0 && st.rank[link] >= 1 && st.rank[link] < 10) {
                    st.heldRank[link] = st.rank[link] + 1;
                    st.ready[link] = false;
                }
                break;
            case OTHER:
                other(m, st, action, link, outcome.rank);
                break;
            case AS_PREDICTED:
            default:
                boolean ranked = action.type == Action.Type.MEET || action.type == Action.Type.UNLOCK_STEP;
                if (ranked && link >= 0 && outcome.rank >= 0) other(m, st, action, link, outcome.rank);
                else Kernel.mainEffect(m, st, code, null);
                break;
        }
        if (action.type == Action.Type.ACTIVITY) {
            if (predictedAffinityLink >= 0) st.pts[predictedAffinityLink] = predictedAffinityBefore;
            boolean completed = outcome.code != Outcome.Code.SKIPPED && outcome.code != Outcome.Code.NOT_DONE;
            int recordedLink = outcome.activityAffinityLink == null ? -1 : rules.linkIndex(outcome.activityAffinityLink);
            if (completed && recordedLink >= 0 && outcome.activityAffinityPts > 0) {
                st.pts[recordedLink] += outcome.activityAffinityPts;
            }
        }
        if (outcome.readyCue && link >= 0 && st.rank[link] >= 1 && st.rank[link] < 10) {
            st.ready[link] = true;
            st.heldRank[link] = 0;
        }
        applyVisibleStats(m, st, outcome.statLv);
        if (outcome.cashBalance >= 0) {
            st.cash = outcome.cashBalance;
            st.cashExact = true;
        }
        Kernel.advance(m, st);
        return st;
    }

    /** The link an action is about (meetings, unlock steps and dating-site notes), else -1. */
    private static int linkOf(Action a) {
        return a.type == Action.Type.MEET || a.type == Action.Type.INVITE
                || a.type == Action.Type.UNLOCK_STEP || a.type == Action.Type.NOTE ? a.target : -1;
    }

    private static void rankUp(Model m, SimState st, Action action, int link, int observedRank) {
        if (link < 0) {
            Kernel.mainEffect(m, st, Kernel.encode(action), null);
            return;
        }
        int to;
        if (action.type == Action.Type.NOTE || action.type == Action.Type.INVITE) {
            // Notes and invitations never rank up by themselves; believe an explicit observed rank only.
            Kernel.mainEffect(m, st, Kernel.encode(action), null);
            if (observedRank > st.rank[link]) setRank(m, st, link, Math.min(10, observedRank));
            return;
        }
        if (action.type == Action.Type.UNLOCK_STEP) {
            // Pays the step costs; "ranked up" after an unlock step means the link is now established.
            Kernel.mainEffect(m, st, Kernel.encode(action), null);
            to = observedRank >= 0 ? observedRank : Math.max(1, st.rank[link]);
        } else {
            to = observedRank >= 0 ? observedRank : st.rank[link] + 1;
        }
        setRank(m, st, link, Math.min(10, to));
    }

    private static void noRankUp(Model m, SimState st, int link) {
        int r = st.rank[link];
        if (r < 1 || r >= 10) return;
        int gain = Kernel.ordinaryPts(m, Kernel.mult(m, st, link));
        int need = Kernel.need(m, link, r);
        st.pts[link] = Math.max(st.pts[link] + gain, need - m.ordinaryGain);
        st.ready[link] = false;
    }

    private static void other(Model m, SimState st, Action action, int link, int observedRank) {
        int before = link >= 0 ? st.rank[link] : -1;
        SimState pre = link >= 0 ? st.copy() : null;
        Kernel.mainEffect(m, st, Kernel.encode(action), null);
        if (link < 0 || observedRank < 0) return;
        int obs = Math.min(10, observedRank);
        if (obs > before) {
            if (st.rank[link] != obs) setRank(m, st, link, obs);
            st.heldRank[link] = 0;
        } else if (obs == before) {
            if (st.rank[link] != before && action.type == Action.Type.MEET) {
                // Forecast said rank-up, the player says no: same as NO_RANK_UP from the pre-state.
                st.rank[link] = before;
                st.pts[link] = pre.pts[link];
                st.ready[link] = false;
                noRankUp(m, st, link);
            }
        } else {
            st.rank[link] = obs;
            st.pts[link] = 0;
            st.ready[link] = false;
        }
    }

    private static void setRank(Model m, SimState st, int link, int to) {
        st.rank[link] = to;
        st.pts[link] = to >= 10 ? 0 : Kernel.eventPts(m, link, to, Kernel.mult(m, st, link));
        st.ready[link] = false;
        st.heldRank[link] = 0;
        if (to >= 1) st.unlockStep[link] = Math.max(st.unlockStep[link], m.t.stepCount[link]);
    }

    /** Clamp hidden stat points into the visible Lv's range [threshold(Lv), threshold(Lv+1) - 1]. */
    private static void applyVisibleStats(Model m, SimState st, int[] statLv) {
        if (statLv == null) return;
        for (int s = 0; s < Math.min(statLv.length, m.t.nStats); s++) {
            int lv = statLv[s];
            if (lv < 1 || lv > 6) continue;
            int[] thr = m.rules.stats[s].thresholds;
            int lo = thr[lv];
            int hi = lv < 6 ? thr[lv + 1] - 1 : Integer.MAX_VALUE;
            st.statPts[s] = Math.max(lo, Math.min(hi, st.statPts[s]));
        }
    }

    private static void checkTarget(Model m, Action a) {
        Tables t = m.t;
        switch (a.type) {
            case MEET:
            case INVITE:
            case UNLOCK_STEP:
                if (a.target < 0 || a.target >= t.nLinks) throw new IllegalArgumentException("bad link " + a);
                if (a.type == Action.Type.UNLOCK_STEP && (a.step < 0 || a.step >= t.stepCount[a.target])) {
                    throw new IllegalArgumentException("bad unlock step " + a);
                }
                break;
            case ACTIVITY:
                if (a.target < 0 || a.target >= t.nActs) throw new IllegalArgumentException("bad activity " + a);
                break;
            case TARTARUS:
                if (a.target < 0 || a.target >= t.nBlocks) throw new IllegalArgumentException("bad block " + a);
                break;
            case NOTE:
                if (a.target < 0 || a.target >= t.nLinks) throw new IllegalArgumentException("bad link " + a);
                break;
            case EPISODE:
                if (a.target < 0 || a.target >= t.nEps) throw new IllegalArgumentException("bad episode track " + a);
                break;
            default:
                break;
        }
    }
}
