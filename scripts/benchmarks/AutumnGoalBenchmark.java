package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static app.p3r.dayplanner.v3.engine.MidgameDeepBenchmark.pool;
import static app.p3r.dayplanner.v3.engine.MidgameDeepBenchmark.quality;
import static app.p3r.dayplanner.v3.engine.MidgameDeepBenchmark.run;
import static app.p3r.dayplanner.v3.engine.MidgameDeepBenchmark.worse;

/** Optional autumn checkpoint experiment; constructed saves, never described as user records. */
public final class AutumnGoalBenchmark {
    private static final String[] GOALS = {"priestess", "empress", "lovers", "justice", "hermit",
            "strength", "hanged_man", "sun", "aeon"};
    private static final String[] MANUAL = {"magician", "priestess", "empress", "emperor", "hierophant",
            "lovers", "chariot", "justice", "hermit", "fortune", "strength", "hanged_man",
            "temperance", "devil", "tower", "star", "moon", "sun", "aeon"};
    private static Rules rules;
    private static Path output;

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(System.getProperty("p3r.repo", System.getProperty("user.dir")));
        rules = RulesLoader.parse(Files.readString(repo.resolve("android/app/src/main/assets/p3r/p3r_rules.json")));
        output = repo.resolve("qa_out/must_goals/autumn-deep");
        Files.createDirectories(output);
        archivePrevious();
        MidgameDeepBenchmark.rules = rules;
        MidgameDeepBenchmark.output = output;
        MidgameDeepBenchmark.issues = 0;
        MidgameDeepBenchmark.strictWarnings = false;
        Field fixtureRules = PlanBreadthRealPackTest.class.getDeclaredField("rules");
        fixtureRules.setAccessible(true);
        fixtureRules.set(null, rules);
        Files.writeString(output.resolve("results.ndjson"), "");
        Files.writeString(output.resolve("comparisons.ndjson"), "");
        writeProtocol();

        List<Fixture> cases = new ArrayList<>();
        cases.add(fixture("sep14-balanced", 9, 14, new int[]{180, 100, 80}, 32_000,
                new int[]{7, 4, 0, 4, 5, 3, 6, 6, 6, 3, 5, 5, 2, 4, 3, 2, 3, 3, 0}, false));
        cases.add(fixture("sep14-delayed-stats", 9, 14, new int[]{130, 100, 68}, 12_000,
                new int[]{6, 0, 0, 3, 4, 2, 5, 5, 5, 2, 4, 5, 1, 0, 2, 1, 2, 2, 0}, false));
        cases.add(fixture("oct08-preexam", 10, 8, new int[]{190, 100, 80}, 35_000,
                new int[]{8, 5, 0, 5, 6, 4, 7, 7, 6, 4, 6, 6, 3, 5, 4, 3, 4, 4, 0}, false));
        cases.add(fixture("oct22-progressed", 10, 22, new int[]{210, 100, 80}, 45_000,
                new int[]{8, 6, 0, 6, 6, 5, 7, 8, 7, 4, 7, 6, 3, 6, 5, 3, 4, 5, 0}, false));
        cases.add(fixture("sep14-sunday-first", 9, 14, new int[]{180, 100, 80}, 32_000,
                new int[]{7, 4, 0, 4, 5, 3, 6, 6, 6, 3, 5, 5, 2, 4, 3, 2, 3, 3, 0}, true));

