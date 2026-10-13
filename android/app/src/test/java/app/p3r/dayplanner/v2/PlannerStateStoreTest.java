package app.p3r.dayplanner.v2;

import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic ledger fixtures, not actual Persona 3 Reload rules. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class PlannerStateStoreTest {
    Context context; PlannerStateStore store;
    JSONObject json(String s) throws Exception { return new JSONObject(s); }
    JSONObject base() throws Exception {
        return json("{\"rules_version\":\"synthetic\",\"game_slot\":100,\"cash\":1000,\"highest_floor\":10,\"active_stage\":\"a\",\"visits_by_stage\":{\"a\":1},\"facts\":{\"rank\":{\"low\":2,\"high\":3}},\"legacy_guide_backup\":{\"done\":[\"old-task\"]}}");
    }
    JSONObject payload() throws Exception { return json("{\"stage_id\":\"a\",\"highest_floor\":20,\"net_cash\":500}"); }
    JSONObject snap() { return store.getSnapshot("run"); }
    long rev() { return snap().optLong("revision"); }
    JSONObject ret(String id,JSONObject p) { return store.recordDungeonReturn("run",id,rev(),p); }
    interface Throwing { void run() throws Exception; }
    void invalid(Throwing action) throws Exception {
        String before=snap().toString();
        try { action.run(); fail("expected rejection"); } catch(PlannerStateStore.InvalidObservation|PlannerStateStore.StateConflict expected) { }
        assertEquals(before,snap().toString());
    }
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication(); context.deleteDatabase(PlannerStateStore.DATABASE_NAME);
        store=new PlannerStateStore(context,json("{\"a\":22,\"b\":43}"),null); store.createRun("run",base());
    }
    @After public void finish() { store.close(); context.deleteDatabase(PlannerStateStore.DATABASE_NAME); }
    @Test public void returnRetryAcrossRestart() throws Exception {
        JSONObject first=store.recordDungeonReturn("run","one",0,payload());
        store.close(); store=new PlannerStateStore(context,json("{\"a\":22}"),null);
        JSONObject retry=store.recordDungeonReturn("run","one",0,payload());
        assertTrue(retry.getBoolean("duplicate")); assertEquals(first.getJSONObject("snapshot").getLong("revision"),snap().getLong("revision"));
        assertEquals(1500,snap().getLong("cash")); assertEquals(2,snap().getJSONObject("visits_by_stage").getInt("a"));
    }
    @Test public void canonicalOrderDoesNotBreakRetry() throws Exception {
        ret("one",payload()); JSONObject retry=json("{\"net_cash\":500,\"highest_floor\":20,\"stage_id\":\"a\"}");
        assertTrue(store.recordDungeonReturn("run","one",0,retry).getBoolean("duplicate"));
    }
    @Test public void differentPayloadAndStaleCommandReject() throws Exception {
        ret("one",payload());
        invalid(()->store.recordDungeonReturn("run","one",0,payload().put("net_cash",501)));
        invalid(()->store.recordDungeonReturn("run","two",0,payload()));
    }
    @Test public void zeroAbsoluteAndNegativeDelta() throws Exception {
        JSONObject p=payload(); p.remove("net_cash"); p.put("absolute_balance",0); ret("zero",p);
        assertEquals(0,snap().getLong("cash")); invalid(()->ret("negative",payload().put("net_cash",-1)));
        p.put("absolute_balance",100); ret("money",p); ret("spent",payload().put("net_cash",-100)); assertEquals(0,snap().getLong("cash"));
    }
    @Test public void cashXorAndFloorValidationAtomic() throws Exception {
        invalid(()->ret("bad",payload().put("absolute_balance",1500)));
        invalid(()->{JSONObject p=payload(); p.remove("net_cash"); ret("bad",p);});
        for(Object floor:new Object[]{-1,23,1.5,true,"20"}) invalid(()->ret("floor",payload().put("highest_floor",floor)));
    }
    @Test public void cashRejectsCoercionAndOverflow() throws Exception {
        for(Object cash:new Object[]{1.5,true,"100"}) invalid(()->ret("cash",payload().put("net_cash",cash)));
        invalid(()->ret("overflow",payload().put("net_cash",Long.MAX_VALUE)));
    }
    @Test public void unknownCashAndCountsStayUnknown() throws Exception {
        JSONObject b=base().put("cash",JSONObject.NULL); b.getJSONObject("visits_by_stage").put("a",JSONObject.NULL);
        store.reenter("run","checkpoint",rev(),b); invalid(()->ret("unknown",payload()));
        JSONObject p=payload(); p.remove("net_cash"); p.put("absolute_balance",0); JSONObject receipt=ret("actual",p);
        assertTrue(snap().getJSONObject("visits_by_stage").isNull("a"));
        assertEquals(1,snap().getJSONObject("visits_since_baseline").getInt("a")); assertEquals(1,receipt.getJSONArray("warnings").length());
    }
    @Test public void missingCountIsNotZero() throws Exception {
        JSONObject b=base().put("visits_by_stage",new JSONObject()); store.reenter("run","checkpoint",rev(),b); ret("one",payload());
        assertTrue(snap().getJSONObject("visits_by_stage").isNull("a"));
    }
    @Test public void lowerFloorVisitRetainsMaximum() throws Exception {
        ret("one",payload()); ret("two",payload().put("highest_floor",5)); assertEquals(20,snap().getInt("highest_floor"));
    }
    @Test public void capturedSlotNewCommandRejects() throws Exception {
        JSONObject p=payload().put("visit_slot",100).put("next_game_slot",101); ret("one",p); invalid(()->ret("new-id",p));
    }
    @Test public void correctionsDoNotAddVisitOrTime() throws Exception {
        ret("one",payload()); ret("two",payload().put("net_cash",200).put("highest_floor",22));
        ret("fix",payload().put("net_cash",100).put("highest_floor",18).put("corrects_event_id","one"));
        assertEquals(1300,snap().getLong("cash")); assertEquals(3,snap().getJSONObject("visits_by_stage").getInt("a"));
        assertEquals(102,snap().getInt("game_slot")); assertEquals(22,snap().getInt("highest_floor"));
    }
    @Test public void laterAbsoluteBalanceSurvivesCorrection() throws Exception {
        ret("one",payload()); JSONObject p=payload(); p.remove("net_cash"); p.put("absolute_balance",2000); ret("two",p);
        ret("fix",payload().put("net_cash",1).put("corrects_event_id","one")); assertEquals(2000,snap().getLong("cash"));
    }
    @Test public void invalidCorrectionRollsBack() throws Exception {
        ret("one",payload()); ret("spent",payload().put("net_cash",-1400));
        invalid(()->ret("fix",payload().put("net_cash",0).put("corrects_event_id","one")));
        ret("valid-fix",payload().put("net_cash",600).put("corrects_event_id","one")); assertEquals(200,snap().getLong("cash"));
    }
    @Test public void correctionCannotChangeStageOrTime() throws Exception {
        ret("one",payload());
        invalid(()->ret("fix",payload().put("corrects_event_id","one").put("stage_id","b")));
        invalid(()->ret("fix",payload().put("corrects_event_id","one").put("visit_slot",101).put("next_game_slot",102)));
    }
    @Test public void reentryCanResetBaselineAndRejectOldCorrections() throws Exception {
        ret("one",payload()); long previous=rev(); JSONObject b=base().put("cash",75).put("highest_floor",2);
        store.reenter("run","new-save",previous,b); assertEquals(75,snap().getLong("cash")); assertEquals(2,snap().getInt("highest_floor"));
        assertTrue(store.reenter("run","new-save",previous,b).getBoolean("duplicate"));
        invalid(()->ret("old-fix",payload().put("corrects_event_id","one")));
    }
    @Test public void planCasAndObservationInvalidation() throws Exception {
        long original=rev(); String before=snap().toString(); store.installPlan("run",original,"synthetic",new JSONObject());
        assertEquals(before,snap().toString()); assertNotNull(store.getPlan("run")); ret("one",payload()); assertNull(store.getPlan("run"));
        invalid(()->store.installPlan("run",original,"synthetic",new JSONObject()));
        invalid(()->store.installPlan("run",rev(),"old-version",new JSONObject()));
    }
    @Test public void preferencesInvalidateAsyncPlansAndPersist() throws Exception {
        JSONArray goals=new JSONArray("[{\"field\":\"rank\",\"target\":10,\"priority\":1}]"); JSONObject c=json("{\"visit_caps\":{\"a\":0}}");
        store.installPlan("run",0,"synthetic",new JSONObject()); store.setPreferences("run","prefs",0,goals,c);
        assertNull(store.getPlan("run")); invalid(()->store.installPlan("run",0,"synthetic",new JSONObject()));
        assertTrue(store.setPreferences("run","prefs",0,goals,c).getBoolean("duplicate"));
        ret("actual-over-cap",payload()); assertEquals(0,store.getPreferences("run").getJSONObject("constraints").getJSONObject("visit_caps").getInt("a"));
        store.close(); store=new PlannerStateStore(context,null,null); assertNotNull(store.getPreferences("run"));
    }
    @Test public void malformedBaselineAndPreferencesReject() throws Exception {
        invalid(()->store.reenter("run","bad",rev(),base().put("cash",-1)));
        invalid(()->store.reenter("run","bad",rev(),base().put("facts",json("{\"rank\":{\"low\":3,\"high\":2}}"))));
        invalid(()->store.setPreferences("run","bad",rev(),new JSONArray(),json("{\"visit_caps\":[]} " )));
    }
    @Test public void stageAdvanceChargesEntryStage() throws Exception {
        store.close(); store=new PlannerStateStore(context,json("{\"a\":22,\"b\":43}"),json("{\"a\":[0,101],\"b\":[101,200]}"));
        ret("boundary",payload()); assertEquals("b",snap().getString("active_stage"));
        assertEquals(2,snap().getJSONObject("visits_by_stage").getInt("a"));
        assertFalse(snap().getJSONObject("visits_since_baseline").has("b"));
        invalid(()->store.reenter("run","wrong-stage",rev(),base().put("active_stage","b")));
    }
    @Test public void exportsAreDetachedAndPreserveLegacyBackup() throws Exception {
        JSONObject exposed=snap(); exposed.put("cash",9999); assertEquals(1000,snap().getLong("cash"));
        ret("one",payload()); JSONObject exported=store.exportRun("run");
        assertEquals("p3r_planner_v2",exported.getString("format")); assertEquals(1,exported.getJSONArray("events").length());
        assertEquals("old-task",exported.getJSONObject("run").getJSONObject("snapshot").getJSONObject("legacy_guide_backup").getJSONArray("done").getString(0));
    }
    JSONObject observation(int slot,int next) throws Exception {
        return json("{\"action_id\":\"social-meeting\",\"fact_assignments\":{\"rank\":{\"low\":4,\"high\":4}}}")
            .put("action_slot",slot).put("next_game_slot",next);
    }
    @Test public void observedActionIsExplicitAndIdempotentAfterRestart() throws Exception {
        JSONObject action=observation(100,101); long revision=rev();
        store.recordObservedAction("run","social",revision,action);
        assertEquals(4,snap().getJSONObject("facts").getJSONObject("rank").getInt("low"));
        assertEquals(1,snap().getJSONObject("visits_by_stage").getInt("a")); assertEquals(1000,snap().getLong("cash"));
        assertEquals(0,snap().getJSONObject("visits_since_baseline").length());
        store.close(); store=new PlannerStateStore(context,null,null);
        assertTrue(store.recordObservedAction("run","social",revision,action).getBoolean("duplicate"));
        invalid(()->store.recordObservedAction("run","social",revision,action.put("absolute_balance",20)));
    }
    @Test public void observedActionPlanBindingRejectsStaleAndWrongInstance() throws Exception {
        JSONObject plan=json("{\"steps\":[{\"instance_id\":\"instance-1\",\"action_id\":\"social-meeting\",\"slot\":100}]}");
        long p=store.installPlan("run",rev(),"synthetic",plan);
        JSONObject action=observation(100,101).put("expected_plan_revision",p).put("action_instance_id","wrong");
        invalid(()->store.recordObservedAction("run","wrong",rev(),action));
        action.put("action_instance_id","instance-1"); long observedRevision=rev();
        store.recordObservedAction("run","correct",observedRevision,action); assertNull(store.getPlan("run"));
        assertTrue(store.recordObservedAction("run","correct",observedRevision,action).getBoolean("duplicate"));
        invalid(()->store.recordObservedAction("run","stale",rev(),observation(101,102).put("expected_plan_revision",p).put("action_instance_id","instance-1")));
    }
    @Test public void observedActionRejectsPartialPlanBindingAndMalformedFacts() throws Exception {
        invalid(()->store.recordObservedAction("run","bad",rev(),observation(100,101).put("expected_plan_revision",1)));
        invalid(()->store.recordObservedAction("run","bad",rev(),observation(100,101).put("action_instance_id","id")));
        invalid(()->store.recordObservedAction("run","bad",rev(),observation(100,101).put("fact_assignments",json("{\"rank\":{\"low\":8,\"high\":4}}"))));
        invalid(()->store.recordObservedAction("run","bad",rev(),observation(100,101).put("absolute_balance",-1)));
    }
    @Test public void laterObservedFactsCashAndTimeSurviveDungeonCorrection() throws Exception {
        ret("dungeon",payload());
        JSONObject actual=observation(101,102).put("absolute_balance",70);
        actual.getJSONObject("fact_assignments").put("affinity",json("{\"low\":null,\"high\":null}"));
        store.recordObservedAction("run","social",rev(),actual);
        ret("fix",payload().put("corrects_event_id","dungeon").put("net_cash",200));
        assertEquals(70,snap().getLong("cash")); assertEquals(102,snap().getInt("game_slot"));
        assertEquals(4,snap().getJSONObject("facts").getJSONObject("rank").getInt("low"));
        assertTrue(snap().getJSONObject("facts").getJSONObject("affinity").isNull("low"));
        assertEquals(2,snap().getJSONObject("visits_by_stage").getInt("a"));
    }
    @Test public void observedAndDungeonSlotsCannotOverlap() throws Exception {
        store.recordObservedAction("run","social",rev(),observation(100,101));
        invalid(()->ret("dungeon",payload().put("visit_slot",100).put("next_game_slot",101)));
        invalid(()->store.recordObservedAction("run","social2",rev(),observation(100,101)));
        ret("dungeon",payload()); assertEquals(102,snap().getInt("game_slot"));
    }
    @Test public void planningContextAndOptionPolicyPreservedWithoutInventedDefaults() throws Exception {
        assertFalse(snap().has("planning_context"));
        JSONObject b=base().put("planning_context",json("{\"story_flags\":{\"rescue\":null}}"))
            .put("option_policy",json("{\"matching_persona\":\"ask\"}"));
        store.reenter("run","context",rev(),b); b.getJSONObject("planning_context").put("new",true);
        ret("one",payload());
        assertFalse(snap().getJSONObject("planning_context").has("new"));
        assertTrue(snap().getJSONObject("planning_context").getJSONObject("story_flags").isNull("rescue"));
        assertEquals("ask",store.exportRun("run").getJSONObject("run").getJSONObject("snapshot").getJSONObject("option_policy").getString("matching_persona"));
        invalid(()->store.reenter("run","bad-context",rev(),base().put("planning_context",new JSONArray())));
        invalid(()->store.reenter("run","bad-policy",rev(),base().put("option_policy",JSONObject.NULL)));
    }

    @Test public void atomicSetupChangesBaselinePreferencesAndRevisionOnce() throws Exception {
        store.installPlan("run",0,"synthetic",new JSONObject());
        JSONObject b=base().put("cash",77); JSONArray goals=new JSONArray("[{\"field\":\"rank\",\"target\":10}]");
        JSONObject constraints=json("{\"visit_caps\":{\"a\":2}}");
        JSONObject receipt=store.reenterWithPreferences("run","setup",0,b,goals,constraints);
        assertFalse(receipt.getBoolean("duplicate")); assertEquals(1,rev()); assertEquals(1,snap().getLong("epoch"));
        assertEquals(77,snap().getLong("cash")); assertNull(store.getPlan("run"));
        assertEquals(10,store.getPreferences("run").getJSONArray("goals").getJSONObject(0).getInt("target"));
        JSONArray events=store.exportRun("run").getJSONArray("events"); assertEquals(1,events.length());
        assertEquals("setup",events.getJSONObject(0).getString("kind"));
        ret("after-setup",payload()); assertEquals(577,snap().getLong("cash"));
        assertEquals(2,store.getPreferences("run").getJSONObject("constraints").getJSONObject("visit_caps").getInt("a"));
    }
    @Test public void atomicSetupRetrySurvivesRestartAndRejectsPayloadChange() throws Exception {
        JSONObject b=base().put("cash",50); JSONArray goals=new JSONArray("[{\"field\":\"rank\",\"target\":8}]"); JSONObject constraints=new JSONObject();
        store.reenterWithPreferences("run","setup",0,b,goals,constraints);
        store.close(); store=new PlannerStateStore(context,null,null);
        assertTrue(store.reenterWithPreferences("run","setup",0,b,goals,constraints).getBoolean("duplicate"));
        assertEquals(1,rev()); assertEquals(1,store.exportRun("run").getJSONArray("events").length());
        invalid(()->store.reenterWithPreferences("run","setup",0,b.put("cash",51),goals,constraints));
    }
    @Test public void invalidOrStaleAtomicSetupLeavesBothStoresUnchanged() throws Exception {
        JSONArray goals=new JSONArray("[{\"field\":\"rank\",\"target\":8}]");
        store.setPreferences("run","initial-prefs",0,goals,new JSONObject());
        String prefs=store.getPreferences("run").toString(), exported=store.exportRun("run").toString();
        invalid(()->store.reenterWithPreferences("run","bad",rev(),base().put("cash",2),goals,json("{\"visit_caps\":{\"a\":-1}}")));
        assertEquals(prefs,store.getPreferences("run").toString()); assertEquals(exported,store.exportRun("run").toString());
        invalid(()->store.reenterWithPreferences("run","stale",0,base().put("cash",3),goals,new JSONObject()));
        assertEquals(prefs,store.getPreferences("run").toString()); assertEquals(exported,store.exportRun("run").toString());
        invalid(()->store.reenterWithPreferences("run","bad-baseline",rev(),base().put("cash",-1),goals,new JSONObject()));
        assertEquals(prefs,store.getPreferences("run").toString());
    }

    @Test public void historicalReturnRetrySurvivesCeilingUpdate() throws Exception {
        JSONObject command=payload(); ret("old-return",command);
        store.close(); store=new PlannerStateStore(context,json("{\"a\":15}"),null);
        assertTrue(store.recordDungeonReturn("run","old-return",0,command).getBoolean("duplicate"));
        assertEquals(1500,snap().getLong("cash"));
        invalid(()->ret("new-return",payload()));
        invalid(()->store.recordDungeonReturn("run","old-return",0,payload().put("net_cash",501)));
    }
    @Test public void historicalReentryRetrySurvivesCalendarUpdate() throws Exception {
        JSONObject b=base(); store.reenter("run","old-baseline",0,b);
        store.close(); store=new PlannerStateStore(context,null,json("{\"b\":[0,200]}"));
        assertTrue(store.reenter("run","old-baseline",0,b).getBoolean("duplicate")); assertEquals(1,rev());
        invalid(()->store.reenter("run","new-baseline",rev(),b));
    }
    @Test public void historicalAtomicSetupRetrySurvivesCalendarUpdate() throws Exception {
        JSONObject b=base(); JSONArray goals=new JSONArray("[{\"field\":\"rank\",\"target\":8}]"); JSONObject constraints=new JSONObject();
        store.reenterWithPreferences("run","old-setup",0,b,goals,constraints);
        store.close(); store=new PlannerStateStore(context,json("{\"b\":43}"),json("{\"b\":[0,200]}"));
        assertTrue(store.reenterWithPreferences("run","old-setup",0,b,goals,constraints).getBoolean("duplicate")); assertEquals(1,rev());
        invalid(()->store.reenterWithPreferences("run","new-setup",rev(),b,goals,constraints));
        assertEquals(8,store.getPreferences("run").getJSONArray("goals").getJSONObject(0).getInt("target"));
    }


    @Test public void initialSetupIsAtomicAndRetryable() throws Exception {
        JSONArray goals=new JSONArray("[{\"field\":\"rank\",\"target\":10}]");
        try { store.createRunWithPreferences("new","setup",base(),goals,json("{\"visit_caps\":{\"a\":-1}}")); fail(); } catch(PlannerStateStore.InvalidObservation expected) {}
        try { store.getSnapshot("new"); fail(); } catch(PlannerStateStore.InvalidObservation expected) {}
        JSONObject first=store.createRunWithPreferences("new","setup",base(),goals,new JSONObject());
        assertFalse(first.getBoolean("duplicate")); assertEquals(0,first.getJSONObject("snapshot").getLong("revision"));
        store.close(); store=new PlannerStateStore(context,null,json("{\"b\":[0,200]}"));
        assertTrue(store.createRunWithPreferences("new","setup",base(),goals,new JSONObject()).getBoolean("duplicate"));
        assertEquals(1,store.exportRun("new").getJSONArray("events").length());
        assertEquals(10,store.getPreferences("new").getJSONArray("goals").getJSONObject(0).getInt("target"));
    }
    @Test public void explicitNextCapacitySurvivesReplayAndCorrection() throws Exception {
        JSONObject p=payload().put("next_slot_capacity",1); ret("tower",p);
        assertEquals(1,snap().getJSONObject("planning_context").getJSONObject("calendar").getJSONObject("slotStates").getJSONObject("101").getInt("remainingCapacity"));
        store.recordObservedAction("run","social",rev(),observation(101,102).put("next_slot_capacity",0));
        ret("correction",payload().put("corrects_event_id","tower").put("net_cash",10));
        JSONObject states=snap().getJSONObject("planning_context").getJSONObject("calendar").getJSONObject("slotStates");
        assertEquals(0,states.getJSONObject("102").getInt("remainingCapacity")); assertEquals(1,states.getJSONObject("101").getInt("remainingCapacity"));
        assertEquals(102,snap().getInt("game_slot")); assertEquals(2,snap().getJSONObject("visits_by_stage").getInt("a"));
        assertTrue(store.recordDungeonReturn("run","tower",0,p).getBoolean("duplicate"));
        invalid(()->ret("bad-correction",payload().put("corrects_event_id","tower").put("next_slot_capacity",0)));
    }
    @Test public void missingNextCapacityIsUnknownAndInvalidCapacityAtomic() throws Exception {
        JSONObject b=base().put("planning_context",json("{\"calendar\":{\"slotStates\":{\"101\":{\"remainingCapacity\":1}}}}")); store.reenter("run","baseline",0,b);
        invalid(()->ret("bad",payload().put("next_slot_capacity",2)));
        invalid(()->store.recordObservedAction("run","bad-social",rev(),observation(100,101).put("next_slot_capacity",true)));
        ret("old",payload()); JSONObject next=snap().getJSONObject("planning_context").getJSONObject("calendar").getJSONObject("slotStates").getJSONObject("101");
        assertTrue(next.isNull("remainingCapacity")); assertTrue(next.isNull("knownStoryLock"));
    }

    @Test public void unknownObservedCashPersistsAndLaterAbsoluteRestoresIt() throws Exception {
        store.recordObservedAction("run","training",0,observation(100,101).put("absolute_balance",JSONObject.NULL));
        assertTrue(snap().isNull("cash"));
        store.close(); store=new PlannerStateStore(context,null,null); assertTrue(snap().isNull("cash"));
        invalid(()->ret("unknown-net",payload()));
        store.recordObservedAction("run","later",rev(),observation(101,102).put("absolute_balance",400));
        assertEquals(400,snap().getInt("cash"));
    }

    @Test public void slotAdvanceExpiresCurrentUnlockFlagsButRetainsPreparation() throws Exception {
        JSONObject b=base().put("planning_context",json("{\"unlock\":{\"sun\":{\"observations\":{\"current.sun.rank1_interaction_available\":true,\"calendar.sun.exact_date_legal\":true,\"observedIntroductionAvailable\":true,\"inventory.red_fountain_pen\":1,\"prep.penFound\":true}}}}"));
        store.reenter("run","baseline",0,b);ret("tower",payload().put("next_slot_capacity",1));
        JSONObject observations=snap().getJSONObject("planning_context").getJSONObject("unlock").getJSONObject("sun").getJSONObject("observations");
        assertFalse(observations.has("observedIntroductionAvailable")); assertFalse(observations.has("current.sun.rank1_interaction_available"));assertFalse(observations.has("calendar.sun.exact_date_legal"));assertTrue(observations.getBoolean("prep.penFound"));assertEquals(1,observations.getInt("inventory.red_fountain_pen"));
        store.recordObservedAction("run","later",rev(),observation(101,102).put("next_slot_capacity",1));
        ret("fix",payload().put("corrects_event_id","tower").put("net_cash",100));
        JSONObject context=snap().getJSONObject("planning_context");assertEquals(1,context.getJSONObject("calendar").getJSONObject("slotStates").getJSONObject("102").getInt("remainingCapacity"));assertFalse(context.getJSONObject("unlock").getJSONObject("sun").getJSONObject("observations").has("observedIntroductionAvailable"));
    }
}
