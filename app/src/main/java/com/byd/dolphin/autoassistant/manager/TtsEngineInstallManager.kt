package com.byd.dolphin.autoassistant.manager

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Installs the official sherpa-onnx Korean Android TextToSpeechService engine.
 *
 * The vehicle currently exposes no Android TTS engine at all.  We therefore download one
 * official, model-bundled APK directly from the sherpa-onnx published APK repository and let
 * Android's normal package installer perform the final user-confirmed installation.
 *
 * We intentionally do not change the system-wide default TTS engine. DolphinAssistant opens
 * this engine explicitly by package name from VoiceAndSoundManager.
 */
object TtsEngineInstallManager {
    private const val TAG = "TTS_ENGINE_INSTALL"

    const val ENGINE_PACKAGE = "com.k2fsa.sherpa.onnx.tts.engine"
    private const val ENGINE_VERSION = "1.13.4"
    private const val MODEL = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    private const val BASE_URL =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/$ENGINE_VERSION"

    private val supportedApkAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(ENGINE_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun selectedAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull { it in supportedApkAbis }

    fun statusText(context: Context): String {
        val abi = selectedAbi() ?: Build.SUPPORTED_ABIS.joinToString().ifBlank { "unknown" }
        return if (isInstalled(context)) {
            "🟢 한국어 TTS 엔진 설치됨 · $ENGINE_PACKAGE · ABI $abi"
        } else {
            "🟠 차량 TTS 엔진 없음 · 한국어 오프라인 엔진 설치 필요 · ABI $abi"
        }
    }

    fun showInstallFlow(activity: AppCompatActivity) {
        if (isInstalled(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("한국어 TTS 엔진 설치됨")
                .setMessage(
                    "공식 sherpa-onnx 한국어 TTS 엔진이 이미 설치되어 있습니다.\n\n" +
                        "아래의 'TTS 다시 초기화 + 테스트' 버튼으로 운전석 음성을 확인하세요."
                )
                .setPositiveButton("확인", null)
                .show()
            return
        }

        val abi = selectedAbi()
        if (abi == null) {
            AlertDialog.Builder(activity)
                .setTitle("지원하지 않는 CPU")
                .setMessage("차량 ABI를 지원하는 TTS APK를 찾지 못했습니다.\n${Build.SUPPORTED_ABIS.joinToString()}")
                .setPositiveButton("확인", null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle("설치 권한을 한 번만 허용해주세요")
                .setMessage(
                    "한국어 TTS 엔진 APK를 차량에서 직접 설치하려면 DolphinAssistant의 '이 출처 허용' 권한이 필요합니다.\n\n" +
                        "다음 화면에서 허용한 뒤 앱으로 돌아와 이 버튼을 다시 누르세요."
                )
                .setNegativeButton("취소", null)
                .setPositiveButton("설정 열기") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    }.onFailure {
                        DolphinLogger.e(TAG, "unknown-source settings open failed", it)
                        Toast.makeText(activity, "설치 권한 화면을 열지 못했습니다.", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("한국어 오프라인 TTS 엔진 설치")
            .setMessage(
                "차량에 Android TTS 엔진이 하나도 없어 현재 음성이 비프로 대체되고 있습니다.\n\n" +
                    "공식 sherpa-onnx Supertonic 한국어 엔진을 차량에서 직접 다운로드합니다. " +
                    "다운로드 후 패키지명과 TTS 서비스 포함 여부를 검사하고 Android 설치 화면을 엽니다.\n\n" +
                    "CPU: $abi"
            )
            .setNegativeButton("취소", null)
            .setPositiveButton("다운로드 · 설치") { _, _ -> downloadAndOpenInstaller(activity, abi) }
            .show()
    }

    private fun downloadAndOpenInstaller(activity: AppCompatActivity, abi: String) {
        val progress = ProgressDialog(activity).apply {
            setTitle("한국어 TTS 엔진")
            setMessage("오프라인 음성 엔진을 다운로드하고 검증하는 중입니다…")
            setCancelable(false)
            setIndeterminate(true)
            show()
        }

        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { downloadAndVerify(activity, abi) }
            }
            progress.dismiss()

            val apk = result.getOrElse { error ->
                DolphinLogger.e(TAG, "TTS engine download/verify failed", error)
                if (!activity.isFinishing) {
                    AlertDialog.Builder(activity)
                        .setTitle("TTS 엔진 설치 준비 실패")
                        .setMessage("다운로드 또는 검증에 실패했습니다.\n\n${error.message ?: error.javaClass.simpleName}")
                        .setPositiveButton("확인", null)
                        .show()
                }
                return@launch
            }

            openInstaller(activity, apk)
        }
    }

    private fun assetName(abi: String): String =
        "sherpa-onnx-$ENGINE_VERSION-$abi-kor-tts-engine-$MODEL.apk"

    private fun downloadAndVerify(context: Context, abi: String): File {
        val name = assetName(abi)
        val url = "$BASE_URL/$name"
        val dir = File(context.filesDir, "tts-engine").apply { mkdirs() }
        val file = File(dir, name)
        downloadToFile(url, file)

        require(file.length() > 1_000_000L) { "다운로드된 TTS APK가 비정상적으로 작습니다." }
        verifyArchive(context, file)
        DolphinLogger.i(
            TAG,
            "official TTS engine verified abi=$abi bytes=${file.length()} sha256=${sha256(file)} package=$ENGINE_PACKAGE"
        )
        return file
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(context: Context, apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SERVICES
        ) ?: error("다운로드 APK의 패키지 정보를 읽을 수 없습니다.")

        require(info.packageName == ENGINE_PACKAGE) {
            "예상한 TTS 엔진 패키지가 아닙니다: ${info.packageName}"
        }
        val serviceNames = info.services?.mapNotNull { it.name }.orEmpty()
        require(serviceNames.any { it.endsWith(".TtsService") || it.contains("TtsService") }) {
            "다운로드 APK에 Android TTS 서비스가 없습니다."
        }
    }

    private fun openInstaller(activity: AppCompatActivity, apk: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apk
            )
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure { error ->
            DolphinLogger.e(TAG, "TTS engine installer open failed", error)
            AlertDialog.Builder(activity)
                .setTitle("설치 화면 열기 실패")
                .setMessage(error.message ?: error.javaClass.simpleName)
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private fun downloadToFile(url: String, file: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "DolphinAssistant-TTS/30.5.3")
            setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "TTS APK HTTP $code" }
            file.outputStream().buffered().use { output ->
                connection.inputStream.use { input -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
