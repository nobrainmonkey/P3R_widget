package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backup file format (schema {@value #SCHEMA}): the profile, every checkpoint and every event
 * except import journal rows, written as canonical JSON so an export → import → export round trip
 * is byte-identical apart from {@code exportedMillis}.
 *
 * <pre>
 * {"schema":"p3r.backup.v3","exportedMillis":…,"rulesVersion":"…","profile":{…}|null,
 *  "checkpoints":[{"id":1,"created":…,"state":{…}}],
 *  "events":[{"seq":1,"commandId":"…","checkpointId":1,"kind":"outcome","payload":{…},
 *             "voided":false,"created":…}]}
 * </pre>
 *
 * {@link #read} checks the structure and every cross-reference; it does not need the rule pack.
 * Pure code, no I/O.
 */
public final class Backup {
    public static final String SCHEMA = "p3r.backup.v3";

    /** User-facing messages (Simplified Chinese). */
    public static final String ZH_NOT_A_BACKUP = "这不是 P3R 规划的备份文件，没有做任何更改";
    public static final String ZH_DAMAGED = "备份文件不完整或已损坏，没有做任何更改";

    private Backup() {}

    /** Contents of a backup. Rows hold canonical JSON. */
    public static final class Data {
        /** Canonical profile JSON, or null when the backup has no profile. */
        public String profileJson;
        public final List<CheckpointRow> checkpoints = new ArrayList<>();
        public final List<EventRow> events = new ArrayList<>();

        /** Latest checkpoint id, or 0 when there is none. */
        public long latestCheckpointId() {
            long id = 0;
            for (CheckpointRow c : checkpoints) {
                id = Math.max(id, c.id);
            }
            return id;
        }
    }

    /** Rejected backup; {@link #zh} is shown to the player, the message is for logs. */
    public static final class Invalid extends Exception {
        public final String zh;

        Invalid(String zh, String detail) {
            super(detail);
            this.zh = zh;
        }

        Invalid(String zh, String detail, Throwable cause) {
            super(detail, cause);
            this.zh = zh;
        }
    }

    /** Serialises {@code data}; import journal rows are left out. */
    public static String write(Data data, String rulesVersion, long exportedMillis) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA);
        root.put("exportedMillis", exportedMillis);
        root.put("rulesVersion", rulesVersion == null ? "" : rulesVersion);
        root.put("profile", data.profileJson == null ? JSONObject.NULL : new JSONObject(data.profileJson));
        JSONArray checkpoints = new JSONArray();
        for (CheckpointRow c : data.checkpoints) {
            JSONObject o = new JSONObject();
            o.put("id", c.id);
            o.put("created", c.created);
            o.put("state", new JSONObject(c.json));
            checkpoints.put(o);
        }
        root.put("checkpoints", checkpoints);
        JSONArray events = new JSONArray();
        for (EventRow e : data.events) {
            if (EventKind.IMPORT.equals(e.kind)) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("seq", e.seq);
            o.put("commandId", e.commandId);
            o.put("checkpointId", e.checkpointId);
            o.put("kind", e.kind);
            o.put("payload", new JSONObject(e.json));
            o.put("voided", e.voided);
            o.put("created", e.created);
            events.put(o);
        }
        root.put("events", events);
        return Canonical.of(root);
    }

    /**
     * Parses and checks a backup. Import journal rows are dropped; {@code voided} flags are
     * recomputed from the void rows so the file cannot contradict itself.
     */
    public static Data read(String text) throws Invalid {
        JSONObject root;
        try {
            root = new JSONObject(text == null ? "" : text);
        } catch (JSONException e) {
            throw new Invalid(ZH_NOT_A_BACKUP, "not JSON", e);
        }
        if (!SCHEMA.equals(root.optString("schema"))) {
            throw new Invalid(ZH_NOT_A_BACKUP, "schema " + root.optString("schema"));
        }
        try {
            Data data = new Data();
            readProfile(root, data);
            readCheckpoints(root, data);
            readEvents(root, data);
            return data;
        } catch (JSONException | RuntimeException e) {
            throw new Invalid(ZH_DAMAGED, "malformed: " + e.getMessage(), e);
        }
    }

    private static void readProfile(JSONObject root, Data data) throws JSONException {
        if (!root.has("profile") || root.isNull("profile")) {
            data.profileJson = null;
            return;
        }
        JSONObject profile = root.getJSONObject("profile");
        Profile.fromJson(profile);
        data.profileJson = Canonical.of(profile);
    }

    private static void readCheckpoints(JSONObject root, Data data) throws JSONException, Invalid {
        JSONArray list = root.getJSONArray("checkpoints");
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            long id = o.getLong("id");
            long created = o.getLong("created");
            JSONObject state = o.getJSONObject("state");
            SimState.fromJson(state);
            if (id <= 0 || created < 0 || !ids.add(id)) {
                throw new Invalid(ZH_DAMAGED, "bad checkpoint id " + id);
            }
            data.checkpoints.add(new CheckpointRow(id, created, Canonical.of(state)));
        }
        data.checkpoints.sort((a, b) -> Long.compare(a.id, b.id));
    }

    private static void readEvents(JSONObject root, Data data) throws JSONException, Invalid {
        Set<Long> checkpointIds = new HashSet<>();
        for (CheckpointRow c : data.checkpoints) {
            checkpointIds.add(c.id);
        }
        JSONArray list = root.getJSONArray("events");
        Map<Long, EventRow> bySeq = new HashMap<>();
        Set<String> commandIds = new HashSet<>();
        Set<Long> voidedTargets = new HashSet<>();
        List<EventRow> rows = new ArrayList<>();
        long lastSeq = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            EventRow row = new EventRow(o.getLong("seq"), o.getString("commandId"),
                    o.getLong("checkpointId"), o.getString("kind"),
                    Canonical.of(o.getJSONObject("payload")), o.getBoolean("voided"), o.getLong("created"));
            if (row.seq <= lastSeq || row.commandId.isEmpty() || !commandIds.add(row.commandId)
                    || row.created < 0 || !EventKind.isKnown(row.kind)) {
                throw new Invalid(ZH_DAMAGED, "bad event header at seq " + row.seq);
            }
            lastSeq = row.seq;
            if (EventKind.IMPORT.equals(row.kind)) {
                continue;
            }
            if (row.checkpointId != 0 && !checkpointIds.contains(row.checkpointId)) {
                throw new Invalid(ZH_DAMAGED, "event " + row.seq + " refers to a missing checkpoint");
            }
            checkPayload(row, bySeq);
            if (EventKind.VOID.equals(row.kind)) {
                voidedTargets.add(row.target());
            }
            bySeq.put(row.seq, row);
            rows.add(row);
        }
        for (EventRow row : rows) {
            data.events.add(row.withVoided(voidedTargets.contains(row.seq)));
        }
    }

    private static void checkPayload(EventRow row, Map<Long, EventRow> earlier) throws JSONException, Invalid {
        JSONObject payload = row.payload();
        switch (row.kind) {
            case EventKind.OUTCOME:
            case EventKind.SKIP:
                OutcomeRecord.fromJson(payload);
                break;
            case EventKind.TOWER:
                TowerRecord.fromJson(payload);
                break;
            case EventKind.TOWER_FIX:
                TowerRecord.fromJson(payload.getJSONObject("tower"));
                requireTarget(row, earlier, EventKind.TOWER);
                break;
            case EventKind.VOID:
                requireTarget(row, earlier, null);
                break;
            default:
                break;
        }
    }

    /** The target must be an earlier row of the same checkpoint (and of {@code kind} if given). */
    private static void requireTarget(EventRow row, Map<Long, EventRow> earlier, String kind) throws Invalid {
        EventRow target = earlier.get(row.target());
        boolean ok = target != null
                && target.checkpointId == row.checkpointId
                && (kind == null ? EventKind.isVoidable(target.kind) : kind.equals(target.kind));
        if (!ok) {
            throw new Invalid(ZH_DAMAGED, row.kind + " " + row.seq + " has a bad target");
        }
    }
}
