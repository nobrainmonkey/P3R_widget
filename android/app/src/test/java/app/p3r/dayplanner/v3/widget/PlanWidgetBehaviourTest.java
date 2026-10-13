package app.p3r.dayplanner.v3.widget;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
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
import org.robolectric.shadows.ShadowPendingIntent;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.WidgetPaletteFadeTest;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Outcome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * End-to-end widget behaviour through real RemoteViews clicks: Robolectric sends each click's
 * PendingIntent, delivers broadcasts to the manifest receiver and records started activities.
 *
 * <p>Uses the legacy fade test's real-ViewAnimator shadow: Robolectric sandboxes are shared per SDK,
 * so every test that inflates the palette host must agree on the ViewAnimator shadow.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, shadows = {WidgetPaletteFadeTest.RealViewAnimator.class})
public class PlanWidgetBehaviourTest {
    private static final int S = FakePlannerApi.FIRST_SLOT;
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

    // ------------------------------------------------------------------ helpers

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void refresh() {
        PlanWidget.refreshAll(app);
        idle();
    }

    /** The card the user currently sees (static slot when settled, flipper child while fading). */
    private View card() {
        View root = host.getViewFor(id);
        View settled = root.findViewById(R.id.widget_palette_static);
        if (settled.getVisibility() == View.VISIBLE) return settled;
        android.widget.ViewFlipper flipper = root.findViewById(R.id.widget_palette_flipper);
        return flipper.getCurrentView();
    }

    private String text(int viewId) {
        return ((TextView) card().findViewById(viewId)).getText().toString();
    }

    private void tap(int viewId) {
        View target = card().findViewById(viewId);
        assertTrue(target.performClick());
        idle();
    }

    private Intent lastBroadcast() {
        List<Intent> sent = shadowOf(app).getBroadcastIntents();
        assertFalse("no broadcast sent", sent.isEmpty());
        return sent.get(sent.size() - 1);
    }

    private void redeliver(Intent intent) {
        app.sendBroadcast(new Intent(intent));
        idle();
    }

    // ------------------------------------------------------------------ Done: direct records

    @Test
    public void doneOnTrainingRecordsExactlyOnceEvenWhenTheTapArrivesTwice() {
        fake.observeAt("act:study_room");
        refresh();
        assertEquals("在宿舍房间学习", text(R.id.widget_task));
        assertEquals("完成", text(R.id.button_spec_done_text));

        tap(R.id.button_spec_done);
        Intent done = lastBroadcast();
        assertEquals(1, fake.recordCalls.size());
        FakePlannerApi.Call call = fake.recordCalls.get(0);
        assertEquals(WidgetIntents.commitCommandId(FakePlannerApi.PLAN_ID, S + 1, "act:study_room"), call.commandId);
        assertEquals(7, call.expectedRevision);
        assertEquals(S + 1, call.slot);
        assertEquals("act:study_room", call.action);
        assertEquals(Outcome.Code.AS_PREDICTED, call.outcome.code);
        assertEquals("✓ 已记录 · 再点撤销", text(R.id.widget_detail_cue));
        assertEquals("撤销", text(R.id.button_spec_done_text));

        // The same PendingIntent delivered again (double tap before the re-render) is stale now.
        redeliver(done);
        assertEquals(1, fake.recordCalls.size());
        assertEquals(S + 2, fake.observed.slot);
        assertEquals("撤销", text(R.id.button_spec_done_text));
    }

    @Test
    public void commandIdIsStablePerPlanSlotAndAction() {
        String a = WidgetIntents.commitCommandId("plan-A", 10, "act:study_room");
        assertEquals(a, WidgetIntents.commitCommandId("plan-A", 10, "act:study_room"));
        assertNotEquals(a, WidgetIntents.commitCommandId("plan-A", 11, "act:study_room"));
        assertNotEquals(a, WidgetIntents.commitCommandId("plan-B", 10, "act:study_room"));
        assertNotEquals(a, WidgetIntents.commitCommandId("plan-A", 10, "act:job_chagall"));
    }

