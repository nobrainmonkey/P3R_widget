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
        assertEquals(PlanView.Mode.ERROR, PlanView.unavailable().mode);
        assertEquals("暂时读不到安排", PlanView.unavailable().toCard(null).title);
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
        assertEquals("今日", social.progressLabel);
        assertEquals(List.of(CardModel.Tick.CURRENT, CardModel.Tick.PENDING), social.ticks);
        assertFalse(social.night);

        fake.observeAt("act:study_room");
        CardModel stat = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null);
        assertTrue(stat.night);
        assertEquals(List.of(CardModel.Tick.DONE, CardModel.Tick.CURRENT), stat.ticks);

        fake.observeAt("locked:full_moon");
        CardModel locked = PlanView.resolve(snapshot(fake), -1, null, NOW).toCard(null);
        assertEquals("满月作战", locked.title);
        assertNull("subtitle repeated by the cue is dropped", locked.tag);
        assertEquals("剧情推进，无需选择", locked.cue);
    }

    @Test
    public void textHelpersNeverLeakInternals() {
        assertEquals("RANK 6→7", PlanView.compactTag("RANK 6 → 7"));
        assertNull(PlanView.compactTag("  "));
        assertEquals("打工", PlanView.titleOf("", "job"));
        assertEquals("探索塔尔塔罗斯", PlanView.titleOf(null, "tower"));
        assertEquals("自由行动", PlanView.titleOf(" ", "act:weird_internal_id"));
        assertEquals("今日", PlanView.relativeDay(10, 10));
        assertEquals("明天", PlanView.relativeDay(11, 10));
        assertEquals("后天", PlanView.relativeDay(12, 10));
        assertEquals("5天后", PlanView.relativeDay(15, 10));
        assertEquals("昨天", PlanView.relativeDay(9, 10));
        assertEquals("3天前", PlanView.relativeDay(7, 10));
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
