import os
os.environ['MOATRIP_TESTING']='1'
import pytest
from fastapi.testclient import TestClient
from server.app import create_app, now_ms

PLACE={'latitude':37.48544722,'longitude':127.03636666,'name':'42dot','address':'공식 SDK 예제',
       'poiId':'36khp37hh8gqcm5nqhe5','poiSubId':'0','poiProvider':''}


def session(client):
    result=client.post('/v1/device-sessions').json()
    return {'Authorization':'Bearer '+result['token']}


def room(client):
    host=session(client)
    response=client.post('/v1/rooms',headers=host,json={'title':'테스트 여행','nickname':'방장'})
    assert response.status_code==200
    data=response.json()
    return host,data['room']['id'],data['self']['inviteCode']


def publish(client,host,rid,revision=0,key='change-0001',place=None):
    return client.put(f'/v1/rooms/{rid}/plan',headers=host,json={
        'leaderConfirmed':True,'expectedPlanRevision':revision,'hostEpoch':1,'idempotencyKey':key,'destination':place or PLACE})


def grant(client,headers,rid,position=True,eta=True,follow=True):
    return client.put(f'/v1/rooms/{rid}/grants/me',headers=headers,json={
        'hostEpoch':1,'locationEnabled':position,'etaEnabled':eta,'destinationEnabled':follow})


def sample(client,headers,rid,seq=1,age=0,eta_age=0,source='PLEOS_SDK'):
    return client.put(f'/v1/rooms/{rid}/telemetry/me',headers=headers,json={
        'sequence':seq,'source':source,'location':{'latitude':37.49,'longitude':127.04,'heading':90,'sampleAgeMs':age},
        'eta':{'planRevision':1,'remainTimeSec':120,'remainDistanceM':900,'sampleAgeMs':eta_age}})


@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(tmp_path/'rooms.db')) as client:
        yield client


def test_destination_late_join_and_private_projection(client):
    host,rid,code=room(client)
    assert publish(client,host,rid).json()['planRevision']==1
    follower=session(client)
    joined=client.post('/v1/rooms/join',headers=follower,json={'code':code,'nickname':'후발'}).json()
    assert joined['room']['destination']['poiId']==PLACE['poiId']
    assert joined['room']['planRevision']==1
    assert not joined['self']['grants']['destinationEnabled']
    assert joined['self']['inviteCode'] is None
    assert all('sessionId' not in member and 'grants' not in member for member in joined['members'])


def test_nonmember_and_follower_cannot_control_plan(client):
    host,rid,code=room(client); other=session(client)
    assert client.get(f'/v1/rooms/{rid}',headers=other).status_code==404
    client.post('/v1/rooms/join',headers=other,json={'code':code,'nickname':'B'})
    assert publish(client,other,rid).status_code==403
    assert client.post(f'/v1/rooms/{rid}/close',headers=other).status_code==403


def test_plan_cas_and_idempotency(client):
    host,rid,_=room(client)
    assert publish(client,host,rid).status_code==200
    assert publish(client,host,rid).json()['planRevision']==1
    assert publish(client,host,rid,key='different-key').status_code==409
    changed={**PLACE,'name':'changed'}
    assert publish(client,host,rid,place=changed).status_code==409


def test_no_consent_no_coordinates_then_revoke(client):
    host,rid,_=room(client); publish(client,host,rid)
    sample(client,host,rid)
    snap=client.get(f'/v1/rooms/{rid}',headers=host).json()
    assert snap['members'][0]['location'] is None
    assert snap['members'][0]['eta'] is None
    assert grant(client,host,rid).status_code==200
    sample(client,host,rid,seq=2)
    assert client.get(f'/v1/rooms/{rid}',headers=host).json()['members'][0]['location'] is not None
    grant(client,host,rid,position=False,eta=False)
    assert client.get(f'/v1/rooms/{rid}',headers=host).json()['members'][0]['location'] is None


