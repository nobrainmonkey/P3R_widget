package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
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
import java.util.List;

import app.p3r.dayplanner.v3.compendium.Compendium;
import app.p3r.dayplanner.v3.compendium.CompendiumPrefs;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;

/** Real catalogue coverage and complete user journeys, independent of a current calendar. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CompendiumPageTest extends UiTestBase {
    private Compendium data;
    private CompendiumPrefs prefs;

    @Before public void catalogue() throws Exception {
        try (InputStream input = RuntimeEnvironment.getApplication().getAssets().open(Compendium.ASSET_NAME)) {
            data = Compendium.load(input);
        }
        prefs = new CompendiumPrefs(RuntimeEnvironment.getApplication());
        prefs.setOwnedDlc(Collections.emptySet());
        for (String id : prefs.favorites()) prefs.setFavorite(id, false);
    }

    @Test public void searchDetailFavoriteAndBackKeepTheOriginalLookup() {
        HomeActivity activity = open();
        assertEquals("173 个", text(activity, "compendium_count"));
        EditText search = tagged(activity, "compendium_search");
        search.setText("Orpheus");
        Shots.idle();
        Compendium.Persona orpheus = data.personaByKey("Orpheus");
        assertTrue(contains(activity, orpheus.id));
        capture(activity, "compendium_search");
        choose(activity, "compendium_list", orpheus.id);
        assertTrue(Shots.has(activity, "弱点与耐性"));
        assertTrue(Shots.has(activity, "可学技能"));
        assertTrue(Shots.has(activity, orpheus.nameEn));
        tagged(activity, "persona_favorite_" + orpheus.id).performClick();
        assertTrue(prefs.favorites().contains(orpheus.id));
        capture(activity, "compendium_detail");
        activity.onBackPressed();
        assertEquals("Orpheus", ((EditText) tagged(activity, "compendium_search")).getText().toString());
        Shots.click(activity, "收藏");
        assertEquals("1 个", text(activity, "compendium_count"));
        assertEquals(orpheus.id, item(activity, 0).id);
        assertTrue(api.calls.isEmpty());
    }

    @Test public void filtersApplyArcanaLevelAndOrderingAndResetTogether() {
        HomeActivity activity = open();
        Shots.click(activity, "筛选");
        ((EditText) tagged(activity, "compendium_min_level")).setText("20");
        ((EditText) tagged(activity, "compendium_max_level")).setText("70");
        Shots.click(activity, data.arcana("Lovers").nameZh);
        Shots.click(activity, "等级 ↓");
        Shots.click(activity, "应用");
        Shots.idle();
        ListView list = tagged(activity, "compendium_list");
        assertTrue(list.getAdapter().getCount() > 1);
        int last = 100;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            Compendium.Persona persona = (Compendium.Persona) list.getAdapter().getItem(i);
            assertEquals("Lovers", persona.arcana);
            assertTrue(persona.level >= 20 && persona.level <= 70);
            assertTrue(persona.level <= last);
            last = persona.level;
        }
        Shots.click(activity, "筛选");
        Shots.click(activity, "重置");
        Shots.click(activity, "应用");
        assertEquals("173 个", text(activity, "compendium_count"));
    }

    @Test public void ownedDlcChangesTheActualFusionResult() {
        HomeActivity activity = open();
        tagged(activity, "fusion_tab").performClick();
        pick(activity, "fusion_material_1", "Orpheus", "Orpheus");
        pick(activity, "fusion_material_2", "Power", "Power");
        Compendium.Persona narcissus = data.personaByKey("Narcissus");
        assertNotNull(tagged(activity, "persona_" + narcissus.id));
        capture(activity, "fusion_base_result");
        tagged(activity, "compendium_dlc").performClick();
        String pack = data.dlcPacks().stream().filter(value -> value.id.equals("p5r_1")).findFirst().get().nameZh;
        Shots.click(activity, pack);
        Shots.click(activity, "保存");
        Shots.idle();
        Compendium.Persona carmen = data.personaByKey("Carmen");
        assertNotNull(tagged(activity, "persona_" + carmen.id));
        assertEquals(Collections.singleton("p5r_1"), prefs.ownedDlc());
        tagged(activity, "compendium_tab").performClick();
        assertEquals("182 个", text(activity, "compendium_count"));
    }

    @Test public void aSpecialRecipeLinksBackToEachMaterial() {
        HomeActivity activity = open();
        Compendium.Persona alice = data.personaByKey("Alice");
        ((EditText) tagged(activity, "compendium_search")).setText("Alice");
        choose(activity, "compendium_list", alice.id);
        tagged(activity, "persona_recipes_" + alice.id).performClick();
        Shots.idle();
        assertTrue(Shots.has(activity, "特殊合成"));
        capture(activity, "fusion_special_recipe");
        FusionCalculator.Recipe recipe = new FusionCalculator(data, Collections.emptySet()).recipesFor(alice.id).get(0);
        Compendium.Persona material = recipe.ingredients.get(0);
        Shots.click(activity, material.nameZh + " · " + data.arcana(material.arcana).nameZh + " Lv." + material.level);
        assertNotNull(tagged(activity, "persona_recipes_" + material.id));
        activity.onBackPressed();
        activity.onBackPressed();
        activity.onBackPressed();
        assertEquals("Alice", ((EditText) tagged(activity, "compendium_search")).getText().toString());
    }

    @Test public void skillAndBilingualSearchCoverTheCompleteCatalogue() {
        FusionCalculator calculator = new FusionCalculator(data, Collections.emptySet());
        Compendium.Persona orpheus = data.personaByKey("Orpheus");
        Compendium.Skill skill = data.skill(orpheus.skills.get(0).skillId);
        for (String query : new String[] {orpheus.nameZh, "ORPHEUS", skill.nameZh, skill.nameEn}) {
            List<Compendium.Persona> found = CompendiumQuery.find(data, calculator, query, null, 1, 99,
                false, Collections.emptySet(), false, CompendiumQuery.LEVEL_ASC);
            assertTrue(query, found.stream().anyMatch(value -> value.id.equals(orpheus.id)));
        }
        assertEquals(194, CompendiumQuery.find(data, calculator, "", null, 1, 99, false,
            Collections.emptySet(), true, CompendiumQuery.LEVEL_ASC).size());
        for (String query : new String[] {"Armageddon", "哈米吉多顿"}) {
            List<Compendium.Persona> found = CompendiumQuery.find(data, calculator, query, null, 1, 99,
                false, Collections.emptySet(), false, CompendiumQuery.LEVEL_ASC);
            assertTrue(query, found.stream().anyMatch(value -> value.key.equals("Helel")));
            assertTrue(query, found.stream().anyMatch(value -> value.key.equals("Satan")));
        }
        assertFalse(CompendiumQuery.find(data, calculator, "does not exist", null, 1, 99, false,
            Collections.emptySet(), false, CompendiumQuery.LEVEL_ASC).stream().findAny().isPresent());
    }

    private HomeActivity open() {
        HomeActivity activity = home();
        activity.selectTab(HomeActivity.TAB_ME);
        Shots.click(activity, "图鉴与合成");
        Shots.idle();
        assertNotNull(tagged(activity, "compendium_page"));
        return activity;
    }

    private void pick(HomeActivity activity, String button, String query, String key) {
        tagged(activity, button).performClick();
        ((EditText) tagged(activity, "fusion_picker_search")).setText(query);
        choose(activity, "fusion_picker_list", data.personaByKey(key).id);
        Shots.idle();
    }

    private static void choose(HomeActivity activity, String listTag, String id) {
        ListView list = tagged(activity, listTag);
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (((Compendium.Persona) list.getAdapter().getItem(i)).id.equals(id)) {
                list.getAdapter().getView(i, null, list).performClick();
                Shots.idle();
                return;
            }
        }
        throw new AssertionError("Missing Persona: " + id);
    }

    private static Compendium.Persona item(HomeActivity activity, int position) {
        ListView list = tagged(activity, "compendium_list");
        return (Compendium.Persona) list.getAdapter().getItem(position);
    }

    private static boolean contains(HomeActivity activity, String id) {
        ListView list = tagged(activity, "compendium_list");
        for (int i = 0; i < list.getAdapter().getCount(); i++) if (item(activity, i).id.equals(id)) return true;
        return false;
    }

    private static String text(HomeActivity activity, String tag) {
        return Zh.plain(((TextView) tagged(activity, tag)).getText());
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T tagged(HomeActivity activity, String tag) {
        T view = (T) activity.getWindow().getDecorView().findViewWithTag(tag);
        assertNotNull(tag, view);
        return view;
    }

    private static void capture(HomeActivity activity, String name) {
        Shots.checkTypography(activity.getWindow().getDecorView(), name, true);
        Shots.save(Shots.capture(activity, false), name);
    }
}
