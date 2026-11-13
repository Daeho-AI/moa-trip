package dev.moa.trip

import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.moa.trip.core.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Pleos system colors: white/neutral surfaces, charcoal filled buttons; no dynamic wallpaper tint.
private val Day=lightColorScheme(primary=Color(0xFF44474F),onPrimary=Color.White,
    background=Color.White,onBackground=Color(0xFF121315),surface=Color.White,onSurface=Color(0xFF121315),
    surfaceVariant=Color(0xFFF5F6F7),onSurfaceVariant=Color(0xFF62656D),outline=Color(0xFF747780),outlineVariant=Color(0xFFE1E2E5))
private val Night=darkColorScheme(primary=Color(0xFFE4E5E8),onPrimary=Color(0xFF151618),
    background=Color(0xFF1D1E20),onBackground=Color(0xFFF4F4F5),surface=Color(0xFF303134),onSurface=Color(0xFFF4F4F5),
    surfaceVariant=Color(0xFF28292C),onSurfaceVariant=Color(0xFFBDBFC5),outline=Color(0xFF94979F),outlineVariant=Color(0xFF484A50))

fun syncText(state: String)=when(state) {
    "WAITING_DESTINATION"->"목적지 대기"
    "NEEDS_CONFIRMATION"->"변경 확인 대기"
    "WAITING_CONNECTION"->"내비 확인 중"
    "APPLYING"->"목적지 등록 중"
    "APPLIED","MATCHED"->"같은 목적지"
    "DECLINED","LOCAL_OVERRIDE"->"현재 목적지 유지"
    "WRITE_UNCERTAIN"->"내비 결과 확인 중"
    "DEFERRED_POLICY"->"차량 상태 확인 중"
    "CAPACITY_CONFLICT"->"경유지 확인 필요"
    "FAILED"->"내비 확인 필요"
    "ARRIVED"->"도착"
    else->"확인 중"
}
private fun clockText(time: Long)=SimpleDateFormat("HH:mm",Locale.KOREA).format(Date(time))

