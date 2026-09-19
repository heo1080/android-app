#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    p = ROOT / rel
    return p, p.read_text(encoding="utf-8")

def write(p, text):
    p.write_text(text, encoding="utf-8")

def one(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)

p, text = read("app/build.gradle.kts")
text = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 49', text, count=1)
text = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "3.1.6-v30.7.2-update-channel"', text, count=1)
write(p, text)

p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/InAppSupertonicTtsManager.kt")
text = one(text,
    '    fun speak(context: Context, text: String): Boolean {\n',
    '    fun speak(context: Context, text: String, sid: Int = 6, speed: Float = 1.05f): Boolean {\n',
    'tts selectable voice signature')
text = one(text,
    '        val cache = phraseCacheFile(app, clean)\n',
    '        val cache = phraseCacheFile(app, clean, sid, speed)\n',
    'tts voice-specific cache lookup')
speak_start = text.find('    fun speak(context: Context, text: String, sid:')
speak_end = text.find('\n    fun prewarmDefaults', speak_start)
segment = text[speak_start:speak_end]
segment = one(segment,
    '                val ok = synthesizeToCache(app, clean, cache)\n',
    '                val ok = synthesizeToCache(app, clean, cache, sid, speed)\n',
    'tts speak synthesis params')
text = text[:speak_start] + segment + text[speak_end:]
text = one(text,
    '    private fun synthesizeToCache(context: Context, text: String, file: File): Boolean {\n',
    '    private fun synthesizeToCache(context: Context, text: String, file: File, sid: Int = 6, speed: Float = 1.05f): Boolean {\n',
    'tts synthesis signature')
text = one(text, '                sid = 6,\n', '                sid = sid.coerceIn(0, 9),\n', 'tts sid')
text = one(text, '                speed = 1.05f,\n', '                speed = speed.coerceIn(0.75f, 1.35f),\n', 'tts speed')
text = one(text,
    '    private fun phraseCacheFile(context: Context, text: String): File {\n        val digest = MessageDigest.getInstance("SHA-256")\n            .digest(text.toByteArray(Charsets.UTF_8))\n',
    '    private fun phraseCacheFile(context: Context, text: String, sid: Int = 6, speed: Float = 1.05f): File {\n        val cacheKey = "$text|sid=$sid|speed=$speed"\n        val digest = MessageDigest.getInstance("SHA-256")\n            .digest(cacheKey.toByteArray(Charsets.UTF_8))\n',
    'tts cache key')
write(p, text)

p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/VoiceAndSoundManager.kt")
bsd_start = text.find('    fun playBlindSpotWarning() {')
bsd_end = text.find('    fun playLaneDepartureWarning()', bsd_start)
if bsd_start < 0 or bsd_end < 0:
    raise SystemExit("BSD function block not found")
new_bsd = '''    fun playBlindSpotWarning(direction: String = "") {
        val state = when (direction.uppercase()) {
            "LEFT" -> "왼쪽"
            "RIGHT" -> "오른쪽"
            else -> "주변"
        }
        dispatchConfiguredAlert(
            AlertOutputProfileManager.EVENT_BSD,
            state,
            "$state 사각지대 경고",
            "$state 사각지대에 차량이 있습니다"
        )
    }

'''
text = text[:bsd_start] + new_bsd + text[bsd_end:]

methods_start = text.find('    fun speakGear(')
methods_end = text.find('    fun speak(text: String)', methods_start)
if methods_start < 0 or methods_end < 0:
    raise SystemExit("voice event method block not found")
