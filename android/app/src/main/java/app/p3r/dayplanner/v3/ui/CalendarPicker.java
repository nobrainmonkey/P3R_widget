package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * In-game date picker: one month at a time under 「‹ 10月 ›」 (a sideways swipe turns the month
 * too, within the playable calendar), then a Monday-first grid of 2009/2010. Days the story takes
 * whole are dim and cannot be picked; full moons carry a small moon; the day last recorded keeps a
 * cyan outline.
 */
final class CalendarPicker extends LinearLayout {
    /** Day selection callback. */
    interface Listener {
        void onDay(int day);
    }

    private static final int[] MONTHS = {4, 5, 6, 7, 8, 9, 10, 11, 12, 1};
    private static final String[] HEAD = {"一", "二", "三", "四", "五", "六", "日"};

    private final Rules rules;
    private final ImageView prev;
    private final ImageView next;
    private final TextView monthNumber;
    private final LinearLayout grid;
    private final int markedDay;
    private final int touchSlop;
    private Listener listener;
    private int selectedDay;
    private int shownMonth;
    private float downX;
    private float downY;
    private boolean swiping;

    /** @param markedDay day to mark as "上次记录" (-1 for none) */
    CalendarPicker(Context c, Rules rules, int selectedDay, int markedDay) {
        super(c);
        setOrientation(VERTICAL);
        this.rules = rules;
        this.selectedDay = selectedDay;
        this.markedDay = markedDay;
        this.shownMonth = Zh.dateOf(selectedDay).getMonthValue();
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();

        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        prev = arrow(c, R.drawable.ui_ic_back, "上个月");
        prev.setOnClickListener(v -> turn(-1));
        head.addView(prev, new LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        LinearLayout title = Ui.row(c);
        title.setGravity(Gravity.CENTER | Gravity.BOTTOM);
        monthNumber = Ui.numeral(c, "", 12, Ui.col(c, R.color.p3r_cyan_400));
        monthNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 36);
        monthNumber.setShadowLayer(0.01f, Ui.dp(c, 2), Ui.dp(c, 2), Ui.alpha(Ui.col(c, R.color.p3r_royal_500), 230));
        monthNumber.setPadding(0, 0, Ui.dp(c, 4), Ui.dp(c, 2));
        title.addView(monthNumber, Ui.wrap());
        TextView unit = Ui.text(c, "月", 18, Ui.col(c, R.color.p3r_white), true);
        LayoutParams up = Ui.wrap();
        up.bottomMargin = Ui.dp(c, 5);
        title.addView(unit, up);
        head.addView(title, Ui.weight(1));
        next = arrow(c, R.drawable.ui_ic_chevron, "下个月");
        next.setOnClickListener(v -> turn(1));
        head.addView(next, new LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        addView(head, Ui.matchWrap());

        grid = Ui.column(c);
        LayoutParams gp = Ui.matchWrap();
        gp.topMargin = Ui.dp(c, 6);
        addView(grid, gp);
        render();
    }

    private static ImageView arrow(Context c, int icon, String description) {
        ImageView view = new ImageView(c);
        view.setImageDrawable(Ui.tinted(c, icon, Ui.col(c, R.color.p3r_cyan_200)));
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_soft))
            .skew(Ui.dp(c, 6)));
        view.setContentDescription(description);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
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

    /** True when part of {@code month} lies in the playable calendar. */
    private boolean playable(int month) {
        LocalDate first = LocalDate.of(yearOf(month), month, 1);
        int from = Zh.dayOf(first);
        int to = Zh.dayOf(first.plusMonths(1)) - 1;
        return to >= rules.firstDay && from <= rules.lastDay;
    }

    /** The playable month {@code step} months away from the shown one, or -1. */
    private int monthAfter(int step) {
        int index = -1;
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i] == shownMonth) index = i;
        }
        for (int i = index + step; i >= 0 && i < MONTHS.length; i += step) {
            if (playable(MONTHS[i])) return MONTHS[i];
        }
        return -1;
    }

    /** Shows the next ({@code step} 1) or the previous ({@code -1}) playable month. */
    private void turn(int step) {
        int month = monthAfter(step);
        if (month < 0) {
            return;
        }
        shownMonth = month;
        render();
        Motion.tabIn(grid, step > 0);
    }

    // A sideways swipe over the grid turns the month; taps still reach the days.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                swiping = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (horizontalDrag(e)) {
                    swiping = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                return swiping;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                swiping = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!swiping && horizontalDrag(e)) {
                    swiping = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - downX;
                if (swiping && Math.abs(dx) > Ui.dp(getContext(), 48)) {
                    turn(dx < 0 ? 1 : -1);
                }
                swiping = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                swiping = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private boolean horizontalDrag(MotionEvent e) {
        float dx = Math.abs(e.getX() - downX);
        float dy = Math.abs(e.getY() - downY);
        return dx > touchSlop && dx > dy * 1.5f;
    }

    private void render() {
        Context c = getContext();
        monthNumber.setText(String.valueOf(shownMonth));
        monthNumber.setContentDescription(shownMonth + "月");
        boolean hasPrev = monthAfter(-1) >= 0;
        boolean hasNext = monthAfter(1) >= 0;
        prev.setEnabled(hasPrev);
        prev.setAlpha(hasPrev ? 1f : 0.25f);
        next.setEnabled(hasNext);
        next.setAlpha(hasNext ? 1f : 0.25f);

        grid.removeAllViews();
        LinearLayout head = Ui.row(c);
        for (int i = 0; i < HEAD.length; i++) {
            TextView t = Ui.text(c, HEAD[i], 12,
                Ui.col(c, i == 6 ? R.color.ui_holiday : i == 5 ? R.color.p3r_cyan_300 : R.color.p3r_ink_muted), true);
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
                rp.topMargin = Ui.dp(c, 2);
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

    /** Equal-width 48 dp cell with a small gap so the slanted tiles do not touch. */
    private static LayoutParams cellParams(Context c) {
        LayoutParams params = new LayoutParams(0, Ui.dp(c, Ui.TOUCH), 1);
        params.leftMargin = Ui.dp(c, 2);
        params.rightMargin = Ui.dp(c, 2);
        return params;
    }

    private View dayCell(Context c, int day, int dom) {
        FrameLayout cell = new FrameLayout(c);
        TextView number = Ui.numeral(c, String.valueOf(dom), 20, Ui.col(c, R.color.p3r_white));
        number.setGravity(Gravity.CENTER);
        boolean enabled = selectable(rules, day);
        boolean selected = day == selectedDay;
        Rules.Day info = rules.day(day);
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 5));
        if (selected) {
            bg.fill(Ui.col(c, R.color.p3r_cyan_400))
                .slab(Ui.alpha(Ui.col(c, R.color.p3r_white), 210), Ui.dp(c, 2), Ui.dp(c, 2));
            number.setTextColor(Ui.col(c, R.color.p3r_navy_950));
        } else if (!enabled) {
            bg.fill(0);
            number.setTextColor(Ui.alpha(Ui.col(c, R.color.ui_locked), 110));
        } else {
            bg.fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 110)).pressedFill(Ui.col(c, R.color.p3r_navy_700));
            boolean rest = info != null && (info.type == Rules.DayType.SUNDAY || info.type == Rules.DayType.HOLIDAY);
            boolean saturday = info != null && info.wd == 6;
            number.setTextColor(Ui.col(c, rest ? R.color.p3r_danger : saturday ? R.color.p3r_cyan_200 : R.color.p3r_white));
        }
        if (day == markedDay && !selected) {
            bg.stroke(Ui.col(c, R.color.p3r_cyan_300), Ui.dp(c, 1.5f));
        }
        cell.setBackground(bg);
        cell.addView(number, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        boolean full = info != null && (info.fullMoon || info.moon == 4);
        if (full) {
            MoonDrawable glyph = new MoonDrawable(Ui.col(c, selected ? R.color.p3r_navy_950 : R.color.p3r_cyan_200),
                Ui.col(c, selected ? R.color.p3r_navy_950 : R.color.p3r_cyan_300), Ui.dp(c, 1));
            glyph.setPhase(4);
            View moon = new View(c);
            moon.setBackground(glyph);
            moon.setAlpha(enabled || selected ? 1f : 0.5f);
            moon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8), Gravity.TOP | Gravity.END);
            mp.topMargin = Ui.dp(c, 5);
            mp.rightMargin = Ui.dp(c, 8);
            cell.addView(moon, mp);
        }
        number.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        String desc = Zh.date(day) + (full ? "，满月" : "") + (enabled ? "" : "，剧情占用")
            + (selected ? "，已选" : "") + (day == markedDay ? "，上次记录" : "");
        cell.setContentDescription(desc);
        if (enabled) {
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setOnClickListener(v -> pick(day));
        }
        return cell;
    }
}
