package com.byd.dolphin.autoassistant.next.integrated

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BootAppRule(
    val packageName: String,
    val label: String,
    val enabled: Boolean = true,
    val delaySeconds: Double = 0.0,
    val mediaPlay: Boolean = false,
    val mediaDelaySeconds: Double = 0.5
)

object IntegratedSettings {
    private const val PREF = "dolphin_next_integrated_v1"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun splitPrimaryRatio(context: Context): Int =
        prefs(context).getInt("split_primary_ratio", 50).coerceIn(20, 80)
    fun setSplitPrimaryRatio(context: Context, ratio: Int) {
        prefs(context).edit().putInt("split_primary_ratio", ratio.coerceIn(20, 80)).apply()
    }

    fun quickDockTimeoutSeconds(context: Context): Int =
        prefs(context).getInt("quick_dock_timeout", 15).coerceIn(5, 60)
    fun setQuickDockTimeoutSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt("quick_dock_timeout", seconds.coerceIn(5, 60)).apply()
    }

    fun floatingCollapseDelaySeconds(context: Context): Int =
        prefs(context).getInt("floating_collapse_delay", 15).coerceIn(5, 60)
    fun setFloatingCollapseDelaySeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt("floating_collapse_delay", seconds.coerceIn(5, 60)).apply()
    }

    fun floatingScalePercent(context: Context): Int =
        prefs(context).getInt("floating_scale", 100).coerceIn(50, 150)
    fun setFloatingScalePercent(context: Context, percent: Int) {
        prefs(context).edit().putInt("floating_scale", percent.coerceIn(50, 150)).apply()
    }

    fun floatingOpacityPercent(context: Context): Int =
        prefs(context).getInt("floating_opacity", 90).coerceIn(30, 100)
    fun setFloatingOpacityPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt("floating_opacity", percent.coerceIn(30, 100)).apply()
    }

    fun floatingX(context: Context, defaultValue: Int): Int =
        prefs(context).getInt("floating_x", defaultValue)
    fun floatingY(context: Context, defaultValue: Int): Int =
        prefs(context).getInt("floating_y", defaultValue)
    fun setFloatingPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt("floating_x", x).putInt("floating_y", y).apply()
    }

    fun floatingEnabled(context: Context): Boolean =
        prefs(context).getBoolean("floating_enabled", false)
    fun setFloatingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("floating_enabled", enabled).apply()
    }

    fun selectedDockApps(context: Context): Set<String> =
        prefs(context).getStringSet("dock_apps", emptySet())?.toSet().orEmpty()
    fun setSelectedDockApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet("dock_apps", packages).apply()
    }

    fun selectedFloatingApps(context: Context): Set<String> =
        prefs(context).getStringSet("floating_apps", emptySet())?.toSet().orEmpty()
    fun setSelectedFloatingApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet("floating_apps", packages).apply()
    }

    fun selectedDriverAudioApps(context: Context): Set<String> =
        prefs(context).getStringSet("driver_audio_apps", emptySet())?.toSet().orEmpty()
    fun setSelectedDriverAudioApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet("driver_audio_apps", packages).apply()
    }

    fun bootRules(context: Context): List<BootAppRule> {
        val text = prefs(context).getString("boot_rules", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        BootAppRule(
                            packageName = o.optString("packageName"),
                            label = o.optString("label"),
                            enabled = o.optBoolean("enabled", true),
                            delaySeconds = o.optDouble("delaySeconds", 0.0),
                            mediaPlay = o.optBoolean("mediaPlay", false),
                            mediaDelaySeconds = o.optDouble("mediaDelaySeconds", 0.5)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setBootRules(context: Context, rules: List<BootAppRule>) {
        val arr = JSONArray()
        rules.forEach { rule ->
            arr.put(JSONObject().apply {
                put("packageName", rule.packageName)
                put("label", rule.label)
                put("enabled", rule.enabled)
                put("delaySeconds", rule.delaySeconds.coerceIn(0.0, 600.0))
                put("mediaPlay", rule.mediaPlay)
                put("mediaDelaySeconds", rule.mediaDelaySeconds.coerceIn(0.0, 60.0))
            })
        }
        prefs(context).edit().putString("boot_rules", arr.toString()).apply()
    }

    fun hudAutoForward(context: Context): Boolean =
        prefs(context).getBoolean("hud_auto_forward", false)
    fun setHudAutoForward(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("hud_auto_forward", enabled).apply()
    }

    fun clusterTbtEnabled(context: Context): Boolean =
        prefs(context).getBoolean("cluster_tbt", false)
    fun setClusterTbtEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("cluster_tbt", enabled).apply()
    }
}