    @Test
    public void secondTapUndoesTheDirectRecord() {
        fake.observeAt("act:job_chagall");
        refresh();
        tap(R.id.button_spec_done);
        assertEquals(1, fake.recordCalls.size());
        assertEquals("撤销", text(R.id.button_spec_done_text));
        assertNotNull(shadowOf(app.getSystemService(AlarmManager.class)).getNextScheduledAlarm());

        tap(R.id.button_spec_done);
        Intent undo = lastBroadcast();
        assertEquals(1, fake.voidCalls.size());
        assertTrue(fake.voidCalls.get(0).endsWith("#1"));
        assertEquals(S + 3, fake.observed.slot);
        assertEquals("咖啡店打工", text(R.id.widget_task));
        assertEquals("完成", text(R.id.button_spec_done_text));
        assertNull(WidgetPrefs.receipt(app, id));

        redeliver(undo);
        assertEquals("a stale undo never voids again", 1, fake.voidCalls.size());
    }

    @Test
    public void currentLeavesTheReceiptForTheNextItem() {
        fake.observeAt("act:study_room");
        refresh();
        tap(R.id.button_spec_done);
        tap(R.id.button_spec_current);
        assertEquals("皇帝 · 小田桐秀利", text(R.id.widget_task));
        assertEquals("完成", text(R.id.button_spec_done_text));
        assertNull(WidgetPrefs.receipt(app, id));
    }

    // ------------------------------------------------------------------ Done: sheets

