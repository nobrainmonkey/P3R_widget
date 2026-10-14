package app.p3r.dayplanner.v3.widget;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.ui.HomeActivity;
import app.p3r.dayplanner.v3.ui.ResultActivity;
import app.p3r.dayplanner.v3.ui.StateActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * After an in-place update the system sends MY_PACKAGE_REPLACED to the manifest receiver, which
 * redraws every placed plan widget on the widget thread. The manifest declares only the planner's
 * own components (3.1.0 removed the 2.x guide's activity, widget and receiver).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, shadows = {RealViewAnimator.class})
public class WidgetUpgradeReceiverTest {
    private Application app;
    private FakePlannerApi fake;
    private ShadowAppWidgetManager host;
    private final List<Runnable> queued = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        fake = new FakePlannerApi();
        WidgetApi.setOverrideForTest(fake);
        WidgetWorker.setForTest(queued::add, (task, delay) -> { });
        host = shadowOf(AppWidgetManager.getInstance(app));
    }

    @After
    public void tearDown() {
        WidgetApi.setOverrideForTest(null);
        WidgetWorker.setForTest(null, null);
    }

    private void drain() {
        shadowOf(Looper.getMainLooper()).idle();
        while (!queued.isEmpty()) queued.remove(0).run();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private String title(int id) {
        TextView title = host.getViewFor(id).findViewById(R.id.widget_palette_static).findViewById(R.id.widget_task);
        return CardText.plain(title.getText().toString());
    }

    @Test
    public void anUpdateRedrawsEveryPlacedWidget() {
        int first = host.createWidget(PlanWidget.class, R.layout.widget_loading);
        int second = host.createWidget(PlanWidget.class, R.layout.widget_loading);
        drain();
        assertEquals("倒悬者 · 舞子", title(first));
        assertEquals("倒悬者 · 舞子", title(second));

        fake.observeAt("act:study_room");
        app.sendBroadcast(new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the receiver hands the redraw to the widget thread", 1, queued.size());
        assertEquals("nothing is drawn on the main thread", "倒悬者 · 舞子", title(first));
        drain();
        assertEquals("在宿舍房间学习", title(first));
        assertEquals("在宿舍房间学习", title(second));
        assertTrue("an update never records anything", fake.recordCalls.isEmpty());
    }

    @Test
    public void otherBroadcastsAreIgnored() {
        WidgetUpgradeReceiver receiver = new WidgetUpgradeReceiver();
        receiver.onReceive(app, null);
        receiver.onReceive(app, new Intent(Intent.ACTION_PACKAGE_REPLACED));
        receiver.onReceive(app, new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE));
        assertTrue(queued.isEmpty());
        receiver.onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertEquals(1, queued.size());
    }

    @Test
    public void theManifestDeclaresOnlyThePlannersComponents() throws Exception {
        PackageManager pm = app.getPackageManager();
        String pkg = app.getPackageName();

        List<ResolveInfo> upgrade = pm.queryBroadcastReceivers(new Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(pkg), 0);
        assertEquals(1, upgrade.size());
        assertEquals(WidgetUpgradeReceiver.class.getName(), upgrade.get(0).activityInfo.name);
        assertFalse(upgrade.get(0).activityInfo.exported);

        List<ResolveInfo> providers = pm.queryBroadcastReceivers(
                new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(pkg), PackageManager.GET_META_DATA);
        assertEquals("one widget in the picker", 1, providers.size());
        assertEquals(PlanWidget.class.getName(), providers.get(0).activityInfo.name);

        PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES | PackageManager.GET_RECEIVERS);
        assertEquals(new TreeSet<>(Arrays.asList(HomeActivity.class.getName(), StateActivity.class.getName(),
                ResultActivity.class.getName())), names(info.activities));
        assertEquals(new TreeSet<>(Arrays.asList(PlanWidget.class.getName(), WidgetUpgradeReceiver.class.getName())),
                names(info.receivers));
        assertEquals("the application theme is the planner's", R.style.P3R_App, info.applicationInfo.theme);
    }

    private static Set<String> names(ActivityInfo[] components) {
        Set<String> out = new TreeSet<>();
        if (components != null) {
            for (ActivityInfo component : components) out.add(component.name);
        }
        return out;
    }
}
