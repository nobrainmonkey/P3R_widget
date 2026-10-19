package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

/** Unlock/gateway exploration must survive the bounded candidate cut. */
public class DiverseCandidatePolicyTest {
    @Test
    public void aLowerScoringAdmittedUnlockSurvivesTwoHigherMeetingsAndKTwo() {
        Rules rules = GoalPrerequisitePolicyTest.plainLinks(5);
        Profile profile = new Profile();
        profile.charmBonus = false;
        profile.goalBuffer = false;
        SimState state = GoalPrerequisitePolicyTest.state(rules);
        state.rank[0] = state.rank[1] = 9;
        state.rank[2] = 0;
        state.rank[3] = state.rank[4] = 10;
        for (int l = 0; l < 2; l++) {
            rules.links[l].avail.clear();
            rules.links[l].avail.set(rules.firstDay);
        }
        Engine.Options options = options(2);
        Model model = new Model(rules, profile, options);
        Heuristics h = new Heuristics(model, state, null);
        h.prepare(state);
        int unlock = Kernel.encode(Action.unlock(2, 0));
        assertTrue("the test needs an unlock that raw top-K would discard",
                h.score(state, unlock) < h.score(state, Kernel.encode(Action.meet(1))));
        int[] candidates = new Planner.Search(model, h, options, Long.MAX_VALUE, 0, 0).rolloutCandidates(state);
        assertTrue(contains(candidates, unlock));
        assertEquals("the base action occupies the other K=2 slot", 1, candidates.length);
    }

    @Test
    public void anOrdinaryUnlockCannotSuppressTheThirdGoalsGatewayMeeting() {
        Rules rules = GoalPrerequisitePolicyTest.plainLinks(5);
        rules.links[3].unlock.linkRank.put(2, 2);
        Profile profile = new Profile();
        profile.charmBonus = false;
        profile.goalBuffer = false;
        profile.tier[0] = profile.tier[1] = profile.tier[3] = Profile.Tier.MUST;
        profile.order[0] = 0;
        profile.order[1] = 1;
        profile.order[3] = 2;
        profile.target[3] = 1;
        SimState state = GoalPrerequisitePolicyTest.state(rules);
        state.rank[0] = state.rank[1] = 9;
        state.rank[3] = state.rank[4] = 0;
        Engine.Options options = options(3);
        Model model = new Model(rules, profile, options);
        Heuristics h = new Heuristics(model, state, null, Heuristics.Tuning.DEFAULT.goalFirst());
        h.prepare(state);
        int gateway = Kernel.encode(Action.meet(2));
        int ordinaryUnlock = Kernel.encode(Action.unlock(4, 0));
        assertTrue(h.isAdmittedOrGate(4));
        assertTrue("two direct goals precede the gateway by H", h.score(state, gateway)
                < h.score(state, Kernel.encode(Action.meet(1))));
        int[] candidates = new Planner.Search(model, h, options, Long.MAX_VALUE, 0, 0).rolloutCandidates(state);
        assertTrue(contains(candidates, gateway));
        assertFalse(contains(candidates, ordinaryUnlock));
        assertEquals(2, candidates.length);
    }

    @Test
    public void theBaseGatewayAlreadyInsideTopKIsNotDuplicated() {
        Rules rules = GoalPrerequisitePolicyTest.chain();
        Profile profile = GoalPrerequisitePolicyTest.goals();
        SimState state = GoalPrerequisitePolicyTest.state(rules);
        Engine.Options options = options(2);
        Model model = new Model(rules, profile, options);
        Heuristics h = new Heuristics(model, state, null, Heuristics.Tuning.DEFAULT.goalFirst());
        int[] candidates = new Planner.Search(model, h, options, Long.MAX_VALUE, 0, 0).rolloutCandidates(state);
        assertFalse("the greedy gateway is separately tried as the base",
                contains(candidates, Kernel.encode(Action.meet(2))));
        assertTrue(candidates.length <= 1);
    }

    @Test
    public void reservingAnEarlierGoalsUnlockStillCountsADifferentGreedyUnlockInK() {
        Rules rules = GoalPrerequisitePolicyTest.chain();
        Profile profile = GoalPrerequisitePolicyTest.goals();
        profile.target[3] = 1;
        SimState state = GoalPrerequisitePolicyTest.state(rules);
        state.rank[2] = 2;
        state.rank[3] = 0;
        state.rank[4] = 10;
        rules.links[3].avail.clear();
        rules.links[3].avail.set(rules.firstDay);
        Engine.Options options = options(2);
        Model model = new Model(rules, profile, options);
        Heuristics h = new Heuristics(model, state, null);
        h.prepare(state);
        int earlierGate = Kernel.encode(Action.unlock(1, 0));
        int greedyUnlock = Kernel.encode(Action.unlock(3, 0));
        assertTrue("the best H unlock is different from the highest-priority gateway",
                h.score(state, greedyUnlock) > h.score(state, earlierGate));
        int[] candidates = new Planner.Search(model, h, options, Long.MAX_VALUE, 0, 0).rolloutCandidates(state);
        assertTrue(contains(candidates, earlierGate));
        assertFalse(contains(candidates, greedyUnlock));
        assertEquals("the greedy unlock must still consume one of the K=2 slots", 1, candidates.length);
    }

    private static Engine.Options options(int k) {
        Engine.Options options = new Engine.Options();
        options.candidates = k;
        return options;
    }

    private static boolean contains(int[] codes, int wanted) {
        for (int code : codes) if (code == wanted) return true;
        return false;
    }
}
