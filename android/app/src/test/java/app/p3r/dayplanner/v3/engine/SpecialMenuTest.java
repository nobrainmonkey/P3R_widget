package app.p3r.dayplanner.v3.engine;

import static app.p3r.dayplanner.v3.engine.RulesFixtures.ACADEMICS;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.CHARM;
import static app.p3r.dayplanner.v3.engine.RulesFixtures.COURAGE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import org.junit.Test;

import java.util.List;

/** A stat threshold never invents menu knowledge; a real discovery meal unlocks efficient training. */
public class SpecialMenuTest {
    private static final int WAKATSU_REGULAR = 1;
    private static final int WAKATSU_MENU = 2;
    private static final int WAKATSU_SPECIAL = 3;
    private static final int HAGAKURE_MENU = 5;
    private static final int HAGAKURE_SPECIAL = 6;

    @Test
    public void charmGateAndDiscoveryAreBothRequiredForWakatsu() {
        Rules rules = menus();
        Profile profile = new Profile();
        SimState state = at(rules, 4, 26, true); // Sunday
        state.statPts[CHARM] = rules.stats[CHARM].thresholds[3];
        assertFalse(legal(rules, profile, state, WAKATSU_SPECIAL));

        state.used[WAKATSU_MENU] = 1;
        state.statPts[CHARM]--;
        assertFalse(legal(rules, profile, state, WAKATSU_SPECIAL));
        state.statPts[CHARM]++;
        assertTrue(legal(rules, profile, state, WAKATSU_SPECIAL));

        state.slot = RulesFixtures.slot(4, 27, false);
        assertFalse("special menu is evening only", legal(rules, profile, state, WAKATSU_SPECIAL));
        state.slot = RulesFixtures.slot(4, 28, true);
        assertFalse("Tuesday has no special Wakatsu menu", legal(rules, profile, state, WAKATSU_SPECIAL));
    }

    @Test
    public void regularMealOnWrongDayDoesNotInventDiscoveryAndDiscoveryCanPrecedeCharmGate() {
        Rules rules = menus();
        Profile profile = new Profile();
        SimState state = at(rules, 4, 25, true); // Saturday: ordinary meal only
        assertFalse(legal(rules, profile, state, WAKATSU_MENU));
        state = Engine.apply(rules, profile, state, Action.activity(WAKATSU_REGULAR)).state;
        assertEquals(0, state.used[WAKATSU_MENU]);

        state = Engine.advanceTo(rules, profile, state, RulesFixtures.slot(4, 26, true));
        assertEquals(0, state.statPts[CHARM]);
        assertTrue("discovery itself has no stat gate", legal(rules, profile, state, WAKATSU_MENU));
        long cash = state.cash;
        int academics = state.statPts[ACADEMICS];
        state = Engine.apply(rules, profile, state, Action.activity(WAKATSU_MENU)).state;
        assertEquals(cash - 680, state.cash);
        assertEquals(academics + 3, state.statPts[ACADEMICS]);
        assertEquals(1, state.used[WAKATSU_MENU]);

        state = Engine.advanceTo(rules, profile, state, RulesFixtures.slot(4, 27, true));
        assertFalse("discovery cannot be repeated", legal(rules, profile, state, WAKATSU_MENU));
        assertFalse("discovery does not skip Charm Lv3", legal(rules, profile, state, WAKATSU_SPECIAL));
        state.statPts[CHARM] = rules.stats[CHARM].thresholds[3];
        assertTrue(legal(rules, profile, state, WAKATSU_SPECIAL));
        cash = state.cash;
        academics = state.statPts[ACADEMICS];
        SimState after = Engine.apply(rules, profile, state, Action.activity(WAKATSU_SPECIAL)).state;
        assertEquals(cash - 900, after.cash);
        assertEquals(academics + 4, after.statPts[ACADEMICS]);
    }

    @Test
    public void hagakureUsesCourageGateAndItsOwnDiscovery() {
        Rules rules = menus();
        Profile profile = new Profile();
        SimState state = at(rules, 4, 28, true); // Tuesday
        state.statPts[COURAGE] = rules.stats[COURAGE].thresholds[3];
        state.used[WAKATSU_MENU] = 1;
        assertFalse("another restaurant's discovery does not count", legal(rules, profile, state, HAGAKURE_SPECIAL));
        state = Engine.apply(rules, profile, state, Action.activity(HAGAKURE_MENU)).state;
        state = Engine.advanceTo(rules, profile, state, RulesFixtures.slot(4, 29, true));
        assertTrue(legal(rules, profile, state, HAGAKURE_SPECIAL));
        state.statPts[COURAGE]--;
        assertFalse(legal(rules, profile, state, HAGAKURE_SPECIAL));
    }

    @Test
    public void plannerDiscoversMenuAndThenGetsMorePointsPerSlot() {
        Rules rules = menus();
        Profile profile = new Profile();
        SimState start = at(rules, 4, 26, true);
        start.statPts[CHARM] = rules.stats[CHARM].thresholds[6];
        start.statPts[COURAGE] = rules.stats[COURAGE].thresholds[6];
        Plan plan = plan(rules, profile, start);
        assertEquals("act:meal_wakatsu_menu", plan.steps.get(0).action);
        Plan.Step special = first(plan, "act:meal_wakatsu_special");
        assertNotNull("planner should use the cheaper +4 alternative after discovery", special);
        assertTrue(special.slot > plan.steps.get(0).slot);
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.isEmpty());

