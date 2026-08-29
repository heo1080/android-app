package com.byd.dolphin.autoassistant.hud

object T900Protocol {

    const val TURN_STRAIGHT = 0x01
    const val TURN_LEFT = 0x02
    const val TURN_RIGHT = 0x03
    const val TURN_UTURN = 0x04
    const val TURN_SLIGHT_LEFT = 0x05
    const val TURN_SLIGHT_RIGHT = 0x06
    const val TURN_HIGHWAY_IN = 0x07
    const val TURN_HIGHWAY_OUT = 0x08
    const val TURN_OVERPASS = 0x09
    const val TURN_UNDERPASS = 0x0A

    // T900 HUD 시리얼 데이터 프레임 조립 (Header 0xAA, 0x55 ... Checksum)
    fun buildNavigationFrame(
        currentSpeed: Int,
        speedLimit: Int,
        cameraDistance: Int,
        turnType: Int,
        turnDistance: Int
    ): ByteArray {
        val frame = ByteArray(16)
        frame[0] = 0xAA.toByte() // Start 1
        frame[1] = 0x55.toByte() // Start 2
        frame[2] = 0x01.toByte() // Cmd: TBT Navigation Data

        frame[3] = (currentSpeed and 0xFF).toByte()
        frame[4] = (speedLimit and 0xFF).toByte()

        // 카메라 남은 거리 (2 Bytes)
        frame[5] = ((cameraDistance shr 8) and 0xFF).toByte()
        frame[6] = (cameraDistance and 0xFF).toByte()

        // 턴 아이콘 코드 (좌/우/유턴/직진 등)
        frame[7] = (turnType and 0xFF).toByte()

        // 턴 남은 거리 (2 Bytes)
        frame[8] = ((turnDistance shr 8) and 0xFF).toByte()
        frame[9] = (turnDistance and 0xFF).toByte()

        frame[10] = 0x00.toByte()
        frame[11] = 0x00.toByte()
        frame[12] = 0x00.toByte()
        frame[13] = 0x00.toByte()
        frame[14] = 0x00.toByte()

        // Checksum 계산 (XOR 방식)
        var checksum = 0
        for (i in 0..14) {
            checksum = checksum xor frame[i].toInt()
        }
        frame[15] = (checksum and 0xFF).toByte()

        return frame
    }
}
