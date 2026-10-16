package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 当日提醒 (docs/DESIGN_v3.md §17.1): puts the rule pack's static reminder lines on a plan and works out
 * the dynamic ones from its forecast: 上课犯困 (stay awake or close the eyes, by the forecast stat levels),
 * 保健室 (the school day after a Tartarus or full-moon night) and 失踪者 (missing people against the planned
 * Tartarus evenings). Display-only: {@link PlanHash} and the forecast never see them. One walk over the
 * plan's codes, so the cost is O(slots + items).
 */
final class Reminders {
    private Reminders() {}

    /** Added to the detail of every reminder that gives a stat (stats are not forecast, DESIGN §10.9). */
    static final String STAT_NOTE = "这份加成不在安排的预计里，能力值升级后去「同步现状」更新一下，安排会更准。";
    /** Appended to a TV shopping detail when its price is above the forecast cash of that slot. */
    static final String CASH_SHORT = "按预计，这天的现金可能不够。";
    static final String DOZE_LABEL = "上课犯困";
    static final String NURSE_LABEL = "保健室";
    static final String RESCUE_LABEL = "失踪者";
    /**
     * The limit day's evening still counts by the evidence (game data Start/Limit days, aqiu's tested Reload
     * walkthrough rescues on limit days; rules/build/REPORT.md, tartarus "rescue deadline"), but nobody has
     * checked it in the game, so the copy never promises it without saying so ({@link Labels#LIMIT_EVENING_CAVEAT}).
     */
    static final String RESCUE_DETAIL = "在塔尔塔罗斯里找到失踪的人就能救出，还能拿到奖励。过了期限就救不出来了；期限当天的晚上"
            + Labels.LIMIT_EVENING_CAVEAT + "。";
    static final String TOWER_DETAIL = "失踪的人在塔尔塔罗斯里，探索时找到就能救出，还能拿到奖励。";
    /** Added to {@link #TOWER_DETAIL} when the planned evening is the limit evening of a group it rescues. */
    static final String TOWER_LIMIT_NOTE = "这是期限当天的晚上：" + Labels.LIMIT_EVENING_CAVEAT + "。";
    static final String DEADLINE_DETAIL = "过了期限还没去塔尔塔罗斯的话就救不出来了。期限当天的晚上" + Labels.LIMIT_EVENING_CAVEAT
            + "，想救的话能早一天就早一天去。";
    /** {@link #DEADLINE_DETAIL} when the line is shown on the limit day itself (a plan that starts that day). */
    static final String DEADLINE_TODAY_DETAIL = "过了今天就救不出来了。今晚是期限当天的晚上：" + Labels.LIMIT_EVENING_CAVEAT + "。";
    /** Visible stat level that is MAX. */
    private static final int MAX_LV = 6;

    /** Plan order: by slot, then priority (highest first), then id. */
    static final Comparator<Plan.Reminder> ORDER = (a, b) -> {
        if (a.slot != b.slot) return Integer.compare(a.slot, b.slot);
        if (a.priority != b.priority) return Integer.compare(b.priority, a.priority);
        return a.id.compareTo(b.id);
    };

    /**
     * Fills {@code plan.reminders} for the plan made of {@code codes} from {@code start} (the normalised start
     * state the plan was assembled from). {@code lastTowerSlot} is the latest recorded Tartarus evening (-1
     * none): the 保健室 line the next school day when it is the evening right before the plan, and it counts as
     * a visit for missing people who were already gone that evening.
     */
    static void attach(Model m, SimState start, int[] codes, Plan plan, int lastTowerSlot) {
        Rules.Reminders data = m.rules.reminders;
        if (data == null || data.isEmpty()) return;
        Walk walk = new Walk(m, start, codes);
        List<Plan.Reminder> out = new ArrayList<>();
        statics(m, data, plan, walk, out);
        doze(m, data, plan, walk, out);
        nurse(m, data, plan, walk, lastTowerSlot, out);
        rescue(m, data, plan, start, lastTowerSlot, out);
        out.sort(ORDER);
        plan.reminders = out;
    }

