package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Availability;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A read-only calendar of what is open, using recorded progress rather than the planned route. */
public final class AvailableActions {
    private AvailableActions() {}

    public static final class Entry {
        public final String key;
        public final String title;
        public final String detail;
        public final String categoryZh;
        public final int slot;
        public final int link;
        /** Null for a venue tip that is not a planner/recording action. */
        public final Action.Type type;
        public final boolean isInformational;

        private Entry(String key, String title, String detail, String categoryZh, int slot, int link,
                      Action.Type type) {
            this.key = key;
            this.title = title;
            this.detail = detail;
            this.categoryZh = categoryZh;
            this.slot = slot;
            this.link = link;
            this.type = type;
            this.isInformational = type == null;
        }
    }

    /** Equipped-Persona training, not the protagonist's three social stats. */
    private static final class PersonaArcade {
        final String id;
        final String statZh;
        final int weekdayMask;

        PersonaArcade(String id, String statZh, int... weekdays) {
            this.id = id;
            this.statZh = statZh;
            int mask = 0;
            for (int weekday : weekdays) mask |= 1 << (weekday - 1);
            this.weekdayMask = mask;
        }
    }

    // aqiu384 / marendarade, P3R Combat: Raising Persona Stats. Price is corroborated by
    // the Reload section of MegaTen Wiki's Paulownia Mall. See AVAILABILITY_CALENDAR.md.
    private static final PersonaArcade[] PERSONA_ARCADE = {
        new PersonaArcade("strength", "力", 1, 4, 7),
        new PersonaArcade("magic", "魔", 3, 6, 7),
        new PersonaArcade("agility", "速", 2, 5, 7),
    };

    public static final class Day {
        public final int day;
        public final List<Entry> daytime;
        public final List<Entry> evening;
        public final String daytimeLockZh;
        public final String eveningLockZh;
        public final List<String> unlocks;

        private Day(int day, List<Entry> daytime, List<Entry> evening, String daytimeLockZh,
                    String eveningLockZh, List<String> unlocks) {
            this.day = day;
            this.daytime = Collections.unmodifiableList(daytime);
            this.evening = Collections.unmodifiableList(evening);
            this.daytimeLockZh = daytimeLockZh;
            this.eveningLockZh = eveningLockZh;
            this.unlocks = Collections.unmodifiableList(unlocks);
        }
    }

    public static final class Calendar {
        public final int firstDay;
        public final int lastDay;
        private final Day[] days;
        private final Rules rules;
        private final Profile profile;
        private final SimState observed;
        private Availability.Query query;

        private Calendar(Rules rules, Profile profile, SimState observed) {
            this.rules = rules;
            this.observed = observed == null ? null : observed.copy();
            // Availability only depends on this profile choice. Goal and utility options must
            // not remove opportunities; later profile edits cannot change a captured calendar.
            this.profile = new Profile();
            if (profile != null) this.profile.christmasLink = profile.christmasLink;
            boolean empty = rules == null || observed == null || rules.days.length == 0;
            this.firstDay = empty ? 0 : rules.firstDay;
            this.lastDay = empty ? -1 : rules.lastDay;
            this.days = new Day[Math.max(0, lastDay + 1)];
        }

        /** Only requested days are expanded; switching tabs never builds the entire year. */
        public synchronized Day day(int day) {
            if (day < firstDay || day > lastDay || day < 0 || day >= days.length) return null;
            if (days[day] == null) {
                if (query == null) query = new Availability.Query(rules, profile, observed);
                Availability.Slot daytime = query.slot(Rules.slot(day, false));
                Availability.Slot evening = query.slot(Rules.slot(day, true));
                days[day] = new Day(day, entries(rules, observed, daytime), entries(rules, observed, evening),
                        lock(rules, daytime.lock), lock(rules, evening.lock), unlocks(rules, day));
            }
            return days[day];
        }
    }

    /** Cheap detached snapshot; the calendar caches individual days as screens request them. */
    public static Calendar build(Rules rules, Profile profile, SimState observed) {
        return new Calendar(rules, profile, observed);
    }

    private static String lock(Rules rules, String reason) {
        if (reason == null) return null;
        String title = rules.lockReasonZh(reason);
        return empty(title) ? "剧情事件" : title;
    }

