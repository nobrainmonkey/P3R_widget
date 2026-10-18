package app.p3r.dayplanner.v3.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Dated free activities remain backward compatible and malformed availability fails closed. */
public class DormActivityLoaderTest {
    @Test
    public void exactDaysAndDormMetadataLoad() throws Exception {
        JSONObject tree = PackFixture.tree();
        JSONObject activity = tree.getJSONArray("activities").getJSONObject(0);
        int first = activity.getInt("fromDay");
        activity.put("fixedDays", new JSONArray(new int[]{first, first + 1}));
        activity.put("slots", new JSONArray().put("eve"));
        activity.put("cost", 0);
        activity.put("once", false);
        activity.put("kind", "stat");
        activity.put("dormPartnerZh", "岳羽由加莉");
        activity.put("requiresPlantedCrop", true);
        Rules.Activity parsed = RulesLoader.parse(tree.toString()).activities[0];
        assertArrayEquals(new int[]{first, first + 1}, parsed.fixedDays);
        assertEquals("岳羽由加莉", parsed.dormPartnerZh);
        assertTrue(parsed.dormActivity());
        assertTrue(parsed.requiresPlantedCrop);
    }

    @Test
    public void oldPacksHaveNoDormMetadataAndKeepGardenPreparationDisabled() throws Exception {
        JSONObject tree = PackFixture.tree();
        JSONArray activities = tree.getJSONArray("activities");
        for (int i = 0; i < activities.length(); i++) {
            JSONObject activity = activities.getJSONObject(i);
            activity.remove("fixedDays");
            activity.remove("dormPartnerZh");
            activity.remove("requiresPlantedCrop");
            activity.remove("affinityLink");
            activity.remove("affinityPts");
        }
        for (Rules.Activity activity : RulesLoader.parse(tree.toString()).activities) {
            assertNull(activity.fixedDays);
            assertFalse(activity.dormActivity());
            assertFalse(activity.requiresPlantedCrop);
            assertEquals(-1, activity.affinityLink);
            assertEquals(0, activity.affinityPts);
        }
        assertFalse(Profile.fromJson(new JSONObject()).planDormGardening);
    }

    @Test
    public void emptyUnsortedAndOutOfRangeFixedDaysAreRejected() throws Exception {
        JSONObject tree = PackFixture.tree();
        JSONObject activity = tree.getJSONArray("activities").getJSONObject(0);
        int first = activity.getInt("fromDay");
        activity.put("fixedDays", new JSONArray());
        rejected(tree, "fixedDays must not be empty");
        activity.put("fixedDays", new JSONArray(new int[]{first + 1, first}));
        rejected(tree, "must be strictly ascending");
        activity.put("toDay", first);
        activity.put("fixedDays", new JSONArray(new int[]{first + 1}));
        rejected(tree, "outside fromDay..toDay");
    }

    @Test
    public void dormActivityCannotInventFeeDaytimeOrNoCalendar() throws Exception {
        JSONObject tree = PackFixture.tree();
        JSONObject activity = tree.getJSONArray("activities").getJSONObject(0);
        activity.put("dormPartnerZh", "岳羽由加莉");
        activity.remove("fixedDays");
        rejected(tree, "dorm activity must be a free, repeatable evening activity with fixedDays");
        activity.put("fixedDays", new JSONArray(new int[]{activity.getInt("fromDay")}));
        activity.put("slots", new JSONArray().put("day"));
        rejected(tree, "dorm activity must be a free, repeatable evening activity with fixedDays");
        activity.put("slots", new JSONArray().put("eve"));
        activity.put("cost", 10);
        rejected(tree, "dorm activity must be a free, repeatable evening activity with fixedDays");
    }

    @Test
    public void rawAffinityReferencesAManualLinkAndRequiresAPositivePairedGain() throws Exception {
        JSONObject tree = PackFixture.tree();
        JSONObject activity = tree.getJSONArray("activities").getJSONObject(0);
        activity.put("fixedDays", new JSONArray(new int[]{activity.getInt("fromDay")}));
        activity.put("slots", new JSONArray().put("eve"));
        activity.put("cost", 0);
        activity.put("once", false);
        activity.put("kind", "stat");
        activity.put("dormPartnerZh", "山岸风花");
        activity.put("affinityLink", "priestess");
        activity.put("affinityPts", 1);
        Rules.Activity parsed = RulesLoader.parse(tree.toString()).activities[0];
        assertEquals(RulesLoader.parse(tree.toString()).linkIndex("priestess"), parsed.affinityLink);
        assertEquals(1, parsed.affinityPts);
        activity.put("affinityLink", "unknown");
        rejected(tree, "affinityLink references unknown link");
        activity.put("affinityLink", "priestess");
        activity.put("affinityPts", -1);
        rejected(tree, "affinityPts");
        activity.put("affinityPts", 0);
        rejected(tree, "provided together");
        activity.put("affinityPts", 1);
        activity.remove("affinityLink");
        rejected(tree, "provided together");
        activity.put("affinityLink", "fool");
        rejected(tree, "manual link");
    }

    private static void rejected(JSONObject tree, String expected) {
        try {
            RulesLoader.parse(tree.toString());
            fail("accepted malformed dated activity");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }
}
