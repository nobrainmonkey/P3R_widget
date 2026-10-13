package app.p3r.dayplanner.v3.ui;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Canned, realistic test data for the UI: a full 2009-04-07 … 2010-01-31 calendar with story
 * locks, the 22 Social Links with their Chinese labels (from rules/p3r/social_goal_identity_labels
 * .json, using the official zh-Hans spellings 贝培 / Ｙ子), Tartarus blocks, rescues, activities,
 * Link Episode tracks, the dating-site note, a mid-game observed state (Oct 22) and a plan that
 * covers every step kind (with 人格面具 reminders, how-to text and "顺便" side steps).
 */
final class Fixtures {
    static final int OCT_22 = 204;
    static final int SLOT_NOW = OCT_22 * 2;
    static final long STATE_REV = 12;
    static final long PROFILE_REV = 3;
    static final String PLAN_ID = "plan-mid-1";

    /** {id, arcanaZh, personZh, genderZh, story?, evening?, location}. */
    private static final String[][] LINKS = {
        {"fool", "愚者", "特别课外活动部", "团体·男女", "story", "", "宿舍"},
        {"magician", "魔术师", "友近健二", "男", "", "", "月光馆学园 2楼 教室"},
        {"priestess", "女教皇", "山岸风花", "女", "", "", "月光馆学园 家政教室"},
        {"empress", "女皇", "桐条美鹤", "女", "", "", "月光馆学园 学生会室"},
        {"emperor", "皇帝", "小田桐秀利", "男", "", "", "月光馆学园 学生会室"},
        {"hierophant", "教皇", "北村文吉、光子", "夫妇·男女", "", "", "长鸣神社附近 古书店"},
        {"lovers", "恋爱", "岳羽由加莉", "女", "", "", "月光馆学园 1楼 走廊"},
        {"chariot", "战车", "宫本一志", "男", "", "", "月光馆学园 体育馆"},
        {"justice", "正义", "伏见千寻", "女", "", "", "月光馆学园 学生会室"},
        {"hermit", "隐者", "Ｙ子", "女", "", "eve", "宿舍 · 自己房间（电脑）"},
        {"fortune", "命运", "平贺庆介", "男", "", "", "月光馆学园 美术社"},
        {"strength", "力量", "西胁结子", "女", "", "", "月光馆学园 操场"},
        {"hanged_man", "倒悬者", "大桥舞子", "女", "", "", "长鸣神社"},
        {"death", "死神", "法洛斯", "男", "story", "", "宿舍"},
        {"temperance", "节制", "贝培", "男", "", "", "月光馆学园 家政教室"},
        {"devil", "恶魔", "田中社长", "男", "", "eve", "波罗尼亚商城 2楼"},
        {"tower", "塔", "无达", "男", "", "eve", "波罗尼亚商城 后巷"},
        {"star", "星", "早濑护", "男", "", "", "长鸣神社"},
        {"moon", "月亮", "末光望美", "男", "", "", "严户台商店街 咖啡店"},
        {"sun", "太阳", "神木秋成", "男", "", "", "长鸣神社"},
        {"judgement", "审判", "伙伴们", "团体·男女", "story", "", "宿舍"},
        {"aeon", "永劫", "埃癸斯", "女", "story", "", "月光馆学园 教室"},
    };

    private Fixtures() {}

    static int day(int month, int dom) {
        return Zh.dayOf(LocalDate.of(month >= 4 ? 2009 : 2010, month, dom));
    }

    // ------------------------------------------------------------------ rules

