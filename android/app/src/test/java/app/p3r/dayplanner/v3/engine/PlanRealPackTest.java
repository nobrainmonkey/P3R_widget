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
import java.util.List;

/**
 * Integration with the real rule pack {@code assets/p3r/p3r_rules.json} (built by the rules module).
 * Skipped while the pack or its loader is not available in this checkout.
 *
 * <p>Prints, per scenario: plan span, useful actions, maxed links, elapsed time, the first 30 days
 * of the plan in Chinese and the quality metrics of {@link PlanMetrics} (daytimeWaste, jobs, cash
 * on Dec 1 and at the end, the day each link opens, Tartarus visits per block), and writes one
 * markdown table of every scenario (start, span, steps, useful actions, free slots, maxed links,
 * goal MAX dates, runtime) to {@code android/app/build/plan_metrics.md}. Asserts full
 * coverage, a clean independent validation, useful actions until the last two weeks of January,
 * every Tartarus requirement and rescue, the one-day rescue margin ({@link RescueMargin}: no rescue that
 * matters on its limit evening while an earlier evening was open), runtime, and the plan-quality benchmarks below as lower
 * bounds (the numbers this planner reaches with default options; the CN walkthrough pinned in
 * rules/sources/cn_guides/data.json maxes all 22 links with matching Personas and is the feasibility witness):
 *
 * <table>
 *   <tr><th>scenario</th><th>bound (reached 2026-10-14, pack with notes and episodes)</th><th>daytimeWaste</th></tr>
 *   <tr><td>new game, Persona for every link (ALL)</td><td>22/22 (22/22)</td><td>0</td></tr>
 *   <tr><td>new game, no Persona</td><td>≥ 21/22 (22/22 with 11 notes; 21/22 without notes)</td><td>0</td></tr>
 *   <tr><td>new game, 皇帝 MUST (MAX by 6/29, every Emperor day used)</td><td>≥ 20/22 (21/22)</td><td>0</td></tr>
 *   <tr><td>mid game 10/22 (¥1,000, Charm Lv3)</td><td>≥ 6/22, 21 unlocked, 17 at RANK 5 or above (8/22, 21 unlocked, 18 at RANK 5 or above; 尽量多 MAX 13/22, 20, 14)</td><td>≤ 3</td></tr>
 *   <tr><td>new game, Persona for goals only (GOALS), 皇帝 MUST + 恋爱/女皇 WANT</td><td>≥ 20/22 (20/22, the other two RANK 8; 尽量多 MAX 21/22, one RANK 3)</td><td>≤ 2</td></tr>
 *   <tr><td>new game, every Link Episode track</td><td>every completable track complete; ≥ 19/22 (21/22)</td><td>≤ 1</td></tr>
 *   <tr><td>mid game 10/22, 皇帝 必须 + 女教皇 想要</td><td>both MAX; ≥ 7/22 (7/22; 尽量多 MAX 10/22)</td><td>≤ 30 (jobs that pay the goals' notes)</td></tr>
 *   <tr><td>mid game 10/22, 必须 ① 力量 ② 正义 (either order)</td><td>both MAX (the audit found 力量 9)</td><td>≤ 30</td></tr>
 *   <tr><td>1/8, 力量 6 + 正义 6, both 必须</td><td>the first goal reaches MAX, the second does not; swapping swaps them</td><td>—</td></tr>
 *   <tr><td>mid game 10/22, 必须 ①女教皇 ②女皇 ③恋爱 / ①恶魔 ②女皇 ③月亮</td><td>① MAX (the audit found ① RANK 9 with ② MAX)</td><td>—</td></tr>
 *   <tr><td>mid game 10/22, 12 random 必须 triples</td><td>strict goal priority over every compared plan; the goals no worse than under 尽量多 MAX, and the promises of PlanBreadthRealPackTest#brokenPromise against it</td><td>—</td></tr>
 * </table>
 *
 * <p>Every scenario also checks the hard fee reserve: on a day the plan pays an unlock fee, no paid
 * training or dating-site note leaves less than 2,500 日元.</p>
 */
public class PlanRealPackTest {
    private static Rules rules;
    /** The pack's JSON, to load independent copies that a scenario may extend (null for the synthetic year). */
    private static String packJson;

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
        packJson = json;
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

    /**
     * Plan-quality budget per scenario (default options, desktop JVM; DESIGN §8). Shared CI runners
     * (env CI) are several times slower and noisy: there the planner's own time budget bounds the run
     * (each scenario still asserts the rollout finished inside it, {@code budgetExhausted} false).
     */
    private static final long MAX_MILLIS = System.getenv("CI") != null ? 5_000 : 1_500;
    /** "Modest" cash: what the plan holds before the December filler jobs start. */
    private static final long MODEST_CASH = 60_000;

    @Test
    public void earlyGameEmperorMustPlusOthers() {
        int emperor = link("emperor");
        Profile profile = new Profile();
        profile.tier[emperor] = Profile.Tier.MUST;
        SimState start = newGame(dayOf(4, 21));
        Result r = run("早期 · 4月21日新游戏 · 必须：皇帝 + 其余社群", profile, start, 0);
        assertEquals("皇帝 must reach MAX", 10, r.plan.summary.endRanks[emperor]);
        // Every Emperor meeting day from the opening on 4/27 is used: MAX on the 18th Emperor day.
        int maxSlot = r.plan.summary.maxSlot[emperor];
        System.out.println("皇帝 MAX " + PlanReport.date(rules, maxSlot));
        assertTrue("皇帝 MAX at " + PlanReport.date(rules, maxSlot), maxSlot <= Rules.slot(dayOf(6, 29), false));
        assertTrue("others maxed " + (r.metrics.maxed - 1), r.metrics.maxed - 1 >= 19);
    }

