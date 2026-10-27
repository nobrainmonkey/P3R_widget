package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.p3r.dayplanner.v3.model.Rules;

/** The planned route and game availability share dates, not decisions or record actions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScheduleGridUiTest extends UiTestBase {
    @Test public void sourceAndViewAreIndependentAndRemembered() {
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.capture(home, false);
        assertTrue(Shots.find(home, "日程列表").isSelected());
        assertTrue(Shots.find(home, "月历") instanceof ImageView);
        Shots.click(home, "月历");
        assertNotNull(find(home.getWindow().getDecorView(), ScheduleMonthGrid.class));
        assertTrue(Shots.find(home, "月历").isSelected());
        Shots.shot(home, "home_schedule_grid");

        Shots.click(home, "活动日历");
        assertTrue("activity calendar starts as a grid", Shots.find(home, "月历").isSelected());
        Shots.click(home, "日程列表");
        assertNotNull(find(home.getWindow().getDecorView(), ListView.class));
        Shots.click(home, "我的安排");
        assertTrue("my route retains its grid", Shots.find(home, "月历").isSelected());
        Shots.click(home, "活动日历");
        assertTrue("activity calendar retains its list", Shots.find(home, "日程列表").isSelected());
        assertTrue(UiPrefs.calendarAvailable(home));
        assertTrue(UiPrefs.calendarGrid(home, false));
        assertFalse(UiPrefs.calendarGrid(home, true));
        assertTrue("browsing doesn't change game progress", api.calls.isEmpty());
    }

    @Test public void generalCalendarWorksWhileTheFirstPlanIsLoading() {
        api.snap.plan = null;
        api.snap.planning = true;
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        assertTrue(Shots.has(home, SchedulePage.PLANNING));
        Shots.click(home, "活动日历");
        Shots.capture(home, false);
        ScheduleMonthGrid grid = find(home.getWindow().getDecorView(), ScheduleMonthGrid.class);
        assertNotNull(grid);
        assertEquals(Fixtures.OCT_22, grid.selectedDay());
        assertTrue(Shots.has(home, "打工"));
        assertFalse(Shots.has(home, SchedulePage.PLANNING));
        Shots.shot(home, "home_activity_calendar_grid");
        assertTrue(api.calls.isEmpty());
    }

    @Test public void selectingGeneralDaysShowsTheDateActivitiesAndKeepsSourceControls() {
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.click(home, "活动日历");
        ScheduleMonthGrid grid = find(home.getWindow().getDecorView(), ScheduleMonthGrid.class);
        assertNotNull(grid);
        grid.select(Fixtures.OCT_22 + 1, true);
        Shots.capture(home, false);
        assertTrue(Shots.has(home, Zh.dayAndWeekday(Fixtures.OCT_22 + 1)));
        assertTrue(Shots.has(home, "社群"));
        assertTrue(Shots.has(home, "打工"));
        View social = category(home.getWindow().getDecorView(), "社群");
        assertNotNull(social);
        assertEquals("已收起", social.getStateDescription());
        social.performClick();
        Shots.idle();
        assertEquals("已展开", social.getStateDescription());
        ScrollView scroll = find(home.getWindow().getDecorView(), ScrollView.class);
        assertNotNull(scroll);
        scroll.scrollTo(0, Ui.dp(home, 450));
        Shots.shot(home, "home_activity_calendar_day");
        assertTrue("source controls remain above the scroll", Shots.find(home, "我的安排").getGlobalVisibleRect(new android.graphics.Rect()));
        assertTrue(api.calls.isEmpty());
    }

    @Test public void yesterdayInTheGridUsesActualRecordedResultsAndOpensCorrections() {
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.click(home, "月历");
        ScheduleMonthGrid grid = find(home.getWindow().getDecorView(), ScheduleMonthGrid.class);
        grid.select(Fixtures.OCT_22 - 1, true);
        assertTrue(Shots.has(home, "在宿舍房间学习 · 学力 +2"));
        assertFalse("only the previous day is selectable in the route", findDay(grid, Fixtures.OCT_22 - 2).isEnabled()
            && findDay(grid, Fixtures.OCT_22 - 2).isClickable());
        Shots.click(home, "晚上，在宿舍房间学习 · 学力 +2，查看或修改记录");
        Shots.idle();
        assertTrue(Shots.has(home, "修改这条记录：10月21日 晚上 · 在宿舍房间学习 · 学力 +2"));
    }

    @Test public void browsingEarlierActivityMonthsThenReturningToThePlanFindsToday() {
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.click(home, "月历");
        Shots.click(home, "活动日历");
        ScheduleMonthGrid grid = find(home.getWindow().getDecorView(), ScheduleMonthGrid.class);
        grid.select(Fixtures.day(5, 12), true);
        assertEquals(Fixtures.day(5, 12), grid.selectedDay());
        Shots.click(home, "我的安排");
        assertEquals(Fixtures.OCT_22, grid.selectedDay());
        assertFalse(Shots.has(home, "这一天没有安排"));
        grid.select(Fixtures.day(12, 10), true);
        assertTrue(Shots.has(home, "回到今天"));
        Shots.click(home, "回到今天");
        assertEquals(Fixtures.OCT_22, grid.selectedDay());
        assertFalse(Shots.has(home, "回到今天"));
    }

    @Test public void anOpenActivityCategorySurvivesBackgroundPlanUpdates() {
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.click(home, "活动日历");
        View work = category(home.getWindow().getDecorView(), "打工");
        assertNotNull(work);
        assertEquals("已收起", work.getStateDescription());
        work.performClick();
        assertEquals("已展开", work.getStateDescription());
        api.fireChanged();
        Shots.idle();
        View after = category(home.getWindow().getDecorView(), "打工");
        assertNotNull(after);
        assertEquals("已展开", after.getStateDescription());
    }

    @Test @Config(fontScale = 1.3f)
    public void largerTextKeepsDayNumbersAndBothSummariesInsideTheirTiles() {
        HomeActivity home = home();
        home.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.click(home, "月历");
        Shots.shot(home, "home_schedule_grid_font130");
        ScheduleMonthGrid grid = find(home.getWindow().getDecorView(), ScheduleMonthGrid.class);
        View day = findDay(grid, Fixtures.OCT_22);
        assertNotNull(day);
        ViewGroup tile = (ViewGroup) day;
        for (int i = 0; i < tile.getChildCount(); i++) {
            TextView text = (TextView) tile.getChildAt(i);
            assertTrue("tile holds " + text.getText(), text.getBottom() <= tile.getHeight());
            assertTrue("no clipped line " + text.getText(), text.getLayout().getHeight()
                <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
    }

    private static View findDay(View root, int day) {
        String prefix = "日历 " + Zh.date(day);
        if (root.getContentDescription() != null && root.getContentDescription().toString().startsWith(prefix)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDay(group.getChildAt(i), day);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View category(View root, String category) {
        if (root.getVisibility() != View.VISIBLE) return null;
        if (root.getContentDescription() != null
                && root.getContentDescription().toString().startsWith(category + "，")) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = category(group.getChildAt(i), category);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> T find(View root, Class<T> type) {
        if (!root.isShown()) return null;
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
