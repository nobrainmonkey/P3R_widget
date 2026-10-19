package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.store.Ledger;
import app.p3r.dayplanner.v3.store.PlanDb;
import app.p3r.dayplanner.v3.store.TestRules;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A new planning policy invalidates cached forecasts without changing observed history. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerPolicyVersionTest extends ServiceTestBase {
    @Test
    public void anOlderPlanWithoutAPolicyVersionReplansOnce() throws Exception {
        assertOldPolicyReplans(null);
    }

    @Test
    public void anExplicitOlderPolicyReplansOnce() throws Exception {
        assertOldPolicyReplans(0);
    }

    @Test
    public void previousStatAndInvitationPolicyReplansOnce() throws Exception {
        assertOldPolicyReplans(1);
    }

    @Test
    public void previousMultiGoalPolicyReplansOnce() throws Exception {
        assertOldPolicyReplans(2);
    }

    @Test
    public void previousUnlockCandidatePolicyReplansOnce() throws Exception {
        assertOldPolicyReplans(3);
    }

    @Test
    public void policyVersionSurvivesJsonAndLegacyJsonDefaultsToZero() throws Exception {
        Plan fresh = new Plan();
        assertEquals(Plan.CURRENT_POLICY_VERSION, fresh.policyVersion);
        JSONObject json = fresh.toJson();
        assertEquals(Plan.CURRENT_POLICY_VERSION, json.getInt("policyVersion"));
        assertEquals(Plan.CURRENT_POLICY_VERSION, Plan.fromJson(json).policyVersion);
        json.remove("policyVersion");
        assertEquals(0, Plan.fromJson(json).policyVersion);
    }

    private void assertOldPolicyReplans(Integer oldVersion) throws Exception {
        onboard(TestRules.slot(TestRules.MAY_12, false));
        settle();
        PlannerApi.Snapshot before = service.snapshot();
        String observed = before.observed.toJson().toString();
        long stateRevision = before.stateRevision;
        long profileRevision = before.profileRevision;

        try (Ledger ledger = new Ledger(context, PlanDb.FILE_NAME)) {
            Ledger.StoredPlan stored = ledger.plan();
            assertNotNull(stored);
            JSONObject old = new JSONObject(stored.json);
            if (oldVersion != null) old.put("policyVersion", oldVersion);
            else old.remove("policyVersion");
            ledger.putPlan(new Ledger.StoredPlan(stored.stateRevision, stored.profileRevision,
                    stored.rulesVersion, old.toString()));
        }

        CountDownLatch release = new CountDownLatch(1);
        planner.blockedCall = 2;
        planner.blockFirstCall = release;
        restart();
        try {
            assertTrue("the old forecast must trigger a computation", planner.firstCallEntered.await(10, TimeUnit.SECONDS));
            PlannerApi.Snapshot during = service.snapshot();
            assertNotNull("the old forecast remains available while recomputing", during.plan);
            assertEquals(oldVersion == null ? 0 : oldVersion.intValue(), during.plan.policyVersion);
            assertFalse("same rule pack and revisions are insufficient under an old policy", during.planCurrent);
            assertTrue(during.planning);
            assertEquals(rules.version, during.plan.rulesVersion);
            assertEquals(stateRevision, during.stateRevision);
            assertEquals(profileRevision, during.profileRevision);
            assertEquals(observed, during.observed.toJson().toString());
        } finally {
            release.countDown();
        }
        settle();

        PlannerApi.Snapshot after = service.snapshot();
        assertTrue(after.planCurrent);
        assertEquals(Plan.CURRENT_POLICY_VERSION, after.plan.policyVersion);
        assertEquals("fake-2", after.plan.planId);
        assertEquals(stateRevision, after.stateRevision);
        assertEquals(profileRevision, after.profileRevision);
        assertEquals(observed, after.observed.toJson().toString());

        try (Ledger ledger = new Ledger(context, PlanDb.FILE_NAME)) {
            assertEquals(Plan.CURRENT_POLICY_VERSION,
                    new JSONObject(ledger.plan().json).getInt("policyVersion"));
        }
        restart();
        settle();
        assertTrue(service.snapshot().planCurrent);
        assertEquals("a current-policy forecast is reused across restarts", 2, planner.calls.get());
        assertEquals("fake-2", service.snapshot().plan.planId);
    }
}
