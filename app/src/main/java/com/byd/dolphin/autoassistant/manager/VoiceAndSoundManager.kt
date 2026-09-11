package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

class VoiceAndSoundManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isTtsReady = false
        private set
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val soundScope = CoroutineScope(Dispatchers.Default)
    private var ldwJob: Job? = null
    private var bsdJob: Job? = null

    /**
     * BYD's automotive audio policy can distinguish navigation guidance from
     * ordinary media/notification streams. Both TTS and warning beeps now use
     * this usage so the OEM navigation mix (driver-side/navigation route when
     * configured by the head unit) is selected consistently.
     */
    private val navigationAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val navigationToneAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        DolphinLogger.i("AUDIO", "TTS 초기화 시작; route=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE")
        tts = TextToSpeech(context.applicationContext, this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(navigationAudioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { change -> DolphinLogger.d("AUDIO", "audioFocus=$change") }
                .build()
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            isTtsReady = false
            DolphinLogger.e("AUDIO", "TTS 초기화 실패 status=$status")
            return
        }
        val languageResult = tts?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
        tts?.setPitch(1.0f)
        tts?.setSpeechRate(1.05f)
        tts?.setAudioAttributes(navigationAudioAttributes)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                DolphinLogger.i("AUDIO", "TTS 출력 시작 id=$utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                DolphinLogger.i("AUDIO", "TTS 출력 완료 id=$utteranceId")
                releaseAudioFocus()
            }
            override fun onError(utteranceId: String?) {
                DolphinLogger.e("AUDIO", "TTS 출력 오류 id=$utteranceId")
                releaseAudioFocus()
            }
        })
        isTtsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        DolphinLogger.i(
            "AUDIO",
            "TTS 준비=${isTtsReady} languageResult=$languageResult " +
                "usage=NAVIGATION_GUIDANCE outputs=${describeOutputs()}"
        )
    }

    private fun requestAudioFocus(): Int {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
                ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        DolphinLogger.i("AUDIO", "navigation audio focus result=$result outputs=${describeOutputs()}")
        return result
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
        when (SettingsManager.getBsdAlertMode(context)) {
            "VOICE_RECOMMENDED", "VOICE_CUSTOM" -> speak(SettingsManager.getBsdCustomText(context))
            else -> {
                if (bsdJob?.isActive == true) return
                bsdJob = soundScope.launch { playNavigationBeepPattern(1320.0, 140, 4, 80) }
            }
        }
    }

    fun playLaneDepartureWarning() {
        if (!SettingsManager.isSafetyAlertEnabled(context)) return
        when (SettingsManager.getLdpAlertMode(context)) {
            "VOICE_RECOMMENDED", "VOICE_CUSTOM" -> speak(SettingsManager.getLdpCustomText(context))
            else -> {
                if (ldwJob?.isActive == true) return
                ldwJob = soundScope.launch { playNavigationBeepPattern(880.0, 160, 3, 100) }
            }
        }
    }

    /** Explicit route test used by the settings screen. */
    fun playNavigationRouteTest() {
        soundScope.launch {
            DolphinLogger.i("AUDIO", "운전석/내비게이션 믹스 테스트 시작")
            playNavigationBeepPattern(1040.0, 220, 2, 120)
        }
    }

    private suspend fun playNavigationBeepPattern(
        frequencyHz: Double,
        durationMs: Int,
        repeats: Int,
        gapMs: Long
    ) {
        requestAudioFocus()
        try {
            repeat(repeats) {
                playPcmTone(frequencyHz, durationMs)
                if (it < repeats - 1) delay(gapMs)
            }
        } finally {
            releaseAudioFocus()
        }
    }

    private fun playPcmTone(frequencyHz: Double, durationMs: Int) {
        val sampleRate = 16_000
        val sampleCount = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val samples = ShortArray(sampleCount) { index ->
            val envelope = when {
                index < sampleRate / 100 -> index.toDouble() / (sampleRate / 100.0)
                index > sampleCount - sampleRate / 100 -> (sampleCount - index).toDouble() / (sampleRate / 100.0)
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            (Short.MAX_VALUE * 0.32 * envelope * sin(2.0 * PI * index * frequencyHz / sampleRate)).toInt().toShort()
        }
        var audioTrack: AudioTrack? = null
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(navigationToneAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            audioTrack = track
            val written = track.write(samples, 0, samples.size)
            DolphinLogger.i("AUDIO", "NAV tone write=$written freq=$frequencyHz duration=$durationMs session=${track.audioSessionId}")
            track.play()
            Thread.sleep(durationMs.toLong() + 30L)
            runCatching { track.stop() }
        } catch (e: Exception) {
            DolphinLogger.e("AUDIO", "NAV tone 출력 실패", e)
        } finally {
            audioTrack?.release()
        }
    }

    private fun describeOutputs(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString(prefix = "[", postfix = "]") { "${it.id}:${it.type}:${it.productName}" }
        } else "legacy"
    }.getOrDefault("unavailable")

    fun speakGear(gear: String) { if (SettingsManager.isGearVoiceEnabled(context)) speak(SettingsManager.getGearPhrase(context, gear)) }
    fun speakDriveMode(mode: String) { if (SettingsManager.isDriveModeVoiceEnabled(context)) speak(SettingsManager.getDriveModePhrase(context, mode)) }
    fun speakRegenMode(regen: String) { if (SettingsManager.isRegenModeVoiceEnabled(context)) speak(SettingsManager.getRegenModePhrase(context, regen)) }
    fun speakSnowMode() { if (SettingsManager.isSnowModeVoiceEnabled(context)) speak(SettingsManager.getSnowModePhrase(context)) }
    fun speakAutoHoldSwitch(isSwitchOn: Boolean) { if (SettingsManager.isAutoHoldVoiceEnabled(context)) speak(SettingsManager.getAutoHoldSwitchPhrase(context, isSwitchOn)) }
    fun speakAutoHoldBrake(isEngaged: Boolean) { if (SettingsManager.isAutoHoldVoiceEnabled(context)) speak(SettingsManager.getAutoHoldBrakePhrase(context, isEngaged)) }
    fun speakAutoHold(isActive: Boolean) = speakAutoHoldBrake(isActive)
    fun speakEpb(isEngaged: Boolean) { if (SettingsManager.isEpbVoiceEnabled(context)) speak(SettingsManager.getEpbPhrase(context, isEngaged)) }
    fun speakIcc(isActive: Boolean) { if (SettingsManager.isIccVoiceEnabled(context)) speak(SettingsManager.getIccPhrase(context)) }
    fun speakLeadingCarDeparture() { if (SettingsManager.isLeadingCarVoiceEnabled(context)) speak(SettingsManager.getLeadingCarPhrase(context)) }
    fun speakChargingStart() { if (SettingsManager.isChargingVoiceEnabled(context)) speak(SettingsManager.getChargingStartPhrase(context)) }
    fun speakChargingEnd() { if (SettingsManager.isChargingVoiceEnabled(context)) speak(SettingsManager.getChargingEndPhrase(context)) }
    fun speakCharging() = speakChargingStart()

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!isTtsReady) {
            DolphinLogger.w("AUDIO", "TTS 미준비로 출력 생략: len=${text.length}")
            return
        }
        val focus = requestAudioFocus()
        val id = "DolphinTTS_${System.currentTimeMillis()}"
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        DolphinLogger.i("AUDIO", "TTS speak 요청 result=$result focus=$focus id=$id len=${text.length}")
        if (result == TextToSpeech.ERROR) releaseAudioFocus()
    }

    fun release() {
        ldwJob?.cancel()
        bsdJob?.cancel()
        soundScope.cancel()
        tts?.stop()
        tts?.shutdown()
        releaseAudioFocus()
    }
}
