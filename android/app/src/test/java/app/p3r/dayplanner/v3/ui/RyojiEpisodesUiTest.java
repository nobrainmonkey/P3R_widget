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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;
import app.p3r.dayplanner.v3.service.PlannerApi;

/** Ryoji has five scenes, but only scenes 2 and 4 use a freely chosen calendar slot. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RyojiEpisodesUiTest extends UiTestBase {
    private Rules.Episode installRealRyoji(int month, int dom) throws Exception {
        api.rules = RulesLoader.parse(new String(Files.readAllBytes(
            Paths.get("src/main/assets/p3r/p3r_rules.json")), StandardCharsets.UTF_8));
        api.snap.observed = new SimState(api.rules.blocks.length, api.rules.activities.length);
        api.snap.observed.episodes = new int[api.rules.episodes.length];
        api.snap.observed.slot = Rules.slot(Fixtures.day(month, dom), false);
        api.snap.profile = new Profile();
        api.snap.plan = null;
        for (Rules.Episode track : api.rules.episodes) if ("ryoji".equals(track.id)) return track;
        throw new AssertionError("Ryoji track missing");
    }

    @Test public void optingInMidStoryRecordsTheRealSceneWithoutCountingAutomaticScenes() throws Exception {
        Rules.Episode ryoji = installRealRyoji(11, 13);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        HomeActivity activity = home(intent);
        Shots.click(activity, "羁绊剧情");
        assertTrue(Shots.has(activity, "0/2次"));
        Shots.click(activity, "望月绫时 · 需赴约 2 次，已关闭");
        assertTrue(Shots.has(activity, "已经看过哪一话？"));
        assertTrue(Shots.has(activity, "望月绫时：尚未赴约"));
        assertTrue(Shots.has(activity, "望月绫时：已看第 4 话"));
        assertFalse(Shots.has(activity, "全部 2 话"));

        Shots.click(activity, "望月绫时：已看第 2 话");
        assertEquals(1, api.last().state.episodes[ryoji.idx]);
        assertTrue(api.last().profile.wantsEpisodes("ryoji"));
        assertEquals("已记录：羁绊剧情 · 望月绫时 · 已看第 2 话", activity.undoBar().messageText().toString());
        assertTrue(Shots.has(activity, "1/2次"));
    }

    @Test public void resultAndHistoryKeepTheGameSceneNumbersForBothMeetings() throws Exception {
        Rules.Episode ryoji = installRealRyoji(11, 12);
        api.snap.profile.episodeTracks = new String[]{"ryoji"};
        PlannerApi.OutcomeCorrection record = new PlannerApi.OutcomeCorrection();
        record.actionKey = "episode:ryoji";
        record.outcome = new Outcome();
        record.beforeState = api.snap.observed;
        record.slot = record.beforeState.slot;
        assertEquals("看完第 2 话了吗？", ResultActivity.episodeQuestion(ryoji, record.beforeState));
        assertEquals("羁绊剧情 · 望月绫时（第 2 话）",
            HistoricalOutcome.step(api.rules, api.snap.profile, record).titleZh);

        record.beforeState = record.beforeState.copy();
        record.beforeState.slot = Rules.slot(Fixtures.day(12, 1), false);
        record.beforeState.episodes[ryoji.idx] = 1;
        record.slot = record.beforeState.slot;
        assertEquals("看完第 4 话了吗？", ResultActivity.episodeQuestion(ryoji, record.beforeState));
        assertEquals("羁绊剧情 · 望月绫时（第 4 话）",
            HistoricalOutcome.step(api.rules, api.snap.profile, record).titleZh);
    }

    @Test public void finishingBothMeetingsDoesNotClaimTheDecemberRewardIsAlreadyUnlocked() throws Exception {
        Rules.Episode ryoji = installRealRyoji(12, 1);
        assertEquals("已看第 4 话", StateDraft.episodesText(ryoji, 2));
        assertFalse(StateDraft.episodesText(ryoji, 2).contains("全部看完"));
        assertTrue(LinksPage.rewardLine(ryoji), LinksPage.rewardLine(ryoji).contains("12月31日"));
        assertTrue(LinksPage.rewardLine(ryoji), LinksPage.rewardLine(ryoji).contains("萨图尔努斯"));
    }
}
