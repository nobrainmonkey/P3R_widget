package app.p3r.dayplanner.v2;

import android.content.Context;
import org.json.*;
import java.util.*;

/** Native authoritative state and offline calculation; HTML is presentation only. */
public final class PlannerCoordinator implements AutoCloseable {
    private static final String RUN="local";
    public final PlannerRules pack;
    public final PlannerStateStore store;
    public final JSONObject stageCeilings,stageWindows;
    private final UnifiedPlannerRules unified;
    public PlannerCoordinator(Context context)throws Exception {this(context,false);}
    public PlannerCoordinator(Context context,boolean expanded)throws Exception {pack=new PlannerRules(context);unified=expanded?new UnifiedPlannerRules(context,pack):null;NativeProgressionRules progression=expanded?new NativeProgressionRules(context):null;stageCeilings=progression==null?pack.stageCeilings:progression.stageCeilings();stageWindows=progression==null?pack.stageWindows:progression.stageWindows();store=new PlannerStateStore(context,stageCeilings,stageWindows);}
    public String rulesVersion(){return unified==null?pack.version:unified.version;}
    public synchronized JSONObject state()throws Exception {
        JSONObject observed=null;
        try{observed=store.getSnapshot(RUN);}catch(PlannerStateStore.InvalidObservation absent){}
        return new JSONObject().put("snapshot",observed==null?JSONObject.NULL:observed)
            .put("preferences",nullable(store.getPreferences(RUN))).put("coverage",pack.coverage)
            .put("labels",pack.labels).put("first_slot",pack.firstSlot).put("rules_version",rulesVersion());
    }
    private static Object nullable(Object o){return o==null?JSONObject.NULL:o;}
    public synchronized JSONObject replan()throws Exception {
        JSONObject state=store.getSnapshot(RUN),prefs=store.getPreferences(RUN);
        if(prefs==null)return new JSONObject().put("plan",JSONObject.NULL).put("plan_revision",JSONObject.NULL);
        JSONObject cached=store.getPlan(RUN);
        if(cached!=null&&cached.getLong("state_revision")==state.getLong("revision")&&rulesVersion().equals(cached.getString("rules_version")))return new JSONObject().put("plan",cached.getJSONObject("payload")).put("plan_revision",cached.getLong("plan_revision"));
        JSONArray goals=prefs.getJSONArray("goals");JSONObject constraints=prefs.getJSONObject("constraints");
        if(unified!=null){JSONObject result=unified.plan(state,goals,constraints).put("run_id",RUN).put("state_revision",state.getLong("revision"));
            if(!rulesVersion().equals(state.getString("rules_version")))return new JSONObject().put("plan",result).put("plan_revision",JSONObject.NULL);
            long revision=store.installPlan(RUN,state.getLong("revision"),rulesVersion(),result);return new JSONObject().put("plan",result).put("plan_revision",revision);}
        JSONObject issue=pack.preflight(state,goals);
        if(!pack.version.equals(state.getString("rules_version")))return new JSONObject().put("plan",issue).put("plan_revision",JSONObject.NULL);
        JSONObject result=issue;
        if(result==null){
            JSONObject initial=engineSnapshot(state);result=PlannerEngine.plan(pack.rules,initial,goals,constraints,128,20000);
            JSONObject replay=PlannerEngine.validatePlan(pack.rules,initial,result.getJSONArray("steps"),constraints);
            // Validation verifies every returned action, independently of search's forecast.
            result.put("replay_validated",true);
        }
        result.put("run_id",RUN).put("rules_version",pack.version).put("state_revision",state.getLong("revision"))
              .put("model_scope","partial_real_rules").put("optimality_proven",false).put("observed_state_mutated",false);
        long revision=store.installPlan(RUN,state.getLong("revision"),pack.version,result);
        return new JSONObject().put("plan",result).put("plan_revision",revision);
    }
    private JSONObject engineSnapshot(JSONObject observed)throws JSONException{
        JSONObject values=new JSONObject(),facts=observed.getJSONObject("facts");JSONArray fields=pack.rules.getJSONArray("fields");
        for(int i=0;i<fields.length();i++){String field=fields.getString(i);if(field.equals("cash")){
                Object cash=observed.opt("cash");values.put(field,new JSONObject().put("low",nullable(cash)).put("high",nullable(cash)));
            }else if(facts.has(field))values.put(field,new JSONObject(facts.getJSONObject(field).toString()));}
        return new JSONObject().put("run_id",RUN).put("rules_version",observed.getString("rules_version"))
            .put("revision",observed.getLong("revision")).put("slot",observed.getInt("game_slot"))
            .put("values",values).put("visits",observed.getJSONObject("visits_by_stage"));
    }
    public synchronized JSONObject request(String path,String body)throws Exception {
        if(path.equals("/api/state"))return state();
        if(path.equals("/api/plan")){if(state().isNull("snapshot"))return new JSONObject().put("plan",JSONObject.NULL);return replan();}
        if(path.equals("/api/export"))return store.exportRun(RUN);
        JSONObject data=new JSONObject(body);String command=data.getString("command_id");
        long expected=data.isNull("expected_revision")?0:data.getLong("expected_revision");JSONObject receipt;
        switch(path){
            case "/api/setup":
                JSONObject setup=data.getJSONObject("baseline");validateGameBaseline(setup);
                if(data.isNull("expected_revision"))return withReplan(store.createRunWithPreferences(RUN,command,setup,data.getJSONArray("goals"),data.getJSONObject("constraints")));
                return withReplan(store.reenterWithPreferences(RUN,command,expected,setup,data.getJSONArray("goals"),data.getJSONObject("constraints")));
            case "/api/baseline":
                JSONObject baseline=data.getJSONObject("baseline");validateGameBaseline(baseline);
                if(state().isNull("snapshot")){if(!data.isNull("expected_revision"))throw new IllegalArgumentException("No existing baseline");store.createRun(RUN,baseline);}
                receipt=store.reenter(RUN,command,expected,baseline);
                return new JSONObject().put("observation",receipt).put("replan_pending",true);
            case "/api/preferences":
                receipt=store.setPreferences(RUN,command,expected,data.getJSONArray("goals"),data.getJSONObject("constraints"));
                return withReplan(receipt);
            case "/api/observed_stats":
                return withReplan(store.recordObservedAction(RUN,command,expected,statObservation(data)));
            case "/api/observed_social":
                return withReplan(store.recordObservedAction(RUN,command,expected,socialObservation(data)));
            case "/api/return":
                data.remove("command_id");data.remove("expected_revision");
                receipt=store.recordDungeonReturn(RUN,command,expected,data);return withReplan(receipt);
            default:throw new IllegalArgumentException("Unknown command");
        }
    }
    private JSONObject statObservation(JSONObject data)throws Exception {
        JSONObject observed=data.getJSONObject("visible_ranks"),assignments=new JSONObject();if(observed.length()==0)throw new IllegalArgumentException("请填写至少一个实际属性等级");
        for(Iterator<String> it=observed.keys();it.hasNext();){String slug=it.next();JSONObject definition=null;for(int i=0;i<pack.statRules.length();i++)if(pack.statRules.getJSONObject(i).getString("statId").equals(slug))definition=pack.statRules.getJSONObject(i);
            if(definition==null)throw new IllegalArgumentException("Unknown social stat");Object raw=observed.get(slug);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<1||((Number)raw).longValue()>6)throw new IllegalArgumentException("属性等级须为 1–6");int rank=((Number)raw).intValue();JSONObject range=definition.getJSONArray("ranks").getJSONObject(rank-1);JSONObject value=new JSONObject().put("low",range.get("minInclusive")).put("high",range.opt("maxInclusive")==null?JSONObject.NULL:range.get("maxInclusive"));assignments.put("stats."+slug,value).put("socialStats."+slug+".raw",new JSONObject(value.toString())).put("socialStats."+slug+".rank",exact(rank));
        }
        JSONObject payload=new JSONObject().put("action_id","observed.stats").put("action_slot",data.getInt("action_slot")).put("next_game_slot",data.getInt("next_game_slot")).put("fact_assignments",assignments);
        payload.put("absolute_balance",data.has("absolute_balance")?data.get("absolute_balance"):JSONObject.NULL);
        for(String key:new String[]{"next_slot_capacity"})if(data.has(key))payload.put(key,data.get(key));return payload;
    }
    private JSONObject socialObservation(JSONObject data)throws Exception {
        String slug=data.getString("entity_id");if(!slug.matches("[a-z_]+"))throw new IllegalArgumentException("Invalid social link");
        boolean exists=false;org.json.JSONArray catalog=pack.catalog;
        for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getString("id").equals("p3r.social_link."+slug))exists=true;
        if(!exists)throw new IllegalArgumentException("Unknown social link");
        String actionId=data.getString("action_id");
        if(!actionId.equals("observed.social."+slug)&&!((slug.equals("sun")||slug.equals("devil"))&&actionId.equals("p3r.action.visit_"+slug)))throw new IllegalArgumentException("This is not a supported social observation action; dungeon visits require the return form");
        Object raw=data.get("observed_rank");if(!(raw instanceof Integer||raw instanceof Long))throw new IllegalArgumentException("Observed rank must be an integer");
        long rawRank=((Number)raw).longValue();if(rawRank<0||rawRank>10)throw new IllegalArgumentException("Observed rank must be 0–10");int rank=(int)rawRank;if(rank<0||rank>10)throw new IllegalArgumentException("Observed rank must be 0–10");
        JSONObject assignments=new JSONObject();String prefix="socialLinks."+slug+".";
        assignments.put(prefix+"rank",exact(rank)).put(prefix+"alreadyUnlocked",exact(rank>0?1:0));
        for(String key:new String[]{"relationship_normal","ready"})if(data.has(key)&&!data.isNull(key)&&!(data.get(key) instanceof Boolean))throw new IllegalArgumentException("Observed flags must be boolean or unknown");
        Object normal=data.opt("relationship_normal"),ready=data.opt("ready");
        if(!Boolean.TRUE.equals(normal)||rank==0||rank==10)ready=JSONObject.NULL;
        assignments.put(prefix+"normal",normal instanceof Boolean?exact((Boolean)normal?1:0):unknown());
        assignments.put(prefix+"ready",ready instanceof Boolean?exact((Boolean)ready?1:0):unknown());
        assignments.put(prefix+"readyRank",ready instanceof Boolean?exact(rank):unknown());
        assignments.put(prefix+"affinityLowerBound",unknown()).put(prefix+"affinityRank",unknown()).put(prefix+"affinityValid",unknown());
        JSONObject payload=new JSONObject().put("action_id",data.getString("action_id")).put("action_slot",data.getInt("action_slot"))
            .put("next_game_slot",data.getInt("next_game_slot")).put("fact_assignments",assignments);
        for(String key:new String[]{"absolute_balance","expected_plan_revision","action_instance_id","next_slot_capacity"})if(data.has(key))payload.put(key,data.get(key));
        return payload;
    }
    private static JSONObject exact(int value)throws JSONException{return new JSONObject().put("low",value).put("high",value);}
    private static JSONObject unknown()throws JSONException{return new JSONObject().put("low",JSONObject.NULL).put("high",JSONObject.NULL);}
    private void validateGameBaseline(JSONObject baseline)throws JSONException {
        JSONObject facts=baseline.getJSONObject("facts");
        for(Iterator<String> keys=facts.keys();keys.hasNext();){String key=keys.next();
            if(key.startsWith("socialLinks.")&&key.endsWith(".rank")){
                JSONObject interval=facts.getJSONObject(key);
                for(String bound:new String[]{"low","high"})if(!interval.isNull(bound)){
                    double rank=interval.getDouble(bound);if(rank<0||rank>10||rank!=Math.floor(rank))throw new IllegalArgumentException("社群等级必须为 0–10，未知值请留空");
                }
            }
        }
    }
    private JSONObject withReplan(JSONObject receipt)throws JSONException {
        JSONObject result=new JSONObject().put("observation",receipt);
        try{JSONObject planned=replan();boolean pending=planned.isNull("plan");return result.put("planning",planned).put("replan_pending",pending);}
        catch(Exception error){return result.put("planning",JSONObject.NULL).put("replan_pending",true).put("reason",error.getMessage());}
    }
    @Override public void close(){store.close();}
}
