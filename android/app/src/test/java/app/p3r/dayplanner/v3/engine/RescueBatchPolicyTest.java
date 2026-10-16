package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

/** Small calendar examples of batching arrivals without spending another evening in Tartarus. */
public class RescueBatchPolicyTest {
    private static Profile profile(int cap) {
        Profile p = RulesFixtures.others();
        p.otherLinks = false;
        p.towerCapPerBlock = cap;
        p.towerVisitsPerBlock = 1;
        return p;
    }

    private static SimState start(Rules rules, int day) {
        return RulesFixtures.newGame(rules, Rules.slot(day, true));
    }

    private static RescueBatches batches(Rules rules, Profile profile, SimState start, int lastTowerSlot) {
        RescueBatches b = new RescueBatches(new Model(rules, profile, new Engine.Options()), start, lastTowerSlot);
        start.plannedRescueGroups = b.initialMask();
        return b;
    }

    @Test
    public void sameDeadlineArrivalsShareOneTripEvenWhenNoSocialLinksAreWanted() {
        Rules rules = ReminderFixtures.rules();
        SimState early = start(rules, 40);
        RescueBatches b = batches(rules, profile(2), early, -1);
        assertEquals(1, b.pendingTrips(early, 1));
        assertTrue(b.waitForLaterArrivals(early, 1));
        assertEquals(0, b.urgency(early, 1), 0);
        assertEquals(1, b.pendingTripsAfterVisit(early, 1));

        SimState together = early.copy();
        together.slot = Rules.slot(42, true);
        assertFalse(b.waitForLaterArrivals(together, 1));
        assertTrue(b.urgency(together, 1) > 0);
        assertEquals(0, b.pendingTripsAfterVisit(together, 1));
        b.recordVisit(together, together.slot);
        assertEquals(0, b.missed(together));
        assertEquals(0, b.late(together));
    }

    @Test
    public void theOnlyAllowedTripIsReservedForTheLaterArrival() {
        Rules rules = ReminderFixtures.rules();
        SimState s = start(rules, 39);
        RescueBatches b = batches(rules, profile(1), s, -1);
        assertTrue(b.waitForLaterArrivals(s, 1));
        assertEquals("one future shared rescue, not one allowance per group", 1, b.pendingTripsAfterVisit(s, 1));
        s.slot = Rules.slot(40, true);
        assertTrue("rescuing only the first group would strand the later arrivals", b.waitForLaterArrivals(s, 1));
    }

    @Test
    public void locksThatRemoveSharedSafeEveningsSplitTheTrips() {
        Rules rules = ReminderFixtures.rules();
        for (int day = 42; day <= 44; day++) rules.days[day].eveLock = "story";
        SimState s = start(rules, 40);
        RescueBatches b = batches(rules, profile(2), s, -1);
        assertFalse("waiting for the last group would leave the first group to the limit evening",
                b.waitForLaterArrivals(s, 1));
        assertEquals(Heuristics.MANDATORY, b.urgency(s, 1), 0);
        assertEquals(2, b.pendingTrips(start(rules, 39), 1));
        b.recordVisit(s, s.slot);
        s.towerVisits[1]++;
        s.slot = Rules.slot(45, true);
        assertEquals(Heuristics.MANDATORY, b.urgency(s, 1), 0);
        b.recordVisit(s, s.slot);
        assertEquals(0, b.missed(s));
        assertEquals("only the later group has no safe evening at all", 0, b.late(s));
    }

    @Test
    public void anEarlierDeadlineCannotBeDelayedForAnIncompatibleLaterArrival() {
        Rules rules = ReminderFixtures.rules();
        rules.reminders.rescueGroups.get(0).toDay = 42;
        rules.reminders.rescueGroups.get(1).fromDay = 43;
        SimState s = start(rules, 40);
        RescueBatches b = batches(rules, profile(2), s, -1);
        assertFalse(b.waitForLaterArrivals(s, 1));
        assertEquals("day 41 is locked, making day 40 the last safe evening", Heuristics.MANDATORY, b.urgency(s, 1), 0);
        assertEquals(2, b.pendingTrips(start(rules, 39), 1));
    }

    @Test
    public void aRecordedVisitSettlesOnlyGroupsAlreadyPresentThatNight() {
        Rules rules = ReminderFixtures.rules();
        SimState s = start(rules, 42);
        RescueBatches b = batches(rules, profile(1), s, Rules.slot(40, true));
        assertEquals(1L, b.initialMask());
        assertEquals(1, b.missed(s));
        assertEquals(2L, b.coveredOn(s.slot) & ~s.plannedRescueGroups);
        b.recordVisit(s, s.slot);
        assertEquals(0, b.missed(s));
    }

    @Test
    public void aLinkedRescueDoesNotImplyItsUnmodelledCompanionWasRescued() {
        Rules rules = ReminderFixtures.rules();
        rules.reminders.rescueGroups.get(0).count = 2;
        SimState s = start(rules, 42);
        s.rescued = 1;
        RescueBatches b = batches(rules, profile(1), s, -1);
        assertEquals(0, b.initialMask());
        assertEquals(2, b.missed(s));
    }

    @Test
    public void aFullyConfirmedOnePersonGroupDoesNotNeedAnotherTrip() {
        Rules rules = ReminderFixtures.rules();
        SimState s = start(rules, 42);
        s.rescued = 1;
        RescueBatches b = batches(rules, profile(1), s, -1);
        assertEquals(1L, b.initialMask());
        assertEquals(1, b.missed(s));
        assertEquals(1, b.pendingTrips(s, 1));
    }

    @Test
    public void anExhaustedCapNeverSuggestsAnImpossibleTripOrWaitingForOne() {
        Rules rules = ReminderFixtures.rules();
        SimState s = start(rules, 40);
        s.towerVisits[1] = 1;
        RescueBatches b = batches(rules, profile(1), s, -1);
        assertEquals(0, b.urgency(s, 1), 0);
        assertFalse(b.waitForLaterArrivals(s, 1));
        assertEquals(0, b.pendingTrips(s, 1));
        assertEquals(2, b.missed(s));
    }

    @Test
    public void leavingAnAvailableSafeEveningForTheLimitIsTrackedAsLate() {
        Rules rules = ReminderFixtures.rules();
        SimState s = start(rules, 42);
        RescueBatches b = batches(rules, profile(1), s, -1);
        s.slot = Rules.slot(44, true);
        assertEquals(Heuristics.MANDATORY, b.urgency(s, 1), 0);
        s.slot = Rules.slot(45, true);
        b.recordVisit(s, s.slot);
        assertEquals(0, b.missed(s));
        assertEquals(2, b.late(s));
        b.recordVisit(s, s.slot);
        assertEquals("repeated forecast recording does not accumulate penalties", 2, b.late(s));
    }

    @Test
    public void aPlanStartingOnTheLimitUsesTheRemainingEveningWithoutALatePenalty() {
        Rules rules = ReminderFixtures.rules();
        SimState s = start(rules, 45);
        RescueBatches b = batches(rules, profile(1), s, -1);
        assertEquals(Heuristics.MANDATORY, b.urgency(s, 1), 0);
        b.recordVisit(s, s.slot);
        assertEquals(0, b.missed(s));
        assertEquals(0, b.late(s));
    }
}
