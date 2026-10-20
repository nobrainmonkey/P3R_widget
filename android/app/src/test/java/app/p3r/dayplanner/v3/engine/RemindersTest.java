package app.p3r.dayplanner.v3.engine;

import static app.p3r.dayplanner.v3.engine.ReminderFixtures.find;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 当日提醒 in the engine (docs/DESIGN_v3.md §17.1): static lines placed on the plan, 上课犯困, 保健室 and
 * 失踪者 worked out from the forecast, their order, and that the plan itself never depends on them. The
 * fixture plans are assembled from explicit codes ({@link ReminderFixtures}); the last tests run the real pack.
 */
public class RemindersTest {
    private static final int COURAGE = RulesFixtures.COURAGE;

    private static int day(int d) {
        return Rules.slot(d, false);
    }

    private static int eve(int d) {
        return Rules.slot(d, true);
    }

    private static Plan freePlan(Rules r, SimState start) {
        return ReminderFixtures.plan(r, start, ReminderFixtures.codes(r, start, Collections.emptyList(), -1, 0), -1);
    }

    // ------------------------------------------------------------------ 1. window and fromSlot

    @Test
    public void staticLinesFromThePlanStartToTheEndOfTheCalendarOnly() {
        Rules r = ReminderFixtures.rules();
        Plan fromStart = freePlan(r, ReminderFixtures.start(r, 20, 1, 1, 1));
        Plan.Reminder answer = find(fromStart, "class.04-23");
        assertNotNull(answer);
        assertEquals(day(ReminderFixtures.CLASS_DAY), answer.slot);
        assertEquals("课堂提问", answer.labelZh);
        assertEquals("课堂提问：选「马」（第 2 项）", answer.textZh);
        assertEquals("课堂：马（第 2 项）", answer.shortZh);
        assertEquals("马", answer.answerZh);
        assertEquals(2, answer.option);
        assertEquals("魅力 +2", answer.gainZh);
        assertEquals(Plan.Reminder.MISSABLE, answer.priority);
        assertTrue(answer.isAnswer());
        assertFalse("conf single is not an estimate", answer.estimate);
        assertTrue("conf estimate marks the line", find(fromStart, "exam.05-19").estimate);
        Plan.Reminder deadline = find(fromStart, "request.05-01.deadline");
        assertEquals(30, deadline.untilDay);
        assertNull("after the calendar", find(fromStart, "feature.05-27"));
        for (Plan.Reminder x : fromStart.reminders) assertTrue(x.id, x.slot >= fromStart.fromSlot && x.slot <= fromStart.toSlot);

        Plan later = freePlan(r, ReminderFixtures.start(r, ReminderFixtures.CLASS_DAY + 1, 1, 1, 1));
        assertNull("the day before the plan", find(later, "class.04-23"));
        assertNotNull(find(later, "tv.04-26"));
        // A plan that starts in the evening keeps the evening lines of that day only.
        SimState evening = ReminderFixtures.start(r, ReminderFixtures.CLASS_DAY, 1, 1, 1);
        evening.slot = eve(ReminderFixtures.CLASS_DAY);
        Plan eveningPlan = freePlan(r, evening);
        assertNull(find(eveningPlan, "class.04-23"));
        for (Plan.Reminder x : eveningPlan.reminders) assertTrue(x.id, x.slot >= eveningPlan.fromSlot);
    }

    @Test
    public void aRequirementShowsTheLineOnlyWhenTheForecastMeetsIt() {
        Rules r = ReminderFixtures.rules();
        assertNull(find(freePlan(r, ReminderFixtures.start(r, 20, 1, 1, 1)), "feature.04-30"));
        assertNotNull(find(freePlan(r, ReminderFixtures.start(r, 20, 1, 1, 2)), "feature.04-30"));
    }

    // ------------------------------------------------------------------ 2. firstEligible

