package app.p3r.dayplanner.v3.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private static final int GROUP = 2;
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
        final EntryGroup group;

        Item(int type, int section, Plan.Reminder reminder, int count) {
            this.type = type;
            this.section = section;
            this.reminder = reminder;
            this.count = count;
            this.group = null;
        }

        Item(int section, EntryGroup group) {
            this.type = GROUP;
            this.section = section;
            this.reminder = null;
            this.count = group.entries.size();
            this.group = group;
        }
    }

    static final class EntryGroup {
        final String key;
        final Rules.Link link;
        final Rules.Block block;
        final List<Plan.Reminder> entries = new ArrayList<>();

        EntryGroup(String key, Rules.Link link, Rules.Block block) {
            this.key = key;
            this.link = link;
            this.block = block;
        }

        boolean dialogue() { return link != null; }
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
    private final Set<String> expandedGroups = new HashSet<>();
    private final Set<String> collapsedSearchGroups = new HashSet<>();

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
        controls.addView(Ui.text(c, "提示", 28, Ui.col(c, R.color.p3r_white), true));
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
        filterButton = CompendiumControls.icon(c, "筛选提示", R.drawable.ui_ic_filter);
        filterButton.setTag("tips_filter_button");
        filterButton.setOnClickListener(v -> openFilters());
        LinearLayout.LayoutParams filterGap = new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48));
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
                collapsedSearchGroups.clear();
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
        CompendiumControls.setToolActive(filterButton, content != TipsCatalogue.Content.ALL || category != null);
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
            adapter.setItems(groupEntries(items));
        } else if (!allScope) {
            adapter.setItems(groupEntries(buildItems(matches, currentSlot, selected.ongoingIds, selected.calendarEndSlots)));
        } else {
            List<Item> items = new ArrayList<>();
            for (Plan.Reminder reminder : matches) items.add(new Item(REMINDER, LOOKUP, reminder, 0));
            adapter.setItems(groupEntries(items));
        }
        list.setSelection(0);
    }

    /** Keep section order, but give each social link or tower block just one expandable row. */
    private List<Item> groupEntries(List<Item> source) {
        List<Item> result = new ArrayList<>();
        Map<String, EntryGroup> groups = new LinkedHashMap<>();
        for (Item item : source) {
            if (item.reminder == null) {
                result.add(item);
                continue;
            }
            Rules.Link link = TipsCatalogue.dialogueLink(data.rules, item.reminder);
            Rules.CombatTip combat = TipsCatalogue.combatTip(data.rules, item.reminder);
            boolean monad = combat != null && "monad".equals(combat.kind);
            if (link == null && !monad) {
                result.add(item);
                continue;
            }
            Rules.Block block = monad ? TipsCatalogue.monadBlock(data.rules, item.reminder) : null;
            String key = link != null ? "dialogue_" + link.id : "monad_" + (block == null ? "other" : block.id);
            String scopedKey = item.section + ":" + key;
            EntryGroup group = groups.get(scopedKey);
            if (group == null) {
                group = new EntryGroup(key, link, block);
                groups.put(scopedKey, group);
                result.add(new Item(item.section, group));
            }
            group.entries.add(item.reminder);
        }
        for (EntryGroup group : groups.values()) group.entries.sort((a, b) -> {
            if (group.dialogue()) return Integer.compare(TipsCatalogue.dialogueRank(a), TipsCatalogue.dialogueRank(b));
            Rules.CombatTip ca = TipsCatalogue.combatTip(data.rules, a);
            Rules.CombatTip cb = TipsCatalogue.combatTip(data.rules, b);
            int floor = Integer.compare(ca.floor, cb.floor);
            return floor != 0 ? floor : Integer.compare(ca.battleOrder, cb.battleOrder);
        });
        return result;
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
        @Override public int getViewTypeCount() { return 3; }
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
            if (item.type == GROUP) return entryGroup(item.group);
            return reminder(item.reminder, item.section);
        }
    }

    private boolean isExpanded(EntryGroup group) {
        return query.trim().isEmpty() ? expandedGroups.contains(group.key) : !collapsedSearchGroups.contains(group.key);
    }

    private View entryGroup(EntryGroup group) {
        LinearLayout card = Ui.column(c);
        card.setTag("tips_group_" + group.key);
        card.setBackground(Ui.card(c).accent(Ui.col(c, R.color.p3r_cyan_300), Ui.dp(c, 3)));
        Ui.pad(card, 14, 0, 12, 0);
        LinearLayout header = Ui.row(c);
        header.setTag("tips_group_header_" + group.key);
        header.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint)));
        header.setMinimumHeight(Ui.dp(c, 76));
        Ui.pad(header, 0, 10, 0, 10);
        LinearLayout names = Ui.column(c);
        String title = group.dialogue() ? group.link.labelZh : group.block == null ? "蒙纳多" : group.block.zh;
        if (title == null || title.trim().isEmpty()) title = group.dialogue() ? group.link.arcanaZh : "蒙纳多";
        names.addView(Ui.mixed(c, title, 17, Ui.col(c, R.color.p3r_white), true));
        String subtitle;
        if (group.dialogue()) subtitle = group.entries.size() + " 段剧情";
        else {
            int doors = 0;
            int passages = 0;
            for (Plan.Reminder reminder : group.entries) {
                Rules.CombatTip combat = TipsCatalogue.combatTip(data.rules, reminder);
                if ("passage".equals(combat.encounterType)) passages++;
                else doors++;
            }
            List<String> parts = new ArrayList<>();
            if (group.block != null && group.block.floorsZh != null) parts.add(group.block.floorsZh);
            if (doors > 0) parts.add("蒙纳多之门 " + doors);
            if (passages > 0) parts.add("蒙纳多通道 " + passages);
            subtitle = String.join(" · ", parts);
        }
        names.addView(Ui.mixed(c, subtitle, 12, Ui.col(c, R.color.p3r_ink_muted), false), top(3));
        header.addView(names, Ui.weight(1));
        TextView arrow = Ui.text(c, isExpanded(group) ? "−" : "+", 25, Ui.col(c, R.color.p3r_cyan_300), false);
        arrow.setGravity(Gravity.CENTER);
        header.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 48)));
        card.addView(header, Ui.matchWrap());

        LinearLayout body = Ui.column(c);
        body.setTag("tips_group_body_" + group.key);
        Ui.pad(body, 0, 0, 0, 12);
        if (group.dialogue()) addRankGrid(body, group);
        else for (Plan.Reminder reminder : group.entries) body.addView(monadEntry(reminder), top(5));
        body.setVisibility(isExpanded(group) ? View.VISIBLE : View.GONE);
        card.addView(body, Ui.matchWrap());
        header.setClickable(true);
        header.setFocusable(true);
        String groupTitle = title;
        header.setContentDescription(title + "，" + subtitle + (isExpanded(group) ? "，收起" : "，展开"));
        header.setOnClickListener(v -> {
            boolean expanded = !isExpanded(group);
            if (query.trim().isEmpty()) {
                if (expanded) expandedGroups.add(group.key);
                else expandedGroups.remove(group.key);
            } else {
                if (expanded) collapsedSearchGroups.remove(group.key);
                else collapsedSearchGroups.add(group.key);
            }
            arrow.setText(expanded ? "−" : "+");
            header.setContentDescription(groupTitle + "，" + subtitle + (expanded ? "，收起" : "，展开"));
            animateExpansion(body, expanded);
        });
        return card;
    }

    private void addRankGrid(LinearLayout body, EntryGroup group) {
        LinearLayout row = null;
        int position = 0;
        for (Plan.Reminder reminder : group.entries) {
            if (position % 3 == 0) {
                row = Ui.row(c);
                body.addView(row, top(6));
            }
            int target = TipsCatalogue.dialogueRank(reminder);
            LinearLayout rank = Ui.column(c);
            rank.setTag("tips_entry_" + reminder.id);
            rank.setMinimumHeight(Ui.dp(c, 56));
            rank.setGravity(Gravity.CENTER);
            rank.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_navy_950))
                .pressedFill(Ui.col(c, R.color.p3r_navy_700))
                .stroke(Ui.col(c, R.color.p3r_line), Ui.dp(c, 1)));
            Ui.pad(rank, 4, 8, 4, 8);
            TextView label = Ui.mixed(c, target <= 1 ? "解锁" : "RANK " + (target - 1), 14,
                Ui.col(c, R.color.p3r_ice_100), true);
            label.setGravity(Gravity.CENTER);
            rank.addView(label, Ui.matchWrap());
            if (target > 1) {
                TextView next = Ui.mixed(c, "→ " + target, 11, Ui.col(c, R.color.p3r_ink_muted), false);
                next.setGravity(Gravity.CENTER);
                rank.addView(next, Ui.matchWrap());
            }
            rank.setClickable(true);
            rank.setFocusable(true);
            rank.setContentDescription(group.link.labelZh + (target <= 1 ? "，解锁与前置条件" : "，RANK " + (target - 1)
                + " 升到 RANK " + target) + "，查看对话");
            rank.setOnClickListener(v -> showDetail(reminder));
            LinearLayout.LayoutParams params = Ui.weight(1);
            if (position % 3 != 0) params.leftMargin = Ui.dp(c, 6);
            row.addView(rank, params);
            position++;
        }
        for (int missing = position % 3; missing > 0 && missing < 3; missing++) {
            LinearLayout.LayoutParams params = Ui.weight(1);
            params.leftMargin = Ui.dp(c, 6);
            row.addView(new View(c), params);
        }
    }

    private View monadEntry(Plan.Reminder reminder) {
        Rules.CombatTip combat = TipsCatalogue.combatTip(data.rules, reminder);
        LinearLayout entry = Ui.column(c);
        entry.setTag("tips_entry_" + reminder.id);
        entry.setMinimumHeight(Ui.dp(c, 64));
        entry.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_navy_950))
            .pressedFill(Ui.col(c, R.color.p3r_navy_700))
            .stroke(Ui.col(c, R.color.p3r_line), Ui.dp(c, 1)));
        Ui.pad(entry, 12, 10, 12, 10);
        LinearLayout line = Ui.row(c);
        String title = monadTitle(combat.titleZh);
        if (combat.floor > 0 && !title.contains(combat.floor + "F") && !title.contains(combat.floor + "层")) {
            title = combat.floor + "F · " + title;
        }
        line.addView(Ui.mixed(c, title, 14, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
        line.addView(Ui.text(c, "›", 20, Ui.col(c, R.color.p3r_cyan_300), false), Ui.wrap());
        entry.addView(line, Ui.matchWrap());
        entry.addView(Ui.mixed(c, TipsCatalogue.categoryLabel(combat)
            + (combat.battleOrder > 0 ? " · 第 " + combat.battleOrder + " 战" : ""), 11,
            Ui.col(c, R.color.p3r_ink_muted), false), top(3));
        String summary = combat.enemies.isEmpty() ? combat.shortZh : CombatViews.weaknessSummary(combat);
        if (summary != null && !summary.trim().isEmpty()) entry.addView(Ui.mixed(c, summary, 12,
            Ui.col(c, R.color.ui_gold), false), top(4));
        entry.setClickable(true);
        entry.setFocusable(true);
        entry.setContentDescription(title + "，" + TipsCatalogue.categoryLabel(combat) + "，查看属性与打法");
        entry.setOnClickListener(v -> showDetail(reminder));
        return entry;
    }

    private static String monadTitle(String title) {
        for (String marker : new String[] {"蒙纳多通道", "蒙纳多之门", "蒙纳多门"}) {
            int markerAt = title.indexOf(marker);
            if (markerAt < 0) continue;
            String name = title.substring(markerAt + marker.length()).trim();
            int colon = name.indexOf('：');
            if (colon < 0) colon = name.indexOf(':');
            if (colon >= 0) name = name.substring(colon + 1).trim();
            else if (name.startsWith("·")) name = name.substring(1).trim();
            return name.isEmpty() ? title : name;
        }
        return title;
    }

    /** Expand in place so the selected link or floor range stays under the user's finger. */
    private void animateExpansion(View body, boolean expanded) {
        if (body.getTag(R.id.tips_expansion_animator) instanceof ValueAnimator) {
            ((ValueAnimator) body.getTag(R.id.tips_expansion_animator)).cancel();
        }
        View parent = (View) body.getParent();
        int width = body.getWidth() > 0 ? body.getWidth() : parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        if (!Ui.animationsEnabled() || width <= 0) {
            body.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            return;
        }
        int start = body.getVisibility() == View.GONE ? 0 : body.getHeight();
        body.setVisibility(View.VISIBLE);
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int end = expanded ? body.getMeasuredHeight() : 0;
        ValueAnimator animator = ValueAnimator.ofInt(start, end);
        body.setTag(R.id.tips_expansion_animator, animator);
        animator.setDuration(180);
        animator.addUpdateListener(value -> {
            body.getLayoutParams().height = (int) value.getAnimatedValue();
            body.requestLayout();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                body.setVisibility(expanded ? View.VISIBLE : View.GONE);
                body.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                body.setTag(R.id.tips_expansion_animator, null);
                body.requestLayout();
            }
        });
        animator.start();
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
        boolean dialogue = TipsCatalogue.DIALOGUE.equals(reminder.kind);
        sheet.addView(SheetChrome.title(c, dialogue ? "社群对话" : combat == null ? "提示详情" : "战斗详情"));
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
