package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manual deeper search covers extra routes and may never replace a legal baseline with worse goals. */
public class DeepSearchPolicyTest {
    private static Engine.Options options(boolean deep) {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 60_000;
        options.deepSearch = deep;
        options.trace = new ArrayList<>();
        return options;
    }

    private static Profile profile() {
        Profile profile = RulesFixtures.others();
        profile.style = Profile.Style.MAX;
        return profile;
    }

    private static void valid(Rules rules, Profile profile, SimState start, Plan plan) {
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        assertEquals(start.slot, plan.fromSlot);
        assertEquals(rules.lastSlot() - start.slot + 1, plan.steps.size());
    }

    @Test
    public void deepSearchActuallyFortifiesTheTuningsOutsideTheNormalShortlist() {
        Rules rules = RulesFixtures.small();
        Profile profile = profile();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Engine.Options normal = options(false);
        Plan ordinary = Engine.plan(rules, profile, start, null, normal);
        Engine.Options deep = options(true);
        Plan thorough = Engine.plan(rules, profile, start, null, deep);
        valid(rules, profile, start, ordinary);
        valid(rules, profile, start, thorough);
        assertFalse(ordinary.budgetExhausted);
        assertFalse(thorough.budgetExhausted);
        assertEquals(Planner.PORTFOLIO.length + Planner.FORTIFY, normal.trace.size());
        assertEquals("ordinary phase, admitted ordinary result, then every expanded tuning",
                normal.trace.size() + 1 + Planner.PORTFOLIO.length * 2, deep.trace.size());
        assertTrue(deep.trace.size() > normal.trace.size());
    }

