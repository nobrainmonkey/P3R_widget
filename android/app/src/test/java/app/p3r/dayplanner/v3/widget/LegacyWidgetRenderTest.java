package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.PlannerStore;
import app.p3r.dayplanner.PlannerWidget;
import app.p3r.dayplanner.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The restyled legacy guide widget on the shared card: renders day and night guide records at the
 * three size buckets on both SDK lines into $p3r.qa.dir/v3/widget/legacy and checks for clipping.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LegacyWidgetRenderTest {
    private static final int ID = 91;
    private Context c;

    @Before
    public void clean() {
        c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("p3r_native_progress_v1", 0).edit().clear().commit();
    }

    @Test
    public void guideRecordsRenderCleanlyOnTheSharedCard() throws Exception {
        File dir = RenderSupport.outputDir("legacy");
        List<String> issues = new ArrayList<>();
        // 放学后 action with steps, an evening task, a long title and a dialogue-choice task.
        String[] tasks = {"t005_04", "t016_03", "t048_02", "t016_02"};
        int[][] sizes = {{180, 160}, {250, 180}, {320, 200}};
        for (String id : tasks) {
            for (int[] size : sizes) {
                PlannerStore.setWidgetCursor(c, ID, id);
                View root = RenderSupport.layout(c, PlannerWidget.renderForTest(c, ID, size[0], size[1]), size[0], size[1]);
                String tag = id + "-" + size[0] + "x" + size[1];
                RenderSupport.save(root, dir, tag);
                RenderSupport.collectTextIssues(root, tag, issues);
                RenderSupport.collectOverflow(root, R.id.widget_plate, tag, issues);
                RenderSupport.collectHeaderOverlap(root, tag, issues);
                assertEquals(PlannerStore.taskById(c, id).widgetTitle, ((TextView) root.findViewById(R.id.widget_task)).getText().toString());
                assertEquals("当前", ((TextView) root.findViewById(R.id.button_spec_current_text)).getText().toString());
                assertEquals("完成", ((TextView) root.findViewById(R.id.button_spec_done_text)).getText().toString());
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }
}
