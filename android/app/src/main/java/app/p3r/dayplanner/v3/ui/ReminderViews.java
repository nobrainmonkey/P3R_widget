package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 当日提醒 in the app (docs/DESIGN_v3.md §17.2): the 「今天别错过」 block on 今天, the 「当天提醒」
 * section of the day sheet, the gold 「提醒：…」 line of a 日程 day and the gold 「提醒 N」 marker of the
 * 接下来 rows. The reminders are plan data ({@link Plan#reminders}) in final Chinese: slot-free things
 * to do or answer on a game day, shown, never recorded and not counted in the forecast. Their gold is
 * the colour of the 顺便 side steps: something to do on the way.
 */
final class ReminderViews {
    /** The 今天 block: what the rest of today has. */
    static final String TODAY_TITLE = "今天别错过";
    /** The day sheet's section: everything the day has. */
    static final String DAY_TITLE = "当天提醒";
    /** Under a reminder whose answer has a single source or is an estimate. */
    static final String ESTIMATE_CAPTION = "这条只有一份攻略写过，仅供参考";
    /** Starts the 日程 day's third line: 「提醒：课堂提问 · 电视购物」. */
    static final String SCHEDULE_PREFIX = "提醒：";
    /** Labels a 日程 line names before 「等」. */
    static final int SCHEDULE_LABELS = 3;

    private ReminderViews() {}

    /** The reminders of {@code day}, in plan order (slot, then most important first). */
    static List<Plan.Reminder> ofDay(Plan plan, int day) {
        if (plan == null || plan.reminders == null) {
            return new ArrayList<>();
        }
        return plan.remindersIn(Rules.slot(day, false), Rules.slot(day, true));
    }

    /** 今天别错过: the reminders of the observed day from the observed slot on. */
    static List<Plan.Reminder> today(Plan plan, int observedSlot) {
        if (plan == null || observedSlot < 0) {
            return new ArrayList<>();
        }
        return plan.remindersIn(observedSlot, Rules.slot(Rules.dayOf(observedSlot), true));
    }

    /** Reminders whose card is {@code slot}. */
    static int countAt(Plan plan, int slot) {
        return plan == null ? 0 : plan.remindersIn(slot, slot).size();
    }

    // ------------------------------------------------------------------ 今天

    /**
     * 「今天别错过」 under the hero: one row per reminder of the rest of today (label chip, line, what it
     * gives); a row opens the day sheet of its slot. Null when there is none.
     */
    static View todayBlock(HomeHost host, UiData d) {
        List<Plan.Reminder> reminders = today(d.plan(), d.currentSlot());
        if (reminders.isEmpty()) {
            return null;
        }
        Context c = host.activity();
        LinearLayout box = Ui.column(c);
        box.addView(Ui.sectionHeader(c, TODAY_TITLE, null));
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c).accent(Ui.col(c, R.color.ui_gold), Ui.dp(c, 4)));
        Ui.pad(card, 14, 6, 12, 6);
        for (Plan.Reminder r : reminders) {
            LinearLayout row = row(c, r, false);
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint)));
            final int slot = r.slot;
            row.setOnClickListener(v -> host.openDetail(slot));
            card.addView(row);
        }
        LinearLayout.LayoutParams cp = Ui.matchWrap();
        cp.topMargin = Ui.dp(c, 4);
        box.addView(card, cp);
        return box;
    }

    // ------------------------------------------------------------------ day sheet

    /**
     * 「当天提醒」 for the day sheet: every reminder of the day with its longer text, and
     * {@link #ESTIMATE_CAPTION} under an estimated answer. Null when the day has none.
     */
    static View daySection(Context c, Plan plan, int day) {
        List<Plan.Reminder> reminders = ofDay(plan, day);
        if (reminders.isEmpty()) {
            return null;
        }
        LinearLayout box = Ui.column(c);
        box.addView(Ui.sectionHeader(c, DAY_TITLE, null));
        for (int i = 0; i < reminders.size(); i++) {
            LinearLayout.LayoutParams rp = Ui.matchWrap();
            rp.topMargin = Ui.dp(c, i == 0 ? 0 : 8);
            box.addView(row(c, reminders.get(i), true), rp);
        }
        return box;
    }

    // ------------------------------------------------------------------ rows

    /**
     * One reminder: gold label chip, the line and, beside it, what it gives (「魅力 +2」); with
     * {@code detail}, its longer text and the estimate caption below.
     */
    static LinearLayout row(Context c, Plan.Reminder r, boolean detail) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        row.setMinimumHeight(Ui.dp(c, 40));
        Ui.pad(row, 0, 6, 0, 6);
        TextView chip = labelChip(c, r.labelZh);
        LinearLayout.LayoutParams chipParams = Ui.wrap();
        chipParams.topMargin = Ui.dp(c, 2);
        row.addView(chip, chipParams);
        LinearLayout texts = Ui.column(c);
        LinearLayout line = Ui.row(c);
        line.setGravity(Gravity.TOP);
        TextView text = Ui.mixed(c, r.textZh, 14, Ui.col(c, R.color.p3r_white), detail);
        line.addView(text, Ui.weight(1));
        String gain = blank(r.gainZh) ? null : r.gainZh.trim();
        if (gain != null) {
            TextView g = Ui.mixed(c, gain, 13, Ui.col(c, R.color.p3r_cyan_300), true);
            LinearLayout.LayoutParams gp = Ui.wrap();
            gp.leftMargin = Ui.dp(c, 8);
            line.addView(g, gp);
        }
        texts.addView(line);
        StringBuilder spoken = new StringBuilder(blank(r.labelZh) ? "" : r.labelZh.trim() + "，").append(r.textZh);
        if (gain != null) {
            spoken.append("，").append(gain);
        }
        if (detail && !blank(r.detailZh)) {
            TextView more = Ui.mixed(c, r.detailZh.trim(), 13, Ui.col(c, R.color.p3r_ink_muted), false);
            more.setLineSpacing(0f, 1.2f);
            LinearLayout.LayoutParams mp = Ui.matchWrap();
            mp.topMargin = Ui.dp(c, 3);
            texts.addView(more, mp);
            spoken.append("。").append(r.detailZh.trim());
        }
        if (detail && r.estimate) {
            TextView caption = Ui.text(c, ESTIMATE_CAPTION, 12, Ui.col(c, R.color.ui_gold), false);
            LinearLayout.LayoutParams ep = Ui.matchWrap();
            ep.topMargin = Ui.dp(c, 3);
            texts.addView(caption, ep);
            spoken.append("。").append(ESTIMATE_CAPTION);
        }
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 10);
        row.addView(texts, tp);
        row.setContentDescription(spoken.toString());
        return row;
    }

    /** The kind label (「课堂提问」) on a small gold chip. */
    static TextView labelChip(Context c, String label) {
        TextView chip = Ui.text(c, blank(label) ? "提醒" : label.trim(), 11, Ui.col(c, R.color.p3r_navy_950), true);
        chip.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.ui_gold)).skew(Ui.dp(c, 4)));
        Ui.pad(chip, 7, 1, 8, 1);
        chip.setMaxLines(1);
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return chip;
    }

    // ------------------------------------------------------------------ 日程 and 接下来

    /**
     * The 日程 day's third line: 「提醒：课堂提问 · 电视购物」, the day's labels in order (each once), at
     * most {@value #SCHEDULE_LABELS} and then 「等」; null when the day has none.
     */
    static String scheduleLine(Plan plan, int day) {
        Set<String> labels = new LinkedHashSet<>();
        for (Plan.Reminder r : ofDay(plan, day)) {
            if (!blank(r.labelZh)) {
                labels.add(r.labelZh.trim());
            }
        }
        if (labels.isEmpty()) {
            return null;
        }
        List<String> shown = new ArrayList<>(labels);
        boolean more = shown.size() > SCHEDULE_LABELS;
        if (more) {
            shown = shown.subList(0, SCHEDULE_LABELS);
        }
        return SCHEDULE_PREFIX + String.join(" · ", shown) + (more ? " 等" : "");
    }

    /** The gold 「提醒 N」 marker for a 接下来 row with {@code count} reminders; null for none. */
    static TextView marker(Context c, int count) {
        if (count <= 0) {
            return null;
        }
        TextView tag = Ui.mixed(c, "提醒 " + count, 11, Ui.col(c, R.color.ui_gold), true);
        tag.setBackground(new SlantedPanelDrawable().stroke(Ui.col(c, R.color.ui_gold), Ui.dp(c, 1)).skew(Ui.dp(c, 4)));
        Ui.pad(tag, 6, 1, 7, 1);
        tag.setMaxLines(1);
        tag.setContentDescription(count + " 条当日提醒");
        return tag;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
