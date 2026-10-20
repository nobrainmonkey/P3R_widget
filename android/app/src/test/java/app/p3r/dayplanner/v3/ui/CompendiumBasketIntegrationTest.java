package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.p3r.dayplanner.v3.compendium.Compendium;
import app.p3r.dayplanner.v3.compendium.CompendiumPrefs;
import app.p3r.dayplanner.v3.compendium.FusionBasket;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;

/** A real two-stage recipe stays usable while the player sorts, marks registrations and leaves. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CompendiumBasketIntegrationTest extends UiTestBase {
    private Compendium data;
    private CompendiumPrefs prefs;
    private FusionCalculator calculator;

    @Before public void catalogue() throws Exception {
        try (InputStream input = RuntimeEnvironment.getApplication().getAssets().open(Compendium.ASSET_NAME)) {
            data = Compendium.load(input);
        }
        prefs = new CompendiumPrefs(RuntimeEnvironment.getApplication());
        prefs.setOwnedDlc(Collections.emptySet());
        prefs.setBasket(new FusionBasket());
        for (String id : prefs.ownedPersonas()) prefs.setOwned(id, false);
        calculator = new FusionCalculator(data, Collections.emptySet());
    }

    @Test public void expandRecipeSortAndMarkRegisteredThenReopen() throws Exception {
        // Narcissus = Orpheus + Power; Power = Naga + Fortuna. Both are real base-game recipes.
        String narcissus = id("Narcissus"), orpheus = id("Orpheus"), power = id("Power");
        String naga = id("Naga"), fortuna = id("Fortuna");
        assertEquals(narcissus, calculator.fuse(orpheus, power).result.id);
        assertEquals(power, calculator.fuse(naga, fortuna).result.id);

        HomeActivity activity = open();
        tag(activity, "fusion_tab").performClick();
        tag(activity, "fusion_material_1").performClick();
        chooseInPicker(activity, "Orpheus");
        if (!activity.sheets().isShowing()) tag(activity, "fusion_material_2").performClick();
        chooseInPicker(activity, "Power");
        tag(activity, "fusion_add_basket").performClick();
        Shots.idle();
        assertTrue("adding keeps the player on the fusion pane", tag(activity, "fusion_tab").isSelected());
        assertEquals("已加入合体篮", activity.undoBar().messageText().toString());
        tag(activity, "basket_tab").performClick();
        Shots.idle();
        assertEquals(Collections.singletonList(narcissus), prefs.basket().goals());
        assertEquals(set(orpheus, power), summonMaterials());

        tag(activity, "basket_expand_" + power).performClick();
        Shots.idle();
        FusionCalculator.Recipe cheapestPower = calculator.recipesFor(power).get(0);
        assertEquals(set(naga, fortuna), recipeIngredients(cheapestPower));
        tag(activity, "recipe_add_" + power + "_0").performClick();
        Shots.idle();
        assertNotNull(tag(activity, "basket_page"));
        assertEquals("expanding a material must not create a second target",
            Collections.singletonList(narcissus), prefs.basket().goals());
        assertEquals(set(orpheus, naga, fortuna), summonMaterials());
        assertNotNull("the first recipe is shown beside the material list", tag(activity, "basket_step_" + power));
        assertNotNull("the final recipe is on the same page", tag(activity, "basket_step_" + narcissus));

        assertTrue("materials start by level, low to high", tag(activity, "basket_sort_level").isSelected());
        assertEquals(Arrays.asList(orpheus, fortuna, naga), materialRows(activity));
        tag(activity, "basket_sort_level").performClick();
        Shots.idle();
        assertEquals("tapping the selected order flips it", Arrays.asList(naga, fortuna, orpheus), materialRows(activity));
        tag(activity, "basket_sort_arcana").performClick();
        Shots.idle();
        assertTrue(tag(activity, "basket_sort_arcana").isSelected());
        assertEquals("switching modes retains the chosen direction",
            Arrays.asList(fortuna, naga, orpheus), materialRows(activity));
        tag(activity, "basket_sort_arcana").performClick();
        Shots.idle();
        assertEquals(Arrays.asList(orpheus, naga, fortuna), materialRows(activity));
        assertEquals("还差 2 步", Shots.allText(tag(activity, "basket_goal_status_" + narcissus)).trim());
        capture(activity, "fusion_basket_expanded");
        captureSteps(activity, power);

        tag(activity, "basket_owned_" + orpheus).performClick();
        Shots.idle();
        tag(activity, "basket_owned_" + fortuna).performClick();
        Shots.idle();
        assertEquals(set(orpheus, fortuna), prefs.ownedPersonas());
        assertEquals("registered materials still need to be summoned", set(orpheus, naga, fortuna), summonMaterials());
        assertEquals(Arrays.asList(orpheus, naga, fortuna), materialRows(activity));
        assertEquals(Collections.singleton(naga), unregisteredMaterials());
        assertTrue(tag(activity, "basket_owned_" + orpheus).isSelected());
        assertTrue(tag(activity, "basket_owned_" + fortuna).isSelected());
        assertFalse(tag(activity, "basket_owned_" + naga).isSelected());
        assertTrue(tag(activity, "basket_owned_" + naga).getContentDescription().toString().startsWith("登录到全书"));
        String materials = Shots.allText(tag(activity, "basket_page"));
        assertTrue(materials, materials.contains("已登录 2/3"));
        assertFalse("one word for registration", materials.contains("可召唤"));
        assertTrue(api.calls.isEmpty());
        capture(activity, "fusion_basket_registered_materials");

        tag(activity, "basket_complete_" + power).performClick();
        Shots.idle();
        assertEquals("registering a result preserves earlier registrations",
            set(orpheus, fortuna, power), prefs.ownedPersonas());
        assertNotNull("an explicitly chosen recipe remains selected after registration",
            prefs.basket().chosenRecipe(power));
        assertNotNull(tag(activity, "basket_step_" + power));
        assertNotNull(tag(activity, "basket_step_" + narcissus));
        assertEquals(set(orpheus, naga, fortuna), summonMaterials());

        String savedBasket = prefs.basket().toJson().toString();
        activity = open();
        tag(activity, "basket_tab").performClick();
        Shots.idle();
        CompendiumPrefs reopened = new CompendiumPrefs(RuntimeEnvironment.getApplication());
        assertEquals(savedBasket, reopened.basket().toJson().toString());
        assertEquals(set(orpheus, fortuna, power), reopened.ownedPersonas());
        assertEquals(set(orpheus, naga, fortuna), new HashSet<>(materialRows(activity)));
        assertEquals(Collections.singleton(naga), unregisteredMaterials());
        assertTrue("the search and basket are game tools, not calendar edits", api.calls.isEmpty());

        assertEquals("还差 1 步", Shots.allText(tag(activity, "basket_goal_status_" + narcissus)).trim());
        tag(activity, "basket_complete_" + narcissus).performClick();
        Shots.idle();
        assertTrue(prefs.ownedPersonas().contains(narcissus));
        assertEquals(data.persona(narcissus).nameZh + " 合出来了！", activity.undoBar().messageText().toString());
        assertEquals("已登录", Shots.allText(tag(activity, "basket_goal_status_" + narcissus)).trim());
        assertNotNull("a finished goal offers to leave the basket", tag(activity, "basket_done_remove_" + narcissus));
        capture(activity, "fusion_basket_goal_done");
    }

    @Test public void stepMenuClearingAndUndoKeepTheBasketLogic() {
        String narcissus = id("Narcissus"), orpheus = id("Orpheus"), power = id("Power");
        FusionBasket basket = new FusionBasket();
        assertTrue(basket.addGoal(calculator.fuse(orpheus, power)));
        assertTrue(basket.chooseRecipe(calculator.recipesFor(power).get(0)));
        prefs.setBasket(basket);
        HomeActivity activity = open();
        tag(activity, "basket_tab").performClick();
        Shots.idle();
        assertNotNull(tag(activity, "basket_progress"));

        tag(activity, "basket_step_" + power).performClick();
        assertTrue(activity.sheets().isShowing());
        assertTrue(Shots.has(activity, "换个配方"));
        capture(activity, "fusion_basket_step_menu");
        tag(activity, "basket_summon_" + power).performClick();
        Shots.idle();
        assertEquals("「不合了，直接召唤」 collapses the branch", null, prefs.basket().chosenRecipe(power));
        assertEquals(set(orpheus, power), summonMaterials());

        String saved = prefs.basket().toJson().toString();
        tag(activity, "basket_clear").performClick();
        Shots.idle();
        assertTrue(prefs.basket().goals().isEmpty());
        assertNotNull(tag(activity, "basket_empty"));
        assertTrue(activity.undoBar().hasAction());
        capture(activity, "fusion_basket_empty");
        activity.undoBar().performAction();
        Shots.idle();
        assertEquals("撤销 restores the basket as it was", saved, prefs.basket().toJson().toString());
        assertNotNull(tag(activity, "basket_step_" + narcissus));

        View menu = tag(activity, "basket_goal_menu_" + narcissus);
        assertTrue(menu.getWidth() >= Ui.dp(activity, Ui.TOUCH) && menu.getHeight() >= Ui.dp(activity, Ui.TOUCH));
        menu.performClick();
        tag(activity, "basket_remove_" + narcissus).performClick();
        Shots.idle();
        assertTrue(prefs.basket().goals().isEmpty());
        activity.undoBar().performAction();
        Shots.idle();
        assertEquals(Collections.singletonList(narcissus), prefs.basket().goals());

        tag(activity, "basket_step_" + narcissus).performClick();
        tag(activity, "basket_summon_" + narcissus).performClick();
        Shots.idle();
        assertEquals(Collections.singletonList(narcissus), prefs.basket().goals());
        assertEquals("a goal with no fusion left is not 「还差 0 步」", "未登录",
            Shots.allText(tag(activity, "basket_goal_status_" + narcissus)).trim());
        assertEquals(Collections.singleton(narcissus), summonMaterials());
        tag(activity, "basket_owned_" + narcissus).performClick();
        Shots.idle();
        assertEquals("已登录",Shots.allText(tag(activity, "basket_goal_status_" + narcissus)).trim());
    }

    @Test public void aRecipeThatNeedsAnUnownedPackIsFixedFromItsStep() {
        // Carmen is in P5R 人格面具组合 1; without the pack her recipe step needs a new choice.
        FusionCalculator withPack = new FusionCalculator(data, Collections.singleton("p5r_1"));
        FusionCalculator.Recipe recipe = withPack.fuse(id("Carmen"), id("Orpheus"));
        assertNotNull(recipe);
        FusionBasket basket = new FusionBasket();
        assertTrue(basket.addGoal(recipe));
        prefs.setBasket(basket);
        HomeActivity activity = open();
        tag(activity, "basket_tab").performClick();
        Shots.idle();
        assertNotNull(tag(activity, "basket_invalid"));
        assertEquals("配方要重选", Shots.allText(tag(activity, "basket_goal_status_" + recipe.result.id)).trim());
        View fix = tag(activity, "basket_complete_" + recipe.result.id);
        assertTrue("the 「!」 has the tick's 48 dp target",
            fix.getWidth() >= Ui.dp(activity, Ui.TOUCH) && fix.getHeight() >= Ui.dp(activity, Ui.TOUCH));
        capture(activity, "fusion_basket_invalid");
        fix.performClick();
        Shots.idle();
        assertEquals("the recipe choice opens", 1, activity.pageDepth());
    }

    @Test public void anEmptyBasketGoesStraightToTheTargetPicker() {
        HomeActivity activity = open();
        tag(activity, "basket_tab").performClick();
        Shots.idle();
        assertTrue(Shots.has(activity, "合体篮是空的"));
        tag(activity, "basket_browse").performClick();
        assertTrue(activity.sheets().isShowing());
        assertTrue(Shots.has(activity, "想合出谁？"));
        chooseInPicker(activity, "Narcissus");
        Shots.idle();
        assertEquals("the recipes page opens", 1, activity.pageDepth());
        tag(activity, "recipe_add_" + id("Narcissus") + "_0").performClick();
        Shots.idle();
        assertEquals(Collections.singletonList(id("Narcissus")), prefs.basket().goals());
        assertEquals(1, activity.pageDepth());
    }

    private Set<String> summonMaterials() {
        Set<String> ids = new HashSet<>();
        FusionBasket.Summary summary = prefs.basket().summarise(data, prefs.ownedPersonas(), calculator);
        for (FusionBasket.ShoppingItem item : summary.shopping) ids.add(item.persona.id);
        return ids;
    }

    private Set<String> unregisteredMaterials() {
        Set<String> ids = new HashSet<>();
        FusionBasket.Summary summary = prefs.basket().summarise(data, prefs.ownedPersonas(), calculator);
        for (FusionBasket.ShoppingItem item : summary.unregistered) ids.add(item.persona.id);
        return ids;
    }

    private static Set<String> recipeIngredients(FusionCalculator.Recipe recipe) {
        Set<String> ids = new HashSet<>();
        for (Compendium.Persona persona : recipe.ingredients) ids.add(persona.id);
        return ids;
    }

    private static Set<String> set(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    private String id(String key) {
        Compendium.Persona persona = data.personaByKey(key);
        assertNotNull(key, persona);
        return persona.id;
    }

    private HomeActivity open() {
        HomeActivity activity = home();
        activity.selectTab(HomeActivity.TAB_COMPENDIUM);
        Shots.idle();
        return activity;
    }

    private void chooseInPicker(HomeActivity activity, String key) {
        ((EditText) tag(activity, "fusion_picker_search")).setText(key);
        ListView list = tag(activity, "fusion_picker_list");
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (((Compendium.Persona) list.getAdapter().getItem(i)).id.equals(id(key))) {
                list.getAdapter().getView(i, null, list).performClick();
                Shots.idle();
                return;
            }
        }
        throw new AssertionError("Missing Persona: " + key);
    }

    private static List<String> materialRows(HomeActivity activity) {
        List<String> ids = new ArrayList<>();
        collectMaterialRows(tag(activity, "basket_page"), ids);
        return ids;
    }

    private static void collectMaterialRows(View view, List<String> ids) {
        if (!view.isShown()) return;
        Object value = view.getTag();
        if (value instanceof String && ((String) value).startsWith("basket_missing_persona_")) {
            ids.add(((String) value).substring("basket_missing_".length()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectMaterialRows(group.getChildAt(i), ids);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T tag(HomeActivity activity, String tag) {
        View view = visibleTag(activity.getWindow().getDecorView(), tag);
        assertNotNull(tag, view);
        return (T) view;
    }

    private static View visibleTag(View view, String tag) {
        if (!view.isShown()) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = visibleTag(group.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return tag.equals(view.getTag()) ? view : null;
    }

    private static void capture(HomeActivity activity, String name) {
        // Official bilingual names are intentional; every other typography check applies.
        Shots.checkTypography(activity.getWindow().getDecorView(), name, true);
        Shots.save(Shots.capture(activity, false), name);
    }

    private static void captureSteps(HomeActivity activity, String firstStep) {
        View card = tag(activity, "basket_step_" + firstStep);
        View ancestor = card;
        while (ancestor.getParent() instanceof View && !(ancestor.getParent() instanceof ScrollView)) {
            ancestor = (View) ancestor.getParent();
        }
        assertTrue(ancestor.getParent() instanceof ScrollView);
        ScrollView scroll = (ScrollView) ancestor.getParent();
        int before = scroll.getScrollY();
        scroll.scrollTo(0, Math.max(0, card.getTop() - Ui.dp(activity, 8)));
        capture(activity, "fusion_basket_steps");
        scroll.scrollTo(0, before);
    }
}
