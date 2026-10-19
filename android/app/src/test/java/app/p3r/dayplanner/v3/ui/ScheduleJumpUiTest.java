package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.List;

import app.p3r.dayplanner.v3.model.Rules;

/** A floating arrow returns to the game's current day after browsing future months. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScheduleJumpUiTest extends UiTestBase {
    @Test
    public void floatingArrowStaysVisibleInDecemberAndReturnsToToday() {
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.capture(home, false);
        ListView list = findList(home.getWindow().getDecorView());
        assertNotNull(list);
        scrollToMonth(home, list, 12);
        assertFalse(dayVisible(list, Fixtures.OCT_22));
        View jump = Shots.find(home, "回到今天");
        assertTrue("the shortcut is an icon, not another text button", jump instanceof ImageView);
        Rect rect = new Rect();
        assertTrue(jump.getGlobalVisibleRect(rect));
        assertTrue(rect.width() >= Ui.dp(home, 48));
        assertTrue(rect.height() >= Ui.dp(home, 48));
        Rect listRect = new Rect();
        assertTrue(list.getGlobalVisibleRect(listRect));
        assertTrue("the arrow clears the bottom navigation", rect.bottom < listRect.bottom);
        Shots.shot(home, "home_schedule_jump_future");
        Shots.click(home, "回到今天");
        Shots.capture(home, false);
        assertTrue(dayVisible(list, Fixtures.OCT_22));
        assertTrue("yesterday remains available", dayVisible(list, Fixtures.OCT_22 - 1));
        Shots.shot(home, "home_schedule_jump_today");
    }

    @Test
    public void jumpingCanBeRepeatedAfterProgressChangesWithoutANewPlanId() {
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.capture(home, false);
        ListView list = findList(home.getWindow().getDecorView());
        assertNotNull(list);
        String planId = api.snap.plan.planId;
        scrollToMonth(home, list, 12);
        Shots.click(home, "回到今天");
        Shots.capture(home, false);
        assertTrue(dayVisible(list, Fixtures.OCT_22));

        api.snap.observed.slot = Rules.slot(Fixtures.OCT_22 + 1, true);
        api.snap.stateRevision++;
        api.fireChanged();
        Shots.idle();
        assertEquals(planId, api.snap.plan.planId);
        scrollToMonth(home, list, 12);
        assertFalse(dayVisible(list, Fixtures.OCT_22 + 1));
        Shots.click(home, "回到今天");
        Shots.capture(home, false);
        assertTrue("night still belongs to the same game date", dayVisible(list, Fixtures.OCT_22 + 1));
        assertTrue(dayVisible(list, Fixtures.OCT_22));
        assertFalse("only one earlier game day is retained", containsDay(list, Fixtures.OCT_22 - 1));
    }

    @Test
    public void aFinishedCalendarKeepsItsLastGameDayAsTheJumpDestination() {
        int lastDay = Rules.dayOf(api.snap.plan.toSlot);
        int afterFinale = Rules.slot(lastDay + 1, false);
        List<SchedulePage.Item> items = SchedulePage.buildItems(api.snap.plan, afterFinale, api.rules.firstDay);
        assertTrue(items.stream().anyMatch(item -> item.type == 2 && item.day == lastDay));
        int position = SchedulePage.todayPosition(items, afterFinale);
        assertTrue(position >= 0);
        assertTrue(items.subList(position, items.size()).stream().anyMatch(item -> item.type == 2 && item.day == lastDay));
        assertFalse(items.stream().anyMatch(item -> item.type == 2 && item.day < lastDay));
    }

    private static void scrollToMonth(HomeActivity home, ListView list, int month) {
        for (int i = 0; i < list.getCount(); i++) {
            SchedulePage.Item item = (SchedulePage.Item) list.getItemAtPosition(i);
            if (item.type == 2 && item.month == month) {
                list.setSelectionFromTop(i, 0);
                Shots.capture(home, false);
                assertTrue(list.getFirstVisiblePosition() >= i);
                return;
            }
        }
        throw new AssertionError("calendar month missing: " + month);
    }

    private static boolean dayVisible(ListView list, int day) {
        for (int i = list.getFirstVisiblePosition(); i <= list.getLastVisiblePosition(); i++) {
            if (i < 0 || i >= list.getCount()) continue;
            SchedulePage.Item item = (SchedulePage.Item) list.getItemAtPosition(i);
            if (item.type == 2 && item.day == day) return true;
        }
        return false;
    }

    private static boolean containsDay(ListView list, int day) {
        for (int i = 0; i < list.getCount(); i++) {
            SchedulePage.Item item = (SchedulePage.Item) list.getItemAtPosition(i);
            if (item.type == 2 && item.day == day) return true;
        }
        return false;
    }

    private static ListView findList(View view) {
        if (!view.isShown()) return null;
        if (view instanceof ListView) return (ListView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView list = findList(group.getChildAt(i));
                if (list != null) return list;
            }
        }
        return null;
    }
}
