package app.p3r.dayplanner.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * Translucent quick result sheet, opened from the widget or the app (docs/DESIGN_3.6_UI.md).
 *
 * <p>The player has already played the step, so a panel asks only what happened: the kind tag and
 * title, the question on a slanted speech plate (「升到 RANK 7 了吗？」 over 「RANK 6 → 7」), the
 * 「顺便」 lines with an inline 「也做了」 check, and at most two answer plates staggered like the
 * game's answer list; 「其他结果…」 and 「做不了…」 are quiet links. Kinds: social rank-up (没升级
 * asks for the 「下次就能升级」 cue next), ordinary meeting, holiday invitation, unlock step,
 * 结缘网站的字条, 羁绊剧情, dorm activity, training/job (「结果不一样…」 for levels and cash),
 * Tartarus ({@link TowerPanel}), story and free slots; plus 做不了 reasons, 改做别的 alternatives and
 * the catch-up question when 完成 is pressed on a future item. Panels opened from a panel stack up:
 * back returns one level, and only the entry panel closes the sheet.
 *
 * <p>A plan id / revision that no longer matches shows the current item with 「安排更新了」 instead;
 * nothing stale is ever saved. A recorded rank-up plays {@link RankUpBurst} before the sheet closes.
 *
 * <p>From 记录历史 the sheet corrects an earlier record ({@link Intents#EXTRA_CORRECT_SEQ}) on the
 * same panels, from the state before it; a tower night opens on 做不了, with 「其实去了」 when it
 * was recorded as 没去成.
 */
public final class ResultActivity extends Activity {
    /** The button on 「这项已经记过了」 that opens 记录历史 in the app. */
    static final String HISTORY_LABEL = "看记录历史";
    private static final String STATE_SLOT = "slot";
    private static final String STATE_ACTION = "action";
    private static final String STATE_MODE = "mode";
    private static final String STATE_QUEUE = "queue";
    private static final String STATE_QUEUE_TOTAL = "queueTotal";
    private static final String STATE_CHECKED = "checked";
    private static final String STATE_BANNER = "banner";
    private static final String STATE_BANNER_ERROR = "bannerError";
    private static final String STATE_TOWER = "tower";
    private static final String STATE_CAUGHT_UP = "caughtUp";

    /** Sub-panel: the 「下次就能升级」 question after 没升级 on a rank-up. */
    private static final String SUB_CUE = "cue";
    /** Sub-panel: 「以后还排…吗？」 after 不想做 on an activity. */
    private static final String SUB_EXCLUDE = "exclude";
    /** Sub-panel: 「结果不一样…」 of a training / job / dorm step with stat gains. */
    private static final String SUB_DIFFERENT = "different";

    /** Label of the inline 「顺便」 check on a result panel (side steps done unless unchecked). */
    static final String SIDE_DONE = "也做了";
    /** The same check on the 做不了 panel (default not done). */
    static final String SIDE_DONE_CANNOT = "做了";
    /** The 「下次就能升级」 cue as a compact check on meetings. */
    static final String READY_CUE = "提示了「下次就能升级」";
    /** Asked after 没升级 on a rank-up. */
    static final String CUE_QUESTION = "提示下次就能升级了吗？";
    static final String OTHER_RESULTS = "其他结果…";
    static final String HOLD = "剧情卡住了，升不了";
    static final String OTHER_INSTEAD = "其实做了别的…";
    /** On the correction of a tower night recorded as 没去成. */
    static final String WENT_AFTER_ALL = "其实去了";
    static final String NOTHING_ELSE = "都不是，没什么收获";

    /** How long a save may take before 「正在保存…」 is shown (ms). */
    private static final long SAVING_TEXT_DELAY = 400;
    /** While no plan is there yet, the sheet looks again after this long (ms). */
    private static final long PLAN_RETRY = 1500;

    private FrameLayout root;
    private View scrim;
    private LinearLayout panel;
    private LinearLayout body;
    private ScrollView scroll;
    private FrameLayout blocker;
    private TextView blockerText;
    private BusyStripe busyStripe;

    private PlannerApi api;
    private Rules rules;
    private PlannerApi.Snapshot snap;
    private int rememberedLevel = 1;
    private List<Plan.Step> alternatives;

    private int slot;
    private String actionKey;
    private String mode;
    private String planIdExtra;
    private long stateRevExtra;
    private boolean fromApp;
    private boolean staleChecked;
    private int queueTarget = -1;
    /** Open slots the 一项项对 queue started with (before the target). */
    private int queueTotal;
    private String banner;
    /** The banner reports a failure (ERROR look) rather than a change. */
    private boolean bannerError;
    /** A catch-up toward {@link #slot} just finished (all earlier open slots are recorded). */
    private boolean caughtUp;
    private boolean busy;
    /** The rank-up flourish is playing: back waits for it. */
    private boolean celebrating;
    private String playedBurst;
    private boolean closing;
    /** Alternative chosen on 改做别的 (its own panel), else null. */
    private Plan.Step substitute;
    /** Sub-panel of the current mode ({@link #SUB_CUE}, {@link #SUB_EXCLUDE}, {@link #SUB_DIFFERENT}) or null. */
    private String sub;
    /** The panels this one was opened from; back returns to the last one. */
    private final List<Screen> stack = new ArrayList<>();
    /** The 「顺便」 answer of the panel a sub-panel was opened from (it has no check of its own). */
    private Boolean carriedSide;
    /** The 「顺便」 answer to show again when going back to a panel. */
    private Boolean restoreSide;
    /** How the next render enters. */
    private Transition transition = Transition.FIRST;
    private TowerPanel towerPanel;
    private Bundle pendingTowerState;
    /** The 「也做了」 check of the current panel, or null when the step has no side steps. */
    private CheckRow sideRow;
    /** The answer plate tapped last (it flashes while saving). */
    private View tapped;
    private long correctionSeq = -1;
    private PlannerApi.OutcomeCorrection correction;
    private Plan.Step correctionStep;
    private final Runnable showSavingText = () -> {
        if (busy && blockerText != null) {
            blockerText.setVisibility(View.VISIBLE);
        }
    };
    private final Runnable retryPlan = () -> {
        if (!isFinishing() && !busy && snap != null && step() == null) {
            reloadAndRoute();
        }
    };

    private enum Transition { NONE, FIRST, FORWARD, BACK }

    /** One panel in the back stack. */
    private static final class Screen {
        String mode;
        Plan.Step substitute;
        String sub;
        Boolean side;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(getWindow());
        readRequest(getIntent());
        if (savedInstanceState != null) {
            slot = savedInstanceState.getInt(STATE_SLOT, slot);
            actionKey = savedInstanceState.getString(STATE_ACTION, actionKey);
            mode = savedInstanceState.getString(STATE_MODE, mode);
            queueTarget = savedInstanceState.getInt(STATE_QUEUE, -1);
            queueTotal = savedInstanceState.getInt(STATE_QUEUE_TOTAL, 0);
            staleChecked = savedInstanceState.getBoolean(STATE_CHECKED, false);
            banner = savedInstanceState.getString(STATE_BANNER);
            bannerError = savedInstanceState.getBoolean(STATE_BANNER_ERROR, false);
            caughtUp = savedInstanceState.getBoolean(STATE_CAUGHT_UP, false);
            pendingTowerState = savedInstanceState.getBundle(STATE_TOWER);
        }
        buildFrame();
        showLoading();
        load();
    }

    /**
     * singleTask: a second "完成" from the widget arrives here. Unless a save is in flight, the
     * sheet starts over for the new request (with a fresh stale check).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (busy) {
            return;
        }
        setIntent(intent);
        readRequest(intent);
        staleChecked = false;
        queueTarget = -1;
        banner = null;
        bannerError = false;
        caughtUp = false;
        substitute = null;
        sub = null;
        stack.clear();
        carriedSide = null;
        restoreSide = null;
        alternatives = null;
        pendingTowerState = null;
        transition = Transition.FIRST;
        showLoading();
        load();
    }

    /** Reads the request extras (docs: {@link Intents}). */
    private void readRequest(Intent intent) {
        slot = intent.getIntExtra(Intents.EXTRA_SLOT, -1);
        actionKey = intent.getStringExtra(Intents.EXTRA_ACTION);
        mode = intent.getStringExtra(Intents.EXTRA_MODE);
        if (mode == null) {
            mode = Intents.MODE_RESULT;
        }
        planIdExtra = intent.getStringExtra(Intents.EXTRA_PLAN_ID);
        stateRevExtra = intent.getLongExtra(Intents.EXTRA_STATE_REV, -1);
        fromApp = intent.getBooleanExtra(HomeActivity.EXTRA_FROM_APP, false);
        correctionSeq = intent.getLongExtra(Intents.EXTRA_CORRECT_SEQ, -1);
        correction = null;
        correctionStep = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_SLOT, slot);
        out.putString(STATE_ACTION, actionKey);
        out.putString(STATE_MODE, mode);
        out.putInt(STATE_QUEUE, queueTarget);
        out.putInt(STATE_QUEUE_TOTAL, queueTotal);
        out.putBoolean(STATE_CHECKED, staleChecked);
        out.putString(STATE_BANNER, banner);
        out.putBoolean(STATE_BANNER_ERROR, bannerError);
        out.putBoolean(STATE_CAUGHT_UP, caughtUp);
        if (towerPanel != null) {
            out.putBundle(STATE_TOWER, towerPanel.saveState());
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (busy || celebrating) {
            return;
        }
        if (pop()) {
            return;
        }
        close();
    }

    @Override
    protected void onDestroy() {
        if (body != null) {
            body.removeCallbacks(retryPlan);
        }
        if (blocker != null) {
            blocker.removeCallbacks(showSavingText);
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ frame

    private void buildFrame() {
        root = new FrameLayout(this);
        scrim = new View(this);
        scrim.setBackgroundColor(Ui.col(this, R.color.ui_scrim));
        scrim.setContentDescription("关闭");
        scrim.setOnClickListener(v -> {
            if (!busy && !celebrating) {
                close();
            }
        });
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        panel = Ui.column(this);
        panel.setBackground(SheetChrome.surface(this));
        panel.setClickable(true);
        // The grip: drag the sheet down to close it (as the in-app sheets); the saving stripe runs
        // along the top edge.
        FrameLayout grip = SheetChrome.grip(this);
        busyStripe = new BusyStripe(this);
        busyStripe.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(this, 3), Gravity.TOP);
        bp.leftMargin = Ui.dp(this, 30);
        grip.addView(busyStripe, bp);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(this, SheetChrome.GRIP));
        gp.topMargin = Ui.dp(this, 2);
        panel.addView(grip, gp);
        SheetChrome.dragToDismiss(grip, panel, () -> !busy && !celebrating && !closing, this::close);
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = Ui.column(this);
        Ui.pad(body, 20, 0, 20, 20);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        pp.topMargin = Ui.dp(this, 56);
        root.addView(panel, pp);
        // While saving: a transparent touch blocker (no double submits); the text only when slow.
        blocker = new FrameLayout(this);
        blocker.setClickable(true);
        blocker.setVisibility(View.GONE);
        blockerText = Ui.text(this, "正在保存…", 15, Ui.col(this, R.color.p3r_white), true);
        blockerText.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(this, R.color.p3r_navy_950), 230)).skew(Ui.dp(this, 8)));
        Ui.pad(blockerText, 20, 10, 20, 10);
        blockerText.setVisibility(View.GONE);
        blocker.addView(blockerText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(blocker, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        EdgeToEdge.listen(root, (left, top, right, bottom, ime) -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
            p.topMargin = top + Ui.dp(this, 48);
            panel.setLayoutParams(p);
            scroll.setPadding(left, 0, right, bottom + ime);
        });
        if (Ui.animationsEnabled()) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(200).start();
            panel.setTranslationY(Ui.dp(this, 80));
            panel.setAlpha(0f);
            panel.animate().translationY(0).alpha(1f).setDuration(260).setInterpolator(Motion.SETTLE).start();
        }
    }

    /** Closes the sheet the way it came: the panel drops away (from wherever a drag left it). */
    private void close() {
        if (closing) {
            return;
        }
        closing = true;
        if (!Ui.animationsEnabled() || panel == null) {
            finish();
            return;
        }
        scrim.animate().alpha(0f).setDuration(160).start();
        panel.animate().cancel();
        panel.animate().translationY(SheetChrome.exitTranslation(panel)).alpha(0f).setDuration(160)
            .setInterpolator(Motion.EASE).withEndAction(() -> {
                finish();
                overridePendingTransition(0, 0);
            }).start();
    }

    private void showLoading() {
        body.removeAllViews();
        body.addView(new BusyStripe(this), top(36));
        body.addView(Ui.gap(this, 36));
    }

    // ------------------------------------------------------------------ data

    /** Loaded bundle from the background thread. */
    private static final class Loaded {
        PlannerApi api;
        Rules rules;
        PlannerApi.Snapshot snapshot;
        int level;
        PlannerApi.OutcomeCorrection correction;
        Plan.Step correctionStep;
    }

    private void load() {
        stack.clear();
        sub = null;
        Context app = getApplicationContext();
        Async.run(this, () -> {
            Loaded l = new Loaded();
            l.api = Api.get(app);
            l.rules = l.api.rules();
            l.snapshot = l.api.snapshot();
            l.level = UiPrefs.towerLevel(app);
            if (correctionSeq >= 0) {
                l.correction = l.api.outcomeCorrection(correctionSeq);
                if (l.correction != null && l.rules != null) {
                    l.correctionStep = HistoricalOutcome.step(l.rules, l.snapshot.profile, l.correction);
                }
            }
            return l;
        }, l -> {
            api = l.api;
            rules = l.rules;
            Zh.useNames(rules);
            snap = l.snapshot;
            rememberedLevel = l.level;
            correction = l.correction;
            correctionStep = l.correctionStep;
            route();
        }, error -> showLoadFailed(null));
    }

    /** Decides what to show for the current snapshot (stale check, catch-up, mode). */
    private void route() {
        SimState obs = snap.observed;
        Plan plan = snap.plan;
        if (rules == null || (obs == null && snap.planErrorZh != null)) {
            // The records or the game data could not be read: say so, never "not set up".
            showLoadFailed(snap.planErrorZh);
            return;
        }
        if (obs == null) {
            showNotSetUp();
            return;
        }
        if (correctionSeq >= 0) {
            if (correction == null || correctionStep == null) {
                showState("这条记录刚改过", "重新打开看看？", "好的");
                return;
            }
            snap.observed = correction.beforeState.copy();
            snap.stateRevision = correction.stateRevision;
            slot = correction.slot;
            actionKey = correction.actionKey;
            render();
            return;
        }
        if (Slots.finished(rules, obs)) {
            showFinished();
            return;
        }
        if (!staleChecked) {
            staleChecked = true;
            boolean planMoved = planIdExtra != null && (plan == null || !planIdExtra.equals(plan.planId));
            boolean revMoved = stateRevExtra >= 0 && stateRevExtra != snap.stateRevision;
            Plan.Step requested = plan == null || slot < 0 ? null : plan.at(slot);
            boolean actionMoved = actionKey != null && requested != null && !Intents.MODE_OTHER.equals(mode)
                && !actionKey.equals(requested.action);
            if (planMoved || revMoved || actionMoved) {
                setBanner(STALE_INFO, false);
                slot = obs.slot;
                mode = Intents.MODE_RESULT;
                Plan.Step current = plan == null ? null : plan.at(slot);
                actionKey = current == null ? null : current.action;
            }
        }
        if (queueTarget >= 0 && obs.slot >= queueTarget) {
            slot = queueTarget;
            queueTarget = -1;
            caughtUp = true;
            Plan.Step target = plan == null ? null : plan.at(slot);
            actionKey = target == null ? actionKey : target.action;
        } else if (queueTarget >= 0) {
            slot = obs.slot;
            Plan.Step next = plan == null ? null : plan.at(slot);
            actionKey = next == null ? null : next.action;
        }
        if (slot < 0) {
            slot = obs.slot;
        }
        if (Intents.MODE_OTHER.equals(mode) && alternatives == null) {
            loadAlternatives();
            return;
        }
        render();
    }

    /** Info banner after a stale request: the sheet shows the current item instead. */
    static final String STALE_INFO = "安排更新了，这是现在的下一项";

    private void loadAlternatives() {
        showLoading();
        PlannerApi target = api;
        int at = slot;
        Async.run(this, () -> target.alternatives(at), list -> {
            alternatives = list == null ? new ArrayList<>() : list;
            render();
        }, error -> {
            alternatives = new ArrayList<>();
            render();
        });
    }

    /** The plan step the panel is about (the chosen alternative when there is one). */
    private Plan.Step step() {
        if (correctionStep != null) return correctionStep;
        if (substitute != null) {
            return substitute;
        }
        Plan plan = snap.plan;
        return plan == null ? null : plan.at(slot);
    }

    /**
     * True when the step's visible predictions can be trusted: a chosen alternative (forecast
     * from the current record) or a step of a plan that matches the latest record.
     */
    private boolean predictionCurrent() {
        return correctionStep != null || substitute != null || snap.planCurrent;
    }

    private void setBanner(String text, boolean error) {
        banner = text;
        bannerError = error && text != null;
    }

    // ------------------------------------------------------------------ panel stack

    /** Opens {@code newMode} / {@code newSub} on top of the current panel; back returns here. */
    private void push(String newMode, Plan.Step newSubstitute, String newSub, boolean carrySide) {
        Screen here = new Screen();
        here.mode = mode;
        here.substitute = substitute;
        here.sub = sub;
        here.side = sideRow == null ? carriedSide : Boolean.valueOf(sideRow.isChecked());
        stack.add(here);
        carriedSide = carrySide ? here.side : null;
        restoreSide = null;
        mode = newMode;
        substitute = newSubstitute;
        sub = newSub;
        transition = Transition.FORWARD;
        if (Intents.MODE_OTHER.equals(mode) && substitute == null && alternatives == null) {
            loadAlternatives();
        } else {
            render();
        }
    }

    /** Back one panel; false when this is the panel the sheet opened with. */
    private boolean pop() {
        if (stack.isEmpty() || snap == null) {
            return false;
        }
        Screen back = stack.remove(stack.size() - 1);
        mode = back.mode;
        substitute = back.substitute;
        sub = back.sub;
        restoreSide = back.side;
        carriedSide = stack.isEmpty() || back.sub == null ? null : stack.get(stack.size() - 1).side;
        transition = Transition.BACK;
        render();
        return true;
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        body.removeCallbacks(retryPlan);
        body.removeAllViews();
        towerPanel = null;
        sideRow = null;
        tapped = null;
        SimState obs = snap.observed;
        boolean resultLike = Intents.MODE_RESULT.equals(mode) || Intents.MODE_CATCHUP.equals(mode);
        if (substitute == null && sub == null && resultLike && slot > obs.slot && correction == null
            && pendingBefore(slot).isEmpty()) {
            // Only story time lies before the item: go through it on its own panel, then on to the item.
            startQueue(slot, 0);
        }
        Plan.Step step = step();
        if (slot < obs.slot) {
            if (caughtUp) {
                showCaughtUp();
            } else {
                showAlreadyRecorded();
            }
            return;
        }
        header(step);
        if (step == null) {
            waitingForPlan();
            return;
        }
        if (banner != null) {
            body.addView(bannerView(banner, bannerError), top(12));
        }
        if (SUB_CUE.equals(sub)) {
            cuePanel(step);
        } else if (SUB_EXCLUDE.equals(sub)) {
            excludePanel(step);
        } else if (substitute == null && resultLike && slot > obs.slot) {
            catchUpPanel(step);
        } else if (substitute == null && Intents.MODE_CANNOT.equals(mode)) {
            cannotPanel(step);
        } else if (substitute == null && Intents.MODE_OTHER.equals(mode)) {
            otherPanel(step);
        } else {
            resultPanel(step);
        }
        restoreSide = null;
        enter();
    }

    /** Plays the entrance of freshly built panel content. */
    private void enter() {
        Transition how = transition;
        transition = Transition.NONE;
        if (!Ui.animationsEnabled()) {
            return;
        }
        switch (how) {
            case FIRST:
                Motion.cascade(body);
                break;
            case FORWARD:
                Motion.swapIn(body);
                break;
            case BACK:
                Motion.tabIn(body, false);
                break;
            default:
                break;
        }
    }

    /** Decorative Latin word behind the header, after the game's menus. */
    static String watermarkWord(StepKind kind) {
        switch (kind) {
            case SOCIAL_RANK_UP:
            case SOCIAL_MEET:
            case UNLOCK:
            case NOTE:
                return "SOCIAL LINK";
            case STAT:
                return "STATS";
            case JOB:
                return "PART-TIME";
            case TOWER:
                return "TARTARUS";
            case EPISODE:
                return "LINK EPISODE";
            case DORM:
                return "DORM";
            case LOCKED:
                return "STORY";
            default:
                return "FREE TIME";
        }
    }

    /**
     * Date plate, 「补记 2/4」 and the close button; for a step its slanted kind tag and the title
     * (a correction adds what was recorded before), over a huge faint Latin word.
     */
    private void header(Plan.Step step) {
        FrameLayout head = new FrameLayout(this);
        LinearLayout column = Ui.column(this);
        LinearLayout row = Ui.row(this);
        if (slot >= 0 && rules != null) {
            DatePlate plate = new DatePlate(this, false);
            plate.bind(rules, slot);
            row.addView(plate, Ui.wrap());
        }
        row.addView(new View(this), Ui.weight(1));
        if (queueTarget >= 0 && queueTotal > 0) {
            int left = Slots.openBetween(rules, snap.observed.slot, queueTarget);
            int done = Math.max(0, queueTotal - left);
            TextView progress = Ui.tag(this, "补记 " + (done + 1) + "/" + (queueTotal + 1),
                Ui.alpha(Ui.col(this, R.color.p3r_cyan_400), 46), Ui.col(this, R.color.p3r_cyan_200));
            LinearLayout.LayoutParams pp = Ui.wrap();
            pp.rightMargin = Ui.dp(this, 4);
            row.addView(progress, pp);
        }
        ImageView close = new ImageView(this);
        close.setImageDrawable(Ui.tinted(this, R.drawable.ui_ic_close, Ui.col(this, R.color.p3r_ink_muted)));
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setContentDescription("关闭");
        close.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(this, R.color.ui_cyan_soft))
            .skew(Ui.dp(this, 6)));
        close.setOnClickListener(v -> {
            if (!busy && !celebrating) {
                close();
            }
        });
        int size = Ui.dp(this, Ui.TOUCH);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(size, size);
        cp.rightMargin = -Ui.dp(this, 8);
        row.addView(close, cp);
        column.addView(row, Ui.matchWrap());
        if (step != null) {
            StepKind kind = StepKind.of(step);
            LinearLayout tagRow = Ui.row(this);
            tagRow.addView(TodayPage.kindTag(this, kind), Ui.wrap());
            column.addView(tagRow, top(10));
            TextView title = Ui.mixed(this, SlotRows.title(rules, step), 26, Ui.col(this, R.color.p3r_white), true);
            column.addView(title, top(6));
            if (correction != null) {
                column.addView(Ui.mixed(this, "原来记的是：" + Outcomes.summary(rules, correctionStep, correction.outcome),
                    14, Ui.col(this, R.color.p3r_ink_muted), false), top(2));
            }
            head.setBackground(new WatermarkDrawable(watermarkWord(kind), Ui.numerals(this),
                Ui.alpha(Ui.col(this, R.color.p3r_cyan_300), 22), Ui.dp(this, 96), Ui.dp(this, 1.5f), -6f, 0.36f));
        }
        head.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(head, Ui.matchWrap());
    }

    /**
     * A change says so in a borderless cyan line; a failure in the ERROR look (red slab and 「!」,
     * as the app's message bar).
     */
    private View bannerView(String text, boolean error) {
        LinearLayout box = Ui.row(this);
        if (error) {
            box.setBackground(new SlantedPanelDrawable()
                .fill(Ui.alpha(Ui.col(this, R.color.p3r_danger), 36))
                .accent(Ui.col(this, R.color.p3r_danger), Ui.dp(this, 4))
                .skew(Ui.dp(this, 8)));
            Ui.pad(box, 14, 8, 12, 8);
            TextView mark = Ui.numeral(this, "!", 18, Ui.col(this, R.color.p3r_white));
            mark.setGravity(Gravity.CENTER);
            mark.setBackground(new SlantedPanelDrawable().fill(Ui.col(this, R.color.p3r_danger)).skew(Ui.dp(this, 4)));
            Ui.pad(mark, 9, 2, 10, 2);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(mark, Ui.wrap());
        } else {
            box.addView(Ui.icon(this, R.drawable.ui_ic_sync, Ui.col(this, R.color.p3r_cyan_400), 18));
        }
        TextView label = Ui.text(this, text, error ? 15 : 14, Ui.col(this, error ? R.color.p3r_white : R.color.p3r_cyan_200), true);
        LinearLayout.LayoutParams p = Ui.weight(1);
        p.leftMargin = Ui.dp(this, 10);
        box.addView(label, p);
        return box;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(this, dp);
        return p;
    }

    /**
     * The question on a slanted navy plate with a cyan blade, like the game's dialogue box; an
     * optional numeral kicker above it ("RANK 6 → 7") with one 「预计」 tag for a forecast.
     */
    private LinearLayout speech(CharSequence question, CharSequence kicker, boolean estimate) {
        LinearLayout plate = Ui.column(this);
        plate.setBackground(new SlantedPanelDrawable()
            .gradient(Ui.alpha(Ui.col(this, R.color.p3r_navy_950), 235), Ui.alpha(Ui.col(this, R.color.p3r_navy_800), 235))
            .accent(Ui.col(this, R.color.p3r_cyan_400), Ui.dp(this, 5))
            .cornerTick(Ui.alpha(Ui.col(this, R.color.p3r_cyan_400), 200), Ui.dp(this, 2))
            .skew(Ui.dp(this, 8))
            .cuts(0, 0, Ui.dp(this, 14), 0));
        Ui.pad(plate, 22, 12, 18, 14);
        if (kicker != null && kicker.length() > 0) {
            LinearLayout line = Ui.row(this);
            line.addView(Ui.mixed(this, kicker, 16, Ui.col(this, R.color.p3r_cyan_400), true), Ui.wrap());
            if (estimate) {
                TextView est = Ui.tag(this, "预计", Ui.alpha(Ui.col(this, R.color.p3r_cyan_200), 40),
                    Ui.col(this, R.color.p3r_cyan_200));
                LinearLayout.LayoutParams ep = Ui.wrap();
                ep.leftMargin = Ui.dp(this, 10);
                line.addView(est, ep);
            }
            plate.addView(line, Ui.matchWrap());
        }
        TextView q = Ui.mixed(this, question, 22, Ui.col(this, R.color.p3r_white), true);
        plate.addView(q, top(kicker != null && kicker.length() > 0 ? 2 : 0));
        return plate;
    }

    /** One answer plate (≥ 56 dp): the first of a panel is the white primary, the rest outlined. */
    private TextView choice(CharSequence label, boolean primary, Runnable action) {
        TextView plate = primary ? Ui.primary(this, label) : Ui.secondary(this, label);
        plate.setTextSize(17);
        plate.setMaxLines(1);
        plate.setMinHeight(Ui.dp(this, 56));
        plate.setMinimumHeight(Ui.dp(this, 56));
        plate.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            tapped = v;
            action.run();
        });
        return plate;
    }

    /** Answer plates stacked and staggered to the right, like the game's answer list. */
    private LinearLayout choices(TextView... plates) {
        LinearLayout list = Ui.column(this);
        int n = plates.length;
        for (int i = 0; i < n; i++) {
            LinearLayout.LayoutParams lp = Ui.matchWrap();
            lp.leftMargin = Ui.dp(this, i * 14);
            lp.rightMargin = Ui.dp(this, (n - 1 - i) * 14);
            lp.topMargin = i == 0 ? 0 : Ui.dp(this, 10);
            list.addView(plates[i], lp);
        }
        body.addView(list, top(20));
        return list;
    }

    /** A quiet link aligned to its edge. */
    private TextView link(String label, boolean end, Runnable action) {
        TextView link = Ui.quiet(this, label);
        link.setTextSize(15);
        link.setGravity((end ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
        Ui.pad(link, end ? 12 : 2, 8, end ? 2 : 12, 8);
        link.setOnClickListener(v -> {
            if (!busy) {
                action.run();
            }
        });
        return link;
    }

    /**
     * The panel's foot: 「做不了…」 at the left (when the step can fall through) and an optional
     * quiet link at the right ({@code moreLabel}: 「其他结果…」, 「结果不一样…」).
     */
    private void footer(Plan.Step step, String moreLabel, Runnable more) {
        LinearLayout row = Ui.row(this);
        StepKind kind = StepKind.of(step);
        if (kind.deviatable() && kind != StepKind.FREE) {
            TextView cannot = link(TodayPage.CANNOT_LABEL, false, () -> push(Intents.MODE_CANNOT, null, null, false));
            cannot.setContentDescription("做不了，选原因");
            row.addView(cannot, Ui.wrap());
        }
        row.addView(new View(this), Ui.weight(1));
        if (moreLabel != null) {
            row.addView(link(moreLabel, true, more), Ui.wrap());
        }
        if (row.getChildCount() > 1) {
            body.addView(row, top(12));
        }
    }

    private TextView closeButton(String label) {
        TextView ok = Ui.primary(this, label);
        ok.setOnClickListener(v -> close());
        return ok;
    }

    /** Two buttons side by side: a secondary one and the wider primary one. */
    private LinearLayout pair(TextView left, TextView right) {
        LinearLayout row = Ui.row(this);
        row.setBaselineAligned(false);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f);
        rp.leftMargin = Ui.dp(this, 14);
        row.addView(right, rp);
        return row;
    }

    /** A state with nothing to record: a title, an optional line and one button that closes. */
    private void showState(String title, String line, String button) {
        body.removeAllViews();
        header(null);
        body.addView(Ui.title(this, title), top(14));
        if (line != null) {
            body.addView(Ui.body(this, line), top(6));
        }
        body.addView(closeButton(button), top(22));
        enter();
    }

    private void showNotSetUp() {
        body.removeAllViews();
        body.addView(Ui.title(this, "还没开始设置"), top(14));
        body.addView(Ui.body(this, "填好进度，就能在这里记结果。"), top(6));
        TextView go = Ui.primary(this, "开始设置");
        go.setOnClickListener(v -> {
            Intent intent = new Intent(this, StateActivity.class);
            intent.putExtra(Intents.EXTRA_STATE_MODE, "onboarding");
            startActivity(intent);
            finish();
        });
        body.addView(go, top(22));
        enter();
    }

    /** Every slot up to Jan 31 is recorded: nothing left to record. */
    private void showFinished() {
        body.removeAllViews();
        body.addView(Ui.title(this, TodayPage.FINISHED_TITLE), top(14));
        body.addView(closeButton("好的"), top(22));
        enter();
    }

    /** The item was recorded meanwhile (e.g. from the app): close, or look at the record. */
    private void showAlreadyRecorded() {
        body.removeAllViews();
        header(null);
        body.addView(Ui.title(this, "这项已经记过了"), top(14));
        TextView history = Ui.secondary(this, HISTORY_LABEL);
        history.setOnClickListener(v -> {
            Intent intent = new Intent(this, HomeActivity.class);
            intent.putExtra(Intents.EXTRA_OPEN_PAGE, Intents.PAGE_HISTORY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });
        body.addView(pair(history, closeButton("好的")), top(22));
        enter();
    }

    /** The records or the game data could not be read: the service's own message and a retry. */
    private void showLoadFailed(String errorZh) {
        body.removeAllViews();
        body.addView(Ui.title(this, "记录没读出来"), top(14));
        body.addView(Ui.body(this, Messages.loadFailed(errorZh)), top(6));
        TextView close = Ui.secondary(this, "关闭");
        close.setOnClickListener(v -> close());
        TextView retry = Ui.primary(this, "重试");
        retry.setOnClickListener(v -> {
            showLoading();
            load();
        });
        body.addView(pair(close, retry), top(22));
    }

    /** The plan for this slot is still being made: say so briefly and look again shortly. */
    private void waitingForPlan() {
        body.addView(Ui.headline(this, "安排还在排，马上就好"), top(16));
        body.addView(new BusyStripe(this), top(14));
        body.addView(closeButton("好的"), top(22));
        body.postDelayed(retryPlan, PLAN_RETRY);
        enter();
    }

    /**
     * After 都做了 / 一项项对 / 前面都没做 toward a slot the store has already moved past (a story
     * slot such as 满月作战, skipped on its own): the catch-up is done, nothing is left here.
     */
    private void showCaughtUp() {
        Plan.Step step = step();
        header(step);
        body.addView(bannerView(banner != null ? banner : CAUGHT_UP, banner != null && bannerError), top(12));
        boolean locked = rules.lockOf(slot) != null || (step != null && StepKind.of(step) == StepKind.LOCKED);
        body.addView(Ui.body(this, locked ? "剧情时段，不用记。" : "这项也记好了。"), top(12));
        body.addView(closeButton("好的"), top(22));
        enter();
    }

    /** Banners after a catch-up. */
    static final String CAUGHT_UP = "前面的都记好了";
    static final String CAUGHT_UP_SKIPPED = "前面的记为没做";

    // ------------------------------------------------------------------ shared panel parts

    /**
     * The 「顺便」 lines with an inline check at their side: on a result panel 「也做了」 (checked),
     * on the 做不了 panel 「做了」 (unchecked); a correction shows the record's answer.
     */
    private void addSide(Plan.Step step, boolean doneByDefault, String label) {
        if (!StepViews.hasSide(step)) {
            return;
        }
        boolean preset = restoreSide != null ? restoreSide
            : carriedSide != null ? carriedSide
            : correction == null ? doneByDefault : correction.outcome.sideDone;
        LinearLayout row = Ui.row(this);
        row.addView(StepViews.sideBlock(this, step, 15), Ui.weight(1));
        sideRow = new CheckRow(this, label, preset);
        Ui.pad(sideRow, 10, 0, 4, 0);
        LinearLayout.LayoutParams cp = Ui.wrap();
        cp.leftMargin = Ui.dp(this, 6);
        row.addView(sideRow, cp);
        body.addView(row, top(14));
    }

    /** Applies the side answer to an outcome (side steps count as done unless unchecked). */
    private Outcome withSide(Plan.Step step, Outcome outcome) {
        if (!StepViews.hasSide(step)) {
            return outcome;
        }
        if (sideRow != null) {
            outcome.sideDone = sideRow.isChecked();
        } else if (carriedSide != null) {
            outcome.sideDone = carriedSide;
        }
        return outcome;
    }

    /** The how-to, folded to one row, for the steps where it helps to look back at what was done. */
    private void addHowTo(Plan.Step step) {
        if (StepViews.hasHowTo(step)) {
            body.addView(StepViews.howToSection(this, step.detailZh, true, false), top(10));
        }
    }

    /** Index of the link a meeting / note is about, or -1. */
    private int linkOf(Plan.Step step) {
        Action action = Action.parse(rules, step.action);
        if (action != null && (action.type == Action.Type.MEET || action.type == Action.Type.INVITE || action.type == Action.Type.NOTE)
            && action.target >= 0 && action.target < rules.links.length) {
            return action.target;
        }
        Rules.Link link = step.entity == null ? null : rules.link(step.entity);
        return link == null ? -1 : link.idx;
    }

    /**
     * RANK before the meeting: the recorded rank (a record is only accepted at the observed slot),
     * so a plan that lags behind never shifts the buttons.
     */
    private int rankBefore(Plan.Step step) {
        int link = linkOf(step);
        if (link >= 0 && link < snap.observed.rank.length) {
            return snap.observed.rank[link];
        }
        return Math.max(0, step.predicted.rankFrom);
    }

    /** The visible prediction of this meeting still describes the recorded state. */
    private boolean rankPredictionCurrent(Plan.Step step, int from) {
        return predictionCurrent() && (step.predicted.rankFrom < 0 || step.predicted.rankFrom == from);
    }

    /** The compact 「下次就能升级」 check of a meeting (no card, no caption). */
    private CheckRow readyCheck() {
        CheckRow cue = new CheckRow(this, READY_CUE, correction != null && correction.outcome.readyCue);
        body.addView(cue, top(10));
        return cue;
    }

    // ------------------------------------------------------------------ result panels

    private void resultPanel(Plan.Step step) {
        StepKind kind = StepKind.of(step);
        switch (kind) {
            case SOCIAL_RANK_UP:
                socialRankUp(step);
                break;
            case SOCIAL_MEET:
                if (step.action != null && step.action.startsWith("invite:")) invitePanel(step);
                else socialMeet(step);
                break;
            case UNLOCK:
                unlockPanel(step);
                break;
            case NOTE:
                notePanel(step);
                break;
            case EPISODE:
                episodePanel(step);
                break;
            case DORM:
                dormPanel(step);
                break;
            case STAT:
            case JOB:
                statJobPanel(step);
                break;
            case TOWER:
                // A correction never records a new visit: a tower night is edited on the 做不了 panel.
                if (correction != null) cannotPanel(step);
                else towerPanel(step);
                break;
            case LOCKED:
                lockedPanel(step);
                break;
            default:
                freePanel(step);
                break;
        }
    }

    private void socialRankUp(Plan.Step step) {
        int from = rankBefore(step);
        int to = Math.min(10, from + 1);
        boolean current = rankPredictionCurrent(step, from);
        body.addView(speech("升到 " + Zh.rank(to) + " 了吗？", Zh.rankStep(from, to), step.estimate), top(16));
        addSide(step, true, SIDE_DONE);
        // A matching current prediction is recorded as such (the plan stays valid); otherwise the
        // player's answer is recorded explicitly.
        TextView up = choice("升到 " + Zh.rank(to) + " 了", true,
            () -> submit(step, current ? Outcomes.asPredicted(step) : Outcomes.rankUp(to)));
        // 没升级 asks next whether the game showed the 「下次就能升级」 cue.
        TextView no = choice("没升级", false, () -> push(mode, substitute, SUB_CUE, true));
        choices(up, no);
        LinearLayout more = otherResults(step, from);
        footer(step, OTHER_RESULTS, () -> reveal(more));
        body.addView(more, top(4));
    }

    /** 没升级 → 「有没有提示「下次就能升级」？」. */
    private void cuePanel(Plan.Step step) {
        int from = rankBefore(step);
        body.addView(speech(CUE_QUESTION, Zh.rank(from) + " · 没升级", false), top(16));
        TextView shown = choice("提示了", true, () -> submit(step, Outcomes.noRankUp(from, true)));
        TextView not = choice("没有", false, () -> submit(step, Outcomes.noRankUp(from, false)));
        boolean recordedCue = correction != null && correction.outcome.readyCue;
        if (correction != null && !recordedCue) {
            choices(not, shown);
        } else {
            choices(shown, not);
        }
    }

    /** Shows a folded part of the panel, sliding in. */
    private void reveal(View view) {
        boolean open = view.getVisibility() != View.VISIBLE;
        view.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            Motion.enter(view, 0);
            scroll.post(() -> scroll.smoothScrollTo(0, view.getBottom()));
        }
    }

    /** 「其他结果…」: the rank the game shows now (no chip preselected), and the story hold. */
    private LinearLayout otherResults(Plan.Step step, int from) {
        LinearLayout box = Ui.column(this);
        box.setVisibility(View.GONE);
        box.addView(Ui.text(this, "游戏里现在是 RANK 几？", 15, Ui.col(this, R.color.p3r_white), true), top(8));
        FlowLayout chips = new FlowLayout(this, 10, 10);
        for (int r = Math.max(1, from - 1); r <= Math.min(10, from + 2); r++) {
            final int rank = r;
            TextView chip = Ui.chip(this, Zh.rank(r), false);
            chip.setMinHeight(Ui.dp(this, Ui.TOUCH));
            chip.setMinimumHeight(Ui.dp(this, Ui.TOUCH));
            chip.setOnClickListener(v -> {
                if (!busy) {
                    tapped = v;
                    submit(step, Outcomes.observedRank(from, rank, false));
                }
            });
            chips.addView(chip);
        }
        box.addView(chips, top(8));
        box.addView(holdLink(step), top(6));
        return box;
    }

    private TextView holdLink(Plan.Step step) {
        TextView hold = link(HOLD, false, () -> submit(step, Outcomes.hold()));
        hold.setTextColor(Ui.col(this, R.color.p3r_ice_100));
        return hold;
    }

    private void socialMeet(Plan.Step step) {
        int from = rankBefore(step);
        int to = Math.min(10, from + 1);
        boolean current = rankPredictionCurrent(step, from);
        int link = linkOf(step);
        String who = link >= 0 ? rules.links[link].personZh : Outcomes.shortName(rules, step);
        body.addView(speech("和" + who + "见面了吗？", Zh.rank(from), false), top(16));
        addSide(step, true, SIDE_DONE);
        CheckRow ready = readyCheck();
        TextView met = choice("见面了", true, () -> submit(step, current ? Outcomes.met(step, ready.isChecked())
            : Outcomes.noRankUp(from, ready.isChecked())));
        TextView up = choice("升到 " + Zh.rank(to) + " 了", false, () -> submit(step, Outcomes.rankUp(to)));
        choices(met, up);
        LinearLayout more = Ui.column(this);
        more.setVisibility(View.GONE);
        more.addView(holdLink(step), top(4));
        footer(step, OTHER_RESULTS, () -> reveal(more));
        body.addView(more, top(2));
    }

    /** Only an actual received SMS can be recorded here; no rank-up scene is inferred. */
    private void invitePanel(Plan.Step step) {
        body.addView(speech("赴约了吗？", "休息日邀约", false), top(16));
        addSide(step, true, SIDE_DONE);
        CheckRow ready = readyCheck();
        TextView attended = choice("赴约了", true, () -> submit(step, Outcomes.invited(ready.isChecked())));
        TextView not = choice("没赴约", false, () -> submit(step, Outcomes.notDone()));
        choices(attended, not);
        footer(step, null, null);
    }

    /** "解锁恶魔社群前的准备（第 1 步，共 3 步）" → 「第 1/3 步」. */
    private static final Pattern STEP_OF = Pattern.compile("第\\s*(\\d+)\\s*步\\s*[，,]\\s*共\\s*(\\d+)\\s*步");

    /** The unlock step's progress for the kicker: 「第 1/3 步」, 「完成后就解锁了」, or null. */
    static String unlockProgress(String why) {
        if (why == null) {
            return null;
        }
        Matcher m = STEP_OF.matcher(why);
        if (m.find()) {
            return "第 " + m.group(1) + "/" + m.group(2) + " 步";
        }
        return why.contains("解锁了") ? "完成后就解锁了" : null;
    }

    private void unlockPanel(Plan.Step step) {
        body.addView(speech("这一步做完了吗？", unlockProgress(step.whyZh), false), top(16));
        if (step.subtitleZh != null && !step.subtitleZh.trim().isEmpty()) {
            body.addView(Ui.mixed(this, step.subtitleZh.trim(), 16, Ui.col(this, R.color.p3r_cyan_300), true), top(10));
        }
        addSide(step, true, SIDE_DONE);
        addHowTo(step);
        TextView done = choice("做完了", true, () -> submit(step, Outcomes.asPredicted(step, predictionCurrent())));
        TextView notDone = choice("没做完", false, () -> submit(step, Outcomes.notDone()));
        choices(done, notDone);
        footer(step, null, null);
    }

    /** 结缘网站的字条: bought at the club and used on the dorm PC for one established link. */
    private void notePanel(Plan.Step step) {
        int link = linkOf(step);
        String who = link >= 0 ? rules.links[link].personZh : Outcomes.shortName(rules, step);
        body.addView(speech("给" + who + "用上了吗？", null, false), top(16));
        addSide(step, true, SIDE_DONE);
        addHowTo(step);
        TextView used = choice("用了", true, () -> submit(step, Outcomes.asPredicted(step, false)));
        TextView not = choice("没用成", false, () -> submit(step, Outcomes.notDone()));
        choices(used, not);
        footer(step, null, null);
    }

    /** The next 羁绊剧情 of an opted-in track. */
    private void episodePanel(Plan.Step step) {
        Rules.Episode track = Outcomes.episodeOf(rules, step);
        body.addView(speech(episodeQuestion(track, snap.observed), null, false), top(16));
        String reward = episodeNote(track);
        if (!reward.isEmpty()) {
            body.addView(Ui.mixed(this, reward, 14, Ui.col(this, R.color.p3r_ink_muted), false), top(10));
        }
        addSide(step, true, SIDE_DONE);
        addHowTo(step);
        TextView watched = choice("看完了", true, () -> submit(step, Outcomes.asPredicted(step, false)));
        TextView missed = choice("没看成", false, () -> submit(step, Outcomes.notDone()));
        choices(watched, missed);
        footer(step, null, null);
    }

    /** Number of the next episode of {@code track} for the recorded state (1..count). */
    static int nextEpisode(Rules.Episode track, SimState observed) {
        int watched = observed != null && track.idx < observed.episodes.length ? observed.episodes[track.idx] : 0;
        return Math.max(1, Math.min(track.count, watched + 1));
    }

    /**
     * "看完第 2 话了吗？" — the sheet's title already says "羁绊剧情 · 伊织顺平", so the question
     * only names the episode.
     */
    static String episodeQuestion(Rules.Episode track, SimState observed) {
        if (track == null) {
            return "这一话看完了吗？";
        }
        return "看完第 " + nextEpisode(track, observed) + " 话了吗？";
    }

    /**
     * The track's reward as one sentence ("每一话都有小奖励…，看完第 5 话可以合体人格面具「苏尔特尔」。"),
     * or "" when the pack has none.
     */
    static String episodeNote(Rules.Episode track) {
        return track == null ? "" : Zh.sentence(track.rewardZh);
    }

    private void statJobPanel(Plan.Step step) {
        StepKind kind = StepKind.of(step);
        boolean different = SUB_DIFFERENT.equals(sub);
        String expected = expectedText(step);
        body.addView(speech("做完了吗？", expected, step.estimate && expected != null), top(16));
        addSide(step, true, SIDE_DONE);
        if (!different) {
            boolean current = predictionCurrent();
            TextView asPlanned = choice("做完了", true, () -> submit(step, Outcomes.asPredicted(step, current)));
            asPlanned.setContentDescription("做完了" + (expected == null ? "" : "：" + expected));
            choices(asPlanned);
            footer(step, "结果不一样…", () -> push(mode, substitute, SUB_DIFFERENT, true));
            return;
        }
        SimState obs = snap.observed;
        int[] chosen = {-1, -1, -1};
        long[] balance = {-1};
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            if (step.predicted.statDelta[i] == 0 && kind == StepKind.JOB) {
                continue;
            }
            if (step.predicted.statDelta[i] == 0 && hasAnyStatDelta(step)) {
                continue;
            }
            int now = rules.stats[i].levelFor(obs.statPts[i]);
            body.addView(Ui.text(this, rules.stats[i].zh + "现在几级？", 15, Ui.col(this, R.color.p3r_white), true), top(16));
            List<String> labels = new ArrayList<>();
            List<Integer> values = new ArrayList<>();
            for (int lv = correction == null ? now : 1; lv <= (correction == null ? Math.min(6, now + 1) : 6); lv++) {
                labels.add(Zh.lv(lv));
                values.add(lv);
            }
            SegmentedControl seg = new SegmentedControl(this, labels);
            final int stat = i;
            // Preselect the forecast level (the prediction), never silently "no level-up".
            int predicted = predictionCurrent() ? step.predicted.statLvAfter[i] : -1;
            if (correction != null && correction.outcome.statLv[i] > 0) predicted = correction.outcome.statLv[i];
            int preset = predicted > 0 && values.contains(predicted) ? values.indexOf(predicted) : 0;
            seg.setSelected(preset);
            chosen[stat] = values.get(preset);
            seg.setListener(index -> chosen[stat] = values.get(index));
            body.addView(seg, top(8));
        }
        if (kind == StepKind.JOB) {
            Long initial = correction != null && correction.outcome.cashBalance >= 0
                ? Long.valueOf(correction.outcome.cashBalance) : obs.cashExact ? Long.valueOf(obs.cash + step.predicted.cashDelta) : null;
            body.addView(cashField("打工后的现金", initial, value -> balance[0] = value), top(16));
        }
        TextView save = choice("保存", true, () -> submit(step, Outcomes.different(chosen, balance[0])));
        choices(save);
        footer(step, null, null);
    }

    /** Receives a typed amount. */
    private interface AmountListener {
        void onAmount(long value);
    }

    /**
     * A slanted amount field ("打工后的现金 · 21,500 日元", prefilled with the forecast); tapped, it
     * turns into the inline keypad, so a different amount takes no extra 确定.
     */
    private View cashField(String label, Long initial, AmountListener listener) {
        LinearLayout box = Ui.column(this);
        TextView caption = Ui.text(this, label, 13, Ui.col(this, R.color.p3r_ink_muted), true);
        box.addView(caption, Ui.matchWrap());
        LinearLayout field = Ui.row(this);
        field.setMinimumHeight(Ui.dp(this, 64));
        field.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(this, R.color.p3r_navy_950), 170))
            .pressedFill(Ui.col(this, R.color.p3r_navy_700))
            .stroke(Ui.col(this, R.color.p3r_cyan_400), Ui.dp(this, 1.5f))
            .skew(Ui.dp(this, 10)));
        Ui.pad(field, 20, 6, 20, 6);
        TextView amount = Ui.text(this, initial == null ? "—" : Ui.money(this, Zh.number(initial)), 26,
            Ui.col(this, initial == null ? R.color.p3r_ink_muted : R.color.p3r_white), true);
        amount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        field.addView(amount, Ui.weight(1));
        field.setClickable(true);
        field.setFocusable(true);
        field.setContentDescription(label + "，" + (initial == null ? "未填" : Zh.yen(initial)));
        LinearLayout.LayoutParams fp = Ui.matchWrap();
        fp.topMargin = Ui.dp(this, 6);
        box.addView(field, fp);
        // The caption opens the keypad too.
        caption.setOnClickListener(v -> {
            if (field.getVisibility() == View.VISIBLE) {
                field.performClick();
            }
        });
        field.setOnClickListener(v -> {
            CashPad.Options options = new CashPad.Options();
            options.title = label;
            options.initial = initial;
            options.allowUnknown = false;
            View pad = CashPad.inline(this, options, value -> {
                if (value != null) {
                    listener.onAmount(value);
                }
            });
            field.setVisibility(View.GONE);
            box.addView(pad, Ui.matchWrap());
            Motion.enter(pad, 0);
        });
        return box;
    }

    private void dormPanel(Plan.Step step) {
        if (hasAnyStatDelta(step)) {
            statJobPanel(step);
            return;
        }
        body.addView(speech("做完了吗？", null, false), top(16));
        addSide(step, true, SIDE_DONE);
        addHowTo(step);
        TextView done = choice("做完了", true, () -> submit(step, Outcomes.asPredicted(step, predictionCurrent())));
        TextView notDone = choice("没做成", false, () -> submit(step, Outcomes.notDone()));
        choices(done, notDone);
        footer(step, null, null);
    }

    private static boolean hasAnyStatDelta(Plan.Step step) {
        for (int d : step.predicted.statDelta) {
            if (d != 0) {
                return true;
            }
        }
        return false;
    }

    /** "学力 +2" / "+2,500 日元 · 魅力 +1", or null when nothing is predicted. */
    private String expectedText(Plan.Step step) {
        String sub = step.subtitleZh == null ? "" : step.subtitleZh.trim();
        if (sub.startsWith("≈")) {
            sub = sub.substring(1).trim();
        }
        if (!sub.isEmpty()) {
            return sub;
        }
        List<String> parts = new ArrayList<>();
        if (step.predicted.cashDelta != 0) {
            parts.add(Zh.yenDelta(step.predicted.cashDelta));
        }
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            if (step.predicted.statDelta[i] != 0) {
                parts.add(rules.stats[i].zh + " +" + step.predicted.statDelta[i]);
            }
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private void towerPanel(Plan.Step step) {
        int block = -1;
        if (step.action != null && step.action.startsWith("tower:")) {
            try {
                block = Integer.parseInt(step.action.substring(6));
            } catch (NumberFormatException ignored) {
                block = -1;
            }
        }
        Profile profile = snap.profile == null ? new Profile() : snap.profile;
        SimState obs = snap.observed;
        boolean visitKnown = block >= 0 && block < obs.towerVisits.length && block < rules.blocks.length;
        // The block, and the visit once, as a numeral chip ("第 2 次 · 最多 4 次").
        String sub = visitKnown ? withoutVisit(SlotRows.subtitle(step)) : SlotRows.subtitle(step);
        if (TowerPanel.activeRescue(rules, obs, Rules.dayOf(step.slot)) != null) {
            // The rescue question is right below: the plan's 「救出舞子」 is not said twice.
            sub = withoutRescue(rules, sub);
        }
        LinearLayout kicker = Ui.row(this);
        if (!sub.isEmpty()) {
            kicker.addView(Ui.mixed(this, sub, 16, Ui.col(this, R.color.p3r_cyan_300), true), Ui.weight(1));
        } else {
            kicker.addView(new View(this), Ui.weight(1));
        }
        if (visitKnown) {
            String visits = "第 " + (obs.towerVisits[block] + 1) + " 次 · 最多 " + profile.capFor(rules.blocks[block].id) + " 次";
            TextView chip = Ui.tag(this, visits, Ui.alpha(Ui.col(this, R.color.p3r_cyan_400), 40),
                Ui.col(this, R.color.p3r_cyan_200));
            LinearLayout.LayoutParams vp = Ui.wrap();
            vp.leftMargin = Ui.dp(this, 8);
            kicker.addView(chip, vp);
        }
        body.addView(kicker, top(12));
        boolean sideRecordable = Outcomes.towerSideSupported();
        if (sideRecordable) {
            addSide(step, true, SIDE_DONE);
        } else if (StepViews.hasSide(step)) {
            body.addView(StepViews.sideBlock(this, step, 15), top(12));
        }
        towerPanel = new TowerPanel(this, rules, obs, step.slot, block, null, false, rememberedLevel,
            predictsRescue(rules, step), new TowerPanel.Listener() {
                @Override
                public void onSave(PlannerApi.TowerReturn tower, String summaryZh) {
                    String summary = summaryZh;
                    if (sideRow != null) {
                        Outcomes.setTowerSideDone(tower, sideRow.isChecked());
                        if (!sideRow.isChecked()) {
                            summary += " · 顺便的事没做";
                        }
                    }
                    tapped = towerPanel == null ? null : towerPanel.saveButton();
                    submitTower(tower, summary);
                }

                @Override
                public void onCannot() {
                    if (!busy) {
                        push(Intents.MODE_CANNOT, null, null, false);
                    }
                }
            });
        if (pendingTowerState != null) {
            towerPanel.restoreState(pendingTowerState);
            pendingTowerState = null;
        }
        body.addView(towerPanel, top(18));
    }

    /** "提齐亚（69–117F）· 本区块第 1 次 · 救出舞子" without its "本区块第 1 次" part. */
    static String withoutVisit(String subtitle) {
        String out = subtitle.replaceAll("\\s*·?\\s*本区块第\\s*\\d+\\s*次", "").replace("） ·", "）·").trim();
        return out.startsWith("·") ? out.substring(1).trim() : out;
    }

    /** "提齐亚（69–117F）· 救出舞子" without its "救出舞子" part. */
    static String withoutRescue(Rules rules, String subtitle) {
        String out = subtitle;
        for (Rules.Rescue r : rules.rescues) {
            if (r.zh != null && !r.zh.isEmpty()) {
                out = out.replaceAll("\\s*·?\\s*救出" + Pattern.quote(r.zh), "");
            }
        }
        out = out.trim();
        return out.startsWith("·") ? out.substring(1).trim() : out;
    }

    /**
     * True when the plan step expects the missing person to be rescued on this visit (the
     * engine's "rescue_on_visit" assumption, or a subtitle "… · 救出舞子").
     */
    static boolean predictsRescue(Rules rules, Plan.Step step) {
        if (step == null) {
            return false;
        }
        if (step.assumptions != null
            && (step.assumptions.contains("rescue_on_visit") || step.assumptions.contains("rescue"))) {
            return true;
        }
        String sub = step.subtitleZh == null ? "" : step.subtitleZh;
        for (Rules.Rescue r : rules.rescues) {
            if (r.zh != null && sub.contains("救出" + r.zh)) {
                return true;
            }
        }
        return false;
    }

    private void lockedPanel(Plan.Step step) {
        body.addView(Ui.mixed(this, TodayPage.lockedNote(step), 15, Ui.col(this, R.color.p3r_ink_muted), false), top(6));
        TextView go = choice("继续", true, () -> submit(step, Outcomes.asPredicted(step)));
        choices(go);
    }

    private void freePanel(Plan.Step step) {
        // A free slot can still carry "顺便" side steps: ask about them like on every other panel.
        addSide(step, true, SIDE_DONE);
        TextView go = choice("继续", true, () -> submit(step, Outcomes.asPredicted(step)));
        if (correction != null) {
            choices(go);
            return;
        }
        TextView other = choice("做了别的…", false, this::switchToOther);
        choices(go, other);
    }

    private void switchToOther() {
        push(Intents.MODE_OTHER, null, null, false);
    }

    // ------------------------------------------------------------------ 做不了 / 改做别的

    /** The reasons that can apply to {@code step}, in display order. */
    static List<String> reasonsFor(Plan.Step step) {
        StepKind kind = StepKind.of(step);
        List<String> out = new ArrayList<>();
        boolean person = kind.social() || kind == StepKind.UNLOCK || kind == StepKind.EPISODE || kind == StepKind.DORM;
        boolean venue = kind == StepKind.STAT || kind == StepKind.JOB || kind == StepKind.NOTE;
        String subtitle = step == null || step.subtitleZh == null ? "" : step.subtitleZh;
        boolean costs = step != null && step.predicted != null && step.predicted.cashDelta < 0 || subtitle.contains("花费");
        for (String reason : Outcomes.SKIP_REASONS.keySet()) {
            boolean shown;
            switch (reason) {
                case "absent":
                    shown = person;
                    break;
                case "closed":
                    shown = venue;
                    break;
                case "money":
                    shown = costs;
                    break;
                default:
                    shown = true;
                    break;
            }
            if (shown) {
                out.add(reason);
            }
        }
        return out;
    }

    private void cannotPanel(Plan.Step step) {
        body.addView(speech("怎么没做成？", null, false), top(16));
        // Side steps of a slot that fell through are only counted when the player says so.
        addSide(step, false, SIDE_DONE_CANNOT);
        FlowLayout reasons = new FlowLayout(this, 10, 10);
        for (String reason : reasonsFor(step)) {
            TextView chip = Ui.chip(this, Outcomes.SKIP_REASONS.get(reason), false);
            chip.setTextSize(15);
            chip.setMinHeight(Ui.dp(this, Ui.TOUCH));
            chip.setMinimumHeight(Ui.dp(this, Ui.TOUCH));
            Ui.pad(chip, 18, 6, 18, 6);
            chip.setOnClickListener(v -> {
                if (!busy) {
                    tapped = v;
                    onSkipReason(step, reason);
                }
            });
            reasons.addView(chip);
        }
        body.addView(reasons, top(18));
        if (correction == null) {
            LinearLayout row = Ui.row(this);
            row.addView(link(OTHER_INSTEAD, false, this::switchToOther), Ui.wrap());
            body.addView(row, top(14));
        } else if (StepKind.of(step) == StepKind.TOWER && missed(correction.outcome)) {
            // The night counts as a visit after all; the engine works it out from the state before.
            choices(choice(WENT_AFTER_ALL, false, () -> submit(step, Outcomes.asPredicted(step, false))));
        }
    }

    private static boolean missed(Outcome outcome) {
        return outcome.code == Outcome.Code.SKIPPED || outcome.code == Outcome.Code.NOT_DONE;
    }

    private void onSkipReason(Plan.Step step, String reason) {
        if ("money".equals(reason)) {
            CashPad.Options options = new CashPad.Options();
            options.title = "现在有多少钱？";
            options.initial = snap.observed.cashExact ? snap.observed.cash : null;
            CashPad.show(this, options, value -> submit(step, Outcomes.skipped(reason, value == null ? -1 : value)));
            return;
        }
        if (correction == null && "unwilling".equals(reason) && "act:".regionMatches(0, step.action, 0, 4)) {
            push(mode, substitute, SUB_EXCLUDE, true);
            return;
        }
        submit(step, Outcomes.skipped(reason, -1));
    }

    /** 「以后还排「X」吗？」 after 不想做 on a training/job step. */
    private void excludePanel(Plan.Step step) {
        body.addView(speech("以后还排「" + step.titleZh + "」吗？", null, false), top(16));
        TextView once = choice("只是这次", true, () -> submit(step, Outcomes.skipped("unwilling", -1)));
        TextView never = choice("以后别排了", false, () -> {
            String id = step.action.substring(4);
            submitThen(step, Outcomes.skipped("unwilling", -1), () -> excludeActivity(id));
        });
        choices(once, never);
    }

    private void excludeActivity(String activityId) {
        Profile edited = (snap.profile == null ? new Profile() : snap.profile).copy();
        edited.excludedActivities = SettingsPage.with(edited.excludedActivities, activityId);
        PlannerApi target = api;
        long rev = snap.profileRevision;
        String id = CommandIds.of("exclude", rev, activityId);
        Async.run(this, () -> {
            PlannerApi.Snapshot latest = target.snapshot();
            return target.saveProfile(id, latest.profileRevision, edited);
        }, receipt -> continueOrFinish(), error -> continueOrFinish());
    }

    /** After a save: the next item of a running 一项项对 queue, else close the sheet. */
    private void continueOrFinish() {
        if (queueTarget >= 0) {
            substitute = null;
            sub = null;
            setBanner(null, false);
            mode = Intents.MODE_RESULT;
            transition = Transition.FORWARD;
            reloadAndRoute();
            return;
        }
        finishAfterSave();
    }

    private void otherPanel(Plan.Step planned) {
        body.addView(speech("做了什么？", null, false), top(16));
        List<Plan.Step> list = alternatives == null ? new ArrayList<>() : alternatives;
        if (list.isEmpty()) {
            body.addView(Ui.body(this, "这时段没别的可做。"), top(14));
        }
        LinearLayout rows = Ui.column(this);
        String lastKind = null;
        for (Plan.Step alt : list) {
            String group = groupOf(alt);
            if (!group.equals(lastKind)) {
                lastKind = group;
                TextView kicker = Ui.text(this, group, 13, Ui.col(this, R.color.p3r_cyan_400), true);
                Ui.pad(kicker, 2, 0, 0, 2);
                LinearLayout.LayoutParams kp = Ui.matchWrap();
                kp.topMargin = Ui.dp(this, rows.getChildCount() == 0 ? 0 : 16);
                rows.addView(kicker, kp);
            } else {
                View rule = Ui.fadingRule(this);
                rows.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, Ui.dp(this, 1))));
            }
            rows.addView(alternativeRow(alt, planned), Ui.matchWrap());
        }
        body.addView(rows, top(14));
        LinearLayout foot = Ui.row(this);
        foot.addView(link(NOTHING_ELSE, false, () -> submit(planned, Outcomes.withSide(
            Outcomes.skipped(Outcomes.REASON_OTHER, -1), false))), Ui.wrap());
        body.addView(foot, top(10));
        if (transition == Transition.FORWARD && Ui.animationsEnabled()) {
            // The list itself cascades in, one row after another.
            transition = Transition.NONE;
            Motion.cascade(rows);
        }
    }

    private static String groupOf(Plan.Step step) {
        switch (StepKind.of(step)) {
            case SOCIAL_RANK_UP:
            case SOCIAL_MEET:
            case UNLOCK:
            case NOTE:
                return "社群";
            case STAT:
                return "个人能力值";
            case JOB:
                return "打工";
            case TOWER:
                return "探索塔尔塔罗斯";
            case EPISODE:
                return "羁绊剧情";
            case DORM:
                return "宿舍活动";
            default:
                return "其他";
        }
    }

    /** A borderless row: kind icon, title, cyan subtitle and 「原安排」 for the planned step. */
    private View alternativeRow(Plan.Step alt, Plan.Step planned) {
        LinearLayout row = Ui.row(this);
        boolean original = planned != null && alt.action.equals(planned.action);
        row.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(this, R.color.ui_cyan_soft))
            .skew(Ui.dp(this, 8)));
        row.setMinimumHeight(Ui.dp(this, 56));
        Ui.pad(row, 4, 8, 6, 8);
        row.addView(Ui.icon(this, StepKind.of(alt).iconRes, Ui.col(this, R.color.p3r_cyan_400), 20));
        LinearLayout texts = Ui.column(this);
        texts.addView(Ui.mixed(this, SlotRows.title(rules, alt), 16, Ui.col(this, R.color.p3r_white), true));
        String sub = SlotRows.subtitle(alt);
        if (!sub.isEmpty()) {
            texts.addView(Ui.mixed(this, sub, 13, Ui.col(this, R.color.p3r_cyan_300), false));
        }
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(this, 12);
        row.addView(texts, tp);
        if (original) {
            TextView tag = Ui.tag(this, "原安排", Ui.col(this, R.color.p3r_cyan_400), Ui.col(this, R.color.p3r_navy_950));
            LinearLayout.LayoutParams gp = Ui.wrap();
            gp.leftMargin = Ui.dp(this, 8);
            row.addView(tag, gp);
        }
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(SlotRows.title(rules, alt) + (sub.isEmpty() ? "" : "，" + sub)
            + (original ? "，原安排" : ""));
        row.setOnClickListener(v -> {
            if (!busy) {
                push(Intents.MODE_OTHER, alt, null, false);
            }
        });
        return row;
    }

    // ------------------------------------------------------------------ catch-up

    /** Open slots before {@code target} that still need a record, in order (locked slots excluded). */
    private List<Plan.Step> pendingBefore(int target) {
        List<Plan.Step> pending = new ArrayList<>();
        Plan plan = snap.plan;
        for (int s = Slots.firstOpen(rules, snap.observed.slot); s < target; s = Slots.firstOpen(rules, s + 1)) {
            Plan.Step p = plan == null ? null : plan.at(s);
            if (p != null && !"locked".equals(p.kind)) {
                pending.add(p);
            }
        }
        return pending;
    }

    /** Starts the one-by-one queue toward {@code target}: the earliest open slot shows first. */
    private void startQueue(int target, int total) {
        queueTarget = target;
        queueTotal = total;
        slot = snap.observed.slot;
        Plan.Step first = snap.plan == null ? null : snap.plan.at(slot);
        actionKey = first == null ? null : first.action;
    }

    private void catchUpPanel(Plan.Step target) {
        List<Plan.Step> pending = pendingBefore(target.slot);
        int count = pending.size();
        body.addView(speech("前面 " + count + " 项也照安排做了吗？", null, false), top(16));
        LinearLayout list = Ui.column(this);
        int shown = 0;
        for (Plan.Step p : pending) {
            if (shown == 4) {
                list.addView(Ui.mixed(this, "…还有 " + (count - shown) + " 项", 14, Ui.col(this, R.color.p3r_ink_muted), false),
                    top(6));
                break;
            }
            if (shown > 0) {
                list.addView(Ui.fadingRule(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, Ui.dp(this, 1))));
            }
            TextView line = Ui.mixed(this, Zh.slot(rules, p.slot) + " · "
                + SlotRows.title(rules, p), 15, Ui.col(this, R.color.p3r_ice_100), false);
            Ui.pad(line, 2, 8, 0, 8);
            list.addView(line);
            shown++;
        }
        body.addView(list, top(12));
        TextView all = choice("都做了", true, () -> catchUp(target.slot, true));
        TextView each = choice("一项项对", false, () -> {
            startQueue(target.slot, count);
            transition = Transition.FORWARD;
            render();
        });
        choices(all, each);
        LinearLayout foot = Ui.row(this);
        TextView none = link("前面都没做", false, () -> catchUp(target.slot, false));
        none.setContentDescription("前面都没做，从这一项开始");
        foot.addView(none, Ui.wrap());
        body.addView(foot, top(10));
    }

    /**
     * Records every open slot before {@code target} as planned (or as skipped), then shows the
     * target. Locked slots are never sent: the store moves past them on its own, so the loop
     * follows the store's observed slot after each record.
     */
    private void catchUp(int target, boolean asPlanned) {
        setBusy(true);
        PlannerApi a = api;
        Rules r = rules;
        Plan plan = snap.plan;
        int from = snap.observed.slot;
        long startRev = snap.stateRevision;
        boolean current = snap.planCurrent;
        String planId = plan.planId;
        Async.run(this, () -> {
            long rev = startRev;
            int s = Slots.firstOpen(r, from);
            while (s < target) {
                Plan.Step p = plan.at(s);
                if (p == null) {
                    break;
                }
                if ("locked".equals(p.kind)) {
                    s = Slots.firstOpen(r, s + 1);
                    continue;
                }
                Outcome o = asPlanned || !p.productive() ? Outcomes.asPredicted(p, current)
                    : Outcomes.withSide(Outcomes.skipped(Outcomes.REASON_OTHER, -1), false);
                String id = CommandIds.of("catchup", rev, planId, s, p.action, outcomeKey(o));
                PlannerApi.Receipt receipt = a.recordOutcome(id, rev, s, p.action, o);
                if (!receipt.ok) {
                    return receipt;
                }
                rev = receipt.stateRevision;
                PlannerApi.Snapshot now = a.snapshot();
                int next = Slots.firstOpen(r, s + 1);
                s = now.observed == null ? next : Math.max(next, now.observed.slot);
            }
            PlannerApi.Receipt done = new PlannerApi.Receipt();
            done.ok = true;
            done.stateRevision = rev;
            return done;
        }, receipt -> {
            setBusy(false);
            if (!receipt.ok) {
                setBanner(Messages.receiptError(receipt), true);
            } else {
                setBanner(asPlanned ? CAUGHT_UP : CAUGHT_UP_SKIPPED, false);
                caughtUp = true;
            }
            slot = target;
            transition = Transition.FORWARD;
            reloadAndRoute();
        }, error -> {
            setBusy(false);
            setBanner(Messages.GENERIC_ERROR, true);
            reloadAndRoute();
        });
    }

    // ------------------------------------------------------------------ saving

    private void submit(Plan.Step step, Outcome outcome) {
        submitThen(step, outcome, null);
    }

    private void submitThen(Plan.Step step, Outcome outcome, Runnable after) {
        if (busy) {
            return;
        }
        withSide(step, outcome);
        String burst = correction == null && after == null ? rankUpCaption(step, outcome) : null;
        setBusy(true);
        String summary = Outcomes.summary(rules, step, outcome);
        PlannerApi a = api;
        long rev = snap.stateRevision;
        String planId = snap.plan == null ? "" : snap.plan.planId;
        String id = CommandIds.of("result", rev, planId, step.slot, step.action, outcomeKey(outcome));
        if (correction != null) {
            long target = correction.targetSeq;
            String fixId = CommandIds.of("outcome_fix", rev, target, outcomeKey(outcome));
            Async.run(this, () -> a.correctOutcome(fixId, rev, target, outcome),
                    receipt -> onReceipt(receipt, "已修改：" + summary, after, null), error -> onFailure());
            return;
        }
        Async.run(this, () -> a.recordOutcome(id, rev, step.slot, step.action, outcome),
            receipt -> onReceipt(receipt, summary, after, burst), error -> onFailure());
    }

    /**
     * The flourish caption when {@code outcome} records a rank-up of a social link ("皇帝 · RANK 7",
     * "皇帝 · MAX"), else null.
     */
    private String rankUpCaption(Plan.Step step, Outcome outcome) {
        StepKind kind = StepKind.of(step);
        if (!kind.social()) {
            return null;
        }
        int to = -1;
        if (outcome.code == Outcome.Code.RANK_UP) {
            to = outcome.rank;
        } else if ((outcome.code == Outcome.Code.OTHER || outcome.code == Outcome.Code.AS_PREDICTED)
            && outcome.rank > rankBefore(step)) {
            to = outcome.rank;
        }
        if (to <= 0) {
            return null;
        }
        Rules.Link link = Outcomes.linkOf(rules, step);
        String name = link != null ? link.arcanaZh : Outcomes.shortName(rules, step);
        return name + " · " + (to >= 10 ? "MAX" : "RANK " + to);
    }

    private void submitTower(PlannerApi.TowerReturn tower, String summary) {
        if (busy) {
            return;
        }
        setBusy(true);
        PlannerApi a = api;
        long rev = snap.stateRevision;
        String id = CommandIds.of("tower", rev, tower.slot, tower.block, tower.floor, tower.balanceMode, tower.amount,
            tower.level, tower.rescuedId, sideRow == null || sideRow.isChecked());
        Async.run(this, () -> a.recordTower(id, rev, tower), receipt -> onReceipt(receipt, summary, null, null),
            error -> onFailure());
    }

    /** Stable key of an outcome's content, so different answers never share a command id. */
    static String outcomeKey(Outcome o) {
        return o.code + ":" + o.rank + ":" + o.readyCue + ":" + Arrays.toString(o.statLv) + ":" + o.cashBalance + ":"
            + o.reason + (o.sideDone ? "" : ":noside");
    }

    private void onReceipt(PlannerApi.Receipt receipt, String summary, Runnable after, String burst) {
        setBusy(false);
        if (receipt.ok) {
            if (fromApp) {
                UndoCenter.post(receipt.eventSeq, summary);
            }
            if (after != null) {
                after.run();
                return;
            }
            // In a 一项项对 queue the next item (and the target) open on their own result panel,
            // even when this one was answered with 做不了 / 没去成 / 改做别的.
            if (burst != null && Ui.animationsEnabled()) {
                celebrating = true;
                playedBurst = burst;
                RankUpBurst.play(root, "RANK UP!!", burst, () -> {
                    celebrating = false;
                    continueOrFinish();
                });
                return;
            }
            continueOrFinish();
            return;
        }
        if (Messages.isStale(receipt)) {
            setBanner(Messages.STALE, true);
            if (correctionSeq >= 0) {
                showLoading();
                load();
                return;
            }
            caughtUp = false;
            substitute = null;
            sub = null;
            mode = Intents.MODE_RESULT;
            slot = -1;
            reloadAndRoute();
            return;
        }
        setBanner(Messages.receiptError(receipt), true);
        render();
    }

    private void onFailure() {
        setBusy(false);
        setBanner(Messages.GENERIC_ERROR, true);
        render();
    }

    private void finishAfterSave() {
        close();
    }

    private void reloadAndRoute() {
        PlannerApi a = api;
        stack.clear();
        carriedSide = null;
        restoreSide = null;
        Async.run(this, a::snapshot, s -> {
            snap = s;
            if (slot < 0 && s.observed != null) {
                slot = s.observed.slot;
            }
            alternatives = null;
            if (Intents.MODE_OTHER.equals(mode)) {
                mode = Intents.MODE_RESULT;
            }
            route();
        }, error -> showLoadFailed(null));
    }

    /**
     * While saving: a transparent touch blocker prevents double submissions, the stripe runs along
     * the sheet's top edge, the tapped plate stays lit, and 「正在保存…」 shows only when slow.
     */
    private void setBusy(boolean value) {
        busy = value;
        blocker.setVisibility(value ? View.VISIBLE : View.GONE);
        busyStripe.setVisibility(value ? View.VISIBLE : View.GONE);
        blocker.removeCallbacks(showSavingText);
        blockerText.setVisibility(View.GONE);
        if (value) {
            blocker.postDelayed(showSavingText, SAVING_TEXT_DELAY);
        }
        View plate = tapped;
        if (plate != null) {
            plate.setPressed(value);
            if (Ui.animationsEnabled()) {
                plate.animate().translationX(value ? Ui.dp(this, 12) : 0f).setDuration(120)
                    .setInterpolator(Motion.SETTLE).start();
            }
        }
    }

    // ------------------------------------------------------------------ test hooks

    /** The current tower panel, if shown (tests). */
    TowerPanel towerPanel() {
        return towerPanel;
    }

    String bannerText() {
        return banner;
    }

    /** Caption of the last 「RANK UP!!」 flourish this sheet played, or null (tests). */
    String playedBurst() {
        return playedBurst;
    }

    /** The 「也做了」 check of the current panel, or null (tests). */
    CheckRow sideRow() {
        return sideRow;
    }
}
