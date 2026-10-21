package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.engine.RescueLocations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only catalogue for 提示: a Tartarus block's opening day through the day before the next opens.
 * Unlike a plan's daily reminders, this keeps the entire period when the player opens it midway through.
 * It records neither completion nor stat gains, and never changes the plan or observed state. The copy
 * is 提示's own (docs/DESIGN_3.6_UI.md §2): what to do, the real game condition, nothing about the planner.
 */
public final class PeriodTips {
    private PeriodTips() {}

    public static final class Period {
        public int fromDay;
        public int toDay = -1;
        public int observedSlot;
        public String blockZh = "当前阶段";
        public String floorsZh = "";
        /** The whole game ({@link #collectAll}), not one block's period. */
        public boolean whole;
        /** Original event/opening dates are retained, including an explicit window's earlier date. */
        public List<Plan.Reminder> items = new ArrayList<>();
        /** Unbounded conditional opportunities: the opening date passing does not expire them. */
        public Set<String> ongoingIds = new LinkedHashSet<>();
        /** Exact last occupied slot of calendar ranges, including daytime-only exam periods. */
        public Map<String, Integer> calendarEndSlots = new LinkedHashMap<>();
    }

    /** How many upcoming dates the whole-game lookup names before 「等」. */
    private static final int UPCOMING_DATES = 5;

    private static final Comparator<Plan.Reminder> ORDER = Comparator
            .comparingInt((Plan.Reminder r) -> r.slot)
            .thenComparing((a, b) -> Integer.compare(b.priority, a.priority))
            .thenComparing(r -> r.id);

    public static Period collect(Rules rules, SimState observed, Plan plan) {
        Period period = new Period();
        if (rules == null) return period;
        period.observedSlot = observed == null ? rules.firstSlot() : observed.slot;
        bounds(rules, Rules.dayOf(period.observedSlot), period);
        return collect(rules, observed, plan, period);
    }

    /** The same read-only catalogue over the whole game; no synthetic plan or checkpoint is needed. */
    public static Period collectAll(Rules rules, SimState observed) {
        Period period = new Period();
        if (rules == null) return period;
        period.observedSlot = observed == null ? rules.firstSlot() : observed.slot;
        period.fromDay = rules.firstDay;
        period.toDay = rules.lastDay;
        period.blockZh = "全部提示";
        period.whole = true;
        return collect(rules, observed, null, period);
    }

