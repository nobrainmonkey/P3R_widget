package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Dialogue hints for social steps. Text comes only from the rule pack's verbatim CN guide
 * fragments ({@code links[].dialogue}); otherwise the neutral fallback is shown. Foreign option
 * ordinals are never displayed (docs/DESIGN_v3.md §10.7).
 *
 * <p>The guide fragments mix three kinds of text: answers to pick (sometimes several in a row,
 * separated by "/", sometimes behind a label such as "对话选择："), "任意" for choices that do not
 * matter, and notes about routes and consequences. Only the answers themselves are shown, in
 * order, after "参考回答："; labels, route warnings, option ordinals ("第2项") and "任意" are left
 * out.
 */
final class Dialogue {
    static final String FALLBACK = "选能让对方高兴的回答（♪ 越多越好）";
    static final String PREFIX = "参考回答：";
    /** Every choice of this rank-up is free. */
    static final String ANY = PREFIX + "怎么回答都可以";

    /** Route / consequence notes ("…会进入恋爱线", "关系会破裂", "选1进入恋人线，选2做朋友"). */
    private static final Pattern WARNING = Pattern.compile("会进入|不会进入|破裂|做朋友|进入恋|选\\s*[0-9０-９]|选第");
    /** Option ordinals that may not match the in-game order ("第1项", "除第1项外任选"). */
    private static final Pattern ORDINAL = Pattern.compile("第\\s*[0-9０-９一二三四五六七八九十]+\\s*(项|个)");
    /** Notes in brackets about earlier options ("（前面选择第一个选项才有这个对话）"). */
    private static final Pattern OPTION_NOTE = Pattern.compile("（[^（）]*(选项|选择|前面)[^（）]*）");
    /** A leading label such as "对话选择：", "前3次对话选择：", "接着选择：". */
    private static final Pattern LABEL = Pattern.compile("^[^：:/（]{0,10}(选择|对话)[^：:/（]{0,4}[：:]\\s*");

    private Dialogue() {}

    /** "参考回答：A / B" for a social step, the fallback for other social steps, null otherwise. */
    static String line(Rules rules, Plan.Step step) {
        if (step == null || !"social".equals(step.kind) || !StepKind.of(step).social()) {
            return null;
        }
        Rules.Link link = rules == null || step.entity == null ? null : rules.link(step.entity);
        if (link != null && step.predicted != null) {
            int target = step.predicted.rankUp ? step.predicted.rankTo : step.predicted.rankFrom;
            String line = line(link.dialogue.get(target));
            if (line != null) {
                return line;
            }
        }
        return FALLBACK;
    }

    /**
     * The "参考回答：…" line for one rank's guide fragments, {@link #ANY} when every choice is free,
     * or null when nothing usable remains (the caller shows {@link #FALLBACK}).
     */
    static String line(List<String> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return null;
        }
        List<String> answers = new ArrayList<>();
        boolean dropped = false;
        for (String fragment : fragments) {
            if (fragment == null) {
                continue;
            }
            String entry = LABEL.matcher(fragment.trim()).replaceFirst("");
            if (WARNING.matcher(entry).find()) {
                dropped = true;
                continue;
            }
            entry = OPTION_NOTE.matcher(entry).replaceAll("");
            for (String part : entry.split("[/／]")) {
                String answer = part.trim();
                if (answer.isEmpty() || "任意".equals(answer)) {
                    continue;
                }
                if (ORDINAL.matcher(answer).find()) {
                    dropped = true;
                    continue;
                }
                answers.add(answer);
            }
        }
        if (answers.isEmpty()) {
            return dropped ? null : ANY;
        }
        return PREFIX + String.join(" / ", answers);
    }
}
