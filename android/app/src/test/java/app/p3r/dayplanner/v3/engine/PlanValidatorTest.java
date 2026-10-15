package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/** The independent validator must catch deliberately corrupted plans. */
public class PlanValidatorTest {
    private Rules rules;
    private Profile profile;
    private SimState start;
    private Plan plan;

    @Before
    public void setUp() {
        rules = RulesFixtures.small();
        profile = RulesFixtures.others();
        start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Engine.Options o = new Engine.Options();
        o.timeBudgetMillis = 60_000;
        plan = Engine.plan(rules, profile, start, null, o);
        assertTrue(Engine.validate(rules, profile, start, plan).isEmpty());
    }

    private Plan copy() throws JSONException {
        return Plan.fromJson(new JSONObject(plan.toJson().toString()));
    }

    private void assertRejected(Plan bad, String expectedFragment) {
        List<String> errors = Engine.validate(rules, profile, start, bad);
        assertFalse("corruption not detected: " + expectedFragment, errors.isEmpty());
        boolean found = false;
        for (String e : errors) found |= e.contains(expectedFragment);
        assertTrue(expectedFragment + " not in " + errors, found);
    }

    private Plan.Step firstOfKind(Plan p, String kind) {
        for (Plan.Step s : p.steps) if (kind.equals(s.kind)) return s;
        throw new AssertionError("no " + kind + " step");
    }

    @Test
    public void doubleBookingIsRejected() throws JSONException {
        Plan bad = copy();
        Plan.Step dup = Plan.fromJson(new JSONObject(plan.toJson().toString())).steps.get(3);
        bad.steps.add(4, dup);
        assertRejected(bad, "out of order");
    }

    @Test
    public void missingSlotsAreRejected() throws JSONException {
        Plan bad = copy();
        bad.steps.remove(bad.steps.size() - 1);
        bad.toSlot--;
        assertRejected(bad, "calendar ends");
    }

    @Test
    public void actionOnALockedSlotIsRejected() throws JSONException {
        Plan bad = copy();
        Plan.Step locked = bad.at(Rules.slot(21, false));
        locked.action = "act:study_room";
        locked.kind = "stat";
        assertRejected(bad, "locked slot");
    }

    @Test
    public void overspendingIsRejected() throws JSONException {
        Plan bad = copy();
        Plan.Step firstEvening = bad.at(Rules.slot(20, true)); // no cash yet
        firstEvening.action = "act:arcade_charm";
        firstEvening.kind = "stat";
        assertRejected(bad, "arcade_charm costs 1500");
        assertRejected(bad, "cash below zero");
    }

    @Test
    public void tartarusCapBreachIsRejected() throws JSONException {
        Profile capped = RulesFixtures.others();
        capped.towerVisitsPerBlock = 1;
        capped.towerCapPerBlock = 1;
        List<String> errors = Engine.validate(rules, capped, start, plan);
        boolean found = false;
        for (String e : errors) found |= e.contains("cap 1 exceeded");
        assertTrue(errors.toString(), found);
    }

    @Test
    public void meetingOnAnUnavailableDayIsRejected() throws JSONException {
        Plan bad = copy();
        Plan.Step meet = firstOfKind(bad, "social");
        Plan.Step sunday = bad.at(Rules.slot(25, false)); // Sunday: magician never meets
        sunday.action = "meet:magician";
        sunday.kind = "social";
        sunday.predicted = meet.predicted;
        assertRejected(bad, "magician is not available");
    }

    @Test
    public void rankSkipIsRejected() throws JSONException {
        Plan bad = copy();
        for (Plan.Step s : bad.steps) {
            if ("social".equals(s.kind) && s.predicted.rankUp) {
                s.predicted.rankTo = s.predicted.rankFrom + 2;
                break;
            }
        }
        assertRejected(bad, "predicted RANK");
    }

    @Test
    public void wrongEndRanksInTheSummaryAreRejected() throws JSONException {
        Plan bad = copy();
        bad.summary.endRanks[RulesFixtures.S_MAGICIAN] = 3;
        assertRejected(bad, "summary end rank");
    }

    @Test
    public void unlockOutOfOrderIsRejected() throws JSONException {
        Plan bad = copy();
        Plan.Step s = bad.at(Rules.slot(26, false)); // Monday, emperor not started
        s.action = "unlock:emperor:1";
        s.kind = "unlock";
        assertRejected(bad, "emperor");
    }

    // ------------------------------------------------------------------ rescue windows

    /*
     * The fixture's 北村文吉 is missing on days 40–45. Both ends count: the limit evening (day 45) still
     * rescues him and from day 46 he is lost (REPORT, tartarus "rescue deadline").
     */