    private static Period collect(Rules rules, SimState observed, Plan plan, Period period) {
        Rules.Reminders data = rules.reminders;
        if (data == null || period.toDay < period.fromDay) return period;

        Map<String, Plan.Reminder> items = new LinkedHashMap<>();
        Set<String> conditionalItems = new LinkedHashSet<>();
        Set<String> sourceIds = new LinkedHashSet<>();
        for (Rules.Reminder source : data.items) {
            sourceIds.add(source.id);
            // Permanent pickups and invitations belong to their opening period. Only a real
            // deadline/window can carry an earlier entry into a later period.
            if (!overlaps(period, source.day, source.untilDay)) continue;
            Plan.Reminder item = from(source);
            String condition = requirements(rules, source);
            if (source.firstEligible) {
                // The catalogue cannot know whether an unrecorded, slot-free pickup was already done.
                item.textZh = nonempty(source.shortZh, source.labelZh)
                        + (condition.isEmpty() ? "" : "（" + condition + " 以后）");
                if (source.untilDay < 0) period.ongoingIds.add(item.id);
            } else if (!condition.isEmpty()) {
                item.textZh = condition + " 以后，" + source.zh;
            }
            if (!condition.isEmpty()) item.detailZh = append(item.detailZh, "要" + condition + "。");
            if (source.firstEligible || !condition.isEmpty()) conditionalItems.add(item.id);
            items.put(item.id, item);
        }

        for (int day : data.dozeDays) {
            if (!inside(period, day)) continue;
            Rules.Day date = rules.day(day);
            Plan.Reminder item = new Plan.Reminder();
            item.id = "doze." + (date == null ? day : date.md);
            item.kind = Plan.Reminder.DOZE;
            item.labelZh = "上课犯困";
            item.slot = Rules.slot(day, false);
            item.priority = Plan.Reminder.DEADLINE;
            item.textZh = "别闭眼：学力 +" + data.dozeAcademics + "；学力 MAX 了就闭上眼：勇气 +" + data.dozeCourage;
            items.put(item.id, item);
        }

        if (data.nurseCourage > 0) {
            Plan.Reminder item = new Plan.Reminder();
            item.id = "period.nurse";
            item.kind = Plan.Reminder.NURSE;
            item.labelZh = "保健室";
            item.slot = Rules.slot(period.fromDay, false);
            item.textZh = "探索后第二天上学：放学后先去保健室";
            item.detailZh = "前一晚探索了塔尔塔罗斯或打了满月作战，第二天上学时放学后去 1 楼保健室：勇气 +"
                    + data.nurseCourage + "，不会消耗时间。勇气 MAX 了就不用去。";
            items.put(item.id, item);
            period.ongoingIds.add(item.id);
        }

        // Forecasts refine future advice (e.g. which stat is capped), but never erase past catalogue entries.
        if (plan != null) {
            for (Plan.Reminder item : plan.reminders) {
                if (Plan.Reminder.RESCUE.equals(item.kind)) continue;
                // A predicted pickup date must not turn an old opening into a current-period item.
                if (sourceIds.contains(item.id) && !items.containsKey(item.id)) continue;
                if (conditionalItems.contains(item.id)) {
                    Plan.Reminder catalogue = items.get(item.id);
                    if (catalogue.slot != item.slot) {
                        catalogue.detailZh = append(catalogue.detailZh,
                                "安排在 " + date(rules, Rules.dayOf(item.slot)) + "。");
                    }
                    continue;
                }
                if (!inside(period, Rules.dayOf(item.slot))) continue;
                items.put(item.id, copy(item));
            }
        }
        for (Rules.RescueGroup group : data.rescueGroups) {
            if (overlaps(period, group.fromDay, group.toDay)) {
                Plan.Reminder item = rescue(rules, observed, plan, group);
                items.put(item.id, item);
            }
        }
        activities(rules, observed, period, items);
        unlocks(rules, observed, period, items);
        episodes(rules, observed, period, items);
        datingNote(rules, period, items);
        calendar(rules, period, items);
        period.items.addAll(items.values());
        period.items.sort(ORDER);
        return period;
    }

    private static void bounds(Rules rules, int day, Period period) {
        period.fromDay = rules.firstDay;
        period.toDay = rules.lastDay;
        Rules.Block current = null;
        Rules.Block first = null;
        for (Rules.Block block : rules.blocks) {
            if (first == null || block.fromDay < first.fromDay) first = block;
            if (block.fromDay <= day && (current == null || block.fromDay > current.fromDay)) current = block;
        }
        if (first != null && day < first.fromDay && rules.firstDay < first.fromDay) {
            period.toDay = Math.min(rules.lastDay, first.fromDay - 1);
            period.blockZh = "探索开放前";
            return;
        }
        // A fixture without pre-opening calendar days can still fall back to its first playable block.
        if (current == null) current = first;
        if (current == null) return;
        period.fromDay = Math.max(rules.firstDay, current.fromDay);
        for (Rules.Block block : rules.blocks) {
            if (block.fromDay > current.fromDay) period.toDay = Math.min(period.toDay, block.fromDay - 1);
        }
        period.blockZh = nonempty(current.zh, "当前阶段");
        period.floorsZh = nonempty(current.floorsZh, "");
    }

    private static boolean inside(Period period, int day) {
        return day >= period.fromDay && day <= period.toDay;
    }

    private static boolean overlaps(Period period, int fromDay, int untilDay) {
        // Without an explicit window, an ordinary reminder remains a one-day event.
        return fromDay <= period.toDay && Math.max(fromDay, untilDay) >= period.fromDay;
    }

    private static Plan.Reminder from(Rules.Reminder source) {
        Plan.Reminder item = new Plan.Reminder();
        item.id = source.id;
        item.kind = source.kind;
        item.labelZh = source.labelZh;
        item.textZh = source.zh;
        item.slot = Rules.slot(source.day, source.evening);
        item.shortZh = source.shortZh;
        item.answerZh = source.answerZh;
        item.option = source.option;
        item.detailZh = source.detailZh;
        item.gainZh = source.gainZh;
        item.priority = source.priority;
        item.untilDay = source.untilDay;
        item.estimate = "estimate".equals(source.conf);
        return item;
    }

