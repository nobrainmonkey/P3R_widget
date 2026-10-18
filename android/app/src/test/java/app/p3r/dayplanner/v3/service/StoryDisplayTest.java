package app.p3r.dayplanner.v3.service;

import org.junit.Test;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.TestRules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Presentation of story days is independent of the forecast and of gameplay transitions. */
public class StoryDisplayTest {
    @Test
    public void acceptedChristmasInvitationReplacesTheDefaultDeclineNote() {
        Rules rules = TestRules.create();
        int day = TestRules.MAY_12;
        rules.days[day].md = "12-24";
        rules.days[day].noteZh = "默认婉拒平安夜邀约";
        Profile profile = new Profile();
        profile.christmasLink = rules.links[0].id;
        int first = Rules.slot(day, false);
        StoryDisplay story = StoryDisplay.create(rules, profile, "accepted", first, first + 1, first);
        assertTrue(story.at(first).subtitleZh, story.at(first).subtitleZh.contains("已经答应了"));
        assertTrue(story.at(first).subtitleZh.contains(rules.links[0].personZh));
        assertTrue(!story.at(first).detailZh.contains("婉拒"));
    }

    @Test
    public void overlayKeepsStoryReasonsNotesAndExamAnswersAfterReplanning() {
        Rules rules = TestRules.create();
        int day = TestRules.MAY_12;
        int first = Rules.slot(day, false);
        for (int d = day; d < day + 2; d++) {
            rules.days[d].dayLock = "school_trip";
            rules.days[d].eveLock = "school_trip";
            rules.days[d].noteZh = "这天去修学旅行，不能自由行动";
        }
        rules.lockReasonsZh.put("school_trip", "修学旅行");
        Rules.Reminder exam = new Rules.Reminder();
        exam.id = "exam.test";
        exam.kind = "exam";
        exam.day = day + 1;
        exam.answerZh = "摩西";
        exam.zh = "考试：摩西";
        rules.reminders.items.add(exam);
        StoryDisplay story = StoryDisplay.create(rules, new Profile(), "checkpoint:record:days", first, first + 3, first);
        assertEquals("修学旅行", story.at(first).titleZh);
        assertEquals("这天去修学旅行，不能自由行动", story.at(first).subtitleZh);
        assertEquals("one acknowledgement never swallows the next day", first + 1, story.currentLastSlot());

        Plan forecast = new Plan();
        forecast.fromSlot = first + 4;
        forecast.toSlot = first + 4;
        Plan.Step action = new Plan.Step();
        action.slot = first + 4;
        action.action = "free";
        action.kind = "free";
        forecast.steps.add(action);
        Plan display = story.overlay(forecast, first + 4);
        assertEquals(first, display.fromSlot);
        assertEquals(first + 4, display.toSlot);
        assertEquals(5, display.steps.size());
        assertEquals("摩西", display.reminders.get(0).answerZh);
        assertSame(action, display.at(first + 4));
        assertSame(forecast.summary, display.summary);
        assertEquals("forecast has not acquired story steps", 1, forecast.steps.size());
        assertTrue(forecast.reminders.isEmpty());
        assertNull(StoryDisplay.create(rules, new Profile(), "checkpoint:record:days", first, first + 3, first + 4));
    }

    @Test
    public void differentReasonsOnTheSameDayRemainSeparate() {
        Rules rules = TestRules.create();
        int first = Rules.slot(TestRules.MAY_12, false);
        rules.days[TestRules.MAY_12].dayLock = "exam";
        rules.days[TestRules.MAY_12].eveLock = "full_moon";
        rules.lockReasonsZh.put("exam", "考试期间");
        StoryDisplay story = StoryDisplay.create(rules, new Profile(), "different-reasons", first, first + 1, first);
        assertEquals(first, story.currentLastSlot());
        assertEquals("考试期间", story.at(first).titleZh);
        assertEquals("满月作战", story.at(first + 1).titleZh);
        assertNull("a free slot cannot be presented as a story day",
                StoryDisplay.create(rules, new Profile(), "not-a-lock", first, first + 2, first));
    }
}
