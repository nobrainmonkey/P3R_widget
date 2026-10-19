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
        FakePlannerApi.Call save = api.last();
        assertEquals("saveProfile", save.method);
        assertEquals(Fixtures.PROFILE_REV, save.expectedRevision);
        assertEquals(Profile.Tier.WANT, save.profile.tier[1]);
        assertEquals(Profile.Tier.WANT, a.data().profile().tier[1]);
        // A second edit right after uses the new revision (no false conflict).
        Shots.click(a, "魔术师 目标：想要，点按切换");
        assertEquals(Fixtures.PROFILE_REV + 1, api.last().expectedRevision);
        assertEquals(Profile.Tier.MUST, api.last().profile.tier[1]);
    }

    @Test
    public void goalOrderArrowsSwap() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        HomeActivity a = home(intent);
        Shots.click(a, "上移 恋爱");
        Profile saved = api.last().profile;
        assertTrue(saved.order[6] < saved.order[4]);
    }

    @Test
    public void listenerUpdatesShowChangeSummary() {
        HomeActivity a = home();
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed, api.snap.observed.slot, "plan-2");
        api.snap.plan.steps.set(1, Fixtures.social(api.rules, Fixtures.SLOT_NOW + 1, "hermit", 3, 4, true, false));
        api.fireChanged();
        Shots.idle();
        assertTrue(Shots.has(a, "已重新安排：这周 1 处有变"));
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
        Shots.click(a, "基本都带");
        assertEquals(Profile.PersonaMode.ALL, api.last().profile.personaMode);
        assertTrue(api.last().profile.personaBonus);
        Shots.click(a, "不带/不一定");
        assertEquals(Profile.PersonaMode.NONE, api.last().profile.effectivePersonaMode());
        assertFalse(api.last().profile.personaBonus);
        Shots.click(a, "结缘网站的字条，已开启");
        assertFalse(api.last().profile.datingNotes);
    }

    @Test
    public void episodeTrackToggleSavesProfile() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        HomeActivity a = home(intent);
        assertTrue(Shots.has(a, "羁绊剧情"));
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
        assertEquals("先粘贴备份内容。", a.undoBar().messageText().toString());
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
        a.showUndo(7, "皇帝 RANK 7");
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
