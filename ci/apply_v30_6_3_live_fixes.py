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


# Version bump.
p, text = read("app/build.gradle.kts")
text = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 46', text, count=1)
text = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "3.1.3-v30.6.3-live-calibration"', text, count=1)
write(p, text)

# Vehicle voice monitor: fix live Dolphin mappings and add AutoHold physical inference.
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/VehicleVoiceStateMonitor.kt")
text = one(
    text,
    '    private val onAutoHoldRawChanged: (previous: Int?, current: Int) -> Unit,\n',
    '    private val onAutoHoldRawChanged: (previous: Int?, current: Int) -> Unit,\n'
    '    private val onAutoHoldBrakeChanged: (applied: Boolean) -> Unit,\n',
    'autohold brake callback',
)
text = one(
    text,
    '    private var lastAvhRaw: Int? = null\n',
    '    private var lastAvhRaw: Int? = null\n'
    '    private var autoHoldBrakeWasPressed = false\n'
    '    private var autoHoldInferredHolding = false\n',
    'autohold state fields',
)
text = text.replace(
    'val raw = readInt(INSTRUMENT_CLASS, "getCurrentDriveInterFace") ?: return',
    'val raw = readInt(ENERGY_CLASS, "getOperationMode") ?: return',
    1,
)
text = text.replace('driveInterface baseline raw=', 'operationMode baseline raw=', 1)
text = text.replace('driveInterface $previous -> $raw decoded=', 'operationMode $previous -> $raw decoded=', 1)
old_decode = '''    /**
     * DiLink 3 Dolphin live baseline was raw=2 while the car was in its normal
     * drive profile. 1/2/3 are therefore treated as ECO/NORMAL/SPORT, while 4
     * is reserved as a snow candidate. Any other value remains diagnostic-only.
     */
    private fun decodeDriveMode(raw: Int): String? = when (raw) {
        1 -> "ECO"
        2 -> "NORMAL"
        3 -> "SPORT"
        4 -> "SNOW"
        else -> null
    }
'''
new_decode = '''    /**
     * BYDAutoEnergyDevice.getOperationMode() is the drive-mode API.
     * Public BYD SDK research maps 1=ECO and 2=SPORT; NORMAL is represented by 0
     * on firmware variants that expose all three modes. Unknown values remain log-only.
     */
    private fun decodeDriveMode(raw: Int): String? = when (raw) {
        0 -> "NORMAL"
        1 -> "ECO"
        2 -> "SPORT"
        else -> null
    }
'''
text = one(text, old_decode, new_decode, 'drive mode decoder')
text = text.replace('private const val ENERGY_FB_STANDARD = 1', 'private const val ENERGY_FB_STANDARD = 2', 1)
text = text.replace('private const val ENERGY_FB_HIGH = 2', 'private const val ENERGY_FB_HIGH = 3', 1)
text = one(
    text,
    '''        lastAvhRaw = raw
    }

    private fun sampleIcc() {
''',
    '''        lastAvhRaw = raw

        // getAVHState() on the tested Dolphin behaves as the AVH switch state.
        // Infer the physical hold/release from AVH enabled + D + speed/pedal data.
        val gear = readInt(GEARBOX_CLASS, "getCurrentGear")
        val speed = readInt(SPEED_CLASS, "getCurrentSpeed")
        val brakeDepth = readInt(SPEED_CLASS, "getBrakeDeepness")
        val accelDepth = readInt(SPEED_CLASS, "getAccelerateDeepness")
        DolphinLogger.d(TAG, "AVH physical raw=$raw gear=$gear speed=$speed brake=$brakeDepth accel=$accelDepth holding=$autoHoldInferredHolding")

        if (raw != AVH_SWITCH_ON) {
            autoHoldBrakeWasPressed = false
            if (autoHoldInferredHolding) {
                autoHoldInferredHolding = false
                main.post { onAutoHoldBrakeChanged(false) }
            }
            return
        }

        if (autoHoldInferredHolding) {
            val released = gear != GEAR_D || (speed ?: 0) >= 1 || (accelDepth ?: 0) > 0
            if (released) {
                autoHoldInferredHolding = false
                autoHoldBrakeWasPressed = false
                DolphinLogger.i(TAG, "AutoHold inferred RELEASE gear=$gear speed=$speed accel=$accelDepth")
                main.post { onAutoHoldBrakeChanged(false) }
            }
            return
        }

        if (gear == GEAR_D && (speed ?: Int.MAX_VALUE) == 0) {
            if ((brakeDepth ?: 0) > 0) {
                autoHoldBrakeWasPressed = true
            } else if (autoHoldBrakeWasPressed) {
                autoHoldInferredHolding = true
                DolphinLogger.i(TAG, "AutoHold inferred HOLD gear=$gear speed=$speed")
                main.post { onAutoHoldBrakeChanged(true) }
            }
        } else {
            autoHoldBrakeWasPressed = false
        }
    }

    private fun sampleIcc() {
''',
    'autohold physical inference',
)
text = one(
    text,
    '        private const val LIGHT_CLASS = "android.hardware.bydauto.light.BYDAutoLightDevice"\n',
    '        private const val LIGHT_CLASS = "android.hardware.bydauto.light.BYDAutoLightDevice"\n'
    '        private const val SPEED_CLASS = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"\n'
    '        private const val GEARBOX_CLASS = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"\n',
    'voice monitor speed/gear classes',
)
text = one(
    text,
    '        private const val ROAD_SURFACE_SNOW = 2\n',
    '        private const val ROAD_SURFACE_SNOW = 2\n'
    '        private const val AVH_SWITCH_ON = 1\n'
    '        private const val GEAR_D = 2\n',
    'voice monitor constants',
)
write(p, text)

