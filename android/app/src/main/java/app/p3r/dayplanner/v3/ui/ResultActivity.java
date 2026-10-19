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
 * Translucent quick result sheet, opened from the widget or the app (docs/DESIGN_v3.md §7.1).
 *
 * <p>Shows a panel targeted at the action: social rank-up ("升到 RANK 7 了" / "没升级" / 其他结果…),
 * ordinary meeting, unlock step, 结缘网站的字条, 羁绊剧情, training/job ("完成了，和预计一样" with a
 * correction path), Tartarus (floor + exactly one cash field), story locks; plus 做不了 reasons,
 * 改做别的 alternatives and the catch-up question when "完成" is pressed on a future item. Every
 * panel carries the step's 人格面具 reminder, its how-to and its "顺便" side steps (with the
 * "顺便的事也做了" checkbox). A plan id / revision that no longer matches shows "安排已更新" and
 * the current item instead; nothing stale is ever saved.
 */
public final class ResultActivity extends Activity {
    private static final String STATE_SLOT = "slot";
    private static final String STATE_ACTION = "action";
    private static final String STATE_MODE = "mode";
    private static final String STATE_QUEUE = "queue";
    private static final String STATE_QUEUE_TOTAL = "queueTotal";
    private static final String STATE_CHECKED = "checked";
    private static final String STATE_BANNER = "banner";
    private static final String STATE_TOWER = "tower";
    private static final String STATE_CAUGHT_UP = "caughtUp";

