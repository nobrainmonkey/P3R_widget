package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Integration with the real rule pack {@code assets/p3r/p3r_rules.json} (built by the rules module).
 * Skipped while the pack or its loader is not available in this checkout.
 *
 * <p>Prints, per scenario: plan span, useful actions, maxed links, elapsed time and the first 30
 * days of the plan in Chinese. Asserts full coverage, a clean independent validation and useful
 * actions until the last two weeks of January.
 */
public class PlanRealPackTest {
    private static Rules rules;

    @BeforeClass
    public static void loadPack() throws Exception {
        if (Boolean.getBoolean("p3r.rules.synthetic")) {
            // Developer switch: exercise the same scenarios on the synthetic year.
            rules = RulesFixtures.fullYear();
            return;
        }
        File pack = findPack();
        Assume.assumeTrue("rule pack not built yet", pack != null);
        String json = new String(Files.readAllBytes(pack.toPath()), StandardCharsets.UTF_8);
        try {
            rules = RulesLoader.parse(json);
        } catch (UnsupportedOperationException notYet) {
            Assume.assumeTrue("RulesLoader not implemented in this checkout", false);
        }
    }

    private static File findPack() {
        String override = System.getProperty("p3r.rules.path");
        String[] candidates = {
                override,
                "../app/src/main/assets/p3r/p3r_rules.json",
                "src/main/assets/p3r/p3r_rules.json",
                "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"};
        for (String c : candidates) {
            if (c == null) continue;
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    public void earlyGameEmperorMustPlusOthers() {
        int emperor = link("emperor");
        Profile profile = new Profile();
        profile.tier[emperor] = Profile.Tier.MUST;
        SimState start = newGame(dayOf(4, 21));
        Plan plan = run("早期 · 4月21日新游戏 · 必须：皇帝 + 其余社群", profile, start);
        assertEquals("皇帝 must reach MAX", 10, plan.summary.endRanks[emperor]);
    }

    @Test
    public void midGameOctober22() {
        Profile profile = new Profile();
        SimState start = newGame(dayOf(10, 22));
        start.cash = 1_000;
        start.cashExact = true;
        setStat(start, "academics", 4);
        setStat(start, "charm", 3);
        setStat(start, "courage", 4);
        setRank(start, "magician", 2);
        setRank(start, "sun", 7);
        setRank(start, "hanged_man", 4);
        setRank(start, "chariot", 5);
        setRank(start, "emperor", 5);
        setRank(start, "hermit", 4);
        setRank(start, "hierophant", 4);
        setRank(start, "fortune", 3);
        setRank(start, "strength", 3);
        int hierophant = link("hierophant");
        for (int i = 0; i < rules.rescues.length; i++) {
            if (rules.rescues[i].link == hierophant) start.rescued |= 1L << i;
        }
        Rules.Block block = rules.blockOn(start.slot >> 1);
        if (block != null) start.towerVisits[block.idx] = 1;
        Plan plan = run("中期 · 10月22日 · 魔术师 2、太阳 7、1,000 日元、魅力 Lv.3", profile, start);
        assertTrue(plan.summary.endRanks[link("magician")] > 2);
    }

    @Test
    public void newGameWithPersonaBonus() {
        Profile profile = new Profile();
        profile.personaBonus = true;
        SimState start = newGame(dayOf(4, 21));
        Plan plan = run("新游戏 · 携带同阿尔卡那人格面具（×1.51）· 全部社群", profile, start);
        System.out.println("benchmark: legacy guide maxes 22/22 with matching Personas; this plan maxes "
                + PlanReport.maxedLinks(rules, plan) + "/" + rules.links.length);
    }

    /** Every multi-step chain (non-consuming side steps, REPORT §0) completes in a new game. */
    @Test
    public void multiStepUnlocksComplete() {
        Profile profile = new Profile();
        SimState start = newGame(dayOf(4, 21));
        Plan plan = Engine.plan(rules, profile, start, null, new Engine.Options());
        assertTrue(Engine.validate(rules, profile, start, plan).isEmpty());
        int sides = 0;
        for (Plan.Step s : plan.steps) sides += s.side.size();
        System.out.println("side steps in the new-game plan: " + sides);
        for (String id : new String[]{"justice", "hanged_man", "devil", "sun", "tower", "moon"}) {
            assertTrue(id + " never opened", plan.summary.endRanks[link(id)] >= 1);
        }
    }

    /** Devil on the real pack: two payments beside other evening actions, then the paid opening. */
    @Test
    public void devilChainOnTheRealPack() {
        int devil = link("devil");
        Profile profile = new Profile();
        SimState s = newGame(dayOf(6, 1));
        setStat(s, "charm", 4);
        setRank(s, "hermit", 4);
        s.cash = 40_000;
        Rules.Link d = rules.links[devil];
        int day = d.avail.nextSetBit(dayOf(6, 1));
        s = Engine.advanceTo(rules, profile, s, Rules.slot(day, true));
        Action study = Action.activity(rules.activity("study_room").idx);
        Engine.Applied first = Engine.apply(rules, profile, s, study);
        assertEquals(java.util.Collections.singletonList("unlock:devil:0"), first.side);
        assertEquals(20_000, first.state.cash);
        int day2 = d.avail.nextSetBit(day + 1);
        Engine.Applied second = Engine.apply(rules, profile, Engine.advanceTo(rules, profile, first.state, Rules.slot(day2, true)), study);
        assertEquals(java.util.Collections.singletonList("unlock:devil:1"), second.side);
        int day3 = d.avail.nextSetBit(day2 + 1);
        SimState last = Engine.advanceTo(rules, profile, second.state, Rules.slot(day3, true));
        assertTrue(Engine.legalActions(rules, profile, last).contains(Action.unlock(devil, 2)));
        assertEquals(1, Engine.apply(rules, profile, last, Action.unlock(devil, 2)).state.rank[devil]);
    }

    // ------------------------------------------------------------------ helpers

    private Plan run(String title, Profile profile, SimState start) {
        Engine.plan(rules, profile, start, null, null); // JIT warm-up
        long t0 = System.nanoTime();
        Plan plan = Engine.plan(rules, profile, start, null, new Engine.Options());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        PlanReport.print(title, rules, plan, 30);
        System.out.println("elapsed " + ms + " ms (default options)");
        assertEquals(rules.lastSlot() - start.slot + 1, plan.steps.size());
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        List<String> warnings = Engine.validationWarnings(rules, profile, start, plan);
        assertTrue(warnings.toString(), warnings.isEmpty());
        int lastTwoWeeks = Rules.slot(rules.lastDay - 13, false);
        assertTrue("last useful action " + PlanReport.date(rules, plan.summary.lastUsefulSlot),
                plan.summary.lastUsefulSlot >= lastTwoWeeks);
        return plan;
    }

    private static int dayOf(int month, int dom) {
        for (int d = rules.firstDay; d <= rules.lastDay; d++) {
            Rules.Day day = rules.day(d);
            if (day != null && day.month() == month && day.dayOfMonth() == dom) return d;
        }
        throw new AssertionError("date not in pack: " + month + "/" + dom);
    }

    /** New-game state at the daytime of {@code day}; story links carry the ranks the story granted. */
    private static SimState newGame(int day) {
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = Rules.slot(day, false);
        s.cashExact = true;
        for (Rules.Link l : rules.links) {
            if (l.kind != Rules.LinkKind.STORY || l.storyRankDay == null) continue;
            for (int r = 1; r < l.storyRankDay.length && r <= 10; r++) {
                if (l.storyRankDay[r] >= 0 && l.storyRankDay[r] <= day) s.rank[l.idx] = r;
            }
        }
        return s;
    }

    private static int link(String id) {
        Rules.Link l = rules.link(id);
        Assume.assumeTrue("pack has no link " + id, l != null);
        return l.idx;
    }

    private static void setRank(SimState s, String id, int rank) {
        Rules.Link l = rules.link(id);
        if (l != null) s.rank[l.idx] = rank;
    }

    private static void setStat(SimState s, String id, int lv) {
        Rules.Stat st = rules.stat(id);
        if (st != null) s.statPts[st.idx] = st.thresholds[lv];
    }
}
