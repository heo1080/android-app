package com.byd.dolphin.autoassistant.manager

import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v30.5.2 self-update client.
 *
 * Distribution channel:
 *   https://github.com/heo1080/android-app/releases/latest
 *
 * Safety gates before Android's package installer is opened:
 *  1) only the public heo1080/android-app latest Release API is accepted
 *  2) APK and .sha256 assets must both exist
 *  3) downloaded APK SHA-256 must match the release hash asset
 *  4) packageName must equal the currently installed package
 *  5) signing certificate set must exactly match the currently installed app
 *
 * This manager never performs silent installation. Android's normal package installer UI
 * remains the final confirmation step, as requested.
 */
object AppUpdateManager {

    private const val TAG = "APP_UPDATE"
    private const val RELEASE_API = "https://api.github.com/repos/heo1080/android-app/releases/latest"
    private const val REQUEST_INSTALL_PERMISSION_ACTION = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
    private val versionRegex = Regex("v(\\d+(?:\\.\\d+)+)", RegexOption.IGNORE_CASE)

    data class ReleaseInfo(
        val tagName: String,
        val apkName: String,
        val apkUrl: String,
        val shaUrl: String
    )

    fun checkForUpdates(activity: AppCompatActivity, manual: Boolean) {
        activity.lifecycleScope.launch {
            if (!manual) delay(1_200L)

            val result = withContext(Dispatchers.IO) {
                runCatching { fetchLatestRelease() }
            }

            val release = result.getOrElse { error ->
                DolphinLogger.e(TAG, "update check failed", error)
                if (manual && !activity.isFinishing) {
                    AlertDialog.Builder(activity)
                        .setTitle("업데이트 확인 실패")
                        .setMessage("GitHub에서 최신 버전을 확인하지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도하세요.\n\n${error.message ?: error.javaClass.simpleName}")
                        .setPositiveButton("확인", null)
                        .show()
                }
                return@launch
            }

            val latest = parseProductVersion(release.tagName)
            val current = parseProductVersion(BuildConfig.VERSION_NAME)
            if (latest == null || current == null) {
                DolphinLogger.w(TAG, "version parse failed current=${BuildConfig.VERSION_NAME} latest=${release.tagName}")
                if (manual && !activity.isFinishing) {
                    AlertDialog.Builder(activity)
                        .setTitle("버전 확인 오류")
                        .setMessage("버전 형식을 읽지 못했습니다.\n현재: ${BuildConfig.VERSION_NAME}\n서버: ${release.tagName}")
                        .setPositiveButton("확인", null)
                        .show()
                }
                return@launch
            }

            if (compareVersions(latest, current) <= 0) {
                DolphinLogger.i(TAG, "already latest current=$current latest=$latest")
                if (manual && !activity.isFinishing) {
                    AlertDialog.Builder(activity)
                        .setTitle("최신 버전입니다")
                        .setMessage("현재 버전: ${BuildConfig.VERSION_NAME}\n최신 Release: ${release.tagName}")
                        .setPositiveButton("확인", null)
                        .show()
                }
                return@launch
            }

            if (activity.isFinishing) return@launch
            showUpdateAvailableDialog(activity, release)
        }
    }

    private fun showUpdateAvailableDialog(activity: AppCompatActivity, release: ReleaseInfo) {
        AlertDialog.Builder(activity)
            .setTitle("새 버전이 있습니다")
            .setMessage(
                "현재: ${BuildConfig.VERSION_NAME}\n" +
                    "새 버전: ${release.tagName}\n\n" +
                    "업데이트를 누르면 차량에서 APK를 직접 내려받고, 무결성과 앱 서명을 확인한 뒤 설치 화면을 엽니다."
            )
            .setNegativeButton("나중에", null)
            .setPositiveButton("업데이트") { _, _ ->
                beginUpdate(activity, release)
            }
            .show()
    }

    private fun beginUpdate(activity: AppCompatActivity, release: ReleaseInfo) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("설치 권한을 한 번만 허용해주세요")
                .setMessage(
                    "DolphinAssistant가 다운로드한 업데이트 APK를 Android 설치 화면으로 넘기려면 " +
                        "'이 출처 허용' 권한이 필요합니다.\n\n" +
                        "다음 화면에서 DolphinAssistant를 허용한 뒤 앱으로 돌아와 '업데이트 확인'을 다시 누르세요."
                )
                .setNegativeButton("취소", null)
                .setPositiveButton("설정 열기") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(
                                REQUEST_INSTALL_PERMISSION_ACTION,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    }.onFailure {
                        DolphinLogger.e(TAG, "unknown-source settings open failed", it)
                        Toast.makeText(activity, "설치 권한 설정 화면을 열지 못했습니다.", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
            return
        }

        val progress = ProgressDialog(activity).apply {
            setTitle("DolphinAssistant 업데이트")
            setMessage("새 APK를 다운로드하고 검증하는 중입니다…")
            setCancelable(false)
            setIndeterminate(true)
            show()
        }

        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { downloadAndVerify(activity, release) }
            }
            progress.dismiss()

