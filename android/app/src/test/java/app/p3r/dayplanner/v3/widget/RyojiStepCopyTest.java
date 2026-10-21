package app.p3r.dayplanner.v3.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

/** Missing cached step text must still show the real scene number and meeting location. */
public class RyojiStepCopyTest {
    @Test public void fallbackNumbersIncludeTheEarlierPlannedMeeting() throws Exception {
        Rules rules = RulesLoader.parse(new String(Files.readAllBytes(
            Paths.get("src/main/assets/p3r/p3r_rules.json")), StandardCharsets.UTF_8));
        Rules.Episode ryoji = null;
        for (Rules.Episode track : rules.episodes) if ("ryoji".equals(track.id)) ryoji = track;
        if (ryoji == null) throw new AssertionError("Ryoji track missing");
        SimState observed = new SimState(rules.blocks.length, rules.activities.length);
        observed.episodes = new int[rules.episodes.length];
        Plan.Step first = new Plan.Step();
        first.action = "episode:ryoji";
        first.kind = "episode";
        first.slot = Rules.slot(ryoji.firstDay(1), false);
        Plan.Step second = new Plan.Step();
        second.action = first.action;
        second.kind = "episode";
        second.slot = Rules.slot(ryoji.firstDay(2), false);
        observed.slot = first.slot;
        Plan plan = new Plan();
        plan.fromSlot = first.slot;
        plan.steps = Arrays.asList(first, second);
        assertEquals("羁绊剧情 · 望月绫时（第 2 话）", StepCopy.of(first, rules, observed, plan).title);
        StepCopy last = StepCopy.of(second, rules, observed, plan);
        assertEquals("羁绊剧情 · 望月绫时（第 4 话）", last.title);
        assertEquals(ryoji.locationOf(2), last.location);
    }
}
