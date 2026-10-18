package app.p3r.dayplanner.v3.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Deep automatic replanning is opt-in, including for profiles saved before the option existed. */
public class ProfileDeepChangesTest {
    @Test
    public void oldProfilesKeepTheNormalCalculationBudget() throws Exception {
        assertFalse(new Profile().deepChanges);
        assertFalse(Profile.fromJson(new JSONObject("{\"schema\":\"p3r.profile.v3\"}")).deepChanges);
    }

    @Test
    public void jsonRoundTripAndCopyPreserveTheChoiceIndependently() throws Exception {
        Profile original = new Profile();
        original.deepChanges = true;
        Profile restored = Profile.fromJson(new JSONObject(original.toJson().toString()));
        assertTrue(restored.deepChanges);
        Profile copy = restored.copy();
        assertTrue(copy.deepChanges);
        copy.deepChanges = false;
        assertTrue(restored.deepChanges);
        assertFalse(Profile.fromJson(copy.toJson()).deepChanges);
    }
}
