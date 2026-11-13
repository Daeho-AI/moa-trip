"""v0.4: actual two-Pleos SDK test of explicit, per-destination confirmation.
Only the dedicated MoaTrip A/B emulators and their test rooms are changed.
GPS and driving restrictions are injected at the emulator/platform boundary, not app fixtures.
"""
import json
import subprocess
import time
import xml.etree.ElementTree as ET
import urllib.request
import threading
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ADB=r'D:\Codex\PleosBossToolchain\android-sdk\platform-tools\adb.exe'
OUT=ROOT/'work/evidence/confirm-e2e'
OUT.mkdir(parents=True,exist_ok=True)
A='emulator-5556'; B='emulator-5558'
users={}; results=[]
console_stop=threading.Event()
console_ready=False
FIRST='36khp37hh8gqcm5nqhe5'; SECOND='36khp2hdj2cfcm5nqhe3'


def adb(serial,*args):
    return subprocess.check_output([ADB,'-s',serial,*args],timeout=40).decode('utf-8',errors='replace').strip()


def read(serial,trip=False,prompt=False):
    name='destination-prompt.json' if prompt else 'trip-state.json' if trip else 'sdk-probe.json'
    for attempt in range(5):
        try:
            return json.loads(adb(serial,'shell','run-as','dev.moa.trip','--user',users[serial],'cat','files/'+name))
        except (json.JSONDecodeError,subprocess.SubprocessError):
            if attempt==4: raise
            time.sleep(.3)


def command(serial,kind,value,**extras):
    args=['shell','am','start','--user',users[serial],'-n','dev.moa.trip/.MainActivity','--es',kind+'_command',value]
    for key,item in extras.items():
        args.extend(['--ez' if isinstance(item,bool) else '--es',key,str(item).lower() if isinstance(item,bool) else str(item)])
    adb(serial,*args)


def active_room_id(serial):
    try:
        prefs=ET.fromstring(adb(serial,'shell','run-as','dev.moa.trip','--user',users[serial],'cat','shared_prefs/moatrip.xml'))
    except subprocess.CalledProcessError:
        # A fresh installation has no preferences file until the first mutation.
        return None
    element=prefs.find("string[@name='roomId']")
    return element.text if element is not None else None


def passed(label):
    print('PASS '+label,flush=True)
    results.append({'check':label,'passed':True})


def wait(label,condition,seconds=100):
    deadline=time.monotonic()+seconds; last=None
    while time.monotonic()<deadline:
        try:
            last=condition()
            if last: passed(label); return last
        except (json.JSONDecodeError,subprocess.SubprocessError,KeyError,TypeError): pass
        time.sleep(1)
    raise AssertionError(f'{label}: timeout; last={last}')


def own(snap): return next(m for m in snap['members'] if m['id']==snap['self']['id'])

def target(serial): return (read(serial).get('destination') or {}).get('poiId')

def revision(serial=A): return read(serial,True)['room']['planRevision']

def writes(serial=B): return read(serial)['writeCount']

def native_ready(serial):
    s=read(serial)
    elapsed=float(adb(serial,'shell','cat','/proc/uptime').split()[0])*1000
    return s['naviConnection']=='CONNECTED' and s['naviMode'] in ('SAFE_GUIDING','ROUTE_GUIDING') and s['parked'] and 0 <= elapsed-s['routeStateAtMs'] < 7000 and s['routeStateAtMs']>0


def pending(serial,kind=None,poi=None):
    p=read(serial,prompt=True)
    return p and (not kind or p['kind']==kind) and (not poi or p['target']['poiId']==poi)


def console(path,method='POST'):
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8089'+path,method=method),timeout=8) as response:
        return response.read().decode()


def heartbeat():
    while not console_stop.wait(1):
        try: console('/browser/session?client=moatrip-confirmation')
        except Exception: pass


def prompt_key(p):
    t=p['target']
    return f"{p['kind']}:{p['roomId']}:{p['hostEpoch']}:{p['revision']}:{t['poiProvider']}:{t['poiId']}:{t['latitude']}:{t['longitude']}"


def respond(serial,approve,key=None):
    command(serial,'trip','respond',prompt_key=key or prompt_key(read(serial,prompt=True)),approve=approve)


def unchanged(label,serial,count,poi,seconds=5):
    time.sleep(seconds)
    assert writes(serial)==count and target(serial)==poi,(label,read(serial))
    passed(label)


