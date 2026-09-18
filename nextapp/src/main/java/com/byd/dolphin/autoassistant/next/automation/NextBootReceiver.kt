package com.byd.dolphin.autoassistant.next.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.byd.dolphin.autoassistant.next.core.NextLogger

class NextBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NextLogger.i("BOOT", "receiver action=" + intent?.action)
        ContextCompat.startForegroundService(
            context,
            Intent(context, NextRuntimeService::class.java)
        )
    }
}
