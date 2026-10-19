package app.p3r.dayplanner.v3.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.p3r.dayplanner.v3.model.Profile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Backup format: canonical, self-checking, never contradicts itself (plain JVM). */
public class BackupTest {

    @Test
    public void writeThenReadIsByteStable() throws Exception {
        Backup.Data data = sample();
        String first = Backup.write(data, "pack|1", 1000L);
        Backup.Data back = Backup.read(first);
        String second = Backup.write(back, "pack|1", 1000L);
        assertEquals(first, second);
        assertEquals(data.profileJson, back.profileJson);
        assertEquals(2, back.checkpoints.size());
        assertEquals(2, back.latestCheckpointId());
    }

    @Test
    public void rulesVersionStampsRoundTripAndOlderFilesStillRead() throws Exception {
        Backup.Data data = sample();
        data.checkpoints.set(1, new CheckpointRow(2, 20, "{\"slot\":82}", "pack|2"));
        data.events.set(0, new EventRow(1, "c1", 2, EventKind.OUTCOME,
                "{\"action\":\"meet:emperor\",\"outcome\":{\"code\":\"RANK_UP\"},\"slot\":82}", false, 21, "pack|2"));
        String text = Backup.write(data, "pack|2", 5L);
        Backup.Data back = Backup.read(text);
        assertEquals("pack|2", back.rulesVersion);
        assertNull(back.checkpoints.get(0).rulesVersion);
        assertEquals("pack|2", back.checkpoints.get(1).rulesVersion);
        assertEquals("pack|2", back.events.get(0).rulesVersion);
        assertNull(back.events.get(1).rulesVersion);
        assertEquals(text, Backup.write(back, "pack|2", 5L));

        JSONObject old = new JSONObject(Backup.write(sample(), "", 0));
        old.remove("rulesVersion");
        assertNull(Backup.read(old.toString()).rulesVersion);
    }

    @Test
    public void importJournalRowsAreNeverExported() throws Exception {
        Backup.Data data = sample();
        data.events.add(new EventRow(9, "imp", 2, EventKind.IMPORT, "{\"payloadSha256\":\"x\"}", false, 9));
        Backup.Data back = Backup.read(Backup.write(data, "", 0));
        for (EventRow e : back.events) {
            assertFalse(EventKind.IMPORT.equals(e.kind));
        }
    }

    @Test
    public void voidedFlagsFollowTheVoidRows() throws Exception {
        JSONObject root = new JSONObject(Backup.write(sample(), "", 0));
        JSONArray events = root.getJSONArray("events");
        events.getJSONObject(0).put("voided", true);
        events.getJSONObject(1).put("voided", false);
        Backup.Data back = Backup.read(root.toString());
        assertFalse("nothing voids row 1", back.events.get(0).voided);
        assertTrue("row 2 is the target of the void row", back.events.get(1).voided);
    }

    @Test
    public void rejectsForeignFilesAndDamage() throws Exception {
        expectInvalid("not json", Backup.ZH_NOT_A_BACKUP);
        expectInvalid("{\"schema\":\"something.else\"}", Backup.ZH_NOT_A_BACKUP);

        JSONObject dangling = new JSONObject(Backup.write(sample(), "", 0));
        dangling.getJSONArray("events").getJSONObject(3).getJSONObject("payload").put("target", 99);
        expectInvalid(dangling.toString(), Backup.ZH_DAMAGED);

        JSONObject duplicateId = new JSONObject(Backup.write(sample(), "", 0));
        duplicateId.getJSONArray("events").getJSONObject(1).put("commandId", "c1");
        expectInvalid(duplicateId.toString(), Backup.ZH_DAMAGED);

        JSONObject badTower = new JSONObject(Backup.write(sample(), "", 0));
        badTower.getJSONArray("events").getJSONObject(2).getJSONObject("payload").put("cashMode", "both");
        expectInvalid(badTower.toString(), Backup.ZH_DAMAGED);

        JSONObject missingCheckpoint = new JSONObject(Backup.write(sample(), "", 0));
        missingCheckpoint.getJSONArray("events").getJSONObject(0).put("checkpointId", 7);
        expectInvalid(missingCheckpoint.toString(), Backup.ZH_DAMAGED);
    }

    @Test
    public void profileIsOptional() throws Exception {
        Backup.Data data = sample();
        data.profileJson = null;
        assertNull(Backup.read(Backup.write(data, "", 0)).profileJson);
    }

    private static void expectInvalid(String text, String zh) {
        try {
            Backup.read(text);
            fail("accepted: " + text);
        } catch (Backup.Invalid e) {
            assertEquals(zh, e.zh);
        }
    }

    /** Two checkpoints; checkpoint 2 has an outcome, a voided skip... (see rows). */
    private static Backup.Data sample() throws Exception {
        Backup.Data data = new Backup.Data();
        data.profileJson = Canonical.of(new Profile().toJson());
        data.checkpoints.add(new CheckpointRow(1, 10, "{\"slot\":40}"));
        data.checkpoints.add(new CheckpointRow(2, 20, "{\"slot\":82}"));
        data.events.add(new EventRow(1, "c1", 2, EventKind.OUTCOME,
                "{\"action\":\"meet:emperor\",\"outcome\":{\"code\":\"RANK_UP\"},\"slot\":82}", false, 21));
        data.events.add(new EventRow(2, "c2", 2, EventKind.SKIP,
                "{\"action\":\"act:study_room\",\"outcome\":{\"code\":\"SKIPPED\",\"reason\":\"story\"},\"slot\":83}", true, 22));
        data.events.add(new EventRow(3, "c3", 2, EventKind.TOWER,
                "{\"amount\":14750,\"block\":1,\"cashMode\":\"balance\",\"floor\":27,\"slot\":83}", false, 23));
        data.events.add(new EventRow(4, "c4", 2, EventKind.VOID, "{\"target\":2}", false, 24));
        return data;
    }
}
