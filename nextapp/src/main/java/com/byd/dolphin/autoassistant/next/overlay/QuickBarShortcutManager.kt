package com.byd.dolphin.autoassistant.next.overlay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.byd.dolphin.autoassistant.next.core.NextLogger

object QuickBarShortcutManager {
    fun refreshLauncherEntry(context: Context) {
        val app = context.applicationContext
        val component = ComponentName(app, QuickBarDrawerActivity::class.java)
        val pm = app.packageManager

        runCatching {
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            NextLogger.i(
                "QUICKBAR_SHORTCUT",
                "launcher component refreshed " + component.flattenToShortString()
            )
        }.onFailure {
            NextLogger.e("QUICKBAR_SHORTCUT", "refresh failed", it)
        }
    }
}
