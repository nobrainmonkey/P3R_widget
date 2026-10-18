package app.p3r.dayplanner.v3.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** Mandatory story gains are optional pack data and never inferred from a reminder. */
public class AutomaticStoryStatLoaderTest {
    @Test
    public void olderPacksWithoutAutomaticGainsRemainEmpty() throws JSONException {
        JSONObject root = PackFixture.tree();
        JSONArray days = root.getJSONArray("days");
        for (int i = 0; i < days.length(); i++) days.getJSONObject(i).remove("autoStatGain");
        Rules rules = RulesLoader.parse(root.toString());
        for (Rules.Day day : rules.days) {
            if (day != null) assertEquals(0, day.automaticStatGain.length);
        }
    }

    @Test
    public void indexesGuaranteedPointsByDeclaredStat() throws JSONException {
        JSONObject root = PackFixture.tree();
        JSONObject day = day(root, "08-10");
        day.put("autoStatGain", new JSONObject().put("academics", 3));
        Rules rules = RulesLoader.parse(root.toString());
        int[] expected = new int[rules.stats.length];
        expected[rules.stat("academics").idx] = 3;
        assertArrayEquals(expected, rules.day(day.getInt("d")).automaticStatGain);
    }

    @Test
    public void rejectsUnknownStatsNonIntegralPointsAndFreeSlots() throws JSONException {
        for (Object value : new Object[] {-1, 0, 101, 1.5, true, "3"}) {
            JSONObject root = PackFixture.tree();
            day(root, "08-10").put("autoStatGain", new JSONObject().put("academics", value));
            rejects(root, "autoStatGain");
        }
        JSONObject root = PackFixture.tree();
        day(root, "08-10").put("autoStatGain", new JSONObject().put("wisdom", 3));
        rejects(root, "unknown stat");
        root = PackFixture.tree();
        day(root, "04-21").put("autoStatGain", new JSONObject().put("academics", 3));
        rejects(root, "locked daytime");
    }

    private static JSONObject day(JSONObject root, String md) throws JSONException {
        JSONArray days = root.getJSONArray("days");
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.getJSONObject(i);
            if (md.equals(day.getString("md"))) return day;
        }
        throw new AssertionError("Missing day " + md);
    }

    private static void rejects(JSONObject root, String expected) {
        try {
            RulesLoader.parse(root.toString());
            fail("Expected malformed autoStatGain rejection");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains(expected));
        }
    }
}
