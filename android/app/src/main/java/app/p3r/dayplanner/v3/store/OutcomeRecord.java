package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.Outcome;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Payload of {@link EventKind#OUTCOME} and {@link EventKind#SKIP} rows: what happened at one slot.
 *
 * <pre>{"slot":200,"action":"meet:emperor","outcome":{"code":"RANK_UP","rank":7}}</pre>
 */
public final class OutcomeRecord {
    public final int slot;
    /** {@code Action.key} form; kept verbatim even if a later rule pack no longer knows it. */
    public final String actionKey;
    public final Outcome outcome;

    public OutcomeRecord(int slot, String actionKey, Outcome outcome) {
        this.slot = slot;
        this.actionKey = actionKey;
        this.outcome = outcome;
    }

    /** Row kind for this record: skipped outcomes are stored as {@link EventKind#SKIP}. */
    public String kind() {
        return outcome.code == Outcome.Code.SKIPPED ? EventKind.SKIP : EventKind.OUTCOME;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("slot", slot);
        o.put("action", actionKey);
        o.put("outcome", outcome.toJson());
        return o;
    }

    public static OutcomeRecord fromJson(JSONObject o) throws JSONException {
        JSONObject outcome = o.getJSONObject("outcome");
        return new OutcomeRecord(o.getInt("slot"), o.getString("action"), Outcome.fromJson(outcome));
    }
}
