package app.p3r.dayplanner.v3.compendium;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline Reload compendium, independent of the calendar and Android framework.
 * Data and fusion rules are credited to aqiu384's megaten-database and
 * megaten-fusion-tool (Unlicense); the bundled catalogue retains source revisions.
 */
public final class Compendium {
    public static final String ASSET_NAME = "p3r_compendium.json";
    public static final String DLC_P5_FIRST = "p5r_1";
    public static final String DLC_P5_SECOND = "p5r_2";
    public static final String DLC_P4 = "p4g";

    public static final class Arcana {
        public final String id, nameZh, nameEn;
        public final int order;
        private Arcana(JSONObject row) throws JSONException {
            id = required(row, "id");
            nameZh = required(row, "nameZh");
            nameEn = required(row, "nameEn");
            order = row.getInt("order");
        }
    }

    public static final class DlcPack {
        public final String id, nameZh, nameEn;
        private DlcPack(JSONObject row) throws JSONException {
            id = required(row, "id");
            nameZh = required(row, "nameZh");
            nameEn = required(row, "nameEn");
        }
    }

    public static final class Source {
        public final String name, url, commit, license;
        private Source(JSONObject row) {
            name = row.optString("name", "");
            url = row.optString("url", "");
            commit = row.optString("commit", "");
            license = row.optString("license", "");
        }
    }

    public static final class Skill {
        public final String id, nameZh, nameEn, element, target, targetZh, costType, effectZh, effectEn;
        public final int cost, power, minHits, maxHits, accuracy, critical;
        public final boolean unique;
        private Skill(JSONObject row) throws JSONException {
            id = required(row, "id");
            nameZh = required(row, "nameZh");
            nameEn = required(row, "nameEn");
            element = required(row, "element");
            target = row.optString("target", "");
            targetZh = row.optString("targetZh", target);
            costType = row.optString("costType", "none");
            cost = row.optInt("cost", 0);
            power = row.optInt("power", 0);
            minHits = row.optInt("minHits", 1);
            maxHits = row.optInt("maxHits", minHits);
            accuracy = row.optInt("accuracy", 0);
            critical = row.optInt("critical", 0);
            unique = row.optBoolean("unique", false);
            effectZh = row.optString("effectZh", "");
            effectEn = row.optString("effectEn", "");
        }
        public String costLabel() {
            if (costType.equals("HP")) return cost + "%HP";
            if (costType.equals("SP")) return cost + "SP";
            return "";
        }
    }

    public static final class LearnedSkill {
        public final String skillId;
        /** Zero means innate; a positive value is the Persona level needed. */
        public final int level;
        private LearnedSkill(JSONObject row) throws JSONException {
            skillId = required(row, "id");
            level = row.getInt("level");
            check(level >= 0 && level <= 99, "Invalid learned skill level: " + skillId);
        }
    }

    public static final class Persona {
        public final String id, key, nameZh, nameEn, arcana, resists, inherits, dlc;
        public final int level;
        /** Base Strength, Magic, Endurance, Agility and Luck, in this order. */
        public final int[] stats;
        public final List<LearnedSkill> skills;
        public final List<String> theurgySkillIds;
        public final String unlockType, unlockTextZh, unlockTextEn;
        private Persona(JSONObject row, int elementCount) throws JSONException {
            id = required(row, "id");
            key = required(row, "key");
            nameZh = required(row, "nameZh");
            nameEn = required(row, "nameEn");
            arcana = required(row, "arcana");
            level = row.getInt("level");
            check(level >= 1 && level <= 99, "Invalid Persona level: " + id);
            JSONArray values = row.getJSONArray("stats");
            check(values.length() == 5, "Persona stats must have 5 entries: " + id);
            stats = new int[5];
            for (int i = 0; i < stats.length; i++) stats[i] = values.getInt(i);
            resists = required(row, "resists");
            check(resists.length() == elementCount, "Persona resistances have the wrong length: " + id);
            for (int i = 0; i < resists.length(); i++) {
                check("-wsnrd".indexOf(resists.charAt(i)) >= 0, "Unknown resistance code: " + id);
            }
            inherits = row.optString("inherits", "none");
            dlc = row.optString("dlc", "");
            List<LearnedSkill> learned = new ArrayList<>();
            JSONArray skillRows = row.getJSONArray("skills");
            for (int i = 0; i < skillRows.length(); i++) learned.add(new LearnedSkill(skillRows.getJSONObject(i)));
            skills = Collections.unmodifiableList(learned);
            theurgySkillIds = strings(row.optJSONArray("theurgySkillIds"));
            JSONObject unlock = row.optJSONObject("unlock");
            unlockType = unlock == null ? "" : unlock.optString("type", "");
            unlockTextZh = unlock == null ? "" : unlock.optString("textZh", "");
            unlockTextEn = unlock == null ? "" : unlock.optString("textEn", "");
        }
        public boolean isDlc() { return !dlc.isEmpty(); }
    }

