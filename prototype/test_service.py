"""Integration tests: synthetic mechanics plus explicitly limited audited adapter."""
import unittest
import tempfile
import shutil
from pathlib import Path
from state_store import StateStore
from planner_service import PlannerService
from real_rules_adapter import load_january_pack

class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.pack=load_january_pack(); self.rules=self.pack['rules']
        self.store=StateStore(stage_ceilings={s.id:s.floor_ceiling for s in self.rules.stages},
                              stage_windows={s.id:(s.start,s.end) for s in self.rules.stages})
        self.base=dict(rules_version=self.rules.version,game_slot=552,cash=1000,highest_floor=200,
                       active_stage='p3r.tower.stage_10',visits_by_stage={'p3r.tower.stage_10':0},
                       facts={'socialLinks.sun.rank':{'low':9,'high':9},'socialLinks.sun.normal':{'low':1,'high':1}})
        self.store.create_run('local',self.base)
        self.service=PlannerService(self.store,self.rules,self.pack['preflight'])
    def tearDown(self): self.store.close()
    def preferences(self,field='socialLinks.sun.rank'):
        s=self.store.get_snapshot('local')
        return self.store.set_preferences('local','prefs',s['revision'],goals=[{'field':field,'target':10}],constraints={'visit_caps':{'p3r.tower.stage_10':1}})
    def test_real_candidate_conditional_and_observation_unchanged(self):
        self.preferences(); before=self.store.get_snapshot('local')
        result=self.service.replan('local')['plan']
        self.assertEqual('conditional',result['status']); self.assertEqual(before,self.store.get_snapshot('local'))
        self.assertLessEqual(result['forecast']['values']['socialLinks.sun.rank']['high'],10)
    def test_unsupported_goal_not_impossible(self):
        self.preferences('socialLinks.lovers.rank')
        self.assertEqual('unsupported',self.service.replan('local')['plan']['status'])
    def test_unknown_rank_not_zero(self):
        self.base['facts']['socialLinks.sun.rank']={'low':None,'high':None}
        self.store.reenter('local','baseline2',0,self.base); self.preferences()
        self.assertEqual('unknown',self.service.replan('local')['plan']['status'])
    def test_return_replans_and_duplicate_is_one_visit(self):
        self.preferences(); revision=self.store.get_snapshot('local')['revision']
        args=dict(stage_id='p3r.tower.stage_10',highest_floor=210,absolute_balance=0)
        first=self.service.return_and_replan('local','return',revision,**args)
        again=self.service.return_and_replan('local','return',revision,**args)
        self.assertFalse(first['replan_pending']); self.assertTrue(again['observation']['duplicate'])
        state=self.store.get_snapshot('local'); self.assertEqual(0,state['cash']); self.assertEqual(1,state['visits_by_stage']['p3r.tower.stage_10'])
    def test_durable_return_without_preferences(self):
        result=self.service.return_and_replan('local','return',0,stage_id='p3r.tower.stage_10',highest_floor=210,net_cash=12)
        self.assertTrue(result['replan_pending']); self.assertEqual(1012,self.store.get_snapshot('local')['cash'])
    def test_changed_rules_requires_explicit_rebase(self):
        self.base['rules_version']='old'; self.store.reenter('local','older',0,self.base)
        self.assertEqual('unsupported',self.service.replan('local')['plan']['status']); self.assertIsNone(self.store.get_plan('local'))
    def test_tampered_rule_pack_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source=Path(__file__).parent/"rules"/"p3r"
            shutil.copytree(source,Path(directory)/"rules")
            target=Path(directory)/"rules"/"playable_actions.json"
            target.write_text(target.read_text()+" ")
            with self.assertRaisesRegex(ValueError,"checksum"):
                load_january_pack(Path(directory)/"rules")

    def test_no_dungeon_income_or_floor_forecast(self):
        action=next(a for a in self.rules.actions if a.dungeon_visit)
        self.assertFalse(action.effects); self.assertFalse(action.uncertain_effects); self.assertIsNone(action.target_floor)

if __name__=='__main__': unittest.main()
