package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
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

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Regression tests for the partially met requirements of the independent audit
 * (.toolchain/reports/audit.json) that belong to the ui module: per-block Tartarus settings,
 * 平安夜, the 朋友 / 恋人 route, onboarding "?" ranks, a recorded 没救出, the Tartarus result sheet,
 * wording, the stable step counter and CJK line breaks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AuditFixesTest extends UiTestBase {
    private static final int NOW = Fixtures.SLOT_NOW;

    private HomeActivity homeOn(String tab) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, tab);
        return home(intent);
    }

    private static void jumpTo(StateActivity a, StateSteps.Page page) {
        for (int i = 0; i < 14 && a.currentPage() != page; i++) {
            Shots.click(a, "下一步");
        }
        assertEquals(page, a.currentPage());
    }

    private Profile lastProfile() {
        FakePlannerApi.Call call = api.last();
        assertNotNull(call);
        assertEquals("saveProfile", call.method);
        return call.profile;
    }

    private static Stepper stepper(HomeActivity a, String description) {
        return (Stepper) Shots.find(a, description);
    }

    // ------------------------------------------------------------------ per-block Tartarus settings

    @Test
    public void towerSettingsListEveryBlockWithItsOwnSteppers() {
        HomeActivity a = homeOn("me");
        Shots.click(a, "规划设置");
        Shots.click(a, "每个区块去几次");
        assertEquals(2, a.pageDepth());
        for (Rules.Block b : api.rules.blocks) {
            assertTrue(b.zh, Shots.has(a, b.zh));
            assertTrue(b.zh, Shots.has(a, TowerSettingsPage.blockLine(b)));
        }
        assertTrue(Shots.has(a, "2–22F · 4月20日–6月12日"));
        assertTrue(Shots.has(a, "舞子在10月21日–11月2日失踪，救人也算一次探索。"));
        // Every block starts at the defaults (想去 2, 最多 4).
        assertEquals(2, stepper(a, "提齐亚（69–117F）想去几次").getValue());
        assertEquals(4, stepper(a, "提齐亚（69–117F）最多几次").getValue());
        Shots.shot(a, "home_settings_tower");

        // 想去 above 最多 raises 最多 for that block only.
        stepper(a, "提齐亚（69–117F）想去几次").step(+3);
        Profile p = lastProfile();
        assertEquals(Integer.valueOf(5), p.towerVisitsByBlock.get("tziah"));
        assertEquals(5, p.capFor("tziah"));
        assertEquals(5, p.visitsFor("tziah"));
        assertEquals(4, p.capFor("arqa"));
        assertEquals(2, p.visitsFor("arqa"));
        assertEquals(5, stepper(a, "提齐亚（69–117F）最多几次").getValue());
        assertTrue(Shots.has(a, "恢复默认"));

        // 最多 below 想去 lowers 想去; 0 is allowed.
        stepper(a, "台伯勒（2–22F）最多几次").step(-4);
        p = lastProfile();
        assertEquals(0, p.capFor("thebel"));
        assertEquals(0, p.visitsFor("thebel"));
        assertEquals(0, stepper(a, "台伯勒（2–22F）想去几次").getValue());

        // The default moves every block without values of its own.
        stepper(a, "所有区块默认想去几次").step(+1);
        p = lastProfile();
        assertEquals(3, p.towerVisitsPerBlock);
        assertEquals(3, p.visitsFor("arqa"));
        assertEquals(0, p.visitsFor("thebel"));
        assertEquals(5, p.visitsFor("tziah"));
        assertEquals(3, stepper(a, "阿尔卡（23–43F）想去几次").getValue());
        Shots.shot(a, "home_settings_tower_edited");

        // 恢复默认 drops a block's own values.
        Shots.click(a, "恢复默认");
        p = lastProfile();
        assertFalse(TowerSettingsPage.ownValues(p, "thebel"));
        assertEquals(3, p.visitsFor("thebel"));
        assertEquals(4, p.capFor("thebel"));
    }

    @Test
    public void towerCapsPerBlockAppearOnTodayTheWizardAndTheSheet() {
        api.snap.profile.towerCapByBlock.put("tziah", 6);
        HomeActivity a = home();
        assertTrue(Shots.has(a, "提齐亚 · 去过 1/6 次"));
        StateActivity s = stateController("edit").get();
        jumpTo(s, StateSteps.Page.TOWER);
        assertTrue(Shots.has(s, "这个区块最多去 6 次，可在「规划设置 → 塔尔塔罗斯」里修改。"));
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        api.snap.observed.slot = slot;
        ResultActivity r = result(slot, "tower:3", Intents.MODE_RESULT);
        assertTrue(Shots.has(r, "本区块第 2 次 · 上限 6 次"));
    }

    // ------------------------------------------------------------------ 平安夜

    @Test
    public void settingsChristmasListsRomanceableLinksAtRankOneOrAbove() {
        HomeActivity a = homeOn("me");
        Shots.click(a, "规划设置");
        assertTrue(Shots.has(a, "12月24日的邀约"));
        assertTrue(Shots.has(a, "都婉拒了"));
        for (String person : new String[]{"山岸风花", "桐条美鹤", "岳羽由加莉", "伏见千寻", "西胁结子"}) {
            assertTrue(person, Shots.has(a, "接受了" + person + "的邀约"));
        }
        assertFalse("story link / RANK 0", Shots.has(a, "接受了埃癸斯的邀约"));
        assertFalse("not romanceable", Shots.has(a, "接受了友近健二的邀约"));
        Shots.click(a, "接受了山岸风花的邀约");
        assertEquals("priestess", lastProfile().christmasLink);
        Shots.capture(a, false);
        android.widget.ScrollView scroll = lastScroll(a.getWindow().getDecorView(), null);
        View card = Shots.find(a, "12月24日的邀约");
        scroll.scrollTo(0, Math.max(0, top(card) - 200));
        Shots.shot(a, "home_settings_christmas");
        Shots.click(a, "都婉拒了");
        assertNull(lastProfile().christmasLink);
    }

    @Test
    public void wizardAsksAboutChristmasInEditModeAndSavesIt() {
        StateActivity a = stateController("edit").get();
        jumpTo(a, StateSteps.Page.LINKS);
        assertTrue(Shots.has(a, "12月24日的邀约"));
        Shots.click(a, "接受了岳羽由加莉的邀约");
        assertEquals("lovers", a.draft().christmasLink());
        jumpTo(a, StateSteps.Page.CONFIRM);
        assertTrue(Shots.has(a, "平安夜"));
        assertTrue(Shots.has(a, "接受了岳羽由加莉的邀约"));
        assertTrue(Shots.has(a, "将更新 1 项"));
        Shots.shot(a, "state_edit_christmas_confirm");
        Shots.click(a, "保存并重新安排");
        FakePlannerApi.Call call = api.last();
        assertEquals("saveCheckpoint", call.method);
        assertNotNull("the profile goes with the checkpoint", call.profile);
        assertEquals("lovers", call.profile.christmasLink);
    }

    @Test
    public void syncAsksAboutChristmasOnlyFromDecember20() {
        StateActivity a = stateController("sync").get();
        Shots.click(a, "否");
        assertEquals(StateSteps.Page.DATE, a.currentPage());
        jumpTo(a, StateSteps.Page.LINKS);
        assertFalse(Shots.has(a, "12月24日的邀约"));

        api.calls.clear();
        RuntimeEnvironment.getApplication().getSharedPreferences(DraftStore.PREFS, 0).edit().clear().commit();
        StateActivity b = stateController("sync").get();
        Shots.click(b, "否");
        b.draft().setSlot(Rules.slot(Fixtures.day(12, 21), false));
        jumpTo(b, StateSteps.Page.LINKS);
        assertTrue(Shots.has(b, "12月24日的邀约"));
        Shots.capture(b, false);
        lastScroll(b.getWindow().getDecorView(), null).scrollTo(0, 100_000);
        Shots.shot(b, "state_sync_christmas");
    }

    @Test
    public void anAcceptedInvitationReadsAsADate() {
        Plan.Step date = new Plan.Step();
        date.slot = Rules.slot(Fixtures.day(12, 24), false);
        date.action = "locked:" + SlotRows.CHRISTMAS_LOCK;
        date.kind = "locked";
        date.titleZh = "";
        assertEquals("平安夜约会", SlotRows.title(api.rules, date));
        List<String> global = Assumptions.global(Fixtures.profileMid(), api.rules);
        assertTrue(global.contains("12月24日按都婉拒了邀约安排，可在「规划设置 → 平安夜」里改。"));
        Profile accepted = Fixtures.profileMid();
        accepted.christmasLink = "priestess";
        assertTrue(Assumptions.global(accepted, api.rules)
            .contains("12月24日接受了山岸风花的邀约：这天白天和晚上都留给约会，不安排别的事。"));
    }

    // ------------------------------------------------------------------ 朋友 / 恋人 route

    @Test
    public void romanceableCardsOfferTheRouteAndStoryLinksHaveNoTierChip() {
        HomeActivity a = homeOn("links");
        // The route is chosen in 社群详情, not repeated on every romanceable tile.
        assertFalse(Shots.has(a, "女教皇 路线：朋友"));
        assertFalse("story links have no tier chip", Shots.has(a, "愚者 目标：未选，点按切换"));
        assertTrue(Shots.has(a, "剧情自动推进"));
        Shots.click(a, "皇帝 · 小田桐秀利，RANK 6 → MAX，11月20日 升到 MAX");
        assertTrue(a.sheets().isShowing());
        assertFalse("not romanceable", Shots.has(a, "皇帝 路线：朋友"));
        a.sheets().dismiss();
        Shots.idle();
        Shots.click(a, "愚者 · 特别课外活动部，RANK 7，剧情自动推进");
        assertTrue(Shots.has(a, "这个社群跟着剧情推进"));
        assertFalse("no goal for a story link", Shots.has(a, "愚者 目标：未选"));
        a.sheets().dismiss();
        Shots.idle();
        Shots.click(a, "女教皇 · 山岸风花，RANK 5 → 9，时间先给了皇帝");
        assertTrue(Shots.has(a, "回答按哪条路线？"));
        assertTrue("default is 朋友", Shots.find(a, "女教皇 路线：朋友").isSelected());
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("安排不会要求你走恋爱路线"));
        Shots.click(a, "女教皇 路线：恋人");
        Profile p = lastProfile();
        assertEquals(Profile.Route.ROMANCE, p.route[2]);
        assertEquals(Profile.Route.UNDECIDED, p.route[6]);
        // The tile says 恋人 once the route is chosen.
        a.sheets().dismiss();
        Shots.idle();
        assertTrue(Shots.has(a, "恋人"));
    }

    @Test
    public void linkCardsUseOneDateFormatAndNameLockedLinks() {
        HomeActivity a = homeOn("links");
        // One 「预计」 on the page (the MAX count); the tiles' dates need none.
        assertTrue(Shots.has(a, "11月20日 升到 MAX"));
        assertEquals(1, Shots.findAll(a.getWindow().getDecorView(), "预计").size());
        // 恶魔: not unlocked yet, planned to RANK 7 (said once, on the tile's description).
        assertTrue(Shots.has(a, "恶魔 · 田中社长，未解锁 → RANK 7"));
        String all = Shots.allText(a.getWindow().getDecorView());
        assertFalse(all, all.contains("RANK 0"));
        assertFalse(all, all.contains("11/20"));
        assertFalse(all, all.contains("时间不够"));
        assertFalse(all, all.contains("这次的安排里"));
        assertFalse(all, all.contains("暂时不安排"));
        assertTrue(all, all.contains("时间先给了皇帝"));
        // Person names only: no gender label on the tiles.
        assertTrue(all, all.contains("特别课外活动部"));
        assertFalse(all, all.contains("男女"));
        // 永劫's card numeral is the pack's (the game prints XX), not the arcana number's.
        assertEquals("XX", LinkStatus.numeral(api.rules.links[21]));
        api.rules.links[21].numeral = "XX";
        assertEquals("XX", LinkStatus.numeral(api.rules.links[21]));
        assertEquals("未解锁 → RANK 2", Zh.rankStep(0, 2));
        assertEquals("未解锁 → MAX", Zh.rankStep(0, 10));
        assertEquals("RANK 6 → 7", Zh.rankStep(6, 7));
    }

    @Test
    public void dialogueHidesRomanceAnswersUnlessTheRouteIsRomance() {
        // Verbatim pack fragments.
        List<String> priestess9 = Arrays.asList("对话选择：任意/任意/我喜欢风花（1），恋人线/任意");
        assertEquals(Dialogue.FRIEND_FALLBACK, Dialogue.friendLine(priestess9, 9));
        assertEquals("回答：现在就是啊（2）；" + Dialogue.FRIEND_CAUTION,
            Dialogue.friendLine(Arrays.asList("对话选择：现在就是啊（2）/任意/是恋情（1，恋人线）"), 8));
        assertEquals("回答：随便问吧（1） / 随时欢迎千寻找我倾诉（1） / 握住她的手（1）；" + Dialogue.FRIEND_CAUTION,
            Dialogue.friendLine(Arrays.asList("对话选择：随便问吧（1）/产生恋爱感情了（1）/随时欢迎千寻找我倾诉（1）/握住她的手（1）",
                "第二次选择“产生恋爱感情了（1）”会进入恋爱线；选第二项不会进入", "最后选择第二项亲上去，关系会破裂"), 5));
        assertEquals("回答：突然这是怎么了？（1）；" + Dialogue.FRIEND_CAUTION,
            Dialogue.friendLine(Arrays.asList("前3次对话选择：第1项/任意/任意", "接着选择：突然这是怎么了？（1）",
                "最后一项：选1进入恋人线，选2做朋友"), 9));
        // No marking: RANK 8 and up get the generic hint, lower ranks read as usual.
        assertEquals(Dialogue.FRIEND_FALLBACK, Dialogue.friendLine(Arrays.asList("任意", "男孩或者女孩"), 8));
        assertEquals("回答：再做给我吃吧（3）", Dialogue.friendLine(Arrays.asList("再做给我吃吧（3）"), 5));
        // On the 恋人 route the guide's answers are shown as they are.
        assertEquals("回答：我喜欢风花（1），恋人线", Dialogue.line(priestess9));

        Plan.Step step = Fixtures.social(api.rules, NOW, "priestess", 8, 9, true, false);
        Profile friend = Fixtures.profileMid();
        assertEquals(Dialogue.FRIEND_FALLBACK, Dialogue.line(api.rules, friend, step));
        Profile romance = Fixtures.profileMid();
        romance.route[2] = Profile.Route.ROMANCE;
        assertEquals("回答：我喜欢风花（1），恋人线", Dialogue.line(api.rules, romance, step));
        // A link that cannot be romanced is unchanged.
        Plan.Step emperor = Fixtures.social(api.rules, NOW, "emperor", 6, 7, true, true);
        assertEquals("回答：我会帮你的 / 这样的规矩没有意义", Dialogue.line(api.rules, friend, emperor));
    }

    @Test
    public void heroCardShowsTheFriendRouteLine() {
        api.snap.plan.steps.set(0, Fixtures.social(api.rules, NOW, "priestess", 8, 9, true, false));
        HomeActivity a = home();
        assertTrue(Shots.has(a, Dialogue.FRIEND_SHORT));
        assertFalse("the generic advice is not repeated", Shots.has(a, Dialogue.FALLBACK));
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("我喜欢风花"));
        Shots.shot(a, "home_today_friend_route");
    }

    // ------------------------------------------------------------------ onboarding "?"

    @Test
    public void onboardingListsUnsureRanksOnTheConfirmPage() {
        api = FakePlannerApi.firstRun();
        Api.setForTest(api);
        StateActivity a = stateController("onboarding").get();
        StateDraft d = a.draft();
        d.setSlot(Rules.slot(Fixtures.day(6, 1), false));
        d.setRank(1, StateDraft.UNKNOWN);
        d.setRank(7, StateDraft.UNKNOWN);
        d.setRank(4, 3);
        List<StateDraft.Change> changes = d.changes();
        List<String> unsure = new ArrayList<>();
        for (StateDraft.Change c : changes) {
            if (StateDraft.UNSURE_RANK.equals(c.afterZh)) {
                unsure.add(c.labelZh);
            }
            if ("社群".equals(c.labelZh)) {
                assertEquals("已解锁 1 个", c.afterZh);
            }
        }
        assertEquals(Arrays.asList("魔术师", "战车"), unsure);
        jumpTo(a, StateSteps.Page.CONFIRM);
        assertTrue(Shots.has(a, "不确定（先按未解锁安排）"));
        assertTrue(Shots.has(a, "魔术师"));
        assertTrue(Shots.has(a, "战车"));
        Shots.shot(a, "state_onboarding_unsure_ranks");
        Shots.click(a, "保存并开始安排");
        SimState saved = api.last().state;
        assertEquals("? is saved as 未解锁", 0, saved.rank[1]);
        assertEquals(0, saved.rank[7]);
        assertEquals(3, saved.rank[4]);
    }

    @Test
    public void allUnsureIsNotCalledAllLocked() {
        api = FakePlannerApi.firstRun();
        StateDraft d = new StateDraft(StateDraft.Mode.ONBOARDING, api.rules, null, new Profile(), null, 0);
        d.setRank(1, StateDraft.UNKNOWN);
        for (StateDraft.Change c : d.changes()) {
            if ("社群".equals(c.labelZh)) {
                assertEquals("没有确定已解锁的", c.afterZh);
            }
        }
    }

    // ------------------------------------------------------------------ I3(3): recorded values after "是"

    @Test
    public void syncYesPickingTheRecordedValuesKeepsTheRecord() {
        SimState obs = api.snap.observed;
        obs.pts[4] = 9;
        obs.ready[4] = true;
        StateDraft d = new StateDraft(StateDraft.Mode.SYNC, api.rules, obs, Fixtures.profileMid(), api.snap.plan, 12);
        d.setSync(StateDraft.SyncAnswer.YES);
        d.setSlot(Rules.slot(Fixtures.day(10, 25), false));
        assertEquals("forecast after 是", 7, d.rank(4));
        d.setRank(4, 6);
        SimState built = d.build();
        assertEquals(6, built.rank[4]);
        assertEquals("the record's points stay", 9, built.pts[4]);
        assertTrue(built.ready[4]);
        // A stat set back to the recorded Lv keeps the recorded points.
        obs.statPts[0] = 120;
        int recorded = api.rules.stats[0].levelFor(120);
        Plan.Step study = api.snap.plan.at(Rules.slot(Fixtures.day(10, 22), true));
        study.predicted.statLvAfter[0] = recorded + 1;
        StateDraft e = new StateDraft(StateDraft.Mode.SYNC, api.rules, obs, Fixtures.profileMid(), api.snap.plan, 12);
        e.setSync(StateDraft.SyncAnswer.YES);
        e.setSlot(Rules.slot(Fixtures.day(10, 25), false));
        assertEquals("forecast after 是", recorded + 1, e.statLv(0));
        e.setStatLv(0, recorded);
        assertEquals(120, e.build().statPts[0]);
        e.setStatLv(0, recorded + 2);
        assertEquals(api.rules.stats[0].thresholds[recorded + 2], e.build().statPts[0]);
    }

    // ------------------------------------------------------------------ Tartarus result sheet

    @Test
    public void towerSheetStartsEmptyAndPreselectsThePredictedRescue() {
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        api.snap.observed.slot = slot;
        ResultActivity a = result(slot, "tower:3", Intents.MODE_RESULT);
        TowerPanel panel = a.towerPanel();
        assertNotNull(panel);
        assertFalse("nothing typed yet", panel.saveButton().isEnabled());
        assertEquals("救出了 preselected (the plan predicts it)", 0, panel.rescueSelection());
        assertTrue(Shots.has(a, TowerPanel.FORGOT));
        Shots.shot(a, "result_tower_empty");
        // 保存 does nothing until an amount or 不记得钱了.
        Shots.click(a, "保存");
        assertTrue(api.callsOf("recordTower").isEmpty());
        Shots.click(a, TowerPanel.FORGOT);
        assertTrue(panel.saveButton().isEnabled());
        assertTrue(panel.previewText(), panel.previewText().startsWith("不记得钱了：这次的现金按没变记（+0）"));
        Shots.shot(a, "result_tower_forgot_cash");
        Shots.click(a, "保存");
        FakePlannerApi.Call call = api.last();
        assertEquals("recordTower", call.method);
        assertFalse("net change", call.tower.balanceMode);
        assertEquals(0, call.tower.amount);
        assertEquals("maiko", call.tower.rescuedId);
        UndoCenter.Recorded recorded = UndoCenter.take();
        assertNotNull(recorded);
        assertTrue(recorded.summaryZh, recorded.summaryZh.endsWith("现金按没变记"));
    }

    @Test
    public void towerSheetWithoutAPredictedRescueAsksWithStillOpenSelected() {
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        api.snap.observed.slot = slot;
        Plan.Step step = api.snap.plan.at(slot);
        step.assumptions.clear();
        step.subtitleZh = "提齐亚（69–117F）· 本区块第 2 次";
        ResultActivity a = result(slot, "tower:3", Intents.MODE_RESULT);
        assertEquals(1, a.towerPanel().rescueSelection());
        assertFalse(ResultActivity.predictsRescue(api.rules, step));
    }

    @Test
    public void typingAnAmountAfterForgotUsesTheAmount() {
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        api.snap.observed.slot = slot;
        org.robolectric.android.controller.ActivityController<ResultActivity> c =
            resultController(slot, "tower:3", Intents.MODE_RESULT, true);
        TowerPanel panel = c.get().towerPanel();
        Shots.click(c.get(), TowerPanel.FORGOT);
        c.recreate();
        Shots.idle();
        assertTrue("不记得钱了 survives rotation", c.get().towerPanel().previewText().startsWith("不记得钱了"));
        panel = c.get().towerPanel();
        panel.setAmountForTest(20_000);
        assertEquals("进塔前 18,500 → 出塔后 20,000 日元（+1,500）", panel.previewText());
        Shots.click(c.get(), "保存");
        assertTrue(api.last().tower.balanceMode);
        assertEquals(20_000, api.last().tower.amount);
    }

    // ------------------------------------------------------------------ stable step counter

    @Test
    public void wizardStepCounterNeverChangesItsTotal() {
        StateActivity a = stateController("edit").get();
        String first = a.progressText();
        String total = first.substring(first.indexOf('/') + 1);
        // Before 9/1 neither 恶魔 nor 塔 can be prepared: 解锁进度 does not apply, its number stays.
        a.draft().setSlot(Rules.slot(Fixtures.day(8, 20), false));
        List<StateSteps.Page> seen = new ArrayList<>();
        List<String> counters = new ArrayList<>();
        for (int i = 0; i < 12 && a.currentPage() != StateSteps.Page.CONFIRM; i++) {
            seen.add(a.currentPage());
            counters.add(a.progressText());
            Shots.click(a, "下一步");
        }
        counters.add(a.progressText());
        for (String counter : counters) {
            assertEquals(counters.toString(), total, counter.substring(counter.indexOf('/') + 1));
        }
        assertFalse(seen.contains(StateSteps.Page.UNLOCK));
        assertEquals(total + "/" + total, a.progressText());
        // Back from 羁绊剧情 skips the page that does not apply.
        jumpBackTo(a, StateSteps.Page.EPISODES);
        a.onBackPressed();
        Shots.idle();
        assertEquals(StateSteps.Page.LINKS, a.currentPage());
        // From 9/1 on the same page is asked, under the same total.
        a.draft().setSlot(Rules.slot(Fixtures.day(10, 22), false));
        Shots.click(a, "下一步");
        assertEquals(StateSteps.Page.UNLOCK, a.currentPage());
        assertTrue(a.progressText().endsWith("/" + total));
    }

    private static void jumpBackTo(StateActivity a, StateSteps.Page page) {
        for (int i = 0; i < 12 && a.currentPage() != page; i++) {
            a.onBackPressed();
            Shots.idle();
        }
        assertEquals(page, a.currentPage());
    }

    // ------------------------------------------------------------------ wording

    @Test
    public void meAndSettingsWording() {
        HomeActivity a = homeOn("me");
        assertTrue(Shots.has(a, "安排风格、人格面具、塔尔塔罗斯、平安夜等"));
        Shots.click(a, "规划设置");
        // ×1.51 is set in the numeral face in plain and mixed text alike.
        int styled = 0;
        for (TextView t : textViews(a.getWindow().getDecorView())) {
            String plain = Zh.plain(t.getText());
            int at = plain.indexOf("×1.51");
            if (at < 0) {
                continue;
            }
            assertTrue(plain, t.getText() instanceof Spanned);
            Spanned s = (Spanned) t.getText();
            int raw = s.toString().indexOf('×');
            assertTrue(plain, raw >= 0 && s.getSpans(raw, raw + 1, NumeralSpan.class).length > 0);
            styled++;
        }
        assertTrue("several ×1.51 on the page", styled >= 3);
    }

    // ------------------------------------------------------------------ CJK line breaks

    @Test
    public void namesVenuesAndTagsAreKeptTogether() {
        Zh.useNames(api.rules);
        String sub = "提齐亚（69–117F）· 救出舞子";
        String kept = Zh.keepTogether(sub).toString();
        assertEquals(sub, Zh.plain(kept));
        assertTrue(kept, kept.contains("舞" + Zh.WJ + "子"));
        assertTrue(kept, kept.contains("救" + Zh.WJ + "出"));
        assertTrue(kept, kept.contains("齐" + Zh.WJ + "亚"));
        assertEquals("idempotent", kept, Zh.keepTogether(kept).toString());
        assertEquals(Zh.glue("本区块第 2 次"), Zh.keepTogether("本区块第 2 次").toString());
        String foot = Zh.keepTogether("这次的安排里没排到 MAX，先保证了皇帝、恋爱").toString();
        assertTrue(foot, foot.contains("皇" + Zh.WJ + "帝"));
        assertTrue(foot, foot.contains(Zh.glue("没排到")));
        String side = Zh.keepTogether("顺便：听西胁结子说起奇怪的和尚").toString();
        assertTrue(side, side.contains("西" + Zh.WJ + "胁" + Zh.WJ + "结" + Zh.WJ + "子"));
        String persona = Zh.keepTogether("记得带上「皇帝」阿尔卡那的人格面具").toString();
        assertTrue(persona, persona.contains("人" + Zh.WJ + "格" + Zh.WJ + "面" + Zh.WJ + "具"));
        String answer = Zh.keepTogether("回答：你说得对（1） / 那也没办法（2）").toString();
        assertTrue(answer, answer.contains("对" + Zh.WJ + "（"));
        assertTrue(Zh.keepTogether("10月22日 晚上").toString().contains("2" + Zh.WJ + "日"));
        // Ordinary words (ICU dictionary segmentation) and whole short answers.
        assertTrue(side, side.contains("和" + Zh.WJ + "尚"));
        assertTrue(side, side.contains("奇" + Zh.WJ + "怪"));
        String rule = Zh.keepTogether("回答：我会帮你的 / 这样的规矩没有意义").toString();
        assertEquals(Zh.glue("这样的规矩没有意义"), rule.substring(rule.indexOf(" / ") + 3));
        assertTrue(rule, rule.contains(" / "));
        String meet = Zh.keepTogether("RANK 4 · 见面（预计不升级）").toString();
        assertTrue(meet, meet.contains(Zh.glue("见面（预计不升级）")));
        assertTrue(Zh.keepTogether("+3,000 日元 · 魅力 +1 勇气 +1").toString().contains(Zh.glue("勇气 +1")));
    }

    @Test
    @Config(fontScale = 1.3f)
    public void noLineBreaksInsideNamesAtLargeFont() {
        checkScreens("_font130");
    }

    @Test
    public void noLineBreaksInsideNames() {
        checkScreens("");
    }

    private void checkScreens(String suffix) {
        HomeActivity a = home();
        assertNoBreakInsideKeptText(a, "today" + suffix);
        Shots.shot(a, "audit_today" + suffix);
        HomeActivity s = homeOn("schedule");
        assertNoBreakInsideKeptText(s, "schedule" + suffix);
        Shots.shot(s, "audit_schedule" + suffix);
        HomeActivity l = homeOn("links");
        android.widget.ScrollView scroll = lastScroll(l.getWindow().getDecorView(), null);
        for (int y = 0; y <= 6000; y += 1500) {
            scroll.scrollTo(0, y);
            Shots.capture(l, false);
            assertNoBreakInsideKeptText(l, "links@" + y + suffix);
        }
        scroll.scrollTo(0, 1500);
        Shots.shot(l, "audit_links_grid" + suffix);
        HomeActivity m = homeOn("me");
        Shots.click(m, "规划设置");
        assertNoBreakInsideKeptText(m, "settings" + suffix);
        Shots.shot(m, "audit_settings" + suffix);
        Shots.click(m, "每个区块去几次");
        Shots.capture(m, false);
        assertNoBreakInsideKeptText(m, "settings_tower" + suffix);
        Shots.shot(m, "audit_settings_tower" + suffix);
        int slot = Rules.slot(Fixtures.day(10, 24), true);
        api.snap.observed.slot = slot;
        ResultActivity r = result(slot, "tower:3", Intents.MODE_RESULT);
        Shots.capture(r, false);
        assertNoBreakInsideKeptText(r, "tower sheet" + suffix);
        Shots.shot(r, "audit_result_tower" + suffix);
    }

    /**
     * Every visible text view: no line ends right before or after a word joiner, i.e. no name,
     * venue, word or short tag kept together by {@link Zh#keepTogether} is split across lines; no
     * line visibly ends with a " · " separator and no wrapped line is a lone character
     * ({@link Shots#checkLines}).
     */
    static void assertNoBreakInsideKeptText(android.app.Activity activity, String where) {
        Shots.capture(activity, false);
        for (TextView t : textViews(activity.getWindow().getDecorView())) {
            Shots.checkLines(t, where);
        }
    }

    static List<TextView> textViews(View root) {
        List<TextView> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(View v, List<TextView> out) {
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (v instanceof TextView) {
            out.add((TextView) v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
    }

    private static int top(View v) {
        int y = 0;
        View cur = v;
        while (cur != null && !(cur instanceof android.widget.ScrollView)) {
            y += cur.getTop();
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return y;
    }

    private static android.widget.ScrollView lastScroll(View v, android.widget.ScrollView found) {
        android.widget.ScrollView result = found;
        if (v instanceof android.widget.ScrollView && v.isShown()) {
            result = (android.widget.ScrollView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                result = lastScroll(g.getChildAt(i), result);
            }
        }
        return result;
    }
}
