package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

/** Mid-game dorm progress corrections, confirmation and stable draft persistence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DormProgressUiTest extends UiTestBase {
    private static final int FIRST = 4;
    private static final int SECOND = 5;

    private Rules dormRules() {
        Rules rules = Fixtures.rules();
        rules.activities = Arrays.copyOf(rules.activities, 6);
        rules.activities[FIRST] = dormActivity(FIRST, "dorm_junpei_read", "与顺平阅读");
        rules.activities[SECOND] = dormActivity(SECOND, "dorm_junpei_garden", "与顺平园艺");
        rules.activities[SECOND].requiresPlantedCrop = true;
        return rules;
    }

    private Rules.Activity dormActivity(int index, String id, String title) {
        Rules.Activity activity = new Rules.Activity();
        activity.idx = index;
        activity.id = id;
        activity.zh = title;
        activity.dormPartnerZh = "伊织顺平";
        activity.eve = true;
        activity.fromDay = Fixtures.day(6, 16);
        activity.locationZh = "宿舍";
        return activity;
    }

    @Test
    public void correctionsDoNotGrantStatsAndKeepUntouchedCounts() {
        Rules rules = dormRules();
        SimState observed = Fixtures.observedMid(rules);
        observed.used[0] = 12;
        observed.used[FIRST] = 5; // Repeated stat activities can continue after the trait milestone.
        StateDraft draft = new StateDraft(StateDraft.Mode.EDIT, rules, observed, new Profile(), null, 1);
        assertEquals(3, draft.dormCount(FIRST));
        assertEquals(5, draft.build().used[FIRST]);
        draft.setDormCount(SECOND, 3);
        SimState after = draft.build();
        assertEquals(12, after.used[0]);
        assertEquals(5, after.used[FIRST]);
        assertEquals(3, after.used[SECOND]);
        assertArrayEquals(observed.statPts, after.statPts);
        assertEquals("特性已强化", draft.dormMilestone("伊织顺平"));
        assertEquals(0, observed.used[SECOND]);
    }

    @Test
    public void oldShortStateArraysCanBeFilledWithoutLosingOtherActivities() {
        Rules rules = dormRules();
        SimState old = Fixtures.observedMid(Fixtures.rules());
        old.used[0] = 7;
        StateDraft draft = new StateDraft(StateDraft.Mode.EDIT, rules, old, new Profile(), null, 1);
        assertEquals(0, draft.dormCount(FIRST));
        draft.setDormCount(FIRST, 2);
        assertEquals(6, draft.build().used.length);
        assertEquals(7, draft.build().used[0]);
        assertEquals(2, draft.build().used[FIRST]);
    }

    @Test
    public void forecastCountsNeedThePlayersSyncConfirmation() {
        Rules rules = dormRules();
        SimState observed = Fixtures.observedMid(rules);
        observed.used[FIRST] = 2;
        Plan.Step planned = new Plan.Step();
        planned.slot = observed.slot;
        planned.action = "act:" + rules.activities[FIRST].id;
        planned.kind = "dorm";
        Plan plan = new Plan();
        plan.fromSlot = observed.slot;
        plan.toSlot = observed.slot;
        plan.steps.add(planned);
        StateDraft draft = new StateDraft(StateDraft.Mode.SYNC, rules, observed, new Profile(), plan, 1);
        draft.setSlot(observed.slot + 1);
        draft.sync = StateDraft.SyncAnswer.PARTLY;
        assertEquals(2, draft.dormCount(FIRST));
        assertFalse(draft.isForecastDormCount(FIRST));
        draft.sync = StateDraft.SyncAnswer.YES;
        assertEquals(3, draft.dormCount(FIRST));
        assertTrue(draft.isForecastDormCount(FIRST));
        assertEquals(2, observed.used[FIRST]);
        draft.setDormCount(FIRST, 2);
        assertFalse(draft.isForecastDormCount(FIRST));
        assertEquals(2, draft.build().used[FIRST]);
    }

    @Test
    public void draftCountsFollowStableIdsAndGardeningChoiceSurvivesRebase() {
        Rules rules = dormRules();
        StateDraft draft = new StateDraft(StateDraft.Mode.EDIT, rules, Fixtures.observedMid(rules), new Profile(), null, 1);
        draft.setDormCount(FIRST, 3);
        draft.setDormCount(SECOND, 1);
        draft.setDormGardening(true);
        DraftStore store = new DraftStore(RuntimeEnvironment.getApplication());
        store.save(draft);
        Rules.Activity first = rules.activities[FIRST];
        rules.activities[FIRST] = rules.activities[SECOND];
        rules.activities[SECOND] = first;
        rules.activities[FIRST].idx = FIRST;
        rules.activities[SECOND].idx = SECOND;
        StateDraft restored = new StateDraft(StateDraft.Mode.EDIT, rules, Fixtures.observedMid(rules), new Profile(), null, 2);
        assertEquals(DraftStore.Restored.REBASED, store.restore(restored));
        assertEquals(1, restored.dormCount(FIRST));
        assertEquals(3, restored.dormCount(SECOND));
        assertTrue(restored.profile.planDormGardening);
        assertTrue(restored.profileChanged());
        StateDraft copied = new StateDraft(StateDraft.Mode.EDIT, rules, Fixtures.observedMid(rules), new Profile(), null, 3);
        copied.copyEditsFrom(restored);
        assertEquals(3, copied.dormCount(SECOND));
        assertTrue(copied.profile.planDormGardening);
    }

    @Test
    public void pageShowsRecordedMilestonesAndSavesCountCorrections() {
        Rules rules = dormRules();
        api.rules = rules;
        api.snap.observed = Fixtures.observedMid(rules);
        api.snap.observed.used[FIRST] = 3;
        StateActivity activity = stateController("edit").get();
        StateWizardTest.openFromHub(activity, "宿舍活动", StateSteps.Page.DORM);
        assertTrue("a count is recorded: no 做过吗？ first", Shots.has(activity, "宿舍活动各做过几次？"));
        assertTrue(Shots.has(activity, "特性已解锁"));
        assertTrue(Shots.has(activity, "与顺平阅读"));
        assertTrue(Shots.find(activity, "与顺平阅读：3 次").isSelected());
        String text = Shots.allText(activity.getWindow().getDecorView());
        assertFalse("no rule teaching", text.contains("做满 3 次"));
        assertFalse(text.contains("/ 3 次"));
        Shots.click(activity, "与顺平园艺：3 次");
        assertTrue(Shots.has(activity, "特性已强化"));
        Shots.shot(activity, "state_dorm_progress");
        Shots.click(activity, "完成");
        Shots.click(activity, "更新安排");
        assertEquals(3, api.last().state.used[SECOND]);
        assertFalse(api.last().profile != null && api.last().profile.planDormGardening);
    }

    @Test
    public void itemOnlyDormResultIsNotPresentedAsStatTraining() {
        Rules rules = dormRules();
        api.rules = rules;
        api.snap.observed = Fixtures.observedMid(rules);
        Plan.Step step = api.snap.plan.at(Fixtures.SLOT_NOW);
        step.action = "act:" + rules.activities[SECOND].id;
        step.kind = "dorm";
        step.titleZh = rules.activities[SECOND].zh;
        step.predicted = new Plan.Predicted();
        step.side.clear();
        step.sideZh.clear();
        assertEquals(StepKind.DORM, StepKind.of(step));
        assertTrue(StepKind.oneTap(step));
        ResultActivity activity = result(step.slot, step.action, "result");
        assertTrue(Shots.has(activity, "宿舍活动完成了吗？"));
        assertFalse(Shots.has(activity, "现在学力是几级？"));
        Shots.click(activity, "没完成");
        assertEquals(app.p3r.dayplanner.v3.engine.Outcome.Code.NOT_DONE, api.last().outcome.code);
    }

    @Test
    public void realPackAsksFirstThenShowsAllEighteenCategories() throws Exception {
        Rules rules = null;
        for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            File file = new File(candidate);
            if (file.isFile()) {
                rules = RulesLoader.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                break;
            }
        }
        org.junit.Assert.assertNotNull(rules);
        api.rules = rules;
        api.snap.observed = new SimState(rules.blocks.length, rules.activities.length);
        api.snap.observed.slot = Fixtures.SLOT_NOW;
        StateActivity activity = stateController("edit").get();
        StateWizardTest.openFromHub(activity, "宿舍活动", StateSteps.Page.DORM);
        assertEquals(18, activity.draft().dormActivities().size());
        // Nothing counted yet: one question first.
        assertTrue(Shots.has(activity, "做过宿舍活动吗？"));
        Shots.shot(activity, "state_dorm_gate");
        Shots.click(activity, "做过");
        assertTrue(Shots.has(activity, "宿舍活动各做过几次？"));
        for (Rules.Activity category : activity.draft().dormActivities()) {
            assertTrue(category.id, Shots.has(activity, category.zh + "：3 次"));
        }
        // Gardening is a planning choice of the 社群 tab's 队友特性, not part of the game state.
        assertFalse(Shots.has(activity, "允许安排园艺（我会提前种好作物）"));
        Shots.shot(activity, "state_dorm_real_pack_top");
        android.widget.ScrollView scroll = scrollIn(activity.getWindow().getDecorView());
        org.junit.Assert.assertNotNull(scroll);
        scroll.fullScroll(android.view.View.FOCUS_DOWN);
        Shots.idle();
        Shots.shot(activity, "state_dorm_real_pack_bottom");
    }

    private static android.widget.ScrollView scrollIn(android.view.View view) {
        if (view instanceof android.widget.ScrollView) return (android.widget.ScrollView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.ScrollView found = scrollIn(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void notDoneYetSkipsThePage() {
        Rules rules = dormRules();
        api.rules = rules;
        api.snap.observed = Fixtures.observedMid(rules);
        StateActivity activity = stateController("edit").get();
        StateWizardTest.openFromHub(activity, "宿舍活动", StateSteps.Page.DORM);
        assertTrue(Shots.has(activity, "做过宿舍活动吗？"));
        Shots.click(activity, "都没做过");
        assertEquals(StateSteps.Page.HUB, activity.currentPage());
        assertTrue(activity.draft().changes().isEmpty());
    }
}
