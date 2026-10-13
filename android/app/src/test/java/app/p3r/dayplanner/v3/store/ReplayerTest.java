package app.p3r.dayplanner.v3.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** Observed state = checkpoint + replay; tower cash exactly once; fixes never add visits. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReplayerTest {
    private Rules rules;
    private FakeApplier applier;
    private Replayer replayer;
    private long seq;

    @Before
    public void setUp() {
        rules = TestRules.create();
        applier = new FakeApplier();
        replayer = new Replayer(rules, new Profile(), applier);
        seq = 0;
    }

    @Test
    public void balanceModeSetsCashAndCountsOneVisit() throws Exception {
        int slot = TestRules.slot(TestRules.MAY_12, true);
        SimState start = TestRules.state(rules, slot);
        List<EventRow> rows = List.of(tower(slot, 27, true, 14_750));

        Replayer.Result r = replayer.replay(start, rows, null);

        assertEquals(14_750, r.state.cash);
        assertTrue(r.state.cashExact);
        assertEquals(1, r.state.towerVisits[1]);
        assertEquals(0, r.state.towerVisits[0]);
        assertEquals(27, r.state.floor);
        assertEquals(slot + 1, r.state.slot);
        assertEquals(1, r.towerGains.size());
        assertEquals(4_750, r.towerGains.get(0).net);
    }

    @Test
    public void netModeAddsExactlyOncePerRecord() throws Exception {
        int day = TestRules.MAY_12;
        SimState start = TestRules.state(rules, TestRules.slot(day, true));
        List<EventRow> rows = List.of(
                tower(TestRules.slot(day, true), 20, false, 3_000),
                tower(TestRules.slot(day + 1, true), 18, false, -500));

        Replayer.Result r = replayer.replay(start, rows, null);

        assertEquals(10_000 + 3_000 - 500, r.state.cash);
        assertEquals(2, r.state.towerVisits[1]);
        assertEquals("floor is the maximum reached", 20, r.state.floor);
    }

    @Test
    public void correctionReplacesValuesWithoutAddingAVisit() throws Exception {
        int slot = TestRules.slot(TestRules.MAY_12, true);
        SimState start = TestRules.state(rules, slot);
        EventRow original = tower(slot, 20, false, 3_000);
        EventRow fix = fix(original.seq, slot, 27, true, 20_000);

        Replayer.Result r = replayer.replay(start, List.of(original, fix), null);

        assertEquals(1, r.state.towerVisits[1]);
        assertEquals(20_000, r.state.cash);
        assertEquals(27, r.state.floor);
        assertEquals(1, r.towerGains.size());
        assertEquals(10_000, r.towerGains.get(0).net);

        Replayer.Result undone = replayer.replay(start, List.of(original, fix.withVoided(true)), null);
        assertEquals("a voided fix restores the original values", 13_000, undone.state.cash);
        assertEquals(1, undone.state.towerVisits[1]);
    }

    @Test
    public void voidedRowsDoNotReplayButAreTraced() throws Exception {
        int slot = TestRules.slot(TestRules.MAY_12, false);
        SimState start = TestRules.state(rules, slot);
        EventRow meet = outcome(slot, "meet:emperor", Outcome.of(Outcome.Code.RANK_UP)).withVoided(true);
        List<String> traced = new ArrayList<>();

        Replayer.Result r = replayer.replay(start, List.of(meet),
                (row, before, after, tower) -> traced.add(row.seq + ":" + before.rank[TestRules.EMPEROR]
                        + "->" + after.rank[TestRules.EMPEROR]));

        assertEquals(6, r.state.rank[TestRules.EMPEROR]);
        assertEquals("the slot is free to record again", slot, r.state.slot);
        assertEquals(List.of(meet.seq + ":6->7"), traced);
    }

    @Test
    public void lockedSlotsAreSkippedAndLaterRowsMoveTimeForward() throws Exception {
        int fullMoonEvening = TestRules.slot(TestRules.FULL_MOON_DAY, true);
        SimState start = TestRules.state(rules, TestRules.slot(TestRules.FULL_MOON_DAY, false));
        EventRow study = outcome(TestRules.slot(TestRules.FULL_MOON_DAY, false), "act:study_room",
                Outcome.of(Outcome.Code.AS_PREDICTED));

        Replayer.Result r = replayer.replay(start, List.of(study), null);
        assertEquals("evening of May 9 is locked", fullMoonEvening + 1, r.state.slot);
        assertEquals(22, r.state.statPts[0]);

        EventRow later = outcome(fullMoonEvening + 5, "act:study_room", Outcome.of(Outcome.Code.AS_PREDICTED));
        Replayer.Result jumped = replayer.replay(start, List.of(later), null);
        assertEquals(fullMoonEvening + 6, jumped.state.slot);
        assertEquals(0, jumped.problems);
    }

    @Test
    public void rowForAPassedSlotIsIgnoredAndCounted() throws Exception {
        int slot = TestRules.slot(TestRules.MAY_12, false);
        SimState start = TestRules.state(rules, slot);
        List<EventRow> rows = List.of(
                outcome(slot, "act:study_room", Outcome.of(Outcome.Code.AS_PREDICTED)),
                outcome(slot, "act:study_room", Outcome.of(Outcome.Code.AS_PREDICTED)));

        Replayer.Result r = replayer.replay(start, rows, null);

        assertEquals(1, r.problems);
        assertEquals(22, r.state.statPts[0]);
    }

    @Test
    public void rescueAndExplicitCashAreApplied() throws Exception {
        int slot = TestRules.slot(TestRules.MAY_12, true);
        SimState start = TestRules.state(rules, slot);
        start.rescueFailed = 1L;
        TowerRecord rec = new TowerRecord();
        rec.slot = slot;
        rec.block = 1;
        rec.floor = 30;
        rec.balanceMode = false;
        rec.amount = 0;
        rec.rescuedId = "bunkichi";
        Replayer.Result r = replayer.replay(start, List.of(row(EventKind.TOWER, rec.toJson())), null);
        assertEquals(1L, r.state.rescued);
        assertEquals(0L, r.state.rescueFailed);

        Outcome withCash = Outcome.of(Outcome.Code.AS_PREDICTED);
        withCash.cashBalance = 777;
        Replayer.Result paid = replayer.replay(start, List.of(outcome(slot, "act:job_chagall", withCash)), null);
        assertEquals(777, paid.state.cash);
    }

    @Test
    public void unknownActionKeysAndBrokenRowsNeverThrow() throws Exception {
        int slot = TestRules.slot(TestRules.MAY_12, false);
        SimState start = TestRules.state(rules, slot);
        EventRow unknown = outcome(slot, "meet:nobody", Outcome.of(Outcome.Code.AS_PREDICTED));
        EventRow broken = new EventRow(++seq, "c" + seq, 1, EventKind.OUTCOME, "{\"slot\":" + (slot + 1) + "}", false, 0);

        Replayer.Result r = replayer.replay(start, List.of(unknown, broken), null);

        assertEquals("an unknown action key and a broken row both count", 2, r.problems);
        assertEquals(slot + 2, r.state.slot);
        assertEquals(10_000, r.state.cash);
    }

    @Test
    public void aRecordInASlotThatANewPackLocksStaysInEffect() throws Exception {
        int day = TestRules.slot(TestRules.MAY_12, false);
        int eve = day + 1;
        SimState start = TestRules.state(rules, day);
        TowerRecord rescue = towerRecord(eve, 30, false, 2_000);
        rescue.rescuedId = "bunkichi";
        List<EventRow> rows = List.of(
                outcome(day, "act:study_room", Outcome.of(Outcome.Code.AS_PREDICTED)),
                row(EventKind.TOWER, rescue.toJson()));

        Rules update = TestRules.create();
        update.days[TestRules.MAY_12].eveLock = "full_moon";
        Replayer.Result r = new Replayer(update, new Profile(), applier).replay(start, rows, null);

        assertEquals(0, r.problems);
        assertEquals("the visit still counts", 1, r.state.towerVisits[1]);
        assertEquals(1L, r.state.rescued);
        assertEquals(12_000, r.state.cash);
        assertEquals(TestRules.slot(TestRules.MAY_12 + 1, false), r.state.slot);
    }

    @Test
    public void towerVisitsAndGapsRollTheDayOverLikeTheEngine() throws Exception {
        RolloverApplier rollover = new RolloverApplier();
        Replayer replay = new Replayer(rules, new Profile(), rollover);
        int eve = TestRules.slot(TestRules.MAY_12, true);
        SimState start = TestRules.state(rules, eve);

        Replayer.Result r = replay.replay(start, List.of(tower(eve, 20, false, 0)), null);
        assertEquals("entering the next day after a tower evening", List.of(TestRules.MAY_12 + 1), rollover.days);
        assertEquals(3, r.state.rank[0]);

        rollover.days.clear();
        int later = TestRules.slot(TestRules.MAY_12 + 3, false);
        replay.replay(start, List.of(outcome(later, "act:study_room", Outcome.of(Outcome.Code.AS_PREDICTED))), null);
        assertEquals("a gap rolls over every day it skips",
                List.of(TestRules.MAY_12 + 1, TestRules.MAY_12 + 2, TestRules.MAY_12 + 3), rollover.days);
    }

    @Test
    public void towerRecordsApplyTheSlotsSideStepsUnlessNotDone() throws Exception {
        applier.sideFee = 1_000;
        int eve = TestRules.slot(TestRules.MAY_12, true);
        SimState start = TestRules.state(rules, eve);

        Replayer.Result done = replayer.replay(start, List.of(tower(eve, 20, false, 3_000)), null);
        assertEquals(1, applier.sideCalls.get());
        assertEquals("side fee first, then the visit's net change", 12_000, done.state.cash);
        assertEquals("the side fee is not counted as tower income", 3_000, done.towerGains.get(0).net);

        TowerRecord notDone = towerRecord(eve, 20, true, 15_000);
        notDone.sideDone = false;
        Replayer.Result skipped = replayer.replay(start, List.of(row(EventKind.TOWER, notDone.toJson())), null);
        assertEquals("not applied when the player says so", 1, applier.sideCalls.get());
        assertEquals(15_000, skipped.state.cash);
        assertEquals(5_000, skipped.towerGains.get(0).net);
    }

    @Test
    public void unlockKeysFollowTheirStepIdAcrossAPackUpdate() throws Exception {
        int day = TestRules.slot(TestRules.MAY_12, false);
        SimState start = TestRules.state(rules, day);
        OutcomeRecord rec = OutcomeRecord.of(rules, day, "unlock:magician:0", Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals("magician.intro", rec.stepId);
        EventRow unlock = row(rec.kind(), rec.toJson());

        Rules update = TestRules.create();
        Rules.Step greet = new Rules.Step();
        greet.id = "magician.greet";
        update.links[TestRules.MAGICIAN].unlock.steps.add(0, greet);
        Replayer.Result r = new Replayer(update, new Profile(), applier).replay(start, List.of(unlock), null);
        assertEquals(0, r.problems);
        assertEquals(app.p3r.dayplanner.v3.engine.Action.unlock(TestRules.MAGICIAN, 1), applier.actions.get(0));

        Rules removed = TestRules.create();
        removed.links[TestRules.MAGICIAN].unlock.steps.clear();
        removed.links[TestRules.MAGICIAN].unlock.steps.add(greet);
        Replayer.Result gone = new Replayer(removed, new Profile(), applier).replay(start, List.of(unlock), null);
        assertEquals("a step the pack no longer has is counted", 1, gone.problems);
        assertEquals(app.p3r.dayplanner.v3.engine.Action.free(), applier.actions.get(1));
    }

    @Test
    public void towerKeysAndRecordsUseTheBlockOfTheirDay() throws Exception {
        int eve = TestRules.slot(TestRules.MAY_12, true);
        SimState start = TestRules.state(rules, eve);
        replayer.replay(start, List.of(outcome(eve, "tower:0", Outcome.of(Outcome.Code.AS_PREDICTED))), null);
        assertEquals(app.p3r.dayplanner.v3.engine.Action.tower(1), applier.actions.get(0));

        TowerRecord stale = towerRecord(eve, 20, false, 0);
        stale.block = 0;
        Replayer.Result r = replayer.replay(start, List.of(row(EventKind.TOWER, stale.toJson())), null);
        assertEquals(0, r.state.towerVisits[0]);
        assertEquals(1, r.state.towerVisits[1]);
        assertEquals(0, r.problems);
    }

    /** {@link FakeApplier} whose time moves through day rollovers: entering day 42 sets Fool to RANK 3. */
    private static final class RolloverApplier implements OutcomeApplier {
        final FakeApplier inner = new FakeApplier();
        final List<Integer> days = new ArrayList<>();

        @Override
        public SimState apply(Rules rules, Profile profile, SimState state, app.p3r.dayplanner.v3.engine.Action action,
                              Outcome outcome) {
            SimState s = inner.apply(rules, profile, state, action, outcome);
            s.slot = state.slot;
            return advanceTo(rules, profile, s, state.slot + 1);
        }

        @Override
        public SimState advanceTo(Rules rules, Profile profile, SimState state, int slot) {
            SimState s = state.copy();
            while (s.slot < slot) {
                s.slot++;
                if (!Rules.isEvening(s.slot)) {
                    int day = Rules.dayOf(s.slot);
                    days.add(day);
                    if (day == TestRules.MAY_12 + 1) {
                        s.rank[0] = Math.max(s.rank[0], 3);
                    }
                }
            }
            return s;
        }
    }

    // ------------------------------------------------------------------------------ helpers

    private EventRow outcome(int slot, String action, Outcome outcome) throws JSONException {
        OutcomeRecord rec = new OutcomeRecord(slot, action, outcome);
        return row(rec.kind(), rec.toJson());
    }

    private EventRow tower(int slot, int floor, boolean balance, long amount) throws JSONException {
        return row(EventKind.TOWER, towerRecord(slot, floor, balance, amount).toJson());
    }

    private TowerRecord towerRecord(int slot, int floor, boolean balance, long amount) {
        TowerRecord rec = new TowerRecord();
        rec.slot = slot;
        rec.block = rules.blockOn(Rules.dayOf(slot)).idx;
        rec.floor = floor;
        rec.balanceMode = balance;
        rec.amount = amount;
        return rec;
    }

    private EventRow fix(long target, int slot, int floor, boolean balance, long amount) throws JSONException {
        TowerRecord rec = new TowerRecord();
        rec.slot = slot;
        rec.block = 1;
        rec.floor = floor;
        rec.balanceMode = balance;
        rec.amount = amount;
        JSONObject o = new JSONObject();
        o.put("target", target);
        o.put("tower", rec.toJson());
        return row(EventKind.TOWER_FIX, o);
    }

    private EventRow row(String kind, JSONObject payload) {
        seq++;
        return new EventRow(seq, "c" + seq, 1, kind, Canonical.of(payload), false, seq);
    }
}