    static Rules rules() {
        Rules r = new Rules();
        r.packVersion = "3.0.0-test";
        r.version = "3.0.0-test|fixture";
        r.firstDay = day(4, 7);
        r.lastDay = day(1, 31);
        r.lockReasonsZh.put("prologue", "序章剧情");
        r.lockReasonsZh.put("full_moon", "满月作战");
        r.lockReasonsZh.put("exam", "期中/期末考试");
        r.lockReasonsZh.put("story", "剧情");
        r.lockReasonsZh.put("trip", "修学旅行");
        r.lockReasonsZh.put("finale", "最终决战");
        r.stats = new Rules.Stat[]{
            stat(0, "academics", "学力", 0, 0, 20, 55, 100, 155, 230),
            stat(1, "charm", "魅力", 0, 0, 15, 30, 45, 70, 100),
            stat(2, "courage", "勇气", 0, 0, 15, 30, 45, 60, 80),
        };
        r.blocks = new Rules.Block[]{
            block(0, "thebel", "台伯勒", "2–22F", day(4, 20), day(6, 12), 22),
            block(1, "arqa", "阿尔卡", "23–43F", day(6, 13), day(7, 31), 43),
            block(2, "yabbashah", "雅巴沙", "44–68F", day(8, 1), day(9, 30), 68),
            block(3, "tziah", "提齐亚", "69–117F", day(10, 1), day(11, 30), 117),
            block(4, "harabah", "哈拉巴", "118–172F", day(12, 1), day(12, 31), 172),
            block(5, "adamah", "亚达玛", "173–263F", day(1, 1), day(1, 31), 263),
        };
        r.days = new Rules.Day[r.lastDay + 1];
        for (int d = r.firstDay; d <= r.lastDay; d++) {
            r.days[d] = dayOf(r, d);
        }
        r.links = new Rules.Link[LINKS.length];
        for (int i = 0; i < LINKS.length; i++) {
            r.links[i] = link(r, i);
        }
        r.activities = new Rules.Activity[]{
            activity(0, "study_room", "在宿舍房间学习", "宿舍 · 自己房间", Rules.ActivityKind.STAT, new int[]{2, 0, 0}),
            activity(1, "job_chagall", "咖啡店「夏卡尔」打工", "波罗尼亚商城 咖啡店", Rules.ActivityKind.JOB, new int[]{0, 1, 1}),
            activity(2, "wakatsu", "定食屋「若津」吃大份套餐", "严户台商店街", Rules.ActivityKind.STAT, new int[]{0, 0, 2}),
            activity(3, "job_flower", "花店打工", "严户台商店街", Rules.ActivityKind.JOB, new int[]{0, 1, 0}),
        };
        r.activities[1].pay = new int[][]{{1, 2500}, {5, 3000}, {15, 3500}};
        r.activities[3].pay = new int[][]{{1, 2000}};
        r.rescues = new Rules.Rescue[]{
            rescue("bunkichi", "北村文吉", day(9, 12), day(10, 3), 5),
            rescue("maiko", "舞子", day(10, 21), day(11, 2), 12),
        };
        r.episodes = new Rules.Episode[]{
            episode(r, 0, "junpei", "伊织顺平", false, 4, day(5, 12), day(1, 29), "道具「顺平的棒球帽」"),
            episode(r, 1, "akihiko", "真田明彦", true, 3, day(6, 2), day(1, 28), null),
        };
        r.datingNote = new Rules.DatingNote();
        r.datingNote.zh = "结缘网站的字条";
        r.datingNote.locationZh = "桐叶购物中心 放纵俱乐部";
        r.datingNote.fromDay = day(11, 11);
        r.datingNote.cost = 3000;
        r.datingNote.gain = 10;
        Rules.Source s1 = new Rules.Source();
        s1.id = "aqiu_gamedata";
        s1.url = "https://github.com/aqiu384/megaten-database";
        s1.kind = "game_data";
        Rules.Source s2 = new Rules.Source();
        s2.id = "atwiki453";
        s2.url = "https://w.atwiki.jp/wiki10_persona3/pages/453.html";
        s2.kind = "wiki";
        Rules.Source s3 = new Rules.Source();
        s3.id = "evidence_note";
        s3.kind = "evidence";
        r.sources.addAll(Arrays.asList(s1, s2, s3));
        return r;
    }

    /** A Link Episode track; episode n can be watched on every n-th open weekday of its window. */
    private static Rules.Episode episode(Rules r, int idx, String id, String person, boolean evening, int count,
                                         int from, int to, String reward) {
        Rules.Episode e = new Rules.Episode();
        e.idx = idx;
        e.id = id;
        e.personZh = person;
        e.titleZh = "羁绊剧情 · " + person;
        e.evening = evening;
        e.count = count;
        e.locationZh = evening ? "宿舍" : "月光馆学园";
        e.rewardZh = reward;
        e.dayMask = new int[r.lastDay + 1];
        for (int d = from; d <= to; d++) {
            if (Zh.dateOf(d).getDayOfWeek() != DayOfWeek.SUNDAY) {
                e.dayMask[d] = (1 << count) - 1;
            }
        }
        return e;
    }

