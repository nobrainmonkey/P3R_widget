package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.List;

/**
 * 安排风格 (DESIGN §20) on the synthetic year: the objective of each style, the plan id, strict goal
 * priority, and 「尽量多 MAX」 making exactly the plans of the engine before 3.1.0. The real-pack
 * comparison is PlanBreadthRealPackTest.
 */
public class BreadthObjectiveTest {
    private static final double EPS = 1e-9;

    private static Profile styled(Profile p, Profile.Style style) {
        Profile q = p.copy();
        q.style = style;
        return q;
    }

    private static Heuristics heuristics(Rules rules, Profile profile, SimState observed) {
        Model m = new Model(rules, profile, null);
        return new Heuristics(m, Kernel.normalize(m, observed), null);
    }

    @Test
    public void theDefaultStyleIsBreadth() {
        assertEquals(Profile.Style.BREADTH, new Profile().style);
        Rules rules = RulesFixtures.fullYear();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        assertTrue(heuristics(rules, new Profile(), start).breadth);
        assertFalse(heuristics(rules, styled(new Profile(), Profile.Style.MAX), start).breadth);
    }

    /**
     * 「尽量都体验」: each further RANK of a 「其余社群」 link is worth less than the one before, unlocking
     * the most, and MAX adds one rank's worth; 「尽量多 MAX」 keeps one {@code W_OTHER} per RANK and
     * six for MAX.
     */
    @Test
    public void breadthWeighsEachFurtherRankLessAndMaxModestly() {
        Rules rules = RulesFixtures.fullYear();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Heuristics breadth = heuristics(rules, new Profile(), start);
        Heuristics max = heuristics(rules, styled(new Profile(), Profile.Style.MAX), start);
        double w = Heuristics.W_OTHER;
        for (Rules.Link link : rules.links) {
            int l = link.idx;
            for (int r = 1; r <= 10; r++) {
                double b = breadth.val[l][r] - breadth.val[l][r - 1];
                double m = max.val[l][r] - max.val[l][r - 1];
                assertEquals(link.id + " RANK " + r, w * Heuristics.BREADTH_RANK[r] + (r == 10 ? Heuristics.BREADTH_MAX_BONUS_RANKS * w : 0),
                        b, EPS);
                assertEquals(link.id + " RANK " + r, w + (r == 10 ? 6 * w : 0), m, EPS);
                assertEquals(w * Heuristics.BREADTH_RANK[r], breadth.wRank(l, r), EPS);
                assertEquals(w, max.wRank(l, r), EPS);
            }
        }
        for (int r = 1; r < 10; r++) assertTrue("RANK " + r, Heuristics.BREADTH_RANK[r] > Heuristics.BREADTH_RANK[r + 1]);
        // Unlocking beats any later RANK, MAX included; two unlocks beat taking a link from RANK 5 to MAX.
        double unlock = Heuristics.BREADTH_RANK[1];
        assertTrue(unlock > Heuristics.BREADTH_RANK[10] + Heuristics.BREADTH_MAX_BONUS_RANKS);
        assertTrue(2 * unlock * w > breadth.val[4][10] - breadth.val[4][5]);
        assertTrue("under max, the MAX of a RANK 5 link beats two unlocks", 2 * w < max.val[4][10] - max.val[4][5]);
        assertTrue(Heuristics.BREADTH_MAX_BONUS_RANKS < Heuristics.MAX_BONUS_RANKS);
    }

    /**
     * The earlier MAX of a goal is worth half as much under 「尽量都体验」 (Heuristics.BREADTH_EARLY_SHARE):
     * finishing a goal a few days earlier must not buy away meetings, unlocks and MAX links of 其余社群.
     */
    @Test
    public void aGoalsEarlierMaxWeighsHalfUnderBreadth() {
        Rules rules = RulesFixtures.fullYear();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Profile profile = RulesFixtures.must(4);
        Heuristics breadth = heuristics(rules, profile, start);
        Heuristics max = heuristics(rules, styled(profile, Profile.Style.MAX), start);
        double[] perSlot = new double[2];
        Heuristics[] both = {breadth, max};
        for (int i = 0; i < 2; i++) {
            int[] reached = both[i].initialReached(start);
            reached[0] = start.slot + 10;
            double early = both[i].objective(start, reached);
            reached[0] = start.slot + 11;
            perSlot[i] = early - both[i].objective(start, reached);
        }
        assertEquals(max.wGoal[4] * Heuristics.EARLY_PER_SLOT, perSlot[1], EPS);
        assertEquals(Heuristics.BREADTH_EARLY_SHARE * perSlot[1], perSlot[0], EPS);
    }

