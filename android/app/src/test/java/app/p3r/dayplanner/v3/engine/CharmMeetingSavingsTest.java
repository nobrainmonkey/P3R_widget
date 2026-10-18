package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Charm is an affinity investment only when its discrete future meetings actually decrease. */
public class CharmMeetingSavingsTest {
    @Test
    public void realSunAloneDoesNotInventAffinitySavings() throws Exception {
        Rules rules = RulesLoader.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/p3r/p3r_rules.json")), StandardCharsets.UTF_8));
        Profile profile = goal(rules.linkIndex("sun"), 10);
        SimState state = new SimState(rules.blocks.length, rules.activities.length);
        state.slot = RulesFixtures.slot(9, 14, true);
        state.cash = 100_000;
        Arrays.fill(state.rank, 10);
        state.rank[rules.linkIndex("sun")] = 1;
        state.statPts[0] = rules.stats[0].thresholds[4];
        state.statPts[2] = rules.stats[2].thresholds[6];
        int activity = rules.activityIndex("dorm_yukari_dvd");
        assertEquals("zero-threshold ranks need exactly their rank-up meetings",
                9, heuristic(rules, profile, state).remainingMeetingsWithCharm(state, rules.linkIndex("sun")));
        assertEquals("Charm adds no affinity-investment value to a Sun-only goal",
                scoreWithoutBonus(rules, profile, state, activity), score(rules, profile, state, activity), 0);
    }

    @Test
    public void unselectedPointGatedLinksDoNotCreateACharmInvestment() {
        Rules rules = fixture();
        Profile profile = goal(0, 10);
        SimState state = state(rules);
        state.rank[0] = 1;
        state.rank[1] = 1; // This ordinary link could benefit, but the player did not request it.
        assertEquals(scoreWithoutBonus(rules, profile, state, 0), score(rules, profile, state, 0), 0);
        state.rank[1] = 10;
        assertEquals(scoreWithoutBonus(rules, profile, state, 0), score(rules, profile, state, 0), 0);
    }

    @Test
    public void aRealDiscreteSavedMeetingStillMakesCharmUseful() {
        Rules rules = fixture();
        Profile profile = goal(1, 2);
        SimState state = state(rules);
        state.rank[1] = 1;
        Heuristics h = heuristic(rules, profile, state);
        assertEquals(4, h.remainingMeetings(state, 1, 1));
        assertEquals(3, h.remainingMeetingsWithCharm(state, 1));
        assertTrue("the bonus remains useful for an actual saved meeting",
                score(rules, profile, state, 0) > scoreWithoutBonus(rules, profile, state, 0));

        rules = fixture();
        rules.links[1].threshold[2] = 20;
        assertEquals("a larger affinity multiplier can still save zero whole meetings",
                scoreWithoutBonus(rules, profile, state, 0), score(rules, profile, state, 0), 0);
    }

    @Test
    public void anUnopenedLinkRecomputesItsFutureEstablishingSceneAtTheNewMultiplier() {
        Rules rules = fixture();
        rules.links[1].eventGain[1] = 10;
        Profile profile = goal(1, 2);
        SimState state = state(rules);
        Heuristics h = heuristic(rules, profile, state);
        assertEquals(4, h.remainingMeetings(state, 1, 1));
        assertEquals("one unlock plus two later meetings; old meetsFromOne must not be reused",
                3, h.remainingMeetingsWithCharm(state, 1));
        assertTrue(score(rules, profile, state, 0) > scoreWithoutBonus(rules, profile, state, 0));
    }

    @Test
    public void observedPointsAndReadyCuesAreNotRetroactivelyMultiplied() {
        Rules rules = fixture();
        Profile profile = goal(1, 2);
        SimState state = state(rules);
        state.rank[1] = 1;
        state.pts[1] = 20;
        Heuristics h = heuristic(rules, profile, state);
        assertEquals("twenty observed points stay twenty, so one hangout is still needed",
                2, h.remainingMeetingsWithCharm(state, 1));
        assertEquals(scoreWithoutBonus(rules, profile, state, 0), score(rules, profile, state, 0), 0);
        state.pts[1] = 0;
        state.ready[1] = true;
        assertEquals("the same heuristic must notice the new ready cue", 1, h.remainingMeetingsWithCharm(state, 1));
        assertEquals(scoreWithoutBonus(rules, profile, state, 0), score(rules, profile, state, 0), 0);
        state.ready[1] = false;
        assertEquals("clearing the cue restores the actual affinity gap", 3, h.remainingMeetingsWithCharm(state, 1));
    }