    private static Plan.Reminder copy(Plan.Reminder source) {
        Plan.Reminder item = new Plan.Reminder();
        item.id = source.id;
        item.kind = source.kind;
        item.labelZh = source.labelZh;
        item.textZh = source.textZh;
        item.slot = source.slot;
        item.shortZh = source.shortZh;
        item.answerZh = source.answerZh;
        item.option = source.option;
        item.detailZh = source.detailZh;
        item.gainZh = source.gainZh;
        item.priority = source.priority;
        item.untilDay = source.untilDay;
        item.estimate = source.estimate;
        return item;
    }

    private static Plan.Reminder rescue(Rules rules, SimState observed, Plan plan, Rules.RescueGroup group) {
        Plan.Reminder item = new Plan.Reminder();
        item.id = group.id;
        item.kind = Plan.Reminder.RESCUE;
        item.labelZh = "失踪者";
        item.slot = Rules.slot(group.fromDay, false);
        item.untilDay = group.toDay;
        item.priority = Plan.Reminder.MISSABLE;
        List<String> names = new ArrayList<>();
        StringBuilder detail = new StringBuilder("在塔尔塔罗斯找到就能救出，最晚 ")
                .append(date(rules, group.toDay)).append(" 晚上。");
        String locations = RescueLocations.details(java.util.Collections.singletonList(group));
        if (!locations.isEmpty()) detail.append('\n').append(locations).append('\n');
        for (String id : group.rescueIds) {
            for (int i = 0; i < rules.rescues.length; i++) {
                Rules.Rescue person = rules.rescues[i];
                if (!id.equals(person.id)) continue;
                names.add(person.zh);
                if (person.link >= 0 && person.link < rules.links.length) {
                    detail.append(person.zh).append("没救出来的话，").append(rules.links[person.link].arcanaZh)
                            .append("社群就没法继续了。");
                }
                if (observed != null && (observed.rescued & (1L << i)) != 0) {
                    detail.append(person.zh).append("已经救出来了。");
                } else if (observed != null && (observed.rescueFailed & (1L << i)) != 0) {
                    detail.append("没赶上救").append(person.zh).append("。");
                }
                break;
            }
        }
        if (group.locksZh != null) detail.append(group.locksZh);
        int tower = firstTower(plan, Math.max(Rules.slot(group.fromDay, true),
                observed == null ? rules.firstSlot() : observed.slot), Rules.slot(group.toDay, true));
        if (tower >= 0) {
            detail.append("安排里 ").append(date(rules, Rules.dayOf(tower))).append(" 会探索塔尔塔罗斯，顺便救人。");
        }
        boolean one = group.count == 1 && names.size() == 1;
        item.textZh = (one ? "救出" + names.get(0)
                : "救出 " + group.count + " 位失踪者" + (names.isEmpty() ? "" : "（有" + String.join("、", names) + "）"))
                + " · 期限 " + date(rules, group.toDay);
        String floors = RescueLocations.floors(java.util.Collections.singletonList(group));
        if (!floors.isEmpty()) item.textZh += " · " + floors;
        item.shortZh = "失踪者 " + group.count + " 人";
        item.detailZh = detail.toString().trim();
        return item;
    }

    private static int firstTower(Plan plan, int from, int until) {
        if (plan == null) return -1;
        for (int slot = Math.max(from, plan.fromSlot) | 1; slot <= Math.min(until, plan.toSlot); slot += 2) {
            Plan.Step step = plan.at(slot);
            if (step != null && "tower".equals(step.kind)) return slot;
        }
        return -1;
    }

    private static String requirements(Rules rules, Rules.Reminder source) {
        List<String> requirements = new ArrayList<>();
        for (Rules.Stat stat : rules.stats) {
            Integer level = source.requiresLv.get(stat.idx);
            if (level != null) requirements.add(stat.zh + (level >= 6 ? " MAX" : " Lv." + level));
        }
        return String.join("、", requirements);
    }

    /**
     * The rule every dorm activity once repeated in its own note; the overview says it once. The
     * 3.6 pack no longer writes it there, so this only guards an older pack.
     */
    private static final String DORM_RULE = "同一种活动累计3次可解锁或强化队友特性。";

