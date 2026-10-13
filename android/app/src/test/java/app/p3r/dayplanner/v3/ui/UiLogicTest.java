package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/** Pure UI logic: formatting, outcomes, drafts, forecasts, plan diffs and link status. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UiLogicTest {
    private final Rules rules = Fixtures.rules();
    private final SimState obs = Fixtures.observedMid(rules);
    private final Plan plan = Fixtures.planMid(rules, obs);

    // ------------------------------------------------------------------ Zh

    @Test
    public void chineseFormatting() {
        assertEquals("8,200 日元", Zh.yen(8200));
        assertEquals("−1,000 日元", Zh.yenDelta(-1000));
        assertEquals("+2,500 日元", Zh.yenDelta(2500));
        assertEquals("10月22日", Zh.date(Fixtures.OCT_22));
        assertEquals("10/22", Zh.shortDate(Fixtures.OCT_22));
        assertEquals("周四", Zh.weekdayOf(Fixtures.OCT_22));
        assertEquals("1月31日", Zh.date(305));
        assertEquals("放学后", Zh.period(rules, Fixtures.SLOT_NOW));
        assertEquals("晚上", Zh.period(rules, Fixtures.SLOT_NOW + 1));
        assertEquals("白天", Zh.period(rules, Rules.slot(Fixtures.day(10, 25), false)));
        assertEquals("未解锁", Zh.rank(0));
        assertEquals("RANK 7", Zh.rank(7));
        assertEquals("MAX", Zh.rank(10));
        assertEquals("RANK 9 → MAX", Zh.rankStep(9, 10));
        assertEquals("Lv.3", Zh.lv(3));
        assertEquals("MAX", Zh.lv(6));
        assertEquals("5月12日–1月29日", Zh.dateRange(Fixtures.day(5, 12), Fixtures.day(1, 29)));
        assertEquals("0", Zh.roman(0));
        assertEquals("XIV", Zh.roman(14));
        assertEquals("10月21日 晚上", Zh.lastRecorded(rules, Fixtures.SLOT_NOW));
    }

    // ------------------------------------------------------------------ outcomes

    @Test
    public void outcomesForEachButton() {
        Plan.Step emperor = plan.at(Fixtures.SLOT_NOW);
        Outcome up = Outcomes.asPredicted(emperor);
        assertEquals(Outcome.Code.AS_PREDICTED, up.code);
        assertEquals(7, up.rank);
        assertEquals("皇帝 RANK 7", Outcomes.summary(rules, emperor, up));

        Outcome no = Outcomes.noRankUp(6, true);
        assertEquals(Outcome.Code.NO_RANK_UP, no.code);
        assertEquals(6, no.rank);
        assertTrue(no.readyCue);
        assertEquals("皇帝 · 没升级", Outcomes.summary(rules, emperor, no));

        assertEquals(Outcome.Code.RANK_UP, Outcomes.observedRank(6, 8, false).code);
        assertEquals(Outcome.Code.NO_RANK_UP, Outcomes.observedRank(6, 6, false).code);
        Outcome down = Outcomes.observedRank(6, 5, false);
        assertEquals(Outcome.Code.OTHER, down.code);
        assertEquals(5, down.rank);

        Outcome absent = Outcomes.notMet();
        assertEquals(Outcome.Code.SKIPPED, absent.code);
        assertEquals("absent", absent.reason);
        assertEquals("跳过 · 对方不在", Outcomes.summary(rules, emperor, absent));

        Outcome hold = Outcomes.hold();
        assertEquals(Outcome.Code.NOT_DONE, hold.code);
        assertEquals("hold", hold.reason);

        Outcome money = Outcomes.skipped("money", 1200);
        assertEquals(1200, money.cashBalance);
        Outcome diff = Outcomes.different(new int[]{-1, 4, -1}, 30_000);
        assertEquals(Outcome.Code.OTHER, diff.code);
        assertEquals(4, diff.statLv[1]);
        assertEquals(30_000, diff.cashBalance);

        Plan.Step meet = plan.at(Fixtures.SLOT_NOW + 2);
        assertEquals("恋爱 · 见面", Outcomes.summary(rules, meet, Outcomes.met(meet, false)));
        assertEquals(4, Outcomes.met(meet, false).rank);
    }

    @Test
    public void stepKindsAndDirectCommit() {
        assertEquals(StepKind.SOCIAL_RANK_UP, StepKind.of(plan.at(Fixtures.SLOT_NOW)));
        assertEquals(StepKind.STAT, StepKind.of(plan.at(Fixtures.SLOT_NOW + 1)));
        assertEquals(StepKind.SOCIAL_MEET, StepKind.of(plan.at(Fixtures.SLOT_NOW + 2)));
        assertEquals(StepKind.JOB, StepKind.of(plan.at(Fixtures.SLOT_NOW + 3)));
        assertEquals(StepKind.TOWER, StepKind.of(plan.at(Fixtures.SLOT_NOW + 5)));
        assertEquals(StepKind.UNLOCK, StepKind.of(plan.at(Fixtures.SLOT_NOW + 6)));
        assertEquals(StepKind.FREE, StepKind.of(plan.at(Fixtures.SLOT_NOW + 7)));
        assertTrue(StepKind.STAT.directCommit());
        assertTrue(StepKind.LOCKED.directCommit());
        assertFalse(StepKind.TOWER.directCommit());
        assertFalse(StepKind.SOCIAL_MEET.directCommit());
    }

    @Test
    public void estimatedRankUpsGetTheApproxPrefix() {
        assertEquals("≈ RANK 7 → 8", SlotRows.subtitle(plan.at(Rules.slot(Fixtures.day(10, 24), false))));
        assertEquals("RANK 6 → 7", SlotRows.subtitle(plan.at(Fixtures.SLOT_NOW)));
    }

    @Test
    public void commandIdsAreDeterministicAndDistinct() {
        String a = CommandIds.of("result", 12, "p", 408, "meet:emperor", "AS_PREDICTED");
        assertEquals(a, CommandIds.of("result", 12, "p", 408, "meet:emperor", "AS_PREDICTED"));
        assertNotEquals(a, CommandIds.of("result", 13, "p", 408, "meet:emperor", "AS_PREDICTED"));
        assertTrue(a.startsWith("ui-"));
        assertEquals(27, a.length());
        String up = ResultActivity.outcomeKey(Outcomes.rankUp(7));
        String no = ResultActivity.outcomeKey(Outcomes.noRankUp(6, false));
        assertNotEquals(up, no);
    }

    @Test
    public void assumptionIdsNeverLeak() {
        List<String> lines = Assumptions.describeAll(java.util.Arrays.asList("ordinary_gain", "some_new_id"), true);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("+10"));
        assertEquals(Assumptions.GENERIC, lines.get(1));
        for (String line : Assumptions.global(new Profile())) {
            assertFalse(line, line.matches(".*[a-z_]{4,}.*"));
        }
    }

    // ------------------------------------------------------------------ drafts

    @Test
    public void untouchedDraftKeepsHiddenEstimates() {
        obs.pts[1] = 9;
        obs.statPts[0] = 120;
        StateDraft d = new StateDraft(StateDraft.Mode.EDIT, rules, obs, Fixtures.profileMid(), plan, 12);
        SimState built = d.build();
        assertEquals(9, built.pts[1]);
        assertEquals(120, built.statPts[0]);
        assertEquals(12, built.pts[4]);
        assertTrue(d.changes().isEmpty());
    }

    @Test
    public void changedValuesResetOnlyTheirOwnEstimates() {
        obs.pts[1] = 9;
        StateDraft d = new StateDraft(StateDraft.Mode.EDIT, rules, obs, Fixtures.profileMid(), plan, 12);
        d.setRank(4, 8);
        d.setStatLv(1, 4);
        d.setRank(2, StateDraft.UNKNOWN);
        SimState built = d.build();
        assertEquals(8, built.rank[4]);
        assertEquals(0, built.pts[4]);
        assertEquals(9, built.pts[1]);
        assertEquals(rules.stats[1].thresholds[4], built.statPts[1]);
        assertEquals(obs.rank[2], built.rank[2]);
        assertEquals(2, d.changes().size());
    }

    @Test
    public void cashUnknownIsNotZero() {
        StateDraft d = new StateDraft(StateDraft.Mode.EDIT, rules, obs, Fixtures.profileMid(), plan, 12);
        d.setCashUnknown();
        SimState built = d.build();
        assertFalse(built.cashExact);
        assertEquals(obs.cash, built.cash);
    }

    @Test
    public void newGameStartsAtTheFirstFreeSlot() {
        StateDraft d = new StateDraft(StateDraft.Mode.ONBOARDING, rules, null, new Profile(), null, 0);
        assertEquals(Rules.slot(Fixtures.day(4, 18), false), d.slot());
        assertTrue(d.cashUnknown());
        assertEquals(1, d.statLv(0));
        assertEquals(6, d.changes().size());
    }

    @Test
    public void unlockPageOnlyForRelevantLinks() {
        StateDraft d = new StateDraft(StateDraft.Mode.EDIT, rules, obs, Fixtures.profileMid(), plan, 12);
        List<Integer> links = d.unlockLinks();
        assertTrue(links.contains(15));
        assertTrue(links.contains(16));
        assertFalse(links.contains(4));
        d.profile.otherLinks = false;
        assertFalse(d.unlockLinks().contains(16));
    }

    @Test
    public void goalCyclingAndOrdering() {
        Profile p = new Profile();
        assertEquals(Profile.Tier.WANT, Goals.cycle(p, 3));
        assertEquals(Profile.Tier.WANT, Goals.cycle(p, 5));
        assertEquals(1, p.order[5]);
        assertTrue(Goals.move(p, 5, -1));
        assertEquals(0, p.order[5]);
        assertEquals(1, p.order[3]);
        assertFalse(Goals.move(p, 5, -1));
        assertEquals(Profile.Tier.MUST, Goals.cycle(p, 5));
        assertEquals(Profile.Tier.NONE, Goals.cycle(p, 5));
        assertEquals(java.util.Arrays.asList(3), LinkStatus.goalOrder(p));
    }

    // ------------------------------------------------------------------ forecast / diff / status

    @Test
    public void forecastProjectsVisibleValuesOnly() {
        int target = Rules.slot(Fixtures.day(10, 25), false);
        SimState f = Forecast.visibleAt(rules, obs, plan, target);
        assertEquals(target, f.slot);
        assertEquals(7, f.rank[4]);
        assertEquals(0, f.pts[4]);
        assertEquals(8, f.rank[19]);
        assertEquals(21_500, f.cash);
        assertFalse(f.cashExact);
        assertEquals(2, f.towerVisits[3]);
        assertEquals(7, f.shifts[1]);
        assertEquals(6, obs.rank[4]);
        assertEquals(Fixtures.SLOT_NOW, obs.slot);
    }

    @Test
    public void planDiffCountsChangedSlots() {
        Plan other = Fixtures.planMid(rules, obs, obs.slot, "plan-2");
        other.steps.set(3, Fixtures.social(rules, obs.slot + 3, "moon", 3, 4, true, false));
        assertEquals(1, PlanDiff.changedSlots(plan, other, obs.slot));
        assertEquals("已重新安排：这一周有 1 处变动", PlanDiff.summary(rules, plan, other, obs.slot));
        Plan far = Fixtures.planMid(rules, obs, obs.slot, "plan-3");
        int farIndex = far.steps.size() - 1;
        Plan.Step last = far.steps.get(farIndex);
        if (last.slot > Rules.slot(Rules.dayOf(obs.slot) + PlanDiff.NEAR_DAYS, true)) {
            far.steps.set(farIndex, Fixtures.social(rules, last.slot, "moon", 3, 4, true, false));
            assertEquals("已重新安排：这一周不变，之后的安排顺着调整了", PlanDiff.summary(rules, plan, far, obs.slot));
        }
        assertEquals("已重新安排：后面的安排没变", PlanDiff.summary(rules, plan, plan, obs.slot));
    }

    @Test
    public void linkStatusFooters() {
        Profile p = Fixtures.profileMid();
        assertEquals("预计 11/20 升到 MAX", LinkStatus.of(rules, p, obs, plan, 4).footZh);
        assertEquals("皇帝 6→MAX · 预计 11/20", LinkStatus.of(rules, p, obs, plan, 4).chipZh);
        assertEquals("剧情自动推进", LinkStatus.of(rules, p, obs, plan, 13).footZh);
        assertTrue(LinkStatus.of(rules, p, obs, plan, 2).footZh.startsWith("时间不够，先让给了皇帝、恋爱"));
        obs.rescueFailed = 2L;
        assertTrue(LinkStatus.of(rules, p, obs, plan, 12).lost);
    }

    @Test
    public void syncYesWithUnknownRankKeepsTheRecord() {
        obs.pts[4] = 9;
        StateDraft d = new StateDraft(StateDraft.Mode.SYNC, rules, obs, Fixtures.profileMid(), plan, 12);
        d.setSync(StateDraft.SyncAnswer.YES);
        d.setSlot(Rules.slot(Fixtures.day(10, 25), false));
        assertEquals(7, d.rank(4));
        d.setRank(4, StateDraft.UNKNOWN);
        SimState built = d.build();
        assertEquals(6, built.rank[4]);
        assertEquals(9, built.pts[4]);
        for (StateDraft.Change change : d.changes()) {
            assertFalse(change.labelZh, change.labelZh.equals("皇帝"));
        }
    }

    @Test
    public void forecastCarriesSideStepsRescuesAndEpisodes() {
        int target = Rules.slot(Fixtures.day(10, 27), false);
        SimState f = Forecast.visibleAt(rules, obs, plan, target);
        // 10/22 side step "unlock:tower:0", 10/24 tower visit inside 舞子's window, 10/26 episode.
        assertEquals(1, f.unlockStep[16]);
        assertEquals(2L, f.rescued & 2L);
        assertEquals(2, f.episodes[0]);
        assertEquals(0, obs.unlockStep[16]);
    }

    @Test
    public void openSlotsFollowTheCalendarLocks() {
        int nov2eve = Rules.slot(Fixtures.day(11, 2), true);
        assertEquals(nov2eve, Slots.firstOpen(rules, nov2eve));
        assertEquals(Rules.slot(Fixtures.day(11, 5), false), Slots.firstOpen(rules, Rules.slot(Fixtures.day(11, 3), true)));
        assertEquals(3, Slots.openBetween(rules, nov2eve, Rules.slot(Fixtures.day(11, 6), false)));
        assertEquals(rules.lastSlot() + 1, Slots.firstOpen(rules, rules.lastSlot()));
        SimState done = obs.copy();
        done.slot = rules.lastSlot() + 1;
        assertTrue(Slots.finished(rules, done));
        assertFalse(Slots.finished(rules, obs));
    }

    @Test
    public void howToAndSideLinesAreCleanedUp() {
        assertEquals(java.util.Arrays.asList("去商城", "找社长", "借钱"),
            StepViews.howToLines("1. 去商城\n2、找社长\n\n③ 借钱"));
        assertEquals(java.util.Arrays.asList("只有一步"), StepViews.howToLines("第 1 步：只有一步"));
        assertEquals("怎么做 · 3 步", StepViews.howToLabel("a\nb\nc"));
        assertEquals("怎么做", StepViews.howToLabel("a"));
        Plan.Step step = new Plan.Step();
        step.sideZh.add("把钱交给社长");
        step.sideZh.add("顺便：听说和尚的事");
        step.sideZh.add(" ");
        assertEquals(java.util.Arrays.asList("顺便：把钱交给社长", "顺便：听说和尚的事"), StepViews.sideLines(step));
        assertTrue(StepViews.hasSide(step));
        assertFalse(StepViews.hasHint(step));
    }

    @Test
    public void rescueWindowPassedWithoutRecordExplainsTheLostLink() {
        Profile p = Fixtures.profileMid();
        obs.rescued = 0;
        LinkStatus hierophant = LinkStatus.of(rules, p, obs, plan, 5);
        assertTrue(hierophant.lost);
        assertEquals("还没记录救出北村文吉，可以在「修改进度」里补上", hierophant.footZh);
        obs.rescueFailed = 1L;
        assertEquals("没能救出失踪者，这个社群无法继续", LinkStatus.of(rules, p, obs, plan, 5).footZh);
    }

    @Test
    public void summariesForTheNewPanels() {
        Rules.Episode junpei = rules.episodes[0];
        assertEquals("白天 · 5月12日–1月29日能看 · 已看 1 话\n"
                + "每一话都有小奖励（能力值或顺平的能力），看完第 4 话可以拿到道具「顺平的棒球帽」。",
            LinksPage.episodeSummary(junpei, 1));
        assertTrue(LinksPage.episodeSummary(junpei, 4).contains("已经看完"));
        assertEquals("在桐叶购物中心的放纵俱乐部买结缘网站的字条，当晚回宿舍用公用电脑给一个已解锁的社群加好感，不会直接升级。"
            + "每张 3,000 日元。11月11日起能买到。", SettingsPage.datingNoteText(rules.datingNote));
        assertEquals(java.util.Arrays.asList("游戏数据 · 1 项", "资料站 · 1 项"), InfoPages.sourceLines(rules.sources));
        assertEquals("小田桐秀利 · 男", Zh.person(rules.links[4]));
        assertEquals("特别课外活动部\n团体·男女", Zh.person(rules.links[0]));
        Plan.Step planned = plan.at(Fixtures.SLOT_NOW);
        assertEquals("做了列表以外的事",
            Outcomes.summary(rules, planned, Outcomes.withSide(Outcomes.skipped(Outcomes.REASON_OTHER, -1), true)));
        assertEquals(StepKind.NOTE, StepKind.of(plan.at(Rules.slot(Fixtures.day(10, 26), true))));
        assertEquals(StepKind.EPISODE, StepKind.of(plan.at(Rules.slot(Fixtures.day(10, 26), false))));
    }

    @Test
    public void returnBannerAfterEightHours() {
        PlannerApi.Snapshot s = new PlannerApi.Snapshot();
        s.observed = obs;
        long now = 100L * 60 * 60 * 1000;
        s.lastRecordMillis = now - 9L * 60 * 60 * 1000;
        assertTrue(UiData.returnBannerDue(s, now, 0));
        assertFalse(UiData.returnBannerDue(s, now, s.lastRecordMillis));
        s.lastRecordMillis = now - 60 * 60 * 1000;
        assertFalse(UiData.returnBannerDue(s, now, 0));
    }

    @Test
    public void messagesNeverShowEnglish() {
        PlannerApi.Receipt r = new PlannerApi.Receipt();
        r.errorZh = "state revision mismatch";
        assertEquals(Messages.GENERIC_ERROR, Messages.receiptError(r));
        r.errorCode = "stale";
        assertEquals(Messages.STALE, Messages.receiptError(r));
        r.errorCode = "invalid";
        r.errorZh = "现金不能是负数";
        assertEquals("现金不能是负数", Messages.receiptError(r));
    }
}
