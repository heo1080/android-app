#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    p = ROOT / rel
    return p, p.read_text(encoding="utf-8")

def write(p, text):
    p.write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# Gradle: v30.6.2 + in-app sherpa AAR downloaded by workflow.
# ---------------------------------------------------------------------------
p, text = read("app/build.gradle.kts")
text = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 45', text, count=1)
text = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "3.1.2-v30.6.2-runtime-voice-fix"', text, count=1)
if 'sherpa-onnx-1.13.4.aar' not in text:
    text = replace_once(
        text,
        'dependencies {\n',
        'dependencies {\n    implementation(files("libs/sherpa-onnx-1.13.4.aar"))\n',
        'add sherpa aar dependency',
    )
write(p, text)

# ---------------------------------------------------------------------------
# Fix small Kotlin/JVM type details in the newly committed in-app TTS manager.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/InAppSupertonicTtsManager.kt")
text = text.replace('header.putShort(1)\n            header.putShort(1)', 'header.putShort(1.toShort())\n            header.putShort(1.toShort())')
text = text.replace('header.putShort(2)\n            header.putShort(16)', 'header.putShort(2.toShort())\n            header.putShort(16.toShort())')
text = text.replace(
    'it.name == "setLegacyStreamType" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))',
    'it.name == "setLegacyStreamType" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType'
)
write(p, text)

# ---------------------------------------------------------------------------
# Settings: exact requested phrases, ICC ON/OFF, and a BSD-specific toggle.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/SettingsManager.kt")
text = replace_once(
    text,
    '    private const val KEY_PHRASE_ICC_ON = "key_phrase_icc_on"\n',
    '    private const val KEY_PHRASE_ICC_ON = "key_phrase_icc_on"\n'
    '    private const val KEY_PHRASE_ICC_OFF = "key_phrase_icc_off"\n'
    '    private const val KEY_BSD_ALERT_ENABLED = "key_bsd_alert_enabled"\n',
    'add icc off/bsd keys',
)
# Exact default wording requested by the driver.
text = text.replace('"회생제동 하이"', '"하이"')
text = text.replace('"회생제동 에코"', '"스탠다드"')
text = text.replace('"스노우 모드가 켜졌습니다."', '"스노우모드"')
text = text.replace('"오토홀드가 켜졌습니다."', '"오토홀드 ON"')
text = text.replace('"오토홀드가 꺼졌습니다."', '"오토홀드 OFF"')
text = text.replace('"오토홀드가 체결되었습니다."', '"오토홀드 체결 유"')
text = text.replace('"오토홀드가 해제되었습니다."', '"오토홀드 체결 무"')
text = text.replace('"사이드브레이크가 체결되었습니다."', '"사이드브레이크 체결 유"')
text = text.replace('"사이드브레이크 해제되었습니다."', '"사이드브레이크 체결 무"')

old_icc = '''    fun getIccPhrase(context: Context): String = getPrefs(context).getString(KEY_PHRASE_ICC_ON, "자율주행이 켜졌습니다.") ?: "자율주행이 켜졌습니다."
    fun setIccPhrase(context: Context, phrase: String) = getPrefs(context).edit().putString(KEY_PHRASE_ICC_ON, phrase).apply()
'''
new_icc = '''    fun getIccPhrase(context: Context, isActive: Boolean): String {
        val key = if (isActive) KEY_PHRASE_ICC_ON else KEY_PHRASE_ICC_OFF
        val fallback = if (isActive) "자율주행 ON" else "자율주행 OFF"
        return getPrefs(context).getString(key, fallback) ?: fallback
    }
    fun setIccPhrase(context: Context, isActive: Boolean, phrase: String) {
        val key = if (isActive) KEY_PHRASE_ICC_ON else KEY_PHRASE_ICC_OFF
        getPrefs(context).edit().putString(key, phrase).apply()
    }
    // Existing UI compatibility; v30.6.2 adds the explicit OFF variant dynamically.
    fun getIccPhrase(context: Context): String = getIccPhrase(context, true)
    fun setIccPhrase(context: Context, phrase: String) = setIccPhrase(context, true, phrase)

    fun isBsdAlertEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_BSD_ALERT_ENABLED, true)
    fun setBsdAlertEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_BSD_ALERT_ENABLED, enabled).apply()
'''
text = replace_once(text, old_icc, new_icc, 'replace ICC settings')
write(p, text)

