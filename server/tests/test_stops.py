from test_rooms import client, session, room, publish, PLACE, grant, sample

STOP={**PLACE,'poiId':'stop','name':'공동 휴게소','latitude':37.4}

def setup(client):
    host,rid,code=room(client);publish(client,host,rid)
    follower=session(client);client.post('/v1/rooms/join',headers=follower,json={'code':code,'nickname':'참여'})
    return host,follower,rid

def propose(client,headers,rid,previous=None,key='stop-key-01'):
    return client.put(f'/v1/rooms/{rid}/stop-proposal',headers=headers,json={
        'leaderConfirmed':True,'planRevision':1,'hostEpoch':1,'expectedStopId':previous,'idempotencyKey':key,'place':STOP})

def choose(client,headers,rid,sid,accept=True):
    return client.put(f'/v1/rooms/{rid}/stop-decision/me',headers=headers,json={
        'stopId':sid,'planRevision':1,'hostEpoch':1,'accepted':accept})

def status(client,headers,rid,sid,state='APPLIED'):
    return client.put(f'/v1/rooms/{rid}/stop-status/me',headers=headers,json={'stopId':sid,'state':state})

def test_additive_stop_never_changes_destination_plan_and_requires_leader(client):
    host,follower,rid=setup(client)
    assert propose(client,follower,rid).status_code==403
    sid=propose(client,host,rid).json()['stopId']
    snap=client.get(f'/v1/rooms/{rid}',headers=follower).json()
    assert snap['room']['planRevision']==1 and snap['room']['destination']['poiId']==PLACE['poiId']
    assert snap['room']['stopProposal']['id']==sid and snap['self']['stopDecision'] is None
    assert status(client,follower,rid,sid).status_code==403

def test_each_visit_is_distinct_cas_and_idempotent(client):
    host,_,rid=setup(client);sid=propose(client,host,rid).json()['stopId']
    assert propose(client,host,rid).json()['stopId']==sid
    assert propose(client,host,rid,key='stop-key-02').status_code==409
    new=propose(client,host,rid,sid,'stop-key-02').json()['stopId']
    assert new!=sid

def test_decline_persists_and_cannot_claim_applied(client):
    host,follower,rid=setup(client);sid=propose(client,host,rid).json()['stopId']
    assert choose(client,follower,rid,sid,False).json()['self']['stopDecision']['state']=='DECLINED'
    assert status(client,follower,rid,sid).status_code==403
    assert choose(client,follower,rid,sid,True).status_code==409
    assert client.get(f'/v1/rooms/{rid}',headers=follower).json()['self']['stopDecision']['state']=='DECLINED'

def test_accepted_visit_not_reset_by_duplicate_choice_or_reconnect(client):
    host,follower,rid=setup(client);sid=propose(client,host,rid).json()['stopId']
    assert choose(client,follower,rid,sid).status_code==200
    assert status(client,follower,rid,sid).status_code==200
    assert choose(client,follower,rid,sid).json()['self']['stopDecision']['state']=='APPLIED'
    assert client.get(f'/v1/rooms/{rid}',headers=follower).json()['self']['stopDecision']['stopId']==sid

def test_destination_change_invalidates_pending_stop(client):
    host,follower,rid=setup(client);sid=propose(client,host,rid).json()['stopId']
    publish(client,host,rid,revision=1,key='new-destination')
    assert choose(client,follower,rid,sid).status_code==409
    assert status(client,follower,rid,sid).status_code==409
    assert client.get(f'/v1/rooms/{rid}',headers=follower).json()['room']['stopProposal'] is None

def test_expired_proposal_cannot_authorize_a_write(client):
    host,follower,rid=setup(client);sid=propose(client,host,rid).json()['stopId']
    store=client.app.state.store
    data=next(r for r in store.all_rooms() if r['id']==rid);data['stopProposal']['expiresAt']=0
    with store.db:store.save(data)
    assert choose(client,follower,rid,sid).status_code==409

def test_id_only_waypoint_cannot_be_used_as_a_destination(client):
    host,_,rid=setup(client)
    opaque={**STOP,'latitude':0,'longitude':0,'poiSubId':None,'waypointBinding':'CONNECT_2_1_POI'}
    assert publish(client,host,rid,revision=1,key='opaque-dest',place=opaque).status_code==422
    result=client.put(f'/v1/rooms/{rid}/stop-proposal',headers=host,json={
        'leaderConfirmed':True,'planRevision':1,'hostEpoch':1,'idempotencyKey':'opaque-stop','place':opaque})
    assert result.status_code==200
