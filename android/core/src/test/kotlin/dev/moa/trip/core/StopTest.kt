package dev.moa.trip.core

import kotlin.test.*

class StopTest {
    private val dest=Place(37.5,127.3,"최종 목적지","주소","d","0")
    private val privateStop=dest.copy(name="개인 픽업",poiId="p",latitude=37.4)
    private val stop=dest.copy(name="공동 경유지",poiId="s",latitude=37.45)
    private val route=RouteBaseline(dest,listOf(privateStop))
    private val proposal=StopProposal("visit-1",2,1,stop,999999)
    private val room=RoomInfo("r","여행","ACTIVE",1,2,999999,dest,stopProposal=proposal)
    private val prompt=DestinationPrompt("FOLLOWER_STOP","r",1,2,stop,route,"visit-1")

    @Test fun stopConsentBindsVisitAndExactLocalRoute() {
        assertTrue(promptStillValid(prompt,room,route))
        assertFalse(promptStillValid(prompt,room.copy(stopProposal=proposal.copy(id="visit-2")),route))
        assertFalse(promptStillValid(prompt,room.copy(destination=privateStop),route))
        assertFalse(promptStillValid(prompt,room,route.copy(waypoints=emptyList())))
    }
    @Test fun sameDestinationDoesNotMeanStopAlreadyApplied() {
        assertEquals(StopApply.ADD,decideStop(stop,route,dest,true))
        assertEquals(listOf(privateStop,stop),route.waypoints+stop)
        assertEquals(StopApply.PRESENT,decideStop(stop,route.copy(waypoints=listOf(privateStop,stop)),dest,true))
    }
    @Test fun fullPersonalRouteAndUnknownBindingNeverOverwrite() {
        assertEquals(StopApply.CAPACITY,decideStop(stop,route.copy(waypoints=listOf(privateStop,privateStop.copy(poiId="p2"))),dest,true))
        assertEquals(StopApply.INVALID,decideStop(stop,route,dest,false))
        assertEquals(StopApply.WRONG_DESTINATION,decideStop(stop,route,privateStop,true))
    }
    @Test fun removalArrivalAndReconnectDoNotCreateNewVisits() {
        assertNull(newSingleStop(null,route))
        assertNull(newSingleStop(route,route.copy(waypoints=emptyList())))
        assertNull(newSingleStop(route,route))
        assertEquals(stop,newSingleStop(route,route.copy(waypoints=route.waypoints+stop)))
        assertNull(newSingleStop(route,RouteBaseline(privateStop,listOf(privateStop,stop))))
    }
    @Test fun stopReadbackRequiresDestinationAndAllOrderedWaypoints() {
        val confirmation=RouteConfirmation(100,RouteBaseline(dest,listOf(privateStop,stop)))
        fun native(t:Long,stops:List<Place>)=GatewayState(naviMode="ROUTE_GUIDING",destination=dest,waypoints=stops,
            destinationAtMs=t,waypointsAtMs=t,routeStateAtMs=t)
        assertFalse(confirmation.observe(native(110,listOf(stop)),true))
        assertFalse(confirmation.observe(native(120,listOf(stop,privateStop)),true))
        assertFalse(confirmation.observe(native(130,listOf(privateStop,stop)),true))
        assertTrue(confirmation.observe(native(140,listOf(privateStop,stop)),true))
    }
    @Test fun idOnlyCompatibilityIsExplicitAndNeverAppliesToDestinations() {
        val opaque=stop.copy(latitude=0.0,longitude=0.0,poiSubId=null,waypointBinding="CONNECT_2_1_POI")
        assertFalse(opaque.valid())
        assertTrue(waypointValid(opaque))
        assertTrue(sameWaypoint(opaque,opaque))
        assertFalse(samePlace(opaque,opaque))
        assertFalse(sameWaypoint(opaque,opaque.copy(waypointBinding="COORDINATES")))
        assertFalse(sameWaypoint(opaque,opaque.copy(poiId="other")))
        assertEquals(StopApply.INVALID,decideStop(opaque,route,dest,false))
        assertEquals(StopApply.ADD,decideStop(opaque,route,dest,true))
    }
}
