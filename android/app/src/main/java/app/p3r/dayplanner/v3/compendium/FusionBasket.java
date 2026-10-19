package app.p3r.dayplanner.v3.compendium;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.p3r.dayplanner.v3.compendium.Compendium.Persona;
import app.p3r.dayplanner.v3.compendium.FusionCalculator.Recipe;

/**
 * A manually selected fusion dependency graph. Choosing a missing ingredient's recipe expands
 * that branch; it does not add another requested final Persona or change the fusion calculator.
 * Registration checkboxes mean the Persona can be summoned from the game's compendium.
 * Registration is reusable and never substitutes for an explicitly selected crafting recipe.
 */
public final class FusionBasket {
    private static final int MAX_PERSONAS = 194;
    private static final Pattern PERSONA_ID = Pattern.compile("persona_[0-9]{1,6}");

    public static final class Choice {
        public final String resultId;
        public final List<String> ingredientIds;
        private Choice(String resultId, List<String> ingredientIds) {
            this.resultId = resultId;
            this.ingredientIds = Collections.unmodifiableList(new ArrayList<>(ingredientIds));
        }
    }

    public static final class ShoppingItem {
        public final Persona persona;
        public final int quantity;
        public final boolean registered;
        private ShoppingItem(Persona persona, int quantity, boolean registered) {
            this.persona = persona;
            this.quantity = quantity;
            this.registered = registered;
        }
    }

    public static final class CraftStep {
        public final Persona result;
        public final List<Persona> ingredients;
        public final int quantity;
        /** False means this saved choice is invalid for the current DLC selection. */
        public final boolean valid;
        private CraftStep(Persona result, List<Persona> ingredients, int quantity, boolean valid) {
            this.result = result;
            this.ingredients = Collections.unmodifiableList(new ArrayList<>(ingredients));
            this.quantity = quantity;
            this.valid = valid;
        }
    }

    public static final class Node {
        public final Persona persona;
        /** ownedQuantity is the registered quantity, not an inventory allocation. */
        public final int quantity, ownedQuantity, summonQuantity, craftQuantity;
        public final boolean invalidRecipe;
        public final List<Node> children;
        private Node(Persona persona, int quantity, int ownedQuantity, int summonQuantity,
                     int craftQuantity, boolean invalidRecipe, List<Node> children) {
            this.persona = persona;
            this.quantity = quantity;
            this.ownedQuantity = ownedQuantity;
            this.summonQuantity = summonQuantity;
            this.craftQuantity = craftQuantity;
            this.invalidRecipe = invalidRecipe;
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }
    }

    public static final class Summary {
        public final List<Node> roots;
        /** Dependencies precede consumers; repeated crafts separated by a consumer stay separate. */
        public final List<CraftStep> steps;
        /** All leaf materials to summon; ownedUsed is their registered subset for compatibility. */
        public final List<ShoppingItem> shopping, ownedUsed;
        public final List<ShoppingItem> unregistered;
        /** Result ids of blocked branches; those branches contribute no fictional shopping list. */
        public final List<String> invalidRecipes;
        public final boolean valid;
        public final boolean allRegistered;
        private Summary(List<Node> roots, List<CraftStep> steps, List<ShoppingItem> shopping,
                        List<ShoppingItem> ownedUsed, Set<String> invalidRecipes) {
            this.roots = Collections.unmodifiableList(roots);
            this.steps = Collections.unmodifiableList(steps);
            this.shopping = Collections.unmodifiableList(shopping);
            this.ownedUsed = Collections.unmodifiableList(ownedUsed);
            List<ShoppingItem> missing = new ArrayList<>();
            for (ShoppingItem item : shopping) if (!item.registered) missing.add(item);
            unregistered = Collections.unmodifiableList(missing);
            this.invalidRecipes = Collections.unmodifiableList(new ArrayList<>(invalidRecipes));
            valid = invalidRecipes.isEmpty();
            allRegistered = valid && missing.isEmpty();
        }
    }

    private final List<String> goals = new ArrayList<>();
    private final Map<String, Choice> choices = new LinkedHashMap<>();

