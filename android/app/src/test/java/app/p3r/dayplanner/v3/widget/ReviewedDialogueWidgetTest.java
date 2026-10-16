package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
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
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;
import app.p3r.dayplanner.v3.service.PlannerApi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Approved source wording reaches the actual widget, including scene position and route safety. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReviewedDialogueWidgetTest {
    private static final int ID = 83;
    private static final int[][] SIZES = {{320, 240}, {180, 150}};
    private static Rules pack;
    private Context app;

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        RuntimeEnvironment.setFontScale(1f);
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        if (pack == null) {
            for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json",
                    "app/src/main/assets/p3r/p3r_rules.json", "android/app/src/main/assets/p3r/p3r_rules.json"}) {
                File file = new File(candidate);
                if (file.isFile()) {
                    pack = RulesLoader.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                    break;
                }
            }
        }
        assertNotNull("the shipped rules are part of this integration test", pack);
    }

    @After
    public void tearDown() {
        WidgetApi.setOverrideForTest(null);
        RuntimeEnvironment.setFontScale(1f);
    }

    /** No search: a canned rank-up specifies exactly which real-pack scene the widget should show. */
    private PlanView scene(String linkId, int rank, Profile.Route route) {
        Rules.Link link = pack.link(linkId);
        assertNotNull(linkId, link);
        FakePlannerApi fake = new FakePlannerApi();
        fake.rules = pack;
        int slot = Rules.slot(GameCalendar.ordinal(2009, 11, 22), false);
        Plan.Step step = FakePlannerApi.rank(FakePlannerApi.step(slot,
                "meet:" + linkId, "social", link.labelZh, "RANK " + (rank - 1) + " → " + rank,
                link.locationZh, null), rank - 1, rank);
        step.entity = linkId;
        step.predicted.certain = true;
        fake.plan.planId = "reviewed-dialogue-" + linkId + "-" + rank;
        fake.plan.fromSlot = slot;
        fake.plan.toSlot = slot;
        fake.plan.steps.clear();
        fake.plan.steps.add(step);
        fake.observed = new SimState();
        fake.observed.slot = slot;
        fake.observed.rank[link.idx] = rank - 1;
        PlannerApi.Snapshot snapshot = fake.snapshot();
        snapshot.profile.route[link.idx] = route;
        PlanView view = PlanView.resolve(snapshot, -1, null, 0L);
        assertEquals(PlanView.Mode.ITEM, view.mode);
        return view;
    }

    private static String plain(String text) {
        return CardText.plain(text).replaceAll("\\s+", "");
    }

    private CardModel card(PlanView view) {
        CardModel model = view.toCard(pack);
        assertEquals("only this meeting's answers, no unrelated reminders", 1, model.answers.size());
        return model;
    }

    /** Both launchers get a visible, unclipped answers row; save the actual RemoteViews for review. */
    private String render(PlanView view, String name, int[] size) throws Exception {
        CardModel model = card(view);
        View root = RenderSupport.layout(app,
                PlanWidget.card(app, ID, view, model, size[0], size[1]), size[0], size[1]);
        String tag = name + "-" + size[0] + "x" + size[1];
        RenderSupport.save(root, RenderSupport.outputDir("reviewed"), tag);
        TextView row = root.findViewById(R.id.widget_dialogue_choices);
        assertEquals(tag + " must keep its answer row", View.VISIBLE, row.getVisibility());
        assertFalse(tag + " must not ellipsize its answers", RenderSupport.ellipsized(row));
        String text = plain(row.getText().toString());
        assertFalse(tag + " must not truncate inside an answer: " + text, text.contains("…"));
        List<String> issues = new ArrayList<>();
        RenderSupport.collectTextIssues(root, tag, issues);
        RenderSupport.collectCutText(root, tag, issues);
        RenderSupport.collectAnswerSplits(row, tag, issues);
        assertTrue(String.join("\n", issues), issues.isEmpty());
        return text;
    }

    private static void inOrder(String text, String first, String second) {
        int a = text.indexOf(first);
        int b = text.indexOf(second);
        assertTrue(text + " must keep 「" + first + "」 before 「" + second + "」", a >= 0 && b > a);
    }

    @Test
    public void fuukaRankThreeShowsTheReviewedWordsInsteadOfTheGenericAdvice() throws Exception {
        PlanView view = scene("priestess", 3, Profile.Route.FRIEND);
        assertEquals("答：就啪的一撒/不用心急", plain(card(view).answers.get(0).text()));
        for (int[] size : SIZES) {
            String text = render(view, "fuuka-r3", size);
            assertTrue(text, text.contains("就啪的一撒"));
            assertFalse(text, text.contains("让对方高兴"));
            if (size[0] == 320) {
                assertEquals("答：就啪的一撒/不用心急", text);
                inOrder(text, "就啪的一撒", "不用心急");
            }
        }
    }

    @Test
    public void yukariRankFiveKeepsTheLastQuestionQualifier() throws Exception {
        PlanView view = scene("lovers", 5, Profile.Route.FRIEND);
        String model = plain(card(view).answers.get(0).text());
        assertTrue(model, model.contains("抱歉"));
        assertTrue(model, model.contains("最后一问"));
        for (int[] size : SIZES) {
            String text = render(view, "yukari-r5", size);
            assertTrue(text, text.contains("抱歉"));
            assertTrue(text, text.contains("最后一问"));
        }
    }

    @Test
    public void fuukaFriendRouteShowsTheCompleteFriendshipAnswers() throws Exception {
        PlanView view = scene("priestess", 9, Profile.Route.FRIEND);
        String model = plain(card(view).answers.get(0).text());
        inOrder(model, "当然", "好啊");
        assertFalse(model, model.contains("我喜欢风花"));
        for (int[] size : SIZES) {
            String text = render(view, "fuuka-r9-friend", size);
            assertTrue(text, text.contains("当然"));
            assertFalse(text, text.contains("我喜欢风花"));
            if (size[0] == 320) inOrder(text, "当然", "好啊");
        }
    }

    @Test
    public void fuukaRankSevenKeepsBothAnswersWhenThePlayerWantsRomance() throws Exception {
        PlanView view = scene("priestess", 7, Profile.Route.ROMANCE);
        inOrder(plain(card(view).answers.get(0).text()), "太过分了", "真的是这样吗");
        for (int[] size : SIZES) {
            String text = render(view, "fuuka-r7-romance", size);
            assertTrue(text, text.contains("太过分了"));
            if (size[0] == 320) {
                inOrder(text, "太过分了", "真的是这样吗");
                assertFalse(text, text.contains("等2项"));
                assertFalse(text, text.contains("点开看看"));
            }
        }
    }

    @Test
    public void completedRoutesKeepRecognizableAnswersAtSmallSizes() throws Exception {
        Object[][] cases = {
                {"priestess", 2, Profile.Route.FRIEND, "没问题", "首问"},
                {"strength", 8, Profile.Route.FRIEND, "时髦风格", null},
                {"aeon", 9, Profile.Route.FRIEND, "是啊", "早就是挚友了"},
                {"justice", 9, Profile.Route.FRIEND, "她会帮你的", "重要的学妹"},
                {"justice", 9, Profile.Route.ROMANCE, "她会帮你的", "心意和你一样"},
                {"empress", 8, Profile.Route.FRIEND, "欢迎你来倾诉", null}
        };
        for (Object[] item : cases) {
            String id = (String) item[0];
            PlanView view = scene(id, (Integer) item[1], (Profile.Route) item[2]);
            String model = plain(card(view).answers.get(0).text());
            assertTrue(model, model.contains((String) item[3]));
            if (item[4] != null) assertTrue(model, model.contains((String) item[4]));
            for (float scale : new float[]{1f, 1.15f}) {
                RuntimeEnvironment.setFontScale(scale);
                String text = render(view, id + "-r" + item[1] + "-" + item[2] + "-" + scale,
                        new int[]{180, 130});
                assertTrue(text, text.contains((String) item[3]));
                assertFalse(text, text.contains("译意"));
            }
        }
    }

}
