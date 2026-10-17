package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

/** The period hub is navigable without a current plan and keeps historical dates distinct from completion. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TipsPageTest extends UiTestBase {
    private static final String CARRIED = "别忘了把饮料交给伊莉莎白";
    private static final String PAST = "先买好明天要交的东西";
    private static final String FUTURE = "记得在月底前领取奖励";
    private static final String DETAIL = "先去车站买好，再到天鹅绒房间交付；不会消耗行动时段。";

    private void reminders() {
        api.rules.reminders = new Rules.Reminders();
        api.rules.reminders.items.add(raw("request.carried", Fixtures.day(9, 28), Fixtures.day(10, 25), CARRIED));
        api.rules.reminders.items.add(raw("request.past", Fixtures.day(10, 3), -1, PAST));
        api.rules.reminders.items.add(raw("request.next", Fixtures.day(10, 24), Fixtures.day(10, 31), FUTURE));
        api.rules.reminders.items.add(raw("request.other", Fixtures.day(12, 1), -1, "下个区间的提示"));
    }

    private static Rules.Reminder raw(String id, int day, int until, String text) {
        Rules.Reminder reminder = new Rules.Reminder();
        reminder.id = id;
        reminder.kind = Plan.Reminder.REQUEST;
        reminder.labelZh = "委托";
        reminder.day = day;
        reminder.untilDay = until;
        reminder.zh = text;
        reminder.detailZh = DETAIL;
        reminder.priority = Plan.Reminder.MISSABLE;
        return reminder;
    }

    private HomeActivity tips() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "tips");
        return home(intent);
    }

    @Test
    public void tipsDeepLinkShowsTheObservedPeriodAndDetailsWithoutAPlan() {
        reminders();
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        assertEquals(HomeActivity.TAB_TIPS, a.currentTab());
        assertTrue(Shots.has(a, "提齐亚 · 69–117F"));
        assertTrue(Shots.allText(a.getWindow().getDecorView()).contains("10月1日 ～ 11月30日 · 共 "));
        assertTrue(Shots.has(a, "游戏进度：10月22日 白天"));
        assertTrue(Shots.has(a, TipsPage.LEGEND));
        View carried = rowForId(a, "request.carried");
        assertTrue(Shots.allText(carried).contains(CARRIED));
        Shots.shot(a, "home_tips_period");
        assertTrue(carried.performClick());
        Shots.idle();
        assertTrue(a.sheets().isShowing());
        assertTrue(Shots.has(a, "提示详情"));
        assertTrue(Shots.has(a, DETAIL));
        assertTrue(api.calls.isEmpty());
        Shots.shot(a, "tips_period_detail");
        Shots.click(a, "知道了");
        assertFalse(a.sheets().isShowing());
    }

    @Test
    public void oldMeDeepLinkAndBackNavigationStillWork() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "me");
        HomeActivity a = home(intent);
        assertEquals(HomeActivity.TAB_ME, a.currentTab());
        a.selectTab(HomeActivity.TAB_TIPS);
        assertEquals(HomeActivity.TAB_TIPS, a.currentTab());
        a.onBackPressed();
        assertEquals(HomeActivity.TAB_TODAY, a.currentTab());
    }

    @Test
    public void onboardingExplainsWhereThePeriodComesFrom() {
        api.snap.observed = null;
        api.snap.plan = null;
        HomeActivity a = tips();
        assertTrue(Shots.has(a, "先在「今天」里完成设置，这里会按游戏日期汇总当期提示。"));
    }

    @Test
    public void allPeriodRowsRemainAvailableIncludingPastDatesAndCarryOverWindows() {
        reminders();
        Plan.Reminder stale = planReminder(Fixtures.OCT_22, false, -1);
        stale.id = "nurse.10-22";
        stale.kind = Plan.Reminder.NURSE;
        stale.textZh = "旧计划的保健室预测";
        api.snap.plan.reminders.add(stale);
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        ListView list = visibleList(a.getWindow().getDecorView());
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            View row = list.getAdapter().getView(i, null, list);
            all.append(Shots.allText(row));
        }
        String text = all.toString();
        assertTrue(text, text.contains(CARRIED));
        assertTrue(text, text.contains(PAST));
        assertTrue(text, text.contains(FUTURE));
        assertFalse(text, text.contains("下个区间的提示"));
        assertFalse(text, text.contains("旧计划的保健室预测"));
        assertTrue(text, text.indexOf("当前要留意") < text.indexOf("接下来别错过"));
        assertTrue(text, text.indexOf("接下来别错过") < text.indexOf("本期已过日期"));
        assertFalse(text, text.contains("已完成"));
    }

    @Test
    public void datesDoNotTurnIntoCompletionAndAllRowsAppearExactlyOnce() {
        int now = Fixtures.SLOT_NOW;
        Plan.Reminder carried = planReminder(Fixtures.day(9, 28), false, Fixtures.day(10, 25));
        Plan.Reminder morning = planReminder(Fixtures.OCT_22, false, -1);
        Plan.Reminder tonight = planReminder(Fixtures.OCT_22, true, -1);
        Plan.Reminder later = planReminder(Fixtures.OCT_22 + 1, false, -1);
        Plan.Reminder expired = planReminder(Fixtures.day(10, 1), false, Fixtures.day(10, 20));
        List<TipsPage.Item> items = TipsPage.buildItems(Arrays.asList(carried, morning, tonight, later, expired), now + 1);
        int rows = 0;
        for (TipsPage.Item item : items) {
            if (item.reminder != null) rows++;
        }
        assertEquals(5, rows);
        assertEquals(0, TipsPage.sectionOf(carried, now));
        assertEquals(0, TipsPage.sectionOf(tonight, now + 1));
        assertEquals(1, TipsPage.sectionOf(later, now));
        assertEquals(2, TipsPage.sectionOf(morning, now + 1));
        assertEquals(2, TipsPage.sectionOf(expired, now));
        assertEquals("9月28日开放 · 10月25日截止", TipsPage.dateLine(carried, now));
        Plan.Reminder dueToday = planReminder(Fixtures.day(10, 1), false, Fixtures.OCT_22);
        assertEquals("10月1日开放 · 今天截止", TipsPage.dateLine(dueToday, now));
        Plan.Reminder exam = planReminder(Fixtures.day(10, 20), false, Fixtures.day(10, 24));
        exam.kind = "calendar";
        assertEquals("10月20日 ～ 10月24日 · 当前在此期间", TipsPage.dateLine(exam, now));
        exam.id = "exam";
        int lastDaytime = Rules.slot(Fixtures.day(10, 24), false);
        assertEquals(2, TipsPage.sectionOf(exam, lastDaytime + 1, Collections.emptySet(),
            Collections.singletonMap(exam.id, lastDaytime)));
        assertEquals("10月20日 ～ 10月24日", TipsPage.dateLine(exam, lastDaytime + 1, lastDaytime));
        morning.id = "ongoing";
        assertEquals(0, TipsPage.sectionOf(morning, now + 1, Collections.singleton("ongoing")));
        later.id = "ongoing";
        assertEquals(1, TipsPage.sectionOf(later, now, Collections.singleton("ongoing")));
    }

    @Test
    public void realPeriodListsSpecificClassAndExamAnswersAndOpensTheFullLongAnswer() throws Exception {
        Rules rules = realRules();
        api.rules = rules;
        api.snap.observed = Fixtures.observedMid(rules);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        ListView list = visibleList(a.getWindow().getDecorView());
        assertNotNull(list);
        int classAnswers = 0;
        int examAnswers = 0;
        View longAnswer = null;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            Plan.Reminder reminder = item.reminder;
            if (reminder == null || !reminder.isAnswer()) continue;
            View row = list.getAdapter().getView(i, null, list);
            String text = Shots.allText(row);
            assertTrue(reminder.id + ": " + text, text.contains("选「" + reminder.answerZh + "」"));
            if (reminder.option > 0) {
                assertTrue(reminder.id + ": " + text, text.contains("第 " + reminder.option + " 项"));
            }
            if (Plan.Reminder.CLASS.equals(reminder.kind)) classAnswers++;
            if (Plan.Reminder.EXAM.equals(reminder.kind)) examAnswers++;
            if ("布拉瓦茨基夫人".equals(reminder.answerZh)) longAnswer = row;
        }
        assertTrue("the period includes the complete class answer text", classAnswers > 0);
        assertTrue("past exams remain readable midway through the period", examAnswers > 0);
        assertNotNull("the real October long answer is in the period", longAnswer);
        assertTrue(longAnswer.performClick());
        Shots.idle();
        assertTrue(Shots.has(a, "选「布拉瓦茨基夫人」（第 2 项）"));
        assertTrue(Shots.allText(a.sheets().content()).contains("答对了魅力 +2"));
        Shots.shot(a, "tips_period_real_class_answer");
    }

    @Test
    public void unlockingEntriesShowTheActualUnlockAndKeepRequirementsInTheDetails() {
        api.rules.reminders = new Rules.Reminders();
        Rules.Reminder unlock = raw("feature.unlock", Fixtures.OCT_22, -1, "勇气达到 6 级后，可以开启女教皇社群");
        unlock.kind = Plan.Reminder.FEATURE;
        unlock.labelZh = "社群解锁";
        unlock.detailZh = "勇气达到 6 级，放学后到家政教室找山岸风花；先加入家政部。";
        api.rules.reminders.items.add(unlock);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        View row = rowForId(a, unlock.id);
        assertTrue(Shots.allText(row).contains(unlock.zh));
        assertTrue(row.performClick());
        Shots.idle();
        assertTrue(Shots.has(a, unlock.detailZh));
    }

    private static View rowForId(HomeActivity a, String id) {
        ListView list = visibleList(a.getWindow().getDecorView());
        assertNotNull(list);
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.reminder != null && id.equals(item.reminder.id)) {
                return list.getAdapter().getView(i, null, list);
            }
        }
        throw new AssertionError("missing reminder " + id);
    }

    private static Rules realRules() throws Exception {
        for (String path : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            File file = new File(path);
            if (file.isFile()) return RulesLoader.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
        throw new IllegalStateException("rule pack not found");
    }

    private static Plan.Reminder planReminder(int day, boolean evening, int until) {
        Plan.Reminder reminder = new Plan.Reminder();
        reminder.slot = Rules.slot(day, evening);
        reminder.untilDay = until;
        return reminder;
    }

    private static ListView visibleList(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof ListView) return (ListView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView found = visibleList(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
