package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import app.p3r.dayplanner.v3.compendium.FusionBasket;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;

/**
 * 全书 tab: 一览 (search, favorites, filters), 合体 (two-Persona fusion and 想合出谁？) and 合体篮,
 * with the Persona detail, recipe and picker pages around them. Lookups and fusions never touch
 * the plan.
 */
public final class CompendiumPage {
    private static volatile Compendium cached;
    private static final int RECIPE_PAGE = 30;
    /** A recipe that would lead back into one of the basket's own Personas (also the basket's). */
    static final String CYCLE = "这个配方会绕回篮里的目标，换一个吧";
    /** Confirmation after 加入合体篮; the player stays where they are. */
    static final String ADDED = "已加入合体篮";
    /** The same recipe added again: nothing changes. */
    static final String ALREADY_ADDED = "已经在合体篮里了";
    /** The basket made this Persona another way; 撤销 brings that recipe and its sub-recipes back. */
    static final String SWAPPED = "已换成这个配方";

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
    private TextView favoritesButton;
    private TextView count;
    private LinearLayout selection;
    private TextView empty;
    private ListView list;
    private PersonaAdapter adapter;
    private LinearLayout fusionContent;
    private Compendium.Persona first;
    private Compendium.Persona second;
    private Compendium.Persona browseGoal;
    private String shownResult;
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
    private int basketGoals = -1;
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

