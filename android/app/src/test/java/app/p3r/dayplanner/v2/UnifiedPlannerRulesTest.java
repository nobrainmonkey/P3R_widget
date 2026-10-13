package app.p3r.dayplanner.v2;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

/** Composition tests against pinned source data, not observed gameplay replays. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class UnifiedPlannerRulesTest {
    private static UnifiedPlannerRules cached;
    private static UnifiedPlannerRules adapter()throws Exception{if(cached==null)cached=new UnifiedPlannerRules(RuntimeEnvironment.getApplication(),new PlannerRules(RuntimeEnvironment.getApplication()));return cached;}
    private static JSONObject o(String raw)throws Exception{return new JSONObject(raw);}
    private static String f(String slug,String suffix){return "socialLinks."+slug+"."+suffix;}
    private static JSONObject iv(Object low,Object high)throws Exception{return new JSONObject().put("low",low==null?JSONObject.NULL:low).put("high",high==null?JSONObject.NULL:high);}
    private static void fact(JSONObject state,String slug,String suffix,Object value)throws Exception{state.getJSONObject("facts").put(f(slug,suffix),iv(value,value));}
    private static JSONObject state(int month,int day)throws Exception{
        int slot=GameDate.slot(month,day,false);JSONObject context=o("{\"gameEdition\":\"P3R-base\",\"checkpoint\":{\"userConfirmedCurrentState\":true},\"calendar\":{\"slotStates\":{},\"entityDateStates\":{}},\"storyFlags\":{}}");context.getJSONObject("calendar").getJSONObject("slotStates").put(String.valueOf(slot),o("{\"remainingCapacity\":1,\"knownStoryLock\":false}"));
        return new JSONObject().put("run_id","fixture").put("rules_version",adapter().version).put("revision",4).put("game_slot",slot).put("cash",JSONObject.NULL).put("facts",new JSONObject()).put("visits_by_stage",new JSONObject()).put("planning_context",context).put("option_policy",new JSONObject());
    }
    private static void link(JSONObject state,String slug,int rank,Boolean ready)throws Exception{fact(state,slug,"rank",rank);fact(state,slug,"normal",1);fact(state,slug,"ready",ready==null?null:ready?1:0);fact(state,slug,"readyRank",ready==null?null:rank);}
    private static JSONArray goal(String slug,int target)throws Exception{return new JSONArray().put(new JSONObject().put("field",f(slug,"rank")).put("target",target).put("priority",1));}
    private static int nonWait(JSONObject result)throws Exception{int count=0;JSONArray steps=result.getJSONArray("steps");for(int i=0;i<steps.length();i++)if(!steps.getJSONObject(i).getString("action_id").equals("__wait__"))count++;return count;}
    private static JSONObject action(JSONObject built,String id)throws Exception{JSONArray actions=built.getJSONObject("rules").getJSONArray("actions");for(int i=0;i<actions.length();i++)if(actions.getJSONObject(i).getString("id").equals(id))return actions.getJSONObject(i);return null;}
    private static boolean has(JSONArray array,int value)throws Exception{for(int i=0;i<array.length();i++)if(array.getInt(i)==value)return true;return false;}

    @Test public void compositePinsBothSemanticVersions()throws Exception{assertTrue(adapter().version.contains("p3r-manual-0.4.0-5c6c350e7cd8-a4-e2"));assertTrue(adapter().version.contains("adapter2"));}
    @Test public void sunCanReachMaxBeforeJanuaryWithThreeGuardedVisits()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",7,null);JSONArray goals=goal("sun",10);String before=state.toString();JSONObject result=adapter().plan(state,goals,new JSONObject(),8,10000);
        assertEquals("conditional",result.getString("status"));assertEquals(3,nonWait(result));assertEquals(10,result.getJSONObject("forecast").getJSONObject("values").getJSONObject(f("sun","rank")).getInt("low"));
        JSONObject compiled=adapter().build(state,goals,new JSONObject()),replay=PlannerEngine.validatePlan(compiled.getJSONObject("rules"),compiled.getJSONObject("engine_snapshot"),result.getJSONArray("steps"),compiled.getJSONObject("active_constraints"));assertEquals(10,replay.getJSONObject("values").getJSONObject(f("sun","rank")).getInt("low"));
        JSONArray dates=new JSONArray();for(int i=0;i<result.getJSONArray("steps").length();i++){JSONObject step=result.getJSONArray("steps").getJSONObject(i);if(!step.getString("action_id").equals("__wait__")){assertTrue(step.getInt("slot")<GameDate.slot(1,2,false));dates.put(result.getJSONObject("labels").getJSONObject(String.valueOf(step.getInt("slot"))).getString("date"));}}
        System.out.println("COMPOSITE_SUN7_TO10_DATES="+dates);assertEquals(before,state.toString());
    }
    @Test public void devilUsesFullCalendarNormalRankTransition()throws Exception{
        JSONObject state=state(10,22);link(state,"devil",8,null);JSONObject result=adapter().plan(state,goal("devil",10),new JSONObject(),8,10000);assertEquals("conditional",result.getString("status"));assertEquals(2,nonWait(result));assertEquals(10,result.getJSONObject("forecast").getJSONObject("values").getJSONObject(f("devil","rank")).getInt("low"));
    }
    @Test public void exactSunRankGuardsPreventSkippingOrEleven()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",9,null);JSONObject built=adapter().build(state,goal("sun",10),new JSONObject()),action=action(built,"p3r.manual.sun.rank_10");assertEquals(9,action.getJSONObject("requirements").getInt(f("sun","rank")));assertEquals(9,action.getJSONObject("maximums").getInt(f("sun","rank")));assertNull(action(built,"p3r.manual.sun.rank_11"));
    }
    @Test public void sourceCalendarStoryLocksOverrideSunOpportunity()throws Exception{
        JSONObject state=state(10,25);link(state,"sun",9,null);int slot=state.getInt("game_slot");state.getJSONObject("planning_context").getJSONObject("calendar").getJSONObject("slotStates").getJSONObject(String.valueOf(slot)).put("knownStoryLock",true);
        JSONObject built=adapter().build(state,goal("sun",10),new JSONObject());assertFalse(has(action(built,"p3r.manual.sun.rank_10").getJSONArray("slots"),slot));
    }
    @Test public void knownLockedSunDoesNotRegainUnlockFromVisibleRank()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",9,null);fact(state,"sun","alreadyUnlocked",0);JSONObject built=adapter().build(state,goal("sun",10),new JSONObject());assertNull(action(built,"p3r.manual.sun.rank_10"));
    }
    @Test public void unsupportedGoalDoesNotSuppressUsefulSupportedSteps()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",9,null);link(state,"fool",3,null);JSONArray goals=goal("sun",10).put(new JSONObject().put("field",f("fool","rank")).put("target",10));JSONObject result=adapter().plan(state,goals,new JSONObject(),8,10000);
        assertEquals("unknown",result.getString("status"));assertEquals("conditional",result.getString("supported_plan_status"));assertEquals(1,result.getJSONArray("unsupported_goals").length());assertEquals(1,nonWait(result));assertFalse(result.getBoolean("all_requested_goals_supported"));
    }
    @Test public void unknownVisibleRankIsSeparatedFromSupportedGoal()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",9,null);fact(state,"magician","rank",null);JSONArray goals=goal("sun",10).put(new JSONObject().put("field",f("magician","rank")).put("target",10));JSONObject result=adapter().plan(state,goals,new JSONObject(),8,10000);assertEquals("unknown",result.getString("status"));assertEquals(1,result.getJSONArray("goals_needing_observation").length());assertEquals(1,nonWait(result));
    }
    @Test public void conservativeUnknownReadinessHasDatedActionableReservation()throws Exception{
        JSONObject state=state(10,22);link(state,"magician",3,null);JSONObject result=adapter().plan(state,goal("magician",4),new JSONObject(),4,10000);assertEquals("unknown",result.getString("status"));assertEquals(0,nonWait(result));
        JSONObject r=result.getJSONArray("adaptive_reservations").getJSONObject(0);assertEquals("10-22",r.getString("date"));assertEquals("magician",r.getString("entity"));assertTrue(r.isNull("predicted_rank_delta"));assertTrue(r.isNull("predicted_affinity_gain"));assertTrue(r.isNull("guaranteed_finish_date"));assertEquals(0,r.getInt("observation_only_slot_cost"));
    }
    @Test public void reservationConflictsResolveByGoalPriority()throws Exception{
        JSONObject state=state(10,22);link(state,"magician",3,null);link(state,"chariot",3,null);JSONArray goals=goal("magician",4).put(new JSONObject().put("field",f("chariot","rank")).put("target",4).put("priority",8));JSONObject result=adapter().plan(state,goals,new JSONObject(),4,10000);JSONArray rs=result.getJSONArray("adaptive_reservations");assertEquals(2,rs.length());assertEquals("chariot",rs.getJSONObject(0).getString("entity"));assertNotEquals(rs.getJSONObject(0).getInt("slot"),rs.getJSONObject(1).getInt("slot"));
    }
    @Test public void reservationsCannotDoubleBookCompiledRankStep()throws Exception{
        JSONObject state=state(10,22);link(state,"magician",3,null);link(state,"chariot",2,true);JSONArray goals=goal("magician",4).put(new JSONObject().put("field",f("chariot","rank")).put("target",3));JSONObject result=adapter().plan(state,goals,new JSONObject(),8,10000);Set<Integer> occupied=new HashSet<>();JSONArray steps=result.getJSONArray("steps");for(int i=0;i<steps.length();i++)if(!steps.getJSONObject(i).getString("action_id").equals("__wait__"))occupied.add(steps.getJSONObject(i).getInt("slot"));JSONArray rs=result.getJSONArray("adaptive_reservations");for(int i=0;i<rs.length();i++)assertFalse(occupied.contains(rs.getJSONObject(i).getInt("slot")));
    }
    @Test public void adaptiveCheckpointAfterRankEventIsChronological()throws Exception{
        JSONObject state=state(10,22);link(state,"magician",2,true);JSONObject result=adapter().plan(state,goal("magician",4),new JSONObject(),4,10000);assertEquals(1,nonWait(result));JSONObject r=result.getJSONArray("adaptive_reservations").getJSONObject(0);assertEquals(3,r.getInt("at_rank"));for(int i=0;i<result.getJSONArray("steps").length();i++){JSONObject step=result.getJSONArray("steps").getJSONObject(i);if(!step.getString("action_id").equals("__wait__"))assertTrue(r.getInt("slot")>step.getInt("slot"));}
    }
    @Test public void unknownAffinityFloorZeroIsNotAnExactObservedBalance()throws Exception{
        JSONObject state=state(10,22);link(state,"magician",3,null);String before=state.toString();JSONObject built=adapter().build(state,goal("magician",4),new JSONObject()),value=built.getJSONObject("engine_snapshot").getJSONObject("values").getJSONObject(f("magician","affinityLowerBound"));assertEquals(0,value.getInt("low"));assertTrue(value.isNull("high"));assertEquals(before,state.toString());assertNull(action(built,"p3r.manual.magician.rank_04"));
    }
    @Test public void unknownRankEventChoicesCreateNoInventedPositiveGain()throws Exception{
        JSONObject state=state(10,22);link(state,"magician",2,true);JSONObject built=adapter().build(state,goal("magician",4),new JSONObject()),event=action(built,"p3r.manual.magician.rank_03"),value=event.getJSONObject("assignments").getJSONObject(f("magician","affinityLowerBound"));assertEquals(0,value.getInt("low"));assertTrue(value.isNull("high"));assertNull(action(built,"p3r.manual.magician.rank_04"));
    }
    @Test public void staleContextDateIsNeverSilentlyOverwritten()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",9,null);state.getJSONObject("planning_context").getJSONObject("checkpoint").put("date",new JSONObject().put("ordinal",0));JSONObject result=adapter().plan(state,goal("sun",10),new JSONObject(),4,10000);assertEquals("unknown",result.getString("status"));assertEquals(0,nonWait(result));assertEquals(0,result.getJSONArray("adaptive_reservations").length());
    }
    @Test public void observedEditionMismatchCannotBeRewrittenToBase()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",9,null);state.getJSONObject("planning_context").put("gameEdition","not-base");assertEquals("unsupported",adapter().plan(state,goal("sun",10),new JSONObject(),4,10000).getString("status"));
    }
    @Test public void staleCompositeVersionRequiresReconfirmation()throws Exception{
        JSONObject state=state(10,22).put("rules_version","old");link(state,"sun",9,null);assertEquals("unsupported",adapter().plan(state,goal("sun",10),new JSONObject(),4,10000).getString("status"));assertEquals("old",state.getString("rules_version"));
    }
    @Test public void dormantCapsRemainSavedAndCannotAuthorizeNewDungeonWindows()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",9,null);JSONObject constraints=o("{\"visit_caps\":{\"p3r.tower.future_stage\":1}}");String before=constraints.toString();JSONObject built=adapter().build(state,goal("sun",10),constraints);assertEquals(1,built.getJSONObject("dormant_visit_caps").getInt("p3r.tower.future_stage"));assertEquals(before,constraints.toString());JSONArray actions=built.getJSONObject("rules").getJSONArray("actions");for(int i=0;i<actions.length();i++)if(actions.getJSONObject(i).optBoolean("dungeon_visit"))for(int j=0;j<actions.getJSONObject(i).getJSONArray("slots").length();j++)assertTrue(actions.getJSONObject(i).getJSONArray("slots").getInt(j)>=GameDate.slot(1,2,false));
    }
    @Test public void unknownCappedDungeonVisitsStayUnknownAndBlockPinnedVisit()throws Exception{
        JSONObject state=state(1,2);link(state,"sun",9,null);JSONObject constraints=o("{\"visit_caps\":{\"p3r.tower.stage_10\":1},\"pinned_actions\":{\"553\":\"p3r.action.explore_stage_10\"}}");JSONObject result=adapter().plan(state,goal("sun",10),constraints,4,10000);assertNotEquals("conditional",result.getString("status"));assertFalse(state.getJSONObject("visits_by_stage").has("p3r.tower.stage_10"));
    }
    @Test public void limitedSearchNeverClaimsImpossible()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",1,null);JSONObject result=adapter().plan(state,goal("sun",10),new JSONObject(),1,1);assertEquals("search_limit",result.getString("status"));assertFalse(result.getBoolean("optimality_proven"));
    }
    @Test public void loneRankGoalIsScheduledAtFirstSupportedDateNotMonthsLater()throws Exception{
        JSONObject state=state(10,22);link(state,"magician",2,true);JSONObject result=adapter().plan(state,goal("magician",3),new JSONObject(),8,10000);assertEquals("conditional",result.getString("status"));for(int i=0;i<result.getJSONArray("steps").length();i++){JSONObject step=result.getJSONArray("steps").getJSONObject(i);if(!step.getString("action_id").equals("__wait__")){assertEquals(state.getInt("game_slot"),step.getInt("slot"));return;}}fail("Missing rank step");
    }
    private static JSONArray statGoal(String stat,int rank)throws Exception{return new JSONArray().put(new JSONObject().put("field","socialStats."+stat+".rank").put("target",rank).put("priority",1));}
    private static void statRank(JSONObject state,String stat,int rank)throws Exception{state.getJSONObject("facts").put("socialStats."+stat+".rank",iv(rank,rank));}
    @Test public void progressionVersionAndAllConservativeStageInteriorsAreComposed()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",9,null);JSONObject built=adapter().build(state,goal("sun",10),new JSONObject());assertTrue(adapter().version.contains("p3r-progression-0.5.1"));assertEquals(10,built.getJSONObject("rules").getJSONArray("stages").length());
        for(int i=0;i<built.getJSONObject("rules").getJSONArray("stages").length();i++){JSONObject stage=built.getJSONObject("rules").getJSONArray("stages").getJSONObject(i);if(stage.getString("id").equals("p3r.tower.stage_04"))assertEquals(GameDate.slot(7,18,false),stage.getInt("start"));}
    }
    @Test public void futureRoutineTrainingStaysUncreditedAlternative()throws Exception{
        JSONObject state=state(10,22).put("cash",20000);statRank(state,"courage",5);state.getJSONObject("planning_context").put("progression",o("{\"assumeFutureRoutineAvailability\":true,\"repeatableActivityGuards\":{\"arcade_courage_night\":{\"locationAccessible\":true}}}"));
        String before=state.toString();JSONObject result=adapter().plan(state,statGoal("courage",6),new JSONObject(),8,10000);assertEquals("unknown",result.getString("status"));assertEquals(60,result.getJSONObject("forecast").getJSONObject("values").getJSONObject("socialStats.courage.raw").getLong("low"));assertEquals(5,result.getJSONObject("forecast_stat_ranks").getJSONObject("socialStats.courage.rank").getInt("low"));assertEquals(0,nonWait(result));assertEquals(before,state.toString());assertTrue(result.getBoolean("replay_validated"));JSONArray candidates=result.getJSONArray("conditional_activity_candidates");assertTrue(candidates.length()>0);for(int i=0;i<candidates.length();i++)assertFalse(candidates.getJSONObject(i).getBoolean("forecast_credit"));
    }
    @Test public void unknownBonusCannotMakeInsufficientBaseTrainingReachGoal()throws Exception{
        JSONObject state=state(10,23).put("game_slot",GameDate.slot(10,23,true)).put("cash",3000);statRank(state,"courage",5);int slot=state.getInt("game_slot");JSONObject ctx=state.getJSONObject("planning_context");ctx.getJSONObject("calendar").getJSONObject("slotStates").put(String.valueOf(slot),o("{\"remainingCapacity\":1,\"knownStoryLock\":false,\"slotState\":\"free\"}"));ctx.getJSONObject("calendar").put("statActionStates",new JSONObject().put(String.valueOf(slot),o("{\"arcade_courage_night\":{\"locationAccessible\":true,\"calendar.exactDateActionAvailable\":true}}")));
        JSONObject result=adapter().plan(state,statGoal("courage",6),new JSONObject(),4,10000);assertEquals("unknown",result.getString("status"));assertEquals(64,result.getJSONObject("forecast").getJSONObject("values").getJSONObject("socialStats.courage.raw").getInt("low"));assertEquals(1,result.getJSONArray("unproven_lower_bound_goals").length());
    }
    @Test public void unknownStatRankRequestsVisibleObservationWithoutRawGuess()throws Exception{
        JSONObject state=state(10,22),result=adapter().plan(state,statGoal("courage",6),new JSONObject(),4,10000);assertEquals("unknown",result.getString("status"));assertEquals(1,result.getJSONArray("goals_needing_observation").length());assertTrue(result.getJSONObject("forecast").getJSONObject("values").getJSONObject("socialStats.courage.raw").isNull("low"));
    }
    @Test public void realCompositeActionsAllHaveStrictNumericGuards()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",9,null);JSONObject built=adapter().build(state,goal("sun",10),new JSONObject());JSONArray actions=built.getJSONObject("rules").getJSONArray("actions");for(int i=0;i<actions.length();i++)assertTrue(actions.getJSONObject(i).getBoolean("strict_requirements"));
    }

    @Test public void compositeSchedulesAuditedIntroductionWithoutPriorHistory()throws Exception{
        JSONObject state=state(4,27);fact(state,"emperor","rank",0);String before=state.toString();JSONObject result=adapter().plan(state,goal("emperor",1),new JSONObject(),8,20000);assertEquals("conditional",result.getString("status"));assertEquals(1,nonWait(result));assertEquals("p3r.unlock.emperor",result.getJSONArray("steps").getJSONObject(0).getString("action_id"));assertEquals(1,result.getJSONObject("forecast").getJSONObject("values").getJSONObject(f("emperor","rank")).getInt("low"));assertEquals(before,state.toString());assertTrue(adapter().version.contains("p3r-unlock-0.5.1"));
    }
    @Test public void introductionDoesNotInventRestOfMaxRankRoute()throws Exception{
        JSONObject state=state(4,27);fact(state,"emperor","rank",0);JSONObject result=adapter().plan(state,goal("emperor",10),new JSONObject(),8,20000);assertEquals("unknown",result.getString("status"));assertEquals(1,nonWait(result));assertEquals(1,result.getJSONObject("forecast").getJSONObject("values").getJSONObject(f("emperor","rank")).getInt("low"));assertEquals(0,state.getJSONObject("facts").getJSONObject(f("emperor","rank")).getInt("low"));
    }
    @Test public void routineTrainingCannotSatisfyIntroductionPrerequisite()throws Exception{
        JSONObject state=state(10,22).put("cash",20000);fact(state,"priestess","rank",0);link(state,"fortune",1,null);statRank(state,"courage",5);state.getJSONObject("planning_context").put("progression",o("{\"assumeFutureRoutineAvailability\":true,\"repeatableActivityGuards\":{\"arcade_courage_night\":{\"locationAccessible\":true}}}"));
        JSONObject result=adapter().plan(state,goal("priestess",1),new JSONObject(),12,20000);assertEquals("unknown",result.getString("status"));assertEquals(0,result.getJSONObject("forecast").getJSONObject("values").getJSONObject(f("priestess","rank")).getInt("low"));assertTrue(result.getJSONArray("prerequisite_goals").toString().contains("socialStats.courage.raw"));assertTrue(result.getJSONArray("conditional_activity_candidates").length()>0);assertEquals(0,nonWait(result));assertEquals(20000,result.getJSONObject("forecast").getJSONObject("values").getJSONObject("cash").getInt("low"));
    }
    @Test public void defaultUnknownPreparationsNeverScheduleOldPaymentsAgain()throws Exception{
        JSONObject state=state(10,22).put("cash",40000);fact(state,"devil","rank",0);JSONObject result=adapter().plan(state,goal("devil",1),new JSONObject(),4,10000);assertEquals("unknown",result.getString("status"));assertEquals(0,nonWait(result));assertTrue(result.getJSONArray("observation_checkpoints").toString().contains("preparation_state_unknown"));assertEquals(40000,result.getJSONObject("forecast").getJSONObject("values").getJSONObject("cash").getInt("low"));
    }
    @Test public void existingObservedOnlineGameInventoryCanAuthorizeHermitIntro()throws Exception{
        JSONObject state=state(4,29);fact(state,"hermit","rank",0);state.getJSONObject("planning_context").put("unlock",o("{\"hermit\":{\"observations\":{\"inventory.onlineGameAvailable\":true}}}"));JSONObject result=adapter().plan(state,goal("hermit",1),new JSONObject(),8,20000);assertEquals("conditional",result.getString("status"));assertEquals(1,nonWait(result));assertEquals(1,result.getJSONObject("forecast").getJSONObject("values").getJSONObject(f("hermit","rank")).getInt("low"));assertFalse(state.getJSONObject("facts").has("inventory.onlineGameAvailable"));
    }

    @Test public void octoberDungeonCandidatesUseKnownCapAndNeverForecastEarnings()throws Exception{
        JSONObject state=state(10,22).put("highest_floor",170).put("active_stage","p3r.tower.stage_07");link(state,"sun",9,null);state.getJSONObject("visits_by_stage").put("p3r.tower.stage_07",0);state.getJSONObject("planning_context").put("tartarus",o("{\"normalStoryProgressionAssumed\":true}"));JSONObject caps=o("{\"visit_caps\":{\"p3r.tower.stage_07\":1}}");JSONObject result=adapter().plan(state,goal("sun",10),caps,8,10000);boolean found=false;
        JSONArray candidates=result.getJSONArray("dungeon_candidates");for(int i=0;i<candidates.length();i++){JSONObject c=candidates.getJSONObject(i);if(c.getInt("slot")==GameDate.slot(10,30,true)){found=true;assertTrue(c.isNull("expected_money"));assertTrue(c.isNull("expected_floor"));assertTrue(c.getBoolean("optional_candidate_not_reserved"));}}
        assertTrue(found);assertEquals(0,result.getJSONObject("forecast").getJSONObject("visits").getInt("p3r.tower.stage_07"));assertTrue(state.isNull("cash"));
    }
    @Test public void pinnedOctoberVisitReplaysAndCannotExceedHardCap()throws Exception{
        JSONObject state=state(10,22).put("highest_floor",170).put("active_stage","p3r.tower.stage_07");link(state,"sun",9,null);state.getJSONObject("visits_by_stage").put("p3r.tower.stage_07",0);state.getJSONObject("planning_context").put("tartarus",o("{\"normalStoryProgressionAssumed\":true,\"assumeRoutineFutureEntry\":true}"));JSONObject constraints=o("{\"visit_caps\":{\"p3r.tower.stage_07\":1},\"pinned_actions\":{}}");int slot=GameDate.slot(10,30,true);constraints.getJSONObject("pinned_actions").put(String.valueOf(slot),"p3r.tartarus.stage_07.source_positive_future_night");
        JSONObject result=adapter().plan(state,goal("sun",10),constraints,8,15000);assertEquals("conditional",result.getString("status"));assertEquals(1,result.getJSONObject("forecast").getJSONObject("visits").getInt("p3r.tower.stage_07"));assertTrue(result.getJSONObject("forecast").getJSONObject("values").getJSONObject("cash").isNull("high"));assertEquals(0,state.getJSONObject("visits_by_stage").getInt("p3r.tower.stage_07"));
        constraints.getJSONObject("pinned_actions").put(String.valueOf(GameDate.slot(10,31,true)),"p3r.tartarus.stage_07.routine_future_evening");result=adapter().plan(state,goal("sun",10),constraints,8,15000);assertNotEquals("conditional",result.getString("status"));assertNotEquals("proven_infeasible",result.getString("status"));
    }
    @Test public void unknownStageUsageSuppressesOctoberDungeonCandidate()throws Exception{
        JSONObject state=state(10,22);link(state,"sun",9,null);state.getJSONObject("planning_context").put("tartarus",o("{\"normalStoryProgressionAssumed\":true}"));JSONObject result=adapter().plan(state,goal("sun",10),o("{\"visit_caps\":{\"p3r.tower.stage_07\":1}}"),8,10000);assertEquals(0,result.getJSONArray("dungeon_candidates").length());assertFalse(state.getJSONObject("visits_by_stage").has("p3r.tower.stage_07"));
    }
    @Test public void storyGoalShowsPendingMilestonesWithoutAwardingRanks()throws Exception{
        JSONObject state=state(10,22);fact(state,"fool","rank",7);JSONObject result=adapter().plan(state,goal("fool",8),new JSONObject(),4,10000);assertEquals("unsupported",result.getString("status"));assertEquals(1,result.getJSONArray("story_milestones").length());assertEquals(9,result.getJSONArray("story_milestones").getJSONObject(0).getInt("to_rank"));assertEquals(7,result.getJSONObject("forecast").getJSONObject("values").getJSONObject(f("fool","rank")).getInt("low"));
    }

    @Test public void routineJobAlternativesCannotFinanceTrainingOrUnlock()throws Exception{
        JSONObject state=state(10,19).put("cash",0);fact(state,"priestess","rank",0);link(state,"fortune",1,null);statRank(state,"courage",5);
        state.getJSONObject("planning_context").put("progression",o("{\"assumeFutureRoutineAvailability\":true,\"repeatableActivityGuards\":{\"arcade_courage_night\":{\"locationAccessible\":true},\"job_chagall_minimum\":{\"locationAccessible\":true,\"job.chagall.currentlyAvailable\":true}}}"));
        JSONObject result=adapter().plan(state,goal("priestess",1),new JSONObject(),48,60000);assertEquals("unknown",result.getString("status"));assertEquals(0,nonWait(result));assertEquals(0,result.getJSONObject("forecast").getJSONObject("values").getJSONObject(f("priestess","rank")).getInt("low"));assertEquals(0,result.getJSONObject("forecast").getJSONObject("values").getJSONObject("cash").getLong("low"));assertEquals(0,state.getInt("cash"));boolean job=false,training=false;JSONArray candidates=result.getJSONArray("conditional_activity_candidates");for(int i=0;i<candidates.length();i++){JSONObject candidate=candidates.getJSONObject(i);assertFalse(candidate.getBoolean("forecast_credit"));job|=candidate.getString("action_id").equals("p3r.action.job_chagall_minimum");training|=candidate.getString("action_id").equals("p3r.stat.arcade_courage_night");}assertTrue(job);assertTrue(training);
    }

    private static JSONObject pinnedTowerState()throws Exception{
        JSONObject state=state(10,22).put("highest_floor",170).put("active_stage","p3r.tower.stage_07");state.getJSONObject("visits_by_stage").put("p3r.tower.stage_07",0);state.getJSONObject("planning_context").put("tartarus",o("{\"normalStoryProgressionAssumed\":true}"));return state;
    }
    private static JSONObject towerPin()throws Exception{
        return o("{\"visit_caps\":{\"p3r.tower.stage_07\":1},\"pinned_actions\":{}}").put("pinned_actions",new JSONObject().put(String.valueOf(GameDate.slot(10,30,true)),"p3r.tartarus.stage_07.source_positive_future_night"));
    }
    private static void assertTowerPinReplayed(JSONObject result)throws Exception{
        assertEquals("conditional",result.getString("pin_reservation_status"));assertTrue(result.getBoolean("all_pins_honored"));assertTrue(result.getBoolean("replay_validated"));assertEquals(0,result.getJSONArray("unhonored_pins").length());assertEquals(1,result.getJSONObject("forecast").getJSONObject("visits").getInt("p3r.tower.stage_07"));
        boolean found=false;JSONArray steps=result.getJSONArray("steps");for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);if(step.getInt("slot")==GameDate.slot(10,30,true)){assertEquals("p3r.tartarus.stage_07.source_positive_future_night",step.getString("action_id"));found=true;}}assertTrue(found);
    }
    @Test public void explicitTowerPinRunsWithOnlyUnsupportedStoryGoal()throws Exception{
        JSONObject state=pinnedTowerState();fact(state,"fool","rank",7);String before=state.toString();JSONObject result=adapter().plan(state,goal("fool",8),towerPin(),4,10000);assertEquals("unknown",result.getString("status"));assertEquals("conditional",result.getString("supported_plan_status"));assertEquals(0,result.getJSONArray("planned_goals").length());assertEquals(1,result.getJSONArray("unsupported_goals").length());assertTowerPinReplayed(result);assertEquals(before,state.toString());
    }
    @Test public void explicitTowerPinRunsWithNoNumericGoals()throws Exception{
        JSONObject result=adapter().plan(pinnedTowerState(),new JSONArray(),towerPin(),4,10000);assertEquals("conditional",result.getString("status"));assertTowerPinReplayed(result);
    }
    @Test public void achievedGoalsNeverSkipFutureTowerPin()throws Exception{
        JSONObject state=pinnedTowerState();link(state,"sun",10,null);JSONObject result=adapter().plan(state,goal("sun",10),towerPin(),4,10000);assertEquals("conditional",result.getString("status"));assertTowerPinReplayed(result);assertEquals(10,result.getJSONObject("forecast").getJSONObject("values").getJSONObject(f("sun","rank")).getInt("low"));
    }
    @Test public void unknownCountCannotSilentlyConfirmStoryOnlyTowerPin()throws Exception{
        JSONObject state=pinnedTowerState();fact(state,"fool","rank",7);state.getJSONObject("visits_by_stage").put("p3r.tower.stage_07",JSONObject.NULL);JSONObject result=adapter().plan(state,goal("fool",8),towerPin(),4,10000);assertNotEquals("conditional",result.getString("status"));assertEquals("unconfirmed",result.getString("pin_reservation_status"));assertFalse(result.getBoolean("all_pins_honored"));assertEquals(1,result.getJSONArray("unhonored_pins").length());assertTrue(result.getJSONArray("explanations").toString().contains("手动预留尚未全部"));
    }
    @Test public void spentCapCannotSilentlyConfirmStoryOnlyTowerPin()throws Exception{
        JSONObject state=pinnedTowerState();state.getJSONObject("visits_by_stage").put("p3r.tower.stage_07",1);JSONObject result=adapter().plan(state,new JSONArray(),towerPin(),4,10000);assertNotEquals("conditional",result.getString("status"));assertEquals("unconfirmed",result.getString("pin_reservation_status"));assertFalse(result.getBoolean("all_pins_honored"));assertEquals(1,result.getJSONObject("forecast").getJSONObject("visits").getInt("p3r.tower.stage_07"));
    }
    @Test public void pastPinAndBudgetLimitedPinAreNeverReportedConfirmed()throws Exception{
        JSONObject state=pinnedTowerState(),constraints=towerPin();constraints.getJSONObject("pinned_actions").put(String.valueOf(state.getInt("game_slot")-1),"p3r.tartarus.stage_07.source_positive_future_night");JSONObject result=adapter().plan(state,new JSONArray(),constraints,4,10000);assertEquals("unknown",result.getString("status"));assertEquals("unconfirmed",result.getString("pin_reservation_status"));assertFalse(result.getBoolean("all_pins_honored"));
        result=adapter().plan(pinnedTowerState(),new JSONArray(),towerPin(),4,1);assertEquals("search_limit",result.getString("status"));assertEquals("unconfirmed",result.getString("pin_reservation_status"));assertFalse(result.getBoolean("all_pins_honored"));
    }

    @Test public void pinningRoutineTrainingDoesNotTurnAlternativeIntoCreditedAction()throws Exception{
        JSONObject state=state(10,22).put("cash",20000);statRank(state,"courage",5);state.getJSONObject("planning_context").put("progression",o("{\"assumeFutureRoutineAvailability\":true,\"repeatableActivityGuards\":{\"arcade_courage_night\":{\"locationAccessible\":true}}}"));JSONObject constraints=new JSONObject().put("pinned_actions",new JSONObject().put(String.valueOf(GameDate.slot(10,23,true)),"p3r.stat.arcade_courage_night"));JSONObject result=adapter().plan(state,new JSONArray(),constraints,4,10000);assertEquals("unconfirmed",result.getString("pin_reservation_status"));assertNotEquals("conditional",result.getString("status"));assertEquals(0,nonWait(result));assertEquals(60,result.getJSONObject("forecast").getJSONObject("values").getJSONObject("socialStats.courage.raw").getInt("low"));assertTrue(result.getJSONArray("conditional_activity_candidates").length()>0);assertNotNull(result.getJSONObject("action_metadata").optJSONObject("p3r.stat.arcade_courage_night"));
    }
    @Test public void ordinarySourcePositiveSocialPlanStillWorksWithoutRoutineCredit()throws Exception{
        JSONObject state=state(10,22).put("cash",20000);link(state,"sun",9,null);state.getJSONObject("planning_context").put("progression",o("{\"assumeFutureRoutineAvailability\":true,\"repeatableActivityGuards\":{\"arcade_courage_night\":{\"locationAccessible\":true}}}"));JSONObject result=adapter().plan(state,goal("sun",10),new JSONObject(),4,10000);assertEquals("conditional",result.getString("status"));assertEquals(1,nonWait(result));assertEquals(0,result.getJSONArray("conditional_activity_candidates").length());assertEquals(20000,result.getJSONObject("forecast").getJSONObject("values").getJSONObject("cash").getInt("low"));
    }

}
