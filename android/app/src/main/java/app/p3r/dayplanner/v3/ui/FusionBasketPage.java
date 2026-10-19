package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.compendium.Compendium;
import app.p3r.dayplanner.v3.compendium.CompendiumPrefs;
import app.p3r.dayplanner.v3.compendium.FusionBasket;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;

/** A persistent workbench around the existing calculator's recipes. */
public final class FusionBasketPage {
    public interface Actions {
        void openPersona(String personaId);
        void chooseRecipe(String targetId, Consumer<FusionCalculator.Recipe> chosen);
        void onChanged();
        default void browseRecipes() {}
    }

    private static final String[] SORT_LABELS = {"等级", "阿尔卡那"};
    private static final String[] SORT_TAGS = {"level", "arcana"};
    private final HomeHost host;
    private final Context c;
    private final Compendium data;
    private final CompendiumPrefs prefs;
    private final Actions actions;
    private final LinearLayout root;
    private final LinearLayout header;
    private final ScrollView scroll;
    private final LinearLayout content;
    private FusionCalculator calculator;
    private FusionBasket basket;
    private FusionBasket.Summary summary;
    private Set<String> owned;
    private FusionBasket clearedBasket;
    private int order;
    private boolean descending;
    private int refreshGeneration;

    public FusionBasketPage(HomeHost host, Compendium data, FusionCalculator calculator,
                            CompendiumPrefs prefs, Actions actions) {
        this.host = host;
        c = host.activity();
        this.data = data;
        this.calculator = calculator;
        this.prefs = prefs;
        this.actions = actions;
        root = Ui.column(c);
        root.setTag("basket_page");
        header = Ui.column(c);
        Ui.pad(header, Ui.GUTTER, 0, Ui.GUTTER, 4);
        root.addView(header);
        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 4, Ui.GUTTER, 28);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        refresh();
    }

    public View view() { return root; }

    public void setCalculator(FusionCalculator calculator) {
        this.calculator = calculator;
        refresh();
    }

    /** Keeps the workspace, sort and scroll position when a material lookup returns. */
    public void refresh() {
        refreshGeneration++;
        int y = scroll.getScrollY();
        basket = prefs.basket();
        owned = new HashSet<>(prefs.ownedPersonas());
        summary = basket.summarise(data, owned, calculator);
        header.removeAllViews();
        content.removeAllViews();
        if (basket.goals().isEmpty()) {
            renderEmpty();
        } else {
            renderHeader();
            renderGoals();
            renderMaterials();
            renderSteps();
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void renderEmpty() {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.heroCard(c));
        Ui.pad(card, 22, 26, 22, 24);
        card.addView(Ui.numeral(c, "FUSION", 34, color(R.color.p3r_cyan_300)));
        card.addView(Ui.title(c, "还没有合成目标"), top(12));
        card.addView(Ui.body(c, "选好配方，加入合成篮"), top(8));
        TextView browse = Ui.primary(c, "去查配方");
        browse.setTag("basket_browse");
        browse.setOnClickListener(v -> actions.browseRecipes());
        card.addView(browse, top(22));
        if (clearedBasket != null) {
            TextView undo = Ui.quiet(c, "撤销清空");
            undo.setTag("basket_undo_clear");
            undo.setOnClickListener(v -> {
                prefs.setBasket(clearedBasket);
                clearedBasket = null;
                changed();
            });
            card.addView(undo, top(10));
        }
        content.addView(card, top(8));
    }

    private void renderHeader() {
        LinearLayout hero = Ui.row(c);
        hero.setTag("basket_summary");
        hero.setBackground(Ui.card(c));
        Ui.pad(hero, 12, 2, 6, 2);
        LinearLayout metrics = Ui.row(c);
        metrics.addView(metric("目标", basket.goals().size(), "basket_goal_count"), Ui.weight(1));
        metrics.addView(metric("召唤", count(summary.shopping), "basket_summon_count"), Ui.weight(1));
        metrics.addView(metric("未登录", unregisteredCount(), "basket_missing_count"), Ui.weight(1));
        hero.addView(metrics, Ui.weight(1));
        TextView clear = Ui.quiet(c, "清空");
        clear.setTextSize(13);
        Ui.pad(clear, 10, 6, 10, 6);
        clear.setTag("basket_clear");
        clear.setOnClickListener(v -> {
            clearedBasket = prefs.basket();
            prefs.setBasket(new FusionBasket());
            changed();
            host.showMessage("已清空合成篮");
        });
        hero.addView(clear, Ui.wrap());
        header.addView(hero);

    }

    private View metric(String label, int value, String tag) {
        LinearLayout cell = Ui.row(c);
        cell.addView(Ui.caption(c, label));
        TextView number = Ui.numeral(c, Integer.toString(value), 22, color(R.color.p3r_cyan_300));
        number.setTag(tag);
        cell.addView(number, gapWrap(5));
        return cell;
    }

    private void renderGoals() {
        List<FusionBasket.Node> roots = new ArrayList<>(summary.roots);
        roots.sort((a, b) -> personaOrder().compare(a.persona, b.persona));
        HorizontalScrollView targetStrip = new HorizontalScrollView(c);
        targetStrip.setHorizontalScrollBarEnabled(false);
        targetStrip.setClipToPadding(false);
        LinearLayout targets = Ui.row(c);
        targets.setGravity(Gravity.TOP);
        targetStrip.addView(targets);
        int screenWidth = c.getResources().getDisplayMetrics().widthPixels;
        int cardWidth = roots.size() == 1 ? screenWidth - Ui.dp(c, Ui.GUTTER * 2)
            : Math.min(Ui.dp(c, 340), screenWidth - Ui.dp(c, Ui.GUTTER * 2 + 16));
        for (FusionBasket.Node node : roots) {
            Compendium.Persona persona = node.persona;
            LinearLayout card = Ui.column(c);
            card.setBackground(Ui.card(c));
            Ui.pad(card, 12, 6, 8, 6);
            LinearLayout row = Ui.row(c);
            LinearLayout title = identity(persona);
            title.setOnClickListener(v -> actions.openPersona(persona.id));
            boolean registered = node.ownedQuantity >= node.quantity;
            TextView metadata = (TextView) title.getChildAt(1);
            metadata.setText(arcanaName(persona.arcana) + " · Lv." + persona.level + " · "
                + (node.invalidRecipe ? "重选配方" : node.craftQuantity > 0 ? "待合成"
                    : registered ? "可召唤" : "未登录"));
            if (node.invalidRecipe) metadata.setTextColor(color(R.color.p3r_attention));
            row.addView(title, Ui.weight(1));
            TextView change = Ui.quiet(c, "配方");
            change.setTextSize(13);
            Ui.pad(change, 8, 6, 8, 6);
            change.setTag("basket_recipe_" + persona.id);
            change.setOnClickListener(v -> chooseRecipe(persona));
            row.addView(change, Ui.wrap());
            TextView remove = Ui.quiet(c, "移除");
            remove.setTextSize(13);
            Ui.pad(remove, 8, 6, 8, 6);
            remove.setTag("basket_remove_" + persona.id);
            remove.setContentDescription("移除合成目标 " + persona.nameZh);
            remove.setOnClickListener(v -> {
                FusionBasket current = prefs.basket();
                current.removeGoal(persona.id);
                prefs.setBasket(current);
                changed();
            });
            row.addView(remove, Ui.wrap());
            card.addView(row);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (targets.getChildCount() > 0) params.leftMargin = Ui.dp(c, 8);
            targets.addView(card, params);
        }
        content.addView(targetStrip, top(4));
    }

    private void renderMaterials() {
        LinearLayout section = Ui.row(c);
        LinearLayout title = Ui.sectionHeader(c, "召唤清单", null);
        title.setTag("basket_materials_tab");
        title.setOnClickListener(v -> scrollTo(section));
        section.addView(title, Ui.weight(1));
        TextView sort = toolButton("排序：" + SORT_LABELS[order], false);
        sort.setTag("basket_sort");
        sort.setTextSize(12);
        Ui.pad(sort, 8, 4, 8, 4);
        sort.setOnClickListener(v -> openSort());
        section.addView(sort, Ui.wrap());
        TextView direction = toolButton(descending ? "↓" : "↑", true);
        direction.setTag("basket_sort_direction");
        direction.setContentDescription(descending ? "降序" : "升序");
        direction.setTextSize(21);
        direction.setMinWidth(Ui.dp(c, 48));
        Ui.pad(direction, 8, 4, 8, 4);
        direction.setOnClickListener(v -> {
            descending = !descending;
            refresh();
        });
        section.addView(direction, Ui.wrap());
        content.addView(section, top(4));
        if (!summary.valid) {
            LinearLayout pending = Ui.column(c);
            pending.setBackground(Ui.card(c));
            Ui.pad(pending, 14, 14, 14, 10);
            pending.addView(Ui.text(c, "有配方需要重选", 15, color(R.color.p3r_attention), true));
            TextView choose = Ui.quiet(c, "重新选配方");
            choose.setOnClickListener(v -> {
                Compendium.Persona persona = data.persona(summary.invalidRecipes.get(0));
                if (persona != null) chooseRecipe(persona);
            });
            pending.addView(choose, top(6));
            content.addView(pending, top(6));
        }
        for (FusionBasket.ShoppingItem item : sorted(summary.shopping)) {
            content.addView(materialCard(item), top(5));
        }
    }

    private View materialCard(FusionBasket.ShoppingItem item) {
        Compendium.Persona persona = item.persona;
        boolean registered = owned.contains(persona.id);
        LinearLayout card = Ui.row(c);
        card.setTag("basket_missing_" + persona.id);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 8, 5, 6, 5);
        TextView check = ownedButton(persona);
        card.addView(check, Ui.wrap());
        LinearLayout title = identity(persona);
        title.setOnClickListener(v -> actions.openPersona(persona.id));
        TextView name = (TextView) title.getChildAt(0);
        if (item.quantity > 1) name.setText(persona.nameZh + " ×" + item.quantity);
        TextView metadata = (TextView) title.getChildAt(1);
        metadata.setText(arcanaName(persona.arcana) + " · Lv." + persona.level + " · "
            + (registered ? "可召唤" : "未登录"));
        metadata.setTextSize(12);
        if (registered) metadata.setTextColor(color(R.color.p3r_good));
        if (!calculator.isAvailable(persona)) {
            metadata.setText(arcanaName(persona.arcana) + " · Lv." + persona.level + " · 需 DLC");
            metadata.setTextColor(color(R.color.p3r_attention));
        }
        card.addView(title, gapWeight(8));
        TextView recipe = Ui.quiet(c, "配方");
        recipe.setTextSize(13);
        Ui.pad(recipe, 8, 4, 8, 4);
        recipe.setMinWidth(Ui.dp(c, 48));
        recipe.setTag("basket_expand_" + persona.id);
        recipe.setContentDescription("查找 " + persona.nameZh + " 的配方");
        recipe.setOnClickListener(v -> chooseRecipe(persona));
        card.addView(recipe, Ui.wrap());
        return card;
    }

    private void renderSteps() {
        LinearLayout section = Ui.sectionHeader(c, "合成顺序", summary.steps.size() + " 步");
        section.setTag("basket_steps_tab");
        section.setOnClickListener(v -> scrollTo(section));
        content.addView(section, top(8));
        if (summary.steps.isEmpty()) {
            TextView message = Ui.body(c, "按召唤清单准备即可");
            Ui.pad(message, 12, 10, 12, 10);
            content.addView(message);
            return;
        }
        int index = 0;
        for (FusionBasket.CraftStep step : summary.steps) {
            LinearLayout card = Ui.row(c);
            card.setTag("basket_step_" + step.result.id);
            card.setBackground(Ui.card(c));
            Ui.pad(card, 10, 6, 6, 6);
            TextView number = Ui.numeral(c, String.format(java.util.Locale.ROOT, "%02d", ++index), 23,
                color(R.color.p3r_cyan_300));
            card.addView(number, new LinearLayout.LayoutParams(Ui.dp(c, 30), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout formula = Ui.column(c);
            formula.setMinimumHeight(Ui.dp(c, 48));
            formula.setGravity(Gravity.CENTER_VERTICAL);
            formula.setClickable(true);
            formula.setFocusable(true);
            formula.setOnClickListener(v -> actions.openPersona(step.result.id));
            StringBuilder ingredients = new StringBuilder();
            for (Compendium.Persona ingredient : step.ingredients) {
                if (ingredients.length() > 0) ingredients.append(" + ");
                ingredients.append(ingredient.nameZh);
            }
            TextView sources = Ui.caption(c, ingredients);
            sources.setTextSize(12);
            sources.setMaxLines(2);
            sources.setEllipsize(android.text.TextUtils.TruncateAt.END);
            formula.addView(sources);
            TextView result = Ui.text(c, "→ " + step.result.nameZh
                + (step.quantity > 1 ? " ×" + step.quantity : ""), 16, color(R.color.p3r_white), true);
            formula.addView(result, top(2));
            formula.setContentDescription(ingredients + "，合成 " + step.result.nameZh);
            card.addView(formula, Ui.weight(1));
            FusionCalculator.Recipe active = basket.selectedRecipe(step.result.id, calculator);
            boolean valid = step.valid && !summary.invalidRecipes.contains(step.result.id) && active != null;
            TextView done = Ui.chip(c, valid ? owned.contains(step.result.id) ? "✓" : "○" : "!",
                valid && owned.contains(step.result.id));
            done.setTextSize(20);
            Ui.pad(done, 10, 6, 10, 6);
            done.setMinWidth(Ui.dp(c, 48));
            done.setMinHeight(Ui.dp(c, 48));
            done.setMinimumHeight(Ui.dp(c, 48));
            done.setTag("basket_complete_" + step.result.id);
            done.setContentDescription(valid ? (owned.contains(step.result.id) ? "取消已登录 " : "标记已登录 ")
                + step.result.nameZh : "重新选择 " + step.result.nameZh + " 的配方");
            done.setOnClickListener(v -> {
                if (!valid) { chooseRecipe(step.result); return; }
                boolean registered = prefs.ownedPersonas().contains(step.result.id);
                if (registered) prefs.setOwned(step.result.id, false); else prefs.recordFusion(active);
                changed();
            });
            card.addView(done, gapWrap(5));
            TextView more = Ui.quiet(c, "···");
            more.setTextSize(22);
            Ui.pad(more, 8, 4, 8, 4);
            more.setMinWidth(Ui.dp(c, 48));
            more.setTag("basket_actions_" + step.result.id);
            more.setContentDescription(step.result.nameZh + " 的配方和素材操作");
            more.setOnClickListener(v -> openStepActions(step));
            card.addView(more, Ui.wrap());
            content.addView(card, top(5));
        }
    }

    private void openStepActions(FusionBasket.CraftStep step) {
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, Ui.GUTTER, 12, Ui.GUTTER, 24);
        sheet.addView(SheetChrome.title(c, step.result.nameZh));
        for (Compendium.Persona ingredient : step.ingredients) {
            LinearLayout row = Ui.row(c);
            row.setBackground(Ui.pressableCard(c));
            Ui.pad(row, 12, 6, 8, 6);
            LinearLayout name = identity(ingredient);
            name.setOnClickListener(v -> {
                host.dismissSheet();
                actions.openPersona(ingredient.id);
            });
            row.addView(name, Ui.weight(1));
            TextView lookup = Ui.quiet(c, "配方");
            Ui.pad(lookup, 8, 4, 8, 4);
            lookup.setTag("basket_expand_" + ingredient.id);
            lookup.setOnClickListener(v -> {
                host.dismissSheet();
                chooseRecipe(ingredient);
            });
            row.addView(lookup);
            row.addView(ownedButton(ingredient), gapWrap(5));
            sheet.addView(row, top(6));
        }
        LinearLayout actions = Ui.row(c);
        TextView change = Ui.secondary(c, "换配方");
        change.setTag("basket_recipe_" + step.result.id);
        change.setOnClickListener(v -> {
            host.dismissSheet();
            chooseRecipe(step.result);
        });
        actions.addView(change, Ui.weight(1));
        TextView summon = Ui.primary(c, "直接召唤");
        summon.setTag("basket_summon_" + step.result.id);
        summon.setOnClickListener(v -> {
            FusionBasket current = prefs.basket();
            current.clearRecipe(step.result.id);
            prefs.setBasket(current);
            host.dismissSheet();
            changed();
        });
        actions.addView(summon, gapWeight(8));
        sheet.addView(actions, top(16));
        host.showSheet(sheet);
    }

    private TextView ownedButton(Compendium.Persona persona) {
        boolean checked = owned.contains(persona.id);
        TextView button = Ui.chip(c, checked ? "✓" : "○", checked);
        button.setTag("basket_owned_" + persona.id);
        button.setMinWidth(Ui.dp(c, 48));
        button.setMinHeight(Ui.dp(c, 48));
        button.setMinimumHeight(Ui.dp(c, 48));
        button.setTextSize(20);
        Ui.pad(button, 10, 6, 10, 6);
        button.setContentDescription((checked ? "取消已登录 " : "标记已登录 ") + persona.nameZh);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName("android.widget.CheckBox");
                info.setCheckable(true);
                info.setChecked(view.isSelected());
            }
        });
        button.setOnClickListener(v -> {
            boolean registered = !prefs.ownedPersonas().contains(persona.id);
            prefs.setOwned(persona.id, registered);
            button.setText(registered ? "✓" : "○");
            Ui.setChipSelected(button, registered);
            button.setContentDescription((registered ? "取消已登录 " : "标记已登录 ") + persona.nameZh);
            changed();
        });
        return button;
    }

    private LinearLayout identity(Compendium.Persona persona) {
        LinearLayout title = Ui.column(c);
        title.setMinimumHeight(Ui.dp(c, 48));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setClickable(true);
        title.setFocusable(true);
        title.addView(Ui.headline(c, persona.nameZh));
        title.addView(Ui.caption(c, arcanaName(persona.arcana) + " · Lv." + persona.level), top(2));
        title.setContentDescription(persona.nameZh + "，" + arcanaName(persona.arcana) + "，等级 " + persona.level);
        return title;
    }

    private void chooseRecipe(Compendium.Persona persona) {
        actions.chooseRecipe(persona.id, recipe -> {
            FusionBasket current = prefs.basket();
            if (!current.chooseRecipe(recipe)) {
                host.showMessage("素材绕回目标了，换一份配方");
                return;
            }
            prefs.setBasket(current);
            changed();
        });
    }

    private void changed() {
        int before = refreshGeneration;
        actions.onChanged();
        if (before == refreshGeneration) refresh();
    }

    private void scrollTo(View section) {
        int y = section.getTop();
        if (Ui.animationsEnabled()) scroll.smoothScrollTo(0, y); else scroll.scrollTo(0, y);
    }

    private void openSort() {
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, Ui.GUTTER, 12, Ui.GUTTER, 24);
        sheet.addView(SheetChrome.title(c, "合成篮排序"));
        for (int i = 0; i < SORT_LABELS.length; i++) {
            int selected = i;
            TextView choice = Ui.chip(c, SORT_LABELS[i], order == i);
            choice.setTag("basket_sort_" + SORT_TAGS[i]);
            choice.setMinHeight(Ui.dp(c, 48));
            SheetChrome.selectChoice(choice, order == i);
            choice.setOnClickListener(v -> {
                order = selected;
                host.dismissSheet();
                refresh();
            });
            sheet.addView(choice, top(10));
        }
        host.showSheet(sheet);
    }

    private Comparator<Compendium.Persona> personaOrder() {
        Comparator<Compendium.Persona> result;
        if (order == 1) result = Comparator.comparingInt((Compendium.Persona p) -> {
            Compendium.Arcana arcana = data.arcana(p.arcana);
            return arcana == null ? Integer.MAX_VALUE : arcana.order;
        }).thenComparingInt(p -> p.level).thenComparing(p -> p.id);
        else result = Comparator.comparingInt((Compendium.Persona p) -> p.level).thenComparing(p -> p.id);
        return descending ? result.reversed() : result;
    }

    private List<FusionBasket.ShoppingItem> sorted(List<FusionBasket.ShoppingItem> values) {
        List<FusionBasket.ShoppingItem> result = new ArrayList<>(values);
        Comparator<Compendium.Persona> order = personaOrder();
        result.sort((a, b) -> order.compare(a.persona, b.persona));
        return result;
    }

    private int unregisteredCount() {
        int count = 0;
        for (FusionBasket.ShoppingItem item : summary.shopping) {
            if (!owned.contains(item.persona.id)) count++;
        }
        return count;
    }

    private static int count(List<FusionBasket.ShoppingItem> values) {
        int count = 0;
        for (FusionBasket.ShoppingItem item : values) count += item.quantity;
        return count;
    }

    private String arcanaName(String id) {
        Compendium.Arcana arcana = data.arcana(id);
        return arcana == null ? id : arcana.nameZh;
    }

    /** A small neutral control inside a full-size touch target. */
    private TextView toolButton(String label, boolean iconOnly) {
        TextView button = CompendiumControls.toolButton(c, label, false);
        button.setMinWidth(Ui.dp(c, 48));
        button.setBackground(new android.graphics.drawable.InsetDrawable(button.getBackground(),
            Ui.dp(c, iconOnly ? 7 : 0), Ui.dp(c, 8), Ui.dp(c, iconOnly ? 7 : 0), Ui.dp(c, 8)));
        return button;
    }

    private int color(int id) { return Ui.col(c, id); }

    private LinearLayout.LayoutParams top(int gap) {
        LinearLayout.LayoutParams params = Ui.matchWrap();
        params.topMargin = Ui.dp(c, gap);
        return params;
    }

    private LinearLayout.LayoutParams gapWrap(int gap) {
        LinearLayout.LayoutParams params = Ui.wrap();
        params.leftMargin = Ui.dp(c, gap);
        return params;
    }

    private LinearLayout.LayoutParams gapWeight(int gap) {
        LinearLayout.LayoutParams params = Ui.weight(1);
        params.leftMargin = Ui.dp(c, gap);
        return params;
    }
}
