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
 * detail sheet of that slot once data is loaded. Back closes the sheet first, then sub-pages,
 * then returns to 今天, then leaves. All planner calls run through {@link Async}.
 *
 * <p>The undo / message bar is drawn above every layer (tabs, sub-pages, sheets): above the tab
 * bar normally, just above an open sheet, and at the top when it would cover the 今天 hero buttons.
 */
public final class HomeActivity extends Activity implements HomeHost, PlannerApi.Listener {
    static final int TAB_TODAY = 0;
    static final int TAB_SCHEDULE = 1;
    static final int TAB_LINKS = 2;
    static final int TAB_TIPS = 3;
    static final int TAB_ME = 4;
    static final String[] TAB_KEYS = {"today", "schedule", "links", "tips", "me"};
    /** Height of a sub-page's top bar (back button + title). */
    private static final int PAGE_BAR_DP = 60;
    /** Extra on result-sheet intents launched from inside the app (enables the undo bar). */
    static final String EXTRA_FROM_APP = "p3r.ui.fromApp";

    private final Deque<View> pages = new ArrayDeque<>();
    private final AtomicLong stateRevision = new AtomicLong(-1);
    private final AtomicLong profileRevision = new AtomicLong(-1);
    private PlannerApi api;
    private UiData data;
    private Plan lastPlan;
    private String changeSummary;
    private long bannerDismissedFor;
    /** 从头重新安排 asked for here and not answered by the service yet (shown as running meanwhile). */
    private boolean fullRunning;
    /** Ticket of the 从头重新安排 asked for here whose result is still to be shown once, 0 if none. */
    private long fullTicket;
    /** The plan shown when 从头重新安排 was asked for (what the result is compared with), or null. */
    private Plan fullBefore;
    /** Latest 从头重新安排 ticket whose result this screen has shown (it is shown once). */
    private long fullShown;

    private FrameLayout root;
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
    private int tab = TAB_TODAY;
    private int pendingOpenSlot = -1;
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
        handleIntent(intent);
        selectTab(tab);
        if (data != null && pendingOpenSlot >= 0) {
            openDetail(pendingOpenSlot);
            pendingOpenSlot = -1;
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
            showUndo(recorded.eventSeq, recorded.summaryZh);
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
        if (plan != null && snap.planCurrent) {
            if (lastPlan != null && !lastPlan.planId.equals(plan.planId) && snap.observed != null) {
                changeSummary = PlanDiff.summary(rules, lastPlan, plan, snap.observed.slot);
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
        if (fullTicket > 0 && snap.fullReplanDoneTicket >= fullTicket) {
            // The 从头重新安排 asked for here has ended: say how, once.
            Plan before = fullBefore != null ? fullBefore : shownBefore;
            if (before != null && plan != null && before.planId != null && before.planId.equals(plan.planId)
                && snap.fullReplanResult == PlannerApi.FullReplanResult.CHANGED) {
                // Read in the instant the new plan was in and the request not yet ended: not the plan before.
                before = null;
            }
            fullResult = PlanDiff.fullSummary(rules, before, plan, snap.observed == null ? 0 : snap.observed.slot,
                snap.fullReplanResult);
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
        }
        boolean banner = UiData.returnBannerDue(snap, System.currentTimeMillis(), bannerDismissedFor);
        data = new UiData(rules, snap, changeSummary, banner, System.currentTimeMillis());
        loading.setVisibility(View.GONE);
        bindPages();
        if (fullResult != null && !undo.hasAction()) {
            // Said once in the message bar, unless a record made during the run still offers its 撤销
            // there (the 今天 status line says it as well, changeSummary).
            showMessage(fullResult);
        }
        if (pendingOpenSlot >= 0) {
            int slot = pendingOpenSlot;
            pendingOpenSlot = -1;
            openDetail(slot);
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
     * Replaces every tab with "暂时读不到记录", the service's Chinese message and [重试] (the
     * database or the rule pack failed to open, or loading threw).
     */
    private void showLoadFailed(String errorZh) {
        failed = true;
        data = null;
        loading.setVisibility(View.GONE);
        for (View v : tabViews) {
            v.setVisibility(View.GONE);
        }
        errorText.setText(Messages.loadFailed(errorZh));
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
        root.setBackground(new OceanDrawable(Ui.col(this, R.color.p3r_navy_800), Ui.col(this, R.color.p3r_navy_900),
            Ui.col(this, R.color.p3r_navy_950), Ui.col(this, R.color.p3r_royal_500), Ui.col(this, R.color.p3r_cyan_300)));
        LinearLayout column = Ui.column(this);
        FrameLayout contentFrame = new FrameLayout(this);
        today = new TodayPage(this);
        schedule = new SchedulePage(this);
        links = new LinksPage(this);
        tips = new TipsPage(this);
        me = new MePage(this, meActions());
        tabViews = new View[]{today.view(), schedule.view(), links.view(), tips.view(), me.view()};
        for (View v : tabViews) {
            contentFrame.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        }
        loading = Ui.muted(this, "正在读取记录…");
        loading.setGravity(Gravity.CENTER);
        contentFrame.addView(loading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        errorView = Ui.column(this);
        errorView.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(errorView, Ui.GUTTER + 8, 0, Ui.GUTTER + 8, 0);
        errorView.addView(Ui.title(this, "暂时读不到记录"));
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
        tabBar = new SlantTabBar(this, new String[]{"今天", "日程", "社群", "提示", "我的"}, new int[]{
            R.drawable.ui_ic_tab_today, R.drawable.ui_ic_tab_schedule, R.drawable.ui_ic_tab_links,
            R.drawable.ui_ic_tab_tips, R.drawable.ui_ic_tab_me});
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
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setVisibility(i == tab && !failed ? View.VISIBLE : View.GONE);
        }
        tabBar.setSelectedTab(tab);
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
                showUndo(receipt.eventSeq, summary);
            } else {
                showMessage(Messages.receiptError(receipt));
            }
            reload();
        }, error -> showMessage(Messages.GENERIC_ERROR));
    }

