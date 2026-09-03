package com.byd.dolphin.autoassistant.hud

/**
 * 3번 요구사항:
 * 한문철 HUD (TMAP T900 / T800) 전용 프로토콜
 * 3-1. huddata(시각 텔레메트리) 및 hudaudio(자체 스피커 사운드) 분리
 * 3-2. HUD 화면 밝기 수동(단계별 0~15), 자동 조절(센서 기반 최소/최대값 지정)
 * 3-3. hudaudio 자체 스피커 음량 조절(0~15단계)
 * 3-4. 5대 내비게이션(순정 TMAP, 모바일 TMAP, 네이버지도, 카카오내비, 아이나비 에어) 지원
 */
object T900Protocol {

    const val CMD_HUD_DATA = 0x01
    const val CMD_HUD_AUDIO = 0x02
    const val CMD_HUD_BRIGHTNESS = 0x03

    // TBT 회전 방향 코드
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

    // hudaudio 사운드 및 경보음 코드 (네비게이션 안내 소리 전용)
    const val SOUND_MUTE = 0x00
    const val SOUND_OVERSPEED_BEEP = 0x01
    const val SOUND_CAMERA_WARNING = 0x02
    const val SOUND_TURN_CHIME = 0x03
    const val SOUND_TEST_BEEP = 0x04

    /**
     * 3-1. [huddata] TBT 및 주행 데이터 프레임 조립
     */
    fun buildNavigationFrame(
        currentSpeed: Int,
        speedLimit: Int,
        cameraDistance: Int,
        turnType: Int,
        turnDistance: Int
    ): ByteArray {
        val frame = ByteArray(16)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = CMD_HUD_DATA.toByte()
        frame[3] = (currentSpeed and 0xFF).toByte()
        frame[4] = (speedLimit and 0xFF).toByte()

        frame[5] = ((cameraDistance shr 8) and 0xFF).toByte()
        frame[6] = (cameraDistance and 0xFF).toByte()

        frame[7] = (turnType and 0xFF).toByte()

        frame[8] = ((turnDistance shr 8) and 0xFF).toByte()
        frame[9] = (turnDistance and 0xFF).toByte()

        frame[10] = 0x00.toByte()
        frame[11] = 0x00.toByte()
        frame[12] = 0x00.toByte()
        frame[13] = 0x00.toByte()
        frame[14] = 0x00.toByte()

        var checksum = 0
        for (i in 0..14) {
            checksum = checksum xor frame[i].toInt()
        }
        frame[15] = (checksum and 0xFF).toByte()
        return frame
    }

    /**
     * 3-1 & 3-3. [hudaudio] 자체 스피커 음량 및 경보음 제어 프레임 조립
     */
    fun buildAudioFrame(
        soundType: Int,
        volume: Int = 10,
        repeatCount: Int = 1
    ): ByteArray {
        val frame = ByteArray(16)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = CMD_HUD_AUDIO.toByte()
        frame[3] = (soundType and 0xFF).toByte()
        frame[4] = (volume.coerceIn(0, 15) and 0xFF).toByte()
        frame[5] = (repeatCount.coerceIn(1, 10) and 0xFF).toByte()
        frame[6] = 0x00.toByte()
        frame[7] = 0x00.toByte()
        frame[8] = 0x00.toByte()
        frame[9] = 0x00.toByte()
        frame[10] = 0x00.toByte()
        frame[11] = 0x00.toByte()
        frame[12] = 0x00.toByte()
        frame[13] = 0x00.toByte()
        frame[14] = 0x00.toByte()

        var checksum = 0
        for (i in 0..14) {
            checksum = checksum xor frame[i].toInt()
        }
        frame[15] = (checksum and 0xFF).toByte()
        return frame
    }

    /**
     * 3-2. HUD 화면 밝기 제어 프레임 조립 (수동 단계별 / 자동 조절 센서 최소~최대 범위)
     */
    fun buildBrightnessFrame(
        isAuto: Boolean,
        manualLevel: Int,
        minLevel: Int = 2,
        maxLevel: Int = 15
    ): ByteArray {
        val frame = ByteArray(16)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = CMD_HUD_BRIGHTNESS.toByte()
        frame[3] = if (isAuto) 0x00.toByte() else 0x01.toByte()
        frame[4] = (manualLevel.coerceIn(0, 15) and 0xFF).toByte()
        frame[5] = (minLevel.coerceIn(0, 15) and 0xFF).toByte()
        frame[6] = (maxLevel.coerceIn(0, 15) and 0xFF).toByte()
        frame[7] = 0x00.toByte()
        frame[8] = 0x00.toByte()
        frame[9] = 0x00.toByte()
        frame[10] = 0x00.toByte()
        frame[11] = 0x00.toByte()
        frame[12] = 0x00.toByte()
        frame[13] = 0x00.toByte()
        frame[14] = 0x00.toByte()

        var checksum = 0
        for (i in 0..14) {
            checksum = checksum xor frame[i].toInt()
        }
        frame[15] = (checksum and 0xFF).toByte()
        return frame
    }
}
