package app.p3r.dayplanner.v3.ui;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;
import app.p3r.dayplanner.v3.widget.PlanWidget;

/**
 * Launcher screen with five tabs — 今天 / 日程 / 社群 / 提示 / 我的 — on a slanted P3R tab bar, an in-app
 * bottom-sheet layer, full-screen sub-pages for 我的, and the undo bar.
 *
 * <p>Intents: {@link Intents#EXTRA_TAB} selects a tab; {@link Intents#EXTRA_OPEN_SLOT} opens the
 * detail sheet of that slot once data is loaded; {@link Intents#EXTRA_OPEN_PAGE} pushes 记录历史.
 * Each closes the sheet and sub-pages left open first. Back closes the sheet first, then
 * sub-pages, then returns to 今天, then leaves. All planner calls run through {@link Async}.
 *
 * <p>The undo / message bar is drawn above every layer (tabs, sub-pages, sheets): above the tab
 * bar normally, just above an open sheet, and at the top when it would cover the 今天 hero buttons.
 */
public final class HomeActivity extends Activity implements HomeHost, PlannerApi.Listener {
    static final int TAB_TODAY = 0;
    static final int TAB_SCHEDULE = 1;
    static final int TAB_LINKS = 2;
    static final int TAB_TIPS = 3;
    static final int TAB_COMPENDIUM = 4;
    static final int TAB_ME = 5;
    static final String[] TAB_KEYS = {"today", "schedule", "links", "tips", "compendium", "me"};
    /** Height of a sub-page's top bar (back button + title). */
    private static final int PAGE_BAR_DP = 60;
    /** Extra on result-sheet intents launched from inside the app (enables the undo bar). */
    static final String EXTRA_FROM_APP = "p3r.ui.fromApp";

    private final Deque<View> pages = new ArrayDeque<>();
    private View leavingPage;
    private final AtomicLong stateRevision = new AtomicLong(-1);
    private final AtomicLong profileRevision = new AtomicLong(-1);
    private PlannerApi api;
    private UiData data;
    private Plan lastPlan;
    private String changeSummary;
    private long bannerDismissedFor;
    /** 从头重新安排 asked for here and not answered by the service yet (shown as running meanwhile). */
    private boolean fullRunning;
    private boolean fullDeep;
    /** Ticket of the 从头重新安排 asked for here whose result is still to be shown once, 0 if none. */
    private long fullTicket;
    /** The plan shown when 从头重新安排 was asked for (what the result is compared with), or null. */
    private Plan fullBefore;
    /** Latest 从头重新安排 ticket whose result this screen has shown (it is shown once). */
    private long fullShown;
    /**
     * The plan shown before this week's changes that could not be said yet (a record's 撤销 was on
     * offer in the message bar), or null: they are said once that bar has had its time.
     */
    private Plan changeBase;
    /** A 从头重新安排 result waiting the same way, or null, and the slot its 「看看」 opens (-1: none). */
    private String pendingFull;
    private int pendingFullSlot = -1;
    /** The message bar shows what a replan changed: a newer change may take its place. */
    private boolean noticeShowing;

