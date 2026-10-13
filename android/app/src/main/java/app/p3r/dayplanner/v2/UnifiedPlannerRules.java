package app.p3r.dayplanner.v2;

import android.content.Context;
import org.json.*;
import java.util.*;

/** Conditional composition; observed state and saved constraints are never rewritten. */
public final class UnifiedPlannerRules {
    private final PlannerRules january;
    private final NativeManualRules manual;
    private final NativeProgressionRules progression;
    private final NativeUnlockRules unlocks;
    public final String version;
    public UnifiedPlannerRules(Context context,PlannerRules january)throws Exception {
        this.january=january;manual=new NativeManualRules(context);progression=new NativeProgressionRules(context);unlocks=new NativeUnlockRules(context);
        version="p3r-unified-v2-a6|"+january.version+"|"+manual.version+"|"+progression.version+"|"+unlocks.version;
    }

    /** Exposes the exact immutable inputs used for independent simulation/replay. */
    public JSONObject build(JSONObject observed,JSONArray goals,JSONObject constraints)throws Exception {
        JSONObject context=observed.optJSONObject("planning_context");context=context==null?new JSONObject():copy(context);
        validateContext(observed,context);
        JSONObject local=copy(observed).put("rules_version",manual.version),policy=observed.optJSONObject("option_policy");
        JSONObject built=manual.build(local,context,policy==null?new JSONObject():policy);
        JSONObject progress=progression.build(copy(observed).put("rules_version",progression.version),context,policy==null?new JSONObject():policy);
        JSONObject introductions=unlocks.build(copy(observed).put("rules_version",unlocks.version),context,policy==null?new JSONObject():policy);
        if(!version.equals(observed.optString("rules_version")))built.put("status","unsupported").getJSONArray("diagnostics").put(diag("rules_version_mismatch","规则已更新，请核对当前实际状态后建立新基线。"));
        JSONObject rules=copy(built.getJSONObject("rules")).put("version",version),initial=copy(built.getJSONObject("engine_snapshot")).put("rules_version",version);
        Set<String> selected=new TreeSet<>(),supportedFields=new TreeSet<>(),fields=new TreeSet<>();
        for(int i=0;i<goals.length();i++){JSONObject goal=goals.getJSONObject(i);String field=goal.getString("field");if(field.startsWith("socialLinks.")&&field.endsWith(".rank"))selected.add(field.substring(12,field.length()-5));long target=goal.getLong("target");if(field.startsWith("socialLinks.")&&(target<1||target>10))throw new IllegalArgumentException("社群目标等级须为1–10");}
        Set<String> requestedEntities=new TreeSet<>(selected);JSONArray prerequisiteGoals=new JSONArray();JSONObject dependencies=introductions.getJSONObject("goal_dependencies");Set<String> visitedDependencies=new HashSet<>();Map<String,Long> dependencyTargets=new TreeMap<>();int helperPriority=0;
        if(goals.length()>0){helperPriority=Integer.MAX_VALUE;for(int i=0;i<goals.length();i++)helperPriority=Math.min(helperPriority,goals.getJSONObject(i).optInt("priority",0));if(helperPriority>Integer.MIN_VALUE)helperPriority--;}
        List<String> queue=new ArrayList<>(selected);for(int q=0;q<queue.size();q++){String slug=queue.get(q),field="socialLinks."+slug+".rank";if(!visitedDependencies.add(field)||!known(observed.getJSONObject("facts"),field)||observed.getJSONObject("facts").getJSONObject(field).getInt("low")!=0)continue;
            JSONArray needed=dependencies.optJSONArray(field);if(needed==null)continue;for(int n=0;n<needed.length();n++){JSONObject dep=needed.getJSONObject(n);String key=dep.getString("field");dependencyTargets.put(key,Math.max(dep.getLong("target"),dependencyTargets.getOrDefault(key,Long.MIN_VALUE)));if(key.startsWith("socialLinks.")&&key.endsWith(".rank")){String next=key.substring(12,key.length()-5);if(selected.add(next))queue.add(next);}}}
        for(Map.Entry<String,Long> dep:dependencyTargets.entrySet())prerequisiteGoals.put(new JSONObject().put("field",dep.getKey()).put("target",dep.getValue()).put("priority",helperPriority));
        JSONArray supportedArray=built.getJSONArray("supported_goal_fields");for(int i=0;i<supportedArray.length();i++)supportedFields.add(supportedArray.getString(i));supportedFields.add("socialLinks.sun.rank");supportedFields.add("socialLinks.devil.rank");for(String stat:new String[]{"academics","charm","courage"}){supportedFields.add("socialStats."+stat+".rank");supportedFields.add("socialStats."+stat+".raw");}
        JSONArray originalFields=rules.getJSONArray("fields");for(int i=0;i<originalFields.length();i++)fields.add(originalFields.getString(i));
        JSONArray diagnostics=filterEntities(built.getJSONArray("diagnostics"),selected),checks=filterEntities(built.getJSONArray("observation_checkpoints"),selected);
        JSONArray actions=new JSONArray(),all=rules.getJSONArray("actions");JSONObject metadata=copy(built.getJSONObject("action_metadata")),values=initial.getJSONObject("values"),derived=copy(built.getJSONObject("derived_checkpoint_facts"));
        JSONObject statDerived=progress.getJSONObject("derived_checkpoint_facts");for(Iterator<String> it=statDerived.keys();it.hasNext();){String field=it.next();fields.add(field);values.put(field,statDerived.get(field));derived.put(field,"visible_rank_or_observed_raw_interval_not_exact_minimum");}
        values.put("cash",intersection(values.optJSONObject("cash"),progress.getJSONObject("engine_snapshot").getJSONObject("values").getJSONObject("cash")));
        for(String stat:new String[]{"academics","charm","courage"}){values.remove("stats."+stat);fields.remove("stats."+stat);}
        JSONObject introValues=introductions.getJSONObject("engine_snapshot").getJSONObject("values");for(Iterator<String> it=introValues.keys();it.hasNext();){String key=it.next();if(key.startsWith("stats."))continue;fields.add(key);JSONObject incoming=introValues.getJSONObject(key),existing=values.optJSONObject(key);values.put(key,intersection(existing,incoming));}
        JSONObject introDerived=introductions.getJSONObject("derived_checkpoint_facts");for(Iterator<String> it=introDerived.keys();it.hasNext();){String key=it.next();derived.put(key,introDerived.get(key));}
        append(diagnostics,filterEntities(introductions.getJSONArray("diagnostics"),selected));append(checks,filterEntities(introductions.getJSONArray("observation_checkpoints"),selected));
        boolean wantsStats=false;for(int i=0;i<goals.length();i++)if(goals.getJSONObject(i).getString("field").startsWith("socialStats."))wantsStats=true;for(String key:dependencyTargets.keySet())if(key.startsWith("socialStats."))wantsStats=true;
        if(wantsStats)append(diagnostics,progress.getJSONArray("diagnostics"));
        Set<String> pinned=new HashSet<>();JSONObject pins=constraints.optJSONObject("pinned_actions");if(pins!=null)for(Iterator<String> it=pins.keys();it.hasNext();)pinned.add(pins.getString(it.next()));
        for(int i=0;i<all.length();i++){JSONObject action=all.getJSONObject(i),meta=metadata.optJSONObject(action.getString("id"));if(meta!=null&&selected.contains(meta.optString("entity"))||pinned.contains(action.getString("id")))actions.put(action);}
        JSONArray introActions=introductions.getJSONObject("rules").getJSONArray("actions"),introFields=introductions.getJSONObject("rules").getJSONArray("fields");for(int i=0;i<introFields.length();i++)if(!introFields.getString(i).startsWith("stats."))fields.add(introFields.getString(i));
        if("conditional".equals(introductions.optString("status")))for(int i=0;i<introActions.length();i++){JSONObject action=introActions.getJSONObject(i),meta=introductions.getJSONObject("action_metadata").getJSONObject(action.getString("id"));if(selected.contains(meta.optString("entity"))||pinned.contains(action.getString("id"))){actions.put(action);metadata.put(action.getString("id"),meta);}}
        // A nonnegative floor is forecast knowledge only, not exact observed hidden points.
        for(String field:fields)if(field.endsWith(".affinityLowerBound")){JSONObject value=values.optJSONObject(field);if(value==null||value.isNull("low")){values.put(field,interval(0,null));derived.put(field,"conservative_nonnegative_floor_not_observed_exact_points");}}
        for(int i=0;i<actions.length();i++){JSONObject assignments=actions.getJSONObject(i).optJSONObject("assignments");if(assignments!=null)for(Iterator<String> it=assignments.keys();it.hasNext();){String field=it.next();if(field.endsWith(".affinityLowerBound")&&assignments.isNull(field))assignments.put(field,interval(0,null));}}
        JSONObject calendarByEntity=new JSONObject();
        for(String slug:selected)if(supportedFields.contains("socialLinks."+slug+".rank")){
            JSONObject guarded=manual.guardedCalendarCandidates(local,context,slug);calendarByEntity.put(slug,guarded);append(diagnostics,guarded.getJSONArray("diagnostics"));
            if(!slug.equals("sun")&&!slug.equals("devil"))continue;
            String rank="socialLinks."+slug+".rank",normal="socialLinks."+slug+".normal",unlocked="socialLinks."+slug+".alreadyUnlocked";fields.add(rank);fields.add(normal);fields.add(unlocked);
            if(!"conditional".equals(guarded.optString("status")))continue;
            JSONObject template=null;JSONArray old=january.rules.getJSONArray("actions");for(int i=0;i<old.length();i++)if(old.getJSONObject(i).getString("id").equals("p3r.action.visit_"+slug))template=old.getJSONObject(i);
            if(template==null||template.getJSONObject("effects").getInt(rank)!=1)throw new IllegalStateException("Missing audited point-independent transition");
            if(guarded.getJSONObject("derived_checkpoint_facts").has(unlocked)){values.put(unlocked,interval(1,1));derived.put(unlocked,"validated_observed_rank_at_least_1");}
            int current=values.getJSONObject(rank).getInt("low");String name=slug.equals("sun")?"太阳":"恶魔";
            for(int from=current;from<10;from++){
                String id="p3r.manual."+slug+".rank_"+String.format(Locale.ROOT,"%02d",from+1);JSONObject action=copy(template).put("id",id).put("slots",new JSONArray(guarded.getJSONArray("eligible_slots").toString()));
                JSONObject guard=new JSONObject().put(rank,from).put(normal,1).put(unlocked,1);action.put("requirements",guard).put("maximums",copy(guard));
                action.put("source",new JSONObject().put("rank_transition_evidence",template.getString("source")).put("calendar_source_ids",guarded.getJSONArray("source_rule_ids")).put("calendar_manifest_sha256",NativeManualRules.MANIFEST_SHA256).toString());actions.put(action);
                metadata.put(id,new JSONObject().put("entity",slug).put("name_zh_cn",name).put("display_title",name+" · "+from+"→"+(from+1)).put("from_rank",from).put("to_rank",from+1).put("point_independent_normal_visit",true).put("assumptions",guarded.getJSONArray("assumptions")).put("source_ordinals_are_game_instructions",false));
            }
        }
        JSONObject dungeon=progression.dungeonCandidates(observed,context,constraints);JSONArray dungeonActions=dungeon.getJSONArray("actions");
        for(int i=0;i<dungeonActions.length();i++){JSONObject action=dungeonActions.getJSONObject(i);actions.put(action);metadata.put(action.getString("id"),dungeon.getJSONObject("action_metadata").getJSONObject(action.getString("id")));}
        if(context.has("tartarus")||constraints.optJSONObject("visit_caps")!=null)append(diagnostics,dungeon.getJSONArray("diagnostics"));
        if("conditional".equals(progress.optString("status"))){JSONArray statActions=progress.getJSONObject("rules").getJSONArray("actions");for(int i=0;i<statActions.length();i++){JSONObject action=statActions.getJSONObject(i);if(wantsStats||pinned.contains(action.getString("id"))){actions.put(action);metadata.put(action.getString("id"),progress.getJSONObject("action_metadata").getJSONObject(action.getString("id")));}}}
        JSONArray conditionalActivities=new JSONArray(),routine=progress.optJSONArray("conditional_activity_candidates");if(routine!=null)for(int i=0;i<routine.length();i++){JSONObject candidate=routine.getJSONObject(i);if(wantsStats||pinned.contains(candidate.getString("action_id"))){conditionalActivities.put(candidate);JSONObject detail=progress.getJSONObject("action_metadata").optJSONObject(candidate.getString("action_id"));if(detail!=null)metadata.put(candidate.getString("action_id"),detail);}}
        for(int i=0;i<actions.length();i++){JSONObject action=actions.getJSONObject(i);action.put("strict_requirements",true);JSONArray allowed=new JSONArray(),raw=action.getJSONArray("slots");for(int j=0;j<raw.length();j++){int slot=raw.getInt(j);if(!(action.optBoolean("dungeon_visit")?progression.isDungeonSlotLocked(slot):progression.isOrdinarySlotLocked(slot)))allowed.put(slot);}action.put("slots",allowed);}
        JSONArray unsupported=new JSONArray(),blocked=new JSONArray(),supported=new JSONArray(),engineGoals=new JSONArray(),already=new JSONArray();JSONObject facts=observed.getJSONObject("facts");
        for(int i=0;i<goals.length();i++){JSONObject goal=goals.getJSONObject(i);String field=goal.getString("field");
            if(field.startsWith("socialStats.")&&supportedFields.contains(field)){
                String[] pieces=field.split("\\.");String raw="socialStats."+pieces[1]+".raw";long target=pieces[2].equals("rank")?progression.rawTargetForRank(pieces[1],goal.getInt("target")):goal.getLong("target");JSONObject known=values.optJSONObject(raw);
                if(known==null||known.isNull("low")){blocked.put(new JSONObject().put("goal",goal).put("reason","请确认当前可见属性等级，无需填写隐藏点数"));continue;}
                supported.put(goal);engineGoals.put(copy(goal).put("field",raw).put("target",target));if(known.getLong("low")>=target)already.put(goal);continue;
            }
            if(known(facts,field)&&facts.getJSONObject(field).getLong("low")>=goal.getLong("target")){supported.put(goal);engineGoals.put(goal);already.put(goal);fields.add(field);continue;}
            if(!supportedFields.contains(field)){unsupported.put(goal);continue;}
            if(!known(facts,field)){blocked.put(new JSONObject().put("goal",goal).put("reason","当前可见等级未知"));continue;}
            String normal=field.replace(".rank",".normal");long rank=facts.getJSONObject(field).getLong("low");
            if(rank==0){supported.put(goal);engineGoals.put(goal);continue;}
            if(rank<0||!known(facts,normal)||facts.getJSONObject(normal).getLong("low")!=1){blocked.put(new JSONObject().put("goal",goal).put("reason","当前关系状态需要核对或修复"));continue;}
            supported.put(goal);engineGoals.put(goal);
        }
        for(int i=0;i<prerequisiteGoals.length();i++){JSONObject dep=prerequisiteGoals.getJSONObject(i);boolean alreadyRequested=false;for(int j=0;j<engineGoals.length();j++)if(engineGoals.getJSONObject(j).getString("field").equals(dep.getString("field"))&&engineGoals.getJSONObject(j).getLong("target")>=dep.getLong("target"))alreadyRequested=true;if(!alreadyRequested)engineGoals.put(dep);}
        JSONObject active=copy(constraints),caps=constraints.optJSONObject("visit_caps"),activeCaps=new JSONObject(),dormant=new JSONObject();JSONArray stages=progression.operationalStages();Set<String> stageIds=new HashSet<>();for(int i=0;i<stages.length();i++)stageIds.add(stages.getJSONObject(i).getString("id"));
        if(caps!=null)for(Iterator<String> it=caps.keys();it.hasNext();){String id=it.next();if(stageIds.contains(id))activeCaps.put(id,caps.get(id));else dormant.put(id,caps.get(id));}
        active.put("visit_caps",activeCaps);if(dormant.length()>0)diagnostics.put(diag("dormant_stage_caps","边界尚未核对的阶段上限仍被保留，此候选不会在这些阶段安排爬塔。").put("caps",dormant));
        rules.put("fields",new JSONArray(fields)).put("actions",actions).put("stages",stages).put("coverage_complete",false).put("synthetic",false);
        return new JSONObject().put("status",built.getString("status")).put("rules",rules).put("engine_snapshot",initial).put("active_constraints",active).put("preserved_constraints",copy(constraints)).put("dormant_visit_caps",dormant)
            .put("planned_goals",supported).put("engine_goals",engineGoals).put("prerequisite_goals",prerequisiteGoals).put("unlock_stage_code_map",introductions.getJSONObject("stage_code_map")).put("unsupported_goals",unsupported).put("goals_needing_observation",blocked).put("already_satisfied_goals",already).put("supported_goal_fields",new JSONArray(supportedFields))
            .put("labels",built.getJSONObject("labels")).put("action_metadata",metadata).put("diagnostics",diagnostics).put("observation_checkpoints",checks).put("derived_checkpoint_facts",derived).put("calendar_by_entity",calendarByEntity).put("planning_context",context).put("conditional_activity_candidates",conditionalActivities).put("dungeon_candidates",dungeon).put("story_milestones",progression.storyGoalMilestones(observed,goals));
    }

