package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.List;
import java.util.Locale;

/**
 * User-facing Simplified Chinese copy for plan steps and the plan summary. Uses the official
 * ATLUS P3R zh-Hans terms (社群, RANK, 学力/魅力/勇气 Lv.N, 塔尔塔罗斯, 打工 …) and never shows ids.
 */
final class Labels {
    private static final String[] WEEKDAYS = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final Model m;
    private final Rules rules;
    private final Heuristics h;

    Labels(Model m, Heuristics h) {
        this.m = m;
        this.rules = m.rules;
        this.h = h;
    }

    // ------------------------------------------------------------------ formatting helpers

    static String yen(long amount) {
        return String.format(Locale.US, "%,d", amount) + " 日元";
    }

    static String rankName(int rank) {
        return rank >= 10 ? "MAX" : String.valueOf(rank);
    }

    String date(int day) {
        Rules.Day d = rules.day(day);
        if (d == null || d.md == null) return "";
        return d.month() + "月" + d.dayOfMonth() + "日";
    }

    String linkLabel(int l) {
        Rules.Link link = rules.links[l];
        if (link.labelZh != null && !link.labelZh.isEmpty()) return link.labelZh;
        if (link.personZh != null && !link.personZh.isEmpty()) return link.arcanaZh + " · " + link.personZh;
        return link.arcanaZh;
    }

    String arcana(int l) {
        String a = rules.links[l].arcanaZh;
        return a == null ? "" : a;
    }

    String statName(int s) {
        String zh = s < rules.stats.length ? rules.stats[s].zh : null;
        return zh == null ? "" : zh;
    }

