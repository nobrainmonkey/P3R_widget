"""Synthetic-only acceptance tests. These fixtures contain NO real P3R rules."""

from copy import deepcopy
from dataclasses import replace
import unittest

from planner_core import (
    Action, Constraints, Goal, Interval, Ruleset, RuleViolation, Snapshot, Stage,
    Step, plan, transition, validate_plan,
)


class SyntheticCoreTests(unittest.TestCase):
    def state(self, slot=0, visits=None, revision=7, **values):
        return Snapshot("synthetic-run", "synthetic-v1", revision, slot,
                        {k: v if isinstance(v, Interval) else Interval.exact(v)
                         for k, v in values.items()}, visits or {})

    def rules(self, *actions, horizon=2, fields=("score", "cash", "key"), **kwargs):
        return Ruleset("synthetic-v1", horizon, frozenset(fields), tuple(actions), **kwargs)

    def assertViolation(self, code, function, *args):
        with self.assertRaises(RuleViolation) as caught:
            function(*args)
        self.assertEqual(code, caught.exception.code)

    def test_intervals_validate_and_keep_unknown_unknown(self):
        self.assertEqual(Interval(3, 6), Interval(1, 2).plus(Interval(2, 4)))
        self.assertEqual(Interval(None, 6), Interval(None, 2).plus(Interval(2, 4)))
        self.assertEqual(Interval.unknown(), Interval.unknown().plus(Interval.exact(99)))
        for values in [(2, 1), (True, 3), (0.5, 1)]:
            with self.assertRaises(ValueError):
                Interval(*values)

    def test_snapshot_copies_input_mappings(self):
        values = {"score": Interval.exact(0)}
        visits = {"A": 1}
        state = Snapshot("run", "v1", 1, 0, values, visits)
        values["score"] = Interval.exact(99)
        visits["A"] = 99
        self.assertEqual(Interval.exact(0), state.values["score"])
        self.assertEqual(1, state.visits["A"])

    def test_transition_is_pure_and_does_not_advance_observed_revision(self):
        action = Action("gain", frozenset({0}), effects={"score": 3})
        state = self.state(score=0, cash=4)
        before = deepcopy(state)
        output = transition(self.rules(action), state, action, Constraints())
        self.assertEqual(before, state)
        self.assertEqual(Interval.exact(3), output.state.values["score"])
        self.assertEqual(7, output.state.revision)
        self.assertEqual(1, output.state.slot)
        self.assertEqual((), output.assumptions)

    def test_plan_is_deterministic_with_unordered_action_input(self):
        a = Action("a", frozenset({0, 1}), effects={"score": 1})
        b = Action("b", frozenset({0, 1}), effects={"score": 1})
        state = self.state(score=0)
        first = plan(self.rules(b, a), state, [Goal("score", 2)], Constraints())
        second = plan(self.rules(a, b), state, [Goal("score", 2)], Constraints())
        self.assertEqual(first, second)
        self.assertEqual(["a", "a"], [s.action_id for s in first.steps])
        self.assertEqual("feasible_within_model", first.status)

    def test_forecasting_and_replay_do_not_mutate_observed_state(self):
        a = Action("gain", frozenset({0, 1}), effects={"score": 1})
        rules = self.rules(a)
        state = self.state(score=0)
        original = deepcopy(state)
        output = plan(rules, state, [Goal("score", 2)], Constraints())
        self.assertEqual(output.forecast, validate_plan(rules, state, output.steps, Constraints()))
        self.assertEqual(original, state)

    def test_empty_replay_returns_independent_snapshot(self):
        state = self.state(score=0)
        replay = validate_plan(self.rules(), state, (), Constraints())
        replay.values["score"] = Interval.exact(5)
        self.assertEqual(Interval.exact(0), state.values["score"])

    def test_requirement_above_known_upper_bound_is_illegal(self):
        action = Action("needs_key", frozenset({0}), requirements={"key": 2})
        self.assertViolation("requirement", transition, self.rules(action),
                             self.state(key=1), action, Constraints())

    def test_unknown_requirement_must_be_conditional(self):
        action = Action("gain", frozenset({0}), requirements={"key": 1}, effects={"score": 1})
        output = plan(self.rules(action, horizon=1), self.state(score=0),
                      [Goal("score", 1)], Constraints())
        self.assertEqual("conditional", output.status)
        self.assertTrue(any("key" in text for text in output.explanations))

    def test_maximum_precondition_exact_at_boundary_allowed_above_rejected(self):
        action = Action("rank_up", frozenset({0}), effects={"score": 1}, maximums={"score": 9})
        rules = self.rules(action, horizon=1)
        result = transition(rules, self.state(score=9), action, Constraints())
        self.assertEqual(Interval.exact(10), result.state.values["score"])
        self.assertEqual((), result.assumptions)
        self.assertViolation("requirement", transition, rules, self.state(score=10), action, Constraints())

    def test_maximum_range_overlap_is_conditional_and_never_forecasts_overcap(self):
        action = Action("rank_up", frozenset({0}), effects={"score": 1}, maximums={"score": 9})
        result = transition(self.rules(action), self.state(score=Interval(8, 12)), action, Constraints())
        self.assertEqual(Interval(9, 10), result.state.values["score"])
        self.assertTrue(result.assumptions)
        output = plan(self.rules(action, horizon=1), self.state(score=Interval(8, 9)),
                      [Goal("score", 10)], Constraints())
        self.assertEqual("conditional", output.status)

    def test_maximum_unknown_is_explicitly_conditional(self):
        action = Action("bounded", frozenset({0}), maximums={"key": 9}, effects={"score": 1})
        result = transition(self.rules(action), self.state(score=0), action, Constraints())
        self.assertEqual(Interval(None, 9), result.state.values["key"])
        self.assertTrue(any("at most 9" in text for text in result.assumptions))
        self.assertEqual("conditional", plan(self.rules(action, horizon=1), self.state(score=0),
                         [Goal("score", 1)], Constraints()).status)

    def test_maximum_unmodeled_field_is_unsupported(self):
        action = Action("bounded", frozenset({0}), maximums={"missing": 9})
        self.assertViolation("unsupported_field", transition, self.rules(action), self.state(), action, Constraints())

    def test_conflicting_minimum_and_maximum_rejected(self):
        action = Action("impossible", frozenset({0}), requirements={"key": 10}, maximums={"key": 9})
        self.assertViolation("requirement", transition, self.rules(action), self.state(), action, Constraints())

    def test_maximum_prevents_repeated_rank_action_exceeding_ten(self):
        action = Action("rank_up", frozenset({0, 1}), effects={"score": 1}, maximums={"score": 9})
        output = plan(self.rules(action), self.state(score=9), [Goal("score", 11)], Constraints())
        self.assertEqual("proven_infeasible", output.status)
        self.assertEqual(Interval.exact(10), output.forecast.values["score"])

    def test_upfront_cost_cannot_be_funded_by_same_action_reward(self):
        action = Action("job", frozenset({0}), costs={"cash": 5}, effects={"cash": 10})
        self.assertViolation("requirement", transition, self.rules(action),
                             self.state(cash=0), action, Constraints())

    def test_costs_apply_before_deterministic_and_uncertain_effects(self):
        action = Action("job", frozenset({0}), costs={"cash": 3},
                        effects={"cash": 2}, uncertain_effects={"cash": Interval(0, 4)})
        output = transition(self.rules(action), self.state(cash=8), action, Constraints())
        self.assertEqual(Interval(7, 11), output.state.values["cash"])

    def test_uncertain_cash_cannot_guarantee_later_purchase(self):
        earn = Action("earn", frozenset({0}), uncertain_effects={"cash": Interval(0, 10)})
        buy = Action("buy", frozenset({1}), costs={"cash": 5}, effects={"score": 1})
        output = plan(self.rules(earn, buy), self.state(cash=0, score=0),
                      [Goal("score", 1)], Constraints())
        self.assertEqual("conditional", output.status)
        self.assertEqual(Interval(0, 5), output.forecast.values["cash"])
        self.assertTrue(any("cash" in reason for reason in output.explanations))

    def test_guaranteed_lower_reward_bound_can_fund_purchase(self):
        earn = Action("earn", frozenset({0}), uncertain_effects={"cash": Interval(5, 10)})
        buy = Action("buy", frozenset({1}), costs={"cash": 5}, effects={"score": 1})
        output = plan(self.rules(earn, buy), self.state(cash=0, score=0),
                      [Goal("score", 1)], Constraints())
        self.assertEqual("feasible_within_model", output.status)

    def test_unknown_goal_values_are_not_zero_or_proven_infeasible(self):
        output = plan(self.rules(horizon=1), self.state(), [Goal("score", 10)], Constraints())
        self.assertEqual("conditional", output.status)
        self.assertNotIn("score", output.forecast.values)

    def test_missing_field_rules_are_unsupported(self):
        output = plan(self.rules(horizon=1), self.state(score=0),
                      [Goal("unmodeled", 1)], Constraints())
        self.assertEqual("unsupported", output.status)

    def test_missing_action_rules_cannot_prove_infeasibility(self):
        action = Action("mystery", frozenset({0}), effects={"score": 1}, verification="unknown")
        output = plan(self.rules(action, horizon=1), self.state(score=0),
                      [Goal("score", 1)], Constraints())
        self.assertEqual("unsupported", output.status)

    def test_synthetic_actions_cannot_be_used_as_real_rules(self):
        action = Action("fixture", frozenset({0}), effects={"score": 1})
        output = plan(self.rules(action, horizon=1, synthetic=False), self.state(score=0),
                      [Goal("score", 1)], Constraints())
        self.assertEqual("unsupported", output.status)

    def test_verified_rule_requires_a_source(self):
        action = Action("unattributed", frozenset({0}), effects={"score": 1},
                        verification="verified", source="")
        self.assertViolation("unsupported_rule", transition, self.rules(action),
                             self.state(score=0), action, Constraints())

    def test_corroborated_source_status_preserved_and_partial_witness_conditional(self):
        action = Action("audited", frozenset({0}), effects={"score": 1},
                        verification="corroborated", source="test-evidence-only")
        rules = self.rules(action, horizon=1, coverage_complete=False, synthetic=False)
        output = plan(rules, self.state(score=0), [Goal("score", 1)], Constraints())
        self.assertEqual("conditional", output.status)
        self.assertEqual("corroborated", action.verification)

    def test_incomplete_coverage_is_conditional_even_for_witness(self):
        action = Action("gain", frozenset({0}), effects={"score": 1})
        output = plan(self.rules(action, horizon=1, coverage_complete=False),
                      self.state(score=0), [Goal("score", 1)], Constraints())
        self.assertEqual("conditional", output.status)

    def test_incomplete_coverage_failure_is_unknown(self):
        output = plan(self.rules(horizon=1, coverage_complete=False), self.state(score=0),
                      [Goal("score", 1)], Constraints())
        self.assertEqual("unknown", output.status)

    def test_exhaustive_supported_failure_can_be_proven_within_model(self):
        action = Action("gain", frozenset({0}), effects={"score": 1})
        output = plan(self.rules(action, horizon=1), self.state(score=0),
                      [Goal("score", 2)], Constraints(), beam_width=1)
        # All terminal alternatives are checked, so the last level is not pruning.
        self.assertEqual("proven_infeasible", output.status)

    def test_beam_pruning_failure_never_claims_infeasible(self):
        lure = Action("lure", frozenset({0}), effects={"score": 1})
        key = Action("key", frozenset({0}), effects={"key": 1})
        finish = Action("finish", frozenset({1}), requirements={"key": 1}, effects={"score": 3})
        rules = self.rules(lure, key, finish)
        state = self.state(score=0, key=0)
        narrow = plan(rules, state, [Goal("score", 3)], Constraints(), beam_width=1)
        wide = plan(rules, state, [Goal("score", 3)], Constraints(), beam_width=128)
        self.assertEqual("unknown", narrow.status)
        self.assertEqual("feasible_within_model", wide.status)

    def test_search_budget_exhaustion_has_distinct_status(self):
        a = Action("gain", frozenset({0, 1}), effects={"score": 1})
        for budget in (0, 1):
            output = plan(self.rules(a), self.state(score=0), [Goal("score", 2)],
                          Constraints(), max_expansions=budget)
            self.assertEqual("search_limit", output.status)
            self.assertLessEqual(output.expanded, budget)

    def test_already_achieved_goal_still_honors_future_fixed_actions(self):
        spend = Action("required", frozenset({1}), effects={"score": -1})
        output = plan(self.rules(spend, fixed_actions={1: "required"}), self.state(score=1),
                      [Goal("score", 1)], Constraints())
        self.assertEqual("proven_infeasible", output.status)
        self.assertEqual("required", output.steps[-1].action_id)

    def test_priority_prefers_important_goal_in_best_partial_plan(self):
        major = Action("major", frozenset({0}), effects={"key": 1})
        minor = Action("minor", frozenset({0}), effects={"score": 100})
        output = plan(self.rules(major, minor, horizon=1), self.state(key=0, score=0),
                      [Goal("key", 1, priority=9), Goal("score", 100, priority=1)], Constraints())
        self.assertEqual("major", output.steps[0].action_id)
        self.assertEqual("proven_infeasible", output.status)

    def test_pinned_and_fixed_actions_are_hard_constraints(self):
        a = Action("a", frozenset({0}), effects={"score": 1})
        b = Action("b", frozenset({0}), effects={"score": 99})
        rules = self.rules(a, b, horizon=1, fixed_actions={0: "a"})
        self.assertViolation("required_action", transition, rules,
                             self.state(score=0), b, Constraints())
        output = plan(rules, self.state(score=0), [Goal("score", 1)],
                      Constraints(pinned_actions={0: "b"}))
        self.assertEqual("proven_infeasible", output.status)

    def test_missing_pinned_action_is_unsupported(self):
        output = plan(self.rules(), self.state(score=0), [Goal("score", 1)],
                      Constraints(pinned_actions={0: "missing"}))
        self.assertEqual("unsupported", output.status)

    def test_dungeon_visits_have_no_implicit_cash_or_floor_rewards(self):
        action = Action("visit", frozenset({0}), dungeon_visit=True, target_floor=5)
        rules = self.rules(action, stages=(Stage("A", 0, 2, 10),), fields=("cash", "floor"))
        before = self.state(visits={"A": 0}, cash=7, floor=2)
        result = transition(rules, before, action, Constraints(visit_caps={"A": 1}))
        self.assertEqual(before.values, result.state.values)
        self.assertEqual({"A": 1}, result.state.visits)

    def test_old_floor_visit_counts_against_active_new_stage(self):
        visit = Action("old_floor", frozenset({1, 2}), dungeon_visit=True, target_floor=2)
        rules = self.rules(visit, horizon=3,
                           stages=(Stage("A", 0, 1, 5), Stage("B", 1, 3, 10)))
        before = self.state(slot=1, visits={"A": 1, "B": 0}, score=0)
        constraints = Constraints(visit_caps={"A": 1, "B": 1})
        first = transition(rules, before, visit, constraints).state
        self.assertEqual({"A": 1, "B": 1}, first.visits)
        self.assertViolation("visit_cap", transition, rules, first, visit, constraints)

    def test_observed_over_cap_history_is_retained_but_no_more_visits_allowed(self):
        visit = Action("visit", frozenset({0}), dungeon_visit=True)
        rules = self.rules(visit, horizon=1, stages=(Stage("A", 0, 1, 5),))
        state = self.state(visits={"A": 2}, score=1)
        constraints = Constraints(visit_caps={"A": 1})
        self.assertViolation("visit_cap", transition, rules, state, visit, constraints)
        output = plan(rules, state, [Goal("score", 1)], constraints)
        self.assertEqual("feasible_within_model", output.status)
        self.assertEqual({"A": 2}, output.forecast.visits)

    def test_zero_visit_cap_is_hard(self):
        visit = Action("visit", frozenset({0}), dungeon_visit=True, effects={"score": 1})
        rules = self.rules(visit, horizon=1, stages=(Stage("A", 0, 1, 5),))
        output = plan(rules, self.state(visits={"A": 0}, score=0), [Goal("score", 1)],
                      Constraints(visit_caps={"A": 0}))
        self.assertEqual("proven_infeasible", output.status)
        self.assertEqual({"A": 0}, output.forecast.visits)

    def test_floor_ceiling_and_stage_windows_are_enforced(self):
        visit = Action("visit", frozenset({0, 1}), dungeon_visit=True, target_floor=6)
        rules = self.rules(visit, stages=(Stage("A", 0, 1, 5), Stage("B", 1, 2, 10)))
        self.assertViolation("floor_locked", transition, rules, self.state(), visit, Constraints())
        output = transition(rules, self.state(slot=1, visits={"B": 0}), visit, Constraints())
        self.assertEqual({"B": 1}, output.state.visits)

    def test_missing_or_overlapping_stage_rules_are_unsupported(self):
        visit = Action("visit", frozenset({0}), dungeon_visit=True, effects={"score": 1})
        for stages in ((), (Stage("A", 0, 2, 5), Stage("B", 0, 2, 10))):
            output = plan(self.rules(visit, horizon=1, stages=stages), self.state(score=0),
                          [Goal("score", 1)], Constraints())
            self.assertEqual("unsupported", output.status)

    def test_unknown_stage_in_constraints_is_unsupported(self):
        output = plan(self.rules(), self.state(score=0), [Goal("score", 1)],
                      Constraints(visit_caps={"unknown": 1}))
        self.assertEqual("unsupported", output.status)

    def test_unknown_prior_visits_never_default_to_zero(self):
        visit = Action("visit", frozenset({0}), dungeon_visit=True, effects={"score": 1})
        rules = self.rules(visit, horizon=1, stages=(Stage("A", 0, 1, 5),))
        constraints = Constraints(visit_caps={"A": 1})
        for visits in ({}, {"A": None}):
            state = self.state(visits=visits, score=0)
            self.assertViolation("unknown_visit_count", transition, rules, state, visit, constraints)
            self.assertEqual("unknown", plan(rules, state, [Goal("score", 1)], constraints).status)
            self.assertIsNone(transition(rules, state, visit, Constraints()).state.visits["A"])

    def test_replay_rejects_duplicate_instance_and_wrong_slot(self):
        gain = Action("gain", frozenset({0, 1}), effects={"score": 1})
        rules = self.rules(gain)
        state = self.state(score=0)
        self.assertViolation("duplicate_instance", validate_plan, rules, state,
                             (Step("same", "gain", 0), Step("same", "gain", 1)), Constraints())
        self.assertViolation("wrong_slot", validate_plan, rules, state,
                             (Step("one", "gain", 1),), Constraints())

    def test_replay_rejects_unknown_action_and_forged_action_definition(self):
        gain = Action("gain", frozenset({0}), effects={"score": 1})
        rules = self.rules(gain)
        self.assertViolation("unsupported_action", validate_plan, rules, self.state(score=0),
                             (Step("one", "missing", 0),), Constraints())
        self.assertViolation("unsupported_action", transition, rules, self.state(score=0),
                             replace(gain, effects={"score": 999}), Constraints())

    def test_forged_wait_action_cannot_add_effects(self):
        self.assertViolation("unsupported_action", transition, self.rules(), self.state(score=0),
                             Action("__wait__", frozenset({0}), effects={"score": 999}), Constraints())

    def test_rules_version_mismatch_is_not_a_game_infeasibility(self):
        state = replace(self.state(score=0), rules_version="older")
        self.assertViolation("rules_version_mismatch", validate_plan, self.rules(), state, (), Constraints())
        self.assertEqual("unsupported", plan(self.rules(), state, [Goal("score", 1)], Constraints()).status)

    def test_plan_identity_binds_run_revision_and_request(self):
        gain = Action("gain", frozenset({0}), effects={"score": 1})
        rules = self.rules(gain, horizon=1)
        state = self.state(score=0)
        original = plan(rules, state, [Goal("score", 1)], Constraints())
        variants = (
            plan(rules, replace(state, revision=8), [Goal("score", 1)], Constraints()),
            plan(rules, replace(state, run_id="other"), [Goal("score", 1)], Constraints()),
            plan(rules, state, [Goal("score", 2)], Constraints()),
        )
        for changed in variants:
            self.assertNotEqual(original.plan_id, changed.plan_id)
            self.assertNotEqual(original.steps[0].instance_id, changed.steps[0].instance_id)

    def test_empty_horizon_uses_observed_goal_bounds(self):
        rules = self.rules(horizon=0)
        self.assertEqual("feasible_within_model", plan(rules, self.state(score=1), [Goal("score", 1)], Constraints()).status)
        self.assertEqual("proven_infeasible", plan(rules, self.state(score=0), [Goal("score", 1)], Constraints()).status)

    def test_invalid_budget_and_negative_caps_rejected(self):
        for kwargs in ({"beam_width": 0}, {"max_expansions": -1}):
            with self.assertRaises(ValueError):
                plan(self.rules(), self.state(), [], Constraints(), **kwargs)
        with self.assertRaises(ValueError):
            Constraints(visit_caps={"A": -1})

    def test_booleans_are_not_valid_step_slots(self):
        with self.assertRaises(ValueError):
            Step("id", "gain", True)

    def test_invalid_identifiers_rejected_before_hashing(self):
        for constructor in (lambda: Goal(5, 1), lambda: self.state(**{"": 1}),
                            lambda: Action(" ", frozenset({0})),
                            lambda: Constraints(visit_caps={"": 0})):
            with self.assertRaises(ValueError):
                constructor()

    def test_plan_id_encoding_is_unambiguous_for_colon_action_ids(self):
        actions = (
            Action("a:b", frozenset({0})), Action("a", frozenset({0})),
            Action("c", frozenset({1})), Action("b:c", frozenset({1})),
        )
        rules = self.rules(*actions)
        state = self.state(score=0)
        one = plan(rules, state, [], Constraints(pinned_actions={0: "a:b", 1: "c"}))
        two = plan(rules, state, [], Constraints(pinned_actions={0: "a", 1: "b:c"}))
        self.assertNotEqual(one.plan_id, two.plan_id)


if __name__ == "__main__":
    unittest.main()
