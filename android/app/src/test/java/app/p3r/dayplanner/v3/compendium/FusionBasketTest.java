package app.p3r.dayplanner.v3.compendium;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

import app.p3r.dayplanner.v3.compendium.Compendium.Persona;
import app.p3r.dayplanner.v3.compendium.FusionCalculator.Recipe;

/** Chained crafting uses actual Reload recipes and reusable registered-compendium status. */
public class FusionBasketTest {
    private static Compendium data;

    private static synchronized Compendium compendium() throws Exception {
        if (data == null) {
            for (String candidate : new String[]{"src/main/assets/p3r_compendium.json",
                    "app/src/main/assets/p3r_compendium.json", "android/app/src/main/assets/p3r_compendium.json"}) {
                Path path = Path.of(candidate);
                if (Files.isRegularFile(path)) {
                    try (InputStream stream = Files.newInputStream(path)) { data = Compendium.load(stream); }
                    break;
                }
            }
        }
        assertNotNull(data);
        return data;
    }

    private static Persona persona(String key) throws Exception { return compendium().personaByKey(key); }
    private static String id(String key) throws Exception { return persona(key).id; }
    private static FusionCalculator calculator() throws Exception {
        return new FusionCalculator(compendium(), Collections.emptySet());
    }
    private static Recipe fusion(String first, String second) throws Exception {
        Recipe recipe = calculator().fuse(id(first), id(second));
        assertNotNull(recipe);
        return recipe;
    }
    private static FusionBasket chain() throws Exception {
        FusionBasket basket = new FusionBasket();
        assertTrue(basket.addGoal(fusion("Orpheus", "Power"))); // Narcissus = Orpheus + Power
        assertTrue(basket.chooseRecipe(fusion("Naga", "Fortuna"))); // Power = Naga + Fortuna
        return basket;
    }
    private static FusionBasket.Summary summary(FusionBasket basket, String... owned) throws Exception {
        Set<String> ownedIds = new HashSet<>();
        for (String name : owned) ownedIds.add(id(name));
        return basket.summarise(compendium(), ownedIds, calculator());
    }
    private static Map<String, Integer> counts(List<FusionBasket.ShoppingItem> items) {
        Map<String, Integer> counts = new HashMap<>();
        for (FusionBasket.ShoppingItem item : items) counts.put(item.persona.key, item.quantity);
        return counts;
    }

    @Test public void expandingMissingPowerKeepsOneGoalAndProducesLeavesAndCraftOrder() throws Exception {
        FusionBasket basket = chain();
        assertEquals(Collections.singletonList(id("Narcissus")), basket.goals());
        FusionBasket.Summary summary = summary(basket);
        assertTrue(summary.valid);
        assertEquals(Map.of("Orpheus", 1, "Naga", 1, "Fortuna", 1), counts(summary.shopping));
        assertEquals(2, summary.steps.size());
        assertEquals("Power", summary.steps.get(0).result.key);
        assertEquals("Narcissus", summary.steps.get(1).result.key);
        assertTrue(summary.steps.get(0).valid);
        assertEquals("Power", summary.roots.get(0).children.get(1).persona.key);
        assertEquals(2, summary.roots.get(0).children.get(1).children.size());
    }

    @Test public void registeringOrpheusAndFortunaKeepsAllSummonLeavesButOnlyNagaUnregistered() throws Exception {
        FusionBasket basket = chain();
        Set<String> owned = new HashSet<>();
        owned.add(id("Orpheus"));
        owned.add(id("Fortuna"));
        FusionBasket.Summary summary = basket.summarise(compendium(), owned, calculator());
        assertEquals(Map.of("Orpheus", 1, "Naga", 1, "Fortuna", 1), counts(summary.shopping));
        assertEquals(Map.of("Naga", 1), counts(summary.unregistered));
        assertFalse(summary.allRegistered);
        assertEquals(Map.of("Orpheus", 1, "Fortuna", 1), counts(summary.ownedUsed));
        assertEquals(2, summary.steps.size());
        assertEquals(Set.of(id("Orpheus"), id("Fortuna")), owned);
        assertEquals(counts(summary.shopping), counts(basket.summarise(compendium(), owned, calculator()).shopping));
    }

