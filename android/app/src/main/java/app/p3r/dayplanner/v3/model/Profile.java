package app.p3r.dayplanner.v3.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Player goals and planning options (persisted as JSON in the {@code profile} table).
 * Pure data; defaults follow docs/DESIGN_v3.md §5–7.
 */
public final class Profile {
    public enum Tier { NONE, WANT, MUST }
    public enum AnswerPolicy { BEST, NONE }
    public enum Route { UNDECIDED, FRIEND, ROMANCE }
    /** Matching-arcana Persona carried on meetings: never, only for goal links, or (almost) always. */
    public enum PersonaMode { NONE, GOALS, ALL }

    public static final int LINKS = 22;

    /** Goal tier per link index. */
    public Tier[] tier = new Tier[LINKS];
    /** Order within tier (lower = more important); only meaningful for WANT/MUST. */
    public int[] order = new int[LINKS];
    /** Target rank per link (default 10 = MAX). */
    public int[] target = new int[LINKS];
    public Route[] route = new Route[LINKS];

    /** "其余社群也尽量提升" — secondary optimisation of non-goal links. */
    public boolean otherLinks = true;
    /**
     * Deprecated mirror of {@code personaMode == ALL}; kept so older code and backups still read.
     * New code uses {@link #personaMode} / {@link #personaFor(int)}.
     */
    public boolean personaBonus = false;
    /** Which meetings get the matching-Persona ×1.51 (default NONE: conservative). */
    public PersonaMode personaMode = PersonaMode.NONE;
    /** Allow evening dating-site notes for established links (from mid-November). */
    public boolean datingNotes = true;
    /** Link Episode track ids the player wants scheduled (opt-in). */
    public String[] episodeTracks = new String[0];
    /** Apply the Charm Lv6 ×1.51 bonus once (forecast) Charm reaches Lv6. */
    public boolean charmBonus = true;
    /** Explicit goals get one extra ordinary meeting of margin per point-gated rank. */
    public boolean goalBuffer = true;
    public AnswerPolicy answers = AnswerPolicy.BEST;
    /** Desired Tartarus visits in each block (scheduled as requirements). */
    public int towerVisitsPerBlock = 2;
    /** Hard cap of Tartarus visits per block (≥ towerVisitsPerBlock). */
    public int towerCapPerBlock = 4;
    /** Hard cap per Tartarus block id; a missing block uses {@link #towerCapPerBlock}. */
    public Map<String, Integer> towerCapByBlock = new TreeMap<>();
    /** Wanted Tartarus visits per block id; a missing block uses {@link #towerVisitsPerBlock}. */
    public Map<String, Integer> towerVisitsByBlock = new TreeMap<>();
    /**
     * Link id whose 12/24 invitation the player accepted (平安夜约会: daytime and evening of 12/24
     * are taken), or null when every invitation was declined (default).
     */
    public String christmasLink;
    /** Keep at least this much cash when choosing optional purchases. */
    public int cashBuffer = 0;
    /** Activity ids the player does not want scheduled. */
    public String[] excludedActivities = new String[0];

    public Profile() {
        Arrays.fill(tier, Tier.NONE);
        Arrays.fill(target, 10);
        Arrays.fill(route, Route.UNDECIDED);
    }

    public boolean isGoal(int link) { return tier[link] != Tier.NONE; }

    /** True if meetings with this link get the matching-Persona bonus under the current mode. */
    public boolean personaFor(int link) {
        PersonaMode mode = effectivePersonaMode();
        return mode == PersonaMode.ALL || (mode == PersonaMode.GOALS && isGoal(link));
    }

    /** {@link #personaMode}, honouring the legacy boolean when the mode was never set. */
    public PersonaMode effectivePersonaMode() {
        return personaMode == PersonaMode.NONE && personaBonus ? PersonaMode.ALL : personaMode;
    }

    public boolean wantsEpisodes(String trackId) {
        for (String s : episodeTracks) if (s.equals(trackId)) return true;
        return false;
    }

    public boolean hasAnyGoal() {
        for (Tier t : tier) if (t != Tier.NONE) return true;
        return false;
    }

    /** Tartarus visit cap of one block (its own value, else {@link #towerCapPerBlock}). */
    public int capFor(String blockId) {
        Integer v = blockId == null ? null : towerCapByBlock.get(blockId);
        return v != null ? Math.max(0, v) : towerCapPerBlock;
    }

    /** Wanted Tartarus visits in one block (its own value, else {@link #towerVisitsPerBlock}). */
    public int visitsFor(String blockId) {
        Integer v = blockId == null ? null : towerVisitsByBlock.get(blockId);
        return v != null ? Math.max(0, v) : towerVisitsPerBlock;
    }

