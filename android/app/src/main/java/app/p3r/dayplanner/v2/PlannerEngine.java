package app.p3r.dayplanner.v2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Offline numeric planning foundation. This class contains no P3R game rules.
 *
 * Unknown bounds and visit totals remain unknown. Forecasts do not advance the
 * observed revision. Actual outcomes and idempotency belong to a separate store.
 * Costs precede arithmetic effects, assignments, then forget_fields. One action
 * consumes one slot. Larger numeric goal priorities are more important.
 */
public final class PlannerEngine {
    public static final String SIMULATOR_REVISION="e2";
    private PlannerEngine() { }

    public static final class RuleViolation extends IllegalArgumentException {
        public final String code;
        public RuleViolation(String code) { this(code, code); }
        public RuleViolation(String code, String message) { super(message); this.code = code; }
    }

    private static final class Interval {
        final Long low, high;
        Interval(Long low, Long high) {
            if (low != null && high != null && low > high) throw new IllegalArgumentException("Inverted interval");
            this.low = low; this.high = high;
        }
        static Interval unknown() { return new Interval(null, null); }
        static Interval exact(long value) { return new Interval(value, value); }
        Interval plus(Interval other) {
            return new Interval(low == null || other.low == null ? null : Math.addExact(low, other.low),
                    high == null || other.high == null ? null : Math.addExact(high, other.high));
        }
        JSONObject json() { JSONObject out = new JSONObject(); put(out,"low",low); put(out,"high",high); return out; }
    }

    private static final class State {
        String run, version;
        long revision;
        int slot;
        Map<String,Interval> values = new TreeMap<>();
        Map<String,Long> visits = new TreeMap<>();
        State(JSONObject raw) {
            run = string(raw,"run_id"); version = string(raw,"rules_version");
            revision = integer(raw.opt("revision"),"revision",0);
            slot = smallInt(raw.opt("slot"),"slot",0);
            JSONObject rawValues = object(raw,"values");
            for (String key : keys(rawValues)) values.put(identifier(key,"state field"),interval(rawValues.opt(key)));
            JSONObject rawVisits = optionalObject(raw,"visits");
            for (String key : keys(rawVisits)) {
                Object value = rawVisits.opt(key);
                visits.put(identifier(key,"visit stage"),nil(value) ? null : integer(value,"visit count",0));
            }
        }
        State(State original) {
            run=original.run;version=original.version;revision=original.revision;slot=original.slot;
            values.putAll(original.values);visits.putAll(original.visits);
        }
        JSONObject json() {
            JSONObject out=new JSONObject(),v=new JSONObject(),counts=new JSONObject();
            put(out,"run_id",run);put(out,"rules_version",version);put(out,"revision",revision);put(out,"slot",slot);
            for (String key:values.keySet())put(v,key,values.get(key).json());
            for (String key:visits.keySet())put(counts,key,visits.get(key));
            put(out,"values",v);put(out,"visits",counts);return out;
        }
    }

    private static final class Stage {
        final String id; final int start,end; final long ceiling;
        Stage(JSONObject raw) {
            id=string(raw,"id");start=smallInt(raw.opt("start"),"stage start",0);
            end=smallInt(raw.opt("end"),"stage end",1);ceiling=integer(raw.opt("floor_ceiling"),"floor ceiling",0);
            if(end<=start)throw new IllegalArgumentException("Empty stage window");
        }
    }

