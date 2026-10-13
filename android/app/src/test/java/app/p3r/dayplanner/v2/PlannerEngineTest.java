package app.p3r.dayplanner.v2;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic numeric fixtures only. None of these tests encodes a real P3R rule. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class PlannerEngineTest {
    private static JSONObject o(String raw) throws Exception {return new JSONObject(raw);}
    private static JSONArray a(String raw) throws Exception {return new JSONArray(raw);}
    private static JSONObject rules(int horizon,JSONObject...actions) throws Exception {
        JSONObject rules=o("{\"version\":\"synthetic-v1\",\"horizon\":2,\"fields\":[\"x\",\"cash\",\"key\",\"rank\",\"ready\"],\"actions\":[]}");
        rules.put("horizon",horizon);JSONArray list=new JSONArray();for(JSONObject action:actions)list.put(action);rules.put("actions",list);return rules;
    }
    private static JSONObject state() throws Exception {
        return o("{\"run_id\":\"synthetic-run\",\"rules_version\":\"synthetic-v1\",\"revision\":7,\"slot\":0,\"values\":{\"x\":{\"low\":0,\"high\":0},\"cash\":{\"low\":0,\"high\":0},\"key\":{\"low\":0,\"high\":0}},\"visits\":{}}");
    }
    private static void exact(JSONObject state,String name,long value) throws Exception {state.getJSONObject("values").put(name,new JSONObject().put("low",value).put("high",value));}
    private static void range(JSONObject state,String name,Object low,Object high) throws Exception {state.getJSONObject("values").put(name,new JSONObject().put("low",low==null?JSONObject.NULL:low).put("high",high==null?JSONObject.NULL:high));}
    private static JSONObject gain() throws Exception {return o("{\"id\":\"gain\",\"slots\":[0,1],\"effects\":{\"x\":1}}");}
    private static JSONObject visit() throws Exception {return o("{\"id\":\"visit\",\"slots\":[0,1],\"dungeon_visit\":true,\"target_floor\":2}");}
    private static JSONObject withStage(JSONObject rules) throws Exception {rules.put("stages",a("[{\"id\":\"A\",\"start\":0,\"end\":2,\"floor_ceiling\":5}]"));return rules;}
    private static JSONArray goal(String field,int target) throws Exception {return new JSONArray().put(new JSONObject().put("field",field).put("target",target));}
    private static JSONObject plan(JSONObject rules,JSONObject state,JSONArray goals,JSONObject constraints) {return PlannerEngine.plan(rules,state,goals,constraints,128,10000);}
    private static JSONObject plan(JSONObject rules,JSONObject state,int target) throws Exception {return plan(rules,state,goal("x",target),o("{}"));}
    private static long low(JSONObject state,String field) throws Exception {return state.getJSONObject("values").getJSONObject(field).getLong("low");}
    private static long high(JSONObject state,String field) throws Exception {return state.getJSONObject("values").getJSONObject(field).getLong("high");}
    private interface Attempt {void run() throws Exception;}
    private static void violation(String code,Attempt attempt) throws Exception {
        try{attempt.run();fail("Expected violation "+code);}catch(PlannerEngine.RuleViolation error){assertEquals(code,error.code);}
    }
    private static void invalid(Attempt attempt) throws Exception {
        try{attempt.run();fail("Expected invalid input");}catch(IllegalArgumentException expected){ }
    }
    private static void sameObject(JSONObject expected,JSONObject actual) throws Exception {
        assertEquals(expected.length(),actual.length());
        java.util.Iterator<String> keys=expected.keys();
        while(keys.hasNext()){
            String key=keys.next();assertTrue("Missing JSON key "+key,actual.has(key));
            Object left=expected.get(key),right=actual.get(key);
            if(left instanceof JSONObject){assertTrue(right instanceof JSONObject);sameObject((JSONObject)left,(JSONObject)right);}
            else if(left instanceof Number&&right instanceof Number)assertEquals(((Number)left).longValue(),((Number)right).longValue());
            else assertEquals(left,right);
        }
    }

    @Test public void transitionIsPureAndPreservesObservedRevision() throws Exception {
        JSONObject before=state(),action=gain(),rules=rules(2,action);String saved=before.toString(),savedRules=rules.toString();
        JSONObject result=PlannerEngine.transition(rules,before,action,o("{}"));
        assertEquals(saved,before.toString());assertEquals(savedRules,rules.toString());
        assertEquals(1,low(result.getJSONObject("state"),"x"));assertEquals(1,result.getJSONObject("state").getInt("slot"));
        assertEquals(7,result.getJSONObject("state").getLong("revision"));assertEquals(0,result.getJSONArray("assumptions").length());
    }

    @Test public void searchDoesNotMutateObservedInput() throws Exception {
        JSONObject before=state();String saved=before.toString();JSONObject output=plan(rules(2,gain()),before,2);
        assertEquals("feasible_within_model",output.getString("status"));assertEquals(saved,before.toString());
        assertEquals(7,output.getLong("state_revision"));assertEquals(7,output.getJSONObject("forecast").getLong("revision"));
    }

    @Test public void deterministicRegardlessOfActionObjectKeyOrder() throws Exception {
        JSONObject one=o("{\"id\":\"a\",\"slots\":[0,1],\"effects\":{\"x\":1}}");
        JSONObject two=o("{\"effects\":{\"x\":1},\"slots\":[0,1],\"id\":\"b\"}");
        JSONObject first=plan(rules(2,two,one),state(),2),second=plan(rules(2,one,two),state(),2);
        assertEquals(first.getString("plan_id"),second.getString("plan_id"));
        assertEquals(first.getJSONArray("steps").toString(),second.getJSONArray("steps").toString());
        assertEquals("a",first.getJSONArray("steps").getJSONObject(0).getString("action_id"));
    }

    @Test public void replayMatchesSearchAndDoesNotAliasInputs() throws Exception {
        JSONObject rules=rules(2,gain()),before=state(),output=plan(rules,before,2);
        JSONObject replay=PlannerEngine.validatePlan(rules,before,output.getJSONArray("steps"),o("{}"));
        assertEquals(output.getJSONObject("forecast").toString(),replay.toString());
        replay.getJSONObject("values").getJSONObject("x").put("low",999);assertEquals(0,low(before,"x"));
        JSONObject empty=PlannerEngine.validatePlan(rules,before,new JSONArray(),o("{}"));
        empty.getJSONObject("values").getJSONObject("x").put("low",999);assertEquals(0,low(before,"x"));
    }

    @Test public void knownImpossiblePrerequisiteIsRejected() throws Exception {
        JSONObject action=o("{\"id\":\"needs\",\"slots\":[0],\"requirements\":{\"key\":1}}");
        violation("requirement",()->PlannerEngine.transition(rules(1,action),state(),action,o("{}")));
    }

    @Test public void unknownPrerequisiteOnlyProducesConditionalCandidate() throws Exception {
        JSONObject action=o("{\"id\":\"needs\",\"slots\":[0],\"requirements\":{\"key\":1},\"effects\":{\"x\":1}}");
        JSONObject before=state();before.getJSONObject("values").remove("key");
        JSONObject output=plan(rules(1,action),before,1);assertEquals("conditional",output.getString("status"));
        assertTrue(output.getJSONArray("explanations").toString().contains("key"));
    }

    @Test public void costCannotBeFundedBySameActionReward() throws Exception {
        JSONObject action=o("{\"id\":\"job\",\"slots\":[0],\"costs\":{\"cash\":5},\"effects\":{\"cash\":10}}");
        violation("requirement",()->PlannerEngine.transition(rules(1,action),state(),action,o("{}")));
    }

    @Test public void costsThenArithmeticThenUncertainRewards() throws Exception {
        JSONObject action=o("{\"id\":\"job\",\"slots\":[0],\"costs\":{\"cash\":3},\"effects\":{\"cash\":2},\"uncertain_effects\":{\"cash\":{\"low\":0,\"high\":4}}}");
        JSONObject before=state();exact(before,"cash",8);
        JSONObject after=PlannerEngine.transition(rules(1,action),before,action,o("{}")).getJSONObject("state");
        assertEquals(7,low(after,"cash"));assertEquals(11,high(after,"cash"));
    }

    @Test public void uncertainMoneyDoesNotGuaranteePurchase() throws Exception {
        JSONObject earn=o("{\"id\":\"earn\",\"slots\":[0],\"uncertain_effects\":{\"cash\":{\"low\":0,\"high\":10}}}");
        JSONObject buy=o("{\"id\":\"buy\",\"slots\":[1],\"costs\":{\"cash\":5},\"effects\":{\"x\":1}}");
        JSONObject output=plan(rules(2,earn,buy),state(),1);assertEquals("conditional",output.getString("status"));
        assertEquals(0,low(output.getJSONObject("forecast"),"cash"));assertEquals(5,high(output.getJSONObject("forecast"),"cash"));
    }

    @Test public void guaranteedRewardLowerBoundMayFundPurchase() throws Exception {
        JSONObject earn=o("{\"id\":\"earn\",\"slots\":[0],\"uncertain_effects\":{\"cash\":{\"low\":5,\"high\":10}}}");
        JSONObject buy=o("{\"id\":\"buy\",\"slots\":[1],\"costs\":{\"cash\":5},\"effects\":{\"x\":1}}");
        assertEquals("feasible_within_model",plan(rules(2,earn,buy),state(),1).getString("status"));
    }

    @Test public void unknownGoalNotZeroAndNotImpossible() throws Exception {
        JSONObject before=state();before.getJSONObject("values").remove("x");
        JSONObject output=plan(rules(1),before,10);assertEquals("conditional",output.getString("status"));
        assertFalse(output.getJSONObject("forecast").getJSONObject("values").has("x"));
    }

    @Test public void maximumIsInclusiveAndBlocksExtraRank() throws Exception {
        JSONObject action=o("{\"id\":\"rank\",\"slots\":[0,1],\"maximums\":{\"rank\":9},\"effects\":{\"rank\":1}}");
        JSONObject before=state();exact(before,"rank",9);
        JSONObject first=PlannerEngine.transition(rules(2,action),before,action,o("{}")).getJSONObject("state");
        assertEquals(10,low(first,"rank"));violation("requirement",()->PlannerEngine.transition(rules(2,action),first,action,o("{}")));
        assertEquals("proven_infeasible",plan(rules(2,action),before,goal("rank",11),o("{}")).getString("status"));
    }

    @Test public void maximumOverlappingRangeIsConditionalAndClamped() throws Exception {
        JSONObject action=o("{\"id\":\"rank\",\"slots\":[0],\"maximums\":{\"rank\":9},\"effects\":{\"rank\":1}}");
        JSONObject before=state();range(before,"rank",8,12);
        JSONObject result=PlannerEngine.transition(rules(1,action),before,action,o("{}"));
        assertEquals(9,low(result.getJSONObject("state"),"rank"));assertEquals(10,high(result.getJSONObject("state"),"rank"));
        assertTrue(result.getJSONArray("assumptions").length()>0);
    }

    @Test public void maximumUnknownRemainsExplicitAssumption() throws Exception {
        JSONObject action=o("{\"id\":\"bounded\",\"slots\":[0],\"maximums\":{\"rank\":9},\"effects\":{\"x\":1}}");
        JSONObject result=PlannerEngine.transition(rules(1,action),state(),action,o("{}"));
        assertTrue(result.getJSONObject("state").getJSONObject("values").getJSONObject("rank").isNull("low"));
        assertEquals(9,high(result.getJSONObject("state"),"rank"));
        assertEquals("conditional",plan(rules(1,action),state(),1).getString("status"));
    }

    @Test public void contradictoryMinimumMaximumIsRejected() throws Exception {
        JSONObject action=o("{\"id\":\"bad\",\"slots\":[0],\"requirements\":{\"rank\":10},\"maximums\":{\"rank\":9}}");
        violation("requirement",()->PlannerEngine.transition(rules(1,action),state(),action,o("{}")));
    }

    @Test public void assignmentOverridesPriorUncertainty() throws Exception {
        JSONObject action=o("{\"id\":\"set\",\"slots\":[0],\"assignments\":{\"ready\":{\"low\":1,\"high\":1}}}");
        JSONObject before=state();range(before,"ready",null,null);
        JSONObject after=PlannerEngine.transition(rules(1,action),before,action,o("{}")).getJSONObject("state");
        assertEquals(1,low(after,"ready"));assertEquals(1,high(after,"ready"));
        assertTrue(before.getJSONObject("values").getJSONObject("ready").isNull("low"));
    }

    @Test public void nullAssignmentExplicitlyForgetsValue() throws Exception {
        JSONObject action=o("{\"id\":\"reset\",\"slots\":[0],\"assignments\":{\"ready\":null}}");
        JSONObject before=state();exact(before,"ready",1);
        JSONObject after=PlannerEngine.transition(rules(1,action),before,action,o("{}")).getJSONObject("state");
        assertTrue(after.getJSONObject("values").getJSONObject("ready").isNull("low"));
        assertTrue(after.getJSONObject("values").getJSONObject("ready").isNull("high"));
    }

    @Test public void forgetEffectsPreventAutomaticRepeatedReadiness() throws Exception {
        JSONObject action=o("{\"id\":\"rank\",\"slots\":[0,1],\"requirements\":{\"ready\":1},\"effects\":{\"rank\":1},\"forget_fields\":[\"ready\"]}");
        JSONObject before=state();exact(before,"ready",1);exact(before,"rank",0);
        JSONObject output=plan(rules(2,action),before,goal("rank",2),o("{}"));
        assertEquals("conditional",output.getString("status"));
        assertTrue(output.getJSONArray("explanations").toString().contains("ready"));
        assertTrue(output.getJSONObject("forecast").getJSONObject("values").getJSONObject("ready").isNull("low"));
    }

    @Test public void effectOrderingIsAdditiveThenAssignmentThenForget() throws Exception {
        JSONObject action=o("{\"id\":\"order\",\"slots\":[0],\"effects\":{\"key\":1},\"assignments\":{\"key\":{\"low\":7,\"high\":7},\"ready\":{\"low\":1,\"high\":1}},\"forget_fields\":[\"ready\"]}");
        JSONObject after=PlannerEngine.transition(rules(1,action),state(),action,o("{}")).getJSONObject("state");
        assertEquals(7,low(after,"key"));assertTrue(after.getJSONObject("values").getJSONObject("ready").isNull("low"));
    }

    @Test public void assignmentAndForgetFieldsMustBeDeclared() throws Exception {
        for(String body:new String[]{"\"assignments\":{\"missing\":null}","\"forget_fields\":[\"missing\"]","\"maximums\":{\"missing\":9}"}) {
            JSONObject action=o("{\"id\":\"bad\",\"slots\":[0],"+body+"}");
            violation("unsupported_field",()->PlannerEngine.transition(rules(1,action),state(),action,o("{}")));
        }
    }

    @Test public void unknownFieldAndUnsupportedRuleNotInfeasibility() throws Exception {
        assertEquals("unsupported",plan(rules(1),state(),goal("absent",1),o("{}")).getString("status"));
        JSONObject action=gain().put("verification","unverified");
        assertEquals("unsupported",plan(rules(1,action),state(),1).getString("status"));
    }

    @Test public void corroboratedEvidencePreservedAndPartialPlanConditional() throws Exception {
        JSONObject action=gain().put("verification","corroborated").put("source","synthetic-test-evidence");
        JSONObject rules=rules(1,action).put("synthetic",false).put("coverage_complete",false);
        assertEquals("conditional",plan(rules,state(),1).getString("status"));
        assertEquals("corroborated",action.getString("verification"));
    }

    @Test public void syntheticActionsCannotBeRealRuleWitness() throws Exception {
        assertEquals("unsupported",plan(rules(1,gain()).put("synthetic",false),state(),1).getString("status"));
    }

    @Test public void missingEvidenceRejected() throws Exception {
        JSONObject action=gain().put("verification","verified").put("source","");
        assertEquals("unsupported",plan(rules(1,action),state(),1).getString("status"));
    }

    @Test public void incompleteFailureUnknownAndSuccessConditional() throws Exception {
        JSONObject rules=rules(1,gain()).put("coverage_complete",false);
        assertEquals("conditional",plan(rules,state(),1).getString("status"));
        assertEquals("unknown",plan(rules,state(),2).getString("status"));
    }

    @Test public void exhaustiveFailureWithinModelCanBeProven() throws Exception {
        JSONObject result=PlannerEngine.plan(rules(1,gain()),state(),goal("x",2),o("{}"),1,100);
        assertEquals("proven_infeasible",result.getString("status"));
    }

    @Test public void beamPruningNeverProvesFailure() throws Exception {
        JSONObject lure=o("{\"id\":\"lure\",\"slots\":[0],\"effects\":{\"x\":1}}");
        JSONObject key=o("{\"id\":\"key\",\"slots\":[0],\"effects\":{\"key\":1}}");
        JSONObject finish=o("{\"id\":\"finish\",\"slots\":[1],\"requirements\":{\"key\":1},\"effects\":{\"x\":3}}");
        JSONObject rules=rules(2,lure,key,finish);
        assertEquals("unknown",PlannerEngine.plan(rules,state(),goal("x",3),o("{}"),1,100).getString("status"));
        assertEquals("feasible_within_model",plan(rules,state(),3).getString("status"));
    }

    @Test public void budgetExhaustionHasDistinctHonestStatus() throws Exception {
        for(int budget:new int[]{0,1}) {
            JSONObject output=PlannerEngine.plan(rules(2,gain()),state(),goal("x",2),o("{}"),128,budget);
            assertEquals("search_limit",output.getString("status"));assertTrue(output.getInt("expanded")<=budget);
        }
    }

    @Test public void priorityChoosesImportantGoalInPartialPlan() throws Exception {
        JSONObject high=o("{\"id\":\"major\",\"slots\":[0],\"effects\":{\"key\":1}}");
        JSONObject low=o("{\"id\":\"minor\",\"slots\":[0],\"effects\":{\"x\":100}}");
        JSONArray goals=a("[{\"field\":\"key\",\"target\":1,\"priority\":9},{\"field\":\"x\",\"target\":100,\"priority\":1}]");
        JSONObject output=plan(rules(1,high,low),state(),goals,o("{}"));
        assertEquals("major",output.getJSONArray("steps").getJSONObject(0).getString("action_id"));
        assertEquals("proven_infeasible",output.getString("status"));
    }

    @Test public void fixedFutureActionMustStillBeHonored() throws Exception {
        JSONObject action=o("{\"id\":\"required\",\"slots\":[1],\"effects\":{\"x\":-1}}");
        JSONObject rules=rules(2,action).put("fixed_actions",o("{\"1\":\"required\"}"));JSONObject before=state();exact(before,"x",1);
        JSONObject output=plan(rules,before,1);assertEquals("proven_infeasible",output.getString("status"));
        assertEquals("required",output.getJSONArray("steps").getJSONObject(1).getString("action_id"));
    }

    @Test public void fixedPinConflictAndUnknownPinDiffer() throws Exception {
        JSONObject action=gain(),rules=rules(1,action).put("fixed_actions",o("{\"0\":\"gain\"}"));
        assertEquals("proven_infeasible",plan(rules,state(),goal("x",1),o("{\"pinned_actions\":{\"0\":\"__wait__\"}}")).getString("status"));
        assertEquals("unsupported",plan(rules(1),state(),goal("x",1),o("{\"pinned_actions\":{\"0\":\"missing\"}}")).getString("status"));
    }

    @Test public void dungeonHasNoImplicitMoneyOrFloorEffects() throws Exception {
        JSONObject before=state();exact(before,"cash",42);before.getJSONObject("visits").put("A",0);
        JSONObject action=visit();JSONObject output=PlannerEngine.transition(withStage(rules(2,action)),before,action,o("{\"visit_caps\":{\"A\":1}}"));
        assertEquals(42,low(output.getJSONObject("state"),"cash"));
        sameObject(before.getJSONObject("values"),output.getJSONObject("state").getJSONObject("values"));
        assertEquals(1,output.getJSONObject("state").getJSONObject("visits").getLong("A"));
    }

    @Test public void oldFloorChargesCurrentUnlockStage() throws Exception {
        JSONObject action=visit().put("slots",a("[1,2]"));
        JSONObject rules=rules(3,action).put("stages",a("[{\"id\":\"A\",\"start\":0,\"end\":1,\"floor_ceiling\":5},{\"id\":\"B\",\"start\":1,\"end\":3,\"floor_ceiling\":10}]"));
        JSONObject before=state().put("slot",1).put("visits",o("{\"A\":1,\"B\":0}"));JSONObject caps=o("{\"visit_caps\":{\"A\":1,\"B\":1}}");
        JSONObject after=PlannerEngine.transition(rules,before,action,caps).getJSONObject("state");
        assertEquals(1,after.getJSONObject("visits").getLong("A"));assertEquals(1,after.getJSONObject("visits").getLong("B"));
        violation("visit_cap",()->PlannerEngine.transition(rules,after,action,caps));
    }

    @Test public void missingAndNullVisitTotalsNeverDefaultToZero() throws Exception {
        JSONObject action=visit().put("effects",o("{\"x\":1}")),rules=withStage(rules(1,action));
        for(JSONObject visits:new JSONObject[]{o("{}"),o("{\"A\":null}")}) {
            JSONObject before=state().put("visits",visits),caps=o("{\"visit_caps\":{\"A\":1}}");
            violation("unknown_visit_count",()->PlannerEngine.transition(rules,before,action,caps));
            assertEquals("unknown",plan(rules,before,goal("x",1),caps).getString("status"));
            assertTrue(PlannerEngine.transition(rules,before,action,o("{}")).getJSONObject("state").getJSONObject("visits").isNull("A"));
        }
    }

    @Test public void observedOvercapHistoryRetainedNoFurtherVisit() throws Exception {
        JSONObject action=visit(),rules=withStage(rules(1,action));JSONObject before=state().put("visits",o("{\"A\":2}"));exact(before,"x",1);
        JSONObject caps=o("{\"visit_caps\":{\"A\":1}}");
        violation("visit_cap",()->PlannerEngine.transition(rules,before,action,caps));
        JSONObject output=plan(rules,before,goal("x",1),caps);assertEquals("feasible_within_model",output.getString("status"));
        assertEquals(2,output.getJSONObject("forecast").getJSONObject("visits").getLong("A"));
    }

    @Test public void zeroCapAndLockedFloorAreHard() throws Exception {
        JSONObject action=visit(),rules=withStage(rules(1,action)),before=state().put("visits",o("{\"A\":0}"));
        violation("visit_cap",()->PlannerEngine.transition(rules,before,action,o("{\"visit_caps\":{\"A\":0}}")));
        action.put("target_floor",6);JSONObject lockedRules=withStage(rules(1,action));
        violation("floor_locked",()->PlannerEngine.transition(lockedRules,before,action,o("{}")));
    }

    @Test public void missingAndOverlappingStagesAreUnsupported() throws Exception {
        JSONObject action=visit().put("effects",o("{\"x\":1}"));
        assertEquals("unsupported",plan(rules(1,action),state(),1).getString("status"));
        JSONObject rules=rules(1,action).put("stages",a("[{\"id\":\"A\",\"start\":0,\"end\":2,\"floor_ceiling\":5},{\"id\":\"B\",\"start\":0,\"end\":2,\"floor_ceiling\":5}]"));
        assertEquals("unsupported",plan(rules,state(),1).getString("status"));
    }

    @Test public void duplicateInstanceAndWrongSlotRejectedByReplay() throws Exception {
        violation("duplicate_instance",()->PlannerEngine.validatePlan(rules(2,gain()),state(),a("[{\"instance_id\":\"same\",\"action_id\":\"gain\",\"slot\":0},{\"instance_id\":\"same\",\"action_id\":\"gain\",\"slot\":1}]"),o("{}")));
        violation("wrong_slot",()->PlannerEngine.validatePlan(rules(2,gain()),state(),a("[{\"instance_id\":\"one\",\"action_id\":\"gain\",\"slot\":1}]"),o("{}")));
    }

    @Test public void forgedActionAndWaitEffectsRejected() throws Exception {
        JSONObject action=gain(),forged=o(action.toString()).put("effects",o("{\"x\":999}"));
        violation("unsupported_action",()->PlannerEngine.transition(rules(2,action),state(),forged,o("{}")));
        JSONObject wait=o("{\"id\":\"__wait__\",\"slots\":[0],\"effects\":{\"x\":999}}");
        violation("unsupported_action",()->PlannerEngine.transition(rules(2),state(),wait,o("{}")));
    }

    @Test public void staleRulesVersionNotGameInfeasibility() throws Exception {
        JSONObject before=state().put("rules_version","older");
        violation("rules_version_mismatch",()->PlannerEngine.validatePlan(rules(1),before,new JSONArray(),o("{}")));
        assertEquals("unsupported",plan(rules(1),before,1).getString("status"));
    }

    @Test public void generatedIdentityBindsRunRevisionAndGoal() throws Exception {
        JSONObject rules=rules(1,gain()),base=plan(rules,state(),1);
        JSONObject otherRevision=plan(rules,state().put("revision",8),1);
        JSONObject otherRun=plan(rules,state().put("run_id","other"),1);
        JSONObject otherGoal=plan(rules,state(),2);
        for(JSONObject other:new JSONObject[]{otherRevision,otherRun,otherGoal}) {
            assertNotEquals(base.getString("plan_id"),other.getString("plan_id"));
            assertNotEquals(base.getJSONArray("steps").getJSONObject(0).getString("instance_id"),other.getJSONArray("steps").getJSONObject(0).getString("instance_id"));
        }
    }

    @Test public void emptyHorizonUsesObservedBounds() throws Exception {
        JSONObject before=state();assertEquals("proven_infeasible",plan(rules(0),before,1).getString("status"));
        exact(before,"x",1);assertEquals("feasible_within_model",plan(rules(0),before,1).getString("status"));
    }

    @Test public void invalidInputNeverSilentlyCoerced() throws Exception {
        invalid(()->plan(rules(1),state().put("slot",true),1));
        invalid(()->plan(rules(1),state().put("revision",1.5),1));
        invalid(()->plan(rules(1),state(),goal("x",1),o("{\"visit_caps\":{\"A\":-1}}")));
        invalid(()->PlannerEngine.plan(rules(1),state(),goal("x",1),o("{}"),0,10));
        invalid(()->PlannerEngine.validatePlan(rules(1),state(),a("[{\"instance_id\":\"x\",\"action_id\":\"__wait__\",\"slot\":false}]"),o("{}")));
    }
    @Test public void strictNumericMinimumNeverNarrowsUnknownIntoProof()throws Exception{
        JSONObject action=o("{\"id\":\"strict\",\"slots\":[0],\"requirements\":{\"key\":5},\"effects\":{\"x\":1},\"strict_requirements\":true}"),state=state();range(state,"key",4,null);
        violation("unknown_requirement",()->PlannerEngine.transition(rules(1,action),state,action,o("{}")));assertEquals(4,low(state,"key"));
        JSONObject result=plan(rules(1,action),state,1);assertEquals("unknown",result.getString("status"));
    }
    @Test public void strictCostsCannotSpendPossibleButUnprovenMoney()throws Exception{
        JSONObject action=o("{\"id\":\"paid\",\"slots\":[0],\"costs\":{\"cash\":5},\"strict_requirements\":true}"),state=state();range(state,"cash",null,null);
        violation("unknown_requirement",()->PlannerEngine.transition(rules(1,action),state,action,o("{}")));
    }
    @Test public void strictMaximumNeedsUpperBoundProof()throws Exception{
        JSONObject action=o("{\"id\":\"capped\",\"slots\":[0],\"maximums\":{\"rank\":9},\"strict_requirements\":true}"),state=state();range(state,"rank",9,null);
        violation("unknown_requirement",()->PlannerEngine.transition(rules(1,action),state,action,o("{}")));exact(state,"rank",9);assertEquals(1,PlannerEngine.transition(rules(1,action),state,action,o("{}")).getJSONObject("state").getInt("slot"));
    }
    @Test public void strictGateCanBeReachedByProvenEarlierGain()throws Exception{
        JSONObject train=o("{\"id\":\"train\",\"slots\":[0],\"uncertain_effects\":{\"key\":{\"low\":5,\"high\":null}}}"),use=o("{\"id\":\"use\",\"slots\":[1],\"requirements\":{\"key\":5},\"effects\":{\"x\":1},\"strict_requirements\":true}");
        JSONObject result=plan(rules(2,train,use),state(),1);assertEquals("feasible_within_model",result.getString("status"));assertEquals("train",result.getJSONArray("steps").getJSONObject(0).getString("action_id"));assertEquals("use",result.getJSONArray("steps").getJSONObject(1).getString("action_id"));
    }
    @Test public void strictFlagMustBeBoolean()throws Exception{JSONObject action=gain().put("strict_requirements","true");invalid(()->PlannerEngine.transition(rules(2,action),state(),action,o("{}")));}
    @Test public void equalFinalGoalProgressPrefersEarlierAttainment()throws Exception{
        JSONObject action=o("{\"id\":\"gain\",\"slots\":[0,1,2],\"effects\":{\"x\":1}}"),result=plan(rules(3,action),state(),1);assertEquals("gain",result.getJSONArray("steps").getJSONObject(0).getString("action_id"));assertEquals("__wait__",result.getJSONArray("steps").getJSONObject(1).getString("action_id"));assertEquals("__wait__",result.getJSONArray("steps").getJSONObject(2).getString("action_id"));
    }
    @Test public void earlierTieBreakDoesNotScheduleUselessActions()throws Exception{
        JSONObject noop=o("{\"id\":\"a_noop\",\"slots\":[0,1,2]}"),gain=o("{\"id\":\"z_gain\",\"slots\":[1],\"effects\":{\"x\":1}}"),result=plan(rules(3,noop,gain),state(),1);
        assertEquals("__wait__",result.getJSONArray("steps").getJSONObject(0).getString("action_id"));assertEquals("z_gain",result.getJSONArray("steps").getJSONObject(1).getString("action_id"));assertEquals("__wait__",result.getJSONArray("steps").getJSONObject(2).getString("action_id"));
    }

}
