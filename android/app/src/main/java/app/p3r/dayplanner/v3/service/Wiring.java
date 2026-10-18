package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.store.OutcomeApplier;
import app.p3r.dayplanner.v3.store.PlanDb;

import java.util.function.LongSupplier;

/**
 * Dependencies of {@link PlannerService}. Production values come from {@link DefaultWiring};
 * tests replace any of them with fakes. Read once, at construction.
 */
public final class Wiring {
    /** Rule pack source (required). */
    public RulesSource rules;
    /** Applies recorded outcomes during replay. */
    public OutcomeApplier applier = OutcomeApplier.ENGINE;
    /** Computes plans. */
    public PlanFunction planner = PlanFunction.ENGINE;
    /** Checks plans before installation. */
    public PlanCheck validator = PlanCheck.ENGINE;
    /** Legal actions and forecasts for alternatives. */
    public Forecaster forecaster = Forecaster.ENGINE;
    /** Re-renders the home-screen widgets; called from background threads. */
    public Runnable widgetRefresh = () -> { };
    /** Wall clock (ms) for the {@code created} columns. */
    public LongSupplier clock = System::currentTimeMillis;
    /** Soft time budget per plan (DESIGN §10.6: 4 s on device). */
    public long planBudgetMillis = Engine.DEFAULT_PLAN_BUDGET_MILLIS;
    /**
     * Stop a computation early when a write makes it outdated. Its result would be discarded by
     * the revision check on install anyway; turning this off only costs time (tests use it to
     * exercise that check on its own).
     */
    public boolean cancelSupersededPlans = true;
    /** Database file name. */
    public String databaseName = PlanDb.FILE_NAME;
}
