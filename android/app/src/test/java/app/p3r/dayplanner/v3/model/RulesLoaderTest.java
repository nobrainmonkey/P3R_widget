package app.p3r.dayplanner.v3.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Parsing, versioning and strict validation of the rule pack (plain JVM, real org.json). */
public class RulesLoaderTest {

    // ------------------------------------------------------------------ round trip

    @Test
    public void parsesTheShippedPack() {
        Rules r = PackFixture.rules();
        assertEquals("p3r.rules.v3", r.schema);
        assertEquals("3.0.0", r.packVersion);
        assertTrue(r.game.contains("Persona 3 Reload"));
        assertEquals(6, r.firstDay);
        assertEquals(305, r.lastDay);
        assertEquals(306, r.days.length);
        assertNull(r.days[5]);
        assertEquals("04-07", r.days[6].md);
        assertEquals("01-31", r.days[305].md);
        assertEquals(22, r.links.length);
        assertEquals(3, r.stats.length);
        assertEquals(10, r.blocks.length);
        assertEquals(2, r.rescues.length);
        assertTrue(r.activities.length >= 15);
        assertEquals("满月作战", r.lockReasonsZh.get("full_moon"));
        assertTrue(r.sources.size() > 20);
        assertTrue(r.notes.size() > 10);
        assertEquals(10, r.policy.ordinaryGain);
        assertEquals(1.51, r.policy.personaMult, 1e-9);
        assertEquals(1.51, r.policy.charmMult, 1e-9);
        for (int i = 0; i < r.links.length; i++) {
            assertEquals(i, r.links[i].idx);
            assertEquals(RulesLoader.LINK_IDS.get(i), r.links[i].id);
            assertSame(r.links[i], r.link(r.links[i].id));
            assertEquals(i, r.linkIndex(r.links[i].id));
        }
        for (int i = 0; i < r.activities.length; i++) {
            assertEquals(i, r.activityIndex(r.activities[i].id));
        }
        assertEquals(-1, r.linkIndex("world"));
        assertEquals(5, r.episodes.length);
        for (int i = 0; i < r.episodes.length; i++) assertEquals(i, r.episodes[i].idx);
        assertNotNull(r.datingNote);
    }

    @Test
    public void episodesAndDatingNoteAreOptional() throws JSONException {
        JSONObject root = PackFixture.tree();
        root.remove("episodes");
        root.put("datingNote", JSONObject.NULL);
        Rules r = RulesLoader.parse(root.toString());
        assertEquals(0, r.episodes.length);
        assertNull(r.datingNote);
    }

    @Test
    public void parsesEpisodePartsIntoDayMasks() throws JSONException {
        JSONObject root = PackFixture.tree();
        JSONObject track = root.getJSONArray("episodes").getJSONObject(0);
        int count = track.getInt("count");
        JSONArray parts = track.getJSONArray("parts");
        Rules r = RulesLoader.parse(root.toString());
        Rules.Episode e = r.episodes[0];
        assertEquals(track.getString("id"), e.id);
        for (int n = 1; n <= count; n++) {
            JSONArray days = parts.getJSONObject(n - 1).getJSONArray("days");
            int allowed = 0;
            for (int d = 0; d < e.dayMask.length; d++) if (e.allows(d, n)) allowed++;
            assertEquals("episode " + n, days.length(), allowed);
            for (int i = 0; i < days.length(); i++) assertTrue(e.allows(days.getInt(i), n));
        }
        assertTrue(!e.allows(-1, 1) && !e.allows(r.lastDay + 1, 1) && !e.allows(e.firstDay(1), 0));
    }

    @Test
    public void datingNoteWithoutLimitIsUnlimited() throws JSONException {
        JSONObject root = PackFixture.tree();
        root.getJSONObject("datingNote").put("maxUses", JSONObject.NULL);
        assertEquals(Rules.DatingNote.UNLIMITED, RulesLoader.parse(root.toString()).datingNote.maxUses);
        root.getJSONObject("datingNote").put("maxUses", 1);
        Rules.DatingNote once = RulesLoader.parse(root.toString()).datingNote;
        assertEquals(1, once.maxUses);
        assertTrue(!once.unlimited());
        root.getJSONObject("datingNote").remove("maxUses");
        assertEquals("missing key = the conservative single use", 1, RulesLoader.parse(root.toString()).datingNote.maxUses);
    }

