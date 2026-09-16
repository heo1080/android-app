#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
manager_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/VoiceAndSoundManager.kt"
build_path = ROOT / "app/build.gradle.kts"
main_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# ---- version ----
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "versionCode = 37", "versionCode = 39", "versionCode")
build = replace_once(
    build,
    'versionName = "3.0.7-v30.4-driver-dsp-probe"',
    'versionName = "3.0.9-v30.5.1-auto-diagnostic-upload"',
    "versionName",
)
build_path.write_text(build, encoding="utf-8")

# ---- production driver-only audio route ----
manager = manager_path.read_text(encoding="utf-8")

attrs_marker = '''    private val voiceCommunicationAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
'''
attrs_insert = '''    private fun buildDriverStream14Attributes(): AudioAttributes? = runCatching {
        val builder = AudioAttributes.Builder()
        val setter = builder.javaClass.methods.firstOrNull {
            it.name == "setLegacyStreamType" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: builder.javaClass.declaredMethods.firstOrNull {
                it.name == "setLegacyStreamType" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: throw NoSuchMethodException("AudioAttributes.Builder.setLegacyStreamType(int)")
        runCatching { setter.isAccessible = true }
        setter.invoke(builder, 14)
        builder.build().also { attrs ->
            DolphinLogger.i(
                "AUDIO",
                "v30.5 driver stream14 attributes ready usage=${attrs.usage} content=${attrs.contentType} flags=${attrs.flags}"
            )
        }
    }.onFailure {
        DolphinLogger.e("AUDIO", "v30.5 driver stream14 attributes unavailable; raw stream14 fallback will be used", unwrapReflection(it))
    }.getOrNull()

    private val driverStream14Attributes: AudioAttributes? by lazy { buildDriverStream14Attributes() }

    private val voiceCommunicationAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
'''
manager = replace_once(manager, attrs_marker, attrs_insert, "stream14 attributes injection")

manager = replace_once(
    manager,
    'DolphinLogger.i("AUDIO", "TTS 초기화 시작; route=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE")',
    'DolphinLogger.i("AUDIO", "TTS 초기화 시작; route=v30.5 LEGACY_STREAM_14 (NAV fallback only if hidden attribute unavailable)")',
    "TTS init log",
)
manager = replace_once(
    manager,
    "tts?.setAudioAttributes(navigationAudioAttributes)",
    "tts?.setAudioAttributes(driverStream14Attributes ?: navigationAudioAttributes)",
    "TTS stream14 attributes",
)
manager = replace_once(
    manager,
    '"usage=NAVIGATION_GUIDANCE outputs=${describeOutputs()}"',
    '"usage=${driverStream14Attributes?.let { "LEGACY_STREAM_14" } ?: "NAV_FALLBACK"} outputs=${describeOutputs()}"',
    "TTS route diagnostic",
)

