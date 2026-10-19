package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/** Popup chrome at the normal phone size keeps labels, pending choices and callbacks intact. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PopupChromeTest extends UiTestBase {
    @Test
    public void filterPopupHighlightsActualTagsAndCombatWithoutChangingLabelsOrWritingProgress() {
        api.rules.reminders = new Rules.Reminders();
        Rules.Reminder unlock = new Rules.Reminder();
        unlock.id = "feature.popup.unlock";
        unlock.kind = Plan.Reminder.FEATURE;
        unlock.labelZh = "社群解锁";
        unlock.day = Fixtures.OCT_22;
        unlock.zh = "勇气达到 6 级后，可开启女教皇社群。";
        api.rules.reminders.items.add(unlock);
        Rules.CombatTip guardian = new Rules.CombatTip();
        guardian.id = "guardian.popup.36";
        guardian.kind = "guardian";
        guardian.titleZh = "36层守关战";
        guardian.floor = 36;
        guardian.fromDay = Fixtures.day(6, 13);
        guardian.shortZh = "弱点：冰";
        guardian.tacticsZh = "用冰属性攻击，优先处理随从。";
        api.rules.reminders.combatTips.add(guardian);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "tips");
        HomeActivity a = home(intent);

        a.getWindow().getDecorView().findViewWithTag("tips_filter_button").performClick();
        Shots.idle();
        choose(a, "社群解锁");
        TextView tag = (TextView) Shots.find(a.sheets().content(), "社群解锁");
        assertTrue(tag.isSelected());
        assertEquals("社群解锁", Zh.plain(tag.getText()));
        assertFalse(Shots.find(a.sheets().content(), "全部内容").isSelected());
        Shots.shot(a, "popup_filter_selected_tag");
        a.onBackPressed();
        Shots.idle();
        assertFalse(a.sheets().isShowing());
        assertFalse(a.getWindow().getDecorView().findViewWithTag("tips_filter_button").isSelected());
        assertTrue(api.calls.isEmpty());

        a.getWindow().getDecorView().findViewWithTag("tips_filter_button").performClick();
        Shots.idle();
        choose(a, "战斗");
        TextView combat = (TextView) Shots.find(a.sheets().content(), "战斗");
        assertTrue(combat.isSelected());
        assertEquals("战斗", Zh.plain(combat.getText()));
        assertFalse(Shots.find(a.sheets().content(), "社群解锁").isSelected());
        Shots.shot(a, "popup_filter_selected_combat");
        choose(a, "应用");
        assertFalse(a.sheets().isShowing());
        assertTrue(a.getWindow().getDecorView().findViewWithTag("tips_filter_button").isSelected());
        assertTrue(Shots.find(a, "本期").isSelected());
        assertEquals("10月1日 ～ 11月30日 · 战斗", Zh.plain(((TextView) a.getWindow().getDecorView()
            .findViewWithTag("tips_filter_selection")).getText()));
        assertTrue(api.calls.isEmpty());
        assertEquals(Fixtures.SLOT_NOW, api.snap.observed.slot);
    }

    @Test
    public void moneyAndConfirmationPopupsKeepTheirConfirmAndCancelCallbacks() {
        HomeActivity a = home();
        List<Long> amounts = new ArrayList<>();
        CashPad.Options options = new CashPad.Options();
        options.initial = 12345L;
        CashPad pad = CashPad.show(a, options, amounts::add);
        Shots.clickInDialog("6");
        Shots.clickInDialog("000");
        assertEquals(6000L, pad.value());
        assertTrue("editing is pending until confirmation", amounts.isEmpty());
        Shots.shot(a, "popup_cash_pad");
        Shots.clickInDialog("确定");
        assertEquals(Collections.singletonList(6000L), amounts);

        AtomicInteger confirmations = new AtomicInteger();
        Dialog cancel = ConfirmDialog.show(a, "删除记录？", "确认后将删除这条记录。", "删除", confirmations::incrementAndGet);
        Shots.shot(a, "popup_confirmation");
        Shots.clickInDialog("取消");
        assertFalse(cancel.isShowing());
        assertEquals(0, confirmations.get());
        Dialog confirm = ConfirmDialog.show(a, "删除记录？", "确认后将删除这条记录。", "删除", confirmations::incrementAndGet);
        Shots.clickInDialog("删除");
        assertFalse(confirm.isShowing());
        assertEquals(1, confirmations.get());
        assertTrue(api.calls.isEmpty());
    }

    private static void choose(HomeActivity a, String label) {
        assertTrue(a.sheets().isShowing());
        View choice = Shots.find(a.sheets().content(), label);
        assertTrue(Shots.clickTarget(choice).performClick());
        Shots.idle();
    }
}