    @Test
    public void newGameNoPersona() {
        // 尽量都体验 used to give one early daytime (4/23) to a stat gate where 尽量多 MAX gives none;
        // with the 尽量多 MAX plans among its candidates it wastes none either (DESIGN §20).
        Result r = run("新游戏 · 不带人格面具 · 全部社群", new Profile(), newGame(dayOf(4, 21)), 0);
        assertTrue("maxed " + r.metrics.maxed, r.metrics.maxed >= 21);
    }

    @Test
    public void newGameWithPersonaBonus() {
        Profile profile = new Profile();
        profile.personaBonus = true;
        Result r = run("新游戏 · 携带同阿尔卡那人格面具（×1.51）· 全部社群", profile, newGame(dayOf(4, 21)), 0);
        System.out.println("benchmark: the CN walkthrough maxes 22/22 with matching Personas; this plan maxes "
                + r.metrics.maxed + "/" + rules.links.length);
        assertEquals(rules.links.length, r.metrics.maxed);
    }

    @Test
    public void midGameOctober22() {
        Profile profile = new Profile();
        SimState start = midGameState();
        Result r = run("中期 · 10月22日 · 魔术师 2、太阳 7、1,000 日元、魅力 Lv.3", profile, start, 3);
        assertTrue(r.plan.summary.endRanks[link("magician")] > 2);
        // 尽量都体验 (the default) raises the links 尽量多 MAX leaves low instead of finishing a few
        // more (PlanBreadthRealPackTest): every link but 女皇 (Academics MAX from Lv.4) is unlocked.
        assertTrue("maxed " + r.metrics.maxed, r.metrics.maxed >= 6);
        assertTrue("unlocked " + r.metrics.unlocked, r.metrics.unlocked >= 21);
        // 3.2 schedules every rescue wave and discovers stat-gated menus: this start trades
        // some middle ranks for one more MAX (9 MAX, 15 links at RANK 5+, rank sum 148).
        assertTrue("RANK ≥ 5 " + r.metrics.atLeast5, r.metrics.atLeast5 >= 15);
        // Every chain that can still complete does: the Justice talks, the Devil payments (Charm Lv4 and
        // ¥40,000 from ¥1,000), the Tower and Moon rumours; Temperance, Star and Aeon open too.
        for (String id : new String[]{"justice", "devil", "tower", "moon", "temperance", "star", "aeon"}) {
            assertTrue(id + " never opened", r.plan.summary.endRanks[link(id)] >= 1);
        }
    }

    /** The mid-game benchmark state: 10/22 daytime, 1,000 日元, a few links underway, 文吉 rescued. */
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
     * Daytime slots a late-game plan with goals may give to jobs while a 「其余社群」 link could be
     * met: the pay buys the 结缘网站的字条 that bring the goals to MAX in time (goals come first).
     */
    private static final int GOAL_WASTE = 30;

    /** 「必须」 goals in this order (order 0, 1, …). */
    private static Profile must(int... links) {
        Profile p = new Profile();
        for (int i = 0; i < links.length; i++) {
            p.tier[links[i]] = Profile.Tier.MUST;
            p.order[links[i]] = i;
        }
        return p;
    }

    /**
     * Work order 3, mid game with an explicit goal: 皇帝 必须 and 女教皇 想要 from the 10/22
     * benchmark state, with the other links as secondary goals. Full coverage and a clean
     * validation (asserted by {@link #run}), both goals at MAX, and the other links still useful.
     */
    @Test
    public void midGameOctober22WithGoals() {
        int emperor = link("emperor");
        int priestess = link("priestess");
        Profile profile = must(emperor);
        profile.tier[priestess] = Profile.Tier.WANT;
        Result r = run("中期 · 10月22日 · 必须：皇帝 · 想要：女教皇 + 其余社群", profile, midGameState(), GOAL_WASTE);
        assertEquals("皇帝 MAX", 10, r.plan.summary.endRanks[emperor]);
        assertEquals("女教皇 MAX", 10, r.plan.summary.endRanks[priestess]);
        assertTrue(r.plan.summary.maxSlot[emperor] < r.plan.summary.maxSlot[priestess]);
        assertEquals("皇帝：预计 " + noteDate(r.plan.summary.maxSlot[emperor]) + " 升到 MAX", r.plan.summary.notesZh.get(0));
        assertEquals("女教皇：预计 " + noteDate(r.plan.summary.maxSlot[priestess]) + " 升到 MAX", r.plan.summary.notesZh.get(1));
        assertTrue("maxed " + r.metrics.maxed, r.metrics.maxed >= 7);
        System.out.println("皇帝 MAX " + PlanReport.date(rules, r.plan.summary.maxSlot[emperor]) + " · 女教皇 MAX "
                + PlanReport.date(rules, r.plan.summary.maxSlot[priestess]));
    }