new_methods = '''    private suspend fun playConfiguredBeep(preset: AlertOutputProfileManager.BeepPreset) {
        requestAudioFocus()
        try {
            preset.frequencies.forEachIndexed { index, frequency ->
                playPcmTone(frequency, preset.durationMs)
                if (index < preset.frequencies.lastIndex && preset.gapMs > 0) delay(preset.gapMs)
            }
        } finally {
            releaseAudioFocus()
        }
    }

    private fun dispatchConfiguredAlert(
        eventKey: String,
        state: String,
        defaultText: String,
        recommendedText: String
    ) {
        val profile = AlertOutputProfileManager.getProfile(context, eventKey)
        when (profile.mode) {
            AlertOutputProfileManager.MODE_OFF ->
                DolphinLogger.i("ALERT_PROFILE", "$eventKey OFF state=$state")
            AlertOutputProfileManager.MODE_BEEP -> {
                val preset = AlertOutputProfileManager.getBeep(profile.beepId)
                DolphinLogger.i("ALERT_PROFILE", "$eventKey BEEP=${preset.id} state=$state")
                soundScope.launch { playConfiguredBeep(preset) }
            }
            else -> {
                val phrase = AlertOutputProfileManager.resolvePhrase(profile, state, defaultText, recommendedText)
                val voice = AlertOutputProfileManager.resolveVoice(context, profile)
                val accepted = InAppSupertonicTtsManager.speak(context, phrase, voice.sid, voice.speed)
                DolphinLogger.i(
                    "ALERT_PROFILE",
                    "$eventKey TTS voice=${voice.id}/sid=${voice.sid} speed=${voice.speed} accepted=$accepted state=$state"
                )
                if (!accepted) {
                    val fallback = AlertOutputProfileManager.getBeep(profile.beepId)
                    soundScope.launch { playConfiguredBeep(fallback) }
                }
            }
        }
    }

    fun previewConfiguredAlert(eventKey: String) {
        when (eventKey) {
            AlertOutputProfileManager.EVENT_GEAR ->
                dispatchConfiguredAlert(eventKey, "D", "D", "기어 D")
            AlertOutputProfileManager.EVENT_REGEN ->
                dispatchConfiguredAlert(eventKey, "HIGH", "하이", "회생제동 하이")
            AlertOutputProfileManager.EVENT_DRIVE ->
                dispatchConfiguredAlert(eventKey, "NORMAL", "노멀", "노멀 모드입니다")
            AlertOutputProfileManager.EVENT_SNOW ->
                dispatchConfiguredAlert(eventKey, "스노우모드", "스노우모드", "스노우모드를 시작합니다")
            AlertOutputProfileManager.EVENT_AUTOHOLD_SWITCH ->
                dispatchConfiguredAlert(eventKey, "ON", "오토홀드 ON", "오토홀드를 켰습니다")
            AlertOutputProfileManager.EVENT_AUTOHOLD_BRAKE ->
                dispatchConfiguredAlert(eventKey, "체결", "오토홀드 체결 유", "오토홀드가 체결되었습니다")
            AlertOutputProfileManager.EVENT_EPB ->
                dispatchConfiguredAlert(eventKey, "체결", "사이드브레이크 체결 유", "주차 브레이크가 체결되었습니다")
            AlertOutputProfileManager.EVENT_ICC ->
                dispatchConfiguredAlert(eventKey, "ON", "자율주행 ON", "ICC가 켜졌습니다")
            AlertOutputProfileManager.EVENT_BSD ->
                dispatchConfiguredAlert(eventKey, "왼쪽", "왼쪽 사각지대 경고", "왼쪽 사각지대에 차량이 있습니다")
            AlertOutputProfileManager.EVENT_LEADING ->
                dispatchConfiguredAlert(eventKey, "출발", "전방 차량 출발", "전방 차량이 출발했습니다. 안전을 확인하세요")
            AlertOutputProfileManager.EVENT_CHARGING ->
                dispatchConfiguredAlert(eventKey, "시작", "충전 시작", "충전을 시작합니다")
        }
    }

    fun speakGear(gear: String) {
        if (!SettingsManager.isGearVoiceEnabled(context)) return
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_GEAR, gear, SettingsManager.getGearPhrase(context, gear), "기어 $gear")
    }

    fun speakDriveMode(mode: String) {
        if (!SettingsManager.isDriveModeVoiceEnabled(context)) return
        val ko = when (mode) { "ECO" -> "에코"; "NORMAL" -> "노멀"; "SPORT" -> "스포츠"; else -> mode }
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_DRIVE, mode, SettingsManager.getDriveModePhrase(context, mode), "$ko 모드입니다")
    }

    fun speakRegenMode(regen: String) {
        if (!SettingsManager.isRegenModeVoiceEnabled(context)) return
        val ko = if (regen == "HIGH") "하이" else "스탠다드"
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_REGEN, regen, SettingsManager.getRegenModePhrase(context, regen), "회생제동 $ko")
    }

    fun speakSnowMode() {
        if (!SettingsManager.isSnowModeVoiceEnabled(context)) return
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_SNOW, "스노우모드", SettingsManager.getSnowModePhrase(context), "스노우모드를 시작합니다")
    }

    fun speakAutoHoldSwitch(isSwitchOn: Boolean) {
        if (!SettingsManager.isAutoHoldVoiceEnabled(context)) return
        val state = if (isSwitchOn) "ON" else "OFF"
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_AUTOHOLD_SWITCH, state,
            SettingsManager.getAutoHoldSwitchPhrase(context, isSwitchOn),
            if (isSwitchOn) "오토홀드를 켰습니다" else "오토홀드를 껐습니다")
    }

    fun speakAutoHoldBrake(isEngaged: Boolean) {
        if (!SettingsManager.isAutoHoldVoiceEnabled(context)) return
        val state = if (isEngaged) "체결" else "해제"
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_AUTOHOLD_BRAKE, state,
            SettingsManager.getAutoHoldBrakePhrase(context, isEngaged),
            if (isEngaged) "오토홀드가 체결되었습니다" else "오토홀드가 해제되었습니다")
    }

    fun speakAutoHold(isActive: Boolean) = speakAutoHoldBrake(isActive)

    fun speakEpb(isEngaged: Boolean) {
        if (!SettingsManager.isEpbVoiceEnabled(context)) return
        val state = if (isEngaged) "체결" else "해제"
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_EPB, state,
            SettingsManager.getEpbPhrase(context, isEngaged),
            if (isEngaged) "주차 브레이크가 체결되었습니다" else "주차 브레이크가 해제되었습니다")
    }

    fun speakIcc(isActive: Boolean) {
        if (!SettingsManager.isIccVoiceEnabled(context)) return
        val state = if (isActive) "ON" else "OFF"
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_ICC, state,
            SettingsManager.getIccPhrase(context),
            if (isActive) "ICC가 켜졌습니다" else "ICC가 꺼졌습니다")
    }

    fun speakLeadingCarDeparture() {
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_LEADING, "출발",
            SettingsManager.getLeadingCarPhrase(context),
            "전방 차량이 출발했습니다. 안전을 확인하세요")
    }

    fun speakChargingStart() {
        if (!SettingsManager.isChargingVoiceEnabled(context)) return
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_CHARGING, "시작",
            SettingsManager.getChargingStartPhrase(context), "충전을 시작합니다")
    }

    fun speakChargingEnd() {
        if (!SettingsManager.isChargingVoiceEnabled(context)) return
        dispatchConfiguredAlert(AlertOutputProfileManager.EVENT_CHARGING, "종료",
            SettingsManager.getChargingEndPhrase(context), "충전이 종료되었습니다")
    }

    fun speakCharging() = speakChargingStart()

'''
text = text[:methods_start] + new_methods + text[methods_end:]
write(p, text)

