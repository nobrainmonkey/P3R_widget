package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The game's calendar opportunities, independently of the planner's goals and utility filters.
 * One model/table is shared by the whole calendar. No actions are forecast or recorded: ranks,
 * money and completed activities stay at the player's current recorded values.
 */
public final class Availability {
    private Availability() {}

    public static final class Slot {
        public final int slot;
        public final String lock;
        public final List<Action> actions;

        private Slot(int slot, String lock, List<Action> actions) {
            this.slot = slot;
            this.lock = lock;
            this.actions = Collections.unmodifiableList(actions);
        }
    }

    /** Reusable, read-only query context; tables are shared with the planner. */
    public static final class Query {
        private final Rules rules;
        private final Model m;
        private final SimState observed;

        public Query(Rules rules, Profile profile, SimState observed) {
            this.rules = rules;
            this.m = new Model(rules, profile == null ? new Profile() : profile, null);
            this.observed = Kernel.normalize(m, observed);
        }

        public Slot slot(int slot) {
            if (slot < rules.firstSlot() || slot > rules.lastSlot()) return null;
            SimState current = observed.copy();
            current.slot = slot;
            String lock = m.t.lock[slot];
            List<Action> actions = new ArrayList<>();
            if (lock == null) {
                int day = Rules.dayOf(slot);
                boolean evening = Rules.isEvening(slot);
                int mask = m.t.meetMask[slot];
                for (int l = 0; l < m.t.nLinks; l++) {
                    Rules.Link link = rules.links[l];
                    if ((mask & (1 << l)) != 0 && day >= link.unlock.earliestDay
                            && Kernel.meetLegal(m, current, l, day)) actions.add(Action.meet(l));
                    int step = unlock(m, current, l, day, evening);
                    if (step >= 0) actions.add(Action.unlock(l, step));
                }
                for (int a : m.t.actsAt[slot]) {
                    if (activityOpen(m, current, a)) actions.add(Action.activity(a));
                }
                // The planning pack deliberately keeps the common evening-only arcade subset.
                // Aqiu's archived regular-activity table marks these games B (day and evening),
                // corroborated by both stored GameFAQs sources. The browsing roster shows that
                // physical opening without changing the planner's candidates or transitions.
                // Source details: docs/AVAILABILITY_CALENDAR.md.
                if (!evening) {
                    Rules.Day date = rules.day(day);
                    for (Rules.Activity activity : rules.activities) {
                        if (daytimeArcade(activity, date) && activityOpen(m, current, activity.idx)) {
                            actions.add(Action.activity(activity.idx));
                        }
                    }
                }
                for (Rules.Episode episode : rules.episodes) {
                    int next = current.episodes[episode.idx] + 1;
                    if (episode.evening == evening && next <= episode.count && episode.allows(day, next)) {
                        actions.add(Action.episode(episode.idx));
                    }
                }
                int block = m.t.towerBlock[slot];
                if (block >= 0) actions.add(Action.tower(block));
            }
            return new Slot(slot, lock, actions);
        }
    }

    private static boolean daytimeArcade(Rules.Activity activity, Rules.Day date) {
        if (activity.day || date == null || !("arcade_answer".equals(activity.id)
                || "arcade_youth".equals(activity.id) || "arcade_deceased".equals(activity.id))) return false;
        if (date.d < activity.fromDay || date.d > activity.toDay || !activity.allowsWeekday(date.wd)) return false;
        if (activity.fixedDays != null && Arrays.binarySearch(activity.fixedDays, date.d) < 0) return false;
        return activity.dayTypes.isEmpty() || activity.dayTypes.contains(date.type);
    }

    /** Affordability is a displayed condition, not an opening-hours filter. */
    private static boolean activityOpen(Model m, SimState state, int index) {
        Rules.Activity activity = m.rules.activities[index];
        if (activity.once && state.used[index] > 0) return false;
        if (activity.requiresActivity >= 0 && state.used[activity.requiresActivity] <= 0) return false;
        for (Map.Entry<Integer, Integer> requirement : activity.requiresLv.entrySet()) {
            if (requirement.getKey() < m.t.nStats
                    && Kernel.statLv(m, state, requirement.getKey()) < requirement.getValue()) return false;
        }
        return true;
    }

    /** Include both consuming unlock groups and the currently pending slot-free step. */
    private static int unlock(Model m, SimState state, int link, int day, boolean evening) {
        Rules.Link person = m.rules.links[link];
        int first = state.unlockStep[link];
        if (person.kind != Rules.LinkKind.MANUAL || state.rank[link] != 0
                || first < 0 || first >= m.t.stepCount[link] || day < person.unlock.earliestDay
                || Kernel.blocked(m, state, link, day) || !Kernel.prereqsMet(m, state, link)) return -1;
        int last = m.t.groupEnd[link][first];
        if (last < 0) last = first;
        for (int s = first; s <= last; s++) {
            Rules.Step step = person.unlock.steps.get(s);
            if (step.evening != evening || !m.t.stepDayOk[link][s][day]) return -1;
            if (s == first && state.lastStepDay[link] >= 0
                    && day - state.lastStepDay[link] < step.minGapDays) return -1;
        }
        return last;
    }
}
