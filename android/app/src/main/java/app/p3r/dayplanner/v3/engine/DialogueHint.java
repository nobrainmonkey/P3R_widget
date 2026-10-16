package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialogue choices of a meeting or an unlock step as one structured {@link Answers}, and the two
 * ways to write them (docs/GLOSSARY_zh.md, docs/DESIGN_v3.md §18): the app's 「参考回答：A / B」
 * ({@link #appLine}; also the line in a meeting's 「怎么做」, see {@link Labels}) and the widget's
 * 「答：A / B」 ({@link #widgetLine}). A meeting's answers come from the rule pack's verbatim CN
 * guide fragments ({@code links[].dialogue}); an unlock step's from the 「…」 its how-to says to
 * pick. Foreign option ordinals are never shown (docs/DESIGN_v3.md §10.7).
 *
 * <p>Scene ({@link #forMeeting}): a rank-up shows the scene it ranks up into; a meeting that cannot
 * rank up (a story hold) or certainly will not shows nothing; one that may rank up after all shows
 * the next scene's answers, {@link Answers#conditional} (「要是升级了，答：…」). Up to 3.0.2 a meeting
 * without a rank-up showed the scene of its current RANK, one already played.
 *
 * <p>The guide fragments mix answers to pick (sometimes several in a row, separated by "/",
 * sometimes behind a label such as "对话选择："), "任意" for choices that do not matter, and notes
 * about routes and consequences. Only the answers are kept, in order; labels, route warnings,
 * option ordinals ("第2项") and "任意" are left out.
 *
 * <p>Romance: the plan never asks for a romance route. For a romanceable link whose route is not
 * 恋人 (the default counts as 朋友), answers the guide marks as romance answers are left out (and
 * named in {@link Answers#avoid}) and the line ends with {@link #FRIEND_CAUTION}; where the guide
 * has no such marking, the scenes of RANK {@value #ROMANCE_FROM_RANK} and up get
 * {@link #FRIEND_FALLBACK}.
 */
public final class DialogueHint {
    /** Shown when the guide has nothing usable for the scene. */
    public static final String FALLBACK = "选能让对方高兴的回答（♪ 越多越好）";
    public static final String PREFIX = "参考回答：";
    /** Every choice of the scene is free (after a prefix). */
    public static final String ANY_ZH = "怎么回答都可以";
    public static final String ANY = PREFIX + ANY_ZH;
    /** Added to the line of a romanceable link on the 朋友 route. */
    public static final String FRIEND_CAUTION = "只想做朋友的话，别选表白的回答";
    /** Generic hint for the romance scenes of a link on the 朋友 route. */
    public static final String FRIEND_FALLBACK = FALLBACK + "；" + FRIEND_CAUTION;
    /** From this RANK on, unmarked answers of a romanceable link may lead into the romance route. */
    public static final int ROMANCE_FROM_RANK = 8;
    /** The widget's prefix: the glossary never puts 「参考回答：」 on the widget. */
    public static final String WIDGET_PREFIX = "答：";
    /** Prefix of the answers of a meeting that may rank up after all (app and widget). */
    public static final String CONDITIONAL_PREFIX = "要是升级了，答：";
    /** {@link #CONDITIONAL_PREFIX} on a widget too narrow for it. */
    public static final String CONDITIONAL_SHORT_PREFIX = "升级时答：";
    /** A side step's pick on the widget: 「顺便选：有话想和你说」. */
    public static final String SIDE_PREFIX = "顺便选：";
    /** Widget: the end of a 朋友-route line with answers (「答：A / B · 别选表白」). */
    public static final String FRIEND_AVOID = "别选表白";
    /** Widget: a 朋友-route line without answers. */
    public static final String FRIEND_AVOID_ALONE = "别选表白的回答";
    /** Assumption id of a meeting during a hold (engine Kernel.ASSUMPTION_IDS): no scene to prepare for. */
    static final String HOLD_ASSUMPTION = "rank_hold";

    /** Route / consequence notes ("…会进入恋爱线", "关系会破裂", "选1进入恋人线，选2做朋友"). */
    private static final Pattern WARNING = Pattern.compile("会进入|不会进入|破裂|做朋友|进入恋|选\\s*[0-9０-９]|选第");
    /** Marks a romance answer or a romance route note in the guide. */
    private static final Pattern ROMANCE = Pattern.compile("恋人线|恋爱线|恋人路线|恋爱路线");
    /** The route note on a romance answer ("我喜欢风花（1），恋人线", "是恋情（1，恋人线）"). */
    private static final Pattern ROUTE_NOTE = Pattern.compile("\\s*[，,、]?\\s*(恋人线|恋爱线|恋人路线|恋爱路线)|（）");
    /** An answer quoted inside a note ("第二次选择“产生恋爱感情了（1）”会进入恋爱线"). */
    private static final Pattern QUOTED = Pattern.compile("[“「\"]([^”」\"]+)[”」\"]");
    /** Option ordinals that may not match the in-game order ("第1项", "除第1项外任选"). */
    private static final Pattern ORDINAL = Pattern.compile("第\\s*[0-9０-９一二三四五六七八九十]+\\s*(项|个)");
    /** Notes in brackets about earlier options ("（前面选择第一个选项才有这个对话）"). */
    private static final Pattern OPTION_NOTE = Pattern.compile("（[^（）]*(选项|选择|前面)[^（）]*）");
    /** A leading label such as "对话选择：", "前3次对话选择：", "接着选择：". */
    private static final Pattern LABEL = Pattern.compile("^[^：:/（]{0,10}(选择|对话)[^：:/（]{0,4}[：:]\\s*");
    /**
     * The guide's count after an answer, 「（1）」: its meaning (option position or ♪) is unverified,
     * so the widget leaves it out (spec 3.1.0 decision O3); the app keeps it.
     */
    private static final Pattern COUNT = Pattern.compile("\\s*[（(][0-9０-９]+[）)]");
    /** The picks an unlock how-to names: 「选「有话想和你说」」, 「依次回答「费洛蒙咖啡」「红色」「叶隐盖饭」」. */
    private static final Pattern PICKS = Pattern.compile("(?:依次)?(?:选择?|回答)((?:「[^「」]+」[、，和或]?)+)");
    private static final Pattern PICK = Pattern.compile("「([^「」]+)」");

    private DialogueHint() {}

    /** The choices of one meeting scene or unlock step. */
    public static final class Answers {
        /** No scene to prepare for: a meeting that cannot or certainly will not rank up. */
        public static final Answers NONE = new Answers(Collections.emptyList(), false, Collections.emptyList(),
                false, false, -1);

        /** The answers to pick, in order, as the guide writes them (its 「（1）」 counts included). */
        public final List<String> choices;
        /** Every choice of the scene is free. */
        public final boolean any;
        /** Romance answers the guide marks, to leave out on the 朋友 route (as the guide writes them). */
        public final List<String> avoid;
        /** A romanceable link on the 朋友 route: say not to pick the confession. */
        public final boolean friendCaution;
        /** The meeting may not rank up: these are the next scene's answers (「要是升级了，答：…」). */
        public final boolean conditional;
        /** RANK of the meeting scene these answers are for; 0 for an unlock step, -1 for {@link #NONE}. */
        public final int scene;
        /** Reviewed guidance is authoritative; absent route variants never reparse the raw fragments. */
        public final boolean reviewed;
        /** Explicit compact wording; null means the widget uses choices without the guide's counts. */
        public final List<String> shortChoices;
        /** Full and compact route/condition notes, separate from the answer strings. */
        public final String noteZh;
        public final String shortNoteZh;
        /** Complete ordered, route-appropriate question sequence for the app's details. */
        public final List<String> sequence;

        Answers(List<String> choices, boolean any, List<String> avoid, boolean friendCaution, boolean conditional,
                int scene) {
            this(choices, any, avoid, friendCaution, conditional, scene, false, null, null, null,
                    Collections.emptyList());
        }

        Answers(List<String> choices, boolean any, List<String> avoid, boolean friendCaution, boolean conditional,
                int scene, boolean reviewed, List<String> shortChoices, String noteZh, String shortNoteZh,
                List<String> sequence) {
            this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
            this.any = any;
            this.avoid = Collections.unmodifiableList(new ArrayList<>(avoid));
            this.friendCaution = friendCaution;
            this.conditional = conditional;
            this.scene = scene;
            this.reviewed = reviewed;
            this.shortChoices = shortChoices == null ? null : Collections.unmodifiableList(new ArrayList<>(shortChoices));
            this.noteZh = noteZh;
            this.shortNoteZh = shortNoteZh;
            this.sequence = Collections.unmodifiableList(new ArrayList<>(sequence));
        }

        /** Nothing to say: no answers, no free choice and no caution (the app shows {@link #FALLBACK}). */
        public boolean isEmpty() {
            return choices.isEmpty() && !any && !friendCaution && avoid.isEmpty()
                    && (noteZh == null || noteZh.isEmpty());
        }
    }

    // ------------------------------------------------------------------ sources

    /**
     * The answers of a meeting with {@code link} on {@code day} predicted as {@code pred}: the scene it
     * ranks up into; {@link Answers#NONE} during a story hold of the rank-up ({@link Rules.Link#holds}
     * for RANK {@code rankFrom + 1} on {@code day}), when it certainly does not rank up, and without
     * rules, link or prediction; otherwise the next scene's, {@link Answers#conditional}. The route
     * of {@code profile} (null = default) decides whether romance answers are shown.
     */
    public static Answers forMeeting(Rules r, Profile p, int link, Plan.Predicted pred, int day) {
        if (r == null || r.links == null || link < 0 || link >= r.links.length || pred == null) return Answers.NONE;
        if (pred.rankUp) return forScene(r, p, link, pred.rankTo, false);
        int into = pred.rankFrom + 1;
        if (pred.certain || into < 2 || into > 10 || held(r.links[link], into, day)) return Answers.NONE;
        return forScene(r, p, link, into, true);
    }

    /**
     * {@link #forMeeting(Rules, Profile, int, Plan.Predicted, int)} for a plan step: a meeting
     * ({@code social}) of a link the rules know, else {@link Answers#NONE}; also none when the plan
     * counts on a hold the player reported (the {@code rank_hold} assumption).
     */
    public static Answers forMeeting(Rules r, Profile p, Plan.Step step) {
        if (r == null || step == null || !"social".equals(step.kind) || step.entity == null) return Answers.NONE;
        Rules.Link link = r.link(step.entity);
        if (link == null) return Answers.NONE;
        Plan.Predicted pred = step.predicted;
        boolean held = step.assumptions != null && step.assumptions.contains(HOLD_ASSUMPTION);
        if (pred != null && !pred.rankUp && held) return Answers.NONE;
        return forMeeting(r, p, link.idx, pred, Rules.dayOf(step.slot));
    }

    /**
     * The answers of the scene of RANK {@code scene} of {@code link} ({@link Answers#conditional} as
     * given), as the route of {@code p} shows them.
     */
    static Answers forScene(Rules r, Profile p, int link, int scene, boolean conditional) {
        Rules.Link l = r.links[link];
        Rules.DialogueHintEntry hint = l.dialogueHints.get(scene);
        if (hint != null) {
            boolean romance = romanceRoute(p, link);
            Rules.DialogueVariant variant = romance ? hint.romance : hint.friend;
            if (variant == null) variant = hint.common;
            if (variant != null) {
                return new Answers(variant.choices, variant.any, variant.avoid, false, conditional, scene,
                        true, variant.shortChoices, variant.noteZh, variant.shortNoteZh, variant.sequence);
            }
            // The scene is known, but this route has not been reviewed. Raw guide arrays may be a
            // different route: retain a neutral caution instead of exposing its confession answers.
            return new Answers(Collections.emptyList(), false, Collections.emptyList(),
                    l.romanceable && !romance, conditional, scene, true, null, null, null,
                    Collections.emptyList());
        }
        List<String> fragments = l.dialogue == null ? null : l.dialogue.get(scene);
        if (l.romanceable && !romanceRoute(p, link)) return friendAnswers(fragments, scene, conditional);
        return answers(fragments, scene, conditional);
    }

    /**
     * The picks an unlock step's how-to names after 选 / 回答 / 依次选 / 依次回答, in order
     * (月亮: 「费洛蒙咖啡」「红色」「叶隐盖饭」); {@link Answers#NONE} when it names none.
     */
    public static Answers forUnlockStep(Rules.Step s) {
        if (s == null || s.howZh == null) return Answers.NONE;
        List<String> picks = new ArrayList<>();
        Matcher m = PICKS.matcher(s.howZh);
        while (m.find()) {
            Matcher pick = PICK.matcher(m.group(1));
            while (pick.find()) {
                String answer = pick.group(1).trim();
                if (!answer.isEmpty()) picks.add(answer);
            }
        }
        if (picks.isEmpty()) return Answers.NONE;
        return new Answers(picks, false, Collections.emptyList(), false, false, 0);
    }

    /** True when the player chose the 恋人 route for {@code link} (the default is 朋友). */
    public static boolean romanceRoute(Profile profile, int link) {
        return profile != null && link >= 0 && link < profile.route.length && profile.route[link] == Profile.Route.ROMANCE;
    }

    /** Whether a rule hold blocks the rank-up into {@code into} on {@code day} (as the kernel reads it). */
    static boolean held(Rules.Link link, int into, int day) {
        if (link.holds == null) return false;
        for (Rules.Hold hold : link.holds) {
            if (hold != null && hold.rank == into && (hold.untilDay < 0 || day < hold.untilDay)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ app line

    /**
     * The app's line: 「参考回答：A / B」 (byte-identical to 3.0.2 for a rank-up), 「要是升级了，答：A / B」
     * for a meeting that may rank up, {@link #ANY}, a 朋友-route line (「…；只想做朋友的话，别选表白的回答」
     * or {@link #FRIEND_FALLBACK}), or {@link #FALLBACK} when there is nothing to say.
     */
    public static String appLine(Answers a) {
        if (a == null || a.isEmpty()) return FALLBACK;
        String prefix = a.conditional ? CONDITIONAL_PREFIX : PREFIX;
        if (a.reviewed) {
            String notes = reviewedNotes(a, false);
            String body = !a.choices.isEmpty() ? String.join(" / ", a.choices) : a.any ? ANY_ZH : "";
            if (!body.isEmpty()) return prefix + body + (notes == null ? "" : "；" + notes);
            if (notes != null) return prefix + notes;
            return a.friendCaution ? FRIEND_FALLBACK : FALLBACK;
        }
        if (!a.choices.isEmpty()) {
            return prefix + String.join(" / ", a.choices) + (a.friendCaution ? "；" + FRIEND_CAUTION : "");
        }
        if (a.friendCaution) return FRIEND_FALLBACK;
        return a.any ? prefix + ANY_ZH : FALLBACK;
    }

    /**
     * The 「参考回答：…」 line of one scene's guide fragments as written, {@link #ANY} when every
     * choice is free, or null when nothing usable remains (the caller shows {@link #FALLBACK}).
     */
    public static String line(List<String> fragments) {
        Answers a = answers(fragments, 0, false);
        return a.isEmpty() ? null : appLine(a);
    }

    /**
     * The line for a romanceable link on the 朋友 route: the guide's answers without the ones it
     * marks as romance answers, then {@link #FRIEND_CAUTION}; {@link #FRIEND_FALLBACK} when nothing
     * is left or when a RANK ≥ {@value #ROMANCE_FROM_RANK} scene has no marking; unmarked lower
     * ranks read as usual.
     */
    public static String friendLine(List<String> fragments, int sceneRank) {
        return appLine(friendAnswers(fragments, sceneRank, false));
    }

    // ------------------------------------------------------------------ widget line

    /**
     * The widget's line on one line, as a card wide enough shows it: 「答：A / B」, 「答：怎么回答都可以」,
     * 「答：A / B · 别选表白」, 「答：别选「我喜欢风花」」, 「答：别选表白的回答」, 「要是升级了，答：A / B」;
     * the guide's 「（1）」 counts left out. Null when there is nothing to say (no row, never the
     * generic {@link #FALLBACK}).
     */
    public static String widgetLine(Answers a) {
        if (a == null || a.isEmpty()) return null;
        String body = widgetBody(a);
        String note = widgetNote(a);
        StringBuilder sb = new StringBuilder(widgetLabel(a)).append(body);
        if (note != null) sb.append(body.isEmpty() ? "" : " · ").append(note);
        return sb.toString();
    }

    /** 「答：」, or 「要是升级了，答：」 for {@link Answers#conditional}. */
    public static String widgetLabel(Answers a) {
        return a != null && a.conditional ? CONDITIONAL_PREFIX : WIDGET_PREFIX;
    }

    /** The choices as the widget shows them: in order, their 「（1）」 counts left out. */
    public static List<String> widgetChoices(Answers a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (String choice : a.shortChoices == null ? a.choices : a.shortChoices) {
            String shown = withoutCounts(choice);
            if (!shown.isEmpty()) out.add(shown);
        }
        return out;
    }

    /** The choices joined with 「 / 」, or 「怎么回答都可以」, or empty. */
    static String widgetBody(Answers a) {
        List<String> choices = widgetChoices(a);
        if (!choices.isEmpty()) return String.join(" / ", choices);
        return a != null && a.any ? ANY_ZH : "";
    }

    /**
     * What follows the choices on the widget: 「别选「我喜欢风花」」 for romance answers the guide names,
     * else 「别选表白」 (after answers) or 「别选表白的回答」 (alone) on the 朋友 route; null otherwise.
     */
    public static String widgetNote(Answers a) {
        if (a == null) return null;
        if (a.reviewed) {
            String note = reviewedNotes(a, true);
            if (note != null) return note;
        }
        if (!a.avoid.isEmpty()) {
            Set<String> names = new LinkedHashSet<>();
            for (String answer : a.avoid) {
                String shown = withoutCounts(answer);
                if (!shown.isEmpty()) names.add(shown);
            }
            if (!names.isEmpty()) return "别选「" + String.join("」「", names) + "」";
        }
        if (!a.friendCaution) return null;
        return widgetChoices(a).isEmpty() ? FRIEND_AVOID_ALONE : FRIEND_AVOID;
    }

    /** Named prohibited answers and authored route/condition guidance, never inferred from wording. */
    private static String reviewedNotes(Answers a, boolean compact) {
        List<String> notes = new ArrayList<>();
        if (!a.avoid.isEmpty()) {
            Set<String> names = new LinkedHashSet<>();
            for (String answer : a.avoid) {
                String shown = withoutCounts(answer);
                if (!shown.isEmpty()) names.add(shown);
            }
            if (!names.isEmpty()) notes.add("别选「" + String.join("」「", names) + "」");
        }
        String authored = compact && a.shortNoteZh != null ? a.shortNoteZh : a.noteZh;
        if (authored != null && !authored.isEmpty() && !notes.contains(authored)) notes.add(authored);
        return notes.isEmpty() ? null : String.join("；", notes);
    }

    /** {@code text} without the guide's 「（1）」 counts. */
    public static String withoutCounts(String text) {
        return text == null ? "" : COUNT.matcher(text).replaceAll("").trim();
    }

    // ------------------------------------------------------------------ fragments

    /** One scene's answers as the guide writes them (romance answers included). */
    private static Answers answers(List<String> fragments, int scene, boolean conditional) {
        if (fragments == null || fragments.isEmpty()) return empty(scene, conditional);
        boolean[] dropped = {false};
        List<String> choices = choices(fragments, dropped);
        boolean any = choices.isEmpty() && !dropped[0];
        return new Answers(choices, any, Collections.emptyList(), false, conditional, scene);
    }

    /** One scene's answers for a romanceable link on the 朋友 route (see {@link #friendLine}). */
    private static Answers friendAnswers(List<String> fragments, int scene, boolean conditional) {
        boolean marked = false;
        Set<String> quoted = new HashSet<>();
        Set<String> avoid = new LinkedHashSet<>();
        List<String> kept = new ArrayList<>();
        if (fragments != null) {
            for (String fragment : fragments) {
                if (fragment == null) continue;
                if (!ROMANCE.matcher(fragment).find()) {
                    kept.add(fragment);
                    continue;
                }
                marked = true;
                Matcher q = QUOTED.matcher(fragment);
                while (q.find()) {
                    quoted.add(q.group(1).trim());
                    avoid.add(q.group(1).trim());
                }
                String entry = LABEL.matcher(fragment.trim()).replaceFirst("");
                if (WARNING.matcher(entry).find()) continue;
                List<String> parts = new ArrayList<>();
                for (String part : entry.split("[/／]")) {
                    if (!ROMANCE.matcher(part).find()) {
                        parts.add(part);
                        continue;
                    }
                    String answer = ROUTE_NOTE.matcher(part).replaceAll("").trim();
                    if (!answer.isEmpty()) avoid.add(answer);
                }
                if (!parts.isEmpty()) kept.add(String.join("/", parts));
            }
        }
        if (!marked) {
            if (scene >= ROMANCE_FROM_RANK) {
                return new Answers(Collections.emptyList(), false, Collections.emptyList(), true, conditional, scene);
            }
            return answers(fragments, scene, conditional);
        }
        boolean[] dropped = {false};
        List<String> choices = choices(kept, dropped);
        choices.removeIf(quoted::contains);
        return new Answers(choices, false, new ArrayList<>(avoid), true, conditional, scene);
    }

    private static Answers empty(int scene, boolean conditional) {
        return new Answers(Collections.emptyList(), false, Collections.emptyList(), false, conditional, scene);
    }

    /** The answers to pick, in order; {@code dropped[0]} is set when a note or ordinal was left out. */
    private static List<String> choices(List<String> fragments, boolean[] dropped) {
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