def screenshot(serial,name):
    adb(serial,'shell','screencap','-p','/sdcard/moatrip-confirm.png')
    adb(serial,'pull','/sdcard/moatrip-confirm.png',str(OUT/(name+'.png')))


def capture(name):
    for serial,car in [(A,'a'),(B,'b')]:
        for trip,kind in [(False,'sdk'),(True,'room')]:
            (OUT/f'{name}-{car}-{kind}.json').write_text(json.dumps(read(serial,trip),ensure_ascii=False,indent=2),encoding='utf-8')


def run():
    global console_ready
    try:
        for serial in (A,B):
            assert adb(serial,'shell','getprop','ro.boot.qemu.avd_name')==('MoaTripA' if serial==A else 'MoaTripB')
            users[serial]=adb(serial,'shell','am','get-current-user')
            wait(serial+' actual SDK ready',lambda s=serial:native_ready(s))
            if active_room_id(serial):
                command(serial,'trip','refresh')
                wait(serial+' old room restored',lambda s=serial:read(s,True)['serverTime']>time.time()*1000-15000)
                command(serial,'trip','finish')
                wait(serial+' previous test participation ended',lambda s=serial:active_room_id(s) is None)
        adb(A,'emu','geo','fix','127.035','37.49')
        adb(B,'emu','geo','fix','127.042','37.487')
        command(A,'probe','official')
        command(B,'probe','official-b')
        wait('different native destinations before test',lambda:target(A)==FIRST and target(B)==SECOND)
        command(A,'trip','create',title='가을여행',nickname='대호')
        wait('leader prompt before any plan publication',lambda:active_room_id(A) and pending(A,'LEADER',FIRST) and revision()==0)
        command(A,'trip','grant',location=True,eta=True,follow=True)
        command(A,'trip','refresh')
        screenshot(A,'leader-confirmation')
        count=writes(B)
        time.sleep(5)
        assert revision()==0 and writes(B)==count
        passed('leader change never publishes without confirmation')
        respond(A,False)
        time.sleep(5)
        assert revision()==0 and read(A,prompt=True) is None
        passed('leader decline keeps own destination private')
        command(A,'trip','reconsider')
        wait('leader can reopen sharing confirmation',lambda:pending(A,'LEADER',FIRST))
        respond(A,True)
        wait('leader explicit confirmation publishes canonical destination',lambda:revision()==1 and read(A,True)['room']['destination']['poiId']==FIRST)
        rid=read(A,True)['room']['id']
        command(B,'trip','join',code=read(A,True)['self']['inviteCode'],nickname='민수')
        wait('recipient receives confirmation instead of automatic route',lambda:read(B,True)['room']['id']==rid and pending(B,'FOLLOWER',FIRST))
        command(B,'trip','grant',location=True,eta=True,follow=True)
        unchanged('legacy auto-follow grant cannot write navigation',B,count,SECOND)
        command(B,'trip','refresh')
        screenshot(B,'recipient-confirmation')
        respond(B,False)
        wait('recipient can keep current destination',lambda:own(read(B,True))['sync']['state']=='DECLINED')
        unchanged('decline and repeated snapshots preserve native route',B,count,SECOND)
        adb(B,'shell','am','force-stop','--user',users[B],'dev.moa.trip')
        command(B,'trip','refresh')
        wait('decline survives process restart without route write',lambda:writes(B)==0 and own(read(B,True))['sync']['state']=='DECLINED')
        count=writes(B)
        command(B,'trip','reconsider')
        wait('recipient can reconsider the same proposal',lambda:pending(B,'FOLLOWER',FIRST))
        respond(B,True)
        wait('recipient approval writes exactly once and native readback confirms',lambda:target(B)==FIRST and writes(B)==count+1 and own(read(B,True))['sync']['state'] in ['APPLIED','MATCHED'])
        adb(A,'emu','geo','fix','127.035','37.49')
        adb(B,'emu','geo','fix','127.042','37.487')
        wait('both real FusedLocation samples and native ETAs are shared',lambda:read(A,True)['assembly']['known']==2 and all(m['location'] is not None for m in read(A,True)['members']))
        capture('01-approved')
        count=writes(B)
        for _ in range(3): command(B,'trip','refresh')
        unchanged('repeated snapshots never duplicate approved write',B,count,FIRST)
        command(A,'probe','official-b')
        wait('every subsequent leader change needs confirmation',lambda:pending(A,'LEADER',SECOND) and revision()==1)
        unchanged('second leader destination stays local until confirmed',B,count,FIRST)
        respond(A,True)
        wait('second recipient proposal awaits separate confirmation',lambda:revision(B)==2 and pending(B,'FOLLOWER',SECOND))
        old_key=prompt_key(read(B,prompt=True))
        unchanged('previous approval does not authorize new destination',B,count,FIRST)
        # A superseding proposal invalidates an already displayed confirmation.
        command(A,'probe','official')
        wait('new leader proposal detected while recipient deciding',lambda:pending(A,'LEADER',FIRST))
        respond(A,True)
        wait('superseding revision delivered',lambda:revision(B)==3)
        respond(B,True,old_key)
        unchanged('stale confirmation cannot apply a superseded destination',B,count,FIRST)
        adb(B,'shell','am','start','--user',users[B],'-n','ai.umos.maps.android.navigation.app/.MainActivity')
        command(A,'probe','official-b')
        wait('latest leader proposal ready',lambda:pending(A,'LEADER',SECOND))
        respond(A,True)
        wait('latest recipient proposal ready',lambda:revision(B)==4 and pending(B,'FOLLOWER',SECOND))
        notification=adb(B,'shell','dumpsys','notification')
        assert 'id=7302' in notification, 'Background proposal notification missing'
        unchanged('background notification never changes native destination',B,count,FIRST)
        # The official virtual-console API supplies VHAL gear/speed on this Connect 2.1.0 image.
        adb(B,'forward','tcp:8089','tcp:8088')
        console('/browser/session?client=moatrip-confirmation')
        console_ready=True
        threading.Thread(target=heartbeat,daemon=True).start()
        console('/vehicle/gear?gear=Drive','PUT')
        output=console('/vehicle/controls?speed=30&accelerator=0&brake=0&steering=0','PUT')
        (OUT/'driving-console-response.json').write_text(output,encoding='utf-8')
        wait('actual platform driving restrictions delivered',lambda:not read(B)['parked'] and read(B)['uxCanConfirm'])
        command(B,'trip','refresh')
        screenshot(B,'recipient-driving-confirmation')
        unchanged('driving restrictions do not bypass destination confirmation',B,count,FIRST)
        respond(B,True)
        wait('short confirmation works under platform driving restrictions',lambda:target(B)==SECOND and writes(B)==count+1 and own(read(B,True))['sync']['state'] in ['APPLIED','MATCHED'])
        console('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0','PUT')
        console('/vehicle/gear?gear=Park','PUT')
        wait('parked controls restored',lambda:read(B)['parked'])
        count=writes(B)
        adb(B,'shell','am','force-stop','--user',users[B],'dev.moa.trip')
        command(B,'trip','refresh')
        wait('restart after approval does not replay request',lambda:target(B)==SECOND and writes(B)==0 and own(read(B,True))['sync']['state'] in ['APPLIED','MATCHED'])
        command(B,'probe','official')
        wait('manual participant route change is preserved',lambda:target(B)==FIRST and own(read(B,True))['sync']['state']=='LOCAL_OVERRIDE')
        count=writes(B)
        unchanged('manual route is never silently overwritten',B,count,FIRST)
        command(B,'trip','reconsider')
        wait('manual override can be explicitly reconsidered',lambda:pending(B,'FOLLOWER',SECOND))
        respond(B,True)
        wait('final destination matches after fresh confirmation',lambda:target(B)==SECOND and own(read(B,True))['sync']['state'] in ['APPLIED','MATCHED'])
        adb(A,'emu','geo','fix','127.035','37.49'); adb(B,'emu','geo','fix','127.042','37.487')
        wait('review room has two live locations and ETAs',lambda:read(A,True)['assembly']['known']==2 and all(m['location'] is not None for m in read(A,True)['members']))
        command(A,'trip','refresh'); command(B,'trip','refresh')
        time.sleep(3)
        screenshot(A,'travel-overview'); screenshot(B,'participant-overview')
        capture('02-final')
    except BaseException as error:
        results.append({'check':'run completed','passed':False,'error':str(error)})
        raise
    finally:
        if console_ready:
            try:
                console('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0','PUT')
                console('/vehicle/gear?gear=Park','PUT')
                console('/browser/session?client=moatrip-confirmation&closing=1')
            except Exception: pass
        console_stop.set()
        (OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':
    run()
