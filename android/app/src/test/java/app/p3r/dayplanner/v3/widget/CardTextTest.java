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

    @Test
    public void guideTextKeepsNamesPlacesAndNumbersWhole() {
        // Re-audit: the legacy guide card wrapped as 「交给伊莉莎/白」 and 「委托/11」.
        String text = "收集12种饮料交给伊莉莎白，接取委托11";
        String shaped = CardText.guide(text, CardText.locationWords("桐叶购物中心 · 天鹅绒房间"));
        assertEquals(text, CardText.plain(shaped));
        assertTrue(shaped.contains("伊" + WJ + "莉" + WJ + "莎" + WJ + "白"));
        assertTrue("「委托11」 stays whole", shaped.contains("委" + WJ + "托" + WJ + "1" + WJ + "1"));
        assertTrue("a number keeps its counter and the word before it", shaped.contains("集" + WJ + "1" + WJ + "2" + WJ + "种"));
        assertTrue("it may still wrap between words", shaped.contains("给伊") && shaped.contains("，接"));

        String velvet = CardText.guide("派出所旁小巷进入天鹅绒房间，确认人格面具合成功能", null);
        assertTrue(velvet.contains("天" + WJ + "鹅" + WJ + "绒" + WJ + "房" + WJ + "间"));
        assertTrue(velvet.contains("人" + WJ + "格" + WJ + "面" + WJ + "具"));

        String rank = CardText.guide("和教室的友近对话 · 魔术师→RANK2", CardText.locationWords("学园"));
        assertTrue("「魔术师→RANK2」 stays whole", rank.contains("魔" + WJ + "术" + WJ + "师" + WJ + "→" + WJ + "R"));
        assertTrue(rank.contains("话" + NBSP + "· 魔"));

        assertTrue("a name the dictionary splits stays whole",
                CardText.guide("和山岸风花一起看书，学力+♪", null).contains("山" + WJ + "岸" + WJ + "风" + WJ + "花"));
        assertTrue("「+♪」 stays with its stat", CardText.guide("和山岸风花一起看书，学力+♪", null).contains("力" + WJ + "+" + WJ + "♪"));
        assertTrue(CardText.guide("喷泉前与西装男对话，交1万日元（恶魔前置3）", null).contains("1" + WJ + "万" + WJ + "日" + WJ + "元"));
        assertTrue(CardText.guide("电视购物 · 名牌钱包+香水，6980日元", null).contains("6" + WJ + "9" + WJ + "8" + WJ + "0" + WJ + "日" + WJ + "元"));
        assertTrue("a short “…” name stays whole",
                CardText.guide("用公用电脑学习“山间补习班”，学力升级LV2", null).contains("“" + WJ + "山" + WJ + "间"));
        assertTrue(CardText.guide("探索塔，愚者LV2", null).contains("者" + WJ + "L" + WJ + "V" + WJ + "2"));
    }

    @Test
    public void aPlusBetweenTwoThingsToBuyStartsTheNextOne() {
        // Joined to both sides, 「汽水10个+莫乐蜜G20个，」 was one run wider than the small card, which the
        // line breaker then cut as 「同位素汽水」/「10个+…」. The 「+」 goes with the next item instead.
        String shaped = CardText.guide("电视购物 · 同位素汽水10个+莫乐蜜G20个，3980日元", null);
        assertTrue(shaped.contains("个+" + WJ + "莫"));
        assertFalse("a line may break before the 「+」", shaped.contains("个" + WJ + "+"));
        assertTrue("the count stays with its item", shaped.contains("水" + WJ + "1" + WJ + "0" + WJ + "个"));
        assertTrue(CardText.guide("电视购物 · 名牌钱包+香水，6980日元", null).contains("包+" + WJ + "香"));
        // A stat bonus keeps its 「+」 (「学力+♪」, 「勇气+2」, a 「+」 at the end).
        assertTrue(CardText.guide("看书，学力+♪", null).contains("力" + WJ + "+" + WJ + "♪"));
        assertTrue(CardText.guide("吃饭，勇气+2", null).contains("气" + WJ + "+" + WJ + "2"));
        assertTrue(CardText.guide("吃饭，勇气+", null).endsWith("气" + WJ + "+"));
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
        assertFalse("but the rest may wrap", longer.startsWith("不" + WJ));
        assertEquals(shaped, CardText.choicesLoose(shaped));
    }

    /**
     * Review of the 320x200 guide card: 「解锁战车；邀 / 结子同行」, 「外出；未 / 解锁可延后」,
     * 「教室对面 / 的学生会活动室」, 「再带星 / 面具找早濑护」, 「可以SL / 不同的人物」.
     */
    @Test
    public void guideTextPrefersToBreakAfterClausePunctuation() {
        String invite = CardText.guide("操场加入田径社，解锁战车；邀结子同行", null);
        assertTrue("a lone 「邀」 goes with what follows", invite.contains("；邀" + WJ + "结"));
        assertFalse("the line may still break after 「；」", invite.contains("；" + WJ));
        assertTrue(CardText.guide("接77号委托，带伊莉莎白外出；未解锁可延后", null).contains("；未" + WJ + "解"));
        assertTrue("「的」 never starts a line", CardText.guide("然后去教室对面的学生会活动室", null).contains("面" + WJ + "的"));
        assertTrue("…nor after a closing quote", CardText.guide("使用“严户台留言板”的字条", null).contains("”" + WJ + "的"));
        String mask = CardText.guide("进教职员室，再带星面具找早濑护", null);
        assertTrue("「星面具」 is one word", mask.contains("星" + WJ + "面" + WJ + "具"));
        assertTrue(CardText.guide("带倒悬者面具，找舞子", null).contains("者" + WJ + "面"));
        assertTrue("a Latin word keeps the word after it",
                CardText.guide("提前存档，第二天夏日祭可以SL不同的人物", null).contains("S" + WJ + "L" + WJ + "不"));
        assertTrue(CardText.guide("满月BOSS战", null).contains("S" + WJ + "战"));
        assertTrue("…but a single letter belongs to the name before it", CardText.guide("带莫乐蜜G到长鸣神社", null).contains("G到"));
        assertTrue("a place word stays with the word before it", CardText.guide("去教室对面的活动室", null).contains("室" + WJ + "对"));
        assertTrue(CardText.guide("找教室外的贝培", null).contains("室" + WJ + "外" + WJ + "的"));
        assertTrue("a one-character verb keeps its object", CardText.guide("再到大厅邀经理同行", null).contains("邀" + WJ + "经"));
        assertFalse("the guide's typo is gone from its word list", GuideWords.WORDS.contains("铜条美鹤"));
        assertTrue("a rank keeps its own rule", CardText.guide("魔术师→RANK2，再看书", null).contains("2，"));
        // Two characters already a word after the punctuation need nothing more.
        assertTrue(CardText.guide("交给伊莉莎白，接取委托11", null).contains("，接"));
    }

    @Test
    public void aLongQuotedNameStaysWholeWhereTheCardSaysItFits() {
        String text = "种卡加土豆，再使用“历史学投稿站的字条”";
        assertTrue("9 characters: wraps between its words by default", CardText.guide(text, null).contains("学投"));
        String wide = CardText.guide(text, null, 13);
        assertTrue(wide.contains("“" + WJ + "历") && wide.contains("学" + WJ + "投") && wide.contains("条" + WJ + "”"));
        assertEquals(text, CardText.plain(wide));
        assertEquals(wide, CardText.guide(wide, null, 13));
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

    @Test
    public void guideShapingTwiceChangesNothing() {
        for (String text : Arrays.asList("收集12种饮料交给伊莉莎白，接取委托11", "和教室的友近对话 · 魔术师→RANK2",
                "10/13 期中考试：选择「成瘾症状（Addiction）」", "课堂 · 鲤鱼旗（3）。魅力+♪", "考试 · 选择 May Blues",
                "桐叶购物中心 · 天鹅绒房间；宿舍2楼 / 3楼、严户台")) {
            String once = CardText.guide(text, CardText.locationWords("桐叶购物中心 · 天鹅绒房间"));
            assertEquals(text, CardText.plain(once));
            assertEquals(text, once, CardText.guide(once, CardText.locationWords("桐叶购物中心 · 天鹅绒房间")));
        }
        assertNull(CardText.guide(null, null));
    }

    /** Plain spaces left in the text: the only places a line may break besides opening brackets. */
    private static int breakableSpaces(String s) {
        int n = 0;
        for (char ch : s.toCharArray()) if (ch == ' ') n++;
        return n;
    }
}
