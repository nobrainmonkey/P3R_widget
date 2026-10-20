package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

/** Optional characteristic targets save with confirmed counts, never with a forecast as fact. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DormTargetsUiTest extends UiTestBase {
    private static final int READING = 4;
    private static final int GARDENING = 5;

    private void installDormRules() {
        Rules rules = Fixtures.rules();
        rules.activities = Arrays.copyOf(rules.activities, 6);
        rules.activities[READING] = activity(READING, "dorm_junpei_reading", "和顺平看书");
        rules.activities[GARDENING] = activity(GARDENING, "dorm_junpei_gardening", "和顺平种菜");
        rules.activities[GARDENING].requiresPlantedCrop = true;
        api.rules = rules;
        api.snap.observed = Fixtures.observedMid(rules);
    }

    private Rules.Activity activity(int idx, String id, String title) {
        Rules.Activity activity = new Rules.Activity();
        activity.idx = idx;
        activity.id = id;
        activity.zh = title;
        activity.dormPartnerZh = "伊织顺平";
        activity.fromDay = Fixtures.day(6, 16);
        activity.toDay = Fixtures.day(1, 30);
        activity.fixedDays = new int[]{Fixtures.day(6, 16), Fixtures.day(10, 23), Fixtures.day(1, 30)};
        activity.eve = true;
        activity.locationZh = "宿舍";
        return activity;
    }

    /** The 社群 tab on its 队友特性 sub-tab. */
    private HomeActivity links() {
        HomeActivity activity = home();
        activity.selectTab(HomeActivity.TAB_LINKS);
        Shots.idle();
        Shots.click(activity, "队友特性");
        return activity;
    }

    @Test
    public void goalAndCountCorrectionAreSavedOnceTogetherWithoutGrantingStats() {
        installDormRules();
        api.snap.observed.used[READING] = 2;
        api.snap.observed.used[GARDENING] = 1;
        api.snap.observed.used[0] = 12;
        api.snap.observed.rescueFailed = 1;
        int[] stats = api.snap.observed.statPts.clone();
        HomeActivity activity = links();
        assertTrue(Shots.has(activity, "队友特性"));
        Shots.click(activity, "伊织顺平 特性目标：未选");
        assertTrue(Shots.has(activity, DormTargets.RULE));
        assertTrue(Shots.has(activity, "未解锁"));
        Shots.click(activity, "伊织顺平 特性目标：强化");
        Shots.click(activity, "和顺平种菜：3 次");
        assertTrue("edits wait for the save button", api.calls.isEmpty());
        // The stamp follows the counts being edited.
        assertTrue(Shots.has(activity, "已解锁"));
        Shots.shot(activity, "links_dorm_target_edit");
        Shots.click(activity, "保存");
        assertEquals(1, api.calls.size());
        assertEquals("saveCheckpoint", api.last().method);
        assertEquals(Profile.DormTarget.UPGRADE, api.last().profile.dormTarget("dorm_junpei"));
        assertEquals(2, api.last().state.used[READING]);
        assertEquals(3, api.last().state.used[GARDENING]);
        assertEquals(12, api.last().state.used[0]);
        assertEquals(1, api.last().state.rescueFailed);
        assertArrayEquals(stats, api.last().state.statPts);
        assertFalse("selecting a characteristic does not silently allow gardening", api.last().profile.planDormGardening);
    }

    @Test
    public void dismissingEditsLeavesProfileAndRecordedCountsUntouched() {
        installDormRules();
        HomeActivity activity = links();
        Shots.click(activity, "伊织顺平 特性目标：未选");
        Shots.click(activity, "伊织顺平 特性目标：解锁");
        Shots.click(activity, "和顺平看书：3 次");
        // No 取消: back (or a swipe) discards.
        assertFalse(Shots.has(activity, "取消"));
        activity.onBackPressed();
        Shots.idle();
        assertFalse(activity.sheets().isShowing());
        assertTrue(api.calls.isEmpty());
        assertEquals(Profile.DormTarget.NONE, api.snap.profile.dormTarget("dorm_junpei"));
        assertEquals(0, api.snap.observed.used[READING]);
    }

    @Test
    public void currentForecastDoesNotPrefillActualCountsAndStaleForecastIsHidden() {
        installDormRules();
        api.snap.observed.used[READING] = 2;
        Plan.Step step = new Plan.Step();
        step.slot = api.snap.observed.slot;
        step.action = "act:dorm_junpei_reading";
        step.kind = "dorm";
        step.titleZh = "和顺平看书";
        Plan plan = new Plan();
        plan.fromSlot = step.slot;
        plan.toSlot = step.slot;
        plan.steps.add(step);
        api.snap.plan = plan;
        HomeActivity activity = links();
        Shots.click(activity, "伊织顺平 特性目标：未选");
        // The forecast is a cyan tag beside the count, never the selected count itself.
        assertTrue(Shots.has(activity, "预计 3"));
        assertTrue(Shots.find(activity, "和顺平看书：2 次").isSelected());
        assertFalse(Shots.find(activity, "和顺平看书：3 次").isSelected());
        Shots.click(activity, "伊织顺平 特性目标：解锁");
        Shots.click(activity, "保存");
        assertEquals("saveProfile", api.last().method);
        assertEquals(2, api.snap.observed.used[READING]);
        api.snap.planCurrent = false;
        activity.reload();
        Shots.idle();
        Shots.click(activity, "伊织顺平 特性目标：解锁");
        assertTrue(activity.sheets().isShowing());
        assertFalse(Shots.has(activity, "预计 3"));
    }

    @Test
    public void unchangedCappedCountKeepsRawCountAndDoesNotCreateCheckpoint() {
        installDormRules();
        api.snap.observed.used[READING] = 7;
        HomeActivity activity = links();
        Shots.click(activity, "伊织顺平 特性目标：未选");
        Shots.click(activity, "和顺平看书：2 次");
        Shots.click(activity, "和顺平看书：3 次");
        Shots.click(activity, "伊织顺平 特性目标：强化");
        Shots.click(activity, "保存");
        assertEquals("saveProfile", api.last().method);
        assertEquals(7, api.snap.observed.used[READING]);
    }

    @Test
    public void gardeningIsOnlyPlannedThroughItsOwnSwitch() {
        installDormRules();
        HomeActivity activity = links();
        Shots.click(activity, "伊织顺平 特性目标：未选");
        ToggleRow crops = (ToggleRow) Shots.clickTarget(Shots.find(activity, "种菜也排进来"));
        assertEquals("种菜也排进来，记得先买好种苗种下去", crops.getContentDescription().toString());
        assertFalse(crops.isChecked());
        Shots.click(activity, "种菜也排进来");
        assertTrue(crops.isChecked());
        assertTrue(api.calls.isEmpty());
        Shots.click(activity, "保存");
        assertEquals("saveProfile", api.last().method);
        assertTrue(api.last().profile.planDormGardening);
        assertEquals(Profile.DormTarget.NONE, api.last().profile.dormTarget("dorm_junpei"));
    }

    @Test
    public void aNewRecordOfAnEditedCountReopensTheSheetInsteadOfOverwritingIt() {
        installDormRules();
        HomeActivity activity = links();
        Shots.click(activity, "伊织顺平 特性目标：未选");
        Shots.click(activity, "伊织顺平 特性目标：强化");
        Shots.click(activity, "和顺平看书：3 次");
        // Meanwhile the widget records a reading session.
        api.snap.observed.used[READING] = 1;
        api.snap.stateRevision++;
        activity.reload();
        Shots.idle();
        Shots.click(activity, "保存");
        assertTrue(api.calls.isEmpty());
        assertTrue(activity.sheets().isShowing());
        assertEquals(DormTargets.COUNTS_MOVED, activity.undoBar().messageText().toString());
        // The player's target stays picked; the count shows the new record.
        assertTrue(Shots.find(activity, "伊织顺平 特性目标：强化").isSelected());
        assertTrue(Shots.find(activity, "和顺平看书：1 次").isSelected());
        Shots.click(activity, "保存");
        assertEquals("saveProfile", api.last().method);
        assertEquals(Profile.DormTarget.UPGRADE, api.last().profile.dormTarget("dorm_junpei"));
    }

    @Test
    public void aTargetChangeIsSavedOntoTheLatestProfile() {
        installDormRules();
        HomeActivity activity = links();
        Shots.click(activity, "伊织顺平 特性目标：未选");
        Shots.click(activity, "伊织顺平 特性目标：解锁");
        // Another edit lands while the sheet is open.
        api.snap.profile.tier[1] = Profile.Tier.WANT;
        api.snap.profileRevision++;
        activity.reload();
        Shots.idle();
        Shots.click(activity, "保存");
        assertEquals("saveProfile", api.last().method);
        assertEquals(Profile.DormTarget.UNLOCK, api.last().profile.dormTarget("dorm_junpei"));
        assertEquals("the newer edit is kept", Profile.Tier.WANT, api.last().profile.tier[1]);
    }

    @Test
    public void beforeInitialSetupCanSelectGoalWithoutCreatingGameProgress() {
        installDormRules();
        api.snap.observed = null;
        api.snap.plan = null;
        HomeActivity activity = links();
        Shots.click(activity, "伊织顺平 特性目标：未选");
        assertFalse("no counts before 开始设置", Shots.has(activity, "做过几次？"));
        assertFalse(Shots.has(activity, "和顺平看书：3 次"));
        Shots.click(activity, "伊织顺平 特性目标：解锁");
        Shots.click(activity, "保存");
        assertEquals("saveProfile", api.last().method);
        assertEquals(Profile.DormTarget.UNLOCK, api.last().profile.dormTarget("dorm_junpei"));
        assertTrue(api.snap.observed == null);
    }

    @Test
    public void realPackOnlyOffersNineTeammatesWithTheirTwoActualActivities() throws Exception {
        Rules rules = readRealRules();
        List<DormTargets.Partner> partners = DormTargets.partners(rules);
        assertEquals(9, partners.size());
        for (DormTargets.Partner partner : partners) assertEquals(partner.id, 2, partner.activities.size());
        api.rules = rules;
        api.snap.observed = new SimState(rules.blocks.length, rules.activities.length);
        api.snap.observed.slot = Fixtures.SLOT_NOW;
        api.snap.observed.used[partners.get(2).activities.get(0).idx] = 3;
        api.snap.plan = null;
        api.snap.profile.dormTargets.put(partners.get(0).id, Profile.DormTarget.UNLOCK);
        HomeActivity activity = links();
        for (DormTargets.Partner partner : partners) {
            assertTrue(partner.name, Shots.has(activity, partner.name + " 特性目标："
                + DormTargets.label(api.snap.profile.dormTarget(partner.id))));
        }
        assertTrue(Shots.has(activity, "解锁"));
        assertTrue(Shots.has(activity, "已解锁"));
        Shots.shot(activity, "links_dorm_grid");
        // The sub-tab is remembered the next time the tab opens.
        HomeActivity again = home();
        again.selectTab(HomeActivity.TAB_LINKS);
        Shots.idle();
        assertTrue(Shots.has(again, partners.get(1).name + " 特性目标：未选"));
    }

    private static Rules readRealRules() throws Exception {
        for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            File file = new File(candidate);
            if (file.isFile()) return RulesLoader.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
        throw new AssertionError("real rule pack missing");
    }
}
