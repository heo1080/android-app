package com.byd.dolphin.autoassistant.manager

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * Read-only hazard diagnostics.
 *
 * The supplied DiLink 3 framework exposes `getDoubleFlashLightState()` but no
 * public hazard setter. Automatic or synthetic commands are therefore blocked.
 */
class HazardLightManager(context: Context) {
    private val appContext = context.applicationContext

    enum class Gear { P, R, N, D }

    fun onGearChanged(newGear: Gear, currentSpeedKmH: Float) {
        if (SettingsManager.isHazardAutoEnabled(appContext)) {
            DolphinLogger.w(
                TAG,
                "기어 $newGear speed=$currentSpeedKmH: 비상등 자동화 차단 " +
                    "(공개 setter 미확인, getter=${readHazardState()})"
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onSpeedChanged(speedKmH: Float) = Unit

    fun turnOnHazard() = blockRequest(true)

    fun turnOffHazard() = blockRequest(false)

    fun toggleHazard() {
        DolphinLogger.w(
            TAG,
            "비상등 토글 차단: 공개 setter 미확인 (현재 getter=${readHazardState()})"
        )
    }

    fun diagnosticStatus(): String =
        "getter=${readHazardState()} setter=UNAVAILABLE commands=BLOCKED"

    private fun blockRequest(enable: Boolean) {
        DolphinLogger.w(
            TAG,
            "비상등 ${if (enable) "ON" else "OFF"} 요청 차단: " +
                "공개 setter 미확인 (현재 getter=${readHazardState()})"
        )
    }

    private fun readHazardState(): Boolean? = try {
        val clazz = Class.forName(LIGHT_CLASS)
        val instance = clazz.getMethod("getInstance", Context::class.java)
            .invoke(null, BydPermissionContext.wrap(appContext))
        when ((clazz.getMethod("getDoubleFlashLightState").invoke(instance) as? Number)?.toInt()) {
            1 -> true
            2 -> false
            else -> null
        }
    } catch (e: Exception) {
        DolphinLogger.w(TAG, "getDoubleFlashLightState 실패: ${(e.cause ?: e).message}")
        null
    }

    fun cleanup() = Unit

    companion object {
        private const val TAG = "HAZARD"
        private const val LIGHT_CLASS =
            "android.hardware.bydauto.light.BYDAutoLightDevice"
    }
}
