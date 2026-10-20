package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * The 3.6 day sheet, 记录历史 and date picker (design §6.2): the sheet mirrors the 今天 hero and never
 * explains the plan, 记录历史 is grouped by day with one 同步现状 divider and a retry, and the picker
 * turns one month at a time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DaySheetHistoryUiTest extends UiTestBase {
    private static final String[] PLANNER_TALK = {"为什么这样安排", "安排依据", "这一步用到的估计", "这天：",
        "点按查看或修改", "这是之后的安排", "选能让对方高兴的回答"};

    private static void assertNoPlannerTalk(View root) {
        String all = Shots.allText(root);
        for (String talk : PLANNER_TALK) {
            assertFalse(talk + " in: " + all, all.contains(talk));
        }
    }

    // ------------------------------------------------------------------ day sheet

    @Test
    public void theCurrentSlotHasExactlyTheHerosActions() {
        HomeActivity a = home();
        a.openDetail(Fixtures.SLOT_NOW);
        Shots.idle();
        View sheet = a.sheets();
        Shots.find(sheet, TodayPage.CANNOT_LABEL);
        Shots.find(sheet, TodayPage.DONE_LABEL);
        assertTrue("the 做不了 sheet offers 改做别的…", Shots.findAll(sheet, "改做别的…").isEmpty());
        assertTrue(Shots.findAll(sheet, "做不了").isEmpty());
        assertNoPlannerTalk(sheet);
        Shots.clickTarget(Shots.find(sheet, TodayPage.CANNOT_LABEL)).performClick();
        Shots.idle();
        assertFalse(a.sheets().isShowing());
        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(Intents.MODE_CANNOT, next.getStringExtra(Intents.EXTRA_MODE));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void aLaterSlotOffersOnlyReachedHereWithoutACaption() {
        HomeActivity a = home();
        a.openDetail(Fixtures.SLOT_NOW + 2);
        Shots.idle();
        View reach = Shots.find(a.sheets(), DayDetailSheet.REACHED);
        assertTrue(Shots.findAll(a.sheets(), TodayPage.DONE_LABEL).isEmpty());
        assertNoPlannerTalk(a.sheets());
        Shots.clickTarget(reach).performClick();
        Shots.idle();
        Intent next = shadowOf(a).getNextStartedActivity();
        assertEquals(Intents.MODE_RESULT, next.getStringExtra(Intents.EXTRA_MODE));
        assertEquals(Fixtures.SLOT_NOW + 2, next.getIntExtra(Intents.EXTRA_SLOT, -1));
    }

    @Test
    public void aStorySlotSaysTheStoryOnceWithNoHowToAndNothingToPress() {
        int fullMoon = Rules.slot(Fixtures.day(11, 3), true);
        // The rule pack's note of 11/3; the engine also puts it in the locked step's detail.
        api.rules.day(Fixtures.day(11, 3)).noteZh = "文化节";
        api.snap.plan.at(fullMoon).detailZh = "文化节";
        HomeActivity a = home();
        a.openDetail(fullMoon);
        Shots.idle();
        View sheet = a.sheets();
        Shots.find(sheet, "满月作战");
        Shots.find(sheet, "文化节");
        String all = Shots.allText(sheet);
        assertFalse(all, all.contains("怎么做"));
        assertFalse(all, all.contains("不能自由行动"));
        assertTrue(Shots.findAll(sheet, DayDetailSheet.REACHED).isEmpty());
        assertTrue(Shots.findAll(sheet, TodayPage.DONE_LABEL).isEmpty());
        assertNoPlannerTalk(sheet);
        Shots.shot(a, "day_sheet_story");
    }

    @Test
    public void theDayNoteIsOneLineAndTheChristmasNoteSaysTheChoice() {
        Rules rules = api.rules;
        // The rule pack's words (p3r_rules.json days[].noteZh).
        rules.day(Fixtures.day(12, 24)).noteZh = "平安夜：默认按婉拒所有邀约来安排；选了赴约的对象，白天和晚上就都留给平安夜约会";
        rules.day(Fixtures.day(10, 30)).noteZh = "期中考试放榜";
        rules.day(Fixtures.day(7, 21)).noteZh = "暑期补习：学力 +3";
        assertEquals("平安夜 · 邀约都婉拒了", DayDetailSheet.dayNote(rules, api.snap.profile, Fixtures.day(12, 24), null));
        assertEquals("期中考试放榜", DayDetailSheet.dayNote(rules, api.snap.profile, Fixtures.day(10, 30), null));
        assertNull(DayDetailSheet.dayNote(rules, api.snap.profile, Fixtures.day(10, 29), null));
        Plan.Step summer = new Plan.Step();
        summer.titleZh = "暑期补习";
        summer.subtitleZh = "暑期补习：学力 +3";
        assertNull("said by the step already", DayDetailSheet.dayNote(rules, api.snap.profile, Fixtures.day(7, 21), summer));
        // A story slot shows its title and its own line only (no 怎么做), so its detail does not count.
        rules.day(Fixtures.day(11, 3)).noteZh = "文化节";
        Plan.Step moon = new Plan.Step();
        moon.kind = "locked";
        moon.action = "locked:full_moon";
        moon.titleZh = "满月作战";
        moon.subtitleZh = "这段时间不能自由行动";
        moon.detailZh = "文化节";
        assertEquals("文化节", DayDetailSheet.dayNote(rules, api.snap.profile, Fixtures.day(11, 3), moon));
        Plan.Step classes = new Plan.Step();
        classes.kind = "locked";
        classes.action = "locked:summer_classes";
        classes.titleZh = "暑期补习";
        classes.subtitleZh = "这段时间不能自由行动\n剧情自动获得：学力 +3";
        classes.detailZh = "暑期补习：学力 +3";
        assertNull("the title says it", DayDetailSheet.dayNote(rules, api.snap.profile, Fixtures.day(7, 21), classes));
        Plan.Step trip = new Plan.Step();
        trip.subtitleZh = "修学旅行，不能自由行动";
        assertNull(DayDetailSheet.lockedLine(trip, "修学旅行"));
        trip.subtitleZh = "这段时间不能自由行动";
        assertNull(DayDetailSheet.lockedLine(trip, "满月作战"));
    }

    // ------------------------------------------------------------------ 记录历史

    @Test
    public void historyIsGroupedByDayWithoutUndoJournalRowsOrCaptions() {
        PlannerApi.HistoryItem journal = FakePlannerApi.item(101, Fixtures.SLOT_NOW - 6, "void",
            "10月19日 放学后 · 撤销了「跳过 · 对方不在」", false, true);
        api.history.add(0, journal);
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "记录历史");
        View page = a.getWindow().getDecorView();
        String all = Shots.allText(page);
        assertFalse("undo journal rows are not listed", all.contains("撤销了"));
        assertFalse("no recompute explanation", all.contains("重新计算"));
        assertFalse("no trailing 记录 after the age", all.contains("前记录"));
        assertTrue("one header per game day", Shots.has(a, "10月21日 周三") && Shots.has(a, "10月20日 周二"));
        assertTrue("rows say the result, the header says the day", Shots.has(a, "在宿舍房间学习 · 学力 +2"));
        assertTrue("TalkBack still reads the whole record", Shots.has(a, "10月21日 晚上 · 在宿舍房间学习 · 学力 +2"));
        TextView undone = (TextView) Shots.find(a, "跳过 · 对方不在");
        assertTrue("an undone record is struck through", (undone.getPaintFlags() & Paint.STRIKE_THRU_TEXT_FLAG) != 0);
        assertTrue(Shots.has(a, "已撤销"));
        Shots.shot(a, "home_history");
    }

    @Test
    public void undoAsksWithTheRecordOnly() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "记录历史");
        Shots.click(a, "撤销这条记录：10月21日 晚上 · 在宿舍房间学习 · 学力 +2");
        View dialog = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        String text = Shots.allText(dialog);
        assertTrue(text, text.contains(HistoryPage.UNDO_TITLE));
        assertTrue(text, text.contains("10月21日 晚上 · 在宿舍房间学习 · 学力 +2"));
        assertFalse(text, text.contains("重新计算"));
        api.nextReceipt = FakePlannerApi.stale();
        Shots.clickInDialog("撤销");
        assertEquals("a rejected undo says so in red", Messages.STALE, a.undoBar().messageText().toString());
    }

    @Test
    public void aLongHistoryOffersTheOlderRecords() {
        for (int i = 0; i < 90; i++) {
            api.history.add(FakePlannerApi.item(10 + i, Fixtures.SLOT_NOW - 20 - i, "outcome",
                Zh.slot(api.rules, Fixtures.SLOT_NOW - 20 - i) + " · 旧记录 " + i, false, false));
        }
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "记录历史");
        assertTrue(Shots.has(a, HistoryPage.MORE));
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("旧记录 89"));
        Shots.click(a, HistoryPage.MORE);
        assertTrue(Shots.allText(a.getWindow().getDecorView()).contains("旧记录 89"));
        assertFalse("everything is in", Shots.has(a, HistoryPage.MORE));
    }

    @Test
    public void unreadableRecordsOfferARetry() {
        HomeActivity a = home();
        AtomicBoolean broken = new AtomicBoolean(true);
        PlannerApi real = api;
        Api.setForTest((PlannerApi) Proxy.newProxyInstance(PlannerApi.class.getClassLoader(),
            new Class<?>[]{PlannerApi.class}, (proxy, method, args) -> {
                if (method.getName().equals("history") && broken.get()) {
                    throw new IllegalStateException("disk");
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }));
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "记录历史");
        assertTrue(Shots.has(a, HistoryPage.LOAD_FAILED));
        broken.set(false);
        Shots.click(a, "重试");
        assertFalse(Shots.has(a, HistoryPage.LOAD_FAILED));
        assertTrue(Shots.has(a, "在宿舍房间学习 · 学力 +2"));
    }

    @Test
    public void aRejectedTowerCorrectionKeepsTheSheetAndWhatWasTyped() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "记录历史");
        Shots.click(a, "修改这条记录：10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元");
        TowerPanel panel = findPanel(a.sheets());
        panel.setAmountForTest(19_000);
        assertFalse("no reassurance caption", Shots.allText(a.sheets()).contains("不会多算"));
        api.nextReceipt = FakePlannerApi.stale();
        Shots.click(a, "保存修改");
        assertTrue("the sheet stays", a.sheets().isShowing());
        assertEquals(Messages.STALE, a.undoBar().messageText().toString());
        assertEquals(96, findPanel(a.sheets()).floorStepper().getValue());
        Shots.click(a, "保存修改");
        assertFalse(a.sheets().isShowing());
        assertEquals("已修改：探索塔尔塔罗斯 · 96F", a.undoBar().messageText().toString());
    }

    @Test
    public void aTowerCorrectionFromThePastDaySheetReturnsToThatDay() {
        int towerSlot = Fixtures.SLOT_NOW - 3;
        HomeActivity a = home();
        a.openDetail(towerSlot);
        Shots.idle();
        Shots.click(a, "修改这条记录：10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元");
        assertNotNull(findPanel(a.sheets()));
        Shots.click(a, "保存修改");
        assertEquals("correctTower", api.last().method);
        assertTrue("the day is back", a.sheets().isShowing());
        assertTrue(Shots.has(a, "探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元"));
        assertTrue(findPanel(a.sheets()) == null);
    }

    // ------------------------------------------------------------------ 日程 empty state

    @Test
    public void theScheduleBeforeSetupOffersSetup() {
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        HomeActivity a = homeOn("schedule");
        assertTrue(Shots.has(a, SchedulePage.NO_PROGRESS));
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("先在「今天」"));
        Shots.shot(a, "home_schedule_empty");
        Shots.click(a, "开始设置");
        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(StateActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void aDayTheStoryTakesWholeIsOneLine() {
        int day = Fixtures.day(11, 4);
        Rules.Day info = api.rules.day(day);
        assertTrue(info.dayLock != null && info.dayLock.equals(info.eveLock));
        HomeActivity a = homeOn("schedule");
        android.widget.ListView list = findList(a.getWindow().getDecorView());
        for (int i = 0; i < list.getCount(); i++) {
            SchedulePage.Item item = (SchedulePage.Item) list.getItemAtPosition(i);
            if (item.type == 2 && item.day == day) {
                list.setSelectionFromTop(i, 0);
                break;
            }
        }
        Shots.capture(a, false);
        View row = list.getChildAt(0);
        assertEquals(1, Shots.findAll(row, "全天").size());
        String reason = api.rules.lockReasonsZh.get(info.dayLock);
        assertEquals("one line for the two slots", 1, Shots.findAll(row, reason).size());
        Shots.shot(a, "home_schedule_story_day");
    }

    // ------------------------------------------------------------------ picker and corrections

    @Test
    public void thePickerTurnsOneMonthAtATimeAndSaysWhichDayWasLastRecorded() {
        HomeActivity a = home();
        CalendarPicker picker = new CalendarPicker(a, api.rules, Fixtures.OCT_22, Fixtures.OCT_22 - 1);
        Shots.render(picker);
        assertNotNull(Shots.find(picker, "10月21日，上次记录"));
        View locked = Shots.find(picker, "10月21日，上次记录");
        assertEquals(Ui.dp(a, Ui.TOUCH), locked.getHeight());
        assertNotNull(Shots.find(picker, "10月22日，已选"));
        Shots.clickTarget(Shots.find(picker, "下个月")).performClick();
        Shots.render(picker);
        View story = startingWith(picker, "11月4日，");
        assertTrue(String.valueOf(story.getContentDescription()), story.getContentDescription().toString().endsWith("，剧情占用"));
        assertFalse("a story day is not picked", story.isClickable());
        assertFalse("no strike-through", hasStrike(story));
        Shots.save(Shots.render(picker), "calendar_picker_november");
        int[] picked = {-1};
        picker.setListener(d -> picked[0] = d);
        Shots.clickTarget(Shots.find(picker, "11月3日，满月")).performClick();
        assertEquals(Fixtures.day(11, 3), picked[0]);
    }

    /** After 1/31 the progress points at 2/1: the picker opens on 1月, one tap from 12月. */
    @Test
    public void aFinishedCalendarOpensThePickerOnItsLastMonth() {
        HomeActivity a = home();
        CalendarPicker picker = new CalendarPicker(a, api.rules, api.rules.lastDay + 1, api.rules.lastDay);
        Shots.render(picker);
        Shots.find(picker, "1月");
        assertTrue(startingWith(picker, "1月31日，").getContentDescription().toString().endsWith("，上次记录"));
        View back = Shots.find(picker, "上个月");
        assertTrue(back.isEnabled());
        assertFalse("nothing after 1月", Shots.find(picker, "下个月").isEnabled());
        Shots.clickTarget(back).performClick();
        Shots.render(picker);
        Shots.find(picker, "12月");
    }

    /** Every Sunday gets the red plate, a vacation or story one too (8/2 is a vacation Sunday). */
    @Test
    public void everySundayIsARestDay() {
        Rules.Day sunday = api.rules.day(Fixtures.day(8, 2));
        sunday.type = Rules.DayType.VACATION;
        assertEquals(7, sunday.wd);
        assertTrue(DatePlate.restDay(sunday));
        Rules.Day saturday = api.rules.day(Fixtures.day(8, 1));
        assertEquals(6, saturday.wd);
        assertFalse(DatePlate.restDay(saturday));
        Rules.Day holiday = api.rules.day(Fixtures.day(10, 12));
        holiday.type = Rules.DayType.HOLIDAY;
        assertTrue(DatePlate.restDay(holiday));
    }

    @Test
    public void aCorrectedTowerSkipIsCalledTheTowerAndAnEpisodeHasItsNumber() {
        SimState before = api.snap.observed.copy();
        before.slot = Fixtures.SLOT_NOW - 3;
        PlannerApi.OutcomeCorrection tower = new PlannerApi.OutcomeCorrection();
        tower.slot = before.slot;
        tower.actionKey = "tower:3";
        tower.beforeState = before;
        tower.outcome = Outcomes.notMet();
        Plan.Step step = HistoricalOutcome.step(api.rules, api.snap.profile, tower);
        assertEquals("探索塔尔塔罗斯", step.titleZh);
        assertEquals(api.rules.blocks[3].zh, step.subtitleZh);

        if (api.rules.episodes.length > 0) {
            SimState watched = before.copy();
            if (watched.episodes.length == 0) watched.episodes = new int[api.rules.episodes.length];
            watched.episodes[0] = 1;
            PlannerApi.OutcomeCorrection episode = new PlannerApi.OutcomeCorrection();
            episode.slot = before.slot;
            episode.actionKey = "episode:" + api.rules.episodes[0].id;
            episode.beforeState = watched;
            episode.outcome = Outcomes.notMet();
            assertEquals("羁绊剧情 · " + api.rules.episodes[0].personZh + "（第 2 话）",
                HistoricalOutcome.step(api.rules, api.snap.profile, episode).titleZh);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static View startingWith(View v, String prefix) {
        if (v.getContentDescription() != null && v.getContentDescription().toString().startsWith(prefix)) return v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View found = startingWith(((ViewGroup) v).getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasStrike(View v) {
        if (v instanceof TextView && (((TextView) v).getPaintFlags() & Paint.STRIKE_THRU_TEXT_FLAG) != 0) return true;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                if (hasStrike(((ViewGroup) v).getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static TowerPanel findPanel(View v) {
        if (v instanceof TowerPanel) return (TowerPanel) v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                TowerPanel p = findPanel(((ViewGroup) v).getChildAt(i));
                if (p != null) return p;
            }
        }
        return null;
    }

    private static android.widget.ListView findList(View v) {
        if (v instanceof android.widget.ListView && v.isShown()) return (android.widget.ListView) v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                android.widget.ListView l = findList(((ViewGroup) v).getChildAt(i));
                if (l != null) return l;
            }
        }
        return null;
    }

    private HomeActivity homeOn(String tab) {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, tab);
        return home(intent);
    }
}