    public FusionBasket() { }

    public List<String> goals() { return Collections.unmodifiableList(new ArrayList<>(goals)); }
    public boolean containsGoal(String personaId) { return goals.contains(personaId); }
    public Choice chosenRecipe(String personaId) { return choices.get(personaId); }

    /** Adds a final requested result and its recipe; repeated taps do not duplicate the root. */
    public boolean addGoal(Recipe recipe) {
        if (recipe == null) return false;
        Choice choice = fromRecipe(recipe);
        Map<String, Choice> updated = new LinkedHashMap<>(choices);
        updated.put(choice.resultId, choice);
        if (hasCycle(updated)) return false;
        choices.clear();
        choices.putAll(updated);
        if (!goals.contains(choice.resultId)) goals.add(choice.resultId);
        pruneUnreachable();
        return true;
    }

    /** Selects how to craft an existing branch without creating another final requested result. */
    public boolean chooseRecipe(Recipe recipe) {
        if (recipe == null || !reachable().contains(recipe.result.id)) return false;
        Choice choice = fromRecipe(recipe);
        Map<String, Choice> updated = new LinkedHashMap<>(choices);
        updated.put(choice.resultId, choice);
        if (hasCycle(updated)) return false;
        choices.clear();
        choices.putAll(updated);
        pruneUnreachable();
        return true;
    }

    public boolean removeGoal(String personaId) {
        boolean removed = goals.remove(personaId);
        if (removed) pruneUnreachable();
        return removed;
    }

    /** Collapses this branch back to an ingredient to summon or mark owned. */
    public boolean clearRecipe(String personaId) {
        boolean removed = choices.remove(personaId) != null;
        if (removed) pruneUnreachable();
        return removed;
    }

    public void clear() {
        goals.clear();
        choices.clear();
    }

    public JSONObject toJson() {
        try {
            JSONObject root = new JSONObject();
            JSONArray goalRows = new JSONArray();
            for (String goal : goals) goalRows.put(goal);
            JSONObject recipeRows = new JSONObject();
            for (Choice choice : choices.values()) {
                JSONArray ingredients = new JSONArray();
                for (String id : choice.ingredientIds) ingredients.put(id);
                recipeRows.put(choice.resultId, ingredients);
            }
            return root.put("goals", goalRows).put("recipes", recipeRows);
        } catch (JSONException error) {
            throw new IllegalStateException("Cannot encode fusion basket", error);
        }
    }