    @Test
    public void versionIsPackVersionPlusContentHash() throws Exception {
        String json = PackFixture.json();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        Rules r = RulesLoader.parse(json);
        assertEquals("3.0.0|" + hex.substring(0, 12), r.version);
        assertEquals(r.version, RulesLoader.parse(json).version);

        Rules touched = RulesLoader.parse(json + "\n");
        assertEquals(r.packVersion, touched.packVersion);
        assertNotEquals("any byte change must change the version", r.version, touched.version);
    }

    @Test
    public void calendarHelpersMatchTheGameYear() {
        assertEquals("04-01", RulesLoader.monthDay(0));
        assertEquals("04-07", RulesLoader.monthDay(6));
        assertEquals("12-31", RulesLoader.monthDay(274));
        assertEquals("01-01", RulesLoader.monthDay(275));
        assertEquals("01-31", RulesLoader.monthDay(305));
        assertEquals(3, RulesLoader.isoWeekday(0)); // 2009-04-01 was a Wednesday
        assertEquals(7, RulesLoader.isoWeekday(305)); // 2010-01-31 was a Sunday
        assertEquals(305, PackFixture.day(1, 31));
    }

    @Test
    public void assetStreamLoaderReadsUtf8() throws IOException {
        byte[] bytes = PackFixture.json().getBytes(StandardCharsets.UTF_8);
        Rules r = RulesAssets.load(new ByteArrayInputStream(bytes));
        assertEquals(PackFixture.rules().version, r.version);
        assertEquals("贝培", r.link("temperance").personZh);
    }

    // ------------------------------------------------------------------ rejection

    @Test
    public void rejectsNullAndMalformedText() {
        assertRejected((String) null, "Rules JSON is null");
        assertRejected("[1,2,3]", "not a JSON object");
        assertRejected("{\"schema\":", "not a JSON object");
    }

    @Test
    public void rejectsWrongSchema() throws JSONException {
        JSONObject root = PackFixture.tree();
        root.put("schema", "p3r.rules.v2");
        assertRejected(root, "Unsupported rules schema 'p3r.rules.v2'");
    }

    @Test
    public void rejectsNonContiguousDays() throws JSONException {
        JSONObject root = PackFixture.tree();
        root.getJSONArray("days").remove(10);
        assertRejected(root, "root.days must have 300 entries");

        JSONObject shifted = PackFixture.tree();
        shifted.getJSONArray("days").getJSONObject(10).put("d", 17);
        assertRejected(shifted, "days[10].d is 17 but must be 16 (days must be contiguous from firstDay 6)");
    }

    @Test
    public void rejectsWrongDateOrWeekday() throws JSONException {
        JSONObject md = PackFixture.tree();
        md.getJSONArray("days").getJSONObject(0).put("md", "04-08");
        assertRejected(md, "days[0].md is '04-08' but day 6 is 04-07");

        JSONObject wd = PackFixture.tree();
        wd.getJSONArray("days").getJSONObject(0).put("wd", 3);
        assertRejected(wd, "days[0].wd is 3 but 04-07 is ISO weekday 2");
    }

    @Test
    public void rejectsWrongLinkCountOrOrder() throws JSONException {
        JSONObject missing = PackFixture.tree();
        missing.getJSONArray("links").remove(21);
        assertRejected(missing, "root.links must have 22 entries in arcana order, found 21");

        JSONObject swapped = PackFixture.tree();
        JSONArray links = swapped.getJSONArray("links");
        JSONObject magician = links.getJSONObject(1);
        links.put(1, links.getJSONObject(2));
        links.put(2, magician);
        assertRejected(swapped, "links[1].id is 'priestess', expected 'magician'");
    }

