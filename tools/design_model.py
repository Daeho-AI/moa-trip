"""Executable design rules only. This is not a vehicle or server implementation."""


def route_merge(data, policy):
    if data.get('progressUncertain'):
        return {'state': 'PROGRESS_UNCERTAIN', 'waypoints': []}
    personal = [p['ref'] for p in data.get('personal', []) if not p.get('completed')]
    common = [p['ref'] for p in data.get('common', [])
              if p['choice'] == 'GO' and not p.get('completed')]
    desired = personal + common
    if len(set(desired)) != len(desired):
        return {'state': 'INVALID_PLAN', 'waypoints': []}
    if len(desired) > policy['navigation']['maxActiveWaypoints']:
        return {'state': 'CAPACITY_CONFLICT', 'waypoints': []}
    if personal and common:
        order = [ref for ref in data.get('approvedOrder', []) if ref in desired]
        if (len(order) != len(desired) or set(order) != set(desired)
                or [ref for ref in order if ref in personal] != personal
                or [ref for ref in order if ref in common] != common):
            return {'state': 'ORDER_REVIEW', 'waypoints': []}
    else:
        order = personal or common
    return {'state': 'READY', 'waypoints': order}


def assembly_summary(data, policy):
    """ETA coverage never uses only the subset that happened to send a value."""
    members = data['members']
    expected = [m for m in members if m['choice'] == 'GO']
    pending = sum(m['choice'] == 'PENDING' for m in members)
    fresh_sec = policy['sharing']['etaStaleAfterMs'] / 1000
    known = []
    missing = []
    arrived = 0
    for member in expected:
        if (member.get('arrived') and member.get('presenceFresh')
                and member.get('arrivedVisit') == data['target']):
            known.append(data['nowSec'])
            arrived += 1
        elif (member.get('target') == data['target'] and member.get('applied')
              and member.get('ageSec', fresh_sec + 1) <= fresh_sec
              and member.get('ageSec', -1) >= 0
              and member.get('etaAtSec') is not None):
            known.append(max(data['nowSec'], member['etaAtSec']))
        else:
            missing.append(member['id'])
    complete = bool(expected) and not missing and pending == 0
    if complete and arrived == len(expected):
        state = 'ALL_ARRIVED'
    elif complete:
        state = 'COMPLETE'
    elif known:
        state = 'PARTIAL'
    elif not expected and not pending:
        state = 'EMPTY'
    else:
        state = 'UNKNOWN'
    all_ready = complete and all(
        m.get('arrived') and m.get('presenceFresh') and m.get('ready')
        and m.get('arrivedVisit') == data['target']
        and 0 <= m.get('readyAgeSec', -1) <= policy['assembly']['readinessExpiresSec']
        for m in expected
    )
    return {'state': state, 'expected': len(expected), 'known': len(known),
            'pending': pending, 'missing': missing,
            'latestKnownArrivalSec': max(known) if known else None,
            'allReady': all_ready}


def navigation_gate(data, _policy):
    if data.get('sharedStopFollow') and not data.get('destinationFollow'):
        return 'INVALID_GRANT'
    if data.get('previousWriteUncertain'):
        return 'WRITE_UNCERTAIN'
    if data.get('writeInFlight'):
        return 'WAIT_FOR_CURRENT_WRITE'
    if data.get('localStructuralOverride'):
        return 'LOCAL_OVERRIDE'
    if not data.get('uxAllowsWrite', False):
        return 'DEFERRED_POLICY'
    if not data.get('destinationFollow') and not data.get('oneTimeApply'):
        return 'NEEDS_CONFIRMATION'
    if data.get('newEnRouteStop') and data.get('stopChoice') != 'GO':
        return 'STOP_DECISION_REQUIRED'
    return 'ALLOW'


def pickup_decision(data, policy):
    phase = data['phase']
    if phase in {'CANCELLED', 'EXPIRED', 'BOARDED'}:
        return {'place': 'TERMINAL', 'recommendation': 'OFF', 'walkingPrompt': 'OFF'}
    committed = phase in {'DRIVER_CONFIRMED', 'APPLYING', 'APPLIED', 'MEETING'}
    if committed:
        confirmed_navigation = (data.get('nativeState') in {'APPLIED', 'ARRIVED'}
                                and data.get('vehicleFresh')
                                and data.get('matchesCommittedPlace'))
        return {'place': 'KEEP_COMMITTED', 'recommendation': 'OFF',
                'walkingPrompt': 'SCHEDULED' if confirmed_navigation else 'HOLD'}
    if data.get('maxWalkSec') == 0:
        return {'place': 'KEEP_ORIGINAL', 'recommendation': 'OFF', 'walkingPrompt': 'HOLD'}
    invalidated = (data.get('resultAgeSec', 0) > policy['pickup']['resultFreshForSec']
                   or data.get('passengerMovedM', 0) >= 50
                   or data.get('vehicleMovedM', 0) >= 250
                   or data.get('planChanged'))
    return {'place': 'UNCOMMITTED',
            'recommendation': 'REFRESH_ON_REQUEST' if invalidated else 'CURRENT',
            'walkingPrompt': 'HOLD'}


SCENARIO_HANDLERS = {
    'route_merge': route_merge,
    'assembly': assembly_summary,
    'navigation_gate': navigation_gate,
    'pickup': pickup_decision,
}
