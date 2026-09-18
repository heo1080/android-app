package com.byd.dolphin.autoassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.byd.dolphin.autoassistant.MainActivity
import com.byd.dolphin.autoassistant.core.NextLog
import com.byd.dolphin.autoassistant.core.NextRuntime

class DolphinNextService : Service() {
    override fun onCreate() {
        super.onCreate()
        NextRuntime.start(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        NextLog.i("SERVICE", "foreground vehicle runtime started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NextRuntime.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        NextLog.w("SERVICE", "foreground service destroyed; runtime retained until process death")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "DolphinAssistant 차량 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "주행 상태와 사용자 설정 경고를 처리합니다."
                setShowBadge(false)
            }
        )
    }

    private fun notification(): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("DolphinAssistant")
            .setContentText("차량 상태 · BSD · AutoHold · 음성 서비스 실행 중")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "dolphin_next_vehicle"
        private const val NOTIFICATION_ID = 3200
    }
}
