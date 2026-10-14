package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 规划设置: the profile options (人格面具 three-way choice / 魅力加成 / 目标余量 / 回答方式 / 其余社群
 * / 结缘网站的字条, the 塔尔塔罗斯 sub-page with visits and caps per block, 平安夜, cash floor) and the activities the player
 * asked never to schedule. Every change is saved at once and triggers a replan.
 */
final class SettingsPage {
    /** Segment order of the 人格面具 choice. */
    static final Profile.PersonaMode[] PERSONA_MODES = {
        Profile.PersonaMode.ALL, Profile.PersonaMode.GOALS, Profile.PersonaMode.NONE};

    private SettingsPage() {}

    static View build(HomeHost host) {
        Context c = host.activity();
        Profile p = host.data().profile();
        Rules rules = host.data().rules;
        LinearLayout column = Ui.column(c);

        column.addView(style(host, p));
        column.addView(Ui.sectionHeader(c, "社群点数", null), gapTop(c, 20));
        column.addView(persona(host, p), gapTop(c, 4));
        column.addView(toggle(host, "魅力 MAX 后的加成",
            "魅力升到 MAX 后，社群点数 ×1.51。", p.charmBonus,
            (q, on) -> q.charmBonus = on), gapTop(c, 8));
        column.addView(toggle(host, "目标多留一次见面",
            "目标社群每次升级多排一次见面，以防估计偏高。", p.goalBuffer,
            (q, on) -> q.goalBuffer = on), gapTop(c, 8));
        column.addView(answers(host, p), gapTop(c, 8));
        column.addView(toggle(host, "其余社群也尽量提升",
            "排完目标后，空出来的时段用来提升其他社群。", p.otherLinks,
            (q, on) -> q.otherLinks = on), gapTop(c, 8));
        if (rules != null && rules.datingNote != null) {
            column.addView(toggle(host, "结缘网站的字条", datingNoteText(rules.datingNote), p.datingNotes,
                (q, on) -> q.datingNotes = on), gapTop(c, 8));
        }

        column.addView(Ui.sectionHeader(c, "塔尔塔罗斯", null), gapTop(c, 20));
        column.addView(MePage.entry(c, R.drawable.ui_ic_tower, "每个区块去几次",
            "按区块设置想去几次、最多几次（包括救失踪者）", true,
            () -> host.pushPage("塔尔塔罗斯", TowerSettingsPage.build(host))), gapTop(c, 4));

        column.addView(Ui.sectionHeader(c, "平安夜", null), gapTop(c, 20));
        column.addView(christmas(host, p), gapTop(c, 4));

        column.addView(Ui.sectionHeader(c, "现金", null), gapTop(c, 20));
        column.addView(cashBuffer(host, p), gapTop(c, 4));

        column.addView(Ui.sectionHeader(c, "不再安排的活动", null), gapTop(c, 20));
        column.addView(excluded(host, p), gapTop(c, 4));

        column.addView(fullReplanFooter(host), gapTop(c, 28));
        return column;
    }

    /**
     * Footer: changes here replan on their own; 从头重新安排 is for planning again without leaning on
     * the current plan (opens {@link FullReplanSheet}).
     */
    private static View fullReplanFooter(HomeHost host) {
        Context c = host.activity();
        LinearLayout box = Ui.column(c);
        box.addView(Ui.muted(c, "改动会马上生效，安排也会自动更新；想完全不参考现在的安排、从头排一遍："));
        TextView again = Ui.quiet(c, FullReplanSheet.TITLE);
        again.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        boolean enabled = FullReplanSheet.available(host.data());
        again.setEnabled(enabled);
        again.setAlpha(enabled ? 1f : 0.45f);
        again.setOnClickListener(v -> FullReplanSheet.show(host));
        box.addView(again, Ui.wrap());
        return box;
    }

    /** 「平安夜」: 都婉拒了 / 接受了XX的邀约 (romanceable links at RANK 1 or above). */
    private static View christmas(HomeHost host, Profile p) {
        Context c = host.activity();
        Rules rules = host.data().rules;
        if (rules == null) {
            return Ui.muted(c, "游戏资料暂时读不出来。");
        }
        SimState obs = host.data().observed();
        return ChristmasChoice.build(c, rules, i -> obs == null || i >= obs.rank.length ? 0 : obs.rank[i],
            p.christmasLink, id -> edit(host, q -> q.christmasLink = id));
    }

    /** "在放纵俱乐部买结缘网站的字条…" with the price and the first day it can be bought. */
    static String datingNoteText(Rules.DatingNote note) {
        StringBuilder sb = new StringBuilder();
        // "桐叶购物中心 · 放纵俱乐部" is a label; inside a sentence it reads "桐叶购物中心的放纵俱乐部".
        String where = note.locationZh == null || note.locationZh.trim().isEmpty() ? "放纵俱乐部"
            : note.locationZh.trim().replaceAll("\\s*·\\s*", "的");
        sb.append("在").append(where).append("买结缘网站的字条，当晚回宿舍用公用电脑给一个已解锁的社群加好感，不会直接升级。");
        if (note.cost > 0) {
            sb.append("每张 ").append(Zh.yen(note.cost)).append("。");
        }
        if (note.fromDay > 0) {
            sb.append(Zh.date(note.fromDay)).append("起能买到。");
        }
        return sb.toString();
    }