pattern = re.compile(
    r'''    private fun playPcmTone\(frequencyHz: Double, durationMs: Int\) \{.*?\n    \}\n\n    private fun describeOutputs\(\): String =''',
    re.S,
)
replacement = r'''    private fun playPcmTone(frequencyHz: Double, durationMs: Int) {
        // v30.5 real-vehicle result: BYD legacy stream 14 is the confirmed driver-only DSP route.
        // Prefer Route 6 style AudioAttributes; if hidden API creation/playback fails, fall back to
        // Route 5 raw AudioTrack(streamType=14), 44.1 kHz mono, MODE_STATIC, write-first.
        val attrs = driverStream14Attributes
        if (attrs != null && playDriverStream14AttributesTone(attrs, frequencyHz, durationMs)) return
        playDriverRawStream14Tone(frequencyHz, durationMs)
    }

    private fun playDriverStream14AttributesTone(
        attrs: AudioAttributes,
        frequencyHz: Double,
        durationMs: Int
    ): Boolean {
        val sampleRate = 44_100
        val samples = buildToneSamples(sampleRate, frequencyHz, durationMs, amplitude = 0.32)
        var audioTrack: AudioTrack? = null
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(maxOf(samples.size * 2, minBuffer))
                .build()
            audioTrack = track
            val written = track.write(samples, 0, samples.size)
            if (written <= 0) throw IllegalStateException("stream14 attrs write=$written")
            track.setVolume(0.78f)
            track.play()
            Thread.sleep(70L)
            logTrackRoute(0, "V30_5_DRIVER_STREAM14_ATTR", track)
            Thread.sleep((durationMs - 40).coerceAtLeast(40).toLong())
            runCatching { track.stop() }
            DolphinLogger.i("AUDIO", "v30.5 stream14 attrs tone OK write=$written freq=$frequencyHz duration=$durationMs")
            true
        } catch (t: Throwable) {
            DolphinLogger.e("AUDIO", "v30.5 stream14 attrs tone failed; raw stream14 fallback", t)
            false
        } finally {
            runCatching { audioTrack?.release() }
        }
    }

    @Suppress("DEPRECATION")
    private fun playDriverRawStream14Tone(frequencyHz: Double, durationMs: Int): Boolean {
        val sampleRate = 44_100
        val samples = buildToneSamples(sampleRate, frequencyHz, durationMs, amplitude = 0.32)
        var audioTrack: AudioTrack? = null
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            val track = AudioTrack(
                14,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(samples.size * 2, minBuffer),
                AudioTrack.MODE_STATIC
            )
            audioTrack = track
            val preState = track.state
            if (preState != AudioTrack.STATE_INITIALIZED && preState != AudioTrack.STATE_NO_STATIC_DATA) {
                throw IllegalStateException("raw stream14 unexpected preState=$preState")
            }
            val written = track.write(samples, 0, samples.size)
            val postState = track.state
            if (written <= 0 || postState != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("raw stream14 postState=$postState write=$written")
            }
            track.setVolume(0.78f)
            track.play()
            Thread.sleep(70L)
            logTrackRoute(0, "V30_5_DRIVER_STREAM14_RAW_FALLBACK", track)
            Thread.sleep((durationMs - 40).coerceAtLeast(40).toLong())
            runCatching { track.stop() }
            DolphinLogger.i("AUDIO", "v30.5 raw stream14 tone OK write=$written freq=$frequencyHz duration=$durationMs")
            true
        } catch (t: Throwable) {
            DolphinLogger.e("AUDIO", "v30.5 raw stream14 tone failed", t)
            false
        } finally {
            runCatching { audioTrack?.release() }
        }
    }

    private fun describeOutputs(): String ='''
manager, count = pattern.subn(replacement, manager, count=1)
if count != 1:
    raise SystemExit(f"production tone replacement: expected 1 match, got {count}")

