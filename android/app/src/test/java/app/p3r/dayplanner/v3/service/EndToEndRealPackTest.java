package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * Whole-system check: real rule pack + real engine + real SQLite ledger, driven the way the UI and
 * widget drive it. Covers following the plan, deviations (no rank-up, skip), a tower trip and its
 * correction, stale and out-of-order writes, and a weeks-later sync. Every installed plan must be
 * validator-clean and cover every slot to the end of the calendar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EndToEndRealPackTest {
    private Context context;
    private Rules rules;
    private PlannerService service;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        File pack = new File("src/main/assets/p3r/p3r_rules.json");
        rules = RulesLoader.parse(new String(Files.readAllBytes(pack.toPath()), StandardCharsets.UTF_8));
        Wiring wiring = new Wiring();
        wiring.rules = () -> rules;
        wiring.planBudgetMillis = 2500;
        wiring.databaseName = "e2e-" + UUID.randomUUID() + ".db";
        service = PlannerService.create(context, wiring);
    }

    @After
    public void tearDown() {
        service.close();
    }

    @Test
    public void followDeviateCorrectAndSyncKeepsEveryPlanValidAndComplete() {
        Profile profile = new Profile();
        profile.tier[rules.link("emperor").idx] = Profile.Tier.MUST;
        profile.tier[rules.link("lovers").idx] = Profile.Tier.WANT;
        SimState start = newGame(Rules.slot(20, false));
        PlannerApi.Receipt r = service.saveCheckpoint(id(), service.snapshot().stateRevision, start, profile);
        assertTrue(r.errorZh, r.ok);
        Plan first = settledPlan("onboarding");
        print("onboarding", first);

        // Follow the plan for a while, accepting predicted results.
        int recorded = 0;
        while (recorded < 40) {
            PlannerApi.Snapshot s = service.snapshot();
            Plan.Step step = s.plan.at(s.observed.slot);
            assertNotNull("plan has no step for the observed slot", step);
            assertTrue("observed slot must be actionable: " + step.kind, step.productive());
            PlannerApi.Receipt rr = "tower".equals(step.kind)
                    ? service.recordTower(id(), s.stateRevision, tower(step.slot, rules, s.observed, 18, 3_000))
                    : service.recordOutcome(id(), s.stateRevision, step.slot, step.action, Outcome.of(Outcome.Code.AS_PREDICTED));
            assertTrue(rr.errorZh, rr.ok);
            recorded++;
            if (recorded % 10 == 0) settledPlan("after " + recorded + " records");
        }
        Plan followed = settledPlan("followed");

        // Deviation 1: the next social meeting does not rank up.
        PlannerApi.Snapshot s = service.snapshot();
        Plan.Step social = nextOfKind(s, "social");
        while (s.observed.slot < social.slot) {
            Plan.Step step = s.plan.at(s.observed.slot);
            PlannerApi.Receipt rr = "tower".equals(step.kind)
                    ? service.recordTower(id(), s.stateRevision, tower(step.slot, rules, s.observed, 20, 3_000))
                    : service.recordOutcome(id(), s.stateRevision, step.slot, step.action, Outcome.of(Outcome.Code.AS_PREDICTED));
            assertTrue(rr.errorZh, rr.ok);
            s = service.snapshot();
        }
        int link = rules.link(social.entity).idx;
        int rankBefore = s.observed.rank[link];
        assertTrue(service.recordOutcome(id(), s.stateRevision, social.slot, social.action,
                Outcome.of(Outcome.Code.NO_RANK_UP)).ok);
        Plan afterNoRankUp = settledPlan("after no rank-up");
        assertEquals(rankBefore, service.snapshot().observed.rank[link]);
        boolean meetsAgain = false;
        for (Plan.Step step : afterNoRankUp.steps) meetsAgain |= social.action.equals(step.action) && rankBefore < 10;
        assertTrue("the link is met again later", meetsAgain || rankBefore >= 10);

        // Deviation 2: skip the next slot.
        s = service.snapshot();
        int skipped = s.observed.slot;
        Outcome skip = Outcome.of(Outcome.Code.SKIPPED);
        skip.reason = "story";
        assertTrue(service.recordOutcome(id(), s.stateRevision, skipped, s.plan.at(skipped).action, skip).ok);
        settledPlan("after skip");
        assertTrue(service.snapshot().observed.slot > skipped);

        // Stale and out-of-order writes are rejected without side effects.
        s = service.snapshot();
        PlannerApi.Receipt stale = service.recordOutcome(id(), s.stateRevision - 1, s.observed.slot,
                s.plan.at(s.observed.slot).action, Outcome.of(Outcome.Code.AS_PREDICTED));
        assertFalse(stale.ok);
        assertEquals(PlannerApi.Receipt.CODE_STALE, stale.errorCode);
        PlannerApi.Receipt gap = service.recordOutcome(id(), s.stateRevision, s.observed.slot + 6, "free",
                Outcome.of(Outcome.Code.AS_PREDICTED));
        assertFalse(gap.ok);

        // A tower trip, then a correction that must not add a visit.
        s = service.snapshot();
        Plan.Step towerStep = nextOfKind(s, "tower");
        if (towerStep != null) {
            while (s.observed.slot < towerStep.slot) {
                Plan.Step step = s.plan.at(s.observed.slot);
                assertTrue(service.recordOutcome(id(), s.stateRevision, step.slot, step.action,
                        Outcome.of(Outcome.Code.AS_PREDICTED)).ok);
                s = service.snapshot();
            }
            int block = rules.day(Rules.dayOf(towerStep.slot)).block;
            int visitsBefore = s.observed.towerVisits[block];
            PlannerApi.Receipt t = service.recordTower(id(), s.stateRevision, tower(towerStep.slot, rules, s.observed, 30, 4_000));
            assertTrue(t.errorZh, t.ok);
            assertEquals(visitsBefore + 1, service.snapshot().observed.towerVisits[block]);
            PlannerApi.TowerReturn fix = tower(towerStep.slot, rules, s.observed, 33, 5_000);
            PlannerApi.Receipt c = service.correctTower(id(), service.snapshot().stateRevision, t.eventSeq, fix);
            assertTrue(c.errorZh, c.ok);
            assertEquals(visitsBefore + 1, service.snapshot().observed.towerVisits[block]);
            assertEquals(33, service.snapshot().observed.floor);
            settledPlan("after tower correction");
        }

        // Three weeks later: sync with a few changed visible values.
        s = service.snapshot();
        SimState later = Engine.advanceTo(rules, s.profile, s.observed, s.observed.slot + 42);
        later.rank[rules.link("magician").idx] = Math.min(10, later.rank[rules.link("magician").idx] + 1);
        later.cash = 12_000;
        later.cashExact = true;
        PlannerApi.Receipt sync = service.saveCheckpoint(id(), s.stateRevision, later, null);
        assertTrue(sync.errorZh, sync.ok);
        Plan synced = settledPlan("after sync");
        assertTrue(synced.fromSlot >= later.slot);

        // History reads naturally.
        List<PlannerApi.HistoryItem> history = service.history(200);
        assertFalse(history.isEmpty());
        for (PlannerApi.HistoryItem h : history) assertFalse(h.summaryZh, h.summaryZh.matches(".*[a-z_]{4,}.*"));
        System.out.println("followed plan: " + followed.summary.usefulActions + " useful actions");
    }

    // ------------------------------------------------------------------ helpers

    private SimState newGame(int slot) {
        SimState st = new SimState(rules.blocks.length, rules.activities.length);
        st.slot = slot;
        st.cash = 5_000;
        st.cashExact = true;
        return st;
    }

    private Plan settledPlan(String label) {
        assertTrue("planner did not go idle (" + label + ")", service.awaitIdle(60_000));
        shadowOf(Looper.getMainLooper()).idle();
        PlannerApi.Snapshot s = service.snapshot();
        assertNull(label + ": " + s.planErrorZh, s.planErrorZh);
        assertNotNull(label + ": no plan", s.plan);
        assertTrue(label + ": plan not current", s.planCurrent);
        Plan p = s.plan;
        assertEquals(label + ": plan starts at the observed slot", s.observed.slot, p.fromSlot);
        assertEquals(label + ": plan covers the calendar", rules.lastSlot(), p.toSlot);
        assertEquals(label + ": one step per slot", p.toSlot - p.fromSlot + 1, p.steps.size());
        List<String> problems = Engine.validate(rules, s.profile, s.observed, p);
        assertTrue(label + ": " + problems, problems.isEmpty());
        assertTrue(label + ": useful actions until late January",
                p.summary.lastUsefulSlot >= Rules.slot(rules.lastDay - 14, false));
        return p;
    }

    private static void assertNull(String message, Object o) {
        if (o != null) throw new AssertionError(message);
    }

    private static Plan.Step nextOfKind(PlannerApi.Snapshot s, String kind) {
        for (Plan.Step step : s.plan.steps) {
            if (step.slot >= s.observed.slot && kind.equals(step.kind)) return step;
        }
        return null;
    }

    private static PlannerApi.TowerReturn tower(int slot, Rules rules, SimState observed, int floor, long gain) {
        PlannerApi.TowerReturn t = new PlannerApi.TowerReturn();
        t.slot = slot;
        t.block = rules.day(Rules.dayOf(slot)).block;
        t.floor = Math.max(observed.floor, floor);
        t.balanceMode = false;
        t.amount = gain;
        return t;
    }

    private void print(String label, Plan p) {
        int maxed = 0;
        for (int r : p.summary.endRanks) if (r >= 10) maxed++;
        System.out.println("[" + label + "] steps " + p.steps.size() + " useful " + p.summary.usefulActions
                + " maxed " + maxed + "/22 elapsed " + p.elapsedMillis + " ms");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