# ---------------------------------------------------------------------------
# Voice manager: completely bypass Android TextToSpeech at playback time.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/VoiceAndSoundManager.kt")
text = text.replace(
    'fun speakIcc(isActive: Boolean) { if (SettingsManager.isIccVoiceEnabled(context)) speak(SettingsManager.getIccPhrase(context)) }',
    'fun speakIcc(isActive: Boolean) { if (SettingsManager.isIccVoiceEnabled(context)) speak(SettingsManager.getIccPhrase(context, isActive)) }'
)
text = text.replace(
    'if (!SettingsManager.isSafetyAlertEnabled(context)) return\n        when (SettingsManager.getBsdAlertMode(context))',
    'if (!SettingsManager.isSafetyAlertEnabled(context) || !SettingsManager.isBsdAlertEnabled(context)) return\n        when (SettingsManager.getBsdAlertMode(context))',
    1,
)
# User confirmed stock lane-departure warning already exists; never duplicate it.
ldw_start = text.find('    fun playLaneDepartureWarning() {')
if ldw_start >= 0:
    ldw_end = text.find('\n    /** Existing single navigation-route test', ldw_start)
    if ldw_end < 0:
        raise SystemExit('lane warning function end not found')
    text = text[:ldw_start] + '''    fun playLaneDepartureWarning() {
        DolphinLogger.i("AUDIO", "LDP duplicate warning suppressed: vehicle stock warning retained")
    }
''' + text[ldw_end:]

speak_start = text.find('    fun speak(text: String) {')
speak_end = text.find('\n    fun release() {', speak_start)
if speak_start < 0 or speak_end < 0:
    raise SystemExit('VoiceAndSoundManager.speak block not found')
new_speak = '''    fun speak(text: String) {
        if (text.isBlank()) return
        val accepted = InAppSupertonicTtsManager.speak(context, text)
        DolphinLogger.i(
            "AUDIO",
            "v30.6.2 in-app Supertonic request accepted=$accepted len=${text.length} " +
                InAppSupertonicTtsManager.statusSummary(context)
        )
        if (!accepted) {
            // Model preparation can take one first-time download. Do not queue the
            // utterance into the broken system TextToSpeech service; emit only a
            // short immediate marker so an old sentence can never appear later.
            soundScope.launch { playNavigationBeepPattern(1180.0, 120, 1, 0) }
        }
    }
'''
text = text[:speak_start] + new_speak + text[speak_end:]
write(p, text)

