package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

/**
 * {@link DialogueHint} over the shipped rule pack (spec 3.1.0 §1.4, docs/DESIGN_v3.md §18): the scene
 * rule (a meeting without a rank-up never shows the scene already played), the 朋友/恋人 routes, the
 * widget's 「答：」 lines, an unlock step's picks, the app line byte-identical to 3.0.2 for every rank-up
 * scene, and the widget's choices equal to the engine's how-to for the same step. Plain JVM.
 */
public class DialogueHintTest {
    private static Rules pack;

    @BeforeClass
    public static void loadPack() throws Exception {
        for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            File f = new File(candidate);
            if (f.isFile()) pack = RulesLoader.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
        Assume.assumeTrue("rule pack not available", pack != null);
    }

    private static int link(String id) {
        Rules.Link l = pack.link(id);
        assertNotNull(id, l);
        return l.idx;
    }

    private static Plan.Predicted pred(int from, int to, boolean certain) {
        Plan.Predicted p = new Plan.Predicted();
        p.rankFrom = from;
        p.rankTo = to;
        p.rankUp = to > from;
        p.certain = certain;
        return p;
    }

    private static Profile romance(String id) {
        Profile p = new Profile();
        p.route[link(id)] = Profile.Route.ROMANCE;
        return p;
    }

    private static int dayOf(int month, int dom) {
        for (int d = pack.firstDay; d <= pack.lastDay; d++) {
            Rules.Day day = pack.day(d);
            if (day != null && day.month() == month && day.dayOfMonth() == dom) return d;
        }
        throw new AssertionError(month + "/" + dom);
    }

    // ------------------------------------------------------------------ scene rule

    /** Spec test 3: 隐者 RANK 4 without a rank-up never shows 「我们结婚吧」 (the 3→4 scene) but RANK 5's, muted. */
    @Test
    public void aMeetingWithoutARankUpShowsTheNextScene() {
        int hermit = link("hermit");
        int day = dayOf(11, 22);
        DialogueHint.Answers maybe = DialogueHint.forMeeting(pack, null, hermit, pred(4, 4, false), day);
        assertTrue(maybe.conditional);
        assertEquals(5, maybe.scene);
        assertEquals("要是升级了，答：大~~蠢~~蛋~~！ / Ｙ子是教醉拳的代理师傅？或者Ｙ子是老师？", DialogueHint.widgetLine(maybe));
        assertEquals("要是升级了，答：大~~蠢~~蛋~~！（3） / Ｙ子是教醉拳的代理师傅？或者Ｙ子是老师？", DialogueHint.appLine(maybe));
        assertFalse(DialogueHint.appLine(maybe).contains("我们结婚吧"));
        // The rank-up into RANK 4 is that scene.
        DialogueHint.Answers up = DialogueHint.forMeeting(pack, null, hermit, pred(3, 4, false), day);
        assertFalse(up.conditional);
        assertEquals("参考回答：我们结婚吧（3）", DialogueHint.appLine(up));
        assertEquals("答：我们结婚吧", DialogueHint.widgetLine(up));
        // No rank-up for certain, MAX already, no prediction or rules: nothing.
        assertSame(DialogueHint.Answers.NONE, DialogueHint.forMeeting(pack, null, hermit, pred(4, 4, true), day));
        assertSame(DialogueHint.Answers.NONE, DialogueHint.forMeeting(pack, null, hermit, pred(10, 10, false), day));
        assertSame(DialogueHint.Answers.NONE, DialogueHint.forMeeting(pack, null, hermit, null, day));
        assertSame(DialogueHint.Answers.NONE, DialogueHint.forMeeting(null, null, hermit, pred(4, 4, false), day));
        assertNull(DialogueHint.widgetLine(DialogueHint.Answers.NONE));
    }

    /** Held → none: a story hold of the rank-up (until a day or for good) and a hold the player reported. */
    @Test
    public void aHeldMeetingHasNoAnswers() {
        Rules.Link hermit = pack.links[link("hermit")];
        int day = dayOf(11, 22);
        Rules.Hold hold = new Rules.Hold();
        hold.rank = 5;
        hold.untilDay = day + 1;
        hermit.holds.add(hold);
        try {
            assertSame(DialogueHint.Answers.NONE, DialogueHint.forMeeting(pack, null, hermit.idx, pred(4, 4, false), day));
            assertTrue("the hold has ended", DialogueHint.forMeeting(pack, null, hermit.idx, pred(4, 4, false), day + 1).conditional);
            hold.untilDay = -1;
            assertSame(DialogueHint.Answers.NONE, DialogueHint.forMeeting(pack, null, hermit.idx, pred(4, 4, false), day + 30));
            hold.rank = 6;
            assertFalse("another rank's hold", DialogueHint.forMeeting(pack, null, hermit.idx, pred(4, 4, false), day).isEmpty());
        } finally {
            hermit.holds.remove(hold);
        }
        Plan.Step step = new Plan.Step();
        step.slot = Rules.slot(day, false);
        step.kind = "social";
        step.entity = "hermit";
        step.predicted = pred(4, 4, false);
        assertTrue(DialogueHint.forMeeting(pack, null, step).conditional);
        step.assumptions.add(DialogueHint.HOLD_ASSUMPTION);
        assertSame(DialogueHint.Answers.NONE, DialogueHint.forMeeting(pack, null, step));
        step.kind = "note";
        assertSame("not a meeting", DialogueHint.Answers.NONE, DialogueHint.forMeeting(pack, null, step));
    }

