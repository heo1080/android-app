package com.byd.dolphin.autoassistant.next.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.overlay.QuickBarShortcutManager

class NextBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NextLogger.i("BOOT", "receiver action=" + intent?.action)
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            QuickBarShortcutManager.refreshLauncherEntry(context)
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, NextRuntimeService::class.java)
        )
    }
}
