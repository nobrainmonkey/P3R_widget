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
    private static final int[][] SIZES = {{250, 140}, {320, 160}, {360, 180}, {400, 180}, {320, 200}, {320, 240}};
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
     * Every way the ladder may draw {@code line}: label long or short, 「或者」 or 「或」, each choice of two
     * alternatives as its first, a class answer with or without its 「（第 n 项）」, cut to 「等 N 项」,
     * caution only.
     */
    private static Set<String> forms(CardModel.AnswerLine line) {
        Set<String> out = new LinkedHashSet<>();
        for (CardModel.AnswerLine form : new LinkedHashSet<>(Arrays.asList(line, line.withoutPosition(), line.firstAlternatives()))) {
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
     * {@link #forms} of its model line, in order (a 课堂/考试 answer may share the line before it after
     * 「 · 」, or sit on the title's line, {@link R.id#widget_title_aside}). A line is cut to 「等 N 项」 only
     * where the choices it leaves out are wider than 「等 N 项」. Both kinds of answers stay at every size
     * (docs/DESIGN_v3.md §18): the step's own line with a choice in it (its note, for a line without
     * choices) and every 课堂/考试 answer; only the side steps' picks may go, the least needed first
     * ({@link CardRenderer#dropOrder}), and a card taller than the shortest (130dp high: two lines of text
     * under the plate) keeps every line unless the row already fills {@link CardRenderer#MAX_ANSWER_LINES}
     * lines. The side steps' picks go with or before the side row and never show without it (after 3.1.0:
     * 「顺便选：有话想和你说」 alone read as the answer to 「命运 · 平贺庆介」); a card whose only answers are picks
     * may have no row. Returns whether the row shows.
     */
    private static boolean checkRow(View root, CardModel model, String tag, boolean shortest, List<String> issues) {
        checkTitle(root, model, tag, issues);
        TextView row = root.findViewById(R.id.widget_dialogue_choices);
        boolean sideRow = root.findViewById(R.id.widget_side).getVisibility() == View.VISIBLE;
        // The lines a card must keep: every line but the side steps' picks.
        List<CardModel.AnswerLine> needed = CardRenderer.withoutSidePicks(model.answers);
        if (row.getVisibility() != View.VISIBLE) {
            if (needed.isEmpty()) return false;
            issues.add(tag + " hides its answers " + model.answers + " (title '"
                    + text(root, R.id.widget_task) + "')");
            return false;
        }
        boolean allLines = !shortest && row.getLineCount() < CardRenderer.MAX_ANSWER_LINES;
        RenderSupport.collectAnswerSplits(row, tag, issues);
        List<String> drawn = segments(row.getText().toString());
        List<CardModel.AnswerLine> shown = matched(model.answers, drawn);
        if (shown == null) {
            issues.add(tag + " draws " + drawn + ", not the model's lines in order (model " + model.answers + ")");
            return true;
        }
        // A 课堂/考试 answer beside the title: one of the card's, whole, not in the row as well.
        TextView asideView = root.findViewById(R.id.widget_title_aside);
        CardModel.AnswerLine beside = null;
        if (asideView.getVisibility() == View.VISIBLE) {
            String aside = text(root, R.id.widget_title_aside);
            for (CardModel.AnswerLine line : model.answers) {
                if (beside == null && CardRenderer.certain(line) && !shown.contains(line)
                        && canon(line.withoutPosition().text()).equals(canon(aside))) {
                    beside = line;
                }
            }
            if (beside == null) issues.add(tag + " shows '" + aside + "' beside its title, no 课堂/考试 answer of " + model.answers);
            if (RenderSupport.ellipsized(asideView) || asideView.getLineCount() != 1) {
                issues.add(tag + " cuts or wraps '" + aside + "' beside its title");
            }
        }
        List<CardModel.AnswerLine> kept = new ArrayList<>(shown);
        if (beside != null) kept.add(beside);
        for (CardModel.AnswerLine line : shown) {
            if (CardRenderer.sidePick(line) && !sideRow) issues.add(tag + " shows '" + line.text() + "' without its side row");
        }
        int keptNeeded = CardRenderer.withoutSidePicks(kept).size();
        for (int i = 0; i < drawn.size(); i++) {
            String why = needlessCut(row, shown.get(i), drawn.get(i));
            if (why != null) issues.add(tag + " " + why);
        }
        // Both kinds stay: the step's own answers with a choice in them, and every 课堂/考试 answer.
        CardModel.AnswerLine own = CardRenderer.own(model.answers);
        boolean anyCertain = false;
        for (CardModel.AnswerLine line : model.answers) anyCertain |= CardRenderer.certain(line);
        if (own != null && anyCertain) {
            int at = shown.indexOf(own);
            if (at < 0 || !showsAnAnswer(own, drawn.get(at))) {
                issues.add(tag + " loses the step's own answers " + own + " beside " + kinds(drawn, beside));
            }
            for (CardModel.AnswerLine line : model.answers) {
                if (CardRenderer.certain(line) && !kept.contains(line)) {
                    issues.add(tag + " drops '" + line + "' beside " + kinds(drawn, beside));
                }
            }
            if (allLines && keptNeeded < needed.size()) {
                issues.add(tag + " keeps " + keptNeeded + " of " + needed.size() + " lines in a row of "
                        + row.getLineCount() + ": " + kinds(drawn, beside));
            }
        } else if (allLines && keptNeeded < needed.size()) {
            issues.add(tag + " draws " + keptNeeded + " of " + needed.size() + " lines: " + kinds(drawn, beside)
                    + " (model " + model.answers + ")");
        }
        // Lines go the least needed first (CardRenderer.dropOrder): a 课堂/考试 line never goes while a
        // side pick or a 「要是升级了」 line (the meeting may not rank up) stays.
        for (CardModel.AnswerLine line : model.answers) {
            if (!CardRenderer.certain(line) || kept.contains(line)) continue;
            for (CardModel.AnswerLine other : kept) {
                if (!CardRenderer.certain(other) && (other.conditional || model.answers.indexOf(other) > 0)) {
                    issues.add(tag + " drops the " + line.label + " line but keeps '" + other.text() + "'");
                }
            }
        }
        // Muted: a meeting that may not rank up, never a 课堂/考试 answer that shares its line.
        CharSequence styled = row.getText();
        boolean mutedFirst = false;
        if (styled instanceof Spanned) {
            Spanned spanned = (Spanned) styled;
            for (ForegroundColorSpan span : spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class)) {
                String part = CardText.plain(spanned.subSequence(spanned.getSpanStart(span), spanned.getSpanEnd(span)).toString());
                if (part.contains(CardModel.CLASS_LABEL) || part.contains(CardModel.EXAM_LABEL)) {
                    issues.add(tag + " mutes '" + part + "'");
                }
                mutedFirst |= spanned.getSpanStart(span) == 0;
            }
        }
        if (!shown.isEmpty() && shown.get(0).conditional && !mutedFirst) issues.add(tag + " does not mute '" + drawn.get(0) + "'");
        if (beside != null) besides.add(tag);
        if (!shortest && keptNeeded < needed.size()) dropped.add(tag);
        if (kept.size() < model.answers.size() && keptNeeded == needed.size()) picksLeftOut.add(tag + (sideRow ? " (side row shown)" : ""));
        return true;
    }

    /**
     * The drawn answers row in its model lines: a paragraph per line, a 课堂/考试 answer that shares the
     * line before it (after 「 · 」, or after the line break that replaced it) split off.
     */
    private static List<String> segments(String shaped) {
        List<String> out = new ArrayList<>();
        for (String paragraph : shaped.split(CardText.ANSWER_LINE_BREAK, -1)) {
            String plain = CardText.plain(paragraph);
            for (String part : plain.split("(?<=.)(?=" + CardModel.CLASS_LABEL + "|" + CardModel.EXAM_LABEL + ")")) out.add(part);
        }
        return out;
    }

    /** The drawn row, with what sits beside the title, for reports. */
    private static String kinds(List<String> drawn, CardModel.AnswerLine beside) {
        return String.join(" | ", drawn).replace("\n", " ") + (beside == null ? "" : " [beside the title: " + beside + "]");
    }

    /**
     * Whether a drawn form of the step's own line {@code line} gives an answer: one of its choices (whole,
     * 「或」, or the first of two alternatives), or its note where it has no choices; not the count alone
     * (「答：共 2 项，点开看看」) or a caution in place of its choices.
     */
    private static boolean showsAnAnswer(CardModel.AnswerLine line, String drawn) {
        if (line.choices.isEmpty()) return true; // its note, whole or as the shorter 「别选表白」
        String first = line.choices.get(0);
        for (String form : new String[]{first, CardText.shortOr(first), line.firstAlternatives().choices.get(0)}) {
            if (canon(drawn).contains(canon(form))) return true;
        }
        return false;
    }


    /** Regression: SDK 26 wrapped compact route notes to a lone final character on these cards. */
    @Test
    public void compactFriendshipNotesFromRealPlansStayWhole() throws Exception {
        RuntimeEnvironment.setFontScale(1f);
        List<Plan> plans = realPlans();
        List<String> issues = new ArrayList<>();
        for (int index = 0; index < plans.size(); index++) {
            for (int slot : new int[]{494, 568, 594}) {
                FakePlannerApi fake = on(plans.get(index), slot);
                CardModel model = model(fake);
                View root = render(fake, 180, 150);
                String tag = "friend-note-plan" + index + "-" + slot;
                RenderSupport.save(root, RenderSupport.outputDir("reviewed"), tag);
                System.out.println(tag + " " + model.answers);
                RenderSupport.collectTextIssues(root, tag, issues);
                RenderSupport.collectCutText(root, tag, issues);
                RenderSupport.collectAnswerSplits(root.findViewById(R.id.widget_dialogue_choices), tag, issues);
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /** Short friendship annotations keep the real scene answer beside a sourced classroom answer. */
    @Test
    @Config(sdk = {26, 31, 34})
    public void friendshipNotesKeepTheirOwnAnswerBesideClassWithoutPlanning() throws Exception {
        assertNotNull("the shipped pack is required", pack);
        Object[][] scenes = {{"lovers", 7, "class.11-30", "好像不错啊", "", Profile.Route.FRIEND},
                {"empress", 9, "class.12-11", "放心了", "婉拒告白", Profile.Route.FRIEND},
                {"lovers", 5, "class.11-12", "我是她朋友", "", Profile.Route.FRIEND},
                {"lovers", 5, "class.11-12", "她男朋友", "", Profile.Route.ROMANCE},
                {"priestess", 8, "class.11-30", "帮你吃拉面", "已走友情", Profile.Route.FRIEND, true},
                {"priestess", 9, "class.01-18", "当然", "已走友情", Profile.Route.FRIEND},
                {"priestess", 9, "class.01-18", "当然", "已走友情", Profile.Route.FRIEND, true}};
        List<String> issues = new ArrayList<>();
        for (Object[] scene : scenes) {
            String link = (String) scene[0];
            int rank = (Integer) scene[1];
            Rules.Reminder classroom = null;
            for (Rules.Reminder reminder : pack.reminders.items) {
                if (scene[2].equals(reminder.id)) classroom = reminder;
            }
            assertNotNull(scene[2].toString(), classroom);
            boolean conditional = scene.length > 6 && Boolean.TRUE.equals(scene[6]);
            FakePlannerApi fake = scene(meeting(link, rank - 1, conditional ? rank - 1 : rank, !conditional));
            int slot = Rules.slot(classroom.day, false);
            fake.observed.slot = slot;
            fake.observed.rank[pack.link(link).idx] = rank - 1;
            fake.observed.pts[pack.link(link).idx] = 0;
            fake.observed.ready[pack.link(link).idx] = true;
            fake.plan.fromSlot = slot;
            fake.plan.toSlot = slot;
            fake.plan.steps.get(0).slot = slot;
            ReminderFixtures.with(fake.plan, ReminderFixtures.classAnswer(classroom.day, classroom.answerZh, classroom.option));
            app.p3r.dayplanner.v3.service.PlannerApi.Snapshot snapshot = fake.snapshot();
            snapshot.profile.route[pack.link(link).idx] = (Profile.Route) scene[5];
            PlanView plan = PlanView.resolve(snapshot, -1, null, 0L);
            CardModel beforeFit = plan.toCard(pack);
            assertTrue(link, ownAndClassLines(beforeFit));
            CardModel.AnswerLine own = CardRenderer.own(beforeFit.answers);
            assertNotNull(own);
            assertEquals(link, conditional, own.conditional);
            assertTrue(own.text(), own.choices.contains(scene[3]));
            assertEquals(scene[4], own.note == null ? "" : own.note);
            if ("priestess".equals(link) && rank == 9) {
                assertEquals(Arrays.asList("当然", "好啊"), own.choices);
                CardModel.AnswerLine classLine = beforeFit.answers.get(beforeFit.answers.size() - 1);
                assertEquals("喀耳刻", classroom.answerZh);
                assertEquals(Arrays.asList("喀耳刻"), classLine.withoutPosition().choices);
            }
            for (float scale : new float[]{1f, 1.15f}) {
                RuntimeEnvironment.setFontScale(scale);
                for (int[] size : new int[][]{{250, 140}, {320, 160}, {360, 180}, {400, 180}}) {
                    CardModel model = plan.toCard(pack);
                    View root = RenderSupport.layout(app,
                            PlanWidget.card(app, ID, plan, model, size[0], size[1]), size[0], size[1]);
                    String tag = "friend-class-" + link + "-r" + rank + "-" + scene[5]
                            + (conditional ? "-conditional" : "")
                            + "-" + size[0] + "x" + size[1] + "-font" + scale;
                    RenderSupport.collectTextIssues(root, tag, issues);
                    RenderSupport.collectCutText(root, tag, issues);
                    RenderSupport.collectWordSplits(root, tag, issues);
                    checkRow(root, model, tag, size[1] < 150, issues);
                    if ("priestess".equals(link) && rank == 9) {
                        assertEquals(tag, View.VISIBLE, root.findViewById(R.id.widget_dialogue_choices).getVisibility());
                        String drawn = text(root, R.id.widget_dialogue_choices);
                        assertTrue(tag + ": " + drawn, drawn.contains("当然"));
                        assertTrue(tag + ": " + drawn, drawn.contains("好啊"));
                        TextView aside = root.findViewById(R.id.widget_title_aside);
                        String classroomDrawn = drawn + (aside.getVisibility() == View.VISIBLE
                                ? text(root, R.id.widget_title_aside) : "");
                        assertTrue(tag + ": " + classroomDrawn, classroomDrawn.contains("喀耳刻"));
                    }
                    if (scale == 1f || conditional || ("priestess".equals(link) && rank == 9 && size[0] == 250 && size[1] == 140)) {
                        RenderSupport.save(root, RenderSupport.outputDir("reviewed"), tag);
                    }
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /** Cards that showed a 课堂/考试 answer beside their title, for the report. */
    private static final List<String> besides = new ArrayList<>();

    /** Taller cards (150dp or more) that left a line out, for the report. */
    private static final List<String> dropped = new ArrayList<>();
    /** Cards that left only side picks out (they go with or before the side row), for the report. */
    private static final List<String> picksLeftOut = new ArrayList<>();
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
    private static List<CardModel.AnswerLine> matched(List<CardModel.AnswerLine> lines, List<String> drawn) {
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
     * Spec test 1: the supported 4×2/5×2 sizes {250x140, 320x160, 360x180, 400x180}, plus taller
     * {320x200, 320x240} cards, × font {1.0, 1.15} × SDK {26, 31} over every
     * card with a distinct answers row of three real plans (new game; 10/22 with every Persona; 7/1
     * with every link underway). Every card whose step has answers of its own and a 课堂/考试 answer keeps
     * both kinds at every size ({@link #checkRow}; until 3.1.0 the row left 「课堂：…」 out where the
     * meeting's answers filled two lines, and a 130dp card showed one kind only); renders of those cards
     * at the four shorter sizes go to answers/both/.
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
        File both = RenderSupport.outputDir("answers/both");
        int bothCards = 0;
        int bothRenders = 0;
        for (float scale : new float[]{1f, 1.15f}) {
            RuntimeEnvironment.setFontScale(scale);
            for (Object[] card : cards.values()) {
                FakePlannerApi fake = on((Plan) card[0], (Integer) card[1]);
                CardModel model = model(fake);
                boolean bothKinds = bothKinds(model);
                if (bothKinds && scale == 1f) bothCards++;
                for (int[] size : SIZES) {
                    View root = render(fake, size[0], size[1]);
                    String tag = card[1] + "-" + size[0] + "x" + size[1] + "@" + scale;
                    if (!checkRow(root, model, tag, size[1] < 150, issues)) hidden++;
                    if (bothKinds) {
                        bothRenders++;
                        // Named by plan and slot: two plans may have a card with both kinds on the same slot.
                        String name = "plan" + plans.indexOf((Plan) card[0]) + "-" + card[1] + "-" + size[0] + "x" + size[1]
                                + (scale > 1 ? "-font115" : "");
                        if (size[1] < 200) RenderSupport.save(root, both, name);
                    }
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
        System.out.println("cards with both kinds: " + bothCards + " (" + bothRenders + " renders), 课堂/考试 beside the title: "
                + besides.size() + " " + besides);
        System.out.println("short titles shown: " + shortened.size() + " " + shortened);
        System.out.println("side picks left out: " + picksLeftOut.size() + " " + picksLeftOut);
        System.out.println("count only: " + counted.size() + "\n" + String.join("\n", counted));
        if (!issues.isEmpty()) System.out.println("ISSUES\n" + String.join("\n", issues));
        assertTrue(String.join("\n", issues), issues.isEmpty());
        assertTrue("cards with both kinds: " + bothCards, bothCards >= 10);
        assertTrue("no taller card leaves a line out: " + dropped, dropped.isEmpty());
    }

    /** The card's step has answers of its own and the card a 课堂/考试 answer. */
    private static boolean bothKinds(CardModel model) {
        boolean certain = false;
        for (CardModel.AnswerLine line : model.answers) certain |= CardRenderer.certain(line);
        return certain && CardRenderer.own(model.answers) != null;
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
     * The minimum 4×2 and nominal 5×2 cards keep the meeting title, its real scene choices and a
     * sourced classroom answer together. Independent one-step scenes keep this visual regression
     * stable when planner priorities move a meeting to a different date: 教皇 RANK 4 has its long
     * pair beside 7/10「卡巴拉」, and 战车 RANK 10 has its pair beside 10/19「印度」.
     */
    @Test
    public void theShortestCardsKeepTheirTitleBesideTheAnswers() throws Exception {
        assertNotNull("the shipped rule pack is required", pack);
        File dir = RenderSupport.outputDir("answers");
        Object[][] wanted = {
                {"0710-hierophant", "hierophant", 4, "class.07-10", "卡巴拉",
                        Arrays.asList("我也去看看吧或者我在这里等她", "真担心啊")},
                {"1019-chariot", "chariot", 10, "class.10-19", "印度",
                        Arrays.asList("明年的选拔赛呢？", "加油")}};
        List<String> issues = new ArrayList<>();
        for (Object[] w : wanted) {
            String link = (String) w[1];
            int rank = (Integer) w[2];
            Rules.Reminder classroom = null;
            for (Rules.Reminder reminder : pack.reminders.items) {
                if (w[3].equals(reminder.id)) classroom = reminder;
            }
            assertNotNull(w[3].toString(), classroom);
            assertEquals(Plan.Reminder.CLASS, classroom.kind);
            assertEquals(w[4], classroom.answerZh);
            FakePlannerApi fake = scene(meeting(link, rank - 1, rank, true));
            int slot = Rules.slot(classroom.day, false);
            fake.observed.slot = slot;
            fake.observed.rank[pack.link(link).idx] = rank - 1;
            fake.observed.ready[pack.link(link).idx] = true;
            fake.plan.fromSlot = slot;
            fake.plan.toSlot = slot;
            fake.plan.steps.get(0).slot = slot;
            ReminderFixtures.with(fake.plan, ReminderFixtures.classAnswer(classroom.day, classroom.answerZh, classroom.option));
            assertEquals("the fixture is one explicit meeting", 1, fake.plan.steps.size());
            CardModel beforeFit = model(fake);
            assertTrue(w[0] + " has its own line and a class line", ownAndClassLines(beforeFit));
            CardModel.AnswerLine own = CardRenderer.own(beforeFit.answers);
            assertNotNull(own);
            assertFalse(own.conditional);
            assertEquals(w[0] + " keeps the real scene's two choices", w[5], own.choices);
            CardModel.AnswerLine classLine = beforeFit.answers.get(beforeFit.answers.size() - 1);
            assertEquals(Arrays.asList(classroom.answerZh + CardModel.AnswerLine.position(classroom.option)), classLine.choices);
            for (float scale : new float[]{1f, 1.15f}) {
                RuntimeEnvironment.setFontScale(scale);
                for (int[] size : new int[][]{{250, 140}, {320, 160}, {360, 180}}) {
                    CardModel model = model(fake);
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
                    float density = app.getResources().getDisplayMetrics().scaledDensity;
                    assertTrue(tag + " keeps the title readable", title.getTextSize() + 1 >= CardRenderer.MIN_TITLE_SP * density);
                    assertTrue(tag + " keeps the answers readable", row.getTextSize() + 1 >= CardRenderer.ANSWERS_SMALL_SP * density);
                    TextView aside = root.findViewById(R.id.widget_title_aside);
                    String beside = aside.getVisibility() == View.VISIBLE ? " [" + text(root, R.id.widget_title_aside) + "]" : "";
                    System.out.println(tag + ": " + RenderSupport.lines(title) + beside + " | "
                            + text(root, R.id.widget_dialogue_choices).replace("\n", " / "));
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

    /** Actual friendship answers replace old avoidance-only rows and the last unmapped scenes. */
    @Test
    public void routesNoDataAndFreeChoices() {
        Assume.assumeTrue("rule pack not available", pack != null);
        String fuuka = text(render(scene(meeting("priestess", 8, 9, false)), 320, 200), R.id.widget_dialogue_choices);
        assertTrue(fuuka, fuuka.contains("当然 / 好啊"));
        assertFalse(fuuka, fuuka.contains("我喜欢风花"));
        assertEquals("答：时髦风格", text(render(scene(meeting("strength", 7, 8, false)), 320, 200), R.id.widget_dialogue_choices));
        assertEquals("答：是啊 / 早就是挚友了", text(render(scene(meeting("aeon", 8, 9, false)), 320, 200), R.id.widget_dialogue_choices));
        assertEquals("答：你很努力啊", text(render(scene(meeting("emperor", 3, 4, false)), 320, 200), R.id.widget_dialogue_choices));
        assertEquals("答：没问题（首问） / 没问题（末问）", text(render(scene(meeting("priestess", 1, 2, false)), 320, 200), R.id.widget_dialogue_choices));
        assertEquals("答：怎么回答都可以", text(render(scene(meeting("moon", 9, 10, false)), 320, 200), R.id.widget_dialogue_choices));
        // The full app line is read out.
        CardModel justice = model(scene(meeting("justice", 4, 5, false)));
        assertTrue(justice.taskDescription, justice.taskDescription.contains(
                "回答：随便问吧 / 产生恋爱感情了 / 随时欢迎千寻找我倾诉 / 握住她的手"));
        assertTrue(justice.taskDescription, justice.taskDescription.contains("亲上去"));
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
     * After 3.1.0: a side step's pick belongs to its side row (「顺便：和伏见千寻搭话（第 1 次）」). It goes with
     * or before that row and never shows without it, where it reads as the answer to the card's title: 10/27
     * 命运 · 平贺庆介 (an unlock with no answers of its own) showed only 「顺便选：有话想和你说」 below 320x200,
     * and 5/2 力量 at 180x150 cut its own line to 「答：发生什么了吗？ 等 3 项」 beside 「顺便选：一起出去玩吧」.
     * Renders under answers/ as sidepick-*.
     */
    @Test
    public void aSidePickNeverShowsWithoutItsSideRow() throws Exception {
        Assume.assumeTrue("rule pack not available", pack != null);
        File dir = RenderSupport.outputDir("answers");
        String sideZh = "顺便：和伏见千寻搭话（第 1 次）";
        String own = "答：发生什么了吗？ / 那也没办法 / 你说得对";
        List<String> issues = new ArrayList<>();
        int picksShown = 0;
        for (float scale : new float[]{1f, 1.15f}) {
            RuntimeEnvironment.setFontScale(scale);
            for (int[] size : SIZES) {
                Plan.Step strength = FakePlannerApi.side(meeting("strength", 1, 2, false), "unlock:justice:0", sideZh);
                Plan.Step fortune = FakePlannerApi.side(FakePlannerApi.step(0, "unlock:fortune:0", "unlock", "命运 · 平贺庆介",
                        "去美术教室加入美术社", "月光馆学园 · 美术教室", "完成后命运社群就解锁了"), "unlock:justice:0", sideZh);
                for (Plan.Step step : new Plan.Step[]{strength, fortune}) {
                    FakePlannerApi fake = scene(step);
                    CardModel model = model(fake);
                    assertEquals(step.action + " carries the pick", "顺便选：有话想和你说",
                            model.answers.get(model.answers.size() - 1).text());
                    View root = render(fake, size[0], size[1]);
                    String tag = "sidepick-" + step.action.replace(':', '_') + "-" + size[0] + "x" + size[1]
                            + (scale > 1 ? "-font115" : "");
                    RenderSupport.save(root, dir, tag);
                    checkRow(root, model, tag, size[1] < 150, issues);
                    TextView row = root.findViewById(R.id.widget_dialogue_choices);
                    String drawn = row.getVisibility() == View.VISIBLE ? text(root, R.id.widget_dialogue_choices) : "";
                    boolean sideRow = root.findViewById(R.id.widget_side).getVisibility() == View.VISIBLE;
                    if (drawn.contains(DialogueHint.SIDE_PREFIX)) {
                        picksShown++;
                        if (!sideRow) issues.add(tag + " shows its pick without the side row: " + drawn);
                    }
                    // The meeting's own line stays whole where it fits beside the title without the pick.
                    if (step == strength && size[1] >= 150 && scale == 1f && !canon(drawn).startsWith(canon(own))) {
                        issues.add(tag + " draws '" + drawn.replace("\n", " / ") + "', not its own line whole");
                    }
                    System.out.println(tag + ": " + RenderSupport.titleText(root.findViewById(R.id.widget_task))
                            + (sideRow ? " [" + text(root, R.id.widget_side) + "]" : "") + " | " + drawn.replace("\n", " / "));
                }
            }
        }
        RuntimeEnvironment.setFontScale(1f);
        assertTrue(String.join("\n", issues), issues.isEmpty());
        assertTrue("the pick shows where its side row does", picksShown > 0);
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