    private LinearLayout panel;
    private LinearLayout body;
    private ScrollView scroll;
    private TextView blocker;

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
    /** Open slots the 逐项核对 queue started with (before the target). */
    private int queueTotal;
    private String banner;
    /** A catch-up toward {@link #slot} just finished (all earlier open slots are recorded). */
    private boolean caughtUp;
    private boolean busy;
    /** Alternative chosen on 改做别的 (its own panel), else null. */
    private Plan.Step substitute;
    private TowerPanel towerPanel;
    private Bundle pendingTowerState;
    /** "顺便的事也做了" on the current panel, or null when the step has no side steps. */
    private CheckRow sideRow;

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
        caughtUp = false;
        substitute = null;
        alternatives = null;
        pendingTowerState = null;
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
        out.putBoolean(STATE_CAUGHT_UP, caughtUp);
        if (towerPanel != null) {
            out.putBundle(STATE_TOWER, towerPanel.saveState());
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (busy) {
            return;
        }
        if (substitute != null && snap != null) {
            substitute = null;
            render();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ frame

    private void buildFrame() {
        FrameLayout root = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(Ui.col(this, R.color.ui_scrim));
        scrim.setContentDescription("关闭");
        scrim.setOnClickListener(v -> {
            if (!busy) {
                finish();
            }
        });
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        panel = Ui.column(this);
        int cut = Ui.dp(this, 28);
        panel.setBackground(new SlantedPanelDrawable()
            .fill(Ui.col(this, R.color.ui_sheet))
            .stroke(Ui.alpha(Ui.col(this, R.color.p3r_cyan_400), 150), Ui.dp(this, 1))
            .cuts(cut, 0, 0, 0));
        panel.setClickable(true);
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = Ui.column(this);
        Ui.pad(body, 20, 14, 20, 20);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        pp.topMargin = Ui.dp(this, 56);
        root.addView(panel, pp);
        blocker = Ui.text(this, "正在保存…", 15, Ui.col(this, R.color.p3r_white), true);
        blocker.setGravity(Gravity.CENTER);
        blocker.setBackgroundColor(Ui.alpha(Ui.col(this, R.color.p3r_navy_950), 110));
        blocker.setClickable(true);
        blocker.setVisibility(View.GONE);
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
            panel.setTranslationY(Ui.dp(this, 80));
            panel.setAlpha(0f);
            panel.animate().translationY(0).alpha(1f).setDuration(200).start();
        }
    }

    private void showLoading() {
        body.removeAllViews();
        TextView text = Ui.muted(this, "正在读取…");
        Ui.pad(text, 0, 24, 0, 24);
        body.addView(text);
    }

    // ------------------------------------------------------------------ data

    /** Loaded bundle from the background thread. */
    private static final class Loaded {
        PlannerApi api;
        Rules rules;
        PlannerApi.Snapshot snapshot;
        int level;
    }

    private void load() {
        Context app = getApplicationContext();
        Async.run(this, () -> {
            Loaded l = new Loaded();
            l.api = Api.get(app);
            l.rules = l.api.rules();
            l.snapshot = l.api.snapshot();
            l.level = UiPrefs.towerLevel(app);
            return l;
        }, l -> {
            api = l.api;
            rules = l.rules;
            Zh.useNames(rules);
            snap = l.snapshot;
            rememberedLevel = l.level;
            route();
        }, error -> showLoadFailed(null));
    }

    /** Decides what to show for the current snapshot (stale check, catch-up, mode). */
    private void route() {
        SimState obs = snap.observed;
        Plan plan = snap.plan;
        if (rules == null || (obs == null && snap.planErrorZh != null)) {
            // The records or the game data could not be read: say so, never "还没有设置".
            showLoadFailed(snap.planErrorZh);
            return;
        }
        if (obs == null) {
            showNotSetUp();
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
                banner = "安排已更新，这是现在的下一项。";
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
        return substitute != null || snap.planCurrent;
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        body.removeAllViews();
        towerPanel = null;
        sideRow = null;
        SimState obs = snap.observed;
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
        if (banner != null) {
            body.addView(bannerView(banner), top(10));
        }
        if (step == null) {
            body.addView(Ui.body(this, "安排正在更新，稍后再记录这一项。"), top(16));
            body.addView(closeButton("好的"), top(20));
            return;
        }
        boolean resultLike = Intents.MODE_RESULT.equals(mode) || Intents.MODE_CATCHUP.equals(mode);
        if (substitute == null && resultLike && slot > obs.slot) {
            catchUpPanel(step);
            return;
        }
        if (substitute == null && Intents.MODE_CANNOT.equals(mode)) {
            cannotPanel(step);
            return;
        }
        if (substitute == null && Intents.MODE_OTHER.equals(mode)) {
            otherPanel(step);
            return;
        }
        resultPanel(step);
    }

    private void header(Plan.Step step) {
        LinearLayout row = Ui.row(this);
        DatePlate plate = new DatePlate(this, false);
        plate.bind(rules, slot);
        row.addView(plate, Ui.weight(1));
        if (queueTarget >= 0 && queueTotal > 0) {
            int left = Slots.openBetween(rules, snap.observed.slot, queueTarget);
            int done = Math.max(0, queueTotal - left);
            TextView progress = Ui.mixed(this, "补记 " + (done + 1) + "/" + (queueTotal + 1), 13,
                Ui.col(this, R.color.p3r_cyan_300), true);
            row.addView(progress, Ui.wrap());
        }
        ImageView close = new ImageView(this);
        close.setImageDrawable(Ui.tinted(this, R.drawable.ui_ic_close, Ui.col(this, R.color.p3r_ink_muted)));
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setContentDescription("关闭");
        close.setOnClickListener(v -> {
            if (!busy) {
                finish();
            }
        });
        int size = Ui.dp(this, Ui.TOUCH);
        row.addView(close, new LinearLayout.LayoutParams(size, size));
        body.addView(row);
        if (step != null) {
            StepKind kind = StepKind.of(step);
            TextView title = Ui.mixed(this, SlotRows.title(rules, step), 22, Ui.col(this, R.color.p3r_white), true);
            body.addView(title, top(10));
            if (step.locationZh != null && !step.locationZh.isEmpty() && kind != StepKind.LOCKED) {
                body.addView(Ui.muted(this, step.locationZh), top(2));
            }
        }
    }

    private View bannerView(String text) {
        LinearLayout box = Ui.row(this);
        box.setBackground(Ui.card(this).accent(Ui.col(this, R.color.p3r_cyan_400), Ui.dp(this, 4)));
        Ui.pad(box, 16, 10, 12, 10);
        box.addView(Ui.icon(this, R.drawable.ui_ic_sync, Ui.col(this, R.color.p3r_cyan_400), 18));
        TextView label = Ui.text(this, text, 14, Ui.col(this, R.color.p3r_white), true);
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

    private TextView question(String text) {
        return Ui.mixed(this, text, 20, Ui.col(this, R.color.p3r_cyan_400), true);
    }

    private TextView note(String text) {
        return Ui.mixed(this, text, 14, Ui.col(this, R.color.p3r_ink_muted), false);
    }

    private TextView closeButton(String label) {
        TextView ok = Ui.primary(this, label);
        ok.setOnClickListener(v -> finish());
        return ok;
    }

    /** Two equal buttons side by side; long labels shrink a little so both stay one line. */
    private LinearLayout pair(TextView left, TextView right) {
        LinearLayout row = Ui.row(this);
        row.setBaselineAligned(false);
        for (TextView button : new TextView[]{left, right}) {
            if (Zh.plain(button.getText()).length() > 6) {
                button.setTextSize(15);
                Ui.pad(button, 10, 8, 10, 8);
            }
        }
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.leftMargin = Ui.dp(this, 12);
        row.addView(right, rp);
        return row;
    }

    private void showNotSetUp() {
        body.removeAllViews();
        body.addView(Ui.title(this, "还没有设置游戏进度"));
        body.addView(Ui.body(this, "先打开「P3R 规划」填好现在的进度和目标，之后就能在这里记录结果。"), top(8));
        TextView go = Ui.primary(this, "去设置");
        go.setOnClickListener(v -> {
            Intent intent = new Intent(this, StateActivity.class);
            intent.putExtra(Intents.EXTRA_STATE_MODE, "onboarding");
            startActivity(intent);
            finish();
        });
        body.addView(go, top(20));
    }

    /** Every slot up to Jan 31 is recorded: nothing left to record. */
    private void showFinished() {
        body.removeAllViews();
        body.addView(Ui.title(this, "日程已经全部结束"));
        body.addView(Ui.body(this, "1月31日之前的时段都记录完了，没有要记录的事了。"), top(8));
        body.addView(closeButton("好的"), top(20));
    }

    private void showAlreadyRecorded() {
        header(null);
        body.addView(Ui.title(this, "这一项已经记录过了"), top(12));
        body.addView(Ui.body(this, "要更正的话，可以在「我的 → 记录历史」里撤销或修改。"), top(8));
        body.addView(closeButton("好的"), top(20));
    }

    /** The records or the game data could not be read: the service's own message and a retry. */
    private void showLoadFailed(String errorZh) {
        body.removeAllViews();
        body.addView(Ui.title(this, "暂时读不到记录"));
        body.addView(Ui.body(this, Messages.loadFailed(errorZh)), top(8));
        TextView close = Ui.secondary(this, "关闭");
        close.setOnClickListener(v -> finish());
        TextView retry = Ui.primary(this, "重试");
        retry.setOnClickListener(v -> {
            showLoading();
            load();
        });
        body.addView(pair(close, retry), top(20));
    }

    /**
     * After "都照安排做完了" / 逐项核对 / "前面的都没做" toward a slot the store has already moved
     * past (a story slot such as 满月作战, skipped on its own): the catch-up is done, nothing is
     * left to record here.
     */
    private void showCaughtUp() {
        Plan.Step step = step();
        header(step);
        body.addView(bannerView(banner != null ? banner : "已补记前面的安排。"), top(10));
        boolean locked = rules.lockOf(slot) != null || (step != null && StepKind.of(step) == StepKind.LOCKED);
        body.addView(Ui.body(this, locked ? "这段时间由剧情安排，不用记录。游戏里过完后，接着照「今天」的下一项玩就行。"
            : "这一项也已经记好了。"), top(12));
        body.addView(closeButton("好的"), top(20));
    }

    // ------------------------------------------------------------------ shared panel parts

    /**
     * The step's how-to ("怎么做", collapsible) and its "顺便" side steps with the
     * "顺便的事也做了" checkbox, placed above the panel's buttons.
     */
    private void stepExtras(Plan.Step step, boolean howToOpen, boolean sideDoneByDefault) {
        if (StepViews.hasHowTo(step)) {
            body.addView(StepViews.howToSection(this, step.detailZh, true, howToOpen), top(10));
        }
        addSide(step, sideDoneByDefault);
    }

    /** "顺便：…" lines and the checkbox (only for steps that have side steps). */
    private void addSide(Plan.Step step, boolean doneByDefault) {
        if (!StepViews.hasSide(step)) {
            return;
        }
        LinearLayout box = Ui.column(this);
        box.setBackground(Ui.card(this).accent(Ui.col(this, R.color.ui_gold), Ui.dp(this, 4)));
        Ui.pad(box, 16, 10, 12, 4);
        box.addView(StepViews.sideBlock(this, step, 14));
        sideRow = new CheckRow(this, "顺便的事也做了", doneByDefault);
        LinearLayout.LayoutParams rp = Ui.matchWrap();
        rp.topMargin = Ui.dp(this, 4);
        box.addView(sideRow, rp);
        body.addView(box, top(12));
    }

    /** Applies the side checkbox to an outcome (side steps count as done unless unchecked). */
    private Outcome withSide(Plan.Step step, Outcome outcome) {
        if (sideRow != null && StepViews.hasSide(step)) {
            outcome.sideDone = sideRow.isChecked();
        }
        return outcome;
    }

    /** Index of the link a meeting / note is about, or -1. */
    private int linkOf(Plan.Step step) {
        Action action = Action.parse(rules, step.action);
        if (action != null && (action.type == Action.Type.MEET || action.type == Action.Type.NOTE)
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

    // ------------------------------------------------------------------ result panels

    private void resultPanel(Plan.Step step) {
        StepKind kind = StepKind.of(step);
        if (StepViews.hasHint(step) && kind != StepKind.LOCKED) {
            body.addView(StepViews.hintChip(this, step.hintZh), top(10));
        }
        switch (kind) {
            case SOCIAL_RANK_UP:
                socialRankUp(step);
                break;
            case SOCIAL_MEET:
                socialMeet(step);
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
            case STAT:
            case JOB:
                statJobPanel(step);
                break;
            case TOWER:
                towerPanel(step);
                break;
            case LOCKED:
                lockedPanel(step);
                break;
            default:
                freePanel(step);
                break;
        }
        if (kind.deviatable() && kind != StepKind.TOWER) {
            body.addView(deviationLinks(), top(14));
        }
    }

    private void socialRankUp(Plan.Step step) {
        int from = rankBefore(step);
        int to = Math.min(10, from + 1);
        boolean current = rankPredictionCurrent(step, from);
        body.addView(question("升到 " + Zh.rank(to) + " 了吗？"), top(14));
        body.addView(note("现在是 " + Zh.rank(from) + "，这次见面预计会升级。"), top(2));
        String dialogue = Dialogue.besideHowTo(rules, snap.profile, step);
        if (dialogue != null) {
            body.addView(Ui.body(this, dialogue), top(6));
        }
        if (!step.predicted.certain) {
            body.addView(Ui.muted(this, "好感点数为预估，请按游戏内实际结果选择。"), top(4));
        }
        stepExtras(step, false, true);
        ToggleRow ready = readyToggle();
        body.addView(ready, top(12));
        TextView up = Ui.primary(this, "升到 " + Zh.rank(to) + " 了");
        // A matching current prediction is recorded as such (the plan stays valid); otherwise the
        // player's answer is recorded explicitly.
        up.setOnClickListener(v -> submit(step, current ? Outcomes.asPredicted(step) : Outcomes.rankUp(to)));
        TextView no = Ui.secondary(this, "没升级");
        no.setOnClickListener(v -> submit(step, Outcomes.noRankUp(from, ready.isChecked())));
        LinearLayout more = otherResults(step, from, ready);
        more.setVisibility(View.GONE);
        if (step.predicted.certain) {
            body.addView(up, top(16));
            TextView other = Ui.secondary(this, "其他结果…");
            body.addView(pair(no, other), top(12));
            other.setOnClickListener(v -> toggle(more));
        } else {
            body.addView(pair(no, up), top(16));
            TextView other = Ui.quiet(this, "其他结果…");
            body.addView(other, top(6));
            other.setOnClickListener(v -> toggle(more));
        }
        body.addView(more, top(4));
    }

    private ToggleRow readyToggle() {
        return new ToggleRow(this, "游戏提示下次就能升级", "没升级，但看到了这个提示时打开。", false);
    }

    private static void toggle(View view) {
        view.setVisibility(view.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /** "其他结果…": rank chips around the current rank, 没见到, and the story-hold answer. */
    private LinearLayout otherResults(Plan.Step step, int from, ToggleRow ready) {
        LinearLayout box = Ui.column(this);
        box.addView(Ui.text(this, "游戏里现在是 RANK 几？", 14, Ui.col(this, R.color.p3r_white), true), top(10));
        FlowLayout chips = new FlowLayout(this, 8, 8);
        for (int r = Math.max(0, from - 1); r <= Math.min(10, from + 2); r++) {
            final int rank = r;
            TextView chip = Ui.chip(this, Zh.rank(r), r == from);
            chip.setOnClickListener(v -> submit(step, Outcomes.observedRank(from, rank, ready.isChecked())));
            chips.addView(chip);
        }
        box.addView(chips, top(6));
        LinearLayout row = Ui.row(this);
        row.setBaselineAligned(false);
        TextView absent = Ui.secondary(this, "没见到");
        absent.setOnClickListener(v -> submit(step, Outcomes.notMet()));
        TextView hold = Ui.secondary(this, "剧情原因暂时升不了");
        hold.setTextSize(14);
        hold.setOnClickListener(v -> submit(step, Outcomes.hold()));
        row.addView(absent, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f);
        hp.leftMargin = Ui.dp(this, 12);
        row.addView(hold, hp);
        box.addView(row, top(12));
        return box;
    }

    private void socialMeet(Plan.Step step) {
        int from = rankBefore(step);
        int to = Math.min(10, from + 1);
        boolean current = rankPredictionCurrent(step, from);
        int link = linkOf(step);
        String who = link >= 0 ? rules.links[link].personZh : Outcomes.shortName(rules, step);
        body.addView(question("和" + who + "见面了吗？"), top(14));
        body.addView(note("现在是 " + Zh.rank(from) + "，预计这次不会升级，好感会继续累积。"), top(4));
        String dialogue = Dialogue.besideHowTo(rules, snap.profile, step);
        if (dialogue != null) {
            body.addView(Ui.body(this, dialogue), top(6));
        }
        stepExtras(step, false, true);
        ToggleRow ready = readyToggle();
        body.addView(ready, top(12));
        TextView met = Ui.primary(this, "见面了");
        met.setOnClickListener(v -> submit(step, current ? Outcomes.met(step, ready.isChecked())
            : Outcomes.noRankUp(from, ready.isChecked())));
        body.addView(met, top(16));
        TextView up = Ui.secondary(this, "升到 " + Zh.rank(to) + " 了");
        up.setOnClickListener(v -> submit(step, Outcomes.rankUp(to)));
        TextView absent = Ui.secondary(this, "没见到");
        absent.setOnClickListener(v -> submit(step, Outcomes.notMet()));
        body.addView(pair(up, absent), top(12));
        TextView hold = Ui.quiet(this, "剧情原因暂时升不了");
        hold.setOnClickListener(v -> submit(step, Outcomes.hold()));
        body.addView(hold, top(4));
    }

    private void unlockPanel(Plan.Step step) {
        body.addView(question("完成了吗？"), top(14));
        if (step.subtitleZh != null && !step.subtitleZh.isEmpty()) {
            body.addView(Ui.mixed(this, step.subtitleZh, 17, Ui.col(this, R.color.p3r_white), true), top(4));
        }
        if (step.whyZh != null && !step.whyZh.isEmpty()) {
            body.addView(note(step.whyZh), top(4));
        }
        stepExtras(step, true, true);
        TextView done = Ui.primary(this, "完成了");
        done.setOnClickListener(v -> submit(step, Outcomes.asPredicted(step, predictionCurrent())));
        TextView notDone = Ui.secondary(this, "没完成");
        notDone.setOnClickListener(v -> submit(step, Outcomes.notDone()));
        body.addView(pair(notDone, done), top(16));
    }

    /** 结缘网站的字条: bought at the club and used on the dorm PC for one established link. */
    private void notePanel(Plan.Step step) {
        int link = linkOf(step);
        String who = link >= 0 ? rules.links[link].personZh : Outcomes.shortName(rules, step);
        body.addView(question("用结缘网站的字条给" + who + "加好感了吗？"), top(14));
        body.addView(note("结缘网站的字条只加好感，不会直接升级。"), top(4));
        stepExtras(step, true, true);
        TextView used = Ui.primary(this, "用了");
        used.setOnClickListener(v -> submit(step, Outcomes.asPredicted(step, false)));
        TextView not = Ui.secondary(this, "没买到/没用");
        not.setOnClickListener(v -> submit(step, Outcomes.notDone()));
        body.addView(pair(not, used), top(16));
    }

    /** The next 羁绊剧情 of an opted-in track. */
    private void episodePanel(Plan.Step step) {
        Rules.Episode track = Outcomes.episodeOf(rules, step);
        body.addView(question(episodeQuestion(track, snap.observed)), top(14));
        body.addView(note(episodeNote(track)), top(4));
        stepExtras(step, true, true);
        TextView watched = Ui.primary(this, "看完了");
        watched.setOnClickListener(v -> submit(step, Outcomes.asPredicted(step, false)));
        TextView missed = Ui.secondary(this, "没看成");
        missed.setOnClickListener(v -> submit(step, Outcomes.notDone()));
        body.addView(pair(missed, watched), top(16));
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
     * "羁绊剧情不加社群点数。" followed by the pack's reward text as its own sentence (it already is
     * one: "每一话都有小奖励…，看完第 5 话可以合体人格面具「苏尔特尔」。").
     */
    static String episodeNote(Rules.Episode track) {
        return "羁绊剧情不加社群点数。" + (track == null ? "" : Zh.sentence(track.rewardZh));
    }

    private void statJobPanel(Plan.Step step) {
        StepKind kind = StepKind.of(step);
        body.addView(question("完成了吗？"), top(14));
        String expected = expectedText(step);
        if (expected != null) {
            body.addView(Ui.mixed(this, "预计 " + expected, 17, Ui.col(this, R.color.p3r_white), true), top(4));
        }
        stepExtras(step, false, true);
        boolean current = predictionCurrent();
        TextView asPlanned = Ui.primary(this, "完成了，和预计一样");
        asPlanned.setContentDescription("完成了，和预计一样" + (expected == null ? "" : "：" + expected));
        asPlanned.setOnClickListener(v -> submit(step, Outcomes.asPredicted(step, current)));
        body.addView(asPlanned, top(16));
        TextView differ = Ui.secondary(this, "结果不一样…");
        body.addView(differ, top(12));
        LinearLayout more = Ui.column(this);
        more.setVisibility(View.GONE);
        differ.setOnClickListener(v -> toggle(more));
        SimState obs = snap.observed;
        int[] chosen = {-1, -1, -1};
        long[] balance = {-1};
        boolean any = false;
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            if (step.predicted.statDelta[i] == 0 && kind == StepKind.JOB) {
                continue;
            }
            if (step.predicted.statDelta[i] == 0 && hasAnyStatDelta(step)) {
                continue;
            }
            any = true;
            int now = rules.stats[i].levelFor(obs.statPts[i]);
            more.addView(Ui.text(this, "现在" + rules.stats[i].zh + "是几级？", 14, Ui.col(this, R.color.p3r_white), true),
                top(10));
            List<String> labels = new ArrayList<>();
            List<Integer> values = new ArrayList<>();
            for (int lv = now; lv <= Math.min(6, now + 1); lv++) {
                labels.add(Zh.lv(lv));
                values.add(lv);
            }
            SegmentedControl seg = new SegmentedControl(this, labels);
            final int stat = i;
            // Preselect the forecast level (the prediction), never silently "no level-up".
            int predicted = current ? step.predicted.statLvAfter[i] : -1;
            int preset = predicted > 0 && values.contains(predicted) ? values.indexOf(predicted) : 0;
            seg.setSelected(preset);
            chosen[stat] = values.get(preset);
            seg.setListener(index -> chosen[stat] = values.get(index));
            more.addView(seg, top(6));
        }
        if (kind == StepKind.JOB) {
            more.addView(Ui.text(this, "打工后手上有多少钱？", 14, Ui.col(this, R.color.p3r_white), true),
                top(any ? 14 : 10));
            TextView amount = Ui.secondary(this, "点这里输入");
            amount.setOnClickListener(v -> {
                CashPad.Options options = new CashPad.Options();
                options.title = "打工后手上有多少钱？";
                options.initial = obs.cashExact ? obs.cash + step.predicted.cashDelta : null;
                options.allowUnknown = false;
                CashPad.show(this, options, value -> {
                    if (value != null) {
                        balance[0] = value;
                        amount.setText(Ui.latin(this, Zh.yen(value)));
                    }
                });
            });
            more.addView(amount, top(6));
        }
        TextView save = Ui.primary(this, "保存");
        save.setOnClickListener(v -> submit(step, Outcomes.different(chosen, balance[0])));
        more.addView(save, top(16));
        body.addView(more, top(4));
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
        if (step.subtitleZh != null && !step.subtitleZh.isEmpty()) {
            return step.subtitleZh;
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
        boolean visitLine = block >= 0 && block < obs.towerVisits.length && block < rules.blocks.length;
        // The visit count has its own line below ("本区块第 1 次 · 上限 4 次"): not twice.
        String sub = visitLine ? withoutVisit(SlotRows.subtitle(step)) : SlotRows.subtitle(step);
        if (!sub.isEmpty()) {
            body.addView(Ui.mixed(this, sub, 15, Ui.col(this, R.color.p3r_cyan_300), true), top(8));
        }
        if (visitLine) {
            String visits = "本区块第 " + (obs.towerVisits[block] + 1) + " 次 · 上限 "
                + profile.capFor(rules.blocks[block].id) + " 次";
            body.addView(Ui.mixed(this, visits, 13, Ui.col(this, R.color.p3r_ink_muted), false), top(2));
        }
        if (StepViews.hasHowTo(step)) {
            body.addView(StepViews.howToSection(this, step.detailZh, true, false), top(10));
        }
        boolean sideRecordable = Outcomes.towerSideSupported();
        if (sideRecordable) {
            addSide(step, true);
        } else if (StepViews.hasSide(step)) {
            body.addView(StepViews.sideBlock(this, step, 14), top(10));
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
                    submitTower(tower, summary);
                }

                @Override
                public void onCannot() {
                    mode = Intents.MODE_CANNOT;
                    substitute = null;
                    render();
                }
            });
        if (pendingTowerState != null) {
            towerPanel.restoreState(pendingTowerState);
            pendingTowerState = null;
        }
        body.addView(towerPanel, top(16));
    }

    /** "提齐亚（69–117F）· 本区块第 1 次 · 救出舞子" without its "本区块第 1 次" part. */
    static String withoutVisit(String subtitle) {
        String out = subtitle.replaceAll("\\s*·?\\s*本区块第\\s*\\d+\\s*次", "").replace("） ·", "）·").trim();
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
        String sub = step.subtitleZh == null || step.subtitleZh.isEmpty() ? "这段时间不能自由行动。" : step.subtitleZh;
        body.addView(Ui.body(this, sub), top(6));
        TextView go = Ui.primary(this, "继续");
        go.setOnClickListener(v -> submit(step, Outcomes.asPredicted(step)));
        body.addView(go, top(18));
    }

    private void freePanel(Plan.Step step) {
        body.addView(Ui.body(this, "这个时段是自由活动，没有能推进进度的事。想做点什么，可以点「改做别的…」。"), top(10));
        // A free slot can still carry "顺便" side steps: ask about them like on every other panel.
        stepExtras(step, false, true);
        TextView go = Ui.primary(this, "继续");
        go.setOnClickListener(v -> submit(step, Outcomes.asPredicted(step)));
        body.addView(go, top(18));
    }

    private View deviationLinks() {
        LinearLayout row = Ui.row(this);
        TextView cannot = Ui.quiet(this, "做不了");
        cannot.setOnClickListener(v -> {
            substitute = null;
            mode = Intents.MODE_CANNOT;
            render();
        });
        row.addView(cannot, Ui.weight(1));
        TextView other = Ui.quiet(this, "改做别的…");
        other.setOnClickListener(v -> switchToOther());
        row.addView(other, Ui.weight(1));
        return row;
    }

    private void switchToOther() {
        substitute = null;
        mode = Intents.MODE_OTHER;
        if (alternatives == null) {
            loadAlternatives();
        } else {
            render();
        }
    }

    // ------------------------------------------------------------------ 做不了 / 改做别的

    private void cannotPanel(Plan.Step step) {
        body.addView(question("这个时段没能照安排做？"), top(14));
        body.addView(Ui.muted(this, "选一个原因，这个时段就算过去了，后面会重新安排。"), top(4));
        // Side steps of a slot that fell through are only counted when the player says so.
        addSide(step, false);
        List<TextView> buttons = new ArrayList<>();
        for (String reason : Outcomes.SKIP_REASONS.keySet()) {
            TextView button = Ui.secondary(this, Outcomes.SKIP_REASONS.get(reason));
            button.setOnClickListener(v -> onSkipReason(step, reason));
            buttons.add(button);
        }
        TextView other = Ui.secondary(this, "改做别的…");
        other.setTextColor(Ui.col(this, R.color.p3r_cyan_300));
        other.setOnClickListener(v -> switchToOther());
        buttons.add(other);
        for (int i = 0; i < buttons.size(); i += 2) {
            body.addView(pair(buttons.get(i), buttons.get(i + 1)), top(i == 0 ? 16 : 12));
        }
    }

    private void onSkipReason(Plan.Step step, String reason) {
        if ("money".equals(reason)) {
            CashPad.Options options = new CashPad.Options();
            options.title = "现在有多少钱？";
            options.hint = "填了现金，后面要花钱的事会按实际的钱重新安排。";
            options.initial = snap.observed.cashExact ? snap.observed.cash : null;
            CashPad.show(this, options, value -> submit(step, Outcomes.skipped(reason, value == null ? -1 : value)));
            return;
        }
        if ("unwilling".equals(reason) && "act:".regionMatches(0, step.action, 0, 4)) {
            askExclude(step);
            return;
        }
        submit(step, Outcomes.skipped(reason, -1));
    }

    /** "以后还安排「X」吗？" after 我不想做 on a training/job step. */
    private void askExclude(Plan.Step step) {
        boolean sideDone = sideRow != null && sideRow.isChecked();
        body.removeAllViews();
        header(step);
        body.addView(question("以后还安排「" + step.titleZh + "」吗？"), top(8));
        body.addView(Ui.muted(this, "选「以后不再安排」后，可以在「我的 → 规划设置」里恢复。"), top(4));
        TextView once = Ui.secondary(this, "只是这次");
        once.setOnClickListener(v -> submit(step, Outcomes.withSide(Outcomes.skipped("unwilling", -1), sideDone)));
        TextView never = Ui.primary(this, "以后不再安排");
        never.setOnClickListener(v -> {
            String id = step.action.substring(4);
            submitThen(step, Outcomes.withSide(Outcomes.skipped("unwilling", -1), sideDone), () -> excludeActivity(id));
        });
        body.addView(pair(once, never), top(18));
        sideRow = null;
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

    /** After a save: the next item of a running 逐项核对 queue, else close the sheet. */
    private void continueOrFinish() {
        if (queueTarget >= 0) {
            substitute = null;
            banner = null;
            mode = Intents.MODE_RESULT;
            reloadAndRoute();
            return;
        }
        finishAfterSave();
    }

    private void otherPanel(Plan.Step planned) {
        body.addView(question("这个时段做了什么？"), top(14));
        body.addView(Ui.muted(this, "标着「原安排」的是本来排的那项。选你实际做的事，后面会据此重新安排。"), top(4));
        List<Plan.Step> list = alternatives == null ? new ArrayList<>() : alternatives;
        if (list.isEmpty()) {
            body.addView(Ui.body(this, "这个时段没有其他能推进进度的事。"), top(12));
        }
        String lastKind = null;
        for (Plan.Step alt : list) {
            String group = groupOf(alt);
            if (!group.equals(lastKind)) {
                lastKind = group;
                body.addView(Ui.text(this, group, 13, Ui.col(this, R.color.p3r_cyan_300), true), top(14));
            }
            body.addView(alternativeRow(alt, planned), top(6));
        }
        TextView nothing = Ui.secondary(this, "做了列表以外的事（没有数值变化）");
        nothing.setTextSize(15);
        nothing.setOnClickListener(v -> submit(planned, Outcomes.withSide(
            Outcomes.skipped(Outcomes.REASON_OTHER, -1), false)));
        body.addView(nothing, top(18));
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
            default:
                return "其他";
        }
    }

    private View alternativeRow(Plan.Step alt, Plan.Step planned) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.pressableCard(this));
        row.setMinimumHeight(Ui.dp(this, 56));
        Ui.pad(row, 14, 8, 10, 8);
        row.addView(Ui.icon(this, StepKind.of(alt).iconRes, Ui.col(this, R.color.p3r_cyan_400), 20));
        LinearLayout texts = Ui.column(this);
        texts.addView(Ui.mixed(this, SlotRows.title(rules, alt), 15, Ui.col(this, R.color.p3r_white), true));
        String sub = SlotRows.subtitle(alt);
        if (!sub.isEmpty()) {
            texts.addView(Ui.mixed(this, sub, 13, Ui.col(this, R.color.p3r_cyan_300), false));
        }
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(this, 12);
        row.addView(texts, tp);
        if (planned != null && alt.action.equals(planned.action)) {
            TextView tag = Ui.text(this, "原安排", 11, Ui.col(this, R.color.p3r_navy_950), true);
            tag.setBackground(new SlantedPanelDrawable().fill(Ui.col(this, R.color.p3r_cyan_400)).skew(Ui.dp(this, 4)));
            Ui.pad(tag, 7, 1, 7, 1);
            row.addView(tag, Ui.wrap());
        }
        row.addView(Ui.icon(this, R.drawable.ui_ic_chevron, Ui.col(this, R.color.p3r_ink_faint), 18));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(SlotRows.title(rules, alt) + (sub.isEmpty() ? "" : "，" + sub));
        row.setOnClickListener(v -> {
            substitute = alt;
            render();
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

    private void catchUpPanel(Plan.Step target) {
        SimState obs = snap.observed;
        Plan plan = snap.plan;
        List<Plan.Step> pending = pendingBefore(target.slot);
        int count = pending.size();
        body.addView(question(count == 0 ? "前面只剩剧情时段，确认一下就好" : "前面还有 " + count + " 项没记录"), top(14));
        if (count == 0) {
            TextView go = Ui.primary(this, "继续");
            go.setOnClickListener(v -> catchUp(target.slot, true));
            body.addView(go, top(18));
            return;
        }
        LinearLayout list = Ui.column(this);
        list.setBackground(Ui.card(this));
        Ui.pad(list, 14, 8, 12, 8);
        int shown = 0;
        for (Plan.Step p : pending) {
            if (shown == 4) {
                list.addView(Ui.muted(this, "……还有 " + (count - shown) + " 项"));
                break;
            }
            TextView line = Ui.mixed(this, Zh.slot(rules, p.slot) + " · "
                + SlotRows.title(rules, p), 14, Ui.col(this, R.color.p3r_ice_100), false);
            Ui.pad(line, 0, 4, 0, 4);
            list.addView(line);
            shown++;
        }
        body.addView(list, top(10));
        TextView all = Ui.primary(this, "都照安排做完了");
        all.setOnClickListener(v -> catchUp(target.slot, true));
        body.addView(all, top(18));
        TextView each = Ui.secondary(this, "逐项核对");
        each.setOnClickListener(v -> {
            queueTarget = target.slot;
            queueTotal = count;
            slot = obs.slot;
            Plan.Step first = plan.at(slot);
            actionKey = first == null ? null : first.action;
            render();
        });
        TextView none = Ui.secondary(this, "前面的都没做");
        none.setContentDescription("前面的都没做，从这一项开始");
        none.setOnClickListener(v -> catchUp(target.slot, false));
        body.addView(pair(each, none), top(12));
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
                banner = Messages.receiptError(receipt);
            } else {
                banner = asPlanned ? "已补记前面的安排。" : "前面的时段记为没做。";
                caughtUp = true;
            }
            slot = target;
            reloadAndRoute();
        }, error -> {
            setBusy(false);
            banner = Messages.GENERIC_ERROR;
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
        setBusy(true);
        String summary = Outcomes.summary(rules, step, outcome);
        PlannerApi a = api;
        long rev = snap.stateRevision;
        String planId = snap.plan == null ? "" : snap.plan.planId;
        String id = CommandIds.of("result", rev, planId, step.slot, step.action, outcomeKey(outcome));
        Async.run(this, () -> a.recordOutcome(id, rev, step.slot, step.action, outcome),
            receipt -> onReceipt(receipt, summary, after), error -> onFailure());
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
        Async.run(this, () -> a.recordTower(id, rev, tower), receipt -> onReceipt(receipt, summary, null),
            error -> onFailure());
    }

    /** Stable key of an outcome's content, so different answers never share a command id. */
    static String outcomeKey(Outcome o) {
        return o.code + ":" + o.rank + ":" + o.readyCue + ":" + Arrays.toString(o.statLv) + ":" + o.cashBalance + ":"
            + o.reason + (o.sideDone ? "" : ":noside");
    }

    private void onReceipt(PlannerApi.Receipt receipt, String summary, Runnable after) {
        setBusy(false);
        if (receipt.ok) {
            if (fromApp) {
                UndoCenter.post(receipt.eventSeq, summary);
            }
            if (after != null) {
                after.run();
                return;
            }
            // In a 逐项核对 queue the next item (and the target) open on their own result panel,
            // even when this one was answered with 做不了 / 没去成 / 改做别的.
            continueOrFinish();
            return;
        }
        if (Messages.isStale(receipt)) {
            banner = Messages.STALE;
            caughtUp = false;
            substitute = null;
            mode = Intents.MODE_RESULT;
            slot = -1;
            reloadAndRoute();
            return;
        }
        banner = Messages.receiptError(receipt);
        render();
    }

    private void onFailure() {
        setBusy(false);
        banner = Messages.GENERIC_ERROR;
        render();
    }

    private void finishAfterSave() {
        finish();
    }

    private void reloadAndRoute() {
        PlannerApi a = api;
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

    /** While saving, a touch-blocking overlay prevents double submissions. */
    private void setBusy(boolean value) {
        busy = value;
        blocker.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ test hooks

    /** The current tower panel, if shown (tests). */
    TowerPanel towerPanel() {
        return towerPanel;
    }

    String bannerText() {
        return banner;
    }

    /** The "顺便的事也做了" checkbox of the current panel, or null (tests). */
    CheckRow sideRow() {
        return sideRow;
    }
}
