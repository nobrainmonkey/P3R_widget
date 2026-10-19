package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.store.FakeApplier;
import app.p3r.dayplanner.v3.store.TestRules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * What a record did is stored with it when it is written (F1/F2): the side steps done (from the
 * sheet, else the installed plan's 顺便 lines, else the engine's pick) and the RANK a meeting
 * recorded as predicted led to. Retrying the same command afterwards is still a duplicate.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceRecordTest extends ServiceTestBase {
    private static final int MAY12_DAY = TestRules.slot(TestRules.MAY_12, false);
    private static final int MAY12_EVE = TestRules.slot(TestRules.MAY_12, true);
    private static final int MAY13_DAY = TestRules.slot(TestRules.MAY_12 + 1, false);
    private static final int MAY13_EVE = TestRules.slot(TestRules.MAY_12 + 1, true);
    private static final String SIDE = "unlock:magician:0";

    @Test
    public void theInstalledPlansSideStepsAreStoredWhenTheRecordFollowsIt() throws Exception {
        applier.sideFee = 1_000;
        planner.eveningSide = List.of(SIDE);
        onboard(MAY12_EVE);
        settle();
        assertTrue(service.snapshot().planCurrent);

        // As planned: the plan's 顺便 line is stored, with its step id, and paid.
        assertTrue(record(MAY12_EVE, "act:study_room", Outcome.Code.AS_PREDICTED).ok);
        JSONObject followed = lastPayload();
        assertEquals(List.of(SIDE), sideOf(followed));
        assertEquals("magician.intro", followed.getJSONObject("sideStepIds").getString(SIDE));
        assertEquals(9_000, observed().cash);

        // 改做别的 (another action than the plan's): the engine's pick for it (this fake picks none).
        settle();
        assertTrue(record(MAY13_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertEquals("the plan's daytime step has no 顺便 line", List.of(), sideOf(lastPayload()));
        settle();
        assertTrue(record(MAY13_EVE, "act:job_chagall", Outcome.Code.AS_PREDICTED).ok);
        assertEquals(List.of(), sideOf(lastPayload()));
        assertEquals("no side fee beside the job", 9_000 + 2_500, observed().cash);

        // Side steps said not done: nothing stored, nothing paid.
        settle();
        Outcome notDone = Outcome.of(Outcome.Code.AS_PREDICTED);
        notDone.sideDone = false;
        int next = observed().slot;
        assertTrue(service.recordOutcome(id(), rev(), next, "meet:emperor", notDone).ok);
        assertFalse(lastPayload().has("side"));
    }

    @Test
    public void sideKeysFromTheSheetAreCheckedAndStored() throws Exception {
        applier.sideFee = 1_000;
        onboard(MAY12_EVE);
        Outcome bad = Outcome.of(Outcome.Code.AS_PREDICTED);
        bad.sideKeys = List.of("meet:emperor");
        PlannerApi.Receipt rejected = service.recordOutcome(id(), rev(), MAY12_EVE, "act:study_room", bad);
        assertFalse(rejected.ok);
        assertEquals(CommandCheck.ZH_SIDE_UNKNOWN, rejected.errorZh);

        Outcome given = Outcome.of(Outcome.Code.AS_PREDICTED);
        given.sideKeys = List.of(SIDE);
        assertTrue(service.recordOutcome(id(), rev(), MAY12_EVE, "act:study_room", given).ok);
        JSONObject payload = lastPayload();
        assertEquals(List.of(SIDE), sideOf(payload));
        assertEquals(List.of(SIDE), keys(payload.getJSONObject("outcome").getJSONArray("sideKeys")));
        assertEquals(9_000, observed().cash);

        // A tower visit with the sheet's list; the history item carries what was stored.
        PlannerApi.TowerReturn visit = towerReturn(MAY13_EVE, 20, false, 3_000);
        visit.sideKeys = List.of(SIDE);
        assertTrue(record(MAY13_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(service.recordTower(id(), rev(), visit).ok);
        assertEquals(List.of(SIDE), sideOf(lastPayload()));
        assertEquals("the side fee, then the visit", 9_000 - 1_000 + 3_000, observed().cash);
        assertEquals(List.of(SIDE), service.history(1).get(0).tower.sideKeys);
    }

    @Test
    public void retryingASavedRecordIsADuplicateEvenThoughWhatItResolvedWouldDifferNow() throws Exception {
        applier.sideFee = 1_000;
        onboard(MAY12_DAY);
        String meet = id();
        long rev = rev();
        // The widget's shape: as predicted, no rank. The RANK it led to is stored with it.
        Outcome widget = Outcome.of(Outcome.Code.AS_PREDICTED);
        PlannerApi.Receipt first = service.recordOutcome(meet, rev, MAY12_DAY, "meet:emperor", widget);
        assertTrue(first.errorZh, first.ok);
        assertEquals(7, lastPayload().getInt("rankAfter"));
        settle();

        PlannerApi.Receipt again = service.recordOutcome(meet, rev, MAY12_DAY, "meet:emperor", widget);
        assertTrue(again.errorZh, again.ok);
        assertTrue(again.duplicate);
        assertEquals(first.eventSeq, again.eventSeq);
        PlannerApi.Receipt other = service.recordOutcome(meet, rev, MAY12_DAY, "meet:emperor",
                Outcome.of(Outcome.Code.NO_RANK_UP));
        assertEquals(PlannerApi.Receipt.CODE_CONFLICT, other.errorCode);

        // A tower visit: the side step this fake picks beside a visit is stored.
        String tower = id();
        long towerRev = rev();
        PlannerApi.TowerReturn visit = towerReturn(MAY12_EVE, 20, false, 3_000);
        PlannerApi.Receipt saved = service.recordTower(tower, towerRev, visit);
        assertTrue(saved.errorZh, saved.ok);
        assertEquals(List.of(FakeApplier.PICKED_SIDE), sideOf(lastPayload()));
        settle();
        PlannerApi.Receipt dup = service.recordTower(tower, towerRev, visit);
        assertTrue(dup.duplicate);
        assertEquals(saved.eventSeq, dup.eventSeq);
        assertEquals(12_000, observed().cash);
    }

    @Test
    public void storedSideStepsThatNoLongerFitAreCountedInTheNotice() {
        onboard(MAY12_EVE);
        Outcome given = Outcome.of(Outcome.Code.AS_PREDICTED);
        given.sideKeys = List.of(SIDE);
        assertTrue(service.recordOutcome(id(), rev(), MAY12_EVE, "act:study_room", given).ok);
        assertEquals(null, service.snapshot().noticeZh);

        // E.g. after an app update the step cannot be applied as recorded any more.
        applier.refuse.add(SIDE);
        assertTrue(service.saveProfile(id(), service.snapshot().profileRevision, service.snapshot().profile).ok);
        assertEquals(PlannerService.mismatchNotice(1), service.snapshot().noticeZh);
    }

    @Test
    public void theStoryHoldAnswerReadsTheSameInHistory() {
        onboard(MAY12_DAY);
        Outcome hold = Outcome.of(Outcome.Code.NOT_DONE);
        hold.reason = "hold";
        assertTrue(service.recordOutcome(id(), rev(), MAY12_DAY, "meet:emperor", hold).ok);
        assertEquals("5月12日 放学后 · 皇帝 · 小田桐秀利 · 剧情原因暂时升不了", service.history(1).get(0).summaryZh);
    }

    // ------------------------------------------------------------------ helpers

    /** Payload of the newest observed row, read back through a backup. */
    private JSONObject lastPayload() throws JSONException {
        JSONArray events = new JSONObject(service.exportBackup()).getJSONArray("events");
        for (int i = events.length() - 1; i >= 0; i--) {
            JSONObject e = events.getJSONObject(i);
            String kind = e.getString("kind");
            if ("outcome".equals(kind) || "skip".equals(kind) || "tower".equals(kind)) {
                return e.getJSONObject("payload");
            }
        }
        throw new AssertionError("no observed row");
    }

    private static List<String> sideOf(JSONObject payload) throws JSONException {
        JSONArray side = payload.optJSONArray("side");
        return side == null ? null : keys(side);
    }

    private static List<String> keys(JSONArray array) throws JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            out.add(array.getString(i));
        }
        return out;
    }
}
