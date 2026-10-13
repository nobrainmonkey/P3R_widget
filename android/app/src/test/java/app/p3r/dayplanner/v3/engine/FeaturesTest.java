package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Round-2 features on synthetic packs: Persona modes (不带 / 只给目标社群带 / 基本都带) and the
 * "记得带上…" hint, the dating-site note (结缘网站的字条), opted-in Link Episodes (羁绊剧情), the
 * glossary wording of the step copy, and the usefulness order of {@link Engine#alternatives}.
 */
public class FeaturesTest {
    private static final int MAGICIAN = RulesFixtures.S_MAGICIAN;
    private static final int EMPEROR = RulesFixtures.S_EMPEROR;
    private static final int HIEROPHANT = RulesFixtures.S_HIEROPHANT;
    /** Friday 5/1 in the small fixture: magician and emperor both meet in the daytime. */
    private static final int FRIDAY = 30;
    /** Saturday 5/2 in the small fixture (evening open; hierophant and the movie in the daytime). */
    private static final int SATURDAY = 31;

    private static Engine.Options generous() {
        Engine.Options o = new Engine.Options();
        o.timeBudgetMillis = 60_000;
        return o;
    }

    private static SimState smallState(Rules rules, int slot) {
        SimState s = RulesFixtures.newGame(rules, slot);
        s.rank[MAGICIAN] = 4;
        s.rank[EMPEROR] = 5;
        s.unlockStep[MAGICIAN] = 1;
        s.unlockStep[EMPEROR] = 2;
        return s;
    }

    private static Plan.Step alternative(List<Plan.Step> alts, String action) {
        for (Plan.Step s : alts) if (s.action.equals(action)) return s;
        return null;
    }

    // ------------------------------------------------------------------ Persona modes

    @Test
    public void goalsModeBoostsOnlyGoalLinks() {
        Rules rules = RulesFixtures.small();
        SimState s = smallState(rules, Rules.slot(FRIDAY, false));
        Profile goals = new Profile();
        goals.personaMode = Profile.PersonaMode.GOALS;
        goals.tier[EMPEROR] = Profile.Tier.WANT;
        assertEquals(15, Engine.apply(rules, goals, s, Action.meet(EMPEROR)).state.pts[EMPEROR]);
        assertEquals(10, Engine.apply(rules, goals, s, Action.meet(MAGICIAN)).state.pts[MAGICIAN]);
        assertTrue(Engine.apply(rules, goals, s, Action.meet(EMPEROR)).assumptions.contains("persona_bonus"));
        assertFalse(Engine.apply(rules, goals, s, Action.meet(MAGICIAN)).assumptions.contains("persona_bonus"));

        List<Plan.Step> alts = Engine.alternatives(rules, goals, s);
        assertEquals("记得带上「皇帝」阿尔卡那的人格面具", alternative(alts, "meet:emperor").hintZh);
        assertNull(alternative(alts, "meet:magician").hintZh);

        Profile none = new Profile();
        none.tier[EMPEROR] = Profile.Tier.WANT;
        assertEquals(10, Engine.apply(rules, none, s, Action.meet(EMPEROR)).state.pts[EMPEROR]);
        for (Plan.Step step : Engine.alternatives(rules, none, s)) assertNull(step.action, step.hintZh);

        Profile all = new Profile();
        all.personaMode = Profile.PersonaMode.ALL;
        assertEquals(15, Engine.apply(rules, all, s, Action.meet(MAGICIAN)).state.pts[MAGICIAN]);
        assertEquals("记得带上「魔术师」阿尔卡那的人格面具", alternative(Engine.alternatives(rules, all, s), "meet:magician").hintZh);

        Profile legacy = new Profile();
        legacy.personaBonus = true; // older profiles and backups: the boolean means 基本都带
        assertEquals(15, Engine.apply(rules, legacy, s, Action.meet(MAGICIAN)).state.pts[MAGICIAN]);
    }

    @Test
    public void goalsModePlanValidatesAndHintsOnlyGoals() {
        Rules rules = RulesFixtures.fullYear();
        int emperor = 4;
        int lovers = 6;
        Profile p = RulesFixtures.must(emperor);
        p.tier[lovers] = Profile.Tier.WANT;
        p.personaMode = Profile.PersonaMode.GOALS;
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Plan plan = Engine.plan(rules, p, start, null, new Engine.Options());
        assertEquals(Engine.validate(rules, p, start, plan).toString(), 0, Engine.validate(rules, p, start, plan).size());
        assertEquals(10, plan.summary.endRanks[emperor]);
        int hinted = 0;
        for (Plan.Step s : plan.steps) {
            if (s.hintZh == null) continue;
            hinted++;
            assertTrue(s.entity + " " + s.hintZh, "emperor".equals(s.entity) || "lovers".equals(s.entity));
            assertTrue(s.hintZh, s.hintZh.startsWith("记得带上「") && s.hintZh.endsWith("」阿尔卡那的人格面具"));
        }
        assertTrue("goal meetings carry the Persona hint", hinted > 5);
        assertTrue(plan.summary.notesZh.toString(), plan.summary.notesZh.contains("按和目标社群见面时带着同阿尔卡那的人格面具估计，好感 ×1.51"));
    }

    // ------------------------------------------------------------------ dating-site note

    private static Rules smallWithNote() {
        Rules rules = RulesFixtures.small();
        rules.datingNote = RulesFixtures.datingNote(30);
        return rules;
    }

    @Test
    public void noteLegality() {
        Rules rules = smallWithNote();
        Profile p = new Profile();
        SimState eve = smallState(rules, Rules.slot(SATURDAY, true));
        eve.cash = 5_000;
        List<Action> legal = Engine.legalActions(rules, p, eve);
        assertTrue(legal.toString(), legal.contains(Action.note(MAGICIAN)));
        assertTrue(legal.contains(Action.note(EMPEROR)));
        assertFalse("not unlocked yet", legal.contains(Action.note(HIEROPHANT)));

        SimState day = eve.copy();
        day.slot = Rules.slot(SATURDAY, false);
        assertFalse("evenings only", Engine.legalActions(rules, p, day).contains(Action.note(MAGICIAN)));
        SimState early = eve.copy();
        early.slot = Rules.slot(29, true);
        assertFalse("not on sale yet", Engine.legalActions(rules, p, early).contains(Action.note(MAGICIAN)));
        SimState poor = eve.copy();
        poor.cash = 2_999;
        assertFalse(Engine.legalActions(rules, p, poor).contains(Action.note(MAGICIAN)));
        Profile buffer = new Profile();
        buffer.cashBuffer = 3_000;
        assertFalse("an optional spend keeps the cash buffer", Engine.legalActions(rules, buffer, eve).contains(Action.note(MAGICIAN)));
        Profile off = new Profile();
        off.datingNotes = false;
        assertFalse(Engine.legalActions(rules, off, eve).contains(Action.note(MAGICIAN)));
        SimState ready = eve.copy();
        ready.ready[MAGICIAN] = true;
        assertFalse("the next meeting ranks up anyway", Engine.legalActions(rules, p, ready).contains(Action.note(MAGICIAN)));
        SimState full = eve.copy();
        full.pts[MAGICIAN] = 15;
        assertFalse("points already enough", Engine.legalActions(rules, p, full).contains(Action.note(MAGICIAN)));
        SimState max = eve.copy();
        max.rank[MAGICIAN] = 10;
        assertFalse(Engine.legalActions(rules, p, max).contains(Action.note(MAGICIAN)));
        Profile onlyEmperor = RulesFixtures.must(EMPEROR);
        onlyEmperor.otherLinks = false;
        List<Action> goalOnly = Engine.legalActions(rules, onlyEmperor, eve);
        assertFalse("links the plan does not want get no notes", goalOnly.contains(Action.note(MAGICIAN)));
        assertTrue(goalOnly.contains(Action.note(EMPEROR)));
    }

    @Test
    public void noteAddsPointsButNeverRanksUp() {
        Rules rules = smallWithNote();
        Profile p = new Profile();
        SimState eve = smallState(rules, Rules.slot(SATURDAY, true));
        eve.cash = 5_000;
        eve.pts[MAGICIAN] = 10;
        Engine.Applied a = Engine.apply(rules, p, eve, Action.note(MAGICIAN));
        assertEquals(4, a.state.rank[MAGICIAN]);
        assertEquals(20, a.state.pts[MAGICIAN]);
        assertEquals(2_000, a.state.cash);
        assertEquals(1, a.state.notesUsed);
        assertFalse(a.predicted.rankUp);
        assertEquals(4, a.predicted.rankTo);
        assertTrue(a.estimate);
        assertTrue(a.assumptions.contains("dating_note_gain"));
        // The next meeting ranks up (Thursday 5/7 daytime is a magician day).
        SimState thu = Engine.advanceTo(rules, p, a.state, Rules.slot(36, false));
        assertTrue(Engine.apply(rules, p, thu, Action.meet(MAGICIAN)).predicted.rankUp);
        // Notes do not take the Persona multiplier.
        Profile all = new Profile();
        all.personaMode = Profile.PersonaMode.ALL;
        assertEquals(20, Engine.apply(rules, all, eve, Action.note(MAGICIAN)).state.pts[MAGICIAN]);
    }

    @Test
    public void noteOutcomes() {
        Rules rules = smallWithNote();
        Profile p = new Profile();
        SimState eve = smallState(rules, Rules.slot(SATURDAY, true));
        eve.cash = 5_000;
        SimState done = Engine.applyOutcome(rules, p, eve, Action.note(MAGICIAN), Outcome.of(Outcome.Code.AS_PREDICTED));
        assertEquals(10, done.pts[MAGICIAN]);
        assertEquals(2_000, done.cash);
        SimState skipped = Engine.applyOutcome(rules, p, eve, Action.note(MAGICIAN), Outcome.of(Outcome.Code.SKIPPED));
        assertEquals(0, skipped.pts[MAGICIAN]);
        assertEquals(5_000, skipped.cash);
        Outcome up = Outcome.of(Outcome.Code.RANK_UP);
        up.rank = 5;
        assertEquals(5, Engine.applyOutcome(rules, p, eve, Action.note(MAGICIAN), up).rank[MAGICIAN]);
        Outcome cue = Outcome.of(Outcome.Code.AS_PREDICTED);
        cue.readyCue = true;
        assertTrue(Engine.applyOutcome(rules, p, eve, Action.note(MAGICIAN), cue).ready[MAGICIAN]);
    }

    @Test
    public void noteCopy() {
        Rules rules = smallWithNote();
        Profile p = new Profile();
        SimState eve = smallState(rules, Rules.slot(SATURDAY, true));
        eve.cash = 5_000;
        eve.rank[HIEROPHANT] = 4;
        eve.unlockStep[HIEROPHANT] = 1;
        List<Plan.Step> alts = Engine.alternatives(rules, p, eve);
        Plan.Step note = alternative(alts, "note:magician");
        assertNotNull(alts.toString(), note);
        assertEquals("note", note.kind);
        assertEquals("magician", note.entity);
        assertEquals("在放纵俱乐部买结缘网站的字条，给 友近健二 加好感", note.titleZh);
        assertEquals("魔术师 · 好感 +10 · 3,000 日元", note.subtitleZh);
        assertEquals("桐叶购物中心 · 放纵俱乐部", note.locationZh);
        assertTrue(note.detailZh, note.detailZh.startsWith(rules.datingNote.howZh));
        assertTrue(note.detailZh, note.detailZh.contains("不会让社群直接升级"));
        assertEquals(-3_000, note.predicted.cashDelta);
        assertEquals("在放纵俱乐部买结缘网站的字条，给 教皇社群 加好感", alternative(alts, "note:hierophant").titleZh);
    }

    @Test
    public void notesHelpWhenDaytimeIsScarce() {
        Rules rules = RulesFixtures.fullYearWithExtras();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Profile on = RulesFixtures.others();
        Profile off = RulesFixtures.others();
        off.datingNotes = false;
        Plan withNotes = Engine.plan(rules, on, start, null, new Engine.Options());
        Plan without = Engine.plan(rules, off, start, null, new Engine.Options());
        assertTrue(Engine.validate(rules, on, start, withNotes).toString(), Engine.validate(rules, on, start, withNotes).isEmpty());
        assertTrue(Engine.validationWarnings(rules, on, start, withNotes).isEmpty());
        assertTrue(Engine.validate(rules, off, start, without).isEmpty());
        int notes = 0;
        int sale = RulesFixtures.slot(11, 16, true);
        for (Plan.Step s : withNotes.steps) {
            if (!s.action.startsWith("note:")) continue;
            notes++;
            assertTrue("evening " + s.slot, (s.slot & 1) == 1 && s.slot >= sale);
        }
        for (Plan.Step s : without.steps) assertFalse(s.action.startsWith("note:"));
        int ranksWith = 0;
        int ranksWithout = 0;
        for (int l = 0; l < rules.links.length; l++) {
            ranksWith += withNotes.summary.endRanks[l];
            ranksWithout += without.summary.endRanks[l];
        }
        System.out.println("synthetic year · notes " + notes + " · end ranks " + ranksWith + " vs " + ranksWithout + " without notes · maxed "
                + PlanReport.maxedLinks(rules, withNotes) + " vs " + PlanReport.maxedLinks(rules, without));
        assertTrue("notes are used", notes > 0);
        assertTrue("notes never cost ranks", ranksWith >= ranksWithout);
        assertTrue(PlanReport.maxedLinks(rules, withNotes) >= PlanReport.maxedLinks(rules, without));
        // Validation catches a plan that uses notes the player turned off.
        assertFalse(Engine.validate(rules, off, start, withNotes).isEmpty());
    }

    // ------------------------------------------------------------------ Link Episodes

    private static Profile episodes(String... tracks) {
        Profile p = RulesFixtures.others();
        p.episodeTracks = tracks;
        return p;
    }

    @Test
    public void episodeLegalityFollowsTheCalendarAndOrder() {
        Rules rules = RulesFixtures.fullYearWithExtras();
        Profile p = episodes("junpei", "akihiko");
        SimState tue = RulesFixtures.newGame(rules, RulesFixtures.slot(5, 12, false));
        assertTrue(Engine.legalActions(rules, p, tue).contains(Action.episode(0)));
        assertFalse("not opted in", Engine.legalActions(rules, RulesFixtures.others(), tue).contains(Action.episode(0)));
        SimState wed = RulesFixtures.newGame(rules, RulesFixtures.slot(5, 13, false));
        assertFalse(Engine.legalActions(rules, p, wed).contains(Action.episode(0)));
        SimState tueEve = RulesFixtures.newGame(rules, RulesFixtures.slot(5, 12, true));
        assertFalse("daytime track", Engine.legalActions(rules, p, tueEve).contains(Action.episode(0)));

        Engine.Applied first = Engine.apply(rules, p, tue, Action.episode(0));
        assertEquals(1, first.state.episodes[0]);
        assertTrue(first.predicted.certain);
        SimState fri = Engine.advanceTo(rules, p, first.state, RulesFixtures.slot(5, 15, false));
        assertFalse("episode 2 has its own window", Engine.legalActions(rules, p, fri).contains(Action.episode(0)));
        SimState aug = fri.copy();
        aug.slot = RulesFixtures.slot(8, 18, false);
        assertTrue(Engine.legalActions(rules, p, aug).contains(Action.episode(0)));

        SimState friEve = RulesFixtures.newGame(rules, RulesFixtures.slot(5, 29, true));
        assertTrue(Engine.legalActions(rules, p, friEve).contains(Action.episode(1)));

        List<Plan.Step> alts = Engine.alternatives(rules, p, tue);
        Plan.Step ep = alternative(alts, "episode:junpei");
        assertEquals("episode", ep.kind);
        assertEquals("junpei", ep.entity);
        assertEquals("羁绊剧情 · 伊织顺平", ep.titleZh);
        assertEquals("第 1 话（共 5 话）", ep.subtitleZh);
        assertTrue(ep.detailZh, ep.detailZh.contains("看完全部 5 话：特性「伊织顺平的心意」"));
        assertFalse(ep.estimate);

        assertEquals(1, Engine.applyOutcome(rules, p, tue, Action.episode(0), Outcome.of(Outcome.Code.AS_PREDICTED)).episodes[0]);
        assertEquals(0, Engine.applyOutcome(rules, p, tue, Action.episode(0), Outcome.of(Outcome.Code.SKIPPED)).episodes[0]);
    }

    @Test
    public void optedInTracksAreCompleted() {
        Rules rules = RulesFixtures.fullYearWithExtras();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Profile p = episodes("junpei", "akihiko");
        Plan plan = Engine.plan(rules, p, start, null, new Engine.Options());
        assertTrue(Engine.validate(rules, p, start, plan).toString(), Engine.validate(rules, p, start, plan).isEmpty());
        assertTrue(Engine.validationWarnings(rules, p, start, plan).isEmpty());
        int junpei = 0;
        int akihiko = 0;
        for (Plan.Step s : plan.steps) {
            if (s.action.equals("episode:junpei")) {
                junpei++;
                assertEquals("第 " + junpei + " 话（共 5 话）", s.subtitleZh);
                assertTrue(rules.episodes[0].allows(s.slot >> 1, junpei));
            }
            if (s.action.equals("episode:akihiko")) akihiko++;
        }
        assertEquals(5, junpei);
        assertEquals(3, akihiko);
        boolean noted = false;
        for (String n : plan.summary.notesZh) noted |= n.startsWith("羁绊剧情 · 伊织顺平：预计 ") && n.endsWith(" 看完全部 5 话");
        assertTrue(plan.summary.notesZh.toString(), noted);

        Plan none = Engine.plan(rules, RulesFixtures.others(), start, null, new Engine.Options());
        for (Plan.Step s : none.steps) assertFalse(s.action.startsWith("episode:"));
        System.out.println("synthetic year · episodes 5+3 · maxed " + PlanReport.maxedLinks(rules, plan) + " vs "
                + PlanReport.maxedLinks(rules, none) + " without episodes");
        // A plan with episodes is invalid for a player who did not choose them.
        assertFalse(Engine.validate(rules, RulesFixtures.others(), start, plan).isEmpty());
    }

    @Test
    public void aSingleDayEpisodeIsNotMissed() {
        Rules rules = RulesFixtures.fullYearWithExtras();
        Rules.Episode ryoji = RulesFixtures.track(rules, 2, "ryoji", "望月绫时", false, 1);
        int only = RulesFixtures.day(11, 9);
        ryoji.dayMask[only] = 1;
        rules.episodes = new Rules.Episode[]{rules.episodes[0], rules.episodes[1], ryoji};
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(10, 20, false));
        start.rank[1] = 6;
        start.rank[4] = 7;
        start.rank[7] = 5;
        Profile p = episodes("ryoji");
        Plan plan = Engine.plan(rules, p, start, null, new Engine.Options());
        assertTrue(Engine.validate(rules, p, start, plan).isEmpty());
        assertEquals("episode:ryoji", plan.at(Rules.slot(only, false)).action);
        assertEquals("这是能看第 1 话的最后机会", plan.at(Rules.slot(only, false)).whyZh);
    }

    // ------------------------------------------------------------------ copy

    @Test
    public void glossaryWording() {
        Rules rules = RulesFixtures.small();
        Profile p = RulesFixtures.others();
        Model m = new Model(rules, p, null);
        SimState s = smallState(rules, Rules.slot(SATURDAY, false));
        Heuristics h = new Heuristics(m, s, null);
        Labels labels = new Labels(m, h);
        assertEquals("周二、周四、周五放学后", labels.weekdaysPhrase(MAGICIAN, 20));
        assertEquals("周二、周六晚上", labels.weekdaysPhrase(RulesFixtures.S_DEVIL, 20));
        assertNull("six weekdays", labels.weekdaysPhrase(HIEROPHANT, 20));
        assertEquals("教皇社群", labels.who(HIEROPHANT));
        assertEquals("友近健二", labels.who(MAGICIAN));
        assertEquals("友近健二只在周二、周四、周五放学后出现，剩下的机会不多", labels.scarceWhy(MAGICIAN, 20));

        Rules year = RulesFixtures.fullYear();
        year.links[9].locationZh = "宿舍 · 自己房间的电脑";
        Model ym = new Model(year, p, null);
        Labels yl = new Labels(ym, new Heuristics(ym, RulesFixtures.newGame(year, RulesFixtures.slot(4, 21, false)), null));
        assertEquals("周日和节假日白天", yl.weekdaysPhrase(9, RulesFixtures.day(4, 21)));
        assertEquals("Ｙ子只在周日和节假日白天上线，剩下的机会不多", yl.scarceWhy(9, RulesFixtures.day(4, 21)));
        assertEquals("MAX", Labels.lvName(6));
        assertEquals("Lv.5", Labels.lvName(5));
    }

    @Test
    public void stepCopyFollowsTheGlossary() {
        Rules rules = RulesFixtures.small();
        Profile p = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, Rules.slot(20, false));
        Plan plan = Engine.plan(rules, p, start, null, generous());
        Plan.Step locked = plan.at(Rules.slot(21, false));
        assertEquals("这段时间不能自由行动", locked.subtitleZh);
        boolean tower = false;
        for (Plan.Step s : plan.steps) {
            if (s.kind.equals("tower")) {
                tower = true;
                assertEquals("探索塔尔塔罗斯", s.titleZh);
                assertTrue(s.subtitleZh, s.subtitleZh.matches(".+（\\d+–\\d+F）· (本区块第 \\d+ 次|救出.+)"));
                assertEquals("影时间 · 月光馆学园", s.locationZh);
            }
            if (s.kind.equals("free")) assertEquals("自由活动", s.titleZh);
        }
        assertTrue(tower);
    }

    @Test
    public void foldedUnlockStepsAreNumbered() {
        Rules rules = RulesFixtures.fullYear();
        rules.links[5].unlock.steps.get(0).howZh = "去商店街买柿子叶";
        rules.links[5].unlock.steps.get(1).howZh = "把柿子叶送到旧书店";
        Profile p = RulesFixtures.others();
        SimState s = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 25, false));
        Plan.Step unlock = alternative(Engine.alternatives(rules, p, s), "unlock:hierophant:1");
        assertNotNull(unlock);
        assertEquals("先买柿子叶，再把柿子叶送给书店老板", unlock.subtitleZh);
        assertEquals("① 去商店街买柿子叶\n② 把柿子叶送到旧书店", unlock.detailZh);
        assertEquals("完成后教皇社群就解锁了", unlock.whyZh);
    }

    @Test
    public void statLevelSixReadsMax() {
        Rules rules = RulesFixtures.small();
        Profile p = RulesFixtures.others();
        SimState s = smallState(rules, Rules.slot(SATURDAY, true));
        s.statPts[RulesFixtures.ACADEMICS] = rules.stats[RulesFixtures.ACADEMICS].thresholds[6] - 1;
        Plan.Step study = alternative(Engine.alternatives(rules, p, s), "act:study_room");
        assertEquals("学力 +2（升到 MAX）· 免费", study.subtitleZh);
    }

    /** Words the glossary rules out, in every text a plan shows (steps and notes). */
    private static final String[] BANNED = {"练满", "开启", "会面", "加深关系", "自由时间", "自由安排", "爬塔", "Persona", "Lv.6",
            "规划", "计划", "约会", "社交链接", "牵绊", "未升级", "估算", "剧情时段", "null"};

    @Test
    public void planTextAvoidsBannedWords() {
        Rules rules = RulesFixtures.fullYearWithExtras();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Profile p = episodes("junpei", "akihiko");
        p.tier[4] = Profile.Tier.MUST;
        p.tier[6] = Profile.Tier.WANT;
        p.personaMode = Profile.PersonaMode.GOALS;
        Plan plan = Engine.plan(rules, p, start, null, new Engine.Options());
        List<String> texts = new ArrayList<>(plan.summary.notesZh);
        for (Plan.Step s : plan.steps) {
            texts.add(s.titleZh);
            texts.add(s.subtitleZh);
            if (s.whyZh != null) texts.add(s.whyZh);
            if (s.detailZh != null) texts.add(s.detailZh);
            if (s.hintZh != null) texts.add(s.hintZh);
            if (s.locationZh != null) texts.add(s.locationZh);
            texts.addAll(s.sideZh);
        }
        for (String t : texts) {
            for (String b : BANNED) assertFalse(t + " contains " + b, t.contains(b));
            assertFalse(t, t.matches("(?s).*[^日]元.*") || t.startsWith("元"));
            // Only the glossary's Latin tokens: RANK, MAX, Lv., F (floors); shop names in 「」 and 卡拉OK are names.
            String latin = t.replace("RANK", "").replace("MAX", "").replace("Lv.", "").replace("卡拉OK", "")
                    .replaceAll("「[^」]*」", "").replaceAll("\\d+F", "");
            assertFalse(t, latin.matches("(?s).*[A-Za-z].*"));
        }
    }

    // ------------------------------------------------------------------ alternatives

    @Test
    public void alternativesComeMostUsefulFirst() {
        Rules rules = RulesFixtures.small();
        Profile p = RulesFixtures.must(HIEROPHANT);
        SimState s = smallState(rules, Rules.slot(SATURDAY, false));
        s.rank[HIEROPHANT] = 2;
        s.unlockStep[HIEROPHANT] = 1;
        s.cash = 5_000;
        List<Plan.Step> alts = Engine.alternatives(rules, p, s);
        assertEquals(Engine.legalActions(rules, p, s).size(), alts.size());
        assertTrue(alts.size() >= 2);
        assertEquals("meet:hierophant", alts.get(0).action);
    }
}