# Service: only AVH switch speaks from raw state; physical hold gets its own callback.
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/service/DolphinService.kt")
text = text.replace(
    '''            previous == 1 && current == 2 -> audioManager.speakAutoHoldBrake(true)
            previous == 2 && current == 1 -> audioManager.speakAutoHoldBrake(false)
''',
    '',
    1,
)
text = one(
    text,
    '            onAutoHoldRawChanged = { previous, current -> handleAutoHoldVoice(previous, current) },\n',
    '            onAutoHoldRawChanged = { previous, current -> handleAutoHoldVoice(previous, current) },\n'
    '            onAutoHoldBrakeChanged = { applied -> audioManager.speakAutoHoldBrake(applied) },\n',
    'service autohold physical callback',
)
write(p, text)

# Telemetry: record the exact signals needed for next live calibration.
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/VehicleTelemetryMonitor.kt")
text = one(
    text,
    '''        logRawTransition("adas.tja", readInt(ADAS_CLASS, "getTJAState"))
        logRawTransition("instrument.driveInterface", readInt(INSTRUMENT_CLASS, "getCurrentDriveInterFace"))
        logRawTransition("instrument.energyFeedback", readInt(INSTRUMENT_CLASS, "getEnergyFeedback"))
''',
    '''        logRawTransition("adas.tja", readInt(ADAS_CLASS, "getTJAState"))
        logRawTransition("gearbox.brakePedal", readInt(GEARBOX_CLASS, "getBrakePedalState"))
        logRawTransition("speed.brakeDeepness", readInt(SPEED_CLASS, "getBrakeDeepness"))
        logRawTransition("speed.accelerateDeepness", readInt(SPEED_CLASS, "getAccelerateDeepness"))
        logRawTransition("setting.energyFeedback", readInt(SETTING_CLASS, "getEnergyFeedback"))
        logRawTransition("energy.operationMode", readInt(ENERGY_CLASS, "getOperationMode"))
        logRawTransition("energy.roadSurfaceMode", readInt(ENERGY_CLASS, "getRoadSurfaceMode"))
        logRawTransition("instrument.driveInterface", readInt(INSTRUMENT_CLASS, "getCurrentDriveInterFace"))
''',
    'telemetry live calibration probes',
)
text = one(
    text,
    '        private const val AC_CLASS = "android.hardware.bydauto.ac.BYDAutoAcDevice"\n',
    '        private const val AC_CLASS = "android.hardware.bydauto.ac.BYDAutoAcDevice"\n'
    '        private const val SETTING_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"\n'
    '        private const val ENERGY_CLASS = "android.hardware.bydauto.energy.BYDAutoEnergyDevice"\n',
    'telemetry setting/energy classes',
)
write(p, text)

