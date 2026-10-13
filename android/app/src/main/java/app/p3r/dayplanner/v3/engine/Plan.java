package app.p3r.dayplanner.v3.engine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A full-horizon plan: one {@link Step} per slot from {@link #fromSlot} to {@link #toSlot}
 * inclusive, locked slots included (docs/DESIGN_v3.md §5.5). Stored as JSON by the store and read
 * by the UI/widget without loading the rule pack.
 */
public final class Plan {
    public String planId = "";
    public long stateRevision;
    public long profileRevision;
    public String rulesVersion = "";
    public int fromSlot;
    public int toSlot;
    public long createdMillis;
    /** Wall time spent planning, for diagnostics. */
    public long elapsedMillis;
    /** Whether the time budget ran out before the rollout finished (plan is still complete). */
    public boolean budgetExhausted;
    public List<Step> steps = new ArrayList<>();
    public Summary summary = new Summary();

    /** Step for a slot, or null if outside the plan. */
    public Step at(int slot) {
        int i = slot - fromSlot;
        return i >= 0 && i < steps.size() ? steps.get(i) : null;
    }

    public static final class Predicted {
        public int rankFrom = -1;
        public int rankTo = -1;
        public boolean rankUp;
        /** True when the rank-up (or its absence) does not depend on estimated hidden points. */
        public boolean certain;
        public long cashDelta;
        public int[] statDelta = new int[3];
        /** Visible stat Lv after the step (forecast), -1 if unchanged. */
        public int[] statLvAfter = {-1, -1, -1};
        /** Forecast cash after the step. */
        public long cashAfter;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("rankFrom", rankFrom);
            o.put("rankTo", rankTo);
            o.put("rankUp", rankUp);
            o.put("certain", certain);
            o.put("cashDelta", cashDelta);
            o.put("cashAfter", cashAfter);
            o.put("statDelta", new JSONArray(new int[]{statDelta[0], statDelta[1], statDelta[2]}));
            o.put("statLvAfter", new JSONArray(new int[]{statLvAfter[0], statLvAfter[1], statLvAfter[2]}));
            return o;
        }

        static Predicted fromJson(JSONObject o) throws JSONException {
            Predicted p = new Predicted();
            p.rankFrom = o.optInt("rankFrom", -1);
            p.rankTo = o.optInt("rankTo", -1);
            p.rankUp = o.optBoolean("rankUp");
            p.certain = o.optBoolean("certain");
            p.cashDelta = o.optLong("cashDelta");
            p.cashAfter = o.optLong("cashAfter");
            JSONArray sd = o.optJSONArray("statDelta");
            for (int i = 0; sd != null && i < 3; i++) p.statDelta[i] = sd.getInt(i);
            JSONArray lv = o.optJSONArray("statLvAfter");
            for (int i = 0; lv != null && i < 3; i++) p.statLvAfter[i] = lv.getInt(i);
            return p;
        }
    }

    public static final class Step {
        public int slot;
        /** {@link Action#key} form. */
        public String action = "free";
        /** Coarse category for icons: social | unlock | stat | job | tower | note | episode | locked | free. */
        public String kind = "free";
        /** Link/activity id (no index) for display, or null. */
        public String entity;
        /** Short Chinese title, e.g. "皇帝 · 小田桐秀利". */
        public String titleZh = "";
        /** Second line, e.g. "RANK 6 → 7" / "学力 +2 · 免费" / "这段时间不能自由行动". */
        public String subtitleZh = "";
        public String locationZh;
        /** One-line reason the planner chose this, e.g. "恶魔只在周二、周六晚上出现". */
        public String whyZh;
        /** Step-by-step how-to in plain Chinese (multi-line, from the rule pack's howZh/noteZh), or null. */
        public String detailZh;
        /** Short reminder shown on the card, e.g. "记得带上「皇帝」阿尔卡那的人格面具", or null. */
        public String hintZh;
        public Predicted predicted = new Predicted();
        /** Prediction relies on estimated hidden points (ordinary gain, answer gains, bonuses). */
        public boolean estimate;
        /** Assumption ids used (explained in 规划说明). */
        public List<String> assumptions = new ArrayList<>();
        /**
         * Side steps done in this slot before the main action: non-consuming unlock steps as action
         * keys ("unlock:devil:0"), in order. They use no slot of their own; their fees are part of
         * {@link Predicted#cashDelta}. Empty when none.
         */
        public List<String> side = new ArrayList<>();
        /** Chinese lines for {@link #side}, same order, e.g. "顺便：把 20,000 日元交给穿西装的男人". */
        public List<String> sideZh = new ArrayList<>();

        public boolean productive() { return !"locked".equals(kind) && !"free".equals(kind); }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("slot", slot);
            o.put("action", action);
            o.put("kind", kind);
            if (entity != null) o.put("entity", entity);
            o.put("title", titleZh);
            o.put("subtitle", subtitleZh);
            if (locationZh != null) o.put("location", locationZh);
            if (whyZh != null) o.put("why", whyZh);
            if (detailZh != null) o.put("detail", detailZh);
            if (hintZh != null) o.put("hint", hintZh);
            o.put("predicted", predicted.toJson());
            o.put("estimate", estimate);
            JSONArray a = new JSONArray();
            for (String s : assumptions) a.put(s);
            o.put("assumptions", a);
            if (!side.isEmpty()) {
                JSONArray keys = new JSONArray();
                for (String s : side) keys.put(s);
                o.put("side", keys);
                JSONArray zh = new JSONArray();
                for (String s : sideZh) zh.put(s);
                o.put("sideZh", zh);
            }
            return o;
        }

        static Step fromJson(JSONObject o) throws JSONException {
            Step s = new Step();
            s.slot = o.getInt("slot");
            s.action = o.optString("action", "free");
            s.kind = o.optString("kind", "free");
            s.entity = o.has("entity") ? o.getString("entity") : null;
            s.titleZh = o.optString("title", "");
            s.subtitleZh = o.optString("subtitle", "");
            s.locationZh = o.has("location") ? o.getString("location") : null;
            s.whyZh = o.has("why") ? o.getString("why") : null;
            s.detailZh = o.has("detail") ? o.getString("detail") : null;
            s.hintZh = o.has("hint") ? o.getString("hint") : null;
            JSONObject p = o.optJSONObject("predicted");
            if (p != null) s.predicted = Predicted.fromJson(p);
            s.estimate = o.optBoolean("estimate");
            JSONArray a = o.optJSONArray("assumptions");
            for (int i = 0; a != null && i < a.length(); i++) s.assumptions.add(a.getString(i));
            JSONArray side = o.optJSONArray("side");
            for (int i = 0; side != null && i < side.length(); i++) s.side.add(side.getString(i));
            JSONArray sideZh = o.optJSONArray("sideZh");
            for (int i = 0; sideZh != null && i < sideZh.length(); i++) s.sideZh.add(sideZh.getString(i));
            return s;
        }
    }

    public static final class Summary {
        /** Forecast rank per link index at the end of the plan. */
        public int[] endRanks = new int[22];
        /** Slot at which each link is forecast to reach MAX (or its target), -1 if never. */
        public int[] maxSlot = new int[22];
        public int usefulActions;
        public int lastUsefulSlot = -1;
        public int freeSlots;
        public int lockedSlots;
        public long endCash;
        public int[] endStatLv = new int[3];
        /** Chinese notes for the 规划说明 sheet (e.g. why a goal cannot reach MAX). */
        public List<String> notesZh = new ArrayList<>();

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("endRanks", new JSONArray(endRanks));
            o.put("maxSlot", new JSONArray(maxSlot));
            o.put("usefulActions", usefulActions);
            o.put("lastUsefulSlot", lastUsefulSlot);
            o.put("freeSlots", freeSlots);
            o.put("lockedSlots", lockedSlots);
            o.put("endCash", endCash);
            o.put("endStatLv", new JSONArray(endStatLv));
            JSONArray n = new JSONArray();
            for (String s : notesZh) n.put(s);
            o.put("notes", n);
            return o;
        }

        static Summary fromJson(JSONObject o) throws JSONException {
            Summary s = new Summary();
            JSONArray er = o.optJSONArray("endRanks");
            for (int i = 0; er != null && i < Math.min(22, er.length()); i++) s.endRanks[i] = er.getInt(i);
            JSONArray ms = o.optJSONArray("maxSlot");
            for (int i = 0; ms != null && i < Math.min(22, ms.length()); i++) s.maxSlot[i] = ms.getInt(i);
            s.usefulActions = o.optInt("usefulActions");
            s.lastUsefulSlot = o.optInt("lastUsefulSlot", -1);
            s.freeSlots = o.optInt("freeSlots");
            s.lockedSlots = o.optInt("lockedSlots");
            s.endCash = o.optLong("endCash");
            JSONArray lv = o.optJSONArray("endStatLv");
            for (int i = 0; lv != null && i < 3; i++) s.endStatLv[i] = lv.getInt(i);
            JSONArray n = o.optJSONArray("notes");
            for (int i = 0; n != null && i < n.length(); i++) s.notesZh.add(n.getString(i));
            return s;
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("schema", "p3r.plan.v3");
        o.put("planId", planId);
        o.put("stateRevision", stateRevision);
        o.put("profileRevision", profileRevision);
        o.put("rulesVersion", rulesVersion);
        o.put("fromSlot", fromSlot);
        o.put("toSlot", toSlot);
        o.put("createdMillis", createdMillis);
        o.put("elapsedMillis", elapsedMillis);
        o.put("budgetExhausted", budgetExhausted);
        JSONArray a = new JSONArray();
        for (Step s : steps) a.put(s.toJson());
        o.put("steps", a);
        o.put("summary", summary.toJson());
        return o;
    }

    public static Plan fromJson(JSONObject o) throws JSONException {
        Plan p = new Plan();
        p.planId = o.optString("planId");
        p.stateRevision = o.optLong("stateRevision");
        p.profileRevision = o.optLong("profileRevision");
        p.rulesVersion = o.optString("rulesVersion");
        p.fromSlot = o.getInt("fromSlot");
        p.toSlot = o.getInt("toSlot");
        p.createdMillis = o.optLong("createdMillis");
        p.elapsedMillis = o.optLong("elapsedMillis");
        p.budgetExhausted = o.optBoolean("budgetExhausted");
        JSONArray a = o.optJSONArray("steps");
        for (int i = 0; a != null && i < a.length(); i++) p.steps.add(Step.fromJson(a.getJSONObject(i)));
        JSONObject s = o.optJSONObject("summary");
        if (s != null) p.summary = Summary.fromJson(s);
        return p;
    }
}
