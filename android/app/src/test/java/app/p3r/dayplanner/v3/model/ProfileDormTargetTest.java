package app.p3r.dayplanner.v3.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Characteristic goals are explicit preferences, independent of actual activity history. */
public class ProfileDormTargetTest {
    @Test
    public void missingOldAndUnknownGoalsStayUnselected() throws Exception {
        Profile old = Profile.fromJson(new JSONObject());
        assertTrue(old.dormTargets.isEmpty());
        assertEquals(Profile.DormTarget.NONE, old.dormTarget("dorm_yukari"));
        assertEquals(Profile.DormTarget.NONE, old.dormTarget(null));
        JSONObject json = new JSONObject().put("dormTargets", new JSONObject()
                .put("dorm_yukari", "FUTURE_VALUE")
                .put("dorm_junpei", JSONObject.NULL)
                .put("dorm_ken", 42)
                .put("dorm_akihiko", "NONE")
                .put("dorm_mitsuru", "UNLOCK"));
        Profile parsed = Profile.fromJson(json);
        assertEquals(Profile.DormTarget.NONE, parsed.dormTarget("dorm_yukari"));
        assertEquals(Profile.DormTarget.NONE, parsed.dormTarget("dorm_junpei"));
        assertEquals(Profile.DormTarget.NONE, parsed.dormTarget("dorm_ken"));
        assertEquals(Profile.DormTarget.UNLOCK, parsed.dormTarget("dorm_mitsuru"));
        assertEquals(1, parsed.dormTargets.size());
    }

    @Test
    public void roundTripAndCopyPreserveTargetsWithoutSharingMutablePreferences() throws Exception {
        Profile profile = new Profile();
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UNLOCK);
        profile.dormTargets.put("dorm_junpei", Profile.DormTarget.UPGRADE);
        profile.dormTargets.put("dorm_ken", Profile.DormTarget.NONE);
        Profile copy = profile.copy();
        assertEquals(Profile.DormTarget.UNLOCK, copy.dormTarget("dorm_yukari"));
        assertEquals(Profile.DormTarget.UPGRADE, copy.dormTarget("dorm_junpei"));
        assertFalse(copy.toJson().getJSONObject("dormTargets").has("dorm_ken"));
        copy.dormTargets.put("dorm_yukari", Profile.DormTarget.NONE);
        assertEquals(Profile.DormTarget.UNLOCK, profile.dormTarget("dorm_yukari"));
    }

    @Test
    public void stablePartnerKeyComesFromActivityIdAndNeverChineseDisplayText() {
        Rules.Activity activity = new Rules.Activity();
        activity.id = "dorm_yukari_dvd";
        assertNull(activity.dormPartnerId());
        activity.dormPartnerZh = "岳羽由加莉";
        assertEquals("dorm_yukari", activity.dormPartnerId());
        activity.id = "dorm_yukari_reading";
        activity.dormPartnerZh = "另一个显示译名";
        assertEquals("dorm_yukari", activity.dormPartnerId());
        activity.id = "dorm_";
        assertNull(activity.dormPartnerId());
        activity.id = "ordinary_dvd";
        assertNull(activity.dormPartnerId());
    }
}
