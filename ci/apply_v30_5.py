#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
manager_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/VoiceAndSoundManager.kt"
build_path = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# ---- version ----
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "versionCode = 37", "versionCode = 38", "versionCode")
build = replace_once(
    build,
    'versionName = "3.0.7-v30.4-driver-dsp-probe"',
    'versionName = "3.0.8-v30.5-driver-stream14"',
    "versionName",
)
build_path.write_text(build, encoding="utf-8")

# ---- production driver-only audio route ----
manager = manager_path.read_text(encoding="utf-8")

attrs_marker = '''    private val voiceCommunicationAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
'''
attrs_insert = '''    private fun buildDriverStream14Attributes(): AudioAttributes? = runCatching {
        val builder = AudioAttributes.Builder()
        val setter = builder.javaClass.methods.firstOrNull {
            it.name == "setLegacyStreamType" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: builder.javaClass.declaredMethods.firstOrNull {
            it.name == "setLegacyStreamType" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("AudioAttributes.Builder.setLegacyStreamType(int)")
        runCatching { setter.isAccessible = true }
        setter.invoke(builder, 14)
        builder.build().also { attrs ->
            DolphinLogger.i(
                "AUDIO",
                "v30.5 driver stream14 attributes ready usage=${attrs.usage} content=${attrs.contentType} flags=${attrs.flags}"
            )
        }
    }.onFailure {
        DolphinLogger.e("AUDIO", "v30.5 driver stream14 attributes unavailable; raw stream14 fallback will be used", unwrapReflection(it))
    }.getOrNull()

    private val driverStream14Attributes: AudioAttributes? by lazy { buildDriverStream14Attributes() }

    private val voiceCommunicationAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
'''
manager = replace_once(manager, attrs_marker, attrs_insert, "stream14 attributes injection")

manager = replace_once(
    manager,
    'DolphinLogger.i("AUDIO", "TTS 초기화 시작; route=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE")',
    'DolphinLogger.i("AUDIO", "TTS 초기화 시작; route=v30.5 LEGACY_STREAM_14 (NAV fallback only if hidden attribute unavailable)")',
    "TTS init log",
)
manager = replace_once(
    manager,
    "tts?.setAudioAttributes(navigationAudioAttributes)",
    "tts?.setAudioAttributes(driverStream14Attributes ?: navigationAudioAttributes)",
    "TTS stream14 attributes",
)
manager = replace_once(
    manager,
    '"usage=NAVIGATION_GUIDANCE outputs=${describeOutputs()}"',
    '"usage=${driverStream14Attributes?.let { "LEGACY_STREAM_14" } ?: "NAV_FALLBACK"} outputs=${describeOutputs()}"',
    "TTS route diagnostic",
)

pattern = re.compile(
    r'''    private fun playPcmTone\(frequencyHz: Double, durationMs: Int\) \{.*?\n    \}\n\n    private fun describeOutputs\(\): String =''',
    re.S,
)
replacement = r'''    private fun playPcmTone(frequencyHz: Double, durationMs: Int) {
        // v30.5 real-vehicle result: BYD legacy stream 14 is the confirmed driver-only DSP route.
        // Prefer Route 6 style AudioAttributes; if hidden API creation/playback fails, fall back to
        // Route 5 raw AudioTrack(streamType=14), 44.1 kHz mono, MODE_STATIC, write-first.
        val attrs = driverStream14Attributes
        if (attrs != null && playDriverStream14AttributesTone(attrs, frequencyHz, durationMs)) return
        playDriverRawStream14Tone(frequencyHz, durationMs)
    }

    private fun playDriverStream14AttributesTone(
        attrs: AudioAttributes,
        frequencyHz: Double,
        durationMs: Int
    ): Boolean {
        val sampleRate = 44_100
        val samples = buildToneSamples(sampleRate, frequencyHz, durationMs, amplitude = 0.32)
        var audioTrack: AudioTrack? = null
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(maxOf(samples.size * 2, minBuffer))
                .build()
            audioTrack = track
            val written = track.write(samples, 0, samples.size)
            if (written <= 0) throw IllegalStateException("stream14 attrs write=$written")
            track.setVolume(0.78f)
            track.play()
            Thread.sleep(70L)
            logTrackRoute(0, "V30_5_DRIVER_STREAM14_ATTR", track)
            Thread.sleep((durationMs - 40).coerceAtLeast(40).toLong())
            runCatching { track.stop() }
            DolphinLogger.i("AUDIO", "v30.5 stream14 attrs tone OK write=$written freq=$frequencyHz duration=$durationMs")
            true
        } catch (t: Throwable) {
            DolphinLogger.e("AUDIO", "v30.5 stream14 attrs tone failed; raw stream14 fallback", t)
            false
        } finally {
            runCatching { audioTrack?.release() }
        }
    }

    @Suppress("DEPRECATION")
    private fun playDriverRawStream14Tone(frequencyHz: Double, durationMs: Int): Boolean {
        val sampleRate = 44_100
        val samples = buildToneSamples(sampleRate, frequencyHz, durationMs, amplitude = 0.32)
        var audioTrack: AudioTrack? = null
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            val track = AudioTrack(
                14,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(samples.size * 2, minBuffer),
                AudioTrack.MODE_STATIC
            )
            audioTrack = track
            val preState = track.state
            if (preState != AudioTrack.STATE_INITIALIZED && preState != AudioTrack.STATE_NO_STATIC_DATA) {
                throw IllegalStateException("raw stream14 unexpected preState=$preState")
            }
            val written = track.write(samples, 0, samples.size)
            val postState = track.state
            if (written <= 0 || postState != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("raw stream14 postState=$postState write=$written")
            }
            track.setVolume(0.78f)
            track.play()
            Thread.sleep(70L)
            logTrackRoute(0, "V30_5_DRIVER_STREAM14_RAW_FALLBACK", track)
            Thread.sleep((durationMs - 40).coerceAtLeast(40).toLong())
            runCatching { track.stop() }
            DolphinLogger.i("AUDIO", "v30.5 raw stream14 tone OK write=$written freq=$frequencyHz duration=$durationMs")
            true
        } catch (t: Throwable) {
            DolphinLogger.e("AUDIO", "v30.5 raw stream14 tone failed", t)
            false
        } finally {
            runCatching { audioTrack?.release() }
        }
    }

    private fun describeOutputs(): String ='''
manager, count = pattern.subn(replacement, manager, count=1)
if count != 1:
    raise SystemExit(f"production tone replacement: expected 1 match, got {count}")

# Sanity checks: no v30.5 build should silently regress to the old production NAV tone.
checks = {
    "version marker": 'v30.5 driver stream14 attributes ready' in manager,
    "tts stream14": 'tts?.setAudioAttributes(driverStream14Attributes ?: navigationAudioAttributes)' in manager,
    "attrs production route": 'V30_5_DRIVER_STREAM14_ATTR' in manager,
    "raw fallback": 'V30_5_DRIVER_STREAM14_RAW_FALLBACK' in manager,
    "old production NAV marker removed": 'NAV_WARNING_48K_AUTO' not in manager,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("v30.5 sanity check failed: " + ", ".join(failed))

manager_path.write_text(manager, encoding="utf-8")
print("v30.5 CI patch applied successfully")
print("versionCode=38 versionName=3.0.8-v30.5-driver-stream14")
print("production audio=legacy stream14 attrs -> raw stream14 fallback; TTS attrs=stream14")
