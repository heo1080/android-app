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
        AlertSpec(EVENT_GEAR, "기어 P / R / N / D", AlertMode.TTS, "{state}", "{state}", "예: 후진합니다"),
        AlertSpec(EVENT_REGEN, "회생제동", AlertMode.TTS, "{state}", "{state}", "예: 회생제동 하이로 변경되었습니다"),
        AlertSpec(EVENT_DRIVE, "주행모드", AlertMode.TTS, "{state}", "{state}", "예: 스포츠 모드로 변경되었습니다"),
        AlertSpec(EVENT_SNOW, "스노우모드", AlertMode.TTS, "{state}", "{state}", "예: 스노우 모드가 켜졌습니다"),
        AlertSpec(EVENT_AUTOHOLD_SWITCH, "오토홀드 ON / OFF", AlertMode.TTS, "{state}", "{state}", "예: 오토홀드가 켜졌습니다"),
        AlertSpec(EVENT_AUTOHOLD_HOLD, "오토홀드 체결 / 해제", AlertMode.TTS, "{state}", "{state}", "예: 오토홀드가 체결되었습니다"),
        AlertSpec(EVENT_EPB, "사이드브레이크", AlertMode.TTS, "{state}", "{state}", "예: 사이드 브레이크가 켜졌습니다"),
        AlertSpec(EVENT_ICC, "ICC", AlertMode.TTS, "{state}", "{state}", "예: 자율주행 모드가 켜졌습니다"),
        AlertSpec(EVENT_BSD, "BSD 경고", AlertMode.BEEP, "{state}", "{state}", "예: 왼쪽 차량을 주의하세요"),
        AlertSpec(EVENT_LEADING, "전방차량출발", AlertMode.TTS, "{state}", "{state}", "예: 앞차가 출발했습니다")
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
        val id = getGlobalVoice(context)
        return voicePresets.firstOrNull { it.id == id } ?: voicePresets[6]
    }

    fun resolveText(spec: AlertSpec, profile: AlertProfile, state: String): String {
        if (profile.phraseMode == PhraseMode.CUSTOM && profile.customText.isNotBlank()) {
            return profile.customText.replace("{state}", state)
        }
        return when (profile.phraseMode) {
            PhraseMode.DEFAULT -> defaultPhrase(spec.key, state)
            PhraseMode.RECOMMENDED -> recommendedPhrase(spec.key, state)
            PhraseMode.CUSTOM -> defaultPhrase(spec.key, state)
        }
    }

    private fun defaultPhrase(key: String, state: String): String = when (key) {
        EVENT_GEAR -> when (state.uppercase()) {
            "P" -> "파킹"
            "R" -> "후진"
            "N" -> "중립"
            "D" -> "전진"
            else -> state
        }
        EVENT_REGEN -> when {
            state.contains("스탠", true) || state.equals("STANDARD", true) -> "회생제동 스탠다드"
            state.contains("하이", true) || state.equals("HIGH", true) -> "회생제동 하이"
            else -> "회생제동 " + state
        }
        EVENT_DRIVE -> when {
            state.contains("ECO", true) || state.contains("에코") -> "에코모드"
            state.contains("NORMAL", true) || state.contains("노멀") -> "노멀모드"
            state.contains("SPORT", true) || state.contains("스포츠") -> "스포츠모드"
            else -> state
        }
        EVENT_SNOW -> "스노우모드" + normalizeOnOff(state)
        EVENT_AUTOHOLD_SWITCH -> "오토홀드 " + normalizeOnOff(state)
        EVENT_AUTOHOLD_HOLD -> if (state.contains("해제")) "오토홀드 해제" else "오토홀드 체결"
        EVENT_EPB -> "사이드 브레이크 " + normalizeOnOff(state)
        EVENT_ICC -> if (isOn(state)) "자율주행모드" else "자율주행해제"
        EVENT_BSD -> if (state.contains("오른")) "오른쪽 조심" else "왼쪽 조심"
        EVENT_LEADING -> "앞차 출발"
        else -> state
    }

    private fun recommendedPhrase(key: String, state: String): String = when (key) {
        EVENT_GEAR -> when (state.uppercase()) {
            "P" -> "파킹으로 전환했습니다"
            "R" -> "후진 기어입니다"
            "N" -> "중립 기어입니다"
            "D" -> "전진 기어입니다"
            else -> defaultPhrase(key, state)
        }
        EVENT_REGEN -> if (state.contains("하이", true) || state.equals("HIGH", true))
            "회생제동이 하이로 변경되었습니다"
        else "회생제동이 스탠다드로 변경되었습니다"
        EVENT_DRIVE -> when {
            state.contains("ECO", true) || state.contains("에코") -> "에코 모드로 변경되었습니다"
            state.contains("NORMAL", true) || state.contains("노멀") -> "노멀 모드로 변경되었습니다"
            state.contains("SPORT", true) || state.contains("스포츠") -> "스포츠 모드로 변경되었습니다"
            else -> defaultPhrase(key, state)
        }
        EVENT_SNOW -> if (isOn(state)) "스노우 모드가 켜졌습니다" else "스노우 모드가 꺼졌습니다"
        EVENT_AUTOHOLD_SWITCH -> if (isOn(state)) "오토홀드가 켜졌습니다" else "오토홀드가 꺼졌습니다"
        EVENT_AUTOHOLD_HOLD -> if (state.contains("해제")) "오토홀드가 해제되었습니다" else "오토홀드가 체결되었습니다"
        EVENT_EPB -> if (isOn(state)) "사이드 브레이크가 켜졌습니다" else "사이드 브레이크가 꺼졌습니다"
        EVENT_ICC -> if (isOn(state)) "자율주행 모드가 켜졌습니다" else "자율주행 모드가 해제되었습니다"
        EVENT_BSD -> if (state.contains("오른")) "오른쪽 차량을 주의하세요" else "왼쪽 차량을 주의하세요"
        EVENT_LEADING -> "앞차가 출발했습니다"
        else -> defaultPhrase(key, state)
    }

    private fun isOn(state: String): Boolean =
        state.equals("ON", true) || state.contains("켜") || state == "1" || state == "2"

    private fun normalizeOnOff(state: String): String = if (isOn(state)) "ON" else "OFF"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
