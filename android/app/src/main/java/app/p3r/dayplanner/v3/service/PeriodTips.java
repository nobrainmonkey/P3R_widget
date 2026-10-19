package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.engine.RescueLocations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only catalogue for 提示: a Tartarus block's opening day through the day before the next opens.
 * Unlike a plan's daily reminders, this keeps the entire period when the player opens it midway through.
 * It records neither completion nor stat gains, and never changes the plan or observed state.
 */
public final class PeriodTips {
    private PeriodTips() {}

    public static final class Period {
        public int fromDay;
        public int toDay = -1;
        public int observedSlot;
        public String blockZh = "当前阶段";
        public String floorsZh = "";
        /** Original event/opening dates are retained, including a carried-over window's earlier date. */
        public List<Plan.Reminder> items = new ArrayList<>();
        /** Unbounded conditional opportunities: the opening date passing does not expire them. */
        public Set<String> ongoingIds = new LinkedHashSet<>();
        /** Exact last occupied slot of calendar ranges, including daytime-only exam periods. */
        public Map<String, Integer> calendarEndSlots = new LinkedHashMap<>();
    }

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
        return collect(rules, observed, null, period);
    }

    private static Period collect(Rules rules, SimState observed, Plan plan, Period period) {
        Rules.Reminders data = rules.reminders;
        if (data == null || period.toDay < period.fromDay) return period;

        Map<String, Plan.Reminder> items = new LinkedHashMap<>();
        Set<String> conditionalItems = new LinkedHashSet<>();
        for (Rules.Reminder source : data.items) {
            int until = source.untilDay;
            boolean ongoingRequest = "request.05-29.outings".equals(source.id)
                    || "request.10-06.faculty".equals(source.id);
            if ((source.firstEligible || ongoingRequest) && until < 0) until = rules.lastDay;
            if (!overlaps(period, source.day, until)) continue;
            Plan.Reminder item = from(source);
            String condition = requirements(rules, source);
            if (source.firstEligible) {
                // The catalogue cannot know whether an unrecorded, slot-free pickup was already done.
                item.textZh = "满足条件后可处理：" + nonempty(source.shortZh, source.labelZh);
                if (source.untilDay < 0) period.ongoingIds.add(item.id);
            } else if (!condition.isEmpty()) {
                item.textZh = "达到" + condition + "时：" + source.zh;
            }
            if (!condition.isEmpty()) item.detailZh = append(item.detailZh, "条件：" + condition + "。");
            if (ongoingRequest) period.ongoingIds.add(item.id);
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
            item.textZh = "不闭眼：学力 +" + data.dozeAcademics + "；闭眼：勇气 +" + data.dozeCourage;
            item.detailZh = "学力未满选不闭眼；学力已满、勇气未满选闭眼。";
            items.put(item.id, item);
        }

        if (data.nurseCourage > 0) {
            Plan.Reminder item = new Plan.Reminder();
            item.id = "period.nurse";
            item.kind = Plan.Reminder.NURSE;
            item.labelZh = "保健室";
            item.slot = Rules.slot(period.fromDay, false);
            item.textZh = "探索塔尔塔罗斯或满月作战后的上学日，顺路去保健室";
            item.detailZh = "前一晚探索了塔尔塔罗斯或打了满月作战，第二天若是上学日，放学后到 1 楼保健室喝药，勇气 +"
                    + data.nurseCourage + "，不消耗时间。仅限次日上学时，勇气满了可跳过。";
            items.put(item.id, item);
            period.ongoingIds.add(item.id);
        }

        // Forecasts refine future advice (e.g. which stat is capped), but never erase past catalogue entries.
        if (plan != null) {
            for (Plan.Reminder item : plan.reminders) {
                if (Plan.Reminder.RESCUE.equals(item.kind)) continue;
                if (conditionalItems.contains(item.id)) {
                    Plan.Reminder catalogue = items.get(item.id);
                    if (catalogue.slot != item.slot) {
                        catalogue.detailZh = append(catalogue.detailZh,
                                "安排：" + date(rules, Rules.dayOf(item.slot)) + "处理。");
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
        StringBuilder detail = new StringBuilder("在塔尔塔罗斯探索时找到失踪者并救出。救援窗口：")
                .append(date(rules, group.fromDay)).append("～").append(date(rules, group.toDay))
                .append("。最晚在期限当天晚上救出，逾期无法救援。");
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
                    detail.append("记录里已确认救出").append(person.zh).append("。");
                } else if (observed != null && (observed.rescueFailed & (1L << i)) != 0) {
                    detail.append("记录里").append(person.zh).append("的救援已错过。");
                }
                break;
            }
        }
        if (group.locksZh != null) detail.append(group.locksZh);
        int tower = firstTower(plan, Math.max(Rules.slot(group.fromDay, true),
                observed == null ? rules.firstSlot() : observed.slot), Rules.slot(group.toDay, true));
        if (tower >= 0) detail.append("安排：").append(date(rules, Rules.dayOf(tower))).append("进塔，顺便救援。");
        item.textZh = "失踪者 " + group.count + " 人" + (names.isEmpty() ? "" : "（"
                + (names.size() < group.count ? "包括" : "") + String.join("、", names) + "）")
                + "，" + date(rules, group.toDay) + "截止";
        String floors = RescueLocations.floors(java.util.Collections.singletonList(group));
        if (!floors.isEmpty()) item.textZh += " · " + floors;
        item.shortZh = "失踪者 " + group.count + " 人";
        item.detailZh = detail.toString();
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

    private static void activities(Rules rules, SimState observed, Period period, Map<String, Plan.Reminder> items) {
        List<String> dormLines = new ArrayList<>();
        Set<String> partners = new LinkedHashSet<>();
        for (Rules.Activity activity : rules.activities) {
            if (activity.fixedDays == null) continue;
            List<String> dates = new ArrayList<>();
            for (int day : activity.fixedDays) if (inside(period, day)) dates.add(date(rules, day));
            if (dates.isEmpty()) continue;
            String gains = gains(rules, activity);
            if (activity.id.startsWith("group_study_")) {
                for (int day : activity.fixedDays) {
                    if (!inside(period, day)) continue;
                    Plan.Reminder item = new Plan.Reminder();
                    item.id = "activity." + activity.id + "." + day;
                    item.kind = Plan.Reminder.FEATURE;
                    item.labelZh = "集体学习";
                    item.slot = Rules.slot(day, true);
                    item.textZh = activity.zh + (gains.isEmpty() ? "" : "：" + gains) + "，占用晚上";
                    item.gainZh = gains.isEmpty() ? null : gains;
                    item.detailZh = append(activity.noteZh, "到" + nonempty(activity.locationZh, "宿舍大厅")
                            + "找队友一起学习。免费，占用整个晚上；学力已满可跳过。");
                    item.estimate = "estimate".equals(activity.conf) || "conflict".equals(activity.conf);
                    items.put(item.id, item);
                }
            } else if (activity.dormActivity()) {
                partners.add(activity.dormPartnerZh);
                String progress;
                if (observed == null) {
                    progress = "尚未同步进度";
                } else {
                    int count = activity.idx >= 0 && activity.idx < observed.used.length
                            ? Math.max(0, observed.used[activity.idx]) : 0;
                    progress = "已记录 " + count + " 次（特性进度 " + Math.min(3, count) + "/3）";
                }
                dormLines.add(activity.dormPartnerZh + " · " + activity.zh + "\n"
                        + "本期晚上：" + String.join("、", dates) + "\n" + progress
                        + (gains.isEmpty() ? "；不加个人能力值" : "；" + gains)
                        + (activity.noteZh == null || activity.noteZh.isEmpty() ? "" : "。" + activity.noteZh));
            }
        }
        if (!dormLines.isEmpty()) {
            Plan.Reminder item = new Plan.Reminder();
            item.id = "period.dorm";
            item.kind = Plan.Reminder.FEATURE;
            item.labelZh = "宿舍活动";
            item.slot = Rules.slot(period.fromDay, false);
            item.textZh = "本期可选宿舍活动：" + partners.size() + " 位队友，查看日期和进度";
            item.detailZh = "免费，占用整个晚上。同一项目完成 3 次解锁队友特性，两个项目各完成 3 次后强化。"
                    + "属性已满仍可推进特性。\n"
                    + "园艺需提前种好作物，成熟后收获、再种苗。安排园艺前，在「同步现状」→「宿舍活动」开启「允许安排园艺」。\n\n"
                    + String.join("\n\n", dormLines);
            items.put(item.id, item);
            period.ongoingIds.add(item.id);
        }
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
            Plan.Reminder item = new Plan.Reminder();
            item.id = "unlock." + link.id;
            item.kind = Plan.Reminder.FEATURE;
            item.labelZh = "社群解锁";
            item.slot = Rules.slot(unlock.earliestDay, link.evening);
            String name = nonempty(link.labelZh, nonempty(link.arcanaZh, "社群"));
            boolean story = link.kind == Rules.LinkKind.STORY;
            item.textZh = name + (story ? "：随剧情自动开启" : "：本期起可尝试解锁");
            List<String> lines = new ArrayList<>();
            lines.add("最早" + date(rules, unlock.earliestDay) + (story ? "随剧情开启。" : "开放。"));
            List<String> conditions = new ArrayList<>();
            for (Rules.Stat stat : rules.stats) {
                Integer level = unlock.statLv.get(stat.idx);
                if (level != null) conditions.add(stat.zh + (level >= 6 ? " MAX" : " Lv." + level));
            }
            for (Rules.Link prerequisite : rules.links) {
                Integer rank = unlock.linkRank.get(prerequisite.idx);
                if (rank != null) conditions.add(nonempty(prerequisite.arcanaZh, prerequisite.labelZh) + "社群 RANK " + rank);
            }
            if (!conditions.isEmpty()) lines.add("前置条件：" + String.join("、", conditions) + "。");
            for (int i = 0; i < unlock.steps.size(); i++) {
                Rules.Step step = unlock.steps.get(i);
                String time = step.consumesSlot ? "占用" + (step.evening ? "晚上" : "白天") : "不消耗时间";
                lines.add((i + 1) + ". " + nonempty(step.howZh, step.zh) + "（" + time
                        + (step.cost > 0 ? "；花费 " + step.cost + " 日元" : "")
                        + (i > 0 && step.minGapDays > 0 ? "；与上一步至少间隔 " + step.minGapDays + " 天" : "") + "）");
            }
            if (unlock.noteZh != null && !unlock.noteZh.isEmpty()) lines.add(unlock.noteZh);
            if (observed != null && link.idx >= 0 && link.idx < observed.rank.length && observed.rank[link.idx] > 0) {
                lines.add("当前记录已建立此社群：RANK " + observed.rank[link.idx] + "。");
            } else if (!story) {
                period.ongoingIds.add(item.id);
            }
            item.detailZh = String.join("\n", lines);
            item.estimate = "estimate".equals(unlock.conf) || "conflict".equals(unlock.conf);
            items.put(item.id, item);
        }
    }

    private static void episodes(Rules rules, SimState observed, Period period, Map<String, Plan.Reminder> items) {
        for (Rules.Episode track : rules.episodes) {
            for (int part = 1; part <= track.count; part++) {
                List<String> dates = new ArrayList<>();
                int first = -1;
                for (int day = period.fromDay; day <= period.toDay; day++) {
                    if (!track.allows(day, part)) continue;
                    if (first < 0) first = day;
                    dates.add(date(rules, day));
                }
                if (first < 0) continue;
                Plan.Reminder item = new Plan.Reminder();
                item.id = "episode." + track.id + "." + part;
                item.kind = Plan.Reminder.FEATURE;
                item.labelZh = "羁绊剧情";
                item.slot = Rules.slot(first, track.evening);
                item.untilDay = track.lastDay(part);
                item.priority = Plan.Reminder.MISSABLE;
                item.textZh = nonempty(track.titleZh, "羁绊剧情 · " + track.personZh) + "（第 " + part + " 话）：查看本期日期";
                String detail = "本期可看日期：" + String.join("、", dates) + "（" + (track.evening ? "晚上" : "白天") + "）。\n"
                        + "这一话最后可看日期：" + date(rules, item.untilDay) + "；需要按顺序看，错过前面的剧情可能无法继续后续。"
                        + "每话占用一个" + (track.evening ? "晚上" : "白天时段") + "。\n"
                        + nonempty(track.howZh, "") + "\n";
                String reward = track.rewardOf(part);
                if (reward != null && !reward.isEmpty()) detail += "这一话奖励：" + reward + "。\n";
                if (track.rewardZh != null && !track.rewardZh.isEmpty()) detail += track.rewardZh + "\n";
                if (track.noteZh != null && !track.noteZh.isEmpty()) detail += track.noteZh + "\n";
                if (observed != null && track.idx >= 0 && track.idx < observed.episodes.length) {
                    detail += "当前记录已看 " + observed.episodes[track.idx] + " 话。\n";
                }
                detail += "在「社群」页勾选对应的「羁绊剧情」可纳入安排。";
                item.detailZh = detail;
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
        item.textZh = nonempty(note.zh, "交友网站笔记") + "开始出售：花费 " + note.cost + " 日元，占用晚上";
        item.detailZh = nonempty(note.howZh, "") + "\n" + nonempty(note.noteZh, "");
        List<String> conditions = new ArrayList<>();
        for (Rules.Stat stat : rules.stats) {
            Integer level = note.requiresLv.get(stat.idx);
            if (level != null) conditions.add(stat.zh + (level >= 6 ? " MAX" : " Lv." + level));
        }
        if (!conditions.isEmpty()) item.detailZh = append(item.detailZh, "进入购买地点需要：" + String.join("、", conditions) + "。");
        item.estimate = "estimate".equals(note.conf) || "conflict".equals(note.conf);
        items.put(item.id, item);
    }

    private static final class CalendarRange {
        String reason;
        int mask;
        int from;
        int until;
    }

    private static void calendar(Rules rules, Period period, Map<String, Plan.Reminder> items) {
        List<CalendarRange> ranges = new ArrayList<>();
        Map<String, CalendarRange> latest = new LinkedHashMap<>();
        for (int day = period.fromDay; day <= period.toDay; day++) {
            Rules.Day date = rules.day(day);
            if (date == null) continue;
            if (date.noteZh != null && !date.noteZh.isEmpty() && !rescueNote(rules, day, date.noteZh)) {
                Plan.Reminder item = new Plan.Reminder();
                item.id = "calendar.note." + day;
                item.kind = "calendar";
                item.labelZh = "日历事项";
                item.slot = Rules.slot(day, false);
                item.textZh = date.noteZh;
                item.detailZh = date.noteZh;
                items.put(item.id, item);
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
                item.textZh = "满月作战日";
                item.detailZh = "满月剧情与作战。";
                items.put(item.id, item);
            }
        }
        for (CalendarRange range : ranges) {
            Plan.Reminder item = new Plan.Reminder();
            item.id = "calendar.lock." + range.reason + "." + range.mask + "." + range.from;
            item.kind = "calendar";
            item.labelZh = "重要日程";
            item.slot = Rules.slot(range.from, range.mask == 2);
            item.untilDay = range.until;
            period.calendarEndSlots.put(item.id, Rules.slot(range.until, (range.mask & 2) != 0));
            String label = nonempty(rules.lockReasonZh(range.reason), "剧情安排");
            String times = range.mask == 3 ? "白天和晚上" : range.mask == 2 ? "晚上" : "白天";
            item.textZh = label + "：" + date(rules, range.from)
                    + (range.until == range.from ? "" : "～" + date(rules, range.until)) + "（" + times + "）";
            item.detailZh = times + "用于" + label + "。";
            items.put(item.id, item);
        }
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
