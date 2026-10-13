import json,pathlib,hashlib,datetime
P=pathlib.Path(__file__).parent
read=lambda f:json.loads((P/f).read_text())
links=read('social_links.json')['entities'];cal=read('calendar_january_slice.json');stages=read('tower_stages.json')['stages'];actions=read('playable_actions.json')['actions'];stats=read('stats_actions.json')
checks={}
checks['22_unique_links']=len(links)==len({x['id'] for x in links})==22
checks['58_unique_link_calendar_cells']=len(cal['cells'])==len({(c['entityId'],c['date']['ordinal'],c['slot']) for c in cal['cells']})==58
checks['4_sun_9_devil_dates']=sum(c['available'] for c in cal['cells'] if c['entityId'].endswith('.sun'))==4 and sum(c['available'] for c in cal['cells'] if c['entityId'].endswith('.devil'))==9
checks['calendar_predicates_guard_rank_and_state']=all(len(c['preconditions'])==3 and c['sources'] for c in cal['cells'])
checks['date_axis_roundtrip']=all((datetime.date(2009,4,1)+datetime.timedelta(days=c['date']['ordinal'])).strftime('%m-%d')==c['date']['mmdd'] for c in cal['cells'])
checks['only_two_point_free_link_transitions']=sorted(x['id'] for x in links if x['rank_transition'].get('executable'))==['p3r.social_link.devil','p3r.social_link.sun']
checks['no_automatic_unlocks']=all(not x['unlock'].get('executable',False) for x in links)
checks['stage10_explicit_window']=next(x for x in stages if x['id'].endswith('_10'))['floorCeiling']==256 and sum(x['planningEnabled'] for x in stages)==1
checks['conflicting_stage_dates_remain_null']=all(x['startInclusive'] is None for x in stages if x['id'].endswith(('_03','_09')))
checks['no_trip_cash_or_floor_guarantee']=actions[-1]['expectedMoney'] is None and actions[-1]['expectedFloor'] is None
checks['five_stat_actions_need_verified_free_slot']=len(stats['actions'])==5 and all(any(q['field']=='calendar.slotState' for q in a['prerequisites']) for a in stats['actions'])
checks['unknown_preconditions_do_not_schedule']=all(a['missingPreconditionPolicy']=='unknown_do_not_schedule' for a in stats['actions'])
checks['original_candidate_unchanged']=hashlib.sha256(pathlib.Path('/workspace/scratch/c0da63724a20/p3r_guide_audit/data.candidate.json').read_bytes()).hexdigest()=='ff93b7653ec19049667856a8f439f69d5f722772a21f57e504f85e145a3c68d1'
checks['fixture_sun6_reaches10']=min(10,6+sum(c['available'] for c in cal['cells'] if c['entityId'].endswith('.sun')))==10
checks['fixture_sun5_stops9']=min(10,5+sum(c['available'] for c in cal['cells'] if c['entityId'].endswith('.sun')))==9
checks['fixture_devil1_reaches10']=min(10,1+sum(c['available'] for c in cal['cells'] if c['entityId'].endswith('.devil')))==10
full=read('calendar_full_candidate.json');points=read('reported_rank_points.json');observed=read('observation_gated_rank_actions.json');romance=read('romance_rank_rules_candidate.json');regular=read('reported_regular_activities.json')
checks['5434_unique_full_calendar_candidates']=len(full['cells'])==len({(x['entityId'],x['date']['ordinal'],x['slot']) for x in full['cells']})==5434
checks['286_candidate_dates']=len({x['date']['ordinal'] for x in full['cells']})==286
checks['candidate_calendar_not_blindly_executable']=all(x['executable']==False and x['available'] is None for x in full['cells'])
checks['full_calendar_date_axis']=all((datetime.date(2009,4,1)+datetime.timedelta(days=x['date']['ordinal'])).strftime('%m-%d')==x['date']['mmdd'] for x in full['cells'])
checks['175_unique_point_rows']=len(points['rows'])==len({x['ruleId'] for x in points['rows']})==175
checks['unresolved_numeric_model_not_enabled']=all(not x['numericExecutionEnabled'] for x in points['rows'])
checks['17_observed_actions_require_rank_scoped_readiness']=len(observed['actions'])==17 and all(any(q['field']=='rankUpReady.observedRank' and q['op']=='eq_current_rank' for q in a['preconditions']) for a in observed['actions'])
checks['six_romance_links_ten_rank_coverage']=len(romance['entities'])==6 and romance['verification']['all_rank_1_to_10_coverage']
checks['romance_numeric_disabled']=romance['verification']['all_numeric_simulation_disabled']
checks['22_regular_activity_candidates']=len(regular['activities'])==22 and all(not x['planningEnabled'] for x in regular['activities'])
report={'allPassed':all(checks.values()),'checks':checks,'scope':'Data integrity and bounded arithmetic fixtures; not actual game replay, engine integration, or proof of full rule completeness'}
(P/'validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2));assert report['allPassed']
