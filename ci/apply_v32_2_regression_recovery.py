#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    p = ROOT / rel
    return p, p.read_text(encoding="utf-8")

def write(p, text):
    p.write_text(text, encoding="utf-8")

def require(text, needle, label):
    if needle not in text:
        raise SystemExit(f"{label}: missing required guard: {needle}")

# Final recovery version is applied after every historical compatibility patch.
p, text = read("app/build.gradle.kts")
text = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 48', text, count=1)
text = re.sub(
    r'versionName\s*=\s*"[^"]+"',
    'versionName = "3.2.2-v32.2.0-regression-recovery"',
    text,
    count=1,
)
write(p, text)

# Do not allow the proven live drive/regen mappings to silently regress again.
p, voice = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/VehicleVoiceStateMonitor.kt")
for needle, label in [
    ('readInt(ENERGY_CLASS, "getOperationMode")', "drive mode API"),
    ('0 -> "NORMAL"', "NORMAL raw mapping"),
    ('1 -> "ECO"', "ECO raw mapping"),
    ('2 -> "SPORT"', "SPORT raw mapping"),
    ('private const val ENERGY_FB_STANDARD = 2', "regen STANDARD raw mapping"),
    ('private const val ENERGY_FB_HIGH = 3', "regen HIGH raw mapping"),
]:
    require(voice, needle, label)

# Fix the stale wording/pref key that made STANDARD announce as ECO.
p, settings = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/SettingsManager.kt")
if 'private const val KEY_PHRASE_REGEN_STANDARD = "key_phrase_regen_standard"' not in settings:
    settings = settings.replace(
        'private const val KEY_PHRASE_REGEN_ECO = "key_phrase_regen_eco"',
        'private const val KEY_PHRASE_REGEN_STANDARD = "key_phrase_regen_standard"',
    )
settings = settings.replace(
'''    fun getRegenModePhrase(context: Context, regen: String): String {
        val prefs = getPrefs(context)
        return if (regen.contains("HIGH", ignoreCase = true)) {
            prefs.getString(KEY_PHRASE_REGEN_HIGH, "회생제동 하이") ?: "회생제동 하이"
        } else {
            prefs.getString(KEY_PHRASE_REGEN_ECO, "회생제동 에코") ?: "회생제동 에코"
        }
    }
    fun setRegenModePhrase(context: Context, regen: String, phrase: String) {
        val key = if (regen.contains("HIGH", ignoreCase = true)) KEY_PHRASE_REGEN_HIGH else KEY_PHRASE_REGEN_ECO
        getPrefs(context).edit().putString(key, phrase).apply()
    }
''',
'''    fun getRegenModePhrase(context: Context, regen: String): String {
        val prefs = getPrefs(context)
        return if (regen.contains("HIGH", ignoreCase = true)) {
            prefs.getString(KEY_PHRASE_REGEN_HIGH, "회생제동 하이") ?: "회생제동 하이"
        } else {
            prefs.getString(KEY_PHRASE_REGEN_STANDARD, "회생제동 스탠다드") ?: "회생제동 스탠다드"
        }
    }
    fun setRegenModePhrase(context: Context, regen: String, phrase: String) {
        val key = if (regen.contains("HIGH", ignoreCase = true)) KEY_PHRASE_REGEN_HIGH else KEY_PHRASE_REGEN_STANDARD
        getPrefs(context).edit().putString(key, phrase).apply()
    }
''',
)
require(settings, 'KEY_PHRASE_REGEN_STANDARD', "regen STANDARD preference")
if 'KEY_PHRASE_REGEN_ECO_LEGACY' not in settings and 'KEY_PHRASE_REGEN_ECO' in settings:
    raise SystemExit("stale regen ECO preference survived recovery patch")
write(p, settings)

# Fix the legacy editor itself so the UI no longer writes STANDARD into an ECO-named slot.
p, main = read("app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt")
main = main.replace(
    'setupVoiceEditButton(R.id.btnVoiceRegenMode, "회생 제동", "회생제동 에코", "회생제동 감속 제어가 적용되었습니다.") { SettingsManager.getRegenModePhrase(this, "ECO") }',
    'setupVoiceEditButton(R.id.btnVoiceRegenMode, "회생 제동 STANDARD", "회생제동 스탠다드", "회생제동 스탠다드 모드입니다.") { SettingsManager.getRegenModePhrase(this, "STANDARD") }',
)
main = main.replace(
    'R.id.btnVoiceRegenMode -> SettingsManager.setRegenModePhrase(this, "ECO", phrase)',
    'R.id.btnVoiceRegenMode -> SettingsManager.setRegenModePhrase(this, "STANDARD", phrase)',
)
require(main, 'getRegenModePhrase(this, "STANDARD")', "regen editor read key")
require(main, 'setRegenModePhrase(this, "STANDARD", phrase)', "regen editor write key")

# Direct command-center entry to the integrated app-audio lab.
p_ui, beta = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/IntegratedBetaUi.kt")
beta = beta.replace(
    "    private fun showIntegratedDialog(activity: AppCompatActivity, audioManager: VoiceAndSoundManager) {",
    "    fun showIntegratedDialog(activity: AppCompatActivity, audioManager: VoiceAndSoundManager) {",
)
require(beta, "fun showIntegratedDialog(activity: AppCompatActivity", "integrated lab public entry")
write(p_ui, beta)

router_anchor = '            "dpi" -> showSubScreen(subLayoutDpiAdb)\n'
require(main, router_anchor, "v32 panel router")
if '"audio_lab" -> IntegratedBetaUi.showIntegratedDialog(this, audioManager)' not in main:
    main = main.replace(
        router_anchor,
        router_anchor + '            "audio_lab" -> IntegratedBetaUi.showIntegratedDialog(this, audioManager)\n',
        1,
    )

# Regression guards for the features the user reported missing from the visible UI.
for needle, label in [
    ("btnAddBootApp", "multi-app boot Add button"),
    ("showDualCreationOptionDialog", "app-drawer shortcut creation"),
    ("SplitScreenManager.launchSplitScreen", "split-screen execution"),
]:
    require(main, needle, label)
write(p, main)

drawer = (ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/AppDrawerShortcutManager.kt").read_text(encoding="utf-8")
require(drawer, "const val SLOT_COUNT = 16", "16 app-drawer launcher slots")
require(drawer, "setComponentEnabledSetting", "real launcher component enable path")

boot = (ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/BootAutomationController.kt").read_text(encoding="utf-8")
require(boot, "SettingsManager.getBootAppList", "multi-app boot list")
require(boot, "items.forEach", "multi-app schedule loop")

print("v32.2 regression recovery guards applied")
