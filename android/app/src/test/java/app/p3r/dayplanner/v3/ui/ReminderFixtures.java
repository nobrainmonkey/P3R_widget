package app.p3r.dayplanner.v3.ui;

import java.util.Arrays;
import java.util.Comparator;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Synthetic 当日提醒 ({@link Plan#reminders}) worded exactly as spec 3.1.0 §1.2.2 writes each kind, for
 * the app and widget tests while the engine's reminders (WP-C) are built in parallel. Days are the
 * calendar's day ordinals (4月1日 = 0, as {@link Fixtures#day} and the widget's GameCalendar count).
 */
public final class ReminderFixtures {
    /** The day sheet's caption source: the estimate sentence of the exam answers 7/17 and 12/16. */
    public static final String ESTIMATE_DETAIL = "这条答案只有一份攻略写过，游戏里的写法可能略有不同。";

    private ReminderFixtures() {}

    public static Plan.Reminder reminder(String id, String kind, String label, int day, boolean evening, String text,
                                         String shortText, int priority) {
        Plan.Reminder r = new Plan.Reminder();
        r.id = id;
        r.kind = kind;
        r.labelZh = label;
        r.slot = Rules.slot(day, evening);
        r.textZh = text;
        r.shortZh = shortText;
        r.priority = priority;
        return r;
    }

    /** 课堂提问 with the 3DM answer and its position: 7/8 「马」（第 2 项）, 10/22 「朱诺」（第 3 项）. */
    public static Plan.Reminder classAnswer(int day, String answer, int option) {
        String id = "class." + md(day);
        Plan.Reminder r = reminder(id, Plan.Reminder.CLASS, "课堂提问", day, false,
                "课堂提问：选「" + answer + "」（第 " + option + " 项）", "课堂：" + answer + "（第 " + option + " 项）",
                Plan.Reminder.MISSABLE);
        r.answerZh = answer;
        r.option = option;
        r.gainZh = "魅力 +2";
        return r;
    }

    /** 考试: 5/19 「五月病」; {@code estimate} for the single-sourced 7/17 MADRID and 12/16 JUNE. */
    public static Plan.Reminder exam(int day, String answer, boolean estimate) {
        Plan.Reminder r = reminder("exam." + md(day), Plan.Reminder.EXAM, "考试", day, false, "考试：选「" + answer + "」",
                "考试：" + answer, Plan.Reminder.MISSABLE);
        r.answerZh = answer;
        r.estimate = estimate;
        if (estimate) r.detailZh = ESTIMATE_DETAIL;
        return r;
    }

    /** 上课犯困 as the engine writes it: the gain is in the line and in {@code gainZh}. */
    public static Plan.Reminder doze(int day) {
        Plan.Reminder r = reminder("doze." + md(day), Plan.Reminder.DOZE, "上课犯困", day, false, "上课犯困时别闭眼：学力 +2",
                "犯困别闭眼：学力 +2", Plan.Reminder.DEADLINE);
        r.gainZh = "学力 +2";
        return r;
    }

    /** 保健室 as the engine writes it: the gain is in the line and in {@code gainZh}. */
    public static Plan.Reminder nurse(int day) {
        Plan.Reminder r = reminder("nurse." + md(day), Plan.Reminder.NURSE, "保健室", day, false,
                "放学后先去保健室：勇气 +2，不会消耗时间", "保健室：勇气 +2", Plan.Reminder.DEADLINE);
        r.gainZh = "勇气 +2";
        r.detailZh = "前一晚探索了塔尔塔罗斯或打了满月作战，放学后到 1 楼保健室喝药，勇气 +2，不会消耗时间。"
                + "这份加成不在安排的预计里，能力值升级后去「同步现状」更新一下，安排会更准。";
        return r;
    }

    public static Plan.Reminder tv(int day) {
        Plan.Reminder r = reminder("tv." + md(day), Plan.Reminder.TV, "电视购物", day, false,
                "电视购物：玫瑰花束、高级巧克力 ×2，2,980 日元", "电视购物 2,980 日元", Plan.Reminder.NICE);
        r.detailZh = "在自己房间看电视就能买，不会消耗时间；最好出门前先买。花的钱不在安排的预计里，买了以后记得在「同步现状」里更新现金。";
        return r;
    }

    public static Plan.Reminder requestBatch(int day) {
        Plan.Reminder r = reminder("request." + md(day), Plan.Reminder.REQUEST, "委托", day, false, "伊莉莎白有新的委托",
                "伊莉莎白有新委托", Plan.Reminder.NICE);
        r.detailZh = "去天鹅绒房间找伊莉莎白看看新委托；接委托和交东西都不会消耗时间。";
        return r;
    }

    public static Plan.Reminder requestDeadline(int day) {
        Plan.Reminder r = reminder("request." + md(day) + ".deadline", Plan.Reminder.REQUEST, "委托", day, false,
                "伊莉莎白的第 12、13 号委托 6月6日截止", "伊莉莎白的委托 6月6日截止", Plan.Reminder.DEADLINE);
        r.untilDay = day;
        return r;
    }

    /** The planned rescue on the evening of a Tartarus night. */
    public static Plan.Reminder rescueTonight(int day) {
        return reminder("rescue." + md(day) + ".tower", Plan.Reminder.RESCUE, "失踪者", day, true,
                "今晚去塔尔塔罗斯时，顺便救出失踪者（3 人）", "顺便救出失踪者", Plan.Reminder.DEADLINE);
    }

    public static Plan.Reminder fragment(int day) {
        return reminder("fragment." + md(day), Plan.Reminder.FRAGMENT, "黄昏的碎片", day, false,
                "今天城里能捡到黄昏的碎片（14 处）", "能捡黄昏的碎片", Plan.Reminder.NICE);
    }

    public static Plan.Reminder feature(int day) {
        return reminder("feature." + md(day), Plan.Reminder.FEATURE, "新开放", day, false, "真宵堂今天开张了", "真宵堂开张",
                Plan.Reminder.NICE);
    }

    /** Sets {@code plan}'s reminders, sorted as the engine sorts them: slot, priority (highest first), id. */
    public static Plan with(Plan plan, Plan.Reminder... reminders) {
        plan.reminders.clear();
        plan.reminders.addAll(Arrays.asList(reminders));
        plan.reminders.sort(Comparator.<Plan.Reminder>comparingInt(r -> r.slot)
                .thenComparing(r -> -r.priority).thenComparing(r -> r.id));
        return plan;
    }

    /** "07-08" for the id of a day ordinal. */
    static String md(int day) {
        java.time.LocalDate date = java.time.LocalDate.of(2009, 4, 1).plusDays(day);
        return String.format(java.util.Locale.US, "%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
    }
}
