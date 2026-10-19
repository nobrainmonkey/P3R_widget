package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.DialogueHint;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 当日提醒 and the dialogue scene rule in the app (docs/DESIGN_v3.md §17.2, §18): the 「今天别错过」
 * block, the 「提醒 N」 marks of 接下来, the day sheet's 「当天提醒」 with the estimate caption, the 日程
 * day's gold 「提醒：…」 line and legend, and the hero's answers line. Synthetic reminders
 * ({@link ReminderFixtures}) until the engine's (WP-C) are in the pack.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RemindersUiTest extends UiTestBase {
    private static final int OCT_22 = Fixtures.OCT_22;
    private static final int NOW = Fixtures.SLOT_NOW;
    /** The lines after their chips (「课堂提问：」 and 「电视购物：」 are on the chips). */
    private static final String CLASS_LINE = "选「朱诺」（第 3 项）";
    private static final String TV_LINE = "玫瑰花束、高级巧克力 ×2，2,980 日元";
    private static final String RESCUE_LINE = "今晚去塔尔塔罗斯时，顺便救出失踪者（3 人）";
    private static final String NURSE_LINE = "放学后先去保健室：勇气 +2，不会消耗时间";

    /** 10/22: class answer, TV shopping (day) and a rescue tonight; 10/23: 保健室; 12/16: the JUNE exam. */
    private void remind() {
        ReminderFixtures.with(api.snap.plan,
                ReminderFixtures.classAnswer(OCT_22, "朱诺", 3),
                ReminderFixtures.tv(OCT_22),
                ReminderFixtures.rescueTonight(OCT_22),
                ReminderFixtures.nurse(OCT_22 + 1),
                ReminderFixtures.exam(Fixtures.day(12, 16), "JUNE", true));
    }

    /**
     * {@code reminders} as 今天别错过 rows, laid out at the phone width less the page's and the card's
     * padding, checked by the typography pass and saved (the block is below the fold of a screen shot).
     */
    private static void rows(HomeActivity a, String name, boolean detail, Plan.Reminder... reminders) {
        android.widget.LinearLayout box = Ui.column(a);
        box.setBackgroundColor(Ui.col(a, app.p3r.dayplanner.R.color.p3r_navy_950));
        Ui.pad(box, 16 + 14, 6, 16 + 12, 6);
        int chips = ReminderViews.chipColumn(a, Arrays.asList(reminders));
        for (Plan.Reminder r : reminders) box.addView(ReminderViews.row(a, r, detail, chips));
        android.graphics.Bitmap bitmap = Shots.render(box);
        // 3.1.0 re-check: the chips share the widest one's width, so every line starts on one column.
        int column = -1;
        for (int i = 0; i < box.getChildCount(); i++) {
            View text = ((android.view.ViewGroup) box.getChildAt(i)).getChildAt(1);
            int left = text.getLeft();
            assertTrue(name + ": line " + i + " starts at " + left + ", not " + column, column < 0 || left == column);
            column = left;
        }
        Shots.checkTypography(box, name);
        Shots.save(bitmap, name);
    }

    private HomeActivity homeOn(String tab) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, tab);
        return home(intent);
    }

    @Test
    public void todayListsTheRestOfTodaysRemindersUnderTheHero() {
        remind();
        HomeActivity a = home();
        assertTrue(Shots.has(a, ReminderViews.TODAY_TITLE));
        for (String line : new String[]{CLASS_LINE, TV_LINE, RESCUE_LINE}) assertTrue(line, Shots.has(a, line));
        assertTrue("the class answer says what it gives", Shots.has(a, "魅力 +2"));
        assertTrue("label chips", Shots.has(a, "课堂提问") && Shots.has(a, "电视购物") && Shots.has(a, "失踪者"));
        assertFalse("tomorrow's reminder waits for tomorrow", Shots.has(a, NURSE_LINE));
        String all = Shots.allText(a.getWindow().getDecorView());
        assertFalse("the chip says the label once", all.contains("课堂提问：") || all.contains("电视购物："));
        assertTrue("TalkBack reads the label once",
                Shots.has(a, "课堂提问，选「朱诺」（第 3 项），魅力 +2"));
        assertTrue("under the hero, before 接下来",
                all.indexOf("完成 ✓") < all.indexOf(ReminderViews.TODAY_TITLE) && all.indexOf(ReminderViews.TODAY_TITLE) < all.indexOf("接下来"));
        assertTrue("most important first", all.indexOf(CLASS_LINE) < all.indexOf(TV_LINE));
        Shots.shot(a, "home_today_reminders");

        // A row opens the day sheet of its slot, with every reminder of the day and the longer text.
        Shots.click(a, CLASS_LINE);
        assertTrue(Shots.has(a, ReminderViews.DAY_TITLE));
        assertTrue(Shots.has(a, "在自己房间看电视就能买，不会消耗时间；最好出门前先买。购买后到「同步现状」更新现金。"));
    }

    @Test
    public void theBlockIsHiddenWithoutRemindersAndOnceTheDaysAreDone() {
        HomeActivity plain = home();
        assertFalse(Shots.has(plain, ReminderViews.TODAY_TITLE));
        // Evening: the day's class answer and TV shopping are behind; tonight's rescue is not.
        remind();
        api.snap.observed.slot = NOW + 1;
        HomeActivity evening = home();
        assertTrue(Shots.has(evening, RESCUE_LINE));
        assertFalse(Shots.has(evening, CLASS_LINE));
        assertEquals(Arrays.asList(), ReminderViews.today(api.snap.plan, -1));
        assertEquals(1, ReminderViews.today(api.snap.plan, NOW + 1).size());
    }

    @Test
    public void upcomingRowsCarryAGoldReminderMark() {
        remind();
        HomeActivity a = home();
        // Tonight's rescue and tomorrow's 保健室: one each.
        assertEquals(2, Shots.findAll(a.getWindow().getDecorView(), "提醒 1").size());
        assertEquals(1, ReminderViews.countAt(api.snap.plan, NOW + 1));
        assertEquals(0, ReminderViews.countAt(api.snap.plan, NOW + 3));
        assertNull(ReminderViews.marker(a, 0));
        // Read out with the row (the row's own description is what TalkBack reads), never 「当日提醒」.
        assertEquals("提醒 2 条", ReminderViews.marker(a, 2).getContentDescription().toString());
        int counted = 0;
        for (View v : Shots.findAll(a.getWindow().getDecorView(), "提醒 1")) {
            View row = (View) v.getParent();
            CharSequence spoken = row.getContentDescription();
            assertTrue(String.valueOf(spoken), spoken != null && spoken.toString().endsWith("，提醒 1 条"));
            counted++;
        }
        assertEquals(2, counted);
    }

    @Test
    public void theDaySheetListsAnswersAndDetailsWithoutExtraSourceCaptions() {
        remind();
        HomeActivity a = home();
        int exam = Rules.slot(Fixtures.day(12, 16), false);
        a.openDetail(exam);
        Shots.idle();
        assertTrue(Shots.has(a, ReminderViews.DAY_TITLE));
        assertTrue(Shots.has(a, "选「JUNE」"));
        assertTrue(Shots.has(a, "选「JUNE」。"));
        // The exam answers 「JUNE」「MADRID」 ship verbatim (spec 3.1.0 decision O4): the typography pass
        // takes a quoted capitalised answer for what it is, not leaked English.
        Shots.shot(a, "day_sheet_reminders_estimate");
        // A day without reminders has no section.
        a.dismissSheet();
        Shots.idle();
        a.openDetail(Rules.slot(Fixtures.day(11, 3), false));
        Shots.idle();
        assertFalse(Shots.has(a, ReminderViews.DAY_TITLE));
        // Source metadata does not add a second footer to the answer.
        Plan.Reminder bare = ReminderFixtures.exam(Fixtures.day(12, 16), "JUNE", true);
        bare.detailZh = "这天考试有一道题要自己选，选「JUNE」。";
        String spoken = ReminderViews.row(a, bare, true).getContentDescription().toString();
        assertEquals("考试，选「JUNE」。" + bare.detailZh, spoken);
    }

    /**
     * 上课犯困 and 保健室 say what they give in the line itself (the widget shows the line alone): the row
     * does not repeat it beside the line, nor does TalkBack.
     */
    @Test
    public void aGainTheLineAlreadySaysIsNotRepeated() {
        ReminderFixtures.with(api.snap.plan, ReminderFixtures.doze(OCT_22), ReminderFixtures.nurse(OCT_22));
        HomeActivity a = home();
        assertTrue(Shots.has(a, "上课犯困时别闭眼：学力 +2"));
        assertTrue(Shots.has(a, NURSE_LINE));
        assertTrue("no separate 学力 +2",
                Shots.findAll(ReminderViews.row(a, ReminderFixtures.doze(OCT_22), false), "学力 +2").isEmpty());
        assertTrue("no separate 勇气 +2",
                Shots.findAll(ReminderViews.row(a, ReminderFixtures.nurse(OCT_22), true), "勇气 +2").isEmpty());
        assertEquals("a class answer still says it", 1,
                Shots.findAll(ReminderViews.row(a, ReminderFixtures.classAnswer(OCT_22, "朱诺", 3), false), "魅力 +2").size());
        assertTrue(Shots.has(a, "上课犯困，上课犯困时别闭眼：学力 +2"));
        assertTrue(Shots.has(a, "保健室，放学后先去保健室：勇气 +2，不会消耗时间"));
        rows(a, "reminder_rows", false, ReminderFixtures.classAnswer(OCT_22, "朱诺", 3), ReminderFixtures.doze(OCT_22),
                ReminderFixtures.nurse(OCT_22), ReminderFixtures.tv(OCT_22));
        assertNull(ReminderViews.gainOf(ReminderFixtures.doze(OCT_22)));
        assertEquals("魅力 +2", ReminderViews.gainOf(ReminderFixtures.classAnswer(OCT_22, "朱诺", 3)));
        assertEquals("选「朱诺」（第 3 项）", ReminderViews.lineOf(ReminderFixtures.classAnswer(OCT_22, "朱诺", 3)));
        assertEquals("今晚去塔尔塔罗斯时，顺便救出失踪者（3 人）", ReminderViews.lineOf(ReminderFixtures.rescueTonight(OCT_22)));
        // The chip says the kind; the line after it never says it again (「失踪者｜失踪者 7月6日截止…」).
        assertEquals("7月6日截止，这几天没安排去塔尔塔罗斯", ReminderViews.lineOf(ReminderFixtures.reminder("rescue.06-18.deadline",
                Plan.Reminder.RESCUE, "失踪者", OCT_22, false, "失踪者：7月6日截止，这几天没安排去塔尔塔罗斯", "失踪者 7月6日截止",
                Plan.Reminder.MISSABLE)));
        assertEquals("有新商品，3,980 日元", ReminderViews.lineOf(ReminderFixtures.reminder("tv.07-05", Plan.Reminder.TV, "电视购物",
                OCT_22, false, "电视购物：有新商品，3,980 日元", "电视购物 3,980 日元", Plan.Reminder.NICE)));
    }

    /**
     * At 130% font a long real class answer (10/10 「布拉瓦茨基夫人」, the second choice) stays whole on
     * 今天 and in the day sheet: the line has the whole width after the chip (Shots checks every break).
     */
    @Test
    @Config(fontScale = 1.3f)
    public void aLongAnswerStaysWholeAtALargeFont() {
        Plan.Reminder answer = ReminderFixtures.classAnswer(OCT_22, "布拉瓦茨基夫人", 2);
        ReminderFixtures.with(api.snap.plan, answer, ReminderFixtures.tv(OCT_22), ReminderFixtures.doze(OCT_22));
        HomeActivity a = home();
        assertTrue(Shots.has(a, "选「布拉瓦茨基夫人」（第 2 项）"));
        Shots.shot(a, "home_today_reminders_font130");
        rows(a, "reminder_rows_font130", false, answer, ReminderFixtures.tv(OCT_22), ReminderFixtures.doze(OCT_22),
                ReminderFixtures.nurse(OCT_22));
        rows(a, "reminder_rows_detail_font130", true, answer, ReminderFixtures.nurse(OCT_22));
        a.openDetail(answer.slot);
        Shots.idle();
        assertTrue(Shots.has(a, ReminderViews.DAY_TITLE));
        Shots.shot(a, "day_sheet_reminders_font130");
    }

    @Test
    public void theScheduleNamesTheDaysRemindersInGold() {
        remind();
        HomeActivity a = homeOn("schedule");
        // No legend: the gold chips are the day's reminders, read out as 「提醒：…」.
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("灰色时段"));
        assertTrue(Shots.has(a, "提醒：课堂提问 · 电视购物 · 失踪者"));
        assertTrue("one gold chip per label", Shots.has(a, "课堂提问") && Shots.has(a, "电视购物"));
        assertTrue(Shots.has(a, "提醒：保健室"));
        Shots.shot(a, "home_schedule_reminders");
        // At most three labels, then 「等」; each label once.
        Plan plan = ReminderFixtures.with(new Plan(), ReminderFixtures.classAnswer(OCT_22, "朱诺", 3),
                ReminderFixtures.tv(OCT_22), ReminderFixtures.doze(OCT_22), ReminderFixtures.fragment(OCT_22),
                ReminderFixtures.rescueTonight(OCT_22), ReminderFixtures.feature(OCT_22));
        assertEquals("提醒：课堂提问 · 上课犯困 · 新开放 等", ReminderViews.scheduleLine(plan, OCT_22));
        assertNull(ReminderViews.scheduleLine(plan, OCT_22 + 1));
    }

    /** spec 3.1.0 §1.4.1: a meeting that may not rank up shows the next scene's answers, not the last. */
    @Test
    public void theHeroShowsTheNextScenesAnswersForAMeetingThatMayNotRankUp() {
        Plan.Step maybe = Fixtures.social(api.rules, NOW, "emperor", 6, 6, false, false);
        api.snap.plan.steps.set(0, maybe);
        HomeActivity a = home();
        assertTrue(Shots.has(a, "要是升级了，答：我会帮你的 / 这样的规矩没有意义"));
        // The same rule on every surface: certain no-rank-up and holds show no answers at all.
        Plan.Step certain = Fixtures.social(api.rules, NOW, "emperor", 6, 6, false, true);
        assertNull(Dialogue.line(api.rules, api.snap.profile, certain));
        Plan.Step held = Fixtures.social(api.rules, NOW, "emperor", 6, 6, false, false);
        held.assumptions.add("rank_hold");
        assertNull(Dialogue.line(api.rules, api.snap.profile, held));
        // A rank-up reads exactly as in 3.0.2.
        assertEquals("回答：我会帮你的 / 这样的规矩没有意义", Dialogue.line(api.rules, api.snap.profile,
                Fixtures.social(api.rules, NOW, "emperor", 6, 7, true, true)));
    }

    /** The conditional line in a how-to is a note under the steps, like 「回答：…」, and is said once. */
    @Test
    public void theConditionalLineIsAHowToNote() {
        String how = "放学后到月光馆学园 · 学生会活动室找小田桐秀利\n" + DialogueHint.CONDITIONAL_PREFIX + "我会帮你的 / 这样的规矩没有意义";
        assertEquals(Arrays.asList("放学后到月光馆学园 · 学生会活动室找小田桐秀利"), StepViews.howToSteps(how));
        assertEquals(1, StepViews.howToNotes(how).size());
        Plan.Step maybe = Fixtures.social(api.rules, NOW, "emperor", 6, 6, false, false);
        maybe.detailZh = how;
        assertNull(Dialogue.besideHowTo(api.rules, api.snap.profile, maybe));
    }
}
