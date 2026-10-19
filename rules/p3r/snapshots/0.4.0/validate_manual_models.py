import json,pathlib
P=pathlib.Path(__file__).parent;R=lambda f:json.loads((P/f).read_text());d=R('manual_link_models.json');checks={};n=0;tables=0;gates=0
checks['17_unique_entities']=len(d['entities'])==len({e['entity_id'] for e in d['entities']})==17
for e in d['entities']:
 slug=e['entity_id'].split('.')[-1];gs=e['incoming_thresholds'];gates+=len(gs)
 assert {g['to_rank'] for g in gs}==set(range(2,11))
 for g in gs:
  if g['exact_raw_threshold'] is None:assert not g['executable_sufficiency_check']
 for v in e['rank_events']:
  tables+=1;nodes={p['prompt_ordinal']:p for p in v['prompts']}
  def calc(k,seen=()):
   if k is None:return [0]
   assert k in nodes and k not in seen
   out=[]
   for o in nodes[k]['options']:
    assert o['localized_text'] is None
    assert isinstance(o['raw_affinity_points'],int) and o['raw_affinity_points']>=0
    if any(f.get('field')=='relationship_reversed' and f.get('value') for f in o['effects']):continue
    out.extend(o['raw_affinity_points']+x for x in calc(o['next_prompt_ordinal'],seen+(k,)))
   return out
  vals=calc(min(nodes)) if nodes else [0]
  assert max(vals)==v['optimal_base_gain'],v['rule_id']
  n+=sum(len(p['options']) for p in v['prompts'])
 checks[slug+'_gates_graphs_points']=True
checks.update({'153_gates':gates==153,'168_branch_tables':tables==168,'897_raw_answer_values':n==897})
c=R('calendar_execution_extension.json');checks['all19_manual_calendar_profiles']=len(c['profiles'])==19;checks['1352_unique_calendar_cells']=len(c['records'])==len({x['sourceRuleId'] for x in c['records']})==1352
checks['all_models_have_calendar_profile']=all(e['entity_id'].split('.')[-1] in c['profiles'] for e in d['entities'])
checks['slot_index_consistent']=all(x['slotIndex']==x['date']['ordinal']*2+(1 if x['slot']=='evening' else 0) for x in c['records'])
checks['only_relevant_rescue_guards']=all(x['entityId'].endswith(('hierophant','hanged_man')) for x in c['records'] if 'person_confirmed_available_after_rescue_window' in x['additionalGuardIds'])
r={'allPassed':all(checks.values()),'checks':checks,'scope':'All normalized graphs and point arithmetic checked, not full-game plan feasibility or Android integration.'};(P/'validation_manual_models.json').write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n');print(json.dumps(r,indent=2));assert r['allPassed']
