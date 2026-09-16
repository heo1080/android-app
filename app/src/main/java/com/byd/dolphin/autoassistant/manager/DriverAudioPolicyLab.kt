package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.media.AudioManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.io.File

/**
 * Research scaffold for package/UID -> driver-only audio routing.
 *
 * We already verified BYD stream 14 as the physical driver-only path, but v30.6
 * intentionally does NOT intercept arbitrary third-party playback yet. Android 10
 * playback capture / AudioPolicyMix permissions and the OEM safety-audio priority
 * must be proven first. Failing open to the stock route is mandatory.
 */
object DriverAudioPolicyLab {
    private const val TAG = "APP_AUDIO_LAB"
    private const val PREF = "dolphin_app_audio_lab_v1"
    private const val KEY_PACKAGES = "packages"

    fun selectedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_PACKAGES, emptySet())
            ?.filter { it.matches(Regex("[A-Za-z0-9_.]+")) }
            ?.toSet().orEmpty()

    fun saveSelectedPackages(context: Context, packages: Set<String>) {
        val clean = packages.filter { it.matches(Regex("[A-Za-z0-9_.]+")) }.toSet()
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PACKAGES, clean).apply()
        DolphinLogger.i(TAG, "selected package count=${clean.size}; active interception remains disabled")
    }

    fun summary(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val entries = selectedPackages(context).sorted().joinToString { pkg ->
            val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
            "$pkg(uid=${uid ?: "missing"})"
        }.ifBlank { "<none>" }
        return buildString {
            appendLine("driverOnlyRoute=BYD_LEGACY_STREAM_14_CONFIRMED")
            appendLine("selectedApps=$entries")
            appendLine("audioMode=${am.mode}")
            appendLine("activePerAppInterception=false")
            appendLine("priorityDesign=OEM_SAFETY > STOCK_RECOVERY > DRIVER_ROUTE > DEFAULT")
            appendLine("plannedRouteOrder=UID_AFFINITY -> AudioPolicyMix_RULE_MATCH_UID -> usage fallback")
            appendLine("safetyArbitration=IDLE -> ROUTING -> SAFETY_OVERRIDE -> fail-open DEFAULT")
            appendLine("reason=Android10 AudioPolicyMix/playback-capture privileges and OEM warning preemption are not yet verified on this firmware")
        }
    }

    fun writeSnapshot(context: Context, directory: File) {
        runCatching { File(directory, "app_driver_audio_lab.txt").writeText(summary(context)) }
            .onFailure { DolphinLogger.e(TAG, "audio lab snapshot failed", it) }
    }
}
