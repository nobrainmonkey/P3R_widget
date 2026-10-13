package app.p3r.dayplanner.v3.service;

import android.util.Log;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
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
 *       (its result would be discarded anyway).</li>
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
    }

    /** The service side of the worker. */
    interface Host {
        /** Inputs for the next computation, or null when nothing needs planning. */
        Job nextJob();

        /**
         * Installs {@code plan} or records the failure. Returns true when the job's revisions are
         * no longer current, so the result was discarded and the worker should compute again.
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
    private volatile AtomicBoolean inFlight;

    ReplanWorker(Executor executor, Host host, PlanFunction planner, PlanCheck validator, LongSupplier clock) {
        this.executor = executor;
        this.host = host;
        this.planner = planner;
        this.validator = validator;
        this.clock = clock;
    }

    /** Queues a run unless one is already queued. Returns true when this call queued it. */
    boolean schedule() {
        if (!queued.compareAndSet(false, true)) {
            return false;
        }
        executor.execute(this::run);
        return true;
    }

    /** A run is queued or computing. */
    boolean busy() {
        return queued.get() || running;
    }

    /** Asks the computation in flight (if any) to stop early. */
    void cancelInFlight() {
        AtomicBoolean flag = inFlight;
        if (flag != null) {
            flag.set(true);
        }
    }

    private void run() {
        running = true;
        queued.set(false);
        try {
            while (true) {
                Job job = host.nextJob();
                if (job == null) {
                    return;
                }
                if (!compute(job)) {
                    return;
                }
            }
        } finally {
            running = false;
            host.runEnded();
        }
    }

    /** Computes, validates and hands over one plan; true = stale, compute again. */
    private boolean compute(Job job) {
        AtomicBoolean cancel = new AtomicBoolean();
        job.options.cancelled = cancel;
        inFlight = cancel;
        Plan plan = null;
        List<String> problems = Collections.emptyList();
        Throwable error = null;
        try {
            plan = planner.plan(job.rules, job.profile, job.observed, job.previous, job.options);
            if (plan != null) {
                stamp(plan, job);
                problems = validator.validate(job.rules, job.profile, job.observed, plan);
                if (problems == null) {
                    problems = Collections.emptyList();
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            error = e;
            if (!cancel.get()) {
                Log.e(TAG, "planning failed", e);
            }
        } finally {
            inFlight = null;
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