    /** Choice order of the 安排风格 card. */
    static final Profile.Style[] STYLES = {Profile.Style.BREADTH, Profile.Style.MAX};

    /** Title and one-line explanation of each 安排风格 (in {@link #STYLES} order). */
    static final String[][] STYLE_TEXT = {
        {"尽量都体验", "先把每个社群都解锁、都往上提，再冲 MAX；时间不够时更均衡。"},
        {"尽量多 MAX", "集中时间把尽量多的社群升到 MAX。"},
    };

    /**
     * 安排风格, the first card of the page (DESIGN §20): 尽量都体验 (the default) or 尽量多 MAX,
     * stacked like the 人格面具 choices. A change is saved at once and triggers a replan.
     */
    private static View style(HomeHost host, Profile p) {
        Context c = host.activity();
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 16, 14);
        card.addView(Ui.text(c, "安排风格", 16, Ui.col(c, R.color.p3r_white), true));
        List<LinearLayout> rows = new ArrayList<>();
        int selected = Arrays.asList(STYLES).indexOf(p.style);
        for (int i = 0; i < STYLES.length; i++) {
            final int index = i;
            LinearLayout row = choiceRow(c, STYLE_TEXT[i][0], STYLE_TEXT[i][1]);
            row.setOnClickListener(v -> {
                for (int k = 0; k < rows.size(); k++) {
                    markChoice(c, rows.get(k), STYLE_TEXT[k][0], STYLE_TEXT[k][1], k == index);
                }
                Profile.Style chosen = STYLES[index];
                edit(host, q -> q.style = chosen);
            });
            markChoice(c, row, STYLE_TEXT[i][0], STYLE_TEXT[i][1], i == selected);
            rows.add(row);
            card.addView(row, gapTop(c, i == 0 ? 10 : 6));
        }
        return card;
    }

    /** Title and one-line explanation of each 人格面具 choice (in {@link #PERSONA_MODES} order). */
    static final String[][] PERSONA_TEXT = {
        {"基本都带", "所有社群的点数都按 ×1.51 算。"},
        {"只给目标社群带", "只有目标社群按 ×1.51 算；每次和目标社群的人见面前，会提醒你带哪个阿尔卡那的人格面具。"},
        {"不带/不一定", "都按不带算，最保守。拿不准就选这个。"},
    };

    /** The 人格面具 question as three stacked choices (the labels are too long for one row). */
    private static View persona(HomeHost host, Profile p) {
        Context c = host.activity();
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 16, 14);
        card.addView(Ui.text(c, "见面时带同阿尔卡那的人格面具", 16, Ui.col(c, R.color.p3r_white), true));
        card.addView(Ui.muted(c, "带着和社群同阿尔卡那的人格面具，见面时点数 ×1.51。"));
        List<LinearLayout> rows = new ArrayList<>();
        int selected = Arrays.asList(PERSONA_MODES).indexOf(p.effectivePersonaMode());
        for (int i = 0; i < PERSONA_MODES.length; i++) {
            final int index = i;
            LinearLayout row = choiceRow(c, PERSONA_TEXT[i][0], PERSONA_TEXT[i][1]);
            row.setOnClickListener(v -> {
                for (int k = 0; k < rows.size(); k++) {
                    markChoice(c, rows.get(k), PERSONA_TEXT[k][0], PERSONA_TEXT[k][1], k == index);
                }
                Profile.PersonaMode chosen = PERSONA_MODES[index];
                edit(host, q -> {
                    q.personaMode = chosen;
                    q.personaBonus = chosen == Profile.PersonaMode.ALL;
                });
            });
            markChoice(c, row, PERSONA_TEXT[i][0], PERSONA_TEXT[i][1], i == selected);
            rows.add(row);
            card.addView(row, gapTop(c, i == 0 ? 10 : 6));
        }
        return card;
    }

    /** A tappable choice: slanted radio mark, bold title and a short explanation. */
    static LinearLayout choiceRow(Context c, String title, String description) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(c, 52));
        Ui.pad(row, 12, 8, 12, 8);
        View mark = new View(c);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18));
        mp.rightMargin = Ui.dp(c, 12);
        row.addView(mark, mp);
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, title, 15, Ui.col(c, R.color.p3r_white), true));
        texts.addView(Ui.mixed(c, description, 12, Ui.col(c, R.color.p3r_ink_muted), false));
        row.addView(texts, Ui.weight(1));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    static void markChoice(Context c, LinearLayout row, String title, String description, boolean on) {
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 6))
            .pressedFill(Ui.col(c, R.color.ui_cyan_faint));
        if (on) {
            bg.fill(Ui.col(c, R.color.ui_card_raised)).stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 1.5f));
        } else {
            bg.fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 120)).stroke(Ui.col(c, R.color.p3r_line), Ui.dp(c, 1));
        }
        row.setBackground(bg);
        View mark = row.getChildAt(0);
        SlantedPanelDrawable dot = new SlantedPanelDrawable().skew(Ui.dp(c, 4));
        if (on) {
            dot.fill(Ui.col(c, R.color.p3r_cyan_400));
        } else {
            dot.stroke(Ui.col(c, R.color.p3r_cyan_300), Ui.dp(c, 1.5f));
        }
        mark.setBackground(dot);
        row.setSelected(on);
        row.setContentDescription(title + "，" + description + (on ? "，已选" : ""));
    }

    private static LinearLayout.LayoutParams gapTop(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    /** Applies an edit to the latest profile and saves it. */
    static void edit(HomeHost host, Consumer<Profile> change) {
        Profile edited = host.data().profile().copy();
        change.accept(edited);
        host.saveProfile(edited);
    }

    private interface BoolEdit {
        void apply(Profile profile, boolean on);
    }

    private static View toggle(HomeHost host, String title, String description, boolean value, BoolEdit edit) {
        ToggleRow row = new ToggleRow(host.activity(), title, description, value);
        row.setListener(on -> edit(host, q -> edit.apply(q, on)));
        return row;
    }

    private static View answers(HomeHost host, Profile p) {
        Context c = host.activity();
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 16, 14);
        card.addView(Ui.text(c, "回答方式", 16, Ui.col(c, R.color.p3r_white), true));
        card.addView(Ui.muted(c, "升级那次选对回答能多加好感，下一级来得更快。"));
        SegmentedControl seg = new SegmentedControl(c, Arrays.asList("选最好的回答", "不计回答加成"));
        seg.setSelected(p.answers == Profile.AnswerPolicy.BEST ? 0 : 1);
        seg.setListener(i -> edit(host, q -> q.answers = i == 0 ? Profile.AnswerPolicy.BEST : Profile.AnswerPolicy.NONE));
        card.addView(seg, gapTop(c, 10));
        return card;
    }

    private static View cashBuffer(HomeHost host, Profile p) {
        Context c = host.activity();
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.pressableCard(c));
        row.setMinimumHeight(Ui.dp(c, 64));
        Ui.pad(row, 16, 10, 16, 10);
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, "手上至少留", 16, Ui.col(c, R.color.p3r_white), true));
        texts.addView(Ui.muted(c, "安排花钱的事时，不会让现金低于这个数。"));
        row.addView(texts, Ui.weight(1));
        TextView value = Ui.mixed(c, Zh.yen(p.cashBuffer), 18, Ui.col(c, R.color.p3r_cyan_400), true);
        value.setGravity(Gravity.END);
        row.addView(value, Ui.wrap());
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("手上至少留 " + Zh.yen(p.cashBuffer) + "，点按修改");
        row.setOnClickListener(v -> {
            CashPad.Options options = new CashPad.Options();
            options.title = "手上至少留多少钱？";
            options.initial = (long) host.data().profile().cashBuffer;
            options.allowUnknown = false;
            CashPad.show(c, options, amount -> {
                if (amount == null) {
                    return;
                }
                int buffer = (int) Math.min(Integer.MAX_VALUE, Math.max(0, amount));
                value.setText(Ui.latin(c, Zh.yen(buffer)));
                edit(host, q -> q.cashBuffer = buffer);
            });
        });
        return row;
    }

    private static View excluded(HomeHost host, Profile p) {
        Context c = host.activity();
        LinearLayout box = Ui.column(c);
        if (p.excludedActivities.length == 0) {
            TextView none = Ui.muted(c, "还没有。遇到不想做的安排时，在「做不了」里选「我不想做」，就可以让它以后不再出现。");
            box.addView(none);
            return box;
        }
        Rules rules = host.data().rules;
        for (String id : p.excludedActivities) {
            Rules.Activity activity = rules.activity(id);
            String name = activity == null ? "已不存在的活动" : activity.zh;
            LinearLayout row = Ui.row(c);
            row.setBackground(Ui.card(c));
            Ui.pad(row, 16, 4, 8, 4);
            row.addView(Ui.text(c, name, 15, Ui.col(c, R.color.p3r_white), false), Ui.weight(1));
            TextView restore = Ui.quiet(c, "恢复安排");
            restore.setOnClickListener(v -> edit(host, q -> q.excludedActivities = without(q.excludedActivities, id)));
            row.addView(restore, Ui.wrap());
            box.addView(row, gapTop(c, box.getChildCount() == 0 ? 0 : 8));
        }
        return box;
    }

    static String[] without(String[] ids, String id) {
        List<String> out = new ArrayList<>();
        for (String s : ids) {
            if (!s.equals(id)) {
                out.add(s);
            }
        }
        return out.toArray(new String[0]);
    }

    static String[] with(String[] ids, String id) {
        List<String> out = new ArrayList<>(Arrays.asList(ids));
        if (!out.contains(id)) {
            out.add(id);
        }
        return out.toArray(new String[0]);
    }
}
