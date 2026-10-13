package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * User-facing Simplified Chinese copy for plan steps and the plan summary, following
 * docs/GLOSSARY_zh.md: 社群 / RANK / 升到 MAX, 解锁 (never 开启), 见面, 安排, 学力/魅力/勇气 Lv.1–Lv.5
 * then MAX, 探索塔尔塔罗斯 / 区块, 自由活动, 结缘网站的字条, 羁绊剧情, amounts as "2,500 日元".
 * Never shows ids or English. Sentences about where someone is use the person's name (an arcana
 * name is only used as a label, or as "X社群" for links of several people).
 */
final class Labels {
    private static final String[] WEEKDAYS = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩";
    /** Where Tartarus is entered: the school in the Dark Hour (same wording as the alternatives list). */
    static final String TOWER_PLACE = "影时间 · 月光馆学园";
    static final String TOWER_TITLE = "探索塔尔塔罗斯";
    static final String FREE_TITLE = "自由活动";
    static final String NOTE_NAME = "结缘网站的字条";
    static final String EPISODE_NAME = "羁绊剧情";
    /** The result-sheet answer for a story hold the player reports (outcome NOT_DONE, reason "hold"). */
    static final String HOLD_ANSWER = "剧情原因暂时升不了";

    private final Model m;
    private final Rules rules;
    private final Heuristics h;
    /**
     * Slot at which each link gets unlocked in the plan being labelled (-1 never), or null when no
     * plan is known (alternatives): money reasons only name links the plan really unlocks later.
     */
    private int[] plannedOpen;

    Labels(Model m, Heuristics h) {
        this.m = m;
        this.rules = m.rules;
        this.h = h;
    }

    /** The plan's unlock slot per link (see {@link #plannedOpen}). */
    void setPlannedOpen(int[] slots) {
        this.plannedOpen = slots;
    }

    // ------------------------------------------------------------------ formatting helpers

    static String yen(long amount) {
        return String.format(Locale.US, "%,d", amount) + " 日元";
    }

    static String rankName(int rank) {
        return rank >= 10 ? "MAX" : String.valueOf(rank);
    }

    /** Visible stat level: Lv.1 … Lv.5, then MAX. */
    static String lvName(int lv) {
        return lv >= 6 ? "MAX" : "Lv." + lv;
    }

    /** "6月24日". */
    String date(int day) {
        Rules.Day d = rules.day(day);
        if (d == null || d.md == null) return "";
        return d.month() + "月" + d.dayOfMonth() + "日";
    }

    String linkLabel(int l) {
        Rules.Link link = rules.links[l];
        if (!blank(link.labelZh)) return link.labelZh;
        if (!blank(link.personZh)) return arcana(l) + " · " + link.personZh;
        return arcana(l);
    }

    String arcana(int l) {
        String a = rules.links[l].arcanaZh;
        return a == null ? "" : a;
    }

    /** The one person behind a link, or null for a group ("北村文吉、光子", "伙伴们") or no name. */
    String person(int l) {
        String p = rules.links[l].personZh;
        if (blank(p) || p.contains("、") || p.contains("和") || p.contains("/") || p.endsWith("们")) return null;
        return p;
    }

    /** The person's name, or "教皇社群" for a link of several people. */
    String who(int l) {
        String p = person(l);
        return p != null ? p : arcana(l) + "社群";
    }

    String statName(int s) {
        String zh = s < rules.stats.length ? rules.stats[s].zh : null;
        return zh == null ? "" : zh;
    }

    String lockTitle(String reason) {
        String zh = reason == null ? null : rules.lockReasonsZh.get(reason);
        if (!blank(zh)) return zh;
        if ("out_of_range".equals(reason)) return "不在安排的日期内";
        return "剧情事件";
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** "A · B", without a space before "·" after a closing bracket: "台伯勒（2–22F）· 本区块第 1 次". */
    static String dot(String left, String right) {
        return left + (left.endsWith("）") ? "· " : " · ") + right;
    }

    /** Lines numbered ①②③ (a single line stays as it is). */
    static String numbered(List<String> lines) {
        if (lines.isEmpty()) return null;
        if (lines.size() == 1) return lines.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(i < CIRCLED.length() ? CIRCLED.charAt(i) + " " : (i + 1) + ". ").append(lines.get(i));
        }
        return sb.toString();
    }

