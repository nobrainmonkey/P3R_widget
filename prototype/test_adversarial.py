"""Independent state-contract tests. Synthetic fixtures are NOT actual P3R rules."""
import copy
import os
import tempfile
import unittest

from state_store import StateStore, StateConflict, InvalidObservation


def baseline(**changes):
    value = {
        'rules_version': 'synthetic-test-only', 'game_slot': 100,
        'cash': 1000, 'highest_floor': 10, 'active_stage': 'stage_a',
        'visits_by_stage': {'stage_a': 1},
        'facts': {'academics': {'low': 5, 'high': 8}},
        'legacy_guide_backup': {'completed': ['legacy-id-1']},
    }
    value.update(changes)
    return value


class StateAdversarialTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, 'state.sqlite')
        self.store = StateStore(self.path, stage_ceilings={'stage_a': 22, 'stage_b': 43})
        self.initial = self.store.create_run('run', baseline())

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def snap(self):
        return self.store.get_snapshot('run')

    def record(self, operation='return-1', revision=None, **kwargs):
        if revision is None:
            revision = self.snap()['revision']
        args = dict(stage_id='stage_a', highest_floor=20, net_cash=500)
        if 'absolute_balance' in kwargs:
            args.pop('net_cash')
        args.update(kwargs)
        return self.store.record_dungeon_return('run', operation, revision, **args)

    def assertUnchangedAfterFailure(self, exception, call):
        previous = copy.deepcopy(self.snap())
        with self.assertRaises(exception):
            call()
        self.assertEqual(previous, self.snap(), 'invalid command partially mutated observed state')

    def test_return_retry_after_restart_is_idempotent(self):
        revision = self.initial['revision']
        first = self.record(revision=revision)
        self.store.close()
        self.store = StateStore(self.path, stage_ceilings={'stage_a': 22, 'stage_b': 43})
        again = self.record(revision=revision)
        self.assertTrue(again.duplicate)
        self.assertEqual(first.snapshot, again.snapshot)
        self.assertEqual(1500, self.snap()['cash'])
        self.assertEqual(2, self.snap()['visits_by_stage']['stage_a'])

    def test_same_operation_different_payload_cannot_apply(self):
        revision = self.initial['revision']
        self.record(revision=revision)
        self.assertUnchangedAfterFailure((StateConflict, InvalidObservation),
            lambda: self.record(revision=revision, net_cash=501))

    def test_stale_new_operation_rejected_atomically(self):
        revision = self.initial['revision']
        self.record(revision=revision)
        self.assertUnchangedAfterFailure(StateConflict,
            lambda: self.record('new-stale-operation', revision=revision))

    def test_absolute_zero_and_negative_delta(self):
        self.record(absolute_balance=0)
        self.assertEqual(0, self.snap()['cash'])
        self.assertUnchangedAfterFailure(InvalidObservation,
            lambda: self.record('negative-balance', net_cash=-1))
        self.record('funds', absolute_balance=100)
        self.record('spent', net_cash=-100)
        self.assertEqual(0, self.snap()['cash'])

    def test_cash_xor_and_missing_cash(self):
        self.assertUnchangedAfterFailure(InvalidObservation,
            lambda: self.record(absolute_balance=2000, net_cash=1000))
        self.assertUnchangedAfterFailure(InvalidObservation,
            lambda: self.record(net_cash=None))

    def test_unknown_cash_requires_absolute_and_visits_stay_unknown(self):
        self.store.reenter('run', 'checkpoint-unknown', self.snap()['revision'],
            baseline(cash=None, visits_by_stage={'stage_a': None}))
        self.assertUnchangedAfterFailure(InvalidObservation, lambda: self.record())
        self.record(absolute_balance=0)
        self.assertEqual(0, self.snap()['cash'])
        self.assertIsNone(self.snap()['visits_by_stage']['stage_a'])
        self.assertEqual(1, self.snap()['visits_since_baseline']['stage_a'])

    def test_illegal_floor_does_not_credit_cash(self):
        for floor in [-1, 23, 1.5, True]:
            with self.subTest(floor=floor):
                self.assertUnchangedAfterFailure(InvalidObservation,
                    lambda floor=floor: self.record(highest_floor=floor))

    def test_normal_return_cannot_lower_highest_floor(self):
        self.record(highest_floor=20)
        old = self.snap()
        try:
            self.record('revisit-old-floor', highest_floor=5)
        except InvalidObservation:
            self.assertEqual(old, self.snap())
        else:
            self.assertEqual(20, self.snap()['highest_floor'])

    def test_wrong_stage_cannot_shift_visit_bucket(self):
        self.assertUnchangedAfterFailure(InvalidObservation,
            lambda: self.record(stage_id='stage_b', highest_floor=30))

    def test_noninteger_cash_rejected(self):
        for amount in [1.25, True, '100', float('inf'), float('nan')]:
            with self.subTest(amount=amount):
                self.assertUnchangedAfterFailure(InvalidObservation,
                    lambda amount=amount: self.record(net_cash=amount))

    def test_plan_install_nonmutating_and_observation_invalidates(self):
        observed = copy.deepcopy(self.snap())
        self.store.install_plan('run', observed['revision'], 'synthetic-test-only',
            {'status': 'conditional', 'steps': []})
        self.assertEqual(observed, self.snap())
        self.assertIsNotNone(self.store.get_plan('run'))
        self.record()
        self.assertIsNone(self.store.get_plan('run'))
        self.assertUnchangedAfterFailure(StateConflict,
            lambda: self.store.install_plan('run', observed['revision'],
                'synthetic-test-only', {'steps': []}))

    def test_reentry_is_new_authoritative_baseline_and_invalidates_plan(self):
        self.record()
        self.store.install_plan('run', self.snap()['revision'], 'synthetic-test-only', {'steps': []})
        old = self.snap()
        new = baseline(cash=75, highest_floor=2, visits_by_stage={'stage_a': 0})
        receipt = self.store.reenter('run', 'loaded-earlier-save', old['revision'], new)
        self.assertGreater(receipt.snapshot['epoch'], old['epoch'])
        self.assertEqual(75, self.snap()['cash'])
        self.assertEqual(2, self.snap()['highest_floor'])
        self.assertEqual(0, self.snap()['visits_by_stage']['stage_a'])
        self.assertIsNone(self.store.get_plan('run'))
        self.assertEqual(new['legacy_guide_backup'], self.snap()['legacy_guide_backup'])

    def test_correction_replaces_return_does_not_add_visit(self):
        self.record('original', net_cash=500)
        self.record('later', net_cash=200, highest_floor=22)
        used = self.snap()['visits_by_stage']['stage_a']
        self.record('correction', corrects_event_id='original', net_cash=100, highest_floor=18)
        self.assertEqual(1300, self.snap()['cash'])
        self.assertEqual(used, self.snap()['visits_by_stage']['stage_a'])
        self.assertEqual(22, self.snap()['highest_floor'])

    def test_correction_cannot_cross_checkpoint_epoch(self):
        self.record('old-return')
        self.store.reenter('run', 'new-checkpoint', self.snap()['revision'], baseline())
        self.assertUnchangedAfterFailure((StateConflict, InvalidObservation),
            lambda: self.record('illegal-correction', corrects_event_id='old-return'))

    def test_absolute_later_balance_survives_earlier_correction(self):
        self.record('original', net_cash=500)
        self.record('later-absolute', absolute_balance=2000, highest_floor=22)
        self.record('correction', corrects_event_id='original', net_cash=100, highest_floor=18)
        self.assertEqual(2000, self.snap()['cash'])
        self.assertEqual(3, self.snap()['visits_by_stage']['stage_a'])


    def test_invalid_correction_rolls_back_event_and_cash(self):
        self.record('original', net_cash=500)
        self.record('spent', net_cash=-1400)
        self.assertUnchangedAfterFailure(InvalidObservation,
            lambda: self.record('bad-correction', corrects_event_id='original', net_cash=0))
        self.record('good-correction', corrects_event_id='original', net_cash=600)
        self.assertEqual(200, self.snap()['cash'])
        self.assertEqual(3, self.snap()['visits_by_stage']['stage_a'])

    def test_reentry_retry_and_corrupt_checkpoint_atomicity(self):
        revision = self.snap()['revision']
        value = baseline(cash=17)
        receipt = self.store.reenter('run', 'checkpoint', revision, value)
        again = self.store.reenter('run', 'checkpoint', revision, value)
        self.assertTrue(again.duplicate)
        self.assertEqual(receipt.snapshot, again.snapshot)
        for invalid in [baseline(cash=-1), baseline(facts={'stat': {'low': 4, 'high': 2}}),
                        baseline(visits_by_stage={'stage_a': -1})]:
            self.assertUnchangedAfterFailure(InvalidObservation,
                lambda invalid=invalid: self.store.reenter('run', 'bad-checkpoint',
                    self.snap()['revision'], invalid))

    def test_stale_rules_plan_is_not_installed(self):
        self.assertUnchangedAfterFailure(StateConflict,
            lambda: self.store.install_plan('run', self.snap()['revision'],
                'different-rules', {'steps': []}))
        self.assertIsNone(self.store.get_plan('run'))

    def test_snapshots_do_not_alias_internal_observed_state(self):
        detached = self.snap()
        detached['cash'] = 999999
        detached['visits_by_stage']['stage_a'] = 0
        self.assertEqual(1000, self.snap()['cash'])
        self.assertEqual(1, self.snap()['visits_by_stage']['stage_a'])


    def test_captured_visit_slot_rejects_regenerated_submission_id(self):
        self.record('original', visit_slot=100, next_game_slot=101)
        self.assertEqual(101, self.snap()['game_slot'])
        self.assertUnchangedAfterFailure((StateConflict, InvalidObservation),
            lambda: self.record('accidental-new-id', visit_slot=100, next_game_slot=101))

    def test_return_correction_does_not_advance_time_again(self):
        self.record('first')
        self.record('second')
        slot = self.snap()['game_slot']
        self.assertEqual(102, slot)
        self.record('correction', corrects_event_id='first', net_cash=100)
        self.assertEqual(slot, self.snap()['game_slot'])


    def test_preferences_change_invalidates_old_async_plan(self):
        revision = self.snap()['revision']
        self.store.install_plan('run', revision, 'synthetic-test-only', {'steps': []})
        self.store.set_preferences('run', 'goals-v1', revision,
            goals=[{'field': 'sun_rank', 'target': 10, 'priority': 1}],
            constraints={'visit_caps': {'stage_a': 1}})
        self.assertGreater(self.snap()['revision'], revision)
        self.assertIsNone(self.store.get_plan('run'))
        self.assertUnchangedAfterFailure(StateConflict,
            lambda: self.store.install_plan('run', revision, 'synthetic-test-only', {'steps': []}))
        before_change = self.snap()['revision']
        self.store.set_preferences('run', 'caps-v2', before_change,
            goals=[{'field': 'sun_rank', 'target': 10, 'priority': 1}],
            constraints={'visit_caps': {'stage_a': 0}})
        self.assertUnchangedAfterFailure(StateConflict,
            lambda: self.store.install_plan('run', before_change, 'synthetic-test-only', {'steps': []}))

    def test_preferences_retry_persistence_and_return_replay(self):
        revision = self.snap()['revision']
        arguments = dict(goals=[{'field': 'rank', 'target': 10}],
                         constraints={'visit_caps': {'stage_a': 0}})
        original = self.store.set_preferences('run', 'settings', revision, **arguments)
        retry = self.store.set_preferences('run', 'settings', revision, **arguments)
        self.assertTrue(retry.duplicate)
        self.assertEqual(original.snapshot, retry.snapshot)
        self.store.close()
        self.store = StateStore(self.path, stage_ceilings={'stage_a': 22})
        self.assertEqual(arguments, self.store.get_preferences('run'))
        self.record()
        self.assertEqual(arguments, self.store.get_preferences('run'))
        self.assertEqual(arguments, self.store.export_run('run')['preferences'])

    def test_stage_boundary_return_charges_entry_stage_then_advances(self):
        self.store.close()
        self.store = StateStore(self.path, stage_ceilings={'stage_a': 22, 'stage_b': 43},
            stage_windows={'stage_a': (0, 101), 'stage_b': (101, 200)})
        self.record('boundary', visit_slot=100, next_game_slot=101)
        current = self.snap()
        self.assertEqual('stage_b', current['active_stage'])
        self.assertEqual(2, current['visits_by_stage']['stage_a'])
        self.assertEqual(1, current['visits_since_baseline']['stage_a'])
        self.assertNotIn('stage_b', current['visits_since_baseline'])


