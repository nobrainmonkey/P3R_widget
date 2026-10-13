package app.p3r.dayplanner.v3.engine;

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
        Plan.Step thursday = bad.at(Rules.slot(20, true)); // the very first evening: no cash yet
        thursday.action = "act:arcade_charm";
        thursday.kind = "stat";
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

    @Test
    public void freeSlotWithALegalActionIsAWarning() throws JSONException {
        Plan bad = copy();
        Plan.Step s = bad.at(Rules.slot(26, true)); // study is always possible in the evening
        Plan.Step studyLike = s;
        studyLike.action = "free";
        studyLike.kind = "free";
        studyLike.predicted = new Plan.Predicted();
        // Cash after a FREE step is not checked, but later steps now disagree with the recount when
        // this slot earned money; the warning must be reported either way.
        List<String> warnings = Engine.validationWarnings(rules, profile, start, bad);
        assertFalse(warnings.isEmpty());
    }
}