    public boolean excluded(String activityId) {
        for (String s : excludedActivities) if (s.equals(activityId)) return true;
        return false;
    }

    public Profile copy() {
        try { return fromJson(toJson()); } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray goals = new JSONArray();
        for (int i = 0; i < LINKS; i++) {
            JSONObject g = new JSONObject();
            g.put("tier", tier[i].name());
            g.put("order", order[i]);
            g.put("target", target[i]);
            g.put("route", route[i].name());
            goals.put(g);
        }
        o.put("schema", "p3r.profile.v3");
        o.put("links", goals);
        o.put("otherLinks", otherLinks);
        o.put("personaBonus", effectivePersonaMode() == PersonaMode.ALL);
        o.put("personaMode", effectivePersonaMode().name());
        o.put("datingNotes", datingNotes);
        JSONArray eps = new JSONArray();
        for (String s : episodeTracks) eps.put(s);
        o.put("episodeTracks", eps);
        o.put("charmBonus", charmBonus);
        o.put("goalBuffer", goalBuffer);
        o.put("answers", answers.name());
        o.put("towerVisitsPerBlock", towerVisitsPerBlock);
        o.put("towerCapPerBlock", towerCapPerBlock);
        if (!towerCapByBlock.isEmpty()) o.put("towerCapByBlock", intMap(towerCapByBlock));
        if (!towerVisitsByBlock.isEmpty()) o.put("towerVisitsByBlock", intMap(towerVisitsByBlock));
        if (christmasLink != null) o.put("christmasLink", christmasLink);
        o.put("cashBuffer", cashBuffer);
        JSONArray ex = new JSONArray();
        for (String s : excludedActivities) ex.put(s);
        o.put("excludedActivities", ex);
        return o;
    }

    public static Profile fromJson(JSONObject o) throws JSONException {
        Profile p = new Profile();
        JSONArray goals = o.optJSONArray("links");
        if (goals != null) {
            for (int i = 0; i < Math.min(LINKS, goals.length()); i++) {
                JSONObject g = goals.getJSONObject(i);
                p.tier[i] = Tier.valueOf(g.optString("tier", "NONE"));
                p.order[i] = g.optInt("order", 0);
                p.target[i] = Math.max(1, Math.min(10, g.optInt("target", 10)));
                p.route[i] = Route.valueOf(g.optString("route", "UNDECIDED"));
            }
        }
        p.otherLinks = o.optBoolean("otherLinks", true);
        p.personaBonus = o.optBoolean("personaBonus", false);
        p.personaMode = PersonaMode.valueOf(o.optString("personaMode", p.personaBonus ? "ALL" : "NONE"));
        p.personaBonus = p.personaMode == PersonaMode.ALL;
        p.datingNotes = o.optBoolean("datingNotes", true);
        JSONArray eps = o.optJSONArray("episodeTracks");
        if (eps != null) {
            p.episodeTracks = new String[eps.length()];
            for (int i = 0; i < eps.length(); i++) p.episodeTracks[i] = eps.getString(i);
        }
        p.charmBonus = o.optBoolean("charmBonus", true);
        p.goalBuffer = o.optBoolean("goalBuffer", true);
        p.answers = AnswerPolicy.valueOf(o.optString("answers", "BEST"));
        p.towerVisitsPerBlock = Math.max(0, o.optInt("towerVisitsPerBlock", 2));
        p.towerCapPerBlock = Math.max(p.towerVisitsPerBlock, o.optInt("towerCapPerBlock", 4));
        readIntMap(o.optJSONObject("towerCapByBlock"), p.towerCapByBlock);
        readIntMap(o.optJSONObject("towerVisitsByBlock"), p.towerVisitsByBlock);
        String christmas = o.optString("christmasLink", "");
        p.christmasLink = christmas.isEmpty() ? null : christmas;
        p.cashBuffer = Math.max(0, o.optInt("cashBuffer", 0));
        JSONArray ex = o.optJSONArray("excludedActivities");
        if (ex != null) {
            p.excludedActivities = new String[ex.length()];
            for (int i = 0; i < ex.length(); i++) p.excludedActivities[i] = ex.getString(i);
        }
        return p;
    }

    private static JSONObject intMap(Map<String, Integer> map) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Integer> e : new TreeMap<>(map).entrySet()) {
            if (e.getKey() != null && e.getValue() != null) o.put(e.getKey(), Math.max(0, e.getValue()));
        }
        return o;
    }

    private static void readIntMap(JSONObject o, Map<String, Integer> into) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String key = it.next();
            into.put(key, Math.max(0, o.optInt(key, 0)));
        }
    }
}