    // ------------------------------------------------------------------ routes

    /** Spec test 4: 朋友 route names the romance answer to avoid; 恋人 shows the answers; unmarked R ≥ 8 → 别选表白的回答. */
    @Test
    public void friendAndRomanceRoutes() {
        int priestess = link("priestess");
        DialogueHint.Answers friend = DialogueHint.forScene(pack, new Profile(), priestess, 9, false);
        assertEquals("答：别选「我喜欢风花」", DialogueHint.widgetLine(friend));
        assertEquals("the app line is unchanged", DialogueHint.FRIEND_FALLBACK, DialogueHint.appLine(friend));
        DialogueHint.Answers love = DialogueHint.forScene(pack, romance("priestess"), priestess, 9, false);
        assertEquals("参考回答：我喜欢风花（1），恋人线", DialogueHint.appLine(love));
        assertEquals("答：我喜欢风花，恋人线", DialogueHint.widgetLine(love));

        DialogueHint.Answers strength = DialogueHint.forScene(pack, new Profile(), link("strength"), 8, false);
        assertEquals("答：别选表白的回答", DialogueHint.widgetLine(strength));
        assertEquals(DialogueHint.FRIEND_FALLBACK, DialogueHint.appLine(strength));

        int justice = link("justice");
        DialogueHint.Answers five = DialogueHint.forScene(pack, new Profile(), justice, 5, false);
        assertEquals("答：随便问吧 / 随时欢迎千寻找我倾诉 / 握住她的手 · 别选「产生恋爱感情了」", DialogueHint.widgetLine(five));
        assertEquals("参考回答：随便问吧（1） / 随时欢迎千寻找我倾诉（1） / 握住她的手（1）；" + DialogueHint.FRIEND_CAUTION,
                DialogueHint.appLine(five));
        DialogueHint.Answers nine = DialogueHint.forScene(pack, new Profile(), justice, 9, false);
        assertEquals("答：突然这是怎么了？ · 别选表白", DialogueHint.widgetLine(nine));
        // A link that cannot be romanced has no caution.
        assertEquals("答：是你毅力不够", DialogueHint.widgetLine(DialogueHint.forScene(pack, new Profile(), link("chariot"), 2, false)));
    }

    /** Spec test 5: no data → no widget row (never the generic hint); every choice free → 怎么回答都可以. */
    @Test
    public void noDataShowsNothingAndFreeChoicesSaySo() {
        DialogueHint.Answers none = DialogueHint.forScene(pack, new Profile(), link("emperor"), 4, false);
        assertTrue(none.isEmpty());
        assertNull(DialogueHint.widgetLine(none));
        assertEquals(DialogueHint.FALLBACK, DialogueHint.appLine(none));
        assertTrue("ordinals only", DialogueHint.forScene(pack, new Profile(), link("chariot"), 4, false).isEmpty());
        DialogueHint.Answers any = DialogueHint.forScene(pack, new Profile(), link("moon"), 10, false);
        assertTrue(any.any);
        assertEquals("答：怎么回答都可以", DialogueHint.widgetLine(any));
        assertEquals(DialogueHint.ANY, DialogueHint.appLine(any));
        assertEquals("要是升级了，答：怎么回答都可以",
                DialogueHint.widgetLine(DialogueHint.forScene(pack, new Profile(), link("moon"), 10, true)));
    }

    /** Spec test 6: the moon's three answers; a justice talk's pick; nothing for a step without 选/回答. */
    @Test
    public void unlockStepsNameTheirPicks() {
        assertEquals("答：费洛蒙咖啡 / 红色 / 叶隐盖饭", DialogueHint.widgetLine(DialogueHint.forUnlockStep(step("moon", 1))));
        assertEquals(Arrays.asList("有话想和你说"), DialogueHint.forUnlockStep(step("justice", 0)).choices);
        assertEquals(Arrays.asList("我也是男性"), DialogueHint.forUnlockStep(step("justice", 1)).choices);
        assertEquals(Arrays.asList("基尔酒", "血腥玛丽", "卡西斯橙汁", "乌龙茶"), DialogueHint.forUnlockStep(step("tower", 1)).choices);
        assertSame("「柿子树叶」 is no pick", DialogueHint.Answers.NONE, DialogueHint.forUnlockStep(step("hierophant", 0)));
        assertSame(DialogueHint.Answers.NONE, DialogueHint.forUnlockStep(step("emperor", 0)));
        assertSame("the rumour before the moon's opening asks nothing", DialogueHint.Answers.NONE, DialogueHint.forUnlockStep(step("moon", 0)));
        assertSame(DialogueHint.Answers.NONE, DialogueHint.forUnlockStep(null));
    }

