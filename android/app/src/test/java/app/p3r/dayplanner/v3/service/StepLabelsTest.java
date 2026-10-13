package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

/** 改做别的 labels follow docs/GLOSSARY_zh.md, including the Persona reminder, notes and Link Episodes. */
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
        assertEquals("RANK 6 · 见面（预计不升级）", plain.subtitleZh);
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
        assertEquals("结缘网站的字条", note.titleZh);
        assertEquals("给小田桐秀利加好感 · RANK 6", note.subtitleZh);
        assertEquals("桐叶购物中心 · 放纵俱乐部", note.locationZh);
        assertEquals(rules.datingNote.howZh, note.detailZh);

        SimState watched = state.copy();
        watched.episodes = new int[]{1};
        Plan.Step episode = StepLabels.step(rules, new Profile(), watched, Action.episode(TestRules.JUNPEI), applied(false));
        assertEquals("episode", episode.kind);
        assertEquals("羁绊剧情 · 伊织顺平（第 2 话）", episode.titleZh);
        assertEquals("不加社群点数", episode.subtitleZh);
        assertEquals("junpei", episode.entity);
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
    }
}
