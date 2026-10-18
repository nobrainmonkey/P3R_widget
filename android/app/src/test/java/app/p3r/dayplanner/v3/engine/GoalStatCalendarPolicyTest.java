package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.List;

/** Prerequisite training inherits the selected links, without overriding actual free capacity. */
public class GoalStatCalendarPolicyTest {
    @Test
    public void statValueFollowsMustWantButNotTheOrdinalWithinATier() {
        Rules rules = mirroredGates();
        SimState start = start(rules, 24);
        for (boolean goalFirst : new boolean[]{false, true}) {
            Profile profile = goals();
            profile.tier[0] = Profile.Tier.MUST;
            profile.tier[1] = Profile.Tier.WANT;
            assertTrue("Academics unlocks the MUST goal", training(rules, profile, start, goalFirst, 0)
                    > training(rules, profile, start, goalFirst, 1));
            profile.tier[0] = Profile.Tier.WANT;
            profile.tier[1] = Profile.Tier.MUST;
            assertTrue("Courage now unlocks the MUST goal", training(rules, profile, start, goalFirst, 1)
                    > training(rules, profile, start, goalFirst, 0));
            profile.tier[0] = profile.tier[1] = Profile.Tier.MUST;
            profile.order[0] = 0;
            profile.order[1] = 1;
            double beforeA = training(rules, profile, start, goalFirst, 0);
            double beforeC = training(rules, profile, start, goalFirst, 1);
            assertEquals("equal physical gates in one tier have equal preparation value", beforeA, beforeC, 1e-6);
            profile.order[0] = 1;
            profile.order[1] = 0;
            assertEquals("reordering two MUST links must not reorder their stat preparation",
                    beforeA, training(rules, profile, start, goalFirst, 0), 1e-6);
            assertEquals(beforeC, training(rules, profile, start, goalFirst, 1), 1e-6);
        }
    }

    @Test
    public void anUngatedFirstDaytimeGoalDoesNotDelayAnotherMustGoalsEveningAcademics() {
        Rules rules = mirroredGates();
        rules.links[0].unlock.statLv.clear();
        rules.links[0].unlock.earliestDay = 25;
        rules.links[0].avail.set(25, rules.lastDay + 1);
        rules.links[1].unlock.statLv.clear();
        rules.links[1].unlock.statLv.put(0, 6);
        Profile profile = goals();
        profile.tier[0] = profile.tier[1] = Profile.Tier.MUST;
        profile.order[1] = 1;
        SimState start = start(rules, 24);
        start.statPts[0] = rules.stats[0].thresholds[6] - 8;
        start.statPts[2] = rules.stats[2].thresholds[6];
        assertEquals("use the night for the second MUST goal's real Academics gap",
                Kernel.encode(Action.activity(0)), choose(rules, profile, start, true));
        SimState tomorrow = start.copy();
        tomorrow.slot = Rules.slot(25, false);
        assertEquals("the ungated first goal still receives the next daytime",
                Kernel.encode(Action.unlock(0, 0)), choose(rules, profile, tomorrow, true));
        double before = training(rules, profile, start, true, 0);
        profile.order[0] = 1;
        profile.order[1] = 0;
        assertEquals("the same-tier list position does not change the night prerequisite",
                before, training(rules, profile, start, true, 0), 1e-6);
    }

