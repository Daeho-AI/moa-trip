package dev.moa.trip.core

import kotlinx.serialization.Serializable

@Serializable data class Grants(val locationEnabled: Boolean=false,val etaEnabled: Boolean=false,
    val destinationEnabled: Boolean=false,val sharedStopsEnabled: Boolean=false,val hostEpoch: Int=1,val expiresAt: Long=0)
@Serializable data class RoomInfo(val id: String,val title: String,val state: String,val hostEpoch: Int,
    val planRevision: Int,val expiresAt: Long,val destination: Place?=null,val leaderBroadcastEnabled: Boolean=true)
@Serializable data class OwnMember(val id: String,val leader: Boolean,val grants: Grants,
    val inviteCode: String?=null,val inviteExpiresAt: Long?=null,val destinationDecision: DestinationDecision?=null)
@Serializable data class DestinationDecision(val planRevision: Int,val hostEpoch: Int,val accepted: Boolean)
@Serializable data class SyncReceipt(val planRevision: Int=0,val state: String="WAITING_DESTINATION",val reason: String="")
@Serializable data class SharedLocation(val latitude: Double,val longitude: Double,val heading: Double,
    val observedAt: Long,val ageMs: Long,val stale: Boolean)
@Serializable data class SharedEta(val planRevision: Int,val remainTimeSec: Int,val remainDistanceM: Int,
    val observedAt: Long,val ageMs: Long,val arrivalAt: Long)
@Serializable data class TripMember(val id: String,val nickname: String,val leader: Boolean,
    val participationState: String,val joinedAt: Long,val sync: SyncReceipt,
    val online: Boolean,val source: String="PLEOS_SDK",val location: SharedLocation?=null,val eta: SharedEta?=null)
@Serializable data class Assembly(val expected: Int=0,val known: Int=0,val complete: Boolean=false,val latestKnownArrivalAt: Long?=null)
@Serializable data class TripSnapshot(val serverTime: Long,val room: RoomInfo,val self: OwnMember,
    val members: List<TripMember>,val assembly: Assembly)
@Serializable data class RouteBaseline(val destination: Place?,val waypoints: List<Place>)
@Serializable data class PendingRouteWrite(val roomId: String,val revision: Int,val target: Place,val waypoints: List<Place>)
@Serializable data class DestinationPrompt(val kind: String,val roomId: String,val hostEpoch: Int,
    val revision: Int,val target: Place,val localRoute: RouteBaseline) {
    val key get()="$kind:$roomId:$hostEpoch:$revision:${target.poiProvider}:${target.poiId}:${target.latitude}:${target.longitude}"
}

/** Approval is valid only for the exact proposal AND native route shown in the dialog. */
fun promptStillValid(prompt: DestinationPrompt,room: RoomInfo,current: RouteBaseline): Boolean =
    room.state=="ACTIVE" && prompt.roomId==room.id && prompt.hostEpoch==room.hostEpoch && prompt.revision==room.planRevision &&
    sameRoute(current.destination,current.waypoints,prompt.localRoute.destination,prompt.localRoute.waypoints) &&
    if(prompt.kind=="LEADER") room.leaderBroadcastEnabled && samePlace(current.destination,prompt.target)
    else samePlace(room.destination,prompt.target)

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
