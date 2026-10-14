package app.p3r.dayplanner.v3.widget;

import org.junit.Test;

import app.p3r.dayplanner.v3.model.Rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain-JVM checks of the plate's calendar maths (no device clock, no rule pack needed). */
public class GameCalendarTest {

    @Test
    public void ordinalsFollowTheDesignAxis() {
        assertEquals(0, GameCalendar.ordinal(2009, 4, 1));
        assertEquals(6, GameCalendar.ordinal(2009, 4, 7));
        assertEquals(305, GameCalendar.ordinal(2010, 1, 31));
    }

    @Test
    public void plateTextForADaytimeAndAnEveningSlot() {
        int day = GameCalendar.ordinal(2009, 10, 22);
        GameCalendar.Info daytime = GameCalendar.ofSlot(Rules.slot(day, false), null);
        assertEquals("10/22", daytime.numerals());
        assertEquals("周四", daytime.weekdayZh());
        assertEquals("放学后", daytime.periodZh());
        assertEquals("10月22日 周四 放学后", daytime.spokenZh());
        GameCalendar.Info evening = GameCalendar.ofSlot(Rules.slot(day, true), null);
        assertEquals("晚上", evening.periodZh());
        assertEquals("周三", GameCalendar.ofDay(0, null).weekdayZh());
    }

    @Test
    public void holidaysSundaysAndVacationsAreDaytimeNotAfterSchool() {
        assertEquals("白天", GameCalendar.ofDay(GameCalendar.ordinal(2009, 11, 1), null).periodZh()); // Sunday
        assertEquals("白天", GameCalendar.ofDay(GameCalendar.ordinal(2009, 11, 3), null).periodZh()); // 文化之日
        assertEquals("白天", GameCalendar.ofDay(GameCalendar.ordinal(2009, 8, 12), null).periodZh()); // summer
        assertEquals("白天", GameCalendar.ofDay(GameCalendar.ordinal(2010, 1, 4), null).periodZh()); // winter break
        assertEquals("放学后", GameCalendar.ofDay(GameCalendar.ordinal(2009, 11, 2), null).periodZh());
        assertEquals("放学后", GameCalendar.ofDay(GameCalendar.ordinal(2009, 11, 7), null).periodZh()); // Saturday class
    }

    @Test
    public void moonIsFullOnStoryFullMoonsAndNewInBetween() {
        int[][] fullMoons = {{4, 9}, {5, 9}, {6, 8}, {7, 7}, {8, 6}, {9, 5}, {10, 4}, {11, 3}, {12, 2}, {12, 31}};
        for (int[] md : fullMoons) {
            assertEquals(md[0] + "/" + md[1], 4, GameCalendar.approximateMoonPhase(GameCalendar.ordinal(2009, md[0], md[1])));
        }
        assertEquals(0, GameCalendar.approximateMoonPhase(GameCalendar.ordinal(2009, 10, 19)));
        assertEquals(4, GameCalendar.approximateMoonPhase(GameCalendar.ordinal(2010, 1, 30)));
        int waxing = GameCalendar.approximateMoonPhase(GameCalendar.ordinal(2009, 10, 27));
        assertTrue("waxing before 11/3: " + waxing, waxing >= 1 && waxing <= 3);
        for (int d = 0; d <= 305; d++) {
            int phase = GameCalendar.approximateMoonPhase(d);
            assertTrue(phase >= 0 && phase <= 7);
        }
    }

    @Test
    public void rulePackWinsWhenAvailable() {
        Rules rules = new Rules();
        int d = GameCalendar.ordinal(2009, 10, 22);
        rules.days = new Rules.Day[d + 1];
        Rules.Day day = new Rules.Day();
        day.d = d;
        day.md = "10-22";
        day.wd = 4;
        day.type = Rules.DayType.HOLIDAY;
        day.fullMoon = true;
        rules.days[d] = day;
        GameCalendar.Info info = GameCalendar.ofDay(d, rules);
        assertEquals(4, info.moonPhase);
        assertFalse(info.schoolDay);
        assertEquals("白天", info.periodZh());
        day.fullMoon = false;
        day.moon = 0; // the field's default: keep the approximation
        assertEquals(GameCalendar.approximateMoonPhase(d), GameCalendar.ofDay(d, rules).moonPhase);
    }

    @Test
    public void everyShaftCompositionIsUsed() {
        boolean[] seen = new boolean[WidgetMotif.VARIANTS];
        for (int d = 0; d <= 305; d++) seen[WidgetMotif.forDay(d)] = true;
        for (boolean used : seen) assertTrue("every shaft composition is used", used);
    }
}
