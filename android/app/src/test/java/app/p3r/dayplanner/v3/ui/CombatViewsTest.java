package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

/** 360dp encounter cards and complete nine-attribute charts, including secondary-effect immunity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CombatViewsTest extends UiTestBase {
    @Test public void multiEnemyCardShowsCountsAndWeaknessesAndTheDetailKeepsAllNineAttributesVisible() {
        Rules.CombatTip guardian = combat("guardian.36", "guardian", "36层守关战", 36, 6, 13);
        guardian.enemies.add(enemy("欺骗的巨轮", 1, "-", "s", "r", "w", "n", "-", "t", "*", "d"));
        guardian.enemies.add(enemy("魔法手", 2, "w", "-", "-", "n", "w", "-", "-", "z", "*"));
        guardian.tacticsZh = "先打倒魔法手，再集中攻击巨轮。";
        Rules.CombatTip later = combat("guardian.136", "guardian", "136层守关战", 136, 10, 1);
        api.rules.reminders.combatTips.add(later);
        api.rules.reminders.combatTips.add(guardian);
        HomeActivity a = battle();
        List<String> ids = resultIds(a);
        assertTrue(ids.toString(), ids.indexOf("combat.catalogue.guardian.36") < ids.indexOf("combat.catalogue.guardian.136"));
        search(a, "36F");
        View row = row(a, "combat.catalogue.guardian.36");
        String card = Shots.allText(row);
        assertTrue(card, card.contains("魔法手 ×2"));
        assertTrue(card, card.contains("弱点：火"));
        assertTrue(card, card.contains("弱点：斩、冰、光"));
        Shots.shot(a, "combat_multi_enemy_card");
        assertTrue(row.performClick());
        Shots.idle();
        View detail = a.sheets().content();
        assertEquals("弱", label(detail, 0, 3));
        assertEquals("反", label(detail, 0, 2));
        assertEquals("吸", label(detail, 0, 8));
        assertEquals("弱†", label(detail, 1, 7));
        assertTrue(Shots.allText(detail).contains("先打倒魔法手，再集中攻击巨轮。"));
        Shots.shot(a, "combat_multi_enemy_matrix");
        HorizontalScrollView chart = detail.findViewWithTag("combat_affinity_scroll");
        assertNotNull(chart);
        assertTrue("all nine attributes fit the normal 360dp sheet", chart.getChildAt(0).getWidth() <= chart.getWidth() + 1);
        TextView dark = detail.findViewWithTag("combat_affinity_0_8");
        assertTrue("dark affinity is visible initially", dark.getRight() <= chart.getWidth() + 1);
        assertTrue(api.calls.isEmpty());
    }

    @Test public void storyOperationsIncludeAwakeningBothNovemberFightsAndFinaleInDateOrder() {
        Rules.CombatTip awakening = combat("awakening", "story", "觉醒", 0, 4, 9);
        awakening.shortZh = "按剧情推进";
        awakening.tacticsZh = "按剧情完成觉醒。";
        Rules.CombatTip may = combat("may", "full_moon", "女教皇", 0, 5, 9);
        may.enemies.add(enemy("女教皇", 1, "-", "-", "-", "v", "n", "*", "-", "*", "*"));
        may.tacticsZh = "集中攻击首领，留意战斗倒计时。";
        Rules.CombatTip novemberFirst = combat("november.first", "story", "塔卡亚与阵", 0, 11, 3);
        Rules.CombatTip novemberSecond = combat("november.second", "full_moon", "倒悬者", 0, 11, 3);
        Rules.CombatTip finale = combat("finale", "final", "最终决战", 0, 1, 31);
        api.rules.reminders.combatTips.addAll(Arrays.asList(finale, novemberFirst, novemberSecond, may, awakening));
        HomeActivity a = battle();
        assertEquals(Arrays.asList("combat.catalogue.awakening", "combat.catalogue.may",
            "combat.catalogue.november.first", "combat.catalogue.november.second", "combat.catalogue.finale"), resultIds(a));
        assertTrue(allRows(a).contains("剧情作战"));
        Shots.shot(a, "combat_story_operations");
        assertTrue(row(a, "combat.catalogue.may").performClick());
        Shots.idle();
        assertTrue(Shots.allText(a.sheets().content()).contains("5月9日"));
        assertTrue(Shots.allText(a.sheets().content()).contains("不触发弱点"));
        Shots.shot(a, "combat_fullmoon_matrix");
        Shots.click(a, "知道了");
        assertTrue(row(a, "combat.catalogue.awakening").performClick());
        Shots.idle();
        assertNull(a.sheets().content().findViewWithTag("combat_affinity_scroll"));
        assertTrue(Shots.allText(a.sheets().content()).contains("按剧情完成觉醒。"));
        assertTrue(api.calls.isEmpty());
    }

    @Test public void monadChartSeparatesNoWeaknessDoubleDamageReflectDrainAndSecondaryImmunity() {
        Rules.CombatTip passage = combat("monad.passage.91", "monad", "91层战斗", 91, 8, 1);
        passage.encounterType = "passage";
        passage.enemies.add(enemy("守护者", 1, "s", "t", "n", "v", "*", "r", "d", "t*", "-"));
        passage.tacticsZh = "用万能攻击，留意蓄力。";
        passage.detailZh = "这段旧抗性正文不应重复显示。";
        Rules.CombatTip door = combat("monad.door.77", "monad", "77层战斗", 77, 8, 1);
        door.encounterType = "door";
        api.rules.reminders.combatTips.addAll(Arrays.asList(passage, door));
        HomeActivity a = battle();
        assertTrue(allRows(a).contains("蒙纳多通道"));
        assertTrue(allRows(a).contains("蒙纳多之门"));
        search(a, "91层");
        View card = row(a, "combat.catalogue.monad.passage.91");
        String text = Shots.allText(card);
        assertTrue(text, text.contains("无弱点"));
        assertTrue(text, text.contains("火伤害 ×2"));
        assertFalse(text, text.contains("弱点：火"));
        assertTrue(card.performClick());
        Shots.idle();
        View detail = a.sheets().content();
        assertEquals("耐", label(detail, 0, 0));
        assertEquals("强耐", label(detail, 0, 1));
        assertEquals("无", label(detail, 0, 2));
        assertEquals("×2", label(detail, 0, 3));
        assertEquals("—*", label(detail, 0, 4));
        assertEquals("反", label(detail, 0, 5));
        assertEquals("吸", label(detail, 0, 6));
        assertEquals("强耐*", label(detail, 0, 7));
        TextView secondary = detail.findViewWithTag("combat_affinity_0_4");
        assertTrue(secondary.getContentDescription().toString().contains("普通伤害，副效果无效"));
        TextView reflect = detail.findViewWithTag("combat_affinity_0_5");
        TextView drain = detail.findViewWithTag("combat_affinity_0_6");
        assertEquals(Ui.col(a, R.color.combat_reflect), reflect.getCurrentTextColor());
        assertEquals(Ui.col(a, R.color.p3r_good), drain.getCurrentTextColor());
        String body = Shots.allText(detail);
        assertFalse(body, body.contains("旧抗性正文"));
        assertTrue(body, body.contains("用万能攻击，留意蓄力。"));
        assertTrue(body, body.contains("不触发弱点"));
        Shots.shot(a, "combat_monad_affinity_semantics");
        assertTrue(api.calls.isEmpty());
    }

    @Test public void realFinalBossKeepsAllFourteenPhasesWithoutCallingThemFourteenEnemies() throws Exception {
        Rules.CombatTip finalBoss = null;
        for (Rules.CombatTip combat : realRules().reminders.combatTips) {
            if ("combat.adamah_final.264".equals(combat.id)) finalBoss = combat;
        }
        assertNotNull(finalBoss);
        assertEquals(14, finalBoss.enemies.size());
        api.rules.reminders.combatTips.add(finalBoss);
        HomeActivity a = battle();
        View card = row(a, "combat.catalogue.combat.adamah_final.264");
        String text = Shots.allText(card);
        assertTrue(text, text.contains("阵容／阶段"));
        assertFalse(text, text.contains("×14"));
        assertFalse(text, text.contains("14只"));
        assertTrue(card.performClick());
        Shots.idle();
        View detail = a.sheets().content();
        assertNotNull(detail.findViewWithTag("combat_affinity_13_8"));
        Shots.shot(a, "combat_real_final_boss_phases");
        HorizontalScrollView chart = detail.findViewWithTag("combat_affinity_scroll");
        assertTrue("all nine affinities fit alongside wrapped phase names", chart.getChildAt(0).getWidth() <= chart.getWidth() + 1);
    }

    private static Rules realRules() throws Exception {
        for (String name : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            Path path = Path.of(name);
            if (Files.isRegularFile(path)) return RulesLoader.parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        }
        throw new IllegalStateException("rule pack not found");
    }

    private HomeActivity battle() {
        api.snap.plan = null;
        api.snap.planCurrent = false;
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "tips");
        HomeActivity a = home(intent);
        Shots.click(a, "全部");
        a.getWindow().getDecorView().findViewWithTag("tips_filter_button").performClick();
        Shots.idle();
        assertTrue(Shots.clickTarget(Shots.find(a.sheets().content(), "战斗")).performClick());
        Shots.idle();
        assertTrue(Shots.clickTarget(Shots.find(a.sheets().content(), "应用")).performClick());
        Shots.idle();
        assertFalse(a.sheets().isShowing());
        return a;
    }

    private static void search(HomeActivity a, String query) {
        EditText field = a.getWindow().getDecorView().findViewWithTag("tips_search");
        field.setText(query);
        Shots.idle();
    }

    private static String label(View root, int enemy, int attribute) {
        TextView cell = root.findViewWithTag("combat_affinity_" + enemy + "_" + attribute);
        assertNotNull(cell);
        return Zh.plain(cell.getText());
    }

    private static Rules.CombatTip combat(String id, String kind, String title, int floor, int month, int day) {
        Rules.CombatTip combat = new Rules.CombatTip();
        combat.id = id;
        combat.kind = kind;
        combat.titleZh = title;
        combat.floor = floor;
        combat.fromDay = Fixtures.day(month, day);
        combat.shortZh = "集中攻击首领";
        combat.tacticsZh = "留意蓄力攻击。";
        return combat;
    }

    private static Rules.CombatEnemy enemy(String name, int count, String... affinities) {
        Rules.CombatEnemy enemy = new Rules.CombatEnemy();
        enemy.nameZh = name;
        enemy.count = count;
        enemy.affinities = affinities;
        return enemy;
    }

    private static View row(HomeActivity a, String id) {
        ListView list = list(a.getWindow().getDecorView());
        assertNotNull(list);
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.reminder != null && id.equals(item.reminder.id)) return list.getAdapter().getView(i, null, list);
            if (item.group != null) for (app.p3r.dayplanner.v3.engine.Plan.Reminder entry : item.group.entries) {
                if (id.equals(entry.id)) return list.getAdapter().getView(i, null, list).findViewWithTag("tips_entry_" + id);
            }
        }
        throw new AssertionError("missing combat " + id);
    }

    private static List<String> resultIds(HomeActivity a) {
        List<String> ids = new ArrayList<>();
        ListView list = list(a.getWindow().getDecorView());
        assertNotNull(list);
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            TipsPage.Item item = (TipsPage.Item) list.getAdapter().getItem(i);
            if (item.reminder != null) ids.add(item.reminder.id);
            if (item.group != null) for (app.p3r.dayplanner.v3.engine.Plan.Reminder entry : item.group.entries) ids.add(entry.id);
        }
        return ids;
    }

    private static String allRows(HomeActivity a) {
        ListView list = list(a.getWindow().getDecorView());
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < list.getAdapter().getCount(); i++) text.append(Shots.allText(list.getAdapter().getView(i, null, list)));
        return text.toString();
    }

    private static ListView list(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof ListView) return (ListView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView found = list(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
