package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@link Plan#reminders} contract (spec 3.1.0 §2, DESIGN §17): every field survives the JSON
 * round trip, unset optional fields and an empty list write no key, and a plan stored before
 * 3.1.0 (no "reminders" key) still parses.
 */
public class PlanReminderJsonTest {
    private static final int JUL_8 = 98;
    private static final int JUL_17 = 107;
    private static final int JUN_18 = 78;
    private static final int JUL_6 = 96;
    private static final int JUL_18 = 108;

    private static Plan.Reminder classAnswer() {
        Plan.Reminder r = new Plan.Reminder();
        r.id = "class.07-08";
        r.kind = Plan.Reminder.CLASS;
        r.labelZh = "课堂提问";
        r.textZh = "课堂提问：选「马」（第 2 项）";
        r.slot = Rules.slot(JUL_8, false);
        r.shortZh = "课堂：马（第 2 项）";
        r.answerZh = "马";
        r.option = 2;
        r.gainZh = "魅力 +2";
        r.priority = Plan.Reminder.MISSABLE;
        return r;
    }

    private static Plan.Reminder examEstimate() {
        Plan.Reminder r = new Plan.Reminder();
        r.id = "exam.07-17";
        r.kind = Plan.Reminder.EXAM;
        r.labelZh = "考试";
        r.textZh = "考试：选「MADRID」";
        r.slot = Rules.slot(JUL_17, false);
        r.shortZh = "考试：MADRID";
        r.answerZh = "MADRID";
        r.detailZh = "这条答案只有一份攻略写过，游戏里的写法可能略有不同。";
        r.priority = Plan.Reminder.MISSABLE;
        r.estimate = true;
        return r;
    }

    private static Plan.Reminder rescueStart() {
        Plan.Reminder r = new Plan.Reminder();
        r.id = "rescue.06-18.start";
        r.kind = Plan.Reminder.RESCUE;
        r.labelZh = "失踪者";
        r.textZh = "有 3 名失踪者，期限 7月6日";
        r.slot = Rules.slot(JUN_18, false);
        r.shortZh = "失踪者 3 人 · 期限 7月6日";
        r.detailZh = "在塔尔塔罗斯里找到他们就能救出，能拿到奖励。期限当天还算不算没核对过，最好提前去。";
        r.priority = Plan.Reminder.DEADLINE;
        r.untilDay = JUL_6;
        return r;
    }

    /** Only the fields every reminder has; the optional ones keep their defaults. */
    private static Plan.Reminder bare() {
        Plan.Reminder r = new Plan.Reminder();
        r.id = "feature.07-18";
        r.kind = Plan.Reminder.FEATURE;
        r.labelZh = "新开放";
        r.textZh = "真宵堂今天开张了";
        r.slot = Rules.slot(JUL_18, false);
        return r;
    }

    private static Plan plan(Plan.Reminder... reminders) {
        Plan p = new Plan();
        p.planId = "plan-1";
        p.fromSlot = Rules.slot(JUN_18, false);
        p.toSlot = Rules.slot(JUL_18, true);
        for (Plan.Reminder r : reminders) p.reminders.add(r);
        return p;
    }

    private static Plan roundTrip(Plan p) throws Exception {
        return Plan.fromJson(new JSONObject(p.toJson().toString()));
    }

    private static void assertSameReminder(Plan.Reminder want, Plan.Reminder got) {
        assertEquals(want.id, got.id);
        assertEquals(want.id, want.kind, got.kind);
        assertEquals(want.id, want.labelZh, got.labelZh);
        assertEquals(want.id, want.textZh, got.textZh);
        assertEquals(want.id, want.slot, got.slot);
        assertEquals(want.id, want.shortZh, got.shortZh);
        assertEquals(want.id, want.answerZh, got.answerZh);
        assertEquals(want.id, want.option, got.option);
        assertEquals(want.id, want.detailZh, got.detailZh);
        assertEquals(want.id, want.gainZh, got.gainZh);
        assertEquals(want.id, want.priority, got.priority);
        assertEquals(want.id, want.untilDay, got.untilDay);
        assertEquals(want.id, want.estimate, got.estimate);
    }

