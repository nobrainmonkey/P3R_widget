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
    public void tipsMoreListAppliesATagWithOneTapAndTheCombatChipKeepsThePeriod() {
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
        guardian.fromDay = Fixtures.day(10, 13);
        guardian.shortZh = "弱点：冰";
        guardian.tacticsZh = "用冰属性攻击，优先处理随从。";
        api.rules.reminders.combatTips.add(guardian);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "tips");
        HomeActivity a = home(intent);

        a.getWindow().getDecorView().findViewWithTag("tips_content_more").performClick();
        Shots.idle();
        assertTrue(a.sheets().isShowing());
        View tag = a.sheets().content().findViewWithTag("tips_more_社群解锁");
        assertEquals("the tag and how many there are", "社群解锁\n1", Shots.allText(tag).trim());
        Shots.shot(a, "popup_tips_more");
        a.onBackPressed();
        Shots.idle();
        assertFalse("back closes without choosing", a.sheets().isShowing());
        assertEquals(View.GONE, a.getWindow().getDecorView().findViewWithTag("tips_filter_selection").getVisibility());

        a.getWindow().getDecorView().findViewWithTag("tips_content_more").performClick();
        Shots.idle();
        assertTrue(a.sheets().content().findViewWithTag("tips_more_社群解锁").performClick());
        Shots.idle();
        assertFalse("one tap applies and closes", a.sheets().isShowing());
        TextView pill = a.getWindow().getDecorView().findViewWithTag("tips_filter_selection");
        assertEquals("社群解锁", Zh.plain(pill.getText()));
        assertTrue(a.getWindow().getDecorView().findViewWithTag("tips_content_more").isSelected());

        a.getWindow().getDecorView().findViewWithTag("tips_content_combat").performClick();
        Shots.idle();
        assertTrue(a.getWindow().getDecorView().findViewWithTag("tips_content_combat").isSelected());
        assertFalse(a.getWindow().getDecorView().findViewWithTag("tips_content_more").isSelected());
        assertEquals(View.GONE, pill.getVisibility());
        assertTrue(a.getWindow().getDecorView().findViewWithTag("tips_scope_period").isSelected());
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
}
