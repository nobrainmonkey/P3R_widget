package app.p3r.dayplanner.v3.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Redraws every placed plan widget after an in-place APK update, so it never keeps showing the old
 * version's views. Records, settings and widget prefs are left untouched.
 */
public class WidgetUpgradeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            PlanWidget.refreshAll(context);
        }
    }
}