    @Test
    public void looseFirstMustGoalNotesCannotStarveAnUrgentSameTierStatGate() {
        Rules rules = mirroredGates();
        rules.links[0].unlock.statLv.clear();
        rules.links[0].threshold[10] = 30;
        rules.links[1].unlock.statLv.clear();
        rules.links[1].unlock.statLv.put(0, 6);
        rules.datingNote = RulesFixtures.datingNote(20);
        Profile profile = goals();
        profile.tier[0] = profile.tier[1] = Profile.Tier.MUST;
        profile.target[0] = 10;
        profile.order[1] = 1;
        SimState state = start(rules, 24);
        state.statPts[0] = rules.stats[0].thresholds[6] - 8;
        state.statPts[2] = rules.stats[2].thresholds[6];
        state.rank[0] = 9;
        state.pts[0] = 29;
        assertTrue("the first goal has a genuine one-point note option",
                Engine.legalActions(rules, profile, state).contains(Action.note(0)));
        assertEquals("many later note opportunities cannot take all nights from the imminent gate",
                Kernel.encode(Action.activity(0)), choose(rules, profile, state, true));
        double before = training(rules, profile, state, true, 0);
        profile.order[0] = 1;
        profile.order[1] = 0;
        assertEquals(before, training(rules, profile, state, true, 0), 1e-6);
        assertEquals(Kernel.encode(Action.activity(0)), choose(rules, profile, state, true));
    }

    @Test
    public void goalFirstDoesNotBuyPointsAlreadyCoveredByRealLaterFreeDates() {
        Rules rules = mirroredGates();
        rules.links = new Rules.Link[]{rules.links[0]};
        rules.links[0].unlock.earliestDay = 40;
        rules.links[0].unlock.statLv.put(0, 6);
        Rules.Activity paid = RulesFixtures.activity(0, "paid", "收费培训", Rules.ActivityKind.STAT, false, true, 100, 0, 4);
        Rules.Activity free = RulesFixtures.activity(1, "later_free", "集体学习", Rules.ActivityKind.STAT, false, true, 0, 0, 4);
        free.fixedDays = new int[]{30, 35};
        rules.activities = new Rules.Activity[]{paid, free};
        Profile profile = goals();
        profile.tier[0] = Profile.Tier.MUST;
        SimState start = start(rules, 24);
        start.statPts[0] = rules.stats[0].thresholds[6] - 8;
        Heuristics h = new Heuristics(new Model(rules, profile, null), start, null, Heuristics.Tuning.DEFAULT.goalFirst());
        h.prepare(start);
        assertTrue("the two real later free sessions cover the entire remaining gate",
                Heuristics.vetoed(h.score(start, Kernel.encode(Action.activity(0)))));
    }

    @Test
    public void sharedSchoolDaysArePreservedWhileFlexibleMeetingsUseTheirHolidayAlternatives() {
        Rules rules = schoolAndHolidayCalendar();
        rules.links[1].threshold[9] = 30;
        rules.links[1].eventGain[9] = 30;
        Profile profile = new Profile();
        profile.charmBonus = false;
        profile.towerVisitsPerBlock = profile.towerCapPerBlock = 0;
        SimState crowded = schoolStart(rules);
        // Four required school meetings exceed the three shared O dates: the seven holidays
        // cannot repay this school-only work, although they can serve the outside link.
        crowded.rank[1] = 8;
        SimState otherwiseFree = crowded.copy();
        otherwiseFree.rank[1] = 10;
        assertTrue("the second school link shares the same three O dates, despite seven spare holidays",
                meeting(rules, profile, crowded, 0) > meeting(rules, profile, otherwiseFree, 0));
        int chosen = choose(rules, profile, crowded, false);
        assertTrue("a school day goes to one of the two constrained school links",
                chosen == Kernel.encode(Action.meet(0)) || chosen == Kernel.encode(Action.meet(1)));

        SimState holiday = crowded.copy();
        holiday.slot = Rules.slot(37, false);
        assertEquals("the flexible outside link still uses its real holiday O cell",
                Kernel.encode(Action.meet(2)), choose(rules, profile, holiday, false));
    }

    @Test
    public void explicitMustOutsideLinkStillBeatsOrdinarySchoolLinks() {
        Rules rules = schoolAndHolidayCalendar();
        Profile profile = new Profile();
        profile.charmBonus = false;
        profile.towerVisitsPerBlock = profile.towerCapPerBlock = 0;
        profile.tier[2] = Profile.Tier.MUST;
        SimState state = schoolStart(rules);
        for (boolean goalFirst : new boolean[]{false, true}) {
            assertEquals("school-calendar scarcity must respect the user's selected goal",
                    Kernel.encode(Action.meet(2)), choose(rules, profile, state, goalFirst));
        }
    }

