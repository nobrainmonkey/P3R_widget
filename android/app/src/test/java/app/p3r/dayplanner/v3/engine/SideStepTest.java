package app.p3r.dayplanner.v3.engine;

import static app.p3r.dayplanner.v3.engine.RulesFixtures.CHARM;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.X_DEVIL;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.X_HANGED;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.X_JUSTICE;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.X_LIBRARY;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.X_MOON;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.X_PATRON;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.X_STUDY;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.X_SUN;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.X_TOWER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * Non-consuming unlock steps (REPORT §0): they need a free slot of their kind on an allowed day,
 * leave that slot to another action, respect minGapDays and the unlock requirements, and are
 * recorded on the plan step as side steps ("顺便：…").
 */
public class SideStepTest {

    private static SimState start(Rules rules, int day, long cash) {
        SimState s = RulesFixtures.newGame(rules, Rules.slot(day, false));
        s.cash = cash;
        s.statPts[CHARM] = 15; // Charm Lv2 for the Devil chain
        return s;
    }

    private static Engine.Applied act(Rules rules, Profile p, SimState s, Action a) {
        return Engine.apply(rules, p, s, a);
    }

    // ------------------------------------------------------------------ kernel / simulator

    @Test
    public void devilPaymentsHappenBesideOtherEveningActionsOneDayApart() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        // Day 24 is a Saturday (Devil evening); day 27 a Tuesday, day 31 a Saturday.
        SimState s = start(rules, 24, 50_000);
        s.slot = Rules.slot(24, true);
        Engine.Applied a = act(rules, p, s, Action.activity(X_STUDY));
        assertEquals(List.of("unlock:devil:0"), a.side);
        assertEquals(1, a.state.unlockStep[X_DEVIL]);
        assertEquals(30_000, a.state.cash);
        assertEquals(-20_000, a.predicted.cashDelta);
        assertEquals(2, a.state.statPts[0]); // the evening's own action still happened
        // Next Devil evening: the second payment (gap 1 day is satisfied).
        SimState tue = Engine.advanceTo(rules, p, a.state, Rules.slot(27, true));
        Engine.Applied b = act(rules, p, tue, Action.activity(X_STUDY));
        assertEquals(List.of("unlock:devil:1"), b.side);
        assertEquals(20_000, b.state.cash);
        // The last step consumes the slot: it is a legal action, not a side step.
        SimState sat = Engine.advanceTo(rules, p, b.state, Rules.slot(31, true));
        assertTrue(Engine.legalActions(rules, p, sat).contains(Action.unlock(X_DEVIL, 2)));
        Engine.Applied c = act(rules, p, sat, Action.activity(X_STUDY));
        assertTrue(c.side.isEmpty());
        Engine.Applied open = act(rules, p, sat, Action.unlock(X_DEVIL, 2));
        assertEquals(1, open.state.rank[X_DEVIL]);
        assertEquals(10_000, open.state.cash);
    }

    @Test
    public void sideStepsRespectGapRequirementsCashAndSlotKind() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 24, 50_000);
        // Daytime: the Devil steps are evening steps.
        Engine.Applied day = act(rules, p, s, Action.activity(X_LIBRARY));
        assertFalse(day.side.contains("unlock:devil:0"));
        // Same evening after the first payment: at most one step per link per slot, and the gap holds the next.
        s.slot = Rules.slot(24, true);
        s.unlockStep[X_DEVIL] = 1;
        s.lastStepDay[X_DEVIL] = 24;
        assertFalse(act(rules, p, s, Action.activity(X_STUDY)).side.contains("unlock:devil:1"));
        // Not enough cash for the fee.
        SimState poor = start(rules, 24, 19_999);
        poor.slot = Rules.slot(24, true);
        assertTrue(act(rules, p, poor, Action.activity(X_STUDY)).side.isEmpty());
        // Requirement (Charm Lv2) applies to every step.
        SimState weak = start(rules, 24, 50_000);
        weak.slot = Rules.slot(24, true);
        weak.statPts[CHARM] = 0;
        assertTrue(act(rules, p, weak, Action.activity(X_STUDY)).side.isEmpty());
        // A paid main action keeps its own fee: ¥21,000 cannot pay ¥20,000 and a ¥1,500 arcade.
        SimState tight = start(rules, 24, 21_000);
        tight.slot = Rules.slot(24, true);
        Engine.Applied arcade = act(rules, p, tight, Action.activity(RulesFixtures.X_ARCADE));
        assertTrue(arcade.side.isEmpty());
        assertEquals(19_500, arcade.state.cash);
    }

    @Test
    public void unwantedLinksTakeNoSideSteps() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        p.otherLinks = false;
        SimState s = start(rules, 24, 50_000);
        s.slot = Rules.slot(24, true);
        assertTrue(act(rules, p, s, Action.activity(X_STUDY)).side.isEmpty());
        p.tier[X_DEVIL] = Profile.Tier.WANT;
        assertEquals(List.of("unlock:devil:0"), act(rules, p, s, Action.activity(X_STUDY)).side);
    }

    @Test
    public void justiceTalksAreSideStepsOnSeparateDaysThenTheOpeningConsumesTheSlot() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 20, 0);
        s.rank[X_PATRON] = 1;
        // Day 20 Tue, 22 Thu, 24 Sat: Justice days. Every daytime action carries one talk.
        int[] days = {20, 22, 24};
        for (int i = 0; i < days.length; i++) {
            s = Engine.advanceTo(rules, p, s, Rules.slot(days[i], false));
            assertFalse("the open step is not legal before its talks", Engine.legalActions(rules, p, s).contains(Action.unlock(X_JUSTICE, 3)));
            Engine.Applied a = act(rules, p, s, Action.activity(X_LIBRARY));
            assertTrue(a.side.toString(), a.side.contains("unlock:justice:" + i));
            s = a.state;
        }
        assertEquals(3, s.unlockStep[X_JUSTICE]);
        // The opening needs day 30 (fromDay) and a Justice day: Saturday 31 is the first.
        s = Engine.advanceTo(rules, p, s, Rules.slot(27, false));
        assertFalse(Engine.legalActions(rules, p, s).contains(Action.unlock(X_JUSTICE, 3)));
        s = Engine.advanceTo(rules, p, s, Rules.slot(31, false));
        assertTrue(Engine.legalActions(rules, p, s).contains(Action.unlock(X_JUSTICE, 3)));
        assertEquals(1, act(rules, p, s, Action.unlock(X_JUSTICE, 3)).state.rank[X_JUSTICE]);
    }

    @Test
    public void justiceNeedsItsLinkRequirementForEveryTalk() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 20, 0);
        assertFalse(act(rules, p, s, Action.activity(X_LIBRARY)).side.contains("unlock:justice:0"));
    }

    @Test
    public void hangedManGiftIsPaidBesideAMeetingAndTheOpeningWaitsADay() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 26, 3_010); // Monday: Hanged Man and patron days
        s.rank[X_PATRON] = 1;
        Engine.Applied a = act(rules, p, s, Action.meet(X_PATRON));
        assertTrue(a.side.contains("unlock:hanged_man:0"));
        assertEquals("the gift keeps the 2,500 reserve", 2_500, a.state.cash);
        assertEquals(2, a.state.rank[X_PATRON]);
        SimState eve = a.state;
        Engine.Applied night = act(rules, p, eve, Action.activity(X_STUDY));
        SimState next = Engine.advanceTo(rules, p, night.state, Rules.slot(28, false)); // Wednesday
        assertTrue(Engine.legalActions(rules, p, next).contains(Action.unlock(X_HANGED, 1)));
        assertFalse("the gift does not fold into the opening (gap 1)", Engine.legalActions(rules, p, s).contains(Action.unlock(X_HANGED, 1)));
    }

    @Test
    public void aFeeThatCanWaitKeepsTheReserve() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 26, 1_000); // the 510 gift would leave less than 2,500
        s.rank[X_PATRON] = 1;
        Engine.Applied a = act(rules, p, s, Action.meet(X_PATRON));
        assertFalse("the Hanged Man has weeks left: the gift waits for the reserve", a.side.contains("unlock:hanged_man:0"));
        assertEquals(1_000, a.state.cash);
        assertEquals(Kernel.FEE_RESERVE, 2_500);
    }

    @Test
    public void sunAsksByDayFetchesThePenThatEveningAndOpensAWeekLater() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 25, 0); // Sunday
        Engine.Applied day = act(rules, p, s, Action.activity(X_LIBRARY));
        assertTrue(day.side.contains("unlock:sun:0"));
        Engine.Applied eve = act(rules, p, day.state, Action.activity(X_STUDY));
        assertTrue(eve.side.contains("unlock:sun:1"));
        SimState next = Engine.advanceTo(rules, p, eve.state, Rules.slot(32, false));
        assertTrue(Engine.legalActions(rules, p, next).contains(Action.unlock(X_SUN, 2)));
    }

    @Test
    public void rumoursFoldIntoASameSlotOpeningOrHappenBeside() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        // Moon: rumour (fixed day 24, daytime) + open (daytime, gap 0) fold into one unlock action.
        SimState s = start(rules, 24, 0);
        assertTrue(Engine.legalActions(rules, p, s).contains(Action.unlock(X_MOON, 1)));
        Engine.Applied fold = act(rules, p, s, Action.unlock(X_MOON, 1));
        assertEquals(1, fold.state.rank[X_MOON]);
        assertFalse("the main unlock's own link has no side step", fold.side.contains("unlock:moon:0"));
        // Or the rumour happens beside another action, and the opening follows on a later slot.
        Engine.Applied beside = act(rules, p, s, Action.activity(X_LIBRARY));
        assertTrue(beside.side.contains("unlock:moon:0"));
        assertEquals(0, beside.state.rank[X_MOON]);
        // Tower: daytime rumour on Monday 26, evening opening (Thursday–Sunday).
        SimState mon = start(rules, 26, 0);
        Engine.Applied heard = act(rules, p, mon, Action.activity(X_LIBRARY));
        assertTrue(heard.side.contains("unlock:tower:0"));
        SimState thu = Engine.advanceTo(rules, p, heard.state, Rules.slot(29, true));
        assertTrue(Engine.legalActions(rules, p, thu).contains(Action.unlock(X_TOWER, 1)));
        // Without the rumour the tower opening is not legal.
        SimState thu0 = Engine.advanceTo(rules, p, mon, Rules.slot(29, true));
        assertFalse(Engine.legalActions(rules, p, thu0).contains(Action.unlock(X_TOWER, 1)));
    }

    @Test
    public void lockedSlotsHaveNoSideSteps() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 39, 0); // daytime locked
        assertTrue(act(rules, p, s, Action.free()).side.isEmpty());
        assertEquals(0, act(rules, p, s, Action.free()).state.unlockStep[X_SUN]);
    }

    // ------------------------------------------------------------------ outcomes

    @Test
    public void outcomeAppliesSideStepsUnlessTheyWereNotDone() throws Exception {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 24, 50_000);
        s.slot = Rules.slot(24, true);
        SimState done = Engine.applyOutcome(rules, p, s, Action.activity(X_STUDY), Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals(1, done.unlockStep[X_DEVIL]);
        assertEquals(30_000, done.cash);
        Outcome not = Outcome.of(Outcome.Code.AS_PREDICTED);
        not.sideDone = false;
        SimState skipped = Engine.applyOutcome(rules, p, s, Action.activity(X_STUDY), not);
        assertEquals(0, skipped.unlockStep[X_DEVIL]);
        assertEquals(50_000, skipped.cash);
        assertEquals(2, skipped.statPts[0]);
        // A main action that did not happen does not stop the side step by default.
        SimState notDone = Engine.applyOutcome(rules, p, s, Action.activity(X_STUDY), Outcome.of(Outcome.Code.NOT_DONE));
        assertEquals(1, notDone.unlockStep[X_DEVIL]);
        assertEquals(0, notDone.statPts[0]);
        // JSON: "sideDone" only written when false.
        assertFalse(Outcome.of(Outcome.Code.AS_PREDICTED).toJson().has("sideDone"));
        JSONObject j = not.toJson();
        assertFalse(j.getBoolean("sideDone"));
        assertFalse(Outcome.fromJson(j).sideDone);
        assertTrue(Outcome.fromJson(new JSONObject("{\"code\":\"AS_PREDICTED\"}")).sideDone);
    }

    @Test
    public void sideStepsAloneForRecordsOutsideApplyOutcome() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 24, 50_000);
        s.slot = Rules.slot(24, true);
        // A Tartarus evening recorded by the store: the Devil payment happens beside the visit.
        SimState side = Engine.applySideSteps(rules, p, s, Action.tower(0));
        assertEquals(s.slot, side.slot);
        assertEquals(1, side.unlockStep[X_DEVIL]);
        assertEquals(30_000, side.cash);
        assertEquals(0, side.towerVisits[0]);
        assertEquals(0, s.unlockStep[X_DEVIL]); // the input is untouched
    }

    /**
     * F2: what was done beside an action is a fact of the past. Recorded side steps apply exactly,
     * whatever the profile in effect wants now; an explicit empty list means none.
     */
    @Test
    public void recordedSideStepsApplyExactlyWhateverTheProfileWantsNow() throws Exception {
        Rules rules = RulesFixtures.sideSteps();
        Profile wanted = RulesFixtures.others();
        Profile nobody = RulesFixtures.others();
        nobody.otherLinks = false;
        SimState s = start(rules, 24, 50_000);
        s.slot = Rules.slot(24, true);
        Action study = Action.activity(X_STUDY);

        // keys == null: the engine's own pick, the same as the forecast.
        assertEquals(act(rules, wanted, s, study).side, Engine.sideSteps(rules, wanted, s, study, null).applied);
        assertTrue(Engine.sideSteps(rules, nobody, s, study, null).applied.isEmpty());

        // Recorded while the Devil was wanted, replayed after it no longer is: still paid.
        Engine.SideSteps paid = Engine.sideSteps(rules, nobody, s, study, List.of("unlock:devil:0"));
        assertEquals(List.of("unlock:devil:0"), paid.applied);
        assertTrue(paid.rejected.isEmpty());
        assertEquals(1, paid.state.unlockStep[X_DEVIL]);
        assertEquals(30_000, paid.state.cash);
        assertEquals(s.slot, paid.state.slot);
        assertEquals(0, s.unlockStep[X_DEVIL]); // the input is untouched
        Outcome one = Outcome.of(Outcome.Code.AS_PREDICTED);
        one.sideKeys = List.of("unlock:devil:0");
        SimState did = Engine.applyOutcome(rules, nobody, s, study, one);
        assertEquals(1, did.unlockStep[X_DEVIL]);
        assertEquals(30_000, did.cash);
        assertEquals(2, did.statPts[0]);

        // Recorded as none, replayed after the Devil became wanted: no payment invented.
        Outcome none = Outcome.of(Outcome.Code.AS_PREDICTED);
        none.sideKeys = List.of();
        SimState nothing = Engine.applyOutcome(rules, wanted, s, study, none);
        assertEquals(0, nothing.unlockStep[X_DEVIL]);
        assertEquals(50_000, nothing.cash);
        assertEquals(2, nothing.statPts[0]);
        // sideDone false still wins over a list.
        one.sideDone = false;
        assertEquals(0, Engine.applyOutcome(rules, wanted, s, study, one).unlockStep[X_DEVIL]);

        // JSON: "sideKeys" only when given, empty lists included.
        assertFalse(Outcome.of(Outcome.Code.AS_PREDICTED).toJson().has("sideKeys"));
        assertEquals(List.of(), Outcome.fromJson(none.toJson()).sideKeys);
        assertEquals(List.of("unlock:devil:0"), Outcome.fromJson(one.toJson()).sideKeys);
        assertEquals(null, Outcome.fromJson(new JSONObject("{\"code\":\"AS_PREDICTED\"}")).sideKeys);
    }

    @Test
    public void recordedSideStepsAreLegalityChecked() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 24, 50_000);
        s.slot = Rules.slot(24, true);
        Action study = Action.activity(X_STUDY);

        // Not the pending step, a second step of one link in a slot, not an unlock step, unknown link.
        Engine.SideSteps mixed = Engine.sideSteps(rules, p, s, study,
                List.of("unlock:devil:1", "unlock:devil:0", "unlock:devil:1", "meet:devil", "unlock:nobody:0"));
        assertEquals(List.of("unlock:devil:0"), mixed.applied);
        assertEquals(List.of("unlock:devil:1", "unlock:devil:1", "meet:devil", "unlock:nobody:0"), mixed.rejected);
        assertEquals(1, mixed.state.unlockStep[X_DEVIL]);
        // The slot-consuming last step is never a side step.
        SimState late = s.copy();
        late.unlockStep[X_DEVIL] = 2;
        late.lastStepDay[X_DEVIL] = 20;
        assertEquals(List.of("unlock:devil:2"), Engine.sideSteps(rules, p, late, study, List.of("unlock:devil:2")).rejected);
        // Wrong slot kind (the payment is an evening step).
        SimState day = start(rules, 24, 50_000);
        assertEquals(List.of("unlock:devil:0"), Engine.sideSteps(rules, p, day, Action.free(), List.of("unlock:devil:0")).rejected);
        // The main unlock action's own link.
        assertEquals(List.of("unlock:devil:0"),
                Engine.sideSteps(rules, p, s, Action.unlock(X_DEVIL, 2), List.of("unlock:devil:0")).rejected);
        // Too soon after the previous step (minGapDays 1).
        SimState again = Engine.sideSteps(rules, p, s, study, List.of("unlock:devil:0")).state;
        assertEquals(List.of("unlock:devil:1"), Engine.sideSteps(rules, p, again, study, List.of("unlock:devil:1")).rejected);
        // Cash is not checked (the player paid): it floors at 0 and stops being exact.
        SimState poor = s.copy();
        poor.cash = 5_000;
        Engine.SideSteps short_ = Engine.sideSteps(rules, p, poor, study, List.of("unlock:devil:0"));
        assertEquals(List.of("unlock:devil:0"), short_.applied);
        assertEquals(0, short_.state.cash);
        assertFalse(short_.state.cashExact);
    }

    @Test
    public void outcomeOfTheMainUnlockDoesNotDoubleApplyItsLink() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 24, 0);
        SimState after = Engine.applyOutcome(rules, p, s, Action.unlock(X_MOON, 1), Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals(1, after.rank[X_MOON]);
        assertEquals(2, after.unlockStep[X_MOON]);
    }

    // ------------------------------------------------------------------ plans

    @Test
    public void planCompletesEveryChainWithSideStepsAndValidates() throws Exception {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 20, 45_000);
        Plan plan = Engine.plan(rules, p, s, null, new Engine.Options());
        List<String> errors = Engine.validate(rules, p, s, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        for (int l : new int[]{X_JUSTICE, X_HANGED, X_DEVIL, X_SUN, X_MOON, X_TOWER}) {
            assertTrue(rules.links[l].id + " never opened", plan.summary.endRanks[l] >= 1);
        }
        int sides = 0;
        long sideFees = 0;
        for (Plan.Step step : plan.steps) {
            assertEquals(step.side.size(), step.sideZh.size());
            for (int i = 0; i < step.side.size(); i++) {
                sides++;
                assertTrue(step.sideZh.get(i), step.sideZh.get(i).startsWith("顺便："));
                Action a = Action.parse(rules, step.side.get(i));
                assertEquals(Action.Type.UNLOCK_STEP, a.type);
                sideFees += rules.links[a.target].unlock.steps.get(a.step).cost;
            }
        }
        // justice 3 talks, hanged gift, devil 2 payments, sun ask + pen, plus rumours not folded.
        assertTrue("side steps " + sides, sides >= 8);
        assertEquals(20_000 + 10_000 + 510, sideFees);
        // JSON round trip keeps the side steps; steps without side steps omit the keys.
        Plan back = Plan.fromJson(new JSONObject(plan.toJson().toString()));
        for (int i = 0; i < plan.steps.size(); i++) {
            assertEquals(plan.steps.get(i).side, back.steps.get(i).side);
            assertEquals(plan.steps.get(i).sideZh, back.steps.get(i).sideZh);
            JSONObject js = plan.steps.get(i).toJson();
            assertEquals(!plan.steps.get(i).side.isEmpty(), js.has("side"));
        }
        assertTrue(Engine.validate(rules, p, s, back).isEmpty());
    }

    @Test
    public void validatorChecksSideStepsIndependently() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 20, 45_000);
        Plan plan = Engine.plan(rules, p, s, null, new Engine.Options());
        assertTrue(Engine.validate(rules, p, s, plan).isEmpty());
        Plan.Step devilPay = null;
        for (Plan.Step step : plan.steps) if (step.side.contains("unlock:devil:0")) devilPay = step;
        assertTrue("plan pays the Devil", devilPay != null);

        // Dropping the listed side step: the later steps are out of order in the recount.
        Plan dropped = copy(plan);
        Plan.Step d = dropped.at(devilPay.slot);
        int i = d.side.indexOf("unlock:devil:0");
        d.side.remove(i);
        d.sideZh.remove(i);
        assertFalse(Engine.validate(rules, p, s, dropped).isEmpty());

        // Moving the payment to a daytime slot: wrong slot kind.
        Plan moved = copy(plan);
        Plan.Step from = moved.at(devilPay.slot);
        from.side.remove("unlock:devil:0");
        from.sideZh.remove(0);
        Plan.Step morning = moved.at(devilPay.slot - 1);
        morning.side.add("unlock:devil:0");
        morning.sideZh.add("顺便：x");
        List<String> errors = Engine.validate(rules, p, s, moved);
        assertTrue(errors.toString(), errors.toString().contains("wrong slot kind") || errors.toString().contains("not allowed"));

        // A side step that does not exist yet (skipping the order).
        Plan skip = copy(plan);
        Plan.Step first = skip.steps.get(0);
        first.side.add("unlock:devil:1");
        first.sideZh.add("顺便：x");
        assertTrue(Engine.validate(rules, p, s, skip).toString().contains("out of order"));

        // A consuming step listed as a side step.
        Plan consume = copy(plan);
        Plan.Step c0 = consume.steps.get(0);
        c0.side.add("unlock:patron:0");
        c0.sideZh.add("顺便：x");
        assertTrue(Engine.validate(rules, p, s, consume).toString().contains("consumes a slot"));
    }

    @Test
    public void alternativesCountSideFeesOnce() {
        Rules rules = RulesFixtures.sideSteps();
        Profile p = RulesFixtures.others();
        SimState s = start(rules, 24, 50_000);
        s.slot = Rules.slot(24, true);
        List<Plan.Step> alts = Engine.alternatives(rules, p, s);
        assertFalse(alts.isEmpty());
        for (Plan.Step a : alts) {
            assertEquals(a.action, List.of("unlock:devil:0"), a.side);
            Action act = Action.parse(rules, a.action);
            long own = act.type == Action.Type.ACTIVITY ? -rules.activities[act.target].cost : 0;
            if (act.type == Action.Type.ACTIVITY && rules.activities[act.target].kind == Rules.ActivityKind.JOB) {
                own = rules.activities[act.target].payFor(1);
            }
            if (act.type == Action.Type.TARTARUS) own = 0;
            assertEquals(a.action, own - 20_000, a.predicted.cashDelta);
        }
        // The unlock alternative of a link never lists that link's own steps beside it.
        SimState day = start(rules, 24, 0);
        for (Plan.Step a : Engine.alternatives(rules, p, day)) {
            if (a.action.equals("unlock:moon:1")) assertFalse(a.side.contains("unlock:moon:0"));
        }
    }

    @Test
    public void plainPlanStepsHaveNoSideKeys() throws Exception {
        Rules rules = RulesFixtures.small();
        Profile p = RulesFixtures.others();
        SimState s = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, p, s, null, new Engine.Options());
        for (Plan.Step step : plan.steps) {
            JSONObject js = step.toJson();
            assertFalse(js.has("side"));
            assertFalse(js.has("sideZh"));
        }
    }

    private static Plan copy(Plan plan) {
        try {
            return Plan.fromJson(new JSONObject(plan.toJson().toString()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
