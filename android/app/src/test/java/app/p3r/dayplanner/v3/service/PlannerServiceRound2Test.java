package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.CheckpointRow;
import app.p3r.dayplanner.v3.store.EventRow;
import app.p3r.dayplanner.v3.store.Ledger;
import app.p3r.dayplanner.v3.store.PlanDb;
import app.p3r.dayplanner.v3.store.TestRules;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Review round 2: tower corrections keep what the form cannot ask, history flags match what the
 * service accepts, widgets refresh after failed replans, repeated imports say so, stored state
 * survives rule-pack updates, rescues are inferred at checkpoint time, notes and Link Episodes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceRound2Test extends ServiceTestBase {
    private static final int MAY12_DAY = TestRules.slot(TestRules.MAY_12, false);
    private static final int MAY12_EVE = TestRules.slot(TestRules.MAY_12, true);
    private static final int MAY13_DAY = TestRules.slot(TestRules.MAY_12 + 1, false);
    private static final int MAY13_EVE = TestRules.slot(TestRules.MAY_12 + 1, true);

    @Test
    public void correctingATowerRecordKeepsItsRescueAndLevel() {
        onboard(MAY12_EVE);
        PlannerApi.TowerReturn visit = towerReturn(MAY12_EVE, 30, true, 14_750);
        visit.rescuedId = "bunkichi";
        visit.level = 20;
        PlannerApi.Receipt recorded = service.recordTower(id(), rev(), visit);
        assertTrue(recorded.errorZh, recorded.ok);
        assertEquals(1L, observed().rescued);

        // The correction form never asks about the rescue and leaves the level unset.
        PlannerApi.TowerReturn cashTypo = towerReturn(0, 30, true, 15_000);
        PlannerApi.Receipt fix = service.correctTower(id(), rev(), recorded.eventSeq, cashTypo);
        assertTrue(fix.errorZh, fix.ok);

        SimState s = observed();
        assertEquals("the rescue survives the correction", 1L, s.rescued);
        assertEquals(15_000, s.cash);
        List<PlannerApi.HistoryItem> history = service.history(0);
        assertEquals("5月12日 晚上 · 修改了这次探索 · 到达 30F · 出塔后余额 15,000 日元 · 救出了北村文吉",
                history.get(0).summaryZh);
        PlannerApi.HistoryItem towerItem = history.get(1);
        assertEquals("tower", towerItem.kind);
        assertNotNull("the correction form can be prefilled", towerItem.tower);
        assertEquals("values in effect", 15_000, towerItem.tower.amount);
        assertEquals("bunkichi", towerItem.tower.rescuedId);
        assertEquals(Integer.valueOf(20), towerItem.tower.level);
        assertEquals(30, towerItem.tower.floor);

        restart();
        assertEquals(1L, observed().rescued);
    }

    @Test
    public void historyOffersUndoAndCorrectionOnlyWhereTheServiceAcceptsThem() {
        onboard(MAY12_DAY);
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        PlannerApi.Receipt visit = tower(MAY12_EVE, 20, false, 3_000);
        assertTrue(service.correctTower(id(), rev(), visit.eventSeq, towerReturn(0, 22, false, 2_500)).ok);

        List<PlannerApi.HistoryItem> history = service.history(0);
        PlannerApi.HistoryItem fix = history.get(0);
        PlannerApi.HistoryItem towerRow = history.get(1);
        PlannerApi.HistoryItem meeting = history.get(2);
        PlannerApi.HistoryItem checkpoint = history.get(3);
        assertEquals("tower_fix", fix.kind);
        assertTrue(fix.undoable);
        assertFalse("修改 targets the tower row itself", fix.correctable);
        assertEquals(22, fix.tower.floor);
        assertTrue(towerRow.undoable);
        assertTrue(towerRow.correctable);
        assertEquals(2_500, towerRow.tower.amount);
        assertTrue(meeting.undoable);
        assertFalse("meetings have no correction form", meeting.correctable);
        assertNull(meeting.tower);
        assertFalse(checkpoint.undoable);
        assertFalse(checkpoint.correctable);
    }

    @Test
    public void widgetsRefreshAfterAFailedReplanWithPlanningOver() {
        planner.failWith = new IllegalStateException("boom");
        List<Boolean> planningSeen = new CopyOnWriteArrayList<>();
        onWidgetRefresh = () -> planningSeen.add(service.peekSnapshot().planning);

        onboard(MAY12_DAY);
        settle();

        PlannerApi.Snapshot s = service.snapshot();
        assertFalse(s.planning);
        assertNull(s.plan);
        assertEquals(PlannerService.ZH_PLAN_FAILED_NONE, s.planErrorZh);
        assertTrue("the write and the failed run both refresh", planningSeen.size() >= 2);
        assertEquals("the last refresh shows the error, not 正在生成安排…", Boolean.FALSE,
                planningSeen.get(planningSeen.size() - 1));
    }

    @Test
    public void importingTheSameBackupAgainSaysItWasAlreadyRestored() {
        onboard(MAY12_DAY);
        String backup = service.exportBackup();
        String command = id();
        PlannerApi.Receipt first = service.importBackup(command, backup);
        assertTrue(first.errorZh, first.ok);
        assertFalse(first.duplicate);
        assertNull(first.noticeZh);

        PlannerApi.Receipt retry = service.importBackup(command, backup);
        assertTrue(retry.ok);
        assertTrue(retry.duplicate);
        assertEquals(PlannerService.ZH_IMPORT_ALREADY, retry.noticeZh);

        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        PlannerApi.Receipt stale = service.importBackup(command, backup);
        assertTrue(stale.duplicate);
        assertEquals(PlannerService.ZH_IMPORT_ALREADY_LATER, stale.noticeZh);
        assertEquals("nothing was replaced", 7, observed().rank[TestRules.EMPEROR]);

        PlannerApi.Receipt again = service.importBackup(id(), backup);
        assertTrue(again.ok);
        assertFalse(again.duplicate);
        assertEquals(6, observed().rank[TestRules.EMPEROR]);
    }

    @Test
    public void checkpointsAndEventsAreStampedWithThePackTheyWereWrittenUnder() throws Exception {
        onboard(MAY12_DAY);
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(tower(MAY12_EVE, 20, false, 1_000).ok);
        String backup = service.exportBackup();
        assertTrue(service.importBackup(id(), backup).ok);
        settle();

        try (Ledger ledger = new Ledger(context, PlanDb.FILE_NAME)) {
            CheckpointRow checkpoint = ledger.latestCheckpoint();
            assertEquals(rules.version, checkpoint.rulesVersion);
            assertTrue("the pack layout is kept with the checkpoint", checkpoint.hasLayout());
            for (EventRow row : ledger.allEvents()) {
                assertEquals(row.kind, rules.version, row.rulesVersion);
            }
        }
    }

    @Test
    public void aPackUpdateCarriesStoredCountersOverById() {
        SimState start = TestRules.state(rules, MAY12_DAY);
        start.shifts[TestRules.CAFE] = 5;
        start.used[TestRules.STUDY] = 1;
        onboard(start);

        Rules update = TestRules.create();
        update.version = "test-pack|2";
        Rules.Activity library = new Rules.Activity();
        library.id = "library";
        library.zh = "去图书馆学习";
        library.gain = new int[]{2, 0, 0};
        update.activities = new Rules.Activity[]{library, update.activities[0], update.activities[1]};
        for (int i = 0; i < update.activities.length; i++) {
            update.activities[i].idx = i;
        }
        rules = update;
        restart();

        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("the café job keeps its shift count", 5, s.observed.shifts[2]);
        assertEquals(1, s.observed.used[1]);
        assertEquals(0, s.observed.shifts[0]);
        assertNull(s.noticeZh);
    }

    @Test
    public void recordsThatNoLongerFitTheGameDataRaiseANotice() {
        onboard(MAY12_EVE);
        assertTrue(record(MAY12_EVE, "act:job_chagall", Outcome.Code.AS_PREDICTED).ok);
        assertNull(service.snapshot().noticeZh);

        Rules update = TestRules.create();
        update.version = "test-pack|2";
        update.activities = new Rules.Activity[]{update.activities[TestRules.STUDY]};
        rules = update;
        restart();

        PlannerApi.Snapshot s = service.snapshot();
        assertEquals("有 1 条记录和现在的游戏资料对不上，建议「同步现状」核对一下", s.noticeZh);
        assertEquals("the slot still counts as recorded", MAY13_DAY, s.observed.slot);
    }

    @Test
    public void aRecordInASlotANewPackLocksIsKept() {
        onboard(MAY12_DAY);
        assertTrue(record(MAY12_DAY, "act:study_room", Outcome.Code.AS_PREDICTED).ok);
        PlannerApi.TowerReturn rescue = towerReturn(MAY12_EVE, 25, false, 2_000);
        rescue.rescuedId = "bunkichi";
        assertTrue(service.recordTower(id(), rev(), rescue).ok);

        Rules update = TestRules.create();
        update.version = "test-pack|2";
        update.days[TestRules.MAY_12].eveLock = "full_moon";
        rules = update;
        restart();

        PlannerApi.Snapshot s = service.snapshot();
        assertEquals(1, s.observed.towerVisits[1]);
        assertEquals(1L, s.observed.rescued);
        assertEquals(12_000, s.observed.cash);
        assertEquals(MAY13_DAY, s.observed.slot);
        assertNull(s.noticeZh);
    }

    @Test
    public void rescuesAreInferredWhenTheCheckpointIsSaved() {
        SimState afterWindow = TestRules.state(rules, TestRules.slot(190, false));
        afterWindow.rank[TestRules.HIEROPHANT] = 3;
        onboard(afterWindow);
        assertEquals("window over and the link goes on: rescued", 1L, observed().rescued);

        SimState failed = afterWindow.copy();
        failed.rescueFailed = 1L;
        onboard(failed);
        assertEquals(0L, observed().rescued);
        assertEquals("an explicit 没救出 stays", 1L, observed().rescueFailed);

        SimState inWindow = TestRules.state(rules, TestRules.slot(170, false));
        inWindow.rank[TestRules.HIEROPHANT] = 3;
        onboard(inWindow);
        assertEquals("nothing is inferred while the window is open", 0L, observed().rescued);
    }

    @Test
    public void notesAndLinkEpisodesAreRecordedAndReadNaturally() {
        onboard(MAY12_EVE);
        assertTrue(record(MAY12_EVE, "note:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(record(MAY13_DAY, "episode:junpei", Outcome.Code.AS_PREDICTED).ok);
        Outcome broke = Outcome.of(Outcome.Code.SKIPPED);
        broke.reason = "money";
        assertTrue(service.recordOutcome(id(), rev(), MAY13_EVE, "note:emperor", broke).ok);
        assertEquals(1, observed().episodes[TestRules.JUNPEI]);

        List<String> lines = new ArrayList<>();
        for (PlannerApi.HistoryItem item : service.history(0)) {
            lines.add(item.summaryZh);
        }
        assertEquals(List.of(
                "5月13日 晚上 · 结缘网站的字条 · 皇帝 · 小田桐秀利 · 没用上（钱不够）",
                "5月13日 放学后 · 羁绊剧情 · 伊织顺平（第 1 话） · 看完了",
                "5月12日 晚上 · 结缘网站的字条 · 皇帝 · 小田桐秀利 · 已完成",
                "5月12日 晚上 · 设置了初始进度"), lines);
        Pattern internal = Pattern.compile("[a-z]+:|[a-z]{4,}");
        for (String line : lines) {
            assertFalse("no internal ids shown: " + line, internal.matcher(line).find());
        }
    }

    @Test
    public void towerSideStepsFollowTheRecord() {
        applier.sideFee = 1_000;
        onboard(MAY12_EVE);
        PlannerApi.TowerReturn notDone = towerReturn(MAY12_EVE, 20, false, 3_000);
        notDone.sideDone = false;
        assertTrue(service.recordTower(id(), rev(), notDone).ok);
        assertEquals(13_000, observed().cash);
        assertTrue(service.history(1).get(0).summaryZh.endsWith("顺便要做的事没做"));
        assertFalse(service.history(1).get(0).tower.sideDone);

        assertTrue(record(MAY13_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(tower(MAY13_EVE, 20, false, 3_000).ok);
        assertEquals("side fee, then the visit", 15_000, observed().cash);
    }

    @Test
    public void alternativesForLaterSlotsMoveTimeThroughTheForecaster() {
        onboard(MAY12_DAY);
        settle();
        int beyondPlan = MAY12_DAY + 8;
        service.alternatives(beyondPlan);
        assertTrue(forecaster.advances.get() > 0);
    }
}
