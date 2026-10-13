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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.p3r.dayplanner.PlannerStore;
import app.p3r.dayplanner.PlannerWidget;
import app.p3r.dayplanner.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The legacy guide widget keeps words whole like the plan widget (re-audit: 「交给伊莉莎/白」,
 * 「魔/术师→RANK2」, 「天鹅绒房/间」, 「确/认人格面具」, 「委托/11」 on the small card). Renders guide
 * records at 180x160 and 320x200 into $p3r.qa.dir/v3/widget/legacy_words, with every title's lines in
 * lines.txt for review.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LegacyGuideWordsRenderTest {
    private static final int ID = 92;
    private static final int[][] SIZES = {{180, 160}, {320, 200}};
    private Context c;

    @Before
    public void clean() {
        c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("p3r_native_progress_v1", 0).edit().clear().commit();
    }

    private View render(String id, int[] size) {
        PlannerStore.setWidgetCursor(c, ID, id);
        return RenderSupport.layout(c, PlannerWidget.renderForTest(c, ID, size[0], size[1]), size[0], size[1]);
    }

    @Test
    public void theAuditedNamesNeverSplitOnSmallOrLargeCards() throws Exception {
        File dir = RenderSupport.outputDir("legacy_words");
        String[][] cases = {
                {"t048_02", "伊莉莎白", "委托11", "12种"},
                {"t014_03", "魔术师→RANK2", "友近"},
                {"t005_04", "天鹅绒房间", "确认", "人格面具"},
                {"t055_06", "放纵俱乐部", "天鹅绒房间", "伊莉莎白"},
                {"t082_04", "山岸风花", "伊莉莎白", "委托43"},
        };
        List<String> issues = new ArrayList<>();
        for (String[] k : cases) {
            for (int[] size : SIZES) {
                String tag = k[0] + "-" + size[0] + "x" + size[1];
                View root = render(k[0], size);
                RenderSupport.save(root, dir, tag);
                TextView title = root.findViewById(R.id.widget_task);
                assertEquals(PlannerStore.taskById(c, k[0]).widgetTitle, CardText.plain(title.getText().toString()));
                RenderSupport.collectJoinerBreaks(root, tag, issues);
                for (String word : Arrays.copyOfRange(k, 1, k.length)) {
                    splitWords(title, Arrays.asList(word), tag, issues);
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /**
     * Found while re-rendering: on the small card the title of t028_01 was cut off under the date plate
     * (its first choice is wider than the card, so 「keep every choice whole」 was measured as two lines
     * but drawn as three), and the long choice of t106_01 pushed the title over three lines and the
     * location off the card. Every line here is whole and nothing is clipped.
     */
    @Test
    public void longChoicesAndShoppingListsFitTheSmallCard() throws Exception {
        File dir = RenderSupport.outputDir("legacy_words");
        View small = render("t028_01", SIZES[0]);
        RenderSupport.save(small, dir, "t028_01-180x160");
        List<String> issues = new ArrayList<>();
        RenderSupport.collectTextIssues(small, "t028_01", issues);
        assertTrue(String.join("\n", issues), issues.isEmpty());
        TextView choices = small.findViewById(R.id.widget_dialogue_choices);
        assertEquals("the choices stay on the card", View.VISIBLE, choices.getVisibility());
        android.text.Layout layout = choices.getLayout();
        String text = choices.getText().toString();
        for (int line = 1; line < layout.getLineCount(); line++) {
            String shown = CardText.plain(text.substring(layout.getLineStart(line), layout.getLineEnd(line))).trim();
            assertFalse("a line of the choices starts with one character alone: " + shown, shown.matches("^\\S(\\s.*)?$"));
        }
        assertEquals("我也去看看吧或者我在这里等她 / 任意 / 真担心啊（3）", CardText.plain(choices.getText().toString()));

        View shopping = render("t106_01", SIZES[0]);
        RenderSupport.save(shopping, dir, "t106_01-180x160");
        TextView title = shopping.findViewById(R.id.widget_task);
        assertEquals("电视购物 · 同位素汽水10个 / +莫乐蜜G20个，3980日元", RenderSupport.lines(title));
        assertEquals(View.VISIBLE, shopping.findViewById(R.id.widget_location).getVisibility());
    }

    @Test
    public void everyGuideRecordWrapsBetweenWords() throws Exception {
        File dir = RenderSupport.outputDir("legacy_words");
        List<String> issues = new ArrayList<>();
        List<String> report = new ArrayList<>();
        int records = 0;
        for (PlannerStore.Day day : PlannerStore.days(c)) {
            for (PlannerStore.Task task : day.tasks) {
                records++;
                List<String> words = new ArrayList<>(GuideWords.WORDS);
                words.addAll(CardText.locationWords(task.locationLabel));
                for (int[] size : SIZES) {
                    String tag = task.id + "-" + size[0] + "x" + size[1];
                    View root = render(task.id, size);
                    TextView title = root.findViewById(R.id.widget_task);
                    assertEquals(tag, task.widgetTitle, CardText.plain(title.getText().toString()));
                    RenderSupport.collectJoinerBreaks(root, tag, issues);
                    RenderSupport.collectTextIssues(root, tag, issues);
                    splitWords(title, words, tag, issues);
                    if (title.getLineCount() > 1) report.add(tag + "\t" + RenderSupport.lines(title));
                }
            }
        }
        assertEquals(691, records);
        Files.write(new File(dir, "lines.txt").toPath(), report, StandardCharsets.UTF_8);
        assertTrue(issues.size() + " issues:\n" + String.join("\n", issues), issues.isEmpty());
    }

    /**
     * Each line break of the title falls outside every occurrence of {@code words} in the visible
     * text, unless the line breaker had to cut a run wider than the card.
     */
    private static void splitWords(TextView title, List<String> words, String tag, List<String> issues) {
        if (title.getLayout() == null) return;
        android.text.Layout layout = title.getLayout();
        String shaped = title.getText().toString();
        String plain = CardText.plain(shaped);
        for (int line = 0; line < layout.getLineCount() - 1; line++) {
            if (layout.getEllipsisCount(line) > 0) continue;
            if (RenderSupport.forcedBreak(title, layout.getLineEnd(line))) continue;
            int at = CardText.plain(shaped.substring(0, layout.getLineEnd(line))).length();
            for (String word : words) {
                if (word.length() < 2) continue;
                for (int from = plain.indexOf(word); from >= 0; from = plain.indexOf(word, from + 1)) {
                    if (from < at && at < from + word.length()) {
                        issues.add(tag + " splits 「" + word + "」: '" + plain.substring(0, at) + "' / '" + plain.substring(at) + "'");
                    }
                }
            }
        }
    }
}
