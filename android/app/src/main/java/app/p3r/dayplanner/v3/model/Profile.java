package app.p3r.dayplanner.v3.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
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
    /** Optional teammate characteristic goal; actual dorm activity counts live in the observed state. */
    public enum DormTarget { NONE, UNLOCK, UPGRADE }
    /** Matching-arcana Persona carried on meetings: never, only for goal links, or (almost) always. */
    public enum PersonaMode { NONE, GOALS, ALL }
    /**
     * 安排风格 (docs/DESIGN_v3.md §20): how the planner values 「其余社群」. {@link #BREADTH}
     * (「尽量都体验」, the default) unlocks and raises every link before stacking a few;
     * {@link #MAX} (「尽量多 MAX」) brings as many links as possible to MAX (the 3.0 objective).
     * Goals keep strict priority under both. JSON values {@code "breadth"} / {@code "max"}.
     */
    public enum Style {
        BREADTH("breadth"), MAX("max");

        public final String json;

        Style(String json) {
            this.json = json;
        }

        /** The style written as {@code json}; anything else (a missing key included) is {@link #BREADTH}. */
        public static Style fromJson(String json) {
            return MAX.json.equals(json) ? MAX : BREADTH;
        }
    }

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
     * 安排风格; {@link Style#BREADTH} also for profiles stored before 3.1.0 (it changes how the
     * planner values links, not a choice the player made). JSON key {@code style}.
     */
    public Style style = Style.BREADTH;
    /** 深度修改: use a 12 s deep search for each automatic replan after records/settings change. */
    public boolean deepChanges = false;
    /**
     * Deprecated mirror of {@code personaMode == ALL}; kept so older code and backups still read.
     * New code uses {@link #personaMode} / {@link #personaFor(int)}.
     */
    public boolean personaBonus = false;
    /** Which meetings get the matching-Persona ×1.51 (default NONE: conservative). */
    public PersonaMode personaMode = PersonaMode.NONE;
    /** Allow evening dating-site notes for established links (from mid-November). */
    public boolean datingNotes = true;
    /** Allow dorm gardening when the player will plant crops beforehand (seeds are not budgeted). */
    public boolean planDormGardening = false;
    /** Stable teammate id (e.g. dorm_yukari) → optional characteristic goal. Missing means NONE. */
    public Map<String, DormTarget> dormTargets = new TreeMap<>();
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
    /**
     * Per-block hard caps (block id → cap ≥ 0). A block without an entry uses
     * {@link #towerCapPerBlock}; read through {@link #capFor}. JSON key {@code towerCapByBlock}.
     */
    public Map<String, Integer> towerCapByBlock = new TreeMap<>();
    /**
     * Per-block wanted visits (block id → visits ≥ 0). A block without an entry uses
     * {@link #towerVisitsPerBlock}; read through {@link #visitsFor}. The planner schedules
     * {@code min(visitsFor, capFor)} visits. JSON key {@code towerVisitsByBlock}.
     */
    public Map<String, Integer> towerVisitsByBlock = new TreeMap<>();
    /**
     * Link id of the 12/24 invitation the player accepted, or null when every invitation is
     * declined (the default, DESIGN §10.5). When set, the daytime and the evening of 12/24 are
     * taken (lock reason {@link Rules#LOCK_CHRISTMAS}, 「平安夜约会」). JSON key {@code christmasLink}.
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

    public DormTarget dormTarget(String partnerId) {
        DormTarget target = partnerId == null ? null : dormTargets.get(partnerId);
        return target == null ? DormTarget.NONE : target;
    }

    /**
     * The player chose the romance route with this link. {@link Route#UNDECIDED} (the default)
     * counts as the friend route: the planner never assumes romance on its own.
     */
    public boolean romance(int link) {
        return link >= 0 && link < route.length && route[link] == Route.ROMANCE;
    }

    /** Hard cap of Tartarus visits in the block with this id (its own entry, else {@link #towerCapPerBlock}). */
    public int capFor(String blockId) {
        Integer v = blockId == null ? null : towerCapByBlock.get(blockId);
        return Math.max(0, v != null ? v : towerCapPerBlock);
    }

    /**
     * Wanted Tartarus visits in the block with this id (its own entry, else
     * {@link #towerVisitsPerBlock}); the planner schedules at most {@link #capFor} of them.
     */
    public int visitsFor(String blockId) {
        Integer v = blockId == null ? null : towerVisitsByBlock.get(blockId);
        return Math.max(0, v != null ? v : towerVisitsPerBlock);
    }

    /** A 12/24 invitation was accepted ({@link #christmasLink} set). */
    public boolean christmasTaken() {
        return christmasLink != null && !christmasLink.trim().isEmpty();
    }

    /**
     * Lock reason of {@code slot} under this profile: the pack's lock, else
     * {@link Rules#LOCK_CHRISTMAS} for both slots of 12/24 when an invitation was accepted, else
     * null (free). Labels: {@link Rules#lockReasonZh}.
     */
    public String lockOf(Rules rules, int slot) {
        String lock = rules.lockOf(slot);
        if (lock != null || !christmasTaken()) return lock;
        return Rules.dayOf(slot) == rules.christmasEveDay() ? Rules.LOCK_CHRISTMAS : null;
    }

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

    /**
     * Defaults of a profile made by onboarding (stored profiles are never rewritten): under
     * 「尽量都体验」 the player opts into every 羁绊剧情 track of the pack, since Link Episodes are
     * content to see too. Does nothing when a track is already chosen or under 「尽量多 MAX」.
     */
    public void applyNewProfileDefaults(Rules rules) {
        if (rules == null || style != Style.BREADTH || episodeTracks.length > 0) return;
        episodeTracks = defaultEpisodeTracks(rules);
    }

    /** Ids of every 羁绊剧情 track of the pack with at least one episode, in pack order. */
    public static String[] defaultEpisodeTracks(Rules rules) {
        List<String> ids = new ArrayList<>();
        if (rules != null && rules.episodes != null) {
            for (Rules.Episode track : rules.episodes) {
                if (track != null && track.id != null && track.count > 0 && !ids.contains(track.id)) ids.add(track.id);
            }
        }
        return ids.toArray(new String[0]);
    }

    public boolean hasAnyGoal() {
        for (Tier t : tier) if (t != Tier.NONE) return true;
        return false;
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
        o.put("style", style.json);
        o.put("deepChanges", deepChanges);
        o.put("personaBonus", effectivePersonaMode() == PersonaMode.ALL);
        o.put("personaMode", effectivePersonaMode().name());
        o.put("datingNotes", datingNotes);
        o.put("planDormGardening", planDormGardening);
        JSONObject dorm = new JSONObject();
        for (Map.Entry<String, DormTarget> entry : new TreeMap<>(dormTargets).entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isEmpty() && entry.getValue() != null
                    && entry.getValue() != DormTarget.NONE) dorm.put(entry.getKey(), entry.getValue().name());
        }
        o.put("dormTargets", dorm);
        JSONArray eps = new JSONArray();
        for (String s : episodeTracks) eps.put(s);
        o.put("episodeTracks", eps);
        o.put("charmBonus", charmBonus);
        o.put("goalBuffer", goalBuffer);
        o.put("answers", answers.name());
        o.put("towerVisitsPerBlock", towerVisitsPerBlock);
        o.put("towerCapPerBlock", towerCapPerBlock);
        o.put("cashBuffer", cashBuffer);
        o.put("towerCapByBlock", blockMap(towerCapByBlock));
        o.put("towerVisitsByBlock", blockMap(towerVisitsByBlock));
        if (christmasTaken()) o.put("christmasLink", christmasLink.trim());
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
        p.style = Style.fromJson(o.optString("style", Style.BREADTH.json));
        p.deepChanges = o.optBoolean("deepChanges", false);
        p.personaBonus = o.optBoolean("personaBonus", false);
        p.personaMode = PersonaMode.valueOf(o.optString("personaMode", p.personaBonus ? "ALL" : "NONE"));
        p.personaBonus = p.personaMode == PersonaMode.ALL;
        p.datingNotes = o.optBoolean("datingNotes", true);
        p.planDormGardening = o.optBoolean("planDormGardening", false);
        JSONObject dorm = o.optJSONObject("dormTargets");
        if (dorm != null) for (Iterator<String> it = dorm.keys(); it.hasNext(); ) {
            String id = it.next();
            if (id == null || id.isEmpty()) continue;
            try {
                DormTarget target = DormTarget.valueOf(dorm.optString(id, "NONE"));
                if (target != DormTarget.NONE) p.dormTargets.put(id, target);
            } catch (IllegalArgumentException ignored) { /* Unknown future values keep the conservative NONE default. */ }
        }
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
        p.cashBuffer = Math.max(0, o.optInt("cashBuffer", 0));
        readBlockMap(o.optJSONObject("towerCapByBlock"), p.towerCapByBlock);
        readBlockMap(o.optJSONObject("towerVisitsByBlock"), p.towerVisitsByBlock);
        String christmas = o.isNull("christmasLink") ? null : o.optString("christmasLink", "").trim();
        p.christmasLink = christmas == null || christmas.isEmpty() ? null : christmas;
        JSONArray ex = o.optJSONArray("excludedActivities");
        if (ex != null) {
            p.excludedActivities = new String[ex.length()];
            for (int i = 0; i < ex.length(); i++) p.excludedActivities[i] = ex.getString(i);
        }
        return p;
    }

    private static JSONObject blockMap(Map<String, Integer> map) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Integer> e : new TreeMap<>(map).entrySet()) {
            if (e.getKey() != null && e.getValue() != null) o.put(e.getKey(), Math.max(0, e.getValue()));
        }
        return o;
    }

    /** Reads a block id → count object; non-numeric entries are skipped, negatives become 0. */
    private static void readBlockMap(JSONObject o, Map<String, Integer> into) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String key = it.next();
            Object v = o.opt(key);
            if (key == null || key.isEmpty() || !(v instanceof Number)) continue;
            into.put(key, Math.max(0, ((Number) v).intValue()));
        }
    }
}
