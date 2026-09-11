package com.byd.dolphin.autoassistant.hud

import android.content.Context
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

object HudAudioManager {
    private const val TAG = "HUD_AUDIO"

    fun playSound(context: Context, soundType: Int, repeatCount: Int = 1): Boolean {
        val volume = SettingsManager.getHudAudioVolume(context).coerceIn(0, 15)
        val ok = HudDataManager.sendAudioCommand(context, soundType, volume, repeatCount)
        DolphinLogger.i(TAG, "HUD sound send=$ok type=$soundType volume=$volume repeat=$repeatCount")
        return ok
    }

    fun playOverspeedAlert(context: Context): Boolean =
        playSound(context, HudSemanticValues.SOUND_OVERSPEED_BEEP, repeatCount = 2)
    fun playCameraWarning(context: Context): Boolean = playSound(context, HudSemanticValues.SOUND_CAMERA_WARNING)
    fun playTurnChime(context: Context): Boolean = playSound(context, HudSemanticValues.SOUND_TURN_CHIME)
    fun playTestBeep(context: Context): Boolean = playSound(context, HudSemanticValues.SOUND_TEST_BEEP)
}