def test_sdk_readback_receipt_required_for_eta_and_missing_car_kept(client):
    host,rid,code=room(client); publish(client,host,rid); grant(client,host,rid)
    other=session(client); client.post('/v1/rooms/join',headers=other,json={'code':code,'nickname':'B'})
    sample(client,host,rid)
    assert client.get(f'/v1/rooms/{rid}',headers=host).json()['assembly']['known']==0
    client.put(f'/v1/rooms/{rid}/sync-status/me',headers=host,json={'planRevision':1,'state':'MATCHED'})
    snap=client.get(f'/v1/rooms/{rid}',headers=host).json()
    assert snap['assembly']['known']==1 and snap['assembly']['expected']==2
    assert snap['assembly']['complete'] is False
    assert snap['members'][0]['eta']['arrivalAt']==snap['members'][0]['eta']['observedAt']+120000


def test_location_age_does_not_refresh_old_eta(client):
    host,rid,_=room(client); publish(client,host,rid); grant(client,host,rid)
    client.put(f'/v1/rooms/{rid}/sync-status/me',headers=host,json={'planRevision':1,'state':'APPLIED'})
    sample(client,host,rid,age=100,eta_age=20000)
    member=client.get(f'/v1/rooms/{rid}',headers=host).json()['members'][0]
    assert member['location'] is not None and member['eta'] is None
    sample(client,host,rid,seq=2,age=61000)
    assert client.get(f'/v1/rooms/{rid}',headers=host).json()['members'][0]['location'] is None


def test_out_of_order_telemetry_does_not_replace_latest(client):
    host,rid,_=room(client); publish(client,host,rid); grant(client,host,rid)
    assert sample(client,host,rid,seq=5).json()['accepted']
    assert sample(client,host,rid,seq=4,age=60000).json()['accepted'] is False
    assert client.get(f'/v1/rooms/{rid}',headers=host).json()['members'][0]['location'] is not None


def test_finish_is_personal_and_does_not_clear_shared_destination(client):
    host,rid,code=room(client); publish(client,host,rid)
    other=session(client); client.post('/v1/rooms/join',headers=other,json={'code':code,'nickname':'B'})
    client.post(f'/v1/rooms/{rid}/members/me/finish',headers=host)
    snap=client.get(f'/v1/rooms/{rid}',headers=other).json()
    assert snap['room']['state']=='ACTIVE' and snap['room']['destination']['poiId']==PLACE['poiId']
    assert snap['assembly']['expected']==1
    assert snap['room']['leaderBroadcastEnabled'] is False


def test_invalid_grant_source_and_coordinates_fail(client):
    host,rid,_=room(client)
    bad=client.put(f'/v1/rooms/{rid}/grants/me',headers=host,json={'hostEpoch':1,'sharedStopsEnabled':True})
    assert bad.status_code==422
    assert sample(client,host,rid,source='FIXTURE').status_code==422
    assert publish(client,host,rid,place={**PLACE,'latitude':0,'longitude':0}).status_code==422


def test_websocket_initial_snapshot_and_plan_update(client):
    host,rid,_=room(client)
    with client.websocket_connect(f'/v1/rooms/{rid}/stream',headers=host) as ws:
        assert ws.receive_json()['room']['planRevision']==0
        assert publish(client,host,rid).status_code==200
        assert ws.receive_json()['room']['planRevision']==1


def test_restart_preserves_consent_and_plan_but_not_location(tmp_path):
    path=tmp_path/'restart.db'
    with TestClient(create_app(path)) as first:
        host,rid,_=room(first); publish(first,host,rid); grant(first,host,rid); sample(first,host,rid)
    with TestClient(create_app(path)) as second:
        snap=second.get(f'/v1/rooms/{rid}',headers=host).json()
        assert snap['room']['planRevision']==1
        assert snap['self']['grants']['locationEnabled'] is True
        assert snap['members'][0]['location'] is None


def test_plan_change_invalidates_previous_eta(client):
    host,rid,_=room(client); publish(client,host,rid); grant(client,host,rid)
    client.put(f'/v1/rooms/{rid}/sync-status/me',headers=host,json={'planRevision':1,'state':'APPLIED'})
    sample(client,host,rid)
    assert publish(client,host,rid,revision=1,key='second-change',place={**PLACE,'poiId':'new-place'}).status_code==200
    snap=client.get(f'/v1/rooms/{rid}',headers=host).json()
    assert snap['members'][0]['eta'] is None
    assert client.put(f'/v1/rooms/{rid}/sync-status/me',headers=host,json={'planRevision':1,'state':'APPLIED'}).status_code==409


