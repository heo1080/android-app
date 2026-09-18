package com.byd.dolphin.autoassistant.core.model

data class SignalStatus(
    val source: String,
    val updatedAtMs: Long,
    val confidence: Float,
    val available: Boolean = true
) {
    fun isStale(nowMs: Long = System.currentTimeMillis(), timeoutMs: Long = 2_500L): Boolean =
        !available || nowMs - updatedAtMs > timeoutMs
}

data class VehicleState(
    val connected: Boolean = false,
    val sampledAtMs: Long = 0L,
    val lastProbeMs: Long = 0L,
    val signals: Map<String, SignalStatus> = emptyMap(),

    val speedKmh: Double? = null,
    val gear: String? = null,
    val gearRaw: Int? = null,
    val epbApplied: Boolean? = null,
    val epbRaw: Int? = null,
    val driveMode: String? = null,
    val driveModeRaw: Int? = null,
    val regenMode: String? = null,
    val regenRaw: Int? = null,
    val snowMode: Boolean? = null,
    val snowRaw: Int? = null,
    val avhRaw: Int? = null,
    val autoHoldHolding: Boolean? = null,
    val brakeDepth: Int? = null,
    val acceleratorDepth: Int? = null,
    val iccActive: Boolean? = null,
    val tjaRaw: Int? = null,
    val bsdRaw: Int? = null,
    val turnDirection: String? = null,
    val turnRaw: Int? = null,
    val frontLeftCm: Int? = null,
    val frontRightCm: Int? = null,
    val driverSeatHeat: Int? = null,
    val passengerSeatHeat: Int? = null,
    val steeringHeat: Boolean? = null,
    val acOn: Boolean? = null
) {
    fun signalFresh(
        id: String,
        nowMs: Long = System.currentTimeMillis(),
        timeoutMs: Long = 2_500L
    ): Boolean = signals[id]?.isStale(nowMs, timeoutMs) == false

    fun connectionFresh(
        nowMs: Long = System.currentTimeMillis(),
        timeoutMs: Long = 2_500L
    ): Boolean = connected && lastProbeMs > 0L && nowMs - lastProbeMs <= timeoutMs
}
