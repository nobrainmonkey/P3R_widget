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
import static org.junit.Assert.assertSame;
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
        m.progressLabel = "今天";
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
     * 3.1.0 re-check: the side step (「顺便：…」) and the 人格面具 reminder were cut with "…" in the middle
     * of a word (「顺便：把 20,000 日元交给穿西装…」). Like the reminder row they show whole, else a short
     * form (「顺便：恶魔社群解锁前的准备」, then 「顺便还有一件事，点开看看」), else no row, at every size,
     * font scale and SDK.
     */
    @Test
    public void sideAndHintRowsAreWholeOrShortOrGone() {
        Context c = RuntimeEnvironment.getApplication();
        String side = "顺便：在喷泉前把 20,000 日元交给田中社长";
        String shortSide = "顺便：恶魔社群解锁前的准备";
        String hint = "记得带上「皇帝」阿尔卡那的人格面具";
        assertEquals(side, CardRenderer.fitSide(c, side, shortSide, WidgetSize.REGULAR, 300));
        assertEquals(shortSide, CardRenderer.fitSide(c, side, shortSide, WidgetSize.TINY, 160));
        assertEquals("顺便还有一件事，点开看看", CardRenderer.fitSide(c, side, null, WidgetSize.TINY, 160));
        assertEquals("顺便还有 2 件事，点开看看", CardRenderer.fitSide(c, side + "；再交给田中社长 10,000 日元", null, WidgetSize.TINY, 160));
        assertEquals(null, CardRenderer.fitSide(c, side, shortSide, WidgetSize.TINY, 60));
        assertEquals(hint, CardRenderer.fitHint(c, hint, WidgetSize.REGULAR, 300));
        assertEquals(null, CardRenderer.fitHint(c, hint, WidgetSize.TINY, 150));
        List<String> issues = new ArrayList<>();
        int[][] sizes = {{180, 150}, {180, 200}, {250, 160}, {250, 200}, {320, 200}};
        for (float scale : new float[]{1f, 1.15f, 1.3f}) {
            RuntimeEnvironment.setFontScale(scale);
            for (int[] size : sizes) {
                CardModel m = sample(false);
                m.side = side;
                m.sideShort = shortSide;
                m.hint = hint;
                View root = RenderSupport.layout(c, CardRenderer.render(c, m, size[0], size[1]), size[0], size[1]);
                String tag = "side-hint-" + size[0] + "x" + size[1] + "@" + scale;
                RenderSupport.collectCutText(root, tag, issues);
                TextView shown = root.findViewById(R.id.widget_side);
                if (shown.getVisibility() == View.VISIBLE) {
                    String text = shown.getText().toString();
                    if (!text.equals(side) && !text.equals(shortSide) && !text.equals("顺便还有一件事，点开看看")) {
                        issues.add(tag + " side shows '" + text + "'");
                    }
                }
                TextView reminder = root.findViewById(R.id.widget_hint);
                if (reminder.getVisibility() == View.VISIBLE && !reminder.getText().toString().equals(hint)) {
                    issues.add(tag + " hint shows '" + reminder.getText() + "'");
                }
            }
        }
        RuntimeEnvironment.setFontScale(1f);
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /**
     * With {@link CardModel#keepWordsWhole} (the plan widget) names and venues stay whole at every
     * size and font scale, while the drawn text reads the same (a 「 · 」 it wraps at becomes the line
     * break, and no line ends with the dot).
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
                    RenderSupport.collectDotLineEnds(root, tag, issues);
                    TextView title = root.findViewById(R.id.widget_task);
                    assertEquals(tag, card[0], RenderSupport.titleText(title));
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

    /**
     * The answers row (DESIGN §18): one paragraph per line, a meeting that may not rank up muted, and
     * the width ladder's forms best first, each shorter than the one before.
     */
    @Test
    public void theAnswersRowMutesTheLinesOfAMeetingThatMayNotRankUp() {
        Context c = RuntimeEnvironment.getApplication();
        CardModel m = sample(false);
        m.keepWordsWhole = true;
        m.answers.add(new CardModel.AnswerLine("要是升级了，答：", "升级时答：", java.util.Arrays.asList("我会帮你的", "这样的规矩没有意义"),
                null, true));
        m.answers.add(new CardModel.AnswerLine("课堂：", null, java.util.Collections.singletonList("马（第 2 项）"), null, false));
        View root = RenderSupport.layout(c, CardRenderer.render(c, m, 320, 240), 320, 240);
        TextView row = root.findViewById(R.id.widget_dialogue_choices);
        assertEquals(View.VISIBLE, row.getVisibility());
        String drawn = row.getText().toString();
        assertTrue(CardText.plain(drawn), CardText.plain(drawn).matches("(要是升级了，|升级时)答：我会帮你的 / 这样的规矩没有意义\n课堂：马（第 2 项）"));
        android.text.Spanned spanned = (android.text.Spanned) row.getText();
        android.text.style.ForegroundColorSpan[] muted = spanned.getSpans(0, spanned.length(), android.text.style.ForegroundColorSpan.class);
        assertEquals(1, muted.length);
        assertEquals(0, spanned.getSpanStart(muted[0]));
        assertEquals("only the meeting's line", drawn.indexOf(CardText.ANSWER_LINE_BREAK), spanned.getSpanEnd(muted[0]));
        assertEquals(CardRenderer.MUTED_ANSWER_ALPHA, muted[0].getForegroundColor() >>> 24);
        assertEquals("the watermark steps aside", View.GONE, root.findViewById(R.id.widget_watermark).getVisibility());

        List<CardRenderer.AnswerForm> forms = CardRenderer.answerForms(c, m.answers, 180 - 2 * CardRenderer.bodyPaddingDp(WidgetSize.TINY));
        assertTrue(forms.size() > 1);
        for (int i = 1; i < forms.size(); i++) assertTrue("form " + i, forms.get(i).heightDp < forms.get(i - 1).heightDp);
        // Lines go the least needed first: a meeting that may not rank up gives way to the class answer
        // (certain, and missed for good); with a certain own line the class answer goes first.
        String shortest = CardText.plain(forms.get(forms.size() - 1).text);
        assertEquals("the shortest keeps the class answer", "课堂：马", shortest);
        List<CardModel.AnswerLine> certainOwn = java.util.Arrays.asList(
                new CardModel.AnswerLine("答：", null, java.util.Arrays.asList("我会帮你的", "这样的规矩没有意义"), null, false),
                m.answers.get(1));
        List<CardRenderer.AnswerForm> own = CardRenderer.answerForms(c, certainOwn, 180 - 2 * CardRenderer.bodyPaddingDp(WidgetSize.TINY));
        assertTrue(CardText.plain(own.get(own.size() - 1).text).startsWith("答：我会帮你的"));
        assertTrue(CardRenderer.answerForms(c, new ArrayList<>(), 160).isEmpty());
    }

    /**
     * Spec §1.4.4 and the 3.1.0 review: neither the answers nor the title is ever hidden. On a 130dp card
     * at 115% font, where the first choice 「我也去看看吧或者我在这里等她」 is wider than the line, the title
     * keeps one line and the row an answer to every prompt, the first of the two alternatives (「答：我也去看看吧
     * / 真担心啊」; 3.1.0 said 「答：共 2 项，点开看看」); a taller card keeps both whole. With a 课堂 answer
     * the card keeps both kinds (docs/DESIGN_v3.md §18; 3.1.0 showed 「课堂：卡巴拉」 alone): the class
     * answer on the title's line beside the longest title form that fits there, the meeting's answers below.
     */
    @Test
    public void theTitleKeepsOneLineBesideAnswersThatNeedTheRoom() {
        Context c = RuntimeEnvironment.getApplication();
        CardModel m = sample(false);
        m.keepWordsWhole = true;
        m.title = "教皇 · 北村文吉、光子";
        m.shortTitles.addAll(StepCopy.shortTitles(m.title, null));
        m.answers.add(new CardModel.AnswerLine("答：", null, java.util.Arrays.asList("我也去看看吧或者我在这里等她", "真担心啊"), null, false));
        RuntimeEnvironment.setFontScale(1.15f);
        try {
            View small = RenderSupport.layout(c, CardRenderer.render(c, m, 180, 130), 180, 130);
            List<String> issues = new ArrayList<>();
            RenderSupport.collectHiddenTitle(small, "small", issues);
            RenderSupport.collectTextIssues(small, "small", issues);
            assertTrue(String.join("\n", issues), issues.isEmpty());
            TextView title = small.findViewById(R.id.widget_task);
            assertEquals("教皇 · 北村文吉、光子", RenderSupport.titleText(title));
            assertEquals(1, title.getLineCount());
            TextView row = small.findViewById(R.id.widget_dialogue_choices);
            assertEquals(View.VISIBLE, row.getVisibility());
            assertEquals("答：我也去看看吧 / 真担心啊", CardText.plain(row.getText().toString()));
            assertEquals(View.GONE, small.findViewById(R.id.widget_meta_row).getVisibility());
            assertEquals(View.GONE, small.findViewById(R.id.widget_title_aside).getVisibility());
            // With a class answer both kinds stay: the class answer beside the title, which takes the
            // longest form that fits there, and the meeting's answers below.
            CardModel withClass = m.copy();
            withClass.answers.add(new CardModel.AnswerLine("课堂：", null,
                    java.util.Collections.singletonList("卡巴拉" + CardModel.AnswerLine.position(3)), null, false));
            View classCard = RenderSupport.layout(c, CardRenderer.render(c, withClass, 180, 130), 180, 130);
            RenderSupport.collectHiddenTitle(classCard, "class", issues);
            RenderSupport.collectTextIssues(classCard, "class", issues);
            RenderSupport.collectCutText(classCard, "class", issues);
            assertTrue(String.join("\n", issues), issues.isEmpty());
            TextView aside = classCard.findViewById(R.id.widget_title_aside);
            assertEquals(View.VISIBLE, aside.getVisibility());
            assertEquals("课堂：卡巴拉", CardText.plain(aside.getText().toString()));
            assertEquals("北村文吉", RenderSupport.titleText(classCard.findViewById(R.id.widget_task)));
            TextView classRow = classCard.findViewById(R.id.widget_dialogue_choices);
            assertEquals(1, classRow.getLineCount());
            assertTrue(CardText.plain(classRow.getText().toString()), CardText.plain(classRow.getText().toString()).startsWith("答：我也去看看吧"));
            View tall = RenderSupport.layout(c, CardRenderer.render(c, m, 180, 150), 180, 150);
            assertEquals(View.VISIBLE, tall.findViewById(R.id.widget_title_row).getVisibility());
            assertTrue(CardText.plain(((TextView) tall.findViewById(R.id.widget_dialogue_choices)).getText().toString())
                    .startsWith("答：我也去看看吧或"));
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    /**
     * A class answer goes on the line of a short meeting answer before the row takes another line
     * (「答：好的 / 没那回事 · 课堂：变为零」), and only the meeting's part of such a line is muted when the
     * meeting may not rank up. Every form up to the ones that lose a kind keeps both kinds.
     */
    @Test
    public void aClassAnswerSharesTheLineOfAShortMeetingAnswer() {
        Context c = RuntimeEnvironment.getApplication();
        List<CardModel.AnswerLine> lines = java.util.Arrays.asList(
                new CardModel.AnswerLine("要是升级了，答：", "升级时答：", java.util.Arrays.asList("好的", "没那回事"), null, true),
                new CardModel.AnswerLine("课堂：", null, java.util.Collections.singletonList("变为零" + CardModel.AnswerLine.position(2)),
                        null, false));
        List<CardRenderer.AnswerForm> forms = CardRenderer.answerForms(c, lines, 250 - 2 * CardRenderer.bodyPaddingDp(WidgetSize.COMPACT));
        CardRenderer.AnswerForm merged = null;
        for (CardRenderer.AnswerForm form : forms) {
            if (merged == null && form.lines == 1 && CardText.plain(form.text).contains("课堂")) merged = form;
        }
        assertTrue("a one-line form with both", merged != null);
        String text = merged.text;
        assertEquals("升级时答：好的 / 没那回事 · 课堂：变为零", CardText.plain(text));
        assertTrue(merged.whole);
        assertTrue(merged.both);
        assertEquals("the meeting's part is muted", 2, merged.muted.length);
        assertEquals(0, merged.muted[0]);
        assertEquals("升级时答：好的 / 没那回事", CardText.plain(text.substring(merged.muted[0], merged.muted[1])));
        boolean lost = false;
        for (CardRenderer.AnswerForm form : forms) {
            if (!form.both) lost = true;
            else assertTrue("no form that keeps both kinds after one that loses one: " + CardText.plain(form.text), !lost);
        }
        // Drawn: the muted span stops before the class answer.
        CardModel m = sample(false);
        m.keepWordsWhole = true;
        m.answers.addAll(lines);
        View root = RenderSupport.layout(c, CardRenderer.render(c, m, 250, 130), 250, 130);
        TextView row = root.findViewById(R.id.widget_dialogue_choices);
        String drawn = CardText.plain(row.getText().toString());
        assertTrue(drawn, drawn.contains("课堂：变为零") && drawn.contains("没那回事"));
        android.text.Spanned spanned = (android.text.Spanned) row.getText();
        for (android.text.style.ForegroundColorSpan span : spanned.getSpans(0, spanned.length(), android.text.style.ForegroundColorSpan.class)) {
            String part = CardText.plain(spanned.subSequence(spanned.getSpanStart(span), spanned.getSpanEnd(span)).toString());
            assertTrue(part, !part.contains("课堂"));
        }
    }

    /**
     * A title too long for the one line a 130dp card leaves it takes its longest short form that fits
     * that line whole: the person beside the note mark, the first of two people, then the arcana.
     */
    @Test
    public void aTitleWithOneLineTakesItsLongestFormThatFits() {
        Context c = RuntimeEnvironment.getApplication();
        CardModel m = sample(false);
        m.keepWordsWhole = true;
        m.title = "教皇 · 北村文吉、光子";
        m.shortTitles.addAll(StepCopy.shortTitles(m.title, null));
        assertEquals("教皇 · 北村文吉、光子", CardRenderer.oneLineTitle(c, m, 160));
        assertEquals("北村文吉、光子", CardRenderer.oneLineTitle(c, m, 100));
        assertEquals("北村文吉", CardRenderer.oneLineTitle(c, m, 60));
        assertEquals("教皇", CardRenderer.oneLineTitle(c, m, 30));

        CardModel note = sample(false);
        note.keepWordsWhole = true;
        note.title = "结缘网站的字条 · 岳羽由加莉";
        note.titleIcon = CardModel.TitleIcon.NOTE;
        note.shortTitles.addAll(StepCopy.shortTitles(note.title, null));
        note.answers.add(new CardModel.AnswerLine("课堂：", null, java.util.Collections.singletonList("马" + CardModel.AnswerLine.position(2)),
                null, false));
        RuntimeEnvironment.setFontScale(1.15f);
        try {
            View root = RenderSupport.layout(c, CardRenderer.render(c, note, 180, 130), 180, 130);
            List<String> issues = new ArrayList<>();
            RenderSupport.collectHiddenTitle(root, "note", issues);
            RenderSupport.collectTextIssues(root, "note", issues);
            RenderSupport.collectCutText(root, "note", issues);
            assertTrue(String.join("\n", issues), issues.isEmpty());
            TextView title = root.findViewById(R.id.widget_task);
            assertEquals("岳羽由加莉", RenderSupport.titleText(title));
            assertEquals(View.VISIBLE, root.findViewById(R.id.widget_title_icon).getVisibility());
            assertEquals("课堂：马（第 2 项）", CardText.plain(((TextView) root.findViewById(R.id.widget_dialogue_choices)).getText().toString()));
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    /**
     * A class answer whose 「（第 n 项）」 makes it wrap gives up the position before any choice is cut
     * to 「等 N 项」, and a 180x150 card keeps every choice of both lines (as on the real pack's
     * new-game plan, where the position used to take the class line with it).
     */
    @Test
    public void aClassAnswerGivesUpItsPositionBeforeAnyChoice() {
        Context c = RuntimeEnvironment.getApplication();
        CardModel m = sample(false);
        m.keepWordsWhole = true;
        m.title = "倒悬者 · 舞子";
        m.answers.add(new CardModel.AnswerLine("答：", null, java.util.Arrays.asList("一定会有的", "好啊"), null, false));
        m.answers.add(new CardModel.AnswerLine("课堂：", null,
                java.util.Collections.singletonList("布拉瓦茨基夫人" + CardModel.AnswerLine.position(2)), null, false));
        assertEquals("课堂：布拉瓦茨基夫人", m.answers.get(1).withoutPosition().text());
        assertSame("a line without a position stays as it is", m.answers.get(0), m.answers.get(0).withoutPosition());

        List<String> forms = new ArrayList<>();
        for (CardRenderer.AnswerForm form : CardRenderer.answerForms(c, m.answers, 150)) {
            forms.add(CardText.plain(form.text).replaceAll(" *\u00A0*/ *", " / "));
        }
        int bare = forms.indexOf("答：一定会有的 / 好啊\n课堂：布拉瓦茨基夫人");
        assertTrue(String.join(" | ", forms), bare > 0);
        for (int i = 0; i < bare; i++) assertTrue(forms.get(i), forms.get(i).contains("（第 2 项）"));

        View root = RenderSupport.layout(c, CardRenderer.render(c, m, 180, 150), 180, 150);
        TextView row = root.findViewById(R.id.widget_dialogue_choices);
        assertEquals(View.VISIBLE, row.getVisibility());
        String drawn = CardText.plain(row.getText().toString());
        assertTrue(drawn, drawn.matches("答：一定会有的 */ *好啊\n课堂：布拉瓦茨基夫人(（第 2 项）)?"));
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
