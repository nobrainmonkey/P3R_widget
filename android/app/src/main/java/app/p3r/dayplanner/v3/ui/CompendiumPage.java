package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.compendium.Compendium;
import app.p3r.dayplanner.v3.compendium.CompendiumPrefs;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;

/** One offline entry for Persona lookup, favorites and both directions of fusion. */
public final class CompendiumPage {
    private static volatile Compendium cached;
    private static final int RECIPE_PAGE = 30;
    private static final String[] SORT_LABELS = {"等级 ↑", "等级 ↓", "塔罗牌", "名称"};
    private final HomeHost host;
    private final Context c;
    private final CompendiumPrefs prefs;
    private final LinearLayout root;
    private Compendium data;
    private FusionCalculator calculator;
    private LinearLayout listPane;
    private LinearLayout fusionPane;
    private TextView listSegment;
    private TextView fusionSegment;
    private TextView filterButton;
    private TextView allButton;
    private TextView favoritesButton;
    private TextView count;
    private TextView selection;
    private TextView empty;
    private ListView list;
    private PersonaAdapter adapter;
    private LinearLayout fusionContent;
    private Compendium.Persona first;
    private Compendium.Persona second;
    private Compendium.Persona target;
    private boolean fusionSelected;
    private boolean favoritesOnly;
    private boolean showUnowned;
    private String query = "";
    private String arcana;
    private int minLevel = 1;
    private int maxLevel = 99;
    private int order = CompendiumQuery.LEVEL_ASC;
    private Set<String> favoriteIds;
    private Set<String> ownedDlc;

    public static View create(HomeHost host) {
        return new CompendiumPage(host).root;
    }

    private CompendiumPage(HomeHost host) {
        this.host = host;
        c = host.activity();
        prefs = new CompendiumPrefs(c);
        favoriteIds = new HashSet<>(prefs.favorites());
        ownedDlc = new HashSet<>(prefs.ownedDlc());
        root = Ui.column(c);
        root.setTag("compendium_page");
        root.setFocusableInTouchMode(true);
        load();
    }

    private void load() {
        root.removeAllViews();
        TextView loading = Ui.muted(c, "正在打开图鉴…");
        Ui.pad(loading, Ui.GUTTER, 24, Ui.GUTTER, 24);
        root.addView(loading);
        Async.run(host.activity(), () -> {
            Compendium result = cached;
            if (result == null) {
                synchronized (CompendiumPage.class) {
                    result = cached;
                    if (result == null) {
                        try (InputStream input = c.getApplicationContext().getAssets().open(Compendium.ASSET_NAME)) {
                            result = Compendium.load(input);
                        }
                        cached = result;
                    }
                }
            }
            return result;
        }, loaded -> {
            data = loaded;
            calculator = new FusionCalculator(data, ownedDlc);
            build();
        }, error -> {
            root.removeAllViews();
            TextView message = Ui.body(c, "图鉴暂时打不开");
            Ui.pad(message, Ui.GUTTER, 24, Ui.GUTTER, 12);
            root.addView(message);
            TextView retry = Ui.primary(c, "重试");
            retry.setOnClickListener(v -> load());
            root.addView(retry, top(8));
        });
    }

