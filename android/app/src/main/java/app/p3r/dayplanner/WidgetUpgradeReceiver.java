package app.p3r.dayplanner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Rebuild installed widget views after an in-place APK update, without touching progress. */
public class WidgetUpgradeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            PlannerWidget.refreshAll(context);
            app.p3r.dayplanner.v2.GoalPlannerWidget.refreshAll(context);
        }
    }
}