# ---------------------------------------------------------------------------
# Mirror: the live getter returned -1/44 but setter accepts discrete 0..8.
# Reject invalid captured values and expose explicit calibrated presets.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/MirrorMemoryManager.kt")
text = replace_once(
    text,
    '    private const val COMMAND_SETTLE_MS = 900L\n',
    '    private const val COMMAND_SETTLE_MS = 900L\n    private const val MIN_SETTER_INDEX = 0\n    private const val MAX_SETTER_INDEX = 8\n',
    'mirror index bounds',
)
insert_after = '''    fun readCurrent(context: Context): Position? = runCatching {
        val target = setting(context)
        val left = invokeInt(target, "getLeftViewMirrorFlipAngle")
        val right = invokeInt(target, "getRightViewMirrorFlipAngle")
        if (left == null || right == null) null else Position(left, right)
    }.onFailure { DolphinLogger.e(TAG, "mirror getter failed", it.cause ?: it) }.getOrNull()
'''
addition = insert_after + '''
    private fun isSetterPositionValid(position: Position): Boolean =
        position.left in MIN_SETTER_INDEX..MAX_SETTER_INDEX &&
            position.right in MIN_SETTER_INDEX..MAX_SETTER_INDEX

    fun saveCalibratedNormal(context: Context, left: Int, right: Int): Boolean =
        saveCalibratedPreset(context, Position(left, right), reverse = false)

    fun saveCalibratedReverse(context: Context, left: Int, right: Int): Boolean =
        saveCalibratedPreset(context, Position(left, right), reverse = true)

    private fun saveCalibratedPreset(context: Context, position: Position, reverse: Boolean): Boolean {
        if (!isSetterPositionValid(position)) return false
        val p = prefs(context).edit()
        if (reverse) {
            p.putInt(KEY_REVERSE_LEFT, position.left)
                .putInt(KEY_REVERSE_RIGHT, position.right)
                .putBoolean(KEY_HAS_REVERSE, true)
        } else {
            p.putInt(KEY_NORMAL_LEFT, position.left)
                .putInt(KEY_NORMAL_RIGHT, position.right)
                .putBoolean(KEY_HAS_NORMAL, true)
        }
        p.apply()
        DolphinLogger.i(TAG, "${if (reverse) "REVERSE" else "NORMAL"} calibrated setter preset=$position")
        return true
    }

    fun previewCalibrated(context: Context, left: Int, right: Int): Boolean {
        val position = Position(left, right)
        if (!isSetterPositionValid(position)) return false
        return applyPosition(context.applicationContext, position, "CALIBRATION_PREVIEW", takeOwnership = false)
    }
'''
text = replace_once(text, insert_after, addition, 'mirror calibration API')
# Never save getter values that cannot be written back.
text = text.replace(
    '        if (pos == null) {\n            state = State.ERROR\n            return null\n        }\n        prefs(context).edit()',
    '        if (pos == null || !isSetterPositionValid(pos)) {\n            state = State.ERROR\n            DolphinLogger.w(TAG, "getter value is not a valid setter index: $pos; use 0..8 calibrated preset")\n            return null\n        }\n        prefs(context).edit()',
)
# Invalidate old -1/44 style presets at read time.
text = text.replace(
    '        return Position(p.getInt(KEY_NORMAL_LEFT, 0), p.getInt(KEY_NORMAL_RIGHT, 0))',
    '        val pos = Position(p.getInt(KEY_NORMAL_LEFT, 0), p.getInt(KEY_NORMAL_RIGHT, 0))\n        return pos.takeIf(::isSetterPositionValid)'
)
text = text.replace(
    '        return Position(p.getInt(KEY_REVERSE_LEFT, 0), p.getInt(KEY_REVERSE_RIGHT, 0))',
    '        val pos = Position(p.getInt(KEY_REVERSE_LEFT, 0), p.getInt(KEY_REVERSE_RIGHT, 0))\n        return pos.takeIf(::isSetterPositionValid)'
)
# Getter semantics differ from setter semantics on the target car, so don't let an
# out-of-range getter falsely look like a manual override.
text = text.replace(
    '        val actual = readCurrent(context) ?: return\n        if (actual != expected) {',
    '        val actual = readCurrent(context) ?: return\n        if (!isSetterPositionValid(actual)) return\n        if (actual != expected) {'
)
write(p, text)

# ---------------------------------------------------------------------------
# Interior light: no-argument method is a true OFF command on this framework.
# Use it first; keep the old parameter route only as fallback.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/InsideLightManager.kt")
old_off = '''    fun turnOff(context: Context, showToast: Boolean = true): Boolean {
        val ok = invokeInsideLight(context, STATE_OFF)
'''
new_off = '''    fun turnOff(context: Context, showToast: Boolean = true): Boolean {
        val direct = runCatching {
            val clazz = Class.forName(SETTING_CLASS)
            val instance = clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, BydPermissionContext.wrap(context))
                ?: error("getInstance returned null")
            val result = (clazz.getMethod("turnOffInsideLight").invoke(instance) as? Number)?.toInt()
            DolphinLogger.i(TAG, "turnOffInsideLight() result=$result")
            result == 0
        }.getOrElse {
            DolphinLogger.w(TAG, "turnOffInsideLight() unavailable: ${it.javaClass.simpleName}:${it.message}")
            false
        }
        val ok = direct || invokeInsideLight(context, STATE_OFF)
'''
if old_off in text:
    text = replace_once(text, old_off, new_off, 'inside light direct off')
write(p, text)

# ---------------------------------------------------------------------------
# Display font size: write succeeded in live log; force a configuration signal
# and recreate our activity so the visible UI picks it up immediately.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/DisplayDiagnosticsManager.kt")
if 'import android.app.Activity' not in text:
    text = text.replace('import android.content.Context\n', 'import android.app.Activity\nimport android.content.Context\nimport android.os.Handler\nimport android.os.Looper\n')