    private static String joinLines(String a, String b) {
        if (blank(a)) return blank(b) ? null : b;
        if (blank(b)) return a;
        return a + "\n" + b;
    }

    /** "记得带上「皇帝」阿尔卡那的人格面具". */
    String personaHint(int l) {
        return "记得带上「" + arcana(l) + "」阿尔卡那的人格面具";
    }

    // ------------------------------------------------------------------ steps

    /** Fill the display fields of {@code step} for {@code code} taken in pre-state {@code st}. */
    void describe(Plan.Step step, SimState st, int code, Kernel.Effect fx) {
        int target = Kernel.target(code);
        switch (Kernel.type(code)) {
            case Kernel.T_MEET:
                describeMeet(step, st, target, fx);
                break;
            case Kernel.T_UNLOCK:
                describeUnlock(step, st, target, Kernel.step(code), fx);
                break;
            case Kernel.T_ACT:
                describeActivity(step, st, target, fx);
                break;
            case Kernel.T_TOWER:
                describeTower(step, st, target, fx);
                break;
            case Kernel.T_NOTE:
                describeNote(step, st, target, fx);
                break;
            case Kernel.T_EPISODE:
                describeEpisode(step, st, target);
                break;
            default:
                step.kind = "free";
                step.titleZh = FREE_TITLE;
                step.subtitleZh = "没有能推进目标的安排";
                step.whyZh = "这个时段想做什么都行";
                break;
        }
    }

    /**
     * Side steps of the slot ("顺便：…") from the effect of the slot's action, and their how-to lines
     * at the end of {@link Plan.Step#detailZh}.
     */
    void describeSide(Plan.Step step, Kernel.Effect fx) {
        List<String> how = new ArrayList<>();
        for (int i = 0; i < fx.sideCount; i++) {
            int l = fx.sideLink[i];
            int k = fx.sideStep[i];
            step.side.add(Action.unlock(l, k).key(rules));
            step.sideZh.add(sideZh(l, k));
            Rules.Step s = rules.links[l].unlock.steps.get(k);
            String line = !blank(s.howZh) ? s.howZh.trim() : sideWhat(l, k);
            how.add("顺便：" + line);
        }
        if (!how.isEmpty()) step.detailZh = joinLines(step.detailZh, String.join("\n", how));
    }

    /** "顺便：把 20,000 日元交给穿西装的男人" for a non-consuming unlock step. */
    String sideZh(int l, int k) {
        return "顺便：" + sideWhat(l, k);
    }

    private String sideWhat(int l, int k) {
        Rules.Step s = rules.links[l].unlock.steps.get(k);
        String what = !blank(s.zh) ? s.zh : "为解锁" + arcana(l) + "社群做准备";
        if (s.cost > 0 && !what.contains("日元")) what += "（" + yen(s.cost) + "）";
        return what;
    }

    void describeLocked(Plan.Step step, String reason) {
        step.kind = "locked";
        step.titleZh = lockTitle(reason);
        Rules.Day d = rules.day(step.slot >> 1);
        String note = d == null || blank(d.noteZh) ? null : d.noteZh.trim();
        if ("reserved".equals(reason) && note != null) {
            step.subtitleZh = note;
        } else {
            step.subtitleZh = "这段时间不能自由行动";
            step.detailZh = note;
        }
    }

    private void describeMeet(Plan.Step step, SimState st, int l, Kernel.Effect fx) {
        Rules.Link link = rules.links[l];
        step.kind = "social";
        step.entity = link.id;
        step.titleZh = linkLabel(l);
        step.locationZh = fx.invite ? "应邀外出" : link.locationZh;
        String invite = fx.invite ? "假日邀约 · " : "";
        if (fx.rankUp) {
            step.subtitleZh = invite + "RANK " + rankName(fx.rankFrom) + " → " + rankName(fx.rankTo);
        } else if (fx.held) {
            step.subtitleZh = invite + "RANK " + rankName(fx.rankFrom) + " · 见面（暂时无法升级）";
        } else {
            step.subtitleZh = invite + "RANK " + rankName(fx.rankFrom) + " · 见面（预计不升级）";
        }
        step.whyZh = meetWhy(st, l, fx);
        step.detailZh = blank(link.noteZh) ? null : link.noteZh.trim();
        if (fx.persona) step.hintZh = personaHint(l);
    }

