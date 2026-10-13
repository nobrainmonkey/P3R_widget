package app.p3r.dayplanner.v2;

import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real packaged partial rules; fault injection only checks durability, never game mechanics. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PlannerCoordinatorAdversarialTest {
    Context context; PlannerCoordinator app;
    @Before public void setup() throws Exception { context=RuntimeEnvironment.getApplication(); context.deleteDatabase(PlannerStateStore.DATABASE_NAME); app=new PlannerCoordinator(context); }
    @After public void finish() { app.close(); context.deleteDatabase(PlannerStateStore.DATABASE_NAME); }
    JSONObject base() throws Exception { return new JSONObject().put("rules_version",app.pack.version).put("game_slot",app.pack.firstSlot)
        .put("cash",1000).put("highest_floor",200).put("active_stage","p3r.tower.stage_10")
        .put("visits_by_stage",new JSONObject().put("p3r.tower.stage_10",0))
        .put("facts",new JSONObject().put("socialLinks.sun.rank",new JSONObject().put("low",9).put("high",9))
        .put("socialLinks.sun.normal",new JSONObject().put("low",1).put("high",1))); }
    JSONObject creation() throws Exception { return new JSONObject().put("command_id","create").put("expected_revision",JSONObject.NULL).put("baseline",base()); }
    void create() throws Exception { app.request("/api/baseline",creation().toString()); }
    long revision() throws Exception { return app.store.getSnapshot("local").getLong("revision"); }
    JSONObject goal(String field) throws Exception { return new JSONObject().put("command_id","goal").put("expected_revision",revision())
        .put("goals",new JSONArray().put(new JSONObject().put("field",field).put("target",10)))
        .put("constraints",new JSONObject().put("visit_caps",new JSONObject().put("p3r.tower.stage_10",1))); }
    @Test public void initialBaselineRetryAfterRestartIsIdempotent() throws Exception {
        JSONObject command=creation(); app.request("/api/baseline",command.toString()); long rev=revision(), epoch=app.store.getSnapshot("local").getLong("epoch");
        app.close(); app=new PlannerCoordinator(context);
        JSONObject retried=app.request("/api/baseline",command.toString());
        assertTrue(retried.getJSONObject("observation").getBoolean("duplicate")); assertEquals(rev,revision());
        assertEquals(epoch,app.store.getSnapshot("local").getLong("epoch"));
    }
    @Test public void createCommitBeforeBaselineReceiptCanRecover() throws Exception {
        JSONObject command=creation(); app.store.createRun("local",command.getJSONObject("baseline"));
        JSONObject recovered=app.request("/api/baseline",command.toString());
        assertFalse(recovered.getJSONObject("observation").getBoolean("duplicate")); assertEquals(1,revision());
        assertTrue(app.request("/api/baseline",command.toString()).getJSONObject("observation").getBoolean("duplicate"));
    }
    @Test public void observedReturnSurvivesPlanningExceptionAndRestartRetry() throws Exception {
        create(); app.request("/api/preferences",goal("socialLinks.sun.rank").toString());
        JSONObject command=new JSONObject().put("command_id","return").put("expected_revision",revision())
            .put("stage_id","p3r.tower.stage_10").put("highest_floor",210).put("absolute_balance",0)
            .put("visit_slot",app.pack.firstSlot).put("next_game_slot",app.pack.firstSlot+1);
        app.pack.rules.remove("horizon"); // Explicit test-only downstream planner failure.
        JSONObject result=app.request("/api/return",command.toString());
        assertTrue(result.getBoolean("replan_pending")); assertTrue(result.isNull("planning"));
        assertEquals(0,app.store.getSnapshot("local").getLong("cash"));
        assertEquals(1,app.store.getSnapshot("local").getJSONObject("visits_by_stage").getInt("p3r.tower.stage_10"));
        long rev=revision(); app.close(); app=new PlannerCoordinator(context);
        assertTrue(app.request("/api/return",command.toString()).getJSONObject("observation").getBoolean("duplicate")); assertEquals(rev,revision());
    }
    @Test public void unmodeledSocialLinkIsUnsupportedNotImpossible() throws Exception {
        create(); JSONObject result=app.request("/api/preferences",goal("socialLinks.empress.rank").toString());
        assertEquals("unsupported",result.getJSONObject("planning").getJSONObject("plan").getString("status"));
        assertEquals(0,result.getJSONObject("planning").getJSONObject("plan").getJSONArray("steps").length());
    }
    @Test public void insufficientPartialCalendarDoesNotProveImpossible() throws Exception {
        JSONObject b=base().put("game_slot",app.pack.rules.getInt("horizon"));
        b.getJSONObject("facts").getJSONObject("socialLinks.sun.rank").put("low",1).put("high",1);
        app.request("/api/baseline",creation().put("baseline",b).toString());
        JSONObject result=app.request("/api/preferences",goal("socialLinks.sun.rank").toString());
        assertNotEquals("proven_infeasible",result.getJSONObject("planning").getJSONObject("plan").getString("status"));
    }
}