class CoreAdversarialTest(unittest.TestCase):
    """All fixtures below are intentionally synthetic, never game recommendations."""
    def setUp(self):
        from planner_core import (Interval, Snapshot, Stage, Action, Ruleset,
                                  Constraints, Goal, transition, plan, RuleViolation)
        self.I, self.S, self.Stage, self.A, self.R = Interval, Snapshot, Stage, Action, Ruleset
        self.C, self.G = Constraints, Goal
        self.transition, self.plan, self.Error = transition, plan, RuleViolation

    def state(self, values=None, visits=None, slot=0):
        return self.S('run', 'synthetic', 7, slot,
            values or {'rank': self.I.exact(0), 'cash': self.I.exact(0)},
            visits if visits is not None else {'stage_a': 0})

    def rules(self, actions=(), horizon=2, **kwargs):
        return self.R('synthetic', horizon, frozenset({'rank', 'cash'}), tuple(actions),
                      **kwargs)

    def test_capped_visits_never_exceeded_even_by_old_floor_target(self):
        dungeon = self.A('dungeon', frozenset({0, 1}), effects={'rank': 1},
                         dungeon_visit=True, target_floor=2)
        rules = self.rules([dungeon], stages=(self.Stage('stage_a', 0, 2, 22),))
        cap = self.C(visit_caps={'stage_a': 1})
        current = self.state(visits={'stage_a': 1})
        with self.assertRaises(self.Error) as rejected:
            self.transition(rules, current, dungeon, cap)
        self.assertEqual('visit_cap', rejected.exception.code)
        result = self.plan(rules, current, [self.G('rank', 1)], cap)
        self.assertFalse(any(step.action_id == 'dungeon' for step in result.steps))
        self.assertEqual({'stage_a': 1}, result.forecast.visits)

    def test_unknown_visit_count_does_not_default_to_zero(self):
        dungeon = self.A('dungeon', frozenset({0}), dungeon_visit=True)
        rules = self.rules([dungeon], horizon=1,
            stages=(self.Stage('stage_a', 0, 1, 22),))
        with self.assertRaises(self.Error):
            self.transition(rules, self.state(visits={}), dungeon,
                self.C(visit_caps={'stage_a': 1}))

    def test_observed_overcap_history_is_not_rewritten(self):
        dungeon = self.A('dungeon', frozenset({0}), dungeon_visit=True)
        rules = self.rules([dungeon], horizon=1,
            stages=(self.Stage('stage_a', 0, 1, 22),))
        result = self.plan(rules, self.state(visits={'stage_a': 3}), [],
            self.C(visit_caps={'stage_a': 1}))
        self.assertEqual(3, result.forecast.visits['stage_a'])
        self.assertFalse(any(step.action_id == 'dungeon' for step in result.steps))

    def test_search_never_mutates_observed_state_or_revision(self):
        action = self.A('rank_up', frozenset({0, 1}), effects={'rank': 1})
        current = self.state()
        before = copy.deepcopy(current)
        result = self.plan(self.rules([action]), current, [self.G('rank', 2)], self.C())
        self.assertEqual(before, current)
        self.assertEqual(7, result.state_revision)
        self.assertEqual(7, result.forecast.revision)
        self.assertEqual('feasible_within_model', result.status)

    def test_unknown_cash_paid_action_is_conditional(self):
        action = self.A('paid', frozenset({0}), effects={'rank': 1}, costs={'cash': 10})
        current = self.state(values={'rank': self.I.exact(0), 'cash': self.I.unknown()})
        result = self.plan(self.rules([action], horizon=1), current,
            [self.G('rank', 1)], self.C())
        self.assertEqual('conditional', result.status)
        self.assertTrue(any('cash' in reason for reason in result.explanations))
        self.assertEqual(self.I.unknown(), current.values['cash'])

    def test_upfront_cost_cannot_use_own_reward(self):
        action = self.A('self_funding', frozenset({0}), costs={'cash': 10},
                        effects={'cash': 20, 'rank': 1})
        rules = self.rules([action], horizon=1)
        with self.assertRaises(self.Error):
            self.transition(rules, self.state(), action, self.C())

    def test_search_limit_and_missing_rules_not_proven_impossible(self):
        result = self.plan(self.rules(), self.state(), [self.G('rank', 1)],
            self.C(), max_expansions=0)
        self.assertEqual('search_limit', result.status)
        result = self.plan(self.rules(coverage_complete=False), self.state(),
            [self.G('rank', 1)], self.C())
        self.assertNotEqual('proven_infeasible', result.status)
        result = self.plan(self.rules(), self.state(), [self.G('unmodeled', 1)], self.C())
        self.assertEqual('unsupported', result.status)

    def test_uncertain_gain_not_guaranteed_and_not_infeasible(self):
        action = self.A('random', frozenset({0}), uncertain_effects={'rank': self.I(0, 1)})
        result = self.plan(self.rules([action], horizon=1), self.state(),
            [self.G('rank', 1)], self.C())
        self.assertEqual('conditional', result.status)

    def test_synthetic_action_cannot_be_real_game_witness(self):
        action = self.A('fake_rank', frozenset({0}), effects={'rank': 1})
        result = self.plan(self.rules([action], horizon=1, synthetic=False), self.state(),
            [self.G('rank', 1)], self.C())
        self.assertEqual('unsupported', result.status)

    def test_stage_boundary_charges_new_stage(self):
        action = self.A('dungeon', frozenset({1}), dungeon_visit=True, target_floor=1)
        rules = self.rules([action], stages=(self.Stage('stage_a', 0, 1, 22),
                                            self.Stage('stage_b', 1, 2, 43)))
        current = self.state(slot=1, visits={'stage_a': 1, 'stage_b': 0})
        result = self.transition(rules, current, action,
            self.C(visit_caps={'stage_a': 1, 'stage_b': 1}))
        self.assertEqual({'stage_a': 1, 'stage_b': 1}, result.state.visits)


    def test_beam_pruning_failure_is_not_infeasibility_proof(self):
        a = self.A('a_gain_rank', frozenset({0}), effects={'rank': 1})
        b = self.A('b_save_cash', frozenset({0}), effects={'cash': 10})
        c = self.A('c_unlock', frozenset({1}), costs={'cash': 10}, effects={'rank': 2})
        result = self.plan(self.rules([a, b, c]), self.state(),
            [self.G('rank', 2)], self.C(), beam_width=1)
        self.assertEqual('unknown', result.status)

    def test_higher_priority_goal_wins_single_slot_competition(self):
        rank = self.A('rank', frozenset({0}), effects={'rank': 1})
        cash = self.A('cash', frozenset({0}), effects={'cash': 10})
        result = self.plan(self.rules([rank, cash], horizon=1), self.state(),
            [self.G('rank', 1, priority=9), self.G('cash', 10, priority=0)], self.C())
        self.assertEqual('rank', result.steps[0].action_id)
        self.assertEqual('proven_infeasible', result.status)

    def test_floor_lock_and_duplicate_instance_validator(self):
        from planner_core import Step, validate_plan
        action = self.A('locked', frozenset({0}), dungeon_visit=True, target_floor=23)
        rules = self.rules([action], stages=(self.Stage('stage_a', 0, 2, 22),))
        with self.assertRaises(self.Error) as rejected:
            self.transition(rules, self.state(), action, self.C())
        self.assertEqual('floor_locked', rejected.exception.code)
        with self.assertRaises(self.Error) as rejected:
            validate_plan(rules, self.state(),
                [Step('same', '__wait__', 0), Step('same', '__wait__', 1)], self.C())
        self.assertEqual('duplicate_instance', rejected.exception.code)


    def test_explicit_unknown_visits_remain_unknown_and_block_cap(self):
        dungeon = self.A('dungeon', frozenset({0}), effects={'rank': 1}, dungeon_visit=True)
        rules = self.rules([dungeon], horizon=1,
            stages=(self.Stage('stage_a', 0, 1, 22),))
        current = self.state(visits={'stage_a': None})
        result = self.plan(rules, current, [self.G('rank', 1)],
            self.C(visit_caps={'stage_a': 1}))
        self.assertEqual('unknown', result.status)
        self.assertFalse(any(step.action_id == 'dungeon' for step in result.steps))
        uncapped = self.transition(rules, current, dungeon, self.C())
        self.assertIsNone(uncapped.state.visits['stage_a'])


if __name__ == '__main__':
    unittest.main()
