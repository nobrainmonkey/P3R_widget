package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.p3r.dayplanner.v3.Intents;

/**
 * 我的 (DESIGN_3.6_UI §6): the SYSTEM header, the progress hero with [修改进度] [同步现状], and one flat
 * list of the planner's own entries in order, without subtitles or section headers; entries that
 * cannot be used are left out. 3.1.0 removed the 2.x guide and its entry.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MePageTest extends UiTestBase {

    private HomeActivity me() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "me");
        return home(intent);
    }

    @Test
    public void meListsOnlyThePlannersOwnEntries() {
        HomeActivity a = me();
        String all = Shots.allText(a.getWindow().getDecorView());
        assertFalse(all, all.contains("攻略"));
        int at = -1;
        for (String title : new String[]{"我的", "游戏进度", "修改进度", "同步现状", "记录历史", "规划设置", "规划说明",
            "从头重新安排", "备份与恢复", "添加桌面小组件", "关于"}) {
            int found = all.indexOf(title + "\n", at + 1);
            assertTrue("「" + title + "」 after the entry before it:\n" + all, found > at);
            at = found;
        }
        // The version sits beside 关于.
        assertTrue(all, all.contains("关于\n" + InfoPages.versionName(a) + "\n"));
        Shots.shot(a, "me_system_list");
    }

    @Test
    public void noSubtitlesNoSectionHeadersNoRecordCount() {
        HomeActivity a = me();
        String all = Shots.allText(a.getWindow().getDecorView());
        for (String gone : new String[]{"离开一段时间后", "逐项核对", "撤销或修改之前的记录", "安排风格、人格面具",
            "怎么排的", "普通重排或深度计算", "导出全部记录", "在桌面上看下一步", "已记录", "其他\n"}) {
            assertFalse(gone + " in\n" + all, all.contains(gone));
        }
        // The progress hero carries the date plate of the next slot.
        assertTrue(Shots.has(a, "10/22"));
        assertTrue(Shots.has(a, "放学后"));
    }

    @Test
    public void heroButtonsOpenTheWizardInTheirModes() {
        HomeActivity a = me();
        Shots.click(a, "同步现状");
        Intent sync = org.robolectric.Shadows.shadowOf(a).getNextStartedActivity();
        assertEquals("sync", sync.getStringExtra(Intents.EXTRA_STATE_MODE));
        Shots.click(a, "修改进度");
        Intent edit = org.robolectric.Shadows.shadowOf(a).getNextStartedActivity();
        assertEquals("edit", edit.getStringExtra(Intents.EXTRA_STATE_MODE));
        Shots.click(a, "规划设置");
        assertEquals(1, a.pageDepth());
    }

    @Test
    public void beforeOnboardingOnlyStartAndTheEntriesThatWork() {
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        HomeActivity a = me();
        assertTrue(Shots.has(a, "还没设置进度"));
        assertTrue(Shots.has(a, "开始设置"));
        for (String hidden : new String[]{"同步现状", "修改进度", "记录历史", "规划说明", "从头重新安排"}) {
            assertFalse(hidden + " is left out, not dimmed", Shots.has(a, hidden));
        }
        assertTrue(Shots.has(a, "规划设置"));
        assertTrue(Shots.has(a, "备份与恢复"));
        Shots.shot(a, "me_first_run");
        Shots.click(a, "开始设置");
        Intent start = org.robolectric.Shadows.shadowOf(a).getNextStartedActivity();
        assertEquals("onboarding", start.getStringExtra(Intents.EXTRA_STATE_MODE));
    }

    @Test
    public void rowsAreFlatAndAnnouncedByTheirTitle() {
        HomeActivity a = me();
        Shots.capture(a, false);
        View row = Shots.clickTarget(Shots.find(a, "备份与恢复"));
        assertTrue(row instanceof SystemList.Row);
        assertEquals("备份与恢复", row.getContentDescription().toString());
        assertTrue("no boxed card behind a row", row.getBackground() instanceof SystemList.Cursor);
        assertTrue(row.getHeight() >= Ui.dp(a, 48));
    }
}