    /**
     * Audit case ("maximise explicit goals in priority order"): 必须 ① 力量 ② 正义 from the 10/22
     * state used to end 力量 9 / 正义 10 with 其余社群 on. Both fit, in either order.
     */
    @Test
    public void twoMustGoalsBothFitInTheMidGame() {
        int strength = link("strength");
        int justice = link("justice");
        Result a = run("中期 · 10月22日 · 必须：①力量 ②正义 + 其余社群", must(strength, justice), midGameState(), GOAL_WASTE);
        assertEquals(10, a.plan.summary.endRanks[strength]);
        assertEquals(10, a.plan.summary.endRanks[justice]);
        Result b = run("中期 · 10月22日 · 必须：①正义 ②力量 + 其余社群", must(justice, strength), midGameState(), GOAL_WASTE);
        assertEquals(10, b.plan.summary.endRanks[strength]);
        assertEquals(10, b.plan.summary.endRanks[justice]);
    }

    /**
     * Goal order is decisive: from 1/8 with 力量 RANK 6 and 正义 RANK 6, each reaches MAX alone, but
     * they meet on the same days and only one of them can. Whichever goal is first gets MAX; swapping
     * the order swaps who gets it, and the note says what this plan did instead of calling the other
     * goal impossible.
     */
    @Test
    public void goalOrderDecidesWhoGetsMaxWhenTwoMustGoalsCompete() {
        int strength = link("strength");
        int justice = link("justice");
        SimState start = Engine.advanceTo(rules, new Profile(), midGameState(), Rules.slot(dayOf(1, 8), false));
        start.rank[strength] = 6;
        start.pts[strength] = 0;
        start.rank[justice] = 6;
        start.pts[justice] = 0;
        assertEquals("力量 alone", 10, run("1月8日 · 力量 6、正义 6 · 必须：力量", must(strength), start, 99).plan.summary.endRanks[strength]);
        assertEquals("正义 alone", 10, run("1月8日 · 力量 6、正义 6 · 必须：正义", must(justice), start, 99).plan.summary.endRanks[justice]);

        Result sj = run("1月8日 · 力量 6、正义 6 · 必须：①力量 ②正义", must(strength, justice), start, 99);
        assertEquals(10, sj.plan.summary.endRanks[strength]);
        assertTrue("正义 " + sj.plan.summary.endRanks[justice], sj.plan.summary.endRanks[justice] < 10);
        assertEquals("正义：这次的安排里没排到 MAX（先保证了力量），预计停在 RANK " + sj.plan.summary.endRanks[justice],
                sj.plan.summary.notesZh.get(1));

        Result js = run("1月8日 · 力量 6、正义 6 · 必须：①正义 ②力量", must(justice, strength), start, 99);
        assertEquals(10, js.plan.summary.endRanks[justice]);
        assertTrue("力量 " + js.plan.summary.endRanks[strength], js.plan.summary.endRanks[strength] < 10);
        assertEquals("力量：这次的安排里没排到 MAX（先保证了正义），预计停在 RANK " + js.plan.summary.endRanks[strength],
                js.plan.summary.notesZh.get(1));
    }

    /**
     * Audit cases (strict goal priority): 必须 ①女教皇 ②女皇 ③恋爱 used to end 女教皇 RANK 9 with 女皇
     * MAX, and ①恶魔 ②女皇 ③月亮 恶魔 RANK 9 with 女皇 and 月亮 MAX, although planning ① and ② alone
     * reaches ①. The first goal is kept whenever a plan reaches it; a note names a goal as kept
     * instead of another only when that goal comes first and this plan reaches it.
     */
    @Test
    public void strictGoalPriorityKeepsTheFirstGoalInTheAuditCases() {
        String[][] cases = {{"priestess", "empress", "lovers"}, {"devil", "empress", "moon"}};
        for (String[] ids : cases) {
            int[] goals = {link(ids[0]), link(ids[1]), link(ids[2])};
            Plan two = Engine.plan(rules, must(goals[0], goals[1]), midGameState(), null, PlanBreadthRealPackTest.options());
            assertEquals(ids[0] + " with two goals", 10, two.summary.endRanks[goals[0]]);
            String title = "中期 · 10月22日 · 必须：①" + rules.links[goals[0]].arcanaZh + " ②" + rules.links[goals[1]].arcanaZh
                    + " ③" + rules.links[goals[2]].arcanaZh + " + 其余社群";
            Result r = run(title, must(goals), midGameState(), 99);
            assertEquals(ids[0] + " first", 10, r.plan.summary.endRanks[goals[0]]);
            assertTrue(r.plan.summary.notesZh.get(0), r.plan.summary.notesZh.get(0).startsWith(rules.links[goals[0]].arcanaZh + "：预计 "));
            assertStrictPriority(r.plan, goals, null);
            assertNotesNameOnlyKeptHigherGoals(r.plan, goals);
        }
    }

