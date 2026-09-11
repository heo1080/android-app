package com.byd.dolphin.autoassistant.hud

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * T900 bridge packet builder.
 *
 * The project specification records a fixed 16-byte frame: AA 55, command,
 * payload, zero padding and XOR(0..14) checksum. The transport logs every send
 * so a firmware mismatch can be identified from the next diagnostic export.
 */
object HudDataManager {
    private const val TAG = "HUD_T900"
    private const val CMD_NAV = 0x01
    private const val CMD_AUDIO = 0x02
    private const val CMD_BRIGHTNESS = 0x03

    fun sendNavigationData(
        context: Context,
        currentSpeed: Int,
        speedLimit: Int,
        cameraDistance: Int,
        turnType: Int,
        turnDistance: Int
    ): Boolean {
        val packet = frame(CMD_NAV).apply {
            this[3] = currentSpeed.coerceIn(0, 255).toByte()
            this[4] = speedLimit.coerceIn(0, 255).toByte()
            putU16(this, 5, cameraDistance)
            this[7] = normalizeTurn(turnType).toByte()
            putU16(this, 8, turnDistance)
            finish(this)
        }
        val ok = TmapPlusHudBluetoothManager.sendPacket(context, packet)
        DolphinLogger.i(TAG, "NAV send=$ok speed=$currentSpeed limit=$speedLimit camera=$cameraDistance turn=$turnType dist=$turnDistance")
        return ok
    }

    fun applyBrightness(
        context: Context,
        isAuto: Boolean,
        manualLevel: Int,
        minLevel: Int = 2,
        maxLevel: Int = 15
    ): Boolean {
        val min = minLevel.coerceIn(0, 15)
        val max = maxLevel.coerceIn(min, 15)
        val packet = frame(CMD_BRIGHTNESS).apply {
            this[3] = if (isAuto) 1 else 0
            this[4] = manualLevel.coerceIn(0, 15).toByte()
            this[5] = min.toByte()
            this[6] = max.toByte()
            finish(this)
        }
        val ok = TmapPlusHudBluetoothManager.sendPacket(context, packet)
        DolphinLogger.i(TAG, "BRIGHTNESS send=$ok auto=$isAuto manual=$manualLevel min=$min max=$max")
        return ok
    }

    fun sendAudioCommand(context: Context, soundType: Int, volume: Int, repeatCount: Int): Boolean {
        val packet = frame(CMD_AUDIO).apply {
            this[3] = soundType.coerceIn(0, 255).toByte()
            this[4] = volume.coerceIn(0, 15).toByte()
            this[5] = repeatCount.coerceIn(1, 10).toByte()
            finish(this)
        }
        val ok = TmapPlusHudBluetoothManager.sendPacket(context, packet)
        DolphinLogger.i(TAG, "AUDIO send=$ok type=$soundType volume=$volume repeat=$repeatCount")
        return ok
    }

    fun sendTestData(context: Context): Boolean = sendNavigationData(
        context = context,
        currentSpeed = 50,
        speedLimit = 60,
        cameraDistance = 350,
        turnType = HudSemanticValues.TURN_LEFT,
        turnDistance = 300
    )

    private fun frame(command: Int): ByteArray = ByteArray(16).also {
        it[0] = 0xAA.toByte()
        it[1] = 0x55.toByte()
        it[2] = command.toByte()
    }

    private fun putU16(packet: ByteArray, offset: Int, value: Int) {
        val safe = value.coerceIn(0, 65535)
        packet[offset] = ((safe ushr 8) and 0xFF).toByte()
        packet[offset + 1] = (safe and 0xFF).toByte()
    }

    private fun finish(packet: ByteArray) {
        var checksum = 0
        for (i in 0..14) checksum = checksum xor (packet[i].toInt() and 0xFF)
        packet[15] = checksum.toByte()
    }

    private fun normalizeTurn(turn: Int): Int = when (turn) {
        HudSemanticValues.TURN_STRAIGHT -> HudSemanticValues.TURN_FRONT
        in 0..10 -> turn
        else -> HudSemanticValues.TURN_FRONT
    }
}
