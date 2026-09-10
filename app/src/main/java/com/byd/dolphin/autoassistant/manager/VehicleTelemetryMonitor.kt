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
 * Read-only vehicle telemetry backed by methods and constants present in the
 * supplied DiLink 3 framework.jar. Polling avoids relying on guessed broadcasts.
 */
class VehicleTelemetryMonitor(
    context: Context,
    private val onMotion: (speedKmH: Float, gear: String) -> Unit,
    private val onGearChanged: (gear: String, speedKmH: Float) -> Unit,
    private val onEpbChanged: (applied: Boolean) -> Unit,
    private val onChargingStarted: () -> Unit,
    private val onChargingEnded: () -> Unit,
    private val onFrontDefrostChanged: (enabled: Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = mutableMapOf<String, Any>()
    private val methods = mutableMapOf<String, Method>()
    private val reportedErrors = mutableSetOf<String>()

    private var pollingJob: Job? = null
    private var lastGear: String? = null
    private var lastSpeed = Float.NaN
    private var lastEpbApplied: Boolean? = null
    private var lastChargingWorkState: Int? = null
    private var lastFrontDefrost: Boolean? = null
    private val lastRawStates = mutableMapOf<String, Int>()
    private var slowTick = 0

    fun start() {
        if (pollingJob != null) return
        pollingJob = scope.launch {
            DolphinLogger.i(TAG, "검증된 read-only 차량 텔레메트리 폴링 시작")
            while (isActive) {
                runCatching { sampleFast() }
                    .onFailure { reportError("fast-sample", it) }
                if (slowTick++ % SLOW_TICK_DIVISOR == 0) {
                    runCatching { sampleSlow() }
                        .onFailure { reportError("slow-sample", it) }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        devices.clear()
        methods.clear()
        DolphinLogger.i(TAG, "차량 텔레메트리 폴링 정지")
    }

    private fun sampleFast() {
        val speed = readNumber(SPEED_CLASS, "getCurrentSpeed")?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..MAX_REASONABLE_SPEED_KMH }
            ?.toFloat()
        // A missing speed sample must never be interpreted as a stationary car.
        // NaN makes the experimental parking-sensor detector disarm safely.
        lastSpeed = speed ?: Float.NaN

        val gear = readNumber(GEARBOX_CLASS, "getCurrentGear")?.toInt()?.let(::decodeGear)
        if (gear != null) {
            val previous = lastGear
            if (previous == null) {
                DolphinLogger.i(TAG, "초기 기어 기준값: $gear")
                mainHandler.post { onGearChanged(gear, lastSpeed) }
            } else if (previous != gear) {
                DolphinLogger.i(TAG, "기어 API 전환: $previous -> $gear speed=$lastSpeed")
                mainHandler.post { onGearChanged(gear, lastSpeed) }
            }
            lastGear = gear
        }

        val currentGear = lastGear
        if (currentGear != null) {
            mainHandler.post { onMotion(lastSpeed, currentGear) }
        }
    }

    private fun sampleSlow() {
        sampleEpb()
        sampleCharging()
        sampleFrontDefrost()

        // These APIs are exact, but their raw state semantics must be mapped from
        // a physical-control capture before they are allowed to trigger sounds.
        logRawTransition("adas.avh", readInt(ADAS_CLASS, "getAVHState"))
        logRawTransition("adas.bsd", readInt(ADAS_CLASS, "getBSDState"))
        logRawTransition("adas.laneOffset", readInt(ADAS_CLASS, "getLaneOffsetState"))
        logRawTransition("adas.tja", readInt(ADAS_CLASS, "getTJAState"))
        logRawTransition("instrument.driveInterface", readInt(INSTRUMENT_CLASS, "getCurrentDriveInterFace"))
        logRawTransition("instrument.energyFeedback", readInt(INSTRUMENT_CLASS, "getEnergyFeedback"))
    }

    private fun sampleEpb() {
        val raw = readInt(GEARBOX_CLASS, "getEPBState") ?: return
        val applied = when (raw) {
            EPB_RELEASED -> false
            EPB_APPLIED -> true
            else -> {
                logRawTransition("gearbox.epb", raw)
                return
            }
        }
        val previous = lastEpbApplied
        if (previous != null && previous != applied) {
            DolphinLogger.i(TAG, "EPB 전환: $previous -> $applied (raw=$raw)")
            mainHandler.post { onEpbChanged(applied) }
        }
        lastEpbApplied = applied
        logRawTransition("gearbox.epb", raw)
    }

    private fun sampleCharging() {
        val workState = readInt(CHARGING_CLASS, "getChargerWorkState") ?: return
        val previous = lastChargingWorkState
        if (previous != null && previous != workState) {
            DolphinLogger.i(TAG, "충전 작업 상태: $previous -> $workState")
            when {
                workState == CHARGING_WORK_START && previous != CHARGING_WORK_START ->
                    mainHandler.post { onChargingStarted() }
                previous == CHARGING_WORK_START && workState == CHARGING_WORK_FINISH ->
                    mainHandler.post { onChargingEnded() }
                previous == CHARGING_WORK_START && workState == CHARGING_WORK_TERMINATE ->
                    DolphinLogger.w(TAG, "충전 비정상/사용자 종료 상태 감지(raw=$workState) — 완료 음성 생략")
            }
        }
        lastChargingWorkState = workState
        logRawTransition("charging.work", workState)
        logRawTransition("charging.state", readInt(CHARGING_CLASS, "getChargingState"))
        logRawTransition("charging.gun", readInt(CHARGING_CLASS, "getChargingGunState"))
    }

    private fun sampleFrontDefrost() {
        val raw = readInt(AC_CLASS, "getAcDefrostState", AREA_FRONT) ?: return
        val enabled = when (raw) {
            0 -> false
            1 -> true
            else -> {
                logRawTransition("ac.frontDefrost", raw)
                return
            }
        }
        val previous = lastFrontDefrost
        if (previous != null && previous != enabled) {
            DolphinLogger.i(TAG, "앞유리 성에 제거 전환: $previous -> $enabled")
            mainHandler.post { onFrontDefrostChanged(enabled) }
        }
        lastFrontDefrost = enabled
        logRawTransition("ac.frontDefrost", raw)
    }

    private fun logRawTransition(name: String, value: Int?) {
        if (value == null) return
        val previous = lastRawStates.put(name, value)
        if (previous == null || previous != value) {
            DolphinLogger.i(TAG, "raw $name: ${previous ?: "unknown"} -> $value")
        }
    }

    private fun readInt(className: String, methodName: String, vararg args: Int): Int? =
        readNumber(className, methodName, *args)?.toInt()

    private fun readNumber(className: String, methodName: String, vararg args: Int): Number? {
        val key = "$className#$methodName/${args.size}"
        return try {
            val instance = devices.getOrPut(className) {
                val clazz = Class.forName(className)
                clazz.getMethod("getInstance", Context::class.java).invoke(null, appContext)
                    ?: error("getInstance returned null")
            }
            val method = methods.getOrPut(key) {
                val types = Array(args.size) { Int::class.javaPrimitiveType!! }
                instance.javaClass.getMethod(methodName, *types)
            }
            method.invoke(instance, *args.toTypedArray()) as? Number
        } catch (e: Exception) {
            devices.remove(className)
            methods.remove(key)
            reportError(key, e.cause ?: e)
            null
        }
    }

    private fun reportError(key: String, error: Throwable) {
        val signature = "$key:${error.javaClass.simpleName}:${error.message}"
        if (reportedErrors.add(signature)) {
            DolphinLogger.w(TAG, "$key 읽기 실패: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun decodeGear(raw: Int): String? = when (raw) {
        GEAR_N -> "N"
        GEAR_R -> "R"
        GEAR_D -> "D"
        GEAR_P -> "P"
        else -> {
            logRawTransition("gearbox.currentGear", raw)
            null
        }
    }

    companion object {
        private const val TAG = "VEHICLE_TELEMETRY"
        private const val SPEED_CLASS = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
        private const val GEARBOX_CLASS = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
        private const val CHARGING_CLASS = "android.hardware.bydauto.charging.BYDAutoChargingDevice"
        private const val ADAS_CLASS = "android.hardware.bydauto.adas.BYDAutoADASDevice"
        private const val INSTRUMENT_CLASS = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
        private const val AC_CLASS = "android.hardware.bydauto.ac.BYDAutoAcDevice"

        private const val GEAR_N = 0
        private const val GEAR_R = 1
        private const val GEAR_D = 2
        private const val GEAR_P = 3
        private const val EPB_RELEASED = 1
        private const val EPB_APPLIED = 3
        private const val CHARGING_WORK_START = 2
        private const val CHARGING_WORK_FINISH = 3
        private const val CHARGING_WORK_TERMINATE = 4
        private const val AREA_FRONT = 1
        private const val POLL_INTERVAL_MS = 500L
        private const val SLOW_TICK_DIVISOR = 4
        private const val MAX_REASONABLE_SPEED_KMH = 300.0
    }
}
