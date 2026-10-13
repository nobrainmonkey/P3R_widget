package app.p3r.dayplanner.v2;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import static org.junit.Assert.*;

/** Tests against the immutable audited source snapshot, not a game replay. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class NativeExpandedRulesTest {
    private static NativeExpandedRules cached;
    private static NativeExpandedRules adapter()throws Exception {if(cached==null)cached=new NativeExpandedRules(RuntimeEnvironment.getApplication());return cached;}
    private static JSONObject o(String raw)throws Exception{return new JSONObject(raw);}
    private static JSONArray a(String raw)throws Exception{return new JSONArray(raw);}
    private static int slot(String date){String[] parts=date.split("-");int month=Integer.parseInt(parts[0]),day=Integer.parseInt(parts[1]);return(int)ChronoUnit.DAYS.between(LocalDate.of(2009,4,1),LocalDate.of(month<4?2010:2009,month,day))*2;}
    private static String f(String slug,String name){return"socialLinks."+slug+"."+name;}
    private static JSONObject interval(Object low,Object high)throws Exception{return new JSONObject().put("low",low==null?JSONObject.NULL:low).put("high",high==null?JSONObject.NULL:high);}
    private static void fact(JSONObject state,String slug,String name,Object value)throws Exception{state.getJSONObject("facts").put(f(slug,name),interval(value,value));}
    private static JSONObject baseline(String date,String slug,int rank)throws Exception{
        JSONObject facts=new JSONObject();JSONObject state=new JSONObject().put("run_id","fixture-only").put("rules_version",adapter().version).put("revision",4).put("game_slot",slot(date)).put("cash",JSONObject.NULL).put("visits_by_stage",new JSONObject()).put("facts",facts);
        for(String link:new String[]{"magician","chariot"}){fact(state,link,"rank",link.equals(slug)?rank:10);fact(state,link,"normal",1);fact(state,link,"ready",1);fact(state,link,"readyRank",link.equals(slug)?rank:10);}
        return state;
    }
    private static JSONObject context(JSONObject state)throws Exception{
        JSONObject current=new JSONObject().put("remainingCapacity",1).put("knownStoryLock",false);
        return new JSONObject().put("gameEdition","P3R-base").put("checkpoint",new JSONObject().put("consistencyValidated",true).put("date",new JSONObject().put("ordinal",state.getInt("game_slot")/2)))
            .put("calendar",new JSONObject().put("slotStates",new JSONObject().put(String.valueOf(state.getInt("game_slot")),current)).put("entityDateStates",new JSONObject())).put("storyFlags",new JSONObject());
    }
    private static JSONObject policy(String event,String choices)throws Exception{return new JSONObject().put("declared",true).put("mode","source_option_ordinals").put("events",new JSONObject().put(event,a(choices)));}
    private static JSONObject built(JSONObject state,JSONObject policy)throws Exception{return adapter().build(state,context(state),policy);}
    private static JSONObject action(JSONObject result,String slug,int to)throws Exception{
        JSONArray actions=result.getJSONObject("rules").getJSONArray("actions");String id="p3r.expanded."+slug+".rank_"+String.format(java.util.Locale.ROOT,"%02d",to);
        for(int i=0;i<actions.length();i++)if(actions.getJSONObject(i).getString("id").equals(id))return actions.getJSONObject(i);return null;
    }
    private static JSONObject candidate(JSONObject result,String slug,String date)throws Exception{
        JSONArray all=result.getJSONArray("calendar_candidates");for(int i=0;i<all.length();i++){JSONObject candidate=all.getJSONObject(i);if(candidate.getString("entity").equals(slug)&&candidate.getInt("slot")==slot(date))return candidate;}return null;
    }
    private static JSONObject metadata(JSONObject result,JSONObject action)throws Exception{return result.getJSONObject("action_metadata").getJSONObject(action.getString("id"));}
    private static boolean hasSlot(JSONObject action,int slot)throws Exception{JSONArray slots=action.getJSONArray("slots");for(int i=0;i<slots.length();i++)if(slots.getInt(i)==slot)return true;return false;}

    @Test public void pinsSourceManifestAndScope()throws Exception{
        assertEquals("p3r-expanded-0.3.0-11d09600d77f-a2-e2",adapter().version);assertEquals(40,adapter().firstSlot);assertEquals(612,adapter().horizon);
        JSONObject result=built(baseline("04-24","magician",2),new JSONObject());
        assertEquals(2,result.getJSONArray("supported_goal_fields").length());assertFalse(result.getJSONObject("rules").getBoolean("coverage_complete"));assertFalse(result.getJSONObject("rules").getBoolean("synthetic"));
    }

    @Test public void preservesObservedCheckpointAndContext()throws Exception{
        JSONObject state=baseline("10-22","magician",3),context=context(state),policy=policy("p3r.magician.event_rank_04","[1,3]");
        String before=state.toString(),beforeContext=context.toString(),beforePolicy=policy.toString();
        adapter().build(state,context,policy);assertEquals(before,state.toString());assertEquals(beforeContext,context.toString());assertEquals(beforePolicy,policy.toString());
    }

    @Test public void validatedVisibleRankAvoidsRedundantUnlockQuestion()throws Exception{
        JSONObject state=baseline("10-22","magician",2),result=built(state,new JSONObject());
        assertFalse(state.getJSONObject("facts").has(f("magician","alreadyUnlocked")));
        assertEquals("validated_observed_rank_at_least_1",result.getJSONObject("derived_checkpoint_facts").getString(f("magician","alreadyUnlocked")));
        assertEquals(1,result.getJSONObject("engine_snapshot").getJSONObject("values").getJSONObject(f("magician","alreadyUnlocked")).getInt("low"));
    }

    @Test public void explicitLockedOrAbnormalLinkHasNoRankActions()throws Exception{
        JSONObject state=baseline("10-22","magician",3);fact(state,"magician","alreadyUnlocked",0);
        assertNull(action(built(state,new JSONObject()),"magician",4));
        state=baseline("10-22","magician",3);fact(state,"magician","normal",0);assertNull(action(built(state,new JSONObject()),"magician",4));
    }

    @Test public void unknownCurrentRankIsNotInvented()throws Exception{
        JSONObject state=baseline("10-22","magician",3);fact(state,"magician","rank",null);
        JSONObject result=built(state,new JSONObject());assertEquals(0,result.getJSONObject("rules").getJSONArray("actions").length());
        assertTrue(result.getJSONArray("diagnostics").toString().contains("current_link_state_unknown"));
    }

    @Test public void arbitraryCurrentRankDoesNotInheritFixedRouteRank()throws Exception{
        JSONObject result=built(baseline("10-22","magician",2),new JSONObject());JSONObject action=action(result,"magician",3);
        assertNotNull(action);assertTrue(hasSlot(action,slot("10-22")));assertEquals(2,action.getJSONObject("requirements").getInt(f("magician","rank")));assertEquals(2,action.getJSONObject("maximums").getInt(f("magician","rank")));
    }

    @Test public void missingPolicyNeverChoosesOptimalAnswersOrDefaultsAffinityToZero()throws Exception{
        JSONObject result=built(baseline("10-22","magician",2),new JSONObject()),action=action(result,"magician",3);
        assertNotNull(action);assertTrue(action.getJSONObject("assignments").isNull(f("magician","affinityLowerBound")));assertNull(action(result,"magician",4));
        assertFalse(result.getBoolean("source_policy_declared"));assertTrue(result.getJSONArray("observation_checkpoints").length()>0);
    }

    @Test public void declaredSourceOptionsProveConservativeNextGateOnly()throws Exception{
        JSONObject result=built(baseline("10-22","magician",2),policy("p3r.magician.event_rank_03","[3,2]"));
        JSONObject event=action(result,"magician",3),gain=event.getJSONObject("assignments").getJSONObject(f("magician","affinityLowerBound"));
        assertEquals(20,gain.getInt("low"));assertTrue(gain.isNull("high"));assertNotNull(action(result,"magician",4));assertNull(action(result,"magician",5));
        JSONObject meta=metadata(result,event);assertEquals("魔术师",meta.getString("name_zh_cn"));assertEquals("魔术师 · 2→3",meta.getString("display_title"));
        assertEquals("[3,2]",meta.getJSONObject("event_gain").getJSONArray("selected_source_options").toString());assertFalse(meta.getBoolean("exact_affinity_balance_claimed"));
    }

    @Test public void partialChoiceUsesOnlyKnownContribution()throws Exception{
        JSONObject result=built(baseline("10-22","magician",2),policy("p3r.magician.event_rank_03","[3,null]"));
        JSONObject gain=metadata(result,action(result,"magician",3)).getJSONObject("event_gain");assertEquals(15,gain.getInt("lower_bound"));assertFalse(gain.getBoolean("all_choices_known"));assertNotNull(action(result,"magician",4));
    }

    @Test public void allUnknownChoicePolicyDoesNotCreateZeroKnowledge()throws Exception{
        JSONObject result=built(baseline("10-22","magician",2),policy("p3r.magician.event_rank_03","[null,null]"));
        assertTrue(metadata(result,action(result,"magician",3)).getJSONObject("event_gain").isNull("lower_bound"));assertNull(action(result,"magician",4));
    }

    @Test public void newRankKnowledgeReplacesOldBoundInsteadOfAddingOrSpending()throws Exception{
        JSONObject state=baseline("10-22","magician",2);state.getJSONObject("facts").put(f("magician","affinityLowerBound"),interval(99,null));fact(state,"magician","affinityRank",2);fact(state,"magician","affinityValid",1);
        JSONObject result=built(state,policy("p3r.magician.event_rank_03","[1,1]")),event=action(result,"magician",3);
        JSONObject after=PlannerEngine.transition(result.getJSONObject("rules"),result.getJSONObject("engine_snapshot"),event,new JSONObject()).getJSONObject("state");
        assertEquals(0,after.getJSONObject("values").getJSONObject(f("magician","affinityLowerBound")).getInt("low"));assertTrue(after.getJSONObject("values").getJSONObject(f("magician","affinityLowerBound")).isNull("high"));
        assertTrue(after.getJSONObject("values").getJSONObject(f("magician","ready")).isNull("low"));
    }

    @Test public void chariotRankSevenConflictDoesNotEnableBonusLookup()throws Exception{
        JSONObject state=baseline("06-04","chariot",6),policy=policy("p3r.chariot.event_rank_07","[1,1]");policy.put("matching_arcana_now",true).put("bonus_readiness_lookup",true);
        JSONObject result=built(state,policy),event=action(result,"chariot",7),gain=metadata(result,event).getJSONObject("event_gain");
        assertEquals(20,gain.getInt("lower_bound"));assertFalse(gain.getBoolean("bonus_lookup_enabled"));assertNull(action(result,"chariot",8));assertTrue(metadata(result,event).isNull("next_ready"));
    }

    @Test public void observedNotReadyOverridesSourceBoundDeduction()throws Exception{
        JSONObject state=baseline("10-22","magician",3);fact(state,"magician","ready",0);fact(state,"magician","affinityRank",3);fact(state,"magician","affinityValid",1);state.getJSONObject("facts").put(f("magician","affinityLowerBound"),interval(99,null));
        JSONObject result=built(state,new JSONObject());assertNull(action(result,"magician",4));assertTrue(result.getJSONArray("diagnostics").toString().contains("observed_not_ready"));
    }

    @Test public void staleReadinessDoesNotAuthorizeCurrentRankEvent()throws Exception{
        JSONObject state=baseline("10-22","magician",3);fact(state,"magician","readyRank",2);
        assertNull(action(built(state,new JSONObject()),"magician",4));
    }

    @Test public void currentProvenanceBoundCanDeriveReadinessWithoutInventingExactPoints()throws Exception{
        JSONObject state=baseline("10-22","magician",3);fact(state,"magician","ready",null);fact(state,"magician","readyRank",null);fact(state,"magician","affinityRank",3);fact(state,"magician","affinityValid",1);state.getJSONObject("facts").put(f("magician","affinityLowerBound"),interval(15,null));
        JSONObject result=built(state,new JSONObject());assertNotNull(action(result,"magician",4));assertTrue(state.getJSONObject("facts").getJSONObject(f("magician","ready")).isNull("low"));
    }

    @Test public void observedReadyOneStepStopsWhenFollowingGateUnknown()throws Exception{
        JSONObject result=built(baseline("10-22","magician",7),new JSONObject());assertNotNull(action(result,"magician",8));assertNull(action(result,"magician",9));assertEquals(1,result.getJSONObject("rules").getJSONArray("actions").length());
    }

    @Test public void exactRankGateCannotBeSkipped()throws Exception{
        JSONObject result=built(baseline("10-22","magician",2),policy("p3r.magician.event_rank_03","[3,2]"));JSONObject initial=result.getJSONObject("engine_snapshot"),future=action(result,"magician",4);
        try{PlannerEngine.transition(result.getJSONObject("rules"),initial,future,new JSONObject());fail("Cannot skip rank3");}catch(PlannerEngine.RuleViolation expected){assertEquals("requirement",expected.code);}
    }

    @Test public void normalCheckpointDoesNotNeedFuturePerDateFlags()throws Exception{
        JSONObject state=baseline("10-22","magician",2),context=context(state);JSONObject result=adapter().build(state,context,new JSONObject());
        JSONObject future=candidate(result,"magician","10-23");assertNotNull(future);assertTrue(future.getString("status").startsWith("conditional_available"));assertTrue(future.getJSONArray("assumptions").toString().contains("A.calendar_context_complete_enough"));assertEquals(0,context.getJSONObject("calendar").getJSONObject("entityDateStates").length());
    }

    @Test public void explicitStoryLockBeatsPositiveSourceCell()throws Exception{
        JSONObject state=baseline("10-22","magician",2),context=context(state);context.getJSONObject("calendar").getJSONObject("slotStates").getJSONObject(String.valueOf(state.getInt("game_slot"))).put("knownStoryLock",true);
        JSONObject result=adapter().build(state,context,new JSONObject());assertEquals("blocked_known_state",candidate(result,"magician","10-22").getString("status"));assertFalse(hasSlot(action(result,"magician",3),slot("10-22")));
    }

    @Test public void unknownCurrentSpendBlocksOnlyCurrentCandidate()throws Exception{
        JSONObject state=baseline("10-22","magician",2),context=context(state);context.getJSONObject("calendar").getJSONObject("slotStates").getJSONObject(String.valueOf(state.getInt("game_slot"))).remove("remainingCapacity");
        JSONObject result=adapter().build(state,context,new JSONObject());assertEquals("unknown_eligibility",candidate(result,"magician","10-22").getString("status"));assertTrue(candidate(result,"magician","10-23").getString("status").startsWith("conditional_available"));
    }

    @Test public void explicitUnknownEntityOverrideIsNotIgnored()throws Exception{
        JSONObject state=baseline("10-22","magician",2),context=context(state);context.getJSONObject("calendar").getJSONObject("entityDateStates").put("magician",new JSONObject().put(String.valueOf(state.getInt("game_slot")/2),new JSONObject().put("knownUnavailable",JSONObject.NULL)));
        JSONObject result=adapter().build(state,context,new JSONObject());assertEquals("unknown_eligibility",candidate(result,"magician","10-22").getString("status"));
    }

    @Test public void christmasNeedsExplicitInvitationCondition()throws Exception{
        JSONObject state=baseline("12-24","magician",5),context=context(state);JSONObject result=adapter().build(state,context,new JSONObject());assertEquals("unknown_eligibility",candidate(result,"magician","12-24").getString("status"));
        context.getJSONObject("storyFlags").put("christmasAllInvitationsDeclined",true);result=adapter().build(state,context,new JSONObject());assertTrue(candidate(result,"magician","12-24").getString("status").startsWith("conditional_available"));
    }

    @Test public void january21NeedsSpecificKnownFlagAndResolvedStorySlot()throws Exception{
        JSONObject state=baseline("01-21","chariot",5),context=context(state);JSONObject result=adapter().build(state,context,new JSONObject());assertEquals("unknown_eligibility",candidate(result,"chariot","01-21").getString("status"));
        context.getJSONObject("storyFlags").put("junpeiWhiteFlowersGiven",false);result=adapter().build(state,context,new JSONObject());assertTrue(candidate(result,"chariot","01-21").getString("status").startsWith("conditional_available"));
        context.getJSONObject("calendar").getJSONObject("slotStates").getJSONObject(String.valueOf(state.getInt("game_slot"))).put("knownStoryLock",JSONObject.NULL);result=adapter().build(state,context,new JSONObject());assertEquals("unknown_eligibility",candidate(result,"chariot","01-21").getString("status"));
    }

    @Test public void introductionsAreNotOrdinaryRepeatVisits()throws Exception{
        JSONObject magician=built(baseline("04-22","magician",1),new JSONObject());assertNull(candidate(magician,"magician","04-22"));
        JSONObject chariot=built(baseline("04-23","chariot",1),new JSONObject());assertNull(candidate(chariot,"chariot","04-23"));
    }

    @Test public void dateAxisUsesFixedGameEpochAcrossNewYear()throws Exception{
        JSONObject result=built(baseline("10-22","magician",2),new JSONObject()),labels=result.getJSONObject("labels");
        assertEquals("04-21",labels.getJSONObject("40").getString("date"));assertEquals("01-31",labels.getJSONObject("611").getString("date"));assertEquals("evening",labels.getJSONObject("611").getString("period"));
    }

    @Test public void earlyCheckpointRoutesToStoryBoundaryNotImpossibility()throws Exception{
        JSONObject state=baseline("04-10","magician",0),result=built(state,new JSONObject());assertEquals("requires_story_progression_to_planning_boundary",result.getString("status"));assertEquals(40,result.getInt("planning_boundary_slot"));assertEquals(0,result.getJSONObject("rules").getJSONArray("actions").length());
    }

    @Test public void staleRuleVersionRequiresExplicitRebase()throws Exception{
        JSONObject state=baseline("10-22","magician",2).put("rules_version","older"),result=built(state,new JSONObject());assertEquals("unsupported",result.getString("status"));assertTrue(result.getBoolean("requires_rebase"));assertEquals("older",state.getString("rules_version"));
    }

    @Test public void futureRankPlanRemainsConditionalAndUnknownNeverImpossible()throws Exception{
        JSONObject state=baseline("10-22","magician",2),context=context(state);JSONArray goal=new JSONArray().put(new JSONObject().put("field",f("magician","rank")).put("target",3));
        JSONObject result=adapter().plan(state,context,new JSONObject(),goal,new JSONObject(),4,10000);assertEquals("conditional",result.getString("status"));assertFalse(result.getBoolean("observed_state_mutated"));
        goal.getJSONObject(0).put("target",10);result=adapter().plan(state,context,new JSONObject(),goal,new JSONObject(),4,10000);assertNotEquals("proven_infeasible",result.getString("status"));
    }

    @Test public void optionPolicyMustBeExplicitAndOrdinalsMustExist()throws Exception{
        JSONObject state=baseline("10-22","magician",2),undeclared=policy("p3r.magician.event_rank_03","[3,2]").put("declared",false);
        try{built(state,undeclared);fail("Undeclared policy");}catch(IllegalArgumentException expected){}
        try{built(state,policy("p3r.magician.event_rank_03","[99,2]"));fail("Unknown ordinal");}catch(IllegalArgumentException expected){}
    }

    @Test public void invalidCheckpointContextCannotBeUsed()throws Exception{
        JSONObject state=baseline("10-22","magician",2),context=context(state);context.getJSONObject("checkpoint").getJSONObject("date").put("ordinal",0);
        JSONObject result=adapter().build(state,context,new JSONObject());assertEquals("unknown",result.getString("status"));assertEquals(0,result.getJSONObject("rules").getJSONArray("actions").length());
    }

    @Test public void explicitlyDeclaredMaximumPolicyRemainsForecastOnlyAndStopsAtShortfall()throws Exception{
        JSONObject state=baseline("10-22","magician",2),policy=o("{\"declared\":true,\"mode\":\"source_max_base_points\"}"),result=built(state,policy);
        JSONObject event=action(result,"magician",3),meta=metadata(result,event),gain=meta.getJSONObject("event_gain");
        assertEquals(20,gain.getInt("lower_bound"));assertTrue(gain.getBoolean("forecast_max_policy"));assertFalse(gain.getBoolean("source_ordinals_are_game_instructions"));assertFalse(gain.getBoolean("bonus_lookup_enabled"));
        assertTrue(meta.getJSONArray("assumptions").toString().contains("A.source_max_base_points_forecast_only"));assertNotNull(action(result,"magician",7));assertNull(action(result,"magician",8));
        assertEquals(2,state.getJSONObject("facts").getJSONObject(f("magician","rank")).getInt("low"));
    }

    @Test public void maximumPolicyIsNeverEnabledJustByNamingMode()throws Exception{
        JSONObject result=built(baseline("10-22","magician",2),o("{\"mode\":\"source_max_base_points\"}"));
        JSONObject gain=metadata(result,action(result,"magician",3)).getJSONObject("event_gain");assertFalse(gain.getBoolean("forecast_max_policy"));assertTrue(gain.isNull("lower_bound"));
    }
    @Test public void octoberMultiStepGoalSearchReplaysFiveRanks()throws Exception{
        JSONObject state=baseline("10-22","magician",2),ctx=context(state),policy=o("{\"declared\":true,\"mode\":\"source_max_base_points\"}");
        JSONArray goals=new JSONArray().put(new JSONObject().put("field",f("magician","rank")).put("target",7).put("priority",1));
        JSONObject result=adapter().plan(state,ctx,policy,goals,new JSONObject(),8,20000);
        assertEquals("conditional",result.getString("status"));int count=0;JSONArray dates=new JSONArray();
        JSONObject built=adapter().build(state,ctx,policy);JSONArray steps=result.getJSONArray("steps");
        for(int n=0;n<steps.length();n++){JSONObject step=steps.getJSONObject(n);if(!step.getString("action_id").equals("__wait__")){count++;dates.put(built.getJSONObject("labels").getJSONObject(String.valueOf(step.getInt("slot"))).getString("date"));}}
        assertEquals(5,count);JSONObject replay=PlannerEngine.validatePlan(built.getJSONObject("rules"),built.getJSONObject("engine_snapshot"),steps,new JSONObject());
        assertEquals(7,replay.getJSONObject("values").getJSONObject(f("magician","rank")).getInt("low"));
        assertEquals(2,state.getJSONObject("facts").getJSONObject(f("magician","rank")).getInt("low"));
        System.out.println("OCTOBER_MULTI_STEP_DATES="+dates);
    }

    @Test public void socialOnlyPlansPreserveDormantUnmodeledVisitCaps()throws Exception{
        JSONObject state=baseline("10-22","magician",2),constraints=o("{\"visit_caps\":{\"p3r.tower.stage_02\":1}}");
        JSONArray goals=new JSONArray().put(new JSONObject().put("field",f("magician","rank")).put("target",3));
        JSONObject result=adapter().plan(state,context(state),new JSONObject(),goals,constraints,4,10000);
        assertEquals("conditional",result.getString("status"));assertEquals(1,result.getJSONObject("dormant_visit_caps").getInt("p3r.tower.stage_02"));
        assertEquals(1,constraints.getJSONObject("visit_caps").getInt("p3r.tower.stage_02"));assertFalse(result.getBoolean("contains_dungeon_actions"));
    }

}
