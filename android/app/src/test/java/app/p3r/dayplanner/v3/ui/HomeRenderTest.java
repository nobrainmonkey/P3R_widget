package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.model.Rules;

/** Renders every 今天/日程/社群/我的 state and sub-page into $p3r.qa.dir/v3/ui. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeRenderTest extends UiTestBase {

    @Test
    public void today() {
        HomeActivity a = home();
        assertTrue(Shots.has(a, "皇帝 · 小田桐秀利"));
        assertTrue(Shots.has(a, "完成 ✓"));
        Shots.shot(a, "home_today");
    }

    @Test
    @Config(fontScale = 1.3f)
    public void todayAtLargeFontScale() {
        HomeActivity a = home();
        Shots.shot(a, "home_today_font130");
    }

    @Test
    public void todayScrolled() {
        HomeActivity a = home();
        android.widget.ScrollView scroll = findScroll(a.getWindow().getDecorView());
        Shots.capture(a, false);
        scroll.scrollTo(0, 1400);
        Shots.shot(a, "home_today_scrolled");
    }

    private static android.widget.ScrollView findScroll(android.view.View v) {
        if (v instanceof android.widget.ScrollView && v.isShown()) {
            return (android.widget.ScrollView) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.ScrollView s = findScroll(g.getChildAt(i));
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    @Test
    public void todayReturnBannerAndReplanning() {
        api.snap.lastRecordMillis = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000;
        api.snap.planning = true;
        HomeActivity a = home();
        assertTrue(Shots.has(a, "同步现状"));
        assertTrue(Shots.has(a, "正在重新安排…"));
        Shots.shot(a, "home_today_return_replanning");
        Shots.click(a, "没变");
        assertFalse(Shots.has(a, "没变"));
    }

    @Test
    public void todayLockedHero() {
        api.snap.observed.slot = Rules.slot(Fixtures.day(11, 3), true);
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        HomeActivity a = home();
        assertTrue(Shots.has(a, "继续"));
        Shots.shot(a, "home_today_locked");
    }

    @Test
    public void welcomeBeforeOnboarding() {
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        HomeActivity a = home();
        assertTrue(Shots.has(a, "开始设置"));
        Shots.shot(a, "home_welcome");
    }

    @Test
    public void schedule() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "schedule");
        HomeActivity a = home(intent);
        assertEquals(HomeActivity.TAB_SCHEDULE, a.currentTab());
        Shots.shot(a, "home_schedule");
    }

    @Test
    public void links() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        HomeActivity a = home(intent);
        Shots.shot(a, "home_links");
        findScroll(a.getWindow().getDecorView()).scrollTo(0, 1500);
        Shots.shot(a, "home_links_grid");
    }

    @Test
    public void meAndSubPages() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "me");
        HomeActivity a = home(intent);
        Shots.shot(a, "home_me");
        Shots.click(a, "规划设置");
        Shots.shot(a, "home_settings");
        a.popPage();
        Shots.click(a, "记录历史");
        Shots.shot(a, "home_history");
        a.popPage();
        Shots.click(a, "备份与恢复");
        Shots.shot(a, "home_backup");
        a.popPage();
        Shots.click(a, "规划说明");
        Shots.shot(a, "home_explain");
        a.popPage();
        Shots.click(a, "关于");
        Shots.shot(a, "home_about");
    }

    @Test
    public void detailSheetFromIntent() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_OPEN_SLOT, Fixtures.SLOT_NOW + 2);
        HomeActivity a = home(intent);
        assertTrue(a.sheets().isShowing());
        Shots.shot(a, "home_detail_future");
        a.dismissSheet();
        a.openDetail(Fixtures.SLOT_NOW);
        Shots.shot(a, "home_detail_current");
    }
}
