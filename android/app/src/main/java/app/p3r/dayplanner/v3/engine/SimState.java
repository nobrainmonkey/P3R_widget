package app.p3r.dayplanner.v3.engine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * Planning state (docs/DESIGN_v3.md §5.1). Arrays only, so copies are cheap in rollouts.
 *
 * <p>The same structure carries the observed state (built by the store from checkpoint + events)
 * and the forecast state inside the planner. The planner always works on copies; it never writes
 * back into the observed record.
 */
public final class SimState {
    public static final int LINKS = 22;
    public static final int STATS = 3;

    /** Next slot to act in (day*2 + evening). */
    public int slot;
    public long cash;
    /** True when cash is an exact typed/derived value rather than a conservative lower bound. */
    public boolean cashExact;
    /** Hidden stat points, lower-bound estimate (≥ threshold of the visible Lv). */
    public int[] statPts = new int[STATS];
    public int[] rank = new int[LINKS];
    /** Estimated points toward the next rank. */
    public int[] pts = new int[LINKS];
    /** Game showed the rank-up cue: next meeting ranks up. */
    public boolean[] ready = new boolean[LINKS];
    /** Number of completed unlock steps (index into Unlock.steps). */
    public int[] unlockStep = new int[LINKS];
    /** Day of the most recent unlock step, -1 if none. */
    public int[] lastStepDay = new int[LINKS];
    /** Bit i set → rescue i (index into Rules.rescues) completed. */
    public long rescued;
    /** Bit i set → rescue i failed (deadline passed / player confirmed). */
    public long rescueFailed;
    /** Visits per Tartarus block index. */
    public int[] towerVisits = new int[0];
    public int floor;
    /** Completed shifts per activity index (jobs). */
    public int[] shifts = new int[0];
    /** Times used per activity index (for once-only activities). */
    public int[] used = new int[0];
    /**
     * Player-reported rank holds: {@code heldRank[L] = r} means the game said link L cannot rank up
     * into rank r for now ("游戏提示暂时无法升级"). Meetings still add points but the forecast never
     * ranks the link up past {@code r-1} until the player reports progress (a rank-up, a ready cue
     * or a new checkpoint). 0 = no hold.
     */
    public int[] heldRank = new int[LINKS];

    public SimState() {
        Arrays.fill(lastStepDay, -1);
    }

    public SimState(int blocks, int activities) {
        this();
        towerVisits = new int[blocks];
        shifts = new int[activities];
        used = new int[activities];
    }

    public SimState copy() {
        SimState s = new SimState();
        s.slot = slot;
        s.cash = cash;
        s.cashExact = cashExact;
        s.statPts = statPts.clone();
        s.rank = rank.clone();
        s.pts = pts.clone();
        s.ready = ready.clone();
        s.unlockStep = unlockStep.clone();
        s.lastStepDay = lastStepDay.clone();
        s.rescued = rescued;
        s.rescueFailed = rescueFailed;
        s.towerVisits = towerVisits.clone();
        s.floor = floor;
        s.shifts = shifts.clone();
        s.used = used.clone();
        s.heldRank = heldRank.clone();
        return s;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("slot", slot);
        o.put("cash", cash);
        o.put("cashExact", cashExact);
        o.put("statPts", arr(statPts));
        o.put("rank", arr(rank));
        o.put("pts", arr(pts));
        JSONArray r = new JSONArray();
        for (boolean b : ready) r.put(b);
        o.put("ready", r);
        o.put("unlockStep", arr(unlockStep));
        o.put("lastStepDay", arr(lastStepDay));
        o.put("rescued", rescued);
        o.put("rescueFailed", rescueFailed);
        o.put("towerVisits", arr(towerVisits));
        o.put("floor", floor);
        o.put("shifts", arr(shifts));
        o.put("used", arr(used));
        o.put("heldRank", arr(heldRank));
        return o;
    }

    public static SimState fromJson(JSONObject o) throws JSONException {
        SimState s = new SimState();
        s.slot = o.getInt("slot");
        s.cash = o.optLong("cash", 0);
        s.cashExact = o.optBoolean("cashExact", false);
        s.statPts = ints(o.optJSONArray("statPts"), STATS);
        s.rank = ints(o.optJSONArray("rank"), LINKS);
        s.pts = ints(o.optJSONArray("pts"), LINKS);
        JSONArray r = o.optJSONArray("ready");
        for (int i = 0; r != null && i < Math.min(LINKS, r.length()); i++) s.ready[i] = r.getBoolean(i);
        s.unlockStep = ints(o.optJSONArray("unlockStep"), LINKS);
        s.lastStepDay = ints(o.optJSONArray("lastStepDay"), LINKS, -1);
        s.rescued = o.optLong("rescued", 0);
        s.rescueFailed = o.optLong("rescueFailed", 0);
        JSONArray tv = o.optJSONArray("towerVisits");
        s.towerVisits = ints(tv, tv == null ? 0 : tv.length());
        s.floor = o.optInt("floor", 0);
        JSONArray sh = o.optJSONArray("shifts");
        s.shifts = ints(sh, sh == null ? 0 : sh.length());
        JSONArray us = o.optJSONArray("used");
        s.used = ints(us, us == null ? 0 : us.length());
        s.heldRank = ints(o.optJSONArray("heldRank"), LINKS);
        return s;
    }

    private static JSONArray arr(int[] a) {
        JSONArray j = new JSONArray();
        for (int v : a) j.put(v);
        return j;
    }

    private static int[] ints(JSONArray a, int n) throws JSONException { return ints(a, n, 0); }

    private static int[] ints(JSONArray a, int n, int fill) throws JSONException {
        int[] out = new int[n];
        Arrays.fill(out, fill);
        for (int i = 0; a != null && i < Math.min(n, a.length()); i++) out[i] = a.getInt(i);
        return out;
    }
}
