#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
build_path = ROOT / "app/build.gradle.kts"
main_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt"
manifest_path = ROOT / "app/src/main/AndroidManifest.xml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# Version bump after the v30.5.1 patch has run.
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "versionCode = 39", "versionCode = 40", "versionCode 40")
build = replace_once(
    build,
    'versionName = "3.0.9-v30.5.1-auto-diagnostic-upload"',
    'versionName = "3.0.10-v30.5.2-auto-update"',
    "versionName v30.5.2",
)
build_path.write_text(build, encoding="utf-8")

# Android 8+ package installer permission. Final install still requires user confirmation.
manifest = manifest_path.read_text(encoding="utf-8")
if 'android.permission.REQUEST_INSTALL_PACKAGES' not in manifest:
    manifest = replace_once(
        manifest,
        '    <uses-permission android:name="android.permission.INTERNET" />\n',
        '    <uses-permission android:name="android.permission.INTERNET" />\n'
        '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n',
        "REQUEST_INSTALL_PACKAGES",
    )
manifest_path.write_text(manifest, encoding="utf-8")

main = main_path.read_text(encoding="utf-8")

# Automatic update check every time the main app starts. Network failures stay silent in auto mode.
main = replace_once(
    main,
    '''        performAutoAdbGrant()\n    }\n''',
    '''        performAutoAdbGrant()\n\n        // v30.5.2: on launch, quietly check GitHub Release and prompt only when a newer build exists.\n        AppUpdateManager.checkForUpdates(this, manual = false)\n    }\n''',
    "launch update check",
)

# Add a manual update button to the existing diagnostic/cloud settings panel.
main = replace_once(
    main,
    '''        uploadPanel.addView(tvDiagnosticUploadStatus)\n''',
    '''        uploadPanel.addView(tvDiagnosticUploadStatus)\n\n        val btnCheckAppUpdate = Button(this).apply {\n            text = "⬇ 업데이트 확인 · 현재 ${BuildConfig.VERSION_NAME}"\n            setTextColor(Color.WHITE)\n            textSize = 12f\n            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32"))\n        }\n        uploadPanel.addView(btnCheckAppUpdate)\n        btnCheckAppUpdate.setOnClickListener {\n            AppUpdateManager.checkForUpdates(this, manual = true)\n        }\n''',
    "manual update button",
)

checks = {
    "version 40": 'versionCode = 40' in build,
    "v30.5.2 name": '3.0.10-v30.5.2-auto-update' in build,
    "install permission": 'android.permission.REQUEST_INSTALL_PACKAGES' in manifest,
    "launch check": 'AppUpdateManager.checkForUpdates(this, manual = false)' in main,
    "manual update": 'AppUpdateManager.checkForUpdates(this, manual = true)' in main,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("v30.5.2 updater sanity check failed: " + ", ".join(failed))

main_path.write_text(main, encoding="utf-8")
print("v30.5.2 updater patch applied")
