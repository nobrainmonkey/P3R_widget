package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * In-game date picker: month chips (4–12 月 and 1 月, docs/DESIGN_v3.md §10.11) above a 7-column
 * calendar of 2009/2010 (Monday first). Days outside the playable calendar, and days whose
 * daytime and evening are both taken by the story, are greyed and cannot be picked.
 */
final class CalendarPicker extends LinearLayout {
    /** Day selection callback. */
    interface Listener {
        void onDay(int day);
    }

    private static final int[] MONTHS = {4, 5, 6, 7, 8, 9, 10, 11, 12, 1};
    private static final String[] HEAD = {"一", "二", "三", "四", "五", "六", "日"};

    private final Rules rules;
    private final FlowLayout months;
    private final LinearLayout grid;
    private final int markedDay;
    private Listener listener;
    private int selectedDay;
    private int shownMonth;

    /** @param markedDay day to mark as "上次记录" (-1 for none) */
    CalendarPicker(Context c, Rules rules, int selectedDay, int markedDay) {
        super(c);
        setOrientation(VERTICAL);
        this.rules = rules;
        this.selectedDay = selectedDay;
        this.markedDay = markedDay;
        this.shownMonth = Zh.dateOf(selectedDay).getMonthValue();
        months = new FlowLayout(c, 6, 6);
        addView(months, Ui.matchWrap());
        grid = Ui.column(c);
        LayoutParams gp = Ui.matchWrap();
        gp.topMargin = Ui.dp(c, 12);
        addView(grid, gp);
        render();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Selects a day as if tapped (tests). */
    void pick(int day) {
        selectedDay = day;
        shownMonth = Zh.dateOf(day).getMonthValue();
        render();
        if (listener != null) {
            listener.onDay(day);
        }
    }

    /** Year for a game month: April–December are 2009, January is 2010. */
    static int yearOf(int month) {
        return month >= 4 ? 2009 : 2010;
    }

    /** True when the day can be chosen as "now". */
    static boolean selectable(Rules rules, int day) {
        Rules.Day d = rules.day(day);
        if (d == null) {
            return false;
        }
        return d.dayLock == null || d.eveLock == null;
    }

    private void render() {
        Context c = getContext();
        months.removeAllViews();
        for (int m : MONTHS) {
            int first = Zh.dayOf(LocalDate.of(yearOf(m), m, 1));
            int last = Zh.dayOf(LocalDate.of(yearOf(m), m, 1).plusMonths(1)) - 1;
            if (last < rules.firstDay || first > rules.lastDay) {
                continue;
            }
            TextView chip = Ui.chip(c, m + "月", m == shownMonth);
            chip.setMinWidth(Ui.dp(c, 58));
            chip.setOnClickListener(v -> {
                shownMonth = m;
                render();
            });
            months.addView(chip);
        }
        grid.removeAllViews();
        LinearLayout head = Ui.row(c);
        for (String h : HEAD) {
            TextView t = Ui.text(c, h, 12, Ui.col(c, R.color.p3r_ink_muted), true);
            t.setGravity(Gravity.CENTER);
            head.addView(t, Ui.weight(1));
        }
        grid.addView(head);
        LocalDate first = LocalDate.of(yearOf(shownMonth), shownMonth, 1);
        int lead = first.getDayOfWeek().getValue() - 1;
        int length = first.lengthOfMonth();
        LinearLayout row = null;
        for (int cell = 0; cell < lead + length; cell++) {
            if (cell % 7 == 0) {
                row = Ui.row(c);
                LayoutParams rp = Ui.matchWrap();
                rp.topMargin = Ui.dp(c, 4);
                grid.addView(row, rp);
            }
            if (cell < lead) {
                row.addView(new View(c), cellParams(c));
                continue;
            }
            int dom = cell - lead + 1;
            int day = Zh.dayOf(first.withDayOfMonth(dom));
            row.addView(dayCell(c, day, dom), cellParams(c));
        }
        int trailing = (7 - (lead + length) % 7) % 7;
        for (int i = 0; i < trailing && row != null; i++) {
            row.addView(new View(c), cellParams(c));
        }
    }

    /** Equal-width cell with a small gap so the slanted tiles do not touch. */
    private static LayoutParams cellParams(Context c) {
        LayoutParams params = new LayoutParams(0, Ui.dp(c, 44), 1);
        params.leftMargin = Ui.dp(c, 2);
        params.rightMargin = Ui.dp(c, 2);
        return params;
    }

    private View dayCell(Context c, int day, int dom) {
        TextView cell = Ui.numeral(c, String.valueOf(dom), 19, Ui.col(c, R.color.p3r_white));
        cell.setGravity(Gravity.CENTER);
        boolean enabled = selectable(rules, day);
        boolean selected = day == selectedDay;
        Rules.Day info = rules.day(day);
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 5));
        if (selected) {
            bg.fill(Ui.col(c, R.color.p3r_cyan_400));
            cell.setTextColor(Ui.col(c, R.color.p3r_navy_950));
        } else if (!enabled) {
            bg.fill(0);
            cell.setTextColor(Ui.alpha(Ui.col(c, R.color.ui_locked), 150));
            if (info != null) {
                cell.setPaintFlags(cell.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            }
        } else {
            bg.fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 110)).pressedFill(Ui.col(c, R.color.p3r_navy_700));
            if (day == markedDay) {
                bg.stroke(Ui.col(c, R.color.p3r_cyan_300), Ui.dp(c, 1.5f));
            }
            boolean weekend = info != null && info.wd >= 6;
            cell.setTextColor(weekend ? Ui.col(c, R.color.p3r_cyan_200) : Ui.col(c, R.color.p3r_white));
        }
        cell.setBackground(bg);
        String desc = Zh.date(day) + (enabled ? "" : "，剧情占用") + (selected ? "，已选" : "");
        cell.setContentDescription(desc);
        if (enabled) {
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setOnClickListener(v -> pick(day));
        }
        return cell;
    }
}