    private static Rules.Stat stat(int idx, String id, String zh, int... thresholds) {
        Rules.Stat s = new Rules.Stat();
        s.idx = idx;
        s.id = id;
        s.zh = zh;
        s.thresholds = thresholds;
        return s;
    }

    private static Rules.Block block(int idx, String id, String zh, String floors, int from, int to, int top) {
        Rules.Block b = new Rules.Block();
        b.idx = idx;
        b.id = id;
        b.zh = zh;
        b.floorsZh = floors;
        b.fromDay = from;
        b.toDay = to;
        b.top = top;
        return b;
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

    private static Rules.Day dayOf(Rules rules, int d) {
        LocalDate date = Zh.dateOf(d);
        Rules.Day day = new Rules.Day();
        day.d = d;
        day.md = String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
        day.wd = date.getDayOfWeek().getValue();
        boolean vacation = date.isAfter(LocalDate.of(2009, 7, 20)) && date.isBefore(LocalDate.of(2009, 9, 1));
        boolean winter = date.isAfter(LocalDate.of(2009, 12, 23)) && date.isBefore(LocalDate.of(2010, 1, 8));
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day.type = Rules.DayType.SUNDAY;
        } else if (vacation || winter) {
            day.type = Rules.DayType.VACATION;
        } else if (isExam(date)) {
            day.type = Rules.DayType.EXAM;
        } else {
            day.type = Rules.DayType.SCHOOL;
        }
        day.moon = moonPhase(d);
        day.fullMoon = day.moon == 4;
        if (d < day(4, 18)) {
            day.dayLock = "prologue";
            day.eveLock = "prologue";
        }
        if (day.fullMoon) {
            day.eveLock = "full_moon";
        }
        if (day.type == Rules.DayType.EXAM) {
            day.eveLock = "exam";
        }
        if (date.equals(LocalDate.of(2009, 11, 4)) || date.equals(LocalDate.of(2010, 1, 1))) {
            day.dayLock = "story";
            day.eveLock = "story";
        }
        if (!date.isBefore(LocalDate.of(2009, 11, 17)) && !date.isAfter(LocalDate.of(2009, 11, 19))) {
            day.dayLock = "trip";
            day.eveLock = "trip";
        }
        if (d == day(1, 31)) {
            day.eveLock = "finale";
        }
        Rules.Block block = null;
        for (Rules.Block b : rules.blocks) {
            if (d >= b.fromDay && d <= b.toDay) {
                block = b;
            }
        }
        day.block = block == null ? -1 : block.idx;
        day.tartarus = block != null && day.eveLock == null && date.getDayOfWeek() != DayOfWeek.SUNDAY;
        return day;
    }

    private static boolean isExam(LocalDate date) {
        LocalDate[][] exams = {
            {LocalDate.of(2009, 5, 19), LocalDate.of(2009, 5, 22)},
            {LocalDate.of(2009, 7, 14), LocalDate.of(2009, 7, 17)},
            {LocalDate.of(2009, 10, 13), LocalDate.of(2009, 10, 16)},
            {LocalDate.of(2009, 12, 14), LocalDate.of(2009, 12, 17)},
        };
        for (LocalDate[] e : exams) {
            if (!date.isBefore(e[0]) && !date.isAfter(e[1])) {
                return true;
            }
        }
        return false;
    }

    /** 0..7 with 4 at the full moons (anchor 5/9). */
    static int moonPhase(int d) {
        double cycle = 29.53;
        double shifted = ((d - day(5, 9)) % cycle + cycle + cycle / 2) % cycle;
        return (int) Math.floor(shifted / cycle * 8) % 8;
    }

