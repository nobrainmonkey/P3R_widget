package app.p3r.dayplanner.v3.store;

import static org.junit.Assert.assertEquals;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

/** The rescue rule for typed-in states, and array sizes that follow the pack. */
public class StateShapeTest {
    private final Rules rules = TestRules.create();

    private SimState at(int day, int hierophantRank) {
        SimState s = TestRules.state(rules, TestRules.slot(day, false));
        s.rank[TestRules.HIEROPHANT] = hierophantRank;
        return s;
    }

    @Test
    public void anEndedWindowWithAnEstablishedLinkCountsAsRescued() {
        SimState out = StateShape.inferRescues(at(186, 4), rules);
        assertEquals(1L, out.rescued);
        assertEquals(0L, out.rescueFailed);
    }

    @Test
    public void nothingIsInferredInsideOrBeforeTheWindowOrWithoutTheLink() {
        assertEquals("on the last day of the window", 0L, StateShape.inferRescues(at(185, 4), rules).rescued);
        assertEquals("inside the window", 0L, StateShape.inferRescues(at(170, 4), rules).rescued);
        assertEquals("link never established", 0L, StateShape.inferRescues(at(200, 0), rules).rescued);
    }

    @Test
    public void onlyAnExplicitFailureStopsTheRule() {
        SimState failed = at(200, 4);
        failed.rescueFailed = 1L;
        SimState kept = StateShape.inferRescues(failed, rules);
        assertEquals(0L, kept.rescued);
        assertEquals("the rule never clears an explicit 没救出", 1L, kept.rescueFailed);

        SimState notAsked = StateShape.inferRescues(failed, rules, false);
        assertEquals(1L, notAsked.rescued);
        assertEquals(0L, notAsked.rescueFailed);
        assertEquals("the input is not modified", 1L, failed.rescueFailed);
    }

    @Test
    public void fitSizesEpisodesAndHeldRanks() {
        SimState s = new SimState();
        s.heldRank = new int[3];
        s.episodes = new int[]{2, 1, 1};
        SimState out = StateShape.fit(s, rules);
        assertEquals(rules.episodes.length, out.episodes.length);
        assertEquals(2, out.episodes[0]);
        assertEquals(SimState.LINKS, out.heldRank.length);
    }
}