    /** Goals keep their own weights per RANK under both styles; only the 「其余社群」 weights change. */
    @Test
    public void goalsWeighTheSameUnderBothStyles() {
        Rules rules = RulesFixtures.fullYear();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Profile profile = RulesFixtures.must(4, 6);
        profile.tier[9] = Profile.Tier.WANT;
        profile.target[9] = 6;
        Heuristics breadth = heuristics(rules, profile, start);
        Heuristics max = heuristics(rules, styled(profile, Profile.Style.MAX), start);
        assertArrayEquals(max.wGoal, breadth.wGoal, EPS);
        for (int g : new int[]{4, 6}) assertArrayEquals(max.val[g], breadth.val[g], EPS);
        for (int r = 1; r <= 6; r++) assertEquals(max.val[9][r], breadth.val[9][r], EPS);
        // Above its target a goal counts as 「其余社群」.
        assertEquals(Heuristics.W_OTHER * Heuristics.BREADTH_RANK[7], breadth.val[9][7] - breadth.val[9][6], EPS);
        assertNotEquals(max.val[1][1], breadth.val[1][1], EPS);
    }

    /** The plan id includes the style for 「尽量都体验」 only, so 「尽量多 MAX」 ids stay those of 3.0. */
    @Test
    public void planIdIncludesTheBreadthStyle() {
        Rules rules = RulesFixtures.small();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Engine.Options options = new Engine.Options();
        Profile max = styled(RulesFixtures.others(), Profile.Style.MAX);
        Plan plan = Engine.plan(rules, max, start, null, options);
        SimState normalized = Kernel.normalize(new Model(rules, max, null), start);
        assertEquals(plan.planId, PlanHash.of(rules, max, normalized, options, plan));
        String breadthId = PlanHash.of(rules, styled(max, Profile.Style.BREADTH), normalized, options, plan);
        assertNotEquals("same steps, other style", plan.planId, breadthId);
        assertEquals(breadthId, PlanHash.of(rules, styled(max, Profile.Style.BREADTH), normalized, options, plan));
    }

    /**
     * 「尽量多 MAX」 plans equal the plans of the engine before 3.1.0, plan id included (recorded at
     * the WP-0 commit with a time budget the search never runs out of): a new game, a 「必须」 goal and
     * a 9/1 start with nothing unlocked.
     */
    @Test
    public void maxStyleReproducesThePreviousPlans() {
        Rules rules = RulesFixtures.fullYear();
        Object[][] golden = {
                {"new game", RulesFixtures.others(), RulesFixtures.slot(4, 21, false), "07966421522f3e79", "c45f79d442d12dbe"},
                {"must 4", RulesFixtures.must(4), RulesFixtures.slot(4, 21, false), "4186884fcf91ae90", "791749e1117abaf8"},
                {"9/1", RulesFixtures.others(), RulesFixtures.slot(9, 1, false), "a97c882b2cc11458", "b2f684a085d67d50"}};
        for (Object[] g : golden) {
            Engine.Options options = new Engine.Options();
            options.timeBudgetMillis = 60_000;
            Plan plan = Engine.plan(rules, styled((Profile) g[1], Profile.Style.MAX), RulesFixtures.newGame(rules, (int) g[2]), null, options);
            assertFalse(g[0] + " ran out of time", plan.budgetExhausted);
            assertEquals(g[0] + " plan id", g[3], plan.planId);
            assertEquals(g[0] + " steps", g[4], PlanBreadthRealPackTest.fingerprint(plan));
        }
    }

    /**
     * From a 9/1 start with nothing unlocked, 「尽量都体验」 unlocks at least as many links, has at
     * least as many at RANK 5 or above and keeps 95% of the rank sum.
     */
    @Test
    public void breadthOpensAndRaisesMoreLinksFromALateStart() {
        Rules rules = RulesFixtures.fullYear();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(9, 1, false));
        Profile breadth = RulesFixtures.others();
        Profile max = styled(breadth, Profile.Style.MAX);
        Plan b = Engine.plan(rules, breadth, start, null, new Engine.Options());
        Plan m = Engine.plan(rules, max, start, null, new Engine.Options());
        assertValid(rules, breadth, start, b);
        assertValid(rules, max, start, m);
        PlanMetrics pb = PlanMetrics.of(rules, breadth, start, b);
        PlanMetrics pm = PlanMetrics.of(rules, max, start, m);
        String line = "breadth " + pb.breadthLine() + " / max " + pm.breadthLine();
        System.out.println(line);
        assertTrue(line, pb.unlocked >= pm.unlocked);
        assertTrue(line, pb.atLeast5 >= pm.atLeast5);
        assertTrue(line, pb.rankSum >= 0.95 * pm.rankSum);
    }

    /** Goals keep strict priority under 「尽量都体验」: a 「必须」 goal reaches MAX as it does under max. */
    @Test
    public void goalsReachTheirTargetUnderBothStyles() {
        Rules rules = RulesFixtures.fullYear();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Profile breadth = RulesFixtures.must(4);
        Profile max = styled(breadth, Profile.Style.MAX);
        Plan b = Engine.plan(rules, breadth, start, null, new Engine.Options());
        Plan m = Engine.plan(rules, max, start, null, new Engine.Options());
        assertValid(rules, breadth, start, b);
        assertEquals(10, m.summary.endRanks[4]);
        assertEquals(m.summary.endRanks[4], b.summary.endRanks[4]);
        assertNotEquals(m.planId, b.planId);
    }

    private static void assertValid(Rules rules, Profile profile, SimState start, Plan plan) {
        assertEquals(rules.lastSlot() - start.slot + 1, plan.steps.size());
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        assertFalse(plan.budgetExhausted);
    }
}
