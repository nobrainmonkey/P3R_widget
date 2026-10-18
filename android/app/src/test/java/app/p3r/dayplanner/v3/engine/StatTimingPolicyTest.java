package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Training follows actual unlock dates and leaves a useful gap for later free opportunities. */
public class StatTimingPolicyTest {
    @Test
    public void laterFreeSessionsKeepTheirGapAndStillOpenTheSelectedMaxLinkOnTime() {
        Rules rules = lateGate();
        Rules.Activity first = free(2, "group_first", 5, 35);
        Rules.Activity second = free(3, "group_second", 5, 40);
        Rules.Activity dorm = free(4, "dorm_junpei_reading", 2, 22, 25, 30);
        dorm.dormPartnerZh = "伊织顺平";
        rules.activities = new Rules.Activity[]{rules.activities[0], rules.activities[1], first, second, dorm};
        SimState start = start(rules, 20, 20);
        Profile profile = goal();
        for (long budget : new long[]{0, 1_000}) {
            Plan plan = plan(rules, profile, start, budget);
            SimState end = start.copy();
            List<String> used = new ArrayList<>();
            for (Plan.Step step : plan.steps) {
                if (step.slot < Rules.slot(40, true)) assertTrue("leave room for the last free study: " + step.action,
                        end.statPts[0] < rules.stats[0].thresholds[6]);
                if (step.action.startsWith("act:")) used.add(step.action);
                end = Engine.apply(rules, profile, end, Action.parse(rules, step.action)).state;
            }
            assertTrue(used.toString(), used.contains("act:group_first"));
            assertTrue(used.toString(), used.contains("act:group_second"));
            assertTrue(used.toString(), used.contains("act:dorm_junpei_reading"));
            assertFalse(used.toString(), used.contains("act:paid"));
            assertEquals("selected link still reaches MAX", 10, end.rank[0]);
        }
    }

    @Test
    public void unavoidableStoryPointsAreNotBoughtAgainAndEveningDoesNotCountThemTwice() {
        Rules rules = lateGate();
        rules.days[25].automaticStatGain = new int[]{3, 0, 0};
        rules.days[26].automaticStatGain = new int[]{3, 0, 0};
        SimState start = start(rules, 20, 6);
        Profile profile = goal();
        Plan plan = plan(rules, profile, start, 1_000);
        for (Plan.Step step : plan.steps) assertFalse(step.action, step.action.startsWith("act:"));
        assertEquals(10, plan.summary.endRanks[0]);
        SimState evening = start(rules, 26, 3);
        Heuristics h = new Heuristics(new Model(rules, profile, null), evening, null);
        h.prepare(evening);
        assertFalse("that daytime's points were already included in the observed evening",
                Heuristics.vetoed(h.score(evening, Kernel.encode(Action.activity(0)))));
    }

    @Test
    public void missingOrTooLateFreeOptionsRestorePaidCatchupBeforeTheUnlock() {
        Rules rules = lateGate();
        Rules.Activity late = free(2, "too_late", 20, 50);
        Rules.Activity excluded = free(3, "excluded", 20, 43);
        Rules.Activity locked = free(4, "locked", 20, 44);
        rules.activities = new Rules.Activity[]{rules.activities[1], rules.activities[0], late, excluded, locked};
        // Preserve each stable idx after swapping the cheap ordinary study out of the plan.
        rules.activities[0].idx = 0;
        rules.activities[1].idx = 1;
        rules.days[44].eveLock = "story";
        Profile profile = goal();
        profile.excludedActivities = new String[]{"ordinary", "excluded"};
        SimState start = start(rules, 41, 10);
        Plan plan = plan(rules, profile, start, 1_000);
        assertEquals("act:paid", plan.steps.get(0).action);
        assertEquals(10, plan.summary.endRanks[0]);
        assertTrue("unavailable freebies must never be promised", plan.steps.stream().noneMatch(s -> s.action.equals("act:excluded") || s.action.equals("act:locked")));
    }

    @Test
    public void competingStatsCannotBothClaimTheSameFreeEvenings() {
        Rules rules = lateGate();
        rules.links[0].unlock.statLv.put(1, 6);
        rules.links[0].avail.clear();
        rules.links[0].avail.set(45);
        Profile profile = goal();
        profile.target[0] = 1;
        Rules.Activity paidCharm = RulesFixtures.activity(2, "paid_charm", "魅力练习", Rules.ActivityKind.STAT, false, true, 100, 1, 4);
        Rules.Activity academic = free(3, "free_academic", 4, 43, 44);
        Rules.Activity charm = free(4, "free_charm", 0, 43, 44);
        charm.gain[1] = 4;
        rules.activities = new Rules.Activity[]{rules.activities[0], rules.activities[1], paidCharm, academic, charm};
        profile.excludedActivities = new String[]{"ordinary"};
        SimState start = start(rules, 41, 8);
        start.statPts[1] -= 8;
        Plan plan = plan(rules, profile, start, 1_000);
        assertTrue("two evenings provide eight total free points, not sixteen", plan.steps.stream().anyMatch(s -> s.action.equals("act:paid") || s.action.equals("act:paid_charm")));
        assertEquals("both gates must be genuinely met for the only opening", 1, plan.summary.endRanks[0]);
    }

