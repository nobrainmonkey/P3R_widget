package app.p3r.dayplanner.v3.engine;

import static app.p3r.dayplanner.v3.engine.RulesFixtures.CHARM;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_DEVIL;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_HIEROPHANT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Planner behavior: coverage, validity, determinism, priorities, stability, budget, money, Tartarus. */
public class PlannerTest {

    private static Engine.Options generous() {
        Engine.Options o = new Engine.Options();
        o.timeBudgetMillis = 60_000;
        return o;
    }

    private static void assertValid(Rules rules, Profile profile, SimState start, Plan plan) {
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        List<String> warnings = Engine.validationWarnings(rules, profile, start, plan);
        assertTrue(warnings.toString(), warnings.isEmpty());
        assertEquals(start.slot, plan.fromSlot);
        assertEquals(rules.lastSlot(), plan.toSlot);
        assertEquals(rules.lastSlot() - start.slot + 1, plan.steps.size());
        for (int i = 0; i < plan.steps.size(); i++) assertEquals(start.slot + i, plan.steps.get(i).slot);
    }

    /** Forecast state before {@code slot}, replaying the plan's own actions through the public API. */
    private static SimState stateBefore(Rules rules, Profile profile, SimState start, Plan plan, int slot) {
        SimState s = start;
        for (Plan.Step step : plan.steps) {
            if (step.slot >= slot) break;
            Action a = Action.parse(rules, step.action);
            s = Engine.apply(rules, profile, s, a.type == Action.Type.LOCKED ? Action.free() : a).state;
        }
        return s;
    }

    // ------------------------------------------------------------------ coverage and validity

