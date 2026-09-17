package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.byd.dolphin.autoassistant.util.DolphinLogger
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * DolphinAssistant-owned Korean TTS.
 *
 * This deliberately bypasses Android TextToSpeechService. The DiLink 3 vehicle
 * accepted the external sherpa engine package but repeatedly returned TTS init
 * status=-1, so v30.6.2 runs sherpa-onnx inside our own process instead.
 *
 * Supertonic 3 is downloaded once into app-private storage. Generated phrases
 * are cached as PCM16 WAV and played on BYD legacy stream 14, the driver-only
 * route already proven on the target car. No speech request is queued into the
 * broken system TTS service, which also prevents an old utterance from playing
 * later after a preview beep.
 */
object InAppSupertonicTtsManager {
    private const val TAG = "INAPP_TTS"
    private const val MODEL_ID = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    private const val MODEL_BASE =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 10 * 60_000
    private const val STREAM_DRIVER_ONLY = 14

    private val requiredFiles = linkedMapOf(
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
    @Volatile private var lastError: String? = null
    @Volatile private var downloadedBytes: Long = 0L

    private fun modelDir(context: Context) = File(context.filesDir, "supertonic/$MODEL_ID")
    private fun cacheDir(context: Context) = File(context.filesDir, "supertonic-cache").apply { mkdirs() }

    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        return requiredFiles.all { (name, minimum) ->
            File(dir, name).let { it.isFile && it.length() >= minimum }
        }
    }

    fun statusSummary(context: Context): String {
        val ready = isModelReady(context)
        val cached = cacheDir(context).listFiles()?.count { it.extension.equals("wav", true) } ?: 0
        return buildString {
            append("engine=IN_APP_SHERPA_ONNX_SUPERTONIC3_KO")
            append(" modelReady=$ready downloading=$downloading")
            append(" cached=$cached")
            if (downloadedBytes > 0) append(" downloadedMB=${downloadedBytes / 1_048_576}")
            lastError?.let { append(" lastError=${it.take(160)}") }
        }
    }

    /** Starts the one-time 145 MB model preparation without blocking the caller. */
    fun ensureModelAsync(context: Context, onDone: ((Boolean, String) -> Unit)? = null) {
        val app = context.applicationContext
        if (isModelReady(app)) {
            onDone?.invoke(true, "Supertonic 3 한국어 모델 준비 완료")
            return
        }
        synchronized(this) {
            if (downloading) {
                onDone?.invoke(false, "Supertonic 3 모델을 이미 준비 중입니다.")
                return
            }
            downloading = true
        }
        scope.launch {
            var ok = false
            var message = ""
            try {
                val dir = modelDir(app).apply { mkdirs() }
                downloadedBytes = 0L
                requiredFiles.forEach { (name, minimum) ->
                    val dst = File(dir, name)
                    if (dst.isFile && dst.length() >= minimum) return@forEach
                    downloadOne("$MODEL_BASE/$name?download=true", dst, minimum)
                }
                require(isModelReady(app)) { "모델 파일 검증 실패" }
                initializeEngine(app)
                ok = true
                lastError = null
                message = "Supertonic 3 한국어 모델/엔진 준비 완료"
                DolphinLogger.i(TAG, message)
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                message = "인앱 TTS 준비 실패: ${lastError}"
                DolphinLogger.e(TAG, message, t)
            } finally {
                downloading = false
                onDone?.invoke(ok, message)
            }
        }
    }