            val apk = result.getOrElse { error ->
                DolphinLogger.e(TAG, "update download/verify failed", error)
                if (!activity.isFinishing) {
                    AlertDialog.Builder(activity)
                        .setTitle("업데이트 실패")
                        .setMessage("APK 다운로드 또는 검증에 실패했습니다.\n\n${error.message ?: error.javaClass.simpleName}")
                        .setPositiveButton("확인", null)
                        .show()
                }
                return@launch
            }

            openPackageInstaller(activity, apk)
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val json = httpGetText(RELEASE_API)
        val root = JSONObject(json)
        val tag = root.getString("tag_name")
        val assets = root.getJSONArray("assets")

        var apkName: String? = null
        var apkUrl: String? = null
        var shaUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith("-stable.apk", ignoreCase = true)) {
                apkName = name
                apkUrl = url
            } else if (name.endsWith("-stable.sha256", ignoreCase = true)) {
                shaUrl = url
            }
        }

        require(!apkName.isNullOrBlank() && !apkUrl.isNullOrBlank()) {
            "latest Release에 stable APK가 없습니다."
        }
        require(!shaUrl.isNullOrBlank()) {
            "latest Release에 SHA-256 파일이 없습니다."
        }

        return ReleaseInfo(
            tagName = tag,
            apkName = apkName!!,
            apkUrl = apkUrl!!,
            shaUrl = shaUrl!!
        )
    }

    private fun downloadAndVerify(activity: AppCompatActivity, release: ReleaseInfo): File {
        val expectedHashText = httpGetText(release.shaUrl)
        val expectedHash = Regex("(?i)\\b[0-9a-f]{64}\\b")
            .find(expectedHashText)?.value?.lowercase()
            ?: error("SHA-256 파일 형식이 올바르지 않습니다.")

        val updateDir = File(activity.filesDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDir, release.apkName)
        downloadToFile(release.apkUrl, apkFile)

        val actualHash = sha256(apkFile)
        require(actualHash.equals(expectedHash, ignoreCase = true)) {
            "APK SHA-256 불일치: 다운로드 파일을 설치하지 않았습니다."
        }

        verifyPackageAndSigner(activity, apkFile)
        DolphinLogger.i(TAG, "update verified tag=${release.tagName} file=${apkFile.name} sha256=$actualHash")
        return apkFile
    }

    @Suppress("DEPRECATION")
    private fun verifyPackageAndSigner(activity: AppCompatActivity, apkFile: File) {
        val pm = activity.packageManager
        val archive = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: error("다운로드 APK의 패키지 정보를 읽을 수 없습니다.")

        require(archive.packageName == activity.packageName) {
            "패키지명이 다릅니다: ${archive.packageName}"
        }

        val installed = pm.getPackageInfo(
            activity.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )

        val installedDigests = installed.signingInfo?.apkContentsSigners
            ?.map { signature -> sha256(signature.toByteArray()) }
            ?.toSet()
            .orEmpty()
        val archiveDigests = archive.signingInfo?.apkContentsSigners
            ?.map { signature -> sha256(signature.toByteArray()) }
            ?.toSet()
            .orEmpty()

        require(installedDigests.isNotEmpty()) { "현재 앱의 서명 인증서를 읽지 못했습니다." }
        require(archiveDigests == installedDigests) {
            "다운로드 APK의 서명이 현재 설치된 DolphinAssistant와 다릅니다."
        }
    }

    private fun openPackageInstaller(activity: AppCompatActivity, apkFile: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        }.onFailure { error ->
            DolphinLogger.e(TAG, "package installer open failed", error)
            AlertDialog.Builder(activity)
                .setTitle("설치 화면 열기 실패")
                .setMessage(error.message ?: error.javaClass.simpleName)
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private fun parseProductVersion(text: String): List<Int>? {
        val match = versionRegex.find(text) ?: return null
        return match.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }
            .takeIf { it.isNotEmpty() }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun httpGetText(url: String): String {
        val connection = openConnection(url)
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToFile(url: String, file: File) {
        val connection = openConnection(url)
        try {
            connection.inputStream.use { input ->
                file.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        require(file.isFile && file.length() > 0L) { "다운로드된 APK가 비어 있습니다." }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DolphinAssistant/${BuildConfig.VERSION_NAME}")
            connect()
            if (responseCode !in 200..299) {
                val errorText = runCatching { errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                disconnect()
                error("GitHub HTTP $responseCode ${errorText.orEmpty().take(200)}")
            }
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