    @Test
    public void rejectsWrongArrayLengths() throws JSONException {
        JSONObject threshold = PackFixture.tree();
        threshold.getJSONArray("links").getJSONObject(4).getJSONArray("threshold").remove(10);
        assertRejected(threshold, "links[4].threshold must have 11 entries, found 10");

        JSONObject gain = PackFixture.tree();
        gain.getJSONArray("links").getJSONObject(4).getJSONArray("eventGain").put(0);
        assertRejected(gain, "links[4].eventGain must have 11 entries, found 12");

        JSONObject stat = PackFixture.tree();
        stat.getJSONArray("stats").getJSONObject(1).getJSONArray("thresholds").remove(6);
        assertRejected(stat, "stats[1].thresholds must have 7 entries, found 6");
    }

    @Test
    public void rejectsIndicesOutOfRange() throws JSONException {
        JSONObject block = PackFixture.tree();
        block.getJSONArray("days").getJSONObject(50).put("block", 99);
        assertRejected(block, "days[50].block 99 is out of range (-1..9)");

        JSONObject avail = PackFixture.tree();
        avail.getJSONArray("links").getJSONObject(1).getJSONArray("avail").put(400);
        assertRejected(avail, "links[1].avail[");
        assertRejected(avail, "= 400 is outside the calendar 6..305");

        JSONObject lock = PackFixture.tree();
        lock.getJSONArray("days").getJSONObject(0).put("dayLock", "nap");
        assertRejected(lock, "days[0].dayLock 'nap' is not declared in root.lockReasons");

        JSONObject linkRef = PackFixture.tree();
        linkRef.getJSONArray("links").getJSONObject(15).getJSONObject("unlock").getJSONObject("link").put("world", 1);
        assertRejected(linkRef, "links[15].unlock.link references unknown link 'world'");

        JSONObject statRef = PackFixture.tree();
        statRef.getJSONArray("links").getJSONObject(2).getJSONObject("unlock").getJSONObject("stat").put("courage", 7);
        assertRejected(statRef, "links[2].unlock.stat.courage = 7 is outside 1..6");

        JSONObject source = PackFixture.tree();
        source.getJSONArray("activities").getJSONObject(0).getJSONArray("src").put("nowhere");
        assertRejected(source, "activities[0].src[");
        assertRejected(source, "'nowhere' is not declared in root.sources");

        JSONObject rescue = PackFixture.tree();
        rescue.getJSONArray("links").getJSONObject(5).getJSONObject("missing").put("rescueId", "nobody");
        assertRejected(rescue, "links[5].missing.rescueId 'nobody' is not a known rescue");
    }

    @Test
    public void rejectsMeetingsOnLockedSlots() throws JSONException {
        JSONObject root = PackFixture.tree();
        JSONArray avail = root.getJSONArray("links").getJSONObject(1).getJSONArray("avail");
        avail.put(0, PackFixture.day(4, 22)); // forced Magician introduction, daytime locked
        assertRejected(root, "links[1].avail contains day 21 (04-22) whose daytime is locked (forced_event)");
    }

    @Test
    public void rejectsUnlockStepsThatDoNotEndWithTheEstablishingStep() throws JSONException {
        JSONObject root = PackFixture.tree();
        JSONArray steps = root.getJSONArray("links").getJSONObject(15).getJSONObject("unlock").getJSONArray("steps");
        steps.getJSONObject(2).put("establishes", false);
        assertRejected(root, "links[15].unlock.steps: exactly the last step must establish the link (step 2)");
    }

    @Test
    public void rejectsBadEnumsAndTypes() throws JSONException {
        JSONObject type = PackFixture.tree();
        type.getJSONArray("days").getJSONObject(30).put("type", "weekend");
        assertRejected(type, "days[30].type must be school|sunday|holiday|vacation|exam|story, got 'weekend'");

        JSONObject conf = PackFixture.tree();
        conf.getJSONArray("links").getJSONObject(3).put("conf", "probably");
        assertRejected(conf, "links[3].conf 'probably' must be one of");

        JSONObject slot = PackFixture.tree();
        slot.getJSONArray("activities").getJSONObject(0).getJSONArray("slots").put(0, "night");
        assertRejected(slot, "activities[0].slots[0] must be day|eve, got 'night'");

        JSONObject number = PackFixture.tree();
        number.getJSONArray("activities").getJSONObject(3).put("cost", "680");
        assertRejected(number, "activities[3].cost must be an integer");
    }

