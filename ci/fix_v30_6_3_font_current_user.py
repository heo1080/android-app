#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/DisplayDiagnosticsManager.kt"
text = path.read_text(encoding="utf-8")

start = text.find("    fun setFontScale(context: Context, scale: Float): Boolean {")
end = text.find("\n    fun resetFontScale", start)
if start < 0 or end < 0:
    raise SystemExit("setFontScale block not found")

replacement = '''    fun setFontScale(context: Context, scale: Float): Boolean {
        if (!scale.isFinite() || scale !in 0.85f..1.30f) return false
        val normalized = "%.2f".format(java.util.Locale.US, scale)
        val currentUserResult = NativeAdbClient.executeShell(context.applicationContext, "am get-current-user")
        val currentUser = currentUserResult.output.trim().lineSequence().lastOrNull()?.trim()
            ?.takeIf { it.matches(Regex("\\d+")) }
            ?: "current"
        val before = NativeAdbClient.executeShell(
            context.applicationContext,
            "settings --user $currentUser get system font_scale"
        )
        val result = NativeAdbClient.executeShell(
            context.applicationContext,
            "settings --user $currentUser put system font_scale $normalized"
        )
        val after = NativeAdbClient.executeShell(
            context.applicationContext,
            "settings --user $currentUser get system font_scale"
        )
        DolphinLogger.i(
            TAG,
            "fontScale user=$currentUser target=$normalized success=${result.success} " +
                "before=${before.output.trim()} after=${after.output.trim()} exit=${result.exitCode}"
        )
        if (result.success) {
            // AOSP ActivityTaskManager observes FONT_SCALE for the current user.
            // The previous protected CONFIGURATION_CHANGED broadcast was rejected
            // by DiLink 3, so only recreate our own activity here.
            Handler(Looper.getMainLooper()).postDelayed({
                (context as? Activity)?.recreate()
            }, 650L)
        }
        return result.success
    }
'''

text = text[:start] + replacement + text[end:]
path.write_text(text, encoding="utf-8")
print("v30.6.3 font scale current-user fix applied")
