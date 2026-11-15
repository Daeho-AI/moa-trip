package dev.moa.trip

import android.content.Context
import android.os.SystemClock
import dev.moa.trip.core.*
import dev.moa.trip.data.*
import dev.moa.trip.sdk.PleosGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TripUiState(val snapshot: TripSnapshot?=null,val connected: Boolean=false,val receivedAtMs: Long=0,
    val busy: Boolean=false,val error: String?=null,val syncState: String="WAITING_DESTINATION",val syncReason: String="",
    val prompt: DestinationPrompt?=null)

class TripController(private val context: Context,private val scope: CoroutineScope,private val gateway: PleosGateway) {
    val settings=SecureSettings(context)
    val api=RoomApi(settings,scope)
    private val mutable=MutableStateFlow(TripUiState())
    val state=mutable.asStateFlow()
    private val actions=Mutex()
    private var baseline: RouteBaseline?=settings.prefs.getString("baseline",null)?.let { runCatching { api.json.decodeFromString<RouteBaseline>(it) }.getOrNull() }
    private var paused=settings.prefs.getBoolean("paused",false)
    private var uncertain=settings.prefs.getBoolean("writePending",false)
    private data class Pending(val roomId: String,val revision: Int,val target: Place,val waypoints: List<Place>,val issued: Long,val stopId: String?=null,
        val confirmation: RouteConfirmation=RouteConfirmation(issued,RouteBaseline(target,waypoints)))
    private var pending: Pending?=settings.prefs.getString("pendingWrite",null)?.let { saved ->
        runCatching { api.json.decodeFromString<PendingRouteWrite>(saved) }.getOrNull()?.let {
            Pending(it.roomId,it.revision,it.target,it.waypoints,SystemClock.elapsedRealtime(),it.stopId)
        }
    }
    private var stableCandidate: Place?=null
    private var stableSince=0L
    private var observedRoute: RouteBaseline?=null
    private var stopCandidate: Place?=null
    private var stopStableSince=0L
    private var ignoredLeader: DestinationPrompt?=settings.prefs.getString("ignoredLeader",null)?.let {
        runCatching { api.json.decodeFromString<DestinationPrompt>(it) }.getOrNull()
    }
    private var foreground=false
    private var lastReceipt=""
    private var lastTelemetry=0L
    private var started=false
    private val roomId get()=settings.prefs.getString("roomId",null)
    private fun now()=SystemClock.elapsedRealtime()
    fun onForeground() {
        foreground=true
        TripService.clearProposal(context)
        if(roomId!=null)
            runCatching { TripService.start(context) }.onFailure(::failure)
    }
    fun onBackground() { foreground=false; state.value.prompt?.let { TripService.showProposal(context,it) } }
    private fun setPrompt(value: DestinationPrompt?) {
        if(state.value.prompt==value) return
        mutable.update { it.copy(prompt=value) }
        if(value==null || foreground) TripService.clearProposal(context) else TripService.showProposal(context,value)
        if(BuildConfig.DEBUG) (context.applicationContext as TripApplication).writeDiagnostic("destination-prompt.json",
            value?.let { api.json.encodeToString(it) } ?: "null")
    }
    init {
        api.onSnapshot={ accept(it) }
        api.onConnection={ connected -> mutable.update { it.copy(connected=connected) } }
    }
    fun start() {
        if(started) return
        started=true
        roomId?.let { room -> scope.launch { runCatching { accept(api.snapshot(room)); api.connect(room) }.onFailure(::failure) } }
        scope.launch {
            while(isActive) {
                runCatching { actions.withLock { tick() } }.onFailure(::failure)
                delay(2000)
            }
        }
    }
    private fun failure(t: Throwable) {
        mutable.update { it.copy(error=t.message ?: "연결을 확인하세요",busy=false) }
        if(BuildConfig.DEBUG) (context.applicationContext as TripApplication).writeDiagnostic("action-error.json",
            JSONObject().put("atMs",now()).put("type",t.javaClass.simpleName).put("message",t.message).toString())
        if(t is ApiFailure && t.code in listOf(401,404,410)) {
            setPrompt(null)
            api.disconnect()
            TripService.stop(context)
            mutable.update { it.copy(snapshot=null) }
            if(t.code==401) settings.clearSession()
            else settings.prefs.edit().remove("roomId").apply()
        }
    }
    private fun accept(snapshot: TripSnapshot) {
        if(roomId!=snapshot.room.id) return
        val old=state.value.snapshot
        if(old!=null && old.room.id==snapshot.room.id && (snapshot.room.planRevision<old.room.planRevision || snapshot.serverTime<old.serverTime)) return
        mutable.update { it.copy(snapshot=snapshot,receivedAtMs=now(),error=null) }
        state.value.prompt?.let { if(it.roomId!=snapshot.room.id || it.revision!=snapshot.room.planRevision ||
            it.hostEpoch!=snapshot.room.hostEpoch || snapshot.room.state!="ACTIVE") setPrompt(null) }
        if(snapshot.room.state!="ACTIVE") { api.disconnect(); TripService.stop(context) }
        export(snapshot)
    }
    private fun export(snapshot: TripSnapshot) {
        if(BuildConfig.DEBUG) scope.launch(Dispatchers.IO) {
            synchronized(this@TripController) {
                (context.applicationContext as TripApplication).writeDiagnostic("trip-state.json",api.json.encodeToString(snapshot))
            }
        }
    }
    private fun action(block: suspend ()->Unit) { scope.launch {
        mutable.update { it.copy(busy=true,error=null) }
        runCatching { actions.withLock { block() } }.onFailure(::failure)
        mutable.update { it.copy(busy=false) }
    } }
    fun configureServer(url: String) { runCatching { api.setBase(url); mutable.value=TripUiState() }.onFailure(::failure) }
    fun create(title: String,nickname: String) = action {
        api.ensureSession(); flushFinish()
        attach(api.decode(api.request("POST","/v1/rooms",JSONObject().put("title",title).put("nickname",nickname))))
    }
    fun join(code: String,nickname: String)=action {
        api.ensureSession(); flushFinish()
        attach(api.decode(api.request("POST","/v1/rooms/join",JSONObject().put("code",code.replace("-","").uppercase()).put("nickname",nickname))))
    }
    private fun attach(snapshot: TripSnapshot) {
        settings.prefs.edit().putString("roomId",snapshot.room.id).remove("baseline").putBoolean("paused",false)
            .putBoolean("localLocation",false).putBoolean("localEta",false).putBoolean("localFollow",false).apply()
        baseline=null; paused=false; stableCandidate=null; ignoredLeader=null; lastReceipt=""; lastTelemetry=0
        observedRoute=null; stopCandidate=null
        settings.prefs.edit().remove("ignoredLeader").apply()
        mutable.value=TripUiState()
        accept(snapshot); api.connect(snapshot.room.id); TripService.start(context)
    }
    private fun nativeFresh(native: GatewayState): Boolean = native.naviConnection=="CONNECTED" &&
        native.routeStateAtMs>0 && now()-native.routeStateAtMs<7000 &&
        native.waypointsAtMs>0 && now()-native.waypointsAtMs<7000 &&
        (native.naviMode!="ROUTE_GUIDING" || native.destinationAtMs>0 && now()-native.destinationAtMs<7000)
    private fun currentRoute(native: GatewayState)=RouteBaseline(
        if(native.naviMode=="ROUTE_GUIDING") native.destination else null,
        if(native.naviMode=="ROUTE_GUIDING") native.waypoints else emptyList())
    private fun saveBaseline(value: RouteBaseline) {
        baseline=value; settings.prefs.edit().putString("baseline",api.json.encodeToString(value)).apply()
    }
    fun grant(location: Boolean,eta: Boolean,follow: Boolean)=action {
        val snap=state.value.snapshot ?: error("여행방에 먼저 참여하세요")
        check(gateway.state.value.parked) { "주차 상태에서 참여 설정을 확인하세요" }
        // This legacy preference now has NO authority to write a destination.
        settings.prefs.edit().putBoolean("localLocation",location).putBoolean("localEta",eta).putBoolean("localFollow",follow).commit()
        val response=api.request("PUT","/v1/rooms/${snap.room.id}/grants/me",JSONObject()
            .put("hostEpoch",snap.room.hostEpoch).put("locationEnabled",location).put("etaEnabled",eta).put("destinationEnabled",follow))
        lastReceipt=""; accept(api.decode(response)); TripService.start(context)
    }
    fun toggleBroadcast()=action {
        val snap=state.value.snapshot ?: return@action
        check(gateway.state.value.parked) { "주차 후 변경하세요" }
        accept(api.decode(api.request("PUT","/v1/rooms/${snap.room.id}/leader-broadcast",JSONObject().put("enabled",!snap.room.leaderBroadcastEnabled))))
        stableCandidate=null
        setPrompt(null)
    }
    fun finish()=action {
        val snap=state.value.snapshot ?: return@action
        settings.prefs.edit().putString("pendingFinishRoomId",snap.room.id).commit()
        api.disconnect(); settings.prefs.edit().remove("roomId").remove("baseline").putBoolean("paused",false).apply()
        baseline=null; paused=false; setPrompt(null); mutable.value=TripUiState(); TripService.stop(context)
        flushFinish()
    }
    private suspend fun flushFinish() {
        val ending=settings.prefs.getString("pendingFinishRoomId",null) ?: return
        try { api.request("POST","/v1/rooms/$ending/members/me/finish") }
        catch(t: ApiFailure) { if(t.code !in listOf(404,410)) throw t }
        settings.prefs.edit().remove("pendingFinishRoomId").apply()
    }
    fun refresh()=action { roomId?.let { accept(api.snapshot(it)) }; gateway.refresh() }
    fun reconsiderDestination()=action {
        ignoredLeader=null; settings.prefs.edit().remove("ignoredLeader").apply()
        val snap=state.value.snapshot ?: return@action
        val native=gateway.state.value
        if(!snap.self.leader && snap.room.destination!=null && nativeFresh(native))
            setPrompt(DestinationPrompt("FOLLOWER",snap.room.id,snap.room.hostEpoch,snap.room.planRevision,requireNotNull(snap.room.destination),currentRoute(native)))
        else { stableCandidate=null; tick() }
    }
    fun respondDestination(prompt: DestinationPrompt,approve: Boolean)=action {
        if(prompt.isStop) { respondStop(prompt,approve); return@action }
        check(state.value.prompt==prompt && roomId==prompt.roomId) { "목적지 요청이 바뀌었습니다. 새 요청을 확인하세요" }
        check(pending==null && !uncertain) { "이전 내비 요청 결과를 먼저 확인하세요" }
        val latest=api.snapshot(prompt.roomId); accept(latest)
        val native=gateway.state.value
        check(nativeFresh(native) && native.uxCanConfirm) { "내비와 차량 상태를 확인한 뒤 다시 선택하세요" }
        check(promptStillValid(prompt,latest.room,currentRoute(native))) { "목적지 또는 내 경로가 바뀌었습니다. 다시 확인하세요" }
        if(prompt.kind=="LEADER") {
            check(latest.self.leader) { "리더만 변경 요청을 보낼 수 있습니다" }
            if(approve) {
                api.request("PUT","/v1/rooms/${prompt.roomId}/plan",JSONObject().put("leaderConfirmed",true)
                    .put("expectedPlanRevision",prompt.revision).put("hostEpoch",prompt.hostEpoch)
                    .put("idempotencyKey",UUID.randomUUID().toString()).put("destination",JSONObject(api.json.encodeToString(prompt.target))))
                setPrompt(null); accept(api.snapshot(prompt.roomId))
            } else {
                ignoredLeader=prompt; settings.prefs.edit().putString("ignoredLeader",api.json.encodeToString(prompt)).apply()
                setPrompt(null)
            }
            return@action
        }
        check(!latest.self.leader) { "참여 차량에서 확인하세요" }
        if(approve) {
            check(native.waypoints.size<=2) { "개인 경유지를 먼저 확인하세요" }
            check(decideSync(prompt.target,currentRoute(native),prompt.localRoute,true,true,false,false,
                gateway.supportsNullDestinationSubId(prompt.target)) in setOf(SyncDecision.APPLY,SyncDecision.ALREADY_MATCHED)) {
                "개인 경유지 또는 장소의 내비 호환성을 확인하세요"
            }
        }
        accept(api.decode(api.request("PUT","/v1/rooms/${prompt.roomId}/destination-decision/me",JSONObject()
            .put("planRevision",prompt.revision).put("hostEpoch",prompt.hostEpoch).put("accepted",approve))))
        if(!approve) { setPrompt(null); receipt(latest,"DECLINED"); return@action }
        // Re-read after the network round trip. Never apply a newer target using an older dialog's approval.
        val checked=api.snapshot(prompt.roomId); accept(checked)
        var current=gateway.state.value
        check(nativeFresh(current) && current.uxCanConfirm && promptStillValid(prompt,checked.room,currentRoute(current))) {
            "목적지 또는 내 경로가 바뀌어 요청을 보류했습니다. 다시 확인하세요"
        }
        if(samePlace(prompt.target,currentRoute(current).destination)) { setPrompt(null); receipt(checked,"MATCHED"); return@action }
        receipt(checked,"APPLYING")
        current=gateway.state.value
        check(nativeFresh(current) && current.uxCanConfirm && promptStillValid(prompt,state.value.snapshot!!.room,currentRoute(current))) {
            "내 경로가 바뀌어 요청을 보류했습니다. 다시 확인하세요"
        }
        val start=now()
        pending=Pending(prompt.roomId,prompt.revision,prompt.target,current.waypoints,start)
        settings.prefs.edit().putBoolean("writePending",true)
            .putString("pendingWrite",api.json.encodeToString(PendingRouteWrite(prompt.roomId,prompt.revision,prompt.target,current.waypoints))).commit()
        setPrompt(null)
        runCatching { gateway.requestRoute(prompt.target,current.waypoints) }.onFailure {
            uncertain=true; receipt(checked,"WRITE_UNCERTAIN",(it.message ?: "내비 요청 확인 중").take(150))
        }
    }
    /** Sharing a stop is a one-off visit proposal, never a replayable route plan. */
    fun shareStop(place: Place)=action {
        val snap=state.value.snapshot ?: return@action
        val native=gateway.state.value
        check(snap.self.leader && nativeFresh(native) && samePlace(snap.room.destination,currentRoute(native).destination)) { "공유 목적지로 안내 중일 때 경유지를 요청하세요" }
        check(currentRoute(native).waypoints.any { sameWaypoint(it,place) }) { "순정 내비 경유지를 먼저 확인하세요" }
        check(gateway.supportsWaypoint(place)) { "이 경유지의 내비 호환성을 확인할 수 없습니다" }
        setPrompt(DestinationPrompt("LEADER_STOP",snap.room.id,snap.room.hostEpoch,snap.room.planRevision,place,currentRoute(native),snap.room.stopProposal?.id))
    }
    private fun stopActive(snap: TripSnapshot)=snap.room.stopProposal?.let {
        it.planRevision==snap.room.planRevision && it.hostEpoch==snap.room.hostEpoch &&
            it.expiresAt>snap.serverTime+(now()-state.value.receivedAtMs)
    }==true
    private suspend fun stopReceipt(snap: TripSnapshot,stopId: String,status: String,reason: String="") {
        if(snap.room.stopProposal?.id!=stopId) return
        accept(api.decode(api.request("PUT","/v1/rooms/${snap.room.id}/stop-status/me",JSONObject()
            .put("stopId",stopId).put("state",status).put("reason",reason.take(150)))))
    }
    private suspend fun respondStop(prompt: DestinationPrompt,approve: Boolean) {
        check(state.value.prompt==prompt && roomId==prompt.roomId) { "경유지 요청이 바뀌었습니다" }
        check(pending==null && !uncertain) { "이전 내비 요청 결과를 먼저 확인하세요" }
        val latest=api.snapshot(prompt.roomId); accept(latest)
        var native=gateway.state.value
        check(nativeFresh(native) && native.uxCanConfirm && promptStillValid(prompt,latest.room,currentRoute(native))) { "요청 또는 내 경로가 바뀌었습니다. 다시 확인하세요" }
        if(prompt.isLeader) {
            check(latest.self.leader) { "리더만 요청할 수 있습니다" }
            if(approve) {
                api.request("PUT","/v1/rooms/${prompt.roomId}/stop-proposal",JSONObject()
                    .put("leaderConfirmed",true).put("planRevision",prompt.revision).put("hostEpoch",prompt.hostEpoch)
                    .put("expectedStopId",prompt.stopId ?: JSONObject.NULL).put("idempotencyKey",UUID.randomUUID().toString())
                    .put("place",JSONObject(api.json.encodeToString(prompt.target))))
                accept(api.snapshot(prompt.roomId))
            }
            stopCandidate=null; setPrompt(null); return
        }
        check(!latest.self.leader && stopActive(latest)) { "경유지 요청이 만료되었습니다" }
        check(latest.self.stopDecision?.stopId!=prompt.stopId) { "이미 선택한 경유지입니다. 다시 추가하지 않습니다" }
        val result=decideStop(prompt.target,currentRoute(native),latest.room.destination,gateway.supportsWaypoint(prompt.target))
        if(approve) check(result in setOf(StopApply.ADD,StopApply.PRESENT)) {
            when(result) { StopApply.CAPACITY -> "경유지는 최대 2곳입니다. 내 경유지를 먼저 확인하세요"
                StopApply.WRONG_DESTINATION -> "공유 목적지로 안내 중일 때만 경유지를 추가합니다"
                else -> "이 경유지의 내비 호환성을 확인할 수 없습니다" }
        }
        accept(api.decode(api.request("PUT","/v1/rooms/${prompt.roomId}/stop-decision/me",JSONObject()
            .put("stopId",prompt.stopId).put("planRevision",prompt.revision).put("hostEpoch",prompt.hostEpoch).put("accepted",approve))))
        if(!approve) { setPrompt(null); return }
        val checked=api.snapshot(prompt.roomId); accept(checked); native=gateway.state.value
        check(stopActive(checked) && nativeFresh(native) && native.uxCanConfirm && promptStillValid(prompt,checked.room,currentRoute(native))) { "내 경로가 바뀌어 경유지 추가를 보류했습니다" }
        if(result==StopApply.PRESENT) { stopReceipt(checked,requireNotNull(prompt.stopId),"PRESENT"); setPrompt(null); return }
        stopReceipt(checked,requireNotNull(prompt.stopId),"APPLYING")
        native=gateway.state.value
        check(nativeFresh(native) && native.uxCanConfirm && promptStillValid(prompt,state.value.snapshot!!.room,currentRoute(native))) { "내 경로가 바뀌어 경유지 추가를 보류했습니다" }
        val destination=requireNotNull(native.destination); val waypoints=native.waypoints+prompt.target; val start=now()
        pending=Pending(prompt.roomId,prompt.revision,destination,waypoints,start,prompt.stopId)
        settings.prefs.edit().putBoolean("writePending",true).putString("pendingWrite",
            api.json.encodeToString(PendingRouteWrite(prompt.roomId,prompt.revision,destination,waypoints,prompt.stopId))).commit()
        setPrompt(null)
        runCatching { gateway.addWaypoint(prompt.target,native.waypoints.size) }.onFailure {
            uncertain=true; stopReceipt(checked,requireNotNull(prompt.stopId),"WRITE_UNCERTAIN",it.message ?: "내비 반영 확인 중")
        }
    }
    private suspend fun receipt(snap: TripSnapshot,status: String,reason: String="") {
        mutable.update { it.copy(syncState=status,syncReason=reason) }
        val key="${snap.room.id}:${snap.room.planRevision}:$status:$reason"
        if(key==lastReceipt) return
        api.request("PUT","/v1/rooms/${snap.room.id}/sync-status/me",JSONObject()
            .put("planRevision",snap.room.planRevision).put("state",status).put("reason",reason))
        lastReceipt=key
    }
    private suspend fun tick() {
        val id=roomId ?: return
        var snap=state.value.snapshot
        if(snap==null || !state.value.connected || now()-state.value.receivedAtMs>10000) {
            snap=api.snapshot(id); accept(snap)
        }
        if(snap.room.state!="ACTIVE") return
        val native=gateway.state.value
        val fresh=nativeFresh(native)
        val current=currentRoute(native)
        val target=snap.room.destination
        if(fresh && snap.self.leader && native.naviMode=="ROUTE_GUIDING" && current.destination!=null) {
            newSingleStop(observedRoute,current)?.takeIf(gateway::supportsWaypoint)?.let { stopCandidate=it; stopStableSince=now() }
            observedRoute=current
            if(stopCandidate!=null && current.waypoints.none { sameWaypoint(it,stopCandidate) }) stopCandidate=null
        }
        val grant=snap.self.grants
        val serverNow=snap.serverTime+(now()-state.value.receivedAtMs)
        val validGrant=grant.expiresAt>serverNow && grant.hostEpoch==snap.room.hostEpoch
        val inFlight=pending
        if(inFlight!=null) {
            setPrompt(null)
            val matches=inFlight.confirmation.observe(native,fresh)
            if(matches) {
                saveBaseline(current); pending=null; uncertain=false
                settings.prefs.edit().putBoolean("writePending",false).remove("pendingWrite").apply()
                if(inFlight.roomId==snap.room.id && inFlight.revision==snap.room.planRevision) {
                    if(inFlight.stopId!=null) { stopReceipt(snap,inFlight.stopId,"APPLIED"); receipt(snap,"MATCHED") }
                    else receipt(snap,"APPLIED")
                }
            } else if(now()-inFlight.issued>(if(inFlight.stopId!=null) 30000 else 15000)) {
                if(inFlight.stopId!=null && !uncertain) stopReceipt(snap,inFlight.stopId,"WRITE_UNCERTAIN","순정 내비 경유지를 확인하세요")
                uncertain=true
                receipt(snap,"WRITE_UNCERTAIN",if(fresh && current.destination!=null &&
                    !samePlace(inFlight.target,current.destination))
                    "요청한 장소와 실제 내비가 다릅니다. 순정 내비를 확인하세요."
                    else "순정 내비에서 요청 결과를 확인하세요. 추가 변경은 보류합니다.")
            } else if(inFlight.roomId==snap.room.id && inFlight.revision==snap.room.planRevision) receipt(snap,if(inFlight.stopId==null) "APPLYING" else "WAITING_CONNECTION")
            else receipt(snap,"WAITING_CONNECTION","이전에 승인한 내비 요청 확인 중")
        } else if(uncertain) receipt(snap,"WRITE_UNCERTAIN","이전 요청 결과 확인 전 추가 설정을 중지했습니다")
        else if(snap.self.leader) {
            if(fresh && native.naviMode=="ROUTE_GUIDING" && current.destination!=null && snap.room.leaderBroadcastEnabled) {
                if(!samePlace(stableCandidate,current.destination)) { stableCandidate=current.destination; stableSince=now() }
                else if(now()-stableSince>=2000 && !samePlace(target,current.destination)) {
                    val prompt=DestinationPrompt("LEADER",id,snap.room.hostEpoch,snap.room.planRevision,requireNotNull(current.destination),current)
                    if(ignoredLeader?.let { promptStillValid(it,snap.room,current) }!=true) setPrompt(prompt)
                }
                if(samePlace(target,current.destination)) {
                    val stop=stopCandidate
                    if(stop!=null && now()-stopStableSince>=2000)
                        setPrompt(DestinationPrompt("LEADER_STOP",id,snap.room.hostEpoch,snap.room.planRevision,stop,current,snap.room.stopProposal?.id))
                    else if(state.value.prompt?.kind!="LEADER_STOP" || state.value.prompt?.let { promptStillValid(it,snap.room,current) }!=true) setPrompt(null)
                }
            } else { stableCandidate=null; setPrompt(null) }
            if(target!=null && fresh && samePlace(current.destination,target)) { saveBaseline(current); receipt(snap,"MATCHED") }
            else receipt(snap,if(target==null) "WAITING_DESTINATION" else if(!fresh) "WAITING_CONNECTION" else "LOCAL_OVERRIDE")
        } else if(target==null) { setPrompt(null); receipt(snap,"WAITING_DESTINATION") }
        else {
            // Polls, old blanket grants, reconnection and persisted ACCEPTED never initiate a write.
            val decision=snap.self.destinationDecision?.takeIf { it.planRevision==snap.room.planRevision && it.hostEpoch==snap.room.hostEpoch }
            when {
                !fresh -> { setPrompt(null); receipt(snap,"WAITING_CONNECTION") }
                samePlace(target,current.destination) -> {
                    val stop=snap.room.stopProposal
                    if(stop!=null && stopActive(snap) && snap.self.stopDecision?.stopId!=stop.id)
                        setPrompt(DestinationPrompt("FOLLOWER_STOP",id,snap.room.hostEpoch,snap.room.planRevision,stop.place,current,stop.id))
                    else setPrompt(null)
                    saveBaseline(current); receipt(snap,"MATCHED")
                }
                decision?.accepted==false && state.value.prompt==null -> receipt(snap,"DECLINED")
                decision?.accepted==true && baseline!=null && !sameRoute(current.destination,current.waypoints,baseline!!.destination,baseline!!.waypoints) && state.value.prompt==null ->
                    receipt(snap,"LOCAL_OVERRIDE","내가 설정한 목적지를 유지합니다")
                else -> {
                    setPrompt(DestinationPrompt("FOLLOWER",id,snap.room.hostEpoch,snap.room.planRevision,target,current))
                    receipt(snap,"NEEDS_CONFIRMATION")
                }
            }
        }
        if(now()-lastTelemetry>=2000) {
            val sequence=settings.prefs.getLong("telemetrySequence",0)+1
            settings.prefs.edit().putLong("telemetrySequence",sequence).commit()
            val body=JSONObject().put("sequence",sequence).put("source","PLEOS_SDK")
            val location=native.location
            val locationAge=location?.measuredAtElapsedMs?.let { now()-it }
            if(validGrant && grant.locationEnabled && settings.prefs.getBoolean("localLocation",false) && native.locationPermission && location!=null && locationAge!=null && locationAge in 0..59999)
                body.put("location",JSONObject().put("latitude",location.latitude).put("longitude",location.longitude)
                    .put("heading",((location.heading.toDouble()%360)+360)%360).put("sampleAgeMs",locationAge))
            val remainingSec=native.remainTimeSec; val remainingM=native.remainDistanceM
            if(validGrant && grant.etaEnabled && settings.prefs.getBoolean("localEta",false) && target!=null && fresh && samePlace(target,current.destination) &&
                remainingSec!=null && remainingSec>=0 && remainingM!=null && remainingM>=0)
                body.put("eta",JSONObject().put("planRevision",snap.room.planRevision).put("remainTimeSec",remainingSec)
                    .put("remainDistanceM",remainingM).put("sampleAgeMs",now()-native.destinationAtMs))
            api.request("PUT","/v1/rooms/$id/telemetry/me",body)
            lastTelemetry=now()
        }
    }
}
