package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.*;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.Backup;
import app.p3r.dayplanner.v3.store.EventRow;
import app.p3r.dayplanner.v3.store.OutcomeApplier;
import app.p3r.dayplanner.v3.store.TestRules;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Editing yesterday replays its original slot, including the already recorded evening. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class OutcomeCorrectionTest extends ServiceTestBase {
    private static final int DAY = TestRules.slot(TestRules.MAY_12, false);
    private static final int NIGHT = DAY + 1;

    @Test public void yesterdayDaytimeCanBeCorrectedAfterItsTowerEveningWithoutLosingTheVisit() {
        onboard(DAY);
        PlannerApi.Receipt day = record(DAY, "meet:emperor", Outcome.Code.AS_PREDICTED);
        assertTrue(tower(NIGHT, 27, true, 14_750).ok);
        PlannerApi.OutcomeCorrection form = service.outcomeCorrection(day.eventSeq);
        assertNotNull(form);
        assertEquals(DAY, form.slot);
        assertEquals("meet:emperor", form.actionKey);
        assertEquals(6, form.beforeState.rank[TestRules.EMPEROR]);
        assertEquals(10_000, form.beforeState.cash);
        assertEquals(rev(), form.stateRevision);
        form.beforeState.cash = 1;
        assertEquals("context is detached", 14_750, observed().cash);

        PlannerApi.Receipt fixed = service.correctOutcome(id(), rev(), day.eventSeq, Outcome.of(Outcome.Code.NO_RANK_UP));
        assertTrue(fixed.errorZh, fixed.ok);
        assertEquals(DAY + 2, observed().slot);
        assertEquals(6, observed().rank[TestRules.EMPEROR]);
        assertEquals(10, observed().pts[TestRules.EMPEROR]);
        assertEquals(1, observed().towerVisits[1]);
        assertEquals(27, observed().floor);
        assertEquals(14_750, observed().cash);
        assertEquals(2, service.snapshot().eventsSinceCheckpoint);
        assertEquals(Outcome.Code.NO_RANK_UP, service.outcomeCorrection(fixed.eventSeq).outcome.code);
        assertEquals(day.eventSeq, service.history(0).stream().filter(x -> x.seq == fixed.eventSeq).findFirst().get().originalSeq);
        assertTrue(service.history(0).stream().anyMatch(x -> x.seq == day.eventSeq && x.correctable));
        assertTrue(service.history(0).stream().anyMatch(x -> x.seq == fixed.eventSeq && x.correctable));
        restart();
        assertEquals(6, observed().rank[TestRules.EMPEROR]);
        assertEquals(1, observed().towerVisits[1]);
    }

    /**
     * A tower night recorded as 没去成, with later records after it (so it cannot be undone and
     * recorded again): its reason can be changed, and 「其实去了」 counts the visit.
     */
    @Test public void anOlderSkippedTowerNightCanBeEditedIntoAVisit() {
        onboard(DAY);
        assertTrue(record(DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        PlannerApi.Receipt night = record(NIGHT, "tower:1", Outcome.Code.SKIPPED);
        assertTrue(night.errorZh, night.ok);
        assertTrue(record(NIGHT + 1, "free", Outcome.Code.AS_PREDICTED).ok);
        PlannerApi.HistoryItem row = service.history(0).stream().filter(x -> x.seq == night.eventSeq).findFirst().get();
        assertEquals("skip", row.kind);
        assertTrue(row.undoable);
        assertTrue(row.correctable);

        Outcome story = Outcome.of(Outcome.Code.SKIPPED);
        story.reason = "story";
        assertTrue(service.correctOutcome(id(), rev(), night.eventSeq, story).ok);
        assertEquals("story", service.outcomeCorrection(night.eventSeq).outcome.reason);
        PlannerApi.Receipt went = service.correctOutcome(id(), rev(), night.eventSeq, Outcome.of(Outcome.Code.AS_PREDICTED));
        assertTrue(went.errorZh, went.ok);
        assertEquals(NIGHT + 2, observed().slot);
        // Replayed as a visit to the night's block (the engine counts it; this fake only records it).
        int last = -1;
        for (int i = 0; i < applier.actions.size(); i++) {
            if (applier.actions.get(i).type == Action.Type.TARTARUS) last = i;
        }
        assertEquals(1, applier.actions.get(last).target);
        assertEquals(Outcome.Code.AS_PREDICTED, applier.outcomes.get(last).code);
        PlannerApi.HistoryItem fix = service.history(0).stream().filter(x -> x.seq == went.eventSeq).findFirst().get();
        assertTrue(fix.summaryZh, fix.summaryZh.endsWith("修改了这次结果 · 探索塔尔塔罗斯 · 去了"));
        assertTrue(fix.correctable);
    }

    @Test public void aSkippedRowCanBecomeCompletedAndVoidingItsFixRestoresTheSkip() {
        onboard(DAY);
        PlannerApi.Receipt day = record(DAY, "meet:emperor", Outcome.Code.SKIPPED);
        assertTrue(record(NIGHT, "act:study_room", Outcome.Code.AS_PREDICTED).ok);
        PlannerApi.Receipt fixed = service.correctOutcome(id(), rev(), day.eventSeq, Outcome.of(Outcome.Code.AS_PREDICTED));
        assertTrue(fixed.errorZh, fixed.ok);
        assertEquals(7, observed().rank[TestRules.EMPEROR]);
        assertEquals(22, observed().statPts[0]);
        assertEquals(DAY + 2, observed().slot);
        assertTrue(service.voidEvent(id(), rev(), fixed.eventSeq).ok);
        assertEquals(6, observed().rank[TestRules.EMPEROR]);
        assertEquals(22, observed().statPts[0]);
        assertEquals(Outcome.Code.SKIPPED, service.outcomeCorrection(day.eventSeq).outcome.code);
    }

    @Test public void retriesStayIdempotentAfterRestartAndOtherCorrectionsWhileStaleAndConflictsDoNotWrite() {
        onboard(DAY);
        PlannerApi.Receipt day = record(DAY, "meet:emperor", Outcome.Code.AS_PREDICTED);
        String command = id();
        long expected = rev();
        PlannerApi.Receipt first = service.correctOutcome(command, expected, day.eventSeq, Outcome.of(Outcome.Code.NO_RANK_UP));
        assertTrue(first.ok);
        assertTrue(service.correctOutcome(id(), rev(), day.eventSeq, Outcome.of(Outcome.Code.SKIPPED)).ok);
        restart();
        long current = rev();
        PlannerApi.Receipt duplicate = service.correctOutcome(command, expected, day.eventSeq, Outcome.of(Outcome.Code.NO_RANK_UP));
        assertTrue(duplicate.ok);
        assertTrue(duplicate.duplicate);
        assertEquals(first.eventSeq, duplicate.eventSeq);
        assertEquals(current, rev());
        assertEquals(6, observed().rank[TestRules.EMPEROR]);
        assertEquals("conflict", service.correctOutcome(command, current, day.eventSeq, Outcome.of(Outcome.Code.OTHER)).errorCode);
        assertEquals("stale", service.correctOutcome(id(), expected, day.eventSeq, Outcome.of(Outcome.Code.OTHER)).errorCode);
        assertEquals(current, rev());
    }

    @Test public void invalidAndOlderTargetsAreRejectedWithoutChangingTheLedger() {
        onboard(DAY);
        PlannerApi.Receipt day = record(DAY, "meet:emperor", Outcome.Code.AS_PREDICTED);
        long before = rev();
        Outcome invalid = Outcome.of(Outcome.Code.RANK_UP);
        invalid.rank = 6;
        assertFalse(service.correctOutcome(id(), before, day.eventSeq, invalid).ok);
        invalid = Outcome.of(Outcome.Code.OTHER);
        invalid.statLv[0] = 7;
        assertFalse(service.correctOutcome(id(), before, day.eventSeq, invalid).ok);
        invalid = Outcome.of(Outcome.Code.OTHER);
        invalid.cashBalance = -2;
        assertFalse(service.correctOutcome(id(), before, day.eventSeq, invalid).ok);
        assertFalse(service.correctOutcome(id(), before, 999999, Outcome.of(Outcome.Code.AS_PREDICTED)).ok);
        assertEquals(before, rev());
        assertTrue(service.voidEvent(id(), rev(), day.eventSeq).ok);
        assertNull(service.outcomeCorrection(day.eventSeq));
        assertFalse(service.correctOutcome(id(), rev(), day.eventSeq, Outcome.of(Outcome.Code.OTHER)).ok);
        assertTrue(service.saveCheckpoint(id(), rev(), TestRules.state(rules, DAY + 2), null).ok);
        assertFalse(service.correctOutcome(id(), rev(), day.eventSeq, Outcome.of(Outcome.Code.OTHER)).ok);
    }

    @Test public void backupRoundTripPreservesLatestFixAndRejectsChangedActionOrCrossCheckpointTargets() throws Exception {
        onboard(DAY);
        PlannerApi.Receipt day = record(DAY, "meet:emperor", Outcome.Code.AS_PREDICTED);
        assertTrue(record(NIGHT, "act:study_room", Outcome.Code.AS_PREDICTED).ok);
        PlannerApi.Receipt fix = service.correctOutcome(id(), rev(), day.eventSeq, Outcome.of(Outcome.Code.NO_RANK_UP));
        assertTrue(fix.ok);
        String exported = service.exportBackup();
        Backup.Data parsed = Backup.read(exported);
        EventRow fixed = parsed.events.stream().filter(e -> e.seq == fix.eventSeq).findFirst().get();
        assertEquals(day.eventSeq, fixed.target());
        PlannerService restored = open("outcome-correction-restored.db");
        assertTrue(restored.importBackup(id(), exported).ok);
        assertEquals(6, restored.snapshot().observed.rank[TestRules.EMPEROR]);
        assertEquals(22, restored.snapshot().observed.statPts[0]);
        assertEquals(DAY + 2, restored.snapshot().observed.slot);

        JSONObject root = new JSONObject(exported);
        for (int i = 0; i < root.getJSONArray("events").length(); i++) {
            JSONObject row = root.getJSONArray("events").getJSONObject(i);
            if (row.getLong("seq") == fix.eventSeq) row.getJSONObject("payload").getJSONObject("record").put("action", "act:study_room");
        }
        try { Backup.read(root.toString()); fail("changing the original action must be rejected"); }
        catch (Backup.Invalid expected) { assertEquals(Backup.ZH_DAMAGED, expected.zh); }
        root = new JSONObject(exported);
        JSONObject checkpoint = new JSONObject(root.getJSONArray("checkpoints").getJSONObject(0).toString());
        checkpoint.put("id", 999);
        root.getJSONArray("checkpoints").put(checkpoint);
        for (int i = 0; i < root.getJSONArray("events").length(); i++) {
            JSONObject row = root.getJSONArray("events").getJSONObject(i);
            if (row.getLong("seq") == fix.eventSeq) row.put("checkpointId", 999);
        }
        try { Backup.read(root.toString()); fail("cross-checkpoint corrections must be rejected"); }
        catch (Backup.Invalid expected) { assertEquals(Backup.ZH_DAMAGED, expected.zh); }
    }

    @Test public void aReplacementThatCannotReplayIsRejectedAtomically() {
        Wiring wiring = new Wiring();
        wiring.rules = () -> rules;
        wiring.planner = planner;
        wiring.validator = validator;
        wiring.forecaster = forecaster;
        wiring.databaseName = "outcome-correction-reject.db";
        wiring.applier = new OutcomeApplier() {
            @Override public SimState apply(Rules r, Profile p, SimState s, Action a, Outcome o) {
                if ("replay_reject".equals(o.reason)) throw new IllegalArgumentException("bad replacement");
                return applier.apply(r, p, s, a, o);
            }
        };
        PlannerService checked = PlannerService.create(context, wiring);
        try {
            assertTrue(checked.saveCheckpoint(id(), 0, TestRules.state(rules, DAY), null).ok);
            PlannerApi.Receipt row = checked.recordOutcome(id(), 1, DAY, "meet:emperor", Outcome.of(Outcome.Code.NO_RANK_UP));
            Outcome bad = Outcome.of(Outcome.Code.OTHER);
            bad.reason = "replay_reject";
            long revision = checked.snapshot().stateRevision;
            assertFalse(checked.correctOutcome(id(), revision, row.eventSeq, bad).ok);
            assertEquals(revision, checked.snapshot().stateRevision);
            assertEquals(6, checked.snapshot().observed.rank[TestRules.EMPEROR]);
            assertEquals(10, checked.snapshot().observed.pts[TestRules.EMPEROR]);
            assertFalse(checked.history(0).stream().anyMatch(x -> "outcome_fix".equals(x.kind)));
        } finally { checked.close(); }
    }

    @Test public void aNewFailureCannotReplaceAnExistingMismatchEvenWhenProblemCountAndCursorTie() {
        Wiring wiring = new Wiring();
        wiring.rules = () -> rules;
        wiring.planner = planner;
        wiring.validator = validator;
        wiring.forecaster = forecaster;
        wiring.databaseName = "outcome-correction-mismatched.db";
        wiring.applier = new OutcomeApplier() {
            @Override public SimState apply(Rules r, Profile p, SimState s, Action a, Outcome o) {
                if ("legacy_mismatch".equals(o.reason) && s.rank[TestRules.EMPEROR] >= 7)
                    throw new IllegalArgumentException("an existing old-pack mismatch");
                return applier.apply(r, p, s, a, o);
            }
            @Override public Engine.SideSteps sideSteps(Rules r, Profile p, SimState s, Action a, java.util.List<String> keys) {
                if (keys != null && keys.contains("unlock:magician:0") && s.rank[TestRules.EMPEROR] < 7) {
                    Engine.SideSteps rejected = new Engine.SideSteps();
                    rejected.state = s.copy();
                    rejected.applied = java.util.Collections.emptyList();
                    rejected.rejected = new java.util.ArrayList<>(keys);
                    return rejected;
                }
                return applier.sideSteps(r, p, s, a, keys);
            }
        };
        PlannerService checked = PlannerService.create(context, wiring);
        try {
            assertTrue(checked.saveCheckpoint(id(), 0, TestRules.state(rules, DAY), null).ok);
            PlannerApi.Receipt target = checked.recordOutcome(id(), 1, DAY, "meet:emperor", Outcome.of(Outcome.Code.AS_PREDICTED));
            Outcome later = Outcome.of(Outcome.Code.AS_PREDICTED);
            later.sideKeys = java.util.Collections.singletonList("unlock:magician:0");
            assertTrue(checked.recordOutcome(id(), 2, NIGHT, "act:study_room", later).ok);
            Outcome old = Outcome.of(Outcome.Code.AS_PREDICTED);
            old.reason = "legacy_mismatch";
            assertTrue(checked.recordOutcome(id(), 3, DAY + 2, "free", old).ok);
            long revision = checked.snapshot().stateRevision;
            int cursor = checked.snapshot().observed.slot;
            assertNotNull(checked.snapshot().noticeZh);
            assertFalse(checked.correctOutcome(id(), revision, target.eventSeq, Outcome.of(Outcome.Code.NO_RANK_UP)).ok);
            assertEquals(revision, checked.snapshot().stateRevision);
            assertEquals(cursor, checked.snapshot().observed.slot);
            assertEquals(7, checked.snapshot().observed.rank[TestRules.EMPEROR]);
            assertEquals(22, checked.snapshot().observed.statPts[0]);
        } finally { checked.close(); }
    }
}
