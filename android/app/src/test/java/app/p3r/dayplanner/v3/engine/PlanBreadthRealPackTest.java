package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 安排风格 on the real rule pack (DESIGN §20): 「尽量都体验」 (the default) against 「尽量多 MAX」
 * from the same starts, and 「尽量多 MAX」 reproducing the plans of the engine before 3.1.0.
 * Skipped while the pack is not available in this checkout.
 *
 * <p>Acceptance (spec §8.2), every plan complete, validator-clean and within the time budget:
 * <ul>
 *   <li>new game 4/21 (no Persona; also with every 羁绊剧情 track, the default of a new profile):
 *       breadth brings at most one link fewer to MAX than max;</li>
 *   <li>mid game 10/22 with and without goals, 1/8 late start: breadth unlocks at least as many
 *       links, has a higher minimum rank, at least as many links at RANK 5 or above, and a rank sum
 *       at most 5% lower; its sorted ranks are higher at the first place they differ (leximin);</li>
 *   <li>goals end where they end under max (strict goal priority is unchanged), and over 22 goal
 *       profiles never lower ({@link #goalProfilesKeepTheGoalsAndStayBroad});</li>
 *   <li>the 尽量都体验 objective scores the breadth plan at least as high as the max plan in each of
 *       these scenarios ({@link #both}): the result is what the objective asks for.</li>
 * </ul>
 * The minimum rank is taken over the non-story links that one of the two plans unlocks: from the
 * 10/22 state 女皇 needs Academics MAX from Lv.4 (130 points, about 40 evenings), which neither
 * style buys, so it would pin both minimums at 0 and say nothing about the styles.
 *
 * <p>Measured 2026-10-14 (pack 3.1.0, default options; max → breadth):
 * <table>
 *   <tr><th>scenario</th><th>MAX</th><th>unlocked</th><th>rank sum</th><th>RANK ≥ 5</th><th>min rank (opened)</th></tr>
 *   <tr><td>new game 4/21, no Persona</td><td>21 → 22</td><td>22 → 22</td><td>219 → 220</td><td>22 → 22</td><td>9 → 10</td></tr>
 *   <tr><td>new game 4/21, every 羁绊剧情 track</td><td>21 → 21</td><td>22 → 22</td><td>216 → 217</td><td>22 → 22</td><td>6 → 7</td></tr>
 *   <tr><td>mid game 10/22</td><td>13 → 6</td><td>20 → 21</td><td>152 → 151</td><td>14 → 21</td><td>0 → 5</td></tr>
 *   <tr><td>mid game 10/22, 皇帝 必须 + 女教皇 想要</td><td>10 → 7</td><td>21 → 21</td><td>137 → 135</td><td>12 → 13</td><td>1 → 3</td></tr>
 *   <tr><td>1/8 late start</td><td>8 → 4</td><td>22 → 22</td><td>135 → 141</td><td>15 → 18</td><td>1 → 3</td></tr>
 * </table>
 */
public class PlanBreadthRealPackTest {
    private static Rules rules;

    /**
     * Per-plan budget (default options, desktop JVM), as in PlanRealPackTest; shared CI runners are
     * several times slower, there the planner's own time budget bounds the run.
     */
    private static final long MAX_MILLIS = System.getenv("CI") != null ? 5_000 : 1_500;
    /** Rank sum breadth may lose against max (spec §8.2: "not more than ~5% lower"). */
    private static final double RANK_SUM_SHARE = 0.95;

    @BeforeClass
    public static void loadPack() throws Exception {
        File pack = null;
        for (String c : new String[]{System.getProperty("p3r.rules.path"), "../app/src/main/assets/p3r/p3r_rules.json",
                "src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            if (c != null && new File(c).isFile()) {
                pack = new File(c);
                break;
            }
        }
        Assume.assumeTrue("rule pack not built yet", pack != null);
        rules = RulesLoader.parse(new String(Files.readAllBytes(pack.toPath()), StandardCharsets.UTF_8));
        Engine.plan(rules, new Profile(), newGame(dayOf(4, 21)), null, null); // JIT warm-up
    }

    @AfterClass
    public static void writeMetrics() throws Exception {
        if (rules != null) PlanMetrics.writeTable(rules);
    }

    // ------------------------------------------------------------------ breadth against max

    @Test
    public void newGameKeepsTheMaxCount() {
        Pair none = both("对比 · 新游戏 · 不带人格面具", new Profile(), newGame(dayOf(4, 21)));
        assertTrue(none.line(), none.breadth.maxed >= none.max.maxed - 1);
        assertEquals(none.line(), rules.links.length, none.breadth.unlocked);

        Profile episodes = new Profile();
        episodes.episodeTracks = Profile.defaultEpisodeTracks(rules);
        Assume.assumeTrue("no Link Episode data", episodes.episodeTracks.length > 0);
        Pair all = both("对比 · 新游戏 · 不带人格面具 · 全部羁绊剧情", episodes, newGame(dayOf(4, 21)));
        assertTrue(all.line(), all.breadth.maxed >= all.max.maxed - 1);
        assertEquals(all.line(), rules.links.length, all.breadth.unlocked);
        assertTrue(all.line(), all.breadth.episodes >= all.max.episodes);
    }

    @Test
    public void midGameUnlocksAndRaisesTheLowLinks() {
        Pair p = both("对比 · 中期 · 10月22日 · 全部社群", new Profile(), midGameState());
        assertBroader(p);
        // 女教皇 (0 under max) is unlocked; the links max leaves at RANK 1 get further.
        assertTrue(p.line(), p.breadth.endRank[link("priestess")] >= 1);
        assertTrue(p.line(), p.breadth.endRank[link("lovers")] > p.max.endRank[link("lovers")]);
        assertTrue(p.line(), p.breadth.endRank[link("aeon")] > p.max.endRank[link("aeon")]);
    }

    @Test
    public void midGameWithGoalsKeepsTheGoalsAndRaisesTheRest() {
        int emperor = link("emperor");
        int priestess = link("priestess");
        Profile profile = new Profile();
        profile.tier[emperor] = Profile.Tier.MUST;
        profile.tier[priestess] = Profile.Tier.WANT;
        Pair p = both("对比 · 中期 · 10月22日 · 必须：皇帝 · 想要：女教皇", profile, midGameState());
        assertBroader(p);
        assertEquals(p.line(), 10, p.breadth.endRank[emperor]);
        assertEquals(p.line(), 10, p.breadth.endRank[priestess]);
    }

    @Test
    public void lateStartRaisesTheLowestLinks() {
        Pair p = both("对比 · 1月8日 · 晚开始（恶魔未解锁，各社群高低不一）", new Profile(), lateStart());
        assertBroader(p);
        // Every non-story link is open in both plans here: the plain minimum is higher too.
        assertTrue(p.line(), p.breadth.minRank > p.max.minRank);
    }

    /**
     * Goal profiles (the 3.1.0 review): the 1/8 pair of competing 必须 goals, the 10/22 sets the
     * review found worse on a goal or dominated under 「尽量都体验」, and 12 seeded 10/22 profiles (1 to 4
     * goals, 必须 or 想要, some carrying their Persona). Spec §8.1: every one ends its goals no worse than
     * 「尽量多 MAX」 (Planner.Pool.addMaxStylePlans; 10/22 ①女教皇 ②永劫 ③恋爱 left 恋爱 at RANK 9
     * before). Breadth: at most one link fewer unlocked and at most 15 more wasted daytimes in any
     * profile (the goals' dating-site notes are paid with daytime jobs in a few), and over the set at
     * least as many links unlocked and at RANK 5 or above and no more wasted daytime.
     */
    @Test
    public void goalProfilesKeepTheGoalsAndStayBroad() {
        List<Object[]> cases = new ArrayList<>();
        SimState late = Engine.advanceTo(rules, new Profile(), midGameState(), Rules.slot(dayOf(1, 8), false));
        late.rank[link("strength")] = 6;
        late.pts[link("strength")] = 0;
        late.rank[link("justice")] = 6;
        late.pts[link("justice")] = 0;
        cases.add(new Object[]{"1月8日 · 必须：①力量 ②正义", must(link("strength"), link("justice")), late});
        cases.add(new Object[]{"1月8日 · 必须：①正义 ②力量", must(link("justice"), link("strength")), late});
        String[][] review = {{"priestess", "aeon", "lovers"}, {"hermit", "hierophant", "emperor"},
                {"hermit", "hierophant", "chariot", "magician"}, {"lovers", "emperor", "magician", "moon"},
                {"priestess", "empress"}, {"star", "justice", "fortune"}, {"aeon", "magician", "priestess", "empress"}};
        for (String[] ids : review) {
            int[] goals = new int[ids.length];
            StringBuilder title = new StringBuilder("10月22日 · 必须：");
            for (int i = 0; i < ids.length; i++) {
                goals[i] = link(ids[i]);
                title.append(i == 0 ? "" : "、").append(rules.links[goals[i]].arcanaZh);
            }
            cases.add(new Object[]{title.toString(), must(goals), midGameState()});
        }
        Profile carried = must(link("emperor"), link("lovers"));
        carried.personaMode = Profile.PersonaMode.GOALS;
        cases.add(new Object[]{"10月22日 · 必须：皇帝、恋爱 · 只给目标社群带人格面具", carried, midGameState()});
        List<Integer> manual = new ArrayList<>();
        for (Rules.Link l : rules.links) if (l.kind == Rules.LinkKind.MANUAL) manual.add(l.idx);
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 12; i++) {
            List<Integer> pool = new ArrayList<>(manual);
            java.util.Collections.shuffle(pool, random);
            int count = 1 + random.nextInt(4);
            Profile p = new Profile();
            StringBuilder title = new StringBuilder("10月22日 · 随机 " + i + " ·");
            for (int j = 0; j < count; j++) {
                int l = pool.get(j);
                p.tier[l] = random.nextInt(3) == 0 ? Profile.Tier.WANT : Profile.Tier.MUST;
                p.order[l] = j;
                title.append(' ').append(p.tier[l] == Profile.Tier.MUST ? "必须" : "想要").append(rules.links[l].arcanaZh);
            }
            if (random.nextInt(4) == 3) p.personaMode = Profile.PersonaMode.GOALS;
            cases.add(new Object[]{title.toString(), p, midGameState()});
        }
        int[] sum = new int[6];
        for (Object[] c : cases) {
            Pair p = compare((String) c[0], (Profile) c[1], (SimState) c[2]);
            assertTrue("goals " + p.line(), compareGoals(p.profile, p.breadth.endRank, p.max.endRank) >= 0);
            assertTrue("unlocked " + p.line(), p.breadth.unlocked >= p.max.unlocked - 1);
            assertTrue("wasted daytime " + p.line(), p.breadth.daytimeWaste <= p.max.daytimeWaste + 15);
            int[] add = {p.breadth.unlocked, p.max.unlocked, p.breadth.atLeast5, p.max.atLeast5, p.breadth.daytimeWaste,
                    p.max.daytimeWaste};
            for (int i = 0; i < sum.length; i++) sum[i] += add[i];
        }
        String totals = "unlocked " + sum[0] + " / " + sum[1] + " · RANK ≥ 5 " + sum[2] + " / " + sum[3]
                + " · wasted daytime " + sum[4] + " / " + sum[5] + " (尽量都体验 / 尽量多 MAX, " + cases.size() + " profiles)";
        System.out.println(totals);
        assertTrue(totals, sum[0] >= sum[1] && sum[2] >= sum[3] && sum[4] <= sum[5]);
    }

    /**
     * Strict goal priority between two end states of one profile (as Planner.Pool.compareGoals): the
     * goals in order, 「必须」 then 「想要」; reaching the target beats missing it, between two misses the
     * higher RANK wins. Positive when {@code a} is better.
     */
    private static int compareGoals(Profile profile, int[] a, int[] b) {
        Integer[] goals = new Integer[Profile.LINKS];
        int n = 0;
        for (int l = 0; l < Profile.LINKS; l++) if (profile.isGoal(l)) goals[n++] = l;
        Arrays.sort(goals, 0, n, (x, y) -> profile.tier[x] != profile.tier[y]
                ? (profile.tier[x] == Profile.Tier.MUST ? -1 : 1)
                : profile.order[x] != profile.order[y] ? Integer.compare(profile.order[x], profile.order[y]) : Integer.compare(x, y));
        for (int i = 0; i < n; i++) {
            int l = goals[i];
            int target = Math.max(1, Math.min(10, profile.target[l]));
            boolean ra = a[l] >= target;
            boolean rb = b[l] >= target;
            if (ra != rb) return ra ? 1 : -1;
            if (!ra && a[l] != b[l]) return Integer.compare(a[l], b[l]);
        }
        return 0;
    }

    private static Profile must(int... links) {
        Profile p = new Profile();
        for (int i = 0; i < links.length; i++) {
            p.tier[links[i]] = Profile.Tier.MUST;
            p.order[links[i]] = i;
        }
        return p;
    }

    /** The spec §8.2 comparison of a mid-game or late start (see the class comment), goals included. */
    private static void assertBroader(Pair p) {
        PlanMetrics b = p.breadth;
        PlanMetrics m = p.max;
        assertTrue("unlocked " + p.line(), b.unlocked >= m.unlocked);
        boolean[] opened = new boolean[rules.links.length];
        for (int l = 0; l < opened.length; l++) opened[l] = b.endRank[l] >= 1 || m.endRank[l] >= 1;
        assertTrue("min rank " + p.line(), b.minRankOver(opened) > m.minRankOver(opened));
        assertTrue("RANK ≥ 5 " + p.line(), b.atLeast5 >= m.atLeast5);
        assertTrue("rank sum " + p.line(), b.rankSum >= RANK_SUM_SHARE * m.rankSum);
        assertTrue("leximin " + p.line(), leximin(b.sortedManualRanks(), m.sortedManualRanks()) > 0);
        for (int l = 0; l < Profile.LINKS; l++) {
            if (p.profile.isGoal(l)) assertEquals("goal " + rules.links[l].id + " " + p.line(), m.endRank[l], b.endRank[l]);
        }
    }

    /** Positive when {@code a} is higher than {@code b} at the first place the sorted ranks differ. */
    private static int leximin(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    // ------------------------------------------------------------------ 尽量多 MAX = the plans before 3.1.0

    /**
     * 「尽量多 MAX」 makes exactly the plans of the engine before 3.1.0 (step fingerprints recorded at
     * the WP-0 commit on pack 3.0.0, plan ids on pack 3.1.0 with the engine just before 安排风格, with
     * a time budget the search never runs out of): the same slot actions and side steps and, on the
     * pack they were recorded on, the same plan ids (the style is left out of a max plan's id). A
     * pack rebuild that changes the pack version changes every plan id but not the steps.
     */
    @Test
    public void maxStyleReproducesThePreviousPlans() {
        Object[][] golden = {
                {"新游戏", new Profile(), newGame(dayOf(4, 21)), "34604f4c96d9520c", "253ac6d57a6c0a00"},
                {"中期 10月22日", new Profile(), midGameState(), "36389407a7a568f6", "b19a7e04c1cdb390"},
                {"中期 10月22日 · 必须：皇帝 · 想要：女教皇", midGoals(), midGameState(), "fda71266df9c484e", "d021df66a452f281"}};
        boolean samePack = GOLDEN_RULES_VERSION.equals(rules.version);
        if (!samePack) System.out.println("pack " + rules.version + " is not " + GOLDEN_RULES_VERSION + ": plan ids not compared");
        for (Object[] g : golden) {
            Profile profile = ((Profile) g[1]).copy();
            profile.style = Profile.Style.MAX;
            SimState start = (SimState) g[2];
            Engine.Options options = new Engine.Options();
            options.timeBudgetMillis = 60_000;
            Plan plan = Engine.plan(rules, profile, start, null, options);
            assertFalse(g[0] + " ran out of time", plan.budgetExhausted);
            assertEquals(g[0] + " steps", g[3], fingerprint(plan));
            if (samePack) assertEquals(g[0] + " plan id", g[4], plan.planId);
            Profile breadth = profile.copy();
            breadth.style = Profile.Style.BREADTH;
            assertFalse(g[0] + ": the style is part of a breadth plan's id",
                    plan.planId.equals(PlanHash.of(rules, breadth, Kernel.normalize(new Model(rules, breadth, null), start), options, plan)));
        }
    }

    /**
     * {@link Rules#version} of the pack the golden plan ids were recorded on (3.1.0, by the engine of
     * the WP-C merge, before 安排风格; at WP-0 they were recorded on 3.0.0).
     */
    private static final String GOLDEN_RULES_VERSION = "3.1.0|8e4fbeda99c2";

    /** FNV-1a 64 over "slot|action|side…" per step: the plan's actions, independent of the pack version. */
    static String fingerprint(Plan plan) {
        long h = 0xcbf29ce484222325L;
        StringBuilder sb = new StringBuilder();
        for (Plan.Step s : plan.steps) {
            sb.setLength(0);
            sb.append(s.slot).append('|').append(s.action);
            for (String side : s.side) sb.append('|').append(side);
            sb.append('\n');
            for (byte b : sb.toString().getBytes(StandardCharsets.UTF_8)) {
                h ^= b & 0xff;
                h *= 0x100000001b3L;
            }
        }
        return String.format("%016x", h);
    }

    // ------------------------------------------------------------------ helpers

    /** The same start planned in both styles, with metrics and the 尽量都体验 objective of each plan. */
    private static final class Pair {
        final String title;
        final Profile profile;
        final PlanMetrics breadth;
        final PlanMetrics max;
        final double breadthJ;
        final double maxJ;

        Pair(String title, Profile profile, PlanMetrics breadth, PlanMetrics max, double breadthJ, double maxJ) {
            this.title = title;
            this.profile = profile;
            this.breadth = breadth;
            this.max = max;
            this.breadthJ = breadthJ;
            this.maxJ = maxJ;
        }

        String line() {
            return title + "\n  尽量多 MAX: MAX " + max.maxed + " · " + max.breadthLine() + " · 浪费 " + max.daytimeWaste
                    + " · J " + Math.round(maxJ) + " · " + Arrays.toString(max.endRank)
                    + "\n  尽量都体验: MAX " + breadth.maxed + " · " + breadth.breadthLine() + " · 浪费 " + breadth.daytimeWaste
                    + " · J " + Math.round(breadthJ) + " · " + Arrays.toString(breadth.endRank);
        }
    }

    /**
     * Both styles from {@code start}, recorded in the metrics table (the lowest RANK over the non-story
     * links either plan unlocks). Spec §8.2 / the 3.1.0 review: the 尽量都体验 objective scores its own
     * plan at least as high as the 尽量多 MAX plan, so the breadth result is what the objective asks
     * for, not an accident of the search.
     */
    private static Pair both(String title, Profile profile, SimState start) {
        Pair p = compare(title, profile, start);
        boolean[] opened = new boolean[rules.links.length];
        for (int l = 0; l < opened.length; l++) opened[l] = p.breadth.endRank[l] >= 1 || p.max.endRank[l] >= 1;
        for (PlanMetrics pm : new PlanMetrics[]{p.breadth, p.max}) {
            pm.minOpened = pm.minRankOver(opened);
            pm.recordPending();
        }
        assertTrue("the 尽量都体验 objective prefers the 尽量多 MAX plan: " + p.line(), p.breadthJ >= p.maxJ);
        return p;
    }

    /** Both styles from {@code start}, each checked by {@link #run}; not recorded. */
    private static Pair compare(String title, Profile profile, SimState start) {
        Profile breadth = profile.copy();
        breadth.style = Profile.Style.BREADTH;
        Profile max = profile.copy();
        max.style = Profile.Style.MAX;
        PlanMetrics b = run(title, breadth, start);
        PlanMetrics m = run(title, max, start);
        Pair p = new Pair(title, profile, b, m, breadthObjective(breadth, start, b.plan), breadthObjective(breadth, start, m.plan));
        System.out.println(p.line());
        return p;
    }

    /** The 尽量都体验 objective of {@code plan} (as the planner compares complete plans: less the fragile-MAX penalty). */
    static double breadthObjective(Profile breadth, SimState observed, Plan plan) {
        Model m = new Model(rules, breadth, new Engine.Options());
        SimState st = Kernel.normalize(m, observed);
        Heuristics h = new Heuristics(m, st, null);
        int[] reached = h.initialReached(st);
        for (Plan.Step step : plan.steps) {
            int slot = st.slot;
            Action a = Action.parse(rules, step.action);
            int code = a != null && a.isProductive() ? Kernel.encode(a) : Kernel.FREE;
            Kernel.apply(m, st, m.t.open(slot) ? code : Kernel.FREE, null);
            h.track(st, slot, reached);
        }
        return h.objective(st, reached) - Heuristics.FRAGILE_MAX_PENALTY * h.fragileMaxes(st, reached);
    }

    /** One plan with default options: complete, validator-clean, within the time budget (recorded by {@link #both}). */
    private static PlanMetrics run(String title, Profile profile, SimState start) {
        long t0 = System.nanoTime();
        Plan plan = Engine.plan(rules, profile, start, null, new Engine.Options());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String what = title + " · " + PlanMetrics.styleZh(profile);
        assertEquals(what, rules.lastSlot() - start.slot + 1, plan.steps.size());
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(what + " " + errors, errors.isEmpty());
        List<String> warnings = Engine.validationWarnings(rules, profile, start, plan);
        assertTrue(what + " " + warnings, warnings.isEmpty());
        assertFalse(what + " budget exhausted", plan.budgetExhausted);
        assertTrue(what + " took " + ms + " ms", ms <= MAX_MILLIS);
        PlanMetrics pm = PlanMetrics.of(rules, profile, start, plan);
        assertEquals(what + " missed rescues", 0, pm.rescuesMissed());
        assertEquals(what + " unmet Tartarus visits", 0, pm.unmetTower);
        pm.plan = plan;
        pm.pending = () -> PlanMetrics.record(rules, title, profile, start, plan, pm, ms);
        return pm;
    }

    private static Profile midGoals() {
        Profile p = new Profile();
        p.tier[link("emperor")] = Profile.Tier.MUST;
        p.tier[link("priestess")] = Profile.Tier.WANT;
        return p;
    }

    /** The mid-game benchmark state of PlanRealPackTest: 10/22 daytime, 1,000 日元, a few links underway, 文吉 rescued. */
    private static SimState midGameState() {
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
        return start;
    }

    /**
     * A player who starts planning on 1/8: Academics and Charm MAX, Courage Lv.5, 30,000 日元, every
     * missing person dealt with, every link but the Devil unlocked at uneven ranks (女皇 and 永劫
     * RANK 1, 女教皇 and 塔 RANK 2).
     */
    private static SimState lateStart() {
        SimState s = newGame(dayOf(1, 8));
        s.cash = 30_000;
        s.cashExact = true;
        setStat(s, "academics", 6);
        setStat(s, "charm", 6);
        setStat(s, "courage", 5);
        String[] ids = {"magician", "priestess", "empress", "emperor", "hierophant", "lovers", "chariot", "justice", "hermit",
                "fortune", "strength", "hanged_man", "temperance", "tower", "star", "moon", "sun", "aeon"};
        int[] ranks = {6, 2, 1, 7, 8, 3, 7, 5, 5, 4, 5, 6, 3, 2, 3, 4, 6, 1};
        for (int i = 0; i < ids.length; i++) setRank(s, ids[i], ranks[i]);
        for (int i = 0; i < rules.rescues.length; i++) s.rescued |= 1L << i;
        return s;
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
