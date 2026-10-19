import json,pathlib
P=pathlib.Path(__file__).parent;d=json.loads((P/'romance_base_models_candidate.json').read_text());checks={};events={};entity_gates={}
for e in d['entities']:
 slug=e['entity_id'].split('.')[-1];gates={g['to_rank']:g for g in e['incoming_thresholds']};entity_gates[slug]=gates
 checks[slug+'_nine_gates']=set(gates)==set(range(2,11));checks[slug+'_all_ranks']=set(v['to_rank'] for v in e['rank_events'])==set(range(2,11))
 for v in e['rank_events']:
  assert v['rule_id'] not in events;events[v['rule_id']]=(slug,v)
  nodes={p['prompt_ordinal']:p for p in v['prompts']}
  def paths(n,seen=()):
   if n is None:return [0]
   assert n in nodes and n not in seen
   values=[]
   for o in nodes[n]['options']:
    assert o['localized_text'] is None
    if any(f.get('field')=='relationship_reversed' and f.get('value') for f in o['effects']):continue
    for rest in paths(o['next_prompt_ordinal'],seen+(n,)):values.append(o['raw_affinity_points']+rest)
   return values
  values=paths(min(nodes)) if nodes else [0]
  assert [min(values),max(values)]==v['base_points_path_range_nonreversed'],v['rule_id']
  assert max(values)==v['optimal_base_gain'],v['rule_id']
 checks[slug+'_acyclic_paths_and_gains']=True
passed=0
for f in d['test_fixtures']:
 if 'expected_optimal_base_gain' not in f:continue
 slug,v=events[f['event_rule_id']];gain=v['optimal_base_gain'];assert gain==f['expected_optimal_base_gain']
 if v['to_rank']==10:ready=None
 else:
  g=entity_gates[slug][v['to_rank']+1];threshold=g['exact_raw_threshold'];ready=True if g['executable_sufficiency_check'] and threshold is not None and gain>=threshold else None
 if 'expected_next_ready_default_mode' in f:assert ready==f['expected_next_ready_default_mode'],f['id']
 passed+=1
checks['independent_optimal_path_readiness_fixtures']=passed>=54
checks['441_answers']=sum(len(p['options']) for _,v in events.values() for p in v['prompts'])==441
checks['69_branch_tables']=len(events)==69
lg=entity_gates['lovers'][6];checks['conflicting_gate_default_off']=lg['exact_raw_threshold'] is None and not lg['executable_sufficiency_check'] and lg['requires_opt_in_source_envelope_mode']
r={'allPassed':all(checks.values()),'checks':checks,'independentlyReevaluatedPathReadinessFixtures':passed,'scope':'All graph maxima and source-point arithmetic independently reevaluated; no game replay or native integration claim.'};(P/'validation_romance.json').write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n');print(json.dumps(r,indent=2));assert r['allPassed']
