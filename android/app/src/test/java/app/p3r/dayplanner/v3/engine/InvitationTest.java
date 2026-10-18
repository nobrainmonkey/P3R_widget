package app.p3r.dayplanner.v3.engine;

import static app.p3r.dayplanner.v3.engine.RulesFixtures.S_MAGICIAN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.List;

/** Possible holiday SMS senders are manual records, never guaranteed rank-up opportunities. */
public class InvitationTest {
    private final Rules rules = RulesFixtures.small();
    private final Profile profile = RulesFixtures.others();
    private static final int INVITE_DAY = 33; // Fixture holiday, May 4.

    private SimState invitedAt(int rank) {
        SimState s = RulesFixtures.newGame(rules, Rules.slot(INVITE_DAY, false));
        s.rank[S_MAGICIAN] = rank;
        if (rank > 0) s.unlockStep[S_MAGICIAN] = rules.links[S_MAGICIAN].unlock.steps.size();
        return s;
    }

    private static Engine.Options quick() {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 0;
        return options;
    }

    @Test
    public void dCellIsAConditionalManualChoiceNotAnOrdinaryMeeting() {
        SimState s = invitedAt(2);
        List<Action> actions = Engine.legalActions(rules, profile, s);
        assertTrue(actions.contains(Action.invite(S_MAGICIAN)));
        assertFalse(actions.contains(Action.meet(S_MAGICIAN)));
        Model model = new Model(rules, profile, null);
        int[] codes = new int[Kernel.maxLegal(model)];
        int count = Kernel.legal(model, s, codes);
        for (int i = 0; i < count; i++) {
            assertFalse("automatic candidates must not include invitations", Kernel.type(codes[i]) == Kernel.T_INVITE);
            assertFalse("D is not an ordinary meeting", codes[i] == Kernel.encode(Action.meet(S_MAGICIAN)));
        }
        assertEquals(Action.invite(S_MAGICIAN), Action.parse(rules, "invite:magician"));
        assertEquals("invite:magician", Action.invite(S_MAGICIAN).key(rules));
    }

    @Test
    public void ordinaryCellOverlappingAnInvitationKeepsNormalRankUpSemantics() {
        rules.links[S_MAGICIAN].avail.set(INVITE_DAY);
        SimState s = invitedAt(1);
        List<Action> actions = Engine.legalActions(rules, profile, s);
        assertTrue(actions.contains(Action.meet(S_MAGICIAN)));
        assertFalse(actions.contains(Action.invite(S_MAGICIAN)));
        assertEquals(2, Engine.apply(rules, profile, s, Action.meet(S_MAGICIAN)).state.rank[S_MAGICIAN]);
    }

    @Test
    public void invitationsNeverRankUpEvenWhenReadyOrAlreadyAtThePointThreshold() {
        SimState s = invitedAt(9);
        s.pts[S_MAGICIAN] = 100;
        s.ready[S_MAGICIAN] = true;
        Engine.Applied applied = Engine.apply(rules, profile, s, Action.invite(S_MAGICIAN));
        assertEquals(9, applied.state.rank[S_MAGICIAN]);
        assertEquals(100, applied.state.pts[S_MAGICIAN]);
        assertTrue(applied.state.ready[S_MAGICIAN]);
        assertFalse(applied.predicted.rankUp);
        assertTrue(applied.predicted.certain);
        assertEquals(9, applied.predicted.rankFrom);
        assertEquals(9, applied.predicted.rankTo);
        assertTrue(applied.assumptions.contains("invite_gain_unmodeled"));
        assertEquals(s.slot + 1, applied.state.slot);
        assertEquals("input remains unchanged", Rules.slot(INVITE_DAY, false), s.slot);
    }

    @Test
    public void actualAttendanceCanRecordTheReadyCueAndConsumesExactlyOneSlot() {
        SimState s = invitedAt(4);
        s.pts[S_MAGICIAN] = 3;
        Outcome attended = Outcome.of(Outcome.Code.AS_PREDICTED);
        attended.sideDone = false;
        attended.readyCue = true;
        SimState after = Engine.applyOutcome(rules, profile, s, Action.invite(S_MAGICIAN), attended);
        assertEquals(s.slot + 1, after.slot);
        assertEquals(4, after.rank[S_MAGICIAN]);
        assertEquals("unmodeled answer-dependent affinity is not invented", 3, after.pts[S_MAGICIAN]);
        assertTrue(after.ready[S_MAGICIAN]);

        Outcome noRank = Outcome.of(Outcome.Code.NO_RANK_UP);
        noRank.sideDone = false;
        SimState unchanged = Engine.applyOutcome(rules, profile, s, Action.invite(S_MAGICIAN), noRank);
        assertEquals(3, unchanged.pts[S_MAGICIAN]);
        assertEquals(4, unchanged.rank[S_MAGICIAN]);
    }

