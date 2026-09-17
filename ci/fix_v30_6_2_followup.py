#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_file(rel: str, transform):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    new = transform(text)
    if new == text:
        raise SystemExit(f"{rel}: follow-up patch made no changes")
    path.write_text(new, encoding="utf-8")


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


# Interior lights: on this vehicle the no-arg turnOffInsideLight() method exists.
# Call it first for OFF, because the int overload can report success without
# physically switching the lamps on some DiLink 3 builds.
def fix_light(text: str) -> str:
    text = one(
        text,
        '''    fun turnOff(context: Context, showToast: Boolean = true): Boolean {
        val success = invokeBydInsideLight(context, PARAM_LIGHT_OFF)
''',
        '''    fun turnOff(context: Context, showToast: Boolean = true): Boolean {
        val success = invokeNoArgInsideLightOff(context) || invokeBydInsideLight(context, PARAM_LIGHT_OFF)
''',
        "inside light OFF entry",
    )
    anchor = '''    /**
     * BYD AutoSettingDevice를 리플렉션으로 호출하여 실내등 점등/소등 실행
     */
    private fun invokeBydInsideLight(context: Context, param: Int): Boolean {
'''
    helper = '''    /** Runtime inventory on the target car exposes a dedicated no-arg OFF command. */
    private fun invokeNoArgInsideLightOff(context: Context): Boolean {
        return runCatching {
            val clazz = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
            val instance = clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, BydPermissionContext.wrap(context))
                ?: error("BYDAutoSettingDevice.getInstance returned null")
            val result = (clazz.getMethod("turnOffInsideLight").invoke(instance) as? Number)?.toInt()
            DolphinLogger.i(TAG, "turnOffInsideLight() direct result=$result")
            result == 0
        }.onFailure {
            DolphinLogger.w(TAG, "turnOffInsideLight() direct failed: ${it.javaClass.simpleName}:${it.message}")
        }.getOrDefault(false)
    }

''' + anchor
    return one(text, anchor, helper, "inside light direct helper")

patch_file("app/src/main/java/com/byd/dolphin/autoassistant/manager/InsideLightManager.kt", fix_light)


# font_scale was confirmed written by shell in the 20:27 car log. Force the
# CONFIGURATION_CHANGED broadcast and recreate DolphinAssistant's foreground
# activity so sp-based text visibly resizes immediately.
def fix_display(text: str) -> str:
    if "import android.app.Activity" not in text:
        text = text.replace(
            "import android.content.Context\n",
            "import android.app.Activity\nimport android.content.Context\nimport android.os.Handler\nimport android.os.Looper\n",
            1,
        )
    return one(
        text,
        '''        val result = NativeAdbClient.executeShell(context.applicationContext, command)
        DolphinLogger.i(TAG, "fontScale set=$scale success=${result.success} exit=${result.exitCode}")
        return result.success
''',
        '''        val result = NativeAdbClient.executeShell(context.applicationContext, command)
        DolphinLogger.i(TAG, "fontScale set=$scale success=${result.success} exit=${result.exitCode}")
        if (result.success) {
            runCatching {
                NativeAdbClient.executeShell(
                    context.applicationContext,
                    "am broadcast -a android.intent.action.CONFIGURATION_CHANGED"
                )
            }.onFailure { DolphinLogger.w(TAG, "configuration broadcast failed: ${it.message}") }
            Handler(Looper.getMainLooper()).postDelayed({
                (context as? Activity)?.recreate()
            }, 450L)
        }
        return result.success
''',
        "font scale visible refresh",
    )

patch_file("app/src/main/java/com/byd/dolphin/autoassistant/manager/DisplayDiagnosticsManager.kt", fix_display)


# v30.6.2 must not instantiate Android's TextToSpeechService at startup. The
# external sherpa TTS package initialized with status=-1 in the target vehicle.
def fix_voice(text: str) -> str:
    # v30.5.3 injected these lines before v30.6.2 runs.
    text = one(
        text,
        '''        val preferredEngine = TtsEngineInstallManager.ENGINE_PACKAGE
            .takeIf { discoveredTtsEngines.contains(it) }
        startTtsEngine(preferredEngine)
''',
        '''        DolphinLogger.i(
            "AUDIO",
            "v30.6.2 system TextToSpeech init intentionally skipped; using in-app Supertonic 3"
        )
''',
        "disable system TTS startup",
    )
    # Ensure release tears down the in-app engine too.
    text = one(
        text,
        '''        tts?.shutdown()
        releaseAudioFocus()
''',
        '''        tts?.shutdown()
        InAppSupertonicTtsManager.release()
        releaseAudioFocus()
''',
        "release in-app TTS",
    )
    return text

patch_file("app/src/main/java/com/byd/dolphin/autoassistant/manager/VoiceAndSoundManager.kt", fix_voice)

print("v30.6.2 follow-up applied: direct interior OFF, visible font refresh, system TTS startup disabled")
