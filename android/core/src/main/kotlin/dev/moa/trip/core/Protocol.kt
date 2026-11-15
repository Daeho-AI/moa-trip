package dev.moa.trip.core

import kotlinx.serialization.Serializable

@Serializable data class Grants(val locationEnabled: Boolean=false,val etaEnabled: Boolean=false,
    val destinationEnabled: Boolean=false,val sharedStopsEnabled: Boolean=false,val hostEpoch: Int=1,val expiresAt: Long=0)
@Serializable data class RoomInfo(val id: String,val title: String,val state: String,val hostEpoch: Int,
    val planRevision: Int,val expiresAt: Long,val destination: Place?=null,val leaderBroadcastEnabled: Boolean=true,
    val stopProposal: StopProposal?=null)
@Serializable data class StopProposal(val id: String,val planRevision: Int,val hostEpoch: Int,val place: Place,val expiresAt: Long)
@Serializable data class StopDecision(val stopId: String,val accepted: Boolean,val state: String,val reason: String="")
@Serializable data class OwnMember(val id: String,val leader: Boolean,val grants: Grants,
    val inviteCode: String?=null,val inviteExpiresAt: Long?=null,val destinationDecision: DestinationDecision?=null,
    val stopDecision: StopDecision?=null)
@Serializable data class DestinationDecision(val planRevision: Int,val hostEpoch: Int,val accepted: Boolean)
@Serializable data class SyncReceipt(val planRevision: Int=0,val state: String="WAITING_DESTINATION",val reason: String="")
@Serializable data class SharedLocation(val latitude: Double,val longitude: Double,val heading: Double,
    val observedAt: Long,val ageMs: Long,val stale: Boolean)
@Serializable data class SharedEta(val planRevision: Int,val remainTimeSec: Int,val remainDistanceM: Int,
    val observedAt: Long,val ageMs: Long,val arrivalAt: Long)
@Serializable data class TripMember(val id: String,val nickname: String,val leader: Boolean,
    val participationState: String,val joinedAt: Long,val sync: SyncReceipt,
    val online: Boolean,val source: String="PLEOS_SDK",val location: SharedLocation?=null,val eta: SharedEta?=null,
    val stopDecision: StopDecision?=null)
@Serializable data class Assembly(val expected: Int=0,val known: Int=0,val complete: Boolean=false,val latestKnownArrivalAt: Long?=null)
@Serializable data class TripSnapshot(val serverTime: Long,val room: RoomInfo,val self: OwnMember,
    val members: List<TripMember>,val assembly: Assembly)
@Serializable data class RouteBaseline(val destination: Place?,val waypoints: List<Place>)
@Serializable data class PendingRouteWrite(val roomId: String,val revision: Int,val target: Place,val waypoints: List<Place>,val stopId: String?=null)
@Serializable data class DestinationPrompt(val kind: String,val roomId: String,val hostEpoch: Int,
    val revision: Int,val target: Place,val localRoute: RouteBaseline,val stopId: String?=null) {
    val key get()="$kind:$roomId:$hostEpoch:$revision:${target.poiProvider}:${target.poiId}:${target.latitude}:${target.longitude}"
    val isStop get()=kind.endsWith("_STOP")
    val isLeader get()=kind.startsWith("LEADER")
    val stopFull get()=isStop && !isLeader && localRoute.waypoints.size>=2 && localRoute.waypoints.none { sameWaypoint(it,target) }
    val title get()=if(isStop) { if(isLeader) "이 경유지도 함께 들를까요?" else "경유지를 추가할까요?" }
        else if(isLeader) "일행에게도 변경을 요청할까요?" else "목적지를 변경할까요?"
    val confirmLabel get()=if(isLeader) "일행에게 요청" else if(isStop) "경유지 추가" else "목적지 변경"
    val cancelLabel get()=if(isStop) { if(isLeader) "나만 들르기" else "들르지 않기" }
        else if(isLeader) "나만 변경" else "현재 목적지 유지"
}

