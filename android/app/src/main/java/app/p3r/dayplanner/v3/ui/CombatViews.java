package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.TipsCatalogue;

/**
 * 提示's fights: borderless encounter rows (a floor or date plate, the enemy's name with its English
 * name for guide lookup, coloured weakness tags) and the sheet with the nine-attribute chart. All game
 * facts come from the structured rules; element names follow the official Simplified Chinese game.
 */
final class CombatViews {
    /** Column heads of the chart (one character each, so all nine fit a phone). */
    private static final String[] ATTRIBUTES = {"斩", "打", "贯", "火", "冰", "电", "风", "圣", "暗"};
    /** Official element names for tags and spoken text. */
    static final String[] ELEMENTS = {"斩击", "打击", "贯穿", "火焰", "冰冻", "电击", "疾风", "神圣", "暗黑"};
    private static final int NAME_WIDTH = 80;
    private static final int ATTRIBUTE_WIDTH = 26;
    /** Width of the plate column of a row (dp), shared with the 提示 rows. */
    static final int PLATE_WIDTH = 52;

    private CombatViews() {}

    // ------------------------------------------------------------------ names

    /**
     * The encounter as the player knows it: the boss or enemy name without the pack's location
     * prefix (「5层守门：无情冰鸦」 → 「无情冰鸦」, 「…蒙纳多门：优雅圣母」 → 「优雅圣母」, 「猎杀者：可选挑战」 → 「猎杀者」).
     */
    static String displayTitle(Rules.CombatTip combat) {
        String title = combat.titleZh == null ? "" : combat.titleZh.trim();
        int colon = title.lastIndexOf('：');
        if (colon > 0 && colon < title.length() - 1) {
            String before = title.substring(0, colon).trim();
            String after = title.substring(colon + 1).trim();
            title = "可选挑战".equals(after) ? before : after;
        }
        return Zh.dots(title);
    }

    private static final Pattern BLOCK_ALIAS = Pattern.compile(
        "(?:Thebel|Arqa|Yabbashah|Tziah|Harabah|Adamah)(?: I{1,2})?|Ultimate Adversary|\\d\\d-\\d\\d");

    /**
     * The English name of the encounter's main enemy for guide lookup ("Ruthless Ice Raven"), or null
     * when the pack's aliases do not pair up with its enemies one to one.
     */
    static String alias(Rules.CombatTip combat) {
        List<String> names = new ArrayList<>();
        for (String alias : combat.aliases) {
            if (alias == null || BLOCK_ALIAS.matcher(alias.trim()).matches()) continue;
            if (alias.codePoints().anyMatch(cp -> cp >= 0x2E80)) continue;
            names.add(alias.trim());
        }
        if (names.isEmpty() || names.size() != combat.enemies.size()) return null;
        String title = displayTitle(combat);
        for (int i = 0; i < combat.enemies.size(); i++) {
            if (title.equals(Zh.dots(combat.enemies.get(i).nameZh))) {
                // "Elegant Mother C": the guides' variant letter is not part of the name.
                return names.get(i).replaceAll(" [A-F]$", "");
            }
        }
        return null;
    }

    static String kindLabel(Rules.CombatTip combat) {
        return TipsCatalogue.categoryLabel(combat);
    }

    private static String enemyName(Rules.CombatEnemy enemy) {
        return Zh.dots(enemy.nameZh) + (enemy.count > 1 ? " ×" + enemy.count : "");
    }