old_font = '''        DolphinLogger.i(TAG, "font_scale set=$normalized ok=$ok raw=${result.output.take(160)}")
        return ok
'''
new_font = '''        DolphinLogger.i(TAG, "font_scale set=$normalized ok=$ok raw=${result.output.take(160)}")
        if (ok) {
            runCatching { LocalAdbManager.executeShellCommand(context, "am broadcast -a android.intent.action.CONFIGURATION_CHANGED") }
            Handler(Looper.getMainLooper()).postDelayed({
                (context as? Activity)?.recreate()
            }, 450L)
        }
        return ok
'''
if old_font in text:
    text = replace_once(text, old_font, new_font, 'font scale refresh')
write(p, text)

# ---------------------------------------------------------------------------
# Service: connect all new state events and start Supertonic preparation.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/service/DolphinService.kt")
text = replace_once(
    text,
    '    private lateinit var telemetryMonitor: VehicleTelemetryMonitor\n',
    '    private lateinit var telemetryMonitor: VehicleTelemetryMonitor\n    private lateinit var voiceStateMonitor: VehicleVoiceStateMonitor\n',
    'service voice monitor field',
)
# Add AutoHold interpreter before split restoration helper.
anchor = '    private fun scheduleSplitScreenRestoration(context: Context?) {'
helper = '''    private fun handleAutoHoldVoice(previous: Int?, current: Int) {
        // BYD AVH exposes four raw states. On DiLink 3 the useful transitions are
        // OFF(0) -> READY(1) -> HOLDING(2); state 3 is treated as fault/unknown.
        when {
            previous == 0 && current == 1 -> audioManager.speakAutoHoldSwitch(true)
            previous == 1 && current == 0 -> audioManager.speakAutoHoldSwitch(false)
            previous == 1 && current == 2 -> audioManager.speakAutoHoldBrake(true)
            previous == 2 && current == 1 -> audioManager.speakAutoHoldBrake(false)
            else -> DolphinLogger.i("AUTOHOLD", "unspoken AVH transition $previous -> $current")
        }
    }

'''
if anchor not in text:
    raise SystemExit('service schedule anchor missing')
text = text.replace(anchor, helper + anchor, 1)
# Start in-app model after audio manager exists.
text = replace_once(
    text,
    '        audioManager = VoiceAndSoundManager(this)\n        hazardManager = HazardLightManager(this)\n',
    '        audioManager = VoiceAndSoundManager(this)\n'
    '        InAppSupertonicTtsManager.prewarmDefaults(this)\n'
    '        hazardManager = HazardLightManager(this)\n',
    'service tts prewarm',
)
start_anchor = '        telemetryMonitor.start()\n\n        registerVehicleReceiver()'
voice_monitor_start = '''        telemetryMonitor.start()

        voiceStateMonitor = VehicleVoiceStateMonitor(
            context = this,
            onDriveModeChanged = { audioManager.speakDriveMode(it) },
            onRegenModeChanged = { audioManager.speakRegenMode(it) },
            onSnowModeChanged = { enabled -> if (enabled) audioManager.speakSnowMode() },
            onAutoHoldRawChanged = { previous, current -> handleAutoHoldVoice(previous, current) },
            onIccChanged = { active -> audioManager.speakIcc(active) },
            onBsdSignal = { direction, raw ->
                DolphinLogger.i("BSD", "direction=$direction activeRaw=$raw -> warning beep")
                audioManager.playBlindSpotWarning()
            }
        )
        voiceStateMonitor.start()

        registerVehicleReceiver()'''
text = replace_once(text, start_anchor, voice_monitor_start, 'start voice monitor')
text = replace_once(
    text,
    '        if (::telemetryMonitor.isInitialized) telemetryMonitor.stop()\n',
    '        if (::telemetryMonitor.isInitialized) telemetryMonitor.stop()\n        if (::voiceStateMonitor.isInitialized) voiceStateMonitor.stop()\n',
    'stop voice monitor',
)
write(p, text)

# ---------------------------------------------------------------------------
# Fix BSD change detection ordering in the new monitor.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/VehicleVoiceStateMonitor.kt")
old_bsd = '''        if (bsd != null && bsd != lastBsdRaw) {
            DolphinLogger.i(TAG, "BSD raw ${lastBsdRaw ?: "unknown"} -> $bsd")
            lastBsdRaw = bsd
        }
'''
new_bsd = '''        val previousBsd = lastBsdRaw
        if (bsd != null && bsd != previousBsd) {
            DolphinLogger.i(TAG, "BSD raw ${previousBsd ?: "unknown"} -> $bsd")
            lastBsdRaw = bsd
        }
'''
text = replace_once(text, old_bsd, new_bsd, 'bsd previous ordering')
text = text.replace('        val previousBsd = lastBsdRaw ?: return\n        if (baseline == previousBsd) return', '        val previous = previousBsd ?: return\n        if (baseline == previous) return')
write(p, text)

