package app.p3r.dayplanner.v3.model;

import static app.p3r.dayplanner.v3.model.PackFixture.day;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;
import java.util.function.Consumer;

/** The optional {@code reminders} section of the rule pack (当日提醒): parsing and strict validation. */
public class RemindersLoaderTest {
    private static final int COURAGE = 2;

    @Test
    public void parsesTheShippedReminders() {
        Rules r = PackFixture.rules();
        Rules.Reminders rem = r.reminders;
        assertFalse(rem.isEmpty());
        assertTrue(rem.items.size() > 90);
        Rules.Reminder horse = item(r, "class.07-08");
        assertEquals("class", horse.kind);
        assertEquals("课堂提问", horse.labelZh);
        assertEquals(day(7, 8), horse.day);
        assertFalse(horse.evening);
        assertEquals("马", horse.answerZh);
        assertEquals(2, horse.option);
        assertEquals(3, horse.priority);
        assertEquals("verified", horse.conf);
        Rules.Reminder club = item(r, "fragment.05-25.club");
        assertTrue(club.evening);
        assertTrue(club.firstEligible);
        assertEquals(Map.of(COURAGE, 2), club.requiresLv);
        Rules.Reminder deadline = item(r, "request.06-06.deadline");
        assertEquals(day(6, 6), deadline.untilDay);
        assertEquals(day(6, 3), deadline.day);
        assertEquals(9_800, item(r, "tv.07-12").costYen);
        assertNull(item(r, "tv.07-12").answerZh);
        for (int i = 1; i < rem.items.size(); i++) {
            Rules.Reminder a = rem.items.get(i - 1);
            Rules.Reminder b = rem.items.get(i);
            int ka = a.day * 2 + (a.evening ? 1 : 0);
            int kb = b.day * 2 + (b.evening ? 1 : 0);
            assertTrue(a.id + " / " + b.id, ka < kb || ka == kb && a.id.compareTo(b.id) < 0);
        }
        assertTrue(rem.dozeDays.length >= 30);
        assertEquals(2, rem.dozeAcademics);
        assertEquals(2, rem.dozeCourage);
        assertEquals(2, rem.nurseCourage);
        assertEquals(13, rem.rescueGroups.size());
        int people = 0;
        for (Rules.RescueGroup g : rem.rescueGroups) people += g.count;
        assertEquals(20, people);
        // The story's own Tartarus evening (4/20) counts for 保健室; the 6/18 rescue keeps request #97 possible.
        assertEquals(1, rem.nurseStoryDays.length);
        assertEquals(day(4, 20), rem.nurseStoryDays[0]);
        Rules.RescueGroup june = rem.rescueGroups.get(0);
        assertEquals("rescue.06-18", june.id);
        assertEquals("救不出来的话，伊莉莎白的第 97 号委托就做不了。", june.locksZh);
        assertNull(rem.rescueGroups.get(1).locksZh);
        Rules.Reminder accept = item(r, "trip.07-19.accept");
        assertTrue(accept.evening);
        assertEquals(day(7, 19), accept.day);
        assertTrue(item(r, "request.05-10").evening);
    }

    @Test
    public void theSectionAndItsBlocksAreOptional() throws JSONException {
        JSONObject root = PackFixture.tree();
        root.remove("reminders");
        Rules none = RulesLoader.parse(root.toString());
        assertTrue(none.reminders.isEmpty());

        root = PackFixture.tree();
        JSONObject rem = root.getJSONObject("reminders");
        rem.remove("doze");
        rem.remove("nurse");
        rem.remove("rescueGroups");
        Rules items = RulesLoader.parse(root.toString());
        assertEquals(0, items.reminders.dozeDays.length);
        assertEquals(0, items.reminders.nurseCourage);
        assertTrue(items.reminders.rescueGroups.isEmpty());
        assertFalse(items.reminders.items.isEmpty());
    }

    // ------------------------------------------------------------------ every failure names its field

    @Test
    public void itemValidationNamesTheField() {
        rejects(x -> put(x, "id", "Class.07-08"), "reminders.items[0].id");
        rejects(x -> put(x, "id", "class.7-8"), "reminders.items[0].id");
        rejects(x -> put(x, "kind", "doze"), "reminders.items[0].kind");
        rejects(x -> put(x, "day", 400), "reminders.items[0].day");
        rejects(x -> put(x, "day", 2), "reminders.items[0].day");
        rejects(x -> put(x, "untilDay", 3), "reminders.items[0].untilDay");
        rejects(x -> put(x, "slot", "night"), "reminders.items[0].slot");
        rejects(x -> put(x, "zh", ""), "reminders.items[0].zh");
        rejects(x -> put(x, "labelZh", JSONObject.NULL), "reminders.items[0].labelZh");
        rejects(x -> put(x, "shortZh", "课堂提问：选「鲤鱼旗」（第 3 项）"), "reminders.items[0].shortZh");
        rejects(x -> put(x, "answerZh", JSONObject.NULL), "reminders.items[0].answerZh");
        rejects(x -> put(x, "option", 5), "reminders.items[0].option");
        rejects(x -> put(x, "priority", 0), "reminders.items[0].priority");
        rejects(x -> put(x, "priority", 4), "reminders.items[0].priority");
        rejects(x -> put(x, "costYen", -1), "reminders.items[0].costYen");
        rejects(x -> put(x, "requires", obj("stamina", 2)), "reminders.items[0].requires");
        rejects(x -> put(x, "requires", obj("courage", 7)), "reminders.items[0].requires");
        rejects(x -> put(x, "firstEligible", true), "reminders.items[0].firstEligible");
        rejects(x -> put(x, "conf", "probably"), "reminders.items[0].conf");
        rejects(x -> put(x, "src", new JSONArray().put("lpbeach")), "reminders.items[0].src");
        rejects(x -> put(x, "src", new JSONArray().put("nowhere")), "reminders.items[0].src[0]");
    }

