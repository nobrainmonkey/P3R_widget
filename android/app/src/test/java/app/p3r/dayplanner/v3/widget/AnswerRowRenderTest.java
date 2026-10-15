package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.DialogueHint;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;
import app.p3r.dayplanner.v3.ui.ReminderFixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The widget's answers row (spec 3.1.0 §1.4, docs/DESIGN_v3.md §18) with the shipped rule pack: every
 * card with answers shows them at every size, font scale and SDK, every choice whole and in order
 * (the width ladder: 「升级时答：」, 「或」, three lines, loose wrapping, 11sp; then 「等 N 项」), never
 * cut with "…"; the scene rule, the 朋友/恋人 lines, an unlock step's picks and the side steps'; and the
 * order in which the card's other rows give way. Renders go to $p3r.qa.dir/v3/widget/answers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AnswerRowRenderTest {
    private static final int ID = 47;
    private static final int[][] SIZES = {{180, 130}, {180, 150}, {250, 130}, {250, 160}, {320, 200}, {320, 240}};
    private static Rules pack;
    private Context app;

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        WidgetWorker.setForTest(Runnable::run, (task, delay) -> { });
        if (pack == null) {
            for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                    "android/app/src/main/assets/p3r/p3r_rules.json"}) {
                File f = new File(candidate);
                if (f.isFile()) pack = RulesLoader.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            }
        }
    }

    @After
    public void tearDown() {
        WidgetApi.setOverrideForTest(null);
        WidgetWorker.setForTest(null, null);
        RuntimeEnvironment.setFontScale(1f);
    }

    private View render(FakePlannerApi fake, int width, int height) {
        WidgetApi.setOverrideForTest(fake);
        PlanWidget.Loaded loaded = PlanWidget.load(app, ID);
        return RenderSupport.layout(app, PlanWidget.card(app, ID, loaded.view, loaded.view.toCard(loaded.rules), width, height),
                width, height);
    }

    private CardModel model(FakePlannerApi fake) {
        WidgetApi.setOverrideForTest(fake);
        PlanWidget.Loaded loaded = PlanWidget.load(app, ID);
        return loaded.view.toCard(loaded.rules);
    }

    private static String text(View root, int id) {
        return CardText.plain(((TextView) root.findViewById(id)).getText().toString());
    }

    // ------------------------------------------------------------------ the drawn row

    /** Text compared without spaces, line breaks and the note's 「·」 (where it wraps, the dot goes). */
    private static String canon(String s) {
        return CardText.plain(s).replaceAll("[\\s·]", "");
    }

    /**
     * Every way the ladder may draw {@code line}: label long or short, 「或者」 or 「或」, a class answer
     * with or without its 「（第 n 项）」, cut to 「等 N 项」, caution only.
     */
    private static Set<String> forms(CardModel.AnswerLine line) {
        Set<String> out = new LinkedHashSet<>();
        for (CardModel.AnswerLine form : new LinkedHashSet<>(Arrays.asList(line, line.withoutPosition()))) {
            List<String> labels = new ArrayList<>(Arrays.asList(form.label));
            if (form.shortLabel != null) labels.add(form.shortLabel);
            String note = form.note == null ? "" : form.note;
            int n = form.choices.size();
            for (String label : labels) {
                for (boolean shortOr : new boolean[]{false, true}) {
                    List<String> choices = new ArrayList<>();
                    for (String c : form.choices) choices.add(shortOr ? CardText.shortOr(c) : c);
                    out.add(canon(label + String.join("/", choices) + note));
                    for (int k = 1; k < n; k++) out.add(canon(label + String.join("/", choices.subList(0, k)) + CardText.etc(n) + note));
                    if (n > 0 && form.note != null) out.add(canon(label + note));
                    // A 朋友-route caution alone may take the shorter 「别选表白」 (the same warning).
                    if (note.startsWith("别选")) out.add(canon(label + DialogueHint.FRIEND_AVOID));
                    // Beside a one-line title on the shortest cards, the count alone: 「答：共 2 项，点开看看」,
                    // 「升级时答：点开看看」.
                    if (n > 0) out.add(canon(label + CardText.countOnly(n)));
                    if (n > 0) out.add(canon(label + CardText.TAP_TO_SEE));
                }
            }
        }
        return out;
    }

    /**
     * The drawn answers row against the card model: visible at every size (spec §1.4.4: the answers are
     * never hidden), never 「…」, wrapping only where its shaping allows, each drawn line one of
     * {@link #forms} of its model line, in order. A line is cut to 「等 N 项」 only where the choices it
     * leaves out are wider than 「等 N 项」. Lines go the least needed first ({@link CardRenderer#dropOrder}:
     * a 课堂/考试 answer, certain and missed for good, outlasts the side picks and a 「要是升级了」 line),
     * and a card taller than the shortest (130dp high: two lines of text under the plate) keeps every
     * line unless the row already fills {@link CardRenderer#MAX_ANSWER_LINES} lines or a 「要是升级了」
     * line gave way. Returns whether the row shows.
     */
    private static boolean checkRow(View root, CardModel model, String tag, boolean shortest, List<String> issues) {
        checkTitle(root, model, tag, issues);
        TextView row = root.findViewById(R.id.widget_dialogue_choices);
        if (row.getVisibility() != View.VISIBLE) {
            issues.add(tag + " hides its answers " + model.answers + " (title '"
                    + text(root, R.id.widget_task) + "')");
            return false;
        }
        boolean allLines = !shortest && row.getLineCount() < CardRenderer.MAX_ANSWER_LINES;
        RenderSupport.collectAnswerSplits(row, tag, issues);
        String[] drawn = row.getText().toString().split(CardText.ANSWER_LINE_BREAK, -1);
        if (!shortest && drawn.length < model.answers.size()) {
            System.out.println(tag + " keeps " + drawn.length + " of " + model.answers.size() + " lines in a full row: "
                    + CardText.plain(row.getText().toString()));
        }
        List<CardModel.AnswerLine> shown = matched(model.answers, drawn);
        // (A 「要是升级了」 line that has given way to a 课堂/考试 answer is not counted: it needs more room
        // than is left beside that line.)
        boolean ownGaveWay = shown != null && !shown.contains(model.answers.get(0));
        if (shown == null || (allLines && !ownGaveWay && drawn.length < model.answers.size())) {
            issues.add(tag + " draws " + drawn.length + " of " + model.answers.size() + " lines: " + CardText.plain(row.getText().toString())
                    + " (model " + model.answers + ")");
            return true;
        }
        for (int i = 0; i < drawn.length; i++) {
            String why = needlessCut(row, shown.get(i), drawn[i]);
            if (why != null) issues.add(tag + " " + why);
        }
        // Lines go the least needed first (CardRenderer.dropOrder): a 课堂/考试 line never goes while a
        // side pick or a 「要是升级了」 line (the meeting may not rank up) stays.
        for (CardModel.AnswerLine line : model.answers) {
            if (!CardRenderer.certain(line) || shown.contains(line)) continue;
            for (int i = 0; i < shown.size(); i++) {
                CardModel.AnswerLine other = shown.get(i);
                if (!CardRenderer.certain(other) && (other.conditional || model.answers.indexOf(other) > 0)) {
                    issues.add(tag + " drops the " + line.label + " line but keeps '" + other.text() + "'");
                }
            }
        }
        if (!shortest && shown.size() < model.answers.size()) dropped.add(tag);
        return true;
    }

    /** Taller cards (150dp or more) that left a line out, for the report. */
    private static final List<String> dropped = new ArrayList<>();
    /** Cards that showed a short form of their title, for the report. */
    private static final List<String> shortened = new ArrayList<>();

    /**
     * The title beside the answers (docs/DESIGN_v3.md §18): never hidden, and whole, the model's title
     * or one of its {@link CardModel#shortTitles} (「北村文吉」, 「教皇」 for 「教皇 · 北村文吉、光子」), never
     * cut with "…" and never clipped.
     */
    private static void checkTitle(View root, CardModel model, String tag, List<String> issues) {
        RenderSupport.collectHiddenTitle(root, tag, issues);
        TextView title = root.findViewById(R.id.widget_task);
        String drawn = RenderSupport.titleText(title);
        List<String> forms = new ArrayList<>();
        forms.add(CardText.plainTitle(model.title));
        for (String form : model.shortTitles) forms.add(CardText.plainTitle(form));
        if (!forms.contains(drawn)) issues.add(tag + " shows the title '" + drawn + "', none of " + forms);
        else if (!drawn.equals(forms.get(0))) shortened.add(tag + " " + drawn);
        if (RenderSupport.ellipsized(title)) issues.add(tag + " cuts its title: '" + RenderSupport.lines(title) + "'");
    }

    /**
     * The model line each drawn line is a form of, in the model's order (later lines may be left out,
     * never reordered), or null when a drawn line is no form of any line after the previous one.
     */
    private static List<CardModel.AnswerLine> matched(List<CardModel.AnswerLine> lines, String[] drawn) {
        List<CardModel.AnswerLine> out = new ArrayList<>();
        int next = 0;
        for (String d : drawn) {
            int found = -1;
            for (int i = next; i < lines.size() && found < 0; i++) if (forms(lines.get(i)).contains(canon(d))) found = i;
            if (found < 0) return null;
            out.add(lines.get(found));
            next = found + 1;
        }
        return out;
    }

    /**
     * Why a drawn line cut to 「等 N 项」 should have kept its choices: the ones it leaves out are no wider
     * than 「等 N 项」 at the row's size. Null when it is not cut or the cut saves width.
     */
    private static String needlessCut(TextView row, CardModel.AnswerLine line, String drawn) {
        int n = line.choices.size();
        String etc = CardText.etc(n);
        if (n < 2 || !canon(drawn).contains(canon(etc))) return null;
        for (CardModel.AnswerLine form : new LinkedHashSet<>(Arrays.asList(line, line.withoutPosition()))) {
            for (boolean shortOr : new boolean[]{false, true}) {
                List<String> choices = new ArrayList<>();
                for (String c : form.choices) choices.add(shortOr ? CardText.shortOr(c) : c);
                for (int k = 1; k < n; k++) {
                    String head = String.join("/", choices.subList(0, k)) + etc;
                    if (!canon(drawn).contains(canon(head))) continue;
                    android.graphics.Paint paint = row.getPaint();
                    float left = paint.measureText(" / " + String.join(" / ", choices.subList(k, n)));
                    float cut = paint.measureText(" " + etc);
                    if (left <= cut) {
                        return "cuts '" + CardText.plain(drawn) + "' although the choices it leaves out are no wider than 「" + etc + "」";
                    }
                    return null;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ real pack

    private static int dayOf(int month, int dom) {
        for (int d = pack.firstDay; d <= pack.lastDay; d++) {
            Rules.Day day = pack.day(d);
            if (day != null && day.month() == month && day.dayOfMonth() == dom) return d;
        }
        throw new AssertionError(month + "/" + dom);
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

    /** 7/1 daytime of a game played with every link underway: the summer exams, 屋久岛 and the class answers after them. */
    private static SimState julyStart() {
        SimState s = start(dayOf(7, 1));
        s.cash = 20_000;
        for (Rules.Stat st : pack.stats) s.statPts[st.idx] = st.thresholds[Math.min(3, st.thresholds.length - 1)];
        for (Rules.Link l : pack.links) {
            if (l.kind == Rules.LinkKind.MANUAL && pack.day(dayOf(7, 1)) != null && l.unlock.earliestDay <= dayOf(7, 1)) {
                s.rank[l.idx] = Math.max(s.rank[l.idx], 2);
            }
        }
        return s;
    }

    private FakePlannerApi on(Plan plan, int slot) {
        FakePlannerApi fake = new FakePlannerApi();
        fake.rules = pack;
        fake.plan = plan;
        fake.observed = start(Rules.dayOf(slot));
        fake.observed.slot = slot;
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        return fake;
    }

    /**
     * Spec test 1: the render matrix {180x130 … 320x240} × font {1.0, 1.15} × SDK {26, 31} over every
     * card with a distinct answers row of two real plans (new game; 10/22 with every Persona).
     */
    @Test
    public void everyCardWithAnswersShowsThemWholeAtEverySize() throws Exception {
        Assume.assumeTrue("rule pack not available", pack != null);
        List<Plan> plans = realPlans();
        Map<String, Object[]> cards = new LinkedHashMap<>();
        for (Plan plan : plans) {
            for (Plan.Step step : plan.steps) {
                // Locked runs too: an exam day's card carries its answer.
                CardModel model = model(on(plan, step.slot));
                if (model.answers.isEmpty()) continue;
                cards.putIfAbsent(model.answers.toString(), new Object[]{plan, step.slot});
            }
        }
        System.out.println("distinct answers rows: " + cards.size());
        assertTrue("few answers rows: " + cards.size(), cards.size() >= 30);
        List<String> issues = new ArrayList<>();
        int cut = 0;
        int hidden = 0;
        int rendered = 0;
        List<String> counted = new ArrayList<>();
        for (float scale : new float[]{1f, 1.15f}) {
            RuntimeEnvironment.setFontScale(scale);
            for (Object[] card : cards.values()) {
                FakePlannerApi fake = on((Plan) card[0], (Integer) card[1]);
                CardModel model = model(fake);
                for (int[] size : SIZES) {
                    View root = render(fake, size[0], size[1]);
                    String tag = card[1] + "-" + size[0] + "x" + size[1] + "@" + scale;
                    if (!checkRow(root, model, tag, size[1] < 150, issues)) hidden++;
                    RenderSupport.collectTextIssues(root, tag, issues);
                    RenderSupport.collectCutText(root, tag, issues);
                    if (text(root, R.id.widget_dialogue_choices).contains("项") && !model.answers.toString().contains("项")) cut++;
                    if (text(root, R.id.widget_dialogue_choices).contains(CardText.TAP_TO_SEE)) {
                        counted.add(tag + " " + RenderSupport.titleText(root.findViewById(R.id.widget_task)) + " | "
                                + text(root, R.id.widget_dialogue_choices));
                    }
                    rendered++;
                }
            }
        }
        System.out.println("answers rows rendered: " + rendered + ", cut to 等 N 项: " + cut + ", hidden: " + hidden
                + ", a line left out on a taller card: " + dropped.size() + " " + dropped);
        System.out.println("short titles shown: " + shortened.size() + " " + shortened);
        System.out.println("count only: " + counted.size() + "\n" + String.join("\n", counted));
        if (!issues.isEmpty()) System.out.println("ISSUES\n" + String.join("\n", issues));
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /** The three real plans of the matrix: a new game, 10/22 with every Persona, 7/1 with every link underway. */
    private static List<Plan> realPlans() {
        Profile mid = new Profile();
        mid.personaMode = Profile.PersonaMode.ALL;
        SimState midStart = start(dayOf(10, 22));
        midStart.cash = 1_000;
        return Arrays.asList(Engine.plan(pack, new Profile(), start(dayOf(4, 21)), null, new Engine.Options()),
                Engine.plan(pack, mid, midStart, null, new Engine.Options()),
                Engine.plan(pack, new Profile(), julyStart(), null, new Engine.Options()));
    }

    /**
     * Review: the shortest cards left their title out for the answers (7/10 教皇 at 180x130 also lost its
     * 「课堂：卡巴拉」 line; 10/22 战车 at 180x130 and 115% font), and 「答：…」 without who it is for confuses.
     * At 130dp high the title keeps one line beside one line of answers: a form of the row that fits, a
     * 课堂/考试 answer whole rather than a line with no choice in it, else the count (「答：共 2 项，点开看看」).
     * Each case is the first real card of that meeting on that day with its own line and a class line
     * ({@link #ownAndClassLines}): 7/10 教皇 of the 7/1 plan (「答：我也去看看吧或我在这里等她 / 真担心啊」,
     * 「课堂：卡巴拉」; the new game's 7/10 教皇 has only the class line). Since 「尽量都体验」 keeps the
     * promises of 「尽量多 MAX」 (DESIGN §20) the 10/22 plan meets 战车 first on 10/29; the same 战车 card
     * (「答：明年的选拔赛呢？ / 加油」 and a class answer) is the new game's 10/19. Renders under answers/ as
     * shortest-*.
     */
    @Test
    public void theShortestCardsKeepTheirTitleBesideTheAnswers() throws Exception {
        Assume.assumeTrue("rule pack not available", pack != null);
        File dir = RenderSupport.outputDir("answers");
        Object[][] wanted = {{"0710-hierophant", dayOf(7, 10), "meet:hierophant"}, {"1019-chariot", dayOf(10, 19), "meet:chariot"}};
        List<Plan> plans = realPlans();
        List<String> issues = new ArrayList<>();
        for (Object[] w : wanted) {
            Plan found = null;
            int slot = -1;
            for (Plan plan : plans) {
                for (Plan.Step step : plan.steps) {
                    if (found == null && Rules.dayOf(step.slot) == (Integer) w[1] && w[2].equals(step.action)
                            && ownAndClassLines(model(on(plan, step.slot)))) {
                        found = plan;
                        slot = step.slot;
                    }
                }
            }
            assertNotNull(w[0] + " with its own line and a class line is in a real plan", found);
            for (float scale : new float[]{1f, 1.15f}) {
                RuntimeEnvironment.setFontScale(scale);
                FakePlannerApi fake = on(found, slot);
                CardModel model = model(fake);
                assertTrue(w[0] + " has its own line and a class line", ownAndClassLines(model));
                for (int[] size : new int[][]{{180, 130}, {250, 130}, {180, 150}}) {
                    View root = render(fake, size[0], size[1]);
                    String tag = "shortest-" + w[0] + "-" + size[0] + "x" + size[1] + (scale > 1 ? "-font115" : "");
                    RenderSupport.save(root, dir, tag);
                    RenderSupport.collectTextIssues(root, tag, issues);
                    RenderSupport.collectCutText(root, tag, issues);
                    RenderSupport.collectWordSplits(root, tag, issues);
                    RenderSupport.collectDotLineEnds(root, tag, issues);
                    checkRow(root, model, tag, size[1] < 150, issues);
                    TextView title = root.findViewById(R.id.widget_task);
                    TextView row = root.findViewById(R.id.widget_dialogue_choices);
                    if (size[1] == 130 && (title.getLineCount() != 1 || row.getLineCount() != 1)) {
                        issues.add(tag + " shows " + title.getLineCount() + " title and " + row.getLineCount() + " answers lines");
                    }
                    System.out.println(tag + ": " + RenderSupport.lines(title) + " | " + text(root, R.id.widget_dialogue_choices)
                            .replace("\n", " / "));
                }
            }
        }
        RuntimeEnvironment.setFontScale(1f);
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /** The card's answers row starts with the step's own line (not a class answer) and ends with a class answer. */
    private static boolean ownAndClassLines(CardModel model) {
        List<CardModel.AnswerLine> lines = model.answers;
        return lines.size() >= 2 && !lines.get(0).label.startsWith("课堂")
                && lines.get(lines.size() - 1).label.startsWith("课堂");
    }

    // ------------------------------------------------------------------ card scenes

    /** A one-step plan on 11/22 (Sunday) with {@code step} as the observed card. */
    private FakePlannerApi scene(Plan.Step step) {
        FakePlannerApi fake = new FakePlannerApi();
        fake.rules = pack;
        int slot = Rules.slot(dayOf(11, 22), false);
        step.slot = slot;
        fake.plan.fromSlot = slot;
        fake.plan.steps.clear();
        fake.plan.steps.add(step);
        fake.plan.toSlot = slot;
        fake.observed.slot = slot;
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        return fake;
    }

    private static Plan.Step meeting(String link, int from, int to, boolean certain) {
        Rules.Link l = pack.link(link);
        Plan.Step s = FakePlannerApi.rank(FakePlannerApi.step(0, "meet:" + link, "social", l.labelZh,
                to > from ? "RANK " + from + " → " + to : "RANK " + from + " · 见面（预计不升级）", l.locationZh, null), from, to);
        s.entity = link;
        s.predicted.certain = certain;
        return s;
    }

    /** Spec test 3: 隐者 4→4 never 「我们结婚吧」, the RANK 5 line muted; a hold and a certain no-rank-up: no row. */
    @Test
    public void aMeetingWithoutARankUpShowsTheNextSceneMuted() {
        Assume.assumeTrue("rule pack not available", pack != null);
        FakePlannerApi fake = scene(meeting("hermit", 4, 4, false));
        View root = render(fake, 320, 200);
        String row = text(root, R.id.widget_dialogue_choices);
        assertFalse(row, row.contains("我们结婚吧"));
        assertTrue(row, row.startsWith(DialogueHint.CONDITIONAL_PREFIX) || row.startsWith(DialogueHint.CONDITIONAL_SHORT_PREFIX));
        assertTrue(row, row.contains("大~~蠢~~蛋~~！"));
        CharSequence drawn = ((TextView) root.findViewById(R.id.widget_dialogue_choices)).getText();
        ForegroundColorSpan[] muted = ((Spanned) drawn).getSpans(0, drawn.length(), ForegroundColorSpan.class);
        assertEquals("the whole line is muted", 1, muted.length);
        assertEquals(CardRenderer.MUTED_ANSWER_ALPHA, muted[0].getForegroundColor() >>> 24);
        // Narrow: 「升级时答：」.
        View narrow = render(fake, 180, 150);
        assertTrue(text(narrow, R.id.widget_dialogue_choices), text(narrow, R.id.widget_dialogue_choices).startsWith(DialogueHint.CONDITIONAL_SHORT_PREFIX));

        Plan.Step held = meeting("hermit", 4, 4, false);
        held.assumptions.add("rank_hold");
        assertEquals(View.GONE, render(scene(held), 320, 200).findViewById(R.id.widget_dialogue_choices).getVisibility());
        assertEquals(View.GONE, render(scene(meeting("hermit", 4, 4, true)), 320, 200).findViewById(R.id.widget_dialogue_choices).getVisibility());
        assertEquals("答：我们结婚吧", text(render(scene(meeting("hermit", 3, 4, false)), 320, 200), R.id.widget_dialogue_choices));
    }

    /** Spec test 4 and 5: 朋友 and 恋人 routes; no data → no row; every choice free → 怎么回答都可以. */
    @Test
    public void routesNoDataAndFreeChoices() {
        Assume.assumeTrue("rule pack not available", pack != null);
        assertEquals("答：别选「我喜欢风花」", text(render(scene(meeting("priestess", 8, 9, false)), 320, 200), R.id.widget_dialogue_choices));
        assertEquals("答：别选表白的回答", text(render(scene(meeting("strength", 7, 8, false)), 320, 200), R.id.widget_dialogue_choices));
        assertEquals(View.GONE, render(scene(meeting("emperor", 3, 4, false)), 320, 200).findViewById(R.id.widget_dialogue_choices).getVisibility());
        assertEquals("答：怎么回答都可以", text(render(scene(meeting("moon", 9, 10, false)), 320, 200), R.id.widget_dialogue_choices));
        // The full app line is read out.
        CardModel justice = model(scene(meeting("justice", 4, 5, false)));
        assertTrue(justice.taskDescription, justice.taskDescription.contains(
                "参考回答：随便问吧（1） / 随时欢迎千寻找我倾诉（1） / 握住她的手（1）；" + DialogueHint.FRIEND_CAUTION));
    }

    /** Spec test 6: the moon's unlock card shows its three answers; a justice talk done 顺便 shows its pick. */
    @Test
    public void unlockAndSideStepPicks() {
        Assume.assumeTrue("rule pack not available", pack != null);
        Plan.Step moon = FakePlannerApi.step(0, "unlock:moon:1", "unlock", "月亮 · 末光望美", "去喷泉广场找末光望美",
                "桐叶购物中心 · 喷泉广场", "完成后月亮社群就解锁了");
        View card = render(scene(moon), 320, 200);
        assertEquals("答：费洛蒙咖啡 / 红色 / 叶隐盖饭", text(card, R.id.widget_dialogue_choices));
        Plan.Step meet = meeting("chariot", 1, 2, false);
        FakePlannerApi.side(meet, "unlock:justice:0", "顺便：和伏见千寻搭话（第 1 次）");
        View side = render(scene(meet), 320, 240);
        assertEquals("答：是你毅力不够\n顺便选：有话想和你说", text(side, R.id.widget_dialogue_choices).replace(CardText.WJ + "", ""));
        assertEquals("the side row keeps its own text", "顺便：和伏见千寻搭话（第 1 次）", text(side, R.id.widget_side));
    }

    /**
     * Spec test 2: with a hint, a side step, a cue, the chip row and a 当日提醒, the answers always stay;
     * the cue goes first, then the reminder, then the hint and the side step; at 130dp high the chip's
     * row goes before the answers do.
     */
    @Test
    public void theOtherRowsGiveWayInOrder() throws Exception {
        Assume.assumeTrue("rule pack not available", pack != null);
        File dir = RenderSupport.outputDir("answers");
        Plan.Step step = meeting("chariot", 1, 2, false);
        step.hintZh = "记得带上「战车」阿尔卡那的人格面具";
        step.whyZh = "预计这次见面能升到 RANK 2";
        FakePlannerApi.side(step, "unlock:devil:0", "顺便：把 20,000 日元交给穿西装的男人");
        FakePlannerApi fake = scene(step);
        ReminderFixtures.with(fake.plan, ReminderFixtures.tv(Rules.dayOf(fake.plan.fromSlot)));
        List<String> issues = new ArrayList<>();
        // The order the rows go in: the least important first.
        int[] order = {R.id.widget_detail_cue, R.id.widget_reminder, R.id.widget_hint, R.id.widget_side};
        for (int[] size : new int[][]{{320, 240}, {320, 200}, {250, 160}, {180, 150}, {250, 130}, {180, 130}}) {
            View root = render(fake, size[0], size[1]);
            String tag = "rows-" + size[0] + "x" + size[1];
            RenderSupport.save(root, dir, tag);
            RenderSupport.collectTextIssues(root, tag, issues);
            assertEquals(tag, View.VISIBLE, root.findViewById(R.id.widget_dialogue_choices).getVisibility());
            assertTrue(tag, text(root, R.id.widget_dialogue_choices).startsWith("答："));
            // A row that shows keeps every more important one: none goes before a less important row.
            boolean kept = false;
            for (int id : order) {
                boolean shown = root.findViewById(id).getVisibility() == View.VISIBLE;
                if (kept && !shown) issues.add(tag + " dropped " + RenderSupport.name(root.findViewById(id)) + " before a less important row");
                kept |= shown;
            }
            if (size[1] == 130) {
                assertEquals(tag + " the chip's row goes first", View.GONE, root.findViewById(R.id.widget_meta_row).getVisibility());
            }
            if (size[0] == 250 && size[1] == 160) {
                assertEquals(tag, View.GONE, root.findViewById(R.id.widget_detail_cue).getVisibility());
            }
        }
        View tall = render(fake, 320, 240);
        assertEquals(View.VISIBLE, tall.findViewById(R.id.widget_reminder).getVisibility());
        assertEquals(View.VISIBLE, tall.findViewById(R.id.widget_meta_row).getVisibility());
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /** Spec test 9: golden renders of four scenes, with the cut-text, word-split and dot checks. */
    @Test
    public void goldenRendersOfFourScenes() throws Exception {
        Assume.assumeTrue("rule pack not available", pack != null);
        File dir = RenderSupport.outputDir("answers");
        List<String> issues = new ArrayList<>();
        Object[][] scenes = {{"justice-R5", meeting("justice", 4, 5, false)}, {"hermit-R5", meeting("hermit", 4, 5, false)},
                {"strength-R2", meeting("strength", 1, 2, false)}, {"justice-R2", meeting("justice", 1, 2, false)}};
        for (Object[] s : scenes) {
            for (float scale : new float[]{1f, 1.15f}) {
                RuntimeEnvironment.setFontScale(scale);
                for (int[] size : SIZES) {
                    Plan.Step step = (Plan.Step) s[1];
                    FakePlannerApi fake = scene(step);
                    View root = render(fake, size[0], size[1]);
                    String tag = s[0] + "-" + size[0] + "x" + size[1] + (scale > 1 ? "-font115" : "");
                    RenderSupport.save(root, dir, tag);
                    RenderSupport.collectCutText(root, tag, issues);
                    RenderSupport.collectWordSplits(root, tag, issues);
                    RenderSupport.collectDotLineEnds(root, tag, issues);
                    RenderSupport.collectTextIssues(root, tag, issues);
                    checkRow(root, model(fake), tag, size[1] < 150, issues);
                }
            }
        }
        RuntimeEnvironment.setFontScale(1f);
        assertNotNull(dir.listFiles());
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }
}
