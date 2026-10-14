package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Assume;
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
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;
import app.p3r.dayplanner.v3.ui.ReminderFixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The widget's 当日提醒 (spec 3.1.0 §1.2.7, docs/DESIGN_v3.md §17.2): the reminder row shows the most
 * important one whole, else its short form, else nothing (never "…"), with 「 · 共 N 条」 when the
 * card carries more; every reminder is read out; class and exam answers go into the answers row, on
 * the daytime card and on their own exam-day card. Renders go to $p3r.qa.dir/v3/widget/reminders.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReminderRowRenderTest {
    private static final int ID = 43;
    private static final int S = FakePlannerApi.FIRST_SLOT;
    private static final int NOV_1 = Rules.dayOf(S);
    private static final int NOV_2 = NOV_1 + 1;
    private static final String TV = "电视购物：玫瑰花束、高级巧克力 ×2，2,980 日元";
    private Context app;
    private FakePlannerApi fake;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        fake = new FakePlannerApi();
        fake.rules = FakePlannerApi.miniRules();
        WidgetApi.setOverrideForTest(fake);
        WidgetWorker.setForTest(Runnable::run, (task, delay) -> { });
    }

    @After
    public void tearDown() {
        WidgetApi.setOverrideForTest(null);
        WidgetWorker.setForTest(null, null);
        RuntimeEnvironment.setFontScale(1f);
    }

    private CardModel model() {
        PlanWidget.Loaded loaded = PlanWidget.load(app, ID);
        return loaded.view.toCard(loaded.rules);
    }

    private View render(int width, int height) {
        PlanWidget.Loaded loaded = PlanWidget.load(app, ID);
        return RenderSupport.layout(app, PlanWidget.card(app, ID, loaded.view, loaded.view.toCard(loaded.rules), width, height),
                width, height);
    }

    private static String text(View root, int id) {
        return CardText.plain(((TextView) root.findViewById(id)).getText().toString());
    }

    private static void check(View root, String tag, List<String> issues) {
        RenderSupport.collectTextIssues(root, tag, issues);
        RenderSupport.collectCutText(root, tag, issues);
        RenderSupport.collectWordSplits(root, tag, issues);
        RenderSupport.collectDotLineEnds(root, tag, issues);
    }

    @Test
    public void theRowShowsTheReminderWholeOrShortOrNotAtAll() throws Exception {
        File dir = RenderSupport.outputDir("reminders");
        List<String> issues = new ArrayList<>();
        // 11/2 day: the 皇帝 card carries the TV shopping.
        ReminderFixtures.with(fake.plan, ReminderFixtures.tv(NOV_2));
        fake.observeAt("meet:emperor");
        View wide = render(320, 200);
        RenderSupport.save(wide, dir, "tv-320x200");
        assertEquals(View.VISIBLE, wide.findViewById(R.id.widget_reminder).getVisibility());
        assertEquals(TV, text(wide, R.id.widget_reminder));
        // Too narrow for the line: the short form, never 「…」.
        View narrow = render(180, 200);
        RenderSupport.save(narrow, dir, "tv-180x200");
        assertEquals("电视购物 2,980 日元", text(narrow, R.id.widget_reminder));
        check(wide, "tv-320x200", issues);
        check(narrow, "tv-180x200", issues);

        // Two on the card: the more important first, then 「 · 共 2 条」.
        ReminderFixtures.with(fake.plan, ReminderFixtures.tv(NOV_2), ReminderFixtures.requestDeadline(NOV_2));
        View two = render(320, 200);
        RenderSupport.save(two, dir, "two-320x200");
        assertEquals("伊莉莎白的第 12、13 号委托 6月6日截止 · 共 2 条", text(two, R.id.widget_reminder));
        View twoNarrow = render(180, 200);
        assertEquals("委托 6月6日截止 · 共 2 条", text(twoNarrow, R.id.widget_reminder));
        check(two, "two-320x200", issues);
        check(twoNarrow, "two-180x200", issues);

        // A line without a short form that does not fit: no row at all.
        Plan.Reminder longOne = ReminderFixtures.tv(NOV_2);
        longOne.shortZh = null;
        ReminderFixtures.with(fake.plan, longOne);
        View none = render(180, 200);
        assertEquals(View.GONE, none.findViewById(R.id.widget_reminder).getVisibility());
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    @Test
    public void everyReminderIsReadOut() {
        ReminderFixtures.with(fake.plan, ReminderFixtures.classAnswer(NOV_2, "朱诺", 3), ReminderFixtures.tv(NOV_2),
                ReminderFixtures.requestDeadline(NOV_2), ReminderFixtures.doze(NOV_2));
        fake.observeAt("meet:emperor");
        CardModel card = model();
        for (Plan.Reminder r : fake.plan.reminders) assertTrue(r.textZh, card.taskDescription.contains(r.textZh));
        // The answer kinds go into the answers row, the rest into the reminder row by priority.
        assertEquals("课堂：朱诺（第 3 项）", card.answers.get(card.answers.size() - 1).text());
        assertEquals(3, card.reminders.size());
        assertEquals("上课犯困时别闭眼：学力 +2", card.reminders.get(0).text);
        assertEquals("伊莉莎白的第 12、13 号委托 6月6日截止", card.reminders.get(1).text);
        assertEquals(TV, card.reminders.get(2).text);
    }

    /** Spec test 7: the class answer only on the daytime card; each exam answer on its own exam-day card. */
    @Test
    public void classAndExamAnswersGoIntoTheAnswersRowOfTheirCard() throws Exception {
        File dir = RenderSupport.outputDir("reminders");
        List<String> issues = new ArrayList<>();
        ReminderFixtures.with(fake.plan, ReminderFixtures.classAnswer(NOV_2, "朱诺", 3));
        fake.observeAt("meet:emperor");
        View day = render(250, 160);
        RenderSupport.save(day, dir, "class-250x160");
        TextView row = day.findViewById(R.id.widget_dialogue_choices);
        assertEquals(View.VISIBLE, row.getVisibility());
        assertTrue(text(day, R.id.widget_dialogue_choices), text(day, R.id.widget_dialogue_choices).endsWith("课堂：朱诺（第 3 项）"));
        assertEquals("no reminder row for an answer", View.GONE, day.findViewById(R.id.widget_reminder).getVisibility());
        check(day, "class-250x160", issues);
        fake.observeAt("act:job_chagall");
        assertEquals(View.GONE, render(250, 160).findViewById(R.id.widget_dialogue_choices).getVisibility());

        // 11/1–11/3 exams: each day its own card with its answer.
        for (int i = 0; i < 6; i++) {
            fake.plan.steps.set(i, FakePlannerApi.step(S + i, "locked:exam", "locked", "期中考试", "剧情安排，不能自由行动", null, null));
        }
        ReminderFixtures.with(fake.plan, ReminderFixtures.exam(NOV_1, "五月病", false), ReminderFixtures.exam(NOV_2, "摩西", false),
                ReminderFixtures.exam(NOV_1 + 2, "做好了拼命的心理准备", false));
        fake.observed.slot = S;
        String[] answers = {"考试：五月病", "考试：摩西", "考试：做好了拼命的心理准备"};
        for (int i = 0; i < 3; i++) {
            WidgetPrefs.setCursor(app, ID, FakePlannerApi.PLAN_ID, S + 2 * i);
            for (int[] size : new int[][]{{180, 150}, {250, 160}, {320, 200}}) {
                View exam = render(size[0], size[1]);
                String tag = "exam-" + i + "-" + size[0] + "x" + size[1];
                RenderSupport.save(exam, dir, tag);
                assertEquals(tag, answers[i], text(exam, R.id.widget_dialogue_choices));
                assertEquals(tag, "期中考试", text(exam, R.id.widget_task));
                check(exam, tag, issues);
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    // ------------------------------------------------------------------ real pack

    /**
     * The shipped pack's 当日提醒 (spec 3.1.0 §1.2.4–§1.2.5) on a new game's plan: every card that
     * carries reminders reads each of them out; at 320x240 it shows its class or exam answer in the
     * answers row and any other reminder in the reminder row (a shorter card gives the reminder row
     * up before the 人格面具 hint and the side step); at 250x160 and 320x240 nothing is cut with "…".
     */
    @Test
    public void realPackRemindersRideOnTheirCards() throws Exception {
        Rules rules = null;
        for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            File f = new File(candidate);
            if (f.isFile()) rules = RulesLoader.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
        Assume.assumeTrue("rule pack not available", rules != null);
        SimState start = new SimState(rules.blocks.length, rules.activities.length);
        start.slot = Rules.slot(GameCalendar.ordinal(2009, 4, 21), false);
        start.cashExact = true;
        Plan plan = Engine.plan(rules, new Profile(), start, null, new Engine.Options());
        assertFalse("the shipped pack attaches 当日提醒", plan.reminders.isEmpty());
        File dir = RenderSupport.outputDir("reminders-real");
        List<String> issues = new ArrayList<>();
        fake.rules = rules;
        fake.plan = plan;
        fake.observed = start;
        int cards = 0;
        int answers = 0;
        int rows = 0;
        for (PlanItems.Item item : PlanItems.of(plan, start.slot)) {
            if (item.reminders.isEmpty()) continue;
            WidgetPrefs.setCursor(app, ID, plan.planId, item.slot);
            CardModel card = model();
            for (Plan.Reminder r : item.reminders) {
                if (!card.taskDescription.contains(r.textZh)) issues.add("slot" + item.slot + " does not read out " + r.textZh);
            }
            for (int[] size : new int[][]{{250, 160}, {320, 240}}) {
                View root = render(size[0], size[1]);
                String tag = "slot" + item.slot + "-" + size[0] + "x" + size[1];
                check(root, tag, issues);
                if (cards < 6) RenderSupport.save(root, dir, tag);
                if (size[0] != 320) continue;
                boolean answer = false;
                boolean other = false;
                for (Plan.Reminder r : item.reminders) {
                    if (r.isAnswer()) answer = true;
                    else other = true;
                }
                TextView row = root.findViewById(R.id.widget_dialogue_choices);
                if (answer) {
                    for (Plan.Reminder r : item.reminders) {
                        if (!r.isAnswer()) continue;
                        boolean drawn = row.getVisibility() == View.VISIBLE && text(root, R.id.widget_dialogue_choices).contains(r.answerZh);
                        if (!drawn) issues.add(tag + " lacks the answer " + r.answerZh + ": " + text(root, R.id.widget_dialogue_choices));
                        else answers++;
                    }
                }
                if (other) {
                    if (root.findViewById(R.id.widget_reminder).getVisibility() != View.VISIBLE) issues.add(tag + " hides its reminder row");
                    else rows++;
                }
            }
            cards++;
        }
        System.out.println("real cards with reminders: " + cards + ", answers drawn: " + answers + ", reminder rows: " + rows);
        assertTrue("few real cards with reminders: " + cards, cards >= 20);
        assertTrue("no class or exam answer drawn", answers > 0);
        assertTrue("no reminder row drawn", rows > 0);
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }
}