    @Test public void registeredIntermediateDoesNotOverrideItsExplicitlySelectedRecipe() throws Exception {
        FusionBasket basket = chain();
        FusionBasket.Summary summary = summary(basket, "Power");
        assertEquals(Map.of("Orpheus", 1, "Naga", 1, "Fortuna", 1), counts(summary.shopping));
        assertTrue(summary.ownedUsed.isEmpty());
        assertEquals(2, summary.steps.size());
        assertEquals("Power", summary.steps.get(0).result.key);
        FusionBasket.Node power = summary.roots.get(0).children.get(1);
        assertEquals(1, power.ownedQuantity);
        assertEquals(1, power.craftQuantity);
        assertEquals(2, power.children.size());
        assertTrue(basket.clearRecipe(id("Power")));
        summary = summary(basket, "Power");
        assertEquals(Map.of("Orpheus", 1, "Power", 1), counts(summary.shopping));
        assertEquals(Map.of("Power", 1), counts(summary.ownedUsed));
        assertEquals(1, summary.steps.size());
    }

    @Test public void registrationCoversEveryRepeatedLeafWhileSummonQuantitiesAndConsumerOrderRemainAccurate() throws Exception {
        FusionBasket basket = chain();
        assertTrue(basket.addGoal(fusion("Naga", "Fortuna"))); // Also want a surviving copy of Power.
        FusionBasket.Summary summary = summary(basket, "Fortuna");
        assertEquals(Map.of("Orpheus", 1, "Naga", 2, "Fortuna", 2), counts(summary.shopping));
        assertEquals(Map.of("Fortuna", 2), counts(summary.ownedUsed));
        assertEquals(Map.of("Orpheus", 1, "Naga", 2), counts(summary.unregistered));
        assertEquals(3, summary.steps.size());
        assertEquals("Power", summary.steps.get(0).result.key);
        assertEquals(1, summary.steps.get(0).quantity);
        assertEquals("Narcissus", summary.steps.get(1).result.key);
        assertEquals("Power", summary.steps.get(2).result.key);
    }

    @Test public void registeringBothIntermediateAndFinalGoalDoesNotSuppressEitherChosenCraft() throws Exception {
        FusionBasket basket = chain();
        assertTrue(basket.addGoal(fusion("Naga", "Fortuna")));
        FusionBasket.Summary summary = summary(basket, "Power");
        assertTrue(summary.ownedUsed.isEmpty());
        assertEquals(Map.of("Orpheus", 1, "Naga", 2, "Fortuna", 2), counts(summary.shopping));
        int powerCrafts = 0;
        for (FusionBasket.CraftStep step : summary.steps) if (step.result.key.equals("Power")) powerCrafts += step.quantity;
        assertEquals(2, powerCrafts);
    }

    @Test public void repeatedAddDoesNotDuplicateGoalAndCollapsingBranchRestoresDirectSummon() throws Exception {
        FusionBasket basket = chain();
        assertTrue(basket.addGoal(fusion("Orpheus", "Power")));
        assertEquals(1, basket.goals().size());
        assertTrue(basket.clearRecipe(id("Power")));
        assertFalse(basket.clearRecipe(id("Power")));
        assertNull(basket.chosenRecipe(id("Power")));
        assertEquals(Map.of("Orpheus", 1, "Power", 1), counts(summary(basket).shopping));
        assertEquals(1, summary(basket).steps.size());
        assertTrue(basket.containsGoal(id("Narcissus")));
    }

    @Test public void actualValidRecipesThatWouldCreateDependencyCycleAreRejectedAtomically() throws Exception {
        FusionBasket basket = chain();
        String before = basket.toJson().toString();
        Recipe cycle = fusion("Narcissus", "Slime"); // Power = Narcissus + Slime creates Narcissus → Power → Narcissus.
        assertEquals("Power", cycle.result.key);
        assertFalse(basket.chooseRecipe(cycle));
        assertEquals(before, basket.toJson().toString());
        assertTrue(summary(basket).valid);
    }

    @Test public void unrelatedBranchChoiceDoesNotCreateAnotherGoalOrHiddenShopping() throws Exception {
        FusionBasket basket = chain();
        assertFalse(basket.chooseRecipe(fusion("Rangda", "Barong")));
        assertEquals(1, basket.goals().size());
        assertNull(basket.chosenRecipe(id("Shiva")));
        assertEquals(3, summary(basket).shopping.size());
    }

    @Test public void jsonRoundTripPreservesSelectedBranchesAndOwnershipCalculation() throws Exception {
        FusionBasket basket = chain();
        FusionBasket restored = FusionBasket.fromJson(new JSONObject(basket.toJson().toString()), compendium());
        assertEquals(basket.goals(), restored.goals());
        assertEquals(basket.chosenRecipe(id("Power")).ingredientIds, restored.chosenRecipe(id("Power")).ingredientIds);
        assertEquals(Map.of("Naga", 1), counts(summary(restored, "Orpheus", "Fortuna").unregistered));
        assertEquals(3, summary(restored, "Orpheus", "Fortuna").shopping.size());
        assertEquals("Power", summary(restored).steps.get(0).result.key);
        assertNotNull(restored.selectedRecipe(id("Power"), compendium()));
        assertEquals(3, basket.summarise(compendium(), Collections.emptySet()).shopping.size());
    }