    private static Rules.Step step(String link, int k) {
        return pack.links[link(link)].unlock.steps.get(k);
    }

    // ------------------------------------------------------------------ 3.0.2 parity

    /** Spec test 11: the app line of every rank-up scene, both routes, reads exactly as 3.0.2 wrote it. */
    @Test
    public void theAppLineOfEveryRankUpIsByteIdenticalTo302() {
        int compared = 0;
        for (Rules.Link l : pack.links) {
            Set<Integer> scenes = new HashSet<>(l.dialogue.keySet());
            for (int r = 1; r <= 11; r++) scenes.add(r);
            for (int scene : scenes) {
                for (Profile p : new Profile[]{null, new Profile(), romance(l.id)}) {
                    String before = Legacy302.line(pack, p, l.idx, scene);
                    assertEquals(l.id + " RANK " + scene, before, DialogueHint.appLine(DialogueHint.forScene(pack, p, l.idx, scene, false)));
                    compared++;
                }
            }
        }
        assertTrue(compared > 22 * 11);
    }

    // ------------------------------------------------------------------ engine how-to

    /**
     * Spec test 10: on real plans, a meeting's how-to carries exactly the app line of its scene
     * (none for a meeting without a scene), and the widget's choices are that line's choices without
     * the 「（1）」 counts; an unlock step's picks are the 「…」 its how-to names, in order.
     */
    @Test
    public void theWidgetsChoicesAreTheEnginesHowToChoices() {
        Profile mid = new Profile();
        mid.personaMode = Profile.PersonaMode.ALL;
        SimState midStart = start(dayOf(10, 22));
        midStart.cash = 1_000;
        int meetings = 0;
        int conditional = 0;
        int unlocks = 0;
        for (Object[] run : new Object[][]{{new Profile(), start(dayOf(4, 21))}, {mid, midStart}}) {
            Profile profile = (Profile) run[0];
            Plan plan = Engine.plan(pack, profile, (SimState) run[1], null, new Engine.Options());
            for (Plan.Step s : plan.steps) {
                if ("social".equals(s.kind)) {
                    DialogueHint.Answers answers = DialogueHint.forMeeting(pack, profile, s);
                    String line = answerLine(s.detailZh);
                    if (answers.isEmpty()) {
                        assertNull(s.slot + " " + s.detailZh, line);
                        continue;
                    }
                    meetings++;
                    if (answers.conditional) conditional++;
                    assertEquals(s.slot + " " + s.action, DialogueHint.appLine(answers), line);
                    if (!answers.choices.isEmpty()) assertEquals(choicesOf(line), DialogueHint.widgetChoices(answers));
                    assertFalse("never the scene already played", !s.predicted.rankUp && !answers.conditional);
                } else if (s.action.startsWith("unlock:")) {
                    String[] key = s.action.split(":");
                    DialogueHint.Answers picks = DialogueHint.forUnlockStep(pack.link(key[1]).unlock.steps.get(Integer.parseInt(key[2])));
                    if (picks.isEmpty()) continue;
                    unlocks++;
                    List<String> named = new ArrayList<>();
                    Matcher m = Pattern.compile("「([^「」]+)」").matcher(s.detailZh);
                    while (m.find()) named.add(m.group(1));
                    assertTrue(s.action + ": " + s.detailZh, String.join("|", named).contains(String.join("|", picks.choices)));
                }
            }
        }
        System.out.println("how-to answers checked: " + meetings + " meetings (" + conditional + " if they rank up), " + unlocks + " unlock steps");
        assertTrue(meetings > 20 && conditional > 0);
    }

    /** The how-to's answers line, or null. */
    private static String answerLine(String detail) {
        if (detail == null) return null;
        for (String l : detail.split("\n")) {
            String t = l.trim();
            if (t.startsWith(DialogueHint.PREFIX) || t.startsWith(DialogueHint.CONDITIONAL_PREFIX)
                    || t.equals(DialogueHint.FRIEND_FALLBACK)) {
                return t;
            }
        }
        return null;
    }

