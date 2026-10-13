package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Renders the plan widget for every state × palette × size bucket (and at font scale 1.15), writes
 * PNGs to $p3r.qa.dir/v3/widget/plan/, and fails on any clipped or lost text.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlanWidgetRenderTest {
    private static final int ID = 41;
    private static final int[][] SIZES = {{180, 150}, {250, 160}, {320, 200}};
    private Context app;
    private FakePlannerApi fake;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        fake = new FakePlannerApi();
        WidgetApi.setOverrideForTest(fake);
        WidgetWorker.setForTest(Runnable::run, (task, delay) -> { });
    }

    @After
    public void tearDown() {
        WidgetApi.setOverrideForTest(null);
        WidgetWorker.setForTest(null, null);
        RuntimeEnvironment.setFontScale(1f);
    }

    /** Prepares the fake/prefs for a named state. */
    private void state(String name) {
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        fake = new FakePlannerApi();
        WidgetApi.setOverrideForTest(fake);
        switch (name) {
            case "social": fake.observeAt("meet:hanged"); break;
            case "stat": fake.observeAt("act:study_room"); break;
            case "job": fake.observeAt("act:job_chagall"); break;
            case "unlock": fake.observeAt("unlock:temperance:0"); break;
            case "locked": fake.observeAt("locked:full_moon"); break;
            case "story-run":
                // 11/4 daytime through 11/5 daytime: one merged card.
                fake.plan.steps.set(8, FakePlannerApi.step(FakePlannerApi.FIRST_SLOT + 8, "locked:story", "locked", "剧情事件", "剧情推进", null, null));
                fake.observeAt("locked:story");
                break;
            case "tower": fake.observeAt("tower:4"); break;
            case "future":
                fake.observeAt("meet:hanged");
                WidgetPrefs.setCursor(app, ID, FakePlannerApi.PLAN_ID, FakePlannerApi.FIRST_SLOT + 9);
                break;
            case "empty": fake.observed = null; break;
            case "planning": fake.plan = null; fake.planning = true; break;
            case "receipt": {
                fake.observeAt("meet:emperor");
                WidgetPrefs.Receipt r = new WidgetPrefs.Receipt();
                r.eventSeq = 12;
                r.stateRevision = fake.stateRevision;
                r.slot = FakePlannerApi.FIRST_SLOT + 1;
                r.action = "act:study_room";
                r.planId = FakePlannerApi.PLAN_ID;
                r.title = "在宿舍房间学习";
                r.subtitle = "学力 +2";
                r.location = "宿舍 · 自己房间";
                r.kind = "stat";
                r.createdMillis = System.currentTimeMillis();
                WidgetPrefs.setReceipt(app, ID, r);
                break;
            }
            default: throw new IllegalArgumentException(name);
        }
    }

    private View render(boolean night, int width, int height) {
        PlanWidget.Loaded loaded = PlanWidget.load(app, ID);
        CardModel model = loaded.view.toCard(loaded.rules).withNight(night);
        return RenderSupport.layout(app, PlanWidget.card(app, ID, loaded.view, model, width, height), width, height);
    }

    @Test
    public void everyStateRendersCleanlyInBothPalettesAndAllSizes() throws Exception {
        File dir = RenderSupport.outputDir("plan");
        List<String> issues = new ArrayList<>();
        String[] states = {"social", "stat", "job", "unlock", "tower", "locked", "story-run", "future", "receipt", "empty", "planning"};
        for (String name : states) {
            for (boolean night : new boolean[]{false, true}) {
                for (int[] size : SIZES) {
                    state(name);
                    View root = render(night, size[0], size[1]);
                    String tag = name + "-" + (night ? "night" : "day") + "-" + size[0] + "x" + size[1];
                    RenderSupport.save(root, dir, tag);
                    RenderSupport.collectTextIssues(root, tag, issues);
                    RenderSupport.collectOverflow(root, R.id.widget_plate, tag, issues);
                    TextView title = root.findViewById(R.id.widget_task);
                    assertFalse(tag + " blank title", title.getText().toString().trim().isEmpty());
                    assertTrue(tag + " title collapsed", title.getHeight() > 0);
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    @Test
    public void largerFontScaleStillFits() throws Exception {
        RuntimeEnvironment.setFontScale(1.15f);
        File dir = RenderSupport.outputDir("plan-font115");
        List<String> issues = new ArrayList<>();
        for (String name : new String[]{"social", "stat", "future", "receipt", "empty"}) {
            for (int[] size : SIZES) {
                state(name);
                View root = render(false, size[0], size[1]);
                String tag = name + "-" + size[0] + "x" + size[1];
                RenderSupport.save(root, dir, tag);
                RenderSupport.collectTextIssues(root, tag, issues);
                RenderSupport.collectOverflow(root, R.id.widget_plate, tag, issues);
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    @Test
    public void stateCopyIsNaturalChinese() {
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("social", new String[]{"倒悬者 · 舞子", "RANK 4→5", "完成"});
        expected.put("stat", new String[]{"在宿舍房间学习", "学力 +2", "完成"});
        expected.put("future", new String[]{"探索塔尔塔罗斯", "本区块第 2 次", "做到这里"});
        expected.put("receipt", new String[]{"在宿舍房间学习", "学力 +2", "撤销"});
        expected.put("locked", new String[]{"满月作战", null, "完成"});
        expected.put("empty", new String[]{"设置目标，从当前存档开始", null, "开始设置"});
        for (Map.Entry<String, String[]> e : expected.entrySet()) {
            state(e.getKey());
            View root = render(false, 320, 200);
            assertEquals(e.getKey(), e.getValue()[0], text(root, R.id.widget_task));
            if (e.getValue()[1] != null) assertEquals(e.getKey(), e.getValue()[1], text(root, R.id.widget_tag));
            else assertEquals(e.getKey(), View.GONE, root.findViewById(R.id.widget_tag).getVisibility());
            assertEquals(e.getKey(), e.getValue()[2], text(root, R.id.button_spec_done_text));
        }
        state("receipt");
        assertEquals("✓ 已记录 · 再点撤销", text(render(false, 320, 200), R.id.widget_detail_cue));
        state("locked");
        View locked = render(false, 320, 200);
        assertFalse("locked items are information only", locked.findViewById(R.id.button_spec_done).isEnabled());
        assertEquals("剧情推进，无需选择", text(locked, R.id.widget_detail_cue));
        state("story-run");
        assertEquals("剧情推进，无需选择 · 持续到11月5日", text(render(false, 320, 200), R.id.widget_detail_cue));
        state("social");
        View social = render(false, 320, 200);
        assertEquals("11/1", text(social, R.id.widget_date));
        assertEquals("周日", text(social, R.id.widget_weekday));
        assertEquals("白天", text(social, R.id.widget_period_word));
        assertEquals("今日", text(social, R.id.widget_progress));
        state("stat");
        assertEquals("晚上", text(render(true, 320, 200), R.id.widget_period_word));
        state("future");
        assertEquals("4天后", text(render(true, 320, 200), R.id.widget_progress));
    }

    private static String text(View root, int id) {
        return ((TextView) root.findViewById(id)).getText().toString();
    }
}