    private static void activities(Rules rules, SimState observed, Period period, Map<String, Plan.Reminder> items) {
        List<String> dormLines = new ArrayList<>();
        Set<String> partners = new LinkedHashSet<>();
        int today = Rules.dayOf(period.observedSlot);
        // Teammates with one activity done 3 times: their trait is unlocked, the other activity 强化s it.
        Set<String> unlocked = new HashSet<>();
        for (Rules.Activity activity : rules.activities) {
            if (activity.dormActivity() && observed != null && used(observed, activity) >= 3) {
                unlocked.add(partnerKey(activity));
            }
        }
        for (Rules.Activity activity : rules.activities) {
            if (activity.fixedDays == null) continue;
            List<Integer> days = new ArrayList<>();
            for (int day : activity.fixedDays) if (inside(period, day)) days.add(day);
            if (days.isEmpty()) continue;
            String gains = gains(rules, activity);
            if (activity.id.startsWith("group_study_")) {
                for (int day : days) {
                    Plan.Reminder item = new Plan.Reminder();
                    item.id = "activity." + activity.id + "." + day;
                    item.kind = Plan.Reminder.FEATURE;
                    item.labelZh = "宿舍集体学习";
                    item.slot = Rules.slot(day, true);
                    item.textZh = gains.isEmpty() ? nonempty(activity.zh, "宿舍集体学习") : gains;
                    item.gainZh = gains.isEmpty() ? null : gains;
                    item.detailZh = "到" + nonempty(activity.locationZh, "宿舍大厅").replace(" · ", "")
                            + "和大家一起学习。不花钱，但会用掉晚上；学力 MAX 了就不用参加。";
                    item.estimate = "estimate".equals(activity.conf) || "conflict".equals(activity.conf);
                    items.put(item.id, item);
                }
            } else if (activity.dormActivity()) {
                String dates = dates(rules, days, period.whole ? today : Integer.MIN_VALUE);
                if (dates.isEmpty()) continue;
                partners.add(activity.dormPartnerZh);
                String progress;
                if (observed == null) {
                    progress = "还没记进度";
                } else {
                    int count = used(observed, activity);
                    progress = count >= 3 ? "做满 3 次了" : "做过 " + count + " 次，再 " + (3 - count) + " 次"
                            + (unlocked.contains(partnerKey(activity)) ? "强化" : "解锁") + "特性";
                }
                String note = activity.noteZh == null ? "" : activity.noteZh.replace(DORM_RULE, "").trim();
                note = note.replace("，同一种活动累计3次可解锁或强化队友特性", "").trim();
                dormLines.add(activity.dormPartnerZh + " · " + activity.zh + "\n"
                        + "能做的晚上：" + dates + "\n" + progress
                        + (gains.isEmpty() ? "" : " · " + gains)
                        + (note.isEmpty() ? "" : "\n" + note));
            }
        }
        if (!dormLines.isEmpty()) {
            Plan.Reminder item = new Plan.Reminder();
            item.id = "period.dorm";
            item.kind = Plan.Reminder.FEATURE;
            item.labelZh = "宿舍活动";
            item.slot = Rules.slot(period.fromDay, false);
            List<String> names = new ArrayList<>(partners);
            item.textZh = "和" + (names.size() <= 3 ? String.join("、", names)
                    : String.join("、", names.subList(0, 2)) + " 等 " + names.size() + " 位队友") + "一起做宿舍活动";
            item.detailZh = "不花钱，但会用掉晚上。同一种活动做 3 次解锁队友特性，两种各 3 次强化。\n"
                    + "园艺要先种好菜。\n\n"
                    + String.join("\n\n", dormLines);
            items.put(item.id, item);
            period.ongoingIds.add(item.id);
        }
    }

    /** Times a dorm activity was recorded (0 when the record has no count for it). */
    private static int used(SimState observed, Rules.Activity activity) {
        return activity.idx >= 0 && activity.idx < observed.used.length ? Math.max(0, observed.used[activity.idx]) : 0;
    }

    /** One key per teammate across their activities (the name when the id has no teammate part). */
    private static String partnerKey(Rules.Activity activity) {
        String id = activity.dormPartnerId();
        return id != null ? id : "zh:" + activity.dormPartnerZh;
    }

    /**
     * 「5月13日、6月8日」; from {@code fromDay} on only (the whole-game lookup), the first
     * {@value #UPCOMING_DATES} and then 「等」.
     */
    private static String dates(Rules rules, List<Integer> days, int fromDay) {
        List<String> out = new ArrayList<>();
        boolean more = false;
        for (int day : days) {
            if (day < fromDay) continue;
            if (fromDay != Integer.MIN_VALUE && out.size() == UPCOMING_DATES) {
                more = true;
                break;
            }
            out.add(date(rules, day));
        }
        return String.join("、", out) + (more ? " 等" : "");
    }

