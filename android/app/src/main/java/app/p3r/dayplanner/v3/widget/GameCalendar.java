package app.p3r.dayplanner.v3.widget;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import app.p3r.dayplanner.v3.model.Rules;

/**
 * Calendar facts for the widget's date plate, computed from the game-day ordinal (days since
 * 2009-04-01, docs/DESIGN_v3.md §3) without touching the device clock.
 *
 * <p>When the rule pack is available its per-day weekday, moon phase and day type win. Otherwise the
 * moon phase is interpolated between the game's full-moon dates and the school-day test uses a small
 * built-in list of 2009/2010 holidays and vacations; both are decorative approximations.
 */
public final class GameCalendar {
    /** Day ordinal 0. */
    public static final LocalDate DAY_ZERO = LocalDate.of(2009, 4, 1);

    private static final String[] WEEKDAYS_ZH = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final double SYNODIC_DAYS = 29.53;
    /** Full-moon story dates in P3R (4/9 … 12/31), as day ordinals. */
    private static final int[] FULL_MOONS = {
            ordinal(2009, 4, 9), ordinal(2009, 5, 9), ordinal(2009, 6, 8), ordinal(2009, 7, 7),
            ordinal(2009, 8, 6), ordinal(2009, 9, 5), ordinal(2009, 10, 4), ordinal(2009, 11, 3),
            ordinal(2009, 12, 2), ordinal(2009, 12, 31)};
    /** Single holidays (Mon–Sat) without classes, approximate (used only without the rule pack). */
    private static final int[] HOLIDAYS = {
            ordinal(2009, 4, 29), ordinal(2009, 5, 4), ordinal(2009, 5, 5), ordinal(2009, 5, 6),
            ordinal(2009, 7, 20), ordinal(2009, 9, 21), ordinal(2009, 9, 22), ordinal(2009, 9, 23),
            ordinal(2009, 10, 12), ordinal(2009, 11, 3), ordinal(2009, 11, 23), ordinal(2009, 12, 23),
            ordinal(2010, 1, 11)};
    /** Inclusive vacation ranges, approximate. */
    private static final int[][] VACATIONS = {
            {ordinal(2009, 7, 21), ordinal(2009, 8, 31)},
            {ordinal(2009, 12, 24), ordinal(2010, 1, 7)}};

    /** Plate data for one slot. */
    public static final class Info {
        public int day;
        public int month;
        public int dayOfMonth;
        /** ISO weekday 1 = Monday … 7 = Sunday. */
        public int weekday;
        /** 0..7, 4 = full moon. */
        public int moonPhase;
        public boolean schoolDay;
        public boolean evening;

        /** "10/22". */
        public String numerals() {
            return month + "/" + dayOfMonth;
        }

        public String weekdayZh() {
            return WEEKDAYS_ZH[(weekday + 6) % 7];
        }

        /** 白天 / 放学后 / 晚上 (docs/DESIGN_v3.md §3). */
        public String periodZh() {
            if (evening) return "晚上";
            return schoolDay ? "放学后" : "白天";
        }

        /** Spoken form, e.g. "10月22日 周四 放学后". */
        public String spokenZh() {
            return month + "月" + dayOfMonth + "日 " + weekdayZh() + " " + periodZh();
        }
    }

    private GameCalendar() {}

    public static int ordinal(int year, int month, int dayOfMonth) {
        return (int) ChronoUnit.DAYS.between(DAY_ZERO, LocalDate.of(year, month, dayOfMonth));
    }

    /** Calendar ordinal of a legacy guide date (April–December 2009, January–March 2010). */
    public static int ordinalOfGuideDate(int month, int dayOfMonth) {
        return ordinal(month >= 4 ? 2009 : 2010, month, dayOfMonth);
    }

    /** Info for a plan slot ({@code slot = day*2 + evening}). {@code rules} may be null. */
    public static Info ofSlot(int slot, Rules rules) {
        Info info = ofDay(Rules.dayOf(slot), rules);
        info.evening = Rules.isEvening(slot);
        return info;
    }

    /** Info for a day ordinal (daytime). {@code rules} may be null. */
    public static Info ofDay(int day, Rules rules) {
        LocalDate date = DAY_ZERO.plusDays(day);
        Info info = new Info();
        info.day = day;
        info.month = date.getMonthValue();
        info.dayOfMonth = date.getDayOfMonth();
        info.weekday = date.getDayOfWeek().getValue();
        info.moonPhase = approximateMoonPhase(day);
        info.schoolDay = approximateSchoolDay(day, info.weekday);
        Rules.Day ruleDay = rules == null ? null : rules.day(day);
        if (ruleDay != null) {
            if (ruleDay.wd >= 1 && ruleDay.wd <= 7) info.weekday = ruleDay.wd;
            // 0 is also the field's default, so only a positive phase or the full-moon flag overrides.
            if (ruleDay.fullMoon) info.moonPhase = 4;
            else if (ruleDay.moon > 0 && ruleDay.moon <= 7) info.moonPhase = ruleDay.moon;
            info.schoolDay = ruleDay.type == Rules.DayType.SCHOOL || ruleDay.type == Rules.DayType.EXAM;
        }
        return info;
    }

    /**
     * Eight-step phase (0 new … 4 full … 7 waning crescent) interpolated between the full-moon story
     * dates; before the first and after the last anchor a mean synodic month is assumed.
     */
    public static int approximateMoonPhase(int day) {
        double previous;
        double next;
        if (day < FULL_MOONS[0]) {
            next = FULL_MOONS[0];
            previous = next - SYNODIC_DAYS;
            while (day < previous) {
                next = previous;
                previous -= SYNODIC_DAYS;
            }
        } else if (day >= FULL_MOONS[FULL_MOONS.length - 1]) {
            previous = FULL_MOONS[FULL_MOONS.length - 1];
            next = previous + SYNODIC_DAYS;
            while (day >= next) {
                previous = next;
                next += SYNODIC_DAYS;
            }
        } else {
            int i = 0;
            while (FULL_MOONS[i + 1] <= day) i++;
            previous = FULL_MOONS[i];
            next = FULL_MOONS[i + 1];
        }
        double fraction = (day - previous) / (next - previous);
        int steps = (int) Math.floor(fraction * 8 + 0.5);
        return Math.floorMod(4 + steps, 8);
    }

    static boolean approximateSchoolDay(int day, int weekday) {
        if (weekday == 7) return false;
        for (int holiday : HOLIDAYS) {
            if (holiday == day) return false;
        }
        for (int[] range : VACATIONS) {
            if (day >= range[0] && day <= range[1]) return false;
        }
        return true;
    }
}
