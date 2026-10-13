package app.p3r.dayplanner.v3.ui;

import java.time.LocalDate;
import java.util.Locale;

import app.p3r.dayplanner.v3.model.Rules;

/**
 * Simplified Chinese wording and number formatting used across the v3 screens.
 *
 * <p>Conventions (docs/DESIGN_v3.md §4, §7): link ranks are "RANK N" with "MAX" at 10, stats are
 * "Lv.N" (the top level is "MAX"), money is "8,200 日元", dates are "10月22日", the free daytime block is "放学后" on school
 * days and "白天" otherwise, and the evening is "晚上".
 */
public final class Zh {
    /** Day ordinal 0 of the calendar axis. */
    private static final LocalDate EPOCH = LocalDate.of(2009, 4, 1);
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] ROMAN = {
        "0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
        "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX", "XXI"
    };

    private Zh() {}

    /** "8,200" — thousands separators, no unit. */
    public static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** "8,200 日元". */
    public static String yen(long value) {
        return number(value) + " 日元";
    }

    /** "+2,500 日元" / "−1,000 日元" (typographic minus). */
    public static String yenDelta(long value) {
        if (value >= 0) {
            return "+" + yen(value);
        }
        return "−" + yen(-value);
    }

    /** Calendar date for a day ordinal (days since 2009-04-01). */
    public static LocalDate dateOf(int day) {
        return EPOCH.plusDays(day);
    }

    /** Day ordinal for a calendar date. */
    public static int dayOf(LocalDate date) {
        return (int) (date.toEpochDay() - EPOCH.toEpochDay());
    }

    /** "10月22日". */
    public static String date(int day) {
        LocalDate date = dateOf(day);
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    /** "10/22" — the numeral form used on date plates. */
    public static String shortDate(int day) {
        LocalDate date = dateOf(day);
        return date.getMonthValue() + "/" + date.getDayOfMonth();
    }

    /** "周四" for ISO weekday 1..7. */
    public static String weekday(int isoWeekday) {
        if (isoWeekday < 1 || isoWeekday > 7) {
            return "";
        }
        return WEEKDAYS[isoWeekday - 1];
    }

    /** "周四" for a day ordinal (independent of the device date). */
    public static String weekdayOf(int day) {
        return weekday(dateOf(day).getDayOfWeek().getValue());
    }

    /** "放学后" / "白天" / "晚上" for a slot. */
    public static String period(Rules rules, int slot) {
        if (Rules.isEvening(slot)) {
            return "晚上";
        }
        Rules.Day day = rules == null ? null : rules.day(Rules.dayOf(slot));
        if (day != null && (day.type == Rules.DayType.SCHOOL || day.type == Rules.DayType.EXAM)) {
            return "放学后";
        }
        return "白天";
    }

    /** "10月22日 晚上". */
    public static String slot(Rules rules, int slot) {
        return date(Rules.dayOf(slot)) + " " + period(rules, slot);
    }

    /** "RANK 7" / "RANK MAX" / "未解锁". */
    public static String rank(int rank) {
        if (rank <= 0) {
            return "未解锁";
        }
        return "RANK " + rankValue(rank);
    }

    /** "7" / "MAX" (no prefix) for compact displays. */
    public static String rankValue(int rank) {
        if (rank >= 10) {
            return "MAX";
        }
        return String.valueOf(Math.max(0, rank));
    }

    /** "RANK 6 → 7" / "RANK 9 → MAX". */
    public static String rankStep(int from, int to) {
        return "RANK " + rankValue(from) + " → " + rankValue(to);
    }

    /** "Lv.3"; the top level (6) is written "MAX", as the game and the guide do ("勇气MAX"). */
    public static String lv(int level) {
        return level >= 6 ? "MAX" : "Lv." + level;
    }

    /** "1" … "5", "MAX" — compact stat level labels for segmented pickers. */
    public static String lvValue(int level) {
        return level >= 6 ? "MAX" : String.valueOf(Math.max(1, level));
    }

    /** "5月12日–1月29日" for a day range ("5月12日" when both ends are the same day). */
    public static String dateRange(int fromDay, int toDay) {
        return fromDay == toDay ? date(fromDay) : date(fromDay) + "–" + date(toDay);
    }

    /** Roman numeral for an arcana number ("0" for the Fool). */
    public static String roman(int arcanaNo) {
        if (arcanaNo < 0 || arcanaNo >= ROMAN.length) {
            return String.valueOf(arcanaNo);
        }
        return ROMAN[arcanaNo];
    }

    /** "5月2日 晚上" for the slot that was last recorded (the one before {@code nextSlot}). */
    public static String lastRecorded(Rules rules, int nextSlot) {
        return slot(rules, Math.max(0, nextSlot - 1));
    }

    /** "3天前" style relative age for real-world timestamps. */
    public static String age(long nowMillis, long thenMillis) {
        long minutes = Math.max(0, (nowMillis - thenMillis) / 60_000L);
        if (minutes < 60) {
            return minutes <= 1 ? "刚刚" : minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        return (hours / 24) + " 天前";
    }
}
