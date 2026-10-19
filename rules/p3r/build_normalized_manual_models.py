import json,pathlib,copy
P=pathlib.Path(__file__).parent;R=lambda f:json.loads((P/f).read_text());entities=[]
for slug in ['magician','chariot']:
 f=slug+'_base_model.json';d=R(f);gates=[]
 for g in d['incoming_thresholds']:
  z=copy.deepcopy(g);z.update(exact_raw_threshold=g['required_raw_affinity'],source_envelope_threshold_upper_bound=g['required_raw_affinity'],executable_sufficiency_check=True,requires_opt_in_source_envelope_mode=False);gates.append(z)
 entities.append({'entity_id':d['entity_id'],'name_zh_cn':d['name_zh_cn'],'sourceArtifact':f,'incoming_thresholds':gates,'rank_events':copy.deepcopy(d['rank_events']),'base_mode_executable':True,'bonus_lookup_executable':False,'optional_source_bonus_lookup':d.get('readiness_truth_table'),'normal_visit_contract':d['normal_visit_contract'],'state_contract':d['state_contract'],'sources':d['sources']})
for f in ['romance_base_models_candidate.json','other_manual_link_base_models_candidate.json']:
 for e in R(f)['entities']:
  z=copy.deepcopy(e);z['sourceArtifact']=f;entities.append(z)
for e in entities:
 for v in e['rank_events']:
  v.setdefault('branch_context','shared');v.setdefault('entry_guards',[])
  ps=v['prompts']
  for i,p in enumerate(ps):
   for o in p['options']:
    if 'next_prompt_ordinal' not in o:o['next_prompt_ordinal']=ps[i+1]['prompt_ordinal'] if i+1<len(ps) else None
    o.setdefault('effects',[])
    o['sourceOptionOrderVerifiedAgainstSimplifiedChineseGame']=False
    o['localizedPointBindingEnabled']=False
  v.setdefault('executable_base_lower_bound',True)
d={'schema':'p3r.manual_link_models.normalized.v1','gameEdition':'P3R-base','normalization':'No numeric evidence altered. Existing prompt graphs retained; linear nonbranching source tables receive sequential edges. Every entity preserves sourceArtifact and provenance.','sourceOptionOrderVerifiedAgainstSimplifiedChineseGame':False,'sourceOrdinalsAreUserFacingGameNumbers':False,'scope':'All17 affinity-dependent manual links; Sun/Devil separate fixed normal-visit model; three story links separate','sharedExecutionPolicy':{'eligibility':'Calendar/source guards, unlocked rank1..9 and normal non-reversed status required','afterCompletedRankEvent':'assign new rank; replace known affinity lower bound with actual chosen path base gains; ignore unknown carryover and bonuses','unknownChoice':'no optimal credit; use known selected gains only when legal path known','readiness':'true only if bound reaches an enabled next-rank gate; else unknown, not false','reversal':'invalidate readiness and lower bound; permanent calendar status becomes reversed until actually resolved','branchPolicy':'entry guards must be satisfied; traverse only reachable prompts; unknown branch cannot borrow maximum of mutually exclusive paths','planningPolicy':'Future answer policy is an explicit conditional assumption; reconcile actual choices/result on completion','localization':'Foreign source ordinal is not proof of official Chinese wording; localized_text remains null unless separately sourced'},'stateFieldMapping':{'relationship':'socialLinks.{slug}.relationshipType (friendship|romantic)','relationship_reversed':'socialLinks.{slug}.relationshipState=reversed and forget affinity/readiness','{slug}.romance_eligible':'socialLinks.{slug}.romanceEligible; unknown remains unknown'},'entities':entities,'counts':{'entities':len(entities),'incomingThresholds':sum(len(e['incoming_thresholds']) for e in entities),'rankEventBranches':sum(len(e['rank_events']) for e in entities),'answerValues':sum(len(p['options']) for e in entities for v in e['rank_events'] for p in v['prompts'])}}
assert d['counts']['entities']==17 and d['counts']['answerValues']==897
(P/'manual_link_models.json').write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n');print(d['counts'])
