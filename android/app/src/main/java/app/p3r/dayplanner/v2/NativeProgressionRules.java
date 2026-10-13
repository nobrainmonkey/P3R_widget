package app.p3r.dayplanner.v2;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded audited progression adapter. It never writes observed state or infers tower income.
 * Explicit per-slot guard observations live at calendar.statActionStates[slot][sourceActionId].
 * Source guard names are flat keys there. Missing guards remain unknown.
 */
public final class NativeProgressionRules {
    public static final String DEFAULT_ROOT="planner_v2/rules_v051/";
    public static final String MANIFEST_SHA256="4928f5b9435d98f194a61106540a3b311cc6b756d5700c7d335805f426e760c9";
    public final String version="p3r-progression-0.5.1-"+MANIFEST_SHA256.substring(0,12)+"-a6";
    private final JSONArray stageRules,statRules,activities;
    private final JSONObject story,dungeon,january;
    public static final String DUNGEON_SUPPLEMENT_SHA256="e5a0f50e30a066c5e72c16927f3b0696e04d3aefd83a450a32849a2e60413bdd";
    private final Set<Integer> locks=new HashSet<>(),dungeonLocks=new HashSet<>();
    public final int firstSlot,horizon;
    private interface Reader{byte[] read(String name)throws Exception;}
    public NativeProgressionRules(Context context)throws Exception{this(context,DEFAULT_ROOT);}
    public NativeProgressionRules(Context context,String root)throws Exception{this(name->{try(InputStream in=context.getAssets().open(root+name)){return bytes(in);}});}
    public NativeProgressionRules(File directory)throws Exception{this(name->{File f=new File(directory,name);if(!f.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IllegalArgumentException("Invalid path");try(InputStream in=new FileInputStream(f)){return bytes(in);}});}
    private NativeProgressionRules(Reader reader)throws Exception{
        byte[] raw=reader.read("manifest.json");if(!sha(raw).equals(MANIFEST_SHA256))throw new IllegalArgumentException("Pinned 0.5.1 manifest mismatch");
        JSONObject manifest=parse(raw);if(!manifest.getString("packVersion").equals("0.5.1")||!manifest.getString("gameEdition").equals("P3R-base"))throw new IllegalArgumentException("Wrong edition/version");
        Map<String,JSONObject> data=new HashMap<>();JSONObject files=manifest.getJSONObject("files");
        for(String name:new String[]{"stage_identification_contract.json","stats_actions.json","stat_activity_expansion_candidate.json","stat_lower_bound_adapter_contract.json","calendar_global_constraints_candidate.json","calendar_execution_contract.json","global_slot_locks_expansion_candidate.json","story_link_transitions_candidate.json","jobs_wage_rules_candidate.json","calendar_january_slice.json","playable_actions.json"}){
            byte[] content=reader.read(name);if(!sha(content).equals(files.getString(name)))throw new IllegalArgumentException("Checksum mismatch: "+name);data.put(name,parse(content));}
        byte[] dungeonBytes=reader.read("tartarus_entry_availability_supplement.json");if(!sha(dungeonBytes).equals(DUNGEON_SUPPLEMENT_SHA256))throw new IllegalArgumentException("Dungeon supplement checksum mismatch");dungeon=parse(dungeonBytes);
        if(!"P3R-base".equals(dungeon.getString("edition")))throw new IllegalArgumentException("Wrong dungeon edition");
        january=data.get("calendar_january_slice.json");
        story=data.get("story_link_transitions_candidate.json");
        stageRules=data.get("stage_identification_contract.json").getJSONArray("rules");statRules=data.get("stats_actions.json").getJSONArray("statRules");activities=new JSONArray();
        for(String name:new String[]{"stats_actions.json","stat_activity_expansion_candidate.json"}){JSONArray source=data.get(name).getJSONArray("actions");for(int i=0;i<source.length();i++)activities.put(source.getJSONObject(i));}
        // Income-only projection: the Temperance prerequisite protects exact job STAT gains,
        // not wages. All job stat effects are deliberately omitted (Wiki451 + audited wage model).
        JSONObject wage=data.get("jobs_wage_rules_candidate.json").getJSONArray("guardedMinimumIncomeActions").getJSONObject(0);
        if(!"p3r.action.job_chagall_minimum".equals(wage.getString("ruleId")))throw new IllegalArgumentException("Unexpected wage rule");
        int minimum=-1;JSONArray wageEffects=wage.getJSONArray("effects");for(int i=0;i<wageEffects.length();i++){JSONObject effect=wageEffects.getJSONObject(i);if(effect.getString("field").equals("yenLowerBound")&&effect.getString("op").equals("add"))minimum=effect.getInt("value");}
        if(minimum!=2500)throw new IllegalArgumentException("Unexpected pinned minimum wage");
        JSONArray wageGuards=new JSONArray();for(int i=0;i<wage.getJSONArray("preconditions").length();i++){JSONObject guard=wage.getJSONArray("preconditions").getJSONObject(i);if(!guard.getString("field").equals("temperanceStatBonusActive"))wageGuards.put(new JSONObject(guard.toString()));}
        activities.put(new JSONObject().put("actionId","job_chagall_minimum").put("engineActionId",wage.getString("ruleId")).put("labelZhHans",wage.getString("labelZh")).put("planningStatus","enabled_with_preconditions").put("repeatable",true).put("oneUse",false).put("slots",new JSONArray().put("night")).put("isoWeekdays",wage.getJSONArray("isoWeekdays")).put("slotCost",wage.getInt("slotCost")).put("yenCost",wage.getInt("yenCost")).put("incomeLowerBound",minimum).put("statDelta",new JSONObject()).put("prerequisites",wageGuards));
        JSONObject calendar=data.get("calendar_execution_contract.json"),window=calendar.getJSONObject("coverage").getJSONObject("calendarSourceWindow");firstSlot=window.getJSONObject("startInclusive").getInt("ordinal")*2;horizon=(window.getJSONObject("endInclusive").getInt("ordinal")+1)*2;
        JSONArray hard=data.get("calendar_global_constraints_candidate.json").getJSONArray("explicitActionLockClaims");for(int i=0;i<hard.length();i++){JSONObject item=hard.getJSONObject(i);JSONArray slots=item.getJSONArray("slots");for(int j=0;j<slots.length();j++)locks.add(item.getJSONObject("date").getInt("ordinal")*2+(slots.getString(j).equals("evening")?1:0));}
        JSONObject supplement=data.get("global_slot_locks_expansion_candidate.json");
        JSONArray retained=supplement.getJSONArray("retainedLockClaims");
        for(int i=0;i<retained.length();i++){JSONObject claim=retained.getJSONObject(i);JSONArray periods=claim.getJSONArray("slots");for(int j=0;j<periods.length();j++)locks.add(claim.getJSONObject("date").getInt("ordinal")*2+(periods.getString(j).equals("evening")?1:0));}
        JSONArray extra=supplement.getJSONArray("newWholeSlotLockClaims");
        for(int i=0;i<extra.length();i++){JSONObject claim=extra.getJSONObject(i);if(!claim.optBoolean("globalOrdinarySlotLockClaim"))continue;JSONArray dates=claim.getJSONArray("dates"),periods=claim.getJSONArray("slots");for(int d=0;d<dates.length();d++)for(int j=0;j<periods.length();j++)locks.add(dates.getJSONObject(d).getInt("ordinal")*2+(periods.getString(j).equals("evening")?1:0));}
        JSONArray restricted=supplement.getJSONArray("dungeonEntryOnlyRestrictions");for(int i=0;i<restricted.length();i++){JSONObject claim=restricted.getJSONObject(i);if(claim.optBoolean("globalSlotLock"))throw new IllegalArgumentException("Unexpected dungeon-only restriction");dungeonLocks.add(claim.getJSONObject("date").getInt("ordinal")*2+(claim.getString("slot").equals("evening")?1:0));}
        JSONArray exclusions=calendar.getJSONArray("safeHardExclusions");for(int i=0;i<exclusions.length();i++)if(exclusions.getJSONObject(i).optString("kind").equals("mandatory_Aug16_festival")){locks.add(274);locks.add(275);}
    }
    public boolean isOrdinarySlotLocked(int slot){return locks.contains(slot);}
    public boolean isDungeonSlotLocked(int slot){return locks.contains(slot)||dungeonLocks.contains(slot);}
    /** Informational milestones only: no actions, rank assignments or free-slot awards. */
    public JSONArray storyGoalMilestones(JSONObject observed,JSONArray goals)throws Exception {
        JSONArray out=new JSONArray(),events=story.getJSONArray("transitions");JSONObject facts=observed.getJSONObject("facts"),context=observed.optJSONObject("planning_context");
        JSONObject confirmations=context==null?null:context.optJSONObject("confirmedStoryMilestones");
        for(int g=0;g<goals.length();g++){
            JSONObject goal=goals.getJSONObject(g);String field=goal.getString("field");
            if(!field.matches("socialLinks\\.(fool|death|judgement)\\.rank"))continue;
            int target=strictInt(goal.get("target"));if(target<1||target>10)throw new IllegalArgumentException("Story goal rank must be 1–10");
            String slug=field.split("\\.")[1];JSONObject current=facts.optJSONObject(field);Long low=bound(current,"low"),high=bound(current,"high");
            if(low!=null&&low>=target)continue;
            for(int i=0;i<events.length();i++){
                JSONObject event=events.getJSONObject(i);if(!event.getString("entityId").equals("p3r.social_link."+slug))continue;
                int from=event.getInt("fromRank"),to=event.getInt("toRank");if(from>=target||low!=null&&to<=low)continue;
                String flag=event.getJSONObject("trigger").getString("flagId");JSONObject confirmation=confirmations==null?null:confirmations.optJSONObject(flag);
                boolean confirmed=confirmation!=null&&Boolean.TRUE.equals(confirmation.opt("completed"))&&observed.getString("run_id").equals(confirmation.optString("run_id"));
                Integer expectedSlot=null,expectedDay=null;String expectedDate=event.isNull("expectedMonthDay")?null:event.getString("expectedMonthDay");
                if(expectedDate!=null){String[] parts=expectedDate.split("-");expectedDay=GameDate.slot(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),false)/2;}
                if(expectedDate!=null&&("daytime".equals(event.optString("eventPhase"))||"evening".equals(event.optString("eventPhase")))){String[] parts=expectedDate.split("-");expectedSlot=GameDate.slot(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),"evening".equals(event.optString("eventPhase")));}
                boolean next=low!=null&&Objects.equals(low,high)&&low==from;
                String status=confirmed?"confirm_actual_resulting_rank":next?"pending_event_confirmation":"pending_prerequisite_or_current_rank";
                JSONObject milestone=new JSONObject().put("rule_id",event.getString("ruleId")).put("goal_field",field).put("entity",slug).put("display_title",event.getString("nameZhCn")+" · "+from+"→"+to+" 剧情进度确认")
                    .put("from_rank",from).put("to_rank",to).put("current_rank",current==null?interval(null,null):new JSONObject(current.toString())).put("expected_date",expectedDate==null?JSONObject.NULL:expectedDate).put("expected_slot",expectedSlot==null?JSONObject.NULL:expectedSlot)
                    .put("earliest_date",event.opt("earliestMonthDay")==null?JSONObject.NULL:event.opt("earliestMonthDay")).put("event_phase",event.getString("eventPhase")).put("status",status).put("trigger_flag",flag).put("completed_event_observed",confirmed)
                    .put("conditions",new JSONArray(event.getJSONArray("conditions").toString())).put("source_description",event.getString("sourceEventDescription")).put("source_evidence",new JSONArray(event.getJSONArray("evidence").toString())).put("source_catalog",new JSONArray(story.getJSONArray("sources").toString()))
                    .put("manifest_sha256",MANIFEST_SHA256).put("actual_event_confirmation_required",true).put("date_alone_sufficient",false).put("highest_floor_alone_sufficient",false).put("informational_only",true).put("expected_date_ordinal",expectedDay==null?JSONObject.NULL:expectedDay).put("past_expected_date",expectedDay!=null&&expectedDay<observed.getInt("game_slot")/2);
                if(event.has("tartarusMilestone"))milestone.put("tartarus_milestone",new JSONObject(event.getJSONObject("tartarusMilestone").toString()));out.put(milestone);
            }
        }return out;
    }
    /** Conditional reservations only. Completing a visit never promises income, floors or rescues. */
    public JSONObject dungeonCandidates(JSONObject observed,JSONObject context,JSONObject constraints)throws Exception {
        JSONArray actions=new JSONArray(),diagnostics=new JSONArray(),checks=new JSONArray();JSONObject metadata=new JSONObject();
        int start=strictInt(observed.get("game_slot"));JSONObject t=context.optJSONObject("tartarus"),calendar=context.optJSONObject("calendar"),states=calendar==null?null:calendar.optJSONObject("slotStates");
        JSONObject caps=constraints.optJSONObject("visit_caps"),used=observed.optJSONObject("visits_by_stage");
        JSONObject cp=context.optJSONObject("checkpoint"),date=cp==null?null:cp.optJSONObject("date");
        boolean valid="P3R-base".equals(context.optString("gameEdition"))&&cp!=null&&Boolean.TRUE.equals(cp.opt("consistencyValidated"))&&date!=null&&date.optInt("ordinal",-1)==start/2;
        if(!valid||t==null)return new JSONObject().put("actions",actions).put("action_metadata",metadata).put("observation_checkpoints",checks).put("diagnostics",diagnostics.put(diag("dungeon_context_unknown",null,"需核对当前状态及探索条件")));
        JSONObject currentStage=identifyStage(start,observed.isNull("active_stage")?null:observed.getString("active_stage"),observed.isNull("highest_floor")?null:strictInt(observed.get("highest_floor")));
        if("needs_reconciliation".equals(currentStage.getString("status")))return new JSONObject().put("actions",actions).put("action_metadata",metadata).put("observation_checkpoints",checks.put(new JSONObject().put("kind","reconcile_stage_and_floor").put("stage_observation",currentStage))).put("diagnostics",diagnostics);
        Map<Integer,JSONObject> witnesses=new HashMap<>();JSONArray sourceDates=dungeon.getJSONArray("positiveNightWitnesses");for(int i=0;i<sourceDates.length();i++){JSONObject witness=sourceDates.getJSONObject(i);witnesses.put(witness.getJSONObject("date").getInt("ordinal")*2+1,witness);}
        JSONArray januaryWitnesses=january.getJSONArray("explorationNightAllowlist");
        for(int i=0;i<januaryWitnesses.length();i++){JSONObject witness=januaryWitnesses.getJSONObject(i);if(!"corroborated".equals(witness.optString("verificationStatus"))||!"evening".equals(witness.optString("slot")))continue;int witnessedSlot=witness.getJSONObject("date").getInt("ordinal")*2+1;if(!witnesses.containsKey(witnessedSlot))witnesses.put(witnessedSlot,new JSONObject(witness.toString()).put("evidence_file","calendar_january_slice.json").put("manifest_sha256",MANIFEST_SHA256));}
        boolean storyAssumed=Boolean.TRUE.equals(t.opt("normalStoryProgressionAssumed")),routine=Boolean.TRUE.equals(t.opt("assumeRoutineFutureEntry"));
        Map<String,JSONArray> grouped=new TreeMap<>();Set<String> reported=new HashSet<>();
        for(int slot=Math.max(start,GameDate.slot(4,21,true));slot<=GameDate.slot(1,30,true)&&slot<horizon;slot++){
            if(slot%2==0||isDungeonSlotLocked(slot))continue;JSONObject state=states==null?null:states.optJSONObject(""+slot);
            if(specialSlotGuard(slot,context,state)<0)continue;
            if(state!=null&&(Boolean.TRUE.equals(state.opt("knownStoryLock"))||state.opt("remainingCapacity") instanceof Number&&strictInt(state.get("remainingCapacity"))<1))continue;
            if(slot==start&&(t.isNull("entryObservationDateOrdinal")||t.optInt("entryObservationDateOrdinal",-1)==slot/2)&&(Boolean.TRUE.equals(t.opt("departureBlockedObserved"))||Boolean.TRUE.equals(t.opt("navigatorUnavailableObserved"))))continue;
            String tier;
            if(slot==start){
                if(state==null||!(state.opt("remainingCapacity") instanceof Number)||strictInt(state.get("remainingCapacity"))<1||!Boolean.FALSE.equals(state.opt("knownStoryLock")))continue;
                if(!Boolean.TRUE.equals(t.opt("entryAvailableObserved"))||t.optInt("entryObservationDateOrdinal",-1)!=slot/2||!"evening".equals(t.optString("entryObservationSlot"))||!Boolean.TRUE.equals(t.opt("optionalExplorationUnlockedObserved")))continue;
                tier="observed_current_entry";
            }else{
                if(!storyAssumed)continue;
                if(witnesses.containsKey(slot))tier="source_positive_future_night";
                else if(routine)tier="routine_future_evening";else continue;
            }
            String manual=slot==start&&!observed.isNull("active_stage")?observed.getString("active_stage"):null;
            JSONObject identity=identifyStage(slot,manual,slot==start&&!observed.isNull("highest_floor")?strictInt(observed.get("highest_floor")):null);
            if(!"identified".equals(identity.getString("status"))||identity.isNull("date_stage_id")){
                if(slot==start)checks.put(new JSONObject().put("kind","resolve_operational_stage_gap").put("slot",slot).put("message","当前阶段边界需人工核对，本候选不占用新的阶段次数").put("stage_observation",identity));continue;
            }
            String stage=identity.getString("stage_id");Object count=used==null?null:used.opt(stage),cap=caps==null?null:caps.opt(stage);
            if(count==null||count==JSONObject.NULL||cap==null||cap==JSONObject.NULL){if(reported.add(stage))diagnostics.put(diag("dungeon_cap_or_used_count_unknown",stage,"探索候选要求已知阶段上限和已用次数；未知不当作0"));continue;}
            int knownCount=strictInt(count),knownCap=strictInt(cap);if(knownCount<0||knownCap<0)throw new IllegalArgumentException("Negative visit allowance");if(knownCount>=knownCap)continue;
            String id="p3r.tartarus."+stage.substring(stage.lastIndexOf('.')+1)+"."+tier;JSONArray slots=grouped.get(id);if(slots==null){slots=new JSONArray();grouped.put(id,slots);}slots.put(slot);
            metadata.put(id,new JSONObject().put("display_title","探索塔尔塔罗斯").put("stage_id",stage).put("availability_tier",tier).put("guaranteed_entry",false).put("expected_money",JSONObject.NULL).put("expected_floor",JSONObject.NULL).put("before_entry_check_required",true).put("supplement_sha256",DUNGEON_SUPPLEMENT_SHA256).put("source_catalog",new JSONArray(dungeon.getJSONArray("sources").toString())));
            JSONObject check=new JSONObject().put("kind","verify_tartarus_departure").put("action_id",id).put("slot",slot).put("stage_id",stage).put("availability_tier",tier).put("message","在宿舍确认今晚能否探索；不能出发则释放预约，不增加已用次数").put("expected_money",JSONObject.NULL).put("expected_floor",JSONObject.NULL).put("guaranteed_entry",false);
            if(witnesses.containsKey(slot))check.put("source_witness",new JSONObject(witnesses.get(slot).toString()));checks.put(check);
        }
        for(Map.Entry<String,JSONArray> entry:grouped.entrySet())actions.put(new JSONObject().put("id",entry.getKey()).put("slots",entry.getValue()).put("dungeon_visit",true).put("strict_requirements",true).put("verification","corroborated").put("source",DUNGEON_SUPPLEMENT_SHA256+":conditional_entry_only"));
        return new JSONObject().put("actions",actions).put("action_metadata",metadata).put("observation_checkpoints",checks).put("diagnostics",diagnostics).put("observed_state_mutated",false);
    }
    public JSONArray operationalStages()throws Exception{
        JSONArray out=new JSONArray();for(int i=0;i<stageRules.length();i++){JSONObject r=stageRules.getJSONObject(i);if(r.optBoolean("identifyFromDateEnabled")&&r.optBoolean("countAndCapAccountingEnabled"))out.put(new JSONObject().put("id",r.getString("stageId")).put("start",r.getJSONObject("startInclusive").getInt("ordinal")*2).put("end",r.getJSONObject("endExclusive").getInt("ordinal")*2).put("floor_ceiling",r.getInt("floorCeiling")));}return out;
    }
    public JSONObject stageWindows()throws Exception{JSONObject out=new JSONObject();JSONArray stages=operationalStages();for(int i=0;i<stages.length();i++){JSONObject s=stages.getJSONObject(i);out.put(s.getString("id"),new JSONArray().put(s.getInt("start")).put(s.getInt("end")));}return out;}
    public JSONObject stageCeilings()throws Exception{JSONObject out=new JSONObject();for(int i=0;i<stageRules.length();i++){JSONObject s=stageRules.getJSONObject(i);if(s.optBoolean("floorBoundsVerified"))out.put(s.getString("stageId"),s.getInt("floorCeiling"));}return out;}
    /** Date identity never grants permission to explore. A gap can use a manually observed stage. */
    public JSONObject identifyStage(int slot,String manualStage,Integer floor)throws Exception{
        if(slot<0||floor!=null&&floor<0)throw new IllegalArgumentException("Invalid slot/floor");String fromDate=null;JSONArray stages=operationalStages();for(int i=0;i<stages.length();i++){JSONObject s=stages.getJSONObject(i);if(slot>=s.getInt("start")&&slot<s.getInt("end"))fromDate=s.getString("id");}
        JSONObject ceilings=stageCeilings();String stage=manualStage==null?fromDate:manualStage;String status=stage==null?"unknown":"identified";
        if(manualStage!=null&&!ceilings.has(manualStage)||manualStage!=null&&fromDate!=null&&!manualStage.equals(fromDate))status="needs_reconciliation";
        if(stage!=null&&ceilings.has(stage)&&floor!=null&&floor>ceilings.getInt(stage))status="needs_reconciliation";
        return new JSONObject().put("status",status).put("stage_id",stage==null?JSONObject.NULL:stage).put("date_stage_id",fromDate==null?JSONObject.NULL:fromDate).put("floor_ceiling",stage!=null&&ceilings.has(stage)?ceilings.get(stage):JSONObject.NULL).put("nightly_exploration_permitted",false).put("used_visits_inferred",false);
    }
    public long rawTargetForRank(String slug,int rank)throws Exception{if(rank<1||rank>6)throw new IllegalArgumentException("Stat rank must be 1–6");return stat(slug).getJSONArray("rankThresholds").getLong(rank-1);}
    private JSONObject stat(String slug)throws Exception{for(int i=0;i<statRules.length();i++)if(statRules.getJSONObject(i).getString("statId").equals(slug))return statRules.getJSONObject(i);throw new IllegalArgumentException("Unknown stat: "+slug);}
    private int rank(String slug,long raw)throws Exception{int result=1;JSONArray thresholds=stat(slug).getJSONArray("rankThresholds");for(int i=0;i<thresholds.length();i++)if(raw>=thresholds.getLong(i))result=i+1;return result;}
    public JSONObject rankIntervals(JSONObject values)throws Exception{JSONObject out=new JSONObject();for(String slug:new String[]{"academics","charm","courage"}){JSONObject v=values.optJSONObject(field(slug,"raw"));Long low=bound(v,"low"),high=bound(v,"high");out.put(field(slug,"rank"),interval(low==null?null:rank(slug,low),high==null?6:rank(slug,high)));}return out;}
    /** Reconciles visible rank with hidden-point interval without replacing it with an exact minimum. */
    public JSONObject deriveStatFacts(JSONObject facts)throws Exception{
        JSONObject out=new JSONObject();for(String slug:new String[]{"academics","charm","courage"}){
            JSONObject raw=facts.optJSONObject(field(slug,"raw")),visible=facts.optJSONObject(field(slug,"rank")),legacy=facts.optJSONObject("stats."+slug);
            if(raw!=null&&legacy!=null&&(!Objects.equals(bound(raw,"low"),bound(legacy,"low"))||!Objects.equals(bound(raw,"high"),bound(legacy,"high"))))throw new IllegalArgumentException("Conflicting raw stat aliases");
            if(raw==null)raw=legacy;
            Long lo=bound(raw,"low"),hi=bound(raw,"high"),rlo=bound(visible,"low"),rhi=bound(visible,"high");
            if(lo!=null&&lo<0||hi!=null&&hi<0)throw new IllegalArgumentException("Negative raw stat");
            if(rlo!=null){if(rlo<1||rlo>6)throw new IllegalArgumentException("Invalid visible rank");lo=max(lo,rawTargetForRank(slug,rlo.intValue()));}
            if(rhi!=null){if(rhi<1||rhi>6)throw new IllegalArgumentException("Invalid visible rank");if(rhi<6)hi=min(hi,rawTargetForRank(slug,rhi.intValue()+1)-1);}
            if(lo!=null&&hi!=null&&lo>hi)throw new IllegalArgumentException("Visible rank/raw point conflict");
            out.put(field(slug,"raw"),interval(lo,hi));
        }JSONObject ranks=rankIntervals(out);for(Iterator<String> it=ranks.keys();it.hasNext();){String key=it.next();out.put(key,ranks.get(key));}return out;
    }
    public JSONObject build(JSONObject observed,JSONObject context,JSONObject optionPolicy)throws Exception{
        int start=strictInt(observed.get("game_slot"));JSONObject derived=deriveStatFacts(observed.getJSONObject("facts")),values=new JSONObject(observed.getJSONObject("facts").toString());
        for(Iterator<String> it=derived.keys();it.hasNext();){String key=it.next();values.put(key,derived.get(key));}
        values.put("cash",interval(observed.isNull("cash")?0:strictInt(observed.get("cash")),observed.isNull("cash")?null:strictInt(observed.get("cash"))));
        for(String slug:new String[]{"academics","charm","courage"})values.remove("stats."+slug);
        JSONArray actions=new JSONArray(),diagnostics=new JSONArray(),supported=new JSONArray(),conditionalCandidates=new JSONArray();JSONObject metadata=new JSONObject(),labels=new JSONObject();Set<String> fields=new TreeSet<>();for(Iterator<String> it=values.keys();it.hasNext();)fields.add(it.next());
        for(String slug:new String[]{"academics","charm","courage"})supported.put(field(slug,"raw"));
        if(observed.isNull("cash"))diagnostics.put(diag("cash_nonnegative_lower_bound",null,"金钱未知，仅在预测中使用非负下界0；不视为实际余额0"));
        JSONObject cal=context.optJSONObject("calendar"),slots=cal==null?null:cal.optJSONObject("slotStates"),guards=cal==null?null:cal.optJSONObject("statActionStates");
        JSONObject progression=context.optJSONObject("progression"),persistent=progression==null?null:progression.optJSONObject("repeatableActivityGuards");
        boolean assumeFuture=progression!=null&&Boolean.TRUE.equals(progression.opt("assumeFutureRoutineAvailability"));
        if(assumeFuture)diagnostics.put(diag("conditional_future_routine_availability",null,"常规营业假设仅提供待确认备选，不计入可靠训练或收入预测"));
        boolean contextValid="P3R-base".equals(context.optString("gameEdition"))&&context.optJSONObject("checkpoint")!=null&&Boolean.TRUE.equals(context.getJSONObject("checkpoint").opt("consistencyValidated"));
        JSONObject cp=context.optJSONObject("checkpoint"),date=cp==null?null:cp.optJSONObject("date");
        contextValid=contextValid&&date!=null&&date.optInt("ordinal",-1)==start/2;
        if(!contextValid)diagnostics.put(diag("checkpoint_context_unknown",null,"版本与当前检查点一致性尚未确认"));
        for(int i=0;i<activities.length();i++){
            JSONObject source=activities.getJSONObject(i);String sourceId=source.getString("actionId"),id=source.optString("engineActionId","p3r.stat."+sourceId);
            if(!source.optBoolean("repeatable")||source.optBoolean("oneUse")||!"enabled_with_preconditions".equals(source.optString("planningStatus"))||source.getInt("slotCost")!=1){diagnostics.put(diag("unsupported_one_use_activity",sourceId,"一次性结果须先实际确认"));continue;}
            boolean insufficientCurrentCash=source.getInt("yenCost")>0&&(observed.isNull("cash")||strictInt(observed.get("cash"))<source.getInt("yenCost"));
            if(insufficientCurrentCash)diagnostics.put(diag("cash_not_proven_sufficient",sourceId,"当前金钱不足或未知；未来行动仍须证明费用足够"));
            JSONArray eligible=new JSONArray();Set<String> missing=new TreeSet<>();JSONObject requirements=new JSONObject(),gain=new JSONObject();
            JSONArray prerequisites=source.getJSONArray("prerequisites");
            for(int slot=Math.max(start,firstSlot);contextValid&&slot<horizon;slot++){
                if(slot==start&&insufficientCurrentCash)continue;
                if(!contains(source.getJSONArray("slots"),slot%2==0?"day":"night")||!contains(source.getJSONArray("isoWeekdays"),GameDate.date(slot).getDayOfWeek().getValue()))continue;
                JSONObject state=slots==null?null:slots.optJSONObject(String.valueOf(slot));
                int special=specialSlotGuard(slot,context,state);
                if(special<0){missing.add(specialSlotGuardName(slot));continue;}
                boolean assumedSlot=slot>start&&assumeFuture&&!sourceId.startsWith("movie_");
                boolean knownFree=state!=null&&Boolean.FALSE.equals(state.opt("knownStoryLock"))&&"free".equals(state.optString("slotState"))&&state.opt("remainingCapacity") instanceof Number&&strictInt(state.get("remainingCapacity"))>=1;
                boolean explicitlyBlocked=state!=null&&(Boolean.TRUE.equals(state.opt("knownStoryLock"))||state.opt("remainingCapacity") instanceof Number&&strictInt(state.get("remainingCapacity"))==0);
                if(locks.contains(slot)||explicitlyBlocked||!knownFree&&!assumedSlot){missing.add("calendar.slotState");continue;}
                JSONObject atSlot=guards==null?null:guards.optJSONObject(String.valueOf(slot)),specific=atSlot==null?null:atSlot.optJSONObject(sourceId),defaults=persistent==null?null:persistent.optJSONObject(sourceId);
                JSONObject observations=new JSONObject();if(defaults!=null)for(Iterator<String> it=defaults.keys();it.hasNext();){String key=it.next();if(!key.startsWith("calendar.")&&!key.startsWith("movie."))observations.put(key,defaults.get(key));}
                if(specific!=null)for(Iterator<String> it=specific.keys();it.hasNext();){String key=it.next();observations.put(key,specific.get(key));}
                boolean valid=true;
                Object exact=specific==null?null:specific.opt("calendar.exactDateActionAvailable");
                if(Boolean.FALSE.equals(exact)){missing.add("calendar.exactDateActionAvailable");continue;}
                boolean availabilityUnknown=!knownFree||special==0||slot>start&&!Boolean.TRUE.equals(exact);
                for(int p=0;p<prerequisites.length();p++){
                    JSONObject guard=prerequisites.getJSONObject(p);String key=guard.getString("field"),op=guard.getString("op");Object wanted=guard.get("value");
                    if(key.equals("temperanceStatBonusActive")||key.equals("calendar.slotState"))continue;
                    if(key.equals("yen")){requirements.put("cash",strictInt(wanted));continue;}
                    if(key.startsWith("stats.")&&key.endsWith(".rank")){String slug=key.split("\\.")[1];if(!op.equals("gte"))throw new IllegalArgumentException("Unsupported stat gate");requirements.put(field(slug,"raw"),rawTargetForRank(slug,strictInt(wanted)));continue;}
                    if(key.equals("calendar.exactDateActionAvailable")&&assumedSlot&&!observations.has(key)){availabilityUnknown=true;continue;}
                    Object actual=observations.opt(key);
                    if(!op.equals("eq")||!Objects.equals(actual,wanted)){valid=false;missing.add(key);}
                }
                if(valid&&!availabilityUnknown)eligible.put(slot);
                else if(valid&&assumedSlot){
                    JSONArray needed=new JSONArray();if(!knownFree)needed.put("calendar.slotState");if(!Boolean.TRUE.equals(exact))needed.put("calendar.exactDateActionAvailable");if(special==0)needed.put(specialSlotGuardName(slot));
                    conditionalCandidates.put(new JSONObject().put("action_id",id).put("source_action_id",sourceId).put("slot",slot).put("date",String.format(Locale.ROOT,"%02d-%02d",GameDate.date(slot).getMonthValue(),GameDate.date(slot).getDayOfMonth())).put("period",slot%2==0?"daytime":"evening").put("display_title",source.getString("labelZhHans"))
                        .put("status","conditional_availability_unconfirmed").put("forecast_credit",false).put("conditions_to_confirm",needed).put("conditional_base_stat_gain",new JSONObject(source.getJSONObject("statDelta").toString())).put("conditional_minimum_income",source.has("incomeLowerBound")?source.get("incomeLowerBound"):JSONObject.NULL).put("yen_cost",source.getInt("yenCost")).put("source_manifest_sha256",MANIFEST_SHA256));
                    if(!metadata.has(id))metadata.put(id,new JSONObject().put("display_title",source.getString("labelZhHans")).put("source_action_id",sourceId).put("stat_gain_mode","conditional_base_gain_not_credited").put("future_routine_availability_assumed",true).put("income_only_projection",source.has("incomeLowerBound")).put("cash_minimum_gain",source.opt("incomeLowerBound")==null?JSONObject.NULL:source.opt("incomeLowerBound")).put("yen_cost",source.getInt("yenCost")));
                }else if(valid)missing.add(special==0?specialSlotGuardName(slot):"calendar.exactDateActionAvailable");
            }
            JSONObject deltas=source.getJSONObject("statDelta");for(Iterator<String> it=deltas.keys();it.hasNext();){String slug=it.next(),key=field(slug,"raw");int delta=strictInt(deltas.get(slug));if(delta<0)throw new IllegalArgumentException("Negative gain unsupported");gain.put(key,interval(delta,null));fields.add(key);}
            if(source.has("incomeLowerBound"))gain.put("cash",interval(source.getInt("incomeLowerBound"),null));
            if(eligible.length()>0){JSONObject costs=new JSONObject();if(source.getInt("yenCost")>0)costs.put("cash",source.getInt("yenCost"));actions.put(new JSONObject().put("id",id).put("slots",eligible).put("requirements",requirements).put("costs",costs).put("uncertain_effects",gain).put("strict_requirements",true).put("verification","corroborated").put("source",MANIFEST_SHA256+":"+sourceId));
                metadata.put(id,new JSONObject().put("display_title",source.getString("labelZhHans")).put("source_action_id",sourceId).put("stat_gain_mode","base_lower_bound_bonus_not_assumed").put("exact_observed_gain_claimed",false).put("future_routine_availability_assumed",false).put("availability_basis","observed_current_or_explicit_exact_date_guards").put("income_only_projection",source.has("incomeLowerBound")).put("cash_minimum_gain",source.opt("incomeLowerBound")==null?JSONObject.NULL:source.opt("incomeLowerBound")).put("yen_cost",source.getInt("yenCost")));}
            if(!missing.isEmpty())diagnostics.put(diag("activity_guards_unknown_or_false",sourceId,"缺少或不满足行动条件").put("guards",new JSONArray(missing)));
        }
        for(int slot=Math.max(start,firstSlot);slot<horizon;slot++)labels.put(String.valueOf(slot),new JSONObject().put("date",String.format(Locale.ROOT,"%02d-%02d",GameDate.date(slot).getMonthValue(),GameDate.date(slot).getDayOfMonth())).put("period",slot%2==0?"daytime":"evening"));
        JSONObject rules=new JSONObject().put("version",version).put("horizon",horizon).put("fields",new JSONArray(fields)).put("actions",actions).put("stages",operationalStages()).put("coverage_complete",false).put("synthetic",false);
        JSONObject snapshot=new JSONObject().put("run_id",observed.getString("run_id")).put("rules_version",version).put("revision",observed.getLong("revision")).put("slot",start).put("values",values).put("visits",observed.has("visits_by_stage")?new JSONObject(observed.getJSONObject("visits_by_stage").toString()):new JSONObject());
        return new JSONObject().put("status",contextValid&&start>=firstSlot&&start<horizon?"conditional":"unknown").put("rules",rules).put("engine_snapshot",snapshot).put("labels",labels).put("action_metadata",metadata).put("supported_goal_fields",supported).put("diagnostics",diagnostics).put("conditional_activity_candidates",conditionalCandidates).put("observation_checkpoints",new JSONArray()).put("derived_checkpoint_facts",derived).put("observed_state_mutated",false);
    }
    private static String specialSlotGuardName(int slot){
        return slot/2==GameDate.slot(12,24,false)/2?"storyFlags.christmasAllInvitationsDeclined":"jan21_conditional_story_event_resolved";
    }
    private static int specialSlotGuard(int slot,JSONObject context,JSONObject slotState){
        JSONObject flags=context.optJSONObject("storyFlags");
        if(slot/2==GameDate.slot(12,24,false)/2){Object declined=flags==null?null:flags.opt("christmasAllInvitationsDeclined");return Boolean.TRUE.equals(declined)?1:Boolean.FALSE.equals(declined)?-1:0;}
        if(slot==GameDate.slot(1,21,false)){Object flowers=flags==null?null:flags.opt("junpeiWhiteFlowersGiven");return flowers instanceof Boolean&&slotState!=null&&Boolean.FALSE.equals(slotState.opt("knownStoryLock"))?1:0;}
        return 1;
    }
    private static String field(String slug,String suffix){return "socialStats."+slug+"."+suffix;}
    private static Long bound(JSONObject value,String side)throws Exception{if(value==null||value.isNull(side))return null;return (long)strictInt(value.get(side));}
    private static Long min(Long a,long b){return a==null?b:Math.min(a,b);}private static Long max(Long a,long b){return a==null?b:Math.max(a,b);}
    private static JSONObject interval(Object low,Object high)throws Exception{return new JSONObject().put("low",low==null?JSONObject.NULL:low).put("high",high==null?JSONObject.NULL:high);}
    private static JSONObject diag(String code,String action,String message)throws Exception{return new JSONObject().put("code",code).put("action_id",action==null?JSONObject.NULL:action).put("message",message);}
    private static boolean contains(JSONArray values,Object value)throws Exception{for(int i=0;i<values.length();i++)if(Objects.equals(values.get(i),value))return true;return false;}
    private static int strictInt(Object value){if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<Integer.MIN_VALUE||((Number)value).longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException("Expected integer");return ((Number)value).intValue();}
    private static byte[] bytes(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}
    private static JSONObject parse(byte[] raw)throws Exception{return new JSONObject(new String(raw,StandardCharsets.UTF_8));}
    private static String sha(byte[] raw)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(raw))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