    private static final class Action {
        final String id,verification,source;
        final Set<Integer> slots=new TreeSet<>();
        final Map<String,Long> requirements,maximums,effects,costs;
        final Map<String,Interval> uncertain=new TreeMap<>(),assignments=new TreeMap<>();
        final Set<String> forget=new TreeSet<>();
        final boolean dungeon,strictRequirements;
        final Long target;
        final JSONObject raw;
        Action(JSONObject raw) {
            this.raw=copy(raw);id=string(raw,"id");
            JSONArray availability=array(raw,"slots");
            for(int i=0;i<availability.length();i++)slots.add(smallInt(availability.opt(i),"action slot",0));
            requirements=numbers(optionalObject(raw,"requirements"),false);
            maximums=numbers(optionalObject(raw,"maximums"),false);
            effects=numbers(optionalObject(raw,"effects"),false);
            costs=numbers(optionalObject(raw,"costs"),true);
            JSONObject gains=optionalObject(raw,"uncertain_effects");
            for(String key:keys(gains))uncertain.put(identifier(key,"effect field"),interval(gains.opt(key)));
            JSONObject assigned=optionalObject(raw,"assignments");
            for(String key:keys(assigned))assignments.put(identifier(key,"assignment field"),
                    nil(assigned.opt(key))?Interval.unknown():interval(assigned.opt(key)));
            JSONArray forgotten=optionalArray(raw,"forget_fields");
            for(int i=0;i<forgotten.length();i++)forget.add(identifier(forgotten.opt(i),"forgotten field"));
            dungeon=bool(raw,"dungeon_visit",false);strictRequirements=bool(raw,"strict_requirements",false);
            target=nil(raw.opt("target_floor"))?null:integer(raw.opt("target_floor"),"target floor",0);
            if(target!=null&&!dungeon)throw new IllegalArgumentException("Only dungeon actions have target floors");
            verification=optionalString(raw,"verification","synthetic");
            source=optionalString(raw,"source","synthetic");
        }
        Set<String> fields() {
            Set<String> result=new HashSet<>();
            result.addAll(requirements.keySet());result.addAll(maximums.keySet());result.addAll(effects.keySet());
            result.addAll(costs.keySet());result.addAll(uncertain.keySet());result.addAll(assignments.keySet());result.addAll(forget);
            return result;
        }
    }

    private static final class Rules {
        final String version;
        final int horizon;
        final boolean complete,synthetic;
        final Set<String> fields=new TreeSet<>();
        final Map<String,Action> actions=new TreeMap<>();
        final List<Stage> stages=new ArrayList<>();
        final Map<Integer,String> fixed;
        Rules(JSONObject raw) {
            version=string(raw,"version");horizon=smallInt(raw.opt("horizon"),"horizon",0);
            complete=bool(raw,"coverage_complete",true);synthetic=bool(raw,"synthetic",true);
            JSONArray names=array(raw,"fields");
            for(int i=0;i<names.length();i++)fields.add(identifier(names.opt(i),"declared field"));
            JSONArray list=array(raw,"actions");
            for(int i=0;i<list.length();i++) {
                Action action=new Action(jsonObject(list.opt(i),"action"));
                if(action.id.equals("__wait__")||actions.put(action.id,action)!=null)throw new IllegalArgumentException("Duplicate/reserved action ID");
            }
            JSONArray stageList=optionalArray(raw,"stages");Set<String> seen=new HashSet<>();
            for(int i=0;i<stageList.length();i++) {
                Stage stage=new Stage(jsonObject(stageList.opt(i),"stage"));
                if(!seen.add(stage.id))throw new IllegalArgumentException("Duplicate stage ID");stages.add(stage);
            }
            fixed=slotMap(optionalObject(raw,"fixed_actions"));
            for(Integer slot:fixed.keySet())if(slot>=horizon)throw new IllegalArgumentException("Fixed action outside horizon");
        }
    }

    private static final class Constraints {
        final Map<String,Long> caps;
        final Map<Integer,String> pins;
        Constraints(JSONObject raw) {
            caps=numbers(optionalObject(raw,"visit_caps"),true);
            pins=slotMap(optionalObject(raw,"pinned_actions"));
        }
    }

    private static final class Goal {
        final String field;final long target;final int priority;
        Goal(JSONObject raw) {
            field=string(raw,"field");target=integer(raw.opt("target"),"goal target",null);
            priority=raw.has("priority")?smallInt(raw.opt("priority"),"goal priority",null):0;
        }
    }

    private static final class Step {
        final String instance,action;final int slot;
        Step(String instance,String action,int slot) {this.instance=instance;this.action=action;this.slot=slot;}
        Step(JSONObject raw) {
            instance=string(raw,"instance_id");action=string(raw,"action_id");slot=smallInt(raw.opt("slot"),"step slot",0);
        }
        JSONObject json(){JSONObject out=new JSONObject();put(out,"instance_id",instance);put(out,"action_id",action);put(out,"slot",slot);return out;}
    }

    private static final class Change {
        final State state;final List<String> assumptions;
        Change(State state,List<String> assumptions){this.state=state;this.assumptions=assumptions;}
    }

    private static final class Node {
        final State state;final List<Step> steps;final List<String> assumptions;
        final List<BigInteger> timing=new ArrayList<>();int actionCount;
        Node(State state){this(state,new ArrayList<>(),new ArrayList<>());}
        Node(State state,List<Step> steps,List<String> assumptions){this.state=state;this.steps=steps;this.assumptions=assumptions;}
    }