    /** Validates safe ids, bounded sizes and the dependency graph, without loading the asset. */
    public static FusionBasket fromJson(JSONObject root) {
        if (root == null) return new FusionBasket();
        try {
            FusionBasket result = new FusionBasket();
            JSONArray goals = root.getJSONArray("goals");
            check(goals.length() <= MAX_PERSONAS, "Too many fusion basket goals");
            for (int i = 0; i < goals.length(); i++) {
                String id = goals.getString(i);
                checkId(id);
                check(!result.goals.contains(id), "Duplicate fusion basket goal: " + id);
                result.goals.add(id);
            }
            JSONObject recipes = root.getJSONObject("recipes");
            check(recipes.length() <= MAX_PERSONAS, "Too many fusion basket recipes");
            Iterator<String> keys = recipes.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                checkId(id);
                JSONArray ingredients = recipes.getJSONArray(id);
                check(ingredients.length() >= 2 && ingredients.length() <= 6,
                        "A fusion basket recipe needs 2 to 6 ingredients");
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < ingredients.length(); i++) {
                    String ingredient = ingredients.getString(i);
                    checkId(ingredient);
                    check(!ids.contains(ingredient), "Repeated fusion basket ingredient: " + ingredient);
                    ids.add(ingredient);
                }
                result.choices.put(id, new Choice(id, ids));
            }
            check(!hasCycle(result.choices), "Fusion basket recipes contain a cycle");
            result.pruneUnreachable();
            return result;
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid fusion basket: " + error.getMessage(), error);
        }
    }

    /** Adds catalogue reference checks to the shape validation used by backup loading. */
    public static FusionBasket fromJson(JSONObject root, Compendium compendium) {
        FusionBasket basket = fromJson(root);
        basket.checkReferences(compendium);
        return basket;
    }

    /** Rebuilds a saved recipe under whichever DLC configuration originally made it valid. */
    public Recipe selectedRecipe(String personaId, Compendium compendium) {
        Choice choice = choices.get(personaId);
        if (choice == null) return null;
        String[] packs = {Compendium.DLC_P5_FIRST, Compendium.DLC_P5_SECOND, Compendium.DLC_P4};
        for (int mask = 0; mask < 8; mask++) {
            Set<String> owned = new HashSet<>();
            for (int i = 0; i < packs.length; i++) if ((mask & (1 << i)) != 0) owned.add(packs[i]);
            Recipe recipe = resolveRecipe(choice, new FusionCalculator(compendium, owned));
            if (recipe != null) return recipe;
        }
        return null;
    }

    /** Uses the already loaded calculator and its DLC settings; suitable for UI refreshes. */
    public Recipe selectedRecipe(String personaId, FusionCalculator calculator) {
        Choice choice = choices.get(personaId);
        return choice == null ? null : resolveRecipe(choice, calculator);
    }

    /** Summarises selections without filtering a particular DLC ownership configuration. */
    public Summary summarise(Compendium compendium, Set<String> ownedPersonas) {
        return summarise(compendium, ownedPersonas, null);
    }

    /**
     * A supplied calculator validates every needed choice against the current DLC setting.
     * An invalid branch stays visible and blocked until a replacement recipe is selected.
     * Registration marks reusable summon availability; it never consumes a copy, hides a leaf,
     * or overrides an explicitly chosen recipe. This method never mutates user choices.
     */
    public Summary summarise(Compendium compendium, Set<String> ownedPersonas,
                             FusionCalculator activeCalculator) {
        checkReferences(compendium);
        SummaryBuilder builder = new SummaryBuilder(compendium, ownedPersonas, activeCalculator);
        List<Node> roots = new ArrayList<>();
        for (String goal : goals) roots.add(builder.expand(goal, 1));
        return builder.finish(roots);
    }

    private final class SummaryBuilder {
        final Compendium compendium;
        final FusionCalculator calculator;
        final Set<String> registered;
        final Map<String, Integer> shopping = new LinkedHashMap<>();
        final List<CraftStep> steps = new ArrayList<>();
        final Map<String, Boolean> choiceValidity = new LinkedHashMap<>();
        final Set<String> invalidRecipes = new LinkedHashSet<>();

        SummaryBuilder(Compendium compendium, Set<String> owned, FusionCalculator calculator) {
            this.compendium = compendium;
            this.calculator = calculator;
            registered = new HashSet<>(owned);
        }

        Node expand(String id, int quantity) {
            Persona persona = compendium.persona(id);
            int registeredQuantity = registered.contains(id) ? quantity : 0;
            if (calculator != null && !calculator.isAvailable(persona)) {
                invalidRecipes.add(id);
                return new Node(persona, quantity, registeredQuantity, 0, 0, true, Collections.emptyList());
            }
            Choice choice = choices.get(id);
            if (choice == null) {
                addCount(shopping, id, quantity);
                return new Node(persona, quantity, registeredQuantity, quantity, 0, false, Collections.emptyList());
            }
            Boolean cached = choiceValidity.get(id);
            boolean valid = cached != null ? cached : calculator == null
                    ? selectedRecipe(id, compendium) != null : resolveRecipe(choice, calculator) != null;
            choiceValidity.put(id, valid);
            if (!valid) {
                invalidRecipes.add(id);
                steps.add(step(choice, quantity, false));
                return new Node(persona, quantity, registeredQuantity, 0, 0, true, Collections.emptyList());
            }
            List<Node> children = new ArrayList<>();
            boolean childrenValid = true;
            for (String ingredient : choice.ingredientIds) {
                Node child = expand(ingredient, quantity);
                children.add(child);
                childrenValid &= !child.invalidRecipe;
            }
            steps.add(step(choice, quantity, childrenValid));
            return new Node(persona, quantity, registeredQuantity, 0, quantity, !childrenValid, children);
        }

        Summary finish(List<Node> roots) {
            List<ShoppingItem> materials = shoppingItems(shopping);
            List<ShoppingItem> registeredMaterials = new ArrayList<>();
            for (ShoppingItem item : materials) if (item.registered) registeredMaterials.add(item);
            return new Summary(roots, steps, materials, registeredMaterials, invalidRecipes);
        }

        CraftStep step(Choice choice, int quantity, boolean valid) {
            List<Persona> ingredients = new ArrayList<>();
            for (String id : choice.ingredientIds) ingredients.add(compendium.persona(id));
            return new CraftStep(compendium.persona(choice.resultId), ingredients, quantity, valid);
        }

        List<ShoppingItem> shoppingItems(Map<String, Integer> counts) {
            List<ShoppingItem> items = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : counts.entrySet())
                items.add(new ShoppingItem(compendium.persona(entry.getKey()), entry.getValue(),
                        registered.contains(entry.getKey())));
            return items;
        }
    }

    private static Recipe resolveRecipe(Choice choice, FusionCalculator calculator) {
        if (choice.ingredientIds.size() == 2) {
            Recipe recipe = calculator.fuse(choice.ingredientIds.get(0), choice.ingredientIds.get(1));
            return recipe != null && recipe.result.id.equals(choice.resultId) ? recipe : null;
        }
        for (Recipe recipe : calculator.recipesFor(choice.resultId)) {
            if (recipe.special && new HashSet<>(choice.ingredientIds).equals(ingredientIds(recipe))) return recipe;
        }
        return null;
    }

    private static Choice fromRecipe(Recipe recipe) {
        List<String> ingredients = new ArrayList<>();
        for (Persona persona : recipe.ingredients) ingredients.add(persona.id);
        return new Choice(recipe.result.id, ingredients);
    }

    private static Set<String> ingredientIds(Recipe recipe) {
        Set<String> result = new HashSet<>();
        for (Persona persona : recipe.ingredients) result.add(persona.id);
        return result;
    }

    private void checkReferences(Compendium compendium) {
        for (String id : goals) check(compendium.persona(id) != null, "Unknown fusion basket goal: " + id);
        for (Choice choice : choices.values()) {
            check(compendium.persona(choice.resultId) != null, "Unknown fusion basket result: " + choice.resultId);
            for (String id : choice.ingredientIds)
                check(compendium.persona(id) != null, "Unknown fusion basket ingredient: " + id);
        }
    }

    private Set<String> reachable() {
        Set<String> reachable = new LinkedHashSet<>();
        for (String root : goals) visitReachable(root, reachable);
        return reachable;
    }
    private void visitReachable(String id, Set<String> reached) {
        if (!reached.add(id)) return;
        Choice choice = choices.get(id);
        if (choice != null) for (String ingredient : choice.ingredientIds) visitReachable(ingredient, reached);
    }
    private void pruneUnreachable() {
        Set<String> reached = reachable();
        choices.keySet().retainAll(reached);
    }

    private static boolean hasCycle(Map<String, Choice> graph) {
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String id : graph.keySet()) if (cycleFrom(id, graph, visiting, visited)) return true;
        return false;
    }
    private static boolean cycleFrom(String id, Map<String, Choice> graph,
                                     Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return false;
        if (!visiting.add(id)) return true;
        Choice choice = graph.get(id);
        if (choice != null) for (String ingredient : choice.ingredientIds)
            if (cycleFrom(ingredient, graph, visiting, visited)) return true;
        visiting.remove(id);
        visited.add(id);
        return false;
    }
    private static void addCount(Map<String, Integer> counts, String id, int quantity) {
        Integer previous = counts.get(id);
        long total = (previous == null ? 0L : previous) + quantity;
        check(total <= Integer.MAX_VALUE, "Fusion basket quantity is too large");
        counts.put(id, (int) total);
    }
    private static void checkId(String id) {
        check(id != null && PERSONA_ID.matcher(id).matches(), "Invalid fusion basket Persona id");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
