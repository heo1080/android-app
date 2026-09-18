package com.byd.dolphin.autoassistant.next.diagnostics

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.next.core.NextLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single diagnostics uploader for DolphinAssistant Next.
 *
 * Deliberately reuses the v30.5.1 prefs and Android Keystore alias so a normal
 * package update keeps the user's existing private GitHub diagnostics setup.
 */
object DiagnosticUploadManager {
    private const val PREF_NAME = "dolphin_diag_upload_v1"
    private const val KEY_AUTO = "auto_upload"
    private const val KEY_OWNER = "owner"
    private const val KEY_REPO = "repo"
    private const val KEY_BRANCH = "branch"
    private const val KEY_TOKEN_CIPHER = "token_cipher"
    private const val KEY_TOKEN_IV = "token_iv"
    private const val KEYSTORE_ALIAS = "dolphin_diag_upload_token_v1"
    private const val MAX_SINGLE_REMOTE_FILE_BYTES = 900 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 25_000

    data class UploadConfig(
        val enabled: Boolean,
        val owner: String,
        val repo: String,
        val branch: String,
        val tokenPresent: Boolean
    ) {
        val complete: Boolean
            get() = owner.isNotBlank() && repo.isNotBlank() &&
                branch.isNotBlank() && tokenPresent
    }

