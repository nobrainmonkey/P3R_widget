package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.TextView;

import java.util.Arrays;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

/** The balance remains legible and the activity directory is a read-only view of this date. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TodayAvailableUiTest extends UiTestBase {
    @Test
    public void sevenDigitBalanceFitsAt360() {
        assertCashFits(9_999_999, true, "9,999,999", "today_cash_seven_digits_360");
    }

    @Test
    @Config(qualifiers = "zh-rCN-w390dp-h844dp-xxhdpi")
    public void sevenDigitBalanceFitsAt390() {
        assertCashFits(1_234_567, true, "1,234,567", "today_cash_seven_digits_390");
    }

    @Test
    @Config(qualifiers = "zh-rCN-w430dp-h932dp-xxhdpi")
    public void sevenDigitBalanceFitsAt430() {
        assertCashFits(9_999_999, true, "9,999,999", "today_cash_seven_digits_430");
    }

    @Test
    @Config(fontScale = 1.3f)
    public void sevenDigitLowerBoundFitsAtLargeFont() {
        assertCashFits(9_999_999, false, "9,999,999+", "today_cash_seven_digits_font130");
    }

    private void assertCashFits(long balance, boolean exact, String expected, String screenshot) {
        api.snap.observed.cash = balance;
        api.snap.observed.cashExact = exact;
        HomeActivity activity = home();
        Shots.capture(activity, false);
        TextView number = activity.getWindow().getDecorView().findViewWithTag("today-cash-number");
        assertNotNull(number);
        assertEquals(expected, Zh.plain(number.getText()));
        assertEquals(1, number.getLineCount());
        assertNotNull(number.getLayout());
        assertEquals(0, number.getLayout().getEllipsisCount(0));
        float available = number.getWidth() - number.getCompoundPaddingLeft() - number.getCompoundPaddingRight();
        assertTrue("balance clips: " + number.getLayout().getLineWidth(0) + " > " + available,
            number.getLayout().getLineWidth(0) <= available + 1);
        assertTrue(number.getWidth() > Ui.dp(activity, 160));
        Shots.shot(activity, screenshot);
    }

    @Test
    public void directoryShowsDormAndArcadeEvenWithFullStatsAndNoTraitGoal() {
        addActivities();
        api.snap.observed.slot = Fixtures.SLOT_NOW + 1;
        api.snap.observed.statPts = new int[]{999, 999, 999};
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        HomeActivity activity = home();
        View directory = activity.getWindow().getDecorView().findViewWithTag("today-available-actions");
        assertNotNull(directory);
        String text = Shots.allText(directory);
        assertTrue(text, text.contains("今天可做"));
        assertTrue(text, text.contains("与顺平阅读"));
        assertTrue(text, text.contains("电玩城 · 恐怖游戏"));
        assertTrue(text, text.contains("勇气 +4"));
        assertTrue(text, text.contains("咖啡店「夏卡尔」打工"));
        assertFalse(text, text.contains("解锁"));
        assertFalse(text, text.contains("放学后"));
        assertTrue("consulting available activities never records anything", api.calls.isEmpty());
        Shots.capture(activity, false);
        View parent = directory;
        while (parent.getParent() instanceof View && !(parent instanceof android.widget.ScrollView)) {
            parent = (View) parent.getParent();
        }
        assertTrue(parent instanceof android.widget.ScrollView);
        ((android.widget.ScrollView) parent).scrollTo(0, Math.max(0, directory.getTop() - Ui.dp(activity, 12)));
        Shots.shot(activity, "today_available_evening");
        // Collapsed categories can be browsed without opening a recording sheet.
        Shots.clickTarget(Shots.find(directory, "打工")).performClick();
        Shots.idle();
        assertFalse(activity.sheets().isShowing());
        assertTrue(api.calls.isEmpty());
        Shots.shot(activity, "today_available_evening_jobs");
    }

    @Test
    public void daytimeCanBrowseTonightWithoutChangingProgress() {
        addActivities();
        HomeActivity activity = home();
        View directory = activity.getWindow().getDecorView().findViewWithTag("today-available-actions");
        assertNotNull(directory);
        int slot = api.snap.observed.slot;
        Shots.find(directory, "晚上").performClick();
        Shots.idle();
        assertTrue(Shots.allText(directory).contains("与顺平阅读"));
        assertEquals(slot, api.snap.observed.slot);
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void backgroundReplanKeepsThePeriodAndExpandedCategory() {
        addActivities();
        HomeActivity activity = home();
        View directory = activity.getWindow().getDecorView().findViewWithTag("today-available-actions");
        Shots.find(directory, "晚上").performClick();
        Shots.idle();
        Shots.clickTarget(Shots.find(directory, "打工")).performClick();
        assertTrue(Shots.allText(directory).contains("报酬"));
        api.snap.planning = true;
        api.fireChanged();
        Shots.idle();
        View refreshed = activity.getWindow().getDecorView().findViewWithTag("today-available-actions");
        assertTrue(Shots.find(refreshed, "晚上").isSelected());
        assertTrue(Shots.allText(refreshed).contains("与顺平阅读"));
        assertTrue(Shots.allText(refreshed).contains("报酬"));
        assertTrue(api.calls.isEmpty());
    }

    private void addActivities() {
        Rules rules = api.rules;
        rules.activities = Arrays.copyOf(rules.activities, 7);
        Rules.Activity read = new Rules.Activity();
        read.idx = 4;
        read.id = "dorm_junpei_read";
        read.zh = "与顺平阅读";
        read.dormPartnerZh = "伊织顺平";
        read.locationZh = "宿舍 · 休息室";
        read.eve = true;
        read.gain = new int[]{0, 0, 2};
        rules.activities[4] = read;
        Rules.Activity garden = new Rules.Activity();
        garden.idx = 5;
        garden.id = "dorm_junpei_garden";
        garden.zh = "与顺平园艺";
        garden.dormPartnerZh = "伊织顺平";
        garden.locationZh = "宿舍 · 天台";
        garden.eve = true;
        rules.activities[5] = garden;
        Rules.Activity arcade = new Rules.Activity();
        arcade.idx = 6;
        arcade.id = "arcade_horror";
        arcade.zh = "电玩城 · 恐怖游戏";
        arcade.locationZh = "波罗尼亚商城 · 电玩城";
        arcade.eve = true;
        arcade.cost = 3000;
        arcade.gain = new int[]{0, 0, 4};
        rules.activities[6] = arcade;
        SimState state = Fixtures.observedMid(rules);
        api.snap.observed = state;
        api.snap.plan = Fixtures.planMid(rules, state);
    }
}
