package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Quick result sheet: one render per panel kind and mode, and the exact API call + Outcome each
 * button records (including stale plans, CAS rejections and catch-up).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ResultSheetTest extends UiTestBase {
    private static final int NOW = Fixtures.SLOT_NOW;

    // ------------------------------------------------------------------ social

    @Test
    public void actualInvitationHasItsOwnPanelWithoutRankUpOrRankSceneAnswers() {
        Plan.Step invite = new Plan.Step();
        invite.slot = NOW;
        invite.action = "invite:magician";
        invite.kind = "invite";
        invite.entity = "magician";
        invite.titleZh = "魔术师 · 友近健二";
        invite.subtitleZh = "休息日邀约 · 收到短信时";
        invite.predicted.rankFrom = 3;
        invite.predicted.rankTo = 3;
        invite.predicted.certain = true;
        api.alternatives = java.util.List.of(invite);
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_OTHER);
        assertTrue(Shots.has(a, "休息日邀约 · 收到短信时"));
        Shots.click(a, "魔术师 · 友近健二");
        assertTrue(Shots.has(a, "赴约了吗？"));
        assertTrue(Shots.has(a, "休息日邀约"));
        assertFalse(Shots.has(a, "升到 RANK 4 了"));
        assertNull(Dialogue.line(api.rules, api.snap.profile, invite));
        Shots.click(a, ResultActivity.READY_CUE);
        Shots.click(a, "赴约了");
        assertEquals("invite:magician", api.last().actionKey);
        assertEquals(Outcome.Code.AS_PREDICTED, api.last().outcome.code);
        assertEquals(-1, api.last().outcome.rank);
        assertTrue(api.last().outcome.readyCue);
        assertEquals("魔术师 · 赴约了", UndoCenter.take().summaryZh);
    }

    @Test
    public void rankUpAsPredictedRecordsRank7() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "升到 RANK 7 了"));
        Shots.shot(a, "result_social_rankup");
        Shots.click(a, "升到 RANK 7 了");
        FakePlannerApi.Call call = api.last();
        assertEquals("recordOutcome", call.method);
        assertEquals(NOW, call.slot);
        assertEquals("meet:emperor", call.actionKey);
        assertEquals(Fixtures.STATE_REV, call.expectedRevision);
        assertEquals(Outcome.Code.AS_PREDICTED, call.outcome.code);
        assertEquals(7, call.outcome.rank);
        assertTrue(call.outcome.sideDone);
        assertTrue(finishing(a));
        UndoCenter.Recorded recorded = UndoCenter.take();
        assertNotNull(recorded);
        assertEquals("皇帝 RANK 7", recorded.summaryZh);
    }

    @Test
    public void sideStepsOnTheSheetWithoutThePreActionInfo() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        // The step is done already: no persona reminder, venue, answers or how-to before the answer.
        String all = Shots.allText(a.getWindow().getDecorView());
        assertFalse(all, all.contains("记得带上「皇帝」阿尔卡那的人格面具"));
        assertFalse(all, all.contains("月光馆学园 学生会室"));
        assertFalse(all, all.contains("回答："));
        assertFalse(all, all.contains("怎么做"));
        assertFalse(all, all.contains("现在是 RANK"));
        assertTrue(Shots.has(a, "RANK 6 → 7"));
        assertTrue(Shots.has(a, "顺便：听西胁结子说起奇怪的和尚"));
        assertNotNull(a.sideRow());
        assertTrue(a.sideRow().isChecked());
        Shots.click(a, ResultActivity.SIDE_DONE);
        assertFalse(a.sideRow().isChecked());
        Shots.shot(a, "result_social_side_unchecked");
        Shots.click(a, "升到 RANK 7 了");
        assertFalse(api.last().outcome.sideDone);
        assertEquals("皇帝 RANK 7 · 顺便的事没做", UndoCenter.take().summaryZh);
    }

    @Test
    public void lagglingPlanRecordsTheRankThePlayerSaw() {
        // The plan still predicts 6 → 7, but the record already says RANK 5 (a replan is running).
        api.snap.planCurrent = false;
        api.snap.observed.rank[4] = 5;
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "升到 RANK 6 了"));
        Shots.click(a, "升到 RANK 6 了");
        Outcome o = api.last().outcome;
        assertEquals(Outcome.Code.RANK_UP, o.code);
        assertEquals(6, o.rank);
    }

    @Test
    @Config(fontScale = 1.3f)
    public void rankUpAtLargeFontScale() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        Shots.shot(a, "result_social_rankup_font130");
    }

    @Test
    public void noRankUpAsksForTheReadyCueNext() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        assertFalse("no switch before the answer", Shots.has(a, "游戏提示下次就能升级"));
        Shots.click(a, "没升级");
        assertTrue("nothing saved yet", api.calls.isEmpty());
        assertTrue(Shots.has(a, ResultActivity.CUE_QUESTION));
        Shots.shot(a, "result_social_no_rankup_cue");
        Shots.click(a, "提示了");
        Outcome o = api.last().outcome;
        assertEquals(Outcome.Code.NO_RANK_UP, o.code);
        assertEquals(6, o.rank);
        assertTrue(o.readyCue);
        assertTrue(finishing(a));
    }

    @Test
    public void noRankUpWithoutTheCueCarriesTheSideAnswer() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        Shots.click(a, ResultActivity.SIDE_DONE);
        Shots.click(a, "没升级");
        // Back returns to the rank-up panel with the answer kept.
        a.onBackPressed();
        Shots.idle();
        assertFalse(finishing(a));
        assertTrue(Shots.has(a, "升到 RANK 7 了"));
        assertFalse(a.sideRow().isChecked());
        Shots.click(a, "没升级");
        Shots.click(a, "没有");
        Outcome o = api.last().outcome;
        assertEquals(Outcome.Code.NO_RANK_UP, o.code);
        assertFalse(o.readyCue);
        assertFalse(o.sideDone);
        assertEquals("皇帝 · 没升级 · 顺便的事没做", UndoCenter.take().summaryZh);
    }

    @Test
    public void otherResultsExpandAndRecordHold() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        Shots.click(a, ResultActivity.OTHER_RESULTS);
        Shots.shot(a, "result_social_other");
        // No rank is preselected (a chip records on tap), and 「未解锁」 is never offered.
        for (int r = 5; r <= 8; r++) {
            assertFalse(Shots.find(a, "RANK " + r).isSelected());
        }
        assertFalse(Shots.has(a, "RANK 4"));
        assertFalse("没见到 is 做不了 → 对方不在", Shots.has(a, "没见到"));
        Shots.click(a, ResultActivity.HOLD);
        Outcome o = api.last().outcome;
        assertEquals(Outcome.Code.NOT_DONE, o.code);
        assertEquals(Outcomes.REASON_HOLD, o.reason);
    }

    @Test
    public void estimatedRankUpShowsEqualChoices() {
        int slot = Rules.slot(Fixtures.day(10, 24), false);
        advanceTo(slot);
        ResultActivity a = result(slot, "meet:sun", Intents.MODE_RESULT);
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("以游戏为准"));
        Shots.shot(a, "result_social_rankup_estimate");
        assertTrue(Shots.has(a, "没升级"));
        assertTrue(Shots.has(a, "升到 RANK 8 了"));
        assertTrue("one 预计 tag", Shots.has(a, "预计"));
        // "其他结果…" stays collapsed until asked for: its rank chips are not on screen.
        assertFalse(Shots.has(a, "RANK 9"));
    }

    @Test
    public void ordinaryMeetingUnexpectedRankUp() {
        int slot = Rules.slot(Fixtures.day(10, 23), false);
        advanceTo(slot);
        ResultActivity a = result(slot, "meet:lovers", Intents.MODE_RESULT);
        Shots.shot(a, "result_social_meet");
        Shots.click(a, "升到 RANK 5 了");
        Outcome o = api.last().outcome;
        assertEquals(Outcome.Code.RANK_UP, o.code);
        assertEquals(5, o.rank);
    }

    @Test
    public void ordinaryMeetingDone() {
        int slot = Rules.slot(Fixtures.day(10, 23), false);
        advanceTo(slot);
        ResultActivity a = result(slot, "meet:lovers", Intents.MODE_RESULT);
        Shots.click(a, "见面了");
        assertEquals(Outcome.Code.AS_PREDICTED, api.last().outcome.code);
        assertEquals(4, api.last().outcome.rank);
    }

    // ------------------------------------------------------------------ other kinds

    @Test
    public void unlockStep() {
        int slot = Rules.slot(Fixtures.day(10, 25), false);
        advanceTo(slot);
        ResultActivity a = result(slot, "unlock:devil:1", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "这一步做完了吗？"));
        assertTrue(Shots.has(a, "第 2/3 步"));
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("解锁恶魔社群前的准备"));
        Shots.shot(a, "result_unlock");
        Shots.click(a, "没做完");
        assertEquals(Outcome.Code.NOT_DONE, api.last().outcome.code);
        assertEquals("unlock:devil:1", api.last().actionKey);
    }

    @Test
    public void statAsPredicted() {
        int slot = NOW + 1;
        advanceTo(slot);
        ResultActivity a = result(slot, "act:study_room", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "做完了吗？"));
        Shots.shot(a, "result_stat");
        Shots.click(a, "做完了");
        assertEquals(Outcome.Code.AS_PREDICTED, api.last().outcome.code);
        assertEquals("act:study_room", api.last().actionKey);
    }

    @Test
    public void statDifferentLevel() {
        int slot = NOW + 1;
        advanceTo(slot);
        ResultActivity a = result(slot, "act:study_room", Intents.MODE_RESULT);
        Shots.click(a, "结果不一样…");
        // One primary on screen: 保存 replaces 做完了.
        assertFalse(Shots.has(a, "做完了"));
        Shots.shot(a, "result_stat_different");
        Shots.click(a, "Lv.5");
        Shots.click(a, "保存");
        Outcome o = api.last().outcome;
        assertEquals(Outcome.Code.OTHER, o.code);
        assertEquals(5, o.statLv[0]);
        assertEquals(-1, o.cashBalance);
    }

    @Test
    public void jobShowsPayAndBalancePath() {
        int slot = Rules.slot(Fixtures.day(10, 23), true);
        advanceTo(slot);
        ResultActivity a = result(slot, "act:job_chagall", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "+3,000 日元 · 魅力 +1 勇气 +1"));
        assertFalse(Shots.has(a, "预计 +3,000 日元 · 魅力 +1 勇气 +1"));
        assertTrue(Shots.has(a, "顺便：在商城 2楼借给田中社长 5,000 日元"));
        Shots.shot(a, "result_job");
        Shots.click(a, "结果不一样…");
        // The field shows the forecast balance; tapped, the keypad opens inline (no dialog, no 确定).
        assertTrue(Shots.has(a, "打工后的现金，21,500 日元"));
        Shots.click(a, "打工后的现金");
        assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog());
        Shots.shot(a, "result_job_cashpad");
        Shots.click(a, "2");
        Shots.click(a, "1");
        Shots.click(a, "000");
        Shots.click(a, "保存");
        Outcome o = api.last().outcome;
        assertEquals(Outcome.Code.OTHER, o.code);
        assertEquals(21_000, o.cashBalance);
    }

    @Test
    public void towerBalanceModeRecordsOnce() {
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        advanceTo(slot);
        ResultActivity a = result(slot, "tower:3", Intents.MODE_RESULT);
        TowerPanel panel = a.towerPanel();
        assertNotNull(panel);
        assertTrue(Shots.has(a, "舞子救出来了吗？"));
        panel.floorStepper().step(+5);
        panel.setAmountForTest(25_050);
        Shots.shot(a, "result_tower");
        assertEquals("进塔前 18,500 → 出塔后 25,050 日元（+6,550）", panel.previewText());
        Shots.click(a, "救出了");
        Shots.click(a, "保存");
        FakePlannerApi.Call call = api.last();
        assertEquals("recordTower", call.method);
        assertTrue(call.tower.balanceMode);
        assertEquals(25_050, call.tower.amount);
        assertEquals(101, call.tower.floor);
        assertEquals(3, call.tower.block);
        assertEquals("maiko", call.tower.rescuedId);
        assertEquals(1, api.callsOf("recordTower").size());
    }

    @Test
    public void towerNetModeIsExclusive() {
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        advanceTo(slot);
        ResultActivity a = result(slot, "tower:3", Intents.MODE_RESULT);
        TowerPanel panel = a.towerPanel();
        Shots.click(a, "这次多了多少");
        panel.setAmountForTest(4_200);
        assertEquals("进塔前 18,500 → 出塔后 22,700 日元（+4,200）", panel.previewText());
        Shots.shot(a, "result_tower_net");
        Shots.click(a, "保存");
        assertFalse(api.last().tower.balanceMode);
        assertEquals(4_200, api.last().tower.amount);
    }

    @Test
    public void towerUnknownCashForcesBalance() {
        api.snap.observed.cashExact = false;
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        advanceTo(slot);
        ResultActivity a = result(slot, "tower:3", Intents.MODE_RESULT);
        TowerPanel panel = a.towerPanel();
        // Only the balance can be typed: the 这次多了多少 tab is not offered at all.
        assertFalse(panel.cashModeControl().isShown());
        assertFalse(Shots.has(a, "这次多了多少"));
        assertTrue(Shots.has(a, "出塔后的现金"));
        assertFalse(panel.saveButton().isEnabled());
        Shots.shot(a, "result_tower_unknown_cash");
    }

    @Test
    public void lockedContinue() {
        // The store never stops on a locked slot; this only checks the panel itself.
        api.strictSlots = false;
        api.snap.observed.slot = Rules.slot(Fixtures.day(11, 3), true);
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        ResultActivity a = result(api.snap.observed.slot, "locked:full_moon", Intents.MODE_RESULT);
        Shots.shot(a, "result_locked");
        Shots.click(a, "继续");
        assertEquals("locked:full_moon", api.last().actionKey);
        assertEquals(Outcome.Code.AS_PREDICTED, api.last().outcome.code);
    }

    @Test
    public void freeSlotContinues() {
        int slot = Rules.slot(Fixtures.day(10, 25), true);
        advanceTo(slot);
        ResultActivity a = result(slot, "free", Intents.MODE_RESULT);
        Shots.shot(a, "result_free");
        assertFalse("nothing was planned, nothing to fall through", Shots.has(a, TodayPage.CANNOT_LABEL));
        assertTrue(Shots.has(a, "做了别的…"));
        Shots.click(a, "继续");
        assertEquals("free", api.last().actionKey);
        assertEquals(Outcome.Code.AS_PREDICTED, api.last().outcome.code);
    }

    // ------------------------------------------------------------------ deviations

    @Test
    public void cannotRecordsSkipReason() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_CANNOT);
        assertTrue(Shots.has(a, "怎么没做成？"));
        // Only the reasons that fit a meeting.
        assertFalse(Shots.has(a, "没开门"));
        assertFalse(Shots.has(a, "钱不够"));
        assertTrue(Shots.has(a, ResultActivity.OTHER_INSTEAD));
        Shots.shot(a, "result_cannot");
        Shots.click(a, "对方不在");
        Outcome o = api.last().outcome;
        assertEquals(Outcome.Code.SKIPPED, o.code);
        assertEquals("absent", o.reason);
        assertEquals("meet:emperor", api.last().actionKey);
    }

    @Test
    public void unwillingOnActivityCanExcludeIt() {
        int slot = NOW + 1;
        advanceTo(slot);
        ResultActivity a = result(slot, "act:study_room", Intents.MODE_CANNOT);
        Shots.click(a, "不想做");
        assertTrue(Shots.has(a, "以后还排「在宿舍房间学习」吗？"));
        Shots.shot(a, "result_cannot_exclude");
        Shots.click(a, "以后别排了");
        assertEquals("unwilling", api.callsOf("recordOutcome").get(0).outcome.reason);
        FakePlannerApi.Call save = api.last();
        assertEquals("saveProfile", save.method);
        assertTrue(java.util.Arrays.asList(save.profile.excludedActivities).contains("study_room"));
        assertTrue(finishing(a));
    }

    @Test
    public void otherListRecordsTheChosenAlternative() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_OTHER);
        assertTrue(Shots.has(a, "原安排"));
        Shots.shot(a, "result_other");
        Shots.click(a, "力量 · 西胁结子");
        Shots.shot(a, "result_other_chosen");
        Shots.click(a, "升到 RANK 6 了");
        assertEquals("meet:strength", api.last().actionKey);
        assertEquals(NOW, api.last().slot);
    }

    @Test
    public void catchUpAllAsPlannedChainsRevisions() {
        int target = NOW + 3;
        ResultActivity a = result(target, "act:job_chagall", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "前面 3 项也照安排做了吗？"));
        Shots.shot(a, "result_catchup");
        Shots.click(a, "都做了");
        assertEquals(3, api.callsOf("recordOutcome").size());
        for (int i = 0; i < 3; i++) {
            FakePlannerApi.Call c = api.callsOf("recordOutcome").get(i);
            assertEquals(NOW + i, c.slot);
            assertEquals(Fixtures.STATE_REV + i, c.expectedRevision);
            assertEquals(Outcome.Code.AS_PREDICTED, c.outcome.code);
        }
        assertTrue(Shots.has(a, "做完了"));
        assertTrue(Shots.has(a, ResultActivity.CAUGHT_UP));
        Shots.shot(a, "result_catchup_done");
    }

    @Test
    public void catchUpAcrossLockedSlotsSendsOpenSlotsOnly() {
        // 11/2 evening → 11/6: the full-moon evenings (11/3, 11/5) and the 11/4 story day are locked.
        api.snap.observed.slot = Rules.slot(Fixtures.day(11, 2), true);
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        int target = Rules.slot(Fixtures.day(11, 6), false);
        ResultActivity a = resultController(target, api.snap.plan.at(target).action, Intents.MODE_RESULT, true).get();
        assertTrue(Shots.has(a, "前面 3 项也照安排做了吗？"));
        Shots.shot(a, "result_catchup_locks");
        Shots.click(a, "都做了");
        java.util.List<FakePlannerApi.Call> records = api.callsOf("recordOutcome");
        assertEquals(3, records.size());
        assertEquals(Rules.slot(Fixtures.day(11, 2), true), records.get(0).slot);
        assertEquals(Rules.slot(Fixtures.day(11, 3), false), records.get(1).slot);
        assertEquals(Rules.slot(Fixtures.day(11, 5), false), records.get(2).slot);
        for (FakePlannerApi.Call c : records) {
            assertFalse(c.actionKey.startsWith("locked:"));
        }
        assertEquals(ResultActivity.CAUGHT_UP, a.bannerText());
        assertEquals(target, api.snap.observed.slot);
    }

    @Test
    public void catchUpQueueCountsOpenSlots() {
        api.snap.observed.slot = Rules.slot(Fixtures.day(11, 2), true);
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        int target = Rules.slot(Fixtures.day(11, 6), false);
        ResultActivity a = resultController(target, api.snap.plan.at(target).action, Intents.MODE_RESULT, true).get();
        Shots.click(a, "一项项对");
        assertTrue(Shots.has(a, "补记 1/4"));
        Shots.shot(a, "result_catchup_queue_locks");
    }

    @Test
    public void notePanelRecordsUseOrNot() {
        int slot = Rules.slot(Fixtures.day(10, 26), true);
        advanceTo(slot);
        ResultActivity a = result(slot, "note:priestess", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "给山岸风花用上了吗？"));
        assertTrue(Shots.has(a, "怎么做 · 3 步"));
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("只加好感，不会直接升级"));
        Shots.shot(a, "result_note");
        Shots.click(a, "没用成");
        assertEquals(Outcome.Code.NOT_DONE, api.last().outcome.code);
        assertEquals("note:priestess", api.last().actionKey);
    }

    @Test
    public void notePanelUsed() {
        int slot = Rules.slot(Fixtures.day(10, 26), true);
        advanceTo(slot);
        ResultActivity a = result(slot, "note:priestess", Intents.MODE_RESULT);
        Shots.click(a, "用了");
        assertEquals(Outcome.Code.AS_PREDICTED, api.last().outcome.code);
        assertEquals("女教皇 · 用了结缘网站的字条", UndoCenter.take().summaryZh);
    }

    @Test
    public void episodePanelRecordsWatchedOrNot() {
        int slot = Rules.slot(Fixtures.day(10, 26), false);
        advanceTo(slot);
        ResultActivity a = result(slot, "episode:junpei", Intents.MODE_RESULT);
        // The title says whose episode it is; the question only names the episode.
        assertTrue(Shots.has(a, "羁绊剧情 · 伊织顺平"));
        assertTrue(Shots.has(a, "看完第 2 话了吗？"));
        assertTrue(Shots.has(a, "每一话都有小奖励（能力值或顺平的能力），看完第 4 话可以拿到道具「顺平的棒球帽」。"));
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("不加社群点数"));
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("。。"));
        Shots.shot(a, "result_episode");
        Shots.click(a, "没看成");
        assertEquals(Outcome.Code.NOT_DONE, api.last().outcome.code);
        assertEquals("episode:junpei", api.last().actionKey);
        assertEquals("羁绊剧情 · 伊织顺平 · 没看成", UndoCenter.take().summaryZh);
    }

    @Test
    public void unlockPanelShowsTheHowTo() {
        int slot = Rules.slot(Fixtures.day(10, 25), false);
        advanceTo(slot);
        ResultActivity a = result(slot, "unlock:devil:1", Intents.MODE_RESULT);
        // Folded to one row; open on request to look back at what was done.
        assertFalse(Shots.has(a, "找到穿西装的田中社长，和他说话"));
        Shots.click(a, "怎么做 · 3 步");
        assertTrue(Shots.has(a, "找到穿西装的田中社长，和他说话"));
        Shots.click(a, "做完了");
        assertEquals(Outcome.Code.AS_PREDICTED, api.last().outcome.code);
    }

    @Test
    public void cannotPanelAsksAboutSideStepsUnchecked() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_CANNOT);
        assertNotNull(a.sideRow());
        assertFalse(a.sideRow().isChecked());
        Shots.click(a, "对方不在");
        assertFalse(api.last().outcome.sideDone);
    }

    @Test
    public void finishedCalendarHasNothingToRecord() {
        api.snap.observed.slot = api.rules.lastSlot() + 1;
        api.snap.plan = Fixtures.planMid(api.rules, api.snap.observed);
        ResultActivity a = result(Rules.slot(Fixtures.day(1, 31), false), "act:study_room", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, TodayPage.FINISHED_TITLE));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void explicitCatchUpModeFromTheWidget() {
        ResultActivity a = resultController(NOW + 3, "act:job_chagall", Intents.MODE_CATCHUP, false).get();
        assertTrue(Shots.has(a, "前面 3 项也照安排做了吗？"));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void catchUpOneByOne() {
        int target = NOW + 2;
        ResultActivity a = result(target, "meet:lovers", Intents.MODE_RESULT);
        Shots.click(a, "一项项对");
        assertTrue(Shots.has(a, "升到 RANK 7 了"));
        Shots.shot(a, "result_catchup_queue");
        Shots.click(a, "升到 RANK 7 了");
        assertFalse(finishing(a));
        assertTrue(Shots.has(a, "做完了"));
        Shots.click(a, "做完了");
        assertTrue(Shots.has(a, "见面了"));
        Shots.click(a, "见面了");
        assertTrue(finishing(a));
        assertEquals(3, api.callsOf("recordOutcome").size());
    }

    @Test
    public void catchUpFromHereSkipsEarlierSlots() {
        int target = NOW + 2;
        ResultActivity a = result(target, "meet:lovers", Intents.MODE_RESULT);
        Shots.click(a, "前面都没做");
        assertEquals(2, api.callsOf("recordOutcome").size());
        assertEquals(Outcome.Code.SKIPPED, api.callsOf("recordOutcome").get(0).outcome.code);
        assertTrue(Shots.has(a, "见面了"));
    }

    // ------------------------------------------------------------------ stale

    @Test
    public void stalePlanIdShowsCurrentItemAndSavesNothing() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ResultActivity.class);
        intent.putExtra(Intents.EXTRA_SLOT, NOW + 1);
        intent.putExtra(Intents.EXTRA_ACTION, "act:study_room");
        intent.putExtra(Intents.EXTRA_PLAN_ID, "old-plan");
        intent.putExtra(Intents.EXTRA_STATE_REV, Fixtures.STATE_REV - 1);
        ResultActivity a = Robolectric.buildActivity(ResultActivity.class, intent).setup().get();
        Shots.idle();
        assertTrue(Shots.has(a, ResultActivity.STALE_INFO));
        assertTrue(Shots.has(a, "升到 RANK 7 了"));
        assertTrue(api.calls.isEmpty());
        Shots.shot(a, "result_stale");
    }

    @Test
    public void casRejectionShowsRefreshMessage() {
        api.nextReceipt = FakePlannerApi.stale();
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        Shots.click(a, "升到 RANK 7 了");
        assertFalse(finishing(a));
        assertEquals(Messages.STALE, a.bannerText());
        assertTrue(Shots.has(a, Messages.STALE));
        Shots.shot(a, "result_cas_stale");
    }

    @Test
    public void doubleTapUsesTheSameCommandId() {
        String first = CommandIds.of("result", 12, Fixtures.PLAN_ID, NOW, "meet:emperor",
            ResultActivity.outcomeKey(Outcomes.asPredicted(api.snap.plan.at(NOW))));
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        Shots.click(a, "升到 RANK 7 了");
        assertEquals(first, api.last().commandId);
    }

    @Test
    public void fromWidgetDoesNotPostUndo() {
        ResultActivity a = resultController(NOW, "meet:emperor", Intents.MODE_RESULT, false).get();
        Shots.click(a, "升到 RANK 7 了");
        assertNull(UndoCenter.take());
    }

    @Test
    public void backFromChosenAlternativeReturnsToList() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_OTHER);
        Shots.click(a, "力量 · 西胁结子");
        a.onBackPressed();
        Shots.idle();
        assertTrue(Shots.has(a, "做了什么？"));
        assertFalse(finishing(a));
        // The sheet opened on the list: back from the list closes it.
        a.onBackPressed();
        Shots.idle();
        assertTrue(finishing(a));
    }

    @Test
    public void newIntentStartsOverForTheNewItem() {
        org.robolectric.android.controller.ActivityController<ResultActivity> c =
            resultController(NOW, "meet:emperor", Intents.MODE_CANNOT, false);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ResultActivity.class);
        intent.putExtra(Intents.EXTRA_SLOT, NOW);
        intent.putExtra(Intents.EXTRA_ACTION, "meet:emperor");
        intent.putExtra(Intents.EXTRA_MODE, Intents.MODE_RESULT);
        intent.putExtra(Intents.EXTRA_PLAN_ID, Fixtures.PLAN_ID);
        intent.putExtra(Intents.EXTRA_STATE_REV, Fixtures.STATE_REV);
        c.newIntent(intent);
        Shots.idle();
        assertTrue(Shots.has(c.get(), "升到 RANK 7 了"));
        assertFalse(Shots.has(c.get(), "对方不在"));
    }

    @Test
    public void towerInputSurvivesRotation() {
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        advanceTo(slot);
        org.robolectric.android.controller.ActivityController<ResultActivity> c =
            resultController(slot, "tower:3", Intents.MODE_RESULT, true);
        TowerPanel panel = c.get().towerPanel();
        panel.floorStepper().step(+3);
        panel.setAmountForTest(30_000);
        c.recreate();
        Shots.idle();
        TowerPanel restored = c.get().towerPanel();
        assertNotNull(restored);
        assertEquals(99, restored.floorStepper().getValue());
        assertEquals("进塔前 18,500 → 出塔后 30,000 日元（+11,500）", restored.previewText());
    }

    // ------------------------------------------------------------------ panel stack

    @Test
    public void backFromCannotReturnsToTheResultPanel() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        Shots.click(a, TodayPage.CANNOT_LABEL);
        assertTrue(Shots.has(a, "怎么没做成？"));
        Shots.click(a, ResultActivity.OTHER_INSTEAD);
        assertTrue(Shots.has(a, "做了什么？"));
        a.onBackPressed();
        Shots.idle();
        assertTrue(Shots.has(a, "怎么没做成？"));
        a.onBackPressed();
        Shots.idle();
        assertFalse(finishing(a));
        assertTrue(Shots.has(a, "升到 RANK 7 了"));
        assertTrue(api.calls.isEmpty());
        a.onBackPressed();
        Shots.idle();
        assertTrue("only the entry panel closes the sheet", finishing(a));
    }

    @Test
    public void cannotOpenedDirectlyClosesOnBack() {
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_CANNOT);
        a.onBackPressed();
        Shots.idle();
        assertTrue(finishing(a));
    }

    @Test
    public void statDifferentGoesBackToTheOneTapAnswer() {
        int slot = NOW + 1;
        advanceTo(slot);
        ResultActivity a = result(slot, "act:study_room", Intents.MODE_RESULT);
        Shots.click(a, "结果不一样…");
        assertTrue(Shots.has(a, "学力现在几级？"));
        a.onBackPressed();
        Shots.idle();
        assertTrue(Shots.has(a, "做完了"));
        assertFalse(finishing(a));
    }

    @Test
    public void freeSlotOffersWhatWasDoneInstead() {
        int slot = Rules.slot(Fixtures.day(10, 25), true);
        advanceTo(slot);
        ResultActivity a = result(slot, "free", Intents.MODE_RESULT);
        Shots.click(a, "做了别的…");
        assertTrue(Shots.has(a, "做了什么？"));
        a.onBackPressed();
        Shots.idle();
        assertTrue(Shots.has(a, "继续"));
        assertTrue(api.calls.isEmpty());
    }

    // ------------------------------------------------------------------ copy and reasons

    @Test
    public void meetingRecordsTheReadyCueFromItsCompactCheck() {
        int slot = Rules.slot(Fixtures.day(10, 23), false);
        advanceTo(slot);
        ResultActivity a = result(slot, "meet:lovers", Intents.MODE_RESULT);
        assertTrue(Shots.has(a, "RANK 4"));
        assertFalse(Shots.has(a, "没见到"));
        Shots.click(a, ResultActivity.READY_CUE);
        Shots.click(a, "见面了");
        assertEquals(Outcome.Code.AS_PREDICTED, api.last().outcome.code);
        assertTrue(api.last().outcome.readyCue);
    }

    @Test
    public void meetingOtherResultsOfferTheStoryHold() {
        int slot = Rules.slot(Fixtures.day(10, 23), false);
        advanceTo(slot);
        ResultActivity a = result(slot, "meet:lovers", Intents.MODE_RESULT);
        assertFalse(Shots.has(a, ResultActivity.HOLD));
        Shots.click(a, ResultActivity.OTHER_RESULTS);
        Shots.click(a, ResultActivity.HOLD);
        assertEquals(Outcomes.REASON_HOLD, api.last().outcome.reason);
        assertEquals("恋爱 · 剧情卡住了", UndoCenter.take().summaryZh);
    }

    @Test
    public void cannotReasonsFollowTheStep() {
        java.util.List<String> meet = ResultActivity.reasonsFor(api.snap.plan.at(NOW));
        assertEquals(java.util.Arrays.asList("story", "absent", "unwilling"), meet);
        java.util.List<String> study = ResultActivity.reasonsFor(api.snap.plan.at(NOW + 1));
        assertEquals(java.util.Arrays.asList("story", "closed", "unwilling"), study);
        Plan.Step unlock = api.snap.plan.at(Rules.slot(Fixtures.day(10, 25), false));
        // Lending the president 10,000 日元 costs money.
        assertTrue(ResultActivity.reasonsFor(unlock).contains("money"));
        assertTrue(ResultActivity.reasonsFor(unlock).contains("absent"));
        assertEquals("第 2/3 步", ResultActivity.unlockProgress("解锁恶魔社群前的准备（第 2 步，共 3 步）"));
        assertEquals("完成后就解锁了", ResultActivity.unlockProgress("完成后塔社群就解锁了"));
        assertNull(ResultActivity.unlockProgress("塔社群需要勇气达到 Lv.4。"));
    }

    @Test
    public void correctionGoneSaysSoBriefly() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ResultActivity.class);
        intent.putExtra(Intents.EXTRA_CORRECT_SEQ, 12345L);
        ResultActivity a = Robolectric.buildActivity(ResultActivity.class, intent).setup().get();
        Shots.idle();
        assertTrue(Shots.has(a, "这条记录刚改过"));
        Shots.click(a, "好的");
        assertTrue(finishing(a));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void saveErrorUsesTheErrorLookAndStays() {
        api.nextReceipt = FakePlannerApi.stale();
        api.nextReceipt.errorCode = "invalid";
        api.nextReceipt.errorZh = null;
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        Shots.click(a, "升到 RANK 7 了");
        assertFalse(finishing(a));
        assertEquals(Messages.GENERIC_ERROR, a.bannerText());
        assertTrue(Shots.has(a, Messages.GENERIC_ERROR));
        Shots.shot(a, "result_save_error");
    }

    private void advanceTo(int slot) {
        api.snap.observed.slot = slot;
    }
}
