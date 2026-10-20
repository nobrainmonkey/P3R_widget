package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Re-audit (3.0.1) wording gaps on the app screens: the 规划说明 card promised a re-run that might
 * never come and repeated the plan's own note; list text used the plate's 「4/22」; 「规划」 in the
 * route note; the short 「字条」 before the full name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReauditWordingTest extends UiTestBase {
    /** The engine's notes (Labels.BUDGET_NOTE / BUDGET_NOTE_FOLLOW_UP, and core's newer wording). */
    private static final String NOTE_PENDING = "这次没来得及算完，稍后会自动再算一次";
    private static final String NOTE_FOLLOW_UP = "这次没来得及算完，记下一个结果后会重新安排";
    private static final String NOTE_ROUGH = "这次没来得及算完，后半段是粗略安排；记下一个结果后会重新安排";
    /**
     * M/D followed by a weekday or a period in the same text: the plate's numerals used as body text
     * (the date plate itself shows 「10/22」 and its weekday in separate views).
     */
    private static final Pattern SLASH_DATE = Pattern.compile("\\d{1,2}/\\d{1,2}[ \\u00A0]*(周|放学后|白天|晚上)");

    private static Plan exhausted(String... notes) {
        Plan plan = new Plan();
        plan.budgetExhausted = true;
        plan.summary.notesZh.addAll(Arrays.asList(notes));
        return plan;
    }

    // ------------------------------------------------------------------ 规划说明: the time budget

    @Test
    public void theBudgetLinePromisesARerunOnlyWhileOneIsPending() {
        assertNull("a complete plan says nothing about time", InfoPages.budgetLine(new Plan()));

        Plan first = exhausted(NOTE_PENDING, "女教皇：没排到 MAX，先保证了皇帝 · 预计停在 RANK 7");
        assertEquals(InfoPages.BUDGET_PENDING, InfoPages.budgetLine(first));
        assertEquals("the card says it; the bullets do not repeat it",
                Collections.singletonList("女教皇：没排到 MAX，先保证了皇帝 · 预计停在 RANK 7"), InfoPages.notesBelow(first));

        // The follow-up itself ran out again (re-audit case a), with today's and core's newer note.
        for (String note : new String[]{NOTE_FOLLOW_UP, NOTE_ROUGH}) {
            Plan followUp = exhausted(note);
            assertEquals(note, InfoPages.BUDGET_ROUGH, InfoPages.budgetLine(followUp));
            assertTrue(InfoPages.notesBelow(followUp).isEmpty());
        }
        assertEquals("后半段先粗排，下次记录后再细算", InfoPages.BUDGET_ROUGH);
        assertEquals("后半段还在细算，稍后自动更新", InfoPages.BUDGET_PENDING);

        // Plan.followUp (set by the service on its second computation) wins over an older note.
        assertFalse(InfoPages.followUpPending(true, true, Collections.singletonList(NOTE_PENDING)));
        assertTrue(InfoPages.followUpPending(true, false, Collections.singletonList(NOTE_PENDING)));
        assertTrue("no note at all: the service queues one more run", InfoPages.followUpPending(true, false, Collections.emptyList()));
        assertFalse(InfoPages.followUpPending(false, false, Collections.singletonList(NOTE_PENDING)));
    }

    @Test
    public void aStoredFollowUpPlanNeverPromisesAnotherRun() throws Exception {
        JSONObject json = exhausted(NOTE_PENDING).toJson();
        json.put("followUp", true);
        Plan stored = Plan.fromJson(json);
        assertTrue(InfoPages.isFollowUp(stored));
        assertEquals(InfoPages.BUDGET_ROUGH, InfoPages.budgetLine(stored));
    }

    @Test
    public void theExplainPageSaysItOnce() {
        api.snap.plan.budgetExhausted = true;
        api.snap.plan.summary.notesZh.add(0, NOTE_PENDING);
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "规划说明");
        String text = Shots.allText(a.getWindow().getDecorView());
        assertEquals("the engine's note is said once, as the hero's chip", 0, count(text, "这次没来得及算完"));
        assertEquals(text, 1, count(text, InfoPages.BUDGET_PENDING));
        // 女教皇 is a goal: its note is the reason line under its row in 目标.
        assertTrue("the other notes are still listed", Shots.has(a, "没排到 MAX，先保证了皇帝 · 预计停在 RANK 7"));
        Shots.shot(a, "explain_budget_pending");
    }

    @Test
    public void theExplainPageOfAFollowUpPlanPromisesNoRerun() {
        api.snap.plan.budgetExhausted = true;
        api.snap.plan.summary.notesZh.add(0, NOTE_FOLLOW_UP);
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "规划说明");
        String text = Shots.allText(a.getWindow().getDecorView());
        assertFalse(text, text.contains("稍后会自动再算一次") || text.contains(InfoPages.BUDGET_PENDING));
        assertEquals(text, 0, count(text, "这次没来得及算完"));
        assertTrue(Shots.has(a, InfoPages.BUDGET_ROUGH));
        Shots.shot(a, "explain_budget_follow_up");
    }

    // ------------------------------------------------------------------ dates in list text

    @Test
    public void todayGroupsReadMonthDayAndWeekday() {
        assertEquals("10月23日 周五", Zh.dayAndWeekday(Fixtures.OCT_22 + 1));
        HomeActivity a = home();
        String text = Shots.allText(a.getWindow().getDecorView());
        assertTrue(text, text.contains("10月23日 周五"));
        assertNoSlashDates(text);
    }

    @Test
    public void catchUpRowsReadMonthDayAndPeriod() {
        ResultActivity a = result(Fixtures.SLOT_NOW + 3, "act:job_chagall", Intents.MODE_RESULT);
        String text = Shots.allText(a.getWindow().getDecorView());
        assertTrue(text, text.contains("10月22日 放学后 · "));
        assertTrue(text, text.contains("10月22日 晚上 · "));
        assertNoSlashDates(text);
        Shots.shot(a, "result_catchup_dates");
    }

    @Test
    public void theWizardDiffReadsMonthDayAndPeriod() {
        StateDraft d = new StateDraft(StateDraft.Mode.EDIT, api.rules, api.snap.observed, Fixtures.profileMid(), api.snap.plan, 12);
        d.setSlot(Rules.slot(Fixtures.day(10, 25), false));
        StateDraft.Change date = d.changes().get(0);
        assertEquals("日期", date.labelZh);
        assertEquals("10月22日 放学后", date.beforeZh);
        assertEquals("10月25日 白天", date.afterZh);

        StateDraft first = new StateDraft(StateDraft.Mode.ONBOARDING, api.rules, null, new Profile(), null, 0);
        for (StateDraft.Change change : first.changes()) {
            if ("日期".equals(change.labelZh)) {
                assertTrue(change.afterZh, change.afterZh.matches("4月18日 (白天|放学后)"));
            }
        }
    }

    // ------------------------------------------------------------------ words

    @Test
    public void routeNoteAndDatingSiteNoteUseTheGlossaryWords() {
        assertEquals("结缘网站的字条", StepKind.NOTE.tagZh);
        Plan.Step note = new Plan.Step();
        note.slot = Fixtures.SLOT_NOW + 1;
        note.action = "note:empress";
        note.kind = "note";
        note.titleZh = "结缘网站的字条 · 桐条美鹤";
        String label = Outcomes.summary(api.rules, note, Outcomes.notDone());
        assertTrue(label, label.endsWith(" · 没用结缘网站的字条"));

        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_LINKS);
        // The route is a plain choice in 社群详情 (「回答按哪条路线？」), without a reassuring note.
        String links = Shots.allText(a.getWindow().getDecorView());
        assertFalse(links.contains("规划不会"));
        assertFalse(links.contains("恋爱路线"));
    }

    private static void assertNoSlashDates(String text) {
        Matcher m = SLASH_DATE.matcher(text);
        assertFalse("M/D in body text: " + (m.find() ? m.group() : ""), SLASH_DATE.matcher(text).find());
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            n++;
        }
        return n;
    }
}