    public JSONObject plan(JSONObject observed,JSONArray goals,JSONObject constraints)throws Exception{return plan(observed,goals,constraints,48,60000);}
    public JSONObject plan(JSONObject observed,JSONArray goals,JSONObject constraints,int beamWidth,int budget)throws Exception {
        JSONObject built=build(observed,goals,constraints),initial=built.getJSONObject("engine_snapshot"),result;
        JSONArray supported=built.getJSONArray("planned_goals"),unsupported=built.getJSONArray("unsupported_goals"),blocked=built.getJSONArray("goals_needing_observation");
        JSONObject pins=built.getJSONObject("active_constraints").optJSONObject("pinned_actions");boolean hasPins=pins!=null&&pins.length()>0;
        if(!"conditional".equals(built.getString("status")))result=issue(built.getString("status").equals("unsupported")?"unsupported":"unknown","当前日期、规则或存档状态需要核对后才能规划。").put("forecast",initial);
        else if(supported.length()==0&&!hasPins)result=issue(blocked.length()>0?"unknown":"unsupported","选定目标尚缺当前状态或可执行规则；不会据此判断无法完成。").put("forecast",initial);
        else {
            result=PlannerEngine.plan(built.getJSONObject("rules"),initial,built.getJSONArray("engine_goals"),built.getJSONObject("active_constraints"),beamWidth,budget);
            JSONObject replay=PlannerEngine.validatePlan(built.getJSONObject("rules"),initial,result.getJSONArray("steps"),built.getJSONObject("active_constraints"));
            if(!canonical(replay).equals(canonical(result.getJSONObject("forecast"))))throw new IllegalStateException("Replay forecast mismatch");result.put("replay_validated",true);
            if("feasible_within_model".equals(result.optString("status")))result.put("status","conditional");
        }
        JSONArray unproven=new JSONArray();JSONObject forecastValues=result.getJSONObject("forecast").getJSONObject("values");JSONArray engineGoals=built.getJSONArray("engine_goals");
        for(int i=0;i<engineGoals.length();i++){JSONObject goal=engineGoals.getJSONObject(i),value=forecastValues.optJSONObject(goal.getString("field"));if(value==null||value.isNull("low")||value.getLong("low")<goal.getLong("target"))unproven.put(goal);}
        if(unproven.length()>0&&result.getString("status").equals("conditional")){result.put("status","unknown");result.getJSONArray("explanations").put("保守下界尚未达到全部目标；未知加成不能当作目标已完成。");}
        result.put("unproven_lower_bound_goals",unproven).put("forecast_stat_ranks",progression.rankIntervals(forecastValues));
        result.put("supported_plan_status",result.getString("status"));boolean partial=unsupported.length()>0||blocked.length()>0;
        if(partial&&(supported.length()>0||hasPins)&&result.getString("status").equals("conditional"))result.put("status","unknown");
        JSONArray unhonoredPins=new JSONArray();
        if(hasPins)for(Iterator<String> it=pins.keys();it.hasNext();){String key=it.next();int slot=Integer.parseInt(key);String actionId=pins.getString(key);boolean matched=false;JSONArray steps=result.getJSONArray("steps");for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);if(step.getInt("slot")==slot&&step.getString("action_id").equals(actionId)){matched=true;break;}}if(!matched)unhonoredPins.put(new JSONObject().put("slot",slot).put("action_id",actionId));}
        boolean pinsConfirmed=hasPins&&unhonoredPins.length()==0&&result.optBoolean("replay_validated",false);
        result.put("pin_reservation_status",!hasPins?"none":pinsConfirmed?"conditional":"unconfirmed").put("all_pins_honored",!hasPins||pinsConfirmed).put("unhonored_pins",unhonoredPins);
        if(hasPins&&!pinsConfirmed){if(result.getString("status").equals("conditional"))result.put("status","unknown");result.getJSONArray("explanations").put("手动预留尚未全部排入有效候选；请核对日期、阶段次数和当前可用状态，或清除预留后重排。出发前仍须在游戏内确认。");}
        JSONArray reservations=adaptiveReservations(observed,goals,built,result.getJSONArray("steps"));
        return result.put("rules_version",version).put("run_id",observed.getString("run_id")).put("state_revision",observed.getLong("revision")).put("requested_goals",new JSONArray(goals.toString())).put("planned_goals",supported).put("unsupported_goals",unsupported).put("goals_needing_observation",blocked)
            .put("prerequisite_goals",built.getJSONArray("prerequisite_goals")).put("unlock_stage_code_map",built.getJSONObject("unlock_stage_code_map")).put("all_requested_goals_supported",!partial).put("constraints",copy(constraints)).put("dormant_visit_caps",built.getJSONObject("dormant_visit_caps")).put("diagnostics",built.getJSONArray("diagnostics")).put("observation_checkpoints",built.getJSONArray("observation_checkpoints")).put("adaptive_reservations",reservations)
            .put("labels",built.getJSONObject("labels")).put("action_metadata",built.getJSONObject("action_metadata")).put("derived_checkpoint_facts",built.getJSONObject("derived_checkpoint_facts")).put("model_scope","partial_audited_composite").put("optimality_proven",false).put("observed_state_mutated",false).put("conservative_visible_state_default",true).put("conditional_activity_candidates",built.getJSONArray("conditional_activity_candidates")).put("dungeon_candidates",dungeonViews(built)).put("dungeon_observation_checkpoints",built.getJSONObject("dungeon_candidates").getJSONArray("observation_checkpoints")).put("story_milestones",built.getJSONArray("story_milestones"));
    }

    private JSONArray dungeonViews(JSONObject built)throws Exception {
        JSONArray out=new JSONArray();JSONObject candidates=built.getJSONObject("dungeon_candidates");JSONArray actions=candidates.getJSONArray("actions");
        for(int i=0;i<actions.length();i++){JSONObject action=actions.getJSONObject(i),meta=candidates.getJSONObject("action_metadata").getJSONObject(action.getString("id"));JSONArray slots=action.getJSONArray("slots");for(int j=0;j<slots.length();j++){int slot=slots.getInt(j);JSONObject label=built.getJSONObject("labels").optJSONObject(String.valueOf(slot));if(label==null)continue;out.put(new JSONObject().put("action_id",action.getString("id")).put("slot",slot).put("date",label.getString("date")).put("period",label.getString("period")).put("stage_id",meta.getString("stage_id")).put("availability_tier",meta.getString("availability_tier")).put("before_entry_check_required",true).put("expected_money",JSONObject.NULL).put("expected_floor",JSONObject.NULL).put("guaranteed_entry",false).put("optional_candidate_not_reserved",true));}}
        return out;
    }

    private JSONArray adaptiveReservations(JSONObject observed,JSONArray goals,JSONObject built,JSONArray steps)throws Exception {
        JSONArray out=new JSONArray();if(!built.getString("status").equals("conditional"))return out;
        Map<String,JSONObject> requested=new HashMap<>();for(int i=0;i<goals.length();i++){JSONObject g=goals.getJSONObject(i);requested.put(g.getString("field"),g);}
        List<JSONObject> checks=new ArrayList<>();JSONArray raw=built.getJSONArray("observation_checkpoints");for(int i=0;i<raw.length();i++)checks.add(raw.getJSONObject(i));
        checks.sort((a,b)->{JSONObject ga=requested.get("socialLinks."+a.optString("entity")+".rank"),gb=requested.get("socialLinks."+b.optString("entity")+".rank");long pa=ga==null?0:ga.optLong("priority"),pb=gb==null?0:gb.optLong("priority");int order=Long.compare(pb,pa);return order!=0?order:a.optString("entity").compareTo(b.optString("entity"));});
        Set<Integer> occupied=new HashSet<>();Set<String> reserved=new HashSet<>();JSONObject pins=built.getJSONObject("active_constraints").optJSONObject("pinned_actions");if(pins!=null)for(Iterator<String> it=pins.keys();it.hasNext();)occupied.add(Integer.parseInt(it.next()));
        JSONObject metadata=built.getJSONObject("action_metadata");for(int i=0;i<steps.length();i++)if(!steps.getJSONObject(i).getString("action_id").equals("__wait__"))occupied.add(steps.getJSONObject(i).getInt("slot"));
        for(JSONObject check:checks){String slug=check.getString("entity"),field="socialLinks."+slug+".rank";JSONObject goal=requested.get(field);int rank=check.getInt("at_rank");if(goal==null||goal.getInt("target")<=rank||reserved.contains(slug))continue;
            int after=observed.getInt("game_slot");JSONObject actual=observed.getJSONObject("facts").optJSONObject(field);boolean reached=known(observed.getJSONObject("facts"),field)&&actual.getInt("low")==rank;
            for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i),meta=metadata.optJSONObject(step.getString("action_id"));if(meta!=null&&slug.equals(meta.optString("entity"))&&meta.optInt("to_rank")==rank){reached=true;after=step.getInt("slot")+1;break;}}
            if(!reached)continue;JSONObject guarded=built.getJSONObject("calendar_by_entity").optJSONObject(slug);if(guarded==null)continue;JSONArray candidates=guarded.getJSONArray("candidates");
            for(int i=0;i<candidates.length();i++){JSONObject candidate=candidates.getJSONObject(i);int slot=candidate.getInt("slot");if(slot<after||occupied.contains(slot)||progression.isOrdinarySlotLocked(slot)||!candidate.getString("status").startsWith("conditional_available"))continue;
                JSONObject label=built.getJSONObject("labels").getJSONObject(String.valueOf(slot));out.put(new JSONObject().put("kind","adaptive_observation_checkpoint").put("entity",slug).put("at_rank",rank).put("slot",slot).put("date",label.getString("date")).put("period",label.getString("period"))
                    .put("display_title",metadataName(slug)+" · 前往会面，确认是否可升阶").put("status","requires_observation").put("observation_only_slot_cost",0).put("consumptive_visit_pending_choice",true).put("predicted_rank_delta",JSONObject.NULL).put("predicted_affinity_gain",JSONObject.NULL).put("forecast_meeting_count",JSONObject.NULL).put("guaranteed_finish_date",JSONObject.NULL)
                    .put("source_rule_id",candidate.getString("source_rule_id")).put("assumptions",candidate.getJSONArray("assumptions")).put("next_action",check.optJSONObject("next_action")==null?new JSONObject().put("kind","verify_current_branch_and_relationship").put("instruction",check.getString("message")):check.getJSONObject("next_action")));
                occupied.add(slot);reserved.add(slug);break;
            }
        }return out;
    }
    private String metadataName(String slug)throws Exception {for(int i=0;i<january.catalog.length();i++){JSONObject item=january.catalog.getJSONObject(i);if(item.getString("id").equals("p3r.social_link."+slug))return item.optString("nameZh",item.optString("name_zh_cn",slug));}return slug;}
    private void validateContext(JSONObject observed,JSONObject context)throws Exception {
        int slot=observed.getInt("game_slot");JSONObject checkpoint=context.optJSONObject("checkpoint");if(checkpoint==null)checkpoint=new JSONObject();boolean consistent=checkpoint.optBoolean("userConfirmedCurrentState",checkpoint.optBoolean("consistencyValidated",false));JSONArray conflicts=new JSONArray();
        JSONObject date=checkpoint.optJSONObject("date");if(date!=null&&date.has("ordinal")&&date.getInt("ordinal")!=slot/2){consistent=false;conflicts.put("检查点日期与当前日期不一致");}
        JSONObject facts=observed.getJSONObject("facts");for(int i=0;i<january.catalog.length();i++){JSONObject entry=january.catalog.getJSONObject(i);String id=entry.getString("id"),slug=id.substring(id.lastIndexOf('.')+1),earliest=entry.getJSONObject("unlock").optString("earliest_month_day_claim","");JSONObject rank=facts.optJSONObject("socialLinks."+slug+".rank");if(rank!=null&&!rank.isNull("low")&&rank.getInt("low")>0&&earliest.matches("[0-9]{2}-[0-9]{2}")){String[] pieces=earliest.split("-");if(slot/2<GameDate.slot(Integer.parseInt(pieces[0]),Integer.parseInt(pieces[1]),false)/2){consistent=false;conflicts.put(slug+" 当前等级与来源最早解锁日期冲突");}}}
        checkpoint.put("consistencyValidated",consistent).put("source_conflicts",conflicts);if(date==null)checkpoint.put("date",new JSONObject().put("ordinal",slot/2));context.put("checkpoint",checkpoint);if(!context.has("gameEdition"))context.put("gameEdition","P3R-base");
    }
    private static JSONObject intersection(JSONObject a,JSONObject b)throws Exception {
        if(a==null)return copy(b);Long lo=a.isNull("low")?null:a.getLong("low"),hi=a.isNull("high")?null:a.getLong("high"),bl=b.isNull("low")?null:b.getLong("low"),bh=b.isNull("high")?null:b.getLong("high");if(bl!=null)lo=lo==null?bl:Math.max(lo,bl);if(bh!=null)hi=hi==null?bh:Math.min(hi,bh);if(lo!=null&&hi!=null&&lo>hi)throw new IllegalArgumentException("Conflicting derived checkpoint intervals");return interval(lo,hi);
    }
    private static JSONObject slotState(JSONObject context,int slot){JSONObject calendar=context.optJSONObject("calendar"),states=calendar==null?null:calendar.optJSONObject("slotStates");return states==null?null:states.optJSONObject(String.valueOf(slot));}
    private static boolean known(JSONObject facts,String field){JSONObject v=facts.optJSONObject(field);if(v==null)return false;Object low=v.opt("low"),high=v.opt("high");return(low instanceof Integer||low instanceof Long)&&(high instanceof Integer||high instanceof Long)&&((Number)low).longValue()==((Number)high).longValue();}
    private static JSONObject copy(JSONObject object)throws JSONException{return new JSONObject(object.toString());}
    private static JSONObject interval(Object low,Object high)throws JSONException{return new JSONObject().put("low",low==null?JSONObject.NULL:low).put("high",high==null?JSONObject.NULL:high);}
    private static JSONObject diag(String code,String message)throws JSONException{return new JSONObject().put("code",code).put("message",message);}
    private static void append(JSONArray target,JSONArray source)throws JSONException{for(int i=0;i<source.length();i++)target.put(source.get(i));}
    private static JSONArray filterEntities(JSONArray raw,Set<String> selected)throws JSONException{JSONArray out=new JSONArray();for(int i=0;i<raw.length();i++){JSONObject item=raw.getJSONObject(i);if(item.isNull("entity")||!item.has("entity")||selected.contains(item.optString("entity")))out.put(item);}return out;}
    private static JSONObject issue(String status,String reason)throws JSONException{return new JSONObject().put("status",status).put("steps",new JSONArray()).put("explanations",new JSONArray().put(reason));}
    private static String canonical(Object value)throws JSONException {if(value instanceof JSONObject){JSONObject o=(JSONObject)value;List<String> keys=new ArrayList<>();for(Iterator<String> it=o.keys();it.hasNext();)keys.add(it.next());Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys)out.append(JSONObject.quote(key)).append(':').append(canonical(o.get(key))).append(',');return out.append('}').toString();}if(value instanceof JSONArray){StringBuilder out=new StringBuilder("[");JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)out.append(canonical(a.get(i))).append(',');return out.append(']').toString();}return String.valueOf(value);}
}
