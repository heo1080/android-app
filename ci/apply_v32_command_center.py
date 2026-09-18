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
    if new in text:
        return text
    if text.count(old) != 1:
        raise SystemExit(f"{label}: expected one anchor, found {text.count(old)}")
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# Version: v32.1.0 command center + Surrounding Vision + nav safety overlay.
# ---------------------------------------------------------------------------
p, text = read("app/build.gradle.kts")
text = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 47', text, count=1)
text = re.sub(
    r'versionName\s*=\s*"[^"]+"',
    'versionName = "3.2.1-v32.1.0-command-center"',
    text,
    count=1,
)
write(p, text)

# ---------------------------------------------------------------------------
# MainActivity remains the mature feature host. New CommandCenter deep-links
# directly to one old panel so users never need the obsolete 10-card dashboard.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt")

old_back = '''    override fun onBackPressed() {
        if (layoutMainDashboard.visibility != View.VISIBLE) {
            showMainDashboard()
        } else {
            super.onBackPressed()
        }
    }
'''
new_back = '''    override fun onBackPressed() {
        if (intent.hasExtra("open_panel")) {
            finish()
            return
        }
        if (layoutMainDashboard.visibility != View.VISIBLE) {
            showMainDashboard()
        } else {
            super.onBackPressed()
        }
    }
'''
text = one(text, old_back, new_back, "MainActivity command-center back routing")

anchor = '''        setupBootSchedulerSubScreen()
        setupDpiAdbSubScreen()

        // 1. 앱 실행 즉시 백그라운드 서비스 및 플로팅 독 가동
'''
replacement = '''        setupBootSchedulerSubScreen()
        setupDpiAdbSubScreen()
        openRequestedPanel(intent.getStringExtra("open_panel"))

        // 1. 앱 실행 즉시 백그라운드 서비스 및 플로팅 독 가동
'''
text = one(text, anchor, replacement, "MainActivity open requested panel")

method_anchor = '''    private fun performAutoAdbGrant() {
'''
method_block = '''    private fun openRequestedPanel(panel: String?) {
        if (panel.isNullOrBlank()) return
        when (panel) {
            "split" -> showSubScreen(subLayoutSplit)
            "comfort" -> showSubScreen(subLayoutComfort)
            "voice" -> showSubScreen(subLayoutVoice)
            "safety" -> showSubScreen(subLayoutSafetyAudio)
            "hud" -> showSubScreen(subLayoutHud)
            "cluster" -> showSubScreen(subLayoutCluster)
            "button" -> showSubScreen(subLayoutButtonBuilder)
            "automation" -> showSubScreen(subLayoutAutomation)
            "boot" -> showSubScreen(subLayoutBootScheduler)
            "dpi" -> showSubScreen(subLayoutDpiAdb)
            else -> {
                DolphinLogger.w("MainActivity", "Unknown command-center panel: " + panel)
                showMainDashboard()
            }
        }
    }

    private fun performAutoAdbGrant() {
'''
text = one(text, method_anchor, method_block, "MainActivity panel router")
write(p, text)

# ---------------------------------------------------------------------------
# Navigation safety data -> DriveSafetyBus. This reuses the existing supported
# TMAP/Naver/Kakao/iNavi notification ingestion, no fabricated online database.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/hud/NavGuidanceParser.kt")
if "com.byd.dolphin.autoassistant.drive.DriveSafetyBus" not in text:
    text = one(
        text,
        "import com.byd.dolphin.autoassistant.manager.SettingsManager\n",
        "import com.byd.dolphin.autoassistant.manager.SettingsManager\n"
        "import com.byd.dolphin.autoassistant.drive.DriveSafetyBus\n",
        "NavGuidanceParser DriveSafetyBus import",
    )

text = one(
    text,
    '''        val combined = "$title $text $subText"

        var turnDistance = 0
''',
    '''        val combined = "$title $text $subText"
        DriveSafetyBus.updateFromNavigation(pkg, title, text, subText)

        var turnDistance = 0
''',
    "NavGuidanceParser safety feed",
)

text = one(
    text,
    '''    fun clear(context: Context) {
        ClusterMirrorManager.clearClusterTbt(context)
''',
    '''    fun clear(context: Context) {
        ClusterMirrorManager.clearClusterTbt(context)
        DriveSafetyBus.clear()
''',
    "NavGuidanceParser safety clear",
)
write(p, text)

# ---------------------------------------------------------------------------
# Launcher: new six-area command center. MainActivity becomes internal host.
# ---------------------------------------------------------------------------
p, text = read("app/src/main/AndroidManifest.xml")
old_activity = '''        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
'''
new_activity = '''        <activity
            android:name=".CommandCenterActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".drive.DriveVisionActivity"
            android:exported="false" />

        <activity
            android:name=".MainActivity"
            android:exported="false" />
'''
text = one(text, old_activity, new_activity, "AndroidManifest launcher swap")
write(p, text)

print("v32.1.0 command-center / surrounding-vision patch applied")
