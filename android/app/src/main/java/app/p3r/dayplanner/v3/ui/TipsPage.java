package app.p3r.dayplanner.v3.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PeriodTips;
import app.p3r.dayplanner.v3.service.TipsCatalogue;

/**
 * 提示 (docs/DESIGN_3.6_UI.md): what matters in the current Tartarus period and an all-game lookup,
 * both read-only and independent of the remaining plan. The header is the tab's page header with a
 * [本期｜全部] toggle; under the search field a one-tap content strip (全部 · 社群 · 课堂·考试 · 委托 ·
 * 战斗 · 更多). 本期 opens with the period's banner and borderless rows by time (现在 / 接下来 / a
 * folded 已经过去); 全部 without a search is a directory of big menu tiles.
 */
final class TipsPage {
    private static final int SECTION = 0;
    private static final int REMINDER = 1;
    private static final int GROUP = 2;
    private static final int HERO = 3;
    private static final int DIRECTORY = 4;
    private static final int ACTIVE = 0;
    private static final int UPCOMING = 1;
    private static final int PAST = 2;
    private static final int LOOKUP = 3;
    private static final int COMBAT_GROUP = 4;
    static final String[] SECTION_TITLES = {"现在", "接下来", "已经过去"};
    private static final String[] COMBAT_TITLES = {"守关首领", "蒙纳多之门与通道", "剧情作战", "特殊挑战"};

    /** The content strip: one tap shows one kind. */
    private static final TipsCatalogue.Content[] STRIP = {TipsCatalogue.Content.ALL, TipsCatalogue.Content.DIALOGUE,
        TipsCatalogue.Content.CLASS_EXAM, TipsCatalogue.Content.REQUEST, TipsCatalogue.Content.COMBAT};
    static final String[] STRIP_LABELS = {"全部", "社群", "课堂·考试", "委托", "战斗"};
    private static final String[] STRIP_KEYS = {"all", "dialogue", "class", "request", "combat"};
    /** Last chip of the strip: the rarer tags in a single-tap list. */
    static final String MORE = "更多";
    /** Tags the strip already covers; 更多 lists the others. */
    private static final Set<String> STRIP_COVERED = new HashSet<>(Arrays.asList("社群对话", "委托", "课堂提问", "考试",
        "守关首领", "满月作战", "蒙纳多", "蒙纳多之门", "蒙纳多通道", "剧情作战", "最终战", "特殊挑战"));

    static final String EMPTY_SEARCH = "没找到。换个说法试试？比如「风花」「36F」「委托 75」";
    static final String EMPTY_KIND = "这一期没有这一类。";
    static final String NO_DATE = "现在玩到游戏里的哪一天了？";
    static final String SET_DATE = "设置日期";
    static final String SHOW_ALL = "看全部";
    static final String CLOSE = "知道了";

    private static final int G_DIALOGUE = 0;
    private static final int G_MONAD = 1;
    private static final int G_REQUESTS = 2;
    private static final int G_REPEAT = 3;

    static final class Item {
        final int type;
        final int section;
        final Plan.Reminder reminder;
        final int count;
        final EntryGroup group;
        final String title;

        Item(int type, int section, Plan.Reminder reminder, int count) {
            this(type, section, reminder, count, null, null);
        }

        Item(int section, EntryGroup group) {
            this(GROUP, section, null, group.entries.size(), group, null);
        }

        private Item(int type, int section, Plan.Reminder reminder, int count, EntryGroup group, String title) {
            this.type = type;
            this.section = section;
            this.reminder = reminder;
            this.count = count;
            this.group = group;
            this.title = title;
        }

        static Item header(int section, String title) {
            return new Item(SECTION, section, null, 0, null, title);
        }
    }

    static final class EntryGroup {
        final String key;
        final int kind;
        final Rules.Link link;
        final Rules.Block block;
        final List<Plan.Reminder> entries = new ArrayList<>();

        EntryGroup(String key, Rules.Link link, Rules.Block block) {
            this(key, link != null ? G_DIALOGUE : G_MONAD, link, block);
        }

        EntryGroup(String key, int kind, Rules.Link link, Rules.Block block) {
            this.key = key;
            this.kind = kind;
            this.link = link;
            this.block = block;
        }

        boolean dialogue() { return link != null; }
    }

    private final HomeHost host;
    private final Context c;
    private final FrameLayout root;
    private final ListView list;
    private final LinearLayout emptyBox;
    private final TextView emptyText;
    private final TextView emptyAction;
    private final EditText search;
    private final ImageView clear;
    private final TextView periodScope;
    private final TextView allScopeChip;
    private final List<TextView> stripChips = new ArrayList<>();
    private final TextView moreChip;
    private final HorizontalScrollView stripScroll;
    private final TextView pill;
    private final TextView resultCounter;
    private final Adapter adapter = new Adapter();
    private PeriodTips.Period period;
    private PeriodTips.Period catalogue;
    private UiData data;
    private boolean allScope;
    /** The player picked a scope; until then 提示 opens in 本期 when there is a game date. */
    private boolean scopeChosen;
    /** The scope before the first search character; restored when the search is cleared. */
    private Boolean scopeBeforeSearch;
    private String query = "";
    private TipsCatalogue.Content content = TipsCatalogue.Content.ALL;
    private String category;
    private int currentSlot = -1;
    private boolean pastOpen;
    private boolean shownOnce;
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
        Ui.pad(controls, Ui.GUTTER, 12, Ui.GUTTER, 2);

        LinearLayout scopes = Ui.row(c);
        periodScope = scopeChip("本期", "tips_scope_period");
        periodScope.setOnClickListener(v -> chooseScope(false));
        scopes.addView(periodScope, Ui.wrap());
        allScopeChip = scopeChip("全部", "tips_scope_all");
        allScopeChip.setOnClickListener(v -> chooseScope(true));
        LinearLayout.LayoutParams allParams = Ui.wrap();
        allParams.leftMargin = Ui.dp(c, 4);
        scopes.addView(allScopeChip, allParams);
        LinearLayout.LayoutParams scopesParams = Ui.wrap();
        scopesParams.bottomMargin = Ui.dp(c, 4);
        scopes.setLayoutParams(scopesParams);
        controls.addView(Ui.pageHeader(c, "提示", "GUIDE", scopes), Ui.matchWrap());

