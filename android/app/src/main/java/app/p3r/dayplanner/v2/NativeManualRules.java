package app.p3r.dayplanner.v2;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Pinned, normalized 17-link conditional source adapter. No observed state is written.
 * Only a readiness-supported prefix is compiled: an unresolved gate becomes an
 * observation checkpoint, never a speculative chain of repeated rank gains.
 */
public final class NativeManualRules {
    public static final String DEFAULT_ROOT="planner_v2/rules_v04/";
    public static final String MANIFEST_SHA256="5c6c350e7cd88503b1c1002ba63fcbde0a120493e2c3c26be7d4e795d87db2d1";
    public static final String ADAPTER_REVISION="a4", SIMULATOR_REVISION=PlannerEngine.SIMULATOR_REVISION;
    public final String version="p3r-manual-0.4.0-"+MANIFEST_SHA256.substring(0,12)+"-"+ADAPTER_REVISION+"-"+SIMULATOR_REVISION;
    public final int firstSlot,horizon;
    private final JSONObject calendar,global,registry,extension;
    private final Map<String,JSONObject> models=new java.util.TreeMap<>();
    private final Set<Integer> hardLocks=new HashSet<>();
    private final Set<String> eventIds=new HashSet<>();
    private interface Reader {byte[] read(String filename)throws Exception;}

