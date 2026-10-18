package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Waiting for already allocated free study is different from leaving a real training gap idle. */
public class StatWaitingValidationTest {
    private static final int LINK = RulesFixtures.S_EMPEROR;
    private static final int START = Rules.slot(30, true);

    private static Rules rules() {
        Rules rules = RulesFixtures.small();
        rules.rescues = new Rules.Rescue[0];
        rules.links[LINK].unlock.earliestDay = 45;
        rules.links[LINK].unlock.statLv.put(0, 6);
        Rules.Activity ordinary = rules.activities[0];
        Rules.Activity first = RulesFixtures.activity(1, "dated_first", "集体学习", Rules.ActivityKind.STAT, false, true, 0, 0, 2);
        first.fixedDays = new int[]{35};
        Rules.Activity second = RulesFixtures.activity(2, "dorm_fuuka_reading", "和风花读书", Rules.ActivityKind.STAT, false, true, 0, 0, 2);
        second.fixedDays = new int[]{40};
        second.dormPartnerZh = "山岸风花";
        rules.activities = new Rules.Activity[]{ordinary, first, second};
        return rules;
    }

    private static Profile profile() {
        Profile p = new Profile();
        p.otherLinks = false;
        p.tier[LINK] = Profile.Tier.WANT;
        p.target[LINK] = 1;
        p.charmBonus = false;
        p.towerVisitsPerBlock = p.towerCapPerBlock = 0;
        return p;
    }

    private static SimState start(Rules rules) {
        SimState s = RulesFixtures.newGame(rules, START);
        Arrays.fill(s.rank, 10);
        s.rank[LINK] = 0;
        s.cash = 100_000;
        for (int i = 0; i < 3; i++) s.statPts[i] = rules.stats[i].thresholds[6];
        s.statPts[0] -= 3;
        return s;
    }

    /** Build a complete explicit schedule so tests do not depend on the policy choosing it. */
    private static Plan schedule(Rules rules, Profile p, SimState start, Map<Integer, Action> actions) {
        Plan plan = new Plan();
        plan.fromSlot = start.slot;
        plan.toSlot = rules.lastSlot();
        SimState state = start;
        while (state.slot <= rules.lastSlot()) {
            String lock = p.lockOf(rules, state.slot);
            Action action = lock == null ? actions.getOrDefault(state.slot, Action.free()) : Action.locked(lock);
            Engine.Applied applied = Engine.apply(rules, p, state, action);
            Plan.Step step = new Plan.Step();
            step.slot = state.slot;
            step.action = action.key(rules);
            step.kind = lock != null ? "locked" : action.type == Action.Type.ACTIVITY
                    ? rules.activities[action.target].dormActivity() ? "dorm" : "stat" : "free";
            step.titleZh = "测试安排";
            step.predicted = applied.predicted;
            step.side = applied.side;
            plan.steps.add(step);
            state = applied.state;
        }
        plan.summary.endRanks = state.rank.clone();
        return plan;
    }

    private static boolean warningNow(Rules rules, Profile p, SimState start, Map<Integer, Action> actions) {
        Plan plan = schedule(rules, p, start, actions);
        PlanValidator.Report report = PlanValidator.check(rules, p, start, plan);
        assertTrue(report.errors.toString(), report.errors.isEmpty());
        return report.warnings.contains("slot " + START + ": FREE while a productive action was legal");
    }

    private static Map<Integer, Action> bothSessions() {
        Map<Integer, Action> actions = new HashMap<>();
        actions.put(Rules.slot(35, true), Action.activity(1));
        actions.put(Rules.slot(40, true), Action.activity(2));
        return actions;
    }

    @Test
    public void allocatedDatedFreeStudyBeforeTheOpeningMakesOrdinaryStudyRedundant() {
        Rules rules = rules();
        Profile p = profile();
        assertFalse(warningNow(rules, p, start(rules), bothSessions()));
    }

    @Test
    public void merelyAvailableOrInsufficientFutureStudyDoesNotHideWaste() {
        Rules rules = rules();
        Profile p = profile();
        assertTrue(warningNow(rules, p, start(rules), Map.of()));
        assertTrue(warningNow(rules, p, start(rules), Map.of(Rules.slot(35, true), Action.activity(1))));
    }

    @Test
    public void studyAfterTheOpeningCannotJustifyWaitingBeforeAnAvailableGate() {
        Rules rules = rules();
        Profile p = profile();
        rules.links[LINK].unlock.earliestDay = 39;
        assertTrue(warningNow(rules, p, start(rules), bothSessions()));
        rules.links[LINK].unlock.earliestDay = 29;
        assertTrue("an already open gate makes training useful now", warningNow(rules, p, start(rules), bothSessions()));
    }

    @Test
    public void anUnscheduledOrLockedSessionCannotBeCountedAsFutureTraining() {
        Rules rules = rules();
        Profile p = profile();
        rules.days[40].eveLock = "story";
        assertTrue(warningNow(rules, p, start(rules), bothSessions()));
    }

    @Test
    public void theCurrentDatedSessionRemainsProductiveEvenWithFutureCoverage() {
        Rules rules = rules();
        Profile p = profile();
        rules.activities[0].fixedDays = new int[]{30};
        assertTrue(warningNow(rules, p, start(rules), bothSessions()));
    }
}
