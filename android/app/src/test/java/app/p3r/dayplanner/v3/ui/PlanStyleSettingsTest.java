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
 * 安排风格 (DESIGN §20): the first card of 规划设置, the 规划说明 sentence, and the 羁绊剧情 default
 * of a profile made by onboarding.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlanStyleSettingsTest extends UiTestBase {
    private static final String BREADTH_TEXT = "先把每个社群都解锁、都往上提，再冲 MAX；时间不够时更均衡。";
    private static final String MAX_TEXT = "集中时间把尽量多的社群升到 MAX。";

    private HomeActivity homeOn(String tab) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, tab);
        return home(intent);
    }

    @Test
    public void styleIsTheFirstCardOfThePlanningSettings() {
        assertEquals("尽量都体验", SettingsPage.STYLE_TEXT[0][0]);
        assertEquals(BREADTH_TEXT, SettingsPage.STYLE_TEXT[0][1]);
        assertEquals("尽量多 MAX", SettingsPage.STYLE_TEXT[1][0]);
        assertEquals(MAX_TEXT, SettingsPage.STYLE_TEXT[1][1]);

        HomeActivity a = homeOn("me");
        Shots.click(a, "规划设置");
        String text = Shots.allText(a.getWindow().getDecorView());
        int style = text.indexOf("安排风格");
        assertTrue(text, style >= 0 && style < text.indexOf("社群点数"));
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

        Shots.click(a, "尽量都体验");
        assertEquals(saves + 1, api.calls.size());
        assertEquals(Profile.Style.BREADTH, api.last().profile.style);
    }

    @Test
    public void explainPageSaysWhatTheStyleDoes() {
        assertEquals("默认「尽量都体验」：先把每个社群都解锁、都往上提，再冲 MAX；想集中冲 MAX，可在「规划设置」里改成「尽量多 MAX」。",
            InfoPages.styleLine(new Profile()));
        Profile max = new Profile();
        max.style = Profile.Style.MAX;
        assertEquals("你选了「尽量多 MAX」：集中时间把尽量多的社群升到 MAX；可在「规划设置」里改回「尽量都体验」。",
            InfoPages.styleLine(max));

        HomeActivity a = homeOn("me");
        Shots.click(a, "规划说明");
        assertTrue(Shots.has(a, InfoPages.styleLine(new Profile())));
        Shots.shot(a, "home_explain_style");

        api.snap.profile.style = Profile.Style.MAX;
        HomeActivity b = homeOn("me");
        View page = InfoPages.explain(b);
        assertTrue(Shots.allText(page).contains(InfoPages.styleLine(max)));
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
    public void deepChangesToggleSavesTheChoiceAndRestoresItWhenSettingsReopen() {
        HomeActivity a = homeOn("me");
        Shots.click(a, "规划设置");
        ToggleRow row = (ToggleRow) Shots.clickTarget(Shots.find(a, "深度修改"));
        assertFalse(row.isChecked());
        assertTrue(Shots.has(a, "每次修改后最多计算 12 秒"));
        long revision = api.snap.profileRevision;
        Shots.click(a, "深度修改");
        assertEquals("saveProfile", api.last().method);
        assertEquals(revision, api.last().expectedRevision);
        assertTrue(api.last().profile.deepChanges);
        assertTrue(api.snap.profile.deepChanges);

        a.popPage();
        Shots.click(a, "规划设置");
        row = (ToggleRow) Shots.clickTarget(Shots.find(a, "深度修改"));
        assertTrue(row.isChecked());
        Shots.click(a, "深度修改");
        assertFalse(api.last().profile.deepChanges);
        assertEquals("a preference change does not request a manual 30 s run", 0, api.deepReplanRequests);
        assertEquals(0, api.fullReplanRequests);
    }
}
