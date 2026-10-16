package app.p3r.dayplanner.v3.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** Prior activity uses are optional pack data, resolved by stable identity and validated strictly. */
public class ActivityPrerequisiteLoaderTest {
    @Test
    public void prerequisiteResolvesEvenWhenItAppearsLaterInThePack() throws JSONException {
        JSONObject tree = PackFixture.tree();
        JSONArray activities = tree.getJSONArray("activities");
        JSONObject earlier = activities.getJSONObject(0);
        JSONObject later = activities.getJSONObject(activities.length() - 1);
        earlier.put("requiresActivity", later.getString("id"));
        Rules rules = RulesLoader.parse(tree.toString());
        assertEquals(activities.length() - 1, rules.activities[0].requiresActivity);
    }

    @Test
    public void oldPacksWithoutPrerequisitesKeepTheirBehavior() throws JSONException {
        JSONObject tree = PackFixture.tree();
        JSONArray activities = tree.getJSONArray("activities");
        for (int i = 0; i < activities.length(); i++) activities.getJSONObject(i).remove("requiresActivity");
        for (Rules.Activity activity : RulesLoader.parse(tree.toString()).activities) {
            assertEquals(-1, activity.requiresActivity);
        }
    }

    @Test
    public void unknownPrerequisiteIsRejected() throws JSONException {
        JSONObject tree = PackFixture.tree();
        tree.getJSONArray("activities").getJSONObject(0).put("requiresActivity", "missing_menu");
        rejected(tree, "requiresActivity references unknown activity 'missing_menu'");
    }

    @Test
    public void selfDependencyAndTwoActivityCycleAreRejected() throws JSONException {
        JSONObject tree = PackFixture.tree();
        JSONArray activities = tree.getJSONArray("activities");
        JSONObject a = activities.getJSONObject(0);
        JSONObject b = activities.getJSONObject(1);
        a.put("requiresActivity", a.getString("id"));
        rejected(tree, "requiresActivity contains a cycle");
        a.put("requiresActivity", b.getString("id"));
        b.put("requiresActivity", a.getString("id"));
        rejected(tree, "requiresActivity contains a cycle");
    }

    private static void rejected(JSONObject tree, String expected) {
        try {
            RulesLoader.parse(tree.toString());
            fail("accepted invalid activity prerequisite");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }
}
