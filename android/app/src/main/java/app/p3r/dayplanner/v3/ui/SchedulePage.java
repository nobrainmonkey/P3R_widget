package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 日程 tab: the whole remaining calendar as a ListView with month headers and one row per day
 * (白天/放学后 and 晚上 lines, kind icons, grey locks, "≈" for estimated rank-ups, a gold
 * 「提醒：…」 line for the day's 当日提醒). Today is
 * highlighted; tapping a line opens the day detail sheet.
 */
final class SchedulePage {
    private static final int TYPE_SUMMARY = 0;
    private static final int TYPE_MONTH = 1;
    private static final int TYPE_DAY = 2;
    /** Under the summary: how to read the rows. */
    static final String LEGEND = "带「≈」的升级是估计，灰色时段被剧情占用，金色的是顺便要做的事和当天提醒。";

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
    private final TextView empty;
    private final Adapter adapter = new Adapter();
    private UiData data;
    private String scrolledForPlan;

    SchedulePage(HomeHost host) {
        this.host = host;
        this.c = host.activity();
        root = new FrameLayout(c);
        list = new ListView(c);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0));
        list.setDividerHeight(Ui.dp(c, 8));
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        list.setPadding(Ui.dp(c, Ui.GUTTER), Ui.dp(c, 8), Ui.dp(c, Ui.GUTTER), Ui.dp(c, 24));
        list.setAdapter(adapter);
        root.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        empty = Ui.body(c, "");
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 32, 0, 32, 0);
        root.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
    }

    View view() {
        return root;
    }

    void setBottomInset(int px) {
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), px + Ui.dp(c, 24));
    }

    void bind(UiData d) {
        data = d;
        Plan plan = d.plan();
        if (!d.hasState() || plan == null || PlanDiff.finished(plan)) {
            String text;
            if (!d.hasState()) {
                text = "先在「今天」里完成设置，这里会显示到1月31日的完整日程。";
            } else if (plan == null) {
                text = "正在排到1月31日的安排…";
            } else {
                text = "日程已经全部结束。";
            }
            empty.setText(text);
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
            adapter.setItems(new ArrayList<>());
            return;
        }
        empty.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        List<Item> items = buildItems(plan, d.currentSlot());
        adapter.setItems(items);
        if (!plan.planId.equals(scrolledForPlan)) {
            scrolledForPlan = plan.planId;
            int today = Rules.dayOf(Math.max(d.currentSlot(), plan.fromSlot));
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).type == TYPE_DAY && items.get(i).day == today) {
                    list.setSelection(i <= 3 ? 0 : i - 1);
                    break;
                }
            }
        }
    }

    /** Summary, then for every day of the plan a month header when the month changes. */
    static List<Item> buildItems(Plan plan, int currentSlot) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(TYPE_SUMMARY, -1, -1));
        int firstDay = Rules.dayOf(plan.fromSlot);
        int lastDay = Rules.dayOf(plan.toSlot);
        int month = -1;
        for (int day = firstDay; day <= lastDay; day++) {
            int m = Zh.dateOf(day).getMonthValue();
            if (m != month) {
                month = m;
                items.add(new Item(TYPE_MONTH, day, m));
            }
            items.add(new Item(TYPE_DAY, day, m));
        }
        return items;
    }

    /** List adapter with three row types; day rows are recycled. */
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
            return 3;
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
                    return summary();
                case TYPE_MONTH:
                    return month(item.month);
                default:
                    DayRow row = convertView instanceof DayRow ? (DayRow) convertView : new DayRow(c);
                    row.bind(data, item.day);
                    return row;
            }
        }
    }

    private View summary() {
        Plan plan = data.plan();
        LinearLayout box = Ui.column(c);
        Ui.pad(box, 0, 8, 0, 4);
        TextView title = Ui.text(c, "日程", 28, Ui.col(c, R.color.p3r_white), true);
        box.addView(title);
        // Spaced like every token of the line ("共 179 件事", "1 个时段"): "排到 1月31日".
        String text = "排到 " + Zh.date(Rules.dayOf(plan.toSlot)) + " · 共 " + plan.summary.usefulActions + " 件事";
        if (plan.summary.freeSlots > 0) {
            text += " · " + plan.summary.freeSlots + " 个时段自由活动";
        }
        TextView sub = Ui.mixed(c, text, 13, Ui.col(c, R.color.p3r_ink_muted), false);
        box.addView(sub);
        TextView legend = Ui.mixed(c, LEGEND, 12, Ui.col(c, R.color.p3r_ink_faint), false);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(c, 4);
        box.addView(legend, lp);
        box.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View month(int month) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.BOTTOM);
        Ui.pad(row, 2, 20, 0, 6);
        TextView number = Ui.numeral(c, String.valueOf(month), 36, Ui.col(c, R.color.p3r_cyan_400));
        row.addView(number, Ui.wrap());
        TextView unit = Ui.text(c, "月", 18, Ui.col(c, R.color.p3r_white), true);
        LinearLayout.LayoutParams up = Ui.wrap();
        up.leftMargin = Ui.dp(c, 4);
        up.bottomMargin = Ui.dp(c, 4);
        row.addView(unit, up);
        View rule = new View(c);
        rule.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_line)).skew(Ui.dp(c, 2)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, Ui.dp(c, 2), 1);
        rp.leftMargin = Ui.dp(c, 12);
        rp.bottomMargin = Ui.dp(c, 10);
        row.addView(rule, rp);
        row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setContentDescription(month + "月");
        return row;
    }

    /** One calendar day: date column, the two slot lines and the gold 「提醒：…」 line of its 当日提醒. */
    private final class DayRow extends LinearLayout {
        private final TextView dayNumber;
        private final TextView weekday;
        private final TextView todayTag;
        private final SlotRows.Line[] lines = new SlotRows.Line[2];
        private final TextView reminders;

        DayRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            Ui.pad(this, 12, 8, 10, 8);
            LinearLayout dateColumn = Ui.column(context);
            dateColumn.setGravity(Gravity.CENTER_HORIZONTAL);
            dayNumber = Ui.numeral(context, "", 28, Ui.col(context, R.color.p3r_white));
            dateColumn.addView(dayNumber, Ui.wrap());
            weekday = Ui.text(context, "", 12, Ui.col(context, R.color.p3r_ink_muted), true);
            dateColumn.addView(weekday, Ui.wrap());
            todayTag = Ui.text(context, "今天", 11, Ui.col(context, R.color.p3r_navy_950), true);
            todayTag.setBackground(new SlantedPanelDrawable().fill(Ui.col(context, R.color.p3r_cyan_400))
                .skew(Ui.dp(context, 4)));
            Ui.pad(todayTag, 6, 0, 6, 0);
            LayoutParams tagParams = Ui.wrap();
            tagParams.topMargin = Ui.dp(context, 4);
            dateColumn.addView(todayTag, tagParams);
            addView(dateColumn, new LayoutParams(Ui.dp(context, 52), LayoutParams.WRAP_CONTENT));
            LinearLayout slots = Ui.column(context);
            for (int i = 0; i < 2; i++) {
                lines[i] = new SlotRows.Line(context);
                lines[i].setClickable(true);
                lines[i].setFocusable(true);
                lines[i].setBackground(new SlantedPanelDrawable().fill(0)
                    .pressedFill(Ui.col(context, R.color.ui_cyan_faint)));
                slots.addView(lines[i], Ui.matchWrap());
            }
            reminders = Ui.text(context, "", 12, Ui.col(context, R.color.ui_gold), false);
            reminders.setMaxLines(2);
            reminders.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LayoutParams reminderParams = Ui.matchWrap();
            reminderParams.leftMargin = Ui.dp(context, 4);
            reminderParams.bottomMargin = Ui.dp(context, 4);
            slots.addView(reminders, reminderParams);
            LayoutParams slotParams = Ui.weight(1);
            slotParams.leftMargin = Ui.dp(context, 8);
            addView(slots, slotParams);
        }

        void bind(UiData d, int day) {
            Context context = getContext();
            Plan plan = d.plan();
            int current = d.currentSlot();
            boolean today = Rules.dayOf(current) == day;
            dayNumber.setText(String.valueOf(Zh.dateOf(day).getDayOfMonth()));
            Rules.Day info = d.rules.day(day);
            String wd = info != null ? Zh.weekday(info.wd) : Zh.weekdayOf(day);
            weekday.setText(wd);
            boolean weekend = info != null ? info.wd >= 6 : Zh.dateOf(day).getDayOfWeek().getValue() >= 6;
            weekday.setTextColor(weekend ? Ui.col(context, R.color.p3r_cyan_300) : Ui.col(context, R.color.p3r_ink_muted));
            todayTag.setVisibility(today ? VISIBLE : GONE);
            boolean allLocked = true;
            for (int i = 0; i < 2; i++) {
                int slot = Rules.slot(day, i == 1);
                Plan.Step step = plan.at(slot);
                if (step == null) {
                    lines[i].setVisibility(GONE);
                    continue;
                }
                lines[i].setVisibility(VISIBLE);
                SlotRows.State state = slot < current ? SlotRows.State.DONE
                    : slot == current ? SlotRows.State.CURRENT : SlotRows.State.FUTURE;
                lines[i].bind(d.rules, step, state, true);
                allLocked &= "locked".equals(step.kind);
                final int target = slot;
                lines[i].setOnClickListener(v -> host.openDetail(target));
            }
            String reminderLine = ReminderViews.scheduleLine(plan, day);
            reminders.setText(reminderLine == null ? "" : reminderLine);
            reminders.setVisibility(reminderLine == null ? GONE : VISIBLE);
            reminders.setTextColor(Rules.slot(day, true) < current ? Ui.col(context, R.color.p3r_ink_faint)
                : Ui.col(context, R.color.ui_gold));
            dayNumber.setTextColor(allLocked ? Ui.col(context, R.color.ui_locked) : Ui.col(context, R.color.p3r_white));
            SlantedPanelDrawable bg = Ui.card(context);
            if (today) {
                bg.stroke(Ui.col(context, R.color.p3r_cyan_400), Ui.dp(context, 1.5f))
                    .fill(Ui.col(context, R.color.ui_card_raised))
                    .accent(Ui.col(context, R.color.p3r_cyan_400), Ui.dp(context, 4));
            } else if (allLocked) {
                bg.fill(Ui.col(context, R.color.ui_card_sunken));
            }
            setBackground(bg);
            setContentDescription(Zh.date(day) + " " + wd + (today ? "，今天" : "")
                + (reminderLine == null ? "" : "，" + reminderLine));
        }
    }
}