    @Test
    public void deepSearchExploresMoreActionsWithinEachRollout() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = profile();
        Engine.Options normal = options(false);
        Engine.Options deep = options(true);
        Model model = new Model(rules, profile, normal);
        SimState start = Kernel.normalize(model,
                RulesFixtures.newGame(rules, RulesFixtures.slot(1, 10, false)));
        for (Rules.Link link : rules.links) {
            if (link.kind == Rules.LinkKind.MANUAL) start.rank[link.idx] = 5;
        }
        start.cash = 100_000;
        for (int stat = 0; stat < 3; stat++) start.statPts[stat] = rules.stats[stat].thresholds[6];
        model.initializeBatches(start);
        Planner.Search ordinary = new Planner.Search(model, new Heuristics(model, start, null), normal,
                Long.MAX_VALUE, 0, 0);
        Planner.Search thorough = new Planner.Search(model, new Heuristics(model, start, null), deep,
                Long.MAX_VALUE, 0, 0);
        ordinary.run(start);
        thorough.run(start);
        assertTrue("ordinary=" + ordinary.rollouts + ", deep=" + thorough.rollouts,
                thorough.rollouts > ordinary.rollouts);
    }

    @Test
    public void deepSearchAdmitsTheOrdinaryRouteEvenWithoutAPreviousPlanOrExplicitSeed() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.must(RulesFixtures.S_MAGICIAN, RulesFixtures.S_EMPEROR);
        profile.style = Profile.Style.MAX;
        SimState start = RulesFixtures.newGame(rules, Rules.slot(22, false));
        Engine.Options normal = options(false);
        Plan ordinary = Engine.plan(rules, profile, start, null, normal);
        Engine.Options deep = options(true);
        List<Float> progress = new ArrayList<>();
        deep.progress = progress::add;
        Plan result = Engine.plan(rules, profile, start, null, deep);
        valid(rules, profile, start, ordinary);
        valid(rules, profile, start, result);
        Model model = new Model(rules, profile, deep);
        SimState normalized = Kernel.normalize(model, start);
        model.initializeBatches(normalized);
        Planner.Pool comparison = new Planner.Pool(model, normalized, null, deep, Long.MAX_VALUE,
                Planner.portfolioFor(profile));
        Planner.Candidate baseline = comparison.admitIncumbent(ordinary);
        Planner.Candidate actual = comparison.admitIncumbent(result);
        assertNotNull(baseline);
        assertNotNull(actual);
        assertTrue(actual.missed <= baseline.missed);
        if (actual.missed == baseline.missed) {
            assertTrue(actual.late <= baseline.late);
            if (actual.late == baseline.late) {
                int goals = Planner.Pool.compareGoals(actual, baseline);
                assertTrue(goals >= 0);
                if (goals == 0) assertTrue(actual.score + 1e-6 >= baseline.score);
            }
        }
        // The ordinary witness appears immediately after the ordinary phase's compared plans,
        // before the expanded search. A larger candidate count alone cannot provide this witness.
        assertTrue(java.util.Arrays.equals(baseline.linkRanks, deep.trace.get(normal.trace.size())));
        assertFalse(progress.isEmpty());
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1));
        assertEquals(1f, progress.get(progress.size() - 1), 0f);
    }

    @Test
    public void aValidSeedWinsOverAnyWorseGoalEvenWhenCancelledBeforeSearch() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.must(RulesFixtures.S_MAGICIAN, RulesFixtures.S_EMPEROR);
        profile.style = Profile.Style.MAX;
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan seed = Engine.plan(rules, profile, start, null, options(false));
        valid(rules, profile, start, seed);
        Engine.Options deep = options(true);
        deep.incumbentPlan = seed;
        deep.cancelled = new AtomicBoolean(true);
        Plan result = Engine.plan(rules, profile, start, seed, deep);
        valid(rules, profile, start, result);
        Model model = new Model(rules, profile, deep);
        SimState normalized = Kernel.normalize(model, start);
        model.initializeBatches(normalized);
        Planner.Pool comparison = new Planner.Pool(model, normalized, null, deep, Long.MAX_VALUE,
                Planner.portfolioFor(profile));
        Planner.Candidate before = comparison.admitIncumbent(seed);
        Planner.Candidate after = comparison.admitIncumbent(result);
        assertNotNull(before);
        assertNotNull(after);
        assertTrue("deep search cannot leave more rescue groups missing", after.missed <= before.missed);
        if (after.missed == before.missed) {
            assertTrue("equal rescue coverage cannot move more rescues to their last evening",
                    after.late <= before.late);
            if (after.late == before.late) {
                int goals = Planner.Pool.compareGoals(after, before);
                assertTrue("equal rescue outcomes cannot sacrifice an earlier goal", goals >= 0);
                if (goals == 0) {
                    assertTrue("equal rescue and goal outcomes must keep the seed's score",
                            after.score + 1e-6 >= before.score);
                }
            }
        }
        assertTrue(result.budgetExhausted);
        // Admission is a real quality floor, not a stability instruction for the first days.
        comparison.all.clear();
        comparison.add(before);
        boolean[] worseReached = before.reached.clone();
        int[] worseRanks = before.endRank.clone();
        worseReached[0] = false;
        worseRanks[0] = Math.max(0, Math.min(profile.target[RulesFixtures.S_MAGICIAN] - 1, worseRanks[0] - 1));
        Planner.Candidate highScoreButMissed = new Planner.Candidate(null, null, null, new int[0],
                1e12, 1e12, 1e12, 0, worseReached, worseRanks, new int[rules.links.length], 0,
                before.late, before.missed);
        comparison.add(highScoreButMissed);
        assertEquals(before, comparison.choose());
        // More score or completed goals cannot compensate for an avoidable missed/late rescue.
        boolean[] allReached = before.reached.clone();
        java.util.Arrays.fill(allReached, true);
        int[] allMax = before.endRank.clone();
        java.util.Arrays.fill(allMax, 10);
        comparison.add(new Planner.Candidate(null, null, null, new int[0], 1e12, 1e12, 1e12, 0,
                allReached, allMax, new int[rules.links.length], 0, before.late, before.missed + 1));
        comparison.add(new Planner.Candidate(null, null, null, new int[0], 1e12, 1e12, 1e12, 0,
                allReached, allMax, new int[rules.links.length], 0, before.late + 1, before.missed));
        assertEquals(before, comparison.choose());
    }

    @Test
    public void staleOrIllegalSeedsAreRejectedBeforeUncheckedKernelReplay() throws Exception {
        Rules rules = RulesFixtures.small();
        Profile profile = profile();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan seed = Engine.plan(rules, profile, start, null, options(false));
        Engine.Options options = options(true);
        Model model = new Model(rules, profile, options);
        SimState normalized = Kernel.normalize(model, start);
        model.initializeBatches(normalized);
        Planner.Pool pool = new Planner.Pool(model, normalized, null, options, Long.MAX_VALUE,
                Planner.portfolioFor(profile));
        assertNotNull(pool.admitIncumbent(seed));
        Plan stale = Plan.fromJson(new JSONObject(seed.toJson().toString()));
        stale.policyVersion--;
        assertNull(pool.admitIncumbent(stale));
        Plan wrongRules = Plan.fromJson(new JSONObject(seed.toJson().toString()));
        wrongRules.rulesVersion = "other";
        assertNull(pool.admitIncumbent(wrongRules));
        Plan wrongState = Plan.fromJson(new JSONObject(seed.toJson().toString()));
        wrongState.fromSlot++;
        assertNull(pool.admitIncumbent(wrongState));
        Plan illegal = Plan.fromJson(new JSONObject(seed.toJson().toString()));
        illegal.steps.get(0).action = "tower:999999";
        assertNull(pool.admitIncumbent(illegal));
        assertEquals("only the valid seed joined the pool", 1, pool.all.size());
    }

    @Test
    public void incrementalReplansRetainTheQualityOfADeepRouteAfterRecordingAnExpectedStep() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.must(RulesFixtures.S_MAGICIAN, RulesFixtures.S_EMPEROR);
        profile.style = Profile.Style.MAX;
        SimState start = RulesFixtures.newGame(rules, Rules.slot(22, false));
        Plan deep = Engine.plan(rules, profile, start, null, options(true));
        valid(rules, profile, start, deep);
        Action completed = Action.parse(rules, deep.steps.get(0).action);
        SimState observed = Engine.applyOutcome(rules, profile, start, completed,
                Outcome.of(Outcome.Code.AS_PREDICTED));
        // A changed cash observation invalidates the old forecast, but not this legal route.
        observed.cash += 500;
        for (boolean deepChanges : new boolean[]{false, true}) {
            Engine.Options incremental = options(deepChanges);
            incremental.timeBudgetMillis = 0; // the quality floor must survive even without rollout time
            Plan result = Engine.plan(rules, profile, observed, deep, incremental);
            valid(rules, profile, observed, result);
            Model model = new Model(rules, profile, incremental);
            SimState normalized = Kernel.normalize(model, observed);
            model.initializeBatches(normalized);
            Planner.Pool comparison = new Planner.Pool(model, normalized, null, incremental,
                    Long.MAX_VALUE, Planner.portfolioFor(profile));
            Planner.Candidate baseline = comparison.admitPrevious(deep);
            Planner.Candidate actual = comparison.admitIncumbent(result);
            assertNotNull("remaining deep route stays legal", baseline);
            assertNotNull(actual);
            assertTrue(actual.missed <= baseline.missed);
            if (actual.missed == baseline.missed) {
                assertTrue(actual.late <= baseline.late);
                if (actual.late == baseline.late) {
                    int goals = Planner.Pool.compareGoals(actual, baseline);
                    assertTrue(goals >= 0);
                    if (goals == 0) assertTrue(actual.score + 1e-6 >= baseline.score);
                }
            }
            assertEquals("predictions are rebuilt from the new cash observation", observed.cash,
                    result.steps.get(0).predicted.cashAfter - result.steps.get(0).predicted.cashDelta);
        }
    }

    @Test
    public void anActualDeviationThatMakesALaterActionIllegalRejectsTheWholeRemainingRoute() {
        Rules rules = RulesFixtures.small();
        Profile profile = profile();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(22, false));
        Plan seed = Engine.plan(rules, profile, start, null, options(true));
        Action completed = Action.parse(rules, seed.steps.get(0).action);
        SimState observed = Engine.applyOutcome(rules, profile, start, completed,
                Outcome.of(Outcome.Code.AS_PREDICTED));
        // This outcome was instead recorded as every manual link already being MAX. At least
        // one of the remaining meetings/unlocks is consequently no longer a legal action.
        for (Rules.Link link : rules.links) {
            if (link.kind == Rules.LinkKind.MANUAL) observed.rank[link.idx] = 10;
        }
        Engine.Options incremental = options(false);
        incremental.timeBudgetMillis = 0;
        Model model = new Model(rules, profile, incremental);
        SimState normalized = Kernel.normalize(model, observed);
        model.initializeBatches(normalized);
        Planner.Pool comparison = new Planner.Pool(model, normalized, null, incremental,
                Long.MAX_VALUE, Planner.portfolioFor(profile));
        assertNull(comparison.admitPrevious(seed));
        assertTrue(comparison.all.isEmpty());
        Plan result = Engine.plan(rules, profile, observed, seed, incremental);
        valid(rules, profile, observed, result);
        for (Plan.Step step : result.steps) {
            assertFalse(step.action, "social".equals(step.kind) || "unlock".equals(step.kind));
        }
    }

    @Test
    public void aPreviousMaxStyleRouteCannotOverrideTheCurrentBreadthPromisesForMoreScore() {
        Rules rules = RulesFixtures.small();
        Profile profile = profile();
        profile.style = Profile.Style.BREADTH;
        Engine.Options options = options(true);
        Model model = new Model(rules, profile, options);
        SimState start = Kernel.normalize(model, RulesFixtures.newGame(rules, Rules.slot(22, false)));
        Planner.Pool pool = new Planner.Pool(model, start, null, options, Long.MAX_VALUE,
                Planner.portfolioFor(profile));
        int[] covered = {1, 10, 10, 10, 10};
        int[] unopened = {1, 10, 10, 10, 0};
        Planner.Candidate breadth = new Planner.Candidate(null, null, null, new int[0],
                1_000, 1_000, 1_000, 0, new boolean[0], new int[0], covered, 0);
        Planner.Candidate oldRoute = new Planner.Candidate(null, null, null, new int[0],
                9_000, 9_000, 9_000, 0, new boolean[0], new int[0], unopened, 0);
        pool.add(oldRoute);
        pool.add(breadth);
        pool.maxPlan = breadth;
        assertFalse("the old route leaves a completable link unopened", pool.keepsPromises(oldRoute));
        assertTrue(pool.keepsPromises(breadth));
        assertEquals("the current breadth promise takes precedence over the old route's score",
                breadth, pool.chooseWithFloor(oldRoute));
        pool.maxPlan = null;
        assertEquals("without a current style promise, the better baseline remains eligible",
                oldRoute, pool.chooseWithFloor(oldRoute));
    }

    @Test
    public void deepSearchIsDeterministicWithTheSamePreviousRoute() {
        Rules rules = RulesFixtures.small();
        Profile profile = profile();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan previous = Engine.plan(rules, profile, start, null, options(false));
        Plan first = Engine.plan(rules, profile, start, previous, options(true));
        Plan second = Engine.plan(rules, profile, start.copy(), previous, options(true));
        valid(rules, profile, start, first);
        valid(rules, profile, start, second);
        assertFalse(first.budgetExhausted);
        assertEquals(first.planId, second.planId);
        for (int i = 0; i < first.steps.size(); i++) {
            assertEquals(first.steps.get(i).action, second.steps.get(i).action);
        }
    }
}