    /** Pre-action stat levels and cash of every slot of the plan (the forecast at the start of the slot). */
    static final class Walk {
        final int from;
        final int[][] lv;
        final long[] cash;

        Walk(Model m, SimState start, int[] codes) {
            from = start.slot;
            lv = new int[codes.length][3];
            cash = new long[codes.length];
            SimState st = start.copy();
            int stats = Math.min(3, m.t.nStats);
            for (int i = 0; i < codes.length; i++) {
                int slot = st.slot;
                for (int s = 0; s < stats; s++) lv[i][s] = Kernel.statLv(m, st, s);
                cash[i] = st.cash;
                Kernel.apply(m, st, m.t.open(slot) ? codes[i] : Kernel.FREE, null);
            }
        }

        boolean has(int slot) {
            return slot >= from && slot - from < cash.length;
        }

        int lv(int slot, int stat) {
            return lv[slot - from][stat];
        }

        boolean meets(int slot, Map<Integer, Integer> requiresLv) {
            for (Map.Entry<Integer, Integer> e : requiresLv.entrySet()) {
                int s = e.getKey();
                if (s < 0 || s >= 3 || lv(slot, s) < e.getValue()) return false;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ static lines of the pack

    private static void statics(Model m, Rules.Reminders data, Plan plan, Walk walk, List<Plan.Reminder> out) {
        Rules rules = m.rules;
        for (Rules.Reminder r : data.items) {
            if (r.day > rules.lastDay) continue;
            int slot = Rules.slot(r.day, r.evening);
            if (r.firstEligible) {
                slot = firstEligible(m, r, plan, walk);
            } else if (!walk.has(slot) || !walk.meets(slot, r.requiresLv)) {
                continue;
            }
            if (slot < 0) continue;
            Plan.Reminder x = new Plan.Reminder();
            x.id = r.id;
            x.kind = r.kind;
            x.labelZh = r.labelZh;
            x.textZh = r.zh;
            x.slot = slot;
            x.shortZh = r.shortZh;
            x.answerZh = r.answerZh;
            x.option = r.option;
            x.detailZh = r.detailZh;
            x.gainZh = r.gainZh;
            x.priority = r.priority;
            x.untilDay = r.firstEligible ? -1 : r.untilDay;
            x.estimate = "estimate".equals(r.conf);
            if (r.costYen > 0 && r.costYen > walk.cash[slot - walk.from]) {
                x.detailZh = (x.detailZh == null ? "" : x.detailZh) + CASH_SHORT;
            }
            out.add(x);
        }
    }

    /**
     * The first open slot of the item's kind from its day (to its untilDay, else the end of the calendar) in the
     * plan whose forecast meets the requirement, or -1. When the window began before the plan and the start
     * already meets it, the player may have been there before: no line.
     */
    private static int firstEligible(Model m, Rules.Reminder r, Plan plan, Walk walk) {
        int first = Rules.slot(r.day, r.evening);
        if (first < plan.fromSlot && walk.meets(plan.fromSlot, r.requiresLv)) return -1;
        int until = r.untilDay >= 0 ? Math.min(r.untilDay, m.rules.lastDay) : m.rules.lastDay;
        for (int d = r.day; d <= until; d++) {
            int slot = Rules.slot(d, r.evening);
            if (walk.has(slot) && m.t.open(slot) && walk.meets(slot, r.requiresLv)) return slot;
        }
        return -1;
    }

    // ------------------------------------------------------------------ 上课犯困 and 保健室

    private static void doze(Model m, Rules.Reminders data, Plan plan, Walk walk, List<Plan.Reminder> out) {
        Rules.Stat academics = m.rules.stat("academics");
        Rules.Stat courage = m.rules.stat("courage");
        if (academics == null || courage == null || academics.idx >= 3 || courage.idx >= 3) return;
        for (int d : data.dozeDays) {
            int slot = Rules.slot(d, false);
            if (d > m.rules.lastDay || !walk.has(slot)) continue;
            Plan.Reminder x = new Plan.Reminder();
            if (walk.lv(slot, academics.idx) < MAX_LV) {
                x.gainZh = "学力 +" + data.dozeAcademics;
                x.textZh = "上课犯困时别闭眼：" + x.gainZh;
                x.shortZh = "犯困别闭眼：" + x.gainZh;
                x.detailZh = "上课犯困时，撑住不闭眼能让学力 +" + data.dozeAcademics + "；学力 MAX 以后改成闭上眼，勇气 +"
                        + data.dozeCourage + "。" + STAT_NOTE;
            } else if (walk.lv(slot, courage.idx) < MAX_LV) {
                x.gainZh = "勇气 +" + data.dozeCourage;
                x.textZh = "上课犯困时闭上眼：" + x.gainZh;
                x.shortZh = "犯困闭眼：" + x.gainZh;
                x.detailZh = "学力已经 MAX，上课犯困时闭上眼，勇气 +" + data.dozeCourage + "。" + STAT_NOTE;
            } else {
                continue;
            }
            x.id = "doze." + m.rules.days[d].md;
            x.kind = Plan.Reminder.DOZE;
            x.labelZh = DOZE_LABEL;
            x.slot = slot;
            x.priority = Plan.Reminder.DEADLINE;
            out.add(x);
        }
    }

    /**
     * 保健室 on school day D whose after-school slot is free when the evening before was a planned Tartarus
     * visit, a full-moon operation, an evening the story spends in Tartarus ({@link Rules.Reminders#nurseStoryDays},
     * 4/20 before a new game's first plan), or (before the plan) the latest recorded Tartarus evening, and
     * Courage is not MAX yet.
     */
    private static void nurse(Model m, Rules.Reminders data, Plan plan, Walk walk, int lastTowerSlot,
                              List<Plan.Reminder> out) {
        Rules.Stat courage = m.rules.stat("courage");
        if (data.nurseCourage <= 0 || courage == null || courage.idx >= 3) return;
        Rules rules = m.rules;
        for (int d = Math.max(rules.firstDay + 1, Rules.dayOf(plan.fromSlot)); d <= rules.lastDay; d++) {
            Rules.Day day = rules.day(d);
            int slot = Rules.slot(d, false);
            if (day == null || day.type != Rules.DayType.SCHOOL || !walk.has(slot) || !m.t.open(slot)) continue;
            if (walk.lv(slot, courage.idx) >= MAX_LV) continue;
            int eve = Rules.slot(d - 1, true);
            Plan.Step before = plan.at(eve);
            Rules.Day prev = rules.day(d - 1);
            boolean afterNight = before != null && "tower".equals(before.kind)
                    || prev != null && prev.fullMoon && "full_moon".equals(prev.eveLock)
                    || Arrays.binarySearch(data.nurseStoryDays, d - 1) >= 0
                    || eve < plan.fromSlot && lastTowerSlot == eve;
            if (!afterNight) continue;
            Plan.Reminder x = new Plan.Reminder();
            x.id = "nurse." + day.md;
            x.kind = Plan.Reminder.NURSE;
            x.labelZh = NURSE_LABEL;
            x.gainZh = "勇气 +" + data.nurseCourage;
            x.textZh = "放学后先去保健室：" + x.gainZh + "，不会消耗时间";
            x.shortZh = "保健室：" + x.gainZh;
            x.detailZh = "前一晚探索了塔尔塔罗斯或打了满月作战，放学后到 1 楼保健室喝药，勇气 +" + data.nurseCourage
                    + "，不会消耗时间。" + STAT_NOTE;
            x.slot = slot;
            x.priority = Plan.Reminder.DEADLINE;
            out.add(x);
        }
    }

    // ------------------------------------------------------------------ 失踪者

    /**
     * Missing people by (missing from, limit) group: when they go missing (counting the people of earlier groups
     * with the same limit who are still missing then), on the first planned Tartarus evening of the window (one
     * line per evening, all the people rescued that night), or, without one, the day before the limit. Groups
     * whose limit is before the plan, whose modelled people are already rescued or lost, or who were already
     * missing on the latest recorded Tartarus evening get no line. Whatever speaks of the limit evening says
     * that the evidence has it in time but nobody checked it in the game ({@link Labels#LIMIT_EVENING_CAVEAT}).
     */
    private static void rescue(Model m, Rules.Reminders data, Plan plan, SimState start, int lastTowerSlot,
                               List<Plan.Reminder> out) {
        if (data.rescueGroups.isEmpty()) return;
        Rules rules = m.rules;
        int startDay = Rules.dayOf(plan.fromSlot);
        List<Rules.RescueGroup> active = new ArrayList<>();
        Map<Rules.RescueGroup, Integer> night = new HashMap<>();
        for (Rules.RescueGroup g : data.rescueGroups) {
            if (g.toDay < startDay || g.toDay > rules.lastDay || done(rules, g, start)) continue;
            int missingEve = Rules.slot(g.fromDay, true);
            if (lastTowerSlot >= missingEve && lastTowerSlot < plan.fromSlot) continue;
            active.add(g);
            night.put(g, firstTowerNight(plan, Math.max(missingEve, plan.fromSlot), Rules.slot(g.toDay, true)));
        }
        active.sort((a, b) -> a.fromDay != b.fromDay ? Integer.compare(a.fromDay, b.fromDay) : a.id.compareTo(b.id));
        // When they go missing: the latest group of a slot and limit speaks for the earlier ones still missing.
        Map<String, Plan.Reminder> starts = new TreeMap<>();
        for (Rules.RescueGroup g : active) {
            int day = Math.max(g.fromDay, startDay);
            int slot = Math.max(Rules.slot(day, false), plan.fromSlot);
            int count = 0;
            List<String> names = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (Rules.RescueGroup e : active) {
                if (e.toDay != g.toDay || e.fromDay > g.fromDay) continue;
                int n = night.get(e);
                if (e != g && n >= 0 && n < Rules.slot(g.fromDay, false)) continue;
                count += e.count;
                people(rules, e, names, notes);
            }
            Plan.Reminder x = rescueLine("start", g, slot, Plan.Reminder.DEADLINE, g.toDay);
            String limit = date(rules, g.toDay);
            if (names.size() == count) {
                x.textZh = String.join("、", names) + "失踪了，期限 " + limit;
            } else if (!names.isEmpty()) {
                x.textZh = "有 " + count + " 名失踪者（包括" + String.join("、", names) + "），期限 " + limit;
            } else {
                x.textZh = "有 " + count + " 名失踪者，期限 " + limit;
            }
            x.shortZh = "失踪者 " + count + " 人 · 期限 " + limit;
            x.detailZh = RESCUE_DETAIL + String.join("", notes);
            starts.put(slot + "/" + g.toDay, x); // groups in fromDay order: a later one replaces an earlier one
        }
        out.addAll(starts.values());
        // The planned Tartarus evenings that rescue them, one line per evening.
        Map<Integer, List<Rules.RescueGroup>> byNight = new TreeMap<>();
        Map<Integer, List<Rules.RescueGroup>> byLimit = new TreeMap<>();
        for (Rules.RescueGroup g : active) {
            int n = night.get(g);
            if (n >= 0) byNight.computeIfAbsent(n, k -> new ArrayList<>()).add(g);
            else byLimit.computeIfAbsent(g.toDay, k -> new ArrayList<>()).add(g);
        }
        for (Map.Entry<Integer, List<Rules.RescueGroup>> e : byNight.entrySet()) {
            List<Rules.RescueGroup> groups = e.getValue();
            int count = 0;
            int until = Integer.MAX_VALUE;
            List<String> notes = new ArrayList<>();
            for (Rules.RescueGroup g : groups) {
                count += g.count;
                until = Math.min(until, g.toDay);
                people(rules, g, new ArrayList<>(), notes);
            }
            Plan.Reminder x = rescueLine("tower", groups.get(0), e.getKey(), Plan.Reminder.DEADLINE, until);
            x.textZh = "今晚去塔尔塔罗斯时，顺便救出失踪者（" + count + " 人）";
            x.shortZh = "顺便救出失踪者";
            boolean limitNight = Rules.dayOf(e.getKey()) >= until;
            x.detailZh = TOWER_DETAIL + (limitNight ? TOWER_LIMIT_NOTE : "") + String.join("", notes);
            out.add(x);
        }
        // No Tartarus evening planned before the limit: say so the day before it.
        for (Map.Entry<Integer, List<Rules.RescueGroup>> e : byLimit.entrySet()) {
            int limitDay = e.getKey();
            int slot = Math.max(Rules.slot(Math.max(startDay, limitDay - 1), false), plan.fromSlot);
            List<String> notes = new ArrayList<>();
            for (Rules.RescueGroup g : e.getValue()) people(rules, g, new ArrayList<>(), notes);
            Plan.Reminder x = rescueLine("deadline", e.getValue().get(0), slot, Plan.Reminder.MISSABLE, limitDay);
            String limit = date(rules, limitDay);
            x.textZh = RESCUE_LABEL + "：" + limit + "截止，这几天没安排去塔尔塔罗斯";
            x.shortZh = "失踪者 " + limit + "截止";
            x.detailZh = (Rules.dayOf(slot) >= limitDay ? DEADLINE_TODAY_DETAIL : DEADLINE_DETAIL) + String.join("", notes);
            out.add(x);
        }
    }

    /** All the group's modelled people are already rescued or lost in the start state. */
    private static boolean done(Rules rules, Rules.RescueGroup g, SimState start) {
        if (g.rescueIds.isEmpty() || g.count > g.rescueIds.size()) return false;
        for (String id : g.rescueIds) {
            int i = rescueIndex(rules, id);
            if (i < 0 || ((start.rescued | start.rescueFailed) & (1L << i)) == 0) return false;
        }
        return true;
    }

    /** First evening slot in [from, to] (from rounded up to an evening) whose plan step visits Tartarus, or -1. */
    private static int firstTowerNight(Plan plan, int from, int to) {
        for (int s = from | 1; s <= to; s += 2) {
            Plan.Step step = plan.at(s);
            if (step != null && "tower".equals(step.kind)) return s;
        }
        return -1;
    }

    /**
     * Names of the group's modelled people, and what is lost when they are not rescued: their link, and what
     * the group says it locks (伊莉莎白的第 97 号委托, {@link Rules.RescueGroup#locksZh}).
     */
    private static void people(Rules rules, Rules.RescueGroup g, List<String> names, List<String> notes) {
        if (g.locksZh != null && !g.locksZh.isEmpty()) notes.add(g.locksZh);
        for (String id : g.rescueIds) {
            int i = rescueIndex(rules, id);
            if (i < 0) continue;
            Rules.Rescue r = rules.rescues[i];
            names.add(r.zh);
            if (r.link >= 0 && r.link < rules.links.length) {
                notes.add(r.zh + "没救出来的话，" + rules.links[r.link].arcanaZh + "社群就没法继续了。");
            }
        }
    }

    private static int rescueIndex(Rules rules, String id) {
        for (int i = 0; i < rules.rescues.length; i++) if (rules.rescues[i].id.equals(id)) return i;
        return -1;
    }

    private static Plan.Reminder rescueLine(String part, Rules.RescueGroup g, int slot, int priority, int until) {
        Plan.Reminder x = new Plan.Reminder();
        x.id = g.id + "." + part;
        x.kind = Plan.Reminder.RESCUE;
        x.labelZh = RESCUE_LABEL;
        x.slot = slot;
        x.priority = priority;
        x.untilDay = until;
        return x;
    }

    /** "7月6日". */
    private static String date(Rules rules, int day) {
        Rules.Day d = rules.day(day);
        return d == null ? "" : d.month() + "月" + d.dayOfMonth() + "日";
    }
}
