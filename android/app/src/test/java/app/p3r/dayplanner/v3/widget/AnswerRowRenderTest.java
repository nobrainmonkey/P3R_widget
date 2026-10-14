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

    /** Every way the ladder may draw {@code line}: label long or short, 「或者」 or 「或」, cut to 「等 N 项」, caution only. */
    private static Set<String> forms(CardModel.AnswerLine line) {
        Set<String> out = new LinkedHashSet<>();
        List<String> labels = new ArrayList<>(Arrays.asList(line.label));
        if (line.shortLabel != null) labels.add(line.shortLabel);
        String note = line.note == null ? "" : line.note;
        int n = line.choices.size();
        for (String label : labels) {
            for (boolean shortOr : new boolean[]{false, true}) {
                List<String> choices = new ArrayList<>();
                for (String c : line.choices) choices.add(shortOr ? CardText.shortOr(c) : c);
                out.add(canon(label + String.join("/", choices) + note));
                for (int k = 1; k < n; k++) out.add(canon(label + String.join("/", choices.subList(0, k)) + CardText.etc(n) + note));
                if (n > 0 && line.note != null) out.add(canon(label + note));
            }
        }
        return out;
    }

    /**
     * The drawn answers row against the card model: visible, never 「…」, wrapping only where its
     * shaping allows, each drawn line one of {@link #forms} of its model line, in order. Only a
     * shortest card (130dp high: two lines of text under the plate) may keep just the step's own
     * line, or none where even its shortest form does not fit beside a one-line title. Returns
     * whether the row shows.
     */
    private static boolean checkRow(View root, CardModel model, String tag, boolean shortest, List<String> issues) {
        TextView row = root.findViewById(R.id.widget_dialogue_choices);
        if (row.getVisibility() != View.VISIBLE) {
            if (!shortest) issues.add(tag + " hides its answers " + model.answers);
            return false;
        }
        boolean allLines = !shortest;
        RenderSupport.collectAnswerSplits(row, tag, issues);
        String[] drawn = row.getText().toString().split(CardText.ANSWER_LINE_BREAK, -1);
        if (drawn.length > model.answers.size() || (allLines && drawn.length < model.answers.size())) {
            issues.add(tag + " draws " + drawn.length + " of " + model.answers.size() + " lines: " + CardText.plain(row.getText().toString()));
        }
        for (int i = 0; i < Math.min(drawn.length, model.answers.size()); i++) {
            if (!forms(model.answers.get(i)).contains(canon(drawn[i]))) {
                issues.add(tag + " line '" + CardText.plain(drawn[i]) + "' is no form of '" + model.answers.get(i).text() + "'");
            }
        }
        return true;
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
        Profile mid = new Profile();
        mid.personaMode = Profile.PersonaMode.ALL;
        SimState midStart = start(dayOf(10, 22));
        midStart.cash = 1_000;
        List<Plan> plans = Arrays.asList(Engine.plan(pack, new Profile(), start(dayOf(4, 21)), null, new Engine.Options()),
                Engine.plan(pack, mid, midStart, null, new Engine.Options()));
        Map<String, Object[]> cards = new LinkedHashMap<>();
        for (Plan plan : plans) {
            for (Plan.Step step : plan.steps) {
                if (!PlanItems.isProductive(step)) continue;
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
                    rendered++;
                }
            }
        }
        System.out.println("answers rows rendered: " + rendered + ", cut to 等 N 项: " + cut + ", none on a 130dp card: " + hidden);
        assertTrue(String.join("\n", issues), issues.isEmpty());
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
