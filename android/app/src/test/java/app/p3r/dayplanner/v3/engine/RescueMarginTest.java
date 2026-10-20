package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The planner's one-day margin on missing-person rescues (DESIGN §10.3) on the small fixture, whose 北村文吉
 * is missing on days 40–45 (block 1, every evening enterable). The game window is unchanged: the limit
 * evening (day 45) still rescues him (PlanValidatorTest). But a plan never depends on it when an earlier
 * evening is open: the planner's deadline is day 44, and a rescue on day 45 counts against a plan like a
 * missed one. Only when day 45 is the last evening left does the plan use it, and then it says that the
 * evidence has it in time but nobody checked it in the game.
 */
public class RescueMarginTest {
    private static final int FROM = 40;
    private static final int LIMIT = 45;

    private static Engine.Options generous() {
        Engine.Options o = new Engine.Options();
        o.timeBudgetMillis = 60_000;
        return o;
    }

    /** 教皇 at RANK 2, some cash and stats, 北村文吉 missing and nothing recorded, at {@code slot}. */
    private static SimState missingAt(Rules rules, int slot) {
        SimState s = RulesFixtures.newGame(rules, slot);
        s.cash = 20_000;
        s.statPts[RulesFixtures.ACADEMICS] = rules.stats[RulesFixtures.ACADEMICS].thresholds[3];
        s.statPts[RulesFixtures.CHARM] = rules.stats[RulesFixtures.CHARM].thresholds[3];
        s.rank[RulesFixtures.S_HIEROPHANT] = 2;
        return s;
    }

    private static Plan plan(Rules rules, Profile profile, SimState start) {
        Plan p = Engine.plan(rules, profile, start, null, generous());
        List<String> errors = Engine.validate(rules, profile, start, p);
        assertTrue(errors.toString(), errors.isEmpty());
        return p;
    }

    /** The plan's step that rescues 北村文吉 (its first Tartarus visit in the window). */
    private static Plan.Step rescueStep(Plan p) {
        for (Plan.Step s : p.steps) {
            int day = s.slot >> 1;
            if ("tower".equals(s.kind) && day >= FROM && day <= LIMIT) {
                assertTrue(s.assumptions.contains(PlanValidator.RESCUE_ASSUMPTION));
                assertTrue(s.subtitleZh, s.subtitleZh.endsWith("救出北村文吉"));
                return s;
            }
        }
        throw new AssertionError("no rescue in " + FROM + ".." + LIMIT);
    }

    @Test
    public void everyStartWithAnEarlierEveningRescuesBeforeTheLimitDay() {
        Rules rules = RulesFixtures.small();
        List<Profile> profiles = new ArrayList<>();
        profiles.add(RulesFixtures.others());
        Profile max = RulesFixtures.others();
        max.style = Profile.Style.MAX;
        profiles.add(max);
        // A goal that wants every evening it can get (恶魔 meets on Saturday evenings, the limit day is one).
        Profile devil = RulesFixtures.must(RulesFixtures.S_DEVIL);
        profiles.add(devil);
        profiles.add(RulesFixtures.must(RulesFixtures.S_HIEROPHANT));
        int checked = 0;
        for (int day = FROM - 2; day < LIMIT; day++) {
            for (boolean evening : new boolean[]{false, true}) {
                for (Profile profile : profiles) {
                    SimState start = missingAt(rules, Rules.slot(day, evening));
                    Plan p = plan(rules, profile, start);
                    Plan.Step rescue = rescueStep(p);
                    String where = "start " + day + (evening ? " evening" : "") + ", " + profile.style + ", goals " + profile.hasAnyGoal();
                    assertTrue(where + ": rescued on day " + (rescue.slot >> 1), rescue.slot >> 1 <= LIMIT - 1);
                    assertNull(where, RescueMargin.broken(rules, profile, start, p));
                    // The step says the deadline and what is at stake, nothing about the plan or the limit evening.
                    assertEquals("北村文吉失踪了，期限 5月16日，救不出来教皇社群就没法继续了", rescue.whyZh);
                    for (String note : p.summary.notesZh) assertFalse(note, note.contains("的最后机会"));
                    checked++;
                }
            }
        }
        assertEquals(7 * 2 * profiles.size(), checked);
    }

