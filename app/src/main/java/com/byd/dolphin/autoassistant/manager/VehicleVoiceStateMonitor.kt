package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method

/**
 * v30.6.2 voice-oriented read-only state monitor.
 *
 * It is intentionally separate from VehicleTelemetryMonitor so the already proven
 * gear/EPB/charging path stays untouched. Every signal below is read-only.
 * Unknown values are logged and ignored rather than guessed into a spoken state.
 */
class VehicleVoiceStateMonitor(
    context: Context,
    private val onDriveModeChanged: (String) -> Unit,
    private val onRegenModeChanged: (String) -> Unit,
    private val onSnowModeChanged: (Boolean) -> Unit,
    private val onAutoHoldRawChanged: (previous: Int?, current: Int) -> Unit,
    private val onIccChanged: (Boolean) -> Unit,
    private val onBsdSignal: (turnDirection: String, bsdRaw: Int) -> Unit
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val devices = mutableMapOf<String, Any>()
    private val methods = mutableMapOf<String, Method>()
    private val reportedErrors = mutableSetOf<String>()
    private var job: Job? = null

    private var lastDriveRaw: Int? = null
    private var lastRegenRaw: Int? = null
    private var lastRoadSurfaceRaw: Int? = null
    private var lastAvhRaw: Int? = null
    private var lastTjaRaw: Int? = null
    private var lastTurnRaw: Int? = null
    private var lastBsdRaw: Int? = null
    private var lastBsdWarningAt = 0L

    fun start() {
        if (job != null) return
        job = scope.launch {
            DolphinLogger.i(TAG, "v30.6.2 voice-state monitor start")
            while (isActive) {
                runCatching { sample() }.onFailure { reportError("sample", it) }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        devices.clear()
        methods.clear()
    }

    private fun sample() {
        sampleDriveMode()
        sampleRegen()
        sampleSnow()
        sampleAutoHold()
        sampleIcc()
        sampleBsdAndTurnSignal()
    }

    private fun sampleDriveMode() {
        val raw = readInt(INSTRUMENT_CLASS, "getCurrentDriveInterFace") ?: return
        val previous = lastDriveRaw
        if (previous == null) {
            DolphinLogger.i(TAG, "driveInterface baseline raw=$raw")
        } else if (previous != raw) {
            val decoded = decodeDriveMode(raw)
            DolphinLogger.i(TAG, "driveInterface $previous -> $raw decoded=${decoded ?: "UNKNOWN"}")
            if (decoded != null && decoded != "SNOW") main.post { onDriveModeChanged(decoded) }
            if (decoded == "SNOW") main.post { onSnowModeChanged(true) }
        }
        lastDriveRaw = raw
    }

    /**
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

    private fun sampleRegen() {
        // Important: EnergyFeedback belongs to BYDAutoSettingDevice. The older
        // monitor queried InstrumentDevice and got the link-error value 65535.
        val raw = readInt(SETTING_CLASS, "getEnergyFeedback") ?: return
        val previous = lastRegenRaw
        if (previous == null) {
            DolphinLogger.i(TAG, "energyFeedback baseline raw=$raw")
        } else if (previous != raw) {
            val decoded = when (raw) {
                ENERGY_FB_STANDARD -> "STANDARD"
                ENERGY_FB_HIGH -> "HIGH"
                else -> null
            }
            DolphinLogger.i(TAG, "energyFeedback $previous -> $raw decoded=${decoded ?: "UNKNOWN"}")
            if (decoded != null) main.post { onRegenModeChanged(decoded) }
        }
        lastRegenRaw = raw
    }

    private fun sampleSnow() {
        val raw = readInt(ENERGY_CLASS, "getRoadSurfaceMode") ?: return
        val previous = lastRoadSurfaceRaw
        if (previous == null) {
            DolphinLogger.i(TAG, "roadSurface baseline raw=$raw")
        } else if (previous != raw) {
            val enabled = raw == ROAD_SURFACE_SNOW
            DolphinLogger.i(TAG, "roadSurface $previous -> $raw snow=$enabled")
            // User asked for a spoken announcement when Snow mode is selected;
            // leaving snow is logged but does not generate an extra sentence.
            if (enabled) main.post { onSnowModeChanged(true) }
        }
        lastRoadSurfaceRaw = raw
    }

    private fun sampleAutoHold() {
        val raw = readInt(ADAS_CLASS, "getAVHState") ?: return
        val previous = lastAvhRaw
        if (previous == null) {
            DolphinLogger.i(TAG, "AVH baseline raw=$raw")
        } else if (previous != raw) {
            DolphinLogger.i(TAG, "AVH raw $previous -> $raw")
            main.post { onAutoHoldRawChanged(previous, raw) }
        }
        lastAvhRaw = raw
    }

    private fun sampleIcc() {
        val raw = readInt(ADAS_CLASS, "getTJAState") ?: return
        val previous = lastTjaRaw
        if (previous == null) {
            DolphinLogger.i(TAG, "TJA/ICC candidate baseline raw=$raw")
        } else if (previous != raw) {
            // SDK states: 0=off, 1=passive, 2/3=active, 4=fault.
            val before = decodeIccActive(previous)
            val now = decodeIccActive(raw)
            DolphinLogger.i(TAG, "TJA/ICC candidate $previous -> $raw active=$now")
            if (now != null && now != before) main.post { onIccChanged(now) }
        }
        lastTjaRaw = raw
    }

    private fun decodeIccActive(raw: Int): Boolean? = when (raw) {
        0, 1 -> false
        2, 3 -> true
        else -> null
    }

    private fun sampleBsdAndTurnSignal() {
        val bsd = readInt(ADAS_CLASS, "getBSDState")
        val turn = readInt(LIGHT_CLASS, "getTurnLightState")
        if (bsd != null && bsd != lastBsdRaw) {
            DolphinLogger.i(TAG, "BSD raw ${lastBsdRaw ?: "unknown"} -> $bsd")
            lastBsdRaw = bsd
        }
        if (turn != null && turn != lastTurnRaw) {
            DolphinLogger.i(TAG, "turnLight raw ${lastTurnRaw ?: "unknown"} -> $turn dir=${decodeTurn(turn)}")
            lastTurnRaw = turn
        }

        // getBSDState() is a feature state on some DiLink generations and an
        // active-warning state on others. To prevent false continuous alarms,
        // v30.6.2 only arms a beep when BSD changes away from its baseline while
        // a directional turn signal is active. This is intentionally stricter
        // than simply beeping whenever BSD=ON.
        val direction = turn?.let(::decodeTurn) ?: return
        if (direction !in setOf("LEFT", "RIGHT")) return
        val baseline = bsd ?: return
        val previousBsd = lastBsdRaw ?: return
        if (baseline == previousBsd) return
        val now = System.currentTimeMillis()
        if (now - lastBsdWarningAt < BSD_COOLDOWN_MS) return
        lastBsdWarningAt = now
        main.post { onBsdSignal(direction, baseline) }
    }

    private fun decodeTurn(raw: Int): String = when (raw) {
        TURN_LEFT_NORMAL, TURN_LEFT_FAST -> "LEFT"
        TURN_RIGHT_NORMAL, TURN_RIGHT_FAULT -> "RIGHT"
        TURN_DANGER, TURN_EMERGENCY, TURN_REAR_END -> "HAZARD"
        TURN_OFF -> "OFF"
        else -> "UNKNOWN($raw)"
    }

    private fun readInt(className: String, methodName: String, vararg args: Int): Int? {
        val key = "$className#$methodName/${args.size}"
        return try {
            val instance = devices.getOrPut(className) {
                val clazz = Class.forName(className)
                clazz.getMethod("getInstance", Context::class.java)
                    .invoke(null, BydPermissionContext.wrap(appContext))
                    ?: error("getInstance returned null")
            }
            val method = methods.getOrPut(key) {
                val types = Array(args.size) { Int::class.javaPrimitiveType!! }
                instance.javaClass.getMethod(methodName, *types)
            }
            (method.invoke(instance, *args.toTypedArray()) as? Number)?.toInt()
        } catch (t: Throwable) {
            devices.remove(className)
            methods.remove(key)
            reportError(key, t.cause ?: t)
            null
        }
    }

    private fun reportError(key: String, t: Throwable) {
        val signature = "$key:${t.javaClass.simpleName}:${t.message}"
        if (reportedErrors.add(signature)) {
            DolphinLogger.w(TAG, "$key unavailable: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "VOICE_STATE"
        private const val SETTING_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
        private const val INSTRUMENT_CLASS = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
        private const val ENERGY_CLASS = "android.hardware.bydauto.energy.BYDAutoEnergyDevice"
        private const val ADAS_CLASS = "android.hardware.bydauto.adas.BYDAutoADASDevice"
        private const val LIGHT_CLASS = "android.hardware.bydauto.light.BYDAutoLightDevice"

        private const val ENERGY_FB_STANDARD = 1
        private const val ENERGY_FB_HIGH = 2
        private const val ROAD_SURFACE_SNOW = 2

        private const val TURN_OFF = 1
        private const val TURN_LEFT_NORMAL = 2
        private const val TURN_LEFT_FAST = 3
        private const val TURN_RIGHT_NORMAL = 4
        private const val TURN_RIGHT_FAULT = 5
        private const val TURN_DANGER = 6
        private const val TURN_EMERGENCY = 7
        private const val TURN_REAR_END = 8

        private const val POLL_MS = 250L
        private const val BSD_COOLDOWN_MS = 1_500L
    }
}
