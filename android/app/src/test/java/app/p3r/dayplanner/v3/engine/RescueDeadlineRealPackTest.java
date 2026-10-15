package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Missing-person deadlines on the shipped pack. Both ends of a window count: the game's table gives each
 * person a start and a limit day (DisappearDataAsset), and aqiu's tested Reload walkthrough rescues on the
 * limit day itself (REPORT, tartarus "rescue deadline"). So the planner may rescue on the limit evening, the
 * validator accepts exactly the visits inside the window, and from the day after the limit the link is lost.
 */
public class RescueDeadlineRealPackTest {
    private static final String CLAIM = PlanValidator.RESCUE_ASSUMPTION;
    private static Rules rules;

    @BeforeClass
    public static void loadPack() throws Exception {
        for (String c : new String[] {"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            File f = new File(c);
            if (f.isFile()) {
                rules = RulesLoader.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                return;
            }
        }
        Assume.assumeTrue("rule pack not built yet", false);
    }

    @Test
    public void everyLimitEveningIsTheLastTartarusEveningOfItsWindow() {
        assertEquals(2, rules.rescues.length);
        for (Rules.Rescue r : rules.rescues) {
            assertTrue(r.id, rules.day(r.toDay).tartarus && rules.lockOf(Rules.slot(r.toDay, true)) == null);
            assertFalse(r.id + " the night after the limit", rules.day(r.toDay + 1).tartarus);
        }
        for (Rules.RescueGroup g : rules.reminders.rescueGroups) {
            assertTrue(g.id, rules.day(g.toDay).tartarus);
        }
    }

    @Test
    public void aNewGamePlanRescuesEachLinkInsideItsWindow() {
        Profile profile = new Profile();
        SimState start = newGame(dayOf(4, 21));
        Plan plan = plan(profile, start);
        for (Rules.Rescue r : rules.rescues) {
            int claims = 0;
            for (Plan.Step s : plan.steps) {
                if (!s.assumptions.contains(CLAIM) || !s.subtitleZh.endsWith("救出" + r.zh)) continue;
                claims++;
                int day = Rules.dayOf(s.slot);
                System.out.println(r.id + " rescued on " + rules.day(day).md);
                assertTrue(r.id + " rescued on day " + day, day >= r.fromDay && day <= r.toDay);
                assertEquals("tower", s.kind);
            }
            int maxSlot = plan.summary.maxSlot[r.link];
            boolean matters = maxSlot < 0 || maxSlot >= Rules.slot(r.fromDay, false);
            System.out.println(r.id + ": " + claims + " rescue step(s) in " + r.fromDay + ".." + r.toDay
                    + (matters ? "" : " (link MAX before the window)"));
            if (matters) assertEquals(r.id + " is rescued once, inside its window", 1, claims);
            else assertTrue(r.id + " is rescued at most once", claims <= 1);
        }
        for (Plan.Step s : plan.steps) {
            if (!s.assumptions.contains(CLAIM)) continue;
            boolean inside = false;
            for (Rules.Rescue r : rules.rescues) inside |= Rules.dayOf(s.slot) >= r.fromDay && Rules.dayOf(s.slot) <= r.toDay;
            assertTrue("claim outside every window at slot " + s.slot, inside);
        }
    }

    @Test
    public void theLimitEveningStillRescuesAndTheNextDayIsTooLate() throws JSONException {
        Profile profile = new Profile();
        for (Rules.Rescue r : rules.rescues) {
            // On the limit day, with nothing recorded: the plan goes to Tartarus that evening and the link goes on.
            SimState onLimit = missingAt(r, r.toDay);
            Plan plan = plan(profile, onLimit);
            Plan.Step evening = plan.at(Rules.slot(r.toDay, true));
            assertNotNull(evening);
            assertEquals(r.id, "tower", evening.kind);
            assertTrue(r.id, evening.assumptions.contains(CLAIM));
            assertTrue(evening.subtitleZh, evening.subtitleZh.endsWith("救出" + r.zh));
            String date = rules.day(r.toDay).month() + "月" + rules.day(r.toDay).dayOfMonth() + "日";
            assertTrue(evening.whyZh, evening.whyZh.contains("最晚要在" + date + "晚上救出"));
            assertTrue(r.id + " goes on after the rescue", plan.summary.endRanks[r.link] > 3);

            // The day after the limit: lost, no step rescues this person, and a claimed rescue is rejected.
            SimState after = missingAt(r, r.toDay + 1);
            Plan late = plan(profile, after);
            for (Plan.Step s : late.steps) {
                assertFalse(s.action + " at slot " + s.slot, "tower".equals(s.kind) && s.subtitleZh.endsWith("救出" + r.zh));
            }
            assertEquals(r.id + " is lost", 3, late.summary.endRanks[r.link]);
            int night = nextTartarusEvening(r.toDay + 1);
            Plan bad = Plan.fromJson(new JSONObject(late.toJson().toString()));
            Plan.Step step = bad.at(night);
            step.action = "tower:" + rules.day(Rules.dayOf(night)).block;
            step.kind = "tower";
            step.side.clear();
            step.predicted.cashDelta = 0;
            step.assumptions.add(CLAIM);
            assertRejected(profile, after, bad, "claims a rescue outside every open rescue window");

            // And a claim before the window opens is rejected too.
            SimState before = missingAt(r, r.fromDay - 3);
            Plan early = plan(profile, before);
            Plan wrong = Plan.fromJson(new JSONObject(early.toJson().toString()));
            int beforeNight = nextTartarusEvening(r.fromDay - 3);
            assertTrue(Rules.dayOf(beforeNight) < r.fromDay);
            Plan.Step pre = wrong.at(beforeNight);
            pre.action = "tower:" + rules.day(Rules.dayOf(beforeNight)).block;
            pre.kind = "tower";
            pre.side.clear();
            pre.predicted.cashDelta = 0;
            if (!pre.assumptions.contains(CLAIM)) pre.assumptions.add(CLAIM);
            assertRejected(profile, before, wrong, "claims a rescue outside every open rescue window");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Plan plan(Profile profile, SimState start) {
        Plan plan = Engine.plan(rules, profile, start, null, new Engine.Options());
        assertEquals(rules.lastSlot() - start.slot + 1, plan.steps.size());
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        return plan;
    }

    private static void assertRejected(Profile profile, SimState start, Plan bad, String fragment) {
        List<String> errors = Engine.validate(rules, profile, start, bad);
        boolean found = false;
        for (String e : errors) found |= e.contains(fragment);
        assertTrue(fragment + " not in " + errors, found);
    }

    /** Daytime of {@code day}: the rescue's link at RANK 3, some cash and stats, the person not rescued. */
    private static SimState missingAt(Rules.Rescue r, int day) {
        SimState s = newGame(day);
        s.cash = 30_000;
        for (int i = 0; i < rules.stats.length && i < 3; i++) s.statPts[i] = rules.stats[i].thresholds[4];
        s.rank[r.link] = 3;
        for (int i = 0; i < rules.rescues.length; i++) {
            if (rules.rescues[i] != r && rules.rescues[i].toDay < day) s.rescued |= 1L << i;
        }
        return s;
    }

    /** First evening from {@code day} on where a Tartarus visit is possible (open night, evening not locked). */
    private static int nextTartarusEvening(int day) {
        for (int d = day; d <= rules.lastDay; d++) {
            if (rules.day(d).tartarus && rules.day(d).block >= 0 && rules.lockOf(Rules.slot(d, true)) == null) {
                return Rules.slot(d, true);
            }
        }
        throw new AssertionError("no Tartarus evening from day " + day);
    }

    private static int dayOf(int month, int dom) {
        for (int d = rules.firstDay; d <= rules.lastDay; d++) {
            if (rules.days[d].month() == month && rules.days[d].dayOfMonth() == dom) return d;
        }
        throw new AssertionError(month + "/" + dom);
    }

    /** New-game state at the daytime of {@code day}; story links carry the ranks the story granted. */
    private static SimState newGame(int day) {
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = Rules.slot(day, false);
        s.cashExact = true;
        for (Rules.Link l : rules.links) {
            if (l.kind != Rules.LinkKind.STORY || l.storyRankDay == null) continue;
            for (int r = 1; r < l.storyRankDay.length && r <= 10; r++) {
                if (l.storyRankDay[r] >= 0 && l.storyRankDay[r] <= day) s.rank[l.idx] = r;
            }
        }
        return s;
    }
}