    private static String gains(Rules rules, Rules.Activity activity) {
        List<String> gains = new ArrayList<>();
        for (Rules.Stat stat : rules.stats) {
            if (stat.idx >= 0 && stat.idx < activity.gain.length && activity.gain[stat.idx] > 0) {
                gains.add(stat.zh + " +" + activity.gain[stat.idx]);
            }
        }
        return String.join("、", gains);
    }

    private static void unlocks(Rules rules, SimState observed, Period period, Map<String, Plan.Reminder> items) {
        for (Rules.Link link : rules.links) {
            Rules.Unlock unlock = link.unlock;
            if (unlock == null || !inside(period, unlock.earliestDay)) continue;
            boolean unlocked = observed != null && link.idx >= 0 && link.idx < observed.rank.length
                    && observed.rank[link.idx] > 0;
            // A link the player already has is no news in its opening period.
            if (unlocked && !period.whole) continue;
            Plan.Reminder item = unlockTip(rules, observed, link);
            if (link.kind != Rules.LinkKind.STORY && !unlocked) period.ongoingIds.add(item.id);
            items.put(item.id, item);
        }
    }

    /** Shared opening instructions for both the calendar and the social-link lookup. */
    static Plan.Reminder unlockTip(Rules rules, SimState observed, Rules.Link link) {
        Rules.Unlock unlock = link.unlock;
        Plan.Reminder item = new Plan.Reminder();
        item.id = "unlock." + link.id;
        item.kind = Plan.Reminder.FEATURE;
        item.labelZh = "社群解锁";
        item.slot = Rules.slot(unlock.earliestDay, link.evening);
        String name = nonempty(link.labelZh, nonempty(link.arcanaZh, "社群"));
        boolean story = link.kind == Rules.LinkKind.STORY;
        item.textZh = name + (story ? " · 随剧情解锁" : "");
        List<String> lines = new ArrayList<>();
        lines.add(date(rules, unlock.earliestDay) + (story ? "起随剧情解锁。" : "起可以解锁。"));
        List<String> conditions = new ArrayList<>();
        for (Rules.Stat stat : rules.stats) {
            Integer level = unlock.statLv.get(stat.idx);
            if (level != null) conditions.add(stat.zh + (level >= 6 ? " MAX" : " Lv." + level));
        }
        for (Rules.Link prerequisite : rules.links) {
            Integer rank = unlock.linkRank.get(prerequisite.idx);
            if (rank != null) conditions.add(nonempty(prerequisite.arcanaZh, prerequisite.labelZh) + "社群 RANK " + rank);
        }
        if (!conditions.isEmpty()) lines.add("解锁条件：" + String.join("、", conditions) + "。");
        for (int i = 0; i < unlock.steps.size(); i++) {
            Rules.Step step = unlock.steps.get(i);
            List<String> notes = new ArrayList<>();
            notes.add(step.consumesSlot ? "会用掉" + (step.evening ? "晚上" : "白天") : "不会消耗时间");
            if (step.cost > 0) notes.add(String.format(Locale.ROOT, "%,d 日元", step.cost));
            if (i > 0 && step.minGapDays > 0) notes.add("和上一步隔 " + step.minGapDays + " 天以上");
            lines.add((i + 1) + ". " + nonempty(step.howZh, step.zh) + "（" + String.join("；", notes) + "）");
        }
        if (unlock.noteZh != null && !unlock.noteZh.isEmpty()) lines.add(unlock.noteZh);
        if (observed != null && link.idx >= 0 && link.idx < observed.rank.length && observed.rank[link.idx] > 0) {
            int rank = observed.rank[link.idx];
            lines.add("已经解锁了，现在 " + (rank >= 10 ? "MAX" : "RANK " + rank) + "。");
        }
        item.detailZh = String.join("\n", lines);
        item.estimate = "estimate".equals(unlock.conf) || "conflict".equals(unlock.conf);
        return item;
    }

