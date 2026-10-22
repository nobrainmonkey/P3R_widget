package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.AvailableActions;

/** Read-only activity directory shared by 今天 and the general calendar. */
final class AvailableActionViews {
    static final String TODAY = "今天可做";
    private static final List<String> ORDER = Arrays.asList(
        "社群", "宿舍", "集体学习", "电玩城", "打工", "营业", "学习", "羁绊剧情", "塔尔塔罗斯", "解锁");

    private AvailableActionViews() {}

    /** UI-only browse choices survive a background plan refresh on the same game date. */
    static final class State {
        private int day = -1;
        private int cursor = -1;
        private int period;
        private final Map<String, Boolean> expanded = new LinkedHashMap<>();

        void bind(int day, int cursor) {
            if (this.day != day) expanded.clear();
            if (this.cursor != cursor || this.day != day) period = cursor & 1;
            this.day = day;
            this.cursor = cursor;
        }
    }

    /** Today's remaining periods; social meetings/unlock errands belong in the calendar. */
    static View today(HomeHost host, UiData data, State state) {
        if (data.availability == null || !data.hasState()) return null;
        int dayIndex = Rules.dayOf(data.displaySlot());
        AvailableActions.Day day = data.availability.day(dayIndex);
        if (day == null) return null;
        state.bind(dayIndex, data.displaySlot());
        Context c = host.activity();
        LinearLayout block = Ui.column(c);
        block.setTag("today-available-actions");
        block.addView(Ui.sectionHeader(c, TODAY, null));

        LinearLayout content = Ui.column(c);
        boolean eveningOnly = Rules.isEvening(data.displaySlot());
        if (eveningOnly) {
            block.addView(periodHeader(c, "晚上"), top(c, 2));
            fillGroups(host, content, day.evening, day.eveningLockZh, true, state, 1);
        } else {
            SegmentedControl periods = new SegmentedControl(c, Arrays.asList(
                Zh.period(data.rules, Rules.slot(dayIndex, false)), "晚上"));
            periods.setSelected(state.period);
            periods.setListener(index -> {
                state.period = index;
                fillGroups(host, content, index == 0 ? day.daytime : day.evening,
                    index == 0 ? day.daytimeLockZh : day.eveningLockZh, true, state, index);
                Motion.enter(content, 0);
            });
            block.addView(periods, top(c, 2));
            fillGroups(host, content, state.period == 0 ? day.daytime : day.evening,
                state.period == 0 ? day.daytimeLockZh : day.eveningLockZh, true, state, state.period);
        }
        block.addView(content, top(c, 6));
        return block;
    }

    /** Complete day directory, with the same category rows and no record/progress-changing action. */
    static View day(HomeHost host, UiData data, AvailableActions.Day day) {
        return day(host, data, day, new State());
    }

    static View day(HomeHost host, UiData data, AvailableActions.Day day, State state) {
        state.bind(day.day, Rules.slot(day.day, false));
        Context c = host.activity();
        LinearLayout page = Ui.column(c);
        Ui.pad(page, 0, 0, 0, 12);
        page.addView(SheetChrome.title(c, Zh.dayAndWeekday(day.day)));
        page.addView(periodHeader(c, Zh.period(data.rules, Rules.slot(day.day, false))), top(c, 8));
        LinearLayout daytime = Ui.column(c);
        fillGroups(host, daytime, day.daytime, day.daytimeLockZh, false, state, 0);
        page.addView(daytime, top(c, 4));
        page.addView(periodHeader(c, "晚上"), top(c, 16));
        LinearLayout evening = Ui.column(c);
        fillGroups(host, evening, day.evening, day.eveningLockZh, false, state, 1);
        page.addView(evening, top(c, 4));
        if (!day.unlocks.isEmpty()) {
            page.addView(Ui.sectionHeader(c, "当天解锁", null), top(c, 14));
            for (String unlock : day.unlocks) {
                TextView line = Ui.body(c, unlock);
                page.addView(line, top(c, 6));
            }
        }
        return page;
    }

