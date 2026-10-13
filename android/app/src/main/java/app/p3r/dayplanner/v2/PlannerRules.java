package app.p3r.dayplanner.v2;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Narrow audited adapter. Missing calendar coverage never establishes impossibility. */
public final class PlannerRules {
    public final JSONObject rules, labels=new JSONObject(), stageCeilings=new JSONObject(), stageWindows=new JSONObject();
    public final JSONArray catalog,statRules;
    public final int firstSlot;
    public final String version;
    public final String coverage="当前可执行规则：1 月 2–30 日已解锁且关系正常的太阳／恶魔。日程与剧情覆盖尚不完整，结果仅为有条件候选；其他社群不会被误判为无法完成。";
    private final Context context;
    private static final String ROOT="planner_v2/rules/";
    public PlannerRules(Context context) throws Exception {
        this.context=context;
        String manifestText=read("manifest.json");JSONObject manifest=new JSONObject(manifestText);
        if(!"P3R-base".equals(manifest.getString("gameEdition")))throw new IllegalArgumentException("Wrong game edition");
        JSONObject files=manifest.getJSONObject("files");
        for(Iterator<String> it=files.keys();it.hasNext();){String name=it.next();
            if(!name.matches("[a-zA-Z0-9_.-]+\\.json")||!sha(readBytes(name)).equals(files.getString(name)))throw new IllegalArgumentException("Rule checksum mismatch: "+name);
        }
        catalog=new JSONObject(read("social_links.json")).getJSONArray("entities");statRules=new JSONObject(read("stats_actions.json")).getJSONArray("statRules");
        version="p3r-january-"+manifest.getString("packVersion")+"-"+sha(manifestText.getBytes(StandardCharsets.UTF_8)).substring(0,12)+"-adapter2";
        JSONObject calendar=new JSONObject(read("calendar_january_slice.json")),definitions=new JSONObject(read("playable_actions.json")),tower=new JSONObject(read("tower_stages.json"));
        firstSlot=calendar.getJSONObject("window").getJSONObject("startInclusive").getInt("ordinal")*2;
        int horizon=(calendar.getJSONObject("window").getJSONObject("endInclusive").getInt("ordinal")+1)*2;
        JSONArray cells=calendar.getJSONArray("cells"),stages=new JSONArray(),actions=new JSONArray(),fields=new JSONArray().put("cash");
        Map<String,Integer> dates=new HashMap<>();
        for(int i=0;i<cells.length();i++){JSONObject day=cells.getJSONObject(i).getJSONObject("date");int ordinal=day.getInt("ordinal");String date=day.getString("mmdd");dates.put(date,ordinal);
            labels.put(""+(ordinal*2),new JSONObject().put("date",date).put("period","daytime"));
            labels.put(""+(ordinal*2+1),new JSONObject().put("date",date).put("period","evening"));}
        JSONArray rawStages=tower.getJSONArray("stages");
        for(int i=0;i<rawStages.length();i++){JSONObject s=rawStages.getJSONObject(i);if(!s.isNull("floorCeiling")&&!"incomplete".equals(s.optString("verificationStatus")))stageCeilings.put(s.getString("id"),s.getInt("floorCeiling"));if("p3r.tower.stage_04".equals(s.optString("id"))||!"corroborated".equals(s.optString("verificationStatus"))||s.isNull("startInclusive")||s.isNull("endExclusive"))continue; // July9–17 evidence re-opened; manual stage until corrected pack.
            int start=s.getJSONObject("startInclusive").getInt("ordinal")*2,end=s.getJSONObject("endExclusive").getInt("ordinal")*2;
            String id=s.getString("id");int ceiling=s.getInt("floorCeiling");
            stages.put(new JSONObject().put("id",id).put("start",start).put("end",end).put("floor_ceiling",ceiling));stageCeilings.put(id,ceiling);stageWindows.put(id,new JSONArray().put(start).put(end));}
        JSONArray defs=definitions.getJSONArray("actions");
        for(int i=0;i<defs.length();i++){JSONObject d=defs.getJSONObject(i);String id=d.getString("ruleId");JSONArray slots=new JSONArray();
            JSONObject a=new JSONObject().put("id",id).put("slots",slots).put("verification","corroborated").put("source",d.getJSONArray("evidenceRefs").toString());
            if(id.equals("p3r.action.visit_sun")||id.equals("p3r.action.visit_devil")){
                String link=id.endsWith("sun")?"sun":"devil",rank="socialLinks."+link+".rank",normal="socialLinks."+link+".normal";fields.put(rank).put(normal);
                JSONObject effect=d.getJSONObject("onCompletedNormalVisit");if(effect.length()!=2||effect.getInt("rankDelta")!=1||effect.getInt("rankCap")!=10||d.getInt("slotCost")!=1)throw new IllegalArgumentException("Unmapped meeting semantics");
                for(int j=0;j<cells.length();j++){JSONObject cell=cells.getJSONObject(j);if(cell.getString("entityId").equals(d.getString("entityId"))&&cell.optBoolean("available")&&"corroborated".equals(cell.optString("verificationStatus")))slots.put(cell.getJSONObject("date").getInt("ordinal")*2+("evening".equals(cell.getString("slot"))?1:0));}
                a.put("requirements",new JSONObject().put(rank,1).put(normal,1));a.put("maximums",new JSONObject().put(rank,9).put(normal,1));a.put("effects",new JSONObject().put(rank,1));
            }else if(id.equals("p3r.action.explore_stage_10")){
                JSONArray allowed=d.getJSONArray("allowedDateSubset");for(int j=0;j<allowed.length();j++){Integer day=dates.get(allowed.getString(j));if(day==null)throw new IllegalArgumentException("Missing date");slots.put(day*2+1);}a.put("dungeon_visit",true);
            }else throw new IllegalArgumentException("Unmapped action: "+id);
            actions.put(a);
        }
        rules=new JSONObject().put("version",version).put("horizon",horizon).put("fields",fields).put("actions",actions).put("stages",stages).put("coverage_complete",false).put("synthetic",false);
    }
    public JSONObject preflight(JSONObject state,JSONArray goals) throws JSONException {
        if(!version.equals(state.getString("rules_version")))return issue("unsupported","规则包已经更新，请确认当前实际状态后建立新基线。");
        int slot=state.getInt("game_slot");if(slot<firstSlot||slot>rules.getInt("horizon"))return issue("unsupported","当前可执行窗口为 1 月 2–30 日。");
        JSONObject facts=state.getJSONObject("facts");
        for(int i=0;i<goals.length();i++){JSONObject g=goals.getJSONObject(i);String field=g.getString("field");
            if(!(field.equals("socialLinks.sun.rank")||field.equals("socialLinks.devil.rank")))return issue("unsupported","该目标暂缺完整可执行规则；这不表示无法完成。");
            int target=g.getInt("target");if(target<1||target>10)return issue("unsupported","目标等级须在 1–10 之间。");
            JSONObject rank=facts.optJSONObject(field),normal=facts.optJSONObject(field.replace(".rank",".normal"));
            if(rank==null||rank.isNull("low")||rank.isNull("high")||rank.getInt("low")!=rank.getInt("high")||normal==null||normal.isNull("low")||normal.isNull("high")||normal.getInt("low")!=1||normal.getInt("high")!=1)return issue("unknown","请确认可见等级和当前关系状态；无需回忆隐藏好感或过去操作。");
            if(rank.getInt("low")<1||rank.getInt("low")>10)return issue("unsupported","当前规则需要目标社群已经解锁。");
        }return null;
    }
    private JSONObject issue(String status,String text)throws JSONException{return new JSONObject().put("status",status).put("explanations",new JSONArray().put(text)).put("steps",new JSONArray());}
    private byte[] readBytes(String name)throws IOException{try(InputStream in=context.getAssets().open(ROOT+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private String read(String name)throws IOException{return new String(readBytes(name),StandardCharsets.UTF_8);}
    private static String sha(byte[] bytes)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
