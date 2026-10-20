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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mid-game save with real remaining dates, rather than a fresh-game dorm demonstration. */
public class DormTimingRealPackTest {
    private static final String[] GOALS = {"priestess", "empress", "lovers", "justice", "hermit",
            "strength", "hanged_man", "aeon", "sun"};

    @Test
    public void octoberPartyUpgradesEnterTheAutumnPlanWithoutLosingMustGoals() throws Exception {
        Rules rules = RulesLoader.parse(new String(Files.readAllBytes(
                Paths.get("src/main/assets/p3r/p3r_rules.json")), StandardCharsets.UTF_8));
        Profile profile = new Profile();
        profile.personaMode = Profile.PersonaMode.ALL;
        profile.planDormGardening = true;
        for (int i = 0; i < GOALS.length; i++) {
            int link = rules.linkIndex(GOALS[i]);
            profile.tier[link] = Profile.Tier.MUST;
            profile.order[link] = i;
        }
        for (String partner : Set.of("dorm_yukari", "dorm_fuuka", "dorm_aigis"))
            profile.dormTargets.put(partner, Profile.DormTarget.UPGRADE);
        SimState start = new SimState(rules.blocks.length, rules.activities.length);
        start.slot = RulesFixtures.slot(10, 22, false);
        start.cash = 150_000;
        for (int s = 0; s < 3; s++) start.statPts[s] = rules.stats[s].thresholds[6];
        for (Rules.Link link : rules.links) {
            if (link.kind != Rules.LinkKind.STORY || link.storyRankDay == null) continue;
            for (int rank = 1; rank < link.storyRankDay.length; rank++) {
                if (link.storyRankDay[rank] >= 0 && link.storyRankDay[rank] <= Rules.dayOf(start.slot))
                    start.rank[link.idx] = rank;
            }
        }
        String[] ids = {"magician", "sun", "hanged_man", "chariot", "emperor", "hermit", "hierophant",
                "fortune", "strength", "priestess", "lovers", "justice", "devil", "tower", "star", "moon", "temperance"};
        int[] ranks = {5, 8, 5, 6, 6, 8, 6, 5, 5, 5, 6, 7, 5, 4, 5, 5, 5};
        for (int i = 0; i < ids.length; i++) start.rank[rules.linkIndex(ids[i])] = ranks[i];
        for (int i = 0; i < rules.rescues.length; i++)
            if (rules.rescues[i].toDay < Rules.dayOf(start.slot)) start.rescued |= 1L << i;
        Rules.Block block = rules.blockOn(Rules.dayOf(start.slot));
        if (block != null) start.towerVisits[block.idx] = 1;
        for (Rules.Activity activity : rules.activities)
            if (activity.dormPartnerId() != null && !"dorm_shinjiro".equals(activity.dormPartnerId()))
                start.used[activity.idx] = activity.idx % 3;
        String observed = start.toJson().toString();
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 4_000;
        Plan plan = Engine.plan(rules, profile, start, null, options);
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        assertEquals("forecasting must not invent recorded progress", observed, start.toJson().toString());
        for (String goal : GOALS) assertEquals(goal, 10, plan.summary.endRanks[rules.linkIndex(goal)]);
        Map<String, Integer> upgraded = new HashMap<>();
        Model model = new Model(rules, profile, null);
        SimState state = start;
        for (Plan.Step step : plan.steps) {
            state = Engine.apply(rules, profile, state, Action.parse(rules, step.action)).state;
            for (int[] tracks : model.dormTracks) {
                String partner = rules.activities[tracks[0]].dormPartnerId();
                if (profile.dormTarget(partner) != Profile.DormTarget.UPGRADE) continue;
                boolean complete = tracks.length >= 2;
                for (int activity : tracks) complete &= state.used[activity] >= 3;
                if (complete) upgraded.putIfAbsent(partner, step.slot);
            }
        }
        assertEquals(3, upgraded.size());
        assertTrue("Aigis should no longer wait until January", upgraded.get("dorm_aigis") <= RulesFixtures.slot(11, 1, true));
        assertTrue("Fuuka should gain autumn battle utility", upgraded.get("dorm_fuuka") <= RulesFixtures.slot(11, 30, true));
        assertTrue("Yukari's sparse DVD dates still constrain her", upgraded.get("dorm_yukari") <= RulesFixtures.slot(1, 10, true));
    }
}
