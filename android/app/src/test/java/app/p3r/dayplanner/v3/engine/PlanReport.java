package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Rules;

/** Human-readable plan dumps for test output (stdout). */
final class PlanReport {
    private PlanReport() {}

    static int maxedLinks(Rules rules, Plan plan) {
        int n = 0;
        for (Rules.Link l : rules.links) if (plan.summary.endRanks[l.idx] >= 10) n++;
        return n;
    }

    static String date(Rules rules, int slot) {
        Rules.Day d = rules.day(slot >> 1);
        if (d == null) return "slot " + slot;
        return d.month() + "月" + d.dayOfMonth() + "日" + ((slot & 1) == 1 ? " 晚上" : " 白天");
    }

    /** Prints span, counts, end ranks, notes and the first {@code days} days of the plan. */
    static void print(String title, Rules rules, Plan plan, int days) {
        StringBuilder sb = new StringBuilder();
        sb.append("==== ").append(title).append('\n');
        sb.append("span ").append(date(rules, plan.fromSlot)).append(" → ").append(date(rules, plan.toSlot))
                .append(" · steps ").append(plan.steps.size())
                .append(" · useful ").append(plan.summary.usefulActions)
                .append(" · free ").append(plan.summary.freeSlots)
                .append(" · locked ").append(plan.summary.lockedSlots)
                .append(" · last useful ").append(plan.summary.lastUsefulSlot >= 0 ? date(rules, plan.summary.lastUsefulSlot) : "-")
                .append(" · maxed ").append(maxedLinks(rules, plan)).append('/').append(rules.links.length)
                .append(" · elapsed ").append(plan.elapsedMillis).append(" ms")
                .append(plan.budgetExhausted ? " (budget exhausted)" : "").append('\n');
        sb.append("end ranks:");
        for (Rules.Link l : rules.links) sb.append(' ').append(l.arcanaZh).append(plan.summary.endRanks[l.idx] >= 10 ? "MAX" : String.valueOf(plan.summary.endRanks[l.idx]));
        sb.append("\nend stats Lv ").append(plan.summary.endStatLv[0]).append('/').append(plan.summary.endStatLv[1]).append('/').append(plan.summary.endStatLv[2])
                .append(" · end cash ").append(plan.summary.endCash).append('\n');
        for (String n : plan.summary.notesZh) sb.append("  · ").append(n).append('\n');
        int until = plan.fromSlot + days * 2;
        for (Plan.Step s : plan.steps) {
            if (s.slot >= until) break;
            sb.append(String.format("  %-12s %-7s %s | %s%s%s%n", date(rules, s.slot), s.kind, s.titleZh, s.subtitleZh,
                    s.whyZh != null ? " | " + s.whyZh : "", s.estimate ? " ≈" : ""));
        }
        System.out.print(sb);
    }
}
