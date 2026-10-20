package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.text.TextUtils;
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

/** A browsable Monday-first calendar: dates stay compact, the selected day opens below it. */
final class ScheduleMonthGrid extends LinearLayout {
    interface Listener { void onDay(int day); }
    interface Provider { Label label(int day); }

    static final class Label {
        final boolean enabled;
        final String first;
        final String second;
        final String description;

        Label(boolean enabled, String first, String second, String description) {
            this.enabled = enabled;
            this.first = first;
            this.second = second;
            this.description = description;
        }
    }

    private final Rules rules;
    private final LinearLayout grid;
    private final TextView month;
    private final TextView year;
    private final ImageView previous;
    private final ImageView next;
    private final int touchSlop;
    private Provider provider;
    private Listener listener;
    private LocalDate shown;
    private int selected;
    private int today;
    private float downX;
    private float downY;
    private boolean swiping;
    private View todayTile;

    ScheduleMonthGrid(Context c, Rules rules, int day) {
        super(c);
        this.rules = rules;
        selected = clamp(day);
        today = selected;
        shown = Zh.dateOf(selected).withDayOfMonth(1);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setOrientation(VERTICAL);
        LinearLayout navigation = Ui.row(c);
        previous = arrow(c, R.drawable.ui_ic_back, "上个月");
        previous.setOnClickListener(v -> turn(-1));
        navigation.addView(previous, new LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        LinearLayout heading = Ui.row(c);
        heading.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        month = Ui.numeral(c, "", 38, Ui.col(c, R.color.p3r_cyan_400));
        heading.addView(month, Ui.wrap());
        TextView unit = Ui.text(c, "月", 18, Ui.col(c, R.color.p3r_white), true);
        LayoutParams unitParams = Ui.wrap();
        unitParams.leftMargin = Ui.dp(c, 4);
        unitParams.bottomMargin = Ui.dp(c, 5);
        heading.addView(unit, unitParams);
        year = Ui.numeral(c, "", 16, Ui.col(c, R.color.p3r_ink_muted));
        LayoutParams yearParams = Ui.wrap();
        yearParams.leftMargin = Ui.dp(c, 10);
        yearParams.bottomMargin = Ui.dp(c, 5);
        heading.addView(year, yearParams);
        navigation.addView(heading, Ui.weight(1));
        next = arrow(c, R.drawable.ui_ic_chevron, "下个月");
        next.setOnClickListener(v -> turn(1));
        navigation.addView(next, new LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        addView(navigation, Ui.matchWrap());
        grid = Ui.column(c);
        LayoutParams gp = Ui.matchWrap();
        gp.topMargin = Ui.dp(c, 8);
        addView(grid, gp);
    }

    void bind(int currentDay, Provider provider, Listener listener) {
        today = clamp(currentDay);
        this.provider = provider;
        this.listener = listener;
        render();
    }

    int selectedDay() { return selected; }
    int shownMonthDay() { return Zh.dayOf(shown); }

    void select(int day, boolean notify) {
        selected = clamp(day);
        shown = Zh.dateOf(selected).withDayOfMonth(1);
        render();
        if (notify && listener != null) listener.onDay(selected);
    }

    boolean showingToday() {
        return shown.equals(Zh.dateOf(today).withDayOfMonth(1)) && selected == today;
    }

    boolean todayVisible() {
        android.graphics.Rect bounds = new android.graphics.Rect();
        return todayTile != null && todayTile.getGlobalVisibleRect(bounds)
            && bounds.height() >= todayTile.getHeight() / 2;
    }

    private int clamp(int day) {
        return Math.max(rules.firstDay, Math.min(rules.lastDay, day));
    }

    private boolean playable(LocalDate month) {
        return Zh.dayOf(month) <= rules.lastDay && Zh.dayOf(month.plusMonths(1)) > rules.firstDay;
    }

    private void turn(int direction) {
        LocalDate target = shown.plusMonths(direction);
        if (!playable(target)) return;
        shown = target;
        int targetDay = clamp(Zh.dayOf(target.withDayOfMonth(Math.min(Zh.dateOf(selected).getDayOfMonth(),
            target.lengthOfMonth()))));
        // Keep a meaningful selected day as the month changes; a past month in 我的安排 can be empty.
        selected = targetDay;
        render();
        Motion.tabIn(grid, direction > 0);
        if (listener != null) listener.onDay(selected);
    }

    private void render() {
        if (provider == null) return;
        Context c = getContext();
        month.setText(String.valueOf(shown.getMonthValue()));
        month.setContentDescription(shown.getMonthValue() + "月");
        year.setText(String.valueOf(shown.getYear()));
        previous.setEnabled(playable(shown.minusMonths(1)));
        previous.setAlpha(previous.isEnabled() ? 1f : .25f);
        next.setEnabled(playable(shown.plusMonths(1)));
        next.setAlpha(next.isEnabled() ? 1f : .25f);
        grid.removeAllViews();
        todayTile = null;
        LinearLayout weekdays = Ui.row(c);
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < names.length; i++) {
            TextView name = Ui.text(c, names[i], 12,
                Ui.col(c, i == 6 ? R.color.ui_holiday : R.color.p3r_ink_muted), true);
            name.setGravity(Gravity.CENTER);
            weekdays.addView(name, Ui.weight(1));
        }
        grid.addView(weekdays, Ui.matchWrap());
        int lead = shown.getDayOfWeek().getValue() - 1;
        int cells = ((lead + shown.lengthOfMonth() + 6) / 7) * 7;
        LinearLayout row = null;
        for (int i = 0; i < cells; i++) {
            if (i % 7 == 0) {
                row = Ui.row(c);
                LayoutParams rp = Ui.matchWrap();
                rp.topMargin = Ui.dp(c, 4);
                grid.addView(row, rp);
            }
            int dom = i - lead + 1;
            View tile = dom < 1 || dom > shown.lengthOfMonth() ? new View(c)
                : cell(c, Zh.dayOf(shown.withDayOfMonth(dom)), dom);
            float fontScale = c.getResources().getConfiguration().fontScale;
            int height = Math.max(70, (int) Math.ceil(62 * fontScale + 8));
            LayoutParams cp = new LayoutParams(0, Ui.dp(c, height), 1);
            cp.leftMargin = Ui.dp(c, 1);
            cp.rightMargin = Ui.dp(c, 1);
            row.addView(tile, cp);
        }
    }

    private View cell(Context c, int day, int dom) {
        Label label = provider.label(day);
        boolean inRange = day >= rules.firstDay && day <= rules.lastDay;
        boolean enabled = inRange && label != null && label.enabled;
        boolean on = day == selected && enabled;
        boolean now = day == today;
        Rules.Day info = rules.day(day);
        int ink = Ui.col(c, on ? R.color.p3r_navy_950
            : DatePlate.restDay(info) ? R.color.ui_holiday : R.color.p3r_white);
        LinearLayout tile = Ui.column(c);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(tile, 2, 4, 2, 4);
        SlantedPanelDrawable background = new SlantedPanelDrawable().skew(Ui.dp(c, 4))
            .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 95))
            .pressedFill(Ui.col(c, R.color.ui_cyan_soft));
        if (on) background.fill(Ui.col(c, R.color.p3r_cyan_400))
            .slab(Ui.col(c, R.color.p3r_white), Ui.dp(c, 2), Ui.dp(c, 2));
        else if (now) background.stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 1.4f));
        tile.setBackground(background);
        TextView number = Ui.numeral(c, String.valueOf(dom), 24, ink);
        number.setGravity(Gravity.CENTER);
        tile.addView(number, Ui.matchWrap());
        int detailInk = Ui.col(c, on ? R.color.p3r_navy_950 : R.color.p3r_ink_muted);
        for (String text : new String[]{label == null ? "" : label.first, label == null ? "" : label.second}) {
            TextView line = Ui.text(c, text == null ? "" : text, 12, detailInk, false);
            line.setGravity(Gravity.CENTER);
            line.setSingleLine(true);
            line.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(line, Ui.matchWrap());
        }
        tile.setAlpha(enabled ? 1f : .28f);
        tile.setContentDescription("日历 " + Zh.date(day) + (now ? "，今天" : "")
            + (on ? "，已选" : "") + (label == null || label.description == null ? "" : "，" + label.description));
        tile.setSelected(on);
        if (now) todayTile = tile;
        tile.setClickable(enabled);
        tile.setFocusable(enabled);
        for (int i = 0; i < tile.getChildCount(); i++)
            tile.getChildAt(i).setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (enabled) tile.setOnClickListener(v -> select(day, true));
        return tile;
    }

    private static ImageView arrow(Context c, int icon, String description) {
        ImageView button = new ImageView(c);
        button.setImageDrawable(Ui.tinted(c, icon, Ui.col(c, R.color.p3r_cyan_200)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(new SlantedPanelDrawable().fill(0)
            .pressedFill(Ui.col(c, R.color.ui_cyan_soft)).skew(Ui.dp(c, 6)));
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); swiping = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            swiping |= horizontal(event);
            if (swiping && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            return swiping;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downY = event.getY(); swiping = false; return true;
            case MotionEvent.ACTION_MOVE:
                swiping |= horizontal(event); return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                if (swiping && Math.abs(dx) > Ui.dp(getContext(), 48)) turn(dx < 0 ? 1 : -1);
                swiping = false; return true;
            case MotionEvent.ACTION_CANCEL:
                swiping = false; return true;
            default: return super.onTouchEvent(event);
        }
    }

    private boolean horizontal(MotionEvent event) {
        float dx = Math.abs(event.getX() - downX);
        float dy = Math.abs(event.getY() - downY);
        return dx > touchSlop && dx > 1.5f * dy;
    }
}