    /**
     * The mask plate at the right of the 全书 header (tag compendium_dlc): opens 「买了哪些 DLC？」
     * once the page has loaded; it shows a dot while any DLC is owned.
     */
    static View dlcPlate(Context context) {
        TextView plate = CompendiumControls.icon(context, "已购买的 DLC", R.drawable.ui_ic_mask);
        plate.setTag("compendium_dlc");
        plate.setOnClickListener(v -> {
            View page = v.getRootView().findViewWithTag("compendium_page");
            Object controller = page == null ? null : page.getTag(R.id.compendium_controller);
            if (controller instanceof CompendiumPage) ((CompendiumPage) controller).openDlc();
        });
        // A frame has no text baseline, so the header's title keeps its own line position.
        FrameLayout frame = new FrameLayout(context);
        frame.addView(plate, new FrameLayout.LayoutParams(Ui.dp(context, Ui.TOUCH), Ui.dp(context, Ui.TOUCH)));
        return frame;
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
        TextView loading = Ui.muted(c, "正在打开全书…");
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
            LinearLayout box = Ui.column(c);
            Ui.pad(box, Ui.GUTTER, 28, Ui.GUTTER, 0);
            box.addView(Ui.headline(c, "全书没读出来"));
            TextView retry = Ui.primary(c, "重试");
            retry.setOnClickListener(v -> load());
            box.addView(retry, top(16));
            root.addView(box);
        });
    }

    // ------------------------------------------------------------------ shell

    private void build() {
        root.removeAllViews();
        LinearLayout segments = Ui.row(c);
        Ui.pad(segments, Ui.GUTTER, 10, Ui.GUTTER, 4);
        listSegment = segment("一览", "compendium_tab", 0);
        fusionSegment = segment("合体", "fusion_tab", 1);
        basketSegment = segment("合体篮", "basket_tab", 2);
        segments.addView(listSegment, Ui.weight(1));
        segments.addView(fusionSegment, weightedGap());
        segments.addView(basketSegment, weightedGap());
        root.addView(segments);
        paneHost = new FrameLayout(c);
        root.addView(paneHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        buildList();
        fusionPane = Ui.column(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        fusionContent = Ui.column(c);
        Ui.pad(fusionContent, Ui.GUTTER, 8, Ui.GUTTER, 28);
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
            @Override public void pickTarget() { pickTargetThenRecipes(); }
        });
        basketPane = basketPage.view();
        paneHost.addView(basketPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        refreshList(true);
        renderFusion();
        refreshBasketBadge();
        paintDlcPlate();
        int first = activeSegment;
        activeSegment = -1;
        selectSegment(first);
    }

    private TextView segment(String label, String tag, int index) {
        TextView chip = Ui.chip(c, label, false);
        chip.setTag(tag);
        chip.setTextSize(15);
        chip.setMinHeight(Ui.dp(c, 44));
        chip.setMinimumHeight(Ui.dp(c, 44));
        Ui.pad(chip, 6, 6, 6, 6);
        chip.setOnClickListener(v -> selectSegment(index));
        return chip;
    }

    private void selectSegment(int segment) {
        int previous = activeSegment;
        boolean changed = previous != segment;
        View[] panes = {listPane, fusionPane, basketPane};
        View outgoing = previous >= 0 ? panes[previous] : null;
        View incoming = panes[segment];
        activeSegment = segment;
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
        if (changed && outgoing != null && Ui.animationsEnabled() && root.isLaidOut()) {
            int direction = segment > previous ? 1 : -1;
            incoming.setAlpha(0f);
            incoming.setTranslationX(Ui.dp(c, 24) * direction);
            outgoing.animate().alpha(0f).translationX(-Ui.dp(c, 12) * direction).setDuration(120)
                .setInterpolator(Motion.EASE).withEndAction(() -> {
                    if (generation == segmentGeneration) outgoing.setVisibility(View.GONE);
                    outgoing.setAlpha(1f);
                    outgoing.setTranslationX(0);
                }).start();
            incoming.animate().alpha(1f).translationX(0).setDuration(220).setInterpolator(Motion.SETTLE).start();
        } else if (outgoing != null && outgoing != incoming) {
            outgoing.setVisibility(View.GONE);
            outgoing.setAlpha(1f);
            outgoing.setTranslationX(0);
        }
        if (changed) {
            if (segment == 0) cascadeList();
            else if (segment == 1) Motion.cascade(fusionContent);
            else basketPage.cascade();
        }
        hideKeyboard();
    }

    private void paintDlcPlate() {
        View plate = host.activity().getWindow().getDecorView().findViewWithTag("compendium_dlc");
        if (plate instanceof TextView) CompendiumControls.setToolActive((TextView) plate, !ownedDlc.isEmpty());
    }

    // ------------------------------------------------------------------ 一览

    private void buildList() {
        listPane = Ui.column(c);
        LinearLayout controls = Ui.column(c);
        Ui.pad(controls, Ui.GUTTER, 4, Ui.GUTTER, 0);
        LinearLayout searchRow = Ui.row(c);
        EditText search = search("名字、英文名或技能", "compendium_search");
        search.addTextChangedListener(watcher(text -> { query = text; refreshList(true); }));
        searchRow.addView(searchBox(search), Ui.weight(1));
        favoritesButton = CompendiumControls.icon(c, "只看收藏", R.drawable.ui_ic_star);
        favoritesButton.setTag("compendium_favorites");
        favoritesButton.setOnClickListener(v -> {
            favoritesOnly = !favoritesOnly;
            refreshList(true);
            cascadeList();
        });
        searchRow.addView(favoritesButton, gapWrap(8));
        filterButton = CompendiumControls.icon(c, "筛选", R.drawable.ui_ic_filter);
        filterButton.setTag("compendium_filter");
        filterButton.setOnClickListener(v -> openFilters());
        searchRow.addView(filterButton, gapWrap(6));
        controls.addView(searchRow);
        LinearLayout status = Ui.row(c);
        // As tall as a filter chip's target, so the list does not move when the first chip appears.
        status.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        HorizontalScrollView chips = new HorizontalScrollView(c);
        chips.setHorizontalScrollBarEnabled(false);
        selection = Ui.row(c);
        selection.setTag("compendium_filter_selection");
        chips.addView(selection);
        status.addView(chips, Ui.weight(1));
        count = Ui.text(c, "", 13, Ui.col(c, R.color.p3r_ink_muted), false);
        count.setTag("compendium_count");
        status.addView(count, gapWrap(8));
        controls.addView(status);
        listPane.addView(controls);
        FrameLayout results = new FrameLayout(c);
        list = personaList();
        list.setTag("compendium_list");
        adapter = new PersonaAdapter(true, this::openDetail);
        list.setAdapter(adapter);
        results.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        empty = Ui.text(c, "", 16, Ui.col(c, R.color.p3r_ink_muted), false);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(empty, 24, 48, 24, 16);
        results.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        listPane.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        paneHost.addView(listPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void refreshList(boolean resetPosition) {
        if (data == null || adapter == null) return;
        List<Compendium.Persona> matches = CompendiumQuery.find(data, calculator, query, arcana,
            minLevel, maxLevel, favoritesOnly, favoriteIds, showUnowned, order);
        adapter.setItems(matches, sortByArcana(order));
        count.setText(countText(matches.size()));
        count.setContentDescription(matches.size() + " 个人格面具");
        empty.setText(favoritesOnly && favoriteIds.isEmpty() ? "还没有收藏" : "没有符合的人格面具");
        empty.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(matches.isEmpty() ? View.GONE : View.VISIBLE);
        CompendiumControls.setToolActive(favoritesButton, favoritesOnly);
        CompendiumControls.setToolActive(filterButton, arcana != null || minLevel > 1 || maxLevel < 99
            || showUnowned || order != CompendiumQuery.LEVEL_ASC);
        renderSelection();
        if (resetPosition) list.setSelection(0);
    }

    /** 「173 个」 with the number as a numeral. */
    private CharSequence countText(int n) {
        SpannableString text = new SpannableString(n + " 个");
        int end = String.valueOf(n).length();
        NumeralSpan.style(text, 0, end, Ui.numerals(c), 1.5f);
        text.setSpan(new ForegroundColorSpan(Ui.col(c, R.color.p3r_white)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /** The active filters as removable chips (「恋爱 ×」「Lv.20–70 ×」「等级 ↓ ×」「含没买的 DLC ×」). */
    private void renderSelection() {
        selection.removeAllViews();
        if (arcana != null) {
            selection.addView(filterChip(PersonaViews.arcanaName(data, arcana), () -> arcana = null));
        }
        if (minLevel > 1 || maxLevel < 99) {
            selection.addView(filterChip("Lv." + minLevel + "–" + maxLevel, () -> { minLevel = 1; maxLevel = 99; }));
        }
        if (order != CompendiumQuery.LEVEL_ASC) {
            String label = (sortByArcana(order) ? "阿尔卡那" : "等级") + (sortDescending(order) ? " ↓" : " ↑");
            selection.addView(filterChip(label, () -> order = CompendiumQuery.LEVEL_ASC));
        }
        if (showUnowned) selection.addView(filterChip("含没买的 DLC", () -> showUnowned = false));
    }

    private View filterChip(String label, Runnable remove) {
        TextView chip = Ui.mixed(c, label + "  ×", 13, Ui.col(c, R.color.p3r_cyan_200), true);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(Ui.dp(c, Ui.TOUCH));
        chip.setMaxLines(1);
        // A 32 dp plate in the middle of the 48 dp target; the label's padding is set after it.
        chip.setBackground(new InsetDrawable(new SlantedPanelDrawable().fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 140))
            .pressedFill(Ui.col(c, R.color.ui_cyan_soft))
            .stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 150), Ui.dp(c, 1)).skew(Ui.dp(c, 5)),
            0, Ui.dp(c, 8), 0, Ui.dp(c, 8)));
        Ui.pad(chip, 12, 4, 12, 4);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setContentDescription("去掉筛选：" + label);
        chip.setOnClickListener(v -> {
            remove.run();
            refreshList(true);
            cascadeList();
        });
        LinearLayout.LayoutParams p = Ui.wrap();
        p.rightMargin = Ui.dp(c, 6);
        chip.setLayoutParams(p);
        return chip;
    }

    private void cascadeList() {
        if (list == null || !Ui.animationsEnabled()) return;
        list.post(() -> Motion.cascade(list));
    }

    private void openFilters() {
        hideKeyboard();
        String[] pendingArcana = {arcana};
        int[] pendingOrder = {order};
        boolean[] pendingUnowned = {showUnowned};
        LinearLayout sheet = sheet("筛选");
        LinearLayout body = Ui.column(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.addView(body);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(c, sheetScrollHeight(430, 300))));
        body.addView(Ui.sectionHeader(c, "等级", null), top(4));
        LinearLayout levels = Ui.row(c);
        levels.addView(Ui.numeral(c, "Lv.", 20, Ui.col(c, R.color.p3r_cyan_300)), Ui.wrap());
        EditText min = number(minLevel, "compendium_min_level");
        levels.addView(min, gapWeight(10));
        TextView dash = Ui.text(c, "–", 18, Ui.col(c, R.color.p3r_ink_muted), true);
        LinearLayout.LayoutParams dashParams = Ui.wrap();
        dashParams.leftMargin = Ui.dp(c, 10);
        dashParams.rightMargin = Ui.dp(c, 10);
        levels.addView(dash, dashParams);
        EditText max = number(maxLevel, "compendium_max_level");
        levels.addView(max, Ui.weight(1));
        body.addView(levels, top(2));
        body.addView(Ui.sectionHeader(c, "排序", null), top(12));
        TextView byLevel = Ui.chip(c, "等级", false);
        byLevel.setTag("compendium_sort_level");
        TextView byArcana = Ui.chip(c, "阿尔卡那", false);
        byArcana.setTag("compendium_sort_arcana");
        TextView direction = CompendiumControls.toolButton(c, "升序 ↑", false);
        direction.setTag("compendium_sort_direction");
        List<TextView> arcanaChips = new ArrayList<>();
        List<String> arcanaIds = new ArrayList<>();
        PersonaViews.Toggle unownedCheck = new PersonaViews.Toggle(c, false);
        LinearLayout unowned = Ui.row(c);
        Runnable paint = () -> {
            SheetChrome.selectChoice(byLevel, !sortByArcana(pendingOrder[0]));
            SheetChrome.selectChoice(byArcana, sortByArcana(pendingOrder[0]));
            boolean descending = sortDescending(pendingOrder[0]);
            direction.setText(descending ? "降序 ↓" : "升序 ↑");
            direction.setContentDescription(descending ? "降序，换成升序" : "升序，换成降序");
            for (int i = 0; i < arcanaChips.size(); i++) {
                String id = arcanaIds.get(i);
                SheetChrome.selectChoice(arcanaChips.get(i), id == null ? pendingArcana[0] == null : id.equals(pendingArcana[0]));
            }
            unownedCheck.setChecked(pendingUnowned[0]);
            unowned.setSelected(pendingUnowned[0]);
        };
        Runnable flip = () -> {
            boolean descending = !sortDescending(pendingOrder[0]);
            pendingOrder[0] = sortByArcana(pendingOrder[0])
                ? descending ? CompendiumQuery.ARCANA_DESC : CompendiumQuery.ARCANA
                : descending ? CompendiumQuery.LEVEL_DESC : CompendiumQuery.LEVEL_ASC;
        };
        byLevel.setOnClickListener(v -> {
            if (!sortByArcana(pendingOrder[0])) flip.run();
            else pendingOrder[0] = sortDescending(pendingOrder[0]) ? CompendiumQuery.LEVEL_DESC : CompendiumQuery.LEVEL_ASC;
            paint.run();
        });
        byArcana.setOnClickListener(v -> {
            if (sortByArcana(pendingOrder[0])) flip.run();
            else pendingOrder[0] = sortDescending(pendingOrder[0]) ? CompendiumQuery.ARCANA_DESC : CompendiumQuery.ARCANA;
            paint.run();
        });
        direction.setOnClickListener(v -> { flip.run(); paint.run(); });
        LinearLayout sortRow = Ui.row(c);
        sortRow.addView(byLevel, Ui.weight(1));
        sortRow.addView(byArcana, gapWeight(6));
        LinearLayout.LayoutParams directionParams = new LinearLayout.LayoutParams(Ui.dp(c, 92),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        directionParams.leftMargin = Ui.dp(c, 10);
        sortRow.addView(direction, directionParams);
        body.addView(sortRow, top(4));
        body.addView(Ui.sectionHeader(c, "阿尔卡那", null), top(12));
        TextView any = Ui.chip(c, "全部", false);
        any.setOnClickListener(v -> { pendingArcana[0] = null; paint.run(); });
        arcanaChips.add(any);
        arcanaIds.add(null);
        for (Compendium.Arcana item : data.arcana()) {
            String number = PersonaViews.roman(item);
            TextView value = Ui.chip(c, number.isEmpty() ? item.nameZh : number + " " + item.nameZh, false);
            value.setContentDescription(item.nameZh);
            value.setOnClickListener(v -> { pendingArcana[0] = item.id; paint.run(); });
            arcanaChips.add(value);
            arcanaIds.add(item.id);
        }
        for (TextView chip : arcanaChips) {
            chip.setTextSize(14);
            chip.setMaxLines(1);
            Ui.pad(chip, 6, 6, 6, 6);
        }
        grid(body, arcanaChips, 3);
        unowned.setTag("compendium_show_unowned");
        unowned.setClickable(true);
        unowned.setFocusable(true);
        unowned.setAccessibilityDelegate(PersonaViews.CHECKBOX);
        unowned.setContentDescription("也显示没买的 DLC");
        unownedCheck.setClickable(false);
        unownedCheck.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        unowned.addView(unownedCheck, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 48)));
        unowned.addView(Ui.text(c, "也显示没买的 DLC", 15, Ui.col(c, R.color.p3r_ice_100), false), gapWeight(6));
        unowned.setOnClickListener(v -> { pendingUnowned[0] = !pendingUnowned[0]; paint.run(); });
        body.addView(unowned, top(10));
        LinearLayout actions = Ui.row(c);
        TextView reset = Ui.quiet(c, "重置");
        reset.setOnClickListener(v -> {
            pendingArcana[0] = null;
            pendingOrder[0] = CompendiumQuery.LEVEL_ASC;
            pendingUnowned[0] = false;
            min.setText("1");
            max.setText("99");
            paint.run();
        });
        actions.addView(reset, Ui.weight(1));
        TextView apply = Ui.primary(c, "看结果");
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
            cascadeList();
        });
        LinearLayout.LayoutParams applyParams = Ui.weight(2);
        applyParams.leftMargin = Ui.dp(c, 12);
        actions.addView(apply, applyParams);
        sheet.addView(actions, top(14));
        paint.run();
        host.showSheet(sheet);
    }

    // ------------------------------------------------------------------ DLC

    /** 「买了哪些 DLC？」: each tap applies at once (catalogue, fusion results, basket). */
    void openDlc() {
        if (data == null) return;
        hideKeyboard();
        LinearLayout sheet = sheet("买了哪些 DLC？");
        List<Runnable> painters = new ArrayList<>();
        for (Compendium.DlcPack pack : data.dlcPacks()) {
            String name = PersonaViews.dlcName(data, pack.id);
            LinearLayout row = Ui.row(c);
            row.setTag("dlc_pack_" + pack.id);
            row.setContentDescription(name);
            row.setClickable(true);
            row.setFocusable(true);
            row.setAccessibilityDelegate(PersonaViews.CHECKBOX);
            row.setMinimumHeight(Ui.dp(c, 64));
            Ui.pad(row, 18, 10, 8, 10);
            LinearLayout words = Ui.column(c);
            words.addView(Ui.mixed(c, name, 16, Ui.col(c, R.color.p3r_white), true));
            int total = 0;
            List<String> examples = new ArrayList<>();
            for (Compendium.Persona persona : data.personas()) {
                if (!pack.id.equals(persona.dlc)) continue;
                total++;
                if (examples.size() < 2) examples.add(persona.nameZh);
            }
            words.addView(Ui.mixed(c, total + " 个 · " + String.join("、", examples), 13,
                Ui.col(c, R.color.p3r_ink_muted), false), top(3));
            row.addView(words, Ui.weight(1));
            PersonaViews.Toggle check = new PersonaViews.Toggle(c, false);
            check.setClickable(false);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(check, Ui.wrap());
            Runnable paint = () -> {
                boolean on = ownedDlc.contains(pack.id);
                row.setSelected(on);
                check.setChecked(on);
                SlantedPanelDrawable plate = Ui.pressableCard(c);
                if (on) plate.accent(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 4));
                row.setBackground(plate);
            };
            painters.add(paint);
            row.setOnClickListener(v -> {
                Set<String> packs = new HashSet<>(ownedDlc);
                if (!packs.remove(pack.id)) packs.add(pack.id);
                applyDlc(packs);
                for (Runnable p : painters) p.run();
            });
            sheet.addView(row, top(8));
        }
        for (Runnable p : painters) p.run();
        TextView done = Ui.primary(c, "好了");
        done.setOnClickListener(v -> host.dismissSheet());
        sheet.addView(done, top(18));
        host.showSheet(sheet);
    }

    /** Saves the owned packs and rebuilds everything that depends on them. */
    private void applyDlc(Set<String> packs) {
        prefs.setOwnedDlc(packs);
        ownedDlc = new HashSet<>(packs);
        calculator = new FusionCalculator(data, ownedDlc);
        basketPage.setCalculator(calculator);
        if (first != null && !calculator.isAvailable(first)) first = null;
        if (second != null && !calculator.isAvailable(second)) second = null;
        refreshList(true);
        renderFusion();
        paintDlcPlate();
    }

    /** 「这是 P5R 人格面具组合 1 里的人格面具，买了吗？」 [买了]: adds that one pack, then {@code then}. */
    private LinearLayout dlcPrompt(Compendium.Persona persona, Runnable[] then) {
        LinearLayout box = Ui.column(c);
        box.setTag("dlc_prompt_" + persona.id);
        box.setBackground(Ui.card(c).accent(Ui.col(c, R.color.ui_gold), Ui.dp(c, 4)));
        Ui.pad(box, 18, 12, 14, 12);
        box.addView(Ui.mixed(c, "这是 " + PersonaViews.dlcName(data, persona.dlc) + " 里的人格面具，买了吗？", 15,
            Ui.col(c, R.color.p3r_white), true));
        TextView yes = Ui.primary(c, "买了");
        yes.setTag("dlc_owned_" + persona.id);
        yes.setOnClickListener(v -> {
            Set<String> packs = new HashSet<>(ownedDlc);
            packs.add(persona.dlc);
            applyDlc(packs);
            then[0].run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(c, 132), ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(c, 10);
        box.addView(yes, p);
        return box;
    }

    // ------------------------------------------------------------------ detail

    private void openDetail(Compendium.Persona persona) {
        openDetail(persona, false);
    }

    private void openDetail(Compendium.Persona persona, boolean replace) {
        hideKeyboard();
        if (!replace) browseGoal = null;
        LinearLayout content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 10, Ui.GUTTER, 32);
        content.addView(detailHero(persona));
        LinearLayout prompt = null;
        Runnable[] afterDlc = {() -> openDetail(persona, true)};
        if (!calculator.isAvailable(persona)) {
            prompt = dlcPrompt(persona, afterDlc);
            content.addView(prompt, top(12));
        }
        LinearLayout actions = Ui.row(c);
        TextView recipes = Ui.primary(c, "查配方");
        recipes.setTag("persona_recipes_" + persona.id);
        recipes.setOnClickListener(v -> openRecipes(persona, true));
        actions.addView(recipes, Ui.weight(1));
        TextView use = Ui.secondary(c, "拿它当素材");
        use.setTag("persona_material_" + persona.id);
        LinearLayout dlcPrompt = prompt;
        use.setOnClickListener(v -> {
            if (calculator.isAvailable(persona)) {
                openMaterialFusion(persona);
            } else if (dlcPrompt != null) {
                afterDlc[0] = () -> openMaterialFusion(persona);
                Motion.swapIn(dlcPrompt);
            }
        });
        LinearLayout.LayoutParams useParams = Ui.weight(1);
        useParams.leftMargin = Ui.dp(c, 12);
        actions.addView(use, useParams);
        content.addView(actions, top(14));
        if (!persona.origins.isEmpty()) content.addView(origins(persona), top(18));
        content.addView(Ui.sectionHeader(c, "能力值", null), top(18));
        String[] labels = {"力", "魔", "耐", "速", "运"};
        for (int i = 0; i < labels.length; i++) {
            content.addView(statRow(labels[i], i < persona.stats.length ? persona.stats[i] : 0), top(i == 0 ? 2 : 6));
        }
        content.addView(Ui.sectionHeader(c, "弱点与耐性", null), top(18));
        content.addView(affinities(persona), top(2));
        content.addView(Ui.sectionHeader(c, "可学技能", null), top(18));
        boolean firstSkill = true;
        for (Compendium.LearnedSkill learned : persona.skills) {
            Compendium.Skill skill = data.skill(learned.skillId);
            if (skill == null) continue;
            if (!firstSkill) content.addView(rule(), ruleParams());
            content.addView(skillRow(skill, learned.level <= 0 ? "初始" : "Lv." + learned.level));
            firstSkill = false;
        }
        if (!persona.theurgySkillIds.isEmpty()) {
            content.addView(Ui.sectionHeader(c, "神通法", null), top(18));
            boolean firstTheurgy = true;
            for (String id : persona.theurgySkillIds) {
                Compendium.Skill skill = data.skill(id);
                if (skill == null) continue;
                if (!firstTheurgy) content.addView(rule(), ruleParams());
                content.addView(skillRow(skill, ""));
                firstTheurgy = false;
            }
        }
        String title = PersonaViews.arcanaName(data, persona.arcana);
        if (replace) host.replaceFillingPage(title, scroll(content));
        else host.pushFillingPage(title, scroll(content));
        Motion.cascade(content);
    }

    /**
     * The one hero of the detail: the arcana's Latin word as a giant outline behind, the arcana
     * tag, the name at 32 sp, the English name, a giant 「Lv.」, the two labelled toggles, the
     * 合体条件 and the DLC pack.
     */
    private View detailHero(Compendium.Persona persona) {
        LinearLayout hero = Ui.column(c);
        hero.setBackground(PersonaViews.withWatermark(c, Ui.heroCard(c), PersonaViews.arcanaWord(data, persona.arcana)));
        Ui.pad(hero, 22, 16, 18, 18);
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.TOP);
        LinearLayout identity = Ui.column(c);
        identity.addView(arcanaTag(persona.arcana), Ui.wrap());
        TextView name = Ui.text(c, persona.nameZh, 32, Ui.col(c, R.color.p3r_white), true);
        name.setShadowLayer(0.01f, Ui.dp(c, 2), Ui.dp(c, 2), Ui.alpha(Ui.col(c, R.color.p3r_royal_500), 200));
        identity.addView(name, top(8));
        TextView english = Ui.text(c, Ui.latin(c, persona.nameEn), 15, Ui.col(c, R.color.p3r_ink_muted), false);
        identity.addView(english, top(0));
        top.addView(identity, Ui.weight(1));
        TextView level = PersonaViews.level(c, persona.level, 54, Ui.col(c, R.color.p3r_cyan_400), 0.4f);
        top.addView(level, Ui.wrap());
        hero.addView(top);
        LinearLayout toggles = Ui.row(c);
        toggles.setTag("persona_detail_toggles");
        TextView favorite = PersonaViews.labelledToggle(c);
        favorite.setTag("persona_favorite_" + persona.id);
        TextView owned = PersonaViews.labelledToggle(c);
        owned.setTag("persona_detail_owned_" + persona.id);
        Runnable paint = () -> {
            boolean star = favoriteIds.contains(persona.id);
            PersonaViews.paintLabelled(favorite, star, star ? "已收藏" : "收藏",
                PersonaViews.starDrawable(c, star, Ui.dp(c, 18)));
            favorite.setContentDescription((star ? "取消收藏 " : "收藏 ") + persona.nameZh);
            boolean registered = ownedPersonas.contains(persona.id);
            PersonaViews.paintLabelled(owned, registered, registered ? "已登录" : "未登录",
                PersonaViews.checkDrawable(c, registered, Ui.dp(c, 18)));
            owned.setContentDescription((registered ? "取消登录 " : "登录到全书 ") + persona.nameZh);
        };
        paint.run();
        favorite.setOnClickListener(v -> {
            toggleFavorite(persona);
            paint.run();
        });
        owned.setOnClickListener(v -> {
            prefs.setOwned(persona.id, !ownedPersonas.contains(persona.id));
            refreshChoices();
            paint.run();
        });
        toggles.addView(favorite, Ui.wrap());
        LinearLayout.LayoutParams ownedParams = Ui.wrap();
        ownedParams.leftMargin = Ui.dp(c, 10);
        toggles.addView(owned, ownedParams);
        hero.addView(toggles, top(14));
        String condition = PersonaViews.condition(data, persona);
        if (!condition.isEmpty()) hero.addView(PersonaViews.conditionLine(c, condition), top(14));
        if (persona.isDlc()) {
            TextView dlc = Ui.tag(c, "DLC · " + PersonaViews.dlcName(data, persona.dlc),
                Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170), Ui.col(c, R.color.p3r_cyan_200));
            hero.addView(dlc, topWrap(10));
        }
        return hero;
    }

    /** 「VI 恋爱」 on a cyan slanted plate. */
    private TextView arcanaTag(String arcanaId) {
        String number = PersonaViews.roman(data.arcana(arcanaId));
        String name = PersonaViews.arcanaName(data, arcanaId);
        TextView tag = Ui.tag(c, number.isEmpty() ? name : number + " " + name, Ui.col(c, R.color.p3r_cyan_400),
            Ui.col(c, R.color.p3r_navy_950));
        tag.setContentDescription(name);
        return tag;
    }

    /** 「洗牌时间」: one borderless row per tower block with its floors (「2–4、6–10F」). */
    private View origins(Compendium.Persona persona) {
        LinearLayout box = Ui.column(c);
        box.setTag("persona_origins_" + persona.id);
        box.addView(Ui.sectionHeader(c, "洗牌时间", null));
        boolean firstBlock = true;
        for (int i = 0; i < persona.origins.size();) {
            Compendium.Origin origin = persona.origins.get(i);
            StringBuilder floors = new StringBuilder();
            while (i < persona.origins.size()) {
                Compendium.Origin range = persona.origins.get(i);
                if (!origin.blockId.equals(range.blockId) || !origin.kind.equals(range.kind)) break;
                if (floors.length() > 0) floors.append("、");
                floors.append(range.floorFrom);
                if (range.floorTo != range.floorFrom) floors.append("–").append(range.floorTo);
                i++;
            }
            if (!firstBlock) box.addView(rule(), ruleParams());
            LinearLayout row = Ui.column(c);
            Ui.pad(row, 4, 6, 0, 6);
            row.addView(Ui.text(c, origin.blockNameZh, 15, Ui.col(c, R.color.p3r_white), true));
            row.addView(Ui.mixed(c, floors + "F", 15, Ui.col(c, R.color.p3r_cyan_300), false), top(2));
            box.addView(row);
            firstBlock = false;
        }
        return box;
    }

    /** One 能力值 as a slanted bar out of 99, like the game's status screen. */
    private View statRow(String label, int value) {
        LinearLayout row = Ui.row(c);
        TextView name = Ui.text(c, label, 14, Ui.col(c, R.color.p3r_ink_muted), true);
        // A minimum, not a fixed width: a large system font widens the column instead of clipping it.
        name.setMinWidth(Ui.dp(c, 22));
        row.addView(name, Ui.wrap());
        LinearLayout track = Ui.row(c);
        track.setBackground(new SlantedPanelDrawable().fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 160))
            .skew(Ui.dp(c, 6)));
        View fill = new View(c);
        fill.setBackground(new SlantedPanelDrawable()
            .gradient(Ui.col(c, R.color.p3r_royal_500), Ui.col(c, R.color.p3r_cyan_400)).skew(Ui.dp(c, 6)));
        int bounded = Math.max(0, Math.min(99, value));
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, bounded));
        track.addView(new View(c), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 99 - bounded));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(0, Ui.dp(c, 12), 1);
        trackParams.leftMargin = Ui.dp(c, 6);
        row.addView(track, trackParams);
        TextView number = Ui.numeral(c, String.valueOf(value), 20, Ui.col(c, R.color.p3r_white));
        number.setGravity(Gravity.END);
        row.addView(number, new LinearLayout.LayoutParams(Ui.dp(c, 40), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setContentDescription(label + " " + value);
        return row;
    }

    /** 弱点与耐性 as one band of 2 × 5 cells: 弱 on a cyan plate, the rest plain. */
    private View affinities(Compendium.Persona persona) {
        LinearLayout band = Ui.column(c);
        band.setBackground(Ui.band(c));
        Ui.pad(band, 12, 10, 12, 12);
        List<String> elements = data.elements();
        for (int start = 0; start < elements.size(); start += 5) {
            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.TOP);
            for (int i = start; i < Math.min(start + 5, elements.size()); i++) {
                String element = data.elementNameZh(elements.get(i));
                char code = i < persona.resists.length() ? persona.resists.charAt(i) : '-';
                LinearLayout cell = Ui.column(c);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.addView(Ui.text(c, element, 13, Ui.col(c, R.color.p3r_ink_muted), false), Ui.wrap());
                String label = data.resistanceLabel(code);
                TextView status;
                if (code == 'w') {
                    status = Ui.tag(c, label, Ui.col(c, R.color.p3r_cyan_400), Ui.col(c, R.color.p3r_navy_950));
                    status.setTextSize(16);
                } else if (code == '-') {
                    status = Ui.text(c, label, 16, Ui.col(c, R.color.p3r_ink_faint), false);
                } else {
                    status = Ui.text(c, label, 17, Ui.col(c, R.color.p3r_white), true);
                }
                LinearLayout.LayoutParams statusParams = Ui.wrap();
                statusParams.topMargin = Ui.dp(c, 4);
                cell.addView(status, statusParams);
                cell.setContentDescription(element + " " + (code == '-' ? "普通" : label));
                row.addView(cell, Ui.weight(1));
            }
            band.addView(row, top(start == 0 ? 0 : 10));
        }
        return band;
    }

    /**
     * A skill row on a fading rule: 「初始」/「Lv.3」, the name with its English name, the element
     * tag with target and power, the cost on the right, and the effect only when it says more.
     */
    private View skillRow(Compendium.Skill skill, String learned) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        Ui.pad(row, 0, 8, 0, 8);
        TextView when = learned.startsWith("Lv")
            ? Ui.numeral(c, learned, 17, Ui.col(c, R.color.p3r_cyan_400))
            : Ui.text(c, learned, 13, Ui.col(c, R.color.p3r_cyan_300), true);
        Ui.pad(when, 0, 3, 6, 0);
        // At least 46 dp so the names line up; a large system font widens it rather than splitting 「Lv.44」.
        when.setMinWidth(Ui.dp(c, 46));
        when.setMaxLines(1);
        row.addView(when, Ui.wrap());
        LinearLayout main = Ui.column(c);
        SpannableStringBuilder title = new SpannableStringBuilder();
        title.append(skill.nameZh, new StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.append(PersonaViews.bilingual(c, "", skill.nameEn, 0.75f));
        TextView name = Ui.text(c, title, 16, Ui.col(c, R.color.p3r_white), false);
        name.setMaxLines(2);
        main.addView(name);
        LinearLayout meta = Ui.row(c);
        String element = data.elementNameZh(skill.element);
        if (!element.isEmpty()) {
            TextView tag = Ui.tag(c, element, Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170), Ui.col(c, R.color.p3r_cyan_200));
            tag.setTextSize(12);
            LinearLayout.LayoutParams tagParams = Ui.wrap();
            tagParams.rightMargin = Ui.dp(c, 8);
            meta.addView(tag, tagParams);
        }
        List<String> parts = new ArrayList<>();
        if (!skill.targetZh.isEmpty() && !"自身".equals(skill.targetZh) && !"pas".equals(skill.element)) {
            parts.add(skill.targetZh);
        }
        if (skill.power > 0) parts.add("威力 " + skill.power);
        if (!parts.isEmpty()) {
            meta.addView(Ui.mixed(c, String.join(" · ", parts), 13, Ui.col(c, R.color.p3r_ink_muted), false), Ui.wrap());
        }
        if (meta.getChildCount() > 0) main.addView(meta, top(4));
        String effect = PersonaViews.effect(skill);
        if (!effect.isEmpty()) {
            main.addView(Ui.mixed(c, effect, 13, Ui.col(c, R.color.p3r_ink_muted), false), top(3));
        }
        row.addView(main, Ui.weight(1));
        String cost = PersonaViews.cost(skill);
        if (!cost.isEmpty()) {
            TextView price = Ui.numeral(c, cost, 16, Ui.col(c, R.color.p3r_white));
            Ui.pad(price, 8, 3, 0, 0);
            row.addView(price, Ui.wrap());
        }
        return row;
    }

    // ------------------------------------------------------------------ 合体

    private void renderFusion() {
        if (fusionContent == null) return;
        fusionContent.removeAllViews();
        LinearLayout pair = Ui.row(c);
        pair.setBaselineAligned(false);
        pair.addView(materialSlot(first, 1, this::chooseFirst),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView plus = Ui.numeral(c, "+", 34, Ui.col(c, R.color.p3r_cyan_400));
        plus.setGravity(Gravity.CENTER);
        plus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        pair.addView(plus, new LinearLayout.LayoutParams(Ui.dp(c, 30), ViewGroup.LayoutParams.MATCH_PARENT));
        pair.addView(materialSlot(second, 2, this::chooseSecond),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        fusionContent.addView(pair);
        String pairKey = first == null || second == null ? null : first.id + "+" + second.id;
        if (pairKey != null) {
            ImageView down = Ui.icon(c, R.drawable.ui_ic_down, Ui.col(c, R.color.p3r_cyan_400), 26);
            LinearLayout.LayoutParams downParams = (LinearLayout.LayoutParams) down.getLayoutParams();
            downParams.gravity = Gravity.CENTER_HORIZONTAL;
            downParams.topMargin = Ui.dp(c, 6);
            downParams.bottomMargin = Ui.dp(c, 4);
            fusionContent.addView(down, downParams);
            FusionCalculator.Recipe result = calculator.fuse(first.id, second.id);
            if (result != null) {
                View hero = resultHero(result);
                fusionContent.addView(hero);
                LinearLayout actions = Ui.row(c);
                TextView add = Ui.primary(c, "加入合体篮");
                add.setTag("fusion_add_basket");
                add.setOnClickListener(v -> addToBasket(result));
                actions.addView(add, Ui.weight(1));
                TextView use = Ui.secondary(c, "用它继续合体");
                use.setTag("fusion_continue");
                use.setOnClickListener(v -> openMaterialFusion(result.result));
                LinearLayout.LayoutParams useParams = Ui.weight(1);
                useParams.leftMargin = Ui.dp(c, 12);
                actions.addView(use, useParams);
                fusionContent.addView(actions, top(14));
                if (!pairKey.equals(shownResult)) Motion.swapIn(hero);
            } else {
                TextView none = Ui.text(c, "这两个合不出人格面具", 16, Ui.col(c, R.color.p3r_ink_muted), true);
                none.setGravity(Gravity.CENTER);
                none.setBackground(Ui.band(c));
                Ui.pad(none, 16, 18, 16, 18);
                fusionContent.addView(none, top(2));
            }
        }
        shownResult = pairKey;
        LinearLayout target = Ui.row(c);
        target.setTag("fusion_target");
        target.setBackground(Ui.band(c).pressedFill(Ui.col(c, R.color.ui_cyan_soft)));
        target.setClickable(true);
        target.setFocusable(true);
        target.setMinimumHeight(Ui.dp(c, 56));
        Ui.pad(target, 20, 10, 14, 10);
        target.addView(Ui.text(c, "想合出谁？", 17, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
        target.addView(Ui.icon(c, R.drawable.ui_ic_chevron, Ui.col(c, R.color.p3r_cyan_300), 20));
        target.setContentDescription("想合出谁？查配方");
        target.setOnClickListener(v -> pickTargetThenRecipes());
        fusionContent.addView(target, top(28));
    }

    /** A material plate: empty, a big 「+」 and 「选人格面具」; filled, the Persona with an info link. */
    private View materialSlot(Compendium.Persona persona, int index, Runnable onPick) {
        LinearLayout slot = Ui.column(c);
        slot.setTag("fusion_material_" + index);
        slot.setBackground(Ui.pressableCard(c).skew(Ui.dp(c, 8)));
        slot.setClickable(true);
        slot.setFocusable(true);
        slot.setMinimumHeight(Ui.dp(c, 116));
        Ui.pad(slot, 16, 4, 4, 12);
        LinearLayout heading = Ui.row(c);
        heading.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        heading.addView(Ui.text(c, "素材 " + index, 13, Ui.col(c, R.color.p3r_cyan_300), true), Ui.weight(1));
        if (persona != null) {
            ImageView info = Ui.icon(c, R.drawable.ui_ic_info, Ui.col(c, R.color.p3r_cyan_300), 18);
            info.setScaleType(ImageView.ScaleType.CENTER);
            info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            info.setContentDescription(persona.nameZh + "的资料");
            info.setTag("fusion_material_detail_" + index);
            info.setOnClickListener(v -> openDetail(persona));
            info.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_soft)));
            heading.addView(info, new LinearLayout.LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        }
        slot.addView(heading);
        if (persona == null) {
            TextView plus = Ui.numeral(c, "+", 40, Ui.col(c, R.color.p3r_cyan_400));
            plus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            slot.addView(plus, Ui.wrap());
            slot.addView(Ui.text(c, "选人格面具", 15, Ui.col(c, R.color.p3r_ice_100), true), top(2));
            slot.setContentDescription("素材 " + index + "：选人格面具");
        } else {
            TextView name = Ui.text(c, persona.nameZh, 18, Ui.col(c, R.color.p3r_white), true);
            name.setMaxLines(2);
            slot.addView(name, top(2));
            TextView english = Ui.text(c, Ui.latin(c, persona.nameEn), 12, Ui.col(c, R.color.p3r_ink_muted), false);
            english.setMaxLines(1);
            english.setEllipsize(TextUtils.TruncateAt.END);
            slot.addView(english);
            slot.addView(Ui.mixed(c, PersonaViews.arcanaLevel(data, persona), 13, Ui.col(c, R.color.p3r_cyan_300), false), top(4));
        }
        slot.setOnClickListener(v -> onPick.run());
        return slot;
    }

    /** The fusion's result: the one hero of the pane; tapping it opens the Persona. */
    private View resultHero(FusionCalculator.Recipe recipe) {
        Compendium.Persona persona = recipe.result;
        LinearLayout hero = Ui.column(c);
        hero.setTag("persona_" + persona.id);
        hero.setBackground(PersonaViews.withWatermark(c, Ui.heroCard(c), PersonaViews.arcanaWord(data, persona.arcana)));
        hero.setClickable(true);
        hero.setFocusable(true);
        Ui.pad(hero, 22, 14, 18, 16);
        LinearLayout kicker = Ui.row(c);
        TextView result = Ui.numeral(c, "RESULT", 14, Ui.col(c, R.color.p3r_cyan_400));
        result.setLetterSpacing(0.16f);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        kicker.addView(result, Ui.weight(1));
        if (recipe.special) {
            kicker.addView(Ui.tag(c, "特殊合体", Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_navy_950)), Ui.wrap());
        }
        hero.addView(kicker);
        LinearLayout line = Ui.row(c);
        line.setGravity(Gravity.BOTTOM);
        LinearLayout identity = Ui.column(c);
        TextView name = Ui.text(c, persona.nameZh, 28, Ui.col(c, R.color.p3r_white), true);
        name.setShadowLayer(0.01f, Ui.dp(c, 2), Ui.dp(c, 2), Ui.alpha(Ui.col(c, R.color.p3r_royal_500), 200));
        identity.addView(name);
        identity.addView(Ui.text(c, Ui.latin(c, persona.nameEn), 14, Ui.col(c, R.color.p3r_ink_muted), false));
        line.addView(identity, Ui.weight(1));
        line.addView(PersonaViews.level(c, persona.level, 44, Ui.col(c, R.color.p3r_cyan_400), 0.42f), Ui.wrap());
        hero.addView(line, top(4));
        hero.addView(arcanaTag(persona.arcana), topWrap(10));
        String condition = PersonaViews.condition(data, persona);
        if (!condition.isEmpty()) hero.addView(PersonaViews.conditionLine(c, condition), top(12));
        hero.setContentDescription("合体结果：" + persona.nameZh + "，" + PersonaViews.arcanaLevel(data, persona)
            + (recipe.special ? "，特殊合体" : "") + (condition.isEmpty() ? "" : "，合体条件：" + condition));
        hero.setOnClickListener(v -> openDetail(persona));
        return hero;
    }

    private void chooseFirst() {
        pickPersona("选素材", second == null ? null : second.id, second, second == null, value -> {
            first = value;
            renderFusion();
            if (second == null) chooseSecond();
        });
    }

    private void chooseSecond() {
        pickPersona("选素材", first == null ? null : first.id, first, first == null, value -> {
            second = value;
            renderFusion();
            if (first == null) chooseFirst();
        });
    }

    /** 想合出谁？: pick the target, then its recipes. */
    private void pickTargetThenRecipes() {
        pickPersona("想合出谁？", null, null, value -> {
            browseGoal = value;
            openRecipes(value, false);
        });
    }

    private void openMaterialFusion(Compendium.Persona persona) {
        hideKeyboard();
        if (!calculator.isAvailable(persona)) {
            openDlc();
            return;
        }
        first = persona;
        second = null;
        renderFusion();
        host.returnToPage(root);
        selectSegment(1);
        chooseSecond();
    }

    // ------------------------------------------------------------------ recipes

    private void openRecipes(Compendium.Persona persona, boolean replace) {
        hideKeyboard();
        if (browseGoal == null) browseGoal = persona;
        LinearLayout content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 8, Ui.GUTTER, 32);
        if (!browseGoal.id.equals(persona.id)) {
            Compendium.Persona originalGoal = browseGoal;
            TextView goal = Ui.text(c, "‹ " + originalGoal.nameZh + "的配方", 14, Ui.col(c, R.color.p3r_cyan_200), true);
            goal.setTag("fusion_original_goal");
            goal.setGravity(Gravity.CENTER_VERTICAL);
            goal.setMinHeight(Ui.dp(c, Ui.TOUCH));
            goal.setBackground(new InsetDrawable(new SlantedPanelDrawable().fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 140))
                .pressedFill(Ui.col(c, R.color.ui_cyan_soft))
                .stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 150), Ui.dp(c, 1)).skew(Ui.dp(c, 6)),
                0, Ui.dp(c, 4), 0, Ui.dp(c, 4)));
            Ui.pad(goal, 12, 4, 14, 4);
            goal.setClickable(true);
            goal.setFocusable(true);
            goal.setContentDescription("回到" + originalGoal.nameZh + "的配方");
            goal.setOnClickListener(v -> openRecipes(originalGoal, true));
            content.addView(goal, Ui.wrap());
        }
        content.addView(targetStrip(persona, v -> openDetail(persona, true)), top(content.getChildCount() == 0 ? 0 : 6));
        LinearLayout recipes = Ui.column(c);
        content.addView(recipes, top(14));
        loadRecipes(persona, recipes, null, null);
        String title = persona.nameZh + " · 合体配方";
        if (replace) host.replaceFillingPage(title, scroll(content));
        else host.pushFillingPage(title, scroll(content));
    }

    private void openRecipeSelector(Compendium.Persona persona, Consumer<FusionCalculator.Recipe> chosen) {
        hideKeyboard();
        LinearLayout content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 8, Ui.GUTTER, 32);
        content.addView(targetStrip(persona, v -> openDetail(persona)));
        LinearLayout choices = Ui.column(c);
        content.addView(choices, top(14));
        FusionBasket.Choice current = prefs.basket().chosenRecipe(persona.id);
        loadRecipes(persona, choices, recipe -> {
            host.returnToPage(root);
            selectSegment(2);
            chosen.accept(recipe);
            refreshChoices();
        }, current);
        host.pushFillingPage("选" + persona.nameZh + "的配方", scroll(content));
    }

    /** The recipe page's target: level, name, English and arcana; tapping opens the Persona. */
    private View targetStrip(Compendium.Persona persona, View.OnClickListener onClick) {
        LinearLayout strip = Ui.row(c);
        strip.setTag("persona_" + persona.id);
        strip.setBackground(Ui.band(c).pressedFill(Ui.col(c, R.color.ui_cyan_soft)));
        strip.setClickable(true);
        strip.setFocusable(true);
        Ui.pad(strip, 18, 10, 12, 10);
        strip.addView(PersonaViews.level(c, persona.level, 30, Ui.col(c, R.color.p3r_cyan_400), 0.45f), Ui.wrap());
        LinearLayout identity = Ui.column(c);
        identity.addView(Ui.text(c, persona.nameZh, 19, Ui.col(c, R.color.p3r_white), true));
        identity.addView(Ui.text(c, Ui.latin(c, persona.nameEn + " · " + PersonaViews.arcanaName(data, persona.arcana)),
            12, Ui.col(c, R.color.p3r_ink_muted), false));
        LinearLayout.LayoutParams identityParams = Ui.weight(1);
        identityParams.leftMargin = Ui.dp(c, 14);
        strip.addView(identity, identityParams);
        strip.addView(Ui.icon(c, R.drawable.ui_ic_chevron, Ui.col(c, R.color.p3r_cyan_300), 18));
        strip.setContentDescription(persona.nameZh + "，" + persona.nameEn + "，" + PersonaViews.arcanaLevel(data, persona));
        strip.setOnClickListener(onClick);
        return strip;
    }

    private void loadRecipes(Compendium.Persona persona, LinearLayout container,
                             Consumer<FusionCalculator.Recipe> chosen, FusionBasket.Choice current) {
        if (!calculator.isAvailable(persona)) {
            Runnable[] reload = {() -> {
                container.removeAllViews();
                loadRecipes(persona, container, chosen, current);
            }};
            container.addView(dlcPrompt(persona, reload));
            return;
        }
        TextView loading = Ui.muted(c, "找配方中…");
        Ui.pad(loading, 4, 8, 4, 8);
        container.addView(loading);
        FusionCalculator currentCalculator = calculator;
        Async.run(host.activity(), () -> currentCalculator.recipesFor(persona.id), recipes -> {
            container.removeAllViews();
            if (currentCalculator != calculator) {
                loadRecipes(persona, container, chosen, current);
                return;
            }
            if (recipes.isEmpty()) {
                container.addView(noRecipes(persona));
                return;
            }
            container.addView(Ui.sectionHeader(c, "配方", recipes.size() + " 组"));
            appendRecipes(container, recipes, 0, chosen, current);
            Motion.cascade(container);
        }, error -> {
            container.removeAllViews();
            container.addView(Ui.text(c, "配方没算出来", 16, Ui.col(c, R.color.p3r_white), true));
            TextView retry = Ui.secondary(c, "重试");
            retry.setOnClickListener(v -> {
                container.removeAllViews();
                loadRecipes(persona, container, chosen, current);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(c, 132), ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = Ui.dp(c, 10);
            container.addView(retry, p);
        });
    }

    /** No recipe: where the Persona comes from instead. */
    private View noRecipes(Compendium.Persona persona) {
        String condition = PersonaViews.condition(data, persona);
        if (persona.origins.isEmpty() && !condition.isEmpty()) return PersonaViews.conditionLine(c, condition);
        TextView text = Ui.text(c, persona.origins.isEmpty() ? "合不出来" : "合不出来，去塔尔塔罗斯的洗牌时间找它", 16,
            Ui.col(c, R.color.p3r_white), true);
        Ui.pad(text, 4, 4, 4, 4);
        return text;
    }

    private void appendRecipes(LinearLayout container, List<FusionCalculator.Recipe> recipes, int from,
                               Consumer<FusionCalculator.Recipe> chosen, FusionBasket.Choice current) {
        int end = Math.min(from + RECIPE_PAGE, recipes.size());
        for (int i = from; i < end; i++) {
            FusionCalculator.Recipe recipe = recipes.get(i);
            if (i > 0) container.addView(rule(), ruleParams());
            container.addView(recipeBlock(recipe, i, chosen, current));
        }
        if (end < recipes.size()) {
            TextView more = Ui.quiet(c, "还有 " + (recipes.size() - end) + " 组，接着看");
            more.setOnClickListener(v -> {
                container.removeView(more);
                appendRecipes(container, recipes, end, chosen, current);
            });
            container.addView(more, top(10));
        }
        if (chosen == null) paintBasketMarks(container, prefs.basket());
    }

    /**
     * One recipe, borderless: its tags (特殊合体, 素材都已登录, 现在用的 in the selector, 在合体篮里
     * while browsing), one line per ingredient and, while browsing, a 「＋」 plate that adds it to
     * the basket without leaving. In the selector the whole block chooses the recipe.
     */
    private View recipeBlock(FusionCalculator.Recipe recipe, int index, Consumer<FusionCalculator.Recipe> chosen,
                             FusionBasket.Choice current) {
        boolean selector = chosen != null;
        LinearLayout block = Ui.row(c);
        Ui.pad(block, 0, 8, 0, 8);
        LinearLayout lines = Ui.column(c);
        LinearLayout tags = Ui.row(c);
        if (recipe.special) {
            tags.addView(Ui.tag(c, "特殊合体", Ui.col(c, R.color.p3r_cyan_400), Ui.col(c, R.color.p3r_navy_950)), tagGap());
        }
        boolean allRegistered = true;
        for (Compendium.Persona ingredient : recipe.ingredients) allRegistered &= ownedPersonas.contains(ingredient.id);
        if (allRegistered) {
            tags.addView(Ui.tag(c, "素材都已登录", Ui.col(c, R.color.ui_cyan_soft), Ui.col(c, R.color.p3r_cyan_200)), tagGap());
        }
        if (sameRecipe(current, recipe)) {
            tags.addView(Ui.tag(c, "现在用的", Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_navy_950)), tagGap());
        } else if (!selector) {
            BasketMark mark = new BasketMark(c, recipe);
            mark.setTag("recipe_in_basket_" + recipe.result.id + "_" + index);
            tags.addView(mark, tagGap());
        }
        lines.addView(tags, bottom(4));
        BasketMark.showRowIfAny(tags);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < recipe.ingredients.size(); i++) {
            Compendium.Persona ingredient = recipe.ingredients.get(i);
            if (names.length() > 0) names.append(" + ");
            names.append(ingredient.nameZh);
            lines.addView(ingredientLine(ingredient, i, !selector));
        }
        block.addView(lines, Ui.weight(1));
        String tag = "recipe_add_" + recipe.result.id + "_" + index;
        if (!selector) {
            ImageView add = new ImageView(c);
            add.setTag(tag);
            add.setContentDescription("把 " + names + " 加入合体篮");
            add.setImageDrawable(Ui.tinted(c, R.drawable.ui_ic_plus, Ui.col(c, R.color.p3r_cyan_400)));
            add.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            Ui.pad(add, 14, 14, 14, 14);
            add.setBackground(new SlantedPanelDrawable().fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 120))
                .pressedFill(Ui.col(c, R.color.ui_cyan_soft))
                .stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 170), Ui.dp(c, 1.2f)).skew(Ui.dp(c, 6)));
            add.setClickable(true);
            add.setFocusable(true);
            add.setOnClickListener(v -> addToBasket(recipe));
            Motion.pressable(add);
            LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52));
            addParams.leftMargin = Ui.dp(c, 8);
            addParams.gravity = Gravity.CENTER_VERTICAL;
            block.addView(add, addParams);
        } else {
            block.setTag(tag);
            block.setClickable(true);
            block.setFocusable(true);
            block.setBackground(new PersonaViews.CursorDrawable(c));
            block.setContentDescription("选这个配方：" + names);
            TextView pick = Ui.text(c, "选", 15, Ui.col(c, R.color.p3r_navy_950), true);
            pick.setGravity(Gravity.CENTER);
            pick.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_cyan_400)).skew(Ui.dp(c, 5)));
            Ui.pad(pick, 14, 6, 14, 6);
            LinearLayout.LayoutParams pickParams = Ui.wrap();
            pickParams.leftMargin = Ui.dp(c, 8);
            pickParams.gravity = Gravity.CENTER_VERTICAL;
            block.addView(pick, pickParams);
            block.setOnClickListener(v -> chosen.accept(recipe));
        }
        return block;
    }

    /**
     * 「+ 皮克希  Pixie」 over 「恋爱 Lv.2 · 已登录」; while browsing, tapping it opens that
     * ingredient's recipes in place.
     */
    private View ingredientLine(Compendium.Persona ingredient, int index, boolean link) {
        LinearLayout line = Ui.row(c);
        line.setMinimumHeight(Ui.dp(c, 48));
        Ui.pad(line, 0, 4, 4, 4);
        TextView plus = Ui.numeral(c, index == 0 ? "" : "+", 17, Ui.col(c, R.color.p3r_cyan_400));
        plus.setGravity(Gravity.CENTER);
        plus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(plus, new LinearLayout.LayoutParams(Ui.dp(c, 20), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout words = Ui.column(c);
        TextView name = Ui.text(c, PersonaViews.bilingual(c, ingredient.nameZh, ingredient.nameEn, 0.75f), 16,
            Ui.col(c, R.color.p3r_white), false);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        LinearLayout meta = Ui.row(c);
        meta.addView(Ui.mixed(c, PersonaViews.arcanaLevel(data, ingredient), 13, Ui.col(c, R.color.p3r_cyan_300), false),
            Ui.wrap());
        PersonaViews.OwnedMark owned = new PersonaViews.OwnedMark(c);
        owned.show(ingredient.id, ownedPersonas.contains(ingredient.id));
        LinearLayout.LayoutParams ownedParams = Ui.wrap();
        ownedParams.leftMargin = Ui.dp(c, 8);
        meta.addView(owned, ownedParams);
        words.addView(meta, top(1));
        line.addView(words, Ui.weight(1));
        line.setContentDescription(ingredient.nameZh + "，" + ingredient.nameEn + "，" + PersonaViews.arcanaLevel(data, ingredient));
        if (link) {
            line.addView(Ui.icon(c, R.drawable.ui_ic_chevron, Ui.col(c, R.color.p3r_cyan_300), 16));
            line.setTag("material_recipe_" + ingredient.id);
            line.setClickable(true);
            line.setFocusable(true);
            line.setBackground(new PersonaViews.CursorDrawable(c));
            line.setOnClickListener(v -> openRecipes(ingredient, true));
        }
        return line;
    }

    // ------------------------------------------------------------------ picker

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
            sheet.addView(Ui.text(c, "和 " + partner.nameZh + " 合体", 14, Ui.col(c, R.color.p3r_ink_muted), false), bottom(2));
        }
        LinearLayout searchRow = Ui.row(c);
        EditText search = search("名字、英文名或技能", "fusion_picker_search");
        activePickerSearch = search;
        searchRow.addView(searchBox(search), Ui.weight(1));
        TextView favorites = CompendiumControls.icon(c, "只看收藏", R.drawable.ui_ic_star);
        favorites.setTag("fusion_picker_favorites");
        searchRow.addView(favorites, gapWrap(8));
        sheet.addView(searchRow, top(8));
        ListView results = personaList();
        results.setTag("fusion_picker_list");
        Ui.pad(results, 0, 4, 0, 8);
        PersonaAdapter choices = new PersonaAdapter(false, value -> {
            if (!keepSheet) {
                hideKeyboard();
                host.dismissSheet();
            }
            onSelected.accept(value);
        });
        results.setAdapter(choices);
        TextView noMatch = Ui.text(c, "", 14, Ui.col(c, R.color.p3r_ink_muted), false);
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
            choices.setItems(matches, false);
            CompendiumControls.setToolActive(favorites, favoriteScope[0]);
            noMatch.setText(favoriteScope[0] && favoriteIds.isEmpty() ? "还没有收藏" : "没有符合的人格面具");
            noMatch.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
            results.setSelection(0);
        };
        favorites.setOnClickListener(v -> { favoriteScope[0] = !favoriteScope[0]; filter.run(); });
        search.addTextChangedListener(watcher(text -> filter.run()));
        int maxDp = partner == null ? 360 : 330;
        int screenDp = Math.round(c.getResources().getDisplayMetrics().heightPixels
            / c.getResources().getDisplayMetrics().density);
        int rowsDp = Math.max(180, Math.min(maxDp, screenDp - (partner == null ? 330 : 360)));
        sheet.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, rowsDp)));
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

    // ------------------------------------------------------------------ choices

    private void toggleFavorite(Compendium.Persona persona) {
        boolean favorite = !favoriteIds.contains(persona.id);
        prefs.setFavorite(persona.id, favorite);
        if (favorite) favoriteIds.add(persona.id); else favoriteIds.remove(persona.id);
        refreshList(false);
    }

    /**
     * Adds the recipe as a basket goal and stays on the page: 「已加入合体篮」 · 去看看. When the
     * basket already makes this Persona another way, that recipe and the sub-recipes under it
     * give way, so 「已换成这个配方」 offers 撤销.
     */
    private void addToBasket(FusionCalculator.Recipe recipe) {
        FusionBasket before = prefs.basket();
        FusionBasket basket = prefs.basket();
        FusionBasket.Choice old = basket.chosenRecipe(recipe.result.id);
        if (sameRecipe(old, recipe) && basket.containsGoal(recipe.result.id)) {
            PersonaViews.announce(host, ALREADY_ADDED, "去看看", this::showBasket);
            return;
        }
        if (!basket.addGoal(recipe)) {
            host.showError(CYCLE);
            return;
        }
        prefs.setBasket(basket);
        refreshChoices();
        if (old != null && !sameRecipe(old, recipe)) {
            PersonaViews.announce(host, SWAPPED, "撤销", () -> {
                prefs.setBasket(before);
                refreshChoices();
            });
        } else {
            PersonaViews.announce(host, ADDED, "去看看", this::showBasket);
        }
    }

    private void showBasket() {
        host.returnToPage(root);
        selectSegment(2);
    }

    /** Whether the basket's choice for a Persona is this recipe (the same ingredients). */
    private static boolean sameRecipe(FusionBasket.Choice choice, FusionCalculator.Recipe recipe) {
        if (choice == null || !choice.resultId.equals(recipe.result.id)) return false;
        Set<String> ids = new HashSet<>();
        for (Compendium.Persona ingredient : recipe.ingredients) ids.add(ingredient.id);
        return new HashSet<>(choice.ingredientIds).equals(ids);
    }

    private void refreshChoices() {
        ownedPersonas = new HashSet<>(prefs.ownedPersonas());
        refreshList(false);
        refreshBasketBadge();
        if (basketPage != null) basketPage.refresh();
        renderFusion();
        View window = host.activity().getWindow().getDecorView();
        paintOwned(window);
        paintBasketMarks(window, prefs.basket());
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
            if (basketPage != null) basketPage.setCalculator(calculator);
            paintDlcPlate();
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
        paintBasketMarks(host.activity().getWindow().getDecorView(), prefs.basket());
    }

    /** 「合体篮 2」; the segment pops when a goal was added. */
    private void refreshBasketBadge() {
        if (basketSegment == null) return;
        int goals = prefs.basket().goals().size();
        basketSegment.setText(goals == 0 ? "合体篮" : PersonaViews.withCount(c, "合体篮", goals));
        basketSegment.setContentDescription(goals == 0 ? "合体篮" : "合体篮，" + goals + " 个目标");
        if (basketGoals >= 0 && goals > basketGoals && Ui.animationsEnabled()) {
            basketSegment.animate().cancel();
            basketSegment.setScaleX(1.12f);
            basketSegment.setScaleY(1.12f);
            basketSegment.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(Motion.SETTLE).start();
        }
        basketGoals = goals;
    }

    private void paintOwned(View view) {
        if (view instanceof PersonaViews.OwnedMark) {
            PersonaViews.OwnedMark mark = (PersonaViews.OwnedMark) view;
            if (mark.personaId != null) mark.show(mark.personaId, ownedPersonas.contains(mark.personaId));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) paintOwned(group.getChildAt(i));
        }
    }

    private static void paintBasketMarks(View view, FusionBasket basket) {
        if (view instanceof BasketMark) {
            ((BasketMark) view).show(basket);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) paintBasketMarks(group.getChildAt(i), basket);
        }
    }

    /** 「在合体篮里」 on a browsed recipe while the basket makes its result this way. */
    private static final class BasketMark extends FrameLayout {
        private final FusionCalculator.Recipe recipe;

        BasketMark(Context c, FusionCalculator.Recipe recipe) {
            super(c);
            this.recipe = recipe;
            addView(Ui.tag(c, "在合体篮里", Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_navy_950)));
            setVisibility(GONE);
        }

        void show(FusionBasket basket) {
            setVisibility(sameRecipe(basket.chosenRecipe(recipe.result.id), recipe) ? VISIBLE : GONE);
            if (getParent() instanceof ViewGroup) showRowIfAny((ViewGroup) getParent());
        }

        /** A tag row takes no room once its last tag is hidden. */
        static void showRowIfAny(ViewGroup row) {
            boolean any = false;
            for (int i = 0; i < row.getChildCount(); i++) any |= row.getChildAt(i).getVisibility() == VISIBLE;
            row.setVisibility(any ? VISIBLE : GONE);
        }
    }

    // ------------------------------------------------------------------ list plumbing

    private final class PersonaAdapter extends BaseAdapter implements PersonaViews.PersonaRow.Callbacks {
        private final boolean catalogue;
        private final Consumer<Compendium.Persona> onClick;
        private List<Compendium.Persona> items = Collections.emptyList();
        private boolean grouped;

        PersonaAdapter(boolean catalogue, Consumer<Compendium.Persona> onClick) {
            this.catalogue = catalogue;
            this.onClick = onClick;
        }

        void setItems(List<Compendium.Persona> values, boolean byArcana) {
            items = values;
            grouped = byArcana;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Compendium.Persona getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            PersonaViews.PersonaRow row = convertView instanceof PersonaViews.PersonaRow
                ? (PersonaViews.PersonaRow) convertView : new PersonaViews.PersonaRow(c, catalogue, this);
            Compendium.Persona persona = getItem(position);
            boolean header = grouped && (position == 0 || !items.get(position - 1).arcana.equals(persona.arcana));
            row.bind(data, persona, calculator.isAvailable(persona), favoriteIds.contains(persona.id),
                ownedPersonas.contains(persona.id), header, grouped);
            return row;
        }

        @Override public void open(Compendium.Persona persona) { onClick.accept(persona); }
        @Override public void favorite(Compendium.Persona persona) { toggleFavorite(persona); }
    }

    private ListView personaList() {
        ListView result = new ListView(c);
        GradientDrawable divider = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 90), Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 0)});
        result.setDivider(divider);
        result.setDividerHeight(Math.max(1, Ui.dp(c, 1)));
        result.setSelector(android.R.color.transparent);
        result.setClipToPadding(false);
        result.setVerticalScrollBarEnabled(false);
        Ui.pad(result, Ui.GUTTER, 2, Ui.GUTTER, 24);
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
        result.setVerticalScrollBarEnabled(false);
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
        result.setTextSize(15);
        result.setBackground(new SlantedPanelDrawable().fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170))
            .stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 120), Ui.dp(c, 1)).skew(Ui.dp(c, 8)));
        result.setMinHeight(Ui.dp(c, Ui.TOUCH));
        Ui.pad(result, 16, 8, 12, 8);
        return result;
    }

    private View searchBox(EditText field) {
        FrameLayout box = new FrameLayout(c);
        Ui.pad(field, 18, 8, 52, 8);
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
        EditText result = search("", tag);
        result.setInputType(InputType.TYPE_CLASS_NUMBER);
        result.setTypeface(Ui.numerals(c));
        result.setTextSize(20);
        result.setGravity(Gravity.CENTER);
        Ui.pad(result, 12, 6, 12, 6);
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

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        View focus = host.activity().getCurrentFocus();
        if (focus == null) focus = host.activity().getWindow().getDecorView().findFocus();
        if (keyboard != null && focus != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        if (focus != null) focus.clearFocus();
        root.requestFocus();
    }

    private View rule() {
        return Ui.fadingRule(c);
    }

    private LinearLayout.LayoutParams ruleParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Math.max(1, Ui.dp(c, 1)));
        p.topMargin = Ui.dp(c, 2);
        p.bottomMargin = Ui.dp(c, 2);
        return p;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams result = Ui.matchWrap();
        result.topMargin = Ui.dp(c, dp);
        return result;
    }

    private LinearLayout.LayoutParams topWrap(int dp) {
        LinearLayout.LayoutParams result = Ui.wrap();
        result.topMargin = Ui.dp(c, dp);
        return result;
    }

    private LinearLayout.LayoutParams bottom(int dp) {
        LinearLayout.LayoutParams result = Ui.matchWrap();
        result.bottomMargin = Ui.dp(c, dp);
        return result;
    }

    private LinearLayout.LayoutParams tagGap() {
        LinearLayout.LayoutParams result = Ui.wrap();
        result.rightMargin = Ui.dp(c, 6);
        return result;
    }

    private LinearLayout.LayoutParams gapWrap(int dp) {
        LinearLayout.LayoutParams result = Ui.wrap();
        result.leftMargin = Ui.dp(c, dp);
        return result;
    }

    private LinearLayout.LayoutParams gapWeight(int dp) {
        LinearLayout.LayoutParams result = Ui.weight(1);
        result.leftMargin = Ui.dp(c, dp);
        return result;
    }

    private LinearLayout.LayoutParams weightedGap() {
        return gapWeight(8);
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
