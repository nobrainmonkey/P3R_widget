package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;

/**
 * Pieces of a plan step shared by the hero card, the detail sheet, the result sheet and the
 * timeline: the 人格面具 reminder chip ({@link Plan.Step#hintZh}), the "顺便：…" side lines
 * ({@link Plan.Step#sideZh}) and the numbered how-to ({@link Plan.Step#detailZh}).
 */
final class StepViews {
    /** Leading list markers the how-to text may already carry ("1.", "2、", "①", "第 1 步："). */
    private static final Pattern MARKER = Pattern.compile(
        "^(?:[0-9]{1,2}\\s*[.、．)）:：]\\s*|[\\u2460-\\u2473]\\s*|第\\s*[0-9一二三四五六七八九十]+\\s*步\\s*[:：]?\\s*|[·•\\-]\\s+)");
    private static final String SIDE_PREFIX = "顺便：";

    private StepViews() {}

    static boolean hasHint(Plan.Step step) {
        return step != null && step.hintZh != null && !step.hintZh.trim().isEmpty();
    }

    static boolean hasSide(Plan.Step step) {
        return step != null && step.sideZh != null && !sideLines(step).isEmpty();
    }

    static boolean hasHowTo(Plan.Step step) {
        return step != null && !howToLines(step.detailZh).isEmpty();
    }

    /** The persona reminder chip: mask icon + "记得带上「皇帝」阿尔卡那的人格面具". */
    static View hintChip(Context c, String hint) {
        LinearLayout chip = Ui.row(c);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(c, R.color.ui_gold), 34))
            .stroke(Ui.col(c, R.color.ui_gold), Ui.dp(c, 1.5f))
            .skew(Ui.dp(c, 6)));
        Ui.pad(chip, 12, 6, 14, 6);
        chip.setMinimumHeight(Ui.dp(c, 36));
        chip.addView(Ui.icon(c, R.drawable.ui_ic_mask, Ui.col(c, R.color.ui_gold), 18));
        TextView text = Ui.mixed(c, hint.trim(), 14, Ui.col(c, R.color.p3r_ice_100), true);
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 8);
        chip.addView(text, tp);
        chip.setContentDescription(hint.trim());
        return chip;
    }

    /** The side lines, each starting with "顺便："; empty entries are dropped. */
    static List<String> sideLines(Plan.Step step) {
        List<String> out = new ArrayList<>();
        if (step == null || step.sideZh == null) {
            return out;
        }
        for (String line : step.sideZh) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            String t = line.trim();
            out.add(t.startsWith(SIDE_PREFIX) || t.startsWith("顺便") ? t : SIDE_PREFIX + t);
        }
        return out;
    }

    /** One compact block of "顺便：…" lines with a plus mark, in the attention colour. */
    static View sideBlock(Context c, Plan.Step step, float sp) {
        LinearLayout box = Ui.column(c);
        for (String line : sideLines(step)) {
            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.TOP);
            View mark = Ui.icon(c, R.drawable.ui_ic_plus, Ui.col(c, R.color.ui_gold), 14);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14));
            mp.topMargin = Ui.dp(c, (int) (sp * 0.28f));
            row.addView(mark, mp);
            TextView text = Ui.mixed(c, line, sp, Ui.col(c, R.color.ui_gold), false);
            LinearLayout.LayoutParams tp = Ui.weight(1);
            tp.leftMargin = Ui.dp(c, 6);
            row.addView(text, tp);
            LinearLayout.LayoutParams rp = Ui.matchWrap();
            rp.topMargin = box.getChildCount() == 0 ? 0 : Ui.dp(c, 2);
            box.addView(row, rp);
        }
        return box;
    }

    /** The how-to split into steps: one entry per non-empty line, list markers removed. */
    static List<String> howToLines(String detail) {
        List<String> out = new ArrayList<>();
        if (detail == null) {
            return out;
        }
        for (String raw : detail.split("\\r?\\n")) {
            String line = MARKER.matcher(raw.trim()).replaceFirst("").trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /** Numbered how-to ("怎么做"); a single line is shown as a plain paragraph. */
    static View howTo(Context c, String detail, float sp) {
        List<String> lines = howToLines(detail);
        LinearLayout box = Ui.column(c);
        if (lines.size() == 1) {
            TextView text = Ui.mixed(c, lines.get(0), sp, Ui.col(c, R.color.p3r_ice_100), false);
            text.setLineSpacing(0f, 1.25f);
            box.addView(text);
            return box;
        }
        for (int i = 0; i < lines.size(); i++) {
            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.TOP);
            TextView number = Ui.numeral(c, String.valueOf(i + 1), sp + 2, Ui.col(c, R.color.p3r_navy_950));
            number.setGravity(Gravity.CENTER);
            number.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_cyan_400)).skew(Ui.dp(c, 4)));
            int size = Ui.dp(c, sp + 8);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(size + Ui.dp(c, 6), size);
            np.topMargin = Ui.dp(c, 1);
            row.addView(number, np);
            TextView text = Ui.mixed(c, lines.get(i), sp, Ui.col(c, R.color.p3r_ice_100), false);
            text.setLineSpacing(0f, 1.2f);
            LinearLayout.LayoutParams tp = Ui.weight(1);
            tp.leftMargin = Ui.dp(c, 10);
            row.addView(text, tp);
            row.setContentDescription("第 " + (i + 1) + " 步：" + lines.get(i));
            LinearLayout.LayoutParams rp = Ui.matchWrap();
            rp.topMargin = i == 0 ? 0 : Ui.dp(c, 8);
            box.addView(row, rp);
        }
        return box;
    }
}
