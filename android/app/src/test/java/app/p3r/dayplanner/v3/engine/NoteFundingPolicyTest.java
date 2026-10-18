package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.Arrays;

/** Actual near-term Social Link gaps can justify work without making surplus wealth a goal. */
public class NoteFundingPolicyTest {
    private static final int LINK = RulesFixtures.S_MAGICIAN;

    private static Rules oneLastMeeting() {
        Rules rules = RulesFixtures.small();
        rules.rescues = new Rules.Rescue[0];
        rules.links[LINK].avail.clear();
        rules.links[LINK].avail.set(35);
        rules.links[LINK].invite.clear();
        rules.links[LINK].threshold[10] = 10;
        rules.datingNote = RulesFixtures.datingNote(34);
        rules.datingNote.cost = 2_500;
        rules.datingNote.maxUses = Rules.DatingNote.UNLIMITED;
        return rules;
    }

    private static Profile ordinaryLinks() {
        Profile p = new Profile();
        p.charmBonus = false;
        p.towerVisitsPerBlock = 0;
        p.towerCapPerBlock = 0;
        return p;
    }

    private static SimState beforeWork(Rules rules, long cash) {
        SimState s = RulesFixtures.newGame(rules, Rules.slot(33, true));
        Arrays.fill(s.rank, 10);
        for (int stat = 0; stat < rules.stats.length; stat++) s.statPts[stat] = rules.stats[stat].thresholds[6];
        s.rank[LINK] = 9;
        s.pts[LINK] = 0;
        s.cash = cash;
        return s;
    }

    private static Plan plan(Rules rules, Profile p, SimState s, long budget) {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = budget;
        Plan plan = Engine.plan(rules, p, s, null, options);
        assertTrue(Engine.validate(rules, p, s, plan).toString(), Engine.validate(rules, p, s, plan).isEmpty());
        return plan;
    }

    private static int count(Plan plan, String kind) {
        int n = 0;
        for (Plan.Step step : plan.steps) if (kind.equals(step.kind)) n++;
        return n;
    }

    @Test
    public void eveningWorkFundsAnOrdinaryLinksNoteBeforeItsLastMeeting() {
        for (long budget : new long[]{0, 1_000}) {
            Rules rules = oneLastMeeting();
            Profile p = ordinaryLinks();
            SimState state = beforeWork(rules, 0);
            assertFalse("this is an ordinary link, not an explicit goal", p.hasAnyGoal());
            Plan plan = plan(rules, p, state, budget);
            assertEquals("job", plan.at(Rules.slot(33, true)).kind);
            assertEquals("note:magician", plan.at(Rules.slot(34, true)).action);
            assertEquals(10, plan.summary.endRanks[LINK]);
            assertEquals("only the one useful note needs funding", 1, count(plan, "job"));
            assertEquals(1, count(plan, "note"));
        }
    }

    @Test
    public void noteBudgetIsSpendableAndDoesNotPenalizeItsOwnPurchase() {
        Rules rules = oneLastMeeting();
        rules.datingNote.fromDay = 33;
        Profile p = ordinaryLinks();
        p.towerCapPerBlock = 1;
        SimState state = beforeWork(rules, 2_500);
        Plan plan = plan(rules, p, state, 0);
        assertEquals("the note's own budget must not make spare exploration beat it",
                "note:magician", plan.steps.get(0).action);
        assertEquals(0, count(plan, "job"));
        assertEquals(10, plan.summary.endRanks[LINK]);
    }

    @Test
    public void aLockedPurchaseNightCannotJustifyTheOnlyEarlierShift() {
        Rules rules = oneLastMeeting();
        rules.activities[RulesFixtures.A_JOB].fixedDays = new int[]{33};
        rules.days[34].eveLock = "story";
        Profile p = ordinaryLinks();
        SimState state = beforeWork(rules, 0);
        Plan plan = plan(rules, p, state, 1_000);
        assertEquals("there is no night after work to use a note before the last meeting",
                "free", plan.steps.get(0).kind);
        assertEquals(0, count(plan, "job"));
        assertEquals(0, count(plan, "note"));
        assertEquals(9, plan.summary.endRanks[LINK]);
    }

    @Test
    public void workAndANoteCannotShareTheLastEveningBeforeAMeeting() {
        Rules rules = oneLastMeeting();
        rules.activities[RulesFixtures.A_JOB].fixedDays = new int[]{33};
        rules.datingNote.fromDay = 33;
        rules.links[LINK].avail.clear();
        rules.links[LINK].avail.set(34);
        Profile p = ordinaryLinks();
        SimState state = beforeWork(rules, 0);
        Plan plan = plan(rules, p, state, 1_000);
        assertEquals("earning tonight cannot also pay for a note tonight", "free", plan.steps.get(0).kind);
        assertEquals(0, count(plan, "job"));
        assertEquals(0, count(plan, "note"));
        assertEquals(9, plan.summary.endRanks[LINK]);
    }

