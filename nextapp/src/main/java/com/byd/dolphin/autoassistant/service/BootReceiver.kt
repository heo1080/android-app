package com.byd.dolphin.autoassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.byd.dolphin.autoassistant.core.NextLog

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val service = Intent(context, DolphinNextService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        }.onSuccess {
            NextLog.init(context)
            NextLog.i("BOOT", "boot receiver started vehicle service")
        }.onFailure {
            NextLog.init(context)
            NextLog.e("BOOT", "boot service start failed", it)
        }
    }
}
