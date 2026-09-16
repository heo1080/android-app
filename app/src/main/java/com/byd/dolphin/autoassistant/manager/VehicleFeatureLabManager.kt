package com.byd.dolphin.autoassistant.manager

import android.content.Context
import com.byd.dolphin.autoassistant.hud.ClusterMirrorManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.io.File

/** Read-only capability lab for features that are not safe to guess/write yet. */
object VehicleFeatureLabManager {
    private const val TAG = "FEATURE_LAB"
    private const val SETTING_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"

    fun summary(context: Context): String = buildString {
        appendLine("BYD v30.6 feature lab (READ ONLY)")
        appendLine("mirror.current=${MirrorMemoryManager.readCurrent(context)}")
        appendLine("insideLightDoor=${readNoArgInt(context, SETTING_CLASS, "getInsideLightDoorState")}")
        appendLine("cluster.INSTheme=${readNoArgInt(context, SETTING_CLASS, "getINSTheme")}")
        appendLine("driverSeatBack=${readNoArgInt(context, SETTING_CLASS, "getDriverSeatBack")}")
        appendLine("driverSeatAutoReturn=${readNoArgInt(context, SETTING_CLASS, "getDriverSeatAutoReturn")}")
        appendLine("rearViewMirrorFlip=${readNoArgInt(context, SETTING_CLASS, "getRearViewMirrorFlip")}")
        appendLine("insideRearMirrorScreenSwitch=${readNoArgInt(context, SETTING_CLASS, "getInsideRearMirrorScreenSwitchState")}")
        appendLine("clusterTbt=${ClusterMirrorManager.capabilitySummary(context)}")
        appendLine("seatMemoryWrites=BLOCKED_UNTIL_POSITION_GETTER_SETTER_PAIR_VERIFIED")
        appendLine("clusterThemeWrites=BLOCKED_UNTIL_THEME_VALUE_MAPPING_VERIFIED")
        appendLine("fullClusterMirroring=BLOCKED_UNTIL_DISPLAY_TARGET_AND_RECOVERY_VERIFIED")
        appendLine()
        appendLine("--- candidate class inventory (metadata only) ---")
        CANDIDATE_CLASSES.forEach { className ->
            appendLine("[$className]")
            appendLine(methodInventory(className))
        }
        appendLine("[$SETTING_CLASS filtered]")
        appendLine(filteredMethods(SETTING_CLASS, listOf("Seat", "Mirror", "InsideLight", "INSTheme")))
    }

    fun writeSnapshot(context: Context, directory: File) {
        runCatching {
            File(directory, "vehicle_feature_lab.txt").writeText(summary(context))
            DolphinLogger.i(TAG, "feature lab snapshot written")
        }.onFailure { DolphinLogger.e(TAG, "feature lab snapshot failed", it) }
    }

    private fun readNoArgInt(context: Context, className: String, methodName: String): Int? = runCatching {
        val clazz = Class.forName(className)
        val instance = clazz.getMethod("getInstance", Context::class.java)
            .invoke(null, BydPermissionContext.wrap(context)) ?: return@runCatching null
        (clazz.getMethod(methodName).invoke(instance) as? Number)?.toInt()
    }.onFailure { DolphinLogger.w(TAG, "$methodName read unavailable: ${(it.cause ?: it).message}") }.getOrNull()

    private fun methodInventory(className: String): String = runCatching {
        val clazz = Class.forName(className)
        clazz.methods
            .filter { method -> method.declaringClass.name == className }
            .sortedBy { it.name }
            .joinToString("\n") { method ->
                "${method.returnType.simpleName} ${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"
            }
            .ifBlank { "<class present, no public declared methods>" }
    }.getOrElse { "<unavailable:${(it.cause ?: it).javaClass.simpleName}>" }

    private fun filteredMethods(className: String, terms: List<String>): String = runCatching {
        val clazz = Class.forName(className)
        clazz.methods
            .filter { method -> terms.any { term -> method.name.contains(term, ignoreCase = true) } }
            .sortedBy { it.name }
            .joinToString("\n") { method ->
                "${method.returnType.simpleName} ${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"
            }
            .ifBlank { "<none>" }
    }.getOrElse { "<unavailable:${(it.cause ?: it).javaClass.simpleName}>" }

    private val CANDIDATE_CLASSES = listOf(
        "android.hardware.bydauto.seat.BYDAutoSeatDevice",
        "android.hardware.bydauto.seat.BYDAutoSeatManager",
        "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
        "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
    )
}