    /**
     * Strict goal priority on random mid-game triples (fixed seed, drawn from the links that are
     * hard to finish from 10/22, so that goals compete): no plan ends a higher goal below MAX while
     * a lower one reaches MAX if any plan the planner compared reached the higher one, and no
     * compared plan beats the chosen one on the goals in priority order. Each plan stays within the
     * time budget, complete and valid; a missed goal's note names only a higher goal this plan kept.
     */
    @Test
    public void strictGoalPriorityOnRandomMidGameTriples() {
        List<Integer> manual = new ArrayList<>();
        for (String id : new String[]{"priestess", "empress", "lovers", "devil", "moon", "temperance", "aeon", "star",
                "justice", "strength", "tower", "magician", "chariot", "hermit", "fortune"}) {
            manual.add(link(id));
        }
        java.util.Random random = new java.util.Random(20261013L);
        int missed = 0;
        SimState start = midGameState();
        Engine.plan(rules, new Profile(), start, null, null); // JIT warm-up
        for (int i = 0; i < 12; i++) {
            List<Integer> pool = new ArrayList<>(manual);
            java.util.Collections.shuffle(pool, random);
            int[] goals = {pool.get(0), pool.get(1), pool.get(2)};
            Profile profile = must(goals);
            Engine.Options options = PlanBreadthRealPackTest.options();
            options.trace = new ArrayList<>();
            long t0 = System.nanoTime();
            Plan plan = Engine.plan(rules, profile, start, null, options);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            StringBuilder line = new StringBuilder("random triple " + i + ":");
            for (int g : goals) line.append(' ').append(rules.links[g].id).append('=').append(plan.summary.endRanks[g]);
            System.out.println(line + " · " + ms + " ms · " + options.trace.size() + " plans compared");
            assertEquals(rules.lastSlot() - start.slot + 1, plan.steps.size());
            List<String> errors = Engine.validate(rules, profile, start, plan);
            assertTrue(errors.toString(), errors.isEmpty());
            assertFalse("budget exhausted " + line, plan.budgetExhausted);
            assertTrue(line + " took " + ms + " ms", ms <= MAX_MILLIS);
            String late = RescueMargin.broken(rules, profile, start, plan);
            assertTrue(line + ": " + late, late == null);
            assertStrictPriority(plan, goals, options.trace);
            assertNotesNameOnlyKeptHigherGoals(plan, goals);
            // Spec §8.1: 尽量都体验 (the default) reaches what 尽量多 MAX reaches on the goals, and keeps its
            // promises against the 尽量多 MAX plan (Planner.Pool.addMaxStylePlans, keepsPromises).
            Profile maxStyle = profile.copy();
            maxStyle.style = Profile.Style.MAX;
            Plan max = Engine.plan(rules, maxStyle, start, null, PlanBreadthRealPackTest.options());
            String maxLate = RescueMargin.broken(rules, maxStyle, start, max);
            assertTrue(line + " · 尽量多 MAX: " + maxLate, maxLate == null);
            for (int g : goals) {
                if (plan.summary.endRanks[g] == max.summary.endRanks[g]) continue;
                assertTrue(line + ": " + rules.links[g].id + " " + plan.summary.endRanks[g] + " against " + max.summary.endRanks[g]
                        + " under 尽量多 MAX", plan.summary.endRanks[g] > max.summary.endRanks[g]);
                break;
            }
            PlanMetrics b = PlanMetrics.of(rules, profile, start, plan);
            PlanMetrics m = PlanMetrics.of(rules, maxStyle, start, max);
            String both = line + " · 尽量都体验 " + b.breadthLine() + " · 浪费 " + b.daytimeWaste + " / 尽量多 MAX " + m.breadthLine()
                    + " · 浪费 " + m.daytimeWaste;
            System.out.println(both);
            String broken = PlanBreadthRealPackTest.brokenPromise(rules, profile, start, plan, max);
            assertTrue(broken + " · " + both, broken == null);
            for (int g : goals) if (plan.summary.endRanks[g] < 10) missed++;
        }
        assertTrue("some triples compete for the same days", missed > 0);
    }

    /**
     * No higher goal (in {@code goals}' order) ends below MAX while a lower one reaches MAX if a
     * compared plan ({@code trace}; null: a run of its own) reached the higher one, and no compared
     * plan is better on the goals in priority order (reached beats missed, then the higher rank).
     */
    private static void assertStrictPriority(Plan plan, int[] goals, List<int[]> trace) {
        if (trace == null) {
            Engine.Options options = PlanBreadthRealPackTest.options();
            options.trace = new ArrayList<>();
            Profile profile = must(goals);
            Engine.plan(rules, profile, midGameState(), null, options);
            trace = options.trace;
        }
        int[] end = plan.summary.endRanks;
        for (int a = 0; a < goals.length; a++) {
            for (int b = a + 1; b < goals.length; b++) {
                if (end[goals[a]] >= 10 || end[goals[b]] < 10) continue;
                for (int[] other : trace) {
                    assertTrue(rules.links[goals[a]].id + " missed for " + rules.links[goals[b]].id + " although a compared plan reached it",
                            other[goals[a]] < 10);
                }
            }
        }
        for (int[] other : trace) {
            for (int g : goals) {
                boolean mine = end[g] >= 10;
                boolean theirs = other[g] >= 10;
                if (mine != theirs) {
                    assertTrue(rules.links[g].id + ": a compared plan reaches it and keeps every higher goal", mine);
                    break;
                }
                if (!mine && end[g] != other[g]) {
                    assertTrue(rules.links[g].id + ": a compared plan gets it higher and keeps every higher goal", end[g] > other[g]);
                    break;
                }
            }
        }
    }

    /** A note "…（先保证了X）" names a goal placed before the missed one that this plan brings to MAX. */
    private static void assertNotesNameOnlyKeptHigherGoals(Plan plan, int[] goals) {
        for (int i = 0; i < goals.length; i++) {
            String head = rules.links[goals[i]].arcanaZh + "：";
            for (String note : plan.summary.notesZh) {
                if (!note.startsWith(head)) continue;
                int kept = plan.summary.tradedFor[goals[i]];
                if (!note.contains("先保证了")) {
                    assertEquals(note, -1, kept);
                    continue;
                }
                assertTrue(note, kept >= 0 && note.contains("先保证了" + rules.links[kept].arcanaZh + "）"));
                int place = -1;
                for (int k = 0; k < goals.length; k++) if (goals[k] == kept) place = k;
                assertTrue(note + " names a later goal", place >= 0 && place < i);
                assertEquals(note, 10, plan.summary.endRanks[kept]);
            }
        }
    }