        LinearLayout searchBox = Ui.row(c);
        searchBox.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170))
            .stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 110), Ui.dp(c, 1))
            .skew(Ui.dp(c, 8)));
        ImageView glass = Ui.icon(c, R.drawable.tips_ic_search, Ui.col(c, R.color.p3r_cyan_300), 18);
        LinearLayout.LayoutParams glassParams = new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18));
        glassParams.leftMargin = Ui.dp(c, 16);
        searchBox.addView(glass, glassParams);
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
        search.setTextSize(15);
        search.setHint(R.string.tips_search_hint);
        search.setContentDescription(c.getString(R.string.tips_search_description));
        search.setBackground(null);
        search.setMinHeight(Ui.dp(c, Ui.TOUCH));
        Ui.pad(search, 10, 8, 4, 8);
        searchBox.addView(search, Ui.weight(1));
        clear = new ImageView(c);
        clear.setTag("tips_search_clear");
        clear.setImageDrawable(Ui.tinted(c, R.drawable.ui_ic_close, Ui.col(c, R.color.p3r_cyan_300)));
        clear.setScaleType(ImageView.ScaleType.CENTER);
        clear.setContentDescription("清空搜索");
        clear.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_soft)));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> search.setText(""));
        searchBox.addView(clear, new LinearLayout.LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH)));
        controls.addView(searchBox, top(10));

        stripScroll = new HorizontalScrollView(c);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setClipToPadding(false);
        LinearLayout strip = Ui.row(c);
        for (int i = 0; i < STRIP.length; i++) {
            TipsCatalogue.Content value = STRIP[i];
            TextView chip = stripChip(STRIP_LABELS[i], "tips_content_" + STRIP_KEYS[i]);
            chip.setOnClickListener(v -> chooseContent(value));
            strip.addView(chip, stripParams(i));
            stripChips.add(chip);
        }
        moreChip = stripChip(MORE, "tips_content_more");
        moreChip.setOnClickListener(v -> openMore());
        strip.addView(moreChip, stripParams(STRIP.length));
        stripScroll.addView(strip, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        controls.addView(stripScroll, top(10));

        LinearLayout status = Ui.row(c);
        pill = Ui.chip(c, "", true);
        pill.setTag("tips_filter_selection");
        pill.setMinHeight(Ui.dp(c, 36));
        pill.setMinimumHeight(Ui.dp(c, 36));
        android.graphics.drawable.Drawable x = Ui.tinted(c, R.drawable.ui_ic_close, Ui.col(c, R.color.p3r_navy_950));
        x.setBounds(0, 0, Ui.dp(c, 14), Ui.dp(c, 14));
        pill.setCompoundDrawables(null, null, x, null);
        pill.setCompoundDrawablePadding(Ui.dp(c, 6));
        pill.setOnClickListener(v -> {
            category = null;
            refresh(true);
        });
        pill.setVisibility(View.GONE);
        status.addView(pill, Ui.wrap());
        resultCounter = Ui.mixed(c, "", 13, Ui.col(c, R.color.p3r_ink_muted), false);
        resultCounter.setTag("tips_result_counter");
        resultCounter.setVisibility(View.GONE);
        LinearLayout.LayoutParams counterParams = Ui.weight(1);
        counterParams.leftMargin = Ui.dp(c, 2);
        status.addView(resultCounter, counterParams);
        controls.addView(status, top(6));
        column.addView(controls, Ui.matchWrap());

        FrameLayout results = new FrameLayout(c);
        column.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        list = new ListView(c);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0));
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        Ui.pad(list, Ui.GUTTER, 4, Ui.GUTTER, 24);
        list.setAdapter(adapter);
        results.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        emptyBox = Ui.column(c);
        emptyBox.setGravity(Gravity.CENTER);
        Ui.pad(emptyBox, 32, 0, 32, 48);
        emptyText = Ui.text(c, "", 17, Ui.col(c, R.color.p3r_white), true);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setLineSpacing(0f, 1.3f);
        emptyBox.addView(emptyText, Ui.matchWrap());
        emptyAction = Ui.primary(c, "");
        LinearLayout.LayoutParams actionParams = Ui.wrap();
        actionParams.topMargin = Ui.dp(c, 18);
        emptyBox.addView(emptyAction, actionParams);
        emptyBox.setVisibility(View.GONE);
        results.addView(emptyBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean wasEmpty = query.trim().isEmpty();
                boolean nowEmpty = s.toString().trim().isEmpty();
                query = s.toString();
                collapsedSearchGroups.clear();
                if (wasEmpty && !nowEmpty) {
                    // The whole game is searched; clearing the search brings back the scope the player had.
                    scopeBeforeSearch = allScope;
                    allScope = true;
                } else if (!wasEmpty && nowEmpty && scopeBeforeSearch != null) {
                    allScope = scopeBeforeSearch;
                    scopeBeforeSearch = null;
                }
                refresh(true);
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
        if (!scopeChosen && scopeBeforeSearch == null) {
            // Without a game date the lookup still works: open in 全部.
            allScope = period == null;
        }
        refresh(false);
    }

    // ------------------------------------------------------------------ controls

    private TextView scopeChip(String label, String tag) {
        TextView chip = Ui.chip(c, label, false);
        chip.setTag(tag);
        chip.setMinWidth(Ui.dp(c, 58));
        Ui.pad(chip, 12, 4, 12, 4);
        return chip;
    }

    private TextView stripChip(String label, String tag) {
        TextView chip = Ui.chip(c, label, false);
        chip.setTag(tag);
        Ui.pad(chip, 12, 4, 12, 4);
        return chip;
    }

    private LinearLayout.LayoutParams stripParams(int index) {
        LinearLayout.LayoutParams p = Ui.wrap();
        if (index > 0) p.leftMargin = Ui.dp(c, 4);
        return p;
    }

    private void chooseScope(boolean all) {
        scopeChosen = true;
        scopeBeforeSearch = null;
        if (allScope == all) return;
        allScope = all;
        refresh(true);
    }

    private void chooseContent(TipsCatalogue.Content value) {
        if (content == value && category == null) return;
        content = value;
        category = null;
        refresh(true);
    }

    private void updateControls() {
        int hint = content == TipsCatalogue.Content.REQUEST ? R.string.tips_request_search_hint
            : content == TipsCatalogue.Content.COMBAT ? R.string.tips_combat_search_hint
            : content == TipsCatalogue.Content.DIALOGUE ? R.string.tips_social_search_hint
            : content == TipsCatalogue.Content.CLASS_EXAM ? R.string.tips_class_search_hint : R.string.tips_search_hint;
        search.setHint(hint);
        clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        Ui.setChipSelected(periodScope, !allScope);
        Ui.setChipSelected(allScopeChip, allScope);
        // Without a game date 本期 waits for one: dimmed, still tappable to set it.
        periodScope.setAlpha(period == null ? 0.45f : 1f);
        for (int i = 0; i < stripChips.size(); i++) {
            Ui.setChipSelected(stripChips.get(i), category == null && STRIP[i] == content);
        }
        Ui.setChipSelected(moreChip, category != null);
        pill.setVisibility(category == null ? View.GONE : View.VISIBLE);
        if (category != null) {
            pill.setText(Ui.latin(c, category));
            pill.setContentDescription(category + "，取消");
        }
    }

    private void openMore() {
        if (data == null) return;
        List<Plan.Reminder> items = new ArrayList<>();
        if (catalogue != null) items.addAll(catalogue.items);
        if (period != null) items.addAll(period.items);
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 16);
        sheet.addView(CombatViews.sheetTitle(c, MORE));
        PeriodTips.Period shown = allScope || period == null ? catalogue : period;
        Map<String, Integer> counts = new HashMap<>();
        for (Plan.Reminder r : shown.items) counts.merge(TipsCatalogue.categoryLabel(data.rules, r), 1, Integer::sum);
        for (String label : TipsCatalogue.categories(items, data.rules)) {
            if (STRIP_COVERED.contains(label)) continue;
            LinearLayout row = Ui.row(c);
            row.setTag("tips_more_" + label);
            row.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
            row.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_soft))
                .skew(Ui.dp(c, 6)));
            Ui.pad(row, 6, 0, 6, 0);
            boolean selected = label.equals(category);
            TextView name = Ui.mixed(c, label, 16, Ui.col(c, selected ? R.color.p3r_cyan_400 : R.color.p3r_white), selected);
            row.addView(name, Ui.weight(1));
            int count = counts.getOrDefault(label, 0);
            TextView number = Ui.numeral(c, String.valueOf(count), 20,
                Ui.col(c, count == 0 ? R.color.p3r_ink_faint : R.color.p3r_cyan_300));
            LinearLayout.LayoutParams numberParams = Ui.wrap();
            numberParams.leftMargin = Ui.dp(c, 8);
            row.addView(number, numberParams);
            if (selected) {
                LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18));
                checkParams.leftMargin = Ui.dp(c, 10);
                row.addView(Ui.icon(c, R.drawable.ui_ic_check, Ui.col(c, R.color.p3r_cyan_400), 18), checkParams);
            }
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(label + "，" + count + " 条" + (selected ? "，已选" : ""));
            row.setOnClickListener(v -> {
                category = label;
                content = TipsCatalogue.contentForCategory(items, data.rules, label);
                host.dismissSheet();
                refresh(true);
            });
            sheet.addView(row, Ui.matchWrap());
            View rule = Ui.fadingRule(c);
            sheet.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 1))));
        }
        host.showSheet(sheet);
    }

    // ------------------------------------------------------------------ content

    private void refresh(boolean reset) {
        updateControls();
        if (data == null) return;
        boolean searching = !query.trim().isEmpty();
        resultCounter.setVisibility(View.GONE);
        if (!allScope && period == null) {
            showEmpty(NO_DATE, SET_DATE, () -> host.openState(data.hasState() ? "edit" : "onboarding"));
            return;
        }
        boolean directory = !searching && allScope && content == TipsCatalogue.Content.ALL && category == null;
        // Rank-up answers do not belong to a period: 社群 always shows every link.
        PeriodTips.Period selected = allScope || content == TipsCatalogue.Content.DIALOGUE ? catalogue : period;
        List<Plan.Reminder> matches = directory ? Collections.emptyList()
            : TipsCatalogue.filter(selected.items, data.rules, query, content, category);
        List<Item> items;
        if (directory) {
            items = Collections.singletonList(new Item(DIRECTORY, LOOKUP, null, 0));
        } else if (matches.isEmpty()) {
            boolean filtered = content != TipsCatalogue.Content.ALL || category != null;
            if (searching) {
                showEmpty(EMPTY_SEARCH, filtered ? SHOW_ALL : null, this::clearFilters);
            } else {
                showEmpty(EMPTY_KIND, SHOW_ALL, () -> {
                    if (!allScope) chooseScope(true);
                    else clearFilters();
                });
            }
            return;
        } else if (searching) {
            items = searchItems(matches);
        } else if (content == TipsCatalogue.Content.COMBAT) {
            items = combatItems(matches);
        } else if (!allScope && content != TipsCatalogue.Content.DIALOGUE) {
            items = periodItems(matches, selected);
        } else {
            items = lookupItems(matches);
        }
        if (searching) {
            resultCounter.setText(Ui.latin(c, "找到 " + visibleCount(items) + " 条"));
            resultCounter.setVisibility(View.VISIBLE);
        }
        emptyBox.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        adapter.setItems(items);
        if (reset) {
            list.setSelection(0);
            if (shownOnce) Motion.enter(list, 0);
        }
        if (!shownOnce) {
            shownOnce = true;
            list.post(() -> Motion.cascade(list));
        }
    }

    private void clearFilters() {
        content = TipsCatalogue.Content.ALL;
        category = null;
        refresh(true);
    }

    private void showEmpty(String text, String action, Runnable onAction) {
        adapter.setItems(new ArrayList<>());
        list.setVisibility(View.GONE);
        emptyText.setText(Ui.latin(c, text));
        emptyAction.setText(action == null ? "" : action);
        emptyAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
        emptyAction.setOnClickListener(v -> onAction.run());
        emptyBox.setVisibility(View.VISIBLE);
    }

    /** Rows and groups a player sees (section headers and the banner do not count). */
    private static int visibleCount(List<Item> items) {
        int count = 0;
        for (Item item : items) if (item.type == REMINDER || item.type == GROUP) count++;
        return count;
    }

    /** 本期: the period banner, then 现在 / 接下来 / a folded 已经过去, repeated entries as one row each. */
    private List<Item> periodItems(List<Plan.Reminder> matches, PeriodTips.Period selected) {
        List<Item> items = new ArrayList<>();
        if (content == TipsCatalogue.Content.ALL && category == null) items.add(new Item(HERO, LOOKUP, null, 0));
        List<Item> grouped = groupEntries(buildItems(matches, currentSlot, selected.ongoingIds, selected.calendarEndSlots),
            content == TipsCatalogue.Content.ALL && category == null);
        items.addAll(foldPast(grouped));
        return items;
    }

    /** The whole game for one kind: month headers over dated entries, social links and Monad blocks as groups. */
    private List<Item> lookupItems(List<Plan.Reminder> matches) {
        List<Item> items = new ArrayList<>();
        int month = -1;
        for (Plan.Reminder reminder : matches) {
            if (reminder.slot >= 0) {
                Rules.Day day = data.rules.day(Rules.dayOf(reminder.slot));
                if (day != null && day.month() != month) {
                    month = day.month();
                    items.add(Item.header(LOOKUP, month + "月"));
                }
            }
            items.add(new Item(REMINDER, LOOKUP, reminder, 0));
        }
        return groupEntries(items, false);
    }

    /** Search results by kind, each under 「社群 · 3」-style headers. */
    private List<Item> searchItems(List<Plan.Reminder> matches) {
        String[] titles = {"社群", "课堂·考试", "委托", "战斗", "其他"};
        List<List<Plan.Reminder>> buckets = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) buckets.add(new ArrayList<>());
        for (Plan.Reminder r : matches) {
            int bucket = TipsCatalogue.DIALOGUE.equals(r.kind) ? 0 : r.isAnswer() ? 1
                : Plan.Reminder.REQUEST.equals(r.kind) ? 2 : TipsCatalogue.COMBAT.equals(r.kind) ? 3 : 4;
            buckets.get(bucket).add(r);
        }
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            if (buckets.get(i).isEmpty()) continue;
            List<Item> section = new ArrayList<>();
            for (Plan.Reminder r : buckets.get(i)) section.add(new Item(REMINDER, LOOKUP + i, r, 0));
            List<Item> grouped = groupEntries(section, false);
            items.add(Item.header(LOOKUP + i, titles[i] + " · " + visibleCount(grouped)));
            items.addAll(grouped);
        }
        return items;
    }

    private List<Item> combatItems(List<Plan.Reminder> matches) {
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
        return groupEntries(items, false);
    }

    /**
     * Keep section order, but give each social link or tower block just one expandable row; with
     * {@code collapse} (本期 at a glance) all of Elizabeth's requests become one row and an entry
     * repeated on several days (上课犯困, 教职员室) one row with its dates.
     */
    private List<Item> groupEntries(List<Item> source, boolean collapse) {
        Map<String, Integer> repeats = new HashMap<>();
        if (collapse) {
            for (Item item : source) {
                String key = repeatKey(item.reminder);
                if (key != null) repeats.merge(key, 1, Integer::sum);
            }
        }
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
            String repeat = collapse ? repeatKey(item.reminder) : null;
            String key;
            String scopedKey;
            EntryGroup group;
            if (link != null || monad) {
                Rules.Block block = monad ? TipsCatalogue.monadBlock(data.rules, item.reminder) : null;
                key = link != null ? "dialogue_" + link.id : "monad_" + (block == null ? "other" : block.id);
                scopedKey = item.section + ":" + key;
                group = groups.get(scopedKey);
                if (group == null) {
                    group = new EntryGroup(key, link, block);
                    groups.put(scopedKey, group);
                    result.add(new Item(item.section, group));
                }
            } else if (collapse && TipsCatalogue.isRequestEntry(item.reminder)) {
                key = "requests";
                scopedKey = key;
                group = groups.get(scopedKey);
                if (group == null) {
                    group = new EntryGroup(key, G_REQUESTS, null, null);
                    groups.put(scopedKey, group);
                    result.add(new Item(item.section, group));
                }
            } else if (repeat != null && repeats.getOrDefault(repeat, 0) >= 3) {
                key = "repeat_" + Integer.toHexString(repeat.hashCode());
                scopedKey = key;
                group = groups.get(scopedKey);
                if (group == null) {
                    group = new EntryGroup(key, G_REPEAT, null, null);
                    groups.put(scopedKey, group);
                    result.add(new Item(item.section, group));
                }
            } else {
                result.add(item);
                continue;
            }
            group.entries.add(item.reminder);
        }
        for (EntryGroup group : groups.values()) {
            if (group.kind == G_DIALOGUE) {
                group.entries.sort((a, b) -> Integer.compare(TipsCatalogue.dialogueRank(a), TipsCatalogue.dialogueRank(b)));
            } else if (group.kind == G_MONAD) {
                group.entries.sort((a, b) -> {
                    Rules.CombatTip ca = TipsCatalogue.combatTip(data.rules, a);
                    Rules.CombatTip cb = TipsCatalogue.combatTip(data.rules, b);
                    int floor = Integer.compare(ca.floor, cb.floor);
                    return floor != 0 ? floor : Integer.compare(ca.battleOrder, cb.battleOrder);
                });
            } else {
                group.entries.sort((a, b) -> Integer.compare(a.slot, b.slot));
            }
        }
        // A section whose entries all went into a group above it says nothing.
        List<Item> cleaned = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            Item item = result.get(i);
            boolean emptySection = item.type == SECTION
                && (i + 1 >= result.size() || result.get(i + 1).type == SECTION);
            if (!emptySection) cleaned.add(item);
        }
        return cleaned;
    }

    /** Same kind, tag and line on several days: one row (null for entries that never repeat). */
    private String repeatKey(Plan.Reminder r) {
        if (r == null || TipsCatalogue.isRequestEntry(r) || TipsCatalogue.DIALOGUE.equals(r.kind)
            || TipsCatalogue.COMBAT.equals(r.kind) || r.untilDay > Rules.dayOf(r.slot)) {
            return null;
        }
        return r.kind + "|" + r.labelZh + "|" + lineOf(r);
    }

    /** 已经过去 folds into one bar 「已经过去 · 12」; open, its rows follow the bar. */
    private List<Item> foldPast(List<Item> items) {
        int past = 0;
        for (Item item : items) if (item.section == PAST && item.type != SECTION) past++;
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (item.section != PAST) {
                out.add(item);
            } else if (item.type == SECTION) {
                out.add(new Item(SECTION, PAST, null, past));
            } else if (pastOpen) {
                out.add(item);
            }
        }
        return out;
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

    // ------------------------------------------------------------------ dates

    /** A period entry keeps its actual unlock date, even for a request carried over from last period. */
    static String dateLine(Plan.Reminder reminder, int observedSlot) {
        return dateLine(reminder, observedSlot, null);
    }

    /**
     * 「9月28日起 · 10月25日截止」, 「10月1日起 · 今天截止」, 「10月20日–10月24日 · 进行中」,
     * 「10月22日 晚上 · 今天」.
     */
    static String dateLine(Plan.Reminder reminder, int observedSlot, Integer endSlot) {
        int from = Rules.dayOf(reminder.slot);
        int today = Rules.dayOf(observedSlot);
        String date = Zh.date(from);
        if ("calendar".equals(reminder.kind) && reminder.untilDay >= from) {
            boolean now = reminder.slot <= observedSlot && observedSlot <= (endSlot == null
                ? Rules.slot(reminder.untilDay, true) : endSlot);
            return Zh.dateRange(from, reminder.untilDay) + (now ? " · 进行中" : "");
        }
        if (reminder.untilDay > from) {
            return date + "起 · " + (reminder.untilDay == today ? "今天" : Zh.date(reminder.untilDay)) + "截止";
        } else if (reminder.untilDay == from) {
            return date + (reminder.untilDay == today ? " · 今天截止" : "截止");
        }
        date += Rules.isEvening(reminder.slot) ? " 晚上" : " 白天";
        return from == today ? date + " · 今天" : date;
    }

    /** The date of an entry as its sheet shows it; null for an undated lookup (rank-up answers). */
    private String dateLine(Plan.Reminder reminder) {
        if (TipsCatalogue.DIALOGUE.equals(reminder.kind) || reminder.slot < 0) return null;
        if (TipsCatalogue.isRequestEntry(reminder) && reminder.untilDay < 0) {
            return Zh.date(Rules.dayOf(reminder.slot)) + "起可以接";
        }
        PeriodTips.Period selected = allScope ? catalogue : period;
        if (selected != null && selected.ongoingIds.contains(reminder.id)) {
            if ("period.nurse".equals(reminder.id)) return "探索后的第二天";
            if (reminder.id.startsWith("period.")) return "这一期随时";
            return Zh.date(Rules.dayOf(reminder.slot)) + "起随时";
        }
        return dateLine(reminder, currentSlot, selected == null ? null : selected.calendarEndSlots.get(reminder.id));
    }

    /** What a row adds beside its date plate: a deadline, a range in progress, 随时. */
    private String rowMeta(Plan.Reminder r) {
        int from = Rules.dayOf(r.slot);
        int today = Rules.dayOf(currentSlot);
        PeriodTips.Period selected = allScope ? catalogue : period;
        if ("calendar".equals(r.kind) && r.untilDay > from) {
            Integer end = selected == null ? null : selected.calendarEndSlots.get(r.id);
            boolean now = r.slot <= currentSlot && currentSlot <= (end == null ? Rules.slot(r.untilDay, true) : end);
            return "到 " + Zh.date(r.untilDay) + (now ? " · 进行中" : "");
        }
        if ("calendar".equals(r.kind)) return null;
        if (r.untilDay > from) return r.untilDay == today ? "今天截止" : Zh.date(r.untilDay) + "截止";
        if (r.untilDay == from) return r.untilDay == today ? "今天截止" : "当天截止";
        if ("period.nurse".equals(r.id)) return "探索后的第二天";
        if (selected != null && selected.ongoingIds.contains(r.id) && from <= today) {
            return TipsCatalogue.isRequestEntry(r) ? "随时可以接" : "随时";
        }
        return null;
    }

    /** The row's line: the reminder's text without the tag it starts with (「羁绊剧情 · 」, 「课堂提问：」). */
    static String lineOf(Plan.Reminder r) {
        String line = ReminderViews.lineOf(r);
        if (r.labelZh != null && !r.labelZh.trim().isEmpty()) {
            String prefix = r.labelZh.trim() + " · ";
            if (line.startsWith(prefix) && line.length() > prefix.length()) line = line.substring(prefix.length());
        }
        return line;
    }

    /** True when the sheet would say more than the row. */
    private boolean hasMore(Plan.Reminder r) {
        if (TipsCatalogue.combatTip(data.rules, r) != null || TipsCatalogue.DIALOGUE.equals(r.kind)) return true;
        String detail = r.detailZh == null ? "" : r.detailZh.trim();
        return !detail.isEmpty() && !detail.equals(lineOf(r)) && !detail.equals(r.textZh == null ? "" : r.textZh.trim());
    }

    // ------------------------------------------------------------------ adapter

    private final class Adapter extends BaseAdapter {
        private List<Item> items = new ArrayList<>();

        void setItems(List<Item> value) {
            items = value;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 5; }
        @Override public int getItemViewType(int position) { return items.get(position).type; }
        @Override public boolean isEnabled(int position) { return false; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Item item = items.get(position);
            switch (item.type) {
                case SECTION: return sectionView(item, position == 0);
                case HERO: return hero();
                case DIRECTORY: return directory();
                case GROUP: return divided(entryGroup(item.group));
                default: return divided(reminderRow(item.reminder, item.section));
            }
        }
    }

    /** A row with a hairline under it: rows are separated by space and a fading rule, never boxed. */
    private View divided(View row) {
        LinearLayout box = Ui.column(c);
        box.addView(row, Ui.matchWrap());
        View rule = Ui.fadingRule(c);
        rule.setAlpha(0.45f);
        box.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 1))));
        return box;
    }

    private View sectionView(Item item, boolean first) {
        if (item.section == PAST && item.title == null) return pastBar(item.count);
        String title = item.title != null ? item.title
            : item.section >= COMBAT_GROUP ? COMBAT_TITLES[item.section - COMBAT_GROUP] : SECTION_TITLES[item.section];
        LinearLayout header = Ui.column(c);
        Ui.pad(header, 0, first ? 4 : 16, 0, 0);
        header.addView(Ui.sectionHeader(c, title, null));
        return header;
    }

    private View pastBar(int count) {
        LinearLayout bar = Ui.row(c);
        bar.setTag("tips_past_bar");
        bar.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        Ui.pad(bar, 12, 0, 8, 0);
        bar.setBackground(Ui.band(c).pressedFill(Ui.col(c, R.color.ui_cyan_faint)));
        TextView title = Ui.mixed(c, SECTION_TITLES[PAST] + " · " + count, 15, Ui.col(c, R.color.p3r_ink_muted), true);
        bar.addView(title, Ui.weight(1));
        ImageView arrow = Ui.icon(c, R.drawable.ui_ic_down, Ui.col(c, R.color.p3r_ink_muted), 18);
        arrow.setRotation(pastOpen ? 180f : 0f);
        bar.addView(arrow);
        bar.setClickable(true);
        bar.setFocusable(true);
        bar.setContentDescription(SECTION_TITLES[PAST] + " " + count + " 条，" + (pastOpen ? "收起" : "展开"));
        bar.setOnClickListener(v -> {
            pastOpen = !pastOpen;
            refresh(false);
        });
        LinearLayout box = Ui.column(c);
        Ui.pad(box, 0, 16, 0, 4);
        box.addView(bar, Ui.matchWrap());
        return box;
    }

    // ------------------------------------------------------------------ banner and directory

    /** 本期's banner: the block, its floors and the period's dates. */
    private View hero() {
        LinearLayout card = Ui.column(c);
        card.setTag("tips_period_hero");
        card.setBackground(Ui.heroCard(c));
        Ui.pad(card, 22, 14, 18, 16);
        LinearLayout line = Ui.row(c);
        line.setGravity(Gravity.BOTTOM);
        TextView block = Ui.text(c, period.blockZh, 22, Ui.col(c, R.color.p3r_white), true);
        block.setMaxLines(2);
        line.addView(block, Ui.weight(1));
        if (period.floorsZh != null && !period.floorsZh.isEmpty()) {
            TextView floors = Ui.numeral(c, period.floorsZh, 28, Ui.col(c, R.color.p3r_cyan_400));
            LinearLayout.LayoutParams fp = Ui.wrap();
            fp.leftMargin = Ui.dp(c, 10);
            line.addView(floors, fp);
        }
        card.addView(line, Ui.matchWrap());
        String dates = Zh.dateRange(period.fromDay, period.toDay);
        card.addView(Ui.mixed(c, dates, 14, Ui.col(c, R.color.p3r_ice_100), false), top(4));
        card.setContentDescription(period.blockZh + " " + period.floorsZh + "，" + dates);
        LinearLayout box = Ui.column(c);
        Ui.pad(box, 0, 4, 0, 6);
        box.addView(card, Ui.matchWrap());
        return box;
    }

    /** 全部 without a search: big slanted menu tiles, each a kind and its count. */
    private View directory() {
        int links = 0;
        int answers = 0;
        int requests = 0;
        int fights = 0;
        int others = 0;
        Set<String> linkIds = new HashSet<>();
        for (Plan.Reminder r : catalogue.items) {
            Rules.Link link = TipsCatalogue.dialogueLink(data.rules, r);
            if (link != null) {
                if (linkIds.add(link.id)) links++;
            } else if (r.isAnswer()) {
                answers++;
            } else if (Plan.Reminder.REQUEST.equals(r.kind)) {
                requests++;
            } else if (TipsCatalogue.COMBAT.equals(r.kind)) {
                fights++;
            } else if (!TipsCatalogue.DIALOGUE.equals(r.kind)) {
                others++;
            }
        }
        LinearLayout box = Ui.column(c);
        Ui.pad(box, 0, 8, 0, 8);
        Object[][] tiles = {
            {"社群对话", links, TipsCatalogue.Content.DIALOGUE, "dialogue"},
            {"课堂 · 考试", answers, TipsCatalogue.Content.CLASS_EXAM, "class"},
            {"伊莉莎白的委托", requests, TipsCatalogue.Content.REQUEST, "request"},
            {"战斗", fights, TipsCatalogue.Content.COMBAT, "combat"},
            {"日程与其他", others, null, "other"},
        };
        int index = 0;
        for (Object[] tile : tiles) {
            int count = (Integer) tile[1];
            if (count == 0) continue;
            TipsCatalogue.Content value = (TipsCatalogue.Content) tile[2];
            View view = tile(Zh.dots((String) tile[0]), count, "tips_tile_" + tile[3],
                value == null ? this::openMore : () -> chooseContent(value));
            LinearLayout.LayoutParams p = Ui.matchWrap();
            // Stepped like the game's main menu: each tile starts a little further right.
            p.leftMargin = Ui.dp(c, index * 12);
            p.topMargin = Ui.dp(c, index == 0 ? 0 : 10);
            box.addView(view, p);
            index++;
        }
        return box;
    }

    private View tile(String label, int count, String tag, Runnable open) {
        LinearLayout tile = Ui.row(c);
        tile.setTag(tag);
        tile.setMinimumHeight(Ui.dp(c, 72));
        Ui.pad(tile, 20, 8, 22, 8);
        int cut = Ui.dp(c, 14);
        tile.setBackground(new SlantedPanelDrawable()
            .gradient(Ui.col(c, R.color.ui_glass_top), Ui.col(c, R.color.ui_glass_bottom))
            .lightSlash(Ui.col(c, R.color.ui_slash))
            .cornerTick(Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 200), Ui.dp(c, 2))
            .pressedFill(Ui.col(c, R.color.p3r_navy_700))
            .pressedStroke(Ui.col(c, R.color.p3r_cyan_400))
            .cuts(cut, 0, cut, 0));
        TextView name = Ui.text(c, label, 21, Ui.col(c, R.color.p3r_white), true);
        tile.addView(name, Ui.weight(1));
        TextView number = Ui.numeral(c, String.valueOf(count), 36, Ui.col(c, R.color.p3r_cyan_400));
        tile.addView(number, Ui.wrap());
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(label + "，" + count + " 条");
        tile.setOnClickListener(v -> open.run());
        Motion.pressable(tile);
        return tile;
    }

    // ------------------------------------------------------------------ rows

    /** The kind tag's colours: answers cyan, deadlines and requests gold, calendar white, fights red. */
    private int[] tagColors(Plan.Reminder r) {
        int navy = Ui.col(c, R.color.p3r_navy_950);
        if (r.isAnswer()) return new int[]{Ui.col(c, R.color.p3r_cyan_400), navy};
        if (TipsCatalogue.COMBAT.equals(r.kind)) return new int[]{Ui.col(c, R.color.p3r_danger), navy};
        if ("calendar".equals(r.kind)) return new int[]{Ui.col(c, R.color.p3r_white), navy};
        if (Plan.Reminder.REQUEST.equals(r.kind) || Plan.Reminder.RESCUE.equals(r.kind)
            || r.priority >= Plan.Reminder.MISSABLE || r.untilDay >= 0) {
            return new int[]{Ui.col(c, R.color.ui_gold), navy};
        }
        return new int[]{Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 60), Ui.col(c, R.color.p3r_cyan_200)};
    }

    private LinearLayout datePlate(Plan.Reminder r) {
        int today = Rules.dayOf(currentSlot);
        if (r.id != null && r.id.startsWith("period.")) {
            return CombatViews.plate(c, null, "随时", null, Ui.col(c, R.color.p3r_cyan_300));
        }
        int day = Rules.dayOf(r.slot);
        Rules.Day info = data.rules.day(day);
        String month = (info != null ? info.month() : Zh.dateOf(day).getMonthValue()) + "月";
        String dom = String.valueOf(info != null ? info.dayOfMonth() : Zh.dateOf(day).getDayOfMonth());
        boolean isToday = day == today;
        String under = isToday ? "今天" : Rules.isEvening(r.slot) ? "晚上" : null;
        return CombatViews.plate(c, month, dom, under,
            Ui.col(c, isToday ? R.color.p3r_cyan_400 : R.color.p3r_white));
    }

    private View reminderRow(Plan.Reminder r, int section) {
        Rules.CombatTip combat = TipsCatalogue.combatTip(data.rules, r);
        if (combat != null) {
            View fight = CombatViews.row(c, data.rules, combat, () -> showDetail(r));
            fight.setTag("tips_row_" + r.id);
            return fight;
        }
        LinearLayout row = Ui.row(c);
        row.setTag("tips_row_" + r.id);
        row.setGravity(Gravity.TOP);
        row.setMinimumHeight(Ui.dp(c, 60));
        Ui.pad(row, 0, 10, 0, 10);
        if (r.slot >= 0) {
            row.addView(datePlate(r), new LinearLayout.LayoutParams(Ui.dp(c, CombatViews.PLATE_WIDTH),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout texts = Ui.column(c);
        LinearLayout head = Ui.row(c);
        int[] colors = tagColors(r);
        String label = r.labelZh == null || r.labelZh.trim().isEmpty() ? "提醒" : r.labelZh.trim();
        head.addView(Ui.tag(c, label, colors[0], colors[1]), Ui.wrap());
        String meta = rowMeta(r);
        if (meta != null) {
            boolean urgent = meta.startsWith("今天");
            TextView m = Ui.mixed(c, meta, 13, Ui.col(c, urgent ? R.color.ui_gold : R.color.p3r_ink_muted), urgent);
            m.setMaxLines(1);
            m.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mp = Ui.weight(1);
            mp.leftMargin = Ui.dp(c, 8);
            head.addView(m, mp);
        }
        texts.addView(head, Ui.matchWrap());
        String line = lineOf(r);
        texts.addView(Ui.mixed(c, line, 15, Ui.col(c, R.color.p3r_white), false), top(5));
        String gain = ReminderViews.gainOf(r);
        if (gain != null) texts.addView(Ui.mixed(c, gain, 13, Ui.col(c, R.color.p3r_cyan_300), true), top(2));
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, r.slot >= 0 ? 12 : 0);
        row.addView(texts, tp);
        boolean more = hasMore(r);
        if (more) row.addView(CombatViews.chevron(c), chevronParams());
        if (section == PAST) row.setAlpha(0.6f);
        String when = dateLine(r);
        row.setContentDescription((when == null ? "" : when + "，") + label + "，" + line
            + (gain == null ? "" : "，" + gain));
        if (more) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
                .skew(Ui.dp(c, 6)));
            row.setOnClickListener(v -> showDetail(r));
        }
        return row;
    }

    private LinearLayout.LayoutParams chevronParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18));
        p.leftMargin = Ui.dp(c, 8);
        p.topMargin = Ui.dp(c, 22);
        return p;
    }

    // ------------------------------------------------------------------ groups

    private boolean isExpanded(EntryGroup group) {
        return query.trim().isEmpty() ? expandedGroups.contains(group.key) : !collapsedSearchGroups.contains(group.key);
    }

    private View entryGroup(EntryGroup group) {
        LinearLayout card = Ui.column(c);
        card.setTag("tips_group_" + group.key);
        LinearLayout header = Ui.row(c);
        header.setTag("tips_group_header_" + group.key);
        header.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
            .skew(Ui.dp(c, 6)));
        header.setMinimumHeight(Ui.dp(c, 64));
        Ui.pad(header, 0, 10, 0, 10);
        String spoken;
        switch (group.kind) {
            case G_DIALOGUE: spoken = linkHeader(header, group); break;
            case G_MONAD: spoken = monadHeader(header, group); break;
            case G_REQUESTS: spoken = requestsHeader(header, group); break;
            default: spoken = repeatHeader(header, group); break;
        }
        ImageView arrow = Ui.icon(c, R.drawable.ui_ic_down, Ui.col(c, R.color.p3r_cyan_300), 20);
        arrow.setRotation(isExpanded(group) ? 180f : 0f);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20));
        ap.leftMargin = Ui.dp(c, 8);
        header.addView(arrow, ap);
        card.addView(header, Ui.matchWrap());
        if (group.kind == G_DIALOGUE) {
            View next = nextLine(group);
            if (next != null) card.addView(next, Ui.matchWrap());
        }

        LinearLayout body = Ui.column(c);
        body.setTag("tips_group_body_" + group.key);
        Ui.pad(body, 0, 2, 0, 12);
        switch (group.kind) {
            case G_DIALOGUE: addRankTrack(body, group); break;
            case G_MONAD: addMonadEntries(body, group); break;
            case G_REQUESTS: addRequestRows(body, group); break;
            default: addRepeatDates(body, group); break;
        }
        body.setVisibility(isExpanded(group) ? View.VISIBLE : View.GONE);
        card.addView(body, Ui.matchWrap());
        header.setClickable(true);
        header.setFocusable(true);
        header.setContentDescription(spoken + (isExpanded(group) ? "，收起" : "，展开"));
        header.setOnClickListener(v -> {
            boolean expanded = !isExpanded(group);
            if (query.trim().isEmpty()) {
                if (expanded) expandedGroups.add(group.key);
                else expandedGroups.remove(group.key);
            } else {
                if (expanded) collapsedSearchGroups.remove(group.key);
                else collapsedSearchGroups.add(group.key);
            }
            header.setContentDescription(spoken + (expanded ? "，收起" : "，展开"));
            if (Ui.animationsEnabled()) {
                arrow.animate().rotation(expanded ? 180f : 0f).setDuration(180).setInterpolator(Motion.SETTLE).start();
            } else {
                arrow.setRotation(expanded ? 180f : 0f);
            }
            animateExpansion(body, expanded);
            if (expanded) revealAfterExpanding(card);
        });
        return card;
    }

    /** A social link like the 社群 tab's cards: the arcana numeral, the arcana over the person, RANK and pips. */
    private String linkHeader(LinearLayout header, EntryGroup group) {
        Rules.Link link = group.link;
        String numeral = link.numeral != null && !link.numeral.isEmpty() ? link.numeral : Zh.roman(link.arcanaNo);
        TextView roman = Ui.numeral(c, numeral, 26, Ui.col(c, R.color.p3r_cyan_400));
        roman.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        roman.setGravity(Gravity.CENTER);
        header.addView(roman, new LinearLayout.LayoutParams(Ui.dp(c, CombatViews.PLATE_WIDTH),
            ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout names = Ui.column(c);
        names.addView(Ui.text(c, link.arcanaZh, 13, Ui.col(c, R.color.p3r_cyan_300), true));
        String person = link.personZh == null || link.personZh.isEmpty() ? link.arcanaZh : link.personZh;
        TextView name = Ui.text(c, person, 18, Ui.col(c, R.color.p3r_white), true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);
        LinearLayout.LayoutParams np = Ui.weight(1);
        np.leftMargin = Ui.dp(c, 12);
        header.addView(names, np);
        int rank = observedRank(link);
        String spoken = link.labelZh;
        if (rank >= 0) {
            LinearLayout state = Ui.column(c);
            state.setGravity(Gravity.END);
            TextView value = Ui.numeral(c, rank == 0 ? "—" : Zh.rankValue(rank).equals("MAX") ? "MAX" : "RANK " + rank,
                16, Ui.col(c, rank == 0 ? R.color.p3r_ink_muted : R.color.p3r_white));
            state.addView(value, Ui.wrap());
            RankPips pips = new RankPips(c);
            pips.set(rank, rank);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 7));
            pp.topMargin = Ui.dp(c, 4);
            state.addView(pips, pp);
            header.addView(state, Ui.wrap());
            spoken += "，" + (rank == 0 ? "还没解锁" : Zh.rank(rank));
        }
        return spoken;
    }

    /** The player's recorded RANK of {@code link}, or -1 without a game date. */
    private int observedRank(Rules.Link link) {
        if (data == null || !data.hasState() || link.idx < 0 || link.idx >= data.observed().rank.length) return -1;
        return Math.max(0, Math.min(10, data.observed().rank[link.idx]));
    }

    private static final Pattern ANSWER_LINE = Pattern.compile("\\n(.+)$");

    /**
     * Under a link's header, from the recorded RANK: 「下一次 RANK 3 → 4 · 回答：…」 (opens the scene),
     * 「还没解锁 · 6月19日起」 (opens the unlock), nothing at MAX or without a game date.
     */
    private View nextLine(EntryGroup group) {
        int rank = observedRank(group.link);
        if (rank < 0 || rank >= 10) return null;
        Plan.Reminder target = null;
        for (Plan.Reminder entry : group.entries) {
            if (TipsCatalogue.dialogueRank(entry) == rank + 1) target = entry;
        }
        if (target == null) return null;
        String text;
        String answers = null;
        if (rank == 0) {
            int earliest = group.link.unlock == null ? -1 : group.link.unlock.earliestDay;
            text = "还没解锁" + (earliest > Rules.dayOf(currentSlot) ? " · " + Zh.date(earliest) + "起" : "");
        } else {
            Matcher answer = ANSWER_LINE.matcher(target.textZh == null ? "" : target.textZh);
            text = "下一次 " + TipsCatalogue.rankStep(rank + 1);
            // As everywhere in the app: real answers only, the friend hint short (Dialogue.shown).
            if (answer.find()) answers = Dialogue.shown(answer.group(1));
        }
        LinearLayout row = Ui.row(c);
        row.setTag("tips_next_" + group.link.id);
        row.setMinimumHeight(Ui.dp(c, 44));
        Ui.pad(row, CombatViews.PLATE_WIDTH + 12, 0, 0, 8);
        LinearLayout lines = Ui.column(c);
        lines.addView(Ui.mixed(c, text, 14, Ui.col(c, R.color.p3r_cyan_400), true));
        if (answers != null) {
            TextView line = Ui.mixed(c, answers, 15, Ui.col(c, R.color.p3r_white), false);
            line.setMaxLines(3);
            line.setEllipsize(TextUtils.TruncateAt.END);
            lines.addView(line, top(1));
        }
        row.addView(lines, Ui.weight(1));
        if (answers != null) text += " · " + answers;
        row.addView(CombatViews.chevron(c), chevronParamsCentered());
        row.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
            .skew(Ui.dp(c, 6)));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(text);
        Plan.Reminder open = target;
        row.setOnClickListener(v -> showDetail(open));
        return row;
    }

    private LinearLayout.LayoutParams chevronParamsCentered() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18));
        p.leftMargin = Ui.dp(c, 8);
        p.gravity = Gravity.CENTER_VERTICAL;
        return p;
    }

    /** Ten slanted tiles in two rows: 解锁, 1→2 … 9→MAX; played ranks dimmed, the next one lit. */
    private void addRankTrack(LinearLayout body, EntryGroup group) {
        int rank = observedRank(group.link);
        Map<Integer, Plan.Reminder> byScene = new HashMap<>();
        for (Plan.Reminder entry : group.entries) byScene.put(TipsCatalogue.dialogueRank(entry), entry);
        LinearLayout row = null;
        for (int scene = 1; scene <= 10; scene++) {
            if ((scene - 1) % 5 == 0) {
                row = Ui.row(c);
                LinearLayout.LayoutParams rp = Ui.matchWrap();
                rp.topMargin = Ui.dp(c, scene == 1 ? 4 : 6);
                rp.leftMargin = Ui.dp(c, CombatViews.PLATE_WIDTH - 8);
                body.addView(row, rp);
            }
            LinearLayout.LayoutParams params = Ui.weight(1);
            if ((scene - 1) % 5 != 0) params.leftMargin = Ui.dp(c, 5);
            Plan.Reminder entry = byScene.get(scene);
            if (entry == null) {
                row.addView(new View(c), params);
                continue;
            }
            boolean played = rank >= 0 && scene <= rank;
            boolean next = rank >= 0 && scene == rank + 1;
            TextView tile = scene == 1
                ? Ui.text(c, "解锁", 15, Ui.col(c, next ? R.color.p3r_navy_950 : R.color.p3r_ice_100), true)
                : Ui.numeral(c, (scene - 1) + "→" + (scene >= 10 ? "MAX" : String.valueOf(scene)), 16,
                    Ui.col(c, next ? R.color.p3r_navy_950 : R.color.p3r_ice_100));
            tile.setTag("tips_entry_" + entry.id);
            tile.setGravity(Gravity.CENTER);
            tile.setMinHeight(Ui.dp(c, 44));
            SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 7));
            if (next) {
                bg.fill(Ui.col(c, R.color.p3r_cyan_400)).pressedFill(Ui.col(c, R.color.p3r_cyan_200))
                    .slab(Ui.alpha(Ui.col(c, R.color.p3r_white), 210), Ui.dp(c, 2), Ui.dp(c, 2));
            } else {
                bg.fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 150)).pressedFill(Ui.col(c, R.color.p3r_navy_700));
            }
            tile.setBackground(bg);
            if (played) tile.setAlpha(0.45f);
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setContentDescription(group.link.labelZh + "，" + (scene == 1 ? "解锁条件和方法"
                : "RANK " + (scene - 1) + " 升到 " + (scene >= 10 ? "MAX" : "RANK " + scene) + "，查看回答")
                + (next ? "，下一次" : ""));
            tile.setOnClickListener(v -> showDetail(entry));
            row.addView(tile, params);
        }
    }

    /** The Monad block: its floors in the numeral face, the block's name and how many doors and passages. */
    private String monadHeader(LinearLayout header, EntryGroup group) {
        String floors = group.block != null && group.block.floorsZh != null ? group.block.floorsZh : "";
        TextView range = Ui.numeral(c, floors, floors.length() > 7 ? 18 : 22, Ui.col(c, R.color.p3r_cyan_400));
        range.setMinWidth(Ui.dp(c, 84));
        header.addView(range, Ui.wrap());
        LinearLayout names = Ui.column(c);
        String title = group.block == null || group.block.zh == null || group.block.zh.isEmpty() ? "蒙纳多" : group.block.zh;
        names.addView(Ui.mixed(c, title, 17, Ui.col(c, R.color.p3r_white), true));
        int doors = 0;
        int passages = 0;
        for (Plan.Reminder reminder : group.entries) {
            Rules.CombatTip combat = TipsCatalogue.combatTip(data.rules, reminder);
            if ("passage".equals(combat.encounterType)) passages++;
            else doors++;
        }
        List<String> parts = new ArrayList<>();
        if (doors > 0) parts.add("蒙纳多之门 " + doors);
        if (passages > 0) parts.add("蒙纳多通道 " + passages);
        String subtitle = String.join(" · ", parts);
        names.addView(Ui.mixed(c, subtitle, 13, Ui.col(c, R.color.p3r_ink_muted), false), top(2));
        LinearLayout.LayoutParams np = Ui.weight(1);
        np.leftMargin = Ui.dp(c, 10);
        header.addView(names, np);
        return title + "，" + floors + "，" + subtitle;
    }

    private void addMonadEntries(LinearLayout body, EntryGroup group) {
        String shownHeader = null;
        for (Plan.Reminder reminder : group.entries) {
            Rules.CombatTip combat = TipsCatalogue.combatTip(data.rules, reminder);
            boolean passage = "passage".equals(combat.encounterType);
            String mini = passage ? "蒙纳多通道" + (combat.floor > 0 ? " · " + combat.floor + "F" : "") : "蒙纳多之门 · 随机出现";
            if (!mini.equals(shownHeader)) {
                shownHeader = mini;
                TextView head = Ui.mixed(c, mini, 13, Ui.col(c, R.color.p3r_cyan_300), true);
                Ui.pad(head, CombatViews.PLATE_WIDTH + 12, 8, 0, 2);
                body.addView(head, Ui.matchWrap());
            }
            View entry = CombatViews.entry(c, combat, () -> showDetail(reminder));
            entry.setTag("tips_entry_" + reminder.id);
            LinearLayout.LayoutParams ep = Ui.matchWrap();
            ep.leftMargin = Ui.dp(c, passage ? CombatViews.PLATE_WIDTH - 40 : CombatViews.PLATE_WIDTH + 12);
            body.addView(entry, ep);
        }
    }

    /** 「伊莉莎白的委托 · 29 件」 with how many have a deadline. */
    private String requestsHeader(LinearLayout header, EntryGroup group) {
        TextView count = Ui.numeral(c, String.valueOf(group.entries.size()), 26, Ui.col(c, R.color.ui_gold));
        count.setGravity(Gravity.CENTER);
        header.addView(count, new LinearLayout.LayoutParams(Ui.dp(c, CombatViews.PLATE_WIDTH),
            ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout names = Ui.column(c);
        String title = "伊莉莎白的委托 · " + group.entries.size() + " 件";
        names.addView(Ui.mixed(c, title, 17, Ui.col(c, R.color.p3r_white), true));
        Map<Integer, Integer> deadlines = new LinkedHashMap<>();
        int today = Rules.dayOf(currentSlot);
        for (Plan.Reminder r : group.entries) {
            if (r.untilDay >= today) deadlines.merge(r.untilDay, 1, Integer::sum);
        }
        String sub = null;
        if (!deadlines.isEmpty()) {
            int first = Collections.min(deadlines.keySet());
            sub = Zh.date(first) + "截止 " + deadlines.get(first) + " 件";
            names.addView(Ui.mixed(c, sub, 13, Ui.col(c, R.color.ui_gold), true), top(2));
        }
        LinearLayout.LayoutParams np = Ui.weight(1);
        np.leftMargin = Ui.dp(c, 12);
        header.addView(names, np);
        return title + (sub == null ? "" : "，" + sub);
    }

    private void addRequestRows(LinearLayout body, EntryGroup group) {
        int today = Rules.dayOf(currentSlot);
        for (Plan.Reminder r : group.entries) {
            LinearLayout row = Ui.row(c);
            row.setTag("tips_entry_" + r.id);
            row.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
            Ui.pad(row, CombatViews.PLATE_WIDTH + 12, 6, 0, 6);
            TextView line = Ui.mixed(c, r.textZh, 15, Ui.col(c, R.color.p3r_white), false);
            row.addView(line, Ui.weight(1));
            String meta = r.untilDay >= 0 ? (r.untilDay == today ? "今天截止" : Zh.date(r.untilDay) + "截止") : null;
            if (meta != null) {
                TextView m = Ui.mixed(c, meta, 13, Ui.col(c, R.color.ui_gold), true);
                LinearLayout.LayoutParams mp = Ui.wrap();
                mp.leftMargin = Ui.dp(c, 8);
                row.addView(m, mp);
            }
            row.addView(CombatViews.chevron(c), chevronParamsCentered());
            if (r.untilDay >= 0 && r.untilDay < today) row.setAlpha(0.5f);
            row.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
                .skew(Ui.dp(c, 6)));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(r.textZh + (meta == null ? "" : "，" + meta));
            row.setOnClickListener(v -> showDetail(r));
            body.addView(row, Ui.matchWrap());
        }
    }

    /** 「上课犯困 · 7 天」 with its line; open, the dates (past ones dimmed). */
    private String repeatHeader(LinearLayout header, EntryGroup group) {
        Plan.Reminder first = group.entries.get(0);
        TextView count = Ui.numeral(c, String.valueOf(group.entries.size()), 26, Ui.col(c, R.color.p3r_white));
        count.setGravity(Gravity.CENTER);
        header.addView(count, new LinearLayout.LayoutParams(Ui.dp(c, CombatViews.PLATE_WIDTH),
            ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout texts = Ui.column(c);
        int[] colors = tagColors(first);
        String label = first.labelZh == null || first.labelZh.trim().isEmpty() ? "提醒" : first.labelZh.trim();
        LinearLayout head = Ui.row(c);
        head.addView(Ui.tag(c, label, colors[0], colors[1]), Ui.wrap());
        TextView days = Ui.mixed(c, group.entries.size() + " 天", 13, Ui.col(c, R.color.p3r_ink_muted), false);
        LinearLayout.LayoutParams dp = Ui.wrap();
        dp.leftMargin = Ui.dp(c, 8);
        head.addView(days, dp);
        texts.addView(head, Ui.matchWrap());
        String line = lineOf(first);
        texts.addView(Ui.mixed(c, line, 15, Ui.col(c, R.color.p3r_white), false), top(5));
        String gain = ReminderViews.gainOf(first);
        if (gain != null) texts.addView(Ui.mixed(c, gain, 13, Ui.col(c, R.color.p3r_cyan_300), true), top(2));
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 12);
        header.addView(texts, tp);
        return label + " " + group.entries.size() + " 天，" + line;
    }

    private void addRepeatDates(LinearLayout body, EntryGroup group) {
        FlowLayout dates = new FlowLayout(c, 6, 6);
        int today = Rules.dayOf(currentSlot);
        for (Plan.Reminder r : group.entries) {
            int day = Rules.dayOf(r.slot);
            boolean past = day < today;
            TextView chip = Ui.tag(c, Zh.date(day) + (Rules.isEvening(r.slot) ? " 晚上" : ""),
                day == today ? Ui.col(c, R.color.p3r_cyan_400) : Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 160),
                Ui.col(c, day == today ? R.color.p3r_navy_950 : R.color.p3r_ice_100));
            chip.setTag("tips_entry_" + r.id);
            if (past) chip.setAlpha(0.45f);
            if (hasMore(r)) {
                chip.setClickable(true);
                chip.setFocusable(true);
                chip.setOnClickListener(v -> showDetail(r));
            }
            dates.addView(chip);
        }
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.leftMargin = Ui.dp(c, CombatViews.PLATE_WIDTH + 12);
        p.topMargin = Ui.dp(c, 4);
        body.addView(dates, p);
        String detail = group.entries.get(0).detailZh;
        if (detail != null && !detail.trim().isEmpty()) {
            TextView more = Ui.mixed(c, detail.trim(), 14, Ui.col(c, R.color.p3r_ink_muted), false);
            more.setLineSpacing(0f, 1.2f);
            LinearLayout.LayoutParams mp = Ui.matchWrap();
            mp.leftMargin = p.leftMargin;
            mp.topMargin = Ui.dp(c, 8);
            body.addView(more, mp);
        }
    }

    /** After a group opens near the bottom, bring its first rows into view. */
    private void revealAfterExpanding(View card) {
        if (!Ui.animationsEnabled()) return;
        list.postDelayed(() -> {
            int overflow = card.getBottom() - (list.getHeight() - list.getPaddingBottom());
            if (overflow > 0) list.smoothScrollBy(Math.min(overflow, Math.max(0, card.getTop())), 240);
        }, 200);
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
        animator.setInterpolator(Motion.SETTLE);
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

    // ------------------------------------------------------------------ sheets

    private void showDetail(Plan.Reminder reminder) {
        Rules.CombatTip combat = TipsCatalogue.combatTip(data.rules, reminder);
        if (combat != null) {
            LinearLayout sheet = sheet();
            sheet.addView(CombatViews.detail(c, combat, combatWhere(combat, reminder)), Ui.matchWrap());
            closeButton(sheet);
            host.showSheet(sheet);
            return;
        }
        Rules.Link link = TipsCatalogue.dialogueLink(data.rules, reminder);
        if (link == null && reminder.id != null && reminder.id.startsWith("unlock.")) {
            link = data.rules.link(reminder.id.substring("unlock.".length()));
        }
        int rank = TipsCatalogue.dialogueRank(reminder);
        if (link != null && rank == 1) {
            unlockSheet(link, reminder);
        } else if (link != null && rank >= 2) {
            rankSheet(link, rank, null);
        } else {
            reminderSheet(reminder);
        }
    }

    private LinearLayout sheet() {
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 20);
        return sheet;
    }

    private void closeButton(LinearLayout sheet) {
        TextView close = Ui.quiet(c, CLOSE);
        close.setOnClickListener(v -> host.dismissSheet());
        LinearLayout.LayoutParams p = Ui.wrap();
        p.topMargin = Ui.dp(c, 14);
        p.gravity = Gravity.END;
        sheet.addView(close, p);
    }

    /** 「鄙俗之庭 亚巴扎 · 70–92F」 for a Monad fight, the date of a full moon, the floor of a guardian. */
    private String combatWhere(Rules.CombatTip combat, Plan.Reminder reminder) {
        if ("monad".equals(combat.kind)) {
            Rules.Block block = TipsCatalogue.monadBlock(data.rules, reminder);
            if (block != null) return Zh.dots(block.zh) + (block.floorsZh == null ? "" : " · " + block.floorsZh);
        }
        if ("guardian".equals(combat.kind) || "optional".equals(combat.kind)) {
            return combat.floor > 0 ? combat.floor + "F" : "随机出现";
        }
        return Zh.date(combat.fromDay) + (combat.floor > 0 ? " · " + combat.floor + "F" : "");
    }

    /** A dated tip: its tag and date over the line as the title, the details as labelled rows. */
    private void reminderSheet(Plan.Reminder reminder) {
        LinearLayout sheet = sheet();
        LinearLayout tags = Ui.row(c);
        int[] colors = tagColors(reminder);
        String label = reminder.labelZh == null || reminder.labelZh.trim().isEmpty() ? "提醒" : reminder.labelZh.trim();
        tags.addView(Ui.tag(c, label, colors[0], colors[1]), Ui.wrap());
        String when = dateLine(reminder);
        if (when != null) {
            TextView date = Ui.mixed(c, when, 14, Ui.col(c, R.color.ui_gold), true);
            LinearLayout.LayoutParams dp = Ui.weight(1);
            dp.leftMargin = Ui.dp(c, 10);
            tags.addView(date, dp);
        }
        sheet.addView(tags, Ui.matchWrap());
        String line = lineOf(reminder);
        sheet.addView(CombatViews.sheetTitle(c, line), top(8));
        String gain = ReminderViews.gainOf(reminder);
        if (gain != null) sheet.addView(Ui.mixed(c, gain, 15, Ui.col(c, R.color.p3r_cyan_300), true), top(2));
        String detail = reminder.detailZh == null ? "" : reminder.detailZh.trim();
        if (!detail.isEmpty() && !detail.equals(line)) {
            sheet.addView(detailBody(detail, TipsCatalogue.isRequestEntry(reminder)), top(10));
        }
        closeButton(sheet);
        host.showSheet(sheet);
    }

    private static final Pattern LABELLED = Pattern.compile("^(开放|前置|截止|奖励|怎么做|条件|解锁条件)：(.+)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+)\\. (.+?)(?:（([^（）]+)）)?$");

    /**
     * Details as the player reads them: 「开放：…」「前置：…」「奖励：…」 as labelled rows (a request's other
     * lines under 怎么做), numbered steps with their time and cost as small tags, the rest as text.
     */
    private View detailBody(String detail, boolean request) {
        LinearLayout body = Ui.column(c);
        for (String raw : detail.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                body.addView(Ui.gap(c, 6));
                continue;
            }
            List<String[]> labelled = new ArrayList<>();
            for (String part : line.split("；")) {
                Matcher m = LABELLED.matcher(part.trim());
                if (!m.matches()) {
                    labelled = null;
                    break;
                }
                labelled.add(new String[]{m.group(1), m.group(2)});
            }
            if (labelled != null && !labelled.isEmpty()) {
                for (String[] pair : labelled) body.addView(labelRow(pair[0], pair[1]), top(6));
                continue;
            }
            Matcher numbered = NUMBERED.matcher(line);
            if (numbered.matches()) {
                body.addView(stepRow(numbered.group(1), numbered.group(2), numbered.group(3)), top(8));
                continue;
            }
            if (request) {
                body.addView(labelRow("怎么做", line), top(6));
                continue;
            }
            boolean status = line.startsWith("已经解锁了") || line.endsWith("已经救出来了。");
            TextView text = Ui.mixed(c, line, 15, Ui.col(c, status ? R.color.p3r_cyan_300 : R.color.p3r_ice_100), status);
            text.setLineSpacing(0f, 1.3f);
            body.addView(text, top(6));
        }
        return body;
    }

    private View labelRow(String label, String value) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        TextView name = Ui.text(c, label, 13, Ui.col(c, R.color.p3r_cyan_300), true);
        name.setMinWidth(Ui.dp(c, 60));
        name.setPadding(0, Ui.dp(c, 2), 0, 0);
        row.addView(name, Ui.wrap());
        TextView text = Ui.mixed(c, value, 15, Ui.col(c, R.color.p3r_ice_100), false);
        text.setLineSpacing(0f, 1.3f);
        row.addView(text, Ui.weight(1));
        return row;
    }

    private View stepRow(String number, String text, String notes) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        TextView n = Ui.numeral(c, number, 22, Ui.col(c, R.color.p3r_cyan_400));
        n.setMinWidth(Ui.dp(c, 28));
        row.addView(n, Ui.wrap());
        LinearLayout texts = Ui.column(c);
        TextView body = Ui.mixed(c, text, 15, Ui.col(c, R.color.p3r_ice_100), false);
        body.setLineSpacing(0f, 1.3f);
        texts.addView(body, Ui.matchWrap());
        if (notes != null) {
            FlowLayout tags = new FlowLayout(c, 6, 4);
            for (String note : notes.split("；")) {
                if (note.trim().isEmpty()) continue;
                tags.addView(Ui.tag(c, note.trim(), Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 50),
                    Ui.col(c, R.color.p3r_cyan_200)));
            }
            texts.addView(tags, top(4));
        }
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 6);
        row.addView(texts, tp);
        return row;
    }

    /** 「解锁女教皇社群」: the earliest day as a tag, the conditions and the numbered steps. */
    private void unlockSheet(Rules.Link link, Plan.Reminder reminder) {
        LinearLayout sheet = sheet();
        boolean story = link.kind == Rules.LinkKind.STORY;
        String[] lines = reminder.detailZh == null ? new String[0] : reminder.detailZh.split("\n");
        LinearLayout tags = Ui.row(c);
        tags.addView(Ui.tag(c, "社群解锁", Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 60),
            Ui.col(c, R.color.p3r_cyan_200)), Ui.wrap());
        int from = 0;
        if (lines.length > 0 && lines[0].contains("起")) {
            String when = lines[0].substring(0, lines[0].indexOf('起') + 1);
            LinearLayout.LayoutParams wp = Ui.wrap();
            wp.leftMargin = Ui.dp(c, 6);
            tags.addView(Ui.tag(c, when, Ui.col(c, R.color.ui_gold), Ui.col(c, R.color.p3r_navy_950)), wp);
            if (story) {
                LinearLayout.LayoutParams sp = Ui.wrap();
                sp.leftMargin = Ui.dp(c, 6);
                tags.addView(Ui.tag(c, "随剧情解锁", Ui.alpha(Ui.col(c, R.color.p3r_white), 40),
                    Ui.col(c, R.color.p3r_ice_100)), sp);
            }
            from = 1;
        }
        sheet.addView(tags, Ui.matchWrap());
        sheet.addView(CombatViews.sheetTitle(c, (story ? "" : "解锁") + link.arcanaZh + "社群"), top(8));
        if (link.personZh != null && !link.personZh.isEmpty()) {
            sheet.addView(Ui.text(c, link.personZh, 15, Ui.col(c, R.color.p3r_cyan_200), true), top(0));
        }
        String rest = String.join("\n", Arrays.asList(lines).subList(from, lines.length)).trim();
        if (!rest.isEmpty()) sheet.addView(detailBody(rest, false), top(10));
        closeButton(sheet);
        host.showSheet(sheet);
    }

    /**
     * A rank-up scene: the arcana numeral and the person, 「RANK 1 → 2」 in big numerals, a [朋友｜恋人]
     * preview for links that can be romanced (the profile stays as it is), then one choice plate per
     * question.
     */
    private void rankSheet(Rules.Link link, int scene, Boolean romance) {
        LinearLayout sheet = sheet();
        sheet.setTag("tips_rank_sheet");
        LinearLayout head = Ui.row(c);
        String numeral = link.numeral != null && !link.numeral.isEmpty() ? link.numeral : Zh.roman(link.arcanaNo);
        TextView roman = Ui.numeral(c, numeral, 40, Ui.col(c, R.color.p3r_cyan_400));
        roman.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(roman, Ui.wrap());
        LinearLayout names = Ui.column(c);
        names.addView(Ui.text(c, link.arcanaZh, 13, Ui.col(c, R.color.p3r_cyan_300), true));
        String person = link.personZh == null || link.personZh.isEmpty() ? link.arcanaZh : link.personZh;
        TextView title = Ui.text(c, person, 22, Ui.col(c, R.color.p3r_white), true);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        names.addView(title);
        LinearLayout.LayoutParams np = Ui.weight(1);
        np.leftMargin = Ui.dp(c, 14);
        head.addView(names, np);
        sheet.addView(head, Ui.matchWrap());
        sheet.addView(Ui.numeral(c, TipsCatalogue.rankStep(scene), 30, Ui.col(c, R.color.p3r_white)), top(10));
        LinearLayout content = Ui.column(c);
        if (link.romanceable) {
            boolean shownRomance = romance != null ? romance
                : app.p3r.dayplanner.v3.engine.DialogueHint.romanceRoute(data.profile(), link.idx);
            SegmentedControl route = new SegmentedControl(c, Arrays.asList("朋友", "恋人"));
            route.setTag("tips_route_toggle");
            route.setSelected(shownRomance ? 1 : 0);
            route.segment(0).setContentDescription("看朋友路线的回答");
            route.segment(1).setContentDescription("看恋人路线的回答");
            route.setListener(index -> fillScene(content, link, scene, index == 1));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.dp(c, 168), ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = Ui.dp(c, 10);
            sheet.addView(route, rp);
            fillScene(content, link, scene, shownRomance);
        } else {
            fillScene(content, link, scene, null);
        }
        sheet.addView(content, Ui.matchWrap());
        closeButton(sheet);
        host.showSheet(sheet);
    }

    /** The scene's answers for one route (null: the profile's), as choice plates. */
    private void fillScene(LinearLayout content, Rules.Link link, int scene, Boolean romance) {
        content.removeAllViews();
        Plan.Reminder answers = TipsCatalogue.scene(data.rules, data.profile(), link.idx, scene, romance);
        if (answers == null || answers.detailZh == null) return;
        for (String paragraph : answers.detailZh.split("\n\n")) {
            boolean firstLine = true;
            for (String raw : paragraph.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                Matcher question = TipsCatalogue.QUESTION.matcher(line);
                if (question.matches()) {
                    content.addView(choicePlate(question.group(1), question.group(2)), top(firstLine ? 12 : 6));
                } else if (line.endsWith("：")) {
                    TextView head = Ui.mixed(c, line.substring(0, line.length() - 1), 14, Ui.col(c, R.color.ui_gold), true);
                    content.addView(head, top(16));
                } else if (line.startsWith("别选")) {
                    content.addView(Ui.mixed(c, line, 15, Ui.col(c, R.color.ui_gold), true), top(10));
                } else if (line.startsWith(app.p3r.dayplanner.v3.engine.DialogueHint.PREFIX)) {
                    String shown = Dialogue.shown(line);
                    if (shown != null) content.addView(Ui.mixed(c, shown, 16, Ui.col(c, R.color.p3r_white), true), top(12));
                } else {
                    TextView note = Ui.mixed(c, line, 14, Ui.col(c, R.color.p3r_ice_100), false);
                    note.setLineSpacing(0f, 1.3f);
                    content.addView(note, top(10));
                }
                firstLine = false;
            }
        }
        if (Ui.animationsEnabled()) Motion.cascade(content);
    }

    /** One answer like the game's choice box: a white slanted plate, 「第 1 问」 and the answer. */
    private View choicePlate(String number, String answer) {
        boolean free = answer.startsWith("随便选");
        LinearLayout plate = Ui.row(c);
        plate.setMinimumHeight(Ui.dp(c, 44));
        Ui.pad(plate, 16, 6, 16, 8);
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 9));
        if (free) {
            bg.fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 150)).stroke(Ui.alpha(Ui.col(c, R.color.p3r_line), 180),
                Ui.dp(c, 1));
        } else {
            bg.fill(Ui.col(c, R.color.p3r_white)).slab(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 3), Ui.dp(c, 3));
        }
        plate.setBackground(bg);
        TextView n = Ui.text(c, "第 " + number + " 问", 13,
            free ? Ui.col(c, R.color.p3r_ink_muted) : Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170), true);
        n.setMinWidth(Ui.dp(c, 52));
        plate.addView(n, Ui.wrap());
        TextView text = Ui.mixed(c, answer, 16, Ui.col(c, free ? R.color.p3r_ice_100 : R.color.p3r_navy_950), true);
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 8);
        plate.addView(text, tp);
        plate.setContentDescription("第 " + number + " 问：" + answer);
        return plate;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
