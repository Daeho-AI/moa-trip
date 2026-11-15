"""Actual Pleos heads-up actions while native navigation remains visible (debug APK)."""
import json
import time
import threading
from pathlib import Path
import emulator_ui as ui
import verify_emulators as v

OUT=v.ROOT/'work/evidence/background-v050'
OUT.mkdir(parents=True,exist_ok=True)
v.OUT=OUT
A,B=v.A,v.B

def native(s):
    # Let the activity launch requested by the debug setup complete before leaving it.
    time.sleep(1)
    v.adb(s,'shell','am','start','--user','current','-n','ai.umos.maps.android.navigation.app/.MainActivity')

def hun(s,label):
    def visible():
        nodes=list(ui.tree(s,True).iter('node'))
        return any(n.get('text')==label and n.get('package')!='dev.moa.trip' for n in nodes) and any(
            n.get('package')=='ai.umos.maps.android.navigation.app' for n in nodes)
    v.wait(s+' heads-up over native: '+label,visible,12)

def answer(s,label,name):
    hun(s,label)
    v.screenshot(s,name)
    ui.tap(s,label,timeout=5,all_windows=True)
    time.sleep(1)
    nodes=list(ui.tree(s,True).iter('node'))
    assert any(n.get('package')=='ai.umos.maps.android.navigation.app' for n in nodes)
    assert not any(n.get('package')=='dev.moa.trip' for n in nodes)
    v.passed(name+' stays on native navigation')

def run():
    ui.prepare((A,B))
    for s in (A,B):
        v.users[s]=v.adb(s,'shell','am','get-current-user')
        v.wait(s+' SDK ready',lambda s=s:v.native_ready(s))
        if v.active_room_id(s):
            v.command(s,'trip','finish')
            v.wait(s+' old room ended',lambda s=s:v.active_room_id(s) is None)
    ui.native_search_42dot(A)
    ui.native_search_42dot(B,'DOT42','DOT42')
    v.wait('fresh native search results settled',lambda:(v.read(A).get('destination') or {}).get('name')=='42dot' and (v.read(B).get('destination') or {}).get('name')=='DOT42')
    before=v.read(B)['destination']; source=v.read(A)['destination']
    initial_writes=v.writes(B)
    v.command(A,'trip','create',title='Trip',nickname='Car-A');native(A)
    answer(A,'나만 변경','leader-decline')
    assert v.revision()==0;v.passed('leader decline does not publish destination')
    v.command(A,'trip','reconsider');native(A)
    answer(A,'일행에게 요청','leader-approve')
    v.wait('leader publishes approved target',lambda:v.revision()==1)
    code=v.read(A,True)['self']['inviteCode']
    v.command(B,'trip','join',code=code,nickname='Car-B');native(B)
    answer(B,'현재 목적지 유지','follower-decline')
    v.unchanged('decline keeps native destination and issues no write',B,initial_writes,before['poiId'])
    v.adb(B,'forward','tcp:8089','tcp:8088')
    v.console('/browser/session?client=moatrip-confirmation')
    threading.Thread(target=v.heartbeat,daemon=True).start()
    try:
        v.console('/vehicle/gear?gear=Drive','PUT')
        v.console('/vehicle/controls?speed=30&accelerator=0&brake=0&steering=0','PUT')
        v.wait('Drive UX restrictions active',lambda:not v.read(B)['parked'] and v.read(B)['uxCanConfirm'])
        v.command(B,'trip','reconsider');native(B)
        answer(B,'목적지 변경','follower-approve-drive')
        v.wait('background approval verified by native readbacks',lambda:v.own(v.read(B,True))['sync']['state']=='APPLIED')
        assert v.read(B)['destination']==source,(v.read(B)['destination'],source)
        assert v.writes(B)==initial_writes+1
        v.passed('exact source destination matches; one SDK write')
        (OUT/'driving-platform.txt').write_text(v.adb(B,'shell','dumpsys','car_service','--services','CarDrivingStateService','CarUxRestrictionsManagerService'))
        v.screenshot(B,'native-after-approval')
    finally:
        v.console_stop.set()
        v.console('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0','PUT')
        v.console('/vehicle/gear?gear=Park','PUT')
        v.console('/browser/session?client=moatrip-confirmation&closing=1')
    time.sleep(3)
    for s in (A,B):
        v.command(s,'trip','grant',location=True,eta=True,follow=False);time.sleep(1);native(s)
    for s,lon,lat in ((A,'127.035','37.49'),(B,'127.042','37.487')):
        v.adb(s,'emu','geo','fix',lon,lat)
    v.wait('both actual SDK location and ETA shared in background',lambda:all(
        len(v.read(s,True)['members'])==2 and all(m['location'] and m['eta'] for m in v.read(s,True)['members']) for s in (A,B)))
    v.capture('final')
    (OUT/'checks.json').write_text(json.dumps(v.results,ensure_ascii=False,indent=2))
    print('ALL BACKGROUND CHECKS PASSED',len(v.results),flush=True)

if __name__=='__main__':run()
