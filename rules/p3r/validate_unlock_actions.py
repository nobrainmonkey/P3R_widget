import json,pathlib,datetime
P=pathlib.Path(__file__).parent;R=lambda f:json.loads((P/f).read_text());s=R('simple_unlock_rules_candidate.json');m=R('multistep_unlock_rules_candidate.json');story=R('story_link_transitions_candidate.json');stats=R('stat_activity_expansion_candidate.json');hang=R('ordinary_hangout_rules_candidate.json');aff=R('affinity_actions_candidate.json');stage=R('stage_identification_contract.json');checks={}
ids=[a['entityId'] for a in s['actions']]+[a['entityId'] for a in m['models']]+['p3r.social_link.magician'];checks['19_unique_manual_unlock_coverage']=len(ids)==len(set(ids))==19
checks['simple_11_one_slot_actions']=len(s['actions'])==11 and all(a['slotCost']==1 for a in s['actions'])
checks['simple_dates_roundtrip']=all((datetime.date(2009,4,1)+datetime.timedelta(days=a['earliestDate']['ordinal'])).strftime('%m-%d')==a['earliestDate']['mmdd'] for a in s['actions'] if a['earliestDate'])
models={a['entityId'].split('.')[-1]:a for a in m['models']};checks['justice_costs_0001']=[x['slotCost'] for x in models['justice']['transitions']]==[0,0,0,1]
dev=models['devil']['transitions'];checks['devil_three_payments_40k']=sum(x['moneyCostJpy'] for x in dev)==40000;checks['devil_slot_costs001']=[x['slotCost'] for x in dev]==[0,0,1]
checks['all_chain_steps_require_success']=all(t['requiresConfirmedInteractionSuccess'] for e in m['models'] for t in e['transitions'])
checks['current_baseline_no_history_required']=m['currentBaselinePolicy']['noHistoryRequired']
checks['story25_no_extra_slots']=len(story['transitions'])==25 and all(t['additionalOptionalSlotsConsumed']==0 for t in story['transitions'])
checks['story_flags_not_dates']=all(not t['trigger'].get('dateAloneSufficient',False) for t in story['transitions'])
checks['12_stat_expansion_actions']=len(stats['actions'])==12
checks['ordinary_raw_gain_not_invented']=hang['decision']['ordinary_numeric_effect_enabled']==False and hang['ordinary_hangout'].get('base_affinity_gain') is None
checks['two_dating_actions_no_same_slot_rankup']=len(aff['actions'])==2 and all(x['rankDelta']==0 and x['slotCost']==1 for x in aff['actions'])
checks['owned_note_not_double_charged']=aff['actions'][0]['yenCost']==0 and aff['actions'][1]['yenCost']==3000
checks['dating_conservative_date_roundtrip']=(datetime.date(2009,4,1)+datetime.timedelta(days=aff['actions'][1]['earliestConservativeDate']['ordinal'])).strftime('%m-%d')=='11-16'
checks['stage_identification_independent_from_permission']=sum(x['identifyFromDateEnabled'] for x in stage['rules'])==10 and all(not x['nightlyExplorationPermissionGranted'] for x in stage['rules'])
checks['contested_date_stages_keep_verified_ceilings']=all(x['floorBoundsVerified'] for x in stage['rules'] if x['stageId'].endswith(('_02','_03','_08','_09')))
checks['july18_not_july9_operational_start']=next(x for x in stage['rules'] if x['stageId'].endswith('_04'))['startInclusive']['mmdd']=='07-18'
checks['three_ambiguous_intervals_keep_counts']=len(stage['ambiguousDateIntervals'])==3
checks['no_identification_window_overlaps']=all(a['endExclusive']['ordinal']<=b['startInclusive']['ordinal'] for a,b in zip(stage['rules'][:9],stage['rules'][1:10]))
r={'allPassed':all(checks.values()),'checks':checks,'scope':'Independent cross-file structural and resource-semantics checks; no engine integration or game replay claim'};(P/'validation_unlock_actions.json').write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n');print(json.dumps(r,indent=2));assert r['allPassed']
