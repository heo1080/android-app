package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceAndSoundManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isTtsReady = false
        private set
    private var toneGenerator: ToneGenerator? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val soundScope = CoroutineScope(Dispatchers.Default)
    private var ldwJob: Job? = null
    private var bsdJob: Job? = null

    private val driverSpeakerAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(context, this)
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            Log.e("DolphinAudio", "ToneGenerator init error", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(driverSpeakerAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* Auto managed */ }
                .build()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.KOREAN
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.05f)
            tts?.setAudioAttributes(driverSpeakerAttributes)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { releaseAudioFocus() }
                override fun onError(utteranceId: String?) { releaseAudioFocus() }
            })

            isTtsReady = true
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun playBlindSpotWarning() {
        if (!SettingsManager.isSafetyAlertEnabled(context)) return
        val mode = SettingsManager.getBsdAlertMode(context)
        when (mode) {
            "VOICE_RECOMMENDED", "VOICE_CUSTOM" -> {
                val msg = SettingsManager.getBsdCustomText(context)
                speak(msg)
            }
            else -> {
                if (bsdJob?.isActive == true) return
                bsdJob = soundScope.launch {
                    requestAudioFocus()
                    repeat(4) {
                        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 140)
                        delay(220)
                    }
                    delay(100)
                    releaseAudioFocus()
                }
            }
        }
    }

    fun playLaneDepartureWarning() {
        if (!SettingsManager.isSafetyAlertEnabled(context)) return
        val mode = SettingsManager.getLdpAlertMode(context)
        when (mode) {
            "VOICE_RECOMMENDED", "VOICE_CUSTOM" -> {
                val msg = SettingsManager.getLdpCustomText(context)
                speak(msg)
            }
            else -> {
                if (ldwJob?.isActive == true) return
                ldwJob = soundScope.launch {
                    requestAudioFocus()
                    repeat(3) {
                        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
                        delay(260)
                    }
                    delay(100)
                    releaseAudioFocus()
                }
            }
        }
    }

    fun speakGear(gear: String) {
        if (!SettingsManager.isGearVoiceEnabled(context)) return
        speak(SettingsManager.getGearPhrase(context, gear))
    }

    fun speakDriveMode(mode: String) {
        if (!SettingsManager.isDriveModeVoiceEnabled(context)) return
        speak(SettingsManager.getDriveModePhrase(context, mode))
    }

    fun speakRegenMode(regen: String) {
        if (!SettingsManager.isRegenModeVoiceEnabled(context)) return
        speak(SettingsManager.getRegenModePhrase(context, regen))
    }

    fun speakSnowMode() {
        if (!SettingsManager.isSnowModeVoiceEnabled(context)) return
        speak(SettingsManager.getSnowModePhrase(context))
    }

    // 1) 오토홀드 물리 스위치 ON/OFF
    fun speakAutoHoldSwitch(isSwitchOn: Boolean) {
        if (!SettingsManager.isAutoHoldVoiceEnabled(context)) return
        speak(SettingsManager.getAutoHoldSwitchPhrase(context, isSwitchOn))
    }

    // 2) 오토홀드 브레이크 체결 / 해제
    fun speakAutoHoldBrake(isEngaged: Boolean) {
        if (!SettingsManager.isAutoHoldVoiceEnabled(context)) return
        speak(SettingsManager.getAutoHoldBrakePhrase(context, isEngaged))
    }

    // 하위 호환
    fun speakAutoHold(isActive: Boolean) {
        speakAutoHoldBrake(isActive)
    }

    fun speakEpb(isEngaged: Boolean) {
        if (!SettingsManager.isEpbVoiceEnabled(context)) return
        speak(SettingsManager.getEpbPhrase(context, isEngaged))
    }

    fun speakIcc(isActive: Boolean) {
        if (!SettingsManager.isIccVoiceEnabled(context)) return
        speak(SettingsManager.getIccPhrase(context))
    }

    fun speakLeadingCarDeparture() {
        if (!SettingsManager.isLeadingCarVoiceEnabled(context)) return
        speak(SettingsManager.getLeadingCarPhrase(context))
    }

    fun speakChargingStart() {
        if (!SettingsManager.isChargingVoiceEnabled(context)) return
        speak(SettingsManager.getChargingStartPhrase(context))
    }

    fun speakChargingEnd() {
        if (!SettingsManager.isChargingVoiceEnabled(context)) return
        speak(SettingsManager.getChargingEndPhrase(context))
    }

    // 하위 호환
    fun speakCharging() {
        speakChargingStart()
    }

    fun speak(text: String) {
        if (isTtsReady) {
            requestAudioFocus()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "DolphinTTS_${System.currentTimeMillis()}")
        }
    }

    fun release() {
        ldwJob?.cancel()
        bsdJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        toneGenerator?.release()
        releaseAudioFocus()
    }
}