    private fun downloadOne(url: String, destination: File, minimum: Long) {
        val tmp = File(destination.parentFile, destination.name + ".part")
        if (tmp.exists()) tmp.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "DolphinAssistant-v30.6.2")
        }
        try {
            val code = conn.responseCode
            require(code in 200..299) { "HTTP $code for ${destination.name}" }
            BufferedInputStream(conn.inputStream, 128 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(tmp), 128 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        downloadedBytes += n
                    }
                }
            }
            require(tmp.length() >= minimum) {
                "${destination.name} too small: ${tmp.length()}"
            }
            if (destination.exists()) destination.delete()
            require(tmp.renameTo(destination)) { "rename failed: ${destination.name}" }
            DolphinLogger.i(TAG, "model file ready ${destination.name} bytes=${destination.length()}")
        } finally {
            conn.disconnect()
        }
    }

    private fun initializeEngine(context: Context): OfflineTts {
        engine?.let { return it }
        synchronized(engineLock) {
            engine?.let { return it }
            require(isModelReady(context)) { "Supertonic model is not ready" }
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
                DolphinLogger.i(TAG, "OfflineTts initialized sampleRate=${it.sampleRate()} speakers=${it.numSpeakers()}")
            }
        }
    }

    /**
     * @return true when playback/synthesis was accepted by the in-app engine.
     * false means the model is not ready; callers may emit a short fallback beep.
     */
    fun speak(context: Context, text: String): Boolean {
        val clean = text.trim().replace(Regex("\\s+"), " ").take(240)
        if (clean.isBlank()) return true
        val app = context.applicationContext
        val cache = phraseCacheFile(app, clean)
        if (cache.isFile && cache.length() > 44L) {
            scope.launch { playCachedWav(app, cache) }
            return true
        }
        if (!isModelReady(app)) {
            ensureModelAsync(app)
            DolphinLogger.w(TAG, "model not ready; speech not queued len=${clean.length}")
            return false
        }
        val key = cache.name
        if (!inFlight.add(key)) {
            DolphinLogger.i(TAG, "same phrase synthesis already running key=${key.take(14)}")
            return true
        }
        scope.launch {
            try {
                val ok = synthesizeToCache(app, clean, cache)
                if (ok) playCachedWav(app, cache)
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                DolphinLogger.e(TAG, "synthesis/play failed len=${clean.length}", t)
            } finally {
                inFlight.remove(key)
            }
        }
        return true
    }

    fun prewarmDefaults(context: Context) {
        val app = context.applicationContext
        ensureModelAsync(app) { ok, _ ->
            if (!ok) return@ensureModelAsync
            val phrases = linkedSetOf(
                SettingsManager.getGearPhrase(app, "P"),
                SettingsManager.getGearPhrase(app, "R"),
                SettingsManager.getGearPhrase(app, "N"),
                SettingsManager.getGearPhrase(app, "D"),
                SettingsManager.getDriveModePhrase(app, "ECO"),
                SettingsManager.getDriveModePhrase(app, "NORMAL"),
                SettingsManager.getDriveModePhrase(app, "SPORT"),
                SettingsManager.getRegenModePhrase(app, "STANDARD"),
                SettingsManager.getRegenModePhrase(app, "HIGH"),
                SettingsManager.getSnowModePhrase(app),
                SettingsManager.getAutoHoldSwitchPhrase(app, true),
                SettingsManager.getAutoHoldSwitchPhrase(app, false),
                SettingsManager.getAutoHoldBrakePhrase(app, true),
                SettingsManager.getAutoHoldBrakePhrase(app, false),
                SettingsManager.getEpbPhrase(app, true),
                SettingsManager.getEpbPhrase(app, false),
                SettingsManager.getIccPhrase(app, true),
                SettingsManager.getIccPhrase(app, false),
                SettingsManager.getLeadingCarPhrase(app)
            ).filter { it.isNotBlank() }
            phrases.forEach { phrase ->
                val file = phraseCacheFile(app, phrase)
                if (!file.exists() && inFlight.add(file.name)) {
                    scope.launch {
                        try { synthesizeToCache(app, phrase, file) }
                        catch (t: Throwable) { DolphinLogger.e(TAG, "prewarm failed", t) }
                        finally { inFlight.remove(file.name) }
                    }
                }
            }
            DolphinLogger.i(TAG, "prewarm scheduled phrases=${phrases.size}")
        }
    }

    fun preparePhraseAsync(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val app = context.applicationContext
        if (!isModelReady(app)) {
            ensureModelAsync(app) { ok, _ -> if (ok) preparePhraseAsync(app, clean) }
            return
        }
        val file = phraseCacheFile(app, clean)
        if (file.exists() || !inFlight.add(file.name)) return
        scope.launch {
            try { synthesizeToCache(app, clean, file) }
            catch (t: Throwable) { DolphinLogger.e(TAG, "prepare phrase failed", t) }
            finally { inFlight.remove(file.name) }
        }
    }

    private fun synthesizeToCache(context: Context, text: String, file: File): Boolean {
        if (file.isFile && file.length() > 44L) return true
        val tts = initializeEngine(context)
        val generated = tts.generateWithConfig(
            text,
            GenerationConfig(
                sid = 6,
                speed = 1.05f,
                numSteps = 8,
                extra = mapOf("lang" to "ko")
            )
        )
        require(generated.samples.isNotEmpty()) { "Supertonic returned no audio" }
        writeWav(file, generated.samples, generated.sampleRate)
        DolphinLogger.i(
            TAG,
            "phrase cached textLen=${text.length} samples=${generated.samples.size} rate=${generated.sampleRate} file=${file.name.take(14)}"
        )
        return true
    }

    private fun phraseCacheFile(context: Context, text: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir(context), "$digest.wav")
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { f ->
            val s = (f.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
            pcm.putShort(s.toShort())
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

    private fun readWav(file: File): Pair<Int, ByteArray> {
        FileInputStream(file).use { input ->
            val header = ByteArray(44)
            require(input.read(header) == 44) { "short WAV" }
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "not RIFF" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "not WAVE" }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val channels = bb.getShort(22).toInt()
            val rate = bb.getInt(24)
            val bits = bb.getShort(34).toInt()
            require(channels == 1 && bits == 16 && rate in 8_000..96_000) {
                "unsupported WAV $channels/$bits/$rate"
            }
            return rate to input.readBytes()
        }
    }

    private fun buildStream14Attributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
        val method = builder.javaClass.methods.firstOrNull {
            it.name == "setLegacyStreamType" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: builder.javaClass.declaredMethods.first {
            it.name == "setLegacyStreamType" && it.parameterTypes.size == 1
        }
        runCatching { method.isAccessible = true }
        method.invoke(builder, STREAM_DRIVER_ONLY)
        return builder.build()
    }

    private fun playCachedWav(context: Context, file: File): Boolean {
        val (sampleRate, pcm) = readWav(file)
        val attrs = buildStream14Attributes()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var track: AudioTrack? = null
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(maxOf(minBuffer, pcm.size))
                .build()
            val written = track.write(pcm, 0, pcm.size)
            require(written == pcm.size) { "AudioTrack write=$written/${pcm.size}" }
            track.setVolume(0.84f)
            track.play()
            val durationMs = (pcm.size / 2.0 / sampleRate * 1000.0).toLong().coerceAtLeast(100L)
            Thread.sleep(durationMs + 120L)
            runCatching { track.stop() }
            DolphinLogger.i(TAG, "stream14 voice played rate=$sampleRate bytes=${pcm.size} mode=${am.mode}")
            true
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            DolphinLogger.e(TAG, "stream14 voice playback failed", t)
            false
        } finally {
            runCatching { track?.release() }
        }
    }

    fun clearPhraseCache(context: Context): Int {
        var count = 0
        cacheDir(context).listFiles().orEmpty().forEach { if (it.delete()) count++ }
        return count
    }

    fun release() {
        synchronized(engineLock) {
            runCatching { engine?.release() }
            engine = null
        }
    }
}
