package app.p3r.dayplanner.v3.ui;

import java.util.List;

import app.p3r.dayplanner.v3.engine.DialogueHint;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Dialogue hints for social steps, as the app writes them: the engine's {@link DialogueHint} (one
 * structured source for the app, the 「怎么做」 text and the widget) in the app's words,
 * 「回答：A / B」 with the short note only ({@link #compact}). Text comes only from the rule pack's
 * verbatim CN guide fragments ({@code links[].dialogue}); otherwise the neutral fallback is shown.
 * Foreign option ordinals are never displayed (docs/DESIGN_v3.md §10.7).
 *
 * <p>Which scene: a rank-up shows the scene it ranks up into; a meeting during a story hold, or one
 * that certainly will not rank up, shows nothing; any other meeting shows the next scene's answers
 * as 「要是升级了，答：…」 ({@link DialogueHint#forMeeting}).
 *
 * <p>Romance: the plan never asks for a romance route. For a link that can be romanced and whose
 * route ({@link Profile#route}) is not 恋人 (the default counts as 朋友), answers the guide marks as
 * romance answers ("…，恋人线", an answer quoted in a "…会进入恋爱线" note) are hidden and the line
 * ends with 「别选表白」 (or the answers the guide names). Where the guide has no such marking, the
 * answers of RANK {@value #ROMANCE_FROM_RANK} and up are replaced by the generic hint
 * {@link #FRIEND_FALLBACK}, since the romance choice comes in those scenes.
 */
public final class Dialogue {
    static final String FALLBACK = DialogueHint.FALLBACK;
    static final String PREFIX = DialogueHint.PREFIX;
    /** Every choice of this rank-up is free. */
    static final String ANY = DialogueHint.ANY;
    /** Added to the line of a romanceable link on the 朋友 route. */
    static final String FRIEND_CAUTION = DialogueHint.FRIEND_CAUTION;
    /** Generic hint for the romance scenes of a link on the 朋友 route. */
    static final String FRIEND_FALLBACK = DialogueHint.FRIEND_FALLBACK;
    /** From this RANK on, unmarked answers of a romanceable link may lead into the romance route. */
    static final int ROMANCE_FROM_RANK = DialogueHint.ROMANCE_FROM_RANK;

    private Dialogue() {}

    /** {@link #line(Rules, Profile, Plan.Step)} with the default profile (朋友 route everywhere). */
    public static String line(Rules rules, Plan.Step step) {
        return line(rules, null, step);
    }

    /**
     * "回答：A / B" for a rank-up, "要是升级了，答：A / B" for a meeting that may rank up after
     * all, the fallback for other meetings the guide has nothing on; null for a meeting with no scene
     * to prepare for (a hold, no rank-up for certain) and for every other step. The route of
     * {@code profile} (null = default) decides whether romance answers are shown.
     */
    public static String line(Rules rules, Profile profile, Plan.Step step) {
        if (step == null || !"social".equals(step.kind) || !StepKind.of(step).social()) {
            return null;
        }
        Rules.Link link = rules == null || step.entity == null ? null : rules.link(step.entity);
        if (link == null) {
            // No guide to look in: the generic hint, unless the meeting certainly has no scene.
            boolean noScene = step.predicted != null && !step.predicted.rankUp && step.predicted.certain;
            return noScene ? null : FALLBACK;
        }
        DialogueHint.Answers answers = DialogueHint.forMeeting(rules, profile, step);
        return answers == DialogueHint.Answers.NONE ? null : compact(answers);
    }

    /**
     * The answers as the hero and the day sheet show them: the guide's choices and, after 「；」, only
     * a short note: for a reviewed scene the answers to leave out and the pack's {@code shortNoteZh}
     * (as on the widget), else 「别选表白」 on the 朋友 route. The guide's long note stays in 提示.
     */
    static String compact(DialogueHint.Answers a) {
        if (a == null || a.isEmpty()) {
            return FALLBACK;
        }
        String prefix = a.conditional ? DialogueHint.CONDITIONAL_PREFIX : PREFIX;
        String note = a.reviewed ? reviewedNote(a) : null;
        String body = !a.choices.isEmpty() ? String.join(" / ", a.choices) : a.any ? DialogueHint.ANY_ZH : "";
        if (!body.isEmpty()) {
            if (note == null && a.friendCaution) {
                note = DialogueHint.FRIEND_AVOID;
            }
            return prefix + body + (note == null ? "" : "；" + note);
        }
        if (note == null) {
            return a.friendCaution ? FRIEND_FALLBACK : FALLBACK;
        }
        // A scene without choices (「观看剧情即可」); the next scene's stays marked, so it is drawn muted.
        return a.conditional ? prefix + note : note;
    }

    /**
     * A reviewed scene's short note: {@link DialogueHint#widgetNote}, but never the long guide note
     * where the pack has no short one (then only the answers to leave out).
     */
    private static String reviewedNote(DialogueHint.Answers a) {
        if (a.shortNoteZh != null) {
            return DialogueHint.widgetNote(a);
        }
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (String answer : a.avoid) {
            String shown = DialogueHint.withoutCounts(answer);
            if (!shown.isEmpty()) {
                names.add(shown);
            }
        }
        return names.isEmpty() ? null : "别选「" + String.join("」「", names) + "」";
    }

    /** What the app shows for the generic friend-route hint: the one thing worth knowing. */
    static final String FRIEND_SHORT = "只想做朋友？别选表白的回答";

    /**
     * {@code line} as the app shows it: real answers as they are, nothing for the generic
     * 「选能让对方高兴的回答」 (every player knows that), and the friend-route hint as
     * {@link #FRIEND_SHORT}. The widget keeps its own wording ({@link DialogueHint}).
     */
    static String shown(String line) {
        if (line == null || FALLBACK.equals(line)) {
            return null;
        }
        if (FRIEND_FALLBACK.equals(line)) {
            return FRIEND_SHORT;
        }
        return line;
    }

    /** The answers of a meeting that may not rank up after all: 「要是升级了，答：…」 (drawn muted). */
    static boolean conditional(String line) {
        return line != null && line.startsWith(DialogueHint.CONDITIONAL_PREFIX);
    }

    /** A how-to line that is the guide's answers: 「回答：…」 or 「要是升级了，答：…」. */
    static boolean isAnswerLine(String line) {
        return line.startsWith(PREFIX) || line.startsWith(DialogueHint.CONDITIONAL_PREFIX);
    }

    /** True when the player chose the 恋人 route for {@code link} (the default is 朋友). */
    static boolean romanceRoute(Profile profile, int link) {
        return DialogueHint.romanceRoute(profile, link);
    }

    /** {@link DialogueHint#friendLine}: the line of a romanceable link on the 朋友 route. */
    static String friendLine(List<String> fragments, int targetRank) {
        return DialogueHint.friendLine(fragments, targetRank);
    }

    /**
     * The "回答：…" line for one rank's guide fragments, {@link #ANY} when every choice is free,
     * or null when nothing usable remains (the caller shows {@link #FALLBACK}).
     */
    static String line(List<String> fragments) {
        return DialogueHint.line(fragments);
    }
}
