#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Version: this script runs after v30.5.4, which produces code 42 / 3.0.12.
# ---------------------------------------------------------------------------
build_path = ROOT / "app/build.gradle.kts"
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "versionCode = 42", "versionCode = 44", "versionCode")
build = replace_once(
    build,
    'versionName = "3.0.12-v30.5.4-api29-updater-fix"',
    'versionName = "3.1.1-v30.6.1-visible-menu-fix"',
    "versionName",
)
build_path.write_text(build, encoding="utf-8")

# ---------------------------------------------------------------------------
# Natural HD cache -> verified stream14; local TTS remains the cache-miss fallback.
# ---------------------------------------------------------------------------
voice_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/VoiceAndSoundManager.kt"
voice = voice_path.read_text(encoding="utf-8")
voice = replace_once(
    voice,
    '''    fun speak(text: String) {\n        if (text.isBlank()) return\n''',
    '''    fun speak(text: String) {\n        if (text.isBlank()) return\n        if (NaturalVoiceCacheManager.playCachedOrPrepare(context.applicationContext, text)) {\n            DolphinLogger.i("AUDIO", "v30.6 natural voice cache HIT -> direct legacy stream14 len=${text.length}")\n            return\n        }\n''',
    "natural voice speak hook",
)
voice_path.write_text(voice, encoding="utf-8")

# ---------------------------------------------------------------------------
# Gear/motion hooks for calibrated reverse mirror memory + voice prewarm.
# ---------------------------------------------------------------------------
service_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/service/DolphinService.kt"
service = service_path.read_text(encoding="utf-8")
service = replace_once(
    service,
    '''        previousGear = normalized\n        DolphinLogger.i("GEAR", "기어 변속: $prev -> $normalized, 속도=$speed")\n\n        audioManager.speakGear(normalized)\n''',
    '''        previousGear = normalized\n        DolphinLogger.i("GEAR", "기어 변속: $prev -> $normalized, 속도=$speed")\n        MirrorMemoryManager.onGearChanged(this, normalized, speed)\n\n        audioManager.speakGear(normalized)\n''',
    "mirror gear hook",
)
service = replace_once(
    service,
    '''            onMotion = { speed, gear ->\n                currentSpeed = speed\n                lvdaEngine.updateVehicleSpeedAndGear(speed, gear)\n''',
    '''            onMotion = { speed, gear ->\n                currentSpeed = speed\n                MirrorMemoryManager.onVehicleMotion(this, speed, gear)\n                lvdaEngine.updateVehicleSpeedAndGear(speed, gear)\n''',
    "mirror manual override motion hook",
)
service = replace_once(
    service,
    '''        audioManager = VoiceAndSoundManager(this)\n        hazardManager = HazardLightManager(this)\n''',
    '''        audioManager = VoiceAndSoundManager(this)\n        NaturalVoiceCacheManager.prewarmDefaults(this)\n        hazardManager = HazardLightManager(this)\n''',
    "natural voice prewarm",
)
service_path.write_text(service, encoding="utf-8")

# ---------------------------------------------------------------------------
# Add v30.6 labs to the structured diagnostic ZIP automatically.
# ---------------------------------------------------------------------------
diag_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/DiagnosticCaptureManager.kt"
diag = diag_path.read_text(encoding="utf-8")
diag = replace_once(
    diag,
    '''            runCatching { writeInitialVehicleSnapshot(appContext, session) }\n                .onFailure { appendFailure(session, "vehicle", "initial_snapshot", it) }\n''',
    '''            runCatching {\n                DisplayDiagnosticsManager.writeSnapshot(appContext, session.directory)\n                VehicleFeatureLabManager.writeSnapshot(appContext, session.directory)\n                NaturalVoiceCacheManager.writeStatusSnapshot(appContext, session.directory)\n                DriverAudioPolicyLab.writeSnapshot(appContext, session.directory)\n                appendEvent(session, "v30_6", "integrated_lab_snapshots_written", force = true)\n            }.onFailure { appendFailure(session, "v30_6", "integrated_lab_snapshots", it) }\n            runCatching { writeInitialVehicleSnapshot(appContext, session) }\n                .onFailure { appendFailure(session, "vehicle", "initial_snapshot", it) }\n''',
    "diagnostic lab hook",
)
diag_path.write_text(diag, encoding="utf-8")