    @Test
    public void onTheLimitDayTheLimitEveningIsUsedAndTheStepSaysItIsUnchecked() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        SimState start = missingAt(rules, Rules.slot(LIMIT, false));
        Plan p = plan(rules, profile, start);
        Plan.Step rescue = rescueStep(p);
        assertEquals(Rules.slot(LIMIT, true), rescue.slot);
        assertEquals("今天是救出北村文吉的最后期限，最晚今晚救出", rescue.whyZh);
        // The note can be read weeks ahead: a date, never 「今晚」.
        assertTrue(p.summary.notesZh.toString(), p.summary.notesZh.contains("教皇：5月16日晚上是救出北村文吉的最后机会"));
        assertTrue("教皇 goes on after the rescue", p.summary.endRanks[RulesFixtures.S_HIEROPHANT] > 2);
        assertNull(RescueMargin.broken(rules, profile, start, p));
    }

    @Test
    public void locksThatLeaveOnlyTheLimitEveningRescueOnItAndSaySo() {
        Rules rules = RulesFixtures.small();
        for (int d = FROM; d < LIMIT; d++) rules.days[d].eveLock = "story";
        Profile profile = RulesFixtures.others();
        SimState start = missingAt(rules, Rules.slot(30, false));
        assertFalse(RescueMargin.earlierEvening(rules, profile, start, rules.rescues[0]));
        Plan p = plan(rules, profile, start);
        Plan.Step rescue = rescueStep(p);
        assertEquals(Rules.slot(LIMIT, true), rescue.slot);
        assertTrue(rescue.whyZh, rescue.whyZh.contains(Labels.LIMIT_EVENING_CAVEAT));
        assertTrue(p.summary.notesZh.toString(), p.summary.notesZh.contains("教皇：5月16日晚上是救出北村文吉的最后机会"));
        for (String note : p.summary.notesZh) assertFalse(note, note.contains("今晚"));
        assertNull(RescueMargin.broken(rules, profile, start, p));
    }

    /**
     * The objective: with day 44 open, leaving the rescue to the limit evening scores like a missed planning
     * deadline (one {@link Heuristics#PENALTY}) although the link goes on, so the search never keeps it for
     * what that evening would otherwise do; the base policy makes day 44's evening a must.
     */
    @Test
    public void aRescueLeftToTheLimitEveningLosesToTheSameRescueADayEarlier() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        Model m = new Model(rules, profile, null);
        SimState start = Kernel.normalize(m, missingAt(rules, Rules.slot(LIMIT - 1, false)));
        Heuristics h = new Heuristics(m, start, null);
        assertEquals(LIMIT - 1, h.rescueBy[0]);
        int study = Kernel.encode(Action.activity(RulesFixtures.A_STUDY));
        int tower = Kernel.encode(Action.tower(1));
        int[] early = followBase(m, h, start, tower, study);
        int[] late = followBase(m, h, start, study, tower);
        double jEarly = objective(m, h, start, early);
        double jLate = objective(m, h, start, late);
        assertTrue(jEarly + " against " + jLate, jEarly > jLate + Heuristics.PENALTY / 2);

        SimState evening = start.copy();
        Kernel.apply(m, evening, Kernel.FREE, null);
        assertEquals(Rules.slot(LIMIT - 1, true), evening.slot);
        h.prepare(evening);
        assertEquals("day 44 is the last evening before the limit day: a must", Heuristics.MANDATORY, h.score(evening, tower), 0);

        // From the limit day itself there is no earlier evening: the deadline is the limit day, no penalty.
        SimState onLimit = Kernel.normalize(m, missingAt(rules, Rules.slot(LIMIT, false)));
        assertEquals(LIMIT, new Heuristics(m, onLimit, null).rescueBy[0]);
    }

    /**
     * Among complete plans the margin comes before the goals and the score: a plan that leaves a rescue to the
     * limit evening is not kept although it reaches a goal the other misses and scores higher, and the note of
     * the missed goal names no goal it gave way to (it gave way to the margin).
     */
    @Test
    public void aPlanThatLeavesARescueToTheLimitEveningIsNeverKept() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.must(RulesFixtures.S_EMPEROR, RulesFixtures.S_DEVIL);
        Model m = new Model(rules, profile, null);
        SimState start = missingAt(rules, Rules.slot(FROM, false));
        Planner.Pool pool = new Planner.Pool(m, start, null, new Engine.Options(), Long.MAX_VALUE, Planner.PORTFOLIO);
        Planner.Candidate late = candidate(new boolean[]{true, true}, new int[]{10, 10}, 9_000_000, 1);
        Planner.Candidate margin = candidate(new boolean[]{true, false}, new int[]{10, 6}, 1_000, 0);
        pool.add(late);
        pool.add(margin);
        assertTrue(pool.best() == margin);
        assertTrue(pool.choose() == margin);
        assertEquals(-1, pool.tradedFor(margin)[RulesFixtures.S_DEVIL]);
    }

    private static Planner.Candidate candidate(boolean[] reached, int[] endRank, double score, int late) {
        return new Planner.Candidate(null, null, null, new int[0], score, score, score, 0, reached, endRank, new int[22], 0, late);
    }

    /**
     * The codes of: the base policy from {@code start} until the evening of day 44, {@code eve44} there,
     * {@code eve45} on the limit evening, then the base policy to the end.
     */
    private static int[] followBase(Model m, Heuristics h, SimState start, int eve44, int eve45) {
        SimState st = start.copy();
        int[] buf = new int[Kernel.maxLegal(m)];
        List<Integer> codes = new ArrayList<>();
        while (st.slot <= m.t.lastSlot) {
            int code;
            if (st.slot == Rules.slot(LIMIT - 1, true)) code = eve44;
            else if (st.slot == Rules.slot(LIMIT, true)) code = eve45;
            else code = m.t.open(st.slot) ? h.choose(st, buf, Kernel.legal(m, st, buf)) : Kernel.FREE;
            codes.add(code);
            Kernel.apply(m, st, code, null);
        }
        int[] out = new int[codes.size()];
        for (int i = 0; i < out.length; i++) out[i] = codes.get(i);
        return out;
    }

    private static double objective(Model m, Heuristics h, SimState start, int[] codes) {
        SimState st = start.copy();
        int[] reached = h.initialReached(start);
        for (int code : codes) {
            int slot = st.slot;
            Kernel.apply(m, st, code, null);
            h.track(st, slot, reached);
        }
        assertTrue("rescued either way", (st.rescued & 1L) != 0);
        return h.objective(st, reached);
    }
}