    private void build() {
        root.removeAllViews();
        LinearLayout header = Ui.column(c);
        Ui.pad(header, Ui.GUTTER, 8, Ui.GUTTER, 8);
        LinearLayout segments = Ui.row(c);
        listSegment = Ui.chip(c, "图鉴", !fusionSelected);
        listSegment.setTag("compendium_tab");
        fusionSegment = Ui.chip(c, "合成", fusionSelected);
        fusionSegment.setTag("fusion_tab");
        listSegment.setOnClickListener(v -> selectSegment(false));
        fusionSegment.setOnClickListener(v -> selectSegment(true));
        segments.addView(listSegment, Ui.weight(1));
        segments.addView(fusionSegment, weightedGap());
        TextView dlc = Ui.chip(c, "DLC", false);
        dlc.setTag("compendium_dlc");
        dlc.setContentDescription("选择已购买的面具 DLC");
        dlc.setOnClickListener(v -> openDlc());
        LinearLayout.LayoutParams dlcGap = Ui.wrap();
        dlcGap.leftMargin = Ui.dp(c, 8);
        segments.addView(dlc, dlcGap);
        header.addView(segments);
        root.addView(header);
        buildList();
        fusionPane = Ui.column(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        fusionContent = Ui.column(c);
        Ui.pad(fusionContent, Ui.GUTTER, 4, Ui.GUTTER, 28);
        scroll.addView(fusionContent);
        fusionPane.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(fusionPane, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        refreshList(true);
        renderFusion();
        selectSegment(fusionSelected);
    }

    private void buildList() {
        listPane = Ui.column(c);
        LinearLayout controls = Ui.column(c);
        Ui.pad(controls, Ui.GUTTER, 0, Ui.GUTTER, 4);
        EditText search = search("名称、英文名或技能", "compendium_search");
        search.addTextChangedListener(watcher(text -> { query = text; refreshList(true); }));
        controls.addView(search);
        LinearLayout filters = Ui.row(c);
        allButton = Ui.chip(c, "全部", true);
        favoritesButton = Ui.chip(c, "收藏", false);
        filterButton = Ui.chip(c, "筛选", false);
        allButton.setOnClickListener(v -> { favoritesOnly = false; refreshList(true); });
        favoritesButton.setOnClickListener(v -> { favoritesOnly = true; refreshList(true); });
        filterButton.setOnClickListener(v -> openFilters());
        filters.addView(allButton, Ui.weight(1));
        filters.addView(favoritesButton, weightedGap());
        filters.addView(filterButton, weightedGap());
        count = Ui.caption(c, "");
        count.setTag("compendium_count");
        count.setGravity(Gravity.END);
        LinearLayout.LayoutParams countGap = Ui.wrap();
        countGap.leftMargin = Ui.dp(c, 8);
        filters.addView(count, countGap);
        controls.addView(filters, top(8));
        selection = Ui.caption(c, "");
        selection.setTag("compendium_filter_selection");
        controls.addView(selection, top(6));
        listPane.addView(controls);
        FrameLayout results = new FrameLayout(c);
        list = personaList();
        list.setTag("compendium_list");
        adapter = new PersonaAdapter(true, this::openDetail);
        list.setAdapter(adapter);
        results.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        empty = Ui.body(c, "没有匹配结果");
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 16, 24, 16);
        results.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        listPane.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(listPane, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void selectSegment(boolean fusion) {
        fusionSelected = fusion;
        Ui.setChipSelected(listSegment, !fusion);
        Ui.setChipSelected(fusionSegment, fusion);
        listPane.setVisibility(fusion ? View.GONE : View.VISIBLE);
        fusionPane.setVisibility(fusion ? View.VISIBLE : View.GONE);
        hideKeyboard();
    }

    private void refreshList(boolean resetPosition) {
        if (data == null || adapter == null) return;
        List<Compendium.Persona> matches = CompendiumQuery.find(data, calculator, query, arcana,
            minLevel, maxLevel, favoritesOnly, favoriteIds, showUnowned, order);
        adapter.setItems(matches);
        count.setText(matches.size() + " 个");
        empty.setText(favoritesOnly && favoriteIds.isEmpty() ? "还没有收藏的人格面具" : "没有匹配结果");
        empty.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(matches.isEmpty() ? View.GONE : View.VISIBLE);
        Ui.setChipSelected(allButton, !favoritesOnly);
        Ui.setChipSelected(favoritesButton, favoritesOnly);
        boolean filtered = arcana != null || minLevel > 1 || maxLevel < 99 || showUnowned
            || order != CompendiumQuery.LEVEL_ASC;
        Ui.setChipSelected(filterButton, filtered);
        StringBuilder caption = new StringBuilder();
        if (arcana != null) caption.append(arcanaName(arcana));
        if (minLevel > 1 || maxLevel < 99) append(caption, "Lv." + minLevel + " ～ " + maxLevel);
        if (order != CompendiumQuery.LEVEL_ASC) append(caption, SORT_LABELS[order]);
        if (showUnowned) append(caption, "含未购买 DLC");
        selection.setText(caption.toString());
        selection.setVisibility(caption.length() == 0 ? View.GONE : View.VISIBLE);
        if (resetPosition) list.setSelection(0);
    }

    private void openFilters() {
        String[] pendingArcana = {arcana};
        int[] pendingOrder = {order};
        boolean[] pendingUnowned = {showUnowned};
        LinearLayout sheet = sheet("筛选图鉴");
        sheet.addView(Ui.sectionHeader(c, "等级", null), top(12));
        LinearLayout levels = Ui.row(c);
        EditText min = number(minLevel, "compendium_min_level");
        EditText max = number(maxLevel, "compendium_max_level");
        levels.addView(min, Ui.weight(1));
        TextView dash = Ui.body(c, " ～ ");
        levels.addView(dash, Ui.wrap());
        levels.addView(max, Ui.weight(1));
        sheet.addView(levels);
        sheet.addView(Ui.sectionHeader(c, "排序", null), top(12));
        List<TextView> sorts = new ArrayList<>();
        List<TextView> arcanas = new ArrayList<>();
        TextView unowned = choice("显示未购买 DLC");
        Runnable paint = () -> {
            for (TextView value : sorts) SheetChrome.selectChoice(value, ((Integer) value.getTag()) == pendingOrder[0]);
            for (TextView value : arcanas) SheetChrome.selectChoice(value,
                pendingArcana[0] == null ? value.getTag() == null : pendingArcana[0].equals(value.getTag()));
            SheetChrome.selectChoice(unowned, pendingUnowned[0]);
        };
        for (int i = 0; i < SORT_LABELS.length; i++) {
            int index = i;
            TextView value = choice(SORT_LABELS[i]);
            value.setTag(i);
            value.setOnClickListener(v -> { pendingOrder[0] = index; paint.run(); });
            sorts.add(value);
        }
        grid(sheet, sorts, 2);
        sheet.addView(Ui.sectionHeader(c, "塔罗牌", null), top(12));
        TextView any = choice("全部");
        any.setOnClickListener(v -> { pendingArcana[0] = null; paint.run(); });
        arcanas.add(any);
        for (Compendium.Arcana item : data.arcana()) {
            TextView value = choice(item.nameZh);
            value.setTag(item.id);
            value.setOnClickListener(v -> { pendingArcana[0] = item.id; paint.run(); });
            arcanas.add(value);
        }
        LinearLayout arcanaGrid = Ui.column(c);
        grid(arcanaGrid, arcanas, 3);
        ScrollView arcanaScroll = new ScrollView(c);
        arcanaScroll.addView(arcanaGrid);
        sheet.addView(arcanaScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(c, 184)));
        unowned.setOnClickListener(v -> { pendingUnowned[0] = !pendingUnowned[0]; paint.run(); });
        sheet.addView(unowned, top(12));
        LinearLayout actions = Ui.row(c);
        TextView reset = Ui.secondary(c, "重置");
        reset.setOnClickListener(v -> {
            pendingArcana[0] = null;
            pendingOrder[0] = CompendiumQuery.LEVEL_ASC;
            pendingUnowned[0] = false;
            min.setText("1");
            max.setText("99");
            paint.run();
        });
        actions.addView(reset, Ui.weight(1));
        TextView apply = Ui.primary(c, "应用");
        apply.setOnClickListener(v -> {
            int low = boundedLevel(min, 1);
            int high = boundedLevel(max, 99);
            minLevel = Math.min(low, high);
            maxLevel = Math.max(low, high);
            arcana = pendingArcana[0];
            order = pendingOrder[0];
            showUnowned = pendingUnowned[0];
            hideKeyboard();
            host.dismissSheet();
            refreshList(true);
        });
        actions.addView(apply, weightedGap());
        sheet.addView(actions, top(16));
        paint.run();
        host.showSheet(sheet);
    }

    private void openDlc() {
        openDlc(null);
    }

    private void openDlc(Runnable onSaved) {
        Set<String> pending = new HashSet<>(ownedDlc);
        LinearLayout sheet = sheet("已购买的面具 DLC");
        List<TextView> choices = new ArrayList<>();
        for (Compendium.DlcPack pack : data.dlcPacks()) {
            TextView button = choice(pack.nameZh);
            button.setTag(pack.id);
            button.setMinHeight(Ui.dp(c, 56));
            button.setOnClickListener(v -> {
                if (!pending.remove(pack.id)) pending.add(pack.id);
                SheetChrome.selectChoice(button, pending.contains(pack.id));
            });
            SheetChrome.selectChoice(button, pending.contains(pack.id));
            choices.add(button);
            sheet.addView(button, top(10));
        }
        LinearLayout actions = Ui.row(c);
        TextView none = Ui.secondary(c, "仅游戏本体");
        none.setOnClickListener(v -> {
            pending.clear();
            for (TextView choice : choices) SheetChrome.selectChoice(choice, false);
        });
        actions.addView(none, Ui.weight(1));
        TextView save = Ui.primary(c, "保存");
        save.setOnClickListener(v -> {
            prefs.setOwnedDlc(pending);
            ownedDlc = new HashSet<>(pending);
            calculator = new FusionCalculator(data, ownedDlc);
            if (first != null && !calculator.isAvailable(first)) first = null;
            if (second != null && !calculator.isAvailable(second)) second = null;
            if (target != null && !calculator.isAvailable(target)) target = null;
            host.dismissSheet();
            refreshList(true);
            renderFusion();
            if (onSaved != null) onSaved.run();
        });
        actions.addView(save, weightedGap());
        sheet.addView(actions, top(18));
        host.showSheet(sheet);
    }

    private void openDetail(Compendium.Persona persona) {
        LinearLayout content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 12, Ui.GUTTER, 28);
        LinearLayout hero = Ui.column(c);
        hero.setBackground(Ui.heroCard(c));
        Ui.pad(hero, 16, 16, 16, 16);
        LinearLayout heading = Ui.row(c);
        heading.addView(Ui.title(c, persona.nameZh), Ui.weight(1));
        TextView favorite = Ui.chip(c, favoriteLabel(persona), favoriteIds.contains(persona.id));
        favorite.setTag("persona_favorite_" + persona.id);
        favorite.setOnClickListener(v -> {
            toggleFavorite(persona);
            favorite.setText(favoriteLabel(persona));
            Ui.setChipSelected(favorite, favoriteIds.contains(persona.id));
        });
        heading.addView(favorite, Ui.wrap());
        hero.addView(heading);
        hero.addView(Ui.muted(c, persona.nameEn), top(4));
        hero.addView(Ui.mixed(c, arcanaName(persona.arcana) + " · Lv." + persona.level, 16,
            Ui.col(c, R.color.p3r_cyan_300), true), top(10));
        if (persona.isDlc()) hero.addView(Ui.caption(c, dlcName(persona.dlc)), top(8));
        if (!persona.unlockTextZh.isEmpty()) hero.addView(Ui.body(c, persona.unlockTextZh), top(10));
        content.addView(hero);
        content.addView(Ui.sectionHeader(c, "属性", null), top(14));
        String[] labels = {"力", "魔", "耐", "速", "运"};
        LinearLayout stats = Ui.row(c);
        for (int i = 0; i < labels.length; i++) {
            LinearLayout stat = Ui.column(c);
            TextView label = Ui.caption(c, labels[i]);
            label.setGravity(Gravity.CENTER);
            stat.addView(label);
            TextView value = Ui.numeral(c, i < persona.stats.length ? Integer.toString(persona.stats[i]) : "—",
                22, Ui.col(c, R.color.p3r_white));
            value.setGravity(Gravity.CENTER);
            stat.addView(value, top(4));
            stats.addView(stat, Ui.weight(1));
        }
        content.addView(stats);
        content.addView(Ui.sectionHeader(c, "弱点与耐性", null), top(14));
        List<String> elements = data.elements();
        for (int start = 0; start < elements.size(); start += 5) {
            LinearLayout row = Ui.row(c);
            for (int i = start; i < Math.min(start + 5, elements.size()); i++) {
                String element = elements.get(i);
                char resist = i < persona.resists.length() ? persona.resists.charAt(i) : '-';
                LinearLayout cell = Ui.column(c);
                cell.setBackground(Ui.card(c));
                Ui.pad(cell, 2, 8, 2, 8);
                TextView elementLabel = Ui.caption(c, elementName(element));
                elementLabel.setGravity(Gravity.CENTER);
                cell.addView(elementLabel);
                TextView status = Ui.text(c, data.resistanceLabel(resist), 17,
                    Ui.col(c, resist == 'w' ? R.color.p3r_cyan_300 : R.color.p3r_white), true);
                status.setGravity(Gravity.CENTER);
                cell.addView(status, top(3));
                LinearLayout.LayoutParams p = Ui.weight(1);
                if (i > start) p.leftMargin = Ui.dp(c, 5);
                row.addView(cell, p);
            }
            content.addView(row, top(5));
        }
        content.addView(Ui.sectionHeader(c, "可学技能", null), top(14));
        for (Compendium.LearnedSkill learned : persona.skills) {
            Compendium.Skill skill = data.skill(learned.skillId);
            if (skill != null) content.addView(skillCard(skill, learned.level <= 0 ? "初始" : "Lv." + learned.level), top(7));
        }
        if (!persona.theurgySkillIds.isEmpty()) {
            content.addView(Ui.sectionHeader(c, "神通法", null), top(14));
            for (String id : persona.theurgySkillIds) {
                Compendium.Skill skill = data.skill(id);
                if (skill != null) content.addView(skillCard(skill, ""), top(7));
            }
        }
        TextView recipes = Ui.primary(c, "查看合成配方");
        recipes.setTag("persona_recipes_" + persona.id);
        recipes.setOnClickListener(v -> openRecipes(persona));
        content.addView(recipes, top(20));
        TextView use = Ui.secondary(c, "作为合成素材");
        use.setOnClickListener(v -> openMaterialFusion(persona));
        content.addView(use, top(10));
        host.pushFillingPage(persona.nameZh, scroll(content));
    }

    private View skillCard(Compendium.Skill skill, String learned) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 12, 10, 12, 10);
        LinearLayout row = Ui.row(c);
        row.addView(Ui.headline(c, skill.nameZh), Ui.weight(1));
        if (!learned.isEmpty()) row.addView(Ui.caption(c, learned), Ui.wrap());
        card.addView(row);
        card.addView(Ui.caption(c, skill.nameEn), top(2));
        String cost = skill.costLabel();
        String type = elementName(skill.element);
        if (!skill.targetZh.isEmpty()) type += (type.isEmpty() ? "" : " · ") + skill.targetZh;
        if (!cost.isEmpty()) type += " · " + cost;
        if (!type.isEmpty()) card.addView(Ui.caption(c, type), top(5));
        if (!skill.effectZh.isEmpty()) card.addView(Ui.body(c, skill.effectZh), top(5));
        return card;
    }

    private void renderFusion() {
        if (fusionContent == null) return;
        fusionContent.removeAllViews();
        fusionContent.addView(Ui.sectionHeader(c, "两体合成", null));
        addForward(fusionContent, true);
        fusionContent.addView(Ui.sectionHeader(c, "查找配方", null), top(20));
        TextView targetChoice = Ui.secondary(c, target == null ? "选择想合成的人格面具" : target.nameZh + " · Lv." + target.level);
        targetChoice.setTag("fusion_target");
        targetChoice.setOnClickListener(v -> pickPersona("选择合成目标", value -> {
            target = value;
            renderFusion();
        }));
        fusionContent.addView(targetChoice, top(4));
        if (target != null) {
            LinearLayout recipes = Ui.column(c);
            fusionContent.addView(recipes, top(10));
            loadRecipes(target, recipes);
        }
    }

    private void addForward(LinearLayout content, boolean rootSelection) {
        LinearLayout choices = Ui.row(c);
        TextView a = Ui.secondary(c, first == null ? "选择素材 1" : first.nameZh + "\nLv." + first.level);
        TextView b = Ui.secondary(c, second == null ? "选择素材 2" : second.nameZh + "\nLv." + second.level);
        a.setTag("fusion_material_1");
        b.setTag("fusion_material_2");
        a.setOnClickListener(v -> pickPersona("选择素材 1", value -> {
            first = value;
            renderFusion();
            if (!rootSelection) rebuildMaterialPage(content);
        }));
        b.setOnClickListener(v -> pickPersona("选择素材 2", value -> {
            second = value;
            renderFusion();
            if (!rootSelection) rebuildMaterialPage(content);
        }));
        choices.addView(a, Ui.weight(1));
        choices.addView(b, weightedGap());
        content.addView(choices, top(6));
        if (first != null && second != null) {
            FusionCalculator.Recipe result = calculator.fuse(first.id, second.id);
            if (result != null) {
                content.addView(Ui.caption(c, "合成结果"), top(14));
                content.addView(personaRow(result.result, false, this::openDetail), top(6));
                if (result.special) content.addView(Ui.caption(c, "特殊合成"), top(5));
                if (!result.result.unlockTextZh.isEmpty()) content.addView(Ui.body(c, result.result.unlockTextZh), top(8));
            } else {
                content.addView(Ui.muted(c, first.id.equals(second.id) ? "请选择两个不同的人格面具" : "这两个素材无法合成"), top(14));
            }
        }
    }

    private void openMaterialFusion(Compendium.Persona persona) {
        if (!calculator.isAvailable(persona)) {
            openDlc();
            return;
        }
        first = persona;
        second = null;
        LinearLayout content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 12, Ui.GUTTER, 28);
        rebuildMaterialPage(content);
        host.pushFillingPage("两体合成", scroll(content));
    }

    private void rebuildMaterialPage(LinearLayout content) {
        content.removeAllViews();
        addForward(content, false);
    }

    private void openRecipes(Compendium.Persona persona) {
        LinearLayout content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 12, Ui.GUTTER, 28);
        content.addView(personaRow(persona, false, this::openDetail));
        LinearLayout recipes = Ui.column(c);
        content.addView(recipes, top(12));
        loadRecipes(persona, recipes);
        host.pushFillingPage("合成配方", scroll(content));
    }

    private void loadRecipes(Compendium.Persona persona, LinearLayout container) {
        if (!calculator.isAvailable(persona)) {
            container.addView(Ui.body(c, "先选择拥有的 " + dlcName(persona.dlc)));
            TextView select = Ui.secondary(c, "选择 DLC");
            select.setOnClickListener(v -> openDlc(() -> {
                container.removeAllViews();
                loadRecipes(persona, container);
            }));
            container.addView(select, top(12));
            return;
        }
        TextView loading = Ui.muted(c, "正在查找配方…");
        container.addView(loading);
        FusionCalculator currentCalculator = calculator;
        Async.run(host.activity(), () -> currentCalculator.recipesFor(persona.id), recipes -> {
            container.removeAllViews();
            if (currentCalculator != calculator) {
                loadRecipes(persona, container);
                return;
            }
            if (recipes.isEmpty()) {
                container.addView(Ui.body(c, persona.unlockTextZh.isEmpty() ? "没有合成配方" : persona.unlockTextZh));
                return;
            }
            container.addView(Ui.sectionHeader(c, "合成配方", recipes.size() + " 组"));
            appendRecipes(container, recipes, 0);
        }, error -> {
            container.removeAllViews();
            TextView retry = Ui.secondary(c, "重新查找配方");
            retry.setOnClickListener(v -> { container.removeAllViews(); loadRecipes(persona, container); });
            container.addView(retry);
        });
    }

    private void appendRecipes(LinearLayout container, List<FusionCalculator.Recipe> recipes, int from) {
        int end = Math.min(from + RECIPE_PAGE, recipes.size());
        for (int i = from; i < end; i++) {
            FusionCalculator.Recipe recipe = recipes.get(i);
            LinearLayout card = Ui.column(c);
            card.setBackground(Ui.card(c));
            Ui.pad(card, 12, 8, 12, 8);
            if (recipe.special) card.addView(Ui.mixed(c, "特殊合成", 12, Ui.col(c, R.color.p3r_cyan_300), true));
            for (Compendium.Persona ingredient : recipe.ingredients) {
                TextView material = Ui.quiet(c, ingredient.nameZh + " · " + arcanaName(ingredient.arcana) + " Lv." + ingredient.level);
                material.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                material.setMaxLines(2);
                Ui.pad(material, 4, 6, 4, 6);
                material.setOnClickListener(v -> openDetail(ingredient));
                card.addView(material);
            }
            container.addView(card, top(8));
        }
        if (end < recipes.size()) {
            TextView more = Ui.secondary(c, "更多配方（还剩 " + (recipes.size() - end) + " 组）");
            more.setOnClickListener(v -> {
                container.removeView(more);
                appendRecipes(container, recipes, end);
            });
            container.addView(more, top(12));
        }
    }

    private void pickPersona(String title, Consumer<Compendium.Persona> onSelected) {
        LinearLayout sheet = sheet(title);
        EditText search = search("名称、英文名或技能", "fusion_picker_search");
        sheet.addView(search, top(10));
        ListView results = personaList();
        results.setTag("fusion_picker_list");
        Ui.pad(results, 0, 8, 0, 8);
        PersonaAdapter choices = new PersonaAdapter(false, value -> {
            hideKeyboard();
            host.dismissSheet();
            onSelected.accept(value);
        });
        results.setAdapter(choices);
        TextView noMatch = Ui.caption(c, "没有匹配结果");
        noMatch.setGravity(Gravity.CENTER);
        sheet.addView(noMatch, top(8));
        Runnable filter = () -> {
            List<Compendium.Persona> matches = CompendiumQuery.find(data, calculator, search.getText().toString(),
                null, 1, 99, false, Collections.emptySet(), false, CompendiumQuery.LEVEL_ASC);
            choices.setItems(matches);
            noMatch.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
            results.setSelection(0);
        };
        search.addTextChangedListener(watcher(text -> filter.run()));
        int availableHeight = c.getResources().getDisplayMetrics().heightPixels / 2;
        sheet.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Math.min(Ui.dp(c, 340), Math.max(Ui.dp(c, 180), availableHeight))));
        filter.run();
        host.showSheet(sheet);
    }

    private View personaRow(Compendium.Persona persona, boolean favoriteControl,
                            Consumer<Compendium.Persona> onClick) {
        LinearLayout card = Ui.row(c);
        card.setTag("persona_" + persona.id);
        card.setBackground(Ui.pressableCard(c));
        card.setClickable(true);
        card.setFocusable(true);
        Ui.pad(card, 12, 10, favoriteControl ? 4 : 12, 10);
        LinearLayout words = Ui.column(c);
        words.addView(Ui.headline(c, persona.nameZh));
        words.addView(Ui.caption(c, persona.nameEn), top(2));
        String subtitle = arcanaName(persona.arcana) + " · Lv." + persona.level;
        if (persona.isDlc()) subtitle += " · " + (calculator.isAvailable(persona) ? "DLC" : "未购买 DLC");
        words.addView(Ui.mixed(c, subtitle, 12, Ui.col(c, R.color.p3r_cyan_300), false), top(6));
        card.addView(words, Ui.weight(1));
        if (favoriteControl) {
            boolean selected = favoriteIds.contains(persona.id);
            TextView favorite = Ui.quiet(c, selected ? "★" : "☆");
            favorite.setTextSize(24);
            favorite.setContentDescription((selected ? "取消收藏 " : "收藏 ") + persona.nameZh);
            favorite.setTag("favorite_" + persona.id);
            Ui.pad(favorite, 10, 4, 10, 4);
            favorite.setOnClickListener(v -> toggleFavorite(persona));
            card.addView(favorite, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 52)));
        }
        card.setOnClickListener(v -> onClick.accept(persona));
        return card;
    }

    private void toggleFavorite(Compendium.Persona persona) {
        boolean favorite = !favoriteIds.contains(persona.id);
        prefs.setFavorite(persona.id, favorite);
        if (favorite) favoriteIds.add(persona.id); else favoriteIds.remove(persona.id);
        refreshList(false);
    }

    private String favoriteLabel(Compendium.Persona persona) {
        return favoriteIds.contains(persona.id) ? "★ 已收藏" : "☆ 收藏";
    }

    private final class PersonaAdapter extends BaseAdapter {
        private final boolean favorites;
        private final Consumer<Compendium.Persona> onClick;
        private List<Compendium.Persona> items = Collections.emptyList();
        PersonaAdapter(boolean favorites, Consumer<Compendium.Persona> onClick) {
            this.favorites = favorites;
            this.onClick = onClick;
        }
        void setItems(List<Compendium.Persona> values) {
            items = values;
            notifyDataSetChanged();
        }
        @Override public int getCount() { return items.size(); }
        @Override public Compendium.Persona getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            return personaRow(getItem(position), favorites, onClick);
        }
    }

    private ListView personaList() {
        ListView result = new ListView(c);
        result.setDivider(new android.graphics.drawable.ColorDrawable(0));
        result.setDividerHeight(Ui.dp(c, 8));
        result.setSelector(android.R.color.transparent);
        result.setClipToPadding(false);
        result.setVerticalScrollBarEnabled(false);
        Ui.pad(result, Ui.GUTTER, 6, Ui.GUTTER, 24);
        return result;
    }

    private LinearLayout sheet(String title) {
        LinearLayout result = Ui.column(c);
        Ui.pad(result, 20, 12, 20, 20);
        result.addView(SheetChrome.title(c, title));
        return result;
    }

    private ScrollView scroll(View content) {
        ScrollView result = new ScrollView(c);
        result.setFillViewport(true);
        result.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return result;
    }

    private EditText search(String hint, String tag) {
        EditText result = new EditText(c);
        result.setTag(tag);
        result.setHint(hint);
        result.setSingleLine(true);
        result.setInputType(InputType.TYPE_CLASS_TEXT);
        result.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        result.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();
            root.requestFocus();
            return true;
        });
        result.setTextColor(Ui.col(c, R.color.p3r_white));
        result.setHintTextColor(Ui.col(c, R.color.p3r_ink_faint));
        result.setTextSize(14);
        result.setBackground(new SlantedPanelDrawable().fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170))
            .stroke(Ui.col(c, R.color.p3r_line), Ui.dp(c, 1)));
        result.setMinHeight(Ui.dp(c, Ui.TOUCH));
        Ui.pad(result, 12, 8, 12, 8);
        return result;
    }

    private EditText number(int value, String tag) {
        EditText result = search("等级", tag);
        result.setInputType(InputType.TYPE_CLASS_NUMBER);
        result.setText(Integer.toString(value));
        result.setSelectAllOnFocus(true);
        return result;
    }

    private static int boundedLevel(EditText field, int fallback) {
        try { return Math.max(1, Math.min(99, Integer.parseInt(field.getText().toString().trim()))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private TextView choice(String text) {
        TextView result = Ui.chip(c, text, false);
        result.setTextSize(13);
        result.setMaxLines(2);
        Ui.pad(result, 5, 6, 5, 6);
        return result;
    }

    private void grid(LinearLayout parent, List<TextView> choices, int columns) {
        for (int start = 0; start < choices.size(); start += columns) {
            LinearLayout row = Ui.row(c);
            for (int i = start; i < start + columns; i++) {
                LinearLayout.LayoutParams params = Ui.weight(1);
                if (i > start) params.leftMargin = Ui.dp(c, 6);
                row.addView(i < choices.size() ? choices.get(i) : new View(c), params);
            }
            parent.addView(row, top(6));
        }
    }

    private String arcanaName(String id) {
        Compendium.Arcana value = data.arcana(id);
        return value == null ? id : value.nameZh;
    }

    private String dlcName(String id) {
        for (Compendium.DlcPack pack : data.dlcPacks()) if (pack.id.equals(id)) return pack.nameZh;
        return "DLC";
    }

    static String elementName(String element) {
        switch (element) {
            case "sla": return "斩击";
            case "str": return "打击";
            case "pie": return "贯穿";
            case "fir": return "火炎";
            case "ice": return "冰冻";
            case "ele": return "电击";
            case "win": return "疾风";
            case "lig": return "光";
            case "dar": return "暗";
            case "alm": return "万能";
            case "rec": case "heal": return "回复";
            case "sup": case "support": return "辅助";
            case "pas": case "passive": return "被动";
            case "ail": return "异常";
            default: return "";
        }
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        View focus = host.activity().getCurrentFocus();
        if (keyboard != null && focus != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams result = Ui.matchWrap();
        result.topMargin = Ui.dp(c, dp);
        return result;
    }

    private LinearLayout.LayoutParams weightedGap() {
        LinearLayout.LayoutParams result = Ui.weight(1);
        result.leftMargin = Ui.dp(c, 8);
        return result;
    }

    private static void append(StringBuilder value, String suffix) {
        if (value.length() > 0) value.append(" · ");
        value.append(suffix);
    }

    private static TextWatcher watcher(Consumer<String> onChanged) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                onChanged.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        };
    }
}