p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/service/DolphinService.kt")
text = text.replace('                audioManager.playBlindSpotWarning()\n',
                    '                audioManager.playBlindSpotWarning(direction)\n', 1)
write(p, text)

p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/VehicleVoiceStateMonitor.kt")
text = one(text,
'''    private fun decodeDriveMode(raw: Int): String? = when (raw) {
        0 -> "NORMAL"
        1 -> "ECO"
        2 -> "SPORT"
        else -> null
    }
''',
'''    private fun decodeDriveMode(raw: Int): String? = when (raw) {
        0, 3 -> "NORMAL"
        1 -> "ECO"
        2 -> "SPORT"
        else -> null
    }
''', 'normal mode fallback mapping')

readint_anchor = '    private fun readInt(className: String, methodName: String, vararg args: Int): Int? {\n'
read_double = '''    private fun readDouble(className: String, methodName: String): Double? {
        val key = "$className#$methodName/double"
        return try {
            val instance = devices.getOrPut(className) {
                val clazz = Class.forName(className)
                clazz.getMethod("getInstance", Context::class.java)
                    .invoke(null, BydPermissionContext.wrap(appContext))
                    ?: error("getInstance returned null")
            }
            val method = methods.getOrPut(key) { instance.javaClass.getMethod(methodName) }
            (method.invoke(instance) as? Number)?.toDouble()
        } catch (t: Throwable) {
            methods.remove(key)
            reportError(key, t.cause ?: t)
            null
        }
    }

'''
text = one(text, readint_anchor, read_double + readint_anchor, 'double reader')

auto_start = text.find('        // getAVHState() on the tested Dolphin behaves as the AVH switch state.')
auto_end = text.find('    private fun sampleIcc() {', auto_start)
if auto_start < 0 or auto_end < 0:
    raise SystemExit("v30.6.3 AutoHold inference block not found")
