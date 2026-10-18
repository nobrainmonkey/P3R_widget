package app.p3r.dayplanner.v3.widget;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.ui.ReminderFixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Which card carries a 当日提醒 (spec 3.1.0 §1.2.7, docs/DESIGN_v3.md §17.2): the item covering its slot,
 * else the next item of the same day, else none. Every story day has its own card. Plain JVM.
 */
public class PlanItemsTest {
    private static final int S = FakePlannerApi.FIRST_SLOT;
    private static final int NOV_1 = Rules.dayOf(S);

    private static List<String> ids(PlanItems.Item item) {
        List<String> out = new ArrayList<>();
        for (Plan.Reminder r : item.reminders) out.add(r.id);
        return out;
    }

    @Test
    public void aReminderRidesOnTheCardOfItsSlotOrTheNextOneThatDay() {
        Plan plan = FakePlannerApi.cannedPlan("p");
        // 11/1 day (舞子), 11/1 evening (study), 11/5 day (free slot → 11/5 evening tower).
        ReminderFixtures.with(plan, ReminderFixtures.tv(NOV_1), ReminderFixtures.rescueTonight(NOV_1),
                ReminderFixtures.nurse(NOV_1 + 4), ReminderFixtures.feature(NOV_1 + 5));
        List<PlanItems.Item> items = PlanItems.of(plan, S);
        assertEquals(List.of("tv.11-01"), ids(items.get(0)));
        assertEquals(List.of("rescue.11-01.tower"), ids(items.get(1)));
        PlanItems.Item tower = items.get(PlanItems.indexOf(items, S + 9));
        assertEquals("the free day slot's reminder goes on the evening card", List.of("nurse.11-05"), ids(tower));
        int carried = 0;
        for (PlanItems.Item item : items) carried += item.reminders.size();
        assertEquals("11/6 has no card: none carries it (the app still lists it)", 3, carried);
        // From the evening on, the day's reminders are behind.
        List<PlanItems.Item> later = PlanItems.of(plan, S + 1);
        assertEquals(List.of("rescue.11-01.tower"), ids(later.get(0)));
        // The observed free slot is an item and carries its own.
        assertEquals(List.of("nurse.11-05"), ids(PlanItems.of(plan, S + 8).get(0)));
    }

    /** Every locked day remains visible, with its own answer when one exists. */
    @Test
    public void lockedRunsSplitAtEveryNewDayEvenWithoutReminders() {
        Plan plan = examWeek();
        List<PlanItems.Item> merged = PlanItems.of(plan, S);
        assertEquals("three story days, then the meeting, the training and Tartarus", 6, merged.size());
        assertEquals(S + 1, merged.get(0).lastSlot);

        ReminderFixtures.with(plan, ReminderFixtures.exam(NOV_1, "五月病", false), ReminderFixtures.exam(NOV_1 + 1, "摩西", false));
        List<PlanItems.Item> split = PlanItems.of(plan, S);
        assertEquals(6, split.size());
        assertEquals(S, split.get(0).slot);
        assertEquals(S + 1, split.get(0).lastSlot);
        assertEquals(List.of("exam.11-01"), ids(split.get(0)));
        assertEquals(List.of("exam.11-02"), ids(split.get(1)));
        assertEquals(S + 3, split.get(1).lastSlot);
        assertEquals("the day without an answer still has its own card", S + 4, split.get(2).slot);
        assertEquals(S + 5, split.get(2).lastSlot);
        assertEquals("meet:emperor", split.get(3).step.action);
        assertTrue(split.get(3).reminders.isEmpty());
    }

    /** Spec test 7: the class answer rides on the daytime card only, not on the evening's. */
    @Test
    public void theClassAnswerIsOnlyOnTheDaytimeCard() {
        Plan plan = FakePlannerApi.cannedPlan("p");
        ReminderFixtures.with(plan, ReminderFixtures.classAnswer(NOV_1, "朱诺", 3));
        List<PlanItems.Item> items = PlanItems.of(plan, S);
        assertEquals(List.of("class.11-01"), ids(items.get(0)));
        assertTrue(items.get(1).reminders.isEmpty());
    }

    /** 11/1–11/3 all locked (exam), then 11/4 a meeting. */
    private static Plan examWeek() {
        Plan plan = FakePlannerApi.cannedPlan("p");
        for (int i = 0; i < 6; i++) {
            plan.steps.set(i, FakePlannerApi.step(S + i, "locked:exam", "locked", "期中考试", "剧情安排，不能自由行动", null, null));
        }
        plan.steps.set(6, FakePlannerApi.rank(FakePlannerApi.step(S + 6, "meet:emperor", "social", "皇帝 · 小田桐秀利",
                "RANK 6 → 7", "月光馆学园 3楼 学生会室", null), 6, 7));
        plan.steps.set(7, FakePlannerApi.step(S + 7, "act:study_room", "stat", "在宿舍房间学习", "学力 +2 · 免费", "宿舍 · 自己房间", null));
        return plan;
    }
}
