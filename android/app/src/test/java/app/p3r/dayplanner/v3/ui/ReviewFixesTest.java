package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;
import java.util.List;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * Regression tests for the final review findings of the ui module (F3, F4, UI-1…UI-7, COPY-*):
 * each test names the finding it covers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReviewFixesTest extends UiTestBase {
    private static final int NOW = Fixtures.SLOT_NOW;

    private HomeActivity homeOn(String tab) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, tab);
        return home(intent);
    }

    private static void jumpTo(StateActivity a, StateSteps.Page page) {
        for (int i = 0; i < 14 && a.currentPage() != page; i++) {
            Shots.click(a, "下一步");
        }
        assertEquals(page, a.currentPage());
    }

    private static String text(android.app.Activity a) {
        return Shots.allText(a.getWindow().getDecorView());
    }

    // ------------------------------------------------------------------ F3: episodes already watched

    @Test
    public void f3_optingInMidGameSavesWatchedEpisodesWithTheProfile() {
        // The checkpoint only changes the episodes: a recorded 没救出 stays as recorded.
        api.snap.observed.rescueFailed = 2L;
        HomeActivity a = homeOn("links");
        Shots.click(a, "真田明彦 · 共 3 话，已关闭");
        assertTrue(a.sheets().isShowing());
        assertTrue(Shots.has(a, "羁绊剧情 · 真田明彦"));
        assertTrue(Shots.has(a, "已经看了几话？"));
        assertTrue(Shots.has(a, "全部 3 话"));
        assertTrue(api.calls.isEmpty());
        Shots.shot(a, "home_links_episode_watched");
        Shots.click(a, "真田明彦：已看 2 话");
        FakePlannerApi.Call call = api.last();
        assertEquals("saveCheckpoint", call.method);
        assertEquals(Fixtures.STATE_REV, call.expectedRevision);
        assertEquals(2, call.state.episodes[1]);
        assertEquals("other tracks keep their count", 1, call.state.episodes[0]);
        assertEquals(api.snap.observed.slot, call.state.slot);
        assertEquals(2L, call.state.rescueFailed);
        assertNotNull(call.profile);
        assertTrue(call.profile.wantsEpisodes("akihiko"));
        assertTrue(call.profile.wantsEpisodes("junpei"));
        assertFalse(a.sheets().isShowing());
        assertEquals("已记录：羁绊剧情 · 真田明彦 · 已看 2 话", a.undoBar().messageText().toString());
        assertTrue(Shots.has(a, "真田明彦 · 共 3 话，已开启"));
    }

    @Test
    public void f3_closingTheQuestionLeavesTheTrackOff() {
        HomeActivity a = homeOn("links");
        Shots.click(a, "真田明彦 · 共 3 话，已关闭");
        assertTrue(a.sheets().isShowing());
        a.onBackPressed();
        Shots.idle();
        assertFalse(a.sheets().isShowing());
        assertTrue(api.calls.isEmpty());
        assertTrue(Shots.has(a, "真田明彦 · 共 3 话，已关闭"));
    }

    @Test
    public void f3_beforeOnboardingOptingInOnlySavesTheProfile() {
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        HomeActivity a = homeOn("links");
        Shots.click(a, "真田明彦 · 共 3 话，已关闭");
        assertFalse(a.sheets().isShowing());
        assertEquals("saveProfile", api.last().method);
        assertTrue(api.last().profile.wantsEpisodes("akihiko"));
    }

    @Test
    public void f3_wizardAsksWatchedEpisodesOfOptedInTracks() {
        StateActivity a = stateController("edit").get();
        jumpTo(a, StateSteps.Page.EPISODES);
        assertTrue(Shots.has(a, "羁绊剧情已经看了几话？"));
        assertTrue(Shots.has(a, "伊织顺平 · 共 4 话"));
        assertFalse("only opted-in tracks", Shots.has(a, "真田明彦 · 共 3 话"));
        assertEquals(1, a.draft().episodesWatched(0));
        Shots.shot(a, "state_edit_episodes");
        Shots.click(a, "伊织顺平：已看 3 话");
        assertEquals(3, a.draft().episodesWatched(0));
        jumpTo(a, StateSteps.Page.CONFIRM);
        assertTrue(text(a).contains("羁绊剧情 · 伊织顺平"));
        assertTrue(text(a).contains("已看 3 话"));
        Shots.shot(a, "state_edit_episodes_confirm");
        Shots.click(a, "保存并重新安排");
        FakePlannerApi.Call call = api.last();
        assertEquals("saveCheckpoint", call.method);
        assertEquals(3, call.state.episodes[0]);
        assertEquals(0, call.state.episodes[1]);
        assertNull(call.profile);
    }

    @Test
    public void f3_draftEpisodesBuildPersistAndDiff() {
        Rules rules = api.rules;
        SimState obs = api.snap.observed;
        obs.episodes = new int[0];
        StateDraft d = new StateDraft(StateDraft.Mode.SYNC, rules, obs, Fixtures.profileMid(), api.snap.plan, 12);
        assertEquals(Arrays.asList(0), d.episodeTracks());
        assertEquals(0, d.episodesWatched(0));
        assertEquals("untouched episodes keep the stored array", 0, d.build().episodes.length);
        d.setEpisodesWatched(0, 9);
        assertEquals("clamped to the track's count", 4, d.episodesWatched(0));
        SimState built = d.build();
        assertEquals(rules.episodes.length, built.episodes.length);
        assertEquals(4, built.episodes[0]);
        StateDraft.Change change = d.changes().get(d.changes().size() - 1);
        assertEquals("羁绊剧情 · 伊织顺平", change.labelZh);
        assertEquals("还没看", change.beforeZh);
        assertEquals("全部看完", change.afterZh);

        DraftStore store = new DraftStore(RuntimeEnvironment.getApplication());
        d.setEpisodesWatched(0, 2);
        store.save(d);
        StateDraft restored = new StateDraft(StateDraft.Mode.SYNC, rules, obs, Fixtures.profileMid(), api.snap.plan, 12);
        store.restore(restored);
        assertEquals(2, restored.episodesWatched(0));
        StateDraft rebased = new StateDraft(StateDraft.Mode.SYNC, rules, obs, Fixtures.profileMid(), api.snap.plan, 13);
        rebased.copyEditsFrom(restored);
        assertEquals(2, rebased.episodesWatched(0));
    }

    // ------------------------------------------------------------------ F4 / COPY-1 / COPY-2 / COPY-7

    @Test
    public void f4_episodeCopyIsNaturalChinese() {
        Rules rules = api.rules;
        Rules.Episode junpei = rules.episodes[0];
        Rules.Episode akihiko = rules.episodes[1];
        SimState obs = api.snap.observed;
        assertEquals("看完第 2 话了吗？", ResultActivity.episodeQuestion(junpei, obs));
        assertEquals("这一话看完了吗？", ResultActivity.episodeQuestion(null, obs));
        assertEquals("看完第 1 话了吗？", ResultActivity.episodeQuestion(akihiko, obs));
        assertEquals("羁绊剧情不加社群点数。每一话都有小奖励（能力值或顺平的能力），看完第 4 话可以拿到道具「顺平的棒球帽」。",
            ResultActivity.episodeNote(junpei));
        assertEquals("羁绊剧情不加社群点数。", ResultActivity.episodeNote(akihiko));
        junpei.rewardZh = "每一话都有小奖励（能力值）";
        assertEquals("羁绊剧情不加社群点数。每一话都有小奖励（能力值）。", ResultActivity.episodeNote(junpei));
        for (String s : new String[]{ResultActivity.episodeNote(junpei), LinksPage.episodeSummary(junpei, 1)}) {
            assertFalse(s, s.contains("。。"));
            assertFalse(s, s.contains("看完能拿到每"));
            assertFalse(s, s.contains("看完全部能拿到"));
        }
        assertEquals("白天 · 5月12日–1月29日能看 · 已看 1 话\n每一话都有小奖励（能力值）。",
            LinksPage.episodeSummary(junpei, 1));
        assertEquals("晚上 · 6月2日–1月28日能看", LinksPage.episodeSummary(akihiko, 0));

        assertEquals("", Zh.sentence(null));
        assertEquals("", Zh.sentence("  "));
        assertEquals("已经说好了。", Zh.sentence("已经说好了"));
        assertEquals("已经说好了。", Zh.sentence(" 已经说好了。。 "));
        assertEquals("真的吗？", Zh.sentence("真的吗？"));
        assertEquals("已经说好了", Zh.clause("已经说好了。"));
    }

    // ------------------------------------------------------------------ UI-1: 撤销 per HistoryItem.undoable

    @Test
    public void ui1_historyOffersUndoForEveryUndoableRecord() {
        int slot = api.snap.observed.slot;
        api.history.add(0, FakePlannerApi.item(100, slot - 1, "tower_fix",
            "10月20日 晚上 · 修改探索记录 · 到达 96F · 余额 19,000 日元", false, true));
        api.history.add(1, FakePlannerApi.item(99, slot - 2, "skip", "10月21日 放学后 · 跳过 · 剧情占用了", false, true));
        HomeActivity a = homeOn("me");
        Shots.click(a, "记录历史");
        View page = a.getWindow().getDecorView();
        for (String row : new String[]{"10月21日 晚上 · 在宿舍房间学习 · 学力 +2", "10月20日 放学后 · 皇帝 RANK 6",
            "10月21日 放学后 · 跳过 · 剧情占用了", "10月20日 晚上 · 修改探索记录 · 到达 96F · 余额 19,000 日元",
            "10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元"}) {
            assertTrue(row, Shots.has(a, "撤销这条记录：" + row));
        }
        assertFalse(Shots.has(a, "修改这条记录：10月20日 晚上 · 修改探索记录 · 到达 96F · 余额 19,000 日元"));
        assertFalse(Shots.has(a, "修改这条记录：10月21日 放学后 · 跳过 · 剧情占用了"));
        // Only the row from before the latest 同步现状 says so.
        assertEquals(1, Shots.findAll(page, "同步现状之前的记录").size());
        Shots.shot(a, "home_history_undo_all");
        Shots.click(a, "撤销这条记录：10月21日 放学后 · 跳过 · 剧情占用了");
        Shots.clickInDialog("撤销");
        assertEquals("voidEvent", api.last().method);
        assertEquals(99, api.last().targetSeq);
    }

    // ------------------------------------------------------------------ UI-2: queue after 做不了

    @Test
    public void ui2_queueReturnsToTheResultPanelAfterCannot() {
        ResultActivity a = result(NOW + 2, "meet:lovers", Intents.MODE_RESULT);
        Shots.click(a, "逐项核对");
        Shots.click(a, "做不了");
        assertTrue(Shots.has(a, "这个时段没能照安排做？"));
        Shots.click(a, "对方不在");
        assertEquals(Outcome.Code.SKIPPED, api.last().outcome.code);
        assertFalse(finishing(a));
        // Item 2 opens on its own panel, not on the reasons.
        assertFalse(Shots.has(a, "这个时段没能照安排做？"));
        assertTrue(Shots.has(a, "完成了，和预计一样"));
        assertTrue(Shots.has(a, "补记 2/3"));
        Shots.shot(a, "result_catchup_queue_after_cannot");
        Shots.click(a, "完成了，和预计一样");
        assertTrue(Shots.has(a, "见面了"));
        Shots.click(a, "见面了");
        assertTrue(finishing(a));
        assertEquals(3, api.callsOf("recordOutcome").size());
    }

    @Test
    public void ui2_excludingAnActivityKeepsTheQueueGoing() {
        ResultActivity a = result(NOW + 2, "meet:lovers", Intents.MODE_RESULT);
        Shots.click(a, "逐项核对");
        Shots.click(a, "升到 RANK 7 了");
        Shots.click(a, "做不了");
        Shots.click(a, "我不想做");
        Shots.click(a, "以后不再安排");
        assertEquals("saveProfile", api.last().method);
        assertFalse(finishing(a));
        assertTrue(Shots.has(a, "见面了"));
    }

    // ------------------------------------------------------------------ UI-3: 顺便 side steps before 完成

    @Test
    public void ui3_doneOnAJobWithSideStepsOpensTheSheet() {
        api.snap.observed.slot = NOW + 3;
        HomeActivity a = home();
        assertTrue(Shots.has(a, "顺便：在商城 2楼借给田中社长 5,000 日元"));
        Shots.click(a, "完成 ✓");
        assertTrue("nothing recorded without asking", api.calls.isEmpty());
        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(ResultActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(Intents.MODE_RESULT, next.getStringExtra(Intents.EXTRA_MODE));
        assertEquals("act:job_chagall", next.getStringExtra(Intents.EXTRA_ACTION));

        a.openDetail(NOW + 3);
        View done = Shots.find(a.sheets(), "完成 ✓");
        assertTrue(Shots.clickTarget(done).performClick());
        Shots.idle();
        assertTrue(api.calls.isEmpty());
        assertEquals(ResultActivity.class.getName(), shadowOf(a).getNextStartedActivity().getComponent().getClassName());
    }

    @Test
    public void ui3_jobSheetRecordsSkippedSideSteps() {
        api.snap.observed.slot = NOW + 3;
        ResultActivity a = result(NOW + 3, "act:job_chagall", Intents.MODE_RESULT);
        assertNotNull(a.sideRow());
        assertTrue(a.sideRow().isChecked());
        a.sideRow().setChecked(false);
        Shots.click(a, "完成了，和预计一样");
        assertFalse(api.last().outcome.sideDone);
        assertEquals("咖啡店「夏卡尔」打工 · 顺便的事没做", UndoCenter.take().summaryZh);
    }

    @Test
    public void ui3_freeSlotAsksAboutItsSideSteps() {
        int slot = Rules.slot(Fixtures.day(10, 25), true);
        api.snap.observed.slot = slot;
        Plan.Step free = api.snap.plan.at(slot);
        free.side.add("unlock:tower:0");
        free.sideZh.add("顺便：在后巷和无达搭话");
        HomeActivity home = home();
        assertTrue(Shots.has(home, "顺便：在后巷和无达搭话"));
        Shots.click(home, "完成 ✓");
        assertTrue("the side step is asked about first", api.calls.isEmpty());
        assertEquals(ResultActivity.class.getName(), shadowOf(home).getNextStartedActivity().getComponent().getClassName());

        ResultActivity a = result(slot, "free", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "顺便：在后巷和无达搭话"));
        assertNotNull(a.sideRow());
        Shots.shot(a, "result_free_side");
        a.sideRow().setChecked(false);
        Shots.click(a, "继续");
        assertEquals("free", api.last().actionKey);
        assertFalse(api.last().outcome.sideDone);
    }

    // ------------------------------------------------------------------ UI-4: 修改 prefills the record

    @Test
    public void ui4_correctionPrefillsTheRecordsOwnValues() {
        PlannerApi.HistoryItem tower = api.history.get(1);
        assertEquals(97, tower.seq);
        tower.tower = FakePlannerApi.towerValues(tower.slot, 3, 80, false, 1_200, 50);
        assertEquals(96, api.snap.observed.floor);
        HomeActivity a = homeOn("me");
        Shots.click(a, "记录历史");
        Shots.click(a, "修改这条记录：10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元");
        TowerPanel panel = findPanel(a.sheets());
        assertNotNull(panel);
        assertEquals("the record's floor, not today's", 80, panel.floorStepper().getValue());
        assertEquals("这次多了多少", 1, panel.cashModeControl().getSelected());
        assertEquals("改为：这次 +1,200 日元", panel.previewText());
        Shots.shot(a, "home_history_correct_tower_prefilled");
        Shots.click(a, "保存修改");
        FakePlannerApi.Call call = api.last();
        assertEquals("correctTower", call.method);
        assertEquals(97, call.targetSeq);
        assertEquals(80, call.tower.floor);
        assertFalse(call.tower.balanceMode);
        assertEquals(1_200, call.tower.amount);
        assertEquals(Integer.valueOf(50), call.tower.level);
        assertEquals(3, call.tower.block);
    }

    @Test
    public void ui4_noCorrectionWithoutTheRecordsValues() {
        api.history.get(1).tower = null;
        HomeActivity a = homeOn("me");
        Shots.click(a, "记录历史");
        assertFalse(Shots.has(a, "修改这条记录：10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元"));
        assertTrue(Shots.has(a, "撤销这条记录：10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元"));
    }

    // ------------------------------------------------------------------ UI-5: unreadable records

    private void breakTheStore() {
        api.rules = null;
        api.snap.observed = null;
        api.snap.plan = null;
        api.snap.planCurrent = false;
        api.snap.profile = new Profile();
        api.snap.planErrorZh = "读取记录失败了，请重新打开应用";
    }

    @Test
    public void ui5_homeShowsARetryStateOnEveryTabInsteadOfCrashing() {
        breakTheStore();
        HomeActivity a = home();
        assertTrue(a.showingLoadError());
        // The copy matches the button under it (the service's own 「请重新打开应用」 is for the widget).
        assertTrue(Shots.has(a, "读取记录失败了。点「重试」再试一次，还不行就重新打开应用。"));
        assertFalse(Shots.has(a, "开始设置"));
        Shots.shot(a, "home_load_failed");
        for (int tab = 0; tab < HomeActivity.TAB_KEYS.length; tab++) {
            a.selectTab(tab);
            Shots.idle();
            assertTrue(a.showingLoadError());
            assertTrue(Shots.has(a, "暂时读不到记录"));
            assertFalse(Shots.has(a, "开始设置"));
        }
        // The data comes back: 重试 shows the pages again.
        api.rules = Fixtures.rules();
        api.snap.observed = Fixtures.observedMid(api.rules);
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        api.snap.planCurrent = true;
        api.snap.profile = Fixtures.profileMid();
        api.snap.planErrorZh = null;
        a.selectTab(HomeActivity.TAB_TODAY);
        Shots.click(a, "重试");
        assertFalse(a.showingLoadError());
        assertTrue(Shots.has(a, "皇帝 · 小田桐秀利"));
    }

    @Test
    public void ui5_linksPageSurvivesMissingRules() {
        HomeActivity a = homeOn("links");
        PlannerApi.Snapshot snap = api.snapshot();
        LinksPage page = new LinksPage(a);
        page.bind(new UiData(null, snap, null, false, 0));
        assertTrue(Shots.allText(page.view()).contains("游戏资料暂时读不出来"));
    }

    @Test
    public void ui5_resultSheetSaysTheRecordsAreUnreadable() {
        breakTheStore();
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "暂时读不到记录"));
        assertTrue(Shots.has(a, "读取记录失败了。点「重试」再试一次，还不行就重新打开应用。"));
        assertTrue(Shots.has(a, "重试"));
        assertFalse(Shots.has(a, "还没有设置游戏进度"));
        assertFalse(Shots.has(a, "去设置"));
        Shots.shot(a, "result_load_failed");
        Shots.click(a, "重试");
        assertTrue(Shots.has(a, "暂时读不到记录"));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void ui5_wizardDoesNotStartOverUnreadableRecords() {
        breakTheStore();
        StateActivity a = stateController("sync").get();
        assertTrue(Shots.has(a, "暂时读不到记录"));
        assertNull(a.currentPage());
        Shots.shot(a, "state_load_failed");
        Shots.click(a, "重试");
        assertTrue(Shots.has(a, "暂时读不到记录"));
        assertTrue(api.calls.isEmpty());
    }

    // ------------------------------------------------------------------ UI-6: 已经玩到这里 on a story day

    private int storyDayAfterTwoOpenSlots() {
        api.snap.observed.slot = Rules.slot(Fixtures.day(11, 2), true);
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        int target = Rules.slot(Fixtures.day(11, 4), false);
        assertEquals("story", api.rules.lockOf(target));
        return target;
    }

    @Test
    public void ui6_reachedHereOnAStoryDayEndsCleanly() {
        int target = storyDayAfterTwoOpenSlots();
        ResultActivity a = resultController(target, api.snap.plan.at(target).action, Intents.MODE_RESULT, true).get();
        assertTrue(Shots.has(a, "前面还有 2 项没记录"));
        Shots.click(a, "都照安排做完了");
        assertEquals(2, api.callsOf("recordOutcome").size());
        assertFalse(Shots.has(a, "这一项已经记录过了"));
        assertTrue(Shots.has(a, "已补记前面的安排。"));
        assertTrue(Shots.has(a, "这段时间由剧情安排，不用记录。游戏里过完后，接着照「今天」的下一项玩就行。"));
        Shots.shot(a, "result_catchup_story_day");
        Shots.click(a, "好的");
        assertTrue(finishing(a));
    }

    @Test
    public void ui6_queueTowardAStoryDayEndsCleanly() {
        int target = storyDayAfterTwoOpenSlots();
        ResultActivity a = resultController(target, api.snap.plan.at(target).action, Intents.MODE_RESULT, true).get();
        Shots.click(a, "逐项核对");
        for (int i = 0; i < 2; i++) {
            Shots.click(a, Shots.has(a, "没去成") ? "没去成" : "做不了");
            Shots.click(a, "对方不在");
        }
        assertEquals(2, api.callsOf("recordOutcome").size());
        assertFalse(Shots.has(a, "这一项已经记录过了"));
        assertTrue(Shots.has(a, "这段时间由剧情安排，不用记录。游戏里过完后，接着照「今天」的下一项玩就行。"));
    }

    @Test
    public void ui6_anEarlierSlotWithoutCatchUpStillSaysAlreadyRecorded() {
        api.snap.observed.slot = NOW + 1;
        ResultActivity a = resultController(NOW, "meet:emperor", Intents.MODE_RESULT, true).get();
        assertTrue(Shots.has(a, "这一项已经记录过了"));
        assertFalse(Shots.has(a, "已补记前面的安排。"));
    }

    // ------------------------------------------------------------------ UI-7 / audit I3(2): recorded 没救出

    private void maikoLostByReplay() {
        api.snap.observed.slot = Rules.slot(Fixtures.day(11, 6), false);
        api.snap.observed.rescueFailed = 2L;
        api.snap.observed.rank[12] = 2;
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
    }

    @Test
    public void ui7_recordedFailureIsPreselectedAndKeptUntilChanged() {
        maikoLostByReplay();
        SimState obs = api.snap.observed;
        StateDraft d = new StateDraft(StateDraft.Mode.EDIT, api.rules, obs, Fixtures.profileMid(), api.snap.plan, 12);
        assertEquals(StateDraft.RESCUE_FAILED, d.rescueAnswer(1));
        assertTrue(d.recordSaysFailed(1));
        assertEquals(2L, d.build().rescueFailed & 2L);
        // An unrelated edit (倒悬者's RANK) keeps the recorded result: the link stays lost.
        d.setRank(12, 3);
        assertEquals(2L, d.build().rescueFailed & 2L);
        assertEquals(0L, d.build().rescued & 2L);
        for (StateDraft.Change c : d.changes()) {
            assertFalse("an unchanged rescue is not listed", c.labelZh.equals("救出舞子"));
        }
        // Only the player's own change replaces it.
        d.setRescue(1, StateDraft.RESCUE_DONE);
        assertFalse(d.recordSaysFailed(1));
        assertEquals(0L, d.build().rescueFailed & 2L);
        assertEquals(2L, d.build().rescued & 2L);
        boolean listed = false;
        for (StateDraft.Change c : d.changes()) {
            if (c.labelZh.equals("救出舞子")) {
                listed = true;
                assertEquals("没救出", c.beforeZh);
                assertEquals("救出了", c.afterZh);
            }
        }
        assertTrue("the confirm page shows the rescue change", listed);
    }

    @Test
    public void ui7_recordedFailureWhileTheWindowIsOpenIsNotKept() {
        // Moving the date back into the window: a failure cannot have happened yet.
        maikoLostByReplay();
        StateDraft d = new StateDraft(StateDraft.Mode.EDIT, api.rules, api.snap.observed, Fixtures.profileMid(),
            api.snap.plan, 12);
        d.setSlot(Rules.slot(Fixtures.day(10, 30), false));
        assertTrue(d.rescueOpen(1));
        assertFalse(d.recordSaysFailed(1));
        assertEquals(0L, d.build().rescueFailed & 2L);
    }

    @Test
    public void ui7_wizardPreselectsTheRecordedFailureWithANote() {
        maikoLostByReplay();
        StateActivity a = stateController("edit").get();
        jumpTo(a, StateSteps.Page.TOWER);
        assertTrue(Shots.has(a, StateDraft.RECORDED_FAILURE_NOTE));
        assertTrue("没救出 is preselected", Shots.find(a, "舞子：没救出").isSelected());
        assertFalse(Shots.find(a, "舞子：救出了").isSelected());
        Shots.shot(a, "state_edit_rescue_recorded_failure");
        // Another edit saves the record with the failure still in it.
        a.draft().setCash(30_000);
        jumpTo(a, StateSteps.Page.CONFIRM);
        Shots.click(a, "保存并重新安排");
        assertEquals("saveCheckpoint", api.last().method);
        assertEquals(30_000, api.last().state.cash);
        assertEquals(2L, api.last().state.rescueFailed & 2L);

        api.calls.clear();
        StateActivity b = stateController("edit").get();
        jumpTo(b, StateSteps.Page.TOWER);
        Shots.click(b, "舞子：救出了");
        jumpTo(b, StateSteps.Page.CONFIRM);
        Shots.click(b, "保存并重新安排");
        assertEquals(0L, api.last().state.rescueFailed & 2L);
        assertEquals(2L, api.last().state.rescued & 2L);
    }

    // ------------------------------------------------------------------ COPY-3: MAX

    @Test
    public void copy3_rankTenReadsMax() {
        api.snap.observed.rank[4] = 9;
        api.snap.plan.steps.set(0, Fixtures.social(api.rules, NOW, "emperor", 9, 10, true, true));
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "升到 MAX 了吗？"));
        assertTrue(Shots.has(a, "升到 MAX 了"));
        assertTrue(Shots.has(a, "现在是 RANK 9，这次见面预计会升级。"));
        assertFalse(text(a).contains("RANK MAX"));
        Shots.shot(a, "result_social_rankup_max");
        Shots.click(a, "升到 MAX 了");
        assertEquals(10, api.last().outcome.rank);
        assertEquals("皇帝 升到 MAX", UndoCenter.take().summaryZh);
        assertEquals("皇帝 升到 MAX", Outcomes.summary(api.rules, api.snap.plan.at(NOW), Outcomes.rankUp(10)));
        assertEquals("皇帝 RANK 7", Outcomes.summary(api.rules, api.snap.plan.at(NOW), Outcomes.rankUp(7)));
    }

    // ------------------------------------------------------------------ COPY-5: 参考回答

    @Test
    public void copy5_answerLineShowsOnlyCleanGuideAnswers() {
        assertEquals("参考回答：我喜欢风花（1），恋人线",
            Dialogue.line(Arrays.asList("对话选择：任意/任意/我喜欢风花（1），恋人线/任意")));
        assertEquals("参考回答：随便问吧（1） / 产生恋爱感情了（1） / 随时欢迎千寻找我倾诉（1） / 握住她的手（1）",
            Dialogue.line(Arrays.asList("对话选择：随便问吧（1）/产生恋爱感情了（1）/随时欢迎千寻找我倾诉（1）/握住她的手（1）",
                "第二次选择“产生恋爱感情了（1）”会进入恋爱线；选第二项不会进入", "最后选择第二项亲上去，关系会破裂")));
        assertEquals("参考回答：突然这是怎么了？（1）", Dialogue.line(Arrays.asList("前3次对话选择：第1项/任意/任意",
            "接着选择：突然这是怎么了？（1）", "最后一项：选1进入恋人线，选2做朋友")));
        assertEquals("参考回答：好（1）", Dialogue.line(Arrays.asList("对话选择：任意/任意（前面选择第一个选项才有这个对话）/好（1）/任意")));
        assertEquals("参考回答：那你的膝盖怎么办？（2）", Dialogue.line(Arrays.asList("第2项", "那你的膝盖怎么办？（2）")));
        assertEquals("参考回答：不能进或者保密 / 成熟的好或者无所谓", Dialogue.line(Arrays.asList("不能进或者保密", "成熟的好或者无所谓")));
        assertEquals(Dialogue.ANY, Dialogue.line(Arrays.asList("任意")));
        assertNull("bare ordinals only → fallback", Dialogue.line(Arrays.asList("第1项", "第2项")));
        assertNull(Dialogue.line(Arrays.asList("任意", "除第1项外任选")));

        Plan.Step emperor = api.snap.plan.at(NOW);
        assertEquals("参考回答：我会帮你的 / 这样的规矩没有意义", Dialogue.line(api.rules, emperor));
        api.rules.links[4].dialogue.put(7, Arrays.asList("任意", "第1项"));
        assertEquals(Dialogue.FALLBACK, Dialogue.line(api.rules, emperor));
    }

    // ------------------------------------------------------------------ COPY-6: reserved slot note

    @Test
    public void copy6_heroShowsTheReservedSlotNote() {
        Plan.Step reserved = new Plan.Step();
        reserved.action = "locked:story";
        reserved.kind = "locked";
        reserved.titleZh = "可能有剧情（先空出）";
        reserved.subtitleZh = "如果之前把「白色切花」送给了顺平，这天放学后会有剧情，所以先空出来";
        assertEquals("如果之前把「白色切花」送给了顺平，这天放学后会有剧情，所以先空出来。游戏里过完后点「继续」。",
            TodayPage.lockedNote(reserved));
        Plan.Step plain = new Plan.Step();
        plain.kind = "locked";
        assertEquals("这段时间不能自由行动。游戏里过完后点「继续」。", TodayPage.lockedNote(plain));
        plain.subtitleZh = "这段时间不能自由行动。";
        assertEquals("这段时间不能自由行动。游戏里过完后点「继续」。", TodayPage.lockedNote(plain));

        reserved.slot = NOW;
        api.snap.plan.steps.set(0, reserved);
        HomeActivity a = home();
        assertTrue(Shots.has(a, "可能有剧情（先空出）"));
        assertTrue(Shots.has(a, "如果之前把「白色切花」送给了顺平，这天放学后会有剧情，所以先空出来。游戏里过完后点「继续」。"));
        Shots.shot(a, "home_today_reserved_slot");
    }

    // ------------------------------------------------------------------ remaining copy

    @Test
    public void copy_menusAndBannersUseTheGlossaryTerms() {
        HomeActivity a = homeOn("me");
        assertTrue(Shots.has(a, "修改进度"));
        assertFalse(Shots.has(a, "逐项修改进度"));
        assertTrue(Shots.has(a, "安排"));
        assertFalse("规划 only in 规划设置 / 规划说明", Shots.has(a, "规划"));
        assertTrue(Shots.has(a, "规划设置"));
        assertFalse(text(a).contains("进塔"));
        Shots.shot(a, "home_me_terms");
        String settings = Shots.allText(SettingsPage.build(a));
        assertFalse(settings, settings.contains("进塔"));
        assertTrue(settings.contains("按区块设置想去几次、最多几次（包括救失踪者）"));
        assertTrue(settings.contains("12月24日的邀约"));
        assertTrue(settings.contains("在桐叶购物中心的放纵俱乐部买结缘网站的字条"));
        assertTrue(settings.contains("每次和目标社群的人见面前，会提醒你带哪个阿尔卡那的人格面具。"));

        assertEquals("安排好了，一直排到1月31日", PlanDiff.summary(api.rules, null, api.snap.plan, NOW));
        List<String> global = Assumptions.global(Fixtures.profileMid());
        assertTrue(global.contains("12月24日按都婉拒了邀约安排，可在「规划设置 → 平安夜」里改。"));
        assertTrue(global.contains("1月21日放学后先空出来（送过顺平「白色切花」的话会有剧情）。"));
        assertEquals("假定这次探索能顺利救出失踪者。", Assumptions.describe("rescue"));
        for (String line : global) {
            assertFalse(line, line.contains("进塔") || line.contains("1月21日白天"));
        }
        assertTrue(SettingsPage.PERSONA_TEXT[1][1].contains("每次和目标社群的人见面前"));
    }

    @Test
    public void copy_onboardingPresetAndPersonaWording() {
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        StateActivity a = stateController("onboarding").get();
        assertTrue(Shots.has(a, "尽量全部升到 MAX"));
        assertFalse(Shots.has(a, "尽量全部 MAX"));
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.PERSONA, a.currentPage());
        assertTrue(text(a).contains("每次和目标社群的人见面前，会提醒你带哪个阿尔卡那的人格面具。"));
        assertFalse(text(a).contains("见目标前"));
        Shots.shot(a, "state_onboarding_persona_wording");
    }

    private static TowerPanel findPanel(View v) {
        if (v instanceof TowerPanel) {
            return (TowerPanel) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TowerPanel p = findPanel(g.getChildAt(i));
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }
}