        Profile regularOnly = new Profile();
        regularOnly.excludedActivities = new String[]{"meal_wakatsu_special"};
        Plan baseline = plan(rules, regularOnly, start);
        assertTrue("same slots should earn more Academics with the special menu",
                finish(rules, profile, start, plan).statPts[ACADEMICS]
                        > finish(rules, regularOnly, start, baseline).statPts[ACADEMICS]);
    }

    @Test
    public void independentValidatorRejectsSubstitutingAnOrdinaryMealForDiscovery() {
        Rules rules = menus();
        Profile profile = new Profile();
        SimState start = at(rules, 4, 26, true);
        start.statPts[CHARM] = rules.stats[CHARM].thresholds[6];
        start.statPts[COURAGE] = rules.stats[COURAGE].thresholds[6];
        Plan plan = plan(rules, profile, start);
        assertNotNull(first(plan, "act:meal_wakatsu_special"));
        Plan.Step discovery = first(plan, "act:meal_wakatsu_menu");
        assertNotNull(discovery);
        // Identical stat/cash effects make this corruption visible only to the prerequisite check.
        discovery.action = "act:meal_wakatsu";
        List<String> errors = Engine.validate(rules, profile, start, plan);
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("needs prior activity meal_wakatsu_menu")));
    }

    private static boolean legal(Rules rules, Profile profile, SimState state, int activity) {
        for (Action action : Engine.legalActions(rules, profile, state)) {
            if (action.type == Action.Type.ACTIVITY && action.target == activity) return true;
        }
        return false;
    }

    private static SimState at(Rules rules, int month, int day, boolean evening) {
        SimState state = RulesFixtures.newGame(rules, RulesFixtures.slot(month, day, evening));
        state.cash = 100_000;
        return state;
    }

    private static Plan plan(Rules rules, Profile profile, SimState start) {
        Engine.Options options = new Engine.Options();
        options.timeBudgetMillis = 0; // Base policy must see discovery without a lucky rollout.
        return Engine.plan(rules, profile, start, null, options);
    }

    private static Plan.Step first(Plan plan, String action) {
        for (Plan.Step step : plan.steps) if (action.equals(step.action)) return step;
        return null;
    }

    private static SimState finish(Rules rules, Profile profile, SimState start, Plan plan) {
        SimState state = start;
        for (Plan.Step step : plan.steps) state = Engine.apply(rules, profile, state, Action.parse(rules, step.action)).state;
        return state;
    }

    private static Rules menus() {
        Rules rules = RulesFixtures.small();
        rules.lastDay = RulesFixtures.day(5, 3);
        rules.links = new Rules.Link[0];
        rules.blocks = new Rules.Block[0];
        rules.rescues = new Rules.Rescue[0];
        for (Rules.Day day : rules.days) {
            if (day == null) continue;
            day.dayLock = day.eveLock = null;
            day.tartarus = false;
            day.block = -1;
        }
        rules.activities = new Rules.Activity[]{
                RulesFixtures.activity(0, "study_room", "学习", Rules.ActivityKind.STAT, true, true, 0, ACADEMICS, 2),
                RulesFixtures.activity(1, "meal_wakatsu", "若津普通餐", Rules.ActivityKind.STAT, true, true, 680, ACADEMICS, 3),
                RulesFixtures.activity(2, "meal_wakatsu_menu", "若津普通餐，了解菜单", Rules.ActivityKind.STAT, false, true, 680, ACADEMICS, 3),
                RulesFixtures.activity(3, "meal_wakatsu_special", "水产全席套餐", Rules.ActivityKind.STAT, false, true, 900, ACADEMICS, 4),
                RulesFixtures.activity(4, "meal_hagakure", "叶隐普通餐", Rules.ActivityKind.STAT, true, true, 900, CHARM, 3),
                RulesFixtures.activity(5, "meal_hagakure_menu", "叶隐普通餐，了解菜单", Rules.ActivityKind.STAT, false, true, 900, CHARM, 3),
                RulesFixtures.activity(6, "meal_hagakure_special", "叶隐限定餐", Rules.ActivityKind.STAT, false, true, 1200, CHARM, 4),
        };
        rules.activities[WAKATSU_REGULAR].weekdayMask = RulesFixtures.mask(1, 3, 4, 5, 6, 7);
        rules.activities[WAKATSU_MENU].weekdayMask = RulesFixtures.mask(1, 4, 5, 7);
        rules.activities[WAKATSU_MENU].once = true;
        rules.activities[WAKATSU_SPECIAL].weekdayMask = RulesFixtures.mask(1, 4, 5, 7);
        rules.activities[WAKATSU_SPECIAL].requiresLv.put(CHARM, 3);
        rules.activities[WAKATSU_SPECIAL].requiresActivity = WAKATSU_MENU;
        rules.activities[4].weekdayMask = RulesFixtures.mask(1, 2, 3, 4, 5);
        rules.activities[HAGAKURE_MENU].weekdayMask = RulesFixtures.mask(2, 3, 5);
        rules.activities[HAGAKURE_MENU].once = true;
        rules.activities[HAGAKURE_SPECIAL].weekdayMask = RulesFixtures.mask(2, 3, 5);
        rules.activities[HAGAKURE_SPECIAL].requiresLv.put(COURAGE, 3);
        rules.activities[HAGAKURE_SPECIAL].requiresActivity = HAGAKURE_MENU;
        return rules;
    }
}