    /** 「欺骗的巨轮、魔法手 ×2」; more than three enemies or phases end with 「…」. */
    private static String roster(Rules.CombatTip combat) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(3, combat.enemies.size()); i++) names.add(enemyName(combat.enemies.get(i)));
        return String.join("、", names) + (combat.enemies.size() > 3 ? " …" : "");
    }

    // ------------------------------------------------------------------ weakness

    /** The official names of what hits {@code enemy}'s weakness: 「火焰」「神圣」. */
    static List<String> weak(Rules.CombatEnemy enemy) {
        List<String> weak = new ArrayList<>();
        for (int i = 0; i < ELEMENTS.length; i++) {
            String base = base(enemy.affinities[i]);
            if ("w".equals(base) || "z".equals(base)) weak.add(ELEMENTS[i]);
        }
        return weak;
    }

    private static List<String> doubled(Rules.CombatEnemy enemy) {
        List<String> doubled = new ArrayList<>();
        for (int i = 0; i < ELEMENTS.length; i++) {
            if ("v".equals(base(enemy.affinities[i]))) doubled.add(ELEMENTS[i]);
        }
        return doubled;
    }

    /** 「弱点：火焰、疾风」, 「无弱点」, 「无弱点；火焰伤害 ×2」 (spoken text and sheet summaries). */
    private static String weakness(Rules.CombatEnemy enemy) {
        List<String> weak = weak(enemy);
        List<String> doubled = doubled(enemy);
        return (weak.isEmpty() ? "无弱点" : "弱点：" + String.join("、", weak))
            + (doubled.isEmpty() ? "" : "；" + String.join("、", doubled) + "伤害 ×2");
    }

    /** One line per enemy when they differ; 「每个阶段弱点不同」 beyond three. */
    static String weaknessSummary(Rules.CombatTip combat) {
        Set<String> summaries = new LinkedHashSet<>();
        for (Rules.CombatEnemy enemy : combat.enemies) summaries.add(weakness(enemy));
        if (summaries.size() == 1) return summaries.iterator().next();
        if (combat.enemies.size() > 3) return EVERY_PHASE;
        List<String> lines = new ArrayList<>();
        for (Rules.CombatEnemy enemy : combat.enemies) lines.add(enemyName(enemy) + " " + weakness(enemy));
        return String.join("；", lines);
    }

    /** Shown instead of per-enemy tags for a fight of many enemies or phases. */
    static final String EVERY_PHASE = "每个阶段弱点不同";

    /** Slanted element tags: red for a weakness, gold for double damage, grey 「无弱点」. */
    static FlowLayout weaknessTags(Context c, Rules.CombatEnemy enemy) {
        FlowLayout tags = new FlowLayout(c, 4, 4);
        List<String> weak = weak(enemy);
        for (String name : weak) {
            tags.addView(Ui.tag(c, name, Ui.col(c, R.color.p3r_danger), Ui.col(c, R.color.p3r_navy_950)));
        }
        if (weak.isEmpty()) {
            tags.addView(Ui.tag(c, "无弱点", Ui.alpha(Ui.col(c, R.color.p3r_ink_faint), 90),
                Ui.col(c, R.color.p3r_ice_100)));
        }
        for (String name : doubled(enemy)) {
            tags.addView(Ui.tag(c, name + " ×2", Ui.col(c, R.color.ui_gold), Ui.col(c, R.color.p3r_navy_950)));
        }
        tags.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return tags;
    }

    /** True when the weakness block names every enemy on its own line (then no roster line above it). */
    private static boolean perEnemy(Rules.CombatTip combat) {
        Set<String> kinds = new LinkedHashSet<>();
        for (Rules.CombatEnemy enemy : combat.enemies) kinds.add(weakness(enemy));
        return kinds.size() > 1 && combat.enemies.size() <= 3;
    }

    /** The weakness block of a row: one tag line, per-enemy lines when they differ, or one muted line. */
    private static View weaknessBlock(Context c, Rules.CombatTip combat) {
        Set<String> kinds = new LinkedHashSet<>();
        for (Rules.CombatEnemy enemy : combat.enemies) kinds.add(weakness(enemy));
        if (kinds.size() == 1) return weaknessTags(c, combat.enemies.get(0));
        if (combat.enemies.size() > 3) {
            return Ui.text(c, EVERY_PHASE, 13, Ui.col(c, R.color.ui_gold), true);
        }
        LinearLayout lines = Ui.column(c);
        for (Rules.CombatEnemy enemy : combat.enemies) {
            LinearLayout line = Ui.row(c);
            TextView name = Ui.mixed(c, enemyName(enemy), 13, Ui.col(c, R.color.p3r_ice_100), false);
            name.setMinWidth(Ui.dp(c, 72));
            line.addView(name, Ui.wrap());
            LinearLayout.LayoutParams tp = Ui.weight(1);
            tp.leftMargin = Ui.dp(c, 8);
            line.addView(weaknessTags(c, enemy), tp);
            lines.addView(line, top(c, lines.getChildCount() == 0 ? 0 : 4));
        }
        return lines;
    }

    // ------------------------------------------------------------------ plates

    /**
     * The left column of a 提示 row: a small upright line ({@code over}, may be null), a big numeral
     * ({@code big}) and a small line under it ({@code under}, may be null).
     */
    static LinearLayout plate(Context c, String over, String big, String under, int bigColor) {
        LinearLayout plate = Ui.column(c);
        plate.setGravity(Gravity.CENTER_HORIZONTAL);
        if (over != null) {
            TextView top = Ui.text(c, over, 12, Ui.col(c, R.color.p3r_ink_muted), false);
            top.setGravity(Gravity.CENTER);
            plate.addView(top, Ui.wrap());
        }
        if (big != null) {
            boolean numeral = big.codePoints().noneMatch(cp -> cp >= 0x2E80);
            TextView value = numeral ? Ui.numeral(c, big, big.length() > 4 ? 18 : 26, bigColor)
                : Ui.text(c, big, 15, bigColor, true);
            value.setGravity(Gravity.CENTER);
            plate.addView(value, Ui.wrap());
        }
        if (under != null) {
            TextView bottom = Ui.text(c, under, 12, Ui.col(c, R.color.p3r_cyan_300), true);
            bottom.setGravity(Gravity.CENTER);
            plate.addView(bottom, Ui.wrap());
        }
        plate.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return plate;
    }

    /** A fight's plate: its floor (「36F」), its date for full moons and story fights, else 「随机」. */
    static LinearLayout plateOf(Context c, Rules rules, Rules.CombatTip combat) {
        int white = Ui.col(c, R.color.p3r_white);
        if (combat.floor > 0) return plate(c, null, combat.floor + "F", null, Ui.col(c, R.color.p3r_cyan_300));
        if (!"optional".equals(combat.kind) && !"monad".equals(combat.kind) && rules != null) {
            Rules.Day day = rules.day(combat.fromDay);
            if (day != null) return plate(c, day.month() + "月", String.valueOf(day.dayOfMonth()), null, white);
        }
        return plate(c, null, "随机", null, Ui.col(c, R.color.p3r_ink_muted));
    }

    // ------------------------------------------------------------------ rows

    /**
     * A fight in the list: plate, kind tag, the enemy's name (its English name beside it), the other
     * enemies in muted text and the weakness tags; the whole row opens the sheet.
     */
    static View row(Context c, Rules rules, Rules.CombatTip combat, Runnable open) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        row.setMinimumHeight(Ui.dp(c, 64));
        Ui.pad(row, 0, 10, 0, 10);
        row.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
            .skew(Ui.dp(c, 6)));
        row.addView(plateOf(c, rules, combat), new LinearLayout.LayoutParams(Ui.dp(c, PLATE_WIDTH),
            ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.tag(c, kindLabel(combat), Ui.alpha(Ui.col(c, R.color.p3r_danger), 210),
            Ui.col(c, R.color.p3r_navy_950)), Ui.wrap());
        texts.addView(nameLine(c, combat, 17), top(c, 6));
        if (combat.enemies.size() > 1 && !perEnemy(combat)) {
            TextView others = Ui.mixed(c, roster(combat), 13, Ui.col(c, R.color.p3r_ink_muted), false);
            others.setMaxLines(2);
            others.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(others, top(c, 2));
        }
        if (!combat.enemies.isEmpty()) {
            texts.addView(weaknessBlock(c, combat), top(c, 8));
        } else if (combat.shortZh != null && !combat.shortZh.isEmpty()) {
            texts.addView(Ui.mixed(c, TipsCatalogue.officialTerms(combat.shortZh), 14,
                Ui.col(c, R.color.p3r_ice_100), false), top(c, 4));
        }
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 12);
        row.addView(texts, tp);
        row.addView(chevron(c), chevronParams(c));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(kindLabel(combat) + "，" + spokenTitle(combat)
            + (combat.enemies.isEmpty() ? "" : "，" + weaknessSummary(combat)));
        row.setOnClickListener(v -> open.run());
        return row;
    }

    /**
     * A compact encounter inside a Monad group: 「第 1 战」 for a passage battle, the name with its
     * English name, and the weakness tags on the right.
     */
    static View entry(Context c, Rules.CombatTip combat, Runnable open) {
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, 56));
        Ui.pad(row, 0, 8, 0, 8);
        row.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
            .skew(Ui.dp(c, 6)));
        if (combat.battleOrder > 0) {
            TextView order = Ui.text(c, "第 " + combat.battleOrder + " 战", 13, Ui.col(c, R.color.p3r_cyan_300), true);
            order.setMinWidth(Ui.dp(c, PLATE_WIDTH));
            row.addView(order, Ui.wrap());
        }
        LinearLayout texts = Ui.column(c);
        texts.addView(nameLine(c, combat, 16));
        if (combat.enemies.size() > 1 && !perEnemy(combat)) {
            TextView others = Ui.mixed(c, roster(combat), 13, Ui.col(c, R.color.p3r_ink_muted), false);
            others.setMaxLines(2);
            others.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(others, top(c, 2));
        }
        if (!combat.enemies.isEmpty()) texts.addView(weaknessBlock(c, combat), top(c, 6));
        row.addView(texts, Ui.weight(1));
        row.addView(chevron(c), chevronParams(c));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription((combat.battleOrder > 0 ? "第 " + combat.battleOrder + " 战，" : "")
            + spokenTitle(combat) + (combat.enemies.isEmpty() ? "" : "，" + weaknessSummary(combat)));
        row.setOnClickListener(v -> open.run());
        return row;
    }

    private static String spokenTitle(Rules.CombatTip combat) {
        return (combat.floor > 0 ? combat.floor + "F " : "") + displayTitle(combat);
    }

    /** The name in bold and, beside it, its English name in small muted Latin (decorative). */
    private static View nameLine(Context c, Rules.CombatTip combat, float sp) {
        FlowLayout line = new FlowLayout(c, 8, 0);
        TextView name = Ui.mixed(c, displayTitle(combat), sp, Ui.col(c, R.color.p3r_white), true);
        line.addView(name);
        String alias = alias(combat);
        if (alias != null) {
            line.addView(english(c, alias, 13));
        }
        return line;
    }

    /** A guide-lookup English name: secondary, never the only carrier of meaning. */
    static TextView english(Context c, String alias, float sp) {
        TextView en = Ui.text(c, alias, sp, Ui.col(c, R.color.p3r_ink_muted), false);
        en.setTypeface(Ui.numeralsUpright(c));
        en.setPadding(0, Ui.dp(c, 4), 0, 0);
        en.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return en;
    }

    static android.widget.ImageView chevron(Context c) {
        return Ui.icon(c, R.drawable.ui_ic_chevron, Ui.col(c, R.color.p3r_cyan_300), 18);
    }

    private static LinearLayout.LayoutParams chevronParams(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18));
        p.leftMargin = Ui.dp(c, 8);
        p.gravity = Gravity.CENTER_VERTICAL;
        return p;
    }

    // ------------------------------------------------------------------ sheet

    /**
     * The sheet of a fight: its name (and English name) as the title, one meta line of tags
     * ({@code where}: 「鄙俗之庭 亚巴扎 · 70–92F」 or a date), the nine-attribute chart with one legend
     * line for the marks it uses, and the tactics.
     */
    static View detail(Context c, Rules.CombatTip combat, String where) {
        LinearLayout body = Ui.column(c);
        body.addView(sheetTitle(c, displayTitle(combat)), Ui.matchWrap());
        String alias = alias(combat);
        if (alias != null) body.addView(english(c, alias, 14), top(c, 0));
        LinearLayout meta = Ui.row(c);
        meta.addView(Ui.tag(c, kindLabel(combat), Ui.alpha(Ui.col(c, R.color.p3r_danger), 210),
            Ui.col(c, R.color.p3r_navy_950)), Ui.wrap());
        if (where != null && !where.isEmpty()) {
            TextView place = Ui.mixed(c, where, 14, Ui.col(c, R.color.p3r_ice_100), false);
            LinearLayout.LayoutParams pp = Ui.weight(1);
            pp.leftMargin = Ui.dp(c, 10);
            meta.addView(place, pp);
        }
        body.addView(meta, top(c, 8));
        if (!combat.enemies.isEmpty()) {
            body.addView(Ui.sectionHeader(c, "弱点与耐性", null), top(c, 14));
            body.addView(matrix(c, combat), top(c, 4));
            String legend = legend(combat);
            if (!legend.isEmpty()) {
                TextView line = Ui.mixed(c, legend, 13, Ui.col(c, R.color.p3r_ink_muted), false);
                line.setLineSpacing(0f, 1.2f);
                body.addView(line, top(c, 6));
            }
        }
        String tactics = combat.tacticsZh;
        if ((tactics == null || tactics.trim().isEmpty()) && combat.enemies.isEmpty()) tactics = combat.detailZh;
        if (tactics != null && !tactics.trim().isEmpty()) {
            body.addView(Ui.sectionHeader(c, "打法", null), top(c, 14));
            TextView text = Ui.mixed(c, TipsCatalogue.officialTerms(tactics.trim()), 15,
                Ui.col(c, R.color.p3r_ice_100), false);
            text.setLineSpacing(0f, 1.3f);
            body.addView(text, top(c, 4));
        }
        return body;
    }

    /** A sheet's title: a cyan blade beside the first line and the bold title, which may wrap. */
    static LinearLayout sheetTitle(Context c, CharSequence label) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        View blade = new View(c);
        blade.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_cyan_400)).skew(Ui.dp(c, 3)));
        blade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(c, 6), Ui.dp(c, 24));
        bp.topMargin = Ui.dp(c, 7);
        bp.rightMargin = Ui.dp(c, 10);
        row.addView(blade, bp);
        TextView title = Ui.title(c, label);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        row.addView(title, Ui.weight(1));
        Ui.pad(row, 0, 2, 0, 4);
        return row;
    }

    /** One line for the chart's marks, only those it uses. */
    static String legend(Rules.CombatTip combat) {
        List<String> parts = new ArrayList<>();
        if (has(combat, "v")) parts.add("×2 伤害翻倍但不会倒地");
        if (has(combat, "*")) parts.add("* 不会被冻结／触电／即死");
        if (has(combat, "z")) parts.add("† 即死能命中");
        return String.join(" · ", parts);
    }

    private static View matrix(Context c, Rules.CombatTip combat) {
        float textScale = Math.max(1f, c.getResources().getConfiguration().fontScale);
        int nameWidth = Ui.dp(c, NAME_WIDTH * textScale);
        int attributeWidth = Ui.dp(c, ATTRIBUTE_WIDTH * textScale);
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setTag("combat_affinity_scroll");
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setFillViewport(false);
        LinearLayout table = Ui.column(c);
        LinearLayout header = Ui.row(c);
        header.addView(cell(c, "敌人", Ui.col(c, R.color.p3r_ink_muted), true),
            new LinearLayout.LayoutParams(nameWidth, Ui.dp(c, 32)));
        for (int i = 0; i < ATTRIBUTES.length; i++) {
            TextView head = cell(c, ATTRIBUTES[i], Ui.col(c, R.color.p3r_cyan_300), true);
            head.setContentDescription(ELEMENTS[i]);
            header.addView(head, new LinearLayout.LayoutParams(attributeWidth, Ui.dp(c, 32)));
        }
        table.addView(header, Ui.matchWrap());
        View rule = Ui.fadingRule(c);
        table.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 1))));
        for (int enemyIndex = 0; enemyIndex < combat.enemies.size(); enemyIndex++) {
            Rules.CombatEnemy enemy = combat.enemies.get(enemyIndex);
            LinearLayout row = Ui.row(c);
            row.setMinimumHeight(Ui.dp(c, 44));
            if (enemyIndex % 2 == 1) row.setBackgroundColor(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 90));
            TextView name = Ui.mixed(c, enemyName(enemy), 12, Ui.col(c, R.color.p3r_ice_100), false);
            Ui.pad(name, 2, 8, 4, 8);
            row.addView(name, new LinearLayout.LayoutParams(nameWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int attribute = 0; attribute < ATTRIBUTES.length; attribute++) {
                String raw = enemy.affinities[attribute];
                TextView affinity = cell(c, affinityLabel(raw), affinityColor(c, raw), false);
                affinity.setTag("combat_affinity_" + enemyIndex + "_" + attribute);
                affinity.setContentDescription(enemyName(enemy) + "，" + ELEMENTS[attribute] + "：" + affinityMeaning(raw));
                String base = base(raw);
                if ("w".equals(base) || "z".equals(base)) {
                    // A weakness reads at a glance: a filled red slanted badge.
                    affinity.setTextColor(Ui.col(c, R.color.p3r_navy_950));
                    affinity.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_danger))
                        .skew(Ui.dp(c, 3)));
                } else {
                    affinity.setBackgroundColor(Ui.alpha(affinityColor(c, raw), "-".equals(raw) ? 0 : 18));
                }
                int gap = Ui.dp(c, 1);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(attributeWidth - 2 * gap, Ui.dp(c, 30));
                ap.gravity = Gravity.CENTER_VERTICAL;
                ap.leftMargin = gap;
                ap.rightMargin = gap;
                row.addView(affinity, ap);
            }
            table.addView(row, Ui.matchWrap());
        }
        scroll.addView(table, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private static TextView cell(Context c, String text, int color, boolean header) {
        TextView cell = Ui.mixed(c, text, 13, color, true);
        if (!header && text.length() >= 2 && !text.startsWith("×")) {
            // 「强耐」「强耐*」 in a phone-width column: condensed, never under 12sp.
            cell.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            cell.setTextScaleX(text.length() > 2 ? 0.78f : 0.86f);
        }
        cell.setGravity(Gravity.CENTER);
        cell.setSingleLine(true);
        cell.setIncludeFontPadding(false);
        return cell;
    }

    static String affinityLabel(String raw) {
        String mark;
        switch (base(raw)) {
            case "w": mark = "弱"; break;
            case "z": mark = "弱†"; break;
            case "v": mark = "×2"; break;
            case "s": mark = "耐"; break;
            case "t": mark = "强耐"; break;
            case "n": mark = "无"; break;
            case "r": mark = "反"; break;
            case "d": mark = "吸"; break;
            default: mark = "—";
        }
        return mark + (raw.contains("*") ? "*" : "");
    }

    private static int affinityColor(Context c, String raw) {
        switch (base(raw)) {
            case "w": case "z": return Ui.col(c, R.color.p3r_danger);
            case "s": case "t": return Ui.col(c, R.color.p3r_cyan_300);
            case "n": return Ui.col(c, R.color.p3r_ink_muted);
            case "r": return Ui.col(c, R.color.combat_reflect);
            case "d": return Ui.col(c, R.color.p3r_good);
            case "v": return Ui.col(c, R.color.ui_gold);
            default: return Ui.col(c, raw.contains("*") ? R.color.ui_gold : R.color.p3r_ink_faint);
        }
    }

    private static String affinityMeaning(String raw) {
        String meaning;
        switch (base(raw)) {
            case "w": meaning = "弱点"; break;
            case "z": meaning = "弱点，即死有效"; break;
            case "v": meaning = "伤害翻倍，但不会倒地"; break;
            case "s": meaning = "耐性，受到一半伤害"; break;
            case "t": meaning = "强耐性，受到四分之一伤害"; break;
            case "n": meaning = "伤害无效"; break;
            case "r": meaning = "反弹"; break;
            case "d": meaning = "吸收"; break;
            default: meaning = "普通伤害";
        }
        return meaning + (raw.contains("*") ? "，副效果无效" : "");
    }

    private static String base(String raw) { return raw.replace("*", ""); }

    private static boolean has(Rules.CombatTip combat, String symbol) {
        for (Rules.CombatEnemy enemy : combat.enemies) {
            for (String affinity : enemy.affinities) if (affinity.contains(symbol)) return true;
        }
        return false;
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams params = Ui.matchWrap();
        params.topMargin = Ui.dp(c, dp);
        return params;
    }
}
