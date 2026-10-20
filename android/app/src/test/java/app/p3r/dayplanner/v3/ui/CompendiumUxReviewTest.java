package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.InputStream;
import java.util.Collections;

import app.p3r.dayplanner.v3.compendium.Compendium;
import app.p3r.dayplanner.v3.compendium.CompendiumPrefs;
import app.p3r.dayplanner.v3.compendium.FusionBasket;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;

/** Consecutive lookups and fusions should preserve the player's place and material choices. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CompendiumUxReviewTest extends UiTestBase {
    private Compendium data;
    private CompendiumPrefs prefs;

    @Before public void catalogue() throws Exception {
        try (InputStream input = RuntimeEnvironment.getApplication().getAssets().open(Compendium.ASSET_NAME)) {
            data = Compendium.load(input);
        }
        prefs = new CompendiumPrefs(RuntimeEnvironment.getApplication());
        prefs.setOwnedDlc(Collections.emptySet());
        prefs.setBasket(new FusionBasket());
        for (String id : prefs.ownedPersonas()) prefs.setOwned(id, false);
        for (String id : prefs.favorites()) prefs.setFavorite(id, false);
    }

    @Test public void aResultCanImmediatelyBecomeTheNextFusionsMaterial() {
        HomeActivity activity = open();
        tag(activity, "fusion_tab").performClick();
        pickPair(activity, "Orpheus", "Power");
        FusionCalculator calculator = new FusionCalculator(data, Collections.emptySet());
        Compendium.Persona firstResult = calculator.fuse(persona("Orpheus").id, persona("Power").id).result;
        assertNotNull(tag(activity, "persona_" + firstResult.id));

        tag(activity, "fusion_continue").performClick();
        Shots.idle();
        assertTrue("the remaining material picker opens directly", activity.sheets().isShowing());
        assertFalse("the current material is not offered as its own partner",
            pickerContains(activity, firstResult.id));
        chooseInPicker(activity, "Angel");
        Compendium.Persona secondResult = calculator.fuse(firstResult.id, persona("Angel").id).result;
        assertNotNull(tag(activity, "persona_" + secondResult.id));
        assertEquals("continuing fusion returns to the tool tab", 0, activity.pageDepth());
        assertTrue(api.calls.isEmpty());
        capture(activity, "fusion_continue_result");
    }

    @Test public void ingredientRecipesAreOneTapAwayAndBackReturnsToTheOriginalSearch() {
        HomeActivity activity = open();
        ((EditText) tag(activity, "compendium_search")).setText("Alice");
        chooseFromList(activity, "compendium_list", persona("Alice").id);
        tag(activity, "persona_recipes_" + persona("Alice").id).performClick();
        Shots.idle();
        tag(activity, "material_recipe_" + persona("Pixie").id).performClick();
        Shots.idle();
        assertNotNull(tag(activity, "persona_" + persona("Pixie").id));
        assertTrue("following recipe ingredients keeps one browse page", activity.pageDepth() <= 1);
        capture(activity, "fusion_ingredient_recipe");

        activity.onBackPressed();
        assertEquals(0, activity.pageDepth());
        assertEquals("Alice", ((EditText) tag(activity, "compendium_search")).getText().toString());
        assertEquals("1 个", text(activity, "compendium_count"));
        tag(activity, "compendium_search_clear").performClick();
        assertEquals("", ((EditText) tag(activity, "compendium_search")).getText().toString());
        assertEquals("173 个", text(activity, "compendium_count"));
    }

    @Test public void cancellingAChangedPartnerPreservesTheLastFusionResult() {
        HomeActivity activity = open();
        tag(activity, "fusion_tab").performClick();
        pickPair(activity, "Orpheus", "Power");
        Compendium.Persona result = new FusionCalculator(data, Collections.emptySet())
            .fuse(persona("Orpheus").id, persona("Power").id).result;
        tag(activity, "fusion_material_2").performClick();
        ((EditText) tag(activity, "fusion_picker_search")).setText("Alice");
        activity.onBackPressed();
        Shots.idle();
        assertFalse(activity.sheets().isShowing());
        assertNotNull(tag(activity, "persona_" + result.id));
        assertTrue(text(activity, "fusion_material_1").contains(persona("Orpheus").nameZh));
        assertTrue(text(activity, "fusion_material_2").contains(persona("Power").nameZh));
    }

    @Test public void removingADlcPackClearsItsSelectedMaterialAndRefreshesTheCatalogue() {
        prefs.setOwnedDlc(Collections.singleton("p5r_1"));
        HomeActivity activity = open();
        tag(activity, "fusion_tab").performClick();
        pickPair(activity, "Carmen", "Power");
        tag(activity, "compendium_dlc").performClick();
        tag(activity, "dlc_pack_p5r_1").performClick();
        assertTrue("unticking the pack applies at once", prefs.ownedDlc().isEmpty());
        Shots.click(activity, "好了");
        Shots.idle();
        assertFalse(activity.sheets().isShowing());
        assertFalse(text(activity, "fusion_material_1").contains(persona("Carmen").nameZh));
        assertTrue(text(activity, "fusion_material_2").contains(persona("Power").nameZh));
        tag(activity, "compendium_tab").performClick();
        assertEquals("173 个", text(activity, "compendium_count"));
    }

    @Test public void switchingMainTabsKeepsTheCurrentSearchAndBasketWorkspace() {
        FusionCalculator calculator = new FusionCalculator(data, Collections.emptySet());
        FusionBasket basket = new FusionBasket();
        assertTrue(basket.addGoal(calculator.fuse(persona("Orpheus").id, persona("Power").id)));
        prefs.setBasket(basket);
        HomeActivity activity = open();
        ((EditText) tag(activity, "compendium_search")).setText("Narcissus");
        View toolWorkspace = tag(activity, "compendium_page");
        tag(activity, "basket_tab").performClick();
        Shots.idle();
        View basketWorkspace = tag(activity, "basket_page");

        activity.selectTab(HomeActivity.TAB_SCHEDULE);
        Shots.idle();
        activity.selectTab(HomeActivity.TAB_COMPENDIUM);
        Shots.idle();
        assertSame(toolWorkspace, tag(activity, "compendium_page"));
        assertSame(basketWorkspace, tag(activity, "basket_page"));
        assertEquals(Collections.singletonList(persona("Narcissus").id), prefs.basket().goals());
        assertNotNull(tag(activity, "basket_missing_" + persona("Orpheus").id));
        assertNotNull(tag(activity, "basket_missing_" + persona("Power").id));
        tag(activity, "compendium_tab").performClick();
        assertEquals("Narcissus", ((EditText) tag(activity, "compendium_search")).getText().toString());
        assertEquals("1 个", text(activity, "compendium_count"));
        assertEquals(0, activity.pageDepth());
    }

    private HomeActivity open() {
        HomeActivity activity = home();
        activity.selectTab(HomeActivity.TAB_COMPENDIUM);
        Shots.idle();
        return activity;
    }

    private Compendium.Persona persona(String key) {
        Compendium.Persona value = data.personaByKey(key);
        assertNotNull(key, value);
        return value;
    }

    private void pickPair(HomeActivity activity, String first, String second) {
        tag(activity, "fusion_material_1").performClick();
        chooseInPicker(activity, first);
        if (!activity.sheets().isShowing()) tag(activity, "fusion_material_2").performClick();
        chooseInPicker(activity, second);
    }

    private void chooseInPicker(HomeActivity activity, String key) {
        ((EditText) tag(activity, "fusion_picker_search")).setText(key);
        chooseFromList(activity, "fusion_picker_list", persona(key).id);
        Shots.idle();
    }

    private static boolean pickerContains(HomeActivity activity, String id) {
        ListView list = tag(activity, "fusion_picker_list");
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (((Compendium.Persona) list.getAdapter().getItem(i)).id.equals(id)) return true;
        }
        return false;
    }

    private static void chooseFromList(HomeActivity activity, String listTag, String id) {
        ListView list = tag(activity, listTag);
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (((Compendium.Persona) list.getAdapter().getItem(i)).id.equals(id)) {
                list.getAdapter().getView(i, null, list).performClick();
                Shots.idle();
                return;
            }
        }
        throw new AssertionError("Missing Persona: " + id);
    }

    private static String text(HomeActivity activity, String tag) {
        View view = tag(activity, tag);
        return view instanceof TextView ? Zh.plain(((TextView) view).getText()) : Shots.allText(view);
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
}