    private FrameLayout root;
    /** 今天 sits in the lit water near the surface; the busier tabs and sub-pages in calmer water. */
    private OceanDrawable brightWater;
    private OceanDrawable calmWater;
    private FrameLayout pageLayer;
    private SheetHost sheets;
    private UndoBar undo;
    private SlantTabBar tabBar;
    private View[] tabViews;
    private TextView loading;
    /** Shown on every tab when the records or the game data cannot be read. */
    private LinearLayout errorView;
    private TextView errorText;
    private boolean failed;
    private TodayPage today;
    private SchedulePage schedule;
    private LinksPage links;
    private TipsPage tips;
    private MePage me;
    private LinearLayout compendium;
    private View compendiumContent;
    private boolean compendiumOpened;
    private int tab = TAB_TODAY;
    private int pendingOpenSlot = -1;
    private String pendingOpenPage;
    private boolean started;
    private int topInset;
    private int bottomInset;
    /** Deferred re-placements of the message bar left for the current message (bounded). */
    private int undoRetries;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(getWindow());
        buildChrome();
        if (savedInstanceState != null) {
            tab = savedInstanceState.getInt("tab", TAB_TODAY);
            fullTicket = savedInstanceState.getLong("fullTicket", 0);
        }
        // The plan a pending 从头重新安排 is compared with, kept across a configuration change (too big
        // for the saved state; after the process is gone there is no result to show anyway).
        Object kept = getLastNonConfigurationInstance();
        if (kept instanceof Plan) {
            fullBefore = (Plan) kept;
        }
        handleIntent(getIntent());
        selectTab(tab);
        load();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra(Intents.EXTRA_TAB) || intent.hasExtra(Intents.EXTRA_OPEN_SLOT)
            || intent.hasExtra(Intents.EXTRA_OPEN_PAGE)) {
            // A widget tap or 「看记录历史」 opens what it names, not under what was left open.
            closeOverlays();
        }
        handleIntent(intent);
        selectTab(tab);
        if (data != null && pendingOpenSlot >= 0) {
            openDetail(pendingOpenSlot);
            pendingOpenSlot = -1;
        }
        if (data != null && pendingOpenPage != null) {
            openPendingPage();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (api != null) {
            api.addListener(this);
            load();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UndoCenter.Recorded recorded = UndoCenter.take();
        if (recorded != null) {
            showUndo(recorded.eventSeq, recorded.summaryZh, recorded.rankUp);
        }
    }

    @Override
    protected void onStop() {
        started = false;
        if (api != null) {
            api.removeListener(this);
        }
        super.onStop();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return fullTicket > 0 || fullRunning ? fullBefore : null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", tab);
        outState.putLong("fullTicket", fullTicket);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (sheets.dismiss()) {
            return;
        }
        if (popPage()) {
            return;
        }
        if (tab != TAB_TODAY) {
            selectTab(TAB_TODAY);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        // No UiData needed: after process death the result can arrive before the first load.
        if (resultCode != RESULT_OK || result == null || result.getData() == null) {
            return;
        }
        if (requestCode == BackupPage.REQUEST_IMPORT) {
            BackupPage.importFromUri(this, result.getData());
        } else if (requestCode == BackupPage.REQUEST_EXPORT_OLD_GUIDE) {
            BackupPage.exportOldGuideTo(this, result.getData());
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String key = intent.getStringExtra(Intents.EXTRA_TAB);
        for (int i = 0; i < TAB_KEYS.length; i++) {
            if (TAB_KEYS[i].equals(key)) {
                tab = i;
            }
        }
        if (intent.hasExtra(Intents.EXTRA_OPEN_SLOT)) {
            pendingOpenSlot = intent.getIntExtra(Intents.EXTRA_OPEN_SLOT, -1);
            intent.removeExtra(Intents.EXTRA_OPEN_SLOT);
        }
        if (intent.hasExtra(Intents.EXTRA_OPEN_PAGE)) {
            pendingOpenPage = intent.getStringExtra(Intents.EXTRA_OPEN_PAGE);
            intent.removeExtra(Intents.EXTRA_OPEN_PAGE);
        }
    }

    /** Pushes the page an intent asked for, once the data is there. */
    private void openPendingPage() {
        String page = pendingOpenPage;
        pendingOpenPage = null;
        if (Intents.PAGE_HISTORY.equals(page)) {
            meActions().openHistory();
        }
    }

    // ------------------------------------------------------------------ data

    /** Loaded bundle from the background thread. */
    private static final class Loaded {
        PlannerApi api;
        Rules rules;
        PlannerApi.Snapshot snapshot;
        long dismissedFor;
    }

    private void load() {
        Context app = getApplicationContext();
        Async.run(this, () -> {
            Loaded l = new Loaded();
            l.api = Api.get(app);
            l.rules = l.api.rules();
            l.snapshot = l.api.snapshot();
            l.dismissedFor = UiPrefs.bannerDismissedFor(app);
            return l;
        }, this::onLoaded, error -> showLoadError());
    }

    private void onLoaded(Loaded l) {
        boolean first = api == null;
        api = l.api;
        bannerDismissedFor = l.dismissedFor;
        if (first && started) {
            api.addListener(this);
        }
        apply(l.rules, l.snapshot);
    }

    @Override
    public void onChanged(PlannerApi.Snapshot snapshot) {
        if (data != null) {
            apply(data.rules, snapshot);
        }
    }

    private void apply(Rules rules, PlannerApi.Snapshot snap) {
        Zh.useNames(rules);
        if (rules == null || (snap.observed == null && snap.planErrorZh != null)) {
            // The records or the game data could not be read: a retry state on every tab instead
            // of pages that need both (and instead of the "开始设置" welcome).
            showLoadFailed(snap.planErrorZh);
            return;
        }
        if (failed) {
            failed = false;
            errorView.setVisibility(View.GONE);
            selectTab(tab);
        }
        stateRevision.set(snap.stateRevision);
        profileRevision.set(snap.profileRevision);
        Plan shownBefore = lastPlan;
        Plan plan = snap.plan;
        String planChange = null;
        int changedSlot = -1;
        if (plan != null && snap.planCurrent) {
            if (lastPlan != null && !lastPlan.planId.equals(plan.planId) && snap.observed != null) {
                // Counted from the plan before the changes not said yet, if any.
                Plan base = changeBase != null ? changeBase : lastPlan;
                changeSummary = PlanDiff.summary(rules, base, plan, snap.observed.slot);
                planChange = changeSummary;
                if (planChange == null) {
                    changeBase = null;
                } else {
                    changedSlot = PlanDiff.firstChangedSlot(base, plan, snap.observed.slot);
                }
            }
            lastPlan = plan;
        }
        if (snap.fullReplanning && snap.fullReplanTicket > Math.max(fullTicket, fullShown)) {
            // Running, but this screen never got its ticket (asked for before the screen was made
            // again, or by the screen the player backed out of; only this app asks): its result is
            // shown here, once, against the plan installed now.
            fullTicket = snap.fullReplanTicket;
            if (fullBefore == null) {
                fullBefore = plan;
            }
        }
        String fullResult = null;
        int fullSlot = -1;
        if (fullTicket > 0 && snap.fullReplanDoneTicket >= fullTicket) {
            // The 从头重新安排 asked for here has ended: say how, once.
            Plan before = fullBefore != null ? fullBefore : shownBefore;
            if (before != null && plan != null && before.planId != null && before.planId.equals(plan.planId)
                && snap.fullReplanResult == PlannerApi.FullReplanResult.CHANGED) {
                // Read in the instant the new plan was in and the request not yet ended: not the plan before.
                before = null;
            }
            int from = snap.observed == null ? 0 : snap.observed.slot;
            fullResult = PlanDiff.fullSummary(rules, before, plan, from, snap.fullReplanResult, snap.fullReplanDeep);
            fullSlot = snap.fullReplanResult == PlannerApi.FullReplanResult.CHANGED
                ? PlanDiff.firstChangedSlot(before, plan, from) : -1;
            fullShown = Math.max(fullShown, snap.fullReplanDoneTicket);
            fullTicket = 0;
            fullBefore = null;
            if (fullResult != null) {
                changeSummary = fullResult;
            }
        } else if (fullRunning && !snap.fullReplanning) {
            // Asked for, but the request has not reached the service yet: it is still running.
            snap = copyOf(snap);
            snap.fullReplanning = true;
            snap.fullReplanDeep = fullDeep;
        }
        boolean banner = UiData.returnBannerDue(rules, snap, System.currentTimeMillis(), bannerDismissedFor);
        data = new UiData(rules, snap, changeSummary, banner, System.currentTimeMillis());
        loading.setVisibility(View.GONE);
        bindPages();
        // Said once in the message bar, never over a record's 撤销 (or a 重试): then once that bar
        // has had its time (sayPendingChange). A message without either simply gives way.
        boolean say = !undo.hasAction() || noticeShowing;
        if (fullResult != null) {
            changeBase = null;
            pendingFull = say ? null : fullResult;
            pendingFullSlot = fullSlot;
            if (say) {
                showNotice(fullResult, fullSlot);
            }
        } else if (planChange != null) {
            // An ordinary replan that moved something this week.
            if (say) {
                changeBase = null;
                showNotice(planChange, changedSlot);
            } else if (changeBase == null) {
                changeBase = shownBefore;
            }
        }
        if (pendingOpenSlot >= 0) {
            int slot = pendingOpenSlot;
            pendingOpenSlot = -1;
            openDetail(slot);
        }
        if (pendingOpenPage != null) {
            openPendingPage();
        }
    }

    private void bindPages() {
        today.bind(data);
        schedule.bind(data);
        links.bind(data);
        tips.bind(data);
        me.bind(data);
        if (undo.isShowing()) {
            // The hero card may have moved: re-check that the bar does not cover its buttons.
            root.post(this::placeUndo);
        }
    }

    private void showLoadError() {
        showLoadFailed(null);
    }

    /**
     * Replaces every tab with 「记录没读出来」, the service's Chinese reason when it adds something,
     * and [重试] (the database or the rule pack failed to open, or loading threw).
     */
    private void showLoadFailed(String errorZh) {
        failed = true;
        data = null;
        loading.setVisibility(View.GONE);
        for (View v : tabViews) {
            v.setVisibility(View.GONE);
        }
        String reason = Messages.loadFailed(errorZh);
        errorText.setText(reason);
        errorText.setVisibility(reason.isEmpty() ? View.GONE : View.VISIBLE);
        errorView.setVisibility(View.VISIBLE);
    }

    private void retryLoad() {
        // Asks the service to open the rule pack / database again before re-reading.
        requestReplan();
        errorView.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        loading.setText("正在读取记录…");
        load();
    }

    /** True while the retry state is shown (tests). */
    boolean showingLoadError() {
        return failed && errorView.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------------ chrome

    private void buildChrome() {
        root = new FrameLayout(this);
        brightWater = OceanDrawable.page(this);
        calmWater = OceanDrawable.calm(this);
        root.setBackground(brightWater);
        LinearLayout column = Ui.column(this);
        FrameLayout contentFrame = new FrameLayout(this);
        today = new TodayPage(this);
        schedule = new SchedulePage(this);
        links = new LinksPage(this);
        tips = new TipsPage(this);
        me = new MePage(this, meActions());
        compendium = Ui.column(this);
        View compendiumTitle = Ui.pageHeader(this, "人格面具全书", "COMPENDIUM", CompendiumPage.dlcPlate(this));
        Ui.pad(compendiumTitle, Ui.GUTTER, 12, Ui.GUTTER, 4);
        compendium.addView(compendiumTitle);
        tabViews = new View[]{today.view(), schedule.view(), links.view(), tips.view(), compendium, me.view()};
        for (View v : tabViews) {
            contentFrame.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        }
        loading = Ui.muted(this, "读取中…");
        loading.setGravity(Gravity.CENTER);
        contentFrame.addView(loading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        errorView = Ui.column(this);
        errorView.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(errorView, Ui.GUTTER + 8, 0, Ui.GUTTER + 8, 0);
        errorView.addView(Ui.title(this, "记录没读出来"));
        errorText = Ui.body(this, "");
        errorText.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams textParams = Ui.matchWrap();
        textParams.topMargin = Ui.dp(this, 8);
        errorView.addView(errorText, textParams);
        TextView retry = Ui.primary(this, "重试");
        retry.setOnClickListener(v -> retryLoad());
        LinearLayout.LayoutParams retryParams = Ui.matchWrap();
        retryParams.topMargin = Ui.dp(this, 24);
        errorView.addView(retry, retryParams);
        errorView.setVisibility(View.GONE);
        contentFrame.addView(errorView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        column.addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        tabBar = new SlantTabBar(this, new String[]{"今天", "日程", "社群", "提示", "全书", "我的"}, new int[]{
            R.drawable.ui_ic_tab_today, R.drawable.ui_ic_tab_schedule, R.drawable.ui_ic_tab_links,
            R.drawable.ui_ic_tab_tips, R.drawable.ui_ic_mask, R.drawable.ui_ic_tab_me});
        tabBar.setListener(this::selectTab);
        column.addView(tabBar, Ui.matchWrap());
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        pageLayer = new FrameLayout(this);
        root.addView(pageLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        sheets = new SheetHost(this);
        root.addView(sheets, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        undo = new UndoBar(this);
        undo.setOnTimeout(this::sayPendingChange);
        FrameLayout.LayoutParams undoParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int m = Ui.dp(this, 12);
        undoParams.setMargins(m, 0, m, Ui.dp(this, 76));
        root.addView(undo, undoParams);
        setContentView(root);
        EdgeToEdge.listen(root, (left, top, right, bottom, ime) -> {
            topInset = top;
            bottomInset = bottom;
            column.setPadding(left, top, right, 0);
            tabBar.setPadding(tabBar.getPaddingLeft(), Ui.dp(this, 6), tabBar.getPaddingRight(), bottom + Ui.dp(this, 6));
            sheets.setBottomInset(bottom + ime);
            for (View page : pages) {
                page.setPadding(left, top, right, 0);
            }
            placeUndo();
        });
    }

    /**
     * Puts the undo / message bar on top of every layer: above the tab bar on the tabs, near the
     * bottom edge on sub-pages, just above an open sheet (never over the sheet's buttons; at the
     * top when the sheet leaves no room) and at the top on 今天 when it would cover the hero
     * card's buttons.
     */
    void placeUndo() {
        if (!undo.isShowing()) {
            return;
        }
        int m = Ui.dp(this, 12);
        int width = root.getWidth() > 0 ? root.getWidth() - 2 * m : getResources().getDisplayMetrics().widthPixels - 2 * m;
        undo.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int barHeight = undo.getMeasuredHeight();
        boolean atTop;
        int bottomMargin;
        int topMargin = topInset + (pages.isEmpty() ? m : Ui.dp(this, PAGE_BAR_DP));
        if (sheets.isShowing()) {
            // Just above the sheet (over the dimmed area) so it never covers the sheet's buttons;
            // at the top when the sheet is too tall to leave room.
            int panelTop = sheets.panelTop();
            int barBottom = panelTop - m;
            atTop = panelTop < 0 || barBottom - barHeight < topMargin;
            bottomMargin = atTop ? 0 : root.getHeight() - barBottom;
            if (panelTop < 0 && undoRetries < 2) {
                // The sheet was just shown: place the bar again once it is laid out.
                undoRetries++;
                root.post(this::placeUndo);
            }
        } else if (!pages.isEmpty()) {
            atTop = false;
            bottomMargin = bottomInset + m;
        } else {
            int tabHeight = tabBar.getHeight() > 0 ? tabBar.getHeight() : Ui.dp(this, 64) + bottomInset;
            bottomMargin = tabHeight + m;
            atTop = tab == TAB_TODAY && coversHeroButtons(bottomMargin, barHeight, m);
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) undo.getLayoutParams();
        p.gravity = atTop ? Gravity.TOP : Gravity.BOTTOM;
        p.topMargin = atTop ? topMargin : 0;
        p.bottomMargin = atTop ? 0 : bottomMargin;
        p.leftMargin = m;
        p.rightMargin = m;
        undo.setLayoutParams(p);
        undo.bringToFront();
    }

    /** True when the bar at the bottom would cover the hero buttons and at the top would not. */
    private boolean coversHeroButtons(int bottomMargin, int barHeight, int m) {
        View buttons = today.heroButtons();
        Rect r = buttons == null || !buttons.isShown() ? null : boundsIn(buttons, root);
        if (r == null || root.getHeight() <= 0) {
            return false;
        }
        int gap = Ui.dp(this, 8);
        int barBottom = root.getHeight() - bottomMargin;
        boolean coversBottom = r.bottom + gap > barBottom - barHeight && r.top - gap < barBottom;
        int topY = topInset + m;
        boolean coversTop = r.bottom + gap > topY && r.top - gap < topY + barHeight;
        return coversBottom && !coversTop;
    }

    /** Bounds of {@code view} in {@code ancestor}'s coordinates (scrolling included), or null. */
    static Rect boundsIn(View view, View ancestor) {
        int x = 0;
        int y = 0;
        View v = view;
        while (v != ancestor) {
            x += v.getLeft() + Math.round(v.getTranslationX());
            y += v.getTop() + Math.round(v.getTranslationY());
            ViewParent parent = v.getParent();
            if (!(parent instanceof View)) {
                return null;
            }
            View p = (View) parent;
            x -= p.getScrollX();
            y -= p.getScrollY();
            v = p;
        }
        return new Rect(x, y, x + view.getWidth(), y + view.getHeight());
    }

    @Override
    public void selectTab(int index) {
        tab = Math.max(0, Math.min(TAB_KEYS.length - 1, index));
        if (tab == TAB_COMPENDIUM && !failed && !compendiumOpened) {
            compendiumOpened = true;
            compendiumContent = CompendiumPage.create(this);
            compendium.addView(compendiumContent,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else if (tab == TAB_COMPENDIUM && !failed) {
            CompendiumPage.refresh(compendiumContent);
        }
        int previous = tabBar.getSelectedTab();
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setVisibility(i == tab && !failed ? View.VISIBLE : View.GONE);
        }
        if (previous >= 0 && previous != tab && !failed) {
            Motion.tabIn(tabViews[tab], tab > previous);
        }
        tabBar.setSelectedTab(tab);
        root.setBackground(tab == TAB_TODAY || failed ? brightWater : calmWater);
        placeUndo();
    }

    int currentTab() {
        return tab;
    }

    // ------------------------------------------------------------------ HomeHost

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public UiData data() {
        return data;
    }

    @Override
    public void openDetail(int slot) {
        if (data == null) {
            pendingOpenSlot = slot;
            return;
        }
        showSheet(DayDetailSheet.build(this, slot));
    }

    @Override
    public void openResult(int slot, String actionKey, String mode) {
        Intent intent = new Intent(this, ResultActivity.class);
        intent.putExtra(Intents.EXTRA_SLOT, slot);
        intent.putExtra(Intents.EXTRA_ACTION, actionKey);
        intent.putExtra(Intents.EXTRA_MODE, mode);
        if (data != null && data.plan() != null) {
            intent.putExtra(Intents.EXTRA_PLAN_ID, data.plan().planId);
        }
        if (data != null) {
            intent.putExtra(Intents.EXTRA_STATE_REV, data.snap.stateRevision);
        }
        intent.putExtra(EXTRA_FROM_APP, true);
        startActivity(intent);
    }

    @Override
    public void openLink(int link) {
        selectTab(TAB_LINKS);
        links.openDetail(link);
    }

    @Override
    public void openState(String mode) {
        Intent intent = new Intent(this, StateActivity.class);
        intent.putExtra(Intents.EXTRA_STATE_MODE, mode);
        startActivity(intent);
    }

    @Override
    public void commitDirect(Plan.Step step) {
        if (data == null || api == null) {
            return;
        }
        if ("locked".equals(step.kind) && step.slot < data.currentSlot()) {
            if (data.snap.storyDisplay == null || data.snap.storyDisplay.cursorSlot != step.slot) {
                reload();
                return;
            }
            String source = data.snap.storyDisplay.key;
            PlannerApi target = api;
            Async.run(this, () -> target.acknowledgeStory(source, step.slot), changed -> reload(),
                    error -> showError(Messages.ACTION_FAILED));
            return;
        }
        // A lagging plan's visible predictions (stat Lv) are left out: the engine works the result
        // out from the latest record instead.
        Outcome outcome = Outcomes.asPredicted(step, data.snap.planCurrent);
        String summary = Outcomes.summary(data.rules, step, outcome);
        String planId = data.plan() == null ? "" : data.plan().planId;
        PlannerApi target = api;
        Async.run(this, () -> {
            long rev = stateRevision.get();
            String id = CommandIds.of("result", rev, planId, step.slot, step.action, ResultActivity.outcomeKey(outcome));
            return target.recordOutcome(id, rev, step.slot, step.action, outcome);
        }, receipt -> {
            if (receipt.ok) {
                stateRevision.set(receipt.stateRevision);
                showUndo(receipt.eventSeq, summary, StepKind.of(step) == StepKind.SOCIAL_RANK_UP);
            } else {
                showError(Messages.receiptError(receipt));
            }
            reload();
        }, error -> showError(Messages.GENERIC_ERROR));
    }

    /**
     * "已记录：… · 撤销" (a correction keeps its own 「已修改：…」); {@code rankUp}, decided where the
     * outcome is known, adds the 「RANK UP」 badge.
     */
    void showUndo(long eventSeq, String summary, boolean rankUp) {
        boolean fix = summary != null && summary.startsWith(FIXED);
        undo.show(fix ? summary : "已记录：" + summary, eventSeq >= 0 ? "撤销" : null, () -> undoEvent(eventSeq, summary),
            rankUp && !fix ? UndoBar.Style.RANK_UP : UndoBar.Style.RECORD);
        noticeShowing = false;
        undoRetries = 0;
        placeUndo();
    }

    /** How the result sheet starts the summary of a corrected record. */
    private static final String FIXED = "已修改：";

    private void undoEvent(long eventSeq, String summary) {
        if (api == null) {
            return;
        }
        PlannerApi target = api;
        // A stale revision (a record from the widget meanwhile) or a failed write: 重试 tries again
        // with what reload() reads.
        Runnable retry = () -> undoEvent(eventSeq, summary);
        Async.run(this, () -> {
            long rev = stateRevision.get();
            return target.voidEvent(CommandIds.of("void", rev, eventSeq), rev, eventSeq);
        }, receipt -> {
            if (receipt.ok) {
                boolean fix = summary != null && summary.startsWith(FIXED);
                showMessage(fix ? "已撤销修改：" + summary.substring(FIXED.length()) : "已撤销：" + summary);
            } else if (Messages.isStale(receipt)) {
                showError(Messages.ACTION_FAILED, "重试", retry);
            } else {
                showError(Messages.receiptError(receipt));
            }
            reload();
        }, error -> showError(Messages.ACTION_FAILED, "重试", retry));
    }

    @Override
    public void pushPage(String title, View content) {
        pushPage(title, content, false);
    }

    @Override
    public void pushFillingPage(String title, View content) {
        pushPage(title, content, true);
    }

    @Override
    public void replaceFillingPage(String title, View content) {
        finishPageExit();
        View previous = pages.poll();
        pushPage(title, content, true);
        if (previous == null) return;
        if (Ui.animationsEnabled()) {
            previous.animate().cancel();
            previous.animate().alpha(0f).setDuration(160).withEndAction(() -> pageLayer.removeView(previous)).start();
        } else {
            pageLayer.removeView(previous);
        }
    }

    @Override
    public void returnToPage(View rootContent) {
        View destination = null;
        for (View candidate : pages) {
            View current = rootContent;
            while (current != null) {
                if (current == candidate) { destination = candidate; break; }
                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            if (destination != null) break;
        }
        boolean tabWorkspace = false;
        if (destination == null) {
            View current = rootContent;
            while (current != null) {
                if (current == compendium) { tabWorkspace = true; break; }
                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
        }
        if (destination == null && !tabWorkspace) return;
        if (pages.peek() == destination) {
            if (tabWorkspace) selectTab(TAB_COMPENDIUM);
            return;
        }
        finishPageExit();
        View top = pages.poll();
        while (pages.peek() != destination) {
            View skipped = pages.poll();
            skipped.animate().cancel();
            pageLayer.removeView(skipped);
        }
        animatePageExit(top);
        if (tabWorkspace) selectTab(TAB_COMPENDIUM);
    }

    private void pushPage(String title, View content, boolean fillsPage) {
        finishPageExit();
        LinearLayout page = Ui.column(this);
        page.setBackground(OceanDrawable.calm(this));
        page.setClickable(true);
        page.setPadding(0, topInset, 0, 0);
        LinearLayout bar = Ui.subPageBar(this, title, v -> popPage());
        page.addView(bar, Ui.matchWrap());
        if (fillsPage) {
            FrameLayout holder = new FrameLayout(this);
            holder.setPadding(0, 0, 0, bottomInset);
            holder.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
            page.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setClipToPadding(false);
            scroll.setVerticalScrollBarEnabled(false);
            FrameLayout holder = new FrameLayout(this);
            Ui.pad(holder, Ui.GUTTER, 8, Ui.GUTTER, 32);
            holder.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.addView(holder, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.setPadding(0, 0, 0, bottomInset);
            page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        pageLayer.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        pages.push(page);
        placeUndo();
        if (Ui.animationsEnabled()) {
            page.setTranslationX(Ui.dp(this, 32));
            page.setAlpha(0f);
            page.animate().translationX(0).alpha(1f).setDuration(200)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        }
    }

    @Override
    public boolean popPage() {
        if (leavingPage != null) return true;
        View page = pages.poll();
        if (page == null) {
            return false;
        }
        animatePageExit(page);
        return true;
    }

    private void animatePageExit(View page) {
        page.animate().cancel();
        if (Ui.animationsEnabled()) {
            leavingPage = page;
            page.animate().translationX(Ui.dp(this, 32)).alpha(0f).setDuration(180)
                .setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction(() -> {
                    if (leavingPage == page) finishPageExit();
                }).start();
        } else {
            pageLayer.removeView(page);
        }
        placeUndo();
    }

    private void finishPageExit() {
        if (leavingPage == null) return;
        View page = leavingPage;
        leavingPage = null;
        page.animate().withEndAction(null).cancel();
        pageLayer.removeView(page);
    }

    /** Number of open sub-pages (tests). */
    int pageDepth() {
        return pages.size();
    }

    @Override
    public void showSheet(View content) {
        showSheet(content, null);
    }

    @Override
    public void showSheet(View content, Runnable onDismiss) {
        sheets.show(content, () -> {
            placeUndo();
            if (onDismiss != null) {
                onDismiss.run();
            }
        });
        undoRetries = 0;
        placeUndo();
    }

    @Override
    public void dismissSheet() {
        sheets.dismiss();
    }

    SheetHost sheets() {
        return sheets;
    }

    UndoBar undoBar() {
        return undo;
    }

    @Override
    public void saveProfile(Profile profile) {
        if (data == null || api == null) {
            return;
        }
        PlannerApi.Snapshot optimistic = copyOf(data.snap);
        optimistic.profile = profile;
        data = new UiData(data.rules, optimistic, data.changeSummary, data.returnBanner, data.nowMillis);
        bindPages();
        PlannerApi target = api;
        Async.run(this, () -> {
            long rev = profileRevision.get();
            String id = CommandIds.of("profile", rev, profile.toJson().toString());
            PlannerApi.Receipt receipt = target.saveProfile(id, rev, profile);
            if (receipt.ok) {
                profileRevision.set(receipt.profileRevision);
            }
            return receipt;
        }, receipt -> {
            if (!receipt.ok) {
                showError(Messages.receiptError(receipt));
                reload();
            }
        }, error -> {
            showError(Messages.GENERIC_ERROR);
            reload();
        });
    }

    @Override
    public void saveCheckpoint(SimState state, Profile profile, String doneZh) {
        if (data == null || api == null) {
            return;
        }
        PlannerApi.Snapshot optimistic = copyOf(data.snap);
        optimistic.observed = state;
        if (profile != null) {
            optimistic.profile = profile;
        }
        data = new UiData(data.rules, optimistic, data.changeSummary, data.returnBanner, data.nowMillis);
        bindPages();
        PlannerApi target = api;
        Async.run(this, () -> {
            long rev = stateRevision.get();
            String profileJson = profile == null ? "" : profile.toJson().toString();
            String id = CommandIds.of("checkpoint", rev, state.toJson().toString(), profileJson);
            PlannerApi.Receipt receipt = target.saveCheckpoint(id, rev, state, profile);
            if (receipt.ok) {
                stateRevision.set(receipt.stateRevision);
                profileRevision.set(receipt.profileRevision);
            }
            return receipt;
        }, receipt -> {
            if (receipt.ok) showMessage(doneZh); else showError(Messages.receiptError(receipt));
            reload();
        }, error -> {
            showError(Messages.GENERIC_ERROR);
            reload();
        });
    }

    private static PlannerApi.Snapshot copyOf(PlannerApi.Snapshot s) {
        PlannerApi.Snapshot c = new PlannerApi.Snapshot();
        c.observed = s.observed;
        c.storyDisplay = s.storyDisplay;
        c.profile = s.profile;
        c.plan = s.plan;
        c.stateRevision = s.stateRevision;
        c.profileRevision = s.profileRevision;
        c.planCurrent = s.planCurrent;
        c.planning = s.planning;
        c.planErrorZh = s.planErrorZh;
        c.noticeZh = s.noticeZh;
        c.lastRecordMillis = s.lastRecordMillis;
        c.eventsSinceCheckpoint = s.eventsSinceCheckpoint;
        c.fullReplanning = s.fullReplanning;
        c.fullReplanDeep = s.fullReplanDeep;
        c.fullReplanTicket = s.fullReplanTicket;
        c.fullReplanDoneTicket = s.fullReplanDoneTicket;
        c.fullReplanResult = s.fullReplanResult;
        return c;
    }

    @Override
    public void showMessage(String text) {
        undo.show(text, null, null);
        noticeShowing = false;
        undoRetries = 0;
        placeUndo();
    }

    @Override
    public void showError(String text) {
        showError(text, null, null);
    }

    /** {@link #showError(String)} with the fix as a button ({@code actionLabel}, 「重试」), or none. */
    void showError(String text, String actionLabel, Runnable onAction) {
        undo.show(text, actionLabel, onAction, UndoBar.Style.ERROR);
        noticeShowing = false;
        undoRetries = 0;
        placeUndo();
    }

    /**
     * What a replan changed (「已重新安排：这周 3 处有变」), with 「看看」 → 日程 at the day of
     * {@code changedSlot} when there is one (-1: no button).
     */
    private void showNotice(String text, int changedSlot) {
        Runnable look = changedSlot < 0 ? null : () -> {
            closeOverlays();
            selectTab(TAB_SCHEDULE);
            schedule.showSlot(changedSlot);
        };
        undo.show(text, look == null ? null : "看看", look);
        noticeShowing = true;
        undoRetries = 0;
        placeUndo();
        // The pages were just rebuilt for the new plan: check the hero's buttons again once laid out.
        root.post(this::placeUndo);
    }

    /**
     * The message bar has had its time: a replan result that waited for a record's 撤销 to go is
     * said now (this week's changes counted from the plan before them).
     */
    private void sayPendingChange() {
        if (pendingFull != null) {
            String text = pendingFull;
            pendingFull = null;
            changeBase = null;
            showNotice(text, pendingFullSlot);
            return;
        }
        if (changeBase == null || data == null || lastPlan == null || data.observed() == null) {
            return;
        }
        int from = data.observed().slot;
        String text = PlanDiff.summary(data.rules, changeBase, lastPlan, from);
        int slot = PlanDiff.firstChangedSlot(changeBase, lastPlan, from);
        changeBase = null;
        if (text != null) {
            showNotice(text, slot);
        }
    }

    /** Closes the sheet and every sub-page at once. */
    private void closeOverlays() {
        sheets.dismiss();
        finishPageExit();
        for (View page; (page = pages.poll()) != null; ) {
            page.animate().cancel();
            pageLayer.removeView(page);
        }
        placeUndo();
    }

    @Override
    public void requestReplan() {
        if (api == null) {
            return;
        }
        PlannerApi target = api;
        Async.run(this, target::requestReplan);
    }

    @Override
    public void requestFullReplan() {
        requestManualReplan(false);
    }

    @Override
    public void requestDeepReplan() {
        requestManualReplan(true);
    }

    private void requestManualReplan(boolean deep) {
        if (data == null || api == null || fullRunning || data.snap.fullReplanning) {
            return;
        }
        // Optimistic: the entries and the 今天 status line show it running right away.
        fullRunning = true;
        fullDeep = deep;
        fullBefore = data.plan();
        PlannerApi.Snapshot optimistic = copyOf(data.snap);
        optimistic.fullReplanning = true;
        optimistic.fullReplanDeep = deep;
        data = new UiData(data.rules, optimistic, data.changeSummary, data.returnBanner, data.nowMillis);
        bindPages();
        PlannerApi target = api;
        Async.run(this, () -> deep ? target.requestDeepReplan() : target.requestFullReplan(), ticket -> {
            fullRunning = false;
            // Already taken from a snapshot (and maybe shown) before this answer came: shown once.
            if (ticket > fullShown) {
                fullTicket = ticket;
            }
            // Also drops optimistic leftovers (e.g. a profile) for what the service re-read.
            load();
        }, error -> {
            fullRunning = false;
            fullBefore = null;
            showError(Messages.ACTION_FAILED);
            load();
        });
    }

    @Override
    public void cancelFullReplan() {
        if (api == null) {
            return;
        }
        PlannerApi target = api;
        Async.run(this, () -> {
            target.cancelFullReplan();
            return Boolean.TRUE;
        }, done -> load(), error -> showError(Messages.ACTION_FAILED));
    }

    @Override
    public void dismissReturnBanner() {
        if (data == null) {
            return;
        }
        bannerDismissedFor = data.snap.lastRecordMillis;
        UiPrefs.setBannerDismissedFor(this, bannerDismissedFor);
        data = new UiData(data.rules, data.snap, data.changeSummary, false, data.nowMillis);
        today.bind(data);
    }

    @Override
    public void reload() {
        load();
    }

    // ------------------------------------------------------------------ 我的 actions

    private MePage.Actions meActions() {
        return new MePage.Actions() {
            @Override
            public void openSettings() {
                pushPage("规划设置", SettingsPage.build(HomeActivity.this));
            }

            @Override
            public void openHistory() {
                pushPage("记录历史", HistoryPage.build(HomeActivity.this));
            }

            @Override
            public void openBackup() {
                pushPage("备份与恢复", BackupPage.build(HomeActivity.this));
            }

            @Override
            public void openExplain() {
                pushPage("规划说明", InfoPages.explain(HomeActivity.this));
            }

            @Override
            public void addWidget() {
                requestWidget();
            }

            @Override
            public void openAbout() {
                pushPage("关于", InfoPages.about(HomeActivity.this));
            }
        };
    }

    /** The 添加桌面小组件 sheet on a launcher that cannot pin a widget for the app. */
    static final String WIDGET_BY_HAND = "回到桌面，长按空白处 → 小组件 → 「P3R 每日安排」，拖出来就好。";

    private void requestWidget() {
        AppWidgetManager manager = getSystemService(AppWidgetManager.class);
        ComponentName provider = new ComponentName(this, PlanWidget.class);
        boolean requested = false;
        if (manager != null && manager.isRequestPinAppWidgetSupported()) {
            requested = manager.requestPinAppWidget(provider, null, null);
        }
        if (!requested) {
            LinearLayout sheet = Ui.column(this);
            Ui.pad(sheet, 20, 12, 20, 20);
            sheet.addView(Ui.title(this, "添加桌面小组件"));
            TextView how = Ui.body(this, WIDGET_BY_HAND);
            how.setLineSpacing(0f, 1.3f);
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(this, 8);
            sheet.addView(how, p);
            TextView ok = Ui.primary(this, "好了");
            ok.setOnClickListener(v -> dismissSheet());
            LinearLayout.LayoutParams okParams = Ui.matchWrap();
            okParams.topMargin = Ui.dp(this, 20);
            sheet.addView(ok, okParams);
            showSheet(sheet);
        }
    }
}