    @Test
    public void clubFragmentOnTheFirstEveningCourageReachesLv2() {
        Rules r = ReminderFixtures.rules();
        SimState start = ReminderFixtures.start(r, 20, 1, 1, 1);
        start.statPts[COURAGE] = r.stats[COURAGE].thresholds[2] - 1;
        // 4/25 (Saturday) daytime: the courage movie (+4) brings Courage to Lv2.
        int[] codes = ReminderFixtures.codes(r, start, Collections.emptyList(), day(24), RulesFixtures.A_MOVIE);
        Plan plan = ReminderFixtures.plan(r, start, codes, -1);
        Plan.Reminder club = find(plan, "fragment.04-23.club");
        assertNotNull(club);
        assertEquals("the first evening at Lv2", eve(24), club.slot);
        assertEquals(-1, club.untilDay);

        assertNull("never Lv2: no line", find(freePlan(r, start), "fragment.04-23.club"));
        assertEquals("already Lv2 when the window opens: its first evening", eve(ReminderFixtures.CLUB_DAY),
                find(freePlan(r, ReminderFixtures.start(r, 20, 1, 1, 2)), "fragment.04-23.club").slot);
        assertNull("the window opened before the plan with Lv2 already: maybe done, not repeated",
                find(freePlan(r, ReminderFixtures.start(r, 26, 1, 1, 2)), "fragment.04-23.club"));
    }

    // ------------------------------------------------------------------ 3. 上课犯困

    @Test
    public void dozeStaysAwakeUntilAcademicsIsMaxThenClosesTheEyes() {
        Rules r = ReminderFixtures.rules();
        Plan awake = freePlan(r, ReminderFixtures.start(r, 20, 3, 1, 1));
        Plan.Reminder a = find(awake, "doze.04-22");
        assertEquals("上课犯困时别闭眼：学力 +2", a.textZh);
        assertEquals("犯困别闭眼：学力 +2", a.shortZh);
        assertEquals("学力 +2", a.gainZh);
        assertEquals("上课犯困", a.labelZh);
        assertEquals(Plan.Reminder.DOZE, a.kind);
        assertEquals(day(21), a.slot);
        assertTrue(a.detailZh.contains("不闭眼能让学力 +2"));
        assertNotNull(find(awake, "doze.04-24"));

        Plan eyesClosed = freePlan(r, ReminderFixtures.start(r, 20, 6, 1, 3));
        Plan.Reminder c = find(eyesClosed, "doze.04-22");
        assertEquals("上课犯困时闭上眼：勇气 +2", c.textZh);
        assertEquals("犯困闭眼：勇气 +2", c.shortZh);
        assertTrue(c.detailZh.contains("闭上眼，勇气 +2"));

        Plan both = freePlan(r, ReminderFixtures.start(r, 20, 6, 1, 6));
        assertNull("both MAX: nothing to gain", find(both, "doze.04-22"));
        assertNull(find(both, "doze.04-24"));
    }

    // ------------------------------------------------------------------ 4. 保健室

