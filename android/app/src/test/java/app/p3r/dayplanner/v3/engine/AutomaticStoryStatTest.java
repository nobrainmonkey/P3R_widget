package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.List;

/** Story points belong to completed daytime slots, independently of viewing or acknowledging them. */
public class AutomaticStoryStatTest {
    @Test
    public void daytimeAwardsOnceAndPredictionMatchesTheVisibleRankChange() {
        Rules rules = oneStoryDay();
        Profile profile = new Profile();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(21, false));
        start.statPts[RulesFixtures.ACADEMICS] = 17;
        Engine.Applied completed = Engine.apply(rules, profile, start, Action.free());
        assertEquals(Rules.slot(21, true), completed.state.slot);
        assertEquals(20, completed.state.statPts[RulesFixtures.ACADEMICS]);
        assertEquals(3, completed.predicted.statDelta[RulesFixtures.ACADEMICS]);
        assertEquals(2, completed.predicted.statLvAfter[RulesFixtures.ACADEMICS]);
        assertEquals(17, start.statPts[RulesFixtures.ACADEMICS]);
        assertEquals(20, Engine.apply(rules, profile, completed.state, Action.free())
                .state.statPts[RulesFixtures.ACADEMICS]);
    }

    @Test
    public void rawPointsRemainConsistentWithActivityRewardsAboveTheVisibleCap() {
        Rules rules = oneStoryDay();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(21, false));
        start.statPts[RulesFixtures.ACADEMICS] = rules.stats[RulesFixtures.ACADEMICS].thresholds[6];
        Engine.Applied completed = Engine.apply(rules, new Profile(), start, Action.free());
        assertEquals(start.statPts[RulesFixtures.ACADEMICS] + 3, completed.state.statPts[RulesFixtures.ACADEMICS]);
        assertEquals(3, completed.predicted.statDelta[RulesFixtures.ACADEMICS]);
        assertEquals(-1, completed.predicted.statLvAfter[RulesFixtures.ACADEMICS]);
    }

    @Test
    public void advancingAcrossSixSummerSchoolDaysAddsExactlyEighteen() {
        Rules rules = summer();
        Profile profile = new Profile();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(8, 10, false));
        start.statPts[RulesFixtures.ACADEMICS] = 17;
        SimState end = Engine.advanceTo(rules, profile, start, RulesFixtures.slot(8, 16, false));
        assertEquals(35, end.statPts[RulesFixtures.ACADEMICS]);
        assertEquals(17, start.statPts[RulesFixtures.ACADEMICS]);
        assertEquals(35, Engine.advanceTo(rules, profile, end, end.slot).statPts[RulesFixtures.ACADEMICS]);
    }

    @Test
    public void observedEveningStartNeverReceivesEarlierDaytimePointsAgain() {
        Rules rules = summer();
        Profile profile = new Profile();
        SimState firstEvening = RulesFixtures.newGame(rules, RulesFixtures.slot(8, 10, true));
        firstEvening.statPts[RulesFixtures.ACADEMICS] = 17;
        SimState end = Engine.advanceTo(rules, profile, firstEvening, RulesFixtures.slot(8, 16, false));
        assertEquals("only the five later school days are future", 32, end.statPts[RulesFixtures.ACADEMICS]);
        SimState lastEvening = RulesFixtures.newGame(rules, RulesFixtures.slot(8, 15, true));
        lastEvening.statPts[RulesFixtures.ACADEMICS] = 17;
        assertEquals(17, Engine.advanceTo(rules, profile, lastEvening,
                RulesFixtures.slot(8, 16, false)).statPts[RulesFixtures.ACADEMICS]);
    }

    @Test
    public void outcomeReplayAwardsTheMandatoryStoryOnceEvenIfTheMainActionWasSkipped() {
        Rules rules = oneStoryDay();
        Profile profile = new Profile();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(21, false));
        SimState completed = Engine.applyOutcome(rules, profile, start, Action.free(), Outcome.of(Outcome.Code.SKIPPED));
        assertEquals(3, completed.statPts[RulesFixtures.ACADEMICS]);
        assertEquals(3, Engine.applyOutcome(rules, profile, completed, Action.free(),
                Outcome.of(Outcome.Code.AS_PREDICTED)).statPts[RulesFixtures.ACADEMICS]);
    }

    @Test
    public void independentValidatorUsesCompletedStoryPointsForTheFollowingUnlockGate() {
        Rules rules = oneStoryDay();
        Rules.Link gated = rules.links[RulesFixtures.S_MAGICIAN];
        gated.unlock.statLv.put(RulesFixtures.ACADEMICS, 2);
        gated.avail.set(22);
        Profile profile = new Profile();
        profile.otherLinks = false;
        profile.tier[RulesFixtures.S_MAGICIAN] = Profile.Tier.MUST;
        SimState start = RulesFixtures.newGame(rules, Rules.slot(21, false));
        start.statPts[RulesFixtures.ACADEMICS] = 17;
        int[] codes = new int[rules.lastSlot() - start.slot + 1];
        codes[Rules.slot(22, false) - start.slot] = Kernel.code(Kernel.T_UNLOCK, RulesFixtures.S_MAGICIAN, 0);
        Plan plan = assemble(rules, profile, start, codes);
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        assertEquals(3, plan.at(start.slot).predicted.statDelta[RulesFixtures.ACADEMICS]);
        assertEquals(2, plan.at(start.slot).predicted.statLvAfter[RulesFixtures.ACADEMICS]);
        rules.days[21].automaticStatGain = new int[0];
        plan.at(start.slot).predicted.statDelta[RulesFixtures.ACADEMICS] = 0;
        plan.at(start.slot).predicted.statLvAfter[RulesFixtures.ACADEMICS] = -1;
        errors = Engine.validate(rules, profile, start, plan);
        assertFalse("the independent replay must reject an unlock without its actual stat prerequisite", errors.isEmpty());
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("magician needs stat 0 Lv2")));
    }

    @Test
    public void validatorRejectsInventedLockedStoryRewards() {
        Rules rules = oneStoryDay();
        Profile profile = new Profile();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(21, false));
        Plan plan = assemble(rules, profile, start, new int[rules.lastSlot() - start.slot + 1]);
        plan.at(start.slot).predicted.statDelta[RulesFixtures.ACADEMICS] = 6;
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("predicted story stat gain")));
    }

    @Test
    public void lockedDayLabelsKeepTheStoryNoteAndExplainOnlyItsDaytimeReward() {
        Rules rules = oneStoryDay();
        rules.days[21].eveLock = "story";
        rules.days[21].noteZh = "今天有学校剧情";
        Profile profile = new Profile();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(21, false));
        Plan plan = assemble(rules, profile, start, new int[rules.lastSlot() - start.slot + 1]);
        Plan.Step daytime = plan.at(Rules.slot(21, false));
        // The automatic gain is said once, as the subtitle's second line; the first stays the widget's lock cue.
        assertEquals("今天有学校剧情", daytime.detailZh);
        assertEquals("这段时间不能自由行动\n剧情自动获得：学力 +3", daytime.subtitleZh);
        Plan.Step evening = plan.at(Rules.slot(21, true));
        assertEquals("今天有学校剧情", evening.detailZh);
        assertEquals(0, evening.predicted.statDelta[RulesFixtures.ACADEMICS]);
    }

    private static Rules oneStoryDay() {
        Rules rules = RulesFixtures.small();
        rules.days[21].automaticStatGain = new int[] {3, 0, 0};
        return rules;
    }

    private static Rules summer() {
        Rules rules = RulesFixtures.fullYear();
        for (int dom = 10; dom <= 15; dom++) {
            Rules.Day day = rules.days[RulesFixtures.day(8, dom)];
            day.dayLock = "summer_classes";
            day.automaticStatGain = new int[] {3, 0, 0};
        }
        return rules;
    }

    private static Plan assemble(Rules rules, Profile profile, SimState observed, int[] codes) {
        Model model = new Model(rules, profile, null);
        SimState start = Kernel.normalize(model, observed);
        return Planner.assemble(model, new Heuristics(model, start, null), start, codes);
    }
}
