package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Optional real-pack mid-save experiment. No fresh-new-game comparison or default CI execution. */
public final class MidgameDeepBenchmark {
    static Rules rules;
    static Path output;
    static int issues;
    // Legacy fixtures require zero warnings. Autumn records advisory FREE-slot warnings
    // separately, without conflating unused legal activities with illegal forecasts.
    static boolean strictWarnings = true;

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(System.getProperty("p3r.repo", System.getProperty("user.dir")));
        output = repo.resolve("qa_out/must_goals/midgame-deep");
        Files.createDirectories(output);
        if (Files.exists(output.resolve("results.ndjson"))) {
            Path archive = output.resolveSibling("midgame-deep-archive").resolve("run-" + System.currentTimeMillis());
            Files.createDirectories(archive);
            try (java.util.stream.Stream<Path> entries = Files.list(output)) {
                for (Path entry : entries.filter(Files::isRegularFile).toArray(Path[]::new)) {
                    if (!entry.getFileName().toString().equals("sources.txt"))
                        Files.move(entry, archive.resolve(entry.getFileName()));
                }
            }
            System.out.println("ARCHIVED_PREVIOUS_RUN " + archive);
        }
        rules = RulesLoader.parse(Files.readString(repo.resolve("android/app/src/main/assets/p3r/p3r_rules.json")));
        Field fixtureRules = PlanBreadthRealPackTest.class.getDeclaredField("rules");
        fixtureRules.setAccessible(true);
        fixtureRules.set(null, rules);
        JSONObject protocol = new JSONObject().put("started", Instant.now().toString())
                .put("rulesVersion", rules.version).put("policyVersion", Plan.CURRENT_POLICY_VERSION)
                .put("normalBudgetMs", 4_000).put("manualDeepBudgetMs", 30_000)
                .put("deepModifyBudgetMs", 12_000)
                .put("fixtures", "10/22 heterogeneous observed state; 1/8 heterogeneous late-onboarding state; 1/8 competing MUST goals at rank 6")
                .put("comparison", "same observed state and profile; missed rescues, late rescues, ordered goals, then current Pool score")
                .put("recording", "Engine.applyOutcome AS_PREDICTED, including resolved dorm-affinity snapshot; skipped action consumes its slot");
        Files.writeString(output.resolve("protocol.json"), protocol.toString(2));
        Files.writeString(output.resolve("results.ndjson"), "");

