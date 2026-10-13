package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One row of the {@code checkpoints} table: a full observed state ({@link SimState} JSON plus the
 * {@link PackLayout} it was saved under, key {@value PackLayout#KEY}). Immutable.
 */
public final class CheckpointRow {
    public final long id;
    /** Wall time the checkpoint was saved (ms). */
    public final long created;
    /** Canonical {@link SimState} JSON, with the pack layout when saved by schema 2 or later. */
    public final String json;
    /** {@code Rules.version} of the pack the checkpoint was saved under, or null when unknown. */
    public final String rulesVersion;

    public CheckpointRow(long id, long created, String json) {
        this(id, created, json, null);
    }

    public CheckpointRow(long id, long created, String json, String rulesVersion) {
        this.id = id;
        this.created = created;
        this.json = json;
        this.rulesVersion = rulesVersion;
    }

    /** Parsed observed state, positions as stored (a new object on every call). */
    public SimState state() throws JSONException {
        return SimState.fromJson(new JSONObject(json));
    }

    /**
     * The observed state carried over to {@code rules}: remapped by id when the row has a layout
     * (see {@link PackLayout#remap}), otherwise only resized, and fitted to the pack's sizes.
     */
    public PackLayout.Remapped stateFor(Rules rules) throws JSONException {
        JSONObject o = new JSONObject(json);
        SimState stored = SimState.fromJson(o);
        PackLayout layout = PackLayout.inCheckpoint(o);
        return layout == null ? PackLayout.fitWithoutLayout(stored, rules) : layout.remap(stored, rules);
    }
}
