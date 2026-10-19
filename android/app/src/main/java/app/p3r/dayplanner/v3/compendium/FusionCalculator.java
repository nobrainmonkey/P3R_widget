package app.p3r.dayplanner.v3.compendium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.p3r.dayplanner.v3.compendium.Compendium.Persona;
import app.p3r.dayplanner.v3.compendium.Compendium.SpecialRecipe;

/**
 * Reload's deterministic two-Persona and fixed special fusions. Ported from
 * aqiu384/megaten-fusion-tool (Unlicense), compendium/fusions/{smt,per}-nonelem-fusions.ts.
 * Uses base levels, excludes special-only results from normal ladders, and rebuilds those
 * ladders for the owned DLC packs. Story unlock requirements remain visible on the result;
 * they do not silently turn one recipe into another Persona.
 */
public final class FusionCalculator {
    public static final class Recipe {
        public final Persona result;
        public final List<Persona> ingredients;
        public final boolean special;
        public final int maxIngredientLevel;
        private Recipe(Persona result, List<Persona> ingredients, boolean special) {
            this.result = result;
            this.ingredients = Collections.unmodifiableList(new ArrayList<>(ingredients));
            this.special = special;
            int maximum = 0;
            for (Persona ingredient : ingredients) maximum = Math.max(maximum, ingredient.level);
            maxIngredientLevel = maximum;
        }
    }

    private final Compendium compendium;
    private final Set<String> ownedDlcPacks;
    private final List<Persona> availablePersonas;
    private final Map<String, List<Persona>> resultLadders = new HashMap<>();
    private final Map<String, SpecialRecipe> specialByResult = new HashMap<>();
    private final Map<String, SpecialRecipe> specialByPair = new HashMap<>();
    private Map<String, List<Recipe>> reverseRecipes;

    public FusionCalculator(Compendium compendium, Set<String> ownedDlcPacks) {
        this.compendium = compendium;
        Set<String> allowed = new HashSet<>();
        for (Compendium.DlcPack pack : compendium.dlcPacks()) {
            if (ownedDlcPacks.contains(pack.id)) allowed.add(pack.id);
        }
        this.ownedDlcPacks = Collections.unmodifiableSet(allowed);
        for (SpecialRecipe recipe : compendium.specialRecipes()) {
            specialByResult.put(recipe.resultId, recipe);
            if (recipe.ingredientIds.size() == 2) specialByPair.put(
                    pairKey(recipe.ingredientIds.get(0), recipe.ingredientIds.get(1)), recipe);
        }
        List<Persona> available = new ArrayList<>();
        for (Persona persona : compendium.personas()) {
            if (!isAvailable(persona)) continue;
            available.add(persona);
            if (!specialByResult.containsKey(persona.id)) {
                List<Persona> ladder = resultLadders.get(persona.arcana);
                if (ladder == null) {
                    ladder = new ArrayList<>();
                    resultLadders.put(persona.arcana, ladder);
                }
                ladder.add(persona);
            }
        }
        for (List<Persona> ladder : resultLadders.values()) ladder.sort(Comparator.comparingInt(p -> p.level));
        availablePersonas = Collections.unmodifiableList(available);
    }

    public Set<String> ownedDlcPacks() { return ownedDlcPacks; }
    public List<Persona> availablePersonas() { return availablePersonas; }

    /** Availability means owned DLC, not completion of the displayed story prerequisite. */
    public boolean isAvailable(Persona persona) {
        return persona != null && (!persona.isDlc() || ownedDlcPacks.contains(persona.dlc));
    }

