package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Payload of {@link EventKind#OUTCOME} and {@link EventKind#SKIP} rows: what happened at one slot.
 *
 * <pre>{"slot":200,"action":"meet:emperor","outcome":{"code":"RANK_UP","rank":7}}
 *{"slot":90,"action":"unlock:devil:2","stepId":"devil.meet","outcome":{"code":"AS_PREDICTED"}}</pre>
 *
 * {@code stepId} names the unlock step of an {@code unlock:<link>:<index>} key under the pack the
 * row was written with, so replay can find the step again when an app update renumbers the steps.
 * Rows written before it existed have none.
 */
public final class OutcomeRecord {
    public final int slot;
    /** {@code Action.key} form; kept verbatim even if a later rule pack no longer knows it. */
    public final String actionKey;
    public final Outcome outcome;
    /** Id of the unlock step for {@code unlock:} keys, or null. */
    public final String stepId;

    public OutcomeRecord(int slot, String actionKey, Outcome outcome) {
        this(slot, actionKey, outcome, null);
    }

    public OutcomeRecord(int slot, String actionKey, Outcome outcome, String stepId) {
        this.slot = slot;
        this.actionKey = actionKey;
        this.outcome = outcome;
        this.stepId = stepId;
    }

    /**
     * A record for {@code actionKey} under {@code rules}: unlock-step keys also carry the step's id.
     */
    public static OutcomeRecord of(Rules rules, int slot, String actionKey, Outcome outcome) {
        return new OutcomeRecord(slot, actionKey, outcome, stepIdOf(rules, actionKey));
    }

    /** Id of the unlock step an {@code unlock:} key points to under {@code rules}, else null. */
    static String stepIdOf(Rules rules, String actionKey) {
        Action action = rules == null ? null : Action.parse(rules, actionKey);
        if (action == null || action.type != Action.Type.UNLOCK_STEP) {
            return null;
        }
        Rules.Link link = rules.links[action.target];
        if (link.unlock == null || action.step < 0 || action.step >= link.unlock.steps.size()) {
            return null;
        }
        String id = link.unlock.steps.get(action.step).id;
        return id == null || id.isEmpty() ? null : id;
    }

    /** Row kind for this record: skipped outcomes are stored as {@link EventKind#SKIP}. */
    public String kind() {
        return outcome.code == Outcome.Code.SKIPPED ? EventKind.SKIP : EventKind.OUTCOME;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("slot", slot);
        o.put("action", actionKey);
        if (stepId != null) {
            o.put("stepId", stepId);
        }
        o.put("outcome", outcome.toJson());
        return o;
    }

    public static OutcomeRecord fromJson(JSONObject o) throws JSONException {
        JSONObject outcome = o.getJSONObject("outcome");
        String stepId = o.has("stepId") ? o.getString("stepId") : null;
        return new OutcomeRecord(o.getInt("slot"), o.getString("action"), Outcome.fromJson(outcome), stepId);
    }
}