# ---------------------------------------------------------------------------
# Integrated UI: replace cloud TTS panel, add individual toggles, calibrated
# mirror indices, installed-app picker, and visible font refresh controls.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/IntegratedBetaUi.kt")
# Add toggle panel call.
text = replace_once(
    text,
    '        attachNaturalVoice(activity, content, audioManager)\n        attachComfortLab(activity, content)\n',
    '        attachNaturalVoice(activity, content, audioManager)\n        attachVoiceToggles(activity, content)\n        attachComfortLab(activity, content)\n',
    'add voice toggles panel call',
)

natural_start = text.find('    fun attachNaturalVoice(')
comfort_start = text.find('    fun attachComfortLab(', natural_start)
if natural_start < 0 or comfort_start < 0:
    raise SystemExit('Integrated UI natural/comfort blocks not found')
new_natural = r'''    fun attachNaturalVoice(activity: AppCompatActivity, rootView: View, audioManager: VoiceAndSoundManager) {
        val root = rootView as? ViewGroup ?: return
        val panel = panel(
            activity,
            "인앱 한국어 TTS · Supertonic 3",
            "Android TTS 서비스를 사용하지 않습니다. Supertonic 3 한국어 모델을 앱 내부에서 실행해 문장별 WAV 캐시를 만들고 검증된 BYD stream14 운전석 전용 경로로 재생합니다. 최초 모델 준비 약 145 MB."
        )
        val testText = EditText(activity).apply {
            setText("전방 차량이 출발했습니다.")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "미리듣기 문장"
        }
        val status = info(activity, InAppSupertonicTtsManager.statusSummary(activity))
        val prepare = button(activity, "① Supertonic 3 한국어 모델 준비") {
            status.text = "모델 다운로드/엔진 준비 중…"
            InAppSupertonicTtsManager.ensureModelAsync(activity) { _, message ->
                activity.runOnUiThread {
                    status.text = "$message\n${InAppSupertonicTtsManager.statusSummary(activity)}"
                    toast(activity, message)
                }
            }
        }
        val preview = button(activity, "② 문장 생성 + 운전석 미리듣기") {
            val accepted = InAppSupertonicTtsManager.speak(activity, testText.text.toString())
            status.text = "미리듣기 accepted=$accepted\n${InAppSupertonicTtsManager.statusSummary(activity)}"
            if (!accepted) toast(activity, "모델을 먼저 준비하고 있습니다. 준비 완료 후 다시 눌러 주세요.")
        }
        val prewarm = button(activity, "기본 차량 안내문구 전부 미리 생성") {
            InAppSupertonicTtsManager.prewarmDefaults(activity)
            status.text = "기어/모드/오토홀드/EPB/ICC/전방차량 기본 문구 생성 요청\n${InAppSupertonicTtsManager.statusSummary(activity)}"
        }
        val clear = button(activity, "문장 캐시 비우기") {
            val count = InAppSupertonicTtsManager.clearPhraseCache(activity)
            status.text = "문장 캐시 ${count}개 삭제\n${InAppSupertonicTtsManager.statusSummary(activity)}"
        }
        panel.addView(testText)
        panel.addView(prepare)
        panel.addView(preview)
        panel.addView(prewarm)
        panel.addView(clear)
        panel.addView(status)
        root.addView(panel)
    }

    fun attachVoiceToggles(activity: AppCompatActivity, rootView: View) {
        val root = rootView as? ViewGroup ?: return
        val panel = panel(activity, "맞춤형 음성 안내 · 개별 ON/OFF", "각 항목은 서로 독립적으로 켜고 끕니다. 차선이탈 경고는 순정 경고를 사용하므로 앱 중복 경고는 항상 비활성입니다.")
        fun toggle(label: String, checked: Boolean, save: (Boolean) -> Unit): SwitchCompat =
            SwitchCompat(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                isChecked = checked
                setOnCheckedChangeListener { _, value -> save(value) }
            }
        panel.addView(toggle("기어 P / R / N / D", SettingsManager.isGearVoiceEnabled(activity)) { SettingsManager.setGearVoiceEnabled(activity, it) })
        panel.addView(toggle("회생제동 스탠다드 / 하이", SettingsManager.isRegenModeVoiceEnabled(activity)) { SettingsManager.setRegenModeVoiceEnabled(activity, it) })
        panel.addView(toggle("드라이브 에코 / 노멀 / 스포츠", SettingsManager.isDriveModeVoiceEnabled(activity)) { SettingsManager.setDriveModeVoiceEnabled(activity, it) })
        panel.addView(toggle("스노우모드", SettingsManager.isSnowModeVoiceEnabled(activity)) { SettingsManager.setSnowModeVoiceEnabled(activity, it) })
        panel.addView(toggle("오토홀드 ON/OFF + 체결 유/무", SettingsManager.isAutoHoldVoiceEnabled(activity)) { SettingsManager.setAutoHoldVoiceEnabled(activity, it) })
        panel.addView(toggle("사이드브레이크 체결 유/무", SettingsManager.isEpbVoiceEnabled(activity)) { SettingsManager.setEpbVoiceEnabled(activity, it) })
        panel.addView(toggle("ICC 자율주행 ON/OFF", SettingsManager.isIccVoiceEnabled(activity)) { SettingsManager.setIccVoiceEnabled(activity, it) })
        panel.addView(toggle("BSD + 같은 방향 깜박이 경고음", SettingsManager.isBsdAlertEnabled(activity)) { SettingsManager.setBsdAlertEnabled(activity, it) })
        panel.addView(toggle("전방차량출발 안내", SettingsManager.isLeadingCarVoiceEnabled(activity)) { SettingsManager.setLeadingCarVoiceEnabled(activity, it) })
        panel.addView(info(activity, "차선이탈: 순정 경고 사용 · DolphinAssistant 추가 경고 없음"))
        root.addView(panel)
    }

'''
text = text[:natural_start] + new_natural + text[comfort_start:]