    /** "已记录：… · 撤销". */
    void showUndo(long eventSeq, String summary) {
        undo.show("已记录：" + summary, eventSeq >= 0 ? "撤销" : null, () -> undoEvent(eventSeq, summary));
        undoRetries = 0;
        placeUndo();
    }

    private void undoEvent(long eventSeq, String summary) {
        if (api == null) {
            return;
        }
        PlannerApi target = api;
        Async.run(this, () -> {
            long rev = stateRevision.get();
            return target.voidEvent(CommandIds.of("void", rev, eventSeq), rev, eventSeq);
        }, receipt -> {
            showMessage(receipt.ok ? "已撤销：" + summary : Messages.receiptError(receipt));
            reload();
        }, error -> showMessage(Messages.GENERIC_ERROR));
    }

    @Override
    public void pushPage(String title, View content) {
        LinearLayout page = Ui.column(this);
        page.setBackground(new OceanDrawable(Ui.col(this, R.color.p3r_navy_800), Ui.col(this, R.color.p3r_navy_900),
            Ui.col(this, R.color.p3r_navy_950), Ui.col(this, R.color.p3r_royal_500), Ui.col(this, R.color.p3r_cyan_300)));
        page.setClickable(true);
        page.setPadding(0, topInset, 0, 0);
        LinearLayout bar = Ui.row(this);
        Ui.pad(bar, 4, 4, Ui.GUTTER, 4);
        ImageView back = new ImageView(this);
        back.setImageDrawable(Ui.tinted(this, R.drawable.ui_ic_back, Ui.col(this, R.color.p3r_white)));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("返回");
        back.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(this, R.color.ui_cyan_soft)));
        back.setOnClickListener(v -> popPage());
        int size = Ui.dp(this, Ui.TOUCH);
        bar.addView(back, new LinearLayout.LayoutParams(size, size));
        TextView heading = Ui.text(this, title, 22, Ui.col(this, R.color.p3r_white), true);
        LinearLayout.LayoutParams hp = Ui.weight(1);
        hp.leftMargin = Ui.dp(this, 4);
        bar.addView(heading, hp);
        page.addView(bar, Ui.matchWrap());
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
        pageLayer.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        pages.push(page);
        placeUndo();
        if (Ui.animationsEnabled()) {
            page.setTranslationX(Ui.dp(this, 48));
            page.setAlpha(0f);
            page.animate().translationX(0).alpha(1f).setDuration(180).start();
        }
    }

    @Override
    public boolean popPage() {
        View page = pages.poll();
        if (page == null) {
            return false;
        }
        pageLayer.removeView(page);
        placeUndo();
        return true;
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
                showMessage(Messages.receiptError(receipt));
                reload();
            }
        }, error -> {
            showMessage(Messages.GENERIC_ERROR);
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
            showMessage(receipt.ok ? doneZh : Messages.receiptError(receipt));
            reload();
        }, error -> {
            showMessage(Messages.GENERIC_ERROR);
            reload();
        });
    }

    private static PlannerApi.Snapshot copyOf(PlannerApi.Snapshot s) {
        PlannerApi.Snapshot c = new PlannerApi.Snapshot();
        c.observed = s.observed;
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
        c.fullReplanTicket = s.fullReplanTicket;
        c.fullReplanDoneTicket = s.fullReplanDoneTicket;
        c.fullReplanResult = s.fullReplanResult;
        return c;
    }

    @Override
    public void showMessage(String text) {
        undo.show(text, null, null);
        undoRetries = 0;
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
        if (data == null || api == null || fullRunning || data.snap.fullReplanning) {
            return;
        }
        // Optimistic: the entries and the 今天 status line show it running right away.
        fullRunning = true;
        fullBefore = data.plan();
        PlannerApi.Snapshot optimistic = copyOf(data.snap);
        optimistic.fullReplanning = true;
        data = new UiData(data.rules, optimistic, data.changeSummary, data.returnBanner, data.nowMillis);
        bindPages();
        PlannerApi target = api;
        Async.run(this, target::requestFullReplan, ticket -> {
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
            showMessage(Messages.GENERIC_ERROR);
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
        }, done -> load(), error -> showMessage(Messages.GENERIC_ERROR));
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
            TextView how = Ui.body(this, "这台手机的桌面不支持直接添加。请回到桌面，长按空白处 → 小组件 → 找到「P3R 每日安排」，拖到桌面上。");
            how.setLineSpacing(0f, 1.3f);
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(this, 8);
            sheet.addView(how, p);
            showSheet(sheet);
        }
    }
}