    private static List<Entry> entries(Rules rules, SimState state, Availability.Slot slot) {
        List<Entry> entries = new ArrayList<>(slot.actions.size());
        for (Action action : slot.actions) {
            String title;
            String detail;
            String category;
            int link = -1;
            switch (action.type) {
                case MEET: {
                    Rules.Link person = rules.links[action.target];
                    title = linkLabel(person);
                    detail = person.locationZh;
                    category = "社群";
                    link = action.target;
                    break;
                }
                case UNLOCK_STEP: {
                    Rules.Link person = rules.links[action.target];
                    Rules.Step step = person.unlock.steps.get(action.step);
                    title = person.arcanaZh + " · " + step.zh;
                    List<String> parts = new ArrayList<>();
                    add(parts, step.locationZh);
                    if (!step.consumesSlot) add(parts, "不占时间");
                    int first = value(state.unlockStep, action.target);
                    long cost = 0;
                    for (int i = first; i <= action.step; i++) cost += Math.max(0, person.unlock.steps.get(i).cost);
                    if (cost > 0) add(parts, yen(cost) + (state.cashExact && cost > state.cash ? " · 现金不足" : ""));
                    detail = join(parts);
                    category = "解锁";
                    link = action.target;
                    break;
                }
                case ACTIVITY: {
                    Rules.Activity activity = rules.activities[action.target];
                    if (duplicateMeal(rules, slot.actions, activity)) continue;
                    title = activity.zh;
                    List<String> parts = new ArrayList<>();
                    add(parts, activity.locationZh);
                    String gain = gains(rules, activity.gain);
                    if (!empty(gain)) add(parts, gain);
                    if (activity.kind == Rules.ActivityKind.JOB) {
                        add(parts, "报酬 " + yen(activity.payFor(value(state.shifts, activity.idx) + 1)));
                    } else if (activity.cost > 0) {
                        add(parts, yen(activity.cost) + (state.cashExact && activity.cost > state.cash ? " · 现金不足" : ""));
                    } else {
                        add(parts, "免费");
                    }
                    if (activity.dormActivity()) add(parts, "已做 " + Math.min(3, value(state.used, activity.idx)) + "/3 次");
                    if (activity.requiresPlantedCrop) add(parts, "需种好作物");
                    detail = join(parts);
                    category = category(activity);
                    break;
                }
                case EPISODE: {
                    Rules.Episode episode = rules.episodes[action.target];
                    int next = value(state.episodes, episode.idx) + 1;
                    title = episode.personZh + " · 第 " + episode.sceneOf(next) + " 话";
                    List<String> parts = new ArrayList<>();
                    add(parts, episode.locationOf(next));
                    if (episode.hasUnplannedScenes()) add(parts, episode.howOf(next));
                    add(parts, gains(rules, episode.gainOf(next)));
                    add(parts, episode.rewardOf(next));
                    detail = join(parts);
                    category = "羁绊剧情";
                    break;
                }
                case TARTARUS: {
                    Rules.Block block = rules.blocks[action.target];
                    title = "探索塔尔塔罗斯";
                    detail = block.zh + " · " + block.floorsZh;
                    category = "塔尔塔罗斯";
                    break;
                }
                default: continue;
            }
            entries.add(new Entry(action.key(rules), title, empty(detail) ? "" : detail, category,
                    slot.slot, link, action.type));
        }
        addPersonaArcade(entries, rules, state, slot);
        return entries;
    }

    private static void addPersonaArcade(List<Entry> entries, Rules rules, SimState state,
                                        Availability.Slot slot) {
        if (slot.lock != null) return;
        Rules.Day day = rules.day(Rules.dayOf(slot.slot));
        if (day == null || day.wd < 1 || day.wd > 7 || !arcadeVenueOpen(rules, day.d)) return;
        for (PersonaArcade game : PERSONA_ARCADE) {
            if ((game.weekdayMask & (1 << (day.wd - 1))) == 0) continue;
            String detail = "桐叶购物中心 · 电玩大巡游 · " + yen(2000)
                + (state.cashExact && state.cash < 2000 ? " · 现金不足" : "")
                + " · 提升当前装备的面具";
            entries.add(new Entry("info:arcade_persona_" + game.id,
                "人格面具训练 · " + game.statZh + " +3", detail, "电玩城", slot.slot, -1, null));
        }
    }

