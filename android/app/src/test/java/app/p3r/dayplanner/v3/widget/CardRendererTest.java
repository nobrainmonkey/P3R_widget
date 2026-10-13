package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Renderer-level checks on hand-built models (palette, visibility, no clipping). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CardRendererTest {

    static CardModel sample(boolean night) {
        CardModel m = new CardModel();
        m.night = night;
        m.dateText = "10/22";
        m.weekdayZh = "周四";
        m.moonPhase = 2;
        m.periodZh = night ? "晚上" : "放学后";
        m.watermarkDay = 22;
        m.motif = 1;
        m.progressLabel = "今日";
        m.ticks.add(night ? CardModel.Tick.DONE : CardModel.Tick.CURRENT);
        m.ticks.add(night ? CardModel.Tick.CURRENT : CardModel.Tick.PENDING);
        m.title = "皇帝 · 小田桐秀利";
        m.tag = "RANK 6→7";
        m.location = "月光馆学园 3楼 走廊";
        return m;
    }

    @Test
    public void rendersEverySizeAndPaletteWithoutClipping() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        File dir = RenderSupport.outputDir("renderer");
        List<String> issues = new ArrayList<>();
        int[][] sizes = {{180, 140}, {180, 160}, {250, 150}, {250, 200}, {320, 180}, {320, 240}};
        for (boolean night : new boolean[]{false, true}) {
            for (int[] size : sizes) {
                CardModel m = sample(night);
                WidgetSize bucket = WidgetSize.of(size[0], size[1]);
                View root = RenderSupport.layout(c, CardRenderer.render(c, m, bucket), size[0], size[1]);
                String tag = (night ? "night" : "day") + "-" + size[0] + "x" + size[1];
                RenderSupport.save(root, dir, tag);
                RenderSupport.collectTextIssues(root, tag, issues);
                RenderSupport.collectOverflow(root, R.id.widget_plate, tag, issues);
                assertEquals("皇帝 · 小田桐秀利", ((TextView) root.findViewById(R.id.widget_task)).getText().toString());
                assertEquals(View.VISIBLE, root.findViewById(R.id.widget_tag).getVisibility());
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }
}
