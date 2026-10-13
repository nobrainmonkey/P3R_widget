package app.p3r.dayplanner.v2;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Source-adapter tests, not a claim of game replay or all-goal completeness. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class NativeProgressionRulesTest {
    private static NativeProgressionRules cached;
    private NativeProgressionRules adapter()throws Exception{if(cached==null)cached=new NativeProgressionRules(RuntimeEnvironment.getApplication());return cached;}
    private JSONObject o(String s)throws Exception{return new JSONObject(s);}
    private JSONObject interval(Object low,Object high)throws Exception{return new JSONObject().put("low",low==null?JSONObject.NULL:low).put("high",high==null?JSONObject.NULL:high);}
    private JSONObject state(int month,int day)throws Exception{return new JSONObject().put("run_id","source-fixture").put("revision",7).put("rules_version",adapter().version).put("game_slot",GameDate.slot(month,day,true)).put("cash",10000).put("visits_by_stage",o("{\"p3r.tower.stage_07\":null}")).put("facts",o("{\"socialStats.academics.rank\":{\"low\":3,\"high\":3},\"socialStats.charm.rank\":{\"low\":4,\"high\":4},\"socialStats.courage.raw\":{\"low\":76,\"high\":76}}"));}
    private JSONObject context(JSONObject state,String source)throws Exception{int slot=state.getInt("game_slot");return new JSONObject().put("gameEdition","P3R-base").put("checkpoint",new JSONObject().put("consistencyValidated",true).put("date",new JSONObject().put("ordinal",slot/2))).put("calendar",new JSONObject().put("slotStates",new JSONObject().put(""+slot,o("{\"remainingCapacity\":1,\"knownStoryLock\":false,\"slotState\":\"free\"}"))).put("statActionStates",new JSONObject().put(""+slot,new JSONObject().put(source,o("{\"locationAccessible\":true,\"calendar.exactDateActionAvailable\":true}")))));}
    private JSONObject action(JSONObject built,String source)throws Exception{JSONArray list=built.getJSONObject("rules").getJSONArray("actions");for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getString("id").equals(source.equals("job_chagall_minimum")?"p3r.action.job_chagall_minimum":"p3r.stat."+source))return list.getJSONObject(i);return null;}
    @Test public void operationalStagesKeepManualGapsAndCeilings()throws Exception{
        NativeProgressionRules a=adapter();assertEquals(10,a.operationalStages().length());
        for(int[] date:new int[][]{{6,13},{7,9},{7,17},{12,3},{12,9}})assertEquals("unknown",a.identifyStage(GameDate.slot(date[0],date[1],true),null,null).getString("status"));
        JSONObject known=a.identifyStage(GameDate.slot(7,10,true),"p3r.tower.stage_04",92);assertEquals("identified",known.getString("status"));assertFalse(known.getBoolean("nightly_exploration_permitted"));
        assertEquals("needs_reconciliation",a.identifyStage(GameDate.slot(7,10,true),"p3r.tower.stage_04",93).getString("status"));
        assertEquals("p3r.tower.stage_04",a.identifyStage(GameDate.slot(7,18,false),null,null).getString("stage_id"));
    }
    @Test public void dateManualMismatchNeverSilentlyReassignsVisits()throws Exception{assertEquals("needs_reconciliation",adapter().identifyStage(GameDate.slot(10,22,true),"p3r.tower.stage_01",20).getString("status"));}
    @Test public void visibleRankExpandsToInterval()throws Exception{JSONObject facts=adapter().deriveStatFacts(o("{\"socialStats.academics.rank\":{\"low\":3,\"high\":3}}"));assertEquals(55,facts.getJSONObject("socialStats.academics.raw").getInt("low"));assertEquals(99,facts.getJSONObject("socialStats.academics.raw").getInt("high"));assertTrue(facts.getJSONObject("socialStats.charm.raw").isNull("low"));}
    @Test public void maxRankHasUnknownRawUpperBound()throws Exception{JSONObject facts=adapter().deriveStatFacts(o("{\"socialStats.courage.rank\":{\"low\":6,\"high\":6}}"));assertEquals(80,facts.getJSONObject("socialStats.courage.raw").getInt("low"));assertTrue(facts.getJSONObject("socialStats.courage.raw").isNull("high"));assertEquals(6,facts.getJSONObject("socialStats.courage.rank").getInt("low"));}
    @Test public void contradictoryRankAndRawReject()throws Exception{try{adapter().deriveStatFacts(o("{\"socialStats.charm.rank\":{\"low\":3,\"high\":3},\"socialStats.charm.raw\":{\"low\":70,\"high\":70}}"));fail();}catch(IllegalArgumentException expected){}}
    @Test public void conservativeGainCrossesThresholdWithoutBonusClaim()throws Exception{
        JSONObject state=state(10,23),built=adapter().build(state,context(state,"arcade_courage_night"),new JSONObject()),a=action(built,"arcade_courage_night");assertNotNull(a);
        JSONObject delta=a.getJSONObject("uncertain_effects").getJSONObject("socialStats.courage.raw");assertEquals(4,delta.getInt("low"));assertTrue(delta.isNull("high"));
        JSONObject result=PlannerEngine.transition(built.getJSONObject("rules"),built.getJSONObject("engine_snapshot"),a,new JSONObject());
        JSONObject forecast=result.getJSONObject("state");assertEquals(80,forecast.getJSONObject("values").getJSONObject("socialStats.courage.raw").getInt("low"));
        assertEquals(6,adapter().rankIntervals(forecast.getJSONObject("values")).getJSONObject("socialStats.courage.rank").getInt("low"));assertEquals(7,state.getInt("revision"));
    }
    @Test public void unknownCashBlocksPaidButAllowsVerifiedFreeStudy()throws Exception{JSONObject state=state(10,22).put("cash",JSONObject.NULL),context=context(state,"coffee_chagall_night");assertNull(action(adapter().build(state,context,new JSONObject()),"coffee_chagall_night"));assertNotNull(action(adapter().build(state,context(state,"study_room_night"),new JSONObject()),"study_room_night"));}
    @Test public void unknownLocationAndUnknownSlotBlockActions()throws Exception{JSONObject state=state(10,22),context=context(state,"coffee_chagall_night");context.getJSONObject("calendar").remove("statActionStates");assertNull(action(adapter().build(state,context,new JSONObject()),"coffee_chagall_night"));context=context(state,"coffee_chagall_night");context.getJSONObject("calendar").remove("slotStates");assertNull(action(adapter().build(state,context,new JSONObject()),"coffee_chagall_night"));}
    @Test public void menuUnlockUnknownDoesNotInventPreparation()throws Exception{JSONObject state=state(10,22);assertNull(action(adapter().build(state,context(state,"meal_wakatsu_secret"),new JSONObject()),"meal_wakatsu_secret"));}
    @Test public void explicitLocksOverrideObservedFreeButDungeonOnlyDoesNotBlockStudy()throws Exception{assertTrue(adapter().isOrdinarySlotLocked(GameDate.slot(10,13,true)));assertTrue(adapter().isDungeonSlotLocked(GameDate.slot(7,11,true)));assertFalse(adapter().isOrdinarySlotLocked(GameDate.slot(7,11,true)));JSONObject state=state(10,13);assertNull(action(adapter().build(state,context(state,"study_room_night"),new JSONObject()),"study_room_night"));}
    @Test public void noTowerMoneyAndUnknownVisitCountPreserved()throws Exception{JSONObject state=state(10,22),built=adapter().build(state,context(state,"study_room_night"),new JSONObject());assertTrue(built.getJSONObject("engine_snapshot").getJSONObject("visits").isNull("p3r.tower.stage_07"));JSONArray actions=built.getJSONObject("rules").getJSONArray("actions");for(int i=0;i<actions.length();i++){assertFalse(actions.getJSONObject(i).optBoolean("dungeon_visit"));assertFalse(actions.getJSONObject(i).has("effects"));}assertFalse(built.getJSONObject("rules").getBoolean("coverage_complete"));}
    @Test public void staleContextCannotGenerateStatPlan()throws Exception{JSONObject state=state(10,22),context=context(state,"study_room_night");context.getJSONObject("checkpoint").getJSONObject("date").put("ordinal",0);JSONObject built=adapter().build(state,context,new JSONObject());assertEquals("unknown",built.getString("status"));assertEquals(0,built.getJSONObject("rules").getJSONArray("actions").length());}

    @Test public void legacyRawAliasSupportedButConflictsReject()throws Exception{
        JSONObject d=adapter().deriveStatFacts(o("{\"stats.charm\":{\"low\":45,\"high\":69}}"));assertEquals(4,d.getJSONObject("socialStats.charm.rank").getInt("low"));
        try{adapter().deriveStatFacts(o("{\"stats.charm\":{\"low\":45,\"high\":69},\"socialStats.charm.raw\":{\"low\":30,\"high\":44}}"));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void declaredFutureRoutineAssumptionDoesNotInventVenueOrMenu()throws Exception{
        JSONObject state=state(10,22),context=context(state,"study_room_night");
        context.put("progression",o("{\"assumeFutureRoutineAvailability\":true,\"repeatableActivityGuards\":{\"study_room_night\":{\"locationAccessible\":true},\"meal_wakatsu_secret\":{\"locationAccessible\":true}}}"));
        JSONObject built=adapter().build(state,context,new JSONObject());assertEquals(1,action(built,"study_room_night").getJSONArray("slots").length());assertTrue(built.getJSONArray("conditional_activity_candidates").length()>0);assertNull(action(built,"meal_wakatsu_secret"));
        assertNull(action(built,"coffee_chagall_night"));assertFalse(built.getJSONObject("action_metadata").getJSONObject("p3r.stat.study_room_night").getBoolean("future_routine_availability_assumed"));
    }

    @Test public void secretMenuNeedsProvenStatLowerBound()throws Exception{
        JSONObject state=state(10,22);state.getJSONObject("facts").remove("socialStats.charm.rank");state.getJSONObject("facts").put("socialStats.charm.raw",interval(29,null));
        JSONObject context=context(state,"meal_wakatsu_secret");context.getJSONObject("calendar").getJSONObject("statActionStates").getJSONObject(""+state.getInt("game_slot")).getJSONObject("meal_wakatsu_secret").put("menu.wakatsu.secretUnlockedObserved",true);
        JSONObject built=adapter().build(state,context,new JSONObject()),a=action(built,"meal_wakatsu_secret");assertNotNull(a);assertTrue(a.getBoolean("strict_requirements"));
        try{PlannerEngine.transition(built.getJSONObject("rules"),built.getJSONObject("engine_snapshot"),a,new JSONObject());fail("unknown bonus cannot satisfy prerequisite");}catch(PlannerEngine.RuleViolation expected){assertEquals("unknown_requirement",expected.code);}
    }

    @Test public void storyMilestonesNeverAwardRankFromPastDateOrHighestFloor()throws Exception{
        JSONObject state=state(1,20).put("highest_floor",255);state.getJSONObject("facts").put("socialLinks.judgement.rank",interval(6,6));String before=state.toString();
        JSONArray pending=adapter().storyGoalMilestones(state,new JSONArray("[{\"field\":\"socialLinks.judgement.rank\",\"target\":10}]"));assertEquals(4,pending.length());
        assertEquals(247,pending.getJSONObject(0).getJSONObject("tartarus_milestone").getInt("floor"));assertFalse(pending.getJSONObject(0).getBoolean("completed_event_observed"));assertFalse(pending.getJSONObject(0).has("effects"));assertEquals(before,state.toString());
    }
    @Test public void skippedStoryRanksStayOneMilestoneAndAchievedGoalsDisappear()throws Exception{
        JSONObject state=state(10,22);state.getJSONObject("facts").put("socialLinks.fool.rank",interval(7,7));JSONArray goals=new JSONArray("[{\"field\":\"socialLinks.fool.rank\",\"target\":8}]");
        JSONArray pending=adapter().storyGoalMilestones(state,goals);assertEquals(1,pending.length());assertEquals(9,pending.getJSONObject(0).getInt("to_rank"));
        state.getJSONObject("facts").put("socialLinks.fool.rank",interval(9,9));assertEquals(0,adapter().storyGoalMilestones(state,goals).length());
    }

    @Test public void jobUsesMinimumCashOnlyAndUnknownBalanceIsNotExactZero()throws Exception{
        JSONObject state=state(10,19).put("cash",JSONObject.NULL),context=context(state,"job_chagall_minimum");context.getJSONObject("calendar").getJSONObject("statActionStates").getJSONObject(""+state.getInt("game_slot")).getJSONObject("job_chagall_minimum").put("job.chagall.currentlyAvailable",true);
        JSONObject built=adapter().build(state,context,new JSONObject()),a=action(built,"job_chagall_minimum");assertNotNull(a);JSONObject gain=a.getJSONObject("uncertain_effects");assertEquals(1,gain.length());assertEquals(2500,gain.getJSONObject("cash").getInt("low"));assertTrue(gain.getJSONObject("cash").isNull("high"));
        JSONObject before=built.getJSONObject("engine_snapshot").getJSONObject("values").getJSONObject("cash");assertEquals(0,before.getInt("low"));assertTrue(before.isNull("high"));assertTrue(state.isNull("cash"));
        JSONObject after=PlannerEngine.transition(built.getJSONObject("rules"),built.getJSONObject("engine_snapshot"),a,new JSONObject()).getJSONObject("state").getJSONObject("values").getJSONObject("cash");assertEquals(2500,after.getInt("low"));assertTrue(after.isNull("high"));
    }
    @Test public void unknownJobAvailabilityBlocksAndFutureTrainingRemainsGuarded()throws Exception{
        JSONObject state=state(10,19).put("cash",0),context=context(state,"job_chagall_minimum");assertNull(action(adapter().build(state,context,new JSONObject()),"job_chagall_minimum"));
        context.put("progression",o("{\"assumeFutureRoutineAvailability\":true,\"repeatableActivityGuards\":{\"coffee_chagall_night\":{\"locationAccessible\":true}}}"));
        JSONObject built=adapter().build(state,context,new JSONObject());assertNull(action(built,"coffee_chagall_night"));assertTrue(built.getJSONArray("conditional_activity_candidates").length()>0);assertTrue(built.getJSONObject("action_metadata").has("p3r.stat.coffee_chagall_night"));
    }

    private boolean dungeonSlot(JSONObject built,int slot)throws Exception{JSONArray actions=built.getJSONArray("actions");for(int i=0;i<actions.length();i++){JSONArray slots=actions.getJSONObject(i).getJSONArray("slots");for(int j=0;j<slots.length();j++)if(slots.getInt(j)==slot)return true;}return false;}
    @Test public void dungeonWitnessRequiresExplicitCapUsageAndHasNoOutcomeEffects()throws Exception{
        JSONObject state=state(10,22),context=context(state,"study_room_night"),constraints=o("{\"visit_caps\":{\"p3r.tower.stage_07\":2}}");context.put("tartarus",o("{\"normalStoryProgressionAssumed\":true}"));
        assertFalse(dungeonSlot(adapter().dungeonCandidates(state,context,constraints),GameDate.slot(10,30,true)));
        state.getJSONObject("visits_by_stage").put("p3r.tower.stage_07",1);JSONObject built=adapter().dungeonCandidates(state,context,constraints);assertTrue(dungeonSlot(built,GameDate.slot(10,30,true)));
        JSONObject a=built.getJSONArray("actions").getJSONObject(0);assertTrue(a.getBoolean("dungeon_visit"));assertFalse(a.has("effects"));assertFalse(a.has("target_floor"));assertEquals(1,state.getJSONObject("visits_by_stage").getInt("p3r.tower.stage_07"));
        state.getJSONObject("visits_by_stage").put("p3r.tower.stage_07",2);assertFalse(dungeonSlot(adapter().dungeonCandidates(state,context,constraints),GameDate.slot(10,30,true)));
    }
    @Test public void routineDungeonNeedsDeclarationAndRejectsKnownClosures()throws Exception{
        JSONObject state=state(10,6),context=context(state,"study_room_night"),caps=o("{\"visit_caps\":{\"p3r.tower.stage_07\":2,\"p3r.tower.stage_09\":2}}");state.getJSONObject("visits_by_stage").put("p3r.tower.stage_07",0).put("p3r.tower.stage_09",0);context.put("tartarus",o("{\"normalStoryProgressionAssumed\":true}"));
        assertFalse(dungeonSlot(adapter().dungeonCandidates(state,context,caps),GameDate.slot(10,7,true)));context.getJSONObject("tartarus").put("assumeRoutineFutureEntry",true);JSONObject built=adapter().dungeonCandidates(state,context,caps);assertTrue(dungeonSlot(built,GameDate.slot(10,7,true)));assertFalse(dungeonSlot(built,GameDate.slot(10,13,true)));assertFalse(dungeonSlot(built,GameDate.slot(12,5,true)));assertFalse(dungeonSlot(built,GameDate.slot(1,31,true)));
    }
    @Test public void currentDungeonObservationIsScopedAndGapDoesNotOpenBucket()throws Exception{
        JSONObject state=state(7,10).put("active_stage","p3r.tower.stage_04").put("highest_floor",70),context=context(state,"study_room_night");state.getJSONObject("visits_by_stage").put("p3r.tower.stage_04",0);context.put("tartarus",new JSONObject().put("entryAvailableObserved",true).put("entryObservationDateOrdinal",state.getInt("game_slot")/2).put("entryObservationSlot","evening").put("optionalExplorationUnlockedObserved",true));JSONObject built=adapter().dungeonCandidates(state,context,o("{\"visit_caps\":{\"p3r.tower.stage_04\":2}}"));assertEquals(0,built.getJSONArray("actions").length());assertEquals("resolve_operational_stage_gap",built.getJSONArray("observation_checkpoints").getJSONObject(0).getString("kind"));
    }

    @Test public void priorJanuaryWitnessesRemainAdditiveUnderNewCaps()throws Exception{
        JSONObject state=state(1,19),context=context(state,"study_room_night"),caps=o("{\"visit_caps\":{\"p3r.tower.stage_10\":2}}");state.getJSONObject("visits_by_stage").put("p3r.tower.stage_10",0);context.put("tartarus",o("{\"normalStoryProgressionAssumed\":true}"));JSONObject built=adapter().dungeonCandidates(state,context,caps);assertTrue(dungeonSlot(built,GameDate.slot(1,20,true)));assertTrue(dungeonSlot(built,GameDate.slot(1,28,true)));assertFalse(dungeonSlot(built,GameDate.slot(1,27,true)));
    }

    @Test public void darkHourStoryEventDoesNotInventOptionalSlot()throws Exception{
        JSONObject state=state(6,10);state.getJSONObject("facts").put("socialLinks.death.rank",interval(0,0));JSONArray checkpoints=adapter().storyGoalMilestones(state,new JSONArray("[{\"field\":\"socialLinks.death.rank\",\"target\":1}]"));assertEquals("dark_hour",checkpoints.getJSONObject(0).getString("event_phase"));assertEquals("06-12",checkpoints.getJSONObject(0).getString("expected_date"));assertTrue(checkpoints.getJSONObject(0).isNull("expected_slot"));
    }

    @Test public void currentDepartureRestrictionDoesNotPermanentlyCloseFutureNights()throws Exception{
        JSONObject state=state(10,22),context=context(state,"study_room_night"),caps=o("{\"visit_caps\":{\"p3r.tower.stage_07\":2}}");state.getJSONObject("visits_by_stage").put("p3r.tower.stage_07",0);
        context.put("tartarus",new JSONObject().put("normalStoryProgressionAssumed",true).put("departureBlockedObserved",true).put("navigatorUnavailableObserved",true).put("entryAvailableObserved",true).put("entryObservationDateOrdinal",state.getInt("game_slot")/2).put("entryObservationSlot","evening").put("optionalExplorationUnlockedObserved",true));
        JSONObject built=adapter().dungeonCandidates(state,context,caps);assertFalse(dungeonSlot(built,state.getInt("game_slot")));assertTrue(dungeonSlot(built,GameDate.slot(10,30,true)));
    }

    private boolean statSlot(JSONObject built,String source,int slot)throws Exception{JSONObject a=action(built,source);if(a==null)return false;JSONArray dates=a.getJSONArray("slots");for(int i=0;i<dates.length();i++)if(dates.getInt(i)==slot)return true;return false;}
    private JSONObject candidate(JSONObject built,String source,int slot)throws Exception{JSONArray list=built.getJSONArray("conditional_activity_candidates");for(int i=0;i<list.length();i++){JSONObject c=list.getJSONObject(i);if(c.getString("source_action_id").equals(source)&&c.getInt("slot")==slot)return c;}return null;}
    private JSONObject routine(JSONObject state,String source)throws Exception{JSONObject c=context(state,source);c.put("progression",new JSONObject().put("assumeFutureRoutineAvailability",true).put("repeatableActivityGuards",new JSONObject().put(source,o("{\"locationAccessible\":true}"))));return c;}
    private void verifiedFuture(JSONObject context,String source,int slot)throws Exception{
        context.getJSONObject("calendar").getJSONObject("slotStates").put(""+slot,o("{\"remainingCapacity\":1,\"knownStoryLock\":false,\"slotState\":\"free\"}"));
        context.getJSONObject("calendar").getJSONObject("statActionStates").put(""+slot,new JSONObject().put(source,o("{\"calendar.exactDateActionAvailable\":true,\"locationAccessible\":true}")));
    }
    @Test public void christmasKnownEventBlocksEvenExplicitOrdinaryActivityClaims()throws Exception{
        JSONObject state=state(12,23),c=routine(state,"meal_wakatsu_regular");c.put("storyFlags",o("{\"christmasAllInvitationsDeclined\":false}"));for(boolean evening:new boolean[]{false,true})verifiedFuture(c,"meal_wakatsu_regular",GameDate.slot(12,24,evening));
        JSONObject built=adapter().build(state,c,new JSONObject());for(boolean evening:new boolean[]{false,true}){int slot=GameDate.slot(12,24,evening);assertFalse(statSlot(built,"meal_wakatsu_regular",slot));assertNull(candidate(built,"meal_wakatsu_regular",slot));}
    }
    @Test public void christmasUnknownRemainsUncreditedUntilSpecificGuardAndDateVerified()throws Exception{
        JSONObject state=state(12,23),c=routine(state,"meal_wakatsu_regular");int slot=GameDate.slot(12,24,true);verifiedFuture(c,"meal_wakatsu_regular",slot);
        JSONObject unknown=adapter().build(state,c,new JSONObject());assertFalse(statSlot(unknown,"meal_wakatsu_regular",slot));assertFalse(candidate(unknown,"meal_wakatsu_regular",slot).getBoolean("forecast_credit"));
        c.put("storyFlags",o("{\"christmasAllInvitationsDeclined\":true}"));assertTrue(statSlot(adapter().build(state,c,new JSONObject()),"meal_wakatsu_regular",slot));
    }
    @Test public void jan21NeedsSpecificFlagAndKnownDaytimeStoryState()throws Exception{
        JSONObject state=state(1,20),c=routine(state,"meal_wakatsu_regular");int slot=GameDate.slot(1,21,false);verifiedFuture(c,"meal_wakatsu_regular",slot);
        JSONObject unknown=adapter().build(state,c,new JSONObject());assertFalse(statSlot(unknown,"meal_wakatsu_regular",slot));assertNotNull(candidate(unknown,"meal_wakatsu_regular",slot));
        c.put("storyFlags",o("{\"junpeiWhiteFlowersGiven\":false}"));assertTrue(statSlot(adapter().build(state,c,new JSONObject()),"meal_wakatsu_regular",slot));
        c.getJSONObject("calendar").getJSONObject("slotStates").getJSONObject(""+slot).put("knownStoryLock",JSONObject.NULL);assertFalse(statSlot(adapter().build(state,c,new JSONObject()),"meal_wakatsu_regular",slot));
    }
    @Test public void retainedForcedIntroductionBlocksApr22DaytimeOnly()throws Exception{assertTrue(adapter().isOrdinarySlotLocked(GameDate.slot(4,22,false)));assertFalse(adapter().isOrdinarySlotLocked(GameDate.slot(4,22,true)));assertFalse(adapter().isOrdinarySlotLocked(GameDate.slot(7,11,true)));}
    @Test public void futureRoutineJobCannotCreditCashWithoutExactAvailability()throws Exception{
        JSONObject state=state(10,18).put("cash",0),c=routine(state,"job_chagall_minimum");c.getJSONObject("progression").getJSONObject("repeatableActivityGuards").getJSONObject("job_chagall_minimum").put("job.chagall.currentlyAvailable",true);
        JSONObject built=adapter().build(state,c,new JSONObject());assertNull(action(built,"job_chagall_minimum"));JSONObject alternative=candidate(built,"job_chagall_minimum",GameDate.slot(10,19,true));assertNotNull(alternative);assertFalse(alternative.getBoolean("forecast_credit"));assertEquals(2500,alternative.getInt("conditional_minimum_income"));assertFalse(alternative.has("uncertain_effects"));assertEquals(0,built.getJSONObject("engine_snapshot").getJSONObject("values").getJSONObject("cash").getInt("low"));
    }
}
