package com.byd.dolphin.autoassistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.byd.dolphin.autoassistant.manager.IgnitionMonitor
import com.byd.dolphin.autoassistant.service.DolphinService
import com.byd.dolphin.autoassistant.util.DolphinLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DolphinLogger.init(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            IgnitionMonitor.markPotentialNewCycle(context)
        }
        DolphinLogger.i("BOOT_RECEIVER", "서비스 깨우기: ${intent.action}")
        val serviceIntent = Intent(context, DolphinService::class.java).apply {
            action = intent.action
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }.onFailure {
            DolphinLogger.e("BOOT_RECEIVER", "백그라운드 서비스 시작 제한 또는 오류", it)
        }
    }
}