    @Test
    public void nurseAfterAPlannedTowerAFullMoonAndTheRecordedTowerNight() {
        Rules r = ReminderFixtures.rules();
        SimState start = ReminderFixtures.start(r, 20, 1, 1, 1);
        Plan plan = ReminderFixtures.plan(r, start,
                ReminderFixtures.codes(r, start, Arrays.asList(eve(22), eve(24)), -1, 0), -1);
        Plan.Reminder afterTower = find(plan, "nurse.04-24");
        assertNotNull("planned Tartarus on 4/23 evening", afterTower);
        assertEquals(day(23), afterTower.slot);
        assertEquals("放学后先去保健室：勇气 +2，不会消耗时间", afterTower.textZh);
        assertEquals("保健室：勇气 +2", afterTower.shortZh);
        assertEquals("勇气 +2", afterTower.gainZh);
        assertEquals(Plan.Reminder.DEADLINE, afterTower.priority);
        assertEquals("the line already says 勇气 +2", "放学后去 1 楼保健室喝药，不会消耗时间。", afterTower.detailZh);
        assertNull("4/26 is a Sunday", find(plan, "nurse.04-26"));
        assertNotNull("the 5/12 full-moon operation", find(plan, "nurse.05-13"));
        assertNull("no Tartarus on 4/22 evening", find(plan, "nurse.04-23"));

        SimState later = ReminderFixtures.start(r, 27, 1, 1, 1);
        assertNotNull("recorded Tartarus last night", find(
                ReminderFixtures.plan(r, later, ReminderFixtures.codes(r, later, Collections.emptyList(), -1, 0), eve(26)),
                "nurse.04-28"));
        assertNull("an older recorded night does not count", find(
                ReminderFixtures.plan(r, later, ReminderFixtures.codes(r, later, Collections.emptyList(), -1, 0), eve(25)),
                "nurse.04-28"));

        SimState max = ReminderFixtures.start(r, 20, 1, 1, 6);
        Plan atMax = ReminderFixtures.plan(r, max, ReminderFixtures.codes(r, max, Arrays.asList(eve(22)), -1, 0), -1);
        for (Plan.Reminder x : atMax.reminders) assertFalse("Courage MAX: " + x.id, Plan.Reminder.NURSE.equals(x.kind));
    }

    // ------------------------------------------------------------------ 5. 失踪者

    @Test public void plannedTripListsEveryRescueFloorInAscendingOrderAcrossArrivalGroups() {
        Rules r = ReminderFixtures.rules();
        Rules.RescuePerson first = new Rules.RescuePerson();
        first.id = 9;
        first.nameZh = "北村文吉";
        first.floor = 120;
        r.reminders.rescueGroups.get(0).people.add(first);
        Rules.RescuePerson laterLow = new Rules.RescuePerson();
        laterLow.id = 1;
        laterLow.nameEn = "Ayako Yoshimoto";
        laterLow.floor = 50;
        Rules.RescuePerson laterHigh = new Rules.RescuePerson();
        laterHigh.id = 12;
        laterHigh.nameZh = "舞子";
        laterHigh.floor = 146;
        r.reminders.rescueGroups.get(1).people.addAll(Arrays.asList(laterHigh, laterLow));
        SimState start = ReminderFixtures.start(r, 20, 1, 1, 1);
        Plan plan = ReminderFixtures.plan(r, start,
                ReminderFixtures.codes(r, start, Arrays.asList(eve(43)), -1, 0), -1);
        Plan.Reminder tower = find(plan, "rescue.05-11.tower");
        assertTrue(tower.textZh.endsWith("50F、120F、146F"));
        assertTrue(tower.shortZh.contains("50F、120F、146F"));
        assertTrue(tower.detailZh.contains("50F · Ayako Yoshimoto\n120F · 北村文吉\n146F · 舞子"));
        assertEquals("displaying a plan must not mark people rescued", 0L, start.rescued);
        Plan early = ReminderFixtures.plan(r, start,
                ReminderFixtures.codes(r, start, Arrays.asList(eve(40)), -1, 0), -1);
        Plan.Reminder earlyTower = find(early, "rescue.05-11.tower");
        assertTrue(earlyTower.textZh.endsWith("120F"));
        assertFalse("future arrivals do not belong to an earlier visit", earlyTower.detailZh.contains("146F"));
    }

