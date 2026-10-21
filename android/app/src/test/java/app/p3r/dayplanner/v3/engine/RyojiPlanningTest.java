package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** The real calendar must reserve Ryoji's free-day meetings without inventing automatic scenes. */
public class RyojiPlanningTest {
    private static Rules rules;
    private static Rules.Episode ryoji;

    @BeforeClass public static void load() throws Exception {
        rules = RulesLoader.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/p3r/p3r_rules.json")), StandardCharsets.UTF_8));
        for (Rules.Episode track : rules.episodes) if (track.id.equals("ryoji")) ryoji = track;
        assertTrue("Ryoji must be selectable", ryoji != null);
    }

    @Test public void novemberEleventhPlansTheSecondAndFourthScenes() {
        SimState start = start(11, 11, 0);
        Plan plan = plan(start);
        List<Plan.Step> meetings = meetings(plan);
        assertEquals(2, meetings.size());
        assertTrue(ryoji.allows(Rules.dayOf(meetings.get(0).slot), 1));
        assertEquals("羁绊剧情 · 望月绫时（第 2 话）", meetings.get(0).titleZh);
        assertEquals(RulesFixtures.slot(12, 1, false), meetings.get(1).slot);
        assertEquals("羁绊剧情 · 望月绫时（第 4 话）", meetings.get(1).titleZh);
        assertTrue(meetings.get(1).detailZh.contains("11月18日"));
        assertEquals(0, start.episodes[ryoji.idx]);
    }

    @Test public void midSaveAfterTheTripKeepsOnlyTheRemainingMeeting() {
        SimState start = start(11, 20, 1);
        List<Plan.Step> meetings = meetings(plan(start));
        assertEquals(1, meetings.size());
        assertEquals(RulesFixtures.slot(12, 1, false), meetings.get(0).slot);
        assertEquals("羁绊剧情 · 望月绫时（第 4 话）", meetings.get(0).titleZh);
        assertEquals(1, start.episodes[ryoji.idx]);
    }

    @Test public void missedSecondSceneIsNotSilentlyCreditedOnALateStart() {
        SimState start = start(11, 20, 0);
        Plan plan = plan(start);
        assertTrue(meetings(plan).isEmpty());
        assertTrue(plan.summary.notesZh.toString(), plan.summary.notesZh.stream().anyMatch(s -> s.contains("望月绫时") && s.contains("第 2 话已经错过")));
        assertEquals(0, start.episodes[ryoji.idx]);
    }

    private static SimState start(int month, int day, int watched) {
        SimState state = RulesFixtures.newGame(rules, RulesFixtures.slot(month, day, false));
        Arrays.fill(state.rank, 10);
        state.statPts = new int[]{230, 100, 80};
        state.cash = 300_000;
        state.episodes = new int[rules.episodes.length];
        state.episodes[ryoji.idx] = watched;
        return state;
    }

    private static Plan plan(SimState start) {
        Profile profile = new Profile();
        profile.episodeTracks = new String[]{"ryoji"};
        profile.towerVisitsPerBlock = 0;
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 5_000;
        Plan plan = Engine.plan(rules, profile, start, null, options);
        assertTrue(Engine.validate(rules, profile, start, plan).toString(), Engine.validate(rules, profile, start, plan).isEmpty());
        return plan;
    }

    private static List<Plan.Step> meetings(Plan plan) {
        return plan.steps.stream().filter(s -> s.action.equals("episode:ryoji")).collect(Collectors.toList());
    }
}
