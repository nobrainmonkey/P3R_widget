package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan quality metrics for tests and tuning, recomputed by replaying a plan's action keys through
 * the kernel from the observed state (side steps included).
 *
 * <ul>
 *   <li>{@link #daytimeWaste}: daytime slots in which the plan does something other than a meeting
 *       or an unlock step while a wanted (goal, or any link when 其余社群 is on), established,
 *       not-yet-finished link could be met in that slot.</li>
 *   <li>{@link #unlockDay}: day each manual link gets established (-1 never, -2 already at the start).</li>
 *   <li>{@link #towerVisits}: planned visits per Tartarus block (observed ones included).</li>
 *   <li>job shifts by slot kind and the cash they earn, end cash.</li>
 * </ul>
 */
final class PlanMetrics {
    final Rules rules;
    int maxed;
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
    int unmetTower;
    /** Rescues (index) planned inside their window while the link still mattered. */
    long rescued;
    long rescueNeeded;

    private PlanMetrics(Rules rules) {
        this.rules = rules;
        unlockDay = new int[rules.links.length];
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
            pm.towerRequired[b] = Math.min(Math.min(profile.towerVisitsPerBlock, profile.towerCapPerBlock), open);
        }
        for (int i = 0; i < t.nRescues; i++) {
            int l = t.rescueLink[i];
            boolean open = ((st.rescued | st.rescueFailed) & (1L << i)) == 0 && st.slot <= t.rescueTo[i] * 2 + 1;
            if (open && l >= 0 && l < t.nLinks && st.rank[l] < 10 && (m.goal[l] || profile.otherLinks)) pm.rescueNeeded |= 1L << i;
        }
        for (Plan.Step step : plan.steps) {
            int slot = st.slot;
            int code = Kernel.FREE;
            Action a = Action.parse(rules, step.action);
            if (a != null && a.isProductive()) code = Kernel.encode(a);
            int type = Kernel.type(code);
            boolean open = t.open(slot);
            boolean day = (slot & 1) == 0;
            if (open && day && type != Kernel.T_MEET && type != Kernel.T_UNLOCK && meetableWanted(m, st, slot)) {
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
                .append(" · free ").append(freeSlots).append('\n');
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
}