    @Test
    public void anEarlierActualUnlockFeePrecedesLaterTrainingAndStopsWhenFunded() {
        Rules rules = feeAndTraining();
        Profile profile = goals();
        profile.tier[0] = profile.tier[1] = Profile.Tier.MUST;
        profile.order[1] = 1;
        SimState state = start(rules, 27);
        state.statPts[0] = rules.stats[0].thresholds[2];
        state.cash = 0;
        double feeJob = training(rules, profile, state, true, 0);
        double lowerTraining = training(rules, profile, state, true, 1);
        assertTrue("an already-open fee must not be starved by a later gate's large raw stat value",
                feeJob > lowerTraining);
        assertTrue("goal preparation stays below a mandatory rescue", feeJob < Heuristics.MANDATORY
                && lowerTraining < Heuristics.MANDATORY);
        assertEquals(Kernel.encode(Action.activity(0)), choose(rules, profile, state, true));
        profile.order[0] = 1;
        profile.order[1] = 0;
        assertEquals("fee preparation follows the real deadline, not the list position",
                feeJob, training(rules, profile, state, true, 0), 1e-6);
        assertEquals(Kernel.encode(Action.activity(0)), choose(rules, profile, state, true));

        state.cash = 10_000 + Heuristics.feeReserve(10_000);
        assertTrue("an already-funded fee provides no reason to earn surplus cash",
                Heuristics.vetoed(training(rules, profile, state, true, 0)));
        assertEquals("the lower goal can now receive its useful training",
                Kernel.encode(Action.activity(1)), choose(rules, profile, state, true));
    }

    @Test
    public void aFeeOutsideTheRealLookaheadDoesNotCreateAnEarlyCashPriority() {
        Rules rules = feeAndTraining();
        rules.links[0].unlock.earliestDay = 55;
        Profile profile = goals();
        profile.tier[0] = profile.tier[1] = Profile.Tier.MUST;
        profile.order[1] = 1;
        SimState state = start(rules, 27);
        state.statPts[0] = rules.stats[0].thresholds[2];
        state.cash = 0;
        assertTrue("the fee opens after the existing 21-day funding window",
                Heuristics.vetoed(training(rules, profile, state, true, 0)));
        assertEquals(Kernel.encode(Action.activity(1)), choose(rules, profile, state, true));
    }

    private static Rules feeAndTraining() {
        Rules rules = mirroredGates();
        rules.links[0].unlock.steps.get(0).cost = 10_000;
        rules.links[1].unlock.earliestDay = 35;
        Rules.Activity job = RulesFixtures.activity(0, "fee_job", "打工", Rules.ActivityKind.JOB, false, true, 0, 0, 0);
        job.pay = new int[][]{{1, 2_500}};
        rules.activities = new Rules.Activity[]{job, rules.activities[1]};
        return rules;
    }

    private static double meeting(Rules rules, Profile profile, SimState state, int link) {
        Heuristics h = new Heuristics(new Model(rules, profile, null), state, null);
        h.prepare(state);
        return h.score(state, Kernel.encode(Action.meet(link)));
    }

    private static int choose(Rules rules, Profile profile, SimState state, boolean goalFirst) {
        List<Action> actions = Engine.legalActions(rules, profile, state);
        int[] codes = new int[actions.size()];
        for (int i = 0; i < codes.length; i++) codes[i] = Kernel.encode(actions.get(i));
        Heuristics.Tuning tuning = goalFirst ? Heuristics.Tuning.DEFAULT.goalFirst() : Heuristics.Tuning.DEFAULT;
        return new Heuristics(new Model(rules, profile, null), state, null, tuning).choose(state, codes, codes.length);
    }

    private static SimState schoolStart(Rules rules) {
        SimState state = new SimState(rules.blocks.length, rules.activities.length);
        state.slot = Rules.slot(34, false);
        state.cash = 100_000;
        for (int stat = 0; stat < rules.stats.length; stat++) state.statPts[stat] = rules.stats[stat].thresholds[6];
        for (int link = 0; link < rules.links.length; link++) state.rank[link] = 9;
        state.ready[0] = state.ready[2] = true;
        state.pts[1] = 29;
        return state;
    }

