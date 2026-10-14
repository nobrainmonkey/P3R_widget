package app.p3r.dayplanner.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
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
 * game. Unfinished input is kept per semantic field in {@link DraftStore} and survives rotation
 * and process death. Saving is a single CAS checkpoint write; a conflict reloads the newer record
 * and keeps the player's edits.
 */
public final class StateActivity extends Activity implements StateSteps.Callbacks {
    private StateDraft.Mode mode;
    private PlannerApi api;
    private PlannerApi.Snapshot snap;
    private StateDraft draft;
    private DraftStore drafts;
    private StateSteps steps;
    private List<StateSteps.Page> pages;
    private int index;
    private boolean busy;

    private TextView title;
    private TextView progress;
    private StepBar stepBar;
    private ScrollView scroll;
    private FrameLayout content;
    private TextView back;
    private TextView next;
    private TextView notice;
    private LinearLayout bottomBar;

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
        root.setBackground(new OceanDrawable(Ui.col(this, R.color.p3r_navy_800), Ui.col(this, R.color.p3r_navy_900),
            Ui.col(this, R.color.p3r_navy_950), Ui.col(this, R.color.p3r_royal_500), Ui.col(this, R.color.p3r_cyan_300)));
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
        title = Ui.text(this, titleFor(mode), 18, Ui.col(this, R.color.p3r_white), true);
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(this, 4);
        bar.addView(title, tp);
        progress = Ui.numeral(this, "", 20, Ui.col(this, R.color.p3r_cyan_400));
        bar.addView(progress, Ui.wrap());
        column.addView(bar, Ui.matchWrap());
        stepBar = new StepBar(this);
        LinearLayout.LayoutParams sp = Ui.matchWrap();
        sp.leftMargin = Ui.dp(this, Ui.GUTTER);
        sp.rightMargin = Ui.dp(this, Ui.GUTTER);
        sp.topMargin = Ui.dp(this, 4);
        column.addView(stepBar, sp);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = new FrameLayout(this);
        Ui.pad(content, Ui.GUTTER, 20, Ui.GUTTER, 24);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        notice = Ui.text(this, "", 14, Ui.col(this, R.color.p3r_white), true);
        notice.setBackground(Ui.card(this).accent(Ui.col(this, R.color.ui_gold), Ui.dp(this, 4)));
        Ui.pad(notice, 18, 10, 14, 10);
        notice.setVisibility(View.GONE);
        LinearLayout.LayoutParams np = Ui.matchWrap();
        np.leftMargin = Ui.dp(this, Ui.GUTTER);
        np.rightMargin = Ui.dp(this, Ui.GUTTER);
        np.bottomMargin = Ui.dp(this, 8);
        column.addView(notice, np);

        bottomBar = Ui.row(this);
        Ui.pad(bottomBar, Ui.GUTTER, 10, Ui.GUTTER, 12);
        back = Ui.secondary(this, "上一步");
        back.setOnClickListener(v -> onBackPressed());
        bottomBar.addView(back, Ui.weight(1));
        next = Ui.primary(this, "下一步");
        next.setOnClickListener(v -> onNext());
        LinearLayout.LayoutParams nextParams = Ui.weight(1.6f);
        nextParams.leftMargin = Ui.dp(this, 12);
        bottomBar.addView(next, nextParams);
        bottomBar.setBackgroundColor(Ui.alpha(Ui.col(this, R.color.p3r_navy_950), 200));
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

    /** "暂时读不到记录", the service's message and [重试] instead of a wizard page. */
    private void showLoadFailed(String errorZh) {
        content.removeAllViews();
        LinearLayout box = Ui.column(this);
        box.addView(Ui.title(this, "暂时读不到记录"));
        TextView body = Ui.body(this, Messages.loadFailed(errorZh));
        LinearLayout.LayoutParams bp = Ui.matchWrap();
        bp.topMargin = Ui.dp(this, 8);
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
        next.setVisibility(View.INVISIBLE);
        back.setText("关闭");
    }

