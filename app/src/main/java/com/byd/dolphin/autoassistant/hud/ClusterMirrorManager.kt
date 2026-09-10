package com.byd.dolphin.autoassistant.hud

import android.content.Context
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * Compact TBT bridge backed by methods present in the supplied DiLink 3
 * framework.jar. Full-screen mirroring is intentionally not implemented.
 */
object ClusterMirrorManager {
    private const val TAG = "CLUSTER_TBT"
    private const val INSTRUMENT_CLASS =
        "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
    private const val NAVI_OPEN_WITH_DESTINATION = 2
    private const val NAVI_CLOSE = 4
    private const val SDK_SUCCESS = 0
    private const val MAX_GUIDANCE_DISTANCE_METERS = 16_777_214

    fun sendTbtToCluster(
        context: Context,
        turnType: Int,
        turnDistanceMeters: Int,
        distanceStr: String,
        speedLimit: Int,
        nextRoadName: String,
        isNavigating: Boolean = true
    ): Boolean {
        if (!SettingsManager.isClusterTbtEnabled(context)) {
            DolphinLogger.i(TAG, "계기판 TBT 설정이 꺼져 있어 전송하지 않음")
            return false
        }
        if (!isNavigating) return clearClusterTbt(context)

        return runCatching {
            val instrument = instrument(context)
            val safeDistance = turnDistanceMeters.coerceIn(0, MAX_GUIDANCE_DISTANCE_METERS)
            val safeRoadName = nextRoadName.trim().take(100)
            val statusResult = invokeInt(instrument, "sendAutoNaviStatus", NAVI_OPEN_WITH_DESTINATION)
            val guidanceResult = invokeInt(
                instrument,
                "sendSimpleGuidanceInfo",
                turnType,
                safeDistance
            )
            val roadResult = if (safeRoadName.isNotEmpty()) {
                invokeString(instrument, "sendNextPathName", safeRoadName)
            } else {
                SDK_SUCCESS
            }
            val success = statusResult == SDK_SUCCESS &&
                guidanceResult == SDK_SUCCESS && roadResult == SDK_SUCCESS
            DolphinLogger.i(
                TAG,
                "계기판 API 결과 success=$success status=$statusResult guidance=$guidanceResult " +
                    "road=$roadResult turn=$turnType distance=$safeDistance " +
                    "parsedDistance=$distanceStr speedLimit=$speedLimit"
            )
            success
        }.getOrElse { error ->
            val cause = error.cause ?: error
            DolphinLogger.e(
                TAG,
                "계기판 TBT API 호출 실패(권한 android.permission.BYDAUTO_INSTRUMENT_SET 확인): " +
                    "${cause.javaClass.simpleName}: ${cause.message}",
                cause
            )
            false
        }
    }

    fun clearClusterTbt(context: Context): Boolean {
        if (!SettingsManager.isClusterTbtEnabled(context)) return false
        return runCatching {
            val result = invokeInt(instrument(context), "sendAutoNaviStatus", NAVI_CLOSE)
            val success = result == SDK_SUCCESS
            DolphinLogger.i(TAG, "계기판 TBT 종료 결과 success=$success code=$result")
            success
        }.getOrElse { error ->
            val cause = error.cause ?: error
            DolphinLogger.e(TAG, "계기판 TBT 종료 실패: ${cause.message}", cause)
            false
        }
    }

    private fun instrument(context: Context): Any {
        val clazz = Class.forName(INSTRUMENT_CLASS)
        return clazz.getMethod("getInstance", Context::class.java)
            .invoke(null, context.applicationContext)
            ?: error("BYDAutoInstrumentDevice.getInstance returned null")
    }

    private fun invokeInt(target: Any, method: String, vararg values: Int): Int {
        val types = Array(values.size) { Int::class.javaPrimitiveType!! }
        val result = target.javaClass.getMethod(method, *types)
            .invoke(target, *values.toTypedArray()) as? Number
        return result?.toInt() ?: Int.MIN_VALUE
    }

    private fun invokeString(target: Any, method: String, value: String): Int {
        val result = target.javaClass.getMethod(method, String::class.java)
            .invoke(target, value) as? Number
        return result?.toInt() ?: Int.MIN_VALUE
    }
}
