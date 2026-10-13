package app.p3r.dayplanner.v2;
import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PlannerCoordinatorTest {
    private PlannerCoordinator app;private Context context;
    @Before public void setup()throws Exception{context=RuntimeEnvironment.getApplication();context.deleteDatabase("p3r_goal_planner_v2.db");app=new PlannerCoordinator(context);}
    @After public void close(){app.close();}
    private JSONObject baseline()throws Exception{return new JSONObject().put("rules_version",app.pack.version).put("game_slot",552).put("cash",1000).put("highest_floor",200).put("active_stage","p3r.tower.stage_10").put("visits_by_stage",new JSONObject().put("p3r.tower.stage_10",0)).put("facts",new JSONObject().put("socialLinks.sun.rank",new JSONObject().put("low",9).put("high",9)).put("socialLinks.sun.normal",new JSONObject().put("low",1).put("high",1)));}
    private void create()throws Exception{app.request("/api/baseline",new JSONObject().put("command_id","start").put("expected_revision",JSONObject.NULL).put("baseline",baseline()).toString());}
    @Test public void realRulesAndNativeSearchAreConditional()throws Exception{
        create();long revision=app.store.getSnapshot("local").getLong("revision");
        app.request("/api/preferences",new JSONObject().put("command_id","goals").put("expected_revision",revision).put("goals",new JSONArray().put(new JSONObject().put("field","socialLinks.sun.rank").put("target",10))).put("constraints",new JSONObject().put("visit_caps",new JSONObject().put("p3r.tower.stage_10",1))).toString());
        String before=app.store.getSnapshot("local").toString();JSONObject result=app.replan();assertEquals("conditional",result.getJSONObject("plan").getString("status"));assertEquals(before,app.store.getSnapshot("local").toString());
    }
    @Test public void nativeReturnDurableWithoutGoalsAndIdempotent()throws Exception{
        create();long revision=app.store.getSnapshot("local").getLong("revision");JSONObject body=new JSONObject().put("command_id","return").put("expected_revision",revision).put("stage_id","p3r.tower.stage_10").put("highest_floor",210).put("absolute_balance",0).put("visit_slot",552).put("next_game_slot",553);
        JSONObject first=app.request("/api/return",body.toString());assertTrue(first.getBoolean("replan_pending"));JSONObject again=app.request("/api/return",body.toString());assertTrue(again.getJSONObject("observation").getBoolean("duplicate"));assertEquals(0,app.store.getSnapshot("local").getInt("cash"));assertEquals(1,app.store.getSnapshot("local").getJSONObject("visits_by_stage").getInt("p3r.tower.stage_10"));
    }
    @Test public void observedSocialResultUsesActualRankAndClearsHiddenForecasts()throws Exception {
        create();long revision=app.store.getSnapshot("local").getLong("revision");
        JSONObject body=new JSONObject().put("command_id","actual-social").put("expected_revision",revision).put("entity_id","sun").put("observed_rank",9).put("relationship_normal",true).put("ready",JSONObject.NULL).put("action_id","observed.social.sun").put("action_slot",552).put("next_game_slot",553);
        app.request("/api/observed_social",body.toString());JSONObject state=app.store.getSnapshot("local");
        assertEquals(9,state.getJSONObject("facts").getJSONObject("socialLinks.sun.rank").getInt("low"));
        assertTrue(state.getJSONObject("facts").getJSONObject("socialLinks.sun.ready").isNull("low"));
        assertEquals(0,state.getJSONObject("visits_by_stage").getInt("p3r.tower.stage_10"));
        assertTrue(app.request("/api/observed_social",body.toString()).getJSONObject("observation").getBoolean("duplicate"));
    }
    @Test public void reversedRelationshipCannotCarryReadyTrue()throws Exception {
        create();long revision=app.store.getSnapshot("local").getLong("revision");
        JSONObject body=new JSONObject().put("command_id","reversed").put("expected_revision",revision).put("entity_id","sun").put("observed_rank",9).put("relationship_normal",false).put("ready",true).put("action_id","observed.social.sun").put("action_slot",552).put("next_game_slot",553);
        app.request("/api/observed_social",body.toString());assertTrue(app.store.getSnapshot("local").getJSONObject("facts").getJSONObject("socialLinks.sun.ready").isNull("low"));
    }
    @Test public void missingGoalsDoNotCreateSyntheticPlan()throws Exception{create();assertTrue(app.replan().isNull("plan"));}
    @Test public void atomicSetupRetryKeepsOneRevisionAndPreferences()throws Exception {
        JSONObject body=new JSONObject().put("command_id","setup-all").put("expected_revision",JSONObject.NULL).put("baseline",baseline()).put("goals",new JSONArray().put(new JSONObject().put("field","socialLinks.sun.rank").put("target",10))).put("constraints",new JSONObject());
        JSONObject first=app.request("/api/setup",body.toString());long revision=first.getJSONObject("observation").getJSONObject("snapshot").getLong("revision");
        app.close();app=new PlannerCoordinator(context);
        JSONObject retry=app.request("/api/setup",body.toString());assertTrue(retry.getJSONObject("observation").getBoolean("duplicate"));assertEquals(revision,app.store.getSnapshot("local").getLong("revision"));assertEquals(1,app.store.getPreferences("local").getJSONArray("goals").length());
    }
    @Test public void refreshingSameStateDoesNotInvalidateInstalledActionIdentity()throws Exception {
        create();long revision=app.store.getSnapshot("local").getLong("revision");app.request("/api/preferences",new JSONObject().put("command_id","goal-cached").put("expected_revision",revision).put("goals",new JSONArray().put(new JSONObject().put("field","socialLinks.sun.rank").put("target",10))).put("constraints",new JSONObject()).toString());
        long planRevision=app.replan().getLong("plan_revision");assertEquals(planRevision,app.replan().getLong("plan_revision"));
    }
    @Test public void dungeonCannotBypassVisitCountingThroughSocialForm()throws Exception {
        create();long revision=app.store.getSnapshot("local").getLong("revision");
        JSONObject body=new JSONObject().put("command_id","bad-social").put("expected_revision",revision).put("entity_id","sun").put("observed_rank",9).put("relationship_normal",true).put("action_id","p3r.action.explore_tartarus").put("action_slot",552).put("next_game_slot",553);
        try{app.request("/api/observed_social",body.toString());fail("Must reject dungeon through social route");}catch(IllegalArgumentException expected){}
        assertEquals(revision,app.store.getSnapshot("local").getLong("revision"));
    }
    @Test public void statResultStoresVisibleRankIntervalWithoutForecastCredit()throws Exception {
        create();long revision=app.store.getSnapshot("local").getLong("revision");JSONObject body=new JSONObject().put("command_id","stat-result").put("expected_revision",revision).put("visible_ranks",new JSONObject().put("courage",4)).put("action_slot",552).put("next_game_slot",553).put("next_slot_capacity",1);
        app.request("/api/observed_stats",body.toString());JSONObject state=app.store.getSnapshot("local"),raw=state.getJSONObject("facts").getJSONObject("socialStats.courage.raw");assertEquals(45,raw.getInt("low"));assertEquals(59,raw.getInt("high"));assertTrue(state.isNull("cash"));assertEquals(0,state.getJSONObject("visits_by_stage").getInt("p3r.tower.stage_10"));assertEquals(1,state.getJSONObject("planning_context").getJSONObject("calendar").getJSONObject("slotStates").getJSONObject("553").getInt("remainingCapacity"));assertTrue(app.request("/api/observed_stats",body.toString()).getJSONObject("observation").getBoolean("duplicate"));
    }
}