    @Test public void changingDlcBlocksTheSavedRecipeInsteadOfSilentlyChangingItsResult() throws Exception {
        FusionBasket basket = chain();
        FusionCalculator dlc = new FusionCalculator(compendium(), Set.of(Compendium.DLC_P5_FIRST));
        // Orpheus + Power now creates Carmen, not the requested Narcissus.
        FusionBasket.Summary summary = basket.summarise(compendium(), Collections.emptySet(), dlc);
        assertFalse(summary.valid);
        assertEquals(Collections.singletonList(id("Narcissus")), summary.invalidRecipes);
        assertTrue(summary.roots.get(0).invalidRecipe);
        assertEquals(1, summary.steps.size());
        assertFalse(summary.steps.get(0).valid);
        assertTrue(summary.shopping.isEmpty());
        assertEquals(id("Narcissus"), basket.goals().get(0));
        // Registration never silently substitutes for an explicitly selected recipe.
        assertFalse(basket.summarise(compendium(), Set.of(id("Narcissus")), dlc).valid);
    }

    @Test public void registeredSpecialTargetStillFollowsItsChosenSixIngredientRecipe() throws Exception {
        Recipe telos = calculator().recipesFor(id("Orpheus Telos")).get(0);
        FusionBasket basket = new FusionBasket();
        assertTrue(basket.addGoal(telos));
        FusionBasket.Summary summary = summary(basket);
        assertEquals(6, summary.shopping.size());
        assertEquals(1, summary.steps.size());
        assertEquals(6, summary.steps.get(0).ingredients.size());
        assertTrue(summary.steps.get(0).valid);
        summary = summary(basket, "Orpheus Telos");
        assertEquals(6, summary.shopping.size());
        assertEquals(1, summary.steps.size());
        assertTrue(summary.ownedUsed.isEmpty());
        assertEquals(1, summary.roots.get(0).ownedQuantity);
        assertEquals(1, summary.roots.get(0).craftQuantity);
    }

    @Test public void allRegisteredMeansEveryLeafCanBeSummonedAndDoesNotMeanAlreadyCarried() throws Exception {
        FusionBasket.Summary summary = summary(chain(), "Orpheus", "Naga", "Fortuna");
        assertTrue(summary.valid);
        assertTrue(summary.allRegistered);
        assertTrue(summary.unregistered.isEmpty());
        assertEquals(3, summary.shopping.size());
        assertEquals(2, summary.steps.size());
        for (FusionBasket.ShoppingItem item : summary.shopping) assertTrue(item.registered);
    }

    @Test public void removalPrunesUnusedBranchesAndClearEmptiesEverything() throws Exception {
        FusionBasket basket = chain();
        assertTrue(basket.removeGoal(id("Narcissus")));
        assertTrue(basket.goals().isEmpty());
        assertNull(basket.chosenRecipe(id("Power")));
        assertTrue(summary(basket).shopping.isEmpty());
        assertFalse(basket.removeGoal(id("Narcissus")));
        assertTrue(basket.addGoal(fusion("Orpheus", "Power")));
        basket.clear();
        assertTrue(basket.goals().isEmpty());
        assertEquals(0, basket.toJson().getJSONObject("recipes").length());
    }

    @Test public void invalidCyclesUnsafeIdsAndUnknownCatalogueReferencesAreRejected() throws Exception {
        JSONObject json = new JSONObject("{goals:['persona_140'],recipes:{persona_140:['persona_64','persona_155'],"
                + "persona_155:['persona_140','persona_110']}}");
        assertBad(json, "cycle");
        assertBad(new JSONObject("{goals:['../../bad'],recipes:{}}"), "Persona id");
        assertBad(new JSONObject("{goals:['persona_140','persona_140'],recipes:{}}"), "Duplicate");
        FusionBasket unknown = FusionBasket.fromJson(new JSONObject("{goals:['persona_99999'],recipes:{}}"));
        try {
            unknown.summarise(compendium(), Collections.emptySet());
            fail("Unknown references accepted");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("Unknown"));
        }
    }

    private static void assertBad(JSONObject json, String expected) {
        try {
            FusionBasket.fromJson(json);
            fail("Malformed basket accepted");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }
}