    /** JSON transition result contains state and assumptions. Does not confirm an action. */
    public static JSONObject transition(JSONObject rules,JSONObject snapshot,JSONObject action,JSONObject constraints) {
        Rules parsed=new Rules(rules);State state=new State(snapshot);Action supplied=new Action(action);
        Action known=supplied.id.equals("__wait__")?waitAction(state.slot):parsed.actions.get(supplied.id);
        if(known==null||!canonical(known.raw).equals(canonical(supplied.raw)))throw new RuleViolation("unsupported_action");
        Change change=advance(parsed,state,known,new Constraints(constraints));
        JSONObject out=new JSONObject();put(out,"state",change.state.json());put(out,"assumptions",new JSONArray(change.assumptions));return out;
    }

    /** Independent chronological replay; assumptions are not promoted to observations. */
    public static JSONObject validatePlan(JSONObject rules,JSONObject snapshot,JSONArray steps,JSONObject constraints) {
        return replay(new Rules(rules),new State(snapshot),parseSteps(steps),new Constraints(constraints)).json();
    }

    /** Bounded deterministic search. Feasibility is model-scoped; optimality is never claimed. */
    public static JSONObject plan(JSONObject rawRules,JSONObject rawState,JSONArray rawGoals,JSONObject rawConstraints,
                                  int beamWidth,int maxExpansions) {
        if(beamWidth<1||maxExpansions<0)throw new IllegalArgumentException("Invalid search budget");
        Rules rules=new Rules(rawRules);State state=new State(rawState);Constraints constraints=new Constraints(rawConstraints);
        List<Goal> goals=new ArrayList<>();for(int i=0;i<rawGoals.length();i++)goals.add(new Goal(jsonObject(rawGoals.opt(i),"goal")));
        String token=requestId(rules,state,rawGoals,rawConstraints);
        Node initial=new Node(state),best=initial;
        try {checkContext(rules,state,constraints);}catch(RuleViolation error){
            return rawResult(rules,state,initial,"unsupported",Collections.singletonList(error.getMessage()),0,token);
        }
        for(Goal goal:goals)if(!rules.fields.contains(goal.field))return result(rules,state,initial,constraints,
                "unsupported",Collections.singletonList("A goal field has no modeled rules"),0,token);
        List<Node> frontier=new ArrayList<>();frontier.add(initial);
        List<Node> terminal=state.slot==rules.horizon?new ArrayList<>(frontier):new ArrayList<>();
        boolean pruned=false,limited=false,conditionalSeen=false;
        int expanded=0;
        List<String> unsupported=new ArrayList<>(),unknown=new ArrayList<>();
        outer:for(int slot=state.slot;slot<rules.horizon;slot++) {
            List<Node> candidates=new ArrayList<>();String required;
            try {required=requiredAction(rules,slot,constraints);}catch(RuleViolation error){
                if(error.code.startsWith("unsupported"))unsupported.add(error.getMessage());break;
            }
            List<Action> available=new ArrayList<>();
            for(Action action:rules.actions.values())if(action.slots.contains(slot)&&(required==null||required.equals(action.id)))available.add(action);
            if(required==null||required.equals("__wait__"))available.add(waitAction(slot));
            for(Node node:frontier) {
                for(Action action:available) {
                    if(expanded>=maxExpansions){limited=true;break;}
                    expanded++;
                    Change change;
                    try {change=advance(rules,node.state,action,constraints);}catch(RuleViolation error){
                        if(error.code.startsWith("unsupported"))unsupported.add(error.getMessage());
                        else if(error.code.startsWith("unknown"))unknown.add(error.getMessage());
                        continue;
                    }
                    conditionalSeen|=!change.assumptions.isEmpty();
                    List<Step> steps=new ArrayList<>(node.steps);steps.add(new Step(token+":"+slot+":"+action.id,action.id,slot));
                    List<String> assumptions=new ArrayList<>(node.assumptions);assumptions.addAll(change.assumptions);
                    Node child=new Node(change.state,steps,assumptions);List<BigInteger> currentScore=score(child,goals);
                    for(int i=0;i<currentScore.size()-1;i++)child.timing.add(currentScore.get(i).add(i<node.timing.size()?node.timing.get(i):BigInteger.ZERO));
                    child.actionCount=node.actionCount+(action.id.equals("__wait__")?0:1);candidates.add(child);
                }
                if(limited)break;
            }
            sort(candidates,goals);
            if(!candidates.isEmpty())best=candidates.get(0);
            if(slot+1==rules.horizon)terminal=new ArrayList<>(candidates);
            if(limited||candidates.isEmpty())break outer;
            if(slot+1<rules.horizon&&candidates.size()>beamWidth)pruned=true;
            frontier=new ArrayList<>(candidates.subList(0,Math.min(beamWidth,candidates.size())));
        }
        List<Node> guaranteed=new ArrayList<>(),possible=new ArrayList<>();
        for(Node node:terminal) {
            if(guaranteed(node.state,goals)&&node.assumptions.isEmpty())guaranteed.add(node);
            if(possible(node.state,goals))possible.add(node);
        }
        if(!guaranteed.isEmpty()) {
            sort(guaranteed,goals);Node choice=guaranteed.get(0);
            return result(rules,state,choice,constraints,rules.complete?"feasible_within_model":"conditional",
                    Collections.singletonList(rules.complete?"All goals verified within the supplied model; optimality is not claimed":
                            "Rule coverage is incomplete; this is only a candidate"),expanded,token);
        }
        if(!possible.isEmpty()) {
            sort(possible,goals);Node choice=possible.get(0);List<String> reasons=new ArrayList<>(choice.assumptions);
            if(!guaranteed(choice.state,goals))reasons.add("Goal attainment depends on unknown or uncertain outcomes");
            if(!rules.complete)reasons.add("Rule coverage is incomplete");
            return result(rules,state,choice,constraints,"conditional",reasons,expanded,token);
        }
        if(limited)return result(rules,state,best,constraints,"search_limit",Collections.singletonList("Expansion budget reached; no impossibility proof"),expanded,token);
        if(!unsupported.isEmpty())return result(rules,state,best,constraints,"unsupported",unsupported,expanded,token);
        if(pruned)return result(rules,state,best,constraints,"unknown",Collections.singletonList("Beam pruning discarded alternatives; no impossibility proof"),expanded,token);
        if(!unknown.isEmpty())return result(rules,state,best,constraints,"unknown",unknown,expanded,token);
        if(!rules.complete||conditionalSeen)return result(rules,state,best,constraints,"unknown",Collections.singletonList("Incomplete rules or uncertain prerequisites prevent a proof"),expanded,token);
        return result(rules,state,best,constraints,"proven_infeasible",Collections.singletonList("Exhaustive search found no schedule meeting all goals within this model and horizon"),expanded,token);
    }

