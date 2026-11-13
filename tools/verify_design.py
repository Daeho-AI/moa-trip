"""Validate the saved design inputs and numerical examples; no SDK/live claims."""
from pathlib import Path
import json
import re
from urllib.parse import unquote
from design_model import SCENARIO_HANDLERS

root = Path(__file__).resolve().parents[1]
assert root.drive.upper() == 'D:'
policy = json.loads((root/'contracts/product-policy.json').read_text(encoding='utf-8'))
examples = json.loads((root/'contracts/pickup-examples.json').read_text(encoding='utf-8'))
cases = {c['id']: c for c in examples['cases']}
results = []

for original in examples['cases']:
    case = dict(original)
    if 'copyCandidatesFrom' in case:
        parent = cases[case['copyCandidatesFrom']]
        case['baseline'] = parent['baseline']
        case['candidates'] = parent['candidates']
    def evaluate(place):
        meeting = max(place['driveBeforeSec'], case['readySec']+place['walkSec'])
        wait = max(0, case['readySec']+place['walkSec']-place['driveBeforeSec'])
        total = meeting+case['boardingSec']+place['driveAfterSec']
        walk_start = max(case['readySec'], place['driveBeforeSec']-place['walkSec'])
        return {'total':total,'driverWait':wait,'walkStart':walk_start,
                'passengerWaitWhenReady':max(0,place['driveBeforeSec']-case['readySec']-place['walkSec']),
                'passengerWaitScheduled':max(0,place['driveBeforeSec']-walk_start-place['walkSec'])}
    baseline = evaluate(case['baseline'])['total']
    assert baseline == case['expectedBaselineSec']
    ranked = []
    assert len(case['candidates']) <= policy['pickup']['maxAlternativeCandidates']
    for place in case['candidates']:
        timing = evaluate(place)
        total, wait = timing['total'], timing['driverWait']
        saving = baseline-total
        if (case['maxWalkSec'] > 0 and place['verifiedMeetingPoint']
            and place['walkSec'] <= case['maxWalkSec']
            and wait <= case['maxDriverWaitSec'] and saving >= case['minSavingSec']
            and saving >= place['walkSec']*policy['pickup']['minSavingPerWalkSecond']):
            ranked.append((total,place['walkSec'],wait,place['id'],saving,timing))
    ranked.sort()
    assert [r[3] for r in ranked[:3]] == case['expectedTopThree'], case['id']
    if 'expectedBestSavingSec' in case: assert ranked[0][4] == case['expectedBestSavingSec']
    if 'expectedBestTotalSec' in case: assert ranked[0][0] == case['expectedBestTotalSec']
    for expected, field in [('expectedWalkStartAfterSec','walkStart'),
                            ('expectedPassengerWaitIfLeavingWhenReadySec','passengerWaitWhenReady'),
                            ('expectedPassengerWaitAtSuggestedStartSec','passengerWaitScheduled')]:
        if expected in case: assert ranked[0][5][field] == case[expected], case['id']
    results.append({'case':case['id'],'top':[r[3] for r in ranked[:3]],'passed':True})

scenario_results = []
scenarios = json.loads((root/'contracts/review-scenarios.json').read_text(encoding='utf-8'))
assert len({c['id'] for c in scenarios['cases']}) == len(scenarios['cases'])
for scenario in scenarios['cases']:
    actual = SCENARIO_HANDLERS[scenario['kind']](scenario['input'], policy)
    assert actual == scenario['expected'], (scenario['id'],actual,scenario['expected'])
    scenario_results.append({'case':scenario['id'],'passed':True})

required = ['PRODUCT.md','SDK_EVIDENCE.md','ARCHITECTURE.md','PICKUP.md','DELIVERY_PLAN.md','REVIEW_01.md']
assert all((root/'docs'/name).is_file() for name in required)
for document in [root/'README.md', *list((root/'docs').glob('*.md'))]:
    for destination in re.findall(r'\]\(([^)]+)\)', document.read_text(encoding='utf-8')):
        if destination.startswith(('https://','http://','#')): continue
        assert (document.parent/unquote(destination.split('#')[0])).exists(), (document,destination)

assert policy['sharing']['positionDefault'] is False
assert policy['sharing']['destinationFollowDefault'] is False
assert policy['navigation']['requireReadbackForApplied'] is True
assert policy['publish']['githubRepositoryCreated'] is False
assert policy['pickup']['initialMaxHttpAttempts'] >= 3*(policy['pickup']['maxAlternativeCandidates']+1)
assert policy['navigation']['writeAfterUncertainTimeout'] is False
assert policy['navigation']['restoreCompletedVisits'] is False
assert policy['assembly']['requireFullParticipantCoverage'] is True
assert policy['pickup']['lockMeetingPlaceAfterDriverConfirmation'] is True
assert policy['room']['extendSharingGrantsAutomatically'] is False
work = root/'work'
work.mkdir(exist_ok=True)
report = {'designVersion':policy['designVersion'],'designOnly':True,'sdkExecutionVerified':False,
          'pickupExamples':results,'reviewScenarios':scenario_results,
          'localDocumentLinks':'passed','policyConsistency':'passed'}
(work/'design-review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'designVersion':policy['designVersion'],'designOnly':True,'sdkExecutionVerified':False,
                  'pickupExamplesPassed':len(results),'reviewScenariosPassed':len(scenario_results),
                  'localDocumentLinks':'passed','policyConsistency':'passed'},ensure_ascii=False))
