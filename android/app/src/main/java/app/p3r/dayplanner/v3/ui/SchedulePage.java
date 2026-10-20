package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.AvailableActions;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * 日程 tab: the rest of the calendar as a borderless timeline. The 「日程 / CALENDAR」 header (with
 * 记录历史 at its right), a big month numeral before each month, then one strip per day: the day
 * numeral and its weekday plate (red on rest days, a moon on full moons), the two slots as slanted
 * period tags and lines (one 「全天」 line for a day the story takes whole), and the day's 当日提醒 as
 * gold chips. Today is the hero slab; yesterday is dimmed and shows what was recorded. The list ends
 * with 「1月31日 · 日程结束」. The 「回到今天」 plate appears only while today is off-screen.
 */
final class SchedulePage {
    private static final int TYPE_SUMMARY = 0;
    private static final int TYPE_MONTH = 1;
    private static final int TYPE_DAY = 2;
    private static final int TYPE_END = 3;
    /** The header's icon button and the sub-page it opens. */
    static final String HISTORY = "记录历史";
    /** A past slot nothing was recorded for. */
    static final String NO_RECORD = "—";

    /** One adapter entry. */
    static final class Item {
        final int type;
        final int day;
        final int month;

        Item(int type, int day, int month) {
            this.type = type;
            this.day = day;
            this.month = month;
        }
    }

    private final HomeHost host;
    private final Context c;
    private final FrameLayout root;
    private final ListView list;
    private final LinearLayout fixedHeader;
    private final ScrollView gridScroll;
    private final LinearLayout gridColumn;
    private final LinearLayout dayDetails;
    private ScheduleMonthGrid monthGrid;
    private Rules gridRules;
    private SegmentedControl sourceControl;
    private ImageView listControl;
    private ImageView gridControl;
    private boolean availableSource;
    private boolean gridMode;
    private int selectedDay = -1;
    private int bottomInset;
    private final AvailableActionViews.State availableBrowse = new AvailableActionViews.State();
    private final ImageView jumpToday;
    private final LinearLayout empty;
    private final Adapter adapter = new Adapter();
    private UiData data;
    private String scrolledForPlan;
    private long historyRevision = Long.MIN_VALUE;
    private int historyCursorDay = -1;
    private boolean historyLoading;
    private List<PlannerApi.HistoryItem> history = new ArrayList<>();
    /** -1 hidden, 0 today above (arrow up), 1 today below (arrow down). */
    private int jumpState = -1;
    private boolean cascaded;

    SchedulePage(HomeHost host) {
        this.host = host;
        this.c = host.activity();
        availableSource = UiPrefs.calendarAvailable(c);
        gridMode = UiPrefs.calendarGrid(c, availableSource);
        root = new FrameLayout(c) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                // A jump or a new calendar moves the list without a scroll callback.
                updateJump(list.getFirstVisiblePosition(), list.getChildCount());
            }

