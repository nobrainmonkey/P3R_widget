package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.FakeApplier;
import app.p3r.dayplanner.v3.store.PlanDb;
import app.p3r.dayplanner.v3.store.TestRules;

import org.junit.After;
import org.junit.Before;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared setup: a service on the synthetic rules with fake engine parts and a fixed clock. */
abstract class ServiceTestBase {
    static final long START_MILLIS = 1_760_000_000_000L;

    Context context;
    Rules rules;
    FakeApplier applier;
    Fakes.Planner planner;
    Fakes.Validator validator;
    Fakes.Forecaster forecaster;
    final AtomicInteger widgetRefreshes = new AtomicInteger();
    volatile long now = START_MILLIS;
    PlannerService service;
    private final List<PlannerService> opened = new ArrayList<>();

    @Before
    public void setUpService() {
        context = RuntimeEnvironment.getApplication();
        rules = TestRules.create();
        applier = new FakeApplier();
        planner = new Fakes.Planner();
        validator = new Fakes.Validator();
        forecaster = new Fakes.Forecaster();
        service = open(PlanDb.FILE_NAME);
    }

    @After
    public void closeServices() {
        for (PlannerService s : opened) {
            s.close();
        }
    }

    /** A new service instance on {@code databaseName} (same file = simulated process restart). */
    PlannerService open(String databaseName) {
        Wiring wiring = new Wiring();
        wiring.rules = () -> rules;
        wiring.applier = applier;
        wiring.planner = planner;
        wiring.validator = validator;
        wiring.forecaster = forecaster;
        wiring.widgetRefresh = widgetRefreshes::incrementAndGet;
        wiring.clock = () -> now;
        wiring.planBudgetMillis = 50;
        wiring.databaseName = databaseName;
        PlannerService s = PlannerService.create(context, wiring);
        opened.add(s);
        return s;
    }

    /** Closes the current instance and opens a new one on the same file. */
    void restart() {
        settle();
        service.close();
        opened.remove(service);
        service = open(PlanDb.FILE_NAME);
    }

    /** Waits for background planning and delivers pending main-thread callbacks. */
    void settle() {
        assertTrue("planner did not go idle", service.awaitIdle(20_000));
        shadowOf(Looper.getMainLooper()).idle();
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    /** Onboards at {@code slot} with {@link TestRules#state}; returns the new state revision. */
    long onboard(int slot) {
        return onboard(TestRules.state(rules, slot));
    }

    long onboard(SimState state) {
        PlannerApi.Receipt r = service.saveCheckpoint(id(), service.snapshot().stateRevision, state, null);
        assertTrue(r.errorZh, r.ok);
        return r.stateRevision;
    }

    PlannerApi.Receipt record(int slot, String action, Outcome.Code code) {
        return service.recordOutcome(id(), rev(), slot, action, Outcome.of(code));
    }

    PlannerApi.Receipt tower(int slot, int floor, boolean balance, long amount) {
        return service.recordTower(id(), rev(), towerReturn(slot, floor, balance, amount));
    }

    static PlannerApi.TowerReturn towerReturn(int slot, int floor, boolean balance, long amount) {
        PlannerApi.TowerReturn t = new PlannerApi.TowerReturn();
        t.slot = slot;
        t.block = -1;
        t.floor = floor;
        t.balanceMode = balance;
        t.amount = amount;
        return t;
    }

    long rev() {
        return service.snapshot().stateRevision;
    }

    SimState observed() {
        return service.snapshot().observed;
    }
}
