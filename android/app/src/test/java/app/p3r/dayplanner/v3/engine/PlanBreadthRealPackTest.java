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
 * <p>Every comparison, every plan complete, validator-clean and within the time budget, keeps the
 * promises of {@link #brokenPromise} (Planner.Pool#keepsPromises): goals no worse in strict priority
 * order; with the same goals, not beaten by the 尽量多 MAX plan on links unlocked, links at RANK 5 or
 * above and the rank sum all at once; fewer links unlocked only with a 尽量都体验 objective at least
 * the 尽量多 MAX plan's; at most two more wasted daytimes; a 尽量都体验 score at least the 尽量多 MAX
 * plan's. On top of that (spec §8.2):
 * <ul>
 *   <li>new game 4/21 (no Persona; every 羁绊剧情 track, the default of a new profile; Persona for
 *       the goals only): breadth unlocks the same links and brings at most one link fewer to MAX
 *       than max ({@link #newGameKeepsTheMaxCount});</li>
 *   <li>mid game 10/22 with and without goals, 1/8 late start: breadth unlocks at least as many
 *       links, has a higher minimum rank, at least as many links at RANK 5 or above, and a rank sum
 *       at most 5% lower; its sorted ranks are higher at the first place they differ (leximin);</li>
 *   <li>goals end where they end under max (strict goal priority is unchanged), and over 23 goal
 *       profiles never lower, with no link that max takes to MAX left locked
 *       ({@link #goalProfilesKeepTheGoalsAndStayBroad}).</li>
 * </ul>
 * The minimum rank is taken over the non-story links that one of the two plans unlocks: from the
 * 10/22 state 女皇 needs Academics MAX from Lv.4 (130 points, about 40 evenings), which neither
 * style buys, so it would pin both minimums at 0 and say nothing about the styles.
 *
 * <p>Measured 2026-10-14 (pack 3.1.0, default options; max → breadth):
 * <table>
 *   <tr><th>scenario</th><th>MAX</th><th>unlocked</th><th>rank sum</th><th>RANK ≥ 5</th><th>min rank (opened)</th><th>wasted daytime</th></tr>
 *   <tr><td>new game 4/21, no Persona</td><td>21 → 22</td><td>22 → 22</td><td>219 → 220</td><td>22 → 22</td><td>9 → 10</td><td>0 → 0</td></tr>
 *   <tr><td>new game 4/21, every 羁绊剧情 track</td><td>21 → 21</td><td>22 → 22</td><td>216 → 217</td><td>22 → 22</td><td>6 → 7</td><td>1 → 1</td></tr>
 *   <tr><td>new game 4/21, Persona for goals only, 皇帝 必须 + 恋爱、女皇 想要</td><td>21 → 20</td><td>22 → 22</td><td>213 → 216</td><td>21 → 22</td><td>3 → 8</td><td>1 → 1</td></tr>
 *   <tr><td>mid game 10/22</td><td>13 → 8</td><td>20 → 21</td><td>152 → 151</td><td>14 → 18</td><td>0 → 4</td><td>1 → 1</td></tr>
 *   <tr><td>mid game 10/22, 皇帝 必须 + 女教皇 想要</td><td>10 → 7</td><td>21 → 21</td><td>137 → 137</td><td>12 → 13</td><td>1 → 3</td><td>22 → 21</td></tr>
 *   <tr><td>1/8 late start</td><td>8 → 5</td><td>22 → 22</td><td>135 → 140</td><td>15 → 16</td><td>1 → 3</td><td>0 → 1</td></tr>
 * </table>
 */
public class PlanBreadthRealPackTest {
    private static Rules rules;

    /**
     * Per-plan budget (default options, desktop JVM), as in PlanRealPackTest; shared CI runners are
     * several times slower, there the planner's own time budget bounds the run.
     */
    private static final long MAX_MILLIS = System.getenv("CI") != null ? 5_000 : 1_500;
    /**
     * Per-plan budget of the slowest start ({@link #newGameWithGoalsAndEveryEpisodeStaysWithinTheBudget}):
     * still below the planner's own 2.5 s budget, which it must not run out of.
     */
    private static final long SLOWEST_MILLIS = System.getenv("CI") != null ? 5_000 : 2_000;
    /**
     * Options of every plan here: the defaults, except that on a shared CI runner (env CI, several
     * times slower than a desktop and with two cores for the two plannings 「尽量都体验」 runs) the
     * planner's time budget is three times the default, so this test checks plan quality there and
     * the budget is checked against the desktop limits above. On a device that runs out of time the
     * plan is still complete and the service follows up with twice the budget (DESIGN §20 「Cost」).
     */
    static Engine.Options options() {
        Engine.Options options = new Engine.Options();
        if (System.getenv("CI") != null) options.timeBudgetMillis *= 3;
        return options;
    }

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

    /**
     * New game 4/21: when (nearly) every link can reach MAX, 「尽量都体验」 converges to it: the same
     * links unlocked, at most one MAX fewer than 「尽量多 MAX」. With Persona for the goals only
     * (必须 皇帝, 想要 恋爱、女皇) it stopped at 19 MAX against 21 before the MAX bonus was raised
     * (Heuristics.BREADTH_MAX_BONUS_RANKS).
     */
    @Test
    public void newGameKeepsTheMaxCount() {
        Pair none = both("对比 · 新游戏 · 不带人格面具", new Profile(), newGame(dayOf(4, 21)));
        assertConverges(none);
        assertEquals(none.line(), rules.links.length, none.breadth.unlocked);

        Profile carried = new Profile();
        carried.personaMode = Profile.PersonaMode.GOALS;
        carried.tier[link("emperor")] = Profile.Tier.MUST;
        carried.tier[link("lovers")] = Profile.Tier.WANT;
        carried.order[link("lovers")] = 1;
        carried.tier[link("empress")] = Profile.Tier.WANT;
        carried.order[link("empress")] = 2;
        Pair goals = both("对比 · 新游戏 · 只给目标社群带人格面具 · 必须：皇帝 · 想要：恋爱、女皇", carried, newGame(dayOf(4, 21)));
        assertConverges(goals);
        for (int l : new int[]{link("emperor"), link("lovers"), link("empress")}) assertEquals(goals.line(), 10, goals.breadth.endRank[l]);

        Profile episodes = new Profile();
        episodes.episodeTracks = Profile.defaultEpisodeTracks(rules);
        Assume.assumeTrue("no Link Episode data", episodes.episodeTracks.length > 0);
        Pair all = both("对比 · 新游戏 · 不带人格面具 · 全部羁绊剧情", episodes, newGame(dayOf(4, 21)));
        assertConverges(all);
        assertEquals(all.line(), rules.links.length, all.breadth.unlocked);
        assertTrue(all.line(), all.breadth.episodes >= all.max.episodes);
    }

    /**
     * The slowest 「尽量都体验」 start the 3.1.0 re-check found: a new game with every 羁绊剧情 track and
     * goals (必须 力量 to RANK 6, 想要 命运 to RANK 4), 1.37–1.39 s there and 1.4–1.5 s here against
     * 1.1 s for 「尽量多 MAX」, so it is held to {@link #SLOWEST_MILLIS} rather than {@link #MAX_MILLIS}
     * (DESIGN §20 「Cost」). Complete, validator-clean and not out of time ({@link #run}); the goals
     * reached, the same links unlocked and at most one MAX fewer.
     */
    @Test
    public void newGameWithGoalsAndEveryEpisodeStaysWithinTheBudget() {
        Profile profile = new Profile();
        profile.episodeTracks = Profile.defaultEpisodeTracks(rules);
        Assume.assumeTrue("no Link Episode data", profile.episodeTracks.length > 0);
        int strength = link("strength");
        int fortune = link("fortune");
        profile.tier[strength] = Profile.Tier.MUST;
        profile.target[strength] = 6;
        profile.tier[fortune] = Profile.Tier.WANT;
        profile.target[fortune] = 4;
        profile.order[fortune] = 1;
        Pair p = both("对比 · 新游戏 · 全部羁绊剧情 · 必须：力量 RANK 6 · 想要：命运 RANK 4", profile, newGame(dayOf(4, 21)),
                SLOWEST_MILLIS);
        assertConverges(p);
        assertTrue(p.line(), p.breadth.endRank[strength] >= 6 && p.breadth.endRank[fortune] >= 4);
        assertTrue(p.line(), p.breadth.episodes >= p.max.episodes);
    }

    /** A new game: the same links unlocked and at most one MAX fewer than 「尽量多 MAX」. */
    private static void assertConverges(Pair p) {
        assertEquals("unlocked " + p.line(), p.max.unlocked, p.breadth.unlocked);
        assertTrue("MAX " + p.line(), p.breadth.maxed >= p.max.maxed - 1);
    }

    @Test
    public void midGameUnlocksAndRaisesTheLowLinks() {
        Pair p = both("对比 · 中期 · 10月22日 · 全部社群", new Profile(), midGameState());
        assertBroader(p);
        // 女教皇 (0 under max) is unlocked; the links max leaves at RANK 1 get further.
        assertTrue(p.line(), p.breadth.endRank[link("priestess")] >= 1);
        assertTrue(p.line(), p.breadth.endRank[link("lovers")] >= p.max.endRank[link("lovers")]);
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
     * review found worse on a goal or dominated under 「尽量都体验」, 12 seeded 10/22 profiles (1 to 4
     * goals, 必须 or 想要, some carrying their Persona) and, from the 3.1.0 re-check, 10/22 必须 女教皇
     * with every 羁绊剧情 track. Every one keeps the promises of {@link #brokenPromise} ({@link #compare}):
     * before the 「尽量多 MAX」 plans joined every 「尽量都体验」 pool, 必须 ①星 ②正义 ③命运 was below
     * 「尽量多 MAX」 on every breadth number (18 links unlocked against 19, rank sum 123 against 132,
     * RANK ≥ 5 11 against 13), two profiles unlocked a link fewer and two wasted 11 and 13 more
     * daytimes; 10/22 ①女教皇 ②永劫 ③恋爱 left 恋爱 at RANK 9 before the goals got the 「尽量多 MAX」
     * plans. And no link that 「尽量多 MAX」 takes to MAX stays locked: before the unlock promise
     * (Planner.Pool#keepsPromises) random 5, 必须 皇帝、恋爱 with Persona for the goals and 必须 女教皇
     * with every track left 恶魔 at RANK 0 for fewer wasted daytimes, with a lower objective. Over
     * the set: at least as many links unlocked and at RANK 5 or above, and fewer wasted daytimes.
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
        // 3.1.0 re-check (sweep S11/7): the score kept a plan with 恶魔 at RANK 0 that 尽量多 MAX takes to MAX,
        // one link fewer unlocked although one more MAX.
        Profile priestess = must(link("priestess"));
        priestess.episodeTracks = Profile.defaultEpisodeTracks(rules);
        cases.add(new Object[]{"10月22日 · 必须：女教皇 · 全部羁绊剧情", priestess, midGameState()});
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
        int[] sum = new int[8];
        List<String> locked = new ArrayList<>();
        for (Object[] c : cases) {
            Pair p = compare((String) c[0], (Profile) c[1], (SimState) c[2]);
            // 「先把每个社群都解锁」: no link that 尽量多 MAX takes to MAX stays locked (3.1.0 re-check: 10/22
            // random 5 and 必须 皇帝、恋爱 left 恶魔 at RANK 0, J lower than the 尽量多 MAX plan's).
            for (int l = 0; l < rules.links.length; l++) {
                if (p.max.endRank[l] >= 10 && p.breadth.endRank[l] == 0) locked.add(rules.links[l].arcanaZh + " · " + p.line());
            }
            int[] add = {p.breadth.unlocked, p.max.unlocked, p.breadth.atLeast5, p.max.atLeast5, p.breadth.daytimeWaste,
                    p.max.daytimeWaste, p.breadth.rankSum, p.max.rankSum};
            for (int i = 0; i < sum.length; i++) sum[i] += add[i];
        }
        String totals = "unlocked " + sum[0] + " / " + sum[1] + " · RANK ≥ 5 " + sum[2] + " / " + sum[3]
                + " · wasted daytime " + sum[4] + " / " + sum[5] + " · rank sum " + sum[6] + " / " + sum[7]
                + " (尽量都体验 / 尽量多 MAX, " + cases.size() + " profiles)";
        System.out.println(totals);
        assertTrue("尽量多 MAX takes these to MAX, 尽量都体验 leaves them locked:\n" + String.join("\n", locked), locked.isEmpty());
        assertTrue(totals, sum[0] >= sum[1] && sum[2] >= sum[3] && sum[4] < sum[5]);
    }

    /**
     * Strict goal priority between two end states of one profile (as Planner.Pool.compareGoals): the
     * goals in order, 「必须」 then 「想要」; reaching the target beats missing it, between two misses the
     * higher RANK wins. Positive when {@code a} is better.
     */
    static int compareGoals(Profile profile, int[] a, int[] b) {
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

    // ------------------------------------------------------------------ reviewed MAX plans

    /**
     * Reviewed MAX-style plans, with enough budget to make the outcome deterministic. Dated study
     * and explicit dorm goals intentionally change the pre-3.3 routes; capture fingerprints and ids
     * so later changes still need an explicit review instead of silently skipping the check.
     */
    @Test
    public void maxStyleMatchesTheReviewedPlans() {
        Object[][] golden = {
                {"新游戏", new Profile(), newGame(dayOf(4, 21)), "d4a4ccffe32f662c", GOLDEN_IDS[0]},
                {"中期 10月22日", new Profile(), midGameState(), "1db859c1e75df79c", GOLDEN_IDS[1]},
                {"中期 10月22日 · 必须：皇帝 · 想要：女教皇", midGoals(), midGameState(), "877ab0a115659f88", GOLDEN_IDS[2]}};
        boolean samePack = GOLDEN_RULES_VERSION.equals(rules.version);
        List<String> ids = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (Object[] g : golden) {
            Profile profile = ((Profile) g[1]).copy();
            profile.style = Profile.Style.MAX;
            SimState start = (SimState) g[2];
            Engine.Options options = new Engine.Options();
            options.timeBudgetMillis = 60_000;
            Plan plan = Engine.plan(rules, profile, start, null, options);
            assertFalse(g[0] + " ran out of time", plan.budgetExhausted);
            String actual = fingerprint(plan);
            System.out.println(g[0] + " reviewed fingerprint " + actual + " · plan id " + plan.planId + " · pack " + rules.version);
            if (!g[3].equals(actual)) mismatches.add(g[0] + " steps: " + g[3] + " -> " + actual);
            ids.add(plan.planId);
            if (samePack && !g[4].equals(plan.planId)) mismatches.add(g[0] + " plan id: " + g[4] + " -> " + plan.planId);
            assertTrue(Engine.validate(rules, profile, start, plan).isEmpty());
            Profile breadth = profile.copy();
            breadth.style = Profile.Style.BREADTH;
            assertFalse(g[0] + ": the style is part of a breadth plan's id",
                    plan.planId.equals(PlanHash.of(rules, breadth, Kernel.normalize(new Model(rules, breadth, null), start), options, plan)));
        }
        assertTrue("Review changed routes before updating fixtures: " + mismatches + "; pack " + rules.version
                + ", recorded on " + GOLDEN_RULES_VERSION + ", ids " + ids, samePack && mismatches.isEmpty());
    }

    /**
     * {@link Rules#version} of the pack the golden plan ids ({@link #GOLDEN_IDS}) were recorded on. They
     * were refreshed for 3.3.0 after reviewing dated study, finite dorm milestones, planted-crop
     * permission, explicit NONE defaults, rescue margins, menu discovery, and the existing
     * goal/coverage/cash invariants. Separate opt-in tests cover characteristic milestones.
     * MAX-style ids still omit style.
     */
    private static final String GOLDEN_RULES_VERSION = "3.3.0|30aef29ce462";
    /** Plan ids of the three golden 「尽量多 MAX」 plans on {@link #GOLDEN_RULES_VERSION}. */
    private static final String[] GOLDEN_IDS = {"dd8759b4a234d213", "9e40a8e612474c79", "b663634dfa64fd04"};

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

    /**
     * The same start planned in both styles, with metrics and, for each plan, the 尽量都体验 score
     * ({@link #breadthScore}, what the planner keeps the best of) and objective ({@link #breadthObjective}).
     */
    private static final class Pair {
        final String title;
        final Profile profile;
        final PlanMetrics breadth;
        final PlanMetrics max;
        final double breadthScore;
        final double maxScore;
        final double breadthJ;
        final double maxJ;

        Pair(String title, Profile profile, PlanMetrics breadth, PlanMetrics max, SimState start) {
            this.title = title;
            this.profile = profile;
            this.breadth = breadth;
            this.max = max;
            Profile b = profile.copy();
            b.style = Profile.Style.BREADTH;
            this.breadthScore = breadthScore(rules, b, start, breadth.plan);
            this.maxScore = breadthScore(rules, b, start, max.plan);
            this.breadthJ = breadthObjective(rules, b, start, breadth.plan);
            this.maxJ = breadthObjective(rules, b, start, max.plan);
        }

        String line() {
            return title + "\n  尽量多 MAX: MAX " + max.maxed + " · " + max.breadthLine() + " · 浪费 " + max.daytimeWaste
                    + " · 分 " + Math.round(maxScore) + "（J " + Math.round(maxJ) + "）· " + Arrays.toString(max.endRank)
                    + "\n  尽量都体验: MAX " + breadth.maxed + " · " + breadth.breadthLine() + " · 浪费 " + breadth.daytimeWaste
                    + " · 分 " + Math.round(breadthScore) + "（J " + Math.round(breadthJ) + "）· " + Arrays.toString(breadth.endRank);
        }
    }

    /**
     * Both styles from {@code start} ({@link #compare}), recorded in the metrics table (the lowest
     * RANK over the non-story links either plan unlocks).
     */
    private static Pair both(String title, Profile profile, SimState start) {
        return both(title, profile, start, MAX_MILLIS);
    }

    /** {@link #both} with a per-plan time limit of {@code maxMillis}. */
    private static Pair both(String title, Profile profile, SimState start, long maxMillis) {
        Pair p = compare(title, profile, start, maxMillis);
        boolean[] opened = new boolean[rules.links.length];
        for (int l = 0; l < opened.length; l++) opened[l] = p.breadth.endRank[l] >= 1 || p.max.endRank[l] >= 1;
        for (PlanMetrics pm : new PlanMetrics[]{p.breadth, p.max}) {
            pm.minOpened = pm.minRankOver(opened);
            pm.recordPending();
        }
        return p;
    }

    /** Both styles from {@code start}, each checked by {@link #run}, keeping the promises of {@link #brokenPromise}; not recorded. */
    private static Pair compare(String title, Profile profile, SimState start) {
        return compare(title, profile, start, MAX_MILLIS);
    }

    private static Pair compare(String title, Profile profile, SimState start, long maxMillis) {
        Profile breadth = profile.copy();
        breadth.style = Profile.Style.BREADTH;
        Profile max = profile.copy();
        max.style = Profile.Style.MAX;
        PlanMetrics b = run(title, breadth, start, maxMillis);
        PlanMetrics m = run(title, max, start, maxMillis);
        Pair p = new Pair(title, profile, b, m, start);
        System.out.println(p.line());
        String broken = brokenPromise(rules, profile, start, b.plan, m.plan);
        assertTrue(broken + " · " + p.line(), broken == null);
        return p;
    }

    /**
     * What 「尽量都体验」 promises against 「尽量多 MAX」 from the same start (DESIGN §20,
     * Planner.Pool#keepsPromises), or null when every promise holds: its goals end no worse in strict
     * priority order and, when they end the same, the 尽量多 MAX plan does not beat it on links
     * unlocked, links at RANK 5 or above and the rank sum all at once (at least one strictly), it
     * unlocks fewer links only when the 尽量都体验 objective ({@link #breadthObjective}, J) scores it at
     * least as high, it wastes at most {@link Planner#BREADTH_WASTE_SLACK} more daytimes, and its
     * 尽量都体验 score ({@link #breadthScore}) is at least the 尽量多 MAX plan's.
     *
     * <p>The score, not J, is the floor: J less the waste margin per wasted daytime is what the planner
     * compares complete plans by (DESIGN §20), and J alone may be a little lower for a plan that is
     * broader on every count and wastes fewer daytimes (3.1.0 re-check: 10/22 random 3 and 7 of
     * {@link #goalProfilesKeepTheGoalsAndStayBroad}, 20 and 15 lower). Where a lower J meant a link
     * left locked (random 5: 恶魔 RANK 0 where 尽量多 MAX takes it to MAX), the unlock rule applies.
     */
    static String brokenPromise(Rules rules, Profile profile, SimState start, Plan breadthPlan, Plan maxPlan) {
        Profile breadth = profile.copy();
        breadth.style = Profile.Style.BREADTH;
        Profile max = profile.copy();
        max.style = Profile.Style.MAX;
        PlanMetrics b = PlanMetrics.of(rules, breadth, start, breadthPlan);
        PlanMetrics m = PlanMetrics.of(rules, max, start, maxPlan);
        int goals = compareGoals(profile, b.endRank, m.endRank);
        if (goals < 0) return "goals below 尽量多 MAX";
        if (goals > 0) return null; // a goal ends higher: strict goal priority comes first
        if (m.unlocked >= b.unlocked && m.atLeast5 >= b.atLeast5 && m.rankSum >= b.rankSum
                && (m.unlocked > b.unlocked || m.atLeast5 > b.atLeast5 || m.rankSum > b.rankSum)) {
            return "dominated by 尽量多 MAX";
        }
        double jb = breadthObjective(rules, breadth, start, breadthPlan);
        double jm = breadthObjective(rules, breadth, start, maxPlan);
        if (b.unlocked < m.unlocked && jb < jm - 1e-6) {
            return "unlocks " + b.unlocked + " links against " + m.unlocked + " with a 尽量都体验 objective " + Math.round(jb)
                    + " below the 尽量多 MAX plan's " + Math.round(jm);
        }
        if (b.daytimeWaste > m.daytimeWaste + Planner.BREADTH_WASTE_SLACK) {
            return "wasted daytime " + b.daytimeWaste + " against " + m.daytimeWaste;
        }
        double sb = breadthScore(rules, breadth, start, breadthPlan);
        double sm = breadthScore(rules, breadth, start, maxPlan);
        if (sb < sm - 1e-6) return "尽量都体验 score " + Math.round(sb) + " below the 尽量多 MAX plan's " + Math.round(sm);
        return null;
    }

    /** The 尽量都体验 objective of {@code plan}, less the fragile-MAX penalty (before the waste margin of {@link #breadthScore}). */
    static double breadthObjective(Rules rules, Profile breadth, SimState observed, Plan plan) {
        Model m = new Model(rules, breadth, new Engine.Options());
        SimState st = Kernel.normalize(m, observed);
        Heuristics h = new Heuristics(m, st, null);
        int[] reached = h.initialReached(st);
        for (int code : codes(rules, plan)) {
            int slot = st.slot;
            Kernel.apply(m, st, m.t.open(slot) ? code : Kernel.FREE, null);
            h.track(st, slot, reached);
        }
        return h.objective(st, reached) - Heuristics.FRAGILE_MAX_PENALTY * h.fragileMaxes(st, reached);
    }

    /**
     * The 尽量都体验 score of {@code plan}: the value the planner keeps the best of among plans whose
     * goals tie (Planner.Candidate#score, since 3.0), the objective ({@link #breadthObjective}) less the
     * waste margin (Planner#wasteMargin) for every wasted daytime (Planner#daytimeWaste).
     */
    static double breadthScore(Rules rules, Profile breadth, SimState observed, Plan plan) {
        Model m = new Model(rules, breadth, new Engine.Options());
        SimState st = Kernel.normalize(m, observed);
        Heuristics h = new Heuristics(m, st, null);
        return breadthObjective(rules, breadth, observed, plan) - Planner.wasteMargin(h) * Planner.daytimeWaste(m, st, codes(rules, plan));
    }

    /** The action code of every step of {@code plan} (FREE for a locked or free slot). */
    private static int[] codes(Rules rules, Plan plan) {
        int[] out = new int[plan.steps.size()];
        for (int i = 0; i < out.length; i++) {
            Action a = Action.parse(rules, plan.steps.get(i).action);
            out[i] = a != null && a.isProductive() ? Kernel.encode(a) : Kernel.FREE;
        }
        return out;
    }

    /**
     * One plan with default options: complete, validator-clean, within the time budget, every rescue made and
     * none that matters left to its limit evening while an earlier one was open ({@link RescueMargin});
     * recorded by {@link #both}.
     */
    private static PlanMetrics run(String title, Profile profile, SimState start, long maxMillis) {
        long t0 = System.nanoTime();
        Plan plan = Engine.plan(rules, profile, start, null, options());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String what = title + " · " + PlanMetrics.styleZh(profile);
        assertEquals(what, rules.lastSlot() - start.slot + 1, plan.steps.size());
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(what + " " + errors, errors.isEmpty());
        List<String> warnings = Engine.validationWarnings(rules, profile, start, plan);
        assertTrue(what + " " + warnings, warnings.isEmpty());
        assertFalse(what + " budget exhausted", plan.budgetExhausted);
        assertTrue(what + " took " + ms + " ms", ms <= maxMillis);
        PlanMetrics pm = PlanMetrics.of(rules, profile, start, plan);
        assertEquals(what + " missed rescues", 0, pm.rescuesMissed());
        String late = RescueMargin.broken(rules, profile, start, plan);
        assertTrue(what + ": " + late, late == null);
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