# Replace comfort lab completely up to cluster lab.
comfort_start = text.find('    fun attachComfortLab(')
cluster_start = text.find('    fun attachClusterLab(', comfort_start)
if comfort_start < 0 or cluster_start < 0:
    raise SystemExit('comfort/cluster blocks not found')
new_comfort = r'''    fun attachComfortLab(activity: AppCompatActivity, rootView: View) {
        val root = rootView as? ViewGroup ?: return
        val panel = panel(activity, "다운미러 · 실내등 LAB", "20:27 실차 로그에서 getter=-1/44를 setter에 되넣어 INVALID_VALUE가 발생했습니다. setter의 검증된 범위 0~8에서 NORMAL/R-DOWN을 직접 캘리브레이션합니다.")
        val mirrorEnabled = SwitchCompat(activity).apply {
            text = "R단 자동 다운미러 사용 (5 km/h 이하만)"
            setTextColor(Color.WHITE)
            isChecked = MirrorMemoryManager.isEnabled(activity)
            setOnCheckedChangeListener { _, checked -> MirrorMemoryManager.setEnabled(activity, checked) }
        }
        val values = (0..8).map(Int::toString)
        fun spinner(initial: Int): Spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(initial.coerceIn(0, 8))
        }
        val n = MirrorMemoryManager.normalPreset(activity)
        val r = MirrorMemoryManager.reversePreset(activity)
        val normalLeft = spinner(n?.left ?: 4)
        val normalRight = spinner(n?.right ?: 4)
        val reverseLeft = spinner(r?.left ?: 4)
        val reverseRight = spinner(r?.right ?: 4)
        val status = info(activity, MirrorMemoryManager.statusSummary(activity))

        panel.addView(info(activity, "NORMAL · 왼쪽 / 오른쪽 (0~8)"))
        val normalRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        normalRow.addView(normalLeft, weight())
        normalRow.addView(normalRight, weight())
        panel.addView(normalRow)
        panel.addView(button(activity, "NORMAL 미리보기 + 저장") {
            val left = normalLeft.selectedItemPosition
            val right = normalRight.selectedItemPosition
            val applied = MirrorMemoryManager.previewCalibrated(activity, left, right)
            val saved = applied && MirrorMemoryManager.saveCalibratedNormal(activity, left, right)
            status.text = "NORMAL preview=$applied saved=$saved\n${MirrorMemoryManager.statusSummary(activity)}"
        })

        panel.addView(info(activity, "R-DOWN · 왼쪽 / 오른쪽 (0~8)"))
        val reverseRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        reverseRow.addView(reverseLeft, weight())
        reverseRow.addView(reverseRight, weight())
        panel.addView(reverseRow)
        panel.addView(button(activity, "R-DOWN 미리보기 + 저장") {
            val left = reverseLeft.selectedItemPosition
            val right = reverseRight.selectedItemPosition
            val applied = MirrorMemoryManager.previewCalibrated(activity, left, right)
            val saved = applied && MirrorMemoryManager.saveCalibratedReverse(activity, left, right)
            status.text = "R-DOWN preview=$applied saved=$saved\n${MirrorMemoryManager.statusSummary(activity)}"
        })

        val lightRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        lightRow.addView(button(activity, "실내등 ON") {
            val ok = InsideLightManager.turnOn(activity)
            toast(activity, "실내등 ON 요청=$ok")
        }, weight())
        lightRow.addView(button(activity, "실내등 OFF") {
            val ok = InsideLightManager.turnOff(activity)
            toast(activity, "실내등 OFF 요청=$ok")
        }, weight())
        val doorLight = button(activity, "도어 연동 실내등 토글") {
            val next = InsideLightManager.toggleDoorInterlock(activity)
            toast(activity, "도어 연동 요청: ${if (next) "ON" else "OFF"}")
        }
        panel.addView(mirrorEnabled)
        panel.addView(lightRow)
        panel.addView(doorLight)
        panel.addView(info(activity, "다운미러: P 정차 상태에서 NORMAL과 R-DOWN 각각 0~8 조합을 미리보기로 맞춘 뒤 저장하세요. 기존 -1/44 캡처값은 자동으로 무효 처리됩니다."))
        panel.addView(status)
        root.addView(panel)
    }

'''
text = text[:comfort_start] + new_comfort + text[cluster_start:]

