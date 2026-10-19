package app.p3r.dayplanner.v3.ui;

import android.content.Context;

import app.p3r.dayplanner.v3.service.PlannerApi;
import app.p3r.dayplanner.v3.service.PlannerService;

/**
 * Single access point from the UI to the planner facade. Tests install a fake with
 * {@link #setForTest}; production code always goes through {@link PlannerService#get}.
 *
 * <p>{@link #get} may open the database and load the rule pack on first use, so it is only ever
 * called from {@link Async} background work, never on the main thread.
 */
public final class Api {
    private static volatile PlannerApi override;

    private Api() {}

    /** The planner facade for this process (the test override when one is installed). */
    public static PlannerApi get(Context context) {
        PlannerApi test = override;
        if (test != null) {
            return test;
        }
        return PlannerService.get(context.getApplicationContext());
    }

    /** Installs (or, with {@code null}, removes) a fake implementation for tests. */
    public static void setForTest(PlannerApi api) {
        override = api;
    }
}
