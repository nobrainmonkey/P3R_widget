package app.p3r.dayplanner.v3.widget;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * The provider's day/night crossfade around user actions: a resize cancels a running fade (its
 * settle update then draws nothing), and a burst of taps never queues several fades. Ported from
 * the 3.0.x guide widget's fade test; the canned plan's first item is 11/1 白天 and the next one
 * 11/1 晚上.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, shadows = {RealViewAnimator.class})
public class PlanWidgetFadeTest {
    private Application app;
    private FakePlannerApi fake;
    private ShadowAppWidgetManager host;
    private int id;
    private final List<Runnable> scheduled = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        fake = new FakePlannerApi();
        WidgetApi.setOverrideForTest(fake);
        WidgetWorker.setForTest(Runnable::run, (task, delay) -> scheduled.add(task));
        host = shadowOf(AppWidgetManager.getInstance(app));
        id = host.createWidget(PlanWidget.class, R.layout.widget_loading);
        idle();
        PlanWidget.FADES.forget(id); // widget ids restart per test; the fade throttle is process-wide
    }

    @After
    public void tearDown() {
        WidgetApi.setOverrideForTest(null);
        WidgetWorker.setForTest(null, null);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private View root() {
        return host.getViewFor(id);
    }

    private boolean fading() {
        View root = root();
        boolean flipper = root.findViewById(R.id.widget_palette_flipper).getVisibility() == View.VISIBLE;
        boolean settled = root.findViewById(R.id.widget_palette_static).getVisibility() == View.VISIBLE;
        assertTrue("exactly one of the fade host and the static card shows", flipper != settled);
        return flipper;
    }

    /** The title on the card a reader sees (static slot when settled, flipper child while fading). */
    private String title() {
        View root = root();
        View card = fading()
                ? ((android.widget.ViewFlipper) root.findViewById(R.id.widget_palette_flipper)).getCurrentView()
                : root.findViewById(R.id.widget_palette_static);
        return CardText.plain(((TextView) card.findViewById(R.id.widget_task)).getText().toString());
    }

    private void tap(int viewId) {
        View card = fading()
                ? ((android.widget.ViewFlipper) root().findViewById(R.id.widget_palette_flipper)).getCurrentView()
                : root().findViewById(R.id.widget_palette_static);
        assertTrue(card.findViewById(viewId).performClick());
        idle();
    }

    @Test
    public void aResizeCancelsTheRunningFade() {
        assertEquals("倒悬者 · 舞子", title());
        tap(R.id.widget_next); // 11/1 白天 → 11/1 晚上
        assertTrue("the palette change fades", fading());
        assertEquals(1, scheduled.size());

        new PlanWidget().onAppWidgetOptionsChanged(app, AppWidgetManager.getInstance(app), id, new Bundle());
        idle();
        assertTrue("the resize draws the settled card at once", !fading());
        assertEquals("在宿舍房间学习", title());

        // Had the cancelled settle still run, it would draw the new plan (whose cursor starts over).
        fake.installPlan("plan-B");
        scheduled.remove(0).run();
        idle();
        assertTrue(!fading());
        assertEquals("the cancelled settle draws nothing", "在宿舍房间学习", title());
    }

    @Test
    public void aBurstOfTapsFadesOnceAndAPauseFadesAgain() {
        tap(R.id.widget_next); // to the evening: fades
        assertTrue(fading());
        tap(R.id.widget_prev); // straight back to the daytime: within the throttle, no second fade
        assertTrue("no fade queued behind the first", !fading());
        assertEquals("倒悬者 · 舞子", title());
        assertEquals("only the first tap scheduled a settle", 1, scheduled.size());
        scheduled.remove(0).run();
        idle();
        assertEquals("the first fade's settle is stale", "倒悬者 · 舞子", title());

        ShadowSystemClock.advanceBy(Duration.ofMillis(FadeGuard.THROTTLE_MS));
        tap(R.id.widget_next);
        assertTrue("after a pause the palette change fades again", fading());
        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();
        idle();
        assertTrue(!fading());
        assertEquals("在宿舍房间学习", title());
    }
}
