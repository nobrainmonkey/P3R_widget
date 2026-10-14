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
    /** How-to lines that annotate the steps instead of being one (engine Labels.NOTE_PREFIX, Dialogue.PREFIX). */
    static final String NOTE_PREFIX = "注意：";

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
        Ui.pad(chip, 10, 6, 10, 6);
        chip.setMinimumHeight(Ui.dp(c, 36));
        chip.addView(Ui.icon(c, R.drawable.ui_ic_mask, Ui.col(c, R.color.ui_gold), 18));
        TextView text = Ui.mixed(c, hint.trim(), 13, Ui.col(c, R.color.p3r_ice_100), true);
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 6);
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

    /**
     * A how-to line shown under the numbered steps rather than as one: 「注意：…」, 「参考回答：…」 and
     * 「要是升级了，答：…」.
     */
    static boolean isAnnotation(String line) {
        return line.startsWith(NOTE_PREFIX) || Dialogue.isAnswerLine(line);
    }

    /** The how-to lines that are steps (numbered). */
    static List<String> howToSteps(String detail) {
        List<String> out = new ArrayList<>();
        for (String line : howToLines(detail)) {
            if (!isAnnotation(line)) {
                out.add(line);
            }
        }
        return out;
    }

    /** The how-to lines shown under the steps ({@link #isAnnotation}), in their order. */
    static List<String> howToNotes(String detail) {
        List<String> out = new ArrayList<>();
        for (String line : howToLines(detail)) {
            if (isAnnotation(line)) {
                out.add(line);
            }
        }
        return out;
    }

    /** "怎么做 · 3 步" — the label of the how-to section; only real steps are counted. */
    static String howToLabel(String detail) {
        int n = howToSteps(detail).size();
        return n > 1 ? "怎么做 · " + n + " 步" : "怎么做";
    }

    /**
     * The how-to as a section: a header and the numbered lines. With {@code collapsible} the header
     * is a ≥ 48 dp toggle ("怎么做 · 3 步 ▾") that shows or hides the lines, starting {@code expanded}.
     */
    static View howToSection(Context c, String detail, boolean collapsible, boolean expanded) {
        LinearLayout box = Ui.column(c);
        View lines = howTo(c, detail, 14);
        String label = howToLabel(detail);
        if (!collapsible) {
            box.addView(Ui.sectionHeader(c, label, null));
            LinearLayout.LayoutParams lp = Ui.matchWrap();
            lp.topMargin = Ui.dp(c, 6);
            box.addView(lines, lp);
            return box;
        }
        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        head.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint)));
        TextView title = Ui.mixed(c, label, 15, Ui.col(c, R.color.p3r_cyan_300), true);
        head.addView(title, Ui.weight(1));
        TextView arrow = Ui.text(c, expanded ? "收起" : "展开", 13, Ui.col(c, R.color.p3r_cyan_300), false);
        head.addView(arrow, Ui.wrap());
        head.setClickable(true);
        head.setFocusable(true);
        box.addView(head, Ui.matchWrap());
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(c, 2);
        lp.bottomMargin = Ui.dp(c, 4);
        box.addView(lines, lp);
        lines.setVisibility(expanded ? View.VISIBLE : View.GONE);
        head.setContentDescription(label + (expanded ? "，已展开" : "，已收起"));
        head.setOnClickListener(v -> {
            boolean open = lines.getVisibility() != View.VISIBLE;
            lines.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.setText(open ? "收起" : "展开");
            head.setContentDescription(label + (open ? "，已展开" : "，已收起"));
        });
        return box;
    }

    /**
     * Numbered how-to ("怎么做"); a single step is shown as a plain paragraph. 「参考回答：…」 and
     * 「注意：…」 lines follow the steps as marked notes, not numbered.
     */
    static View howTo(Context c, String detail, float sp) {
        List<String> lines = howToSteps(detail);
        LinearLayout box = Ui.column(c);
        if (lines.size() == 1) {
            TextView text = Ui.mixed(c, lines.get(0), sp, Ui.col(c, R.color.p3r_ice_100), false);
            text.setLineSpacing(0f, 1.25f);
            box.addView(text);
        } else {
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
        }
        for (String note : howToNotes(detail)) {
            box.addView(annotation(c, note, sp - 1), noteParams(c, box.getChildCount() == 0 ? 0 : 10));
        }
        return box;
    }

    /** One note under the steps: a thin bar (gold for 注意, cyan for 参考回答) and the line. */
    private static View annotation(Context c, String line, float sp) {
        LinearLayout row = Ui.row(c);
        int accent = Ui.col(c, line.startsWith(NOTE_PREFIX) ? R.color.ui_gold : R.color.p3r_cyan_300);
        View bar = new View(c);
        bar.setBackgroundColor(accent);
        row.addView(bar, new LinearLayout.LayoutParams(Ui.dp(c, 3), LinearLayout.LayoutParams.MATCH_PARENT));
        TextView text = Ui.mixed(c, line, sp, Ui.col(c, R.color.p3r_ice_100), false);
        text.setLineSpacing(0f, 1.2f);
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 8);
        row.addView(text, tp);
        row.setContentDescription(line);
        return row;
    }

    private static LinearLayout.LayoutParams noteParams(Context c, int topDp) {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(c, topDp);
        return lp;
    }
}
