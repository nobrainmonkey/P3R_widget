package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which entity every position of a stored state's index-based fields belongs to: link order
 * ({@code rank}, {@code pts}, …), Tartarus blocks ({@code towerVisits}), activities
 * ({@code shifts}, {@code used}), rescues (bits of {@code rescued}/{@code rescueFailed}), Link
 * Episode tracks ({@code episodes}) and each link's unlock steps ({@code unlockStep} counts).
 *
 * <p>Every checkpoint is stored with the layout of the pack it was saved under (JSON key
 * {@value #KEY} next to the {@code SimState} fields). When an app update ships a pack that inserts,
 * removes or reorders entities, {@link #remap} moves each stored value to the entity with the same
 * id; values whose entity no longer exists are counted in {@link Remapped#lost} so the service
 * can ask the player to 同步现状. Pure code, no I/O.
 *
 * <pre>{"links":["fool",…],"blocks":["thebel",…],"activities":["study_room",…],
 *  "rescues":["bunkichi","maiko"],"episodes":["junpei",…],"steps":{"devil":["devil.pay1",…]}}</pre>
 */
public final class PackLayout {
    /** Key of the layout object inside a stored checkpoint's JSON. */
    public static final String KEY = "layout";

    private final List<String> links;
    private final List<String> blocks;
    private final List<String> activities;
    private final List<String> rescues;
    private final List<String> episodes;
    /** Link id → its unlock step ids, in order (links without steps are left out). */
    private final Map<String, List<String>> steps;

    private PackLayout(List<String> links, List<String> blocks, List<String> activities,
                       List<String> rescues, List<String> episodes, Map<String, List<String>> steps) {
        this.links = links;
        this.blocks = blocks;
        this.activities = activities;
        this.rescues = rescues;
        this.episodes = episodes;
        this.steps = steps;
    }

    /** A state carried over to the current pack. */
    public static final class Remapped {
        public final SimState state;
        /** Non-zero stored values that have no place in the current pack (dropped). */
        public final int lost;

        Remapped(SimState state, int lost) {
            this.state = state;
            this.lost = lost;
        }
    }

    /** Layout of {@code rules}. */
    public static PackLayout of(Rules rules) {
        List<String> links = new ArrayList<>();
        Map<String, List<String>> steps = new HashMap<>();
        for (int i = 0; i < rules.links.length; i++) {
            Rules.Link link = rules.links[i];
            String id = idOr(link.id, i);
            links.add(id);
            List<String> ids = stepIds(link);
            if (!ids.isEmpty()) {
                steps.put(id, ids);
            }
        }
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < rules.blocks.length; i++) {
            blocks.add(idOr(rules.blocks[i].id, i));
        }
        List<String> activities = new ArrayList<>();
        for (int i = 0; i < rules.activities.length; i++) {
            activities.add(idOr(rules.activities[i].id, i));
        }
        List<String> rescues = new ArrayList<>();
        for (int i = 0; i < rules.rescues.length; i++) {
            rescues.add(idOr(rules.rescues[i].id, i));
        }
        List<String> episodes = new ArrayList<>();
        for (int i = 0; i < rules.episodes.length; i++) {
            episodes.add(idOr(rules.episodes[i].id, i));
        }
        return new PackLayout(links, blocks, activities, rescues, episodes, steps);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("links", new JSONArray(links));
        o.put("blocks", new JSONArray(blocks));
        o.put("activities", new JSONArray(activities));
        o.put("rescues", new JSONArray(rescues));
        o.put("episodes", new JSONArray(episodes));
        JSONObject s = new JSONObject();
        for (Map.Entry<String, List<String>> e : steps.entrySet()) {
            s.put(e.getKey(), new JSONArray(e.getValue()));
        }
        o.put("steps", s);
        return o;
    }

    public static PackLayout fromJson(JSONObject o) throws JSONException {
        Map<String, List<String>> steps = new HashMap<>();
        JSONObject s = o.optJSONObject("steps");
        if (s != null) {
            Iterator<String> keys = s.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                steps.put(key, strings(s.getJSONArray(key)));
            }
        }
        return new PackLayout(strings(o.optJSONArray("links")), strings(o.optJSONArray("blocks")),
                strings(o.optJSONArray("activities")), strings(o.optJSONArray("rescues")),
                strings(o.optJSONArray("episodes")), steps);
    }

    /**
     * Layout stored in a checkpoint's JSON, or null for checkpoints saved before layouts existed
     * (or a malformed layout, which is treated the same way).
     */
    public static PackLayout inCheckpoint(JSONObject checkpointJson) {
        JSONObject o = checkpointJson.optJSONObject(KEY);
        if (o == null) {
            return null;
        }
        try {
            return fromJson(o);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Carries {@code s} (stored under this layout) over to the layout of {@code rules}: every
     * value moves to the entity with the same id. The result is also sized for {@code rules}
     * (see {@link StateShape#fit}); {@code s} is not modified.
     */
    public Remapped remap(SimState s, Rules rules) {
        PackLayout now = of(rules);
        SimState out = s.copy();
        int[] lost = {0};

        int[] linkMap = map(links, now.links);
        out.rank = moveInts(s.rank, linkMap, SimState.LINKS, 0, lost);
        out.pts = moveInts(s.pts, linkMap, SimState.LINKS, 0, null);
        out.lastStepDay = moveInts(s.lastStepDay, linkMap, SimState.LINKS, -1, null);
        out.heldRank = moveInts(s.heldRank, linkMap, SimState.LINKS, 0, null);
        out.ready = new boolean[SimState.LINKS];
        for (int i = 0; i < s.ready.length && i < linkMap.length; i++) {
            if (linkMap[i] >= 0 && linkMap[i] < SimState.LINKS) {
                out.ready[linkMap[i]] = s.ready[i];
            }
        }
        out.unlockStep = new int[SimState.LINKS];
        for (int i = 0; i < s.unlockStep.length && i < linkMap.length; i++) {
            int j = linkMap[i];
            if (j < 0 || j >= SimState.LINKS || j >= rules.links.length) {
                continue;
            }
            out.unlockStep[j] = unlockCount(s.unlockStep[i], steps.get(links.get(i)),
                    stepIds(rules.links[j]), out.rank[j], lost);
        }

        out.towerVisits = moveInts(s.towerVisits, map(blocks, now.blocks), rules.blocks.length, 0, lost);
        int[] activityMap = map(activities, now.activities);
        out.shifts = moveInts(s.shifts, activityMap, rules.activities.length, 0, lost);
        out.used = moveInts(s.used, activityMap, rules.activities.length, 0, lost);
        out.episodes = moveInts(s.episodes, map(episodes, now.episodes), rules.episodes.length, 0, lost);
        int[] rescueMap = map(rescues, now.rescues);
        out.rescued = moveBits(s.rescued, rescueMap, lost);
        out.rescueFailed = moveBits(s.rescueFailed, rescueMap, lost);
        return new Remapped(StateShape.fit(out, rules), lost[0]);
    }

    /**
     * {@link StateShape#fit} for a state without a stored layout (saved before layouts existed):
     * positions are kept as they are; non-zero values beyond the current pack's sizes are dropped
     * and counted.
     */
    public static Remapped fitWithoutLayout(SimState s, Rules rules) {
        int lost = dropped(s.towerVisits, rules.blocks.length)
                + dropped(s.shifts, rules.activities.length)
                + dropped(s.used, rules.activities.length)
                + dropped(s.episodes, rules.episodes.length);
        int rescues = Math.min(64, rules.rescues.length);
        long beyond = rescues >= 64 ? 0 : -1L << rescues;
        lost += Long.bitCount((s.rescued | s.rescueFailed) & beyond);
        SimState out = StateShape.fit(s, rules);
        out.rescued &= ~beyond;
        out.rescueFailed &= ~beyond;
        return new Remapped(out, lost);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PackLayout)) {
            return false;
        }
        PackLayout p = (PackLayout) o;
        return links.equals(p.links) && blocks.equals(p.blocks) && activities.equals(p.activities)
                && rescues.equals(p.rescues) && episodes.equals(p.episodes) && steps.equals(p.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(links, blocks, activities, rescues, episodes, steps);
    }

    // ------------------------------------------------------------------------------ helpers

    private static String idOr(String id, int index) {
        return id == null || id.isEmpty() ? "#" + index : id;
    }

    private static List<String> stepIds(Rules.Link link) {
        List<String> ids = new ArrayList<>();
        if (link.unlock == null) {
            return ids;
        }
        for (int k = 0; k < link.unlock.steps.size(); k++) {
            ids.add(idOr(link.unlock.steps.get(k).id, k));
        }
        return ids;
    }

    private static List<String> strings(JSONArray a) throws JSONException {
        if (a == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            out.add(a.getString(i));
        }
        return out;
    }

    /** For each old position, the new position of the same id, or -1. */
    private static int[] map(List<String> from, List<String> to) {
        Map<String, Integer> index = new HashMap<>();
        for (int j = 0; j < to.size(); j++) {
            index.putIfAbsent(to.get(j), j);
        }
        int[] out = new int[from.size()];
        for (int i = 0; i < from.size(); i++) {
            Integer j = index.get(from.get(i));
            out[i] = j == null ? -1 : j;
        }
        return out;
    }

    /**
     * Moves {@code values[i]} to {@code map[i]}; positions without an id (beyond the stored
     * layout) or without a new place count as lost when non-zero ({@code lost} may be null).
     */
    private static int[] moveInts(int[] values, int[] map, int size, int fill, int[] lost) {
        int[] out = new int[size];
        Arrays.fill(out, fill);
        if (values == null) {
            return out;
        }
        for (int i = 0; i < values.length; i++) {
            int j = i < map.length ? map[i] : -1;
            if (j >= 0 && j < size) {
                out[j] = values[i];
            } else if (lost != null && values[i] != fill) {
                lost[0]++;
            }
        }
        return out;
    }

    private static long moveBits(long bits, int[] map, int[] lost) {
        long out = 0;
        for (int i = 0; i < 64; i++) {
            if ((bits & (1L << i)) == 0) {
                continue;
            }
            int j = i < map.length ? map[i] : -1;
            if (j >= 0 && j < 64) {
                out |= 1L << j;
            } else {
                lost[0]++;
            }
        }
        return out;
    }

    /**
     * New unlock-step count: the longest prefix of the new step list whose steps were all done
     * under the old list. An established link keeps every step done.
     */
    private static int unlockCount(int count, List<String> oldIds, List<String> newIds, int rank, int[] lost) {
        List<String> old = oldIds == null ? Collections.emptyList() : oldIds;
        Set<String> done = new HashSet<>(old.subList(0, Math.max(0, Math.min(count, old.size()))));
        if (rank >= 1 && count >= old.size()) {
            return newIds.size();
        }
        int n = 0;
        while (n < newIds.size() && done.contains(newIds.get(n))) {
            n++;
        }
        if (rank == 0 && n != done.size()) {
            lost[0]++;
        }
        return n;
    }

    private static int dropped(int[] values, int size) {
        int n = 0;
        for (int i = size; values != null && i < values.length; i++) {
            if (values[i] != 0) {
                n++;
            }
        }
        return n;
    }
}