    @Test
    public void unevenFreeGainsCannotHideTheOnlyPaidCatchupNight() {
        Rules rules = lateGate();
        rules.links[0].unlock.earliestDay = 46;
        rules.links[0].unlock.statLv.put(1, 6);
        rules.links[0].avail.clear();
        rules.links[0].avail.set(46);
        Rules.Activity paidA = RulesFixtures.activity(0, "paid_a", "学力练习", Rules.ActivityKind.STAT, false, true, 100, 0, 4);
        Rules.Activity paidC = RulesFixtures.activity(1, "paid_c", "魅力练习", Rules.ActivityKind.STAT, false, true, 100, 1, 4);
        paidA.fixedDays = paidC.fixedDays = new int[]{40};
        Rules.Activity bestA = free(2, "shared_a", 4, 41);
        Rules.Activity bestC = free(3, "shared_c", 0, 41);
        bestC.gain[1] = 4;
        Rules.Activity smallA = free(4, "small_a", 1, 42, 43);
        Rules.Activity smallC = free(5, "small_c", 0, 44, 45);
        smallC.gain[1] = 1;
        rules.activities = new Rules.Activity[]{paidA, paidC, bestA, bestC, smallA, smallC};
        Profile profile = goal();
        profile.target[0] = 1;
        SimState start = start(rules, 40, 4);
        start.statPts[1] -= 4;
        Plan plan = plan(rules, profile, start, 1_000);
        assertTrue("the shared +4 night cannot satisfy both gates; today's paid opportunity is necessary",
                plan.steps.get(0).action.equals("act:paid_a") || plan.steps.get(0).action.equals("act:paid_c"));
        assertEquals("choose one real free activity per date and actually open the link", 1, plan.summary.endRanks[0]);
    }

    @Test
    public void aSelectedEpisodesOnlyNightCannotAlsoBePromisedAsFreeTraining() {
        Rules rules = lateGate();
        rules.links[0].unlock.earliestDay = 44;
        Rules.Activity paid = RulesFixtures.activity(0, "paid", "学力练习", Rules.ActivityKind.STAT, false, true, 100, 0, 4);
        paid.fixedDays = new int[]{42};
        rules.activities = new Rules.Activity[]{paid, free(1, "conflicting_free", 4, 43)};
        Rules.Episode episode = RulesFixtures.track(rules, 0, "only_episode", "同伴", true, 1);
        episode.dayMask[43] = 1;
        rules.episodes = new Rules.Episode[]{episode};
        Profile profile = goal();
        profile.episodeTracks = new String[]{"only_episode"};
        SimState start = start(rules, 42, 4);
        Plan plan = plan(rules, profile, start, 1_000);
        assertEquals("act:paid", plan.steps.get(0).action);
        assertEquals("episode:only_episode", plan.at(Rules.slot(43, true)).action);
        assertEquals(10, plan.summary.endRanks[0]);
    }

    @Test
    public void tonightCannotAlsoBeCountedTowardFutureFreeCapacity() {
        Rules rules = lateGate();
        rules.links[0].avail.clear();
        rules.links[0].avail.set(45);
        Rules.Activity paid = RulesFixtures.activity(0, "paid", "学力练习", Rules.ActivityKind.STAT, false, true, 100, 0, 4);
        paid.fixedDays = new int[]{42};
        rules.activities = new Rules.Activity[]{paid, free(1, "later_free", 2, 43, 44)};
        Rules.Block block = new Rules.Block();
        block.idx = 0;
        block.id = "only_block";
        block.zh = "测试区域";
        block.floorsZh = "1–10F";
        block.fromDay = 42;
        block.toDay = 44;
        block.top = 10;
        rules.blocks = new Rules.Block[]{block};
        for (int d = 42; d <= 44; d++) {
            rules.days[d].block = 0;
            rules.days[d].tartarus = true;
        }
        Profile profile = goal();
        profile.target[0] = 1;
        profile.towerVisitsPerBlock = profile.towerCapPerBlock = 1;
        SimState start = start(rules, 42, 4);
        Plan plan = plan(rules, profile, start, 1_000);
        assertEquals("tonight paid + one later required tower leaves only one future free session",
                "act:paid", plan.steps.get(0).action);
        assertEquals(1, plan.summary.endRanks[0]);
        assertTrue(plan.steps.stream().anyMatch(step -> step.action.equals("tower:0")));
    }

