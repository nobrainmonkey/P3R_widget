package app.p3r.dayplanner;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import app.p3r.dayplanner.v3.widget.CardModel;
import app.p3r.dayplanner.v3.widget.CardText;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Legacy guide widget: real RemoteViews inflation, metadata, progress and Done/undo states. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 28, 35}, qualifiers = "en-rUS-w360dp-h800dp-xhdpi")
public class WidgetRemoteViewsTest {
    private static final int W = 73;
    private Context c;

    @Before
    public void clean() {
        c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("p3r_native_progress_v1", 0).edit().clear().commit();
    }

    private View render(String id, int width, int height) {
        PlannerStore.setWidgetCursor(c, W, id);
        View root = PlannerWidget.renderForTest(c, W, width, height).apply(c, new FrameLayout(c));
        root.measure(View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height * 2, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width * 2, height * 2);
        return root;
    }

    private static String text(View root, int id) {
        return ((TextView) root.findViewById(id)).getText().toString();
    }

    private int visibleTicks(View root) {
        int count = 0;
        for (int id : new int[]{R.id.widget_tick_0, R.id.widget_tick_1, R.id.widget_tick_2, R.id.widget_tick_3,
                R.id.widget_tick_4, R.id.widget_tick_5, R.id.widget_tick_6, R.id.widget_tick_7}) {
            if (root.findViewById(id).getVisibility() == View.VISIBLE) count++;
        }
        return count;
    }

    @Test
    public void allSupportedLayoutsInflateWithRealRemoteViewsAndCorrectMetadata() {
        String[] tasks = {"t005_01", "t005_04", "t016_03"};
        int[][] sizes = {{200, 160}, {250, 200}, {320, 180}, {320, 240}};
        for (String id : tasks) {
            for (int[] size : sizes) {
                PlannerStore.Task task = PlannerStore.taskById(c, id);
                View root = render(id, size[0], size[1]);
                // Title and location carry invisible word joiners; compare the text a reader sees.
                assertEquals(task.widgetTitle, CardText.plain(text(root, R.id.widget_task)));
                assertEquals(task.locationLabel, CardText.plain(text(root, R.id.widget_location)));
                int ordinal = PlannerStore.viewedOrdinal(c, W);
                int count = PlannerStore.viewedDay(c, W).tasks.size();
                assertEquals(ordinal + "/" + count, text(root, R.id.widget_progress));
                CardModel model = PlannerWidget.cardModelForTest(c, W, size[0], size[1]);
                assertEquals(count, model.ticks.size());
                assertEquals(CardModel.Tick.CURRENT, model.ticks.get(ordinal - 1));
                assertFalse("fresh progress has nothing done", model.ticks.contains(CardModel.Tick.DONE));
                int shown = visibleTicks(root);
                assertTrue("ticks are all shown or (no room beside the plate) none: " + shown, shown == count || shown == 0);
                if (count <= 4 && size[0] >= 280) assertEquals(count, shown);
                assertEquals(task.period, text(root, R.id.widget_period_word));
                assertEquals("当前", text(root, R.id.button_spec_current_text));
                assertEquals("完成", text(root, R.id.button_spec_done_text));
                assertTrue(root.findViewById(R.id.button_spec_current).hasOnClickListeners());
                assertTrue(root.findViewById(R.id.button_spec_done).hasOnClickListeners());
                assertTrue(root.findViewById(R.id.widget_prev).hasOnClickListeners());
                assertTrue(root.findViewById(R.id.widget_next).hasOnClickListeners());
                assertTrue(root.findViewById(R.id.widget_task_area).hasOnClickListeners());
            }
        }
    }

