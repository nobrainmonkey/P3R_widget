package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.compendium.Compendium;
import app.p3r.dayplanner.v3.compendium.CompendiumPrefs;
import app.p3r.dayplanner.v3.compendium.FusionBasket;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;

/**
 * 合体篮: the goals (the one hero, with a bar of their fusion steps), the materials to summon or
 * find, and the fusions in order. What the basket contains and how it is worked out is
 * {@link FusionBasket}'s; this page shows it and records the player's choices.
 */
public final class FusionBasketPage {
    public interface Actions {
        void openPersona(String personaId);
        void chooseRecipe(String targetId, Consumer<FusionCalculator.Recipe> chosen);
        void onChanged();
        /** 「挑一个人格面具」: the 想合出谁？ picker, then that Persona's recipes. */
        default void pickTarget() {}
    }

    private static final String[] SORT_LABELS = {"等级", "阿尔卡那"};
    private static final String[] SORT_TAGS = {"level", "arcana"};
    private final HomeHost host;
    private final Context c;
    private final Compendium data;
    private final CompendiumPrefs prefs;
    private final Actions actions;
    private final LinearLayout root;
    private final ScrollView scroll;
    private final LinearLayout content;
    private FusionCalculator calculator;
    private FusionBasket basket;
    private FusionBasket.Summary summary;
    private Set<String> owned;
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
        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = Ui.column(c);
        Ui.pad(content, Ui.GUTTER, 4, Ui.GUTTER, 32);
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
        content.removeAllViews();
        if (basket.goals().isEmpty()) {
            renderEmpty();
        } else {
            renderGoals();
            if (!summary.valid) renderInvalid();
            renderMaterials();
            renderSteps();
            renderClear();
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    /** Rows slide in when the player switches to the basket. */
    void cascade() {
        Motion.cascade(content);
    }

    private void renderEmpty() {
        LinearLayout card = Ui.column(c);
        card.setTag("basket_empty");
        card.setBackground(PersonaViews.withWatermark(c, Ui.heroCard(c), "FUSION"));
        Ui.pad(card, 22, 30, 20, 22);
        card.addView(Ui.title(c, "合体篮是空的"));
        card.addView(Ui.body(c, "想合出谁？挑好配方放进来。"), top(8));
        TextView browse = Ui.primary(c, "挑一个人格面具");
        browse.setTag("basket_browse");
        browse.setOnClickListener(v -> actions.pickTarget());
        card.addView(browse, top(24));
        content.addView(card, top(8));
    }

    // ------------------------------------------------------------------ goals

    private void renderGoals() {
        LinearLayout hero = Ui.column(c);
        hero.setTag("basket_goals");
        hero.setBackground(Ui.heroCard(c));
        Ui.pad(hero, 22, 12, 6, 16);
        hero.addView(Ui.text(c, "目标", 13, color(R.color.p3r_cyan_300), true));
        int index = 0;
        for (FusionBasket.Node node : summary.roots) {
            if (index++ > 0) hero.addView(rule(), ruleParams());
            hero.addView(goalBlock(node));
        }
        if (!summary.steps.isEmpty()) {
            LinearLayout.LayoutParams barParams = top(12);
            barParams.rightMargin = Ui.dp(c, 14);
            hero.addView(progress(), barParams);
        }
        content.addView(hero, top(8));
    }

    /** One goal: name, English, its state (还差 N 步 / 已登录 / 未登录 / 配方要重选), level, and its menu. */
    private View goalBlock(FusionBasket.Node node) {
        Compendium.Persona persona = node.persona;
        boolean registered = owned.contains(persona.id);
        LinearLayout box = Ui.column(c);
        box.setTag("basket_goal_" + persona.id);
        LinearLayout row = Ui.row(c);
        Ui.pad(row, 0, 4, 0, 4);
        LinearLayout identity = Ui.column(c);
        identity.setClickable(true);
        identity.setFocusable(true);
        identity.setOnClickListener(v -> actions.openPersona(persona.id));
        TextView name = Ui.text(c, persona.nameZh, 26, color(R.color.p3r_white), true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(name);
        TextView english = Ui.text(c, Ui.latin(c, persona.nameEn + " · " + PersonaViews.arcanaName(data, persona.arcana)),
            13, color(R.color.p3r_ink_muted), false);
        english.setMaxLines(1);
        english.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(english);
        TextView status;
        if (node.invalidRecipe) {
            status = Ui.text(c, "配方要重选", 15, color(R.color.ui_gold), true);
        } else if (registered) {
            status = Ui.text(c, "已登录", 15, color(R.color.p3r_cyan_400), true);
            Drawable tick = Ui.tinted(c, R.drawable.ui_ic_check, color(R.color.p3r_cyan_400));
            tick.setBounds(0, 0, Ui.dp(c, 16), Ui.dp(c, 16));
            status.setCompoundDrawables(tick, null, null, null);
            status.setCompoundDrawablePadding(Ui.dp(c, 4));
        } else if (node.craftQuantity == 0) {
            // 「不合了，直接召唤」 on the goal's own step: no fusion left, it is found or summoned.
            status = Ui.text(c, "直接召唤 · 未登录", 15, color(R.color.p3r_ink_muted), true);
        } else {
            status = Ui.mixed(c, "还差 " + remainingSteps(node) + " 步", 15, color(R.color.p3r_cyan_300), true);
        }
        status.setTag("basket_goal_status_" + persona.id);
        identity.addView(status, top(4));
        identity.setContentDescription(persona.nameZh + "，" + persona.nameEn + "，" + Zh.plain(status.getText()));
        row.addView(identity, Ui.weight(1));
        row.addView(PersonaViews.level(c, persona.level, 34, color(R.color.p3r_cyan_400), 0.42f), Ui.wrap());
        ImageView menu = Ui.icon(c, R.drawable.ui_ic_edit, color(R.color.p3r_cyan_300), 20);
        menu.setScaleType(ImageView.ScaleType.CENTER);
        menu.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        menu.setTag("basket_goal_menu_" + persona.id);
        menu.setContentDescription(persona.nameZh + "：换配方或移出合体篮");
        menu.setClickable(true);
        menu.setFocusable(true);
        menu.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(color(R.color.ui_cyan_soft)).skew(Ui.dp(c, 6)));
        menu.setOnClickListener(v -> openGoalMenu(persona));
        row.addView(menu, new LinearLayout.LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        box.addView(row);
        if (registered && !node.invalidRecipe) {
            TextView remove = Ui.quiet(c, "移出合体篮");
            remove.setTextSize(14);
            Ui.pad(remove, 0, 6, 12, 6);
            remove.setTag("basket_done_remove_" + persona.id);
            remove.setOnClickListener(v -> removeGoal(persona));
            box.addView(remove, Ui.wrap());
        }
        return box;
    }

    /** The goal's fusions not yet registered (its own included). */
    private int remainingSteps(FusionBasket.Node node) {
        Set<String> steps = new LinkedHashSet<>();
        collectSteps(node, steps);
        int left = 0;
        for (String id : steps) if (!owned.contains(id)) left++;
        return left;
    }

    private void collectSteps(FusionBasket.Node node, Set<String> steps) {
        if (node.craftQuantity > 0 || basket.chosenRecipe(node.persona.id) != null) steps.add(node.persona.id);
        for (FusionBasket.Node child : node.children) collectSteps(child, steps);
    }

    /** One slanted segment per fusion step, filled once its result is registered. */
    private View progress() {
        LinearLayout bar = Ui.row(c);
        bar.setTag("basket_progress");
        int done = 0;
        int index = 0;
        for (FusionBasket.CraftStep step : summary.steps) {
            boolean ok = owned.contains(step.result.id);
            if (ok) done++;
            View segment = new View(c);
            SlantedPanelDrawable plate = new SlantedPanelDrawable().skew(Ui.dp(c, 4));
            if (ok) plate.fill(color(R.color.p3r_cyan_400));
            else plate.fill(Ui.alpha(color(R.color.p3r_navy_950), 150)).stroke(Ui.alpha(color(R.color.p3r_cyan_300), 110), Ui.dp(c, 1));
            segment.setBackground(plate);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(c, 8), 1);
            if (index++ > 0) p.leftMargin = Ui.dp(c, 4);
            bar.addView(segment, p);
        }
        bar.setContentDescription("合体进度 " + done + "/" + summary.steps.size());
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return bar;
    }

    private void openGoalMenu(Compendium.Persona persona) {
        LinearLayout sheet = menuSheet(persona.nameZh, null);
        sheet.addView(menuItem("换配方", "basket_recipe_" + persona.id, () -> chooseRecipe(persona)), top(10));
        sheet.addView(menuItem("移出合体篮", "basket_remove_" + persona.id, () -> removeGoal(persona)), top(6));
        host.showSheet(sheet);
    }

    private void removeGoal(Compendium.Persona persona) {
        FusionBasket before = prefs.basket();
        FusionBasket current = prefs.basket();
        current.removeGoal(persona.id);
        prefs.setBasket(current);
        changed();
        PersonaViews.announce(host, "已把 " + persona.nameZh + " 移出合体篮", "撤销", () -> {
            prefs.setBasket(before);
            changed();
        });
    }

    /** A DLC change left recipes that need an unowned pack: one banner, one fix. */
    private void renderInvalid() {
        LinearLayout banner = Ui.row(c);
        banner.setTag("basket_invalid");
        banner.setBackground(Ui.card(c).accent(color(R.color.ui_gold), Ui.dp(c, 4)));
        Ui.pad(banner, 18, 8, 6, 8);
        banner.addView(Ui.mixed(c, "有配方用到了没买的 DLC，重选一下？", 15, color(R.color.p3r_white), true), Ui.weight(1));
        TextView fix = Ui.quiet(c, "重选");
        fix.setTag("basket_reselect");
        fix.setOnClickListener(v -> {
            Compendium.Persona persona = data.persona(summary.invalidRecipes.get(0));
            if (persona != null) chooseRecipe(persona);
        });
        banner.addView(fix, Ui.wrap());
        content.addView(banner, top(12));
    }

    // ------------------------------------------------------------------ materials

    private void renderMaterials() {
        // Nothing to gather (every goal is registered, or every recipe needs a new choice): no header.
        if (summary.shopping.isEmpty()) return;
        int registered = 0;
        for (FusionBasket.ShoppingItem item : summary.shopping) if (owned.contains(item.persona.id)) registered++;
        content.addView(Ui.sectionHeader(c, "需要的素材", "已登录 " + registered + "/" + summary.shopping.size()), top(20));
        LinearLayout sort = Ui.row(c);
        sort.setGravity(Gravity.END);
        for (int i = 0; i < SORT_LABELS.length; i++) {
            int mode = i;
            boolean selected = order == i;
            TextView chip = CompendiumControls.toolButton(c, SORT_LABELS[i] + (selected ? (descending ? " ↓" : " ↑") : ""),
                selected);
            chip.setTag("basket_sort_" + SORT_TAGS[i]);
            chip.setContentDescription("按" + SORT_LABELS[i] + "排序" + (selected ? descending ? "，从高到低" : "，从低到高" : ""));
            chip.setOnClickListener(v -> {
                if (order == mode) descending = !descending;
                else order = mode;
                refresh();
            });
            LinearLayout.LayoutParams p = Ui.wrap();
            if (i > 0) p.leftMargin = Ui.dp(c, 6);
            sort.addView(chip, p);
        }
        content.addView(sort, Ui.matchWrap());
        boolean first = true;
        for (FusionBasket.ShoppingItem item : sorted(summary.shopping)) {
            if (!first) content.addView(rule(), ruleParams());
            content.addView(materialRow(item));
            first = false;
        }
    }

    /** ☐ 俄耳甫斯 ×2 / Orpheus · 愚者 Lv.1 … 怎么合？ */
    private View materialRow(FusionBasket.ShoppingItem item) {
        Compendium.Persona persona = item.persona;
        boolean registered = owned.contains(persona.id);
        LinearLayout row = Ui.row(c);
        row.setTag("basket_missing_" + persona.id);
        row.setMinimumHeight(Ui.dp(c, 60));
        Ui.pad(row, 0, 4, 0, 4);
        PersonaViews.Toggle check = new PersonaViews.Toggle(c, false);
        check.setChecked(registered);
        check.setTag("basket_owned_" + persona.id);
        check.setContentDescription((registered ? "取消登录 " : "登录到全书 ") + persona.nameZh);
        check.setOnClickListener(v -> {
            prefs.setOwned(persona.id, !prefs.ownedPersonas().contains(persona.id));
            changed();
        });
        row.addView(check, new LinearLayout.LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        LinearLayout identity = Ui.column(c);
        identity.setClickable(true);
        identity.setFocusable(true);
        identity.setOnClickListener(v -> actions.openPersona(persona.id));
        TextView name = Ui.text(c, persona.nameZh + (item.quantity > 1 ? " ×" + item.quantity : ""), 17,
            color(R.color.p3r_white), true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(name);
        TextView sub = Ui.text(c, Ui.latin(c, persona.nameEn + " · " + PersonaViews.arcanaLevel(data, persona)), 12,
            color(R.color.p3r_ink_muted), false);
        sub.setMaxLines(1);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(sub);
        if (!calculator.isAvailable(persona)) {
            identity.addView(Ui.tag(c, "需要 DLC", color(R.color.ui_gold), color(R.color.p3r_navy_950)), topWrap(4));
        }
        identity.setContentDescription(persona.nameZh + (item.quantity > 1 ? " " + item.quantity + " 个" : "")
            + "，" + persona.nameEn + "，" + PersonaViews.arcanaLevel(data, persona));
        LinearLayout.LayoutParams identityParams = Ui.weight(1);
        identityParams.leftMargin = Ui.dp(c, 6);
        row.addView(identity, identityParams);
        TextView how = Ui.quiet(c, "怎么合？");
        how.setTextSize(14);
        Ui.pad(how, 10, 4, 10, 4);
        how.setTag("basket_expand_" + persona.id);
        how.setContentDescription("怎么合出" + persona.nameZh);
        how.setOnClickListener(v -> chooseRecipe(persona));
        row.addView(how, Ui.wrap());
        return row;
    }

    // ------------------------------------------------------------------ steps

    private void renderSteps() {
        int done = 0;
        for (FusionBasket.CraftStep step : summary.steps) if (owned.contains(step.result.id)) done++;
        content.addView(Ui.sectionHeader(c, "合体顺序", summary.steps.isEmpty() ? null
            : "完成 " + done + "/" + summary.steps.size()), top(22));
        if (summary.steps.isEmpty()) {
            content.addView(Ui.body(c, "不用合体，直接召唤就行"), top(4));
            return;
        }
        int index = 0;
        for (FusionBasket.CraftStep step : summary.steps) {
            if (index > 0) content.addView(rule(), ruleParams());
            content.addView(stepRow(step, ++index));
        }
    }

    /** 01 那伽 + 福尔图娜 / 能天使 … ☐; tapping the row opens its three choices. */
    private View stepRow(FusionBasket.CraftStep step, int number) {
        Compendium.Persona result = step.result;
        FusionCalculator.Recipe active = basket.selectedRecipe(result.id, calculator);
        boolean valid = step.valid && !summary.invalidRecipes.contains(result.id) && active != null;
        boolean done = valid && owned.contains(result.id);
        LinearLayout row = Ui.row(c);
        row.setTag("basket_step_" + result.id);
        row.setMinimumHeight(Ui.dp(c, 64));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(new PersonaViews.CursorDrawable(c));
        Ui.pad(row, 0, 6, 0, 6);
        TextView numeral = Ui.numeral(c, String.format(Locale.ROOT, "%02d", number), 30,
            color(done ? R.color.p3r_ink_faint : R.color.p3r_cyan_400));
        numeral.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(numeral, new LinearLayout.LayoutParams(Ui.dp(c, 46), ViewGroup.LayoutParams.WRAP_CONTENT));
        StringBuilder sources = new StringBuilder();
        StringBuilder spoken = new StringBuilder();
        for (Compendium.Persona ingredient : step.ingredients) {
            if (sources.length() > 0) {
                sources.append(" + ");
                spoken.append(" + ");
            }
            sources.append(ingredient.nameZh);
            spoken.append(ingredient.nameZh).append("（").append(ingredient.nameEn).append("）");
        }
        LinearLayout formula = Ui.column(c);
        TextView from = Ui.text(c, sources, 13, color(done ? R.color.p3r_ink_faint : R.color.p3r_ink_muted), false);
        from.setMaxLines(2);
        from.setEllipsize(TextUtils.TruncateAt.END);
        formula.addView(from);
        LinearLayout line = Ui.row(c);
        TextView name = Ui.text(c, result.nameZh + (step.quantity > 1 ? " ×" + step.quantity : ""), 17,
            color(done ? R.color.p3r_ink_faint : R.color.p3r_white), true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(name, Ui.wrap());
        if (done) {
            TextView tag = Ui.tag(c, "已合体", color(R.color.ui_cyan_soft), color(R.color.p3r_cyan_300));
            tag.setTextSize(12);
            LinearLayout.LayoutParams p = Ui.wrap();
            p.leftMargin = Ui.dp(c, 8);
            line.addView(tag, p);
        }
        formula.addView(line, top(1));
        row.addView(formula, Ui.weight(1));
        row.setContentDescription(spoken + "，合出" + result.nameZh + "（" + result.nameEn + "）" + (done ? "，已合体" : ""));
        row.setOnClickListener(v -> openStepMenu(step));
        if (valid) {
            PersonaViews.Toggle check = new PersonaViews.Toggle(c, false);
            check.setChecked(done);
            check.setTag("basket_complete_" + result.id);
            check.setContentDescription(done ? "取消登录 " + result.nameZh : result.nameZh + " 合出来了");
            check.setOnClickListener(v -> {
                if (prefs.ownedPersonas().contains(result.id)) {
                    prefs.setOwned(result.id, false);
                } else {
                    prefs.recordFusion(active);
                    if (basket.containsGoal(result.id)) host.showMessage(result.nameZh + " 合出来了！");
                }
                changed();
            });
            row.addView(check, new LinearLayout.LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        } else {
            TextView fix = Ui.numeral(c, "!", 22, color(R.color.ui_gold));
            fix.setGravity(Gravity.CENTER);
            fix.setTag("basket_complete_" + result.id);
            fix.setContentDescription("重选" + result.nameZh + "的配方");
            fix.setClickable(true);
            fix.setFocusable(true);
            // A 40 dp plate inside the same 48 dp target as the tick it stands in for.
            fix.setBackground(new InsetDrawable(new SlantedPanelDrawable().fill(Ui.alpha(color(R.color.p3r_navy_950), 120))
                .pressedFill(color(R.color.ui_cyan_soft)).stroke(color(R.color.ui_gold), Ui.dp(c, 1.5f))
                .skew(Ui.dp(c, 5)), Ui.dp(c, 4)));
            fix.setOnClickListener(v -> chooseRecipe(result));
            row.addView(fix, new LinearLayout.LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        }
        return row;
    }

    private void openStepMenu(FusionBasket.CraftStep step) {
        Compendium.Persona result = step.result;
        StringBuilder formula = new StringBuilder();
        for (Compendium.Persona ingredient : step.ingredients) {
            if (formula.length() > 0) formula.append(" + ");
            formula.append(ingredient.nameZh);
        }
        LinearLayout sheet = menuSheet(result.nameZh, formula + " → " + result.nameZh);
        sheet.addView(menuItem("看 " + result.nameZh + " 的资料", "basket_open_" + result.id,
            () -> actions.openPersona(result.id)), top(10));
        sheet.addView(menuItem("换个配方", "basket_recipe_" + result.id, () -> chooseRecipe(result)), top(6));
        sheet.addView(menuItem("不合了，直接召唤", "basket_summon_" + result.id, () -> {
            FusionBasket current = prefs.basket();
            current.clearRecipe(result.id);
            prefs.setBasket(current);
            changed();
        }), top(6));
        host.showSheet(sheet);
    }

    // ------------------------------------------------------------------ clear

    /** 「清空合体篮」 at the bottom: one tap, undone from the message bar. */
    private void renderClear() {
        TextView clear = Ui.quiet(c, "清空合体篮");
        clear.setTextSize(14);
        clear.setTag("basket_clear");
        clear.setOnClickListener(v -> {
            FusionBasket cleared = prefs.basket();
            prefs.setBasket(new FusionBasket());
            changed();
            PersonaViews.announce(host, "合体篮清空了", "撤销", () -> {
                prefs.setBasket(cleared);
                changed();
            });
        });
        LinearLayout.LayoutParams p = Ui.wrap();
        p.topMargin = Ui.dp(c, 26);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(clear, p);
    }

    // ------------------------------------------------------------------ helpers

    private LinearLayout menuSheet(String title, String subtitle) {
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 24);
        sheet.addView(SheetChrome.title(c, title));
        if (subtitle != null) {
            sheet.addView(Ui.text(c, subtitle, 14, color(R.color.p3r_ink_muted), false));
        }
        return sheet;
    }

    private View menuItem(String label, String tag, Runnable action) {
        LinearLayout item = Ui.row(c);
        item.setTag(tag);
        item.setClickable(true);
        item.setFocusable(true);
        item.setMinimumHeight(Ui.dp(c, 52));
        item.setBackground(Ui.band(c).pressedFill(color(R.color.ui_cyan_soft)));
        Ui.pad(item, 18, 8, 14, 8);
        item.addView(Ui.text(c, label, 16, color(R.color.p3r_white), true), Ui.weight(1));
        item.addView(Ui.icon(c, R.drawable.ui_ic_chevron, color(R.color.p3r_cyan_300), 18));
        item.setContentDescription(label);
        item.setOnClickListener(v -> {
            host.dismissSheet();
            action.run();
        });
        return item;
    }

    private void chooseRecipe(Compendium.Persona persona) {
        actions.chooseRecipe(persona.id, recipe -> {
            FusionBasket current = prefs.basket();
            if (!current.chooseRecipe(recipe)) {
                host.showError(CompendiumPage.CYCLE);
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

    private int color(int id) { return Ui.col(c, id); }

    private LinearLayout.LayoutParams top(int gap) {
        LinearLayout.LayoutParams params = Ui.matchWrap();
        params.topMargin = Ui.dp(c, gap);
        return params;
    }

    private LinearLayout.LayoutParams topWrap(int gap) {
        LinearLayout.LayoutParams params = Ui.wrap();
        params.topMargin = Ui.dp(c, gap);
        return params;
    }
}
