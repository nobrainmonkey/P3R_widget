package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.model.Rules;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Small Simplified Chinese formatting helpers shared by history lines and alternative labels.
 * Dates come from the game calendar (day 0 = 2009-04-01), never from the device date.
 */
final class ZhText {
    private static final LocalDate DAY_ZERO = LocalDate.of(2009, 4, 1);

    private ZhText() {}

    /** "5月12日 晚上", "4月21日 放学后", "4月29日 白天". */
    static String slot(Rules rules, int slot) {
        return monthDay(rules, Rules.dayOf(slot)) + " " + period(rules, slot);
    }

    /** "5月12日". */
    static String monthDay(Rules rules, int day) {
        Rules.Day d = rules == null ? null : rules.day(day);
        int month;
        int dayOfMonth;
        if (d != null && d.md != null && d.md.length() == 5) {
            month = d.month();
            dayOfMonth = d.dayOfMonth();
        } else {
            LocalDate date = DAY_ZERO.plusDays(Math.max(0, day));
            month = date.getMonthValue();
            dayOfMonth = date.getDayOfMonth();
        }
        return month + "月" + dayOfMonth + "日";
    }

    /** "晚上" for evenings, "放学后" for school-day daytime, otherwise "白天" (DESIGN §3). */
    static String period(Rules rules, int slot) {
        if (Rules.isEvening(slot)) {
            return "晚上";
        }
        Rules.Day d = rules == null ? null : rules.day(Rules.dayOf(slot));
        boolean school = d != null && (d.type == Rules.DayType.SCHOOL || d.type == Rules.DayType.EXAM);
        return school ? "放学后" : "白天";
    }

    /** "14,750 日元". */
    static String yen(long amount) {
        return String.format(Locale.US, "%,d 日元", amount);
    }

    /** Rank as shown in compact UI: 0 → "未建立", 10 → "MAX", otherwise the number. */
    static String rank(int rank) {
        if (rank <= 0) {
            return "未建立";
        }
        return rank >= 10 ? "MAX" : String.valueOf(rank);
    }

    /** "学力 +2" style gain, or null for zero. */
    static String statGain(Rules rules, int stat, int delta) {
        if (delta == 0) {
            return null;
        }
        return statName(rules, stat) + (delta > 0 ? " +" : " ") + delta;
    }

    /** "学力" / "魅力" / "勇气". */
    static String statName(Rules rules, int stat) {
        if (rules != null && stat >= 0 && stat < rules.stats.length && rules.stats[stat].zh != null) {
            return rules.stats[stat].zh;
        }
        switch (stat) {
            case 0:
                return "学力";
            case 1:
                return "魅力";
            default:
                return "勇气";
        }
    }

    /**
     * Reason id of a skipped action as a short phrase for parentheses, or null when the id has no
     * phrase ("unwilling" and "hold" are worded by the caller, unknown ids are left out).
     */
    static String reason(String reasonId) {
        if (reasonId == null) {
            return null;
        }
        switch (reasonId) {
            case "story":
                return "剧情占用了";
            case "absent":
                return "对方不在";
            case "closed":
                return "地点没开";
            case "money":
                return "钱不够";
            default:
                return null;
        }
    }

    /** Joins non-null parts with " · ". */
    static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(p);
        }
        return out.toString();
    }
}
