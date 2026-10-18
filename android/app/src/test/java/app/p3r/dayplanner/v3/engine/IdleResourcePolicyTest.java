package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Jobs fund an actual plan; surplus money must not become an end-of-game objective. */
public class IdleResourcePolicyTest {
    private static Rules finalEvening() {
        Rules rules = RulesFixtures.small();
        rules.rescues = new Rules.Rescue[0];
        rules.days[rules.lastDay].eveLock = null;
        return rules;
    }

    private static Profile spareTime(int cap) {
        Profile p = new Profile();
        p.otherLinks = false;
        p.towerVisitsPerBlock = 0;
        p.towerCapPerBlock = cap;
        return p;
    }

    private static SimState finished(Rules rules, int slot, long cash) {
        SimState state = RulesFixtures.newGame(rules, slot);
        Arrays.fill(state.rank, 10);
        for (int s = 0; s < rules.stats.length; s++) state.statPts[s] = rules.stats[s].thresholds[6];
        state.cash = cash;
        return state;
    }

    private static Plan plan(Rules rules, Profile p, SimState state, long budget) {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = budget;
        Plan plan = Engine.plan(rules, p, state, null, options);
        List<String> errors = Engine.validate(rules, p, state, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        return plan;
    }

    @Test
    public void rolloutKeepsSpareTowerInsteadOfReintroducingRichMaxStatWork() {
        Rules rules = finalEvening();
        Profile p = spareTime(1);
        SimState state = finished(rules, rules.lastSlot(), 100_000);
        assertTrue(Engine.legalActions(rules, p, state).contains(Action.activity(RulesFixtures.A_JOB)));
        assertEquals("tower:1", plan(rules, p, state, 0).steps.get(0).action);
        assertEquals("tower:1", plan(rules, p, state, 1_000).steps.get(0).action);
    }

    @Test
    public void spareTowerStopsAtTheCapThenLeavesUnneededJobsForManualChoice() {
        Rules rules = finalEvening();
        Profile p = spareTime(1);
        SimState state = finished(rules, Rules.slot(33, true), 100_000);
        Plan plan = plan(rules, p, state, 1_000);
        int[] visits = new int[rules.blocks.length];
        int free = 0;
        for (Plan.Step step : plan.steps) {
            assertFalse(step.action, "job".equals(step.kind));
            if ("tower".equals(step.kind)) visits[Action.parse(rules, step.action).target]++;
            if ("free".equals(step.kind)) free++;
        }
        assertArrayVisitsWithinCap(visits, 1);
        assertTrue("after the finite useful visits, leave spare evenings free", free > 5);
        assertEquals(100_000, plan.summary.endCash);

        SimState capped = finished(rules, rules.lastSlot(), 100_000);
        capped.towerVisits[1] = 1;
        assertTrue("the player can still choose paid work manually",
                Engine.legalActions(rules, p, capped).contains(Action.activity(RulesFixtures.A_JOB)));
        assertEquals("free", plan(rules, p, capped, 1_000).steps.get(0).kind);
    }

    private static void assertArrayVisitsWithinCap(int[] visits, int cap) {
        int total = 0;
        for (int count : visits) {
            assertTrue("tower cap", count <= cap);
            total += count;
        }
        assertTrue("available spare exploration is still useful", total > 0);
    }

    @Test
    public void academicsGateDoesNotGiveRichCafeWorkAnImaginaryCashNeed() {
        Rules rules = finalEvening();
        rules.links[RulesFixtures.S_EMPEROR].unlock.statLv.put(RulesFixtures.ACADEMICS, 6);
        rules.links[RulesFixtures.S_EMPEROR].unlock.earliestDay = 40;
        Profile p = spareTime(1);
        p.tier[RulesFixtures.S_EMPEROR] = Profile.Tier.MUST;
        p.target[RulesFixtures.S_EMPEROR] = 1;
        SimState state = finished(rules, Rules.slot(33, true), 1_000_000);
        state.rank[RulesFixtures.S_EMPEROR] = 0;
        state.statPts[RulesFixtures.ACADEMICS] = rules.stats[RulesFixtures.ACADEMICS].thresholds[6] - 2;
        Plan plan = plan(rules, p, state, 1_000);
        for (Plan.Step step : plan.steps) assertFalse(step.action, "job".equals(step.kind));
        assertTrue("the actual academic prerequisite still gets met", plan.summary.endRanks[RulesFixtures.S_EMPEROR] >= 1);
    }

    @Test
    public void workStillFundsASelectedLinksRealUnlockFees() {
        Rules rules = finalEvening();
        Profile p = spareTime(1);
        p.tier[RulesFixtures.S_DEVIL] = Profile.Tier.MUST;
        p.target[RulesFixtures.S_DEVIL] = 1;
        SimState state = finished(rules, Rules.slot(33, true), 20_000);
        state.rank[RulesFixtures.S_DEVIL] = 0;
        Plan plan = plan(rules, p, state, 1_000);
        assertEquals("job", plan.steps.get(0).kind);
        assertTrue("earn the missing fee and actually unlock the selected link", plan.summary.endRanks[RulesFixtures.S_DEVIL] >= 1);
        assertTrue(plan.steps.get(0).whyZh, plan.steps.get(0).whyZh.contains("解锁恶魔"));
    }

    @Test
    public void explicitCashBufferIsFilledButNeverFarmedPastItsTarget() {
        Rules rules = finalEvening();
        Profile p = spareTime(1);
        p.cashBuffer = 5_000;
        SimState shortOfBuffer = finished(rules, rules.lastSlot(), 2_500);
        Plan fill = plan(rules, p, shortOfBuffer, 1_000);
        assertEquals("job", fill.steps.get(0).kind);
        assertEquals(5_000, fill.summary.endCash);
        assertTrue(fill.steps.get(0).whyZh, fill.steps.get(0).whyZh.contains("现金保留额"));
        SimState enough = finished(rules, rules.lastSlot(), 5_000);
        assertEquals("tower", plan(rules, p, enough, 1_000).steps.get(0).kind);
        enough.towerVisits[1] = 1;
        assertEquals("free", plan(rules, p, enough, 1_000).steps.get(0).kind);
    }

    @Test
    public void chosenMaxLinkBeatsCashFillingAndSpareExploration() {
        for (Profile.Tier tier : new Profile.Tier[]{Profile.Tier.MUST, Profile.Tier.WANT}) {
            Rules rules = finalEvening();
            Profile p = spareTime(1);
            p.tier[RulesFixtures.S_DEVIL] = tier;
            p.cashBuffer = 1_000_000;
            SimState state = finished(rules, rules.lastSlot(), 0);
            state.rank[RulesFixtures.S_DEVIL] = 9;
            state.ready[RulesFixtures.S_DEVIL] = true;
            Plan plan = plan(rules, p, state, 1_000);
            assertEquals(tier.toString(), "meet:devil", plan.steps.get(0).action);
            assertEquals(10, plan.summary.endRanks[RulesFixtures.S_DEVIL]);
        }
    }

    @Test
    public void manualJobExplanationOnlyNamesStatsThatStillHaveRoom() {
        Rules rules = finalEvening();
        Profile p = spareTime(0);
        p.charmBonus = false;
        SimState state = finished(rules, rules.lastSlot(), 100_000);
        Plan.Step full = jobAlternative(rules, p, state);
        assertFalse(full.whyZh, full.whyZh.contains("提升"));
        assertFalse(full.subtitleZh, full.subtitleZh.contains("魅力"));
        assertFalse(full.subtitleZh, full.subtitleZh.contains("勇气"));
        state.statPts[RulesFixtures.COURAGE]--;
        Plan.Step courage = jobAlternative(rules, p, state);
        assertTrue(courage.whyZh, courage.whyZh.contains("勇气"));
        assertFalse(courage.whyZh, courage.whyZh.contains("魅力"));
        assertTrue(courage.subtitleZh, courage.subtitleZh.contains("勇气"));
        assertFalse(courage.subtitleZh, courage.subtitleZh.contains("魅力"));
    }

    @Test
    public void independentFreeWarningDistinguishesSurplusWorkFromAMissingCashReserve() {
        Rules rules = finalEvening();
        Profile p = spareTime(0);
        SimState enough = finished(rules, rules.lastSlot(), 100_000);
        Plan free = plan(rules, p, enough, 1_000);
        assertEquals("free", free.steps.get(0).kind);
        assertTrue(Engine.validationWarnings(rules, p, enough, free).isEmpty());

        p.cashBuffer = 5_000;
        SimState shortOfBuffer = finished(rules, rules.lastSlot(), 2_500);
        assertFalse("a skipped evening can verifiably earn the missing reserve",
                Engine.validationWarnings(rules, p, shortOfBuffer, free).isEmpty());
    }

    private static Plan.Step jobAlternative(Rules rules, Profile p, SimState state) {
        Plan.Step found = null;
        for (Plan.Step step : Engine.alternatives(rules, p, state)) {
            if ("act:job_chagall".equals(step.action)) found = step;
        }
        assertNotNull("manual work is still offered", found);
        return found;
    }
}
