"""Revision-safe orchestration; never applies a forecast to observed player state."""
from dataclasses import asdict
from planner_core import Interval, Snapshot, Goal, Constraints, plan, validate_plan
from state_store import StateConflict, InvalidObservation


def core_snapshot(snapshot, rules):
    values = {key: Interval(value['low'], value['high'])
              for key,value in snapshot['facts'].items() if key in rules.fields}
    if 'cash' in rules.fields:
        cash = snapshot['cash']
        values['cash'] = Interval.unknown() if cash is None else Interval.exact(cash)
    return Snapshot(snapshot['run_id'], snapshot['rules_version'], snapshot['revision'],
                    snapshot['game_slot'], values, snapshot['visits_by_stage'])

class PlannerService:
    def __init__(self, store, rules, preflight=None):
        self.store, self.rules, self.preflight = store, rules, preflight

    def replan(self, run_id, *, beam_width=128, max_expansions=20000):
        observed = self.store.get_snapshot(run_id)
        if observed['rules_version'] != self.rules.version:
            return {'plan_revision':None,'plan':{'status':'unsupported','steps':[],
                    'explanations':['Ruleset changed; explicitly rebase the observed state before planning.']}}
        preferences = self.store.get_preferences(run_id)
        if preferences is None:
            raise InvalidObservation('Select goals and constraints before planning')
        goals = [Goal(**goal) for goal in preferences['goals']]
        raw = preferences['constraints']
        constraints = Constraints(raw.get('visit_caps',{}),
                                  {int(k):v for k,v in raw.get('pinned_actions',{}).items()})
        if self.preflight:
            issue = self.preflight(observed, goals)
            if issue:
                result = {'run_id':run_id, 'rules_version':self.rules.version,
                          'state_revision':observed['revision'], 'status':issue[0],
                          'steps':[], 'explanations':[issue[1]], 'model_scope':'partial_real_rules'}
                installed = self.store.install_plan(run_id,observed['revision'],self.rules.version,result)
                return {'plan_revision':installed,'plan':result}
        initial = core_snapshot(observed,self.rules)
        result = plan(self.rules,initial,goals,constraints,beam_width,max_expansions)
        replayed = validate_plan(self.rules,initial,result.steps,constraints)
        if replayed != result.forecast:
            raise AssertionError('Independent replay disagrees with forecast')
        payload = asdict(result)
        payload['model_scope'] = 'synthetic_fixture' if self.rules.synthetic else 'partial_real_rules'
        payload['optimality_proven'] = False
        payload['observed_state_mutated'] = False
        installed = self.store.install_plan(run_id,observed['revision'],self.rules.version,payload)
        return {'plan_revision':installed,'plan':payload}

    def return_and_replan(self, run_id, command_id, expected_revision, **observation):
        receipt = self.store.record_dungeon_return(run_id,command_id,expected_revision,**observation)
        # A successful observed return is durable even if a newer update cancels this search.
        try:
            installed = self.replan(run_id)
            return {'observation':asdict(receipt),'planning':installed,'replan_pending':False}
        except (StateConflict, InvalidObservation, ValueError) as error:
            return {'observation':asdict(receipt),'planning':None,'replan_pending':True,'reason':str(error)}
