package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.List;
import java.util.UUID;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;
import app.p3r.dayplanner.v3.service.PlannerService;
import app.p3r.dayplanner.v3.widget.WidgetWorker;

/**
 * The quick result sheet on the production planner: the shipped rule pack, the real engine and the
 * real SQLite store (no fake). Drives the Emperor rank-up sheet (已升到 / 没升级) and the Tartarus
 * sheet (balance, net change, 不记得钱了, rescue) through {@link ResultActivity} and checks the
 * observed state the store keeps afterwards.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ResultRealServiceTest extends UiTestBase {
    private static final long SETTLE_MS = 60_000L;

    private Application app;
    private PlannerApi real;
    private Rules rules;

    @Before
    public void useTheRealService() {
        app = RuntimeEnvironment.getApplication();
        Api.setForTest(null);
        // Widget refreshes from the planner thread are not part of this test.
        WidgetWorker.setForTest(task -> { }, (task, delay) -> { });
        real = PlannerService.get(app);
        rules = real.rules();
        assertNotNull("the shipped rule pack did not load", rules);
        assertTrue("the real store is used", Api.get(app) == real);
    }

    @After
    public void resetWidgetWorker() {
        WidgetWorker.setForTest(null, null);
    }

    // ------------------------------------------------------------------ Emperor rank-up

    @Test
    public void emperorRankUpSheetStoresTheNewRank() throws Exception {
        int emperor = rules.linkIndex("emperor");
        Plan.Step step = onboardAtEmperorRankUp(emperor);
        int from = real.snapshot().observed.rank[emperor];
        ResultActivity a = open(step);
        assertTrue(Shots.has(a, "升到 RANK " + (from + 1) + " 了吗？"));
        Shots.shot(a, "result_real_emperor_rankup");
        Shots.click(a, "升到 RANK " + (from + 1) + " 了");
        assertTrue(a.isFinishing());

        PlannerApi.Snapshot after = real.snapshot();
        assertEquals(from + 1, after.observed.rank[emperor]);
        assertFalse(after.observed.ready[emperor]);
        assertTrue("the slot is recorded", after.observed.slot > step.slot);
        List<PlannerApi.HistoryItem> history = real.history(1);
        assertEquals("outcome", history.get(0).kind);
        assertTrue(history.get(0).summaryZh, history.get(0).summaryZh.contains("皇帝"));
        settled();
        assertEquals("the record survives the replan", from + 1, real.snapshot().observed.rank[emperor]);
    }

    @Test
    public void emperorNoRankUpKeepsTheRank() throws Exception {
        int emperor = rules.linkIndex("emperor");
        Plan.Step step = onboardAtEmperorRankUp(emperor);
        int from = real.snapshot().observed.rank[emperor];
        ResultActivity a = open(step);
        Shots.click(a, "没升级");
        Shots.click(a, "没有");
        assertTrue(a.isFinishing());

        PlannerApi.Snapshot after = real.snapshot();
        assertEquals(from, after.observed.rank[emperor]);
        assertFalse("没升级 without the cue clears it", after.observed.ready[emperor]);
        assertTrue(after.observed.slot > step.slot);
        assertEquals("outcome", real.history(1).get(0).kind);
    }

    /**
     * Onboards a state whose next slot is an Emperor rank-up (Emperor MUST at RANK 3 with the
     * rank-up cue) and returns the installed plan's step for it.
     */
    private Plan.Step onboardAtEmperorRankUp(int emperor) throws Exception {
        Profile profile = new Profile();
        Goals.setTier(profile, emperor, Profile.Tier.MUST);
        Rules.Link link = rules.links[emperor];
        for (int day = dayOf(6, 1); day <= dayOf(9, 30); day++) {
            int slot = Rules.slot(day, false);
            if (!link.meetsOn(day) || rules.lockOf(slot) != null) {
                continue;
            }
            SimState s = midGame(slot);
            s.rank[emperor] = 3;
            s.unlockStep[emperor] = link.unlock.steps.size();
            s.ready[emperor] = true;
            Plan.Step first = firstStep(profile, s);
            if (first == null || !"meet:emperor".equals(first.action) || !first.predicted.rankUp) {
                continue;
            }
            Plan.Step installed = onboard(s, profile);
            if ("meet:emperor".equals(installed.action) && installed.predicted.rankUp) {
                return installed;
            }
        }
        throw new AssertionError("no day where the plan starts with an Emperor rank-up");
    }

    // ------------------------------------------------------------------ Tartarus

    @Test
    public void towerSheetBalanceModeStoresTheNewBalance() throws Exception {
        Plan.Step step = onboardAtTowerVisit(false);
        SimState before = real.snapshot().observed;
        int block = blockOf(step);
        ResultActivity a = open(step);
        TowerPanel panel = a.towerPanel();
        assertNotNull(panel);
        assertFalse("the amount starts empty", panel.ready());
        int floor = panel.floorStepper().getValue() + 2;
        panel.floorStepper().step(+2);
        panel.setAmountForTest(31_500);
        Shots.shot(a, "result_real_tower_balance");
        Shots.click(a, "保存");
        assertTrue(a.isFinishing());

        SimState after = real.snapshot().observed;
        assertEquals(31_500, after.cash);
        assertTrue(after.cashExact);
        assertEquals(before.towerVisits[block] + 1, after.towerVisits[block]);
        assertEquals(Math.max(before.floor, floor), after.floor);
        assertTrue(after.slot > step.slot);
        assertEquals("tower", real.history(1).get(0).kind);
    }

    @Test
    public void towerSheetNetModeAddsTheChange() throws Exception {
        Plan.Step step = onboardAtTowerVisit(false);
        SimState before = real.snapshot().observed;
        int block = blockOf(step);
        ResultActivity a = open(step);
        Shots.click(a, "这次多了多少");
        a.towerPanel().setAmountForTest(4_200);
        Shots.click(a, "保存");

        SimState after = real.snapshot().observed;
        assertEquals(before.cash + 4_200, after.cash);
        assertEquals(before.towerVisits[block] + 1, after.towerVisits[block]);
    }

    @Test
    public void towerSheetForgotCashRecordsNoChange() throws Exception {
        Plan.Step step = onboardAtTowerVisit(false);
        SimState before = real.snapshot().observed;
        int block = blockOf(step);
        ResultActivity a = open(step);
        Shots.click(a, TowerPanel.FORGOT);
        Shots.click(a, "保存");

        SimState after = real.snapshot().observed;
        assertEquals(before.cash, after.cash);
        // 「不记得钱了」: the amount is kept but no longer counts as exact (the next tower sheet asks for the balance).
        assertFalse(after.cashExact);
        assertEquals(before.towerVisits[block] + 1, after.towerVisits[block]);
        assertEquals("tower", real.history(1).get(0).kind);
    }

    @Test
    public void towerSheetRescuePreselectedFromThePlanIsStored() throws Exception {
        Plan.Step step = onboardAtTowerVisit(true);
        int maiko = rescueIndex("maiko");
        assertTrue("the plan predicts the rescue: " + step.subtitleZh, ResultActivity.predictsRescue(rules, step));
        ResultActivity a = open(step);
        TowerPanel panel = a.towerPanel();
        assertTrue(Shots.has(a, rules.rescues[maiko].zh + "救出来了吗？"));
        assertEquals("救出了 preselected", 0, panel.rescueSelection());
        panel.setAmountForTest(12_000);
        Shots.shot(a, "result_real_tower_rescue");
        Shots.click(a, "保存");

        SimState after = real.snapshot().observed;
        assertTrue("rescued", (after.rescued & (1L << maiko)) != 0);
        assertEquals(0L, after.rescueFailed & (1L << maiko));
        settled();
        assertTrue((real.snapshot().observed.rescued & (1L << maiko)) != 0);
    }

    /**
     * Onboards an evening whose installed plan step is a Tartarus visit: the last open evening of a
     * block that still needs its visits, or (rescue) of 舞子's window with 倒悬者 at RANK 2.
     */
    private Plan.Step onboardAtTowerVisit(boolean rescue) throws Exception {
        Profile profile = new Profile();
        int maiko = rescueIndex("maiko");
        Rules.Rescue r = rules.rescues[maiko];
        int lastDay = rescue ? r.toDay : dayOf(8, 31);
        int firstDay = rescue ? r.fromDay : dayOf(7, 1);
        for (int day = lastDay; day >= firstDay; day--) {
            int slot = Rules.slot(day, true);
            Rules.Day info = rules.day(day);
            Rules.Block block = rules.blockOn(day);
            if (info == null || !info.tartarus || block == null || rules.lockOf(slot) != null) {
                continue;
            }
            SimState s = midGame(slot);
            s.cash = 18_500;
            s.floor = Math.max(1, block.top - 10);
            if (rescue) {
                int hanged = rules.linkIndex("hanged_man");
                s.rank[hanged] = 2;
                s.unlockStep[hanged] = rules.links[hanged].unlock.steps.size();
                s.rescued = 1L << rescueIndex("bunkichi");
            }
            Plan.Step first = firstStep(profile, s);
            if (first == null || !first.action.startsWith("tower:")) {
                continue;
            }
            if (rescue && !ResultActivity.predictsRescue(rules, first)) {
                continue;
            }
            Plan.Step installed = onboard(s, profile);
            if (installed.action.startsWith("tower:") && (!rescue || ResultActivity.predictsRescue(rules, installed))) {
                return installed;
            }
        }
        throw new AssertionError("no evening where the plan starts with a Tartarus visit");
    }

    // ------------------------------------------------------------------ helpers

    /** A plausible mid-game record at {@code slot}: Lv 3 stats, story links where the story is. */
    private SimState midGame(int slot) {
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = slot;
        s.cash = 20_000;
        s.cashExact = true;
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            s.statPts[i] = rules.stats[i].thresholds[3];
        }
        int day = Rules.dayOf(slot);
        for (Rules.Link l : rules.links) {
            if (l.kind != Rules.LinkKind.STORY || l.storyRankDay == null) {
                continue;
            }
            for (int rank = 1; rank < l.storyRankDay.length && rank <= 10; rank++) {
                if (l.storyRankDay[rank] >= 0 && l.storyRankDay[rank] <= day) {
                    s.rank[l.idx] = rank;
                }
            }
        }
        return s;
    }

    private Plan.Step firstStep(Profile profile, SimState s) {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 400;
        Plan plan = Engine.plan(rules, profile, s, null, options);
        return plan == null ? null : plan.at(s.slot);
    }

    /** Saves {@code s} as the first record (onboarding) and returns the installed plan's first step. */
    private Plan.Step onboard(SimState s, Profile profile) throws Exception {
        PlannerApi.Snapshot now = real.snapshot();
        PlannerApi.Receipt receipt = real.saveCheckpoint(UUID.randomUUID().toString(), now.stateRevision, s, profile);
        assertTrue(receipt.errorZh, receipt.ok);
        PlannerApi.Snapshot snap = settled();
        Plan.Step step = snap.plan.at(snap.observed.slot);
        assertNotNull(step);
        return step;
    }

    /** Opens the quick result sheet for {@code step} as the widget or the app would. */
    private ResultActivity open(Plan.Step step) {
        PlannerApi.Snapshot snap = real.snapshot();
        Intent intent = new Intent(app, ResultActivity.class);
        intent.putExtra(Intents.EXTRA_SLOT, step.slot);
        intent.putExtra(Intents.EXTRA_ACTION, step.action);
        intent.putExtra(Intents.EXTRA_MODE, Intents.MODE_RESULT);
        intent.putExtra(Intents.EXTRA_PLAN_ID, snap.plan.planId);
        intent.putExtra(Intents.EXTRA_STATE_REV, snap.stateRevision);
        intent.putExtra(HomeActivity.EXTRA_FROM_APP, true);
        ResultActivity a = Robolectric.buildActivity(ResultActivity.class, intent).setup().get();
        Shots.idle();
        assertTrue("the sheet shows the planned step, not a stale banner", a.bannerText() == null);
        return a;
    }

    /** Waits for the plan of the latest records. */
    private PlannerApi.Snapshot settled() throws InterruptedException {
        long deadline = System.currentTimeMillis() + SETTLE_MS;
        while (true) {
            Shots.idle();
            PlannerApi.Snapshot s = real.snapshot();
            if (s.plan != null && s.planCurrent) {
                return s;
            }
            assertTrue("no plan after " + SETTLE_MS + " ms: " + s.planErrorZh, System.currentTimeMillis() < deadline);
            Thread.sleep(25);
        }
    }

    private int blockOf(Plan.Step step) {
        return Integer.parseInt(step.action.substring("tower:".length()));
    }

    private int rescueIndex(String id) {
        for (int i = 0; i < rules.rescues.length; i++) {
            if (rules.rescues[i].id.equals(id)) {
                return i;
            }
        }
        throw new AssertionError(id);
    }

    private int dayOf(int month, int dom) {
        for (int d = rules.firstDay; d <= rules.lastDay; d++) {
            Rules.Day day = rules.day(d);
            if (day != null && day.month() == month && day.dayOfMonth() == dom) {
                return d;
            }
        }
        throw new AssertionError(month + "/" + dom);
    }
}
