package app.p3r.dayplanner.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * State wizard for onboarding, 同步现状 and 修改进度 ({@link Intents#EXTRA_STATE_MODE}).
 *
 * <p>One question per page, everything prefilled; the player only changes what differs from the
 * game. 开始设置 asks where the game is first and the goals last; 同步现状 after 「基本照着玩了」
 * goes from the date straight to the review; 修改进度 opens on 修改哪一项？, where one value is fixed
 * and saved in a few taps (the full walk stays one tap away). A page opened from 修改哪一项？ or
 * the review comes back there with 完成.
 *
 * <p>Unfinished input is kept per semantic field in {@link DraftStore} and survives rotation and
 * process death. Saving is a single CAS checkpoint write; a conflict reloads the newer record and
 * keeps the player's edits. Messages slide in briefly above the buttons, never as a pinned card.
 */
public final class StateActivity extends Activity implements StateSteps.Callbacks {
    /** A record arrived meanwhile (the widget's 完成, say): merged, the player looks once more. */
    static final String CONFLICT = "刚有新记录，已经合进来了，再看一眼？";
    /** A draft restored onto a newer record. */
    static final String REBASED = "刚有新记录，你填的都还在";
    /** A draft restored where it was left. */
    static final String RESUMED = "接着上次填";
    /** 今天 says 「已记录：游戏进度」 once the wizard has saved. */
    static final String SAVED_SUMMARY = "游戏进度";
    /** How long an ordinary message stays (ms); errors stay until the next page. */
    private static final long NOTICE_MILLIS = 3000;

    private StateDraft.Mode mode;
    private PlannerApi api;
    private PlannerApi.Snapshot snap;
    private StateDraft draft;
    private DraftStore drafts;
    private StateSteps steps;
    private List<StateSteps.Page> pages;
    private int index;
    /** The page a single page returns to (修改哪一项？ or the review); null while walking. */
    private StateSteps.Page returnTo;
    private boolean busy;
    private CharSequence busyLabel;
    /** Shown once the next load has rendered (after a conflict). */
    private String pendingNotice;
    private String lastNotice;

    private TextView modeLabel;
    private TextView counter;
    private StepBar stepBar;
    private ScrollView scroll;
    private FrameLayout content;
    private TextView back;
    private TextView next;
    private LinearLayout bottomBar;
    private UndoBar messageBar;
    private final Runnable hideNotice = () -> {
        if (messageBar != null) {
            messageBar.hide();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(getWindow());
        mode = StateDraft.Mode.of(getIntent().getStringExtra(Intents.EXTRA_STATE_MODE));
        drafts = new DraftStore(this);
        buildFrame();
        load(null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (busy) {
            return;
        }
        if (draft != null && returnTo != null) {
            returnFromSingle();
            return;
        }
        int previous = draft == null ? -1 : neighbour(-1);
        if (previous >= 0) {
            go(previous, -1);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ frame

    private void buildFrame() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(OceanDrawable.page(this));
        LinearLayout column = Ui.column(this);

        LinearLayout bar = Ui.row(this);
        Ui.pad(bar, 4, 4, Ui.GUTTER, 0);
        ImageView close = new ImageView(this);
        close.setImageDrawable(Ui.tinted(this, R.drawable.ui_ic_close, Ui.col(this, R.color.p3r_white)));
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setContentDescription("关闭，稍后继续");
        close.setOnClickListener(v -> finish());
        int size = Ui.dp(this, Ui.TOUCH);
        bar.addView(close, new LinearLayout.LayoutParams(size, size));
        modeLabel = Ui.text(this, titleFor(mode), 15, Ui.col(this, R.color.p3r_ink_muted), true);
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(this, 4);
        bar.addView(modeLabel, tp);
        counter = Ui.numeral(this, "", 30, Ui.col(this, R.color.p3r_white));
        counter.setVisibility(View.INVISIBLE);
        bar.addView(counter, Ui.wrap());
        column.addView(bar, Ui.matchWrap());
        stepBar = new StepBar(this);
        LinearLayout.LayoutParams sp = Ui.matchWrap();
        sp.leftMargin = Ui.dp(this, Ui.GUTTER);
        sp.rightMargin = Ui.dp(this, Ui.GUTTER);
        sp.topMargin = Ui.dp(this, 4);
        stepBar.setVisibility(View.INVISIBLE);
        column.addView(stepBar, sp);

        // The page scrolls; messages slide in over its lower edge, just above the buttons.
        FrameLayout stage = new FrameLayout(this);
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = new FrameLayout(this);
        // Room at the end so a message never hides the last row for good.
        Ui.pad(content, Ui.GUTTER, 18, Ui.GUTTER, 72);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        stage.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        messageBar = new UndoBar(this);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        mp.leftMargin = Ui.dp(this, Ui.GUTTER);
        mp.rightMargin = Ui.dp(this, Ui.GUTTER);
        mp.bottomMargin = Ui.dp(this, 8);
        stage.addView(messageBar, mp);
        column.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        bottomBar = Ui.row(this);
        Ui.pad(bottomBar, Ui.GUTTER, 10, Ui.GUTTER, 12);
        back = Ui.quiet(this, "上一步");
        back.setOnClickListener(v -> onSecondary());
        bottomBar.addView(back, Ui.weight(1));
        next = Ui.primary(this, "下一步");
        next.setOnClickListener(v -> onNext());
        LinearLayout.LayoutParams nextParams = Ui.weight(2);
        nextParams.leftMargin = Ui.dp(this, 12);
        bottomBar.addView(next, nextParams);
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
            Ui.alpha(Ui.col(this, R.color.p3r_navy_950), 120), Ui.alpha(Ui.col(this, R.color.p3r_navy_950), 235)}));
        column.addView(bottomBar, Ui.matchWrap());
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        EdgeToEdge.listen(root, (left, top, right, bottom, ime) -> {
            column.setPadding(left, top, right, 0);
            bottomBar.setPadding(Ui.dp(this, Ui.GUTTER), Ui.dp(this, 10), Ui.dp(this, Ui.GUTTER),
                Ui.dp(this, 12) + bottom + ime);
        });
        TextView loading = Ui.muted(this, "正在读取…");
        content.addView(loading);
        next.setEnabled(false);
        back.setVisibility(View.INVISIBLE);
    }

    private static String titleFor(StateDraft.Mode mode) {
        switch (mode) {
            case ONBOARDING:
                return "开始设置";
            case SYNC:
                return "同步现状";
            default:
                return "修改进度";
        }
    }

    // ------------------------------------------------------------------ data

    /** Background load result. */
    private static final class Loaded {
        PlannerApi api;
        PlannerApi.Snapshot snapshot;
        StateDraft draft;
        DraftStore.Restored restored = DraftStore.Restored.NONE;
        /** Set (possibly "") when the records or the game data could not be read. */
        String failedZh;
    }

    /**
     * The profile the wizard starts from: the stored one, except that a first onboarding (nothing
     * recorded and no profile saved yet) starts from the defaults of a new profile, which under
     * 「尽量都体验」 opt into every 羁绊剧情 track (so the wizard asks how many were watched).
     * Stored profiles are never rewritten.
     */
    static Profile startProfile(StateDraft.Mode mode, PlannerApi.Snapshot snapshot, Rules rules) {
        Profile profile = snapshot.profile;
        if (mode != StateDraft.Mode.ONBOARDING || snapshot.observed != null || snapshot.profileRevision != 0 || profile == null) {
            return profile;
        }
        Profile fresh = profile.copy();
        fresh.applyNewProfileDefaults(rules);
        return fresh;
    }

    /** Loads the snapshot and the saved draft; {@code previous} edits are carried over when given. */
    private void load(StateDraft previous) {
        Context app = getApplicationContext();
        StateDraft.Mode m = mode;
        Async.run(this, () -> {
            Loaded l = new Loaded();
            l.api = Api.get(app);
            Rules rules = l.api.rules();
            l.snapshot = l.api.snapshot();
            if (rules == null || (l.snapshot.observed == null && l.snapshot.planErrorZh != null)) {
                // Records or game data unreadable: never start onboarding over them.
                l.failedZh = l.snapshot.planErrorZh == null ? "" : l.snapshot.planErrorZh;
                return l;
            }
            StateDraft.Mode effective = m == StateDraft.Mode.ONBOARDING || l.snapshot.observed != null
                ? m : StateDraft.Mode.ONBOARDING;
            l.draft = new StateDraft(effective, rules, l.snapshot.observed, startProfile(effective, l.snapshot, rules),
                l.snapshot.plan, l.snapshot.stateRevision);
            if (previous != null) {
                l.draft.copyEditsFrom(previous);
            } else {
                l.restored = drafts.restore(l.draft);
            }
            return l;
        }, this::onLoaded, error -> showLoadFailed(null));
    }

    /** 「记录没读出来」, the reason and [重试] instead of a wizard page; the X is the only way out. */
    private void showLoadFailed(String errorZh) {
        content.removeAllViews();
        stepBar.setVisibility(View.INVISIBLE);
        counter.setVisibility(View.INVISIBLE);
        modeLabel.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.GONE);
        LinearLayout box = Ui.column(this);
        box.addView(Ui.title(this, "记录没读出来"));
        TextView body = Ui.body(this, Messages.loadFailed(errorZh));
        LinearLayout.LayoutParams bp = Ui.matchWrap();
        bp.topMargin = Ui.dp(this, 8);
        body.setVisibility(body.getText().length() == 0 ? View.GONE : View.VISIBLE);
        box.addView(body, bp);
        TextView retry = Ui.primary(this, "重试");
        retry.setOnClickListener(v -> {
            content.removeAllViews();
            content.addView(Ui.muted(this, "正在读取…"));
            load(null);
        });
        LinearLayout.LayoutParams rp = Ui.matchWrap();
        rp.topMargin = Ui.dp(this, 24);
        box.addView(retry, rp);
        content.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        next.setEnabled(false);
    }

    private void onLoaded(Loaded l) {
        if (l.draft == null) {
            showLoadFailed(l.failedZh);
            return;
        }
        bottomBar.setVisibility(View.VISIBLE);
        api = l.api;
        snap = l.snapshot;
        draft = l.draft;
        mode = draft.mode;
        Zh.useNames(draft.rules);
        modeLabel.setText(titleFor(mode));
        steps = new StateSteps(this, draft, this);
        pages = StateSteps.skeletonFor(draft);
        StateSteps.Page at = pageNamed(draft.currentStep());
        StateSteps.Page from = pageNamed(draft.returnTo);
        boolean resumed;
        next.setEnabled(true);
        if (at != null && from != null && at != from) {
            show(at, from);
            resumed = true;
        } else {
            int start = at == null ? 0 : pages.indexOf(at);
            go(start, +1);
            resumed = index > 0 || (draft.observed != null && !draft.changes().isEmpty());
        }
        String notice = pendingNotice;
        pendingNotice = null;
        if (notice != null) {
            showNotice(notice, false);
        } else if (l.restored == DraftStore.Restored.REBASED) {
            showNotice(REBASED, false);
        } else if (l.restored == DraftStore.Restored.SAME && resumed) {
            showNotice(RESUMED, false);
        }
    }

    private StateSteps.Page pageNamed(String name) {
        if (name == null) {
            return null;
        }
        for (StateSteps.Page page : pages) {
            if (page.name().equals(name)) {
                return page;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ navigation

    /**
     * Shows page {@code target} of the walk ({@link StateSteps#skeletonFor}); a page with nothing
     * to ask now is skipped in {@code direction}.
     */
    private void go(int target, int direction) {
        int i = Math.max(0, Math.min(pages.size() - 1, target));
        while (i > 0 && i < pages.size() - 1 && !StateSteps.applies(pages.get(i), draft)) {
            i += direction < 0 ? -1 : 1;
        }
        index = i;
        returnTo = null;
        draft.setStep(pages.get(index).name());
        draft.returnTo = null;
        drafts.save(draft);
        enter(direction >= 0);
    }

    /** Opens {@code page} on its own; 完成 (or back) returns to {@code from}. */
    private void show(StateSteps.Page page, StateSteps.Page from) {
        int at = pages.indexOf(page);
        if (at < 0) {
            return;
        }
        index = at;
        returnTo = from;
        draft.setStep(page.name());
        draft.returnTo = from.name();
        drafts.save(draft);
        enter(true);
    }

    private void returnFromSingle() {
        StateSteps.Page from = returnTo;
        int at = pages.indexOf(from);
        returnTo = null;
        go(at < 0 ? 0 : at, -1);
    }

    private void enter(boolean forward) {
        hideNotice();
        render();
        scroll.scrollTo(0, 0);
        if (content.getChildCount() > 0) {
            Motion.tabIn(content.getChildAt(0), forward);
        }
        for (ViewGroup list : steps.cascades()) {
            Motion.cascade(list);
        }
    }

    /** First page of the walk after (+1) or before (−1) the current one, or -1. */
    private int neighbour(int direction) {
        for (int i = index + direction; i >= 0 && i < pages.size(); i += direction) {
            if (StateSteps.applies(pages.get(i), draft)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasChanges() {
        return draft.observed == null || !draft.changes().isEmpty() || draft.profileChanged();
    }

    private void render() {
        StateSteps.Page page = pages.get(index);
        content.removeAllViews();
        content.addView(steps.build(page), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        boolean single = returnTo != null;
        boolean hub = page == StateSteps.Page.HUB;
        List<StateSteps.Page> walk = StateSteps.pagesFor(draft);
        int position = single || hub ? -1 : walk.indexOf(page);
        if (position >= 0) {
            SpannableStringBuilder text = new SpannableStringBuilder(String.format(java.util.Locale.ROOT, "%02d",
                position + 1));
            int start = text.length();
            text.append("/").append(String.valueOf(walk.size()));
            text.setSpan(new RelativeSizeSpan(0.55f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(Ui.col(this, R.color.p3r_cyan_400)), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            counter.setText(text);
            counter.setContentDescription("第 " + (position + 1) + " 步，共 " + walk.size() + " 步："
                + StateSteps.titleOf(page));
            counter.setVisibility(View.VISIBLE);
            stepBar.set(position, walk.size());
            stepBar.setVisibility(View.VISIBLE);
        } else {
            counter.setText("");
            counter.setVisibility(View.INVISIBLE);
            stepBar.setVisibility(View.INVISIBLE);
        }
        progress = position < 0 ? "" : (position + 1) + "/" + walk.size();
        modeLabel.setVisibility(position <= 0 ? View.VISIBLE : View.INVISIBLE);

        boolean changes = hasChanges();
        if (single) {
            back.setVisibility(View.GONE);
            next.setText("完成");
            next.setVisibility(View.VISIBLE);
        } else if (hub) {
            back.setText("逐项检查");
            back.setVisibility(View.VISIBLE);
            next.setText("更新安排");
            next.setVisibility(changes ? View.VISIBLE : View.INVISIBLE);
        } else {
            back.setText("上一步");
            back.setVisibility(neighbour(-1) >= 0 ? View.VISIBLE : View.INVISIBLE);
            if (page == StateSteps.Page.CONFIRM) {
                next.setText(!changes ? "好的" : mode == StateDraft.Mode.ONBOARDING ? "开始安排" : "更新安排");
            } else {
                next.setText("下一步");
            }
            // On the opening question the three answers are the actions; 下一步 appears once answered.
            boolean awaitingAnswer = page == StateSteps.Page.SYNC_INTRO && draft.sync() == null;
            next.setVisibility(awaitingAnswer ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /** The left button: 上一步, or 逐项检查 (the full walk) on 修改哪一项？. */
    private void onSecondary() {
        if (busy || draft == null) {
            return;
        }
        if (pages.get(index) == StateSteps.Page.HUB && returnTo == null) {
            go(pages.indexOf(StateSteps.Page.DATE), +1);
            return;
        }
        onBackPressed();
    }

    private void onNext() {
        if (busy || draft == null) {
            return;
        }
        if (returnTo != null) {
            returnFromSingle();
            return;
        }
        StateSteps.Page page = pages.get(index);
        if (page == StateSteps.Page.HUB) {
            if (hasChanges()) {
                save();
            }
            return;
        }
        if (page == StateSteps.Page.SYNC_INTRO && draft.sync() == null) {
            draft.setSync(StateDraft.SyncAnswer.NO);
        }
        if (page != StateSteps.Page.CONFIRM) {
            go(index + 1, +1);
            return;
        }
        if (!hasChanges()) {
            drafts.clear(mode);
            if (mode == StateDraft.Mode.SYNC && snap != null) {
                // Nothing changed in the game: the 「之后又玩了吗？」 question is answered.
                UiPrefs.setBannerDismissedFor(this, snap.lastRecordMillis);
            }
            finish();
            return;
        }
        save();
    }

    @Override
    public void changed() {
        drafts.save(draft);
    }

    @Override
    public void next() {
        if (returnTo != null) {
            returnFromSingle();
        } else if (index + 1 < pages.size()) {
            go(index + 1, +1);
        }
    }

    @Override
    public void refresh() {
        int y = scroll.getScrollY();
        render();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    @Override
    public void open(StateSteps.Page page) {
        show(page, pages.get(index));
    }

    // ------------------------------------------------------------------ save

    private void save() {
        setBusy(true);
        SimState built = draft.build();
        Profile profile = draft.profileToSave();
        long rev = snap.stateRevision;
        PlannerApi a = api;
        StateDraft current = draft;
        Async.run(this, () -> {
            String profileJson = profile == null ? "" : profile.toJson().toString();
            String id = CommandIds.of("checkpoint", rev, built.toJson().toString(), profileJson);
            return a.saveCheckpoint(id, rev, built, profile);
        }, receipt -> {
            setBusy(false);
            if (receipt.ok) {
                drafts.clear(mode);
                // 今天 shows 「已记录：游戏进度」 (no 撤销) when it comes back.
                UndoCenter.post(-1, SAVED_SUMMARY);
                finish();
                return;
            }
            if (Messages.isStale(receipt)) {
                pendingNotice = CONFLICT;
                load(current);
                return;
            }
            showNotice(Messages.receiptError(receipt), true);
        }, error -> {
            setBusy(false);
            showNotice(Messages.GENERIC_ERROR, true);
        });
    }

    /** While saving the primary reads 「正在保存…」; afterwards its own label comes back. */
    private void setBusy(boolean value) {
        busy = value;
        next.setEnabled(!value);
        back.setEnabled(!value);
        if (value) {
            busyLabel = next.getText();
            next.setText("正在保存…");
        } else if (busyLabel != null) {
            next.setText(busyLabel);
            busyLabel = null;
        }
    }

    private void showNotice(String text, boolean error) {
        lastNotice = text;
        messageBar.removeCallbacks(hideNotice);
        messageBar.show(text, null, null, error ? UndoBar.Style.ERROR : UndoBar.Style.RECORD);
        if (!error) {
            messageBar.postDelayed(hideNotice, NOTICE_MILLIS);
        }
    }

    private void hideNotice() {
        messageBar.removeCallbacks(hideNotice);
        if (messageBar.isShowing()) {
            messageBar.hide();
        }
    }

    // ------------------------------------------------------------------ test hooks

    private String progress = "";

    StateDraft draft() {
        return draft;
    }

    StateSteps.Page currentPage() {
        return pages == null ? null : pages.get(index);
    }

    /** The page a single page returns to, or null while walking (tests). */
    StateSteps.Page returnPage() {
        return returnTo;
    }

    /** The step counter ("5/9"), "" on 修改哪一项？ and on a single page (tests). */
    String progressText() {
        return progress;
    }

    /** The message showing now, or null (tests). */
    String noticeText() {
        return messageBar.isShowing() ? lastNotice : null;
    }

    /** The primary button's label (tests). */
    String primaryText() {
        return Zh.plain(next.getText());
    }

    /** Slanted segmented progress bar for the wizard: one segment per page of the walk. */
    static final class StepBar extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private int current;
        private int total = 1;

        StepBar(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(int current, int total) {
            this.current = current;
            this.total = Math.max(1, total);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.dp(getContext(), 6));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            Context c = getContext();
            float h = getHeight();
            float gap = Ui.dp(c, 4);
            float skew = h;
            float w = (getWidth() - skew - gap * (total - 1)) / total;
            for (int i = 0; i < total; i++) {
                float left = i * (w + gap);
                path.reset();
                path.moveTo(left + skew, 0);
                path.lineTo(left + skew + w, 0);
                path.lineTo(left + w, h);
                path.lineTo(left, h);
                path.close();
                if (i < current) {
                    paint.setColor(Ui.col(c, R.color.p3r_cyan_300));
                } else if (i == current) {
                    paint.setColor(Ui.col(c, R.color.p3r_white));
                } else {
                    paint.setColor(Ui.col(c, R.color.p3r_line_soft));
                }
                canvas.drawPath(path, paint);
            }
        }
    }
}
