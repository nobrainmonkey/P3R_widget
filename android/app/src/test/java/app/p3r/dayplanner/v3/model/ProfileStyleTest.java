package app.p3r.dayplanner.v3.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 安排风格 in the profile (DESIGN §20): JSON key {@code style} ("breadth" / "max"), 「尽量都体验」 by
 * default also for profiles stored without the key, and the 羁绊剧情 default of a new profile.
 */
public class ProfileStyleTest {

    @Test
    public void breadthIsTheDefaultAndSurvivesAJsonRoundTrip() throws Exception {
        Profile p = new Profile();
        assertEquals(Profile.Style.BREADTH, p.style);
        assertEquals("breadth", p.toJson().getString("style"));
        p.style = Profile.Style.MAX;
        JSONObject json = p.toJson();
        assertEquals("max", json.getString("style"));
        Profile back = Profile.fromJson(new JSONObject(json.toString()));
        assertEquals(Profile.Style.MAX, back.style);
        assertEquals(json.toString(), back.toJson().toString());
        assertEquals(Profile.Style.MAX, p.copy().style);
    }

    @Test
    public void storedProfilesWithoutTheKeyReadAsBreadth() throws Exception {
        // A 3.0.x profile: no style key. Unknown values read as the default too.
        JSONObject old = new JSONObject("{\"schema\":\"p3r.profile.v3\",\"otherLinks\":true,\"towerVisitsPerBlock\":2}");
        assertEquals(Profile.Style.BREADTH, Profile.fromJson(old).style);
        assertEquals(Profile.Style.BREADTH, Profile.fromJson(new JSONObject("{\"style\":\"wide\"}")).style);
        assertEquals(Profile.Style.MAX, Profile.fromJson(new JSONObject("{\"style\":\"max\"}")).style);
        assertEquals(Profile.Style.MAX, Profile.Style.fromJson("max"));
        assertEquals(Profile.Style.BREADTH, Profile.Style.fromJson(null));
    }

    @Test
    public void aNewBreadthProfileOptsIntoEveryLinkEpisodeTrack() {
        Rules rules = new Rules();
        rules.episodes = new Rules.Episode[]{track("junpei", 5), track("empty", 0), track("akihiko", 5)};
        assertArrayEquals(new String[]{"junpei", "akihiko"}, Profile.defaultEpisodeTracks(rules));

        Profile breadth = new Profile();
        breadth.applyNewProfileDefaults(rules);
        assertArrayEquals(new String[]{"junpei", "akihiko"}, breadth.episodeTracks);

        Profile max = new Profile();
        max.style = Profile.Style.MAX;
        max.applyNewProfileDefaults(rules);
        assertEquals("尽量多 MAX keeps the opt-in", 0, max.episodeTracks.length);

        Profile chosen = new Profile();
        chosen.episodeTracks = new String[]{"akihiko"};
        chosen.applyNewProfileDefaults(rules);
        assertArrayEquals("a choice already made is kept", new String[]{"akihiko"}, chosen.episodeTracks);

        Profile none = new Profile();
        none.applyNewProfileDefaults(null);
        assertEquals(0, none.episodeTracks.length);
        assertEquals(0, Profile.defaultEpisodeTracks(new Rules()).length);
    }

    private static Rules.Episode track(String id, int count) {
        Rules.Episode e = new Rules.Episode();
        e.id = id;
        e.count = count;
        return e;
    }
}
