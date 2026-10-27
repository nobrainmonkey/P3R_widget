package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.TestRules;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * 改做别的 labels follow docs/GLOSSARY_zh.md and read like the engine's own (engine Labels), including the
 * Persona reminder, notes and Link Episodes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StepLabelsTest {
    private final Rules rules = TestRules.create();
    private final SimState state = TestRules.state(rules, TestRules.slot(TestRules.MAY_12, true));

    private static Engine.Applied applied(boolean rankUp) {
        Engine.Applied a = new Engine.Applied();
        a.predicted = new Plan.Predicted();
        a.predicted.rankUp = rankUp;
        return a;
    }

    @Test
    public void meetingsSayRankAndRemindOfThePersonaWhenItCounts() {
        Profile none = new Profile();
        Plan.Step plain = StepLabels.step(rules, none, state, Action.meet(TestRules.EMPEROR), applied(false));
        assertEquals("no hedge inside the fact: no arrow means no rank-up", "RANK 6 · 见面", plain.subtitleZh);
        assertNull("不带 Persona: no reminder", plain.hintZh);

        Profile goals = new Profile();
        goals.personaMode = Profile.PersonaMode.GOALS;
        assertNull("not a goal link", StepLabels.step(rules, goals, state, Action.meet(TestRules.EMPEROR), applied(true)).hintZh);
        goals.tier[TestRules.EMPEROR] = Profile.Tier.WANT;
        Plan.Step goal = StepLabels.step(rules, goals, state, Action.meet(TestRules.EMPEROR), applied(true));
        assertEquals("记得带上「皇帝」阿尔卡那的人格面具", goal.hintZh);
        assertEquals("RANK 6 → 7", goal.subtitleZh);

        Profile all = new Profile();
        all.personaMode = Profile.PersonaMode.ALL;
        SimState nine = state.copy();
        nine.rank[TestRules.EMPEROR] = 9;
        Plan.Step max = StepLabels.step(rules, all, nine, Action.meet(TestRules.EMPEROR), applied(true));
        assertEquals("RANK 9 → MAX", max.subtitleZh);
        assertEquals("记得带上「皇帝」阿尔卡那的人格面具", max.hintZh);
    }

    @Test
    public void notesAndEpisodesHaveFriendlyLabels() {
        Plan.Step note = StepLabels.step(rules, new Profile(), state, Action.note(TestRules.EMPEROR), applied(false));
        assertEquals("note", note.kind);
        assertEquals("结缘网站的字条 · 小田桐秀利", note.titleZh);
        assertEquals("好感 +10 · 花费 3,000 日元", note.subtitleZh);
        assertEquals("桐叶购物中心 · 放纵俱乐部", note.locationZh);
        assertEquals(rules.datingNote.howZh + "\n只加好感，不会直接升级。", note.detailZh);

        SimState watched = state.copy();
        watched.episodes = new int[]{1};
        Plan.Step episode = StepLabels.step(rules, new Profile(), watched, Action.episode(TestRules.JUNPEI), applied(false));
        assertEquals("episode", episode.kind);
        assertEquals("羁绊剧情 · 伊织顺平（第 2 话）", episode.titleZh);
        assertEquals("共 4 话", episode.subtitleZh);
        assertEquals("junpei", episode.entity);
    }

    @Test
    public void ryojiMeetingsKeepTheirStorySceneNumbersAndTheLaterFusionDate() {
        Rules.Episode ryoji = new Rules.Episode();
        ryoji.idx = 0;
        ryoji.id = "ryoji";
        ryoji.personZh = "望月绫时";
        ryoji.count = 2;
        ryoji.totalScenes = 5;
        ryoji.partScenes = new int[]{2, 4};
        ryoji.partLocationZh = new String[]{"月光馆学园 · 2 楼教室", "月光馆学园 · 1 楼音乐室前"};
        ryoji.partHowZh = new String[]{"放学后到教室接受邀约。", "先完成11月18日晚的邀约剧情，12月1日接受短信邀约。"};
        ryoji.rewardZh = "此前羁绊剧情全部完成后，12月31日第5话可以合体人格面具「萨图尔努斯」。";
        rules.episodes = new Rules.Episode[]{ryoji};
        SimState before = state.copy();
        before.episodes = new int[]{0};

        Plan.Step first = StepLabels.step(rules, new Profile(), before, Action.episode(0), applied(false));
        assertEquals("羁绊剧情 · 望月绫时（第 2 话）", first.titleZh);
        assertEquals("需安排 2 次邀约", first.subtitleZh);
        assertEquals(ryoji.partLocationZh[0], first.locationZh);
        assertTrue(first.detailZh.contains("放学后到教室接受邀约"));

        before.episodes[0] = 1;
        Plan.Step second = StepLabels.step(rules, new Profile(), before, Action.episode(0), applied(false));
        assertEquals("羁绊剧情 · 望月绫时（第 4 话）", second.titleZh);
        assertEquals(ryoji.partLocationZh[1], second.locationZh);
        assertTrue(second.detailZh.contains("先完成11月18日晚的邀约剧情"));
        assertTrue(second.detailZh.contains("12月31日第5话可以合体"));
        assertEquals("saved progress counts planned meetings, not automatic scenes", 1, before.episodes[0]);
    }

    @Test
    public void unlockStepsAndSideStepsNameTheStep() {
        Plan.Step unlock = StepLabels.step(rules, new Profile(), state, Action.unlock(TestRules.MAGICIAN, 0), applied(false));
        assertEquals("魔术师 · 友近健二", unlock.titleZh);
        assertEquals("与友近健二对话", unlock.subtitleZh);

        Rules.Step nameless = new Rules.Step();
        nameless.id = "magician.second";
        rules.links[TestRules.MAGICIAN].unlock.steps.add(nameless);
        Engine.Applied withSide = applied(false);
        withSide.side = List.of("unlock:magician:1");
        Plan.Step study = StepLabels.step(rules, new Profile(), state, Action.activity(TestRules.STUDY), withSide);
        assertEquals(List.of("unlock:magician:1"), study.side);
        assertEquals(List.of("顺便：解锁前的准备（第 2 步，共 2 步）"), study.sideZh);
    }

    @Test
    public void freeSlotsAreCalledFreeTime() {
        Plan.Step free = StepLabels.step(rules, new Profile(), state, Action.free(), null);
        assertEquals("自由活动", free.titleZh);
        assertEquals("the title says it all", "", free.subtitleZh);
    }
    @Test
    public void invitesAndActivitiesReadLikeTheEngine() {
        Plan.Step invite = StepLabels.step(rules, new Profile(), state, Action.invite(TestRules.EMPEROR), applied(false));
        assertEquals("休息日邀约 · 收到短信就能去", invite.subtitleZh);
        assertEquals("只加好感，不会直接升级。", invite.detailZh);

        Engine.Applied job = applied(false);
        job.predicted.cashDelta = 2_500;
        job.predicted.statDelta = new int[]{0, 1, 1};
        Plan.Step paid = StepLabels.step(rules, new Profile(), state, Action.activity(TestRules.STUDY), job);
        assertEquals("+2,500 日元 · 魅力 +1、勇气 +1", paid.subtitleZh);

        Engine.Applied training = applied(false);
        training.predicted.cashDelta = -1_500;
        training.predicted.statDelta = new int[]{0, 2, 0};
        assertEquals("魅力 +2 · 花费 1,500 日元",
                StepLabels.step(rules, new Profile(), state, Action.activity(TestRules.STUDY), training).subtitleZh);
    }
}
