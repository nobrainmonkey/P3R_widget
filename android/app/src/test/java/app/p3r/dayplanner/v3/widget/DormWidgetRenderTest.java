package app.p3r.dayplanner.v3.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.DormRenderCases;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

/** Real dorm names and third-completion progress remain readable from 4×2 through 5×2 widgets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 34}, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DormWidgetRenderTest {
    private static final int[][] SIZES = {{250, 140}, {320, 160}, {360, 180}, {400, 180}};
    private Context app;
    private Rules rules;

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            File file = new File(candidate);
            if (file.isFile()) {
                rules = RulesLoader.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                break;
            }
        }
        assertNotNull(rules);
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
    }

    @After
    public void tearDown() {
        RuntimeEnvironment.setFontScale(1f);
    }

    @Test
    public void allDormMilestonesFitAtEachSize() throws Exception {
        List<Plan.Step> steps = DormRenderCases.thirdCompletions(rules);
        assertEquals(18, steps.size());
        List<String> issues = new ArrayList<>();
        for (float font : new float[]{1f, 1.15f}) {
            RuntimeEnvironment.setFontScale(font);
            for (Plan.Step step : steps) {
                FakePlannerApi fake = new FakePlannerApi();
                fake.rules = rules;
                fake.plan.fromSlot = step.slot;
                fake.plan.toSlot = step.slot;
                fake.plan.steps.clear();
                fake.plan.steps.add(step);
                fake.observed = new SimState(rules.blocks.length, rules.activities.length);
                fake.observed.slot = step.slot;
                PlanView plan = PlanView.resolve(fake.snapshot(), -1, null, 0L);
                assertEquals(PlanView.Done.COMMIT, plan.done);
                CardModel model = plan.toCard(rules);
                assertTrue(step.action, model.tag.contains("3/3"));
                assertFalse("the chip leaves level-up prose to details and accessibility", model.tag.contains("升到"));
                assertTrue(step.action, model.taskDescription.contains(step.subtitleZh));
                assertEquals(CardModel.TitleIcon.EPISODE, model.titleIcon);
                for (int[] size : SIZES) {
                    String name = step.entity + "-" + size[0] + "x" + size[1] + "-font" + font;
                    View root = RenderSupport.layout(app, PlanWidget.card(app, 94, plan, plan.toCard(rules), size[0], size[1]), size[0], size[1]);
                    RenderSupport.collectTextIssues(root, name, issues);
                    RenderSupport.collectCutText(root, name, issues);
                    RenderSupport.collectWordSplits(root, name, issues);
                    RenderSupport.collectHiddenTitle(root, name, issues);
                    CharSequence description = root.findViewById(R.id.widget_task_area).getContentDescription();
                    assertNotNull(name, description);
                    assertTrue(name, description.toString().contains(step.titleZh));
                    assertTrue(name, description.toString().contains("3/3"));
                    TextView tag = root.findViewById(R.id.widget_tag);
                    assertEquals(name, View.VISIBLE, tag.getVisibility());
                    if (tag.getVisibility() == View.VISIBLE) {
                        assertFalse(name, RenderSupport.ellipsized(tag));
                        assertTrue(name, CardText.plain(tag.getText().toString()).contains("3/3"));
                    }
                    if (font == 1f) {
                        RenderSupport.save(root, RenderSupport.outputDir("dorm"), name);
                    }
                }
                step.side.add("unlock:devil:0");
                assertFalse("side steps still require confirmation", PlanView.isDirect(step));
                step.side.clear();
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }
}
