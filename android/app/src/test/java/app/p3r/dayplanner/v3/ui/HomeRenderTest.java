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
import app.p3r.dayplanner.v3.service.StoryDisplay;

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

    /** The last visible ScrollView in drawing order (the topmost page). */
    private static android.widget.ScrollView lastScroll(android.view.View v, android.widget.ScrollView found) {
        android.widget.ScrollView result = found;
        if (v instanceof android.widget.ScrollView && v.isShown()) {
            result = (android.widget.ScrollView) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                result = lastScroll(g.getChildAt(i), result);
            }
        }
        return result;
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
    public void pendingStoryDaysKeepTheirDatesAndContinueWithoutRecordingAnAction() {
        int from = api.snap.observed.slot - 4;
        int actionable = api.snap.observed.slot;
        for (int day = Rules.dayOf(from); day <= Rules.dayOf(actionable - 1); day++) {
            api.rules.days[day].dayLock = "school_trip";
            api.rules.days[day].eveLock = "school_trip";
            api.rules.days[day].noteZh = "这天去修学旅行，不能自由行动";
        }
        api.rules.lockReasonsZh.put("school_trip", "修学旅行");
        api.snap.storyDisplay = StoryDisplay.create(api.rules, api.snap.profile, "record-before-trip", from, actionable - 1, from);
        long revision = api.snap.stateRevision;
        HomeActivity a = home();
        assertEquals(from, a.data().displaySlot());
        assertTrue(Shots.has(a, "修学旅行"));
        assertTrue(Shots.has(a, StoryDisplay.CONTINUE_LABEL));
        assertTrue(Shots.has(a, "这天去修学旅行，不能自由行动。游戏里过完后点「剧情已过，继续」。"));
        Shots.shot(a, "home_today_pending_story");
        Shots.click(a, StoryDisplay.CONTINUE_LABEL);
        assertEquals(from + 2, a.data().displaySlot());
        assertEquals(actionable, api.snap.observed.slot);
        assertEquals(revision, api.snap.stateRevision);
        assertTrue("the story button never creates a gameplay write", api.calls.isEmpty());
        Shots.click(a, StoryDisplay.CONTINUE_LABEL);
        assertEquals(actionable, a.data().displaySlot());
        assertTrue(Shots.has(a, "完成 ✓"));
        assertEquals(2, api.acknowledgedStorySlots.size());
        assertTrue(api.calls.isEmpty());
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
    public void settingsLowerHalfAndBackupPaste() {
        api.snap.profile.excludedActivities = new String[]{"wakatsu"};
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "me");
        HomeActivity a = home(intent);
        Shots.click(a, "规划设置");
        Shots.capture(a, false);
        android.widget.ScrollView scroll = lastScroll(a.getWindow().getDecorView(), null);
        scroll.scrollTo(0, 5000);
        assertTrue(Shots.has(a, "定食屋「若津」吃大份套餐"));
        Shots.shot(a, "home_settings_lower");
        a.popPage();
        Shots.click(a, "备份与恢复");
        Shots.click(a, "粘贴文字");
        assertTrue(a.sheets().isShowing());
        Shots.shot(a, "home_backup_paste");
    }

    @Test
    public void linksEpisodeSection() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        HomeActivity a = home(intent);
        assertTrue(Shots.has(a, "不加社群点数，但能拿到道具/特性；勾选后会在可看的日子留出时段。"));
        Shots.capture(a, false);
        lastScroll(a.getWindow().getDecorView(), null).scrollTo(0, 100_000);
        Shots.shot(a, "home_links_episodes");
    }

    @Test
    public void detailSheetWithHowTo() {
        HomeActivity a = home();
        a.openDetail(Rules.slot(Fixtures.day(10, 25), false));
        assertTrue(Shots.has(a, "怎么做 · 3 步"));
        assertTrue(Shots.has(a, "找到穿西装的田中社长，和他说话"));
        Shots.shot(a, "home_detail_howto");
    }

    @Test
    public void todayAfterTheLastDay() {
        api.snap.observed.slot = api.rules.lastSlot() + 1;
        api.snap.observed.rank[4] = 10;
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        HomeActivity a = home();
        assertTrue(Shots.has(a, "日程已经全部结束"));
        assertFalse(Shots.has(a, "正在根据最新记录重新安排…"));
        Shots.shot(a, "home_today_finished");
    }

    @Test
    public void scheduleWrapsLongTitles() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "schedule");
        HomeActivity a = home(intent);
        android.widget.ListView list = findList(a.getWindow().getDecorView());
        Shots.capture(a, false);
        list.setSelection(6);
        Shots.shot(a, "home_schedule_long_titles");
        android.widget.TextView title = (android.widget.TextView) Shots.find(a,
            "在严户台商店街的定食屋「若津」挑战超大份的海鲜盖饭");
        assertEquals(2, title.getMaxLines());
        assertEquals(2, title.getLineCount());
    }

    private static android.widget.ListView findList(android.view.View v) {
        if (v instanceof android.widget.ListView && v.isShown()) {
            return (android.widget.ListView) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.ListView l = findList(g.getChildAt(i));
                if (l != null) {
                    return l;
                }
            }
        }
        return null;
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
