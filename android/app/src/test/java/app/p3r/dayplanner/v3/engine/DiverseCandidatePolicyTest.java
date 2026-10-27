package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

/** Unlock/gateway exploration must survive the bounded candidate cut. */
public class DiverseCandidatePolicyTest {
    @Test
    public void selectedTraitAndGoalGatewayBothSurviveTwoHigherMeetings() {
        assertSelectedTraitSurvives(false);
    }

    @Test
    public void highScoringUnselectedDormCannotHideSelectedTraitFromRollout() {
        assertSelectedTraitSurvives(true);
    }

    private static void assertSelectedTraitSurvives(boolean unselectedDorm) {
        Rules rules = GoalPrerequisitePolicyTest.plainLinks(3);
        for (Rules.Link link : rules.links) {
            link.evening = true;
            link.unlock.steps.get(0).evening = true;
        }
        Rules.Activity dorm = RulesFixtures.activity(0, "dorm_yukari_dvd", "和由加莉看DVD",
                Rules.ActivityKind.STAT, false, true, 0, RulesFixtures.ACADEMICS, 0);
        dorm.dormPartnerZh = "岳羽由加莉";
        dorm.fixedDays = new int[]{20, 22, 24};
        rules.activities = new Rules.Activity[]{dorm};
        if (unselectedDorm) {
            Rules.Activity other = RulesFixtures.activity(1, "dorm_ken_dvd", "和天田看DVD",
                    Rules.ActivityKind.STAT, false, true, 0, RulesFixtures.ACADEMICS, 0);
            other.dormPartnerZh = "天田乾";
            other.fixedDays = dorm.fixedDays;
            other.affinityLink = 0;
            other.affinityPts = 30;
            rules.activities = new Rules.Activity[]{dorm, other};
            rules.links[0].threshold[10] = 30;
        }
        Profile profile = new Profile();
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UNLOCK);
        for (int l = 0; l < 3; l++) {
            profile.tier[l] = Profile.Tier.MUST;
            profile.order[l] = l;
        }
        SimState state = new SimState(0, rules.activities.length);
        state.slot = Rules.slot(rules.firstDay, true);
        state.cash = 100_000;
        state.rank[0] = state.rank[1] = 9;
        state.ready[0] = state.ready[1] = true;
        if (unselectedDorm) state.ready[0] = false;
        Engine.Options options = options(3);
        Model model = new Model(rules, profile, options);
        Heuristics h = new Heuristics(model, state, null, Heuristics.Tuning.DEFAULT.goalFirst());
        h.prepare(state);
        int trait = Kernel.encode(Action.activity(0));
        int gateway = Kernel.encode(Action.unlock(2, 0));
        assertTrue(h.score(state, trait) < h.score(state, Kernel.encode(Action.meet(1))));
        if (unselectedDorm) assertTrue("the ordinary dorm candidate would win by raw H",
                h.score(state, Kernel.encode(Action.activity(1))) > h.score(state, trait));
        int[] candidates = new Planner.Search(model, h, options, Long.MAX_VALUE, 0, 0).rolloutCandidates(state);
        assertTrue("a selected trait remains available to whole-plan evaluation", contains(candidates, trait));
        assertTrue("keeping a trait must not evict the goal gateway", contains(candidates, gateway));
        assertEquals("the separately tried greedy meeting still counts within K=3", 2, candidates.length);
    }

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
        profile.tier[0] = profile.tier[1] = Profile.Tier.MUST;
        profile.tier[3] = Profile.Tier.WANT;
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
        profile.tier[3] = Profile.Tier.MUST;
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