    private static void episodes(Rules rules, SimState observed, Period period, Map<String, Plan.Reminder> items) {
        int today = Rules.dayOf(period.observedSlot);
        for (Rules.Episode track : rules.episodes) {
            for (int part = 1; part <= track.count; part++) {
                List<Integer> days = new ArrayList<>();
                for (int day = period.fromDay; day <= period.toDay; day++) {
                    if (track.allows(day, part)) days.add(day);
                }
                if (days.isEmpty()) continue;
                String dates = dates(rules, days, period.whole ? today : Integer.MIN_VALUE);
                Plan.Reminder item = new Plan.Reminder();
                item.id = "episode." + track.id + "." + part;
                item.kind = Plan.Reminder.FEATURE;
                item.labelZh = "羁绊剧情";
                item.slot = Rules.slot(days.get(0), track.evening);
                item.untilDay = track.lastDay(part);
                item.priority = Plan.Reminder.MISSABLE;
                item.textZh = nonempty(track.titleZh, "羁绊剧情 · " + track.personZh)
                        + "（第 " + track.sceneOf(part) + " 话）";
                List<String> lines = new ArrayList<>();
                if (!dates.isEmpty()) lines.add((track.evening ? "能看的晚上：" : "能看的日子：") + dates);
                lines.add("最晚 " + date(rules, item.untilDay) + "。要按顺序看"
                        + (part > 1 ? "，先看完第 " + track.sceneOf(part - 1) + " 话。" : "。"));
                String how = track.howOf(part);
                if (how != null && !how.isEmpty()) lines.add(how);
                String reward = track.rewardOf(part);
                if (reward != null && !reward.isEmpty()) lines.add("奖励：" + reward);
                if (track.rewardZh != null && !track.rewardZh.isEmpty()) lines.add(track.rewardZh);
                if (track.noteZh != null && !track.noteZh.isEmpty()) {
                    // The planner's own reason for keeping a day free is not news for the player (an
                    // older pack's wording; the 3.6 notes no longer say it).
                    lines.add(track.noteZh.replace("，这里已经先把那天空出来了", ""));
                }
                if (observed != null && track.idx >= 0 && track.idx < observed.episodes.length) {
                    int seen = Math.max(0, Math.min(track.count, observed.episodes[track.idx]));
                    if (track.hasUnplannedScenes()) {
                        lines.add((seen > 0 ? "已看第 " + track.sceneOf(seen) + " 话，" : "")
                                + "已赴约 " + seen + "/" + track.count + " 次。");
                    } else {
                        lines.add("已经看了 " + seen + " 话。");
                    }
                }
                item.detailZh = String.join("\n", lines);
                item.estimate = "estimate".equals(track.conf) || "conflict".equals(track.conf);
                items.put(item.id, item);
            }
        }
    }

    private static void datingNote(Rules rules, Period period, Map<String, Plan.Reminder> items) {
        Rules.DatingNote note = rules.datingNote;
        if (note == null || !inside(period, note.fromDay)) return;
        Plan.Reminder item = new Plan.Reminder();
        item.id = "feature.dating_note";
        item.kind = Plan.Reminder.FEATURE;
        item.labelZh = "新开放";
        item.slot = Rules.slot(note.fromDay, true);
        item.textZh = nonempty(note.zh, "结缘网站的字条") + "开始卖了 · "
                + String.format(Locale.ROOT, "%,d 日元", note.cost) + "，会用掉晚上";
        item.detailZh = (nonempty(note.howZh, "") + "\n" + nonempty(note.noteZh, "")).trim();
        List<String> conditions = new ArrayList<>();
        for (Rules.Stat stat : rules.stats) {
            Integer level = note.requiresLv.get(stat.idx);
            if (level != null) conditions.add(stat.zh + (level >= 6 ? " MAX" : " Lv." + level));
        }
        if (!conditions.isEmpty()) item.detailZh = append(item.detailZh, "要" + String.join("、", conditions) + " 才能进店买。");
        item.estimate = "estimate".equals(note.conf) || "conflict".equals(note.conf);
        items.put(item.id, item);
    }

    private static final class CalendarRange {
        String reason;
        int mask;
        int from;
        int until;
    }

    /** The pack's reserved-slot lock reads as a guess; the day's own note says what may happen. */
    private static final String RESERVED = "reserved";

