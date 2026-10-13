package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;
import app.p3r.dayplanner.v3.ui.Outcomes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Regressions for F1 and F2 on the real pack, real engine and SQLite: records are made exactly as
 * the result sheet and the widget make them, then the profile changes (Persona mode, goal tiers,
 * 其余社群). The observed ranks, unlock progress, cash and every history line must stay as the
 * player recorded them; only the plan may change.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProfileChangeReplayTest {
    private Context context;
    private Rules rules;
    private PlannerService service;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        File pack = new File("src/main/assets/p3r/p3r_rules.json");
        rules = RulesLoader.parse(new String(Files.readAllBytes(pack.toPath()), StandardCharsets.UTF_8));
        Wiring wiring = new Wiring();
        wiring.rules = () -> rules;
        wiring.planBudgetMillis = 300;
        wiring.databaseName = "profile-change-" + UUID.randomUUID() + ".db";
        service = PlannerService.create(context, wiring);
    }

    @After
    public void tearDown() {
        service.close();
    }

    /** F1: confirmed RANK results (and 「见面了」) survive Persona-mode and goal-tier changes. */
    @Test
    public void confirmedRanksAndHistorySurviveProfileChanges() {
        Profile p = new Profile();
        p.personaMode = Profile.PersonaMode.ALL;
        p.personaBonus = true;
        assertTrue(service.saveCheckpoint(id(), service.snapshot().stateRevision, newGame(Rules.slot(20, false)), p).ok);
        settled("onboard");
        int rankUps = 0;
        int meetings = 0;
        for (int i = 0; i < 70; i++) {
            Plan.Step step = recordAsUi(i % 5 == 4);
            if ("social".equals(step.kind)) {
                meetings++;
                if (step.predicted.rankUp) rankUps++;
            }
            settled("record " + i);
        }
        assertTrue("enough meetings to matter: " + meetings, meetings >= 15);
        assertTrue("enough rank-ups to matter: " + rankUps, rankUps >= 8);
        PlannerApi.Snapshot recorded = service.snapshot();
        List<String> history = lines();

        // 基本都带 → 不带: every meeting computed with ×1.51 would now be re-derived without it.
        Profile none = recorded.profile.copy();
        none.personaMode = Profile.PersonaMode.NONE;
        none.personaBonus = false;
        saveProfile(none);
        assertUnchanged("Persona 不带", recorded.observed, history);

        // Six links become 想要 (the goal buffer adds a meeting before each of their rank-ups).
        Profile goals = service.snapshot().profile.copy();
        for (String id : new String[]{"magician", "chariot", "emperor", "hierophant", "hermit", "strength"}) {
            goals.tier[rules.link(id).idx] = Profile.Tier.WANT;
        }
        saveProfile(goals);
        assertUnchanged("six goals", recorded.observed, history);

        // And back: goals cleared, Persona on again, Charm bonus off, no answers counted.
        Profile back = service.snapshot().profile.copy();
        back.tier = new Profile().tier;
        back.personaMode = Profile.PersonaMode.ALL;
        back.personaBonus = true;
        back.charmBonus = false;
        back.answers = Profile.AnswerPolicy.NONE;
        saveProfile(back);
        assertUnchanged("goals cleared", recorded.observed, history);
        settled("after the profile changes");
    }

    /** F2: a goal added after the records does not invent a payment the player never made. */
    @Test
    public void addingAGoalLaterDoesNotInventSideSteps() {
        int slot = Rules.slot(52, true); // Saturday 5/23 evening: a Devil payment day
        Profile p = new Profile();
        p.otherLinks = false;
        p.tier[rules.link("magician").idx] = Profile.Tier.MUST;
        assertTrue(service.saveCheckpoint(id(), service.snapshot().stateRevision, devilReady(slot), p).ok);
        Plan plan = settled("onboard");
        assertFalse("the plan lists no Devil payment", plan.at(slot).side.contains("unlock:devil:0"));
        recordAsUi(false);
        settled("first");
        recordAsUi(false);
        settled("second");
        PlannerApi.Snapshot recorded = service.snapshot();
        int devil = rules.link("devil").idx;
        assertEquals(0, recorded.observed.unlockStep[devil]);
        List<String> history = lines();

        Profile want = recorded.profile.copy();
        want.tier[devil] = Profile.Tier.WANT;
        saveProfile(want);
        assertUnchanged("恶魔 → 想要", recorded.observed, history);
        Profile all = service.snapshot().profile.copy();
        all.otherLinks = true;
        saveProfile(all);
        assertUnchanged("其余社群 on", recorded.observed, history);
    }

    /** F2: clearing a goal (or 其余社群) after the records does not refund a payment the player made. */
    @Test
    public void clearingAGoalLaterDoesNotRefundSideSteps() {
        int slot = Rules.slot(52, true);
        int devil = rules.link("devil").idx;
        Profile p = new Profile();
        p.otherLinks = false;
        p.tier[rules.link("magician").idx] = Profile.Tier.MUST;
        p.tier[devil] = Profile.Tier.WANT;
        SimState start = devilReady(slot);
        assertTrue(service.saveCheckpoint(id(), service.snapshot().stateRevision, start, p).ok);
        Plan plan = settled("onboard");
        assertTrue("the plan pays the Devil beside this evening: " + plan.at(slot).side,
                plan.at(slot).side.contains("unlock:devil:0"));
        recordAsUi(false);
        settled("paid");
        PlannerApi.Snapshot recorded = service.snapshot();
        assertEquals("paid beside the evening's action", 1, recorded.observed.unlockStep[devil]);
        List<String> history = lines();

        Profile cleared = recorded.profile.copy();
        cleared.tier[devil] = Profile.Tier.NONE;
        saveProfile(cleared);
        assertUnchanged("恶魔 cleared", recorded.observed, history);
        assertEquals("still paid", 1, service.snapshot().observed.unlockStep[devil]);
    }

    // ------------------------------------------------------------------ helpers

    private void assertUnchanged(String label, SimState recorded, List<String> history) {
        SimState now = service.snapshot().observed;
        assertEquals(label + ": next slot", recorded.slot, now.slot);
        assertArrayEquals(label + ": confirmed ranks", recorded.rank, now.rank);
        assertArrayEquals(label + ": unlock progress", recorded.unlockStep, now.unlockStep);
        assertEquals(label + ": cash", recorded.cash, now.cash);
        assertEquals(label + ": history", history, lines());
        assertEquals(label + ": nothing to sync", null, service.snapshot().noticeZh);
    }

    private void saveProfile(Profile profile) {
        PlannerApi.Receipt r = service.saveProfile(id(), service.snapshot().profileRevision, profile);
        assertTrue(r.errorZh, r.ok);
    }

    private List<String> lines() {
        List<String> out = new ArrayList<>();
        for (PlannerApi.HistoryItem item : service.history(0)) out.add(item.summaryZh);
        return out;
    }

    /**
     * Records the observed slot's planned step the way the app does when it went as planned: the
     * result sheet's Outcome shapes (「升到 RANK n 了」 / 「见面了」 carry the predicted rank), or the
     * widget's (no rank) when {@code widget}; tower evenings through the quick sheet.
     */
    private Plan.Step recordAsUi(boolean widget) {
        PlannerApi.Snapshot s = service.snapshot();
        Plan.Step step = s.plan.at(s.observed.slot);
        assertNotNull(step);
        PlannerApi.Receipt r;
        if ("tower".equals(step.kind)) {
            PlannerApi.TowerReturn t = new PlannerApi.TowerReturn();
            t.slot = step.slot;
            t.block = -1;
            t.floor = Math.max(s.observed.floor, 5);
            t.balanceMode = false;
            t.amount = 0;
            r = service.recordTower(id(), s.stateRevision, t);
        } else {
            Outcome o;
            if (widget) {
                o = Outcome.of(Outcome.Code.AS_PREDICTED);
            } else if ("social".equals(step.kind) && !step.predicted.rankUp) {
                o = Outcomes.met(step, false, s.planCurrent);
            } else {
                o = Outcomes.asPredicted(step, s.planCurrent);
            }
            r = service.recordOutcome(id(), s.stateRevision, step.slot, step.action, o);
        }
        assertTrue(r.errorZh, r.ok);
        return step;
    }

    private Plan settled(String label) {
        assertTrue("planner did not go idle (" + label + ")", service.awaitIdle(120_000));
        shadowOf(Looper.getMainLooper()).idle();
        PlannerApi.Snapshot s = service.snapshot();
        if (s.planErrorZh != null) throw new AssertionError(label + ": " + s.planErrorZh);
        assertNotNull(label, s.plan);
        assertTrue(label + ": plan current", s.planCurrent);
        List<String> problems = Engine.validate(rules, s.profile, s.observed, s.plan);
        assertTrue(label + ": " + problems, problems.isEmpty());
        return s.plan;
    }

    private SimState newGame(int slot) {
        SimState st = new SimState(rules.blocks.length, rules.activities.length);
        st.slot = slot;
        st.cash = 5_000;
        st.cashExact = true;
        for (int i = 0; i < 3; i++) st.statPts[i] = rules.stats[i].thresholds[1];
        return st;
    }

    /** Charm Lv4, Hermit RANK 4 and ¥60,000: the Devil's payments are possible. */
    private SimState devilReady(int slot) {
        SimState st = newGame(slot);
        st.cash = 60_000;
        int charm = rules.stat("charm").idx;
        st.statPts[charm] = rules.stats[charm].thresholds[4];
        int hermit = rules.link("hermit").idx;
        st.rank[hermit] = 4;
        st.unlockStep[hermit] = rules.links[hermit].unlock.steps.size();
        for (String id : new String[]{"magician", "chariot", "emperor", "hierophant", "strength"}) {
            Rules.Link l = rules.link(id);
            st.rank[l.idx] = 2;
            st.unlockStep[l.idx] = l.unlock.steps.size();
        }
        return st;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
