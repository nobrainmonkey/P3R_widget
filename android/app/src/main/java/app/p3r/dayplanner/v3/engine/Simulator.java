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
        return out;
    }

    static Engine.Applied apply(Rules rules, Profile profile, SimState state, Action action) {
        return apply(rules, profile, state, action, null);
    }

    static Engine.Applied apply(Rules rules, Profile profile, SimState state, Action action, Engine.Options options) {
        Model m = new Model(rules, profile, options);
        SimState st = Kernel.normalize(m, state);
        checkTarget(m, action);
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
     *   <li>AS_PREDICTED – the forecast transition; visible values become observed.</li>
     *   <li>RANK_UP – rank set to {@code outcome.rank} (or +1), points reset to the scene's estimate.</li>
     *   <li>NO_RANK_UP – rank kept; points raised to {@code max(pts + gain, need - ordinaryGain)}.</li>
     *   <li>SKIPPED / NOT_DONE – slot consumed with no effect; NOT_DONE with reason "hold" freezes
     *       the link's next rank-up until the player reports progress.</li>
     *   <li>OTHER – the forecast transition, then explicit visible values override it.</li>
     * </ul>
     * In every case a ready cue sets {@code ready}, stat Lv values clamp the hidden points into
     * that Lv's range and a cash balance sets cash exactly. The slot always advances by one.
     *
     * <p>Side steps: unless {@link Outcome#sideDone} is false, the slot's side steps (pending
     * non-consuming unlock steps the plan wants, see {@code Kernel.side}) are applied first, exactly
     * as the forecast did for this action, whatever happened to the main action.
     */
    static SimState applyOutcome(Rules rules, Profile profile, SimState state, Action action, Outcome outcome) {
        Model m = new Model(rules, profile, null);
        SimState st = Kernel.normalize(m, state);
        if (outcome == null) outcome = Outcome.of(Outcome.Code.AS_PREDICTED);
        if (action == null) action = Action.free();
        checkTarget(m, action);
        int code = Kernel.encode(action);
        int link = linkOf(action);
        if (outcome.sideDone) Kernel.side(m, st, code, null);
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
                Kernel.mainEffect(m, st, code, null);
                break;
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

    private static int linkOf(Action a) {
        return a.type == Action.Type.MEET || a.type == Action.Type.UNLOCK_STEP ? a.target : -1;
    }

    private static void rankUp(Model m, SimState st, Action action, int link, int observedRank) {
        if (link < 0) {
            Kernel.mainEffect(m, st, Kernel.encode(action), null);
            return;
        }
        int to;
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
        int gain = Kernel.ordinaryPts(m, Kernel.mult(m, st));
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
        st.pts[link] = to >= 10 ? 0 : Kernel.eventPts(m, link, to, Kernel.mult(m, st));
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
            default:
                break;
        }
    }
}
