package com.byd.dolphin.autoassistant.hud

import android.content.Context
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * [hudaudio] HUD 오디오 및 경보음 전담 제어 매니저
 * HUD 자체 스피커 비프음, 과속 경고음, 단속카메라 접근음, TBT 회전 차임벨을 독립적으로 제어합니다.
 */
object HudAudioManager {

    private const val TAG = "HudAudioManager"

    fun playSound(context: Context, soundType: Int, repeatCount: Int = 1) {
        if (!SettingsManager.isHudBridgeEnabled(context)) return
        if (!SettingsManager.isHudAudioEnabled(context)) {
            DolphinLogger.d(TAG, "[hudaudio] Audio is disabled in settings")
            return
        }

        if (!T900BluetoothManager.isConnected) {
            DolphinLogger.w(TAG, "[hudaudio] HUD is not connected via Bluetooth")
            return
        }

        val volume = SettingsManager.getHudAudioVolume(context)
        val packet = T900Protocol.buildAudioFrame(
            soundType = soundType,
            volume = volume,
            repeatCount = repeatCount
        )

        T900BluetoothManager.sendPacket(packet)
        DolphinLogger.i(TAG, "[hudaudio] Sent sound command: type=$soundType, vol=$volume, repeat=$repeatCount")
    }

    /**
     * 과속 경고 비프음 (2회 반복)
     */
    fun playOverspeedAlert(context: Context) {
        playSound(context, T900Protocol.SOUND_OVERSPEED_BEEP, repeatCount = 2)
    }

    /**
     * 단속 카메라 접근 경고음 (1회)
     */
    fun playCameraWarning(context: Context) {
        playSound(context, T900Protocol.SOUND_CAMERA_WARNING, repeatCount = 1)
    }

    /**
     * TBT 회전 지점 접근 차임벨 (1회)
     */
    fun playTurnChime(context: Context) {
        playSound(context, T900Protocol.SOUND_TURN_CHIME, repeatCount = 1)
    }

    /**
     * 동작 확인 테스트 비프음
     */
    fun playTestBeep(context: Context) {
        playSound(context, T900Protocol.SOUND_TEST_BEEP, repeatCount = 1)
    }
}
