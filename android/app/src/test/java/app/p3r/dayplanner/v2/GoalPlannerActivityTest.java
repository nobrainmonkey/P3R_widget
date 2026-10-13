package app.p3r.dayplanner.v2;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="en-rUS-w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GoalPlannerActivityTest {
 private ActivityController<GoalPlannerActivity> controller;private GoalPlannerActivity activity;private Context context;
 private final File output=new File("/workspace/shared/planner_v2/qa/native");
 @Before public void setup()throws Exception {
  context=RuntimeEnvironment.getApplication();context.deleteDatabase("p3r_goal_planner_v2.db");
  context.getSharedPreferences("p3r_goal_planner_commands_v2",0).edit().clear().commit();
  context.getSharedPreferences("p3r_native_progress_v1",0).edit().clear().putString("completed","{\"t005_03\":true}").putString("substeps","{\"t005_03::0\":true}").commit();
  controller=Robolectric.buildActivity(GoalPlannerActivity.class).setup();activity=controller.get();idle();output.mkdirs();
 }
 @After public void teardown()throws Exception{if(controller!=null){controller.pause().stop().destroy();((ExecutorService)field("worker")).awaitTermination(10,TimeUnit.SECONDS);}}
 private Object field(String name)throws Exception {Field f=GoalPlannerActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
 private JSONObject invoke(String name)throws Exception {Method m=GoalPlannerActivity.class.getDeclaredMethod(name);m.setAccessible(true);try{return (JSONObject)m.invoke(activity);}catch(InvocationTargetException e){throw (Exception)e.getCause();}}
 private void edit(String name,String value)throws Exception{((EditText)field(name)).setText(value);}
 private void select(String name,int index)throws Exception{((Spinner)field(name)).setSelection(index);}
 private void idle()throws Exception {
  ExecutorService executor=(ExecutorService)field("worker");
  for(int n=0;n<20;n++){executor.submit(()->{}).get(20,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();if(!(Boolean)field("busy"))return;}
  fail("Activity did not finish its queued operation");
 }
 private Button button(View node,String label){if(node instanceof Button&&((Button)node).getText().toString().equals(label))return (Button)node;if(node instanceof ViewGroup)for(int i=0;i<((ViewGroup)node).getChildCount();i++){Button found=button(((ViewGroup)node).getChildAt(i),label);if(found!=null)return found;}return null;}
 private void tap(String label)throws Exception{Button b=button((View)field("root"),label);assertNotNull(label,b);assertTrue(label+" disabled",b.isEnabled());b.performClick();}
 private PlannerCoordinator app()throws Exception{return (PlannerCoordinator)field("coordinator");}
 private JSONObject snapshot()throws Exception{return app().state().getJSONObject("snapshot");}
 private void baseline()throws Exception {
  edit("cash","1000");edit("floor","200");edit("visits","0");edit("sunRank","9");edit("devilRank","8");select("sunNormal",1);select("devilNormal",1);select("period",1);
  tap("确认当前状态");ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertFalse(app().state().isNull("snapshot"));
 }
 private void returned(String amount,int mode,String floor,String date)throws Exception {edit("returnCash",amount);select("cashMode",mode);edit("returnFloor",floor);edit("exitDay",date);select("exitPeriod",0);tap("保存爬塔结果并重新规划");idle();}
 @Test public void unknownInputsRemainUnknownAndCoverageIsExplicit()throws Exception {
  JSONObject b=invoke("baseline").getJSONObject("baseline");assertTrue(b.isNull("cash"));assertTrue(b.isNull("highest_floor"));assertTrue(b.getJSONObject("visits_by_stage").isNull("p3r.tower.stage_10"));assertTrue(b.getJSONObject("facts").getJSONObject("socialLinks.sun.rank").isNull("low"));
  String text=allText((View)field("root"));assertTrue(text.contains("当前仅开放"));assertTrue(text.contains("覆盖不足"));assertFalse(text.contains("保证MAX"));
 }
 private String allText(View v){StringBuilder out=new StringBuilder();if(v instanceof TextView)out.append(((TextView)v).getText());if(v instanceof ViewGroup)for(int n=0;n<((ViewGroup)v).getChildCount();n++)out.append(allText(((ViewGroup)v).getChildAt(n)));return out.toString();}
 @Test public void nativeGoalsProduceConditionalPlanWithoutChangingActualsOrLegacyProgress()throws Exception {
  baseline();String before=snapshot().getJSONObject("facts").toString();tap("保存目标并重新规划");idle();
  assertTrue(((TextView)field("planText")).getText().toString().contains("有条件候选"));assertEquals(before,snapshot().getJSONObject("facts").toString());assertEquals(1000,snapshot().getInt("cash"));
  assertEquals("{\"t005_03\":true}",context.getSharedPreferences("p3r_native_progress_v1",0).getString("completed",""));
 }
 @Test public void negativeNetReturnRetriesOnceAndCorrectionDoesNotAddVisit()throws Exception {
  baseline();returned("-200",0,"210","3");assertEquals(800,snapshot().getInt("cash"));assertEquals(1,snapshot().getJSONObject("visits_by_stage").getInt("p3r.tower.stage_10"));
  SharedPreferences receipts=context.getSharedPreferences("p3r_goal_planner_commands_v2",0);JSONObject prior=new JSONObject(receipts.getString("last_return","{}"));
  receipts.edit().putString("pending",new JSONObject().put("path","/api/return").put("body",prior.getJSONObject("body")).toString()).commit();tap("重试尚未确认的提交");idle();assertEquals(800,snapshot().getInt("cash"));assertEquals(1,snapshot().getJSONObject("visits_by_stage").getInt("p3r.tower.stage_10"));
  ((CheckBox)field("correctLast")).setChecked(true);returned("950",1,"205","4");assertEquals(950,snapshot().getInt("cash"));assertEquals(1,snapshot().getJSONObject("visits_by_stage").getInt("p3r.tower.stage_10"));assertEquals(205,snapshot().getInt("highest_floor"));
 }
 @Test public void absoluteBalanceCanResolveUnknownCashAndReentryClearsOldCorrection()throws Exception {
  tap("确认当前状态");ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertTrue(snapshot().isNull("cash"));assertEquals(1,((Spinner)field("cashMode")).getSelectedItemPosition());
  returned("700",1,"200","3");assertEquals(700,snapshot().getInt("cash"));
  edit("day","10");edit("cash","500");tap("确认当前状态");ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(500,snapshot().getInt("cash"));assertFalse(context.getSharedPreferences("p3r_goal_planner_commands_v2",0).contains("last_return"));
  ((CheckBox)field("correctLast")).setChecked(true);try{invoke("returned");fail("Old correction accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("没有可修正"));}
 }
 @Test public void invalidRankCannotBecomeAnActualBaseline()throws Exception {
  edit("sunRank","11");tap("确认当前状态");ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertTrue("Out-of-range rank must not be saved as an actual fact",app().state().isNull("snapshot"));assertFalse("Validation failure must not strand a pending command",context.getSharedPreferences("p3r_goal_planner_commands_v2",0).contains("pending"));
 }
 @Test public void earlierResponseCannotClearOrOverwriteNewerPendingReceipt()throws Exception {
  baseline();SharedPreferences receipts=context.getSharedPreferences("p3r_goal_planner_commands_v2",0);
  edit("returnCash","100");select("cashMode",0);edit("returnFloor","210");edit("exitDay","3");select("exitPeriod",0);
  JSONObject sentinel=new JSONObject().put("original_id","newer-return-record");
  synchronized(app()) {
   tap("保存爬塔结果并重新规划");
   JSONObject newer=new JSONObject(receipts.getString("pending","{}"));newer.getJSONObject("body").put("command_id","newer-command-B");
   receipts.edit().putString("pending",newer.toString()).putString("last_return",sentinel.toString()).commit();
  }
  idle();assertEquals("newer-command-B",new JSONObject(receipts.getString("pending","{}")).getJSONObject("body").getString("command_id"));
  assertEquals("newer-return-record",new JSONObject(receipts.getString("last_return","{}")).getString("original_id"));
  assertEquals(1100,snapshot().getInt("cash"));
 }
 @Test public void invalidGoalsAndReturnValuesDoNotStrandPendingCommands()throws Exception {
  baseline();SharedPreferences receipts=context.getSharedPreferences("p3r_goal_planner_commands_v2",0);
  edit("sunTarget","11");tap("保存目标并重新规划");idle();assertFalse(receipts.contains("pending"));assertTrue(app().state().isNull("preferences"));
  edit("returnFloor","9999");edit("returnCash","100");edit("exitDay","3");tap("保存爬塔结果并重新规划");idle();assertFalse(receipts.contains("pending"));assertEquals(1000,snapshot().getInt("cash"));
  edit("returnFloor","210");edit("returnCash","-1");select("cashMode",1);tap("保存爬塔结果并重新规划");idle();assertFalse(receipts.contains("pending"));assertEquals(0,snapshot().getJSONObject("visits_by_stage").getInt("p3r.tower.stage_10"));
 }
 @Test public void observedSocialResultAdvancesTimeWithoutTowerVisitAndClearsUnobservedHiddenFacts()throws Exception {
  baseline();JSONObject body=invoke("baseline");JSONObject facts=body.getJSONObject("baseline").getJSONObject("facts");
  for(String key:new String[]{"affinityLowerBound","affinityRank","affinityValid","ready","readyRank"})facts.put("socialLinks.sun."+key,new JSONObject().put("low",key.equals("affinityLowerBound")?42:(key.equals("affinityValid")||key.equals("ready")?1:9)).put("high",key.equals("affinityLowerBound")?42:(key.equals("affinityValid")||key.equals("ready")?1:9)));
  body.put("command_id","seed-observed-hidden-state");app().request("/api/baseline",body.toString());tap("刷新实际状态");idle();
  int start=snapshot().getInt("game_slot");
  @SuppressWarnings("unchecked") List<String> slugs=(List<String>)field("socialSlugs");select("socialEntity",slugs.indexOf("sun"));edit("socialRank","9");select("socialNormal",1);select("socialReady",0);
  tap("保存社群实际结果");idle();JSONObject result=snapshot();
  assertEquals(start+1,result.getInt("game_slot"));assertEquals(1000,result.getInt("cash"));assertEquals(0,result.getJSONObject("visits_by_stage").getInt("p3r.tower.stage_10"));
  assertEquals(9,result.getJSONObject("facts").getJSONObject("socialLinks.sun.rank").getInt("low"));
  for(String key:new String[]{"affinityLowerBound","affinityRank","affinityValid","ready","readyRank"})assertTrue(key,result.getJSONObject("facts").getJSONObject("socialLinks.sun."+key).isNull("low"));
  edit("socialRank","10");select("socialReady",1);tap("保存社群实际结果");idle();assertEquals(10,snapshot().getJSONObject("facts").getJSONObject("socialLinks.sun.rank").getInt("low"));assertTrue(snapshot().getJSONObject("facts").getJSONObject("socialLinks.sun.ready").isNull("low"));
 }
 private void measure(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width*2,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height*2,View.MeasureSpec.EXACTLY));view.layout(0,0,width*2,height*2);}
 private void checkHorizontal(View v,List<String> problems){if(v.getVisibility()!=View.VISIBLE)return;if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){View child=group.getChildAt(i);if(child.getVisibility()==View.VISIBLE&&(child.getLeft()<0||child.getRight()>group.getWidth()))problems.add("outside parent: "+(child instanceof TextView?((TextView)child).getText():child.getClass().getSimpleName())+" bounds="+child.getLeft()+".."+child.getRight()+" parent="+group.getWidth());checkHorizontal(child,problems);}}}
 @Test public void smallPhoneNativePanelsRemainHorizontallyUsable()throws Exception {
  baseline();List<String> problems=new ArrayList<>();
  for(String label:new String[]{"当前状态","目标限制","行动结果"}){
   tap(label);View root=(View)field("root");View scroll=(View)root.getParent();measure(scroll,320,640);checkHorizontal(root,problems);
   Bitmap bitmap=Bitmap.createBitmap(640,1280,Bitmap.Config.ARGB_8888);scroll.draw(new Canvas(bitmap));try(FileOutputStream f=new FileOutputStream(new File(output,label+"-320x640.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);}
   ((ScrollView)scroll).scrollTo(0,root.getHeight());Bitmap bottom=Bitmap.createBitmap(640,1280,Bitmap.Config.ARGB_8888);Canvas bottomCanvas=new Canvas(bottom);bottomCanvas.translate(-scroll.getScrollX(),-scroll.getScrollY());scroll.draw(bottomCanvas);assertTrue("Last action remains scroll-reachable",button(root,"打开原固定攻略与原进度").getBottom()-scroll.getScrollY()<=scroll.getHeight());try(FileOutputStream f=new FileOutputStream(new File(output,label+"-bottom-320x640.png"))){bottom.compress(Bitmap.CompressFormat.PNG,100,f);}((ScrollView)scroll).scrollTo(0,0);
  }
  assertTrue(String.join("; ",problems),problems.isEmpty());
 }
}
