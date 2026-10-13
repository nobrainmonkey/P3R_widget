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
        Shots.click(a, "做不了/改做别的");
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
        Shots.click(a, "魔术师 目标：不是目标，点按切换");
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
        assertTrue(Shots.has(a, "已重新规划：覆盖至 1月31日 · 调整 1 处"));
        Shots.shot(a, "home_today_change_summary");
    }

    @Test
    public void openSlotExtraOpensTheDetailSheet() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_OPEN_SLOT, Fixtures.SLOT_NOW + 1);
        HomeActivity a = home(intent);
        assertTrue(a.sheets().isShowing());
        assertTrue(Shots.has(a, "这个时段已经记录过了。要更正可以去「我的 → 记录历史」。") || Shots.has(a, "做到这里"));
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
        Shots.click(a, "修改这条记录：10月20日 晚上 · 探索塔尔塔罗斯 · 96F · 18,500 日元");
        assertTrue(a.sheets().isShowing());
        assertFalse(Shots.has(a, "没去成"));
        TowerPanel panel = findPanel(a.sheets());
        panel.setAmountForTest(19_000);
        Shots.shot(a, "home_history_correct_tower");
        Shots.click(a, "保存修改");
        FakePlannerApi.Call call = api.last();
        assertEquals("correctTower", call.method);
        assertEquals(97, call.targetSeq);
        assertEquals(19_000, call.tower.amount);
        assertTrue(api.callsOf("recordTower").isEmpty());
    }

    @Test
    public void settingsToggleSavesProfile() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "规划设置");
        Shots.click(a, "带同阿尔卡那的人格面具，已关闭");
        assertTrue(api.last().profile.personaBonus);
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
