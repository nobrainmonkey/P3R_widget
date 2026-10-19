package app.p3r.dayplanner.v3.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // ------------------------------------------------------------------------------ F2 / F1: what a row recorded

    @Test
    public void recordedSideStepsAreAppliedExactlyAndNeverWorkedOutAgain() throws Exception {
        applier.sideFee = 1_000;
        int eve = TestRules.slot(TestRules.MAY_12, true);
        SimState start = TestRules.state(rules, eve);
        OutcomeRecord paid = OutcomeRecord.of(rules, eve, "act:study_room", Outcome.of(Outcome.Code.AS_PREDICTED))
                .withResolved(rules, List.of("unlock:magician:0"), -1);
        assertEquals("magician.intro", paid.sideStepIds.get("unlock:magician:0"));

        Replayer.Result r = replayer.replay(start, List.of(row(paid.kind(), paid.toJson())), null);
        assertEquals(List.of(List.of("unlock:magician:0")), applier.recordedSides);
        assertEquals("the recorded fee, once", 9_000, r.state.cash);
        assertFalse("the applier must not work the side steps out again", applier.outcomes.get(0).sideDone);
        assertEquals(0, applier.sideCalls.get());
        assertEquals(0, r.problems);

        // Recorded as "none": nothing, whatever the engine would pick today.
        OutcomeRecord none = OutcomeRecord.of(rules, eve, "act:study_room", Outcome.of(Outcome.Code.AS_PREDICTED))
                .withResolved(rules, List.of(), -1);
        assertEquals(10_000, replayer.replay(start, List.of(row(none.kind(), none.toJson())), null).state.cash);
        assertEquals(List.of(), applier.recordedSides.get(1));

        // Said not done: the stored list is not used.
        Outcome notDone = Outcome.of(Outcome.Code.AS_PREDICTED);
        notDone.sideDone = false;
        OutcomeRecord skipped = OutcomeRecord.of(rules, eve, "act:study_room", notDone)
                .withResolved(rules, List.of("unlock:magician:0"), -1);
        assertEquals(10_000, replayer.replay(start, List.of(row(skipped.kind(), skipped.toJson())), null).state.cash);
        assertEquals(2, applier.recordedSides.size());

        // A step that can no longer be applied as recorded is counted (同步现状 notice).
        applier.refuse.add("unlock:magician:0");
        Replayer.Result refused = replayer.replay(start, List.of(row(paid.kind(), paid.toJson())), null);
        assertEquals(1, refused.problems);
        assertEquals(10_000, refused.state.cash);
    }

    @Test
    public void recordedSideStepsFollowTheirStepIdAcrossAPackUpdate() throws Exception {
        int eve = TestRules.slot(TestRules.MAY_12, true);
        SimState start = TestRules.state(rules, eve);
        OutcomeRecord rec = OutcomeRecord.of(rules, eve, "act:study_room", Outcome.of(Outcome.Code.AS_PREDICTED))
                .withResolved(rules, List.of("unlock:magician:0"), -1);
        EventRow row = row(rec.kind(), rec.toJson());

        Rules update = TestRules.create();
        Rules.Step greet = new Rules.Step();
        greet.id = "magician.greet";
        update.links[TestRules.MAGICIAN].unlock.steps.add(0, greet);
        Replayer.Result moved = new Replayer(update, new Profile(), applier).replay(start, List.of(row), null);
        assertEquals(0, moved.problems);
        assertEquals(List.of("unlock:magician:1"), applier.recordedSides.get(0));

        Rules removed = TestRules.create();
        removed.links[TestRules.MAGICIAN].unlock.steps.clear();
        removed.links[TestRules.MAGICIAN].unlock.steps.add(greet);
        Replayer.Result gone = new Replayer(removed, new Profile(), applier).replay(start, List.of(row), null);
        assertEquals("a side step the pack no longer has is counted", 1, gone.problems);
        assertEquals(List.of(), applier.recordedSides.get(1));
    }

    @Test
    public void towerRowsUseTheirRecordedSideStepsAndCorrectionsKeepThem() throws Exception {
        applier.sideFee = 1_000;
        int eve = TestRules.slot(TestRules.MAY_12, true);
        SimState start = TestRules.state(rules, eve);

        TowerRecord none = towerRecord(eve, 20, false, 3_000);
        none.resolveSide(rules, List.of());
        Replayer.Result r = replayer.replay(start, List.of(row(EventKind.TOWER, none.toJson())), null);
        assertEquals("recorded as none: no fee, and nothing worked out", 13_000, r.state.cash);
        assertEquals(0, applier.sideCalls.get());

        TowerRecord paid = towerRecord(eve, 20, false, 3_000);
        paid.resolveSide(rules, List.of("unlock:magician:0"));
        EventRow visit = row(EventKind.TOWER, paid.toJson());
        EventRow fix = fix(visit.seq, eve, 25, false, 5_000);
        Replayer.Result fixed = replayer.replay(start, List.of(visit, fix), null);
        assertEquals("the visit's side fee once, then the corrected net change", 14_000, fixed.state.cash);
        assertEquals(25, fixed.state.floor);
        assertEquals(List.of("unlock:magician:0"), applier.recordedSides.get(applier.recordedSides.size() - 1));
        assertEquals(0, applier.sideCalls.get());
    }

    @Test
    public void aPredictedMeetingKeepsTheRankItRecorded() throws Exception {
        int day = TestRules.slot(TestRules.MAY_12, false);
        SimState start = TestRules.state(rules, day);
        OutcomeRecord widget = OutcomeRecord.of(rules, day, "meet:emperor", Outcome.of(Outcome.Code.AS_PREDICTED))
                .withResolved(rules, List.of(), 7);
        replayer.replay(start, List.of(row(widget.kind(), widget.toJson())), null);
        assertEquals("the RANK resolved when recording", 7, applier.outcomes.get(0).rank);

        Outcome sheet = Outcome.of(Outcome.Code.AS_PREDICTED);
        sheet.rank = 6;
        OutcomeRecord confirmed = OutcomeRecord.of(rules, day, "meet:emperor", sheet).withResolved(rules, List.of(), 7);
        replayer.replay(start, List.of(row(confirmed.kind(), confirmed.toJson())), null);
        assertEquals("the rank the player confirmed wins", 6, applier.outcomes.get(1).rank);

        OutcomeRecord no = OutcomeRecord.of(rules, day, "meet:emperor", Outcome.of(Outcome.Code.NO_RANK_UP))
                .withResolved(rules, List.of(), 7);
        replayer.replay(start, List.of(row(no.kind(), no.toJson())), null);
        assertEquals(-1, applier.outcomes.get(2).rank);
    }

    @Test
    public void resolvedValuesAreNotPartOfTheCommandFingerprint() throws Exception {
        int day = TestRules.slot(TestRules.MAY_12, false);
        OutcomeRecord request = OutcomeRecord.of(rules, day, "meet:emperor", Outcome.of(Outcome.Code.AS_PREDICTED));
        String json = Canonical.of(request.toJson());
        String stored = Canonical.of(request.withResolved(rules, List.of("unlock:magician:0"), 7).toJson());
        assertTrue(stored.contains("\"side\":[\"unlock:magician:0\"]"));
        assertTrue(stored.contains("\"sideStepIds\":{\"unlock:magician:0\":\"magician.intro\"}"));
        assertTrue(stored.contains("\"rankAfter\":7"));
        assertEquals(Canonical.sha256(json), EventRow.commandSha256(json));
        assertEquals(Canonical.sha256(json), EventRow.commandSha256(stored));
        Outcome other = Outcome.of(Outcome.Code.NO_RANK_UP);
        String different = Canonical.of(OutcomeRecord.of(rules, day, "meet:emperor", other).toJson());
        assertFalse(EventRow.commandSha256(different).equals(EventRow.commandSha256(stored)));

        OutcomeRecord back = OutcomeRecord.fromJson(new JSONObject(stored));
        assertEquals(List.of("unlock:magician:0"), back.side);
        assertEquals(7, back.rankAfter);
        assertEquals(null, OutcomeRecord.fromJson(new JSONObject(json)).side);
        TowerRecord tower = towerRecord(TestRules.slot(TestRules.MAY_12, true), 20, false, 0);
        tower.sideKeys = List.of("unlock:magician:0");
        String towerRequest = Canonical.of(tower.toJson());
        tower.resolveSide(rules, List.of("unlock:magician:0"));
        TowerRecord towerBack = TowerRecord.fromJson(new JSONObject(Canonical.of(tower.toJson())));
        assertEquals(List.of("unlock:magician:0"), towerBack.sideKeys);
        assertEquals(List.of("unlock:magician:0"), towerBack.side);
        assertEquals("magician.intro", towerBack.sideStepIds.get("unlock:magician:0"));
        assertEquals(Canonical.sha256(towerRequest), EventRow.commandSha256(Canonical.of(tower.toJson())));
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
