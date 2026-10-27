package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.service.PlannerApi;

/** Behaviour of the main screen: back order, one-tap records with undo, goals, routing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeBehaviorTest extends UiTestBase {

    @Test
    public void backClosesSheetThenPageThenTabThenLeaves() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "规划说明");
        a.openDetail(Fixtures.SLOT_NOW);
        assertTrue(a.sheets().isShowing());
        assertEquals(1, a.pageDepth());

        a.onBackPressed();
        assertFalse(a.sheets().isShowing());
        assertEquals(1, a.pageDepth());
        a.onBackPressed();
        assertEquals(0, a.pageDepth());
        assertEquals(HomeActivity.TAB_ME, a.currentTab());
        a.onBackPressed();
        assertEquals(HomeActivity.TAB_TODAY, a.currentTab());
        assertFalse(a.isFinishing());
        a.onBackPressed();
        assertTrue(a.isFinishing());
    }

    @Test
    public void doneOnSocialStepOpensTheResultSheet() {
        HomeActivity a = home();
        Shots.click(a, "完成 ✓");
        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(ResultActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(Fixtures.SLOT_NOW, next.getIntExtra(Intents.EXTRA_SLOT, -1));
        assertEquals("meet:emperor", next.getStringExtra(Intents.EXTRA_ACTION));
        assertEquals(Intents.MODE_RESULT, next.getStringExtra(Intents.EXTRA_MODE));
        assertEquals(Fixtures.PLAN_ID, next.getStringExtra(Intents.EXTRA_PLAN_ID));
        assertEquals(Fixtures.STATE_REV, next.getLongExtra(Intents.EXTRA_STATE_REV, -1));
        assertTrue(next.getBooleanExtra(HomeActivity.EXTRA_FROM_APP, false));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void cannotOpensReasons() {
        HomeActivity a = home();
        Shots.click(a, "做不了…");
        Intent next = shadowOf(a).getNextStartedActivity();
        assertEquals(Intents.MODE_CANNOT, next.getStringExtra(Intents.EXTRA_MODE));
    }

    @Test
    public void doneOnTrainingRecordsAtOnceAndCanBeUndone() {
        api.snap.observed.slot = Fixtures.SLOT_NOW + 1;
        HomeActivity a = home();
        assertTrue(Shots.has(a, "在宿舍房间学习"));
        Shots.click(a, "完成 ✓");
        FakePlannerApi.Call call = api.last();
        assertEquals("recordOutcome", call.method);
        assertEquals("act:study_room", call.actionKey);
        assertEquals(Outcome.Code.AS_PREDICTED, call.outcome.code);
        assertTrue(a.undoBar().isShowing());
        assertEquals("已记录：在宿舍房间学习", a.undoBar().messageText().toString());
        Shots.shot(a, "home_undo_bar");
        a.undoBar().performAction();
        Shots.idle();
        FakePlannerApi.Call undo = api.last();
        assertEquals("voidEvent", undo.method);
        assertNotEquals(call.commandId, undo.commandId);
        assertEquals(101, undo.targetSeq);
        assertEquals(Fixtures.STATE_REV + 1, undo.expectedRevision);
    }

    @Test
    public void undoOfferedForRecordMadeOnTheSheet() {
        UndoCenter.post(321, "皇帝 RANK 7");
        HomeActivity a = home();
        assertTrue(a.undoBar().isShowing());
        assertEquals("已记录：皇帝 RANK 7", a.undoBar().messageText().toString());
    }

    @Test
    public void tierChipCyclesAndSavesProfile() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        HomeActivity a = home(intent);
        Shots.click(a, "魔术师 目标：未选，点按切换");
        // Shown at once, saved shortly after the last tap.
        assertTrue(Shots.has(a, "魔术师 目标：想要，点按切换"));
        assertTrue(api.calls.isEmpty());
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(LinksPage.TIER_SAVE_DELAY_MS));
        FakePlannerApi.Call save = api.last();
        assertEquals("saveProfile", save.method);
        assertEquals(Fixtures.PROFILE_REV, save.expectedRevision);
        assertEquals(Profile.Tier.WANT, save.profile.tier[1]);
        assertEquals(Profile.Tier.WANT, a.data().profile().tier[1]);
        // 想要 → 必须 → 未选 in quick taps: one save, never with the 必须 in between, and with the
        // new revision (no false conflict).
        Shots.click(a, "魔术师 目标：想要，点按切换");
        Shots.click(a, "魔术师 目标：必须，点按切换");
        assertEquals(1, api.callsOf("saveProfile").size());
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(LinksPage.TIER_SAVE_DELAY_MS));
        assertEquals(2, api.callsOf("saveProfile").size());
        assertEquals(Fixtures.PROFILE_REV + 1, api.last().expectedRevision);
        assertEquals(Profile.Tier.NONE, api.last().profile.tier[1]);
    }

    @Test
    public void tierTapsAreSavedWhenLeavingTheTab() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        HomeActivity a = home(intent);
        Shots.click(a, "魔术师 目标：未选，点按切换");
        assertTrue(api.calls.isEmpty());
        a.selectTab(HomeActivity.TAB_TODAY);
        Shots.idle();
        assertEquals("saveProfile", api.last().method);
        assertEquals(Profile.Tier.WANT, api.last().profile.tier[1]);
        // Nothing left to save when the timer would have run.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(LinksPage.TIER_SAVE_DELAY_MS));
        assertEquals(1, api.callsOf("saveProfile").size());
    }

    @Test
    public void linkDetailSheetSavesTierAndRouteAtOnce() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        HomeActivity a = home(intent);
        Shots.click(a, "女教皇 · 山岸风花，RANK 5 → 9，时间先给了皇帝");
        assertTrue(a.sheets().isShowing());
        assertTrue(Shots.has(a, "时间先给了皇帝。想先冲它，就设成必须"));
        Shots.click(a, "女教皇 目标：必须");
        assertEquals(Profile.Tier.MUST, api.last().profile.tier[2]);
        Shots.click(a, "女教皇 路线：恋人");
        assertEquals(Profile.Route.ROMANCE, api.last().profile.route[2]);
        assertEquals(Profile.Tier.MUST, api.last().profile.tier[2]);
        assertTrue("every control saves at once; no save button", a.sheets().isShowing());
    }

    @Test
    public void goalOrderNotConfigurable() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        HomeActivity a = home(intent);
        assertFalse(Shots.has(a, "调整顺序"));
    }

    @Test
    public void listenerUpdatesShowChangeSummary() {
        HomeActivity a = home();
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed, api.snap.observed.slot, "plan-2");
        api.snap.plan.steps.set(1, Fixtures.social(api.rules, Fixtures.SLOT_NOW + 1, "hermit", 3, 4, true, false));
        api.fireChanged();
        Shots.idle();
        // Said once in the message bar, not as a line that stays on 今天.
        assertEquals("已重新安排：这周 1 处有变", a.undoBar().messageText().toString());
        Shots.shot(a, "home_today_change_summary");
    }

    @Test
    public void openSlotExtraOpensTheDetailSheet() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_OPEN_SLOT, Fixtures.SLOT_NOW + 1);
        HomeActivity a = home(intent);
        assertTrue(a.sheets().isShowing());
        assertTrue(Shots.has(a, DayDetailSheet.REACHED));
    }

    @Test
    public void historyUndoAsksFirst() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "记录历史");
        Shots.click(a, "撤销这条记录：10月21日 晚上 · 在宿舍房间学习 · 学力 +2");
        assertTrue(api.calls.isEmpty());
        Shots.shot(a, "home_history_confirm");
        Shots.clickInDialog("撤销");
        assertEquals("voidEvent", api.last().method);
        assertEquals(98, api.last().targetSeq);
    }

    @Test
    public void towerCorrectionNeverAddsAVisit() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "记录历史");
        Shots.click(a, "修改这条记录：10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元");
        assertTrue(a.sheets().isShowing());
        assertFalse(Shots.has(a, "没去成"));
        TowerPanel panel = findPanel(a.sheets());
        assertEquals(96, panel.floorStepper().getValue());
        panel.setAmountForTest(19_000);
        Shots.shot(a, "home_history_correct_tower");
        Shots.click(a, "保存修改");
        FakePlannerApi.Call call = api.last();
        assertEquals("correctTower", call.method);
        assertEquals(97, call.targetSeq);
        assertEquals(19_000, call.tower.amount);
        assertEquals(96, call.tower.floor);
        assertTrue(api.callsOf("recordTower").isEmpty());
    }

    @Test
    public void settingsToggleSavesProfile() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "规划设置");
        Shots.click(a, "都带");
        assertEquals(Profile.PersonaMode.ALL, api.last().profile.personaMode);
        assertTrue(api.last().profile.personaBonus);
        assertFalse(Shots.has(a, SettingsPage.PERSONA_GOALS_NOTE));
        Shots.click(a, "目标才带");
        assertEquals(Profile.PersonaMode.GOALS, api.last().profile.effectivePersonaMode());
        assertTrue("the reminder is said only for 目标才带", Shots.has(a, SettingsPage.PERSONA_GOALS_NOTE));
        Shots.click(a, "不带");
        assertEquals(Profile.PersonaMode.NONE, api.last().profile.effectivePersonaMode());
        assertFalse(api.last().profile.personaBonus);
        assertFalse(Shots.has(a, SettingsPage.PERSONA_GOALS_NOTE));
        // The switch says what the note costs; its state comes from the switch role, not the label.
        ToggleRow dating = (ToggleRow) Shots.clickTarget(Shots.find(a, "安排结缘网站的字条"));
        assertEquals("安排结缘网站的字条，11月11日起 · 每张 3,000 日元", dating.getContentDescription().toString());
        assertTrue(dating.isChecked());
        Shots.click(a, "安排结缘网站的字条");
        assertFalse(api.last().profile.datingNotes);
        assertFalse(dating.isChecked());
    }

    @Test
    public void episodeTrackToggleSavesProfile() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        HomeActivity a = home(intent);
        assertTrue(Shots.has(a, "羁绊剧情"));
        Shots.click(a, "羁绊剧情");
        Shots.click(a, "真田明彦 · 共 3 话，已关闭");
        // Opting in mid-game first asks how many episodes were already watched.
        assertTrue(a.sheets().isShowing());
        assertTrue(api.calls.isEmpty());
        Shots.click(a, "真田明彦：还没看");
        Profile saved = api.last().profile;
        assertEquals("saveProfile", api.last().method);
        assertTrue(saved.wantsEpisodes("akihiko"));
        assertTrue(saved.wantsEpisodes("junpei"));
        Shots.click(a, "伊织顺平 · 共 4 话，已开启");
        assertEquals("saveProfile", api.last().method);
        assertFalse(api.last().profile.wantsEpisodes("junpei"));
    }

    @Test
    public void historyOffersOnlyWhatTheStoreAccepts() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "记录历史");
        android.view.View page = a.getWindow().getDecorView();
        // Tower and outcome records: both actions; journal and older rows: none.
        assertTrue(Shots.has(a, "修改这条记录：10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元"));
        assertTrue(Shots.has(a, "撤销这条记录：10月20日 放学后 · 皇帝 RANK 6"));
        assertTrue(Shots.has(a, "修改这条记录：10月20日 放学后 · 皇帝 RANK 6"));
        assertFalse(Shots.has(a, "撤销这条记录：10月18日 · 同步现状"));
        assertFalse(Shots.has(a, "撤销这条记录：10月18日 放学后 · 女教皇 RANK 5"));
        assertFalse(Shots.has(a, "修改这条记录：10月18日 放学后 · 女教皇 RANK 5"));
        // One divider plate says where the latest 同步现状 is; the rows under it need no caption.
        assertEquals(1, Shots.findAll(page, "10月18日 · 同步现状").size());
        assertFalse(Shots.allText(page).contains("同步现状之前的记录"));
        String all = Shots.allText(page);
        assertTrue(all, all.indexOf("10月18日 · 同步现状") < all.indexOf("女教皇 RANK 5"));
    }

    @Test
    public void messagesShowAboveSubPagesAndSheets() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "备份与恢复");
        a.showMessage("测试消息");
        Shots.idle();
        android.view.ViewGroup root = (android.view.ViewGroup) a.undoBar().getParent();
        assertEquals(a.undoBar(), root.getChildAt(root.getChildCount() - 1));
        Shots.click(a, "粘贴文字");
        assertTrue(a.sheets().isShowing());
        Shots.click(a, "恢复");
        assertEquals(BackupPage.EMPTY_PASTE, a.undoBar().messageText().toString());
        assertEquals(a.undoBar(), root.getChildAt(root.getChildCount() - 1));
        Shots.capture(a, false);
        a.placeUndo();
        Shots.capture(a, false);
        android.graphics.Rect bar = HomeActivity.boundsIn(a.undoBar(), root);
        android.graphics.Rect restore = HomeActivity.boundsIn(Shots.clickTarget(Shots.find(a, "恢复")), root);
        android.graphics.Rect title = HomeActivity.boundsIn(Shots.find(a, "粘贴备份"), root);
        assertFalse("bar " + bar + " covers 恢复 " + restore, android.graphics.Rect.intersects(bar, restore));
        assertFalse("bar " + bar + " covers the sheet title " + title, android.graphics.Rect.intersects(bar, title));
        Shots.shot(a, "home_message_over_sheet");
    }

    @Test
    public void undoBarNeverCoversTheHeroButtons() {
        api.snap.observed.slot = Fixtures.SLOT_NOW + 1;
        HomeActivity a = home();
        Shots.click(a, "完成 ✓");
        assertTrue(a.undoBar().isShowing());
        Shots.capture(a, false);
        a.placeUndo();
        Shots.capture(a, false);
        android.view.View root = (android.view.View) a.undoBar().getParent();
        android.graphics.Rect bar = HomeActivity.boundsIn(a.undoBar(), root);
        android.view.View buttons = (android.view.View) Shots.find(a, "完成 ✓").getParent();
        android.graphics.Rect hero = HomeActivity.boundsIn(buttons, root);
        assertFalse("undo bar " + bar + " covers hero buttons " + hero, android.graphics.Rect.intersects(bar, hero));
        Shots.shot(a, "home_undo_bar_clear_of_hero");
    }

    @Test
    public void undoBarMovesUpWhenTheHeroReachesTheBottom() {
        // The 10/22 hero is tall (reminder, side step, reason): its buttons sit where the bar would be.
        HomeActivity a = home();
        a.showUndo(7, "皇帝 RANK 7", true);
        Shots.capture(a, false);
        a.placeUndo();
        Shots.capture(a, false);
        android.view.View root = (android.view.View) a.undoBar().getParent();
        android.graphics.Rect bar = HomeActivity.boundsIn(a.undoBar(), root);
        android.graphics.Rect hero = HomeActivity.boundsIn((android.view.View) Shots.find(a, "完成 ✓").getParent(), root);
        assertFalse("undo bar " + bar + " covers hero buttons " + hero, android.graphics.Rect.intersects(bar, hero));
        Shots.shot(a, "home_undo_bar_top");
    }

    @Test
    public void backupShareUsesActionSend() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "备份与恢复");
        Shots.click(a, "分享备份");
        Intent chooser = shadowOf(a).getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals(api.backupJson, send.getStringExtra(Intent.EXTRA_TEXT));
    }

    // ------------------------------------------------------------------ shell (3.6 review fixes)

    private static Intent homeIntent() {
        return new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
    }

    private static void barTimesOut() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(UndoBar.DURATION_MILLIS));
    }

    /** The widget's 今天 / day-card taps land on what they name, not under a sub-page left open. */
    @Test
    public void aWidgetTapClosesWhatWasLeftOpen() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "规划说明");
        assertEquals(1, a.pageDepth());
        Intent today = homeIntent();
        today.putExtra(Intents.EXTRA_TAB, "today");
        a.onNewIntent(today);
        Shots.idle();
        assertEquals(0, a.pageDepth());
        assertEquals(HomeActivity.TAB_TODAY, a.currentTab());

        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "记录历史");
        assertEquals(1, a.pageDepth());
        Intent card = homeIntent();
        card.putExtra(Intents.EXTRA_TAB, "today");
        card.putExtra(Intents.EXTRA_OPEN_SLOT, Fixtures.SLOT_NOW + 1);
        a.onNewIntent(card);
        Shots.idle();
        assertTrue(a.sheets().isShowing());
        assertEquals(0, a.pageDepth());
        a.onBackPressed();
        assertEquals("closing the sheet shows 今天", HomeActivity.TAB_TODAY, a.currentTab());
        assertEquals(0, a.pageDepth());
    }

    /** 「看记录历史」 from the result sheet: one 记录历史, on a cold start and over a sheet left open. */
    @Test
    public void seeHistoryOpensOneHistoryPage() {
        Intent intent = homeIntent();
        intent.putExtra(Intents.EXTRA_OPEN_PAGE, Intents.PAGE_HISTORY);
        HomeActivity a = home(intent);
        assertEquals(1, a.pageDepth());
        assertTrue(Shots.has(a, "记录历史"));

        a.openDetail(Fixtures.SLOT_NOW);
        assertTrue(a.sheets().isShowing());
        Intent again = homeIntent();
        again.putExtra(Intents.EXTRA_OPEN_PAGE, Intents.PAGE_HISTORY);
        a.onNewIntent(again);
        Shots.idle();
        assertFalse("the sheet left open is closed", a.sheets().isShowing());
        assertEquals("never two of them", 1, a.pageDepth());
        assertTrue(Shots.has(a, "记录历史"));
    }

    /** A replan that lands while a record's 撤销 is up is said once that bar has had its time. */
    @Test
    public void aPlanChangeWaitsForTheRecordsUndo() {
        HomeActivity a = home();
        a.showUndo(7, "在宿舍房间学习", false);
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed, api.snap.observed.slot, "plan-2");
        api.snap.plan.steps.set(1, Fixtures.social(api.rules, Fixtures.SLOT_NOW + 1, "hermit", 3, 4, true, false));
        api.fireChanged();
        Shots.idle();
        assertEquals("已记录：在宿舍房间学习", a.undoBar().messageText().toString());
        assertTrue(a.undoBar().hasAction());
        barTimesOut();
        assertEquals("已重新安排：这周 1 处有变", a.undoBar().messageText().toString());
        // 「看看」: 日程, at the day that changed.
        assertTrue(a.undoBar().hasAction());
        a.undoBar().performAction();
        Shots.idle();
        assertEquals(HomeActivity.TAB_SCHEDULE, a.currentTab());
        barTimesOut();
        assertFalse("said once", a.undoBar().isShowing());
    }

    /** A message without 撤销 (the wizard's 「已记录：游戏进度」) gives way to what the replan changed. */
    @Test
    public void aPlanChangeReplacesAMessageWithoutUndo() {
        UndoCenter.post(-1, StateActivity.SAVED_SUMMARY);
        HomeActivity a = home();
        assertEquals("已记录：" + StateActivity.SAVED_SUMMARY, a.undoBar().messageText().toString());
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed, api.snap.observed.slot, "plan-2");
        api.snap.plan.steps.set(1, Fixtures.social(api.rules, Fixtures.SLOT_NOW + 1, "hermit", 3, 4, true, false));
        api.fireChanged();
        Shots.idle();
        assertEquals("已重新安排：这周 1 处有变", a.undoBar().messageText().toString());
    }

    /** 「RANK UP」 only for a new rank-up: not a lower rank, not a correction (which keeps 「已修改：」). */
    @Test
    public void onlyANewRankUpGetsTheBadge() {
        UndoCenter.post(321, "皇帝 RANK 5", false);
        HomeActivity a = home();
        assertEquals("已记录：皇帝 RANK 5", a.undoBar().messageText().toString());
        assertFalse(a.undoBar().showsBadge());
        a.showUndo(322, "已修改：皇帝 RANK 7", true);
        assertEquals("已修改：皇帝 RANK 7", a.undoBar().messageText().toString());
        assertFalse(a.undoBar().showsBadge());
        a.showUndo(323, "皇帝 RANK 7", true);
        assertTrue(a.undoBar().showsBadge());
    }

    /** A 撤销 that did not go through offers 「重试」 instead of a question with nothing to tap. */
    @Test
    public void aFailedUndoOffersRetry() {
        HomeActivity a = home();
        a.showUndo(7, "在宿舍房间学习", false);
        api.nextReceipt = FakePlannerApi.stale();
        a.undoBar().performAction();
        Shots.idle();
        assertEquals(Messages.ACTION_FAILED, a.undoBar().messageText().toString());
        assertTrue(a.undoBar().showsBadge());
        assertTrue(a.undoBar().hasAction());
        a.undoBar().performAction();
        Shots.idle();
        assertEquals("已撤销：在宿舍房间学习", a.undoBar().messageText().toString());
        assertEquals(2, api.callsOf("voidEvent").size());
    }

    /** A 撤销 whose write failed (the store's 「没保存上」) offers 「重试」 too. */
    @Test
    public void anUndoThatWasNotSavedOffersRetry() {
        HomeActivity a = home();
        a.showUndo(7, "在宿舍房间学习", false);
        PlannerApi.Receipt io = new PlannerApi.Receipt();
        io.errorCode = PlannerApi.Receipt.CODE_IO;
        io.errorZh = "没保存上，再试一次？";
        api.nextReceipt = io;
        a.undoBar().performAction();
        Shots.idle();
        assertEquals(Messages.ACTION_FAILED, a.undoBar().messageText().toString());
        assertTrue(a.undoBar().hasAction());
        a.undoBar().performAction();
        Shots.idle();
        assertEquals("已撤销：在宿舍房间学习", a.undoBar().messageText().toString());
        assertEquals(2, api.callsOf("voidEvent").size());
    }

    /**
     * Only a replan's news gives way to newer news: once it has had its time, a later 撤销 (the
     * 合体篮's) stays until it has had its own.
     */
    @Test
    public void aBasketUndoOutlastsAPlanChangeAfterAnEarlierOne() {
        HomeActivity a = home();
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed, api.snap.observed.slot, "plan-2");
        api.snap.plan.steps.set(1, Fixtures.social(api.rules, Fixtures.SLOT_NOW + 1, "hermit", 3, 4, true, false));
        api.fireChanged();
        Shots.idle();
        assertEquals("已重新安排：这周 1 处有变", a.undoBar().messageText().toString());
        barTimesOut();
        assertFalse(a.undoBar().isShowing());

        PersonaViews.announce(a, "合体篮清空了", "撤销", () -> { });
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed, api.snap.observed.slot, "plan-3");
        api.snap.plan.steps.set(2, Fixtures.social(api.rules, Fixtures.SLOT_NOW + 2, "hermit", 3, 4, true, false));
        api.fireChanged();
        Shots.idle();
        assertEquals("合体篮清空了", a.undoBar().messageText().toString());
        assertTrue(a.undoBar().hasAction());
        barTimesOut();
        assertTrue(a.undoBar().messageText().toString().startsWith("已重新安排："));
    }

    /** A goal in trouble on 今天 opens that link's 社群详情, where its fix is. */
    @Test
    public void aGoalInTroubleOpensItsLink() {
        api.snap.observed.rescued = 0;
        api.snap.profile.tier[5] = Profile.Tier.MUST;
        api.snap.profile.target[5] = 10;
        HomeActivity a = home();
        String line = api.rules.links[5].arcanaZh + " · 无法继续 ›";
        Shots.click(a, line);
        assertEquals(HomeActivity.TAB_LINKS, a.currentTab());
        assertTrue(a.sheets().isShowing());
    }

    /** The resource band says what a double tap does; an unknown cash amount is 「不记得」 as in the wizard. */
    @Test
    public void theResourceBandTellsTalkBackItOpensTheProgress() {
        api.snap.observed.cash = 0;
        api.snap.observed.cashExact = false;
        HomeActivity a = home();
        android.view.View cash = Shots.find(a, "现金 " + CashPad.UNKNOWN_LABEL);
        android.view.View band = (android.view.View) cash.getParent().getParent();
        android.view.accessibility.AccessibilityNodeInfo info = band.createAccessibilityNodeInfo();
        boolean labelled = false;
        for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
            labelled |= action.getId() == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                && TodayPage.EDIT_LABEL.contentEquals(action.getLabel());
        }
        assertTrue(labelled);
    }

    /** No pinning on this launcher: one short line, no 「请」, and [好了]. */
    @Test
    public void addingTheWidgetByHandIsOneShortLine() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "添加桌面小组件");
        assertTrue(a.sheets().isShowing());
        assertTrue(Shots.has(a, HomeActivity.WIDGET_BY_HAND));
        assertFalse(Shots.allText(a.sheets()).contains("请"));
        Shots.click(a, "好了");
        assertFalse(a.sheets().isShowing());
    }

    /** Large text: 「RANK UP」 steps aside so the rank and the 顺便 tail stay whole (360 dp and 320 dp). */
    @Test
    @Config(fontScale = 1.3f)
    public void theRankUpBadgeStepsAsideWithLargeText() {
        HomeActivity a = home();
        a.showUndo(7, "女教皇 RANK 7 · 顺便的事没做", true);
        Shots.capture(a, false);
        a.placeUndo();
        Shots.capture(a, false);
        assertFalse(a.undoBar().showsBadge());
        assertFalse(a.undoBar().messageCut());
        Shots.shot(a, "home_undo_bar_font130");
        // A 320 dp phone: the bar is 320 − 2 × 12 dp wide.
        a.showUndo(8, "女教皇 RANK 7", true);
        UndoBar bar = a.undoBar();
        bar.measure(android.view.View.MeasureSpec.makeMeasureSpec(Ui.dp(a, 296), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
        bar.layout(0, 0, bar.getMeasuredWidth(), bar.getMeasuredHeight());
        assertFalse(bar.showsBadge());
        assertFalse(bar.messageCut());
    }

    /** 「做不了…」 stays on one line next to 「完成 ✓」 (1 : 1.6) on a 320 dp phone with large text. */
    @Test
    @Config(fontScale = 1.3f)
    public void theHeroButtonsKeepOneLineOnASmallPhone() {
        HomeActivity a = home();
        Shots.capture(a, false);
        android.widget.TextView cannot = (android.widget.TextView) Shots.find(a, TodayPage.CANNOT_LABEL);
        // The hero's row on a 320 dp phone: 320 − 2 × 16 (gutter) − 22 − 18 (card padding).
        android.view.View row = (android.view.View) cannot.getParent();
        row.measure(android.view.View.MeasureSpec.makeMeasureSpec(Ui.dp(a, 248), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
        assertEquals(1, cannot.getLineCount());
        assertEquals(0, cannot.getLayout().getEllipsisCount(0));
        float min = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 12,
            a.getResources().getDisplayMetrics());
        assertTrue("it shrinks a little, never below 12sp", cannot.getTextSize() >= min - 1);
    }

    /** The stripe of an ordinary replan never moves the page; the first plan has only the hero's. */
    @Test
    public void theReplanStripeDoesNotMoveThePage() {
        HomeActivity a = home();
        android.view.View root = (android.view.View) a.undoBar().getParent();
        Shots.capture(a, false);
        int idle = HomeActivity.boundsIn(Shots.find(a, "完成 ✓"), root).top;
        assertEquals(0, stripes(a.getWindow().getDecorView()));
        api.snap.planning = true;
        api.fireChanged();
        Shots.idle();
        Shots.capture(a, false);
        assertEquals(1, stripes(a.getWindow().getDecorView()));
        assertEquals(idle, HomeActivity.boundsIn(Shots.find(a, "完成 ✓"), root).top);

        api.snap.plan = null;
        api.snap.planCurrent = false;
        api.fireChanged();
        Shots.idle();
        assertTrue(Shots.has(a, "正在排到 1月31日…"));
        assertEquals("one wait, one stripe", 1, stripes(a.getWindow().getDecorView()));
    }

    private static int stripes(android.view.View v) {
        if (v.getVisibility() != android.view.View.VISIBLE) {
            return 0;
        }
        int n = v instanceof BusyStripe ? 1 : 0;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += stripes(g.getChildAt(i));
            }
        }
        return n;
    }

    /** The tab header is as tall as its title: the watermark is cropped, never adds a blank band. */
    @Test
    public void aTabHeaderIsAsTallAsItsTitle() {
        HomeActivity a = home();
        android.widget.FrameLayout header = Ui.pageHeader(a, "日程", "CALENDAR", null);
        header.measure(android.view.View.MeasureSpec.makeMeasureSpec(Ui.dp(a, 360), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
        android.view.View watermark = header.getChildAt(0);
        android.view.View column = header.getChildAt(1);
        assertEquals(column.getMeasuredHeight(), header.getMeasuredHeight());
        assertTrue(watermark.getMeasuredHeight() > header.getMeasuredHeight());
    }

    /** Sub-pages: back is a full 48 dp target; a long title wraps to a second line instead of 「…」. */
    @Test
    public void subPageBackIsAFullTargetAndLongTitlesWrap() {
        HomeActivity a = home();
        String title = "巴比伦大淫妇 · 合体配方 · 特殊合体";
        a.pushPage(title, Ui.body(a, "配方"));
        Shots.capture(a, false);
        android.view.View back = Shots.find(a, "返回");
        assertEquals(Ui.dp(a, Ui.TOUCH), back.getWidth());
        assertEquals(Ui.dp(a, Ui.TOUCH), back.getHeight());
        android.widget.TextView heading = (android.widget.TextView) Shots.find(a, title);
        assertEquals(2, heading.getLineCount());
        assertEquals(0, heading.getLayout().getEllipsisCount(1));
        Shots.shot(a, "home_subpage_long_title");
    }

    /** A pressable card (社群 tiles, 宿舍 tiles, material slots) outlines itself in cyan when pressed. */
    @Test
    public void aPressableCardLightsItsOutlineWhenPressed() {
        HomeActivity a = home();
        SlantedPanelDrawable card = Ui.pressableCard(a);
        int w = 240;
        int h = 90;
        android.graphics.Bitmap rest = drawn(card, w, h, new int[0]);
        android.graphics.Bitmap pressed = drawn(card, w, h, new int[]{android.R.attr.state_pressed});
        int restEdge = rest.getPixel(w / 2, h - 2);
        int pressedEdge = pressed.getPixel(w / 2, h - 2);
        assertTrue("cyan edge: " + Integer.toHexString(pressedEdge),
            android.graphics.Color.green(pressedEdge) > android.graphics.Color.green(restEdge) + 80);
    }

    private static android.graphics.Bitmap drawn(SlantedPanelDrawable d, int w, int h, int[] state) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.setState(state);
        d.draw(new android.graphics.Canvas(bitmap));
        return bitmap;
    }

    /** The render guard's exemption is for decorative Latin in the numeral face only. */
    @Test
    public void englishInTheSystemFaceIsCaughtEvenWhenHidden() {
        HomeActivity a = home();
        android.widget.TextView label = Ui.text(a, "Social Link", 13, android.graphics.Color.WHITE, false);
        label.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        boolean caught = false;
        try {
            Shots.checkTypography(label, "guard");
        } catch (AssertionError expected) {
            caught = true;
        }
        assertTrue(caught);
        android.widget.TextView kicker = Ui.numeral(a, "SOCIAL LINK", 15, android.graphics.Color.WHITE);
        kicker.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Shots.checkTypography(kicker, "guard");
    }

    private static TowerPanel findPanel(android.view.View v) {
        if (v instanceof TowerPanel) {
            return (TowerPanel) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TowerPanel p = findPanel(g.getChildAt(i));
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }
}
