package app.p3r.dayplanner;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,28,35},shadows={WidgetPaletteFadeTest.RealViewAnimator.class})
public class WidgetPaletteFadeTest {
 @Test public void hostInflatesBothPalettesAndEndsOnCurrentTask() {
  Context c=RuntimeEnvironment.getApplication();
  for(String task:new String[]{"t005_04","t016_03"}) {
   PlannerStore.setWidgetCursor(c,951,task);
   ViewFlipper flipper=(ViewFlipper)PlannerWidget.transitionViews(c,951,180,160).apply(c,new FrameLayout(c)).findViewById(R.id.widget_palette_flipper);
   assertEquals(2,flipper.getChildCount());assertEquals(task.equals("t016_03")?1:0,flipper.getDisplayedChild());
   assertFalse(flipper.isFlipping());assertFalse(flipper.getAnimateFirstView());
   assertEquals(220,flipper.getInAnimation().getDuration());assertEquals(220,flipper.getOutAnimation().getDuration());
   assertEquals(PlannerStore.taskById(c,task).widgetTitle,((TextView)flipper.getCurrentView().findViewById(R.id.widget_task)).getText().toString());
   assertEquals(PlannerStore.taskById(c,task).period,((TextView)flipper.getCurrentView().findViewById(R.id.widget_period_word)).getText().toString());
   View settled=PlannerWidget.renderForTest(c,951,180,160).apply(c,new FrameLayout(c));
   assertNull(settled.findViewById(R.id.widget_palette_flipper));
   assertEquals(PlannerStore.taskById(c,task).widgetTitle,((TextView)settled.findViewById(R.id.widget_task)).getText().toString());
  }
 }
 @Test public void repeatedRapidActionsDoNotQueueFades() {
  assertTrue(PlannerWidget.allowPeriodFade(961,true,1000));
  assertFalse(PlannerWidget.allowPeriodFade(961,true,1100));
  assertFalse(PlannerWidget.allowPeriodFade(961,true,1200));
  assertFalse(PlannerWidget.allowPeriodFade(961,false,1800));
  assertTrue(PlannerWidget.allowPeriodFade(961,true,2200));
 }
 @Test @Config(sdk=28) public void providerSettlesAndResizeCancelsTransientAnimation() {
  Context c=RuntimeEnvironment.getApplication();
  android.appwidget.AppWidgetManager manager=android.appwidget.AppWidgetManager.getInstance(c);
  org.robolectric.shadows.ShadowAppWidgetManager host=org.robolectric.Shadows.shadowOf(manager);
  int id=host.createWidget(PlannerWidget.class,R.layout.widget_loading);
  PlannerWidget provider=(PlannerWidget)host.getAppWidgetProviderFor(id);
  assertEquals(View.VISIBLE,host.getViewFor(id).findViewById(R.id.widget_palette_static).getVisibility());
  String previous=null, before=null;
  boolean previousNight=false;
  outer:for(PlannerStore.Day day:PlannerStore.days(c))for(PlannerStore.Task task:day.tasks) {
   boolean night=PlannerWidget.isNightPeriod(task.period);
   if(previous!=null && previousNight!=night) {before=previous;break outer;}
   previous=task.id;previousNight=night;
  }
  assertNotNull(before);
  PlannerStore.setWidgetCursor(c,id,before);
  android.content.Intent next=new android.content.Intent(c,PlannerWidget.class).setAction(PlannerWidget.ACTION_NEXT_DATE).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,id);
  provider.onReceive(c,next);
  assertEquals(View.GONE,host.getViewFor(id).findViewById(R.id.widget_palette_static).getVisibility());
  org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
  assertEquals(View.VISIBLE,host.getViewFor(id).findViewById(R.id.widget_palette_static).getVisibility());
  PlannerStore.setWidgetCursor(c,id,before);
  provider.onReceive(c,next);
  assertEquals(View.GONE,host.getViewFor(id).findViewById(R.id.widget_palette_static).getVisibility());
  provider.onAppWidgetOptionsChanged(c,manager,id,new android.os.Bundle());
  assertEquals(View.VISIBLE,host.getViewFor(id).findViewById(R.id.widget_palette_static).getVisibility());
  org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
  assertEquals(View.VISIBLE,host.getViewFor(id).findViewById(R.id.widget_palette_static).getVisibility());
 }
 @org.robolectric.annotation.Implements(android.widget.ViewAnimator.class)
 public static class RealViewAnimator extends org.robolectric.shadows.ShadowViewGroup {}
 @Test @Config(sdk=28,qualifiers="en-rUS-w360dp-h800dp-xhdpi",shadows={RealViewAnimator.class})
 @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
 public void transitionAnimationSurvivesActualAttachAndFirstDraw() throws Exception {
  android.app.Activity activity=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
  FrameLayout host=new FrameLayout(activity);activity.setContentView(host);
  PlannerStore.setWidgetCursor(activity,973,"t005_04");
  View root=PlannerWidget.hostViews(activity,973,250,200,false).apply(activity,host);
  host.addView(root);
  root.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));root.layout(0,0,500,400);
  ViewFlipper original=root.findViewById(R.id.widget_palette_flipper);
  PlannerStore.setWidgetCursor(activity,973,"t016_03");
  PlannerWidget.transitionViews(activity,973,250,200).reapply(activity,root);
  ViewFlipper flipper=root.findViewById(R.id.widget_palette_flipper);
  assertSame("Animator survives reapply",original,flipper);
  assertEquals(View.GONE,root.findViewById(R.id.widget_palette_static).getVisibility());
  root.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));root.layout(0,0,500,400);
  assertNotNull("Incoming animation after attached reapply",flipper.getCurrentView().getAnimation());
  for(int i=0;i<2;i++)flipper.getChildAt(i).getAnimation().setStartTime(1000);
  int[] pixels=new int[3];int index=0;
  for(long time:new long[]{1000,1110,1230}) {
   Object attach=org.robolectric.util.ReflectionHelpers.getField(root,"mAttachInfo");
   org.robolectric.util.ReflectionHelpers.setField(attach,"mDrawingTime",time);
   android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(500,400,android.graphics.Bitmap.Config.ARGB_8888);
   root.draw(new android.graphics.Canvas(bitmap));pixels[index]=bitmap.getPixel(250,160);
   try(java.io.FileOutputStream file=new java.io.FileOutputStream(System.getProperty("p3r.qa.dir","build/qa")+"/p3r_native_final_qa/fade-frame-"+index+".png")){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file);}
   index++;
  }
  java.nio.file.Files.write(java.nio.file.Paths.get(System.getProperty("p3r.qa.dir","build/qa")+"/p3r_native_final_qa/fade-frame-pixels.txt"),java.util.Arrays.asList(java.util.Arrays.toString(pixels)));
  assertEquals("Outgoing card is opaque at start",255,android.graphics.Color.alpha(pixels[0]));
  assertNotEquals("Start differs from midpoint",pixels[0],pixels[1]);
  assertNotEquals("Midpoint differs from end",pixels[1],pixels[2]);
  assertNotEquals("Start differs from end",pixels[0],pixels[2]);
  PlannerWidget.hostViews(activity,973,250,200,false).reapply(activity,root);
  assertSame(original,root.findViewById(R.id.widget_palette_flipper));
  assertEquals(View.VISIBLE,root.findViewById(R.id.widget_palette_static).getVisibility());
 }
}