def test_finished_member_cannot_keep_reading_or_controlling_trip(client):
    host,rid,code=room(client); publish(client,host,rid)
    other=session(client); client.post('/v1/rooms/join',headers=other,json={'code':code,'nickname':'B'})
    grant(client,other,rid); sample(client,other,rid)
    result=client.post(f'/v1/rooms/{rid}/members/me/finish',headers=host)
    assert result.status_code==200
    assert all(m['location'] is None for m in result.json()['members'])
    assert client.get(f'/v1/rooms/{rid}',headers=host).status_code==410
    assert client.put(f'/v1/rooms/{rid}/leader-broadcast',headers=host,json={'enabled':True}).status_code==410
    assert client.post(f'/v1/rooms/{rid}/members/me/finish',headers=host).status_code==200
    assert client.get(f'/v1/rooms/{rid}',headers=other).status_code==200


def test_leader_must_confirm_each_publication(client):
    host,rid,_=room(client)
    body={'expectedPlanRevision':0,'hostEpoch':1,'idempotencyKey':'unconfirmed-01','destination':PLACE}
    assert client.put(f'/v1/rooms/{rid}/plan',headers=host,json=body).status_code==422
    assert client.put(f'/v1/rooms/{rid}/plan',headers=host,json={**body,'leaderConfirmed':False}).status_code==422
    assert client.get(f'/v1/rooms/{rid}',headers=host).json()['room']['planRevision']==0
    assert publish(client,host,rid).status_code==200


def test_blanket_grant_cannot_replace_per_revision_approval(client):
    host,rid,code=room(client); publish(client,host,rid)
    car=session(client)
    client.post('/v1/rooms/join',headers=car,json={'code':code,'nickname':'B'})
    grant(client,car,rid,follow=True)
    receipt=f'/v1/rooms/{rid}/sync-status/me'
    assert client.put(receipt,headers=car,json={'planRevision':1,'state':'APPLYING'}).status_code==409
    decision=f'/v1/rooms/{rid}/destination-decision/me'
    approve={'planRevision':1,'hostEpoch':1,'accepted':True}
    assert client.put(decision,headers=car,json=approve).status_code==200
    assert client.put(receipt,headers=car,json={'planRevision':1,'state':'APPLYING'}).status_code==200
    assert publish(client,host,rid,1,'change-0002',{**PLACE,'poiId':'new-place'}).status_code==200
    assert client.put(receipt,headers=car,json={'planRevision':2,'state':'APPLYING'}).status_code==409
    assert client.put(decision,headers=car,json=approve).status_code==409
    assert client.put(decision,headers=car,json={**approve,'planRevision':2,'hostEpoch':2}).status_code==409


def test_decline_persists_and_repeated_delivery_does_not_approve(client):
    host,rid,code=room(client); publish(client,host,rid)
    car=session(client)
    client.post('/v1/rooms/join',headers=car,json={'code':code,'nickname':'B'})
    path=f'/v1/rooms/{rid}/destination-decision/me'
    decline={'planRevision':1,'hostEpoch':1,'accepted':False}
    for _ in range(2):
        snap=client.put(path,headers=car,json=decline).json()
        assert snap['self']['destinationDecision']==decline
        assert snap['members'][1]['sync']['state']=='DECLINED'
    assert client.get(f'/v1/rooms/{rid}',headers=car).json()['self']['destinationDecision']==decline
    assert client.put(f'/v1/rooms/{rid}/sync-status/me',headers=car,json={'planRevision':1,'state':'APPLIED'}).status_code==409
    assert client.put(path,headers=host,json=decline).status_code==403
    assert publish(client,host,rid,1,'change-0002').status_code==200
    assert client.get(f'/v1/rooms/{rid}',headers=car).json()['self']['destinationDecision'] is None
