package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real arrival waves must share a trip, including people with no Social Link. */
public class RescueBatchRealPackTest {
    private static Rules rules;

    @BeforeClass
    public static void load() throws Exception {
        rules = RulesLoader.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/p3r/p3r_rules.json")), StandardCharsets.UTF_8));
    }

    private static int day(int month, int date) {
        return (int) ChronoUnit.DAYS.between(LocalDate.of(2009, 4, 1), LocalDate.of(month == 1 ? 2010 : 2009, month, date));
    }

    private static SimState start(int d) {
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = Rules.slot(d, false);
        s.cash = 10_000;
        for (int i = 0; i < 3; i++) s.statPts[i] = rules.stats[i].thresholds[3];
        return s;
    }

    private static Plan plan(Profile p, SimState s, Engine.Options options) throws Exception {
        String observed = s.toJson().toString();
        Plan plan = Engine.plan(rules, p, s, null, options);
        assertEquals("forecast never edits the saved progress", observed, s.toJson().toString());
        List<String> errors = Engine.validate(rules, p, s, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        assertEquals(rules.lastSlot() - s.slot + 1, plan.steps.size());
        return plan;
    }

    private static Engine.Options baseOnly() {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 0;
        return options;
    }

    @Test
    public void everyWaveIsRescuedTogetherWithOneAllowedTripAndNoSocialGoals() throws Exception {
        Profile p = new Profile();
        p.otherLinks = false;
        p.towerVisitsPerBlock = 1;
        p.towerCapPerBlock = 1;
        Plan plan = plan(p, start(day(4, 21)), baseOnly());
        Map<Integer, List<Rules.RescueGroup>> waves = new LinkedHashMap<>();
        for (Rules.RescueGroup g : rules.reminders.rescueGroups) waves.computeIfAbsent(g.toDay, k -> new ArrayList<>()).add(g);
        for (List<Rules.RescueGroup> wave : waves.values()) {
            int allPresent = 0;
            int deadline = wave.get(0).toDay;
            for (Rules.RescueGroup g : wave) allPresent = Math.max(allPresent, g.fromDay);
            int trips = 0;
            for (Plan.Step step : plan.steps) {
                int d = Rules.dayOf(step.slot);
                if (!"tower".equals(step.kind) || d < allPresent || d >= deadline) continue;
                trips++;
                assertTrue("rescue step explains what to do", step.detailZh.contains("失踪"));
            }
            assertEquals("all arrivals before " + rules.day(deadline).md + " share one safe trip", 1, trips);
        }
        for (Plan.Reminder reminder : plan.reminders) {
            assertFalse(reminder.textZh, reminder.textZh.contains("这几天没安排去塔尔塔罗斯"));
        }
    }

    @Test
    public void septemberAndOctoberDoNotEnterAsSoonAsTheSocialLinkPersonDisappears() throws Exception {
        for (int[] dates : new int[][]{{9, 12, 22, 10, 3}, {10, 21, 30, 11, 2}}) {
            Profile p = new Profile();
            p.towerVisitsPerBlock = 1;
            p.towerCapPerBlock = 1;
            Plan plan = plan(p, start(day(dates[0], dates[1])), baseOnly());
            int together = day(dates[0], dates[2]);
            int limit = day(dates[3], dates[4]);
            int rescued = 0;
            for (Plan.Step step : plan.steps) {
                int d = Rules.dayOf(step.slot);
                if (!"tower".equals(step.kind) || d < day(dates[0], dates[1]) || d > limit) continue;
                assertTrue("wait for the later people instead of a second trip", d >= together);
                assertTrue("leave a day of safety", d < limit);
                rescued++;
            }
            assertEquals(1, rescued);
        }
    }

    @Test
    public void aRecordedVisitAfterAllJuneArrivalsDoesNotScheduleTheSameRescueAgain() throws Exception {
        Profile p = new Profile();
        p.towerVisitsPerBlock = 0;
        p.towerCapPerBlock = 2;
        SimState start = start(day(7, 1));
        int block = rules.day(day(7, 1)).block;
        start.towerVisits[block] = 1;
        Engine.Options options = baseOnly();
        options.lastTowerSlot = Rules.slot(day(6, 30), true);
        Plan plan = plan(p, start, options);
        for (Plan.Reminder reminder : plan.reminders) {
            if (reminder.slot <= Rules.slot(day(7, 6), true)) assertFalse(reminder.id, "rescue".equals(reminder.kind));
        }
        assertEquals(0, start.plannedRescueGroups);
        assertFalse(start.toJson().has("plannedRescueGroups"));
    }
}
