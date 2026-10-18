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
                // 11/4 daytime through 11/5 daytime: one card for each day.
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
            case "dialogue":
            case "dialogue-hint": {
                // A rank-up whose scene has guide answers (as the app's 回答 line shows them).
                fake.rules = FakePlannerApi.miniRules();
                fake.rules.link("emperor").dialogue.put(7, java.util.Arrays.asList("我会帮你的", "这样的规矩没有意义"));
                Plan.Step emperor = fake.plan.steps.get(2);
                emperor.entity = "emperor";
                if (name.equals("dialogue-hint")) emperor.hintZh = "记得带上「皇帝」阿尔卡那的人格面具";
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
                        "倒悬者 · 舞子", "休息日邀约 · RANK MAX · 见面（预计不升级）", "应邀外出", "对方发来了休息日邀约，赴约也算一次见面"), 10, 10));
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
                        "倒悬者 · 舞子", "休息日邀约 · 加深关系（未升级）", "月光馆学园 · 1 楼教职员室前走廊", "对方发来了休息日邀约，赴约也算一次见面"), 4, 4);
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
                "unreadable", "finished", "dialogue", "dialogue-hint"};
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
                    RenderSupport.collectWordSplits(root, tag, issues);
                    RenderSupport.collectCutText(root, tag, issues);
                    RenderSupport.collectDotLineEnds(root, tag, issues);
                    RenderSupport.collectLocationEdges(root, tag, issues);
                    RenderSupport.collectHiddenTitle(root, tag, issues);
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
                "long", "long-stat", "unlock-talk", "dialogue", "unreadable", "error"}) {
            for (int[] size : SIZES) {
                state(name);
                View root = render(false, size[0], size[1]);
                String tag = name + "-" + size[0] + "x" + size[1];
                RenderSupport.save(root, dir, tag);
                RenderSupport.collectTextIssues(root, tag, issues);
                RenderSupport.collectOverflow(root, R.id.widget_plate, tag, issues);
                RenderSupport.collectHeaderOverlap(root, tag, issues);
                RenderSupport.collectSqueezedLocation(root, tag, issues);
                RenderSupport.collectWordSplits(root, tag, issues);
                RenderSupport.collectCutText(root, tag, issues);
                RenderSupport.collectDotLineEnds(root, tag, issues);
                RenderSupport.collectLocationEdges(root, tag, issues);
                RenderSupport.collectHiddenTitle(root, tag, issues);
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    @Test
    public void meetingsShowTheGuideAnswersAtEverySize() throws Exception {
        state("dialogue");
        View regular = render(false, 320, 200);
        TextView line = regular.findViewById(R.id.widget_dialogue_choices);
        assertEquals(View.VISIBLE, line.getVisibility());
        // The widget's 「答：」, never the app's 「回答：」 (docs/GLOSSARY_zh.md).
        assertEquals("答：我会帮你的 / 这样的规矩没有意义", CardText.plain(line.getText().toString()));
        assertTrue("「规矩」 is kept whole", line.getText().toString().contains("规" + CardText.WJ + "矩"));
        assertTrue("at most two lines", line.getLineCount() <= CardRenderer.MAX_ANSWER_LINES);
        assertEquals("the outlined day numeral steps aside", View.GONE, regular.findViewById(R.id.widget_watermark).getVisibility());
        assertEquals("RANK 6→7", text(regular, R.id.widget_tag));
        assertEquals(View.VISIBLE, regular.findViewById(R.id.widget_meta_row).getVisibility());
        // The answers are essential: the narrow card keeps them whole too, beside its title.
        View tiny = render(false, 180, 150);
        TextView small = tiny.findViewById(R.id.widget_dialogue_choices);
        assertEquals(View.VISIBLE, small.getVisibility());
        assertEquals("答：我会帮你的 / 这样的规矩没有意义", CardText.plain(small.getText().toString()).replace("\n", ""));
        assertFalse(RenderSupport.ellipsized(small));
        assertEquals("皇帝 · 小田桐秀利", RenderSupport.titleText(tiny.findViewById(R.id.widget_task)));
        // The shortest cards (130dp high) keep both too: the title on one line, the answers beside it.
        File dir = RenderSupport.outputDir("plan");
        List<String> issues = new ArrayList<>();
        for (float scale : new float[]{1f, 1.15f}) {
            RuntimeEnvironment.setFontScale(scale);
            for (int width : new int[]{180, 250}) {
                View shortest = render(false, width, 130);
                String tag = "dialogue-day-" + width + "x130" + (scale > 1 ? "-font115" : "");
                RenderSupport.save(shortest, dir, tag);
                RenderSupport.collectHiddenTitle(shortest, tag, issues);
                RenderSupport.collectTextIssues(shortest, tag, issues);
                RenderSupport.collectCutText(shortest, tag, issues);
                RenderSupport.collectWordSplits(shortest, tag, issues);
                assertEquals(tag, View.VISIBLE, shortest.findViewById(R.id.widget_dialogue_choices).getVisibility());
                assertTrue(tag, text(shortest, R.id.widget_dialogue_choices).startsWith("答："));
                assertEquals(tag, 1, ((TextView) shortest.findViewById(R.id.widget_task)).getLineCount());
            }
        }
        RuntimeEnvironment.setFontScale(1f);
        assertTrue(String.join("\n", issues), issues.isEmpty());
        state("dialogue-hint");
        View hint = render(false, 250, 160);
        assertEquals(View.VISIBLE, hint.findViewById(R.id.widget_dialogue_choices).getVisibility());
        // Steps without guide text (or that are no meetings) never show the line.
        for (String other : new String[]{"social", "stat", "note", "tower"}) {
            state(other);
            assertEquals(other, View.GONE, render(false, 320, 200).findViewById(R.id.widget_dialogue_choices).getVisibility());
        }
    }

    /** Audit: at 180x150 the unreadable-state card cut its instruction short with "…". */
    @Test
    public void statusInstructionsWrapInsteadOfBeingCutShort() {
        Map<String, String> cues = new LinkedHashMap<>();
        cues.put("unreadable", "游戏资料读取失败，请重新打开应用");
        cues.put("error", "生成安排时出了问题，请稍后再试");
        cues.put("empty", "填好进度后，每天点「完成」就行");
        for (Map.Entry<String, String> e : cues.entrySet()) {
            for (int[] size : SIZES) {
                state(e.getKey());
                TextView cue = render(false, size[0], size[1]).findViewById(R.id.widget_detail_cue);
                String tag = e.getKey() + "-" + size[0] + "x" + size[1];
                // The setup card's hint may give way to its title on the smallest card; error instructions never.
                if (e.getKey().equals("empty") && cue.getVisibility() == View.GONE) continue;
                assertEquals(tag, View.VISIBLE, cue.getVisibility());
                assertEquals(tag, e.getValue(), CardText.plain(cue.getText().toString()));
                for (int line = 0; line < cue.getLineCount(); line++) {
                    assertEquals(tag + " ellipsized", 0, cue.getLayout().getEllipsisCount(line));
                }
                if (cue.getLineCount() > 1) {
                    String first = CardText.plain(cue.getText().subSequence(0, cue.getLayout().getLineEnd(0)).toString());
                    assertTrue(tag + " wraps after the comma: " + first, first.endsWith("，"));
                }
            }
        }
    }

    @Test
    public void wrappedNamesStayWhole() {
        state("max-up");
        List<String> issues = new ArrayList<>();
        for (int[] size : SIZES) RenderSupport.collectWordSplits(render(false, size[0], size[1]), "max-up-" + size[0], issues);
        assertTrue(String.join("\n", issues), issues.isEmpty());
        // Narrow enough that 「皇帝 · 小田桐秀利」 may wrap: then at the dot (which goes), never inside the name.
        TextView title = render(false, 180, 150).findViewById(R.id.widget_task);
        assertEquals("皇帝 · 小田桐秀利", RenderSupport.titleText(title));
        if (title.getLineCount() > 1) assertEquals("皇帝 / 小田桐秀利", RenderSupport.lines(title));
    }

    /**
     * Review: 「结缘网站的字条 · / 山岸风花」 at 180x150 and 115% font, the dot left at the end of a line. A
     * title that wraps at its 「 · 」 wraps there without the dot, on the same lines.
     */
    @Test
    public void aTitleThatWrapsAtItsSeparatorDropsTheDot() {
        RuntimeEnvironment.setFontScale(1.15f);
        state("note");
        TextView title = render(false, 180, 150).findViewById(R.id.widget_task);
        assertEquals("结缘网站的字条 / 山岸风花", RenderSupport.lines(title));
        assertEquals("结缘网站的字条 · 山岸风花", RenderSupport.titleText(title));
        // Where it fits one line the dot stays.
        RuntimeEnvironment.setFontScale(1f);
        state("note");
        assertEquals("结缘网站的字条 · 山岸风花", text(render(false, 320, 200), R.id.widget_task));
    }

    /**
     * Review: the note line of the 250x160 card ended in 「花费 1,000 日…」 and 「赴约也算一次见…」. Like the
     * 180 cards it never ends in "…": it keeps its leading whole clauses, or goes.
     */
    @Test
    public void theNoteLineShowsWholeClausesOrGoes() {
        state("unlock-talk");
        assertEquals("去喷泉广场找那个胖乎乎的学生", text(render(true, 250, 160), R.id.widget_detail_cue));
        assertEquals("去喷泉广场找那个胖乎乎的学生 · 花费 1,000 日元", text(render(true, 320, 200), R.id.widget_detail_cue));
        state("max-meet");
        assertEquals("对方发来了休息日邀约，赴约也算一次见面", text(render(false, 250, 160), R.id.widget_detail_cue));
        RuntimeEnvironment.setFontScale(1.15f);
        for (String name : new String[]{"long", "max-meet"}) {
            state(name);
            TextView cue = render(false, 250, 160).findViewById(R.id.widget_detail_cue);
            // Where the card is too short for the line at all (Android 12's taller title) it goes.
            if (cue.getVisibility() == View.VISIBLE) assertEquals(name, "对方发来了休息日邀约", CardText.plain(cue.getText().toString()));
        }
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
        expected.put("locked", new String[]{"满月作战", null, "下一项"});
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
        assertTrue("locked items have nothing to record: the button goes on", locked.findViewById(R.id.button_spec_done).isEnabled());
        assertEquals("这段时间不能自由行动", text(locked, R.id.widget_detail_cue));
        state("story-run");
        assertEquals("这段时间不能自由行动", text(render(false, 320, 200), R.id.widget_detail_cue));
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
        // The glossary's title; the spaces beside the number are non-breaking so 「第 2 话」 never splits,
        // and the name stays whole.
        assertEquals("羁绊剧情 · 伊织顺平（第 2 话）", text(episode, R.id.widget_task));
        String shaped = ((TextView) episode.findViewById(R.id.widget_task)).getText().toString();
        assertTrue(shaped.contains("第\u00A02\u00A0话") && shaped.contains("伊\u2060织\u2060顺\u2060平"));
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

    /** Review: error, finished and unreadable cards showed a whole row of dead, washed-out buttons. */
    @Test
    public void statusCardsShowOneLiveButton() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("planning", "打开应用");
        labels.put("error", "打开应用");
        labels.put("unreadable", "打开应用");
        labels.put("finished", "查看日程");
        for (Map.Entry<String, String> e : labels.entrySet()) {
            for (int[] size : SIZES) {
                for (boolean night : new boolean[]{false, true}) {
                    state(e.getKey());
                    View root = render(night, size[0], size[1]);
                    String tag = e.getKey() + "-" + size[0] + "x" + size[1] + (night ? "-night" : "");
                    assertEquals(tag, View.GONE, root.findViewById(R.id.button_spec_current).getVisibility());
                    View done = root.findViewById(R.id.button_spec_done);
                    assertTrue(tag, done.isEnabled());
                    assertTrue(tag + " the button spans the row", done.getWidth() > root.getWidth() * 0.8f);
                    TextView label = root.findViewById(R.id.button_spec_done_text);
                    assertEquals(tag, e.getValue(), text(root, R.id.button_spec_done_text));
                    assertEquals(tag + " drawn live, not washed out", 255, android.graphics.Color.alpha(label.getCurrentTextColor()));
                }
            }
        }
    }

    /**
     * Review: a story-locked day on the pending item showed a dimmed 当前 beside a dimmed 完成 ✓, a dead
     * row. Like the status cards it has one live button: 下一项, on to what comes after the story.
     */
    @Test
    public void storyLockedItemsShowOneLiveButton() {
        for (String name : new String[]{"locked", "story-run"}) {
            for (int[] size : SIZES) {
                for (boolean night : new boolean[]{false, true}) {
                    state(name);
                    View root = render(night, size[0], size[1]);
                    String tag = name + "-" + size[0] + "x" + size[1] + (night ? "-night" : "");
                    assertEquals(tag, View.GONE, root.findViewById(R.id.button_spec_current).getVisibility());
                    View done = root.findViewById(R.id.button_spec_done);
                    assertTrue(tag, done.isEnabled());
                    assertTrue(tag + " the button spans the row", done.getWidth() > root.getWidth() * 0.8f);
                    assertEquals(tag, "下一项", text(root, R.id.button_spec_done_text));
                    TextView label = root.findViewById(R.id.button_spec_done_text);
                    assertEquals(tag + " drawn live, not washed out", 255, android.graphics.Color.alpha(label.getCurrentTextColor()));
                    assertEquals(tag + " no check mark", View.GONE, root.findViewById(R.id.button_spec_check).getVisibility());
                }
            }
        }
    }

    /** Review: 当前 looked fully enabled on the pending item although ‹ was dimmed (docs/screenshots/widget_home.png). */
    @Test
    public void currentIsDimmedOnThePendingItemLikeTheBackArrow() {
        state("social");
        View pending = render(true, 320, 200);
        assertFalse(pending.findViewById(R.id.widget_prev).isEnabled());
        assertFalse(pending.findViewById(R.id.button_spec_current).isEnabled());
        TextView dim = pending.findViewById(R.id.button_spec_current_text);
        assertTrue("its label is dimmed", android.graphics.Color.alpha(dim.getCurrentTextColor()) < 255);
        assertEquals(CardRenderer.DISABLED_ARROW_ALPHA,
                ((android.widget.ImageView) pending.findViewById(R.id.button_spec_return_icon)).getImageAlpha());
        state("future");
        View browsed = render(true, 320, 200);
        assertTrue(browsed.findViewById(R.id.button_spec_current).isEnabled());
        TextView live = browsed.findViewById(R.id.button_spec_current_text);
        assertEquals(255, android.graphics.Color.alpha(live.getCurrentTextColor()));
        state("receipt");
        assertTrue("the receipt card goes on to the pending item", render(false, 320, 200).findViewById(R.id.button_spec_current).isEnabled());
    }

    /**
     * Review: at small sizes and 115% font the card cut key information short with "…": the chip
     * 「魅力 +4 → Lv.…」 and 「本区块第 2 …」, the place 「奢豪之…」, the error 「请稍…」. The chip never ends
     * in "…" (it wraps if it must), the location shows a shorter whole form or goes, and the
     * instruction of a status card keeps its lines.
     */
    @Test
    public void chipsPlacesAndInstructionsAreNeverCutShort() {
        List<String> issues = new ArrayList<>();
        for (float scale : new float[]{1f, 1.15f}) {
            RuntimeEnvironment.setFontScale(scale);
            for (String name : new String[]{"long-stat", "long", "future", "note", "unlock-talk", "job", "error", "unreadable"}) {
                for (int[] size : SIZES) {
                    state(name);
                    View root = render(false, size[0], size[1]);
                    String tag = name + "-" + size[0] + "x" + size[1] + "@" + scale;
                    RenderSupport.collectCutText(root, tag, issues);
                    TextView cue = root.findViewById(R.id.widget_detail_cue);
                    if (name.equals("error") || name.equals("unreadable")) {
                        if (cue.getVisibility() != View.VISIBLE) issues.add(tag + " lost its instruction");
                        else if (RenderSupport.ellipsized(cue)) issues.add(tag + " cuts its instruction: " + cue.getText());
                    }
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());

        RuntimeEnvironment.setFontScale(1f);
        state("long-stat");
        View narrow = render(false, 180, 150);
        assertEquals("魅力 +4 → Lv.2、学力 +2 → MAX", text(narrow, R.id.widget_tag));
        state("future");
        assertEquals("the floor range goes first", "奢豪之庭 茨尔阿", text(render(true, 250, 160), R.id.widget_location));
        assertEquals("奢豪之庭 茨尔阿（145–172F）", text(render(true, 320, 200), R.id.widget_location));
        RuntimeEnvironment.setFontScale(1.15f);
        state("future");
        View big = render(false, 180, 150);
        assertEquals("本区块第 2 次", text(big, R.id.widget_tag));
        assertEquals("then the area: the block's name stays", "茨尔阿", text(big, R.id.widget_location));
        state("error");
        TextView cue = render(false, 180, 150).findViewById(R.id.widget_detail_cue);
        assertEquals(2, cue.getLineCount());
        assertEquals("生成安排时出了问题，请稍后再试", CardText.plain(cue.getText().toString()));
    }

    /**
     * Review: on 320x200/240 a 25–35% empty band sat between the plate and the title, and the buttons
     * hugged the bottom edge. The text now sits centred between the plate and the buttons at the
     * title's largest size, and the buttons keep more room below.
     */
    @Test
    public void tallCardsCentreTheirTextAndLiftTheButtons() {
        float density = app.getResources().getDisplayMetrics().density;
        for (String name : new String[]{"social", "stat", "hint", "note", "finished"}) {
            for (int[] size : new int[][]{{320, 200}, {320, 240}, {360, 240}}) {
                state(name);
                View root = render(false, size[0], size[1]);
                String tag = name + "-" + size[0] + "x" + size[1];
                TextView title = root.findViewById(R.id.widget_task);
                assertEquals(tag + " title keeps its largest size", CardRenderer.maxTitleSp(WidgetSize.REGULAR),
                        title.getTextSize() / app.getResources().getDisplayMetrics().scaledDensity, 0.01f);
                android.graphics.Rect plate = RenderSupport.boundsIn(root, root.findViewById(R.id.widget_plate));
                android.graphics.Rect titleBox = RenderSupport.boundsIn(root, title);
                float textTop = titleBox.bottom - title.getPaddingBottom() - title.getLayout().getHeight();
                float textBottom = RenderSupport.lastTextBottom(root);
                android.graphics.Rect buttons = RenderSupport.boundsIn(root, root.findViewById(R.id.button_spec_row));
                float above = (textTop - plate.bottom) / density;
                float below = (buttons.top - textBottom) / density;
                assertTrue(tag + " text centred: " + above + "dp above, " + below + "dp below", Math.abs(above - below) <= 3);
                // Cards with room to spare give some to the buttons; full ones (the reminder or note card
                // at 200dp) keep the plain padding.
                float under = (root.getHeight() - buttons.bottom) / density;
                boolean full = (name.equals("hint") || name.equals("note")) && size[1] < 240;
                float least = full ? CardRenderer.contentPaddingDp(WidgetSize.REGULAR) : 13;
                assertTrue(tag + " buttons sit " + under + "dp from the bottom", under >= least);
            }
        }
    }

    /** Review: the outlined 「22」 ran behind the end of the location and reminder lines. */
    @Test
    public void theDayNumeralFadesBehindText() {
        state("hint");
        View busy = render(false, 320, 200);
        int full = WidgetPalette.DAY.watermarkImageAlpha;
        android.widget.ImageView tens = busy.findViewById(R.id.widget_watermark_tens);
        assertEquals(View.VISIBLE, busy.findViewById(R.id.widget_watermark).getVisibility());
        assertEquals("text runs into the numeral", Math.round(full * CardRenderer.FAINT_WATERMARK), tens.getImageAlpha());
        state("social");
        android.widget.ImageView clear = render(false, 320, 200).findViewById(R.id.widget_watermark_tens);
        assertEquals("short text leaves it alone", full, clear.getImageAlpha());
        state("hint");
        android.widget.ImageView night = render(true, 320, 200).findViewById(R.id.widget_watermark_tens);
        assertEquals(Math.round(WidgetPalette.NIGHT.watermarkImageAlpha * CardRenderer.FAINT_WATERMARK), night.getImageAlpha());
    }

    /**
     * Review: before Android 12 the card had square corners (nothing clips the flush-left plate). The
     * corner is transparent on every version.
     */
    @Test
    public void cornersAreRoundOnEveryVersion() throws Exception {
        float density = app.getResources().getDisplayMetrics().density;
        for (int[] size : SIZES) {
            state("social");
            View root = render(false, size[0], size[1]);
            android.graphics.Bitmap bitmap = RenderSupport.draw(root);
            String tag = size[0] + "x" + size[1];
            assertEquals(tag + " top-left corner", 0, android.graphics.Color.alpha(bitmap.getPixel(1, 1)));
            // Beside the plate's top-left corner (6–8dp down), outside the card's rounded corner.
            assertEquals(tag + " plate corner", 0, android.graphics.Color.alpha(bitmap.getPixel(1, Math.round(8 * density))));
            assertEquals(tag + " bottom-right corner", 0,
                    android.graphics.Color.alpha(bitmap.getPixel(bitmap.getWidth() - 2, bitmap.getHeight() - 2)));
            assertTrue(tag + " the card itself is opaque", android.graphics.Color.alpha(bitmap.getPixel(bitmap.getWidth() / 2, 2)) == 255);
        }
    }

    /**
     * The bundled Barlow Condensed files are the upstream ones, unmodified. Android takes a font
     * family's character coverage from its first font: a 「→」 added to the upright files made the
     * italic numerals (which lack it) draw a missing-glyph box instead of falling back to the system
     * arrow on a device (「RANK 5 ▯ 6」 on the Today card).
     */
    @Test
    public void bundledFontsAreTheUnmodifiedBarlowFiles() throws Exception {
        String[][] pinned = {
                {"barlow_condensed_bold.ttf", "e476562ec9c1e16cf16475895b511f08c804f438cc9a9f80a44ea50a0eeb5b65"},
                {"barlow_condensed_semibold.ttf", "7b619d14bc2327509a9ef32b0890f709626f7ecc9ff61191c2a4314c5499d2d9"},
                {"barlow_condensed_bolditalic.ttf", "d8694f65cd70c3e5e600ead37657aab0adb5f63b275d0c1f6f4a7046de222145"},
                {"barlow_condensed_extrabolditalic.ttf", "331783dae9ec0c398f4d32c9fd5a56d4c602caa0c1628c2b9cbfe93f48eb68c9"},
        };
        for (String[] font : pinned) {
            java.io.File file = null;
            for (String dir : new String[]{"src/main/res/font/", "app/src/main/res/font/", "android/app/src/main/res/font/"}) {
                if (new java.io.File(dir + font[0]).isFile()) file = new java.io.File(dir + font[0]);
            }
            assertTrue(font[0], file != null);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file.toPath()));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            assertEquals(font[0], font[1], hex.toString());
        }
    }

    /** The text a reader sees (the invisible line-break marks removed). */
    private static String text(View root, int id) {
        return CardText.plain(((TextView) root.findViewById(id)).getText().toString());
    }
}
