package com.byd.dolphin.autoassistant.update

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val API = "https://api.github.com/repos/heo1080/android-app/releases?per_page=10"

    private data class Release(val tag: String, val name: String, val apkUrl: String)

    fun checkAndPrompt(activity: MainActivity, manual: Boolean) {
        activity.lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { runCatching { latest() }.getOrNull() }
            if (release == null) {
                if (manual) Toast.makeText(activity, "업데이트 정보를 확인하지 못했습니다.", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (compare(parse(release.tag), parse(BuildConfig.VERSION_NAME)) <= 0) {
                if (manual) Toast.makeText(activity, "현재 버전이 최신입니다.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            AlertDialog.Builder(activity)
                .setTitle("DolphinAssistant 업데이트")
                .setMessage(release.name + "\n현재 " + BuildConfig.VERSION_NAME + " → " + release.tag)
                .setNegativeButton("나중에", null)
                .setPositiveButton("업데이트") { _, _ -> downloadAndInstall(activity, release) }
                .show()
        }
    }

    private fun downloadAndInstall(activity: MainActivity, release: Release) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:" + activity.packageName)
                }
            )
            Toast.makeText(activity, "이 앱의 설치 허용 후 업데이트를 다시 눌러주세요.", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(activity, "업데이트 다운로드를 시작합니다.", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(activity.externalCacheDir ?: activity.cacheDir, "updates").apply { mkdirs() }
                    val out = File(dir, "DolphinAssistant-update.apk")
                    val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 10 * 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "DolphinAssistant-Next")
                    }
                    try {
                        require(conn.responseCode in 200..299) { "HTTP " + conn.responseCode }
                        conn.inputStream.use { input ->
                            out.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    require(out.length() > 5_000_000L) { "APK size invalid" }
                    out
                }.getOrNull()
            }

            if (file == null) {
                Toast.makeText(activity, "업데이트 다운로드에 실패했습니다.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val uri = FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileprovider",
                file
            )
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    private fun latest(): Release? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "DolphinAssistant-Next")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            require(conn.responseCode in 200..299) { "HTTP " + conn.responseCode }
            val array = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            (0 until array.length()).asSequence().mapNotNull { index ->
                val obj = array.getJSONObject(index)
                if (obj.optBoolean("draft", false)) return@mapNotNull null
                val assets = obj.optJSONArray("assets") ?: return@mapNotNull null
                val apk = (0 until assets.length()).asSequence()
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                    ?: return@mapNotNull null
                Release(
                    tag = obj.optString("tag_name"),
                    name = obj.optString("name").ifBlank { obj.optString("tag_name") },
                    apkUrl = apk.optString("browser_download_url")
                )
            }.maxWithOrNull(Comparator { a, b -> compare(parse(a.tag), parse(b.tag)) })
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): List<Int> {
        val m = Regex("""(\d+)\.(\d+)\.(\d+)""").find(text) ?: return listOf(0, 0, 0)
        return m.groupValues.drop(1).map { it.toIntOrNull() ?: 0 }
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        repeat(3) { i ->
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