/** Approval is valid only for the exact proposal AND native route shown in the dialog. */
fun promptStillValid(prompt: DestinationPrompt,room: RoomInfo,current: RouteBaseline): Boolean =
    room.state=="ACTIVE" && prompt.roomId==room.id && prompt.hostEpoch==room.hostEpoch && prompt.revision==room.planRevision &&
    sameRoute(current.destination,current.waypoints,prompt.localRoute.destination,prompt.localRoute.waypoints) &&
    when(prompt.kind) {
        "LEADER" -> room.leaderBroadcastEnabled && samePlace(current.destination,prompt.target)
        "LEADER_STOP" -> room.leaderBroadcastEnabled && samePlace(current.destination,room.destination) &&
            current.waypoints.any { sameWaypoint(it,prompt.target) } && prompt.stopId==room.stopProposal?.id
        "FOLLOWER_STOP" -> room.stopProposal?.let { it.id==prompt.stopId && it.planRevision==room.planRevision &&
            it.hostEpoch==room.hostEpoch && sameWaypoint(it.place,prompt.target) && samePlace(current.destination,room.destination) }==true
        else -> samePlace(room.destination,prompt.target)
    }

/** An additive proposal preserves every personal stop and its order. Never replay an accepted visit. */
enum class StopApply { ADD, PRESENT, CAPACITY, WRONG_DESTINATION, INVALID }
fun decideStop(target: Place,current: RouteBaseline,destination: Place?,supported: Boolean): StopApply = when {
    !samePlace(current.destination,destination) -> StopApply.WRONG_DESTINATION
    !waypointValid(target) || target.poiId.isBlank() || !supported -> StopApply.INVALID
    current.waypoints.any { sameWaypoint(it,target) } -> StopApply.PRESENT
    current.waypoints.size>=2 -> StopApply.CAPACITY
    else -> StopApply.ADD
}
fun newSingleStop(previous: RouteBaseline?,current: RouteBaseline): Place? {
    if(previous==null || !samePlace(previous.destination,current.destination) || current.waypoints.size!=previous.waypoints.size+1) return null
    return current.waypoints.filter { p -> previous.waypoints.none { sameWaypoint(it,p) } }.singleOrNull()
}

/** This guard has no SDK side effects. UI/transport success cannot mark a route APPLIED. */
enum class SyncDecision { ALREADY_MATCHED, APPLY, WAIT_FOR_NATIVE, NEEDS_CONSENT, LOCAL_OVERRIDE, INVALID_BINDING, WRITE_UNCERTAIN }
fun decideSync(target: Place, current: RouteBaseline, baseline: RouteBaseline?, approvedForThisRequest: Boolean,
    nativeFresh: Boolean, uncertain: Boolean, paused: Boolean, verifiedNullDestinationSubId: Boolean=false): SyncDecision {
    if(uncertain) return SyncDecision.WRITE_UNCERTAIN
    if(!nativeFresh) return SyncDecision.WAIT_FOR_NATIVE
    if(paused) return SyncDecision.LOCAL_OVERRIDE
    if(baseline!=null && !sameRoute(current.destination,current.waypoints,baseline.destination,baseline.waypoints))
        return SyncDecision.LOCAL_OVERRIDE
    if(samePlace(target,current.destination)) return SyncDecision.ALREADY_MATCHED
    if(!approvedForThisRequest || baseline==null) return SyncDecision.NEEDS_CONSENT
    if(!target.valid() || (target.poiSubId==null && !verifiedNullDestinationSubId) || target.poiId.isBlank() ||
        current.waypoints.any { !it.valid() || it.poiSubId==null || it.poiId.isBlank() })
        return SyncDecision.INVALID_BINDING
    return SyncDecision.APPLY
}

/** Independent, fresh full readbacks are required; cached fields and one event never confirm a write. */
class RouteConfirmation(private val issuedAt: Long, private val target: RouteBaseline) {
    private var lastDestinationAt=issuedAt
    private var lastWaypointsAt=issuedAt
    private var lastRouteStateAt=issuedAt
    private var consecutive=0
    fun observe(native: GatewayState, fresh: Boolean): Boolean {
        if(!fresh || native.naviMode!="ROUTE_GUIDING" ||
            !sameRoute(native.destination,native.waypoints,target.destination,target.waypoints)) {
            consecutive=0
            return false
        }
        if(native.destinationAtMs<=lastDestinationAt || native.waypointsAtMs<=lastWaypointsAt || native.routeStateAtMs<=lastRouteStateAt)
            return false
        lastDestinationAt=native.destinationAtMs; lastWaypointsAt=native.waypointsAtMs; lastRouteStateAt=native.routeStateAtMs
        consecutive++
        return consecutive>=2
    }
}
