package app.p3r.dayplanner.v2;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Offline pinned introduction adapter. No observed state is written.
 * Facts use canonical numeric intervals (socialLinks.*, socialStats.* and inventory.*).
 * context.unlock.<slug>.stage is an observed source stage string, never defaulted.
 * Optional date ordinals under the same object retain source field suffixes.
 * lastPossiblePrepOrdinal is a retained upper bound, never invented event history.
 * context.unlock.<slug>.observations contains flat source guard names. A current.*
 * flag or observedIntroductionAvailable here applies ONLY at the checkpoint slot.
 * calendar.unlockActionStates[slot][sourceRuleId] supplies exact-slot observations.
 * Zero/unknown-slot and embedded preparations are checkpoints, not invented 1-slot actions.
 */
public final class NativeUnlockRules {
    public static final String DEFAULT_ROOT="planner_v2/rules_v051/";
    public static final String MANIFEST_SHA256="4928f5b9435d98f194a61106540a3b311cc6b756d5700c7d335805f426e760c9";
    public final String version="p3r-unlock-0.5.1-"+MANIFEST_SHA256.substring(0,12)+"-a1";
    public final int firstSlot,horizon;
    private final JSONArray simple,multi,calendar,stats,rankModels;
    private final Set<Integer> locks=new HashSet<>();
    private final Map<String,Map<Integer,JSONObject>> dates=new TreeMap<>();
    private final Map<String,JSONObject> stageCodes=new TreeMap<>();
    private interface Reader { byte[] read(String name)throws Exception; }
    public NativeUnlockRules(Context context)throws Exception {this(context,DEFAULT_ROOT);}
    public NativeUnlockRules(Context context,String root)throws Exception {this(name->{try(InputStream in=context.getAssets().open(root+name)){return bytes(in);}});}
    public NativeUnlockRules(File directory)throws Exception {this(name->{File f=new File(directory,name);if(!f.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IllegalArgumentException("Invalid pack path");try(InputStream in=new FileInputStream(f)){return bytes(in);}});}
    private NativeUnlockRules(Reader reader)throws Exception {
        byte[] raw=reader.read("manifest.json");if(!sha(raw).equals(MANIFEST_SHA256))throw new IllegalArgumentException("Pinned 0.5.1 manifest mismatch");
        JSONObject manifest=parse(raw);if(!"0.5.1".equals(manifest.getString("packVersion"))||!"P3R-base".equals(manifest.getString("gameEdition")))throw new IllegalArgumentException("Wrong audited edition/version");
        Map<String,JSONObject> data=new HashMap<>();JSONObject files=manifest.getJSONObject("files");
        for(String name:new String[]{"simple_unlock_rules_candidate.json","multistep_unlock_rules_candidate.json","calendar_execution_extension.json","calendar_execution_contract.json","calendar_global_constraints_candidate.json","global_slot_locks_expansion_candidate.json","stats_actions.json","manual_link_models.json"}){byte[] content=reader.read(name);if(!sha(content).equals(files.getString(name)))throw new IllegalArgumentException("Checksum mismatch: "+name);data.put(name,parse(content));}
        simple=data.get("simple_unlock_rules_candidate.json").getJSONArray("actions");multi=data.get("multistep_unlock_rules_candidate.json").getJSONArray("models");calendar=data.get("calendar_execution_extension.json").getJSONArray("records");stats=data.get("stats_actions.json").getJSONArray("statRules");rankModels=data.get("manual_link_models.json").getJSONArray("entities");
        JSONObject window=data.get("calendar_execution_contract.json").getJSONObject("coverage").getJSONObject("calendarSourceWindow");firstSlot=window.getJSONObject("startInclusive").getInt("ordinal")*2;horizon=(window.getJSONObject("endInclusive").getInt("ordinal")+1)*2;
        for(int i=0;i<calendar.length();i++){JSONObject row=calendar.getJSONObject(i);String slug=slug(row.getString("entityId"));dates.computeIfAbsent(slug,k->new TreeMap<>()).put(row.getInt("slotIndex"),row);}
        JSONArray hard=data.get("calendar_global_constraints_candidate.json").getJSONArray("explicitActionLockClaims");for(int i=0;i<hard.length();i++){JSONObject row=hard.getJSONObject(i);addLocks(new JSONArray().put(row.getJSONObject("date")),row.getJSONArray("slots"));}
        JSONArray extra=data.get("global_slot_locks_expansion_candidate.json").getJSONArray("newWholeSlotLockClaims");for(int i=0;i<extra.length();i++){JSONObject row=extra.getJSONObject(i);if(row.optBoolean("globalOrdinarySlotLockClaim"))addLocks(row.getJSONArray("dates"),row.getJSONArray("slots"));}
        // Dungeon-only restrictions deliberately do not enter this set.
        JSONArray exclusions=data.get("calendar_execution_contract.json").getJSONArray("safeHardExclusions");for(int i=0;i<exclusions.length();i++)if("mandatory_Aug16_festival".equals(exclusions.getJSONObject(i).optString("kind"))){locks.add(274);locks.add(275);}
        for(int i=0;i<multi.length();i++){JSONObject model=multi.getJSONObject(i),codes=new JSONObject();JSONArray transitions=model.getJSONArray("transitions");for(int j=0;j<transitions.length();j++){JSONObject t=transitions.getJSONObject(j);for(String side:new String[]{"fromStage","toStage"}){String stage=t.getString(side);if(!codes.has(stage))codes.put(stage,codes.length());}}stageCodes.put(slug(model.getString("entityId")),codes);}
    }

    public JSONObject build(JSONObject observed,JSONObject context,JSONObject policy)throws Exception {
        if(context==null)context=new JSONObject();if(policy==null)policy=new JSONObject();int start=integer(observed.get("game_slot"));
        JSONObject values=copy(observed.getJSONObject("facts")),derived=new JSONObject(),metadata=new JSONObject(),labels=new JSONObject(),dependencies=new JSONObject();
        JSONArray actions=new JSONArray(),diagnostics=new JSONArray(),checks=new JSONArray(),supported=new JSONArray();Set<String> fields=new TreeSet<>();
        deriveStats(values,derived);Object cash=observed.opt("cash");values.put("cash",interval(nil(cash)?null:integer(cash),nil(cash)?null:integer(cash)));
        boolean valid="P3R-base".equals(context.optString("gameEdition"))&&Boolean.TRUE.equals(path(context,"checkpoint.consistencyValidated"))&&path(context,"checkpoint.date.ordinal") instanceof Number&&integer(path(context,"checkpoint.date.ordinal"))==start/2&&start>=firstSlot&&start<horizon;
        if(!valid)diagnostics.put(diag("checkpoint_context_unknown",null,"当前版本、日期与存档一致性需要核对"));
        for(int i=0;i<simple.length();i++){
            JSONObject source=simple.getJSONObject(i);String entity=slug(source.getString("entityId"));supported.put(field(entity,"rank"));dependencies.put(field(entity,"rank"),simpleDependencies(source));
            if(!locked(entity,values,derived,diagnostics))continue;
            JSONObject requirements=new JSONObject(),maximums=new JSONObject();JSONArray missing=new JSONArray();
            boolean guards=compileSimpleGuards(source,entity,values,context,requirements,maximums,missing);
            JSONArray eligible=new JSONArray(),sources=new JSONArray(),assumptions=new JSONArray().put("A.complete_introduction_event_resolution");
            for(int slot=Math.max(start,firstSlot);valid&&guards&&slot<horizon;slot++){
                if(!period(source,slot))continue;JSONObject observations=observations(context,entity,source.getString("ruleId"),slot,start);
                Object offered=observations.opt("observedIntroductionAvailable");if(Boolean.FALSE.equals(offered))continue;
                boolean witnessed=introWitness(source,slot),positive=dates.getOrDefault(entity,Collections.emptyMap()).containsKey(slot),observedHere=Boolean.TRUE.equals(offered);
                if(!positive&&!witnessed&&!observedHere)continue;
                JSONObject earliest=source.optJSONObject("earliestDate");if(earliest!=null&&slot/2<earliest.getInt("ordinal")&&!observedHere)continue;
                if(source.has("unresolvedExtraCondition")&&!observedHere&&!Boolean.TRUE.equals(path(policy,"unlock_assumptions.empress_wiki_first_gate"))){addUnique(missing,"empress.observedIntroductionAvailable_or_explicit_wiki_first_assumption");continue;}
                if(!calendarAllows(entity,slot,start,context,observedHere,assumptions))continue;
                eligible.put(slot);sources.put(new JSONObject().put("slot",slot).put("basis",observedHere?"observed_introduction_available":witnessed?"exact_intro_source_witness":"exact_source_positive_O_cell").put("source_rule_id",positive?dates.get(entity).get(slot).getString("sourceRuleId"):source.getString("ruleId")));
            }
            if(eligible.length()>0){JSONObject action=opening(source.getString("ruleId"),entity,eligible,requirements,maximums,new JSONObject());actions.put(action);metadata.put(action.getString("id"),meta(source,entity,sources,assumptions));}
            else addUnique(missing,"supported_introduction_date_or_current_availability");
            if(missing.length()>0){diagnostics.put(diag("introduction_prerequisites_or_availability_needed",entity,"解锁需要下列当前条件；缺少规则不表示无法完成").put("guards",missing));checks.put(check(entity,"unlock_prerequisites",source.getString("ruleId"),"核对当前解锁条件后重新规划").put("missing_guards",missing));}
        }
        for(int i=0;i<multi.length();i++){
            JSONObject model=multi.getJSONObject(i);String entity=slug(model.getString("entityId"));supported.put(field(entity,"rank"));dependencies.put(field(entity,"rank"),multiDependencies(model,policy));
            if(!locked(entity,values,derived,diagnostics))continue;
            JSONObject prep=object(path(context,"unlock."+entity)),codes=stageCodes.get(entity);String state=prep==null||prep.isNull("stage")?null:prep.optString("stage",null);String stageField="unlock."+entity+".stageCode";
            Long numeric=exact(values,stageField);if(state==null&&numeric!=null)for(Iterator<String> it=codes.keys();it.hasNext();){String key=it.next();if(codes.getLong(key)==numeric)state=key;}
            if(state==null){checks.put(check(entity,"preparation_state_unknown",null,"请确认当前准备进度；未知不会当作尚未开始"));diagnostics.put(diag("preparation_state_unknown",entity,"未推断旧准备、付款或道具消耗"));continue;}
            if(!codes.has(state)||"unlocked".equals(state)||numeric!=null&&numeric!=codes.getLong(state)){diagnostics.put(diag("preparation_state_conflict",entity,"准备进度与当前等级或记录冲突，请核对"));continue;}
            values.put(stageField,interval(codes.getInt(state),codes.getInt(state)));derived.put(stageField,"observed_preparation_stage_"+state);
            JSONArray transitions=model.getJSONArray("transitions");boolean available=false;
            for(int t=0;t<transitions.length();t++){
                JSONObject transition=transitions.getJSONObject(t);if(!state.equals(transition.getString("fromStage")))continue;String id=transition.getString("ruleId");
                if(transition.isNull("slotCost")||transition.getInt("slotCost")!=1||transition.optBoolean("requiresParentActionAlreadyConsumedSlot")){
                    JSONObject checkpoint=check(entity,transition.isNull("slotCost")?"preparation_slot_cost_unresolved":"zero_slot_or_embedded_preparation",id,"先确认这一步实际完成，再以新的准备状态重新规划").put("source_slot_cost",transition.get("slotCost")).put("money_cost_jpy",transition.getInt("moneyCostJpy")).put("item_costs",copyArray(transition.getJSONArray("itemCosts"))).put("from_stage",state).put("to_stage",transition.getString("toStage")).put("requires_parent_action",transition.optBoolean("requiresParentActionAlreadyConsumedSlot")).put("provenance",provenance(transition));checks.put(checkpoint);continue;
                }
                JSONArray eligible=new JSONArray(),missing=new JSONArray(),sources=new JSONArray(),assumptions=new JSONArray().put("A.complete_introduction_event_resolution");JSONObject requirements=new JSONObject().put(field(entity,"rank"),0).put(stageField,codes.getInt(state)),maximums=copy(requirements),costs=new JSONObject();
                if(transition.getInt("moneyCostJpy")>0)costs.put("cash",transition.getInt("moneyCostJpy"));JSONArray items=transition.getJSONArray("itemCosts");for(int j=0;j<items.length();j++){JSONObject item=items.getJSONObject(j);costs.put(itemField(item.getString("itemId")),item.getInt("quantity"));}
                for(int slot=Math.max(start,firstSlot);valid&&slot<horizon;slot++){
                    if(!period(transition,slot))continue;JSONObject at=observations(context,entity,id,slot,start),req=copy(requirements),max=copy(maximums);boolean passes=true;
                    JSONArray guards=transition.getJSONArray("guards");for(int g=0;g<guards.length();g++)if(!compileGuard(guards.getJSONObject(g),entity,slot,start,prep,at,context,policy,req,max,missing))passes=false;
                    if(!passes||!calendarAllows(entity,slot,start,context,Boolean.TRUE.equals(at.opt("observedIntroductionAvailable")),assumptions))continue;
                    eligible.put(slot);requirements=req;maximums=max;sources.put(new JSONObject().put("slot",slot).put("basis","exact_slot_guards_and_observed_preparation"));
                }
                if(eligible.length()>0){JSONObject action=opening(id,entity,eligible,requirements,maximums,costs);action.getJSONObject("assignments").put(stageField,interval(codes.getInt("unlocked"),codes.getInt("unlocked")));actions.put(action);metadata.put(id,meta(transition,entity,sources,assumptions).put("from_stage",state).put("to_stage","unlocked").put("money_cost_jpy",transition.getInt("moneyCostJpy")).put("item_costs",items));available=true;}
                if(missing.length()>0)diagnostics.put(diag("preparation_guards_unknown_or_false",entity,"当前准备已保留，剩余步骤仍需确认条件").put("source_rule_id",id).put("guards",missing));
            }
            if(!available)checks.put(check(entity,"next_preparation_observation",null,"保留已完成的准备；确认当前可进行的下一步，不重复付款或交道具").put("stage",state));
        }
        for(Iterator<String> it=values.keys();it.hasNext();)fields.add(it.next());
        for(int i=0;i<actions.length();i++){JSONObject action=actions.getJSONObject(i);for(String bucket:new String[]{"requirements","maximums","assignments","costs"}){JSONObject map=action.optJSONObject(bucket);if(map!=null)for(Iterator<String> it=map.keys();it.hasNext();)fields.add(it.next());}JSONArray forget=action.optJSONArray("forget_fields");if(forget!=null)for(int j=0;j<forget.length();j++)fields.add(forget.getString(j));}
        for(int i=0;i<supported.length();i++)fields.add(supported.getString(i));
        for(int slot=Math.max(start,firstSlot);slot<horizon;slot++)labels.put(""+slot,new JSONObject().put("date",String.format(Locale.ROOT,"%02d-%02d",GameDate.date(slot).getMonthValue(),GameDate.date(slot).getDayOfMonth())).put("period",slot%2==0?"daytime":"evening"));
        JSONObject rules=new JSONObject().put("version",version).put("horizon",horizon).put("fields",new JSONArray(fields)).put("actions",actions).put("coverage_complete",false).put("synthetic",false);
        JSONObject snapshot=new JSONObject().put("run_id",observed.getString("run_id")).put("rules_version",version).put("revision",observed.getLong("revision")).put("slot",start).put("values",values).put("visits",observed.optJSONObject("visits_by_stage")==null?new JSONObject():copy(observed.getJSONObject("visits_by_stage")));
        return new JSONObject().put("status",valid?"conditional":"unknown").put("rules",rules).put("engine_snapshot",snapshot).put("action_metadata",metadata).put("labels",labels).put("supported_goal_fields",supported).put("goal_dependencies",dependencies).put("diagnostics",diagnostics).put("observation_checkpoints",checks).put("derived_checkpoint_facts",derived).put("stage_code_map",new JSONObject(stageCodes)).put("observed_state_mutated",false).put("scope","bounded_introductions_and_observed_final_preparations");
    }

    private boolean locked(String entity,JSONObject values,JSONObject derived,JSONArray diagnostics)throws Exception {
        Long rank=exact(values,field(entity,"rank"));if(rank==null||rank!=0)return false;Long unlocked=exact(values,field(entity,"alreadyUnlocked"));
        if(unlocked!=null&&unlocked!=0){diagnostics.put(diag("rank_unlock_conflict",entity,"等级0与已解锁标记冲突"));return false;}
        if(unlocked==null){values.put(field(entity,"alreadyUnlocked"),interval(0,0));derived.put(field(entity,"alreadyUnlocked"),"observed_rank_zero_only_not_unstarted_preparation");}return true;
    }
    private boolean compileSimpleGuards(JSONObject source,String entity,JSONObject values,JSONObject context,JSONObject req,JSONObject max,JSONArray missing)throws Exception {
        boolean valid=true;JSONArray guards=source.getJSONArray("preconditions");
        for(int i=0;i<guards.length();i++){JSONObject guard=guards.getJSONObject(i);String key=guard.getString("field"),op=guard.getString("op");if(key.equals("calendar.currentSlotRemaining"))continue;String mapped=canonical(key);Object wanted=guard.get("value");
            if(key.equals("socialLinks.temperance.metBebe")){
                // The pinned rule proves the meeting is mandatory in completed Hierophant3.
                Long explicit=exact(values,mapped);Object observation=flatObservation(context,entity,key);
                if(Long.valueOf(0).equals(explicit)||Boolean.FALSE.equals(observation)){valid=false;addUnique(missing,key);continue;}
                req.put(field("hierophant","rank"),3);continue;
            }
            if(key.equals("inventory.onlineGameAvailable")){Long known=exact(values,mapped);Object observation=flatObservation(context,entity,key);if(known==null&&observation instanceof Boolean){values.put(mapped,interval((Boolean)observation?1:0,(Boolean)observation?1:0));known=(Boolean)observation?1L:0L;}if(!Long.valueOf(1).equals(known)){valid=false;addUnique(missing,key);continue;}}
            long value=wanted instanceof Boolean?((Boolean)wanted?1:0):integer(wanted);req.put(mapped,value);if(op.equals("eq"))max.put(mapped,value);else if(!op.equals("gte"))throw new IllegalArgumentException("Unmapped simple guard");
        }return valid;
    }
    private boolean compileGuard(JSONObject guard,String entity,int slot,int start,JSONObject prep,JSONObject at,JSONObject context,JSONObject policy,JSONObject req,JSONObject max,JSONArray missing)throws Exception {
        String type=guard.getString("type"),key;switch(type){
            case "link_rank_equals":case "link_rank_at_least":key=field(slug(guard.getString("entityId")),"rank");req.put(key,guard.getInt("value"));if(type.equals("link_rank_equals"))max.put(key,guard.getInt("value"));return true;
            case "stat_rank_at_least":req.put("socialStats."+guard.getString("statId")+".raw",rawTarget(guard.getString("statId"),guard.getInt("value")));return true;
            case "money_at_least":req.put("cash",guard.getInt("amountJpy"));return true;
            case "inventory_at_least":req.put(itemField(guard.getString("itemId")),guard.getInt("quantity"));return true;
            case "flag_true":key=guard.getString("flagId");if(Boolean.TRUE.equals(at.opt(key)))return true;addUnique(missing,key);return false;
            case "iso_weekday_in":return contains(guard.getJSONArray("values"),GameDate.date(slot).getDayOfWeek().getValue());
            case "month_day_at_least_cycle":String[] date=guard.getString("value").split("-");return slot>=GameDate.slot(Integer.parseInt(date[0]),Integer.parseInt(date[1]),false);
            case "date_strictly_after":key=guard.getString("dateField");String suffix=key.substring(key.lastIndexOf('.')+1);Object last=prep==null?null:prep.opt(suffix);Object safeBound=prep==null?null:prep.opt("lastPossiblePrepOrdinal");int bound=!nil(last)?integer(last):!nil(safeBound)?integer(safeBound):start/2;if(bound<0||bound>start/2)throw new IllegalArgumentException("Preparation date is outside observed history");return slot/2>bound;
            case "any_of":JSONArray choices=guard.getJSONArray("conditions");for(int i=0;i<choices.length();i++){JSONObject r=copy(req),m=copy(max);if(compileGuard(choices.getJSONObject(i),entity,slot,start,prep,at,context,policy,r,m,new JSONArray())){merge(req,r);merge(max,m);return true;}}addUnique(missing,"unresolved_optional_source_envelope_or_observed_gate");return false;
            case "opt_in_sufficient_source_envelope":key=guard.getString("envelopeId");if(!Boolean.TRUE.equals(path(policy,"unlock_assumptions."+key)))return false;JSONArray conditions=guard.getJSONArray("conditions");for(int i=0;i<conditions.length();i++)if(!compileGuard(conditions.getJSONObject(i),entity,slot,start,prep,at,context,policy,req,max,missing))return false;return true;
            default:addUnique(missing,"unmapped_guard:"+type);return false;
        }
    }
    private JSONObject opening(String id,String entity,JSONArray slots,JSONObject req,JSONObject max,JSONObject costs)throws Exception {
        JSONObject assigns=new JSONObject();for(String suffix:new String[]{"rank","normal","alreadyUnlocked","affinityRank","affinityValid"})assigns.put(field(entity,suffix),interval(1,1));assigns.put(field(entity,"affinityLowerBound"),interval(0,null));
        JSONArray forget=new JSONArray().put(field(entity,"affinityExact"));boolean zero=zeroRankTwo(entity);if(zero){assigns.put(field(entity,"ready"),interval(1,1));assigns.put(field(entity,"readyRank"),interval(1,1));}else forget.put(field(entity,"ready")).put(field(entity,"readyRank"));
        return new JSONObject().put("id",id).put("slots",slots).put("requirements",req).put("maximums",max).put("costs",costs).put("assignments",assigns).put("forget_fields",forget).put("strict_requirements",true).put("verification","corroborated").put("source",new JSONObject().put("manifest_sha256",MANIFEST_SHA256).put("rule_id",id).toString());
    }
    private boolean zeroRankTwo(String entity)throws Exception {for(int i=0;i<rankModels.length();i++){JSONObject model=rankModels.getJSONObject(i);if(!slug(model.getString("entity_id")).equals(entity))continue;JSONArray gates=model.getJSONArray("incoming_thresholds");for(int j=0;j<gates.length();j++){JSONObject gate=gates.getJSONObject(j);if(gate.getInt("to_rank")==2&&gate.optBoolean("executable_sufficiency_check")&&!gate.isNull("exact_raw_threshold")&&gate.getInt("exact_raw_threshold")==0)return true;}}return false;}
    private boolean calendarAllows(String entity,int slot,int start,JSONObject context,boolean observedHere,JSONArray assumptions)throws Exception {
        if(locks.contains(slot)||Boolean.TRUE.equals(path(context,"socialLinks."+entity+".permanentlyUnavailable")))return false;
        JSONObject state=object(path(context,"calendar.slotStates."+slot));Object capacity=state==null?null:state.opt("remainingCapacity"),lock=state==null?null:state.opt("knownStoryLock");
        if(!nil(capacity)&&integer(capacity)<1||Boolean.TRUE.equals(lock)||slot==start&&nil(capacity))return false;
        if(!Boolean.FALSE.equals(lock))addUnique(assumptions,"A.calendar_context_complete_enough");
        Object absent=path(context,"calendar.entityDateStates."+entity+"."+(slot/2)+".knownUnavailable");if(Boolean.TRUE.equals(absent)||absent==JSONObject.NULL)return false;
        JSONObject row=dates.getOrDefault(entity,Collections.emptyMap()).get(slot);if(row==null)return true;
        JSONArray special=row.optJSONArray("knownSpecialGuardIds");if(special!=null)for(int i=0;i<special.length();i++){String guard=special.getString(i);if(guard.equals("christmas_all_invitations_declined")){if(!Boolean.TRUE.equals(path(context,"storyFlags.christmasAllInvitationsDeclined")))return false;}else if(guard.equals("jan21_conditional_story_event_resolved")){if(!(path(context,"storyFlags.junpeiWhiteFlowersGiven") instanceof Boolean)||!Boolean.FALSE.equals(lock))return false;}else return false;}
        JSONArray extra=row.optJSONArray("additionalGuardIds");if(extra!=null)for(int i=0;i<extra.length();i++){if(!extra.getString(i).equals("person_confirmed_available_after_rescue_window"))return false;Object rescued=path(context,"socialLinks."+entity+".rescuedAtOrdinal");boolean known=!nil(rescued)&&integer(rescued)<=start/2&&integer(rescued)<slot/2;if(!known&&!(observedHere&&slot==start))return false;}
        return true;
    }
    private JSONObject observations(JSONObject context,String entity,String id,int slot,int start)throws Exception {
        JSONObject out=new JSONObject(),persistent=object(path(context,"unlock."+entity+".observations"));if(persistent!=null)for(Iterator<String> it=persistent.keys();it.hasNext();){String key=it.next();if(slot==start||!key.startsWith("current.")&&!key.startsWith("calendar.")&&!key.equals("observedIntroductionAvailable"))out.put(key,persistent.get(key));}
        JSONObject exact=object(path(context,"calendar.unlockActionStates."+slot));exact=exact==null?null:exact.optJSONObject(id);if(exact!=null)merge(out,exact);return out;
    }
    private Object flatObservation(JSONObject context,String entity,String key){JSONObject o=object(path(context,"unlock."+entity+".observations"));return o==null?null:o.opt(key);}
    private boolean introWitness(JSONObject source,int slot)throws Exception {JSONArray evidence=source.getJSONArray("evidence");for(int i=0;i<evidence.length();i++){JSONObject e=evidence.getJSONObject(i);if(e.has("monthClaim")&&e.has("dayClaim")&&slot==GameDate.slot(e.getInt("monthClaim"),e.getInt("dayClaim"),"evening".equals(e.optString("slot"))))return true;}return false;}
    private JSONObject meta(JSONObject source,String entity,JSONArray dates,JSONArray assumptions)throws Exception {return new JSONObject().put("entity",entity).put("display_title",source.optString("labelZh",name(entity)+" · 完成解锁" )).put("from_rank",0).put("to_rank",1).put("source_rule_id",source.getString("ruleId")).put("slot_cost",1).put("provenance",provenance(source)).put("calendar_sources",dates).put("assumptions",assumptions).put("requires_actual_completion_confirmation",true).put("requires_confirmed_quiz_success",source.optBoolean("requiresConfirmedQuizSuccess")).put("exact_affinity_balance_claimed",false).put("source_ordinals_are_game_instructions",false);}
    private JSONObject provenance(JSONObject source)throws Exception{return new JSONObject().put("manifest_sha256",MANIFEST_SHA256).put("source_rule_id",source.getString("ruleId")).put("evidence",source.optJSONArray("evidence")==null?new JSONArray():copyArray(source.getJSONArray("evidence")));}
    private JSONArray simpleDependencies(JSONObject source)throws Exception{JSONArray out=new JSONArray(),guards=source.getJSONArray("preconditions");for(int i=0;i<guards.length();i++){JSONObject g=guards.getJSONObject(i);String field=g.getString("field");if(g.getString("op").equals("gte")&&(field.startsWith("stats.")||field.startsWith("socialLinks.")))out.put(new JSONObject().put("field",canonical(field)).put("target",g.getInt("value")));}return out;}
    private JSONArray multiDependencies(JSONObject model,JSONObject policy)throws Exception{JSONObject targets=new JSONObject();JSONArray transitions=model.getJSONArray("transitions");for(int i=0;i<transitions.length();i++)collectDependencies(transitions.getJSONObject(i).getJSONArray("guards"),policy,targets);JSONArray out=new JSONArray();for(Iterator<String> it=targets.keys();it.hasNext();){String key=it.next();out.put(new JSONObject().put("field",key).put("target",targets.get(key)));}return out;}
    private void collectDependencies(JSONArray guards,JSONObject policy,JSONObject targets)throws Exception {for(int i=0;i<guards.length();i++){JSONObject g=guards.getJSONObject(i);String type=g.getString("type"),key=null;long value=0;if(type.equals("stat_rank_at_least")){key="socialStats."+g.getString("statId")+".raw";value=rawTarget(g.getString("statId"),g.getInt("value"));}else if(type.equals("link_rank_at_least")){key=field(slug(g.getString("entityId")),"rank");value=g.getInt("value");}else if(type.equals("any_of"))collectDependencies(g.getJSONArray("conditions"),policy,targets);else if(type.equals("opt_in_sufficient_source_envelope")&&Boolean.TRUE.equals(path(policy,"unlock_assumptions."+g.getString("envelopeId"))))collectDependencies(g.getJSONArray("conditions"),policy,targets);if(key!=null)targets.put(key,Math.max(value,targets.optLong(key,0)));}}
    private long rawTarget(String stat,int rank)throws Exception{if(rank<1||rank>6)throw new IllegalArgumentException("Invalid stat rank");for(int i=0;i<stats.length();i++){JSONObject row=stats.getJSONObject(i);if(stat.equals(row.getString("statId")))return row.getJSONArray("rankThresholds").getLong(rank-1);}throw new IllegalArgumentException("Unknown stat");}
    private void deriveStats(JSONObject values,JSONObject derived)throws Exception{for(String stat:new String[]{"academics","charm","courage"}){String raw="socialStats."+stat+".raw";JSONObject v=values.optJSONObject(raw);Long lo=bound(v,"low"),hi=bound(v,"high"),rank=exact(values,"socialStats."+stat+".rank");if(rank!=null){long minimum=rawTarget(stat,rank.intValue());lo=lo==null?minimum:Math.max(lo,minimum);if(rank<6){long maximum=rawTarget(stat,rank.intValue()+1)-1;hi=hi==null?maximum:Math.min(hi,maximum);}if(hi!=null&&lo>hi)throw new IllegalArgumentException("Visible stat/raw conflict");values.put(raw,interval(lo,hi));derived.put(raw,"visible_rank_threshold_interval_not_exact_hidden_points");}}}
    private void addLocks(JSONArray dates,JSONArray periods)throws Exception{for(int d=0;d<dates.length();d++)for(int p=0;p<periods.length();p++)locks.add(dates.getJSONObject(d).getInt("ordinal")*2+(periods.getString(p).equals("evening")?1:0));}
    private static boolean period(JSONObject source,int slot)throws Exception{return source.getString("slot").equals(slot%2==0?"daytime":"evening");}
    private static String canonical(String key){if(key.startsWith("stats.")&&key.endsWith(".rawLowerBound"))return "socialStats."+key.split("\\.")[1]+".raw";return key;}
    private static String field(String entity,String suffix){return "socialLinks."+entity+"."+suffix;}
    private static String itemField(String id){return "inventory."+id.substring("p3r.item.".length());}
    private static String slug(String id){return id.substring(id.lastIndexOf('.')+1);}
    private static JSONObject check(String entity,String kind,String rule,String message)throws Exception{return new JSONObject().put("entity",entity).put("at_rank",0).put("kind",kind).put("source_rule_id",rule==null?JSONObject.NULL:rule).put("message",message).put("predicted_rank_delta",JSONObject.NULL).put("requires_observation",true);}
    private static JSONObject diag(String code,String entity,String message)throws Exception{return new JSONObject().put("code",code).put("entity",entity==null?JSONObject.NULL:entity).put("message",message);}
    private static JSONObject object(Object value){return value instanceof JSONObject?(JSONObject)value:null;}
    private static Object path(JSONObject object,String path){Object at=object;for(String key:path.split("\\.")){if(!(at instanceof JSONObject))return null;at=((JSONObject)at).opt(key);}return at;}
    private static boolean nil(Object value){return value==null||value==JSONObject.NULL;}
    private static Long bound(JSONObject value,String key)throws Exception{return value==null||value.isNull(key)?null:(long)integer(value.get(key));}
    private static Long exact(JSONObject values,String key)throws Exception{JSONObject value=values.optJSONObject(key);Long low=bound(value,"low"),high=bound(value,"high");return low!=null&&low.equals(high)?low:null;}
    private static int integer(Object value){if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<Integer.MIN_VALUE||((Number)value).longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException("Expected integer");return ((Number)value).intValue();}
    private static JSONObject interval(Object low,Object high)throws Exception{return new JSONObject().put("low",low==null?JSONObject.NULL:low).put("high",high==null?JSONObject.NULL:high);}
    private static void merge(JSONObject target,JSONObject source)throws Exception{for(Iterator<String> it=source.keys();it.hasNext();){String key=it.next();target.put(key,source.get(key));}}
    private static void addUnique(JSONArray array,String value)throws Exception{if(!contains(array,value))array.put(value);}
    private static boolean contains(JSONArray array,Object value)throws Exception{for(int i=0;i<array.length();i++)if(Objects.equals(array.get(i),value))return true;return false;}
    private static JSONObject copy(JSONObject o)throws Exception{return new JSONObject(o.toString());}
    private static JSONArray copyArray(JSONArray o)throws Exception{return new JSONArray(o.toString());}
    private static JSONObject parse(byte[] b)throws Exception{return new JSONObject(new String(b,StandardCharsets.UTF_8));}
    private static byte[] bytes(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}
    private static String sha(byte[] bytes)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}

