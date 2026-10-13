package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Small synthetic rule pack for store/service tests: the real calendar axis (Apr 7 – Jan 31,
 * day 0 = 2009-04-01), a few locks, two Tartarus blocks, 22 links (three with real names),
 * one training activity, one job, one rescue, one Link Episode track and the dating-site note.
 */
public final class TestRules {
    public static final int MAGICIAN = 1;
    public static final int EMPEROR = 4;
    public static final int HIEROPHANT = 5;
    public static final int STUDY = 0;
    public static final int CAFE = 1;
    /** May 9: evening locked by the full-moon operation. */
    public static final int FULL_MOON_DAY = 38;
    /** May 12 (block 1 "阿尔卡", top 43F). */
    public static final int MAY_12 = 41;
    /** Apr 22 (block 0 "台伯勒", top 22F). */
    public static final int APR_22 = 21;
    /** Index of the only Link Episode track ("junpei"). */
    public static final int JUNPEI = 0;

    private static final String[] IDS = {
        "fool", "magician", "priestess", "empress", "emperor", "hierophant", "lovers", "chariot",
        "justice", "hermit", "fortune", "strength", "hanged", "death", "temperance", "devil",
        "tower", "star", "moon", "sun", "judgement", "aeon"};

    private TestRules() {}

    public static int slot(int day, boolean evening) {
        return Rules.slot(day, evening);
    }

    public static Rules create() {
        Rules r = new Rules();
        r.version = "test-pack|1";
        r.packVersion = "test-pack";
        r.firstDay = 6;
        r.lastDay = 305;
        r.days = new Rules.Day[r.lastDay + 1];
        r.blocks = new Rules.Block[]{block(0, "thebel", "台伯勒", 20, 38, 22), block(1, "arqa", "阿尔卡", 39, 73, 43)};
        LocalDate zero = LocalDate.of(2009, 4, 1);
        for (int d = r.firstDay; d <= r.lastDay; d++) {
            LocalDate date = zero.plusDays(d);
            Rules.Day day = new Rules.Day();
            day.d = d;
            day.md = String.format(Locale.ROOT, "%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
            day.wd = date.getDayOfWeek().getValue();
            day.type = day.wd == 7 ? Rules.DayType.SUNDAY : Rules.DayType.SCHOOL;
            for (Rules.Block b : r.blocks) {
                if (d >= b.fromDay && d <= b.toDay) {
                    day.block = b.idx;
                    day.tartarus = true;
                }
            }
            r.days[d] = day;
        }
        r.days[6].dayLock = "prologue";
        r.days[6].eveLock = "prologue";
        r.days[FULL_MOON_DAY].eveLock = "full_moon";
        r.days[r.lastDay].eveLock = "finale";
        r.lockReasonsZh.put("prologue", "序章剧情");
        r.lockReasonsZh.put("full_moon", "满月作战");
        r.lockReasonsZh.put("finale", "最终决战");

        r.stats = new Rules.Stat[]{stat(0, "academics", "学力"), stat(1, "charm", "魅力"), stat(2, "courage", "勇气")};
        r.links = new Rules.Link[IDS.length];
        for (int i = 0; i < IDS.length; i++) {
            r.links[i] = link(i, IDS[i], "社群" + i, "某人" + i);
        }
        r.links[MAGICIAN] = link(MAGICIAN, "magician", "魔术师", "友近健二");
        Rules.Step talk = new Rules.Step();
        talk.id = "magician.intro";
        talk.zh = "与友近健二对话";
        talk.establishes = true;
        r.links[MAGICIAN].unlock.steps.add(talk);
        r.links[EMPEROR] = link(EMPEROR, "emperor", "皇帝", "小田桐秀利");
        r.links[EMPEROR].locationZh = "月光馆学园 3楼 学生会室";
        r.links[HIEROPHANT] = link(HIEROPHANT, "hierophant", "教皇", "北村文吉、北村光子");

        Rules.Activity study = new Rules.Activity();
        study.idx = STUDY;
        study.id = "study_room";
        study.zh = "在宿舍房间学习";
        study.locationZh = "宿舍 · 自己房间";
        study.eve = true;
        study.gain = new int[]{2, 0, 0};
        Rules.Activity cafe = new Rules.Activity();
        cafe.idx = CAFE;
        cafe.id = "job_chagall";
        cafe.zh = "咖啡店「夏卡尔」打工";
        cafe.kind = Rules.ActivityKind.JOB;
        cafe.eve = true;
        cafe.gain = new int[]{0, 1, 1};
        cafe.pay = new int[][]{{1, 2500}, {5, 3000}};
        r.activities = new Rules.Activity[]{study, cafe};

        Rules.Rescue bunkichi = new Rules.Rescue();
        bunkichi.id = "bunkichi";
        bunkichi.zh = "北村文吉";
        bunkichi.fromDay = 164;
        bunkichi.toDay = 185;
        bunkichi.link = HIEROPHANT;
        r.rescues = new Rules.Rescue[]{bunkichi};

        Rules.Episode junpei = new Rules.Episode();
        junpei.idx = JUNPEI;
        junpei.id = "junpei";
        junpei.personZh = "伊织顺平";
        junpei.titleZh = "羁绊剧情 · 伊织顺平";
        junpei.count = 4;
        junpei.dayMask = new int[r.lastDay + 1];
        java.util.Arrays.fill(junpei.dayMask, 0b1111);
        junpei.locationZh = "月光馆学园";
        r.episodes = new Rules.Episode[]{junpei};

        Rules.DatingNote note = new Rules.DatingNote();
        note.zh = "结缘网站的字条";
        note.howZh = "在放纵俱乐部买结缘网站的字条，回宿舍用公用电脑给对方加好感";
        note.locationZh = "桐叶购物中心 · 放纵俱乐部";
        note.fromDay = 224;
        note.cost = 3_000;
        note.gain = 10;
        r.datingNote = note;
        return r;
    }

    /** A plausible observed state at {@code slot}: ¥10,000 exact, Emperor RANK 6. */
    public static SimState state(Rules rules, int slot) {
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = slot;
        s.cash = 10_000;
        s.cashExact = true;
        s.rank[EMPEROR] = 6;
        s.rank[0] = 1;
        s.statPts = new int[]{20, 15, 15};
        return s;
    }

    private static Rules.Block block(int idx, String id, String zh, int from, int to, int top) {
        Rules.Block b = new Rules.Block();
        b.idx = idx;
        b.id = id;
        b.zh = zh;
        b.fromDay = from;
        b.toDay = to;
        b.top = top;
        return b;
    }

    private static Rules.Stat stat(int idx, String id, String zh) {
        Rules.Stat s = new Rules.Stat();
        s.idx = idx;
        s.id = id;
        s.zh = zh;
        s.thresholds = new int[]{0, 0, 20, 55, 100, 155, 230};
        return s;
    }

    private static Rules.Link link(int idx, String id, String arcanaZh, String personZh) {
        Rules.Link l = new Rules.Link();
        l.idx = idx;
        l.id = id;
        l.arcanaNo = idx;
        l.arcanaZh = arcanaZh;
        l.personZh = personZh;
        l.labelZh = arcanaZh + " · " + personZh;
        return l;
    }
}