# ---------------------------------------------------------------------------
# Interior-light OFF fallback: runtime inventory confirms both int and no-arg overloads.
# ---------------------------------------------------------------------------
light_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/InsideLightManager.kt"
light = light_path.read_text(encoding="utf-8")
light = replace_once(
    light,
    '''            val method = clazz.getMethod("turnOffInsideLight", Int::class.javaPrimitiveType)\n            val result = (method.invoke(instance, param) as? Number)?.toInt()\n            Log.d(TAG, "BYDAutoSettingDevice.turnOffInsideLight($param) 성공, 반환값: $result")\n            result == 0\n''',
    '''            val method = clazz.getMethod("turnOffInsideLight", Int::class.javaPrimitiveType)\n            val result = (method.invoke(instance, param) as? Number)?.toInt()\n            Log.d(TAG, "BYDAutoSettingDevice.turnOffInsideLight($param) 반환값: $result")\n            if (result == 0) {\n                true\n            } else if (param == PARAM_LIGHT_OFF) {\n                val noArgResult = runCatching {\n                    (clazz.getMethod("turnOffInsideLight").invoke(instance) as? Number)?.toInt()\n                }.getOrNull()\n                DolphinLogger.i(TAG, "실내등 OFF no-arg fallback result=$noArgResult")\n                noArgResult == 0\n            } else {\n                false\n            }\n''',
    "inside light noarg fallback",
)
light_path.write_text(light, encoding="utf-8")

# ---------------------------------------------------------------------------
# v30.6.1 visible integrated menu.
# v30.6.0 appended panels after full-height legacy ScrollViews, leaving them off-screen.
# A dedicated dashboard card now opens all new controls in its own scrollable dialog.
# ---------------------------------------------------------------------------
main_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt"
main = main_path.read_text(encoding="utf-8")
main = replace_once(
    main,
    '''        setupDpiAdbSubScreen()\n\n        // 1. 앱 실행 즉시 백그라운드 서비스 및 플로팅 독 가동\n''',
    '''        setupDpiAdbSubScreen()\n\n        IntegratedBetaUi.attachDashboardEntry(this, layoutMainDashboard, audioManager)\n\n        // 1. 앱 실행 즉시 백그라운드 서비스 및 플로팅 독 가동\n''',
    "v30.6.1 visible integrated menu hook",
)
main_path.write_text(main, encoding="utf-8")

checks = {
    "version": '3.1.1-v30.6.1-visible-menu-fix' in build_path.read_text(encoding="utf-8"),
    "natural cache hook": "NaturalVoiceCacheManager.playCachedOrPrepare" in voice_path.read_text(encoding="utf-8"),
    "mirror gear hook": "MirrorMemoryManager.onGearChanged" in service_path.read_text(encoding="utf-8"),
    "mirror motion hook": "MirrorMemoryManager.onVehicleMotion" in service_path.read_text(encoding="utf-8"),
    "display diagnostics": "DisplayDiagnosticsManager.writeSnapshot" in diag_path.read_text(encoding="utf-8"),
    "feature diagnostics": "VehicleFeatureLabManager.writeSnapshot" in diag_path.read_text(encoding="utf-8"),
    "visible integrated menu": "IntegratedBetaUi.attachDashboardEntry" in main_path.read_text(encoding="utf-8"),
    "inside light fallback": "OFF no-arg fallback" in light_path.read_text(encoding="utf-8"),
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("v30.6.1 sanity check failed: " + ", ".join(failed))

print("v30.6.1 visible-menu patch applied: " + ", ".join(checks))