    private static Rules.Link link(Rules rules, int i) {
        String[] row = LINKS[i];
        Rules.Link l = new Rules.Link();
        l.idx = i;
        l.id = row[0];
        l.arcanaNo = i == 21 ? 20 : i;
        l.arcanaZh = row[1];
        l.personZh = row[2];
        l.genderZh = row[3];
        l.labelZh = row[1] + " · " + row[2];
        l.kind = "story".equals(row[4]) ? Rules.LinkKind.STORY : Rules.LinkKind.MANUAL;
        l.evening = "eve".equals(row[5]);
        l.locationZh = row[6];
        l.threshold = new int[]{0, 0, 0, 0, 15, 15, 15, 15, 22, 22, 22};
        l.eventGain = new int[]{0, 0, 10, 20, 15, 15, 15, 15, 20, 15, 20};
        l.unlock.earliestDay = rules.firstDay + i * 3;
        for (int d = rules.firstDay; d <= rules.lastDay; d++) {
            int wd = Zh.dateOf(d).getDayOfWeek().getValue();
            if ((wd + i) % 3 == 0) {
                l.avail.set(d);
            }
        }
        if ("devil".equals(l.id)) {
            l.unlock.steps.add(step("devil.lend1", "第一次借钱给田中社长（5,000 日元）"));
            l.unlock.steps.add(step("devil.lend2", "再借给社长 10,000 日元"));
            l.unlock.steps.add(step("devil.meet", "与田中社长建立社群"));
            l.unlock.steps.get(2).establishes = true;
            l.unlock.earliestDay = day(9, 1);
        }
        if ("tower".equals(l.id)) {
            l.unlock.steps.add(step("tower.talk", "在后巷和无达搭话"));
            l.unlock.steps.add(step("tower.drink", "陪他喝一杯（需要勇气 Lv.4）"));
            l.unlock.steps.get(1).establishes = true;
            l.unlock.earliestDay = day(9, 1);
        }
        if ("emperor".equals(l.id)) {
            l.dialogue.put(7, Arrays.asList("我会帮你的", "这样的规矩没有意义"));
        }
        if ("hanged_man".equals(l.id)) {
            l.missing = new Rules.Missing();
            l.missing.fromDay = day(10, 21);
            l.missing.toDay = day(11, 2);
            l.missing.rescueId = "maiko";
        }
        return l;
    }

    private static Rules.Step step(String id, String zh) {
        Rules.Step s = new Rules.Step();
        s.id = id;
        s.zh = zh;
        return s;
    }

    private static Rules.Activity activity(int idx, String id, String zh, String where, Rules.ActivityKind kind, int[] gain) {
        Rules.Activity a = new Rules.Activity();
        a.idx = idx;
        a.id = id;
        a.zh = zh;
        a.locationZh = where;
        a.kind = kind;
        a.eve = true;
        a.gain = gain;
        return a;
    }

    // ------------------------------------------------------------------ state and profile

    /** Mid-game state: 10/22 放学后, 18,500 日元, 学力 Lv.4 魅力 Lv.3 勇气 Lv.5. */
    static SimState observedMid(Rules r) {
        SimState s = new SimState(r.blocks.length, r.activities.length);
        s.slot = SLOT_NOW;
        s.cash = 18_500;
        s.cashExact = true;
        s.statPts = new int[]{100, 30, 60};
        int[] ranks = {7, 2, 5, 3, 6, 6, 4, 8, 4, 3, 2, 5, 2, 6, 4, 0, 0, 1, 3, 7, 0, 0};
        s.rank = ranks.clone();
        s.pts = new int[22];
        s.pts[4] = 12;
        s.unlockStep = new int[22];
        for (int i = 0; i < 22; i++) {
            if (ranks[i] > 0) {
                s.unlockStep[i] = r.links[i].unlock.steps.size();
            }
        }
        s.unlockStep[15] = 1;
        s.lastStepDay[15] = day(10, 10);
        s.rescued = 1L;
        s.towerVisits[3] = 1;
        s.floor = 96;
        s.shifts[1] = 6;
        s.episodes = new int[]{1, 0};
        return s;
    }

    static Profile profileMid() {
        Profile p = new Profile();
        Goals.setTier(p, 4, Profile.Tier.MUST);
        Goals.setTier(p, 6, Profile.Tier.MUST);
        Goals.setTier(p, 19, Profile.Tier.WANT);
        Goals.setTier(p, 2, Profile.Tier.WANT);
        Goals.setTier(p, 15, Profile.Tier.WANT);
        p.towerVisitsPerBlock = 2;
        p.towerCapPerBlock = 4;
        p.personaMode = Profile.PersonaMode.GOALS;
        p.episodeTracks = new String[]{"junpei"};
        return p;
    }