# Diagnostic ZIP probes: correct EnergyFeedback source and add drive/AutoHold/turn raw values.
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/DiagnosticCaptureManager.kt")
text = one(
    text,
    '    private const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"\n',
    '    private const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"\n'
    '    private const val ENERGY = "android.hardware.bydauto.energy.BYDAutoEnergyDevice"\n',
    'diagnostic energy class',
)
text = text.replace(
    '        BODYWORK, SETTING, AC, LIGHT, RADAR, SPEED, GEARBOX, CHARGING, ADAS, INSTRUMENT\n',
    '        BODYWORK, SETTING, AC, LIGHT, RADAR, SPEED, GEARBOX, CHARGING, ADAS, INSTRUMENT, ENERGY\n',
    1,
)
text = one(
    text,
    '''        VehicleProbe("adas.laneOffset.raw", ADAS, "getLaneOffsetState"),
        VehicleProbe("instrument.driveInterface.raw", INSTRUMENT, "getCurrentDriveInterFace"),
        VehicleProbe("instrument.energyFeedback.raw", INSTRUMENT, "getEnergyFeedback"),
        VehicleProbe("ac.start.raw", AC, "getAcStartState"),
''',
    '''        VehicleProbe("adas.laneOffset.raw", ADAS, "getLaneOffsetState"),
        VehicleProbe("adas.tja.raw", ADAS, "getTJAState"),
        VehicleProbe("gearbox.brakePedal.raw", GEARBOX, "getBrakePedalState"),
        VehicleProbe("speed.brakeDeepness.raw", SPEED, "getBrakeDeepness"),
        VehicleProbe("speed.accelerateDeepness.raw", SPEED, "getAccelerateDeepness"),
        VehicleProbe("setting.energyFeedback.raw", SETTING, "getEnergyFeedback"),
        VehicleProbe("energy.operationMode.raw", ENERGY, "getOperationMode"),
        VehicleProbe("energy.roadSurfaceMode.raw", ENERGY, "getRoadSurfaceMode"),
        VehicleProbe("instrument.driveInterface.raw", INSTRUMENT, "getCurrentDriveInterFace"),
        VehicleProbe("light.turn.raw", LIGHT, "getTurnLightState"),
        VehicleProbe("ac.start.raw", AC, "getAcStartState"),
''',
    'diagnostic live probes',
)
write(p, text)

# Text diagnostic report: remove misleading Instrument energy reading.
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/util/DolphinLogger.kt")
text = one(
    text,
    '        appendLine("instrument.energyFeedback.raw=${vehicleInt(context, INSTRUMENT, "getEnergyFeedback")}")\n',
    '        appendLine("setting.energyFeedback.raw=${vehicleInt(context, SETTING, "getEnergyFeedback")}")\n'
    '        appendLine("energy.operationMode.raw=${vehicleInt(context, ENERGY, "getOperationMode")}")\n'
    '        appendLine("energy.roadSurfaceMode.raw=${vehicleInt(context, ENERGY, "getRoadSurfaceMode")}")\n'
    '        appendLine("gearbox.brakePedal.raw=${vehicleInt(context, GEARBOX, "getBrakePedalState")}")\n'
    '        appendLine("speed.brakeDeepness.raw=${vehicleInt(context, SPEED, "getBrakeDeepness")}")\n'
    '        appendLine("speed.accelerateDeepness.raw=${vehicleInt(context, SPEED, "getAccelerateDeepness")}")\n',
    'logger live probes',
)
text = one(
    text,
    '    private const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"\n',
    '    private const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"\n'
    '    private const val ENERGY = "android.hardware.bydauto.energy.BYDAutoEnergyDevice"\n',
    'logger energy class',
)
write(p, text)

