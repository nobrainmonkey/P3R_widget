package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

/** 社群 tab 3.6: sub-tabs, tarot tiles, 社群详情, 目标顺序, 羁绊剧情 rows and the RANK pad. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LinksPageTest extends UiTestBase {
    private static final String PRIESTESS = "女教皇 · 山岸风花，RANK 5 → 9，时间先给了皇帝";
    private static final String EMPEROR = "皇帝 · 小田桐秀利，RANK 6 → MAX，11月20日 升到 MAX";

    private HomeActivity links() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "links");
        return home(intent);
    }

    @Test
    public void detailSheetSaysWhyAGoalFallsShortAndOffersTheOrder() {
        HomeActivity a = links();
        Shots.click(a, PRIESTESS);
        assertTrue(a.sheets().isShowing());
        assertTrue(Shots.has(a, "RANK 5 → 9"));
        assertTrue(Shots.has(a, "时间先给了皇帝。想先冲它，就排到皇帝前面"));
        assertTrue(Shots.find(a, "女教皇 目标：想要").isSelected());
        assertFalse("no save or cancel: every control saves at once", Shots.has(a, "保存") || Shots.has(a, "取消"));
        Shots.shot(a, "links_detail_traded");
        Shots.click(a, "调整顺序 ›");
        assertTrue(a.sheets().isShowing());
        assertTrue(Shots.has(a, "目标顺序"));
        assertTrue(Shots.has(a, "必须"));
        assertTrue(Shots.has(a, "想要"));
        Shots.shot(a, "links_order");
    }

    @Test
    public void detailSheetOfAReachedGoalAndOfAStoryLink() {
        HomeActivity a = links();
        Shots.click(a, EMPEROR);
        assertTrue(Shots.has(a, "11月20日 升到 MAX"));
        assertFalse("not romanceable", Shots.has(a, "回答按哪条路线？"));
        Shots.shot(a, "links_detail_reach");
        a.sheets().dismiss();
        Shots.idle();
        Shots.click(a, "愚者 · 特别课外活动部，RANK 7，剧情自动推进");
        assertTrue(Shots.has(a, "这个社群跟着剧情推进"));
        assertFalse("a story link has no goal to pick", Shots.has(a, "愚者 目标：未选"));
    }

    @Test
    public void aRescueToRecordIsAskedAndLeadsToEditProgress() {
        api.snap.observed.rescued = 0;
        HomeActivity a = links();
        assertTrue(Shots.has(a, "救出北村文吉了吗？"));
        View tile = described(a.getWindow().getDecorView(), "教皇 · 北村文吉、光子，");
        assertNotNull(tile);
        assertTrue(tile.getContentDescription().toString().endsWith("，救出北村文吉了吗？"));
        assertTrue(tile.performClick());
        Shots.idle();
        assertTrue(Shots.has(a, "救出北村文吉了吗？"));
        assertTrue(Shots.has(a, "去修改进度"));
        Shots.click(a, "去修改进度");
        assertFalse(a.sheets().isShowing());
        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("edit", started.getStringExtra(Intents.EXTRA_STATE_MODE));
    }

    @Test
    public void beforeSetupOneBannerInsteadOfEmptyForecasts() {
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        HomeActivity a = links();
        assertTrue(Shots.has(a, LinksPage.SETUP_FIRST));
        assertFalse("no rank block before there is a record", Shots.has(a, "未解锁"));
        String all = Shots.allText(a.getWindow().getDecorView());
        assertFalse(all, all.contains("安排排好后显示"));
        assertFalse(all, all.contains("预计"));
        Shots.shot(a, "links_setup");
        Shots.click(a, "开始设置");
        Intent started = shadowOf(a).getNextStartedActivity();
        assertEquals("onboarding", started.getStringExtra(Intents.EXTRA_STATE_MODE));
    }

    @Test
    public void whilePlanningTheStripeRunsAndTheTileStaysOnThePage() {
        api.snap.planning = true;
        HomeActivity a = links();
        List<View> stripes = new ArrayList<>();
        collect(a.getWindow().getDecorView(), BusyStripe.class, stripes);
        assertEquals(1, stripes.size());
        Shots.shot(a, "links_planning");
    }

    @Test
    public void goalStripListsTheGoalsInOrderAndOpensTheOrderSheet() {
        HomeActivity a = links();
        assertTrue(Shots.has(a, "目标顺序：1 皇帝、2 恋爱、3 太阳、4 女教皇、5 恶魔"));
        Shots.click(a, "目标顺序：1 皇帝、2 恋爱、3 太阳、4 女教皇、5 恶魔");
        assertTrue(Shots.has(a, "上移 恋爱"));
        // Without goals: one line, no order button.
        api.snap.profile = new Profile();
        java.util.Arrays.fill(api.snap.profile.tier, Profile.Tier.NONE);
        a.sheets().dismiss();
        a.reload();
        Shots.idle();
        assertTrue(Shots.has(a, LinksPage.NO_GOALS));
        assertFalse(Shots.has(a, "调整顺序"));
    }

    @Test
    public void episodesSubTabIsRememberedAndShowsTheRewardWithTheEnglishName() throws Exception {
        Rules rules = readRealRules();
        api.rules = rules;
        api.snap.observed = new SimState(rules.blocks.length, rules.activities.length);
        api.snap.observed.slot = Fixtures.SLOT_NOW;
        api.snap.observed.episodes = new int[rules.episodes.length];
        api.snap.observed.episodes[0] = 2;
        api.snap.plan = null;
        HomeActivity a = links();
        Shots.click(a, "羁绊剧情");
        assertTrue(Shots.has(a, "看完可以合体「苏尔特尔」Surt"));
        assertTrue(Shots.has(a, "看完可以合体「地狱天使」Hell Biker"));
        assertTrue(Shots.has(a, "2/5"));
        Shots.capture(a, false);
        // The Persona's English name is deliberate (guide lookups).
        Shots.checkTypography(a.getWindow().getDecorView(), "links_episodes_real", true);
        Shots.save(Shots.capture(a, false), "links_episodes_real");
        HomeActivity again = links();
        assertTrue("the last sub-tab is remembered", Shots.has(again, "看完可以合体「荷鲁斯」Horus"));
    }

    @Test
    public void personaNamesMatchTheCompendium() throws Exception {
        String json = read("p3r_compendium.json");
        for (String zh : new String[]{"苏尔特尔", "荷鲁斯", "白虎", "米迦勒", "地狱天使"}) {
            Matcher m = Pattern.compile("\"nameZh\"\\s*:\\s*\"" + zh + "\"[^}]*?\"nameEn\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            Matcher r = Pattern.compile("\"nameEn\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"nameZh\"\\s*:\\s*\"" + zh + "\"").matcher(json);
            String en = m.find() ? m.group(1) : r.find() ? r.group(1) : null;
            assertEquals(zh, en, LinksPage.personaEn(zh));
        }
        Rules rules = readRealRules();
        for (Rules.Episode track : rules.episodes) {
            String line = LinksPage.rewardLine(track);
            assertNotNull(track.personZh, line);
            assertTrue(line, line.matches("看完可以合体「[^」]+」[A-Za-z ]+"));
        }
    }

    @Test
    public void rankPadAdvancesFlashesUnknownInGoldAndCloses() {
        HomeActivity a = links();
        int[] ranks = new int[api.rules.links.length];
        List<Integer> order = Arrays.asList(1, 2, 3);
        List<String> picks = new ArrayList<>();
        RankPad pad = RankPad.show(a, api.rules, order, 1, new RankPad.Listener() {
            @Override
            public void onPicked(int link, int rank) {
                ranks[link] = rank;
                picks.add(link + ":" + rank);
            }

            @Override
            public int currentRank(int link) {
                return link == 2 ? StateDraft.UNKNOWN : ranks[link];
            }
        });
        Shots.idle();
        View root = pad.dialog().getWindow().getDecorView();
        assertTrue(Shots.has(a, "魔术师") || !Shots.findAll(root, "魔术师").isEmpty());
        assertTrue(Shots.findAll(root, "对照游戏里的社群列表，选完会自动跳到下一个。").isEmpty());
        assertFalse(Shots.findAll(root, RankPad.UNKNOWN_LABEL).isEmpty());
        assertFalse(Shots.findAll(root, "MAX").isEmpty());
        assertTrue(Shots.findAll(root, "RANK MAX").isEmpty());
        Shots.clickInDialog("4");
        assertEquals(Arrays.asList("1:4"), picks);
        assertFalse(Shots.findAll(root, "女教皇").isEmpty());
        // 女教皇 is 不记得: the 「?」 key is the selected one, in gold.
        TextView unknown = (TextView) Shots.findAll(root, RankPad.UNKNOWN_LABEL).get(0);
        assertEquals(Ui.col(a, app.p3r.dayplanner.R.color.p3r_navy_950), unknown.getCurrentTextColor());
        Shots.save(Shots.capture(a, true), "links_rankpad");
        Shots.clickInDialog("上一个");
        assertFalse(Shots.findAll(root, "魔术师").isEmpty());
        Shots.clickInDialog("4");
        Shots.clickInDialog(RankPad.UNKNOWN_LABEL);
        Shots.clickInDialog("MAX");
        assertEquals(Arrays.asList("1:4", "1:4", "2:" + StateDraft.UNKNOWN, "3:10"), picks);
        assertFalse("closes after the last link", pad.dialog().isShowing());
        assertTrue(ShadowDialog.getLatestDialog() == pad.dialog());
    }

    /** The first visible view whose content description starts with {@code prefix}. */
    private static View described(View v, String prefix) {
        if (v.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (v.getContentDescription() != null && Zh.plain(v.getContentDescription()).startsWith(prefix)) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = described(g.getChildAt(i), prefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collect(View v, Class<?> type, List<View> out) {
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (type.isInstance(v)) {
            out.add(v);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), type, out);
            }
        }
    }

    private static String read(String asset) throws Exception {
        for (String root : new String[]{"src/main/assets/", "app/src/main/assets/", "android/app/src/main/assets/"}) {
            File file = new File(root + asset);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(asset + " missing");
    }

    private static Rules readRealRules() throws Exception {
        return RulesLoader.parse(read("p3r/p3r_rules.json"));
    }
}
