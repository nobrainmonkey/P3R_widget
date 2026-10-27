package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
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
import app.p3r.dayplanner.v3.service.StoryDisplay;

/** Pure UI logic: formatting, outcomes, drafts, forecasts, plan diffs and link status. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UiLogicTest {
    private final Rules rules = Fixtures.rules();
    private final SimState obs = Fixtures.observedMid(rules);
    private final Plan plan = Fixtures.planMid(rules, obs);

    @Test
    public void pendingStoryDatesRemainInTheCalendarAfterTheForecastMovesOn() {
        int first = obs.slot - 2;
        Rules.Day locked = rules.day(Rules.dayOf(first));
        locked.dayLock = "school_trip";
        locked.eveLock = "school_trip";
        locked.noteZh = "修学旅行，不能自由行动";
        rules.lockReasonsZh.put("school_trip", "修学旅行");
        PlannerApi.Snapshot snap = new PlannerApi.Snapshot();
        snap.observed = obs;
        snap.profile = new Profile();
        snap.plan = plan;
        snap.storyDisplay = StoryDisplay.create(rules, snap.profile, "trip-after-record", first, obs.slot - 1, first);
        UiData data = new UiData(rules, snap, null, false, 0);
        assertEquals(obs.slot, data.currentSlot());
        assertEquals(first, data.displaySlot());
        assertEquals("修学旅行", data.current().titleZh);
        assertEquals("修学旅行，不能自由行动", SlotRows.subtitle(data.current()));
        List<SchedulePage.Item> days = SchedulePage.buildItems(data.displayPlan(), data.displaySlot());
        long dayRows = days.stream().filter(item -> item.type == 2).count();
        assertEquals(Rules.dayOf(plan.toSlot) - Rules.dayOf(first) + 2L, dayRows);
        assertTrue(days.stream().anyMatch(item -> item.type == 2 && item.day == Rules.dayOf(first)));
        assertEquals("the installed forecast still starts at the next actionable slot", obs.slot, plan.fromSlot);
    }

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
        assertEquals("皇帝 改为 RANK 5", Outcomes.summary(rules, emperor, down));
        assertEquals("皇帝 RANK 5", Outcomes.recordedSummary(rules, emperor, down));

        Outcome absent = Outcomes.notMet();
        assertEquals(Outcome.Code.SKIPPED, absent.code);
        assertEquals("absent", absent.reason);
        assertEquals("皇帝 · 对方不在", Outcomes.summary(rules, emperor, absent));

        Outcome hold = Outcomes.hold();
        assertEquals(Outcome.Code.NOT_DONE, hold.code);
        assertEquals("hold", hold.reason);
        assertEquals("皇帝 · 剧情卡住了", Outcomes.summary(rules, emperor, hold));

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
        assertTrue(Assumptions.describe("ordinary_gain").contains("+10"));
        org.junit.Assert.assertNull("unknown ids have no visible copy", Assumptions.describe("some_new_id"));
        org.junit.Assert.assertNull(Assumptions.describe(null));
        for (String line : Assumptions.settings(new Profile(), rules)) {
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
    public void aLowerBoundCashIsShownAsOnTodayAndListedOnlyWhenItMoves() {
        // Cash skipped at setup (or a recorded 打工): only a lower bound is known.
        obs.cash = 0;
        obs.cashExact = false;
        StateDraft d = new StateDraft(StateDraft.Mode.SYNC, rules, obs, Fixtures.profileMid(), plan, 12);
        d.setSync(StateDraft.SyncAnswer.YES);
        assertNull("no 「不记得 → 不记得」 row", cashChange(d));
        d.setSlot(Rules.slot(Fixtures.day(10, 25), false));
        StateDraft.Change cash = cashChange(d);
        assertEquals(CashPad.UNKNOWN_LABEL, cash.beforeZh);
        assertEquals("3,000+ 日元", cash.afterZh);
        assertTrue(cash.forecast);

        obs.cash = 290;
        StateDraft edit = new StateDraft(StateDraft.Mode.EDIT, rules, obs, Fixtures.profileMid(), plan, 12);
        assertEquals("290+ 日元", edit.cashText());
        edit.setCashUnknown();
        assertEquals("290+ 日元", edit.cashText());
        assertNull(cashChange(edit));
        edit.setCash(310);
        assertEquals("310 日元", edit.cashText());
        assertEquals("290+ 日元", cashChange(edit).beforeZh);
    }

    private static StateDraft.Change cashChange(StateDraft d) {
        for (StateDraft.Change change : d.changes()) {
            if (change.labelZh.equals("现金")) {
                return change;
            }
        }
        return null;
    }

    @Test
    public void gardeningIsNotAWizardAnswer() {
        // 「种菜也排进来」 is set in 队友特性; the wizard neither asks nor lists it.
        Profile profile = new Profile();
        profile.planDormGardening = true;
        StateDraft first = new StateDraft(StateDraft.Mode.ONBOARDING, rules, null, profile, null, 0);
        for (StateDraft.Change change : first.changes()) {
            assertFalse(change.labelZh, change.labelZh.contains("园艺"));
        }
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
        assertEquals(0, p.order[5]);
        assertEquals(0, p.order[3]);
        assertEquals(Profile.Tier.MUST, Goals.cycle(p, 5));
        assertEquals(0, p.order[5]);
        assertEquals(Profile.Tier.NONE, Goals.cycle(p, 5));
        assertEquals(java.util.Arrays.asList(3), LinkStatus.goalOrder(p));
    }

    // ------------------------------------------------------------------ forecast / diff / status

    @Test
    public void forecastKeepsVisibleStoryLevelsWithoutFollowingAnUnconfirmedInvitation() {
        SimState before = obs.copy();
        before.statPts[0] = rules.stats[0].thresholds[6] - 3;
        Plan story = new Plan();
        story.fromSlot = before.slot;
        story.toSlot = before.slot;
        Plan.Step locked = new Plan.Step();
        locked.slot = before.slot;
        locked.kind = "locked";
        locked.action = "locked:summer_class";
        locked.predicted.statDelta[0] = 3;
        locked.predicted.statLvAfter[0] = 6;
        story.steps.add(locked);
        SimState after = Forecast.visibleAt(rules, before, story, before.slot + 1);
        assertEquals(rules.stats[0].thresholds[6], after.statPts[0]);
        assertEquals(before.cash, after.cash);
        assertEquals(rules.stats[0].thresholds[6] - 3, before.statPts[0]);

        locked.kind = "invite";
        locked.action = "invite:priestess";
        locked.predicted.cashDelta = 1000;
        SimState conditional = Forecast.visibleAt(rules, before, story, before.slot + 1);
        assertEquals(before.statPts[0], conditional.statPts[0]);
        assertEquals(before.cash, conditional.cash);
    }

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
        assertEquals("已重新安排：这周 1 处有变", PlanDiff.summary(rules, plan, other, obs.slot));
        Plan far = Fixtures.planMid(rules, obs, obs.slot, "plan-3");
        int farIndex = far.steps.size() - 1;
        Plan.Step last = far.steps.get(farIndex);
        if (last.slot > Rules.slot(Rules.dayOf(obs.slot) + PlanDiff.NEAR_DAYS, true)) {
            far.steps.set(farIndex, Fixtures.social(rules, last.slot, "moon", 3, 4, true, false));
            org.junit.Assert.assertNull("later days are re-planned quietly", PlanDiff.summary(rules, plan, far, obs.slot));
        }
        org.junit.Assert.assertNull("nothing changed: nothing said", PlanDiff.summary(rules, plan, plan, obs.slot));
    }

    @Test
    public void linkStatusFooters() {
        Profile p = Fixtures.profileMid();
        // A date needs no 「预计」: the cyan line is the forecast (the page has its one 「预计」).
        assertEquals("11月20日 升到 MAX", LinkStatus.of(rules, p, obs, plan, 4).footZh);
        assertEquals("皇帝 6 → MAX · 11月20日", LinkStatus.of(rules, p, obs, plan, 4).chipZh);
        assertEquals("剧情自动推进", LinkStatus.of(rules, p, obs, plan, 13).footZh);
        // A goal the plan traded away names the goal the time went to, without hedging.
        LinkStatus priestess = LinkStatus.of(rules, p, obs, plan, 2);
        assertEquals("时间先给了皇帝", priestess.footZh);
        assertEquals("皇帝", priestess.blocker);
        assertEquals("女教皇 5 → 9", priestess.chipZh);
        // The advice is one can carry out: set to 必须 when 想要 is traded for 必须 or has shortfall.
        assertEquals(LinkStatus.Fix.SET_MUST, priestess.fix);
        assertEquals("时间先给了皇帝。想先冲它，就设成必须",
            LinkStatus.shortfall("MAX", priestess.observed, priestess.projected, priestess.blocker, priestess.fix));
        assertEquals(LinkStatus.Fix.NONE, LinkStatus.fix(p, 15, 2));
        assertEquals("时间先给了女教皇",
            LinkStatus.shortfall("MAX", 0, 7, "女教皇", LinkStatus.Fix.NONE));
        assertEquals(LinkStatus.Fix.SET_MUST, LinkStatus.fix(p, 15, -1));
        assertEquals(LinkStatus.Fix.NONE, LinkStatus.fix(p, 6, -1));
        assertEquals("这次排到 RANK 9。想冲 MAX，就设成必须",
            LinkStatus.shortfall("MAX", 5, 9, null, LinkStatus.Fix.SET_MUST));
        assertEquals("这次没排上。想冲 MAX，就设成必须", LinkStatus.shortfall("MAX", 5, 5, null, LinkStatus.Fix.SET_MUST));
        // 皇帝 is 必须 (cannot be upgraded further): Fix.NONE.
        // 太阳 is 想要 with no competitor: Fix.SET_MUST.
        assertEquals(LinkStatus.Fix.NONE, LinkStatus.fix(p, 4, -1));
        assertEquals(LinkStatus.Fix.SET_MUST, LinkStatus.fix(p, 19, -1));
        assertEquals("这次排到 RANK 9", LinkStatus.shortfall("MAX", 5, 9, null, LinkStatus.Fix.NONE));
        assertEquals("这次没排上", LinkStatus.shortfall("MAX", 5, 5, null, LinkStatus.Fix.NONE));
        // A link that is not a goal is never reported as missing a target the player never set.
        p.tier[2] = Profile.Tier.NONE;
        assertEquals("", LinkStatus.of(rules, p, obs, plan, 2).footZh);
        assertEquals("女教皇 5 → 9", LinkStatus.of(rules, p, obs, plan, 2).chipZh);
        assertEquals("", LinkStatus.of(rules, p, obs, plan, 3).footZh);
        // No plan yet: no placeholder line on 22 tiles.
        assertEquals("", LinkStatus.of(rules, p, obs, null, 4).footZh);
        obs.rank[4] = 10;
        assertEquals("皇帝 MAX ✓", LinkStatus.of(rules, p, obs, plan, 4).chipZh);
        assertEquals("", LinkStatus.of(rules, p, obs, plan, 4).footZh);
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
        assertFalse(hierophant.rescueFailed);
        // A question; 社群详情 answers it with 「去修改进度」.
        assertEquals("救出北村文吉了吗？", hierophant.footZh);
        assertEquals("北村文吉", hierophant.rescue.zh);
        obs.rescueFailed = 1L;
        assertEquals("没救出失踪者，没法继续了", LinkStatus.of(rules, p, obs, plan, 5).footZh);
        assertTrue(LinkStatus.of(rules, p, obs, plan, 5).rescueFailed);
    }

    /** The hero and the day sheet: the answers and a short note at most, never the guide's long note. */
    @Test
    public void reviewedAnswersShowOnlyTheShortNote() {
        Rules.DialogueVariant friend = new Rules.DialogueVariant();
        friend.choices.add("我很喜欢");
        friend.noteZh = "这是友情结尾的三问剧情：前两问任意，收到耳机后选喜欢。MAX后没有下一级好感门槛。";
        friend.shortNoteZh = "";
        Rules.DialogueVariant romance = new Rules.DialogueVariant();
        romance.choices.add("放心了");
        romance.noteZh = "主列表对应此前 RANK 8 已经选择友情的两问场景，请按下方另一组婉拒。";
        romance.shortNoteZh = "婉拒告白";
        Rules.DialogueHintEntry entry = new Rules.DialogueHintEntry();
        entry.friend = friend;
        entry.romance = romance;
        int priestess = rules.link("priestess").idx;
        rules.links[priestess].dialogueHints.put(10, entry);
        Plan.Step step = Fixtures.social(rules, Fixtures.SLOT_NOW, "priestess", 9, 10, true, false);
        assertEquals("回答：我很喜欢", Dialogue.line(rules, Fixtures.profileMid(), step));
        Profile romanceRoute = Fixtures.profileMid();
        romanceRoute.route[priestess] = Profile.Route.ROMANCE;
        assertEquals("回答：放心了；婉拒告白", Dialogue.line(rules, romanceRoute, step));
        // No short note in the pack: the long one is still left out, the answers to avoid stay.
        romance.shortNoteZh = null;
        assertEquals("回答：放心了", Dialogue.line(rules, romanceRoute, step));
        romance.avoid.add("我喜欢风花（1）");
        assertEquals("回答：放心了；别选「我喜欢风花」", Dialogue.line(rules, romanceRoute, step));
    }

    /** 今天's goal lines: a lost link is in trouble, unless its goal was reached before the rescue. */
    @Test
    public void aReachedGoalIsNeverInTroubleOnToday() {
        Profile p = Fixtures.profileMid();
        p.tier[5] = Profile.Tier.MUST;
        p.target[5] = 10;
        obs.rescued = 0;
        LinkStatus lost = LinkStatus.of(rules, p, obs, plan, 5);
        assertTrue(lost.lost);
        assertEquals(rules.links[5].arcanaZh + " · 无法继续", TodayPage.troubleOf(rules, lost));
        obs.rank[5] = 10;
        LinkStatus maxed = LinkStatus.of(rules, p, obs, plan, 5);
        assertTrue(maxed.lost);
        org.junit.Assert.assertNull("the goal is reached: nothing to fix", TodayPage.troubleOf(rules, maxed));
    }

    @Test
    public void summariesForTheNewPanels() {
        Rules.Episode junpei = rules.episodes[0];
        // The row says what the end of the track gives; the window and time of day are the plan's business.
        assertEquals("看完可以拿到「顺平的棒球帽」", LinksPage.rewardLine(junpei));
        String rewardZh = junpei.rewardZh;
        junpei.rewardZh = "每一话都有小奖励（能力值或顺平的能力），看完第 5 话可以合体人格面具「苏尔特尔」。";
        assertEquals("看完可以合体「苏尔特尔」Surt", LinksPage.rewardLine(junpei));
        junpei.rewardZh = "每一话都有小奖励（能力值），之后的第 5 话可以合体人格面具「地狱天使」。";
        assertEquals("看完可以合体「地狱天使」Hell Biker", LinksPage.rewardLine(junpei));
        junpei.rewardZh = rewardZh;
        assertEquals("11月11日起 · 每张 3,000 日元", SettingsPage.datingNoteText(rules.datingNote));
        assertEquals(java.util.Arrays.asList("游戏数据 · 1 项", "资料站 · 1 项"), InfoPages.sourceLines(rules.sources));
        assertEquals("小田桐秀利 · 男", Zh.person(rules.links[4]));
        assertEquals("特别课外活动部\n团体 · 男女", Zh.person(rules.links[0]));
        Plan.Step planned = plan.at(Fixtures.SLOT_NOW);
        assertEquals("做了别的事",
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
        assertTrue(UiData.returnBannerDue(rules, s, now, 0));
        assertFalse(UiData.returnBannerDue(rules, s, now, s.lastRecordMillis));
        s.lastRecordMillis = now - 60 * 60 * 1000;
        assertFalse(UiData.returnBannerDue(rules, s, now, 0));
        // After Jan 31 there is nothing left to catch up on.
        s.lastRecordMillis = now - 9L * 60 * 60 * 1000;
        SimState over = obs.copy();
        over.slot = rules.lastSlot() + 1;
        s.observed = over;
        assertFalse(UiData.returnBannerDue(rules, s, now, 0));
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