    private static Change advance(Rules rules,State original,Action action,Constraints constraints) {
        checkContext(rules,original,constraints);
        if(original.slot>=rules.horizon)throw new RuleViolation("outside_horizon");
        if(!action.id.equals("__wait__")) {
            if(!rules.actions.containsKey(action.id))throw new RuleViolation("unsupported_action");
            if(action.source.trim().isEmpty()||!(action.verification.equals("verified")||action.verification.equals("corroborated")||action.verification.equals("synthetic")))
                throw new RuleViolation("unsupported_rule","Action "+action.id+" lacks supported evidence");
            if(action.verification.equals("synthetic")&&!rules.synthetic)throw new RuleViolation("unsupported_rule","Synthetic action is not a real game rule");
        }
        if(!action.slots.contains(original.slot))throw new RuleViolation("unavailable");
        String required=requiredAction(rules,original.slot,constraints);
        if(required!=null&&!required.equals(action.id))throw new RuleViolation("required_action");
        if(!rules.fields.containsAll(action.fields()))throw new RuleViolation("unsupported_field");
        State state=new State(original);List<String> assumptions=new ArrayList<>();
        if(action.dungeon) {
            Stage active=null;
            for(Stage stage:rules.stages)if(stage.start<=state.slot&&state.slot<stage.end) {
                if(active!=null)throw new RuleViolation("unsupported_stage","Overlapping active stages");active=stage;
            }
            if(active==null)throw new RuleViolation("unsupported_stage","Missing active stage");
            if(action.target!=null&&action.target>active.ceiling)throw new RuleViolation("floor_locked");
            Long used=state.visits.get(active.id),cap=constraints.caps.get(active.id);
            if(cap!=null) {
                if(used==null)throw new RuleViolation("unknown_visit_count","Stage "+active.id+" prior visit count is unknown");
                if(used>=cap)throw new RuleViolation("visit_cap");
            }
            state.visits.put(active.id,used==null?null:Math.addExact(used,1));
        }
        Map<String,Long> minimums=new TreeMap<>(action.requirements);
        for(String name:action.costs.keySet())minimums.put(name,Math.max(minimums.getOrDefault(name,action.costs.get(name)),action.costs.get(name)));
        for(String name:minimums.keySet()) {
            long threshold=minimums.get(name);Interval value=state.values.getOrDefault(name,Interval.unknown());
            if(value.high!=null&&value.high<threshold)throw new RuleViolation("requirement",name+" cannot meet "+threshold);
            if(value.low==null||value.low<threshold) {
                if(action.strictRequirements)throw new RuleViolation("unknown_requirement",name+" lower bound does not prove "+threshold);
                assumptions.add("slot "+state.slot+": "+name+" must be at least "+threshold+" before "+action.id);
                state.values.put(name,new Interval(threshold,value.high));
            }
        }
        for(String name:action.maximums.keySet()) {
            long maximum=action.maximums.get(name);Interval value=state.values.getOrDefault(name,Interval.unknown());
            if(value.low!=null&&value.low>maximum)throw new RuleViolation("requirement",name+" exceeds maximum "+maximum);
            if(value.high==null||value.high>maximum) {
                if(action.strictRequirements)throw new RuleViolation("unknown_requirement",name+" upper bound does not prove maximum "+maximum);
                assumptions.add("slot "+state.slot+": "+name+" must be at most "+maximum+" before "+action.id);
                state.values.put(name,new Interval(value.low,maximum));
            }
        }
        for(String name:action.costs.keySet())add(state,name,Interval.exact(Math.negateExact(action.costs.get(name))));
        for(String name:action.effects.keySet())add(state,name,Interval.exact(action.effects.get(name)));
        for(String name:action.uncertain.keySet())add(state,name,action.uncertain.get(name));
        state.values.putAll(action.assignments);
        for(String name:action.forget)state.values.put(name,Interval.unknown());
        state.slot++;return new Change(state,assumptions);
    }

