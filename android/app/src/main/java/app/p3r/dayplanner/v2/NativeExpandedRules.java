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

/** Pinned, conditional Magician/Chariot source adapter. No observed state is written.
 * Only a readiness-supported prefix is compiled: an unresolved gate becomes an
 * observation checkpoint, never a speculative chain of repeated rank gains.
 */
public final class NativeExpandedRules {
    public static final String DEFAULT_ROOT="planner_v2/rules_v03/";
    public static final String MANIFEST_SHA256="11d09600d77f5fa0714660ee0465087250d6d103322cb1c12d0aca80308c2bf3";
    public static final String ADAPTER_REVISION="a2", SIMULATOR_REVISION=PlannerEngine.SIMULATOR_REVISION;
    public final String version="p3r-expanded-0.3.0-"+MANIFEST_SHA256.substring(0,12)+"-"+ADAPTER_REVISION+"-"+SIMULATOR_REVISION;
    public final int firstSlot,horizon;
    private final JSONObject calendar,global,registry;
    private final Map<String,JSONObject> models=new HashMap<>();
    private final Set<Integer> hardLocks=new HashSet<>();
    private final Set<String> eventIds=new HashSet<>();
    private interface Reader {byte[] read(String filename)throws Exception;}

    public NativeExpandedRules(Context context)throws Exception {this(context,DEFAULT_ROOT);}
    public NativeExpandedRules(Context context,String assetRoot)throws Exception {
        this(name->{try(InputStream in=context.getAssets().open(assetRoot+name)){return bytes(in);}});
    }
    public NativeExpandedRules(File directory)throws Exception {
        this(name->{File file=new File(directory,name);if(!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IllegalArgumentException("Invalid pack path");try(InputStream in=new FileInputStream(file)){return bytes(in);}});
    }
    private NativeExpandedRules(Reader reader)throws Exception {
        byte[] manifestBytes=reader.read("manifest.json");
        if(!sha(manifestBytes).equals(MANIFEST_SHA256))throw new IllegalArgumentException("Pinned 0.3.0 manifest checksum mismatch");
        JSONObject manifest=parse(manifestBytes);
        if(!"0.3.0".equals(manifest.getString("packVersion"))||!"P3R-base".equals(manifest.getString("gameEdition")))throw new IllegalArgumentException("Wrong audited pack edition/version");
        JSONObject files=manifest.getJSONObject("files");Map<String,JSONObject> verified=new HashMap<>();
        for(Iterator<String> it=files.keys();it.hasNext();){String name=it.next();if(!name.matches("[a-zA-Z0-9_.-]+\\.json"))throw new IllegalArgumentException("Invalid manifest filename");byte[] content=reader.read(name);if(!sha(content).equals(files.getString(name)))throw new IllegalArgumentException("Pack checksum mismatch: "+name);Object parsed=new JSONTokener(new String(content,StandardCharsets.UTF_8)).nextValue();if(parsed instanceof JSONObject)verified.put(name,(JSONObject)parsed);else if(!(parsed instanceof JSONArray))throw new IllegalArgumentException("Unexpected manifest JSON root: "+name);}
        calendar=required(verified,"calendar_execution_contract.json");global=required(verified,"calendar_global_constraints_candidate.json");registry=required(verified,"execution_registry.json");
        JSONObject window=calendar.getJSONObject("coverage").getJSONObject("calendarSourceWindow");
        firstSlot=window.getJSONObject("startInclusive").getInt("ordinal")*2;
        horizon=(window.getJSONObject("endInclusive").getInt("ordinal")+1)*2;
        for(String slug:new String[]{"magician","chariot"}){
            JSONObject model=required(verified,slug+"_base_model.json");if(!model.getString("entity_id").equals("p3r.social_link."+slug))throw new IllegalArgumentException("Entity/model mismatch");
            if(!registryMode(slug).equals("lower_bound_model"))throw new IllegalArgumentException("Registry does not authorize base model");models.put(slug,model);
            JSONArray events=model.getJSONArray("rank_events");for(int i=0;i<events.length();i++)eventIds.add(events.getJSONObject(i).getString("rule_id"));
        }
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
        for(String slug:new String[]{"magician","chariot"})for(String suffix:new String[]{"rank","normal","alreadyUnlocked","ready","readyRank","affinityLowerBound","affinityRank","affinityValid"})fields.add(field(slug,suffix));
        JSONObject policy=optionPolicy==null?new JSONObject():copy(optionPolicy);validatePolicy(policy);
        String status="conditional";
        if(!version.equals(checkpoint.optString("rules_version"))){status="unsupported";diagnostic(diagnostics,"rules_version_mismatch",null,null,"请确认实际状态并建立扩展规则的新基线；不会静默修改旧规则版本。",false);}
        if(!"P3R-base".equals(context.optString("gameEdition"))){status="unsupported";diagnostic(diagnostics,"unsupported_edition",null,null,"需要明确的 P3R-base 游戏版本。",false);}
        if(slot<firstSlot){status="requires_story_progression_to_planning_boundary";diagnostic(diagnostics,"story_planning_boundary",null,null,"当前处于规则日历之前；先完成到 4 月 21 日的必要剧情，再核对实际状态。",true);}
        else if(slot>horizon){status="unsupported";diagnostic(diagnostics,"outside_source_window",null,null,"当前日期不在本次来源日历范围。",false);}
        Object contextDay=path(context,"checkpoint.date.ordinal");
        if(!nil(contextDay)&&strictInt(contextDay,"checkpoint date ordinal")!=slot/2){status="unknown";diagnostic(diagnostics,"checkpoint_date_mismatch",null,null,"日历上下文与当前实际日期不一致，请重新核对。",true);}
        if(!Boolean.TRUE.equals(path(context,"checkpoint.consistencyValidated"))&&status.equals("conditional")){status="unknown";diagnostic(diagnostics,"checkpoint_consistency_unknown",null,null,"请核对当前日期、等级和关系状态是否属于同一个实际存档。",true);}
        JSONArray views=calendar.getJSONArray("minimalTwoLinkCandidateDates");
        for(int i=0;i<views.length();i++){JSONObject view=views.getJSONObject(i),date=view.getJSONObject("date");int index=date.getInt("ordinal")*2+(view.getString("slot").equals("evening")?1:0);labels.put(String.valueOf(index),new JSONObject().put("date",date.getString("mmdd")).put("period",view.getString("slot")));}
        if(status.equals("conditional"))for(String slug:new String[]{"magician","chariot"}){
            Long rank=exact(facts,field(slug,"rank")),normal=exact(facts,field(slug,"normal")),unlocked=exact(facts,field(slug,"alreadyUnlocked"));
            if(rank==null||normal==null){diagnostic(diagnostics,"current_link_state_unknown",slug,null,"当前可见等级或关系状态未知，不会猜测可升级。",true);continue;}
            if(rank<1||rank>9||normal!=1||Long.valueOf(0).equals(unlocked)){diagnostic(diagnostics,"current_link_state_blocked",slug,rank.intValue(),"当前社群未处于已解锁、正常且可继续升级的状态。",false);continue;}
            if(slot/2<(slug.equals("magician")?21:22)){diagnostic(diagnostics,"checkpoint_precedes_known_introduction",slug,rank.intValue(),"当前社群等级与来源中的最早介绍日期冲突，请核对当前存档。",true);continue;}
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
            if(!ready){checkpoint(checks,slug,rank.intValue(),"当前升级准备状态未得到确认；先在游戏里观察，不会把未知当成可升级。");continue;}
            facts.put(field(slug,"ready"),interval(1,1));facts.put(field(slug,"readyRank"),interval(rank,rank));
            int from=rank.intValue();
            while(from<10&&ready){
                int to=from+1;JSONObject event=event(slug,to);
                if(event.has("executable")&&!event.getBoolean("executable")){checkpoint(checks,slug,from,"该等级事件存在未解决的来源问题，暂停预测。");break;}
                JSONObject gain=eventGain(slug,to,policy);
                if(gain.getBoolean("forecast_max_policy")&&!contains(assumptions,"A.source_max_base_points_forecast_only"))assumptions.put("A.source_max_base_points_forecast_only");
                Long bound=gain.isNull("lower_bound")?null:gain.getLong("lower_bound");
                Integer nextGate=to<10?threshold(slug,to+1):null;
                boolean nextReady=to<10&&(nextGate==0||(bound!=null&&bound>=nextGate));
                JSONObject assignments=new JSONObject().put(field(slug,"rank"),interval(to,to))
                        .put(field(slug,"affinityLowerBound"),bound==null?JSONObject.NULL:interval(bound,null))
                        .put(field(slug,"affinityRank"),interval(to,to))
                        .put(field(slug,"affinityValid"),interval(bound==null?0:1,bound==null?0:1));
                JSONArray forgotten=new JSONArray();
                if(nextReady){assignments.put(field(slug,"ready"),interval(1,1));assignments.put(field(slug,"readyRank"),interval(to,to));}
                else {forgotten.put(field(slug,"ready")).put(field(slug,"readyRank"));}
                JSONObject minimums=new JSONObject().put(field(slug,"rank"),from).put(field(slug,"normal"),1).put(field(slug,"alreadyUnlocked"),1).put(field(slug,"ready"),1).put(field(slug,"readyRank"),from);
                JSONObject maximums=new JSONObject().put(field(slug,"rank"),from).put(field(slug,"normal"),1).put(field(slug,"alreadyUnlocked"),1).put(field(slug,"ready"),1).put(field(slug,"readyRank"),from);
                String id="p3r.expanded."+slug+".rank_"+String.format(Locale.ROOT,"%02d",to);
                JSONObject evidence=new JSONObject().put("model_event",event.getString("rule_id")).put("incoming_gate",threshold(slug,to)).put("calendar_sources",sources).put("manifest_sha256",MANIFEST_SHA256);
                JSONObject action=new JSONObject().put("id",id).put("slots",new JSONArray(eligible)).put("requirements",minimums).put("maximums",maximums).put("assignments",assignments).put("forget_fields",forgotten).put("verification","corroborated").put("source",evidence.toString());
                actions.put(action);
                metadata.put(id,new JSONObject().put("name_zh_cn",models.get(slug).getString("name_zh_cn")).put("display_title",models.get(slug).getString("name_zh_cn")+" · "+from+"→"+to).put("entity",slug).put("from_rank",from).put("to_rank",to).put("source_event",event.getString("rule_id")).put("event_gain",gain).put("next_ready",nextReady?Boolean.TRUE:JSONObject.NULL).put("assumptions",assumptions).put("calendar_guard_basis","validated_checkpoint_and_applicable_source_guards").put("source_option_order_warning","来源英文选项序号；不是中文游戏界面的选项映射").put("exact_affinity_balance_claimed",false));
                from=to;ready=nextReady;
                if(!ready&&to<10)checkpoint(checks,slug,to,"已知来源选项的保守下界不足以证明下一次可升级；需新的实际准备状态观察。");
            }
        }
        JSONObject rules=new JSONObject().put("version",version).put("horizon",horizon).put("fields",new JSONArray(fields)).put("actions",actions).put("coverage_complete",false).put("synthetic",false);
        JSONObject engine=new JSONObject().put("run_id",checkpoint.getString("run_id")).put("rules_version",version).put("revision",checkpoint.getLong("revision")).put("slot",slot).put("values",facts).put("visits",copy(checkpoint.optJSONObject("visits_by_stage")==null?new JSONObject():checkpoint.getJSONObject("visits_by_stage")));
        return new JSONObject().put("status",status).put("supported_goal_fields",new JSONArray().put(field("magician","rank")).put(field("chariot","rank"))).put("requires_rebase",!version.equals(checkpoint.optString("rules_version"))).put("rules",rules).put("engine_snapshot",engine).put("labels",labels).put("action_metadata",metadata).put("calendar_candidates",candidates).put("diagnostics",diagnostics).put("observation_checkpoints",checks).put("derived_checkpoint_facts",derived).put("source_policy_declared",Boolean.TRUE.equals(policy.opt("declared"))).put("planning_boundary_slot",firstSlot).put("observed_state_mutated",false).put("scope","partial_source_model_magician_chariot_only");
    }

    /** Convenience wrapper preserves unknown/unsupported outcomes and exposes stops. */
    public JSONObject plan(JSONObject observed,JSONObject calendarContext,JSONObject optionPolicy,JSONArray goals,JSONObject constraints,int beamWidth,int maxExpansions)throws Exception {
        JSONObject built=build(observed,calendarContext,optionPolicy),rules=built.getJSONObject("rules"),initial=built.getJSONObject("engine_snapshot");
        for(int i=0;i<goals.length();i++){String field=goals.getJSONObject(i).getString("field");if(!field.equals(field("magician","rank"))&&!field.equals(field("chariot","rank"))){built.put("status","unsupported");diagnostic(built.getJSONArray("diagnostics"),"unsupported_goal",null,null,"这个适配器只提供魔术师和战车的有限来源模型。",false);}}
        JSONObject result;
        if(!built.getString("status").equals("conditional")){result=new JSONObject().put("status",built.getString("status").equals("requires_story_progression_to_planning_boundary")?"unknown":built.getString("status")).put("steps",new JSONArray()).put("forecast",initial).put("explanations",new JSONArray().put("当前状态需要核对或规则范围不支持；不会据此判定全目标不可能。"));}
        else {JSONObject scoped=copy(constraints);scoped.put("visit_caps",new JSONObject());result=PlannerEngine.plan(rules,initial,goals,scoped,beamWidth,maxExpansions);}
        result.put("preserved_constraints",copy(constraints)).put("dormant_visit_caps",constraints.optJSONObject("visit_caps")==null?new JSONObject():copy(constraints.getJSONObject("visit_caps"))).put("contains_dungeon_actions",false);
        if(constraints.optJSONObject("visit_caps")!=null&&constraints.getJSONObject("visit_caps").length()>0)diagnostic(built.getJSONArray("diagnostics"),"dormant_social_only_visit_caps",null,null,"爬塔上限仍被保留；此候选只含社群行动，上限暂不参与计算。加入爬塔行动时必须重新启用。",false);
        result.put("supported_goal_fields",built.getJSONArray("supported_goal_fields")).put("adapter_status",built.getString("status")).put("action_metadata",built.getJSONObject("action_metadata")).put("diagnostics",built.getJSONArray("diagnostics")).put("observation_checkpoints",built.getJSONArray("observation_checkpoints")).put("derived_checkpoint_facts",built.getJSONObject("derived_checkpoint_facts")).put("source_policy_declared",built.getBoolean("source_policy_declared")).put("model_scope","partial_source_model_magician_chariot_only").put("observed_state_mutated",false).put("optimality_proven",false).put("manifest_sha256",MANIFEST_SHA256);
        return result;
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
        return out.put("capacity_basis",slot==checkpointSlot?"observed_current_slot":"future_single_slot_resource_model").put("entity_guard_basis","normal_validated_checkpoint_no_applicable_rescue_guard");
    }

    private boolean initialReady(String slug,int rank,JSONObject facts,JSONObject derived,JSONArray diagnostics)throws Exception {
        Long ready=exact(facts,field(slug,"ready")),readyRank=exact(facts,field(slug,"readyRank"));
        if(readyRank!=null&&readyRank==rank&&ready!=null){
            if(ready==1)return true;
            if(ready==0){diagnostic(diagnostics,"observed_not_ready",slug,rank,"当前实际观察为尚不可升级；来源下界不会覆盖这个观察。",true);return false;}
        }
        facts.put(field(slug,"ready"),interval(null,null));facts.put(field(slug,"readyRank"),interval(null,null));
        int gate=threshold(slug,rank+1);
        Long valid=exact(facts,field(slug,"affinityValid")),affinityRank=exact(facts,field(slug,"affinityRank"));
        JSONObject bound=facts.optJSONObject(field(slug,"affinityLowerBound"));Long lower=bound==null||bound.isNull("low")?null:strictLong(bound.get("low"),"affinity lower bound");
        if(Long.valueOf(1).equals(valid)&&affinityRank!=null&&affinityRank==rank&&lower!=null&&lower>=gate){derived.put(field(slug,"ready"),"current_rank_provenance_bound_reaches_gate_"+gate);return true;}
        if(gate==0&&!Long.valueOf(0).equals(valid)){derived.put(field(slug,"ready"),"source_zero_gate_no_affinity_value_assigned");return true;}
        return false;
    }

    private JSONObject eventGain(String slug,int toRank,JSONObject policy)throws Exception {
        JSONObject event=event(slug,toRank);JSONArray prompts=event.getJSONArray("prompts");
        JSONObject selections=policy.optJSONObject("events");boolean maximum=Boolean.TRUE.equals(policy.opt("declared"))&&"source_max_base_points".equals(policy.optString("mode"));
        JSONArray selected=Boolean.TRUE.equals(policy.opt("declared"))&&selections!=null?selections.optJSONArray(event.getString("rule_id")):null;
        if(maximum){
            selected=new JSONArray();
            for(int i=0;i<prompts.length();i++){
                JSONObject prompt=prompts.getJSONObject(i);
                if(prompt.has("conditions")||prompt.has("branch")||prompt.has("requires"))throw new IllegalArgumentException("Unresolved event branch cannot use maximum-base forecast policy");
                JSONArray options=prompt.getJSONArray("options");long best=Long.MIN_VALUE;int ordinal=-1;
                for(int j=0;j<options.length();j++){JSONObject option=options.getJSONObject(j);if(option.has("conditions")||option.has("branch")||option.has("requires"))throw new IllegalArgumentException("Unresolved option branch cannot use maximum-base forecast policy");long points=strictLong(option.get("raw_affinity_points"),"raw affinity points");int candidate=option.getInt("source_option_ordinal");if(points>best||(points==best&&(ordinal<0||candidate<ordinal))){best=points;ordinal=candidate;}}
                if(ordinal<0)throw new IllegalArgumentException("No source options for maximum-base policy");selected.put(ordinal);
            }
        }
        if(selected!=null&&selected.length()>prompts.length())throw new IllegalArgumentException("Too many source options for "+event.getString("rule_id"));
        long sum=0;int known=0;JSONArray recorded=new JSONArray();
        for(int i=0;i<prompts.length();i++){
            Object choice=selected==null?null:selected.opt(i);if(nil(choice)){recorded.put(JSONObject.NULL);continue;}
            int ordinal=strictInt(choice,"source option ordinal");JSONArray options=prompts.getJSONObject(i).getJSONArray("options");JSONObject match=null;
            for(int j=0;j<options.length();j++)if(options.getJSONObject(j).getInt("source_option_ordinal")==ordinal)match=options.getJSONObject(j);
            if(match==null)throw new IllegalArgumentException("Unknown source-option ordinal");long points=strictLong(match.get("raw_affinity_points"),"raw affinity points");
            if(points<0)throw new IllegalArgumentException("Negative affinity outcome requires observation, not this base model");sum=Math.addExact(sum,points);known++;recorded.put(ordinal);
        }
        if(maximum&&sum!=event.getLong("optimal_base_gain"))throw new IllegalArgumentException("Maximum policy disagrees with audited event total");
        return new JSONObject().put("lower_bound",known>0||prompts.length()==0?sum:JSONObject.NULL).put("upper_bound",JSONObject.NULL).put("known_source_choices",known).put("prompt_count",prompts.length()).put("selected_source_options",recorded).put("all_choices_known",known==prompts.length()).put("forecast_max_policy",maximum).put("source_ordinals_are_game_instructions",false).put("bonus_lookup_enabled",false).put("replaces_prior_rank_knowledge",true).put("is_exact_game_balance",false);
    }

    private void validatePolicy(JSONObject policy)throws Exception {
        JSONObject events=policy.optJSONObject("events");boolean declared=Boolean.TRUE.equals(policy.opt("declared"));
        if(declared&&!"source_option_ordinals".equals(policy.optString("mode"))&&!"source_max_base_points".equals(policy.optString("mode")))throw new IllegalArgumentException("An explicit supported source policy is required");
        if("source_max_base_points".equals(policy.optString("mode"))&&events!=null&&events.length()>0)throw new IllegalArgumentException("Do not mix maximum forecast policy with manually selected event options");
        if(events!=null&&events.length()>0&&!declared)throw new IllegalArgumentException("Source options cannot be assumed without an explicit declared policy");
        if(events!=null)for(Iterator<String> it=events.keys();it.hasNext();){String id=it.next();if(!eventIds.contains(id)||!(events.get(id)instanceof JSONArray))throw new IllegalArgumentException("Unknown or malformed source-policy event");}
    }

    private JSONObject event(String slug,int to)throws Exception {JSONArray events=models.get(slug).getJSONArray("rank_events");for(int i=0;i<events.length();i++)if(events.getJSONObject(i).getInt("to_rank")==to)return events.getJSONObject(i);throw new IllegalArgumentException("Missing rank event");}
    private int threshold(String slug,int to)throws Exception {JSONArray gates=models.get(slug).getJSONArray("incoming_thresholds");for(int i=0;i<gates.length();i++)if(gates.getJSONObject(i).getInt("to_rank")==to)return gates.getJSONObject(i).getInt("required_raw_affinity");throw new IllegalArgumentException("Missing incoming gate");}
    private String registryMode(String slug)throws Exception {JSONArray entities=registry.getJSONArray("entities");for(int i=0;i<entities.length();i++)if(entities.getJSONObject(i).getString("entityId").equals("p3r.social_link."+slug))return entities.getJSONObject(i).getString("mode");return "";}
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
