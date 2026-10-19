package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/** Yesterday is a ledger entry point, including corrections while later records remain. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScheduleHistoryUiTest extends UiTestBase {
    @Test
    public void calendarKeepsExactlyOnePreviousGameDayEvenWithAnOlderForecast() {
        Plan plan = api.snap.plan;
        plan.fromSlot = Rules.slot(Fixtures.day(4, 21), false);
        List<SchedulePage.Item> items = SchedulePage.buildItems(plan, Fixtures.SLOT_NOW, api.rules.firstDay);
        List<Integer> past = new ArrayList<>();
        for (SchedulePage.Item item : items) {
            if (item.type == 2 && item.day < Fixtures.OCT_22) past.add(item.day);
        }
        assertEquals(java.util.List.of(Fixtures.OCT_22 - 1), past);
        assertTrue(items.stream().anyMatch(item -> item.type == 2 && item.day == Rules.dayOf(plan.toSlot)));
    }

    @Test
    public void previousDayCrossesMonthsAndStopsAtTheGameCalendarBoundary() {
        int october = Fixtures.day(10, 1);
        List<SchedulePage.Item> items = SchedulePage.buildItems(api.snap.plan, Rules.slot(october, false), api.rules.firstDay);
        assertTrue(items.stream().anyMatch(item -> item.type == 2 && item.day == october - 1 && item.month == 9));
        assertTrue(items.stream().anyMatch(item -> item.type == 2 && item.day == october && item.month == 10));
        List<SchedulePage.Item> first = SchedulePage.buildItems(api.snap.plan,
                Rules.slot(api.rules.firstDay, false), api.rules.firstDay);
        assertFalse(first.stream().anyMatch(item -> item.type == 2 && item.day < api.rules.firstDay));
    }

    @Test
    public void aPastCalendarRowShowsTheActualRecordAndIgnoresVoidedAndCorrectionJournalRows() {
        int slot = Fixtures.SLOT_NOW - 1;
        List<PlannerApi.HistoryItem> history = new ArrayList<>(api.history);
        history.add(0, FakePlannerApi.item(102, slot, "outcome", "10月21日 晚上 · 错误旧结果", true, true));
        history.add(0, FakePlannerApi.item(103, slot, "outcome_fix", "10月21日 晚上 · 修改记录", false, true));
        Plan.Step row = SchedulePage.recordedStep(api.rules, history, slot, false);
        assertEquals("在宿舍房间学习 · 学力 +2", row.titleZh);
        assertEquals("点按查看或修改", row.subtitleZh);
    }

    @Test
    public void correctedRankShowsTheLatestEffectiveResultAndUndoRestoresTheOriginal() {
        int slot = Fixtures.SLOT_NOW - 2;
        PlannerApi.HistoryItem original = FakePlannerApi.item(110, slot, "outcome",
                "10月21日 放学后 · 皇帝 · 小田桐秀利 · RANK 6 → 7", false, true);
        PlannerApi.HistoryItem fix = FakePlannerApi.item(111, slot, "outcome_fix",
                "10月21日 放学后 · 修改了这次结果 · 皇帝 · 小田桐秀利 · 没升级", false, true);
        fix.originalSeq = original.seq;
        List<PlannerApi.HistoryItem> rows = java.util.List.of(original, fix);
        assertEquals("皇帝 · 小田桐秀利 · 没升级", SchedulePage.recordedStep(api.rules, rows, slot, false).titleZh);
        assertEquals("history retains its audit wording", "10月21日 放学后 · 修改了这次结果 · 皇帝 · 小田桐秀利 · 没升级",
                fix.summaryZh);
        fix.voided = true;
        assertEquals("皇帝 · 小田桐秀利 · RANK 6 → 7", SchedulePage.recordedStep(api.rules, rows, slot, false).titleZh);
    }

    @Test
    public void undoingTheNewestFixRevealsTheOlderFixRatherThanTheOriginalPrediction() {
        int slot = Fixtures.SLOT_NOW - 2;
        PlannerApi.HistoryItem original = FakePlannerApi.item(110, slot, "outcome",
                "10月21日 放学后 · 皇帝 · RANK 7", false, true);
        PlannerApi.HistoryItem first = FakePlannerApi.item(111, slot, "outcome_fix",
                "10月21日 放学后 · 修改了这次结果 · 皇帝 · 没升级", false, true);
        PlannerApi.HistoryItem second = FakePlannerApi.item(112, slot, "outcome_fix",
                "10月21日 放学后 · 修改了这次结果 · 皇帝 · RANK 8", false, true);
        first.originalSeq = second.originalSeq = original.seq;
        List<PlannerApi.HistoryItem> rows = java.util.List.of(second, original, first);
        assertEquals("皇帝 · RANK 8", SchedulePage.recordedStep(api.rules, rows, slot, false).titleZh);
        second.voided = true;
        assertEquals("皇帝 · 没升级", SchedulePage.recordedStep(api.rules, rows, slot, false).titleZh);
    }

    @Test
    public void aFixOfAVoidedOrDifferentOriginalCannotReplaceThisSlotsRecord() {
        int slot = Fixtures.SLOT_NOW - 2;
        PlannerApi.HistoryItem original = FakePlannerApi.item(110, slot, "outcome",
                "10月21日 放学后 · 皇帝 · RANK 7", false, true);
        PlannerApi.HistoryItem fix = FakePlannerApi.item(111, slot, "outcome_fix",
                "10月21日 放学后 · 修改了这次结果 · 皇帝 · 没升级", false, true);
        fix.originalSeq = 99;
        assertEquals(original, HistoryPage.activeAtSlot(java.util.List.of(fix, original), slot));
        fix.originalSeq = original.seq;
        original.voided = true;
        assertTrue(HistoryPage.activeAtSlot(java.util.List.of(fix, original), slot) == null);
    }

    @Test
    public void correctedTowerFloorShowsTheEffectiveFloorWithoutTheHistoryPrefix() {
        int slot = Fixtures.SLOT_NOW - 1;
        PlannerApi.HistoryItem original = FakePlannerApi.item(110, slot, "tower",
                "10月21日 晚上 · 探索塔尔塔罗斯 · 到达 96F", false, true);
        PlannerApi.HistoryItem fix = FakePlannerApi.item(111, slot, "tower_fix",
                "10月21日 晚上 · 修改了这次探索 · 到达 100F", false, true);
        fix.originalSeq = original.seq;
        assertEquals("到达 100F", SchedulePage.recordedStep(api.rules, java.util.List.of(fix, original), slot, false).titleZh);
        fix.voided = true;
        assertEquals("探索塔尔塔罗斯 · 到达 96F",
                SchedulePage.recordedStep(api.rules, java.util.List.of(fix, original), slot, false).titleZh);
    }

    @Test
    public void yesterdayOpensOnlyItsRecordsAndOffersTheExistingUndoFlow() {
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.idle();
        Shots.click(home, "在宿舍房间学习 · 学力 +2");
        assertTrue(Shots.has(home, "10月21日 晚上 · 在宿舍房间学习 · 学力 +2"));
        assertFalse(Shots.has(home, "10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元"));
        Shots.save(Shots.capture(home, false), "home_yesterday_records");
        Shots.click(home, "撤销这条记录：10月21日 晚上 · 在宿舍房间学习 · 学力 +2");
        assertTrue(api.calls.isEmpty());
        Shots.clickInDialog("撤销");
        assertEquals("voidEvent", api.last().method);
        assertEquals(98, api.last().targetSeq);
    }

    @Test
    public void historyEditOpensCorrectionModeRatherThanRecordingThePastSlotAgain() {
        HomeActivity home = home();
        home.openDetail(Fixtures.SLOT_NOW - 1);
        Shots.idle();
        Shots.click(home, "修改这条记录：10月21日 晚上 · 在宿舍房间学习 · 学力 +2");
        Intent intent = Shadows.shadowOf(home).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(98, intent.getLongExtra(Intents.EXTRA_CORRECT_SEQ, -1));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void changingAnEarlierRankResultUsesItsBeforeStateAndPreservesTheCurrentCursor() {
        PlannerApi.OutcomeCorrection correction = rankCorrection();
        api.snap.observed.rank[4] = 9;
        int current = api.snap.observed.slot;
        ResultActivity activity = correction(correction.targetSeq);
        assertTrue(Shots.has(activity, "RANK 6 → 7"));
        assertTrue(Shots.has(activity, "原来记的是：皇帝 RANK 7"));
        assertFalse("the historical action is fixed", Shots.has(activity, ResultActivity.OTHER_INSTEAD));
        assertFalse("the historical action is fixed", Shots.has(activity, "做了别的…"));
        Shots.click(activity, "没升级");
        Shots.click(activity, "没有");
        FakePlannerApi.Call call = api.last();
        assertEquals("correctOutcome", call.method);
        assertEquals(correction.targetSeq, call.targetSeq);
        assertEquals(correction.slot, call.slot);
        assertEquals("meet:emperor", call.actionKey);
        assertEquals(Outcome.Code.NO_RANK_UP, call.outcome.code);
        assertEquals(6, call.outcome.rank);
        assertEquals(current, api.snap.observed.slot);
        assertTrue(api.callsOf("recordOutcome").isEmpty());
        assertTrue(api.callsOf("saveCheckpoint").isEmpty());
        assertTrue(activity.isFinishing());
    }

    @Test
    public void staleCorrectionReloadsTheOldRecordInsteadOfJumpingToToday() {
        PlannerApi.OutcomeCorrection correction = rankCorrection();
        ResultActivity activity = correction(correction.targetSeq);
        api.nextReceipt = FakePlannerApi.stale();
        api.snap.stateRevision++;
        Shots.click(activity, "没升级");
        Shots.click(activity, "没有");
        assertFalse(activity.isFinishing());
        assertTrue(Shots.has(activity, Messages.STALE));
        assertTrue(Shots.has(activity, "RANK 6 → 7"));
        Shots.click(activity, "没升级");
        Shots.click(activity, "没有");
        assertEquals(correction.targetSeq, api.last().targetSeq);
        assertEquals(2, api.callsOf("correctOutcome").size());
        assertTrue(api.callsOf("recordOutcome").isEmpty());
    }

    @Test
    public void aRecordThatNoLongerBelongsToTheCheckpointCannotBeEdited() {
        ResultActivity activity = correction(91);
        assertTrue(Shots.has(activity, "这条记录刚改过"));
        assertTrue(api.calls.isEmpty());
    }

    private PlannerApi.OutcomeCorrection rankCorrection() {
        PlannerApi.OutcomeCorrection correction = new PlannerApi.OutcomeCorrection();
        correction.targetSeq = 96;
        correction.stateRevision = api.snap.stateRevision;
        correction.slot = Fixtures.SLOT_NOW - 2;
        correction.actionKey = "meet:emperor";
        correction.outcome = Outcomes.rankUp(7);
        SimState before = api.snap.observed.copy();
        before.slot = correction.slot;
        before.rank[4] = 6;
        before.ready[4] = true;
        correction.beforeState = before;
        api.corrections.put(correction.targetSeq, correction);
        return correction;
    }

    private ResultActivity correction(long seq) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ResultActivity.class);
        intent.putExtra(Intents.EXTRA_CORRECT_SEQ, seq);
        intent.putExtra(HomeActivity.EXTRA_FROM_APP, true);
        ResultActivity activity = Robolectric.buildActivity(ResultActivity.class, intent).setup().get();
        Shots.idle();
        return activity;
    }
}
