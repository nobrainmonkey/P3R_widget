package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.StoryDisplay;

/**
 * Sheet for one slot: the date plate, the day's note in one line, then the step as on the 今天 hero
 * (kind tag, title, the rank line with one 「预计」 tag, 人格面具, place, the real answers, 顺便, an
 * urgent reason), its numbered 「怎么做」, the day's 「当天提醒」, and the hero's own actions: [做不了…]
 * [完成] for the next slot, 「已经玩到这里」 for a later one, 「剧情已过，继续」 for a story day to pass.
 * A past slot shows that day's records instead.
 */
final class DayDetailSheet {
    private DayDetailSheet() {}

    /** A slot the plan has nothing for. */
    static final String NO_STEP = "这个时段还没有安排";
    /** Catch up to a later slot (the result sheet then asks about the ones before it). */
    static final String REACHED = "已经玩到这里";

    static View build(HomeHost host, int slot) {
        Context c = host.activity();
        UiData d = host.data();
        Plan plan = d.displayPlan();
        Plan.Step step = plan == null ? null : plan.at(slot);
        LinearLayout column = Ui.column(c);
        Ui.pad(column, 20, 8, 20, 20);

        DatePlate plate = new DatePlate(c, false);
        plate.bind(d.rules, slot);
        column.addView(plate);
        int day = Rules.dayOf(slot);
        if (slot < d.displaySlot() && (d.snap.storyDisplay == null || !d.snap.storyDisplay.contains(slot))) {
            column.addView(HistoryPage.buildForDay(host, day, slot), top(c, 14));
            return column;
        }
        String note = dayNote(d.rules, d.profile(), day, step);
        if (note != null) {
            TextView line = Ui.mixed(c, note, 13, Ui.col(c, R.color.p3r_cyan_300), true);
            column.addView(line, top(c, 6));
        }
        if (step == null) {
            column.addView(Ui.body(c, NO_STEP), top(c, 16));
            addReminders(c, column, plan, day);
            return column;
        }
        StepKind kind = StepKind.of(step);
        column.addView(stepBlock(c, d, step, kind), top(c, 16));
        if (kind != StepKind.LOCKED && StepViews.hasHowTo(step)) {
            column.addView(StepViews.howToSection(c, step.detailZh, false, true), top(c, 16));
        }
        addReminders(c, column, plan, day);
        View actions = actions(host, d, step, kind);
        if (actions != null) {
            column.addView(actions, top(c, 22));
        }
        return column;
    }

    private static void addReminders(Context c, LinearLayout column, Plan plan, int day) {
        View reminders = ReminderViews.daySection(c, plan, day);
        if (reminders != null) {
            column.addView(reminders, top(c, 16));
        }
    }

