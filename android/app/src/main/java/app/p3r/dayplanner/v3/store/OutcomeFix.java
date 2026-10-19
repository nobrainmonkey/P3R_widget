package app.p3r.dayplanner.v3.store;

import org.json.JSONException;
import org.json.JSONObject;

/** Replacement of one recorded outcome at its original slot; never creates a new action. */
public final class OutcomeFix {
    public final long target;
    public final OutcomeRecord record;
    /** Fingerprint of the caller's values, before historical side steps/ranks were resolved. */
    public final String commandSha256;

    public OutcomeFix(long target, OutcomeRecord record, String commandSha256) {
        this.target = target;
        this.record = record;
        this.commandSha256 = commandSha256;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("target", target).put("record", record.toJson())
                .put("commandSha256", commandSha256);
    }

    public static OutcomeFix fromJson(JSONObject value) throws JSONException {
        String sha = value.getString("commandSha256");
        if (!sha.matches("[0-9a-f]{64}")) throw new JSONException("invalid outcome correction fingerprint");
        return new OutcomeFix(value.getLong("target"), OutcomeRecord.fromJson(value.getJSONObject("record")), sha);
    }
}
