package app.p3r.dayplanner.v3.widget;

import org.junit.Test;

import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;
import app.p3r.dayplanner.v3.ui.ReminderFixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain-JVM checks of item selection, Done routing, receipts and card copy. */
public class PlanViewTest {
    private static final int S = FakePlannerApi.FIRST_SLOT;
    private static final long NOW = 1_000_000L;

    private static PlannerApi.Snapshot snapshot(FakePlannerApi fake) {
        return fake.snapshot();
    }

    @Test
    public void itemsSkipFreeSlotsAndMergeLockedRuns() {
        List<PlanItems.Item> items = PlanItems.of(FakePlannerApi.cannedPlan("p"), S);
        assertEquals(8, items.size());
        PlanItems.Item story = items.get(6);
        assertEquals("locked:story", story.step.action);
        assertEquals(S + 6, story.slot);
        assertEquals(S + 7, story.lastSlot);
        assertTrue(story.covers(S + 7));
        assertEquals(6, PlanItems.indexOf(items, S + 7));
        assertEquals(-1, PlanItems.indexOf(items, S + 8)); // free slot
        assertEquals("tower:4", items.get(7).step.action);
        assertEquals(6, PlanItems.of(FakePlannerApi.cannedPlan("p"), S + 2).size());
        assertTrue(PlanItems.of(null, S).isEmpty());
    }

    @Test
    public void noteAndEpisodeStepsAreItemsWhateverTheirKind() {
        Plan plan = FakePlannerApi.cannedPlan("p");
        Plan.Step episode = FakePlannerApi.episodeStep(S + 8);
        episode.kind = "free"; // an engine that files it under a coarse kind must not hide it
        plan.steps.set(8, episode);
        List<PlanItems.Item> items = PlanItems.of(plan, S);
        assertEquals(9, items.size());
        assertEquals("episode:junpei", items.get(7).step.action);
        assertFalse(PlanItems.isProductive(null));
        assertFalse(PlanItems.isProductive(plan.steps.get(5)));
    }

    @Test
    public void modesFollowTheSnapshot() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.observed = null;
        assertEquals(PlanView.Mode.EMPTY, PlanView.resolve(snapshot(fake), -1, null, NOW).mode);
        assertEquals(PlanView.Done.ONBOARD, PlanView.resolve(snapshot(fake), -1, null, NOW).done);

        fake = new FakePlannerApi();
        fake.plan = null;
        fake.planning = true;
        assertEquals(PlanView.Mode.PLANNING, PlanView.resolve(snapshot(fake), -1, null, NOW).mode);
        fake.planning = false;
        fake.planErrorZh = "规则包读取失败";
        PlanView error = PlanView.resolve(snapshot(fake), -1, null, NOW);
        assertEquals(PlanView.Mode.ERROR, error.mode);
        assertEquals("规则包读取失败", error.toCard(null).cue);

