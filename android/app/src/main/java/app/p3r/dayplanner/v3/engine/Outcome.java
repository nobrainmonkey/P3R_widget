package app.p3r.dayplanner.v3.engine;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What actually happened in a slot, as confirmed by the player (docs/DESIGN_v3.md §6/§7).
 * Applied to the observed state by {@link Engine#applyOutcome}. Never produced by the planner.
 */
public final class Outcome {
    public enum Code {
        /** Action done and the visible result matched the plan's prediction. */
        AS_PREDICTED,
        /** Meeting happened and the link ranked up (to {@link #rank} if set, else rank+1). */
        RANK_UP,
        /** Meeting happened, no rank-up. */
        NO_RANK_UP,
        /** Could not do it; slot consumed with no effect (reason in {@link #reason}). */
        SKIPPED,
        /** Unlock step / activity attempted but not completed; slot consumed. */
        NOT_DONE,
        /** Free-form correction: explicit visible values below override. */
        OTHER
    }

    public Code code = Code.AS_PREDICTED;
    /** Observed visible rank after the action, or -1 if not given. */
    public int rank = -1;
    /** Player saw the "can rank up next time" cue after this meeting. */
    public boolean readyCue;
    /** Observed visible stat Lv per stat index after the action, -1 = unchanged/not given. */
    public int[] statLv = {-1, -1, -1};
    /** Observed absolute cash after the action, or -1 if not given. */
    public long cashBalance = -1;
    /** Skip reason id: story / absent / closed / money / unwilling / other. */
    public String reason;
    /**
     * Whether the slot's side steps (the plan's "顺便：…" non-consuming unlock steps, {@link Plan.Step#side})
     * were done. Default true; false when the player says they were not, whatever happened to the
     * main action. JSON key {@code sideDone}, written only when false.
     */
    public boolean sideDone = true;
    /**
     * The side steps done in this slot, exactly ({@link Action#key} form, e.g. "unlock:devil:0"),
     * or null when not given. Given (even empty), {@link Engine#applyOutcome} applies exactly these
     * (see {@link Engine#sideSteps}) instead of working them out from the profile; ignored when
     * {@link #sideDone} is false. The result sheet may pass the step's {@link Plan.Step#side} here.
     * JSON key {@code sideKeys}, written only when not null.
     */
    public java.util.List<String> sideKeys;
    /** Replay-only resolved dorm affinity. Missing in legacy events means zero, never a new estimate. */
    public String activityAffinityLink;
    public int activityAffinityPts;

    public static Outcome of(Code c) { Outcome o = new Outcome(); o.code = c; return o; }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("code", code.name());
        if (rank >= 0) o.put("rank", rank);
        if (readyCue) o.put("readyCue", true);
        boolean anyLv = false;
        for (int v : statLv) anyLv |= v >= 0;
        if (anyLv) o.put("statLv", new org.json.JSONArray(new int[]{statLv[0], statLv[1], statLv[2]}));
        if (cashBalance >= 0) o.put("cashBalance", cashBalance);
        if (reason != null) o.put("reason", reason);
        if (!sideDone) o.put("sideDone", false);
        if (sideKeys != null) o.put("sideKeys", new org.json.JSONArray(sideKeys));
        return o;
    }

    public static Outcome fromJson(JSONObject o) throws JSONException {
        Outcome r = new Outcome();
        r.code = Code.valueOf(o.optString("code", "AS_PREDICTED"));
        r.rank = o.optInt("rank", -1);
        r.readyCue = o.optBoolean("readyCue", false);
        org.json.JSONArray lv = o.optJSONArray("statLv");
        for (int i = 0; lv != null && i < 3 && i < lv.length(); i++) r.statLv[i] = lv.getInt(i);
        r.cashBalance = o.optLong("cashBalance", -1);
        r.reason = o.has("reason") ? o.getString("reason") : null;
        r.sideDone = o.optBoolean("sideDone", true);
        org.json.JSONArray side = o.optJSONArray("sideKeys");
        if (side != null) {
            r.sideKeys = new java.util.ArrayList<>(side.length());
            for (int i = 0; i < side.length(); i++) r.sideKeys.add(side.getString(i));
        }
        return r;
    }
}
