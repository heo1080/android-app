package com.byd.dolphin.autoassistant.next.settings

import android.content.Context

enum class AlertMode { OFF, BEEP, TTS }
enum class PhraseMode { DEFAULT, RECOMMENDED, CUSTOM }

data class AlertProfile(
    val mode: AlertMode,
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

data class AlertSpec(
    val key: String,
    val title: String,
    val defaultMode: AlertMode,
    val defaultText: String,
    val recommendedText: String,
    val customHint: String
)

object NextSettings {
    const val EVENT_GEAR = "gear"
    const val EVENT_REGEN = "regen"
    const val EVENT_DRIVE = "drive"
    const val EVENT_SNOW = "snow"
    const val EVENT_AUTOHOLD_SWITCH = "autohold_switch"
    const val EVENT_AUTOHOLD_HOLD = "autohold_hold"
    const val EVENT_EPB = "epb"
    const val EVENT_ICC = "icc"
    const val EVENT_BSD = "bsd"
    const val EVENT_LEADING = "leading"

    val alertSpecs = listOf(
        AlertSpec(EVENT_GEAR, "기어 P / R / N / D", AlertMode.TTS, "{state}", "기어 {state}", "예: 기어 {state}"),
        AlertSpec(EVENT_REGEN, "회생제동", AlertMode.TTS, "{state}", "회생제동 {state}", "예: 회생제동 {state}"),
        AlertSpec(EVENT_DRIVE, "주행모드", AlertMode.TTS, "{state}", "{state} 모드입니다", "예: 주행모드는 {state}입니다"),
        AlertSpec(EVENT_SNOW, "스노우모드", AlertMode.TTS, "스노우모드", "스노우모드를 시작합니다", "예: 스노우모드를 시작합니다"),
        AlertSpec(EVENT_AUTOHOLD_SWITCH, "오토홀드 ON / OFF", AlertMode.TTS, "오토홀드 {state}", "오토홀드 {state}", "예: 오토홀드 {state}"),
        AlertSpec(EVENT_AUTOHOLD_HOLD, "오토홀드 체결 / 해제", AlertMode.TTS, "오토홀드 {state}", "오토홀드가 {state}되었습니다", "예: 오토홀드 {state}"),
        AlertSpec(EVENT_EPB, "사이드브레이크", AlertMode.TTS, "사이드브레이크 {state}", "주차 브레이크가 {state}되었습니다", "예: 사이드브레이크 {state}"),
        AlertSpec(EVENT_ICC, "ICC", AlertMode.TTS, "자율주행 {state}", "ICC {state}", "예: 자율주행 {state}"),
        AlertSpec(EVENT_BSD, "BSD 경고", AlertMode.BEEP, "{state} 사각지대 경고", "{state} 사각지대에 차량이 있습니다", "예: {state} 사각지대에 차량이 있습니다"),
        AlertSpec(EVENT_LEADING, "전방차량출발", AlertMode.OFF, "전방 차량 출발", "전방 차량이 출발했습니다. 안전을 확인하세요", "예: 전방 차량이 출발했습니다")
    )

    val beepPresets = listOf(
        BeepPreset("SOFT", "부드러운 단음", listOf(880.0), 130, 0),
        BeepPreset("DOUBLE", "기본 더블", listOf(1080.0, 1080.0), 120, 85),
        BeepPreset("HIGH_DOUBLE", "높은 더블", listOf(1420.0, 1420.0), 105, 75),
        BeepPreset("TRIPLE", "빠른 3회", listOf(1260.0, 1260.0, 1260.0), 85, 65),
        BeepPreset("UP", "상승 2톤", listOf(900.0, 1320.0), 120, 60),
        BeepPreset("DOWN", "하강 2톤", listOf(1320.0, 900.0), 120, 60)
    )

    val voicePresets = listOf(
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

    private const val PREF = "dolphin_next_settings_v2"
    private const val KEY_GLOBAL_VOICE = "global_voice"

    fun getGlobalVoice(context: Context): String =
        prefs(context).getString(KEY_GLOBAL_VOICE, "M2") ?: "M2"

    fun setGlobalVoice(context: Context, id: String) {
        prefs(context).edit().putString(KEY_GLOBAL_VOICE, id).apply()
    }

    fun getAlertProfile(context: Context, key: String): AlertProfile {
        val spec = alertSpecs.firstOrNull { it.key == key }
        val defaultMode = spec?.defaultMode ?: AlertMode.OFF
        val p = prefs(context)
        val mode = runCatching {
            AlertMode.valueOf(p.getString("$key.mode", defaultMode.name) ?: defaultMode.name)
        }.getOrDefault(defaultMode)
        val phraseMode = runCatching {
            PhraseMode.valueOf(p.getString("$key.phrase", PhraseMode.DEFAULT.name) ?: PhraseMode.DEFAULT.name)
        }.getOrDefault(PhraseMode.DEFAULT)
        return AlertProfile(
            mode = mode,
            beepId = p.getString("$key.beep", "DOUBLE") ?: "DOUBLE",
            phraseMode = phraseMode,
            customText = p.getString("$key.custom", "") ?: "",
            voiceId = p.getString("$key.voice", "GLOBAL") ?: "GLOBAL"
        )
    }

    fun saveAlertProfile(context: Context, key: String, profile: AlertProfile) {
        prefs(context).edit()
            .putString("$key.mode", profile.mode.name)
            .putString("$key.beep", profile.beepId)
            .putString("$key.phrase", profile.phraseMode.name)
            .putString("$key.custom", profile.customText.take(180))
            .putString("$key.voice", profile.voiceId)
            .apply()
    }

    fun getBeep(id: String): BeepPreset =
        beepPresets.firstOrNull { it.id == id } ?: beepPresets[1]

    fun getVoice(context: Context, profile: AlertProfile): VoicePreset {
        val id = if (profile.voiceId == "GLOBAL") getGlobalVoice(context) else profile.voiceId
        return voicePresets.firstOrNull { it.id == id } ?: voicePresets[6]
    }

    fun resolveText(spec: AlertSpec, profile: AlertProfile, state: String): String {
        val template = when (profile.phraseMode) {
            PhraseMode.RECOMMENDED -> spec.recommendedText
            PhraseMode.CUSTOM -> profile.customText.ifBlank { spec.defaultText }
            PhraseMode.DEFAULT -> spec.defaultText
        }
        return template.replace("{state}", state)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
