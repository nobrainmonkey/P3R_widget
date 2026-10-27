package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.*;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class AvailableActionsTest {
    private static Rules rules;

    @BeforeClass public static void load() throws Exception {
        rules = RulesLoader.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/p3r/p3r_rules.json")), StandardCharsets.UTF_8));
    }

    private static int day(String md) {
        for (Rules.Day d : rules.days) if (d != null && md.equals(d.md)) return d.d;
        throw new AssertionError(md);
    }

    private static SimState state(String md) {
        SimState state = new SimState(rules.blocks.length, rules.activities.length);
        state.episodes = new int[rules.episodes.length];
        state.slot = Rules.slot(day(md), false);
        state.cash = 100_000;
        state.cashExact = true;
        for (int i = 0; i < rules.stats.length; i++) state.statPts[i] = rules.stats[i].thresholds[6];
        return state;
    }

    private static AvailableActions.Entry find(List<AvailableActions.Entry> entries, String key) {
        for (AvailableActions.Entry entry : entries) if (key.equals(entry.key)) return entry;
        return null;
    }

    @Test public void arcadeCalendarShowsTheActualWeekdayGameAndRawStatEvenAtMax() {
        AvailableActions.Calendar calendar = AvailableActions.build(rules, new Profile(), state("10-25"));
        assertTrue(find(calendar.day(day("10-26")).evening, "act:arcade_youth").detail.contains("魅力 +4"));
        assertTrue(find(calendar.day(day("10-27")).evening, "act:arcade_deceased").detail.contains("勇气 +4"));
        assertTrue(find(calendar.day(day("10-28")).evening, "act:arcade_answer").detail.contains("学力 +4"));
        assertNull(find(calendar.day(day("10-26")).evening, "act:arcade_answer"));
        assertTrue(find(calendar.day(day("10-26")).daytime, "act:arcade_youth").detail.contains("魅力 +4"));
        assertTrue(find(calendar.day(day("10-27")).daytime, "act:arcade_deceased").detail.contains("勇气 +4"));
        assertTrue(find(calendar.day(day("10-28")).daytime, "act:arcade_answer").detail.contains("学力 +4"));
        assertNull(find(calendar.day(day("10-26")).daytime, "act:arcade_answer"));
        assertNull(find(calendar.day(day("10-25")).daytime, "act:arcade_youth"));
    }

    @Test public void personaArcadeUsesAllSevenWeekdaysInBothPeriodsIncludingThreeSundayGames() {
        AvailableActions.Calendar calendar = AvailableActions.build(rules, new Profile(), state("10-26"));
        String[][] expected = {
            {"strength"}, {"agility"}, {"magic"}, {"strength"}, {"agility"}, {"magic"},
            {"strength", "magic", "agility"},
        };
        for (int offset = 0; offset < expected.length; offset++) {
            AvailableActions.Day day = calendar.day(day("10-26") + offset);
            for (List<AvailableActions.Entry> period : Arrays.asList(day.daytime, day.evening)) {
                int count = 0;
                for (AvailableActions.Entry entry : period) if (entry.key.startsWith("info:arcade_persona_")) count++;
                assertEquals("weekday " + (offset + 1), expected[offset].length, count);
                for (String stat : expected[offset]) {
                    AvailableActions.Entry entry = find(period, "info:arcade_persona_" + stat);
                    assertNotNull(entry);
                    assertEquals("电玩城", entry.categoryZh);
                    assertTrue(entry.title.contains("+3"));
                    assertTrue(entry.detail.contains("2,000 日元"));
                    assertTrue(entry.detail.contains("当前装备的人格面具"));
                    assertTrue(entry.isInformational);
                    assertNull("training is a tip, not a recordable planner action", entry.type);
                    assertNull(Action.parse(rules, entry.key));
                }
            }
        }
    }

    @Test public void personaArcadeKeepsCashConditionsAndDoesNotChangeProgressOrOpenLockedSlots() throws Exception {
        SimState state = state("10-26");
        state.cash = 1_999;
        String before = state.toJson().toString();
        AvailableActions.Calendar calendar = AvailableActions.build(rules, new Profile(), state);
        assertTrue(find(calendar.day(day("10-26")).evening, "info:arcade_persona_strength")
            .detail.contains("钱不够"));
        AvailableActions.Day trip = calendar.day(day("11-18"));
        assertNotNull(trip.daytimeLockZh);
        assertNotNull(trip.eveningLockZh);
        assertTrue(trip.daytime.isEmpty());
        assertTrue(trip.evening.isEmpty());
        assertEquals(before, state.toJson().toString());

        state.cashExact = false;
        AvailableActions.Entry lowerBound = find(AvailableActions.build(rules, new Profile(), state)
            .day(day("10-26")).daytime, "info:arcade_persona_strength");
        assertFalse(lowerBound.detail.contains("钱不够"));
    }

    @Test public void ryojiCalendarUsesTheActualSceneNumbersAndMeetingLocations() {
        SimState state = state("11-12");
        Action ryoji = Action.parse(rules, "episode:ryoji");
        assertNotNull(ryoji);
        AvailableActions.Entry first = find(AvailableActions.build(rules, new Profile(), state)
            .day(day("11-12")).daytime, "episode:ryoji");
        assertNotNull(first);
        assertEquals("望月绫时 · 第 2 话", first.title);
        assertTrue(first.detail, first.detail.contains("教室"));

        state.episodes[ryoji.target] = 1;
        AvailableActions.Entry second = find(AvailableActions.build(rules, new Profile(), state)
            .day(day("12-01")).daytime, "episode:ryoji");
        assertNotNull(second);
        assertEquals("望月绫时 · 第 4 话", second.title);
        assertTrue(second.detail, second.detail.contains("音乐"));
        assertFalse(second.isInformational);

        state.episodes[ryoji.target] = 2;
        assertNull(find(AvailableActions.build(rules, new Profile(), state)
            .day(day("12-01")).daytime, "episode:ryoji"));
    }

    @Test public void openVenuesIgnorePlanningExclusionsAndShowPriceWhenCashIsShort() {
        SimState state = state("10-26");
        state.cash = 0;
        Profile profile = new Profile();
        profile.excludedActivities = new String[]{"coffee_chagall", "arcade_youth", "job_chagall"};
        profile.cashBuffer = 100_000;
        profile.otherLinks = false;
        AvailableActions.Day day = AvailableActions.build(rules, profile, state).day(day("10-26"));
        AvailableActions.Entry coffee = find(day.evening, "act:coffee_chagall");
        assertNotNull(coffee);
        assertEquals("店铺", coffee.categoryZh);
        assertTrue(coffee.detail.contains("500 日元"));
        assertTrue(coffee.detail.contains("钱不够"));
        assertTrue(find(day.daytime, "act:arcade_youth").detail.contains("钱不够"));
        AvailableActions.Entry job = find(day.evening, "act:job_chagall");
        assertEquals("打工", job.categoryZh);
        assertTrue(job.detail.contains("报酬 2,500 日元"));
        assertNull(find(day.daytime, "act:job_cinema"));
    }

    @Test public void dormRosterUsesExactDatesWithoutNeedingAChosenTraitGoal() {
        SimState state = state("10-25");
        int dvd = rules.activityIndex("dorm_yukari_dvd");
        state.used[dvd] = 2;
        Profile profile = new Profile();
        profile.otherLinks = false;
        AvailableActions.Calendar calendar = AvailableActions.build(rules, profile, state);
        AvailableActions.Entry entry = find(calendar.day(209).evening, "act:dorm_yukari_dvd");
        assertNotNull(entry);
        assertEquals("宿舍活动", entry.categoryZh);
        assertTrue(entry.detail.contains("已做 2/3 次"));
        assertNull(find(calendar.day(210).evening, "act:dorm_yukari_dvd"));
        for (Rules.Activity activity : rules.activities) {
            if (!activity.requiresPlantedCrop) continue;
            int date = activity.fixedDays[activity.fixedDays.length - 1];
            AvailableActions.Entry gardening = find(calendar.day(date).evening, "act:" + activity.id);
            if (gardening != null) {
                assertTrue(gardening.detail.contains("要先种好菜"));
                return;
            }
        }
        fail("a real gardening opportunity should be listed");
    }

    @Test public void schoolSocialMeetingsUseTheRecordedRankAndCalendarRatherThanGoals() {
        SimState state = state("10-25");
        int emperor = rules.linkIndex("emperor");
        state.rank[emperor] = 4;
        Profile profile = new Profile();
        profile.otherLinks = false;
        AvailableActions.Calendar calendar = AvailableActions.build(rules, profile, state);
        int available = 0;
        for (int date = day("10-25"); date <= rules.lastDay; date++) {
            AvailableActions.Entry meeting = find(calendar.day(date).daytime, "meet:emperor");
            if (meeting != null) {
                assertTrue(rules.links[emperor].avail.get(date));
                assertEquals(emperor, meeting.link);
                assertEquals(Action.Type.MEET, meeting.type);
                available++;
            }
        }
        assertTrue(available > 5);
        state.rank[emperor] = 10;
        AvailableActions.Calendar completed = AvailableActions.build(rules, profile, state);
        for (int date = day("10-25"); date <= rules.lastDay; date++) {
            assertNull(find(completed.day(date).daytime, "meet:emperor"));
        }
    }

    @Test public void lockedStoryPeriodsAndAcceptedChristmasHaveNoListedActions() {
        SimState state = state("10-25");
        Profile profile = new Profile();
        profile.christmasLink = "lovers";
        AvailableActions.Calendar calendar = AvailableActions.build(rules, profile, state);
        AvailableActions.Day operation = calendar.day(day("11-03"));
        assertNotNull(operation.daytimeLockZh);
        assertNotNull(operation.eveningLockZh);
        assertTrue(operation.daytime.isEmpty());
        assertTrue(operation.evening.isEmpty());
        AvailableActions.Day christmas = calendar.day(day("12-24"));
        assertEquals("平安夜约会", christmas.daytimeLockZh);
        assertEquals("平安夜约会", christmas.eveningLockZh);
        assertTrue(christmas.daytime.isEmpty());
        assertTrue(christmas.evening.isEmpty());
    }

    @Test public void menuUnlockRequiresItsRecordedVisitAndStatLevelAndDoesNotDuplicateMeals() {
        SimState state = state("10-26");
        int menu = rules.activityIndex("meal_wakatsu_menu");
        AvailableActions.Day before = AvailableActions.build(rules, new Profile(), state).day(day("10-26"));
        assertNotNull(find(before.evening, "act:meal_wakatsu_menu"));
        assertNull(find(before.evening, "act:meal_wakatsu"));
        assertNull(find(before.evening, "act:meal_wakatsu_special"));
        state.used[menu] = 1;
        AvailableActions.Day after = AvailableActions.build(rules, new Profile(), state).day(day("10-26"));
        assertNull(find(after.evening, "act:meal_wakatsu_menu"));
        assertNotNull(find(after.evening, "act:meal_wakatsu"));
        assertTrue(find(after.evening, "act:meal_wakatsu_special").detail.contains("学力 +4"));
        state.statPts[1] = 0;
        assertNull(find(AvailableActions.build(rules, new Profile(), state).day(day("10-26")).evening,
                "act:meal_wakatsu_special"));
    }

    @Test public void freeGroupStudyDoesNotDisappearAtMaxAcademicsOrSpillIntoAnotherDate() {
        AvailableActions.Calendar calendar = AvailableActions.build(rules, new Profile(), state("05-15"));
        AvailableActions.Entry study = find(calendar.day(day("05-17")).evening, "act:group_study_05_17");
        assertNotNull(study);
        assertEquals("集体学习", study.categoryZh);
        assertTrue(study.detail.contains("学力 +5"));
        assertTrue(study.detail.contains("免费"));
        assertNull(find(calendar.day(day("05-16")).evening, "act:group_study_05_17"));
    }

    @Test public void datedUnlockNotesAndActualUnlockActionsKeepTheirRequirements() {
        SimState state = state("10-25");
        int fortune = rules.linkIndex("fortune");
        state.rank[fortune] = 1;
        AvailableActions.Calendar calendar = AvailableActions.build(rules, new Profile(), state);
        assertTrue(String.join(" ", calendar.day(day("01-08")).unlocks).contains("永劫"));
        AvailableActions.Entry priestess = null;
        for (int date = day("10-25"); date <= rules.lastDay && priestess == null; date++) {
            priestess = find(calendar.day(date).daytime, "unlock:priestess:0");
        }
        assertNotNull(priestess);
        assertEquals("解锁", priestess.categoryZh);
        state.statPts[2] = 0;
        AvailableActions.Calendar blocked = AvailableActions.build(rules, new Profile(), state);
        for (int date = day("10-25"); date <= rules.lastDay; date++) {
            assertNull(find(blocked.day(date).daytime, "unlock:priestess:0"));
        }
    }

    @Test public void buildIsReadOnlyAndCollectionsCannotBeChangedByScreens() throws Exception {
        SimState state = state("10-25");
        state.rank[rules.linkIndex("emperor")] = 2;
        String before = state.toJson().toString();
        long start = System.nanoTime();
        AvailableActions.Calendar calendar = AvailableActions.build(rules, new Profile(), state);
        for (int date = rules.firstDay; date <= rules.lastDay; date++) calendar.day(date);
        System.out.println("Availability full calendar: " + (System.nanoTime() - start) / 1_000_000.0 + " ms");
        assertEquals(before, state.toJson().toString());
        assertNull(calendar.day(-1));
        assertNull(calendar.day(rules.lastDay + 1));
        try {
            calendar.day(day("10-26")).evening.clear();
            fail("screen must not mutate the shared calendar");
        } catch (UnsupportedOperationException expected) { }
        assertNull(AvailableActions.build(null, null, null).day(0));
        assertNull(AvailableActions.build(new Rules(), new Profile(), new SimState()).day(0));
    }

    @Test public void lazyDaysKeepTheCapturedStateAndProfileAndOnlyFlagKnownInsufficientCash() {
        SimState state = state("10-26");
        state.cash = 500;
        state.cashExact = false;
        Profile profile = new Profile();
        AvailableActions.Calendar calendar = AvailableActions.build(rules, profile, state);
        state.cashExact = true;
        state.cash = 0;
        state.statPts = new int[3];
        profile.christmasLink = "lovers";
        AvailableActions.Entry arcade = find(calendar.day(day("10-26")).evening, "act:arcade_youth");
        assertNotNull(arcade);
        assertFalse("a recorded cash lower bound cannot prove insufficient money", arcade.detail.contains("钱不够"));
        assertNull("profile edits must not change the captured calendar", calendar.day(day("12-24")).eveningLockZh);
        AvailableActions.Calendar fresh = AvailableActions.build(rules, profile, state);
        assertTrue(find(fresh.day(day("10-26")).evening, "act:arcade_youth").detail.contains("钱不够"));
        assertEquals("平安夜约会", fresh.day(day("12-24")).eveningLockZh);
        assertSame(calendar.day(day("10-26")), calendar.day(day("10-26")));
    }
}
