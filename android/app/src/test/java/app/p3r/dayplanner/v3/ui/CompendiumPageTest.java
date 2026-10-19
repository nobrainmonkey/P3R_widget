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
import java.util.List;

import app.p3r.dayplanner.v3.compendium.Compendium;
import app.p3r.dayplanner.v3.compendium.CompendiumPrefs;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;
import app.p3r.dayplanner.v3.compendium.FusionBasket;

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
        prefs.setBasket(new FusionBasket());
        for (String id : prefs.ownedPersonas()) prefs.setOwned(id, false);
        for (String id : prefs.favorites()) prefs.setFavorite(id, false);
    }

    @Test public void searchDetailFavoriteAndBackKeepTheOriginalLookup() {
        HomeActivity activity = open();
        assertEquals("173 个", text(activity, "compendium_count"));
        assertTrue("the segments use the official terms", Shots.has(activity, "一览") && Shots.has(activity, "合体")
            && Shots.has(activity, "合体篮"));
        capture(activity, "compendium");
        EditText search = tagged(activity, "compendium_search");
        search.setText("Orpheus");
        Shots.idle();
        Compendium.Persona orpheus = data.personaByKey("Orpheus");
        assertTrue(contains(activity, orpheus.id));
        capture(activity, "compendium_search");
        choose(activity, "compendium_list", orpheus.id);
        assertTrue(Shots.has(activity, "弱点与耐性"));
        assertTrue(Shots.has(activity, "可学技能"));
        assertTrue(Shots.has(activity, "能力值"));
        assertTrue(Shots.has(activity, orpheus.nameEn));
        assertTrue("the page is titled with the arcana, the hero carries the name",
            Shots.has(activity, data.arcana(orpheus.arcana).nameZh) && Shots.has(activity, orpheus.nameZh));
        TextView favorite = tagged(activity, "persona_favorite_" + orpheus.id);
        TextView registered = tagged(activity, "persona_detail_owned_" + orpheus.id);
        assertFalse(favorite.isSelected());
        assertFalse(registered.isSelected());
        assertEquals("收藏", text(favorite));
        assertEquals("未登录", text(registered));
        favorite.performClick();
        registered.performClick();
        assertTrue(prefs.favorites().contains(orpheus.id));
        assertTrue(prefs.ownedPersonas().contains(orpheus.id));
        assertTrue(favorite.isSelected());
        assertTrue(registered.isSelected());
        assertEquals("已收藏", text(favorite));
        assertEquals("已登录", text(registered));
        assertTrue(favorite.getContentDescription().toString().contains("取消收藏"));
        assertTrue(registered.getContentDescription().toString().contains("取消登录"));
        capture(activity, "compendium_detail");
        View skills = Shots.find(activity, "可学技能");
        android.widget.ScrollView page = scrollParent(skills);
        int before = page.getScrollY();
        page.scrollTo(0, ((View) skills.getParent()).getTop() - Ui.dp(activity, 12));
        capture(activity, "compendium_detail_skills");
        String detail = Shots.allText(page);
        assertFalse("costs are written with a space before the unit", detail.matches("(?s).*\\d(SP|%HP).*"));
        page.scrollTo(0, before);
        assertTrue(favorite.getHeight() >= Ui.dp(activity, 48));
        assertEquals(favorite.getHeight(), registered.getHeight());
        int[] starPosition = new int[2];
        int[] checkPosition = new int[2];
        favorite.getLocationOnScreen(starPosition);
        registered.getLocationOnScreen(checkPosition);
        assertEquals("the two state buttons share a baseline", starPosition[1], checkPosition[1]);
        activity.onBackPressed();
        assertEquals("Orpheus", ((EditText) tagged(activity, "compendium_search")).getText().toString());
        tagged(activity, "compendium_favorites").performClick();
        assertTrue(tagged(activity, "compendium_favorites").isSelected());
        assertEquals("1 个", text(activity, "compendium_count"));
        assertEquals(orpheus.id, item(activity, 0).id);
        assertTrue(api.calls.isEmpty());
    }

    @Test public void filtersApplyArcanaLevelAndOrderingAndResetTogether() {
        HomeActivity activity = open();
        TextView filter = tagged(activity, "compendium_filter");
        assertEquals("", filter.getText().toString());
        assertEquals("筛选", filter.getContentDescription().toString());
        filter.performClick();
        ((EditText) tagged(activity, "compendium_min_level")).setText("20");
        ((EditText) tagged(activity, "compendium_max_level")).setText("70");
        Shots.click(activity, data.arcana("Lovers").nameZh);
        tagged(activity, "compendium_sort_direction").performClick();
        Shots.click(activity, "看结果");
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
        assertTrue(filter.isSelected());
        String chips = Shots.allText(tagged(activity, "compendium_filter_selection"));
        assertTrue(chips, chips.contains("恋爱") && chips.contains("Lv.20–70") && chips.contains("等级 ↓"));
        capture(activity, "compendium_filtered");
        tagged(activity, "compendium_filter").performClick();
        Shots.click(activity, "重置");
        Shots.click(activity, "看结果");
        assertEquals("173 个", text(activity, "compendium_count"));
        assertFalse(filter.isSelected());
        assertEquals("", Shots.allText(tagged(activity, "compendium_filter_selection")));
        assertCatalogueOrder(activity, false, false);

        tagged(activity, "compendium_filter").performClick();
        tagged(activity, "compendium_sort_arcana").performClick();
        assertEquals("升序 ↑", text(activity, "compendium_sort_direction"));
        Shots.click(activity, "看结果");
        assertCatalogueOrder(activity, true, false);

        tagged(activity, "compendium_filter").performClick();
        tagged(activity, "compendium_sort_direction").performClick();
        assertTrue(tagged(activity, "compendium_sort_arcana").isSelected());
        assertEquals("降序 ↓", text(activity, "compendium_sort_direction"));
        Shots.click(activity, "看结果");
        assertCatalogueOrder(activity, true, true);

        tagged(activity, "compendium_filter").performClick();
        tagged(activity, "compendium_sort_level").performClick();
        assertEquals("降序 ↓", text(activity, "compendium_sort_direction"));
        tagged(activity, "compendium_sort_level").performClick();
        assertEquals("tapping the selected order flips it", "升序 ↑", text(activity, "compendium_sort_direction"));
        tagged(activity, "compendium_sort_level").performClick();
        Shots.click(activity, "看结果");
        assertCatalogueOrder(activity, false, true);
    }

    @Test public void filterChipsRemoveOneFilterAtATime() {
        HomeActivity activity = open();
        tagged(activity, "compendium_filter").performClick();
        Shots.click(activity, data.arcana("Lovers").nameZh);
        tagged(activity, "compendium_show_unowned").performClick();
        Shots.click(activity, "看结果");
        int lovers = adapterCount(activity);
        Shots.click(activity, "去掉筛选：恋爱");
        assertEquals("194 个", text(activity, "compendium_count"));
        assertTrue(tagged(activity, "compendium_filter").isSelected());
        Shots.click(activity, "去掉筛选：含没买的 DLC");
        assertEquals("173 个", text(activity, "compendium_count"));
        assertFalse(tagged(activity, "compendium_filter").isSelected());
        assertTrue(lovers > 1 && lovers < 173);
    }

    @Test public void sortingByArcanaGroupsTheListUnderArcanaHeaders() {
        HomeActivity activity = open();
        tagged(activity, "compendium_filter").performClick();
        tagged(activity, "compendium_sort_arcana").performClick();
        Shots.click(activity, "看结果");
        ListView list = tagged(activity, "compendium_list");
        View firstRow = list.getAdapter().getView(0, null, list);
        View secondRow = list.getAdapter().getView(1, null, list);
        assertTrue("the first Fool row starts the Fool group", Shots.allText(firstRow).contains("FOOL"));
        assertFalse("later rows of the same arcana have no header", Shots.allText(secondRow).contains("FOOL"));
        capture(activity, "compendium_by_arcana");
    }

    @Test public void listRowsAreRecycledAndRebound() {
        HomeActivity activity = open();
        ListView list = tagged(activity, "compendium_list");
        View row = list.getAdapter().getView(0, null, list);
        Compendium.Persona second = item(activity, 1);
        View rebound = list.getAdapter().getView(1, row, list);
        assertSame(row, rebound);
        assertEquals("persona_" + second.id, rebound.getTag());
        assertNotNull(rebound.findViewWithTag("favorite_" + second.id));
        assertNotNull(rebound.findViewWithTag("persona_owned_" + second.id));
        rebound.findViewWithTag("favorite_" + second.id).performClick();
        assertTrue(prefs.favorites().contains(second.id));
    }

    @Test public void towerSourcesAreVisibleBesideThePersonaInformation() {
        HomeActivity activity = open();
        Compendium.Persona pixie = data.personaByKey("Pixie");
        assertFalse("Pixie has a shuffle-time source", pixie.origins.isEmpty());
        ((EditText) tagged(activity, "compendium_search")).setText("Pixie");
        choose(activity, "compendium_list", pixie.id);
        View origins = tagged(activity, "persona_origins_" + pixie.id);
        String source = Shots.allText(origins);
        assertTrue(source.contains("洗牌时间"));
        assertFalse("floors are written with F, as the glossary does", source.contains("层"));
        for (Compendium.Origin origin : pixie.origins) {
            assertTrue(source.contains(origin.blockNameZh));
            String range = origin.floorFrom == origin.floorTo ? Integer.toString(origin.floorFrom)
                : origin.floorFrom + "–" + origin.floorTo;
            assertTrue("source includes " + range, source.contains(range));
        }
        capture(activity, "compendium_tower_source");
    }

    @Test public void fusionConditionsReadInTheGlossarysWords() {
        String[][] expected = {
            {"Asura", "太阳社群升到 MAX"},
            {"Orpheus Telos", "所有社群都升到 MAX"},
            {"Hua Po", "完成伊莉莎白的第 77 号委托"},
            {"Saturnus", "看完望月绫时的全部羁绊剧情"},
            {"Surt", "看完伊织顺平的全部羁绊剧情"},
            {"Decarabia", "塔尔塔罗斯到达 143F"},
        };
        for (String[] row : expected) {
            assertEquals(row[0], row[1], PersonaViews.condition(data, data.personaByKey(row[0])));
        }
        for (Compendium.Persona persona : data.personas()) {
            String condition = PersonaViews.condition(data, persona);
            assertFalse(condition, condition.contains("伊丽莎白") || condition.contains("#")
                || condition.contains("支线") || condition.contains("层") || condition.matches(".*[^ ]MAX.*"));
        }
        HomeActivity activity = open();
        Compendium.Persona asura = data.personaByKey("Asura");
        ((EditText) tagged(activity, "compendium_search")).setText("Asura");
        choose(activity, "compendium_list", asura.id);
        assertTrue(Shots.has(activity, "合体条件"));
        assertTrue(Shots.has(activity, "太阳社群升到 MAX"));
        capture(activity, "compendium_condition");
    }

    @Test public void elementAndPackNamesUseTheOfficialTerms() {
        assertEquals("火焰", data.elementNameZh("fir"));
        assertEquals("神圣", data.elementNameZh("lig"));
        assertEquals("暗黑", data.elementNameZh("dar"));
        assertEquals("恢复", data.elementNameZh("rec"));
        assertEquals("被动", data.elementNameZh("pas"));
        assertEquals("P5R 人格面具组合 1", PersonaViews.dlcName(data, "p5r_1"));
        assertEquals("P4G 人格面具组合", PersonaViews.dlcName(data, "p4g"));
        for (Compendium.Skill skill : data.skills()) {
            assertFalse(skill.effectZh, skill.effectZh.contains("回复") || skill.effectZh.contains("火炎")
                || skill.effectZh.contains("光明") || skill.effectZh.contains("黑暗"));
        }
        assertEquals("3 SP", PersonaViews.cost(data.skill("skill_10")));
    }

    @Test public void ownedDlcChangesTheActualFusionResult() {
        HomeActivity activity = open();
        tagged(activity, "fusion_tab").performClick();
        pick(activity, "fusion_material_1", "Orpheus", "Orpheus");
        pick(activity, "fusion_material_2", "Power", "Power");
        Compendium.Persona narcissus = data.personaByKey("Narcissus");
        assertNotNull(tagged(activity, "persona_" + narcissus.id));
        capture(activity, "fusion_base_result");
        assertFalse(tagged(activity, "compendium_dlc").isSelected());
        tagged(activity, "compendium_dlc").performClick();
        assertTrue(Shots.has(activity, "买了哪些 DLC？"));
        Shots.click(activity, PersonaViews.dlcName(data, "p5r_1"));
        assertEquals("a tap applies at once", Collections.singleton("p5r_1"), prefs.ownedDlc());
        Shots.click(activity, "好了");
        Shots.idle();
        Compendium.Persona carmen = data.personaByKey("Carmen");
        assertNotNull(tagged(activity, "persona_" + carmen.id));
        assertTrue("the header plate shows owned DLC", tagged(activity, "compendium_dlc").isSelected());
        tagged(activity, "compendium_tab").performClick();
        assertEquals("182 个", text(activity, "compendium_count"));
    }

    @Test public void aDlcPersonaAsksForItsPackInPlace() {
        HomeActivity activity = open();
        tagged(activity, "compendium_filter").performClick();
        tagged(activity, "compendium_show_unowned").performClick();
        Shots.click(activity, "看结果");
        Compendium.Persona carmen = data.personaByKey("Carmen");
        ((EditText) tagged(activity, "compendium_search")).setText("Carmen");
        choose(activity, "compendium_list", carmen.id);
        assertTrue(Shots.has(activity, "这是 P5R 人格面具组合 1 里的人格面具，买了吗？"));
        capture(activity, "compendium_dlc_prompt");
        tagged(activity, "persona_material_" + carmen.id).performClick();
        assertTrue("using it as a material waits for the answer", prefs.ownedDlc().isEmpty());
        tagged(activity, "dlc_owned_" + carmen.id).performClick();
        Shots.idle();
        assertEquals(Collections.singleton("p5r_1"), prefs.ownedDlc());
        assertEquals("the material fusion continues on the 合体 pane", 0, activity.pageDepth());
        assertTrue(activity.sheets().isShowing());
        assertNotNull(tagged(activity, "fusion_picker_list"));
    }

    @Test public void aSpecialRecipeLinksBackToEachMaterial() {
        HomeActivity activity = open();
        Compendium.Persona alice = data.personaByKey("Alice");
        ((EditText) tagged(activity, "compendium_search")).setText("Alice");
        choose(activity, "compendium_list", alice.id);
        tagged(activity, "persona_recipes_" + alice.id).performClick();
        Shots.idle();
        assertTrue(Shots.has(activity, "特殊合体"));
        assertFalse(Shots.has(activity, "特殊合成"));
        capture(activity, "fusion_special_recipe");
        FusionCalculator.Recipe recipe = new FusionCalculator(data, Collections.emptySet()).recipesFor(alice.id).get(0);
        Compendium.Persona material = recipe.ingredients.get(0);
        tagged(activity, "material_recipe_" + material.id).performClick();
        Shots.idle();
        assertNotNull("the ingredient's own recipes", tagged(activity, "persona_" + material.id));
        assertNotNull(tagged(activity, "fusion_original_goal"));
        tagged(activity, "persona_" + material.id).performClick();
        Shots.idle();
        assertNotNull(tagged(activity, "persona_recipes_" + material.id));
        assertTrue(activity.pageDepth() <= 1);
        activity.onBackPressed();
        assertEquals("Alice", ((EditText) tagged(activity, "compendium_search")).getText().toString());
    }

    @Test public void addingARecipeStaysOnThePage() {
        HomeActivity activity = open();
        Compendium.Persona alice = data.personaByKey("Alice");
        ((EditText) tagged(activity, "compendium_search")).setText("Alice");
        choose(activity, "compendium_list", alice.id);
        tagged(activity, "persona_recipes_" + alice.id).performClick();
        Shots.idle();
        int depth = activity.pageDepth();
        tagged(activity, "recipe_add_" + alice.id + "_0").performClick();
        Shots.idle();
        assertEquals(Collections.singletonList(alice.id), prefs.basket().goals());
        assertEquals("the player keeps their place", depth, activity.pageDepth());
        assertEquals(CompendiumPage.ADDED, activity.undoBar().messageText().toString());
        assertTrue(activity.undoBar().hasAction());
        activity.undoBar().performAction();
        Shots.idle();
        assertEquals(0, activity.pageDepth());
        assertTrue(tagged(activity, "basket_tab").isSelected());
        assertNotNull(tagged(activity, "basket_goal_" + alice.id));
        assertTrue(api.calls.isEmpty());
    }

    @Test public void skillAndBilingualSearchCoverTheCompleteCatalogue() {
        FusionCalculator calculator = new FusionCalculator(data, Collections.emptySet());
        Compendium.Persona orpheus = data.personaByKey("Orpheus");
        Compendium.Skill skill = data.skill(orpheus.skills.get(0).skillId);
        for (String query : new String[] {orpheus.nameZh, "ORPHEUS", "Ｏｒｐｈｅｕｓ", skill.nameZh, skill.nameEn}) {
            List<Compendium.Persona> found = CompendiumQuery.find(data, calculator, query, null, 1, 99,
                false, Collections.emptySet(), false, CompendiumQuery.LEVEL_ASC);
            assertTrue(query, found.stream().anyMatch(value -> value.id.equals(orpheus.id)));
        }
        for (String query : new String[] {"aramitama", "Ara Mitama", "ara mitama"}) {
            assertTrue(query, CompendiumQuery.find(data, calculator, query, null, 1, 99, false,
                Collections.emptySet(), false, CompendiumQuery.LEVEL_ASC).stream().anyMatch(p -> p.key.equals("Ara Mitama")));
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

    @Test public void secondaryMenuSurfacesRemainUsableAtPhoneSize() {
        HomeActivity activity = open();
        tagged(activity, "compendium_filter").performClick();
        capture(activity, "compendium_filter_sheet");
        assertNotNull(tagged(activity, "compendium_min_level"));
        assertNotNull(tagged(activity, "compendium_max_level"));
        activity.dismissSheet();
        tagged(activity, "compendium_dlc").performClick();
        capture(activity, "compendium_dlc_sheet");
        assertTrue(Shots.has(activity, "好了"));
        assertFalse("unticking every pack is all it takes", Shots.has(activity, "仅游戏本体"));
        activity.dismissSheet();
        tagged(activity, "fusion_tab").performClick();
        capture(activity, "fusion_root");
        tagged(activity, "fusion_material_1").performClick();
        capture(activity, "fusion_material_picker");
        ListView picker = tagged(activity, "fusion_picker_list");
        assertTrue(picker.getAdapter().getCount() > 0);
        assertTrue(picker.getHeight() > Ui.dp(activity, 120));
        View pickerRow = picker.getAdapter().getView(0, null, picker);
        assertTrue("picker rows have no toggles", findClickableChild(pickerRow) == null);
    }

    @Test public void selectingTheFirstMaterialKeepsTheNextSearchReadyForTyping() {
        HomeActivity activity = open();
        tagged(activity, "fusion_tab").performClick();
        tagged(activity, "fusion_material_1").performClick();
        EditText firstSearch = tagged(activity, "fusion_picker_search");
        assertTrue("first search accepts typing focus", firstSearch.requestFocus());
        firstSearch.setText("Orpheus");
        assertTrue("typing keeps the first search focused", firstSearch.hasFocus());
        choose(activity, "fusion_picker_list", data.personaByKey("Orpheus").id);
        assertTrue(activity.sheets().isShowing());
        assertTrue("the partner is named under the title", Shots.has(activity, "和 " + data.personaByKey("Orpheus").nameZh + " 合体"));
        EditText secondSearch = tagged(activity, "fusion_picker_search");
        assertTrue("the next material gets its own empty search", firstSearch != secondSearch);
        assertEquals("", secondSearch.getText().toString());
        assertTrue("continue typing without tapping the search again", secondSearch.hasFocus());
    }

    @Test public void restoredToolChoicesRefreshWithoutDiscardingTheCurrentLookup() {
        HomeActivity activity = open();
        EditText search = tagged(activity, "compendium_search");
        search.setText("Orpheus");
        Compendium.Persona orpheus = data.personaByKey("Orpheus");
        prefs.setFavorite(orpheus.id, true);
        prefs.setOwned(orpheus.id, true);
        prefs.setOwnedDlc(Collections.singleton("p5r_1"));
        CompendiumPage.refresh(tagged(activity, "compendium_page"));
        assertEquals("Orpheus", search.getText().toString());

        tagged(activity, "compendium_favorites").performClick();
        assertEquals("1 个", text(activity, "compendium_count"));
        ListView list = tagged(activity, "compendium_list");
        View row = list.getAdapter().getView(0, null, list);
        assertEquals(orpheus.id, item(activity, 0).id);
        assertTrue(row.findViewWithTag("persona_owned_" + orpheus.id).isSelected());

        tagged(activity, "compendium_favorites").performClick();
        assertFalse(tagged(activity, "compendium_favorites").isSelected());
        search.setText("Carmen");
        assertTrue(contains(activity, data.personaByKey("Carmen").id));
        prefs.setOwnedDlc(Collections.emptySet());
        CompendiumPage.refresh(tagged(activity, "compendium_page"));
        assertEquals("Carmen", search.getText().toString());
        assertEquals("0 个", text(activity, "compendium_count"));
    }

    private HomeActivity open() {
        HomeActivity activity = home();
        activity.selectTab(HomeActivity.TAB_COMPENDIUM);
        Shots.idle();
        assertNotNull(tagged(activity, "compendium_page"));
        return activity;
    }

    private void pick(HomeActivity activity, String button, String query, String key) {
        if (!activity.sheets().isShowing()) tagged(activity, button).performClick();
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

    private static android.widget.ScrollView scrollParent(View view) {
        View current = view;
        while (current != null && !(current instanceof android.widget.ScrollView)) {
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        assertNotNull("inside a scrolling page", current);
        return (android.widget.ScrollView) current;
    }

    private static View findClickableChild(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isClickable()) return child;
            View nested = findClickableChild(child);
            if (nested != null) return nested;
        }
        return null;
    }

    private static int adapterCount(HomeActivity activity) {
        ListView list = tagged(activity, "compendium_list");
        return list.getAdapter().getCount();
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

    private void assertCatalogueOrder(HomeActivity activity, boolean byArcana, boolean descending) {
        ListView list = tagged(activity, "compendium_list");
        assertTrue(list.getAdapter().getCount() > 1);
        int previous = descending ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            Compendium.Persona persona = item(activity, i);
            int key = persona.level + (byArcana ? data.arcana(persona.arcana).order * 100 : 0);
            assertTrue("order at " + persona.nameEn + ": " + previous + " → " + key,
                descending ? previous >= key : previous <= key);
            previous = key;
        }
    }

    private static String text(HomeActivity activity, String tag) {
        return text((TextView) tagged(activity, tag));
    }

    private static String text(TextView view) {
        return Zh.plain(view.getText());
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
