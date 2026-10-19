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
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * The independent design review of the app screens: words never split across lines, no lone last
 * character, no line ending with a " · " separator, the 社群 card footer and story tag, the
 * Tartarus sheet, the stats strip's floor label, the hero's 「做不了…」, number spacing and the
 * load-failed copy; then the second review: no line ending with 「/」, even gaps around the arrow
 * of 「RANK 6 → 7」, the en dash of ranges, RANK/MAX/Lv.N in the numeral face, even episode chips,
 * the 「顶层 117F」 chip, styled bullets in the detail sheet and the route labels. Every render also
 * runs {@link Shots#checkLines}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DesignReviewTest extends UiTestBase {

    /** A text view of the screens, {@code widthDp} wide, measured, laid out and drawn. */
    private static TextView drawn(CharSequence text, float sp, boolean bold, int widthDp) {
        return drawn(Ui.text(RuntimeEnvironment.getApplication(), text, sp, Color.WHITE, bold), widthDp);
    }

    /** {@code t}, {@code widthDp} wide, measured, laid out and drawn. */
    private static TextView drawn(TextView t, int widthDp) {
        Context c = t.getContext();
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
        assertTrue(Shots.has(cannot, "怎么没做成？"));
        assertTrue(Shots.has(cannot, ResultActivity.OTHER_INSTEAD));
        HomeActivity a = home();
        assertTrue(Shots.has(a, TodayPage.CANNOT_LABEL));
        for (String label : new String[]{"怎么没做成？", ResultActivity.OTHER_INSTEAD, ResultActivity.OTHER_RESULTS,
            TodayPage.CANNOT_LABEL}) {
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
        TextView story = (TextView) Shots.find(a, Shots.has(a, LinksPage.STORY_TAG) ? LinksPage.STORY_TAG : LinksPage.STORY_TAG_SHORT);
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
        assertEquals(where, 1, story.getLineCount());
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
            String plain = Zh.plain(t.getText());
            assertFalse(plain, plain.contains("本区块第"));
            if (plain.contains("第 2 次")) {
                visits++;
            }
        }
        assertEquals("the visit once, as the 「第 2 次 · 最多 4 次」 chip", 1, visits);
        assertTrue(Shots.has(a, "第 2 次 · 最多 4 次"));
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
            if (Zh.plain(t.getText()).contains(" · 去过 ")) {
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
        String retry = "重试一下？不行就重开应用。";
        // The title already says the records were not read: the body only offers what to do.
        assertEquals(retry, Messages.loadFailed("读取记录失败了，请重新打开应用"));
        assertEquals("游戏资料读取失败。" + retry, Messages.loadFailed("游戏资料读取失败，请重新打开应用"));
        assertEquals("生成安排时出了问题。" + retry, Messages.loadFailed("生成安排时出了问题，请稍后再试"));
        assertEquals(retry, Messages.loadFailed(null));
        assertEquals(retry, Messages.loadFailed("java.io.IOException"));
    }

    // ================================================================== second review (3.0.2)

    /** Columns of the drawn text of {@code t} (its layout only, no background) that hold ink. */
    private static boolean[] ink(TextView t) {
        Layout layout = t.getLayout();
        Bitmap b = Bitmap.createBitmap(Math.max(1, layout.getWidth()), Math.max(1, layout.getHeight()), Bitmap.Config.ARGB_8888);
        t.getPaint().setColor(Color.WHITE);
        layout.draw(new Canvas(b));
        boolean[] out = new boolean[b.getWidth()];
        for (int x = 0; x < b.getWidth(); x++) {
            for (int y = 0; y < b.getHeight() && !out[x]; y++) {
                out[x] = Color.alpha(b.getPixel(x, y)) > 96;
            }
        }
        return out;
    }

    /** The visible gap (blank columns) around the space at {@code space} of a one-line text. */
    private static int gap(TextView t, boolean[] ink, int space) {
        assertEquals(' ', t.getText().charAt(space));
        Layout layout = t.getLayout();
        int mid = Math.round((layout.getPrimaryHorizontal(space) + layout.getPrimaryHorizontal(space + 1)) / 2);
        int left = mid;
        while (left > 0 && !ink[left - 1]) {
            left--;
        }
        int right = mid;
        while (right < ink.length && !ink[right]) {
            right++;
        }
        return right - left;
    }

    // ------------------------------------------------------------------ 12 「/」 starts the next line

    private static final String ANSWERS = "回答：我会帮你的 / 这样的规矩没有意义";

    @Test
    public void noLineEndsWithASlash() {
        Zh.useNames(api.rules);
        boolean seen = false;
        // From the width that holds 「/ 这样的规矩没有意义」 (a narrower one has to split the answer).
        for (int w = 160; w <= 340; w += 4) {
            TextView t = drawn(ANSWERS, 15, false, w);
            Shots.checkLines(t, ANSWERS + " @" + w);
            List<String> shown = lines(t);
            if (shown.size() > 1 && shown.get(0).equals("回答：我会帮你的")) {
                seen = true;
                assertEquals(shown.toString(), "/ 这样的规矩没有意义", shown.get(1));
            }
        }
        assertTrue("some width wraps at the separator", seen);
        String both = "不加社群点数，但能拿到道具/特性；勾选后会在可看的日子留出时段。";
        for (int w = 100; w <= 340; w += 4) {
            Shots.checkLines(drawn(both, 13, false, w), both + " @" + w);
        }
        // Shaped once: it reads the same, and setting it again changes nothing.
        TextView t = drawn(ANSWERS, 15, false, 200);
        assertEquals(ANSWERS, Zh.plain(t.getText()));
        String once = t.getText().toString();
        assertTrue(once, once.contains(Zh.glue("我会帮你的") + " " + Zh.BREAK + "/" + Zh.WJ + " " + Zh.WJ + "这"));
        assertEquals(once, Ui.text(t.getContext(), once, 15, Color.WHITE, false).getText().toString());
        assertEquals("10/22", ZhTextView.separators("10/22").toString());
    }

    @Test
    @Config(fontScale = 1.3f)
    public void theAnswerSeparatorStartsTheNextLineAtLargeFont() {
        HomeActivity a = home();
        Shots.capture(a, false);
        List<String> shown = lines((TextView) Shots.find(a, ANSWERS));
        assertEquals(Arrays.asList("回答：我会帮你的", "/ 这样的规矩没有意义"), shown);
        ResultActivity r = result(Fixtures.SLOT_NOW, "meet:emperor", Intents.MODE_RESULT);
        AuditFixesTest.assertNoBreakInsideKeptText(r, "rank-up sheet");
    }

    // ------------------------------------------------------------------ 13 「RANK 6 → 7」

    @Test
    public void arrowsHaveTheSameGapOnBothSides() {
        Context c = RuntimeEnvironment.getApplication();
        assertEquals("RANK 6 → 7", Zh.rankStep(6, 7));
        assertEquals("RANK 3 → MAX", Zh.rankStep(3, 10));
        for (int link : new int[]{2, 4}) {
            String chip = LinkStatus.of(api.rules, api.snap.profile, api.snap.observed, api.snap.plan, link).chipZh;
            assertTrue(chip, chip.matches("\\S+ \\S+ → \\S+.*"));
        }
        // The hero (20 sp), the 社群 cards (15 sp) and plain text, which sets a step in the
        // numeral face too. Before, the arrow sat close to the number after it ("6 →7").
        List<TextView> views = new ArrayList<>();
        for (String step : new String[]{"RANK 6 → 7", "RANK 3 → MAX", "RANK 9 → MAX", "RANK 1 → 2", "RANK 7 → 8", "皇帝 6 → MAX"}) {
            views.add(Ui.mixed(c, step, 20, Color.WHITE, true));
            views.add(Ui.mixed(c, step, 15, Color.WHITE, true));
        }
        views.add(Ui.text(c, "皇帝 RANK 6 → 7", 13, Color.WHITE, false));
        for (TextView t : views) {
            drawn(t, 300);
            Spanned text = (Spanned) t.getText();
            int arrow = text.toString().indexOf('→');
            assertEquals(Zh.plain(text), 1, text.getSpans(arrow, arrow + 1, NumeralSpan.Arrow.class).length);
            boolean[] ink = ink(t);
            int before = gap(t, ink, arrow - 1);
            int after = gap(t, ink, arrow + 1);
            assertTrue(Zh.plain(text) + ": " + before + " px before the arrow, " + after + " after",
                Math.abs(before - after) <= Ui.dp(c, 1) && after >= Ui.dp(c, 3));
        }
    }

    // ------------------------------------------------------------------ 14 ranges: 「–」

    @Test
    public void rangesUseTheTextFacesEnDash() {
        Context c = RuntimeEnvironment.getApplication();
        assertEquals("5月12日–1月29日", Zh.dateRange(Fixtures.day(5, 12), Fixtures.day(1, 29)));
        for (String range : new String[]{"提齐亚（69–117F）· 救出舞子", "奇颜之庭 阿尔卡（23–43F）", "145–172F"}) {
            Spanned styled = (Spanned) Ui.latin(c, range);
            int dash = styled.toString().indexOf('–');
            assertEquals(range, 0, styled.getSpans(dash, dash + 1, NumeralSpan.class).length);
            assertEquals(range, 1, styled.getSpans(dash - 1, dash, NumeralSpan.class).length);
            assertEquals(range, 1, styled.getSpans(dash + 1, dash + 2, NumeralSpan.class).length);
        }
    }

    // ------------------------------------------------------------------ 15 RANK / MAX / Lv.N

    @Test
    public void ranksAndLevelsAreInTheNumeralFaceInEveryText() {
        Context c = RuntimeEnvironment.getApplication();
        String[][] cases = {
            {"想把哪些社群升到 MAX，分「想要」和「必须」。", "MAX"},
            {"日期、现金、个人能力值和社群 RANK，点选就行。", "RANK"},
            {"魅力升到 MAX 后，社群点数 ×1.51。", "MAX", "×1.51"},
            {"学力到 Lv.3 以后", "Lv.3"},
            {"皇帝 RANK 6 → 7", "RANK 6 → 7"},
            {"恶魔 未解锁 → RANK 7", "RANK 7"},
        };
        for (String[] k : cases) {
            TextView t = Ui.muted(c, k[0]);
            Shots.checkNumeralWords(t, k[0]);
            Spanned text = (Spanned) t.getText();
            List<String> styled = new ArrayList<>();
            for (NumeralSpan span : text.getSpans(0, text.length(), NumeralSpan.class)) {
                styled.add(Zh.plain(text.subSequence(text.getSpanStart(span), text.getSpanEnd(span))));
            }
            assertEquals(k[0], Arrays.asList(k).subList(1, k.length), styled);
        }
        assertFalse(Ui.muted(c, "MAXIMUM").getText() instanceof Spanned);
        // A view in the numeral face as a whole needs no spans (the stats strip's "Lv.4").
        CharSequence lv = Ui.numeral(c, "Lv.4", 24, Color.WHITE).getText();
        assertTrue(!(lv instanceof Spanned) || ((Spanned) lv).getSpans(0, lv.length(), NumeralSpan.class).length == 0);
        // The welcome page and the settings (every render also runs Shots#checkNumeralWords).
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        HomeActivity a = home();
        Shots.capture(a, false);
        for (TextView t : AuditFixesTest.textViews(a.getWindow().getDecorView())) {
            Shots.checkNumeralWords(t, "welcome");
        }
    }

    // ------------------------------------------------------------------ 16 episode chips

    private void episodeChipsShareOneWidth(String where) {
        Context c = RuntimeEnvironment.getApplication();
        for (Rules.Episode track : api.rules.episodes) {
            FlowLayout chips = LinksPage.watchedChips(c, track, 0, n -> { });
            int width = Ui.dp(c, 320);
            chips.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            chips.layout(0, 0, width, chips.getMeasuredHeight());
            View first = chips.getChildAt(0);
            int perLine = 0;
            for (int i = 0; i < chips.getChildCount(); i++) {
                TextView chip = (TextView) chips.getChildAt(i);
                String label = where + " " + Zh.plain(chip.getText());
                assertEquals(label, first.getWidth(), chip.getWidth());
                assertEquals(label, first.getHeight(), chip.getHeight());
                assertEquals(label, 1, chip.getLineCount());
                perLine += chip.getTop() == 0 ? 1 : 0;
            }
            // The first line is filled edge to edge, and the lines are as even as can be.
            int count = chips.getChildCount();
            assertTrue(where, width - chips.getChildAt(perLine - 1).getRight() < perLine);
            int lines = (count + perLine - 1) / perLine;
            assertEquals(where + ": " + count + " chips on " + lines + " lines", (count + lines - 1) / lines, perLine);
            // 「1 话」 is spaced like 「2 话」: the narrow "1" has a full digit's box (tabular
            // figures), so it is never the tighter one, as it was.
            int[] gaps = new int[2];
            for (int n = 1; n <= 2; n++) {
                TextView chip = (TextView) chips.getChildAt(n);
                gaps[n - 1] = gap(chip, ink(chip), chip.getText().toString().lastIndexOf(' '));
            }
            assertTrue(where + ": 1 话 " + gaps[0] + " px, 2 话 " + gaps[1] + " px",
                gaps[0] >= gaps[1] && gaps[0] - gaps[1] <= Ui.dp(c, 1.5f));
        }
    }

    @Test
    public void episodeChipsShareOneWidth() {
        episodeChipsShareOneWidth("chips");
    }

    @Test
    @Config(fontScale = 1.3f)
    public void episodeChipsShareOneWidthAtLargeFont() {
        episodeChipsShareOneWidth("chips_font130");
    }

    // ------------------------------------------------------------------ 17 「顶层 117F」

    private void topChipMatchesTheStepButtons(String where) {
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        api.snap.observed.slot = slot;
        ResultActivity a = result(slot, "tower:3", Intents.MODE_RESULT);
        Shots.capture(a, false);
        View top = Shots.find(a, "顶层 117F");
        int[] at = new int[2];
        top.getLocationInWindow(at);
        for (String button : new String[]{"减少", "增加"}) {
            View step = Shots.find(a, button);
            int[] stepAt = new int[2];
            step.getLocationInWindow(stepAt);
            assertEquals(where + " " + button, step.getHeight(), top.getHeight());
            assertTrue(where + " " + button + " at " + stepAt[1] + ", chip at " + at[1], Math.abs(stepAt[1] - at[1]) <= 1);
        }
    }

    @Test
    public void topFloorChipMatchesTheStepButtons() {
        topChipMatchesTheStepButtons("tower");
    }

    @Test
    @Config(fontScale = 1.3f)
    public void topFloorChipMatchesTheStepButtonsAtLargeFont() {
        topChipMatchesTheStepButtons("tower_font130");
    }

    // ------------------------------------------------------------------ 18 estimates: styled bullets

    @Test
    public void detailSheetEstimatesUseTheStyledBullet() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_OPEN_SLOT, Fixtures.SLOT_NOW + 2);
        HomeActivity a = home(intent);
        assertTrue(a.sheets().isShowing());
        Shots.capture(a, false);
        int notes = 0;
        for (TextView t : AuditFixesTest.textViews(a.getWindow().getDecorView())) {
            String plain = Zh.plain(t.getText());
            assertFalse(plain, plain.startsWith("·"));
            if (!plain.startsWith("普通见面预计好感")) {
                continue;
            }
            notes++;
            ViewGroup row = (ViewGroup) t.getParent();
            View dot = row.getChildAt(0);
            assertTrue(dot.getBackground() instanceof SlantedPanelDrawable);
            assertEquals(Ui.dp(a, 6), dot.getWidth());
            // Centred on the first line.
            Layout layout = t.getLayout();
            int lineTop = t.getTop() + t.getPaddingTop() + layout.getLineTop(0);
            int lineHeight = layout.getLineBottom(0) - layout.getLineTop(0);
            int middle = dot.getTop() + dot.getHeight() / 2;
            assertTrue(middle + " in " + lineTop + "+" + lineHeight,
                middle > lineTop + lineHeight * 0.3 && middle < lineTop + lineHeight * 0.7);
        }
        assertTrue("the sheet lists the estimate", notes > 0);
    }

    // ------------------------------------------------------------------ 19 朋友 / 恋人

    private void routeSegmentsClearTheSlantedEdges(String where) {
        HomeActivity a = homeOn("links");
        Shots.capture(a, false);
        int segments = 0;
        for (TextView t : AuditFixesTest.textViews(a.getWindow().getDecorView())) {
            String plain = Zh.plain(t.getText());
            CharSequence description = t.getContentDescription();
            if (!(plain.equals("朋友") || plain.equals("恋人")) || description == null || !description.toString().contains("路线")) {
                continue;
            }
            segments++;
            assertTrue(where, t.getPaddingLeft() >= Ui.dp(a, 8) && t.getPaddingRight() >= Ui.dp(a, 8));
            float room = t.getWidth() - t.getPaddingLeft() - t.getPaddingRight();
            assertTrue(where + ": " + plain + " does not fit", t.getPaint().measureText(plain) <= room);
        }
        assertTrue(where, segments >= 2);
    }

    @Test
    public void routeSegmentsClearTheSlantedEdges() {
        routeSegmentsClearTheSlantedEdges("links");
    }

    @Test
    @Config(fontScale = 1.3f)
    public void routeSegmentsClearTheSlantedEdgesAtLargeFont() {
        routeSegmentsClearTheSlantedEdges("links_font130");
    }
}