    // ------------------------------------------------------------------ plan

    /** A full-horizon plan from {@code from} with hand-made early steps covering every kind. */
    static Plan planMid(Rules r, SimState obs, int from, String planId) {
        Plan plan = new Plan();
        plan.planId = planId;
        plan.stateRevision = STATE_REV;
        plan.profileRevision = PROFILE_REV;
        plan.rulesVersion = r.version;
        plan.fromSlot = from;
        plan.toSlot = r.lastSlot();
        plan.createdMillis = 1_700_000_000_000L;
        int[] rank = obs.rank.clone();
        int cycle = 0;
        int useful = 0;
        int free = 0;
        int locked = 0;
        for (int slot = from; slot <= plan.toSlot; slot++) {
            Plan.Step step = handMade(r, slot);
            if (step == null) {
                String lock = r.lockOf(slot);
                if (lock != null) {
                    step = lockedStep(r, slot, lock);
                } else {
                    step = generated(r, slot, rank, cycle++);
                }
            }
            if ("social".equals(step.kind) && step.predicted.rankTo > rank[indexOf(r, step.entity)]) {
                rank[indexOf(r, step.entity)] = step.predicted.rankTo;
            }
            if ("locked".equals(step.kind)) {
                locked++;
            } else if ("free".equals(step.kind)) {
                free++;
            } else {
                useful++;
            }
            plan.steps.add(step);
        }
        plan.summary.endRanks = rank;
        Arrays.fill(plan.summary.maxSlot, -1);
        plan.summary.maxSlot[4] = Rules.slot(day(11, 20), false);
        plan.summary.maxSlot[6] = Rules.slot(day(12, 22), false);
        plan.summary.maxSlot[19] = Rules.slot(day(1, 9), false);
        plan.summary.endRanks[4] = 10;
        plan.summary.endRanks[6] = 10;
        plan.summary.endRanks[19] = 10;
        plan.summary.endRanks[2] = 9;
        plan.summary.endRanks[15] = 7;
        plan.summary.usefulActions = useful;
        plan.summary.freeSlots = free;
        plan.summary.lockedSlots = locked;
        plan.summary.lastUsefulSlot = r.lastSlot() - 1;
        plan.summary.endCash = 64_000;
        plan.summary.endStatLv = new int[]{6, 6, 6};
        plan.summary.notesZh.add("舞子要在11月2日前救出，已安排10月24日晚上探索塔尔塔罗斯。");
        plan.summary.notesZh.add("女教皇最后差一级：时间不够，先让给了皇帝和恋爱。");
        return plan;
    }

    static Plan planMid(Rules r, SimState obs) {
        return planMid(r, obs, obs.slot, PLAN_ID);
    }

    static int indexOf(Rules r, String id) {
        return r.link(id).idx;
    }

