package app.p3r.dayplanner.v3.compendium;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

import app.p3r.dayplanner.v3.compendium.Compendium.Persona;
import app.p3r.dayplanner.v3.compendium.FusionCalculator.Recipe;

/** Actual shipped data and game-rule examples, including DLC-dependent result changes. */
public class CompendiumFusionTest {
    private static Compendium catalogue;

    private static synchronized Compendium catalogue() throws IOException {
        if (catalogue == null) {
            try (InputStream stream = Files.newInputStream(assetPath())) {
                catalogue = Compendium.load(stream);
            }
        }
        return catalogue;
    }

    private static Path assetPath() {
        for (String candidate : new String[]{"src/main/assets/p3r_compendium.json",
                "app/src/main/assets/p3r_compendium.json", "android/app/src/main/assets/p3r_compendium.json"}) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) return path;
        }
        throw new IllegalStateException("Compendium asset not found");
    }

    private static Persona persona(String key) throws IOException {
        Persona result = catalogue().personaByKey(key);
        assertNotNull("Missing Persona: " + key, result);
        return result;
    }

    private static FusionCalculator calculator(String... packs) throws IOException {
        return new FusionCalculator(catalogue(), new HashSet<>(Arrays.asList(packs)));
    }

    private static Recipe fuse(FusionCalculator calculator, String first, String second) throws IOException {
        return calculator.fuse(persona(first).id, persona(second).id);
    }

    @Test public void completeReloadCatalogueHasOfficialNamesAndExactlyThreePersonaDlcPacks() throws Exception {
        Compendium compendium = catalogue();
        assertEquals(194, compendium.personas().size());
        assertEquals(22, compendium.arcana().size());
        assertEquals(3, compendium.dlcPacks().size());
        assertEquals(173, calculator().availablePersonas().size());
        assertEquals(182, calculator(Compendium.DLC_P5_FIRST).availablePersonas().size());
        assertEquals(182, calculator(Compendium.DLC_P5_SECOND).availablePersonas().size());
        assertEquals(176, calculator(Compendium.DLC_P4).availablePersonas().size());
        assertEquals(194, calculator(Compendium.DLC_P5_FIRST, Compendium.DLC_P5_SECOND,
                Compendium.DLC_P4).availablePersonas().size());
        for (Persona persona : compendium.personas()) {
            assertFalse(persona.nameZh.isEmpty());
            assertFalse(persona.nameEn.isEmpty());
            assertEquals(5, persona.stats.length);
            assertEquals(10, persona.resists.length());
            assertNotNull(compendium.arcana(persona.arcana));
        }
        assertEquals("俄耳甫斯", persona("Orpheus").nameZh);
        assertEquals("大天使", persona("Archangel").nameZh);
    }

    @Test public void dlcOwnershipChangesBasePersonaFusionResultRatherThanOnlyHidingRows() throws Exception {
        assertEquals("Narcissus", fuse(calculator(), "Orpheus", "Power").result.key);
        assertEquals("Carmen", fuse(calculator(Compendium.DLC_P5_FIRST), "Orpheus", "Power").result.key);
        assertEquals("Narcissus", fuse(calculator(Compendium.DLC_P5_SECOND), "Orpheus", "Power").result.key);
        // The second P5 pack changes another part of the same arcana's ladder.
        assertEquals("Titania", fuse(calculator(), "Apsaras", "Kohryu").result.key);
        assertEquals("Hecate", fuse(calculator(Compendium.DLC_P5_SECOND), "Apsaras", "Kohryu").result.key);
    }

    @Test public void sameArcanaRoundsDownAndNeverReturnsEitherIngredient() throws Exception {
        assertEquals("Pixie", fuse(calculator(), "Silky", "Tam Lin").result.key);
        assertEquals("Orpheus", fuse(calculator(), "Slime", "Legion").result.key);
        assertEquals("Tam Lin", fuse(calculator(), "Narcissus", "Queen Medb").result.key);
        assertEquals("Carmen", fuse(calculator(Compendium.DLC_P5_FIRST), "Narcissus", "Queen Medb").result.key);
        assertNull(fuse(calculator(), "Pixie", "Silky"));
    }

    @Test public void fixedTwoIngredientRecipesOverrideNormalArcanaRulesInEitherOrder() throws Exception {
        FusionCalculator calculator = calculator();
        Recipe shiva = fuse(calculator, "Rangda", "Barong");
        assertEquals("Shiva", shiva.result.key);
        assertTrue(shiva.special);
        assertEquals("Shiva", fuse(calculator, "Barong", "Rangda").result.key);
        Recipe messiah = fuse(calculator, "Orpheus", "Thanatos");
        assertEquals("Messiah", messiah.result.key);
        assertTrue(messiah.special);
        assertEquals(1, calculator.recipesFor(persona("Shiva").id).size());
        assertEquals(1, calculator.recipesFor(persona("Messiah").id).size());
    }

    @Test public void fixedMultiIngredientRecipesAreNotGenericTripleFusions() throws Exception {
        FusionCalculator calculator = calculator();
        assertEquals(22, catalogue().specialRecipes().size());
        Recipe telos = calculator.recipesFor(persona("Orpheus Telos").id).get(0);
        assertTrue(telos.special);
        assertEquals(6, telos.ingredients.size());
        assertEquals("Thanatos", telos.ingredients.get(0).key);
        assertTrue(calculator.recipesFor(persona("Arsene").id).isEmpty());
        Recipe arsene = calculator(Compendium.DLC_P5_FIRST).recipesFor(persona("Arsene").id).get(0);
        assertTrue(arsene.special);
        assertEquals(3, arsene.ingredients.size());
        Recipe satanael = calculator(Compendium.DLC_P5_SECOND).recipesFor(persona("Satanael").id).get(0);
        assertTrue(satanael.special);
        assertEquals(6, satanael.ingredients.size());
    }

    @Test public void storyRequirementsStayVisibleWithoutCorruptingTheFusionLevelLadder() throws Exception {
        Persona cybele = persona("Cybele");
        assertEquals("social", cybele.unlockType);
        assertFalse(cybele.unlockTextZh.isEmpty());
        assertTrue(calculator().isAvailable(cybele));
        assertFalse(calculator().recipesFor(cybele.id).isEmpty());
        assertTrue(persona("Orpheus Telos").unlockTextZh.contains("社群"));
    }

    @Test public void matchingNamesRemainDistinctForBaseAndDlcLoki() throws Exception {
        Persona base = persona("Loki"), dlc = persona("Loki A");
        assertNotEquals(base.id, dlc.id);
        assertEquals("Fool", base.arcana);
        assertEquals("Justice", dlc.arcana);
        assertFalse(base.isDlc());
        assertEquals(Compendium.DLC_P5_SECOND, dlc.dlc);
    }

    @Test public void theurgyIsSeparateFromInnateAndLevelLearnedSkills() throws Exception {
        Compendium compendium = catalogue();
        boolean innate = false, learned = false;
        for (Persona persona : compendium.personas()) {
            for (Compendium.LearnedSkill skill : persona.skills) {
                assertTrue(skill.level >= 0 && skill.level <= 99);
                assertNotNull(compendium.skill(skill.skillId));
                innate |= skill.level == 0;
                learned |= skill.level > 0;
                assertFalse(persona.theurgySkillIds.contains(skill.skillId));
            }
            for (String skillId : persona.theurgySkillIds) assertNotNull(compendium.skill(skillId));
        }
        assertTrue(innate);
        assertTrue(learned);
        assertFalse(persona("Orpheus").theurgySkillIds.isEmpty());
        assertFalse(persona("Loki").theurgySkillIds.isEmpty());
    }

    @Test public void costsAndResistanceLabelsRetainGameMeaning() throws Exception {
        Compendium compendium = catalogue();
        assertEquals("3SP", compendium.skill("skill_10").costLabel());
        boolean hpSkill = false;
        for (Compendium.Skill skill : compendium.skills()) {
            if (skill.costType.equals("HP")) {
                assertTrue(skill.costLabel().endsWith("%HP"));
                hpSkill = true;
            }
        }
        assertTrue(hpSkill);
        assertEquals("弱", compendium.resistanceLabel('w'));
        assertEquals("耐", compendium.resistanceLabel('s'));
        assertEquals("无", compendium.resistanceLabel('n'));
        assertEquals("反", compendium.resistanceLabel('r'));
        assertEquals("吸", compendium.resistanceLabel('d'));
    }

    @Test public void invalidInputsAndUnavailableDlcMaterialsCannotProduceARecipe() throws Exception {
        FusionCalculator calculator = calculator();
        assertNull(calculator.fuse("missing", persona("Pixie").id));
        assertNull(calculator.fuse(null, persona("Pixie").id));
        assertNull(fuse(calculator, "Pixie", "Pixie"));
        assertNull(fuse(calculator, "Carmen", "Power"));
        assertTrue(calculator.recipesFor("missing").isEmpty());
        assertTrue(calculator("not_a_pack").ownedDlcPacks().isEmpty());
    }

    @Test public void everyPairMatchesReverseRecipesAcrossAllEightDlcConfigurations() throws Exception {
        Set<String> specialResultIds = new HashSet<>();
        for (Compendium.SpecialRecipe recipe : catalogue().specialRecipes()) specialResultIds.add(recipe.resultId);
        String[] packIds = {Compendium.DLC_P5_FIRST, Compendium.DLC_P5_SECOND, Compendium.DLC_P4};
        for (int mask = 0; mask < 8; mask++) {
            List<String> owned = new ArrayList<>();
            for (int bit = 0; bit < packIds.length; bit++) if ((mask & (1 << bit)) != 0) owned.add(packIds[bit]);
            FusionCalculator calculator = calculator(owned.toArray(new String[0]));
            Set<String> reversePairs = new HashSet<>();
            for (Persona target : catalogue().personas()) {
                int previousMax = -1;
                for (Recipe recipe : calculator.recipesFor(target.id)) {
                    assertEquals(target.id, recipe.result.id);
                    assertTrue(calculator.isAvailable(recipe.result));
                    assertTrue(recipe.maxIngredientLevel >= previousMax);
                    previousMax = recipe.maxIngredientLevel;
                    for (Persona ingredient : recipe.ingredients) assertTrue(calculator.isAvailable(ingredient));
                    assertEquals(specialResultIds.contains(target.id), recipe.special);
                    if (recipe.ingredients.size() == 2) {
                        String key = recipeKey(recipe);
                        assertTrue("Duplicate recipe " + key, reversePairs.add(key));
                    }
                }
            }
            List<Persona> available = calculator.availablePersonas();
            int forwardPairs = 0;
            for (int i = 0; i < available.size(); i++) for (int j = i + 1; j < available.size(); j++) {
                Persona first = available.get(i), second = available.get(j);
                Recipe recipe = calculator.fuse(first.id, second.id);
                Recipe reversed = calculator.fuse(second.id, first.id);
                if (recipe == null) {
                    assertNull(reversed);
                    continue;
                }
                forwardPairs++;
                assertEquals(recipe.result.id, reversed.result.id);
                assertTrue(reversePairs.contains(recipeKey(recipe)));
                assertEquals(specialResultIds.contains(recipe.result.id), recipe.special);
                if (!recipe.special) {
                    assertNotEquals(first.id, recipe.result.id);
                    assertNotEquals(second.id, recipe.result.id);
                }
            }
            assertEquals(forwardPairs, reversePairs.size());
        }
    }

    @Test public void sourceAttributionsAreBundledForOfflineViewing() throws Exception {
        boolean database = false, fusionTool = false;
        for (Compendium.Source source : catalogue().sources()) {
            database |= source.url.equals("https://github.com/aqiu384/megaten-database");
            fusionTool |= source.url.equals("https://github.com/aqiu384/megaten-fusion-tool");
            assertFalse(source.commit.isEmpty());
        }
        assertTrue(database);
        assertTrue(fusionTool);
    }

    @Test public void towerAcquisitionContainsRealShuffleFloorsWithoutBossOrDlcLocations() throws Exception {
        int withOrigins = 0;
        for (Persona persona : catalogue().personas()) {
            if (!persona.origins.isEmpty()) withOrigins++;
            if (persona.isDlc()) assertTrue(persona.origins.isEmpty());
            int previous = 0;
            for (Compendium.Origin origin : persona.origins) {
                assertEquals("shuffle", origin.kind);
                assertFalse(origin.blockId.isEmpty());
                assertFalse(origin.blockNameZh.isEmpty());
                assertTrue(origin.floorFrom > previous);
                assertTrue(origin.floorFrom <= origin.floorTo && origin.floorTo <= 264);
                previous = origin.floorTo;
            }
        }
        assertEquals(111, withOrigins);
        assertEquals(Set.of(2, 3, 4, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 18, 19, 20, 21),
                acquisitionFloors(persona("Pixie")));
        assertEquals(Set.of(23, 24, 25, 26, 27, 29), acquisitionFloors(persona("Jack Frost")));
        assertTrue(acquisitionFloors(persona("Loki")).containsAll(Set.of(227, 257, 260, 261)));
        assertFalse(acquisitionFloors(persona("Loki")).contains(256));
        assertTrue(persona("Orpheus").origins.isEmpty());
        assertTrue(persona("Surt").origins.isEmpty());
    }

    @Test public void malformedAcquisitionFloorsAndDlcTowerLocationsAreRejected() throws Exception {
        JSONObject root = new JSONObject(new String(Files.readAllBytes(assetPath()), StandardCharsets.UTF_8));
        JSONObject location = new JSONObject().put("kind", "shuffle").put("blockId", "thebel")
                .put("blockNameZh", "世俗之庭 台伯勒").put("floorFrom", 10).put("floorTo", 2);
        root.getJSONArray("personas").getJSONObject(0).put("origins", new org.json.JSONArray().put(location));
        assertBad(root, "Invalid Persona acquisition floors");
        location.put("floorTo", 12);
        root.getJSONArray("personas").getJSONObject(0).put("dlc", Compendium.DLC_P5_FIRST);
        assertBad(root, "DLC Persona cannot occur");
    }

    private static Set<Integer> acquisitionFloors(Persona persona) {
        Set<Integer> floors = new HashSet<>();
        for (Compendium.Origin origin : persona.origins) {
            for (int floor = origin.floorFrom; floor <= origin.floorTo; floor++) floors.add(floor);
        }
        return floors;
    }

    @Test public void brokenSkillReferencesAndResistanceVectorsAreRejectedAtLoadTime() throws Exception {
        JSONObject root = new JSONObject(new String(Files.readAllBytes(assetPath()), StandardCharsets.UTF_8));
        root.getJSONArray("personas").getJSONObject(0).getJSONArray("skills")
                .getJSONObject(0).put("id", "missing_skill");
        assertBad(root, "Unknown learned skill");
        root = new JSONObject(new String(Files.readAllBytes(assetPath()), StandardCharsets.UTF_8));
        root.getJSONArray("personas").getJSONObject(0).put("resists", "w");
        assertBad(root, "wrong length");
    }

    private static void assertBad(JSONObject root, String expected) throws Exception {
        try {
            Compendium.parse(root.toString());
            fail("Broken catalogue accepted");
        } catch (IOException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }

    private static String recipeKey(Recipe recipe) {
        List<String> ids = new ArrayList<>();
        for (Persona ingredient : recipe.ingredients) ids.add(ingredient.id);
        Collections.sort(ids);
        return recipe.result.id + ":" + String.join(",", ids);
    }
}
