package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/** The state wizard: renders of every page (onboarding, 同步现状, 修改进度), drafts, saving and CAS. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StateWizardTest extends UiTestBase {

    private void firstRun() {
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
    }

    @Test
    public void onboardingAsksTheGameFirstAndTheGoalsLast() {
        firstRun();
        StateActivity a = stateController("onboarding").get();
        // 4/18: nothing to prepare, no 羁绊剧情 yet, the tower not open: seven pages.
        assertEquals(StateSteps.Page.DATE, a.currentPage());
        assertEquals("1/7", a.progressText());
        Shots.shot(a, "state_onboarding_date");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.CASH, a.currentPage());
        assertTrue(a.draft().cashUnknown());
        Shots.click(a, "8");
        Shots.click(a, "2");
        Shots.click(a, "0");
        Shots.click(a, "0");
        Shots.shot(a, "state_onboarding_cash");
        assertEquals(8_200, a.draft().cash());
        assertFalse(a.draft().cashUnknown());
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.STATS, a.currentPage());
        Shots.shot(a, "state_onboarding_stats");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.LINKS, a.currentPage());
        Shots.shot(a, "state_onboarding_links");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.GOALS, a.currentPage());
        assertFalse("story links are never goals", Shots.has(a, "愚者"));
        assertFalse("清空 only once something is chosen", Shots.has(a, "清空"));
        Shots.click(a, "皇帝");
        Shots.click(a, "皇帝");
        Shots.click(a, "恋爱");
        assertTrue(Shots.has(a, "清空"));
        Shots.shot(a, "state_onboarding_goals");
        assertEquals(Profile.Tier.MUST, a.draft().profile.tier[4]);
        assertEquals(Profile.Tier.WANT, a.draft().profile.tier[6]);
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.PERSONA, a.currentPage());
        Shots.shot(a, "state_onboarding_persona");
        Shots.click(a, "不带");
        assertEquals(StateSteps.Page.CONFIRM, a.currentPage());
        assertEquals("7/7", a.progressText());
        assertTrue(Shots.has(a, "准备好了？"));
        Shots.shot(a, "state_onboarding_confirm");
        Shots.click(a, "开始安排");
        FakePlannerApi.Call call = api.last();
        assertEquals("saveCheckpoint", call.method);
        assertNotNull(call.profile);
        assertEquals(Profile.Tier.MUST, call.profile.tier[4]);
        assertFalse(call.profile.personaBonus);
        assertEquals(8_200, call.state.cash);
        assertTrue(call.state.cashExact);
        assertEquals(StateDraft.firstFreeSlot(api.rules), call.state.slot);
        assertTrue(a.isFinishing());
        UndoCenter.Recorded saved = UndoCenter.take();
        assertNotNull("今天 says it once", saved);
        assertEquals(StateActivity.SAVED_SUMMARY, saved.summaryZh);
        assertEquals("no 撤销 for a checkpoint", -1, saved.eventSeq);
    }

    @Test
    public void onboardingLaterInTheYearAsksWhatTheDateOpened() {
        firstRun();
        StateActivity a = stateController("onboarding").get();
        CalendarPicker picker = findPicker(a.getWindow().getDecorView());
        picker.pick(Fixtures.day(10, 22));
        Shots.idle();
        List<StateSteps.Page> seen = walkToConfirm(a);
        assertEquals(Arrays.asList(StateSteps.Page.DATE, StateSteps.Page.CASH, StateSteps.Page.STATS,
            StateSteps.Page.LINKS, StateSteps.Page.UNLOCK, StateSteps.Page.EPISODES, StateSteps.Page.TOWER,
            StateSteps.Page.GOALS, StateSteps.Page.PERSONA), seen);
        assertEquals("10/10", a.progressText());
        // Every part of the summary opens its page and comes back.
        Shots.click(a, "人格面具，不带，点按修改");
        assertEquals(StateSteps.Page.PERSONA, a.currentPage());
        assertEquals(StateSteps.Page.CONFIRM, a.returnPage());
        Shots.click(a, "都带");
        assertEquals(StateSteps.Page.CONFIRM, a.currentPage());
        assertEquals(Profile.PersonaMode.ALL, a.draft().profile.personaMode);
        Shots.shot(a, "state_onboarding_confirm_later");
    }

    @Test
    public void rankPadAutoAdvancesAndHasNoUnknownKeyOnTheFirstRun() {
        firstRun();
        StateActivity a = stateController("onboarding").get();
        jumpTo(a, StateSteps.Page.LINKS);
        Shots.click(a, "魔术师，未解锁，点按修改");
        Shots.shot(a, "state_rankpad");
        View unknown = keyTagged(ShadowDialog.getLatestDialog().getWindow().getDecorView(), StateDraft.UNKNOWN);
        assertNotNull(unknown);
        assertEquals("「?」 would be saved as 未解锁 anyway", View.INVISIBLE, unknown.getVisibility());
        pickKey(3);
        pickKey(10);
        assertEquals(3, a.draft().rank(1));
        assertEquals(10, a.draft().rank(2));
        ShadowDialog.getLatestDialog().dismiss();
        Shots.idle();
        assertTrue(Shots.has(a, "RANK 3"));
        assertFalse("no bulk 「未解锁」 chip", Shots.has(a, "不确定的都设为未解锁"));
    }

    @Test
    public void rankPadOffersNotRememberedWhenARecordExists() {
        api.snap.observed.pts[4] = 9;
        StateActivity a = stateController("edit").get();
        openFromHub(a, "社群", StateSteps.Page.LINKS);
        Shots.click(a, "皇帝，RANK 6，点按修改");
        View unknown = keyTagged(ShadowDialog.getLatestDialog().getWindow().getDecorView(), StateDraft.UNKNOWN);
        assertNotNull(unknown);
        assertTrue(Zh.plain(((android.widget.TextView) unknown).getText()).contains(StateDraft.UNSURE_RANK));
        Shots.shot(a, "state_rankpad_sync");
        pickKey(StateDraft.UNKNOWN);
        ShadowDialog.getLatestDialog().dismiss();
        Shots.idle();
        assertEquals(StateDraft.UNKNOWN, a.draft().rank(4));
        assertTrue(Shots.has(a, "皇帝，不记得，点按修改"));
        SimState built = a.draft().build();
        assertEquals("不记得 keeps the record", 6, built.rank[4]);
        assertEquals(9, built.pts[4]);
    }

    @Test
    public void syncAnsweredYesGoesFromTheDateToTheReview() {
        api.snap.observed.pts[1] = 9;
        StateActivity a = stateController("sync").get();
        assertEquals(StateSteps.Page.SYNC_INTRO, a.currentPage());
        assertEquals("the opening question chooses the way: not a step", "", a.progressText());
        assertTrue(Shots.has(a, StateSteps.syncQuestion(a.draft())));
        assertEquals("上次记到 10月21日 晚上，之后照着安排玩了吗？", StateSteps.syncQuestion(a.draft()));
        Shots.shot(a, "state_sync_intro");
        Shots.click(a, StateSteps.SYNC_YES);
        assertEquals(StateSteps.Page.DATE, a.currentPage());
        assertEquals("1/2", a.progressText());
        CalendarPicker picker = findPicker(a.getWindow().getDecorView());
        picker.pick(Fixtures.day(10, 25));
        Shots.idle();
        Shots.shot(a, "state_sync_date");
        // 10/22–10/24 as planned: emperor 7, sun 8 (forecast), cash +3,000 − 0.
        StateDraft d = a.draft();
        assertEquals(7, d.rank(4));
        assertTrue(d.isForecastRank(4));
        assertEquals(21_500, d.cash());
        Shots.click(a, "下一步");
        assertEquals("the forecast fills the rest", StateSteps.Page.CONFIRM, a.currentPage());
        assertEquals("2/2", a.progressText());
        String text = Shots.allText(a.getWindow().getDecorView());
        assertTrue(text, text.contains("更新这 " + d.changes().size() + " 项？"));
        assertTrue("forecast rows are tagged", text.contains("预计"));
        assertFalse(text.contains("保存就算确认"));
        Shots.shot(a, "state_sync_confirm");
        // A row opens its page alone; 完成 comes back to the review.
        Shots.click(a, "皇帝：RANK 6 → RANK 7，预计，点按修改");
        assertEquals(StateSteps.Page.LINKS, a.currentPage());
        assertEquals(StateSteps.Page.CONFIRM, a.returnPage());
        assertEquals("", a.progressText());
        Shots.click(a, "完成");
        assertEquals(StateSteps.Page.CONFIRM, a.currentPage());
        // 「还有别的变化？」 opens any other page.
        Shots.click(a, "塔尔塔罗斯");
        assertEquals(StateSteps.Page.TOWER, a.currentPage());
        a.onBackPressed();
        Shots.idle();
        assertEquals(StateSteps.Page.CONFIRM, a.currentPage());
        Shots.click(a, "更新安排");
        FakePlannerApi.Call call = api.last();
        assertEquals("saveCheckpoint", call.method);
        assertNull(call.profile);
        assertEquals(Rules.slot(Fixtures.day(10, 25), false), call.state.slot);
        assertEquals(7, call.state.rank[4]);
        assertEquals(0, call.state.pts[4]);
        // An untouched link keeps its hidden estimate exactly.
        assertEquals(api.snap.observed.pts[1], call.state.pts[1]);
        assertTrue(a.isFinishing());
    }

    @Test
    public void cashPageTagsTheForecastAndTakesNotRemembered() {
        StateActivity a = stateController("sync").get();
        Shots.click(a, StateSteps.SYNC_YES);
        findPicker(a.getWindow().getDecorView()).pick(Fixtures.day(10, 25));
        Shots.idle();
        Shots.click(a, "下一步");
        Shots.click(a, "现金");
        assertEquals(StateSteps.Page.CASH, a.currentPage());
        assertTrue("one 预计 tag on the forecast amount", Shots.has(a, "预计"));
        assertTrue(Shots.has(a, "21,500 日元"));
        Shots.shot(a, "state_sync_cash_forecast");
        // The prefilled amount is selected: the first digit replaces it.
        Shots.click(a, "9");
        assertEquals(9, a.draft().cash());
        assertFalse("typed: no longer a forecast", Shots.has(a, "预计"));
        Shots.click(a, CashPad.UNKNOWN_LABEL);
        assertTrue(a.draft().cashUnknown());
        assertTrue(Shots.has(a, "现金" + CashPad.UNKNOWN_LABEL));
        Shots.click(a, "完成");
        assertEquals(StateSteps.Page.CONFIRM, a.currentPage());
        assertTrue(Shots.has(a, CashPad.UNKNOWN_LABEL));
    }

    @Test
    public void goalsClearAllAtOnce() {
        firstRun();
        StateActivity a = stateController("onboarding").get();
        jumpTo(a, StateSteps.Page.GOALS);
        Shots.click(a, "皇帝");
        Shots.click(a, "恋爱");
        Shots.click(a, "清空");
        assertTrue(LinkStatus.goalOrder(a.draft().profile).isEmpty());
        assertFalse(Shots.has(a, "清空"));
        assertTrue("no goals: every link is raised", a.draft().profile.otherLinks);
    }

    @Test
    public void syncPartlyWalksEveryPageWithForecastHints() {
        StateActivity a = stateController("sync").get();
        Shots.click(a, StateSteps.SYNC_PARTLY);
        CalendarPicker picker = findPicker(a.getWindow().getDecorView());
        picker.pick(Fixtures.day(10, 25));
        Shots.idle();
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.CASH, a.currentPage());
        Shots.shot(a, "state_sync_cash");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.STATS, a.currentPage());
        Shots.shot(a, "state_sync_stats");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.LINKS, a.currentPage());
        assertTrue("一部分 shows the forecast beside the record", Shots.has(a, "预计 RANK 7"));
        Shots.shot(a, "state_sync_links");
        Shots.click(a, "下一步");
        if (a.currentPage() == StateSteps.Page.UNLOCK) {
            Shots.shot(a, "state_sync_unlock");
            Shots.click(a, "下一步");
        }
        assertEquals(StateSteps.Page.EPISODES, a.currentPage());
        Shots.shot(a, "state_sync_episodes");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.TOWER, a.currentPage());
        Shots.shot(a, "state_sync_tower");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.CONFIRM, a.currentPage());
        assertFalse("only after 基本照着玩了", Shots.has(a, "还有别的变化？"));
    }

    @Test
    public void editHubFixesOneValueInAFewTaps() {
        StateActivity a = stateController("edit").get();
        assertEquals(StateSteps.Page.HUB, a.currentPage());
        assertTrue(Shots.has(a, "修改哪一项？"));
        assertEquals("", a.progressText());
        assertFalse("nothing to save yet", Shots.has(a, "更新安排"));
        Shots.shot(a, "state_edit_hub");
        openFromHub(a, "现金", StateSteps.Page.CASH);
        Shots.click(a, "3");
        Shots.click(a, "1");
        Shots.click(a, "000");
        assertEquals(31_000, a.draft().cash());
        Shots.click(a, "完成");
        assertEquals(StateSteps.Page.HUB, a.currentPage());
        assertTrue(Shots.has(a, "现金，31,000 日元，已改，点按修改"));
        Shots.shot(a, "state_edit_hub_changed");
        Shots.click(a, "更新安排");
        FakePlannerApi.Call call = api.last();
        assertEquals("saveCheckpoint", call.method);
        assertEquals(31_000, call.state.cash);
        assertTrue(call.state.cashExact);
        assertTrue(a.isFinishing());
        assertEquals(StateActivity.SAVED_SUMMARY, UndoCenter.take().summaryZh);
    }

    @Test
    public void unlockPageListsOnlyRelevantLinks() {
        StateActivity a = stateController("edit").get();
        openFromHub(a, "解锁前的准备", StateSteps.Page.UNLOCK);
        assertTrue(Shots.has(a, "恶魔 · 田中社长"));
        assertFalse(Shots.has(a, "皇帝 · 小田桐秀利"));
        assertFalse("the last step unlocks the link: not a preparation", Shots.has(a, "与田中社长建立社群"));
        Shots.click(a, "再借给社长 10,000 日元");
        assertEquals("tapping step 2 marks steps 1 and 2", 2, a.draft().unlockStep(15));
        Shots.shot(a, "state_edit_unlock");
        Shots.click(a, "都还没做");
        assertEquals(0, a.draft().unlockStep(15));
        Shots.click(a, "第一次借钱给田中社长（5,000 日元）");
        assertEquals(1, a.draft().unlockStep(15));
    }

    @Test
    public void draftSurvivesRecreationAndProcessDeath() {
        ActivityController<StateActivity> controller = stateController("edit");
        StateActivity a = controller.get();
        openFromHub(a, "现金", StateSteps.Page.CASH);
        a.draft().setCash(31_337);
        a.changed();
        controller.recreate();
        Shots.idle();
        StateActivity again = controller.get();
        assertEquals(31_337, again.draft().cash());
        assertEquals(StateSteps.Page.CASH, again.currentPage());
        assertEquals("still a single page of 修改哪一项？", StateSteps.Page.HUB, again.returnPage());
        Shots.shot(again, "state_edit_restored");
        assertEquals(StateActivity.RESUMED, again.noticeText());

        // A brand-new activity (process death) restores from the semantic keys as well.
        StateActivity fresh = stateController("edit").get();
        assertEquals(31_337, fresh.draft().cash());
        assertEquals(StateSteps.Page.CASH, fresh.currentPage());
        Shots.click(fresh, "完成");
        assertEquals(StateSteps.Page.HUB, fresh.currentPage());
        assertNull("a message goes with the page it was about", fresh.noticeText());
    }

    @Test
    public void draftFromOlderRevisionIsKeptOnTheNewRecord() {
        StateActivity a = stateController("edit").get();
        a.draft().setCash(999);
        a.changed();
        api.snap.stateRevision++;
        StateActivity later = stateController("edit").get();
        assertEquals(999, later.draft().cash());
        assertEquals(api.snap.stateRevision, later.draft().baseRevision);
        assertEquals(StateActivity.REBASED, later.noticeText());
    }

    @Test
    public void personaPageOffersThreeChoicesAndGoesOn() {
        firstRun();
        StateActivity a = stateController("onboarding").get();
        jumpTo(a, StateSteps.Page.PERSONA);
        assertTrue(Shots.has(a, "都带"));
        assertTrue(Shots.has(a, "只给目标社群带"));
        assertTrue(Shots.has(a, "不带"));
        String text = Shots.allText(a.getWindow().getDecorView());
        assertFalse("no settings path", text.contains("规划设置"));
        assertFalse(text.contains("按不带计算"));
        Shots.click(a, "只给目标社群带");
        assertEquals(Profile.PersonaMode.GOALS, a.draft().profile.personaMode);
        assertFalse(a.draft().profile.personaBonus);
        assertEquals(StateSteps.Page.CONFIRM, a.currentPage());
    }

    @Test
    public void rescueQuestionsWriteTheRescueBits() {
        StateActivity a = stateController("edit").get();
        openFromHub(a, "塔尔塔罗斯", StateSteps.Page.TOWER);
        // 10/22: 北村文吉's window (9/12–10/3) is over, 舞子's (10/21–11/2) is running.
        assertTrue(Shots.has(a, "北村文吉救出来了吗？"));
        assertTrue(Shots.has(a, "舞子救出来了吗？"));
        assertTrue(Shots.has(a, "期限 10月3日 · 教皇"));
        assertFalse("no 不确定 answer", Shots.has(a, "北村文吉：不确定"));
        assertTrue("the window is open: 还没", Shots.find(a, "舞子：还没").isSelected());
        Shots.shot(a, "state_edit_tower_rescue");
        Shots.click(a, "北村文吉：没救出");
        Shots.click(a, "舞子：救出了");
        SimState built = a.draft().build();
        assertEquals(0, built.rescued & 1L);
        assertEquals(1L, built.rescueFailed & 1L);
        assertEquals(2L, built.rescued & 2L);
        boolean listed = false;
        for (StateDraft.Change change : a.draft().changes()) {
            listed |= change.labelZh.equals("救出舞子") && "TOWER".equals(change.page);
        }
        assertTrue(listed);
        Shots.click(a, "完成");
        assertTrue(Shots.has(a, "已改"));
    }

    @Test
    public void floorCanBeTypedAndStopsAtTheBlockOfTheDate() {
        StateActivity a = stateController("edit").get();
        a.draft().setSlot(Rules.slot(Fixtures.day(9, 15), false));
        assertEquals(68, a.draft().towerTop());
        assertEquals(68, a.draft().floor());
        a.draft().setSlot(Rules.slot(Fixtures.day(10, 22), false));
        openFromHub(a, "塔尔塔罗斯", StateSteps.Page.TOWER);
        Stepper floor = findStepper(a.getWindow().getDecorView());
        assertNotNull(floor);
        assertTrue("the floor is typed, not held for", floor.valueView().isClickable());
        floor.valueView().performClick();
        Shots.idle();
        Shots.shot(a, "state_edit_tower_floor_pad");
        Shots.clickInDialog("1");
        Shots.clickInDialog("2");
        Shots.clickInDialog("0");
        Shots.clickInDialog("确定");
        assertEquals("clamped to the top of 提齐亚", 117, a.draft().floor());
        floor = findStepper(a.getWindow().getDecorView());
        floor.step(-21);
        assertEquals(96, a.draft().floor());
    }

    @Test
    public void casConflictKeepsEditsAndAsksAgain() {
        StateActivity a = stateController("edit").get();
        a.draft().setCash(50_000);
        a.refresh();
        api.nextReceipt = FakePlannerApi.stale();
        Shots.click(a, "更新安排");
        assertFalse(a.isFinishing());
        assertEquals(StateActivity.CONFLICT, a.noticeText());
        assertEquals(50_000, a.draft().cash());
        assertEquals(StateSteps.Page.HUB, a.currentPage());
        Shots.shot(a, "state_cas_conflict");
    }

    @Test
    public void aFailedSaveGivesTheButtonItsLabelBack() {
        StateActivity a = stateController("edit").get();
        a.draft().setCash(40_000);
        a.refresh();
        api.nextReceipt = FakePlannerApi.invalid(null);
        Shots.click(a, "更新安排");
        assertFalse(a.isFinishing());
        assertEquals(Messages.GENERIC_ERROR, a.noticeText());
        assertEquals("never stuck on 正在保存…", "更新安排", a.primaryText());
        Shots.shot(a, "state_save_failed");
    }

    @Test
    public void unchangedEditJustCloses() {
        StateActivity a = stateController("edit").get();
        Shots.click(a, "逐项检查");
        assertEquals(StateSteps.Page.DATE, a.currentPage());
        jumpTo(a, StateSteps.Page.CONFIRM);
        assertTrue(Shots.has(a, "和上次一样，不用改"));
        Shots.click(a, "好的");
        assertTrue(api.callsOf("saveCheckpoint").isEmpty());
        assertTrue(a.isFinishing());
    }

    @Test
    public void backGoesToThePreviousPage() {
        StateActivity a = stateController("edit").get();
        Shots.click(a, "逐项检查");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.CASH, a.currentPage());
        a.onBackPressed();
        Shots.idle();
        assertEquals(StateSteps.Page.DATE, a.currentPage());
        a.onBackPressed();
        Shots.idle();
        assertEquals(StateSteps.Page.HUB, a.currentPage());
        openFromHub(a, "日期", StateSteps.Page.DATE);
        a.onBackPressed();
        Shots.idle();
        assertEquals("a single page goes back to where it was opened", StateSteps.Page.HUB, a.currentPage());
        a.onBackPressed();
        assertTrue(a.isFinishing());
    }

    @Test
    public void lockedDaysAreGreyedInTheCalendar() {
        StateActivity a = stateController("edit").get();
        a.draft().setSlot(Rules.slot(Fixtures.day(10, 22), true));
        openFromHub(a, "日期", StateSteps.Page.DATE);
        CalendarPicker picker = findPicker(a.getWindow().getDecorView());
        assertFalse(CalendarPicker.selectable(api.rules, Fixtures.day(11, 4)));
        assertTrue(CalendarPicker.selectable(api.rules, Fixtures.day(11, 3)));
        picker.pick(Fixtures.day(11, 3));
        Shots.idle();
        // 11/3 evening is the full-moon operation: the daytime slot is chosen instead, and the
        // evening plate says why.
        assertFalse(Rules.isEvening(a.draft().slot()));
        assertTrue(Shots.has(a, "晚上 · 满月作战"));
        assertFalse(Shots.find(a, "晚上 · 满月作战").isEnabled());
        Shots.shot(a, "state_edit_date_fullmoon");
    }

    @Test
    public void wizardCopyHasNoInstructionsOrHedges() {
        StateActivity a = stateController("sync").get();
        Shots.click(a, StateSteps.SYNC_NO);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 14 && a.currentPage() != StateSteps.Page.CONFIRM; i++) {
            texts.add(Shots.allText(a.getWindow().getDecorView()));
            Shots.click(a, "下一步");
        }
        texts.add(Shots.allText(a.getWindow().getDecorView()));
        for (String text : texts) {
            for (String banned : new String[]{"请", "点一格", "自动跳到", "灰色划掉", "按安排推算", "大约", "暂时",
                "可在「", "保存就算确认", "先按", "不确定"}) {
                assertFalse(banned + " in " + text, text.contains(banned));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<StateSteps.Page> walkToConfirm(StateActivity a) {
        List<StateSteps.Page> seen = new ArrayList<>();
        for (int i = 0; i < 14 && a.currentPage() != StateSteps.Page.CONFIRM; i++) {
            seen.add(a.currentPage());
            if (a.currentPage() == StateSteps.Page.PERSONA) {
                Shots.click(a, "不带");
            } else {
                Shots.click(a, "下一步");
            }
        }
        return seen;
    }

    static void jumpTo(StateActivity a, StateSteps.Page page) {
        for (int i = 0; i < 14 && a.currentPage() != page; i++) {
            Shots.click(a, "下一步");
        }
        assertEquals(page, a.currentPage());
    }

    /** Taps a row of 修改哪一项？ and checks that its page opened on its own. */
    static void openFromHub(StateActivity a, String row, StateSteps.Page page) {
        assertEquals(StateSteps.Page.HUB, a.currentPage());
        Shots.click(a, row);
        assertEquals(page, a.currentPage());
        assertEquals(StateSteps.Page.HUB, a.returnPage());
    }

    /** Taps the RANK pad key of {@code value} (found by its tag, whatever its label). */
    static void pickKey(int value) {
        View key = keyTagged(ShadowDialog.getLatestDialog().getWindow().getDecorView(), value);
        assertNotNull("key " + value, key);
        assertTrue(key.performClick());
        Shots.idle();
    }

    static View keyTagged(View v, int value) {
        if (v instanceof android.widget.TextView && Integer.valueOf(value).equals(v.getTag())) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = keyTagged(g.getChildAt(i), value);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Every stepper under {@code v}, in layout order. */
    static List<Stepper> steppers(View v) {
        List<Stepper> out = new ArrayList<>();
        collectSteppers(v, out);
        return out;
    }

    private static void collectSteppers(View v, List<Stepper> out) {
        if (v instanceof Stepper) {
            out.add((Stepper) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectSteppers(g.getChildAt(i), out);
            }
        }
    }

    static Stepper findStepper(View v) {
        if (v instanceof Stepper) {
            return (Stepper) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Stepper s = findStepper(g.getChildAt(i));
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    static CalendarPicker findPicker(View v) {
        if (v instanceof CalendarPicker) {
            return (CalendarPicker) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                CalendarPicker p = findPicker(g.getChildAt(i));
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }
}