        Profile triple = goals(new String[]{"priestess", "empress", "lovers"}, new String[0]);
        scenario("oct22-three-must", fixture("midGameState"), triple, 2, true);
        Profile mixed = goals(new String[]{"emperor"}, new String[]{"priestess"});
        scenario("oct22-must-want", fixture("midGameState"), mixed, 3, false);
        scenario("jan08-late-onboarding", fixture("lateStart"), new Profile(), 0, false);
        scenario("jan08-strength-priority", lateCompetingGoals(),
                goals(new String[]{"strength", "justice"}, new String[0]), 0, false);
        scenario("jan08-justice-priority", lateCompetingGoals(),
                goals(new String[]{"justice", "strength"}, new String[0]), 0, false);
        System.out.println("BENCHMARK_FINISHED issues=" + issues + " output=" + output);
        if (issues != 0) throw new AssertionError("Review " + issues + " quality/validation regressions in results.ndjson");
    }

    private static SimState fixture(String name) throws Exception {
        Method m = PlanBreadthRealPackTest.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return ((SimState) m.invoke(null)).copy();
    }

    private static Profile goals(String[] must, String[] want) {
        Profile p = new Profile();
        for (int i = 0; i < must.length; i++) {
            int l = rules.linkIndex(must[i]); p.tier[l] = Profile.Tier.MUST; p.order[l] = i;
        }
        for (int i = 0; i < want.length; i++) {
            int l = rules.linkIndex(want[i]); p.tier[l] = Profile.Tier.WANT; p.order[l] = i;
        }
        return p;
    }

    /** Exact late competing-goal fixture from PlanRealPackTest, with both visible ranks reset. */
    private static SimState lateCompetingGoals() throws Exception {
        int jan8 = -1;
        for (int day = rules.firstDay; day <= rules.lastDay; day++) {
            if (rules.day(day).month() == 1 && rules.day(day).dayOfMonth() == 8) jan8 = day;
        }
        if (jan8 < 0) throw new AssertionError("missing January 8");
        SimState state = Engine.advanceTo(rules, new Profile(), fixture("midGameState"), Rules.slot(jan8, false));
        int strength = rules.linkIndex("strength"), justice = rules.linkIndex("justice");
        state.rank[strength] = state.rank[justice] = 6;
        state.pts[strength] = state.pts[justice] = 0;
        return state;
    }

    private static void scenario(String id, SimState start, Profile p, int advance, boolean deviation) throws Exception {
        Files.writeString(output.resolve(id + "-profile.json"), p.toJson().toString(2));
        Files.writeString(output.resolve(id + "-observed.json"), start.toJson().toString(2));
        Plan normal = run(id, "initial-normal4", p, start, null, null, 4_000, false, null);
        Plan deep = run(id, "initial-deep30", p, start, null, normal, 30_000, true, normal);
        if (advance > 0) {
            SimState after = start.copy();
            List<String> actual = new ArrayList<>();
            for (int i = 0; i < advance; i++) {
                Plan.Step step = deep.at(after.slot);
                actual.add(step.action);
                after = record(p, after, step, false);
            }
            Files.writeString(output.resolve(id + "-advanced-observed.json"), after.toJson().toString(2));
            System.out.println("ADVANCE " + id + " actions=" + actual);
            Plan ordinary = run(id, "advanced-normal4", p, after, deep, null, 4_000, false, null);
            run(id, "advanced-deep12", p, after, deep, null, 12_000, true, ordinary);
        }
        if (deviation) {
            Plan.Step missed = deep.at(start.slot);
            if (!Action.parse(rules, missed.action).isProductive()) throw new AssertionError("deviation needs a real first action");
            SimState after = record(p, start.copy(), missed, true);
            Files.writeString(output.resolve(id + "-deviated-observed.json"), after.toJson().toString(2));
            System.out.println("DEVIATE " + id + " skipped=" + missed.action + " at=" + PlanReport.date(rules, missed.slot));
            Plan ordinary = run(id, "skipped-normal4", p, after, deep, null, 4_000, false, null);
            run(id, "skipped-deep12", p, after, deep, null, 12_000, true, ordinary);
        }
    }

    static SimState record(Profile p, SimState state, Plan.Step step, boolean skipped) {
        Action action = Action.parse(rules, step.action);
        if (action == null) throw new AssertionError("unparseable action " + step.action);
        Outcome o = Outcome.of(skipped ? Outcome.Code.SKIPPED : Outcome.Code.AS_PREDICTED);
        o.sideKeys = step.side;
        if (skipped) { o.reason = "other"; o.sideDone = false; }
        if (!skipped && action.type == Action.Type.ACTIVITY) {
            Rules.Activity activity = rules.activities[action.target];
            int gain = Engine.activityAffinityGain(rules, state, action.target);
            if (gain > 0 && activity.affinityLink >= 0) {
                o.activityAffinityLink = rules.links[activity.affinityLink].id;
                o.activityAffinityPts = gain;
            }
        }
        return Engine.applyOutcome(rules, p, state, action, o);
    }

    static Planner.Pool pool(Profile p, SimState observed) {
        Engine.Options o = new Engine.Options();
        o.lastTowerSlot = observed.lastTowerSlot;
        Model m = new Model(rules, p, o);
        SimState state = Kernel.normalize(m, observed);
        m.initializeBatches(state);
        return new Planner.Pool(m, state, null, o, Long.MAX_VALUE, Planner.portfolioFor(p));
    }

    static Plan run(String id, String phase, Profile p, SimState observed, Plan previous,
                            Plan incumbent, long budget, boolean deep, Plan pairedBaseline) throws Exception {
        Engine.Options o = new Engine.Options(); o.timeBudgetMillis = budget; o.deepSearch = deep; o.incumbentPlan = incumbent;
        o.lastTowerSlot = observed.lastTowerSlot;
        long t0 = System.nanoTime();
        Plan plan = Engine.plan(rules, p, observed, previous, o);
        long wall = (System.nanoTime() - t0) / 1_000_000;
        Files.writeString(output.resolve(id + "--" + phase + "-plan.json"), plan.toJson().toString(2));
        PlanMetrics metrics = PlanMetrics.of(rules, p, observed, plan);
        List<String> errors = Engine.validate(rules, p, observed, plan);
        List<String> warnings = Engine.validationWarnings(rules, p, observed, plan);
        Planner.Pool evaluator = pool(p, observed);
        Planner.Candidate measured = evaluator.admitIncumbent(plan);
        Planner.Candidate remainingFloor = previous == null ? null : evaluator.admitPrevious(previous);
        Planner.Candidate paired = pairedBaseline == null ? null : evaluator.admitIncumbent(pairedBaseline);
        JSONObject row = new JSONObject().put("scenario", id).put("phase", phase)
                .put("start", PlanReport.date(rules, observed.slot)).put("budgetMs", budget).put("wallMs", wall)
                .put("elapsedMs", plan.elapsedMillis).put("exhausted", plan.budgetExhausted)
                .put("planId", plan.planId).put("fingerprint", PlanBreadthRealPackTest.fingerprint(plan))
                .put("steps", plan.steps.size()).put("max", metrics.maxed).put("unlocked", metrics.unlocked)
                .put("rankSum", metrics.rankSum).put("rank5", metrics.atLeast5).put("minimumRank", metrics.minRank)
                .put("minimumOpened", metrics.minOpened).put("daytimeWaste", metrics.daytimeWaste)
                .put("dayJobs", metrics.dayJobs).put("eveJobs", metrics.eveJobs).put("endCash", metrics.endCash)
                .put("dayStats", metrics.dayStats).put("eveStats", metrics.eveStats).put("freeSlots", metrics.freeSlots)
                .put("style", p.style.json).put("personaMode", p.personaMode.name())
                .put("previousPlanId", previous == null ? JSONObject.NULL : previous.planId)
                .put("incumbentPlanId", incumbent == null ? JSONObject.NULL : incumbent.planId)
                .put("notes", metrics.notes).put("missedRescues", metrics.rescuesMissed()).put("unmetTower", metrics.unmetTower)
                .put("endStatLv", new JSONArray(plan.summary.endStatLv)).put("endRanks", new JSONArray(plan.summary.endRanks))
                .put("errors", new JSONArray(errors)).put("warnings", new JSONArray(warnings))
                .put("candidate", quality(measured)).put("previousSuffix", quality(remainingFloor)).put("pairedBaseline", quality(paired));
        JSONArray goalRows = new JSONArray();
        int goalCompleted = 0, goalRankSum = 0, goalDeficit = 0;
        for (int g : Heuristics.goalOrder(evaluator.m)) {
            int target = Math.max(1, Math.min(10, p.target[g]));
            if (plan.summary.endRanks[g] >= target) goalCompleted++;
            goalRankSum += plan.summary.endRanks[g];
            goalDeficit += Math.max(0, target - plan.summary.endRanks[g]);
            goalRows.put(new JSONObject().put("id", rules.links[g].id).put("tier", p.tier[g].name())
                    .put("target", p.target[g]).put("endRank", plan.summary.endRanks[g])
                    .put("maxDate", plan.summary.maxSlot[g] < 0 ? "—" : PlanReport.date(rules, plan.summary.maxSlot[g])));
        }
        row.put("goals", goalRows).put("goalCompleted", goalCompleted)
                .put("goalRankSum", goalRankSum).put("goalDeficit", goalDeficit);
        row.put("pairedQualityRelation", measured == null || paired == null ? "UNAVAILABLE"
                : worse(measured, paired) ? "WORSE" : worse(paired, measured) ? "BETTER" : "SAME");
        row.put("previousSuffixQualityRelation", measured == null || remainingFloor == null ? "UNAVAILABLE"
                : worse(measured, remainingFloor) ? "WORSE" : worse(remainingFloor, measured) ? "BETTER" : "SAME");
        List<String> reviews = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if ("oct22-three-must".equals(id) && phase.startsWith("initial-")) {
            // Existing two-goal witness proves the first pair feasible from this exact fixture.
            // Structural validity alone cannot excuse leaving the second MUST link unopened.
            for (String goal : new String[]{"priestess", "empress"}) {
                if (plan.summary.endRanks[rules.linkIndex(goal)] != 10)
                    problems.add("known feasible MUST pair not MAX: " + goal);
            }
        }
        if ("jan08-strength-priority".equals(id) || "jan08-justice-priority".equals(id)) {
            int sRank = plan.summary.endRanks[rules.linkIndex("strength")];
            int jRank = plan.summary.endRanks[rules.linkIndex("justice")];
            if ((sRank < 10 && jRank < 10) || (sRank + jRank < 17))
                problems.add("competing MUST goal result below combined rank 17");
        }
        if (!errors.isEmpty() || measured == null) problems.add("validation/replay");
        if (!warnings.isEmpty()) {
            if (strictWarnings) problems.add("validation warnings");
            else reviews.add("independent advisory warnings retained for slot-level audit: " + warnings.size());
        }
        if (plan.steps.size() != rules.lastSlot() - observed.slot + 1) problems.add("incomplete plan");
        if (metrics.rescuesMissed() > 0 || metrics.unmetTower > 0) problems.add("rescue/tower");
        floorContract(p, measured, remainingFloor, "legal previous suffix", problems, reviews);
        // Automatic deep modify receives only the same old route. Its ordinary baseline must be
        // established inside the engine's shared budget, never supplied by this benchmark.
        // The actual paired ordinary run independently verifies the goal/rescue guarantee.
        if (deep) floorContract(p, measured, paired, incumbent == null
                ? "same-state ordinary comparison" : "supplied same-state incumbent", problems, reviews);
        row.put("problems", new JSONArray(problems));
        row.put("reviews", new JSONArray(reviews));
        Files.writeString(output.resolve("results.ndjson"), row.toString() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        issues += problems.size();
        System.out.println("RESULT " + id + " " + phase + " goals=" + goalRows + " max=" + metrics.maxed
                + " rankSum=" + metrics.rankSum + " score=" + (measured == null ? "INVALID" : measured.score)
                + " suffix=" + (remainingFloor == null ? "REJECTED/NONE" : remainingFloor.score)
                + " wallMs=" + wall + " exhausted=" + plan.budgetExhausted + " problems=" + problems + " reviews=" + reviews);
        System.out.flush();
        return plan;
    }

    static JSONObject quality(Planner.Candidate c) {
        if (c == null) return null;
        return new JSONObject().put("missed", c.missed).put("late", c.late).put("objective", c.j).put("score", c.score)
                .put("reached", new JSONArray(c.reached)).put("goalEndRanks", new JSONArray(c.endRank));
    }

    static boolean worse(Planner.Candidate a, Planner.Candidate b) {
        if (a.missed != b.missed) return a.missed > b.missed;
        if (a.late != b.late) return a.late > b.late;
        int goals = Planner.Pool.compareGoals(a, b);
        return goals < 0 || goals == 0 && a.score < b.score - 1e-6;
    }

    private static void floorContract(Profile p, Planner.Candidate actual, Planner.Candidate floor, String label,
                                      List<String> problems, List<String> reviews) {
        if (actual == null || floor == null || !worse(actual, floor)) return;
        boolean priorityWorse = actual.missed != floor.missed ? actual.missed > floor.missed
                : actual.late != floor.late ? actual.late > floor.late : Planner.Pool.compareGoals(actual, floor) < 0;
        if (priorityWorse || p.style == Profile.Style.MAX) problems.add("worse than " + label);
        else reviews.add("lower score than " + label + "; assess current BREADTH promises against its internal MAX reference");
    }
}
