package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Synthetic rule packs for engine tests (the real pack is built by the rules module in parallel).
 *
 * <ul>
 *   <li>{@link #small()} – Apr 21 … May 26, five links (story, manual day, gated, paid evening,
 *       missing-person with a hold), four activities including a job, two Tartarus blocks, locks.</li>
 *   <li>{@link #fullYear()} – Apr 21 … Jan 31 at real scale: 22 links with the thresholds and event
 *       gains of docs/analysis/map_data.md §3, weekday availability, stat gates, exams/full moons,
 *       vacations, 14 activities, 3 jobs, 11 Tartarus blocks and both rescues. An approximation for
 *       benchmarking, not the real pack.</li>
 * </ul>
 * Each call returns fresh objects (the engine caches tables per pack instance).
 */
final class RulesFixtures {
    private RulesFixtures() {}

    static final int ACADEMICS = 0;
    static final int CHARM = 1;
    static final int COURAGE = 2;

    private static final LocalDate EPOCH = LocalDate.of(2009, 4, 1);

    /** Day ordinal of a calendar date (months 1–3 are 2010). */
    static int day(int month, int dom) {
        LocalDate d = LocalDate.of(month >= 4 ? 2009 : 2010, month, dom);
        return (int) (d.toEpochDay() - EPOCH.toEpochDay());
    }

    static int slot(int month, int dom, boolean evening) {
        return Rules.slot(day(month, dom), evening);
    }

    // ------------------------------------------------------------------ small fixture

    static final int S_FOOL = 0;
    static final int S_MAGICIAN = 1;
    static final int S_EMPEROR = 2;
    static final int S_DEVIL = 3;
    static final int S_HIEROPHANT = 4;
    static final int A_STUDY = 0;
    static final int A_ARCADE = 1;
    static final int A_JOB = 2;
    static final int A_MOVIE = 3;

    static Rules small() {
        Rules r = base(20, 55);
        for (int d = 20; d <= 55; d++) {
            Rules.Day day = r.days[d];
            if (d == 28 || d == 33 || d == 34) day.type = Rules.DayType.HOLIDAY;
            if (d >= 47 && d <= 49) {
                day.type = Rules.DayType.EXAM;
                day.dayLock = "exam";
                day.eveLock = "exam";
            }
            day.block = d <= 37 ? 0 : 1;
            day.tartarus = d != 30;
        }
        r.days[21].dayLock = "story";
        r.days[38].eveLock = "full_moon";
        r.days[38].fullMoon = true;
        r.days[55].eveLock = "finale";
        r.lockReasonsZh.put("story", "剧情事件");
        r.lockReasonsZh.put("full_moon", "满月作战");
        r.lockReasonsZh.put("exam", "期中/期末考试");
        r.lockReasonsZh.put("finale", "最终决战");

        Rules.Link fool = link(0, "fool", 0, "愚者", "特别课外活动部", Rules.LinkKind.STORY, false, "月光馆学园");
        fool.storyRankDay = new int[11];
        Arrays.fill(fool.storyRankDay, -1);
        fool.storyRankDay[1] = 17;
        fool.storyRankDay[2] = 38;

        Rules.Link magician = link(1, "magician", 1, "魔术师", "友近健二", Rules.LinkKind.MANUAL, false, "月光馆学园 2楼 教室");
        avail(r, magician, true, 2, 4, 5);
        magician.invite.set(33);
        magician.threshold = new int[]{0, 0, 0, 0, 15, 15, 15, 15, 22, 22, 22};
        magician.eventGain = new int[]{0, 0, 10, 20, 15, 15, 15, 15, 20, 15, 20};
        magician.unlock.earliestDay = 22;
        magician.unlock.steps.add(step("magician.intro", "与友近健二对话", false, Rules.StepDays.LINK_AVAIL, 0, 0, true));

        Rules.Link emperor = link(2, "emperor", 4, "皇帝", "小田桐秀利", Rules.LinkKind.MANUAL, false, "月光馆学园 学生会室");
        avail(r, emperor, true, 1, 3, 5);
        emperor.threshold = new int[]{0, 0, 0, 0, 0, 15, 15, 15, 15, 22, 22};
        emperor.eventGain = new int[]{0, 0, 15, 5, 15, 15, 15, 15, 15, 15, 30};
        emperor.unlock.earliestDay = 26;
        emperor.unlock.statLv.put(ACADEMICS, 2);
        Rules.Step permission = step("emperor.permission", "向老师申请加入学生会", false, Rules.StepDays.WEEKDAYS, 0, 0, false);
        permission.weekdays = new int[]{1, 2, 3, 4, 5};
        emperor.unlock.steps.add(permission);
        emperor.unlock.steps.add(step("emperor.join", "参加学生会", false, Rules.StepDays.LINK_AVAIL, 0, 2, true));

        Rules.Link devil = link(3, "devil", 15, "恶魔", "田中社长", Rules.LinkKind.MANUAL, true, "波罗尼亚商城 2楼");
        avail(r, devil, false, 2, 6);
        devil.unlock.earliestDay = 30;
        devil.unlock.statLv.put(CHARM, 2);
        devil.unlock.steps.add(step("devil.pay1", "投资 20,000 日元", true, Rules.StepDays.ANY_FREE, 20_000, 0, false));
        devil.unlock.steps.add(step("devil.pay2", "再投资 10,000 日元", true, Rules.StepDays.LINK_AVAIL, 10_000, 1, true));

        Rules.Link hierophant = link(4, "hierophant", 5, "教皇", "北村文吉、光子", Rules.LinkKind.MANUAL, false, "长鸣神社旁 书店「本之虫」");
        avail(r, hierophant, false, 2, 3, 4, 5, 6, 7);
        hierophant.threshold = new int[]{0, 0, 0, 0, 20, 20, 20, 20, 20, 20, 30};
        hierophant.eventGain = new int[]{0, 0, 20, 20, 20, 10, 15, 15, 20, 5, 15};
        hierophant.unlock.earliestDay = 24;
        hierophant.unlock.steps.add(step("hierophant.intro", "和书店老板聊天", false, Rules.StepDays.LINK_AVAIL, 0, 0, true));
        hierophant.missing = new Rules.Missing();
        hierophant.missing.fromDay = 40;
        hierophant.missing.toDay = 45;
        hierophant.missing.rescueId = "bunkichi";
        Rules.Hold hold = new Rules.Hold();
        hold.rank = 5;
        hold.untilDay = 50;
        hold.zh = "需要先听到学校里的传闻";
        hierophant.holds.add(hold);
        r.links = new Rules.Link[]{fool, magician, emperor, devil, hierophant};

        Rules.Activity study = activity(0, "study_room", "在宿舍房间学习", Rules.ActivityKind.STAT, false, true, 0, ACADEMICS, 2);
        Rules.Activity arcade = activity(1, "arcade_charm", "游戏中心「青春高校」", Rules.ActivityKind.STAT, false, true, 1500, CHARM, 4);
        arcade.weekdayMask = mask(1, 4);
        Rules.Activity job = activity(2, "job_chagall", "咖啡店「夏卡尔」打工", Rules.ActivityKind.JOB, false, true, 0, CHARM, 1);
        job.gain[COURAGE] = 1;
        job.weekdayMask = mask(1, 2, 3);
        job.pay = new int[][]{{1, 2500}, {5, 3000}};
        Rules.Activity movie = activity(3, "movie_courage", "看电影（勇气）", Rules.ActivityKind.STAT, true, false, 1500, COURAGE, 4);
        movie.weekdayMask = mask(6, 7);
        r.activities = new Rules.Activity[]{study, arcade, job, movie};

        r.blocks = new Rules.Block[]{block(0, "thebel", "世俗之庭 台伯勒", "2–22F", 20, 37), block(1, "arqa", "宽恕之庭 阿尔卡", "23–43F", 38, 55)};
        Rules.Rescue rescue = new Rules.Rescue();
        rescue.id = "bunkichi";
        rescue.zh = "北村文吉";
        rescue.fromDay = 40;
        rescue.toDay = 45;
        rescue.link = 4;
        r.rescues = new Rules.Rescue[]{rescue};
        return r;
    }

    // ------------------------------------------------------------------ side-step fixture

    static final int X_FOOL = 0;
    static final int X_PATRON = 1;
    static final int X_JUSTICE = 2;
    static final int X_HANGED = 3;
    static final int X_DEVIL = 4;
    static final int X_SUN = 5;
    static final int X_MOON = 6;
    static final int X_TOWER = 7;
    static final int X_STUDY = 0;
    static final int X_LIBRARY = 1;
    static final int X_JOB = 2;
    static final int X_ARCADE = 3;
    /** Days on which the Moon rumour can be heard (daytime, fixed days). */
    static final int[] X_MOON_RUMOR = {24, 31, 38, 45};
    /** Days on which the Tower rumour can be heard (daytime, fixed days). */
    static final int[] X_TOWER_RUMOR = {26, 33, 40};

    /**
     * Apr 21 … May 31 with one link per unlock-chain pattern of the real pack (REPORT §0, §4.2),
     * all non-consuming steps marked {@code consumesSlot=false}:
     * <ul>
     *   <li>patron (day, one step) – Justice needs it at RANK 1;</li>
     *   <li>justice (day): talk1 → talk2 (gap 1) → talk3 (gap 1) → open (gap 1, from day 30);</li>
     *   <li>hanged (day): gift ¥510 → open (gap 1);</li>
     *   <li>devil (evening, Tue/Sat): pay ¥20,000 → pay ¥10,000 (gap 1) → open ¥10,000 (gap 1), needs Charm Lv2;</li>
     *   <li>sun (day, Sundays): ask (day) → pen (evening, any day) → open (day, gap 1);</li>
     *   <li>moon (day, every day): rumour on fixed days → open (gap 0, folds into the rumour day);</li>
     *   <li>tower (evening, Thu–Sun): rumour (day, fixed days) → open (evening, gap 0).</li>
     * </ul>
     */
    static Rules sideSteps() {
        Rules r = base(20, 60);
        for (int d = 20; d <= 60; d++) {
            r.days[d].block = 0;
            r.days[d].tartarus = true;
        }
        r.days[39].dayLock = "story";
        r.days[39].eveLock = "full_moon";
        r.lockReasonsZh.put("story", "剧情事件");
        r.lockReasonsZh.put("full_moon", "满月作战");
        r.days[60].eveLock = "finale";
        r.lockReasonsZh.put("finale", "最终决战");

        Rules.Link fool = link(X_FOOL, "fool", 0, "愚者", "特别课外活动部", Rules.LinkKind.STORY, false, "宿舍");
        fool.storyRankDay = new int[11];
        Arrays.fill(fool.storyRankDay, -1);
        fool.storyRankDay[1] = 17;

        Rules.Link patron = link(X_PATRON, "patron", 4, "皇帝", "小田桐秀利", Rules.LinkKind.MANUAL, false, "学生会活动室");
        avail(r, patron, true, 1, 3, 5);
        sideThresholds(patron);
        patron.unlock.earliestDay = 20;
        patron.unlock.steps.add(step("patron.open", "加入学生会", false, Rules.StepDays.LINK_AVAIL, 0, 0, true));

        Rules.Link justice = link(X_JUSTICE, "justice", 8, "正义", "伏见千寻", Rules.LinkKind.MANUAL, false, "学生会活动室门口");
        avail(r, justice, true, 2, 4, 6);
        sideThresholds(justice);
        justice.unlock.earliestDay = 20;
        justice.unlock.linkRank.put(X_PATRON, 1);
        for (int i = 1; i <= 3; i++) {
            Rules.Step talk = step("justice.talk" + i, "和伏见千寻搭话（第 " + i + " 次）", false, Rules.StepDays.LINK_AVAIL, 0, i == 1 ? 0 : 1, false);
            talk.consumesSlot = false;
            justice.unlock.steps.add(talk);
        }
        Rules.Step justiceOpen = step("justice.open", "和伏见千寻一起回家", false, Rules.StepDays.LINK_AVAIL, 0, 1, true);
        justiceOpen.fromDay = 30;
        justice.unlock.steps.add(justiceOpen);

        Rules.Link hanged = link(X_HANGED, "hanged_man", 12, "倒悬者", "舞子", Rules.LinkKind.MANUAL, false, "长鸣神社");
        avail(r, hanged, false, 1, 3, 6);
        sideThresholds(hanged);
        hanged.unlock.earliestDay = 20;
        Rules.Step gift = step("hanged_man.gift", "给神社的小女孩送吃的", false, Rules.StepDays.LINK_AVAIL, 510, 0, false);
        gift.consumesSlot = false;
        hanged.unlock.steps.add(gift);
        hanged.unlock.steps.add(step("hanged_man.open", "改天再去长鸣神社陪她玩", false, Rules.StepDays.LINK_AVAIL, 0, 1, true));

        Rules.Link devil = link(X_DEVIL, "devil", 15, "恶魔", "田中社长", Rules.LinkKind.MANUAL, true, "桐叶购物中心");
        avail(r, devil, false, 2, 6);
        devil.threshold = new int[11];
        devil.eventGain = new int[11];
        devil.unlock.earliestDay = 20;
        devil.unlock.statLv.put(CHARM, 2);
        Rules.Step pay1 = step("devil.pay1", "把 20,000 日元交给穿西装的男人", true, Rules.StepDays.LINK_AVAIL, 20_000, 0, false);
        pay1.consumesSlot = false;
        Rules.Step pay2 = step("devil.pay2", "再交 10,000 日元", true, Rules.StepDays.LINK_AVAIL, 10_000, 1, false);
        pay2.consumesSlot = false;
        devil.unlock.steps.add(pay1);
        devil.unlock.steps.add(pay2);
        devil.unlock.steps.add(step("devil.open", "交出最后的 10,000 日元", true, Rules.StepDays.LINK_AVAIL, 10_000, 1, true));

        Rules.Link sun = link(X_SUN, "sun", 19, "太阳", "神木秋成", Rules.LinkKind.MANUAL, false, "长鸣神社");
        avail(r, sun, false, 7);
        sun.threshold = new int[11];
        sun.eventGain = new int[11];
        sun.unlock.earliestDay = 20;
        Rules.Step ask = step("sun.ask", "和长鸣神社那位消瘦的青年说话", false, Rules.StepDays.LINK_AVAIL, 0, 0, false);
        ask.consumesSlot = false;
        Rules.Step pen = step("sun.pen", "从虎狼丸那里拿到红色钢笔", true, Rules.StepDays.ANY_FREE, 0, 0, false);
        pen.consumesSlot = false;
        sun.unlock.steps.add(ask);
        sun.unlock.steps.add(pen);
        sun.unlock.steps.add(step("sun.open", "把钢笔还给他", false, Rules.StepDays.LINK_AVAIL, 0, 1, true));

        Rules.Link moon = link(X_MOON, "moon", 18, "月亮", "末光望美", Rules.LinkKind.MANUAL, false, "喷泉广场");
        avail(r, moon, false, 1, 2, 3, 4, 5, 6, 7);
        sideThresholds(moon);
        moon.unlock.earliestDay = 20;
        Rules.Step moonRumor = step("moon.rumor", "听友近健二说起「美食大王」", false, Rules.StepDays.FIXED, 0, 0, false);
        moonRumor.consumesSlot = false;
        moonRumor.fixedDays = X_MOON_RUMOR;
        moon.unlock.steps.add(moonRumor);
        moon.unlock.steps.add(step("moon.open", "去喷泉广场找那个胖乎乎的学生", false, Rules.StepDays.LINK_AVAIL, 0, 0, true));

        Rules.Link tower = link(X_TOWER, "tower", 16, "塔", "无达", Rules.LinkKind.MANUAL, true, "放纵俱乐部");
        avail(r, tower, false, 4, 5, 6, 7);
        sideThresholds(tower);
        tower.unlock.earliestDay = 20;
        Rules.Step towerRumor = step("tower.rumor", "听西胁结子说起奇怪的和尚", false, Rules.StepDays.FIXED, 0, 0, false);
        towerRumor.consumesSlot = false;
        towerRumor.fixedDays = X_TOWER_RUMOR;
        tower.unlock.steps.add(towerRumor);
        tower.unlock.steps.add(step("tower.open", "到放纵俱乐部找那位和尚", true, Rules.StepDays.LINK_AVAIL, 0, 0, true));
        r.links = new Rules.Link[]{fool, patron, justice, hanged, devil, sun, moon, tower};

        Rules.Activity study = activity(X_STUDY, "study_room", "在自己房间学习", Rules.ActivityKind.STAT, false, true, 0, ACADEMICS, 2);
        Rules.Activity library = activity(X_LIBRARY, "library", "到学校图书室自习", Rules.ActivityKind.STAT, true, false, 0, ACADEMICS, 2);
        Rules.Activity job = activity(X_JOB, "job_chagall", "在夏卡尔咖啡店打工", Rules.ActivityKind.JOB, false, true, 0, CHARM, 1);
        job.weekdayMask = mask(1, 2, 3);
        job.pay = new int[][]{{1, 2500}};
        Rules.Activity arcade = activity(X_ARCADE, "arcade_youth", "在电玩大巡游玩「青春高校」", Rules.ActivityKind.STAT, false, true, 1500, CHARM, 4);
        r.activities = new Rules.Activity[]{study, library, job, arcade};
        r.blocks = new Rules.Block[]{block(0, "thebel", "世俗之庭 台伯勒", "2–22F", 20, 60)};
        return r;
    }

    private static void sideThresholds(Rules.Link l) {
        l.threshold = new int[]{0, 0, 0, 0, 15, 15, 15, 15, 22, 22, 22};
        l.eventGain = new int[]{0, 0, 10, 20, 15, 15, 15, 15, 20, 15, 20};
    }

    // ------------------------------------------------------------------ full-year fixture

    static Rules fullYear() {
        Rules r = base(20, 305);
        int[] holidays = {day(4, 29), day(5, 4), day(5, 5), day(5, 6), day(7, 20), day(9, 21), day(9, 22), day(9, 23),
                day(10, 12), day(11, 3), day(11, 23), day(12, 23), day(1, 11)};
        for (int d = 20; d <= 305; d++) {
            Rules.Day day = r.days[d];
            if ((d >= day(7, 26) && d <= day(8, 31)) || d >= day(12, 27) && d <= day(1, 7)) day.type = Rules.DayType.VACATION;
            for (int h : holidays) if (h == d) day.type = Rules.DayType.HOLIDAY;
            day.tartarus = true;
        }
        int[][] exams = {{day(5, 18), day(5, 22)}, {day(7, 14), day(7, 17)}, {day(10, 13), day(10, 16)}, {day(12, 14), day(12, 18)}};
        for (int[] e : exams) {
            for (int d = e[0]; d <= e[1]; d++) {
                r.days[d].type = Rules.DayType.EXAM;
                r.days[d].dayLock = "exam";
                r.days[d].eveLock = "exam";
            }
        }
        int[] fullMoons = {day(5, 9), day(6, 8), day(7, 7), day(8, 6), day(9, 5), day(10, 4), day(11, 3), day(12, 3)};
        for (int d : fullMoons) {
            r.days[d].eveLock = "full_moon";
            r.days[d].fullMoon = true;
        }
        lockDays(r, day(11, 17), day(11, 19), "trip", true);
        lockDays(r, day(8, 10), day(8, 14), "summer_class", false);
        lockDays(r, day(9, 18), day(9, 20), "typhoon", true);
        r.days[day(4, 22)].dayLock = "story";
        r.days[day(1, 21)].dayLock = "story";
        r.days[day(1, 31)].eveLock = "finale";
        for (int d : new int[]{day(7, 11), day(7, 12), day(7, 19), day(8, 2), day(8, 15), day(11, 20), day(12, 2), day(12, 5)}) {
            r.days[d].tartarus = false;
        }
        r.lockReasonsZh.put("exam", "期中/期末考试");
        r.lockReasonsZh.put("full_moon", "满月作战");
        r.lockReasonsZh.put("trip", "修学旅行");
        r.lockReasonsZh.put("summer_class", "暑期补习");
        r.lockReasonsZh.put("typhoon", "台风停课");
        r.lockReasonsZh.put("story", "剧情事件");
        r.lockReasonsZh.put("finale", "最终决战");

        int[] blockStart = {day(4, 21), day(5, 10), day(6, 13), day(7, 9), day(8, 8), day(9, 10), day(10, 6), day(11, 6), day(12, 3), day(1, 1), day(1, 31)};
        String[] blockZh = {"世俗之庭 台伯勒", "宽恕之庭 阿尔卡", "安息之庭 雅巴达", "斗争之庭 塔吉尔", "恩宠之庭 哈拉巴", "无慈悲之庭 亚达", "绝望之庭 乌特", "苦痛之庭 亚达姆", "试炼之庭 伊苏法", "终焉之庭 雅达姆", "最终之庭"};
        r.blocks = new Rules.Block[blockStart.length];
        for (int b = 0; b < blockStart.length; b++) {
            int end = b + 1 < blockStart.length ? blockStart[b + 1] - 1 : 305;
            r.blocks[b] = block(b, "block" + b, blockZh[b], null, blockStart[b], end);
            for (int d = blockStart[b]; d <= end; d++) r.days[d].block = b;
        }

        r.links = fullYearLinks(r);
        r.activities = fullYearActivities();
        Rules.Rescue bunkichi = rescue("bunkichi", "北村文吉", day(9, 12), day(10, 3), 5);
        Rules.Rescue maiko = rescue("maiko", "舞子", day(10, 21), day(11, 2), 12);
        r.rescues = new Rules.Rescue[]{bunkichi, maiko};
        r.links[5].missing = missing(day(9, 12), day(10, 3), "bunkichi");
        r.links[12].missing = missing(day(10, 21), day(11, 2), "maiko");
        return r;
    }

    private static Rules.Link[] fullYearLinks(Rules r) {
        List<Rules.Link> links = new ArrayList<>();
        // manual(idx, id, person, arcana, weekdays, evening, schoolOnly, threshold[2..10], eventGain[2..10], earliest)
        Rules.Link fool = link(0, "fool", 0, "愚者", "特别课外活动部", Rules.LinkKind.STORY, false, "宿舍");
        fool.storyRankDay = storyDays(17, day(5, 9), day(6, 8), day(7, 7), day(8, 6), day(9, 5), day(10, 4), day(11, 3), day(12, 3), day(12, 31));
        links.add(fool);
        links.add(manual(r, 1, "magician", "友近健二", "魔术师", new int[]{2, 4, 5}, false, true,
                new int[]{0, 0, 15, 15, 15, 15, 22, 22, 22}, new int[]{10, 20, 15, 15, 15, 15, 20, 15, 20}, day(4, 22)));
        links.add(manual(r, 2, "priestess", "山岸风花", "女教皇", new int[]{1, 5, 6}, false, true,
                new int[]{0, 0, 15, 22, 30, 30, 35, 40, 55}, new int[]{30, 30, 20, 35, 30, 5, 10, 15, 15}, day(6, 19)));
        links.add(manual(r, 3, "empress", "桐条美鹤", "女皇", new int[]{2, 3, 4, 6}, false, true,
                new int[]{0, 0, 22, 22, 22, 22, 22, 22, 22}, new int[]{5, 30, 20, 45, 25, 15, 35, 15, 15}, day(11, 21)));
        links.add(manual(r, 4, "emperor", "小田桐秀利", "皇帝", new int[]{1, 3, 5}, false, true,
                new int[]{0, 0, 0, 15, 15, 15, 15, 22, 22}, new int[]{15, 5, 15, 15, 15, 15, 15, 15, 30}, day(4, 27)));
        links.add(manual(r, 5, "hierophant", "北村文吉、光子", "教皇", new int[]{2, 3, 4, 5, 6, 7}, false, false,
                new int[]{0, 0, 20, 20, 20, 20, 20, 20, 30}, new int[]{20, 20, 20, 10, 15, 15, 20, 5, 15}, day(4, 25)));
        links.add(manual(r, 6, "lovers", "岳羽由加莉", "恋爱", new int[]{1, 3, 4, 6}, false, true,
                new int[]{0, 0, 15, 22, 22, 35, 35, 40, 55}, new int[]{20, 0, 15, 15, 20, 15, 15, 15, 15}, day(7, 25)));
        links.add(manual(r, 7, "chariot", "宫本一志", "战车", new int[]{1, 2, 4, 5}, false, true,
                new int[]{0, 0, 15, 15, 15, 15, 22, 22, 22}, new int[]{5, 10, 15, 20, 15, 20, 5, 15, 20}, day(4, 23)));
        links.add(manual(r, 8, "justice", "伏见千寻", "正义", new int[]{2, 4, 6}, false, true,
                new int[]{0, 0, 22, 22, 22, 30, 30, 30, 40}, new int[]{25, 10, 15, 50, 20, 10, 5, 15, 10}, day(5, 7)));
        links.add(manual(r, 9, "hermit", "Ｙ子", "隐者", new int[]{7}, false, false,
                new int[]{0, 0, 20, 20, 20, 20, 20, 20, 20}, new int[]{20, 20, 15, 10, 15, 20, 20, 10, 25}, day(4, 29)));
        links.add(manual(r, 10, "fortune", "平贺庆介", "命运", new int[]{2, 3, 4}, false, true,
                new int[]{0, 0, 15, 15, 15, 15, 15, 22, 22}, new int[]{0, 15, 20, 15, 15, 15, 15, 15, 30}, day(6, 17)));
        links.add(manual(r, 11, "strength", "西胁结子", "力量", new int[]{3, 6}, false, true,
                new int[]{0, 0, 15, 15, 22, 22, 35, 40, 40}, new int[]{35, 10, 5, 15, 10, 20, 5, 20, 30}, day(4, 24)));
        links.add(manual(r, 12, "hanged_man", "舞子", "倒悬者", new int[]{1, 3, 6}, false, false,
                new int[]{0, 0, 20, 20, 20, 30, 15, 30, 30}, new int[]{15, 15, 15, 20, 20, 0, 35, 15, 30}, day(5, 6)));
        Rules.Link death = link(13, "death", 13, "死神", "法尔洛斯", Rules.LinkKind.STORY, false, "宿舍");
        death.storyRankDay = storyDays(day(6, 12), day(7, 12), day(8, 7), day(9, 12), day(10, 6), day(11, 4), day(11, 4), day(11, 4), day(11, 4), day(11, 4));
        links.add(death);
        links.add(manual(r, 14, "temperance", "贝培", "节制", new int[]{2, 3, 5}, false, true,
                new int[]{0, 0, 15, 15, 15, 15, 10, 22, 22}, new int[]{20, 20, 15, 10, 25, 15, 15, 15, 30}, day(5, 8)));
        links.add(manual(r, 15, "devil", "田中社长", "恶魔", new int[]{2, 6}, true, false,
                new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0}, new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0}, day(5, 16)));
        links.add(manual(r, 16, "tower", "无达", "塔", new int[]{4, 5, 6, 7}, true, false,
                new int[]{0, 0, 25, 25, 25, 30, 30, 40, 40}, new int[]{20, 15, 15, 20, 20, 15, 25, 15, 15}, day(5, 23)));
        links.add(manual(r, 17, "star", "早濑护", "星", new int[]{3, 5, 7}, false, false,
                new int[]{0, 0, 20, 20, 20, 20, 30, 30, 30}, new int[]{20, 10, 15, 10, 15, 25, 20, 20, 15}, day(8, 5)));
        links.add(manual(r, 18, "moon", "末光望美", "月亮", new int[]{1, 2, 3, 4, 5, 6, 7}, false, false,
                new int[]{0, 0, 15, 15, 22, 22, 22, 22, 15}, new int[]{15, 15, 15, 15, 15, 15, 15, 0, 15}, day(4, 28)));
        links.add(manual(r, 19, "sun", "神木秋成", "太阳", new int[]{7}, false, false,
                new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0}, new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0}, day(8, 9)));
        Rules.Link judgement = link(20, "judgement", 20, "审判", "伙伴们", Rules.LinkKind.STORY, false, "塔尔塔罗斯");
        judgement.storyRankDay = storyDays(day(12, 31), day(1, 3), day(1, 6), day(1, 9), day(1, 12), day(1, 15), day(1, 18), day(1, 22), day(1, 26), day(1, 30));
        links.add(judgement);
        links.add(manual(r, 21, "aeon", "埃癸斯", "永劫", new int[]{1, 2, 3, 4, 5, 6}, false, true,
                new int[]{0, 0, 0, 0, 0, 0, 0, 0, 30}, new int[]{15, 5, 15, 15, 15, 0, 15, 15, 0}, day(1, 8)));
        Rules.Link[] out = links.toArray(new Rules.Link[0]);
        fullYearUnlocks(r, out);
        return out;
    }

    private static void fullYearUnlocks(Rules r, Rules.Link[] l) {
        oneStep(l[1], "与友近健二对话");
        oneStep(l[2], "接受山岸风花的邀约");
        l[2].unlock.statLv.put(COURAGE, 6);
        l[2].unlock.linkRank.put(10, 1);
        oneStep(l[3], "接受桐条美鹤的邀约");
        l[3].unlock.statLv.put(ACADEMICS, 6);
        Rules.Step permission = step("emperor.permission", "向老师申请加入学生会", false, Rules.StepDays.WEEKDAYS, 0, 0, false);
        permission.weekdays = new int[]{1, 2, 3, 4, 5};
        l[4].unlock.steps.add(permission);
        l[4].unlock.steps.add(step("emperor.join", "参加学生会", false, Rules.StepDays.LINK_AVAIL, 0, 1, true));
        l[5].unlock.steps.add(step("hierophant.leaf", "买柿子叶", false, Rules.StepDays.ANY_FREE, 0, 0, false));
        l[5].unlock.steps.get(0).consumesSlot = false;
        l[5].unlock.steps.add(step("hierophant.gift", "把柿子叶送给书店老板", false, Rules.StepDays.LINK_AVAIL, 0, 0, true));
        oneStep(l[6], "接受岳羽由加莉的邀约");
        l[6].unlock.statLv.put(CHARM, 6);
        oneStep(l[7], "加入运动社团");
        for (int i = 1; i <= 4; i++) {
            l[8].unlock.steps.add(step("justice.talk" + i, "和伏见千寻说话（第 " + i + " 次）", false, Rules.StepDays.LINK_AVAIL, 0, i == 1 ? 0 : 1, i == 4));
        }
        l[8].unlock.linkRank.put(4, 1);
        oneStep(l[9], "在网游里结识Ｙ子");
        oneStep(l[10], "加入美术社");
        l[11].unlock.steps.add(step("strength.walk1", "陪西胁结子回家", false, Rules.StepDays.LINK_AVAIL, 0, 0, false));
        l[11].unlock.steps.add(step("strength.walk2", "再陪西胁结子回家", false, Rules.StepDays.LINK_AVAIL, 0, 1, true));
        l[11].unlock.linkRank.put(7, 2);
        l[12].unlock.steps.add(step("hanged.food", "请舞子吃东西", false, Rules.StepDays.LINK_AVAIL, 0, 0, false));
        l[12].unlock.steps.add(step("hanged.return", "再去找舞子", false, Rules.StepDays.LINK_AVAIL, 0, 1, true));
        oneStep(l[14], "参观手工艺部");
        l[14].unlock.linkRank.put(5, 3);
        l[14].unlock.statLv.put(ACADEMICS, 2);
        l[15].unlock.steps.add(step("devil.pay1", "投资 20,000 日元", true, Rules.StepDays.LINK_AVAIL, 20_000, 0, false));
        l[15].unlock.steps.add(step("devil.pay2", "再投资 10,000 日元", true, Rules.StepDays.LINK_AVAIL, 10_000, 1, false));
        l[15].unlock.steps.add(step("devil.pay3", "最后投资 10,000 日元", true, Rules.StepDays.LINK_AVAIL, 10_000, 1, true));
        l[15].unlock.linkRank.put(9, 4);
        l[15].unlock.statLv.put(CHARM, 4);
        Rules.Step rumor = step("tower.rumor", "听到关于酒吧的传闻", true, Rules.StepDays.ANY_FREE, 0, 0, false);
        rumor.consumesSlot = false;
        l[16].unlock.steps.add(rumor);
        l[16].unlock.steps.add(step("tower.order", "在酒吧点单", true, Rules.StepDays.LINK_AVAIL, 0, 0, true));
        l[16].unlock.linkRank.put(11, 4);
        l[16].unlock.statLv.put(COURAGE, 4);
        oneStep(l[17], "接受早濑护的邀约");
        l[17].unlock.statLv.put(COURAGE, 4);
        l[18].unlock.steps.add(step("moon.rumor", "打听传闻", false, Rules.StepDays.LINK_AVAIL, 0, 0, false));
        l[18].unlock.steps.add(step("moon.quiz", "回答问题", false, Rules.StepDays.LINK_AVAIL, 0, 1, false));
        l[18].unlock.steps.add(step("moon.food", "请他吃神秘料理", false, Rules.StepDays.LINK_AVAIL, 0, 1, true));
        l[18].unlock.linkRank.put(1, 3);
        l[18].unlock.statLv.put(CHARM, 2);
        oneStep(l[19], "在长鸣神社与神木秋成相识");
        l[19].unlock.linkRank.put(12, 3);
        l[19].unlock.statLv.put(ACADEMICS, 4);
        // aeon: no steps (auto by date)
        Rules.Hold hold = new Rules.Hold();
        hold.rank = 5;
        hold.untilDay = day(6, 1);
        hold.zh = "需要先听到学校里的传闻";
        l[5].holds.add(hold);
        for (Rules.Link link : l) {
            if (link.kind == Rules.LinkKind.MANUAL && link.idx != 21 && link.unlock.steps.isEmpty()) {
                throw new IllegalStateException("missing unlock steps for " + link.id);
            }
        }
        // a few holiday invitations (daytime D cells)
        l[1].invite.set(day(5, 4));
        l[7].invite.set(day(5, 5));
        l[2].invite.set(day(9, 22));
        l[10].invite.set(day(10, 12));
        l[8].invite.set(day(11, 23));
        r.notes.add("synthetic full-year fixture");
    }

    private static void oneStep(Rules.Link link, String zh) {
        link.unlock.steps.add(step(link.id + ".intro", zh, link.evening, Rules.StepDays.LINK_AVAIL, 0, 0, true));
    }

    private static Rules.Link manual(Rules r, int idx, String id, String person, String arcana, int[] weekdays, boolean evening,
                                     boolean schoolOnly, int[] thr2to10, int[] gain2to10, int earliest) {
        Rules.Link l = link(idx, id, idx, arcana, person, Rules.LinkKind.MANUAL, evening, "严户台商店街");
        for (int d = earliest; d <= r.lastDay; d++) {
            Rules.Day day = r.days[d];
            if (day == null || !Tables.contains(weekdays, day.wd)) continue;
            if (schoolOnly && day.type != Rules.DayType.SCHOOL) continue;
            if (id.equals("hermit") && (day.wd == 7 || day.type == Rules.DayType.HOLIDAY)) l.avail.set(d);
            else if (!id.equals("hermit")) l.avail.set(d);
        }
        if (id.equals("hermit")) {
            for (int d = earliest; d <= r.lastDay; d++) if (r.days[d].type == Rules.DayType.HOLIDAY) l.avail.set(d);
        }
        if (id.equals("aeon")) l.avail.clear(day(1, 25));
        l.threshold = new int[11];
        l.eventGain = new int[11];
        System.arraycopy(thr2to10, 0, l.threshold, 2, 9);
        System.arraycopy(gain2to10, 0, l.eventGain, 2, 9);
        l.unlock.earliestDay = earliest;
        return l;
    }

    private static Rules.Activity[] fullYearActivities() {
        List<Rules.Activity> a = new ArrayList<>();
        a.add(activity(0, "study_room", "在宿舍房间学习", Rules.ActivityKind.STAT, false, true, 0, ACADEMICS, 2));
        Rules.Activity coffee = activity(1, "chagall_coffee", "在「夏卡尔」喝咖啡", Rules.ActivityKind.STAT, false, true, 500, CHARM, 2);
        a.add(coffee);
        Rules.Activity answer = activity(2, "arcade_answer", "游戏中心「你就是答案」", Rules.ActivityKind.STAT, false, true, 3000, ACADEMICS, 4);
        answer.weekdayMask = mask(3, 6);
        a.add(answer);
        Rules.Activity school = activity(3, "arcade_school", "游戏中心「青春高校」", Rules.ActivityKind.STAT, false, true, 1500, CHARM, 4);
        school.weekdayMask = mask(1, 4);
        a.add(school);
        Rules.Activity dread = activity(4, "arcade_dread", "游戏中心「死亡之怖」", Rules.ActivityKind.STAT, false, true, 3000, COURAGE, 4);
        dread.weekdayMask = mask(2, 5);
        a.add(dread);
        Rules.Activity wakatsu = activity(5, "wakatsu", "在「若津」吃饭", Rules.ActivityKind.STAT, true, true, 680, ACADEMICS, 3);
        wakatsu.weekdayMask = mask(1, 3, 4, 5, 6, 7);
        a.add(wakatsu);
        Rules.Activity hagakure = activity(6, "hagakure", "在「叶隐」吃拉面", Rules.ActivityKind.STAT, false, true, 900, CHARM, 3);
        hagakure.weekdayMask = mask(1, 2, 3, 4, 5);
        a.add(hagakure);
        Rules.Activity duck = activity(7, "wild_duck", "在「野鸭汉堡」吃汉堡", Rules.ActivityKind.STAT, false, true, 1000, COURAGE, 3);
        duck.weekdayMask = mask(1, 2, 3, 5, 6, 7);
        a.add(duck);
        Rules.Activity karaoke = activity(8, "karaoke", "去卡拉OK唱歌", Rules.ActivityKind.STAT, false, true, 800, COURAGE, 2);
        karaoke.weekdayMask = mask(1, 2, 3, 4, 5, 6);
        a.add(karaoke);
        Rules.Activity movieA = activity(9, "movie_academics", "看电影（学力）", Rules.ActivityKind.STAT, true, false, 1500, ACADEMICS, 4);
        movieA.weekdayMask = mask(3, 6);
        a.add(movieA);
        Rules.Activity movieC = activity(10, "movie_courage", "看电影（勇气）", Rules.ActivityKind.STAT, true, false, 1500, COURAGE, 4);
        movieC.weekdayMask = mask(1, 4);
        a.add(movieC);
        Rules.Activity chagall = activity(11, "job_chagall", "咖啡店「夏卡尔」打工", Rules.ActivityKind.JOB, false, true, 0, CHARM, 1);
        chagall.gain[COURAGE] = 1;
        chagall.weekdayMask = mask(1, 2, 3);
        chagall.pay = new int[][]{{1, 2500}, {5, 3000}, {15, 3500}, {30, 5000}};
        a.add(chagall);
        Rules.Activity blue = activity(12, "job_be_blue_v", "饰品店「Be Blue V」打工", Rules.ActivityKind.JOB, true, false, 0, ACADEMICS, 1);
        blue.gain[CHARM] = 1;
        blue.weekdayMask = mask(1, 2, 3, 4, 5);
        blue.fromDay = day(7, 23);
        blue.pay = new int[][]{{1, 3500}, {3, 4500}, {6, 5500}, {10, 7000}};
        a.add(blue);
        Rules.Activity screen = activity(13, "job_screen_shot", "电影院「Screen Shot」打工", Rules.ActivityKind.JOB, true, false, 0, ACADEMICS, 1);
        screen.gain[COURAGE] = 1;
        screen.weekdayMask = mask(7);
        screen.pay = new int[][]{{1, 5000}, {3, 6000}, {6, 7000}, {10, 9000}};
        a.add(screen);
        return a.toArray(new Rules.Activity[0]);
    }

    // ------------------------------------------------------------------ builders

    private static Rules base(int firstDay, int lastDay) {
        Rules r = new Rules();
        r.packVersion = "test";
        r.version = "test|" + firstDay + "-" + lastDay;
        r.firstDay = firstDay;
        r.lastDay = lastDay;
        r.days = new Rules.Day[lastDay + 1];
        for (int d = firstDay; d <= lastDay; d++) {
            Rules.Day day = new Rules.Day();
            LocalDate date = EPOCH.plusDays(d);
            day.d = d;
            day.md = String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
            day.wd = date.getDayOfWeek().getValue();
            day.type = day.wd == 7 ? Rules.DayType.SUNDAY : Rules.DayType.SCHOOL;
            r.days[d] = day;
        }
        r.stats = new Rules.Stat[]{
                stat(ACADEMICS, "academics", "学力", 0, 0, 20, 55, 100, 155, 230),
                stat(CHARM, "charm", "魅力", 0, 0, 15, 30, 45, 70, 100),
                stat(COURAGE, "courage", "勇气", 0, 0, 15, 30, 45, 60, 80)};
        return r;
    }

    private static void lockDays(Rules r, int from, int to, String reason, boolean evening) {
        for (int d = from; d <= to; d++) {
            r.days[d].dayLock = reason;
            if (evening) r.days[d].eveLock = reason;
        }
    }

    static Rules.Link link(int idx, String id, int no, String arcana, String person, Rules.LinkKind kind, boolean evening, String location) {
        Rules.Link l = new Rules.Link();
        l.idx = idx;
        l.id = id;
        l.arcanaNo = no;
        l.arcanaZh = arcana;
        l.personZh = person;
        l.labelZh = arcana + " · " + person;
        l.kind = kind;
        l.evening = evening;
        l.locationZh = location;
        return l;
    }

    private static void avail(Rules r, Rules.Link link, boolean schoolOnly, int... weekdays) {
        for (int d = r.firstDay; d <= r.lastDay; d++) {
            Rules.Day day = r.days[d];
            if (!Tables.contains(weekdays, day.wd)) continue;
            if (schoolOnly && day.type != Rules.DayType.SCHOOL) continue;
            link.avail.set(d);
        }
    }

    static Rules.Step step(String id, String zh, boolean evening, Rules.StepDays rule, int cost, int gap, boolean establishes) {
        Rules.Step s = new Rules.Step();
        s.id = id;
        s.zh = zh;
        s.evening = evening;
        s.dayRule = rule;
        s.cost = cost;
        s.minGapDays = gap;
        s.establishes = establishes;
        return s;
    }

    static Rules.Activity activity(int idx, String id, String zh, Rules.ActivityKind kind, boolean day, boolean eve, int cost, int stat, int gain) {
        Rules.Activity a = new Rules.Activity();
        a.idx = idx;
        a.id = id;
        a.zh = zh;
        a.kind = kind;
        a.day = day;
        a.eve = eve;
        a.cost = cost;
        a.gain[stat] = gain;
        a.fromDay = 0;
        a.locationZh = "严户台商店街";
        return a;
    }

    static int mask(int... weekdays) {
        int m = 0;
        for (int wd : weekdays) m |= 1 << (wd - 1);
        return m;
    }

    private static Rules.Block block(int idx, String id, String zh, String floors, int from, int to) {
        Rules.Block b = new Rules.Block();
        b.idx = idx;
        b.id = id;
        b.zh = zh;
        b.floorsZh = floors;
        b.fromDay = from;
        b.toDay = to;
        return b;
    }

    private static Rules.Stat stat(int idx, String id, String zh, int... thresholds) {
        Rules.Stat s = new Rules.Stat();
        s.idx = idx;
        s.id = id;
        s.zh = zh;
        s.thresholds = thresholds;
        return s;
    }

    private static Rules.Rescue rescue(String id, String zh, int from, int to, int link) {
        Rules.Rescue r = new Rules.Rescue();
        r.id = id;
        r.zh = zh;
        r.fromDay = from;
        r.toDay = to;
        r.link = link;
        return r;
    }

    private static Rules.Missing missing(int from, int to, String rescueId) {
        Rules.Missing m = new Rules.Missing();
        m.fromDay = from;
        m.toDay = to;
        m.rescueId = rescueId;
        return m;
    }

    private static int[] storyDays(int... days) {
        int[] out = new int[11];
        Arrays.fill(out, -1);
        for (int i = 0; i < days.length && i < 10; i++) out[i + 1] = days[i];
        return out;
    }

    // ------------------------------------------------------------------ dating-site note and Link Episodes

    /** The dating-site note as the evidence describes it: +10 points, 3,000 日元, one evening. */
    static Rules.DatingNote datingNote(int fromDay) {
        Rules.DatingNote n = new Rules.DatingNote();
        n.zh = "结缘网站的字条";
        n.howZh = "晚上到桐叶购物中心的放纵俱乐部，向卖网址的人买结缘网站的字条，回宿舍用公用电脑选一位社群对象";
        n.locationZh = "桐叶购物中心 · 放纵俱乐部";
        n.fromDay = fromDay;
        n.cost = 3_000;
        n.gain = 10;
        n.maxUses = Rules.DatingNote.UNLIMITED;
        n.conf = "estimate";
        return n;
    }

    /** An empty Link Episode track sized for the pack; allow days with {@link #allow}. */
    static Rules.Episode track(Rules r, int idx, String id, String person, boolean evening, int count) {
        Rules.Episode e = new Rules.Episode();
        e.idx = idx;
        e.id = id;
        e.personZh = person;
        e.titleZh = "羁绊剧情 · " + person;
        e.evening = evening;
        e.count = count;
        e.dayMask = new int[r.lastDay + 1];
        e.locationZh = "严户台宿舍";
        e.howZh = (evening ? "晚上" : "白天") + "打开手机，答应" + person + "的邀约";
        e.rewardZh = "看完第 " + count + " 话可以得到特性「" + person + "的心意」";
        e.noteZh = "按顺序看，每话占一个" + (evening ? "晚上" : "白天");
        e.gain = new int[count][3];
        e.partRewardZh = new String[count];
        return e;
    }

    /** Episodes {@code fromEp}…{@code toEp} (1-based) can be watched on those weekdays in [fromDay, toDay]. */
    static void allow(Rules r, Rules.Episode e, int fromEp, int toEp, int fromDay, int toDay, int... weekdays) {
        int bits = 0;
        for (int n = fromEp; n <= toEp; n++) bits |= 1 << (n - 1);
        for (int d = Math.max(r.firstDay, fromDay); d <= Math.min(r.lastDay, toDay); d++) {
            if (weekdays.length == 0 || Tables.contains(weekdays, r.days[d].wd)) e.dayMask[d] |= bits;
        }
    }

    /**
     * The full-year fixture with the dating-site note (on sale from 11/16) and two Link Episode
     * tracks: 伊织顺平 (daytime, five episodes in separate windows, the last ones short) and 真田明彦
     * (evenings, three episodes on Fridays).
     */
    static Rules fullYearWithExtras() {
        Rules r = fullYear();
        r.datingNote = datingNote(day(11, 16));
        Rules.Episode junpei = track(r, 0, "junpei", "伊织顺平", false, 5);
        allow(r, junpei, 1, 1, day(5, 12), day(7, 3), 2, 5);
        allow(r, junpei, 2, 2, day(8, 18), day(8, 28), 2, 5);
        allow(r, junpei, 3, 3, day(11, 7), day(11, 13), 2, 3, 5, 6);
        allow(r, junpei, 4, 4, day(12, 19), day(12, 26), 2, 5, 6);
        allow(r, junpei, 5, 5, day(1, 15), day(1, 29), 2, 5);
        junpei.gain[1][CHARM] = 2;
        junpei.partRewardZh[1] = "魅力 +2";
        Rules.Episode akihiko = track(r, 1, "akihiko", "真田明彦", true, 3);
        allow(r, akihiko, 1, 3, day(5, 29), day(7, 10), 5);
        allow(r, akihiko, 2, 3, day(10, 9), day(11, 2), 5);
        r.episodes = new Rules.Episode[]{junpei, akihiko};
        return r;
    }

    // ------------------------------------------------------------------ states and profiles

    /** A new-game state at {@code slot} sized for the pack. */
    static SimState newGame(Rules r, int slot) {
        SimState s = new SimState(r.blocks.length, r.activities.length);
        s.slot = slot;
        s.cash = 0;
        s.cashExact = true;
        return s;
    }

    /** Default profile: no explicit goals, "其余社群也尽量提升" on. */
    static Profile others() {
        return new Profile();
    }

    static Profile must(int... links) {
        Profile p = new Profile();
        for (int i = 0; i < links.length; i++) {
            p.tier[links[i]] = Profile.Tier.MUST;
            p.order[links[i]] = i;
        }
        return p;
    }
}
