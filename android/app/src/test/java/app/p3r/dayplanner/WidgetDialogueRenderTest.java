package app.p3r.dayplanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="en-rUS-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetDialogueRenderTest {
 private Context c;private JSONObject metadata;
 private final File output=new File("/workspace/shared/p3r_dialogue_qa");
 @Before public void setup()throws Exception {
  c=RuntimeEnvironment.getApplication();
  c.getSharedPreferences("p3r_native_progress_v1",0).edit().clear().commit();
  try(InputStream in=c.getAssets().open("display_metadata.json")){metadata=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
  output.mkdirs();
 }
 @Test public void choicesAreStructuredAndApril30WordingRemainsExact()throws Exception {
  assertEquals(691,metadata.length());
  Iterator<String> ids=metadata.keys();int nonempty=0;
  while(ids.hasNext()) {
   String id=ids.next();JSONObject entry=metadata.getJSONObject(id);
   JSONArray choices=entry.getJSONArray("dialogueChoices");assertNotNull(entry.getString("dialogueCue"));
   for(int i=0;i<choices.length();i++)assertFalse(id,choices.getString(i).trim().isEmpty());
   if(choices.length()>0) {
    nonempty++;
    PlannerStore.Task task=PlannerStore.taskById(c,id);
    String formatted=PlannerWidget.dialogueText(task);
    if(!formatted.isEmpty())assertEquals(id+" global flow",String.join(" / ",task.dialogueChoices),formatted);
    else {
     int from=0;for(String choice:task.dialogueChoices){int at=task.widgetTitle.indexOf(choice,from);assertTrue(id+" omitted choice",at>=0);from=at+choice.length();}
    }
   }
  }
  assertTrue(nonempty>0);
  JSONArray april=metadata.getJSONObject("t014_03").getJSONArray("dialogueChoices");
  assertEquals(2,april.length());assertEquals("不能进或者保密",april.getString(0));assertEquals("成熟的好或者无所谓",april.getString(1));
 }
 private void fullText(TextView text,String tag,List<String> issues) {
  if(text.getVisibility()!=View.VISIBLE)return;
  if(text.getLayout()==null||text.getLineCount()==0){issues.add(tag+" empty layout");return;}
  int last=text.getLineCount()-1;
  if(text.getLayout().getLineBottom(last)+text.getCompoundPaddingTop()+text.getCompoundPaddingBottom()>text.getHeight()+1)issues.add(tag+" vertical clipping lineBottom="+text.getLayout().getLineBottom(last)+" height="+text.getHeight()+" padding="+(text.getCompoundPaddingTop()+text.getCompoundPaddingBottom()));
  if(text.getLayout().getLineEnd(last)<text.getText().length())issues.add(tag+" lost trailing text");
  for(int i=0;i<=last;i++)if(text.getLayout().getEllipsisCount(i)>0)issues.add(tag+" ellipsized");
 }
 @Test public void shortestLongestAndMultiChoiceCardsFitOrShowExplicitDetailCue()throws Exception {
  List<String> all=new ArrayList<>();Iterator<String> ids=metadata.keys();
  while(ids.hasNext()){String id=ids.next();if(metadata.getJSONObject(id).getJSONArray("dialogueChoices").length()>0)all.add(id);}
  all.sort(Comparator.comparingInt(id->metadata.optJSONObject(id).optJSONArray("dialogueChoices").toString().length()));
  Set<String> cases=new LinkedHashSet<>(Arrays.asList("t014_03","t016_01","t016_02",all.get(0),all.get(all.size()/2),all.get(all.size()-1)));
  Iterator<String> fallbackIds=metadata.keys();
  while(fallbackIds.hasNext()){String id=fallbackIds.next();if(!metadata.getJSONObject(id).getString("dialogueCue").isEmpty()){cases.add(id);break;}}
  List<String> report=new ArrayList<>(),issues=new ArrayList<>();
  int shown=0;
  for(String id:cases)for(int[] size:new int[][]{{180,160},{200,160},{250,200},{320,160},{320,180},{320,240}}) {
   String tag=id+"-"+size[0]+"x"+size[1];PlannerStore.setWidgetCursor(c,873,id);
   View root=PlannerWidget.renderForTest(c,873,size[0],size[1]).apply(c,new FrameLayout(c));
   root.measure(View.MeasureSpec.makeMeasureSpec(size[0]*2,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1]*2,View.MeasureSpec.EXACTLY));root.layout(0,0,size[0]*2,size[1]*2);
   TextView choices=root.findViewById(R.id.widget_dialogue_choices),cue=root.findViewById(R.id.widget_detail_cue),title=root.findViewById(R.id.widget_task);
   JSONArray expected=metadata.getJSONObject(id).getJSONArray("dialogueChoices");
   if(id.equals("t016_02")&&size[0]==180&&size[1]==160)assertEquals("May2 full choices fit after reclaiming body padding",View.VISIBLE,choices.getVisibility());
   if(choices.getVisibility()==View.VISIBLE) {
    shown++;fullText(choices,tag+" dialogue",issues);
    List<String> ordered=new ArrayList<>();for(int i=0;i<expected.length();i++)ordered.add(expected.getString(i));
    assertEquals(tag+" choices flow",String.join(" / ",ordered),choices.getText().toString());
    assertFalse(tag+" forced newline",choices.getText().toString().contains("\n"));
    for(int i=0;i<expected.length();i++)if(!choices.getText().toString().contains(expected.getString(i)))issues.add(tag+" missing exact choice "+i);
    assertEquals(tag+" dialogue font",12f,choices.getTextSize()/c.getResources().getDisplayMetrics().scaledDensity,0.05f);
   } else {
    boolean alreadyInTitle=true;
    for(int i=0;i<expected.length();i++)alreadyInTitle &= title.getText().toString().contains(expected.getString(i));
    if(!alreadyInTitle || !metadata.getJSONObject(id).getString("dialogueCue").isEmpty()) {
    assertEquals(tag+" fallback cue",View.VISIBLE,cue.getVisibility());
    assertFalse(tag+" empty fallback",cue.getText().toString().trim().isEmpty());fullText(cue,tag+" cue",issues);
    }
   }
   assertTrue(tag+" squeezed title",title.getTextSize()/c.getResources().getDisplayMetrics().scaledDensity>=10.9f);
   fullText(title,tag+" title",issues);
   report.add(tag+" choicesVisible="+(choices.getVisibility()==View.VISIBLE)+" choiceHeight="+choices.getHeight()+" lines="+choices.getLineCount()+" titleSp="+title.getTextSize()/2+" titleHeight="+title.getHeight()+" titleLineBottom="+title.getLayout().getLineBottom(title.getLineCount()-1));
   Bitmap bitmap=Bitmap.createBitmap(size[0]*2,size[1]*2,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
   try(FileOutputStream file=new FileOutputStream(new File(output,tag+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,file);}
  }
  Files.write(new File(output,"dialogue-layout-report.txt").toPath(),report);
  assertTrue("No choices shown at any supported size",shown>0);
  assertTrue(String.join("; ",issues),issues.isEmpty());
 }
}
