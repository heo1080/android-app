package com.byd.dolphin.autoassistant.manager

import android.content.Context

/**
 * Unified alert-output profile used by every driving/vehicle announcement.
 *
 * Each event can independently choose OFF / BEEP / TTS.  TTS can use the
 * built-in phrase, a recommended phrase, or a user custom template.  Custom
 * templates may contain {state}, which is replaced with the live state string.
 */
object AlertOutputProfileManager {
    const val MODE_OFF = "OFF"
    const val MODE_BEEP = "BEEP"
    const val MODE_TTS = "TTS"

    const val PHRASE_DEFAULT = "DEFAULT"
    const val PHRASE_RECOMMENDED = "RECOMMENDED"
    const val PHRASE_CUSTOM = "CUSTOM"

    const val VOICE_GLOBAL = "GLOBAL"

    const val EVENT_GEAR = "gear"
    const val EVENT_REGEN = "regen"
    const val EVENT_DRIVE = "drive"
    const val EVENT_SNOW = "snow"
    const val EVENT_AUTOHOLD_SWITCH = "autohold_switch"
    const val EVENT_AUTOHOLD_BRAKE = "autohold_brake"
    const val EVENT_EPB = "epb"
    const val EVENT_ICC = "icc"
    const val EVENT_BSD = "bsd"
    const val EVENT_LEADING = "leading"
    const val EVENT_CHARGING = "charging"

    data class EventSpec(
        val key: String,
        val title: String,
        val defaultMode: String,
        val customHint: String
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

    data class Profile(
        val mode: String,
        val beepId: String,
        val phraseMode: String,
        val customText: String,
        val voiceId: String
    )

    val events = listOf(
        EventSpec(EVENT_GEAR, "기어 P / R / N / D", MODE_TTS, "예: 기어 {state}"),
        EventSpec(EVENT_REGEN, "회생제동", MODE_TTS, "예: 회생제동 {state}"),
        EventSpec(EVENT_DRIVE, "주행모드", MODE_TTS, "예: 주행모드 {state}"),
        EventSpec(EVENT_SNOW, "스노우모드", MODE_TTS, "예: {state}"),
        EventSpec(EVENT_AUTOHOLD_SWITCH, "오토홀드 ON / OFF", MODE_TTS, "예: 오토홀드 {state}"),
        EventSpec(EVENT_AUTOHOLD_BRAKE, "오토홀드 체결 / 해제", MODE_TTS, "예: 오토홀드 {state}"),
        EventSpec(EVENT_EPB, "사이드브레이크", MODE_TTS, "예: 사이드브레이크 {state}"),
        EventSpec(EVENT_ICC, "ICC", MODE_TTS, "예: 자율주행 {state}"),
        EventSpec(EVENT_BSD, "BSD 경고", MODE_BEEP, "예: {state} 사각지대에 차량이 있습니다"),
        EventSpec(EVENT_LEADING, "전방차량출발", MODE_TTS, "예: 전방 차량이 출발했습니다"),
        EventSpec(EVENT_CHARGING, "충전 시작 / 종료", MODE_TTS, "예: 충전 {state}")
    )

    val beepPresets = listOf(
        BeepPreset("SOFT", "부드러운 단음", listOf(880.0), 130, 0),
        BeepPreset("DOUBLE", "기본 더블 비프", listOf(1080.0, 1080.0), 120, 85),
        BeepPreset("HIGH_DOUBLE", "높은 더블 경고", listOf(1420.0, 1420.0), 105, 75),
        BeepPreset("TRIPLE", "빠른 3회 경고", listOf(1260.0, 1260.0, 1260.0), 85, 65),
        BeepPreset("UP", "상승 2톤", listOf(900.0, 1320.0), 120, 60),
        BeepPreset("DOWN", "하강 2톤", listOf(1320.0, 900.0), 120, 60)
    )

    /**
     * Supertonic-3 voice.bin contains ten speakers in F1..F5, M1..M5 sid order.
     * Tone words below are user-facing audition labels, not official emotion tags.
     */
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

    private const val PREF = "dolphin_alert_output_profiles_v1"
    private const val KEY_GLOBAL_VOICE = "global_voice"

    fun getEvent(key: String): EventSpec? = events.firstOrNull { it.key == key }
    fun getBeep(id: String): BeepPreset = beepPresets.firstOrNull { it.id == id } ?: beepPresets[1]
    fun getVoice(id: String): VoicePreset {
        val actual = if (id == VOICE_GLOBAL) "M2" else id
        return voicePresets.firstOrNull { it.id == actual } ?: voicePresets.first { it.id == "M2" }
    }

    fun getGlobalVoiceId(context: Context): String =
        prefs(context).getString(KEY_GLOBAL_VOICE, "M2") ?: "M2"

    fun setGlobalVoiceId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_GLOBAL_VOICE, id).apply()
    }

    fun getProfile(context: Context, eventKey: String): Profile {
        val spec = getEvent(eventKey) ?: EventSpec(eventKey, eventKey, MODE_TTS, "")
        val p = prefs(context)
        return Profile(
            mode = p.getString("$eventKey.mode", spec.defaultMode) ?: spec.defaultMode,
            beepId = p.getString("$eventKey.beep", "DOUBLE") ?: "DOUBLE",
            phraseMode = p.getString("$eventKey.phrase", PHRASE_DEFAULT) ?: PHRASE_DEFAULT,
            customText = p.getString("$eventKey.custom", "") ?: "",
            voiceId = p.getString("$eventKey.voice", VOICE_GLOBAL) ?: VOICE_GLOBAL
        )
    }

    fun saveProfile(context: Context, eventKey: String, profile: Profile) {
        prefs(context).edit()
            .putString("$eventKey.mode", profile.mode)
            .putString("$eventKey.beep", profile.beepId)
            .putString("$eventKey.phrase", profile.phraseMode)
            .putString("$eventKey.custom", profile.customText.take(180))
            .putString("$eventKey.voice", profile.voiceId)
            .apply()
    }

    fun resolveVoice(context: Context, profile: Profile): VoicePreset {
        val id = if (profile.voiceId == VOICE_GLOBAL) getGlobalVoiceId(context) else profile.voiceId
        return getVoice(id)
    }

    fun resolvePhrase(
        profile: Profile,
        state: String,
        defaultText: String,
        recommendedText: String
    ): String {
        val chosen = when (profile.phraseMode) {
            PHRASE_RECOMMENDED -> recommendedText.ifBlank { defaultText }
            PHRASE_CUSTOM -> profile.customText.ifBlank { defaultText }
            else -> defaultText
        }
        return chosen.replace("{state}", state)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
