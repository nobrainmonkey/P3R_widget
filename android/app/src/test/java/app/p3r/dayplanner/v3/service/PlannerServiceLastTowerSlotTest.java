package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.store.TestRules;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * {@link Engine.Options#lastTowerSlot} (spec 3.1.0 §2, for the 保健室 reminder): the latest
 * Tartarus evening recorded in the current chain, -1 before any; an undone visit no longer counts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceLastTowerSlotTest extends ServiceTestBase {
    private static final int MAY12_EVE = TestRules.slot(TestRules.MAY_12, true);

    @Test
    public void optionsCarryTheLatestRecordedTowerEvening() {
        onboard(MAY12_EVE);
        settle();
        assertEquals(-1, lastOptions().lastTowerSlot);

        assertTrue(tower(MAY12_EVE, 25, true, 14_000).ok);
        settle();
        assertEquals(MAY12_EVE, lastOptions().lastTowerSlot);

        int day = TestRules.MAY_12 + 1;
        int nextEve = TestRules.slot(day, true);
        assertTrue(record(TestRules.slot(day, false), "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        PlannerApi.Receipt second = tower(nextEve, 26, false, 2_000);
        assertTrue(second.errorZh, second.ok);
        settle();
        assertEquals(nextEve, lastOptions().lastTowerSlot);

        PlannerApi.Receipt undone = service.voidEvent(id(), rev(), second.eventSeq);
        assertTrue(undone.errorZh, undone.ok);
        settle();
        assertEquals("the voided visit is skipped", MAY12_EVE, lastOptions().lastTowerSlot);
    }

    private Engine.Options lastOptions() {
        return planner.options.get(planner.options.size() - 1);
    }
}
