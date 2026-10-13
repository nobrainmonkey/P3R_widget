package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialogue hint of a meeting, for its 「怎么做」 ({@link Labels}): the same text as the app's
 * 「参考回答」 line (ui.Dialogue, which can delegate here), computed from the rule pack's verbatim CN
 * guide fragments ({@code links[].dialogue}) for the scene of the meeting. Foreign option ordinals
 * are never shown (docs/DESIGN_v3.md §10.7).
 *
 * <p>The guide fragments mix answers to pick (sometimes several in a row, separated by "/",
 * sometimes behind a label such as "对话选择："), "任意" for choices that do not matter, and notes
 * about routes and consequences. Only the answers are shown, in order, after "参考回答："; labels,
 * route warnings, option ordinals ("第2项") and "任意" are left out.
 *
 * <p>Romance: the plan never asks for a romance route. For a romanceable link whose route is not
 * 恋人 (the default counts as 朋友), answers the guide marks as romance answers are hidden and the
 * line ends with {@link #FRIEND_CAUTION}; where the guide has no such marking, the scenes of RANK
 * {@value #ROMANCE_FROM_RANK} and up get {@link #FRIEND_FALLBACK}.
 */
public final class DialogueHint {
    /** Shown when the guide has nothing usable for the scene. */
    public static final String FALLBACK = "选能让对方高兴的回答（♪ 越多越好）";
    public static final String PREFIX = "参考回答：";
    /** Every choice of this scene is free. */
    public static final String ANY = PREFIX + "怎么回答都可以";
    /** Added to the line of a romanceable link on the 朋友 route. */
    public static final String FRIEND_CAUTION = "只想做朋友的话，别选表白的回答";
    /** Generic hint for the romance scenes of a link on the 朋友 route. */
    public static final String FRIEND_FALLBACK = FALLBACK + "；" + FRIEND_CAUTION;
    /** From this RANK on, unmarked answers of a romanceable link may lead into the romance route. */
    public static final int ROMANCE_FROM_RANK = 8;

    /** Route / consequence notes ("…会进入恋爱线", "关系会破裂", "选1进入恋人线，选2做朋友"). */
    private static final Pattern WARNING = Pattern.compile("会进入|不会进入|破裂|做朋友|进入恋|选\\s*[0-9０-９]|选第");
    /** Marks a romance answer or a romance route note in the guide. */
    private static final Pattern ROMANCE = Pattern.compile("恋人线|恋爱线|恋人路线|恋爱路线");
    /** An answer quoted inside a note ("第二次选择“产生恋爱感情了（1）”会进入恋爱线"). */
    private static final Pattern QUOTED = Pattern.compile("[“「\"]([^”」\"]+)[”」\"]");
    /** Option ordinals that may not match the in-game order ("第1项", "除第1项外任选"). */
    private static final Pattern ORDINAL = Pattern.compile("第\\s*[0-9０-９一二三四五六七八九十]+\\s*(项|个)");
    /** Notes in brackets about earlier options ("（前面选择第一个选项才有这个对话）"). */
    private static final Pattern OPTION_NOTE = Pattern.compile("（[^（）]*(选项|选择|前面)[^（）]*）");
    /** A leading label such as "对话选择：", "前3次对话选择：", "接着选择：". */
    private static final Pattern LABEL = Pattern.compile("^[^：:/（]{0,10}(选择|对话)[^：:/（]{0,4}[：:]\\s*");

    private DialogueHint() {}

    /**
     * The hint for a meeting with {@code link} whose scene is {@code sceneRank} (the RANK it ranks up
     * to, or its current RANK when it does not): "参考回答：A / B", {@link #ANY}, a 朋友-route line,
     * or {@link #FALLBACK}. The route of {@code profile} (null = default) decides whether romance
     * answers are shown.
     */
    public static String line(Rules rules, Profile profile, int link, int sceneRank) {
        if (rules == null || link < 0 || link >= rules.links.length) return FALLBACK;
        Rules.Link l = rules.links[link];
        List<String> fragments = l.dialogue.get(sceneRank);
        if (l.romanceable && !romanceRoute(profile, link)) return friendLine(fragments, sceneRank);
        String line = line(fragments);
        return line != null ? line : FALLBACK;
    }

    /** True when the player chose the 恋人 route for {@code link} (the default is 朋友). */
    public static boolean romanceRoute(Profile profile, int link) {
        return profile != null && link >= 0 && link < profile.route.length && profile.route[link] == Profile.Route.ROMANCE;
    }

    /**
     * The line for a romanceable link on the 朋友 route: the guide's answers without the ones it
     * marks as romance answers, then {@link #FRIEND_CAUTION}; {@link #FRIEND_FALLBACK} when nothing
     * is left or when a RANK ≥ {@value #ROMANCE_FROM_RANK} scene has no marking; unmarked lower
     * ranks read as usual.
     */
    static String friendLine(List<String> fragments, int sceneRank) {
        boolean marked = false;
        Set<String> romanceAnswers = new HashSet<>();
        List<String> kept = new ArrayList<>();
        if (fragments != null) {
            for (String fragment : fragments) {
                if (fragment == null) continue;
                if (!ROMANCE.matcher(fragment).find()) {
                    kept.add(fragment);
                    continue;
                }
                marked = true;
                Matcher quoted = QUOTED.matcher(fragment);
                while (quoted.find()) romanceAnswers.add(quoted.group(1).trim());
                String entry = LABEL.matcher(fragment.trim()).replaceFirst("");
                if (WARNING.matcher(entry).find()) continue;
                List<String> parts = new ArrayList<>();
                for (String part : entry.split("[/／]")) {
                    if (!ROMANCE.matcher(part).find()) parts.add(part);
                }
                if (!parts.isEmpty()) kept.add(String.join("/", parts));
            }
        }
        if (!marked) {
            if (sceneRank >= ROMANCE_FROM_RANK) return FRIEND_FALLBACK;
            String line = line(fragments);
            return line == null ? FALLBACK : line;
        }
        boolean[] dropped = {false};
        List<String> answers = answers(kept, dropped);
        answers.removeIf(romanceAnswers::contains);
        if (answers.isEmpty()) return FRIEND_FALLBACK;
        return PREFIX + String.join(" / ", answers) + "；" + FRIEND_CAUTION;
    }

    /**
     * The "参考回答：…" line for one scene's guide fragments, {@link #ANY} when every choice is free,
     * or null when nothing usable remains (the caller shows {@link #FALLBACK}).
     */
    static String line(List<String> fragments) {
        if (fragments == null || fragments.isEmpty()) return null;
        boolean[] dropped = {false};
        List<String> answers = answers(fragments, dropped);
        if (answers.isEmpty()) return dropped[0] ? null : ANY;
        return PREFIX + String.join(" / ", answers);
    }

    /** The answers to pick, in order; {@code dropped[0]} is set when a note or ordinal was left out. */
    private static List<String> answers(List<String> fragments, boolean[] dropped) {
        List<String> answers = new ArrayList<>();
        for (String fragment : fragments) {
            if (fragment == null) continue;
            String entry = LABEL.matcher(fragment.trim()).replaceFirst("");
            if (WARNING.matcher(entry).find()) {
                dropped[0] = true;
                continue;
            }
            entry = OPTION_NOTE.matcher(entry).replaceAll("");
            for (String part : entry.split("[/／]")) {
                String answer = part.trim();
                if (answer.isEmpty() || "任意".equals(answer)) continue;
                if (ORDINAL.matcher(answer).find()) {
                    dropped[0] = true;
                    continue;
                }
                answers.add(answer);
            }
        }
        return answers;
    }
}