    /** "12月21日" as the summary notes write a date. */
    private static String noteDate(int slot) {
        Rules.Day d = rules.day(slot >> 1);
        return d.month() + "月" + d.dayOfMonth() + "日";
    }

    /**
     * docs/GLOSSARY_zh.md: a card or title says 「结缘网站的字条」 in full the first time; the short
     * 字条 only follows it within the same sentence.
     */
    private static void assertFullNoteNameFirst(String text) {
        if (text == null) return;
        for (String sentence : text.split("[。；\n]")) {
            int at = sentence.indexOf("字条");
            if (at < 0) continue;
            int full = sentence.indexOf("结缘网站的字条");
            assertTrue(text, full >= 0 && full + "结缘网站的".length() == at);
        }
    }

    /**
     * 「怎么做」 of a meeting is a how-to: when, where and whom, the guide's answers for the scene
     * when there are some, and the link's own note as a separate 「注意：」 line (the audit found a
     * 5/25 皇帝 RANK 3 → 4 step whose how-to was only 「11月3日～11月29日小田桐秀利不在学生会，见不到面。」).
     */
    @Test
    public void meetingHowToSaysWhenWhereAndWhomWithTheNoteApart() {
        int emperor = link("emperor");
        Plan plan = Engine.plan(rules, must(emperor), newGame(dayOf(4, 21)), null, PlanBreadthRealPackTest.options());
        int checked = 0;
        boolean answers = false;
        for (Plan.Step s : plan.steps) {
            if (!"meet:emperor".equals(s.action)) continue;
            String[] lines = s.detailZh.split("\n");
            Rules.Day day = rules.day(s.slot >> 1);
            String when = day.type == Rules.DayType.SCHOOL ? "放学后" : "白天";
            assertEquals(s.detailZh, when + "到月光馆学园 · 学生会活动室找小田桐秀利", lines[0]);
            assertEquals(s.detailZh, "注意：11月3日～11月29日小田桐秀利不在学生会，见不到面。", lines[lines.length - 1]);
            if (s.predicted.rankUp && s.predicted.rankTo == 2) {
                assertEquals(s.detailZh, "参考回答：无聊透顶（2）", lines[1]);
                answers = true;
            }
            for (String line : lines) assertFalse(s.detailZh, line.startsWith("选能让对方高兴的回答"));
            checked++;
        }
        assertTrue("皇帝 meetings " + checked, checked >= 10);
        assertTrue("the RANK 2 scene carries the guide's answer", answers);

        Model m = new Model(rules, new Profile(), null);
        SimState start = newGame(dayOf(4, 21));
        Labels labels = new Labels(m, new Heuristics(m, start, null));
        int sunday = dayOf(5, 3);
        assertEquals("晚上到桐叶购物中心 · 喷泉广场找田中社长", labels.meetWhere(Rules.slot(sunday, true), link("devil"), false));
        assertEquals("白天在宿舍 · 自己房间的电脑上找Ｙ子", labels.meetWhere(Rules.slot(sunday, false), link("hermit"), false));
        assertEquals("白天赴岳羽由加莉的休息日邀约，一起外出", labels.meetWhere(Rules.slot(sunday, false), link("lovers"), true));
    }

    /** Every multi-step chain (non-consuming side steps, REPORT §0) completes in a new game. */
    @Test
    public void multiStepUnlocksComplete() {
        Profile profile = new Profile();
        SimState start = newGame(dayOf(4, 21));
        Plan plan = Engine.plan(rules, profile, start, null, PlanBreadthRealPackTest.options());
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
        // ¥40,000 of fees plus the ¥2,500 the last payment keeps (June: the Devil can wait for it).
        s.cash = 40_000 + Kernel.FEE_RESERVE;
        Rules.Link d = rules.links[devil];
        int day = d.avail.nextSetBit(dayOf(6, 1));
        s = Engine.advanceTo(rules, profile, s, Rules.slot(day, true));
        Action study = Action.activity(rules.activity("study_room").idx);
        Engine.Applied first = Engine.apply(rules, profile, s, study);
        assertEquals(java.util.Collections.singletonList("unlock:devil:0"), first.side);
        assertEquals(20_000 + Kernel.FEE_RESERVE, first.state.cash);
        int day2 = d.avail.nextSetBit(day + 1);
        Engine.Applied second = Engine.apply(rules, profile, Engine.advanceTo(rules, profile, first.state, Rules.slot(day2, true)), study);
        assertEquals(java.util.Collections.singletonList("unlock:devil:1"), second.side);
        int day3 = d.avail.nextSetBit(day2 + 1);
        SimState last = Engine.advanceTo(rules, profile, second.state, Rules.slot(day3, true));
        assertTrue(Engine.legalActions(rules, profile, last).contains(Action.unlock(devil, 2)));
        Engine.Applied open = Engine.apply(rules, profile, last, Action.unlock(devil, 2));
        assertEquals(1, open.state.rank[devil]);
        assertEquals(Kernel.FEE_RESERVE, open.state.cash);
        SimState short1 = last.copy();
        short1.cash = 10_000;
        assertFalse("in June the opening can wait until it leaves ¥2,500",
                Engine.legalActions(rules, profile, short1).contains(Action.unlock(devil, 2)));
    }