    @Test
    public void missingPeopleRescuedOnThePlannedTartarusEvening() {
        Rules r = ReminderFixtures.rules();
        SimState start = ReminderFixtures.start(r, 20, 1, 1, 1);
        Plan plan = ReminderFixtures.plan(r, start, ReminderFixtures.codes(r, start, Arrays.asList(eve(43)), -1, 0), -1);
        Plan.Reminder first = find(plan, "rescue.05-11.start");
        assertEquals(day(ReminderFixtures.MISSING_FROM), first.slot);
        assertEquals("北村文吉失踪了，期限 5月16日", first.textZh);
        assertTrue(first.detailZh.contains("北村文吉没救出来的话，教皇社群就没法继续了。"));
        // One person or many: the details never say 「他们」.
        assertEquals("在塔尔塔罗斯找到就能救出，还有奖励。期限当天晚上是最后机会。", Reminders.RESCUE_DETAIL);
        assertTrue(first.detailZh.startsWith(Reminders.RESCUE_DETAIL) && !first.detailZh.contains("他们"));
        assertEquals(ReminderFixtures.MISSING_TO, first.untilDay);
        Plan.Reminder more = find(plan, "rescue.05-13.start");
        assertEquals("有 3 名失踪者（包括北村文吉），期限 5月16日", more.textZh);
        assertTrue(more.detailZh.startsWith(Reminders.RESCUE_DETAIL));
        assertTrue("a group that a request needs says so", more.detailZh.contains(ReminderFixtures.LOCKS));
        assertFalse(first.detailZh.contains(ReminderFixtures.LOCKS));
        assertEquals("失踪者 3 人 · 期限 5月16日", more.shortZh);
        assertEquals(Reminders.RESCUE_LABEL, more.labelZh);
        Plan.Reminder tower = find(plan, "rescue.05-11.tower");
        assertEquals(eve(43), tower.slot);
        assertEquals("今晚去塔尔塔罗斯时，顺便救出失踪者（3 人）", tower.textZh);
        assertEquals("顺便救出失踪者", tower.shortZh);
        assertTrue(tower.detailZh.contains(ReminderFixtures.LOCKS));
        assertTrue(tower.detailZh.startsWith("失踪的人在塔尔塔罗斯里，探索时找到就能救出，还能拿到奖励。"));
        assertNull("one line per evening", find(plan, "rescue.05-13.tower"));
        assertNull(find(plan, "rescue.05-11.deadline"));
        assertNull(find(plan, "rescue.05-13.deadline"));

        // Rescued on 5/11 evening: the 5/13 group counts only its own two, and has no evening of its own.
        Plan early = ReminderFixtures.plan(r, start, ReminderFixtures.codes(r, start, Arrays.asList(eve(40)), -1, 0), -1);
        assertEquals("今晚去塔尔塔罗斯时，顺便救出失踪者（1 人）", find(early, "rescue.05-11.tower").textZh);
        assertEquals("有 2 名失踪者，期限 5月16日", find(early, "rescue.05-13.start").textZh);
        Plan.Reminder late = find(early, "rescue.05-13.deadline");
        assertEquals(day(ReminderFixtures.MISSING_TO - 1), late.slot);
        assertEquals("失踪者：5月16日截止，这几天没安排去塔尔塔罗斯", late.textZh);
        assertEquals("失踪者 5月16日截止", late.shortZh);
        assertTrue("what the 5/13 group locks", late.detailZh.endsWith(ReminderFixtures.LOCKS));
        assertEquals("期限当天晚上是最后机会，过了就救不出来了。", Reminders.DEADLINE_DETAIL);
        assertTrue(late.detailZh.startsWith(Reminders.DEADLINE_DETAIL));
        assertEquals(Plan.Reminder.MISSABLE, late.priority);
        assertFalse("an evening before the limit", find(plan, "rescue.05-11.tower").detailZh.contains(Reminders.TOWER_LIMIT_NOTE));
    }

