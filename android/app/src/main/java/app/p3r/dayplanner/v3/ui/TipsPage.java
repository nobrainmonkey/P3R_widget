package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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
import app.p3r.dayplanner.v3.service.TipsCatalogue;

/** The current period and an all-game answer lookup, both independent of the remaining plan. */
final class TipsPage {
    private static final int SECTION = 0;
    private static final int REMINDER = 1;
    private static final int ACTIVE = 0;
    private static final int UPCOMING = 1;
    private static final int PAST = 2;
    private static final int LOOKUP = 3;
    private static final int COMBAT_GROUP = 4;
    private static final String[] SECTION_TITLES = {"当前要留意", "接下来别错过", "本期已过日期"};
    private static final String[] COMBAT_TITLES = {"塔尔塔罗斯 · 守关首领", "蒙纳多 · 门与通道", "剧情作战", "特殊挑战"};
    private static final TipsCatalogue.Content[] CONTENT_VALUES = {TipsCatalogue.Content.ALL, TipsCatalogue.Content.REQUEST,
        TipsCatalogue.Content.COMBAT, TipsCatalogue.Content.DIALOGUE, TipsCatalogue.Content.CLASS_EXAM};
    private static final String[] CONTENT_LABELS = {"全部内容", "委托", "战斗", "社群对话", "课堂·考试"};

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
    private final EditText search;
    private final TextView periodFilter;
    private final TextView allFilter;
    private final TextView filterButton;
    private final TextView selectionCaption;
    private final TextView resultCounter;
    private final Adapter adapter = new Adapter();
    private PeriodTips.Period period;
    private PeriodTips.Period catalogue;
    private UiData data;
    private boolean allScope;
    private String query = "";
    private TipsCatalogue.Content content = TipsCatalogue.Content.ALL;
    private String category;
    private int currentSlot = -1;

