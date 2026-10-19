package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/** Compact slot lines used by 今天 ("接下来") and the 日程 timeline; long titles wrap to two lines. */
final class SlotRows {
    /** Where a slot stands relative to the player's progress. */
    enum State { DONE, CURRENT, FUTURE }

    /** Lock reason id of the 12/24 slots taken by an accepted invitation (shared contract). */
    static final String CHRISTMAS_LOCK = "christmas_date";

    private SlotRows() {}

    /** Title for a step; story locks fall back to the rule pack's reason text. */
    static String title(Rules rules, Plan.Step step) {
        if (step.titleZh != null && !step.titleZh.isEmpty()) {
            return step.titleZh;
        }
        if ("locked".equals(step.kind) && rules != null && step.action.startsWith("locked:")) {
            String id = step.action.substring(7);
            String reason = rules.lockReasonsZh.get(id);
            if (reason != null) {
                return reason;
            }
            if (CHRISTMAS_LOCK.equals(id)) {
                // An accepted 12/24 invitation (Profile.christmasLink) is not a lock of the pack.
                return "平安夜约会";
            }
        }
        return "locked".equals(step.kind) ? "剧情" : "自由活动";
    }

    /** Second line; estimated rank-ups get the "≈" prefix. */
    static String subtitle(Plan.Step step) {
        String sub = step.subtitleZh == null ? "" : step.subtitleZh;
        boolean estimatedRankUp = step.predicted != null && step.predicted.rankUp && !step.predicted.certain;
        if (estimatedRankUp && !sub.isEmpty() && !sub.startsWith("≈")) {
            return "≈ " + sub;
        }
        return sub;
    }

    /** A new bound line. */
    static Line line(Context c, Rules rules, Plan.Step step, State state, boolean showPeriod) {
        Line line = new Line(c);
        line.bind(rules, step, state, showPeriod);
        return line;
    }

    /**
     * One slot: optional period label, kind icon, title (up to two lines), subtitle and the
     * "顺便：…" side steps. Locked slots are grey; recorded slots are dimmed with a tick.
     * Rebindable for list recycling.
     */
    static final class Line extends LinearLayout {
        private final TextView period;
        private final ImageView icon;
        private final TextView title;
        private final TextView subtitle;
        private final TextView side;

        Line(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(Ui.dp(c, 44));
            period = Ui.text(c, "", 12, Ui.col(c, R.color.p3r_ink_muted), true);
            period.setMinWidth(Ui.dp(c, 46));
            addView(period, Ui.wrap());
            icon = new ImageView(c);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams iconParams = new LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18));
            iconParams.rightMargin = Ui.dp(c, 10);
            addView(icon, iconParams);
            LinearLayout texts = Ui.column(c);
            Ui.pad(texts, 0, 3, 0, 3);
            title = Ui.text(c, "", 15, Ui.col(c, R.color.p3r_white), false);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            subtitle = Ui.text(c, "", 13, Ui.col(c, R.color.p3r_cyan_300), false);
            subtitle.setMaxLines(2);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle);
            side = Ui.text(c, "", 12, Ui.col(c, R.color.ui_gold), false);
            side.setMaxLines(2);
            side.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(side);
            addView(texts, Ui.weight(1));
        }

        void bind(Rules rules, Plan.Step step, State state, boolean showPeriod) {
            Context c = getContext();
            StepKind kind = StepKind.of(step);
            boolean locked = kind == StepKind.LOCKED;
            int ink = locked ? Ui.col(c, R.color.ui_locked) : Ui.col(c, R.color.p3r_white);
            int sub = locked ? Ui.col(c, R.color.ui_locked) : Ui.col(c, R.color.p3r_cyan_300);
            int iconColor = locked ? Ui.col(c, R.color.ui_locked) : Ui.col(c, R.color.p3r_cyan_400);
            if (state == State.DONE) {
                ink = Ui.col(c, R.color.p3r_ink_faint);
                sub = ink;
                iconColor = ink;
            }
            period.setVisibility(showPeriod ? VISIBLE : GONE);
            period.setText(Zh.period(rules, step.slot));
            period.setTextColor(state == State.CURRENT ? Ui.col(c, R.color.p3r_cyan_400) : Ui.col(c, R.color.p3r_ink_muted));
            icon.setImageDrawable(Ui.tinted(c, state == State.DONE ? R.drawable.ui_ic_check : kind.iconRes, iconColor));
            String titleText = title(rules, step);
            title.setText(Ui.latin(c, titleText));
            title.setTextColor(ink);
            title.setTypeface(state == State.CURRENT ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
            String subText = locked ? "" : subtitle(step);
            subtitle.setText(Ui.latin(c, subText));
            subtitle.setTextColor(sub);
            subtitle.setVisibility(subText.isEmpty() ? GONE : VISIBLE);
            String sideText = locked ? "" : String.join("；", StepViews.sideLines(step));
            side.setText(Ui.latin(c, sideText));
            side.setTextColor(state == State.DONE ? ink : Ui.col(c, R.color.ui_gold));
            side.setVisibility(sideText.isEmpty() ? GONE : VISIBLE);
            String state0 = state == State.DONE ? "已记录，" : state == State.CURRENT ? "下一步，" : "";
            setContentDescription(state0 + Zh.period(rules, step.slot) + "，" + titleText
                + (subText.isEmpty() ? "" : "，" + subText.replace("≈", "预计"))
                + (sideText.isEmpty() ? "" : "，" + sideText));
        }
    }
}
