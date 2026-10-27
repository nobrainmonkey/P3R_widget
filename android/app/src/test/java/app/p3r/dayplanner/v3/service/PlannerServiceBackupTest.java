package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.compendium.CompendiumPrefs;
import app.p3r.dayplanner.v3.compendium.Compendium;
import app.p3r.dayplanner.v3.compendium.FusionBasket;
import app.p3r.dayplanner.v3.compendium.FusionCalculator;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.store.Canonical;
import app.p3r.dayplanner.v3.store.TestRules;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 备份与恢复: export → import round trip, atomic replacement, idempotent import. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlannerServiceBackupTest extends ServiceTestBase {
    private static final int MAY12_DAY = TestRules.slot(TestRules.MAY_12, false);
    private static final int MAY12_EVE = TestRules.slot(TestRules.MAY_12, true);
    private static final int MAY13_DAY = TestRules.slot(TestRules.MAY_12 + 1, false);
    private static final int MAY13_EVE = TestRules.slot(TestRules.MAY_12 + 1, true);

    @Test
    public void nestedFusionBasketAndRegistrationsRoundTripAndCraftingKeepsMaterialsRegistered() throws Exception {
        onboard(MAY12_DAY);
        Compendium data;
        try (java.io.InputStream input = context.getAssets().open(Compendium.ASSET_NAME)) {
            data = Compendium.load(input);
        }
        FusionCalculator calculator = new FusionCalculator(data, Set.of());
        String orpheus = data.personaByKey("Orpheus").id;
        String power = data.personaByKey("Power").id;
        String naga = data.personaByKey("Naga").id;
        String fortuna = data.personaByKey("Fortuna").id;
        FusionCalculator.Recipe targetRecipe = calculator.fuse(orpheus, power);
        FusionCalculator.Recipe powerRecipe = calculator.fuse(naga, fortuna);
        assertEquals(power, powerRecipe.result.id);
        FusionBasket basket = new FusionBasket();
        assertTrue(basket.addGoal(targetRecipe));
        assertTrue(basket.chooseRecipe(powerRecipe));
        CompendiumPrefs prefs = new CompendiumPrefs(context);
        prefs.setBasket(basket);
        prefs.setOwned(orpheus, true);
        prefs.setOwned(fortuna, true);
        prefs.setFavorite(power, true);
        prefs.setOwnedDlc(Set.of("p4g"));
        assertEquals(Set.of(orpheus, fortuna), prefs.ownedPersonas());
        assertEquals(Canonical.of(basket.toJson()), Canonical.of(prefs.basket().toJson()));

        String exported = service.exportBackup();
        JSONObject choices = new JSONObject(exported).getJSONObject("compendium");
        PlannerService restored = open("fusion-basket-restored.db");
        assertTrue(restored.importBackup(id(), exported).ok);
        assertEquals(Canonical.of(choices), Canonical.of(
            new JSONObject(restored.exportBackup()).getJSONObject("compendium")));

        prefs.setOwned(naga, true);
        prefs.recordFusion(powerRecipe);
        assertEquals(Set.of(orpheus, power, naga, fortuna), prefs.ownedPersonas());
        assertEquals(Set.of(power), prefs.favorites());
        assertEquals(Set.of("p4g"), prefs.ownedDlc());
        assertEquals(Canonical.of(basket.toJson()), Canonical.of(prefs.basket().toJson()));
    }

    @Test
    public void compendiumChoicesSurviveBackupWithoutChangingTheSchedule() throws Exception {
        onboard(MAY12_DAY);
        settle();
        long stateRevision = rev();
        long profileRevision = service.snapshot().profileRevision;
        CompendiumPrefs prefs = new CompendiumPrefs(context);
        prefs.setFavorite("persona_118", true);
        prefs.setOwnedDlc(Set.of("p5r_1", "p4g"));
        assertEquals(Set.of("persona_118"), new CompendiumPrefs(context).favorites());
        assertEquals(stateRevision, rev());
        assertEquals(profileRevision, service.snapshot().profileRevision);

        String backup = service.exportBackup();
        JSONObject choices = new JSONObject(backup).getJSONObject("compendium");
        service = open("compendium-restored.db");
        assertTrue(service.importBackup(id(), backup).ok);
        assertEquals(Canonical.of(choices), Canonical.of(
            new JSONObject(service.exportBackup()).getJSONObject("compendium")));
    }

    @Test
    public void invalidCompendiumChoicesRejectTheWholeImport() throws Exception {
        onboard(MAY12_DAY);
        CompendiumPrefs prefs = new CompendiumPrefs(context);
        prefs.setFavorite("persona_118", true);
        String before = stripTime(service.exportBackup());
        JSONObject damaged = new JSONObject(service.exportBackup());
        damaged.getJSONObject("compendium").put("favorites", new org.json.JSONArray().put(42));
        assertFalse(service.importBackup(id(), damaged.toString()).ok);
        assertEquals(before, stripTime(service.exportBackup()));
        assertEquals(Set.of("persona_118"), prefs.favorites());
    }

    @Test
    public void oldBackupsKeepCurrentCompendiumChoicesAndImportRetriesKeepNewFavorites() throws Exception {
        onboard(MAY12_DAY);
        CompendiumPrefs prefs = new CompendiumPrefs(context);
        prefs.setFavorite("persona_118", true);
        JSONObject old = new JSONObject(service.exportBackup());
        old.remove("compendium");
        String command = id();
        assertTrue(service.importBackup(command, old.toString()).ok);
        assertEquals(Set.of("persona_118"), prefs.favorites());
        prefs.setFavorite("persona_119", true);
        assertTrue(service.importBackup(command, old.toString()).duplicate);
        assertEquals(Set.of("persona_118", "persona_119"), prefs.favorites());
    }

    @Test
    public void exportImportRoundTripIsByteStable() throws Exception {
        buildHistory(service);
        String exported = service.exportBackup();
        String observedBefore = Canonical.of(observed().toJson());
        List<String> historyBefore = summaries(service);

        service = open("restored.db");
        PlannerApi.Receipt r = service.importBackup(id(), exported);
        assertTrue(r.errorZh, r.ok);

        assertEquals(observedBefore, Canonical.of(observed().toJson()));
        assertEquals(stripTime(exported), stripTime(service.exportBackup()));
        List<String> historyAfter = summaries(service);
        assertEquals("从备份恢复了进度", historyAfter.get(0).substring(historyAfter.get(0).indexOf("从")));
        assertEquals(historyBefore, historyAfter.subList(1, historyAfter.size()));
        assertTrue(service.snapshot().profile.personaBonus);
        assertEquals(service.snapshot().eventsSinceCheckpoint, 2);
    }

    @Test
    public void damagedBackupsChangeNothing() throws Exception {
        buildHistory(service);
        long revision = rev();
        List<String> history = summaries(service);

        PlannerApi.Receipt notJson = service.importBackup(id(), "这不是备份");
        assertFalse(notJson.ok);
        assertEquals("invalid", notJson.errorCode);
        assertEquals("这个文件读不出来，现在的记录没动", notJson.errorZh);

        JSONObject damaged = new JSONObject(service.exportBackup());
        damaged.getJSONArray("events").getJSONObject(1).getJSONObject("payload").remove("slot");
        PlannerApi.Receipt r = service.importBackup(id(), damaged.toString());
        assertEquals("这个文件读不出来，现在的记录没动", r.errorZh);

        JSONObject badState = new JSONObject(service.exportBackup());
        badState.getJSONArray("checkpoints").getJSONObject(1).getJSONObject("state").put("cash", -5);
        assertEquals("这个文件读不出来，现在的记录没动", service.importBackup(id(), badState.toString()).errorZh);

        assertEquals(revision, rev());
        assertEquals(history, summaries(service));
    }

    @Test
    public void importReplacesEverythingAndOutdatesOldRevisions() {
        onboard(MAY12_DAY);
        String small = service.exportBackup();
        buildMore();
        long before = rev();
        long profileBefore = service.snapshot().profileRevision;

        PlannerApi.Receipt r = service.importBackup(id(), small);
        assertTrue(r.errorZh, r.ok);
        assertTrue("callers holding old revisions are now stale", r.stateRevision > before);
        assertTrue(r.profileRevision > profileBefore);
        assertEquals(MAY12_DAY, observed().slot);
        assertEquals(6, observed().rank[TestRules.EMPEROR]);
        assertEquals(0, service.snapshot().eventsSinceCheckpoint);

        PlannerApi.Receipt stale = record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED);
        assertTrue(stale.ok);
        PlannerApi.Receipt old = service.recordOutcome(id(), before, MAY12_EVE, "act:study_room",
                Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals("stale", old.errorCode);
        settle();
        assertNotNull(service.snapshot().plan);
        assertTrue(service.snapshot().planCurrent);
    }

    @Test
    public void retryingAnImportDoesNotWipeLaterRecords() {
        onboard(MAY12_DAY);
        String backup = service.exportBackup();
        String command = id();
        assertTrue(service.importBackup(command, backup).ok);
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);

        PlannerApi.Receipt retry = service.importBackup(command, backup);
        assertTrue(retry.ok);
        assertTrue(retry.duplicate);
        assertEquals(7, observed().rank[TestRules.EMPEROR]);

        PlannerApi.Receipt reused = service.importBackup(command, backup.replace("\"exportedMillis\":", "\"exportedMillis\":1"));
        assertEquals("conflict", reused.errorCode);
    }

    // ------------------------------------------------------------------------------ helpers

    /** A checkpoint, outcomes, a tower visit with a correction, a void and a profile sync. */
    private void buildHistory(PlannerService s) {
        onboard(MAY12_DAY);
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        PlannerApi.Receipt visit = tower(MAY12_EVE, 20, false, 3_000);
        assertTrue(s.correctTower(id(), rev(), visit.eventSeq, towerReturn(0, 27, true, 20_000)).ok);
        PlannerApi.Receipt meet = record(MAY13_DAY, "meet:emperor", Outcome.Code.NO_RANK_UP);
        assertTrue(s.voidEvent(id(), rev(), meet.eventSeq).ok);
        Profile profile = new Profile();
        profile.personaBonus = true;
        assertTrue(s.saveCheckpoint(id(), rev(), TestRules.state(rules, MAY13_DAY), profile).ok);
        assertTrue(record(MAY13_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(tower(MAY13_EVE, 30, true, 25_000).ok);
    }

    private void buildMore() {
        assertTrue(record(MAY12_DAY, "meet:emperor", Outcome.Code.AS_PREDICTED).ok);
        assertTrue(record(MAY12_EVE, "act:study_room", Outcome.Code.AS_PREDICTED).ok);
        Profile p = new Profile();
        p.goalBuffer = false;
        assertTrue(service.saveProfile(id(), service.snapshot().profileRevision, p).ok);
    }

    private static List<String> summaries(PlannerService s) {
        List<String> out = new ArrayList<>();
        for (PlannerApi.HistoryItem item : s.history(0)) {
            out.add(item.summaryZh + (item.voided ? " [voided]" : "") + (item.correctable ? " [c]" : ""));
        }
        return out;
    }

    private static String stripTime(String backup) throws Exception {
        JSONObject o = new JSONObject(backup);
        o.remove("exportedMillis");
        return Canonical.of(o);
    }
}
