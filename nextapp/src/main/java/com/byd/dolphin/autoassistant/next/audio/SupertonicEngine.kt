package com.byd.dolphin.autoassistant.next.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object SupertonicEngine {
    private const val MODEL_ID = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    private const val MODEL_BASE = "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main"
    private const val DRIVER_STREAM = 14

    private val required = linkedMapOf(
        "duration_predictor.int8.onnx" to 1_000_000L,
        "text_encoder.int8.onnx" to 10_000_000L,
        "vector_estimator.int8.onnx" to 30_000_000L,
        "vocoder.int8.onnx" to 10_000_000L,
        "tts.json" to 1_000L,
        "unicode_indexer.bin" to 50_000L,
        "voice.bin" to 100_000L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val engineLock = Any()
    @Volatile private var engine: OfflineTts? = null
    @Volatile private var downloading = false

    private fun modelDir(context: Context) = File(context.filesDir, "next-supertonic/$MODEL_ID")
    private fun cacheDir(context: Context) = File(context.filesDir, "next-supertonic-cache").apply { mkdirs() }

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return required.all { (name, size) -> File(dir, name).let { it.isFile && it.length() >= size } }
    }

    fun ensureModelAsync(context: Context) {
        val app = context.applicationContext
        if (isReady(app) || downloading) return
        synchronized(this) {
            if (downloading) return
            downloading = true
        }
        scope.launch {
            try {
                val dir = modelDir(app).apply { mkdirs() }
                required.forEach { (name, minimum) ->
                    val dst = File(dir, name)
                    if (!dst.isFile || dst.length() < minimum) {
                        download(MODEL_BASE + "/" + name + "?download=true", dst, minimum)
                    }
                }
                if (isReady(app)) initialize(app)
            } catch (t: Throwable) {
                NextLogger.e("TTS", "model prepare failed", t)
            } finally {
                downloading = false
            }
        }
    }

    fun speak(
        context: Context,
        text: String,
        sid: Int,
        speed: Float,
        onPlaybackStart: ((Long, String) -> Unit)? = null,
        onExpired: (() -> Unit)? = null,
        maxStartDelayMs: Long = 1200L
    ): Boolean {
        val clean = text.trim().replace(Regex("\\s+"), " ").take(240)
        if (clean.isBlank()) return true
        val app = context.applicationContext
        if (!isReady(app)) {
            ensureModelAsync(app)
            return false
        }
        val file = cacheFile(app, clean, sid, speed)
        if (file.exists() && file.length() > 44) {
            scope.launch {
                AudioPlaybackGate.serial {
                    AudioPlaybackGate.serial {
                        playWav(file, onPlaybackStart)
                    }
                }
            }
            return true
        }
        if (!inFlight.add(file.name)) return true
        val requestedAt = System.currentTimeMillis()
        scope.launch {
            try {
                synthesize(app, clean, sid, speed, file)
                val elapsed = System.currentTimeMillis() - requestedAt
                if (elapsed > maxStartDelayMs) {
                    NextLogger.w("TTS", "late TTS dropped elapsedMs=" + elapsed + " text=" + clean)
                    onExpired?.invoke()
                } else {
                    playWav(file, onPlaybackStart)
                }
            } catch (t: Throwable) {
                NextLogger.e("TTS", "synthesis/play failed", t)
            } finally {
                inFlight.remove(file.name)
            }
        }
        return true
    }

    fun warmupAsync(
        context: Context,
        sid: Int,
        speed: Float,
        phrases: List<String>
    ) {
        val app = context.applicationContext
        ensureModelAsync(app)
        scope.launch {
            var tries = 0
            while (!isReady(app) && tries < 240) {
                delay(500L)
                tries++
            }
            if (!isReady(app)) {
                NextLogger.w("TTS", "warmup skipped: model not ready")
                return@launch
            }
            runCatching { initialize(app) }
                .onFailure { NextLogger.e("TTS", "warmup init failed", it) }
                .getOrNull() ?: return@launch
            phrases.distinct().forEach { phrase ->
                val clean = phrase.trim().take(120)
                if (clean.isBlank()) return@forEach
                val file = cacheFile(app, clean, sid, speed)
                if (!file.exists() || file.length() <= 44) {
                    runCatching { synthesize(app, clean, sid, speed, file) }
                        .onFailure { NextLogger.e("TTS", "precache failed " + clean, it) }
                }
            }
            NextLogger.i("TTS", "warmup/precache complete count=" + phrases.distinct().size)
        }
    }

    private fun initialize(context: Context): OfflineTts {
        engine?.let { return it }
        synchronized(engineLock) {
            engine?.let { return it }
            val dir = modelDir(context)
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
                NextLogger.i("TTS", "Supertonic initialized speakers=" + it.numSpeakers())
            }
        }
    }

    private fun synthesize(context: Context, text: String, sid: Int, speed: Float, file: File) {
        val output = initialize(context).generateWithConfig(
            text,
            GenerationConfig(
                sid = sid.coerceIn(0, 9),
                speed = speed.coerceIn(0.75f, 1.35f),
                numSteps = 8,
                extra = mapOf("lang" to "ko")
            )
        )
        require(output.samples.isNotEmpty())
        writeWav(file, output.samples, output.sampleRate)
    }

    private fun buildAttributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
        val method = builder.javaClass.methods.firstOrNull {
            it.name == "setLegacyStreamType" && it.parameterTypes.size == 1
        } ?: builder.javaClass.declaredMethods.first { it.name == "setLegacyStreamType" && it.parameterTypes.size == 1 }
        runCatching { method.isAccessible = true }
        method.invoke(builder, DRIVER_STREAM)
        return builder.build()
    }

    private fun playWav(file: File, onPlaybackStart: ((Long, String) -> Unit)? = null) {
        val bytes = file.readBytes()
        require(bytes.size > 44)
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val rate = header.getInt(24)
        val pcm = bytes.copyOfRange(44, bytes.size)
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(buildAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setVolume(0.84f)
            track.play()
            Thread.sleep(45L)
            val route = runCatching {
                val device = track.routedDevice
                if (device == null) "STREAM14 / ROUTE UNKNOWN"
                else "STREAM14 / " + device.productName + " / type=" + device.type
            }.getOrDefault("STREAM14 / ROUTE UNKNOWN")
            val headFrames = runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(0L)
            if (headFrames > 0L) {
                onPlaybackStart?.invoke(
                    System.currentTimeMillis(),
                    route + " / frames=" + headFrames
                )
            } else {
                NextLogger.w("TTS", "AudioTrack PLAY but playbackHeadPosition=0 route=" + route)
            }
            val durationMs = (pcm.size / 2.0 / rate * 1000.0).toLong()
            Thread.sleep((durationMs - 45L).coerceAtLeast(0L) + 100L)
            runCatching { track.stop() }
        } finally {
            runCatching { track?.release() }
        }
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach {
            val s = (it.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
            pcm.putShort(s.toShort())
        }
        val data = pcm.array()
        FileOutputStream(file).use { out ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII))
            h.putInt(36 + data.size)
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII))
            h.putInt(16)
            h.putShort(1)
            h.putShort(1)
            h.putInt(sampleRate)
            h.putInt(sampleRate * 2)
            h.putShort(2)
            h.putShort(16)
            h.put("data".toByteArray(Charsets.US_ASCII))
            h.putInt(data.size)
            out.write(h.array())
            out.write(data)
        }
    }

    private fun cacheFile(context: Context, text: String, sid: Int, speed: Float): File {
        val key = text + "|sid=" + sid + "|speed=" + speed
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir(context), digest + ".wav")
    }

    private fun download(url: String, dst: File, minimum: Long) {
        val tmp = File(dst.parentFile, dst.name + ".part")
        if (tmp.exists()) tmp.delete()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 600_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "DolphinAssistant-Next")
        try {
            require(conn.responseCode in 200..299)
            conn.inputStream.use { input ->
                tmp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            require(tmp.length() >= minimum)
            if (dst.exists()) dst.delete()
            require(tmp.renameTo(dst))
        } finally {
            conn.disconnect()
        }
    }
}