    private static void add(State state,String name,Interval delta){state.values.put(name,state.values.getOrDefault(name,Interval.unknown()).plus(delta));}

    private static void checkContext(Rules rules,State state,Constraints constraints) {
        if(!state.version.equals(rules.version))throw new RuleViolation("rules_version_mismatch");
        if(state.slot>rules.horizon)throw new RuleViolation("outside_horizon");
        if(!rules.fields.containsAll(state.values.keySet()))throw new RuleViolation("unsupported_field");
        Set<String> stages=new HashSet<>();for(Stage stage:rules.stages)stages.add(stage.id);
        if(!stages.containsAll(constraints.caps.keySet()))throw new RuleViolation("unsupported_stage");
        for(int slot:constraints.pins.keySet())if(slot>=rules.horizon)throw new RuleViolation("outside_horizon");
    }

    private static String requiredAction(Rules rules,int slot,Constraints constraints) {
        String fixed=rules.fixed.get(slot),pin=constraints.pins.get(slot);
        if(fixed!=null&&pin!=null&&!fixed.equals(pin))throw new RuleViolation("conflicting_pin");
        String required=fixed!=null?fixed:pin;
        if(required!=null&&!required.equals("__wait__")&&!rules.actions.containsKey(required))throw new RuleViolation("unsupported_action","Missing required action: "+required);
        return required;
    }

    private static State replay(Rules rules,State initial,List<Step> steps,Constraints constraints) {
        checkContext(rules,initial,constraints);State state=new State(initial);Set<String> seen=new HashSet<>();
        for(Step step:steps) {
            if(!seen.add(step.instance))throw new RuleViolation("duplicate_instance");
            if(step.slot!=state.slot)throw new RuleViolation("wrong_slot");
            Action action=step.action.equals("__wait__")?waitAction(step.slot):rules.actions.get(step.action);
            if(action==null)throw new RuleViolation("unsupported_action");
            state=advance(rules,state,action,constraints).state;
        }
        return state;
    }

    private static boolean guaranteed(State state,List<Goal> goals) {
        for(Goal goal:goals){Interval value=state.values.getOrDefault(goal.field,Interval.unknown());if(value.low==null||value.low<goal.target)return false;}return true;
    }
    private static boolean possible(State state,List<Goal> goals) {
        for(Goal goal:goals){Interval value=state.values.getOrDefault(goal.field,Interval.unknown());if(value.high!=null&&value.high<goal.target)return false;}return true;
    }

