package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v30.6 natural-voice layer.
 *
 * The cloud gateway is used only to synthesize/cache a phrase. Driving-time
 * playback never depends on the network: a cached PCM WAV is played directly on
 * the already verified BYD legacy stream 14 driver-only DSP path. When a phrase
 * is not cached yet the caller can immediately fall back to the existing local
 * Android/Sherpa TTS so a warning is never lost.
 *
 * Google credentials are intentionally NOT stored in the vehicle. The car only
 * stores an app-specific gateway token encrypted by Android Keystore.
 */
object NaturalVoiceCacheManager {
    private const val TAG = "NATURAL_TTS"
    private const val PREF = "dolphin_natural_voice_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_GATEWAY = "gateway"
    private const val KEY_VOICE = "voice"
    private const val KEY_TOKEN_CIPHER = "token_cipher"
    private const val KEY_TOKEN_IV = "token_iv"
    private const val KEYSTORE_ALIAS = "dolphin_natural_voice_gateway_v1"
    private const val DEFAULT_VOICE = "ko-KR-Chirp3-HD-Aoede"
    private const val MAX_TEXT = 300
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    val supportedVoices = listOf(
        "ko-KR-Chirp3-HD-Aoede",
        "ko-KR-Chirp3-HD-Kore",
        "ko-KR-Chirp3-HD-Charon",
        "ko-KR-Chirp3-HD-Fenrir"
    )

    data class Config(
        val enabled: Boolean,
        val gatewayUrl: String,
        val voice: String,
        val tokenPresent: Boolean
    ) {
        val ready: Boolean
            get() = enabled && gatewayUrl.startsWith("https://") && tokenPresent && voice.isNotBlank()
    }

    data class PrepareResult(val success: Boolean, val message: String, val cachedFile: File? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getConfig(context: Context): Config {
        val p = prefs(context)
        return Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            gatewayUrl = p.getString(KEY_GATEWAY, "").orEmpty().trim().trimEnd('/'),
            voice = p.getString(KEY_VOICE, DEFAULT_VOICE).orEmpty().ifBlank { DEFAULT_VOICE },
            tokenPresent = readToken(context).isNotBlank()
        )
    }

    fun saveConfig(
        context: Context,
        enabled: Boolean,
        gatewayUrl: String,
        voice: String,
        newToken: String?
    ): Boolean {
        val gateway = gatewayUrl.trim().trimEnd('/')
        if (gateway.isNotBlank() && !gateway.startsWith("https://")) return false
        if (voice !in supportedVoices) return false
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_GATEWAY, gateway)
            .putString(KEY_VOICE, voice)
            .apply()
        if (!newToken.isNullOrBlank()) saveToken(context, newToken.trim())
        DolphinLogger.i(TAG, "config saved enabled=$enabled gateway=${gateway.take(80)} voice=$voice tokenChanged=${!newToken.isNullOrBlank()}")
        return true
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun statusSummary(context: Context): String {
        val c = getConfig(context)
        val cache = cacheDir(context)
        val count = cache.listFiles()?.count { it.isFile && it.extension.equals("wav", true) } ?: 0
        return "enabled=${c.enabled} ready=${c.ready} voice=${c.voice} gateway=${if (c.gatewayUrl.isBlank()) "<none>" else c.gatewayUrl} token=${c.tokenPresent} cached=$count"
    }

    /**
     * Returns true only when a cached natural voice was accepted for direct playback.
     * A cache miss starts a non-blocking synthesis job and returns false, allowing
     * VoiceAndSoundManager to use its local TTS fallback immediately.
     */
    fun playCachedOrPrepare(context: Context, text: String): Boolean {
        val clean = normalize(text) ?: return false
        val config = getConfig(context)
        if (!config.enabled) return false
        val file = cacheFile(context, config.voice, clean)
        if (file.exists() && file.length() > 44L) {
            scope.launch { playWavOnDriverStream14(context.applicationContext, file, clean.length) }
            return true
        }
        if (config.ready) prepareAsync(context.applicationContext, clean)
        return false
    }

    fun prepareAsync(context: Context, text: String, onDone: ((PrepareResult) -> Unit)? = null) {
        val clean = normalize(text) ?: run {
            onDone?.invoke(PrepareResult(false, "문장이 비어 있거나 너무 깁니다."))
            return
        }
        val config = getConfig(context)
        if (!config.ready) {
            onDone?.invoke(PrepareResult(false, "자연음성 게이트웨이 설정을 먼저 완료하세요."))
            return
        }
        val file = cacheFile(context, config.voice, clean)
        if (file.exists() && file.length() > 44L) {
            onDone?.invoke(PrepareResult(true, "이미 캐시되어 있습니다.", file))
            return
        }
        val key = file.name
        if (!inFlight.add(key)) {
            onDone?.invoke(PrepareResult(false, "같은 문장을 이미 생성 중입니다."))
            return
        }
        scope.launch {
            val result = try {
                synthesizeToCache(context.applicationContext, config, clean, file)
            } finally {
                inFlight.remove(key)
            }
            onDone?.invoke(result)
        }
    }