    public static final class SpecialRecipe {
        public final String resultId;
        public final List<String> ingredientIds;
        private SpecialRecipe(JSONObject row) throws JSONException {
            resultId = required(row, "resultId");
            ingredientIds = strings(row.getJSONArray("ingredientIds"));
            check(ingredientIds.size() >= 2 && ingredientIds.size() <= 6,
                    "Reload special fusion needs 2 to 6 ingredients: " + resultId);
            check(new HashSet<>(ingredientIds).size() == ingredientIds.size(),
                    "Repeated special fusion ingredient: " + resultId);
        }
    }

    private final Map<String, Persona> personasById, personasByKey;
    private final Map<String, Skill> skillsById;
    private final Map<String, Arcana> arcanaById;
    private final List<Persona> personas;
    private final List<Skill> skills;
    private final List<Arcana> arcana;
    private final List<DlcPack> dlcPacks;
    private final List<Source> sources;
    private final List<String> elements;
    private final List<SpecialRecipe> specialRecipes;
    private final Map<String, Map<String, String>> fusionChart;

    private Compendium(JSONObject root) throws JSONException {
        check(root.getInt("schemaVersion") == 1, "Unsupported compendium schema");
        check(root.getString("game").equals("p3r"), "Only the Reload base game is supported");
        elements = strings(root.getJSONArray("elements"));
        check(elements.size() == 10, "Reload requires 10 resistance elements");

        List<Arcana> arcanaRows = new ArrayList<>();
        Map<String, Arcana> arcanaMap = new LinkedHashMap<>();
        JSONArray rows = root.getJSONArray("arcana");
        for (int i = 0; i < rows.length(); i++) {
            Arcana item = new Arcana(rows.getJSONObject(i));
            check(arcanaMap.put(item.id, item) == null, "Duplicate arcana: " + item.id);
            arcanaRows.add(item);
        }
        arcana = Collections.unmodifiableList(arcanaRows);
        arcanaById = Collections.unmodifiableMap(arcanaMap);

        List<DlcPack> packs = new ArrayList<>();
        Set<String> packIds = new HashSet<>();
        rows = root.getJSONArray("dlcPacks");
        for (int i = 0; i < rows.length(); i++) {
            DlcPack pack = new DlcPack(rows.getJSONObject(i));
            check(packIds.add(pack.id), "Duplicate DLC pack: " + pack.id);
            packs.add(pack);
        }
        dlcPacks = Collections.unmodifiableList(packs);

        Map<String, Skill> skillMap = new LinkedHashMap<>();
        rows = root.getJSONArray("skills");
        for (int i = 0; i < rows.length(); i++) {
            Skill item = new Skill(rows.getJSONObject(i));
            check(skillMap.put(item.id, item) == null, "Duplicate skill: " + item.id);
        }
        skillsById = Collections.unmodifiableMap(skillMap);
        skills = Collections.unmodifiableList(new ArrayList<>(skillMap.values()));

        Map<String, Persona> personaMap = new LinkedHashMap<>();
        Map<String, Persona> keyMap = new LinkedHashMap<>();
        Set<String> arcanaLevels = new HashSet<>();
        rows = root.getJSONArray("personas");
        for (int i = 0; i < rows.length(); i++) {
            Persona item = new Persona(rows.getJSONObject(i), elements.size());
            check(personaMap.put(item.id, item) == null, "Duplicate Persona id: " + item.id);
            check(keyMap.put(item.key, item) == null, "Duplicate Persona key: " + item.key);
            check(arcanaMap.containsKey(item.arcana), "Unknown Persona arcana: " + item.id);
            check(item.dlc.isEmpty() || packIds.contains(item.dlc), "Unknown Persona DLC pack: " + item.id);
            check(arcanaLevels.add(item.arcana + ":" + item.level), "Duplicate Persona level in arcana: " + item.id);
            for (LearnedSkill learned : item.skills) check(skillMap.containsKey(learned.skillId),
                    "Unknown learned skill: " + learned.skillId);
            for (String skillId : item.theurgySkillIds) check(skillMap.containsKey(skillId),
                    "Unknown Theurgy skill: " + skillId);
        }
        personasById = Collections.unmodifiableMap(personaMap);
        personasByKey = Collections.unmodifiableMap(keyMap);
        personas = Collections.unmodifiableList(new ArrayList<>(personaMap.values()));

        JSONObject fusion = root.getJSONObject("fusion");
        List<String> fusionArcana = strings(fusion.getJSONArray("arcana"));
        JSONArray table = fusion.getJSONArray("chart");
        check(table.length() == fusionArcana.size(), "Wrong fusion chart height");
        Map<String, Map<String, String>> chart = new LinkedHashMap<>();
        for (String race : fusionArcana) {
            check(arcanaMap.containsKey(race), "Unknown fusion chart arcana: " + race);
            check(!chart.containsKey(race), "Duplicate fusion chart arcana: " + race);
            chart.put(race, new LinkedHashMap<>());
        }
        for (int i = 0; i < table.length(); i++) {
            JSONArray row = table.getJSONArray(i);
            check(row.length() == i + 1, "Fusion chart must be lower triangular");
            for (int j = 0; j <= i; j++) {
                String result = row.getString(j);
                check(result.equals("-") || arcanaMap.containsKey(result), "Unknown fusion result arcana: " + result);
                chart.get(fusionArcana.get(i)).put(fusionArcana.get(j), result);
                chart.get(fusionArcana.get(j)).put(fusionArcana.get(i), result);
            }
        }
        fusionChart = chart;

        List<SpecialRecipe> recipes = new ArrayList<>();
        Set<String> specialResults = new HashSet<>();
        rows = fusion.getJSONArray("specialRecipes");
        for (int i = 0; i < rows.length(); i++) {
            SpecialRecipe recipe = new SpecialRecipe(rows.getJSONObject(i));
            check(personaMap.containsKey(recipe.resultId), "Unknown special fusion result: " + recipe.resultId);
            check(specialResults.add(recipe.resultId), "Duplicate special fusion result: " + recipe.resultId);
            for (String id : recipe.ingredientIds) check(personaMap.containsKey(id), "Unknown special fusion ingredient: " + id);
            recipes.add(recipe);
        }
        specialRecipes = Collections.unmodifiableList(recipes);
        List<Source> sourceRows = new ArrayList<>();
        rows = root.optJSONArray("sources");
        if (rows != null) for (int i = 0; i < rows.length(); i++) sourceRows.add(new Source(rows.getJSONObject(i)));
        sources = Collections.unmodifiableList(sourceRows);
    }

