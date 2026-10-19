package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * 安排风格 (DESIGN §20, DESIGN_3.6_UI): the two tiles at the top of 规划设置, the 当前设置 tag on 规划说明,
 * the folded 「更多选项」 with 仔细重新安排, the page following the saved profile, and the 羁绊剧情
 * default of a profile made by onboarding.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlanStyleSettingsTest extends UiTestBase {
    private static final String BREADTH_TEXT = "每个社群都解锁、都往上提";
    private static final String MAX_TEXT = "集中冲 MAX";

    private HomeActivity homeOn(String tab) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, tab);
        return home(intent);
    }

    private HomeActivity settings() {
        HomeActivity a = homeOn("me");
        Shots.click(a, "规划设置");
        return a;
    }

    @Test
    public void styleTilesComeFirstOnThePlanningSettings() {
        assertEquals("尽量都体验", SettingsPage.STYLE_TEXT[0][0]);
        assertEquals(BREADTH_TEXT, SettingsPage.STYLE_TEXT[0][1]);
        assertEquals("尽量多 MAX", SettingsPage.STYLE_TEXT[1][0]);
        assertEquals(MAX_TEXT, SettingsPage.STYLE_TEXT[1][1]);

        HomeActivity a = settings();
        String text = Shots.allText(a.getWindow().getDecorView());
        int style = text.indexOf("安排风格");
        assertTrue(text, style >= 0 && style < text.indexOf("人格面具"));
        assertTrue(text.contains(BREADTH_TEXT));
        assertTrue(text.contains(MAX_TEXT));
        // The fixture's profile has no style of its own: 尽量都体验 is chosen.
        assertTrue(Shots.clickTarget(Shots.find(a, "尽量都体验")).isSelected());
        assertFalse(Shots.clickTarget(Shots.find(a, "尽量多 MAX")).isSelected());
        Shots.shot(a, "home_settings_style");

        Shots.click(a, "尽量多 MAX");
        FakePlannerApi.Call call = api.last();
        assertEquals("saveProfile", call.method);
        assertEquals(Profile.Style.MAX, call.profile.style);
        assertTrue(Shots.clickTarget(Shots.find(a, "尽量多 MAX")).isSelected());
        assertFalse(Shots.clickTarget(Shots.find(a, "尽量都体验")).isSelected());
        int saves = api.calls.size();
        Shots.click(a, "尽量多 MAX");
        assertEquals("tapping the chosen tile saves nothing", saves, api.calls.size());

        Shots.click(a, "尽量都体验");
        assertEquals(saves + 1, api.calls.size());
        assertEquals(Profile.Style.BREADTH, api.last().profile.style);
    }

    @Test
    public void explainPageListsTheStyleAmongTheSettings() {
        assertEquals("尽量都体验", Assumptions.settings(new Profile(), api.rules).get(0));
        Profile max = new Profile();
        max.style = Profile.Style.MAX;
        assertEquals("尽量多 MAX", Assumptions.settings(max, api.rules).get(0));

        HomeActivity a = homeOn("me");
        Shots.click(a, "规划说明");
        assertTrue(Shots.has(a, "尽量都体验"));
        assertFalse("no 是怎么排的 prose", Shots.has(a, "是怎么排的"));
        Shots.shot(a, "home_explain_style");
        Shots.click(a, "去规划设置 ›");
        assertEquals(2, a.pageDepth());
        // The settings page lies on top of 规划说明 (its 「尽量都体验」 tile is found last).
        java.util.List<View> found = Shots.findAll(a.getWindow().getDecorView(), "尽量都体验");
        assertTrue(Shots.clickTarget(found.get(found.size() - 1)).isSelected());

        api.snap.profile.style = Profile.Style.MAX;
        HomeActivity b = homeOn("me");
        View page = InfoPages.explain(b);
        assertTrue(Shots.allText(page).contains("尽量多 MAX\n"));
    }

    @Test
    public void onboardingOptsIntoEveryLinkEpisodeTrack() {
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        String[] all = Profile.defaultEpisodeTracks(api.rules);
        assertArrayEquals(new String[]{"junpei", "akihiko"}, all);
        StateActivity a = stateController("onboarding").get();
        assertArrayEquals(all, a.draft().profile.episodeTracks);
        for (int i = 0; i < 14 && a.currentPage() != StateSteps.Page.EPISODES; i++) {
            Shots.click(a, "下一步");
        }
        assertEquals("the wizard asks how many were watched", StateSteps.Page.EPISODES, a.currentPage());
        Shots.shot(a, "state_onboarding_episodes_default");
        for (int i = 0; i < 14 && a.currentPage() != StateSteps.Page.CONFIRM; i++) {
            Shots.click(a, "下一步");
        }
        Shots.click(a, "保存并开始安排");
        FakePlannerApi.Call call = api.last();
        assertEquals("saveCheckpoint", call.method);
        assertArrayEquals(all, call.profile.episodeTracks);
        assertEquals(Profile.Style.BREADTH, call.profile.style);
    }

    @Test
    public void storedProfilesAreNeverRewritten() {
        PlannerApi.Snapshot snap = new PlannerApi.Snapshot();
        snap.profile = new Profile();
        snap.profileRevision = 3;
        assertSame("a saved profile", snap.profile, StateActivity.startProfile(StateDraft.Mode.ONBOARDING, snap, api.rules));
        snap.profileRevision = 0;
        snap.observed = api.snap.observed;
        assertSame("records exist", snap.profile, StateActivity.startProfile(StateDraft.Mode.ONBOARDING, snap, api.rules));
        snap.observed = null;
        assertSame(snap.profile, StateActivity.startProfile(StateDraft.Mode.SYNC, snap, api.rules));
        Profile max = new Profile();
        max.style = Profile.Style.MAX;
        snap.profile = max;
        assertEquals(0, StateActivity.startProfile(StateDraft.Mode.ONBOARDING, snap, api.rules).episodeTracks.length);
        snap.profile = new Profile();
        Profile fresh = StateActivity.startProfile(StateDraft.Mode.ONBOARDING, snap, api.rules);
        assertEquals(2, fresh.episodeTracks.length);
        assertEquals("the snapshot's own profile is untouched", 0, snap.profile.episodeTracks.length);

        api.snap.profile.episodeTracks = new String[0];
        StateActivity sync = stateController("sync").get();
        assertEquals(0, sync.draft().profile.episodeTracks.length);
    }

    @Test
    public void deepChangesLiveUnderMoreOptionsAndRestoreWhenSettingsReopen() {
        HomeActivity a = settings();
        assertFalse("folded at first", Shots.has(a, SettingsPage.DEEP));
        Shots.click(a, SettingsPage.MORE);
        ToggleRow row = (ToggleRow) Shots.clickTarget(Shots.find(a, SettingsPage.DEEP));
        assertFalse(row.isChecked());
        assertTrue(Shots.has(a, SettingsPage.DEEP_NOTE));
        assertFalse("no engine budget in seconds", Shots.has(a, "每次修改后最多计算 12 秒"));
        Shots.capture(a, false);
        android.widget.ScrollView scroll = lastScroll(a.getWindow().getDecorView(), null);
        scroll.scrollTo(0, 100_000);
        Shots.shot(a, "home_settings_more");
        scroll.scrollTo(0, 0);
        long revision = api.snap.profileRevision;
        Shots.click(a, SettingsPage.DEEP);
        assertEquals("saveProfile", api.last().method);
        assertEquals(revision, api.last().expectedRevision);
        assertTrue(api.last().profile.deepChanges);
        assertTrue(api.snap.profile.deepChanges);

        a.popPage();
        Shots.click(a, "规划设置");
        // 「更多选项」 stays open once opened.
        row = (ToggleRow) Shots.clickTarget(Shots.find(a, SettingsPage.DEEP));
        assertTrue(row.isChecked());
        Shots.click(a, SettingsPage.DEEP);
        assertFalse(api.last().profile.deepChanges);
        assertEquals("a preference change does not request a manual 30 s run", 0, api.deepReplanRequests);
        assertEquals(0, api.fullReplanRequests);

        Shots.click(a, SettingsPage.MORE);
        assertFalse(Shots.has(a, SettingsPage.DEEP));
        a.popPage();
        Shots.click(a, "规划设置");
        assertFalse("closed again, and remembered", Shots.has(a, SettingsPage.DEEP));
    }

    @Test
    public void moreOptionsSaveTheirProfileFields() {
        HomeActivity a = settings();
        Shots.click(a, SettingsPage.MORE);
        boolean charm = api.snap.profile.charmBonus;
        Shots.click(a, SettingsPage.CHARM);
        assertEquals(!charm, api.last().profile.charmBonus);
        boolean buffer = api.snap.profile.goalBuffer;
        Shots.click(a, SettingsPage.GOAL_BUFFER);
        assertEquals(!buffer, api.last().profile.goalBuffer);
        boolean best = api.snap.profile.answers == Profile.AnswerPolicy.BEST;
        Shots.click(a, SettingsPage.ANSWERS);
        assertEquals(best ? Profile.AnswerPolicy.NONE : Profile.AnswerPolicy.BEST, api.last().profile.answers);
        assertFalse("其余社群 lives on the 社群 tab only", Shots.has(a, "其余社群也尽量提升"));
    }

    @Test
    public void aRejectedSavePutsTheControlBack() {
        HomeActivity a = settings();
        assertTrue(Shots.clickTarget(Shots.find(a, "尽量都体验")).isSelected());
        api.nextReceipt = FakePlannerApi.stale();
        Shots.click(a, "尽量多 MAX");
        assertEquals("saveProfile", api.last().method);
        assertEquals("the stored profile is unchanged", Profile.Style.BREADTH, api.snap.profile.style);
        // The page follows what is saved: the tile goes back.
        assertTrue(Shots.clickTarget(Shots.find(a, "尽量都体验")).isSelected());
        assertFalse(Shots.clickTarget(Shots.find(a, "尽量多 MAX")).isSelected());
        assertTrue(a.undoBar().isShowing());
    }

    private static android.widget.ScrollView lastScroll(View v, android.widget.ScrollView found) {
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

    @Test
    public void theTopSaysWhenThePlanIsBeingMadeAgain() {
        api.snap.planning = true;
        HomeActivity a = settings();
        View busy = Shots.find(a, SettingsPage.BUSY);
        assertTrue(busy.isShown());
        api.snap.planning = false;
        a.reload();
        Shots.idle();
        assertFalse(((View) busy.getParent()).getVisibility() == View.VISIBLE);
    }
}
