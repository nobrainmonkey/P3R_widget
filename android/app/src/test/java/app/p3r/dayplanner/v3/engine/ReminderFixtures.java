package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Arrays;
import java.util.List;

/**
 * 当日提醒 fixtures for engine tests: {@link RulesFixtures#small()} (Apr 21 = day 20 … May 26 = day 55) with
 * a {@code reminders} section, and plans assembled from explicit codes so that the Tartarus evenings and the
 * stat levels are known exactly.
 *
 * <ul>
 *   <li>Static items: a class answer on 4/23 (day 22), a TV offer of 5,000 日元 on 4/26 (Sunday, day 25), a
 *       deadline on 4/28 (day 27, until 5/1), the Club Escapade fragment from 4/23 evening (Courage Lv2,
 *       first eligible), a courage-gated line on 4/30 (day 29) and an item after the calendar.</li>
 *   <li>上课犯困 on 4/22 and 4/24 (days 21 and 23); 保健室 Courage +2.</li>
 *   <li>A full-moon operation on 5/12 evening (day 41, a Tuesday) in addition to the fixture's 5/9.</li>
 *   <li>Missing people: 北村文吉 alone from 5/11 (day 40, the modelled rescue) and two more from 5/13 (day
 *       42, whose rescue a request needs: {@link #LOCKS}), both until 5/16 (day 45).</li>
 * </ul>
 */
final class ReminderFixtures {
    private ReminderFixtures() {}

    static final int CLASS_DAY = 22;
    static final int TV_DAY = 25;
    static final int TV_COST = 5_000;
    static final int CLUB_DAY = 22;
    static final int GATED_DAY = 29;
    static final int[] DOZE_DAYS = {21, 23};
    static final int MOON_DAY = 41;
    static final int MISSING_FROM = 40;
    static final int MORE_MISSING_FROM = 42;
    static final int MISSING_TO = 45;
    /** What the 5/13 group's rescue keeps possible (as the pack's 6/18 group says of request #97). */
    static final String LOCKS = "救不出来的话，伊莉莎白的第 97 号委托就做不了。";

    static Rules rules() {
        Rules r = RulesFixtures.small();
        r.days[MOON_DAY].eveLock = "full_moon";
        r.days[MOON_DAY].fullMoon = true;
        Rules.Reminders rem = r.reminders;
        Rules.Reminder answer = item("class.04-23", "class", CLASS_DAY, false, "课堂提问：选「马」（第 2 项）", "课堂：马（第 2 项）");
        answer.labelZh = "课堂提问";
        answer.answerZh = "马";
        answer.option = 2;
        answer.gainZh = "魅力 +2";
        answer.priority = Plan.Reminder.MISSABLE;
        Rules.Reminder tv = item("tv.04-26", "tv", TV_DAY, false, "电视购物：玫瑰花束，5,000 日元", "电视购物 5,000 日元");
        tv.costYen = TV_COST;
        tv.detailZh = "在自己房间看电视就能买，不会消耗时间。";
        Rules.Reminder deadline = item("request.05-01.deadline", "request", 27, false, "伊莉莎白的第 12 号委托 5月1日截止", "委托 5月1日截止");
        deadline.untilDay = 30;
        deadline.priority = Plan.Reminder.DEADLINE;
        Rules.Reminder club = item("fragment.04-23.club", "fragment", CLUB_DAY, true,
                "勇气到 Lv.2 了：晚上去放纵俱乐部，舞台上有一块黄昏的碎片", "放纵俱乐部：黄昏的碎片");
        club.requiresLv.put(RulesFixtures.COURAGE, 2);
        club.firstEligible = true;
        Rules.Reminder gated = item("feature.04-30", "feature", GATED_DAY, false, "勇气够了，可以去看看", "可以去看看");
        gated.requiresLv.put(RulesFixtures.COURAGE, 2);
        Rules.Reminder late = item("feature.05-27", "feature", r.lastDay + 1, false, "日历之后", "日历之后");
        Rules.Reminder estimate = item("exam.05-19", "exam", 48, false, "考试：选「MADRID」", "考试：MADRID");
        estimate.answerZh = "MADRID";
        estimate.conf = "estimate";
        estimate.priority = Plan.Reminder.MISSABLE;
        rem.items.addAll(Arrays.asList(answer, tv, deadline, club, gated, late, estimate));
        rem.dozeDays = DOZE_DAYS.clone();
        rem.nurseCourage = 2;
        rem.rescueGroups.add(group("rescue.05-11", MISSING_FROM, MISSING_TO, 1, "bunkichi"));
        Rules.RescueGroup more = group("rescue.05-13", MORE_MISSING_FROM, MISSING_TO, 2);
        more.locksZh = LOCKS;
        rem.rescueGroups.add(more);
        return r;
    }

    static Rules.Reminder item(String id, String kind, int day, boolean evening, String zh, String shortZh) {
        Rules.Reminder x = new Rules.Reminder();
        x.id = id;
        x.kind = kind;
        x.labelZh = "提醒";
        x.day = day;
        x.evening = evening;
        x.zh = zh;
        x.shortZh = shortZh;
        x.conf = "single";
        x.src.add("aqiu_social_events");
        return x;
    }

    static Rules.RescueGroup group(String id, int from, int to, int count, String... rescueIds) {
        Rules.RescueGroup g = new Rules.RescueGroup();
        g.id = id;
        g.fromDay = from;
        g.toDay = to;
        g.count = count;
        g.rescueIds.addAll(Arrays.asList(rescueIds));
        return g;
    }

    /** Start state at the daytime of {@code day} with the given visible stat levels (academics, charm, courage). */
    static SimState start(Rules r, int day, int academicsLv, int charmLv, int courageLv) {
        SimState s = RulesFixtures.newGame(r, Rules.slot(day, false));
        int[] lv = {academicsLv, charmLv, courageLv};
        for (int i = 0; i < 3; i++) s.statPts[i] = r.stats[i].thresholds[lv[i]];
        s.cash = 10_000;
        return s;
    }

    /** Codes from {@code start} to the last slot: FREE everywhere, Tartarus on the given evening slots, an activity on others. */
    static int[] codes(Rules r, SimState start, List<Integer> towerSlots, int activitySlot, int activity) {
        int[] codes = new int[r.lastSlot() - start.slot + 1];
        for (int i = 0; i < codes.length; i++) {
            int slot = start.slot + i;
            if (towerSlots.contains(slot)) {
                codes[i] = Kernel.code(Kernel.T_TOWER, Math.max(0, r.days[Rules.dayOf(slot)].block), 0);
            } else if (slot == activitySlot) {
                codes[i] = Kernel.code(Kernel.T_ACT, activity, 0);
            }
        }
        return codes;
    }

    /** The plan for {@code codes} with its reminders, as Planner.plan builds it. */
    static Plan plan(Rules r, SimState start, int[] codes, int lastTowerSlot) {
        Profile profile = new Profile();
        Model m = new Model(r, profile, null);
        SimState st = Kernel.normalize(m, start);
        Plan plan = Planner.assemble(m, new Heuristics(m, st, null), st, codes);
        Reminders.attach(m, st, codes, plan, lastTowerSlot);
        return plan;
    }

    /** The plan's reminder with this id, or null. */
    static Plan.Reminder find(Plan plan, String id) {
        for (Plan.Reminder x : plan.reminders) if (x.id.equals(id)) return x;
        return null;
    }
}
