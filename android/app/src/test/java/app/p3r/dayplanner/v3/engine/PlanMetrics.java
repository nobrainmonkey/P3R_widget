package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan quality metrics for tests and tuning, recomputed by replaying a plan's action keys through
 * the kernel from the observed state (side steps included).
 *
 * <ul>
 *   <li>{@link #daytimeWaste}: daytime slots in which the plan does something other than a meeting,
 *       an unlock step or an opted-in Link Episode while a wanted (goal, or any link when 其余社群 is on), established,
 *       not-yet-finished link could be met in that slot.</li>
 *   <li>{@link #unlockDay}: day each manual link gets established (-1 never, -2 already at the start).</li>
 *   <li>{@link #towerVisits}: planned visits per Tartarus block (observed ones included).</li>
 *   <li>job shifts by slot kind and the cash they earn, end cash.</li>
 *   <li>breadth (安排风格 「尽量都体验」, DESIGN §20): links unlocked (RANK ≥ 1), the sum of end
 *       ranks, links at RANK 5 or above, the lowest end rank among non-story links, and the Link
 *       Episodes the plan watches.</li>
 * </ul>
 */
final class PlanMetrics {
    final Rules rules;
    int maxed;
    /** End rank per link (MAX = 10). */
    final int[] endRank;
    /** Links at RANK 1 or above at the end, the sum of end ranks, links at RANK 5 or above. */
    int unlocked;
    int rankSum;
    int atLeast5;
    /** Lowest end rank among non-story (manual) links. */
    int minRank;
    /**
     * Lowest end rank among the non-story links that are unlocked at the end: of this plan, or, for a
     * 尽量都体验 / 尽量多 MAX comparison, of either plan (PlanBreadthRealPackTest sets it). A link no
     * plan opens (女皇 from 10/22 needs Academics MAX from Lv.4) says nothing about the styles.
     */
    int minOpened;
    /** The plan measured, and its row for the metrics table while it waits for {@link #minOpened} (or null). */
    Plan plan;
    Runnable pending;
    int daytimeWaste;
    final List<Integer> wasteSlots = new ArrayList<>();
    final int[] unlockDay;
    final int[] towerVisits;
    final int[] towerRequired;
    int dayJobs;
    int eveJobs;
    long jobIncome;
    long endCash;
    int dayStats;
    int eveStats;
    int freeSlots;
    /** Dating-site notes and Link Episodes in the plan. */
    int notes;
    int episodes;
    int unmetTower;
    /** Rescues (index) planned inside their window while the link still mattered. */
    long rescued;
    long rescueNeeded;
    /** First slot of the plan and the forecast cash at the start of each of its slots. */
    int fromSlot;
    long[] cashAt = new long[0];

    private PlanMetrics(Rules rules) {
        this.rules = rules;
        unlockDay = new int[rules.links.length];
        endRank = new int[rules.links.length];
        towerVisits = new int[rules.blocks.length];
        towerRequired = new int[rules.blocks.length];
    }

    static PlanMetrics of(Rules rules, Profile profile, SimState observed, Plan plan) {
        PlanMetrics pm = new PlanMetrics(rules);
        Model m = new Model(rules, profile, null);
        SimState st = Kernel.normalize(m, observed);
        Tables t = m.t;
        for (int l = 0; l < t.nLinks; l++) pm.unlockDay[l] = st.rank[l] > 0 ? -2 : -1;
        for (int b = 0; b < t.nBlocks; b++) {
            int open = t.towerRemain[b][st.slot] + st.towerVisits[b];
            String id = rules.blocks[b].id;
            pm.towerRequired[b] = Math.min(Math.min(profile.visitsFor(id), profile.capFor(id)), open);
        }
        for (int i = 0; i < t.nRescues; i++) {
            int l = t.rescueLink[i];
            boolean open = ((st.rescued | st.rescueFailed) & (1L << i)) == 0 && st.slot <= t.rescueTo[i] * 2 + 1;
            // Possible only if some evening of the window is enterable and its block has room under the cap.
            boolean possible = false;
            for (int s = Math.max(st.slot, t.rescueFrom[i] * 2); s <= Math.min(t.lastSlot, t.rescueTo[i] * 2 + 1); s++) {
                int b = t.towerBlock[s];
                if (b >= 0 && st.towerVisits[b] < profile.capFor(rules.blocks[b].id)) possible = true;
            }
            if (open && possible && l >= 0 && l < t.nLinks && st.rank[l] < 10 && (m.goal[l] || profile.otherLinks)) {
                pm.rescueNeeded |= 1L << i;
            }
        }
        pm.fromSlot = st.slot;
        pm.cashAt = new long[plan.steps.size()];
        for (Plan.Step step : plan.steps) {
            int slot = st.slot;
            if (slot - pm.fromSlot < pm.cashAt.length) pm.cashAt[slot - pm.fromSlot] = st.cash;
            int code = Kernel.FREE;
            Action a = Action.parse(rules, step.action);
            if (a != null && a.isProductive()) code = Kernel.encode(a);
            int type = Kernel.type(code);
            boolean open = t.open(slot);
            boolean day = (slot & 1) == 0;
            // A Link Episode of a track the player opted into is not waste either (it is wanted).
            if (open && day && type != Kernel.T_MEET && type != Kernel.T_UNLOCK && type != Kernel.T_EPISODE
                    && meetableWanted(m, st, slot)) {
                pm.daytimeWaste++;
                pm.wasteSlots.add(slot);
            }
            if (open && type == Kernel.T_ACT) {
                Rules.Activity act = rules.activities[Kernel.target(code)];
                if (act.kind == Rules.ActivityKind.JOB) {
                    if (day) pm.dayJobs++;
                    else pm.eveJobs++;
                    pm.jobIncome += act.payFor(st.shifts[act.idx] + 1);
                } else if (day) {
                    pm.dayStats++;
                } else {
                    pm.eveStats++;
                }
            }
            if (open && code == Kernel.FREE) pm.freeSlots++;
            if (open && type == Kernel.T_NOTE) pm.notes++;
            if (open && type == Kernel.T_EPISODE) pm.episodes++;
            int[] before = st.rank.clone();
            Kernel.apply(m, st, open ? code : Kernel.FREE, null);
            for (int l = 0; l < t.nLinks; l++) {
                if (before[l] == 0 && st.rank[l] > 0 && pm.unlockDay[l] == -1) pm.unlockDay[l] = slot >> 1;
            }
        }
        System.arraycopy(st.towerVisits, 0, pm.towerVisits, 0, Math.min(st.towerVisits.length, pm.towerVisits.length));
        for (int b = 0; b < t.nBlocks; b++) pm.unmetTower += Math.max(0, pm.towerRequired[b] - pm.towerVisits[b]);
        pm.rescued = st.rescued & pm.rescueNeeded;
        pm.endCash = st.cash;
        for (int l = 0; l < t.nLinks; l++) if (st.rank[l] >= 10) pm.maxed++;
        System.arraycopy(st.rank, 0, pm.endRank, 0, Math.min(st.rank.length, pm.endRank.length));
        pm.minRank = pm.minRankOver(null);
        boolean[] opened = new boolean[pm.endRank.length];
        for (int l = 0; l < opened.length; l++) opened[l] = pm.endRank[l] >= 1;
        pm.minOpened = pm.minRankOver(opened);
        for (int r : pm.endRank) {
            if (r >= 1) pm.unlocked++;
            if (r >= 5) pm.atLeast5++;
            pm.rankSum += r;
        }
        return pm;
    }

    /** A wanted, established, unfinished link has a legal meeting in this (daytime) slot. */
    static boolean meetableWanted(Model m, SimState st, int slot) {
        int day = slot >> 1;
        int mask = m.t.meetMask[slot];
        for (int l = 0; mask != 0; l++, mask >>>= 1) {
            if ((mask & 1) == 0 || !(m.goal[l] || m.profile.otherLinks)) continue;
            int goalRank = m.goal[l] && !m.profile.otherLinks ? m.target(l) : 10;
            if (st.rank[l] >= goalRank) continue;
            if (Kernel.meetLegal(m, st, l, day)) return true;
        }
        return false;
    }

    /** Forecast cash at the start of {@code slot} (the plan's first or last value outside it). */
    long cashBefore(int slot) {
        if (cashAt.length == 0) return endCash;
        int i = Math.max(0, Math.min(cashAt.length - 1, slot - fromSlot));
        return slot - fromSlot >= cashAt.length ? endCash : cashAt[i];
    }

    /** Records the row this plan's test left pending (see {@link #pending}). */
    void recordPending() {
        Runnable r = pending;
        pending = null;
        if (r != null) r.run();
    }

    /**
     * Lowest end rank among the non-story links in {@code links} (null: every non-story link), or
     * 10 when there is none.
     */
    int minRankOver(boolean[] links) {
        int min = 10;
        for (Rules.Link l : rules.links) {
            if (l.kind == Rules.LinkKind.MANUAL && (links == null || links[l.idx])) min = Math.min(min, endRank[l.idx]);
        }
        return min;
    }

    /** End ranks of the non-story links, lowest first (for a leximin comparison of two plans). */
    int[] sortedManualRanks() {
        int[] out = new int[rules.links.length];
        int n = 0;
        for (Rules.Link l : rules.links) if (l.kind == Rules.LinkKind.MANUAL) out[n++] = endRank[l.idx];
        out = Arrays.copyOf(out, n);
        Arrays.sort(out);
        return out;
    }

    /** "解锁 21/22 · RANK 合计 152 · RANK 5 以上 14 · 最低 RANK 0（解锁的 3）": the breadth metrics in one line. */
    String breadthLine() {
        return "解锁 " + unlocked + "/" + rules.links.length + " · RANK 合计 " + rankSum + " · RANK 5 以上 " + atLeast5
                + " · 最低 RANK " + minRank + "（解锁的 " + minOpened + "）· 羁绊剧情 " + episodes;
    }

    int rescuesMissed() {
        return Long.bitCount(rescueNeeded & ~rescued);
    }

    String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("metrics: maxed ").append(maxed).append('/').append(rules.links.length)
                .append(" · daytimeWaste ").append(daytimeWaste)
                .append(" · end cash ").append(endCash)
                .append(" · jobs day ").append(dayJobs).append(" eve ").append(eveJobs).append(" (+").append(jobIncome).append(")")
                .append(" · stat training day ").append(dayStats).append(" eve ").append(eveStats)
                .append(" · free ").append(freeSlots)
                .append(" · notes ").append(notes).append(" · episodes ").append(episodes).append('\n');
        sb.append("breadth: unlocked ").append(unlocked).append('/').append(rules.links.length)
                .append(" · rank sum ").append(rankSum).append(" · RANK ≥ 5 ").append(atLeast5)
                .append(" · min rank (non-story) ").append(minRank).append('\n');
        sb.append("unlocks:");
        for (Rules.Link l : rules.links) {
            if (l.kind != Rules.LinkKind.MANUAL) continue;
            int d = unlockDay[l.idx];
            sb.append(' ').append(l.id).append('=').append(d == -2 ? "start" : d < 0 ? "NEVER" : PlanReport.date(rules, d * 2).replace(" 白天", ""));
        }
        sb.append("\ntartarus visits/required:");
        for (int b = 0; b < towerVisits.length; b++) sb.append(' ').append(towerVisits[b]).append('/').append(towerRequired[b]);
        sb.append(" · unmet ").append(unmetTower).append(" · rescues missed ").append(rescuesMissed());
        if (!wasteSlots.isEmpty()) {
            sb.append("\nwasted daytime:");
            for (int i = 0; i < Math.min(40, wasteSlots.size()); i++) sb.append(' ').append(PlanReport.date(rules, wasteSlots.get(i)).replace(" 白天", ""));
        }
        return sb.append('\n').toString();
    }

    // ------------------------------------------------------------------ metrics table (docs/PLAN_METRICS.md)

    /**
     * One row per scenario run on the real pack, shared by PlanRealPackTest and
     * PlanBreadthRealPackTest (one test JVM): whichever class finishes last writes every row.
     */
    private static final List<String[]> ROWS = new ArrayList<>();

    /** 「尽量都体验」 / 「尽量多 MAX」, as the 规划设置 card names the profile's 安排风格. */
    static String styleZh(Profile profile) {
        return profile.style == Profile.Style.MAX ? "尽量多 MAX" : "尽量都体验";
    }

    /** Adds the row of one scenario run (title, style, span, counts, breadth metrics, goal MAX dates, runtime). */
    static void record(Rules rules, String title, Profile profile, SimState start, Plan plan, PlanMetrics pm, long ms) {
        StringBuilder goals = new StringBuilder();
        for (int l : goalOrder(profile)) {
            if (goals.length() > 0) goals.append("；");
            String name = rules.links[l].arcanaZh;
            int target = Math.max(1, Math.min(10, profile.target[l]));
            int at = plan.summary.maxSlot[l];
            if (start.rank[l] >= target) {
                goals.append(name).append(" 已经 MAX");
            } else if (plan.summary.endRanks[l] >= target && at >= 0) {
                goals.append(name).append(' ').append(PlanReport.date(rules, at));
            } else {
                goals.append(name).append(" 停在 RANK ").append(plan.summary.endRanks[l]);
            }
        }
        synchronized (ROWS) {
            ROWS.add(new String[]{title, styleZh(profile), PlanReport.date(rules, start.slot),
                    PlanReport.date(rules, plan.fromSlot) + " → " + PlanReport.date(rules, plan.toSlot),
                    String.valueOf(plan.steps.size()), String.valueOf(plan.summary.usefulActions),
                    String.valueOf(plan.summary.freeSlots), pm.maxed + "/" + rules.links.length,
                    pm.unlocked + "/" + rules.links.length, String.valueOf(pm.rankSum), String.valueOf(pm.atLeast5),
                    String.valueOf(pm.minRank), String.valueOf(pm.minOpened), String.valueOf(pm.episodes),
                    goals.length() == 0 ? "—" : goals.toString(), ms + " ms"});
        }
    }

    /** Goal links in priority order: 「必须」 then 「想要」, each by order. */
    private static List<Integer> goalOrder(Profile p) {
        List<Integer> out = new ArrayList<>();
        for (Profile.Tier tier : new Profile.Tier[]{Profile.Tier.MUST, Profile.Tier.WANT}) {
            List<Integer> part = new ArrayList<>();
            for (int l = 0; l < p.tier.length; l++) if (p.tier[l] == tier) part.add(l);
            part.sort((a, b) -> p.order[a] != p.order[b] ? Integer.compare(p.order[a], p.order[b]) : Integer.compare(a, b));
            out.addAll(part);
        }
        return out;
    }

    /**
     * Writes every row recorded so far to {@link #tableFile} (the integrator copies it to
     * docs/PLAN_METRICS.md), sorted by start date, title and style, so only the runtimes change from
     * run to run.
     */
    static void writeTable(Rules rules) throws IOException {
        List<String[]> rows;
        synchronized (ROWS) {
            rows = new ArrayList<>(ROWS);
        }
        if (rows.isEmpty() || rules == null) return;
        rows.sort((a, b) -> {
            int c = Integer.compare(dateKey(a[2]), dateKey(b[2]));
            if (c == 0) c = a[0].compareTo(b[0]);
            return c != 0 ? c : a[1].compareTo(b[1]);
        });
        StringBuilder md = new StringBuilder();
        md.append("# 安排质量（真实规则包）\n\n");
        md.append("由 `PlanRealPackTest` 和 `PlanBreadthRealPackTest` 生成：规则包 ").append(rules.packVersion)
                .append(System.getenv("CI") != null
                        ? "，共享构建环境的 CI 选项（时间预算 7.5 秒；应用默认 2.5 秒），桌面 JVM，用时随机器而变。"
                        : "，默认选项（时间预算 2.5 秒），桌面 JVM，用时随机器而变。")
                .append("每个场景都排到 1月31日，并通过独立校验（PlanValidator）。")
                .append("「解锁」是 RANK 1 以上的社群，「最低 RANK」只看非剧情社群；「最低 RANK（解锁的）」只看解锁了的非剧情社群，")
                .append("「对比」两行按两种风格任一解锁的社群计算，避免双方都未解锁的社群主导最低等级。\n\n");
        md.append("| 场景 | 安排风格 | 开始 | 安排范围 | 步数 | 要做的事 | 自由活动 | MAX 社群 | 解锁 | RANK 合计 | RANK 5 以上 | 最低 RANK | 最低 RANK（解锁的） | 羁绊剧情 | 目标升到 MAX | 用时 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (String[] r : rows) {
            md.append('|');
            for (String cell : r) md.append(' ').append(cell.replace("|", "／")).append(" |");
            md.append('\n');
        }
        File out = tableFile();
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        Files.write(out.toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("plan metrics: " + out.getAbsolutePath());
    }

    /** Sort key of a "10月22日 白天" cell, April first. */
    private static int dateKey(String date) {
        Matcher m = Pattern.compile("(\\d+)月(\\d+)日 (白天|晚上)").matcher(date);
        if (!m.find()) return Integer.MAX_VALUE;
        int month = Integer.parseInt(m.group(1));
        int key = (month < 4 ? month + 12 : month) * 100 + Integer.parseInt(m.group(2));
        return key * 2 + ("晚上".equals(m.group(3)) ? 1 : 0);
    }

    /** {@code android/app/build/plan_metrics.md}, wherever Gradle runs the tests from. */
    static File tableFile() {
        String override = System.getProperty("p3r.metrics.out");
        if (override != null && !override.isEmpty()) return new File(override);
        if (new File("src/main/assets/p3r").isDirectory()) return new File("build/plan_metrics.md");
        if (new File("app/src/main/assets/p3r").isDirectory()) return new File("app/build/plan_metrics.md");
        return new File("android/app/build/plan_metrics.md");
    }
}