    private String meetWhy(SimState st, int l, Kernel.Effect fx) {
        if (fx.invite) return "对方发来了假日邀约，赴约也算一次见面";
        if (fx.held) return holdWhy(st, l);
        double pressure = h.pressure(st, l);
        if (pressure >= 0.6 && pressure < Heuristics.INF) return scarceWhy(l, st.slot >> 1);
        String goal = goalLead(l);
        String core;
        if (fx.rankUp) {
            String to = fx.rankTo >= 10 ? "MAX" : "RANK " + fx.rankTo;
            core = fx.certain ? "这次见面一定能升到 " + to : "预计这次见面能升到 " + to;
        } else {
            core = "好感还不够升级，先多见面几次";
        }
        return goal == null ? core : goal + "，" + core;
    }

    /** "西胁结子只在周三、周六放学后出现，剩下的机会不多" / "岳羽由加莉剩下的见面机会不多了". */
    String scarceWhy(int l, int fromDay) {
        String days = weekdaysPhrase(l, fromDay);
        if (days != null) return who(l) + "只在" + days + presenceVerb(l) + "，剩下的机会不多";
        return who(l) + "剩下的见面机会不多了";
    }

    /** "上线" for someone met online (the room's computer), else "出现". */
    private String presenceVerb(int l) {
        String loc = rules.links[l].locationZh;
        return loc != null && loc.contains("电脑") ? "上线" : "出现";
    }

    private String holdWhy(SimState st, int l) {
        int into = st.rank[l] + 1;
        if (st.heldRank[l] == into) return "你记录了" + HOLD_ANSWER + "，先攒好感";
        for (Rules.Hold hold : rules.links[l].holds) {
            if (hold.rank != into) continue;
            String until = hold.untilDay >= 0 ? date(hold.untilDay) + "前" : "";
            String reason = !blank(hold.zh) ? "（" + hold.zh + "）" : "";
            return until + "暂时无法升级" + reason + "，先攒好感";
        }
        return "暂时无法升级，先攒好感";
    }

    private String goalLead(int l) {
        Profile p = m.profile;
        if (!m.goal[l]) return null;
        return p.tier[l] == Profile.Tier.MUST ? "「必须」目标" : "「想要」目标";
    }

