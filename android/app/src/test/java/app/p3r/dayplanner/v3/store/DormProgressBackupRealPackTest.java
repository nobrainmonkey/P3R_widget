package app.p3r.dayplanner.v3.store;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Existing 3.2 saves and confirmed dorm progress survive the expanded activity pack. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DormProgressBackupRealPackTest {
    /** Actual activity layout of the published 3.2.0 pack, frozen for the migration fixture. */
    private static final List<String> LEGACY_ACTIVITIES = Arrays.asList(
            "study_room", "study_room_day", "library", "meal_wakatsu", "meal_hagakure",
            "meal_wilduck", "meal_wakatsu_menu", "meal_wakatsu_special", "meal_hagakure_menu",
            "meal_hagakure_special", "karaoke", "arcade_answer", "arcade_youth", "arcade_deceased",
            "movie_academics", "movie_charm", "movie_courage", "coffee_chagall",
            "coffee_chagall_day", "job_chagall", "job_accessory", "job_cinema");

    private String pack;
    private Rules rules;

    @Before
    public void setUp() throws Exception {
        pack = new String(Files.readAllBytes(new File("src/main/assets/p3r/p3r_rules.json").toPath()),
                StandardCharsets.UTF_8);
        rules = RulesLoader.parse(pack);
    }

    @Test
    public void backupFromBeforeDormActivitiesKeepsTrainingJobsAndMenuDiscovery() throws Exception {
        Rules legacy = RulesLoader.parse(pack);
        List<Rules.Activity> old = new ArrayList<>();
        for (String id : LEGACY_ACTIVITIES) old.add(legacy.activities[activity(legacy, id)]);
        legacy.activities = old.toArray(new Rules.Activity[0]);
        reindex(legacy);
        legacy.version = "3.2.0|50f6e869ddd3";
        SimState saved = stateAt(legacy, Rules.slot(100, false));
        saved.used[activity(legacy, "meal_wakatsu_menu")] = 1;
        saved.used[activity(legacy, "meal_hagakure_menu")] = 1;
        saved.used[activity(legacy, "arcade_answer")] = 7;
        saved.shifts[activity(legacy, "job_chagall")] = 9;
        saved.rank[rules.link("emperor").idx] = 6;
        Profile profile = new Profile();
        JSONObject oldProfile = profile.toJson();
        oldProfile.remove("planDormGardening");
        oldProfile.remove("dormTargets");
        Backup.Data data = checkpoint(saved, legacy, oldProfile.toString());

        Backup.Data restored = Backup.read(Backup.write(data, legacy.version, 1));
        PackLayout.Remapped mapped = restored.checkpoints.get(0).stateFor(rules);

        assertEquals(0, mapped.lost);
        assertEquals(saved.cash, mapped.state.cash);
        assertArrayEquals(saved.statPts, mapped.state.statPts);
        assertArrayEquals(saved.rank, mapped.state.rank);
        for (int i = 0; i < LEGACY_ACTIVITIES.size(); i++) {
            int current = activity(rules, LEGACY_ACTIVITIES.get(i));
            assertEquals(LEGACY_ACTIVITIES.get(i), saved.used[i], mapped.state.used[current]);
            assertEquals(LEGACY_ACTIVITIES.get(i), saved.shifts[i], mapped.state.shifts[current]);
        }
        int introduced = 0;
        for (Rules.Activity a : rules.activities) {
            if (!LEGACY_ACTIVITIES.contains(a.id)) {
                introduced++;
                assertEquals("no invented completion: " + a.id, 0, mapped.state.used[a.idx]);
                assertEquals(0, mapped.state.shifts[a.idx]);
            }
        }
        assertTrue("the real expanded pack is exercised", introduced > 0);
        assertFalse("old backups do not assume gardening supplies",
                Profile.fromJson(new JSONObject(restored.profileJson)).planDormGardening);
        assertTrue("old backups do not choose characteristic goals for the player",
                Profile.fromJson(new JSONObject(restored.profileJson)).dormTargets.isEmpty());
    }

    @Test
    public void restoredDormHistoryCountsOnlyCompletedRecordsAfterActivityReordering() throws Exception {
        Profile profile = new Profile();
        profile.planDormGardening = true;
        profile.dormTargets.put("dorm_yukari", Profile.DormTarget.UNLOCK);
        profile.dormTargets.put("dorm_junpei", Profile.DormTarget.UPGRADE);
        SimState saved = stateAt(rules, Rules.slot(76, true)); // June 16, when dorm activities begin.
        Rules.Activity dorm = null;
        List<Integer> slots = new ArrayList<>();
        // Find a real recurring track with four legal opportunities, without duplicating its calendar.
        for (Rules.Activity candidate : rules.activities) {
            if (!candidate.id.startsWith("dorm_")) continue;
            saved.used[candidate.idx] = 2;
            slots = legalSlots(saved, profile, candidate.id, 4);
            if (slots.size() == 4) {
                dorm = candidate;
                break;
            }
            saved.used[candidate.idx] = 0;
        }
        assertTrue("a recurring real dorm track exists", dorm != null);
        saved.slot = slots.get(0);
        int originalIndex = dorm.idx;
        SimState forecast = Engine.apply(rules, profile, saved, Action.activity(originalIndex)).state;
        assertEquals(3, forecast.used[originalIndex]);
        assertEquals("a forecast does not become observed progress", 2, saved.used[originalIndex]);
        Backup.Data data = checkpoint(saved, rules, profile.toJson().toString());
        data.events.add(outcome(1, slots.get(0), dorm.id, Outcome.Code.SKIPPED));
        data.events.add(outcome(2, slots.get(1), dorm.id, Outcome.Code.NOT_DONE));
        data.events.add(outcome(3, slots.get(2), dorm.id, Outcome.Code.AS_PREDICTED));
        data.events.add(outcome(4, slots.get(3), dorm.id, Outcome.Code.AS_PREDICTED));
        data.events.add(new EventRow(5, "void-dorm", 1, EventKind.VOID,
                "{\"target\":3}", false, 5, rules.version));
        Backup.Data restored = Backup.read(Backup.write(data, rules.version, 1));
        assertTrue(restored.events.get(2).voided);
        Profile restoredProfile = Profile.fromJson(new JSONObject(restored.profileJson));
        assertTrue(restoredProfile.planDormGardening);
        assertEquals(Profile.DormTarget.UNLOCK, restoredProfile.dormTarget("dorm_yukari"));
        assertEquals(Profile.DormTarget.UPGRADE, restoredProfile.dormTarget("dorm_junpei"));

        Rules reordered = RulesLoader.parse(pack);
        Collections.reverse(Arrays.asList(reordered.activities));
        reindex(reordered);
        PackLayout.Remapped mapped = restored.checkpoints.get(0).stateFor(reordered);
        assertEquals(0, mapped.lost);
        int remappedIndex = activity(reordered, dorm.id);
        assertEquals(2, mapped.state.used[remappedIndex]);
        Replayer.Result replayed = new Replayer(reordered, restoredProfile, OutcomeApplier.ENGINE)
                .replay(mapped.state, restored.events, null);

        assertEquals(0, replayed.problems);
        assertEquals("skip, not done and undone records give no characteristic progress",
                3, replayed.state.used[remappedIndex]);
        assertArrayEquals("only the completed activity grants its stat gain",
                forecast.statPts, replayed.state.statPts);
        assertEquals("a free activity does not spend the player's money", saved.cash, replayed.state.cash);
        assertEquals("backup and replay leave their checkpoint untouched", 2,
                restored.checkpoints.get(0).state().used[originalIndex]);
    }

    private List<Integer> legalSlots(SimState initial, Profile profile, String id, int count) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = initial.slot; slot <= Rules.slot(rules.lastDay, true); slot++) {
            SimState at = initial.copy();
            at.slot = slot;
            for (Action action : Engine.legalActions(rules, profile, at)) {
                if (action.type == Action.Type.ACTIVITY && id.equals(rules.activities[action.target].id)) {
                    slots.add(slot);
                    break;
                }
            }
            if (slots.size() == count) break;
        }
        return slots;
    }

    private EventRow outcome(long seq, int slot, String id, Outcome.Code code) throws Exception {
        Outcome outcome = Outcome.of(code);
        outcome.sideDone = false;
        OutcomeRecord record = OutcomeRecord.of(rules, slot, "act:" + id, outcome);
        return new EventRow(seq, "dorm-" + seq, 1, record.kind(), Canonical.of(record.toJson()),
                false, seq, rules.version);
    }

    private static Backup.Data checkpoint(SimState state, Rules rules, String profile) throws Exception {
        Backup.Data data = new Backup.Data();
        data.profileJson = profile;
        data.checkpoints.add(new CheckpointRow(1, 0, CheckpointRow.jsonFor(state, rules), rules.version));
        return data;
    }

    private static SimState stateAt(Rules rules, int slot) {
        SimState state = StateShape.fit(new SimState(rules.blocks.length, rules.activities.length), rules);
        state.slot = slot;
        state.cash = 30_000;
        state.cashExact = true;
        return state;
    }

    private static int activity(Rules rules, String id) {
        for (Rules.Activity activity : rules.activities) if (id.equals(activity.id)) return activity.idx;
        throw new AssertionError("activity missing from real pack: " + id);
    }

    private static void reindex(Rules rules) {
        for (int i = 0; i < rules.activities.length; i++) rules.activities[i].idx = i;
    }
}
