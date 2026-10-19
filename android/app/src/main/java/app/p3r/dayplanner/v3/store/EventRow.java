package app.p3r.dayplanner.v3.store;

import org.json.JSONException;
import org.json.JSONObject;

/** One row of the {@code events} table. Immutable. */
public final class EventRow {
    public final long seq;
    public final String commandId;
    /** Checkpoint the row belongs to, 0 when it was written before any checkpoint. */
    public final long checkpointId;
    /** One of the {@link EventKind} constants. */
    public final String kind;
    /** Canonical JSON payload (see {@link EventKind} for the shape per kind). */
    public final String json;
    public final boolean voided;
    /** Wall time the row was written (ms). */
    public final long created;
    /** {@code Rules.version} of the pack the row was written under, or null when unknown (older rows). */
    public final String rulesVersion;

    public EventRow(long seq, String commandId, long checkpointId, String kind, String json,
                    boolean voided, long created) {
        this(seq, commandId, checkpointId, kind, json, voided, created, null);
    }

    public EventRow(long seq, String commandId, long checkpointId, String kind, String json,
                    boolean voided, long created, String rulesVersion) {
        this.seq = seq;
        this.commandId = commandId;
        this.checkpointId = checkpointId;
        this.kind = kind;
        this.json = json;
        this.voided = voided;
        this.created = created;
        this.rulesVersion = rulesVersion;
    }

    /** Parsed payload. */
    public JSONObject payload() throws JSONException {
        return new JSONObject(json);
    }

    /** Copy with a different {@link #voided} flag. */
    public EventRow withVoided(boolean value) {
        return new EventRow(seq, commandId, checkpointId, kind, json, value, created, rulesVersion);
    }

    /**
     * Target sequence number of a {@link EventKind#VOID} or {@link EventKind#TOWER_FIX} row, or
     * -1 for other kinds and malformed payloads.
     */
    public long target() {
        if (!EventKind.VOID.equals(kind) && !EventKind.TOWER_FIX.equals(kind) && !EventKind.OUTCOME_FIX.equals(kind)) {
            return -1;
        }
        try {
            return payload().optLong("target", -1);
        } catch (JSONException e) {
            return -1;
        }
    }

    /**
     * Payload keys of observed rows that hold values the service resolved when it wrote the row
     * (the side steps done, the RANK after a meeting). They are not part of the command, so
     * {@link #commandSha256} leaves them out.
     */
    private static final String[] RESOLVED_KEYS = {"side", "sideStepIds", "rankAfter", "activityAffinityLink", "activityAffinityPts"};

    /**
     * SHA-256 of the command an observed-row payload came from: the canonical payload without the
     * values resolved when the row was written ({@code side}, {@code sideStepIds},
     * {@code rankAfter}; see {@link OutcomeRecord}). A payload without them hashes as it is, so
     * rows written before they existed keep their fingerprint.
     */
    public static String commandSha256(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        boolean resolved = false;
        for (String key : RESOLVED_KEYS) {
            resolved |= o.remove(key) != null;
        }
        return Canonical.sha256(resolved ? Canonical.of(o) : json);
    }

    /** Slot recorded in an observed row's payload, or -1 when the row has none. */
    public int slot() {
        if (!EventKind.isObserved(kind)) {
            return -1;
        }
        try {
            return payload().optInt("slot", -1);
        } catch (JSONException e) {
            return -1;
        }
    }

    @Override
    public String toString() {
        return "EventRow{" + seq + " " + kind + (voided ? " voided" : "") + " " + json + "}";
    }
}