    private static final int LIMIT = 45;

    /** 教皇 at RANK 2 with 北村文吉 missing and nothing recorded, at the daytime of {@code day}. */
    private SimState bunkichiMissingAt(int day) {
        SimState s = RulesFixtures.newGame(rules, Rules.slot(day, false));
        s.cash = 20_000;
        s.rank[RulesFixtures.S_HIEROPHANT] = 2;
        return s;
    }

    private Plan plan(SimState from) {
        Engine.Options o = new Engine.Options();
        o.timeBudgetMillis = 60_000;
        Plan p = Engine.plan(rules, profile, from, null, o);
        List<String> errors = Engine.validate(rules, profile, from, p);
        assertTrue(errors.toString(), errors.isEmpty());
        return p;
    }

    private static Plan copy(Plan p) throws JSONException {
        return Plan.fromJson(new JSONObject(p.toJson().toString()));
    }

    private void assertRejected(SimState from, Plan bad, String expectedFragment) {
        List<String> errors = Engine.validate(rules, profile, from, bad);
        boolean found = false;
        for (String e : errors) found |= e.contains(expectedFragment);
        assertTrue(expectedFragment + " not in " + errors, found);
    }

    /** Turns the step at {@code slot} into a Tartarus visit of block 1 that claims a rescue. */
    private static void claimRescueAt(Plan p, int slot) {
        Plan.Step s = p.at(slot);
        s.action = "tower:1";
        s.kind = "tower";
        s.side.clear();
        s.predicted.cashDelta = 0;
        if (!s.assumptions.contains(PlanValidator.RESCUE_ASSUMPTION)) s.assumptions.add(PlanValidator.RESCUE_ASSUMPTION);
    }

    @Test
    public void theLimitEveningStillRescues() {
        SimState start = bunkichiMissingAt(LIMIT);
        Plan p = plan(start);
        Plan.Step limit = p.at(Rules.slot(LIMIT, true));
        assertEquals("tower", limit.kind);
        assertTrue(limit.assumptions.contains(PlanValidator.RESCUE_ASSUMPTION));
        assertTrue(limit.subtitleZh, limit.subtitleZh.endsWith("救出北村文吉"));
        assertTrue(limit.whyZh, limit.whyZh.startsWith("北村文吉失踪了，最晚要在") && limit.whyZh.contains("晚上救出"));
        assertTrue("教皇 goes on after the rescue", p.summary.endRanks[RulesFixtures.S_HIEROPHANT] > 2);
    }

    @Test
    public void aRescueClaimedAfterTheLimitIsRejected() throws JSONException {
        SimState start = bunkichiMissingAt(LIMIT + 1);
        Plan p = plan(start);
        for (Plan.Step s : p.steps) assertFalse(s.action, s.assumptions.contains(PlanValidator.RESCUE_ASSUMPTION));
        assertEquals("lost from the day after the limit", 2, p.summary.endRanks[RulesFixtures.S_HIEROPHANT]);
        Plan bad = copy(p);
        claimRescueAt(bad, Rules.slot(LIMIT + 1, true));
        assertRejected(start, bad, "claims a rescue outside every open rescue window");
    }

    @Test
    public void aRescueClaimedBeforeTheWindowIsRejected() throws JSONException {
        Plan bad = copy();
        claimRescueAt(bad, Rules.slot(39, true));
        assertRejected(start, bad, "claims a rescue outside every open rescue window");
    }

    @Test
    public void aRescueThePlanDoesNotClaimIsRejected() throws JSONException {
        Plan bad = copy();
        Plan.Step rescue = null;
        for (Plan.Step s : bad.steps) if (s.assumptions.contains(PlanValidator.RESCUE_ASSUMPTION)) rescue = s;
        assertTrue("the plan rescues 北村文吉", rescue != null);
        int day = rescue.slot >> 1;
        assertTrue("inside the window: " + day, day >= 40 && day <= LIMIT);
        rescue.assumptions.remove(PlanValidator.RESCUE_ASSUMPTION);
        assertRejected(start, bad, "rescues bunkichi but does not claim it");
    }

    @Test
    public void freeSlotWithALegalActionIsAWarning() throws JSONException {
        Plan bad = copy();
        Plan.Step monday = bad.at(Rules.slot(26, true)); // studying is always possible in the evening
        monday.action = "free";
        monday.kind = "free";
        monday.predicted = new Plan.Predicted();
        List<String> warnings = Engine.validationWarnings(rules, profile, start, bad);
        assertFalse(warnings.isEmpty());
    }
}
