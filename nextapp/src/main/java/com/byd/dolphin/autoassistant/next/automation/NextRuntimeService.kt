package com.byd.dolphin.autoassistant.next.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.byd.dolphin.autoassistant.next.NextRuntime
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.cluster.TbtCorrelationProbe
import com.byd.dolphin.autoassistant.next.integrated.IntegratedSettings
import com.byd.dolphin.autoassistant.next.launcher.LauncherStartController
import com.byd.dolphin.autoassistant.next.launcher.LauncherWatchdog
import com.byd.dolphin.autoassistant.next.overlay.QuickDockOverlay

class NextRuntimeService : Service() {
    private lateinit var ignition: NextIgnitionMonitor
    private lateinit var bootAutomation: BootAutomationController
    private lateinit var launcherWatchdog: LauncherWatchdog

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            3301,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("DolphinAssistant Next")
                .setContentText("시동 자동화 · 통합 기능 런타임")
                .setOngoing(true)
                .build()
        )
        NextRuntime.start(this)
        TbtCorrelationProbe.register(this)
        bootAutomation = BootAutomationController(this)
        launcherWatchdog = LauncherWatchdog(this)
        ignition = NextIgnitionMonitor(
            this,
            onPowerOn = {
                NextLogger.i("RUNTIME", "ignition ON")
                LauncherStartController.showHome(this, "ignition_on")
                launcherWatchdog.startForIgnition()
                bootAutomation.onIgnitionOn()
                if (IntegratedSettings.floatingEnabled(this)) {
                    QuickDockOverlay.showFloating(this)
                }
            },
            onPowerOff = {
                NextLogger.i("RUNTIME", "ignition OFF")
                launcherWatchdog.stop()
                bootAutomation.onIgnitionOff()
                QuickDockOverlay.hideFloating(this)
            }
        )
        ignition.start()
    }

    override fun onDestroy() {
        ignition.stop()
        launcherWatchdog.stop()
        TbtCorrelationProbe.unregister(this)
        QuickDockOverlay.hideFloating(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "DolphinAssistant Runtime",
                NotificationManager.IMPORTANCE_MIN
            )
        )
    }

    companion object {
        private const val CHANNEL = "dolphin_next_runtime"
    }
}
