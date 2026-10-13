package app.p3r.dayplanner.v3.widget;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Line-break shaping of card text (audit: names split as 「小田桐/秀利」, 「（1）」 alone on a line).
 * Plain JVM: the shaping is string work; the render tests check the real line breaks.
 */
public class CardTextTest {
    private static final String WJ = String.valueOf(CardText.WJ);
    private static final String NBSP = String.valueOf(CardText.NBSP);

    @Test
    public void namesBetweenSeparatorsStayWholeAndTheDotEndsItsLine() {
        String shaped = CardText.title("皇帝 · 小田桐秀利");
        assertEquals("皇" + WJ + "帝" + NBSP + "· " + "小" + WJ + "田" + WJ + "桐" + WJ + "秀" + WJ + "利", shaped);
        assertEquals("皇帝 · 小田桐秀利", CardText.plain(shaped));
        assertEquals("one break opportunity: after the dot", 1, breakableSpaces(shaped));
    }

    @Test
    public void episodeTitleMayBreakOnlyBeforeTheBracket() {
        String shaped = CardText.title("羁绊剧情 · 伊织顺平（第 2 话）");
        assertEquals("羁绊剧情 · 伊织顺平（第 2 话）", CardText.plain(shaped));
        assertTrue(shaped.contains("伊" + WJ + "织" + WJ + "顺" + WJ + "平（"));
        assertTrue("「第 2 话」 never splits", shaped.contains("（" + WJ + "第" + NBSP + "2" + NBSP + "话" + WJ + "）"));
        assertFalse("no joiner before the opening bracket", shaped.contains(WJ + "（"));
    }

    @Test
    public void activityTitlesKeepVenuesAndItemsWhole() {
        String shaped = CardText.title("在夏卡尔咖啡店喝费洛蒙咖啡", CardText.locationWords("桐叶购物中心 · 夏卡尔咖啡店"));
        assertTrue(shaped.contains("夏" + WJ + "卡" + WJ + "尔" + WJ + "咖" + WJ + "啡" + WJ + "店"));
        assertTrue(shaped.contains("费" + WJ + "洛" + WJ + "蒙" + WJ + "咖" + WJ + "啡"));
        assertTrue("may wrap between the words", shaped.contains("在夏") && shaped.contains("店喝费"));
        String arcade = CardText.title("在电玩大巡游游玩「你就是答案」", CardText.locationWords("桐叶购物中心 · 电玩大巡游"));
        assertTrue(arcade.contains("「" + WJ + "你" + WJ + "就" + WJ + "是" + WJ + "答" + WJ + "案" + WJ + "」"));
        assertTrue(arcade.contains("游玩「"));
    }

    @Test
    public void shapingTwiceChangesNothing() {
        for (String title : Arrays.asList("皇帝 · 小田桐秀利", "羁绊剧情 · 伊织顺平（第 2 话）", "结缘网站的字条 · 山岸风花",
                "探索塔尔塔罗斯", "设置目标，从当前存档开始")) {
            String once = CardText.title(title, Collections.singletonList("塔尔塔罗斯"));
            assertEquals(title, once, CardText.title(once, Collections.singletonList("塔尔塔罗斯")));
        }
        String choices = CardText.choices("参考回答：我会帮你的 / 这样的规矩没有意义");
        assertEquals(choices, CardText.choices(choices));
    }

    @Test
    public void choicesStayWholeAndBreakOnlyAfterASlash() {
        String shaped = CardText.choices("发生什么了吗？（1） / 那也没办法（2） / 你说得对（1）");
        assertEquals("发生什么了吗？（1） / 那也没办法（2） / 你说得对（1）", CardText.plain(shaped));
        assertTrue("「（1）」 stays with its answer", shaped.contains("对" + WJ + "（" + WJ + "1" + WJ + "）"));
        assertTrue("the slash ends its line", shaped.contains("）" + NBSP + "/ 那"));
        assertEquals(2, breakableSpaces(shaped));
        String answer = CardText.choices("参考回答：我会帮你的 / 这样的规矩没有意义");
        assertTrue(answer.startsWith("参" + WJ + "考" + WJ + "回" + WJ + "答" + WJ + "：我"));
        assertTrue("「规矩」 never splits", answer.contains("规" + WJ + "矩"));
        assertEquals("参考回答：我会帮你的 / 这样的规矩没有意义", CardText.plain(answer));
        // Latin answers keep their inner space, unbreakable.
        String latin = CardText.choices("MAY BLUES（2）");
        assertEquals("MAY BLUES（2）", CardText.plain(latin));
        assertFalse(latin.contains(" "));
        assertTrue(latin.contains("Y" + NBSP + "B") && latin.contains("S" + WJ + "（"));
        assertNull(CardText.choices(null));
    }

    @Test
    public void locationWordsAreTheNamedParts() {
        assertEquals(Arrays.asList("桐叶购物中心", "夏卡尔咖啡店"), CardText.locationWords("桐叶购物中心 · 夏卡尔咖啡店"));
        assertEquals(Arrays.asList("学园", "教室", "田径社"), CardText.locationWords("学园 · 教室／田径社"));
        assertTrue(CardText.locationWords(null).isEmpty());
    }

    @Test
    public void numbersKeepTheirWords() {
        assertEquals("再交" + NBSP + "10,000" + NBSP + "日元", CardText.keepNumbersTogether("再交 10,000 日元"));
        assertEquals("皇帝 · 小田桐秀利", CardText.keepNumbersTogether("皇帝 · 小田桐秀利"));
    }

    /** Plain spaces left in the text: the only places a line may break besides opening brackets. */
    private static int breakableSpaces(String s) {
        int n = 0;
        for (char ch : s.toCharArray()) if (ch == ' ') n++;
        return n;
    }
}