    @Test
    public void legacyConfirmedMeetRanksRemainHistoricalFactsOnAnInvitationDate() {
        SimState s = invitedAt(1);
        Outcome confirmed = Outcome.of(Outcome.Code.AS_PREDICTED);
        confirmed.rank = 2;
        confirmed.sideDone = false;
        Profile changed = profile.copy();
        changed.personaBonus = true;
        changed.goalBuffer = true;
        SimState after = Engine.applyOutcome(rules, changed, s, Action.meet(S_MAGICIAN), confirmed);
        assertEquals(2, after.rank[S_MAGICIAN]);
        assertEquals(s.slot + 1, after.slot);
    }

    @Test
    public void manualChoiceExplainsSmsRequirementWithoutShowingRankSceneAnswers() {
        SimState s = invitedAt(1);
        Plan.Step invite = Engine.alternatives(rules, profile, s, quick()).stream()
                .filter(x -> x.action.equals("invite:magician")).findFirst().orElseThrow();
        assertEquals("invite", invite.kind);
        assertTrue(invite.subtitleZh, invite.subtitleZh.contains("收到短信才选"));
        assertTrue(invite.detailZh, invite.detailZh.contains("不直接升级"));
        assertFalse(invite.predicted.rankUp);
        assertEquals(1, invite.predicted.rankTo);
        assertTrue(DialogueHint.forMeeting(rules, profile, invite) == DialogueHint.Answers.NONE);
        assertFalse(invite.detailZh.contains("回答"));
    }

    @Test
    public void noSmsIsAssumedForAutomaticPlansAndValidatorRejectsAPlantedInvitation() {
        SimState s = invitedAt(2);
        Plan plan = Engine.plan(rules, profile, s, null, quick());
        assertTrue(Engine.validate(rules, profile, s, plan).toString(), Engine.validate(rules, profile, s, plan).isEmpty());
        for (Plan.Step step : plan.steps) {
            assertFalse(step.action, step.action.startsWith("invite:"));
            Action action = Action.parse(rules, step.action);
            if (action != null && action.type == Action.Type.MEET) {
                assertTrue(step.action, rules.links[action.target].avail.get(Rules.dayOf(step.slot)));
            }
        }
        plan.at(s.slot).action = "invite:magician";
        plan.at(s.slot).kind = "invite";
        assertTrue(Engine.validate(rules, profile, s, plan).toString(), Engine.validate(rules, profile, s, plan).stream()
                .anyMatch(x -> x.contains("unconfirmed holiday invitation")));
    }

    @Test
    public void dCellCannotUnlockAnUnestablishedLinkOrOfferAMaxedEveningOrLockedInvite() {
        SimState unestablished = invitedAt(0);
        assertFalse(rules.links[S_MAGICIAN].meetsOn(INVITE_DAY));
        assertFalse(Engine.legalActions(rules, profile, unestablished).contains(Action.unlock(S_MAGICIAN, 0)));
        assertFalse(Engine.legalActions(rules, profile, unestablished).contains(Action.invite(S_MAGICIAN)));
        assertFalse(Engine.legalActions(rules, profile, invitedAt(10)).contains(Action.invite(S_MAGICIAN)));
        SimState evening = invitedAt(2);
        evening.slot++;
        assertFalse(Engine.legalActions(rules, profile, evening).contains(Action.invite(S_MAGICIAN)));
        Rules lockedRules = RulesFixtures.small();
        lockedRules.days[INVITE_DAY].dayLock = "story";
        SimState locked = RulesFixtures.newGame(lockedRules, Rules.slot(INVITE_DAY, false));
        locked.rank[S_MAGICIAN] = 2;
        assertFalse(Engine.legalActions(lockedRules, profile, locked).contains(Action.invite(S_MAGICIAN)));
    }
}
