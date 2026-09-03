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

/**
 * 5번 요구사항:
 * 특정 앱 및 차량 안내 음성 운전석 전용 스피커 출력 매니저
 * 5-1. 설치된 모든 앱 선택 가능하고 여러 개 추가할 수 있게 설계
 * 5-2. BSD 사각지대 물체 감지 시 현대/기아 스타일 경고음 운전석 전용 스피커 출력 (기본/추천/수동 커스텀)
 * 5-3. LDP 차선이탈보조 작동 시 현대/기아 스타일 경고음 운전석 전용 스피커 출력 (기본/추천/수동 커스텀)
 * 5-4. 10대 차량 상태(기어, 드라이브모드, 회생제동, 스노우모드, 오토홀드, EPB, ICC, 전방출발, 오토홀드 체결/해제)
 */
class VoiceAndSoundManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isTtsReady = false
        private set
    private var toneGenerator: ToneGenerator? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val soundScope = CoroutineScope(Dispatchers.Default)
    private var ldwJob: Job? = null
    private var bsdJob: Job? = null

    // 운전석 내비게이션 가이던스 오디오 속성 (미디어 오디오와 분리되어 운전석 전용 스피커 채널로 출력)
    private val driverSpeakerAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // 오디오 덕킹 요청 객체 (말할 때 음악 볼륨 자동 감소, 끝나면 복원)
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
                .setOnAudioFocusChangeListener { /* Auto managed by Android */ }
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
                override fun onDone(utteranceId: String?) {
                    releaseAudioFocus()
                }
                override fun onError(utteranceId: String?) {
                    releaseAudioFocus()
                }
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

    // 5-2. BSD 사각지대 물체 감지 시 현대/기아 스타일 경고음 (비프 / 추천 음성 / 수동 커스텀 음성)
    fun playBlindSpotWarning() {
        if (!SettingsManager.isSafetyAlertEnabled(context)) return
        val mode = SettingsManager.getBsdAlertMode(context)
        when (mode) {
            "VOICE_RECOMMENDED", "VOICE_CUSTOM" -> {
                val msg = SettingsManager.getBsdCustomText(context)
                speak(msg)
            }
            else -> {
                // 현대/기아 스타일 4연속 긴박한 비프음
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

    // 5-3. LDP 차선이탈조향보조 작동 시 현대/기아 스타일 경고음 (비프 / 추천 음성 / 수동 커스텀 음성)
    fun playLaneDepartureWarning() {
        if (!SettingsManager.isSafetyAlertEnabled(context)) return
        val mode = SettingsManager.getLdpAlertMode(context)
        when (mode) {
            "VOICE_RECOMMENDED", "VOICE_CUSTOM" -> {
                val msg = SettingsManager.getLdpCustomText(context)
                speak(msg)
            }
            else -> {
                // 현대/기아 스타일 3연속 딩딩딩 톤
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

    // 5-4. 10대 차량 상태 음성 안내
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

    fun speakAutoHold(isActive: Boolean) {
        if (!SettingsManager.isAutoHoldVoiceEnabled(context)) return
        speak(SettingsManager.getAutoHoldPhrase(context, isActive))
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

    fun speakCharging() {
        if (!SettingsManager.isChargingVoiceEnabled(context)) return
        speak(SettingsManager.getChargingPhrase(context))
    }

    // 운전석 전용 스피커 출력 + 오디오 덕킹
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
