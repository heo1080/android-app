package com.byd.dolphin.autoassistant.hud

/**
 * Shared turn/sound semantic values for the BYD cluster and the experimental
 * T900 16-byte HUD bridge. Turn values below are verified BYD instrument
 * `sendSimpleGuidanceInfo` codes.
 */
object HudSemanticValues {
    const val TURN_BLANK = 0
    const val TURN_FRONT = 1
    const val TURN_RIGHT_FRONT = 2
    const val TURN_RIGHT = 3
    const val TURN_RIGHT_BACK = 4
    const val TURN_UTURN = 5
    const val TURN_LEFT_BACK = 6
    const val TURN_LEFT = 7
    const val TURN_LEFT_FRONT = 8
    const val TURN_STRAIGHT = 77

    // T900 sound command identifiers used by HudAudioManager.
    const val SOUND_MUTE = 0
    const val SOUND_OVERSPEED_BEEP = 1
    const val SOUND_CAMERA_WARNING = 2
    const val SOUND_TURN_CHIME = 3
    const val SOUND_TEST_BEEP = 4
}
