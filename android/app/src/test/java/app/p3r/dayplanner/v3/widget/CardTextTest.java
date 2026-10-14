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

    /** Review: 「结缘网站的字条 · / 山岸风花」: a line never ends in 「·」. */
    @Test
    public void namesBetweenSeparatorsStayWholeAndNoLineEndsWithTheDot() {
        String shaped = CardText.title("皇帝 · 小田桐秀利");
        assertEquals("皇" + WJ + "帝" + " ·" + NBSP + "小" + WJ + "田" + WJ + "桐" + WJ + "秀" + WJ + "利", shaped);
        assertEquals("皇帝 · 小田桐秀利", CardText.plain(shaped));
        assertEquals("one break opportunity: before the dot", 1, breakableSpaces(shaped));
        // A space beside a number does not glue the dot to it: 「RANK2」/「· 友近」 may still wrap.
        assertTrue(CardText.title("魔术师→RANK2 · 友近").contains("2 ·" + NBSP + "友"));
        assertTrue(CardText.keepNumbersTogether("RANK 2 · 友近").endsWith("2 · 友近"));
        // The 12 characters of a name do not count the separator's dot.
        String twelve = CardText.title("带魔术师面具和友近对话吧 · RANK6");
        assertTrue(twelve.startsWith("带" + WJ + "魔") && twelve.contains("话" + WJ + "吧 ·"));
    }

    /**
     * Where the drawn title wraps at a separator, the separator becomes a line break and the dot goes:
     * 「结缘网站的字条」/「山岸风花」. Other separators and breaks stay as they are.
     */
    @Test
    public void aTitleThatWrapsAtTheSeparatorDropsTheDot() {
        String shaped = CardText.title("结缘网站的字条 · 山岸风花");
        int lineEnd = shaped.indexOf(" ·") + 1; // the line breaks after the separator's first space
        String broken = CardText.breakAtSeparators(shaped, new int[]{lineEnd, shaped.length()});
        assertEquals("结缘网站的字条" + CardText.TITLE_BREAK + "山岸风花", CardText.plain(broken));
        assertEquals("结缘网站的字条 · 山岸风花", CardText.plainTitle(broken));
        // An unshaped title may break after the whole separator.
        assertEquals("皇帝\n小田桐秀利", CardText.breakAtSeparators("皇帝 · 小田桐秀利", new int[]{5}));
        assertEquals("皇帝\n小田桐秀利", CardText.breakAtSeparators("皇帝 · 小田桐秀利", new int[]{3}));
        // A break elsewhere leaves the separators alone.
        String three = CardText.title("课堂 · 赫尔墨斯文集（3） · 学力");
        String once = CardText.breakAtSeparators(three, new int[]{three.lastIndexOf(" ·") + 1});
        assertEquals("课堂 · 赫尔墨斯文集（3）\n学力", CardText.plain(once));
        assertEquals(three, CardText.breakAtSeparators(three, new int[]{three.indexOf("（")}));
        assertEquals(three, CardText.breakAtSeparators(three, new int[0]));
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

    /** The answers row (DESIGN §18): one line per answers line, labels, choices and notes whole. */
    @Test
    public void answerLinesKeepLabelsChoicesAndNotesWhole() {
        CardModel.AnswerLine own = new CardModel.AnswerLine("要是升级了，答：", "升级时答：",
                Arrays.asList("我也去看看吧或者我在这里等她", "真担心啊"), "别选表白", true);
        CardModel.AnswerLine side = new CardModel.AnswerLine("顺便选：", null, Collections.singletonList("有话想和你说"), null, false);
        String shaped = CardText.answers(Arrays.asList(own, side), false, false, false, Integer.MAX_VALUE);
        assertEquals("要是升级了，答：我也去看看吧或者我在这里等她 / 真担心啊 · 别选表白\n顺便选：有话想和你说", CardText.plain(shaped));
        assertTrue("a line starts with its marked break", shaped.contains(CardText.ANSWER_LINE_BREAK + "顺"));
        assertTrue("the label stays whole", shaped.startsWith("要" + WJ + "是"));
        assertTrue("a choice stays whole", shaped.contains("她" + NBSP + "/ 真" + WJ + "担"));
        assertTrue("the note goes with its dot", shaped.contains("啊 ·" + NBSP + "别" + WJ + "选"));
        // Ladder: the short label, 「或」, the first choices plus 「等 N 项」.
        String narrow = CardText.answers(Collections.singletonList(own), true, true, false, 1);
        assertEquals("升级时答：我也去看看吧或我在这里等她 等 2 项 · 别选表白", CardText.plain(narrow));
        assertTrue("「等 2 项」 stays whole", narrow.contains("等" + NBSP + "2" + NBSP + "项"));
        // Loose: a long choice may wrap after its 「或」 (each side whole while short); a short one stays whole.
        String loose = CardText.answers(Collections.singletonList(own), true, true, true, Integer.MAX_VALUE);
        assertEquals("升级时答：我也去看看吧或我在这里等她 / 真担心啊 · 别选表白", CardText.plain(loose));
        assertTrue("a break after 「或」", loose.contains("或我") && !loose.contains("或" + WJ + "我"));
        assertTrue("「真担心啊」 whole", loose.contains("真" + WJ + "担" + WJ + "心" + WJ + "啊"));
        assertEquals("或", CardText.shortOr("或" + WJ + "者"));
        assertEquals("A或B", CardText.shortOr("A或者B"));
    }

    /** A note that a drawn line starts with goes there without its 「·」. */
    @Test
    public void aNoteStartingALineDropsItsDot() {
        CardModel.AnswerLine own = new CardModel.AnswerLine("答：", null, Collections.singletonList("随便问吧"), "别选「产生恋爱感情了」", false);
        String shaped = CardText.answers(Collections.singletonList(own), false, false, false, Integer.MAX_VALUE);
        int note = shaped.indexOf("·");
        String broken = CardText.breakBeforeNotes(shaped, new int[]{0, note});
        assertEquals("答：随便问吧\n别选「产生恋爱感情了」", CardText.plain(broken));
        assertFalse(broken.contains(CardText.ANSWER_LINE_BREAK));
        assertEquals("a line that does not start there is left alone", shaped, CardText.breakBeforeNotes(shaped, new int[]{0, 3}));
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

    @Test
    public void looseChoicesNeverLeaveOneCharacterAloneAndWrapAfterAlternatives() {
        // Re-audit render: 「我也去看看吧或者我在这里等」/「她 / 任意 / …」 on the 180x160 card.
        String shaped = CardText.choicesLoose("我也去看看吧或者我在这里等她 / 任意 / 真担心啊（3）");
        assertEquals("我也去看看吧或者我在这里等她 / 任意 / 真担心啊（3）", CardText.plain(shaped));
        assertTrue("each alternative stays whole", shaped.contains("我" + WJ + "也" + WJ + "去" + WJ + "看" + WJ + "看" + WJ
                + "吧" + WJ + "或" + WJ + "者我" + WJ + "在" + WJ + "这" + WJ + "里" + WJ + "等" + WJ + "她"));
        assertTrue("its count stays with the last character", shaped.contains("啊" + WJ + "（" + WJ + "3" + WJ + "）"));
        String question = CardText.choicesLoose("你说得对 / 不是或者什么意思？");
        assertTrue(question.contains("意" + WJ + "思" + WJ + "？"));
        assertTrue(question.contains("对" + NBSP + "/ "));
        String longer = CardText.choicesLoose("不去看汉堡堆成的山并继续吃");
        assertTrue("the last two characters stay together", longer.endsWith("继续" + WJ + "吃"));
        assertTrue("so do the first three (never 「… / 不」 then 「去看…」)", longer.startsWith("不" + WJ + "去" + WJ + "看"));
        assertFalse("but the rest may wrap", longer.contains("看" + WJ + "汉"));
        // A long choice before 「或者」: its first characters stay together too (隐者 RANK 5).
        assertTrue(CardText.choicesLoose("大~~蠢~~蛋~~！ / Ｙ子是教醉拳的代理师傅？或者Ｙ子是老师？").contains("Ｙ" + WJ + "子" + WJ + "是"));
        // A class answer's position never breaks at its spaces.
        String position = CardText.choicesLoose("布拉瓦茨基夫人（第 2 项）");
        assertFalse(position, position.contains(" "));
        assertTrue(position.contains("（" + WJ + "第" + NBSP + "2" + NBSP + "项" + WJ + "）"));
        assertEquals(shaped, CardText.choicesLoose(shaped));
    }

    /** Review: 「魅力 +4 → Lv.…」 — a chip keeps every value whole and wraps only after a 「、」. */
    @Test
    public void chipsWrapOnlyBetweenWholeValues() {
        String chip = CardText.chip("魅力 +4 → Lv.2、学力 +2 → MAX");
        assertEquals("魅力 +4 → Lv.2、学力 +2 → MAX", CardText.plain(chip));
        assertFalse("no plain space left to break at", chip.contains(" "));
        assertTrue("the break is after 「、」", chip.contains("2、学"));
        assertTrue(chip.contains("力" + NBSP + "+" + WJ + "4"));
        assertEquals("RANK 4→5", CardText.plain(CardText.chip("RANK 4→5")));
        assertEquals(chip, CardText.chip(chip));
        assertNull(CardText.chip(null));
    }

    /** Review: 「奢豪之…」 and 「奢豪之庭 茨尔阿（145–…」: shorter whole forms, the least important part first. */
    @Test
    public void locationsHaveShorterWholeForms() {
        assertEquals(Arrays.asList("奢豪之庭 茨尔阿（145–172F）", "奢豪之庭 茨尔阿", "茨尔阿"),
                CardText.locationForms("奢豪之庭 茨尔阿（145–172F）"));
        assertEquals(Arrays.asList("桐叶购物中心 · 夏卡尔咖啡店", "夏卡尔咖啡店"), CardText.locationForms("桐叶购物中心 · 夏卡尔咖啡店"));
        assertEquals("a space next to a number belongs to the place",
                Arrays.asList("桐叶购物中心 · 放纵俱乐部 2 楼", "放纵俱乐部 2 楼"), CardText.locationForms("桐叶购物中心 · 放纵俱乐部 2 楼"));
        assertEquals(Arrays.asList("月光馆学园 3楼 学生会室", "学生会室"), CardText.locationForms("月光馆学园 3楼 学生会室"));
        assertEquals("each leg of a route", Arrays.asList("学园 · 1楼教职员室前 → 港湾人工岛 · 站前广场", "1楼教职员室前 → 站前广场"),
                CardText.locationForms("学园 · 1楼教职员室前 → 港湾人工岛 · 站前广场"));
        assertEquals(Arrays.asList("长鸣神社"), CardText.locationForms("长鸣神社"));
        assertTrue(CardText.locationForms(null).isEmpty());
    }

    /**
     * Review: the legacy card showed 「／3楼、严户台、港湾人工岛站自动贩卖机」, the 「宿舍2楼」 of
     * 「宿舍2楼／3楼、…」 shortened away. No form starts or ends with a separator; 「宿舍2楼 / 3楼」 stays
     * together, and a list of places 「A；B」 may shorten to its last place.
     */
    @Test
    public void shortenedLocationsNeverStartOrEndWithASeparator() {
        assertEquals(Arrays.asList("桐叶购物中心 · 天鹅绒房间；宿舍2楼 / 3楼、严户台、港湾人工岛站自动贩卖机",
                        "天鹅绒房间；宿舍2楼 / 3楼、严户台、港湾人工岛站自动贩卖机", "宿舍2楼 / 3楼、严户台、港湾人工岛站自动贩卖机"),
                CardText.locationForms("桐叶购物中心 · 天鹅绒房间；宿舍2楼 / 3楼、严户台、港湾人工岛站自动贩卖机"));
        assertEquals("alternatives of two places may lose the first",
                Arrays.asList("学园 · 2楼教室外 / 运动社团附近", "2楼教室外 / 运动社团附近", "运动社团附近"),
                CardText.locationForms("学园 · 2楼教室外 / 运动社团附近"));
        assertEquals(Arrays.asList("桐叶购物中心 · 天鹅绒房间入口；宿舍1楼大厅", "天鹅绒房间入口；宿舍1楼大厅", "宿舍1楼大厅"),
                CardText.locationForms("桐叶购物中心 · 天鹅绒房间入口；宿舍1楼大厅"));
        for (String location : Arrays.asList("宿舍2楼 / 3楼", "学校 / 城镇", "宿舍 · 共用区域 / 天台", "港湾人工岛 · 站前广场角落 / 咖啡店门口 → 严户台商店街")) {
            for (String form : CardText.locationForms(location)) {
                assertFalse(form, form.matches("^[／/·、].*|.*[／/·、]$"));
            }
        }
    }

    /**
     * Review: the note line of the 250-wide card ended in 「花费 1,000 日…」 and 「赴约也算一次见…」. A
     * one-line note shortens to whole clauses (never a clause that only leads into the next one).
     */
    @Test
    public void notesShortenToWholeClauses() {
        assertEquals(Arrays.asList("对方发来了休息日邀约，赴约也算一次见面", "对方发来了休息日邀约"),
                CardText.clauseForms("对方发来了休息日邀约，赴约也算一次见面"));
        assertEquals(Arrays.asList("去喷泉广场找那个胖乎乎的学生 · 花费 1,000 日元", "去喷泉广场找那个胖乎乎的学生"),
                CardText.clauseForms("去喷泉广场找那个胖乎乎的学生 · 花费 1,000 日元"));
        assertEquals("a condition never stands alone", Arrays.asList("白色的花交给顺平的话，这天会有剧情"),
                CardText.clauseForms("白色的花交给顺平的话，这天会有剧情"));
        assertEquals(Arrays.asList("填好进度后，每天点「完成」就行"), CardText.clauseForms("填好进度后，每天点「完成」就行"));
        assertEquals(Arrays.asList("舞子失踪了，11月5日前没救出的话，倒悬者社群就无法继续", "舞子失踪了"),
                CardText.clauseForms("舞子失踪了，11月5日前没救出的话，倒悬者社群就无法继续"));
        assertEquals("never inside quotes", Arrays.asList("选「好，走吧」"), CardText.clauseForms("选「好，走吧」"));
        assertEquals(Arrays.asList("这段时间不能自由行动"), CardText.clauseForms("这段时间不能自由行动"));
        assertEquals("shaped text reads plain", "对方发来了休息日邀约",
                CardText.clauseForms(CardText.sentence("对方发来了休息日邀约，赴约也算一次见面")).get(1));
        assertTrue(CardText.clauseForms(null).isEmpty());
    }

    /** Plain spaces left in the text: the only places a line may break besides opening brackets. */
    private static int breakableSpaces(String s) {
        int n = 0;
        for (char ch : s.toCharArray()) if (ch == ' ') n++;
        return n;
    }
}