    /**
     * A plan that reaches the limit day states clearly that tonight is the last rescue opportunity.
     */
    @Test
    public void theLimitEveningShowsTheLastRescueOpportunity() {
        Rules r = ReminderFixtures.rules();
        SimState start = ReminderFixtures.start(r, 20, 1, 1, 1);
        Plan onLimit = ReminderFixtures.plan(r, start,
                ReminderFixtures.codes(r, start, Arrays.asList(eve(ReminderFixtures.MISSING_TO)), -1, 0), -1);
        Plan.Reminder tower = find(onLimit, "rescue.05-11.tower");
        assertEquals(eve(ReminderFixtures.MISSING_TO), tower.slot);
        assertEquals("今晚去塔尔塔罗斯时，顺便救出失踪者（3 人）", tower.textZh);
        assertTrue(tower.detailZh, tower.detailZh.startsWith(Reminders.TOWER_DETAIL + "今晚是最后机会。"));

        SimState lastDay = ReminderFixtures.start(r, ReminderFixtures.MISSING_TO, 1, 1, 1);
        Plan.Reminder today = find(freePlan(r, lastDay), "rescue.05-11.deadline");
        assertEquals(day(ReminderFixtures.MISSING_TO), today.slot);
        assertEquals("过了今晚就救不出来了。", Reminders.DEADLINE_TODAY_DETAIL);
        assertTrue(today.detailZh, today.detailZh.startsWith(Reminders.DEADLINE_TODAY_DETAIL));
    }

    @Test
    public void missingPeopleWithoutAPlannedTartarusEveningAndAlreadyHandled() {
        Rules r = ReminderFixtures.rules();
        SimState start = ReminderFixtures.start(r, 20, 1, 1, 1);
        Plan none = freePlan(r, start);
        Plan.Reminder deadline = find(none, "rescue.05-11.deadline");
        assertNotNull("one line per limit", deadline);
        assertNull(find(none, "rescue.05-13.deadline"));
        assertEquals(day(ReminderFixtures.MISSING_TO - 1), deadline.slot);
        assertEquals("失踪者 5月16日截止", deadline.shortZh);
        assertTrue(deadline.detailZh.contains("教皇社群就没法继续了"));

        // Gone before the plan, and the latest recorded Tartarus evening came after: taken as rescued.
        SimState mid = ReminderFixtures.start(r, 41, 1, 1, 1);
        Plan recorded = ReminderFixtures.plan(r, mid, ReminderFixtures.codes(r, mid, Collections.emptyList(), -1, 0), eve(40));
        assertNull(find(recorded, "rescue.05-11.start"));
        assertEquals("有 2 名失踪者，期限 5月16日", find(recorded, "rescue.05-13.start").textZh);
        // A plan that starts inside the window: one line for the earlier groups still missing.
        Plan inside = freePlan(r, ReminderFixtures.start(r, 43, 1, 1, 1));
        assertNull(find(inside, "rescue.05-11.start"));
        Plan.Reminder merged = find(inside, "rescue.05-13.start");
        assertEquals(day(43), merged.slot);
        assertEquals("有 3 名失踪者（包括北村文吉），期限 5月16日", merged.textZh);
        // The modelled rescue is already done in the start state.
        SimState rescued = ReminderFixtures.start(r, 20, 1, 1, 1);
        rescued.rescued = 1L;
        Plan done = freePlan(r, rescued);
        assertNull(find(done, "rescue.05-11.start"));
        assertEquals("有 2 名失踪者，期限 5月16日", find(done, "rescue.05-13.start").textZh);
        // After the limit: nothing.
        for (Plan.Reminder x : freePlan(r, ReminderFixtures.start(r, 46, 1, 1, 1)).reminders) {
            assertFalse(x.id, Plan.Reminder.RESCUE.equals(x.kind));
        }
    }

    // ------------------------------------------------------------------ 6. TV cash

    @Test
    public void tvShoppingWarnsWhenTheForecastCashIsShort() {
        Rules r = ReminderFixtures.rules();
        SimState poor = ReminderFixtures.start(r, 20, 1, 1, 1);
        poor.cash = 1_000;
        Plan.Reminder shortOfCash = find(freePlan(r, poor), "tv.04-26");
        assertEquals("在自己房间看电视就能买，不会消耗时间。" + Reminders.CASH_SHORT, shortOfCash.detailZh);
        SimState rich = ReminderFixtures.start(r, 20, 1, 1, 1);
        rich.cash = ReminderFixtures.TV_COST;
        assertEquals("exactly enough", "在自己房间看电视就能买，不会消耗时间。", find(freePlan(r, rich), "tv.04-26").detailZh);
    }