    /** Share the recorded pack's venue-opening range, including Sundays without social games. */
    private static boolean arcadeVenueOpen(Rules rules, int day) {
        for (Rules.Activity activity : rules.activities) {
            if (activity.id != null && activity.id.startsWith("arcade_")
                    && day >= activity.fromDay && day <= activity.toDay) return true;
        }
        return false;
    }

    private static String category(Rules.Activity activity) {
        if (activity.dormActivity()) return "宿舍";
        if (activity.kind == Rules.ActivityKind.JOB) return "打工";
        if (activity.id.startsWith("group_study_")) return "集体学习";
        if (activity.id.startsWith("arcade_")) return "电玩城";
        if (activity.id.startsWith("study_") || "library".equals(activity.id)) return "学习";
        return "营业";
    }

    /** The first visit's menu-discovery entry already describes the otherwise identical meal. */
    private static boolean duplicateMeal(Rules rules, List<Action> actions, Rules.Activity activity) {
        if (!activity.id.startsWith("meal_") || activity.id.endsWith("_menu") || activity.id.endsWith("_special")) return false;
        for (Action candidate : actions) {
            if (candidate.type != Action.Type.ACTIVITY) continue;
            Rules.Activity other = rules.activities[candidate.target];
            if (other.id.equals(activity.id + "_menu") && other.cost == activity.cost
                    && Arrays.equals(other.gain, activity.gain)) return true;
        }
        return false;
    }

    private static List<String> unlocks(Rules rules, int day) {
        Set<String> lines = new LinkedHashSet<>();
        for (Rules.Link link : rules.links) {
            if (link.kind != Rules.LinkKind.MANUAL || link.unlock.earliestDay != day) continue;
            List<String> requirements = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : link.unlock.statLv.entrySet()) {
                if (entry.getKey() >= 0 && entry.getKey() < rules.stats.length) {
                    requirements.add(rules.stats[entry.getKey()].zh + " " + level(entry.getValue()));
                }
            }
            for (Map.Entry<Integer, Integer> entry : link.unlock.linkRank.entrySet()) {
                if (entry.getKey() >= 0 && entry.getKey() < rules.links.length) {
                    requirements.add(rules.links[entry.getKey()].arcanaZh + " RANK " + entry.getValue());
                }
            }
            lines.add(linkLabel(link) + "解锁" + (requirements.isEmpty() ? "" : " · " + join(requirements)));
        }
        Set<String> openingPlaces = new LinkedHashSet<>();
        for (Rules.Activity activity : rules.activities) {
            if (activity.fromDay != day || activity.dormActivity() || activity.fixedDays != null
                    || activity.id.endsWith("_menu") || activity.id.endsWith("_special")) continue;
            if (activity.kind == Rules.ActivityKind.JOB) lines.add(activity.zh + "开放");
            else if (day > rules.firstDay && !empty(activity.locationZh)) openingPlaces.add(activity.locationZh + "开放");
        }
        lines.addAll(openingPlaces);
        for (Rules.Block block : rules.blocks) {
            if (block.fromDay == day) lines.add(block.zh + "开放 · " + block.floorsZh);
        }
        for (Rules.Episode episode : rules.episodes) {
            if (episode.firstDay(1) == day) lines.add(episode.personZh + "的羁绊剧情开放");
        }
        for (Rules.Reminder reminder : rules.reminders.items) {
            if (reminder.day == day && "feature".equals(reminder.kind) && !reminder.firstEligible) add(lines, reminder.zh);
        }
        return new ArrayList<>(lines);
    }

    private static String linkLabel(Rules.Link link) {
        if (!empty(link.labelZh)) return link.labelZh;
        return empty(link.personZh) ? link.arcanaZh : link.arcanaZh + " · " + link.personZh;
    }

    private static String gains(Rules rules, int[] gains) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(rules.stats.length, gains.length); i++) {
            if (gains[i] > 0) parts.add(rules.stats[i].zh + " +" + gains[i]);
        }
        return join(parts);
    }

    private static String level(int level) { return level >= 6 ? "MAX" : "Lv." + level; }
    private static String yen(long amount) { return String.format(Locale.US, "%,d 日元", amount); }
    private static int value(int[] values, int index) { return index < 0 || index >= values.length ? 0 : values[index]; }
    private static boolean empty(String text) { return text == null || text.trim().isEmpty(); }
    private static String join(List<String> parts) { return String.join(" · ", parts); }
    private static void add(java.util.Collection<String> parts, String text) { if (!empty(text)) parts.add(text); }
}