    private void onLoaded(Loaded l) {
        if (l.draft == null) {
            showLoadFailed(l.failedZh);
            return;
        }
        next.setVisibility(View.VISIBLE);
        api = l.api;
        snap = l.snapshot;
        draft = l.draft;
        mode = draft.mode;
        Zh.useNames(draft.rules);
        title.setText(titleFor(mode));
        steps = new StateSteps(this, draft, this);
        pages = StateSteps.skeletonFor(draft);
        int start = 0;
        if (draft.currentStep() != null) {
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i).name().equals(draft.currentStep())) {
                    start = i;
                }
            }
        }
        if (l.restored == DraftStore.Restored.REBASED) {
            showNotice("记录有更新，已保留你填过的内容。");
        } else if (l.restored == DraftStore.Restored.SAME && start > 0) {
            showNotice("已恢复上次没填完的内容。");
        }
        next.setEnabled(true);
        go(start, +1);
    }

    // ------------------------------------------------------------------ navigation

    /**
     * Shows page {@code target} of the fixed page list ({@link StateSteps#skeletonFor}); a page that
     * does not apply now is skipped in {@code direction}, so the counter keeps its total.
     */
    private void go(int target, int direction) {
        ensureUnlockPage();
        int i = Math.max(0, Math.min(pages.size() - 1, target));
        while (i > 0 && i < pages.size() - 1 && !StateSteps.applies(pages.get(i), draft)) {
            i += direction < 0 ? -1 : 1;
        }
        index = i;
        StateSteps.Page page = pages.get(index);
        draft.setStep(page.name());
        drafts.save(draft);
        render();
        scroll.scrollTo(0, 0);
    }

    /** First applicable page after (+1) or before (−1) the current one, or -1. */
    private int neighbour(int direction) {
        for (int i = index + direction; i >= 0 && i < pages.size(); i += direction) {
            if (StateSteps.applies(pages.get(i), draft)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解锁进度 is fixed in the list when the wizard opens; only when an edit makes it needed
     * although it was not expected (a multi-step link set back to 未解锁) is it inserted.
     */
    private void ensureUnlockPage() {
        if (!pages.contains(StateSteps.Page.UNLOCK) && StateSteps.applies(StateSteps.Page.UNLOCK, draft)) {
            pages.add(pages.indexOf(StateSteps.Page.LINKS) + 1, StateSteps.Page.UNLOCK);
            if (index > pages.indexOf(StateSteps.Page.LINKS)) {
                index++;
            }
        }
    }

    private void render() {
        StateSteps.Page page = pages.get(index);
        content.removeAllViews();
        content.addView(steps.build(page), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        progress.setText((index + 1) + "/" + pages.size());
        progress.setContentDescription("第 " + (index + 1) + " 步，共 " + pages.size() + " 步：" + StateSteps.titleOf(page));
        stepBar.set(index, pages.size());
        back.setText(index == 0 ? "稍后再填" : "上一步");
        boolean last = page == StateSteps.Page.CONFIRM;
        boolean unchanged = last && draft.observed != null && draft.changes().isEmpty() && !draft.profileChanged();
        if (last) {
            next.setText(unchanged ? "完成" : mode == StateDraft.Mode.ONBOARDING ? "保存并开始安排" : "保存并重新安排");
        } else {
            next.setText("下一步");
        }
        // On the opening question the three answers are the actions; "下一步" appears once answered.
        boolean awaitingAnswer = page == StateSteps.Page.SYNC_INTRO && draft.sync() == null;
        next.setVisibility(awaitingAnswer ? View.INVISIBLE : View.VISIBLE);
    }

    private void onNext() {
        if (busy || draft == null) {
            return;
        }
        if (pages.get(index) == StateSteps.Page.SYNC_INTRO && draft.sync() == null) {
            draft.setSync(StateDraft.SyncAnswer.NO);
        }
        if (pages.get(index) != StateSteps.Page.CONFIRM) {
            go(index + 1, +1);
            return;
        }
        if (draft.observed != null && draft.changes().isEmpty() && !draft.profileChanged()) {
            drafts.clear(mode);
            if (mode == StateDraft.Mode.SYNC && snap != null) {
                // Nothing changed in the game: the "游戏进度变了吗？" question is answered.
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
        if (index + 1 < pages.size()) {
            go(index + 1, +1);
        }
    }

    @Override
    public void refresh() {
        int y = scroll.getScrollY();
        ensureUnlockPage();
        render();
        scroll.post(() -> scroll.scrollTo(0, y));
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
                finish();
                return;
            }
            if (Messages.isStale(receipt)) {
                showNotice("刚才有新的记录（比如在桌面小组件上点了完成），已经合并进来，你填的内容都还在，请再确认一次。");
                load(current);
                return;
            }
            showNotice(Messages.receiptError(receipt));
        }, error -> {
            setBusy(false);
            showNotice(Messages.GENERIC_ERROR);
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        next.setEnabled(!value);
        back.setEnabled(!value);
        next.setText(value ? "正在保存…" : next.getText());
    }

    private void showNotice(String text) {
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ test hooks

    StateDraft draft() {
        return draft;
    }

    StateSteps.Page currentPage() {
        return pages == null ? null : pages.get(index);
    }

    /** The step counter ("5/10") (tests). */
    String progressText() {
        return Zh.plain(progress.getText());
    }

    String noticeText() {
        return notice.getVisibility() == View.VISIBLE ? Zh.plain(notice.getText()) : null;
    }

    /** Slanted segmented progress bar for the wizard. */
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