    fun prepareAndPlayAsync(context: Context, text: String, onDone: ((PrepareResult) -> Unit)? = null) {
        val clean = normalize(text) ?: run {
            onDone?.invoke(PrepareResult(false, "문장을 확인하세요."))
            return
        }
        val config = getConfig(context)
        val file = cacheFile(context, config.voice, clean)
        if (file.exists() && file.length() > 44L) {
            scope.launch {
                val ok = playWavOnDriverStream14(context.applicationContext, file, clean.length)
                onDone?.invoke(PrepareResult(ok, if (ok) "캐시 음성을 재생했습니다." else "캐시 재생에 실패했습니다.", file))
            }
            return
        }
        prepareAsync(context, clean) { result ->
            if (!result.success || result.cachedFile == null) {
                onDone?.invoke(result)
            } else {
                scope.launch {
                    val ok = playWavOnDriverStream14(context.applicationContext, result.cachedFile, clean.length)
                    onDone?.invoke(PrepareResult(ok, if (ok) "생성 후 운전석에서 재생했습니다." else "생성은 성공했지만 재생에 실패했습니다.", result.cachedFile))
                }
            }
        }
    }

    fun prewarmDefaults(context: Context) {
        if (!getConfig(context).ready) return
        val phrases = linkedSetOf(
            SettingsManager.getGearPhrase(context, "P"),
            SettingsManager.getGearPhrase(context, "R"),
            SettingsManager.getGearPhrase(context, "N"),
            SettingsManager.getGearPhrase(context, "D"),
            SettingsManager.getLeadingCarPhrase(context),
            SettingsManager.getChargingStartPhrase(context),
            SettingsManager.getChargingEndPhrase(context),
            SettingsManager.getBsdCustomText(context),
            SettingsManager.getLdpCustomText(context)
        ).filter { it.isNotBlank() }
        DolphinLogger.i(TAG, "prewarm scheduled phrases=${phrases.size}")
        phrases.forEach { prepareAsync(context.applicationContext, it) }
    }

    fun clearCache(context: Context): Int {
        val files = cacheDir(context).listFiles().orEmpty()
        var deleted = 0
        files.forEach { if (it.isFile && it.delete()) deleted++ }
        DolphinLogger.i(TAG, "cache cleared files=$deleted")
        return deleted
    }

    fun writeStatusSnapshot(context: Context, directory: File) {
        runCatching {
            File(directory, "natural_voice_status.txt").writeText(
                buildString {
                    appendLine("DolphinAssistant v30.6 natural voice status")
                    appendLine(statusSummary(context))
                    appendLine("driverPlayback=LEGACY_STREAM_14_CACHED_PCM")
                    appendLine("cloudCredentialsStoredInVehicle=false")
                    appendLine("cacheDirectory=${cacheDir(context).name}")
                }
            )
        }.onFailure { DolphinLogger.e(TAG, "status snapshot failed", it) }
    }