    @Test
    public void storyLinksNeedStoryRanksAndManualLinksMustNotHaveThem() throws JSONException {
        JSONObject story = PackFixture.tree();
        story.getJSONArray("links").getJSONObject(0).put("storyRanks", new JSONArray());
        assertRejected(story, "links[0].storyRanks must not be empty for story links");

        JSONObject manual = PackFixture.tree();
        manual.getJSONArray("links").getJSONObject(4).put("storyRanks", new JSONArray());
        assertRejected(manual, "links[4].storyRanks must be null for manual links");
    }

    @Test
    public void rejectsBrokenEpisodeTracks() throws JSONException {
        JSONObject locked = PackFixture.tree();
        JSONArray days =locked.getJSONArray("episodes").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getJSONArray("days");
        days.put(0, PackFixture.day(4, 22)); // forced Magician introduction, daytime locked
        assertRejected(locked, "episodes[0].parts[0].days contains day 21 (04-22) whose daytime is locked (forced_event)");

        JSONObject count = PackFixture.tree();
        count.getJSONArray("episodes").getJSONObject(1).put("count", 6);
        assertRejected(count, "episodes[1].parts must have count = 6 entries, found 5");

        JSONObject order = PackFixture.tree();
        order.getJSONArray("episodes").getJSONObject(2).getJSONArray("parts").getJSONObject(1).put("no", 3);
        assertRejected(order, "episodes[2].parts[1].no is 3, expected 2 (episodes must be in order)");

        JSONObject stat = PackFixture.tree();
        stat.getJSONArray("episodes").getJSONObject(3).getJSONArray("parts").getJSONObject(0).getJSONObject("gain").put("luck", 1);
        assertRejected(stat, "episodes[3].parts[0].gain references unknown stat 'luck'");

        JSONObject dup = PackFixture.tree();
        dup.getJSONArray("episodes").getJSONObject(1).put("id", "junpei");
        assertRejected(dup, "episodes[1].id 'junpei' is duplicated");

        JSONObject empty = PackFixture.tree();
        empty.getJSONArray("episodes").getJSONObject(4).getJSONArray("parts").getJSONObject(0).put("days", new JSONArray());
        assertRejected(empty, "episodes[4].parts[0].days must not be empty");

        JSONObject title = PackFixture.tree();
        title.getJSONArray("episodes").getJSONObject(0).remove("titleZh");
        assertRejected(title, "episodes[0].titleZh is required");
    }

    @Test
    public void rejectsBrokenDatingNote() throws JSONException {
        JSONObject slot = PackFixture.tree();
        slot.getJSONObject("datingNote").put("slot", "day");
        assertRejected(slot, "datingNote.slot must be eve");

        JSONObject uses = PackFixture.tree();
        uses.getJSONObject("datingNote").put("maxUses", 0);
        assertRejected(uses, "datingNote.maxUses = 0 is outside 1..1000");

        JSONObject gain = PackFixture.tree();
        gain.getJSONObject("datingNote").put("gain", 0);
        assertRejected(gain, "datingNote.gain must be positive");

        JSONObject stat = PackFixture.tree();
        stat.getJSONObject("datingNote").getJSONObject("requires").put("courage", 7);
        assertRejected(stat, "datingNote.requires.courage = 7 is outside 1..6");

        JSONObject src = PackFixture.tree();
        src.getJSONObject("datingNote").getJSONArray("src").put("nowhere");
        assertRejected(src, "'nowhere' is not declared in root.sources");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertRejected(JSONObject root, String expectedFragment) {
        assertRejected(root.toString(), expectedFragment);
    }

    private static void assertRejected(String json, String expectedFragment) {
        try {
            Rules r = RulesLoader.parse(json);
            assertNotNull(r);
            fail("expected rejection containing: " + expectedFragment);
        } catch (IllegalArgumentException e) {
            assertTrue("message '" + e.getMessage() + "' should contain '" + expectedFragment + "'",
                    e.getMessage().contains(expectedFragment));
        }
    }
}
