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
 * limit day itself (REPORT, tartarus "rescue deadline"). So the validator accepts exactly the visits inside
 * the window and from the day after the limit the link is lost. Nobody has checked the limit evening in the
 * game, though, so the planner keeps a one-day margin (DESIGN §10.3, {@link RescueMargin}): it rescues by
 * the day before the limit whenever an earlier evening is open, and uses the limit evening only when it is
 * the last one left, saying so.
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
            // The only evening left: the plan uses it, and says the evidence has it in time but nobody checked it.
            assertEquals("今天是救出" + r.zh + "的最后期限：" + Labels.LIMIT_EVENING_CAVEAT, evening.whyZh);
            assertTrue(plan.summary.notesZh.toString(), plan.summary.notesZh.contains(rules.links[r.link].arcanaZh
                    + "：只剩期限当天（" + date + "）的晚上能救出" + r.zh + "，" + Labels.LIMIT_EVENING_CAVEAT));
            String broken = RescueMargin.broken(rules, profile, onLimit, plan);
            assertTrue(broken, broken == null);
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

    /**
     * The one-day margin (DESIGN §10.3) where it bites: plans that start in the last four days before a limit,
     * in the daytime and in the evening, with the block's wanted Tartarus visits still to make or already made
     * (then the rescue is the only visit left and the evening before the limit has other uses), under both
     * 安排风格, with the rescue's own link as a 「必须」 goal and with 「必须」 goals that want the evenings (恶魔
     * and 塔 meet only in the evening). Every one rescues on or before toDay − 1 and validates; none mentions
     * an unchecked limit evening. Before the margin, 6 of these 128 plans rescued on the limit evening (文吉
     * from 10/1 and 10/2 evening, 舞子 from 11/1, all with the wanted visits made).
     */
    @Test
    public void plansThatStartBeforeTheLimitDayNeverWaitForTheLimitEvening() {
        Profile max = new Profile();
        max.style = Profile.Style.MAX;
        Profile evenings = new Profile();
        evenings.tier[rules.link("devil").idx] = Profile.Tier.MUST;
        evenings.tier[rules.link("tower").idx] = Profile.Tier.MUST;
        evenings.order[rules.link("tower").idx] = 1;
        int checked = 0;
        StringBuilder days = new StringBuilder();
        for (Rules.Rescue r : rules.rescues) {
            Profile own = new Profile();
            own.tier[r.link] = Profile.Tier.MUST;
            Profile[] profiles = {new Profile(), max, own, evenings};
            for (int day = r.toDay - 4; day < r.toDay; day++) {
                for (int variant = 0; variant < 4; variant++) {
                    boolean evening = (variant & 1) == 1;
                    for (Profile profile : profiles) {
                        SimState start = missingAt(r, day);
                        start.slot = Rules.slot(day, evening);
                        // Half the starts have the block's wanted visits done: the rescue is the only visit left.
                        int block = rules.day(day).block;
                        if (variant >= 2) start.towerVisits[block] = profile.visitsFor(rules.blocks[block].id);
                        Plan plan = plan(profile, start);
                        String broken = RescueMargin.broken(rules, profile, start, plan);
                        String where = r.id + " from " + rules.day(day).md + (evening ? " evening" : "") + (variant >= 2 ? ", visits done" : "");
                        assertTrue(where + ": " + broken, broken == null);
                        for (RescueMargin.Landing l : RescueMargin.landings(rules, profile, start, plan)) {
                            if (l.rescue != r) continue;
                            assertTrue(where + ": " + l, l.earlier && l.matters && l.slot >= 0 && Rules.dayOf(l.slot) <= r.toDay - 1);
                            days.append(' ').append(rules.day(Rules.dayOf(l.slot)).md);
                        }
                        for (String note : plan.summary.notesZh) assertFalse(where + ": " + note, note.contains(Labels.LIMIT_EVENING_CAVEAT));
                        checked++;
                    }
                }
            }
        }
        System.out.println("rescue margin: " + checked + " late starts, rescued on" + days);
        assertEquals(rules.rescues.length * 4 * 4 * 4, checked);
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
