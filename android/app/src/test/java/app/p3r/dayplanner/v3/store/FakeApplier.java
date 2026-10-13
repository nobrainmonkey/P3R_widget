package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic stand-in for {@code Engine.applyOutcome} (the engine is a stub in this module's
 * worktree). Conventions: AS_PREDICTED on a meeting means the predicted rank-up happened;
 * NO_RANK_UP adds 10 points; activities add their nominal gain; jobs pay their tier; a watched
 * Link Episode counts one; an explicit cash balance overrides the cash. Side steps (for tower
 * records) cost {@link #sideFee} and are counted; time moves without rollovers.
 */
public final class FakeApplier implements OutcomeApplier {
    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger sideCalls = new AtomicInteger();
    /** Cash a tower record's side steps cost (0: none). */
    public volatile long sideFee;
    /** Every action applied, in order. */
    public final java.util.List<Action> actions = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public SimState applySideSteps(Rules rules, Profile profile, SimState state, Action action) {
        sideCalls.incrementAndGet();
        SimState s = state.copy();
        s.cash = Math.max(0, s.cash - sideFee);
        return s;
    }

    @Override
    public SimState apply(Rules rules, Profile profile, SimState state, Action action, Outcome o) {
        calls.incrementAndGet();
        actions.add(action);
        SimState s = state.copy();
        boolean done = o.code == Outcome.Code.AS_PREDICTED || o.code == Outcome.Code.OTHER;
        switch (action.type) {
            case MEET:
                applyMeeting(s, action.target, o);
                break;
            case UNLOCK_STEP:
                if (done) {
                    s.unlockStep[action.target]++;
                    s.rank[action.target] = Math.max(1, s.rank[action.target]);
                }
                break;
            case ACTIVITY:
                if (done) {
                    Rules.Activity a = rules.activities[action.target];
                    for (int i = 0; i < 3; i++) {
                        s.statPts[i] += a.gain[i];
                    }
                    if (a.kind == Rules.ActivityKind.JOB) {
                        s.shifts[action.target]++;
                        s.cash += a.payFor(s.shifts[action.target]);
                    }
                }
                break;
            case EPISODE:
                if (done && action.target < s.episodes.length) {
                    s.episodes[action.target]++;
                }
                break;
            default:
                break;
        }
        if (o.cashBalance >= 0) {
            s.cash = o.cashBalance;
            s.cashExact = true;
        }
        s.slot = state.slot + 1;
        return s;
    }

    private static void applyMeeting(SimState s, int link, Outcome o) {
        switch (o.code) {
            case RANK_UP:
                s.rank[link] = o.rank >= 0 ? o.rank : Math.min(10, s.rank[link] + 1);
                s.pts[link] = 0;
                break;
            case AS_PREDICTED:
                s.rank[link] = Math.min(10, s.rank[link] + 1);
                s.pts[link] = 0;
                break;
            case NO_RANK_UP:
                s.pts[link] += 10;
                break;
            case OTHER:
                if (o.rank >= 0) {
                    s.rank[link] = o.rank;
                }
                break;
            default:
                break;
        }
        s.ready[link] = o.readyCue;
    }
}
