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
    private var isTtsReady = false
    private var toneGenerator: ToneGenerator? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val soundScope = CoroutineScope(Dispatchers.Default)
    private var ldwJob: Job? = null
    private var bsdJob: Job? = null

    // 운전석 내비게이션 가이던스 오디오 속성 (미디어 오디오와 분리되어 운전석 스피커로 출력됨)
    private val navAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // 오디오 덕킹 요청 객체 (말할 때 음악 볼륨 자동 감소, 끝나면 복원)
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(context, this)
        try {
            // 알림 전용 톤 제너레이터
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            Log.e("DolphinAudio", "ToneGenerator init error", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(navAudioAttributes)
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

            // TTS 오디오 출력을 운전석 내비게이션 채널로 설정
            tts?.setAudioAttributes(navAudioAttributes)

            // 음성 재생 완료 시 오디오 포커스 해제 (미디어 음량 원복)
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

    // 1. 기어 변속 음성 안내 (전진, 후진, 중립, 파킹)
    fun speakGear(gear: String) {
        if (!SettingsManager.isGearVoiceEnabled(context)) return
        val text = when (gear.uppercase()) {
            "R" -> "후진"
            "N" -> "중립"
            "D" -> "전진"
            "P" -> "파킹"
            else -> return
        }
        speak(text)
    }

    // 2. 오토홀드 ON / OFF 음성 안내
    fun speakAutoHold(isActive: Boolean) {
        if (!SettingsManager.isAutoHoldVoiceEnabled(context)) return
        val text = if (isActive) "오토홀드가 켜졌습니다" else "오토홀드가 꺼졌습니다"
        speak(text)
    }

    // 3. 전자식 사이드 브레이크 (EPB) ON / OFF 음성 안내
    fun speakEpb(isEngaged: Boolean) {
        if (!SettingsManager.isEpbVoiceEnabled(context)) return
        val text = if (isEngaged) "사이드브레이크가 체결되었습니다." else "사이드브레이크 해제되었습니다."
        speak(text)
    }

    // 4. 자율주행 (ICC) ON / OFF 음성 안내
    fun speakIcc(isActive: Boolean) {
        if (!SettingsManager.isIccVoiceEnabled(context)) return
        val text = if (isActive) "자율주행이 켜졌습니다." else "자율주행이 꺼졌습니다."
        speak(text)
    }

    // 5. 주행 모드 음성 안내 (에코, 노멀, 스포츠)
    fun speakDriveMode(mode: String) {
        if (!SettingsManager.isDriveModeVoiceEnabled(context)) return
        val text = when (mode.uppercase()) {
            "ECO" -> "에코"
            "NORMAL", "COMFORT" -> "노멀"
            "SPORT" -> "스포츠"
            else -> mode
        }
        speak(text)
    }

    // 6. 회생제동 모드 음성 안내 (에코, 하이)
    fun speakRegenMode(regen: String) {
        if (!SettingsManager.isRegenModeVoiceEnabled(context)) return
        val text = when (regen.uppercase()) {
            "ECO", "STANDARD", "NORMAL" -> "에코"
            "HIGH", "LARGER" -> "하이"
            else -> regen
        }
        speak(text)
    }

    // 7. 차선 이탈 (LDW) 3연속 비프 (출력 시 음악 자동 덕킹)
    fun playLaneDepartureWarning() {
        if (!SettingsManager.isSafetyAlertEnabled(context)) return
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

    // 8. 후측방 (BSD) + 깜박이 4연속 긴박한 비프
    fun playBlindSpotWarning() {
        if (!SettingsManager.isSafetyAlertEnabled(context)) return
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

    // 음성 출력 (오디오 덕킹 자동 적용)
    fun speak(text: String) {
        if (isTtsReady) {
            requestAudioFocus() // 미디어 음량 자동 감소
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