    private static List<BigInteger> score(Node node,List<Goal> goals) {
        Set<Integer> priorities=new TreeSet<>(Collections.reverseOrder());for(Goal goal:goals)priorities.add(goal.priority);
        List<BigInteger> result=new ArrayList<>();
        for(int priority:priorities) {
            long met=0;BigInteger progress=BigInteger.ZERO;
            for(Goal goal:goals)if(goal.priority==priority) {
                Long low=node.state.values.getOrDefault(goal.field,Interval.unknown()).low;
                if(low!=null&&low>=goal.target)met++;
                progress=progress.add(low==null?BigInteger.TEN.pow(30).negate():BigInteger.valueOf(Math.min(goal.target,low)));
            }
            result.add(BigInteger.valueOf(met));result.add(progress);
        }
        result.add(BigInteger.valueOf(-node.assumptions.size()));return result;
    }

    private static void sort(List<Node> nodes,List<Goal> goals) {
        nodes.sort((left,right)->{
            List<BigInteger>a=score(left,goals),b=score(right,goals);
            for(int i=0;i<a.size();i++){int order=b.get(i).compareTo(a.get(i));if(order!=0)return order;}
            for(int i=0;i<Math.min(left.timing.size(),right.timing.size());i++){int order=right.timing.get(i).compareTo(left.timing.get(i));if(order!=0)return order;}
            int fewerActions=Integer.compare(left.actionCount,right.actionCount);if(fewerActions!=0)return fewerActions;
            for(int i=0;i<Math.min(left.steps.size(),right.steps.size());i++){
                int order=left.steps.get(i).action.compareTo(right.steps.get(i).action);if(order!=0)return order;
            }
            return Integer.compare(left.steps.size(),right.steps.size());
        });
    }

    private static JSONObject result(Rules rules,State initial,Node node,Constraints constraints,String status,List<String> reasons,int expanded,String token) {
        State replayed=replay(rules,initial,node.steps,constraints);
        if(!canonical(replayed.json()).equals(canonical(node.state.json())))throw new AssertionError("Forecast differs from independent replay");
        return rawResult(rules,initial,node,status,reasons,expanded,token);
    }

    private static JSONObject rawResult(Rules rules,State initial,Node node,String status,List<String> reasons,int expanded,String token) {
        JSONObject out=new JSONObject(),identity=new JSONObject();JSONArray steps=new JSONArray(),identitySteps=new JSONArray();
        for(Step step:node.steps){steps.put(step.json());JSONArray pair=new JSONArray();pair.put(step.slot);pair.put(step.action);identitySteps.put(pair);}
        put(identity,"request",token);put(identity,"steps",identitySteps);
        put(out,"run_id",initial.run);put(out,"rules_version",rules.version);put(out,"state_revision",initial.revision);
        put(out,"steps",steps);put(out,"forecast",node.state.json());put(out,"status",status);
        List<String> unique=new ArrayList<>();for(String reason:reasons)if(!unique.contains(reason))unique.add(reason);
        put(out,"explanations",new JSONArray(unique));put(out,"expanded",expanded);put(out,"plan_id",hash(canonical(identity)));
        return out;
    }

    private static String requestId(Rules rules,State state,JSONArray goals,JSONObject constraints) {
        JSONObject request=new JSONObject();put(request,"state",state.json());put(request,"rules",rules.version);
        List<String> sortedGoals=new ArrayList<>();for(int i=0;i<goals.length();i++)sortedGoals.add(canonical(goals.opt(i)));Collections.sort(sortedGoals);
        put(request,"goals",new JSONArray(sortedGoals));put(request,"constraints",constraints);return hash(canonical(request));
    }

    private static Action waitAction(int slot) {
        JSONObject raw=new JSONObject();put(raw,"id","__wait__");JSONArray slots=new JSONArray();slots.put(slot);put(raw,"slots",slots);return new Action(raw);
    }

    private static List<Step> parseSteps(JSONArray raw) {
        List<Step> steps=new ArrayList<>();for(int i=0;i<raw.length();i++)steps.add(new Step(jsonObject(raw.opt(i),"step")));return steps;
    }

