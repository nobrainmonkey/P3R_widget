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
                View root = RenderSupport.layout(c, CardRenderer.render(c, m, size[0], size[1]), size[0], size[1]);
                String tag = (night ? "night" : "day") + "-" + size[0] + "x" + size[1];
                RenderSupport.save(root, dir, tag);
                RenderSupport.collectTextIssues(root, tag, issues);
                RenderSupport.collectOverflow(root, R.id.widget_plate, tag, issues);
                RenderSupport.collectHeaderOverlap(root, tag, issues);
                assertEquals("皇帝 · 小田桐秀利", ((TextView) root.findViewById(R.id.widget_task)).getText().toString());
                assertEquals(View.VISIBLE, root.findViewById(R.id.widget_tag).getVisibility());
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /**
     * With {@link CardModel#keepWordsWhole} (the plan widget) names and venues stay whole at every
     * size and font scale, while the drawn text reads the same.
     */
    @Test
    public void namesAndVenuesStayWholeWhenTheTitleWraps() {
        Context c = RuntimeEnvironment.getApplication();
        List<String> issues = new ArrayList<>();
        String[][] cards = {
                {"皇帝 · 小田桐秀利", "月光馆学园 3楼 走廊"},
                {"结缘网站的字条 · 山岸风花", "桐叶购物中心 · 放纵俱乐部"},
                {"羁绊剧情 · 伊织顺平（第 2 话）", "月光馆学园 2楼 教室"},
                {"在夏卡尔咖啡店喝费洛蒙咖啡", "桐叶购物中心 · 夏卡尔咖啡店"},
                {"在电玩大巡游游玩「你就是答案」", "桐叶购物中心 · 电玩大巡游"}};
        for (float scale : new float[]{1f, 1.3f}) {
            RuntimeEnvironment.setFontScale(scale);
            for (String[] card : cards) {
                for (int[] size : new int[][]{{180, 130}, {180, 150}, {250, 160}, {320, 200}}) {
                    CardModel m = sample(false);
                    m.keepWordsWhole = true;
                    m.title = card[0];
                    m.location = card[1];
                    View root = RenderSupport.layout(c, CardRenderer.render(c, m, size[0], size[1]), size[0], size[1]);
                    String tag = card[0] + "@" + size[0] + "x" + size[1] + "x" + scale;
                    RenderSupport.collectWordSplits(root, tag, issues);
                    TextView title = root.findViewById(R.id.widget_task);
                    assertEquals(tag, card[0], CardText.plain(title.getText().toString()));
                    String drawn = title.getText().toString();
                    for (String word : new String[]{"小田桐秀利", "山岸风花", "伊织顺平", "夏卡尔咖啡店", "费洛蒙咖啡", "你就是答案"}) {
                        if (!card[0].contains(word)) continue;
                        for (int line = 0; line < title.getLineCount() - 1; line++) {
                            int end = title.getLayout().getLineEnd(line);
                            String before = CardText.plain(drawn.substring(0, end));
                            String after = CardText.plain(drawn.substring(end));
                            boolean split = !before.contains(word) && !after.contains(word);
                            if (split && before.length() < card[0].length()) issues.add(tag + " splits " + word + ": " + before + " / " + after);
                        }
                    }
                }
            }
        }
        RuntimeEnvironment.setFontScale(1f);
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /** A wrapped title breaks before 「（第 2 话）」, never inside it. */
    @Test
    public void aNumberStaysWithItsWordsWhenTheTitleWraps() {
        Context c = RuntimeEnvironment.getApplication();
        for (int[] size : new int[][]{{180, 150}, {250, 160}, {320, 200}}) {
            CardModel m = sample(false);
            m.title = "羁绊剧情 · 伊织顺平（第 2 话）";
            m.titleIcon = CardModel.TitleIcon.EPISODE;
            m.tag = null;
            TextView title = RenderSupport.layout(c, CardRenderer.render(c, m, size[0], size[1]), size[0], size[1])
                    .findViewById(R.id.widget_task);
            String text = title.getText().toString();
            for (int line = 0; line < title.getLineCount(); line++) {
                String part = text.substring(title.getLayout().getLineStart(line), title.getLayout().getLineEnd(line));
                if (part.contains("第")) assertTrue(size[0] + "dp: '" + part + "'", part.contains("话"));
            }
        }
        assertEquals("羁绊剧情 · 伊织顺平（第\u00A02\u00A0话）", CardRenderer.keepNumbersTogether("羁绊剧情 · 伊织顺平（第 2 话）"));
        assertEquals("再交\u00A010,000\u00A0日元", CardRenderer.keepNumbersTogether("再交 10,000 日元"));
        assertEquals("皇帝 · 小田桐秀利", CardRenderer.keepNumbersTogether("皇帝 · 小田桐秀利"));
    }
}