    @Test
    public void answersAndOptionsBelongToTheirKinds() throws JSONException {
        JSONObject root = PackFixture.tree();
        JSONObject tv = find(root, "tv.05-03");
        tv.put("answerZh", "玫瑰花束");
        expect(root, "answerZh is only for class and exam");

        root = PackFixture.tree();
        find(root, "exam.05-19").put("option", 1);
        expect(root, "option must be 0 except for class");

        root = PackFixture.tree();
        JSONArray items = root.getJSONObject("reminders").getJSONArray("items");
        items.put(new JSONObject(items.getJSONObject(0).toString()));
        expect(root, "is duplicated");
    }

    @Test
    public void blockValidationNamesTheField() throws JSONException {
        JSONObject root = PackFixture.tree();
        JSONArray doze = root.getJSONObject("reminders").getJSONObject("doze").getJSONArray("days");
        doze.put(0, day(4, 26)); // a Sunday
        expect(root, "reminders.doze.days contains day " + day(4, 26));

        root = PackFixture.tree();
        JSONArray days = root.getJSONObject("reminders").getJSONObject("doze").getJSONArray("days");
        int first = days.getInt(0);
        days.put(0, days.getInt(1));
        days.put(1, first);
        expect(root, "reminders.doze.days must be strictly ascending");

        root = PackFixture.tree();
        root.getJSONObject("reminders").getJSONObject("nurse").put("courage", 0);
        expect(root, "reminders.nurse.courage");

        root = PackFixture.tree();
        root.getJSONObject("reminders").getJSONArray("rescueGroups").getJSONObject(0).put("count", 0);
        expect(root, "reminders.rescueGroups[0].count");

        root = PackFixture.tree();
        root.getJSONObject("reminders").getJSONArray("rescueGroups").getJSONObject(0).put("rescueIds", new JSONArray().put("ayako"));
        expect(root, "reminders.rescueGroups[0].rescueIds[0] 'ayako' is not in tartarus.rescues");

        root = PackFixture.tree();
        root.getJSONObject("reminders").getJSONArray("rescueGroups").getJSONObject(0).put("id", "rescue.6-18");
        expect(root, "reminders.rescueGroups[0].id");

        root = PackFixture.tree();
        JSONObject group = root.getJSONObject("reminders").getJSONArray("rescueGroups").getJSONObject(0);
        group.put("toDay", group.getInt("fromDay") - 1);
        expect(root, "reminders.rescueGroups[0] ends before it starts");

        root = PackFixture.tree();
        root.getJSONObject("reminders").getJSONObject("doze").put("src", new JSONArray().put("lpbeach"));
        expect(root, "reminders.doze.src");

        root = PackFixture.tree();
        root.getJSONObject("reminders").getJSONObject("nurse").put("storyTowerDays", new JSONArray().put(day(4, 21)));
        expect(root, "reminders.nurse.storyTowerDays contains day " + day(4, 21));

        root = PackFixture.tree();
        root.getJSONObject("reminders").getJSONArray("rescueGroups").getJSONObject(0).put("locksZh", 97);
        expect(root, "reminders.rescueGroups[0].locksZh must be a string");
    }

    // ------------------------------------------------------------------ helpers

    private static Rules.Reminder item(Rules r, String id) {
        for (Rules.Reminder x : r.reminders.items) if (x.id.equals(id)) return x;
        fail("no reminder " + id);
        return null;
    }

    private static JSONObject find(JSONObject root, String id) throws JSONException {
        JSONArray items = root.getJSONObject("reminders").getJSONArray("items");
        for (int i = 0; i < items.length(); i++) if (items.getJSONObject(i).getString("id").equals(id)) return items.getJSONObject(i);
        throw new AssertionError("no reminder " + id);
    }

    /** Breaks the first item (a class answer) and expects the loader to name {@code field}. */
    private static void rejects(Consumer<JSONObject> breakIt, String field) {
        JSONObject root = PackFixture.tree();
        try {
            JSONObject first = root.getJSONObject("reminders").getJSONArray("items").getJSONObject(0);
            assertEquals("class", first.getString("kind"));
            breakIt.accept(first);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        expect(root, field);
    }

    private static void expect(JSONObject root, String message) {
        try {
            RulesLoader.parse(root.toString());
            fail("expected a failure mentioning " + message);
        } catch (IllegalArgumentException e) {
            assertTrue("'" + e.getMessage() + "' should mention " + message, e.getMessage().contains(message));
        }
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static JSONObject obj(String key, int value) {
        try {
            return new JSONObject().put(key, value);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }
}
