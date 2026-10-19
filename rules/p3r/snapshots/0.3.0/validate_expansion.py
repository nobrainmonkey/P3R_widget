import json,pathlib,datetime
P=pathlib.Path(__file__).parent
R=lambda f:json.loads((P/f).read_text())
checks={}; details=[]
for slug in ['magician','chariot']:
 d=R(slug+'_base_model.json');events={e['to_rank']:e for e in d['rank_events']};gates={g['to_rank']:g['required_raw_affinity'] for g in d['incoming_thresholds']}
 checks[slug+'_all_nine_gates']=set(gates)==set(range(2,11))
 checks[slug+'_all_nine_events']=set(events)==set(range(2,11))
 checks[slug+'_raw_optimal_sums']=all(sum(max(o['raw_affinity_points'] for o in p['options']) for p in e['prompts'])==e['optimal_base_gain'] for e in events.values())
 checks[slug+'_no_fabricated_CN']=all(o.get('localized_text') is None for e in events.values() for p in e['prompts'] for o in p['options'])
 passed=0
 for f in d['test_fixtures']:
  x=f.get('input',f)
  if x.get('mode')=='bonus_readiness_lookup':continue
  rank=x.get('completed_event_to_rank',x.get('event_to_rank',x.get('rank')))
  options=x.get('options',x.get('selected_source_options'))
  if options is not None:
   e=events[rank];total=sum(next((o['raw_affinity_points'] for o in p['options'] if o['source_option_ordinal']==opt),0) for p,opt in zip(e['prompts'],options))
   exp=x.get('lower_bound_after',f.get('expected_lower_bound'))
   if exp is not None:assert total==exp,(f['id'],total,exp)
  else:total=x.get('lower_bound',0)
  predicted=True if rank<10 and total>=gates[rank+1] else None
  assert predicted==f['expected_next_ready'],(f['id'],predicted)
  passed+=1
 checks[slug+'_independent_base_fixtures']=passed>0;details.append({'model':slug,'independentlyReevaluatedBaseFixtures':passed})
j=R('jobs_wage_rules_candidate.json');checks['3_jobs_12_tiers']=len(j['jobs'])==3 and sum(len(x['reportedWageTiers']) for x in j['jobs'])==12
checks['job_exact_pay_disabled']=all(not x['exactWageExecutionEnabled'] for x in j['jobs'])
checks['job_floor_not_exact_money']=j['guardedMinimumIncomeActions'][0]['effects'][0]['field']=='yenLowerBound'
w=R('early_link_calendar_witnesses.json');checks['19_distinct_visit_witnesses']=len(w['records'])==len({r['id'] for r in w['records']})==19
checks['witness_dates_correct']=all((datetime.date(2009,4,1)+datetime.timedelta(days=x['date']['ordinal'])).strftime('%m-%d')==x['date']['mmdd'] for x in w['records'])
checks['witness_not_unconditional']=all(x['availableForArbitraryCheckpoint'] is None for x in w['records'])
r={'allPassed':all(checks.values()),'checks':checks,'details':details,'scope':'Independent raw-point fixture evaluation and candidate integrity. No Java integration or game replay claimed.'}
(P/'validation_expansion.json').write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n');print(json.dumps(r,indent=2));assert r['allPassed']
