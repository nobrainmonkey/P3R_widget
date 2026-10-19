package app.p3r.dayplanner.v3.service;

import android.content.Context;
import android.database.SQLException;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.Backup;
import app.p3r.dayplanner.v3.store.Canonical;
import app.p3r.dayplanner.v3.store.CheckpointRow;
import app.p3r.dayplanner.v3.store.EventKind;
import app.p3r.dayplanner.v3.store.EventRow;
import app.p3r.dayplanner.v3.store.Ledger;
import app.p3r.dayplanner.v3.store.OutcomeRecord;
import app.p3r.dayplanner.v3.store.Replayer;
import app.p3r.dayplanner.v3.store.StateShape;
import app.p3r.dayplanner.v3.store.TowerIncome;
import app.p3r.dayplanner.v3.store.TowerRecord;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide implementation of {@link PlannerApi} backed by SQLite ({@code p3r_v3.db}, see
 * {@link app.p3r.dayplanner.v3.store.PlanDb}) — docs/DESIGN_v3.md §6, §7, §10.4, §10.6.
 *
 * <h3>Data</h3>
 * Observed state = latest checkpoint + replay of the non-voided rows recorded after it. Every write
 * is one SQLite transaction: idempotency lookup by command id first (same id and payload → the
 * original receipt; same id, other payload → {@code conflict}), then the compare-and-set on the
 * expected revision ({@code stale}), then validation (Chinese messages), then the write and the
 * revision bump. After a write the in-memory view is rebuilt, a replan is queued, the widgets are
 * refreshed and listeners are notified on the main thread.
 *
 * <h3>Threads</h3>
 * <ul>
 *   <li>{@link #get} is cheap on any thread; it starts loading the rule pack and the database on
 *       the service's worker thread.</li>
 *   <li>{@link #snapshot} and {@link #rules} read an immutable in-memory model without locks or
 *       I/O; they only wait (once) for the initial load.</li>
 *   <li>Writes, {@link #history}, {@link #alternatives}, {@link #exportBackup} and
 *       {@link #importBackup} touch the disk or do real CPU work: call them from a background
 *       thread. They serialise on one lock that is never held while a plan is computed.</li>
 *   <li>Plans are computed on the worker thread ({@link ReplanWorker}); listeners run on the main
 *       thread.</li>
 * </ul>
 */
public final class PlannerService implements PlannerApi {
    private static final String TAG = "P3rPlanner";

    static final String ZH_STALE = "记录已经更新过了，请看最新的安排";
    static final String ZH_CONFLICT = "刚才已经保存过另一份结果，这次没有保存";
    static final String ZH_IO = "保存失败了，请再试一次";
    static final String ZH_RULES_MISSING = "游戏资料读取失败，请重新打开应用";
    static final String ZH_DB_MISSING = "读取记录失败了，请重新打开应用";
    static final String ZH_COMMAND_ID = "保存失败了，请再试一次";
    static final String ZH_OUTCOME_MISSING = "请先选择结果";
    static final String ZH_TOWER_MISSING = "请先填写这次探索塔尔塔罗斯的结果";
    static final String ZH_PLAN_FAILED_KEPT = "生成安排时出了问题，已保留上一版";
    static final String ZH_PLAN_FAILED_NONE = "生成安排时出了问题，请稍后再试";
    /** Import duplicate with nothing recorded since: the data already is the backup. */
    static final String ZH_IMPORT_ALREADY = "这份备份刚才已经恢复过了，现在的进度就是备份里的";
    /** Import duplicate after newer records: nothing was replaced. */
    static final String ZH_IMPORT_ALREADY_LATER = "这份备份之前已经恢复过，之后又有新的记录，这次没有改动";

    /** {@link Snapshot#noticeZh} for {@code n} records that no longer fit the game data. */
    static String mismatchNotice(int n) {
        return "有 " + n + " 条记录和现在的游戏资料对不上，建议「同步现状」核对一下";
    }

    private static volatile PlannerService instance;

    /**
     * The process-wide service, created on first use with the production wiring. Cheap on any
     * thread: loading happens on the service's worker thread.
     */
    public static PlannerApi get(Context context) {
        Context app = applicationOf(context);
        PlannerService s = instance;
        if (s == null || s.context != app) {
            synchronized (PlannerService.class) {
                s = instance;
                if (s == null || s.context != app) {
                    // A different application object only happens in test runners that create one
                    // per test; the previous instance belongs to a discarded sandbox.
                    if (s != null) {
                        s.retire();
                    }
                    s = new PlannerService(app, DefaultWiring.create(app));
                    instance = s;
                }
            }
        }
        return s;
    }

    private static Context applicationOf(Context context) {
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    /** A separate instance with custom dependencies (tests); call {@link #close} when done. */
    static PlannerService create(Context context, Wiring wiring) {
        return new PlannerService(context, wiring);
    }

    /** Everything {@link #snapshot} reads, replaced atomically. */
    private static final class Model {
        /** Null until loaded, or when loading failed. */
        final LedgerView view;
        final Plan plan;
        final String planErrorZh;

        Model(LedgerView view, Plan plan, String planErrorZh) {
            this.view = view;
            this.plan = plan;
            this.planErrorZh = planErrorZh;
        }

        Model withView(LedgerView v) {
            return new Model(v, plan, planErrorZh);
        }

        Model withPlan(Plan p) {
            return new Model(view, p, null);
        }

        Model withError(String zh) {
            return new Model(view, plan, zh);
        }
    }

    /** Body of a write; runs inside the transaction and the lock. */
    private interface TxCommand {
        Receipt run(LedgerView view) throws JSONException;
    }

    /** Lazily built JSON payload. */
    private interface JsonSource {
        JSONObject get() throws JSONException;
    }

    private final Context context;
    private final Wiring wiring;
    private final Object lock = new Object();
    private final ExecutorService worker;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean notifyPosted = new AtomicBoolean();
    private final CountDownLatch loaded = new CountDownLatch(1);
    private final ReplanWorker replanner;

    /** Guarded by {@link #lock}; null until loaded and after {@link #close}. */
    private Ledger ledger;
    /** Guarded by {@link #lock}: revisions whose last plan attempt failed (no automatic retry). */
    private String failedPlanKey;
    /**
     * Revisions key ({@link #planKey}) whose budget follow-up was already queued: an installed plan
     * that ran out of time gets exactly one follow-up computation with twice the budget.
     */
    private String followUpKey;

    private volatile Model model = new Model(null, null, null);

    private PlannerService(Context context, Wiring wiring) {
        this.context = applicationOf(context);
        this.wiring = wiring;
        this.worker = Executors.newSingleThreadExecutor(PlannerService::newWorkerThread);
        this.replanner = new ReplanWorker(worker, new ReplanHost(), wiring.planner, wiring.validator, wiring.clock);
        worker.execute(this::loadInitial);
    }

    private static Thread newWorkerThread(Runnable task) {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "p3r-planner");
        thread.setDaemon(true);
        return thread;
    }

    // ------------------------------------------------------------------------------ loading

    private void loadInitial() {
        try {
            model = openModel();
        } finally {
            loaded.countDown();
        }
        if (needsPlan(model)) {
            replanner.schedule();
        }
        // Widgets that rendered while the service was loading show the real state now.
        refreshWidgets();
        notifyListeners();
    }

    /** Re-opens the rule pack and ledger after a failed load (worker thread). */
    private void retryOpen() {
        if (model.view != null) {
            return;
        }
        model = openModel();
        if (needsPlan(model)) {
            replanner.schedule();
        }
        refreshWidgets();
        notifyListeners();
    }

    /** Loads the rule pack and the ledger; a failure yields a model that only carries the error. */
    private Model openModel() {
        Rules rules;
        try {
            rules = Objects.requireNonNull(wiring.rules.load(), "rules");
        } catch (RuntimeException e) {
            Log.e(TAG, "rule pack failed to load", e);
            return new Model(null, null, ZH_RULES_MISSING);
        }
        try {
            synchronized (lock) {
                ledger = new Ledger(context, wiring.databaseName);
                return new Model(LedgerView.load(ledger, rules, wiring.applier), readStoredPlan(), null);
            }
        } catch (RuntimeException e) {
            // SQLException or an unexpected bug: never crash the worker thread.
            Log.e(TAG, "planner database failed to open", e);
            return new Model(null, null, ZH_DB_MISSING);
        }
    }

    private Plan readStoredPlan() {
        Ledger.StoredPlan stored = ledger.plan();
        if (stored == null) {
            return null;
        }
        try {
            Plan plan = Plan.fromJson(new JSONObject(stored.json));
            plan.stateRevision = stored.stateRevision;
            plan.profileRevision = stored.profileRevision;
            plan.rulesVersion = stored.rulesVersion;
            return plan;
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "stored plan is unreadable; it will be recomputed", e);
            return null;
        }
    }

    private void awaitLoaded() {
        boolean interrupted = false;
        while (true) {
            try {
                loaded.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------ reads

    @Override
    public Rules rules() {
        awaitLoaded();
        LedgerView view = model.view;
        return view == null ? null : view.rules;
    }

    @Override
    public Snapshot peekSnapshot() {
        return loaded.getCount() > 0 ? null : snapshot();
    }

    @Override
    public Snapshot snapshot() {
        awaitLoaded();
        Model m = model;
        Snapshot s = new Snapshot();
        LedgerView v = m.view;
        s.planErrorZh = m.planErrorZh;
        if (v == null) {
            s.profile = new Profile();
            return s;
        }
        s.observed = v.observed == null ? null : v.observed.copy();
        s.profile = v.profile.copy();
        s.plan = m.plan;
        s.stateRevision = v.stateRevision;
        s.profileRevision = v.profileRevision;
        s.planCurrent = isCurrent(m);
        s.planning = v.observed != null && !s.planCurrent && replanner.busy();
        s.lastRecordMillis = v.lastRecordMillis;
        s.eventsSinceCheckpoint = v.eventsSinceCheckpoint;
        s.noticeZh = v.observed != null && v.mismatches > 0 ? mismatchNotice(v.mismatches) : null;
        return s;
    }

    @Override
    public List<HistoryItem> history(int limit) {
        awaitLoaded();
        LedgerView view;
        List<CheckpointRow> checkpoints;
        List<EventRow> rows;
        synchronized (lock) {
            if (ledger == null || model.view == null) {
                return Collections.emptyList();
            }
            try {
                view = currentViewLocked();
                checkpoints = ledger.checkpoints();
                rows = ledger.allEvents();
            } catch (SQLException e) {
                Log.e(TAG, "history could not be read", e);
                return Collections.emptyList();
            }
        }
        Map<Long, HistoryText.Traced> traces =
                HistoryText.trace(view.rules, view.profile, wiring.applier, checkpoints, rows);
        return HistoryText.items(view.rules, checkpoints, rows, traces, view.checkpointId, limit);
    }

    @Override
    public List<Plan.Step> alternatives(int slot) {
        awaitLoaded();
        Model m = model;
        LedgerView v = m.view;
        if (v == null || v.observed == null) {
            return Collections.emptyList();
        }
        Rules rules = v.rules;
        if (slot < v.observed.slot || slot > rules.lastSlot() || v.profile.lockOf(rules, slot) != null) {
            return Collections.emptyList();
        }
        Profile profile = v.profile.copy();
        try {
            SimState state = forecastUntil(rules, profile, v.observed.copy(), m.plan, slot);
            // The engine labels its alternatives exactly like plan steps and orders them by value.
            List<Plan.Step> labelled = wiring.forecaster.alternatives(rules, profile, state);
            if (labelled != null) {
                return labelled;
            }
            List<Plan.Step> out = new ArrayList<>();
            for (Action action : wiring.forecaster.legalActions(rules, profile, state)) {
                if (!action.isProductive()) {
                    continue;
                }
                Engine.Applied applied = wiring.forecaster.apply(rules, profile, state, action);
                out.add(StepLabels.step(rules, profile, state, action, applied));
            }
            return out;
        } catch (RuntimeException e) {
            Log.w(TAG, "alternatives for slot " + slot + " failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * Follows the plan's (still legal) steps from the observed slot up to {@code slot}; slots
     * without one pass through the forecaster's advance, so day rollovers apply as in the engine.
     */
    private SimState forecastUntil(Rules rules, Profile profile, SimState start, Plan plan, int slot) {
        SimState state = start;
        while (state.slot < slot) {
            int next = state.slot + 1;
            Plan.Step step = plan == null ? null : plan.at(state.slot);
            Action action = step == null ? null : Action.parse(rules, step.action);
            boolean follow = action != null
                    && action.isProductive()
                    && wiring.forecaster.legalActions(rules, profile, state).contains(action);
            SimState after = follow ? wiring.forecaster.apply(rules, profile, state, action).state : null;
            if (after == null || after.slot < next) {
                after = wiring.forecaster.advanceTo(rules, profile, state, next);
            }
            state = after;
        }
        return state;
    }

    @Override
    public String exportBackup() {
        awaitLoaded();
        synchronized (lock) {
            if (ledger == null || model.view == null) {
                return null;
            }
            try {
                Backup.Data data = new Backup.Data();
                data.profileJson = ledger.profileJson();
                data.checkpoints.addAll(ledger.checkpoints());
                data.events.addAll(ledger.allEvents());
                return Backup.write(data, model.view.rules.version, wiring.clock.getAsLong());
            } catch (SQLException | JSONException e) {
                Log.e(TAG, "backup could not be written", e);
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------------ writes

    @Override
    public Receipt recordOutcome(String commandId, long expectedStateRevision, int slot, String actionKey,
                                 Outcome outcome) {
        if (outcome == null || outcome.code == null) {
            return rejectNow(Rejection.invalid(ZH_OUTCOME_MISSING));
        }
        if (outcome.statLv == null || outcome.statLv.length != SimState.STATS) {
            return rejectNow(Rejection.invalid(CommandCheck.ZH_STAT_RANGE));
        }
        String key = actionKey == null ? "" : actionKey;
        return write(commandId, false, view -> {
            OutcomeRecord record = OutcomeRecord.of(view.rules, slot, key, outcome);
            String json = Canonical.of(record.toJson());
            String kind = record.kind();
            Receipt known = existing(commandId, kind, Canonical.sha256(json));
            if (known != null) {
                return known;
            }
            if (expectedStateRevision != view.stateRevision) {
                return stale();
            }
            Rejection r = CommandCheck.outcome(view, slot, key, outcome);
            if (r != null) {
                return r.toReceipt();
            }
            String stored = Canonical.of(resolved(view, record).toJson());
            return append(view, commandId, view.checkpointId, kind, stored);
        });
    }

    /**
     * {@code record} (at {@code view.observed.slot}, already checked) with what it did resolved now,
     * under the profile in effect, so replaying it later under another profile cannot change it:
     * <ul>
     *   <li>the side steps done (unless the outcome says they were not): the outcome's own
     *       {@code sideKeys}, else the installed plan's 顺便 lines when the plan has a step with
     *       this action at this slot (what the sheet showed), else the ones the engine picks for
     *       this action — each kept only if it can be applied now;</li>
     *   <li>for a meeting recorded as predicted without a rank (widget, or a plan that lagged
     *       behind), the RANK it leads to now.</li>
     * </ul>
     */
    private OutcomeRecord resolved(LedgerView view, OutcomeRecord record) {
        try {
            Rules rules = view.rules;
            Action action = Action.parse(rules, record.actionKey);
            Outcome outcome = record.outcome;
            SimState start = view.observed;
            List<String> side = null;
            if (outcome.sideDone) {
                List<String> asked = outcome.sideKeys != null ? outcome.sideKeys : plannedSide(view, record.slot, action);
                Engine.SideSteps done = wiring.applier.sideSteps(rules, view.profile, start, action, asked);
                side = done.applied;
                start = done.state;
            }
            int rankAfter = -1;
            boolean meeting = action != null && action.type == Action.Type.MEET;
            if (meeting && outcome.code == Outcome.Code.AS_PREDICTED && outcome.rank < 0) {
                Outcome main = Outcome.fromJson(outcome.toJson());
                main.sideDone = false;
                main.sideKeys = null;
                SimState after = wiring.applier.apply(rules, view.profile, start.copy(), action, main);
                if (after != null && after.rank != null && action.target < after.rank.length) {
                    rankAfter = after.rank[action.target];
                }
            }
            return record.withResolved(rules, side, rankAfter);
        } catch (JSONException | RuntimeException e) {
            // Never lose a record over this: stored as given, replay then works it out as before.
            Log.w(TAG, "could not resolve what the record at slot " + record.slot + " did", e);
            return record;
        }
    }

    /**
     * The side steps done beside a tower visit, resolved like {@link #resolved}, or null (stored
     * as given) if that fails.
     */
    private List<String> towerSide(LedgerView view, TowerRecord rec) {
        try {
            Action visit = Action.tower(rec.block);
            List<String> asked = rec.sideKeys != null ? rec.sideKeys : plannedSide(view, rec.slot, visit);
            return wiring.applier.sideSteps(view.rules, view.profile, view.observed, visit, asked).applied;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not resolve the side steps of the visit at slot " + rec.slot, e);
            return null;
        }
    }

    /**
     * The 顺便 lines of the installed plan's step at {@code slot} when that step is {@code action}
     * (the lines the sheet showed with it), else null.
     */
    private List<String> plannedSide(LedgerView view, int slot, Action action) {
        Plan plan = model.plan;
        if (plan == null || action == null || !Objects.equals(plan.rulesVersion, view.rules.version)) {
            return null;
        }
        Plan.Step step = plan.at(slot);
        if (step == null || !action.equals(Action.parse(view.rules, step.action))) {
            return null;
        }
        return step.side == null ? new ArrayList<>() : new ArrayList<>(step.side);
    }

    @Override
    public Receipt recordTower(String commandId, long expectedStateRevision, TowerReturn tower) {
        if (tower == null) {
            return rejectNow(Rejection.invalid(ZH_TOWER_MISSING));
        }
        return write(commandId, false, view -> {
            TowerRecord rec = towerRecord(view.rules, tower);
            String json = Canonical.of(rec.toJson());
            Receipt known = existing(commandId, EventKind.TOWER, Canonical.sha256(json));
            if (known != null) {
                return known;
            }
            if (expectedStateRevision != view.stateRevision) {
                return stale();
            }
            Rejection r = CommandCheck.tower(view, rec);
            if (r != null) {
                return r.toReceipt();
            }
            if (rec.sideDone) {
                rec.resolveSide(view.rules, towerSide(view, rec));
            }
            return append(view, commandId, view.checkpointId, EventKind.TOWER, Canonical.of(rec.toJson()));
        });
    }

    @Override
    public Receipt correctTower(String commandId, long expectedStateRevision, long targetSeq, TowerReturn corrected) {
        if (corrected == null) {
            return rejectNow(Rejection.invalid(ZH_TOWER_MISSING));
        }
        return write(commandId, false, view -> {
            EventRow target = towerTarget(ledger.event(targetSeq));
            long towerSeq = target == null ? targetSeq : target.seq;
            TowerRecord rec = towerRecord(view.rules, corrected);
            if (target != null && EventKind.TOWER.equals(target.kind)) {
                // Time and block always come from the original visit: a correction never moves it.
                TowerRecord original = TowerRecord.fromJson(target.payload());
                rec.slot = original.slot;
                rec.block = original.block;
                // The correction form does not ask again what it cannot change: keep the rescue,
                // the level and the side steps of the values in effect.
                TowerRecord effective = effectiveTower(view, target);
                if (rec.rescuedId == null) {
                    rec.rescuedId = effective.rescuedId;
                }
                if (rec.level == null) {
                    rec.level = effective.level;
                }
                rec.sideDone = effective.sideDone;
            }
            // The side steps done beside the visit stay those of the visit's own row.
            rec.sideKeys = null;
            JSONObject payload = new JSONObject();
            payload.put("target", towerSeq);
            payload.put("tower", rec.toJson());
            String json = Canonical.of(payload);
            Receipt known = existing(commandId, EventKind.TOWER_FIX, Canonical.sha256(json));
            if (known != null) {
                return known;
            }
            if (expectedStateRevision != view.stateRevision) {
                return stale();
            }
            Rejection r = CommandCheck.correction(view, target, rec, stateBefore(view, towerSeq));
            if (r != null) {
                return r.toReceipt();
            }
            return append(view, commandId, view.checkpointId, EventKind.TOWER_FIX, json);
        });
    }

    @Override
    public Receipt voidEvent(String commandId, long expectedStateRevision, long targetSeq) {
        String json = canonical(() -> new JSONObject().put("target", targetSeq));
        return write(commandId, false, view -> {
            Receipt known = existing(commandId, EventKind.VOID, Canonical.sha256(json));
            if (known != null) {
                return known;
            }
            if (expectedStateRevision != view.stateRevision) {
                return stale();
            }
            EventRow target = ledger.event(targetSeq);
            Rejection r = CommandCheck.voidTarget(view, target);
            if (r != null) {
                return r.toReceipt();
            }
            Receipt receipt = append(view, commandId, view.checkpointId, EventKind.VOID, json);
            ledger.setVoided(targetSeq, true);
            return receipt;
        });
    }

    @Override
    public Receipt saveCheckpoint(String commandId, long expectedStateRevision, SimState observed,
                                  Profile profileOrNull) {
        if (!isComplete(observed)) {
            return rejectNow(Rejection.invalid(observed == null
                    ? CommandCheck.ZH_STATE_MISSING : CommandCheck.ZH_STATE_INCOMPLETE));
        }
        String stateJson = canonical(observed::toJson);
        String profileJson = profileOrNull == null ? null : canonical(profileOrNull::toJson);
        String fingerprint = Canonical.sha256(canonical(() -> new JSONObject()
                .put("state", new JSONObject(stateJson))
                .put("profile", profileJson == null ? JSONObject.NULL : new JSONObject(profileJson))));
        return write(commandId, false, view -> {
            Receipt known = existing(commandId, EventKind.CHECKPOINT, fingerprint);
            if (known != null) {
                return known;
            }
            if (expectedStateRevision != view.stateRevision) {
                return stale();
            }
            Rejection r = CommandCheck.checkpoint(view.rules, observed);
            if (r != null) {
                return r.toReceipt();
            }
            long now = wiring.clock.getAsLong();
            SimState fitted = StateShape.inferRescues(StateShape.fit(observed, view.rules), view.rules);
            long checkpointId = ledger.insertCheckpoint(now, CheckpointRow.jsonFor(fitted, view.rules),
                    view.rules.version);
            if (profileOrNull != null) {
                ledger.putProfileJson(normalizedProfile(profileOrNull));
                ledger.bumpProfileRevision();
            }
            return append(view, commandId, checkpointId, EventKind.CHECKPOINT, journal(checkpointId, fingerprint));
        });
    }

    @Override
    public Receipt saveProfile(String commandId, long expectedProfileRevision, Profile profile) {
        if (profile == null) {
            return rejectNow(Rejection.invalid(CommandCheck.ZH_PROFILE_MISSING));
        }
        String fingerprint = Canonical.sha256(canonical(profile::toJson));
        return write(commandId, false, view -> {
            Receipt known = existing(commandId, EventKind.PROFILE, fingerprint);
            if (known != null) {
                return known;
            }
            if (expectedProfileRevision != view.profileRevision) {
                return stale();
            }
            ledger.putProfileJson(normalizedProfile(profile));
            ledger.bumpProfileRevision();
            long seq = ledger.insertEvent(commandId, view.checkpointId, EventKind.PROFILE,
                    journal(0, fingerprint), wiring.clock.getAsLong(), view.rules.version);
            return ok(seq);
        });
    }

    @Override
    public Receipt importBackup(String commandId, String json) {
        Backup.Data data;
        String fingerprint;
        try {
            data = Backup.read(json);
            fingerprint = Canonical.sha256(Canonical.normalize(json));
        } catch (Backup.Invalid | JSONException e) {
            Log.w(TAG, "backup rejected", e);
            String zh = e instanceof Backup.Invalid ? ((Backup.Invalid) e).zh : Backup.ZH_DAMAGED;
            return rejectNow(Rejection.invalid(zh));
        }
        return write(commandId, true, view -> {
            Receipt known = existing(commandId, EventKind.IMPORT, fingerprint);
            if (known != null) {
                if (known.duplicate) {
                    // Same command id and backup: nothing is replaced, and the UI must not say
                    // "restored" as if it had just happened.
                    known.noticeZh = ledger.lastSeq() > known.eventSeq ? ZH_IMPORT_ALREADY_LATER : ZH_IMPORT_ALREADY;
                }
                return known;
            }
            Rejection r = checkImport(view.rules, data);
            if (r != null) {
                return r.toReceipt();
            }
            ledger.clearForImport();
            for (CheckpointRow c : data.checkpoints) {
                ledger.insertCheckpointRow(c);
            }
            for (EventRow e : data.events) {
                ledger.insertEventRow(e);
            }
            if (data.profileJson != null) {
                ledger.putProfileJson(data.profileJson);
            }
            ledger.bumpProfileRevision();
            long latest = data.latestCheckpointId();
            return append(view, commandId, latest, EventKind.IMPORT, journal(latest, fingerprint));
        });
    }

    /**
     * The newest checkpoint of a backup, carried over to the loaded pack the way replay will carry
     * it (by id when it has a layout), must be a valid current state. Values that no longer fit are
     * reported after the import through {@link Snapshot#noticeZh}.
     */
    private static Rejection checkImport(Rules rules, Backup.Data data) throws JSONException {
        if (data.checkpoints.isEmpty()) {
            return null;
        }
        CheckpointRow latest = data.checkpoints.get(data.checkpoints.size() - 1);
        Rejection r = CommandCheck.checkpoint(rules, latest.stateFor(rules).state);
        return r == null ? null : Rejection.invalid(Backup.ZH_DAMAGED);
    }

    // ------------------------------------------------------------------------------ replanning

    @Override
    public void requestReplan() {
        awaitLoaded();
        if (model.view == null) {
            // Loading failed earlier (rule pack or database): 重试 opens them again.
            worker.execute(this::retryOpen);
            return;
        }
        synchronized (lock) {
            failedPlanKey = null;
        }
        if (needsPlan(model) && replanner.schedule()) {
            notifyListeners();
        }
    }

    private static boolean isCurrent(Model m) {
        LedgerView v = m.view;
        Plan p = m.plan;
        return v != null && p != null
                && p.stateRevision == v.stateRevision
                && p.profileRevision == v.profileRevision
                && Objects.equals(p.rulesVersion, v.rules.version);
    }

    private static boolean needsPlan(Model m) {
        return m.view != null && m.view.observed != null && !isCurrent(m);
    }

    /**
     * The installed plan is current but ran out of time, and its revisions have not had their
     * follow-up yet ({@link ReplanWorker.Job#followUp}). Caller holds {@link #lock}.
     */
    private boolean needsFollowUpLocked(Model m) {
        return isCurrent(m) && m.view.observed != null && m.plan.followUpPending() && wiring.planBudgetMillis > 0
                && !planKey(m.view).equals(followUpKey);
    }

    /**
     * The follow-up for the installed plan's revisions has run without replacing it (not better,
     * or failed): the plan is marked {@link Plan#followUp} and its budget note no longer promises
     * another run ({@link Plan#markFollowUpDone}), in memory and in the store. Caller holds
     * {@link #lock}.
     */
    private void followUpDoneLocked(Model m) {
        if (!isCurrent(m) || m.plan.followUp) {
            return;
        }
        Plan marked;
        try {
            marked = Plan.fromJson(m.plan.toJson());
        } catch (JSONException e) {
            Log.w(TAG, "plan could not be copied; marking it in place", e);
            marked = m.plan;
        }
        marked.markFollowUpDone();
        persistPlanLocked(marked);
        model = m.withPlan(marked);
    }

    /** Budget of the follow-up computation: twice the normal one. */
    static long followUpBudget(long budgetMillis) {
        return budgetMillis > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : budgetMillis * 2;
    }

    /**
     * Whether a follow-up result replaces the installed plan for the same revisions, with the
     * planner's strict goal priority: the goals in priority order (「必须」 then 「想要」, each by
     * order) decide first, each by its end rank up to its target (reaching the target ties); with the
     * goals tied a complete plan replaces one that ran out of time, and between two that ran out of
     * time more links at MAX, then the higher sum of end ranks, wins.
     */
    static boolean improves(Profile profile, Plan candidate, Plan current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        int[] a = candidate.summary.endRanks;
        int[] b = current.summary.endRanks;
        for (int l : goalOrder(profile)) {
            if (l >= a.length || l >= b.length) {
                continue;
            }
            int target = profile.target[l] > 0 ? profile.target[l] : 10;
            int ra = Math.min(a[l], target);
            int rb = Math.min(b[l], target);
            if (ra != rb) {
                return ra > rb;
            }
        }
        if (!candidate.budgetExhausted) {
            return true;
        }
        int maxedA = 0;
        int maxedB = 0;
        int sumA = 0;
        int sumB = 0;
        for (int r : a) {
            maxedA += r >= 10 ? 1 : 0;
            sumA += r;
        }
        for (int r : b) {
            maxedB += r >= 10 ? 1 : 0;
            sumB += r;
        }
        return maxedA != maxedB ? maxedA > maxedB : sumA > sumB;
    }

    /** Goal links in priority order: 「必须」 then 「想要」, each by {@link Profile#order}, ties by index. */
    private static List<Integer> goalOrder(Profile p) {
        List<Integer> out = new ArrayList<>();
        for (int l = 0; l < p.tier.length; l++) {
            if (p.isGoal(l)) {
                out.add(l);
            }
        }
        out.sort((x, y) -> {
            int tx = p.tier[x] == Profile.Tier.MUST ? 0 : 1;
            int ty = p.tier[y] == Profile.Tier.MUST ? 0 : 1;
            if (tx != ty) {
                return Integer.compare(tx, ty);
            }
            return p.order[x] != p.order[y] ? Integer.compare(p.order[x], p.order[y]) : Integer.compare(x, y);
        });
        return out;
    }

    private static String planKey(LedgerView v) {
        return v.stateRevision + "/" + v.profileRevision + "/" + v.rules.version;
    }

    private Engine.Options options(LedgerView view) {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = wiring.planBudgetMillis;
        options.towerIncomePerVisit = TowerIncome.estimate(view.towerGains, view.currentBlock());
        return options;
    }

    /** Worker-thread side of replanning. */
    private final class ReplanHost implements ReplanWorker.Host {
        @Override
        public ReplanWorker.Job nextJob() {
            synchronized (lock) {
                Model m = model;
                boolean followUp = !needsPlan(m) && m.view != null && needsFollowUpLocked(m);
                if (!followUp && (!needsPlan(m) || planKey(m.view).equals(failedPlanKey))) {
                    return null;
                }
                ReplanWorker.Job job = new ReplanWorker.Job();
                job.rules = m.view.rules;
                job.profile = m.view.profile.copy();
                job.observed = m.view.observed.copy();
                job.previous = m.plan;
                job.stateRevision = m.view.stateRevision;
                job.profileRevision = m.view.profileRevision;
                job.options = options(m.view);
                if (followUp) {
                    // Exactly one follow-up per revisions, even if it runs out of time again.
                    followUpKey = planKey(m.view);
                    job.followUp = true;
                    job.options.followUp = true;
                    job.options.timeBudgetMillis = followUpBudget(wiring.planBudgetMillis);
                }
                return job;
            }
        }

        @Override
        public boolean finish(ReplanWorker.Job job, Plan plan, List<String> problems, Throwable error) {
            synchronized (lock) {
                Model m = model;
                boolean stale = m.view == null
                        || m.view.stateRevision != job.stateRevision
                        || m.view.profileRevision != job.profileRevision
                        || m.view.rules != job.rules;
                if (stale) {
                    return true;
                }
                if (error instanceof CancellationException) {
                    // Cancelled while still current (shutdown): leave everything as it was.
                    return false;
                }
                if (job.followUp) {
                    // The installed plan stays unless the follow-up is complete or better; a
                    // failed follow-up is only logged (the installed plan is still valid). Either
                    // way the follow-up has run: no plan of these revisions promises another one.
                    if (error != null || plan == null || !problems.isEmpty()) {
                        Log.w(TAG, "budget follow-up not used: " + (error != null ? error : problems));
                        followUpDoneLocked(m);
                        return false;
                    }
                    if (isCurrent(m) && improves(job.profile, plan, m.plan)) {
                        plan.markFollowUpDone();
                        persistPlanLocked(plan);
                        model = m.withPlan(plan);
                    } else {
                        followUpDoneLocked(m);
                    }
                    return false;
                }
                if (error != null || plan == null || !problems.isEmpty()) {
                    if (!problems.isEmpty()) {
                        Log.e(TAG, "plan rejected by the validator: " + problems);
                    }
                    failedPlanKey = planKey(m.view);
                    model = m.withError(m.plan != null ? ZH_PLAN_FAILED_KEPT : ZH_PLAN_FAILED_NONE);
                    return false;
                }
                persistPlanLocked(plan);
                failedPlanKey = null;
                model = m.withPlan(plan);
            }
            return false;
        }

        /**
         * After every run, failed ones included: the worker is no longer busy here, so the widgets
         * re-render with {@code planning == false} and show the new plan, the error card or the
         * "not updated" cue instead of staying on "正在生成安排…".
         */
        @Override
        public void runEnded() {
            refreshWidgets();
            notifyListeners();
            boolean followUp;
            synchronized (lock) {
                followUp = needsFollowUpLocked(model);
            }
            if (followUp) {
                // The plan just installed ran out of time: compute it once more with twice the budget.
                replanner.schedule();
            }
        }
    }

    /** Stores the plan row if its revisions still match; a failure keeps it in memory only. */
    private void persistPlanLocked(Plan plan) {
        if (ledger == null) {
            return;
        }
        try {
            String json = plan.toJson().toString();
            ledger.inTransaction(() -> {
                if (ledger.stateRevision() == plan.stateRevision
                        && ledger.profileRevision() == plan.profileRevision) {
                    ledger.putPlan(new Ledger.StoredPlan(plan.stateRevision, plan.profileRevision,
                            plan.rulesVersion, json));
                }
                return null;
            });
        } catch (JSONException | SQLException e) {
            Log.w(TAG, "plan could not be stored; it stays in memory", e);
        }
    }

    // ------------------------------------------------------------------------------ listeners

    @Override
    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Posts one coalesced change notification to the main thread. */
    private void notifyListeners() {
        if (listeners.isEmpty() || !notifyPosted.compareAndSet(false, true)) {
            return;
        }
        mainHandler.post(() -> {
            notifyPosted.set(false);
            for (Listener listener : listeners) {
                listener.onChanged(snapshot());
            }
        });
    }

    private void refreshWidgets() {
        try {
            wiring.widgetRefresh.run();
        } catch (RuntimeException e) {
            Log.w(TAG, "widget refresh failed", e);
        }
    }

    // ------------------------------------------------------------------------------ write plumbing

    /**
     * Runs one command: lock, transaction, view rebuild after a change, then replan, widget
     * refresh and listener notification outside the lock.
     *
     * @param dropPlan forget the installed plan after a successful write (backup import)
     */
    private Receipt write(String commandId, boolean dropPlan, TxCommand command) {
        awaitLoaded();
        if (commandId == null || commandId.trim().isEmpty() || commandId.length() > 200) {
            return rejectNow(Rejection.invalid(ZH_COMMAND_ID));
        }
        Receipt receipt;
        boolean changed;
        synchronized (lock) {
            if (ledger == null || model.view == null) {
                return io(model.planErrorZh != null ? model.planErrorZh : ZH_IO);
            }
            try {
                LedgerView view = currentViewLocked();
                receipt = ledger.inTransaction(() -> {
                    try {
                        return command.run(view);
                    } catch (JSONException e) {
                        throw new IllegalArgumentException("payload is not valid JSON", e);
                    }
                });
            } catch (SQLException e) {
                Log.e(TAG, "write failed", e);
                receipt = io(ZH_IO);
            }
            changed = receipt.ok && !receipt.duplicate;
            if (changed) {
                rebuildAfterWriteLocked(dropPlan);
            }
            fillRevisions(receipt, model.view);
        }
        if (changed) {
            if (wiring.cancelSupersededPlans) {
                replanner.cancelOutdated(receipt.stateRevision, receipt.profileRevision);
            }
            replanner.schedule();
            refreshWidgets();
            notifyListeners();
        }
        return receipt;
    }

    private void rebuildAfterWriteLocked(boolean dropPlan) {
        Model m = dropPlan ? new Model(model.view, null, null) : model;
        try {
            model = m.withView(LedgerView.load(ledger, m.view.rules, wiring.applier));
        } catch (SQLException e) {
            // The write is durable; currentViewLocked() reloads before the next command.
            Log.e(TAG, "view rebuild failed", e);
            model = m;
        }
    }

    /** The cached view, reloaded first if the database moved on (e.g. a failed rebuild). */
    private LedgerView currentViewLocked() {
        LedgerView view = model.view;
        if (view.stateRevision != ledger.stateRevision() || view.profileRevision != ledger.profileRevision()) {
            view = LedgerView.load(ledger, view.rules, wiring.applier);
            model = model.withView(view);
        }
        return view;
    }

    /** Duplicate or conflicting reuse of {@code commandId}, or null for a new command. */
    private Receipt existing(String commandId, String kind, String fingerprint) {
        EventRow old = ledger.eventByCommand(commandId);
        if (old == null) {
            return null;
        }
        if (!old.kind.equals(kind) || !fingerprint.equals(fingerprintOf(old))) {
            Receipt r = new Receipt();
            r.errorCode = Receipt.CODE_CONFLICT;
            r.errorZh = ZH_CONFLICT;
            return r;
        }
        Receipt r = ok(old.seq);
        r.duplicate = true;
        return r;
    }

    /**
     * Fingerprint of the command that wrote {@code row}: journal rows store it; observed rows hash
     * their payload without the values resolved when they were written ({@link EventRow#commandSha256}),
     * so a retry of the same command matches even though the state has moved on since.
     */
    private static String fingerprintOf(EventRow row) {
        if (EventKind.isJournal(row.kind)) {
            try {
                return row.payload().optString("payloadSha256", "");
            } catch (JSONException e) {
                return "";
            }
        }
        if (EventKind.isObserved(row.kind)) {
            try {
                return EventRow.commandSha256(row.json);
            } catch (JSONException e) {
                // Unreadable payloads can only match themselves.
                return Canonical.sha256(row.json);
            }
        }
        return Canonical.sha256(row.json);
    }

    /**
     * Appends an event row stamped with the loaded pack's version and bumps the state revision.
     */
    private Receipt append(LedgerView view, String commandId, long checkpointId, String kind, String json) {
        long seq = ledger.insertEvent(commandId, checkpointId, kind, json, wiring.clock.getAsLong(),
                view.rules.version);
        ledger.bumpStateRevision();
        return ok(seq);
    }

    /**
     * The tower row a correction is about: {@code row} itself, or the tower row a correction row
     * targets. Anything else is returned unchanged; {@link CommandCheck#correction} then rejects
     * what cannot be corrected (other kinds, older checkpoints, undone rows).
     */
    private EventRow towerTarget(EventRow row) {
        if (row == null || !EventKind.TOWER_FIX.equals(row.kind)) {
            return row;
        }
        EventRow tower = ledger.event(row.target());
        return tower != null && EventKind.TOWER.equals(tower.kind) ? tower : row;
    }

    /** Values of tower row {@code target} now in effect: its latest non-voided correction, else its own. */
    private static TowerRecord effectiveTower(LedgerView view, EventRow target) throws JSONException {
        TowerRecord effective = TowerRecord.fromJson(target.payload());
        for (EventRow row : view.chain) {
            if (!row.voided && EventKind.TOWER_FIX.equals(row.kind) && row.target() == target.seq) {
                effective = TowerRecord.fromJson(row.payload().getJSONObject("tower"));
            }
        }
        return effective;
    }

    /** Replayed state just before row {@code seq} of the current chain, or null. */
    private SimState stateBefore(LedgerView view, long seq) throws JSONException {
        CheckpointRow checkpoint = ledger.latestCheckpoint();
        if (checkpoint == null) {
            return null;
        }
        SimState[] found = new SimState[1];
        new Replayer(view.rules, view.profile, wiring.applier).replay(checkpoint.stateFor(view.rules).state,
                view.chain, (row, before, after, tower) -> {
                    if (row.seq == seq) {
                        found[0] = before;
                    }
                });
        return found[0];
    }

    private static TowerRecord towerRecord(Rules rules, TowerReturn tower) {
        TowerRecord rec = new TowerRecord();
        rec.slot = tower.slot;
        rec.block = tower.block;
        if (rec.block < 0) {
            Rules.Block block = rules.blockOn(Rules.dayOf(tower.slot));
            rec.block = block == null ? -1 : block.idx;
        }
        rec.floor = tower.floor;
        rec.balanceMode = tower.balanceMode;
        rec.amount = tower.amount;
        rec.level = tower.level;
        rec.rescuedId = tower.rescuedId;
        rec.cashUnknown = tower.cashUnknown;
        rec.sideDone = tower.sideDone;
        rec.sideKeys = tower.sideDone && tower.sideKeys != null ? new ArrayList<>(tower.sideKeys) : null;
        return rec;
    }

    private static String normalizedProfile(Profile profile) throws JSONException {
        return Canonical.of(Profile.fromJson(profile.toJson()).toJson());
    }

    private static String journal(long checkpointId, String fingerprint) throws JSONException {
        JSONObject o = new JSONObject();
        if (checkpointId > 0) {
            o.put("checkpointId", checkpointId);
        }
        o.put("payloadSha256", fingerprint);
        return Canonical.of(o);
    }

    private static boolean isComplete(SimState s) {
        return s != null && s.statPts != null && s.rank != null && s.pts != null && s.ready != null
                && s.unlockStep != null && s.lastStepDay != null && s.towerVisits != null
                && s.shifts != null && s.used != null;
    }

    private static String canonical(JsonSource source) {
        try {
            return Canonical.of(source.get());
        } catch (JSONException e) {
            throw new IllegalArgumentException("payload is not valid JSON", e);
        }
    }

    private Receipt rejectNow(Rejection rejection) {
        awaitLoaded();
        Receipt r = rejection.toReceipt();
        fillRevisions(r, model.view);
        return r;
    }

    private static Receipt ok(long seq) {
        Receipt r = new Receipt();
        r.ok = true;
        r.eventSeq = seq;
        return r;
    }

    private static Receipt stale() {
        Receipt r = new Receipt();
        r.errorCode = Receipt.CODE_STALE;
        r.errorZh = ZH_STALE;
        return r;
    }

    private static Receipt io(String zh) {
        Receipt r = new Receipt();
        r.errorCode = Receipt.CODE_IO;
        r.errorZh = zh;
        return r;
    }

    private static void fillRevisions(Receipt receipt, LedgerView view) {
        if (view != null) {
            receipt.stateRevision = view.stateRevision;
            receipt.profileRevision = view.profileRevision;
            receipt.nextSlot = view.observed == null ? -1 : view.observed.slot;
        }
    }

    // ------------------------------------------------------------------------------ lifecycle

    /**
     * Waits until no replan is queued or running (tests and diagnostics; never call on the main
     * thread). Returns false on timeout.
     */
    boolean awaitIdle(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            do {
                long left = deadline - System.nanoTime();
                worker.submit(() -> { }).get(Math.max(1, left), TimeUnit.NANOSECONDS);
            } while (replanner.busy() && System.nanoTime() < deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
        return !replanner.busy();
    }

    /** Lets the worker thread finish its queue and exit, without waiting for it. */
    private void retire() {
        replanner.cancelAll();
        worker.shutdown();
    }

    /**
     * Stops the worker and closes the database. The process-wide instance normally lives as long
     * as the process; this exists for tests that simulate a restart.
     */
    void close() {
        replanner.cancelAll();
        worker.shutdown();
        try {
            worker.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            if (ledger != null) {
                ledger.close();
                ledger = null;
            }
        }
        synchronized (PlannerService.class) {
            if (instance == this) {
                instance = null;
            }
        }
    }
}