    private static Interval interval(Object raw) {
        JSONObject value=jsonObject(raw,"interval");
        if(!value.has("low")||!value.has("high"))throw new IllegalArgumentException("Interval needs explicit low/high");
        return new Interval(nil(value.opt("low"))?null:integer(value.opt("low"),"low",null),
                nil(value.opt("high"))?null:integer(value.opt("high"),"high",null));
    }

    private static Map<String,Long> numbers(JSONObject raw,boolean nonnegative) {
        Map<String,Long> result=new TreeMap<>();for(String key:keys(raw))result.put(identifier(key,"field/stage"),integer(raw.opt(key),key,nonnegative?0:null));return result;
    }

    private static Map<Integer,String> slotMap(JSONObject raw) {
        Map<Integer,String> result=new TreeMap<>();for(String key:keys(raw)) {
            if(!key.matches("0|[1-9][0-9]*"))throw new IllegalArgumentException("Slot key must be a nonnegative integer");
            int slot;try{slot=Integer.parseInt(key);}catch(NumberFormatException error){throw new IllegalArgumentException("Slot too large");}
            result.put(slot,identifier(raw.opt(key),"required action"));
        }return result;
    }

    private static String string(JSONObject object,String key){return identifier(object.opt(key),key);}
    private static String identifier(Object value,String name){if(!(value instanceof String)||((String)value).trim().isEmpty())throw new IllegalArgumentException(name+" must be a nonempty string");return(String)value;}
    private static String optionalString(JSONObject object,String key,String fallback){if(!object.has(key))return fallback;Object value=object.opt(key);if(!(value instanceof String))throw new IllegalArgumentException(key+" must be a string");return(String)value;}
    private static boolean nil(Object value){return value==null||value==JSONObject.NULL;}
    private static long integer(Object value,String name,Integer minimum) {
        if(!(value instanceof Integer||value instanceof Long||value instanceof Short||value instanceof Byte))throw new IllegalArgumentException(name+" must be an integer");
        long number=((Number)value).longValue();if(minimum!=null&&number<minimum)throw new IllegalArgumentException(name+" below minimum");return number;
    }
    private static int smallInt(Object value,String name,Integer minimum){long number=integer(value,name,minimum);if(number<Integer.MIN_VALUE||number>Integer.MAX_VALUE)throw new IllegalArgumentException(name+" outside integer range");return(int)number;}
    private static boolean bool(JSONObject object,String key,boolean fallback){if(!object.has(key))return fallback;Object value=object.opt(key);if(!(value instanceof Boolean))throw new IllegalArgumentException(key+" must be boolean");return(Boolean)value;}
    private static JSONObject jsonObject(Object value,String name){if(!(value instanceof JSONObject))throw new IllegalArgumentException(name+" must be an object");return(JSONObject)value;}
    private static JSONObject object(JSONObject object,String key){return jsonObject(object.opt(key),key);}
    private static JSONObject optionalObject(JSONObject object,String key){return object.has(key)?jsonObject(object.opt(key),key):new JSONObject();}
    private static JSONArray array(JSONObject object,String key){Object value=object.opt(key);if(!(value instanceof JSONArray))throw new IllegalArgumentException(key+" must be an array");return(JSONArray)value;}
    private static JSONArray optionalArray(JSONObject object,String key){return object.has(key)?array(object,key):new JSONArray();}
    private static List<String> keys(JSONObject object){List<String> result=new ArrayList<>();java.util.Iterator<String> iter=object.keys();while(iter.hasNext())result.add(iter.next());Collections.sort(result);return result;}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value==null?JSONObject.NULL:value);}catch(JSONException error){throw new IllegalArgumentException(error);}}
    private static JSONObject copy(JSONObject source){try{return new JSONObject(source.toString());}catch(JSONException error){throw new IllegalArgumentException(error);}}
    private static String hash(String input){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(int i=0;i<12;i++)out.append(String.format(java.util.Locale.ROOT,"%02x",bytes[i]&255));return out.toString();}catch(Exception error){throw new IllegalStateException(error);}}
    private static String canonical(Object value) {
        if(nil(value))return"null";
        if(value instanceof JSONObject){StringBuilder out=new StringBuilder("{");boolean first=true;for(String key:keys((JSONObject)value)){if(!first)out.append(',');first=false;out.append(JSONObject.quote(key)).append(':').append(canonical(((JSONObject)value).opt(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){StringBuilder out=new StringBuilder("[");JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.opt(i)));}return out.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
}
