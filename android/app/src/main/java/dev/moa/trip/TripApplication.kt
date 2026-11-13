package dev.moa.trip

import android.app.Application
import dev.moa.trip.sdk.PleosGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class TripApplication: Application() {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    lateinit var gateway: PleosGateway
    lateinit var trip: TripController
    val json=Json { ignoreUnknownKeys=true; encodeDefaults=true; prettyPrint=true }
    override fun onCreate() {
        super.onCreate()
        gateway=PleosGateway(this,scope)
        gateway.start()
        trip=TripController(this,scope,gateway)
        trip.start()
        if(BuildConfig.DEBUG) scope.launch {
            gateway.state.collect { state ->
                withContext(Dispatchers.IO) { writeDiagnostic("sdk-probe.json",json.encodeToString(state)) }
                delay(500)
            }
        }
    }
    fun writeDiagnostic(name: String,text: String) {
        val temp=File(filesDir,"$name.new")
        temp.writeText(text)
        check(temp.renameTo(File(filesDir,name)))
    }
}
