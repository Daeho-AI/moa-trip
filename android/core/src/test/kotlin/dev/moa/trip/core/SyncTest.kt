package dev.moa.trip.core

import kotlin.test.*

class SyncTest {
    private val old=Place(37.49,127.04,"이전 장소","주소","old","0")
    private val next=Place(37.48,127.03,"여행 목적지","주소","next","0")
    private val pickup=Place(37.485,127.035,"개인 픽업","주소","private","0")
    private val baseline=RouteBaseline(old,listOf(pickup))
    private val room=RoomInfo("room","여행","ACTIVE",1,4,9999,next)
    private val prompt=DestinationPrompt("FOLLOWER","room",1,4,next,baseline)

    @Test fun confirmationIsBoundToRoomEpochRevisionAndExactDestination() {
        assertTrue(promptStillValid(prompt,room,baseline))
        assertFalse(promptStillValid(prompt,room.copy(planRevision=5),baseline))
        assertFalse(promptStillValid(prompt,room.copy(id="other"),baseline))
        assertFalse(promptStillValid(prompt,room.copy(hostEpoch=2),baseline))
        assertFalse(promptStillValid(prompt,room.copy(state="CLOSED"),baseline))
        assertFalse(promptStillValid(prompt,room.copy(destination=old),baseline))
    }
    @Test fun dialogDoesNotAuthorizeOverwritingAChangedLocalRoute() {
        assertFalse(promptStillValid(prompt,room,baseline.copy(destination=pickup)))
        assertFalse(promptStillValid(prompt,room,baseline.copy(waypoints=emptyList())))
        assertTrue(promptStillValid(prompt.copy(localRoute=baseline.copy(waypoints=emptyList())),room,baseline.copy(waypoints=emptyList())))
    }
    @Test fun leaderConfirmationIsBoundToItsOwnNativeDestination() {
        val leader=DestinationPrompt("LEADER","room",1,4,old,baseline)
        assertTrue(promptStillValid(leader,room,baseline))
        assertFalse(promptStillValid(leader,room,baseline.copy(destination=next)))
        assertFalse(promptStillValid(leader,room.copy(leaderBroadcastEnabled=false),baseline))
    }

    @Test fun consentAllowsDestinationRequestWithUnchangedPersonalRoute() {
        assertEquals(SyncDecision.APPLY,decideSync(next,baseline,baseline,true,true,false,false))
        assertEquals(listOf(pickup),baseline.waypoints)
    }
    @Test fun noConsentNeverWrites() {
        assertEquals(SyncDecision.NEEDS_CONSENT,decideSync(next,baseline,baseline,false,true,false,false))
    }
    @Test fun editedNativeDestinationPausesFollowing() {
        val manuallyEdited=RouteBaseline(old.copy(poiId="my-other-place"),listOf(pickup))
        assertEquals(SyncDecision.LOCAL_OVERRIDE,decideSync(next,manuallyEdited,baseline,true,true,false,false))
    }
    @Test fun removedPersonalWaypointNeverSilentlyReappears() {
        assertEquals(SyncDecision.LOCAL_OVERRIDE,decideSync(next,RouteBaseline(old,emptyList()),baseline,true,true,false,false))
    }
    @Test fun timeoutBlocksEvenMatchingDestination() {
        val route=RouteBaseline(next,emptyList())
        assertEquals(SyncDecision.WRITE_UNCERTAIN,decideSync(next,route,route,true,true,true,false))
    }
    @Test fun unknownNativeStateWaits() {
        assertEquals(SyncDecision.WAIT_FOR_NATIVE,decideSync(next,baseline,baseline,true,false,false,false))
    }
    @Test fun unverifiedNullSubIdIsNotInvented() {
        assertEquals(SyncDecision.INVALID_BINDING,decideSync(next.copy(poiSubId=null),baseline,baseline,true,true,false,false))
    }
    @Test fun nearbyDifferentEntranceIsNotSamePlace() {
        assertFalse(samePlace(next,next.copy(poiSubId="1")))
        assertFalse(samePlace(next,next.copy(latitude=37.4805)))
        assertTrue(samePlace(next,next.copy(latitude=37.48000001)))
    }
    @Test fun verifiedNullDestinationDoesNotApproveNullPersonalWaypoint() {
        assertEquals(SyncDecision.APPLY,decideSync(next.copy(poiSubId=null),baseline,baseline,true,true,false,false,true))
        val unknownPickup=baseline.copy(waypoints=listOf(pickup.copy(poiSubId=null)))
        assertEquals(SyncDecision.INVALID_BINDING,decideSync(next,unknownPickup,unknownPickup,true,true,false,false,true))
    }
    private fun readback(time: Long, destination: Place=next)=GatewayState(naviMode="ROUTE_GUIDING",destination=destination,
        waypoints=listOf(pickup),destinationAtMs=time,waypointsAtMs=time,routeStateAtMs=time)
    @Test fun twoIndependentReadbacksConfirmWithoutOptionalCallback() {
        val confirmation=RouteConfirmation(100,RouteBaseline(next,listOf(pickup)))
        assertFalse(confirmation.observe(readback(100),true))
        assertFalse(confirmation.observe(readback(110),true))
        assertFalse(confirmation.observe(readback(110),true))
        assertFalse(confirmation.observe(readback(120).copy(waypointsAtMs=110),true))
        assertTrue(confirmation.observe(readback(120),true))
    }
    @Test fun transientOrPartialMatchCannotConfirmRoute() {
        val confirmation=RouteConfirmation(100,RouteBaseline(next,listOf(pickup)))
        assertFalse(confirmation.observe(readback(110),true))
        assertFalse(confirmation.observe(readback(120,old),true))
        assertFalse(confirmation.observe(readback(130),true))
        assertFalse(confirmation.observe(readback(140).copy(waypoints=emptyList()),true))
        assertFalse(confirmation.observe(readback(150),false))
        assertFalse(confirmation.observe(readback(160),true))
        assertTrue(confirmation.observe(readback(170),true))
    }
}
