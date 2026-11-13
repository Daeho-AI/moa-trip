"""Actual SDK confirmation checks, run sequentially within the host RAM budget.
Usage: verify_confirm_sequential.py leader|follower [emulator serial]
Start only the corresponding MoaTrip emulator, with the new APK installed.
The optional serial permits separate test accounts on one emulator. Record that
explicitly; neither execution mode proves two simultaneous live ETAs.
"""
import sys,json,time,threading
from pathlib import Path
import verify_emulators as h
phase=sys.argv[1]
assert phase in ('leader','follower')
h.OUT=h.ROOT/'work/evidence/confirm-sequential';h.OUT.mkdir(parents=True,exist_ok=True)
s=sys.argv[2] if len(sys.argv)>2 else (h.A if phase=='leader' else h.B)
assert s in (h.A,h.B)
assert h.adb(s,'shell','getprop','ro.boot.qemu.avd_name') in ('MoaTripA','MoaTripB')
h.users[s]=h.adb(s,'shell','am','get-current-user')
try:
    h.wait(phase+' actual fresh SDK ready',lambda:h.native_ready(s),seconds=180)
    if h.active_room_id(s):
        h.command(s,'trip','refresh')
        h.wait('existing room restored',lambda:h.read(s,True)['serverTime']>time.time()*1000-15000)
        h.command(s,'trip','finish')
        h.wait('old participation ended',lambda:h.active_room_id(s) is None)
    h.adb(s,'emu','geo','fix','127.035' if phase=='leader' else '127.042','37.49' if phase=='leader' else '37.487')
    h.command(s,'probe','official')
    h.wait('actual canonical initial destination',lambda:h.target(s)==h.FIRST)
    if phase=='leader':
        h.command(s,'trip','create',title='가을여행',nickname='대호')
        h.wait('leader prompt before publication',lambda:h.pending(s,'LEADER',h.FIRST) and h.revision(s)==0)
        h.command(s,'trip','grant',location=True,eta=True,follow=True)
        h.command(s,'trip','refresh');time.sleep(2)
        h.screenshot(s,'leader-confirmation')
        time.sleep(5);assert h.revision(s)==0
        h.passed('unanswered leader prompt never publishes')
        h.respond(s,False);time.sleep(5)
        assert h.revision(s)==0 and h.read(s,prompt=True) is None
        h.passed('leader can change only own destination')
        h.command(s,'trip','reconsider')
        h.wait('leader can reopen confirmation',lambda:h.pending(s,'LEADER',h.FIRST))
        h.respond(s,True)
        h.wait('leader approval publishes actual canonical readback',lambda:h.revision(s)==1)
        h.command(s,'probe','official-b')
        h.wait('next leader change requires fresh confirmation',lambda:h.pending(s,'LEADER',h.SECOND))
        assert h.revision(s)==1
        h.screenshot(s,'leader-next-destination')
        h.respond(s,True)
        h.wait('second confirmed plan is published',lambda:h.revision(s)==2 and h.read(s,True)['room']['destination']['poiId']==h.SECOND)
        snap=h.read(s,True)
        (h.OUT/'session.json').write_text(json.dumps({'roomId':snap['room']['id'],'code':snap['self']['inviteCode'],'planRevision':2},ensure_ascii=False),encoding='utf-8')
        (h.OUT/'leader-sdk.json').write_text(json.dumps(h.read(s),ensure_ascii=False,indent=2),encoding='utf-8')
    else:
        session=json.loads((h.OUT/'session.json').read_text(encoding='utf-8'))
        h.command(s,'trip','join',code=session['code'],nickname='민수')
        h.wait('recipient receives actual leader request',lambda:h.read(s,True)['room']['id']==session['roomId'] and h.pending(s,'FOLLOWER',h.SECOND))
        count=h.writes(s)
        h.command(s,'trip','grant',location=True,eta=True,follow=True)
        h.unchanged('legacy blanket grant cannot change destination',s,count,h.FIRST)
        h.screenshot(s,'recipient-confirmation')
        # Move to native navigation: a notification must offer review without writing.
        h.adb(s,'shell','am','start','--user',h.users[s],'-n','ai.umos.maps.android.navigation.app/.MainActivity')
        time.sleep(3)
        assert 'id=7302' in h.adb(s,'shell','dumpsys','notification')
        h.unchanged('background notification does not apply destination',s,count,h.FIRST)
        h.command(s,'trip','refresh');time.sleep(3)
        h.respond(s,False)
        h.wait('recipient decline recorded',lambda:h.own(h.read(s,True))['sync']['state']=='DECLINED')
        h.unchanged('decline keeps native destination',s,count,h.FIRST)
        h.adb(s,'shell','am','force-stop','--user',h.users[s],'dev.moa.trip')
        h.command(s,'trip','refresh')
        h.wait('decline survives process restart',lambda:h.writes(s)==0 and h.own(h.read(s,True))['sync']['state']=='DECLINED')
        h.command(s,'trip','reconsider')
        h.wait('recipient reopens same proposal',lambda:h.pending(s,'FOLLOWER',h.SECOND))
        h.respond(s,True)
        h.wait('explicit approval writes once and actual SDK confirms',lambda:h.target(s)==h.SECOND and h.writes(s)==1 and h.own(h.read(s,True))['sync']['state'] in ('APPLIED','MATCHED'))
        h.command(s,'trip','refresh');h.command(s,'trip','refresh')
        h.unchanged('repeated snapshots do not repeat write',s,1,h.SECOND)
        h.wait('own actual SDK ETA reaches room',lambda:h.own(h.read(s,True))['eta'] is not None)
        h.command(s,'probe','official')
        h.wait('manual native change is preserved',lambda:h.target(s)==h.FIRST and h.own(h.read(s,True))['sync']['state']=='LOCAL_OVERRIDE')
        count=h.writes(s)
        h.unchanged('manual route never silently reverts',s,count,h.FIRST)
        h.command(s,'trip','reconsider')
        h.wait('fresh confirmation after manual change',lambda:h.pending(s,'FOLLOWER',h.SECOND))
        h.adb(s,'forward','tcp:8089','tcp:8088')
        h.console('/browser/session?client=moatrip-confirmation');h.console_ready=True
        threading.Thread(target=h.heartbeat,daemon=True).start()
        h.console('/vehicle/gear?gear=Drive','PUT')
        h.console('/vehicle/controls?speed=30&accelerator=0&brake=0&steering=0','PUT')
        h.wait('actual VHAL driving state restricts settings but allows short confirmation',lambda:not h.read(s)['parked'] and h.read(s)['uxCanConfirm'])
        h.command(s,'trip','refresh');time.sleep(2)
        h.screenshot(s,'recipient-driving-confirmation')
        h.unchanged('driving never bypasses confirmation',s,count,h.FIRST)
        h.respond(s,True)
        h.wait('driving approval changes only confirmed destination',lambda:h.target(s)==h.SECOND and h.writes(s)==count+1 and h.own(h.read(s,True))['sync']['state'] in ('APPLIED','MATCHED'))
        (h.OUT/'driving-sdk.json').write_text(json.dumps(h.read(s),ensure_ascii=False,indent=2),encoding='utf-8')
        h.console('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0','PUT')
        h.console('/vehicle/gear?gear=Park','PUT')
        h.wait('parked settings restored',lambda:h.read(s)['parked'])
        h.adb(s,'shell','am','force-stop','--user',h.users[s],'dev.moa.trip')
        h.command(s,'trip','refresh')
        h.wait('accepted destination does not replay on restart',lambda:h.writes(s)==0 and h.target(s)==h.SECOND and h.own(h.read(s,True))['sync']['state'] in ('APPLIED','MATCHED'))
        h.adb(s,'emu','geo','fix','127.042','37.487')
        h.wait('fresh real SDK position and ETA after restart',lambda:h.own(h.read(s,True))['eta'] is not None and h.own(h.read(s,True))['location'] is not None)
        h.command(s,'trip','refresh');time.sleep(3)
        h.screenshot(s,'travel-overview')
        for trip,name in [(False,'recipient-sdk'),(True,'recipient-room')]:
            (h.OUT/(name+'.json')).write_text(json.dumps(h.read(s,trip),ensure_ascii=False,indent=2),encoding='utf-8')
except BaseException as e:
    h.results.append({'check':'run completed','passed':False,'error':str(e)});raise
finally:
    if h.console_ready:
        try:
            h.console('/browser/session?client=moatrip-confirmation')
            h.console('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0','PUT')
            h.console('/vehicle/gear?gear=Park','PUT')
            h.console('/browser/session?client=moatrip-confirmation&closing=1')
        finally: h.console_stop.set()
    (h.OUT/(phase+'-results.json')).write_text(json.dumps(h.results,ensure_ascii=False,indent=2),encoding='utf-8')