    @Test
    public void aSmallGapFundsOneNoteAndStopsAfterItIsFilled() {
        Rules rules = oneLastMeeting();
        Profile p = ordinaryLinks();
        SimState state = beforeWork(rules, 0);
        state.pts[LINK] = 5;
        Plan plan = plan(rules, p, state, 1_000);
        assertEquals(1, count(plan, "job"));
        assertEquals(1, count(plan, "note"));
        assertEquals(10, plan.summary.endRanks[LINK]);
        assertEquals(0, plan.summary.endCash);
    }

    @Test
    public void readyCueDoesNotCreateAnImaginaryNoteExpense() {
        Rules rules = oneLastMeeting();
        Profile p = ordinaryLinks();
        SimState state = beforeWork(rules, 0);
        state.ready[LINK] = true;
        Plan plan = plan(rules, p, state, 1_000);
        assertEquals(0, count(plan, "job"));
        assertEquals(0, count(plan, "note"));
        assertEquals(10, plan.summary.endRanks[LINK]);
    }

    @Test
    public void theLastWorkingNightCanFundTheGapAfterAnAlreadyReadyMeeting() {
        Rules rules = oneLastMeeting();
        rules.activities[RulesFixtures.A_JOB].fixedDays = new int[]{33};
        rules.datingNote.fromDay = 35;
        rules.links[LINK].avail.clear();
        rules.links[LINK].avail.set(34);
        rules.links[LINK].avail.set(36);
        rules.links[LINK].eventGain[9] = 0;
        Profile p = ordinaryLinks();
        SimState state = beforeWork(rules, 0);
        state.rank[LINK] = 8;
        state.ready[LINK] = true;
        Plan plan = plan(rules, p, state, 1_000);
        assertEquals("cash must be earned before the ready meeting, while work is available",
                "job", plan.steps.get(0).kind);
        assertEquals("meet:magician", plan.at(Rules.slot(34, false)).action);
        assertEquals("note:magician", plan.at(Rules.slot(35, true)).action);
        assertEquals(10, plan.summary.endRanks[LINK]);
        assertEquals(1, count(plan, "job"));
        assertEquals(1, count(plan, "note"));
    }

    @Test
    public void aPredictedNextRankWithoutAnotherMeetingDoesNotJustifySaving() {
        Rules rules = oneLastMeeting();
        rules.activities[RulesFixtures.A_JOB].fixedDays = new int[]{33};
        rules.links[LINK].eventGain[9] = 0;
        Profile p = ordinaryLinks();
        SimState state = beforeWork(rules, 0);
        state.rank[LINK] = 8;
        state.ready[LINK] = true;
        Plan plan = plan(rules, p, state, 1_000);
        assertEquals(0, count(plan, "job"));
        assertEquals(0, count(plan, "note"));
        assertEquals(9, plan.summary.endRanks[LINK]);
    }

    @Test
    public void unavailableOrDisabledNotesDoNotJustifyWork() {
        for (int reason = 0; reason < 4; reason++) {
            Rules rules = oneLastMeeting();
            Profile p = ordinaryLinks();
            SimState state = beforeWork(rules, 0);
            if (reason == 0) p.datingNotes = false;
            if (reason == 1) {
                rules.datingNote.maxUses = 1;
                state.notesUsed = 1;
            }
            if (reason == 2) p.otherLinks = false;
            if (reason == 3) rules.datingNote.fromDay = 50; // the last meeting has already passed
            Plan plan = plan(rules, p, state, 1_000);
            assertEquals("reason " + reason, 0, count(plan, "job"));
            assertEquals("reason " + reason, 0, count(plan, "note"));
        }
    }

    @Test
    public void aWantedDaytimeMeetingStillWinsOverFinancingANote() {
        Rules rules = oneLastMeeting();
        rules.activities[RulesFixtures.A_JOB].day = true;
        rules.links[LINK].avail.set(33);
        int other = RulesFixtures.S_EMPEROR;
        rules.links[other].avail.clear();
        rules.links[other].avail.set(36);
        rules.links[other].threshold[10] = 10;
        Profile p = ordinaryLinks();
        SimState state = beforeWork(rules, 0);
        state.slot = Rules.slot(33, false);
        state.ready[LINK] = true;
        state.rank[other] = 9;
        Plan plan = plan(rules, p, state, 1_000);
        assertEquals("meet:magician", plan.steps.get(0).action);
        assertEquals(10, plan.summary.endRanks[LINK]);
        assertEquals("the other link's note still gets financed in an evening", 10, plan.summary.endRanks[other]);
        assertEquals(1, count(plan, "job"));
    }
}
