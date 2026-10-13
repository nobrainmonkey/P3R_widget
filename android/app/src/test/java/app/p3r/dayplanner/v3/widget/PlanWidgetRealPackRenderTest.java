package app.p3r.dayplanner.v3.widget;

import app.p3r.dayplanner.v3.ui.Dialogue;

import android.content.Context;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plans with the shipped rule pack and renders the steps with the longest real engine wording of
 * every kind (and every step with side steps or a reminder) from the smallest resize (130dp high)
 * to the default widget sizes,
 * into $p3r.qa.dir/v3/widget/real. Fails on clipped text, a starved location or internal words.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlanWidgetRealPackRenderTest {
    private static final int ID = 57;
    private static final int[][] SIZES = {{180, 130}, {180, 150}, {250, 130}, {250, 160}, {320, 200}};
    /** Words the widget itself must never show (the engine's own why-lines are checked by the engine). */
    private static final String[] INTERNAL = {"act:", "meet:", "unlock:", "tower:", "locked:", "note:", "episode:",
            "null", "Lv.6", "Persona", "FREE", "剧情安排，", "加深关系", "自由时间", "预计 +", "RANK MAX"};
    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        WidgetWorker.setForTest(Runnable::run, (task, delay) -> { });
    }

    @After
    public void tearDown() {
        WidgetApi.setOverrideForTest(null);
        WidgetWorker.setForTest(null, null);
    }

    private static Rules loadPack() throws Exception {
        for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            File f = new File(candidate);
            if (f.isFile()) return RulesLoader.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
        return null;
    }

    private static int dayOf(Rules rules, int month, int dom) {
        for (int d = rules.firstDay; d <= rules.lastDay; d++) {
            Rules.Day day = rules.day(d);
            if (day != null && day.month() == month && day.dayOfMonth() == dom) return d;
        }
        throw new AssertionError(month + "/" + dom);
    }

    private static SimState start(Rules rules, int day) {
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = Rules.slot(day, false);
        s.cashExact = true;
        for (Rules.Link l : rules.links) {
            if (l.kind != Rules.LinkKind.STORY || l.storyRankDay == null) continue;
            for (int r = 1; r < l.storyRankDay.length && r <= 10; r++) {
                if (l.storyRankDay[r] >= 0 && l.storyRankDay[r] <= day) s.rank[l.idx] = r;
            }
        }
        return s;
    }

    /**
     * Steps worth looking at: per kind the longest subtitle and location, plus side steps and hints,
     * plus meetings with guide answers (the longest 参考回答 line and a few more).
     */
    private static List<Plan.Step> picks(Plan plan, Rules rules) {
        Map<String, Plan.Step> chosen = new LinkedHashMap<>();
        Map<String, List<Plan.Step>> byKind = new LinkedHashMap<>();
        for (Plan.Step s : plan.steps) byKind.computeIfAbsent(s.kind, k -> new ArrayList<>()).add(s);
        for (List<Plan.Step> steps : byKind.values()) {
            steps.stream().max(Comparator.comparingInt(s -> len(s.subtitleZh) + len(s.titleZh)))
                    .ifPresent(s -> chosen.put(s.action + "@" + s.slot, s));
            steps.stream().max(Comparator.comparingInt(s -> len(s.locationZh)))
                    .ifPresent(s -> chosen.put(s.action + "@" + s.slot, s));
        }
        int extras = 0;
        for (Plan.Step s : plan.steps) {
            if ((!s.side.isEmpty() || s.hintZh != null) && extras++ < 6) chosen.put(s.action + "@" + s.slot, s);
        }
        List<Plan.Step> answered = new ArrayList<>();
        for (Plan.Step s : plan.steps) if (Dialogue.line(rules, s) != null) answered.add(s);
        answered.stream().max(Comparator.comparingInt(s -> len(Dialogue.line(rules, s))))
                .ifPresent(s -> chosen.put(s.action + "@" + s.slot, s));
        for (int i = 0; i < Math.min(3, answered.size()); i++) chosen.put(answered.get(i).action + "@" + answered.get(i).slot, answered.get(i));
        return new ArrayList<>(chosen.values());
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    @Test
    public void realEngineWordingFitsEverySize() throws Exception {
        Rules rules = loadPack();
        Assume.assumeTrue("rule pack not available", rules != null);
        File dir = RenderSupport.outputDir("real");
        List<String> issues = new ArrayList<>();

        Profile early = new Profile();
        Profile mid = new Profile();
        mid.personaMode = Profile.PersonaMode.ALL;
        SimState midStart = start(rules, dayOf(rules, 10, 22));
        midStart.cash = 1_000;
        Map<String, Plan> plans = new LinkedHashMap<>();
        plans.put("new", Engine.plan(rules, early, start(rules, dayOf(rules, 4, 21)), null, new Engine.Options()));
        plans.put("mid", Engine.plan(rules, mid, midStart, null, new Engine.Options()));

        int rendered = 0;
        int answers = 0;
        for (Map.Entry<String, Plan> e : plans.entrySet()) {
            for (Plan.Step step : picks(e.getValue(), rules)) {
                FakePlannerApi fake = new FakePlannerApi();
                fake.rules = rules;
                fake.plan = e.getValue();
                fake.observed = start(rules, Rules.dayOf(step.slot));
                fake.observed.slot = step.slot;
                WidgetApi.setOverrideForTest(fake);
                app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
                for (int[] size : SIZES) {
                    PlanWidget.Loaded loaded = PlanWidget.load(app, ID);
                    CardModel model = loaded.view.toCard(loaded.rules);
                    View root = RenderSupport.layout(app, PlanWidget.card(app, ID, loaded.view, model, size[0], size[1]), size[0], size[1]);
                    String tag = e.getKey() + "-" + step.kind + "-" + step.slot + "-" + size[0] + "x" + size[1];
                    RenderSupport.save(root, dir, tag);
                    RenderSupport.collectTextIssues(root, tag, issues);
                    RenderSupport.collectOverflow(root, R.id.widget_plate, tag, issues);
                    RenderSupport.collectHeaderOverlap(root, tag, issues);
                    RenderSupport.collectSqueezedLocation(root, tag, issues);
                    RenderSupport.collectWordSplits(root, tag, issues);
                    RenderSupport.collectCutText(root, tag, issues);
                    if (root.findViewById(R.id.widget_dialogue_choices).getVisibility() == View.VISIBLE) answers++;
                    String title = ((TextView) root.findViewById(R.id.widget_task)).getText().toString();
                    assertFalse(tag + " blank title", title.trim().isEmpty());
                    for (int id : new int[]{R.id.widget_task, R.id.widget_tag, R.id.widget_location, R.id.widget_hint,
                            R.id.widget_side, R.id.widget_detail_cue, R.id.widget_dialogue_choices}) {
                        TextView v = root.findViewById(id);
                        if (v.getVisibility() != View.VISIBLE) continue;
                        for (String bad : INTERNAL) {
                            if (v.getText().toString().contains(bad)) issues.add(tag + " shows '" + bad + "': " + v.getText());
                        }
                    }
                    rendered++;
                }
            }
        }
        System.out.println("real-pack widget cards rendered: " + rendered + ", with guide answers: " + answers);
        assertTrue("nothing rendered", rendered > 0);
        assertTrue("no card showed the guide answers of a meeting", answers > 0);
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /**
     * Re-audit: the note's how-to says 「结缘网站的字条」 in full (never 「买字条」), and still fits one line
     * of the 250-wide card, where the location row already says where it is sold.
     */
    @Test
    public void theNoteHowToUsesTheFullNameAndFitsOneLine() throws Exception {
        Rules rules = loadPack();
        Assume.assumeTrue("rule pack not available", rules != null);
        Plan plan = Engine.plan(rules, new Profile(), start(rules, dayOf(rules, 4, 21)), null, new Engine.Options());
        int checked = 0;
        for (Plan.Step step : plan.steps) {
            if (!"note".equals(step.kind) && (step.action == null || !step.action.startsWith("note:"))) continue;
            FakePlannerApi fake = new FakePlannerApi();
            fake.rules = rules;
            fake.plan = plan;
            fake.observed = start(rules, Rules.dayOf(step.slot));
            fake.observed.slot = step.slot;
            WidgetApi.setOverrideForTest(fake);
            app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
            for (int[] size : new int[][]{{250, 160}, {320, 200}}) {
                PlanWidget.Loaded loaded = PlanWidget.load(app, ID);
                CardModel model = loaded.view.toCard(loaded.rules);
                View root = RenderSupport.layout(app, PlanWidget.card(app, ID, loaded.view, model, size[0], size[1]), size[0], size[1]);
                String tag = "note-" + step.slot + "-" + size[0] + "x" + size[1];
                TextView cue = root.findViewById(R.id.widget_detail_cue);
                if (cue.getVisibility() != View.VISIBLE || !CardText.plain(cue.getText().toString()).equals(StepCopy.NOTE_HOW)) continue;
                RenderSupport.save(root, RenderSupport.outputDir("real"), tag);
                assertEquals(tag + " ends in …", 0, cue.getLayout().getEllipsisCount(cue.getLineCount() - 1));
                assertTrue(CardText.plain(((TextView) root.findViewById(R.id.widget_location)).getText().toString()).contains("放纵俱乐部"));
                checked++;
            }
            if (checked >= 2) break;
        }
        assertTrue("no note card showed the how-to", checked > 0);
        assertTrue(StepCopy.NOTE_HOW.contains("结缘网站的字条"));
    }
}
