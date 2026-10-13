package app.p3r.dayplanner.v2;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.*;

/** Separate observed-state ledger. Never reads or modifies the legacy checklist database.
 * All mutation methods are transactional. Unknown run throws InvalidObservation.
 * Returned objects are detached; null JSON values mean unknown, never zero.
 */
public final class PlannerStateStore implements AutoCloseable {
    public static final String DATABASE_NAME = "p3r_goal_planner_v2.db";
    public static class InvalidObservation extends IllegalArgumentException {
        public InvalidObservation(String message) { super(message); }
    }
    public static class StateConflict extends IllegalStateException {
        public StateConflict(String message) { super(message); }
    }
    private final Helper helper;
    private final SQLiteDatabase db;
    private final JSONObject ceilings, windows;
    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, DATABASE_NAME, null, 1); }
        @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE runs(run_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, epoch INTEGER NOT NULL, snapshot TEXT NOT NULL, plan_revision INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE checkpoints(run_id TEXT NOT NULL REFERENCES runs(run_id), epoch INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id,epoch))");
            db.execSQL("CREATE TABLE events(seq INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES runs(run_id), command_id TEXT NOT NULL, epoch INTEGER NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(run_id,command_id))");
            db.execSQL("CREATE TABLE preferences(run_id TEXT PRIMARY KEY REFERENCES runs(run_id), payload TEXT NOT NULL)");
            db.execSQL("CREATE TABLE plans(run_id TEXT PRIMARY KEY REFERENCES runs(run_id), plan_revision INTEGER NOT NULL, state_revision INTEGER NOT NULL, rules_version TEXT NOT NULL, payload TEXT NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
            throw new IllegalStateException("Planner schema migration required; data will not be deleted");
        }
    }
    public PlannerStateStore(Context context, JSONObject stageCeilings, JSONObject stageWindows) {
        ceilings=copy(stageCeilings == null ? new JSONObject() : stageCeilings);
        windows=copy(stageWindows == null ? new JSONObject() : stageWindows);
        List<long[]> spans=new ArrayList<>();
        for(String key:keys(ceilings)) { id(key); integer(ceilings.opt(key),0L,false); }
        for(String key:keys(windows)) {
            id(key); Object raw=windows.opt(key);
            if(!(raw instanceof JSONArray) || ((JSONArray)raw).length()!=2) fail("stage window needs [start,end)");
            JSONArray pair=(JSONArray)raw;
            long start=integer(pair.opt(0),0L,false), end=integer(pair.opt(1),null,false);
            if(end<=start) fail("empty stage window");
            spans.add(new long[]{start,end});
        }
        spans.sort(Comparator.comparingLong(a->a[0]));
        for(int i=1;i<spans.size();i++) if(spans.get(i-1)[1]>spans.get(i)[0]) fail("overlapping stage windows");
        helper=new Helper(context.getApplicationContext()); db=helper.getWritableDatabase();
    }
    private static void fail(String message) { throw new InvalidObservation(message); }
    private static String id(Object value) {
        if(!(value instanceof String) || ((String)value).trim().isEmpty()) fail("identifier must be nonempty text");
        return (String)value;
    }
    private static boolean nil(Object value) { return value==null || value==JSONObject.NULL; }
    private static long integer(Object value,Long min,boolean nullable) {
        if(nil(value) && nullable) return 0;
        if(!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)) fail("integer required");
        long n=((Number)value).longValue(); if(min!=null && n<min) fail("integer below minimum"); return n;
    }
    private static long add(long a,long b) {
        try { return Math.addExact(a,b); } catch(ArithmeticException e) { throw new InvalidObservation("integer overflow"); }
    }
    private static JSONObject object(Object v) { if(!(v instanceof JSONObject)) fail("object required"); return (JSONObject)v; }
    private static JSONObject parse(String s) { try { return new JSONObject(s); } catch(JSONException e) { throw new InvalidObservation("invalid JSON object"); } }
    private static JSONObject copy(JSONObject o) { return parse(o.toString()); }
    private static void put(JSONObject o,String k,Object v) { try { o.put(k,v==null?JSONObject.NULL:v); } catch(JSONException e) { throw new InvalidObservation(e.getMessage()); } }
    private static List<String> keys(JSONObject o) { List<String> out=new ArrayList<>(); o.keys().forEachRemaining(out::add); Collections.sort(out); return out; }
    private static String canonical(Object o) {
        if(nil(o)) return "null";
        if(o instanceof JSONObject) { List<String> parts=new ArrayList<>(); for(String k:keys((JSONObject)o)) parts.add(JSONObject.quote(k)+":"+canonical(((JSONObject)o).opt(k))); return "{"+String.join(",",parts)+"}"; }
        if(o instanceof JSONArray) { List<String> parts=new ArrayList<>(); for(int i=0;i<((JSONArray)o).length();i++) parts.add(canonical(((JSONArray)o).opt(i))); return "["+String.join(",",parts)+"]"; }
        if(o instanceof String) return JSONObject.quote((String)o);
        if(o instanceof Number || o instanceof Boolean) return o.toString();
        throw new InvalidObservation("unsupported JSON value");
    }
    private static void allowed(JSONObject o,String... names) { Set<String> permitted=new HashSet<>(Arrays.asList(names)); for(String k:keys(o)) if(!permitted.contains(k)) fail("unknown field: "+k); }
    private JSONObject baseline(JSONObject input) {
        JSONObject b=copy(input);
        String[] required={"rules_version","game_slot","cash","highest_floor","active_stage","visits_by_stage","facts"};
        for(String k:required) if(!b.has(k)) fail("missing baseline field: "+k);
        allowed(b,"rules_version","game_slot","cash","highest_floor","active_stage","visits_by_stage","facts","legacy_guide_backup","planning_context","option_policy");
        for(String optional:new String[]{"planning_context","option_policy"}) if(b.has(optional)) object(b.opt(optional));
        id(b.opt("rules_version")); long slot=integer(b.opt("game_slot"),0L,false);
        integer(b.opt("cash"),0L,true); integer(b.opt("highest_floor"),0L,true);
        if(!b.isNull("active_stage")) id(b.opt("active_stage"));
        JSONObject visits=object(b.opt("visits_by_stage")), facts=object(b.opt("facts"));
        for(String k:keys(visits)) { id(k); integer(visits.opt(k),0L,true); }
        for(String k:keys(facts)) {
            id(k); JSONObject f=object(facts.opt(k)); allowed(f,"low","high");
            if(!f.has("low") || !f.has("high")) fail("fact needs explicit low/high");
            integer(f.opt("low"),null,true); integer(f.opt("high"),null,true);
            if(!f.isNull("low")&&!f.isNull("high")&&f.optLong("low")>f.optLong("high")) fail("inverted fact interval");
        }
        return b;
    }
    // Mutable rules are checked only for new commands, after durable retry lookup.
    private void checkObservedStage(JSONObject baseline) {
        String known=stageAt(baseline.optLong("game_slot"));
        if(known!=null&&!known.equals(baseline.opt("active_stage"))) fail("observed stage conflicts with verified calendar");
    }
    private void checkReturnCeiling(JSONObject payload) {
        String stage=payload.optString("stage_id");
        if(ceilings.has(stage)&&payload.optLong("highest_floor")>ceilings.optLong(stage)) fail("floor exceeds verified stage ceiling");
    }
    private String stageAt(long slot) {
        for(String k:keys(windows)) { JSONArray p=(JSONArray)windows.opt(k); if(slot>=((Number)p.opt(0)).longValue()&&slot<((Number)p.opt(1)).longValue()) return k; }
        return null;
    }
    private List<JSONObject> rows(String sql,String... args) {
        List<JSONObject> result=new ArrayList<>();
        try(Cursor c=db.rawQuery(sql,args)) { while(c.moveToNext()) { JSONObject r=new JSONObject(); for(int i=0;i<c.getColumnCount();i++) put(r,c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getType(i)==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):c.getString(i)); result.add(r); } }
        return result;
    }
    private JSONObject first(String sql,String... args) { List<JSONObject> r=rows(sql,args); return r.isEmpty()?null:r.get(0); }
    private JSONObject run(String runId) { id(runId); JSONObject r=first("SELECT * FROM runs WHERE run_id=?",runId); if(r==null) fail("unknown run"); return r; }
    public synchronized JSONObject getSnapshot(String runId) { return parse(run(runId).optString("snapshot")); }
    private void revision(JSONObject row,long expected) { if(expected<0||row.optLong("revision")!=expected) throw new StateConflict("state or preferences changed; reload before submitting"); }
    private JSONObject receipt(JSONObject state,boolean duplicate,JSONArray warnings) { JSONObject r=new JSONObject(); put(r,"snapshot",state); put(r,"duplicate",duplicate); put(r,"warnings",warnings); return r; }
    private JSONObject existing(String runId,String command,String kind,JSONObject payload) {
        JSONObject old=first("SELECT * FROM events WHERE run_id=? AND command_id=?",runId,command);
        if(old==null) return null;
        if(!kind.equals(old.optString("kind"))||!canonical(payload).equals(canonical(parse(old.optString("payload"))))) throw new StateConflict("command ID reused with different payload");
        return receipt(getSnapshot(runId),true,new JSONArray());
    }
    private void event(String runId,String command,long epoch,String kind,JSONObject payload) {
        db.execSQL("INSERT INTO events(run_id,command_id,epoch,kind,payload) VALUES(?,?,?,?,?)",new Object[]{runId,command,epoch,kind,canonical(payload)});
    }
    private void save(String runId,JSONObject state) {
        db.execSQL("UPDATE runs SET revision=?,epoch=?,snapshot=? WHERE run_id=?",new Object[]{state.optLong("revision"),state.optLong("epoch"),canonical(state),runId});
        db.execSQL("DELETE FROM plans WHERE run_id=?",new Object[]{runId});
    }
    private JSONObject state(JSONObject b,String runId,long rev,long epoch) {
        JSONObject s=copy(b); put(s,"run_id",runId); put(s,"revision",rev); put(s,"epoch",epoch); put(s,"visits_since_baseline",new JSONObject()); return s;
    }
    public synchronized JSONObject createRun(String runId,JSONObject input) {
        id(runId); JSONObject b=baseline(input); checkObservedStage(b); JSONObject s=state(b,runId,0,0);
        db.beginTransaction(); try {
            if(first("SELECT run_id FROM runs WHERE run_id=?",runId)!=null) throw new StateConflict("run already exists");
            db.execSQL("INSERT INTO runs(run_id,revision,epoch,snapshot) VALUES(?,0,0,?)",new Object[]{runId,canonical(s)});
            db.execSQL("INSERT INTO checkpoints VALUES(?,0,?)",new Object[]{runId,canonical(b)});
            db.setTransactionSuccessful(); return s;
        } finally { db.endTransaction(); }
    }
    /** Initial baseline and preferences commit together. Same command is restart-safe. */
    public synchronized JSONObject createRunWithPreferences(String runId,String command,
            JSONObject input,JSONArray goals,JSONObject constraints) {
        id(runId); id(command); JSONObject b=baseline(input), prefs=preferencesPayload(goals,constraints);
        JSONObject payload=new JSONObject(); put(payload,"baseline",b); put(payload,"preferences",prefs);
        db.beginTransaction(); try {
            JSONObject prior=existing(runId,command,"initial_setup",payload);
            if(prior!=null) { db.setTransactionSuccessful(); return prior; }
            if(first("SELECT run_id FROM runs WHERE run_id=?",runId)!=null) throw new StateConflict("run already exists");
            checkObservedStage(b); JSONObject snapshot=state(b,runId,0,0);
            db.execSQL("INSERT INTO runs(run_id,revision,epoch,snapshot) VALUES(?,0,0,?)",new Object[]{runId,canonical(snapshot)});
            db.execSQL("INSERT INTO checkpoints VALUES(?,0,?)",new Object[]{runId,canonical(b)});
            db.execSQL("INSERT INTO preferences VALUES(?,?)",new Object[]{runId,canonical(prefs)});
            event(runId,command,0,"initial_setup",payload);
            db.setTransactionSuccessful(); return receipt(snapshot,false,new JSONArray());
        } finally { db.endTransaction(); }
    }
    public synchronized JSONObject reenter(String runId,String command,long expected,JSONObject input) {
        id(command); JSONObject b=baseline(input); db.beginTransaction(); try {
            JSONObject prior=existing(runId,command,"baseline",b); if(prior!=null) { db.setTransactionSuccessful(); return prior; }
            checkObservedStage(b);
            JSONObject row=run(runId); revision(row,expected); long epoch=add(row.optLong("epoch"),1);
            JSONObject s=state(b,runId,add(row.optLong("revision"),1),epoch);
            db.execSQL("INSERT INTO checkpoints VALUES(?,?,?)",new Object[]{runId,epoch,canonical(b)});
            event(runId,command,epoch,"baseline",b); save(runId,s); db.setTransactionSuccessful(); return receipt(s,false,new JSONArray());
        } finally { db.endTransaction(); }
    }
    private JSONObject returnPayload(JSONObject input) {
        allowed(input,"stage_id","highest_floor","net_cash","absolute_balance","corrects_event_id","visit_slot","next_game_slot","next_slot_capacity");
        JSONObject p=new JSONObject(); String stage=id(input.opt("stage_id")); long floor=integer(input.opt("highest_floor"),0L,false);
        Object net=input.opt("net_cash"), absolute=input.opt("absolute_balance"), correction=input.opt("corrects_event_id");
        if(nil(net)==nil(absolute)) fail("enter net cash OR absolute balance, exactly one");
        if(!nil(net)) integer(net,null,false); else integer(absolute,0L,false);
        if(!nil(correction)) id(correction);
        put(p,"stage_id",stage); put(p,"highest_floor",floor); put(p,"net_cash",net); put(p,"absolute_balance",absolute); put(p,"corrects_event_id",correction); return p;
    }
    private void validateNextCapacity(JSONObject payload) {
        if(!payload.isNull("next_slot_capacity")) { long value=integer(payload.opt("next_slot_capacity"),0L,false); if(value>1) fail("next slot capacity must be 0, 1 or unknown"); }
    }
    private void updateNextCapacity(JSONObject snapshot,JSONObject payload) {
        JSONObject context=snapshot.optJSONObject("planning_context"); if(context==null) context=new JSONObject();
        JSONObject unlock=context.optJSONObject("unlock");
        if(unlock!=null) for(String slug:keys(unlock)) {
            JSONObject entity=unlock.optJSONObject(slug), observations=entity==null?null:entity.optJSONObject("observations");
            if(observations!=null) for(String key:keys(observations)) if(key.startsWith("current.")||key.startsWith("calendar.")||key.equals("observedIntroductionAvailable")) observations.remove(key);
        }
        JSONObject calendar=context.optJSONObject("calendar"); if(calendar==null) calendar=new JSONObject();
        JSONObject slots=calendar.optJSONObject("slotStates"); if(slots==null) slots=new JSONObject();
        Object capacity=payload.opt("next_slot_capacity"); boolean free=!nil(capacity)&&((Number)capacity).longValue()==1;
        JSONObject current=new JSONObject(); put(current,"remainingCapacity",capacity); put(current,"knownStoryLock",free?Boolean.FALSE:JSONObject.NULL); put(current,"slotState",free?"free":"unknown");
        put(slots,String.valueOf(snapshot.optLong("game_slot")),current); put(calendar,"slotStates",slots); put(context,"calendar",calendar); put(snapshot,"planning_context",context);
    }
    private JSONObject replay(String runId,long epoch) {
        JSONObject cp=first("SELECT payload FROM checkpoints WHERE run_id=? AND epoch=?",runId,String.valueOf(epoch));
        JSONObject s=parse(cp.optString("payload")); put(s,"visits_since_baseline",new JSONObject());
        List<JSONObject> events=rows("SELECT * FROM events WHERE run_id=? AND epoch=? ORDER BY seq",runId,String.valueOf(epoch));
        Map<String,JSONObject> replacements=new HashMap<>();
        for(JSONObject e:events) if("return_correction".equals(e.optString("kind"))) { JSONObject p=parse(e.optString("payload")); replacements.put(p.optString("corrects_event_id"),p); }
        for(JSONObject e:events) {
            if("observed_action".equals(e.optString("kind"))) {
                JSONObject observed=parse(e.optString("payload"));
                if(observed.optLong("action_slot")!=s.optLong("game_slot")) fail("observed action overlaps or skips a consumed slot");
                JSONObject facts=object(s.opt("facts")), assignments=object(observed.opt("fact_assignments"));
                for(String name:keys(assignments)) put(facts,name,copy(object(assignments.opt(name))));
                if(observed.has("absolute_balance")) put(s,"cash",observed.opt("absolute_balance"));
                put(s,"game_slot",observed.optLong("next_game_slot")); updateNextCapacity(s,observed);
                if(windows.length()>0) put(s,"active_stage",stageAt(s.optLong("game_slot")));
                continue;
            }
            if(!"dungeon_return".equals(e.optString("kind"))) continue;
            JSONObject p=replacements.get(e.optString("command_id")); if(p==null) p=parse(e.optString("payload"));
            if(p.optLong("visit_slot")!=s.optLong("game_slot")) fail("dungeon return overlaps or skips a consumed slot");
            if(!p.isNull("absolute_balance")) put(s,"cash",p.opt("absolute_balance"));
            else { if(s.isNull("cash")) fail("cash unknown; enter absolute balance"); long cash=add(s.optLong("cash"),p.optLong("net_cash")); if(cash<0) fail("cash cannot become negative"); put(s,"cash",cash); }
            put(s,"highest_floor",s.isNull("highest_floor")?p.optLong("highest_floor"):Math.max(s.optLong("highest_floor"),p.optLong("highest_floor")));
            String stage=p.optString("stage_id"); JSONObject counts=object(s.opt("visits_by_stage")), since=object(s.opt("visits_since_baseline"));
            put(counts,stage,counts.isNull(stage)?JSONObject.NULL:add(counts.optLong(stage),1)); put(since,stage,add(since.optLong(stage,0),1));
            put(s,"game_slot",p.optLong("next_game_slot")); updateNextCapacity(s,p); if(windows.length()>0) put(s,"active_stage",stageAt(s.optLong("game_slot")));
        }
        return s;
    }
    public synchronized JSONObject recordDungeonReturn(String runId,String command,long expected,JSONObject input) {
        id(command); JSONObject p=returnPayload(input); String correction=p.isNull("corrects_event_id")?null:p.optString("corrects_event_id");
        String kind=correction==null?"dungeon_return":"return_correction";
        db.beginTransaction(); try {
            JSONObject row=run(runId), observed=parse(row.optString("snapshot"));
            JSONObject old=first("SELECT * FROM events WHERE run_id=? AND command_id=?",runId,command);
            JSONObject target=correction==null?null:first("SELECT * FROM events WHERE run_id=? AND command_id=?",runId,correction);
            JSONObject captured=old!=null?parse(old.optString("payload")):target!=null?parse(target.optString("payload")):null;
            long slot=input.isNull("visit_slot")?(captured!=null?captured.optLong("visit_slot",observed.optLong("game_slot")):observed.optLong("game_slot")):integer(input.opt("visit_slot"),0L,false);
            long next=input.isNull("next_game_slot")?(captured!=null?captured.optLong("next_game_slot",add(slot,1)):add(slot,1)):integer(input.opt("next_game_slot"),0L,false);
            if(next<=slot) fail("return must advance game slot"); put(p,"visit_slot",slot); put(p,"next_game_slot",next);
            if(input.has("next_slot_capacity")) put(p,"next_slot_capacity",input.opt("next_slot_capacity"));
            else if(captured!=null&&captured.has("next_slot_capacity")) put(p,"next_slot_capacity",captured.opt("next_slot_capacity"));
            validateNextCapacity(p);
            JSONObject prior=existing(runId,command,kind,p); if(prior!=null) { db.setTransactionSuccessful(); return prior; }
            revision(row,expected); checkReturnCeiling(p);
            if(correction!=null) {
                if(target==null||!"dungeon_return".equals(target.optString("kind"))||target.optLong("epoch")!=row.optLong("epoch")) fail("correction must target original return in current checkpoint");
                JSONObject original=parse(target.optString("payload"));
                if(!original.optString("stage_id").equals(p.optString("stage_id"))) fail("correction cannot move stage");
                if(original.optLong("visit_slot")!=slot||original.optLong("next_game_slot")!=next) fail("correction cannot move time");
                if(!canonical(original.opt("next_slot_capacity")).equals(canonical(p.opt("next_slot_capacity")))) fail("correction cannot change next-slot observation");
            } else {
                if(!p.optString("stage_id").equals(observed.opt("active_stage"))) fail("stage does not match observed active stage");
                if(slot!=observed.optLong("game_slot")) throw new StateConflict("return form belongs to earlier slot");
                for(JSONObject e:rows("SELECT payload FROM events WHERE run_id=? AND epoch=? AND kind='dungeon_return'",runId,String.valueOf(row.optLong("epoch")))) if(parse(e.optString("payload")).optLong("visit_slot")==slot) throw new StateConflict("slot already has a return");
            }
            event(runId,command,row.optLong("epoch"),kind,p);
            JSONObject s=replay(runId,row.optLong("epoch")); put(s,"run_id",runId); put(s,"revision",add(row.optLong("revision"),1)); put(s,"epoch",row.optLong("epoch")); save(runId,s);
            JSONArray warnings=new JSONArray(); if(object(s.opt("visits_by_stage")).isNull(p.optString("stage_id"))) warnings.put("Prior visit count is unknown; confirm it before promising an allowance.");
            db.setTransactionSuccessful(); return receipt(s,false,warnings);
        } finally { db.endTransaction(); }
    }
    /** Records only caller-confirmed observations. Coordinator validates game-specific
     * field scope and action kind; no scheduled effects or dungeon visits are inferred.
     * Plan binding is optional, but revision+instance identity must be supplied together.
     */
    public synchronized JSONObject recordObservedAction(String runId,String command,long expected,JSONObject input) {
        id(command); allowed(input,"action_id","action_slot","next_game_slot","fact_assignments","absolute_balance","expected_plan_revision","action_instance_id","next_slot_capacity");
        String action=id(input.opt("action_id")); if("__wait__".equals(action)) fail("wait is not an observed social action");
        long slot=integer(input.opt("action_slot"),0L,false), next=integer(input.opt("next_game_slot"),0L,false);
        if(next<=slot) fail("observed action must advance the slot");
        JSONObject assignments=object(input.opt("fact_assignments"));
        for(String field:keys(assignments)) {
            id(field); JSONObject interval=object(assignments.opt(field)); allowed(interval,"low","high");
            if(!interval.has("low")||!interval.has("high")) fail("assigned fact needs explicit low/high");
            integer(interval.opt("low"),null,true); integer(interval.opt("high"),null,true);
            if(!interval.isNull("low")&&!interval.isNull("high")&&interval.optLong("low")>interval.optLong("high")) fail("inverted observed interval");
        }
        if(input.has("absolute_balance")) integer(input.opt("absolute_balance"),0L,true);
        boolean hasPlan=!input.isNull("expected_plan_revision"), hasInstance=!input.isNull("action_instance_id");
        if(hasPlan!=hasInstance) fail("plan revision and action instance must be supplied together");
        if(hasPlan) { integer(input.opt("expected_plan_revision"),0L,false); id(input.opt("action_instance_id")); }
        JSONObject payload=copy(input); validateNextCapacity(payload);
        // Normalize omitted optional binding keys so retries are independent of null omission.
        if(!hasPlan) { put(payload,"expected_plan_revision",JSONObject.NULL); put(payload,"action_instance_id",JSONObject.NULL); }
        db.beginTransaction(); try {
            JSONObject prior=existing(runId,command,"observed_action",payload);
            if(prior!=null) { db.setTransactionSuccessful(); return prior; }
            JSONObject row=run(runId); revision(row,expected); JSONObject observed=parse(row.optString("snapshot"));
            if(slot!=observed.optLong("game_slot")) throw new StateConflict("action form belongs to another game slot");
            if(hasPlan) {
                JSONObject installed=getPlan(runId);
                if(installed==null||installed.optLong("plan_revision")!=payload.optLong("expected_plan_revision")||installed.optLong("state_revision")!=row.optLong("revision")) throw new StateConflict("plan is no longer current");
                JSONObject forecast=object(installed.opt("payload")); JSONArray steps=forecast.optJSONArray("steps"); boolean found=false;
                if(steps!=null) for(int i=0;i<steps.length();i++) {
                    JSONObject step=object(steps.opt(i));
                    if(payload.optString("action_instance_id").equals(step.optString("instance_id"))&&action.equals(step.optString("action_id"))&&slot==step.optLong("slot",-1)) { found=true; break; }
                }
                if(!found) throw new StateConflict("action instance does not match the current plan slot");
            }
            event(runId,command,row.optLong("epoch"),"observed_action",payload);
            JSONObject state=replay(runId,row.optLong("epoch")); put(state,"run_id",runId); put(state,"revision",add(row.optLong("revision"),1)); put(state,"epoch",row.optLong("epoch"));
            save(runId,state); db.setTransactionSuccessful(); return receipt(state,false,new JSONArray());
        } finally { db.endTransaction(); }
    }
    private JSONObject preferencesPayload(JSONArray goals,JSONObject constraints) {
        if(goals==null||constraints==null) fail("goals and constraints required");
        allowed(constraints,"visit_caps","pinned_actions");
        for(int i=0;i<goals.length();i++) { JSONObject g=object(goals.opt(i)); allowed(g,"field","target","priority"); id(g.opt("field")); integer(g.opt("target"),null,false); if(g.has("priority")) integer(g.opt("priority"),null,false); }
        JSONObject caps=constraints.has("visit_caps")?object(constraints.opt("visit_caps")):new JSONObject();
        for(String k:keys(caps)) { id(k); integer(caps.opt(k),0L,false); }
        JSONObject pins=constraints.has("pinned_actions")?object(constraints.opt("pinned_actions")):new JSONObject();
        for(String k:keys(pins)) { if(!k.matches("[0-9]+")) fail("pinned slot must be nonnegative integer"); try { Long.parseLong(k); } catch(NumberFormatException e) { fail("slot overflow"); } id(pins.opt(k)); }
        JSONObject p=new JSONObject(); put(p,"goals",goals); put(p,"constraints",constraints); return copy(p);
    }
    public synchronized JSONObject setPreferences(String runId,String command,long expected,JSONArray goals,JSONObject constraints) {
        id(command); JSONObject p=preferencesPayload(goals,constraints);
        db.beginTransaction(); try {
            JSONObject prior=existing(runId,command,"preferences",p); if(prior!=null) { db.setTransactionSuccessful(); return prior; }
            JSONObject row=run(runId); revision(row,expected); JSONObject s=parse(row.optString("snapshot")); put(s,"revision",add(row.optLong("revision"),1));
            db.execSQL("INSERT OR REPLACE INTO preferences VALUES(?,?)",new Object[]{runId,canonical(p)}); event(runId,command,row.optLong("epoch"),"preferences",p); save(runId,s);
            db.setTransactionSuccessful(); return receipt(s,false,new JSONArray());
        } finally { db.endTransaction(); }
    }
    /** Atomically replaces checkpoint and planning preferences under one revision.
     * Existing run required. Retrying the same setup command after restart is safe.
     */
    public synchronized JSONObject reenterWithPreferences(String runId,String command,long expected,
            JSONObject input,JSONArray goals,JSONObject constraints) {
        id(command); JSONObject b=baseline(input), prefs=preferencesPayload(goals,constraints);
        JSONObject payload=new JSONObject(); put(payload,"baseline",b); put(payload,"preferences",prefs);
        db.beginTransaction(); try {
            JSONObject prior=existing(runId,command,"setup",payload);
            if(prior!=null) { db.setTransactionSuccessful(); return prior; }
            checkObservedStage(b);
            JSONObject row=run(runId); revision(row,expected); long epoch=add(row.optLong("epoch"),1);
            JSONObject snapshot=state(b,runId,add(row.optLong("revision"),1),epoch);
            db.execSQL("INSERT INTO checkpoints VALUES(?,?,?)",new Object[]{runId,epoch,canonical(b)});
            db.execSQL("INSERT OR REPLACE INTO preferences VALUES(?,?)",new Object[]{runId,canonical(prefs)});
            event(runId,command,epoch,"setup",payload); save(runId,snapshot);
            db.setTransactionSuccessful(); return receipt(snapshot,false,new JSONArray());
        } finally { db.endTransaction(); }
    }
    public synchronized JSONObject getPreferences(String runId) { JSONObject r=first("SELECT payload FROM preferences WHERE run_id=?",runId); return r==null?null:parse(r.optString("payload")); }
    public synchronized long installPlan(String runId,long expected,String rulesVersion,JSONObject payload) {
        id(rulesVersion); JSONObject detached=copy(payload); db.beginTransaction(); try {
            JSONObject r=run(runId); revision(r,expected); if(!rulesVersion.equals(parse(r.optString("snapshot")).optString("rules_version"))) throw new StateConflict("plan ruleset stale");
            long rev=add(r.optLong("plan_revision"),1); db.execSQL("UPDATE runs SET plan_revision=? WHERE run_id=?",new Object[]{rev,runId});
            db.execSQL("INSERT OR REPLACE INTO plans VALUES(?,?,?,?,?)",new Object[]{runId,rev,expected,rulesVersion,canonical(detached)}); db.setTransactionSuccessful(); return rev;
        } finally { db.endTransaction(); }
    }
    public synchronized JSONObject getPlan(String runId) { JSONObject r=first("SELECT * FROM plans WHERE run_id=?",runId); if(r!=null) put(r,"payload",parse(r.optString("payload"))); return r; }
    public synchronized JSONObject exportRun(String runId) {
        JSONObject out=new JSONObject(), r=run(runId); put(r,"snapshot",parse(r.optString("snapshot"))); put(out,"format","p3r_planner_v2"); put(out,"schema_version",1); put(out,"run",r);
        for(String table:new String[]{"checkpoints","events"}) { JSONArray a=new JSONArray(); for(JSONObject e:rows("SELECT * FROM "+table+" WHERE run_id=? ORDER BY "+(table.equals("events")?"seq":"epoch"),runId)) { put(e,"payload",parse(e.optString("payload"))); a.put(e); } put(out,table,a); }
        put(out,"plan",getPlan(runId)); put(out,"preferences",getPreferences(runId)); return out;
    }
    @Override public synchronized void close() { helper.close(); }
}
