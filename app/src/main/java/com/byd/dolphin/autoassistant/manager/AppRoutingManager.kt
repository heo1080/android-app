package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable?
)

object AppRoutingManager {

    private const val PREF_NAME = "dolphin_app_routing_prefs"
    private const val KEY_ROUTED_PACKAGES = "key_routed_packages"

    // 기본 등록 앱: TMAP
    private val DEFAULT_PACKAGES = setOf("com.skt.tmap.ku", "com.skt.skaf.l001mtm091")

    fun getRoutedPackages(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_ROUTED_PACKAGES, DEFAULT_PACKAGES)?.toMutableSet() ?: DEFAULT_PACKAGES.toMutableSet()
    }

    fun addPackage(context: Context, packageName: String) {
        val packages = getRoutedPackages(context)
        packages.add(packageName)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ROUTED_PACKAGES, packages)
            .apply()
    }

    fun removePackage(context: Context, packageName: String) {
        val packages = getRoutedPackages(context)
        packages.remove(packageName)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ROUTED_PACKAGES, packages)
            .apply()
    }

    fun isAppRouted(context: Context, packageName: String): Boolean {
        return getRoutedPackages(context).contains(packageName)
    }

    // 설치된 사용자 앱 목록 불러오기
    fun getInstalledApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appList = mutableListOf<AppItem>()

        for (app in apps) {
            // 시스템 앱 중 런처에 뜨는 앱 및 사용자 설치 앱 필터링
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(app.packageName) != null) {
                val label = pm.getApplicationLabel(app).toString()
                val icon = pm.getApplicationIcon(app)
                appList.add(AppItem(label, app.packageName, icon))
            }
        }
        return appList.sortedBy { it.name }
    }
}
