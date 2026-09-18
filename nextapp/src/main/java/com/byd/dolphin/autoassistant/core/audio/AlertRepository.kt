package com.byd.dolphin.autoassistant.core.audio

import android.content.Context

class AlertRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("dolphin_next_alerts_v1", Context.MODE_PRIVATE)

    fun getProfile(id: String): AlertProfile {
        val spec = AlertCatalog.specs.firstOrNull { it.id == id }
            ?: error("Unknown alert id: " + id)
        return AlertProfile(
            mode = runCatching {
                OutputMode.valueOf(prefs.getString(id + ".mode", spec.defaultMode.name)!!)
            }.getOrDefault(spec.defaultMode),
            beepId = prefs.getString(id + ".beep", "DOUBLE") ?: "DOUBLE",
            phraseMode = runCatching {
                PhraseMode.valueOf(prefs.getString(id + ".phrase", PhraseMode.DEFAULT.name)!!)
            }.getOrDefault(PhraseMode.DEFAULT),
            customText = prefs.getString(id + ".custom", "") ?: "",
            voiceId = prefs.getString(id + ".voice", "GLOBAL") ?: "GLOBAL"
        )
    }

    fun saveProfile(id: String, profile: AlertProfile) {
        prefs.edit()
            .putString(id + ".mode", profile.mode.name)
            .putString(id + ".beep", profile.beepId)
            .putString(id + ".phrase", profile.phraseMode.name)
            .putString(id + ".custom", profile.customText.take(180))
            .putString(id + ".voice", profile.voiceId)
            .apply()
    }

    fun globalVoice(): String = prefs.getString("global.voice", "M2") ?: "M2"

    fun setGlobalVoice(id: String) {
        prefs.edit().putString("global.voice", id).apply()
    }

    fun voice(profile: AlertProfile): VoicePreset {
        val id = if (profile.voiceId == "GLOBAL") globalVoice() else profile.voiceId
        return AlertCatalog.voices.firstOrNull { it.id == id }
            ?: AlertCatalog.voices.first { it.id == "M2" }
    }

    fun beep(profile: AlertProfile): BeepPreset =
        AlertCatalog.beeps.firstOrNull { it.id == profile.beepId }
            ?: AlertCatalog.beeps.first { it.id == "DOUBLE" }

    fun phrase(spec: AlertSpec, profile: AlertProfile, state: String): String {
        val raw = when (profile.phraseMode) {
            PhraseMode.DEFAULT -> spec.defaultText
            PhraseMode.RECOMMENDED -> spec.recommendedText
            PhraseMode.CUSTOM -> profile.customText.ifBlank { spec.defaultText }
        }
        return raw.replace("{state}", state)
    }
}
