package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import app.p3r.dayplanner.v3.compendium.FusionBasket;

/** One offline entry for Persona lookup, favorites and both directions of fusion. */
public final class CompendiumPage {
    private static volatile Compendium cached;
    private static final int RECIPE_PAGE = 30;
    private static final String[] SORT_LABELS = {"等级 ↑", "等级 ↓", "阿尔卡那 ↑", "名称", "阿尔卡那 ↓"};
    private final HomeHost host;
    private final Context c;
    private final CompendiumPrefs prefs;
    private final LinearLayout root;
    private Compendium data;
    private FusionCalculator calculator;
    private LinearLayout listPane;
    private LinearLayout fusionPane;
    private FrameLayout paneHost;
    private TextView listSegment;
    private TextView fusionSegment;
    private TextView basketSegment;
    private FusionBasketPage basketPage;
    private View basketPane;
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
    private Compendium.Persona browseGoal;
    private boolean fusionSelected;
    private int activeSegment;
    private boolean favoritesOnly;
    private boolean showUnowned;
    private String query = "";
    private String arcana;
    private int minLevel = 1;
    private int maxLevel = 99;
    private int order = CompendiumQuery.LEVEL_ASC;
    private Set<String> favoriteIds;
    private Set<String> ownedDlc;
    private Set<String> ownedPersonas;
    private int segmentGeneration;
    private EditText activePickerSearch;

    public static View create(HomeHost host) {
        return new CompendiumPage(host).root;
    }

    /** Reconciles restored tool settings while retaining the current search and workspace. */
    public static void refresh(View view) {
        if (view == null) return;
        Object controller = view.getTag(R.id.compendium_controller);
        if (controller instanceof CompendiumPage) ((CompendiumPage) controller).refreshStoredChoices();
    }

