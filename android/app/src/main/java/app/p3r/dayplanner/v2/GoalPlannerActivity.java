package app.p3r.dayplanner.v2;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import app.p3r.dayplanner.MainActivity;

/** Native, offline checkpoint/goal/return interface. Forecasts never become observations. */
public final class GoalPlannerActivity extends Activity {
    private static final String STAGE="p3r.tower.stage_10";
    private PlannerCoordinator coordinator;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private SharedPreferences receipts;
    private LinearLayout root,basePanel,goalPanel,returnPanel;
    private TextView summary,notice,planText;
    private EditText day,cash,floor,visits,sunRank,devilRank,sunTarget,devilTarget,sunPriority,devilPriority,cap,returnCash,returnFloor,exitDay,socialRank;
    private Spinner socialEntity,socialNormal,socialReady;
    private final List<String> socialSlugs=new ArrayList<>();
    private Spinner period,sunNormal,devilNormal,cashMode,exitPeriod;
    private CheckBox sunGoal,devilGoal,correctLast;
    private JSONObject current;
    private boolean busy;
    private volatile boolean destroyed;
    private static final Object COMMAND_LOCK=new Object();
    private final List<View> controls=new ArrayList<>();
    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        try{coordinator=new PlannerCoordinator(this);}catch(Exception e){TextView t=new TextView(this);t.setText("规则包无法读取："+e.getMessage());setContentView(t);return;}
        receipts=getSharedPreferences("p3r_goal_planner_commands_v2",MODE_PRIVATE);
        ScrollView scroll=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(28),dp(20),dp(24));scroll.addView(root);setContentView(scroll);
        root.setBackgroundColor(Color.rgb(5,31,60));
        text(root,"P3R · 目标规划",25);text(root,"离线规划预览 · 当前仅开放已核对的一月太阳／恶魔规则。覆盖不足会明确说明。",14);
        summary=text(root,"正在读取实际状态…",16);notice=text(root,"",14);
        LinearLayout nav=new LinearLayout(this);root.addView(nav);
        button(nav,"当前状态",()->show(basePanel));button(nav,"目标限制",()->show(goalPanel));button(nav,"行动结果",()->show(returnPanel));
        basePanel=panel();goalPanel=panel();returnPanel=panel();
        text(basePanel,"从当前游戏状态继续",20);text(basePanel,"未知数值留空。无需重建过去行动；本阶段已用爬塔次数请按实际填写。",14);
        day=input(basePanel,"1 月日期（2–30）",false);day.setText("2");period=choice(basePanel,"当前时段","白天","晚上");
        cash=input(basePanel,"实际现金（未知留空）",false);floor=input(basePanel,"历史最高楼层（未知留空）",false);visits=input(basePanel,"本解锁阶段已用爬塔次数（未知留空）",false);
        sunRank=input(basePanel,"太阳当前等级（0–10；未知留空）",false);sunNormal=choice(basePanel,"太阳关系状态","不确定","正常","非正常／需修复");
        devilRank=input(basePanel,"恶魔当前等级（0–10；未知留空）",false);devilNormal=choice(basePanel,"恶魔关系状态","不确定","正常","非正常／需修复");
        button(basePanel,"确认当前状态",()->new AlertDialog.Builder(this).setMessage("以这些实际状态建立新基线？旧候选会失效，原攻略进度不会更改。").setPositiveButton("确认",(d,w)->prepare("/api/baseline",this::baseline)).setNegativeButton("取消",null).show());
        text(goalPanel,"目标与硬限制",20);sunGoal=check(goalPanel,"太阳社群",true);sunTarget=input(goalPanel,"太阳目标等级",false);sunTarget.setText("10");sunPriority=input(goalPanel,"太阳优先级（数字越大越优先）",false);sunPriority.setText("0");devilGoal=check(goalPanel,"恶魔社群",false);devilTarget=input(goalPanel,"恶魔目标等级",false);devilTarget.setText("10");devilPriority=input(goalPanel,"恶魔优先级（数字越大越优先）",false);devilPriority.setText("0");cap=input(goalPanel,"本阶段最多爬塔次数（留空不设上限）",false);
        text(goalPanel,"其他社群仍在补充可执行规则，暂不据此判断能否达成。可分别设置优先级。",14);
        button(goalPanel,"保存目标并重新规划",()->prepare("/api/preferences",this::preferences));
        text(returnPanel,"填写这次实际结果",20);text(returnPanel,"净现金变化与当前余额二选一。确认一次只计一次爬塔；修正不会再增加次数。",14);
        correctLast=check(returnPanel,"修正上次爬塔记录",false);cashMode=choice(returnPanel,"现金填写方式","净现金变化（可为负数）","当前绝对余额");returnCash=input(returnPanel,"实际金额",true);returnFloor=input(returnPanel,"这次达到的最高楼层",false);exitDay=input(returnPanel,"本次行动结束后 1 月日期（2–31）",false);exitPeriod=choice(returnPanel,"本次行动结束后的时段","白天","晚上");
        button(returnPanel,"保存爬塔结果并重新规划",()->prepare("/api/return",this::returned));
        text(returnPanel,"社群会面后的实际结果",20);text(returnPanel,"填写游戏实际显示的等级，不会按候选自动加一级。结束时段沿用上方填写值。",14);
        List<String> names=new ArrayList<>();try{for(int i=0;i<coordinator.pack.catalog.length();i++){JSONObject entry=coordinator.pack.catalog.getJSONObject(i);names.add(entry.getString("name_zh_cn"));String id=entry.getString("id");socialSlugs.add(id.substring(id.lastIndexOf('.')+1));}}catch(Exception ignored){}
        socialEntity=choice(returnPanel,"本次社群",names.toArray(new String[0]));socialRank=input(returnPanel,"实际显示等级（0–10）",false);
        socialNormal=choice(returnPanel,"当前关系状态","不确定","正常","非正常／需修复");socialReady=choice(returnPanel,"游戏是否明确提示下次可以升级","不确定","是","否");
        button(returnPanel,"保存社群实际结果",()->prepare("/api/observed_social",this::socialResult));
        button(root,"重试尚未确认的提交",this::retry);
        button(root,"刷新实际状态",()->refresh(true));
        button(root,"核对后放弃待确认提交",()->new AlertDialog.Builder(this).setMessage("这只清除待重试标识，不撤销任何已保存结果。请先刷新并核对实际状态，避免重复录入同一次爬塔。").setPositiveButton("已核对，清除标识",(d,w)->{synchronized(COMMAND_LOCK){receipts.edit().remove("pending").commit();}notice.setText("待重试标识已清除，已保存状态保留");}).setNegativeButton("取消",null).show());
        text(root,"未来候选",20);planText=text(root,"先确认实际状态和目标",16);
        button(root,"打开原固定攻略与原进度",()->startActivity(new Intent(this,MainActivity.class)));
        show(basePanel);refresh(true);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout parent,String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setPadding(0,dp(8),0,dp(8));parent.addView(t);return t;}
    private LinearLayout panel(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(0,dp(12),0,dp(12));root.addView(p);return p;}
    private void show(LinearLayout panel){for(LinearLayout p:new LinearLayout[]{basePanel,goalPanel,returnPanel})if(p!=null)p.setVisibility(p==panel?View.VISIBLE:View.GONE);}
    private EditText input(LinearLayout parent,String label,boolean signed){text(parent,label,14);EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(Color.WHITE);e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff9bdbff));e.setHintTextColor(0xffa0b8d0);e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|(signed?android.text.InputType.TYPE_NUMBER_FLAG_SIGNED:0));parent.addView(e);controls.add(e);return e;}
    private Spinner choice(LinearLayout parent,String label,String... values){text(parent,label,14);Spinner s=new Spinner(this);s.setBackgroundColor(0xffc5e8ff);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));parent.addView(s);controls.add(s);return s;}
    private CheckBox check(LinearLayout parent,String label,boolean checked){CheckBox c=new CheckBox(this);c.setText(label);c.setTextColor(Color.WHITE);c.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff9bdbff));c.setChecked(checked);parent.addView(c);controls.add(c);return c;}
    private void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);parent.addView(b);b.setOnClickListener(v->{if(!busy)action.run();});controls.add(b);}
    private Object number(EditText field){String s=field.getText().toString().trim();return s.isEmpty()?JSONObject.NULL:Integer.parseInt(s);}
    private int required(EditText field){Object n=number(field);if(n==JSONObject.NULL)throw new IllegalArgumentException("请填写必需的数值");return (Integer)n;}
    private JSONObject interval(Object n)throws JSONException{return new JSONObject().put("low",n).put("high",n);}
    private int slot(EditText field,Spinner phase,int max){int d=required(field);if(d<2||d>max)throw new IllegalArgumentException("日期超出当前规则窗口");return coordinator.pack.firstSlot+(d-2)*2+phase.getSelectedItemPosition();}
    private JSONObject envelope()throws JSONException{return new JSONObject().put("expected_revision",current==null?JSONObject.NULL:current.getLong("revision"));}
    private JSONObject baseline()throws Exception{
        for(EditText field:new EditText[]{sunRank,devilRank}){Object value=number(field);if(value!=JSONObject.NULL&&((Integer)value<0||(Integer)value>10))throw new IllegalArgumentException("社群当前等级必须为 0–10，未知请留空");}
        for(EditText field:new EditText[]{cash,floor,visits}){Object value=number(field);if(value!=JSONObject.NULL&&(Integer)value<0)throw new IllegalArgumentException("现金、楼层和次数不能为负数");}
        JSONObject facts=new JSONObject();facts.put("socialLinks.sun.rank",interval(number(sunRank))).put("socialLinks.devil.rank",interval(number(devilRank)));
        for(String link:new String[]{"sun","devil"}){Spinner s=link.equals("sun")?sunNormal:devilNormal;Object value=s.getSelectedItemPosition()==0?JSONObject.NULL:s.getSelectedItemPosition()==1?1:0;facts.put("socialLinks."+link+".normal",interval(value));}
        JSONObject b=new JSONObject().put("rules_version",coordinator.pack.version).put("game_slot",slot(day,period,30)).put("cash",number(cash)).put("highest_floor",number(floor)).put("active_stage",STAGE).put("visits_by_stage",new JSONObject().put(STAGE,number(visits))).put("facts",facts);
        if(current!=null&&current.has("legacy_guide_backup"))b.put("legacy_guide_backup",current.get("legacy_guide_backup"));return envelope().put("baseline",b);
    }
    private JSONObject preferences()throws Exception{requireCurrent();if((sunGoal.isChecked()&&(required(sunTarget)<1||required(sunTarget)>10))||(devilGoal.isChecked()&&(required(devilTarget)<1||required(devilTarget)>10)))throw new IllegalArgumentException("目标等级必须为 1–10");Object maxVisits=number(cap);if(maxVisits!=JSONObject.NULL&&(Integer)maxVisits<0)throw new IllegalArgumentException("次数上限不能为负数");JSONArray goals=new JSONArray();if(sunGoal.isChecked())goals.put(new JSONObject().put("field","socialLinks.sun.rank").put("target",required(sunTarget)).put("priority",required(sunPriority)));if(devilGoal.isChecked())goals.put(new JSONObject().put("field","socialLinks.devil.rank").put("target",required(devilTarget)).put("priority",required(devilPriority)));if(goals.length()==0)throw new IllegalArgumentException("请至少选择一个目标");JSONObject caps=new JSONObject();Object limit=number(cap);if(limit!=JSONObject.NULL)caps.put(STAGE,limit);return envelope().put("goals",goals).put("constraints",new JSONObject().put("visit_caps",caps).put("pinned_actions",new JSONObject()));}
    private void requireCurrent(){if(current==null)throw new IllegalArgumentException("请先确认当前实际状态");}
    private JSONObject returned()throws Exception{requireCurrent();if(required(returnFloor)<0||required(returnFloor)>coordinator.pack.stageCeilings.getInt(STAGE))throw new IllegalArgumentException("楼层超出当前已核对阶段范围");if(cashMode.getSelectedItemPosition()==1&&required(returnCash)<0)throw new IllegalArgumentException("当前余额不能为负数");if(!correctLast.isChecked()&&slot(exitDay,exitPeriod,31)<=current.getInt("game_slot"))throw new IllegalArgumentException("离开后的时段必须晚于本次爬塔起始时段");JSONObject result=envelope().put("stage_id",STAGE).put("highest_floor",required(returnFloor));result.put(cashMode.getSelectedItemPosition()==0?"net_cash":"absolute_balance",required(returnCash));
        if(correctLast.isChecked()){JSONObject prior=new JSONObject(receipts.getString("last_return","{}"));if(prior.optLong("epoch",-1)!=current.getLong("epoch"))throw new IllegalArgumentException("当前基线没有可修正的上次记录");JSONObject old=prior.getJSONObject("body");result.put("corrects_event_id",prior.getString("original_id")).put("visit_slot",old.getInt("visit_slot")).put("next_game_slot",old.getInt("next_game_slot"));}
        else result.put("visit_slot",current.getInt("game_slot")).put("next_game_slot",slot(exitDay,exitPeriod,31));return result;}
    private JSONObject socialResult()throws Exception {
        requireCurrent();int rank=required(socialRank);if(rank<0||rank>10)throw new IllegalArgumentException("实际等级必须为 0–10");
        int next=slot(exitDay,exitPeriod,31);if(next<=current.getInt("game_slot"))throw new IllegalArgumentException("结束时段必须晚于开始时段");
        String slug=socialSlugs.get(socialEntity.getSelectedItemPosition());Object normal=socialNormal.getSelectedItemPosition()==0?JSONObject.NULL:socialNormal.getSelectedItemPosition()==1;
        Object ready=socialReady.getSelectedItemPosition()==0?JSONObject.NULL:socialReady.getSelectedItemPosition()==1;
        return envelope().put("entity_id",slug).put("observed_rank",rank).put("relationship_normal",normal).put("ready",ready)
            .put("action_id","observed.social."+slug).put("action_slot",current.getInt("game_slot")).put("next_game_slot",next);
    }
    private interface Builder{JSONObject make()throws Exception;}
    private void prepare(String path,Builder build){try{synchronized(COMMAND_LOCK){if(receipts.contains("pending"))throw new IllegalArgumentException("有尚未确认的提交，请先重试并核对结果");JSONObject body=build.make().put("command_id",UUID.randomUUID().toString());JSONObject pending=new JSONObject().put("path",path).put("body",body);if(!receipts.edit().putString("pending",pending.toString()).commit())throw new IllegalStateException("无法保存提交标识");submit(pending);}}catch(Exception e){notice.setText(e.getMessage());}}
    private void retry(){try{String p=receipts.getString("pending",null);if(p==null){notice.setText("没有待确认的提交");return;}submit(new JSONObject(p));}catch(Exception e){notice.setText(e.getMessage());}}
    private void submit(JSONObject pending){setBusy(true);worker.execute(()->{try{String path=pending.getString("path");JSONObject body=pending.getJSONObject("body"),result=coordinator.request(path,body.toString());
            synchronized(COMMAND_LOCK){JSONObject active=new JSONObject(receipts.getString("pending","{}"));
            if(active.optJSONObject("body")!=null&&commandMatches(active.getJSONObject("body"),body)){
            SharedPreferences.Editor editor=receipts.edit().remove("pending");if(path.equals("/api/baseline"))editor.remove("last_return");
            if(path.equals("/api/return")){JSONObject last=new JSONObject().put("original_id",body.optString("corrects_event_id",body.getString("command_id"))).put("body",body).put("epoch",result.getJSONObject("observation").getJSONObject("snapshot").getLong("epoch"));editor.putString("last_return",last.toString());}editor.commit();}}
            runOnUiThread(()->{if(destroyed)return;setBusy(false);notice.setText(result.optBoolean("replan_pending")?"实际记录已保存；补充状态或目标后可重新规划。":"已保存并重新规划");refresh(true);});
        }catch(Exception e){runOnUiThread(()->{if(destroyed)return;setBusy(false);notice.setText("尚未确认："+e.getMessage()+"。重试会使用同一提交标识。");});}});}
    private static boolean commandMatches(JSONObject first,JSONObject second){return first.optString("command_id").equals(second.optString("command_id"));}
    private void setBusy(boolean value){busy=value;for(View v:controls)v.setEnabled(!value);}
    private void fill(EditText view,JSONObject object,String key){view.setText(object.isNull(key)?"":object.optString(key,""));}
    private void refresh(boolean calculate){if(busy)return;setBusy(true);worker.execute(()->{try{JSONObject data=coordinator.state(),planned=calculate?coordinator.request("/api/plan",""):null;runOnUiThread(()->{if(destroyed)return;try{
            current=data.isNull("snapshot")?null:data.getJSONObject("snapshot");if(current==null)summary.setText("尚未建立实际状态");else{
                int s=current.getInt("game_slot"),d=2+(s-coordinator.pack.firstSlot)/2;summary.setText("实际进度：1 月 "+d+" 日 "+(s%2==0?"白天":"晚上")+"\n现金："+(current.isNull("cash")?"未知":current.get("cash"))+" · 最高楼层："+(current.isNull("highest_floor")?"未知":current.get("highest_floor"))+"\n本阶段已用次数："+current.getJSONObject("visits_by_stage").opt(STAGE));
                day.setText(""+d);period.setSelection(s%2);fill(cash,current,"cash");fill(floor,current,"highest_floor");fill(visits,current.getJSONObject("visits_by_stage"),STAGE);
                for(String link:new String[]{"sun","devil"}){JSONObject fact=current.getJSONObject("facts").optJSONObject("socialLinks."+link+".rank");(link.equals("sun")?sunRank:devilRank).setText(fact==null||fact.isNull("low")?"":fact.optString("low"));JSONObject normal=current.getJSONObject("facts").optJSONObject("socialLinks."+link+".normal");(link.equals("sun")?sunNormal:devilNormal).setSelection(normal==null||normal.isNull("low")?0:normal.optInt("low")==1?1:2);}
                exitDay.setText(""+(2+(s+1-coordinator.pack.firstSlot)/2));exitPeriod.setSelection((s+1)%2);if(current.isNull("cash"))cashMode.setSelection(1);
            }
            JSONObject prefs=data.optJSONObject("preferences");if(prefs!=null){sunGoal.setChecked(false);devilGoal.setChecked(false);JSONArray goals=prefs.getJSONArray("goals");for(int i=0;i<goals.length();i++){JSONObject g=goals.getJSONObject(i);if(g.getString("field").equals("socialLinks.sun.rank")){sunGoal.setChecked(true);sunTarget.setText(g.getString("target"));sunPriority.setText(""+g.optInt("priority",0));}if(g.getString("field").equals("socialLinks.devil.rank")){devilGoal.setChecked(true);devilTarget.setText(g.getString("target"));devilPriority.setText(""+g.optInt("priority",0));}}fill(cap,prefs.getJSONObject("constraints").optJSONObject("visit_caps") == null?new JSONObject():prefs.getJSONObject("constraints").getJSONObject("visit_caps"),STAGE);}
            renderPlan(planned);
        }catch(Exception e){notice.setText(e.getMessage());}finally{setBusy(false);}});}catch(Exception e){runOnUiThread(()->{if(destroyed)return;setBusy(false);notice.setText(e.getMessage());});}});}
    private void renderPlan(JSONObject result)throws Exception{if(result==null||result.isNull("plan")){planText.setText("先确认实际状态和目标");return;}JSONObject p=result.getJSONObject("plan");String status=p.optString("status"),label=status.equals("conditional")?"有条件候选":status.equals("unknown")?"还缺少实际状态":status.equals("unsupported")?"当前规则未覆盖":status.equals("search_limit")?"搜索尚未得出结论":status;StringBuilder out=new StringBuilder(label+"\n");JSONArray reasons=p.optJSONArray("explanations");if(reasons!=null)for(int i=0;i<reasons.length();i++)out.append(explain(reasons.getString(i))).append('\n');JSONArray steps=p.optJSONArray("steps");if(steps!=null)for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);String action=step.getString("action_id");if(action.equals("__wait__"))continue;int slot=step.getInt("slot");out.append("1 月 ").append(2+(slot-coordinator.pack.firstSlot)/2).append(" 日 ").append(slot%2==0?"白天":"晚上").append(" · ").append(action.endsWith("visit_sun")?"太阳社群会面":action.endsWith("visit_devil")?"恶魔社群会面":"爬塔，回来填写实际结果").append('\n');}out.append("\n候选不等于实际完成。其他行动后可直接更新当前状态。");planText.setText(out.toString());}
    private String explain(String message){
        switch(message){
            case "Rule coverage is incomplete; this is only a candidate":return "规则覆盖尚不完整，这是一份需要核对条件的候选日程。";
            case "Rule coverage is incomplete":return "规则覆盖尚不完整。";
            case "Goal attainment depends on unknown or uncertain outcomes":return "目标能否达成仍取决于未知或未确认的实际结果。";
            case "Expansion budget reached; no impossibility proof":return "本轮搜索达到计算上限，不能据此判断目标无法完成。";
            case "Beam pruning discarded alternatives; no impossibility proof":return "搜索保留了部分较优路线，但尚不能排除其他方案。";
            case "Incomplete rules or uncertain prerequisites prevent a proof":return "规则或前置条件仍有缺口，暂时无法给出确定结论。";
            case "All goals verified within the supplied model; optimality is not claimed":return "当前模型内存在满足目标的路线，但未证明它是最优方案。";
            case "Exhaustive search found no schedule meeting all goals within this model and horizon":return "在当前完整模型及规划期限内，穷举未找到同时满足全部目标的日程。";
            default:if(message.contains("unknown_visit_count"))return "请确认当前解锁阶段已经使用的爬塔次数。";return message;
        }
    }
    @Override protected void onDestroy(){destroyed=true;if(coordinator!=null&&!worker.isShutdown())worker.execute(coordinator::close);worker.shutdown();super.onDestroy();}
}
