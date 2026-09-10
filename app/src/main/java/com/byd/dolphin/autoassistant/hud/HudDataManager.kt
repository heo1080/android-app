package com.byd.dolphin.autoassistant.hud

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger

/** TMAP Plus HUD visual commands are locked until model-specific packet capture. */
object HudDataManager {
    private const val TAG = "HudDataManager"

    fun sendNavigationData(
        context: Context,
        currentSpeed: Int,
        speedLimit: Int,
        cameraDistance: Int,
        turnType: Int,
        turnDistance: Int
    ): Boolean {
        DolphinLogger.w(
            TAG,
            "티맵 Plus HUD 시각 데이터 송신 차단: 프로토콜 캡처 필요 " +
                "speed=$currentSpeed limit=$speedLimit camera=$cameraDistance " +
                "turn=$turnType distance=$turnDistance package=${context.packageName}"
        )
        return false
    }

    fun applyBrightness(
        context: Context,
        isAuto: Boolean,
        manualLevel: Int,
        minLevel: Int = 2,
        maxLevel: Int = 15
    ): Boolean {
        DolphinLogger.w(
            TAG,
            "티맵 Plus HUD 밝기 명령 차단: 프로토콜 캡처 필요 auto=$isAuto manual=$manualLevel " +
                "min=$minLevel max=$maxLevel package=${context.packageName}"
        )
        return false
    }

    fun sendTestData(context: Context): Boolean = sendNavigationData(
        context = context,
        currentSpeed = 50,
        speedLimit = 60,
        cameraDistance = 350,
        turnType = HudSemanticValues.TURN_LEFT,
        turnDistance = 300
    )
}