    /** The choices of an app line, counts left out: 「参考回答：A（1） / B；…」 → [A, B]. */
    private static List<String> choicesOf(String line) {
        String body = line.substring(line.indexOf('：') + 1);
        int caution = body.indexOf('；');
        if (caution >= 0) body = body.substring(0, caution);
        List<String> out = new ArrayList<>();
        for (String c : body.split(" / ")) out.add(DialogueHint.withoutCounts(c));
        return out;
    }

    private static SimState start(int day) {
        SimState s = new SimState(pack.blocks.length, pack.activities.length);
        s.slot = Rules.slot(day, false);
        s.cashExact = true;
        for (Rules.Link l : pack.links) {
            if (l.kind != Rules.LinkKind.STORY || l.storyRankDay == null) continue;
            for (int r = 1; r < l.storyRankDay.length && r <= 10; r++) {
                if (l.storyRankDay[r] >= 0 && l.storyRankDay[r] <= day) s.rank[l.idx] = r;
            }
        }
        return s;
    }

    /** Widget lines follow docs/GLOSSARY_zh.md: 「答：」 (never 「参考回答：」), full-width punctuation, no "…". */
    @Test
    public void widgetLinesFollowTheGlossary() {
        Pattern halfWidth = Pattern.compile("[\\u4e00-\\u9fff」）][?!]|[?!][\\u4e00-\\u9fff「（]|\\.\\.\\.|…");
        int lines = 0;
        for (Rules.Link l : pack.links) {
            for (Map.Entry<Integer, List<String>> e : l.dialogue.entrySet()) {
                for (boolean conditional : new boolean[]{false, true}) {
                    String w = DialogueHint.widgetLine(DialogueHint.forScene(pack, new Profile(), l.idx, e.getKey(), conditional));
                    if (w == null) continue;
                    lines++;
                    assertFalse(w, w.contains("参考回答"));
                    assertTrue(w, w.startsWith(conditional ? DialogueHint.CONDITIONAL_PREFIX : DialogueHint.WIDGET_PREFIX));
                    assertFalse(w, halfWidth.matcher(w).find());
                    assertFalse("counts are left out: " + w, Pattern.compile("（[0-9]+）").matcher(w).find());
                }
            }
        }
        assertTrue(lines > 40);
    }

    /** The 3.0.2 dialogue hint, frozen (DialogueHint before 3.1.0), for the parity check. */
    private static final class Legacy302 {
        static final Pattern WARNING = Pattern.compile("会进入|不会进入|破裂|做朋友|进入恋|选\\s*[0-9０-９]|选第");
        static final Pattern ROMANCE = Pattern.compile("恋人线|恋爱线|恋人路线|恋爱路线");
        static final Pattern QUOTED = Pattern.compile("[“「\"]([^”」\"]+)[”」\"]");
        static final Pattern ORDINAL = Pattern.compile("第\\s*[0-9０-９一二三四五六七八九十]+\\s*(项|个)");
        static final Pattern OPTION_NOTE = Pattern.compile("（[^（）]*(选项|选择|前面)[^（）]*）");
        static final Pattern LABEL = Pattern.compile("^[^：:/（]{0,10}(选择|对话)[^：:/（]{0,4}[：:]\\s*");
        static final String FALLBACK = "选能让对方高兴的回答（♪ 越多越好）";
        static final String PREFIX = "参考回答：";
        static final String FRIEND_CAUTION = "只想做朋友的话，别选表白的回答";
        static final String FRIEND_FALLBACK = FALLBACK + "；" + FRIEND_CAUTION;

        static String line(Rules rules, Profile profile, int link, int sceneRank) {
            if (rules == null || link < 0 || link >= rules.links.length) return FALLBACK;
            Rules.Link l = rules.links[link];
            List<String> fragments = l.dialogue.get(sceneRank);
            boolean romance = profile != null && link < profile.route.length && profile.route[link] == Profile.Route.ROMANCE;
            if (l.romanceable && !romance) return friendLine(fragments, sceneRank);
            String line = line(fragments);
            return line != null ? line : FALLBACK;
        }

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
                if (sceneRank >= 8) return FRIEND_FALLBACK;
                String line = line(fragments);
                return line == null ? FALLBACK : line;
            }
            boolean[] dropped = {false};
            List<String> answers = answers(kept, dropped);
            answers.removeIf(romanceAnswers::contains);
            if (answers.isEmpty()) return FRIEND_FALLBACK;
            return PREFIX + String.join(" / ", answers) + "；" + FRIEND_CAUTION;
        }

        static String line(List<String> fragments) {
            if (fragments == null || fragments.isEmpty()) return null;
            boolean[] dropped = {false};
            List<String> answers = answers(fragments, dropped);
            if (answers.isEmpty()) return dropped[0] ? null : PREFIX + "怎么回答都可以";
            return PREFIX + String.join(" / ", answers);
        }

        static List<String> answers(List<String> fragments, boolean[] dropped) {
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
}