    // ------------------------------------------------------------------ 7. order, determinism, plan id

    @Test
    public void remindersAreSortedBySlotPriorityAndIdAndDeterministic() {
        Rules r = ReminderFixtures.rules();
        SimState start = ReminderFixtures.start(r, 20, 1, 1, 1);
        int[] codes = ReminderFixtures.codes(r, start, Arrays.asList(eve(22), eve(43)), -1, 0);
        Plan a = ReminderFixtures.plan(r, start, codes, -1);
        Plan b = ReminderFixtures.plan(r, start, codes, -1);
        assertEquals(json(a.reminders), json(b.reminders));
        for (int i = 1; i < a.reminders.size(); i++) {
            Plan.Reminder p = a.reminders.get(i - 1);
            Plan.Reminder q = a.reminders.get(i);
            assertTrue(p.id + " before " + q.id, p.slot < q.slot || p.slot == q.slot
                    && (p.priority > q.priority || p.priority == q.priority && p.id.compareTo(q.id) < 0));
        }
        // 4/24 daytime: 保健室 and 上课犯困 (priority 2) in id order.
        List<Plan.Reminder> sameSlot = a.remindersIn(day(23), day(23));
        assertEquals(Arrays.asList("doze.04-24", "nurse.04-24"), ids(sameSlot));
    }

    @Test
    public void planIdAndStepsDoNotDependOnDisplayReminders() throws Exception {
        Rules with = ReminderFixtures.rules();
        Rules without = ReminderFixtures.rules();
        // Rescue windows now inform trip batching; isolate the purely display-only reminder data.
        with.reminders.rescueGroups.clear();
        without.reminders = new Rules.Reminders();
        assertEquals(with.version, without.version);
        SimState start = ReminderFixtures.start(with, 20, 1, 1, 1);
        Profile profile = RulesFixtures.others();
        Plan a = Engine.plan(with, profile, start, null, new Engine.Options());
        Plan b = Engine.plan(without, profile, start, null, new Engine.Options());
        assertFalse(a.reminders.isEmpty());
        assertTrue(b.reminders.isEmpty());
        assertEquals(a.planId, b.planId);
        assertEquals(a.steps.size(), b.steps.size());
        for (int i = 0; i < a.steps.size(); i++) assertEquals(a.steps.get(i).action, b.steps.get(i).action);
        assertTrue(Engine.validate(with, profile, start, a).isEmpty());
        // The JSON round trip keeps every field.
        assertEquals(json(a.reminders), json(roundTrip(a).reminders));
    }

    // ------------------------------------------------------------------ 8. copy lint

    private static final Pattern SLASH_DATE = Pattern.compile("\\d+/\\d+");

    static void assertCopy(Plan.Reminder x) {
        for (String text : new String[] {x.labelZh, x.textZh, x.shortZh, x.detailZh, x.gainZh}) {
            if (text == null) continue;
            String plain = text.replace("规划设置", "").replace("规划说明", "");
            assertFalse(x.id + " 规划: " + text, plain.contains("规划"));
            assertFalse(x.id + " 约会: " + text, text.contains("约会"));
            assertFalse(x.id + " M/D date: " + text, SLASH_DATE.matcher(text).find());
            assertFalse(x.id + " half-width ?!: " + text, text.contains("?") || text.contains("!") || text.contains("..."));
            assertFalse(x.id + " 不占用时段: " + text, text.contains("不占用时段") || text.contains("黄昏碎片"));
        }
        assertFalse(x.id, x.textZh.isEmpty());
        assertFalse(x.id, x.labelZh.isEmpty());
        assertTrue(x.id + " short: " + x.shortZh, x.shortZh != null && hanCount(x.shortZh) <= 12);
    }

