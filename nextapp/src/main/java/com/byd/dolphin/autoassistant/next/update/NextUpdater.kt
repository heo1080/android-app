package com.byd.dolphin.autoassistant.next.update

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.next.core.NextLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object NextUpdater {
    private const val API = "https://api.github.com/repos/heo1080/android-app/releases?per_page=30"
    private val versionRegex = Regex("v(\\d+(?:\\.\\d+)+)", RegexOption.IGNORE_CASE)

    data class Release(
        val tag: String,
        val apkName: String,
        val apkUrl: String,
        val shaUrl: String
    )

    fun check(activity: ComponentActivity, manual: Boolean = true) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { latestRelease() } }
            val release = result.getOrElse {
                NextLogger.e("UPDATE", "check failed", it)
                if (manual) AlertDialog.Builder(activity)
                    .setTitle("업데이트 확인 실패")
                    .setMessage(it.message ?: it.javaClass.simpleName)
                    .setPositiveButton("확인", null)
                    .show()
                return@launch
            }

            val remote = parseVersion(release.tag)
            val current = parseVersion(BuildConfig.VERSION_NAME)
            if (remote == null || current == null) {
                if (manual) Toast.makeText(activity, "버전 형식을 읽지 못했습니다.", Toast.LENGTH_LONG).show()
                return@launch
            }

            if (compare(remote, current) <= 0) {
                if (manual) AlertDialog.Builder(activity)
                    .setTitle("최신 버전입니다")
                    .setMessage("현재 " + BuildConfig.VERSION_NAME + "\n최신 " + release.tag)
                    .setPositiveButton("확인", null)
                    .show()
                return@launch
            }

            AlertDialog.Builder(activity)
                .setTitle("새 버전이 있습니다")
                .setMessage("현재 " + BuildConfig.VERSION_NAME + "\n새 버전 " + release.tag)
                .setNegativeButton("나중에", null)
                .setPositiveButton("업데이트") { _, _ -> begin(activity, release) }
                .show()
        }
    }

    private fun begin(activity: ComponentActivity, release: Release) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("업데이트 설치 권한")
                .setMessage("DolphinAssistant가 내려받은 APK를 설치하려면 이 출처 허용이 필요합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("설정 열기") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.packageName))
                    )
                }
                .show()
            return
        }

        val progress = ProgressDialog(activity).apply {
            setTitle("DolphinAssistant Next")
            setMessage("업데이트 다운로드 및 검증 중…")
            setCancelable(false)
            setIndeterminate(true)
            show()
        }

        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { downloadAndVerify(activity, release) }
            }
            progress.dismiss()
            val apk = result.getOrElse {
                NextLogger.e("UPDATE", "download/verify failed", it)
                AlertDialog.Builder(activity)
                    .setTitle("업데이트 실패")
                    .setMessage(it.message ?: it.javaClass.simpleName)
                    .setPositiveButton("확인", null)
                    .show()
                return@launch
            }
            openInstaller(activity, apk)
        }
    }

    private fun latestRelease(): Release {
        val arr = JSONArray(httpText(API))
        var bestVersion: List<Int>? = null
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val root = arr.getJSONObject(i)
            if (root.optBoolean("draft", false)) continue
            val tag = root.optString("tag_name")
            val version = parseVersion(tag) ?: continue
            val release = parseRelease(root) ?: continue
            if (bestVersion == null || compare(version, bestVersion!!) > 0) {
                bestVersion = version
                best = release
            }
        }
        return best ?: error("설치 가능한 DolphinAssistant Release가 없습니다.")
    }

    private fun parseRelease(root: JSONObject): Release? {
        val assets = root.optJSONArray("assets") ?: return null
        var apkName: String? = null
        var apkUrl: String? = null
        val shas = linkedMapOf<String, String>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", true) && apkName == null) {
                apkName = name
                apkUrl = url
            }
            if (name.endsWith(".sha256", true)) shas[name.lowercase()] = url
        }
        val name = apkName ?: return null
        val url = apkUrl ?: return null
        val preferred = name.removeSuffix(".apk").lowercase() + ".sha256"
        val sha = shas[preferred] ?: shas.values.firstOrNull() ?: return null
        return Release(root.optString("tag_name"), name, url, sha)
    }

    private fun downloadAndVerify(activity: ComponentActivity, release: Release): File {
        val expected = Regex("(?i)\\b[0-9a-f]{64}\\b")
            .find(httpText(release.shaUrl))?.value?.lowercase()
            ?: error("SHA-256 형식 오류")
        val dir = File(activity.filesDir, "updates").apply { mkdirs() }
        val file = File(dir, release.apkName)
        download(release.apkUrl, file)
        val actual = sha256(file)
        require(actual == expected) { "APK SHA-256 불일치" }
        verifyPackageAndSigner(activity, file)
        NextLogger.i("UPDATE", "verified " + release.tag + " sha=" + actual)
        return file
    }

    @Suppress("DEPRECATION")
    private fun verifyPackageAndSigner(activity: ComponentActivity, file: File) {
        val pm = activity.packageManager

        val archiveModern = runCatching {
            pm.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        }.getOrNull()

        val archiveLegacy = runCatching {
            pm.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNATURES
            )
        }.getOrNull()

        val archive = archiveModern ?: archiveLegacy
            ?: error("APK 패키지 정보를 읽지 못했습니다.")

        require(archive.packageName == activity.packageName) {
            "패키지명이 다릅니다."
        }

        val installedModern = runCatching {
            pm.getPackageInfo(
                activity.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        }.getOrNull()

        val installedLegacy = runCatching {
            pm.getPackageInfo(
                activity.packageName,
                PackageManager.GET_SIGNATURES
            )
        }.getOrNull()

        val installedDigests = linkedSetOf<String>().apply {
            addAll(signingInfoDigests(installedModern))
            addAll(legacySignatureDigests(installedLegacy))
        }

        val archiveDigests = linkedSetOf<String>().apply {
            addAll(signingInfoDigests(archiveModern))
            addAll(legacySignatureDigests(archiveLegacy))
        }

        NextLogger.i(
            "UPDATE_SIGNER",
            "sdk=" + Build.VERSION.SDK_INT +
                " installed=" + installedDigests.joinToString(",") +
                " archive=" + archiveDigests.joinToString(",")
        )

        require(installedDigests.isNotEmpty()) {
            "현재 앱의 서명 정보를 읽지 못했습니다."
        }
        require(archiveDigests.isNotEmpty()) {
            "업데이트 APK의 서명 정보를 읽지 못했습니다."
        }

        val compatible = installedDigests.intersect(archiveDigests).isNotEmpty()
        require(compatible) {
            "서명이 현재 앱과 다릅니다.\n현재=" +
                installedDigests.joinToString(",") +
                "\n업데이트=" + archiveDigests.joinToString(",")
        }
    }

    @Suppress("DEPRECATION")
    private fun signingInfoDigests(info: android.content.pm.PackageInfo?): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val signing = info?.signingInfo ?: return emptySet()
        val signatures = if (signing.hasPastSigningCertificates()) {
            signing.signingCertificateHistory
        } else {
            signing.apkContentsSigners
        }
        return signatures
            ?.map { sha256(it.toByteArray()) }
            ?.toSet()
            .orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun legacySignatureDigests(info: android.content.pm.PackageInfo?): Set<String> =
        info?.signatures
            ?.map { sha256(it.toByteArray()) }
            ?.toSet()
            .orEmpty()

    private fun openInstaller(activity: ComponentActivity, file: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".fileprovider",
            file
        )
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun parseVersion(text: String): List<Int>? =
        versionRegex.find(text)?.groupValues?.get(1)
            ?.split(".")?.mapNotNull { it.toIntOrNull() }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun httpText(url: String): String {
        val c = connection(url)
        return try { c.inputStream.bufferedReader().use { it.readText() } }
        finally { c.disconnect() }
    }

    private fun download(url: String, file: File) {
        val c = connection(url)
        try {
            c.inputStream.use { input ->
                file.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        } finally { c.disconnect() }
        require(file.length() > 0)
    }

    private fun connection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DolphinAssistant-Next/" + BuildConfig.VERSION_NAME)
            connect()
            if (responseCode !in 200..299) error("GitHub HTTP " + responseCode)
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
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
