package app.p3r.dayplanner.v3.ui;

import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Dialogue hints for social steps. Text comes only from the rule pack's verbatim CN guide
 * fragments ({@code links[].dialogue}); otherwise the neutral fallback is shown. Foreign option
 * ordinals are never displayed (docs/DESIGN_v3.md §10.7).
 */
final class Dialogue {
    static final String FALLBACK = "选能让对方高兴的回答（♪ 越多越好）";

    private Dialogue() {}

    /** "推荐回答：A / B" for a social step, the fallback for other social steps, null otherwise. */
    static String line(Rules rules, Plan.Step step) {
        if (step == null || !"social".equals(step.kind)) {
            return null;
        }
        Rules.Link link = rules == null || step.entity == null ? null : rules.link(step.entity);
        if (link != null && step.predicted != null) {
            int target = step.predicted.rankUp ? step.predicted.rankTo : step.predicted.rankFrom;
            List<String> options = link.dialogue.get(target);
            if (options != null && !options.isEmpty()) {
                return "推荐回答：" + String.join(" / ", options);
            }
        }
        return FALLBACK;
    }
}
