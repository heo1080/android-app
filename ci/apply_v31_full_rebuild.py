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

# Version 31 must update over v30.7.2 (versionCode 49).
p, text = read("app/build.gradle.kts")
text = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 50', text, count=1)
text = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "4.0.0-v31.0.0-full-rebuild"', text, count=1)
write(p, text)

# Replace the v30.7 overlay shell with the new single visible root.
p, main = read("app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt")
main = one(
    main,
    "        V307UiShell.attach(this, audioManager)\n",
    "        V31RootUi.attach(this, audioManager)\n",
    "v31 root hook",
)

old_back = '''    override fun onBackPressed() {
        if (layoutMainDashboard.visibility != View.VISIBLE) {
            showMainDashboard()
        } else {
            super.onBackPressed()
        }
    }
'''
new_back = '''    override fun onBackPressed() {
        if (V31RootUi.handleBack(this)) return
        super.onBackPressed()
    }
'''
main = one(main, old_back, new_back, "v31 back routing")

resume_anchor = '''    override fun onResume() {
        super.onResume()
        updateDashboardCards()
'''
resume_new = '''    override fun onResume() {
        super.onResume()
        updateDashboardCards()
        V31RootUi.refresh(this)
'''
main = one(main, resume_anchor, resume_new, "v31 resume refresh")
write(p, main)

# v31 AlertOutputProfileManager is the only owner of OFF/BEEP/TTS state.
# Remove hidden legacy voice-enable gates so invisible old settings cannot suppress
# the new canonical audio configuration.
p, audio = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/VoiceAndSoundManager.kt")
for legacy_gate in (
    '        if (!SettingsManager.isGearVoiceEnabled(context)) return\n',
    '        if (!SettingsManager.isDriveModeVoiceEnabled(context)) return\n',
    '        if (!SettingsManager.isRegenModeVoiceEnabled(context)) return\n',
    '        if (!SettingsManager.isSnowModeVoiceEnabled(context)) return\n',
    '        if (!SettingsManager.isAutoHoldVoiceEnabled(context)) return\n',
    '        if (!SettingsManager.isEpbVoiceEnabled(context)) return\n',
    '        if (!SettingsManager.isIccVoiceEnabled(context)) return\n',
    '        if (!SettingsManager.isChargingVoiceEnabled(context)) return\n',
):
    audio = audio.replace(legacy_gate, "")
write(p, audio)

checks = {
    "version": "versionCode = 50" in (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"),
    "versionName": "v31.0.0-full-rebuild" in (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"),
    "rootHook": "V31RootUi.attach(this, audioManager)" in (ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt").read_text(encoding="utf-8"),
    "oldShellRemoved": "V307UiShell.attach(this, audioManager)" not in (ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt").read_text(encoding="utf-8"),
    "audioCanonical": "dispatchConfiguredAlert" in (ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/VoiceAndSoundManager.kt").read_text(encoding="utf-8"),
    "v31Source": (ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/V31RootUi.kt").exists(),
}
failed = [k for k, ok in checks.items() if not ok]
if failed:
    raise SystemExit("v31 verification failed: " + ", ".join(failed))

print("v31 full UI rebuild applied")
for key in checks:
    print("  OK " + key)
