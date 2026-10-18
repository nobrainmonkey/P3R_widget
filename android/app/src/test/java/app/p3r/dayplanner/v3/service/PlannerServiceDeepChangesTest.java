package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.store.TestRules;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The persistent 深度修改 option affects automatic replans, separately from manual searches. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceDeepChangesTest extends ServiceTestBase {
    private static final int MAY12_DAY = TestRules.slot(TestRules.MAY_12, false);

    private void planned() {
        onboard(MAY12_DAY);
        settle();
    }

    private void setDeepChanges(boolean enabled) {
        Profile profile = service.snapshot().profile.copy();
        profile.deepChanges = enabled;
        assertTrue(service.saveProfile(id(), service.snapshot().profileRevision, profile).ok);
        settle();
    }

    @Test
    public void settingsAndRecordChangesUseOneTwelveSecondRunWithoutAnAutomaticExtension() {
        planned();
        planner.shape = (plan, n) -> {
            plan.budgetExhausted = true;
            plan.summary.notesZh.add(Plan.BUDGET_NOTE_PENDING);
        };
        setDeepChanges(true);
        assertEquals(2, planner.calls.get());
        Engine.Options changed = planner.options.get(1);
        assertTrue(changed.deepSearch);
        assertTrue(changed.candidates >= 8);
        assertEquals(12_000, changed.timeBudgetMillis);
        assertTrue(changed.followUp);
        assertEquals("fake-1", planner.previousIds.get(1));
        assertTrue(service.snapshot().planCurrent);
        assertFalse(service.snapshot().plan.followUpPending());
        assertFalse("automatic deep search creates no manual request", service.snapshot().fullReplanning);
        assertEquals(0, service.snapshot().fullReplanTicket);

        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        settle();
        assertEquals("one run for each changed input, no extra 8 s run", 3, planner.calls.get());
        Engine.Options recorded = planner.options.get(2);
        assertEquals(12_000, recorded.timeBudgetMillis);
        assertTrue(recorded.deepSearch);
        assertTrue(recorded.followUp);
        assertEquals("the engine can validate and rebase the remaining previous route", "fake-2",
            planner.previousIds.get(2));
        assertFalse(service.snapshot().plan.followUpPending());
    }

    @Test
    public void manualModesKeepTheirBudgetsAndTurningTheSettingOffRestoresNormalReplans() {
        planned();
        setDeepChanges(true);

        service.requestFullReplan();
        settle();
        Engine.Options full = planner.options.get(2);
        assertEquals(150, full.timeBudgetMillis);
        assertFalse(full.deepSearch);
        assertEquals("", planner.previousIds.get(2));

        service.requestDeepReplan();
        settle();
        assertEquals(30_000, planner.options.get(3).timeBudgetMillis);
        assertTrue(planner.options.get(3).deepSearch);
        assertEquals("", planner.previousIds.get(3));

        setDeepChanges(false);
        Engine.Options normal = planner.options.get(4);
        assertEquals("the configured normal budget remains unchanged", 50, normal.timeBudgetMillis);
        assertFalse(normal.deepSearch);
        assertFalse(normal.followUp);
        assertEquals("fake-4", planner.previousIds.get(4));
    }

    @Test
    public void theChoiceSurvivesRestartAndBackupRestoration() throws Exception {
        planned();
        setDeepChanges(true);
        String backup = service.exportBackup();
        assertTrue(new JSONObject(backup).getJSONObject("profile").getBoolean("deepChanges"));

        restart();
        assertTrue(service.snapshot().profile.deepChanges);
        assertTrue(service.snapshot().planCurrent);
        setDeepChanges(false);
        assertFalse(service.snapshot().profile.deepChanges);

        assertTrue(service.importBackup(id(), backup).ok);
        settle();
        assertTrue(service.snapshot().profile.deepChanges);
        Engine.Options restored = planner.options.get(planner.options.size() - 1);
        assertTrue(restored.deepSearch);
        assertEquals(12_000, restored.timeBudgetMillis);
        assertTrue(service.snapshot().planCurrent);
    }
}