    @Test
    public void smallPlanCoversEverySlotAndValidates() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        Plan.Step locked = plan.at(Rules.slot(21, false));
        assertEquals("locked", locked.kind);
        assertEquals("locked:story", locked.action);
        assertEquals("剧情事件", locked.titleZh);
        assertEquals("满月作战", plan.at(Rules.slot(38, true)).titleZh);
        assertEquals(9, plan.summary.lockedSlots);
        assertTrue(plan.summary.usefulActions > 50);
        assertEquals(rules.version, plan.rulesVersion);
    }

    @Test
    public void planFromTheLastSlotAndBeyond() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        SimState last = RulesFixtures.newGame(rules, rules.lastSlot());
        Plan one = Engine.plan(rules, profile, last, null, generous());
        assertEquals(1, one.steps.size());
        assertValid(rules, profile, last, one);
        SimState after = RulesFixtures.newGame(rules, rules.lastSlot() + 1);
        assertTrue(Engine.plan(rules, profile, after, null, generous()).steps.isEmpty());
    }

    @Test
    public void observedStateIsNotMutated() throws JSONException {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.must(4);
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        String before = start.toJson().toString();
        String profileBefore = profile.toJson().toString();
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        Engine.validate(rules, profile, start, plan);
        assertEquals(before, start.toJson().toString());
        assertEquals(profileBefore, profile.toJson().toString());
    }

    @Test
    public void deterministicForIdenticalInputs() throws JSONException {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Plan a = Engine.plan(rules, profile, start, null, generous());
        Plan b = Engine.plan(rules, profile, start.copy(), null, generous());
        assertEquals(a.planId, b.planId);
        assertEquals(16, a.planId.length());
        assertEquals(stepsJson(a), stepsJson(b));
        assertEquals(a.toJson().getJSONObject("summary").toString(), b.toJson().getJSONObject("summary").toString());

        Profile other = RulesFixtures.must(6);
        assertFalse(a.planId.equals(Engine.plan(rules, other, start, null, generous()).planId));
    }

    private static String stepsJson(Plan p) throws JSONException {
        return p.toJson().getJSONArray("steps").toString();
    }

    @Test
    public void planJsonRoundTrips() throws JSONException {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        JSONObject json = plan.toJson();
        Plan back = Plan.fromJson(new JSONObject(json.toString()));
        assertEquals(json.toString(), back.toJson().toString());
        assertTrue(Engine.validate(rules, profile, start, back).isEmpty());
    }

    // ------------------------------------------------------------------ never idle when something useful is possible

    @Test
    public void noFreeSlotWhileAProductiveActionIsLegal() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        int checked = 0;
        for (Plan.Step step : plan.steps) {
            if (!"free".equals(step.kind)) continue;
            SimState before = stateBefore(rules, profile, start, plan, step.slot);
            assertTrue(PlanReport.date(rules, step.slot) + " " + Engine.legalActions(rules, profile, before),
                    Engine.legalActions(rules, profile, before).isEmpty());
            checked++;
        }
        assertTrue("the synthetic year has idle evenings late in the game", checked > 0);
    }

    // ------------------------------------------------------------------ priorities

    @Test
    public void mustGoalOutranksOtherLinks() {
        Rules rules = RulesFixtures.fullYear();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        int lovers = 6;
        Plan othersPlan = Engine.plan(rules, RulesFixtures.others(), start, null, generous());
        Profile must = RulesFixtures.must(lovers);
        Plan mustPlan = Engine.plan(rules, must, start, null, generous());
        assertValid(rules, must, start, mustPlan);
        assertEquals(10, mustPlan.summary.endRanks[lovers]);
        assertTrue(mustPlan.summary.maxSlot[lovers] >= 0);
        if (othersPlan.summary.endRanks[lovers] >= 10) {
            assertTrue("the goal is reached earlier", mustPlan.summary.maxSlot[lovers] <= othersPlan.summary.maxSlot[lovers]);
        }
        String note = mustPlan.summary.notesZh.get(0);
        assertTrue(note, note.startsWith("恋爱：预计") && note.endsWith("练满"));
    }

    @Test
    public void mustBeatsWantWhenTheyCompete() {
        Rules rules = RulesFixtures.fullYear();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Profile p = RulesFixtures.others();
        // Priestess and Lovers both arrive late and compete for daytime slots.
        p.tier[6] = Profile.Tier.MUST;
        p.tier[2] = Profile.Tier.WANT;
        Plan plan = Engine.plan(rules, p, start, null, generous());
        assertValid(rules, p, start, plan);
        assertEquals(10, plan.summary.endRanks[6]);
        assertTrue(plan.summary.endRanks[6] >= plan.summary.endRanks[2]);
    }

    // ------------------------------------------------------------------ stability after a matching outcome

    @Test
    public void matchingOutcomeKeepsTheNextDaysUnchanged() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(5, 4, false));
        start.rank[1] = 3;
        start.rank[5] = 2;
        start.rank[9] = 1;
        start.statPts[CHARM] = 15;
        Plan first = Engine.plan(rules, profile, start, null, generous());
        Plan.Step done = first.steps.get(0);
        SimState observed = Engine.applyOutcome(rules, profile, start, Action.parse(rules, done.action), Outcome.of(Outcome.Code.AS_PREDICTED));
        Plan second = Engine.plan(rules, profile, observed, first, generous());
        assertValid(rules, profile, observed, second);
        for (int slot = observed.slot; slot < observed.slot + 6; slot++) {
            assertEquals(PlanReport.date(rules, slot), first.at(slot).action, second.at(slot).action);
        }
    }

    // ------------------------------------------------------------------ budget, cancellation, progress

    @Test
    public void tinyTimeBudgetStillGivesACompleteValidPlan() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Engine.Options o = new Engine.Options();
        o.timeBudgetMillis = 1;
        long t0 = System.nanoTime();
        Plan plan = Engine.plan(rules, profile, start, null, o);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertValid(rules, profile, start, plan);
        assertTrue(plan.budgetExhausted);
        assertTrue("took " + ms + " ms", ms < 1_000);

        Engine.Options none = new Engine.Options();
        none.timeBudgetMillis = 0;
        Plan base = Engine.plan(rules, profile, start, null, none);
        assertValid(rules, profile, start, base);
    }

    @Test
    public void cancellationStopsSearchingButKeepsThePlanComplete() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Engine.Options o = generous();
        o.cancelled = new AtomicBoolean(true);
        Plan plan = Engine.plan(rules, profile, start, null, o);
        assertValid(rules, profile, start, plan);
        assertTrue(plan.budgetExhausted);
    }

    @Test
    public void progressIsMonotoneAndEndsAtOne() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        List<Float> seen = new ArrayList<>();
        Engine.Options o = generous();
        o.progress = seen::add;
        Engine.plan(rules, profile, start, null, o);
        assertFalse(seen.isEmpty());
        for (int i = 1; i < seen.size(); i++) assertTrue(seen.get(i) >= seen.get(i - 1));
        assertEquals(1f, seen.get(seen.size() - 1), 0f);
    }

    // ------------------------------------------------------------------ Tartarus and rescues

    @Test
    public void requiredTowerVisitsAndTheRescueArePlanned() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        int[] visits = towerVisits(rules, plan);
        assertTrue(visits[0] >= 2 && visits[0] <= 4);
        assertTrue(visits[1] >= 2 && visits[1] <= 4);
        // The first visit inside the missing window is the rescue; later (spare) visits under the cap
        // do not claim it again.
        boolean rescue = false;
        for (Plan.Step s : plan.steps) {
            int day = s.slot >> 1;
            if ("tower".equals(s.kind) && day >= 40 && day <= 45) {
                assertEquals(!rescue, s.subtitleZh.contains("救出北村文吉"));
                assertEquals(!rescue, s.assumptions.contains("rescue_on_visit"));
                rescue = true;
            }
        }
        assertTrue(rescue);
        assertTrue("hierophant keeps going after the rescue", plan.summary.endRanks[S_HIEROPHANT] >= 5);
    }

    @Test
    public void towerCapOneStillLeavesRoomForTheRescue() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        profile.towerVisitsPerBlock = 1;
        profile.towerCapPerBlock = 1;
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        int[] visits = towerVisits(rules, plan);
        assertEquals(1, visits[0]);
        assertEquals(1, visits[1]);
        Plan.Step rescueStep = null;
        for (Plan.Step s : plan.steps) if ("tower".equals(s.kind) && s.slot >> 1 >= 38) rescueStep = s;
        assertNotNull(rescueStep);
        int day = rescueStep.slot >> 1;
        assertTrue("the only block-1 visit is the rescue", day >= 40 && day <= 45);
    }

    @Test
    public void towerCapZeroMeansNoVisitsAndTheMissingLinkIsLost() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        profile.towerVisitsPerBlock = 0;
        profile.towerCapPerBlock = 0;
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        for (Plan.Step s : plan.steps) {
            assertFalse("tower".equals(s.kind));
            if (s.slot >> 1 > 45) assertFalse(s.action.equals("meet:hierophant"));
        }
    }

    private static int[] towerVisits(Rules rules, Plan plan) {
        int[] visits = new int[rules.blocks.length];
        for (Plan.Step s : plan.steps) {
            if ("tower".equals(s.kind)) visits[Action.parse(rules, s.action).target]++;
        }
        return visits;
    }

    // ------------------------------------------------------------------ money

    @Test
    public void jobsFundPaidUnlocksWithoutOverspending() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.must(S_DEVIL);
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        start.cash = 10_000;
        start.statPts[CHARM] = 15;
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        assertTrue("devil established", plan.summary.endRanks[S_DEVIL] >= 1);
        int firstPayment = -1;
        int shiftsBefore = 0;
        for (Plan.Step s : plan.steps) {
            if (s.action.startsWith("unlock:devil") && firstPayment < 0) firstPayment = s.slot;
            if ("job".equals(s.kind) && firstPayment < 0) shiftsBefore++;
            assertTrue(s.predicted.cashAfter >= 0);
        }
        assertTrue(firstPayment > 0);
        assertTrue("jobs come first", shiftsBefore >= 3);
    }

    @Test
    public void noMoneyMeansNoPaidActivities() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        profile.excludedActivities = new String[]{"job_chagall"};
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        for (Plan.Step s : plan.steps) {
            assertFalse(s.action.equals("act:arcade_charm"));
            assertFalse(s.action.equals("act:job_chagall"));
        }
    }

    // ------------------------------------------------------------------ full-horizon scenarios (synthetic year)

    @Test
    public void earlyGameGoalPlusOthersRunsToTheEndOfJanuary() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.must(4); // 皇帝
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        assertEquals(10, plan.summary.endRanks[4]);
        assertTrue(plan.summary.lastUsefulSlot >= RulesFixtures.slot(1, 18, false));
        assertTrue(plan.summary.usefulActions > 400);
    }

    @Test
    public void midGameRunsToTheEndOfJanuary() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.others();
        SimState start = midGame(rules);
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        PlanReport.print("synthetic mid game (Oct 22)", rules, plan, 0);
        assertValid(rules, profile, start, plan);
        assertTrue(plan.summary.lastUsefulSlot >= RulesFixtures.slot(1, 18, false));
        assertTrue(plan.summary.endRanks[1] > 2);
        assertEquals(10, plan.summary.endRanks[19]);
    }

    /** Oct 22: Magician 2, Sun 7, ¥1,000, Charm Lv3, a typical spread of other links. */
    static SimState midGame(Rules rules) {
        SimState s = RulesFixtures.newGame(rules, RulesFixtures.slot(10, 22, false));
        s.cash = 1_000;
        s.statPts[RulesFixtures.ACADEMICS] = 55;
        s.statPts[CHARM] = 30;
        s.statPts[RulesFixtures.COURAGE] = 45;
        int[][] ranks = {{0, 7}, {1, 2}, {4, 6}, {5, 5}, {7, 6}, {9, 5}, {11, 4}, {12, 3}, {13, 5}, {14, 3}, {16, 2}, {17, 3}, {18, 4}, {19, 7}};
        for (int[] r : ranks) s.rank[r[0]] = r[1];
        s.rescued = 1; // Bunkichi was rescued
        s.towerVisits[6] = 1;
        return s;
    }

    @Test
    public void openEndedHoldIsExplainedInTheNotes() {
        Rules rules = RulesFixtures.small();
        Rules.Hold hold = new Rules.Hold();
        hold.rank = 5;
        hold.untilDay = -1;
        hold.zh = "需要先听到学校里的传闻";
        rules.links[RulesFixtures.S_MAGICIAN].holds.add(hold);
        Profile profile = RulesFixtures.must(RulesFixtures.S_MAGICIAN);
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        assertEquals(4, plan.summary.endRanks[RulesFixtures.S_MAGICIAN]);
        assertEquals("魔术师：RANK 5 暂时无法升级（需要先听到学校里的传闻），先规划到 RANK 4", plan.summary.notesZh.get(0));
    }

    @Test
    public void playerReportedHoldStopsForecastRankUps() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.must(RulesFixtures.S_MAGICIAN);
        SimState start = RulesFixtures.newGame(rules, Rules.slot(22, false));
        start.rank[RulesFixtures.S_MAGICIAN] = 4;
        start.pts[RulesFixtures.S_MAGICIAN] = 25;
        start.heldRank[RulesFixtures.S_MAGICIAN] = 5;
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        assertEquals(4, plan.summary.endRanks[RulesFixtures.S_MAGICIAN]);
        assertEquals("魔术师：RANK 5 暂时无法升级（游戏里有提示），先规划到 RANK 4", plan.summary.notesZh.get(0));
        int held = 0;
        int socialSteps = 0;
        for (Plan.Step s : plan.steps) {
            if (s.action.equals("meet:magician")) held++;
            if ("social".equals(s.kind) || "unlock".equals(s.kind)) socialSteps++;
        }
        assertTrue("held meetings only fill otherwise empty daytimes: " + held + " of " + socialSteps, held * 2 <= socialSteps);
    }

    // ------------------------------------------------------------------ alternatives (改做别的)

    @Test
    public void alternativesAreTheLegalActionsWithLabels() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        SimState monday = RulesFixtures.newGame(rules, Rules.slot(26, true));
        monday.cash = 2_000;
        List<Plan.Step> alts = Engine.alternatives(rules, profile, monday);
        List<Action> legal = Engine.legalActions(rules, profile, monday);
        assertEquals(legal.size(), alts.size());
        for (Plan.Step s : alts) {
            assertTrue(legal.contains(Action.parse(rules, s.action)));
            assertEquals(monday.slot, s.slot);
            assertFalse(s.titleZh.isEmpty());
            assertNotNull(s.whyZh);
        }
        Plan.Step job = null;
        for (Plan.Step s : alts) if (s.action.equals("act:job_chagall")) job = s;
        assertNotNull(job);
        assertEquals("咖啡店「夏卡尔」打工", job.titleZh);
        assertEquals("打工 · 预计 +2,500 日元", job.subtitleZh);
        assertEquals(2_500, job.predicted.cashDelta);
        assertTrue(Engine.alternatives(rules, profile, RulesFixtures.newGame(rules, Rules.slot(21, false))).isEmpty());
    }

    // ------------------------------------------------------------------ copy

    @Test
    public void stepTextIsChineseWithoutInternalIds() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.must(4);
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        List<String> ids = new ArrayList<>();
        for (Rules.Link l : rules.links) ids.add(l.id);
        for (Rules.Activity a : rules.activities) ids.add(a.id);
        for (Rules.Block b : rules.blocks) ids.add(b.id);
        for (Plan.Step s : plan.steps) {
            String text = s.titleZh + "|" + s.subtitleZh + "|" + s.whyZh + "|" + s.locationZh;
            assertFalse(text, s.titleZh.isEmpty());
            assertFalse(text, s.subtitleZh.isEmpty());
            assertFalse(text, text.contains("act:") || text.contains("meet:") || text.contains("unlock:"));
            for (String id : ids) assertFalse(text + " contains " + id, text.contains(id));
            if (s.productive()) assertNotNull(text, s.whyZh);
        }
        for (String note : plan.summary.notesZh) {
            for (String id : ids) assertFalse(note, note.contains(id));
        }
    }
}