    private static void calendar(Rules rules, Period period, Map<String, Plan.Reminder> items) {
        List<CalendarRange> ranges = new ArrayList<>();
        Map<String, CalendarRange> latest = new LinkedHashMap<>();
        Plan.Reminder lastNote = null;
        for (int day = period.fromDay; day <= period.toDay; day++) {
            Rules.Day date = rules.day(day);
            if (date == null) continue;
            String note = noteOf(date);
            if (!note.isEmpty() && !rescueNote(rules, day, date.noteZh)) {
                if (lastNote != null && lastNote.textZh.equals(note)
                        && Math.max(Rules.dayOf(lastNote.slot), lastNote.untilDay) == day - 1) {
                    // 暑期补习 six days running: one row over the range.
                    lastNote.untilDay = day;
                } else {
                    Plan.Reminder item = new Plan.Reminder();
                    item.id = "calendar.note." + day;
                    item.kind = "calendar";
                    item.labelZh = "日历事项";
                    item.slot = Rules.slot(day, false);
                    item.textZh = note;
                    items.put(item.id, item);
                    lastNote = item;
                }
            }
            if (date.dayLock != null) addRange(ranges, latest, date.dayLock,
                    date.dayLock.equals(date.eveLock) ? 3 : 1, day);
            if (date.eveLock != null && !date.eveLock.equals(date.dayLock)) {
                addRange(ranges, latest, date.eveLock, 2, day);
            }
            if (date.fullMoon && !"full_moon".equals(date.dayLock) && !"full_moon".equals(date.eveLock)
                    && !"finale".equals(date.eveLock)) {
                Plan.Reminder item = new Plan.Reminder();
                item.id = "calendar.full_moon." + day;
                item.kind = "calendar";
                item.labelZh = "重要日程";
                item.slot = Rules.slot(day, false);
                item.textZh = "满月作战";
                items.put(item.id, item);
            }
        }
        for (CalendarRange range : ranges) {
            String label = nonempty(rules.lockReasonZh(range.reason), "剧情事件");
            Rules.Day first = rules.day(range.from);
            String firstNote = first == null ? "" : noteOf(first);
            // A day whose note already says it (「暑期补习：学力 +3」, the reserved 1月21日) needs no second row.
            if (firstNote.startsWith(label) || (RESERVED.equals(range.reason) && !firstNote.isEmpty())) continue;
            Plan.Reminder item = new Plan.Reminder();
            item.id = "calendar.lock." + range.reason + "." + range.mask + "." + range.from;
            item.kind = "calendar";
            item.labelZh = "重要日程";
            item.slot = Rules.slot(range.from, range.mask == 2);
            item.untilDay = range.until;
            period.calendarEndSlots.put(item.id, Rules.slot(range.until, (range.mask & 2) != 0));
            String times = range.mask == 3 ? "白天和晚上" : range.mask == 2 ? "晚上" : "白天";
            item.textZh = label + " · " + times;
            items.put(item.id, item);
        }
    }

    /**
     * A day's note as 提示 shows it: holidays that change nothing are left out, and the planner's own
     * reasons (「所以先空出来」「默认按婉拒所有邀约来安排」) are cut. The 3.6 pack no longer words a
     * note so; the cut only guards an older pack.
     */
    private static String noteOf(Rules.Day date) {
        String note = date.noteZh == null ? "" : date.noteZh.trim();
        if (note.isEmpty()) return "";
        if (date.type == Rules.DayType.HOLIDAY && note.length() <= 6 && !note.matches(".*[：；，。].*")) return "";
        return note.replace("，所以先空出来", "").replace("默认按婉拒所有邀约来安排；", "");
    }

    private static void addRange(List<CalendarRange> ranges, Map<String, CalendarRange> latest,
                                 String reason, int mask, int day) {
        String key = reason + "/" + mask;
        CalendarRange previous = latest.get(key);
        if (previous != null && previous.until + 1 == day) {
            previous.until = day;
            return;
        }
        CalendarRange range = new CalendarRange();
        range.reason = reason;
        range.mask = mask;
        range.from = day;
        range.until = day;
        ranges.add(range);
        latest.put(key, range);
    }

    private static boolean rescueNote(Rules rules, int day, String note) {
        for (Rules.Rescue rescue : rules.rescues) {
            if ((rescue.fromDay == day || rescue.toDay == day) && rescue.zh != null && note.contains(rescue.zh)) return true;
        }
        return false;
    }

    private static String date(Rules rules, int day) {
        Rules.Day date = rules.day(day);
        return date == null ? "第 " + day + " 天" : date.month() + "月" + date.dayOfMonth() + "日";
    }

    private static String append(String original, String extra) {
        return original == null || original.isEmpty() ? extra : original + "\n" + extra;
    }

    private static String nonempty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
