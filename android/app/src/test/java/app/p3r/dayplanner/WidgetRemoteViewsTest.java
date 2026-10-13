package app.p3r.dayplanner;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,28,35},qualifiers="en-rUS-w360dp-h800dp-xhdpi")
public class WidgetRemoteViewsTest {
    private Context c;private static final int W=73;
    @Before public void clean(){c=RuntimeEnvironment.getApplication();c.getSharedPreferences("p3r_native_progress_v1",0).edit().clear().commit();}
    private View render(String id,int width,int height){
        PlannerStore.setWidgetCursor(c,W,id);
        View root=PlannerWidget.renderForTest(c,W,width,height).apply(c,new FrameLayout(c));
        root.measure(View.MeasureSpec.makeMeasureSpec(width*2,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height*2,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width*2,height*2);return root;
    }
    @Test public void allSupportedLayoutsInflateWithRealRemoteViewsAndCorrectMetadata(){
        String[] tasks={"t005_01","t005_04","t016_03"};int[][] sizes={{200,160},{250,200},{320,180},{320,240}};
        for(String id:tasks)for(int[] size:sizes){
            PlannerStore.Task task=PlannerStore.taskById(c,id);View root=render(id,size[0],size[1]);
            assertEquals(task.widgetTitle,((TextView)root.findViewById(R.id.widget_task)).getText().toString());
            assertEquals(task.locationLabel.isEmpty()?"":"⌖ "+task.locationLabel,((TextView)root.findViewById(R.id.widget_location)).getText().toString());
            int ordinal=PlannerStore.viewedOrdinal(c,W),count=PlannerStore.viewedDay(c,W).tasks.size();
            assertEquals(ordinal+"/"+count,((TextView)root.findViewById(R.id.widget_progress)).getText().toString());
            String dots=((TextView)root.findViewById(R.id.widget_dots)).getText().toString().replace(" ","");
            assertEquals(count,dots.length()); assertEquals('◎',dots.charAt(ordinal-1));
            assertEquals(1,dots.chars().filter(ch->ch=='◎').count());
            assertEquals(0,dots.chars().filter(ch->ch=='●'||ch=='◉').count());
            assertEquals(PlannerWidget.isNightPeriod(task.period)?"夜":"昼",((TextView)root.findViewById(R.id.widget_period_word)).getText().toString());
            assertEquals("Current",((TextView)root.findViewById(R.id.button_spec_current_text)).getText().toString());
            assertEquals("Done",((TextView)root.findViewById(R.id.button_spec_done_text)).getText().toString());
            assertTrue(root.findViewById(R.id.button_spec_current).hasOnClickListeners());
            assertTrue(root.findViewById(R.id.button_spec_done).hasOnClickListeners());
        }
    }
    @Test public void completedTasksAllowUndoWhileInformationalTasksDisableDone(){
        PlannerStore.Task action=PlannerStore.taskById(c,"t005_04");PlannerStore.markCompleted(c,action.id);
        View root=render(action.id,250,200);assertTrue("Completed task can be explicitly undone",root.findViewById(R.id.button_spec_done).isEnabled());
        assertEquals(action.id,PlannerStore.viewedTask(c,W).id);
        PlannerStore.Task info=null;for(PlannerStore.Day day:PlannerStore.days(c))for(PlannerStore.Task task:day.tasks)if(!task.isCheckable()){info=task;break;}
        assertNotNull(info);root=render(info.id,250,200);assertFalse(root.findViewById(R.id.button_spec_done).isEnabled());
        assertTrue(root.findViewById(R.id.button_spec_current).isEnabled());
    }
    @Test @Config(sdk=28) public void dotsPreserveSkippedMiddleAndImportedActualCompletion()throws Exception {
        PlannerStore.Day selected=null;
        for(PlannerStore.Day day:PlannerStore.days(c))if(day.tasks.size()>=3&&day.tasks.get(0).isCheckable()&&day.tasks.get(1).isCheckable()&&day.tasks.get(2).isCheckable()){selected=day;break;}
        assertNotNull(selected);
        PlannerStore.markCompleted(c,selected.tasks.get(0).id);
        PlannerStore.markCompleted(c,selected.tasks.get(2).id);
        View root=render(selected.tasks.get(1).id,250,200);
        String dots=((TextView)root.findViewById(R.id.widget_dots)).getText().toString().replace(" ","");
        assertEquals("●◎●",dots.substring(0,3));
        String saved=PlannerStore.snapshot(c);
        c.getSharedPreferences("p3r_native_progress_v1",0).edit().clear().commit();
        PlannerStore.acceptWebState(c,saved);
        root=render(selected.tasks.get(2).id,250,200);
        dots=((TextView)root.findViewById(R.id.widget_dots)).getText().toString().replace(" ","");
        assertEquals("●○◉",dots.substring(0,3));
        assertEquals("3/"+selected.tasks.size(),((TextView)root.findViewById(R.id.widget_progress)).getText().toString());
        assertFalse(PlannerStore.isDone(PlannerStore.completed(c),selected.tasks.get(1)));
    }
}