    @Test
    public void personaAndSelectedRouteApplyToFutureGains() {
        Rules rules = fixture();
        rules.links[1].threshold[2] = 40;
        rules.links[1].romanceable = true;
        rules.links[1].eventGainRomance = new int[11];
        rules.links[1].eventGainRomance[1] = 20;
        Profile profile = goal(1, 2);
        profile.personaMode = Profile.PersonaMode.ALL;
        profile.route[1] = Profile.Route.FRIEND;
        SimState state = state(rules);
        assertEquals("Persona and Charm combine for future hangouts", 4,
                heuristic(rules, profile, state).remainingMeetingsWithCharm(state, 1));
        profile.route[1] = Profile.Route.ROMANCE;
        assertEquals("the future establishing scene uses the selected romance answers", 2,
                heuristic(rules, profile, state).remainingMeetingsWithCharm(state, 1));
    }

    @Test
    public void hypotheticalCharmDoesNotBypassReportedOrPermanentHolds() {
        Rules rules = fixture();
        Profile profile = goal(1, 2);
        SimState state = state(rules);
        state.rank[1] = 1;
        state.heldRank[1] = 2;
        Heuristics h = heuristic(rules, profile, state);
        assertEquals(Heuristics.INF, h.remainingMeetingsWithCharm(state, 1));
        assertEquals(scoreWithoutBonus(rules, profile, state, 0), score(rules, profile, state, 0), 0);
        state.heldRank[1] = 0;
        assertEquals("a reported hold being cleared invalidates the cached result", 3, h.remainingMeetingsWithCharm(state, 1));
        rules = fixture();
        Rules.Hold hold = new Rules.Hold();
        hold.rank = 2;
        hold.untilDay = -1;
        rules.links[1].holds.add(hold);
        assertEquals(Heuristics.INF, heuristic(rules, profile, state).remainingMeetingsWithCharm(state, 1));
    }

    private static Rules fixture() {
        Rules rules = RulesFixtures.small();
        rules.blocks = new Rules.Block[0];
        rules.rescues = new Rules.Rescue[0];
        for (Rules.Day day : rules.days) if (day != null) {
            day.dayLock = day.eveLock = null;
            day.tartarus = false;
            day.block = -1;
        }
        Rules.Link sun = RulesFixtures.link(0, "sun", 19, "太阳", "神木秋成", Rules.LinkKind.MANUAL, false, "神社");
        Rules.Link ordinary = RulesFixtures.link(1, "ordinary", 1, "魔术师", "同伴", Rules.LinkKind.MANUAL, false, "学校");
        for (Rules.Link link : new Rules.Link[]{sun, ordinary}) {
            link.avail.set(rules.firstDay, rules.lastDay + 1);
            link.unlock.steps.add(RulesFixtures.step(link.id + ".open", "对话", false, Rules.StepDays.LINK_AVAIL, 0, 0, true));
        }
        Arrays.fill(ordinary.threshold, 30);
        ordinary.threshold[0] = ordinary.threshold[1] = 0;
        rules.links = new Rules.Link[]{sun, ordinary};
        rules.activities = new Rules.Activity[]{RulesFixtures.activity(0, "charm", "魅力练习", Rules.ActivityKind.STAT, false, true, 100, 1, 4)};
        return rules;
    }

    private static Profile goal(int link, int target) {
        Profile profile = new Profile();
        profile.otherLinks = false;
        profile.goalBuffer = false;
        profile.tier[link] = Profile.Tier.MUST;
        profile.target[link] = target;
        profile.towerVisitsPerBlock = profile.towerCapPerBlock = 0;
        return profile;
    }

    private static SimState state(Rules rules) {
        SimState state = new SimState(rules.blocks.length, rules.activities.length);
        state.slot = Rules.slot(rules.firstDay, true);
        state.cash = 100_000;
        state.statPts[0] = rules.stats[0].thresholds[6];
        state.statPts[2] = rules.stats[2].thresholds[6];
        return state;
    }

    private static Heuristics heuristic(Rules rules, Profile profile, SimState state) {
        Model model = new Model(rules, profile, null);
        return new Heuristics(model, Kernel.normalize(model, state), null);
    }

    private static double score(Rules rules, Profile profile, SimState state, int activity) {
        Heuristics h = heuristic(rules, profile, state);
        h.prepare(state);
        return h.score(state, Kernel.encode(Action.activity(activity)));
    }

    private static double scoreWithoutBonus(Rules rules, Profile profile, SimState state, int activity) {
        Profile without = profile.copy();
        without.charmBonus = false;
        return score(rules, without, state, activity);
    }
}
