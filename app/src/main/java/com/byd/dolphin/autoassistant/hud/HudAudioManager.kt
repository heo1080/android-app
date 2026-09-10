package com.byd.dolphin.autoassistant.hud

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger

/** TMAP Plus HUD audio commands are locked until model-specific packet capture. */
object HudAudioManager {
    private const val TAG = "HudAudioManager"

    fun playSound(context: Context, soundType: Int, repeatCount: Int = 1): Boolean {
        DolphinLogger.w(
            TAG,
            "티맵 Plus HUD 오디오 송신 차단: 프로토콜 캡처 필요 type=$soundType " +
                "repeat=$repeatCount package=${context.packageName}"
        )
        return false
    }

    fun playOverspeedAlert(context: Context): Boolean =
        playSound(context, HudSemanticValues.SOUND_OVERSPEED_BEEP, repeatCount = 2)

    fun playCameraWarning(context: Context): Boolean =
        playSound(context, HudSemanticValues.SOUND_CAMERA_WARNING)

    fun playTurnChime(context: Context): Boolean =
        playSound(context, HudSemanticValues.SOUND_TURN_CHIME)

    fun playTestBeep(context: Context): Boolean =
        playSound(context, HudSemanticValues.SOUND_TEST_BEEP)
}
