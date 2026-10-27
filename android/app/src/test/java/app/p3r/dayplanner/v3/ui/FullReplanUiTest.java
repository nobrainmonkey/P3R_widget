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
 * 从头重新安排 in the app: the 我的 entry and the 规划设置 footer open one confirmation sheet with one
 * action (「重新安排」, the long search), one request per confirmation, 「停下」 on 今天 and in the
 * running sheet, and every result said once in the message bar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FullReplanUiTest extends UiTestBase {
    private static final String ENTRY = "从头重新安排";
    private static final String RUNNING = "正在从头重新安排…";
    private static final String CHANGED = "重新安排好了：这周 1 处有变";

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
        assertEquals("从头重新安排", row.getContentDescription().toString());
        Shots.shot(a, "me_full_replan_entry");

        assertTrue(row.performClick());
        Shots.idle();
        assertTrue(a.sheets().isShowing());
        View sheet = a.sheets();
        Shots.find(sheet, "从头重新安排？");
        Shots.find(sheet, "最多半分钟，期间可以照常记录。");
        Shots.find(sheet, "先不了");
        String all = Shots.allText(sheet);
        for (String engine : new String[]{"深度计算", "普通重排", "秒", "规划设置"}) {
            assertFalse("no engine modes or budgets: " + engine, all.contains(engine));
        }
        Shots.shot(a, "full_replan_sheet");
        assertEquals("nothing is asked before confirming", 0, api.deepReplanRequests + api.fullReplanRequests);

        View go = Shots.clickTarget(Shots.find(sheet, FullReplanSheet.GO));
        go.performClick();
        go.performClick();
        Shots.idle();
        assertEquals("the one action is the long search", 1, api.deepReplanRequests);
        assertEquals(0, api.fullReplanRequests);
        assertFalse(a.sheets().isShowing());

        // Running: the entry says so and opens the sheet, which shows it (no second request).
        assertTrue(entry(a).isEnabled());
        assertEquals("从头重新安排，" + RUNNING, entry(a).getContentDescription().toString());
        assertTrue(Shots.has(a, RUNNING));
        a.requestDeepReplan();
        a.requestFullReplan();
        Shots.idle();
        assertEquals("a request is running: no second one", 1, api.deepReplanRequests + api.fullReplanRequests);
    }

    @Test
    public void notNowClosesTheSheetWithoutAsking() {
        HomeActivity a = me();
        entry(a).performClick();
        Shots.idle();
        Shots.clickTarget(Shots.find(a.sheets(), "先不了")).performClick();
        Shots.idle();
        assertFalse(a.sheets().isShowing());
        assertEquals(0, api.deepReplanRequests + api.fullReplanRequests);
    }

    @Test
    public void theEntryIsOffWithoutProgressAfterTheLastDayAndWhileRunning() {
        HomeActivity running;
        api.snap.fullReplanning = true;
        running = me();
        View row = entry(running);
        // Running: the row says so and opens the sheet, which shows the run instead of a second request.
        assertTrue(row.isEnabled());
        assertEquals(1f, row.getAlpha(), 0.001f);
        assertTrue("the row says it is running", Shots.has(running, RUNNING));
        assertTrue(row.performClick());
        Shots.idle();
        assertTrue(running.sheets().isShowing());
        Shots.find(running.sheets(), RUNNING);
        assertEquals(0, api.fullReplanRequests + api.deepReplanRequests);

        // Nothing left to plan, or nothing recorded yet: the row is left out, not dimmed.
        api = FakePlannerApi.midGame();
        api.snap.observed.slot = api.rules.lastSlot() + 1;
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        Api.setForTest(api);
        HomeActivity finished = me();
        assertFalse(Shots.has(finished, ENTRY));

        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        HomeActivity fresh = me();
        assertFalse(Shots.has(fresh, ENTRY));
        FullReplanSheet.show(fresh);
        assertFalse("nothing to replan before onboarding", fresh.sheets().isShowing());
    }

    @Test
    public void whileRunningTodaySaysSoAndOffersToStop() {
        api.snap.fullReplanning = true;
        api.snap.planErrorZh = "新安排没排出来，现在的安排先不变";
        HomeActivity a = home();
        assertTrue(Shots.has(a, RUNNING));
        assertFalse("no error card meanwhile", Shots.allText(a.getWindow().getDecorView()).contains("没排出来"));
        assertTrue("recording goes on as usual", Shots.has(a, "完成 ✓"));
        Shots.shot(a, "home_today_full_replanning");

        Shots.click(a, TodayPage.STOP_LABEL);
        assertEquals(1, api.fullReplanCancels);
    }

    @Test
    public void theRunningSheetOffersToStopInsteadOfASecondRequest() {
        HomeActivity a = me();
        entry(a).performClick();
        Shots.idle();
        Shots.clickTarget(Shots.find(a.sheets(), FullReplanSheet.GO)).performClick();
        Shots.idle();
        FullReplanSheet.show(a);
        assertTrue(a.sheets().isShowing());
        Shots.find(a.sheets(), RUNNING);
        assertTrue(Shots.findAll(a.sheets(), FullReplanSheet.GO).isEmpty());
        assertTrue(Shots.findAll(a.sheets(), "先不了").isEmpty());
        Shots.shot(a, "full_replan_sheet_running");
        Shots.clickTarget(Shots.find(a.sheets(), TodayPage.STOP_LABEL)).performClick();
        Shots.idle();
        assertFalse(a.sheets().isShowing());
        assertEquals(1, api.fullReplanCancels);
        assertEquals(1, api.deepReplanRequests);
    }

    @Test
    public void eachResultIsShownOnceInTheMessageBar() {
        HomeActivity a = home();
        Object[][] cases = {
            {FullReplanResult.CHANGED, CHANGED},
            {FullReplanResult.UNCHANGED, "重新安排好了，和现在的一样"},
            {FullReplanResult.KEPT, "重新安排好了，和现在的一样"},
            {FullReplanResult.FAILED, "这次没排好，安排先不变"},
            {FullReplanResult.CANCELLED, "已停下，安排没变"},
        };
        for (Object[] c : cases) {
            FullReplanResult result = (FullReplanResult) c[0];
            String text = (String) c[1];
            a.requestDeepReplan();
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
            assertFalse(result + ": not repeated on the page", Shots.has(a, text));
        }
        assertEquals(cases.length, api.deepReplanRequests);
    }

    /**
     * A record made during the run (今天 invites it) shows 「已记录：… 撤销」 for 8 s; a result that ends
     * the run meanwhile does not take the bar and its 撤销 away, and is said once that bar has gone.
     */
    @Test
    public void aResultDoesNotTakeTheUndoOfARecordMadeDuringTheRun() {
        HomeActivity a = home();
        a.requestDeepReplan();
        Shots.idle();
        a.showUndo(7, "皇帝 RANK 7", true);
        assertEquals("已记录：皇帝 RANK 7", a.undoBar().messageText().toString());
        assertTrue(a.undoBar().hasAction());
        api.endFullReplan(FullReplanResult.KEPT);
        api.fireChanged();
        Shots.idle();
        assertEquals("the record's bar stays", "已记录：皇帝 RANK 7", a.undoBar().messageText().toString());
        assertTrue("with its 撤销", a.undoBar().hasAction());
        assertEquals("the result is still known", PlanDiff.FULL_KEPT, a.data().changeSummary);
        // The record's bar has its 8 s: then the result is said, once.
        java.time.Duration barTime = java.time.Duration.ofMillis(UndoBar.DURATION_MILLIS);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(barTime);
        assertTrue(a.undoBar().isShowing());
        assertEquals(PlanDiff.FULL_KEPT, a.undoBar().messageText().toString());
        assertFalse("nothing to look at", a.undoBar().hasAction());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(barTime);
        assertFalse("said once", a.undoBar().isShowing());
        api.fireChanged();
        Shots.idle();
        assertFalse(a.undoBar().isShowing());
    }

    /**
     * The screen that asked is gone before the service answered (made again, or the player backed out
     * and came back from the launcher): the new screen takes the running request's ticket from the
     * snapshot and shows its result once, counted against the plan it found installed.
     */
    @Test
    public void aNewScreenShowsTheResultOfARequestItFoundRunning() {
        long ticket = api.requestFullReplan();
        HomeActivity a = home();
        assertTrue("running", Shots.has(a, RUNNING));
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed, api.snap.observed.slot, "plan-full");
        api.snap.plan.steps.set(1, Fixtures.social(api.rules, Fixtures.SLOT_NOW + 1, "hermit", 3, 4, true, false));
        api.endFullReplan(FullReplanResult.CHANGED);
        api.fireChanged();
        Shots.idle();
        assertEquals(ticket, api.snap.fullReplanDoneTicket);
        assertTrue(a.undoBar().isShowing());
        assertEquals(CHANGED, a.undoBar().messageText().toString());
        a.undoBar().hide();
        api.fireChanged();
        Shots.idle();
        assertFalse("once", a.undoBar().isShowing());
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

        a.requestDeepReplan();
        Shots.idle();
        assertEquals(1, api.deepReplanRequests);
        assertEquals("what the service has", Profile.Tier.NONE, a.data().profile().tier[1]);
    }

    @Test
    public void theSettingsFooterOpensTheSameSheet() {
        HomeActivity a = home();
        a.pushPage("规划设置", SettingsPage.build(a));
        Shots.idle();
        assertFalse("no caption about the planner", Shots.has(a, "按当前进度普通重排或深度计算："));
        Shots.click(a, ENTRY);
        assertTrue(a.sheets().isShowing());
        Shots.find(a.sheets(), "从头重新安排？");
        Shots.clickTarget(Shots.find(a.sheets(), FullReplanSheet.GO)).performClick();
        Shots.idle();
        assertEquals(1, api.deepReplanRequests);

        // While it runs the sheet says so instead of offering a second request.
        a.dismissSheet();
        a.popPage();
        a.pushPage("规划设置", SettingsPage.build(a));
        Shots.idle();
        Shots.click(a, ENTRY);
        assertTrue(a.sheets().isShowing());
        Shots.find(a.sheets(), RUNNING);
        assertTrue(Shots.findAll(a.sheets(), FullReplanSheet.GO).isEmpty());
        assertTrue(Shots.findAll(a.sheets(), "先不了").isEmpty());
    }

    @Test
    public void aLongSearchFoundByANewScreenCanBeStoppedAndReportsItsOwnResult() {
        long ticket = api.requestDeepReplan();
        assertTrue("a recreated screen receives the deep mode", api.snapshot().fullReplanDeep);
        HomeActivity a = home();
        assertTrue("the long search is called like any other", Shots.has(a, RUNNING));
        assertTrue("recording remains available", Shots.has(a, "完成 ✓"));
        Shots.click(a, TodayPage.STOP_LABEL);
        assertEquals(1, api.fullReplanCancels);

        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed, api.snap.observed.slot, "plan-deep");
        api.snap.plan.steps.set(1, Fixtures.social(api.rules, Fixtures.SLOT_NOW + 1, "hermit", 3, 4, true, false));
        api.endFullReplan(FullReplanResult.CHANGED);
        api.fireChanged();
        Shots.idle();
        assertEquals(ticket, api.snap.fullReplanDoneTicket);
        assertEquals(CHANGED, a.undoBar().messageText().toString());
        assertFalse(Shots.has(a, RUNNING));
        a.undoBar().hide();
        api.fireChanged();
        Shots.idle();
        assertFalse("the result appears once", a.undoBar().isShowing());
    }
}