    private static Set<String> keys(JSONObject o) {
        Set<String> out = new TreeSet<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    @Test
    public void everyFieldSurvivesTheRoundTripInOrder() throws Exception {
        Plan p = plan(rescueStart(), classAnswer(), examEstimate(), bare());
        Plan back = roundTrip(p);
        assertEquals(4, back.reminders.size());
        for (int i = 0; i < p.reminders.size(); i++) {
            assertSameReminder(p.reminders.get(i), back.reminders.get(i));
        }
        assertEquals("plan-1", back.planId);
        assertTrue(back.toJson().has("reminders"));
    }

    @Test
    public void emptyListAndUnsetFieldsWriteNoKey() throws Exception {
        assertFalse(plan().toJson().has("reminders"));
        assertTrue(roundTrip(plan()).reminders.isEmpty());

        JSONObject bare = plan(bare()).toJson().getJSONArray("reminders").getJSONObject(0);
        assertEquals(new TreeSet<>(List.of("id", "kind", "slot", "label", "text", "priority")), keys(bare));
        Plan.Reminder back = roundTrip(plan(bare())).reminders.get(0);
        assertNull(back.shortZh);
        assertNull(back.answerZh);
        assertNull(back.detailZh);
        assertNull(back.gainZh);
        assertEquals(0, back.option);
        assertEquals(Plan.Reminder.NICE, back.priority);
        assertEquals(-1, back.untilDay);
        assertFalse(back.estimate);

        // The full key set of the contract: id kind slot label text short answer option detail gain priority until estimate.
        Plan.Reminder all = classAnswer();
        all.detailZh = "课堂上被点名时选这个。";
        all.untilDay = JUL_8;
        all.estimate = true;
        JSONObject full = plan(all).toJson().getJSONArray("reminders").getJSONObject(0);
        assertEquals(new TreeSet<>(List.of("id", "kind", "slot", "label", "text", "short", "answer", "option",
                "detail", "gain", "priority", "until", "estimate")), keys(full));
    }

    @Test
    public void planWithoutTheKeyStillParses() throws Exception {
        String stored = "{\"schema\":\"p3r.plan.v3\",\"planId\":\"old\",\"stateRevision\":3,\"profileRevision\":2,"
                + "\"rulesVersion\":\"3.0.0|163881082b41\",\"fromSlot\":40,\"toSlot\":41,\"budgetExhausted\":false,"
                + "\"steps\":[{\"slot\":40,\"action\":\"free\",\"kind\":\"free\",\"title\":\"\",\"subtitle\":\"\"},"
                + "{\"slot\":41,\"action\":\"free\",\"kind\":\"free\",\"title\":\"\",\"subtitle\":\"\"}],"
                + "\"summary\":{\"notes\":[]}}";
        Plan p = Plan.fromJson(new JSONObject(stored));
        assertEquals("old", p.planId);
        assertEquals(2, p.steps.size());
        assertTrue(p.reminders.isEmpty());
        assertTrue(p.remindersIn(0, Integer.MAX_VALUE).isEmpty());
        assertFalse(p.toJson().has("reminders"));
    }

    @Test
    public void remindersInKeepsListOrderWithinBounds() {
        Plan p = plan(rescueStart(), classAnswer(), examEstimate(), bare());
        List<String> ids = new ArrayList<>();
        for (Plan.Reminder r : p.remindersIn(Rules.slot(JUL_8, false), Rules.slot(JUL_17, false))) ids.add(r.id);
        assertEquals(List.of("class.07-08", "exam.07-17"), ids);
        assertTrue(p.remindersIn(Rules.slot(JUL_8, true), Rules.slot(JUL_17, false) - 1).isEmpty());
        assertEquals(4, p.remindersIn(p.fromSlot, p.toSlot).size());
    }

    @Test
    public void onlyClassAndExamAreAnswers() {
        assertTrue(classAnswer().isAnswer());
        assertTrue(examEstimate().isAnswer());
        assertFalse(rescueStart().isAnswer());
        assertFalse(bare().isAnswer());
    }
}
