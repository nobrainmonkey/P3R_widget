import unittest
from local_app import Application

class LocalAppTest(unittest.TestCase):
    def setUp(self): self.app=Application(':memory:')
    def tearDown(self): self.app.store.close()
    def initial(self):
        return {'command_id':'start','expected_revision':None,'baseline':{
            'rules_version':self.app.pack['rules'].version,'game_slot':552,'cash':None,
            'highest_floor':None,'active_stage':'p3r.tower.stage_10',
            'visits_by_stage':{'p3r.tower.stage_10':None},'facts':{}}}
    def test_create_retry_preserves_single_checkpoint(self):
        payload=self.initial(); first=self.app.command('/api/baseline',payload)
        again=self.app.command('/api/baseline',payload)
        self.assertTrue(again['observation']['duplicate'])
        self.assertEqual(first['observation']['snapshot'],again['observation']['snapshot'])
    def test_reentry_keeps_preferences_but_invalidates_plan(self):
        self.app.command('/api/baseline',self.initial()); revision=self.app.state()['snapshot']['revision']
        self.app.command('/api/preferences',dict(command_id='goals',expected_revision=revision,
            goals=[dict(field='socialLinks.sun.rank',target=10)],constraints={'visit_caps':{'p3r.tower.stage_10':1}}))
        payload=self.initial();payload.update(command_id='return-to-app',expected_revision=self.app.state()['snapshot']['revision'])
        payload['baseline']['cash']=123
        self.app.command('/api/baseline',payload)
        self.assertEqual(123,self.app.state()['snapshot']['cash']);self.assertIsNone(self.app.store.get_plan('local'))
        self.assertEqual(10,self.app.state()['preferences']['goals'][0]['target'])
if __name__=='__main__': unittest.main()
