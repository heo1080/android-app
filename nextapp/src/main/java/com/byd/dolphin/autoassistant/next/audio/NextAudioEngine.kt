package com.byd.dolphin.autoassistant.next.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.settings.AlertMode
import com.byd.dolphin.autoassistant.next.settings.NextSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.PI
import kotlin.math.sin

enum class AudioProbePhase { REQUEST, START, ERROR }

data class AudioProbeEvent(
    val probeKey: String,
    val phase: AudioProbePhase,
    val timestampMs: Long,
    val detail: String = ""
)

class NextAudioEngine(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _probeEvents = MutableSharedFlow<AudioProbeEvent>(extraBufferCapacity = 64)
    val probeEvents: SharedFlow<AudioProbeEvent> = _probeEvents.asSharedFlow()

    fun testTts(): Pair<Long, Boolean> {
        val requestAt = System.currentTimeMillis()
        trace("TEST_TTS", AudioProbePhase.REQUEST, "stream14")
        val profile = NextSettings.getAlertProfile(app, NextSettings.EVENT_GEAR)
        val voice = NextSettings.getVoice(app, profile)
        val accepted = SupertonicEngine.speak(
            app,
            "DolphinAssistant 테스트 음성입니다.",
            voice.sid,
            voice.speed
        ) { startedAt, route ->
            trace("TEST_TTS", AudioProbePhase.START, route, startedAt)
        }
        if (!accepted) {
            trace("TEST_TTS", AudioProbePhase.ERROR, "TTS MODEL NOT READY")
        }
        return requestAt to accepted
    }

    fun testBeep(): Long {
        val requestAt = System.currentTimeMillis()
        trace("TEST_BEEP", AudioProbePhase.REQUEST, "stream14")
        scope.launch {
            playPreset(
                frequencies = listOf(1080.0, 1080.0),
                durationMs = 100,
                gapMs = 70,
                probeKey = "TEST_BEEP"
            )
        }
        return requestAt
    }

    private fun trace(
        key: String,
        phase: AudioProbePhase,
        detail: String = "",
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val event = AudioProbeEvent(key, phase, timestampMs, detail)
        _probeEvents.tryEmit(event)
        NextLogger.i("AUDIO_PROBE", key + " " + phase.name + " " + detail)
    }

    fun emit(eventKey: String, state: String, probePrefix: String = "LIVE") {
        val spec = NextSettings.alertSpecs.firstOrNull { it.key == eventKey } ?: return
        val profile = NextSettings.getAlertProfile(app, eventKey)
        when (profile.mode) {
            AlertMode.OFF -> NextLogger.d("AUDIO", eventKey + " OFF")
            AlertMode.BEEP -> {
                val preset = NextSettings.getBeep(profile.beepId)
                val probeKey = probePrefix + "_" + eventKey
                trace(probeKey, AudioProbePhase.REQUEST, "BEEP")
                scope.launch {
                    playPreset(
                        preset.frequencies,
                        preset.durationMs,
                        preset.gapMs,
                        probeKey = probeKey
                    )
                }
            }
            AlertMode.TTS -> {
                val text = NextSettings.resolveText(spec, profile, state)
                val voice = NextSettings.getVoice(app, profile)
                val probeKey = probePrefix + "_" + eventKey
                trace(probeKey, AudioProbePhase.REQUEST, "TTS")
                val accepted = SupertonicEngine.speak(
                    app,
                    text,
                    voice.sid,
                    voice.speed
                ) { startedAt, route ->
                    trace(probeKey, AudioProbePhase.START, "TTS / " + route, startedAt)
                }
                NextLogger.i("AUDIO", eventKey + " TTS accepted=" + accepted + " voice=" + voice.id + " text=" + text)
                if (!accepted) {
                    trace(probeKey, AudioProbePhase.ERROR, "TTS MODEL NOT READY")
                    val preset = NextSettings.getBeep(profile.beepId)
                    scope.launch {
                        playPreset(
                            preset.frequencies,
                            preset.durationMs,
                            preset.gapMs,
                            probeKey = probePrefix + "_FALLBACK_" + eventKey
                        )
                    }
                }
            }
        }
    }

    fun preview(eventKey: String) {
        val sample = when (eventKey) {
            NextSettings.EVENT_GEAR -> "D"
            NextSettings.EVENT_REGEN -> "HIGH"
            NextSettings.EVENT_DRIVE -> "NORMAL"
            NextSettings.EVENT_SNOW -> "스노우모드"
            NextSettings.EVENT_AUTOHOLD_SWITCH -> "ON"
            NextSettings.EVENT_AUTOHOLD_HOLD -> "체결"
            NextSettings.EVENT_EPB -> "체결"
            NextSettings.EVENT_ICC -> "ON"
            NextSettings.EVENT_BSD -> "왼쪽"
            NextSettings.EVENT_LEADING -> "출발"
            else -> "안내"
        }
        emit(eventKey, sample, probePrefix = "PREVIEW")
    }

    fun previewVoice(voiceId: String) {
        val voice = NextSettings.voicePresets.firstOrNull { it.id == voiceId } ?: return
        val accepted = SupertonicEngine.speak(app, "DolphinAssistant 음성 미리듣기입니다.", voice.sid, voice.speed)
        if (!accepted) scope.launch { playPreset(listOf(900.0, 1200.0), 100, 60) }
    }

    private suspend fun playPreset(
        frequencies: List<Double>,
        durationMs: Int,
        gapMs: Long,
        probeKey: String? = null
    ) {
        frequencies.forEachIndexed { index, hz ->
            playTone(hz, durationMs) { route ->
                if (index == 0 && probeKey != null) {
                    trace(probeKey, AudioProbePhase.START, route)
                }
            }
            if (index < frequencies.lastIndex && gapMs > 0) delay(gapMs)
        }
    }

    private fun playTone(hz: Double, durationMs: Int, onStart: ((String) -> Unit)? = null) {
        val rate = 24_000
        val count = (rate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val samples = ShortArray(count)
        for (i in 0 until count) {
            val envelope = when {
                i < rate * 0.01 -> i / (rate * 0.01)
                i > count - rate * 0.02 -> (count - i) / (rate * 0.02)
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            samples[i] = (sin(2.0 * PI * hz * i / rate) * 11_500.0 * envelope).toInt().toShort()
        }
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[i * 2] = (s.toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xff).toByte()
        }

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(driverAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes.size)
                .build()
            track.write(bytes, 0, bytes.size)
            track.setVolume(0.82f)
            track.play()
            val route = runCatching {
                val device = track.routedDevice
                if (device == null) "STREAM14 / ROUTE UNKNOWN"
                else "STREAM14 / " + device.productName + " / type=" + device.type
            }.getOrDefault("STREAM14 / ROUTE UNKNOWN")
            onStart?.invoke(route)
            Thread.sleep(durationMs.toLong() + 35L)
            runCatching { track.stop() }
        } catch (t: Throwable) {
            NextLogger.e("AUDIO", "beep playback failed", t)
        } finally {
            runCatching { track?.release() }
        }
    }

    private fun driverAttributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
        val method = builder.javaClass.methods.firstOrNull {
            it.name == "setLegacyStreamType" && it.parameterTypes.size == 1
        } ?: builder.javaClass.declaredMethods.first {
            it.name == "setLegacyStreamType" && it.parameterTypes.size == 1
        }
        runCatching { method.isAccessible = true }
        method.invoke(builder, 14)
        return builder.build()
    }
}
