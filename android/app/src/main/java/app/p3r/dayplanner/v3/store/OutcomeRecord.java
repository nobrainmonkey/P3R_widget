package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Payload of {@link EventKind#OUTCOME} and {@link EventKind#SKIP} rows: what happened at one slot.
 *
 * <pre>{"slot":200,"action":"meet:emperor","outcome":{"code":"RANK_UP","rank":7}}
 *{"slot":90,"action":"unlock:devil:2","stepId":"devil.meet","outcome":{"code":"AS_PREDICTED"}}
 *{"slot":91,"action":"act:study_room","outcome":{"code":"AS_PREDICTED"},
 *  "side":["unlock:devil:0"],"sideStepIds":{"unlock:devil:0":"devil.pay1"}}
 *{"slot":92,"action":"meet:emperor","outcome":{"code":"AS_PREDICTED"},"side":[],"rankAfter":7}</pre>
 *
 * {@code stepId} names the unlock step of an {@code unlock:<link>:<index>} key under the pack the
 * row was written with, so replay can find the step again when an app update renumbers the steps.
 * Rows written before it existed have none.
 *
 * <p>Values resolved when the row was written (never part of the command itself, so they do not
 * change its idempotency fingerprint, see {@link EventRow#commandSha256}):
 * <ul>
 *   <li>{@code side}: the side steps ("顺便") done in the slot, exactly, as action keys (empty
 *       when none), with {@code sideStepIds} naming their steps. Replay applies exactly these;
 *       rows without {@code side} (older rows) let the engine work them out. Absent when the
 *       outcome says {@code sideDone:false}.</li>
 *   <li>{@code rankAfter}: for a meeting recorded as predicted without a rank, the RANK it led to
 *       when it was recorded, so a later profile change cannot rewrite it.</li>
 * </ul>
 */
public final class OutcomeRecord {
    public final int slot;
    /** {@code Action.key} form; kept verbatim even if a later rule pack no longer knows it. */
    public final String actionKey;
    public final Outcome outcome;
    /** Id of the unlock step for {@code unlock:} keys, or null. */
    public final String stepId;
    /** Side steps done in the slot as resolved when recording (keys, in order), or null when not stored. */
    public final List<String> side;
    /** Unlock step id per key of {@link #side} (keys whose step has no id are left out); never null. */
    public final Map<String, String> sideStepIds;
    /** RANK after the action as resolved when recording, or -1. */
    public final int rankAfter;
    /** Raw dorm affinity resolved when recording, keyed by stable link id; legacy rows have none. */
    public final String activityAffinityLink;
    public final int activityAffinityPts;

    public OutcomeRecord(int slot, String actionKey, Outcome outcome) {
        this(slot, actionKey, outcome, null);
    }

    public OutcomeRecord(int slot, String actionKey, Outcome outcome, String stepId) {
        this(slot, actionKey, outcome, stepId, null, Collections.emptyMap(), -1);
    }

    private OutcomeRecord(int slot, String actionKey, Outcome outcome, String stepId, List<String> side,
                          Map<String, String> sideStepIds, int rankAfter) {
        this(slot, actionKey, outcome, stepId, side, sideStepIds, rankAfter, null, 0);
    }

    private OutcomeRecord(int slot, String actionKey, Outcome outcome, String stepId, List<String> side,
                          Map<String, String> sideStepIds, int rankAfter, String affinityLink, int affinityPts) {
        this.slot = slot;
        this.actionKey = actionKey;
        this.outcome = outcome;
        this.stepId = stepId;
        this.side = side == null ? null : Collections.unmodifiableList(new ArrayList<>(side));
        this.sideStepIds = Collections.unmodifiableMap(new LinkedHashMap<>(sideStepIds));
        this.rankAfter = rankAfter;
        this.activityAffinityLink = affinityLink;
        this.activityAffinityPts = Math.max(0, affinityPts);
    }

    /**
     * A record for {@code actionKey} under {@code rules}: unlock-step keys also carry the step's id.
     */
    public static OutcomeRecord of(Rules rules, int slot, String actionKey, Outcome outcome) {
        return new OutcomeRecord(slot, actionKey, outcome, stepIdOf(rules, actionKey));
    }

    /**
     * This record with the values resolved when recording: the side steps done ({@code side}, null
     * for none stored; their step ids are looked up in {@code rules}) and the RANK after the action
     * ({@code rankAfter}, -1 for none).
     */
    public OutcomeRecord withResolved(Rules rules, List<String> side, int rankAfter) {
        return new OutcomeRecord(slot, actionKey, outcome, stepId, side, stepIdsOf(rules, side), rankAfter,
                activityAffinityLink, activityAffinityPts);
    }

    /** Adds a resolved effect outside the original command, preserving idempotency fingerprints. */
    public OutcomeRecord withActivityAffinity(String linkId, int points) {
        return new OutcomeRecord(slot, actionKey, outcome, stepId, side, sideStepIds, rankAfter, linkId, points);
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

    /** Step id of every key in {@code keys} that has one under {@code rules}. */
    static Map<String, String> stepIdsOf(Rules rules, List<String> keys) {
        Map<String, String> ids = new LinkedHashMap<>();
        if (keys != null) {
            for (String key : keys) {
                String id = stepIdOf(rules, key);
                if (id != null) {
                    ids.put(key, id);
                }
            }
        }
        return ids;
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
        putSide(o, side, sideStepIds);
        if (rankAfter >= 0) {
            o.put("rankAfter", rankAfter);
        }
        if (activityAffinityLink != null) {
            o.put("activityAffinityLink", activityAffinityLink);
            o.put("activityAffinityPts", activityAffinityPts);
        }
        return o;
    }

    public static OutcomeRecord fromJson(JSONObject o) throws JSONException {
        JSONObject outcome = o.getJSONObject("outcome");
        String stepId = o.has("stepId") ? o.getString("stepId") : null;
        return new OutcomeRecord(o.getInt("slot"), o.getString("action"), Outcome.fromJson(outcome), stepId,
                sideOf(o), sideStepIdsOf(o), o.optInt("rankAfter", -1),
                o.isNull("activityAffinityLink") ? null : o.getString("activityAffinityLink"), o.optInt("activityAffinityPts", 0));
    }

    // ------------------------------------------------------------------ shared with TowerRecord

    /** Writes {@code side} (when not null) and its step ids (when any). */
    static void putSide(JSONObject o, List<String> side, Map<String, String> ids) throws JSONException {
        if (side == null) {
            return;
        }
        o.put("side", new JSONArray(side));
        if (!ids.isEmpty()) {
            JSONObject map = new JSONObject();
            for (Map.Entry<String, String> e : ids.entrySet()) {
                map.put(e.getKey(), e.getValue());
            }
            o.put("sideStepIds", map);
        }
    }

    /** The stored side-step keys, or null when the payload has none. */
    static List<String> sideOf(JSONObject o) throws JSONException {
        JSONArray array = o.optJSONArray("side");
        if (array == null) {
            return null;
        }
        List<String> keys = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            keys.add(array.getString(i));
        }
        return keys;
    }

    /** The stored step id per side-step key (empty when none). */
    static Map<String, String> sideStepIdsOf(JSONObject o) throws JSONException {
        Map<String, String> ids = new LinkedHashMap<>();
        JSONObject map = o.optJSONObject("sideStepIds");
        if (map != null) {
            for (Iterator<String> it = map.keys(); it.hasNext(); ) {
                String key = it.next();
                ids.put(key, map.getString(key));
            }
        }
        return ids;
    }
}
