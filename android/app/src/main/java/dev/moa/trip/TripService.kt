package dev.moa.trip

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import dev.moa.trip.core.DestinationPrompt
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

class TripService: Service() {
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("trip","진행 중 여행",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(7301,Notification.Builder(this,"trip").setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("모아트립 여행 중").setContentText("일행 위치·도착 시간 공유 · 목적지는 확인 후 변경").setContentIntent(open).setOngoing(true).build())
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int=START_NOT_STICKY
    companion object {
        fun start(context: Context) { context.startForegroundService(Intent(context,TripService::class.java)) }
        fun stop(context: Context) { clearProposal(context); context.stopService(Intent(context,TripService::class.java)) }
        fun clearProposal(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(7302) }
        fun showProposal(context: Context,prompt: DestinationPrompt) {
            val manager=context.getSystemService(NotificationManager::class.java)
            // Android does not raise an existing channel's importance; use a new channel.
            val channel="destination_confirmation_v2"
            manager.createNotificationChannel(NotificationChannel(channel,"목적지 변경 확인",NotificationManager.IMPORTANCE_HIGH).apply {
                description="순정 내비를 보면서 목적지 변경 여부를 선택합니다"
            })
            val open=PendingIntent.getActivity(context,1,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val app=context.applicationContext as TripApplication
            val payload=app.json.encodeToString(prompt)
            // Bind to the COMPLETE prompt, including the personal route shown at notification time.
            // Old PendingIntents must never acquire a newer prompt's authority through UPDATE_CURRENT.
            val digest=MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
            fun action(approve: Boolean,label: String): Notification.Action {
                val intent=Intent(context,DestinationActionReceiver::class.java)
                    .setAction(DestinationActionReceiver.RESPOND)
                    .setData(Uri.parse("moatrip://destination/$digest/$approve"))
                    .putExtra("prompt",payload).putExtra("approve",approve)
                val pending=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_IMMUTABLE)
                return Notification.Action.Builder(null,label,pending).build()
            }
            // A route-change consent event: no messaging/call/emergency or full-screen intent.
            runCatching { manager.notify(7302,Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_moatrip)
                .setCategory(Notification.CATEGORY_NAVIGATION).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentTitle(prompt.title)
                .setContentText((if(prompt.stopFull) "경유지 최대 2곳 · 내 경유지 정리 후 확인" else prompt.target.name+if(prompt.isStop && !prompt.isLeader && prompt.localRoute.waypoints.isNotEmpty()) " · 내 경유지 다음에 추가" else "").take(app.gateway.state.value.uxMaxStringLength.coerceIn(1,40)))
                .addAction(action(false,prompt.cancelLabel))
                .apply { if(!prompt.stopFull) addAction(action(true,prompt.confirmLabel)) }
                .setContentIntent(open).setOnlyAlertOnce(false).setAutoCancel(false).build()) }
        }
    }
}