@Composable
fun TripScreen(app: TripApplication,requestPermissions: ()->Unit,probe: (String)->Unit) {
    val sdk by app.gateway.state.collectAsState()
    val ui by app.trip.state.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while(true) { delay(1000); now=SystemClock.elapsedRealtime() } }
    var nickname by remember { mutableStateOf("내 차량") }
    var title by remember { mutableStateOf("함께 떠나는 여행") }
    var code by remember { mutableStateOf("") }
    var joinMode by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(app.trip.api.base) }
    var settings by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf(false) }
    var end by remember { mutableStateOf(false) }
    var deferredPrompt by remember { mutableStateOf<DestinationPrompt?>(null) }
    val snap=ui.snapshot
    val live=ui.connected && now-ui.receivedAtMs<10000
    val prompt=ui.prompt
    // A settings dialog must not stay interactive when the gear/UX state changes.
    LaunchedEffect(sdk.parked) { if(!sdk.parked) { settings=false; connection=false; invite=false; end=false } }
    MaterialTheme(colorScheme=if(isSystemInDarkTheme()) Night else Day) {
        val colors=MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(),color=colors.background) {
            Column(Modifier.fillMaxSize().padding(horizontal=36.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min=104.dp),verticalAlignment=Alignment.CenterVertically) {
                    RouteGlyph(36,colors.onSurface)
                    Text("모아트립",Modifier.padding(start=14.dp),fontSize=30.sp,fontWeight=FontWeight.SemiBold)
                    if(snap!=null) Text(snap.room.title.take(sdk.uxMaxStringLength),Modifier.weight(1f).padding(start=28.dp),fontSize=22.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    else Spacer(Modifier.weight(1f))
                    if(snap?.self?.leader==true) QuietButton("초대 코드",sdk.parked && !ui.busy){invite=true}
                    Spacer(Modifier.width(12.dp))
                    QuietButton(if(snap==null) "연결 설정" else "참여 설정",sdk.parked && !ui.busy){if(snap==null) connection=true else settings=true}
                }
                HorizontalDivider(color=colors.outlineVariant)
                if(ui.busy) Text("처리 중…",Modifier.padding(vertical=8.dp),fontSize=18.sp,color=colors.onSurfaceVariant)
                ui.error?.let { Text(it,Modifier.fillMaxWidth().padding(vertical=12.dp),fontSize=18.sp,color=colors.error,maxLines=3,overflow=TextOverflow.Ellipsis) }
                if(snap==null) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical=36.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Column(Modifier.widthIn(max=780.dp).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                            Text(if(joinMode) "여행에 참여하기" else "여행방 만들기",fontSize=40.sp,fontWeight=FontWeight.SemiBold)
                            Text("일행의 위치와 도착 시간을 한곳에서 확인하세요.",fontSize=22.sp,color=colors.onSurfaceVariant)
                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                ActionButton("새 여행",!joinMode,sdk.parked){joinMode=false}
                                ActionButton("초대 코드로 참여",joinMode,sdk.parked){joinMode=true}
                            }
                            Field("내 차량 이름",nickname,sdk.parked){nickname=it.take(16)}
                            if(joinMode) Field("초대 코드 8자리",code,sdk.parked){code=it.uppercase().take(9)}
                            else Field("여행 이름",title,sdk.parked){title=it.take(40)}
                            ActionButton(if(joinMode) "여행에 참여" else "여행방 만들기",true,
                                sdk.parked && !ui.busy && nickname.isNotBlank() && (if(joinMode) code.replace("-","").length==8 else title.isNotBlank()),Modifier.fillMaxWidth()) {
                                if(joinMode) app.trip.join(code,nickname.trim()) else app.trip.create(title.trim(),nickname.trim())
                            }
                            Text(if(sdk.parked) "목적지는 차량마다 확인한 뒤 변경됩니다." else "주차 후 여행방을 만들거나 참여할 수 있습니다.",fontSize=20.sp,color=colors.onSurfaceVariant)
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val wide=maxWidth>=1080.dp
                        val summary: @Composable ()->Unit={ DestinationPanel(snap,ui,sdk,live,
                            onReview={if(ui.prompt!=null) deferredPrompt=null else app.trip.reconsiderDestination()},
                            onShare={app.trip.toggleBroadcast()}) }
                        val members: @Composable ()->Unit={ VehicleList(snap,ui,sdk,live,now) }
                        if(wide) Row(Modifier.fillMaxSize().padding(vertical=28.dp),horizontalArrangement=Arrangement.spacedBy(36.dp)) {
                            Column(Modifier.weight(0.38f).fillMaxHeight().verticalScroll(rememberScrollState())) { summary() }
                            Column(Modifier.weight(0.62f).fillMaxHeight().verticalScroll(rememberScrollState())) { members() }
                        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(28.dp)) { summary(); members() }
                    }
                }
                HorizontalDivider(color=colors.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min=78.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if(live) colors.onSurface else colors.outline,RoundedCornerShape(4.dp)))
                    Text(if(snap==null) "여행 준비" else if(live) "여행방 연결됨" else "다시 연결하는 중",Modifier.padding(start=12.dp).weight(1f),fontSize=18.sp,color=colors.onSurfaceVariant)
                    if(snap!=null) {
                        TextButton(onClick={connection=true},enabled=sdk.parked,modifier=Modifier.heightIn(min=56.dp)){Text("연동 상태",fontSize=18.sp)}
                        TextButton(onClick={end=true},enabled=sdk.parked && !ui.busy,modifier=Modifier.heightIn(min=56.dp)){Text("내 여행 종료",fontSize=18.sp)}
                    }
                }
            }
        }
        if(prompt!=null && sdk.uxCanConfirm && prompt!=deferredPrompt && !settings && !connection && !invite && !end) {
            key(prompt) {
                ConfirmPanel(
                    title=if(prompt.kind=="LEADER") "일행에게도 변경을 요청할까요?" else "목적지를 변경할까요?",
                    confirm=if(prompt.kind=="LEADER") "일행에게 요청" else "목적지 변경",
                    cancel=if(prompt.kind=="LEADER") "나만 변경" else "현재 목적지 유지",
                    enabled=!ui.busy && sdk.uxCanConfirm,
                    onConfirm={app.trip.respondDestination(prompt,true)},
                    onCancel={app.trip.respondDestination(prompt,false)},
                    onDismiss={deferredPrompt=prompt}) {
                    Text(prompt.target.name.take(sdk.uxMaxStringLength),fontSize=32.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(sdk.parked && prompt.target.address.isNotBlank()) Text(prompt.target.address.take(sdk.uxMaxStringLength),fontSize=20.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(if(prompt.kind=="LEADER") "각 차량이 확인하면 목적지가 변경됩니다."
                        else if(prompt.localRoute.waypoints.isNotEmpty()) "내 경유지 ${prompt.localRoute.waypoints.size}개는 유지됩니다."
                        else "확인하면 내 순정 내비에 등록됩니다.",fontSize=20.sp,color=colors.onSurfaceVariant)
                    ui.error?.let { Text(it,fontSize=18.sp,color=colors.error,maxLines=2,overflow=TextOverflow.Ellipsis) }
                }
            }
        }
        if(settings && snap!=null) {
            var location by remember { mutableStateOf(snap.self.grants.locationEnabled) }
            var eta by remember { mutableStateOf(snap.self.grants.etaEnabled) }
            ConfirmPanel("참여 설정","저장","닫기",sdk.parked && !ui.busy,
                {app.trip.grant(location,eta,false);settings=false},{settings=false},{settings=false}) {
                Choice("내 위치 공유",location){location=it}
                Choice("내 도착 예정 시간 공유",eta){eta=it}
                Text("목적지 변경 요청은 매번 직접 확인합니다.",fontSize=20.sp,color=colors.onSurfaceVariant)
            }
        }
        if(invite && snap!=null) ConfirmPanel("일행 초대","확인",null,true,{invite=false},{},{invite=false}) {
            Text(snap.self.inviteCode.orEmpty().chunked(4).joinToString("  "),fontSize=42.sp,fontWeight=FontWeight.Medium)
            Text("코드를 입력하면 이 여행방에 참여할 수 있습니다.",fontSize=20.sp,color=colors.onSurfaceVariant)
            snap.self.inviteExpiresAt?.let { Text("${clockText(it)}까지 유효 · 최대 5대",fontSize=20.sp,color=colors.onSurfaceVariant) }
        }
        if(end) ConfirmPanel("내 여행을 종료할까요?","여행 종료","계속 함께 가기",sdk.parked && !ui.busy,
            {app.trip.finish();end=false},{end=false},{end=false}) {
            Text("내 위치 공유가 종료됩니다. 현재 내비 안내와 일행의 여행은 계속됩니다.",fontSize=22.sp)
        }
        if(connection) ConfirmPanel("연결 설정","확인",null,true,{connection=false},{},{connection=false}) {
            Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                if(snap==null) {
                    Field("여행방 서버 주소",server,sdk.parked){server=it}
                    QuietButton("주소 저장",sdk.parked){app.trip.configureServer(server)}
                }
                Text("순정 내비  ${if(sdk.naviConnection=="CONNECTED") "연결됨" else "연결 확인 중"}",fontSize=22.sp)
                Text("현재 목적지  ${sdk.destination?.name ?: "없음"}",fontSize=20.sp)
                Text("위치  ${if(sdk.locationPermission) "사용 가능" else "권한 확인 필요"}",fontSize=20.sp)
                QuietButton("다시 확인",!ui.busy){app.trip.refresh();requestPermissions()}
                if(BuildConfig.DEBUG) Text("NaviHelper · ${sdk.naviMode}\n내비 요청 ${sdk.writeCount}회 · ${BuildConfig.VERSION_NAME}",fontSize=16.sp,color=colors.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun DestinationPanel(snap: TripSnapshot,ui: TripUiState,sdk: GatewayState,live: Boolean,onReview: ()->Unit,onShare: ()->Unit) {
    val colors=MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=colors.surfaceVariant,contentColor=colors.onSurface) {
        Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                RouteGlyph(26,colors.onSurfaceVariant)
                Text("일행 목적지",Modifier.padding(start=12.dp),fontSize=21.sp,color=colors.onSurfaceVariant)
            }
            Text(snap.room.destination?.name?.take(sdk.uxMaxStringLength) ?: "아직 정하지 않았어요",fontSize=36.sp,lineHeight=44.sp,fontWeight=FontWeight.SemiBold,maxLines=3,overflow=TextOverflow.Ellipsis)
            snap.room.destination?.address?.takeIf { it.isNotBlank() }?.let { Text(it.take(sdk.uxMaxStringLength),fontSize=21.sp,color=colors.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis) }
            HorizontalDivider(color=colors.outlineVariant)
            Text(if(ui.prompt!=null) "새 목적지 요청이 있습니다" else syncText(ui.syncState),fontSize=22.sp,fontWeight=FontWeight.Medium)
            if(!samePlace(snap.room.destination,sdk.destination) && sdk.destination!=null)
                Text("내 내비 · ${sdk.destination?.name}",fontSize=20.sp,color=colors.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(ui.prompt!=null || (!samePlace(snap.room.destination,sdk.destination) && (if(snap.self.leader) sdk.destination!=null else snap.room.destination!=null)))
                QuietButton(if(ui.prompt!=null) "변경 요청 확인" else if(snap.self.leader) "현재 목적지 공유" else "목적지 다시 확인",!ui.busy && sdk.uxKnown && (!snap.self.leader || snap.room.leaderBroadcastEnabled),onReview)
            Spacer(Modifier.height(8.dp))
            val time=snap.assembly.latestKnownArrivalAt
            Text("함께 도착",fontSize=21.sp,color=colors.onSurfaceVariant)
            Text(if(live && snap.assembly.complete && time!=null) clockText(time) else "확인 중",fontSize=44.sp,fontWeight=FontWeight.Medium)
            Text(if(live) "${snap.assembly.expected}대 중 ${snap.assembly.known}대 도착 시간 확인" else "연결 후 도착 시간을 갱신합니다",fontSize=20.sp,color=colors.onSurfaceVariant)
            if(snap.self.leader) {
                HorizontalDivider(color=colors.outlineVariant)
                QuietButton(if(snap.room.leaderBroadcastEnabled) "변경 요청 알림 끄기" else "변경 요청 알림 켜기",sdk.parked && !ui.busy,onShare)
            }
        }
    }
}

@Composable private fun VehicleList(snap: TripSnapshot,ui: TripUiState,sdk: GatewayState,live: Boolean,now: Long) {
    val colors=MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(bottom=26.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("함께 가는 차량",fontSize=27.sp,fontWeight=FontWeight.SemiBold)
        Text("${snap.members.count { it.participationState=="ACTIVE" }}대",Modifier.padding(start=14.dp),fontSize=22.sp,color=colors.onSurfaceVariant)
    }
    snap.members.take(sdk.uxMaxItems).forEachIndexed { index,member ->
        if(index>0) HorizontalDivider(color=colors.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical=26.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically) {
            Surface(shape=RoundedCornerShape(12.dp),color=colors.surfaceVariant) { Box(Modifier.size(62.dp),contentAlignment=Alignment.Center){CarGlyph(colors.onSurface)} }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(member.nickname,fontSize=24.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false))
                    Text(if(member.id==snap.self.id) "  내 차" else if(member.leader) "  리더" else "",fontSize=18.sp,color=colors.onSurfaceVariant)
                }
                Text(if(member.participationState=="FINISHED") "여행 종료" else syncText(member.sync.state),fontSize=20.sp,color=colors.onSurfaceVariant)
                val p=member.location
                val elapsed=(now-ui.receivedAtMs).coerceAtLeast(0)
                val detail=if(live && p!=null && elapsed+p.ageMs<60000) {
                    val own=sdk.location?.takeIf { it.measuredAtElapsedMs?.let { measured -> now-measured in 0..14999 }==true }
                    val age=(elapsed+p.ageMs)/1000
                    val distance=own?.let { distanceMeters(it.latitude,it.longitude,p.latitude,p.longitude)/1000 }
                    if(member.id==snap.self.id) "위치 공유 중 · ${age}초 전"
                    else (distance?.let { "직선 %.1f km · ".format(it) } ?: "")+"${age}초 전 위치"
                } else if(live && member.online) "위치 수신 대기" else "연결 확인 중"
                Text(detail,fontSize=18.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                val eta=member.eta?.takeIf { live && now-ui.receivedAtMs+it.ageMs<15000 }
                Text(if(eta==null) "—" else clockText(eta.arrivalAt),fontSize=34.sp,fontWeight=FontWeight.Medium)
                Text(if(eta==null) "도착 시간 대기" else "예상 도착",fontSize=17.sp,color=colors.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider(color=colors.outlineVariant)
    Text("도착 시간은 각 차량의 순정 내비에서 받아옵니다.",Modifier.padding(top=24.dp),fontSize=18.sp,color=colors.onSurfaceVariant)
}

@Composable private fun Field(label: String,value: String,enabled: Boolean,onChange: (String)->Unit) {
    OutlinedTextField(value,onChange,label={Text(label,fontSize=20.sp)},singleLine=true,enabled=enabled,
        textStyle=LocalTextStyle.current.copy(fontSize=24.sp),shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth().heightIn(min=76.dp))
}
@Composable private fun ActionButton(label: String,filled: Boolean=true,enabled: Boolean=true,modifier: Modifier=Modifier,onClick: ()->Unit) {
    val colors=MaterialTheme.colorScheme
    Button(onClick,modifier.heightIn(min=64.dp),enabled=enabled,shape=RoundedCornerShape(10.dp),
        colors=ButtonDefaults.buttonColors(containerColor=if(filled) colors.primary else colors.surfaceVariant,contentColor=if(filled) colors.onPrimary else colors.onSurface),
        contentPadding=PaddingValues(horizontal=24.dp,vertical=14.dp)) {Text(label,fontSize=21.sp,fontWeight=FontWeight.Medium,maxLines=2)}
}
@Composable private fun QuietButton(label: String,enabled: Boolean=true,onClick: ()->Unit)=ActionButton(label,false,enabled,onClick=onClick)
@Composable private fun Choice(label: String,checked: Boolean,onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=72.dp).clickable{onChange(!checked)},verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),fontSize=24.sp); Switch(checked,onChange)
    }
}
@Composable private fun ConfirmPanel(title: String,confirm: String,cancel: String?,enabled: Boolean,
    onConfirm: ()->Unit,onCancel: ()->Unit,onDismiss: ()->Unit,content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=760.dp).fillMaxWidth().padding(24.dp),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
            Column(Modifier.padding(32.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                Text(title,fontSize=30.sp,fontWeight=FontWeight.SemiBold,maxLines=2)
                content()
                Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    if(cancel!=null) ActionButton(cancel,false,enabled,Modifier.weight(1f),onCancel)
                    ActionButton(confirm,true,enabled,Modifier.weight(1f),onConfirm)
                }
            }
        }
    }
}
@Composable private fun RouteGlyph(size: Int,color: Color) {
    Canvas(Modifier.size(size.dp).semantics { contentDescription="목적지" }) {
        val s=this.size.width/32f
        drawCircle(color,5*s,androidx.compose.ui.geometry.Offset(8*s,24*s),style=Stroke(2.4f*s))
        drawCircle(color,5*s,androidx.compose.ui.geometry.Offset(24*s,8*s),style=Stroke(2.4f*s))
        drawPath(Path().apply {moveTo(8*s,19*s);lineTo(8*s,8*s);quadraticBezierTo(8*s,3*s,14*s,3*s);lineTo(18*s,3*s)},color,style=Stroke(2.4f*s,cap=StrokeCap.Round))
        drawPath(Path().apply {moveTo(24*s,13*s);lineTo(24*s,24*s);quadraticBezierTo(24*s,29*s,18*s,29*s);lineTo(14*s,29*s)},color,style=Stroke(2.4f*s,cap=StrokeCap.Round))
    }
}
@Composable private fun CarGlyph(color: Color) {
    Canvas(Modifier.size(36.dp).semantics { contentDescription="차량" }) {
        val s=size.width/36f
        drawPath(Path().apply {moveTo(5*s,26*s);lineTo(5*s,17*s);lineTo(9*s,9*s);lineTo(27*s,9*s);lineTo(31*s,17*s);lineTo(31*s,26*s);close()},color,style=Stroke(2*s))
        drawLine(color,androidx.compose.ui.geometry.Offset(5*s,17*s),androidx.compose.ui.geometry.Offset(31*s,17*s),2*s)
        drawLine(color,androidx.compose.ui.geometry.Offset(10*s,22*s),androidx.compose.ui.geometry.Offset(13*s,22*s),2*s)
        drawLine(color,androidx.compose.ui.geometry.Offset(23*s,22*s),androidx.compose.ui.geometry.Offset(26*s,22*s),2*s)
        drawLine(color,androidx.compose.ui.geometry.Offset(8*s,26*s),androidx.compose.ui.geometry.Offset(8*s,30*s),3*s)
        drawLine(color,androidx.compose.ui.geometry.Offset(28*s,26*s),androidx.compose.ui.geometry.Offset(28*s,30*s),3*s)
    }
}
