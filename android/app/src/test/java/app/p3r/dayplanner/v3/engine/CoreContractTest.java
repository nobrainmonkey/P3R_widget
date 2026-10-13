package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Engine side of the 3.0.1 shared contract and the audit fixes, on the synthetic packs: per-block
 * Tartarus caps and wanted visits (legality, plan, objective, validator), the accepted Christmas Eve
 * invitation, goal weights spaced by priority, wording that never claims a goal is impossible, the
 * budget note, the fragile-MAX measure and the cash reserve ahead of unlock fees.
 */
public class CoreContractTest {
    private static final String[] INFEASIBLE_WORDS = {"时间不够", "时间太紧", "来不及", "排不开", "不可能"};

    private static Engine.Options generous() {
        Engine.Options o = new Engine.Options();
        o.timeBudgetMillis = 60_000;
        return o;
    }

    private static int[] towerVisits(Rules rules, Plan plan) {
        int[] visits = new int[rules.blocks.length];
        for (Plan.Step s : plan.steps) {
            if ("tower".equals(s.kind)) visits[Action.parse(rules, s.action).target]++;
        }
        return visits;
    }

    private static void assertValid(Rules rules, Profile profile, SimState start, Plan plan) {
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        assertEquals(rules.lastSlot() - start.slot + 1, plan.steps.size());
    }

    // ------------------------------------------------------------------ per-block Tartarus caps