    /** Returns null for an invalid pair or a pair requiring an unowned DLC pack. */
    public Recipe fuse(String firstId, String secondId) {
        Persona first = compendium.persona(firstId);
        Persona second = compendium.persona(secondId);
        if (first == null || second == null || first.id.equals(second.id)
                || !isAvailable(first) || !isAvailable(second)) return null;
        SpecialRecipe special = specialByPair.get(pairKey(firstId, secondId));
        if (special != null) return specialRecipe(special);

        Persona result = null;
        if (first.arcana.equals(second.arcana)) {
            List<Persona> ladder = resultLadders.get(first.arcana);
            if (ladder == null) return null;
            // Same-arcana fusion rounds down the (base-level average + 1) ladder,
            // excluding either ingredient from the possible results.
            int twiceThreshold = first.level + second.level + 2;
            for (Persona candidate : ladder) {
                if (candidate.level * 2 > twiceThreshold) break;
                if (!candidate.id.equals(firstId) && !candidate.id.equals(secondId)) result = candidate;
            }
        } else {
            String arcana = compendium.resultArcana(first.arcana, second.arcana);
            List<Persona> ladder = resultLadders.get(arcana);
            if (ladder == null || ladder.isEmpty()) return null;
            // Different-arcana fusion rounds up average + 0.5; above the top level
            // the tool selects the highest normal Persona in the resulting arcana.
            int twiceThreshold = first.level + second.level + 1;
            result = ladder.get(ladder.size() - 1);
            for (Persona candidate : ladder) {
                if (candidate.level * 2 >= twiceThreshold) {
                    result = candidate;
                    break;
                }
            }
            if (result.id.equals(firstId) || result.id.equals(secondId)) {
                int index = ladder.indexOf(result);
                if (index + 1 < ladder.size()) result = ladder.get(index + 1);
            }
        }
        if (result == null) return null;
        List<Persona> ingredients = new ArrayList<>();
        ingredients.add(first);
        ingredients.add(second);
        return new Recipe(result, ingredients, false);
    }

    /** All normal pairs or the fixed special recipe for a target, cheapest levels first. */
    public synchronized List<Recipe> recipesFor(String targetId) {
        if (!isAvailable(compendium.persona(targetId))) return Collections.emptyList();
        if (reverseRecipes == null) buildReverseRecipes();
        List<Recipe> recipes = reverseRecipes.get(targetId);
        return recipes == null ? Collections.emptyList() : recipes;
    }

    private Recipe specialRecipe(SpecialRecipe special) {
        Persona result = compendium.persona(special.resultId);
        if (!isAvailable(result)) return null;
        List<Persona> ingredients = new ArrayList<>();
        for (String id : special.ingredientIds) {
            Persona ingredient = compendium.persona(id);
            if (!isAvailable(ingredient)) return null;
            ingredients.add(ingredient);
        }
        return new Recipe(result, ingredients, true);
    }

    private void buildReverseRecipes() {
        Map<String, List<Recipe>> recipes = new LinkedHashMap<>();
        for (int i = 0; i < availablePersonas.size(); i++) {
            for (int j = i + 1; j < availablePersonas.size(); j++) {
                Recipe recipe = fuse(availablePersonas.get(i).id, availablePersonas.get(j).id);
                if (recipe != null) addRecipe(recipes, recipe);
            }
        }
        for (SpecialRecipe special : compendium.specialRecipes()) {
            if (special.ingredientIds.size() <= 2) continue; // Two-input specials were added above.
            Recipe recipe = specialRecipe(special);
            if (recipe != null) addRecipe(recipes, recipe);
        }
        Comparator<Recipe> order = Comparator.comparingInt((Recipe recipe) -> recipe.maxIngredientLevel)
                .thenComparingInt(recipe -> ingredientLevelSum(recipe))
                .thenComparing(recipe -> recipe.ingredients.get(0).nameEn)
                .thenComparing(recipe -> recipe.ingredients.get(1).nameEn);
        for (Map.Entry<String, List<Recipe>> entry : recipes.entrySet()) {
            entry.getValue().sort(order);
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        reverseRecipes = recipes;
    }

    private static void addRecipe(Map<String, List<Recipe>> recipes, Recipe recipe) {
        List<Recipe> forResult = recipes.get(recipe.result.id);
        if (forResult == null) {
            forResult = new ArrayList<>();
            recipes.put(recipe.result.id, forResult);
        }
        forResult.add(recipe);
    }
    private static int ingredientLevelSum(Recipe recipe) {
        int total = 0;
        for (Persona persona : recipe.ingredients) total += persona.level;
        return total;
    }
    private static String pairKey(String first, String second) {
        return first.compareTo(second) < 0 ? first + ":" + second : second + ":" + first;
    }
}
