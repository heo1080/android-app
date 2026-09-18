package com.byd.dolphin.autoassistant.core.audio

enum class OutputMode { OFF, BEEP, TTS }
enum class PhraseMode { DEFAULT, RECOMMENDED, CUSTOM }

data class AlertSpec(
    val id: String,
    val title: String,
    val defaultMode: OutputMode,
    val defaultText: String,
    val recommendedText: String,
    val sampleState: String
)

data class AlertProfile(
    val mode: OutputMode,
    val beepId: String = "DOUBLE",
    val phraseMode: PhraseMode = PhraseMode.DEFAULT,
    val customText: String = "",
    val voiceId: String = "GLOBAL"
)

data class BeepPreset(
    val id: String,
    val label: String,
    val frequencies: List<Double>,
    val durationMs: Int,
    val gapMs: Long
)

data class VoicePreset(
    val id: String,
    val label: String,
    val sid: Int,
    val speed: Float
)

object AlertCatalog {
    const val GEAR = "gear"
    const val REGEN = "regen"
    const val DRIVE = "drive"
    const val SNOW = "snow"
    const val AUTOHOLD_SWITCH = "autohold_switch"
    const val AUTOHOLD_HOLD = "autohold_hold"
    const val EPB = "epb"
    const val ICC = "icc"
    const val BSD = "bsd"
    const val LEADING = "leading"

    val specs = listOf(
        AlertSpec(GEAR, "기어", OutputMode.TTS, "{state}", "기어 {state}", "D"),
        AlertSpec(REGEN, "회생제동", OutputMode.TTS, "{state}", "회생제동 {state}", "HIGH"),
        AlertSpec(DRIVE, "주행모드", OutputMode.TTS, "{state}", "{state} 모드입니다", "NORMAL"),
        AlertSpec(SNOW, "스노우모드", OutputMode.TTS, "스노우모드", "스노우모드를 시작합니다", "ON"),
        AlertSpec(AUTOHOLD_SWITCH, "오토홀드 ON/OFF", OutputMode.TTS, "오토홀드 {state}", "오토홀드를 {state}", "ON"),
        AlertSpec(AUTOHOLD_HOLD, "오토홀드 체결/해제", OutputMode.TTS, "오토홀드 {state}", "오토홀드가 {state}되었습니다", "체결"),
        AlertSpec(EPB, "사이드브레이크", OutputMode.TTS, "사이드브레이크 {state}", "주차 브레이크가 {state}되었습니다", "체결"),
        AlertSpec(ICC, "ICC", OutputMode.TTS, "자율주행 {state}", "ICC가 {state}", "ON"),
        AlertSpec(BSD, "BSD 경고", OutputMode.BEEP, "{state} 사각지대 경고", "{state} 사각지대에 차량이 있습니다", "왼쪽"),
        AlertSpec(LEADING, "전방차량출발", OutputMode.TTS, "전방 차량 출발", "전방 차량이 출발했습니다", "출발")
    )

    val beeps = listOf(
        BeepPreset("SOFT", "부드러운 단음", listOf(880.0), 130, 0),
        BeepPreset("DOUBLE", "기본 더블", listOf(1080.0, 1080.0), 120, 85),
        BeepPreset("HIGH_DOUBLE", "높은 더블", listOf(1420.0, 1420.0), 105, 75),
        BeepPreset("TRIPLE", "빠른 3회", listOf(1260.0, 1260.0, 1260.0), 85, 65),
        BeepPreset("UP", "상승 2톤", listOf(900.0, 1320.0), 120, 60),
        BeepPreset("DOWN", "하강 2톤", listOf(1320.0, 900.0), 120, 60)
    )

    val voices = listOf(
        VoicePreset("F1", "여성 1 · 부드러운", 0, 0.98f),
        VoicePreset("F2", "여성 2 · 차분한", 1, 0.94f),
        VoicePreset("F3", "여성 3 · 또렷한", 2, 1.04f),
        VoicePreset("F4", "여성 4 · 밝은", 3, 1.08f),
        VoicePreset("F5", "여성 5 · 단정한", 4, 1.00f),
        VoicePreset("M1", "남성 1 · 부드러운", 5, 0.97f),
        VoicePreset("M2", "남성 2 · 정직한", 6, 1.00f),
        VoicePreset("M3", "남성 3 · 차가운", 7, 0.95f),
        VoicePreset("M4", "남성 4 · 힘있는", 8, 0.92f),
        VoicePreset("M5", "남성 5 · 또렷한", 9, 1.05f)
    )
}