    @Test
    public void eachBlockHasItsOwnCapAndWantedVisits() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        profile.towerCapByBlock.put("thebel", 3);
        profile.towerVisitsByBlock.put("thebel", 3);
        profile.towerCapByBlock.put("arqa", 1);
        profile.towerVisitsByBlock.put("arqa", 1);
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        int[] visits = towerVisits(rules, plan);
        assertEquals("thebel: its own wanted visits, up to its own cap", 3, visits[0]);
        assertEquals("arqa: one visit, which is the rescue", 1, visits[1]);
        for (Plan.Step s : plan.steps) {
            if ("tower".equals(s.kind) && Action.parse(rules, s.action).target == 1) {
                int day = s.slot >> 1;
                assertTrue("the only arqa visit falls in the rescue window", day >= 40 && day <= 45);
            }
        }
    }

    @Test
    public void legalityFollowsTheCapOfTheCurrentBlock() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        profile.towerCapByBlock.put("thebel", 1);
        SimState s = RulesFixtures.newGame(rules, Rules.slot(22, true));
        s.towerVisits[0] = 1;
        assertFalse("thebel is full at 1", Engine.legalActions(rules, profile, s).contains(Action.tower(0)));
        profile.towerCapByBlock.put("thebel", 2);
        assertTrue(Engine.legalActions(rules, profile, s).contains(Action.tower(0)));
        // arqa has no entry: the shared cap (4) applies there.
        profile.towerCapByBlock.put("thebel", 0);
        SimState later = RulesFixtures.newGame(rules, Rules.slot(39, true));
        later.towerVisits[1] = 3;
        assertTrue(Engine.legalActions(rules, profile, later).contains(Action.tower(1)));
        later.towerVisits[1] = 4;
        assertFalse(Engine.legalActions(rules, profile, later).contains(Action.tower(1)));
    }

    @Test
    public void validatorChecksTheCapOfEachBlock() {
        Rules rules = RulesFixtures.small();
        Profile loose = RulesFixtures.others();
        loose.towerVisitsByBlock.put("thebel", 3);
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, loose, start, null, generous());
        assertValid(rules, loose, start, plan);
        assertTrue(towerVisits(rules, plan)[0] >= 3);

        Profile tight = loose.copy();
        tight.towerCapByBlock.put("thebel", 1);
        List<String> errors = Engine.validate(rules, tight, start, plan);
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("Tartarus cap 1 exceeded in block 0")));
        Profile otherBlock = loose.copy();
        otherBlock.towerCapByBlock.put("arqa", 1);
        int arqa = towerVisits(rules, plan)[1];
        assertEquals("only the capped block is checked against 1", arqa > 1,
                !Engine.validate(rules, otherBlock, start, plan).isEmpty());
    }

    @Test
    public void unmetWantedVisitsOfOneBlockArePenalised() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        profile.towerVisitsByBlock.put("thebel", 4);
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Model m = new Model(rules, profile, null);
        assertEquals(4, m.towerWant[0]);
        assertEquals("the shared default elsewhere", 2, m.towerWant[1]);
        Heuristics h = new Heuristics(m, start, null);
        SimState end = start.copy();
        end.towerVisits[0] = 4;
        end.towerVisits[1] = 2;
        end.rescued = 1L;
        int[] reached = h.initialReached(start);
        double met = h.objective(end, reached);
        end.towerVisits[0] = 3;
        assertEquals(Heuristics.PENALTY, met - h.objective(end, reached), 1e-6);
        // Wanted visits never exceed the block's cap.
        profile.towerCapByBlock.put("thebel", 2);
        assertEquals(2, new Model(rules, profile, null).towerWant[0]);
    }

    // ------------------------------------------------------------------ Christmas Eve invitation

    @Test
    public void acceptedChristmasEveInvitationLocksBothSlots() {
        Rules rules = RulesFixtures.fullYear();
        int eve = RulesFixtures.day(12, 24);
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(12, 20, false));
        start.rank[1] = 5; // 友近健二, met on Thursdays (12/24 is a Thursday)
        start.cash = 5_000;
        Profile declined = RulesFixtures.others();
        Profile accepted = RulesFixtures.others();
        accepted.christmasLink = "magician";

        Plan free = Engine.plan(rules, declined, start, null, generous());
        assertValid(rules, declined, start, free);
        assertTrue("12/24 is free by default", free.at(Rules.slot(eve, false)).productive());

        Plan taken = Engine.plan(rules, accepted, start, null, generous());
        assertValid(rules, accepted, start, taken);
        for (boolean evening : new boolean[]{false, true}) {
            Plan.Step step = taken.at(Rules.slot(eve, evening));
            assertEquals("locked:christmas_date", step.action);
            assertEquals("locked", step.kind);
            assertEquals("平安夜约会", step.titleZh);
            assertEquals("这段时间不能自由行动", step.subtitleZh);
            assertEquals("已经答应了友近健二的平安夜邀约", step.detailZh);
            assertTrue(step.side.isEmpty());
            SimState there = Engine.advanceTo(rules, accepted, start, Rules.slot(eve, evening));
            assertTrue("nothing to do on a taken slot", Engine.legalActions(rules, accepted, there).isEmpty());
        }
        assertTrue(taken.at(Rules.slot(eve + 1, false)).productive());
        assertEquals(free.summary.lockedSlots + 2, taken.summary.lockedSlots);

        // The validator derives the lock on its own.
        List<String> wrong = Engine.validate(rules, accepted, start, free);
        assertTrue(wrong.toString(), wrong.stream().anyMatch(e -> e.contains("christmas_date")));
        assertFalse(Engine.validate(rules, declined, start, taken).isEmpty());
    }

    @Test
    public void christmasEveLabelDoesNotNeedThePack() {
        Rules rules = RulesFixtures.fullYear();
        assertFalse(rules.lockReasonsZh.containsKey(Rules.LOCK_CHRISTMAS));
        Profile p = RulesFixtures.others();
        p.christmasLink = "no_such_link";
        Labels labels = new Labels(new Model(rules, p, null), new Heuristics(new Model(rules, p, null),
                RulesFixtures.newGame(rules, RulesFixtures.slot(12, 20, false)), null));
        assertEquals("平安夜约会", labels.lockTitle(Rules.LOCK_CHRISTMAS));
        Plan.Step step = new Plan.Step();
        step.slot = RulesFixtures.slot(12, 24, true);
        labels.describeLocked(step, Rules.LOCK_CHRISTMAS);
        assertEquals("已经答应了平安夜的邀约", step.detailZh);
    }

    // ------------------------------------------------------------------ goal priority

    @Test
    public void goalWeightsAreSpacedByPriority() {
        Rules rules = RulesFixtures.fullYear();
        Profile p = new Profile();
        int[] musts = {4, 11, 8};
        for (int i = 0; i < musts.length; i++) {
            p.tier[musts[i]] = Profile.Tier.MUST;
            p.order[musts[i]] = i;
        }
        p.tier[6] = Profile.Tier.WANT;
        p.order[6] = 0;
        p.tier[2] = Profile.Tier.WANT;
        p.order[2] = 1;
        Heuristics h = new Heuristics(new Model(rules, p, null), RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false)), null);
        assertEquals(150_000, h.wGoal[4], 1e-9);
        assertEquals(125_000, h.wGoal[11], 1e-9);
        assertEquals(100_000, h.wGoal[8], 1e-9);
        assertEquals(10_000, h.wGoal[6], 1e-9);
        assertEquals(8_000, h.wGoal[2], 1e-9);
        int[] order = {4, 11, 8, 6, 2};
        for (int i = 0; i + 1 < order.length; i++) {
            int a = order[i];
            int b = order[i + 1];
            assertEquals(i, h.goalRank[a]);
            // When two goals compete for the last ranks, the earlier goal's MAX outweighs the later one's.
            double maxA = h.val[a][10] - h.val[a][9];
            double maxB = h.val[b][10] - h.val[b][9];
            assertTrue(order[i] + " before " + order[i + 1], maxA > maxB);
        }
        // Every 「必须」 rank outweighs every 「想要」 MAX.
        assertTrue(h.wGoal[8] > h.val[6][10] - h.val[6][9]);
    }

    // ------------------------------------------------------------------ wording and budget

    @Test
    public void notesNeverClaimAGoalIsImpossible() {
        Rules rules = RulesFixtures.fullYear();
        int priestess = 2;
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(1, 10, false));
        start.rank[priestess] = 1;
        start.statPts[RulesFixtures.COURAGE] = 80;
        Profile profile = RulesFixtures.must(priestess);
        Plan plan = Engine.plan(rules, profile, start, null, generous());
        assertValid(rules, profile, start, plan);
        assertTrue("a late start leaves the goal short", plan.summary.endRanks[priestess] < 10);
        String note = plan.summary.notesZh.get(0);
        assertTrue(note, note.startsWith("女教皇：按这次的安排预计停在 RANK "));
        for (String n : plan.summary.notesZh) {
            for (String w : INFEASIBLE_WORDS) assertFalse(n, n.contains(w));
        }
    }

    @Test
    public void budgetRunOutIsSaidFirstAndPromisesOneMoreRun() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Engine.Options cut = new Engine.Options();
        cut.cancelled = new AtomicBoolean(true);
        Plan plan = Engine.plan(rules, profile, start, null, cut);
        assertTrue(plan.budgetExhausted);
        assertValid(rules, profile, start, plan);
        assertEquals("这次没来得及算完，稍后会自动再算一次", plan.summary.notesZh.get(0));

        Engine.Options again = new Engine.Options();
        again.cancelled = new AtomicBoolean(true);
        again.followUp = true;
        Plan second = Engine.plan(rules, profile, start, null, again);
        assertEquals("这次没来得及算完，记下一个结果后会重新安排", second.summary.notesZh.get(0));
        assertEquals("the follow-up flag does not change the plan", plan.planId, second.planId);

        Plan full = Engine.plan(rules, profile, start, null, generous());
        assertFalse(full.budgetExhausted);
        for (String n : full.summary.notesZh) assertFalse(n, n.contains("没来得及算完"));
    }

    // ------------------------------------------------------------------ robustness

    @Test
    public void fragileMaxCountsOnlyOtherLinksMaxedOnTheirLastChance() {
        Rules rules = RulesFixtures.fullYear();
        int magician = 1;
        int emperor = 4;
        Profile profile = RulesFixtures.must(emperor);
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Model m = new Model(rules, profile, null);
        Heuristics h = new Heuristics(m, start, null);
        int last = -1;
        int earlier = -1;
        for (int s = m.t.lastSlot; s >= m.t.firstSlot; s--) {
            if ((m.t.meetMask[s] & (1 << magician)) == 0) continue;
            if (last < 0) last = s;
            else if (earlier < 0) earlier = s;
        }
        SimState end = start.copy();
        end.rank[magician] = 10;
        end.rank[emperor] = 10;
        int[] reached = h.initialReached(start);
        int g = h.goalLinks.length;
        reached[g + magician] = last;
        reached[g + emperor] = last;
        assertEquals("magician on its last chance; the goal never counts", 1, h.fragileMaxes(end, reached));
        reached[g + magician] = earlier;
        assertEquals(0, h.fragileMaxes(end, reached));
        reached[g + magician] = Heuristics.MAX_BEFORE;
        assertEquals("already MAX when the plan starts", 0, h.fragileMaxes(end, reached));

        // track() records the first MAX slot of every link.
        int[] fresh = h.initialReached(start);
        SimState st = start.copy();
        st.rank[magician] = 10;
        h.track(st, 300, fresh);
        h.track(st, 301, fresh);
        assertEquals(300, fresh[g + magician]);
        assertEquals(-1, fresh[g + 2]);
    }

    @Test
    public void unlockFeesKeepAReserveOnTop() {
        Rules rules = RulesFixtures.small();
        Profile profile = RulesFixtures.others();
        SimState s = RulesFixtures.newGame(rules, Rules.slot(31, true));
        s.statPts[RulesFixtures.CHARM] = 15; // Devil's Charm Lv2
        s.cash = 25_000;
        Model m = new Model(rules, profile, null);
        SimState st = Kernel.normalize(m, s);
        Heuristics h = new Heuristics(m, st, null);
        h.prepare(st);
        assertEquals("both Devil payments plus one café shift", 30_000 + Heuristics.FEE_RESERVE, h.feeNeeds());
        assertEquals(2_500, Heuristics.FEE_RESERVE);
        assertEquals(0, Heuristics.feeReserve(0));
        assertEquals(Heuristics.FEE_RESERVE, Heuristics.feeReserve(510));
        Plan plan = Engine.plan(rules, profile, s, null, generous());
        assertTrue(plan.summary.notesZh.contains("需要付钱解锁的社群，安排会在付钱前尽量多留 2,500 日元"));
    }

    @Test
    public void romanceNeedsAnExplicitChoice() {
        Rules rules = RulesFixtures.small();
        rules.links[1].eventGainRomance = new int[]{0, 0, 30, 30, 30, 30, 30, 30, 30, 30, 30};
        Profile undecided = RulesFixtures.others();
        Model m = new Model(rules, undecided, null);
        assertEquals("UNDECIDED plans the friend route", rules.links[1].eventGain, m.eventGain[1]);
        undecided.route[1] = Profile.Route.ROMANCE;
        assertEquals(rules.links[1].eventGainRomance, new Model(rules, undecided, null).eventGain[1]);
        assertNotNull(m.eventGain[1]);
    }
}
