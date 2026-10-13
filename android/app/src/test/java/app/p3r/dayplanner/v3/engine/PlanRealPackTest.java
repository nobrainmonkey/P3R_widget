package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * <p>Prints, per scenario: plan span, useful actions, maxed links, elapsed time, the first 30 days
 * of the plan in Chinese and the quality metrics of {@link PlanMetrics} (daytimeWaste, jobs, cash
 * on Dec 1 and at the end, the day each link opens, Tartarus visits per block). Asserts full
 * coverage, a clean independent validation, useful actions until the last two weeks of January,
 * every Tartarus requirement and rescue, runtime, and the plan-quality benchmarks below as lower
 * bounds (the numbers this planner reaches with default options; the legacy CN walkthrough in
 * assets/data.json maxes all 22 links with matching Personas and is the feasibility witness):
 *
 * <table>
 *   <tr><th>scenario</th><th>bound (reached 2026-10-13, pack with notes and episodes)</th><th>daytimeWaste</th></tr>
 *   <tr><td>new game, Persona for every link (ALL)</td><td>22/22 (22/22)</td><td>0</td></tr>
 *   <tr><td>new game, no Persona</td><td>≥ 21/22 (22/22 with 15 notes; 21/22 without notes)</td><td>0</td></tr>
 *   <tr><td>new game, 皇帝 MUST (MAX by 6/29, every Emperor day used)</td><td>≥ 20/22 (21/22)</td><td>0</td></tr>
 *   <tr><td>mid game 10/22 (¥1,000, Charm Lv3)</td><td>≥ 12/22 (12/22)</td><td>≤ 3</td></tr>
 *   <tr><td>new game, Persona for goals only (GOALS), 皇帝 MUST + 恋爱/女皇 WANT</td><td>≥ 20/22 (21/22)</td><td>≤ 2</td></tr>
 *   <tr><td>new game, every Link Episode track</td><td>every completable track complete; ≥ 19/22 (21/22)</td><td>0</td></tr>
 * </table>
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

    /** Plan-quality budget per scenario (default options, desktop JVM; DESIGN §8). */
    private static final long MAX_MILLIS = 1500;
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
        Result r = run("新游戏 · 不带人格面具 · 全部社群", new Profile(), newGame(dayOf(4, 21)), 0);
        assertTrue("maxed " + r.metrics.maxed, r.metrics.maxed >= 21);
    }

    @Test
    public void newGameWithPersonaBonus() {
        Profile profile = new Profile();
        profile.personaBonus = true;
        Result r = run("新游戏 · 携带同阿尔卡那人格面具（×1.51）· 全部社群", profile, newGame(dayOf(4, 21)), 0);
        System.out.println("benchmark: legacy guide maxes 22/22 with matching Personas; this plan maxes "
                + r.metrics.maxed + "/" + rules.links.length);
        assertEquals(rules.links.length, r.metrics.maxed);
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
        Result r = run("中期 · 10月22日 · 魔术师 2、太阳 7、1,000 日元、魅力 Lv.3", profile, start, 3);
        assertTrue(r.plan.summary.endRanks[link("magician")] > 2);
        assertTrue("maxed " + r.metrics.maxed, r.metrics.maxed >= 12);
        // Every chain that can still complete does: the Justice talks, the Devil payments (Charm Lv4 and
        // ¥40,000 from ¥1,000), the Tower and Moon rumours; Temperance, Star and Aeon open too.
        for (String id : new String[]{"justice", "devil", "tower", "moon", "temperance", "star", "aeon"}) {
            assertTrue(id + " never opened", r.plan.summary.endRanks[link(id)] >= 1);
        }
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
        // Three goals bind more daytime than 皇帝 alone, which already settles at 20/22.
        assertTrue("maxed " + r.metrics.maxed, r.metrics.maxed >= 20);
    }

    /**
     * 结缘网站的字条 on the real calendar: the pack's note, or, until the rules module ships it, the
     * evidence's numbers (rules/p3r/affinity_actions_candidate.json: +10 points, 3,000 日元, one
     * evening, on sale from 11/16). Notes are on by default, so the three benchmarks must hold with
     * them too: no Persona ≥ 21 MAX, every Persona 22/22, 皇帝 MUST by 6/29 with ≥ 20 MAX, no wasted
     * daytime.
     */
    @Test
    public void datingNotesOnTheRealCalendar() throws Exception {
        Rules pack = freshPack();
        boolean injected = pack.datingNote == null;
        if (injected) pack.datingNote = RulesFixtures.datingNote(dayOf(11, 16));
        String tag = injected ? "（按证据补上）" : "";
        SimState start = newGame(dayOf(4, 21));

        Result none = run(pack, "新游戏 · 不带人格面具 · 结缘网站的字条" + tag, new Profile(), start, 0);
        int notes = 0;
        for (Plan.Step s : none.plan.steps) {
            if (!s.action.startsWith("note:")) continue;
            notes++;
            assertEquals("note", s.kind);
            assertTrue((s.slot & 1) == 1 && (s.slot >> 1) >= pack.datingNote.fromDay);
            assertTrue(s.titleZh, s.titleZh.startsWith("在放纵俱乐部买结缘网站的字条，给 "));
        }
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
        Result r = run(pack, "新游戏 · 不带人格面具 · 全部羁绊剧情" + (injected ? "（按日历补上）" : ""), profile, start, 0);
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
        Plan plan = Engine.plan(rules, profile, start, null, new Engine.Options());
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
        assertTrue("daytimeWaste " + pm.daytimeWaste, pm.daytimeWaste <= maxWaste);
        assertTrue("cash on 12/1 " + cashDec1, start.slot >= dec1 || cashDec1 <= MODEST_CASH);
        assertTrue("took " + ms + " ms", ms <= MAX_MILLIS);
        assertFalse("budget exhausted", plan.budgetExhausted);
        assertTrue(plan.planId.equals(Engine.plan(rules, profile, start, null, new Engine.Options()).planId));
        return new Result(plan, pm);
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