    /** Like the plan widget's: on the task 当前 would jump to, it is dimmed rather than a dead button. */
    @Test
    public void currentIsDimmedOnTheEarliestUnfinishedTask() {
        PlannerStore.Task first = PlannerStore.firstPendingGlobally(c);
        View onIt = render(first.id, 320, 200);
        assertFalse(onIt.findViewById(R.id.button_spec_current).isEnabled());
        assertEquals("当前：已经在最早未完成的任务", onIt.findViewById(R.id.button_spec_current).getContentDescription().toString());
        View later = render("t005_04", 320, 200);
        assertTrue(later.findViewById(R.id.button_spec_current).isEnabled());
        assertEquals("当前：回到最早未完成的任务", later.findViewById(R.id.button_spec_current).getContentDescription().toString());
    }

    @Test
    public void completedTasksAllowUndoWhileInformationalTasksDisableDone() {
        PlannerStore.Task action = PlannerStore.taskById(c, "t005_04");
        PlannerStore.markCompleted(c, action.id);
        View root = render(action.id, 250, 200);
        assertTrue("Completed task can be explicitly undone", root.findViewById(R.id.button_spec_done).isEnabled());
        assertEquals("撤销", text(root, R.id.button_spec_done_text));
        assertEquals("✓ 已完成 · 再点撤销", text(root, R.id.widget_detail_cue));
        assertEquals(action.id, PlannerStore.viewedTask(c, W).id);

        PlannerStore.Task info = null;
        for (PlannerStore.Day day : PlannerStore.days(c)) {
            for (PlannerStore.Task task : day.tasks) {
                if (!task.isCheckable()) { info = task; break; }
            }
            if (info != null) break;
        }
        assertNotNull(info);
        root = render(info.id, 250, 200);
        assertFalse(root.findViewById(R.id.button_spec_done).isEnabled());
        assertEquals("完成", text(root, R.id.button_spec_done_text));
        assertTrue(root.findViewById(R.id.button_spec_current).isEnabled());
    }

    @Test
    @Config(sdk = 28)
    public void ticksPreserveSkippedMiddleAndImportedActualCompletion() {
        PlannerStore.Day selected = null;
        for (PlannerStore.Day day : PlannerStore.days(c)) {
            if (day.tasks.size() >= 3 && day.tasks.size() <= 8 && day.tasks.get(0).isCheckable()
                    && day.tasks.get(1).isCheckable() && day.tasks.get(2).isCheckable()) {
                selected = day;
                break;
            }
        }
        assertNotNull(selected);
        PlannerStore.markCompleted(c, selected.tasks.get(0).id);
        PlannerStore.markCompleted(c, selected.tasks.get(2).id);
        render(selected.tasks.get(1).id, 250, 200);
        List<CardModel.Tick> ticks = PlannerWidget.cardModelForTest(c, W, 250, 200).ticks;
        assertEquals(List.of(CardModel.Tick.DONE, CardModel.Tick.CURRENT, CardModel.Tick.DONE), ticks.subList(0, 3));

        String saved = PlannerStore.snapshot(c);
        c.getSharedPreferences("p3r_native_progress_v1", 0).edit().clear().commit();
        PlannerStore.acceptWebState(c, saved);
        View root = render(selected.tasks.get(2).id, 250, 200);
        ticks = PlannerWidget.cardModelForTest(c, W, 250, 200).ticks;
        assertEquals(List.of(CardModel.Tick.DONE, CardModel.Tick.PENDING, CardModel.Tick.CURRENT), ticks.subList(0, 3));
        assertEquals("3/" + selected.tasks.size(), text(root, R.id.widget_progress));
        assertFalse(PlannerStore.isDone(PlannerStore.completed(c), selected.tasks.get(1)));
    }

    @Test
    public void unmarkedPeriodIsNeverShownAndLabelsAreChinese() {
        for (PlannerStore.Day day : PlannerStore.days(c)) {
            for (PlannerStore.Task task : day.tasks) {
                if (!"未标注".equals(task.period)) continue;
                View root = render(task.id, 250, 200);
                assertEquals(View.GONE, root.findViewById(R.id.widget_period_word).getVisibility());
                assertEquals(day.month + "/" + day.day, text(root, R.id.widget_date));
                return;
            }
        }
    }
}
