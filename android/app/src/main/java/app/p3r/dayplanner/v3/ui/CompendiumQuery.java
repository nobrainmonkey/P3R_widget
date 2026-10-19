package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.p3r.dayplanner.v3.compendium.Compendium;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;

/** Search and ordering shared by the compendium and its fusion pickers. */
final class CompendiumQuery {
    static final int LEVEL_ASC = 0;
    static final int LEVEL_DESC = 1;
    static final int ARCANA = 2;
    static final int NAME = 3;

    private CompendiumQuery() {}

    static List<Compendium.Persona> find(Compendium data, FusionCalculator calculator,
                                       String query, String arcana, int minLevel, int maxLevel,
                                       boolean favoritesOnly, Set<String> favorites,
                                       boolean showUnowned, int order) {
        String[] words = query == null ? new String[0] : query.trim().toLowerCase(Locale.ROOT).split("\\s+");
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
        if (order == LEVEL_DESC) sort = byLevel.reversed();
        else if (order == ARCANA) sort = Comparator.comparingInt(p -> {
            Compendium.Arcana value = data.arcana(p.arcana);
            return value == null ? Integer.MAX_VALUE : value.order;
        });
        else if (order == NAME) sort = Comparator.comparing(p -> p.nameZh);
        else sort = byLevel;
        result.sort(sort.thenComparing(byLevel).thenComparing(p -> p.nameEn));
        return result;
    }

    private static String searchable(Compendium data, Compendium.Persona persona) {
        StringBuilder text = new StringBuilder(persona.nameZh).append(' ').append(persona.nameEn)
            .append(' ').append(persona.level);
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
        return text.toString().toLowerCase(Locale.ROOT);
    }
}
