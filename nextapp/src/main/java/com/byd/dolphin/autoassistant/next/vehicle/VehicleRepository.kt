package com.byd.dolphin.autoassistant.next.vehicle

import android.content.Context
import com.byd.dolphin.autoassistant.next.core.NextLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VehicleRepository(context: Context) {
    private val app = context.applicationContext
    private val gateway = BydGateway(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state

    private var sampler: Job? = null
    private var comfortTick = 0
    @Volatile private var surroundingVisionActive = false

    fun start() {
        if (sampler != null) return
        sampler = scope.launch {
            NextLogger.i("VEHICLE", "Next vehicle sampler start")
            while (isActive) {
                sample()
                delay(250L)
            }
        }
    }

    fun stop() {
        sampler?.cancel()
        sampler = null
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val previous = _state.value

        val gearRaw = gateway.readNumber(BydGateway.GEARBOX, "getCurrentGear")?.toInt()
        val instrumentDriveRaw = gateway.readNumber(BydGateway.INSTRUMENT, "getCurrentDriveInterFace")?.toInt()
        val operationRaw = gateway.readNumber(BydGateway.ENERGY, "getOperationMode")?.toInt()
        val regenRaw = gateway.readNumber(BydGateway.SETTING, "getEnergyFeedback")?.toInt()
        val snowRaw = gateway.readNumber(BydGateway.ENERGY, "getRoadSurfaceMode")?.toInt()
        val avhRaw = gateway.readNumber(BydGateway.ADAS, "getAVHState")?.toInt()
        val iccRaw = gateway.readNumber(BydGateway.ADAS, "getTJAState")?.toInt()
        val bsdRaw = gateway.readNumber(BydGateway.ADAS, "getBSDState")?.toInt()
        val turnRaw = gateway.readNumber(BydGateway.LIGHT, "getTurnLightState")?.toInt()
        val speed = gateway.readNumber(BydGateway.SPEED, "getCurrentSpeed")?.toDouble()
        val brake = gateway.readNumber(BydGateway.SPEED, "getBrakeDeepness")?.toInt()
        val accel = gateway.readNumber(BydGateway.SPEED, "getAccelerateDeepness")?.toInt()
        val visionActive = surroundingVisionActive
        val panoramaWorkRaw = if (visionActive) {
            gateway.readNumber(BydGateway.PANORAMA, "getPanoWorkState")?.toInt()
        } else null
        val panoramaOutputRaw = if (visionActive) {
            gateway.readNumber(BydGateway.PANORAMA, "getPanoOutputState")?.toInt()
        } else null
        val radarProbeStates = if (visionActive) {
            gateway.readIntArray(BydGateway.RADAR, "getAllRadarProbeStates")
        } else null
        val radarDistances = if (visionActive) {
            (1..8).map { area ->
                gateway.readNumber(BydGateway.RADAR, "getRadarObstacleDistance", area)
                    ?.toInt()
                    ?.takeIf { it in 0..155 }
            }
        } else {
            List(8) { null }
        }

        val gear = SignalResolver.gear(gearRaw)
        val drive = SignalResolver.driveMode(instrumentDriveRaw, operationRaw)
        val regen = SignalResolver.regen(regenRaw)
        val snow = SignalResolver.snow(snowRaw)
        val icc = SignalResolver.icc(iccRaw)
        val turn = SignalResolver.turn(turnRaw)

        var driverHeat = previous.driverHeat
        var passengerHeat = previous.passengerHeat
        var steeringHeat = previous.steeringHeat
        comfortTick++
        if (comfortTick >= 4) {
            comfortTick = 0
            val driverRaw = gateway.readIntFirst(BydGateway.SETTING, listOf("getSeatHeatingState", "getSeatHeatingState1"), 1)
            val passengerRaw = gateway.readIntFirst(BydGateway.SETTING, listOf("getSeatHeatingState", "getSeatHeatingState1"), 2)
            val steeringRaw = gateway.readNumber(BydGateway.SETTING, "getSteeringWheelHeatingState")?.toInt()
            val dr = SignalResolver.seatHeat(driverRaw)
            val pr = SignalResolver.seatHeat(passengerRaw)
            val sr = SignalResolver.steeringHeat(steeringRaw)
            driverHeat = next(previous.driverHeat, dr.value, driverRaw, dr.confidence, now)
            passengerHeat = next(previous.passengerHeat, pr.value, passengerRaw, pr.confidence, now)
            steeringHeat = next(previous.steeringHeat, sr.value, steeringRaw, sr.confidence, now)
        }

        val nextState = VehicleState(
            gear = next(previous.gear, gear.value, gearRaw, gear.confidence, now),
            driveMode = next(previous.driveMode, drive.value, operationRaw ?: instrumentDriveRaw, drive.confidence, now),
            regenMode = next(previous.regenMode, regen.value, regenRaw, regen.confidence, now),
            snowMode = next(previous.snowMode, snow.value, snowRaw, snow.confidence, now),
            autoHoldRaw = next(previous.autoHoldRaw, avhRaw, avhRaw, Confidence.BETA, now),
            iccActive = next(previous.iccActive, icc.value, iccRaw, icc.confidence, now),
            bsdRaw = next(previous.bsdRaw, bsdRaw, bsdRaw, Confidence.BETA, now),
            turn = next(previous.turn, turn.value, turnRaw, turn.confidence, now),
            speedKph = next(previous.speedKph, speed, speed, Confidence.BETA, now),
            brakeDepth = next(previous.brakeDepth, brake, brake, Confidence.BETA, now),
            acceleratorDepth = next(previous.acceleratorDepth, accel, accel, Confidence.BETA, now),
            driverHeat = driverHeat,
            passengerHeat = passengerHeat,
            steeringHeat = steeringHeat,
            panoramaWork = next(previous.panoramaWork, panoramaWorkRaw, panoramaWorkRaw, Confidence.BETA, now),
            panoramaOutput = next(previous.panoramaOutput, panoramaOutputRaw, panoramaOutputRaw, Confidence.BETA, now),
            parkingSensors = buildParkingSensors(
                previous = previous.parkingSensors,
                probeStates = radarProbeStates,
                distances = radarDistances,
                now = now
            ),
            lastUpdatedMs = now
        )
        logTransitions(previous, nextState, instrumentDriveRaw, operationRaw)
        _state.value = nextState
    }

    private fun buildParkingSensors(
        previous: List<ParkingSensorSample>,
        probeStates: IntArray?,
        distances: List<Int?>,
        now: Long
    ): List<ParkingSensorSample> {
        val labels = listOf(
            "좌전", "우전", "좌후", "우후",
            "좌측", "우측", "전좌중", "전우중"
        )
        return labels.indices.map { index ->
            val prior = previous.getOrNull(index)
            val probe = probeStates?.getOrNull(index)
            val distance = distances.getOrNull(index)
            val hasFresh = probe != null || distance != null
            if (hasFresh) {
                ParkingSensorSample(
                    area = index + 1,
                    label = labels[index],
                    probeStateRaw = probe,
                    distanceCm = distance,
                    timestampMs = now,
                    confidence = Confidence.BETA,
                    stale = false
                )
            } else if (prior != null) {
                val age = if (prior.timestampMs == 0L) Long.MAX_VALUE else now - prior.timestampMs
                prior.copy(stale = age > 1500L)
            } else {
                ParkingSensorSample(
                    area = index + 1,
                    label = labels[index],
                    confidence = Confidence.BETA,
                    stale = true
                )
            }
        }
    }

    fun setSurroundingVisionActive(active: Boolean) {
        if (surroundingVisionActive == active) return
        surroundingVisionActive = active
        NextLogger.i("VISION", "surroundingVisionActive=" + active)
    }

    fun setSeatHeat(seat: Int, level: Int): Boolean {
        val raw = level.coerceIn(0, 2) + 1
        val ok = gateway.command(
            BydGateway.SETTING,
            listOf("setSeatHeatingState", "setSeatHeatingState1"),
            seat, raw
        )
        NextLogger.i("COMFORT", "seat=$seat level=$level accepted=$ok")
        return ok
    }

    fun setSteeringHeat(enabled: Boolean): Boolean {
        val ok = gateway.command(
            BydGateway.SETTING,
            listOf("setSteeringWheelHeatingState"),
            if (enabled) 2 else 1
        )
        NextLogger.i("COMFORT", "steeringHeat=$enabled accepted=$ok")
        return ok
    }

    private fun <T> next(
        previous: SignalValue<T>,
        value: T?,
        raw: Number?,
        confidence: Confidence,
        now: Long
    ): SignalValue<T> {
        return if (value != null) {
            SignalValue(value, raw, now, confidence, stale = false)
        } else {
            val age = if (previous.timestampMs == 0L) Long.MAX_VALUE else now - previous.timestampMs
            previous.copy(stale = age > 1500L)
        }
    }

    private fun logTransitions(
        before: VehicleState,
        after: VehicleState,
        instrumentDriveRaw: Int?,
        operationRaw: Int?
    ) {
        if (before.gear.value != after.gear.value && after.gear.value != null) {
            NextLogger.i("SIGNAL", "gear " + before.gear.value + " -> " + after.gear.value + " raw=" + after.gear.raw)
        }
        if (before.driveMode.value != after.driveMode.value && after.driveMode.value != null) {
            NextLogger.i(
                "SIGNAL",
                "drive " + before.driveMode.value + " -> " + after.driveMode.value +
                    " instrumentRaw=" + instrumentDriveRaw + " operationRaw=" + operationRaw +
                    " confidence=" + after.driveMode.confidence
            )
        }
        if (before.autoHoldRaw.value != after.autoHoldRaw.value && after.autoHoldRaw.value != null) {
            NextLogger.i("SIGNAL", "AVH raw " + before.autoHoldRaw.value + " -> " + after.autoHoldRaw.value)
        }
        if (before.bsdRaw.value != after.bsdRaw.value && after.bsdRaw.value != null) {
            NextLogger.i("SIGNAL", "BSD raw " + before.bsdRaw.value + " -> " + after.bsdRaw.value + " turn=" + after.turn.value)
        }
    }
}
