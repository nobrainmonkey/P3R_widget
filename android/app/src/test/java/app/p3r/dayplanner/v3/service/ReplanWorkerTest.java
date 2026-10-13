package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.TestRules;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Cancellation and scheduling rules of the replan worker, with an inline executor (plain JVM). */
public class ReplanWorkerTest {
    private final Rules rules = TestRules.create();

    /** Hands out one job for revision 3/1, records what {@link #finish} receives. */
    private final class Host implements ReplanWorker.Host {
        boolean handedOut;
        final List<Plan> finishedPlans = new ArrayList<>();
        final List<Throwable> finishedErrors = new ArrayList<>();
        int runsEnded;

        @Override
        public ReplanWorker.Job nextJob() {
            if (handedOut) {
                return null;
            }
            handedOut = true;
            ReplanWorker.Job job = new ReplanWorker.Job();
            job.rules = rules;
            job.profile = new Profile();
            job.observed = TestRules.state(rules, TestRules.slot(TestRules.MAY_12, false));
            job.stateRevision = 3;
            job.profileRevision = 1;
            job.options = new Engine.Options();
            return job;
        }

        @Override
        public boolean finish(ReplanWorker.Job job, Plan plan, List<String> problems, Throwable error) {
            finishedPlans.add(plan);
            finishedErrors.add(error);
            return false;
        }

        @Override
        public void runEnded() {
            runsEnded++;
        }
    }

    @Test
    public void aLateCancelForOlderRevisionsSparesTheCurrentComputation() {
        Host host = new Host();
        AtomicReference<ReplanWorker> worker = new AtomicReference<>();
        Fakes.Validator validator = new Fakes.Validator();
        PlanFunction planner = (r, p, observed, previous, options) -> {
            worker.get().cancelOutdated(3, 0);
            worker.get().cancelOutdated(2, 1);
            return new Plan();
        };
        worker.set(new ReplanWorker(Runnable::run, host, planner, validator, () -> 42L));

        assertTrue(worker.get().schedule());

        assertEquals(1, host.finishedPlans.size());
        Plan plan = host.finishedPlans.get(0);
        assertNotNull(plan);
        assertNull(host.finishedErrors.get(0));
        assertEquals(3, plan.stateRevision);
        assertEquals(1, plan.profileRevision);
        assertEquals(rules.version, plan.rulesVersion);
        assertEquals(42L, plan.createdMillis);
        assertEquals("s3p1", plan.planId);
        assertEquals(1, validator.calls.get());
        assertEquals(1, host.runsEnded);
        assertFalse(worker.get().busy());
    }

    @Test
    public void aComputationForOlderRevisionsIsCancelledAndNotValidated() {
        Host host = new Host();
        AtomicReference<ReplanWorker> worker = new AtomicReference<>();
        Fakes.Validator validator = new Fakes.Validator();
        PlanFunction planner = (r, p, observed, previous, options) -> {
            worker.get().cancelOutdated(4, 1);
            assertTrue(options.cancelled.get());
            return new Plan();
        };
        worker.set(new ReplanWorker(Runnable::run, host, planner, validator, () -> 0L));

        worker.get().schedule();

        assertNull(host.finishedPlans.get(0));
        assertTrue(host.finishedErrors.get(0) instanceof CancellationException);
        assertEquals(0, validator.calls.get());
    }

    @Test
    public void schedulingAfterShutdownIsHarmless() {
        Host host = new Host();
        ReplanWorker worker = new ReplanWorker(task -> {
            throw new RejectedExecutionException("closed");
        }, host, (r, p, o, prev, opts) -> new Plan(), (r, p, o, plan) -> Collections.emptyList(), () -> 0L);

        assertFalse(worker.schedule());
        assertFalse(worker.busy());
        assertTrue(host.finishedPlans.isEmpty());
    }
}
