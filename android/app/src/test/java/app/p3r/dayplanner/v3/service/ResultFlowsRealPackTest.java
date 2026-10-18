package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Work order 5: the targeted result flows end to end on the real rule pack, the real engine and
 * the real SQLite ledger, with the payloads the result sheet sends (ui.Outcomes / TowerPanel):
 *
 * <ul>
 *   <li>皇帝 RANK 6 → 7: 「已升到 RANK 7」 (as predicted, with the rank), 「没升级」 (with and
 *       without the 「下次可升级」 cue), 「其他结果」 (a higher RANK, a lower RANK), undo, duplicate
 *       retry and restart.</li>
 *   <li>探索塔尔塔罗斯 in 舞子's window: 「出塔后余额」 with the rescue confirmed, 「这次多了多少」
 *       without it, 「不记得钱了」, corrections between the cash modes that never add a visit,
 *       duplicate retry and restart.</li>
 * </ul>
 * Every installed plan must be current, validator-clean and cover the calendar to its end.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ResultFlowsRealPackTest {
    private Context context;
    private Rules rules;
    private final List<PlannerService> open = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        File pack = new File("src/main/assets/p3r/p3r_rules.json");
        rules = RulesLoader.parse(new String(Files.readAllBytes(pack.toPath()), StandardCharsets.UTF_8));
    }

    @After
    public void tearDown() {
        for (PlannerService s : open) s.close();
    }

    // ------------------------------------------------------------------ 皇帝 RANK 6 → 7

    @Test
    public void actualReceivedInvitationRecordsWithoutRankingUpAndSurvivesRetryAndRestart() {
        String database = db();
        PlannerService service = open(database);
        int magician = link("magician");
        int day = 25; // April 26: a possible Magician holiday SMS, no ordinary availability.
        SimState start = new SimState(rules.blocks.length, rules.activities.length);
        start.slot = Rules.slot(day, false);
        start.cash = 10_000;
        for (Rules.Link l : rules.links) {
            if (l.kind == Rules.LinkKind.MANUAL) start.rank[l.idx] = 10;
        }
        start.rank[magician] = 1;
        start.unlockStep[magician] = rules.links[magician].unlock.steps.size();
        PlannerApi.Receipt checkpoint = service.saveCheckpoint(id(), service.snapshot().stateRevision, start, null);
        assertTrue(checkpoint.errorZh, checkpoint.ok);
        settledPlan(service, "before actual invitation");
        List<Plan.Step> alternatives = service.alternatives(start.slot);
        assertTrue(alternatives.stream().anyMatch(s -> s.action.equals("invite:magician")));
        assertFalse(alternatives.stream().anyMatch(s -> s.action.equals("meet:magician")));

        Outcome attended = app.p3r.dayplanner.v3.ui.Outcomes.invited(true);
        attended.sideDone = false;
        String command = id();
        PlannerApi.Receipt result = service.recordOutcome(command, service.snapshot().stateRevision,
                start.slot, "invite:magician", attended);
        assertTrue(result.errorZh, result.ok);
        settledPlan(service, "actual invitation recorded");
        SimState after = service.snapshot().observed;
        assertEquals("exactly the daytime slot was consumed", start.slot + 1, after.slot);
        assertEquals(1, after.rank[magician]);
        assertEquals(0, after.pts[magician]);
        assertTrue(after.ready[magician]);
        assertTrue(historyMentions(service, "休息日邀约"));
        assertTrue(historyMentions(service, "赴约了"));

        PlannerApi.Receipt retry = service.recordOutcome(command, result.stateRevision,
                start.slot, "invite:magician", attended);
        assertTrue(retry.ok);
        assertTrue(retry.duplicate);
        service = reopen(service, database);
        settledPlan(service, "invitation after restart");
        assertEquals(start.slot + 1, service.snapshot().observed.slot);
        assertEquals(1, service.snapshot().observed.rank[magician]);
        assertTrue(service.snapshot().observed.ready[magician]);
    }

    @Test
    public void emperorRankUpAsPredictedThenUndoRetryAndRestart() {
        String db = db();
        PlannerService service = open(db);
        Plan.Step step = emperorAtSix(service);
        int emperor = link("emperor");

        // 「已升到 RANK 7」: AS_PREDICTED carrying the predicted rank (ui.Outcomes.asPredicted).
        Outcome asPredicted = Outcome.of(Outcome.Code.AS_PREDICTED);
        asPredicted.rank = step.predicted.rankTo;
        String command = id();
        PlannerApi.Receipt r = service.recordOutcome(command, service.snapshot().stateRevision, step.slot, step.action, asPredicted);
        assertTrue(r.errorZh, r.ok);
        Plan after = settledPlan(service, "已升到 RANK 7");
        SimState obs = service.snapshot().observed;
        assertEquals(7, obs.rank[emperor]);
        assertFalse(obs.ready[emperor]);
        assertTrue(obs.slot > step.slot);
        assertNextEmperorMeetingFrom(after, 7);
        assertTrue("history says 「RANK 6 → 7」", historyMentions(service, "RANK 6 → 7"));

        // The same command again (a retry after a timeout): no second rank-up.
        PlannerApi.Receipt again = service.recordOutcome(command, r.stateRevision, step.slot, step.action, asPredicted);
        assertTrue(again.ok);
        assertTrue(again.duplicate);
        assertEquals(7, service.snapshot().observed.rank[emperor]);

        // Restart: the record is still there.
        service = reopen(service, db);
        assertEquals(7, service.snapshot().observed.rank[emperor]);
        settledPlan(service, "after restart");

        // Undo: back to RANK 6 with the rank-up cue, at the same slot.
        PlannerApi.Receipt undo = service.voidEvent(id(), service.snapshot().stateRevision, r.eventSeq);
        assertTrue(undo.errorZh, undo.ok);
        Plan undone = settledPlan(service, "after undo");
        obs = service.snapshot().observed;
        assertEquals(6, obs.rank[emperor]);
        assertTrue(obs.ready[emperor]);
        assertEquals(step.slot, obs.slot);
        assertEquals(step.action, undone.at(step.slot).action);
    }

    @Test
    public void emperorNoRankUpKeepsRankSixAndMeetsAgain() {
        PlannerService service = open(db());
        Plan.Step step = emperorAtSix(service);
        int emperor = link("emperor");

        // 「没升级」 (ui.Outcomes.noRankUp(6, false)).
        Outcome no = Outcome.of(Outcome.Code.NO_RANK_UP);
        no.rank = 6;
        assertTrue(service.recordOutcome(id(), service.snapshot().stateRevision, step.slot, step.action, no).ok);
        Plan after = settledPlan(service, "没升级");
        SimState obs = service.snapshot().observed;
        assertEquals(6, obs.rank[emperor]);
        assertFalse("the cue is used up", obs.ready[emperor]);
        assertTrue("points end close to the next RANK", obs.pts[emperor] >= rules.links[emperor].threshold[7]);
        Plan.Step next = assertNextEmperorMeetingFrom(after, 6);
        assertFalse("without the cue the next rank-up is an estimate", next.predicted.certain);
        boolean rankUpLater = false;
        for (Plan.Step s : after.steps) rankUpLater |= "meet:emperor".equals(s.action) && s.predicted.rankUp && s.predicted.rankTo == 7;
        assertTrue("RANK 7 is planned again", rankUpLater);

        // The next meeting: 「没升级」 again, this time with 「游戏提示下次可升级」.
        recordUntil(service, next.slot);
        Outcome cue = Outcome.of(Outcome.Code.NO_RANK_UP);
        cue.rank = 6;
        cue.readyCue = true;
        assertTrue(service.recordOutcome(id(), service.snapshot().stateRevision, next.slot, next.action, cue).ok);
        Plan cued = settledPlan(service, "没升级 · 下次可升级");
        assertTrue(service.snapshot().observed.ready[emperor]);
        Plan.Step certain = assertNextEmperorMeetingFrom(cued, 6);
        assertTrue(certain.predicted.rankUp);
        assertTrue("the cue makes the next rank-up certain", certain.predicted.certain);
    }

    @Test
    public void emperorOtherResultsSetTheRankThePlayerSaw() {
        int emperor = link("emperor");

        // 「其他结果」 → RANK 8 (ui.Outcomes.observedRank(6, 8, false) = RANK_UP 8).
        PlannerService up = open(db());
        Plan.Step step = emperorAtSix(up);
        Outcome eight = Outcome.of(Outcome.Code.RANK_UP);
        eight.rank = 8;
        assertTrue(up.recordOutcome(id(), up.snapshot().stateRevision, step.slot, step.action, eight).ok);
        Plan afterUp = settledPlan(up, "其他结果 RANK 8");
        assertEquals(8, up.snapshot().observed.rank[emperor]);
        assertNextEmperorMeetingFrom(afterUp, 8);
        // A RANK that is not higher is refused as a rank-up.
        Outcome notHigher = Outcome.of(Outcome.Code.RANK_UP);
        notHigher.rank = 8;
        Plan.Step later = assertNextEmperorMeetingFrom(afterUp, 8);
        recordUntil(up, later.slot);
        PlannerApi.Receipt refused = up.recordOutcome(id(), up.snapshot().stateRevision, later.slot, later.action, notHigher);
        assertFalse(refused.ok);
        assertEquals(PlannerApi.Receipt.CODE_INVALID, refused.errorCode);

        // 「其他结果」 → RANK 5, a correction (ui.Outcomes.observedRank(6, 5, false) = OTHER 5).
        PlannerService down = open(db());
        step = emperorAtSix(down);
        Outcome five = Outcome.of(Outcome.Code.OTHER);
        five.rank = 5;
        assertTrue(down.recordOutcome(id(), down.snapshot().stateRevision, step.slot, step.action, five).ok);
        Plan afterDown = settledPlan(down, "其他结果 RANK 5");
        SimState obs = down.snapshot().observed;
        assertEquals(5, obs.rank[emperor]);
        assertEquals(0, obs.pts[emperor]);
        assertNextEmperorMeetingFrom(afterDown, 5);
        assertEquals("皇帝 stays the goal", 10, afterDown.summary.endRanks[emperor]);
    }

    // ------------------------------------------------------------------ 探索塔尔塔罗斯

    @Test
    public void towerBalanceModeWithRescueThenCorrectionsNeverAddAVisit() {
        String db = db();
        PlannerService service = open(db);
        Plan.Step visit = towerRescueStep(service);
        int block = rules.day(Rules.dayOf(visit.slot)).block;
        SimState before = service.snapshot().observed;
        int visitsBefore = before.towerVisits[block];
        int maiko = rescue("maiko");

        // 「出塔后余额」 23,456 日元, 舞子 rescued.
        PlannerApi.TowerReturn balance = tower(visit.slot, block, floorIn(block, before.floor + 10), true, 23_456);
        balance.rescuedId = "maiko";
        balance.level = 31;
        String command = id();
        PlannerApi.Receipt r = service.recordTower(command, service.snapshot().stateRevision, balance);
        assertTrue(r.errorZh, r.ok);
        Plan after = settledPlan(service, "出塔后余额 · 救出舞子");
        SimState obs = service.snapshot().observed;
        assertEquals(23_456, obs.cash);
        assertTrue(obs.cashExact);
        assertEquals(visitsBefore + 1, obs.towerVisits[block]);
        assertEquals(floorIn(block, before.floor + 10), obs.floor);
        assertTrue("舞子 rescued", (obs.rescued & (1L << maiko)) != 0);
        for (Plan.Step s : after.steps) assertFalse("no second rescue visit: " + s.subtitleZh, String.valueOf(s.subtitleZh).contains("救出舞子"));
        assertTrue("the rescued goal continues after the window or reaches MAX before it",
                after.summary.endRanks[link("hanged_man")] == 10
                        || meetsAfter(after, "hanged_man", rules.rescues[maiko].toDay));

        // Correction to 「这次多了多少」 +5,000: replaces the balance, never adds a visit.
        PlannerApi.TowerReturn net = tower(visit.slot, block, floorIn(block, before.floor + 15), false, 5_000);
        PlannerApi.Receipt fix = service.correctTower(id(), service.snapshot().stateRevision, r.eventSeq, net);
        assertTrue(fix.errorZh, fix.ok);
        settledPlan(service, "改成这次多了多少");
        obs = service.snapshot().observed;
        assertEquals(visitsBefore + 1, obs.towerVisits[block]);
        assertEquals(floorIn(block, before.floor + 15), obs.floor);
        assertEquals("net change on the pre-visit cash (side fees included), not on the old balance",
                before.cash + visit.predicted.cashDelta + 5_000, obs.cash);
        assertTrue("the rescue answer is kept", (obs.rescued & (1L << maiko)) != 0);

        // And back to a balance: exactly that balance.
        PlannerApi.TowerReturn back = tower(visit.slot, block, floorIn(block, before.floor + 15), true, 30_000);
        assertTrue(service.correctTower(id(), service.snapshot().stateRevision, r.eventSeq, back).ok);
        settledPlan(service, "改回出塔后余额");
        assertEquals(30_000, service.snapshot().observed.cash);
        assertEquals(visitsBefore + 1, service.snapshot().observed.towerVisits[block]);

        // Retry of the original record and a restart: still one visit.
        service = reopen(service, db);
        PlannerApi.Receipt retry = service.recordTower(command, r.stateRevision, balance);
        assertTrue(retry.ok);
        assertTrue(retry.duplicate);
        obs = service.snapshot().observed;
        assertEquals(visitsBefore + 1, obs.towerVisits[block]);
        assertEquals(30_000, obs.cash);
        settledPlan(service, "after restart");
    }

    @Test
    public void towerNetModeWithoutTheRescueKeepsTheRescueInThePlan() {
        PlannerService service = open(db());
        Plan.Step visit = towerRescueStep(service);
        int block = rules.day(Rules.dayOf(visit.slot)).block;
        SimState before = service.snapshot().observed;
        int maiko = rescue("maiko");

        // 「这次多了多少」 +4,000, rescue answered 「还没」.
        PlannerApi.TowerReturn net = tower(visit.slot, block, floorIn(block, before.floor + 8), false, 4_000);
        assertTrue(service.recordTower(id(), service.snapshot().stateRevision, net).ok);
        Plan after = settledPlan(service, "这次多了多少 · 还没救出");
        SimState obs = service.snapshot().observed;
        assertEquals(before.cash + visit.predicted.cashDelta + 4_000, obs.cash);
        assertEquals(before.towerVisits[block] + 1, obs.towerVisits[block]);
        assertEquals("not rescued yet", 0, obs.rescued & (1L << maiko));
        Plan.Step retryRescue = null;
        for (Plan.Step s : after.steps) {
            if ("tower".equals(s.kind) && String.valueOf(s.subtitleZh).contains("救出舞子")) {
                retryRescue = s;
                break;
            }
        }
        assertNotNull("another rescue visit before the window closes", retryRescue);
        assertTrue(Rules.dayOf(retryRescue.slot) <= rules.rescues[maiko].toDay);
    }

    @Test
    public void towerForgottenCashKeepsTheCashAsALowerBound() {
        String db = db();
        PlannerService service = open(db);
        Plan.Step visit = towerRescueStep(service);
        int block = rules.day(Rules.dayOf(visit.slot)).block;
        SimState before = service.snapshot().observed;
        int maiko = rescue("maiko");

        // 「不记得钱了」 (ui.TowerPanel: net 0), 舞子 rescued.
        PlannerApi.TowerReturn forgot = tower(visit.slot, block, floorIn(block, before.floor + 6), false, 0);
        forgot.cashUnknown = true;
        forgot.rescuedId = "maiko";
        PlannerApi.Receipt r = service.recordTower(id(), service.snapshot().stateRevision, forgot);
        assertTrue(r.errorZh, r.ok);
        settledPlan(service, "不记得钱了");
        SimState obs = service.snapshot().observed;
        assertEquals("side fees only, nothing guessed for the visit", before.cash + visit.predicted.cashDelta, obs.cash);
        assertFalse("the cash is a lower bound", obs.cashExact);
        assertEquals(before.towerVisits[block] + 1, obs.towerVisits[block]);
        assertTrue((obs.rescued & (1L << maiko)) != 0);
        assertTrue(historyMentions(service, "不记得钱了"));

        // Restart, then the balance after all: exact again, still one visit.
        service = reopen(service, db);
        assertTrue(service.correctTower(id(), service.snapshot().stateRevision, r.eventSeq,
                tower(visit.slot, block, floorIn(block, before.floor + 6), true, 25_000)).ok);
        settledPlan(service, "改成出塔后余额");
        obs = service.snapshot().observed;
        assertEquals(25_000, obs.cash);
        assertTrue(obs.cashExact);
        assertEquals(before.towerVisits[block] + 1, obs.towerVisits[block]);
        assertTrue("the rescue answer is kept", (obs.rescued & (1L << maiko)) != 0);
    }

    // ------------------------------------------------------------------ helpers

    /** Onboards on 6/1 with 皇帝 RANK 6 and the rank-up cue (皇帝 必须); returns the 6 → 7 meeting. */
    private Plan.Step emperorAtSix(PlannerService service) {
        int emperor = link("emperor");
        int day = firstFreeDay(dayOf(6, 1), rules.links[emperor], false);
        SimState start = state(day, false);
        start.rank[emperor] = 6;
        start.ready[emperor] = true;
        start.cash = 10_000;
        Profile profile = new Profile();
        profile.tier[emperor] = Profile.Tier.MUST;
        PlannerApi.Receipt r = service.saveCheckpoint(id(), service.snapshot().stateRevision, start, profile);
        assertTrue(r.errorZh, r.ok);
        Plan plan = settledPlan(service, "皇帝 RANK 6");
        Plan.Step step = plan.at(service.snapshot().observed.slot);
        assertEquals("meet:emperor", step.action);
        assertEquals("social", step.kind);
        assertTrue(step.titleZh, step.titleZh.contains("皇帝"));
        assertEquals(6, step.predicted.rankFrom);
        assertEquals(7, step.predicted.rankTo);
        assertTrue(step.predicted.rankUp);
        assertTrue("the cue makes it certain", step.predicted.certain);
        return step;
    }

    /** Onboards on 10/21 (舞子 missing, 倒悬者 RANK 4) and follows the plan to its first rescue visit. */
    private Plan.Step towerRescueStep(PlannerService service) {
        int maiko = rescue("maiko");
        SimState start = state(rules.rescues[maiko].fromDay, false);
        start.rank[link("hanged_man")] = 4;
        start.rank[link("emperor")] = 5;
        start.rank[link("chariot")] = 5;
        start.cash = 12_000;
        Rules.Block block = rules.blockOn(rules.rescues[maiko].fromDay);
        if (block != null) start.towerVisits[block.idx] = 1;
        Profile profile = new Profile();
        // This flow checks that rescuing 舞子 preserves the requested Social Link continuation.
        profile.tier[link("hanged_man")] = Profile.Tier.MUST;
        PlannerApi.Receipt r = service.saveCheckpoint(id(), service.snapshot().stateRevision, start, profile);
        assertTrue(r.errorZh, r.ok);
        int deadline = Rules.slot(rules.rescues[maiko].toDay, true);
        while (service.snapshot().observed.slot <= deadline) {
            Plan current = settledPlan(service, "跟随当前安排，等待救出舞子");
            int slot = service.snapshot().observed.slot;
            Plan.Step step = current.at(slot);
            assertNotNull(step);
            if ("tower".equals(step.kind)) {
                assertTrue(step.subtitleZh, step.subtitleZh.endsWith("救出舞子"));
                return step;
            }
            // A distant forecast can move when each recorded result triggers replanning.
            // Follow the current next action until the actual rescue evening is reached.
            recordUntil(service, slot + 1);
            assertTrue("recording the next action advances time", service.snapshot().observed.slot > slot);
        }
        throw new AssertionError("the current plan never reached a rescue visit before 舞子's deadline");
    }

    /** Records every planned step before {@code slot} as predicted (the 「完成」 button). */
    private void recordUntil(PlannerService service, int slot) {
        while (service.snapshot().observed.slot < slot) {
            PlannerApi.Snapshot s = service.snapshot();
            Plan.Step step = s.plan.at(s.observed.slot);
            assertNotNull(step);
            PlannerApi.Receipt r;
            if ("tower".equals(step.kind)) {
                int block = rules.day(Rules.dayOf(step.slot)).block;
                r = service.recordTower(id(), s.stateRevision, tower(step.slot, block, floorIn(block, s.observed.floor + 5), false, 2_000));
            } else {
                Outcome o = Outcome.of(Outcome.Code.AS_PREDICTED);
                if (("social".equals(step.kind) || "unlock".equals(step.kind)) && step.predicted.rankTo >= 0) o.rank = step.predicted.rankTo;
                r = service.recordOutcome(id(), s.stateRevision, step.slot, step.action, o);
            }
            assertTrue(r.errorZh, r.ok);
            settledPlan(service, "following the plan");
        }
    }

    private Plan.Step assertNextEmperorMeetingFrom(Plan plan, int rank) {
        for (Plan.Step s : plan.steps) {
            if ("meet:emperor".equals(s.action)) {
                assertEquals(rank, s.predicted.rankFrom);
                return s;
            }
        }
        throw new AssertionError("皇帝 is not met again");
    }

    private boolean meetsAfter(Plan plan, String linkId, int day) {
        for (Plan.Step s : plan.steps) {
            if (("meet:" + linkId).equals(s.action) && Rules.dayOf(s.slot) > day) return true;
        }
        return false;
    }

    private static boolean historyMentions(PlannerService service, String text) {
        for (PlannerApi.HistoryItem h : service.history(20)) {
            if (h.summaryZh != null && h.summaryZh.contains(text)) return true;
        }
        return false;
    }

    private PlannerService open(String db) {
        Wiring wiring = new Wiring();
        wiring.rules = () -> rules;
        wiring.planBudgetMillis = 2500;
        wiring.databaseName = db;
        PlannerService s = PlannerService.create(context, wiring);
        open.add(s);
        return s;
    }

    private PlannerService reopen(PlannerService service, String db) {
        settledPlan(service, "before restart");
        service.close();
        open.remove(service);
        return open(db);
    }

    private Plan settledPlan(PlannerService service, String label) {
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
        return p;
    }

    /** State at the start of {@code day}; story links carry the ranks the story granted. */
    private SimState state(int day, boolean evening) {
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = Rules.slot(day, evening);
        s.cash = 5_000;
        s.cashExact = true;
        for (Rules.Link l : rules.links) {
            if (l.kind != Rules.LinkKind.STORY || l.storyRankDay == null) continue;
            for (int r = 1; r < l.storyRankDay.length && r <= 10; r++) {
                if (l.storyRankDay[r] >= 0 && l.storyRankDay[r] <= day) s.rank[l.idx] = r;
            }
        }
        return s;
    }

    private int firstFreeDay(int from, Rules.Link link, boolean evening) {
        for (int d = from; d <= rules.lastDay; d++) {
            if (link.avail.get(d) && rules.lockOf(Rules.slot(d, evening)) == null) return d;
        }
        throw new AssertionError("no free day for " + link.id);
    }

    private int dayOf(int month, int dom) {
        for (int d = rules.firstDay; d <= rules.lastDay; d++) {
            Rules.Day day = rules.day(d);
            if (day != null && day.month() == month && day.dayOfMonth() == dom) return d;
        }
        throw new AssertionError(month + "/" + dom);
    }

    private int link(String id) {
        return rules.link(id).idx;
    }

    private int rescue(String id) {
        for (int i = 0; i < rules.rescues.length; i++) if (id.equals(rules.rescues[i].id)) return i;
        throw new AssertionError("no rescue " + id);
    }

    /** {@code floor} kept within the block's highest floor (the service refuses anything above it). */
    private int floorIn(int block, int floor) {
        int top = rules.blocks[block].top;
        return top > 0 ? Math.min(top, floor) : floor;
    }

    private static PlannerApi.TowerReturn tower(int slot, int block, int floor, boolean balance, long amount) {
        PlannerApi.TowerReturn t = new PlannerApi.TowerReturn();
        t.slot = slot;
        t.block = block;
        t.floor = floor;
        t.balanceMode = balance;
        t.amount = amount;
        return t;
    }

    private static String db() {
        return "flows-" + UUID.randomUUID() + ".db";
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
