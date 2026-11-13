package dev.moa.trip

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.moa.trip.core.DestinationPrompt

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
            manager.createNotificationChannel(NotificationChannel("destination","목적지 변경 요청",NotificationManager.IMPORTANCE_DEFAULT))
            val open=PendingIntent.getActivity(context,1,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching { manager.notify(7302,Notification.Builder(context,"destination").setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(if(prompt.kind=="LEADER") "일행에게도 변경을 요청할까요?" else "목적지 변경 요청이 왔습니다")
                .setContentText(prompt.target.name.take(40)+" · 열어서 확인")
                .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true).build()) }
        }
    }
}
