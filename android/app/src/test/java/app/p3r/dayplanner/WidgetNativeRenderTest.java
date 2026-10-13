package app.p3r.dayplanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="en-rUS-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetNativeRenderTest {
    private Context c;private static final int W=73;
    private final List<String> buttonIssues=new ArrayList<>(),buttonMetrics=new ArrayList<>();
    private final File output=new File("/workspace/shared/p3r_native_final_qa");
    @Before public void clean(){c=RuntimeEnvironment.getApplication();c.getSharedPreferences("p3r_native_progress_v1",0).edit().clear().commit();output.mkdirs();}
    private View render(String id,int width,int height,String tag)throws Exception{
        PlannerStore.setWidgetCursor(c,W,id);
        View root=PlannerWidget.renderForTest(c,W,width,height).apply(c,new FrameLayout(c));
        root.measure(View.MeasureSpec.makeMeasureSpec(width*2,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height*2,View.MeasureSpec.EXACTLY));root.layout(0,0,width*2,height*2);
        Bitmap image=Bitmap.createBitmap(width*2,height*2,Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));
        try(FileOutputStream file=new FileOutputStream(new File(output,tag+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,file));}
        return root;
    }
    private void checkTextHeight(View view,String tag,List<String> issues) {
        if(view.getVisibility()!=View.VISIBLE) return;
        if(view instanceof TextView) {
            TextView text=(TextView)view;
            if(text.getLayout()!=null&&text.getLineCount()>0&&text.getLayout().getLineBottom(text.getLineCount()-1)+text.getCompoundPaddingTop()+text.getCompoundPaddingBottom()>text.getHeight()+1)
                issues.add(tag+" textId="+text.getId()+" lineBottom="+text.getLayout().getLineBottom(text.getLineCount()-1)+" height="+text.getHeight()+" text="+text.getText());
        } else if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int n=0;n<group.getChildCount();n++) checkTextHeight(group.getChildAt(n),tag,issues);
        }
    }
    private android.graphics.Rect glyphBounds(View root,View sample,int color) {
        Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
        android.graphics.Rect box=new android.graphics.Rect(0,0,sample.getWidth(),sample.getHeight());
        ((android.view.ViewGroup)root).offsetDescendantRectToMyCoords(sample,box);
        int left=box.right,top=box.bottom,right=-1,bottom=-1;
        for(int y=Math.max(0,box.top);y<Math.min(bitmap.getHeight(),box.bottom);y++) for(int x=Math.max(0,box.left);x<Math.min(bitmap.getWidth(),box.right);x++) {
            int pixel=bitmap.getPixel(x,y);
            int diff=Math.abs(android.graphics.Color.red(pixel)-android.graphics.Color.red(color))+Math.abs(android.graphics.Color.green(pixel)-android.graphics.Color.green(color))+Math.abs(android.graphics.Color.blue(pixel)-android.graphics.Color.blue(color));
            if(diff<70) {left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
        }
        assertTrue("No visible glyph pixels for "+sample.getId(),right>=left);
        return new android.graphics.Rect(left,top,right+1,bottom+1);
    }
    private void assertLabelAndIconSeparated(View root,int labelId,int iconId,int color,boolean iconOnLeft) {
        View label=root.findViewById(labelId),icon=root.findViewById(iconId);
        label.setVisibility(View.INVISIBLE);android.graphics.Rect symbol=glyphBounds(root,icon,color);
        label.setVisibility(View.VISIBLE);icon.setVisibility(View.INVISIBLE);android.graphics.Rect text=glyphBounds(root,label,color);icon.setVisibility(View.VISIBLE);
        int gap=iconOnLeft?text.left-symbol.right:symbol.left-text.right;
        if(gap<2)buttonIssues.add("width="+root.getWidth()+" label="+labelId+" gap="+gap);
        View button=(View)label.getParent();android.graphics.Rect face=new android.graphics.Rect(0,0,button.getWidth()-6,button.getHeight());((android.view.ViewGroup)root).offsetDescendantRectToMyCoords(button,face);
        float delta=text.exactCenterX()-face.exactCenterX();
        buttonMetrics.add("width="+root.getWidth()+" label="+labelId+" expected="+face.exactCenterX()+" actual="+text.exactCenterX()+" delta="+delta+" gap="+gap);
        if(Math.abs(delta)>1.1)buttonIssues.add("width="+root.getWidth()+" label="+labelId+" center delta="+delta);
    }
    @Test public void captureDayNightCompactStandardTallAndLongTitle()throws Exception{
        String[] tasks={"t005_01","t005_04","t016_03","t048_02"};int[][] sizes={{180,160},{200,160},{250,200},{320,160},{320,180},{320,240}};
        List<String> metrics=new ArrayList<>();List<String> issues=new ArrayList<>();
        for(String id:tasks)for(int[] size:sizes){
            String tag=id+"-"+size[0]+"x"+size[1];View root=render(id,size[0],size[1],tag);
            TextView title=root.findViewById(R.id.widget_task),date=root.findViewById(R.id.widget_date),current=root.findViewById(R.id.button_spec_current_text);
            View cue=root.findViewById(R.id.widget_detail_cue);
            View area=root.findViewById(R.id.widget_task_area),done=root.findViewById(R.id.button_spec_done),cur=root.findViewById(R.id.button_spec_current);
            metrics.add(tag+" titleTop="+title.getTop()+" titleBottom="+title.getBottom()+" areaHeight="+area.getHeight()+" titleLines="+title.getLineCount()+" lastLineBottom="+title.getLayout().getLineBottom(title.getLineCount()-1)+" titleHeight="+title.getHeight()+" cueHeight="+cue.getHeight()+" cueBottom="+cue.getBottom()+" dateWidth="+date.getWidth()+" CurrentText="+current.getPaint().measureText("Current")+" available="+(current.getWidth()-current.getPaddingLeft()-current.getPaddingRight()));
            if(title.getLayout().getLineBottom(title.getLineCount()-1)>title.getHeight()+1) issues.add(tag+" clips a title line");
            if(cue.getVisibility()==View.VISIBLE&&(cue.getHeight()==0||cue.getBottom()>area.getHeight())) issues.add(tag+" loses the visible detail cue");
            TextView location=root.findViewById(R.id.widget_location);
            if(location.getLineCount()>0&&location.getLayout().getLineBottom(location.getLineCount()-1)>location.getHeight()+1) issues.add(tag+" clips a location line");
            android.view.ViewGroup cluster=(android.view.ViewGroup)date.getParent();
            for(int child=0;child<cluster.getChildCount();child++) {
                View item=cluster.getChildAt(child);
                if(item.getLeft()<0||item.getRight()>cluster.getWidth()) issues.add(tag+" header child outside date cluster "+item.getId());
            }
            for(int headerId:new int[]{R.id.widget_date,R.id.widget_day_number,R.id.widget_period_word}) {
                TextView headerText=root.findViewById(headerId);
                float needed=headerText.getPaint().measureText(headerText.getText().toString());
                int available=headerText.getWidth()-headerText.getCompoundPaddingLeft()-headerText.getCompoundPaddingRight();
                metrics.add(tag+" headerText="+headerText.getText()+" needed="+needed+" available="+available);
                if(needed>available+1) issues.add(tag+" clips header text "+headerText.getText()+" needs="+needed+" available="+available);
            }
            checkTextHeight(root.findViewById(R.id.widget_header),tag+" header",issues);
            checkTextHeight(cue,tag+" detail cue",issues);
            checkTextHeight(root.findViewById(R.id.widget_progress),tag+" ordinal",issues);
            checkTextHeight(root.findViewById(R.id.widget_dots),tag+" dots",issues);
            assertTrue("Task region collapsed "+tag,area.getHeight()>0);
            assertEquals(80,done.getHeight());assertEquals(80,cur.getHeight());assertEquals(16,done.getLeft()-cur.getRight());
        }
        Files.write(new File(output,"layout-metrics.txt").toPath(),metrics);
        assertTrue(String.join("; ",issues),issues.isEmpty());
    }
    @Test public void doneAndCurrentVisibleActionsPreserveFullWordsAtSupportedWidths()throws Exception{
        for(int width:new int[]{180,200,250,320}){
            View root=render("t005_04",width,200,"action-fit-"+width);
            assertLabelAndIconSeparated(root,R.id.button_spec_current_text,R.id.button_spec_return_icon,0xff344057,true);
            assertLabelAndIconSeparated(root,R.id.button_spec_done_text,R.id.button_spec_check,0xff101723,false);
            for(int id:new int[]{R.id.button_spec_current_text,R.id.button_spec_done_text}){
                TextView label=root.findViewById(id);float measured=label.getPaint().measureText(label.getText().toString());
                assertTrue(label.getText()+" clipped at widgetWidth "+width,measured<=label.getWidth()-label.getPaddingLeft()-label.getPaddingRight()+1);
                assertEquals(1,label.getLineCount());
            }
        }
        Files.write(new File(output,"integrated-button-geometry.txt").toPath(),buttonMetrics);
        assertTrue(String.join("; ",buttonIssues),buttonIssues.isEmpty());
    }
    @Test public void changingOnlyAppThemeDoesNotChangeWidgetPeriodLayout()throws Exception{
        PlannerStore.setWidgetCursor(c,W,"t016_03");
        c.getSharedPreferences("p3r_native_progress_v1",0).edit().putString("theme","day").commit();
        View root=render("t016_03",250,200,"night-with-day-app-theme");assertEquals("夜",((TextView)root.findViewById(R.id.widget_period_word)).getText().toString());
        c.getSharedPreferences("p3r_native_progress_v1",0).edit().putString("theme","night").commit();
        root=render("t005_01",250,200,"day-with-night-app-theme");assertEquals("昼",((TextView)root.findViewById(R.id.widget_period_word)).getText().toString());
    }
    @Test public void actionSilhouetteIs34dpInside40dpHitTarget()throws Exception {
        for(String task:new String[]{"t005_04","t016_03"}) {
            View root=render(task,250,200,"material-height-"+task);
            Bitmap surface=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.getBackground().draw(new Canvas(surface));
            int palette=task.equals("t016_03")?0xff034572:0xff0FB1F5;
            for(int x:new int[]{12,root.getWidth()/2,root.getWidth()-12})assertEquals("Uniform solid surface "+task,palette,surface.getPixel(x,root.getHeight()/2));
            for(int id:new int[]{R.id.button_spec_current,R.id.button_spec_done}) {
                View action=root.findViewById(id);
                assertEquals(80,action.getHeight());
                Bitmap bitmap=Bitmap.createBitmap(action.getWidth(),action.getHeight(),Bitmap.Config.ARGB_8888);
                action.getBackground().draw(new Canvas(bitmap));
                int top=bitmap.getHeight(),bottom=-1;
                for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if(android.graphics.Color.alpha(bitmap.getPixel(x,y))>128){top=Math.min(top,y);bottom=Math.max(bottom,y);}
                assertEquals(6,top);assertEquals(68,bottom-top+1);
            }
        }
    }
    @Test public void everyDayNightMotifRemainsFullyInsideRightAlignedViewport()throws Exception {
        java.util.Map<String,String> examples=new java.util.LinkedHashMap<>();
        for(PlannerStore.Day day:PlannerStore.days(c))for(PlannerStore.Task task:day.tasks) {
            String key=PlannerWidget.motifIndex(day.date)+(PlannerWidget.isNightPeriod(task.period)?"-night":"-day");
            examples.putIfAbsent(key,task.id);
        }
        assertEquals("All eleven motifs need both palettes",22,examples.size());
        for(java.util.Map.Entry<String,String> example:examples.entrySet())for(int width:new int[]{180,250,320}) {
            View root=render(example.getValue(),width,200,"motif-"+example.getKey()+"-"+width);
            android.widget.ImageView motif=root.findViewById(R.id.widget_motif);
            assertEquals(android.widget.ImageView.ScaleType.FIT_END,motif.getScaleType());
            android.graphics.RectF bounds=new android.graphics.RectF(motif.getDrawable().getBounds());
            motif.getImageMatrix().mapRect(bounds);
            assertTrue(example.getKey()+" left crop",bounds.left>=-1);
            assertTrue(example.getKey()+" top crop",bounds.top>=-1);
            assertTrue(example.getKey()+" bottom crop",bounds.bottom<=motif.getHeight()+1);
            assertEquals(example.getKey()+" right edge",motif.getWidth()-motif.getPaddingRight(),bounds.right+motif.getPaddingLeft(),1.0);
        }
    }
    @Test public void cornerWordmarkGlyphsAreNotClippedByRootPadding()throws Exception {
        for(String task:new String[]{"t005_04","t016_03"})for(int width:new int[]{180,250,320}) {
            View root=render(task,width,200,"badge-"+task+"-"+width);
            View badge=root.findViewById(R.id.widget_sees_mark);
            Bitmap expected=Bitmap.createBitmap(badge.getWidth(),badge.getHeight(),Bitmap.Config.ARGB_8888);badge.draw(new Canvas(expected));
            Bitmap actual=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(actual));
            badge.setVisibility(View.INVISIBLE);
            Bitmap background=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(background));badge.setVisibility(View.VISIBLE);
            android.graphics.Rect bounds=new android.graphics.Rect(0,0,badge.getWidth(),badge.getHeight());((android.view.ViewGroup)root).offsetDescendantRectToMyCoords(badge,bounds);
            View header=root.findViewById(R.id.widget_header);
            android.graphics.Rect headerBounds=new android.graphics.Rect(0,0,header.getWidth(),header.getHeight());((android.view.ViewGroup)root).offsetDescendantRectToMyCoords(header,headerBounds);
            assertEquals("Wordmark right endpoint",root.getWidth()-root.getPaddingRight(),bounds.right);
            assertTrue("Wordmark centered on header horizontal axis",Math.abs(bounds.exactCenterY()-headerBounds.exactCenterY())<=1f);
            for(int headerId:new int[]{R.id.widget_next,R.id.widget_period_word,R.id.widget_day_number,R.id.widget_date}) {
                View item=root.findViewById(headerId);
                Bitmap glyph=Bitmap.createBitmap(item.getWidth(),item.getHeight(),Bitmap.Config.ARGB_8888);item.draw(new Canvas(glyph));
                android.graphics.Rect itemBounds=new android.graphics.Rect(0,0,item.getWidth(),item.getHeight());((android.view.ViewGroup)root).offsetDescendantRectToMyCoords(item,itemBounds);
                int collisions=0;
                for(int y=0;y<expected.getHeight();y++)for(int x=0;x<expected.getWidth();x++) {
                    int gx=bounds.left+x-itemBounds.left,gy=bounds.top+y-itemBounds.top;
                    if(gx>=0&&gy>=0&&gx<glyph.getWidth()&&gy<glyph.getHeight()&&android.graphics.Color.alpha(expected.getPixel(x,y))>64&&android.graphics.Color.alpha(glyph.getPixel(gx,gy))>64)collisions++;
                }
                assertEquals("Wordmark/header ink collision width="+width+" header="+headerId+" badge="+bounds+" headerBounds="+itemBounds,0,collisions);
            }
            int pixels=0,missing=0;
            for(int y=0;y<expected.getHeight();y++)for(int x=0;x<expected.getWidth();x++) {
                int wanted=expected.getPixel(x,y);if(android.graphics.Color.alpha(wanted)<200)continue;pixels++;
                int seen=actual.getPixel(bounds.left+x,bounds.top+y);
                int back=background.getPixel(bounds.left+x,bounds.top+y),alpha=android.graphics.Color.alpha(wanted);
                int red=(android.graphics.Color.red(wanted)*alpha+android.graphics.Color.red(back)*(255-alpha))/255;
                int green=(android.graphics.Color.green(wanted)*alpha+android.graphics.Color.green(back)*(255-alpha))/255;
                int blue=(android.graphics.Color.blue(wanted)*alpha+android.graphics.Color.blue(back)*(255-alpha))/255;
                int delta=Math.abs(red-android.graphics.Color.red(seen))+Math.abs(green-android.graphics.Color.green(seen))+Math.abs(blue-android.graphics.Color.blue(seen));
                if(delta>30)missing++;
            }
            assertTrue("No wordmark pixels",pixels>20);
            assertTrue("Clipped wordmark "+task+" width="+width+" missing="+missing+" of="+pixels,missing<=Math.max(2,pixels/20));
        }
    }
    private void noInkOverlap(View root,View a,View b,String tag) {
        android.graphics.Rect ra=new android.graphics.Rect(0,0,a.getWidth(),a.getHeight()),rb=new android.graphics.Rect(0,0,b.getWidth(),b.getHeight());
        ((android.view.ViewGroup)root).offsetDescendantRectToMyCoords(a,ra);((android.view.ViewGroup)root).offsetDescendantRectToMyCoords(b,rb);
        android.graphics.Rect overlap=new android.graphics.Rect(ra);if(!overlap.intersect(rb))return;
        Bitmap ba=Bitmap.createBitmap(a.getWidth(),a.getHeight(),Bitmap.Config.ARGB_8888),bb=Bitmap.createBitmap(b.getWidth(),b.getHeight(),Bitmap.Config.ARGB_8888);a.draw(new Canvas(ba));b.draw(new Canvas(bb));
        int collision=0;
        for(int y=overlap.top;y<overlap.bottom;y++)for(int x=overlap.left;x<overlap.right;x++)if(android.graphics.Color.alpha(ba.getPixel(x-ra.left,y-ra.top))>64&&android.graphics.Color.alpha(bb.getPixel(x-rb.left,y-rb.top))>64)collision++;
        assertEquals(tag+" ink overlap a="+ra+" b="+rb,0,collision);
    }
    @Test public void dateCompositeCentersOnWholeCardAcrossSingleDoubleDigitsAndPalettes()throws Exception {
        java.util.Map<String,String> cases=new java.util.LinkedHashMap<>();
        for(PlannerStore.Day day:PlannerStore.days(c))if((day.month==5&&(day.day==2||day.day==3))||(day.month==4&&day.day==30)||(day.month==9&&day.day==28))for(PlannerStore.Task task:day.tasks) {
            String key=day.month+"-"+day.day+(PlannerWidget.isNightPeriod(task.period)?"-night":"-day");cases.putIfAbsent(key,task.id);
        }
        assertTrue(cases.size()>=6);
        java.util.List<String> metrics=new java.util.ArrayList<>();
        for(java.util.Map.Entry<String,String> example:cases.entrySet())for(int width:new int[]{180,200,250,320}) {
            View root=render(example.getValue(),width,200,"header-center-"+example.getKey()+"-"+width);
            View group=(View)root.findViewById(R.id.widget_date).getParent();
            android.graphics.Rect bounds=new android.graphics.Rect(0,0,group.getWidth(),group.getHeight());((android.view.ViewGroup)root).offsetDescendantRectToMyCoords(group,bounds);
            metrics.add(example.getKey()+" width="+width+" cardCenter="+root.getWidth()/2f+" dateCenter="+bounds.exactCenterX());
            assertEquals(example.getKey()+" whole card center "+width,root.getWidth()/2f,bounds.exactCenterX(),0.5f);
            View logo=root.findViewById(R.id.widget_official_logo);
            for(int id:new int[]{R.id.widget_prev,R.id.widget_date,R.id.widget_day_number,R.id.widget_period_icon,R.id.widget_period_word,R.id.widget_next})noInkOverlap(root,logo,root.findViewById(id),example.getKey()+" width="+width+" header="+id);
        }
        Files.write(new File(output,"header-center-metrics.txt").toPath(),metrics);
    }
}
