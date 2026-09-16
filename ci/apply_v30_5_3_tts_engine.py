#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
build_path = ROOT / "app/build.gradle.kts"
main_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt"
voice_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/VoiceAndSoundManager.kt"
manifest_path = ROOT / "app/src/main/AndroidManifest.xml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# ---- version ----
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "versionCode = 40", "versionCode = 41", "versionCode 41")
build = replace_once(
    build,
    'versionName = "3.0.10-v30.5.2-auto-update"',
    'versionName = "3.0.11-v30.5.3-korean-tts-engine"',
    "versionName v30.5.3",
)
build_path.write_text(build, encoding="utf-8")

# ---- package visibility for the explicit companion TTS engine ----
manifest = manifest_path.read_text(encoding="utf-8")
if 'com.k2fsa.sherpa.onnx.tts.engine' not in manifest:
    manifest = replace_once(
        manifest,
        '        <package android:name="br.com.rory.electro" />\n',
        '        <package android:name="br.com.rory.electro" />\n'
        '        <package android:name="com.k2fsa.sherpa.onnx.tts.engine" />\n',
        "sherpa TTS package visibility",
    )
manifest_path.write_text(manifest, encoding="utf-8")

# ---- prefer sherpa Android TextToSpeechService when present; allow hot refresh after install ----
voice = voice_path.read_text(encoding="utf-8")
voice = replace_once(
    voice,
    '    private val discoveredTtsEngines: List<String> by lazy { discoverTtsEngines() }\n',
    '    @Volatile private var discoveredTtsEngines: List<String> = discoverTtsEngines()\n',
    "mutable TTS engine discovery",
)

voice = replace_once(
    voice,
    '''        DolphinLogger.i("AUDIO", "TTS 엔진 후보=${discoveredTtsEngines.ifEmpty { listOf("<none>") }}")\n        startTtsEngine(null)\n''',
    '''        DolphinLogger.i("AUDIO", "TTS 엔진 후보=${discoveredTtsEngines.ifEmpty { listOf("<none>") }}")\n        val preferredEngine = TtsEngineInstallManager.ENGINE_PACKAGE\n            .takeIf { discoveredTtsEngines.contains(it) }\n        startTtsEngine(preferredEngine)\n''',
    "prefer installed sherpa engine",
)

voice = replace_once(
    voice,
    '''    private fun tryNextTtsEngine(): Boolean {\n        val next = discoveredTtsEngines.firstOrNull { !attemptedTtsEngines.contains(it) } ?: return false\n        startTtsEngine(next)\n        return true\n    }\n\n    fun ttsEngineDiagnosticSummary(): String =\n''',
    '''    private fun tryNextTtsEngine(): Boolean {\n        val next = discoveredTtsEngines.firstOrNull { !attemptedTtsEngines.contains(it) } ?: return false\n        startTtsEngine(next)\n        return true\n    }\n\n    /** Re-scan Android TTS services after the user installs the offline Korean engine. */\n    fun refreshTtsEngine() {\n        discoveredTtsEngines = discoverTtsEngines()\n        attemptedTtsEngines.clear()\n        val preferredEngine = TtsEngineInstallManager.ENGINE_PACKAGE\n            .takeIf { discoveredTtsEngines.contains(it) }\n        DolphinLogger.i(\n            "AUDIO",\n            "TTS hot refresh candidates=${discoveredTtsEngines.ifEmpty { listOf(\"<none>\") }} preferred=${preferredEngine ?: \"DEFAULT\"}"\n        )\n        startTtsEngine(preferredEngine)\n    }\n\n    fun hasAnyTtsEngine(): Boolean {\n        discoveredTtsEngines = discoverTtsEngines()\n        return discoveredTtsEngines.isNotEmpty()\n    }\n\n    fun ttsEngineDiagnosticSummary(): String =\n''',
    "TTS hot refresh",
)
voice_path.write_text(voice, encoding="utf-8")

# ---- UI in the existing TTS screen + auto refresh when installer returns ----
main = main_path.read_text(encoding="utf-8")
main = replace_once(
    main,
    '''    private fun setupVoiceSubScreen() {\n        findViewById<SwitchCompat>(R.id.swExperimentalLvda).apply {\n''',
    '''    private fun setupVoiceSubScreen() {\n        setupTtsEngineRecoveryControls()\n\n        findViewById<SwitchCompat>(R.id.swExperimentalLvda).apply {\n''',
    "TTS recovery controls hook",
)