    String lockTitle(String reason) {
        String zh = reason == null ? null : rules.lockReasonsZh.get(reason);
        if (zh != null && !zh.isEmpty()) return zh;
        if ("out_of_range".equals(reason)) return "不在规划日历内";
        return "剧情时段";
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
                describeUnlock(step, st, target, Kernel.step(code));
                break;
            case Kernel.T_ACT:
                describeActivity(step, st, target, fx);
                break;
            case Kernel.T_TOWER:
                describeTower(step, st, target, fx);
                break;
            default:
                step.kind = "free";
                step.titleZh = "自由时间";
                step.subtitleZh = "没有能推进目标的安排";
                step.whyZh = "这个时段没有可做的事，可以自由安排";
                break;
        }
    }

    void describeLocked(Plan.Step step, String reason) {
        step.kind = "locked";
        step.titleZh = lockTitle(reason);
        step.subtitleZh = "剧情安排，不能自由行动";
    }

    private void describeMeet(Plan.Step step, SimState st, int l, Kernel.Effect fx) {
        Rules.Link link = rules.links[l];
        step.kind = "social";
        step.entity = link.id;
        step.titleZh = linkLabel(l);
        step.locationZh = fx.invite ? "应邀外出" : link.locationZh;
        if (fx.rankUp) {
            step.subtitleZh = (fx.invite ? "假日邀约 · " : "") + "RANK " + rankName(fx.rankFrom) + " → " + rankName(fx.rankTo);
        } else if (fx.held) {
            step.subtitleZh = "加深关系（暂时无法升级）";
        } else {
            step.subtitleZh = (fx.invite ? "假日邀约 · " : "") + "加深关系（未升级）";
        }
        step.whyZh = meetWhy(st, l, fx);
    }

    private String meetWhy(SimState st, int l, Kernel.Effect fx) {
        if (fx.invite) return "对方发来了假日邀约，赴约也算一次见面";
        if (fx.held) return "游戏提示暂时无法升级，先积累好感";
        double pressure = h.pressure(st, l);
        if (pressure >= 0.6 && pressure < Heuristics.INF) {
            String days = weekdaysPhrase(l, st.slot >> 1);
            if (days != null) return arcana(l) + "只在" + days + "出现，剩余机会不多";
            return arcana(l) + "剩下的见面机会不多了";
        }
        String goal = goalLead(l);
        String core;
        if (fx.rankUp) {
            core = fx.rankTo >= 10 ? "这次见面就能练满" : fx.certain ? "这次见面一定能升级" : "预计这次见面能升级";
        } else {
            core = "好感还差一点，先多见面";
        }
        return goal == null ? core : goal + "，" + core;
    }

    private String goalLead(int l) {
        Profile p = m.profile;
        if (!m.goal[l]) return null;
        return p.tier[l] == Profile.Tier.MUST ? "「必须」目标" : "「想要」目标";
    }

    /** "周二、周六晚上" when the link meets on at most three weekdays from now on, else null. */
    String weekdaysPhrase(int l, int fromDay) {
        Rules.Link link = rules.links[l];
        boolean[] wd = new boolean[8];
        int count = 0;
        for (int d = link.avail.nextSetBit(Math.max(0, fromDay)); d >= 0 && d <= rules.lastDay; d = link.avail.nextSetBit(d + 1)) {
            Rules.Day day = rules.day(d);
            if (day == null || day.wd < 1 || day.wd > 7 || wd[day.wd]) continue;
            wd[day.wd] = true;
            count++;
            if (count > 3) return null;
        }
        if (count == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            if (!wd[i]) continue;
            if (sb.length() > 0) sb.append('、');
            sb.append(WEEKDAYS[i]);
        }
        return sb.append(link.evening ? "晚上" : "白天").toString();
    }

    private void describeUnlock(Plan.Step step, SimState st, int l, int last) {
        Rules.Link link = rules.links[l];
        List<Rules.Step> steps = link.unlock.steps;
        int from = Math.max(0, Math.min(st.unlockStep[l], last));
        Rules.Step s = steps.get(last);
        long cost = 0;
        for (int j = from; j <= last; j++) cost += Math.max(0, steps.get(j).cost);
        step.kind = "unlock";
        step.entity = link.id;
        step.titleZh = linkLabel(l);
        String what = s.zh != null && !s.zh.isEmpty() ? s.zh : "开启社群";
        step.subtitleZh = cost > 0 ? what + " · 花费 " + yen(cost) : what;
        step.locationZh = s.locationZh != null ? s.locationZh : link.locationZh;
        int establish = h.t.establishIdx[l];
        if (from <= establish && establish <= last) {
            step.whyZh = "完成后" + arcana(l) + "社群就正式开始了";
        } else {
            int total = h.groupsLeft(l, 0);
            int done = total - h.groupsLeft(l, from);
            step.whyZh = "开启" + arcana(l) + "社群前的准备（第 " + (done + 1) + " 步，共 " + total + " 步）";
        }
    }

    private void describeActivity(Plan.Step step, SimState st, int act, Kernel.Effect fx) {
        Rules.Activity a = rules.activities[act];
        step.entity = a.id;
        step.titleZh = a.zh;
        step.locationZh = a.locationZh;
        String gains = gains(a, fx);
        if (a.kind == Rules.ActivityKind.JOB) {
            step.kind = "job";
            step.subtitleZh = "打工 · 预计 +" + yen(fx.pay);
            step.whyZh = jobWhy(st, a, gains);
        } else {
            step.kind = "stat";
            step.subtitleZh = (gains.isEmpty() ? "提升个人能力值" : gains) + " · " + (a.cost > 0 ? yen(a.cost) : "免费");
            step.whyZh = statWhy(st, a);
        }
    }

    private String gains(Rules.Activity a, Kernel.Effect fx) {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < Math.min(3, a.gain.length); s++) {
            if (a.gain[s] <= 0) continue;
            if (sb.length() > 0) sb.append('、');
            sb.append(statName(s)).append(" +").append(a.gain[s]);
            if (fx.lvAfter[s] > fx.lvBefore[s]) sb.append("（升到 Lv.").append(fx.lvAfter[s]).append('）');
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
            int lv = requiredLv(bestLink, bestStat);
            return arcana(bestLink) + "社群需要" + statName(bestStat) + "达到 Lv." + lv;
        }
        int ci = h.t.charmIdx;
        if (ci >= 0 && ci < a.gain.length && a.gain[ci] > 0 && m.charmMult > 1 && st.statPts[ci] < h.t.statCap[ci]) {
            return "魅力到 Lv.6 后，和大家拉近关系会快很多";
        }
        return "这个时段没有更划算的安排，顺便提升个人能力值";
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
            if (l >= 0) return "攒钱：开启" + arcana(l) + "社群需要花钱";
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

    private int moneyGatedLink(SimState st) {
        int day = st.slot >> 1;
        for (int l = 0; l < h.t.nLinks; l++) {
            if (st.rank[l] != 0 || h.unlockValue[l] <= 0 || Kernel.lost(m, st, l, day)) continue;
            List<Rules.Step> steps = rules.links[l].unlock.steps;
            for (int j = st.unlockStep[l]; j < steps.size(); j++) {
                if (steps.get(j).cost > 0) return l;
            }
        }
        return -1;
    }

    private void describeTower(Plan.Step step, SimState st, int block, Kernel.Effect fx) {
        Rules.Block b = rules.blocks[block];
        step.kind = "tower";
        step.entity = b.id;
        step.titleZh = b.floorsZh != null && !b.floorsZh.isEmpty() ? b.zh + "（" + b.floorsZh + "）" : b.zh;
        step.locationZh = "塔尔塔罗斯";
        if (fx.rescue >= 0) {
            Rules.Rescue r = rules.rescues[fx.rescue];
            step.subtitleZh = "探索塔尔塔罗斯 · 救出" + r.zh;
            String link = r.link >= 0 && r.link < h.t.nLinks ? "，才能继续" + arcana(r.link) + "社群" : "";
            step.whyZh = r.zh + "失踪了，要在" + date(r.toDay) + "前救出" + link;
            return;
        }
        step.subtitleZh = "探索塔尔塔罗斯 · 第 " + fx.towerVisit + " 次";
        int want = h.towerTarget[block];
        if (fx.towerVisit <= want) {
            int open = h.t.towerRemain[block][st.slot];
            int left = want - st.towerVisits[block];
            if (left * 2 >= open) step.whyZh = "这个区块能进塔的晚上不多了，本区块计划去 " + want + " 次";
            else step.whyZh = "本区块计划去 " + want + " 次，这个晚上其他安排不划算";
        } else {
            step.whyZh = "这个晚上没有更好的安排，去塔里练练级";
        }
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
            if (end.rank[l] >= target) {
                String when = reachedSlot >= 0 ? "预计" + date(reachedSlot >> 1) + "达到 RANK " + rankName(target) : "已达到 RANK " + rankName(target);
                out.add(arcana(l) + "：" + when);
                continue;
            }
            int endDay = Math.min(rules.lastDay, end.slot >> 1);
            if (Kernel.lost(m, end, l, endDay)) {
                out.add(arcana(l) + "：失踪者没能及时获救，社群无法继续");
            } else if (end.rank[l] == 0) {
                String gate = unmetGate(end, l);
                out.add(arcana(l) + "：开启条件来不及满足" + (gate == null ? "" : "（需要" + gate + "）"));
            } else {
                String higher = higherGoal(goals, gi, l);
                out.add(arcana(l) + "：时间不够，预计停在 RANK " + rankName(end.rank[l])
                        + (higher == null ? "" : "（优先级低于 " + higher + "）"));
            }
        }
        if (p.otherLinks) {
            int maxed = 0;
            for (int l = 0; l < h.t.nLinks; l++) if (!m.goal[l] && end.rank[l] >= 10) maxed++;
            out.add("其余社群：预计有 " + maxed + " 个练满");
        }
        for (int b = 0; b < h.t.nBlocks; b++) {
            int want = Math.min(p.towerVisitsPerBlock, m.towerCap);
            int open = h.t.towerRemain[b][start.slot] + start.towerVisits[b];
            if (want > 0 && open < want && h.t.towerRemain[b][start.slot] > 0) {
                out.add(rules.blocks[b].zh + "：能进塔的晚上只有 " + open + " 个，少于计划的 " + want + " 次");
            }
        }
        if (p.personaBonus) out.add("已按约会时携带同阿尔卡那人格面具估算（好感 ×1.51）");
        if (m.goal.length > 0 && p.hasAnyGoal() && p.goalBuffer) {
            out.add("升级时间是估算的，「必须」「想要」目标已多留一次见面的余量");
        } else {
            out.add("升级时间是按普通见面约 " + m.ordinaryGain + " 点好感估算的");
        }
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

    private String unmetGate(SimState end, int l) {
        int[] rs = m.reqStat[l];
        for (int i = 0; i < rs.length; i++) {
            int s = rs[i];
            if (s < h.t.nStats && Kernel.statLv(m, end, s) < m.reqStatLv[l][i]) {
                return statName(s) + " Lv." + m.reqStatLv[l][i];
            }
        }
        int[] rl = m.reqLink[l];
        for (int i = 0; i < rl.length; i++) {
            if (rl[i] < h.t.nLinks && end.rank[rl[i]] < m.reqLinkRank[l][i]) {
                return arcana(rl[i]) + " RANK " + m.reqLinkRank[l][i];
            }
        }
        return null;
    }
}
