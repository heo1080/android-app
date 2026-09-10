package com.byd.dolphin.autoassistant.manager

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

/**
 * Experimental low-speed leading-object departure detector.
 *
 * DiLink 3 exposes two front-centre parking-sensor values (areas 7 and 8) in the
 * range 0..155. This engine only evaluates those real values while the vehicle is
 * stationary in D/N. It does not claim access to the forward ADAS camera/radar.
 */
class LeadingVehicleDepartureEngine(
    context: Context,
    private val onDepartureDetected: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    @Volatile private var running = false
    @Volatile private var vehicleStationary = false
    @Volatile private var currentGear = "P"
    @Volatile private var stoppedTimestamp = 0L

    private var radarInstance: Any? = null
    private var distanceMethod: Method? = null
    private val acquisition = ArrayDeque<Int>()
    private var lockedDistanceCm: Int? = null
    private var departureSamples = 0
    private var lastAlertTimestamp = 0L
    private var lastTelemetryLog = 0L

    init {
        initRadar()
    }

    private fun initRadar() {
        try {
            val clazz = Class.forName(RADAR_CLASS)
            radarInstance = clazz.getMethod("getInstance", Context::class.java).invoke(null, appContext)
            distanceMethod = clazz.getMethod("getRadarObstacleDistance", Int::class.javaPrimitiveType)
            DolphinLogger.i(TAG, "레이더 API 확인: getRadarObstacleDistance(int), areas=7/8")
        } catch (e: Exception) {
            radarInstance = null
            distanceMethod = null
            DolphinLogger.e(TAG, "레이더 API 초기화 실패 — LVDA 비활성", e.cause ?: e)
        }
    }

    fun start() {
        if (running) return
        running = true
        DolphinLogger.i(TAG, "전방 주차센서 기반 실험적 출발 감지 시작")
        monitorJob = scope.launch {
            while (isActive && running) {
                runCatching { evaluate() }
                    .onFailure { DolphinLogger.w(TAG, "샘플 처리 오류: ${it.message}") }
                delay(SAMPLE_MS)
            }
        }
    }

    fun stop() {
        running = false
        monitorJob?.cancel()
        monitorJob = null
        resetTarget()
        scope.cancel()
        DolphinLogger.i(TAG, "전방 주차센서 기반 출발 감지 정지")
    }

    fun updateVehicleSpeedAndGear(speed: Float, gear: String) {
        currentGear = gear.uppercase()
        val nowStationary = speed in 0f..0.5f && (currentGear == "D" || currentGear == "N")
        if (nowStationary && !vehicleStationary) {
            stoppedTimestamp = System.currentTimeMillis()
            resetTarget()
            DolphinLogger.i(TAG, "정차 감시 대기: gear=$currentGear speed=$speed")
        } else if (!nowStationary && vehicleStationary) {
            DolphinLogger.i(TAG, "정차 감시 해제: gear=$currentGear speed=$speed")
            resetTarget()
        }
        vehicleStationary = nowStationary
    }

    private suspend fun evaluate() {
        if (!SettingsManager.isLeadingCarVoiceEnabled(appContext) ||
            !SettingsManager.isExperimentalLvdaEnabled(appContext) || !vehicleStationary
        ) {
            resetTarget()
            return
        }
        if (distanceMethod == null || radarInstance == null) return

        val now = System.currentTimeMillis()
        if (now - stoppedTimestamp < STATIONARY_ARM_MS || now - lastAlertTimestamp < COOLDOWN_MS) return

        val left = readDistance(AREA_FRONT_LEFT_MID)
        val right = readDistance(AREA_FRONT_RIGHT_MID)
        val distance = nearestObstacle(left, right)

        if (now - lastTelemetryLog >= TELEMETRY_LOG_MS) {
            DolphinLogger.i(TAG, "sensor left=$left right=$right selected=$distance locked=$lockedDistanceCm departSamples=$departureSamples")
            lastTelemetryLog = now
        }

        val locked = lockedDistanceCm
        if (locked == null) {
            if (distance != null && distance in LOCK_MIN_CM..LOCK_MAX_CM) {
                acquisition.addLast(distance)
                while (acquisition.size > ACQUIRE_SAMPLES) acquisition.removeFirst()
                if (acquisition.size == ACQUIRE_SAMPLES && acquisition.max() - acquisition.min() <= ACQUIRE_SPREAD_CM) {
                    lockedDistanceCm = acquisition.sorted()[acquisition.size / 2]
                    acquisition.clear()
                    DolphinLogger.i(TAG, "전방 물체 고정: baseline=${lockedDistanceCm}cm")
                }
            } else {
                acquisition.clear()
            }
            return
        }

        val sensorClear = left == SENSOR_MAX_CM && right == SENSOR_MAX_CM
        val movedAway = distance != null && distance - locked >= DEPARTURE_DELTA_CM
        departureSamples = if (sensorClear || movedAway) departureSamples + 1 else 0
        if (departureSamples < CONFIRM_DEPARTURE_SAMPLES) return

        lastAlertTimestamp = now
        DolphinLogger.i(TAG, "전방 물체 이탈 판정: baseline=${locked}cm left=$left right=$right")
        resetTarget()
        withContext(Dispatchers.Main) { onDepartureDetected() }
    }

    private fun readDistance(area: Int): Int? {
        return try {
            val value = (distanceMethod?.invoke(radarInstance, area) as? Number)?.toInt()
            value?.takeIf { it in SENSOR_MIN_CM..SENSOR_MAX_CM }
        } catch (e: Exception) {
            DolphinLogger.w(TAG, "센서 읽기 실패 area=$area: ${(e.cause ?: e).message}")
            null
        }
    }

    private fun nearestObstacle(left: Int?, right: Int?): Int? {
        return listOfNotNull(left, right)
            .filter { it in LOCK_MIN_CM until SENSOR_MAX_CM }
            .minOrNull()
    }

    private fun resetTarget() {
        acquisition.clear()
        lockedDistanceCm = null
        departureSamples = 0
    }

    companion object {
        private const val TAG = "LVDA_SENSOR"
        private const val RADAR_CLASS = "android.hardware.bydauto.radar.BYDAutoRadarDevice"
        private const val AREA_FRONT_LEFT_MID = 7
        private const val AREA_FRONT_RIGHT_MID = 8
        private const val SENSOR_MIN_CM = 0
        private const val SENSOR_MAX_CM = 155
        private const val LOCK_MIN_CM = 20
        private const val LOCK_MAX_CM = 140
        private const val ACQUIRE_SAMPLES = 5
        private const val ACQUIRE_SPREAD_CM = 15
        private const val DEPARTURE_DELTA_CM = 40
        private const val CONFIRM_DEPARTURE_SAMPLES = 3
        private const val SAMPLE_MS = 200L
        private const val STATIONARY_ARM_MS = 2_500L
        private const val COOLDOWN_MS = 15_000L
        private const val TELEMETRY_LOG_MS = 2_000L
    }
}