    private static Rules schoolAndHolidayCalendar() {
        Rules rules = RulesFixtures.small();
        rules.firstDay = 33;
        rules.lastDay = 43;
        rules.blocks = new Rules.Block[0];
        rules.rescues = new Rules.Rescue[0];
        rules.activities = new Rules.Activity[0];
        rules.datingNote = null;
        for (int day = rules.firstDay; day <= rules.lastDay; day++) {
            rules.days[day].type = day >= 34 && day <= 36 ? Rules.DayType.SCHOOL : Rules.DayType.HOLIDAY;
            rules.days[day].dayLock = rules.days[day].eveLock = null;
            rules.days[day].tartarus = false;
            rules.days[day].block = -1;
        }
        rules.links = new Rules.Link[3];
        for (int l = 0; l < rules.links.length; l++) {
            Rules.Link link = RulesFixtures.link(l, "calendar_" + l, l, "社群" + l, "同伴" + l,
                    Rules.LinkKind.MANUAL, false, l < 2 ? "学校" : "书店");
            link.avail.set(34, l < 2 ? 37 : 44);
            link.threshold[10] = 30;
            rules.links[l] = link;
        }
        return rules;
    }

    private static double training(Rules rules, Profile profile, SimState start, boolean goalFirst, int activity) {
        Heuristics.Tuning tuning = goalFirst ? Heuristics.Tuning.DEFAULT.goalFirst() : Heuristics.Tuning.DEFAULT;
        Heuristics h = new Heuristics(new Model(rules, profile, null), start, null, tuning);
        h.prepare(start);
        return h.score(start, Kernel.encode(Action.activity(activity)));
    }

    private static Profile goals() {
        Profile profile = new Profile();
        profile.otherLinks = false;
        profile.charmBonus = false;
        profile.towerVisitsPerBlock = profile.towerCapPerBlock = 0;
        profile.target[0] = profile.target[1] = 1;
        return profile;
    }

    private static SimState start(Rules rules, int day) {
        SimState state = new SimState(rules.blocks.length, rules.activities.length);
        state.slot = Rules.slot(day, true);
        state.cash = 100_000;
        for (int stat = 0; stat < rules.stats.length; stat++) state.statPts[stat] = rules.stats[stat].thresholds[6];
        state.statPts[0] = rules.stats[0].thresholds[2] - 8;
        state.statPts[2] = rules.stats[2].thresholds[2] - 8;
        return state;
    }

    private static Rules mirroredGates() {
        Rules rules = RulesFixtures.small();
        rules.blocks = new Rules.Block[0];
        rules.rescues = new Rules.Rescue[0];
        for (Rules.Day day : rules.days) if (day != null) {
            day.dayLock = day.eveLock = null;
            day.tartarus = false;
            day.block = -1;
        }
        rules.links = new Rules.Link[2];
        for (int l = 0; l < rules.links.length; l++) {
            Rules.Link link = RulesFixtures.link(l, "gate_" + l, l, "目标" + l, "同伴" + l,
                    Rules.LinkKind.MANUAL, false, "学校");
            link.unlock.earliestDay = 27;
            link.unlock.statLv.put(l == 0 ? 0 : 2, 2);
            link.unlock.steps.add(RulesFixtures.step(link.id + ".join", "对话", false, Rules.StepDays.LINK_AVAIL, 0, 0, true));
            link.avail.set(27, rules.lastDay + 1);
            rules.links[l] = link;
        }
        rules.activities = new Rules.Activity[]{
                RulesFixtures.activity(0, "academics", "学力练习", Rules.ActivityKind.STAT, false, true, 0, 0, 4),
                RulesFixtures.activity(1, "courage", "勇气练习", Rules.ActivityKind.STAT, false, true, 0, 2, 4)};
        return rules;
    }
}
