package app.p3r.dayplanner.v3.widget;

import android.app.Application;
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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * The widget on the production planner: the shipped rule pack, the real engine and the real SQLite
 * store. A real plan puts a payment done 顺便 on a job (a new game's first one pays 田中社长 on a
 * summer evening); the widget sends that job to the result sheet, and its direct record of a later
 * plain training or job is stored, applies no side step and can be undone. The store keeps the
 * side-step keys of a record from the installed plan's step, so a direct record must only ever
 * happen on a step without them.
 *
 * <p>Uses the legacy fade test's real-ViewAnimator shadow, like every test that inflates the
 * palette host on this SDK.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, shadows = {RealViewAnimator.class})
public class PlanWidgetRealServiceTest {
    private static final long SETTLE_MS = 60_000L;
    private Application app;
    private PlannerApi api;
    private ShadowAppWidgetManager host;
    private int id;
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        WidgetApi.setOverrideForTest(null);
        Looper main = Looper.getMainLooper();
        // Taps run in place on the main thread; the planner thread's own refreshes are dropped (the
        // test re-renders after each plan settles), so no RemoteViews are built off the main thread.
        WidgetWorker.setForTest(task -> {
            if (Looper.myLooper() == main) task.run();
        }, (task, delay) -> { });
        PlanWidget.setClockForTest(clock::get);
        api = WidgetApi.get(app);
        host = shadowOf(AppWidgetManager.getInstance(app));
    }

    @After
    public void tearDown() {
        WidgetWorker.setForTest(null, null);
        PlanWidget.setClockForTest(null);
    }

    @Test
    public void sideStepsGoToTheSheetAndDirectRecordsStillWorkOnTheRealStore() throws Exception {
        Rules rules = api.rules();
        assertNotNull("the shipped rule pack did not load", rules);
        Profile profile = new Profile();
        SimState start = sideStepJobState(rules, profile);
        PlannerApi.Receipt onboarded = api.saveCheckpoint(command(), api.snapshot().stateRevision, start, profile);
        assertTrue(onboarded.errorZh, onboarded.ok);
        PlannerApi.Snapshot s = settled();
        id = host.createWidget(PlanWidget.class, R.layout.widget_loading);
        idle();
        PlanWidget.FADES.forget(id);

        // 1. A job with a payment done 顺便: 完成 opens the sheet and records nothing.
        Plan.Step job = s.plan.at(s.observed.slot);
        assertTrue("the planner put the side step on another step: " + job.action + " " + job.side, directKind(job) && !job.side.isEmpty());
        refresh();
        assertEquals(View.VISIBLE, card().findViewById(R.id.widget_side).getVisibility());
        assertTrue(text(R.id.widget_side), text(R.id.widget_side).startsWith("顺便"));
        assertEquals("完成", text(R.id.button_spec_done_text));
        tap(R.id.button_spec_done);
        Intent sheet = shadowOf(app).getNextStartedActivity();
        assertNotNull(sheet);
        assertEquals(Intents.MODE_RESULT, sheet.getStringExtra(Intents.EXTRA_MODE));
        assertEquals(job.action, sheet.getStringExtra(Intents.EXTRA_ACTION));
        assertEquals("the widget recorded nothing", s.stateRevision, api.snapshot().stateRevision);

        // The sheet's answer (job and payment done) applies the side steps.
        int[] unlockBefore = s.observed.unlockStep.clone();
        PlannerApi.Receipt answered = api.recordOutcome(command(), s.stateRevision, job.slot, job.action, Outcome.of(Outcome.Code.AS_PREDICTED));
        assertTrue(answered.errorZh, answered.ok);
        SimState afterSheet = api.snapshot().observed;
        for (String key : job.side) {
            int link = Action.parse(rules, key).target;
            assertTrue("side step " + key + " applied", afterSheet.unlockStep[link] > unlockBefore[link]);
        }

        // 2. On to the next plain training or job (other steps are answered as not done).
        Plan.Step direct = null;
        for (int i = 0; i < 40 && direct == null; i++) {
            s = settled();
            Plan.Step step = s.plan.at(s.observed.slot);
            if (PlanView.isDirect(step)) {
                direct = step;
                break;
            }
            Outcome skip = Outcome.of(Outcome.Code.SKIPPED);
            skip.reason = "other";
            skip.sideDone = false;
            PlannerApi.Receipt r = api.recordOutcome(command(), s.stateRevision, step.slot, step.action, skip);
            assertTrue(r.errorZh, r.ok);
        }
        assertNotNull("no plain training or job within 40 slots", direct);

        // 3. 完成 records it directly on the real store, with no side step, and 撤销 voids it.
        refresh();
        assertEquals("完成", text(R.id.button_spec_done_text));
        int[] unlock = s.observed.unlockStep.clone();
        tap(R.id.button_spec_done);
        PlannerApi.Snapshot recorded = api.snapshot();
        assertEquals("one direct record", s.stateRevision + 1, recorded.stateRevision);
        assertTrue(recorded.observed.slot > direct.slot);
        assertArrayEquals("a direct record applies no side step", unlock, recorded.observed.unlockStep);
        assertEquals("✓ 已记录 · 再点撤销", text(R.id.widget_detail_cue));
        assertEquals("撤销", text(R.id.button_spec_done_text));

        clock.addAndGet(PlanView.UNDO_ARM_MS + 500);
        tap(R.id.button_spec_done);
        PlannerApi.Snapshot undone = api.snapshot();
        assertEquals(direct.slot, undone.observed.slot);
        assertEquals(s.stateRevision + 2, undone.stateRevision);
        settled();
        refresh();
        assertEquals("完成", text(R.id.button_spec_done_text));
    }

    // ------------------------------------------------------------------ scenario

    /**
     * The forecast state at the first training/job of a new game's plan that carries a side step
     * and is still planned first when the planner starts from there.
     */
    private static SimState sideStepJobState(Rules rules, Profile profile) {
        SimState start = newGame(rules, dayOf(rules, 4, 21));
        Plan plan = Engine.plan(rules, profile, start, null, new Engine.Options());
        SimState state = start.copy();
        for (Plan.Step step : plan.steps) {
            if (step.slot != state.slot) state = Engine.advanceTo(rules, profile, state, step.slot);
            if (directKind(step) && !step.side.isEmpty()) {
                Plan.Step first = Engine.plan(rules, profile, state, null, new Engine.Options()).at(state.slot);
                if (first != null && directKind(first) && !first.side.isEmpty()) return state;
            }
            if (!step.productive()) continue;
            state = Engine.apply(rules, profile, state, Action.parse(rules, step.action)).state;
        }
        throw new AssertionError("the new-game plan has no training or job with a side step");
    }

    private static boolean directKind(Plan.Step step) {
        return step != null && ("stat".equals(step.kind) || "job".equals(step.kind));
    }

    private static SimState newGame(Rules rules, int day) {
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = Rules.slot(day, false);
        s.cashExact = true;
        for (Rules.Link l : rules.links) {
            if (l.kind != Rules.LinkKind.STORY || l.storyRankDay == null) continue;
            for (int r = 1; r < l.storyRankDay.length && r <= 10; r++) {
                if (l.storyRankDay[r] >= 0 && l.storyRankDay[r] <= day) s.rank[l.idx] = r;
            }
        }
        return s;
    }

    private static int dayOf(Rules rules, int month, int dom) {
        for (int d = rules.firstDay; d <= rules.lastDay; d++) {
            Rules.Day day = rules.day(d);
            if (day != null && day.month() == month && day.dayOfMonth() == dom) return d;
        }
        throw new AssertionError(month + "/" + dom);
    }

    // ------------------------------------------------------------------ helpers

    private static String command() {
        return UUID.randomUUID().toString();
    }

    /** Waits for the plan of the latest records. */
    private PlannerApi.Snapshot settled() throws InterruptedException {
        long deadline = System.currentTimeMillis() + SETTLE_MS;
        while (true) {
            idle();
            PlannerApi.Snapshot s = api.snapshot();
            if (s.plan != null && s.planCurrent) return s;
            assertTrue("no plan after " + SETTLE_MS + " ms: " + s.planErrorZh, System.currentTimeMillis() < deadline);
            Thread.sleep(25);
        }
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void refresh() {
        PlanWidget.refreshAll(app);
        idle();
    }

    private View card() {
        View root = host.getViewFor(id);
        View settled = root.findViewById(R.id.widget_palette_static);
        if (settled.getVisibility() == View.VISIBLE) return settled;
        android.widget.ViewFlipper flipper = root.findViewById(R.id.widget_palette_flipper);
        return flipper.getCurrentView();
    }

    /** The text a reader sees (the invisible line-break marks removed). */
    private String text(int viewId) {
        return CardText.plain(((TextView) card().findViewById(viewId)).getText().toString());
    }

    private void tap(int viewId) {
        assertTrue(card().findViewById(viewId).performClick());
        idle();
    }
}
