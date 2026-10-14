package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.service.PlannerApi.FullReplanResult;

/**
 * 从头重新安排 in the app (spec §1.1): the 我的 entry and the 规划设置 footer open one confirmation
 * sheet, one request per confirmation, the running line on 今天 with 「先不算了」, and every result
 * shown once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FullReplanUiTest extends UiTestBase {
    private static final String ENTRY = "从头重新安排";
    private static final String RUNNING = "正在从头重新安排…";

    private HomeActivity me() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.idle();
        return a;
    }

    /** The 我的 entry row. */
    private static View entry(HomeActivity a) {
        return Shots.clickTarget(Shots.find(a, ENTRY));
    }

    @Test
    public void theEntryOpensTheSheetAndConfirmingAsksExactlyOnceEvenOnADoubleTap() {
        HomeActivity a = me();
        View row = entry(a);
        assertTrue(row.isEnabled());
        assertEquals("从头重新安排，重新读取进度和规划设置，从头再排一遍", row.getContentDescription().toString());
        Shots.shot(a, "me_full_replan_entry");

        assertTrue(row.performClick());
        Shots.idle();
        assertTrue(a.sheets().isShowing());
        View sheet = a.sheets();
        Shots.find(sheet, "从头重新安排？");
        Shots.find(sheet, "会重新读取你记下的进度和规划设置，不沿用现在这一版，从头排到1月31日。"
            + "比平时多算一会儿，一般几秒，最多十来秒。接下来几天的安排可能会变。");
        Shots.find(sheet, "先不了");
        Shots.shot(a, "full_replan_sheet");
        assertEquals("nothing is asked before confirming", 0, api.fullReplanRequests);

        View go = Shots.clickTarget(Shots.find(sheet, ENTRY));
        go.performClick();
        go.performClick();
        Shots.idle();
        assertEquals(1, api.fullReplanRequests);
        assertFalse(a.sheets().isShowing());

        // Running: the entry says so and cannot be used again; the footer's sheet shows it too.
        assertFalse(entry(a).isEnabled());
        assertTrue(Shots.has(a, RUNNING));
        a.requestFullReplan();
        Shots.idle();
        assertEquals("a request is running: no second one", 1, api.fullReplanRequests);
    }

    @Test
    public void notNowClosesTheSheetWithoutAsking() {
        HomeActivity a = me();
        entry(a).performClick();
        Shots.idle();
        Shots.clickTarget(Shots.find(a.sheets(), "先不了")).performClick();
        Shots.idle();
        assertFalse(a.sheets().isShowing());
        assertEquals(0, api.fullReplanRequests);
    }

    @Test
    public void theEntryIsOffWithoutProgressAfterTheLastDayAndWhileRunning() {
        HomeActivity running;
        api.snap.fullReplanning = true;
        running = me();
        View row = entry(running);
        assertFalse(row.isEnabled());
        assertEquals(0.45f, row.getAlpha(), 0.001f);
        assertTrue("the subtitle says it is running", Shots.has(running, RUNNING));

        api = FakePlannerApi.midGame();
        api.snap.observed.slot = api.rules.lastSlot() + 1;
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        Api.setForTest(api);
        HomeActivity finished = me();
        assertFalse(entry(finished).isEnabled());
        assertTrue(Shots.has(finished, "重新读取进度和规划设置，从头再排一遍"));

        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        HomeActivity fresh = me();
        View off = entry(fresh);
        assertFalse(off.isEnabled());
        assertEquals(0.45f, off.getAlpha(), 0.001f);
        FullReplanSheet.show(fresh);
        assertFalse("nothing to replan before onboarding", fresh.sheets().isShowing());
    }

    @Test
    public void whileRunningTodaySaysSoAndOffersToStop() {
        api.snap.fullReplanning = true;
        api.snap.planErrorZh = "生成安排时出了问题，已保留上一版";
        HomeActivity a = home();
        assertTrue(Shots.has(a, RUNNING));
        assertTrue(Shots.has(a, "可以照常记录，记下的结果会一起算进去"));
        assertFalse("no error card meanwhile", Shots.allText(a.getWindow().getDecorView()).contains("这次没能排好"));
        assertTrue("recording goes on as usual", Shots.has(a, "完成 ✓"));
        Shots.shot(a, "home_today_full_replanning");

        Shots.click(a, "先不算了");
        assertEquals(1, api.fullReplanCancels);
    }

    @Test
    public void eachResultIsShownOnceInTheMessageBarAndTheStatusLine() {
        HomeActivity a = home();
        Object[][] cases = {
            {FullReplanResult.CHANGED, "从头重新安排好了：这一周有 1 处变动"},
            {FullReplanResult.UNCHANGED, "重新算过了，安排没有变化"},
            {FullReplanResult.KEPT, "重新算过了，没有比现在的安排更好，保留现在的安排"},
            {FullReplanResult.FAILED, "这次没能从头重新安排，现在的安排不变"},
            {FullReplanResult.CANCELLED, "已停下，现在的安排不变"},
        };
        for (Object[] c : cases) {
            FullReplanResult result = (FullReplanResult) c[0];
            String text = (String) c[1];
            a.requestFullReplan();
            Shots.idle();
            assertTrue(result + ": running at once", Shots.has(a, RUNNING));
            if (result == FullReplanResult.CHANGED) {
                api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed, api.snap.observed.slot, "plan-full");
                api.snap.plan.steps.set(1, Fixtures.social(api.rules, Fixtures.SLOT_NOW + 1, "hermit", 3, 4, true, false));
            }
            api.endFullReplan(result);
            api.fireChanged();
            Shots.idle();
            assertTrue(result + ": message bar", a.undoBar().isShowing());
            assertEquals(text, a.undoBar().messageText().toString());
            assertFalse(Shots.has(a, RUNNING));
            if (result == FullReplanResult.CHANGED) {
                Shots.shot(a, "home_today_full_replan_changed");
            }

            a.undoBar().hide();
            api.fireChanged();
            Shots.idle();
            a.reload();
            Shots.idle();
            assertFalse(result + ": shown once", a.undoBar().isShowing());
            assertTrue(result + ": still the status line", Shots.has(a, text));
        }
        assertEquals(cases.length, api.fullReplanRequests);
    }

    /** The request ends with load(): what the service re-read replaces an optimistic profile. */
    @Test
    public void theRequestReloadsAndDropsOptimisticLeftovers() {
        HomeActivity a = home();
        Profile edited = a.data().profile().copy();
        edited.tier[1] = Profile.Tier.WANT;
        a.saveProfile(edited);
        Shots.idle();
        // The stored profile is not what the screen assumed (no listener call has said so yet).
        api.snap.profile.tier[1] = Profile.Tier.NONE;
        assertEquals("optimistic", Profile.Tier.WANT, a.data().profile().tier[1]);

        a.requestFullReplan();
        Shots.idle();
        assertEquals(1, api.fullReplanRequests);
        assertEquals("what the service has", Profile.Tier.NONE, a.data().profile().tier[1]);
    }

    @Test
    public void theSettingsFooterOpensTheSameSheet() {
        HomeActivity a = home();
        a.pushPage("规划设置", SettingsPage.build(a));
        Shots.idle();
        assertTrue(Shots.has(a, "改动会马上生效并自动重新安排；想不沿用上一版、从头排一遍："));
        Shots.click(a, ENTRY);
        assertTrue(a.sheets().isShowing());
        Shots.find(a.sheets(), "从头重新安排？");
        Shots.clickTarget(Shots.find(a.sheets(), ENTRY)).performClick();
        Shots.idle();
        assertEquals(1, api.fullReplanRequests);

        // While it runs the sheet says so instead of offering a second request.
        a.dismissSheet();
        a.popPage();
        a.pushPage("规划设置", SettingsPage.build(a));
        Shots.idle();
        Shots.click(a, ENTRY);
        assertTrue(a.sheets().isShowing());
        Shots.find(a.sheets(), RUNNING);
        assertTrue(Shots.findAll(a.sheets(), ENTRY).isEmpty());
        assertTrue(Shots.findAll(a.sheets(), "先不了").isEmpty());
    }
}
