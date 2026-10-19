package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi.FullReplanResult;

/** The one line that says how a 从头重新安排 ended (message bar and 今天 status line), spec §1.1. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlanDiffFullSummaryTest {
    private final Rules rules = Fixtures.rules();
    private final SimState obs = Fixtures.observedMid(rules);
    private final Plan plan = Fixtures.planMid(rules, obs);

    private String summary(Plan before, Plan after, FullReplanResult result) {
        return PlanDiff.fullSummary(rules, before, after, obs.slot, result);
    }

    private Plan copy(String id) {
        return Fixtures.planMid(rules, obs, obs.slot, id);
    }

    /** First slot after the week the summary counts separately. */
    private int laterSlot() {
        return Rules.slot(Rules.dayOf(obs.slot) + PlanDiff.NEAR_DAYS, true) + 1;
    }

    @Test
    public void resultsWithoutANewPlanHaveFixedWords() {
        assertEquals("重新安排好了，和现在的一样", summary(plan, plan, FullReplanResult.UNCHANGED));
        assertEquals("nothing better is the same thing to a player", "重新安排好了，和现在的一样",
            summary(plan, plan, FullReplanResult.KEPT));
        assertEquals("这次没排好，安排先不变", summary(plan, plan, FullReplanResult.FAILED));
        assertEquals("已停下，安排没变", summary(plan, plan, FullReplanResult.CANCELLED));
        assertNull("nothing to tell (an import, or an ordinary replan took over)", summary(plan, plan, null));
    }

    @Test
    public void aChangedPlanCountsTheChangesOfThisWeekAndSaysWhetherLaterOnesFollowed() {
        Plan near = copy("plan-near");
        near.steps.set(1, Fixtures.social(rules, obs.slot + 1, "hermit", 3, 4, true, false));
        assertEquals("重新安排好了：这周 1 处有变", summary(plan, near, FullReplanResult.CHANGED));
        assertEquals("the long search reads the same: the player asked for one thing", "重新安排好了：这周 1 处有变",
            PlanDiff.fullSummary(rules, plan, near, obs.slot, FullReplanResult.CHANGED, true));
        assertEquals("这次没排好，安排先不变",
            PlanDiff.fullSummary(rules, plan, plan, obs.slot, FullReplanResult.FAILED, true));

        Plan both = copy("plan-both");
        both.steps.set(1, Fixtures.social(rules, obs.slot + 1, "hermit", 3, 4, true, false));
        both.steps.set(3, Fixtures.social(rules, obs.slot + 3, "moon", 3, 4, true, false));
        Plan.Step far = both.at(laterSlot() + 1);
        far.action = "free".equals(far.action) ? "act:study_room" : "free";
        assertEquals("重新安排好了：这周 2 处有变，之后也有调整", summary(plan, both, FullReplanResult.CHANGED));

        Plan later = copy("plan-later");
        Plan.Step step = later.at(laterSlot() + 1);
        step.action = "free".equals(step.action) ? "act:study_room" : "free";
        assertEquals("重新安排好了：这周不变，之后有调整", summary(plan, later, FullReplanResult.CHANGED));

        Plan side = copy("plan-side");
        side.steps.get(1).side.add("unlock:devil:0");
        assertEquals("the same things to do, other 顺便 lines",
            "重新安排好了：顺便的事有调整", summary(plan, side, FullReplanResult.CHANGED));

        assertEquals("nothing visible changed: said as unchanged",
            "重新安排好了，和现在的一样", summary(plan, copy("plan-same"), FullReplanResult.CHANGED));
    }

    @Test
    public void withoutThePlanBeforeOnlyTheEndIsSaid() {
        assertEquals("重新安排好了，一直排到1月31日", summary(null, plan, FullReplanResult.CHANGED));
        assertEquals(PlanDiff.FULL_CHANGED_UNCOUNTED + "1月31日", summary(null, plan, FullReplanResult.CHANGED));
        Plan done = copy("plan-done");
        done.steps.clear();
        assertNull("the calendar is over", summary(plan, done, FullReplanResult.CHANGED));
    }

    @Test
    public void onlyFailedAndStoppedAreSaidWithoutACheckMark() {
        assertTrue(PlanDiff.neutral(PlanDiff.FULL_FAILED));
        assertTrue(PlanDiff.neutral(PlanDiff.FULL_CANCELLED));
        assertFalse(PlanDiff.neutral(PlanDiff.FULL_KEPT));
        assertFalse(PlanDiff.neutral(PlanDiff.FULL_UNCHANGED));
        assertFalse(PlanDiff.neutral("已重新安排：这周 1 处有变"));
        assertFalse(PlanDiff.neutral(null));
    }
}