new_auto = '''        // Hybrid AVH state handling for the Korean Dolphin.
        val gear = readInt(GEARBOX_CLASS, "getCurrentGear")
        val speed = readDouble(SPEED_CLASS, "getCurrentSpeed")
        val brakeDepth = readInt(SPEED_CLASS, "getBrakeDeepness")
        val accelDepth = readInt(SPEED_CLASS, "getAccelerateDeepness")
        DolphinLogger.d(TAG, "AVH raw=$raw prev=$previous gear=$gear speed=$speed brake=$brakeDepth accel=$accelDepth holding=$autoHoldInferredHolding")

        val switchEnabled = raw == 1 || raw == 2
        val explicitHolding = raw == 2
        val moving = (speed ?: 0.0) > 0.5 || (accelDepth ?: 0) > 0

        if (!switchEnabled) {
            autoHoldBrakeWasPressed = false
            if (autoHoldInferredHolding) {
                autoHoldInferredHolding = false
                DolphinLogger.i(TAG, "AutoHold RELEASE by switch/raw=$raw")
                main.post { onAutoHoldBrakeChanged(false) }
            }
            return
        }

        if (explicitHolding) {
            autoHoldBrakeWasPressed = false
            if (!autoHoldInferredHolding) {
                autoHoldInferredHolding = true
                DolphinLogger.i(TAG, "AutoHold HOLD explicit raw=$raw")
                main.post { onAutoHoldBrakeChanged(true) }
            }
            return
        }

        if (autoHoldInferredHolding) {
            if (previous == 2 || moving || gear != GEAR_D) {
                autoHoldInferredHolding = false
                autoHoldBrakeWasPressed = false
                DolphinLogger.i(TAG, "AutoHold RELEASE raw=$raw prev=$previous gear=$gear speed=$speed accel=$accelDepth")
                main.post { onAutoHoldBrakeChanged(false) }
            }
            return
        }

        val stationaryD = gear == GEAR_D && speed != null && speed <= 0.3
        if (stationaryD && (brakeDepth ?: 0) > 0) {
            autoHoldBrakeWasPressed = true
            return
        }
        if (stationaryD && autoHoldBrakeWasPressed && (brakeDepth ?: 0) == 0 && (accelDepth ?: 0) == 0) {
            autoHoldBrakeWasPressed = false
            autoHoldInferredHolding = true
            DolphinLogger.i(TAG, "AutoHold HOLD inferred fallback speed=$speed")
            main.post { onAutoHoldBrakeChanged(true) }
            return
        }
        if (!stationaryD || moving) autoHoldBrakeWasPressed = false
    }

'''
text = text[:auto_start] + new_auto + text[auto_end:]
write(p, text)

p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/LeadingVehicleDepartureEngine.kt")
old_gate = '''        if (!SettingsManager.isLeadingCarVoiceEnabled(appContext) ||
            !SettingsManager.isExperimentalLvdaEnabled(appContext) || !vehicleStationary
        ) {
            resetTarget()
            return
        }
'''
new_gate = '''        val outputMode = AlertOutputProfileManager
            .getProfile(appContext, AlertOutputProfileManager.EVENT_LEADING)
            .mode
        if (outputMode == AlertOutputProfileManager.MODE_OFF || !vehicleStationary) {
            resetTarget()
            return
        }
'''
text = one(text, old_gate, new_gate, 'leading vehicle unified profile gate')
text = text.replace('private const val LOCK_MAX_CM = 140',
                    'private const val LOCK_MAX_CM = 154', 1)
write(p, text)

checks = {
    "versionCode49": "versionCode = 49" in (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"),
    "audioProfileDispatch": "dispatchConfiguredAlert" in (ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/VoiceAndSoundManager.kt").read_text(encoding="utf-8"),
    "selectableSupertonic": "sid: Int = 6, speed: Float = 1.05f" in (ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/InAppSupertonicTtsManager.kt").read_text(encoding="utf-8"),
    "autoholdHybrid": "AutoHold HOLD explicit" in (ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/VehicleVoiceStateMonitor.kt").read_text(encoding="utf-8"),
    "leadingUnified": "EVENT_LEADING" in (ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/LeadingVehicleDepartureEngine.kt").read_text(encoding="utf-8"),
}
failed = [k for k, ok in checks.items() if not ok]
if failed:
    raise SystemExit("v30.7.1 verification failed: " + ", ".join(failed))
print("v30.7.1 audio profiles + live fixes applied")
for k in checks:
    print("  OK " + k)
