package app.p3r.dayplanner.v3.model;

import static app.p3r.dayplanner.v3.model.PackFixture.day;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** The reference catalogue is optional for old packs, but malformed numbers or windows fail visibly. */
public class RequestCatalogueLoaderTest {
    @Test public void shippedCatalogueContainsEachNumberAndTheActualDeadline() {
        Rules.Reminders rem = PackFixture.rules().reminders;
        assertEquals(101, rem.requestTips.size());
        for (int i = 0; i < 101; i++) {
            Rules.RequestTip tip = rem.requestTips.get(i);
            assertEquals(i + 1, tip.number);
            assertFalse(tip.titleZh.isEmpty());
            assertFalse(tip.detailZh.isEmpty());
            assertTrue(tip.src.contains("ev_request_catalogue"));
        }
        Rules.RequestTip knife = rem.requestTips.get(67);
        assertEquals(day(9, 10), knife.fromDay);
        assertEquals(day(10, 2), knife.untilDay);
        assertEquals(-1, rem.requestTips.get(74).untilDay);
        assertTrue(rem.requestTips.get(74).aliases.contains("战国头盔"));
    }

    @Test public void oldPacksWithoutCatalogueStillLoad() throws JSONException {
        JSONObject tree = PackFixture.tree();
        tree.getJSONObject("reminders").remove("requestTips");
        Rules parsed = RulesLoader.parse(tree.toString());
        assertTrue(parsed.reminders.requestTips.isEmpty());
        assertFalse(parsed.reminders.items.isEmpty());
    }

    @Test public void duplicateNumbersInvalidDatesAndEmptyAliasesAreRejected() throws JSONException {
        JSONObject tree = PackFixture.tree();
        JSONArray tips = tips(tree);
        tips.getJSONObject(1).put("number", tips.getJSONObject(0).getInt("number"));
        rejects(tree, "reminders.requestTips[1].number is duplicated");

        tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("fromDay", 2);
        rejects(tree, "reminders.requestTips[0].fromDay");

        tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("untilDay", day(5, 9));
        rejects(tree, "reminders.requestTips[0].untilDay");

        tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("aliases", new JSONArray().put(""));
        rejects(tree, "reminders.requestTips[0].aliases[0]");

        tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("aliases", new JSONArray().put("drink").put("drink"));
        rejects(tree, "reminders.requestTips[0].aliases[1] is duplicated");
    }

    @Test public void catalogueDoesNotBecomeADatedReminderOrSimulatedGain() throws JSONException {
        JSONObject tree = PackFixture.tree();
        Rules with = RulesLoader.parse(tree.toString());
        tree.getJSONObject("reminders").remove("requestTips");
        Rules without = RulesLoader.parse(tree.toString());
        assertEquals(without.reminders.items.size(), with.reminders.items.size());
        assertEquals(without.activities.length, with.activities.length);
        for (int i = with.firstDay; i < with.days.length; i++) {
            assertEquals(without.days[i].dayLock, with.days[i].dayLock);
            assertEquals(without.days[i].eveLock, with.days[i].eveLock);
        }
    }

    private static JSONArray tips(JSONObject tree) throws JSONException {
        return tree.getJSONObject("reminders").getJSONArray("requestTips");
    }

    private static void rejects(JSONObject tree, String message) {
        try {
            RulesLoader.parse(tree.toString());
            fail("expected validation failure: " + message);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }
}