    public NativeManualRules(Context context)throws Exception {this(context,DEFAULT_ROOT);}
    public NativeManualRules(Context context,String assetRoot)throws Exception {
        this(name->{try(InputStream in=context.getAssets().open(assetRoot+name)){return bytes(in);}});
    }
    public NativeManualRules(File directory)throws Exception {
        this(name->{File file=new File(directory,name);if(!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IllegalArgumentException("Invalid pack path");try(InputStream in=new FileInputStream(file)){return bytes(in);}});
    }
    private NativeManualRules(Reader reader)throws Exception {
        byte[] manifestBytes=reader.read("manifest.json");
        if(!sha(manifestBytes).equals(MANIFEST_SHA256))throw new IllegalArgumentException("Pinned 0.4.0 manifest checksum mismatch");
        JSONObject manifest=parse(manifestBytes);
        if(!"0.4.0".equals(manifest.getString("packVersion"))||!"P3R-base".equals(manifest.getString("gameEdition")))throw new IllegalArgumentException("Wrong audited pack edition/version");
        JSONObject files=manifest.getJSONObject("files");Map<String,JSONObject> verified=new HashMap<>();
        for(Iterator<String> it=files.keys();it.hasNext();){String name=it.next();if(!name.matches("[a-zA-Z0-9_.-]+\\.json"))throw new IllegalArgumentException("Invalid manifest filename");byte[] content=reader.read(name);if(!sha(content).equals(files.getString(name)))throw new IllegalArgumentException("Pack checksum mismatch: "+name);Object parsed=new JSONTokener(new String(content,StandardCharsets.UTF_8)).nextValue();if(parsed instanceof JSONObject)verified.put(name,(JSONObject)parsed);else if(!(parsed instanceof JSONArray))throw new IllegalArgumentException("Unexpected manifest JSON root: "+name);}
        calendar=required(verified,"calendar_execution_contract.json");global=required(verified,"calendar_global_constraints_candidate.json");registry=required(verified,"execution_registry.json");
        JSONObject window=calendar.getJSONObject("coverage").getJSONObject("calendarSourceWindow");
        firstSlot=window.getJSONObject("startInclusive").getInt("ordinal")*2;
        horizon=(window.getJSONObject("endInclusive").getInt("ordinal")+1)*2;
        extension=required(verified,"calendar_execution_extension.json");
        JSONArray normalized=required(verified,"manual_link_models.json").getJSONArray("entities");
        for(int n=0;n<normalized.length();n++){
            JSONObject model=normalized.getJSONObject(n);String id=model.getString("entity_id"),slug=id.substring("p3r.social_link.".length());
            if(!model.getBoolean("base_mode_executable"))throw new IllegalArgumentException("Disabled base model");models.put(slug,model);
            JSONArray events=model.getJSONArray("rank_events");for(int i=0;i<events.length();i++)eventIds.add(events.getJSONObject(i).getString("rule_id"));
        }
        if(models.size()!=17)throw new IllegalArgumentException("Unexpected pinned manual model count");
        JSONArray locks=global.getJSONArray("explicitActionLockClaims");
        for(int i=0;i<locks.length();i++){JSONObject lock=locks.getJSONObject(i);int day=lock.getJSONObject("date").getInt("ordinal");JSONArray slots=lock.getJSONArray("slots");for(int j=0;j<slots.length();j++)hardLocks.add(day*2+(slots.getString(j).equals("evening")?1:0));}
        // The execution contract explicitly lists this whole-day event as a hard exclusion.
        JSONArray exclusions=calendar.getJSONArray("safeHardExclusions");for(int i=0;i<exclusions.length();i++)if("mandatory_Aug16_festival".equals(exclusions.getJSONObject(i).optString("kind"))){hardLocks.add(137*2);hardLocks.add(137*2+1);}
    }

    /** Input facts use numeric Interval objects. Unknown/missing values are retained.
     * Additional current fields per entity: alreadyUnlocked, ready, readyRank,
     * affinityLowerBound, affinityRank, affinityValid. Normal is existing 0/1.
     * A validated observed rank>=1 establishes already-unlocked unless contradicted.
     */
    public JSONObject build(JSONObject observed,JSONObject context,JSONObject optionPolicy)throws Exception {
        JSONObject checkpoint=copy(observed),facts=copy(checkpoint.getJSONObject("facts"));
        int slot=strictInt(checkpoint.get("game_slot"),"game_slot");
        JSONArray diagnostics=new JSONArray(),checks=new JSONArray(),candidates=new JSONArray(),actions=new JSONArray();
        JSONObject labels=new JSONObject(),metadata=new JSONObject(),derived=new JSONObject();
        for(int index=firstSlot;index<horizon;index++){LocalDate day=LocalDate.of(2009,4,1).plusDays(index/2);labels.put(String.valueOf(index),new JSONObject().put("date",String.format(Locale.ROOT,"%02d-%02d",day.getMonthValue(),day.getDayOfMonth())).put("period",index%2==0?"daytime":"evening"));}
        TreeSet<String> fields=new TreeSet<>();for(Iterator<String> it=facts.keys();it.hasNext();)fields.add(it.next());
        fields.add("cash");Object cash=checkpoint.opt("cash");facts.put("cash",interval(nil(cash)?null:cash,nil(cash)?null:cash));
        for(String slug:models.keySet())for(String suffix:new String[]{"rank","normal","alreadyUnlocked","ready","readyRank","affinityLowerBound","affinityRank","affinityValid","romanceEligible","relationshipTypeCode"})fields.add(field(slug,suffix));
        JSONObject policy=optionPolicy==null?new JSONObject():copy(optionPolicy);validatePolicy(policy);
        String status=checkpointStatus(checkpoint,context,slot,diagnostics);
        JSONArray views=extension.getJSONArray("records");
        for(int i=0;i<views.length();i++){JSONObject view=views.getJSONObject(i),date=view.getJSONObject("date");int index=date.getInt("ordinal")*2+(view.getString("slot").equals("evening")?1:0);labels.put(String.valueOf(index),new JSONObject().put("date",date.getString("mmdd")).put("period",view.getString("slot")));}
        if(status.equals("conditional"))for(String slug:models.keySet()){
            Long rank=exact(facts,field(slug,"rank")),normal=exact(facts,field(slug,"normal")),unlocked=exact(facts,field(slug,"alreadyUnlocked"));
            if(rank==null||normal==null){diagnostic(diagnostics,"current_link_state_unknown",slug,null,"当前可见等级或关系状态未知，不会猜测可升级。",true);continue;}
            if(rank<1||rank>9||normal!=1||Long.valueOf(0).equals(unlocked)){diagnostic(diagnostics,"current_link_state_blocked",slug,rank.intValue(),"当前社群未处于已解锁、正常且可继续升级的状态。",false);continue;}
            if(beforeIntroduction(slug,slot/2)){diagnostic(diagnostics,"checkpoint_precedes_known_introduction",slug,rank.intValue(),"当前社群等级与来源中的最早介绍日期冲突，请核对当前存档。",true);continue;}
            if(unlocked==null){facts.put(field(slug,"alreadyUnlocked"),interval(1,1));derived.put(field(slug,"alreadyUnlocked"),"validated_observed_rank_at_least_1");}
            List<Integer> eligible=new ArrayList<>();JSONArray sources=new JSONArray(),assumptions=new JSONArray();
            for(int i=0;i<views.length();i++){
                JSONObject view=views.getJSONObject(i);if(!view.getString("entityId").equals("p3r.social_link."+slug))continue;
                int day=view.getJSONObject("date").getInt("ordinal"),index=day*2+(view.getString("slot").equals("evening")?1:0);if(index<slot)continue;
                JSONObject eligibility=evaluateDate(slug,view,slot,context);candidates.put(eligibility);
                if(eligibility.getString("status").startsWith("conditional_available")){eligible.add(index);sources.put(view.getString("sourceRuleId"));JSONArray a=eligibility.getJSONArray("assumptions");for(int j=0;j<a.length();j++)if(!contains(assumptions,a.getString(j)))assumptions.put(a.getString(j));}
            }
            if(eligible.isEmpty()){diagnostic(diagnostics,"no_supported_calendar_candidates",slug,rank.intValue(),"当前剩余日期没有通过本次来源规则检查的候选；这不证明游戏里无法完成。",false);continue;}
            boolean ready=initialReady(slug,rank.intValue(),facts,derived,diagnostics);
            if(!ready){readinessCheckpoint(checks,slug,rank.intValue(),"当前升级准备状态未得到确认；先在游戏里观察，不会把未知当成可升级。");continue;}
            facts.put(field(slug,"ready"),interval(1,1));facts.put(field(slug,"readyRank"),interval(rank,rank));
            JSONObject branch=branchState(slug,context,facts);
            int from=rank.intValue();
            while(from<10&&ready){
                int to=from+1;JSONObject event=event(slug,to,branch);
                if(event==null){checkpoint(checks,slug,from,"事件分支依赖尚未确认的关系或恋爱资格；请先观察或明确路线选择。");break;}
                if(event.has("executable")&&!event.getBoolean("executable")){checkpoint(checks,slug,from,"该等级事件存在未解决的来源问题，暂停预测。");break;}
                JSONObject gain=eventGain(slug,event,policy,branch);
                if(!gain.getBoolean("path_supported")){checkpoint(checks,slug,from,gain.getString("reason"));diagnostic(diagnostics,gain.optString("stop_code","unresolved_source_path"),slug,from,gain.getString("reason"),true);break;}
                if(gain.getBoolean("forecast_max_policy")&&!contains(assumptions,"A.source_max_base_points_forecast_only"))assumptions.put("A.source_max_base_points_forecast_only");
                Long bound=gain.isNull("lower_bound")?null:gain.getLong("lower_bound");
                Integer nextGate=to<10?threshold(slug,to+1):null;
                boolean nextReady=to<10&&nextGate!=null&&(nextGate==0||(bound!=null&&bound>=nextGate));
                JSONObject assignments=new JSONObject().put(field(slug,"rank"),interval(to,to))
                        .put(field(slug,"affinityLowerBound"),bound==null?JSONObject.NULL:interval(bound,null))
                        .put(field(slug,"affinityRank"),interval(to,to))
                        .put(field(slug,"affinityValid"),interval(bound==null?0:1,bound==null?0:1));
                JSONObject effects=gain.getJSONObject("branch_effects");
                for(Iterator<String> it=effects.keys();it.hasNext();){String key=it.next();Object value=effects.get(key);if(nil(value)){branch.remove(key);assignments.put(field(slug,key.equals("relationshipType")?"relationshipTypeCode":key),JSONObject.NULL);}else{branch.put(key,value);if(key.equals("romanceEligible"))assignments.put(field(slug,key),interval(Boolean.TRUE.equals(value)?1:0,Boolean.TRUE.equals(value)?1:0));else if(key.equals("relationshipType"))assignments.put(field(slug,"relationshipTypeCode"),interval(value.equals("romantic")?2:1,value.equals("romantic")?2:1));}}
                JSONArray forgotten=new JSONArray();
                if(nextReady){assignments.put(field(slug,"ready"),interval(1,1));assignments.put(field(slug,"readyRank"),interval(to,to));}
                else {forgotten.put(field(slug,"ready")).put(field(slug,"readyRank"));}
                JSONObject minimums=new JSONObject().put(field(slug,"rank"),from).put(field(slug,"normal"),1).put(field(slug,"alreadyUnlocked"),1).put(field(slug,"ready"),1).put(field(slug,"readyRank"),from);
                JSONObject maximums=new JSONObject().put(field(slug,"rank"),from).put(field(slug,"normal"),1).put(field(slug,"alreadyUnlocked"),1).put(field(slug,"ready"),1).put(field(slug,"readyRank"),from);
                JSONArray guards=event.getJSONArray("entry_guards");for(int g=0;g<guards.length();g++){JSONObject guard=guards.getJSONObject(g);String key=mapBranchField(slug,guard.getString("field")),engineField=field(slug,key.equals("relationshipType")?"relationshipTypeCode":key);int expected=key.equals("relationshipType")?(guard.getString("value").equals("romantic")?2:1):(guard.getBoolean("value")?1:0);minimums.put(engineField,expected);maximums.put(engineField,expected);}
                if(gain.getBoolean("requires_full_event_completion")&&!contains(assumptions,"A.complete_rank_event_resolution"))assumptions.put("A.complete_rank_event_resolution");
                String id="p3r.manual."+slug+".rank_"+String.format(Locale.ROOT,"%02d",to);
                JSONObject evidence=new JSONObject().put("model_event",event.getString("rule_id")).put("incoming_gate",threshold(slug,to)==null?JSONObject.NULL:threshold(slug,to)).put("calendar_sources",sources).put("manifest_sha256",MANIFEST_SHA256);
                JSONObject action=new JSONObject().put("id",id).put("slots",new JSONArray(eligible)).put("requirements",minimums).put("maximums",maximums).put("assignments",assignments).put("forget_fields",forgotten).put("verification","corroborated").put("source",evidence.toString());
                actions.put(action);
                metadata.put(id,new JSONObject().put("name_zh_cn",models.get(slug).getString("name_zh_cn")).put("display_title",models.get(slug).getString("name_zh_cn")+" · "+from+"→"+to).put("entity",slug).put("from_rank",from).put("to_rank",to).put("source_event",event.getString("rule_id")).put("event_gain",gain).put("next_ready",nextReady?Boolean.TRUE:JSONObject.NULL).put("assumptions",assumptions).put("calendar_guard_basis","validated_checkpoint_and_applicable_source_guards").put("source_option_order_warning","来源英文选项序号；不是中文游戏界面的选项映射").put("exact_affinity_balance_claimed",false));
                from=to;ready=nextReady;
                if(!ready&&to<10)readinessCheckpoint(checks,slug,to,"已知来源选项的保守下界不足以证明下一次可升级；需新的实际准备状态观察。");
            }
        }
        JSONObject rules=new JSONObject().put("version",version).put("horizon",horizon).put("fields",new JSONArray(fields)).put("actions",actions).put("coverage_complete",false).put("synthetic",false);
        JSONObject engine=new JSONObject().put("run_id",checkpoint.getString("run_id")).put("rules_version",version).put("revision",checkpoint.getLong("revision")).put("slot",slot).put("values",facts).put("visits",copy(checkpoint.optJSONObject("visits_by_stage")==null?new JSONObject():checkpoint.getJSONObject("visits_by_stage")));
        return new JSONObject().put("status",status).put("supported_goal_fields",supportedFields()).put("requires_rebase",!version.equals(checkpoint.optString("rules_version"))).put("rules",rules).put("engine_snapshot",engine).put("labels",labels).put("action_metadata",metadata).put("calendar_candidates",candidates).put("diagnostics",diagnostics).put("observation_checkpoints",checks).put("derived_checkpoint_facts",derived).put("source_policy_declared",Boolean.TRUE.equals(policy.opt("declared"))).put("planning_boundary_slot",firstSlot).put("observed_state_mutated",false).put("scope","partial_source_model_17_manual_affinity_links");
    }

    /** Convenience wrapper preserves unknown/unsupported outcomes and exposes stops. */
    public JSONObject plan(JSONObject observed,JSONObject calendarContext,JSONObject optionPolicy,JSONArray goals,JSONObject constraints,int beamWidth,int maxExpansions)throws Exception {
        JSONObject built=build(observed,calendarContext,optionPolicy),rules=built.getJSONObject("rules"),initial=built.getJSONObject("engine_snapshot");
        for(int i=0;i<goals.length();i++){String field=goals.getJSONObject(i).getString("field");if(!contains(supportedFields(),field)){built.put("status","unsupported");diagnostic(built.getJSONArray("diagnostics"),"unsupported_goal",null,null,"这个适配器只提供 17 个手动好感社群的有限来源模型。",false);}}
        JSONObject result;
        if(!built.getString("status").equals("conditional")){result=new JSONObject().put("status",built.getString("status").equals("requires_story_progression_to_planning_boundary")?"unknown":built.getString("status")).put("steps",new JSONArray()).put("forecast",initial).put("explanations",new JSONArray().put("当前状态需要核对或规则范围不支持；不会据此判定全目标不可能。"));}
        else {JSONObject scoped=copy(constraints);scoped.put("visit_caps",new JSONObject());result=PlannerEngine.plan(rules,initial,goals,scoped,beamWidth,maxExpansions);}
        result.put("preserved_constraints",copy(constraints)).put("dormant_visit_caps",constraints.optJSONObject("visit_caps")==null?new JSONObject():copy(constraints.getJSONObject("visit_caps"))).put("contains_dungeon_actions",false);
        if(constraints.optJSONObject("visit_caps")!=null&&constraints.getJSONObject("visit_caps").length()>0)diagnostic(built.getJSONArray("diagnostics"),"dormant_social_only_visit_caps",null,null,"爬塔上限仍被保留；此候选只含社群行动，上限暂不参与计算。加入爬塔行动时必须重新启用。",false);
        result.put("supported_goal_fields",built.getJSONArray("supported_goal_fields")).put("adapter_status",built.getString("status")).put("action_metadata",built.getJSONObject("action_metadata")).put("diagnostics",built.getJSONArray("diagnostics")).put("observation_checkpoints",built.getJSONArray("observation_checkpoints")).put("derived_checkpoint_facts",built.getJSONObject("derived_checkpoint_facts")).put("source_policy_declared",built.getBoolean("source_policy_declared")).put("model_scope","partial_source_model_17_manual_affinity_links").put("observed_state_mutated",false).put("optimality_proven",false).put("manifest_sha256",MANIFEST_SHA256);
        return result;
    }

    /** Calendar-only interface for all 19 manual links, including point-independent Sun/Devil.
     * It does not grant readiness, rank effects, unlocks or dungeon availability. */
    public JSONObject guardedCalendarCandidates(JSONObject observed,JSONObject context,String slug)throws Exception {
        JSONObject state=copy(observed),facts=copy(state.getJSONObject("facts"));JSONArray diagnostics=new JSONArray(),candidates=new JSONArray(),slots=new JSONArray(),sources=new JSONArray(),assumptions=new JSONArray();JSONObject derived=new JSONObject();
        int slot=strictInt(state.get("game_slot"),"game_slot");String status=checkpointStatus(state,context,slot,diagnostics);
        if(!extension.getJSONObject("profiles").has(slug)){status="unsupported";diagnostic(diagnostics,"unsupported_calendar_entity",slug,null,"此社群没有已审计的普通会面日历。",false);}
        Long rank=exact(facts,field(slug,"rank")),normal=exact(facts,field(slug,"normal")),unlocked=exact(facts,field(slug,"alreadyUnlocked"));
        if(status.equals("conditional")){
            if(rank==null||normal==null){status="unknown";diagnostic(diagnostics,"current_link_state_unknown",slug,null,"需要当前可见等级和正常关系的实际观察。",true);}
            else if(rank<1||rank>9||normal!=1||Long.valueOf(0).equals(unlocked)){status="blocked_known_state";diagnostic(diagnostics,"current_link_state_blocked",slug,rank.intValue(),"当前状态不支持普通升级会面；未自动解锁社群。",false);}
            else if(models.containsKey(slug)&&beforeIntroduction(slug,slot/2)){status="unknown";diagnostic(diagnostics,"checkpoint_precedes_known_introduction",slug,rank.intValue(),"日期与已知最早介绍日期冲突，请核对。",true);}
            else {if(unlocked==null)derived.put(field(slug,"alreadyUnlocked"),"validated_observed_rank_at_least_1");JSONArray views=extension.getJSONArray("records");
                for(int i=0;i<views.length();i++){JSONObject view=views.getJSONObject(i);if(!view.getString("entityId").equals("p3r.social_link."+slug)||view.getInt("slotIndex")<slot)continue;JSONObject candidate=evaluateDate(slug,view,slot,context);candidates.put(candidate);
                    if(candidate.getString("status").startsWith("conditional_available")){slots.put(candidate.getInt("slot"));sources.put(view.getString("sourceRuleId"));JSONArray values=candidate.getJSONArray("assumptions");for(int j=0;j<values.length();j++)if(!contains(assumptions,values.getString(j)))assumptions.put(values.getString(j));}}
            }
        }
        return new JSONObject().put("status",status).put("candidates",candidates).put("eligible_slots",slots).put("source_rule_ids",sources).put("assumptions",assumptions).put("diagnostics",diagnostics).put("derived_checkpoint_facts",derived).put("observed_state_mutated",false).put("manifest_sha256",MANIFEST_SHA256);
    }

    private String checkpointStatus(JSONObject checkpoint,JSONObject context,int slot,JSONArray diagnostics)throws Exception {
        String status="conditional";
        if(!version.equals(checkpoint.optString("rules_version"))){status="unsupported";diagnostic(diagnostics,"rules_version_mismatch",null,null,"请确认实际状态并建立扩展规则的新基线；不会静默修改旧规则版本。",false);}
        if(!"P3R-base".equals(context.optString("gameEdition"))){status="unsupported";diagnostic(diagnostics,"unsupported_edition",null,null,"需要明确的 P3R-base 游戏版本。",false);}
        if(slot<firstSlot){status="requires_story_progression_to_planning_boundary";diagnostic(diagnostics,"story_planning_boundary",null,null,"当前处于规则日历之前；先完成到 4 月 21 日的必要剧情，再核对实际状态。",true);}
        else if(slot>horizon){status="unsupported";diagnostic(diagnostics,"outside_source_window",null,null,"当前日期不在本次来源日历范围。",false);}
        Object contextDay=path(context,"checkpoint.date.ordinal");
        if(!nil(contextDay)&&strictInt(contextDay,"checkpoint date ordinal")!=slot/2){status="unknown";diagnostic(diagnostics,"checkpoint_date_mismatch",null,null,"日历上下文与当前实际日期不一致，请重新核对。",true);}
        if(!Boolean.TRUE.equals(path(context,"checkpoint.consistencyValidated"))&&status.equals("conditional")){status="unknown";diagnostic(diagnostics,"checkpoint_consistency_unknown",null,null,"请核对当前日期、等级和关系状态是否属于同一个实际存档。",true);}
        return status;
    }

    private JSONObject evaluateDate(String slug,JSONObject view,int checkpointSlot,JSONObject context)throws Exception {
        int day=view.getJSONObject("date").getInt("ordinal"),slot=day*2+(view.getString("slot").equals("evening")?1:0);
        JSONObject out=new JSONObject().put("entity",slug).put("slot",slot).put("source_rule_id",view.getString("sourceRuleId")).put("status","conditional_available").put("assumptions",new JSONArray());
        JSONArray assumptions=out.getJSONArray("assumptions");
        if(hardLocks.contains(slot))return out.put("status","blocked_known_state").put("reason","explicit_source_slot_lock");
        Object capacity=path(context,"calendar.slotStates."+slot+".remainingCapacity");
        if(!nil(capacity)&&strictInt(capacity,"remainingCapacity")<1)return out.put("status","blocked_known_state").put("reason","slot_already_spent");
        if(slot==checkpointSlot&&nil(capacity))return out.put("status","unknown_eligibility").put("reason","current_slot_spend_unknown");
        Object lock=path(context,"calendar.slotStates."+slot+".knownStoryLock");
        if(Boolean.TRUE.equals(lock))return out.put("status","blocked_known_state").put("reason","known_story_lock");
        if(nil(lock)){assumptions.put("A.calendar_context_complete_enough");out.put("status","conditional_available_with_assumption");}
        Object unavailable=path(context,"calendar.entityDateStates."+slug+"."+day+".knownUnavailable");
        if(Boolean.TRUE.equals(unavailable))return out.put("status","blocked_known_state").put("reason","known_entity_unavailable");
        // Missing irrelevant rescue flags are not facts and do not create a future questionnaire.
        if(unavailable==JSONObject.NULL)return out.put("status","unknown_eligibility").put("reason","explicit_entity_guard_unknown");
        if(Boolean.TRUE.equals(path(context,"socialLinks."+slug+".permanentlyUnavailable")))return out.put("status","blocked_known_state").put("reason","observed_permanently_unavailable");
        JSONArray additional=view.optJSONArray("additionalGuardIds");
        if(additional!=null)for(int i=0;i<additional.length();i++){
            if(!additional.getString(i).equals("person_confirmed_available_after_rescue_window"))return out.put("status","unknown_eligibility").put("reason","unmapped_additional_guard");
            Object rescued=path(context,"socialLinks."+slug+".rescuedAtOrdinal"),present=path(context,"socialLinks."+slug+".personAvailabilityConfirmedForDate");
            boolean knownPresent=present instanceof Number&&strictInt(present,"availability date")==day;
            if(present instanceof JSONObject)knownPresent=Boolean.TRUE.equals(((JSONObject)present).opt(String.valueOf(day)));
            knownPresent=knownPresent&&day==checkpointSlot/2;
            boolean knownRescue=!nil(rescued)&&strictInt(rescued,"rescue completion ordinal")<=checkpointSlot/2&&strictInt(rescued,"rescue completion ordinal")<day;
            if(!knownPresent&&!knownRescue)return out.put("status","unknown_eligibility").put("reason","rescue_or_present_availability_unknown");
        }
        JSONArray special=view.getJSONArray("knownSpecialGuardIds");
        for(int i=0;i<special.length();i++){
            String guard=special.getString(i);
            if(guard.equals("christmas_all_invitations_declined")){
                Object declined=path(context,"storyFlags.christmasAllInvitationsDeclined");
                if(!Boolean.TRUE.equals(declined))return out.put("status",Boolean.FALSE.equals(declined)?"blocked_known_state":"unknown_eligibility").put("reason",guard);
            }else if(guard.equals("jan21_conditional_story_event_resolved")){
                Object flag=path(context,"storyFlags.junpeiWhiteFlowersGiven");
                if(!(flag instanceof Boolean)||!Boolean.FALSE.equals(lock))return out.put("status","unknown_eligibility").put("reason",guard);
            }else return out.put("status","unknown_eligibility").put("reason","unmapped_special_guard");
        }
        return out.put("capacity_basis",slot==checkpointSlot?"observed_current_slot":"future_single_slot_resource_model").put("entity_guard_basis","normal_validated_checkpoint_with_applicable_guards");
    }

    private boolean initialReady(String slug,int rank,JSONObject facts,JSONObject derived,JSONArray diagnostics)throws Exception {
        Long ready=exact(facts,field(slug,"ready")),readyRank=exact(facts,field(slug,"readyRank"));
        if(readyRank!=null&&readyRank==rank&&ready!=null){
            if(ready==1)return true;
            if(ready==0){diagnostic(diagnostics,"observed_not_ready",slug,rank,"当前实际观察为尚不可升级；来源下界不会覆盖这个观察。",true);return false;}
        }
        facts.put(field(slug,"ready"),interval(null,null));facts.put(field(slug,"readyRank"),interval(null,null));
        Integer gate=threshold(slug,rank+1);
        if(gate==null){diagnostic(diagnostics,"incoming_gate_unresolved",slug,rank,"下一等级门槛存在来源冲突或缺失，只能使用与当前等级对应的实际准备状态。",true);return false;}
        Long valid=exact(facts,field(slug,"affinityValid")),affinityRank=exact(facts,field(slug,"affinityRank"));
        JSONObject bound=facts.optJSONObject(field(slug,"affinityLowerBound"));Long lower=bound==null||bound.isNull("low")?null:strictLong(bound.get("low"),"affinity lower bound");
        if(Long.valueOf(1).equals(valid)&&affinityRank!=null&&affinityRank==rank&&lower!=null&&lower>=gate){derived.put(field(slug,"ready"),"current_rank_provenance_bound_reaches_gate_"+gate);return true;}
        if(gate==0&&!Long.valueOf(0).equals(valid)){derived.put(field(slug,"ready"),"source_zero_gate_no_affinity_value_assigned");return true;}
        return false;
    }

    private static String mapBranchField(String slug,String source){
        if(source.equals("relationship"))return "relationshipType";
        if(source.equals(slug+".romance_eligible"))return "romanceEligible";
        if(source.equals("relationship_reversed"))return "relationshipReversed";
        throw new IllegalArgumentException("Unmapped source branch field: "+source);
    }
    private JSONObject branchState(String slug,JSONObject context,JSONObject facts)throws Exception {
        JSONObject out=new JSONObject();Object eligible=path(context,"socialLinks."+slug+".romanceEligible"),relationship=path(context,"socialLinks."+slug+".relationshipType");
        Long numeric=exact(facts,field(slug,"romanceEligible")),code=exact(facts,field(slug,"relationshipTypeCode"));
        if(!nil(eligible)&&!(eligible instanceof Boolean))throw new IllegalArgumentException("romanceEligible must be boolean or unknown");
        if(!nil(relationship)&&!relationship.equals("friendship")&&!relationship.equals("romantic"))throw new IllegalArgumentException("Invalid relationshipType");
        if(numeric!=null&&numeric!=0&&numeric!=1)throw new IllegalArgumentException("Invalid romanceEligible interval");
        if(code!=null&&code!=1&&code!=2)throw new IllegalArgumentException("Invalid relationshipTypeCode interval");
        if(nil(eligible)&&numeric!=null)eligible=numeric==1;
        if(nil(relationship)&&code!=null)relationship=code==2?"romantic":"friendship";
        if(!nil(eligible)){if(numeric!=null&&(numeric==1)!=Boolean.TRUE.equals(eligible))throw new IllegalArgumentException("Conflicting branch observations");out.put("romanceEligible",eligible);facts.put(field(slug,"romanceEligible"),interval(Boolean.TRUE.equals(eligible)?1:0,Boolean.TRUE.equals(eligible)?1:0));}
        if(!nil(relationship)){int value=relationship.equals("romantic")?2:1;if(code!=null&&code!=value)throw new IllegalArgumentException("Conflicting relationship observations");out.put("relationshipType",relationship);facts.put(field(slug,"relationshipTypeCode"),interval(value,value));}
        return out;
    }
    private static final class SourcePath {
        long points;int known,unknown;JSONArray choices=new JSONArray();JSONObject effects=new JSONObject();
    }
    private JSONObject eventGain(String slug,JSONObject event,JSONObject policy,JSONObject branch)throws Exception {
        boolean declared=Boolean.TRUE.equals(policy.opt("declared")),maximum=declared&&"source_max_base_points".equals(policy.optString("mode"));
        JSONArray selected=declared&&policy.optJSONObject("events")!=null?policy.getJSONObject("events").optJSONArray(event.getString("rule_id")):null;
        JSONArray prompts=event.getJSONArray("prompts");
        if(selected!=null&&selected.length()>prompts.length())throw new IllegalArgumentException("Too many source options for event");
        String decision=null;JSONObject decisions=policy.optJSONObject("relationship_decisions");
        if(decisions!=null&&!decisions.isNull(slug))decision=decisions.getString(slug);
        if(slug.equals("moon")&&event.getInt("to_rank")==9&&!(declared&&Boolean.TRUE.equals(policy.opt("assume_complete_events"))))return unsupportedPath("full_event_completion_required","月亮九级事件有临时逆位；先明确完整完成事件的预测假设，部分过程必须重新观察。");
        Map<Integer,JSONObject> graph=new HashMap<>();for(int i=0;i<prompts.length();i++)graph.put(prompts.getJSONObject(i).getInt("prompt_ordinal"),prompts.getJSONObject(i));
        SourcePath path=sourcePath(slug,graph,1,selected,maximum,decision,new HashSet<>());
        if(path==null)return unsupportedPath("unresolved_or_reversal_source_path","所选路径含逆位、未确认的分支或未明确的关系决定；暂停，核对事件结果后再规划。");
        JSONArray ordered=new JSONArray();for(int i=0;i<prompts.length();i++)ordered.put(JSONObject.NULL);
        for(int i=0;i<path.choices.length();i++){JSONObject chosen=path.choices.getJSONObject(i);ordered.put(chosen.getInt("prompt")-1,chosen.opt("option"));}
        return new JSONObject().put("path_supported",true).put("lower_bound",path.points).put("upper_bound",JSONObject.NULL).put("known_source_choices",path.known).put("prompt_count",prompts.length()).put("visited_prompt_count",path.known+path.unknown).put("selected_source_options",ordered).put("selected_source_path",path.choices).put("branch_effects",path.effects).put("all_choices_known",path.unknown==0).put("forecast_max_policy",maximum).put("minimum_response_policy",!maximum).put("source_ordinals_are_game_instructions",false).put("bonus_lookup_enabled",false).put("replaces_prior_rank_knowledge",true).put("is_exact_game_balance",false).put("requires_full_event_completion",slug.equals("moon")&&event.getInt("to_rank")==9);
    }
    private static JSONObject unsupportedPath(String code,String reason)throws Exception{return new JSONObject().put("path_supported",false).put("stop_code",code).put("reason",reason);}
    /** Evaluate only a legal root-to-terminal path. Unknown choices never contribute points. */
    private SourcePath sourcePath(String slug,Map<Integer,JSONObject> graph,Integer cursor,JSONArray selected,boolean maximum,String decision,Set<Integer> ancestors)throws Exception {
        if(cursor==null)return new SourcePath();if(!ancestors.add(cursor))throw new IllegalArgumentException("Source prompt cycle");JSONObject prompt=graph.get(cursor);if(prompt==null)throw new IllegalArgumentException("Missing source prompt");
        JSONArray options=prompt.getJSONArray("options");Object chosen=selected==null?null:selected.opt(cursor-1);
        if(!maximum&&nil(chosen))return minimumSourcePath(slug,graph,cursor,selected,decision,ancestors);
        Integer ordinal=nil(chosen)?null:strictInt(chosen,"source option ordinal");boolean found=false;SourcePath best=null;
        for(int i=0;i<options.length();i++){
            JSONObject option=options.getJSONObject(i);int candidate=option.getInt("source_option_ordinal");if(!maximum&&(ordinal==null||ordinal!=candidate))continue;found=true;
            JSONObject effects=new JSONObject();boolean allowed=true;JSONArray sourceEffects=option.getJSONArray("effects");
            for(int e=0;e<sourceEffects.length();e++){JSONObject effect=sourceEffects.getJSONObject(e);String key=mapBranchField(slug,effect.getString("field"));Object value=effect.get("value");
                if(key.equals("relationshipReversed")){if(Boolean.TRUE.equals(value))allowed=false;}
                else if(key.equals("relationshipType")){if(decision==null||!decision.equals(value))allowed=false;else effects.put(key,value);}
                else effects.put(key,value);
            }
            if(!allowed)continue;
            Integer next=option.isNull("next_prompt_ordinal")?null:option.getInt("next_prompt_ordinal");SourcePath tail=sourcePath(slug,graph,next,selected,maximum,decision,new HashSet<>(ancestors));if(tail==null)continue;
            long points=strictLong(option.get("raw_affinity_points"),"raw affinity points");if(points<0)throw new IllegalArgumentException("Negative source points require a separate model");tail.points=Math.addExact(tail.points,points);tail.known++;tail.choices=prepend(new JSONObject().put("prompt",cursor).put("option",candidate),tail.choices);
            for(Iterator<String> it=effects.keys();it.hasNext();){String key=it.next();if(!tail.effects.has(key))tail.effects.put(key,effects.get(key));}
            if(best==null||tail.points>best.points)best=tail;
        }
        if(!found&&!maximum)throw new IllegalArgumentException("Unknown source option ordinal");return best;
    }
    /** Conservative default: every possible safe response on the resolved route is included.
     * A reversal or unresolved relationship branch blocks a completed-event prediction. */
    private SourcePath minimumSourcePath(String slug,Map<Integer,JSONObject> graph,int cursor,JSONArray selected,String decision,Set<Integer> ancestors)throws Exception {
        JSONArray options=graph.get(cursor).getJSONArray("options");List<SourcePath> paths=new ArrayList<>();SourcePath worst=null;
        for(int i=0;i<options.length();i++){
            JSONObject option=options.getJSONObject(i),effects=new JSONObject();JSONArray rawEffects=option.getJSONArray("effects");boolean routeMismatch=false;
            for(int n=0;n<rawEffects.length();n++){JSONObject effect=rawEffects.getJSONObject(n);String key=mapBranchField(slug,effect.getString("field"));Object value=effect.get("value");
                if(key.equals("relationshipReversed")&&Boolean.TRUE.equals(value))return null;
                if(key.equals("relationshipType")){if(decision==null)return null;if(!decision.equals(value))routeMismatch=true;}
                effects.put(key,value);
            }
            if(routeMismatch)continue;Integer next=option.isNull("next_prompt_ordinal")?null:option.getInt("next_prompt_ordinal");SourcePath tail=sourcePath(slug,graph,next,selected,false,decision,new HashSet<>(ancestors));if(tail==null)return null;
            long points=strictLong(option.get("raw_affinity_points"),"raw affinity points");if(points<0)throw new IllegalArgumentException("Unmodeled negative response outcome");tail.points=Math.addExact(tail.points,points);tail.unknown++;
            tail.choices=prepend(new JSONObject().put("prompt",cursor).put("option",JSONObject.NULL).put("evaluation","minimum_across_all_safe_responses"),tail.choices);
            for(Iterator<String> it=effects.keys();it.hasNext();){String key=it.next();if(!tail.effects.has(key))tail.effects.put(key,effects.get(key));}
            paths.add(tail);if(worst==null||tail.points<worst.points)worst=tail;
        }
        if(worst==null)return null;Set<String> keys=new TreeSet<>();for(SourcePath path:paths)for(Iterator<String> it=path.effects.keys();it.hasNext();)keys.add(it.next());JSONObject common=new JSONObject();
        for(String key:keys){Object value=paths.get(0).effects.opt(key);boolean equal=!nil(value);for(SourcePath path:paths)if(!java.util.Objects.equals(value,path.effects.opt(key)))equal=false;common.put(key,equal?value:JSONObject.NULL);}
        worst.effects=common;return worst;
    }
    private static JSONArray prepend(JSONObject head,JSONArray tail)throws Exception{JSONArray out=new JSONArray().put(head);for(int i=0;i<tail.length();i++)out.put(tail.get(i));return out;}


    private void validatePolicy(JSONObject policy)throws Exception {
        JSONObject events=policy.optJSONObject("events");boolean declared=Boolean.TRUE.equals(policy.opt("declared"));
        JSONObject decisions=policy.optJSONObject("relationship_decisions");
        if(decisions!=null)for(Iterator<String> it=decisions.keys();it.hasNext();){String slug=it.next();Object value=decisions.opt(slug);if(!models.containsKey(slug)||(!nil(value)&&!value.equals("friendship")&&!value.equals("romantic")))throw new IllegalArgumentException("Invalid relationship route decision");}

        if(declared&&!"source_option_ordinals".equals(policy.optString("mode"))&&!"source_max_base_points".equals(policy.optString("mode")))throw new IllegalArgumentException("An explicit supported source policy is required");
        if("source_max_base_points".equals(policy.optString("mode"))&&events!=null&&events.length()>0)throw new IllegalArgumentException("Do not mix maximum forecast policy with manually selected event options");
        if(events!=null&&events.length()>0&&!declared)throw new IllegalArgumentException("Source options cannot be assumed without an explicit declared policy");
        if(events!=null)for(Iterator<String> it=events.keys();it.hasNext();){String id=it.next();if(!eventIds.contains(id)||!(events.get(id)instanceof JSONArray))throw new IllegalArgumentException("Unknown or malformed source-policy event");}
    }

    private JSONObject event(String slug,int to,JSONObject branch)throws Exception {
        JSONObject found=null;JSONArray events=models.get(slug).getJSONArray("rank_events");
        for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if(event.getInt("to_rank")!=to)continue;JSONArray guards=event.getJSONArray("entry_guards");boolean allowed=true;
            for(int g=0;g<guards.length();g++){JSONObject guard=guards.getJSONObject(g);String key=mapBranchField(slug,guard.getString("field"));Object actual=branch.opt(key);if(nil(actual)||!actual.equals(guard.get("value")))allowed=false;}
            if(allowed){if(found!=null)throw new IllegalArgumentException("Ambiguous rank event branch");found=event;}}
        return found;
    }
    private Integer threshold(String slug,int to)throws Exception {
        JSONArray gates=models.get(slug).getJSONArray("incoming_thresholds");for(int i=0;i<gates.length();i++){JSONObject gate=gates.getJSONObject(i);if(gate.getInt("to_rank")==to){if(!gate.getBoolean("executable_sufficiency_check")||gate.isNull("exact_raw_threshold")||gate.optBoolean("requires_opt_in_source_envelope_mode",false))return null;return gate.getInt("exact_raw_threshold");}}return null;
    }
    private JSONArray supportedFields(){JSONArray out=new JSONArray();for(String slug:models.keySet())out.put(field(slug,"rank"));return out;}
    private boolean beforeIntroduction(String slug,int day)throws Exception {
        if(slug.equals("magician"))return day<21;if(slug.equals("chariot"))return day<22;
        JSONObject unlock=models.get(slug).optJSONObject("unlock");if(unlock==null||!unlock.has("earliest_month_day_claim"))return false;
        String[] parts=unlock.getString("earliest_month_day_claim").split("-");int month=Integer.parseInt(parts[0]),date=Integer.parseInt(parts[1]);long first=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2009,4,1),LocalDate.of(month<4?2010:2009,month,date));return day<first;
    }
    private String registryMode(String slug)throws Exception {JSONArray entities=registry.getJSONArray("entities");for(int i=0;i<entities.length();i++)if(entities.getJSONObject(i).getString("entityId").equals("p3r.social_link."+slug))return entities.getJSONObject(i).getString("mode");return "";}
    private static void readinessCheckpoint(JSONArray output,String slug,int rank,String message)throws Exception {
        checkpoint(output,slug,rank,message);output.getJSONObject(output.length()-1).put("next_action",new JSONObject()
            .put("kind","observe_pre_meeting_rank_up_prompt").put("label_zh_cn","先查看会面前的升级提示")
            .put("if_rank_up_offered","记录与当前等级对应的可升级观察后重新规划；不强行安排补好感")
            .put("if_ordinary_hangout_explicit","可选择普通会面，但不赋予未建模的数值好感；记录实际结果后重新规划")
            .put("if_unclear","保留未知，先核对实际提示")
            .put("is_executable_rank_action",false).put("assumed_affinity_gain",JSONObject.NULL)
            .put("future_visit_count",JSONObject.NULL).put("maximum_feasibility","unresolved")
            .put("credits_observed_state",false));
    }
    private static void checkpoint(JSONArray output,String slug,int rank,String message)throws Exception {output.put(new JSONObject().put("entity",slug).put("at_rank",rank).put("requires_observation",true).put("message",message).put("never_assume_future_readiness",true));}
    private static void diagnostic(JSONArray output,String code,String slug,Integer rank,String message,boolean observation)throws Exception {output.put(new JSONObject().put("code",code).put("entity",slug==null?JSONObject.NULL:slug).put("rank",rank==null?JSONObject.NULL:rank).put("message",message).put("requires_observation",observation));}
    private static String field(String slug,String suffix){return "socialLinks."+slug+"."+suffix;}
    private static Long exact(JSONObject facts,String field)throws Exception {JSONObject value=facts.optJSONObject(field);if(value==null||value.isNull("low")||value.isNull("high"))return null;long low=strictLong(value.get("low"),field),high=strictLong(value.get("high"),field);return low==high?low:null;}
    private static JSONObject interval(Object low,Object high)throws Exception {return new JSONObject().put("low",nil(low)?JSONObject.NULL:low).put("high",nil(high)?JSONObject.NULL:high);}
    private static Object path(JSONObject root,String path){Object current=root;for(String key:path.split("\\.")){if(!(current instanceof JSONObject))return null;JSONObject object=(JSONObject)current;if(!object.has(key))return null;current=object.opt(key);}return current;}
    private static boolean nil(Object value){return value==null||value==JSONObject.NULL;}
    private static int strictInt(Object value,String name){long number=strictLong(value,name);if(number<Integer.MIN_VALUE||number>Integer.MAX_VALUE)throw new IllegalArgumentException(name+" out of range");return(int)number;}
    private static long strictLong(Object value,String name){if(!(value instanceof Integer||value instanceof Long))throw new IllegalArgumentException(name+" must be an integer");return((Number)value).longValue();}
    private static boolean contains(JSONArray array,String value){for(int i=0;i<array.length();i++)if(value.equals(array.optString(i)))return true;return false;}
    private static JSONObject copy(JSONObject object)throws Exception{return new JSONObject(object.toString());}
    private static JSONObject required(Map<String,JSONObject> map,String name){JSONObject result=map.get(name);if(result==null)throw new IllegalArgumentException("Missing manifest-covered artifact "+name);return result;}
    private static JSONObject parse(byte[] bytes)throws Exception{return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    private static byte[] bytes(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];for(int count;(count=in.read(buffer))!=-1;)out.write(buffer,0,count);return out.toByteArray();}
    private static String sha(byte[] bytes)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte value:hash)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
}
