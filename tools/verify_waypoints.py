"""Actual NaviHelper + native UI test; only MoaTripA/B test AVDs are used."""
import json,time
from pathlib import Path
import emulator_ui as u
import verify_emulators as v

A,B=v.A,v.B
OUT=v.ROOT/'work/evidence/waypoints-v050';OUT.mkdir(parents=True,exist_ok=True);v.OUT=OUT

def native(s):
    time.sleep(1);u.adb(s,'shell','am','start','--user','current','-n','ai.umos.maps.android.navigation.app/.MainActivity');time.sleep(2)

def respond(s,label):
    v.wait('native heads-up '+s+' '+label,lambda:any(n.get('text')==label and n.get('package')=='ai.umos.notification' for n in u.tree(s,True).iter('node')),25)
    u.tap(s,label,all_windows=True);time.sleep(1)

def stops(s):return [p['poiId'] for p in v.read(s)['waypoints']]
def decision(s):return v.read(s,True)['self'].get('stopDecision') or {}
def wait_stop(s,kind):return v.wait('proposal '+kind,lambda:v.pending(s,kind),70)
def app(s):v.command(s,'trip','refresh');time.sleep(2)

def run():
    u.prepare((A,B))
    for s in (A,B):
        v.users[s]=u.adb(s,'shell','am','get-current-user');v.command(s,'probe','refresh')
        v.wait(s+' SDK ready',lambda s=s:v.native_ready(s))
        if v.active_room_id(s):
            v.command(s,'trip','finish');v.wait(s+' old room ended',lambda s=s:v.active_room_id(s) is None)
        u.native_search_42dot(s,'두물머리주차장','두물머리주차장',settle=23,address_contains='양평')
    target=v.target(A);v.wait('same destination and empty waypoints',lambda:v.target(B)==target and not stops(A) and not stops(B))
    # A personal stop in the follower's own navigation must remain first.
    u.native_search_42dot(B,'양수역','양수역 경의중앙선',settle=1,as_waypoint=True)
    v.wait('personal waypoint received from real SDK',lambda:len(stops(B))==1,70)
    personal=stops(B)[0]
    v.command(A,'trip','create',title='경유지 검증 여행',nickname='리더 차량')
    v.wait('initial destination prompt',lambda:v.pending(A,'LEADER'))
    u.tap(A,'일행에게 요청');v.wait('destination published',lambda:v.revision()==1)
    v.command(B,'trip','join',code=v.read(A,True)['self']['inviteCode'],nickname='참여 차량')
    v.wait('participant joined',lambda:v.active_room_id(B) is not None)
    for s in (A,B):v.command(s,'trip','grant',location=True,eta=True,follow=False);native(s)
    count=v.writes(B)
    u.native_search_42dot(A,'양평 세미원','세미원',settle=1,address_contains='양평',as_waypoint=True)
    wait_stop(A,'LEADER_STOP');assert v.read(A,True)['room']['stopProposal'] is None
    v.passed('leader native addition does not publish without consent')
    respond(A,'일행에게 요청');wait_stop(B,'FOLLOWER_STOP')
    v.unchanged('recipient request never writes before approval',B,count,target,2)
    v.capture('before-stop-approval');v.screenshot(B,'native-stop-prompt')
    respond(B,'경유지 추가')
    v.wait('waypoint approval confirms real native readback',lambda:decision(B).get('state')=='APPLIED',80)
    expected=[personal,stops(A)[0]]
    assert stops(B)==expected and v.target(B)==target and v.writes(B)==count+1
    v.passed('one SDK write preserves final destination and personal waypoint order')
    v.capture('approved');v.screenshot(B,'native-stop-applied')
    u.adb(B,'shell','am','force-stop','--user',v.users[B],'dev.moa.trip');v.command(B,'trip','refresh')
    v.wait('restart does not add the accepted visit again',lambda:v.writes(B)==0 and stops(B)==expected and decision(B).get('state')=='APPLIED')
    # Two existing waypoints must not be overwritten by another proposal.
    for s in (A,B):native(s)
    u.native_search_42dot(A,'양평군립미술관','양평군립미술관',settle=1,address_contains='양평',as_waypoint=True)
    wait_stop(A,'LEADER_STOP');respond(A,'일행에게 요청');wait_stop(B,'FOLLOWER_STOP')
    capacity_tail(target,expected)

def capacity_tail(target,expected):
    app(B);time.sleep(3)
    assert v.writes(B)==0 and stops(B)==expected and decision(B).get('stopId')!=v.read(B,True)['room']['stopProposal']['id']
    tree=u.tree(B);nodes=list(tree.iter('node'))
    assert any('최대 2곳' in n.get('text','') for n in nodes)
    label=next(n for n in nodes if n.get('text')=='경유지 추가')
    parent=next(n for n in nodes if label in list(n))
    assert parent.get('enabled')=='false'
    u.tap_node(B,label);time.sleep(1)
    assert v.writes(B)==0 and stops(B)==expected
    v.passed('capacity conflict is visible and preserves both existing waypoints')
    u.tap(B,'들르지 않기');v.wait('decline persists',lambda:decision(B).get('state')=='DECLINED')
    u.adb(B,'shell','am','force-stop','--user',v.users[B],'dev.moa.trip');v.command(B,'trip','refresh')
    v.wait('decline survives restart with no SDK write',lambda:decision(B).get('state')=='DECLINED' and v.writes(B)==0 and stops(B)==expected)
    # Explicit native removal/route reset must not restore an already accepted stop.
    u.native_search_42dot(B,'두물머리주차장','두물머리주차장',settle=1,address_contains='양평')
    v.wait('native waypoint removal remains removed',lambda:not stops(B),70)
    app(B);time.sleep(8)
    assert not stops(B) and v.writes(B)==0 and v.read(B,prompt=True) is None
    v.passed('native removal and refresh never restore previous visits')
    for s,lon,lat in ((A,'127.035','37.49'),(B,'127.042','37.487')):u.adb(s,'emu','geo','fix',lon,lat)
    v.wait('location and final-destination ETA remain shared after stop choice',lambda:v.read(A,True)['assembly']['known']==2 and all(m['location'] for m in v.read(A,True)['members']),50)
    v.capture('final')

if __name__=='__main__':
    import sys
    try:
        if '--resume-capacity' in sys.argv:
            v.results=json.loads((OUT/'checks.json').read_text())
            for s in (A,B):
                v.users[s]=u.adb(s,'shell','am','get-current-user');app(s)
                v.wait(s+' SDK ready after UI update',lambda s=s:v.native_ready(s))
            wait_stop(B,'FOLLOWER_STOP')
            assert len(stops(B))==2
            capacity_tail(v.target(B),stops(B))
        else:run()
    finally:(OUT/'checks.json').write_text(json.dumps(v.results,ensure_ascii=False,indent=2))
