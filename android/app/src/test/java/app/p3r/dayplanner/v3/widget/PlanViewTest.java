package app.p3r.dayplanner.v3.widget;

import org.junit.Test;

import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.service.PlannerApi;

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
        assertEquals(PlanView.Done.NONE, view.done);
        CardModel card = view.toCard(null);
        assertEquals("暂时读不到安排", card.title);
        assertEquals("游戏资料读取失败，请重新打开应用", card.cue);
        assertFalse(card.doneEnabled);
        assertFalse("a dead 当前 is drawn disabled", card.currentEnabled);
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
        assertEquals("never offer 已玩到这里 on the next slot", PlanView.Mode.PLANNING, view.mode);
        assertEquals(PlanView.Done.NONE, view.done);
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
        assertEquals(PlanView.Done.NONE, locked.done);
        assertFalse(locked.toCard(null).doneEnabled);
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
        assertEquals("这段时间不能自由行动，不用记录", locked.doneDescription);

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
        Plan.Step invite = FakePlannerApi.step(S, "meet:hanged", "social", "倒悬者 · 舞子", "假日邀约 · 加深关系（未升级）", "应邀外出", null);
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
        assertEquals("羁绊剧情 · 伊织顺平 · 第 2 话", episode.title);
        assertEquals(CardModel.TitleIcon.EPISODE, episode.titleIcon);
        assertNull("第 2 话 is already in the title", episode.tag);
        assertEquals("月光馆学园 2楼 教室", episode.location);
        // The number comes from the episodes watched plus earlier plan steps when the engine omits it.
        Plan.Step unnumbered = FakePlannerApi.episodeStep(S + 4);
        unnumbered.subtitleZh = "";
        fake.plan.steps.set(4, unnumbered);
        fake.observed.episodes = new int[]{1};
        assertEquals("羁绊剧情 · 伊织顺平 · 第 3 话", StepCopy.of(unnumbered, fake.rules, fake.observed, fake.plan).title);
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
