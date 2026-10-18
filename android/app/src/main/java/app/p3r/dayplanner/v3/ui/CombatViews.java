package app.p3r.dayplanner.v3.ui;

import android.content.Context;
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

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.TipsCatalogue;

/** Encounter cards and the enemy/phase affinity chart; all game facts come from structured rules. */
final class CombatViews {
    private static final String[] ATTRIBUTES = {"斩", "打", "贯", "火", "冰", "电", "风", "光", "暗"};
    private static final int NAME_WIDTH = 86;
    private static final int ATTRIBUTE_WIDTH = 26;

    private CombatViews() {}

    static View card(Context c, Rules.CombatTip combat, String when, Runnable open) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c).accent(Ui.col(c, R.color.ui_gold), Ui.dp(c, 3)));
        Ui.pad(card, 14, 12, 12, 10);
        LinearLayout badges = Ui.row(c);
        badges.addView(ReminderViews.labelChip(c, kindLabel(combat)));
        TextView date = Ui.mixed(c, when, 12, Ui.col(c, R.color.p3r_ink_muted), false);
        LinearLayout.LayoutParams dateParams = Ui.weight(1);
        dateParams.leftMargin = Ui.dp(c, 10);
        badges.addView(date, dateParams);
        card.addView(badges, Ui.matchWrap());
        card.addView(Ui.mixed(c, title(combat), 17, Ui.col(c, R.color.p3r_white), true), top(c, 8));
        if (!combat.enemies.isEmpty()) {
            card.addView(Ui.mixed(c, "阵容／阶段：" + roster(combat, 3), 12,
                Ui.col(c, R.color.p3r_ice_100), false), top(c, 6));
            card.addView(Ui.mixed(c, weaknessSummary(combat), 13,
                Ui.col(c, R.color.ui_gold), true), top(c, 6));
        } else if (combat.shortZh != null && !combat.shortZh.isEmpty()) {
            card.addView(Ui.mixed(c, combat.shortZh, 13, Ui.col(c, R.color.p3r_ice_100), false), top(c, 6));
        }
        TextView more = Ui.text(c, combat.enemies.isEmpty() ? "查看剧情与打法 ›" : "查看属性与打法 ›", 12,
            Ui.col(c, R.color.p3r_cyan_300), false);
        more.setGravity(Gravity.END);
        card.addView(more, top(c, 8));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(kindLabel(combat) + "，" + when + "，" + title(combat)
            + (combat.enemies.isEmpty() ? "" : "，" + weaknessSummary(combat)) + "，查看详情");
        card.setOnClickListener(v -> open.run());
        return card;
    }

    static View detail(Context c, Rules.CombatTip combat) {
        LinearLayout body = Ui.column(c);
        body.addView(Ui.mixed(c, title(combat), 20, Ui.col(c, R.color.p3r_white), true));
        body.addView(Ui.mixed(c, kindLabel(combat), 12, Ui.col(c, R.color.ui_gold), true), top(c, 6));
        if (!combat.enemies.isEmpty()) {
            body.addView(Ui.sectionHeader(c, "属性对照", null), top(c, 16));
            body.addView(matrix(c, combat), top(c, 6));
            if (has(combat, "v")) body.addView(Ui.mixed(c, "×2：承伤翻倍，不触发弱点。", 11,
                Ui.col(c, R.color.ui_gold), false), top(c, 4));
            if (has(combat, "*")) body.addView(Ui.mixed(c, "*：冻结／触电／即死无效，伤害仍按属性计算。", 11,
                Ui.col(c, R.color.p3r_ink_muted), false), top(c, 4));
            if (has(combat, "z")) body.addView(Ui.mixed(c, "†：即死有效。", 11,
                Ui.col(c, R.color.p3r_ink_muted), false), top(c, 4));
        }
        String tactics = combat.tacticsZh;
        if ((tactics == null || tactics.trim().isEmpty()) && combat.enemies.isEmpty()) tactics = combat.detailZh;
        if (tactics != null && !tactics.trim().isEmpty()) {
            body.addView(Ui.sectionHeader(c, combat.enemies.isEmpty() ? "剧情要点" : "关键打法", null), top(c, 16));
            body.addView(Ui.mixed(c, tactics.trim(), 14, Ui.col(c, R.color.p3r_ice_100), false), top(c, 8));
        }
        return body;
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
        header.setBackgroundColor(Ui.col(c, R.color.ui_card_raised));
        header.addView(cell(c, "敌人／阶段", Ui.col(c, R.color.p3r_ice_100), true),
            new LinearLayout.LayoutParams(nameWidth, Ui.dp(c, 36)));
        for (String attribute : ATTRIBUTES) {
            header.addView(cell(c, attribute, Ui.col(c, R.color.p3r_ice_100), true),
                new LinearLayout.LayoutParams(attributeWidth, Ui.dp(c, 36)));
        }
        table.addView(header, Ui.matchWrap());
        for (int enemyIndex = 0; enemyIndex < combat.enemies.size(); enemyIndex++) {
            Rules.CombatEnemy enemy = combat.enemies.get(enemyIndex);
            LinearLayout row = Ui.row(c);
            row.setMinimumHeight(Ui.dp(c, 48));
            row.setBackgroundColor(Ui.col(c, enemyIndex % 2 == 0 ? R.color.ui_card_sunken : R.color.ui_card));
            TextView name = Ui.mixed(c, enemyName(enemy), 12, Ui.col(c, R.color.p3r_ice_100), false);
            Ui.pad(name, 6, 10, 6, 10);
            row.addView(name, new LinearLayout.LayoutParams(nameWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int attribute = 0; attribute < ATTRIBUTES.length; attribute++) {
                String raw = enemy.affinities[attribute];
                TextView affinity = cell(c, affinityLabel(raw), affinityColor(c, raw), false);
                affinity.setTag("combat_affinity_" + enemyIndex + "_" + attribute);
                affinity.setContentDescription(enemyName(enemy) + "，" + ATTRIBUTES[attribute] + "：" + affinityMeaning(raw));
                affinity.setBackgroundColor(Ui.alpha(affinityColor(c, raw), "-".equals(raw) ? 0 : 18));
                row.addView(affinity, new LinearLayout.LayoutParams(attributeWidth, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            table.addView(row, Ui.matchWrap());
        }
        scroll.addView(table, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private static TextView cell(Context c, String text, int color, boolean header) {
        TextView cell = Ui.mixed(c, text, !header && text.length() > 2 ? 10 : 12, color, header);
        cell.setGravity(Gravity.CENTER);
        cell.setSingleLine(true);
        return cell;
    }

    static String kindLabel(Rules.CombatTip combat) {
        return TipsCatalogue.categoryLabel(combat);
    }

    private static String title(Rules.CombatTip combat) {
        String title = Zh.dots(combat.titleZh);
        return combat.floor > 0 && !title.contains(combat.floor + "F") && !title.contains(combat.floor + "层")
            ? combat.floor + "F · " + title : title;
    }

    private static String roster(Rules.CombatTip combat, int limit) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, combat.enemies.size()); i++) names.add(enemyName(combat.enemies.get(i)));
        return String.join(" / ", names) + (combat.enemies.size() > limit ? " / …" : "");
    }

    private static String enemyName(Rules.CombatEnemy enemy) {
        return Zh.dots(enemy.nameZh) + (enemy.count > 1 ? " ×" + enemy.count : "");
    }

    static String weaknessSummary(Rules.CombatTip combat) {
        Set<String> summaries = new LinkedHashSet<>();
        for (Rules.CombatEnemy enemy : combat.enemies) summaries.add(weakness(enemy));
        if (summaries.size() == 1) return summaries.iterator().next();
        if (combat.enemies.size() > 3) return "弱点随敌人／阶段变化";
        List<String> lines = new ArrayList<>();
        for (Rules.CombatEnemy enemy : combat.enemies) lines.add(enemyName(enemy) + "：" + weakness(enemy));
        return String.join("\n", lines);
    }

    private static String weakness(Rules.CombatEnemy enemy) {
        List<String> weak = new ArrayList<>();
        List<String> doubled = new ArrayList<>();
        for (int i = 0; i < ATTRIBUTES.length; i++) {
            String base = base(enemy.affinities[i]);
            if ("w".equals(base) || "z".equals(base)) weak.add(ATTRIBUTES[i]);
            else if ("v".equals(base)) doubled.add(ATTRIBUTES[i]);
        }
        return (weak.isEmpty() ? "无弱点" : "弱点：" + String.join("、", weak))
            + (doubled.isEmpty() ? "" : "；" + String.join("、", doubled) + "伤害 ×2");
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
            case "v": meaning = "承伤翻倍，不触发弱点"; break;
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