    /**
     * "周三、周六放学后" / "周日和节假日白天" / "周二、周六晚上" when the link's ordinary meetings
     * from {@code fromDay} fall on at most three weekdays, else null. Holidays and vacation days do
     * not make a weekday regular: a holiday on another weekday adds "和节假日". Daytime reads
     * "放学后" when every regular day is a school day.
     */
    String weekdaysPhrase(int l, int fromDay) {
        Rules.Link link = rules.links[l];
        boolean[] wd = new boolean[8];
        int count = 0;
        boolean school = true;
        int from = Math.max(0, fromDay);
        for (int d = link.avail.nextSetBit(from); d >= 0 && d <= rules.lastDay; d = link.avail.nextSetBit(d + 1)) {
            Rules.Day day = rules.day(d);
            if (day == null || day.wd < 1 || day.wd > 7) continue;
            if (day.type == Rules.DayType.HOLIDAY || day.type == Rules.DayType.VACATION) continue;
            if (day.type != Rules.DayType.SCHOOL) school = false;
            if (wd[day.wd]) continue;
            wd[day.wd] = true;
            count++;
            if (count > 3) return null;
        }
        boolean holiday = false;
        for (int d = link.avail.nextSetBit(from); d >= 0 && d <= rules.lastDay; d = link.avail.nextSetBit(d + 1)) {
            Rules.Day day = rules.day(d);
            if (day != null && day.type == Rules.DayType.HOLIDAY && day.wd >= 1 && day.wd <= 7 && !wd[day.wd]) holiday = true;
        }
        if (count == 0 && !holiday) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            if (!wd[i]) continue;
            if (sb.length() > 0) sb.append('、');
            sb.append(WEEKDAYS[i]);
        }
        if (holiday) sb.append(sb.length() > 0 ? "和节假日" : "节假日");
        if (link.evening) return sb.append("晚上").toString();
        return sb.append(school && !holiday && count > 0 ? "放学后" : "白天").toString();
    }

    private void describeUnlock(Plan.Step step, SimState st, int l, int last, Kernel.Effect fx) {
        Rules.Link link = rules.links[l];
        List<Rules.Step> steps = link.unlock.steps;
        int from = Math.max(0, Math.min(st.unlockStep[l], last));
        Rules.Step s = steps.get(last);
        long cost = 0;
        for (int j = from; j <= last; j++) cost += Math.max(0, steps.get(j).cost);
        step.kind = "unlock";
        step.entity = link.id;
        step.titleZh = linkLabel(l);
        String what;
        if (last > from) {
            // Folded preparation steps happen in the same slot: say all of them, in order.
            StringBuilder sb = new StringBuilder();
            for (int j = from; j <= last; j++) {
                String zh = steps.get(j).zh;
                if (blank(zh)) continue;
                sb.append(sb.length() == 0 ? "先" : "，再").append(zh.trim());
            }
            what = sb.length() > 0 ? sb.toString() : "解锁社群";
        } else {
            what = !blank(s.zh) ? s.zh.trim() : "解锁社群";
        }
        step.subtitleZh = cost > 0 && !what.contains("日元") ? dot(what, "花费 " + yen(cost)) : what;
        step.locationZh = s.locationZh != null ? s.locationZh : link.locationZh;
        int establish = h.t.establishIdx[l];
        if (from <= establish && establish <= last) {
            step.whyZh = "完成后" + arcana(l) + "社群就解锁了";
        } else {
            step.whyZh = "解锁" + arcana(l) + "社群前的准备（第 " + (last + 1) + " 步，共 " + steps.size() + " 步）";
        }
        List<String> how = new ArrayList<>();
        for (int j = from; j <= last; j++) {
            Rules.Step x = steps.get(j);
            String line = !blank(x.howZh) ? x.howZh.trim() : blank(x.zh) ? null : x.zh.trim();
            if (line != null) how.add(line);
        }
        String note = blank(link.unlock.noteZh) ? null : link.unlock.noteZh.trim();
        step.detailZh = joinLines(numbered(how), note);
        if (fx.persona) step.hintZh = personaHint(l);
    }

    private void describeActivity(Plan.Step step, SimState st, int act, Kernel.Effect fx) {
        Rules.Activity a = rules.activities[act];
        step.entity = a.id;
        step.titleZh = a.zh;
        step.locationZh = a.locationZh;
        String gains = gains(a, fx);
        if (a.kind == Rules.ActivityKind.JOB) {
            step.kind = "job";
            step.subtitleZh = gains.isEmpty() ? "预计 +" + yen(fx.pay) : dot("预计 +" + yen(fx.pay), gains);
            step.whyZh = jobWhy(st, a, gains);
        } else {
            step.kind = "stat";
            step.subtitleZh = dot(gains.isEmpty() ? "提升个人能力值" : gains, a.cost > 0 ? "花费 " + yen(a.cost) : "免费");
            step.whyZh = statWhy(st, a);
        }
        step.detailZh = blank(a.noteZh) ? null : a.noteZh.trim();
    }

    /** "魅力 +2（升到 Lv.3）、勇气 +1"; reaching the top level reads "（升到 MAX）". */
    private String gains(Rules.Activity a, Kernel.Effect fx) {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < Math.min(3, a.gain.length); s++) {
            if (a.gain[s] <= 0) continue;
            if (sb.length() > 0) sb.append('、');
            sb.append(statName(s)).append(" +").append(a.gain[s]);
            if (fx.lvAfter[s] > fx.lvBefore[s]) sb.append("（升到 ").append(lvName(fx.lvAfter[s])).append('）');
        }
        return sb.toString();
    }

    private String statWhy(SimState st, Rules.Activity a) {
        h.prepare(st);
        int bestStat = -1;
        int bestLink = -1;
        for (int s = 0; s < Math.min(3, a.gain.length); s++) {
            if (a.gain[s] <= 0) continue;
            int l = h.dominantGateLink(st, s);
            if (l >= 0 && (bestLink < 0 || h.unlockValue[l] > h.unlockValue[bestLink])) {
                bestLink = l;
                bestStat = s;
            }
        }
        if (bestLink >= 0) {
            return "解锁" + arcana(bestLink) + "社群需要" + statName(bestStat) + "达到 " + lvName(requiredLv(bestLink, bestStat));
        }
        int ci = h.t.charmIdx;
        if (ci >= 0 && ci < a.gain.length && a.gain[ci] > 0 && m.charmMult > 1 && st.statPts[ci] < h.t.statCap[ci]) {
            return "魅力升到 MAX 后，见面时点数 ×1.51";
        }
        return "这个时段没有更划算的事，提升一下个人能力值";
    }

    private int requiredLv(int l, int stat) {
        int[] rs = m.reqStat[l];
        for (int i = 0; i < rs.length; i++) if (rs[i] == stat) return m.reqStatLv[l][i];
        return 6;
    }

    private String jobWhy(SimState st, Rules.Activity a, String gains) {
        h.prepare(st);
        if (h.shortfall() > 0) {
            int l = moneyGatedLink(st);
            if (l >= 0) return "攒钱：解锁" + arcana(l) + "社群要花钱";
            return "攒钱，之后用来提升个人能力值";
        }
        if (!gains.isEmpty()) return "有收入，还能顺便提升" + statsOnly(a);
        return "这个时段没有更好的安排，先赚点钱";
    }

    private String statsOnly(Rules.Activity a) {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < Math.min(3, a.gain.length); s++) {
            if (a.gain[s] <= 0) continue;
            if (sb.length() > 0) sb.append('、');
            sb.append(statName(s));
        }
        return sb.toString();
    }

    /**
     * A link whose remaining unlock steps cost money and whose other prerequisites are met, or -1.
     * With a known plan only links that plan unlocks later count (a fee nobody pays is no reason to
     * save); without one, links that can still open in time.
     */
    private int moneyGatedLink(SimState st) {
        int day = st.slot >> 1;
        for (int l = 0; l < h.t.nLinks; l++) {
            if (st.rank[l] != 0 || h.unlockValue[l] <= 0 || Kernel.lost(m, st, l, day)) continue;
            if (!Kernel.prereqsMet(m, st, l)) continue;
            if (plannedOpen != null ? plannedOpen[l] < st.slot : h.latestUnlock[l] < st.slot) continue;
            List<Rules.Step> steps = rules.links[l].unlock.steps;
            for (int j = st.unlockStep[l]; j < steps.size(); j++) {
                if (steps.get(j).cost > 0) return l;
            }
        }
        return -1;
    }

    /** "世俗之庭 台伯勒（2–22F）". */
    private String blockName(Rules.Block b) {
        return !blank(b.floorsZh) ? b.zh + "（" + b.floorsZh + "）" : b.zh;
    }

    private void describeTower(Plan.Step step, SimState st, int block, Kernel.Effect fx) {
        Rules.Block b = rules.blocks[block];
        step.kind = "tower";
        step.entity = b.id;
        step.titleZh = TOWER_TITLE;
        step.locationZh = TOWER_PLACE;
        if (fx.rescue >= 0) {
            Rules.Rescue r = rules.rescues[fx.rescue];
            step.subtitleZh = dot(blockName(b), "救出" + r.zh);
            if (r.link >= 0 && r.link < h.t.nLinks) {
                step.whyZh = r.zh + "失踪了，" + date(r.toDay) + "前没救出的话，" + arcana(r.link) + "社群就无法继续";
            } else {
                step.whyZh = r.zh + "失踪了，要在" + date(r.toDay) + "前救出";
            }
            return;
        }
        step.subtitleZh = dot(blockName(b), "本区块第 " + fx.towerVisit + " 次");
        int want = h.towerTarget[block];
        if (fx.towerVisit <= want) {
            int open = h.t.towerRemain[block][st.slot];
            int left = want - st.towerVisits[block];
            if (left * 2 >= open) step.whyZh = "这个区块能去的晚上不多了，要去够 " + want + " 次";
            else step.whyZh = "这个区块要去 " + want + " 次，这个晚上去正合适";
        } else {
            step.whyZh = "这个晚上没有更好的安排，去塔里练练级";
        }
    }

    private void describeNote(Plan.Step step, SimState st, int l, Kernel.Effect fx) {
        Rules.Link link = rules.links[l];
        Rules.DatingNote note = m.note != null ? m.note : rules.datingNote;
        int gain = fx.notePts > 0 ? fx.notePts : note == null ? 0 : Math.max(0, note.gain);
        int cost = note == null ? 0 : Math.max(0, note.cost);
        step.kind = "note";
        step.entity = link.id;
        step.titleZh = "在放纵俱乐部买" + NOTE_NAME + "，给" + who(l) + "加好感";
        step.subtitleZh = arcana(l) + " · 好感 +" + gain + (cost > 0 ? " · " + yen(cost) : "");
        step.locationZh = note != null && !blank(note.locationZh) ? note.locationZh : "桐叶购物中心 · 放纵俱乐部";
        double pressure = h.pressure(st, l);
        if (pressure >= 0.6 && pressure < Heuristics.INF) {
            step.whyZh = who(l) + "剩下的见面机会不多，晚上用字条先攒点好感";
        } else {
            step.whyZh = "晚上没有更要紧的事，用字条给" + who(l) + "攒点好感";
        }
        String how = note != null && !blank(note.howZh) ? note.howZh.trim()
                : "在桐叶购物中心的放纵俱乐部买" + NOTE_NAME + "，当晚回宿舍用公用电脑给" + who(l) + "加好感";
        String remark = note != null && !blank(note.noteZh) ? note.noteZh.trim() : "字条只加好感，不会让社群直接升级";
        step.detailZh = how + "\n" + remark;
    }

    private void describeEpisode(Plan.Step step, SimState st, int e) {
        Rules.Episode ep = rules.episodes[e];
        int n = st.episodes[e] + 1;
        int count = h.t.epCount[e];
        step.kind = "episode";
        step.entity = ep.id;
        step.titleZh = episodeTitle(ep, n);
        step.subtitleZh = "共 " + count + " 话";
        step.locationZh = blank(ep.locationZh) ? null : ep.locationZh;
        int slack = h.t.episodeSlack(e, n - 1, st.slot);
        boolean chain = n - 1 < count && h.t.epChain[e][n - 1];
        if (slack <= 1 && chain && n < count) {
            step.whyZh = "想看完全部 " + count + " 话，这是看第 " + n + " 话的最后机会";
        } else if (slack <= 1) {
            step.whyZh = "这是能看第 " + n + " 话的最后机会";
        } else if (slack <= 3) {
            step.whyZh = "能看第 " + n + " 话的日子不多了";
        } else {
            step.whyZh = "你选了要看" + episodePerson(ep) + "的羁绊剧情，这天能看第 " + n + " 话";
        }
        List<String> lines = new ArrayList<>();
        if (!blank(ep.howZh)) lines.add(ep.howZh.trim());
        String part = ep.rewardOf(n);
        if (!blank(part)) lines.add("这一话的奖励：" + part.trim());
        if (!blank(ep.noteZh)) lines.add(ep.noteZh.trim());
        if (!blank(ep.rewardZh)) lines.add(ep.rewardZh.trim());
        step.detailZh = lines.isEmpty() ? null : String.join("\n", lines);
    }

    /**
     * "羁绊剧情 · 伊织顺平（第 2 话）": the one-line form of docs/GLOSSARY_zh.md, as in 记录历史 and
     * the widget.
     */
    static String episodeTitle(Rules.Episode ep, int n) {
        return EPISODE_NAME + " · " + episodePerson(ep) + "（第 " + n + " 话）";
    }

    /** "伊织顺平" (the title without its prefix, or the id as a last resort). */
    static String episodePerson(Rules.Episode ep) {
        if (!blank(ep.personZh)) return ep.personZh.trim();
        if (!blank(ep.titleZh)) {
            String t = ep.titleZh.trim();
            int dot = t.lastIndexOf('·');
            return dot >= 0 ? t.substring(dot + 1).trim() : t;
        }
        return ep.id == null ? "" : ep.id;
    }

    // ------------------------------------------------------------------ summary notes

    void notes(Plan plan, SimState start, SimState end) {
        List<String> out = plan.summary.notesZh;
        Profile p = m.profile;
        Integer[] goals = goalOrder();
        for (int gi = 0; gi < goals.length; gi++) {
            int l = goals[gi];
            int target = h.target[l];
            int reachedSlot = plan.summary.maxSlot[l];
            String what = target >= 10 ? "MAX" : "RANK " + target;
            if (end.rank[l] >= target) {
                boolean already = start.rank[l] >= target;
                out.add(arcana(l) + "：" + (already ? "已经" + (target >= 10 ? " MAX" : "到 " + what) : "预计 " + date(reachedSlot >> 1) + " 升到 " + what));
                continue;
            }
            int endDay = Math.min(rules.lastDay, end.slot >> 1);
            if (Kernel.lost(m, end, l, endDay)) {
                out.add(arcana(l) + "：没能及时救出" + rescuedPerson(l) + "，社群无法继续");
            } else if (end.rank[l] == 0) {
                String gate = unmetGate(end, l);
                out.add(arcana(l) + "：" + (gate != null ? "来不及满足解锁条件（需要" + gate + "）" : "时间太紧，这次没能安排解锁"));
            } else if (openEndedHold(end, l) != null) {
                out.add(arcana(l) + "：" + openEndedHold(end, l) + "，先安排到 RANK " + rankName(end.rank[l]));
            } else {
                String higher = higherGoal(goals, gi, l);
                out.add(arcana(l) + "：时间不够，预计停在 RANK " + rankName(end.rank[l])
                        + (higher == null ? "" : "，" + higher + "排在前面"));
            }
        }
        if (p.otherLinks) {
            int maxed = 0;
            for (int l = 0; l < h.t.nLinks; l++) if (!m.goal[l] && end.rank[l] >= 10) maxed++;
            out.add(goals.length > 0 ? "其余社群：预计另有 " + maxed + " 个升到 MAX" : "预计有 " + maxed + " 个社群升到 MAX");
        }
        episodeNotes(plan, start, end, out);
        for (int b = 0; b < h.t.nBlocks; b++) {
            int want = Math.min(p.towerVisitsPerBlock, m.towerCap);
            int open = h.t.towerRemain[b][start.slot] + start.towerVisits[b];
            if (want > 0 && open < want && h.t.towerRemain[b][start.slot] > 0) {
                out.add(rules.blocks[b].zh + "：只剩 " + open + " 个晚上能去塔尔塔罗斯，不够想去的 " + want + " 次");
            }
        }
        int notesPlanned = 0;
        for (Plan.Step s : plan.steps) if (s.action != null && s.action.startsWith("note:")) notesPlanned++;
        if (notesPlanned > 0 && m.note != null) {
            out.add("安排了 " + notesPlanned + " 次" + NOTE_NAME + "，每张按好感 +" + m.note.gain + " 估计");
        }
        switch (p.effectivePersonaMode()) {
            case ALL:
                out.add("按见面时都带着同阿尔卡那的人格面具估计，点数 ×1.51");
                break;
            case GOALS:
                if (p.hasAnyGoal()) out.add("按和目标社群见面时带着同阿尔卡那的人格面具估计，点数 ×1.51");
                break;
            default:
                break;
        }
        if (p.hasAnyGoal() && p.goalBuffer) {
            out.add("升级时间是估计值，「必须」「想要」目标已多留一次见面的余量");
        } else {
            out.add("升级时间按每次普通见面约 " + m.ordinaryGain + " 点好感（约 ♪♪）估计");
        }
    }

    /** One line per opted-in Link Episode track: when it is finished, or how far it gets. */
    /** True if episode {@code n} of the track can still be watched on some day from {@code fromDay}. */
    private boolean episodeStillPossible(Rules.Episode ep, int n, int fromDay) {
        for (int d = Math.max(0, fromDay); d < ep.dayMask.length; d++) {
            if (ep.allows(d, n)) return true;
        }
        return false;
    }

    private void episodeNotes(Plan plan, SimState start, SimState end, List<String> out) {
        for (int e = 0; e < h.t.nEps; e++) {
            if (!m.episodeWanted[e]) continue;
            Rules.Episode ep = rules.episodes[e];
            int count = h.t.epCount[e];
            String head = EPISODE_NAME + " · " + episodePerson(ep) + "：";
            int seenStart = Math.min(count, start.episodes[e]);
            int seenEnd = Math.min(count, end.episodes[e]);
            if (seenStart >= count) {
                out.add(head + "已经看完了");
            } else if (seenEnd >= count) {
                String key = Action.episode(e).key(rules);
                int last = -1;
                for (Plan.Step s : plan.steps) if (key.equals(s.action)) last = s.slot;
                out.add(head + (last >= 0 ? "预计 " + date(last >> 1) + " 看完全部 " + count + " 话" : "预计能看完全部 " + count + " 话"));
            } else if (seenEnd > seenStart) {
                out.add(head + "预计能看到第 " + seenEnd + " 话（共 " + count + " 话），后面几话的日子排不开");
            } else if (!episodeStillPossible(ep, seenStart + 1, start.slot >> 1)) {
                out.add(head + "第 " + (seenStart + 1) + " 话能看的日子已经过了；如果已经看过，请在「修改进度」里填上已看几话");
            } else {
                out.add(head + "能看的日子排不开，这次没能安排");
            }
        }
    }

    /** The missing person whose rescue keeps the link going ("北村文吉"), or "失踪者". */
    private String rescuedPerson(int l) {
        int ri = h.t.missRescue[l];
        if (ri >= 0 && ri < rules.rescues.length && !blank(rules.rescues[ri].zh)) return rules.rescues[ri].zh;
        return "失踪者";
    }

    /**
     * "RANK 5 暂时无法升级（需要先听到学校里的传闻）" when a hold without an end date (rule or
     * player-reported) blocks the next rank, else null.
     */
    private String openEndedHold(SimState end, int l) {
        int into = end.rank[l] + 1;
        if (into > 10) return null;
        String head = "RANK " + rankName(into) + " 暂时无法升级";
        if (end.heldRank[l] == into) return head + "（你记录了" + HOLD_ANSWER + "）";
        if (h.t.holdUntil[l][into] != Tables.HOLD_FOREVER) return null;
        for (Rules.Hold hold : rules.links[l].holds) {
            if (hold.rank == into && !blank(hold.zh)) return head + "（" + hold.zh + "）";
        }
        return head + "（要等剧情推进）";
    }

    private Integer[] goalOrder() {
        Profile p = m.profile;
        int n = h.t.nLinks;
        int count = 0;
        for (int l = 0; l < n; l++) if (m.goal[l]) count++;
        Integer[] out = new Integer[count];
        for (int l = 0, i = 0; l < n; l++) if (m.goal[l]) out[i++] = l;
        java.util.Arrays.sort(out, (a, b) -> {
            int ta = p.tier[a] == Profile.Tier.MUST ? 0 : 1;
            int tb = p.tier[b] == Profile.Tier.MUST ? 0 : 1;
            if (ta != tb) return Integer.compare(ta, tb);
            if (p.order[a] != p.order[b]) return Integer.compare(p.order[a], p.order[b]);
            return Integer.compare(a, b);
        });
        return out;
    }

    private String higherGoal(Integer[] goals, int index, int l) {
        boolean eve = rules.links[l].evening;
        for (int i = index - 1; i >= 0; i--) {
            if (rules.links[goals[i]].evening == eve) return arcana(goals[i]);
        }
        return null;
    }

    /** "学力 Lv.3" / "魅力 MAX" / "皇帝 RANK 1" for the first unmet unlock condition, or null. */
    private String unmetGate(SimState end, int l) {
        int[] rs = m.reqStat[l];
        for (int i = 0; i < rs.length; i++) {
            int s = rs[i];
            if (s < h.t.nStats && Kernel.statLv(m, end, s) < m.reqStatLv[l][i]) {
                return statName(s) + " " + lvName(m.reqStatLv[l][i]);
            }
        }
        int[] rl = m.reqLink[l];
        for (int i = 0; i < rl.length; i++) {
            if (rl[i] < h.t.nLinks && end.rank[rl[i]] < m.reqLinkRank[l][i]) {
                return arcana(rl[i]) + " RANK " + rankName(m.reqLinkRank[l][i]);
            }
        }
        return null;
    }
}
