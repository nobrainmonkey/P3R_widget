package app.p3r.dayplanner.v3.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/** Story days to show before the next actionable slot. This is presentation, never a forecast record. */
public final class StoryDisplay {
    public static final String CONTINUE_LABEL = "剧情已过，继续";

    public final String key;
    public final int fromSlot;
    public final int toSlot;
    public final int cursorSlot;
    public final List<Plan.Step> steps;
    private final List<Plan.Reminder> answers;

    private StoryDisplay(String key, int fromSlot, int toSlot, int cursorSlot, List<Plan.Step> steps,
                         List<Plan.Reminder> answers) {
        this.key = key;
        this.fromSlot = fromSlot;
        this.toSlot = toSlot;
        this.cursorSlot = cursorSlot;
        this.steps = Collections.unmodifiableList(steps);
        this.answers = Collections.unmodifiableList(answers);
    }

    /** Null once this interval has been acknowledged, or when it contains no story slots. */
    public static StoryDisplay create(Rules rules, Profile profile, String key, int from, int to, int cursor) {
        if (rules == null || key == null || from < 0 || to < from || cursor > to) return null;
        int first = Math.max(from, cursor);
        List<Plan.Step> steps = new ArrayList<>();
        for (int slot = first; slot <= to; slot++) {
            String reason = profile == null ? rules.lockOf(slot) : profile.lockOf(rules, slot);
            if (reason == null) return null;
            Plan.Step step = new Plan.Step();
            step.slot = slot;
            step.action = "locked:" + reason;
            step.kind = "locked";
            String title = rules.lockReasonZh(reason);
            step.titleZh = title == null || title.trim().isEmpty() ? "剧情事件" : title;
            Rules.Day day = rules.day(Rules.dayOf(slot));
            String note = day == null || day.noteZh == null ? "" : day.noteZh.trim();
            if (Rules.LOCK_CHRISTMAS.equals(reason)) {
                Rules.Link partner = profile == null ? null : rules.link(profile.christmasLink);
                note = partner == null ? "已经答应了平安夜的邀约"
                        : "已经答应了" + partner.personZh + "的平安夜邀约";
            }
            step.subtitleZh = note.isEmpty() ? "这段时间不能自由行动" : note;
            step.detailZh = note.isEmpty() ? null : note;
            steps.add(step);
        }
        List<Plan.Reminder> answers = new ArrayList<>();
        if (rules.reminders != null) {
            for (Rules.Reminder source : rules.reminders.items) {
                int slot = Rules.slot(source.day, source.evening);
                if (slot < first || slot > to || (!"class".equals(source.kind) && !"exam".equals(source.kind))) continue;
                Plan.Reminder answer = new Plan.Reminder();
                answer.id = source.id;
                answer.slot = slot;
                answer.kind = source.kind;
                answer.labelZh = source.labelZh;
                answer.textZh = source.zh;
                answer.shortZh = source.shortZh;
                answer.answerZh = source.answerZh;
                answer.option = source.option;
                answer.detailZh = source.detailZh;
                answer.gainZh = source.gainZh;
                answer.priority = source.priority;
                answer.estimate = "estimate".equals(source.conf);
                answers.add(answer);
            }
        }
        return steps.isEmpty() ? null : new StoryDisplay(key, from, to, first, steps, answers);
    }

    public boolean contains(int slot) {
        return slot >= cursorSlot && slot <= toSlot;
    }

    public Plan.Step at(int slot) {
        return contains(slot) ? steps.get(slot - cursorSlot) : null;
    }

    /** A card never joins different calendar days or different story reasons. */
    public int currentLastSlot() {
        int last = cursorSlot;
        String action = steps.get(0).action;
        while (last < toSlot && Rules.dayOf(last + 1) == Rules.dayOf(cursorSlot)
                && action.equals(at(last + 1).action)) last++;
        return last;
    }

    /** A temporary display plan; it is never installed, hashed, validated or recorded. */
    public Plan asPlan() {
        Plan plan = new Plan();
        plan.fromSlot = cursorSlot;
        plan.toSlot = toSlot;
        plan.steps.addAll(steps);
        plan.reminders.addAll(answers);
        return plan;
    }

    /** Prepend pending story days without editing the actual forecast or its summary. */
    public Plan overlay(Plan forecast, int observedSlot) {
        Plan display = asPlan();
        if (forecast == null) return display;
        display.planId = forecast.planId;
        display.stateRevision = forecast.stateRevision;
        display.profileRevision = forecast.profileRevision;
        display.rulesVersion = forecast.rulesVersion;
        display.summary = forecast.summary;
        for (int slot = observedSlot; slot <= forecast.toSlot; slot++) {
            Plan.Step step = forecast.at(slot);
            if (step == null || slot != display.toSlot + 1) break;
            display.steps.add(step);
            display.toSlot = slot;
        }
        for (Plan.Reminder reminder : forecast.reminders) {
            if (reminder.slot >= observedSlot && reminder.slot <= display.toSlot) display.reminders.add(reminder);
        }
        return display;
    }
}
