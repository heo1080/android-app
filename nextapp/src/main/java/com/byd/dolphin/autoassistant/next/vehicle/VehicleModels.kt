package com.byd.dolphin.autoassistant.next.vehicle

enum class Confidence { VERIFIED, BETA, LAB, UNKNOWN }

data class SignalValue<T>(
    val value: T?,
    val raw: Number? = null,
    val timestampMs: Long = 0L,
    val confidence: Confidence = Confidence.UNKNOWN,
    val stale: Boolean = true
)

data class ParkingSensorSample(
    val area: Int,
    val label: String,
    val probeStateRaw: Int? = null,
    val distanceCm: Int? = null,
    val timestampMs: Long = 0L,
    val confidence: Confidence = Confidence.BETA,
    val stale: Boolean = true
)

data class VehicleState(
    val gear: SignalValue<String> = SignalValue(null),
    val driveMode: SignalValue<String> = SignalValue(null),
    val regenMode: SignalValue<String> = SignalValue(null),
    val snowMode: SignalValue<Boolean> = SignalValue(null),
    val autoHoldRaw: SignalValue<Int> = SignalValue(null),
    val iccActive: SignalValue<Boolean> = SignalValue(null),
    val bsdRaw: SignalValue<Int> = SignalValue(null),
    val turn: SignalValue<String> = SignalValue(null),
    val speedKph: SignalValue<Double> = SignalValue(null),
    val brakeDepth: SignalValue<Int> = SignalValue(null),
    val acceleratorDepth: SignalValue<Int> = SignalValue(null),
    val driverHeat: SignalValue<Int> = SignalValue(null),
    val passengerHeat: SignalValue<Int> = SignalValue(null),
    val steeringHeat: SignalValue<Boolean> = SignalValue(null),
    val epbApplied: SignalValue<Boolean> = SignalValue(null),
    val panoramaWork: SignalValue<Int> = SignalValue(null),
    val panoramaOutput: SignalValue<Int> = SignalValue(null),
    val parkingSensors: List<ParkingSensorSample> = emptyList(),
    val lastUpdatedMs: Long = 0L
)
