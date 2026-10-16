package app.p3r.dayplanner.v3.widget;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;
import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.DialogueHint;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Newly covered conditional scenes retain their first actual answer beside the certain class answer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ConditionalAnswerFitTest {
    private static CardModel model(String title, List<String> choices, String classAnswer) {
        CardModel m = CardRendererTest.sample(false);
        m.title = title;
        m.keepWordsWhole = true;
        m.shortTitles.addAll(StepCopy.shortTitles(title, null));
        m.answers.add(new CardModel.AnswerLine(DialogueHint.CONDITIONAL_PREFIX,
                DialogueHint.CONDITIONAL_SHORT_PREFIX, choices, null, true));
        m.answers.add(new CardModel.AnswerLine(CardModel.CLASS_LABEL, null, List.of(classAnswer), null, false));
        return m;
    }

    private static String text(View root, int id) {
        View view = root.findViewById(id);
        return view.getVisibility() == View.VISIBLE ? CardText.plain(((TextView) view).getText().toString()) : "";
    }

    private static void assertInkInsideCard(View root, int id, String tag) {
        TextView view = root.findViewById(id);
        if (view.getVisibility() != View.VISIBLE) return;
        Rect bounds = RenderSupport.boundsIn(root, view);
        assertTrue(tag + " view leaves card: " + bounds, bounds.left >= 0 && bounds.right <= root.getWidth());
        String shaped = view.getText().toString();
        for (int i = 0; i < view.getLineCount(); i++) {
            int from = view.getLayout().getLineStart(i);
            int to = view.getLayout().getLineVisibleEnd(i);
            Rect ink = new Rect();
            view.getPaint().getTextBounds(shaped, from, to, ink);
            float origin = bounds.left + view.getCompoundPaddingLeft() - view.getScrollX()
                    + view.getLayout().getLineLeft(i);
            float left = origin + ink.left;
            float right = origin + ink.right;
            System.out.println(tag + " text=" + CardText.plain(shaped.substring(from, to))
                    + " view=" + bounds + " ink=" + left + ".." + right + " cardRight=" + root.getWidth());
            assertTrue(tag + " ink leaves text view/card: " + left + ".." + right + " " + bounds,
                    left >= bounds.left && right <= bounds.right && right <= root.getWidth());
        }
    }

    @Test
    public void tinyConditionalScenesRetainBothKindsWithoutRewritingApprovedAnswers() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        RuntimeEnvironment.setFontScale(1.15f);
        try {
            File dir = RenderSupport.outputDir("answers/conditional-fit");
            Object[][] cases = {
                    {"magician", "魔术师 · 友近健二", List.of("你有烦恼？", "你也跟去或你们好好谈谈"), "男女关系"},
                    {"star", "星 · 早濑护", List.of("是一个好机会", "肯定能"), "多巴胺"},
                    {"strength", "力量 · 西胁结子", List.of("因为教得好", "我相信结子"), "奥义书"}
            };
            for (Object[] item : cases) {
                @SuppressWarnings("unchecked") List<String> choices = (List<String>) item[2];
                CardModel original = model((String) item[1], choices, (String) item[3]);
                CardModel fitted = CardRenderer.fit(c, original, WidgetSize.TINY, 180, 130);
                View root = RenderSupport.layout(c, CardRenderer.render(c, original, 180, 130), 180, 130);
                String row = text(root, R.id.widget_dialogue_choices);
                String aside = text(root, R.id.widget_title_aside);
                assertTrue(item[0] + " " + row, row.startsWith(DialogueHint.CONDITIONAL_PREFIX)
                        || row.startsWith(DialogueHint.CONDITIONAL_SHORT_PREFIX));
                assertTrue(item[0] + " " + row, row.contains(choices.get(0)));
                assertTrue(item[0] + " " + row + " | " + aside, (row + aside).contains((String) item[3]));
                assertTrue(item[0] + " must reclaim only at this boundary", fitted.bodySidePaddingDp >= 0
                        && fitted.bodySidePaddingDp < CardRenderer.bodyPaddingDp(WidgetSize.TINY));
                float density = c.getResources().getDisplayMetrics().density;
                assertEquals(Math.round(fitted.bodySidePaddingDp * density), root.findViewById(R.id.widget_task_area).getPaddingLeft());
                assertInkInsideCard(root, R.id.widget_title_aside, item[0] + "-aside");
                assertInkInsideCard(root, R.id.widget_dialogue_choices, item[0] + "-answers");
                assertEquals(choices, original.answers.get(0).choices);
                assertEquals(-1f, original.bodySidePaddingDp, 0);
                List<String> issues = new ArrayList<>();
                RenderSupport.collectTextIssues(root, item[0].toString(), issues);
                RenderSupport.collectCutText(root, item[0].toString(), issues);
                RenderSupport.collectHiddenTitle(root, item[0].toString(), issues);
                assertTrue(String.join("\n", issues), issues.isEmpty());
                RenderSupport.save(root, dir, item[0] + "-180x130-font115");
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    @Test
    public void ordinaryCardsAndCardsWithoutBothKindsKeepTheirExistingPadding() {
        Context c = RuntimeEnvironment.getApplication();
        RuntimeEnvironment.setFontScale(1.15f);
        try {
            CardModel both = model("星 · 早濑护", Arrays.asList("是一个好机会", "肯定能"), "多巴胺");
            assertEquals(-1f, CardRenderer.fit(c, both, WidgetSize.TINY, 180, 150).bodySidePaddingDp, 0);
            CardModel alone = both.copy();
            alone.answers.remove(1);
            assertEquals(-1f, CardRenderer.fit(c, alone, WidgetSize.TINY, 180, 130).bodySidePaddingDp, 0);
            CardModel justClass = both.copy();
            justClass.answers.remove(0);
            assertEquals(-1f, CardRenderer.fit(c, justClass, WidgetSize.TINY, 180, 130).bodySidePaddingDp, 0);
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }
}
