package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class PeriodTipsTest {
    private static Rules rules() {
        Rules rules = new Rules();
        rules.firstDay = 20;
        rules.lastDay = 90;
        rules.days = new Rules.Day[91];
        for (int day = 20; day <= 90; day++) {
            Rules.Day date = new Rules.Day();
            LocalDate actual = LocalDate.of(2009, 4, 1).plusDays(day);
            date.md = String.format("%02d-%02d", actual.getMonthValue(), actual.getDayOfMonth());
            rules.days[day] = date;
        }
        rules.blocks = new Rules.Block[]{block(20, "首期"), block(39, "第二期"), block(73, "第三期")};
        Rules.Stat courage = new Rules.Stat();
        courage.idx = 2;
        courage.id = "courage";
        courage.zh = "勇气";
        Rules.Stat academics = new Rules.Stat();
        academics.idx = 0;
        academics.id = "academics";
        academics.zh = "学力";
        Rules.Stat charm = new Rules.Stat();
        charm.idx = 1;
        charm.id = "charm";
        charm.zh = "魅力";
        rules.stats = new Rules.Stat[]{academics, charm, courage};
        return rules;
    }

    private static Rules.Block block(int from, String title) {
        Rules.Block block = new Rules.Block();
        block.fromDay = from;
        block.toDay = from; // Period ends at the next opening, independent of a stale toDay field.
        block.zh = title;
        block.floorsZh = "2–22F";
        return block;
    }

    private static SimState at(int day, boolean evening) {
        SimState state = new SimState();
        state.slot = Rules.slot(day, evening);
        return state;
    }

    private static Rules.Reminder reminder(String id, String kind, int day, int until) {
        Rules.Reminder item = new Rules.Reminder();
        item.id = id;
        item.kind = kind;
        item.labelZh = "提示";
        item.zh = "原始正文";
        item.detailZh = "原始完整攻略与步骤";
        item.day = day;
        item.untilDay = until;
        return item;
    }

    private static Plan.Reminder find(PeriodTips.Period period, String id) {
        for (Plan.Reminder item : period.items) if (id.equals(item.id)) return item;
        return null;
    }

    @Test public void observedGameDateSelectsOpeningPeriodIncludingBoundariesAndFirstFallback() {
        Rules rules = rules();
        Plan farFuture = new Plan();
        farFuture.fromSlot = Rules.slot(80, false);
        PeriodTips.Period before = PeriodTips.collect(rules, at(38, true), farFuture);
        assertEquals(20, before.fromDay);
        assertEquals(38, before.toDay);
        assertEquals("首期", before.blockZh);
        PeriodTips.Period opening = PeriodTips.collect(rules, at(39, false), farFuture);
        assertEquals(39, opening.fromDay);
        assertEquals(72, opening.toDay);
        assertEquals("第二期", opening.blockZh);
        assertEquals(20, PeriodTips.collect(rules, at(0, false), null).fromDay);
        assertEquals(20, PeriodTips.collect(rules, null, null).fromDay);
        assertEquals(90, PeriodTips.collect(rules, at(91, false), null).toDay);
    }

    @Test public void preOpeningCalendarIsItsOwnPeriodContainingTheObservedGameDay() {
        Rules rules = rules();
        rules.firstDay = 7;
        PeriodTips.Period period = PeriodTips.collect(rules, at(10, true), null);
        assertEquals(7, period.fromDay);
        assertEquals(19, period.toDay);
        assertEquals("探索开放前", period.blockZh);
        assertTrue(Rules.dayOf(period.observedSlot) >= period.fromDay);
        assertTrue(Rules.dayOf(period.observedSlot) <= period.toDay);
    }

    @Test public void wholePeriodIncludesPastAndFuturePlusOverlappingWindowsOnly() {
        Rules rules = rules();
        rules.reminders.items.add(reminder("past.class", "class", 40, -1));
        rules.reminders.items.add(reminder("future.tv", "tv", 70, -1));
        rules.reminders.items.add(reminder("carry.request", "request", 35, 45));
        rules.reminders.items.add(reminder("expired.request", "request", 35, 38));
        rules.reminders.items.add(reminder("old.request", "request", 35, -1));
        rules.reminders.items.add(reminder("next.request", "request", 73, 90));
        PeriodTips.Period period = PeriodTips.collect(rules, at(50, true), null);
        assertNotNull(find(period, "past.class"));
        assertNotNull(find(period, "future.tv"));
        Plan.Reminder carried = find(period, "carry.request");
        assertNotNull(carried);
        assertEquals(Rules.slot(35, false), carried.slot);
        assertEquals(45, carried.untilDay);
        assertEquals("原始完整攻略与步骤", carried.detailZh);
        assertNull(find(period, "expired.request"));
        assertNull(find(period, "old.request"));
        assertNull(find(period, "next.request"));
    }

    @Test public void unknownFirstEligiblePickupStaysConditionalAndCanCarryAcrossPeriods() {
        Rules rules = rules();
        Rules.Reminder pickup = reminder("fragment.club", "fragment", 35, -1);
        pickup.firstEligible = true;
        pickup.zh = "勇气到 Lv.2 了";
        pickup.shortZh = "放纵俱乐部：黄昏的碎片";
        pickup.requiresLv.put(2, 2);
        rules.reminders.items.add(pickup);
        PeriodTips.Period period = PeriodTips.collect(rules, at(50, false), null);
        Plan.Reminder item = find(period, pickup.id);
        assertNotNull(item);
        assertTrue(period.ongoingIds.contains(pickup.id));
        assertFalse(item.textZh.contains("勇气到"));
        assertTrue(item.detailZh.contains("勇气 Lv.2"));
        assertTrue(item.detailZh.contains("原始完整攻略与步骤"));
        assertEquals("勇气到 Lv.2 了", pickup.zh);
        Plan forecast = new Plan();
        Plan.Reminder predictedPickup = new Plan.Reminder();
        predictedPickup.id = pickup.id;
        predictedPickup.slot = Rules.slot(80, true); // A forecast date even outside this period cannot move the opening.
        predictedPickup.textZh = "预测已达到勇气 Lv.2";
        forecast.reminders.add(predictedPickup);
        Plan.Reminder withForecast = find(PeriodTips.collect(rules, at(50, false), forecast), pickup.id);
        assertEquals(item.slot, withForecast.slot);
        assertEquals(item.textZh, withForecast.textZh);
        assertTrue(withForecast.detailZh.contains("勇气 Lv.2"));
        assertTrue(withForecast.detailZh.contains("安排：6月20日处理"));
        pickup.requiresLv.put(2, 6);
        assertTrue(find(PeriodTips.collect(rules, at(50, false), null), pickup.id).detailZh.contains("勇气 MAX"));
    }

    @Test public void forecastRefinesSameIdWithoutDuplicatingOrMutatingInputs() {
        Rules rules = rules();
        Rules.Reminder original = reminder("tv.future", "tv", 60, -1);
        rules.reminders.items.add(original);
        rules.reminders.dozeDays = new int[]{40, 60, 73};
        Plan plan = new Plan();
        Plan.Reminder forecast = new Plan.Reminder();
        forecast.id = "tv.future";
        forecast.slot = Rules.slot(60, false);
        forecast.kind = "tv";
        forecast.textZh = "预计现金不够";
        plan.reminders.add(forecast);
        Plan.Reminder doze = new Plan.Reminder();
        doze.id = "doze." + rules.days[60].md;
        doze.kind = "doze";
        doze.slot = Rules.slot(60, false);
        doze.textZh = "学力已满，闭眼加勇气";
        plan.reminders.add(doze);
        PeriodTips.Period period = PeriodTips.collect(rules, at(50, false), plan);
        assertEquals(3, period.items.size());
        assertTrue(find(period, "doze." + rules.days[40].md).textZh.contains("不闭眼"));
        assertEquals(doze.textZh, find(period, doze.id).textZh);
        assertEquals(forecast.textZh, find(period, forecast.id).textZh);
        find(period, forecast.id).textZh = "UI 修改副本";
        assertEquals("预计现金不够", forecast.textZh);
        assertEquals("原始正文", original.zh);
    }

    @Test public void rescueKeepsFullWindowNamesAndConfirmedStatusWithoutInventingOtherCompletions() {
        Rules rules = rules();
        Rules.Rescue person = new Rules.Rescue();
        person.id = "known";
        person.zh = "已知姓名";
        rules.rescues = new Rules.Rescue[]{person};
        Rules.RescueGroup group = new Rules.RescueGroup();
        group.id = "rescue.group";
        group.fromDay = 35;
        group.toDay = 60;
        group.count = 3;
        group.rescueIds.add(person.id);
        group.locksZh = "错过后第 97 号委托无法完成。";
        rules.reminders.rescueGroups.add(group);
        SimState state = at(50, false);
        state.rescued = 1;
        state.lastTowerSlot = Rules.slot(40, true);
        Plan plan = new Plan();
        Plan.Reminder duplicate = new Plan.Reminder();
        duplicate.id = group.id + ".start";
        duplicate.kind = Plan.Reminder.RESCUE;
        duplicate.slot = state.slot;
        plan.reminders.add(duplicate);
        PeriodTips.Period period = PeriodTips.collect(rules, state, plan);
        assertEquals(1, period.items.size());
        Plan.Reminder item = period.items.get(0);
        assertTrue(item.textZh.contains("3 人（包括已知姓名）"));
        assertTrue(item.detailZh.contains("已确认救出已知姓名"));
        assertTrue(item.detailZh.contains("第 97 号委托"));
        assertFalse(item.detailZh.contains("已救出 3"));
        assertEquals(60, item.untilDay);
        assertEquals(Rules.slot(35, false), item.slot);
    }

    @Test public void plannedRescueOnLimitEveningIsAdviceAndNotConfirmedCompletion() {
        Rules rules = rules();
        Rules.RescueGroup group = new Rules.RescueGroup();
        group.id = "rescue.limit";
        group.fromDay = 45;
        group.toDay = 60;
        group.count = 2;
        rules.reminders.rescueGroups.add(group);
        rules.reminders.nurseCourage = 2;
        SimState observed = at(50, false);
        Plan plan = new Plan();
        plan.fromSlot = observed.slot;
        plan.toSlot = Rules.slot(60, true);
        for (int slot = plan.fromSlot; slot <= plan.toSlot; slot++) {
            Plan.Step step = new Plan.Step();
            step.kind = slot == plan.toSlot ? "tower" : "free";
            plan.steps.add(step);
        }
        PeriodTips.Period period = PeriodTips.collect(rules, observed, plan);
        String detail = find(period, group.id).detailZh;
        assertTrue(detail.contains("安排：5月31日进塔"));
        assertTrue(detail.contains("最晚在期限当天晚上救出"));
        assertEquals(0L, observed.rescued);
        assertTrue(period.ongoingIds.contains("period.nurse"));
        assertEquals(-1, find(period, "period.nurse").untilDay);
    }

    @Test public void optionalActivitiesKeepPastAndFuturePeriodDatesAndOneDormOverviewWithRealCounts() {
        Rules rules = rules();
        Rules.Activity dvd = new Rules.Activity();
        dvd.idx = 0;
        dvd.id = "dorm_a_dvd";
        dvd.zh = "和队友甲看DVD";
        dvd.dormPartnerZh = "队友甲";
        dvd.fixedDays = new int[]{38, 42, 68, 73};
        dvd.gain[1] = 2;
        Rules.Activity gardening = new Rules.Activity();
        gardening.idx = 1;
        gardening.id = "dorm_b_gardening";
        gardening.zh = "和队友乙种菜";
        gardening.dormPartnerZh = "队友乙";
        gardening.fixedDays = new int[]{70, 74};
        gardening.requiresPlantedCrop = true;
        gardening.noteZh = "种苗另需准备。";
        Rules.Activity nextPeriod = new Rules.Activity();
        nextPeriod.idx = 2;
        nextPeriod.id = "dorm_future";
        nextPeriod.dormPartnerZh = "下期队友";
        nextPeriod.fixedDays = new int[]{74};
        Rules.Activity study = new Rules.Activity();
        study.idx = 3;
        study.id = "group_study_fixed";
        study.zh = "宿舍集体学习";
        study.fixedDays = new int[]{40, 72, 73};
        study.gain[0] = 4;
        study.noteZh = "考前限定。";
        rules.activities = new Rules.Activity[]{dvd, gardening, nextPeriod, study};
        SimState observed = at(65, false);
        observed.used = new int[]{2, 3, 1, 0};
        PeriodTips.Period period = PeriodTips.collect(rules, observed, null);
        assertEquals(3, period.items.size());
        Plan.Reminder overview = find(period, "period.dorm");
        assertNotNull(overview);
        assertTrue(period.ongoingIds.contains(overview.id));
        assertTrue(overview.textZh.contains("2 位队友"));
        assertTrue(overview.detailZh.contains("5月13日、6月8日"));
        assertTrue(overview.detailZh.contains("6月10日"));
        assertFalse(overview.detailZh.contains("5月9日"));
        assertFalse(overview.detailZh.contains("6月13日"));
        assertFalse(overview.detailZh.contains("下期队友"));
        assertTrue(overview.detailZh.contains("已记录 2 次（特性进度 2/3）"));
        assertTrue(overview.detailZh.contains("已记录 3 次（特性进度 3/3）"));
        assertTrue(overview.detailZh.contains("魅力 +2"));
        assertTrue(overview.detailZh.contains("两个项目各完成 3 次"));
        assertTrue(overview.detailZh.contains("允许安排园艺"));
        Plan.Reminder past = find(period, "activity.group_study_fixed.40");
        assertNotNull(past);
        assertEquals(Rules.slot(40, true), past.slot);
        assertTrue(past.textZh.contains("学力 +4"));
        assertTrue(past.detailZh.contains("占用整个晚上"));
        assertTrue(past.detailZh.contains("学力已满可跳过"));
        assertNotNull(find(period, "activity.group_study_fixed.72"));
        assertNull(find(period, "activity.group_study_fixed.73"));
        assertEquals(2, observed.used[0]);
    }

    @Test public void unlockMethodsAndEpisodeWindowsAreShownWithoutAnyPlannedSelectionOrAssumedPrerequisites() {
        Rules rules = rules();
        Rules.Link prerequisite = new Rules.Link();
        prerequisite.idx = 0;
        prerequisite.id = "prerequisite";
        prerequisite.arcanaZh = "前置";
        prerequisite.unlock.earliestDay = 20;
        Rules.Link link = new Rules.Link();
        link.idx = 1;
        link.id = "current";
        link.labelZh = "本期社群";
        link.unlock.earliestDay = 42;
        link.unlock.statLv.put(2, 6);
        link.unlock.linkRank.put(0, 4);
        link.unlock.noteZh = "考试至少拿过一次第一名。";
        Rules.Step step = new Rules.Step();
        step.howZh = "先到指定地点选「说话」。";
        step.cost = 1000;
        step.consumesSlot = false;
        link.unlock.steps.add(step);
        rules.links = new Rules.Link[]{prerequisite, link};
        Rules.Episode episode = new Rules.Episode();
        episode.idx = 0;
        episode.id = "track";
        episode.titleZh = "羁绊剧情 · 队友";
        episode.personZh = "队友";
        episode.count = 2;
        episode.howZh = "晚上打开手机答应邀请。";
        episode.noteZh = "需要预先保留重要物品。";
        episode.partRewardZh = new String[]{"魅力 +2", "合体解锁"};
        episode.dayMask = new int[91];
        episode.dayMask[38] = 1;
        episode.dayMask[45] = 1;
        episode.dayMask[70] = 1;
        episode.dayMask[74] = 1;
        episode.dayMask[75] = 2;
        rules.episodes = new Rules.Episode[]{episode};
        Rules.DatingNote dating = new Rules.DatingNote();
        dating.fromDay = 55;
        dating.cost = 3000;
        dating.zh = "交友网站笔记";
        dating.howZh = "在俱乐部购买后回宿舍电脑使用。";
        dating.noteZh = "不会自行提升社群等级。";
        dating.requiresLv.put(2, 2);
        rules.datingNote = dating;
        SimState observed = at(60, false);
        observed.episodes = new int[]{1};
        PeriodTips.Period period = PeriodTips.collect(rules, observed, null);
        Plan.Reminder unlock = find(period, "unlock.current");
        assertNotNull(unlock);
        assertTrue(unlock.textZh.contains("可尝试解锁"));
        assertTrue(unlock.detailZh.contains("勇气 MAX、前置社群 RANK 4"));
        assertTrue(unlock.detailZh.contains("指定地点选「说话」"));
        assertTrue(unlock.detailZh.contains("不消耗时间；花费 1000 日元"));
        assertTrue(unlock.detailZh.contains("考试至少拿过一次第一名"));
        assertTrue(unlock.detailZh.contains("最早5月13日开放"));
        assertNull(find(period, "unlock.prerequisite"));
        Plan.Reminder part = find(period, "episode.track.1");
        assertNotNull(part);
        assertEquals(74, part.untilDay);
        assertTrue(part.detailZh.contains("本期可看日期：5月16日、6月10日"));
        assertTrue(part.detailZh.contains("最后可看日期：6月14日"));
        assertTrue(part.detailZh.contains("魅力 +2"));
        assertTrue(part.detailZh.contains("当前记录已看 1 话"));
        assertTrue(part.detailZh.contains("勾选对应的「羁绊剧情」"));
        assertNull(find(period, "episode.track.2"));
        assertTrue(find(period, "feature.dating_note").textZh.contains("3000 日元，占用晚上"));
        assertTrue(find(period, "feature.dating_note").detailZh.contains("不会自行提升社群等级"));
        assertTrue(find(period, "feature.dating_note").detailZh.contains("勇气 Lv.2"));
        assertEquals(0, observed.rank[1]);
    }

    @Test public void calendarKeepsResultNotesAndMergesOnlyConsecutiveMatchingReasonAndSlots() {
        Rules rules = rules();
        rules.lockReasonsZh.put("exam", "考试期间");
        rules.lockReasonsZh.put("full_moon", "满月作战");
        for (int day = 40; day <= 42; day++) {
            rules.days[day].dayLock = "exam";
            rules.days[day].eveLock = "exam";
        }
        rules.days[43].dayLock = "exam";
        rules.days[50].noteZh = "期中考试放榜";
        rules.days[60].dayLock = "full_moon";
        rules.days[60].eveLock = "full_moon";
        rules.days[60].fullMoon = true;
        PeriodTips.Period period = PeriodTips.collect(rules, at(55, false), null);
        assertEquals(4, period.items.size());
        Plan.Reminder exams = find(period, "calendar.lock.exam.3.40");
        assertNotNull(exams);
        assertEquals(42, exams.untilDay);
        assertEquals(Integer.valueOf(Rules.slot(42, true)), period.calendarEndSlots.get(exams.id));
        assertEquals(Integer.valueOf(Rules.slot(43, false)), period.calendarEndSlots.get("calendar.lock.exam.1.43"));
        assertTrue(exams.textZh.contains("5月11日～5月13日（白天和晚上）"));
        assertTrue(find(period, "calendar.lock.exam.1.43").textZh.contains("白天"));
        assertEquals("期中考试放榜", find(period, "calendar.note.50").textZh);
        assertNotNull(find(period, "calendar.lock.full_moon.3.60"));
        assertNull(find(period, "calendar.full_moon.60"));
    }

    @Test public void knownUntimedRequestsCarryAcrossPeriodsWithoutCarryingOrdinaryOldAnnouncements() throws Exception {
        Rules rules = realPack();
        PeriodTips.Period june = PeriodTips.collect(rules, at(80, true), null);
        assertNotNull(find(june, "request.05-29.outings"));
        assertTrue(june.ongoingIds.contains("request.05-29.outings"));
        assertNull(find(june, "request.05-10"));
        PeriodTips.Period december = PeriodTips.collect(rules, at(250, true), null);
        Plan.Reminder faculty = find(december, "request.10-06.faculty");
        assertNotNull(faculty);
        assertTrue(faculty.detailZh.contains("分 8 天"));
        assertTrue(december.ongoingIds.contains(faculty.id));
        assertNull(find(december, "request.10-07.faculty"));
        assertNull(find(december, "request.11-01.deadline"));
    }

    @Test public void everyRealPackPeriodPreservesEveryStaticDateAndEveryRescueGroup() throws Exception {
        Rules rules = realPack();
        List<Integer> starts = new ArrayList<>();
        starts.add(rules.firstDay);
        for (Rules.Block block : rules.blocks) starts.add(block.fromDay);
        Set<String> allAnswers = new HashSet<>();
        for (int start : starts) {
            PeriodTips.Period period = PeriodTips.collect(rules, at(start + 5, true), null);
            Set<String> ids = new HashSet<>();
            int previous = Integer.MIN_VALUE;
            for (Plan.Reminder item : period.items) {
                assertTrue("unique " + item.id, ids.add(item.id));
                assertTrue("date order " + item.id, item.slot >= previous);
                previous = item.slot;
                if (item.isAnswer()) allAnswers.add(item.id);
            }
            for (Rules.Reminder item : rules.reminders.items) {
                if (item.day >= period.fromDay && item.day <= period.toDay) assertNotNull(item.id, find(period, item.id));
            }
            for (Rules.RescueGroup group : rules.reminders.rescueGroups) {
                if (group.fromDay <= period.toDay && group.toDay >= period.fromDay) assertNotNull(group.id, find(period, group.id));
            }
            for (Rules.Link link : rules.links) {
                if (link.unlock.earliestDay >= period.fromDay && link.unlock.earliestDay <= period.toDay) {
                    assertNotNull(link.id, find(period, "unlock." + link.id));
                }
            }
            for (Rules.Episode track : rules.episodes) {
                for (int part = 1; part <= track.count; part++) {
                    boolean available = false;
                    for (int day = period.fromDay; day <= period.toDay; day++) available |= track.allows(day, part);
                    if (available) assertNotNull(track.id + "." + part, find(period, "episode." + track.id + "." + part));
                }
            }
        }
        assertEquals("all 36 classroom and 17 exam answers, including the pre-opening calendar", 53, allAnswers.size());
    }

    private static Rules realPack() throws Exception {
        Path pack = Path.of("src/main/assets/p3r/p3r_rules.json");
        if (!Files.exists(pack)) pack = Path.of("app", pack.toString());
        return RulesLoader.parse(new String(Files.readAllBytes(pack), StandardCharsets.UTF_8));
    }
}