    @Test
    public void dynamicCopyFollowsTheGlossary() {
        Rules r = ReminderFixtures.rules();
        SimState start = ReminderFixtures.start(r, 20, 3, 1, 1);
        start.cash = 0;
        List<Plan.Reminder> all = new ArrayList<>();
        all.addAll(ReminderFixtures.plan(r, start, ReminderFixtures.codes(r, start, Arrays.asList(eve(22), eve(43)), -1, 0), -1).reminders);
        all.addAll(freePlan(r, start).reminders);
        all.addAll(freePlan(r, ReminderFixtures.start(r, 20, 6, 1, 1)).reminders);
        Map<String, Integer> kinds = new HashMap<>();
        for (Plan.Reminder x : all) {
            assertCopy(x);
            kinds.merge(x.kind, 1, Integer::sum);
        }
        for (String kind : new String[] {Plan.Reminder.DOZE, Plan.Reminder.NURSE, Plan.Reminder.RESCUE, Plan.Reminder.TV}) {
            assertTrue(kind, kinds.containsKey(kind));
        }
    }

    // ------------------------------------------------------------------ real pack

    @Test
    public void realPackNewGameAndMidGame() throws Exception {
        Rules rules = realPack();
        Plan newGame = realPlan(rules, newGame(rules, dayOf(rules, 4, 21)));
        Map<String, Integer> kinds = kinds(newGame);
        for (String kind : new String[] {Plan.Reminder.CLASS, Plan.Reminder.EXAM, Plan.Reminder.DOZE, Plan.Reminder.NURSE,
                Plan.Reminder.TV, Plan.Reminder.REQUEST, Plan.Reminder.RESCUE, Plan.Reminder.FRAGMENT, Plan.Reminder.TRIP,
                Plan.Reminder.FEATURE}) {
            assertTrue(kind + " in " + kinds, kinds.getOrDefault(kind, 0) > 0);
        }
        Plan.Reminder horse = find(newGame, "class.07-08");
        assertEquals("课堂提问：选「马」（第 2 项）", horse.textZh);
        assertEquals("课堂：马（第 2 项）", horse.shortZh);
        assertEquals("马", horse.answerZh);
        assertEquals(2, horse.option);
        assertEquals(Rules.slot(dayOf(rules, 7, 8), false), horse.slot);
        // 3DM has no text on 12/11: the answer comes from 游民星空's copy of the same guide (BWIKI writes it alike).
        Plan.Reminder gamble = find(newGame, "class.12-11");
        assertNotNull("class.12-11 from the 游民星空 copy", gamble);
        assertEquals("课堂提问：选「孤注一掷」（第 2 项）", gamble.textZh);
        assertEquals("课堂：孤注一掷（第 2 项）", gamble.shortZh);
        assertEquals(2, gamble.option);
        assertEquals(Rules.slot(dayOf(rules, 12, 11), false), gamble.slot);
        for (String late : new String[] {"class.12-07", "class.12-09", "class.01-18"}) {
            assertNotNull(late, find(newGame, late));
        }
        assertNull("1/3 is not in the game's TV table", find(newGame, "tv.01-03"));
        assertTrue(find(newGame, "exam.07-17").estimate);
        assertEquals(Rules.slot(dayOf(rules, 5, 25), true), find(newGame, "fragment.05-25").slot);
        // A new game's first plan starts the day after the story's own Tartarus evening (4/20): 保健室 on 4/21.
        Plan.Reminder nurse = find(newGame, "nurse.04-21");
        assertNotNull("保健室 after the 4/20 story evening in Tartarus", nurse);
        assertEquals(newGame.fromSlot, nurse.slot);
        // Request #44 is accepted the evening before Yakushima; #97 says in June what failing the rescue locks.
        assertEquals(Rules.slot(dayOf(rules, 7, 19), true), find(newGame, "trip.07-19.accept").slot);
        assertTrue(find(newGame, "rescue.06-18.start").detailZh.contains("伊莉莎白的第 97 号委托就做不了"));

        SimState mid = newGame(rules, dayOf(rules, 10, 22));
        mid.cash = 1_000;
        for (int s = 0; s < 3; s++) mid.statPts[s] = rules.stats[s].thresholds[s == 1 ? 3 : 4];
        Plan midPlan = realPlan(rules, mid);
        Map<String, Integer> midKinds = kinds(midPlan);
        for (String kind : new String[] {Plan.Reminder.CLASS, Plan.Reminder.EXAM, Plan.Reminder.DOZE, Plan.Reminder.TV,
                Plan.Reminder.REQUEST, Plan.Reminder.RESCUE, Plan.Reminder.TRIP}) {
            assertTrue(kind + " in " + midKinds, midKinds.getOrDefault(kind, 0) > 0);
        }
        assertEquals("课堂提问：选「朱诺」（第 3 项）", find(midPlan, "class.10-22").textZh);
        assertNull("before the plan", find(midPlan, "class.07-08"));
        for (Plan plan : new Plan[] {newGame, midPlan}) {
            for (Plan.Reminder x : plan.reminders) {
                assertTrue(x.id, x.slot >= plan.fromSlot && Rules.dayOf(x.slot) <= rules.lastDay);
                assertCopy(x);
            }
        }
    }

