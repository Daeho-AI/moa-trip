"""Single-process trip-room service. Native navigation is always controlled by each car."""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
import time
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ConfigDict, Field, model_validator


def now_ms():
    return int(time.time()*1000)


def uid():
    return uuid.uuid4().hex


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


class Model(BaseModel):
    model_config = ConfigDict(extra='forbid', allow_inf_nan=False)


class Place(Model):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    name: str = Field(min_length=1, max_length=150)
    address: str = Field(max_length=300)
    poiId: str = Field(min_length=1, max_length=160)
    poiSubId: str | None = Field(max_length=80)
    poiProvider: str = Field(default='', max_length=80)
    displayLatitude: float | None = Field(default=None, ge=-90, le=90)
    displayLongitude: float | None = Field(default=None, ge=-180, le=180)

    @model_validator(mode='after')
    def coordinates(self):
        if self.latitude == 0 and self.longitude == 0:
            raise ValueError('A real destination is required')
        return self


class CreateRoom(Model):
    title: str = Field(min_length=1, max_length=40)
    nickname: str = Field(min_length=1, max_length=16)
    durationHours: Literal[12, 24, 72] = 24


class JoinRoom(Model):
    code: str = Field(min_length=8, max_length=9)
    nickname: str = Field(min_length=1, max_length=16)


class Grants(Model):
    locationEnabled: bool = False
    etaEnabled: bool = False
    destinationEnabled: bool = False
    sharedStopsEnabled: bool = False
    hostEpoch: int = Field(ge=1)

    @model_validator(mode='after')
    def dependency(self):
        if self.sharedStopsEnabled and not self.destinationEnabled:
            raise ValueError('Shared stops require destination following')
        return self


class PlanChange(Model):
    leaderConfirmed: Literal[True]
    expectedPlanRevision: int = Field(ge=0)
    hostEpoch: int = Field(ge=1)
    idempotencyKey: str = Field(min_length=8, max_length=100)
    destination: Place


class DestinationDecision(Model):
    planRevision: int = Field(ge=1)
    hostEpoch: int = Field(ge=1)
    accepted: bool


class Location(Model):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    heading: float = Field(ge=0, le=360)
    sampleAgeMs: int = Field(ge=0, le=86400000)

    @model_validator(mode='after')
    def coordinates(self):
        if self.latitude == 0 and self.longitude == 0:
            raise ValueError('Invalid position')
        return self


class Eta(Model):
    planRevision: int = Field(ge=1)
    remainTimeSec: int = Field(ge=0, le=604800)
    remainDistanceM: int = Field(ge=0, le=5000000)
    sampleAgeMs: int = Field(ge=0, le=86400000)


class Telemetry(Model):
    sequence: int = Field(ge=1)
    source: Literal['PLEOS_SDK', 'FIXTURE'] = 'PLEOS_SDK'
    location: Location | None = None
    eta: Eta | None = None


class Receipt(Model):
    planRevision: int = Field(ge=0)
    state: Literal['WAITING_DESTINATION', 'NEEDS_CONFIRMATION', 'WAITING_CONNECTION',
        'DEFERRED_POLICY', 'APPLYING', 'APPLIED', 'MATCHED', 'LOCAL_OVERRIDE',
        'WRITE_UNCERTAIN', 'CAPACITY_CONFLICT', 'FAILED', 'ARRIVED', 'DECLINED']
    reason: str = Field(default='', max_length=160)


