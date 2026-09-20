from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
build_path = ROOT / "app/build.gradle.kts"
strings_path = ROOT / "app/src/main/res/values/strings.xml"

manifest = manifest_path.read_text(encoding="utf-8")

launcher = """        <activity
            android:name=".LauncherActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="landscape"
            android:resizeableActivity="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
"""

command_pattern = re.compile(
    r'<activity\s+android:name="\.CommandCenterActivity"\b.*?</activity>',
    re.DOTALL,
)
command_disabled = """        <activity
            android:name=".CommandCenterActivity"
            android:exported="false" />
"""
manifest, count = command_pattern.subn(launcher + "\n" + command_disabled, manifest, count=1)

if count != 1:
    main_pattern = re.compile(
        r'<activity\s+android:name="\.MainActivity"\b.*?</activity>',
        re.DOTALL,
    )
    main_disabled = """        <activity
            android:name=".MainActivity"
            android:exported="false" />
"""
    manifest, count = main_pattern.subn(launcher + "\n" + main_disabled, manifest, count=1)

if count != 1:
    raise SystemExit("Could not replace legacy launcher activity block")

if 'android.intent.category.HOME' not in manifest:
    raise SystemExit("HOME intent filter missing after manifest swap")

manifest_path.write_text(manifest, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build, count_code = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 115', build, count=1)
build, count_name = re.subn(
    r'versionName\s*=\s*"[^"]+"',
    'versionName = "8.0.0-v35.0.0-launcher-evolution-alpha1"',
    build,
    count=1,
)
if count_code != 1 or count_name != 1:
    raise SystemExit("Could not set Launcher Evolution package version")
build_path.write_text(build, encoding="utf-8")

strings = strings_path.read_text(encoding="utf-8")
strings, count_label = re.subn(
    r'<string name="app_name">.*?</string>',
    '<string name="app_name">BYD Launcher Evolution</string>',
    strings,
    count=1,
)
if count_label != 1:
    raise SystemExit("Could not update app_name")
strings_path.write_text(strings, encoding="utf-8")

launcher_src = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/LauncherActivity.kt"
if not launcher_src.exists():
    raise SystemExit("LauncherActivity.kt is missing")
source = launcher_src.read_text(encoding="utf-8")
for needle in (
    "class LauncherActivity",
    "showAppDrawer",
    "launchConfiguredSplit",
    "launchPopup",
    "SettingsManager.addBootApp",
    "AppUpdateManager.checkForUpdates",
):
    if needle not in source:
        raise SystemExit("Launcher source missing: " + needle)

print("BYD Launcher Evolution v35 alpha1 patch applied")
