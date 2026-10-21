package app.p3r.dayplanner.v3.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Index-based state survives a rule-pack update: every stored value follows its id, values whose
 * entity is gone are counted, and the rescue rule applies when a checkpoint is loaded.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PackLayoutTest {
    private Rules before;

    @Before
    public void setUp() {
        before = TestRules.create();
    }

    @Test
    public void layoutRoundTripsThroughJson() throws Exception {
        PackLayout layout = PackLayout.of(before);
        assertEquals(layout, PackLayout.fromJson(new JSONObject(layout.toJson().toString())));
        assertNull("checkpoints from before layouts have none", PackLayout.inCheckpoint(new JSONObject("{\"slot\":3}")));
    }

    @Test
    public void activityCountersFollowTheirIdsWhenThePackInsertsOne() throws Exception {
        SimState s = TestRules.state(before, TestRules.slot(TestRules.MAY_12, false));
        s.shifts[TestRules.CAFE] = 5;
        s.used[TestRules.STUDY] = 1;

        Rules after = TestRules.create();
        Rules.Activity library = new Rules.Activity();
        library.id = "library";
        library.zh = "去图书馆学习";
        library.gain = new int[]{2, 0, 0};
        after.activities = new Rules.Activity[]{library, after.activities[0], after.activities[1]};
        for (int i = 0; i < after.activities.length; i++) {
            after.activities[i].idx = i;
        }

        PackLayout.Remapped r = PackLayout.of(before).remap(s, after);

        assertEquals(3, r.state.shifts.length);
        assertEquals("the café job keeps its shifts", 5, r.state.shifts[2]);
        assertEquals(1, r.state.used[1]);
        assertEquals(0, r.state.used[0]);
        assertEquals(0, r.lost);
        assertEquals(6, r.state.rank[TestRules.EMPEROR]);
    }

    @Test
    public void valuesOfRemovedEntitiesAreCounted() {
        SimState s = TestRules.state(before, TestRules.slot(TestRules.MAY_12, false));
        s.shifts[TestRules.CAFE] = 3;
        s.towerVisits[0] = 2;
        Rules after = TestRules.create();
        after.activities = new Rules.Activity[]{after.activities[TestRules.STUDY]};
        after.blocks = new Rules.Block[]{after.blocks[1]};
        after.blocks[0].idx = 0;

        PackLayout.Remapped r = PackLayout.of(before).remap(s, after);

        assertEquals(1, r.state.shifts.length);
        assertEquals(0, r.state.towerVisits[0]);
        assertEquals("shifts of a removed job and visits of a removed block", 2, r.lost);
    }

    @Test
    public void rescueBitsAndEpisodesFollowTheirIds() {
        Rules withTwo = TestRules.create();
        Rules.Rescue maiko = new Rules.Rescue();
        maiko.id = "maiko";
        maiko.zh = "舞子";
        maiko.fromDay = 203;
        maiko.toDay = 215;
        maiko.link = 12;
        withTwo.rescues = new Rules.Rescue[]{withTwo.rescues[0], maiko};
        SimState s = TestRules.state(withTwo, TestRules.slot(TestRules.MAY_12, false));
        s.rescued = 0b10;
        s.rescueFailed = 0b01;
        s.episodes = new int[]{3};

        Rules swapped = TestRules.create();
        swapped.rescues = new Rules.Rescue[]{maiko, swapped.rescues[0]};
        Rules.Episode akihiko = new Rules.Episode();
        akihiko.id = "akihiko";
        akihiko.personZh = "真田明彦";
        swapped.episodes = new Rules.Episode[]{akihiko, swapped.episodes[0]};
        swapped.episodes[0].idx = 0;
        swapped.episodes[1].idx = 1;

        PackLayout.Remapped r = PackLayout.of(withTwo).remap(s, swapped);

        assertEquals("maiko rescued", 0b01, r.state.rescued);
        assertEquals("bunkichi failed", 0b10, r.state.rescueFailed);
        assertEquals(0, r.state.episodes[0]);
        assertEquals(3, r.state.episodes[1]);
        assertEquals(0, r.lost);
    }

    @Test
    public void addingRyojiKeepsAllFiveSavedTracksAndStartsHisMeetingsAtZero() throws Exception {
        String pack = new String(Files.readAllBytes(Paths.get("src/main/assets/p3r/p3r_rules.json")),
                StandardCharsets.UTF_8);
        Rules current = RulesLoader.parse(pack);
        assertEquals(6, current.episodes.length);
        assertEquals("ryoji", current.episodes[5].id);
        Rules legacy = RulesLoader.parse(pack);
        legacy.episodes = Arrays.copyOf(legacy.episodes, 5);
        legacy.version = "3.6.1|saved-five-tracks";
        SimState saved = new SimState(legacy.blocks.length, legacy.activities.length);
        saved.slot = Rules.slot(current.episodes[5].firstDay(1) + 8, false);
        saved.episodes = new int[]{5, 5, 5, 5, 4};
        saved.cash = 1_234_567;
        saved.cashExact = true;
        saved.statPts = new int[]{230, 100, 80};
        saved.rank[current.link("emperor").idx] = 6;
        saved.shifts[0] = 7;
        saved.used[0] = 3;
        SimState expected = saved.copy();
        expected.episodes = new int[]{5, 5, 5, 5, 4, 0};

        // Both the current checkpoint format and older checkpoints without a layout survive.
        for (String json : new String[]{CheckpointRow.jsonFor(saved, legacy), Canonical.of(saved.toJson())}) {
            CheckpointRow checkpoint = new CheckpointRow(1, 0, json, legacy.version);
            PackLayout.Remapped loaded = checkpoint.stateFor(current);
            assertEquals(0, loaded.lost);
            assertArrayEquals(expected.episodes, loaded.state.episodes);
            assertEquals("all other recorded progress stays unchanged", Canonical.of(expected.toJson()),
                    Canonical.of(loaded.state.toJson()));
        }
        assertArrayEquals("loading never mutates the old checkpoint state", new int[]{5, 5, 5, 5, 4}, saved.episodes);
    }

    @Test
    public void unlockProgressFollowsStepIds() {
        Rules rules = TestRules.create();
        Rules.Step greet = new Rules.Step();
        greet.id = "magician.greet";
        greet.zh = "打个招呼";
        rules.links[TestRules.MAGICIAN].unlock.steps.add(0, greet);
        SimState s = TestRules.state(rules, TestRules.slot(TestRules.MAY_12, false));
        s.unlockStep[TestRules.MAGICIAN] = 1;

        Rules reordered = TestRules.create();
        reordered.links[TestRules.MAGICIAN].unlock.steps.add(greet);
        PackLayout.Remapped r = PackLayout.of(rules).remap(s, reordered);
        assertEquals("the done step is no longer the first one", 0, r.state.unlockStep[TestRules.MAGICIAN]);
        assertEquals(1, r.lost);

        Rules same = TestRules.create();
        same.links[TestRules.MAGICIAN].unlock.steps.add(0, greet);
        assertEquals(1, PackLayout.of(rules).remap(s, same).state.unlockStep[TestRules.MAGICIAN]);

        s.rank[TestRules.MAGICIAN] = 2;
        s.unlockStep[TestRules.MAGICIAN] = 2;
        Rules longer = TestRules.create();
        Rules.Step extra = new Rules.Step();
        extra.id = "magician.extra";
        longer.links[TestRules.MAGICIAN].unlock.steps.add(extra);
        longer.links[TestRules.MAGICIAN].unlock.steps.add(greet);
        PackLayout.Remapped established = PackLayout.of(rules).remap(s, longer);
        assertEquals("an established link has every step done", 3, established.state.unlockStep[TestRules.MAGICIAN]);
        assertEquals(0, established.lost);
    }

    @Test
    public void checkpointsWithoutALayoutKeepPositionsAndCountWhatNoLongerFits() throws Exception {
        SimState s = TestRules.state(before, TestRules.slot(TestRules.MAY_12, false));
        s.shifts[TestRules.CAFE] = 4;
        CheckpointRow legacy = new CheckpointRow(1, 0, Canonical.of(s.toJson()));
        assertFalse(legacy.hasLayout());
        Rules smaller = TestRules.create();
        smaller.activities = new Rules.Activity[]{smaller.activities[TestRules.STUDY]};

        PackLayout.Remapped r = legacy.stateFor(smaller);

        assertEquals(1, r.state.shifts.length);
        assertEquals(1, r.lost);
    }

    @Test
    public void checkpointJsonCarriesTheLayout() throws Exception {
        SimState s = TestRules.state(before, TestRules.slot(TestRules.MAY_12, false));
        CheckpointRow row = new CheckpointRow(1, 0, CheckpointRow.jsonFor(s, before), before.version);
        assertTrue(row.hasLayout());
        assertNotNull(PackLayout.inCheckpoint(new JSONObject(row.json)));
        assertEquals(s.slot, row.state().slot);
        assertEquals(0, row.stateFor(before).lost);
    }

    @Test
    public void loadingAppliesTheRescueRule() throws Exception {
        int afterWindow = TestRules.slot(190, false);
        SimState s = TestRules.state(before, afterWindow);
        s.rank[TestRules.HIEROPHANT] = 3;
        s.rescueFailed = 1L;

        CheckpointRow explicit = new CheckpointRow(1, 0, CheckpointRow.jsonFor(s, before));
        assertEquals("an explicit 没救出 is kept", 0L, explicit.stateFor(before).state.rescued);
        assertEquals(1L, explicit.stateFor(before).state.rescueFailed);

        CheckpointRow legacy = new CheckpointRow(2, 0, Canonical.of(s.toJson()));
        SimState inferred = legacy.stateFor(before).state;
        assertEquals("saved before the app could ask: the failed bit came from a missed deadline", 1L, inferred.rescued);
        assertEquals(0L, inferred.rescueFailed);
    }
}
