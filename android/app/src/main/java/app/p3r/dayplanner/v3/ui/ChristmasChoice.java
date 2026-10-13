package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

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
 * null) or 「接受了XX的邀约」 for a romanceable link at RANK 1 or above. An accepted invitation takes
 * the daytime and the evening of 12/24 (平安夜约会); the plan schedules nothing else then. Shown in
 * 规划设置 and in the state wizard (from 12/20, and always in 修改进度).
 */
final class ChristmasChoice {
    /** Title of the 「都婉拒了」 choice. */
    static final String DECLINED = "都婉拒了";

    private ChristmasChoice() {}

    /** Day ordinal of 12/20 (the wizard asks from this date on). */
    static int askFromDay() {
        return Zh.dayOf(LocalDate.of(2009, 12, 20));
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
     * The question as a card of choices; {@code onPick} receives the link id, or null for
     * 「都婉拒了」.
     */
    static View build(Context c, Rules rules, IntUnaryOperator rank, String current, Consumer<String> onPick) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 16, 14);
        card.addView(Ui.text(c, "12月24日的邀约", 16, Ui.col(c, R.color.p3r_white), true));
        card.addView(Ui.muted(c, "默认按都婉拒了安排。接受了谁的邀约，这天白天和晚上就都留给约会，不再安排别的事。"));
        List<Rules.Link> links = candidates(rules, rank, current);
        List<String> ids = new ArrayList<>();
        List<String[]> texts = new ArrayList<>();
        ids.add(null);
        texts.add(new String[]{DECLINED, "这天照常安排其他事"});
        for (Rules.Link link : links) {
            ids.add(link.id);
            int r = rank.applyAsInt(link.idx);
            texts.add(new String[]{acceptedText(link), link.arcanaZh + " · " + (r >= 1 ? Zh.rank(r) : "未解锁")});
        }
        List<LinearLayout> rows = new ArrayList<>();
        int selected = Math.max(0, ids.indexOf(current));
        for (int i = 0; i < ids.size(); i++) {
            final int index = i;
            String[] t = texts.get(i);
            LinearLayout row = SettingsPage.choiceRow(c, t[0], t[1]);
            row.setOnClickListener(v -> {
                for (int k = 0; k < rows.size(); k++) {
                    SettingsPage.markChoice(c, rows.get(k), texts.get(k)[0], texts.get(k)[1], k == index);
                }
                onPick.accept(ids.get(index));
            });
            SettingsPage.markChoice(c, row, t[0], t[1], i == selected);
            rows.add(row);
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(c, i == 0 ? 10 : 6);
            card.addView(row, p);
        }
        if (links.isEmpty()) {
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(c, 8);
            card.addView(Ui.caption(c, "能恋爱的社群升到 RANK 1 以上后，会列在这里。"), p);
        }
        return card;
    }
}
