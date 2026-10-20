package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;

/**
 * The UI side of the engine's final-gaps changes: a meeting's 「怎么做」 carries the guide answers and
 * the link's 「注意：」 line (the note shown under the numbered steps, never as a step; the answers
 * shown once, short, beside the step instead), and a missed goal names the goal the planner actually
 * kept instead (Summary.tradedFor).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FinalIntegrationTest extends UiTestBase {
    private static final int NOW = Fixtures.SLOT_NOW;
    private static final String ANSWERS = "回答：我会帮你的 / 这样的规矩没有意义";
    private static final String MEET_HOW = "放学后到月光馆学园 · 学生会活动室找小田桐秀利\n"
        + "顺便：把 20,000 日元交给穿西装的男人\n"
        + ANSWERS + "\n"
        + "注意：考试期间不能见面";

    @Test
    public void notesFollowTheNumberedStepsAndAreNotCounted() {
        assertEquals(Arrays.asList("放学后到月光馆学园 · 学生会活动室找小田桐秀利", "顺便：把 20,000 日元交给穿西装的男人"),
            StepViews.howToSteps(MEET_HOW));
        // The answers are the step block's (Dialogue.line, once), not a 怎么做 note.
        assertEquals(Arrays.asList("注意：考试期间不能见面"), StepViews.howToNotes(MEET_HOW));
        assertEquals("怎么做 · 2 步", StepViews.howToLabel(MEET_HOW));
        assertEquals("one step and a note is one step", "怎么做", StepViews.howToLabel("白天去图书馆\n注意：周日不开"));

        LinearLayout box = (LinearLayout) StepViews.howTo(RuntimeEnvironment.getApplication(), MEET_HOW, 14);
        assertEquals(3, box.getChildCount());
        assertEquals("第 1 步：放学后到月光馆学园 · 学生会活动室找小田桐秀利", box.getChildAt(0).getContentDescription());
        assertEquals("第 2 步：顺便：把 20,000 日元交给穿西装的男人", box.getChildAt(1).getContentDescription());
        assertEquals("注意：考试期间不能见面", box.getChildAt(2).getContentDescription());
        // The whole question order is 提示's, not the plan step's.
        assertEquals(Arrays.asList("注意：考试期间不能见面"),
            StepViews.howToNotes(MEET_HOW + "\n注意：完整问答顺序是 任意 / 我会帮你的"));
    }

    @Test
    public void theGuideAnswersAreShownOnceWhenTheHowToHasThem() {
        Plan.Step step = Fixtures.social(api.rules, NOW, "emperor", 6, 7, true, true);
        assertEquals(ANSWERS, Dialogue.line(api.rules, api.snap.profile, step));
        step.detailZh = MEET_HOW;

        api.snap.plan.steps.set(0, step);
        // Before playing (the day sheet) the answers are shown once; after it (the result sheet) not at all.
        HomeActivity home = home();
        home.openDetail(NOW);
        assertEquals(1, countTextViews(home.sheets(), "回答："));
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        assertEquals(0, countTextViews(a.getWindow().getDecorView(), "回答："));
        Shots.shot(a, "result_social_answers_once");
    }

    @Test
    public void aPlanStoredBeforeTheEngineAddedTheAnswersStillShowsThem() {
        Plan.Step step = Fixtures.social(api.rules, NOW, "emperor", 6, 7, true, true);
        step.detailZh = "放学后到月光馆学园 · 学生会活动室找小田桐秀利";
        api.snap.plan.steps.set(0, step);
        HomeActivity home = home();
        home.openDetail(NOW);
        assertEquals(1, countTextViews(home.sheets(), "回答："));
        ResultActivity a = result(NOW, "meet:emperor", Intents.MODE_RESULT);
        assertEquals("the step is done: no answers on the result sheet", 0,
            countTextViews(a.getWindow().getDecorView(), "回答："));
    }

    @Test
    public void aMissedGoalNamesOnlyTheGoalThePlannerKept() {
        Plan plan = new Plan();
        assertNull("no plan reached it: no reason is claimed", LinkStatus.higherPriorityBlocker(api.rules, plan, 2));
        plan.summary.tradedFor[2] = 4;
        assertEquals("皇帝", LinkStatus.higherPriorityBlocker(api.rules, plan, 2));
        plan.summary.tradedFor[2] = 2;
        assertNull("never itself", LinkStatus.higherPriorityBlocker(api.rules, plan, 2));
        // Without a goal it was traded for, the line names none: it says how far the plan gets.
        assertEquals("这次排到 RANK 9。想冲 MAX，就把它往前排", LinkStatus.shortfall("MAX", 5, 9, null));
    }

    @Test
    public void theFollowUpFlagDecidesTheBudgetLine() {
        Plan plan = new Plan();
        plan.budgetExhausted = true;
        assertEquals(InfoPages.BUDGET_PENDING, InfoPages.budgetLine(plan));
        plan.followUp = true;
        assertEquals(InfoPages.BUDGET_ROUGH, InfoPages.budgetLine(plan));
        assertNotNull(plan.budgetNoteZh());
    }

    @Test
    public void theAboutPageShowsTheInstalledVersion() throws Exception {
        String gradle = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("build.gradle")),
            java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("versionName '([^']+)'").matcher(gradle);
        assertTrue(m.find());
        assertEquals(m.group(1), InfoPages.versionName(RuntimeEnvironment.getApplication()));
    }

    private static int countTextViews(View v, String needle) {
        int n = 0;
        if (v instanceof TextView && Zh.plain(((TextView) v).getText()).contains(needle)) {
            n++;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += countTextViews(g.getChildAt(i), needle);
            }
        }
        return n;
    }
}