    /** Hand-made steps for 10/22–10/26 (one of every kind). */
    private static Plan.Step handMade(Rules r, int slot) {
        int d = Rules.dayOf(slot);
        boolean eve = Rules.isEvening(slot);
        if (d == day(10, 22) && !eve) {
            Plan.Step s = social(r, slot, "emperor", 6, 7, true, true);
            s.whyZh = "皇帝只在周一、三、四放学后出现，这周之后要准备考试。";
            s.hintZh = "记得带上「皇帝」阿尔卡那的人格面具";
            s.assumptions.add("persona_bonus");
            s.side.add("unlock:tower:0");
            s.sideZh.add("顺便：听西胁结子说起奇怪的和尚");
            return s;
        }
        if (d == day(10, 22)) {
            return stat(slot, "act:study_room", "在宿舍房间学习", "学力 +2", "宿舍 · 自己房间", new int[]{2, 0, 0}, 0);
        }
        if (d == day(10, 23) && !eve) {
            Plan.Step s = social(r, slot, "lovers", 4, 4, false, false);
            s.estimate = true;
            s.hintZh = "记得带上「恋爱」阿尔卡那的人格面具";
            s.assumptions.add("ordinary_gain");
            s.whyZh = "恋爱下一级还差点数，先见一次面。";
            return s;
        }
        if (d == day(10, 23)) {
            Plan.Step s = stat(slot, "act:job_chagall", "咖啡店「夏卡尔」打工", "+3,000 日元 · 魅力 +1 勇气 +1",
                "波罗尼亚商城 咖啡店", new int[]{0, 1, 1}, 3000);
            s.kind = "job";
            s.entity = "job_chagall";
            s.assumptions.add("job_min_pay");
            s.whyZh = "11月要给恶魔准备 10,000 日元。";
            s.side.add("unlock:devil:0");
            s.sideZh.add("顺便：在商城 2楼借给田中社长 5,000 日元");
            return s;
        }
        if (d == day(10, 24) && !eve) {
            Plan.Step s = social(r, slot, "sun", 7, 8, true, false);
            s.estimate = true;
            s.assumptions.add("ordinary_gain");
            s.assumptions.add("answers_best");
            return s;
        }
        if (d == day(10, 24)) {
            Plan.Step s = new Plan.Step();
            s.slot = slot;
            s.action = "tower:3";
            s.kind = "tower";
            s.titleZh = "探索塔尔塔罗斯";
            s.subtitleZh = "提齐亚（69–117F）· 救出舞子";
            s.locationZh = "月光馆学园（影时间）";
            s.whyZh = "舞子失踪了，要在11月2日前救出，才能继续倒悬者社群。";
            s.assumptions.add("rescue");
            return s;
        }
        if (d == day(10, 25) && !eve) {
            Plan.Step s = new Plan.Step();
            s.slot = slot;
            s.action = "unlock:devil:1";
            s.kind = "unlock";
            s.entity = "devil";
            s.titleZh = "恶魔 · 田中社长";
            s.subtitleZh = "再借给社长 10,000 日元";
            s.locationZh = "波罗尼亚商城 2楼";
            s.whyZh = "解锁恶魔社群前的准备（第 2 步，共 3 步）";
            s.detailZh = "1. 白天去波罗尼亚商城 2楼\n2. 找到穿西装的田中社长，和他说话\n3. 选「借给他」，交出 10,000 日元";
            s.predicted.cashDelta = -10_000;
            return s;
        }
        if (d == day(10, 25)) {
            Plan.Step s = new Plan.Step();
            s.slot = slot;
            s.action = "free";
            s.kind = "free";
            s.titleZh = "自由活动";
            s.subtitleZh = "这个晚上没有更划算的事";
            return s;
        }
        if (d == day(10, 26) && !eve) {
            Plan.Step s = new Plan.Step();
            s.slot = slot;
            s.action = "episode:junpei";
            s.kind = "episode";
            s.entity = "junpei";
            s.titleZh = "羁绊剧情 · 伊织顺平";
            s.subtitleZh = "第 2 话 · 不加社群点数";
            s.locationZh = "月光馆学园 2楼 教室";
            s.whyZh = "你勾选了伊织顺平的羁绊剧情，这天能看第 2 话。";
            s.assumptions.add("link_episode");
            return s;
        }
        if (d == day(10, 26)) {
            Plan.Step s = new Plan.Step();
            s.slot = slot;
            s.action = "note:priestess";
            s.kind = "social";
            s.entity = "priestess";
            s.titleZh = "女教皇 · 山岸风花";
            s.subtitleZh = "结缘网站的字条 · 只加好感";
            s.locationZh = "桐叶购物中心 放纵俱乐部 → 宿舍";
            s.whyZh = "女教皇还差点数，晚上用字条补一点。";
            s.detailZh = "1. 晚上去桐叶购物中心的放纵俱乐部\n2. 买一张结缘网站的字条（3,000 日元）\n"
                + "3. 回宿舍，用公用电脑使用字条，选山岸风花";
            s.predicted.rankFrom = 5;
            s.predicted.rankTo = 5;
            s.predicted.cashDelta = -3000;
            s.estimate = true;
            s.assumptions.add("dating_note");
            return s;
        }
        return null;
    }

