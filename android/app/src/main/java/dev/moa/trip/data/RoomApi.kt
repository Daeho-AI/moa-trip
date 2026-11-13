package dev.moa.trip.data

import dev.moa.trip.BuildConfig
import dev.moa.trip.core.TripSnapshot
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiFailure(val code: Int,message: String): IOException(message)

class RoomApi(private val settings: SecureSettings,private val scope: CoroutineScope) {
    val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    private val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS)
        .callTimeout(10,TimeUnit.SECONDS).pingInterval(10,TimeUnit.SECONDS).build()
    private var socket: WebSocket?=null
    private var generation=0
    private var reconnect: Job?=null
    var onSnapshot: (TripSnapshot)->Unit={}
    var onConnection: (Boolean)->Unit={}
    // The official Pleos emulator reaches the host through this reserved alias.
    // The release APK uses real SDK input; this exception only changes transport.
    private val localEmulator=android.os.Build.FINGERPRINT.contains("crp_avd")
    val base get()=settings.prefs.getString("serverUrl",if(BuildConfig.DEBUG || localEmulator) "http://10.0.2.2:8766" else "")!!.trimEnd('/')
    fun setBase(value: String) {
        val url=value.trim().trimEnd('/')
        val parsed=java.net.URI(url)
        require(parsed.userInfo==null && parsed.query==null && parsed.fragment==null && (parsed.path.isNullOrBlank() || parsed.path=="/")) { "서버 기본 주소만 입력하세요" }
        require(!parsed.host.isNullOrBlank() && (parsed.scheme=="https" || ((BuildConfig.DEBUG || localEmulator) && parsed.scheme=="http" && parsed.host in setOf("10.0.2.2","127.0.0.1","localhost")))) { "HTTPS 서버 주소가 필요합니다" }
        if(base!=url) { disconnect(); settings.clearSession(); settings.prefs.edit().putString("serverUrl",url).apply() }
    }
    suspend fun request(method: String,path: String,body: JSONObject?=null,auth: Boolean=true): JSONObject = withContext(Dispatchers.IO) {
        check(base.isNotBlank()) { "서버 주소를 설정하세요" }
        val builder=Request.Builder().url(base+path)
        if(auth) builder.header("Authorization","Bearer "+(settings.token() ?: error("세션 없음")))
        builder.method(method,if(method in setOf("GET","HEAD")) null else (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val text=response.body?.string() ?: "{}"
            if(!response.isSuccessful) throw ApiFailure(response.code,runCatching { JSONObject(text).optString("detail") }.getOrDefault("서버 오류 ${response.code}"))
            JSONObject(text)
        }
    }
    suspend fun ensureSession() {
        if(settings.token()==null) settings.saveToken(request("POST","/v1/device-sessions",auth=false).getString("token"))
    }
    fun decode(value: JSONObject)=json.decodeFromString<TripSnapshot>(value.toString())
    suspend fun snapshot(room: String)=decode(request("GET","/v1/rooms/$room"))
    fun connect(room: String) {
        disconnect(); val epoch=generation
        fun open() {
            if(epoch!=generation) return
            val url=base.replaceFirst("http","ws")+"/v1/rooms/$room/stream"
            val request=Request.Builder().url(url).header("Authorization","Bearer "+settings.token()).build()
            socket=client.newWebSocket(request,object: WebSocketListener() {
                override fun onOpen(webSocket: WebSocket,response: Response) { scope.launch { if(epoch==generation) onConnection(true) } }
                override fun onMessage(webSocket: WebSocket,text: String) { scope.launch {
                    if(epoch==generation) runCatching { json.decodeFromString<TripSnapshot>(text) }.onSuccess(onSnapshot)
                } }
                private fun lost() { scope.launch {
                    if(epoch==generation) { onConnection(false); reconnect?.cancel(); reconnect=scope.launch { delay(3000); open() } }
                } }
                override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) { lost() }
                override fun onClosed(webSocket: WebSocket,code: Int,reason: String) { lost() }
                override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { webSocket.close(code,null) }
            })
        }
        open()
    }
    fun disconnect() { generation++; reconnect?.cancel(); reconnect=null; socket?.cancel(); socket=null; onConnection(false) }
}
