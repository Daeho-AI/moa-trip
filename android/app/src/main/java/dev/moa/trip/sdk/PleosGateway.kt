package dev.moa.trip.sdk

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.*
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.annotation.SuppressLint
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.moa.trip.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@SuppressLint("MissingPermission")
class PleosGateway(private val context: Context, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(GatewayState())
    val state = mutable.asStateFlow()
    private var navi: NaviHelper? = null
    private var fused: FusedLocation? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var locationRegistered = false
    private var started = false
    private var poll: Job? = null
    private var navigationGeneration=0
    private var lastBindAt=0L
    // Empirically verified on the bundled Connect 2.1.0 navigation pair. Raw readback stays unchanged.
    // Never apply this compatibility rule to an unknown vehicle, provider or personal waypoint.
    private val nullDestinationSubIdCompatible by lazy {
        fun version(name: String)=runCatching { context.packageManager.getPackageInfo(name,0).versionName }.getOrNull()
        android.os.Build.FINGERPRINT == "Pleos/crp_avd/crp_avd_x86_64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys" &&
            version("ai.pleos.playground.service.navi")=="3.0.4" &&
            version("ai.umos.maps.android.navigation.app")=="3.3.2-rc5-hotfix.3"
    }
    fun supportsNullDestinationSubId(place: Place)=nullDestinationSubIdCompatible && place.poiProvider.isEmpty()
    private fun now() = SystemClock.elapsedRealtime()
    private fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun fail(message: String, t: Throwable? = null) {
        val detail = message + (t?.let { ": ${it.javaClass.simpleName} ${it.message}" } ?: "")
        Log.w("MoaSDK",detail)
        mutable.update { it.copy(lastError=detail) }
    }
    private val listener = object: NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            mutable.update { it.copy(destination=info.place().takeIf(Place::valid), remainTimeSec=info.remainTime,
                remainDistanceM=info.remainDistance, destinationAtMs=now(), destinationEvents=it.destinationEvents+1) }
        }
        override fun onWayPointInfo(info: List<WaypointInfo>) {
            mutable.update { it.copy(waypoints=info.map { p -> p.place() },waypointsAtMs=now(),waypointEvents=it.waypointEvents+1) }
        }
        override fun onRouteStateInfo(info: RouteStateInfo) {
            mutable.update { it.copy(naviMode=info.naviMode.name, routeOption=info.routeOption.name, routeStateAtMs=now()) }
        }
        override fun onDestinationChanged() { scope.launch(Dispatchers.Main) { refresh() } }
        override fun onRoutingCompleted() {
            mutable.update { it.copy(routingCompleted=it.routingCompleted+1) }
            scope.launch(Dispatchers.Main) { refresh() }
        }
        override fun onRouteCancelled() {
            mutable.update { it.copy(destination=null,waypoints=emptyList(),remainTimeSec=null,remainDistanceM=null,
                naviMode="CANCELLED",cancelledEvents=it.cancelledEvents+1,destinationAtMs=now(),waypointsAtMs=now()) }
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            mutable.update { it.copy(arrivedEvents=it.arrivedEvents+1) }
        }
        override fun onWayPointArrived(info: WaypointArrivedInfo) {
            // Structural changes are reconciled conservatively; no inferred passenger boarding.
            scope.launch(Dispatchers.Main) { refresh() }
        }
        override fun onError(code: NaviErrorCode) { fail("NaviHelper: ${code.name}") }
    }
    private val locationListener = object: FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val elapsed=now(); val wall=System.currentTimeMillis()
            val sample=location?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 && !(it.latitude==0.0 && it.longitude==0.0) }
            val measured=sample?.timestampMillis?.let { ts ->
                when {
                    ts in 946684800000L..(wall+2000) -> elapsed-(wall-ts).coerceAtLeast(0)
                    ts in 1..elapsed -> ts
                    else -> null
                }
            }
            mutable.update { it.copy(location=sample?.let { s -> VehicleLocation(s.latitude,s.longitude,s.heading,
                s.timestampMillis,elapsed,measured,s.confidence) },locationEvents=it.locationEvents+1,
                locationError=if(sample==null) "위치 표본 없음" else null) }
        }
        override fun onError(exception: Exception) {
            mutable.update { it.copy(locationError=exception.message ?: exception.javaClass.simpleName) }
        }
    }
    private fun bindNavigation() {
        val generation=++navigationGeneration
        lastBindAt=now()
        runCatching { navi?.removeListener(listener); navi?.release() }
        mutable.update { it.copy(naviConnection="CONNECTING",naviMode="UNKNOWN",routeStateAtMs=0,waypointsAtMs=0,destinationAtMs=0) }
        try {
            navi=NaviHelper(context).also { helper ->
                helper.initialize(onConnected={
                    scope.launch(Dispatchers.Main) {
                        if(started && generation==navigationGeneration) {
                            helper.addListener(listener)
                            mutable.update { it.copy(naviConnection="CONNECTED") }
                            Log.i("MoaSDK","NaviHelper 3.0.5 connected")
                            refresh()
                        }
                    }
                },onFailed={ t -> if(generation==navigationGeneration) {
                    mutable.update { it.copy(naviConnection="FAILED") }; fail("내비 연결",t)
                } })
            }
        } catch(t: Throwable) { fail("내비 초기화",t) }
    }
    fun start() {
        if(started) return
        started=true
        bindNavigation()
        try {
            fused=FusedLocation(context).also { f ->
                mutable.update { it.copy(locationSupported=f.isSupported()) }
                f.initialize()
            }
        } catch(t: Throwable) { fail("위치 초기화",t) }
        try {
            car=Car.createCar(context,Handler(Looper.getMainLooper()),Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected,ready ->
                if(ready && started) {
                    properties=connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                    restrictions=connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                    runCatching { restrictions?.registerListener { readUx() } }.onFailure { fail("UX 구독",it) }
                    readUx()
                } else { mutable.update { it.copy(uxKnown=false,parked=false) } }
            }
        } catch(t: Throwable) { fail("차량 상태 연결",t) }
        poll=scope.launch(Dispatchers.Main) {
            while(isActive && started) { refresh(); delay(2000) }
        }
    }
    fun refresh() {
        if(started && now()-lastBindAt>20000 && (state.value.routeStateAtMs==0L || now()-state.value.routeStateAtMs>15000))
            bindNavigation()
        if(state.value.naviConnection=="CONNECTED") {
            runCatching { navi?.getRouteStateInfo(); navi?.getDestinationInfo(); navi?.getWaypointInfo() }.onFailure { fail("내비 조회",it) }
        }
        val allowed=has("pleos.car.permission.FUSED_LOCATION")
        mutable.update { it.copy(locationPermission=allowed) }
        if(allowed) {
            runCatching {
                if(!locationRegistered && fused!=null) { fused?.registerFusedLocationCallback(locationListener); locationRegistered=true }
                fused?.getLastKnownLocation(locationListener)
            }.onFailure { mutable.update { s -> s.copy(locationError=it.message) } }
        } else {
            mutable.update { it.copy(location=null) }
            if(locationRegistered) { runCatching { fused?.release() }; locationRegistered=false; runCatching { fused?.initialize() } }
        }
        readUx()
    }
    private fun readUx() {
        runCatching {
            val ux=restrictions?.currentCarUxRestrictions
            val gear=if(has(Car.PERMISSION_POWERTRAIN)) properties?.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION,0)
                ?.takeIf { it.status==CarPropertyValue.STATUS_AVAILABLE }?.value else null
            mutable.update { it.copy(uxKnown=ux!=null,uxRestrictions=ux?.activeRestrictions,
                uxMaxStringLength=if(ux!=null && (ux.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH)!=0) ux.maxRestrictedStringLength.coerceAtLeast(1) else 150,
                uxMaxItems=if(ux!=null && (ux.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT)!=0) ux.maxCumulativeContentItems.coerceIn(0,5) else 5,
                uxCanConfirm=ux!=null && ((ux.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT)==0 ||
                    ux.maxContentDepth>=1 && ux.maxCumulativeContentItems>=2),
                parked=ux!=null && !ux.isRequiresDistractionOptimization && gear==VehicleGear.GEAR_PARK &&
                    (ux.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP)==0) }
        }.onFailure { mutable.update { s -> s.copy(uxKnown=false,parked=false) } }
    }
    fun requestRoute(place: Place, waypoints: List<Place> = emptyList()) {
        check(state.value.naviConnection=="CONNECTED") { "내비 미연결" }
        check(place.valid() && place.poiId.isNotBlank() && (place.poiSubId!=null || supportsNullDestinationSubId(place))) { "미검증 장소 식별자" }
        check(waypoints.size<=2 && waypoints.all { it.valid() && it.poiId.isNotBlank() && it.poiSubId!=null }) { "경유지 호환성 확인 필요" }
        val option=RouteOption.entries.firstOrNull { it.name==state.value.routeOption } ?: error("경로 옵션 미수신")
        val route=RouteInfo(latitude=place.latitude,longitude=place.longitude,displayLatitude=place.displayLatitude,
            displayLongitude=place.displayLongitude,poiName=place.name,poiId=place.poiId,address=place.address,
            poiSubId=place.poiSubId ?: "0",poiProvider=place.poiProvider,routeOption=option)
        mutable.update { it.copy(writeCount=it.writeCount+1,lastWriteAtMs=now(),lastRequested=place,lastError=null) }
        if(waypoints.isEmpty()) navi!!.requestRoute(route)
        else navi!!.requestRouteWithWaypoints(RouteWithWaypointsRequest(route,waypoints.mapIndexed { index,p ->
            RequestWaypointInfo(latitude=p.latitude,longitude=p.longitude,poiName=p.name,
                waypointIndex=if(index==0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                poiId=p.poiId,address=p.address,poiSubId=p.poiSubId!!,poiProvider=p.poiProvider)
        }))
        Log.i("MoaSDK","Route request sent; awaiting native readback")
    }
    fun stop() {
        started=false; poll?.cancel()
        runCatching { navi?.removeListener(listener); navi?.release() }
        runCatching { fused?.release() }; locationRegistered=false
        runCatching { restrictions?.unregisterListener() }; runCatching { car?.disconnect() }
    }
}

private fun DestinationInfo.place()=Place(latitude,longitude,name,address,poiId,poiSubId,poiProvider,displayLatitude,displayLongitude)
private fun WaypointInfo.place()=Place(latitude,longitude,name,address,poiId,poiSubId,poiProvider,displayLatitude,displayLongitude)