# Driver-audio lab: expose the real blocker and gather output-device information instead of claiming routing is active.
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/DriverAudioPolicyLab.kt")
text = one(
    text,
    'import android.media.AudioManager\n',
    'import android.media.AudioManager\nimport android.media.AudioDeviceInfo\nimport android.content.pm.PackageManager\n',
    'audio lab imports',
)
text = one(
    text,
    '''        return buildString {
            appendLine("driverOnlyRoute=BYD_LEGACY_STREAM_14_CONFIRMED")
            appendLine("selectedApps=$entries")
            appendLine("audioMode=${am.mode}")
            appendLine("activePerAppInterception=false")
''',
    '''        val modifyRouting = context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") == PackageManager.PERMISSION_GRANTED
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { d ->
            "id=${d.id}:type=${d.type}:name=${d.productName}"
        }.ifBlank { "<none>" }
        return buildString {
            appendLine("driverOnlyRoute=BYD_LEGACY_STREAM_14_CONFIRMED")
            appendLine("selectedApps=$entries")
            appendLine("audioMode=${am.mode}")
            appendLine("modifyAudioRoutingPermission=$modifyRouting")
            appendLine("outputDevices=$outputs")
            appendLine("activePerAppInterception=false")
''',
    'audio capability detail',
)
text = text.replace(
    'reason=Android10 AudioPolicyMix/playback-capture privileges and OEM warning preemption are not yet verified on this firmware',
    'reason=UID device-affinity exists in Android audio policy, but this app must first obtain/verify MODIFY_AUDIO_ROUTING and identify the BYD driver-only output device; stream14 alone only routes DolphinAssistant-owned audio',
    1,
)
write(p, text)

# Manifest: request the routing permission so the live report can prove whether OEM firmware grants it.
p, text = read("app/src/main/AndroidManifest.xml")
if 'android.permission.MODIFY_AUDIO_ROUTING' not in text:
    text = text.replace(
        '    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />\n',
        '    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />\n'
        '    <uses-permission android:name="android.permission.MODIFY_AUDIO_ROUTING" />\n',
        1,
    )
write(p, text)

# Interior-light LAB: expose raw int command values for stationary calibration because 1/2 returned success without physical action.
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/InsideLightManager.kt")
anchor = '    fun isLightOn(context: Context): Boolean {\n'
helper = '''    fun testRawCommand(context: Context, value: Int): Boolean {
        if (value !in 0..4) return false
        val ok = invokeBydInsideLight(context, value)
        DolphinLogger.i(TAG, "RAW inside-light calibration value=$value result=$ok")
        return ok
    }

'''
if 'fun testRawCommand(' not in text:
    text = one(text, anchor, helper + anchor, 'inside light raw calibration helper')
write(p, text)

# UI additions: raw light calibration buttons and clearer driver-audio state.
p, text = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/IntegratedBetaUi.kt")
needle = '''        panel.addView(lightRow)
        panel.addView(doorLight)
'''
replacement = '''        panel.addView(lightRow)
        val rawLightRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        (0..4).forEach { raw ->
            rawLightRow.addView(button(activity, "LIGHT $raw") {
                val ok = InsideLightManager.testRawCommand(activity, raw)
                toast(activity, "실내등 raw=$raw 요청=$ok · 실제 램프 반응을 확인하세요")
            }, weight())
        }
        panel.addView(rawLightRow)
        panel.addView(doorLight)
'''
text = one(text, needle, replacement, 'inside light UI calibration')
text = text.replace(
    '패키지명이나 경로를 직접 입력하지 않습니다. 차량에 설치된 실행 가능 앱 이름에서 선택하면 내부적으로 package/UID를 저장합니다. 순정 안전 경고 우선권은 변경하지 않습니다.',
    '설치 앱의 package/UID를 저장하고 Android UID device-affinity 가능 여부와 출력 장치를 진단합니다. MODIFY_AUDIO_ROUTING이 실제로 허용되기 전에는 강제 라우팅하지 않아 순정 안전 경고를 건드리지 않습니다.',
    1,
)
write(p, text)

print("v30.6.3 live fixes applied")
