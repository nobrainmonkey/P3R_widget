package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.TestRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test doubles for the engine (a stub in this module's worktree). */
final class Fakes {
    private Fakes() {}

    /** Records every call; can block one call (the first by default) to simulate a slow computation. */
    static final class Planner implements PlanFunction {
        final AtomicInteger calls = new AtomicInteger();
        final List<Engine.Options> options = new CopyOnWriteArrayList<>();
        final List<String> previousIds = new CopyOnWriteArrayList<>();
        /** The profile of every call, as the service handed it over. */
        final List<Profile> profiles = new CopyOnWriteArrayList<>();
        /** Opens when the blocked call ({@link #blockedCall}) starts. */
        final CountDownLatch firstCallEntered = new CountDownLatch(1);
        /** Holds the blocked call until it opens; null for none. */
        volatile CountDownLatch blockFirstCall;
        /** Which call (1-based) {@link #blockFirstCall} holds: the first unless a test picks another. */
        volatile int blockedCall = 1;
        volatile RuntimeException failWith;
        /** 顺便 keys put on every evening step ("act:study_room"), or null for none. */
        volatile List<String> eveningSide;
        /** Shapes the plan of call n (1-based) before it is returned, e.g. budgetExhausted; null for none. */
        volatile java.util.function.ObjIntConsumer<Plan> shape;

        @Override
        public Plan plan(Rules rules, Profile profile, SimState observed, Plan previous, Engine.Options opts) {
            int n = calls.incrementAndGet();
            options.add(opts);
            previousIds.add(previous == null ? "" : previous.planId);
            profiles.add(profile);
            if (n == blockedCall && blockFirstCall != null) {
                firstCallEntered.countDown();
                awaitQuietly(blockFirstCall);
            }
            if (failWith != null) {
                throw failWith;
            }
            Plan plan = new Plan();
            plan.fromSlot = observed.slot;
            plan.toSlot = Math.min(rules.lastSlot(), observed.slot + 5);
            for (int s = plan.fromSlot; s <= plan.toSlot; s++) {
                Plan.Step step = new Plan.Step();
                step.slot = s;
                step.action = Rules.isEvening(s) ? "act:study_room" : "meet:emperor";
                step.kind = Rules.isEvening(s) ? "stat" : "social";
                List<String> side = eveningSide;
                if (side != null && Rules.isEvening(s)) {
                    step.side = new ArrayList<>(side);
                }
                plan.steps.add(step);
            }
            plan.planId = "fake-" + n;
            java.util.function.ObjIntConsumer<Plan> hook = shape;
            if (hook != null) {
                hook.accept(plan, n);
            }
            return plan;
        }
    }

    /** Returns the configured problems (none by default). */
    static final class Validator implements PlanCheck {
        final AtomicInteger calls = new AtomicInteger();
        final List<Long> validatedRevisions = new CopyOnWriteArrayList<>();
        volatile List<String> problems = Collections.emptyList();

        @Override
        public List<String> validate(Rules rules, Profile profile, SimState observed, Plan plan) {
            calls.incrementAndGet();
            validatedRevisions.add(plan.stateRevision);
            return problems;
        }
    }

    /** Daytime: meet the Emperor; evening: study, café job, Tartarus. Counts plain advances. */
    static final class Forecaster implements app.p3r.dayplanner.v3.service.Forecaster {
        final AtomicInteger advances = new AtomicInteger();

        @Override
        public SimState advanceTo(Rules rules, Profile profile, SimState state, int slot) {
            advances.incrementAndGet();
            SimState out = state.copy();
            out.slot = Math.max(state.slot, slot);
            return out;
        }

        @Override
        public List<Action> legalActions(Rules rules, Profile profile, SimState state) {
            List<Action> out = new ArrayList<>();
            if (!Rules.isEvening(state.slot)) {
                out.add(Action.meet(TestRules.EMPEROR));
                return out;
            }
            out.add(Action.activity(TestRules.STUDY));
            out.add(Action.activity(TestRules.CAFE));
            Rules.Block block = rules.blockOn(Rules.dayOf(state.slot));
            if (block != null) {
                out.add(Action.tower(block.idx));
            }
            return out;
        }

        @Override
        public Engine.Applied apply(Rules rules, Profile profile, SimState state, Action action) {
            Engine.Applied applied = new Engine.Applied();
            applied.state = state.copy();
            applied.state.slot = state.slot + 1;
            applied.predicted = new Plan.Predicted();
            applied.assumptions = new ArrayList<>();
            if (action.type == Action.Type.MEET) {
                applied.predicted.rankFrom = state.rank[action.target];
                applied.predicted.rankTo = state.rank[action.target] + 1;
                applied.predicted.rankUp = true;
                applied.estimate = true;
                applied.assumptions.add("ordinary_gain");
                applied.state.rank[action.target]++;
            } else if (action.type == Action.Type.ACTIVITY) {
                Rules.Activity a = rules.activities[action.target];
                applied.predicted.statDelta = a.gain.clone();
                if (a.kind == Rules.ActivityKind.JOB) {
                    applied.predicted.cashDelta = a.payFor(state.shifts[action.target] + 1);
                }
            }
            return applied;
        }
    }

    static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
