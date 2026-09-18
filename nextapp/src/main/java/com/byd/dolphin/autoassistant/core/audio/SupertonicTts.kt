package com.byd.dolphin.autoassistant.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.byd.dolphin.autoassistant.core.NextLog
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class SupertonicTts(private val context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val engineLock = Any()

    @Volatile private var engine: OfflineTts? = null
    @Volatile var downloading: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    fun isReady(): Boolean {
        val dir = modelDir()
        return requiredFiles.all { (name, minimum) ->
            File(dir, name).let { it.isFile && it.length() >= minimum }
        }
    }

    fun ensureModelAsync() {
        if (isReady() || downloading) return
        synchronized(this) {
            if (downloading) return
            downloading = true
        }
        scope.launch {
            try {
                val dir = modelDir().apply { mkdirs() }
                requiredFiles.forEach { (name, minimum) ->
                    val dst = File(dir, name)
                    if (!dst.isFile || dst.length() < minimum) {
                        downloadOne(MODEL_BASE + "/" + name + "?download=true", dst, minimum)
                    }
                }
                require(isReady()) { "model validation failed" }
                initialize()
                lastError = null
                NextLog.i("TTS", "Supertonic model ready")
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
                NextLog.e("TTS", "model preparation failed", t)
            } finally {
                downloading = false
            }
        }
    }

    fun speak(text: String, voice: VoicePreset): Boolean {
        val clean = text.trim().replace(Regex("\\s+"), " ").take(240)
        if (clean.isBlank()) return true
        if (!isReady()) {
            ensureModelAsync()
            return false
        }

        val cache = cacheFile(clean, voice)
        if (cache.isFile && cache.length() > 44L) {
            scope.launch { playWav(cache) }
            return true
        }

        if (!inFlight.add(cache.name)) return true
        scope.launch {
            try {
                synthesize(clean, voice, cache)
                playWav(cache)
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
                NextLog.e("TTS", "synthesis failed", t)
            } finally {
                inFlight.remove(cache.name)
            }
        }
        return true
    }

    fun release() {
        synchronized(engineLock) {
            runCatching { engine?.release() }
            engine = null
        }
    }

    private fun initialize(): OfflineTts {
        engine?.let { return it }
        synchronized(engineLock) {
            engine?.let { return it }
            val dir = modelDir()
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = File(dir, "duration_predictor.int8.onnx").absolutePath,
                        textEncoder = File(dir, "text_encoder.int8.onnx").absolutePath,
                        vectorEstimator = File(dir, "vector_estimator.int8.onnx").absolutePath,
                        vocoder = File(dir, "vocoder.int8.onnx").absolutePath,
                        ttsJson = File(dir, "tts.json").absolutePath,
                        unicodeIndexer = File(dir, "unicode_indexer.bin").absolutePath,
                        voiceStyle = File(dir, "voice.bin").absolutePath
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1,
                silenceScale = 0.18f
            )
            return OfflineTts(config = config).also {
                engine = it
                NextLog.i("TTS", "engine initialized sampleRate=" + it.sampleRate() + " speakers=" + it.numSpeakers())
            }
        }
    }

    private fun synthesize(text: String, voice: VoicePreset, file: File) {
        val tts = initialize()
        val generated = tts.generateWithConfig(
            text,
            GenerationConfig(
                sid = voice.sid.coerceIn(0, 9),
                speed = voice.speed.coerceIn(0.75f, 1.35f),
                numSteps = 8,
                extra = mapOf("lang" to "ko")
            )
        )
        require(generated.samples.isNotEmpty()) { "no audio generated" }
        writeWav(file, generated.samples, generated.sampleRate)
    }

    private fun cacheFile(text: String, voice: VoicePreset): File {
        val dir = File(app.filesDir, "supertonic-cache").apply { mkdirs() }
        val key = text + "|sid=" + voice.sid + "|speed=" + voice.speed
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, digest + ".wav")
    }

    private fun modelDir(): File = File(app.filesDir, "supertonic/" + MODEL_ID)

    private fun downloadOne(url: String, destination: File, minimum: Long) {
        val tmp = File(destination.parentFile, destination.name + ".part")
        if (tmp.exists()) tmp.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 10 * 60_000
            setRequestProperty("User-Agent", "DolphinAssistant-Next")
        }
        try {
            require(conn.responseCode in 200..299) { "HTTP " + conn.responseCode }
            BufferedInputStream(conn.inputStream, 128 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(tmp), 128 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
            require(tmp.length() >= minimum) { destination.name + " too small" }
            if (destination.exists()) destination.delete()
            require(tmp.renameTo(destination)) { "rename failed " + destination.name }
        } finally {
            conn.disconnect()
        }
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            val value = (sample.coerceIn(-1f, 1f) * 32767f)
                .roundToInt().coerceIn(-32768, 32767)
            pcm.putShort(value.toShort())
        }
        val data = pcm.array()
        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + data.size)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(data.size)
            out.write(header.array())
            out.write(data)
        }
    }

    private fun playWav(file: File) {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "short wav" }
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = header.getInt(24)
        val pcm = bytes.copyOfRange(44, bytes.size)
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
            require(track.write(pcm, 0, pcm.size) == pcm.size) { "AudioTrack write failed" }
            track.setVolume(0.84f)
            track.play()
            val duration = (pcm.size / 2.0 / sampleRate * 1000.0).toLong().coerceAtLeast(100L)
            Thread.sleep(duration + 120L)
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
        method.invoke(builder, STREAM_DRIVER_ONLY)
        return builder.build()
    }

    companion object {
        private const val STREAM_DRIVER_ONLY = 14
        private const val MODEL_ID = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        private const val MODEL_BASE =
            "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main"

        private val requiredFiles = linkedMapOf(
            "duration_predictor.int8.onnx" to 1_000_000L,
            "text_encoder.int8.onnx" to 10_000_000L,
            "vector_estimator.int8.onnx" to 30_000_000L,
            "vocoder.int8.onnx" to 10_000_000L,
            "tts.json" to 1_000L,
            "unicode_indexer.bin" to 50_000L,
            "voice.bin" to 100_000L
        )
    }
}
