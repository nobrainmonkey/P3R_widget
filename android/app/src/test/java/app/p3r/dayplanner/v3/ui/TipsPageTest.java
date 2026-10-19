package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
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
        assertEquals("10月1日 ～ 11月30日", taggedText(a, "tips_filter_selection"));
        assertEquals(resultIds(a).size() + " 条", taggedText(a, "tips_result_counter"));
        String page = Shots.allText(a.getWindow().getDecorView());
        assertFalse(page, page.contains("游戏进度："));
        assertFalse(page, page.contains("全部提示"));
        assertFalse(page, page.contains(" · 共 "));
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
    public void onboardingAsksForTheGameDateWithoutAddingSearchInstructions() {
        api.snap.observed = null;
        api.snap.plan = null;
        HomeActivity a = tips();
        assertTrue(Shots.has(a, "先设置游戏日期"));
        assertEquals("0 条", taggedText(a, "tips_result_counter"));
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("先在「今天」里完成设置"));
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

    @Test
    public void searchFindsFullRankDialogueBeforeOnboardingWithoutRecordingOrPlanning() throws Exception {
        api.rules = realRules();
        api.snap.observed = null;
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        EditText search = a.getWindow().getDecorView().findViewWithTag("tips_search");
        assertNotNull(search);
        search.setText("风花R1");
        Shots.idle();
        assertTrue(Shots.find(a, "全部").isSelected());
        View row = rowForId(a, "dialogue.priestess.2");
        String card = Shots.allText(row);
        assertTrue(card, card.contains("RANK 1"));
        assertTrue(card, card.contains("→ 2"));
        assertFalse(card, card.contains("没问题（首问）"));
        assertFalse(card, card.contains("4月"));
        Shots.shot(a, "tips_search_dialogue_without_plan");
        assertTrue(row.performClick());
        Shots.idle();
        String detail = Shots.allText(a.sheets().content());
        assertTrue(detail, detail.contains("完整问答顺序：\n没问题\n任意\n没问题"));
        assertTrue(api.calls.isEmpty());
        assertEquals(null, api.snap.observed);
        assertEquals(null, api.snap.plan);
    }

    @Test
    public void contentFiltersAndGlobalSearchExposeAnswersOutsideTheCurrentPeriod() throws Exception {
        api.rules = realRules();
        api.snap.observed = Fixtures.observedMid(api.rules);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        Shots.click(a, "全部");
        filter(a, "社群对话");
        assertTrue("content filtering keeps the chosen all-game scope", Shots.find(a, "全部").isSelected());
        assertNotNull(rowForId(a, "dialogue.priestess.2"));
        filter(a, "课堂·考试");
        EditText search = a.getWindow().getDecorView().findViewWithTag("tips_search");
        search.setText("鲤鱼旗");
        Shots.idle();
        assertNotNull(rowForId(a, "class.04-08"));
        assertTrue(Shots.allText(rowForId(a, "class.04-08")).contains("第 3 项"));
        Shots.shot(a, "tips_search_past_school_answer");
        Shots.click(a, "本期");
        assertTrue(Shots.has(a, "没有匹配结果"));
        Shots.click(a, "全部");
        search.setText("完全不存在的剧情选项");
        Shots.idle();
        assertTrue(Shots.has(a, "没有匹配结果"));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void socialLinksCollapseToOneRowThenOfferUnlockAndCurrentRankButtons() throws Exception {
        api.rules = realRules();
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        Shots.click(a, "全部");
        ListView list = visibleList(a.getWindow().getDecorView());
        int manualLinks = 0;
        for (Rules.Link link : api.rules.links) if (link.kind == Rules.LinkKind.MANUAL) manualLinks++;
        int groupedLinks = 0;
        boolean groupedMonad = false;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.group != null) {
                if (item.group.dialogue()) groupedLinks++;
                else groupedMonad = true;
            } else if (item.reminder != null) assertFalse("all-content keeps dialogue grouped too",
                "dialogue".equals(item.reminder.kind));
        }
        assertEquals(manualLinks, groupedLinks);
        assertTrue("all-content keeps Monad grouped too", groupedMonad);
        filter(a, "社群对话");
        assertEquals("one row per social link, not one card per rank", manualLinks, list.getAdapter().getCount());
        View group = groupRow(a, "dialogue_priestess");
        View body = group.findViewWithTag("tips_group_body_dialogue_priestess");
        assertEquals(View.GONE, body.getVisibility());
        View header = group.findViewWithTag("tips_group_header_dialogue_priestess");
        assertTrue(header.performClick());
        Shots.idle();
        assertEquals(View.VISIBLE, body.getVisibility());
        a.reload();
        Shots.idle();
        group = groupRow(a, "dialogue_priestess");
        body = group.findViewWithTag("tips_group_body_dialogue_priestess");
        header = group.findViewWithTag("tips_group_header_dialogue_priestess");
        assertEquals("refresh preserves the open social link", View.VISIBLE, body.getVisibility());
        Shots.shot(a, "tips_social_ranks");
        View unlock = group.findViewWithTag("tips_entry_dialogue.priestess.1");
        assertEquals("解锁", Shots.allText(unlock).trim());
        View rankOne = group.findViewWithTag("tips_entry_dialogue.priestess.2");
        String rankText = Shots.allText(rankOne);
        assertTrue(rankText, rankText.contains("RANK 1"));
        assertTrue(rankText, rankText.contains("→ 2"));
        assertFalse("answers stay in the detail, not spread across the results", rankText.contains("没问题"));
        assertTrue(unlock.performClick());
        Shots.idle();
        String unlockText = Shots.allText(a.sheets().content());
        assertTrue(unlockText, unlockText.contains("最早6月19日开放"));
        assertTrue(unlockText, unlockText.contains("前置条件：勇气 MAX、命运社群 RANK 1"));
        assertTrue(unlockText, unlockText.contains("放学后到教室前的走廊和山岸风花说话"));
        Shots.click(a, "知道了");
        assertTrue(rankOne.performClick());
        Shots.idle();
        assertTrue(Shots.allText(a.sheets().content()).contains("完整问答顺序：\n没问题\n任意\n没问题"));
        Shots.click(a, "知道了");
        assertTrue(header.performClick());
        assertEquals(View.GONE, body.getVisibility());
        assertTrue(api.calls.isEmpty());
        Shots.shot(a, "tips_social_groups");
    }

    @Test
    public void monadGroupsExposeEveryDoorAndPassageInsideTheirNamedFloorRange() throws Exception {
        api.rules = realRules();
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        Shots.click(a, "全部");
        filter(a, "战斗");
        View group = groupRow(a, "monad_yabbashah_1");
        View body = group.findViewWithTag("tips_group_body_monad_yabbashah_1");
        assertEquals(View.GONE, body.getVisibility());
        String summary = Shots.allText(group.findViewWithTag("tips_group_header_monad_yabbashah_1"));
        assertTrue(summary, summary.contains("鄙俗之庭 亚巴扎"));
        assertTrue(summary, summary.contains("70–92F"));
        assertTrue(summary, summary.contains("蒙纳多之门 3"));
        assertTrue(summary, summary.contains("蒙纳多通道 2"));
        assertTrue(group.findViewWithTag("tips_group_header_monad_yabbashah_1").performClick());
        assertEquals(View.VISIBLE, body.getVisibility());
        assertEquals(5, ((ViewGroup) body).getChildCount());
        View door = group.findViewWithTag("tips_entry_combat.catalogue.combat.yabbashah_1.d1");
        assertTrue(Shots.allText(door).contains("优雅圣母"));
        View passage = group.findViewWithTag("tips_entry_combat.catalogue.combat.yabbashah_1.p2");
        assertTrue(Shots.allText(passage).contains("91F"));
        assertTrue(Shots.allText(passage).contains("第 2 战"));
        Shots.shot(a, "tips_monad_group");
        assertTrue(passage.performClick());
        Shots.idle();
        assertNotNull(a.sheets().content().findViewWithTag("combat_affinity_scroll"));
        assertTrue(Shots.allText(a.sheets().content()).contains("暴走驱动器"));
        Shots.shot(a, "tips_monad_group_detail");
        Shots.click(a, "知道了");
        assertNotNull("the next opening range has its own group", groupRow(a, "monad_yabbashah_2"));
        EditText search = a.getWindow().getDecorView().findViewWithTag("tips_search");
        search.setText("117层");
        Shots.idle();
        View matchingGroup = groupRow(a, "monad_yabbashah_2");
        assertEquals("floor search opens the matching block directly", View.VISIBLE,
            matchingGroup.findViewWithTag("tips_group_body_monad_yabbashah_2").getVisibility());
        assertTrue(resultIds(a).toString(), resultIds(a).contains("combat.catalogue.combat.yabbashah_2.p1"));
        assertFalse(resultIds(a).contains("combat.catalogue.combat.yabbashah_1.p1"));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void filterUsesOnlyTheRecognizableIconWithAnAccessibleName() {
        HomeActivity a = tips();
        TextView filter = a.getWindow().getDecorView().findViewWithTag("tips_filter_button");
        assertNotNull(filter);
        assertEquals("", Zh.plain(filter.getText()));
        assertEquals("筛选提示", filter.getContentDescription().toString());
        Shots.render(a.getWindow().getDecorView()).recycle();
        assertEquals("the icon keeps a full touch target", Ui.dp(a, 48), filter.getHeight());
        assertEquals("the icon keeps a full touch target", Ui.dp(a, 48), filter.getWidth());
        assertTrue(filter.performClick());
        Shots.idle();
        assertTrue(Shots.has(a, "筛选提示"));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void requestsCanBeFoundByExactNumberOrNameAndReadWithoutRecordingCompletion() throws Exception {
        api.rules = realRules();
        api.rules.reminders.requestTips.clear();
        Rules.RequestTip request = new Rules.RequestTip();
        request.number = 75;
        request.titleZh = "带给我战国时代的头盔";
        request.aliases.add("战国头盔");
        request.detailZh = "先接委托，到学校教职员室分 8 天触发对话，再拿物品交付。";
        request.fromDay = Fixtures.day(10, 6);
        api.rules.reminders.requestTips.add(request);
        api.snap.observed = Fixtures.observedMid(api.rules);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        filter(a, "委托");
        assertTrue(Shots.find(a, "本期").isSelected());
        EditText search = a.getWindow().getDecorView().findViewWithTag("tips_search");
        search.setText("请求75");
        Shots.idle();
        View result = rowForId(a, "request.catalogue.75");
        assertTrue(Shots.allText(result).contains("第 75 号 · 带给我战国时代的头盔"));
        Shots.shot(a, "tips_request_search_number");
        search.setText("战国头盔");
        Shots.idle();
        assertTrue(rowForId(a, "request.catalogue.75").performClick());
        Shots.idle();
        String detail = Shots.allText(a.sheets().content());
        assertTrue(detail, detail.contains("分 8 天"));
        assertFalse(detail, detail.contains("未自动追踪"));
        Shots.shot(a, "tips_request_detail");
        assertTrue(api.calls.isEmpty());
        assertEquals(Fixtures.SLOT_NOW, api.snap.observed.slot);
    }

    @Test
    public void combatFilterFindsExactFloorsAndFullMoonMonthsAndOpensWeaknessAndTactics() throws Exception {
        api.rules = realRules();
        api.rules.reminders.combatTips.clear();
        Rules.CombatTip guardian = new Rules.CombatTip();
        guardian.id = "guardian.36";
        guardian.kind = "guardian";
        guardian.titleZh = "守关首领";
        guardian.floor = 36;
        guardian.fromDay = Fixtures.day(6, 13);
        guardian.shortZh = "弱点：冰";
        guardian.detailZh = "弱点：冰；火无效。\n先处理召唤的小怪。";
        guardian.tacticsZh = "先处理召唤的小怪。";
        Rules.CombatEnemy enemy = new Rules.CombatEnemy();
        enemy.nameZh = "守关首领";
        enemy.affinities = new String[]{"-", "-", "-", "n", "w", "-", "-", "-", "-"};
        guardian.enemies.add(enemy);
        api.rules.reminders.combatTips.add(guardian);
        Rules.CombatTip moon = new Rules.CombatTip();
        moon.id = "fullmoon.hermit";
        moon.kind = "full_moon";
        moon.titleZh = "隐者";
        moon.fromDay = Fixtures.day(9, 5);
        moon.shortZh = "注意蓄力攻击";
        moon.detailZh = "蓄力后防御，准备治疗。";
        api.rules.reminders.combatTips.add(moon);
        api.snap.observed = Fixtures.observedMid(api.rules);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        filter(a, "战斗");
        assertTrue(Shots.find(a, "本期").isSelected());
        EditText search = a.getWindow().getDecorView().findViewWithTag("tips_search");
        search.setText("第36层");
        Shots.idle();
        View row = rowForId(a, "combat.catalogue.guardian.36");
        assertTrue(Shots.allText(row).contains("36F · 守关首领"));
        assertTrue(Shots.allText(row).contains("弱点：冰"));
        Shots.shot(a, "tips_combat_floor_search");
        assertTrue(row.performClick());
        Shots.idle();
        String detail = Shots.allText(a.sheets().content());
        android.widget.TextView fire = a.sheets().content().findViewWithTag("combat_affinity_0_3");
        assertEquals("无", Zh.plain(fire.getText()));
        assertTrue(detail, detail.contains("先处理召唤的小怪"));
        Shots.shot(a, "tips_combat_detail");
        Shots.click(a, "知道了");
        search.setText("9月 满月");
        Shots.idle();
        assertNotNull(rowForId(a, "combat.catalogue.fullmoon.hermit"));
        assertTrue(api.calls.isEmpty());
        assertEquals(Fixtures.SLOT_NOW, api.snap.observed.slot);
    }

    @Test
    public void filterSelectionsAreAppliedTogetherAndBackCancelsPendingChanges() {
        reminders();
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        List<String> initial = resultIds(a);
        String initialCount = taggedText(a, "tips_result_counter");

        openFilter(a);
        assertTrue(Shots.has(a, "筛选提示"));
        sheetClick(a, "委托");
        assertTrue(a.sheets().isShowing());
        assertTrue("a pending content choice does not change scope", Shots.find(a, "本期").isSelected());
        assertEquals(initialCount, taggedText(a, "tips_result_counter"));
        assertEquals(initial, resultIds(a));
        a.onBackPressed();
        Shots.idle();
        assertFalse(a.sheets().isShowing());
        assertEquals(initial, resultIds(a));
        assertEquals("10月1日 ～ 11月30日", taggedText(a, "tips_filter_selection"));

        Shots.click(a, "全部");
        filter(a, "委托");
        assertTrue(Shots.find(a, "全部").isSelected());
        assertEquals("委托", taggedText(a, "tips_filter_selection"));
        List<String> requests = resultIds(a);
        assertTrue(requests.toString(), requests.contains("request.other"));
        assertAllLabels(a, "委托");
        openFilter(a);
        sheetClick(a, "重置");
        assertEquals(requests, resultIds(a));
        assertEquals("委托", taggedText(a, "tips_filter_selection"));
        a.onBackPressed();
        Shots.idle();
        assertEquals(requests, resultIds(a));
        assertEquals("委托", taggedText(a, "tips_filter_selection"));

        openFilter(a);
        sheetClick(a, "重置");
        sheetClick(a, "应用");
        assertFalse(a.sheets().isShowing());
        assertFalse(a.getWindow().getDecorView().findViewWithTag("tips_filter_button").isSelected());
        assertEquals(View.GONE, a.getWindow().getDecorView().findViewWithTag("tips_filter_selection").getVisibility());
        assertTrue(resultIds(a).size() > requests.size());
        assertTrue(api.calls.isEmpty());
        Shots.shot(a, "tips_filters_reset_all_content");
    }

    @Test
    public void applyingContentFiltersKeepsTheExplicitPeriodScopeEvenWhenNothingChanged() {
        reminders();
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        filter(a, "委托");
        assertTrue(Shots.find(a, "本期").isSelected());
        List<String> periodRequests = resultIds(a);
        assertFalse(periodRequests.contains("request.other"));
        Shots.click(a, "全部");
        assertTrue(resultIds(a).contains("request.other"));
        Shots.click(a, "本期");
        openFilter(a);
        sheetClick(a, "应用");
        assertTrue("applying an unchanged filter retains the user's period selection", Shots.find(a, "本期").isSelected());
        assertEquals(periodRequests, resultIds(a));
        assertEquals("10月1日 ～ 11月30日 · 委托", taggedText(a, "tips_filter_selection"));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void actualCardTagsFilterRescuesAndUnlocksAndReplaceEarlierSelections() {
        reminders();
        Rules.Reminder unlock = raw("feature.unlock.search", Fixtures.OCT_22, -1, "勇气达到 6 级后，可开启女教皇社群");
        unlock.kind = Plan.Reminder.FEATURE;
        unlock.labelZh = "社群解锁";
        api.rules.reminders.items.add(unlock);
        Rules.RescueGroup rescue = new Rules.RescueGroup();
        rescue.id = "rescue.10-21";
        rescue.fromDay = Fixtures.day(10, 21);
        rescue.toDay = Fixtures.day(11, 2);
        rescue.count = 1;
        rescue.rescueIds.add("maiko");
        api.rules.reminders.rescueGroups.add(rescue);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        List<String> initial = resultIds(a);
        filter(a, "失踪者");
        assertTrue(Shots.find(a, "本期").isSelected());
        assertEquals("10月1日 ～ 11月30日 · 失踪者", taggedText(a, "tips_filter_selection"));
        assertAllLabels(a, "失踪者");
        List<String> rescues = resultIds(a);
        assertTrue(initial.containsAll(rescues));

        openFilter(a);
        assertTrue(Shots.find(a.sheets().content(), "失踪者").isSelected());
        sheetClick(a, "社群解锁");
        assertFalse(Shots.find(a.sheets().content(), "失踪者").isSelected());
        assertTrue(Shots.find(a.sheets().content(), "社群解锁").isSelected());
        assertEquals(rescues, resultIds(a));
        Shots.shot(a, "tips_filter_sheet_actual_tags");
        sheetClick(a, "应用");
        assertEquals("10月1日 ～ 11月30日 · 社群解锁", taggedText(a, "tips_filter_selection"));
        assertAllLabels(a, "社群解锁");
        assertNotNull(rowForId(a, unlock.id));
        for (String id : rescues) assertFalse(resultIds(a).contains(id));
        assertEquals(resultIds(a).size() + " 条", taggedText(a, "tips_result_counter"));
        assertTrue(api.calls.isEmpty());
        assertEquals(Fixtures.SLOT_NOW, api.snap.observed.slot);
        Shots.shot(a, "tips_filter_actual_unlocks");
    }

    private static void openFilter(HomeActivity a) {
        View filter = a.getWindow().getDecorView().findViewWithTag("tips_filter_button");
        assertNotNull(filter);
        assertTrue(filter.performClick());
        Shots.idle();
    }

    private static void filter(HomeActivity a, String label) {
        openFilter(a);
        sheetClick(a, label);
        sheetClick(a, "应用");
        assertFalse(a.sheets().isShowing());
    }

    private static void sheetClick(HomeActivity a, String label) {
        assertTrue("filter sheet is open", a.sheets().isShowing());
        assertTrue(Shots.clickTarget(Shots.find(a.sheets().content(), label)).performClick());
        Shots.idle();
    }

    private static String taggedText(HomeActivity a, String tag) {
        TextView text = a.getWindow().getDecorView().findViewWithTag(tag);
        assertNotNull(text);
        return Zh.plain(text.getText());
    }

    private static List<String> resultIds(HomeActivity a) {
        List<String> ids = new ArrayList<>();
        ListView list = visibleList(a.getWindow().getDecorView());
        assertNotNull(list);
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.reminder != null) ids.add(item.reminder.id);
            if (item.group != null) for (Plan.Reminder entry : item.group.entries) ids.add(entry.id);
        }
        return ids;
    }

    private static void assertAllLabels(HomeActivity a, String label) {
        ListView list = visibleList(a.getWindow().getDecorView());
        assertNotNull(list);
        int count = 0;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.reminder == null) continue;
            assertEquals(item.reminder.id, label, item.reminder.labelZh);
            count++;
        }
        assertTrue("the selected actual card tag has results", count > 0);
    }

    private static View rowForId(HomeActivity a, String id) {
        ListView list = visibleList(a.getWindow().getDecorView());
        assertNotNull(list);
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.reminder != null && id.equals(item.reminder.id)) {
                return list.getAdapter().getView(i, null, list);
            }
            if (item.group != null) for (Plan.Reminder entry : item.group.entries) {
                if (id.equals(entry.id)) return list.getAdapter().getView(i, null, list).findViewWithTag("tips_entry_" + id);
            }
        }
        throw new AssertionError("missing reminder " + id);
    }

    private static View groupRow(HomeActivity a, String key) {
        ListView list = visibleList(a.getWindow().getDecorView());
        assertNotNull(list);
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.group != null && key.equals(item.group.key)) {
                list.setSelection(i);
                Shots.idle();
                Shots.render(a.getWindow().getDecorView()).recycle();
                View group = list.findViewWithTag("tips_group_" + key);
                assertNotNull("the selected group is visible", group);
                return group;
            }
        }
        throw new AssertionError("missing group " + key);
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
