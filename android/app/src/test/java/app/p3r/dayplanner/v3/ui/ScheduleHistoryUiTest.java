package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

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
        assertTrue("no 「点按查看或修改」 under a recorded line", row.subtitleZh == null || row.subtitleZh.isEmpty());
    }

    /** A past slot nothing was recorded for is a faint dash (blank while reading); a story slot says the story. */
    @Test
    public void aPastSlotWithoutARecordIsADashAndAStorySlotNamesTheStory() {
        int free = Fixtures.SLOT_NOW - 2;
        List<PlannerApi.HistoryItem> none = new ArrayList<>();
        assertEquals(SchedulePage.NO_RECORD, SchedulePage.recordedStep(api.rules, none, free, false).titleZh);
        assertEquals("", SchedulePage.recordedStep(api.rules, none, free, true).titleZh);
        int fullMoon = Rules.slot(Fixtures.day(11, 3), true);
        assertEquals("full_moon", api.rules.lockOf(fullMoon));
        Plan.Step story = SchedulePage.recordedStep(api.rules, none, fullMoon, false);
        assertEquals(StepKind.LOCKED, StepKind.of(story));
        assertEquals("满月作战", story.titleZh);

        // On 日程: yesterday's 放学后 had no record here, so it is a dash and opens nothing.
        api.history.removeIf(item -> item.slot == free);
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.capture(home, false);
        android.view.View dash = Shots.find(home, SchedulePage.NO_RECORD);
        for (android.view.View v = dash; !(v instanceof android.widget.ListView); v = (android.view.View) v.getParent()) {
            assertFalse("nothing to open: " + v, v.isClickable());
        }
        assertFalse("no 「暂无记录」", Shots.has(home, "暂无记录"));
        Shots.shot(home, "home_schedule_yesterday");
    }

    @Test
    public void theRecordsOfThePastDaySheetStartWithTheTappedSlot() {
        int evening = Fixtures.SLOT_NOW - 1;
        int afternoon = Fixtures.SLOT_NOW - 2;
        api.history.add(0, FakePlannerApi.item(99, afternoon, "outcome", "10月21日 放学后 · 在图书馆学习 · 学力 +2", false, true));
        HomeActivity home = home();
        home.openDetail(evening);
        Shots.idle();
        String all = Shots.allText(home.sheets());
        assertTrue(all, all.indexOf("在宿舍房间学习 · 学力 +2") < all.indexOf("在图书馆学习 · 学力 +2"));
        assertFalse("the date is on the plate, not on every row", all.contains("10月21日 晚上 · "));
        home.dismissSheet();
        home.openDetail(afternoon);
        Shots.idle();
        all = Shots.allText(home.sheets());
        assertTrue(all, all.indexOf("在图书馆学习 · 学力 +2") < all.indexOf("在宿舍房间学习 · 学力 +2"));
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

    /**
     * 记录历史 says which record is in effect: the correction carries 「已修改」 and the actions, the
     * record it replaced is faint 「原来记的」 with nothing to press (undoing it would drop the slot's
     * record with its correction).
     */
    @Test
    public void aCorrectionIsMarkedAndTheRecordItReplacedOffersNothing() {
        int slot = Fixtures.SLOT_NOW - 2;
        PlannerApi.HistoryItem original = FakePlannerApi.item(110, slot, "outcome",
                "10月21日 放学后 · 皇帝 · 小田桐秀利 · RANK 6 → 7", false, true);
        PlannerApi.HistoryItem fix = FakePlannerApi.item(111, slot, "outcome_fix",
                "10月21日 放学后 · 修改了这次结果 · 皇帝 · 小田桐秀利 · 没升级", false, true);
        fix.originalSeq = original.seq;
        api.history.add(0, original);
        api.history.add(0, fix);
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_ME);
        Shots.click(home, "记录历史");
        assertEquals(1, Shots.findAll(home.getWindow().getDecorView(), HistoryPage.FIXED).size());
        assertEquals(1, Shots.findAll(home.getWindow().getDecorView(), HistoryPage.REPLACED).size());
        assertTrue(Shots.has(home, "撤销这条记录：" + fix.summaryZh));
        assertTrue(Shots.has(home, "修改这条记录：" + fix.summaryZh));
        assertFalse(Shots.has(home, "撤销这条记录：" + original.summaryZh));
        assertFalse(Shots.has(home, "修改这条记录：" + original.summaryZh));
        assertTrue(Shots.has(home, original.summaryZh + "，" + HistoryPage.REPLACED));
        String all = Shots.allText(home.getWindow().getDecorView());
        assertTrue("the correction above what it replaced: " + all,
                all.indexOf("皇帝 · 小田桐秀利 · 没升级") < all.indexOf("皇帝 · 小田桐秀利 · RANK 6 → 7"));
        Shots.shot(home, "home_history_corrected");
    }

    /** A corrected tower visit is edited from its correction, prefilled with the values in effect. */
    @Test
    public void aCorrectedTowerVisitIsEditedFromItsCorrection() {
        PlannerApi.HistoryItem fix = FakePlannerApi.item(111, Fixtures.SLOT_NOW - 3, "tower_fix",
                "10月20日 晚上 · 修改了这次探索 · 到达 100F · 余额 18,500 日元", false, true);
        fix.originalSeq = 97;
        fix.tower = FakePlannerApi.towerValues(Fixtures.SLOT_NOW - 3, 3, 100, true, 18_500, null);
        api.history.add(0, fix);
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_ME);
        Shots.click(home, "记录历史");
        assertFalse(Shots.has(home, "修改这条记录：10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元"));
        Shots.click(home, "修改这条记录：" + fix.summaryZh);
        Shots.click(home, "保存修改");
        assertEquals("correctTower", api.last().method);
        assertEquals("the visit itself is corrected", 97, api.last().targetSeq);
        assertEquals(100, api.last().tower.floor);
    }

    /** A 修改 saved on the result sheet shows in the open 记录历史 and day sheet at once. */
    @Test
    public void theOpenHistoryAndDaySheetFollowACorrectionSavedElsewhere() {
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_ME);
        Shots.click(home, "记录历史");
        View before = Shots.find(home, "皇帝 RANK 6");
        PlannerApi.HistoryItem fix = FakePlannerApi.item(111, Fixtures.SLOT_NOW - 1, "outcome_fix",
                "10月21日 晚上 · 修改了这次结果 · 在宿舍房间学习 · 学力 +1", false, true);
        fix.originalSeq = 98;
        saveElsewhere(fix);
        assertTrue(Shots.has(home, "在宿舍房间学习 · 学力 +1"));
        assertTrue(Shots.has(home, HistoryPage.FIXED));
        assertFalse(Shots.has(home, "撤销这条记录：10月21日 晚上 · 在宿舍房间学习 · 学力 +2"));
        assertTrue("the rows were read again", before != Shots.find(home, "皇帝 RANK 6"));

        home.popPage();
        home.openDetail(Fixtures.SLOT_NOW - 4);
        Shots.idle();
        PlannerApi.HistoryItem dayFix = FakePlannerApi.item(112, Fixtures.SLOT_NOW - 4, "outcome_fix",
                "10月20日 放学后 · 修改了这次结果 · 皇帝 RANK 7", false, true);
        dayFix.originalSeq = 96;
        saveElsewhere(dayFix);
        String sheet = Shots.allText(home.sheets());
        assertTrue(sheet, sheet.contains("皇帝 RANK 7") && sheet.contains(HistoryPage.REPLACED));
    }

    /** What ResultActivity's save does to the ledger, then the service's change notice. */
    private void saveElsewhere(PlannerApi.HistoryItem fix) {
        api.history.add(0, fix);
        api.snap.stateRevision++;
        api.fireChanged();
        Shots.idle();
    }

    /** A record of an older day corrected later stays under that day's one header. */
    @Test
    public void aLaterCorrectionOfAnOlderDayStaysUnderThatDay() {
        PlannerApi.HistoryItem fix = FakePlannerApi.item(111, Fixtures.SLOT_NOW - 4, "outcome_fix",
                "10月20日 放学后 · 修改了这次结果 · 皇帝 RANK 7", false, true);
        fix.originalSeq = 96;
        api.history.add(0, fix);
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_ME);
        Shots.click(home, "记录历史");
        View page = home.getWindow().getDecorView();
        assertEquals(1, Shots.findAll(page, "10月20日 周二").size());
        String all = Shots.allText(page);
        assertTrue(all, all.indexOf("在宿舍房间学习 · 学力 +2") < all.indexOf("皇帝 RANK 7"));
        assertTrue(all, all.indexOf("皇帝 RANK 7") < all.indexOf("皇帝 RANK 6"));
    }

    /** 「再看更早的」 adds the older records under the rows on screen instead of building them again. */
    @Test
    public void olderRecordsAreAddedUnderTheRowsOnScreen() {
        for (int i = 0; i < 90; i++) {
            api.history.add(FakePlannerApi.item(10 + i, Fixtures.SLOT_NOW - 20 - i, "outcome",
                    Zh.slot(api.rules, Fixtures.SLOT_NOW - 20 - i) + " · 旧记录 " + i, false, false));
        }
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_ME);
        Shots.click(home, "记录历史");
        View first = Shots.find(home, "在宿舍房间学习 · 学力 +2");
        Shots.click(home, HistoryPage.MORE);
        assertTrue(Shots.has(home, "旧记录 89"));
        assertTrue("kept, not rebuilt", first == Shots.find(home, "在宿舍房间学习 · 学力 +2"));
        assertFalse(Shots.has(home, HistoryPage.MORE));
    }

    /**
     * An undo saved while the page is open, then 「再看更早的」 before the page has read the records
     * again: the older rows come with the undone record struck through, not with the rows as they were.
     */
    @Test
    public void olderRecordsTappedRightAfterAnUndoDoNotKeepTheUndoneRow() {
        for (int i = 0; i < 90; i++) {
            api.history.add(FakePlannerApi.item(1000 + i, Fixtures.SLOT_NOW - 20 - i, "outcome",
                    Zh.slot(api.rules, Fixtures.SLOT_NOW - 20 - i) + " · 旧记录 " + i, false, false));
        }
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_ME);
        Shots.click(home, "记录历史");
        String summary = "10月21日 晚上 · 在宿舍房间学习 · 学力 +2";
        String undo = "撤销这条记录：" + summary;
        assertTrue(Shots.has(home, undo));
        // The undo as the store keeps it: the record voided, a hidden 撤销 row on top (no seq on screen changes).
        api.history.replaceAll(x -> x.seq == 98 ? FakePlannerApi.item(98, x.slot, x.kind, summary, true, true) : x);
        api.history.add(0, FakePlannerApi.item(200, Fixtures.SLOT_NOW - 1, "void", "撤销了「在宿舍房间学习」", false, true));
        api.snap.stateRevision++;
        api.fireChanged();
        // Tapped before the page's own read is answered.
        assertTrue(Shots.clickTarget(Shots.find(home, HistoryPage.MORE)).performClick());
        Shots.idle();
        assertTrue(Shots.has(home, "旧记录 89"));
        assertFalse("the undone record offers nothing", Shots.has(home, undo));
        assertTrue(Shots.has(home, "在宿舍房间学习 · 学力 +2"));
    }

    /** After 1/31 the last day is just the last day: no 「昨天」, not dimmed. */
    @Test
    public void aFinishedCalendarDoesNotCallItsLastDayYesterday() {
        api.snap.observed.slot = api.rules.lastSlot() + 1;
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.capture(home, false);
        ViewGroup row = (ViewGroup) described(home.getWindow().getDecorView(), Zh.dayAndWeekday(api.rules.lastDay));
        assertNotNull("1月31日 is listed", row);
        assertFalse(Shots.has(home, "昨天"));
        assertEquals("not dimmed", 1f, row.getChildAt(0).getAlpha(), 0.001f);
    }

    /** The first view whose content description starts with {@code prefix}. */
    private static View described(View v, String prefix) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v.getContentDescription() != null && v.getContentDescription().toString().startsWith(prefix)) return v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View found = described(((ViewGroup) v).getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
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

    /**
     * A tower night recorded as 没去成 is corrected on the 做不了 panel under the tower's tag: another
     * reason, or 「其实去了」 (never a bare 「继续」, never the tower form's new visit).
     */
    @Test
    public void aTowerNightIsCorrectedWithAReasonOrCountedAsAVisit() {
        PlannerApi.OutcomeCorrection correction = new PlannerApi.OutcomeCorrection();
        correction.targetSeq = 97;
        correction.stateRevision = api.snap.stateRevision;
        correction.slot = Fixtures.SLOT_NOW - 3;
        correction.actionKey = "tower:3";
        correction.outcome = Outcomes.skipped("unwilling", -1);
        SimState before = api.snap.observed.copy();
        before.slot = correction.slot;
        correction.beforeState = before;
        api.corrections.put(correction.targetSeq, correction);

        ResultActivity activity = correction(correction.targetSeq);
        assertTrue(Shots.has(activity, StepKind.TOWER.tagZh));
        assertTrue(Shots.has(activity, "怎么没做成？"));
        assertTrue(Shots.has(activity, "原来记的是：探索塔尔塔罗斯 · 不想做"));
        assertFalse(Shots.has(activity, "继续"));
        assertFalse(Shots.has(activity, ResultActivity.OTHER_INSTEAD));
        Shots.shot(activity, "result_correct_tower_night");
        Shots.click(activity, ResultActivity.WENT_AFTER_ALL);
        FakePlannerApi.Call call = api.last();
        assertEquals("correctOutcome", call.method);
        assertEquals(correction.targetSeq, call.targetSeq);
        assertEquals(Outcome.Code.AS_PREDICTED, call.outcome.code);
        assertTrue(api.callsOf("recordTower").isEmpty());
        assertTrue(activity.isFinishing());

        // Now recorded as a visit: the panel only asks why it fell through.
        activity = correction(correction.targetSeq);
        assertTrue(Shots.has(activity, "原来记的是：探索塔尔塔罗斯"));
        assertFalse(Shots.has(activity, ResultActivity.WENT_AFTER_ALL));
        Shots.click(activity, Outcomes.SKIP_REASONS.get("story"));
        assertEquals(Outcome.Code.SKIPPED, api.last().outcome.code);
        assertEquals("story", api.last().outcome.reason);
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