    @Test
    public void septemberFourteenthLeavesAcademicsForOctoberStudyBeforeNovemberEmpress() throws Exception {
        Rules rules = RulesLoader.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/p3r/p3r_rules.json")), StandardCharsets.UTF_8));
        Profile profile = new Profile();
        profile.otherLinks = false;
        profile.charmBonus = false;
        profile.towerVisitsPerBlock = profile.towerCapPerBlock = 0;
        int empress = rules.linkIndex("empress");
        profile.tier[empress] = Profile.Tier.MUST;
        SimState start = new SimState(rules.blocks.length, rules.activities.length);
        start.slot = RulesFixtures.slot(9, 14, true);
        start.cash = 100_000;
        for (int s = 0; s < 3; s++) start.statPts[s] = rules.stats[s].thresholds[6];
        start.statPts[0] -= 20;
        Plan plan = plan(rules, profile, start, 1_000);
        SimState end = start.copy();
        int firstMax = -1;
        int study = 0;
        int paid = 0;
        for (Plan.Step step : plan.steps) {
            Action action = Action.parse(rules, step.action);
            if (action.type == Action.Type.ACTIVITY) {
                Rules.Activity activity = rules.activities[action.target];
                if (activity.id.startsWith("group_study_10_")) study++;
                if (activity.cost > 0) {
                    paid++;
                    System.out.println("SEP14 paid: " + rules.day(step.slot >> 1).md + " " + step.action);
                }
            }
            end = Engine.apply(rules, profile, end, action).state;
            if (firstMax < 0 && end.statPts[0] >= rules.stats[0].thresholds[6]) firstMax = step.slot;
        }
        System.out.println("SEP14 stats: firstMAX=" + (firstMax < 0 ? "never" : rules.day(firstMax >> 1).md)
                + ", October study=" + study + ", paid=" + paid + ", Empress=" + end.rank[empress]);
        assertTrue("do not cap before the October free study window", firstMax >= RulesFixtures.slot(10, 8, true));
        assertTrue("October study actually happens; future capacity is not a stat grant", study > 0);
        assertEquals(0, paid);
        assertEquals(10, end.rank[empress]);
        assertEquals("observations are never changed", rules.stats[0].thresholds[6] - 20, start.statPts[0]);
    }

    private static Rules lateGate() {
        Rules rules = RulesFixtures.small();
        rules.blocks = new Rules.Block[0];
        rules.rescues = new Rules.Rescue[0];
        for (Rules.Day day : rules.days) if (day != null) {
            day.dayLock = day.eveLock = null;
            day.tartarus = false;
            day.block = -1;
        }
        Rules.Link link = RulesFixtures.link(0, "late_gate", 3, "女皇", "桐条美鹤", Rules.LinkKind.MANUAL, false, "学校");
        link.unlock.earliestDay = 45;
        link.unlock.statLv.put(0, 6);
        link.unlock.steps.add(RulesFixtures.step("late_gate.join", "对话", false, Rules.StepDays.LINK_AVAIL, 0, 0, true));
        link.avail.set(45, rules.lastDay + 1);
        rules.links = new Rules.Link[]{link};
        rules.activities = new Rules.Activity[]{
                RulesFixtures.activity(0, "ordinary", "宿舍自习", Rules.ActivityKind.STAT, false, true, 0, 0, 2),
                RulesFixtures.activity(1, "paid", "收费培训", Rules.ActivityKind.STAT, false, true, 100, 0, 4)};
        return rules;
    }

    private static Rules.Activity free(int index, String id, int gain, int... days) {
        Rules.Activity a = RulesFixtures.activity(index, id, id, Rules.ActivityKind.STAT, false, true, 0, 0, gain);
        a.fixedDays = days;
        return a;
    }

    private static Profile goal() {
        Profile profile = new Profile();
        profile.otherLinks = false;
        profile.charmBonus = false;
        profile.towerVisitsPerBlock = profile.towerCapPerBlock = 0;
        profile.tier[0] = Profile.Tier.MUST;
        return profile;
    }

    private static SimState start(Rules rules, int day, int gap) {
        SimState state = new SimState(rules.blocks.length, rules.activities.length);
        state.slot = Rules.slot(day, true);
        state.cash = 100_000;
        for (int s = 0; s < 3; s++) state.statPts[s] = rules.stats[s].thresholds[6];
        state.statPts[0] -= gap;
        return state;
    }

    private static Plan plan(Rules rules, Profile profile, SimState start, long budget) {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = budget;
        Plan plan = Engine.plan(rules, profile, start, null, options);
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        return plan;
    }
}
