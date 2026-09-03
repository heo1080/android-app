package com.byd.dolphin.autoassistant.hud

import android.content.Context
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * 3번 요구사항:
 * [huddata] HUD 시각/디스플레이 데이터 및 화면 밝기 전송 매니저
 * 3-1. 속도, 제한속도, 단속카메라, TBT 시각 데이터 송신
 * 3-2. HUD 화면 밝기 수동(단계별), 자동(조도 센서 기반 최소~최대 범위) 조절
 */
object HudDataManager {

    private const val TAG = "HudDataManager"

    fun sendNavigationData(
        context: Context,
        currentSpeed: Int,
        speedLimit: Int,
        cameraDistance: Int,
        turnType: Int,
        turnDistance: Int
    ) {
        if (!SettingsManager.isHudBridgeEnabled(context)) return
        if (!SettingsManager.isHudDataEnabled(context)) return
        if (!T900BluetoothManager.isConnected) return

        val packet = T900Protocol.buildNavigationFrame(
            currentSpeed = currentSpeed,
            speedLimit = speedLimit,
            cameraDistance = cameraDistance,
            turnType = turnType,
            turnDistance = turnDistance
        )

        T900BluetoothManager.sendPacket(packet)
        DolphinLogger.i(TAG, "[huddata] 전송: 속도=$currentSpeed, 제한=$speedLimit, 턴=$turnType, 거리=${turnDistance}m")
    }

    // 3-2. HUD 화면 밝기 제어: 수동 단계별 또는 센서 기반 자동 조절(최소~최대)
    fun applyBrightness(context: Context, isAuto: Boolean, manualLevel: Int, minLevel: Int = 2, maxLevel: Int = 15) {
        if (!T900BluetoothManager.isConnected) return
        val packet = T900Protocol.buildBrightnessFrame(
            isAuto = isAuto,
            manualLevel = manualLevel,
            minLevel = minLevel,
            maxLevel = maxLevel
        )
        T900BluetoothManager.sendPacket(packet)
        DolphinLogger.i(TAG, "[HUD 밝기 설정] isAuto=$isAuto, manual=$manualLevel, min=$minLevel, max=$maxLevel")
    }

    fun sendTestData(context: Context) {
        sendNavigationData(
            context = context,
            currentSpeed = 50,
            speedLimit = 60,
            cameraDistance = 350,
            turnType = T900Protocol.TURN_LEFT,
            turnDistance = 300
        )
    }
}
