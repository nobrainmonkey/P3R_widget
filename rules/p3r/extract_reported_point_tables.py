import json,pathlib,re,hashlib
from lxml import html
P=pathlib.Path(__file__).parent;raw=pathlib.Path('/tmp/p3r_full_calendar_source.html').read_bytes();tree=html.fromstring(raw.decode('utf-8'))
slugs={'Magician':'magician','Priestess':'priestess','Empress':'empress','Emperor':'emperor','Hierophant':'hierophant','Lovers':'lovers','Chariot':'chariot','Justice':'justice','Hermit':'hermit','Fortune':'fortune','Strength':'strength','Hanged':'hanged_man','Temperance':'temperance','Tower':'tower','Star':'star','Moon':'moon','Aeon':'aeon'}
rows=[]
for label,slug in slugs.items():
 hs=tree.xpath('//h4[normalize-space(.)=$name]',name=label);assert len(hs)==1
 ul=hs[0].getnext();assert ul.tag=='ul'
 for li in ul.xpath('./li'):
  heading=(li.text or '').strip();m=re.match(re.escape(label)+r' Rank (\d+)(.*)',heading)
  if not m:continue
  rank=int(m[1]);suffix=m[2];points=re.search(r'(\d+)/(\d+) points',suffix)
  options=[]
  for op in li.xpath('./ul/li'):
   line=' '.join(op.itertext());choice=re.search(r'Choice\s+(\d+)[.:]',line);gain=re.search(r'\(\+(\d+),\s*(\d+) notes?\)',line)
   if line.strip()=='Any':options.append({'kind':'any','optionOrdinal':None,'basePointGain':None,'displayNotes':None})
   elif choice:options.append({'kind':'numbered_source_option','optionOrdinal':int(choice[1]),'basePointGain':int(gain[1]) if gain else None,'displayNotes':int(gain[2]) if gain else None})
  branch='romantic' if 'Romantic' in suffix else 'platonic' if 'Platonic' in suffix else 'unspecified'
  rows.append({'ruleId':f'p3r.reported_points.{slug}.rank_{rank}.{branch}','entityId':'p3r.social_link.'+slug,'sourceSceneRankLabel':rank,'branch':branch,'reportedOptimalSceneGain':int(points[1]) if points else None,'reportedPointsToNextRank':int(points[2]) if points else None,'options':options,'missingPointValue':'unknown_not_zero','verificationStatus':'source_observed_claim','numericExecutionEnabled':False,'sourceURL':'https://aqiu384.github.io/megaten-database/p3r/social-events#'+label.lower(),'evidenceLocator':label+' / Rank '+str(rank)+' '+branch})
report={'schemaVersion':1,'checkedAt':'2026-10-13','gameEdition':'P3R-base','sourceHtmlSha256':hashlib.sha256(raw).hexdigest(),'scope':'17 point-dependent social-link tables; factual numeric candidate extraction, no foreign dialogue translated','rows':rows,'semantics':{'sourceRankLabel':'Scene reaching Rank n; numerator is reported unboosted optimal gain in that scene. Denominator is reported points to following rank. Cross-check model indexing before execution.','notYetVerified':['reset/carryover timing','bonus scope and rounding order','all nonoptimal answers','branch availability and earlier flags','current hidden affinity'],'omittedRanks':'Do not replace missing entries by zero even where source general note suggests zero; keep explicit until crosschecked.'}}
(P/'reported_rank_points.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print('extracted',len(rows),'rank scene records across',len(slugs),'links')