    private static View periodHeader(Context c, String period) {
        LinearLayout row = Ui.row(c);
        row.addView(Ui.tag(c, period, Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_navy_950)));
        View rule = Ui.fadingRule(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, Math.max(1, Ui.dp(c, 1)), 1);
        rp.leftMargin = Ui.dp(c, 10);
        row.addView(rule, rp);
        return row;
    }

    private static void fillGroups(HomeHost host, LinearLayout target, List<AvailableActions.Entry> entries,
                                   String lock, boolean today, State state, int period) {
        target.removeAllViews();
        Context c = host.activity();
        if (lock != null && !lock.isEmpty()) {
            LinearLayout row = Ui.row(c);
            row.addView(Ui.icon(c, R.drawable.ui_ic_locked, Ui.col(c, R.color.p3r_ink_muted), 16));
            TextView text = Ui.muted(c, lock);
            LinearLayout.LayoutParams tp = Ui.weight(1);
            tp.leftMargin = Ui.dp(c, 8);
            row.addView(text, tp);
            Ui.pad(row, 2, 12, 2, 10);
            target.addView(row);
            return;
        }
        Map<String, List<AvailableActions.Entry>> groups = new LinkedHashMap<>();
        for (String category : ORDER) groups.put(category, new ArrayList<>());
        for (AvailableActions.Entry entry : entries) {
            if (today && !showToday(entry)) continue;
            String category = entry.categoryZh == null || entry.categoryZh.isEmpty() ? "其他" : entry.categoryZh;
            if (!groups.containsKey(category)) groups.put(category, new ArrayList<>());
            groups.get(category).add(entry);
        }
        boolean shown = false;
        for (Map.Entry<String, List<AvailableActions.Entry>> group : groups.entrySet()) {
            if (group.getValue().isEmpty()) continue;
            target.addView(category(host, group.getKey(), group.getValue(), today, state, period));
            shown = true;
        }
        if (!shown) {
            TextView empty = Ui.muted(c, "这时段没有其他活动");
            Ui.pad(empty, 2, 12, 2, 10);
            target.addView(empty);
        }
    }

    static boolean showToday(AvailableActions.Entry entry) {
        return entry.type == Action.Type.ACTIVITY || entry.type == Action.Type.EPISODE
            || entry.isInformational;
    }

    /** Category rows are compact directories; opening one reveals all its concrete options. */
    private static View category(HomeHost host, String title, List<AvailableActions.Entry> entries, boolean today,
                                 State state, int period) {
        Context c = host.activity();
        LinearLayout group = Ui.column(c);
        LinearLayout head = Ui.row(c);
        head.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        head.setBackground(Ui.band(c).pressedFill(Ui.col(c, R.color.ui_cyan_faint)));
        Ui.pad(head, 10, 7, 8, 7);
        LinearLayout titles = Ui.column(c);
        TextView name = Ui.text(c, title, 15, Ui.col(c, R.color.p3r_white), true);
        titles.addView(name);
        TextView summary = Ui.muted(c, summary(entries));
        summary.setTextSize(13);
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(summary, top(c, 2));
        head.addView(titles, Ui.weight(1));
        TextView count = Ui.numeral(c, Integer.toString(entries.size()), 20, Ui.col(c, R.color.p3r_cyan_300));
        LinearLayout.LayoutParams cp = Ui.wrap();
        cp.leftMargin = Ui.dp(c, 10);
        head.addView(count, cp);
        ImageView arrow = Ui.icon(c, R.drawable.ui_ic_down, Ui.col(c, R.color.p3r_cyan_300), 16);
        LinearLayout.LayoutParams ap = Ui.wrap();
        ap.leftMargin = Ui.dp(c, 8);
        head.addView(arrow, ap);

        LinearLayout rows = Ui.column(c);
        Ui.pad(rows, 10, 0, 8, 6);
        for (AvailableActions.Entry entry : entries) rows.addView(entry(host, entry), Ui.matchWrap());
        boolean initiallyExpanded = false;
        String stateKey = period + ":" + title;
        if (state != null && state.expanded.containsKey(stateKey)) initiallyExpanded = state.expanded.get(stateKey);
        rows.setVisibility(initiallyExpanded ? View.VISIBLE : View.GONE);
        summary.setVisibility(initiallyExpanded ? View.GONE : View.VISIBLE);
        arrow.setRotation(initiallyExpanded ? 180f : 0f);
        head.setContentDescription(title + "，" + entries.size() + "项");
        head.setClickable(true);
        head.setFocusable(true);
        head.setOnClickListener(v -> {
            boolean expand = rows.getVisibility() != View.VISIBLE;
            if (state != null) state.expanded.put(stateKey, expand);
            rows.setVisibility(expand ? View.VISIBLE : View.GONE);
            summary.setVisibility(expand ? View.GONE : View.VISIBLE);
            arrow.animate().rotation(expand ? 180f : 0f).setDuration(Ui.animationsEnabled() ? 180 : 0).start();
            if (android.os.Build.VERSION.SDK_INT >= 30) head.setStateDescription(expand ? "已展开" : "已折叠");
            if (expand) Motion.cascade(rows);
        });
        if (android.os.Build.VERSION.SDK_INT >= 30) head.setStateDescription(initiallyExpanded ? "已展开" : "已折叠");
        group.addView(head);
        group.addView(rows);
        View rule = Ui.fadingRule(c);
        group.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Math.max(1, Ui.dp(c, 1))));
        return group;
    }

    private static View entry(HomeHost host, AvailableActions.Entry entry) {
        Context c = host.activity();
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        Ui.pad(row, 2, 9, 2, 9);
        LinearLayout text = Ui.column(c);
        TextView title = Ui.text(c, entry.title, 15, Ui.col(c, R.color.p3r_ice_100), true);
        text.addView(title);
        if (entry.detail != null && !entry.detail.isEmpty()) {
            TextView detail = Ui.mixed(c, entry.detail, 13, Ui.col(c, R.color.p3r_ink_muted), false);
            text.addView(detail, top(c, 3));
        }
        row.addView(text, Ui.weight(1));
        if (entry.type == Action.Type.MEET && entry.link >= 0) {
            LinearLayout.LayoutParams icon = Ui.wrap();
            icon.leftMargin = Ui.dp(c, 8);
            icon.topMargin = Ui.dp(c, 4);
            row.addView(Ui.icon(c, R.drawable.ui_ic_chevron, Ui.col(c, R.color.p3r_cyan_300), 16), icon);
            row.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
            row.setBackground(Ui.band(c).pressedFill(Ui.col(c, R.color.ui_cyan_faint)));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                host.dismissSheet();
                host.openLink(entry.link);
            });
        }
        return row;
    }

    private static String summary(List<AvailableActions.Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(entries.size(), 3); i++) {
            if (i > 0) out.append(" · ");
            out.append(entries.get(i).title);
        }
        return out.toString();
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
