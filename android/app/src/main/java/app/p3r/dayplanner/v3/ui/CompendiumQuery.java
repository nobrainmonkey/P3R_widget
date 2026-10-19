package app.p3r.dayplanner.v3.ui;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.p3r.dayplanner.v3.compendium.Compendium;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;

/** Search and ordering shared by the compendium and its fusion pickers. */
final class CompendiumQuery {
    static final int LEVEL_ASC = 0;
    static final int LEVEL_DESC = 1;
    static final int ARCANA = 2;
    static final int ARCANA_DESC = 4;

    /** The searchable text of each Persona, built once per loaded catalogue. */
    private static final Map<Compendium.Persona, String> SEARCHABLE =
        Collections.synchronizedMap(new WeakHashMap<>());

    private CompendiumQuery() {}

    /**
     * Personas matching every word of {@code query} (Chinese or English name, level, arcana,
     * learned and Theurgy skills, in either language). Full-width input from a Chinese keyboard
     * (「Ｏｒｐｈｅｕｓ」) matches like half-width, and English names match without their spaces
     * (「aramitama」).
     */
    static List<Compendium.Persona> find(Compendium data, FusionCalculator calculator,
                                       String query, String arcana, int minLevel, int maxLevel,
                                       boolean favoritesOnly, Set<String> favorites,
                                       boolean showUnowned, int order) {
        String[] words = normalize(query).split("\\s+");
        List<Compendium.Persona> result = new ArrayList<>();
        for (Compendium.Persona persona : data.personas()) {
            if (!showUnowned && !calculator.isAvailable(persona)) continue;
            if (favoritesOnly && !favorites.contains(persona.id)) continue;
            if (arcana != null && !arcana.equals(persona.arcana)) continue;
            if (persona.level < minLevel || persona.level > maxLevel) continue;
            String searchable = searchable(data, persona);
            boolean match = true;
            for (String word : words) if (!searchable.contains(word)) { match = false; break; }
            if (match) result.add(persona);
        }
        Comparator<Compendium.Persona> byLevel = Comparator.comparingInt(p -> p.level);
        Comparator<Compendium.Persona> sort;
        if (order == ARCANA || order == ARCANA_DESC) sort = Comparator.comparingInt((Compendium.Persona p) -> {
            Compendium.Arcana value = data.arcana(p.arcana);
            return value == null ? Integer.MAX_VALUE : value.order;
        }).thenComparing(byLevel);
        else sort = byLevel;
        if (order == LEVEL_DESC || order == ARCANA_DESC) sort = sort.reversed();
        result.sort(sort.thenComparing(p -> p.nameEn));
        return result;
    }

    /** NFKC (full-width letters and digits become half-width), trimmed, lower case. */
    static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }

    private static String searchable(Compendium data, Compendium.Persona persona) {
        String cached = SEARCHABLE.get(persona);
        if (cached != null) return cached;
        StringBuilder text = new StringBuilder(persona.nameZh).append(' ').append(persona.nameEn)
            .append(' ').append(persona.nameEn.replace(" ", "")).append(' ').append(persona.level);
        Compendium.Arcana arcana = data.arcana(persona.arcana);
        if (arcana != null) text.append(' ').append(arcana.nameZh).append(' ').append(arcana.nameEn);
        for (Compendium.LearnedSkill learned : persona.skills) {
            Compendium.Skill skill = data.skill(learned.skillId);
            if (skill != null) text.append(' ').append(skill.nameZh).append(' ').append(skill.nameEn);
        }
        for (String id : persona.theurgySkillIds) {
            Compendium.Skill skill = data.skill(id);
            if (skill != null) text.append(' ').append(skill.nameZh).append(' ').append(skill.nameEn);
        }
        String value = normalize(text.toString());
        SEARCHABLE.put(persona, value);
        return value;
    }
}
