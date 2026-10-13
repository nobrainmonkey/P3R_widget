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
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class PlannerNavigationTest {
    private Context c;
    private static final int WIDGET=47;
    @Before public void clean() {
        c=RuntimeEnvironment.getApplication();
        c.getSharedPreferences("p3r_native_progress_v1",0).edit().clear().commit();
    }
    private List<PlannerStore.Task> all() {
        List<PlannerStore.Task> tasks=new ArrayList<>();
        for(PlannerStore.Day day:PlannerStore.days(c)) tasks.addAll(day.tasks);
        return tasks;
    }
    private void view(PlannerStore.Task task) {
        PlannerStore.selectDate(c,PlannerStore.dayForTask(c,task.id).date);
        PlannerStore.setWidgetCursor(c,WIDGET,task.id);
        assertEquals("Test setup must select the requested viewed record",task.id,PlannerStore.viewedTask(c,WIDGET).id);
    }
    private void importState(JSONObject done,JSONObject subs) throws Exception {
        PlannerStore.acceptWebState(c,new JSONObject().put("completed",done).put("substeps",subs)
                .put("selectedDate",PlannerStore.days(c).get(0).date).put("theme","night").toString());
    }
    @Test public void browsingIncludesEveryRecordAndCompletedTasksWithoutChangingProgress() throws Exception {
        List<PlannerStore.Task> tasks=all(); JSONObject done=new JSONObject();
        for(PlannerStore.Task task:tasks) if(task.isCheckable()) done.put(task.id,true);
        importState(done,new JSONObject()); String saved=PlannerStore.completed(c).toString();
        view(tasks.get(0));
        for(int n=1;n<tasks.size();n++) {
            assertEquals("Forward browse record "+n,tasks.get(n).id,PlannerStore.browseViewed(c,WIDGET,1).id);
        }
        for(int n=tasks.size()-2;n>=0;n--) {
            assertEquals("Backward browse record "+n,tasks.get(n).id,PlannerStore.browseViewed(c,WIDGET,-1).id);
        }
        assertEquals(saved,PlannerStore.completed(c).toString());
    }
    @Test public void headerBrowseCrossesGameDateAndOrdinalTracksViewedPosition() {
        PlannerStore.Day first=PlannerStore.days(c).get(0), second=PlannerStore.days(c).get(1);
        view(first.tasks.get(first.tasks.size()-1));
        assertEquals(second.tasks.get(0).id,PlannerStore.browseViewed(c,WIDGET,1).id);
        assertEquals(second.date,PlannerStore.viewedDay(c,WIDGET).date);
        assertEquals(1,PlannerStore.viewedOrdinal(c,WIDGET));
        assertEquals(first.tasks.get(first.tasks.size()-1).id,PlannerStore.browseViewed(c,WIDGET,-1).id);
    }
    @Test public void currentReturnsEarliestPendingCheckableRatherThanViewedSuccessor() {
        List<PlannerStore.Task> tasks=all(); int completed=0; PlannerStore.Task expected=null;
        for(PlannerStore.Task task:tasks) if(task.isCheckable()) {
            if(completed++<3) PlannerStore.markCompleted(c,task.id); else {expected=task;break;}
        }
        view(tasks.get(tasks.size()-1)); String done=PlannerStore.completed(c).toString();
        assertNotNull(expected); assertEquals(expected.id,PlannerStore.jumpCurrent(c,WIDGET).id);
        assertEquals(expected.id,PlannerStore.viewedTask(c,WIDGET).id);
        assertEquals(done,PlannerStore.completed(c).toString());
    }
    @Test public void doneMarksViewedAndAdvancesAcrossDatesSkippingCompletedWithoutInterstitial() {
        List<PlannerStore.Task> tasks=all(); List<PlannerStore.Task> actions=new ArrayList<>();
        for(PlannerStore.Task task:tasks) if(task.isCheckable()) actions.add(task);
        PlannerStore.markCompleted(c,actions.get(1).id); view(actions.get(0));
        PlannerStore.Task result=PlannerStore.completeViewed(c,WIDGET,actions.get(0).id);
        assertNotNull(result); assertEquals(actions.get(2).id,result.id);
        assertTrue(PlannerStore.isDone(PlannerStore.completed(c),actions.get(0)));
        assertTrue(PlannerStore.isDone(PlannerStore.completed(c),actions.get(1)));
        assertFalse(PlannerStore.isDone(PlannerStore.completed(c),actions.get(2)));
        assertEquals(PlannerStore.dayForTask(c,result.id).date,PlannerStore.viewedDay(c,WIDGET).date);
    }
    @Test public void staleDoneIntentCannotCompleteDifferentViewedTask() {
        List<PlannerStore.Task> tasks=all(); view(tasks.get(0));
        PlannerStore.completeViewed(c,WIDGET,tasks.get(0).id);
        String cursor=PlannerStore.viewedTask(c,WIDGET).id, done=PlannerStore.completed(c).toString();
        PlannerStore.completeViewed(c,WIDGET,tasks.get(0).id);
        assertEquals(cursor,PlannerStore.viewedTask(c,WIDGET).id);
        assertEquals(done,PlannerStore.completed(c).toString());
    }
    @Test public void completingComplexTaskRetainsIndexedPartialSubstepRecords() throws Exception {
        JSONObject subs=new JSONObject().put("t005_03::0",true).put("t005_03::13",false).put("t024_03::1",true);
        importState(new JSONObject(),subs); PlannerStore.Task task=PlannerStore.taskById(c,"t005_03");view(task);
        PlannerStore.completeViewed(c,WIDGET,task.id);
        assertTrue(PlannerStore.isDone(PlannerStore.completed(c),task));
        JSONObject saved=new JSONObject(PlannerStore.snapshot(c)).getJSONObject("substeps");
        assertEquals(3,saved.length());assertTrue(saved.getBoolean("t005_03::0"));
        assertFalse(saved.getBoolean("t005_03::13"));assertTrue(saved.getBoolean("t024_03::1"));
    }
    @Test public void autoAndFreeRecordsNeverBecomeCompletedThroughDone() {
        for(PlannerStore.Task task:all()) if(!task.isCheckable()) {
            view(task); PlannerStore.completeViewed(c,WIDGET,task.id);
            assertFalse("Non-action completed "+task.id,PlannerStore.completed(c).has(task.id));
        }
    }
    @Test public void ordinalIsNotCompletedCountAndIncludesAllRowsOfViewedDate() {
        PlannerStore.Day day=null;for(PlannerStore.Day candidate:PlannerStore.days(c)) if(candidate.tasks.size()>=8) {day=candidate;break;}
        assertNotNull(day); view(day.tasks.get(2));assertEquals(3,PlannerStore.viewedOrdinal(c,WIDGET));
        for(PlannerStore.Task task:day.tasks) if(task.isCheckable()) PlannerStore.markCompleted(c,task.id);
        assertEquals(day.tasks.get(2).id,PlannerStore.viewedTask(c,WIDGET).id);
        assertEquals(3,PlannerStore.viewedOrdinal(c,WIDGET));
    }
    @Test public void completingFinalPendingTaskKeepsARealViewedRecord() throws Exception {
        List<PlannerStore.Task> tasks=all(); JSONObject done=new JSONObject();PlannerStore.Task last=null;
        for(PlannerStore.Task task:tasks) if(task.isCheckable()) {done.put(task.id,true);last=task;}
        assertNotNull(last);done.remove(last.id);importState(done,new JSONObject());view(last);
        PlannerStore.Task result=PlannerStore.completeViewed(c,WIDGET,last.id);
        assertNotNull("No blank/interstitial view after final completion",result);
        assertTrue(PlannerStore.isDone(PlannerStore.completed(c),last));assertNull(PlannerStore.firstPendingGlobally(c));
        assertEquals(result.id,PlannerStore.viewedTask(c,WIDGET).id);
    }
    @Test public void browseClampsAtGlobalEnds() {
        List<PlannerStore.Task> tasks=all();view(tasks.get(0));
        assertEquals(tasks.get(0).id,PlannerStore.browseViewed(c,WIDGET,-1).id);
        view(tasks.get(tasks.size()-1));
        assertEquals(tasks.get(tasks.size()-1).id,PlannerStore.browseViewed(c,WIDGET,1).id);
    }
    @Test public void doneAtTheEndWrapsToEarlierPendingAndCurrentDoesNotChangeCompletion() {
        List<PlannerStore.Task> tasks=all();PlannerStore.Task last=null;
        for(PlannerStore.Task task:tasks) if(task.isCheckable()) last=task;
        assertNotNull(last);view(last);
        PlannerStore.Task next=PlannerStore.completeViewed(c,WIDGET,last.id);
        assertEquals(PlannerStore.firstPendingGlobally(c).id,next.id);
        String progress=PlannerStore.completed(c).toString();
        assertEquals(next.id,PlannerStore.jumpCurrent(c,WIDGET).id);
        assertEquals(progress,PlannerStore.completed(c).toString());
    }
    @Test public void runtimeDisplayFieldsExactlyMatchTheUnifiedMetadata() throws Exception {
        JSONObject metadata;
        try(InputStream in=c.getAssets().open("display_metadata.json")) {metadata=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        for(PlannerStore.Task task:all()) {
            JSONObject display=metadata.getJSONObject(task.id);
            assertEquals(task.id,display.getString("widgetTitle"),task.widgetTitle);
            assertEquals(task.id,display.getString("locationLabel"),task.locationLabel);
            assertEquals(task.id,display.getBoolean("hasDetails"),task.hasDetails);
            assertEquals(task.id,display.getString("dialogueCue"),task.dialogueCue);
            JSONArray choices=display.getJSONArray("dialogueChoices");
            assertEquals(task.id,choices.length(),task.dialogueChoices.size());
            for(int n=0;n<choices.length();n++)assertEquals(task.id+" choice "+n,choices.getString(n),task.dialogueChoices.get(n));
        }
    }
}
