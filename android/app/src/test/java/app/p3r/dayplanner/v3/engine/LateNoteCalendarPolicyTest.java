package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Spare holiday days cannot replace the school days an evening affinity action saves. */
public class LateNoteCalendarPolicyTest {
    private static final int FIRST = RulesFixtures.S_MAGICIAN;
    private static final int SECOND = RulesFixtures.S_EMPEROR;
    private static final int START = 33;

    private static Rules calendar() {
        Rules rules = RulesFixtures.small();
        rules.firstDay = START;
        rules.lastDay = 43;
        rules.rescues = new Rules.Rescue[0];
        for (int day = START; day <= rules.lastDay; day++) {
            rules.days[day].type = day >= 34 && day <= 36
                    ? Rules.DayType.SCHOOL : Rules.DayType.HOLIDAY;
            rules.days[day].dayLock = null;
            rules.days[day].eveLock = null;
        }
        // Only three school days remain, followed by seven holiday daytimes (37 through 43).
        for (int link : new int[]{FIRST, SECOND}) {
            rules.links[link].avail.clear();
            rules.links[link].avail.set(34, 37);
            rules.links[link].invite.clear();
            rules.links[link].threshold[10] = 30;
        }
        rules.datingNote = RulesFixtures.datingNote(START);
        rules.datingNote.maxUses = Rules.DatingNote.UNLIMITED;
        return rules;
    }

    private static Profile profile() {
        Profile p = new Profile();
        p.charmBonus = false;
        p.goalBuffer = false;
        p.towerVisitsPerBlock = 0;
        p.towerCapPerBlock = 0;
        return p;
    }

    private static SimState start(Rules rules) {
        SimState state = RulesFixtures.newGame(rules, Rules.slot(START, true));
        Arrays.fill(state.rank, 10);
        for (int link : new int[]{FIRST, SECOND}) {
            state.rank[link] = 9;
            state.pts[link] = 29;
            state.unlockStep[link] = rules.links[link].unlock.steps.size();
        }
        for (int stat = 0; stat < rules.stats.length; stat++) {
            state.statPts[stat] = rules.stats[stat].thresholds[6];
        }
        state.cash = 100_000;
        return state;
    }

    private static Plan basePlan(Rules rules, Profile p, SimState state) {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 0;
        Plan plan = Engine.plan(rules, p, state, null, options);
        List<String> errors = Engine.validate(rules, p, state, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        List<String> warnings = Engine.validationWarnings(rules, p, state, plan);
        assertTrue(warnings.toString(), warnings.isEmpty());
        return plan;
    }

    private static int count(Plan plan, String kind) {
        int count = 0;
        for (Plan.Step step : plan.steps) if (kind.equals(step.kind)) count++;
        return count;
    }

    private static double noteScore(Rules rules, Profile p, SimState state) {
        Heuristics h = new Heuristics(new Model(rules, p, null), state, null);
        h.prepare(state);
        return h.score(state, Kernel.encode(Action.note(FIRST)));
    }

    @Test
    public void anEveningNotePreservesTheThreeSharedSchoolDaysDespiteSevenSpareHolidays() {
        for (Profile.Tier tier : new Profile.Tier[]{Profile.Tier.NONE, Profile.Tier.WANT, Profile.Tier.MUST}) {
            Rules rules = calendar();
            // Tonight is the last opportunity to prepare affinity before the school meetings.
            rules.days[34].eveLock = "story";
            rules.days[35].eveLock = "story";
            Profile p = profile();
            p.towerCapPerBlock = 1; // A useful note must beat optional exploration tonight.
            for (int link : new int[]{FIRST, SECOND}) {
                p.tier[link] = tier;
                p.target[link] = 10;
            }
            SimState state = start(rules);
            Plan plan = basePlan(rules, p, state);
            assertEquals("tier " + tier + ": four meetings cannot fit into three school days",
                    "note", plan.steps.get(0).kind);
            assertEquals("tier " + tier, 10, plan.summary.endRanks[FIRST]);
            assertEquals("tier " + tier, 10, plan.summary.endRanks[SECOND]);
            assertEquals("one night replaces one affinity-only daytime", 1, count(plan, "note"));
            assertEquals(0, count(plan, "job"));
        }
    }

    @Test
    public void closingAOnePointGapValuesTheWholeSavedMeetingRatherThanOneTenthOfIt() {
        Rules rules = calendar();
        rules.datingNote.gain = 1;
        Profile p = profile();
        SimState crossing = start(rules);
        SimState partial = start(rules);
        partial.pts[FIRST] = 28;
        double savedMeeting = noteScore(rules, p, crossing);
        double partialProgress = noteScore(rules, p, partial);
        assertTrue("multiple notes may still make useful finite progress", partialProgress > 0);
        assertTrue("the threshold crossing saves a full daytime meeting",
                savedMeeting > 2 * partialProgress);
    }

    @Test
    public void twoNotesCanFillAGapEvenWhenTheFirstDoesNotYetSaveAMeeting() {
        Rules rules = calendar();
        rules.datingNote.gain = 3;
        rules.links[FIRST].avail.clear();
        rules.links[FIRST].avail.set(35);
        Profile p = profile();
        SimState state = start(rules);
        state.rank[SECOND] = 10;
        state.pts[FIRST] = 25;

        Model model = new Model(rules, p, null);
        Heuristics h = new Heuristics(model, state, null);
        SimState afterOne = Engine.apply(rules, p, state, Action.note(FIRST)).state;
        assertEquals("the first three points alone do not remove an ordinary meeting",
                h.remainingMeetings(state, FIRST, 1), h.remainingMeetings(afterOne, FIRST, 1));
        assertTrue(noteScore(rules, p, state) > 0);

        Plan plan = basePlan(rules, p, state);
        assertEquals("note:magician", plan.steps.get(0).action);
        assertEquals("note:magician", plan.at(Rules.slot(34, true)).action);
        assertEquals(2, count(plan, "note"));
        assertEquals(10, plan.summary.endRanks[FIRST]);
        assertEquals(100_000 - 2L * rules.datingNote.cost, plan.summary.endCash);
        assertEquals(0, count(plan, "job"));
    }

    @Test
    public void readyOrMaxedLinksDoNotSpendNotesDespiteHavingCashAndSpareEvenings() {
        for (boolean maxed : new boolean[]{false, true}) {
            Rules rules = calendar();
            SimState state = start(rules);
            for (int link : new int[]{FIRST, SECOND}) {
                if (maxed) state.rank[link] = 10;
                else state.ready[link] = true;
            }
            Plan plan = basePlan(rules, profile(), state);
            assertEquals(0, count(plan, "note"));
            assertEquals(0, count(plan, "job"));
            assertEquals(state.cash, plan.summary.endCash);
        }
    }

    @Test
    public void affinityWithoutAnyFutureMeetingDoesNotBurnSpareEveningsOrCash() {
        Rules rules = calendar();
        for (int link : new int[]{FIRST, SECOND}) rules.links[link].avail.clear();
        SimState state = start(rules);
        Plan plan = basePlan(rules, profile(), state);
        assertEquals("free", plan.steps.get(0).kind);
        assertEquals(0, count(plan, "note"));
        assertEquals(0, count(plan, "job"));
        assertEquals(state.cash, plan.summary.endCash);
        assertEquals(9, plan.summary.endRanks[FIRST]);
        assertEquals(9, plan.summary.endRanks[SECOND]);
    }
}