        // JVM warm-up uses the same progressed September checkpoint and is excluded from the rows.
        Fixture warm = cases.get(0);
        for (int i = 0; i < 2; i++) {
            Engine.Options options = new Engine.Options();
            options.timeBudgetMillis = 4_000;
            options.lastTowerSlot = warm.start.lastTowerSlot;
            Plan plan = Engine.plan(rules, warm.profile, warm.start, null, options);
            if (!Engine.validate(rules, warm.profile, warm.start, plan).isEmpty())
                throw new AssertionError("invalid warm-up plan");
            System.out.println("WARMUP autumn " + (i + 1) + " elapsedMs=" + plan.elapsedMillis);
        }
        for (Fixture f : cases) scenario(f);
        System.out.println("AUTUMN_BENCHMARK_FINISHED issues=" + MidgameDeepBenchmark.issues + " output=" + output);
        if (MidgameDeepBenchmark.issues != 0)
            throw new AssertionError("Review " + MidgameDeepBenchmark.issues + " issues in autumn results.ndjson");
    }

    private static void archivePrevious() throws Exception {
        if (!Files.exists(output.resolve("results.ndjson"))) return;
        Path archive = output.resolveSibling("autumn-deep-archive").resolve("run-" + System.currentTimeMillis());
        Files.createDirectories(archive);
        try (java.util.stream.Stream<Path> entries = Files.list(output)) {
            for (Path entry : entries.filter(Files::isRegularFile).toArray(Path[]::new)) {
                if (!entry.getFileName().toString().equals("sources.txt"))
                    Files.move(entry, archive.resolve(entry.getFileName()));
            }
        }
        System.out.println("ARCHIVED_PREVIOUS_AUTUMN_RUN " + archive);
    }

    private static void writeProtocol() throws Exception {
        JSONObject p = new JSONObject().put("started", Instant.now().toString())
                .put("rulesVersion", rules.version).put("policyVersion", Plan.CURRENT_POLICY_VERSION)
                .put("fixtureKind", "constructed representative progressed checkpoints; not captured user saves")
                .put("goals", new JSONArray(GOALS)).put("goalDefinition", "all eight female manual links plus Sun; MUST target10")
                .put("route", "FRIEND for all six romanceable links")
                .put("personaMode", "GOALS").put("answers", "BEST").put("goalBuffer", true)
                .put("style", "BREADTH").put("towerIncomePerVisit", 0)
                .put("ordinaryModifyBudgetMs", 4_000).put("deepModifyBudgetMs", 12_000)
                .put("manualFullBudgetMs", 8_000).put("manualDeepBudgetMs", 30_000)
                .put("warmup", "two ordinary plans from the September14 balanced checkpoint, excluded from results")
                .put("runOrderCheck", "Sun/Hermit-first initial deep12 runs before ordinary4; the other four initial pairs run ordinary4 first")
                .put("comparisons", "normal4/deep12; manualFull8/manualDeep30; one-week records normal4/deep12; deviated-week records normal4/deep12")
                .put("deep12Input", "only same old previous plan; paired normal is evaluation-only and never an engine incumbent")
                .put("manualDeepInput", "same-state ordinary4 incumbent; manual8 comparison is independent, not its engine floor")
                .put("deviation", "one legal alternative evening; one point-gated goal meeting fails predicted rank-up; 5000 cash correction at final recorded slot")
                .put("records", "14 sequential applyOutcome transitions, resolved dorm affinity, exact side keys, explicit visible ranks and cash correction")
                .put("history", "checkpoint totals plus a dated subset of dorm/study history; not an invented complete pre-checkpoint action route")
                .put("comparisonOrder", "missed rescues, late rescues, ordered goals, then current candidate score; score changes shown separately")
                .put("allTargetsFeasible", "not assumed; report all nine end ranks and total target deficit");
        Files.writeString(output.resolve("protocol.json"), p.toString(2));
    }

    private static final class Fixture {
        String id;
        Profile profile;
        SimState start;
        JSONObject provenance;
    }

    private static Fixture fixture(String id, int month, int dom, int[] stats, long cash,
                                   int[] ranks, boolean sundayFirst) throws Exception {
        Fixture f = new Fixture();
        f.id = id;
        f.profile = profile(sundayFirst);
        int day = dayOf(month, dom);
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = Rules.slot(day, false);
        s.cash = cash;
        s.cashExact = true;
        s.statPts = stats.clone();
        for (Rules.Link l : rules.links) {
            if (l.kind == Rules.LinkKind.STORY && l.storyRankDay != null) {
                for (int rank = 1; rank < l.storyRankDay.length; rank++) {
                    if (l.storyRankDay[rank] >= 0 && l.storyRankDay[rank] <= day) s.rank[l.idx] = rank;
                }
            }
        }
        JSONArray affinity = new JSONArray();
        for (int i = 0; i < MANUAL.length; i++) {
            Rules.Link l = rules.link(MANUAL[i]);
            int rank = ranks[i];
            s.rank[l.idx] = rank;
            if (rank == 0) continue;
            if (day < l.unlock.earliestDay) throw new AssertionError(id + " premature opening " + l.id);
            s.unlockStep[l.idx] = l.unlock.steps.size();
            // Exact opening dates are unknown in an imported checkpoint. -1 preserves that fact;
            // completed steps/ranks are observations and must not be charged again by the forecast.
            s.lastStepDay[l.idx] = -1;
            s.pts[l.idx] = rank >= 10 ? 0 : Kernel.scaled(l.eventGain[rank], rules.policy.charmMult);
            affinity.put(new JSONObject().put("link", l.id).put("rank", rank).put("pts", s.pts[l.idx])
                    .put("basis", "best friend-route rank scene with already-MAX Charm; no past Persona bonus assumed"));
        }
        // The player checked one ordinary link's cue. Goal affinity remains an estimate, so a
        // later NO_RANK_UP deviation tests an uncertain forecast rather than contradicting a cue.
        for (String link : new String[]{"magician"}) {
            int l = rules.linkIndex(link), rank = s.rank[l];
            if (rank > 0 && rank < 10 && s.pts[l] >= rules.links[l].threshold[rank + 1]) s.ready[l] = true;
        }
        Rules.Block current = rules.blockOn(day);
        for (Rules.Block b : rules.blocks) if (b.idx < current.idx) s.towerVisits[b.idx] = 2;
        if (month == 9) {
            s.floor = 118;
            s.lastTowerSlot = Rules.slot(dayOf(9, 3), true);
        } else if (dom == 8) {
            s.floor = 144;
            s.lastTowerSlot = Rules.slot(dayOf(10, 2), true);
        } else {
            s.floor = 172;
            s.towerVisits[current.idx] = 1;
            s.lastTowerSlot = Rules.slot(dayOf(10, 19), true);
        }
        for (int i = 0; i < rules.rescues.length; i++) {
            if (rules.rescues[i].toDay < day) s.rescued |= 1L << i;
        }
        Set<Integer> historySlots = new HashSet<>();
        JSONArray history = new JSONArray();
        seedActivity(s, history, historySlots, "group_study_05_15", 5, 15);
        seedActivity(s, history, historySlots, "group_study_07_09", 7, 9);
        seedActivity(s, history, historySlots, "dorm_yukari_dvd", 6, 23);
        seedActivity(s, history, historySlots, "dorm_yukari_dvd", 7, 28);
        seedActivity(s, history, historySlots, "dorm_fuuka_reading", 6, 25);
        seedActivity(s, history, historySlots, "dorm_fuuka_reading", 8, 20);
        seedActivity(s, history, historySlots, "dorm_mitsuru_reading", 7, 5);
        seedActivity(s, history, historySlots, "dorm_aigis_reading", 9, 2);
        if (month == 10) {
            seedActivity(s, history, historySlots, "dorm_yukari_dvd", 9, 15);
            seedActivity(s, history, historySlots, "dorm_fuuka_reading", 10, 1);
            seedActivity(s, history, historySlots, "dorm_mitsuru_reading", 9, 13);
        }
        if (month == 10 && dom > 12) {
            seedActivity(s, history, historySlots, "group_study_10_08", 10, 8);
            seedActivity(s, history, historySlots, "group_study_10_11", 10, 11);
        }
        for (Rules.Link l : rules.links) {
            if (l.kind != Rules.LinkKind.MANUAL || s.rank[l.idx] == 0) continue;
            for (java.util.Map.Entry<Integer, Integer> req : l.unlock.statLv.entrySet()) {
                if (rules.stats[req.getKey()].levelFor(s.statPts[req.getKey()]) < req.getValue())
                    throw new AssertionError(id + " inconsistent opened stat gate: " + l.id);
            }
            for (java.util.Map.Entry<Integer, Integer> req : l.unlock.linkRank.entrySet()) {
                if (s.rank[req.getKey()] < req.getValue())
                    throw new AssertionError(id + " inconsistent opened link gate: " + l.id);
            }
        }
        f.start = s;
        f.provenance = new JSONObject().put("kind", "constructed representative checkpoint")
                .put("date", PlanReport.date(rules, s.slot)).put("checkpointIsNotFullHistory", true)
                .put("affinity", affinity).put("datedActivitySubset", history)
                .put("alreadyCompletedUnlockDates", "unknown; completed steps kept, lastStepDay=-1")
                .put("pastTowerBlockVisits", 2).put("lastTower", PlanReport.date(rules, s.lastTowerSlot))
                .put("forecastTowerIncome", 0).put("storyRanks", "actual rules story ranks granted by checkpoint date")
                .put("rescueHistory", month == 9 ? "Bunkichi pending, Maiko not yet missing"
                        : dom == 8 ? "Bunkichi saved on October2, Maiko not yet missing"
                        : "Bunkichi saved beforeOctober3; Maiko missing sinceOctober21 and still pending")
                .put("goalOrder", orderedGoalIds(f.profile));
        return f;
    }

    private static Profile profile(boolean sundayFirst) {
        Profile p = new Profile();
        p.personaMode = Profile.PersonaMode.GOALS;
        String[] order = sundayFirst
                ? new String[]{"sun", "hermit", "priestess", "empress", "lovers", "justice", "strength", "hanged_man", "aeon"}
                : GOALS;
        for (int i = 0; i < order.length; i++) {
            int l = rules.linkIndex(order[i]);
            p.tier[l] = Profile.Tier.MUST;
            p.order[l] = i;
            p.target[l] = 10;
        }
        Set<String> expectedWomen = new HashSet<>(Arrays.asList(GOALS));
        expectedWomen.remove("sun");
        for (Rules.Link l : rules.links) {
            if (l.romanceable) p.route[l.idx] = Profile.Route.FRIEND;
            if ("女".equals(l.genderZh) && !expectedWomen.remove(l.id))
                throw new AssertionError("female link absent from goal list: " + l.id);
        }
        if (!expectedWomen.isEmpty()) throw new AssertionError("goal list contains nonfemale links: " + expectedWomen);
        return p;
    }

    private static void seedActivity(SimState s, JSONArray history, Set<Integer> occupied, String id,
                                     int month, int dom) throws Exception {
        int day = dayOf(month, dom), slot = Rules.slot(day, true);
        Rules.Activity a = rules.activity(id);
        Rules.Day d = rules.day(day);
        if (slot >= s.slot || a == null || !a.eve || d.eveLock != null || day < a.fromDay || day > a.toDay
                || !a.allowsWeekday(d.wd) || a.fixedDays != null && Arrays.stream(a.fixedDays).noneMatch(v -> v == day)
                || !occupied.add(slot)) throw new AssertionError("invalid dated history " + id + " " + d.md);
        s.used[a.idx]++;
        history.put(new JSONObject().put("slot", slot).put("date", PlanReport.date(rules, slot))
                .put("action", "act:" + id).put("result", "completed; its gains are already included in checkpoint totals"));
    }

    private static void scenario(Fixture f) throws Exception {
        Files.writeString(output.resolve(f.id + "-profile.json"), f.profile.toJson().toString(2));
        Files.writeString(output.resolve(f.id + "-observed.json"), f.start.toJson().toString(2));
        Files.writeString(output.resolve(f.id + "-provenance.json"), f.provenance.toString(2));
        Plan normal, modify;
        if ("sep14-sunday-first".equals(f.id)) {
            modify = run(f.id, "initial-deep12", f.profile, f.start, null, null, 12_000, true, null);
            normal = run(f.id, "initial-normal4", f.profile, f.start, null, null, 4_000, false, null);
        } else {
            normal = run(f.id, "initial-normal4", f.profile, f.start, null, null, 4_000, false, null);
            modify = run(f.id, "initial-deep12", f.profile, f.start, null, null, 12_000, true, normal);
        }
        compare(f.id, "initial-modify", f.profile, f.start, normal, modify);
        Plan full = run(f.id, "initial-full8", f.profile, f.start, null, null, 8_000, false, null);
        Plan deep = run(f.id, "initial-deep30", f.profile, f.start, null, normal, 30_000, true, normal);
        compare(f.id, "manual-full", f.profile, f.start, full, deep);

        SimState expected = week(f, deep, false);
        Plan expectedNormal = run(f.id, "advanced-normal4", f.profile, expected, deep, null, 4_000, false, null);
        Plan expectedDeep = run(f.id, "advanced-deep12", f.profile, expected, deep, null, 12_000, true, expectedNormal);
        compare(f.id, "expected-week", f.profile, expected, expectedNormal, expectedDeep);

        SimState deviated = week(f, deep, true);
        Plan deviatedNormal = run(f.id, "deviated-normal4", f.profile, deviated, deep, null, 4_000, false, null);
        Plan deviatedDeep = run(f.id, "deviated-deep12", f.profile, deviated, deep, null, 12_000, true, deviatedNormal);
        compare(f.id, "deviated-week", f.profile, deviated, deviatedNormal, deviatedDeep);
    }

    /** Records a whole calendar week. Old route steps are rechecked against each actual state. */
    private static SimState week(Fixture f, Plan route, boolean deviation) throws Exception {
        SimState state = f.start.copy();
        int end = state.slot + 14;
        boolean alternativeDone = false, rankFailureDone = false;
        JSONArray ledger = new JSONArray();
        while (state.slot < end) {
            Plan.Step planned = route.at(state.slot);
            if (planned == null) throw new AssertionError("missing observed-week step");
            Action original = Action.parse(rules, planned.action);
            List<Action> legal = Engine.legalActions(rules, f.profile, state);
            Rules.Day day = rules.day(state.slot >> 1);
            String lock = (state.slot & 1) == 1 ? day.eveLock : day.dayLock;
            Action actual = original;
            String reason = "followed original deep plan";
            if (lock != null) {
                actual = Action.locked(lock);
            } else if (original == null || original.type != Action.Type.FREE && !legal.contains(original)) {
                actual = Action.free();
                reason = "old action no longer legal after actual records; took a free slot";
                if (!deviation) throw new AssertionError(f.id + " expected route became illegal: " + planned.action);
            }
            if (deviation && !alternativeDone && lock == null && (state.slot & 1) == 1) {
                Action alternative = eveningAlternative(legal, original);
                if (alternative != null) {
                    actual = alternative;
                    reason = "player chose a different legal evening activity";
                    alternativeDone = true;
                }
            }
            List<String> requestedSide = actual.equals(original) ? planned.side : null;
            Engine.SideSteps side = Engine.sideSteps(rules, f.profile, state, actual, requestedSide);
            Outcome outcome = Outcome.of(Outcome.Code.AS_PREDICTED);
            outcome.sideKeys = side.applied;
            Engine.Applied forecast = Engine.apply(rules, f.profile, state, actual);
            if (deviation && !rankFailureDone && actual.type == Action.Type.MEET && f.profile.isGoal(actual.target)
                    && forecast.predicted.rankUp && !forecast.predicted.certain
                    && rules.links[actual.target].threshold[state.rank[actual.target] + 1] > 0) {
                outcome.code = Outcome.Code.NO_RANK_UP;
                reason = "player completed a point-gated goal meeting but observed no predicted rank-up";
                rankFailureDone = true;
            } else if (actual.type == Action.Type.MEET || actual.type == Action.Type.UNLOCK_STEP) {
                // Preserve the visible result if this ledger is replayed under later profile settings.
                outcome.rank = forecast.state.rank[actual.target];
            }
            // AS_PREDICTED already applies the action's stats and the day's automatic gain.
            // A post-rollover visible level must not be clamped before that automatic gain again.
            if (actual.type == Action.Type.ACTIVITY) {
                Rules.Activity activity = rules.activities[actual.target];
                int gain = Engine.activityAffinityGain(rules, side.state, actual.target);
                if (gain > 0 && activity.affinityLink >= 0) {
                    outcome.activityAffinityLink = rules.links[activity.affinityLink].id;
                    outcome.activityAffinityPts = gain;
                }
            }
            SimState after = Engine.applyOutcome(rules, f.profile, state, actual, outcome);
            if (deviation && state.slot == end - 1) {
                outcome.cashBalance = Math.max(0, after.cash - 5_000);
                after = Engine.applyOutcome(rules, f.profile, state, actual, outcome);
                reason += "; actual cash corrected down by5000 (minimum0)";
            }
            // The real store records this beside applyOutcome (Replayer), because forecast
            // transitions deliberately never update the observed latest tower evening.
            if (actual.type == Action.Type.TARTARUS) after.lastTowerSlot = state.slot;
            JSONObject serialized = outcome.toJson();
            // Outcome's ordinary serializer omits replay-only affinity; keep it explicit in this ledger.
            if (outcome.activityAffinityLink != null) serialized.put("activityAffinityLink", outcome.activityAffinityLink)
                    .put("activityAffinityPts", outcome.activityAffinityPts);
            ledger.put(new JSONObject().put("slot", state.slot).put("date", PlanReport.date(rules, state.slot))
                    .put("plannedAction", planned.action).put("actualAction", actual.key(rules)).put("reason", reason)
                    .put("productiveActionLegal", !actual.isProductive() || legal.contains(actual))
                    .put("rejectedOldSideSteps", new JSONArray(side.rejected)).put("outcome", serialized)
                    .put("before", state.toJson()).put("after", after.toJson()));
            if (actual.isProductive() && !legal.contains(actual)) throw new AssertionError("illegal recorded action");
            if (after.slot != state.slot + 1) throw new AssertionError("record skipped a slot");
            state = after;
        }
        if (deviation && (!alternativeDone || !rankFailureDone)) {
            MidgameDeepBenchmark.issues++;
            System.out.println("DEVIATION_INCOMPLETE " + f.id + " alternative=" + alternativeDone + " rankFailure=" + rankFailureDone);
        }
        String suffix = deviation ? "deviated" : "advanced";
        Files.writeString(output.resolve(f.id + "-" + suffix + "-ledger.json"), new JSONObject()
                .put("startSlot", f.start.slot).put("endSlot", end).put("calendarDays", 7)
                .put("deviation", deviation).put("alternativeDone", alternativeDone).put("rankFailureDone", rankFailureDone)
                .put("records", ledger).toString(2));
        Files.writeString(output.resolve(f.id + "-" + suffix + "-observed.json"), state.toJson().toString(2));
        return state;
    }

    private static Action eveningAlternative(List<Action> legal, Action planned) {
        return legal.stream().filter(a -> a.type == Action.Type.ACTIVITY && !a.equals(planned))
                .sorted(Comparator.comparingInt((Action a) -> "study_room".equals(rules.activities[a.target].id) ? 0 : 1)
                        .thenComparing(a -> a.key(rules))).findFirst().orElse(null);
    }

    private static void compare(String id, String kind, Profile profile, SimState state,
                                Plan baseline, Plan expanded) throws Exception {
        Planner.Pool evaluator = pool(profile, state);
        Planner.Candidate a = evaluator.admitIncumbent(baseline), b = evaluator.admitIncumbent(expanded);
        if (a == null || b == null) throw new AssertionError("unreplayable comparison " + id + " " + kind);
        JSONArray before = new JSONArray(), after = new JSONArray(), goals = new JSONArray();
        int beforeMax = 0, afterMax = 0, beforeDeficit = 0, afterDeficit = 0;
        for (String key : GOALS) {
            int l = rules.linkIndex(key), ar = baseline.summary.endRanks[l], br = expanded.summary.endRanks[l];
            before.put(ar); after.put(br);
            if (ar >= profile.target[l]) beforeMax++;
            if (br >= profile.target[l]) afterMax++;
            beforeDeficit += Math.max(0, profile.target[l] - ar);
            afterDeficit += Math.max(0, profile.target[l] - br);
            goals.put(new JSONObject().put("id", key).put("priority", profile.order[l]).put("baseline", ar).put("expanded", br)
                    .put("baselineMaxDate", maxDate(baseline, l)).put("expandedMaxDate", maxDate(expanded, l)));
        }
        JSONArray diff = new JSONArray();
        JSONObject first = null;
        for (int slot = state.slot; slot <= rules.lastSlot(); slot++) {
            Plan.Step as = baseline.at(slot), bs = expanded.at(slot);
            if (as == null || bs == null) throw new AssertionError("incomplete comparison route");
            if (!as.action.equals(bs.action) || !as.side.equals(bs.side)) {
                JSONObject d = new JSONObject().put("slot", slot).put("date", PlanReport.date(rules, slot))
                        .put("baseline", as.action).put("expanded", bs.action)
                        .put("baselineSide", new JSONArray(as.side)).put("expandedSide", new JSONArray(bs.side));
                if (first == null) first = d;
                if (slot < state.slot + 28) diff.put(d);
            }
        }
        JSONObject row = new JSONObject().put("scenario", id).put("comparison", kind)
                .put("start", PlanReport.date(rules, state.slot)).put("startSlot", state.slot)
                .put("baselinePlanId", baseline.planId).put("expandedPlanId", expanded.planId)
                .put("baselineElapsedMs", baseline.elapsedMillis).put("expandedElapsedMs", expanded.elapsedMillis)
                .put("baselineBudgetExhausted", baseline.budgetExhausted).put("expandedBudgetExhausted", expanded.budgetExhausted)
                .put("goalVectorOrder", new JSONArray(GOALS)).put("goalPriorityOrder", orderedGoalIds(profile))
                .put("baselineGoalRanks", before).put("expandedGoalRanks", after)
                .put("baselineGoalCompleted", beforeMax).put("expandedGoalCompleted", afterMax)
                .put("baselineGoalDeficit", beforeDeficit).put("expandedGoalDeficit", afterDeficit)
                .put("goals", goals).put("goalComparator", Integer.signum(Planner.Pool.compareGoals(b, a)))
                .put("baselineQuality", quality(a)).put("expandedQuality", quality(b)).put("scoreDelta", b.score - a.score)
                .put("qualityRelation", worse(b, a) ? "WORSE" : worse(a, b) ? "BETTER" : "SAME")
                .put("first14DaysActionDiffCount", diff.length()).put("first14DaysActionDiff", diff)
                .put("firstDifference", first == null ? JSONObject.NULL : first);
        Files.writeString(output.resolve("comparisons.ndjson"), row.toString() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        System.out.println("COMPARISON " + id + " " + kind + " goals=" + before + " -> " + after
                + " completed=" + beforeMax + " -> " + afterMax + " deficit=" + beforeDeficit + " -> " + afterDeficit
                + " quality=" + row.getString("qualityRelation") + " scoreDelta=" + (b.score - a.score)
                + " first14DaysDiff=" + diff.length());
        System.out.flush();
    }

    private static String maxDate(Plan p, int link) {
        return p.summary.maxSlot[link] < 0 ? "—" : PlanReport.date(rules, p.summary.maxSlot[link]);
    }

    private static JSONArray orderedGoalIds(Profile p) {
        JSONArray a = new JSONArray();
        for (int l : Heuristics.goalOrder(new Model(rules, p, null))) a.put(rules.links[l].id);
        return a;
    }

    private static int dayOf(int month, int dom) {
        for (int day = rules.firstDay; day <= rules.lastDay; day++) {
            Rules.Day d = rules.day(day);
            if (d != null && d.month() == month && d.dayOfMonth() == dom) return day;
        }
        throw new AssertionError("date outside rules: " + month + "/" + dom);
    }
}