voice_controls = r'''    private fun setupTtsEngineRecoveryControls() {
        val voiceRoot = subLayoutVoice as? LinearLayout ?: return
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10232E"))
                cornerRadius = 12f * density
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.parseColor("#2B7890"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            }
        }
        val title = TextView(this).apply {
            text = "🗣 한국어 오프라인 TTS 엔진"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 11f
            setPadding(0, (4 * density).toInt(), 0, (6 * density).toInt())
        }
        val installButton = Button(this).apply {
            text = "⬇ 한국어 TTS 엔진 다운로드 · 설치"
            setTextColor(Color.WHITE)
            textSize = 12f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00796B"))
        }
        val testButton = Button(this).apply {
            text = "🔊 TTS 다시 초기화 + 운전석 음성 테스트"
            setTextColor(Color.WHITE)
            textSize = 12f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#3949AB"))
        }
        panel.addView(title)
        panel.addView(status)
        panel.addView(installButton)
        panel.addView(testButton)
        voiceRoot.addView(panel, 1.coerceAtMost(voiceRoot.childCount))

        fun refreshStatus(extra: String? = null) {
            status.text = buildString {
                append(TtsEngineInstallManager.statusText(this@MainActivity))
                append("\n")
                append(audioManager.ttsEngineDiagnosticSummary())
                if (!extra.isNullOrBlank()) {
                    append("\n")
                    append(extra)
                }
            }
            installButton.text = if (TtsEngineInstallManager.isInstalled(this@MainActivity)) {
                "✅ 한국어 TTS 엔진 설치됨"
            } else {
                "⬇ 한국어 TTS 엔진 다운로드 · 설치"
            }
        }

        installButton.setOnClickListener {
            TtsEngineInstallManager.showInstallFlow(this)
            refreshStatus()
        }
        testButton.setOnClickListener {
            refreshStatus("TTS 엔진 다시 탐색 중…")
            audioManager.refreshTtsEngine()
            lifecycleScope.launch {
                repeat(24) {
                    if (audioManager.isTtsReady) return@repeat
                    delay(250L)
                }
                if (audioManager.isTtsReady) {
                    refreshStatus("TTS 준비 완료 · 운전석 stream14 음성 테스트 실행")
                    audioManager.speak("운전석 전용 한국어 음성 테스트입니다.")
                } else {
                    refreshStatus("TTS 준비 실패 · 진단 로그에 엔진 초기화 상태를 기록했습니다.")
                    Toast.makeText(
                        this@MainActivity,
                        "TTS 엔진이 아직 준비되지 않았습니다. 설치 완료 후 다시 눌러주세요.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        refreshStatus()
    }

'''

main = replace_once(
    main,
    '''    private fun setupVoiceEditButton(\n''',
    voice_controls + '''    private fun setupVoiceEditButton(\n''',
    "TTS recovery controls function",
)

main = replace_once(
    main,
    '''        updateDashboardCards()\n        if (AdbPermissionManager.isOverlayGranted(this) && SettingsManager.isFloatingOverlayEnabled(this)) {\n''',
    '''        updateDashboardCards()\n        if (::audioManager.isInitialized && !audioManager.isTtsReady && TtsEngineInstallManager.isInstalled(this)) {\n            DolphinLogger.i("AUDIO", "onResume: installed Korean TTS engine detected; hot refresh")\n            audioManager.refreshTtsEngine()\n        }\n        if (AdbPermissionManager.isOverlayGranted(this) && SettingsManager.isFloatingOverlayEnabled(this)) {\n''',
    "onResume TTS hot refresh",
)
main_path.write_text(main, encoding="utf-8")

checks = {
    "version 41": 'versionCode = 41' in build,
    "v30.5.3 name": '3.0.11-v30.5.3-korean-tts-engine' in build,
    "sherpa query": 'com.k2fsa.sherpa.onnx.tts.engine' in manifest,
    "mutable discovery": 'private var discoveredTtsEngines' in voice,
    "preferred engine": 'TtsEngineInstallManager.ENGINE_PACKAGE' in voice,
    "hot refresh": 'fun refreshTtsEngine()' in voice,
    "voice screen installer": '한국어 TTS 엔진 다운로드 · 설치' in main,
    "voice test": '운전석 전용 한국어 음성 테스트입니다.' in main,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("v30.5.3 TTS sanity check failed: " + ", ".join(failed))

print("v30.5.3 Korean TTS engine patch applied")
