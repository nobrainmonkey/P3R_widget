package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;

/**
 * Detail sheet for one slot: what to do, where, dialogue hint, why the planner chose it, which
 * estimates it relies on, and the actions that make sense for its position (record now, change
 * this slot, or "做到这里" for a future item).
 */
final class DayDetailSheet {
    private DayDetailSheet() {}

    static View build(HomeHost host, int slot) {
        Context c = host.activity();
        UiData d = host.data();
        Plan plan = d.plan();
        Plan.Step step = plan == null ? null : plan.at(slot);
        LinearLayout column = Ui.column(c);
        Ui.pad(column, 20, 8, 20, 20);

        DatePlate plate = new DatePlate(c, false);
        plate.bind(d.rules, slot);
        column.addView(plate);
        if (step == null) {
            column.addView(Ui.body(c, "这个时段不在当前的计划里。"), top(c, 12));
            return column;
        }
        StepKind kind = StepKind.of(step);
        TextView tag = Ui.text(c, kind.tagZh, 12, Ui.col(c, R.color.p3r_cyan_300), true);
        column.addView(tag, top(c, 12));
        column.addView(Ui.mixed(c, SlotRows.title(d.rules, step), 22, Ui.col(c, R.color.p3r_white), true), top(c, 2));
        String subtitle = SlotRows.subtitle(step);
        if (!subtitle.isEmpty()) {
            column.addView(Ui.mixed(c, subtitle, 17, Ui.col(c, R.color.p3r_cyan_400), true), top(c, 2));
        }
        if (step.locationZh != null && !step.locationZh.isEmpty()) {
            LinearLayout loc = Ui.row(c);
            loc.addView(Ui.icon(c, R.drawable.ui_ic_pin, Ui.col(c, R.color.p3r_ink_muted), 16));
            TextView where = Ui.text(c, step.locationZh, 14, Ui.col(c, R.color.p3r_ice_100), false);
            LinearLayout.LayoutParams wp = Ui.weight(1);
            wp.leftMargin = Ui.dp(c, 6);
            loc.addView(where, wp);
            column.addView(loc, top(c, 10));
        }
        String dialogue = Dialogue.line(d.rules, step);
        if (dialogue != null) {
            column.addView(Ui.body(c, dialogue), top(c, 8));
        }
        if (step.whyZh != null && !step.whyZh.isEmpty()) {
            column.addView(Ui.sectionHeader(c, "为什么这样安排", null), top(c, 14));
            column.addView(Ui.mixed(c, step.whyZh, 14, Ui.col(c, R.color.p3r_ice_100), false));
        }
        List<String> notes = Assumptions.describeAll(step.assumptions, step.estimate);
        if (!notes.isEmpty()) {
            column.addView(Ui.sectionHeader(c, "其中的估计", null), top(c, 14));
            for (String note : notes) {
                TextView line = Ui.mixed(c, "· " + note, 13, Ui.col(c, R.color.p3r_ink_muted), false);
                line.setLineSpacing(0f, 1.25f);
                column.addView(line, top(c, 2));
            }
        }
        column.addView(actions(host, d, step, kind), top(c, 20));
        return column;
    }

    private static View actions(HomeHost host, UiData d, Plan.Step step, StepKind kind) {
        Context c = host.activity();
        int current = d.currentSlot();
        LinearLayout box = Ui.column(c);
        if (step.slot < current) {
            box.addView(Ui.muted(c, "这个时段已经记录过了。要更正可以去「我的 → 记录历史」。"));
            return box;
        }
        if (step.slot > current) {
            int pending = step.slot - current;
            box.addView(Ui.muted(c, "这是之后的安排。如果游戏里已经做到这里，点「做到这里」，再确认前面的 "
                + pending + " 个时段。"));
            TextView reach = Ui.primary(c, "做到这里");
            reach.setOnClickListener(v -> {
                host.dismissSheet();
                host.openResult(step.slot, step.action, Intents.MODE_RESULT);
            });
            box.addView(reach, top(c, 12));
            return box;
        }
        LinearLayout row = Ui.row(c);
        if (kind == StepKind.LOCKED) {
            TextView go = Ui.primary(c, "继续");
            go.setOnClickListener(v -> {
                host.dismissSheet();
                host.commitDirect(step);
            });
            row.addView(go, Ui.weight(1));
            box.addView(row);
            return box;
        }
        TextView change = Ui.secondary(c, "这个时段改为…");
        change.setOnClickListener(v -> {
            host.dismissSheet();
            host.openResult(step.slot, step.action, Intents.MODE_OTHER);
        });
        row.addView(change, Ui.weight(1));
        TextView record = Ui.primary(c, "记录结果");
        record.setOnClickListener(v -> {
            host.dismissSheet();
            host.openResult(step.slot, step.action, Intents.MODE_RESULT);
        });
        LinearLayout.LayoutParams rp = Ui.weight(1);
        rp.leftMargin = Ui.dp(c, 12);
        row.addView(record, rp);
        box.addView(row);
        TextView cannot = Ui.quiet(c, "做不了");
        cannot.setOnClickListener(v -> {
            host.dismissSheet();
            host.openResult(step.slot, step.action, Intents.MODE_CANNOT);
        });
        box.addView(cannot, top(c, 6));
        return box;
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