    data class UploadResult(
        val success: Boolean,
        val message: String,
        val sessionId: String? = null,
        val remoteFolder: String? = null,
        val uploadedFiles: Int = 0
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getConfig(context: Context): UploadConfig {
        val p = prefs(context)
        return UploadConfig(
            enabled = p.getBoolean(KEY_AUTO, false),
            owner = p.getString(KEY_OWNER, "heo1080").orEmpty().trim(),
            repo = p.getString(KEY_REPO, "dolphin-diagnostics").orEmpty().trim(),
            branch = p.getString(KEY_BRANCH, "main").orEmpty().trim().ifBlank { "main" },
            tokenPresent = readToken(context).isNotBlank()
        )
    }

    fun enableAutomaticallyWhenConfigured(context: Context): UploadConfig {
        val current = getConfig(context)
        if (!current.enabled && current.complete) {
            prefs(context).edit().putBoolean(KEY_AUTO, true).apply()
            NextLogger.i(
                "DIAG_UPLOAD",
                "auto upload re-enabled from preserved private-repo credentials"
            )
        }
        return getConfig(context)
    }

    fun uploadDiagnosticBundle(context: Context, zipFile: File): UploadResult {
        val config = getConfig(context)
        val token = readToken(context)
        if (!config.enabled) return UploadResult(false, "AUTO UPLOAD OFF")
        if (!config.complete || token.isBlank()) {
            return UploadResult(false, "GITHUB LOG UPLOAD NOT CONFIGURED")
        }
        if (!zipFile.exists() || zipFile.length() <= 0L) {
            return UploadResult(false, "DIAGNOSTIC ZIP MISSING")
        }

        val diagnosticsMode = if (zipFile.name.startsWith("FULL_TEST_")) {
            "FULL_TEST_SESSION"
        } else {
            "FAILURES_ONLY"
        }
        val sessionId = zipFile.name
            .removePrefix("FAIL_ONLY_")
            .removePrefix("FULL_TEST_")
            .removeSuffix(".zip")
            .ifBlank { System.currentTimeMillis().toString() }
        val remoteFolder = "diagnostics/sessions/" + sessionId

        return try {
            val repoInfo = requestJson(
                method = "GET",
                url = "https://api.github.com/repos/" +
                    encode(config.owner) + "/" + encode(config.repo),
                token = token
            )
            if (!repoInfo.optBoolean("private", false)) {
                return UploadResult(
                    false,
                    "SAFETY BLOCK: DIAGNOSTICS REPO MUST BE PRIVATE",
                    sessionId
                )
            }

            val uploadedPaths = mutableListOf<String>()
            ZipInputStream(FileInputStream(zipFile)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val cleanEntry = sanitizeEntryName(entry.name)
                        if (cleanEntry.isNotBlank()) {
                            val bytes = readZipEntry(zin)
                            if (bytes.size <= MAX_SINGLE_REMOTE_FILE_BYTES) {
                                val remotePath = remoteFolder + "/" + cleanEntry
                                uploadBytes(config, token, remotePath, bytes, sessionId)
                                uploadedPaths += remotePath
                            } else {
                                var offset = 0
                                var part = 1
                                while (offset < bytes.size) {
                                    val end = minOf(
                                        offset + MAX_SINGLE_REMOTE_FILE_BYTES,
                                        bytes.size
                                    )
                                    val partBytes = bytes.copyOfRange(offset, end)
                                    val partName = "%s.part%03d".format(cleanEntry, part)
                                    val remotePath = remoteFolder + "/" + partName
                                    uploadBytes(
                                        config,
                                        token,
                                        remotePath,
                                        partBytes,
                                        sessionId
                                    )
                                    uploadedPaths += remotePath
                                    offset = end
                                    part++
                                }
                            }
                        }
                    }
                    zin.closeEntry()
                }
            }

            val sha256 = sha256(zipFile)
            val manifest = JSONObject().apply {
                put("sessionId", sessionId)
                put("uploadedAt", Instant.now().toString())
                put("appVersion", BuildConfig.VERSION_NAME)
                put("appVersionCode", BuildConfig.VERSION_CODE)
                put("sourceZipName", zipFile.name)
                put("sourceZipBytes", zipFile.length())
                put("sourceZipSha256", sha256)
                put("folder", remoteFolder)
                put("files", JSONArray(uploadedPaths))
                put("privacy", "PRIVATE_REPOSITORY_REQUIRED")
                put("diagnosticsMode", diagnosticsMode)
            }.toString(2).toByteArray(Charsets.UTF_8)

            val manifestPath = remoteFolder + "/upload_manifest.json"
            uploadBytes(config, token, manifestPath, manifest, sessionId)
            uploadedPaths += manifestPath

            val latest = JSONObject().apply {
                put("sessionId", sessionId)
                put("uploadedAt", Instant.now().toString())
                put("folder", remoteFolder)
                put("manifest", manifestPath)
                put("appVersion", BuildConfig.VERSION_NAME)
                put("sourceZipSha256", sha256)
                put("diagnosticsMode", "FAILURES_ONLY")
            }.toString(2).toByteArray(Charsets.UTF_8)

            uploadBytes(
                config,
                token,
                "diagnostics/latest.json",
                latest,
                sessionId,
                allowReplace = true
            )

            NextLogger.i(
                "DIAG_UPLOAD",
                "complete session=" + sessionId +
                    " files=" + uploadedPaths.size +
                    " repo=" + config.owner + "/" + config.repo
            )
            UploadResult(
                success = true,
                message = "GITHUB AUTO UPLOAD COMPLETE",
                sessionId = sessionId,
                remoteFolder = remoteFolder,
                uploadedFiles = uploadedPaths.size
            )
        } catch (t: Throwable) {
            NextLogger.e("DIAG_UPLOAD", "upload failed session=" + sessionId, t)
            UploadResult(
                false,
                "AUTO UPLOAD FAILED: " + safeError(t),
                sessionId,
                remoteFolder
            )
        }
    }

    private fun uploadBytes(
        config: UploadConfig,
        token: String,
        path: String,
        bytes: ByteArray,
        sessionId: String,
        allowReplace: Boolean = false
    ) {
        val encodedPath = path.split('/').joinToString("/") { encode(it) }
        val endpoint = "https://api.github.com/repos/" +
            encode(config.owner) + "/" + encode(config.repo) +
            "/contents/" + encodedPath
        val body = JSONObject().apply {
            put(
                "message",
                "BYD diagnostic " + sessionId + " · " +
                    path.substringAfterLast('/')
            )
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("branch", config.branch)
            if (allowReplace) {
                readRemoteSha(endpoint, token, config.branch)?.let {
                    put("sha", it)
                }
            }
        }
        requestJson("PUT", endpoint, token, body)
    }

    private fun readRemoteSha(
        endpoint: String,
        token: String,
        branch: String
    ): String? {
        val separator = if (endpoint.contains('?')) '&' else '?'
        val url = endpoint + separator + "ref=" + encode(branch)
        return try {
            requestJson("GET", url, token)
                .optString("sha")
                .takeIf { it.isNotBlank() }
        } catch (e: HttpStatusException) {
            if (e.statusCode == 404) null else throw e
        }
    }

    private fun requestJson(
        method: String,
        url: String,
        token: String,
        body: JSONObject? = null
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Authorization", "Bearer " + token)
            connection.setRequestProperty(
                "X-GitHub-Api-Version",
                "2022-11-28"
            )
            connection.setRequestProperty(
                "User-Agent",
                "DolphinAssistant/" + BuildConfig.VERSION_NAME
            )
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw HttpStatusException(
                    status,
                    extractGitHubMessage(text)
                )
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun readZipEntry(zin: ZipInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = zin.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sanitizeEntryName(name: String): String = name
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() && it != "." && it != ".." }
        .joinToString("/") { segment ->
            segment.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            "%02x".format(it)
        }
    }

    private fun encode(value: String): String = Uri.encode(value)

    private fun extractGitHubMessage(body: String): String = runCatching {
        JSONObject(body).optString("message", body.take(300))
    }.getOrDefault(body.take(300)).ifBlank { "GitHub API error" }

    private fun safeError(t: Throwable): String = when (t) {
        is HttpStatusException ->
            "GitHub " + t.statusCode + ": " + t.message
        else ->
            t.javaClass.simpleName + ": " +
                t.message.orEmpty().take(180)
    }

    private class HttpStatusException(
        val statusCode: Int,
        message: String
    ) : RuntimeException(message)

    private fun readToken(context: Context): String {
        val p = prefs(context)
        val encryptedText = p.getString(KEY_TOKEN_CIPHER, null) ?: return ""
        val ivText = p.getString(KEY_TOKEN_IV, null) ?: return ""
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
            }
            val key = keyStore.getKey(
                KEYSTORE_ALIAS,
                null
            ) as? SecretKey ?: return@runCatching ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(
                    128,
                    Base64.decode(ivText, Base64.NO_WRAP)
                )
            )
            String(
                cipher.doFinal(
                    Base64.decode(
                        encryptedText,
                        Base64.NO_WRAP
                    )
                ),
                Charsets.UTF_8
            )
        }.onFailure {
            NextLogger.e(
                "DIAG_UPLOAD",
                "encrypted token read failed",
                it
            )
        }.getOrDefault("")
    }

    @Suppress("unused")
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let {
            return it
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
