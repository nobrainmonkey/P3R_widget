package app.p3r.dayplanner.v3.service;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.TestRules;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlannerServiceStoryDisplayTest extends ServiceTestBase {
    @Test
    public void acknowledgingStoryDaysPersistsWithoutChangingActualProgressOrReplanning() {
        int day = TestRules.FULL_MOON_DAY;
        int actionSlot = Rules.slot(day, false);
        for (int d = day + 1; d <= day + 2; d++) {
            rules.days[d].dayLock = "school_trip";
            rules.days[d].eveLock = "school_trip";
            rules.days[d].noteZh = "修学旅行，不能自由行动";
        }
        rules.lockReasonsZh.put("school_trip", "修学旅行");
        onboard(actionSlot);
        PlannerApi.Receipt record = record(actionSlot, "meet:emperor", Outcome.Code.AS_PREDICTED);
        assertTrue(record.errorZh, record.ok);
        settle();
        PlannerApi.Snapshot before = service.snapshot();
        assertEquals("canonical next-actionable cursor is unchanged", Rules.slot(day + 3, false), before.observed.slot);
        assertEquals(actionSlot + 1, before.storyDisplay.cursorSlot);
        String source = before.storyDisplay.key;
        int calls = planner.calls.get();
        int history = service.history(0).size();
        assertFalse(service.acknowledgeStory("stale", actionSlot + 1));
        assertTrue(service.acknowledgeStory(source, actionSlot + 1));
        assertFalse("a delayed double tap cannot skip the next day", service.acknowledgeStory(source, actionSlot + 1));
        PlannerApi.Snapshot after = service.snapshot();
        assertEquals(before.stateRevision, after.stateRevision);
        assertEquals(before.profileRevision, after.profileRevision);
        assertEquals(before.observed.slot, after.observed.slot);
        assertEquals(before.observed.cash, after.observed.cash);
        assertArrayEquals(before.observed.statPts, after.observed.statPts);
        assertArrayEquals(before.observed.rank, after.observed.rank);
        assertEquals(history, service.history(0).size());
        assertEquals(calls, planner.calls.get());
        assertEquals(Rules.slot(day + 1, false), after.storyDisplay.cursorSlot);

        restart();
        assertEquals("acknowledgement survives process restart", Rules.slot(day + 1, false), service.snapshot().storyDisplay.cursorSlot);
        service.requestFullReplan();
        settle();
        assertEquals("a fresh forecast cannot replay the acknowledged evening", Rules.slot(day + 1, false), service.snapshot().storyDisplay.cursorSlot);
        Profile changed = service.snapshot().profile.copy();
        changed.cashBuffer++;
        assertTrue(service.saveProfile(id(), service.snapshot().profileRevision, changed).ok);
        assertEquals(Rules.slot(day + 1, false), service.snapshot().storyDisplay.cursorSlot);
        assertTrue(service.acknowledgeStory(source, Rules.slot(day + 1, false)));
        assertTrue(service.acknowledgeStory(source, Rules.slot(day + 2, false)));
        assertNull(service.snapshot().storyDisplay);
        restart();
        assertNull("finished story intervals stay acknowledged", service.snapshot().storyDisplay);
    }

    @Test
    public void undoAndCheckpointsCannotReuseAnOldStoryAcknowledgement() {
        int action = Rules.slot(TestRules.FULL_MOON_DAY, false);
        onboard(action);
        PlannerApi.Receipt first = record(action, "meet:emperor", Outcome.Code.AS_PREDICTED);
        StoryDisplay old = service.snapshot().storyDisplay;
        assertNotNull(old);
        assertTrue(service.acknowledgeStory(old.key, old.cursorSlot));
        assertTrue(service.voidEvent(id(), rev(), first.eventSeq).ok);
        assertNull(service.snapshot().storyDisplay);
        assertTrue(record(action, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        StoryDisplay current = service.snapshot().storyDisplay;
        assertNotNull(current);
        assertNotEquals(old.key, current.key);
        assertFalse(service.acknowledgeStory(old.key, current.cursorSlot));
        onboard(TestRules.state(rules, current.toSlot + 1));
        assertNull(service.snapshot().storyDisplay);
        assertFalse(service.acknowledgeStory(current.key, current.cursorSlot));
    }

    @Test
    public void correctingAndUndoingATowerCorrectionCannotReplayAcknowledgedStoryDays() {
        int day = TestRules.FULL_MOON_DAY;
        for (int d = day; d <= day + 2; d++) {
            rules.days[d].dayLock = "school_trip";
            rules.days[d].eveLock = "school_trip";
        }
        rules.lockReasonsZh.put("school_trip", "修学旅行");
        int visitSlot = Rules.slot(day - 1, true);
        onboard(visitSlot);
        PlannerApi.Receipt visit = tower(visitSlot, 20, false, 3_000);
        assertTrue(visit.errorZh, visit.ok);
        StoryDisplay initial = service.snapshot().storyDisplay;
        assertNotNull(initial);
        assertTrue(service.acknowledgeStory(initial.key, initial.cursorSlot));
        PlannerApi.Snapshot beforeCorrection = service.snapshot();
        int nextDay = Rules.slot(day + 1, false);
        assertEquals(nextDay, beforeCorrection.storyDisplay.cursorSlot);

        PlannerApi.Receipt correction = service.correctTower(id(), rev(), visit.eventSeq,
                towerReturn(visitSlot, 20, false, 3_500));
        assertTrue(correction.errorZh, correction.ok);
        PlannerApi.Snapshot corrected = service.snapshot();
        assertTrue("the correction still updates the gameplay revision", corrected.stateRevision > beforeCorrection.stateRevision);
        assertEquals(beforeCorrection.observed.slot, corrected.observed.slot);
        assertEquals(beforeCorrection.observed.cash + 500, corrected.observed.cash);
        assertEquals("a cash correction does not create a new story interval", initial.key, corrected.storyDisplay.key);
        assertEquals("already viewed days stay acknowledged", nextDay, corrected.storyDisplay.cursorSlot);
        assertFalse("an old tap cannot acknowledge another day", service.acknowledgeStory(initial.key, initial.cursorSlot));

        assertTrue(service.acknowledgeStory(initial.key, nextDay));
        assertTrue(service.acknowledgeStory(initial.key, Rules.slot(day + 2, false)));
        assertNull(service.snapshot().storyDisplay);
        assertTrue(service.voidEvent(id(), rev(), correction.eventSeq).ok);
        assertEquals(beforeCorrection.observed.cash, observed().cash);
        assertNull("undoing only the cash correction cannot replay the finished interval", service.snapshot().storyDisplay);
        restart();
        assertNull("the acknowledgement also survives a restart after the correction was undone", service.snapshot().storyDisplay);
    }
}
