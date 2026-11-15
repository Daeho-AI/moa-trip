package dev.moa.trip.core

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class Place(
    val latitude: Double, val longitude: Double, val name: String,
    val address: String, val poiId: String, val poiSubId: String?,
    val poiProvider: String = "", val displayLatitude: Double? = null,
    val displayLongitude: Double? = null, val waypointBinding: String = "COORDINATES",
) {
    fun valid() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 &&
        longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0) && name.isNotBlank()
}

fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = Math.toRadians(bLat-aLat); val dLon = Math.toRadians(bLon-aLon)
    val s = sin(dLat/2).pow(2) + cos(Math.toRadians(aLat))*cos(Math.toRadians(bLat))*sin(dLon/2).pow(2)
    return 6371000.0 * 2 * atan2(sqrt(s.coerceIn(0.0,1.0)),sqrt((1-s).coerceIn(0.0,1.0)))
}

/** Provider/id AND entrance coordinates are required; nearby different entrances are not equivalent. */
fun samePlace(a: Place?, b: Place?): Boolean {
    if (a == null || b == null) return a == b
    return a.valid() && b.valid() && a.poiId.isNotBlank() && a.poiId == b.poiId &&
        a.poiProvider == b.poiProvider && a.poiSubId == b.poiSubId &&
        distanceMeters(a.latitude,a.longitude,b.latitude,b.longitude) <= 3.0
}

fun sameRoute(a: Place?, aw: List<Place>, b: Place?, bw: List<Place>): Boolean =
    samePlace(a,b) && aw.size == bw.size && aw.zip(bw).all { sameWaypoint(it.first,it.second) }

/** Connect 2.1.0's waypoint callback omits coordinates. Keep that fact explicit, never invent them. */
fun waypointValid(p: Place)=p.valid() || (p.waypointBinding=="CONNECT_2_1_POI" && p.latitude==0.0 && p.longitude==0.0 &&
    p.poiProvider.isEmpty() && p.poiId.isNotBlank() && p.name.isNotBlank() && p.address.isNotBlank())
fun sameWaypoint(a: Place?,b: Place?): Boolean {
    if(a==null || b==null) return a==b
    if(a.valid() && b.valid()) return samePlace(a,b)
    return waypointValid(a) && waypointValid(b) && a.waypointBinding=="CONNECT_2_1_POI" && b.waypointBinding=="CONNECT_2_1_POI" &&
        a.poiId==b.poiId && a.poiSubId==b.poiSubId && a.poiProvider==b.poiProvider && a.name==b.name && a.address==b.address
}

@Serializable
data class VehicleLocation(
    val latitude: Double, val longitude: Double, val heading: Float,
    val sourceTimestampMs: Long, val receivedAtElapsedMs: Long,
    val measuredAtElapsedMs: Long?, val confidence: Float,
)

@Serializable
data class GatewayState(
    val source: String = "PLEOS_SDK", val naviConnection: String = "STARTING",
    val naviMode: String = "UNKNOWN", val routeOption: String = "",
    val destination: Place? = null, val waypoints: List<Place> = emptyList(),
    val remainTimeSec: Int? = null, val remainDistanceM: Int? = null,
    val destinationAtMs: Long = 0, val waypointsAtMs: Long = 0, val routeStateAtMs: Long = 0,
    val location: VehicleLocation? = null, val locationSupported: Boolean = false,
    val locationPermission: Boolean = false, val locationError: String? = null,
    val uxKnown: Boolean = false, val parked: Boolean = false, val uxRestrictions: Int? = null,
    val uxMaxStringLength: Int = 40, val uxMaxItems: Int = 5, val uxCanConfirm: Boolean = false,
    val destinationEvents: Long = 0, val waypointEvents: Long = 0, val locationEvents: Long = 0,
    val routingCompleted: Long = 0, val cancelledEvents: Long = 0, val arrivedEvents: Long = 0,
    val writeCount: Long = 0, val lastWriteAtMs: Long = 0, val lastRequested: Place? = null,
    val lastError: String? = null,
)
