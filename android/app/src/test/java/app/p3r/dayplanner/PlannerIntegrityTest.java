package app.p3r.dayplanner;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

/** Real packaged assets and Android SharedPreferences; never touches a user's installed app. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class PlannerIntegrityTest {
    private Context context;
    @Before public void cleanTestState() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("p3r_native_progress_v1", Context.MODE_PRIVATE).edit().clear().commit();
    }
    private JSONObject asset(String name) throws Exception {
        try (InputStream in = context.getAssets().open(name)) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private List<JSONObject> records(JSONObject data) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        JSONArray days = data.getJSONArray("blocks");
        for(int n=0;n<days.length();n++) {
            JSONArray items=days.getJSONObject(n).getJSONArray("items");
            for(int j=0;j<items.length();j++) result.add(items.getJSONObject(j));
        }
        return result;
    }
    private String sha(String value) throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder(); for(byte b:hash) result.append(String.format("%02x",b));
        return result.toString();
    }
    private boolean sameJson(JSONObject a, JSONObject b) throws Exception {
        if(a.length()!=b.length()) return false;
        java.util.Iterator<String> keys=a.keys();
        while(keys.hasNext()) {
            String key=keys.next(); if(!b.has(key)) return false;
            Object x=a.get(key), y=b.get(key);
            if(x instanceof JSONObject && y instanceof JSONObject) {
                if(!sameJson((JSONObject)x,(JSONObject)y)) return false;
            } else if(!x.equals(y)) return false;
        }
        return true;
    }
    @Test public void allOriginalIdsAndTheirChronologicalOrderArePreserved() throws Exception {
        JSONObject data=asset("data.json");
        assertEquals(284,data.getJSONArray("blocks").length());
        List<JSONObject> tasks=records(data); assertEquals(691,tasks.size());
        List<String> ids=new ArrayList<>(); Set<String> unique=new HashSet<>(); int checkable=0;
        for(JSONObject task:tasks) {
            String id=task.getString("id"); ids.add(id); assertTrue("Duplicate ID "+id,unique.add(id));
            if(!task.optString("kind").equals("free")&&!task.optString("kind").equals("auto")) checkable++;
        }
        assertEquals(623,checkable);
        assertEquals("1ac4393f94e230ef6538988e9971c4ba4b80a4219bdf93a8f0d440bff494a638",sha(String.join("\n",ids)));
    }
    @Test public void indexedSubstepContentAndOrderRemainCompatible() throws Exception {
        List<String> indexed=new ArrayList<>(); int parents=0;
        for(JSONObject task:records(asset("data.json"))) {
            JSONArray sub=task.optJSONArray("substeps"); if(sub==null||sub.length()==0) continue;
            parents++;
            for(int n=0;n<sub.length();n++) indexed.add(task.getString("id")+"::"+n+"::"+sub.getString(n));
        }
        assertEquals(3,parents); assertEquals(20,indexed.size());
        assertEquals("f7f9c1ac0432a5e388a09e1266417ce3b8bf75718a83191d9ccc95de13ec0a49",sha(String.join("\n",indexed)));
    }
    @Test public void displayMetadataCoversEveryOriginalRecordExactlyOnce() throws Exception {
        JSONObject metadata=asset("display_metadata.json"); List<JSONObject> tasks=records(asset("data.json"));
        assertEquals(691,metadata.length());
        for(JSONObject task:tasks) {
            String id=task.getString("id"); assertTrue("Missing metadata "+id,metadata.has(id));
            JSONObject display=metadata.getJSONObject(id);
            assertFalse("Empty title "+id,display.getString("widgetTitle").trim().isEmpty());
            assertTrue("Location field missing "+id,display.has("locationLabel"));
            assertTrue("No app bullets "+id,display.getJSONArray("appBullets").length()>0);
            assertNotNull(display.getJSONArray("extraNotes")); assertTrue(display.has("hasDetails"));
        }
    }
    @Test public void originalPreferenceKeysAndIndexedProgressRoundTripWithoutLoss() throws Exception {
        String date=PlannerStore.days(context).get(4).date;
        JSONObject completed=new JSONObject().put("t001_01",true).put("t005_03",false);
        JSONObject substeps=new JSONObject().put("t005_03::0",true).put("t005_03::13",true)
                .put("t024_03::1",true).put("t088_01::3",false);
        JSONObject state=new JSONObject().put("completed",completed).put("substeps",substeps)
                .put("selectedDate",date).put("theme","night");
        PlannerStore.acceptWebState(context,state.toString());
        JSONObject saved=new JSONObject(PlannerStore.snapshot(context));
        assertTrue(sameJson(completed,saved.getJSONObject("completed")));
        assertTrue(sameJson(substeps,saved.getJSONObject("substeps")));
        assertEquals(date,saved.getString("selectedDate")); assertEquals("night",saved.getString("theme"));
        assertTrue(context.getSharedPreferences("p3r_native_progress_v1",0).contains("completed"));
        PlannerStore.markCompleted(context,"t002_01");
        JSONObject after=new JSONObject(PlannerStore.snapshot(context));
        assertTrue(after.getJSONObject("completed").getBoolean("t001_01"));
        assertTrue(after.getJSONObject("completed").getBoolean("t002_01"));
        assertTrue(sameJson(substeps,after.getJSONObject("substeps")));
    }
    @Test public void malformedImportAndInvalidDateDoNotDestroyProgress() throws Exception {
        PlannerStore.markCompleted(context,"t001_01"); String before=PlannerStore.snapshot(context);
        PlannerStore.acceptWebState(context,"{broken");
        PlannerStore.acceptWebState(context,"{\"completed\":{}}");
        PlannerStore.selectDate(context,"not a valid game date");
        assertTrue(sameJson(new JSONObject(before),new JSONObject(PlannerStore.snapshot(context))));
    }
}
