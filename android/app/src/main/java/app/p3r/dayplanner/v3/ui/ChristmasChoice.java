package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 「平安夜」: which 12/24 invitation the player accepted — 「都婉拒了」 (default, Profile.christmasLink
 * null) or one romanceable link at RANK 1 or above. An accepted invitation takes the daytime and
 * the evening of 12/24 (平安夜约会); the plan schedules nothing else then. 规划设置 shows it as one
 * row that opens {@link #sheet}; the state wizard asks inline ({@link #build}) from 12/20 to 12/24.
 */
final class ChristmasChoice {
    /** Title of the 「都婉拒了」 choice. */
    static final String DECLINED = "都婉拒了";
    /** The question, on the sheet and in the wizard. */
    static final String QUESTION = "12月24日接受了谁的邀约？";
    /** Under the accepted choice only. */
    static final String ACCEPTED_NOTE = "白天和晚上都留给平安夜约会";
    /** When no romanceable link is unlocked yet. */
    static final String NONE_YET = "恋爱社群解锁后会出现在这里";

    private ChristmasChoice() {}

    /** Day ordinal of 12/20 (the wizard asks from this date on). */
    static int askFromDay() {
        return Zh.dayOf(LocalDate.of(2009, 12, 20));
    }

    /** Day ordinal of 12/24 (after it, the answer no longer changes the plan: the wizard stops asking). */
    static int askUntilDay() {
        return Zh.dayOf(LocalDate.of(2009, 12, 24));
    }

    /** "接受了山岸风花的邀约". */
    static String acceptedText(Rules.Link link) {
        return "接受了" + link.personZh + "的邀约";
    }

    /** Short answer for summaries ("都婉拒了" / "接受了山岸风花的邀约"). */
    static String answerText(Rules rules, String linkId) {
        if (linkId == null) {
            return DECLINED;
        }
        Rules.Link link = rules == null ? null : rules.link(linkId);
        return link == null ? "接受了邀约" : acceptedText(link);
    }

    /** The value of the 规划设置 row: 「都婉拒了」 or the person ("山岸风花"). */
    static String shortAnswer(Rules rules, String linkId) {
        if (linkId == null) {
            return DECLINED;
        }
        Rules.Link link = rules == null ? null : rules.link(linkId);
        return link == null ? "接受了邀约" : link.personZh;
    }

    /**
     * Links that can be chosen: romanceable links at RANK 1 or above in {@code rank}, plus the
     * current choice even when it no longer qualifies (so it is never dropped silently).
     */
    static List<Rules.Link> candidates(Rules rules, IntUnaryOperator rank, String current) {
        List<Rules.Link> out = new ArrayList<>();
        for (Rules.Link link : rules.links) {
            if (!link.romanceable || link.kind == Rules.LinkKind.STORY) {
                continue;
            }
            boolean reached = link.idx < Profile.LINKS && rank.applyAsInt(link.idx) >= 1;
            if (reached || link.id.equals(current)) {
                out.add(link);
            }
        }
        return out;
    }

    /**
     * The question as a card of choices (the state wizard); {@code onPick} receives the link id, or
     * null for 「都婉拒了」.
     */
    static View build(Context c, Rules rules, IntUnaryOperator rank, String current, Consumer<String> onPick) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 16, 12);
        card.addView(Ui.mixed(c, QUESTION, 16, Ui.col(c, R.color.p3r_white), true));
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, 8);
        card.addView(choices(c, rules, rank, current, onPick), p);
        return card;
    }

    /** 规划设置's sheet: the question and the choices; a pick saves and closes it. */
    static void sheet(HomeHost host, Rules rules, IntUnaryOperator rank, String current, Consumer<String> onPick) {
        Context c = host.activity();
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 20);
        sheet.addView(SheetChrome.title(c, QUESTION));
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, 8);
        sheet.addView(choices(c, rules, rank, current, id -> {
            onPick.accept(id);
            host.dismissSheet();
        }), p);
        host.showSheet(sheet);
    }

    /**
     * The choices: 「都婉拒了」 and one row per candidate (name, then 「女教皇 · RANK 3」). A row is
     * announced as 「接受了…的邀约」; only the accepted one says what the day becomes.
     */
    static LinearLayout choices(Context c, Rules rules, IntUnaryOperator rank, String current, Consumer<String> onPick) {
        LinearLayout list = Ui.column(c);
        List<Rules.Link> links = candidates(rules, rank, current);
        List<String> ids = new ArrayList<>();
        List<String[]> texts = new ArrayList<>();
        ids.add(null);
        texts.add(new String[]{DECLINED, null, DECLINED});
        for (Rules.Link link : links) {
            ids.add(link.id);
            int r = rank.applyAsInt(link.idx);
            texts.add(new String[]{link.personZh, link.arcanaZh + " · " + (r >= 1 ? Zh.rank(r) : "未解锁"),
                acceptedText(link)});
        }
        List<LinearLayout> rows = new ArrayList<>();
        List<TextView> notes = new ArrayList<>();
        int selected = Math.max(0, ids.indexOf(current));
        for (int i = 0; i < ids.size(); i++) {
            final int index = i;
            String[] t = texts.get(i);
            LinearLayout row = SettingsPage.choiceRow(c, t[0], t[1]);
            TextView note = Ui.text(c, ACCEPTED_NOTE, 13, Ui.col(c, R.color.p3r_cyan_300), false);
            ((LinearLayout) row.getChildAt(1)).addView(note);
            notes.add(note);
            row.setOnClickListener(v -> {
                for (int k = 0; k < rows.size(); k++) {
                    mark(c, rows.get(k), notes.get(k), texts.get(k), ids.get(k) != null, k == index);
                }
                onPick.accept(ids.get(index));
            });
            mark(c, row, note, t, ids.get(i) != null, i == selected);
            rows.add(row);
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(c, i == 0 ? 0 : 4);
            list.addView(row, p);
        }
        if (links.isEmpty()) {
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(c, 8);
            list.addView(Ui.caption(c, NONE_YET), p);
        }
        return list;
    }

    private static void mark(Context c, LinearLayout row, TextView note, String[] t, boolean accepted, boolean on) {
        SettingsPage.markChoice(c, row, t[0], t[1], on);
        note.setVisibility(accepted && on ? View.VISIBLE : View.GONE);
        row.setContentDescription(t[2]);
    }
}
