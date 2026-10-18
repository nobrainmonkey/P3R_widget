package app.p3r.dayplanner.v3.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Replay with the real engine and the bundled pack: tower evenings, gaps and locked days roll the
 * day over exactly like an outcome does (story ranks, rescue deadlines).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReplayerRealPackTest {
    private Rules rules;
    private Replayer replayer;
    private int fool;
    private long seq;

    @Before
    public void setUp() throws Exception {
        File pack = new File("src/main/assets/p3r/p3r_rules.json");
        rules = RulesLoader.parse(new String(Files.readAllBytes(pack.toPath()), StandardCharsets.UTF_8));
        replayer = new Replayer(rules, new Profile(), OutcomeApplier.ENGINE);
        fool = rules.link("fool").idx;
    }

    private SimState stateAt(int slot) {
        SimState s = StateShape.fit(new SimState(rules.blocks.length, rules.activities.length), rules);
        s.slot = slot;
        s.cash = 10_000;
        s.cashExact = true;
        s.rank[fool] = 2;
        return s;
    }

    @Test
    public void aTowerEveningBeforeAStoryDayAppliesItsStoryRank() throws Exception {
        // May 8 evening; May 9 is the full-moon operation, when the Fool reaches RANK 3.
        int may8Eve = Rules.slot(37, true);
        Replayer.Result r = replayer.replay(stateAt(may8Eve), List.of(tower(may8Eve, 10)), null);

        assertEquals(Rules.slot(39, false), r.state.slot);
        assertEquals("same as the engine's own rollover",
                Engine.advanceTo(rules, new Profile(), stateAt(may8Eve), Rules.slot(39, false)).rank[fool],
                r.state.rank[fool]);
        assertEquals(3, r.state.rank[fool]);
        assertEquals(0, r.problems);
    }

    @Test
    public void aMissedRescueDeadlineIsMarkedAfterATowerEvening() throws Exception {
        Rules.Rescue bunkichi = rules.rescues[0];
        int lastEvening = Rules.slot(bunkichi.toDay, true);
        SimState start = stateAt(lastEvening);
        assertNotNull("Tartarus is open on the last day of the window", rules.blockOn(bunkichi.toDay));
        assertNull(rules.lockOf(lastEvening));
        Replayer.Result r = replayer.replay(start, List.of(tower(lastEvening, 1)), null);
        assertEquals("the deadline passed without a rescue", 1L, r.state.rescueFailed & 1L);
    }

    @Test
    public void aGapRollsOverEveryDayItSkips() throws Exception {
        int may7Day = Rules.slot(36, false);
        int may10Day = Rules.slot(39, false);
        OutcomeRecord study = OutcomeRecord.of(rules, may10Day, "act:study_room_day", Outcome.of(Outcome.Code.SKIPPED));
        Replayer.Result r = replayer.replay(stateAt(may7Day), List.of(row(EventKind.SKIP, study)), null);
        assertEquals(3, r.state.rank[fool]);
    }

    @Test
    public void legacyDormRecordsKeepTheSameStateWithoutRetrospectiveAffinity() throws Exception {
        int slot = Rules.slot(161, true); // September 9.
        int fuuka = rules.linkIndex("priestess");
        Rules.Activity garden = rules.activity("dorm_fuuka_gardening");
        garden.affinityLink = fuuka;
        garden.affinityPts = 1;
        SimState start = stateAt(slot);
        start.rank[fuuka] = 9;
        start.pts[fuuka] = rules.links[fuuka].threshold[10] - 1;
        OutcomeRecord old = OutcomeRecord.of(rules, slot, "act:" + garden.id, Outcome.of(Outcome.Code.AS_PREDICTED))
                .withResolved(rules, List.of(), -1);
        SimState expected = Engine.apply(rules, new Profile(), start,
                app.p3r.dayplanner.v3.engine.Action.activity(garden.idx)).state;
        expected.pts[fuuka] = start.pts[fuuka];
        Replayer.Result result = replayer.replay(start, List.of(row(old.kind(), old)), null);
        assertEquals(0, result.problems);
        assertEquals(expected.toJson().toString(), result.state.toJson().toString());
    }

    @Test
    public void savedDormAffinitySurvivesNewActivityMetadataAndDoesNotMoveItsLink() throws Exception {
        int slot = Rules.slot(161, true); // September 9.
        int fuuka = rules.linkIndex("priestess");
        Rules.Activity garden = rules.activity("dorm_fuuka_gardening");
        SimState start = stateAt(slot);
        start.rank[fuuka] = 9;
        start.pts[fuuka] = rules.links[fuuka].threshold[10] - 1;
        OutcomeRecord saved = OutcomeRecord.of(rules, slot, "act:" + garden.id, Outcome.of(Outcome.Code.AS_PREDICTED))
                .withResolved(rules, List.of(), -1).withActivityAffinity("priestess", 1);
        garden.affinityLink = rules.linkIndex("empress");
        garden.affinityPts = 20;
        Replayer.Result result = replayer.replay(start, List.of(row(saved.kind(), saved)), null);
        assertEquals(0, result.problems);
        assertEquals(start.pts[fuuka] + 1, result.state.pts[fuuka]);
        assertEquals(start.pts[garden.affinityLink], result.state.pts[garden.affinityLink]);
        assertEquals(9, result.state.rank[fuuka]);
    }

    private EventRow tower(int slot, int floor) throws JSONException {
        TowerRecord rec = new TowerRecord();
        rec.slot = slot;
        rec.block = rules.blockOn(Rules.dayOf(slot)).idx;
        rec.floor = floor;
        rec.balanceMode = false;
        rec.amount = 0;
        seq++;
        return new EventRow(seq, "t" + seq, 1, EventKind.TOWER, Canonical.of(rec.toJson()), false, seq);
    }

    private EventRow row(String kind, OutcomeRecord rec) throws JSONException {
        seq++;
        return new EventRow(seq, "o" + seq, 1, kind, Canonical.of(rec.toJson()), false, seq);
    }
}