    /**
     * 只给目标社群带人格面具 (GOALS): the ×1.51 only for the goals; their meetings carry the
     * "记得带上…" hint and no other meeting does.
     */
    @Test
    public void goalsPersonaMode() {
        int emperor = link("emperor");
        int lovers = link("lovers");
        int empress = link("empress");
        Profile profile = new Profile();
        profile.personaMode = Profile.PersonaMode.GOALS;
        profile.tier[emperor] = Profile.Tier.MUST;
        profile.tier[lovers] = Profile.Tier.WANT;
        profile.order[lovers] = 1;
        profile.tier[empress] = Profile.Tier.WANT;
        profile.order[empress] = 2;
        // A 「必须」 goal may trade a couple of early daytime slots for an earlier MAX (worth more by
        // the objective than the meeting it skips), so this scenario allows two.
        Result r = run("新游戏 · 只给目标社群带人格面具 · 必须：皇帝 · 想要：恋爱、女皇", profile, newGame(dayOf(4, 21)), 2);
        for (int l : new int[]{emperor, lovers, empress}) assertEquals(rules.links[l].id, 10, r.plan.summary.endRanks[l]);
        int hints = 0;
        for (Plan.Step s : r.plan.steps) {
            if (s.hintZh == null) continue;
            hints++;
            int l = rules.linkIndex(s.entity);
            assertTrue(s.entity + " is not a goal", l >= 0 && profile.isGoal(l));
            assertEquals("记得带上「" + rules.links[l].arcanaZh + "」阿尔卡那的人格面具", s.hintZh);
        }
        System.out.println("GOALS persona: maxed " + r.metrics.maxed + "/" + rules.links.length + " · steps with the Persona hint " + hints);
        assertTrue("hints " + hints, hints >= 20);
        // Three goals bind more daytime than 皇帝 alone (22/22): 尽量都体验 stays within one MAX of
        // 尽量多 MAX (21/22, one link at RANK 3) and brings the two others to RANK 8 (19/22 with three
        // links at RANK 7-8 before the MAX bonus was raised, DESIGN §20).
        assertTrue("maxed " + r.metrics.maxed, r.metrics.maxed >= 20);
        assertEquals("unlocked", rules.links.length, r.metrics.unlocked);
    }

    /**
     * 结缘网站的字条 on the real calendar: the pack's note, or, until the rules module ships it, the
     * evidence's numbers (rules/p3r/affinity_actions_candidate.json: +10 points, 3,000 日元, one
     * evening, on sale from 11/16). Notes are on by default, so the three benchmarks must hold with
     * them too: no Persona ≥ 21 MAX, every Persona 22/22, 皇帝 MUST by 6/29 with ≥ 20 MAX, at most one
     * wasted daytime (none with a goal or every Persona).
     */
    @Test
    public void datingNotesOnTheRealCalendar() throws Exception {
        Rules pack = freshPack();
        boolean injected = pack.datingNote == null;
        if (injected) pack.datingNote = RulesFixtures.datingNote(dayOf(11, 16));
        String tag = injected ? "（按证据补上）" : "";
        SimState start = newGame(dayOf(4, 21));

        Result none = run(pack, "新游戏 · 不带人格面具 · 结缘网站的字条" + tag, new Profile(), start, 1);
        int notes = 0;
        for (Plan.Step s : none.plan.steps) {
            if (!s.action.startsWith("note:")) continue;
            notes++;
            assertEquals("note", s.kind);
            assertTrue((s.slot & 1) == 1 && (s.slot >> 1) >= pack.datingNote.fromDay);
            assertTrue(s.titleZh, s.titleZh.startsWith("在放纵俱乐部买结缘网站的字条，给") && !s.titleZh.contains(" "));
            for (String text : new String[]{s.titleZh, s.subtitleZh, s.whyZh, s.detailZh}) assertFullNoteNameFirst(text);
        }
        for (String n : none.plan.summary.notesZh) assertFullNoteNameFirst(n);
        assertTrue("maxed " + none.metrics.maxed, none.metrics.maxed >= 21);

        Profile all = new Profile();
        all.personaMode = Profile.PersonaMode.ALL;
        Result persona = run(pack, "新游戏 · 基本都带人格面具 · 结缘网站的字条" + tag, all, start, 0);
        assertEquals(pack.links.length, persona.metrics.maxed);

        int emperor = link("emperor");
        Profile must = new Profile();
        must.tier[emperor] = Profile.Tier.MUST;
        Result goal = run(pack, "新游戏 · 必须：皇帝 · 结缘网站的字条" + tag, must, start, 0);
        assertEquals(10, goal.plan.summary.endRanks[emperor]);
        assertTrue(goal.plan.summary.maxSlot[emperor] <= Rules.slot(dayOf(6, 29), false));
        assertTrue("maxed " + goal.metrics.maxed, goal.metrics.maxed >= 20);
        System.out.println("dating notes: no Persona " + none.metrics.maxed + "/22 with " + notes + " notes · every Persona "
                + persona.metrics.maxed + "/22 with " + persona.metrics.notes + " · 皇帝 MUST " + goal.metrics.maxed + "/22 with "
                + goal.metrics.notes + ", 皇帝 MAX " + PlanReport.date(pack, goal.plan.summary.maxSlot[emperor]));
    }