    /** Reads a complete catalogue without closing the caller's stream. */
    public static Compendium load(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    public static Compendium parse(String json) throws IOException {
        try {
            return new Compendium(new JSONObject(json));
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Invalid compendium: " + error.getMessage(), error);
        }
    }

    public List<Persona> personas() { return personas; }
    public Persona persona(String id) { return personasById.get(id); }
    public Persona personaByKey(String key) { return personasByKey.get(key); }
    public List<Skill> skills() { return skills; }
    public Skill skill(String id) { return skillsById.get(id); }
    public List<Arcana> arcana() { return arcana; }
    public Arcana arcana(String id) { return arcanaById.get(id); }
    public List<DlcPack> dlcPacks() { return dlcPacks; }
    public List<Source> sources() { return sources; }
    public List<String> elements() { return elements; }
    public List<SpecialRecipe> specialRecipes() { return specialRecipes; }

    /** Null means the arcana are absent from Reload's normal fusion table. */
    String resultArcana(String first, String second) {
        Map<String, String> row = fusionChart.get(first);
        return row == null ? null : row.get(second);
    }

    public String resistanceLabel(char code) {
        switch (code) {
            case 'w': return "弱";
            case 's': return "耐";
            case 'n': return "无";
            case 'r': return "反";
            case 'd': return "吸";
            default: return "—";
        }
    }

    private static List<String> strings(JSONArray values) throws JSONException {
        if (values == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
        return Collections.unmodifiableList(result);
    }
    private static String required(JSONObject row, String field) throws JSONException {
        String value = row.getString(field);
        check(!value.trim().isEmpty(), "Empty " + field);
        return value;
    }
    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
