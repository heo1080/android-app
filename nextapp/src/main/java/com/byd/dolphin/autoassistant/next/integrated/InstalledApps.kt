package com.byd.dolphin.autoassistant.next.integrated

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class InstalledAppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable?
)

object InstalledApps {
    fun launcherApps(context: Context): List<InstalledAppItem> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { r ->
                runCatching {
                    val info = r.activityInfo.applicationInfo
                    InstalledAppItem(
                        label = info.loadLabel(pm).toString(),
                        packageName = info.packageName,
                        icon = info.loadIcon(pm)
                    )
                }.getOrNull()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
