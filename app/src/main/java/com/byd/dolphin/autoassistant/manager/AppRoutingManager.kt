package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable?
)

object AppRoutingManager {
    // 실제 런처로 실행할 수 있는 앱 전체를 불러옵니다.
    fun getInstalledApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        return activities
            .mapNotNull { resolved ->
                runCatching {
                    val info = resolved.activityInfo.applicationInfo
                    AppItem(
                        name = info.loadLabel(pm).toString(),
                        packageName = info.packageName,
                        icon = info.loadIcon(pm)
                    )
                }.getOrNull()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }
}
