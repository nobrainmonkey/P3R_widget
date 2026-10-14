package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/** The state wizard: renders of every page (onboarding and 同步现状), drafts, saving and CAS. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StateWizardTest extends UiTestBase {

    private void firstRun() {
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
    }

    @Test
    public void onboardingEveryPage() {
        firstRun();
        StateActivity a = stateController("onboarding").get();
        assertEquals(StateSteps.Page.GOALS, a.currentPage());
        Shots.click(a, "皇帝");
        Shots.click(a, "皇帝");
        Shots.click(a, "恋爱");
        Shots.shot(a, "state_onboarding_goals");
        assertEquals(Profile.Tier.MUST, a.draft().profile.tier[4]);
        assertEquals(Profile.Tier.WANT, a.draft().profile.tier[6]);
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.PERSONA, a.currentPage());
        Shots.shot(a, "state_onboarding_persona");
        Shots.click(a, "不带/不一定");
        assertEquals(StateSteps.Page.DATE, a.currentPage());
        Shots.shot(a, "state_onboarding_date");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.CASH, a.currentPage());
        assertTrue(a.draft().cashUnknown());
        Shots.click(a, "8");
        Shots.click(a, "2");
        Shots.click(a, "0");
        Shots.click(a, "0");
        Shots.shot(a, "state_onboarding_cash");
        assertEquals(8_200, a.draft().cash());
        assertFalse(a.draft().cashUnknown());
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.STATS, a.currentPage());
        Shots.shot(a, "state_onboarding_stats");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.LINKS, a.currentPage());
        Shots.shot(a, "state_onboarding_links");
        Shots.click(a, "下一步");
        // A new profile opts into every 羁绊剧情 track (尽量都体验), so the wizard asks how many were watched.
        assertEquals(StateSteps.Page.EPISODES, a.currentPage());
        Shots.shot(a, "state_onboarding_episodes");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.TOWER, a.currentPage());
        Shots.shot(a, "state_onboarding_tower");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.CONFIRM, a.currentPage());
        Shots.shot(a, "state_onboarding_confirm");
        Shots.click(a, "保存并开始安排");
        FakePlannerApi.Call call = api.last();
        assertEquals("saveCheckpoint", call.method);
        assertNotNull(call.profile);
        assertEquals(Profile.Tier.MUST, call.profile.tier[4]);
        assertFalse(call.profile.personaBonus);
        assertEquals(8_200, call.state.cash);
        assertTrue(call.state.cashExact);
        assertEquals(StateDraft.firstFreeSlot(api.rules), call.state.slot);
        assertTrue(a.isFinishing());
    }

    @Test
    public void rankPadAutoAdvances() {
        firstRun();
        StateActivity a = stateController("onboarding").get();
        jumpTo(a, StateSteps.Page.LINKS);
        Shots.click(a, "魔术师，未解锁，点按修改");
        Shots.shot(a, "state_rankpad");
        Shots.clickInDialog("3");
        Shots.clickInDialog("RANK MAX");
        assertEquals(3, a.draft().rank(1));
        assertEquals(10, a.draft().rank(2));
        Shots.clickInDialog("好了");
        assertTrue(Shots.has(a, "RANK 3"));
    }

    @Test
    public void syncAnsweredYesPrefillsForecastAndShowsDiff() {
        api.snap.observed.pts[1] = 9;
        StateActivity a = stateController("sync").get();
        assertEquals(StateSteps.Page.SYNC_INTRO, a.currentPage());
        Shots.shot(a, "state_sync_intro");
        Shots.click(a, "是");
        assertEquals(StateSteps.Page.DATE, a.currentPage());
        CalendarPicker picker = findPicker(a.getWindow().getDecorView());
        picker.pick(Fixtures.day(10, 25));
        Shots.idle();
        Shots.shot(a, "state_sync_date");
        // 10/22–10/24 as planned: emperor 7, sun 8 (forecast), cash +3,000 − 0.
        StateDraft d = a.draft();
        assertEquals(7, d.rank(4));
        assertTrue(d.isForecastRank(4));
        assertEquals(21_500, d.cash());
        Shots.click(a, "下一步");
        Shots.shot(a, "state_sync_cash");
        Shots.click(a, "下一步");
        Shots.shot(a, "state_sync_stats");
        Shots.click(a, "下一步");
        Shots.shot(a, "state_sync_links");
        Shots.click(a, "下一步");
        if (a.currentPage() == StateSteps.Page.UNLOCK) {
            Shots.shot(a, "state_sync_unlock");
            Shots.click(a, "下一步");
        }
        assertEquals(StateSteps.Page.EPISODES, a.currentPage());
        Shots.shot(a, "state_sync_episodes");
        Shots.click(a, "下一步");
        Shots.shot(a, "state_sync_tower");
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.CONFIRM, a.currentPage());
        assertTrue(Shots.allText(a.getWindow().getDecorView()).contains("将更新"));
        Shots.shot(a, "state_sync_confirm");
        Shots.click(a, "保存并重新安排");
        FakePlannerApi.Call call = api.last();
        assertEquals("saveCheckpoint", call.method);
        assertNull(call.profile);
        assertEquals(Rules.slot(Fixtures.day(10, 25), false), call.state.slot);
        assertEquals(7, call.state.rank[4]);
        assertEquals(0, call.state.pts[4]);
        // An untouched link keeps its hidden estimate exactly.
        assertEquals(api.snap.observed.pts[1], call.state.pts[1]);
    }

    @Test
    public void unlockPageListsOnlyRelevantLinks() {
        StateActivity a = stateController("edit").get();
        jumpTo(a, StateSteps.Page.UNLOCK);
        assertEquals(StateSteps.Page.UNLOCK, a.currentPage());
        assertTrue(Shots.has(a, "恶魔 · 田中社长"));
        assertFalse(Shots.has(a, "皇帝 · 小田桐秀利"));
        Shots.shot(a, "state_edit_unlock");
        Shots.click(a, "已完成「再借给社长 10,000 日元」");
        assertEquals(2, a.draft().unlockStep(15));
    }

    @Test
    public void draftSurvivesRecreationAndProcessDeath() {
        ActivityController<StateActivity> controller = stateController("edit");
        StateActivity a = controller.get();
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.CASH, a.currentPage());
        a.draft().setCash(31_337);
        a.changed();
        controller.recreate();
        Shots.idle();
        StateActivity again = controller.get();
        assertEquals(31_337, again.draft().cash());
        assertEquals(StateSteps.Page.CASH, again.currentPage());
        Shots.shot(again, "state_edit_restored");
        assertEquals("已恢复上次没填完的内容。", again.noticeText());

        // A brand-new activity (process death) restores from the semantic keys as well.
        StateActivity fresh = stateController("edit").get();
        assertEquals(31_337, fresh.draft().cash());
    }

    @Test
    public void draftFromOlderRevisionIsKeptOnTheNewRecord() {
        StateActivity a = stateController("edit").get();
        a.draft().setCash(999);
        a.changed();
        api.snap.stateRevision++;
        StateActivity later = stateController("edit").get();
        assertEquals(999, later.draft().cash());
        assertEquals(api.snap.stateRevision, later.draft().baseRevision);
        assertEquals("记录有更新，已保留你填过的内容。", later.noticeText());
    }

    @Test
    public void personaPageOffersThreeChoices() {
        firstRun();
        StateActivity a = stateController("onboarding").get();
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.PERSONA, a.currentPage());
        assertTrue(Shots.has(a, "基本都带"));
        assertTrue(Shots.has(a, "只给目标社群带"));
        Shots.click(a, "只给目标社群带");
        assertEquals(Profile.PersonaMode.GOALS, a.draft().profile.personaMode);
        assertFalse(a.draft().profile.personaBonus);
        assertEquals(StateSteps.Page.DATE, a.currentPage());
    }

    @Test
    public void rescueQuestionsWriteTheRescueBits() {
        StateActivity a = stateController("edit").get();
        jumpTo(a, StateSteps.Page.TOWER);
        // 10/22: 北村文吉's window (9/12–10/3) is over, 舞子's (10/21–11/2) is running.
        assertTrue(Shots.has(a, "北村文吉救出来了吗？"));
        assertTrue(Shots.has(a, "舞子救出来了吗？"));
        Shots.shot(a, "state_edit_tower_rescue");
        Shots.click(a, "北村文吉：没救出");
        Shots.click(a, "舞子：救出了");
        SimState built = a.draft().build();
        assertEquals(0, built.rescued & 1L);
        assertEquals(1L, built.rescueFailed & 1L);
        assertEquals(2L, built.rescued & 2L);
        Shots.click(a, "北村文吉：不确定");
        built = a.draft().build();
        assertEquals(0, (built.rescued | built.rescueFailed) & 1L);
        jumpTo(a, StateSteps.Page.CONFIRM);
        assertTrue(Shots.allText(a.getWindow().getDecorView()).contains("救出舞子"));
    }

    @Test
    public void floorStepperStopsAtTheBlockOfTheDate() {
        StateActivity a = stateController("edit").get();
        a.draft().setSlot(Rules.slot(Fixtures.day(9, 15), false));
        assertEquals(68, a.draft().towerTop());
        assertEquals(68, a.draft().floor());
    }

    @Test
    public void casConflictKeepsEditsAndAsksAgain() {
        StateActivity a = stateController("edit").get();
        a.draft().setCash(50_000);
        jumpTo(a, StateSteps.Page.CONFIRM);
        api.nextReceipt = FakePlannerApi.stale();
        Shots.click(a, "保存并重新安排");
        assertFalse(a.isFinishing());
        assertNotNull(a.noticeText());
        assertTrue(a.noticeText().contains("你填的内容都还在"));
        assertEquals(50_000, a.draft().cash());
        Shots.shot(a, "state_cas_conflict");
    }

    @Test
    public void unchangedEditJustCloses() {
        StateActivity a = stateController("edit").get();
        jumpTo(a, StateSteps.Page.CONFIRM);
        assertTrue(Shots.has(a, "和上次记录一样"));
        Shots.click(a, "完成");
        assertTrue(api.callsOf("saveCheckpoint").isEmpty());
        assertTrue(a.isFinishing());
    }

    @Test
    public void backGoesToThePreviousPage() {
        StateActivity a = stateController("edit").get();
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.CASH, a.currentPage());
        a.onBackPressed();
        Shots.idle();
        assertEquals(StateSteps.Page.DATE, a.currentPage());
        a.onBackPressed();
        assertTrue(a.isFinishing());
    }

    @Test
    public void lockedDaysAreGreyedInTheCalendar() {
        StateActivity a = stateController("edit").get();
        a.draft().setSlot(Rules.slot(Fixtures.day(10, 22), true));
        a.refresh();
        CalendarPicker picker = findPicker(a.getWindow().getDecorView());
        assertFalse(CalendarPicker.selectable(api.rules, Fixtures.day(11, 4)));
        assertTrue(CalendarPicker.selectable(api.rules, Fixtures.day(11, 3)));
        picker.pick(Fixtures.day(11, 3));
        Shots.idle();
        // 11/3 evening is the full-moon operation: the daytime slot is chosen instead.
        assertFalse(Rules.isEvening(a.draft().slot()));
        Shots.shot(a, "state_edit_date_fullmoon");
    }

    private static void jumpTo(StateActivity a, StateSteps.Page page) {
        for (int i = 0; i < 12 && a.currentPage() != page; i++) {
            Shots.click(a, "下一步");
        }
    }

    private static CalendarPicker findPicker(android.view.View v) {
        if (v instanceof CalendarPicker) {
            return (CalendarPicker) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                CalendarPicker p = findPicker(g.getChildAt(i));
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }
}
