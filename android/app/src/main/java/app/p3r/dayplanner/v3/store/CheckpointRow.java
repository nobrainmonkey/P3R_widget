package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.SimState;

import org.json.JSONException;
import org.json.JSONObject;

/** One row of the {@code checkpoints} table: a full observed state. Immutable. */
public final class CheckpointRow {
    public final long id;
    /** Wall time the checkpoint was saved (ms). */
    public final long created;
    /** Canonical {@link SimState} JSON. */
    public final String json;

    public CheckpointRow(long id, long created, String json) {
        this.id = id;
        this.created = created;
        this.json = json;
    }

    /** Parsed observed state (a new object on every call). */
    public SimState state() throws JSONException {
        return SimState.fromJson(new JSONObject(json));
    }
}
