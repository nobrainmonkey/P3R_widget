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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PeriodTips;

/** All reminders of the current Tartarus opening period, independent of the remaining plan. */
final class TipsPage {
    static final String LEGEND = "从本次楼层开放，到下次开放前一天。跨期仍有效的委托也会保留；日期已过不代表已经完成。";
    private static final int SUMMARY = 0;
    private static final int SECTION = 1;
    private static final int REMINDER = 2;
    private static final int ACTIVE = 0;
    private static final int UPCOMING = 1;
    private static final int PAST = 2;
    private static final String[] SECTION_TITLES = {"当前要留意", "接下来别错过", "本期已过日期"};

    static final class Item {
        final int type;
        final int section;
        final Plan.Reminder reminder;
        final int count;

        Item(int type, int section, Plan.Reminder reminder, int count) {
            this.type = type;
            this.section = section;
            this.reminder = reminder;
            this.count = count;
        }
    }

    private final HomeHost host;
    private final Context c;
    private final FrameLayout root;
    private final ListView list;
    private final TextView empty;
    private final Adapter adapter = new Adapter();
    private PeriodTips.Period period;
    private int currentSlot = -1;

    TipsPage(HomeHost host) {
        this.host = host;
        c = host.activity();
        root = new FrameLayout(c);
        list = new ListView(c);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0));
        list.setDividerHeight(Ui.dp(c, 8));
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        Ui.pad(list, Ui.GUTTER, 8, Ui.GUTTER, 24);
        list.setAdapter(adapter);
        root.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        empty = Ui.body(c, "先在「今天」里完成设置，这里会按游戏日期汇总当期提示。");
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 32, 0, 32, 0);
        root.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
    }

    View view() {
        return root;
    }

    void bind(UiData data) {
        currentSlot = data.currentSlot();
        Plan currentPlan = data.snap != null && data.snap.planCurrent ? data.plan() : null;
        period = data.hasState() ? PeriodTips.collect(data.rules, data.observed(), currentPlan) : null;
        empty.setVisibility(period == null ? View.VISIBLE : View.GONE);
        list.setVisibility(period == null ? View.GONE : View.VISIBLE);
        adapter.setItems(period == null ? new ArrayList<>()
            : buildItems(period.items, currentSlot, period.ongoingIds, period.calendarEndSlots));
    }

    /** Current windows first, then future dates and the period's past dates; every item appears once. */
    static List<Item> buildItems(List<Plan.Reminder> reminders, int observedSlot) {
        return buildItems(reminders, observedSlot, Collections.emptySet());
    }

    static List<Item> buildItems(List<Plan.Reminder> reminders, int observedSlot, Set<String> ongoingIds) {
        return buildItems(reminders, observedSlot, ongoingIds, Collections.emptyMap());
    }

    static List<Item> buildItems(List<Plan.Reminder> reminders, int observedSlot, Set<String> ongoingIds,
                               Map<String, Integer> calendarEndSlots) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(SUMMARY, -1, null, 0));
        for (int section = ACTIVE; section <= PAST; section++) {
            List<Plan.Reminder> group = new ArrayList<>();
            for (Plan.Reminder r : reminders) {
                if (sectionOf(r, observedSlot, ongoingIds, calendarEndSlots) == section) {
                    group.add(r);
                }
            }
            if (!group.isEmpty()) {
                items.add(new Item(SECTION, section, null, group.size()));
                for (Plan.Reminder r : group) {
                    items.add(new Item(REMINDER, section, r, 0));
                }
            }
        }
        return items;
    }

    static int sectionOf(Plan.Reminder reminder, int observedSlot) {
        return sectionOf(reminder, observedSlot, Collections.emptySet());
    }

    static int sectionOf(Plan.Reminder reminder, int observedSlot, Set<String> ongoingIds) {
        return sectionOf(reminder, observedSlot, ongoingIds, Collections.emptyMap());
    }

    static int sectionOf(Plan.Reminder reminder, int observedSlot, Set<String> ongoingIds,
                         Map<String, Integer> calendarEndSlots) {
        Integer end = calendarEndSlots.get(reminder.id);
        if (end != null) {
            return reminder.slot > observedSlot ? UPCOMING : end < observedSlot ? PAST : ACTIVE;
        }
        int day = Rules.dayOf(observedSlot);
        int from = Rules.dayOf(reminder.slot);
        if (from > day) {
            return UPCOMING;
        }
        if (ongoingIds.contains(reminder.id) && reminder.slot <= observedSlot) {
            return ACTIVE;
        }
        if (reminder.untilDay >= day || reminder.slot >= observedSlot) {
            return ACTIVE;
        }
        return PAST;
    }

    /** A period entry keeps its actual unlock date, even for a request carried over from last period. */
    static String dateLine(Plan.Reminder reminder, int observedSlot) {
        return dateLine(reminder, observedSlot, null);
    }

    static String dateLine(Plan.Reminder reminder, int observedSlot, Integer endSlot) {
        int from = Rules.dayOf(reminder.slot);
        int today = Rules.dayOf(observedSlot);
        String date = Zh.date(from);
        if ("calendar".equals(reminder.kind) && reminder.untilDay >= from) {
            return date + (reminder.untilDay > from ? " ～ " + Zh.date(reminder.untilDay) : "")
                + (reminder.slot <= observedSlot && observedSlot <= (endSlot == null
                    ? Rules.slot(reminder.untilDay, true) : endSlot) ? " · 当前在此期间" : "");
        }
        if (reminder.untilDay > from) {
            return date + "开放 · " + (reminder.untilDay == today ? "今天" : Zh.date(reminder.untilDay)) + "截止";
        } else if (reminder.untilDay == from) {
            return date + (reminder.untilDay == today ? " · 今天截止" : "截止");
        } else {
            date += Rules.isEvening(reminder.slot) ? " 晚上" : " 白天";
        }
        if (from == today) {
            return date + " · 今天";
        }
        return date;
    }

    private final class Adapter extends BaseAdapter {
        private List<Item> items = new ArrayList<>();

        void setItems(List<Item> value) {
            items = value;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int position) { return items.get(position).type; }
        @Override public boolean isEnabled(int position) { return false; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Item item = items.get(position);
            if (item.type == SUMMARY) {
                return summary();
            }
            if (item.type == SECTION) {
                LinearLayout header = Ui.column(c);
                Ui.pad(header, 0, 12, 0, 0);
                header.addView(Ui.sectionHeader(c, SECTION_TITLES[item.section], item.count + " 条"));
                return header;
            }
            return reminder(item.reminder, item.section);
        }
    }

    private View summary() {
        LinearLayout box = Ui.column(c);
        Ui.pad(box, 0, 8, 0, 4);
        box.addView(Ui.text(c, "提示", 28, Ui.col(c, R.color.p3r_white), true));
        String block = period.blockZh == null || period.blockZh.trim().isEmpty() ? "当期" : period.blockZh;
        if (period.floorsZh != null && !period.floorsZh.trim().isEmpty()) {
            block += " · " + period.floorsZh;
        }
        box.addView(Ui.mixed(c, block, 17, Ui.col(c, R.color.p3r_cyan_300), true), top(6));
        box.addView(Ui.mixed(c, Zh.date(period.fromDay) + " ～ " + Zh.date(period.toDay)
            + " · 共 " + period.items.size() + " 条", 13, Ui.col(c, R.color.p3r_ink_muted), false), top(4));
        box.addView(Ui.mixed(c, "游戏进度：" + Zh.date(Rules.dayOf(currentSlot))
            + (Rules.isEvening(currentSlot) ? " 晚上" : " 白天"), 13,
            Ui.col(c, R.color.p3r_ice_100), false), top(4));
        box.addView(Ui.mixed(c, LEGEND, 12, Ui.col(c, R.color.p3r_ink_faint), false), top(8));
        if (period.items.isEmpty()) {
            box.addView(Ui.body(c, "这期暂时没有收录的提示。"), top(20));
        }
        box.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View reminder(Plan.Reminder r, int section) {
        LinearLayout card = Ui.column(c);
        boolean past = section == PAST;
        card.setBackground(Ui.card(c).accent(Ui.col(c, past ? R.color.p3r_ink_faint : R.color.ui_gold), Ui.dp(c, 3)));
        Ui.pad(card, 14, 10, 12, 8);
        card.addView(Ui.mixed(c, dateLine(r), 12,
            Ui.col(c, past ? R.color.p3r_ink_muted : R.color.ui_gold), true));
        card.addView(ReminderViews.row(c, r, false), top(2));
        TextView more = Ui.text(c, "查看详情 ›", 12, Ui.col(c, R.color.p3r_cyan_300), false);
        more.setGravity(Gravity.END);
        card.addView(more, Ui.matchWrap());
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(dateLine(r) + "，" + r.labelZh + "，" + r.textZh + "，查看详情");
        card.setOnClickListener(v -> showDetail(r));
        return card;
    }

    private void showDetail(Plan.Reminder reminder) {
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 20);
        sheet.addView(Ui.title(c, "提示详情"));
        sheet.addView(Ui.mixed(c, dateLine(reminder), 14,
            Ui.col(c, R.color.ui_gold), true), top(8));
        sheet.addView(ReminderViews.row(c, reminder, true), top(8));
        TextView close = Ui.secondary(c, "知道了");
        close.setOnClickListener(v -> host.dismissSheet());
        sheet.addView(close, top(16));
        host.showSheet(sheet);
    }

    private String dateLine(Plan.Reminder reminder) {
        if (period.ongoingIds.contains(reminder.id)) {
            if ("period.nurse".equals(reminder.id)) return "满足条件时";
            return Zh.date(Rules.dayOf(reminder.slot)) + "起，满足条件时";
        }
        return dateLine(reminder, currentSlot, period.calendarEndSlots.get(reminder.id));
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