            @Override
            public void onVisibilityAggregated(boolean isVisible) {
                super.onVisibilityAggregated(isVisible);
                if (isVisible) {
                    cascadeOnce();
                }
            }
        };
        LinearLayout body = Ui.column(c);
        root.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        fixedHeader = Ui.column(c);
        Ui.pad(fixedHeader, Ui.GUTTER, 0, Ui.GUTTER, 6);
        body.addView(fixedHeader, Ui.matchWrap());
        FrameLayout content = new FrameLayout(c);
        body.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        list = new ListView(c);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0));
        list.setDividerHeight(Ui.dp(c, 8));
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        list.setPadding(Ui.dp(c, Ui.GUTTER), Ui.dp(c, 8), Ui.dp(c, Ui.GUTTER), Ui.dp(c, 88));
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                updateJump(first, visible);
            }
        });
        content.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        gridScroll = new ScrollView(c);
        gridScroll.setClipToPadding(false);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setVisibility(View.GONE);
        gridScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> updateJump(0, 0));
        gridColumn = Ui.column(c);
        Ui.pad(gridColumn, Ui.GUTTER, 0, Ui.GUTTER, 0);
        dayDetails = Ui.column(c);
        gridScroll.addView(gridColumn, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(gridScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        empty = Ui.column(c);
        Ui.pad(empty, Ui.GUTTER, 8, Ui.GUTTER, 0);
        empty.setVisibility(View.GONE);
        content.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        jumpToday = new ImageView(c);
        jumpToday.setImageDrawable(Ui.tinted(c, R.drawable.ui_ic_up, Ui.col(c, R.color.p3r_navy_950)));
        jumpToday.setScaleType(ImageView.ScaleType.CENTER);
        jumpToday.setBackground(new SlantedPanelDrawable()
            .fill(Ui.col(c, R.color.p3r_white))
            .pressedFill(Ui.col(c, R.color.p3r_cyan_200))
            .slab(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 3), Ui.dp(c, 3))
            .skew(Ui.dp(c, 8)));
        jumpToday.setContentDescription("回到今天");
        jumpToday.setTooltipText("回到今天");
        jumpToday.setClickable(true);
        jumpToday.setFocusable(true);
        jumpToday.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName("android.widget.Button");
            }
        });
        jumpToday.setOnClickListener(v -> jumpToToday(true));
        // Kept in the layout while hidden (INVISIBLE), so it shows at its place at once.
        jumpToday.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams jumpParams = new FrameLayout.LayoutParams(Ui.dp(c, 60), Ui.dp(c, 48),
            Gravity.BOTTOM | Gravity.END);
        jumpParams.rightMargin = Ui.dp(c, Ui.GUTTER);
        jumpParams.bottomMargin = Ui.dp(c, 16);
        root.addView(jumpToday, jumpParams);
    }

    View view() {
        return root;
    }

    void setBottomInset(int px) {
        bottomInset = px;
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), px + Ui.dp(c, 88));
        gridScroll.setPadding(0, 0, 0, px + Ui.dp(c, 88));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) jumpToday.getLayoutParams();
        params.bottomMargin = px + Ui.dp(c, 16);
        jumpToday.setLayoutParams(params);
    }

    void bind(UiData d) {
        int previousDay = data != null && data.hasState() ? Math.min(data.rules.lastDay,
            Rules.dayOf(data.displaySlot())) : -1;
        boolean followedToday = selectedDay < 0 || selectedDay == previousDay;
        data = d;
        rebuildHeader();
        Plan plan = d.displayPlan();
        if (!d.hasState() || d.rules == null || (!availableSource && plan == null)) {
            showEmpty(d);
            return;
        }
        empty.setVisibility(View.GONE);
        empty.removeAllViews();
        if (followedToday || (!availableSource && selectedDay < Math.max(d.rules.firstDay,
                Rules.dayOf(d.displaySlot()) - 1))) selectedDay = Math.max(d.rules.firstDay,
            Math.min(d.rules.lastDay, Rules.dayOf(d.displaySlot())));
        if (gridMode) {
            list.setVisibility(View.GONE);
            gridScroll.setVisibility(View.VISIBLE);
            renderGrid();
            if (!availableSource) loadHistory(d);
            return;
        }
        gridScroll.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        List<Item> items = availableSource ? buildAvailableItems(d.rules)
            : buildItems(plan, d.displaySlot(), d.rules.firstDay);
        adapter.setItems(items);
        if (!availableSource) loadHistory(d);
        String viewKey = availableSource ? "available:" + d.rules.version : plan.planId;
        if (!viewKey.equals(scrolledForPlan)) {
            scrolledForPlan = viewKey;
            jumpToToday(false);
        }
        cascadeOnce();
    }

    /** Source and presentation are separate controls, always reachable above either calendar. */
    private void rebuildHeader() {
        fixedHeader.removeAllViews();
        fixedHeader.addView(headerView(data != null && data.hasState()), Ui.matchWrap());
        if (data == null || !data.hasState() || data.rules == null) return;
        LinearLayout controls = Ui.row(c);
        sourceControl = new SegmentedControl(c, Arrays.asList("我的安排", "活动日历"));
        sourceControl.setSelected(availableSource ? 1 : 0);
        sourceControl.setListener(index -> switchSource(index == 1));
        controls.addView(sourceControl, Ui.weight(1));
        listControl = viewButton(R.drawable.ui_ic_calendar_list, "列表视图", !gridMode);
        listControl.setOnClickListener(v -> switchView(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH));
        lp.leftMargin = Ui.dp(c, 8);
        controls.addView(listControl, lp);
        gridControl = viewButton(R.drawable.ui_ic_calendar_grid, "网格视图", gridMode);
        gridControl.setOnClickListener(v -> switchView(true));
        controls.addView(gridControl, new LinearLayout.LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        fixedHeader.addView(controls, top(4));
    }

    private ImageView viewButton(int icon, String description, boolean selected) {
        ImageView button = new ImageView(c);
        button.setImageDrawable(Ui.tinted(c, icon,
            Ui.col(c, selected ? R.color.p3r_navy_950 : R.color.p3r_cyan_200)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(new SlantedPanelDrawable().skew(Ui.dp(c, 5))
            .fill(selected ? Ui.col(c, R.color.p3r_white) : 0)
            .pressedFill(Ui.col(c, R.color.p3r_cyan_200)));
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setClickable(true);
        button.setFocusable(true);
        button.setSelected(selected);
        return button;
    }

    private void switchSource(boolean available) {
        if (availableSource == available) return;
        availableSource = available;
        gridMode = UiPrefs.calendarGrid(c, available);
        UiPrefs.setCalendarAvailable(c, available);
        if (!available && data != null) {
            int today = Math.max(data.rules.firstDay, Math.min(data.rules.lastDay,
                Rules.dayOf(data.displaySlot())));
            if (selectedDay < Math.max(data.rules.firstDay, today - 1)
                    || selectedDay > data.rules.lastDay) selectedDay = today;
        }
        scrolledForPlan = null;
        bind(data);
        Motion.tabIn(gridMode ? gridScroll : list, available);
    }

    private void switchView(boolean grid) {
        if (gridMode == grid) return;
        gridMode = grid;
        UiPrefs.setCalendarGrid(c, availableSource, grid);
        bind(data);
        Motion.enter(grid ? gridScroll : list, 0);
    }

    private void renderGrid() {
        int scrollY = gridScroll.getScrollY();
        if (monthGrid == null || gridRules != data.rules) {
            gridRules = data.rules;
            monthGrid = new ScheduleMonthGrid(c, data.rules, selectedDay);
        }
        if (monthGrid.selectedDay() != selectedDay) monthGrid.select(selectedDay, false);
        if (monthGrid.getParent() instanceof ViewGroup) ((ViewGroup) monthGrid.getParent()).removeView(monthGrid);
        if (dayDetails.getParent() instanceof ViewGroup) ((ViewGroup) dayDetails.getParent()).removeView(dayDetails);
        gridColumn.removeAllViews();
        gridColumn.addView(monthGrid, Ui.matchWrap());
        gridColumn.addView(dayDetails, top(22));
        monthGrid.bind(Rules.dayOf(data.displaySlot()), this::gridLabel, day -> {
            selectedDay = day;
            renderSelectedDay();
            updateJump(0, 0);
        });
        renderSelectedDay();
        gridScroll.post(() -> gridScroll.scrollTo(0, scrollY));
        updateJump(0, 0);
    }

    private void renderSelectedDay() {
        dayDetails.removeAllViews();
        if (availableSource) {
            AvailableActions.Day available = data.availability.day(selectedDay);
            if (available != null) dayDetails.addView(AvailableActionViews.day(host, data, available, availableBrowse),
                Ui.matchWrap());
        } else {
            Plan plan = data.displayPlan();
            int first = Math.max(data.rules.firstDay, Rules.dayOf(data.displaySlot()) - 1);
            if (selectedDay < first || plan == null || selectedDay > Rules.dayOf(plan.toSlot)) {
                dayDetails.addView(Ui.muted(c, "这一天没有安排"), Ui.matchWrap());
                return;
            }
            DayRow row = new DayRow(c);
            row.bind(data, selectedDay);
            dayDetails.addView(row, Ui.matchWrap());
        }
    }

    private ScheduleMonthGrid.Label gridLabel(int day) {
        if (day < data.rules.firstDay || day > data.rules.lastDay) return null;
        if (availableSource) {
            AvailableActions.Day options = data.availability.day(day);
            if (options == null) return null;
            int social = count(options, "社群");
            int dorm = count(options, "宿舍") + count(options, "集体学习");
            int work = count(options, "打工");
            boolean locked = options.daytimeLockZh != null && options.eveningLockZh != null;
            String first = social > 0 ? "社" + social : !options.unlocks.isEmpty() ? "解锁" : locked ? "剧情" : "";
            String second = dorm > 0 ? "宿" + dorm : work > 0 ? "工" + work
                : count(options, "电玩城") > 0 ? "电玩" : "";
            return new ScheduleMonthGrid.Label(true, first, second,
                availabilitySummary(options.daytime, options.daytimeLockZh) + "；"
                    + availabilitySummary(options.evening, options.eveningLockZh));
        }
        Plan plan = data.displayPlan();
        int earliest = Math.max(data.rules.firstDay, Rules.dayOf(data.displaySlot()) - 1);
        boolean enabled = plan != null && day >= earliest && day <= Rules.dayOf(plan.toSlot);
        if (!enabled) return new ScheduleMonthGrid.Label(false, "", "", "没有安排");
        Plan.Step daytime = displayedStep(Rules.slot(day, false));
        Plan.Step evening = displayedStep(Rules.slot(day, true));
        return new ScheduleMonthGrid.Label(true, compactStep(daytime), compactStep(evening),
            title(daytime) + "；" + title(evening));
    }

    private Plan.Step displayedStep(int slot) {
        boolean recorded = slot < data.displaySlot()
            && (data.snap.storyDisplay == null || !data.snap.storyDisplay.contains(slot));
        return recorded ? recordedStep(data.rules, history, slot, historyLoading) : data.displayPlan().at(slot);
    }

    private String compactStep(Plan.Step step) {
        if (step == null) return "";
        Action action = Action.parse(data.rules, step.action);
        if (action != null && (action.type == Action.Type.MEET || action.type == Action.Type.UNLOCK_STEP
                || action.type == Action.Type.NOTE) && action.target >= 0 && action.target < data.rules.links.length)
            return data.rules.links[action.target].arcanaZh;
        switch (StepKind.of(step)) {
            case DORM: return "宿舍";
            case EPISODE: return "羁绊";
            case JOB: return "打工";
            case STAT: return "能力";
            case TOWER: return "爬塔";
            case LOCKED: return "剧情";
            default: return NO_RECORD.equals(step.titleZh) ? "—"
                : step.slot < data.displaySlot() && !title(step).isEmpty() ? "已记" : "自由";
        }
    }

    private static String title(Plan.Step step) {
        return step == null || step.titleZh == null ? "" : step.titleZh;
    }

    private static int count(AvailableActions.Day day, String category) {
        Set<String> keys = new LinkedHashSet<>();
        for (List<AvailableActions.Entry> entries : Arrays.asList(day.daytime, day.evening))
            for (AvailableActions.Entry entry : entries)
                if (category.equals(entry.categoryZh)) keys.add(entry.key);
        return keys.size();
    }

    private static String availabilitySummary(List<AvailableActions.Entry> entries, String lock) {
        if (lock != null) return lock;
        Set<String> labels = new LinkedHashSet<>();
        for (AvailableActions.Entry entry : entries) {
            if ("社群".equals(entry.categoryZh) || "宿舍".equals(entry.categoryZh)
                    || "集体学习".equals(entry.categoryZh) || "打工".equals(entry.categoryZh)
                    || "电玩城".equals(entry.categoryZh)) labels.add(entry.title);
        }
        return labels.isEmpty() ? "自由活动" : android.text.TextUtils.join(" · ", labels);
    }

    /** General availability can be browsed throughout the game, independent of the current plan. */
    private static List<Item> buildAvailableItems(Rules rules) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(TYPE_SUMMARY, -1, -1));
        int month = -1;
        for (int day = rules.firstDay; day <= rules.lastDay; day++) {
            int m = Zh.dateOf(day).getMonthValue();
            if (m != month) {
                month = m;
                items.add(new Item(TYPE_MONTH, day, m));
            }
            items.add(new Item(TYPE_DAY, day, m));
        }
        items.add(new Item(TYPE_END, rules.lastDay, month));
        return items;
    }

    /** No progress yet: 「还没设置进度」 + 开始设置; planning: 「正在安排…」; a failed plan: 重试. */
    private void showEmpty(UiData d) {
        list.setVisibility(View.GONE);
        gridScroll.setVisibility(View.GONE);
        setJump(-1);
        adapter.setItems(new ArrayList<>());
        empty.removeAllViews();
        empty.setVisibility(View.VISIBLE);
        // Before the first setup there is no history to open.
        LinearLayout box = Ui.column(c);
        Ui.pad(box, 4, 0, 4, 0);
        if (!d.hasState()) {
            box.addView(Ui.text(c, NO_PROGRESS, 22, Ui.col(c, R.color.p3r_white), true));
            TextView start = Ui.primary(c, "开始设置");
            start.setOnClickListener(v -> host.openState("onboarding"));
            box.addView(start, top(18));
        } else if (d.snap.planErrorZh != null && !d.snap.planning && !d.snap.fullReplanning) {
            box.addView(Ui.text(c, "安排没排出来", 22, Ui.col(c, R.color.p3r_white), true));
            TextView retry = Ui.primary(c, "重试");
            retry.setOnClickListener(v -> host.requestReplan());
            box.addView(retry, top(18));
        } else {
            box.addView(Ui.text(c, PLANNING, 22, Ui.col(c, R.color.p3r_white), true));
            box.addView(new BusyStripe(c), top(14));
        }
        empty.addView(box, top(96));
    }

    /** The 日程 tab before the first 同步现状 / 设置. */
    static final String NO_PROGRESS = "还没设置进度";
    /** The 日程 tab while the first plan is being made. */
    static final String PLANNING = "正在安排…";

    private void jumpToToday(boolean smooth) {
        if (data == null || !data.hasState()) return;
        if (gridMode && monthGrid != null && gridScroll.getVisibility() == View.VISIBLE) {
            selectedDay = Math.max(data.rules.firstDay, Math.min(data.rules.lastDay,
                Rules.dayOf(data.displaySlot())));
            monthGrid.select(selectedDay, true);
            gridScroll.smoothScrollTo(0, 0);
            setJump(-1);
            return;
        }
        int position = todayPosition(adapter.items, data.displaySlot());
        if (position < 0) return;
        if (smooth && Ui.animationsEnabled()) {
            list.smoothScrollToPositionFromTop(position, Ui.dp(c, 8), 260);
        } else {
            list.setSelectionFromTop(position, Ui.dp(c, 8));
        }
    }

    /** Scrolls to the day of {@code slot} (the message bar's 「看看」 after a replan). */
    void showSlot(int slot) {
        // A changed arrangement always opens the player's plan, keeping the preferred view.
        if (availableSource) {
            availableSource = false;
            gridMode = UiPrefs.calendarGrid(c, false);
            UiPrefs.setCalendarAvailable(c, false);
            if (data != null) bind(data);
        }
        if (gridMode && monthGrid != null) {
            selectedDay = Rules.dayOf(slot);
            monthGrid.select(selectedDay, true);
            gridScroll.scrollTo(0, 0);
            return;
        }
        int position = data == null || !data.hasState() ? -1 : todayPosition(adapter.items, slot);
        if (position >= 0) {
            list.setSelectionFromTop(position, Ui.dp(c, 8));
        }
    }

    /** Rows cascade in the first time the tab is seen with its calendar. */
    private void cascadeOnce() {
        if (cascaded || !Ui.animationsEnabled() || adapter.items.isEmpty() || !root.isShown()) {
            return;
        }
        cascaded = true;
        list.post(() -> Motion.cascade(list));
    }

    /** Position of today's own row (not the scroll target), or -1. */
    private int todayRow() {
        if (data == null) return -1;
        int today = Rules.dayOf(data.displaySlot());
        int last = -1;
        for (int i = 0; i < adapter.items.size(); i++) {
            Item item = adapter.items.get(i);
            if (item.type != TYPE_DAY) continue;
            last = i;
            if (item.day >= today) return i;
        }
        return last;
    }

    /** 「回到今天」 shows only while today's row is scrolled out, its arrow pointing to it. */
    private void updateJump(int first, int visible) {
        if (gridMode && gridScroll.getVisibility() == View.VISIBLE && monthGrid != null) {
            int today = Math.max(data.rules.firstDay, Math.min(data.rules.lastDay, Rules.dayOf(data.displaySlot())));
            setJump(monthGrid.showingToday() && monthGrid.todayVisible() ? -1
                : monthGrid.selectedDay() >= today ? 0 : 1);
            return;
        }
        int today = todayRow();
        if (today < 0 || list.getVisibility() != View.VISIBLE || visible == 0) {
            setJump(-1);
            return;
        }
        if (today < first) {
            setJump(0);
        } else if (today >= first + visible) {
            setJump(1);
        } else {
            // Partly visible at an edge still counts as on screen only when most of it shows.
            View row = list.getChildAt(today - first);
            boolean clipped = row != null && (row.getBottom() < row.getHeight() / 2
                || row.getTop() > list.getHeight() - list.getPaddingBottom() - row.getHeight() / 2);
            setJump(clipped ? (row.getTop() < 0 ? 0 : 1) : -1);
        }
    }

    private void setJump(int state) {
        if (state == jumpState) return;
        boolean wasShown = jumpState >= 0;
        jumpState = state;
        if (state >= 0) {
            jumpToday.setImageDrawable(Ui.tinted(c, state == 1 ? R.drawable.ui_ic_down : R.drawable.ui_ic_up,
                Ui.col(c, R.color.p3r_navy_950)));
            jumpToday.setVisibility(View.VISIBLE);
            if (!wasShown && Ui.animationsEnabled()) {
                jumpToday.setAlpha(0f);
                jumpToday.animate().alpha(1f).setDuration(150).start();
            } else {
                jumpToday.setAlpha(1f);
            }
        } else {
            jumpToday.animate().cancel();
            jumpToday.setVisibility(View.INVISIBLE);
        }
    }

    /** The game's cursor can sit after the finale; keep its last calendar day reachable. */
    static int todayPosition(List<Item> items, int currentSlot) {
        int today = Rules.dayOf(currentSlot);
        int position = -1;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.type != TYPE_DAY) continue;
            position = i;
            if (item.day >= today) break;
        }
        if (position < 0) return -1;
        // At the top, retain the summary, month and yesterday beside today's highlight.
        if (position <= 3) return 0;
        return items.get(position - 1).type == TYPE_MONTH ? position - 1 : position;
    }

    /** Summary, then for every day of the plan a month header when the month changes. */
    static List<Item> buildItems(Plan plan, int currentSlot) {
        return buildItems(plan, currentSlot, 0);
    }

    /**
     * Header, then from the day before today (never before {@code firstGameDay}) to the plan's last
     * day a month item before each month's first day and one item per day, then the closing plate.
     */
    static List<Item> buildItems(Plan plan, int currentSlot, int firstGameDay) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(TYPE_SUMMARY, -1, -1));
        int lastDay = Rules.dayOf(plan.toSlot);
        int firstDay = Math.max(firstGameDay, Math.min(lastDay, Rules.dayOf(currentSlot) - 1));
        int month = -1;
        for (int day = firstDay; day <= lastDay; day++) {
            int m = Zh.dateOf(day).getMonthValue();
            if (m != month) {
                month = m;
                items.add(new Item(TYPE_MONTH, day, m));
            }
            items.add(new Item(TYPE_DAY, day, m));
        }
        items.add(new Item(TYPE_END, lastDay, month));
        return items;
    }

    private void loadHistory(UiData d) {
        long revision = d.snap.stateRevision;
        int cursorDay = Rules.dayOf(d.displaySlot());
        if (historyRevision == revision && historyCursorDay == cursorDay) return;
        historyRevision = revision;
        historyCursorDay = cursorDay;
        historyLoading = true;
        history = new ArrayList<>();
        Async.run(host.activity(), () -> Api.get(c).history(80), items -> {
            if (historyRevision != revision || historyCursorDay != cursorDay) return;
            historyLoading = false;
            history = items;
            adapter.notifyDataSetChanged();
            if (gridMode && !availableSource && monthGrid != null) renderGrid();
        }, error -> {
            if (historyRevision != revision || historyCursorDay != cursorDay) return;
            historyLoading = false;
            adapter.notifyDataSetChanged();
            if (gridMode && !availableSource && monthGrid != null) renderGrid();
        });
    }

    /** List adapter with four row types; day rows are recycled. */
    private final class Adapter extends BaseAdapter {
        private List<Item> items = new ArrayList<>();

        void setItems(List<Item> newItems) {
            items = newItems;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            Item item = items.get(position);
            return item.type * 100_000L + item.day + 1;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Item item = items.get(position);
            switch (item.type) {
                case TYPE_SUMMARY:
                    return convertView != null ? convertView : listHeader();
                case TYPE_MONTH:
                    // The header's watermark already leaves room above the first month.
                    return month(item.month, position == 1);
                case TYPE_END:
                    return end(item.day);
                default:
                    if (availableSource) {
                        AvailabilityRow available = convertView instanceof AvailabilityRow
                            ? (AvailabilityRow) convertView : new AvailabilityRow();
                        available.bind(item.day);
                        return available;
                    }
                    DayRow row = convertView instanceof DayRow ? (DayRow) convertView : new DayRow(c);
                    row.bind(data, item.day);
                    return row;
            }
        }
    }

    // ------------------------------------------------------------------ header, months, end

    /** The page header as the list's first row. */
    private View listHeader() {
        // The shared header and view controls stay fixed above the scrolling content.
        View header = new View(c);
        header.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            0));
        return header;
    }

    private View headerView(boolean withHistory) {
        if (!withHistory) {
            View head = Ui.pageHeader(c, "日程", "CALENDAR", null);
            Ui.pad(head, 0, 8, 0, 0);
            return head;
        }
        ImageView history = new ImageView(c);
        history.setImageDrawable(Ui.tinted(c, R.drawable.ui_ic_history, Ui.col(c, R.color.p3r_cyan_200)));
        history.setScaleType(ImageView.ScaleType.CENTER);
        history.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 90))
            .pressedFill(Ui.col(c, R.color.ui_cyan_soft))
            .stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 160), Ui.dp(c, 1.2f))
            .skew(Ui.dp(c, 6)));
        history.setContentDescription(HISTORY);
        history.setTooltipText(HISTORY);
        history.setClickable(true);
        history.setFocusable(true);
        history.setOnClickListener(v -> host.pushPage(HISTORY, HistoryPage.build(host)));
        history.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, Ui.TOUCH)));
        View head = Ui.pageHeader(c, "日程", "CALENDAR", history);
        Ui.pad(head, 0, 8, 0, 0);
        return head;
    }

    private View month(int month, boolean first) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.BOTTOM);
        Ui.pad(row, 2, first ? 0 : 26, 0, 8);
        TextView number = Ui.numeral(c, String.valueOf(month), 12, Ui.col(c, R.color.p3r_cyan_400));
        number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 48);
        number.setShadowLayer(0.01f, Ui.dp(c, 2), Ui.dp(c, 2), Ui.alpha(Ui.col(c, R.color.p3r_royal_500), 230));
        number.setPadding(0, 0, Ui.dp(c, 4), Ui.dp(c, 2));
        row.addView(number, Ui.wrap());
        TextView unit = Ui.text(c, "月", 20, Ui.col(c, R.color.p3r_white), true);
        LinearLayout.LayoutParams up = Ui.wrap();
        up.leftMargin = Ui.dp(c, 2);
        up.bottomMargin = Ui.dp(c, 6);
        row.addView(unit, up);
        View rule = Ui.fadingRule(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, Ui.dp(c, 2), 1);
        rp.leftMargin = Ui.dp(c, 12);
        rp.bottomMargin = Ui.dp(c, 12);
        row.addView(rule, rp);
        row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setContentDescription(month + "月");
        return row;
    }

    /** 「1月31日 · 日程结束」 under the last day. */
    private View end(int lastDay) {
        LinearLayout box = Ui.row(c);
        box.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(box, 0, 24, 0, 8);
        TextView plate = Ui.tag(c, endText(lastDay), Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_navy_950));
        box.addView(plate, Ui.wrap());
        View rule = Ui.fadingRule(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, Math.max(1, Ui.dp(c, 1)), 1);
        rp.leftMargin = Ui.dp(c, 12);
        box.addView(rule, rp);
        box.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** The closing plate's words. */
    static String endText(int lastDay) {
        return Zh.date(lastDay) + " · 日程结束";
    }

    // ------------------------------------------------------------------ day rows

    /** Compact read-only overview; all of the selected date's actions open together. */
    private final class AvailabilityRow extends LinearLayout {
        private final TextView heading;
        private final TextView dayLine;
        private final TextView eveningLine;

        AvailabilityRow() {
            super(c);
            setOrientation(VERTICAL);
            Ui.pad(this, 12, 10, 12, 10);
            heading = Ui.mixed(c, "", 17, Ui.col(c, R.color.p3r_white), true);
            addView(heading, Ui.matchWrap());
            dayLine = Ui.body(c, "");
            eveningLine = Ui.body(c, "");
            for (TextView line : new TextView[]{dayLine, eveningLine}) {
                line.setMaxLines(2);
                line.setEllipsize(TextUtils.TruncateAt.END);
                addView(line, top(5));
            }
            setClickable(true);
            setFocusable(true);
        }

        void bind(int day) {
            AvailableActions.Day options = data.availability.day(day);
            if (options == null) return;
            boolean today = day == Rules.dayOf(data.displaySlot());
            heading.setText(Zh.date(day) + " " + Zh.weekdayOf(day) + (today ? " · 今天" : ""));
            dayLine.setText(Zh.period(data.rules, Rules.slot(day, false)) + " · "
                + availabilitySummary(options.daytime, options.daytimeLockZh));
            eveningLine.setText("晚上 · " + availabilitySummary(options.evening, options.eveningLockZh));
            setBackground(today ? Ui.heroCard(c) : Ui.pressableCard(c));
            setContentDescription("查看活动 " + Zh.date(day));
            setOnClickListener(v -> {
                selectedDay = day;
                host.showSheet(AvailableActionViews.day(host, data, options, availableBrowse));
            });
        }
    }

    /** One calendar day: date block, the slot lines and the gold chips of its 当日提醒. */
    private final class DayRow extends LinearLayout {
        private final LinearLayout dateBlock;
        private final TextView dayNumber;
        private final TextView weekday;
        private final SlantedPanelDrawable weekdayPlate;
        private final View moon;
        private final MoonDrawable moonGlyph;
        private final TextView dayTag;
        private final SlotRows.Line[] lines = new SlotRows.Line[2];
        private final EmptyLine[] blanks = new EmptyLine[2];
        private final LinearLayout reminders;
        private final RuleDrawable rule;

        DayRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            rule = new RuleDrawable(Ui.alpha(Ui.col(context, R.color.p3r_cyan_400), 70), Math.max(1, Ui.dp(context, 1)));
            dateBlock = Ui.column(context);
            dayNumber = Ui.numeral(context, "", 12, Ui.col(context, R.color.p3r_white));
            dayNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 32);
            float shadow = Ui.dp(context, 2);
            dayNumber.setShadowLayer(0.01f, shadow, shadow, Ui.alpha(Ui.col(context, R.color.p3r_royal_500), 230));
            dayNumber.setPadding(0, 0, (int) shadow + Ui.dp(context, 2), (int) shadow);
            dateBlock.addView(dayNumber, Ui.wrap());
            LinearLayout plateRow = Ui.row(context);
            weekday = Ui.text(context, "", 12, Ui.col(context, R.color.p3r_navy_950), true);
            weekday.setGravity(Gravity.CENTER);
            weekdayPlate = new SlantedPanelDrawable().fill(Ui.col(context, R.color.p3r_white)).skew(Ui.dp(context, 4));
            weekday.setBackground(weekdayPlate);
            Ui.pad(weekday, 6, 0, 7, 0);
            plateRow.addView(weekday, Ui.wrap());
            moonGlyph = new MoonDrawable(Ui.col(context, R.color.p3r_cyan_200), Ui.col(context, R.color.p3r_cyan_300),
                Ui.dp(context, 1));
            moon = new View(context);
            moon.setBackground(moonGlyph);
            moon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams moonParams = new LayoutParams(Ui.dp(context, 13), Ui.dp(context, 13));
            moonParams.leftMargin = Ui.dp(context, 5);
            plateRow.addView(moon, moonParams);
            LayoutParams plateParams = Ui.wrap();
            plateParams.topMargin = Ui.dp(context, 2);
            dateBlock.addView(plateRow, plateParams);
            dayTag = Ui.text(context, "", 12, Ui.col(context, R.color.p3r_navy_950), true);
            dayTag.setGravity(Gravity.CENTER);
            Ui.pad(dayTag, 6, 0, 7, 0);
            LayoutParams tagParams = Ui.wrap();
            tagParams.topMargin = Ui.dp(context, 5);
            dateBlock.addView(dayTag, tagParams);
            addView(dateBlock, new LayoutParams(Ui.dp(context, 58), LayoutParams.WRAP_CONTENT));

            LinearLayout slots = Ui.column(context);
            for (int i = 0; i < 2; i++) {
                lines[i] = new SlotRows.Line(context);
                lines[i].setClickable(true);
                lines[i].setFocusable(true);
                lines[i].setBackground(new SlantedPanelDrawable().fill(0)
                    .pressedFill(Ui.col(context, R.color.ui_cyan_faint)).skew(Ui.dp(context, 6)));
                slots.addView(lines[i], Ui.matchWrap());
                blanks[i] = new EmptyLine(context);
                slots.addView(blanks[i], Ui.matchWrap());
            }
            reminders = Ui.row(context);
            LayoutParams reminderParams = Ui.matchWrap();
            reminderParams.leftMargin = Ui.dp(context, 2);
            reminderParams.topMargin = Ui.dp(context, 2);
            reminderParams.bottomMargin = Ui.dp(context, 2);
            slots.addView(reminders, reminderParams);
            LayoutParams slotParams = Ui.weight(1);
            slotParams.leftMargin = Ui.dp(context, 4);
            addView(slots, slotParams);
        }

        void bind(UiData d, int day) {
            Context context = getContext();
            Plan plan = d.displayPlan();
            int current = d.displaySlot();
            int todayDay = Rules.dayOf(current);
            boolean today = todayDay == day;
            // Once the calendar is over, 1月31日 is the last day, not 「昨天」.
            boolean over = Slots.finished(d.rules, d.observed()) && d.snap.storyDisplay == null;
            boolean previous = !over && day == todayDay - 1;
            dayNumber.setText(String.valueOf(Zh.dateOf(day).getDayOfMonth()));
            Rules.Day info = d.rules.day(day);
            String wd = info != null ? Zh.weekday(info.wd) : Zh.weekdayOf(day);
            weekday.setText(wd);
            boolean rest = DatePlate.restDay(info);
            weekdayPlate.fill(Ui.col(context, rest ? R.color.ui_holiday : R.color.p3r_white));
            weekday.setTextColor(Ui.col(context, rest ? R.color.p3r_white : R.color.p3r_navy_950));
            boolean full = info != null && (info.fullMoon || info.moon == 4);
            moonGlyph.setPhase(4);
            moon.setVisibility(full ? VISIBLE : GONE);
            if (today) {
                dayTag.setText("今天");
                dayTag.setTextColor(Ui.col(context, R.color.p3r_navy_950));
                dayTag.setBackground(new SlantedPanelDrawable().fill(Ui.col(context, R.color.p3r_cyan_400))
                    .skew(Ui.dp(context, 4)));
                dayTag.setVisibility(VISIBLE);
            } else if (previous) {
                dayTag.setText("昨天");
                dayTag.setTextColor(Ui.col(context, R.color.p3r_ink_faint));
                dayTag.setBackground(new SlantedPanelDrawable().stroke(Ui.col(context, R.color.p3r_ink_faint),
                    Ui.dp(context, 1)).skew(Ui.dp(context, 4)));
                dayTag.setVisibility(VISIBLE);
            } else {
                dayTag.setVisibility(GONE);
            }

            boolean allLocked = true;
            int target = -1;
            Plan.Step[] steps = new Plan.Step[2];
            boolean[] recorded = new boolean[2];
            for (int i = 0; i < 2; i++) {
                int slot = Rules.slot(day, i == 1);
                Plan.Step step = plan.at(slot);
                recorded[i] = slot < current && (d.snap.storyDisplay == null || !d.snap.storyDisplay.contains(slot));
                if (recorded[i]) {
                    step = recordedStep(d.rules, history, slot, historyLoading);
                }
                steps[i] = step;
            }
            // A day the story takes whole is one line: 「全天 · 满月作战」.
            boolean allDay = steps[0] != null && steps[1] != null
                && StepKind.of(steps[0]) == StepKind.LOCKED && StepKind.of(steps[1]) == StepKind.LOCKED
                && SlotRows.title(d.rules, steps[0]).equals(SlotRows.title(d.rules, steps[1]));
            for (int i = 0; i < 2; i++) {
                int slot = Rules.slot(day, i == 1);
                Plan.Step step = steps[i];
                lines[i].setVisibility(GONE);
                blanks[i].setVisibility(GONE);
                if (step == null || (allDay && i == 1)) {
                    continue;
                }
                allLocked &= StepKind.of(step) == StepKind.LOCKED;
                if (recorded[i] && (NO_RECORD.equals(step.titleZh) || step.titleZh.isEmpty())) {
                    // Nothing recorded there (or not read yet): a faint dash, nothing to open.
                    blanks[i].bind(Zh.period(d.rules, slot), step.titleZh);
                    blanks[i].setVisibility(VISIBLE);
                    continue;
                }
                lines[i].setVisibility(VISIBLE);
                SlotRows.State state = slot < current ? SlotRows.State.DONE
                    : slot == current ? SlotRows.State.CURRENT : SlotRows.State.FUTURE;
                lines[i].bind(d.rules, step, state, true);
                if (allDay) {
                    lines[i].setPeriod("全天");
                }
                boolean inert = recorded[i] && StepKind.of(step) == StepKind.LOCKED;
                if (recorded[i] && !inert) {
                    lines[i].setContentDescription(Zh.period(d.rules, slot) + "，" + step.titleZh + "，查看或修改记录");
                }
                lines[i].setClickable(!inert);
                lines[i].setFocusable(!inert);
                if (inert) {
                    lines[i].setOnClickListener(null);
                    lines[i].setClickable(false);
                } else {
                    final int open = slot;
                    lines[i].setOnClickListener(v -> host.openDetail(open));
                    if (target < 0) {
                        target = slot;
                    }
                }
            }
            if (today && current >= Rules.slot(day, false) && current <= Rules.slot(day, true)
                    && (target < 0 || target < current)) {
                target = current;
            }

            String reminderLine = ReminderViews.scheduleLine(plan, day);
            bindReminders(plan, day, reminderLine, Rules.slot(day, true) < current);

            dayNumber.setTextColor(allLocked ? Ui.col(context, R.color.ui_locked) : Ui.col(context, R.color.p3r_white));
            dateBlock.setAlpha(previous ? 0.6f : 1f);
            if (today) {
                setBackground(Ui.heroCard(context));
                Ui.pad(this, 16, 12, 8, 12);
            } else {
                setBackground(rule);
                Ui.pad(this, 4, 8, 0, 10);
            }
            // The date and the chips open the day too, when there is something to open.
            for (View part : new View[]{dateBlock, reminders}) {
                if (target >= 0) {
                    final int open = target;
                    part.setOnClickListener(v -> host.openDetail(open));
                } else {
                    part.setOnClickListener(null);
                    part.setClickable(false);
                }
            }
            setContentDescription(Zh.date(day) + " " + wd + (today ? "，今天" : "")
                + (reminderLine == null ? "" : "，" + reminderLine));
        }

        /** The day's reminder labels as gold chips (at most three, then 「等」); read as 「提醒：…」. */
        private void bindReminders(Plan plan, int day, String line, boolean past) {
            reminders.removeAllViews();
            if (line == null) {
                reminders.setVisibility(GONE);
                return;
            }
            Context context = getContext();
            List<String> labels = labels(plan, day);
            int shown = Math.min(ReminderViews.SCHEDULE_LABELS, labels.size());
            for (int i = 0; i < shown; i++) {
                TextView chip = ReminderViews.labelChip(context, labels.get(i));
                LinearLayout.LayoutParams p = Ui.wrap();
                p.rightMargin = Ui.dp(context, 6);
                reminders.addView(chip, p);
            }
            if (labels.size() > shown) {
                TextView more = Ui.text(context, "等", 12, Ui.col(context, R.color.ui_gold), true);
                more.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                reminders.addView(more, Ui.wrap());
            }
            reminders.setAlpha(past ? 0.45f : 1f);
            reminders.setContentDescription(line);
            reminders.setVisibility(VISIBLE);
        }
    }

    /** The day's reminder labels in plan order, each once. */
    private static List<String> labels(Plan plan, int day) {
        Set<String> labels = new LinkedHashSet<>();
        for (Plan.Reminder r : ReminderViews.ofDay(plan, day)) {
            if (r.labelZh != null && !r.labelZh.trim().isEmpty()) {
                labels.add(r.labelZh.trim());
            }
        }
        return new ArrayList<>(labels);
    }

    /** A past slot without a record: its period tag and a faint dash; nothing to tap. */
    private static final class EmptyLine extends LinearLayout {
        private final TextView period;
        private final TextView dash;

        EmptyLine(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(Ui.dp(c, 44));
            period = Ui.text(c, "", 13, Ui.col(c, R.color.p3r_ink_faint), true);
            period.setGravity(Gravity.CENTER);
            period.setMinWidth(Ui.dp(c, 50));
            Ui.pad(period, 4, 1, 4, 2);
            period.setBackground(new SlantedPanelDrawable()
                .stroke(Ui.alpha(Ui.col(c, R.color.p3r_ink_faint), 170), Ui.dp(c, 1)).skew(Ui.dp(c, 4)));
            LayoutParams pp = Ui.wrap();
            pp.rightMargin = Ui.dp(c, 38);
            addView(period, pp);
            dash = Ui.text(c, "", 16, Ui.col(c, R.color.p3r_ink_faint), false);
            addView(dash, Ui.weight(1));
        }

        void bind(String periodZh, String text) {
            period.setText(periodZh);
            dash.setText(text);
            setContentDescription(text.isEmpty() ? periodZh : periodZh + "，没有记录");
        }
    }

    /** A hairline under a day strip, cyan at the left and fading out to the right. */
    private static final class RuleDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private final int height;
        private int shaderWidth = -1;

        RuleDrawable(int color, int height) {
            this.color = color;
            this.height = height;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            if (shaderWidth != b.width()) {
                shaderWidth = b.width();
                paint.setShader(new LinearGradient(b.left, 0, b.right, 0, color, color & 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            }
            canvas.drawRect(b.left, b.bottom - height, b.right, b.bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Past rows describe the ledger, never a discarded prediction for the same slot: the record's
     * result (date and correction prefixes dropped), the story lock when the calendar took the slot,
     * else {@link #NO_RECORD} (blank while the records are still being read).
     */
    static Plan.Step recordedStep(Rules rules, List<PlannerApi.HistoryItem> items, int slot, boolean loading) {
        PlannerApi.HistoryItem record = HistoryPage.activeAtSlot(items, slot);
        Plan.Step step = new Plan.Step();
        step.slot = slot;
        step.action = "free";
        step.kind = "free";
        if (record != null) {
            step.titleZh = HistoryPage.body(record.summaryZh);
        } else {
            String lock = rules.lockOf(slot);
            if (lock != null) {
                step.kind = "locked";
                step.action = "locked:" + lock;
                step.titleZh = rules.lockReasonsZh.getOrDefault(lock, "剧情占用");
            } else {
                step.titleZh = loading ? "" : NO_RECORD;
            }
        }
        return step;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