    /**
     * 羁绊剧情 on the real calendar: every track of the pack, or, until the rules module ships them,
     * the daytime tracks read from the aqiu day calendar (rules/sources/aqiu_pages): 顺平, 小丸,
     * 真次郎, 绫时. Every track that can still be completed is completed.
     */
    @Test
    public void linkEpisodesOnTheRealCalendar() throws Exception {
        Rules pack = freshPack();
        boolean injected = pack.episodes == null || pack.episodes.length == 0;
        if (injected) pack.episodes = aqiuTracks(pack);
        Assume.assumeTrue("no Link Episode data", pack.episodes.length > 0);
        SimState start = newGame(dayOf(4, 21));
        Profile profile = new Profile();
        String[] ids = new String[pack.episodes.length];
        for (int e = 0; e < ids.length; e++) ids[e] = pack.episodes[e].id;
        profile.episodeTracks = ids;
        // One daytime may go to a job that pays an unlock fee early (5/3 with Aeon on Mon/Wed/Fri/Sat only).
        Result r = run(pack, "新游戏 · 不带人格面具 · 全部羁绊剧情" + (injected ? "（按日历补上）" : ""), profile, start, 1);
        Tables t = Tables.of(pack);
        int[] watched = new int[pack.episodes.length];
        for (Plan.Step s : r.plan.steps) {
            Action a = Action.parse(pack, s.action);
            if (a != null && a.type == Action.Type.EPISODE) watched[a.target]++;
        }
        StringBuilder sb = new StringBuilder("episodes:");
        for (int e = 0; e < watched.length; e++) {
            Rules.Episode ep = pack.episodes[e];
            sb.append(' ').append(ep.id).append(' ').append(watched[e]).append('/').append(ep.count);
            boolean completable = t.epCount[e] > 0 && t.epChain[e][0] && t.epLatest[e][0] >= start.slot;
            if (completable) assertEquals(ep.id + " completes", ep.count, watched[e]);
        }
        System.out.println(sb + " · maxed " + r.metrics.maxed + "/" + pack.links.length);
        assertTrue("maxed " + r.metrics.maxed, r.metrics.maxed >= 19);
    }

    /** Daytime Link Episode tracks from the aqiu day calendar (cells "1", "1-4"), or none when the file is missing. */
    private static Rules.Episode[] aqiuTracks(Rules pack) throws Exception {
        File f = null;
        for (String c : new String[]{"../../rules/sources/aqiu_pages/social_events_day_calendar.json",
                "../rules/sources/aqiu_pages/social_events_day_calendar.json", "rules/sources/aqiu_pages/social_events_day_calendar.json"}) {
            if (new File(c).isFile()) f = new File(c);
        }
        if (f == null) return new Rules.Episode[0];
        org.json.JSONArray rows = new org.json.JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        String[][] cols = {{"Junpei", "junpei", "伊织顺平"}, {"Koromaru", "koromaru", "克罗丸"},
                {"Shinjiro", "shinjiro", "荒垣真次郎"}, {"Ryoji", "ryoji", "望月绫时"}};
        java.util.List<Rules.Episode> out = new java.util.ArrayList<>();
        for (String[] col : cols) {
            Rules.Episode ep = RulesFixtures.track(pack, out.size(), col[1], col[2], false, 0);
            for (int i = 0; i < rows.length(); i++) {
                org.json.JSONObject row = rows.getJSONObject(i);
                String cell = row.optString(col[0], "-").trim();
                if (cell.isEmpty() || cell.equals("-")) continue;
                String[] md = row.getString("Date").split("/");
                int day = dayOf(Integer.parseInt(md[0]), Integer.parseInt(md[1]));
                String[] range = cell.split("-");
                int lo = Integer.parseInt(range[0].trim());
                int hi = Integer.parseInt(range[range.length - 1].trim());
                for (int n = lo; n <= hi; n++) ep.dayMask[day] |= 1 << (n - 1);
                ep.count = Math.max(ep.count, hi);
            }
            if (ep.count > 0) out.add(ep);
        }
        return out.toArray(new Rules.Episode[0]);
    }

    // ------------------------------------------------------------------ helpers

    /** A plan and its metrics. */
    private static final class Result {
        final Plan plan;
        final PlanMetrics metrics;

        Result(Plan plan, PlanMetrics metrics) {
            this.plan = plan;
            this.metrics = metrics;
        }
    }

    private Result run(String title, Profile profile, SimState start, int maxWaste) {
        return run(rules, title, profile, start, maxWaste);
    }

