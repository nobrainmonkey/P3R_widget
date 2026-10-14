package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.store.TestRules;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Ledger behaviour through the public facade: CAS, idempotency, validation, replay, history. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceLedgerTest extends ServiceTestBase {
    private static final int MAY12_DAY = TestRules.slot(TestRules.MAY_12, false);
    private static final int MAY12_EVE = TestRules.slot(TestRules.MAY_12, true);
    private static final int MAY13_DAY = TestRules.slot(TestRules.MAY_12 + 1, false);
    private static final int MAY13_EVE = TestRules.slot(TestRules.MAY_12 + 1, true);

    @Test
    public void beforeOnboardingThereIsNoObservedState() {
        PlannerApi.Snapshot s = service.snapshot();
        assertNull(s.observed);
        assertNotNull(s.profile);
        assertEquals(0, s.stateRevision);
        assertFalse(s.planCurrent);
        assertFalse(s.planning);
        assertNotNull(service.rules());

        PlannerApi.Receipt r = record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED);
        assertFalse(r.ok);
        assertEquals("invalid", r.errorCode);
        assertEquals("还没有填写当前进度，请先完成初始设置", r.errorZh);
    }

    @Test
    public void outcomesAdvanceTheObservedState() {
        assertEquals(1, onboard(MAY12_DAY));
        Outcome up = Outcome.of(Outcome.Code.RANK_UP);
        up.rank = 7;
        PlannerApi.Receipt r = service.recordOutcome(id(), 1, MAY12_DAY, "meet:emperor", up);

        assertTrue(r.errorZh, r.ok);
        assertFalse(r.duplicate);
        assertEquals(2, r.stateRevision);
        assertTrue(r.eventSeq > 0);
        SimState s = observed();
        assertEquals(7, s.rank[TestRules.EMPEROR]);
        assertEquals(MAY12_EVE, s.slot);
        assertEquals(1, service.snapshot().eventsSinceCheckpoint);
        assertEquals(START_MILLIS, service.snapshot().lastRecordMillis);
    }

    @Test
    public void retriesAreIdempotentAcrossAProcessRestart() {
        onboard(MAY12_DAY);
        String command = id();
        Outcome up = Outcome.of(Outcome.Code.RANK_UP);
        PlannerApi.Receipt first = service.recordOutcome(command, 1, MAY12_DAY, "meet:emperor", up);
        assertTrue(first.ok);

        restart();
        PlannerApi.Receipt retry = service.recordOutcome(command, 1, MAY12_DAY, "meet:emperor", Outcome.of(Outcome.Code.RANK_UP));
        assertTrue(retry.ok);
        assertTrue(retry.duplicate);
        assertEquals(first.eventSeq, retry.eventSeq);
        assertEquals(2, retry.stateRevision);
        assertEquals("applied once", 7, observed().rank[TestRules.EMPEROR]);

        PlannerApi.Receipt other = service.recordOutcome(command, 2, MAY12_EVE, "act:study_room", Outcome.of(Outcome.Code.AS_PREDICTED));
        assertFalse(other.ok);
        assertEquals("conflict", other.errorCode);
        assertEquals("刚才已经保存过另一份结果，这次没有保存", other.errorZh);
        PlannerApi.Receipt otherKind = service.voidEvent(command, 2, first.eventSeq);
        assertEquals("conflict", otherKind.errorCode);
        assertEquals(1, service.snapshot().eventsSinceCheckpoint);
    }

    @Test
    public void staleRevisionIsRejectedWithoutWriting() {
        onboard(MAY12_DAY);
        PlannerApi.Receipt r = service.recordOutcome(id(), 0, MAY12_DAY, "meet:emperor", Outcome.of(Outcome.Code.RANK_UP));
        assertFalse(r.ok);
        assertEquals("stale", r.errorCode);
        assertEquals("记录已经更新过了，请看最新的安排", r.errorZh);
        assertEquals(1, r.stateRevision);
        assertEquals(0, service.snapshot().eventsSinceCheckpoint);

        PlannerApi.Receipt profile = service.saveProfile(id(), 5, new Profile());
        assertEquals("stale", profile.errorCode);
    }

    @Test
    public void recordedSlotsAndGapsAreReported() {
        onboard(MAY12_DAY);
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);

        PlannerApi.Receipt past = record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED);
        assertEquals("invalid", past.errorCode);
        assertEquals("这个时段已经记录过了", past.errorZh);

        PlannerApi.Receipt gap = record(TestRules.slot(TestRules.MAY_12 + 1, true), "act:study_room", Outcome.Code.AS_PREDICTED);
        assertEquals("gap", gap.errorCode);
        assertEquals(2, gap.missingSlots);
        assertEquals("前面还有 2 个时段没记录，请先补上", gap.errorZh);
    }

    @Test
    public void lockedSlotsDoNotCountAsGaps() {
        int may9Day = TestRules.slot(TestRules.FULL_MOON_DAY, false);
        onboard(may9Day);
        assertTrue(record(may9Day, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertEquals("the full-moon evening is skipped", may9Day + 2, observed().slot);
        assertTrue(record(may9Day + 2, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
    }

    @Test
    public void invalidValuesGetNaturalChineseMessages() {
        onboard(MAY12_EVE);
        assertMessage(tower(MAY12_EVE, 44, true, 1_000), "楼层不能超过本区块的最高层 43F");
        assertMessage(tower(MAY12_EVE, 20, true, -5), "现金不能是负数");
        assertMessage(tower(MAY12_EVE, -1, true, 5), "楼层不能是负数");
        assertMessage(tower(MAY12_EVE, 20, false, -20_000), "按这次的增减算，现金会变成负数，请检查一下");
        PlannerApi.TowerReturn rescue = towerReturn(MAY12_EVE, 20, false, 0);
        rescue.rescuedId = "nobody";
        assertMessage(service.recordTower(id(), rev(), rescue), "没有找到这位失踪者");

        Outcome rankUp = Outcome.of(Outcome.Code.RANK_UP);
        assertMessage(service.recordOutcome(id(), rev(), MAY12_EVE, "act:study_room", rankUp), "只有和社群对象见面才能记录升级");
        Outcome lv = Outcome.of(Outcome.Code.OTHER);
        lv.statLv = new int[]{7, -1, -1};
        assertMessage(service.recordOutcome(id(), rev(), MAY12_EVE, "act:study_room", lv), "学力、魅力、勇气只能是 Lv.1 到 MAX");
        assertMessage(record(MAY12_EVE, "meet:nobody", Outcome.Code.AS_PREDICTED), "没认出这一项，请重新选一下");
        Outcome lower = Outcome.of(Outcome.Code.RANK_UP);
        lower.rank = 6;
        assertMessage(service.recordOutcome(id(), rev(), MAY12_EVE, "meet:emperor", lower), "升级后的 RANK 应该比现在高");

        SimState broke = TestRules.state(rules, MAY13_DAY);
        broke.cash = -1;
        assertMessage(service.saveCheckpoint(id(), rev(), broke, null), "现金不能是负数");
        SimState tooHigh = TestRules.state(rules, MAY13_DAY);
        tooHigh.floor = 60;
        assertMessage(service.saveCheckpoint(id(), rev(), tooHigh, null), "楼层不能超过本区块的最高层 43F");
        assertEquals(0, service.snapshot().eventsSinceCheckpoint);
    }

    @Test
    public void towerDaytimeIsRefused() {
        onboard(MAY12_DAY);
        assertMessage(tower(MAY12_DAY, 10, true, 1_000), "塔尔塔罗斯只能在晚上去");
    }

    @Test
    public void towerCashModesAreExclusiveAndAppliedOnce() {
        onboard(MAY12_EVE);
        PlannerApi.TowerReturn balance = towerReturn(MAY12_EVE, 27, true, 14_750);
        String command = id();
        PlannerApi.Receipt first = service.recordTower(command, 1, balance);
        assertTrue(first.errorZh, first.ok);
        PlannerApi.Receipt retry = service.recordTower(command, 1, balance);
        assertTrue(retry.duplicate);

        SimState s = observed();
        assertEquals(14_750, s.cash);
        assertEquals(1, s.towerVisits[1]);
        assertEquals(27, s.floor);

        assertTrue(record(MAY13_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(tower(MAY13_EVE, 25, false, 3_000).ok);
        s = observed();
        assertEquals(17_750, s.cash);
        assertEquals(2, s.towerVisits[1]);
        assertEquals(27, s.floor);
    }

    @Test
    public void correctionAddsNoVisitAndSurvivesRestart() {
        onboard(MAY12_EVE);
        PlannerApi.Receipt visit = tower(MAY12_EVE, 20, false, 3_000);
        assertTrue(visit.ok);
        PlannerApi.TowerReturn fixed = towerReturn(0, 27, true, 20_000);
        PlannerApi.Receipt fix = service.correctTower(id(), rev(), visit.eventSeq, fixed);
        assertTrue(fix.errorZh, fix.ok);

        SimState s = observed();
        assertEquals(1, s.towerVisits[1]);
        assertEquals(20_000, s.cash);
        assertEquals(27, s.floor);
        assertEquals("the visit stays at its slot", MAY13_DAY, s.slot);

        restart();
        s = observed();
        assertEquals(1, s.towerVisits[1]);
        assertEquals(20_000, s.cash);
        assertEquals(1, service.snapshot().eventsSinceCheckpoint);

        PlannerApi.TowerReturn tooHigh = towerReturn(0, 50, true, 20_000);
        assertMessage(service.correctTower(id(), rev(), visit.eventSeq, tooHigh), "楼层不能超过本区块的最高层 43F");
        PlannerApi.TowerReturn again = towerReturn(0, 30, true, 21_000);
        PlannerApi.Receipt viaFix = service.correctTower(id(), rev(), fix.eventSeq, again);
        assertTrue("correcting a correction corrects its tower record: " + viaFix.errorZh, viaFix.ok);
        s = observed();
        assertEquals(1, s.towerVisits[1]);
        assertEquals(21_000, s.cash);
        assertTrue(service.voidEvent(id(), rev(), visit.eventSeq).ok);
        assertMessage(service.correctTower(id(), rev(), visit.eventSeq, fixed), "这条记录已经撤销了，不能再修改");
    }

    @Test
    public void voidRemovesTheRecordFromReplay() {
        onboard(MAY12_DAY);
        PlannerApi.Receipt meet = record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED);
        assertEquals(7, observed().rank[TestRules.EMPEROR]);

        PlannerApi.Receipt undo = service.voidEvent(id(), rev(), meet.eventSeq);
        assertTrue(undo.errorZh, undo.ok);
        assertEquals(6, observed().rank[TestRules.EMPEROR]);
        assertEquals("the slot can be recorded again", MAY12_DAY, observed().slot);
        assertEquals(0, service.snapshot().eventsSinceCheckpoint);

        assertMessage(service.voidEvent(id(), rev(), meet.eventSeq), "这条记录已经撤销过了");
        assertMessage(service.voidEvent(id(), rev(), undo.eventSeq), "这条记录不能撤销");
        assertMessage(service.voidEvent(id(), rev(), 999), "找不到要撤销的记录");

        restart();
        assertEquals(6, observed().rank[TestRules.EMPEROR]);
        List<PlannerApi.HistoryItem> history = service.history(0);
        assertEquals("5月12日 放学后 · 撤销了「皇帝 · 小田桐秀利 · RANK 6 → 7」", history.get(0).summaryZh);
        assertTrue(history.get(1).voided);
        assertFalse(history.get(1).correctable);
    }

    @Test
    public void checkpointStartsAFreshChainButKeepsHistory() {
        onboard(MAY12_DAY);
        PlannerApi.Receipt meet = record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED);
        assertTrue(record(MAY12_EVE, "act:study_room", Outcome.Code.AS_PREDICTED).ok);
        assertEquals(2, service.snapshot().eventsSinceCheckpoint);

        SimState synced = TestRules.state(rules, TestRules.slot(60, false));
        synced.rank[TestRules.EMPEROR] = 8;
        Profile profile = new Profile();
        profile.personaBonus = true;
        long profileRev = service.snapshot().profileRevision;
        PlannerApi.Receipt sync = service.saveCheckpoint(id(), rev(), synced, profile);
        assertTrue(sync.errorZh, sync.ok);

        PlannerApi.Snapshot s = service.snapshot();
        assertEquals(0, s.eventsSinceCheckpoint);
        assertEquals(8, s.observed.rank[TestRules.EMPEROR]);
        assertEquals(TestRules.slot(60, false), s.observed.slot);
        assertEquals("profile saved in the same command", profileRev + 1, s.profileRevision);
        assertTrue(s.profile.personaBonus);

        List<PlannerApi.HistoryItem> history = service.history(10);
        assertEquals("checkpoint", history.get(0).kind);
        assertEquals("5月31日 白天 · 同步了现状", history.get(0).summaryZh);
        assertEquals(4, history.size());
        assertFalse("older records no longer shape the state", history.get(2).correctable);
        assertEquals("5月12日 放学后 · 设置了初始进度", history.get(3).summaryZh);
        assertMessage(service.voidEvent(id(), rev(), meet.eventSeq), "这条记录早于最近一次同步现状，已经不能撤销了");
    }

    @Test
    public void historyLinesReadNaturally() {
        onboard(MAY12_DAY);
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(tower(MAY12_EVE, 27, true, 14_750).ok);
        Outcome absent = Outcome.of(Outcome.Code.SKIPPED);
        absent.reason = "absent";
        assertTrue(service.recordOutcome(id(), rev(), MAY13_DAY, "meet:emperor", absent).ok);
        assertTrue(record(MAY13_EVE, "act:job_chagall", Outcome.Code.AS_PREDICTED).ok);
        int may14 = TestRules.slot(TestRules.MAY_12 + 2, false);
        Outcome noUp = Outcome.of(Outcome.Code.NO_RANK_UP);
        noUp.readyCue = true;
        assertTrue(service.recordOutcome(id(), rev(), may14, "meet:emperor", noUp).ok);
        Outcome unwilling = Outcome.of(Outcome.Code.SKIPPED);
        unwilling.reason = "unwilling";
        assertTrue(service.recordOutcome(id(), rev(), may14 + 1, "act:study_room", unwilling).ok);
        PlannerApi.TowerReturn netLoss = towerReturn(0, 27, false, -1_200);
        long towerSeq = service.history(0).get(4).seq;
        assertTrue(service.correctTower(id(), rev(), towerSeq, netLoss).ok);

        List<String> lines = new ArrayList<>();
        for (PlannerApi.HistoryItem item : service.history(0)) {
            lines.add(item.summaryZh);
        }
        assertEquals(List.of(
                "5月12日 晚上 · 修改了这次探索 · 到达 27F · 少了 1,200 日元",
                "5月14日 晚上 · 在宿舍房间学习 · 跳过了",
                "5月14日 放学后 · 皇帝 · 小田桐秀利 · 见面了 · 没升级 · 游戏提示下次能升级",
                "5月13日 晚上 · 咖啡店「夏卡尔」打工 · 魅力 +1 · 勇气 +1 · 收入 2,500 日元",
                "5月13日 放学后 · 皇帝 · 小田桐秀利 · 没见到（对方不在）",
                "5月12日 晚上 · 探索塔尔塔罗斯 · 到达 27F · 出塔后余额 14,750 日元",
                "5月12日 放学后 · 皇帝 · 小田桐秀利 · RANK 6 → 7",
                "5月12日 放学后 · 设置了初始进度"), lines);
        Pattern internal = Pattern.compile("[a-z]+:|[a-z]{4,}");
        for (String line : lines) {
            assertFalse("no internal ids shown: " + line, internal.matcher(line).find());
        }
        assertEquals(2, service.history(2).size());
    }

    @Test
    public void sundayDaytimeIsCalledDaytime() {
        int may17 = TestRules.slot(46, false);
        onboard(may17);
        assertEquals("5月17日 白天 · 设置了初始进度", service.history(1).get(0).summaryZh);
    }

    @Test
    public void alternativesAreLabelledInChinese() {
        onboard(MAY12_DAY);
        settle();
        List<Plan.Step> day = service.alternatives(MAY12_DAY);
        assertEquals(1, day.size());
        assertEquals("皇帝 · 小田桐秀利", day.get(0).titleZh);
        assertEquals("RANK 6 → 7", day.get(0).subtitleZh);
        assertEquals("social", day.get(0).kind);
        assertEquals("meet:emperor", day.get(0).action);
        assertEquals("月光馆学园 3楼 学生会室", day.get(0).locationZh);
        assertTrue(day.get(0).estimate);

        List<Plan.Step> evening = service.alternatives(MAY12_EVE);
        assertEquals(3, evening.size());
        assertEquals("在宿舍房间学习", evening.get(0).titleZh);
        assertEquals("学力 +2", evening.get(0).subtitleZh);
        assertEquals("+2,500 日元 · 魅力 +1 · 勇气 +1", evening.get(1).subtitleZh);
        assertEquals("探索塔尔塔罗斯", evening.get(2).titleZh);
        assertEquals("阿尔卡 · 本区块第 1 次", evening.get(2).subtitleZh);
        List<Plan.Step> nextDay = service.alternatives(MAY13_DAY);
        assertEquals("the forecast follows the plan's meeting on May 12", "RANK 7 → 8", nextDay.get(0).subtitleZh);

        assertTrue("past slot", service.alternatives(MAY12_DAY - 1).isEmpty());
        assertTrue("locked slot", service.alternatives(rules.lastSlot()).isEmpty());
    }

    private static void assertMessage(PlannerApi.Receipt r, String zh) {
        assertFalse("expected a rejection: " + zh, r.ok);
        assertEquals(zh, r.errorZh);
    }
}
