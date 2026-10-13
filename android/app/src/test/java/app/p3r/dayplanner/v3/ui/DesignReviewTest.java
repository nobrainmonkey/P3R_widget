package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Layout;
import android.text.Spanned;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * The independent design review of the app screens: words never split across lines, no lone last
 * character, no line ending with a " · " separator, the 社群 card footer and story tag, the
 * Tartarus sheet, the stats strip's floor label, the hero's 「做不了…」, number spacing and the
 * load-failed copy. Every render also runs {@link Shots#checkLines}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DesignReviewTest extends UiTestBase {

    /** A text view of the screens, {@code widthDp} wide, measured, laid out and drawn. */
    private static TextView drawn(CharSequence text, float sp, boolean bold, int widthDp) {
        Context c = RuntimeEnvironment.getApplication();
        TextView t = Ui.text(c, text, sp, Color.WHITE, bold);
        int width = Ui.dp(c, widthDp);
        t.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        t.layout(0, 0, width, t.getMeasuredHeight());
        t.draw(new Canvas(Bitmap.createBitmap(width, Math.max(1, t.getMeasuredHeight()), Bitmap.Config.ARGB_8888)));
        return t;
    }

    /** The lines of a drawn text view, joiners removed and trimmed. */
    private static List<String> lines(TextView t) {
        Layout layout = t.getLayout();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < layout.getLineCount(); i++) {
            out.add(Zh.plain(t.getText().subSequence(layout.getLineStart(i), layout.getLineEnd(i))).trim());
        }
        return out;
    }

    /** True when the last separator dot of line {@code line} is not drawn. */
    private static boolean dotHidden(TextView t, int line) {
        Spanned text = (Spanned) t.getText();
        Layout layout = t.getLayout();
        ZhTextView.Dot last = null;
        for (ZhTextView.Dot dot : text.getSpans(layout.getLineStart(line), layout.getLineEnd(line), ZhTextView.Dot.class)) {
            int at = text.getSpanStart(dot);
            if (at >= layout.getLineStart(line) && (last == null || at > text.getSpanStart(last))) {
                last = dot;
            }
        }
        return last != null && last.hidden;
    }

    private HomeActivity homeOn(String tab) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, tab);
        return home(intent);
    }

    // ------------------------------------------------------------------ 1 words kept whole

    @Test
    public void wordsTheDictionaryMissesAreKeptWhole() {
        Zh.useNames(api.rules);
        String[][] cases = {
            {"10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元", "余额"},
            {"数字字体：Barlow Condensed（SIL 开放字体许可证）。", "许可证"},
            {"日期、现金、个人能力值和社群 RANK，点选就行。", "点选"},
            {"这次的安排里没排到 MAX，先保证了女皇", "没排到"},
            {"这次的安排里没排到 MAX，先保证了女皇", "先保证了"},
            {"刚才有新的记录（比如在桌面小组件上点了完成），已经合并进来", "小组件"},
            {"刚才有新的记录（比如在桌面小组件上点了完成），已经合并进来", "完成"},
            {"暂时不安排解锁", "安排"},
            {"1月31日之前的时段都记录完了，辛苦了！", "时段"},
        };
        for (String[] c : cases) {
            String kept = Zh.keepTogether(c[0]).toString();
            assertEquals(c[0], Zh.plain(kept));
            assertTrue(c[1] + " in " + kept, kept.contains(Zh.glue(c[1])));
            assertEquals("idempotent", kept, Zh.keepTogether(kept).toString());
        }
        // A name is a word of its own, not glued to the single characters after it: a narrow
        // column can still break between 「放纵俱乐部」 and 「买」.
        String note = Zh.keepTogether("在桐叶购物中心的放纵俱乐部买结缘网站的字条").toString();
        assertTrue(note, note.contains(Zh.glue("放纵俱乐部") + "买"));
        // Text set on a view later (a notice, an error) is shaped the same way.
        TextView later = Ui.text(RuntimeEnvironment.getApplication(), "", 14, Color.WHITE, true);
        later.setText("刚才有新的记录（比如在桌面小组件上点了完成）");
        assertTrue(later.getText().toString().contains(Zh.glue("完成")));
    }

    @Test
    public void shapedTextNeverBreaksInsideAWord() {
        Zh.useNames(api.rules);
        String[] texts = {
            "10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元",
            "刚才有新的记录（比如在桌面小组件上点了完成），已经合并进来，你填的内容都还在，请再确认一次。",
            "数字字体：Barlow Condensed（SIL 开放字体许可证）。",
            "日期、现金、个人能力值和社群 RANK，点选就行。",
            "在桐叶购物中心的放纵俱乐部买结缘网站的字条，当晚回宿舍用公用电脑给一个已解锁的社群加好感，不会直接升级。",
        };
        for (String text : texts) {
            for (int w = 120; w <= 340; w += 6) {
                Shots.checkLines(drawn(text, 15, false, w), text + " @" + w + "dp");
            }
        }
    }

    // ------------------------------------------------------------------ 2 no lone last character

    @Test
    public void theLastLineIsNeverALoneCharacter() {
        String question = "这段时间基本照着安排玩了吗？";
        assertTrue(Zh.keepTogether(question).toString().endsWith(Zh.glue("玩了吗") + "？"));
        String licence = Zh.keepTogether("数字字体：Barlow Condensed（SIL 开放字体许可证）。").toString();
        assertTrue(licence, licence.endsWith(Zh.glue("许可证") + "）。"));
        // 13.5 characters wide: 「吗？」 used to be left alone on the second line.
        TextView title = drawn(question, 22, true, 297);
        assertEquals(lines(title).toString(), "玩了吗？", lines(title).get(lines(title).size() - 1));
        // The real page.
        StateActivity a = stateController("sync").get();
        Shots.capture(a, false);
        TextView h1 = (TextView) Shots.find(a, question);
        List<String> shown = lines(h1);
        assertTrue(shown.toString(), shown.size() == 1 || shown.get(shown.size() - 1).length() >= 4);
    }

    // ------------------------------------------------------------------ 3 full-width punctuation

    @Test
    public void questionsAndEllipsesAreFullWidth() {
        ResultActivity cannot = result(Fixtures.SLOT_NOW, "meet:emperor", Intents.MODE_CANNOT);
        assertTrue(Shots.has(cannot, "这个时段没能照安排做？"));
        assertTrue(Shots.has(cannot, "改做别的…"));
        HomeActivity a = home();
        assertTrue(Shots.has(a, TodayPage.CANNOT_LABEL));
        for (String label : new String[]{"这个时段没能照安排做？", "改做别的…", TodayPage.CANNOT_LABEL}) {
            assertFalse(label, label.matches(".*[?!].*|.*\\.\\.\\..*"));
        }
    }

    // ------------------------------------------------------------------ 4 社群 card footer

    @Test
    public void missedGoalFooterIsTwoPlainClauses() {
        String foot = LinkStatus.shortfall("MAX", 5, 9, "女皇");
        assertEquals("这次的安排里没排到 MAX，先保证了女皇", foot);
        assertEquals("这次的安排里排到 RANK 9，没排到 MAX", LinkStatus.shortfall("MAX", 5, 9, null));
        // Any card width: no line ends with an opening bracket or inside 「没排到」.
        for (int w = 90; w <= 200; w += 4) {
            TextView t = drawn(foot, 12, false, w);
            Shots.checkLines(t, foot + " @" + w + "dp");
            for (String line : lines(t)) {
                assertFalse(line, line.endsWith("（") || line.startsWith("，"));
            }
        }
    }

    // ------------------------------------------------------------------ 5 story tag inset

    private static int rightInset(View badge) {
        View card = (View) badge.getParent().getParent().getParent();
        int x = 0;
        for (View v = badge; v != card; v = (View) v.getParent()) {
            x += v.getLeft();
        }
        return card.getWidth() - (x + badge.getWidth());
    }

    private void storyTagInset(String where) {
        HomeActivity a = homeOn("links");
        Shots.capture(a, false);
        TextView story = (TextView) Shots.find(a, LinksPage.STORY_TAG);
        View tier = null;
        for (String label : new String[]{"未选", "想要", "必须"}) {
            if (Shots.has(a, label)) {
                tier = Shots.find(a, label);
                break;
            }
        }
        assertNotNull(tier);
        // The tier chip is centred in 64 dp, so a wider chip at a large font sits a dp or two closer.
        int tolerance = Ui.dp(a, 2);
        assertTrue(where + ": story tag inset " + rightInset(story) + " vs tier chip " + rightInset(tier),
            Math.abs(rightInset(story) - rightInset(tier)) <= tolerance);
        assertTrue(where, story.getLineCount() <= 2);
        Shots.checkLines(story, where);
    }

    @Test
    public void storyTagIsInsetLikeTheTierChip() {
        storyTagInset("links");
    }

    @Test
    @Config(fontScale = 1.3f)
    public void storyTagIsInsetLikeTheTierChipAtLargeFont() {
        storyTagInset("links_font130");
    }

    // ------------------------------------------------------------------ 6 no line ends with 「·」

    /** Every width where {@code text} wraps right after a separator: the dot is not drawn there. */
    private static void separatorWraps(String text, float sp, boolean bold, String next) {
        boolean seen = false;
        for (int w = 100; w <= 340; w += 4) {
            TextView t = drawn(text, sp, bold, w);
            List<String> shown = lines(t);
            for (int i = 0; i < shown.size(); i++) {
                assertFalse(text + " @" + w + ": " + shown, shown.get(i).startsWith("·"));
                if (i < shown.size() - 1 && shown.get(i).endsWith("·")) {
                    seen = true;
                    assertTrue(text + " @" + w + ": " + shown, dotHidden(t, i));
                    assertTrue(text + " @" + w + ": " + shown, shown.get(i + 1).startsWith(next));
                }
            }
            if (shown.size() == 1) {
                assertFalse("a dot inside a line is drawn", dotHidden(t, 0));
            }
        }
        assertTrue("some width wraps at the dot: " + text, seen);
    }

    @Test
    public void noLineEndsWithASeparatorDot() {
        Zh.useNames(api.rules);
        separatorWraps("RANK 4 · 见面（预计不升级）", 20, true, "见面");
        separatorWraps("奇颜之庭 阿尔卡（23–43F）· 本区块第 2 次", 13, false, "本区块第");
        separatorWraps("10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元", 15, true, "");
        TextView hero = drawn("RANK 4 · 见面（预计不升级）", 20, true, 200);
        assertEquals("RANK 4 ·", lines(hero).get(0));
        assertTrue("the dot ending the line is not drawn", dotHidden(hero, 0));
        assertEquals("见面（预计不升级）", lines(hero).get(1));
    }

    // ------------------------------------------------------------------ 7 Tartarus sheet

    @Test
    public void towerSheetShowsTheVisitOnceAndAMutedHint() {
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        api.snap.observed.slot = slot;
        Plan.Step step = api.snap.plan.at(slot);
        step.subtitleZh = "奇颜之庭 阿尔卡（23–43F）· 本区块第 2 次";
        ResultActivity a = result(slot, "tower:3", Intents.MODE_RESULT);
        Shots.capture(a, false);
        int visits = 0;
        for (TextView t : AuditFixesTest.textViews(a.getWindow().getDecorView())) {
            if (Zh.plain(t.getText()).contains("本区块第")) {
                visits++;
            }
        }
        assertEquals("「本区块第 N 次」 once", 1, visits);
        assertTrue(Shots.has(a, "奇颜之庭 阿尔卡（23–43F）"));
        assertEquals("提齐亚（69–117F）· 救出舞子", ResultActivity.withoutVisit("提齐亚（69–117F）· 本区块第 1 次 · 救出舞子"));
        assertEquals("阿尔卡", ResultActivity.withoutVisit("阿尔卡 · 本区块第 1 次"));
        // The empty amount reads as a hint, not a value.
        TextView amount = (TextView) Shots.find(a, TowerPanel.PLACEHOLDER);
        assertEquals(Ui.col(a, R.color.p3r_ink_muted), amount.getCurrentTextColor());
        assertFalse(amount.getTypeface() != null && amount.getTypeface().isBold());
        a.towerPanel().setAmountForTest(25_050);
        assertEquals(Ui.col(a, R.color.p3r_white), amount.getCurrentTextColor());
        assertTrue(amount.getTypeface().isBold());
        Shots.shot(a, "review_result_tower_visit_once");
    }

    // ------------------------------------------------------------------ 8 + 9 Today

    @Test
    public void todayLabelsTheFloorAndOffersOneCannotButton() {
        HomeActivity a = home();
        assertTrue(Shots.has(a, TodayPage.FLOOR_LABEL));
        assertTrue(Shots.has(a, TodayPage.FLOOR_LABEL + " " + api.snap.observed.floor + "F"));
        assertTrue(Shots.has(a, "做不了…"));
        assertFalse(Shots.has(a, "做不了/改做别的"));
        Shots.click(a, "做不了…");
        Intent next = org.robolectric.Shadows.shadowOf(a).getNextStartedActivity();
        assertEquals(Intents.MODE_CANNOT, next.getStringExtra(Intents.EXTRA_MODE));
    }

    @Test
    @Config(fontScale = 1.3f)
    public void floorLabelLeavesRoomForTheTowerLineAtLargeFont() {
        HomeActivity a = home();
        Shots.capture(a, false);
        TextView tower = null;
        for (TextView t : AuditFixesTest.textViews(a.getWindow().getDecorView())) {
            if (Zh.plain(t.getText()).contains("本区块去过")) {
                tower = t;
            }
        }
        assertNotNull(tower);
        Layout layout = tower.getLayout();
        for (int i = 0; i < layout.getLineCount(); i++) {
            assertEquals(Zh.plain(tower.getText()), 0, layout.getEllipsisCount(i));
        }
        assertTrue(Shots.has(a, TodayPage.FLOOR_LABEL + " " + api.snap.observed.floor + "F"));
    }

    // ------------------------------------------------------------------ 10 number spacing

    @Test
    public void numbersAreSpacedLikeTheGlossary() {
        Plan plan = api.snap.plan;
        plan.summary.maxSlot[4] = -1;
        plan.summary.endRanks[4] = 10;
        assertEquals("预计 1月31日前升到 MAX",
            LinkStatus.of(api.rules, api.snap.profile, api.snap.observed, plan, 4).footZh);
        HomeActivity s = homeOn("schedule");
        String all = Shots.allText(s.getWindow().getDecorView());
        assertTrue(all, all.contains("排到 1月31日 · 共 "));
        // The space after a number stays in the text face, so 「1 个时段」 does not look like 「1个时段」.
        CharSequence styled = NumeralSpan.styleLatinRuns(Zh.keepTogether("共 179 件事 · 2 个时段"),
            Ui.numerals(s), 1.14f);
        Spanned spans = (Spanned) styled;
        for (NumeralSpan span : spans.getSpans(0, spans.length(), NumeralSpan.class)) {
            char last = styled.charAt(spans.getSpanEnd(span) - 1);
            assertTrue("numeral run ends on a digit: «" + last + "»", Character.isDigit(last));
        }
    }

    // ------------------------------------------------------------------ 11 load failed

    @Test
    public void loadFailedCopyMatchesTheRetryButton() {
        String retry = "点「重试」再试一次，还不行就重新打开应用。";
        assertEquals("读取记录失败了。" + retry, Messages.loadFailed("读取记录失败了，请重新打开应用"));
        assertEquals("游戏资料读取失败。" + retry, Messages.loadFailed("游戏资料读取失败，请重新打开应用"));
        assertEquals("生成安排时出了问题。" + retry, Messages.loadFailed("生成安排时出了问题，请稍后再试"));
        assertEquals("读取记录失败了。" + retry, Messages.loadFailed(null));
        assertEquals("读取记录失败了。" + retry, Messages.loadFailed("java.io.IOException"));
    }
}