    /** Kind tag, title, the rank line, 人格面具, place, answers, 顺便 and an urgent reason. */
    private static View stepBlock(Context c, UiData d, Plan.Step step, StepKind kind) {
        LinearLayout box = Ui.column(c);
        box.addView(TodayPage.kindTag(c, kind), Ui.wrap());
        String title = SlotRows.title(d.rules, step);
        box.addView(Ui.mixed(c, title, 24, Ui.col(c, R.color.p3r_white), true), top(c, 8));
        if (kind == StepKind.LOCKED) {
            String locked = lockedLine(step, title);
            if (locked != null) {
                box.addView(Ui.mixed(c, locked, 15, Ui.col(c, R.color.p3r_ink_muted), false), top(c, 4));
            }
            return box;
        }
        String subtitle = TodayPage.heroSubtitle(step, kind);
        if (!subtitle.isEmpty()) {
            LinearLayout subRow = Ui.row(c);
            subRow.addView(Ui.mixed(c, subtitle, 20, Ui.col(c, R.color.p3r_cyan_400), true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (step.estimate) {
                TextView est = Ui.tag(c, "预计", Ui.alpha(Ui.col(c, R.color.p3r_cyan_200), 40),
                    Ui.col(c, R.color.p3r_cyan_200));
                LinearLayout.LayoutParams ep = Ui.wrap();
                ep.leftMargin = Ui.dp(c, 10);
                subRow.addView(est, ep);
            }
            box.addView(subRow, top(c, 2));
        }
        if (StepViews.hasHint(step)) {
            box.addView(StepViews.hintChip(c, step.hintZh), top(c, 12));
        }
        if (step.locationZh != null && !step.locationZh.isEmpty()) {
            LinearLayout loc = Ui.row(c);
            loc.addView(Ui.icon(c, R.drawable.ui_ic_pin, Ui.col(c, R.color.p3r_cyan_300), 16));
            TextView where = Ui.text(c, step.locationZh, 15, Ui.col(c, R.color.p3r_ice_100), false);
            LinearLayout.LayoutParams wp = Ui.weight(1);
            wp.leftMargin = Ui.dp(c, 6);
            loc.addView(where, wp);
            box.addView(loc, top(c, 12));
        }
        // The answers once, short, as on the hero (the 怎么做 below leaves them out).
        String dialogue = Dialogue.shown(Dialogue.line(d.rules, d.profile(), step));
        if (dialogue != null) {
            box.addView(answer(c, dialogue), top(c, 10));
        }
        if (StepViews.hasSide(step)) {
            box.addView(StepViews.sideBlock(c, step, 14), top(c, 10));
        }
        String reason = TodayPage.urgentReason(step.whyZh);
        if (reason != null) {
            LinearLayout urgent = Ui.row(c);
            urgent.setGravity(Gravity.TOP);
            urgent.addView(Ui.numeral(c, "!", 16, Ui.col(c, R.color.ui_gold)), Ui.wrap());
            TextView why = Ui.mixed(c, reason, 14, Ui.col(c, R.color.ui_gold), false);
            LinearLayout.LayoutParams rp = Ui.weight(1);
            rp.leftMargin = Ui.dp(c, 8);
            urgent.addView(why, rp);
            box.addView(urgent, top(c, 10));
        }
        return box;
    }

    /** The guide's answers beside a thin cyan bar; 「要是升级了，答：…」 muted. */
    private static View answer(Context c, String line) {
        LinearLayout row = Ui.row(c);
        View bar = new View(c);
        bar.setBackgroundColor(Ui.col(c, R.color.p3r_cyan_300));
        row.addView(bar, new LinearLayout.LayoutParams(Ui.dp(c, 3), ViewGroup.LayoutParams.MATCH_PARENT));
        int ink = Dialogue.conditional(line) ? R.color.p3r_ink_muted : R.color.p3r_ice_100;
        TextView text = Ui.mixed(c, line, 15, Ui.col(c, ink), false);
        text.setLineSpacing(0f, 1.2f);
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 10);
        row.addView(text, tp);
        return row;
    }

    /**
     * The day's note as one line (「昭和之日」「期中考试放榜」), or null when there is none or the step
     * says it already (a story day's 「暑期补习：学力 +3」). 12/24 says what the player chose.
     */
    static String dayNote(Rules rules, Profile profile, int day, Plan.Step step) {
        Rules.Day info = rules == null ? null : rules.day(day);
        String note = info == null || info.noteZh == null ? "" : info.noteZh.trim();
        if (note.isEmpty()) {
            return null;
        }
        if (note.startsWith("平安夜：")) {
            // The pack explains how the plan treats the evening; the player only needs their choice.
            String link = profile == null ? null : profile.christmasLink;
            return "平安夜 · " + (link == null ? "邀约都婉拒了" : ChristmasChoice.answerText(rules, link));
        }
        if (step != null) {
            for (String said : new String[]{step.subtitleZh, step.detailZh, step.titleZh}) {
                if (said != null && Zh.clause(said).contains(Zh.clause(note))) {
                    return null;
                }
            }
        }
        return Zh.clause(note);
    }

    /**
     * The line under a story slot's title: its own note (a reserved slot says why it is kept), never
     * 「这段时间不能自由行动」 or the title again; null when there is nothing more to say.
     */
    static String lockedLine(Plan.Step step, String title) {
        String note = Zh.clause(step.subtitleZh);
        note = note.replace("这段时间不能自由行动", "");
        if (note.endsWith("，不能自由行动")) {
            note = note.substring(0, note.length() - "，不能自由行动".length());
        }
        note = note.trim();
        return note.isEmpty() || note.equals(title) ? null : note;
    }

    /** The actions of the 今天 hero for this slot, or null when there is nothing to do here. */
    private static View actions(HomeHost host, UiData d, Plan.Step step, StepKind kind) {
        Context c = host.activity();
        int current = d.currentSlot();
        if (d.snap.storyDisplay != null && d.snap.storyDisplay.cursorSlot == step.slot) {
            TextView go = Ui.primary(c, StoryDisplay.CONTINUE_LABEL);
            go.setContentDescription("剧情结束，继续到下一个时段");
            go.setOnClickListener(v -> {
                host.dismissSheet();
                host.commitDirect(step);
            });
            return go;
        }
        if (d.snap.storyDisplay != null && d.snap.storyDisplay.contains(step.slot)) {
            return null;
        }
        if (kind == StepKind.LOCKED && step.slot != current) {
            return null;
        }
        if (step.slot < current) {
            // Recorded already (a slot shown before the story's cursor): its record can be changed.
            TextView fix = Ui.secondary(c, "修改记录");
            fix.setOnClickListener(v -> {
                host.dismissSheet();
                host.pushPage(SchedulePage.HISTORY, HistoryPage.build(host));
            });
            return fix;
        }
        if (step.slot > current) {
            TextView reach = Ui.secondary(c, REACHED);
            reach.setOnClickListener(v -> {
                host.dismissSheet();
                host.openResult(step.slot, step.action, Intents.MODE_RESULT);
            });
            return reach;
        }
        LinearLayout row = Ui.row(c);
        row.setBaselineAligned(false);
        if (kind == StepKind.LOCKED) {
            TextView go = Ui.primary(c, "继续");
            go.setContentDescription("剧情结束，继续到下一个时段");
            go.setOnClickListener(v -> {
                host.dismissSheet();
                host.commitDirect(step);
            });
            row.addView(go, Ui.weight(1));
            return row;
        }
        // Exactly the hero's pair: the 做不了 sheet offers the reasons and 「改做别的…」.
        TextView cannot = Ui.secondary(c, TodayPage.CANNOT_LABEL);
        cannot.setTextSize(15);
        cannot.setContentDescription("做不了，选原因或改做别的");
        Ui.pad(cannot, 10, 8, 10, 8);
        cannot.setOnClickListener(v -> {
            host.dismissSheet();
            host.openResult(step.slot, step.action, Intents.MODE_CANNOT);
        });
        row.addView(cannot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView done = Ui.primary(c, TodayPage.DONE_LABEL);
        done.setContentDescription("完成，记录结果");
        done.setOnClickListener(v -> {
            host.dismissSheet();
            if (StepKind.oneTap(step)) {
                host.commitDirect(step);
            } else {
                host.openResult(step.slot, step.action, Intents.MODE_RESULT);
            }
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f);
        doneParams.leftMargin = Ui.dp(c, 14);
        row.addView(done, doneParams);
        return row;
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
