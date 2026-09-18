package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.byd.dolphin.autoassistant.R
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

class VoiceAndSoundManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isTtsReady = false
        private set
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bydAudioMcuProbe = BydAudioMcuProbe(context)
    private val soundScope = CoroutineScope(Dispatchers.Default)
    private var ldwJob: Job? = null
    private var bsdJob: Job? = null
    private var routeProbeJob: Job? = null
    private val communityProbeLock = Any()
    @Volatile private var communityProbeSoundPool: SoundPool? = null
    @Volatile private var communityProbeSoundId: Int = 0
    @Volatile private var communityProbeReady = false
    @Volatile private var communityProbeLoadLatch: CountDownLatch? = null

    /**
     * Normal app audio remains on Android's navigation-guidance usage. The new
     * v30.2 probe below deliberately tests additional BYD/legacy candidates only
     * when the user presses the route-test button; it does not silently alter the
     * everyday TTS/warning path before a real-vehicle result is known.
     */
    private val navigationAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val navigationToneAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val mediaToneAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val voiceCommunicationAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val attemptedTtsEngines = linkedSetOf<String>()
    @Volatile private var currentTtsEngineLabel: String = "DEFAULT"
    private val discoveredTtsEngines: List<String> by lazy { discoverTtsEngines() }

    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        DolphinLogger.i("AUDIO", "TTS 초기화 시작; route=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE")
        DolphinLogger.i("AUDIO", "TTS 엔진 후보=${discoveredTtsEngines.ifEmpty { listOf("<none>") }}")
        startTtsEngine(null)
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
            DolphinLogger.e("AUDIO", "TTS 초기화 실패 status=$status engine=$currentTtsEngineLabel")
            if (tryNextTtsEngine()) return
            DolphinLogger.e("AUDIO", "TTS 사용 가능 엔진 없음/전체 실패 candidates=$discoveredTtsEngines")
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
            "TTS 준비=${isTtsReady} languageResult=$languageResult engine=$currentTtsEngineLabel " +
                "usage=NAVIGATION_GUIDANCE outputs=${describeOutputs()}"
        )
    }

    private fun discoverTtsEngines(): List<String> = runCatching {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }.getOrElse {
        DolphinLogger.e("AUDIO", "TTS 엔진 탐색 실패", it)
        emptyList()
    }

    private fun startTtsEngine(enginePackage: String?) {
        val label = enginePackage ?: "DEFAULT"
        if (!attemptedTtsEngines.add(label)) return
        currentTtsEngineLabel = label
        runCatching { tts?.shutdown() }
        isTtsReady = false
        DolphinLogger.i("AUDIO", "TTS 엔진 시도=$label")
        tts = if (enginePackage == null) {
            TextToSpeech(context.applicationContext, this)
        } else {
            TextToSpeech(context.applicationContext, this, enginePackage)
        }
    }

    private fun tryNextTtsEngine(): Boolean {
        val next = discoveredTtsEngines.firstOrNull { !attemptedTtsEngines.contains(it) } ?: return false
        startTtsEngine(next)
        return true
    }

    fun ttsEngineDiagnosticSummary(): String =
        "ready=$isTtsReady current=$currentTtsEngineLabel candidates=${discoveredTtsEngines.ifEmpty { listOf("<none>") }}"

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

    /** Existing single navigation-route test used by warning-sound previews. */
    fun playNavigationRouteTest() {
        soundScope.launch {
            DolphinLogger.i("AUDIO", "운전석/내비게이션 믹스 테스트 시작")
            playNavigationBeepPattern(1040.0, 220, 2, 120)
        }
    }

    /**
     * v30.3 routes are based on the real vehicle's enumerated output devices:
     * TYPE_BUILTIN_EARPIECE(1), TYPE_BUILTIN_SPEAKER(2), TYPE_TELEPHONY(18).
     * The first five probes use 48 kHz stereo because the BYD/Qualcomm NAV path
     * is documented and community-tested in that format.
     */
    fun driverRouteProbeLabel(routeIndex: Int): String = when (routeIndex) {
        1 -> "NAV 48k stereo / 자동 라우팅 (대조군)"
        2 -> "VOICE_COMMUNICATION / Telephony(type=18) / MODE_IN_COMMUNICATION"
        3 -> "STREAM_VOICE_CALL / Telephony(type=18) / MODE_IN_COMMUNICATION"
        4 -> "NAV / Telephony(type=18) / MODE_IN_COMMUNICATION"
        5 -> "레거시 AudioTrack stream 14 / STATIC write-first 수정"
        6 -> "legacy AudioAttributes setLegacyStreamType(14)"
        7 -> "BYD MCU HW_L1 sounding-direction 0→3 / 원복"
        else -> "알 수 없는 경로"
    }

    /**
     * Runs all seven candidates in order. Route N emits N short beeps so the user
     * can identify the physically correct speaker without looking at the screen.
     */
    fun playDriverRouteComparison(
        onStep: ((Int, String) -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ): Boolean {
        if (routeProbeJob?.isActive == true) {
            DolphinLogger.w("AUDIO_PROBE", "comparison REJECTED: routeProbeJob already active")
            return false
        }
        DolphinLogger.i("AUDIO_PROBE", "comparison ACCEPTED from UI")
        routeProbeJob = soundScope.launch {
            DolphinLogger.i("AUDIO_PROBE", "===== v30.4 7경로 운전석/MCU 오디오 비교 시작 =====")
            logAudioEnvironment("comparison_start")
            DolphinLogger.i("AUDIO_PROBE", "v30.4: prior media-like routes removed; telephony/legacy14/MCU-HW_L1 focus")
            delay(300L)
            try {
                for (route in 1..7) {
                    val label = driverRouteProbeLabel(route)
                    onStep?.invoke(route, label)
                    DolphinLogger.i("AUDIO_PROBE", "ROUTE_$route START label=$label beepCount=$route")
                    playDriverRouteProbeInternal(route, route)
                    DolphinLogger.i("AUDIO_PROBE", "ROUTE_$route END label=$label")
                    if (route < 7) delay(1_400L)
                }
            } finally {
                logAudioEnvironment("comparison_end")
                DolphinLogger.i("AUDIO_PROBE", "===== v30.4 7경로 운전석/MCU 오디오 비교 종료 =====")
                onDone?.invoke()
            }
        }
        return true
    }

    /** Runs one candidate only; useful when the user wants to repeat a winner. */
    fun playDriverRouteProbe(
        routeIndex: Int,
        onDone: (() -> Unit)? = null
    ): Boolean {
        if (routeIndex !in 1..7) {
            DolphinLogger.w("AUDIO_PROBE", "single route REJECTED invalid route=$routeIndex")
            return false
        }
        if (routeProbeJob?.isActive == true) {
            DolphinLogger.w("AUDIO_PROBE", "single route REJECTED: routeProbeJob already active route=$routeIndex")
            return false
        }
        DolphinLogger.i("AUDIO_PROBE", "single route ACCEPTED from UI route=$routeIndex")
        routeProbeJob = soundScope.launch {
            val label = driverRouteProbeLabel(routeIndex)
            DolphinLogger.i("AUDIO_PROBE", "단일 경로 테스트 시작 route=$routeIndex label=$label")
            logAudioEnvironment("single_route_${routeIndex}_before")
            DolphinLogger.i("AUDIO_PROBE", "v30.4 single route probe")
            delay(300L)
            try {
                playDriverRouteProbeInternal(routeIndex, routeIndex)
            } finally {
                logAudioEnvironment("single_route_${routeIndex}_after")
                DolphinLogger.i("AUDIO_PROBE", "단일 경로 테스트 종료 route=$routeIndex label=$label")
                onDone?.invoke()
            }
        }
        return true
    }

    fun isDriverRouteProbeRunning(): Boolean = routeProbeJob?.isActive == true

    fun stopDriverRouteProbe() {
        if (routeProbeJob?.isActive == true) {
            DolphinLogger.w("AUDIO_PROBE", "route probe STOP requested from UI")
            routeProbeJob?.cancel()
        } else {
            DolphinLogger.i("AUDIO_PROBE", "route probe STOP requested but no active job")
        }
    }

    /** Audible execution marker only. STREAM_MUSIC intentionally proves the button actually started. */
    private fun playExecutionMarker() {
        runCatching {
            DolphinLogger.i("AUDIO_PROBE", "MEDIA_EXECUTION_MARKER start; not a route judgement tone")
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 220)
            Thread.sleep(260L)
            tg.release()
            DolphinLogger.i("AUDIO_PROBE", "MEDIA_EXECUTION_MARKER end")
        }.onFailure { DolphinLogger.e("AUDIO_PROBE", "MEDIA_EXECUTION_MARKER failed", it) }
    }

    private suspend fun playDriverRouteProbeInternal(routeIndex: Int, beepCount: Int) {
        requestAudioFocus()
        try {
            val frequency = 700.0 + routeIndex * 95.0
            when (routeIndex) {
                7 -> playMcuHwL1DirectionSweep(routeIndex)
                else -> repeat(beepCount) { beep ->
                    when (routeIndex) {
                        1 -> playProbe48kStereo(routeIndex, driverRouteProbeLabel(1), navigationToneAttributes, null, frequency, 260)
                        2 -> playTelephonyVoiceCommunicationProbe(routeIndex, frequency, 300)
                        3 -> playLegacyVoiceCallTelephonyProbe(routeIndex, frequency, 300)
                        4 -> playNavTelephonyCommunicationModeProbe(routeIndex, frequency, 300)
                        5 -> playProbeWithLegacyAudioTrack14(routeIndex, frequency, 300)
                        6 -> playProbeWithLegacyAttribute14(routeIndex, frequency, 300)
                    }
                    if (beep < beepCount - 1) delay(120L)
                }
            }
        } finally {
            releaseAudioFocus()
        }
    }

    private inline fun <T> withTemporaryCommunicationAudio(block: () -> T): T {
        val previousMode = audioManager.mode
        val previousVoiceVolume = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(-1)
        val maxVoice = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(-1)
        try {
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                .onFailure { DolphinLogger.e("AUDIO_PROBE", "MODE_IN_COMMUNICATION 설정 실패", it) }
            if (maxVoice > 0) {
                val target = maxOf(1, (maxVoice * 0.7).toInt())
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0) }
                    .onFailure { DolphinLogger.e("AUDIO_PROBE", "STREAM_VOICE_CALL 임시 볼륨 설정 실패", it) }
                DolphinLogger.i("AUDIO_PROBE", "voice env mode=${audioManager.mode} voiceVol=$previousVoiceVolume->$target/$maxVoice")
            } else {
                DolphinLogger.i("AUDIO_PROBE", "voice env mode=${audioManager.mode} voiceVol=unavailable current=$previousVoiceVolume max=$maxVoice")
            }
            return block()
        } finally {
            if (previousVoiceVolume >= 0) {
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, previousVoiceVolume, 0) }
            }
            runCatching { audioManager.mode = previousMode }
            DolphinLogger.i("AUDIO_PROBE", "voice env restored mode=$previousMode voiceVol=$previousVoiceVolume")
        }
    }

    private fun playTelephonyVoiceCommunicationProbe(routeIndex: Int, frequencyHz: Double, durationMs: Int) {
        withTemporaryCommunicationAudio {
            playProbe48kStereo(
                routeIndex, driverRouteProbeLabel(routeIndex), voiceCommunicationAttributes,
                AudioDeviceInfo.TYPE_TELEPHONY, frequencyHz, durationMs
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun playLegacyVoiceCallTelephonyProbe(routeIndex: Int, frequencyHz: Double, durationMs: Int) {
        withTemporaryCommunicationAudio {
            val label = driverRouteProbeLabel(routeIndex)
            val sampleRate = 48_000
            val samples = buildStereoToneSamples(sampleRate, frequencyHz, durationMs, 0.28)
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(0)
                val candidate = AudioTrack(
                    AudioManager.STREAM_VOICE_CALL, sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(samples.size * 2, minBuffer), AudioTrack.MODE_STATIC
                )
                track = candidate
                val requested = findOutputDevice(AudioDeviceInfo.TYPE_TELEPHONY)
                val accepted = if (requested != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) candidate.setPreferredDevice(requested) else false
                val preState = candidate.state
                val written = candidate.write(samples, 0, samples.size)
                DolphinLogger.i("AUDIO_PROBE", "ROUTE_$routeIndex voicecall built preState=$preState write=$written telephony=${requested != null} preferredAccepted=$accepted")
                candidate.setVolume(0.78f)
                candidate.play()
                Thread.sleep(100L)
                logTrackRoute(routeIndex, label, candidate)
                Thread.sleep(durationMs.toLong())
                runCatching { candidate.stop() }
            } catch (t: Throwable) {
                DolphinLogger.e("AUDIO_PROBE", "ROUTE_$routeIndex STREAM_VOICE_CALL telephony 실패", t)
            } finally {
                runCatching { track?.release() }
            }
        }
    }

    private fun playNavTelephonyCommunicationModeProbe(routeIndex: Int, frequencyHz: Double, durationMs: Int) {
        withTemporaryCommunicationAudio {
            playProbe48kStereo(
                routeIndex, driverRouteProbeLabel(routeIndex), navigationToneAttributes,
                AudioDeviceInfo.TYPE_TELEPHONY, frequencyHz, durationMs
            )
        }
    }

    private fun playMcuHwL1DirectionSweep(routeIndex: Int) {
        val snap = bydAudioMcuProbe.snapshot()
        DolphinLogger.i("MCU_AUDIO_PROBE", "snapshot=$snap")
        val original = snap.hwL1DirectionState
        if (!bydAudioMcuProbe.canSafelySweepHwL1(original)) {
            DolphinLogger.w("MCU_AUDIO_PROBE", "HW_L1 sweep SKIP: readable restorable original unavailable value=$original")
            playProbe48kStereo(routeIndex, "MCU HW_L1 SKIP / NAV baseline", navigationToneAttributes, null, 1360.0, 420)
            return
        }
        try {
            for (value in 0..3) {
                val setResult = bydAudioMcuProbe.writeInt(BydAudioMcuProbe.FID_HW_L1_DIRECTION_SET, value)
                Thread.sleep(220L)
                val readback = bydAudioMcuProbe.readInt(BydAudioMcuProbe.FID_HW_L1_DIRECTION_STATUS)
                DolphinLogger.i("MCU_AUDIO_PROBE", "HW_L1 candidate=$value setResult=$setResult readback=$readback original=$original")
                repeat(value + 1) { idx ->
                    playProbe48kStereo(routeIndex, "MCU HW_L1 dir=$value group=${value + 1}", navigationToneAttributes, null, 980.0 + value * 140.0, 260)
                    if (idx < value) Thread.sleep(120L)
                }
                Thread.sleep(650L)
            }
        } finally {
            val restoreResult = bydAudioMcuProbe.writeInt(BydAudioMcuProbe.FID_HW_L1_DIRECTION_SET, original!!)
            Thread.sleep(220L)
            val restoreReadback = bydAudioMcuProbe.readInt(BydAudioMcuProbe.FID_HW_L1_DIRECTION_STATUS)
            DolphinLogger.i("MCU_AUDIO_PROBE", "HW_L1 RESTORE original=$original setResult=$restoreResult readback=$restoreReadback")
        }
    }

    private fun findOutputDevice(type: Int): AudioDeviceInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == type }
        } else null
    }.getOrNull()

    private fun buildStereoToneSamples(sampleRate: Int, frequencyHz: Double, durationMs: Int, amplitude: Double): ShortArray {
        val frameCount = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val mono = ShortArray(frameCount) { index ->
            (Short.MAX_VALUE * amplitude * sin(2.0 * PI * index * frequencyHz / sampleRate)).toInt().toShort()
        }
        return ShortArray(frameCount * 2) { i -> mono[i / 2] }
    }

    private fun playProbe48kStereo(
        routeIndex: Int,
        label: String,
        attributes: AudioAttributes,
        preferredDeviceType: Int?,
        frequencyHz: Double,
        durationMs: Int
    ) {
        val sampleRate = 48_000
        val samples = buildStereoToneSamples(sampleRate, frequencyHz, durationMs, 0.28)
        var track: AudioTrack? = null
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            val bufferBytes = maxOf(samples.size * 2, minBuffer)
            val candidate = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufferBytes)
                .build()
            track = candidate

            val requestedDevice = preferredDeviceType?.let { findOutputDevice(it) }
            val preferredAccepted = if (preferredDeviceType != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (requestedDevice != null) candidate.setPreferredDevice(requestedDevice) else false
            } else false

            val written = candidate.write(samples, 0, samples.size)
            DolphinLogger.i(
                "AUDIO_PROBE",
                "ROUTE_$routeIndex built label=$label usage=${attributes.usage} content=${attributes.contentType} " +
                    "sampleRate=$sampleRate stereo=true minBuffer=$minBuffer bufferBytes=$bufferBytes write=$written " +
                    "preferredType=$preferredDeviceType requestedDevice=${requestedDevice?.let { "id=${it.id},type=${it.type},name=${it.productName}" } ?: "none"} " +
                    "preferredAccepted=$preferredAccepted"
            )
            candidate.setVolume(0.78f)
            candidate.play()
            Thread.sleep(90L)
            logTrackRoute(routeIndex, label, candidate)
            Thread.sleep((durationMs - 20).coerceAtLeast(60).toLong())
            runCatching { candidate.stop() }
        } catch (t: Throwable) {
            DolphinLogger.e("AUDIO_PROBE", "ROUTE_$routeIndex 48k stereo 실패 label=$label", t)
        } finally {
            runCatching { track?.release() }
        }
    }

    private fun playProbeWithAttributes(
        routeIndex: Int,
        label: String,
        attributes: AudioAttributes,
        sampleRate: Int,
        frequencyHz: Double,
        durationMs: Int
    ) {
        val samples = buildToneSamples(sampleRate, frequencyHz, durationMs, amplitude = 0.24)
        var track: AudioTrack? = null
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            val bufferBytes = maxOf(samples.size * 2, minBuffer)
            val candidate = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufferBytes)
                .build()
            track = candidate
            val written = candidate.write(samples, 0, samples.size)
            DolphinLogger.i(
                "AUDIO_PROBE",
                "ROUTE_$routeIndex built label=$label usage=${attributes.usage} content=${attributes.contentType} " +
                    "sampleRate=$sampleRate minBuffer=$minBuffer bufferBytes=$bufferBytes write=$written"
            )
            candidate.setVolume(0.72f)
            candidate.play()
            Thread.sleep(70L)
            logTrackRoute(routeIndex, label, candidate)
            Thread.sleep((durationMs - 40).coerceAtLeast(40).toLong())
            runCatching { candidate.stop() }
        } catch (t: Throwable) {
            DolphinLogger.e("AUDIO_PROBE", "ROUTE_$routeIndex 실패 label=$label", t)
        } finally {
            runCatching { track?.release() }
        }
    }


    /**
     * Candidate 2 mirrors the community BydAudioFeedback implementation as closely
     * as practical: SoundPool + USAGE_ASSISTANCE_NAVIGATION_GUIDANCE + SPEECH.
     * A small bundled WAV is used so this probe exercises SoundPool rather than
     * merely changing AudioTrack content type.
     */
    private fun playProbeWithCommunitySoundPool(routeIndex: Int, label: String) {
        try {
            val poolAndId = ensureCommunityProbeSoundPool()
                ?: throw IllegalStateException("SoundPool sample load failed or timed out")
            val (pool, soundId) = poolAndId
            val streamId = pool.play(soundId, 0.72f, 0.72f, 1, 0, 1.0f)
            DolphinLogger.i(
                "AUDIO_PROBE",
                "ROUTE_$routeIndex SoundPool play label=$label soundId=$soundId streamId=$streamId " +
                    "usage=${navigationAudioAttributes.usage} content=${navigationAudioAttributes.contentType}"
            )
            if (streamId == 0) throw IllegalStateException("SoundPool.play returned streamId=0")
            Thread.sleep(310L)
            runCatching { pool.stop(streamId) }
            DolphinLogger.i("AUDIO_PROBE", "ROUTE_$routeIndex SoundPool ACTIVE label=$label outputs=${describeOutputs()}")
        } catch (t: Throwable) {
            DolphinLogger.e("AUDIO_PROBE", "ROUTE_$routeIndex SoundPool NAV/SPEECH 실패", t)
        }
    }

    private fun ensureCommunityProbeSoundPool(): Pair<SoundPool, Int>? {
        communityProbeSoundPool?.let { pool ->
            if (communityProbeReady && communityProbeSoundId != 0) return pool to communityProbeSoundId
        }

        val latch = synchronized(communityProbeLock) {
            communityProbeSoundPool?.let { pool ->
                if (communityProbeReady && communityProbeSoundId != 0) return pool to communityProbeSoundId
            }
            if (communityProbeSoundPool == null) {
                val newLatch = CountDownLatch(1)
                communityProbeLoadLatch = newLatch
                val pool = SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(navigationAudioAttributes)
                    .build()
                pool.setOnLoadCompleteListener { _, sampleId, status ->
                    communityProbeReady = status == 0 && sampleId != 0
                    DolphinLogger.i(
                        "AUDIO_PROBE",
                        "SoundPool sample load sampleId=$sampleId status=$status ready=$communityProbeReady"
                    )
                    communityProbeLoadLatch?.countDown()
                }
                communityProbeSoundPool = pool
                communityProbeSoundId = pool.load(context, R.raw.driver_audio_probe_beep, 1)
                DolphinLogger.i("AUDIO_PROBE", "SoundPool sample load requested soundId=$communityProbeSoundId")
            }
            communityProbeLoadLatch ?: CountDownLatch(0)
        }

        if (!communityProbeReady) {
            latch.await(2, TimeUnit.SECONDS)
        }
        val pool = communityProbeSoundPool
        return if (pool != null && communityProbeReady && communityProbeSoundId != 0) {
            pool to communityProbeSoundId
        } else null
    }

    /**
     * Candidate 3 asks the BYD framework whether its customized AudioAttributes
     * still exposes a legacy stream 14 mapping. Reflection is intentional here:
     * standard Android SDK does not document a BYD stream 14 contract.
     */
    private fun playProbeWithLegacyAttribute14(
        routeIndex: Int,
        frequencyHz: Double,
        durationMs: Int
    ) {
        val label = driverRouteProbeLabel(routeIndex)
        try {
            val builder = AudioAttributes.Builder()
            val setter = builder.javaClass.methods.firstOrNull {
                it.name == "setLegacyStreamType" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: builder.javaClass.declaredMethods.firstOrNull {
                it.name == "setLegacyStreamType" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: throw NoSuchMethodException("AudioAttributes.Builder.setLegacyStreamType(int)")
            runCatching { setter.isAccessible = true }
            setter.invoke(builder, 14)
            val attrs = builder.build()
            DolphinLogger.i(
                "AUDIO_PROBE",
                "ROUTE_$routeIndex stream14 attribute 생성 성공 usage=${attrs.usage} content=${attrs.contentType} flags=${attrs.flags}"
            )
            playProbeWithAttributes(routeIndex, label, attrs, 44_100, frequencyHz, durationMs)
        } catch (t: Throwable) {
            DolphinLogger.e("AUDIO_PROBE", "ROUTE_$routeIndex legacy-attribute stream14 생성 실패", unwrapReflection(t))
        }
    }

    /** Candidate 4 goes through the deprecated AudioTrack streamType constructor directly. */
    @Suppress("DEPRECATION")
    private fun playProbeWithLegacyAudioTrack14(
        routeIndex: Int,
        frequencyHz: Double,
        durationMs: Int
    ) {
        val label = driverRouteProbeLabel(routeIndex)
        val sampleRate = 44_100
        val samples = buildToneSamples(sampleRate, frequencyHz, durationMs, amplitude = 0.24)
        var track: AudioTrack? = null
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            val bufferBytes = maxOf(samples.size * 2, minBuffer)
            val candidate = AudioTrack(
                14,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
                AudioTrack.MODE_STATIC
            )
            track = candidate
            val preWriteState = candidate.state
            // MODE_STATIC legitimately reports STATE_NO_STATIC_DATA(2) until write().
            // v30.3.2 incorrectly aborted here, so route 7 was never actually tested.
            if (preWriteState != AudioTrack.STATE_INITIALIZED && preWriteState != AudioTrack.STATE_NO_STATIC_DATA) {
                throw IllegalStateException("AudioTrack stream14 unexpected preWriteState=$preWriteState")
            }
            val written = candidate.write(samples, 0, samples.size)
            val postWriteState = candidate.state
            DolphinLogger.i(
                "AUDIO_PROBE",
                "ROUTE_$routeIndex built label=$label rawStream=14 sampleRate=$sampleRate " +
                    "minBuffer=$minBuffer bufferBytes=$bufferBytes preState=$preWriteState postState=$postWriteState write=$written"
            )
            if (postWriteState != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack stream14 postWriteState=$postWriteState written=$written")
            }
            candidate.setVolume(0.72f)
            candidate.play()
            Thread.sleep(70L)
            logTrackRoute(routeIndex, label, candidate)
            Thread.sleep((durationMs - 40).coerceAtLeast(40).toLong())
            runCatching { candidate.stop() }
        } catch (t: Throwable) {
            DolphinLogger.e("AUDIO_PROBE", "ROUTE_$routeIndex 레거시 AudioTrack stream14 실패", t)
        } finally {
            runCatching { track?.release() }
        }
    }

    private fun logTrackRoute(routeIndex: Int, label: String, track: AudioTrack) {
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { track.routedDevice }.getOrNull()
        } else null
        val preferred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { track.preferredDevice }.getOrNull()
        } else null
        val routedText = routed?.let { "id=${it.id},type=${it.type},name=${it.productName}" } ?: "null"
        val preferredText = preferred?.let { "id=${it.id},type=${it.type},name=${it.productName}" } ?: "null"
        val frames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { track.bufferSizeInFrames }.getOrDefault(-1)
        } else -1
        val capacity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { track.bufferCapacityInFrames }.getOrDefault(-1)
        } else -1
        val perf = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { track.performanceMode }.getOrDefault(-1)
        } else -1
        DolphinLogger.i(
            "AUDIO_PROBE",
            "ROUTE_$routeIndex ACTIVE label=$label session=${track.audioSessionId} state=${track.state} " +
                "playState=${track.playState} sampleRate=${track.sampleRate} frames=$frames capacity=$capacity perf=$perf " +
                "routedDevice={$routedText} preferredDevice={$preferredText}"
        )
    }

    private fun logAudioEnvironment(stage: String) {
        val outputRate = runCatching { audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }.getOrNull()
        val frames = runCatching { audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }.getOrNull()
        val stream14 = runCatching {
            val current = audioManager.getStreamVolume(14)
            val max = audioManager.getStreamMaxVolume(14)
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(14) else -1
            "recognized current=$current min=$min max=$max"
        }.getOrElse { "unavailable ${it.javaClass.simpleName}:${it.message}" }
        DolphinLogger.i(
            "AUDIO_PROBE",
            "$stage outputRate=$outputRate framesPerBuffer=$frames stream14={$stream14} outputs=${describeOutputs()}"
        )
    }

    private fun buildToneSamples(
        sampleRate: Int,
        frequencyHz: Double,
        durationMs: Int,
        amplitude: Double
    ): ShortArray {
        val sampleCount = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val rampSamples = (sampleRate / 100).coerceAtLeast(1)
        return ShortArray(sampleCount) { index ->
            val envelope = when {
                index < rampSamples -> index.toDouble() / rampSamples.toDouble()
                index > sampleCount - rampSamples -> (sampleCount - index).toDouble() / rampSamples.toDouble()
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            (Short.MAX_VALUE * amplitude * envelope * sin(2.0 * PI * index * frequencyHz / sampleRate)).toInt().toShort()
        }
    }

    private fun unwrapReflection(t: Throwable): Throwable {
        return t.cause ?: t
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
        // v30.2 실차 로그에서 16 kHz mono AudioTrack.write()는 성공했지만 실제 소리는 나지 않았다.
        // BYD/Qualcomm NAV 경로에 맞춰 48 kHz stereo로 고정한다.
        val sampleRate = 48_000
        val samples = buildStereoToneSamples(sampleRate, frequencyHz, durationMs, amplitude = 0.32)
        var audioTrack: AudioTrack? = null
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            val track = AudioTrack.Builder()
                .setAudioAttributes(navigationToneAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(maxOf(samples.size * 2, minBuffer))
                .build()
            audioTrack = track
            val written = track.write(samples, 0, samples.size)
            DolphinLogger.i(
                "AUDIO",
                "NAV tone 48k stereo write=$written freq=$frequencyHz duration=$durationMs session=${track.audioSessionId}"
            )
            track.setVolume(0.78f)
            track.play()
            Thread.sleep(80L)
            logTrackRoute(0, "NAV_WARNING_48K_AUTO", track)
            Thread.sleep(durationMs.toLong().coerceAtLeast(80L))
            runCatching { track.stop() }
        } catch (e: Exception) {
            DolphinLogger.e("AUDIO", "NAV tone 48k stereo 출력 실패", e)
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
    fun speakIcc(isActive: Boolean) { if (SettingsManager.isIccVoiceEnabled(context)) speak(SettingsManager.getIccPhrase(context, isActive)) }
    fun speakLeadingCarDeparture() { if (SettingsManager.isLeadingCarVoiceEnabled(context)) speak(SettingsManager.getLeadingCarPhrase(context)) }
    fun speakChargingStart() { if (SettingsManager.isChargingVoiceEnabled(context)) speak(SettingsManager.getChargingStartPhrase(context)) }
    fun speakChargingEnd() { if (SettingsManager.isChargingVoiceEnabled(context)) speak(SettingsManager.getChargingEndPhrase(context)) }
    fun speakCharging() = speakChargingStart()

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!isTtsReady) {
            DolphinLogger.w(
                "AUDIO",
                "TTS 미준비: 음성 대신 48k NAV 비프 fallback; len=${text.length}; ${ttsEngineDiagnosticSummary()}"
            )
            soundScope.launch { playNavigationBeepPattern(1180.0, 170, 2, 100) }
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
        routeProbeJob?.cancel()
        runCatching { communityProbeSoundPool?.release() }
        communityProbeSoundPool = null
        soundScope.cancel()
        tts?.stop()
        tts?.shutdown()
        releaseAudioFocus()
    }
}
