package com.byd.dolphin.autoassistant.core.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.core.NextLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateClient(private val activity: Activity) {
    data class Release(
        val tag: String,
        val apkName: String,
        val apkUrl: String,
        val shaUrl: String
    )

    sealed interface CheckResult {
        data class Available(val release: Release) : CheckResult
        data object Latest : CheckResult
        data class Error(val message: String) : CheckResult
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val release = fetchBestRelease()
            val latest = version(release.tag)
            val current = version(BuildConfig.VERSION_NAME)
            if (latest == null || current == null) {
                CheckResult.Error("버전 형식을 읽지 못했습니다.")
            } else if (compare(latest, current) > 0) {
                CheckResult.Available(release)
            } else {
                CheckResult.Latest
            }
        }.getOrElse {
            NextLog.e("UPDATE", "check failed", it)
            CheckResult.Error(it.message ?: it.javaClass.simpleName)
        }
    }

    suspend fun downloadAndVerify(release: Release): File = withContext(Dispatchers.IO) {
        val expectedText = getText(release.shaUrl)
        val expected = Regex("(?i)\\b[0-9a-f]{64}\\b")
            .find(expectedText)?.value?.lowercase()
            ?: error("SHA-256 파일을 읽지 못했습니다.")

        val dir = File(activity.filesDir, "updates").apply { mkdirs() }
        val apk = File(dir, release.apkName)
        download(release.apkUrl, apk)

        val actual = sha256(apk)
        require(actual == expected) { "APK SHA-256이 일치하지 않습니다." }
        verifyPackageAndSigner(apk)
        NextLog.i("UPDATE", "verified " + release.tag + " sha256=" + actual)
        apk
    }

    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.packageManager.canRequestPackageInstalls()
        } else true

    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)
                )
            )
        }
    }

    fun openInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".fileprovider",
            apk
        )
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun fetchBestRelease(): Release {
        val releases = JSONArray(getText(RELEASES_API))
        var bestVersion: List<Int>? = null
        var best: Release? = null

        for (i in 0 until releases.length()) {
            val root = releases.getJSONObject(i)
            if (root.optBoolean("draft", false)) continue
            val tag = root.optString("tag_name")
            val parsed = version(tag) ?: continue
            val candidate = releaseFromJson(root) ?: continue
            if (bestVersion == null || compare(parsed, bestVersion!!) > 0) {
                bestVersion = parsed
                best = candidate
            }
        }
        return best ?: error("설치 가능한 Release를 찾지 못했습니다.")
    }

    private fun releaseFromJson(root: JSONObject): Release? {
        val assets = root.optJSONArray("assets") ?: return null
        var apkName: String? = null
        var apkUrl: String? = null
        val hashes = mutableMapOf<String, String>()

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", true) && apkName == null) {
                apkName = name
                apkUrl = url
            }
            if (name.endsWith(".sha256", true)) hashes[name.lowercase()] = url
        }

        val finalApkName = apkName ?: return null
        val preferred = finalApkName.removeSuffix(".apk").lowercase() + ".sha256"
        val sha = hashes[preferred] ?: hashes.values.firstOrNull() ?: return null
        return Release(
            tag = root.optString("tag_name"),
            apkName = finalApkName,
            apkUrl = apkUrl ?: return null,
            shaUrl = sha
        )
    }

    @Suppress("DEPRECATION")
    private fun verifyPackageAndSigner(apk: File) {
        val pm = activity.packageManager
        val archive = pm.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?: error("다운로드 APK의 패키지 정보를 읽지 못했습니다.")

        require(archive.packageName == activity.packageName) {
            "패키지명이 현재 DolphinAssistant와 다릅니다."
        }

        val installed = runCatching {
            pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrElse {
            pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        }

        val current = signatureDigests(installed)
        val incoming = signatureDigests(archive)
        require(current.isNotEmpty()) { "현재 앱 서명을 읽지 못했습니다." }
        require(current == incoming) { "새 APK 서명이 현재 앱과 다릅니다." }
    }

    @Suppress("DEPRECATION")
    private fun signatureDigests(info: PackageInfo): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }.orEmpty()
        } else emptyList()
        if (modern.isNotEmpty()) return modern.toSet()
        return info.signatures?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
    }

    private fun getText(url: String): String {
        val conn = connection(url)
        return try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, file: File) {
        val conn = connection(url)
        try {
            conn.inputStream.use { input ->
                file.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
        require(file.length() > 0L) { "다운로드 파일이 비어 있습니다." }
    }

    private fun connection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DolphinAssistant-Next/" + BuildConfig.VERSION_NAME)
            connect()
            require(responseCode in 200..299) { "GitHub HTTP " + responseCode }
        }

    private fun version(text: String): List<Int>? =
        Regex("v(\\d+(?:\\.\\d+)+)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
            ?.split(".")?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        val count = maxOf(a.size, b.size)
        for (i in 0 until count) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val RELEASES_API =
            "https://api.github.com/repos/heo1080/android-app/releases?per_page=30"
    }
}
