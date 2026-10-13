package app.p3r.dayplanner.v3.widget;

import android.content.Context;

import app.p3r.dayplanner.v3.service.PlannerApi;
import app.p3r.dayplanner.v3.service.PlannerService;

/**
 * Where the widget gets its {@link PlannerApi}: the process-wide {@link PlannerService}, unless a test
 * installed an override. Callers must be off the main thread, since the service reads SQLite.
 */
public final class WidgetApi {
    private static volatile PlannerApi override;

    private WidgetApi() {}

    /** The planner facade; throws whatever the service throws when it cannot be opened. */
    public static PlannerApi get(Context context) {
        PlannerApi test = override;
        if (test != null) return test;
        return PlannerService.get(context.getApplicationContext());
    }

    /** Test hook: route every widget call to {@code api} (null restores the real service). */
    public static void setOverrideForTest(PlannerApi api) {
        override = api;
    }
}