    private fun synthesizeToCache(context: Context, config: Config, text: String, destination: File): PrepareResult {
        val token = readToken(context)
        if (token.isBlank()) return PrepareResult(false, "게이트웨이 토큰이 없습니다.")
        val endpoint = "${config.gatewayUrl}/synthesize"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "audio/wav")
            connection.setRequestProperty("X-Dolphin-Token", token)
            val body = JSONObject().apply {
                put("text", text)
                put("voice", config.voice)
            }.toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(240)
                throw IllegalStateException("gateway HTTP $status $error")
            }
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            require(bytes.size > 44) { "empty WAV response" }
            require(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF") { "response is not RIFF WAV" }
            val tmp = File(destination.parentFile, destination.name + ".tmp")
            tmp.writeBytes(bytes)
            if (destination.exists()) destination.delete()
            require(tmp.renameTo(destination)) { "cache rename failed" }
            DolphinLogger.i(TAG, "cache prepared voice=${config.voice} textLen=${text.length} bytes=${bytes.size} key=${destination.nameWithoutExtension.take(12)}")
            PrepareResult(true, "자연음성 캐시 생성 완료", destination)
        } catch (t: Throwable) {
            DolphinLogger.e(TAG, "synthesis failed voice=${config.voice} textLen=${text.length}", t)
            PrepareResult(false, "자연음성 생성 실패: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(160)}")
        } finally {
            connection.disconnect()
        }
    }

    private data class WavPcm(val sampleRate: Int, val channels: Int, val pcm16: ByteArray)

    private fun parsePcm16Wav(file: File): WavPcm {
        val bytes = file.readBytes()
        require(bytes.size >= 44)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        var offset = 12
        var audioFormat = -1
        var channels = -1
        var sampleRate = -1
        var bits = -1
        var data: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = leInt(bytes, offset + 4).coerceAtLeast(0)
            val start = offset + 8
            if (start + size > bytes.size) break
            when (id) {
                "fmt " -> if (size >= 16) {
                    audioFormat = leShort(bytes, start)
                    channels = leShort(bytes, start + 2)
                    sampleRate = leInt(bytes, start + 4)
                    bits = leShort(bytes, start + 14)
                }
                "data" -> data = bytes.copyOfRange(start, start + size)
            }
            offset = start + size + (size and 1)
        }
        require(audioFormat == 1) { "WAV must be PCM, format=$audioFormat" }
        require(bits == 16) { "WAV must be 16-bit, bits=$bits" }
        require(channels in 1..2) { "unsupported channels=$channels" }
        require(sampleRate in 8_000..96_000) { "unsupported sampleRate=$sampleRate" }
        val pcm = requireNotNull(data) { "WAV data chunk missing" }
        return WavPcm(sampleRate, channels, pcm)
    }

    private fun playWavOnDriverStream14(context: Context, file: File, textLength: Int): Boolean {
        val wav = try { parsePcm16Wav(file) } catch (t: Throwable) {
            DolphinLogger.e(TAG, "cache parse failed file=${file.name}", t)
            return false
        }
        val attrs = buildStream14Attributes() ?: return false
        val channelMask = if (wav.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        var track: AudioTrack? = null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var focusRequest: AudioFocusRequest? = null
        return try {
            val focus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build().also { focusRequest = it }
                    .let(audioManager::requestAudioFocus)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
            val minBuffer = AudioTrack.getMinBufferSize(wav.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(0)
            val built = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(wav.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(maxOf(wav.pcm16.size, minBuffer))
                .build()
            track = built
            val written = built.write(wav.pcm16, 0, wav.pcm16.size)
            require(written > 0) { "AudioTrack write=$written" }
            built.setVolume(0.82f)
            built.play()
            val frameBytes = wav.channels * 2
            val frames = wav.pcm16.size / frameBytes
            val durationMs = ((frames * 1000L) / wav.sampleRate).coerceAtLeast(50L)
            Thread.sleep(durationMs + 80L)
            runCatching { built.stop() }
            DolphinLogger.i(TAG, "cached stream14 playback OK focus=$focus rate=${wav.sampleRate} ch=${wav.channels} bytes=$written durationMs=$durationMs textLen=$textLength")
            true
        } catch (t: Throwable) {
            DolphinLogger.e(TAG, "cached stream14 playback failed textLen=$textLength", t)
            false
        } finally {
            runCatching { track?.release() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            } else {
                @Suppress("DEPRECATION")
                runCatching { audioManager.abandonAudioFocus(null) }
            }
        }
    }

    private fun buildStream14Attributes(): AudioAttributes? = runCatching {
        val builder = AudioAttributes.Builder()
        val setter = builder.javaClass.methods.firstOrNull {
            it.name == "setLegacyStreamType" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: builder.javaClass.declaredMethods.firstOrNull {
            it.name == "setLegacyStreamType" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("AudioAttributes.Builder.setLegacyStreamType(int)")
        runCatching { setter.isAccessible = true }
        setter.invoke(builder, 14)
        builder.build()
    }.onFailure { DolphinLogger.e(TAG, "stream14 attributes unavailable", it.cause ?: it) }.getOrNull()

    private fun cacheDir(context: Context): File = File(context.filesDir, "natural_voice_cache").apply { mkdirs() }

    private fun cacheFile(context: Context, voice: String, text: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$voice\n$text".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir(context), "$digest.wav")
    }

    private fun normalize(text: String): String? {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        return clean.takeIf { it.isNotBlank() && it.length <= MAX_TEXT }
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun saveToken(context: Context, token: String) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(KEY_TOKEN_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun readToken(context: Context): String {
        val p = prefs(context)
        val encrypted = p.getString(KEY_TOKEN_CIPHER, null) ?: return ""
        val iv = p.getString(KEY_TOKEN_IV, null) ?: return ""
        return runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = store.getKey(KEYSTORE_ALIAS, null) as? SecretKey ?: return@runCatching ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.onFailure { DolphinLogger.e(TAG, "gateway token decrypt failed", it) }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