    static Plan.Step social(Rules r, int slot, String id, int from, int to, boolean up, boolean certain) {
        Rules.Link link = r.link(id);
        Plan.Step s = new Plan.Step();
        s.slot = slot;
        s.action = "meet:" + id;
        s.kind = "social";
        s.entity = id;
        s.titleZh = link.labelZh;
        s.subtitleZh = up ? Zh.rankStep(from, to) : Zh.rank(from) + " · 见面（预计不升级）";
        s.locationZh = link.locationZh;
        s.predicted.rankFrom = from;
        s.predicted.rankTo = to;
        s.predicted.rankUp = up;
        s.predicted.certain = certain;
        s.estimate = !certain;
        return s;
    }

    private static Plan.Step stat(int slot, String action, String title, String sub, String where, int[] delta, long cash) {
        Plan.Step s = new Plan.Step();
        s.slot = slot;
        s.action = action;
        s.kind = "stat";
        s.entity = action.substring(4);
        s.titleZh = title;
        s.subtitleZh = sub;
        s.locationZh = where;
        s.predicted.statDelta = delta;
        s.predicted.cashDelta = cash;
        return s;
    }

    private static Plan.Step lockedStep(Rules r, int slot, String lock) {
        Plan.Step s = new Plan.Step();
        s.slot = slot;
        s.action = "locked:" + lock;
        s.kind = "locked";
        s.titleZh = r.lockReasonsZh.get(lock);
        s.subtitleZh = "这段时间不能自由行动";
        return s;
    }

    private static final String[] ROTATION = {"emperor", "lovers", "sun", "priestess", "chariot", "strength",
        "hierophant", "temperance", "moon", "fortune"};

    private static Plan.Step generated(Rules r, int slot, int[] rank, int n) {
        boolean eve = Rules.isEvening(slot);
        if (eve) {
            switch (n % 4) {
                case 0:
                    return stat(slot, "act:study_room", "在宿舍房间学习", "学力 +2", "宿舍 · 自己房间", new int[]{2, 0, 0}, 0);
                case 1: {
                    Plan.Step s = stat(slot, "act:job_chagall", "咖啡店「夏卡尔」打工", "+3,000 日元 · 魅力 +1",
                        "波罗尼亚商城 咖啡店", new int[]{0, 1, 0}, 3000);
                    s.kind = "job";
                    return s;
                }
                case 2: {
                    Plan.Step s = new Plan.Step();
                    s.slot = slot;
                    s.action = "tower:" + Math.max(0, blockIdx(r, slot));
                    s.kind = "tower";
                    s.titleZh = "探索塔尔塔罗斯";
                    s.subtitleZh = "继续往上走";
                    return s;
                }
                default:
                    Plan.Step s = social(r, slot, "hermit", rank[9], Math.min(10, rank[9] + 1), true, false);
                    return s;
            }
        }
        String id = ROTATION[n % ROTATION.length];
        int idx = r.link(id).idx;
        int from = rank[idx];
        if (from >= 10) {
            return stat(slot, "act:wakatsu", "定食屋「若津」吃大份套餐", "勇气 +2", "严户台商店街", new int[]{0, 0, 2}, -800);
        }
        boolean up = n % 3 != 1;
        return social(r, slot, id, from, up ? from + 1 : from, up, n % 5 == 0);
    }

    private static int blockIdx(Rules r, int slot) {
        Rules.Block b = r.blockOn(Rules.dayOf(slot));
        return b == null ? 0 : b.idx;
    }

    /** Legal alternatives for 改做别的 at a slot (as the engine would return them). */
    static List<Plan.Step> alternatives(Rules r, int slot) {
        List<Plan.Step> list = new ArrayList<>();
        list.add(social(r, slot, "emperor", 6, 7, true, true));
        list.add(social(r, slot, "strength", 5, 6, true, false));
        list.add(social(r, slot, "moon", 3, 3, false, false));
        list.add(stat(slot, "act:wakatsu", "定食屋「若津」吃大份套餐", "勇气 +2", "严户台商店街", new int[]{0, 0, 2}, -800));
        Plan.Step job = stat(slot, "act:job_flower", "花店打工", "+2,000 日元 · 魅力 +1", "严户台商店街", new int[]{0, 1, 0}, 2000);
        job.kind = "job";
        list.add(job);
        return list;
    }
}
