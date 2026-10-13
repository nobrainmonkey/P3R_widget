package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.List;

/**
 * Real-scale timing and quality on the synthetic full-year pack (22 links, Apr 21 → Jan 31).
 * Requirement: a new game with every link as "其余社群" plans the whole calendar in ≤ 2.5 s on the
 * desktop JVM with default options.
 */
public class PlanPerformanceTest {

    @Test
    public void newGameAllOthersWithinBudget() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.others();
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        // Warm up the JIT once (a phone would also have warm code after the first replan).
        Engine.plan(rules, profile, start, null, quickOptions());

        Engine.Options options = new Engine.Options();
        long t0 = System.nanoTime();
        Plan plan = Engine.plan(rules, profile, start, null, options);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        PlanReport.print("synthetic year · new game · all others", rules, plan, 0);
        System.out.println("elapsed " + ms + " ms, budgetExhausted=" + plan.budgetExhausted);
        assertEquals(rules.lastSlot() - start.slot + 1, plan.steps.size());
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());
        assertTrue("took " + ms + " ms", ms <= 2500);
        assertFalse("rollout did not finish inside the budget", plan.budgetExhausted);
    }

    @Test
    public void personaBonusMaxesMostLinks() {
        Rules rules = RulesFixtures.fullYear();
        Profile profile = RulesFixtures.others();
        profile.personaBonus = true;
        SimState start = RulesFixtures.newGame(rules, RulesFixtures.slot(4, 21, false));
        Plan plan = Engine.plan(rules, profile, start, null, new Engine.Options());
        PlanReport.print("synthetic year · new game · persona bonus", rules, plan, 0);
        assertTrue(Engine.validate(rules, profile, start, plan).isEmpty());
    }

    private static Engine.Options quickOptions() {
        Engine.Options o = new Engine.Options();
        o.timeBudgetMillis = 400;
        return o;
    }
}
