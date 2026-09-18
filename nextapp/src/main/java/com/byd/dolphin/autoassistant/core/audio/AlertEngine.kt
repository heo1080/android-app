package com.byd.dolphin.autoassistant.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.byd.dolphin.autoassistant.core.NextLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

class AlertEngine(context: Context) {
    private val app = context.applicationContext
    val repository = AlertRepository(app)
    private val tts = SupertonicTts(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureTtsModel() = tts.ensureModelAsync()
    fun ttsReady(): Boolean = tts.isReady()
    fun ttsDownloading(): Boolean = tts.downloading
    fun ttsLastError(): String? = tts.lastError

    fun dispatch(eventId: String, state: String) {
        val spec = AlertCatalog.specs.firstOrNull { it.id == eventId } ?: return
        val profile = repository.getProfile(eventId)
        when (profile.mode) {
            OutputMode.OFF -> NextLog.d("ALERT", eventId + " OFF")
            OutputMode.BEEP -> playBeep(repository.beep(profile))
            OutputMode.TTS -> {
                val phrase = repository.phrase(spec, profile, state)
                val voice = repository.voice(profile)
                val accepted = tts.speak(phrase, voice)
                NextLog.i("ALERT", eventId + " TTS accepted=" + accepted + " text=" + phrase)
                if (!accepted) playBeep(repository.beep(profile))
            }
        }
    }

    fun preview(eventId: String) {
        val spec = AlertCatalog.specs.firstOrNull { it.id == eventId } ?: return
        dispatch(eventId, spec.sampleState)
    }

    fun previewVoice(voiceId: String) {
        val voice = AlertCatalog.voices.firstOrNull { it.id == voiceId } ?: return
        if (!tts.speak("DolphinAssistant 음성 미리듣기입니다.", voice)) {
            tts.ensureModelAsync()
            playBeep(AlertCatalog.beeps.first { it.id == "SOFT" })
        }
    }

    fun release() = tts.release()

    private fun playBeep(preset: BeepPreset) {
        scope.launch {
            preset.frequencies.forEachIndexed { index, freq ->
                runCatching { playTone(freq, preset.durationMs) }
                    .onFailure { NextLog.e("ALERT", "beep failed", it) }
                if (index < preset.frequencies.lastIndex && preset.gapMs > 0) {
                    delay(preset.gapMs)
                }
            }
        }
    }

    private fun playTone(frequency: Double, durationMs: Int) {
        val sampleRate = 24_000
        val samples = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val envelope = when {
                i < samples / 10 -> i.toDouble() / (samples / 10.0)
                i > samples * 9 / 10 -> (samples - i).toDouble() / (samples / 10.0)
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            val v = (sin(2.0 * PI * frequency * i / sampleRate) * 0.36 * envelope * 32767.0).toInt()
            pcm[i * 2] = (v and 0xff).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }

        var track: AudioTrack? = null
        try {
            val min = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            track = AudioTrack.Builder()
                .setAudioAttributes(driverAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(maxOf(min, pcm.size))
                .build()
            require(track.write(pcm, 0, pcm.size) == pcm.size)
            track.setVolume(0.8f)
            track.play()
            Thread.sleep(durationMs.toLong() + 40L)
        } finally {
            runCatching { track?.stop() }
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
