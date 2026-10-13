"""Narrow verified-JSON adapter, NOT an all-goal P3R solver.

Only already-unlocked, explicitly normal Sun/Devil meetings in Jan02-Jan30
are mapped. Unknown story/free-slot coverage keeps every candidate conditional.
Other links, disputed stages, automatic rescues and income predictions stay unsupported.
"""
from pathlib import Path
import hashlib
import json
from planner_core import Action, Ruleset, Stage


def load_january_pack(directory=None):
    directory = Path(directory or Path(__file__).parent/'rules'/'p3r').resolve()
    manifest = json.loads((directory/'manifest.json').read_text())
    if manifest.get('gameEdition') != 'P3R-base':
        raise ValueError('Wrong game edition')
    for filename,digest in manifest['files'].items():
        path = (directory/filename).resolve()
        if path.parent != directory or path.suffix != '.json':
            raise ValueError('Invalid rule pack path')
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Rule manifest checksum mismatch: '+filename)
    def read(name): return json.loads((directory/name).read_text())
    calendar, definitions, towers = read('calendar_january_slice.json'), read('playable_actions.json'), read('tower_stages.json')
    version = 'p3r-january-'+manifest['packVersion']+'-'+hashlib.sha256((directory/'manifest.json').read_bytes()).hexdigest()[:12]
    first = calendar['window']['startInclusive']['ordinal']*2
    horizon = (calendar['window']['endInclusive']['ordinal']+1)*2
    fields = {'cash'}
    actions, stages, labels, evidence = [], [], {}, {}
    date_ordinals = {}
    for cell in calendar['cells']:
        day = cell['date']; date_ordinals[day['mmdd']] = day['ordinal']
        for phase,offset in [('daytime',0),('evening',1)]:
            labels[day['ordinal']*2+offset] = {'date':day['mmdd'],'period':phase}
    for stage in towers['stages']:
        if not stage.get('planningEnabled') or stage['verificationStatus'] != 'corroborated': continue
        stages.append(Stage(stage['id'],stage['startInclusive']['ordinal']*2,
                            stage['endExclusive']['ordinal']*2,stage['floorCeiling']))
    for definition in definitions['actions']:
        name = definition['ruleId']
        evidence[name] = definition
        if name in {'p3r.action.visit_sun','p3r.action.visit_devil'}:
            link = 'sun' if name.endswith('sun') else 'devil'
            field,normal = f'socialLinks.{link}.rank',f'socialLinks.{link}.normal'
            fields.update((field,normal))
            slots = frozenset(cell['date']['ordinal']*2+(cell['slot']=='evening')
                              for cell in calendar['cells'] if cell['entityId']==definition['entityId']
                              and cell['available'] is True and cell['verificationStatus']=='corroborated')
            effect = definition['onCompletedNormalVisit']
            if effect != {'rankDelta':1,'rankCap':10} or definition['slotCost'] != 1:
                raise ValueError('Unmapped transition semantics')
            actions.append(Action(name,slots,requirements={field:1,normal:1},effects={field:1},
                                  maximums={field:9,normal:1}, verification='corroborated',
                                  source=json.dumps(definition['evidenceRefs'])))
        elif name == 'p3r.action.explore_stage_10':
            slots = frozenset(date_ordinals[date]*2+1 for date in definition['allowedDateSubset'])
            # No money or floor effects: return form supplies actual outcomes.
            actions.append(Action(name,slots,dungeon_visit=True,verification='corroborated',
                                  source=json.dumps(definition['evidenceRefs'])))
        else:
            raise ValueError('Unmapped executable action '+name)
    rules = Ruleset(version,horizon,frozenset(fields),tuple(actions),tuple(stages),
                    coverage_complete=False,synthetic=False)
    def preflight(observed,goals):
        if observed['rules_version'] != rules.version:
            return ('unsupported','Ruleset changed; explicitly rebase this run before using the new rules.')
        if not first <= observed['game_slot'] <= horizon:
            return ('unsupported','Only Jan02-Jan30 is supported by this rule slice.')
        for goal in goals:
            if goal.field not in {'socialLinks.sun.rank','socialLinks.devil.rank'}:
                return ('unsupported','This Social Link or goal has no executable rules in the current slice.')
            if not 1 <= goal.target <= 10:
                return ('unsupported','Target rank is outside the verified rank range.')
            rank = observed['facts'].get(goal.field,{'low':None,'high':None})
            normal = observed['facts'].get(goal.field.replace('.rank','.normal'),{'low':None,'high':None})
            if rank['low'] is None or rank['low'] != rank['high'] or normal != {'low':1,'high':1}:
                return ('unknown','Confirm current visible rank and normal relationship state; hidden affinity need not be reconstructed.')
            if not 1 <= rank['low'] <= 10:
                return ('unsupported','This slice requires the selected Social Link to be already unlocked.')
        return None
    return {'rules':rules,'preflight':preflight,'labels':labels,'evidence':evidence,'first_slot':first,
            'coverage_note':'Already-unlocked normal Sun/Devil only; no complete story/free-slot model, no guaranteed Tartarus income or clearance.'}
