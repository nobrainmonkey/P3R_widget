package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.util.reflector.Reflector.reflector;

import android.content.Context;
import android.widget.AbsListView;
import android.widget.ListView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowListView;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/** Saved browsing choices must not prevent opening the app on the next launch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi",
    shadows = StartupPreferencesTest.FrameworkScrollCallbacks.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StartupPreferencesTest extends UiTestBase {
    /**
     * Robolectric's default shadow only stores this listener. Android's implementation also
     * invokes it immediately. Use the SDK implementation so constructor ordering is exercised.
     */
    @Implements(ListView.class)
    public static class FrameworkScrollCallbacks extends ShadowListView {
        @RealObject private ListView realView;

        @Override @Implementation
        protected void setOnScrollListener(AbsListView.OnScrollListener listener) {
            super.setOnScrollListener(listener);
            reflector(AbsListViewReflector.class, realView).setOnScrollListener(listener);
        }
    }

    @ForType(AbsListView.class)
    interface AbsListViewReflector {
        @Direct void setOnScrollListener(AbsListView.OnScrollListener listener);
    }

    @Test public void rememberedPlanGridCanLaunchOnTodayAndStillOpenTheCalendar() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        UiPrefs.setCalendarAvailable(context, false);
        UiPrefs.setCalendarGrid(context, false, true);
        String before = api.snap.observed.toJson().toString();

        HomeActivity activity = home();
        assertTrue(Shots.has(activity, "完成 ✓"));
        activity.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.capture(activity, false);
        assertTrue(Shots.find(activity, "网格视图").isSelected());
        assertEquals(before, api.snap.observed.toJson().toString());
        assertTrue(api.calls.isEmpty());
    }

    @Test public void rememberedActivityCalendarDefaultGridCanLaunchOnToday() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        UiPrefs.setCalendarAvailable(context, true);
        // No explicit view preference: the activity calendar defaults to its month grid.
        String before = api.snap.observed.toJson().toString();

        HomeActivity activity = home();
        assertTrue(Shots.has(activity, "完成 ✓"));
        activity.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.capture(activity, false);
        assertTrue(Shots.find(activity, "网格视图").isSelected());
        assertTrue(UiPrefs.calendarAvailable(activity));
        assertEquals(before, api.snap.observed.toJson().toString());
        assertTrue(api.calls.isEmpty());
    }

    @Test public void openingTheAppAgainAfterChoosingTheGridPreservesTheSave() throws Exception {
        String before = api.snap.observed.toJson().toString();
        long revision = api.snap.stateRevision;
        ActivityController<HomeActivity> first = Robolectric.buildActivity(HomeActivity.class).setup();
        Shots.idle();
        first.get().selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.click(first.get(), "网格视图");
        assertTrue(UiPrefs.calendarGrid(first.get(), false));
        first.pause().stop().destroy();

        ActivityController<HomeActivity> second = Robolectric.buildActivity(HomeActivity.class).setup();
        Shots.idle();
        assertTrue(Shots.has(second.get(), "完成 ✓"));
        second.get().selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.capture(second.get(), false);
        assertTrue(Shots.find(second.get(), "网格视图").isSelected());
        assertEquals(before, api.snap.observed.toJson().toString());
        assertEquals(revision, api.snap.stateRevision);
        assertTrue(api.calls.isEmpty());
        second.pause().stop().destroy();
    }

    @Test public void rememberedActivityCalendarListViewRemainsSelected() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        UiPrefs.setCalendarAvailable(context, true);
        UiPrefs.setCalendarGrid(context, true, false);
        String before = api.snap.observed.toJson().toString();

        HomeActivity activity = home();
        assertTrue(Shots.has(activity, "完成 ✓"));
        activity.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.capture(activity, false);
        assertTrue(Shots.find(activity, "列表视图").isSelected());
        assertTrue(UiPrefs.calendarAvailable(activity));
        assertEquals(false, UiPrefs.calendarGrid(activity, true));
        assertEquals(before, api.snap.observed.toJson().toString());
        assertTrue(api.calls.isEmpty());
    }
}