checks = {
    "version marker": 'v30.5 driver stream14 attributes ready' in manager,
    "tts stream14": 'tts?.setAudioAttributes(driverStream14Attributes ?: navigationAudioAttributes)' in manager,
    "attrs production route": 'V30_5_DRIVER_STREAM14_ATTR' in manager,
    "raw fallback": 'V30_5_DRIVER_STREAM14_RAW_FALLBACK' in manager,
    "old production NAV marker removed": 'NAV_WARNING_48K_AUTO' not in manager,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("v30.5 sanity check failed: " + ", ".join(failed))
manager_path.write_text(manager, encoding="utf-8")

# ---- v30.5.1 private diagnostic auto-upload UI / flow ----
main = main_path.read_text(encoding="utf-8")

capture_vars = '''        val tvCaptureStatus = findViewById<TextView>(R.id.tvDiagnosticCaptureStatus)
        val swRawNavText = findViewById<SwitchCompat>(R.id.swDiagnosticIncludeNavText)
        val btnStartCapture = findViewById<Button>(R.id.btnStartDiagnosticCapture)
        val btnProblemMarker = findViewById<Button>(R.id.btnDiagnosticProblemMarker)
        val btnStopAndExport = findViewById<Button>(R.id.btnStopExportDiagnosticBundle)

        fun refreshCaptureStatus() {
'''

capture_insert = '''        val tvCaptureStatus = findViewById<TextView>(R.id.tvDiagnosticCaptureStatus)
        val swRawNavText = findViewById<SwitchCompat>(R.id.swDiagnosticIncludeNavText)
        val btnStartCapture = findViewById<Button>(R.id.btnStartDiagnosticCapture)
        val btnProblemMarker = findViewById<Button>(R.id.btnDiagnosticProblemMarker)
        val btnStopAndExport = findViewById<Button>(R.id.btnStopExportDiagnosticBundle)

        val density = resources.displayMetrics.density
        val uploadPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#071B29"))
                cornerRadius = 12f * density
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.parseColor("#15506A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }
        val swDiagnosticAutoUpload = SwitchCompat(this).apply {
            text = "☁ 진단 종료 후 비공개 저장소 자동 업로드"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        val btnDiagnosticUploadSettings = Button(this).apply {
            text = "🔐 자동 업로드 저장소·토큰 설정"
            setTextColor(Color.WHITE)
            textSize = 12f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0288D1"))
        }
        val tvDiagnosticUploadStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 11f
            setPadding(0, (5 * density).toInt(), 0, 0)
        }
        uploadPanel.addView(swDiagnosticAutoUpload)
        uploadPanel.addView(btnDiagnosticUploadSettings)
        uploadPanel.addView(tvDiagnosticUploadStatus)
        (swRawNavText.parent as? LinearLayout)?.let { parent ->
            val index = parent.indexOfChild(swRawNavText)
            parent.addView(uploadPanel, (index + 1).coerceAtMost(parent.childCount))
        }

        fun refreshUploadStatus(message: String? = null, success: Boolean? = null) {
            val config = DiagnosticUploadManager.getConfig(this)
            swDiagnosticAutoUpload.setOnCheckedChangeListener(null)
            swDiagnosticAutoUpload.isChecked = config.enabled
            swDiagnosticAutoUpload.setOnCheckedChangeListener { _, enabled ->
                DiagnosticUploadManager.setAutoUploadEnabled(this, enabled)
                refreshUploadStatus()
            }
            tvDiagnosticUploadStatus.setTextColor(
                when (success) {
                    true -> Color.parseColor("#00E676")
                    false -> Color.parseColor("#FFAB40")
                    null -> if (config.complete) Color.parseColor("#80D8FF") else Color.parseColor("#FFD54F")
                }
            )
            tvDiagnosticUploadStatus.text = message ?: when {
                !config.complete -> "설정 필요 · Private GitHub 저장소와 fine-grained token을 한 번만 등록하세요."
                config.enabled -> "자동 업로드 ON · ${config.owner}/${config.repo} · ZIP 공유/재업로드 불필요"
                else -> "자동 업로드 OFF · 설정은 저장되어 있습니다."
            }
        }

        fun showDiagnosticUploadSettingsDialog() {
            val config = DiagnosticUploadManager.getConfig(this)
            val form = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (18 * density).toInt()
                setPadding(pad, (8 * density).toInt(), pad, 0)
            }
            val ownerInput = EditText(this).apply {
                hint = "GitHub owner"
                setText(config.owner)
                singleLine = true
            }
            val repoInput = EditText(this).apply {
                hint = "Private repo (예: dolphin-diagnostics)"
                setText(config.repo)
                singleLine = true
            }
            val branchInput = EditText(this).apply {
                hint = "branch"
                setText(config.branch)
                singleLine = true
            }
            val tokenInput = EditText(this).apply {
                hint = if (config.tokenPresent) "토큰 저장됨 · 변경할 때만 새 토큰 입력" else "fine-grained PAT (Contents: Read/Write)"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                singleLine = true
            }
            val autoSwitch = SwitchCompat(this).apply {
                text = "저장 후 자동 업로드 사용"
                isChecked = config.enabled
            }
            val guide = TextView(this).apply {
                text = "안전장치: 앱이 저장소가 Private인지 GitHub API로 확인합니다. Public이면 업로드를 거부합니다. 토큰은 Android Keystore로 암호화 저장됩니다."
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, (8 * density).toInt(), 0, 0)
            }
            form.addView(ownerInput)
            form.addView(repoInput)
            form.addView(branchInput)
            form.addView(tokenInput)
            form.addView(autoSwitch)
            form.addView(guide)

            AlertDialog.Builder(this)
                .setTitle("진단 자동 업로드 설정")
                .setView(form)
                .setPositiveButton("저장 + 연결 테스트") { _, _ ->
                    val saved = DiagnosticUploadManager.saveConfiguration(
                        this,
                        ownerInput.text.toString(),
                        repoInput.text.toString(),
                        branchInput.text.toString(),
                        tokenInput.text.toString().takeIf { it.isNotBlank() },
                        autoSwitch.isChecked
                    )
                    if (!saved) {
                        refreshUploadStatus("저장 실패 · owner/repo/branch 값을 확인하세요.", false)
                    } else {
                        refreshUploadStatus("Private 저장소 연결 확인 중…", null)
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                DiagnosticUploadManager.testConnection(this@MainActivity)
                            }
                            refreshUploadStatus(result.message, result.success)
                            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNeutralButton("토큰 삭제") { _, _ ->
                    DiagnosticUploadManager.clearToken(this)
                    DiagnosticUploadManager.setAutoUploadEnabled(this, false)
                    refreshUploadStatus("저장된 업로드 토큰을 삭제했습니다.", false)
                }
                .setNegativeButton("취소", null)
                .show()
        }

        btnDiagnosticUploadSettings.setOnClickListener { showDiagnosticUploadSettingsDialog() }
        refreshUploadStatus()

        fun refreshCaptureStatus() {
'''
main = replace_once(main, capture_vars, capture_insert, "diagnostic upload UI injection")

old_export = '''                if (file != null) {
                    findViewById<TextView>(R.id.tvLogPathInfo).apply {
                        text = "진단 ZIP 저장 완료: ${file.absolutePath} (${file.length()} Bytes)"
                        setTextColor(Color.parseColor("#00E676"))
                    }
                    shareDiagnosticFile(file, "application/zip", "원터치 진단 ZIP 공유")
                } else {
                    Toast.makeText(this@MainActivity, "내보낼 진단 세션이 없습니다.", Toast.LENGTH_LONG).show()
                }
                refreshCaptureStatus()
'''
new_export = '''                if (file != null) {
                    val pathView = findViewById<TextView>(R.id.tvLogPathInfo)
                    val config = DiagnosticUploadManager.getConfig(this@MainActivity)
                    if (config.enabled && config.complete) {
                        pathView.apply {
                            text = "진단 ZIP 생성 완료 · 비공개 저장소 자동 업로드 중…"
                            setTextColor(Color.parseColor("#80D8FF"))
                        }
                        val upload = withContext(Dispatchers.IO) {
                            DiagnosticUploadManager.uploadDiagnosticBundle(this@MainActivity, file)
                        }
                        if (upload.success) {
                            pathView.apply {
                                text = "☁ ${upload.message}\n세션: ${upload.sessionId} · 파일 ${upload.uploadedFiles}개"
                                setTextColor(Color.parseColor("#00E676"))
                            }
                            refreshUploadStatus("최신 진단 자동 업로드 완료 · 이제 파일을 다시 올릴 필요가 없습니다.", true)
                            Toast.makeText(this@MainActivity, "진단 자동 업로드 완료", Toast.LENGTH_LONG).show()
                        } else {
                            pathView.apply {
                                text = "자동 업로드 실패 · 로컬 ZIP 보존: ${upload.message}"
                                setTextColor(Color.parseColor("#FFAB40"))
                            }
                            refreshUploadStatus(upload.message, false)
                            shareDiagnosticFile(file, "application/zip", "자동 업로드 실패 · 진단 ZIP 공유")
                        }
                    } else {
                        pathView.apply {
                            text = "진단 ZIP 저장 완료: ${file.absolutePath} (${file.length()} Bytes)"
                            setTextColor(Color.parseColor("#00E676"))
                        }
                        shareDiagnosticFile(file, "application/zip", "원터치 진단 ZIP 공유")
                    }
                } else {
                    Toast.makeText(this@MainActivity, "내보낼 진단 세션이 없습니다.", Toast.LENGTH_LONG).show()
                }
                refreshCaptureStatus()
'''
main = replace_once(main, old_export, new_export, "diagnostic export auto upload flow")

main_path.write_text(main, encoding="utf-8")

print("v30.5.1 CI patch applied successfully")
print("versionCode=39 versionName=3.0.9-v30.5.1-auto-diagnostic-upload")
print("production audio=legacy stream14 attrs -> raw stream14 fallback; TTS attrs=stream14")
print("diagnostics=private GitHub auto upload with Android Keystore token protection")