class Broadcast(Model):
    enabled: bool


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, token_hash TEXT UNIQUE, expires INTEGER);
        CREATE TABLE IF NOT EXISTS rooms(id TEXT PRIMARY KEY, data TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS actions(room TEXT, actor TEXT, key TEXT, body_hash TEXT, response TEXT,
          PRIMARY KEY(room,actor,key));
        ''')
        self.db.commit()
        self.live = {}
        self.subscribers = defaultdict(set)
        self.rates = defaultdict(deque)

    def rate(self, key, limit, interval=60):
        current=time.monotonic(); queue=self.rates[key]
        while queue and queue[0] < current-interval: queue.popleft()
        if len(queue)>=limit: raise HTTPException(429, 'Too many attempts')
        queue.append(current)

    def session(self, authorization):
        if not authorization or not authorization.startswith('Bearer '): raise HTTPException(401, 'Session required')
        row=self.db.execute('SELECT id,expires FROM sessions WHERE token_hash=?',(digest(authorization[7:]),)).fetchone()
        if not row or row[1]<=now_ms(): raise HTTPException(401, 'Session expired')
        return row[0]

    def all_rooms(self):
        return [json.loads(row[0]) for row in self.db.execute('SELECT data FROM rooms')]

    def room(self, room_id, session_id, active=False, allow_finished=False):
        row=self.db.execute('SELECT data FROM rooms WHERE id=?',(room_id,)).fetchone()
        if not row: raise HTTPException(404, 'Room not found')
        room=json.loads(row[0])
        member=next((m for m in room['members'] if m['sessionId']==session_id), None)
        if not member: raise HTTPException(404, 'Room not found')
        if member['participationState']!='ACTIVE' and not allow_finished:
            raise HTTPException(410,'Participation ended')
        if room['expiresAt']<=now_ms(): room['state']='EXPIRED'
        if active and room['state']!='ACTIVE': raise HTTPException(410,'Trip ended')
        return room,member

    def save(self, room):
        self.db.execute('INSERT OR REPLACE INTO rooms(id,data) VALUES(?,?)',(room['id'],json.dumps(room,ensure_ascii=False)))

    def publish(self, room_id):
        for queue in list(self.subscribers[room_id]):
            if queue.empty(): queue.put_nowait(True)

    def add_member(self, session, nickname, room, leader=False):
        member={'id':uid(),'sessionId':session,'nickname':nickname,'leader':leader,
                'participationState':'ACTIVE','joinedAt':now_ms(),
                'grants':{'locationEnabled':False,'etaEnabled':False,'destinationEnabled':False,
                          'sharedStopsEnabled':False,'hostEpoch':room['hostEpoch'],'expiresAt':0},
                'sync':{'planRevision':0,'state':'WAITING_DESTINATION','reason':''}}
        room['members'].append(member)
        return member

    def ensure_no_active_room(self, session):
        for room in self.all_rooms():
            if room['state']=='ACTIVE' and room['expiresAt']>now_ms() and any(
                    m['sessionId']==session and m['participationState']=='ACTIVE' for m in room['members']):
                raise HTTPException(409,'Already participating in a trip')

    def snapshot(self, room, own):
        current=now_ms(); active=room['state']=='ACTIVE' and room['expiresAt']>current and own['participationState']=='ACTIVE'
        members=[]; known=[]; expected=0
        for member in room['members']:
            live=self.live.get(member['id'],{})
            grant=member['grants']; allowed=active and grant['expiresAt']>current and member['participationState']=='ACTIVE'
            public={k:member[k] for k in ['id','nickname','leader','participationState','joinedAt']}
            public['sync']=member['sync']
            public['online']=active and current-live.get('receivedAt',0)<15000
            public['source']=live.get('source','PLEOS_SDK')
            public['location']=None; public['eta']=None
            position=live.get('location')
            if allowed and grant['locationEnabled'] and position:
                age=current-position['observedAt']
                if age<60000:
                    public['location']={**position,'ageMs':age,'stale':age>=15000}
            eta=live.get('eta')
            receipt=member['sync']
            if (allowed and grant['etaEnabled'] and eta and eta['planRevision']==room['planRevision']
                    and receipt['planRevision']==room['planRevision'] and receipt['state'] in ('APPLIED','MATCHED')):
                age=current-eta['observedAt']
                if age<15000:
                    public['eta']={**eta,'ageMs':age,'arrivalAt':eta['observedAt']+eta['remainTimeSec']*1000}
            if member['participationState']=='ACTIVE':
                expected+=1
                if public['eta']: known.append(public['eta']['arrivalAt'])
            members.append(public)
        return {'serverTime':current,'room':{k:room[k] for k in ['id','title','state','hostEpoch','planRevision','expiresAt','destination','leaderBroadcastEnabled']},
                'self':{'id':own['id'],'leader':own['leader'],'grants':own['grants'],
                        'destinationDecision':own.get('destinationDecision'),
                        'inviteCode':room['inviteCode'] if own['leader'] else None,'inviteExpiresAt':room['inviteExpiresAt'] if own['leader'] else None},
                'members':members,'assembly':{'expected':expected,'known':len(known),
                    'complete':active and expected>0 and len(known)==expected,
                    'latestKnownArrivalAt':max(known) if known else None}}


def create_app(db_path=None, allow_fixture=False):
    path=Path(db_path or os.environ.get('MOATRIP_DATABASE',Path(__file__).resolve().parents[1]/'work/server/moatrip.db'))
    path.parent.mkdir(parents=True,exist_ok=True)
    store=Store(path)

    @asynccontextmanager
    async def lifespan(_):
        yield
        store.db.close()

    app=FastAPI(title='MoaTrip core',version='0.4.0',lifespan=lifespan)
    app.state.store=store

    def identity(request): return store.session(request.headers.get('authorization'))
    def peer(request): return request.client.host if request.client else 'unknown'

    @app.get('/health')
    async def health(): return {'status':'ok','version':'0.4.0','source':'MoaTrip','serverTime':now_ms()}

    @app.post('/v1/device-sessions')
    async def session(request: Request):
        store.rate(('session',peer(request)),30)
        token=secrets.token_urlsafe(32); session_id=uid()
        with store.db:
            store.db.execute('INSERT INTO sessions VALUES(?,?,?)',(session_id,digest(token),now_ms()+30*86400000))
        return {'token':token,'sessionId':session_id}

    @app.post('/v1/rooms')
    async def create(body: CreateRoom, request: Request):
        actor=identity(request); store.ensure_no_active_room(actor)
        room={'id':uid(),'title':body.title,'state':'ACTIVE','hostEpoch':1,'planRevision':0,
              'expiresAt':now_ms()+body.durationHours*3600000,'destination':None,'leaderBroadcastEnabled':True,
              'inviteCode':''.join(secrets.choice('ABCDEFGHJKLMNPQRSTUVWXYZ23456789') for _ in range(8)),
              'inviteExpiresAt':now_ms()+1800000,'members':[]}
        own=store.add_member(actor,body.nickname,room,True)
        with store.db: store.save(room)
        return store.snapshot(room,own)

    @app.post('/v1/rooms/join')
    async def join(body: JoinRoom, request: Request):
        actor=identity(request); store.rate(('join',peer(request)),30)
        code=body.code.replace('-','').upper()
        room=next((r for r in store.all_rooms() if r['inviteCode']==code and r['state']=='ACTIVE'
                   and r['inviteExpiresAt']>now_ms() and r['expiresAt']>now_ms()),None)
        if not room: raise HTTPException(404,'Invitation unavailable')
        own=next((m for m in room['members'] if m['sessionId']==actor),None)
        if own and own['participationState']=='ACTIVE': return store.snapshot(room,own)
        store.ensure_no_active_room(actor)
        if len([m for m in room['members'] if m['participationState']=='ACTIVE'])>=5: raise HTTPException(409,'Room is full')
        if own: room['members'].remove(own)
        own=store.add_member(actor,body.nickname,room)
        with store.db: store.save(room)
        store.publish(room['id'])
        return store.snapshot(room,own)

    @app.get('/v1/rooms/{room_id}')
    async def snapshot(room_id: str, request: Request):
        room,own=store.room(room_id,identity(request))
        return store.snapshot(room,own)

    @app.put('/v1/rooms/{room_id}/grants/me')
    async def grants(room_id: str, body: Grants, request: Request):
        room,own=store.room(room_id,identity(request),True)
        if own['participationState']!='ACTIVE': raise HTTPException(409,'Trip already finished')
        if body.hostEpoch!=room['hostEpoch']: raise HTTPException(409,'Leader changed')
        # Shared-stop workflow is intentionally not enabled in this destination-only core.
        if body.sharedStopsEnabled: raise HTTPException(422,'Shared stops are not available in this build')
        own['grants']={**body.model_dump(),'expiresAt':room['expiresAt']}
        live=store.live.get(own['id'],{})
        if not body.locationEnabled: live.pop('location',None)
        if not body.etaEnabled: live.pop('eta',None)
        with store.db: store.save(room)
        store.publish(room_id)
        return store.snapshot(room,own)

    @app.put('/v1/rooms/{room_id}/plan')
    async def plan(room_id: str, body: PlanChange, request: Request):
        actor=identity(request); room,own=store.room(room_id,actor,True)
        if not own['leader'] or not room['leaderBroadcastEnabled'] or own['participationState']!='ACTIVE': raise HTTPException(403,'Leader publication is paused or unavailable')
        body_hash=digest(body.model_dump_json())
        prior=store.db.execute('SELECT body_hash,response FROM actions WHERE room=? AND actor=? AND key=?',(room_id,actor,body.idempotencyKey)).fetchone()
        if prior:
            if prior[0]!=body_hash: raise HTTPException(409,'Idempotency key reused')
            return json.loads(prior[1])
        if body.expectedPlanRevision!=room['planRevision'] or body.hostEpoch!=room['hostEpoch']: raise HTTPException(409,'Plan changed')
        room['destination']=body.destination.model_dump(); room['planRevision']+=1
        for member in room['members']:
            store.live.get(member['id'],{}).pop('eta',None)
            member.pop('destinationDecision',None)
            member['sync']={'planRevision':room['planRevision'],'state':'NEEDS_CONFIRMATION','reason':''}
        response={'planRevision':room['planRevision']}
        with store.db:
            store.save(room)
            store.db.execute('INSERT INTO actions VALUES(?,?,?,?,?)',(room_id,actor,body.idempotencyKey,body_hash,json.dumps(response)))
        store.publish(room_id)
        return response

    @app.put('/v1/rooms/{room_id}/destination-decision/me')
    async def destination_decision(room_id: str, body: DestinationDecision, request: Request):
        room,own=store.room(room_id,identity(request),True)
        if own['leader']: raise HTTPException(403,'Participant only')
        if body.planRevision!=room['planRevision'] or body.hostEpoch!=room['hostEpoch']:
            raise HTTPException(409,'Destination proposal changed')
        if room['destination'] is None: raise HTTPException(409,'No destination proposal')
        own['destinationDecision']=body.model_dump()
        own['sync']={'planRevision':body.planRevision,'state':'NEEDS_CONFIRMATION' if body.accepted else 'DECLINED','reason':''}
        store.live.get(own['id'],{}).pop('eta',None)
        with store.db: store.save(room)
        store.publish(room_id)
        return store.snapshot(room,own)

    @app.put('/v1/rooms/{room_id}/sync-status/me')
    async def receipt(room_id: str, body: Receipt, request: Request):
        room,own=store.room(room_id,identity(request),True)
        if body.planRevision!=room['planRevision']: raise HTTPException(409,'Plan changed')
        decision=own.get('destinationDecision',{})
        if not own['leader'] and body.state in ('APPLYING','APPLIED') and not (
                decision.get('accepted') is True and decision.get('planRevision')==body.planRevision
                and decision.get('hostEpoch')==room['hostEpoch']):
            raise HTTPException(409,'Explicit approval for this destination is required')
        own['sync']=body.model_dump()
        with store.db: store.save(room)
        store.publish(room_id)
        return {'ok':True}

    @app.put('/v1/rooms/{room_id}/telemetry/me')
    async def telemetry(room_id: str, body: Telemetry, request: Request):
        room,own=store.room(room_id,identity(request),True)
        if own['participationState']!='ACTIVE': raise HTTPException(410,'Trip finished')
        if body.source=='FIXTURE' and not allow_fixture: raise HTTPException(422,'Fixture source disabled')
        previous=store.live.get(own['id'],{})
        if body.sequence<=previous.get('sequence',0): return {'accepted':False,'reason':'OLD_SEQUENCE'}
        grant=own['grants']; current=now_ms()
        allowed=grant['expiresAt']>current
        sample={'sequence':body.sequence,'receivedAt':current,'source':body.source}
        if allowed and grant['locationEnabled'] and body.location:
            sample['location']={**body.location.model_dump(exclude={'sampleAgeMs'}),'observedAt':current-body.location.sampleAgeMs}
        if allowed and grant['etaEnabled'] and body.eta and body.eta.planRevision==room['planRevision']:
            sample['eta']={**body.eta.model_dump(exclude={'sampleAgeMs'}),'observedAt':current-body.eta.sampleAgeMs}
        store.live[own['id']]=sample
        store.publish(room_id)
        return {'accepted':True,'serverTime':current}

    @app.put('/v1/rooms/{room_id}/leader-broadcast')
    async def broadcast(room_id: str, body: Broadcast, request: Request):
        room,own=store.room(room_id,identity(request),True)
        if not own['leader']: raise HTTPException(403,'Leader only')
        room['leaderBroadcastEnabled']=body.enabled
        with store.db: store.save(room)
        store.publish(room_id)
        return store.snapshot(room,own)

    @app.post('/v1/rooms/{room_id}/members/me/finish')
    async def finish(room_id: str, request: Request):
        room,own=store.room(room_id,identity(request),True,allow_finished=True)
        own['participationState']='FINISHED'
        own['grants']={**own['grants'],'locationEnabled':False,'etaEnabled':False,'destinationEnabled':False,'sharedStopsEnabled':False,'expiresAt':0}
        if own['leader']: room['leaderBroadcastEnabled']=False
        store.live.pop(own['id'],None)
        with store.db: store.save(room)
        store.publish(room_id)
        return store.snapshot(room,own)

    @app.post('/v1/rooms/{room_id}/close')
    async def close(room_id: str, request: Request):
        room,own=store.room(room_id,identity(request),True)
        if not own['leader']: raise HTTPException(403,'Leader only')
        room['state']='CLOSED'
        for member in room['members']: store.live.pop(member['id'],None)
        with store.db: store.save(room)
        store.publish(room_id)
        return store.snapshot(room,own)

    @app.websocket('/v1/rooms/{room_id}/stream')
    async def stream(websocket: WebSocket, room_id: str):
        queue=asyncio.Queue(maxsize=1)
        try:
            actor=store.session(websocket.headers.get('authorization'))
            store.room(room_id,actor)
            await websocket.accept()
            store.subscribers[room_id].add(queue)
            while True:
                room,own=store.room(room_id,actor)
                await websocket.send_json(store.snapshot(room,own))
                if room['state']!='ACTIVE': break
                try: await asyncio.wait_for(queue.get(),timeout=5)
                except asyncio.TimeoutError: pass
        except (HTTPException,WebSocketDisconnect,RuntimeError):
            pass
        finally:
            store.subscribers[room_id].discard(queue)
            try: await websocket.close()
            except RuntimeError: pass

    return app


app=create_app() if os.environ.get('MOATRIP_TESTING')!='1' else None