    private static Plan realPlan(Rules rules, SimState start) {
        Profile profile = new Profile();
        Plan plan = Engine.plan(rules, profile, start, null, new Engine.Options());
        assertEquals(rules.lastSlot() - start.slot + 1, plan.steps.size());
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        return plan;
    }

    private static Rules realPack() throws Exception {
        for (String c : new String[] {"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            File f = new File(c);
            if (f.isFile()) return RulesLoader.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
        Assume.assumeTrue("rule pack not built yet", false);
        return null;
    }

    private static int dayOf(Rules rules, int month, int dom) {
        for (int d = rules.firstDay; d <= rules.lastDay; d++) {
            if (rules.days[d].month() == month && rules.days[d].dayOfMonth() == dom) return d;
        }
        throw new AssertionError(month + "/" + dom);
    }

    /** New-game state at the daytime of {@code day}; story links carry the ranks the story granted. */
    private static SimState newGame(Rules rules, int day) {
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = Rules.slot(day, false);
        s.cashExact = true;
        for (Rules.Link l : rules.links) {
            if (l.kind != Rules.LinkKind.STORY || l.storyRankDay == null) continue;
            for (int r = 1; r < l.storyRankDay.length && r <= 10; r++) {
                if (l.storyRankDay[r] >= 0 && l.storyRankDay[r] <= day) s.rank[l.idx] = r;
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Integer> kinds(Plan plan) {
        Map<String, Integer> out = new HashMap<>();
        for (Plan.Reminder x : plan.reminders) out.merge(x.kind, 1, Integer::sum);
        return out;
    }

    private static List<String> ids(List<Plan.Reminder> list) {
        List<String> out = new ArrayList<>();
        for (Plan.Reminder x : list) out.add(x.id);
        return out;
    }

    private static int hanCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= '一' && s.charAt(i) <= '鿿') n++;
        return n;
    }

    private static String json(List<Plan.Reminder> list) {
        Plan p = new Plan();
        p.reminders = list;
        try {
            return p.toJson().optJSONArray("reminders") == null ? "[]" : p.toJson().getJSONArray("reminders").toString();
        } catch (org.json.JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static Plan roundTrip(Plan plan) throws Exception {
        return Plan.fromJson(new JSONObject(plan.toJson().toString()));
    }
}