    @Test
    public void socialDoneOpensTheResultSheetWithItsExtras() {
        assertEquals("倒悬者 · 舞子", text(R.id.widget_task));
        tap(R.id.button_spec_done);
        Intent sheet = shadowOf(app).getNextStartedActivity();
        assertNotNull(sheet);
        assertEquals(WidgetIntents.RESULT_ACTIVITY, sheet.getComponent().getClassName());
        assertEquals(app.getPackageName(), sheet.getComponent().getPackageName());
        assertEquals(Intents.MODE_RESULT, sheet.getStringExtra(Intents.EXTRA_MODE));
        assertEquals(S, sheet.getIntExtra(Intents.EXTRA_SLOT, -1));
        assertEquals("meet:hanged", sheet.getStringExtra(Intents.EXTRA_ACTION));
        assertEquals(FakePlannerApi.PLAN_ID, sheet.getStringExtra(Intents.EXTRA_PLAN_ID));
        assertEquals(7L, sheet.getLongExtra(Intents.EXTRA_STATE_REV, -1));
        assertTrue((sheet.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("social outcomes are never recorded from the widget", fake.recordCalls.isEmpty());
    }

    @Test
    public void unlockAndTartarusAlsoOpenTheSheet() {
        for (String action : new String[]{"unlock:temperance:0", "tower:4"}) {
            fake.observeAt(action);
            refresh();
            tap(R.id.button_spec_done);
            Intent sheet = shadowOf(app).getNextStartedActivity();
            assertEquals(action, sheet.getStringExtra(Intents.EXTRA_ACTION));
            assertEquals(Intents.MODE_RESULT, sheet.getStringExtra(Intents.EXTRA_MODE));
        }
        assertTrue(fake.recordCalls.isEmpty());
    }

    @Test
    public void stalePlanSendsTrainingToTheSheetInsteadOfRecording() {
        fake.observeAt("act:study_room");
        fake.planCurrent = false;
        refresh();
        tap(R.id.button_spec_done);
        assertEquals(Intents.MODE_RESULT, shadowOf(app).getNextStartedActivity().getStringExtra(Intents.EXTRA_MODE));
        assertTrue(fake.recordCalls.isEmpty());
    }

    @Test
    public void doneOnAFutureItemOpensCatchUp() {
        tap(R.id.widget_next);
        assertEquals("在宿舍房间学习", text(R.id.widget_task));
        assertEquals("做到这里", text(R.id.button_spec_done_text));
        tap(R.id.button_spec_done);
        Intent sheet = shadowOf(app).getNextStartedActivity();
        assertEquals(Intents.MODE_CATCHUP, sheet.getStringExtra(Intents.EXTRA_MODE));
        assertEquals(S + 1, sheet.getIntExtra(Intents.EXTRA_SLOT, -1));
        assertTrue(fake.recordCalls.isEmpty());
    }

    // ------------------------------------------------------------------ stale taps

    @Test
    public void staleDoneTapsNeverRecord() {
        fake.observeAt("act:study_room");
        refresh();

        fake.stateRevision++; // something else was recorded since the card was drawn
        tap(R.id.button_spec_done);
        assertTrue(fake.recordCalls.isEmpty());
        assertEquals("完成", text(R.id.button_spec_done_text)); // refreshed with the new revision

        fake.installPlan("plan-B"); // a replan replaced the plan
        tap(R.id.button_spec_done);
        assertTrue(fake.recordCalls.isEmpty());

        tap(R.id.button_spec_done); // fresh card: records normally
        assertEquals(1, fake.recordCalls.size());
        assertEquals(WidgetIntents.commitCommandId("plan-B", S + 1, "act:study_room"), fake.recordCalls.get(0).commandId);
    }

    @Test
    public void tamperedDoneIntentForAnotherSlotIsIgnored() {
        fake.observeAt("act:study_room");
        refresh();
        Intent forged = new Intent(app, PlanWidget.class).setAction(WidgetIntents.ACTION_DONE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(Intents.EXTRA_PLAN_ID, FakePlannerApi.PLAN_ID)
                .putExtra(Intents.EXTRA_STATE_REV, fake.stateRevision)
                .putExtra(Intents.EXTRA_SLOT, S + 2)
                .putExtra(Intents.EXTRA_ACTION, "meet:emperor");
        redeliver(forged);
        assertTrue(fake.recordCalls.isEmpty());
    }

    @Test
    public void lockedItemsAreInformationOnly() {
        fake.observeAt("locked:full_moon");
        refresh();
        assertEquals("满月作战", text(R.id.widget_task));
        View done = card().findViewById(R.id.button_spec_done);
        assertFalse(done.isEnabled());
        done.performClick();
        idle();
        assertNull(shadowOf(app).getNextStartedActivity());
        assertTrue(fake.recordCalls.isEmpty());
    }

    // ------------------------------------------------------------------ browsing

    @Test
    public void arrowsBrowseItemsAndCurrentReturnsToThePendingOne() {
        assertFalse("nothing before the pending item", card().findViewById(R.id.widget_prev).isEnabled());
        String[] titles = {"在宿舍房间学习", "皇帝 · 小田桐秀利", "咖啡店打工", "节制 · 贝培", "满月作战", "剧情事件", "探索塔尔塔罗斯"};
        for (String expected : titles) {
            tap(R.id.widget_next);
            assertEquals(expected, text(R.id.widget_task));
        }
        assertFalse("nothing after the last item", card().findViewById(R.id.widget_next).isEnabled());
        tap(R.id.widget_prev);
        assertEquals("剧情事件", text(R.id.widget_task));
        tap(R.id.button_spec_current);
        assertEquals("倒悬者 · 舞子", text(R.id.widget_task));
        assertTrue("browsing never records", fake.recordCalls.isEmpty());
    }

    @Test
    public void cursorIsKeyedByPlanIdAndResetsOnANewPlan() {
        tap(R.id.widget_next);
        tap(R.id.widget_next);
        assertEquals("皇帝 · 小田桐秀利", text(R.id.widget_task));
        refresh();
        assertEquals("same plan keeps the cursor", "皇帝 · 小田桐秀利", text(R.id.widget_task));
        fake.installPlan("plan-B");
        refresh();
        assertEquals("倒悬者 · 舞子", text(R.id.widget_task));
    }

    @Test
    public void paletteChangeFadesThenSettles() {
        View root = host.getViewFor(id);
        tap(R.id.widget_next); // 11/1 白天 → 11/1 晚上
        assertEquals(View.VISIBLE, root.findViewById(R.id.widget_palette_flipper).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.widget_palette_static).getVisibility());
        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();
        idle();
        assertEquals(View.VISIBLE, root.findViewById(R.id.widget_palette_static).getVisibility());
        assertEquals("在宿舍房间学习", text(R.id.widget_task));
    }

    // ------------------------------------------------------------------ other targets

    @Test
    public void taskTextOpensTheAppOnThatSlot() {
        tap(R.id.widget_next);
        tap(R.id.widget_task_area);
        Intent home = shadowOf(app).getNextStartedActivity();
        assertEquals(WidgetIntents.HOME_ACTIVITY, home.getComponent().getClassName());
        assertEquals(S + 1, home.getIntExtra(Intents.EXTRA_OPEN_SLOT, -1));
    }

    @Test
    public void emptyStateInvitesOnboarding() {
        fake.observed = null;
        refresh();
        assertEquals("设置目标，从当前存档开始", text(R.id.widget_task));
        assertEquals("开始设置", text(R.id.button_spec_done_text));
        assertEquals(View.GONE, card().findViewById(R.id.button_spec_current).getVisibility());
        assertEquals(View.INVISIBLE, card().findViewById(R.id.widget_prev).getVisibility());
        tap(R.id.button_spec_done);
        Intent setup = shadowOf(app).getNextStartedActivity();
        assertEquals(WidgetIntents.STATE_ACTIVITY, setup.getComponent().getClassName());
        assertEquals("onboarding", setup.getStringExtra(Intents.EXTRA_STATE_MODE));
        tap(R.id.widget_task_area);
        assertEquals(WidgetIntents.STATE_ACTIVITY, shadowOf(app).getNextStartedActivity().getComponent().getClassName());
    }

    @Test
    public void unreachablePlannerShowsAFriendlyMessage() {
        fake.snapshotFailure = new IllegalStateException("db locked");
        refresh();
        assertEquals("暂时读不到安排", text(R.id.widget_task));
        assertFalse(card().findViewById(R.id.button_spec_done).isEnabled());
    }

    @Test
    public void pendingIntentsAreImmutableAndUniquePerTarget() {
        PendingIntent a = WidgetIntents.commit(app, id, "plan-A", 7, S + 1, "act:study_room");
        PendingIntent b = WidgetIntents.commit(app, id, "plan-A", 8, S + 1, "act:study_room");
        PendingIntent c = WidgetIntents.commit(app, id + 1, "plan-A", 7, S + 1, "act:study_room");
        PendingIntent d = WidgetIntents.result(app, id, Intents.MODE_RESULT, "plan-A", 7, S, "meet:hanged");
        PendingIntent e = WidgetIntents.result(app, id, Intents.MODE_CATCHUP, "plan-A", 7, S, "meet:hanged");
        List<String> identities = new ArrayList<>();
        for (PendingIntent pi : new PendingIntent[]{a, b, c, d, e,
                WidgetIntents.navigate(app, id, WidgetIntents.ACTION_NEXT),
                WidgetIntents.navigate(app, id, WidgetIntents.ACTION_PREV),
                WidgetIntents.undo(app, id, 3, 8),
                WidgetIntents.home(app, id, WidgetIntents.TAB_TODAY, S),
                WidgetIntents.onboarding(app, id)}) {
            ShadowPendingIntent shadow = shadowOf(pi);
            assertTrue(shadow.isImmutable());
            String identity = shadow.getSavedIntent().getData().toString();
            assertFalse("duplicate identity " + identity, identities.contains(identity));
            identities.add(identity);
        }
        assertNotEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    public void refreshAllOnlyQueuesWorkOnTheCallingThread() throws Exception {
        List<Runnable> queue = new ArrayList<>();
        WidgetWorker.setForTest(queue::add, (task, delay) -> { });
        fake.observeAt("act:study_room");
        Thread caller = new Thread(() -> PlanWidget.refreshAll(app));
        caller.start();
        caller.join();
        assertEquals(1, queue.size());
        assertEquals("倒悬者 · 舞子", text(R.id.widget_task));
        queue.remove(0).run();
        idle();
        assertEquals("在宿舍房间学习", text(R.id.widget_task));
    }

    @Test
    public void deletingAWidgetForgetsItsState() {
        tap(R.id.widget_next);
        assertTrue(WidgetPrefs.cursorSlot(app, id, FakePlannerApi.PLAN_ID) >= 0);
        new PlanWidget().onDeleted(app, new int[]{id});
        assertEquals(-1, WidgetPrefs.cursorSlot(app, id, FakePlannerApi.PLAN_ID));
    }
}
