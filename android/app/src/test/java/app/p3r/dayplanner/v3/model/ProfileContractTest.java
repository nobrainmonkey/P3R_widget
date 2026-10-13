package app.p3r.dayplanner.v3.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The shared profile contract of 3.0.1: per-block Tartarus caps and wanted visits, the accepted
 * Christmas Eve invitation and the romance route default (JSON round trip, defaults, older JSON).
 */
public class ProfileContractTest {

    @Test
    public void newFieldsDefaultToTheSharedValues() {
        Profile p = new Profile();
        assertTrue(p.towerCapByBlock.isEmpty());
        assertTrue(p.towerVisitsByBlock.isEmpty());
        assertNull(p.christmasLink);
        assertFalse(p.christmasTaken());
        assertEquals(4, p.capFor("thebel"));
        assertEquals(2, p.visitsFor("thebel"));
        assertEquals(4, p.capFor(null));
        p.towerCapPerBlock = 6;
        p.towerVisitsPerBlock = 3;
        assertEquals("a block without an entry follows the shared cap", 6, p.capFor("arqa"));
        assertEquals(3, p.visitsFor("arqa"));
    }

    @Test
    public void perBlockEntriesWinAndSurviveAJsonRoundTrip() throws Exception {
        Profile p = new Profile();
        p.towerCapByBlock.put("thebel", 1);
        p.towerCapByBlock.put("arqa", 7);
        p.towerVisitsByBlock.put("thebel", 1);
        p.towerVisitsByBlock.put("yabbashah", 0);
        p.christmasLink = "lovers";
        JSONObject json = p.toJson();
        assertEquals(1, json.getJSONObject("towerCapByBlock").getInt("thebel"));
        assertEquals(0, json.getJSONObject("towerVisitsByBlock").getInt("yabbashah"));
        assertEquals("lovers", json.getString("christmasLink"));

        Profile q = Profile.fromJson(new JSONObject(json.toString()));
        assertEquals(1, q.capFor("thebel"));
        assertEquals(7, q.capFor("arqa"));
        assertEquals(4, q.capFor("tziah"));
        assertEquals(1, q.visitsFor("thebel"));
        assertEquals(0, q.visitsFor("yabbashah"));
        assertEquals(2, q.visitsFor("arqa"));
        assertEquals("lovers", q.christmasLink);
        assertTrue(q.christmasTaken());
        assertEquals(p.toJson().toString(), q.toJson().toString());

        Profile copy = q.copy();
        copy.towerCapByBlock.put("thebel", 3);
        assertEquals("copies do not share the maps", 1, q.capFor("thebel"));
    }

    @Test
    public void olderProfilesReadWithTheDefaults() throws Exception {
        // A 3.0.0 profile: no per-block maps and no Christmas Eve key.
        JSONObject old = new JSONObject("{\"schema\":\"p3r.profile.v3\",\"otherLinks\":true,"
                + "\"towerVisitsPerBlock\":1,\"towerCapPerBlock\":3,\"cashBuffer\":0}");
        Profile p = Profile.fromJson(old);
        assertTrue(p.towerCapByBlock.isEmpty());
        assertTrue(p.towerVisitsByBlock.isEmpty());
        assertNull(p.christmasLink);
        assertEquals(3, p.capFor("thebel"));
        assertEquals(1, p.visitsFor("thebel"));
        assertFalse("no key is written while every invitation is declined", p.toJson().has("christmasLink"));
    }

    @Test
    public void badEntriesAreCleanedUp() throws Exception {
        JSONObject o = new JSONObject("{\"towerCapByBlock\":{\"thebel\":-2,\"arqa\":\"x\",\"\":3},"
                + "\"towerVisitsByBlock\":{\"thebel\":2},\"christmasLink\":\"  \"}");
        Profile p = Profile.fromJson(o);
        assertEquals("negative caps become 0", 0, p.capFor("thebel"));
        assertEquals("non-numbers are skipped", 4, p.capFor("arqa"));
        assertFalse(p.towerCapByBlock.containsKey(""));
        assertEquals(2, p.visitsFor("thebel"));
        assertNull("a blank invitation means declined", p.christmasLink);
        assertNull(Profile.fromJson(new JSONObject("{\"christmasLink\":null}")).christmasLink);
    }

    @Test
    public void undecidedRouteCountsAsFriend() {
        Profile p = new Profile();
        assertEquals(Profile.Route.UNDECIDED, p.route[6]);
        assertFalse(p.romance(6));
        p.route[6] = Profile.Route.FRIEND;
        assertFalse(p.romance(6));
        p.route[6] = Profile.Route.ROMANCE;
        assertTrue(p.romance(6));
        assertFalse(p.romance(-1));
        assertFalse(p.romance(99));
    }

    @Test
    public void acceptedChristmasEveInvitationTakesBothSlotsOfTheDay() {
        Rules r = PackFixture.rules();
        int eve = r.christmasEveDay();
        assertEquals("12-24", r.day(eve).md);
        Profile p = new Profile();
        assertNull(p.lockOf(r, Rules.slot(eve, false)));
        assertNull(p.lockOf(r, Rules.slot(eve, true)));
        p.christmasLink = "lovers";
        assertEquals(Rules.LOCK_CHRISTMAS, p.lockOf(r, Rules.slot(eve, false)));
        assertEquals(Rules.LOCK_CHRISTMAS, p.lockOf(r, Rules.slot(eve, true)));
        assertNull("the day before stays free", p.lockOf(r, Rules.slot(eve - 1, true)));
        assertNull("the day after stays free", p.lockOf(r, Rules.slot(eve + 1, false)));
        // A pack lock elsewhere is untouched.
        int jan31 = r.lastDay;
        assertEquals(r.lockOf(Rules.slot(jan31, true)), p.lockOf(r, Rules.slot(jan31, true)));
    }

    @Test
    public void christmasLockHasAChineseLabelEvenWhenThePackHasNone() {
        Rules r = PackFixture.rules();
        assertEquals("平安夜约会", r.lockReasonZh(Rules.LOCK_CHRISTMAS));
        assertEquals(r.lockReasonsZh.get("full_moon"), r.lockReasonZh("full_moon"));
        assertNull(r.lockReasonZh("no_such_reason"));
        assertNull(r.lockReasonZh(null));
        Rules empty = new Rules();
        assertEquals("平安夜约会", empty.lockReasonZh(Rules.LOCK_CHRISTMAS));
        assertEquals(-1, empty.christmasEveDay());
    }
}