    TipsPage(HomeHost host) {
        this.host = host;
        c = host.activity();
        root = new FrameLayout(c);
        LinearLayout column = Ui.column(c);
        column.setFocusableInTouchMode(true);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout controls = Ui.column(c);
        Ui.pad(controls, Ui.GUTTER, 12, Ui.GUTTER, 4);
        LinearLayout titleRow = Ui.row(c);
        titleRow.addView(Ui.text(c, "提示", 28, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
        TextView compendium = Ui.chip(c, "图鉴与合成", false);
        compendium.setTag("open_compendium");
        compendium.setOnClickListener(v -> host.pushFillingPage("图鉴与合成", CompendiumPage.create(host)));
        titleRow.addView(compendium, Ui.wrap());
        controls.addView(titleRow);
        search = new EditText(c);
        search.setTag("tips_search");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            InputMethodManager keyboard = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            column.requestFocus();
            return true;
        });
        search.setTextColor(Ui.col(c, R.color.p3r_white));
        search.setHintTextColor(Ui.col(c, R.color.p3r_ink_faint));
        search.setTextSize(14);
        search.setHint(R.string.tips_search_hint);
        search.setContentDescription(c.getString(R.string.tips_search_description));
        search.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170))
            .stroke(Ui.col(c, R.color.p3r_line), Ui.dp(c, 1)));
        search.setMinHeight(Ui.dp(c, Ui.TOUCH));
        Ui.pad(search, 12, 8, 12, 8);
        controls.addView(search, top(8));
        LinearLayout scopes = Ui.row(c);
        periodFilter = Ui.chip(c, "本期", true);
        allFilter = Ui.chip(c, "全部", false);
        periodFilter.setOnClickListener(v -> { allScope = false; refresh(); });
        allFilter.setOnClickListener(v -> { allScope = true; refresh(); });
        scopes.addView(periodFilter, Ui.weight(1));
        LinearLayout.LayoutParams scopeGap = Ui.weight(1);
        scopeGap.leftMargin = Ui.dp(c, 8);
        scopes.addView(allFilter, scopeGap);
        filterButton = Ui.chip(c, "筛选", false);
        filterButton.setOnClickListener(v -> openFilters());
        LinearLayout.LayoutParams filterGap = Ui.weight(1);
        filterGap.leftMargin = Ui.dp(c, 8);
        scopes.addView(filterButton, filterGap);
        resultCounter = Ui.mixed(c, "0 条", 12, Ui.col(c, R.color.p3r_ink_muted), false);
        resultCounter.setTag("tips_result_counter");
        resultCounter.setMinWidth(Ui.dp(c, 42));
        resultCounter.setGravity(Gravity.END);
        LinearLayout.LayoutParams countGap = Ui.wrap();
        countGap.leftMargin = Ui.dp(c, 8);
        scopes.addView(resultCounter, countGap);
        controls.addView(scopes, top(8));
        selectionCaption = Ui.mixed(c, "", 12, Ui.col(c, R.color.p3r_ink_muted), false);
        selectionCaption.setTag("tips_filter_selection");
        controls.addView(selectionCaption, top(6));
        column.addView(controls, Ui.matchWrap());
        FrameLayout results = new FrameLayout(c);
        column.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        list = new ListView(c);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0));
        list.setDividerHeight(Ui.dp(c, 8));
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        Ui.pad(list, Ui.GUTTER, 8, Ui.GUTTER, 24);
        list.setAdapter(adapter);
        results.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        empty = Ui.body(c, "没有匹配结果");
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 32, 0, 32, 0);
        results.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean firstSearch = query.trim().isEmpty() && !s.toString().trim().isEmpty();
                query = s.toString();
                if (firstSearch) allScope = true;
                refresh();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    View view() {
        return root;
    }

    void bind(UiData data) {
        this.data = data;
        currentSlot = data.currentSlot();
        Plan currentPlan = data.snap != null && data.snap.planCurrent ? data.plan() : null;
        period = data.hasState() ? TipsCatalogue.current(data.rules, data.observed(), currentPlan) : null;
        catalogue = TipsCatalogue.all(data.rules, data.profile(), data.observed());
        refresh();
    }

    private void refresh() {
        search.setHint(content == TipsCatalogue.Content.REQUEST ? R.string.tips_request_search_hint
            : content == TipsCatalogue.Content.COMBAT ? R.string.tips_combat_search_hint : R.string.tips_search_hint);
        Ui.setChipSelected(periodFilter, !allScope);
        Ui.setChipSelected(allFilter, allScope);
        Ui.setChipSelected(filterButton, content != TipsCatalogue.Content.ALL || category != null);
        String caption = !allScope && period != null
            ? Zh.date(period.fromDay) + " ～ " + Zh.date(period.toDay) : "";
        String selection = category == null ? content == TipsCatalogue.Content.ALL ? "" : contentLabel(content) : category;
        if (!selection.isEmpty()) caption += (caption.isEmpty() ? "" : " · ") + selection;
        selectionCaption.setText(Ui.latin(c, caption));
        selectionCaption.setVisibility(caption.isEmpty() ? View.GONE : View.VISIBLE);
        PeriodTips.Period selected = allScope ? catalogue : period;
        if (selected == null || data == null) {
            resultCounter.setText(Ui.latin(c, "0 条"));
            empty.setText("先设置游戏日期");
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
            adapter.setItems(new ArrayList<>());
            return;
        }
        List<Plan.Reminder> matches = TipsCatalogue.filter(selected.items, data.rules, query, content, category);
        resultCounter.setText(Ui.latin(c, matches.size() + " 条"));
        empty.setText("没有匹配结果");
        empty.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(matches.isEmpty() ? View.GONE : View.VISIBLE);
        if (content == TipsCatalogue.Content.COMBAT) {
            List<Item> items = new ArrayList<>();
            for (TipsCatalogue.CombatGroup group : TipsCatalogue.CombatGroup.values()) {
                List<Plan.Reminder> combat = new ArrayList<>();
                for (Plan.Reminder item : matches) {
                    if (TipsCatalogue.combatGroup(TipsCatalogue.combatTip(data.rules, item)) == group) combat.add(item);
                }
                if (combat.isEmpty()) continue;
                int section = COMBAT_GROUP + group.ordinal();
                items.add(new Item(SECTION, section, null, combat.size()));
                for (Plan.Reminder item : combat) items.add(new Item(REMINDER, section, item, 0));
            }
            adapter.setItems(items);
        } else if (!allScope) {
            adapter.setItems(buildItems(matches, currentSlot, selected.ongoingIds, selected.calendarEndSlots));
        } else {
            List<Item> items = new ArrayList<>();
            for (Plan.Reminder reminder : matches) items.add(new Item(REMINDER, LOOKUP, reminder, 0));
            adapter.setItems(items);
        }
        list.setSelection(0);
    }

    private void openFilters() {
        if (data == null) return;
        TipsCatalogue.Content[] pendingContent = {content};
        String[] pendingCategory = {category};
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 20);
        sheet.addView(SheetChrome.title(c, "筛选提示"));
        sheet.addView(Ui.sectionHeader(c, "分类", null), top(14));
        List<TextView> contents = new ArrayList<>();
        List<TextView> tags = new ArrayList<>();
        Runnable paintSelection = () -> {
            for (TextView choice : contents) SheetChrome.selectChoice(choice,
                pendingCategory[0] == null && choice.getTag() == pendingContent[0]);
            for (TextView choice : tags) SheetChrome.selectChoice(choice,
                pendingCategory[0] != null && pendingCategory[0].equals(choice.getTag()));
        };
        for (int i = 0; i < CONTENT_VALUES.length; i++) {
            TipsCatalogue.Content value = CONTENT_VALUES[i];
            TextView choice = filterChoice(CONTENT_LABELS[i]);
            choice.setTag(value);
            choice.setOnClickListener(v -> {
                pendingContent[0] = value;
                pendingCategory[0] = null;
                paintSelection.run();
            });
            contents.add(choice);
        }
        addChoiceGrid(sheet, contents);
        List<Plan.Reminder> categoryItems = new ArrayList<>();
        if (catalogue != null) categoryItems.addAll(catalogue.items);
        if (period != null) categoryItems.addAll(period.items);
        for (String label : TipsCatalogue.categories(categoryItems, data.rules)) {
            if ("委托".equals(label) || "社群对话".equals(label)) continue;
            TextView choice = filterChoice(label);
            choice.setTag(label);
            choice.setOnClickListener(v -> {
                pendingCategory[0] = label;
                pendingContent[0] = TipsCatalogue.contentForCategory(categoryItems, data.rules, label);
                paintSelection.run();
            });
            tags.add(choice);
        }
        if (!tags.isEmpty()) {
            sheet.addView(Ui.sectionHeader(c, "标签", null), top(16));
            LinearLayout tagGrid = Ui.column(c);
            addChoiceGrid(tagGrid, tags);
            ScrollView tagScroll = new ScrollView(c);
            tagScroll.setFillViewport(false);
            tagScroll.setVerticalScrollBarEnabled(true);
            tagScroll.addView(tagGrid, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            sheet.addView(tagScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(c, Math.min(5, (tags.size() + 2) / 3) * 46)));
        }
        paintSelection.run();
        LinearLayout actions = Ui.row(c);
        TextView reset = Ui.secondary(c, "重置");
        reset.setOnClickListener(v -> {
            pendingContent[0] = TipsCatalogue.Content.ALL;
            pendingCategory[0] = null;
            paintSelection.run();
        });
        actions.addView(reset, Ui.weight(1));
        TextView apply = Ui.primary(c, "应用");
        apply.setOnClickListener(v -> {
            content = pendingContent[0];
            category = pendingCategory[0];
            host.dismissSheet();
            refresh();
        });
        LinearLayout.LayoutParams gap = Ui.weight(1);
        gap.leftMargin = Ui.dp(c, 8);
        actions.addView(apply, gap);
        sheet.addView(actions, top(18));
        host.showSheet(sheet);
    }

    private TextView filterChoice(String label) {
        TextView choice = Ui.chip(c, label, false);
        choice.setTextSize(12);
        Ui.pad(choice, 4, 6, 4, 6);
        return choice;
    }

    private void addChoiceGrid(LinearLayout parent, List<TextView> choices) {
        LinearLayout row = null;
        for (int i = 0; i < choices.size(); i++) {
            if (i % 3 == 0) {
                row = Ui.row(c);
                parent.addView(row, top(6));
            }
            LinearLayout.LayoutParams params = Ui.weight(1);
            if (i % 3 != 0) params.leftMargin = Ui.dp(c, 6);
            row.addView(choices.get(i), params);
        }
        if (row == null) return;
        for (int missing = choices.size() % 3; missing > 0 && missing < 3; missing++) {
            LinearLayout.LayoutParams params = Ui.weight(1);
            params.leftMargin = Ui.dp(c, 6);
            row.addView(new View(c), params);
        }
    }

    private static String contentLabel(TipsCatalogue.Content value) {
        for (int i = 0; i < CONTENT_VALUES.length; i++) if (CONTENT_VALUES[i] == value) return CONTENT_LABELS[i];
        return "全部内容";
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
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return items.get(position).type; }
        @Override public boolean isEnabled(int position) { return false; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Item item = items.get(position);
            if (item.type == SECTION) {
                LinearLayout header = Ui.column(c);
                Ui.pad(header, 0, 12, 0, 0);
                String title = item.section >= COMBAT_GROUP ? COMBAT_TITLES[item.section - COMBAT_GROUP]
                    : item.section == LOOKUP ? "查询结果" : SECTION_TITLES[item.section];
                header.addView(Ui.sectionHeader(c, title, null));
                return header;
            }
            return reminder(item.reminder, item.section);
        }
    }

    private View reminder(Plan.Reminder r, int section) {
        Rules.CombatTip combat = TipsCatalogue.combatTip(data.rules, r);
        if (combat != null) return CombatViews.card(c, combat, dateLine(r), () -> showDetail(r));
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
        Rules.CombatTip combat = TipsCatalogue.combatTip(data.rules, reminder);
        sheet.addView(SheetChrome.title(c, combat == null ? "提示详情" : "战斗详情"));
        sheet.addView(Ui.mixed(c, dateLine(reminder), 14,
            Ui.col(c, R.color.ui_gold), true), top(8));
        sheet.addView(combat == null ? ReminderViews.row(c, reminder, true) : CombatViews.detail(c, combat), top(8));
        TextView close = Ui.secondary(c, "知道了");
        close.setOnClickListener(v -> host.dismissSheet());
        sheet.addView(close, top(16));
        host.showSheet(sheet);
    }

    private String dateLine(Plan.Reminder reminder) {
        if (TipsCatalogue.DIALOGUE.equals(reminder.kind)) return "社群升级剧情 · 随时查阅";
        Rules.CombatTip combat = TipsCatalogue.combatTip(data.rules, reminder);
        if (combat != null) return "guardian".equals(combat.kind) || "monad".equals(combat.kind)
            ? "塔尔塔罗斯" : "optional".equals(combat.kind) ? "可选挑战" : Zh.date(combat.fromDay);
        if (TipsCatalogue.isRequestEntry(reminder) && reminder.untilDay < 0) {
            return Zh.date(Rules.dayOf(reminder.slot)) + "起可接";
        }
        PeriodTips.Period selected = allScope ? catalogue : period;
        if (selected.ongoingIds.contains(reminder.id)) {
            if ("period.nurse".equals(reminder.id)) return "满足条件时";
            return Zh.date(Rules.dayOf(reminder.slot)) + "起，满足条件时";
        }
        return dateLine(reminder, currentSlot, selected.calendarEndSlots.get(reminder.id));
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
