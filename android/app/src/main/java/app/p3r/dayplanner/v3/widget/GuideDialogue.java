package app.p3r.dayplanner.v3.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * The 「参考回答：A / B」 line of a meeting, for the plan widget. A read-only copy of the app's
 * {@code ui.Dialogue} (package-private there): same source (the rule pack's verbatim CN guide
 * fragments, {@code links[].dialogue} keyed by target rank), same filtering, same wording. The
 * difference: when the guide has nothing usable this returns null instead of the generic fallback,
 * because the card only shows the line when there is real guide text. Keep the two in step;
 * {@code GuideDialogueTest} compares them on the whole rule pack.
 */
final class GuideDialogue {
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

    private GuideDialogue() {}

    /**
     * "参考回答：A / B" for a meeting whose scene has guide answers, {@link #ANY} when every choice is
     * free, null for anything else (no guide text, not a meeting, no rule pack).
     */
    static String line(Rules rules, Plan.Step step) {
        if (step == null || !"social".equals(step.kind)) return null;
        String action = step.action == null ? "" : step.action;
        if (action.startsWith("note:") || action.startsWith("episode:")) return null;
        Rules.Link link = rules == null || step.entity == null ? null : rules.link(step.entity);
        if (link == null || step.predicted == null || link.dialogue == null) return null;
        int target = step.predicted.rankUp ? step.predicted.rankTo : step.predicted.rankFrom;
        return line(link.dialogue.get(target));
    }

    /** The line for one rank's guide fragments; null when nothing usable remains. */
    static String line(List<String> fragments) {
        if (fragments == null || fragments.isEmpty()) return null;
        List<String> answers = new ArrayList<>();
        boolean dropped = false;
        for (String fragment : fragments) {
            if (fragment == null) continue;
            String entry = LABEL.matcher(fragment.trim()).replaceFirst("");
            if (WARNING.matcher(entry).find()) {
                dropped = true;
                continue;
            }
            entry = OPTION_NOTE.matcher(entry).replaceAll("");
            for (String part : entry.split("[/／]")) {
                String answer = part.trim();
                if (answer.isEmpty() || "任意".equals(answer)) continue;
                if (ORDINAL.matcher(answer).find()) {
                    dropped = true;
                    continue;
                }
                answers.add(answer);
            }
        }
        if (answers.isEmpty()) return dropped ? null : ANY;
        return PREFIX + String.join(" / ", answers);
    }
}
