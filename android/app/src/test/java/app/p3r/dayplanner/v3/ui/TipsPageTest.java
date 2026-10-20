package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

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
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

/**
 * 提示 (docs/DESIGN_3.6_UI.md): the period opens with its banner and borderless rows, 全部 is a
 * directory, the content strip applies on one tap, and nothing here records, plans or saves.
 */
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
    public void tipsDeepLinkShowsThePeriodBannerAndDetailsWithoutAPlan() {
        reminders();
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        assertEquals(HomeActivity.TAB_TIPS, a.currentTab());
        assertTrue("提示 opens in 本期 when there is a game date", tagged(a, "tips_scope_period").isSelected());
        String banner = Shots.allText(tagged(a, "tips_period_hero"));
        assertTrue(banner, banner.contains("提齐亚"));
        assertTrue(banner, banner.contains("69–117F"));
        assertTrue(banner, banner.contains("10月1日–11月30日"));
        assertEquals("the counter only shows while searching", View.GONE, tagged(a, "tips_result_counter").getVisibility());
        String page = Shots.allText(a.getWindow().getDecorView());
        assertFalse(page, page.contains("游戏进度："));
        assertFalse(page, page.contains("全部提示"));
        assertFalse(page, page.contains(" · 共 "));
        assertFalse("no filter sheet and no 查看详情 captions", page.contains("查看详情"));
        View carried = rowForId(a, "request.carried");
        String row = Shots.allText(carried);
        assertTrue(row, row.contains(CARRIED));
        assertTrue("the deadline sits beside the tag: " + row, row.contains("10月25日截止"));
        Shots.shot(a, "home_tips_period");
        assertTrue(carried.performClick());
        Shots.idle();
        assertTrue(a.sheets().isShowing());
        assertFalse("the sheet's title is the item, not a type", Shots.has(a, "提示详情"));
        assertTrue(Shots.has(a, CARRIED));
        assertTrue(Shots.has(a, DETAIL));
        assertTrue(Shots.has(a, "9月28日起 · 10月25日截止"));
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
    public void withoutAGameDateTheLookupOpensInAllAndThePeriodAsksForTheDate() {
        api.snap.observed = null;
        api.snap.plan = null;
        HomeActivity a = tips();
        assertTrue("the lookup works without a date", tagged(a, "tips_scope_all").isSelected());
        assertNotNull("全部 opens on the directory", a.getWindow().getDecorView().findViewWithTag("tips_tile_dialogue"));
        assertTrue(tagged(a, "tips_scope_period").getAlpha() < 1f);
        Shots.shot(a, "tips_without_date");
        assertTrue(tagged(a, "tips_scope_period").performClick());
        Shots.idle();
        assertTrue(Shots.has(a, TipsPage.NO_DATE));
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("先设置游戏日期"));
        Shots.shot(a, "tips_period_asks_for_date");
        Shots.click(a, TipsPage.SET_DATE);
        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(StateActivity.class.getName(), next.getComponent().getClassName());
        assertEquals("onboarding", next.getStringExtra(Intents.EXTRA_STATE_MODE));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void allPeriodRowsRemainAvailableAndThePastFoldsIntoOneBar() {
        reminders();
        Plan.Reminder stale = planReminder(Fixtures.OCT_22, false, -1);
        stale.id = "nurse.10-22";
        stale.kind = Plan.Reminder.NURSE;
        stale.textZh = "旧计划的保健室预测";
        api.snap.plan.reminders.add(stale);
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        String folded = listText(a);
        assertTrue(folded, folded.contains("已经过去 · "));
        assertFalse("the past is folded at first", folded.contains(PAST));
        assertTrue(openPast(a));
        String text = listText(a);
        assertTrue(text, text.contains(CARRIED));
        assertTrue(text, text.contains(PAST));
        assertTrue(text, text.contains(FUTURE));
        assertFalse(text, text.contains("下个区间的提示"));
        assertFalse(text, text.contains("旧计划的保健室预测"));
        assertTrue(text, text.indexOf("现在") < text.indexOf("接下来"));
        assertTrue(text, text.indexOf("接下来") < text.indexOf("已经过去"));
        assertFalse(text, text.contains("已完成"));
        Shots.shot(a, "tips_period_past_open");
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
        assertEquals("9月28日起 · 10月25日截止", TipsPage.dateLine(carried, now));
        Plan.Reminder dueToday = planReminder(Fixtures.day(10, 1), false, Fixtures.OCT_22);
        assertEquals("10月1日起 · 今天截止", TipsPage.dateLine(dueToday, now));
        Plan.Reminder exam = planReminder(Fixtures.day(10, 20), false, Fixtures.day(10, 24));
        exam.kind = "calendar";
        assertEquals("10月20日–10月24日 · 进行中", TipsPage.dateLine(exam, now));
        exam.id = "exam";
        int lastDaytime = Rules.slot(Fixtures.day(10, 24), false);
        assertEquals(2, TipsPage.sectionOf(exam, lastDaytime + 1, Collections.emptySet(),
            Collections.singletonMap(exam.id, lastDaytime)));
        assertEquals("10月20日–10月24日", TipsPage.dateLine(exam, lastDaytime + 1, lastDaytime));
        assertEquals("10月22日 晚上 · 今天", TipsPage.dateLine(tonight, now));
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
        openPast(a);
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
            if ("布拉瓦茨基夫人".equals(reminder.answerZh)) longAnswer = row.findViewWithTag("tips_row_" + reminder.id);
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
    public void realPeriodCollapsesRequestsAndRepeatedDaysAndDropsBatchNotices() throws Exception {
        Rules rules = realRules();
        api.rules = rules;
        api.snap.observed = Fixtures.observedMid(rules);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        List<String> ids = resultIds(a);
        assertFalse("batch notices repeat the named requests", ids.contains("request.10-06"));
        View group = groupRow(a, "requests");
        String header = Shots.allText(group.findViewWithTag("tips_group_header_requests"));
        assertTrue(header, header.contains("伊莉莎白的委托 · "));
        assertTrue(header, header.contains(" 件"));
        assertEquals(View.GONE, group.findViewWithTag("tips_group_body_requests").getVisibility());
        int repeats = 0;
        ListView list = visibleList(a.getWindow().getDecorView());
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.group != null && item.group.key.startsWith("repeat_")) {
                repeats++;
                assertTrue(item.group.entries.size() >= 3);
            }
        }
        assertTrue("the 8 faculty-office days are one row", repeats > 0);
        Shots.shot(a, "tips_period_real");
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void theRequestsRowCountsWhatIsStillOpenAndSaysTodayOnTheLastDay() throws Exception {
        Rules rules = realRules();
        api.rules = rules;
        api.snap.observed = Fixtures.observedMid(rules);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        // 6月6日: 第 12、13 号 are due today, like each row says.
        api.snap.observed.slot = Rules.slot(66, false);
        HomeActivity a = tips();
        String header = Shots.allText(groupRow(a, "requests").findViewWithTag("tips_group_header_requests"));
        assertTrue(header, header.contains("今天截止 2 件"));
        // The next day they have expired: rows under 已经过去, not counted with the open ones under 现在.
        api.snap.observed.slot = Rules.slot(67, false);
        a.reload();
        Shots.idle();
        ListView list = visibleList(a.getWindow().getDecorView());
        TipsPage.EntryGroup open = null;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.group != null && "requests".equals(item.group.key)) open = item.group;
        }
        assertNotNull(open);
        for (Plan.Reminder r : open.entries) assertTrue(r.id, r.untilDay < 0 || r.untilDay >= 67);
        assertTrue(Shots.allText(groupRow(a, "requests")).contains("伊莉莎白的委托 · " + open.entries.size() + " 件"));
        assertTrue(openPast(a));
        list = visibleList(a.getWindow().getDecorView());
        boolean pastRow = false;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.reminder != null && "request.catalogue.12".equals(item.reminder.id)) pastRow = true;
        }
        assertTrue("第 12 号 is a row of its own under 已经过去", pastRow);
    }

    @Test
    public void typingUpdatesTheResultsInPlaceAndOnlyStartingOrClearingASearchSlidesThemIn() throws Exception {
        api.rules = realRules();
        HomeActivity a = tips();
        EditText search = a.getWindow().getDecorView().findViewWithTag("tips_search");
        ListView list = visibleList(a.getWindow().getDecorView());
        Ui.setAnimationsEnabled(true);
        search.setText("3");
        assertEquals("starting a search slides the results in", 0f, list.getAlpha(), 0f);
        list.animate().cancel();
        list.setAlpha(1f);
        list.setTranslationX(0f);
        search.append("6");
        assertEquals("a typed character never blanks the list", 1f, list.getAlpha(), 0f);
        search.append("F");
        assertEquals(1f, list.getAlpha(), 0f);
        assertEquals(0f, list.getTranslationX(), 0f);
        assertTrue(resultIds(a).toString(), resultIds(a).contains("combat.catalogue.combat.arqa_1.36"));
        search.setText("");
        assertEquals("clearing it brings the old view back with the slide", 0f, list.getAlpha(), 0f);
    }

    @Test
    public void openingAGroupNearTheBottomScrollsItsRowsIntoView() throws Exception {
        api.rules = realRules();
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        click(a, "tips_scope_all");
        click(a, "tips_content_dialogue");
        Shots.render(a.getWindow().getDecorView()).recycle();
        ListView list = visibleList(a.getWindow().getDecorView());
        int bottom = list.getHeight() - list.getPaddingBottom();
        ViewGroup row = null;
        for (int i = list.getChildCount() - 1; i >= 0 && row == null; i--) {
            View child = list.getChildAt(i);
            if (child.getBottom() <= bottom) row = (ViewGroup) child;
        }
        assertNotNull(row);
        View card = row.getChildAt(0);
        String key = ((String) card.getTag()).substring("tips_group_".length());
        Ui.setAnimationsEnabled(true);
        assertTrue(card.findViewWithTag("tips_group_header_" + key).performClick());
        for (int step = 0; step < 40; step++) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
            Shots.render(a.getWindow().getDecorView()).recycle();
        }
        // Robolectric records the smooth scroll rather than running it: the open rows below the
        // bottom edge are brought up, and no further than the group's own top.
        int overflow = row.getBottom() - bottom;
        assertTrue("the opened group runs past the bottom", overflow > 0);
        int expected = Math.min(overflow, row.getTop() - list.getPaddingTop());
        assertTrue(expected > 0);
        assertEquals(expected, shadowOf(list).getLastSmoothScrollByDistance());
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
        assertTrue(tagged(a, "tips_scope_all").isSelected());
        assertEquals("找到 1 条", Zh.plain(((TextView) tagged(a, "tips_result_counter")).getText()));
        View row = rowForId(a, "dialogue.priestess.2");
        String card = Shots.allText(row);
        assertTrue(card, card.contains("1→2"));
        assertFalse(card, card.contains("没问题"));
        assertFalse(card, card.contains("4月"));
        Shots.shot(a, "tips_search_dialogue_without_plan");
        assertTrue(row.performClick());
        Shots.idle();
        String detail = Shots.allText(a.sheets().content());
        assertTrue(detail, detail.contains("RANK 1 → 2"));
        assertTrue(detail, detail.contains("第 1 问\n没问题\n第 2 问\n随便选\n第 3 问\n没问题"));
        assertFalse(detail, detail.contains("完整问答顺序"));
        assertFalse(detail, detail.contains("注意："));
        Shots.shot(a, "tips_rank_sheet");
        assertTrue(api.calls.isEmpty());
        assertEquals(null, api.snap.observed);
        assertEquals(null, api.snap.plan);
    }

    @Test
    public void contentStripAndGlobalSearchExposeAnswersOutsideTheCurrentPeriod() throws Exception {
        api.rules = realRules();
        api.snap.observed = Fixtures.observedMid(api.rules);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        click(a, "tips_scope_all");
        click(a, "tips_content_dialogue");
        assertTrue("the content strip keeps the chosen scope", tagged(a, "tips_scope_all").isSelected());
        assertNotNull(rowForId(a, "dialogue.priestess.2"));
        click(a, "tips_content_class");
        EditText search = a.getWindow().getDecorView().findViewWithTag("tips_search");
        search.setText("鲤鱼旗");
        Shots.idle();
        assertNotNull(rowForId(a, "class.04-08"));
        assertTrue(Shots.allText(rowForId(a, "class.04-08")).contains("第 3 项"));
        Shots.shot(a, "tips_search_past_school_answer");
        click(a, "tips_scope_period");
        assertTrue(Shots.has(a, TipsPage.EMPTY_SEARCH));
        assertTrue("a filtered dead end offers the whole list", Shots.has(a, TipsPage.SHOW_ALL));
        click(a, "tips_scope_all");
        search.setText("完全不存在的剧情选项");
        Shots.idle();
        assertTrue(Shots.has(a, TipsPage.EMPTY_SEARCH));
        Shots.click(a, TipsPage.SHOW_ALL);
        assertTrue("看全部 clears the content filter", tagged(a, "tips_content_all").isSelected());
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void socialLinksAreOneArcanaRowWithARankTrackAndTheirSheets() throws Exception {
        api.rules = realRules();
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        click(a, "tips_scope_all");
        Shots.shot(a, "tips_directory");
        // A tile and the strip chip that do the same thing have the same name.
        String[] keys = {"dialogue", "class", "request", "combat"};
        for (int i = 0; i < keys.length; i++) {
            String chip = Zh.plain(((TextView) tagged(a, "tips_content_" + keys[i])).getText());
            assertEquals(TipsPage.KIND_LABELS[i], chip);
            assertTrue(keys[i], Shots.allText(tagged(a, "tips_tile_" + keys[i])).startsWith(chip + "\n"));
        }
        assertTrue(Shots.allText(tagged(a, "tips_tile_other")).startsWith(TipsPage.MORE + "\n"));
        assertEquals(TipsPage.MORE, Zh.plain(((TextView) tagged(a, "tips_content_more")).getText()));
        View tile = a.getWindow().getDecorView().findViewWithTag("tips_tile_dialogue");
        assertNotNull(tile);
        int manualLinks = 0;
        for (Rules.Link link : api.rules.links) if (link.kind == Rules.LinkKind.MANUAL) manualLinks++;
        assertTrue(Shots.allText(tile).contains(String.valueOf(manualLinks)));
        assertTrue(tile.performClick());
        Shots.idle();
        assertTrue("a tile selects its kind", tagged(a, "tips_content_dialogue").isSelected());
        ListView list = visibleList(a.getWindow().getDecorView());
        assertEquals("one row per social link, not one card per rank", manualLinks, list.getAdapter().getCount());
        View group = groupRow(a, "dialogue_priestess");
        View body = group.findViewWithTag("tips_group_body_dialogue_priestess");
        assertEquals(View.GONE, body.getVisibility());
        String header = Shots.allText(group.findViewWithTag("tips_group_header_dialogue_priestess"));
        assertTrue(header, header.contains("II"));
        assertTrue(header, header.contains("山岸风花"));
        assertFalse("no 「10 段剧情」 subtitle", header.contains("段剧情"));
        View headerView = group.findViewWithTag("tips_group_header_dialogue_priestess");
        assertTrue(headerView.performClick());
        Shots.idle();
        assertEquals(View.VISIBLE, body.getVisibility());
        a.reload();
        Shots.idle();
        group = groupRow(a, "dialogue_priestess");
        body = group.findViewWithTag("tips_group_body_dialogue_priestess");
        headerView = group.findViewWithTag("tips_group_header_dialogue_priestess");
        assertEquals("refresh preserves the open social link", View.VISIBLE, body.getVisibility());
        Shots.shot(a, "tips_social_ranks");
        View unlock = group.findViewWithTag("tips_entry_dialogue.priestess.1");
        assertEquals("解锁", Shots.allText(unlock).trim());
        View rankOne = group.findViewWithTag("tips_entry_dialogue.priestess.2");
        String rankText = Shots.allText(rankOne);
        assertTrue(rankText, rankText.contains("1→2"));
        assertFalse("answers stay in the detail, not spread across the results", rankText.contains("没问题"));
        View last = group.findViewWithTag("tips_entry_dialogue.priestess.10");
        assertTrue("RANK 10 is MAX", Shots.allText(last).contains("9→MAX"));
        assertTrue(last.getContentDescription().toString().contains("升到 MAX"));
        assertTrue(unlock.performClick());
        Shots.idle();
        String unlockText = Shots.allText(a.sheets().content());
        assertTrue(unlockText, unlockText.contains("解锁女教皇社群"));
        assertTrue("the title already says 解锁: no kind tag above it",
            Shots.findAll(a.sheets().content(), "社群解锁").isEmpty());
        assertTrue(unlockText, unlockText.contains("6月19日起"));
        assertTrue(unlockText, unlockText.contains("解锁条件"));
        assertTrue(unlockText, unlockText.contains("勇气 MAX、命运社群 RANK 1"));
        assertTrue(unlockText, unlockText.contains("放学后到教室前的走廊和山岸风花说话"));
        assertFalse(unlockText, unlockText.contains("前置条件"));
        Shots.shot(a, "tips_unlock_sheet");
        Shots.click(a, "知道了");
        assertTrue(rankOne.performClick());
        Shots.idle();
        assertTrue(Shots.allText(a.sheets().content()).contains("第 1 问\n没问题\n第 2 问\n随便选\n第 3 问\n没问题"));
        Shots.click(a, "知道了");
        assertTrue(headerView.performClick());
        assertEquals(View.GONE, body.getVisibility());
        assertTrue(api.calls.isEmpty());
        Shots.shot(a, "tips_social_groups");
    }

    @Test
    public void theRecordedRankLightsTheNextSceneAndItsAnswers() throws Exception {
        Rules rules = realRules();
        api.rules = rules;
        api.snap.observed = Fixtures.observedMid(rules);
        int priestess = rules.linkIndex("priestess");
        api.snap.observed.rank[priestess] = 3;
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        click(a, "tips_content_dialogue");
        assertTrue("社群 shows every link, also in 本期", tagged(a, "tips_scope_period").isSelected());
        View group = groupRow(a, "dialogue_priestess");
        String header = Shots.allText(group.findViewWithTag("tips_group_header_dialogue_priestess"));
        assertTrue(header, header.contains("RANK 3"));
        View next = group.findViewWithTag("tips_next_priestess");
        assertNotNull("the next rank-up is one line under the link", next);
        String line = Shots.allText(next);
        assertTrue(line, line.contains("下一次 RANK 3 → 4\n回答："));
        assertTrue(next.getContentDescription().toString().startsWith("下一次 RANK 3 → 4 · 回答："));
        assertTrue(group.findViewWithTag("tips_group_header_dialogue_priestess").performClick());
        Shots.idle();
        View played = group.findViewWithTag("tips_entry_dialogue.priestess.3");
        View lit = group.findViewWithTag("tips_entry_dialogue.priestess.4");
        assertTrue("played ranks are dimmed", played.getAlpha() < 1f);
        assertEquals(1f, lit.getAlpha(), 0.001f);
        assertTrue(lit.getContentDescription().toString().contains("下一次"));
        Shots.shot(a, "tips_social_next_rank");
        assertTrue(next.performClick());
        Shots.idle();
        assertTrue(Shots.allText(a.sheets().content()).contains("RANK 3 → 4"));
        assertTrue(api.calls.isEmpty());
        assertEquals(3, api.snap.observed.rank[priestess]);
    }

    @Test
    public void theRankSheetPreviewsTheOtherRouteWithoutSavingTheProfile() throws Exception {
        Rules rules = realRules();
        api.rules = rules;
        api.snap.observed = null;
        api.snap.plan = null;
        api.snap.planCurrent = false;
        int priestess = rules.linkIndex("priestess");
        HomeActivity a = tips();
        EditText search = a.getWindow().getDecorView().findViewWithTag("tips_search");
        search.setText("风花R6");
        Shots.idle();
        assertTrue(rowForId(a, "dialogue.priestess.7").performClick());
        Shots.idle();
        View sheet = a.sheets().content();
        SegmentedControl route = sheet.findViewWithTag("tips_route_toggle");
        assertNotNull("a link that can be romanced shows [朋友｜恋人]", route);
        assertEquals(0, route.getSelected());
        String friend = Shots.allText(sheet);
        assertTrue(friend, friend.contains("一定是这样"));
        assertTrue(route.segment(1).performClick());
        Shots.idle();
        String romance = Shots.allText(a.sheets().content());
        assertTrue(romance, romance.contains("真的是这样吗"));
        Shots.shot(a, "tips_rank_sheet_route_preview");
        assertTrue("previewing never saves the profile", api.calls.isEmpty());
        assertTrue(api.snap.profile == null || api.snap.profile.route[priestess] != Profile.Route.ROMANCE);
    }

    @Test
    public void monadGroupsExposeEveryDoorAndPassageInsideTheirNamedFloorRange() throws Exception {
        api.rules = realRules();
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        click(a, "tips_scope_all");
        click(a, "tips_content_combat");
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
        assertEquals(5, taggedChildren(body, "tips_entry_"));
        String bodyText = Shots.allText(body);
        assertTrue("doors and passages under their own mini headers: " + bodyText, bodyText.contains("蒙纳多通道 · 91F"));
        View door = group.findViewWithTag("tips_entry_combat.catalogue.combat.yabbashah_1.d1");
        String doorText = Shots.allText(door);
        assertTrue(doorText, doorText.contains("优雅圣母"));
        assertTrue("official element names: " + doorText, doorText.contains("火焰"));
        assertFalse("the door row does not repeat its kind", doorText.contains("蒙纳多之门"));
        View passage = group.findViewWithTag("tips_entry_combat.catalogue.combat.yabbashah_1.p2");
        assertTrue(Shots.allText(passage).contains("第 2 战"));
        Shots.shot(a, "tips_monad_group");
        assertTrue(passage.performClick());
        Shots.idle();
        assertNotNull(a.sheets().content().findViewWithTag("combat_affinity_scroll"));
        String sheet = Shots.allText(a.sheets().content());
        assertTrue(sheet, sheet.contains("暴走驱动器"));
        assertTrue(sheet, sheet.contains("鄙俗之庭 亚巴扎 · 70–92F"));
        assertFalse(sheet, sheet.contains("战斗详情"));
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
    public void theContentStripIsOneTapButtonsAndMoreIsASingleTapList() {
        HomeActivity a = tips();
        for (String key : new String[]{"all", "dialogue", "class", "request", "combat", "more"}) {
            TextView chip = (TextView) tagged(a, "tips_content_" + key);
            Shots.render(a.getWindow().getDecorView()).recycle();
            assertTrue(key + " chip is a full target", chip.getHeight() >= Ui.dp(a, 40));
        }
        assertEquals("全部", Zh.plain(((TextView) tagged(a, "tips_content_all")).getText()));
        assertEquals(TipsPage.MORE, Zh.plain(((TextView) tagged(a, "tips_content_more")).getText()));
        assertNull("no filter icon or 应用 any more", a.getWindow().getDecorView().findViewWithTag("tips_filter_button"));
        click(a, "tips_content_more");
        assertTrue(a.sheets().isShowing());
        String sheet = Shots.allText(a.sheets().content());
        assertFalse(sheet, sheet.contains("应用"));
        assertFalse(sheet, sheet.contains("重置"));
        assertFalse("the strip's kinds are not listed again", sheet.contains("社群对话\n"));
        Shots.shot(a, "tips_more_sheet");
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
        request.detailZh = "开放：10月6日起\n前置：完成第66号\n先接委托，到学校教职员室分 8 天触发对话，再拿物品交付。\n奖励：黄昏的碎片×7";
        request.fromDay = Fixtures.day(10, 6);
        api.rules.reminders.requestTips.add(request);
        api.snap.observed = Fixtures.observedMid(api.rules);
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        click(a, "tips_content_request");
        assertTrue(tagged(a, "tips_scope_period").isSelected());
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
        assertTrue("labelled rows: " + detail, detail.contains("开放\n10月6日起"));
        assertTrue(detail, detail.contains("前置\n完成第66号"));
        assertTrue(detail, detail.contains("怎么做\n"));
        assertTrue(detail, detail.contains("奖励\n黄昏的碎片×7"));
        assertFalse(detail, detail.contains("未自动追踪"));
        Shots.shot(a, "tips_request_detail");
        assertTrue(api.calls.isEmpty());
        assertEquals(Fixtures.SLOT_NOW, api.snap.observed.slot);
    }

    @Test
    public void combatStripFindsExactFloorsAndFullMoonMonthsAndOpensWeaknessAndTactics() throws Exception {
        api.rules = realRules();
        api.rules.reminders.combatTips.clear();
        Rules.CombatTip guardian = new Rules.CombatTip();
        guardian.id = "guardian.36";
        guardian.kind = "guardian";
        guardian.titleZh = "36层守门：守关首领";
        guardian.floor = 36;
        guardian.fromDay = Fixtures.day(6, 13);
        guardian.shortZh = "弱点：冰";
        guardian.detailZh = "弱点：冰；火无效。\n先处理召唤的小怪。";
        guardian.tacticsZh = "先处理召唤的小怪，用火炎以外的属性。";
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
        click(a, "tips_content_combat");
        assertTrue(tagged(a, "tips_scope_period").isSelected());
        EditText search = a.getWindow().getDecorView().findViewWithTag("tips_search");
        search.setText("第36层");
        Shots.idle();
        View row = rowForId(a, "combat.catalogue.guardian.36");
        String card = Shots.allText(row);
        assertTrue(card, card.contains("36F"));
        assertTrue("the boss, not the pack's 「36层守门：」: " + card, card.contains("守关首领"));
        assertFalse(card, card.contains("36层守门"));
        assertTrue(card, card.contains("冰冻"));
        assertFalse("no instructional caption", card.contains("查看属性与打法"));
        Shots.shot(a, "tips_combat_floor_search");
        assertTrue(row.performClick());
        Shots.idle();
        String detail = Shots.allText(a.sheets().content());
        android.widget.TextView fire = a.sheets().content().findViewWithTag("combat_affinity_0_3");
        assertEquals("无", Zh.plain(fire.getText()));
        assertTrue(detail, detail.contains("先处理召唤的小怪"));
        assertTrue("official element names in the tactics: " + detail, detail.contains("火焰以外的属性"));
        Shots.shot(a, "tips_combat_detail");
        Shots.click(a, "知道了");
        search.setText("9月 满月");
        Shots.idle();
        assertNotNull(rowForId(a, "combat.catalogue.fullmoon.hermit"));
        assertTrue(api.calls.isEmpty());
        assertEquals(Fixtures.SLOT_NOW, api.snap.observed.slot);
    }

    @Test
    public void searchingLooksEverywhereAndClearingItRestoresThePeriod() {
        reminders();
        api.snap.plan = null;
        api.snap.planCurrent = false;
        HomeActivity a = tips();
        List<String> initial = resultIds(a);
        click(a, "tips_content_request");
        assertTrue("a strip chip applies at once and keeps the scope", tagged(a, "tips_scope_period").isSelected());
        List<String> periodRequests = resultIds(a);
        assertFalse(periodRequests.contains("request.other"));
        assertTrue(initial.containsAll(periodRequests));
        EditText search = a.getWindow().getDecorView().findViewWithTag("tips_search");
        search.setText("下个区间");
        Shots.idle();
        assertTrue("searching looks at the whole game", tagged(a, "tips_scope_all").isSelected());
        assertTrue(resultIds(a).contains("request.other"));
        String counter = Zh.plain(((TextView) tagged(a, "tips_result_counter")).getText());
        assertEquals("找到 1 条", counter);
        View clear = a.getWindow().getDecorView().findViewWithTag("tips_search_clear");
        assertEquals(View.VISIBLE, clear.getVisibility());
        assertTrue(clear.performClick());
        Shots.idle();
        assertEquals("", search.getText().toString());
        assertTrue("clearing the search brings back 本期", tagged(a, "tips_scope_period").isSelected());
        assertEquals(periodRequests, resultIds(a));
        assertEquals(View.GONE, tagged(a, "tips_result_counter").getVisibility());
        click(a, "tips_content_all");
        assertEquals(initial, resultIds(a));
        click(a, "tips_scope_all");
        assertNotNull("全部 with all kinds is the directory", a.getWindow().getDecorView().findViewWithTag("tips_tile_request"));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void rareTagsComeFromMoreWithOneTapAndShowAsARemovablePill() {
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
        more(a, "失踪者");
        assertFalse("one tap applies and closes", a.sheets().isShowing());
        assertTrue(tagged(a, "tips_scope_period").isSelected());
        assertTrue(tagged(a, "tips_content_more").isSelected());
        assertEquals("失踪者", Zh.plain(((TextView) tagged(a, "tips_filter_selection")).getText()));
        // The ✕ pill is a full chip target, and says what a tap does.
        Shots.render(a.getWindow().getDecorView()).recycle();
        assertTrue(tagged(a, "tips_filter_selection").getHeight() >= Ui.dp(a, 40));
        assertEquals("去掉筛选：失踪者", tagged(a, "tips_filter_selection").getContentDescription().toString());
        View moreChip = tagged(a, "tips_content_more");
        View strip = (View) moreChip.getParent().getParent();
        assertTrue("the lit 其他 chip is scrolled into view", moreChip.getRight() - strip.getScrollX() <= strip.getWidth());
        assertAllLabels(a, "失踪者");
        List<String> rescues = resultIds(a);
        assertTrue(initial.containsAll(rescues));
        Shots.shot(a, "tips_more_rescue");

        more(a, "社群解锁");
        assertEquals("社群解锁", Zh.plain(((TextView) tagged(a, "tips_filter_selection")).getText()));
        assertAllLabels(a, "社群解锁");
        assertNotNull(rowForId(a, unlock.id));
        for (String id : rescues) assertFalse(resultIds(a).contains(id));
        Shots.shot(a, "tips_more_unlocks");
        assertTrue(tagged(a, "tips_filter_selection").performClick());
        Shots.idle();
        assertEquals(View.GONE, tagged(a, "tips_filter_selection").getVisibility());
        assertEquals(initial, resultIds(a));
        assertTrue(api.calls.isEmpty());
        assertEquals(Fixtures.SLOT_NOW, api.snap.observed.slot);
    }

    /** Opens the folded 已经过去 bar (it may be below the visible rows); false when there is none. */
    private static boolean openPast(HomeActivity a) {
        ListView list = visibleList(a.getWindow().getDecorView());
        assertNotNull(list);
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            View bar = list.getAdapter().getView(i, null, list).findViewWithTag("tips_past_bar");
            if (bar != null) {
                assertTrue(bar.getContentDescription().toString().endsWith("展开"));
                assertTrue(bar.performClick());
                Shots.idle();
                return true;
            }
        }
        return false;
    }

    private static void more(HomeActivity a, String label) {
        click(a, "tips_content_more");
        assertTrue("更多 is open", a.sheets().isShowing());
        View row = a.sheets().content().findViewWithTag("tips_more_" + label);
        assertNotNull("更多 lists " + label, row);
        assertTrue(row.performClick());
        Shots.idle();
    }

    private static View tagged(HomeActivity a, String tag) {
        View view = a.getWindow().getDecorView().findViewWithTag(tag);
        assertNotNull(tag, view);
        return view;
    }

    private static void click(HomeActivity a, String tag) {
        assertTrue(tag, tagged(a, tag).performClick());
        Shots.idle();
    }

    private static int taggedChildren(View view, String prefix) {
        int count = view.getTag() instanceof String && ((String) view.getTag()).startsWith(prefix) ? 1 : 0;
        if (count == 0 && view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += taggedChildren(group.getChildAt(i), prefix);
        }
        return count;
    }

    private static String listText(HomeActivity a) {
        ListView list = visibleList(a.getWindow().getDecorView());
        assertNotNull(list);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            all.append(Shots.allText(list.getAdapter().getView(i, null, list)));
        }
        return all.toString();
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

    /** The row of {@code id}: a list row, or the tile/row inside its group. */
    private static View rowForId(HomeActivity a, String id) {
        ListView list = visibleList(a.getWindow().getDecorView());
        assertNotNull(list);
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.reminder != null && id.equals(item.reminder.id)) {
                View view = list.getAdapter().getView(i, null, list);
                View row = view.findViewWithTag("tips_row_" + id);
                return row != null ? row : view;
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
