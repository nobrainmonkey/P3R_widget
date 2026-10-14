package app.p3r.dayplanner.v3.widget;

import org.junit.Test;

import app.p3r.dayplanner.v3.service.PlannerApi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 从头重新安排 runs while the installed plan stays current (spec §1.1): the widget keeps showing
 * that plan and recording with one tap, with no 正在生成 card and no "replanning" cue. Plain JVM.
 */
public class PlanViewFullReplanTest {
    private static final long NOW = 1_000_000L;

    /** The snapshot the service gives while a full replan of a current plan runs. */
    private static PlannerApi.Snapshot fullReplanning(FakePlannerApi fake) {
        PlannerApi.Snapshot snap = fake.snapshot();
        snap.fullReplanning = true;
        snap.fullReplanTicket = 1;
        return snap;
    }

    @Test
    public void aDirectStepStaysOneTapWhileTheFullReplanRuns() {
        FakePlannerApi fake = new FakePlannerApi();
        fake.observeAt("act:study_room");
        PlannerApi.Snapshot snap = fullReplanning(fake);
        assertTrue(snap.planCurrent);
        assertFalse(snap.planning);

        PlanView view = PlanView.resolve(snap, -1, null, NOW);
        assertEquals(PlanView.Mode.ITEM, view.mode);
        assertEquals("widget 完成 records at once", PlanView.Done.COMMIT, view.done);
        CardModel card = view.toCard(null);
        assertNotEquals("正在生成安排…", card.title);
        assertFalse("no replanning cue: " + card.cue, card.cue != null && card.cue.contains("重新安排"));

        CardModel usual = PlanView.resolve(fake.snapshot(), -1, null, NOW).toCard(null);
        assertEquals("the same card as without the request", usual.title, card.title);
        assertEquals(usual.cue, card.cue);
        assertEquals(usual.doneLabel, card.doneLabel);
        assertEquals(usual.taskDescription, card.taskDescription);
    }

    @Test
    public void otherStepsRouteAsUsualToo() {
        FakePlannerApi fake = new FakePlannerApi();
        PlanView social = PlanView.resolve(fullReplanning(fake), -1, null, NOW);
        assertEquals(PlanView.Mode.ITEM, social.mode);
        assertEquals(PlanView.Done.RESULT, social.done);
        assertEquals(PlanView.Done.CATCHUP, PlanView.resolve(fullReplanning(fake), FakePlannerApi.FIRST_SLOT + 1, null, NOW).done);
        assertEquals(PlanView.resolve(fake.snapshot(), -1, null, NOW).toCard(null).cue, social.toCard(null).cue);
    }
}
