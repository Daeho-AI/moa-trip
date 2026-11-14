package dev.moa.trip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moa.trip.core.Place
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainActivity: ComponentActivity() {
    private val app get()=application as TripApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions()
        setContent { TripScreen(app,::permissions,::probe) }
        handle(intent)
    }
    private fun permissions() {
        val missing=listOf("pleos.car.permission.FUSED_LOCATION","android.car.permission.CAR_POWERTRAIN",Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),101)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults); app.gateway.refresh()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }
    override fun onResume() { super.onResume(); app.trip.onForeground() }
    override fun onStop() { app.trip.onBackground(); super.onStop() }
    private fun handle(intent: Intent?) {
        if(!BuildConfig.DEBUG || !android.os.Build.FINGERPRINT.contains("crp_avd") || intent==null) return
        intent.getStringExtra("probe_command")?.let(::probe)
        when(intent.getStringExtra("trip_command")) {
            "create" -> app.trip.create(intent.getStringExtra("title") ?: "SDK 여행 시험",intent.getStringExtra("nickname") ?: "A")
            "join" -> app.trip.join(intent.getStringExtra("code") ?: "",intent.getStringExtra("nickname") ?: "B")
            "grant" -> app.trip.grant(intent.getBooleanExtra("location",false),intent.getBooleanExtra("eta",false),intent.getBooleanExtra("follow",false))
            "finish" -> app.trip.finish()
            "refresh" -> app.trip.refresh()
            "respond" -> app.trip.state.value.prompt?.let { prompt ->
                // Debug intents exercise the same revision-bound controller action as the visible buttons.
                val matches=intent.getStringExtra("prompt_key")==prompt.key
                app.writeDiagnostic("command-probe.json",org.json.JSONObject()
                    .put("matched",matches).put("approve",intent.getBooleanExtra("approve",false)).toString())
                if(matches)
                    app.trip.respondDestination(prompt,intent.getBooleanExtra("approve",false))
            }
            "reconsider" -> app.trip.reconsiderDestination()
        }
    }
    private fun probe(command: String) {
        app.scope.launch {
            runCatching {
                withTimeout(20000) { app.gateway.state.first { it.naviConnection=="CONNECTED" && it.routeOption.isNotBlank() } }
                when(command) {
                    "official" -> app.gateway.requestRoute(Place(37.48544722,127.03636666,"42dot","서울시 강남구 남부순환로 2621","36khp37hh8gqcm5nqhe5","0"))
                    "roundtrip" -> app.gateway.state.value.let { app.gateway.requestRoute(requireNotNull(it.destination),it.waypoints) }
                    "roundtrip-zero" -> app.gateway.state.value.let { app.gateway.requestRoute(requireNotNull(it.destination).copy(poiSubId="0"),it.waypoints.map { p -> p.copy(poiSubId=p.poiSubId ?: "0") }) }
                    "roundtrip-empty" -> app.gateway.state.value.let { app.gateway.requestRoute(requireNotNull(it.destination).copy(poiSubId=""),it.waypoints.map { p -> p.copy(poiSubId=p.poiSubId ?: "") }) }
                    "official-b" -> app.gateway.requestRoute(Place(37.41275833,127.09481944,"현대백화점 판교점","경기도 성남시 분당구 판교역로 146","36khp2hdj2cfcm5nqhe3","0"))
                    "refresh" -> app.gateway.refresh()
                }
            }.onFailure { android.util.Log.e("MoaProbe",command,it) }
        }
    }
}
