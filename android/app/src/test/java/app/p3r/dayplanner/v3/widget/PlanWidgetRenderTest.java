package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Renders the plan widget for every state × palette × size bucket (and at font scale 1.15), writes
 * PNGs to $p3r.qa.dir/v3/widget/plan/, and fails on any clipped or lost text.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlanWidgetRenderTest {
    private static final int ID = 41;
    private static final int[][] SIZES = {{180, 150}, {250, 160}, {320, 200}};
    private Context app;
    private FakePlannerApi fake;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        fake = new FakePlannerApi();
        WidgetApi.setOverrideForTest(fake);
        WidgetWorker.setForTest(Runnable::run, (task, delay) -> { });
    }

    @After
    public void tearDown() {
        WidgetApi.setOverrideForTest(null);
        WidgetWorker.setForTest(null, null);
        RuntimeEnvironment.setFontScale(1f);
    }

    /** Prepares the fake/prefs for a named state. */
    private void state(String name) {
        app.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        fake = new FakePlannerApi();
        WidgetApi.setOverrideForTest(fake);
        switch (name) {
            case "social": fake.observeAt("meet:hanged"); break;
            case "stat": fake.observeAt("act:study_room"); break;
            case "job": fake.observeAt("act:job_chagall"); break;
            case "unlock": fake.observeAt("unlock:temperance:0"); break;
            case "locked": fake.observeAt("locked:full_moon"); break;
            case "story-run":
                // 11/4 daytime through 11/5 daytime: one merged card.
                fake.plan.steps.set(8, FakePlannerApi.step(FakePlannerApi.FIRST_SLOT + 8, "locked:story", "locked", "剧情事件", "剧情推进", null, null));
                fake.observeAt("locked:story");
                break;
            case "tower": fake.observeAt("tower:4"); break;
            case "future":
                fake.observeAt("meet:hanged");
                WidgetPrefs.setCursor(app, ID, FakePlannerApi.PLAN_ID, FakePlannerApi.FIRST_SLOT + 9);
                break;
            case "note":
                fake.rules = FakePlannerApi.miniRules();
                fake.plan.steps.set(3, FakePlannerApi.noteStep(FakePlannerApi.FIRST_SLOT + 3));
                fake.observeAt("note:priestess");
                break;
            case "episode":
                fake.rules = FakePlannerApi.miniRules();
                fake.plan.steps.set(2, FakePlannerApi.episodeStep(FakePlannerApi.FIRST_SLOT + 2));
                fake.observeAt("episode:junpei");
                break;
            case "hint": {
                // A meeting with the Persona reminder and a payment done 顺便 in the same slot.
                Plan.Step emperor = fake.plan.steps.get(2);
                emperor.hintZh = "记得带上「皇帝」阿尔卡那的人格面具";
                emperor.side.add("unlock:devil:0");
                emperor.sideZh.add("顺便：把 20,000 日元交给穿西装的男人");
                fake.observeAt("meet:emperor");
                break;
            }
            case "max-up":
                fake.plan.steps.set(2, FakePlannerApi.rank(FakePlannerApi.step(FakePlannerApi.FIRST_SLOT + 2, "meet:emperor", "social",
                        "皇帝 · 小田桐秀利", "RANK 9 → MAX", "月光馆学园 3楼 学生会室", "「必须」目标，这次见面一定能升到 MAX"), 9, 10));
                fake.observeAt("meet:emperor");
                break;
            case "max-meet":
                // An invitation from a link that is already MAX (older plans wrote "RANK MAX").
                fake.plan.steps.set(0, FakePlannerApi.rank(FakePlannerApi.step(FakePlannerApi.FIRST_SLOT, "meet:hanged", "social",
                        "倒悬者 · 舞子", "假日邀约 · RANK MAX · 见面（预计不升级）", "应邀外出", "对方发来了假日邀约，赴约也算一次见面"), 10, 10));
                fake.observeAt("meet:hanged");
                break;
            case "job-side":
                // A job with a payment done 顺便: 完成 opens the sheet, which asks about the payment.
                FakePlannerApi.side(fake.plan.steps.get(3), "unlock:devil:0", "顺便：在喷泉前把 20,000 日元交给田中社长");
                fake.observeAt("act:job_chagall");
                break;
            case "long-stat":
            case "long": {
                // The engine's longest real wording on one card: an invite, a level-up, a long place.
                Plan.Step invite = FakePlannerApi.rank(FakePlannerApi.step(FakePlannerApi.FIRST_SLOT, "meet:hanged", "social",
                        "倒悬者 · 舞子", "假日邀约 · 加深关系（未升级）", "月光馆学园 · 1 楼教职员室前走廊", "对方发来了假日邀约，赴约也算一次见面"), 4, 4);
                fake.plan.steps.set(0, invite);
                fake.plan.steps.set(1, FakePlannerApi.step(FakePlannerApi.FIRST_SLOT + 1, "act:study_room", "stat",
                        "在宿舍房间学习", "魅力 +4（升到 Lv.2）、学力 +2（升到 Lv.6） · 1,500 日元", "严户台商店街 · 旧书店「书虫」", null));
                fake.observeAt(name.equals("long") ? "meet:hanged" : "act:study_room");
                break;
            }
            case "unlock-talk":
                fake.plan.steps.set(4, FakePlannerApi.step(FakePlannerApi.FIRST_SLOT + 4, "unlock:sun:0", "unlock", "太阳 · 神木秋成",
                        "去喷泉广场找那个胖乎乎的学生 · 花费 1,000 日元", "桐叶购物中心 · 放纵俱乐部 2 楼", "完成后太阳社群就解锁了"));
                fake.observeAt("unlock:sun:0");
                break;
            case "free": fake.observeAt("free"); break;
            case "empty": fake.observed = null; break;
            case "planning": fake.plan = null; fake.planning = true; break;
            case "error": fake.plan = null; fake.planErrorZh = "生成安排时出了问题，请稍后再试"; break;
            case "unreadable": fake.observed = null; fake.planErrorZh = "游戏资料读取失败，请重新打开应用"; break;
            case "finished": fake.observed.slot = FakePlannerApi.FIRST_SLOT + 20; break;
            case "receipt": {
                fake.observeAt("meet:emperor");
                WidgetPrefs.Receipt r = new WidgetPrefs.Receipt();
                r.eventSeq = 12;
                r.stateRevision = fake.stateRevision;
                r.slot = FakePlannerApi.FIRST_SLOT + 1;
                r.action = "act:study_room";
                r.planId = FakePlannerApi.PLAN_ID;
                r.title = "在宿舍房间学习";
                r.subtitle = "学力 +2";
                r.location = "宿舍 · 自己房间";
                r.kind = "stat";
                r.createdMillis = System.currentTimeMillis();
                WidgetPrefs.setReceipt(app, ID, r);
                break;
            }
            default: throw new IllegalArgumentException(name);
        }
    }

    private View render(boolean night, int width, int height) {
        PlanWidget.Loaded loaded = PlanWidget.load(app, ID);
        CardModel model = loaded.view.toCard(loaded.rules).withNight(night);
        return RenderSupport.layout(app, PlanWidget.card(app, ID, loaded.view, model, width, height), width, height);
    }

    @Test
    public void everyStateRendersCleanlyInBothPalettesAndAllSizes() throws Exception {
        File dir = RenderSupport.outputDir("plan");
        List<String> issues = new ArrayList<>();
        String[] states = {"social", "stat", "job", "unlock", "unlock-talk", "tower", "locked", "story-run", "future", "receipt",
                "note", "episode", "hint", "max-up", "max-meet", "job-side", "long", "long-stat", "free", "empty", "planning", "error",
                "unreadable", "finished"};
        for (String name : states) {
            for (boolean night : new boolean[]{false, true}) {
                for (int[] size : SIZES) {
                    state(name);
                    View root = render(night, size[0], size[1]);
                    String tag = name + "-" + (night ? "night" : "day") + "-" + size[0] + "x" + size[1];
                    RenderSupport.save(root, dir, tag);
                    RenderSupport.collectTextIssues(root, tag, issues);
                    RenderSupport.collectOverflow(root, R.id.widget_plate, tag, issues);
                    RenderSupport.collectHeaderOverlap(root, tag, issues);
                    RenderSupport.collectSqueezedLocation(root, tag, issues);
                    TextView title = root.findViewById(R.id.widget_task);
                    assertFalse(tag + " blank title", title.getText().toString().trim().isEmpty());
                    assertTrue(tag + " title collapsed", title.getHeight() > 0);
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    @Test
    public void largerFontScaleStillFits() throws Exception {
        RuntimeEnvironment.setFontScale(1.15f);
        File dir = RenderSupport.outputDir("plan-font115");
        List<String> issues = new ArrayList<>();
        for (String name : new String[]{"social", "stat", "future", "receipt", "empty", "note", "episode", "hint", "max-meet", "job-side",
                "long", "long-stat", "unlock-talk"}) {
            for (int[] size : SIZES) {
                state(name);
                View root = render(false, size[0], size[1]);
                String tag = name + "-" + size[0] + "x" + size[1];
                RenderSupport.save(root, dir, tag);
                RenderSupport.collectTextIssues(root, tag, issues);
                RenderSupport.collectOverflow(root, R.id.widget_plate, tag, issues);
                RenderSupport.collectHeaderOverlap(root, tag, issues);
                RenderSupport.collectSqueezedLocation(root, tag, issues);
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    @Test
    public void stateCopyIsNaturalChinese() {
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("social", new String[]{"倒悬者 · 舞子", "RANK 4→5", "完成"});
        expected.put("stat", new String[]{"在宿舍房间学习", "学力 +2", "完成"});
        expected.put("future", new String[]{"探索塔尔塔罗斯", "本区块第 2 次", "已经玩到这里"});
        expected.put("receipt", new String[]{"在宿舍房间学习", "学力 +2", "撤销"});
        expected.put("max-up", new String[]{"皇帝 · 小田桐秀利", "RANK 9→MAX", "完成"});
        expected.put("max-meet", new String[]{"倒悬者 · 舞子", "MAX · 见面", "完成"});
        expected.put("job-side", new String[]{"咖啡店打工", "+3,000 日元", "完成"});
        expected.put("locked", new String[]{"满月作战", null, "完成"});
        expected.put("empty", new String[]{"设置目标，从当前存档开始", null, "开始设置"});
        for (Map.Entry<String, String[]> e : expected.entrySet()) {
            state(e.getKey());
            View root = render(false, 320, 200);
            assertEquals(e.getKey(), e.getValue()[0], text(root, R.id.widget_task));
            if (e.getValue()[1] != null) assertEquals(e.getKey(), e.getValue()[1], text(root, R.id.widget_tag));
            else assertEquals(e.getKey(), View.GONE, root.findViewById(R.id.widget_tag).getVisibility());
            assertEquals(e.getKey(), e.getValue()[2], text(root, R.id.button_spec_done_text));
        }
        state("receipt");
        assertEquals("✓ 已记录 · 再点撤销", text(render(false, 320, 200), R.id.widget_detail_cue));
        state("locked");
        View locked = render(false, 320, 200);
        assertFalse("locked items are information only", locked.findViewById(R.id.button_spec_done).isEnabled());
        assertEquals("这段时间不能自由行动", text(locked, R.id.widget_detail_cue));
        state("story-run");
        assertEquals("到11月5日都不能自由行动", text(render(false, 320, 200), R.id.widget_detail_cue));
        state("social");
        View social = render(false, 320, 200);
        assertEquals("11/1", text(social, R.id.widget_date));
        assertEquals("周日", text(social, R.id.widget_weekday));
        assertEquals("白天", text(social, R.id.widget_period_word));
        assertEquals("今天", text(social, R.id.widget_progress));
        state("stat");
        assertEquals("晚上", text(render(true, 320, 200), R.id.widget_period_word));
        state("future");
        assertEquals("4天后", text(render(true, 320, 200), R.id.widget_progress));
    }

    @Test
    public void newStepKindsShowTheirMarkHintAndSideLines() {
        state("note");
        View note = render(true, 320, 200);
        assertEquals("结缘网站的字条 · 山岸风花", text(note, R.id.widget_task));
        assertEquals(View.VISIBLE, note.findViewById(R.id.widget_title_icon).getVisibility());
        assertEquals("RANK 6 · 加好感", text(note, R.id.widget_tag));
        assertEquals("当晚回宿舍用公用电脑给山岸风花加好感", text(note, R.id.widget_hint));
        state("episode");
        View episode = render(false, 320, 200);
        // The glossary's title; the spaces beside the number are non-breaking so 「第 2 话」 never splits.
        assertEquals("羁绊剧情 · 伊织顺平（第\u00A02\u00A0话）", text(episode, R.id.widget_task));
        assertEquals(View.VISIBLE, episode.findViewById(R.id.widget_title_icon).getVisibility());
        state("hint");
        View hint = render(false, 320, 200);
        assertEquals("记得带上「皇帝」阿尔卡那的人格面具", text(hint, R.id.widget_hint));
        assertEquals("顺便：把 20,000 日元交给穿西装的男人", text(hint, R.id.widget_side));
        assertEquals(View.GONE, hint.findViewById(R.id.widget_title_icon).getVisibility());
        // On the smallest card the side step outlives the reminder; the title always stays.
        View small = render(false, 180, 150);
        assertTrue(small.findViewById(R.id.widget_task).getHeight() > 0);
        if (small.findViewById(R.id.widget_hint).getVisibility() == View.VISIBLE) {
            assertEquals(View.VISIBLE, small.findViewById(R.id.widget_side).getVisibility());
        }
        state("social");
        View plain = render(false, 320, 200);
        assertEquals(View.GONE, plain.findViewById(R.id.widget_hint).getVisibility());
        assertEquals(View.GONE, plain.findViewById(R.id.widget_side).getVisibility());
    }

    @Test
    public void statusCardsDimTheDeadCurrentButton() {
        state("planning");
        View planning = render(true, 250, 160);
        TextView current = planning.findViewById(R.id.button_spec_current_text);
        assertFalse(planning.findViewById(R.id.button_spec_current).isEnabled());
        assertTrue("disabled 当前 label is dimmed", android.graphics.Color.alpha(current.getCurrentTextColor()) < 255);
        state("social");
        TextView live = render(true, 250, 160).findViewById(R.id.button_spec_current_text);
        assertEquals(255, android.graphics.Color.alpha(live.getCurrentTextColor()));
    }

    private static String text(View root, int id) {
        return ((TextView) root.findViewById(id)).getText().toString();
    }
}