    private CompendiumPage(HomeHost host) {
        this.host = host;
        c = host.activity();
        prefs = new CompendiumPrefs(c);
        favoriteIds = new HashSet<>(prefs.favorites());
        ownedDlc = new HashSet<>(prefs.ownedDlc());
        ownedPersonas = new HashSet<>(prefs.ownedPersonas());
        root = Ui.column(c);
        root.setTag("compendium_page");
        root.setTag(R.id.compendium_controller, this);
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
        basketSegment = Ui.chip(c, "合成篮", false);
        basketSegment.setTag("basket_tab");
        listSegment.setOnClickListener(v -> selectSegment(false));
        fusionSegment.setOnClickListener(v -> selectSegment(true));
        basketSegment.setOnClickListener(v -> selectSegment(2));
        segments.addView(listSegment, Ui.weight(1));
        segments.addView(fusionSegment, weightedGap());
        segments.addView(basketSegment, weightedGap());
        for (TextView segment : new TextView[] {listSegment, fusionSegment, basketSegment}) {
            segment.setTextSize(13);
            Ui.pad(segment, 6, 6, 6, 6);
        }
        TextView dlc = CompendiumControls.toolButton(c, "DLC", false);
        addToolIcon(dlc, R.drawable.ui_ic_mask);
        Ui.pad(dlc, 7, 8, 7, 8);
        dlc.setTag("compendium_dlc");
        dlc.setContentDescription("选择已购买的面具 DLC");
        dlc.setOnClickListener(v -> openDlc());
        LinearLayout.LayoutParams dlcGap = Ui.wrap();
        dlcGap.leftMargin = Ui.dp(c, 8);
        segments.addView(dlc, dlcGap);
        header.addView(segments);
        root.addView(header);
        paneHost = new FrameLayout(c);
        root.addView(paneHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        buildList();
        fusionPane = Ui.column(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        fusionContent = Ui.column(c);
        Ui.pad(fusionContent, Ui.GUTTER, 4, Ui.GUTTER, 28);
        scroll.addView(fusionContent);
        fusionPane.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        paneHost.addView(fusionPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        basketPage = new FusionBasketPage(host, data, calculator, prefs, new FusionBasketPage.Actions() {
            @Override public void openPersona(String id) {
                Compendium.Persona persona = data.persona(id);
                if (persona != null) openDetail(persona);
            }
            @Override public void chooseRecipe(String targetId, Consumer<FusionCalculator.Recipe> chosen) {
                Compendium.Persona persona = data.persona(targetId);
                if (persona != null) openRecipeSelector(persona, chosen);
            }
            @Override public void onChanged() { refreshChoices(); }
            @Override public void browseRecipes() { selectSegment(1); }
        });
        basketPane = basketPage.view();
        paneHost.addView(basketPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        refreshList(true);
        renderFusion();
        refreshBasketBadge();
        selectSegment(activeSegment);
    }

    private void buildList() {
        listPane = Ui.column(c);
        LinearLayout controls = Ui.column(c);
        Ui.pad(controls, Ui.GUTTER, 0, Ui.GUTTER, 4);
        EditText search = search("名称、英文名或技能", "compendium_search");
        search.addTextChangedListener(watcher(text -> { query = text; refreshList(true); }));
        controls.addView(searchBox(search));
        LinearLayout filters = Ui.row(c);
        allButton = CompendiumControls.scopeButton(c, "全部", true);
        favoritesButton = CompendiumControls.scopeButton(c, "收藏", false);
        filterButton = CompendiumControls.toolButton(c, "筛选", false);
        addToolIcon(filterButton, R.drawable.ui_ic_settings);
        allButton.setOnClickListener(v -> { favoritesOnly = false; refreshList(true); });
        favoritesButton.setOnClickListener(v -> { favoritesOnly = true; refreshList(true); });
        filterButton.setOnClickListener(v -> openFilters());
        LinearLayout scope = Ui.row(c);
        scope.addView(allButton, Ui.weight(1));
        scope.addView(favoritesButton, Ui.weight(1));
        filters.addView(scope, Ui.weight(1));
        LinearLayout.LayoutParams toolGap = Ui.wrap();
        toolGap.leftMargin = Ui.dp(c, 12);
        filters.addView(filterButton, toolGap);
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
        paneHost.addView(listPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void selectSegment(boolean fusion) {
        selectSegment(fusion ? 1 : 0);
    }

    private void selectSegment(int segment) {
        boolean changed = activeSegment != segment;
        View[] panes = {listPane, fusionPane, basketPane};
        View outgoing = panes[activeSegment];
        View incoming = panes[segment];
        int previous = activeSegment;
        activeSegment = segment;
        fusionSelected = segment == 1;
        Ui.setChipSelected(listSegment, segment == 0);
        Ui.setChipSelected(fusionSegment, segment == 1);
        Ui.setChipSelected(basketSegment, segment == 2);
        if (segment == 2) basketPage.refresh();
        for (View pane : panes) {
            pane.animate().cancel();
            if (pane != incoming && pane != outgoing) pane.setVisibility(View.GONE);
        }
        int generation = ++segmentGeneration;
        incoming.setVisibility(View.VISIBLE);
        incoming.bringToFront();
        incoming.setAlpha(1f);
        incoming.setTranslationX(0);
        if (changed && Ui.animationsEnabled() && root.isLaidOut()) {
            int direction = segment > previous ? 1 : -1;
            incoming.setAlpha(0f);
            incoming.setTranslationX(Ui.dp(c, 14) * direction);
            outgoing.animate().alpha(0f).translationX(-Ui.dp(c, 8) * direction).setDuration(130)
                .withEndAction(() -> {
                    if (generation == segmentGeneration) outgoing.setVisibility(View.GONE);
                }).start();
            incoming.animate().alpha(1f).translationX(0).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            if (outgoing != incoming) outgoing.setVisibility(View.GONE);
            outgoing.setAlpha(1f);
            outgoing.setTranslationX(0);
        }
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
        CompendiumControls.setScopeSelected(allButton, !favoritesOnly);
        CompendiumControls.setScopeSelected(favoritesButton, favoritesOnly);
        boolean filtered = arcana != null || minLevel > 1 || maxLevel < 99 || showUnowned
            || order != CompendiumQuery.LEVEL_ASC;
        CompendiumControls.setToolActive(filterButton, filtered);
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
        hideKeyboard();
        String[] pendingArcana = {arcana};
        int[] pendingOrder = {order};
        boolean[] pendingUnowned = {showUnowned};
        LinearLayout sheet = sheet("筛选图鉴");
        LinearLayout body = Ui.column(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(body);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(c, sheetScrollHeight(400, 310))));
        body.addView(Ui.sectionHeader(c, "等级", null), top(12));
        LinearLayout levels = Ui.row(c);
        EditText min = number(minLevel, "compendium_min_level");
        EditText max = number(maxLevel, "compendium_max_level");
        levels.addView(min, Ui.weight(1));
        TextView dash = Ui.body(c, " ～ ");
        levels.addView(dash, Ui.wrap());
        levels.addView(max, Ui.weight(1));
        body.addView(levels);
        body.addView(Ui.sectionHeader(c, "排序", null), top(12));
        TextView byLevel = choice("等级");
        byLevel.setTag("compendium_sort_level");
        TextView byArcana = choice("阿尔卡那");
        byArcana.setTag("compendium_sort_arcana");
        TextView direction = choice("升序 ↑");
        direction.setTag("compendium_sort_direction");
        List<TextView> arcanas = new ArrayList<>();
        TextView unowned = choice("显示未购买 DLC");
        Runnable paint = () -> {
            CompendiumControls.setToolActive(byLevel, !sortByArcana(pendingOrder[0]));
            CompendiumControls.setToolActive(byArcana, sortByArcana(pendingOrder[0]));
            direction.setText(sortDescending(pendingOrder[0]) ? "降序 ↓" : "升序 ↑");
            direction.setContentDescription(sortDescending(pendingOrder[0]) ? "降序，切换为升序" : "升序，切换为降序");
            for (TextView value : arcanas) CompendiumControls.setToolActive(value,
                pendingArcana[0] == null ? value.getTag() == null : pendingArcana[0].equals(value.getTag()));
            CompendiumControls.setToolActive(unowned, pendingUnowned[0]);
        };
        byLevel.setOnClickListener(v -> {
            pendingOrder[0] = sortDescending(pendingOrder[0]) ? CompendiumQuery.LEVEL_DESC : CompendiumQuery.LEVEL_ASC;
            paint.run();
        });
        byArcana.setOnClickListener(v -> {
            pendingOrder[0] = sortDescending(pendingOrder[0]) ? CompendiumQuery.ARCANA_DESC : CompendiumQuery.ARCANA;
            paint.run();
        });
        direction.setOnClickListener(v -> {
            boolean descending = !sortDescending(pendingOrder[0]);
            pendingOrder[0] = sortByArcana(pendingOrder[0])
                ? descending ? CompendiumQuery.ARCANA_DESC : CompendiumQuery.ARCANA
                : descending ? CompendiumQuery.LEVEL_DESC : CompendiumQuery.LEVEL_ASC;
            paint.run();
        });
        LinearLayout sortRow = Ui.row(c);
        LinearLayout modes = Ui.row(c);
        modes.addView(byLevel, Ui.weight(1));
        modes.addView(byArcana, weightedGap());
        sortRow.addView(modes, Ui.weight(1));
        LinearLayout.LayoutParams directionGap = new LinearLayout.LayoutParams(Ui.dp(c, 86), ViewGroup.LayoutParams.WRAP_CONTENT);
        directionGap.leftMargin = Ui.dp(c, 12);
        sortRow.addView(direction, directionGap);
        body.addView(sortRow, top(6));
        body.addView(Ui.sectionHeader(c, "阿尔卡那", null), top(12));
        TextView any = choice("全部");
        any.setOnClickListener(v -> { pendingArcana[0] = null; paint.run(); });
        arcanas.add(any);
        for (Compendium.Arcana item : data.arcana()) {
            TextView value = choice(item.nameZh);
            value.setTag(item.id);
            value.setOnClickListener(v -> { pendingArcana[0] = item.id; paint.run(); });
            arcanas.add(value);
        }
        grid(body, arcanas, 3);
        unowned.setOnClickListener(v -> { pendingUnowned[0] = !pendingUnowned[0]; paint.run(); });
        body.addView(unowned, top(12));
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
        hideKeyboard();
        Set<String> pending = new HashSet<>(ownedDlc);
        LinearLayout sheet = sheet("已购买的面具 DLC");
        List<View> choices = new ArrayList<>();
        List<TextView> checks = new ArrayList<>();
        Runnable paint = () -> {
            for (int i = 0; i < choices.size(); i++) {
                boolean selected = pending.contains(data.dlcPacks().get(i).id);
                choices.get(i).setBackground(selected ? Ui.heroCard(c) : Ui.pressableCard(c));
                choices.get(i).setSelected(selected);
                checks.get(i).setText(selected ? "✓" : "□");
                checks.get(i).setTextColor(Ui.col(c, selected ? R.color.p3r_cyan_300 : R.color.p3r_ink_muted));
            }
        };
        for (Compendium.DlcPack pack : data.dlcPacks()) {
            LinearLayout card = Ui.row(c);
            card.setTag("dlc_pack_" + pack.id);
            card.setContentDescription(pack.nameZh);
            card.setClickable(true);
            card.setFocusable(true);
            Ui.pad(card, 14, 14, 12, 14);
            LinearLayout words = Ui.column(c);
            words.addView(Ui.mixed(c, pack.nameZh, 16, Ui.col(c, R.color.p3r_white), true));
            int count = 0;
            List<String> examples = new ArrayList<>();
            for (Compendium.Persona persona : data.personas()) if (pack.id.equals(persona.dlc)) {
                count++;
                if (examples.size() < 2) examples.add(persona.nameZh);
            }
            words.addView(Ui.caption(c, count + " 个 · " + String.join("、", examples)), top(5));
            card.addView(words, Ui.weight(1));
            TextView check = Ui.text(c, "", 24, Ui.col(c, R.color.p3r_cyan_300), true);
            check.setGravity(Gravity.CENTER);
            card.addView(check, new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 44)));
            card.setOnClickListener(v -> {
                if (!pending.remove(pack.id)) pending.add(pack.id);
                paint.run();
            });
            choices.add(card);
            checks.add(check);
            sheet.addView(card, top(10));
        }
        paint.run();
        LinearLayout actions = Ui.row(c);
        TextView none = Ui.secondary(c, "仅游戏本体");
        none.setOnClickListener(v -> {
            pending.clear();
            paint.run();
        });
        actions.addView(none, Ui.weight(1));
        TextView save = Ui.primary(c, "保存");
        save.setOnClickListener(v -> {
            prefs.setOwnedDlc(pending);
            ownedDlc = new HashSet<>(pending);
            calculator = new FusionCalculator(data, ownedDlc);
            basketPage.setCalculator(calculator);
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
        openDetail(persona, false);
    }

    private void openDetail(Compendium.Persona persona, boolean replace) {
        hideKeyboard();
        if (!replace) browseGoal = null;
        LinearLayout content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 12, Ui.GUTTER, 28);
        LinearLayout hero = Ui.column(c);
        hero.setBackground(Ui.heroCard(c));
        Ui.pad(hero, 16, 16, 16, 16);
        LinearLayout heading = Ui.row(c);
        heading.addView(Ui.title(c, persona.nameZh), Ui.weight(1));
        TextView favorite = CompendiumControls.toolButton(c, favoriteLabel(persona), favoriteIds.contains(persona.id));
        favorite.setTag("persona_favorite_" + persona.id);
        favorite.setOnClickListener(v -> {
            toggleFavorite(persona);
            favorite.setText(favoriteLabel(persona));
            CompendiumControls.setToolActive(favorite, favoriteIds.contains(persona.id));
        });
        heading.addView(favorite, Ui.wrap());
        hero.addView(heading);
        hero.addView(Ui.muted(c, persona.nameEn), top(4));
        LinearLayout metadata = Ui.row(c);
        metadata.addView(Ui.mixed(c, arcanaName(persona.arcana) + " · Lv." + persona.level, 16,
            Ui.col(c, R.color.p3r_cyan_300), true), Ui.weight(1));
        OwnedToggle owned = new OwnedToggle(persona, true);
        owned.setTag("persona_detail_owned_" + persona.id);
        metadata.addView(owned, Ui.wrap());
        hero.addView(metadata, top(10));
        if (persona.isDlc()) hero.addView(Ui.caption(c, dlcName(persona.dlc)), top(8));
        if (!persona.unlockTextZh.isEmpty()) hero.addView(Ui.body(c, persona.unlockTextZh), top(10));
        content.addView(hero);
        LinearLayout actions = Ui.row(c);
        TextView recipes = Ui.primary(c, "查配方");
        recipes.setTag("persona_recipes_" + persona.id);
        recipes.setOnClickListener(v -> openRecipes(persona, true));
        actions.addView(recipes, Ui.weight(1));
        TextView use = Ui.secondary(c, "作为素材");
        use.setTag("persona_material_" + persona.id);
        use.setOnClickListener(v -> openMaterialFusion(persona));
        actions.addView(use, weightedGap());
        content.addView(actions, top(12));
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
        if (replace) host.replaceFillingPage(persona.nameZh, scroll(content));
        else host.pushFillingPage(persona.nameZh, scroll(content));
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
        LinearLayout choices = Ui.row(c);
        choices.addView(materialSlot(first, 1, () -> chooseFirst()), Ui.weight(1));
        TextView plus = Ui.numeral(c, "+", 24, Ui.col(c, R.color.p3r_cyan_300));
        plus.setGravity(Gravity.CENTER);
        choices.addView(plus, new LinearLayout.LayoutParams(Ui.dp(c, 26), ViewGroup.LayoutParams.WRAP_CONTENT));
        choices.addView(materialSlot(second, 2, () -> chooseSecond()), Ui.weight(1));
        fusionContent.addView(choices, top(6));
        if (first != null && second != null) {
            FusionCalculator.Recipe result = calculator.fuse(first.id, second.id);
            if (result != null) {
                fusionContent.addView(Ui.sectionHeader(c, "合成结果", result.special ? "特殊合成" : null), top(12));
                fusionContent.addView(personaRow(result.result, false, this::openDetail), top(2));
                if (!result.result.unlockTextZh.isEmpty()) fusionContent.addView(Ui.body(c, result.result.unlockTextZh), top(8));
                LinearLayout actions = Ui.row(c);
                TextView use = Ui.primary(c, "接着合成");
                use.setTag("fusion_continue");
                use.setOnClickListener(v -> openMaterialFusion(result.result));
                actions.addView(use, Ui.weight(1));
                TextView recipes = Ui.secondary(c, "查配方");
                recipes.setTag("fusion_result_recipes");
                recipes.setOnClickListener(v -> {
                    browseGoal = null;
                    openRecipes(result.result, false);
                });
                actions.addView(recipes, weightedGap());
                fusionContent.addView(actions, top(12));
                TextView basket = Ui.quiet(c, "加入合成篮");
                basket.setTag("fusion_add_basket");
                basket.setOnClickListener(v -> addToBasket(result));
                fusionContent.addView(basket, top(4));
            } else {
                fusionContent.addView(Ui.muted(c, "这两个素材无法合成"), top(14));
            }
        }
        fusionContent.addView(Ui.sectionHeader(c, "查找配方", null), top(22));
        TextView targetChoice = Ui.secondary(c, target == null ? "选择合成目标" : target.nameZh + " · Lv." + target.level);
        targetChoice.setTag("fusion_target");
        targetChoice.setOnClickListener(v -> pickPersona("选择合成目标", null, null, value -> {
            target = value;
            browseGoal = value;
            renderFusion();
            openRecipes(value, false);
        }));
        fusionContent.addView(targetChoice, top(4));
        if (target != null) {
            TextView recipes = Ui.quiet(c, "查看 " + target.nameZh + " 的配方");
            recipes.setTag("fusion_target_recipes");
            recipes.setOnClickListener(v -> {
                browseGoal = target;
                openRecipes(target, false);
            });
            fusionContent.addView(recipes, top(4));
        }
    }

    private View materialSlot(Compendium.Persona persona, int index, Runnable onPick) {
        LinearLayout slot = Ui.column(c);
        slot.setTag("fusion_material_" + index);
        slot.setBackground(persona == null ? Ui.pressableCard(c) : Ui.heroCard(c));
        slot.setClickable(true);
        slot.setFocusable(true);
        slot.setMinimumHeight(Ui.dp(c, 124));
        Ui.pad(slot, 12, 10, 12, 10);
        LinearLayout heading = Ui.row(c);
        heading.addView(Ui.caption(c, "素材 " + index), Ui.weight(1));
        if (persona != null) {
            ImageView info = Ui.icon(c, R.drawable.ui_ic_info, Ui.col(c, R.color.p3r_cyan_300), 18);
            info.setScaleType(ImageView.ScaleType.CENTER);
            info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            info.setContentDescription(persona.nameZh + "详情");
            info.setTag("fusion_material_detail_" + index);
            info.setOnClickListener(v -> openDetail(persona));
            Ui.pad(info, 5, 4, 5, 4);
            heading.addView(info, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
        }
        slot.addView(heading);
        TextView name = Ui.headline(c, persona == null ? "＋ 选择面具" : persona.nameZh);
        name.setMaxLines(2);
        slot.addView(name, top(persona == null ? 14 : 5));
        if (persona != null) slot.addView(Ui.mixed(c, arcanaName(persona.arcana) + " · Lv." + persona.level,
            12, Ui.col(c, R.color.p3r_cyan_300), false), top(6));
        slot.setOnClickListener(v -> onPick.run());
        return slot;
    }

    private void chooseFirst() {
        pickPersona("选择素材 1", second == null ? null : second.id, second, second == null, value -> {
            first = value;
            renderFusion();
            if (second == null) chooseSecond();
        });
    }

    private void chooseSecond() {
        pickPersona("选择素材 2", first == null ? null : first.id, first, first == null, value -> {
            second = value;
            renderFusion();
            if (first == null) chooseFirst();
        });
    }

    private void openMaterialFusion(Compendium.Persona persona) {
        hideKeyboard();
        if (!calculator.isAvailable(persona)) {
            openDlc(() -> {
                if (calculator.isAvailable(persona)) openMaterialFusion(persona);
            });
            return;
        }
        first = persona;
        second = null;
        renderFusion();
        selectSegment(true);
        host.returnToPage(root);
        chooseSecond();
    }

    private void openRecipes(Compendium.Persona persona, boolean replace) {
        hideKeyboard();
        if (browseGoal == null) browseGoal = persona;
        LinearLayout content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 10, Ui.GUTTER, 28);
        if (!browseGoal.id.equals(persona.id)) {
            Compendium.Persona originalGoal = browseGoal;
            TextView goal = Ui.quiet(c, "返回 " + originalGoal.nameZh + " 的配方");
            goal.setTag("fusion_original_goal");
            goal.setOnClickListener(v -> openRecipes(originalGoal, true));
            content.addView(goal);
        }
        content.addView(personaRow(persona, false, value -> openDetail(value, true)), top(4));
        TextView use = Ui.primary(c, "接着合成");
        use.setTag("recipe_continue");
        use.setOnClickListener(v -> openMaterialFusion(persona));
        content.addView(use, top(12));
        LinearLayout recipes = Ui.column(c);
        content.addView(recipes, top(8));
        loadRecipes(persona, recipes);
        String title = persona.nameZh + " · 配方";
        if (replace) host.replaceFillingPage(title, scroll(content));
        else host.pushFillingPage(title, scroll(content));
    }

    private void openRecipeSelector(Compendium.Persona persona, Consumer<FusionCalculator.Recipe> chosen) {
        hideKeyboard();
        LinearLayout content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 10, Ui.GUTTER, 28);
        content.addView(personaRow(persona, false, this::openDetail));
        LinearLayout choices = Ui.column(c);
        content.addView(choices, top(10));
        loadRecipes(persona, choices, recipe -> {
            host.returnToPage(root);
            selectSegment(2);
            chosen.accept(recipe);
            refreshChoices();
        });
        host.pushFillingPage(persona.nameZh + " · 选配方", scroll(content));
    }

    private void loadRecipes(Compendium.Persona persona, LinearLayout container) {
        loadRecipes(persona, container, null);
    }

    private void loadRecipes(Compendium.Persona persona, LinearLayout container,
                             Consumer<FusionCalculator.Recipe> chosen) {
        if (!calculator.isAvailable(persona)) {
            container.addView(Ui.body(c, "先选择拥有的 " + dlcName(persona.dlc)));
            TextView select = Ui.secondary(c, "选择 DLC");
            select.setOnClickListener(v -> openDlc(() -> {
                container.removeAllViews();
                loadRecipes(persona, container, chosen);
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
                loadRecipes(persona, container, chosen);
                return;
            }
            if (recipes.isEmpty()) {
                container.addView(Ui.body(c, persona.unlockTextZh.isEmpty() ? "没有合成配方" : persona.unlockTextZh));
                return;
            }
            container.addView(Ui.sectionHeader(c, "素材组合", recipes.size() + " 组"));
            appendRecipes(container, recipes, 0, chosen);
        }, error -> {
            container.removeAllViews();
            TextView retry = Ui.secondary(c, "重新查找配方");
            retry.setOnClickListener(v -> { container.removeAllViews(); loadRecipes(persona, container, chosen); });
            container.addView(retry);
        });
    }

    private void appendRecipes(LinearLayout container, List<FusionCalculator.Recipe> recipes, int from,
                               Consumer<FusionCalculator.Recipe> chosen) {
        int end = Math.min(from + RECIPE_PAGE, recipes.size());
        for (int i = from; i < end; i++) {
            FusionCalculator.Recipe recipe = recipes.get(i);
            LinearLayout card = Ui.column(c);
            card.setBackground(Ui.card(c));
            Ui.pad(card, 12, 8, 12, 8);
            if (recipe.special) card.addView(Ui.mixed(c, "特殊合成", 12, Ui.col(c, R.color.p3r_cyan_300), true));
            for (int materialIndex = 0; materialIndex < recipe.ingredients.size(); materialIndex++) {
                Compendium.Persona ingredient = recipe.ingredients.get(materialIndex);
                LinearLayout materialRow = Ui.row(c);
                materialRow.setBackground(new SlantedPanelDrawable().fill(0)
                    .pressedFill(Ui.col(c, R.color.ui_cyan_soft)).cuts(Ui.dp(c, 8), 0, 0, 0));
                materialRow.setClickable(true);
                materialRow.setFocusable(true);
                materialRow.setOnClickListener(v -> openDetail(ingredient, chosen == null));
                TextView plus = Ui.numeral(c, materialIndex == 0 ? "" : "+", 18, Ui.col(c, R.color.p3r_cyan_300));
                plus.setGravity(Gravity.CENTER);
                materialRow.addView(plus, new LinearLayout.LayoutParams(Ui.dp(c, 18), ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView material = Ui.mixed(c, ingredient.nameZh + " · " + arcanaName(ingredient.arcana) + " Lv." + ingredient.level,
                    15, Ui.col(c, R.color.p3r_white), false);
                material.setMaxLines(2);
                Ui.pad(material, 3, 12, 4, 12);
                materialRow.addView(material, Ui.weight(1));
                materialRow.addView(new OwnedToggle(ingredient, false), Ui.wrap());
                TextView lookup = Ui.quiet(c, "查配方");
                lookup.setTag("material_recipe_" + ingredient.id);
                lookup.setTextSize(13);
                Ui.pad(lookup, 8, 8, 4, 8);
                lookup.setOnClickListener(v -> openRecipes(ingredient, chosen == null));
                materialRow.addView(lookup, Ui.wrap());
                card.addView(materialRow);
            }
            TextView add = chosen == null ? Ui.secondary(c, "加入合成篮") : Ui.primary(c, "选这个配方");
            add.setTag("recipe_add_" + recipe.result.id + "_" + i);
            add.setOnClickListener(v -> {
                if (chosen == null) addToBasket(recipe);
                else chosen.accept(recipe);
            });
            card.addView(add, top(8));
            container.addView(card, top(8));
        }
        if (end < recipes.size()) {
            TextView more = Ui.secondary(c, "更多配方（还剩 " + (recipes.size() - end) + " 组）");
            more.setOnClickListener(v -> {
                container.removeView(more);
                appendRecipes(container, recipes, end, chosen);
            });
            container.addView(more, top(12));
        }
    }

    private void pickPersona(String title, String excludedId, Compendium.Persona partner,
                             Consumer<Compendium.Persona> onSelected) {
        pickPersona(title, excludedId, partner, false, onSelected);
    }

    private void pickPersona(String title, String excludedId, Compendium.Persona partner,
                             boolean keepSheet, Consumer<Compendium.Persona> onSelected) {
        boolean keepTyping = activePickerSearch != null && activePickerSearch.hasFocus();
        if (!keepTyping) hideKeyboard();
        LinearLayout sheet = sheet(title);
        if (partner != null) {
            LinearLayout context = Ui.row(c);
            context.setBackground(Ui.heroCard(c));
            Ui.pad(context, 12, 9, 12, 9);
            context.addView(Ui.caption(c, "已选"), Ui.wrap());
            TextView name = Ui.mixed(c, partner.nameZh + " · Lv." + partner.level, 14,
                Ui.col(c, R.color.p3r_cyan_300), true);
            LinearLayout.LayoutParams gap = Ui.weight(1);
            gap.leftMargin = Ui.dp(c, 10);
            context.addView(name, gap);
            sheet.addView(context, top(9));
        }
        EditText search = search("名称、英文名或技能", "fusion_picker_search");
        activePickerSearch = search;
        sheet.addView(searchBox(search), top(10));
        LinearLayout scopes = Ui.row(c);
        TextView all = CompendiumControls.scopeButton(c, "全部", true);
        all.setTag("fusion_picker_all");
        TextView favorite = CompendiumControls.scopeButton(c, "收藏", false);
        favorite.setTag("fusion_picker_favorites");
        TextView counter = Ui.caption(c, "");
        counter.setGravity(Gravity.END);
        scopes.addView(all, Ui.weight(1));
        scopes.addView(favorite, weightedGap());
        LinearLayout.LayoutParams counterGap = Ui.wrap();
        counterGap.leftMargin = Ui.dp(c, 10);
        scopes.addView(counter, counterGap);
        sheet.addView(scopes, top(10));
        ListView results = personaList();
        results.setTag("fusion_picker_list");
        Ui.pad(results, 0, 8, 0, 8);
        PersonaAdapter choices = new PersonaAdapter(false, value -> {
            if (!keepSheet) {
                hideKeyboard();
                host.dismissSheet();
            }
            onSelected.accept(value);
        });
        results.setAdapter(choices);
        TextView noMatch = Ui.caption(c, "没有匹配结果");
        noMatch.setGravity(Gravity.CENTER);
        sheet.addView(noMatch, top(8));
        boolean[] favoriteScope = {false};
        Runnable filter = () -> {
            List<Compendium.Persona> matches = CompendiumQuery.find(data, calculator, search.getText().toString(),
                null, 1, 99, favoriteScope[0], favoriteIds, false, CompendiumQuery.LEVEL_ASC);
            matches.removeIf(persona -> persona.id.equals(excludedId));
            if (!favoriteScope[0] && search.getText().toString().trim().isEmpty()) {
                matches.sort((a, b) -> Boolean.compare(favoriteIds.contains(b.id), favoriteIds.contains(a.id)));
            }
            choices.setItems(matches);
            counter.setText(matches.size() + " 个");
            CompendiumControls.setScopeSelected(all, !favoriteScope[0]);
            CompendiumControls.setScopeSelected(favorite, favoriteScope[0]);
            noMatch.setText(favoriteScope[0] && favoriteIds.isEmpty() ? "还没有收藏的人格面具" : "没有匹配结果");
            noMatch.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
            results.setSelection(0);
        };
        all.setOnClickListener(v -> { favoriteScope[0] = false; filter.run(); });
        favorite.setOnClickListener(v -> { favoriteScope[0] = true; filter.run(); });
        search.addTextChangedListener(watcher(text -> filter.run()));
        int maxDp = partner == null ? 340 : 300;
        int screenDp = Math.round(c.getResources().getDisplayMetrics().heightPixels
            / c.getResources().getDisplayMetrics().density);
        int rowsDp = Math.max(180, Math.min(maxDp, screenDp - (partner == null ? 360 : 420)));
        sheet.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(c, rowsDp)));
        filter.run();
        host.showSheet(sheet, () -> {
            if (activePickerSearch == search) activePickerSearch = null;
        });
        if (keepTyping) {
            search.requestFocus();
            search.post(() -> {
                if (activePickerSearch != search) return;
                search.requestFocus();
                InputMethodManager keyboard = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null && search.hasFocus()) keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            });
        }
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
        card.addView(new OwnedToggle(persona, false), Ui.wrap());
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

    private void addToBasket(FusionCalculator.Recipe recipe) {
        FusionBasket basket = prefs.basket();
        if (!basket.addGoal(recipe)) {
            host.showMessage("这个配方与篮里的步骤冲突");
            return;
        }
        prefs.setBasket(basket);
        refreshChoices();
        host.returnToPage(root);
        selectSegment(2);
    }

    private void refreshChoices() {
        ownedPersonas = new HashSet<>(prefs.ownedPersonas());
        refreshList(false);
        refreshBasketBadge();
        if (basketPage != null) basketPage.refresh();
        renderFusion();
        paintOwned(host.activity().getWindow().getDecorView());
    }

    private void refreshStoredChoices() {
        Set<String> restoredFavorites = new HashSet<>(prefs.favorites());
        Set<String> restoredOwned = new HashSet<>(prefs.ownedPersonas());
        Set<String> restoredDlc = new HashSet<>(prefs.ownedDlc());
        boolean packsChanged = !ownedDlc.equals(restoredDlc);
        boolean catalogueChanged = packsChanged || !favoriteIds.equals(restoredFavorites)
            || !ownedPersonas.equals(restoredOwned);
        favoriteIds = restoredFavorites;
        ownedPersonas = restoredOwned;
        ownedDlc = restoredDlc;
        if (data == null) return;
        if (packsChanged) {
            calculator = new FusionCalculator(data, ownedDlc);
            if (first != null && !calculator.isAvailable(first)) first = null;
            if (second != null && !calculator.isAvailable(second)) second = null;
            if (target != null && !calculator.isAvailable(target)) target = null;
            if (basketPage != null) basketPage.setCalculator(calculator);
        }
        if (catalogueChanged) {
            int firstVisible = list.getFirstVisiblePosition();
            int offset = list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop() - list.getPaddingTop();
            refreshList(false);
            if (adapter.getCount() > 0) list.setSelectionFromTop(Math.min(firstVisible, adapter.getCount() - 1), offset);
            renderFusion();
            paintOwned(host.activity().getWindow().getDecorView());
        }
        refreshBasketBadge();
        if (basketPage != null) basketPage.refresh();
    }

    private void refreshBasketBadge() {
        if (basketSegment == null) return;
        int goals = prefs.basket().goals().size();
        basketSegment.setText(goals == 0 ? "合成篮" : "合成篮 · " + goals);
    }

    private void paintOwned(View view) {
        if (view instanceof OwnedToggle) ((OwnedToggle) view).paint();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) paintOwned(group.getChildAt(i));
        }
    }

    private final class OwnedToggle extends TextView {
        private final Compendium.Persona persona;
        private final boolean wide;
        OwnedToggle(Compendium.Persona persona, boolean wide) {
            super(c);
            this.persona = persona;
            this.wide = wide;
            setTag("persona_owned_" + persona.id);
            setGravity(Gravity.CENTER);
            setTextSize(wide ? 14 : 21);
            setMinWidth(Ui.dp(c, wide ? 112 : 48));
            setMinHeight(Ui.dp(c, 48));
            Ui.pad(this, wide ? 12 : 8, 6, wide ? 12 : 8, 6);
            setClickable(true);
            setFocusable(true);
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host,
                        android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.CheckBox");
                    info.setCheckable(true);
                    info.setChecked(ownedPersonas.contains(persona.id));
                }
            });
            setOnClickListener(v -> {
                prefs.setOwned(persona.id, !ownedPersonas.contains(persona.id));
                refreshChoices();
            });
            paint();
        }
        void paint() {
            boolean owned = ownedPersonas.contains(persona.id);
            setText(wide ? owned ? "✓ 可召唤" : "□ 未登录" : owned ? "✓" : "□");
            setSelected(owned);
            setTextColor(Ui.col(c, owned ? R.color.p3r_cyan_300 : R.color.p3r_ink_muted));
            setContentDescription((owned ? "取消登录 " : "标记可召唤 ") + persona.nameZh);
            setBackground(new SlantedPanelDrawable()
                .fill(owned ? Ui.col(c, R.color.ui_cyan_soft) : 0)
                .pressedFill(Ui.col(c, R.color.p3r_navy_700)).cuts(Ui.dp(c, 6), 0, 0, 0));
        }
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
        result.setFocusableInTouchMode(true);
        return result;
    }

    private int sheetScrollHeight(int preferredDp, int chromeDp) {
        int heightDp = Math.round(c.getResources().getDisplayMetrics().heightPixels
            / c.getResources().getDisplayMetrics().density);
        return Math.max(160, Math.min(preferredDp, heightDp - chromeDp));
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

    private View searchBox(EditText field) {
        FrameLayout box = new FrameLayout(c);
        Ui.pad(field, 12, 8, 52, 8);
        box.addView(field, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView clear = new ImageView(c);
        clear.setImageDrawable(Ui.tinted(c, R.drawable.ui_ic_close, Ui.col(c, R.color.p3r_ink_muted)));
        clear.setScaleType(ImageView.ScaleType.CENTER);
        clear.setTag(field.getTag() + "_clear");
        clear.setContentDescription("清空搜索");
        clear.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_soft)));
        clear.setOnClickListener(v -> field.setText(""));
        clear.setVisibility(field.length() == 0 ? View.INVISIBLE : View.VISIBLE);
        FrameLayout.LayoutParams action = new FrameLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48),
            Gravity.END | Gravity.CENTER_VERTICAL);
        box.addView(clear, action);
        field.addTextChangedListener(watcher(text -> clear.setVisibility(text.isEmpty() ? View.INVISIBLE : View.VISIBLE)));
        return box;
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

    private static boolean sortByArcana(int order) {
        return order == CompendiumQuery.ARCANA || order == CompendiumQuery.ARCANA_DESC;
    }

    private static boolean sortDescending(int order) {
        return order == CompendiumQuery.LEVEL_DESC || order == CompendiumQuery.ARCANA_DESC;
    }

    private TextView choice(String text) {
        TextView result = CompendiumControls.toolButton(c, text, false);
        result.setTextSize(13);
        result.setMaxLines(2);
        Ui.pad(result, 5, 6, 5, 6);
        return result;
    }

    private void addToolIcon(TextView button, int resource) {
        Drawable icon = Ui.tinted(c, resource, Ui.col(c, R.color.p3r_ice_100));
        icon.setBounds(0, 0, Ui.dp(c, 14), Ui.dp(c, 14));
        button.setCompoundDrawables(icon, null, null, null);
        button.setCompoundDrawablePadding(Ui.dp(c, 5));
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
        if (focus == null) focus = host.activity().getWindow().getDecorView().findFocus();
        if (keyboard != null && focus != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        if (focus != null) focus.clearFocus();
        root.requestFocus();
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
