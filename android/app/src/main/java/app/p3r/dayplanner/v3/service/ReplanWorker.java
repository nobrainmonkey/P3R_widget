package app.p3r.dayplanner.v3.service;

import android.util.Log;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Background replanning (docs/DESIGN_v3.md §10.6), on the service's single worker thread.
 *
 * <ul>
 *   <li>Coalescing: any number of {@link #schedule} calls while a run is queued result in one run;
 *       a run keeps going until the installed plan matches the latest revisions.</li>
 *   <li>Plans are computed outside the service lock from copies of the inputs, stamped with the
 *       revisions they were computed for and checked by the validator; the host installs a result
 *       only if both revisions are still current, otherwise the run recomputes.</li>
 *   <li>A write during a computation cancels it early through {@code Engine.Options.cancelled}
 *       (its result would be discarded anyway). Only computations for older revisions are
 *       cancelled, so a late cancel can never hit the computation for the latest state; a full
 *       replan cancelled this way is computed again for the new revisions.</li>
 *   <li>Follow-up: when the installed plan ran out of time ({@link Plan#budgetExhausted}), the
 *       host queues exactly one more computation for the same revisions with twice the budget, capped at 8 s
 *       ({@link Job#followUp}) and installs its result only if it is complete or better.</li>
 *   <li>Full replan (从头重新安排, {@link Job#full}): computed without the installed plan as
 *       {@code previous} and with three times the budget, capped at 8 s. A request supersedes the other
 *       computation in flight ({@link #cancelPartial}); 「先不算了」 stops only the full one
 *       ({@link #cancelFull}).</li>
 *   <li>Manual deep search shares the full-replan lifecycle, with a fixed 30 s budget and no
 *       automatic follow-up. Its current-plan comparison candidate is validated before search.</li>
 *   <li>深度修改 opts automatic record/settings replans into one 12 s deep search, with no extra
 *       follow-up. It remains an ordinary replan rather than a manual full-replan ticket.</li>
 * </ul>
 */
final class ReplanWorker {
    private static final String TAG = "P3rReplan";

    /** Inputs of one computation (copies; the planner may not see later writes). */
    static final class Job {
        Rules rules;
        Profile profile;
        SimState observed;
        Plan previous;
        long stateRevision;
        long profileRevision;
        Engine.Options options;
        /**
         * The one extra computation, with twice the time budget capped at 8 s, that follows an installed plan
         * which ran out of time ({@link Plan#budgetExhausted}) for the same revisions.
         */
        boolean followUp;
        /**
         * 从头重新安排: computed with no {@link #previous} plan (no stability bias) and three times
         * the budget capped at 8 s (or a manual 30 s deep search), for the request {@link #ticket}.
         */
        boolean full;
        /** The request a {@link #full} job answers ({@code PlannerApi#requestFullReplan}); 0 otherwise. */
        long ticket;
    }

    /** The service side of the worker. */
    interface Host {
        /** Inputs for the next computation, or null when nothing needs planning. */
        Job nextJob();

        /**
         * Installs {@code plan} or records the failure. Returns true when the job's revisions are
         * no longer current, so the result was discarded and the worker should compute again.
         * A cancelled computation arrives as a {@link CancellationException} error.
         */
        boolean finish(Job job, Plan plan, List<String> problems, Throwable error);

        /** Called on the worker thread when a run ends ({@link #busy()} may have changed). */
        void runEnded();
    }

    private final Executor executor;
    private final Host host;
    private final PlanFunction planner;
    private final PlanCheck validator;
    private final LongSupplier clock;
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile boolean running;
    /** Job being computed (its {@code options.cancelled} is set), or null. */
    private volatile Job inFlight;
    /** Set by {@link #cancelAll}: {@link #schedule} queues nothing any more. */
    private volatile boolean stopped;

    ReplanWorker(Executor executor, Host host, PlanFunction planner, PlanCheck validator, LongSupplier clock) {
        this.executor = executor;
        this.host = host;
        this.planner = planner;
        this.validator = validator;
        this.clock = clock;
    }

    /** Queues a run unless one is already queued. Returns true when this call queued it. */
    boolean schedule() {
        if (stopped || !queued.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(this::run);
        } catch (RejectedExecutionException e) {
            // The service was closed; nothing will plan any more.
            queued.set(false);
            return false;
        }
        return true;
    }

    /** A run is queued or computing. */
    boolean busy() {
        return queued.get() || running;
    }

    /**
     * Asks the computation in flight to stop early if it was started for revisions older than the
     * given ones (revisions only grow).
     */
    void cancelOutdated(long stateRevision, long profileRevision) {
        Job job = inFlight;
        if (job != null && (job.stateRevision < stateRevision || job.profileRevision < profileRevision)) {
            job.options.cancelled.set(true);
        }
    }

    /**
     * Asks the computation in flight to stop unless it is a full replan: a full request supersedes
     * a normal computation or a follow-up (the full one replaces their result anyway).
     */
    void cancelPartial() {
        Job job = inFlight;
        if (job != null && !job.full) {
            job.options.cancelled.set(true);
        }
    }

    /** Asks the full replan in flight, if any, to stop (「先不算了」); other computations go on. */
    void cancelFull() {
        Job job = inFlight;
        if (job != null && job.full) {
            job.options.cancelled.set(true);
        }
    }

    /** Asks any computation in flight to stop and queues no further runs (shutdown). */
    void cancelAll() {
        stopped = true;
        Job job = inFlight;
        if (job != null) {
            job.options.cancelled.set(true);
        }
    }

    /** {@link #cancelAll} was called: the service is shutting down. */
    boolean stopped() {
        return stopped;
    }

    private void run() {
        running = true;
        queued.set(false);
        try {
            while (true) {
                Job job = host.nextJob();
                if (job == null || !compute(job)) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            // Never let a bug escape to the thread's uncaught-exception handler (app crash).
            Log.e(TAG, "replanning stopped", e);
        } finally {
            running = false;
            host.runEnded();
        }
    }

    /** Computes, validates and hands over one plan; true = stale, compute again. */
    private boolean compute(Job job) {
        AtomicBoolean cancel = new AtomicBoolean();
        job.options.cancelled = cancel;
        inFlight = job;
        Plan plan = null;
        List<String> problems = Collections.emptyList();
        Throwable error = null;
        try {
            if (job.options.deepSearch && job.options.incumbentPlan != null) {
                List<String> found = validator.validate(job.rules, job.profile, job.observed,
                        job.options.incumbentPlan);
                if (found != null && !found.isEmpty()) {
                    job.options.incumbentPlan = null;
                }
            }
            plan = planner.plan(job.rules, job.profile, job.observed, job.previous, job.options);
            if (plan != null && !cancel.get()) {
                stamp(plan, job);
                List<String> found = validator.validate(job.rules, job.profile, job.observed, plan);
                problems = found == null ? Collections.emptyList() : found;
            }
        } catch (RuntimeException | StackOverflowError e) {
            error = e;
            if (!cancel.get()) {
                Log.e(TAG, "planning failed", e);
            }
        } finally {
            inFlight = null;
        }
        if (cancel.get()) {
            // Superseded by a write (or shutdown): the result is not used and was not validated.
            return host.finish(job, null, Collections.emptyList(), new CancellationException("superseded"));
        }
        return host.finish(job, plan, problems, error);
    }

    /** Ties the plan to the revisions and rule pack it was computed for. */
    private void stamp(Plan plan, Job job) {
        plan.stateRevision = job.stateRevision;
        plan.profileRevision = job.profileRevision;
        plan.rulesVersion = job.rules.version;
        if (plan.createdMillis == 0) {
            plan.createdMillis = clock.getAsLong();
        }
        if (plan.planId == null || plan.planId.isEmpty()) {
            plan.planId = "s" + job.stateRevision + "p" + job.profileRevision;
        }
    }
}
