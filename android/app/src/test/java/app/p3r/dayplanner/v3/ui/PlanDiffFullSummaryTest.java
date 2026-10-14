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
        assertEquals("重新算过了，安排没有变化", summary(plan, plan, FullReplanResult.UNCHANGED));
        assertEquals("重新算过了，没找到更好的，现在的安排不变", summary(plan, plan, FullReplanResult.KEPT));
        assertEquals("这次没能从头重新安排，现在的安排不变", summary(plan, plan, FullReplanResult.FAILED));
        assertEquals("已停下，现在的安排不变", summary(plan, plan, FullReplanResult.CANCELLED));
        assertNull("nothing to tell (an import, or an ordinary replan took over)", summary(plan, plan, null));
    }

    @Test
    public void aChangedPlanCountsTheChangesOfThisWeekAndSaysWhetherLaterOnesFollowed() {
        Plan near = copy("plan-near");
        near.steps.set(1, Fixtures.social(rules, obs.slot + 1, "hermit", 3, 4, true, false));
        assertEquals("从头重新安排好了：这一周有 1 处变动", summary(plan, near, FullReplanResult.CHANGED));

        Plan both = copy("plan-both");
        both.steps.set(1, Fixtures.social(rules, obs.slot + 1, "hermit", 3, 4, true, false));
        both.steps.set(3, Fixtures.social(rules, obs.slot + 3, "moon", 3, 4, true, false));
        Plan.Step far = both.at(laterSlot() + 1);
        far.action = "free".equals(far.action) ? "act:study_room" : "free";
        assertEquals("从头重新安排好了：这一周有 2 处变动，之后的也顺着调整了", summary(plan, both, FullReplanResult.CHANGED));

        Plan later = copy("plan-later");
        Plan.Step step = later.at(laterSlot() + 1);
        step.action = "free".equals(step.action) ? "act:study_room" : "free";
        assertEquals("从头重新安排好了：这一周不变，之后的安排顺着调整了", summary(plan, later, FullReplanResult.CHANGED));

        Plan side = copy("plan-side");
        side.steps.get(1).side.add("unlock:devil:0");
        assertEquals("the same things to do, other 顺便 lines",
            "从头重新安排好了：要做的事没变，顺便做的事有调整", summary(plan, side, FullReplanResult.CHANGED));

        assertEquals("nothing visible changed: said as unchanged",
            "重新算过了，安排没有变化", summary(plan, copy("plan-same"), FullReplanResult.CHANGED));
    }

    @Test
    public void withoutThePlanBeforeOnlyTheEndIsSaid() {
        assertEquals("从头重新安排好了，一直排到1月31日", summary(null, plan, FullReplanResult.CHANGED));
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
        assertFalse(PlanDiff.neutral("已重新安排：这一周有 1 处变动"));
        assertFalse(PlanDiff.neutral(null));
    }
}