# Replace app audio edit box with installed-app multi-select names.
app_start = text.find('    fun attachAppAudioLab(')
helper_start = text.find('    private fun findFirstGridLayout', app_start)
if app_start < 0 or helper_start < 0:
    raise SystemExit('app audio block not found')
new_app = r'''    fun attachAppAudioLab(activity: AppCompatActivity, rootView: View) {
        val root = rootView as? ViewGroup ?: return
        val panel = panel(activity, "앱별 운전석 오디오 LAB", "패키지명이나 경로를 직접 입력하지 않습니다. 차량에 설치된 실행 가능 앱 이름에서 선택하면 내부적으로 package/UID를 저장합니다. 순정 안전 경고 우선권은 변경하지 않습니다.")
        val status = info(activity, DriverAudioPolicyLab.summary(activity))
        val choose = button(activity, "설치된 앱에서 선택") {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val apps = activity.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    val label = runCatching { ri.loadLabel(activity.packageManager).toString() }.getOrDefault(pkg)
                    Triple(label, pkg, ri.activityInfo?.applicationInfo?.uid ?: -1)
                }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
            val selected = DriverAudioPolicyLab.selectedPackages(activity).toMutableSet()
            val labels = apps.map { (label, pkg, uid) -> "$label  ·  UID $uid" }.toTypedArray()
            val checks = BooleanArray(apps.size) { selected.contains(apps[it].second) }
            AlertDialog.Builder(activity)
                .setTitle("운전석 오디오 대상 앱 선택")
                .setMultiChoiceItems(labels, checks) { _, which, checked ->
                    val pkg = apps[which].second
                    if (checked) selected.add(pkg) else selected.remove(pkg)
                }
                .setPositiveButton("저장") { _, _ ->
                    DriverAudioPolicyLab.saveSelectedPackages(activity, selected)
                    val names = apps.filter { selected.contains(it.second) }.joinToString { it.first }
                    status.text = "선택: ${if (names.isBlank()) "없음" else names}\n${DriverAudioPolicyLab.summary(activity)}"
                }
                .setNegativeButton("취소", null)
                .show()
        }
        panel.addView(choose)
        panel.addView(status)
        root.addView(panel)
    }

'''
text = text[:app_start] + new_app + text[helper_start:]
write(p, text)

print("v30.6.2 runtime fixes applied")