        fake = new FakePlannerApi();
        fake.observed.slot = S + 20;
        assertEquals(PlanView.Mode.FINISHED, PlanView.resolve(snapshot(fake), -1, null, NOW).mode);
        assertEquals("后面没有要做的安排了", PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null).title);
        assertEquals(PlanView.Mode.ERROR, PlanView.unavailable().mode);
        assertEquals("暂时读不到安排", PlanView.unavailable().toCard(null).title);
    }

    @Test
    public void unreadableRecordsShowTheErrorInsteadOfOnboarding() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.observed = null;
        fake.planErrorZh = "游戏资料读取失败，请重新打开应用";
        PlanView view = PlanView.resolve(snapshot(fake), -1, null, NOW);
        assertEquals(PlanView.Mode.ERROR, view.mode);
        assertEquals(PlanView.Done.OPEN_APP, view.done);
        CardModel card = view.toCard(null);
        assertEquals("暂时读不到安排", card.title);
        assertEquals("游戏资料读取失败，请重新打开应用", card.cue);
        // Review: a row of dead 当前/完成 buttons. The card offers the one thing it asks for instead.
        assertFalse("no dead 当前", card.currentVisible);
        assertTrue(card.doneEnabled);
        assertEquals("打开应用", card.doneLabel);
        assertEquals(CardModel.DoneIcon.NONE, card.doneIcon);
    }

    @Test
    public void aLaggingPlanThatStartsLaterWaitsForTheReplan() {
        // Right after an undo: observed is back on S+1, the installed plan was made for S+2 onward.
        FakePlannerApi fake = new FakePlannerApi();
        fake.plan.fromSlot = S + 2;
        fake.plan.steps.subList(0, 2).clear();
        fake.observed.slot = S + 1;
        fake.planCurrent = false;
        fake.planning = true;
        PlanView view = PlanView.resolve(snapshot(fake), -1, null, NOW);
        assertEquals("never offer 已经玩到这里 on the next slot", PlanView.Mode.PLANNING, view.mode);
        assertEquals("only 打开应用", PlanView.Done.OPEN_APP, view.done);
        assertEquals("正在生成安排…", view.toCard(null).title);
        fake.planning = false;
        fake.planErrorZh = "生成安排时出了问题，请稍后再试";
        PlanView failed = PlanView.resolve(snapshot(fake), -1, null, NOW);
        assertEquals(PlanView.Mode.ERROR, failed.mode);
        assertEquals("暂时没有安排", failed.toCard(null).title);
        assertEquals("生成安排时出了问题，请稍后再试", failed.toCard(null).cue);
        // A current plan, or one that still covers the observed slot, is shown as usual.
        fake.planErrorZh = null;
        fake.observed.slot = S + 2;
        assertEquals(PlanView.Mode.ITEM, PlanView.resolve(snapshot(fake), -1, null, NOW).mode);
    }

    @Test
    public void cursorPicksTheBrowsedItemOrFallsBackToThePendingOne() {
        FakePlannerApi fake = new FakePlannerApi();
        assertEquals(0, PlanView.resolve(snapshot(fake), -1, null, NOW).index);
        assertEquals(2, PlanView.resolve(snapshot(fake), S + 2, null, NOW).index);
        assertEquals(6, PlanView.resolve(snapshot(fake), S + 7, null, NOW).index);
        assertEquals("unknown slot → pending item", 0, PlanView.resolve(snapshot(fake), S + 8, null, NOW).index);
    }

    @Test
    public void doneRoutesByKindAndPosition() {
        FakePlannerApi fake = new FakePlannerApi();
        assertEquals(PlanView.Done.RESULT, PlanView.resolve(snapshot(fake), -1, null, NOW).done); // social
        assertEquals(PlanView.Done.CATCHUP, PlanView.resolve(snapshot(fake), S + 1, null, NOW).done); // future
        fake.observeAt("act:study_room");
        assertEquals(PlanView.Done.COMMIT, PlanView.resolve(snapshot(fake), -1, null, NOW).done);
        fake.observeAt("act:job_chagall");
        assertEquals(PlanView.Done.COMMIT, PlanView.resolve(snapshot(fake), -1, null, NOW).done);
        fake.planCurrent = false;
        assertEquals("stale plan → sheet", PlanView.Done.RESULT, PlanView.resolve(snapshot(fake), -1, null, NOW).done);
        fake.planCurrent = true;
        fake.observeAt("unlock:temperance:0");
        assertEquals(PlanView.Done.RESULT, PlanView.resolve(snapshot(fake), -1, null, NOW).done);
        fake.observeAt("tower:4");
        assertEquals(PlanView.Done.RESULT, PlanView.resolve(snapshot(fake), -1, null, NOW).done);
        fake.observeAt("locked:full_moon");
        PlanView locked = PlanView.resolve(snapshot(fake), -1, null, NOW);
        assertEquals("nothing to record: on to the next item", PlanView.Done.NEXT, locked.done);
    }

    /**
     * Review: a story-locked pending item showed a dimmed 当前 beside a dimmed 完成, a dead row. It has
     * one live button instead: 下一项 (or 查看日程 when the plan ends with the lock).
     */
    @Test
    public void storyLockedItemsHaveOneLiveButton() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.observeAt("locked:full_moon");
        CardModel pending = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null);
        assertFalse("no dead 当前", pending.currentVisible);
        assertTrue(pending.doneEnabled);
        assertEquals("下一项", pending.doneLabel);
        assertEquals(CardModel.DoneIcon.NONE, pending.doneIcon);
        assertEquals("下一项：这段时间不能自由行动，不用记录，看之后要做的安排", pending.doneDescription);
        // The merged story run after it, browsed to: 当前 goes back, 下一项 goes on.
        PlanView run = PlanView.resolve(snapshot(fake), S + 6, null, NOW);
        assertEquals(PlanView.Done.NEXT, run.done);
        CardModel browsed = run.toCard(null);
        assertTrue(browsed.currentVisible);
        assertTrue(browsed.currentEnabled);
        assertTrue(browsed.doneEnabled);
        // A plan that ends with the lock: nothing comes after it but the whole schedule.
        fake.plan.steps.subList(8, 10).clear();
        PlanView last = PlanView.resolve(snapshot(fake), S + 6, null, NOW);
        assertEquals(PlanView.Done.OPEN_SCHEDULE, last.done);
        CardModel end = last.toCard(null);
        assertEquals("查看日程", end.doneLabel);
        assertTrue(end.doneEnabled);
        assertTrue(end.currentEnabled);
    }

    /** Review: 当前 was drawn fully enabled on the pending item, where ‹ is already dimmed. */
    @Test
    public void currentIsDimmedWhereItHasNowhereToGo() {
        FakePlannerApi fake = new FakePlannerApi();
        CardModel pending = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null);
        assertFalse(pending.prevEnabled);
        assertFalse("on the pending item 当前 does nothing", pending.currentEnabled);
        assertTrue(pending.currentVisible);
        assertEquals("当前：已经在显示下一项要做的安排", pending.currentDescription);
        assertFalse("browsed back to the pending item", PlanView.resolve(snapshot(fake), S, null, NOW).toCard(null).currentEnabled);
        CardModel browsed = PlanView.resolve(snapshot(fake), S + 2, null, NOW).toCard(null);
        assertTrue(browsed.prevEnabled);
        assertTrue("a browsed item can go back", browsed.currentEnabled);
        WidgetPrefs.Receipt r = new WidgetPrefs.Receipt();
        fake.observeAt("meet:emperor");
        r.stateRevision = fake.stateRevision;
        r.slot = S + 1;
        r.action = "act:study_room";
        r.title = "在宿舍房间学习";
        r.kind = "stat";
        r.createdMillis = NOW;
        assertTrue("the receipt leaves for the pending item", PlanView.resolve(snapshot(fake), -1, r, NOW).toCard(null).currentEnabled);
    }

    /** Review: finished and error cards showed a dead 当前/完成 row; each now has one live button. */
    @Test
    public void statusCardsHaveOneButtonThatOpensTheApp() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.observed.slot = S + 20;
        PlanView finished = PlanView.resolve(snapshot(fake), -1, null, NOW);
        assertEquals(PlanView.Mode.FINISHED, finished.mode);
        assertEquals(PlanView.Done.OPEN_SCHEDULE, finished.done);
        CardModel card = finished.toCard(null);
        assertEquals("可以在应用里查看完整日程", card.cue);
        assertEquals("查看日程", card.doneLabel);
        assertTrue(card.doneEnabled);
        assertFalse(card.currentVisible);

        fake = new FakePlannerApi();
        fake.plan = null;
        fake.planning = true;
        CardModel planning = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null);
        assertEquals("打开应用", planning.doneLabel);
        assertFalse(planning.currentVisible);
        CardModel unreachable = PlanView.unavailable().toCard(null);
        assertEquals(PlanView.Done.OPEN_APP, PlanView.unavailable().done);
        assertEquals("打开应用", unreachable.doneLabel);
        assertTrue(unreachable.doneEnabled);
    }

    /**
     * Review: 「结缘网站的字条 · 岳羽由…」 on the smallest card; the short form keeps the name whole (the
     * note mark says what it is: the glossary never shortens the note's name to 「字条」).
     */
    @Test
    public void titlesWithPartsHaveAShortFormThatKeepsTheName() {
        assertEquals("岳羽由加莉", StepCopy.shortTitle("结缘网站的字条 · 岳羽由加莉"));
        assertEquals("舞子", StepCopy.shortTitle("倒悬者 · 舞子"));
        assertEquals("伊织顺平（第 2 话）", StepCopy.shortTitle("羁绊剧情 · 伊织顺平（第 2 话）"));
        assertNull(StepCopy.shortTitle("在宿舍房间学习"));
        assertNull(StepCopy.shortTitle(null));
        FakePlannerApi fake = new FakePlannerApi();
        fake.rules = FakePlannerApi.miniRules();
        fake.plan.steps.set(3, FakePlannerApi.noteStep(S + 3));
        fake.observeAt("note:priestess");
        CardModel note = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(fake.rules);
        assertEquals("结缘网站的字条 · 山岸风花", note.title);
        assertEquals("山岸风花", note.titleShort);
        assertEquals(CardModel.TitleIcon.NOTE, note.titleIcon);
    }

    @Test
    public void noteEpisodeAndSideStepsNeedTheResultSheet() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.plan.steps.set(3, FakePlannerApi.noteStep(S + 3));
        fake.observeAt("note:priestess");
        assertEquals(PlanView.Done.RESULT, PlanView.resolve(snapshot(fake), -1, null, NOW).done);
        fake.plan.steps.set(2, FakePlannerApi.episodeStep(S + 2));
        fake.observeAt("episode:junpei");
        assertEquals(PlanView.Done.RESULT, PlanView.resolve(snapshot(fake), -1, null, NOW).done);
        // Training with a payment done 顺便 in the same slot: the player may have skipped the payment.
        fake = new FakePlannerApi();
        Plan.Step study = fake.plan.steps.get(1);
        study.side.add("unlock:devil:0");
        study.sideZh.add("顺便：把 20,000 日元交给穿西装的男人");
        fake.observeAt("act:study_room");
        assertEquals(PlanView.Done.RESULT, PlanView.resolve(snapshot(fake), -1, null, NOW).done);
        assertFalse(PlanView.isDirect(study));
        assertFalse(PlanView.isDirect(null));
    }

    @Test
    public void theFreeSlotBeingPlayedIsAnItemSoDoneNeverSkipsAhead() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.observed.slot = S + 8; // 自由时间, then Tartarus
        PlanView view = PlanView.resolve(snapshot(fake), -1, null, NOW);
        assertEquals(PlanView.Mode.ITEM, view.mode);
        assertEquals(S + 8, view.item.slot);
        assertEquals(PlanView.Done.RESULT, view.done);
        CardModel card = view.toCard(null);
        assertEquals("自由活动", card.title);
        assertNull(card.tag);
        assertEquals("这个时段想做什么都行", card.cue);
        assertEquals(List.of(CardModel.Tick.CURRENT, CardModel.Tick.PENDING), card.ticks);
        // Free slots after the observed one are still skipped.
        assertEquals(-1, PlanItems.indexOf(PlanItems.of(fake.plan, S), S + 8));
    }

    @Test
    public void receiptShowsOnlyWhileFreshAndNothingElseWasRecorded() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.observeAt("meet:emperor");
        WidgetPrefs.Receipt r = new WidgetPrefs.Receipt();
        r.stateRevision = fake.stateRevision;
        r.slot = S + 1;
        r.title = "在宿舍房间学习";
        r.subtitle = "学力 +2";
        r.kind = "stat";
        r.createdMillis = NOW - 1000;
        PlanView view = PlanView.resolve(snapshot(fake), -1, r, NOW);
        assertEquals(PlanView.Mode.RECEIPT, view.mode);
        assertEquals(PlanView.Done.UNDO, view.done);
        CardModel card = view.toCard(null);
        assertEquals("撤销", card.doneLabel);
        assertEquals(CardModel.DoneIcon.UNDO, card.doneIcon);
        assertEquals("✓ 已记录 · 再点撤销", card.cue);
        assertFalse(card.prevEnabled);
        assertTrue(card.nextEnabled);

        assertEquals(PlanView.Mode.ITEM, PlanView.resolve(snapshot(fake), -1, r, NOW + PlanView.RECEIPT_TTL_MS).mode);
        fake.stateRevision++;
        assertEquals(PlanView.Mode.ITEM, PlanView.resolve(snapshot(fake), -1, r, NOW).mode);
    }

    @Test
    public void cardCopyIsCompleteAndNatural() {
        FakePlannerApi fake = new FakePlannerApi();
        CardModel social = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null);
        assertEquals("倒悬者 · 舞子", social.title);
        assertEquals("RANK 4→5", social.tag);
        assertEquals("长鸣神社", social.location);
        assertEquals("舞子只在周日白天出现", social.cue);
        assertEquals("11/1", social.dateText);
        assertEquals("周日", social.weekdayZh);
        assertEquals("白天", social.periodZh);
        assertEquals(1, social.watermarkDay);
        assertEquals("今天", social.progressLabel);
        assertEquals("今天：白天正在显示，晚上待做", social.progressDescription);
        assertEquals(List.of(CardModel.Tick.CURRENT, CardModel.Tick.PENDING), social.ticks);
        assertFalse(social.night);

        fake.observeAt("act:study_room");
        CardModel stat = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null);
        assertTrue(stat.night);
        assertEquals(List.of(CardModel.Tick.DONE, CardModel.Tick.CURRENT), stat.ticks);

        fake.observeAt("locked:full_moon");
        CardModel locked = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null);
        assertEquals("满月作战", locked.title);
        assertNull("the lock is said once, on the cue line", locked.tag);
        assertEquals("这段时间不能自由行动", locked.cue);
        assertEquals("下一项：这段时间不能自由行动，不用记录，看之后要做的安排", locked.doneDescription);

        // A run that spans days says until when.
        fake.plan.steps.set(8, FakePlannerApi.step(S + 8, "locked:story", "locked", "剧情事件", "剧情安排，不能自由行动", null, null));
        fake.observeAt("locked:story");
        CardModel run = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null);
        assertEquals("剧情事件", run.title);
        assertEquals("到11月5日都不能自由行动", run.cue);
        // A specific engine note is kept instead of the generic line.
        fake.plan.steps.get(5).subtitleZh = "白色的花交给顺平的话，这天会有剧情";
        fake.observeAt("locked:full_moon");
        assertEquals("白色的花交给顺平的话，这天会有剧情", PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null).cue);
    }

    @Test
    public void engineSubtitlesBecomeShortChips() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.observeAt("act:study_room");
        assertEquals("学力 +2", card(fake).tag);
        fake.observeAt("act:job_chagall");
        assertEquals("+3,000 日元", card(fake).tag);
        fake.observeAt("unlock:temperance:0");
        CardModel unlock = card(fake);
        assertEquals("解锁社群", unlock.tag);
        fake.observeAt("tower:4");
        CardModel tower = card(fake);
        assertEquals("探索塔尔塔罗斯", tower.title);
        assertEquals("本区块第 2 次", tower.tag);
        assertEquals("奢豪之庭 茨尔阿（145–172F）", tower.location);

        // Longer engine wording (current and earlier) never reaches the chip whole.
        Plan.Step stat = FakePlannerApi.step(S + 1, "act:study_room", "stat", "在宿舍房间学习", "魅力 +4（升到 Lv.2） · 1,500 日元", "宿舍", null);
        assertEquals("魅力 +4 → Lv.2", StepCopy.of(stat, null, null, null).tag);
        stat.subtitleZh = "学力 +2（升到 Lv.6） · 免费";
        assertEquals("学力 +2 → MAX", StepCopy.of(stat, null, null, null).tag);
        stat.subtitleZh = "魅力 +2 · 花费 500 日元";
        assertEquals("魅力 +2", StepCopy.of(stat, null, null, null).tag);
        Plan.Step job = FakePlannerApi.step(S + 3, "act:job_chagall", "job", "在夏卡尔咖啡店打工", "预计 +2,500 日元 · 魅力 +1、勇气 +1", null, null);
        assertEquals("+2,500 日元", StepCopy.of(job, null, null, null).tag);
        Plan.Step invite = FakePlannerApi.step(S, "meet:hanged", "social", "倒悬者 · 舞子", "休息日邀约 · 加深关系（未升级）", "应邀外出", null);
        assertEquals("见面", StepCopy.of(invite, null, null, null).tag);
        FakePlannerApi.rank(invite, 4, 4);
        assertEquals("RANK 4 · 见面", StepCopy.of(invite, null, null, null).tag);
        FakePlannerApi.rank(invite, 9, 10);
        assertEquals("RANK 9→MAX", StepCopy.of(invite, null, null, null).tag);
        Plan.Step newTower = FakePlannerApi.step(S + 9, "tower:4", "tower", "探索塔尔塔罗斯", "世俗之庭 台伯勒（2–22F） · 救出舞子", "塔尔塔罗斯", null);
        StepCopy t = StepCopy.of(newTower, null, null, null);
        assertEquals("探索塔尔塔罗斯", t.title);
        assertEquals("救出舞子", t.tag);
        assertEquals("世俗之庭 台伯勒（2–22F）", t.location);
        Plan.Step talk = FakePlannerApi.step(S + 4, "unlock:sun:0", "unlock", "太阳 · 神木秋成", "去喷泉广场找那个胖乎乎的学生 · 花费 1,000 日元", "长鸣神社", "完成后太阳社群就解锁了");
        StepCopy u = StepCopy.of(talk, null, null, null);
        assertEquals("解锁社群", u.tag);
        assertEquals("the instruction moves to the cue", "去喷泉广场找那个胖乎乎的学生 · 花费 1,000 日元", u.instruction);
    }

    @Test
    public void noteAndEpisodeStepsHaveTheirOwnTitles() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.rules = FakePlannerApi.miniRules();
        fake.plan.steps.set(3, FakePlannerApi.noteStep(S + 3));
        fake.observeAt("note:priestess");
        CardModel note = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(fake.rules);
        assertEquals("结缘网站的字条 · 山岸风花", note.title);
        assertEquals(CardModel.TitleIcon.NOTE, note.titleIcon);
        assertEquals("RANK 6 · 加好感", note.tag);
        assertEquals("桐叶购物中心 · 放纵俱乐部", note.location);
        assertEquals("当晚回宿舍用公用电脑给山岸风花加好感", note.hint);
        assertEquals("the hint already says how, so the cue keeps the engine's reason",
                "风花的见面机会不多了，用字条补上好感", note.cue);
        assertEquals("完成：记录「结缘网站的字条 · 山岸风花」的结果", note.doneDescription);
        // Without a hint the cue says how to use it; without the rule pack the title still reads well.
        Plan.Step bare = FakePlannerApi.noteStep(S + 3);
        bare.hintZh = null;
        bare.titleZh = "";
        StepCopy noRules = StepCopy.of(bare, null, null, null);
        assertEquals("结缘网站的字条", noRules.title);
        assertEquals(StepCopy.NOTE_HOW, noRules.instruction);
        assertEquals("结缘网站的字条 · 山岸风花", StepCopy.of(bare, fake.rules, null, null).title);

        fake.plan.steps.set(2, FakePlannerApi.episodeStep(S + 2));
        fake.observeAt("episode:junpei");
        CardModel episode = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(fake.rules);
        assertEquals("羁绊剧情 · 伊织顺平（第 2 话）", episode.title);
        assertEquals(CardModel.TitleIcon.EPISODE, episode.titleIcon);
        assertNull("第 2 话 is already in the title", episode.tag);
        assertEquals("月光馆学园 2楼 教室", episode.location);
        // The number comes from the episodes watched plus earlier plan steps when the engine omits it.
        Plan.Step unnumbered = FakePlannerApi.episodeStep(S + 4);
        unnumbered.subtitleZh = "";
        fake.plan.steps.set(4, unnumbered);
        fake.observed.episodes = new int[]{1};
        assertEquals("羁绊剧情 · 伊织顺平（第 3 话）", StepCopy.of(unnumbered, fake.rules, fake.observed, fake.plan).title);
    }

    /** docs/GLOSSARY_zh.md: 「羁绊剧情 · 伊织顺平（第 2 话）」, as 记录历史 and 改做别的 write it. */
    @Test
    public void episodeTitlesUseTheGlossaryForm() {
        assertEquals("羁绊剧情 · 伊织顺平（第 2 话）", StepCopy.episodeTitle("伊织顺平", 2));
        assertEquals("羁绊剧情 · 伊织顺平", StepCopy.episodeTitle("伊织顺平", -1));
        assertEquals("羁绊剧情（第 1 话）", StepCopy.episodeTitle(null, 1));
        Rules rules = FakePlannerApi.miniRules();
        // The current engine wording: the number only in the subtitle.
        Plan.Step engine = FakePlannerApi.step(S, "episode:junpei", "episode", "羁绊剧情 · 伊织顺平", "第 2 话（共 4 话）", null, null);
        for (Rules r : new Rules[]{rules, null}) {
            StepCopy c = StepCopy.of(engine, r, null, null);
            assertEquals("羁绊剧情 · 伊织顺平（第 2 话）", c.title);
            assertNull(c.tag);
        }
        // The app's own title (改做别的) reads the same on the widget, with or without the rule pack.
        Plan.Step app = FakePlannerApi.step(S, "episode:junpei", "episode", "羁绊剧情 · 伊织顺平（第 2 话）", "不加社群点数", null, null);
        assertEquals("羁绊剧情 · 伊织顺平（第 2 话）", StepCopy.of(app, rules, null, null).title);
        assertEquals("羁绊剧情 · 伊织顺平（第 2 话）", StepCopy.of(app, null, null, null).title);
        assertEquals("不加社群点数", StepCopy.of(app, null, null, null).tag);
        assertEquals("伊织顺平", StepCopy.personFromTitle("羁绊剧情 · 伊织顺平（第 2 话）"));

        FakePlannerApi fake = new FakePlannerApi();
        fake.rules = rules;
        fake.plan.steps.set(2, FakePlannerApi.episodeStep(S + 2));
        fake.observeAt("episode:junpei");
        CardModel card = card(fake);
        assertFalse(card.title, card.title.contains(" · 第"));
        assertEquals("完成：记录「羁绊剧情 · 伊织顺平（第 2 话）」的结果", card.doneDescription);
    }

    /** docs/GLOSSARY_zh.md: 「升到 MAX」「RANK 9 → MAX」, a link at MAX is just "MAX", never "RANK MAX". */
    @Test
    public void maxIsNeverWrittenRankMax() {
        Plan.Step meet = FakePlannerApi.rank(FakePlannerApi.step(S, "meet:hanged", "social", "倒悬者 · 舞子",
                "休息日邀约 · RANK MAX · 见面（预计不升级）", "应邀外出", "对方发来了休息日邀约，赴约也算一次见面"), 10, 10);
        assertEquals("MAX · 见面", StepCopy.of(meet, null, null, null).tag);
        meet.predicted = new Plan.Predicted();
        assertEquals("MAX · 见面", StepCopy.of(meet, null, null, null).tag);
        meet.subtitleZh = "RANK 9 → RANK MAX";
        assertEquals("RANK 9→MAX", StepCopy.of(meet, null, null, null).tag);
        meet.subtitleZh = "RANK 9 → MAX";
        assertEquals("RANK 9→MAX", StepCopy.of(meet, null, null, null).tag);
        assertEquals("预计这次见面能升到 MAX", StepCopy.glossary("预计这次见面能升到 RANK MAX"));
        assertEquals("皇帝 RANK 9 → MAX", StepCopy.glossary("皇帝 RANK 9 → RANK MAX"));

        // Every line of a card: an older plan's sentences are put in the glossary's words.
        FakePlannerApi fake = new FakePlannerApi();
        Plan.Step emperor = FakePlannerApi.rank(FakePlannerApi.step(S + 2, "meet:emperor", "social", "皇帝 · 小田桐秀利",
                "RANK 9 → RANK MAX", "月光馆学园 3楼 学生会室", "「必须」目标，这次见面一定能升到 RANK MAX"), 9, 10);
        emperor.hintZh = "升到 RANK MAX 前记得带上「皇帝」阿尔卡那的人格面具";
        FakePlannerApi.side(emperor, "unlock:devil:0", "顺便：把 20,000 日元交给穿西装的男人");
        fake.plan.steps.set(2, emperor);
        fake.observeAt("meet:emperor");
        CardModel card = card(fake);
        assertEquals("RANK 9→MAX", card.tag);
        assertEquals("「必须」目标，这次见面一定能升到 MAX", card.cue);
        assertEquals("升到 MAX 前记得带上「皇帝」阿尔卡那的人格面具", card.hint);
        for (String text : new String[]{card.title, card.tag, card.cue, card.hint, card.side, card.taskDescription, card.doneDescription}) {
            assertFalse(text, text.contains("RANK MAX"));
        }
    }

    @Test
    public void catchUpUsesTheAppsWording() {
        FakePlannerApi fake = new FakePlannerApi();
        PlanView future = PlanView.resolve(snapshot(fake), S + 1, null, NOW);
        assertEquals(PlanView.Done.CATCHUP, future.done);
        CardModel card = future.toCard(null);
        assertEquals("已经玩到这里", card.doneLabel);
        assertEquals(CardModel.DoneIcon.NONE, card.doneIcon);
        assertEquals("已经玩到这里：补记这一项前面还没记录的安排", card.doneDescription);
    }

    @Test
    public void reminderAndSideStepsAreShownWhenPresent() {
        FakePlannerApi fake = new FakePlannerApi();
        Plan.Step emperor = fake.plan.steps.get(2);
        emperor.hintZh = "记得带上「皇帝」阿尔卡那的人格面具";
        emperor.side.add("unlock:devil:0");
        emperor.sideZh.add("顺便：把 20,000 日元交给穿西装的男人");
        emperor.side.add("unlock:devil:1");
        emperor.sideZh.add("顺便：去书店买那本杂志");
        fake.observeAt("meet:emperor");
        CardModel card = card(fake);
        assertEquals("记得带上「皇帝」阿尔卡那的人格面具", card.hint);
        assertEquals("顺便：把 20,000 日元交给穿西装的男人；去书店买那本杂志", card.side);
        assertTrue(card.taskDescription.contains("记得带上「皇帝」阿尔卡那的人格面具"));
        assertNull(card(new FakePlannerApi()).hint);
        assertNull(card(new FakePlannerApi()).side);
        assertNull(StepCopy.sideLine(List.of(" ", "")));
        assertEquals("顺便：去书店", StepCopy.sideLine(List.of("去书店")));
    }

    private static CardModel card(FakePlannerApi fake) {
        return PlanView.resolve(fake.snapshot(), -1, null, NOW).toCard(fake.rules);
    }

    /**
     * The answers row (DESIGN §18): the scene of the meeting as 「答：」 (the app line read out), the
     * next scene's as 「要是升级了，答：」 when it may rank up after all, none when it certainly does not
     * or without rules (never the generic hint); a side step's pick after it.
     */
    @Test
    public void answersFollowTheSceneOfTheMeeting() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.rules = FakePlannerApi.miniRules();
        fake.rules.link("emperor").dialogue.put(7, java.util.Arrays.asList("我会帮你的", "这样的规矩没有意义"));
        Plan.Step emperor = fake.plan.steps.get(2);
        emperor.entity = "emperor";
        fake.observeAt("meet:emperor");
        CardModel up = card(fake);
        assertEquals(1, up.answers.size());
        assertEquals("答：我会帮你的 / 这样的规矩没有意义", up.answers.get(0).text());
        assertFalse(up.answers.get(0).conditional);
        assertTrue(up.taskDescription, up.taskDescription.contains("参考回答：我会帮你的 / 这样的规矩没有意义"));

        emperor.predicted.rankTo = 6;
        emperor.predicted.rankUp = false;
        CardModel maybe = card(fake);
        assertEquals("要是升级了，答：我会帮你的 / 这样的规矩没有意义", maybe.answers.get(0).text());
        assertTrue(maybe.answers.get(0).conditional);
        assertEquals("升级时答：", maybe.answers.get(0).shortLabel);
        assertTrue(maybe.taskDescription.contains("要是升级了，答：我会帮你的 / 这样的规矩没有意义"));
        emperor.predicted.certain = true;
        assertTrue("no rank-up for certain: no scene", card(fake).answers.isEmpty());
        emperor.predicted.certain = false;
        emperor.assumptions.add("rank_hold");
        assertTrue("held: no scene", card(fake).answers.isEmpty());
        emperor.assumptions.clear();

        // A side step with a pick (a justice talk) adds its line; the side row keeps its own text.
        Rules.Link justice = new Rules.Link();
        justice.id = "justice";
        justice.idx = 2;
        Rules.Step talk = new Rules.Step();
        talk.howZh = "放学后到学生会活动室门口找伏见千寻，选「有话想和你说」。这一步不会消耗时间。";
        justice.unlock.steps.add(talk);
        fake.rules.links = new Rules.Link[]{fake.rules.links[0], fake.rules.links[1], justice};
        FakePlannerApi.side(emperor, "unlock:justice:0", "顺便：和伏见千寻搭话（第 1 次）");
        CardModel side = card(fake);
        assertEquals("顺便选：有话想和你说", side.answers.get(1).text());
        assertEquals("顺便：和伏见千寻搭话（第 1 次）", side.side);

        FakePlannerApi none = new FakePlannerApi();
        none.observeAt("meet:emperor");
        assertTrue("without rules only class and exam answers could show", card(none).answers.isEmpty());
    }

    /** 当日提醒 on the card: answers (class, exam) last in the answers row, the rest by priority; all read out. */
    @Test
    public void remindersOfTheCardsSlots() {
        FakePlannerApi fake = new FakePlannerApi();
        int nov2 = Rules.dayOf(S + 2);
        ReminderFixtures.with(fake.plan,
                ReminderFixtures.classAnswer(nov2, "马", 2),
                ReminderFixtures.feature(nov2),
                ReminderFixtures.nurse(nov2));
        fake.observeAt("meet:emperor");
        CardModel card = card(fake);
        assertEquals("课堂：马（第 2 项）", card.answers.get(card.answers.size() - 1).text());
        assertEquals(2, card.reminders.size());
        assertEquals("放学后先去保健室：勇气 +2，不会消耗时间", card.reminders.get(0).text);
        assertEquals("保健室：勇气 +2", card.reminders.get(0).shortText);
        assertEquals("真宵堂今天开张了", card.reminders.get(1).text);
        for (Plan.Reminder r : fake.plan.reminders) assertTrue(r.textZh, card.taskDescription.contains(r.textZh));
        // The evening card of the same day carries none of them.
        fake.observeAt("act:job_chagall");
        assertTrue(card(fake).reminders.isEmpty());
        assertTrue(card(fake).answers.isEmpty());
        // An exam answer without its own field reads as its short form.
        Plan.Reminder bare = ReminderFixtures.exam(nov2, "五月病", false);
        bare.answerZh = null;
        assertEquals("考试：五月病", PlanView.answerLine(bare).text());
    }

    @Test
    public void textHelpersNeverLeakInternals() {
        assertEquals("RANK 6→7", PlanView.compactTag("RANK 6 → 7"));
        assertNull(PlanView.compactTag("  "));
        assertEquals("打工", PlanView.titleOf("", "job"));
        assertEquals("探索塔尔塔罗斯", PlanView.titleOf(null, "tower"));
        assertEquals("自由活动", PlanView.titleOf(" ", "act:weird_internal_id"));
        assertEquals("今天", PlanView.relativeDay(10, 10));
        assertEquals("明天", PlanView.relativeDay(11, 10));
        assertEquals("后天", PlanView.relativeDay(12, 10));
        assertEquals("5天后", PlanView.relativeDay(15, 10));
        assertEquals("昨天", PlanView.relativeDay(9, 10));
        assertEquals("3天前", PlanView.relativeDay(7, 10));
        // Engine sentences from older plans are put in the glossary's words.
        assertEquals("女皇社群需要学力达到 MAX", StepCopy.glossary("女皇社群需要学力达到 Lv.6"));
        assertEquals("这次见面就能升到 MAX", StepCopy.glossary("这次见面就能练满"));
        assertEquals("完成后太阳社群就解锁了", StepCopy.glossary("完成后太阳社群就正式开始了"));
        assertEquals("解锁恶魔社群前的准备（第 1 步，共 3 步）", StepCopy.glossary("开启恶魔社群前的准备（第 1 步，共 3 步）"));
        assertEquals("魅力达到 Lv.5", StepCopy.glossary("魅力达到 Lv.5"));
        assertNull(StepCopy.glossary(null));
    }

    @Test
    public void stepWithoutTitleStillReadsWell() {
        FakePlannerApi fake = new FakePlannerApi();
        Plan.Step bare = FakePlannerApi.step(S, "meet:hanged", "social", "", "", null, null);
        fake.plan.steps.set(0, bare);
        CardModel card = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null);
        assertEquals("社群", card.title);
        assertNull(card.tag);
        assertNull(card.location);
    }
}