    /** Small current-state form. Strings are factual paraphrases, never game-answer translations. */
    public JSONObject preparationFormSchema()throws Exception {
        JSONObject out=new JSONObject();
        for(int i=0;i<simple.length();i++){JSONObject source=simple.getJSONObject(i);String entity=slug(source.getString("entityId"));out.put(entity,new JSONObject().put("label",name(entity)).put("stage_options",new JSONArray()).put("observations",new JSONArray()));}
        for(int i=0;i<multi.length();i++){JSONObject model=multi.getJSONObject(i);String entity=slug(model.getString("entityId"));JSONArray options=new JSONArray().put(new JSONObject().put("value",JSONObject.NULL).put("label","未知 / 不确定"));JSONObject codes=stageCodes.get(entity);List<String> ordered=new ArrayList<>();for(Iterator<String> it=codes.keys();it.hasNext();)ordered.add(it.next());ordered.sort(Comparator.comparingInt(k->codes.optInt(k)));
            for(String stage:ordered)if(!stage.equals("unlocked"))options.put(new JSONObject().put("value",stage).put("label",stageLabel(entity,stage)));
            out.put(entity,new JSONObject().put("label",model.getString("nameZhCn")).put("stage_path","unlock."+entity+".stage").put("stage_options",options).put("observations",new JSONArray()));}
        for(Iterator<String> it=out.keys();it.hasNext();){String entity=it.next();addInput(out,entity,"observedIntroductionAvailable","当前时段已确认可进行解锁互动","boolean","current_slot_only");}
        addInput(out,"hermit","inventory.onlineGameAvailable","已拿到并可使用网络游戏","boolean","persistent_observation");
        addInput(out,"temperance","socialLinks.temperance.metBebe","已在教皇社群事件中认识贝贝","boolean","persistent_observation");
        for(String[] input:new String[][]{{"justice","current.justice.introduction_interaction_available","当前可进行千寻的下一次准备对话"},{"hanged_man","current.hanged_man.rank1_interaction_available","当前可与舞子玩耍并建立社群"},{"hanged_man","calendar.hanged_man.exact_date_legal","已确认当前日期可以完成这次互动"},{"devil","current.devil.payment_interaction_available","当前可进行田中要求的下一次付款"},{"tower","current.tower.opening_interaction_available","当前可开始和尚的完整解锁互动"},{"tower","calendar.tower.exact_date_legal","已确认当前日期可以完成这次互动"},{"tower","tower.courage_gate_passed_observed","已实际通过和尚的勇气要求"},{"moon","current.moon.opening_interaction_available","当前可进行美食问答和交食物的完整互动"},{"sun","current.sun.rank1_interaction_available","当前可归还钢笔并建立社群"},{"sun","calendar.sun.exact_date_legal","已确认当前日期可以完成这次互动"}})addInput(out,input[0],input[1],input[2],"boolean",input[1].startsWith("current.")||input[1].startsWith("calendar.")?"current_slot_only":"persistent_observation");
        for(String[] item:new String[][]{{"moon","odd_morsel","神秘食品的当前数量"},{"sun","red_fountain_pen","红色钢笔的当前数量"}})out.getJSONObject(item[0]).getJSONArray("observations").put(new JSONObject().put("key","inventory."+item[1]).put("label",item[2]).put("type","integer_interval_fact").put("scope","observed_inventory").put("fact_field","inventory."+item[1]));
        return out;
    }
    private static void addInput(JSONObject schema,String entity,String key,String label,String type,String scope)throws Exception{schema.getJSONObject(entity).getJSONArray("observations").put(new JSONObject().put("key",key).put("label",label).put("type",type).put("scope",scope).put("context_path","unlock."+entity+".observations").put("default",JSONObject.NULL));}
    private static String stageLabel(String entity,String stage){switch(stage){case "unstarted":return "已确认尚未开始准备";case "conversation1":return "已完成第1次准备对话";case "conversation2":return "已完成第2次准备对话";case "conversation3":return "已完成第3次准备对话（邀约已接受）";case "invited_once":return "已邀请结子放学同行1次";case "food_given":return "已交食物，尚未交饮料";case "promised_return":return "已交食物和饮料，并约好再来";case "paid1":return "已付第1笔2万日元";case "paid2":return "已付前2笔（合计3万日元）";case "rumor_heard":return entity.equals("tower")?"已从结子处听说和尚":"已从健二处听说美食家";case "pen_requested":return "已听到寻找钢笔的请求";case "pen_obtained":return "已找回钢笔，尚未归还";default:return stage;}}
    private static String name(String entity){switch(entity){case "priestess":return "女教皇";case "empress":return "女皇";case "emperor":return "皇帝";case "hierophant":return "教皇";case "lovers":return "恋爱";case "chariot":return "战车";case "hermit":return "隐者";case "fortune":return "命运";case "temperance":return "节制";case "star":return "星";case "aeon":return "永劫";case "justice":return "正义";case "strength":return "力量";case "hanged_man":return "倒悬者";case "devil":return "恶魔";case "tower":return "塔";case "sun":return "太阳";case "moon":return "月亮";default:return entity;}}
}
