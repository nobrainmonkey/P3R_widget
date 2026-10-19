package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

/** A chosen goal owns the useful part of its complete link-rank prerequisite chain. */
public class GoalPrerequisitePolicyTest {
    @Test
    public void towerPriorityReachesChariotThroughUnopenedStrength() {
        Rules rules = chain();
        Profile profile = goals();
        SimState state = state(rules);
        Heuristics h = policy(rules, profile, state);
        assertEquals(0, h.goalPriority(0));
        assertEquals(0, h.goalPriority(1));
        assertEquals(0, h.goalPriority(2));
        assertTrue(h.opensGate(2));
        assertTrue("the first goal's two-hop prerequisite beats a direct later goal",
                h.score(state, Kernel.encode(Action.meet(2)))
                        > h.score(state, Kernel.encode(Action.meet(3))));
    }

    @Test
    public void inheritanceStopsAtFulfilledRanksAndEstablishedParents() {
        Rules rules = chain();
        Profile profile = goals();
        SimState state = state(rules);
        state.rank[2] = 2;
        Heuristics h = policy(rules, profile, state);
        assertEquals("Chariot needs only R2 to open Strength", -1, h.goalPriority(2));
        assertEquals(0, h.goalPriority(1));

        state.rank[1] = 1;
        state.rank[2] = 1; // An edited save must not reopen an established link's old gate.
        h = policy(rules, profile, state);
        assertEquals(-1, h.goalPriority(2));
        assertEquals(0, h.goalPriority(1));

        state.rank[1] = 4;
        h = policy(rules, profile, state);
        assertEquals("Strength is not an implicit MAX goal", -1, h.goalPriority(1));

        state.rank[0] = 1;
        state.rank[1] = 1;
        h = policy(rules, profile, state);
        assertEquals("an established Tower no longer needs its unlock chain", -1, h.goalPriority(1));
        assertFalse(h.opensGate(1));
    }

    @Test
    public void anExistingLowerRankGoalDoesNotHideAnotherGoalsHigherGate() {
        Rules rules = chain();
        Profile profile = goals();
        profile.tier[1] = Profile.Tier.WANT;
        profile.target[1] = 2;
        profile.otherLinks = false;
        SimState state = state(rules);
        state.rank[1] = 2;
        Heuristics h = policy(rules, profile, state);
        assertEquals("Strength's own R2 goal is done but Tower still requires R4", 0, h.goalPriority(1));
        assertTrue(h.score(state, Kernel.encode(Action.meet(1))) > Heuristics.GOAL_FIRST);
    }

    @Test
    public void sharedPrerequisiteUsesBestUnfinishedGoalAndReleasesItWhenDone() {
        Rules rules = chain();
        rules.links[3].unlock.linkRank.put(2, 3);
        Profile profile = goals();
        SimState state = state(rules);
        state.rank[3] = 0;
        Heuristics h = policy(rules, profile, state);
        assertEquals(0, h.goalPriority(2));
        state.rank[0] = profile.target[0];
        h = policy(rules, profile, state);
        assertEquals("the still-unopened second goal keeps its own requirement", 1, h.goalPriority(2));
        state.rank[2] = 3;
        h = policy(rules, profile, state);
        assertEquals(-1, h.goalPriority(2));
    }

    @Test
    public void heldPrerequisiteDoesNotGetAFalseUrgentMeeting() {
        Rules rules = chain();
        Profile profile = goals();
        SimState state = state(rules);
        state.heldRank[2] = 2;
        Heuristics h = policy(rules, profile, state);
        assertEquals(-1, h.goalPriority(2));
        assertTrue(h.score(state, Kernel.encode(Action.meet(2))) < Heuristics.GOAL_FIRST);
        state.ready[2] = true;
        h = policy(rules, profile, state);
        assertEquals("the player's current rank-up cue overrides an older hold", 0, h.goalPriority(2));
        assertTrue(h.score(state, Kernel.encode(Action.meet(2))) > Heuristics.GOAL_FIRST);
    }

    @Test
    public void malformedCyclesTerminateWithoutLosingAnIndependentGoal() {
        Rules rules = chain();
        rules.links[2].unlock.linkRank.put(1, 1);
        Profile profile = goals();
        SimState state = state(rules);
        state.rank[2] = 0;
        Heuristics h = policy(rules, profile, state);
        assertEquals(0, h.goalPriority(1));
        assertEquals(0, h.goalPriority(2));
        assertEquals(1, h.goalPriority(3));
    }

    static Rules chain() {
        Rules rules = plainLinks(5);
        rules.links[0].unlock.linkRank.put(1, 4); // Tower -> Strength R4
        rules.links[1].unlock.linkRank.put(2, 2); // Strength -> Chariot R2
        return rules;
    }

    static Rules plainLinks(int count) {
        Rules rules = RulesFixtures.small();
        rules.blocks = new Rules.Block[0];
        rules.rescues = new Rules.Rescue[0];
        rules.activities = new Rules.Activity[0];
        rules.datingNote = null;
        for (int day = rules.firstDay; day <= rules.lastDay; day++) {
            rules.days[day].dayLock = rules.days[day].eveLock = null;
            rules.days[day].block = -1;
            rules.days[day].tartarus = false;
        }
        rules.links = new Rules.Link[count];
        for (int l = 0; l < count; l++) {
            Rules.Link link = RulesFixtures.link(l, "link_" + l, l, "社群" + l, "同伴" + l,
                    Rules.LinkKind.MANUAL, false, "学校");
            link.avail.set(rules.firstDay, rules.lastDay + 1);
            link.unlock.earliestDay = rules.firstDay;
            link.unlock.steps.add(RulesFixtures.step("open_" + l, "解锁", false,
                    Rules.StepDays.LINK_AVAIL, 0, 0, true));
            rules.links[l] = link;
        }
        return rules;
    }

    static Profile goals() {
        Profile profile = new Profile();
        profile.charmBonus = false;
        profile.goalBuffer = false;
        profile.towerVisitsPerBlock = profile.towerCapPerBlock = 0;
        profile.tier[0] = profile.tier[3] = Profile.Tier.MUST;
        profile.target[0] = 1;
        profile.order[3] = 1;
        return profile;
    }

    static SimState state(Rules rules) {
        SimState state = new SimState(0, rules.activities.length);
        state.slot = Rules.slot(rules.firstDay, false);
        state.cash = 100_000;
        state.cashExact = true;
        state.rank[2] = state.rank[3] = state.rank[4] = 1;
        return state;
    }

    private static Heuristics policy(Rules rules, Profile profile, SimState state) {
        Heuristics h = new Heuristics(new Model(rules, profile, null), state, null,
                Heuristics.Tuning.DEFAULT.goalFirst());
        h.prepare(state);
        return h;
    }
}