    private Result run(Rules rules, String title, Profile profile, SimState start, int maxWaste) {
        Engine.plan(rules, profile, start, null, null); // JIT warm-up
        long t0 = System.nanoTime();
        Plan plan = Engine.plan(rules, profile, start, null, PlanBreadthRealPackTest.options());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        PlanReport.print(title, rules, plan, 30);
        PlanMetrics pm = PlanMetrics.of(rules, profile, start, plan);
        int dec1 = Rules.slot(dayOf(12, 1), false);
        long cashDec1 = pm.cashBefore(dec1);
        System.out.print(pm.format());
        System.out.println("cash on 12/1 " + cashDec1 + " · end cash " + pm.endCash + " · elapsed " + ms + " ms (default options)");
        assertEquals(rules.lastSlot() - start.slot + 1, plan.steps.size());
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        List<String> warnings = Engine.validationWarnings(rules, profile, start, plan);
        assertTrue(warnings.toString(), warnings.isEmpty());
        int lastTwoWeeks = Rules.slot(rules.lastDay - 13, false);
        assertTrue("last useful action " + PlanReport.date(rules, plan.summary.lastUsefulSlot),
                plan.summary.lastUsefulSlot >= lastTwoWeeks);
        assertEquals("unmet Tartarus visits " + pm.format(), 0, pm.unmetTower);
        assertEquals("missed rescues", 0, pm.rescuesMissed());
        String late = RescueMargin.broken(rules, profile, start, plan);
        assertTrue(title + ": " + late, late == null);
        assertTrue("daytimeWaste " + pm.daytimeWaste, pm.daytimeWaste <= maxWaste);
        assertTrue("cash on 12/1 " + cashDec1, start.slot >= dec1 || cashDec1 <= MODEST_CASH);
        assertTrue("took " + ms + " ms", ms <= MAX_MILLIS);
        assertFalse("budget exhausted", plan.budgetExhausted);
        assertTrue(plan.planId.equals(Engine.plan(rules, profile, start, null, PlanBreadthRealPackTest.options()).planId));
        assertFeeDaysKeepTheReserve(rules, start, plan);
        PlanMetrics.record(rules, title, profile, start, plan, pm, ms);
        return new Result(plan, pm);
    }

    /**
     * Hard fee reserve: on a day the plan pays an unlock fee (as the slot's action or on the side),
     * no optional purchase (paid training, a dating-site note) at or after the payment leaves less
     * than 2,500 日元. Returns the number of fee payments.
     */
    private static int assertFeeDaysKeepTheReserve(Rules rules, SimState start, Plan plan) {
        int feeDay = -1;
        int payments = 0;
        for (Plan.Step s : plan.steps) {
            int day = s.slot >> 1;
            long fee = feeOf(rules, s.action);
            for (String side : s.side) fee += feeOf(rules, side);
            if (fee > 0) {
                feeDay = day;
                payments++;
            }
            if (feeDay == day && optionalPurchase(rules, s.action)) {
                assertTrue(PlanReport.date(rules, s.slot) + " " + s.action + " leaves " + s.predicted.cashAfter + " on a fee day",
                        s.predicted.cashAfter >= Kernel.FEE_RESERVE);
            }
        }
        return payments;
    }

    /** Fee of an unlock step key ("unlock:devil:0"), else 0. */
    private static long feeOf(Rules rules, String key) {
        Action a = key == null ? null : Action.parse(rules, key);
        if (a == null || a.type != Action.Type.UNLOCK_STEP) return 0;
        List<Rules.Step> steps = rules.links[a.target].unlock.steps;
        return a.step >= 0 && a.step < steps.size() ? Math.max(0, steps.get(a.step).cost) : 0;
    }

    /** Paid training or a dating-site note. */
    private static boolean optionalPurchase(Rules rules, String key) {
        Action a = key == null ? null : Action.parse(rules, key);
        if (a == null) return false;
        if (a.type == Action.Type.NOTE) return rules.datingNote != null && rules.datingNote.cost > 0;
        return a.type == Action.Type.ACTIVITY && rules.activities[a.target].cost > 0;
    }

    /**
     * Audit case (hard fee reserve): the default new game used to pay the ¥10,000 Devil fee on 8/29
     * with ¥830 to spare and buy a ¥680 meal the same evening (¥150 left). Plans that pay fees, from
     * the new game and the mid game, keep 2,500 日元 after every optional purchase on a fee day, and
     * their note says exactly what is kept.
     */
    @Test
    public void unlockFeeDaysKeepTheReserveOnTheRealPack() {
        int emperor = link("emperor");
        int priestess = link("priestess");
        Profile emperorMust = must(emperor);
        Object[][] cases = {
                {new Profile(), newGame(dayOf(4, 21))},
                {emperorMust, newGame(dayOf(4, 21))},
                {new Profile(), midGameState()},
                {must(priestess), midGameState()},
                {must(link("justice"), link("strength")), midGameState()}};
        int payments = 0;
        for (Object[] c : cases) {
            Profile profile = (Profile) c[0];
            SimState start = (SimState) c[1];
            Plan plan = Engine.plan(rules, profile, start, null, PlanBreadthRealPackTest.options());
            assertTrue(Engine.validate(rules, profile, start, plan).isEmpty());
            assertTrue(Engine.validationWarnings(rules, profile, start, plan).toString(),
                    Engine.validationWarnings(rules, profile, start, plan).isEmpty());
            int paid = assertFeeDaysKeepTheReserve(rules, start, plan);
            payments += paid;
            assertEquals(paid > 0, plan.summary.notesZh.contains(Labels.FEE_NOTE));
        }
        assertTrue("the scenarios pay unlock fees", payments >= 5);
    }

    // ------------------------------------------------------------------ metrics table (plan_metrics.md)

    /**
     * Writes {@code build/plan_metrics.md} of the app module (the integrator commits it as
     * docs/PLAN_METRICS.md): the rows of this class and of PlanBreadthRealPackTest (PlanMetrics).
     */
    @AfterClass
    public static void writeMetrics() throws Exception {
        if (rules == null || packJson == null) return;
        PlanMetrics.writeTable(rules);
    }

    /** A copy of the pack that a scenario may extend without touching {@link #rules}. */
    private static Rules freshPack() throws Exception {
        if (packJson == null) return RulesFixtures.fullYear();
        return RulesLoader.parse(packJson);
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
