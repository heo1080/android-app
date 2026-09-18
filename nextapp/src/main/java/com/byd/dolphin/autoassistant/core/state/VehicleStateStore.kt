package com.byd.dolphin.autoassistant.core.state

import android.content.Context
import com.byd.dolphin.autoassistant.core.NextLog
import com.byd.dolphin.autoassistant.core.model.VehicleState
import com.byd.dolphin.autoassistant.core.vehicle.BydVehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VehicleStateStore(context: Context) {
    private val gateway = BydVehicleGateway(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = mutable.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            NextLog.i("STATE", "vehicle polling started")
            while (isActive) {
                val previous = mutable.value
                runCatching { gateway.readState(previous) }
                    .onSuccess { fresh -> mutable.value = merge(mutable.value, fresh) }
                    .onFailure { NextLog.e("STATE", "poll failed", it) }
                delay(300L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun setSeatHeat(seat: Int, level: Int): Boolean = withContext(Dispatchers.IO) {
        val accepted = gateway.setSeatHeat(seat, level)
        if (!accepted) {
            NextLog.w("READBACK", "seat command rejected seat=" + seat + " level=" + level)
            return@withContext false
        }
        delay(350L)
        val fresh = gateway.readState(mutable.value)
        mutable.value = merge(mutable.value, fresh)
        val actual = if (seat == 1) mutable.value.driverSeatHeat else mutable.value.passengerSeatHeat
        val signalId = if (seat == 1) "seatDriver" else "seatPassenger"
        val verified = actual == level && mutable.value.signalFresh(signalId)
        NextLog.i("READBACK", "seat=" + seat + " requested=" + level + " actual=" + actual + " verified=" + verified)
        verified
    }

    suspend fun setSteeringHeat(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val accepted = gateway.setSteeringHeat(enabled)
        if (!accepted) {
            NextLog.w("READBACK", "steering command rejected enabled=" + enabled)
            return@withContext false
        }
        delay(350L)
        val fresh = gateway.readState(mutable.value)
        mutable.value = merge(mutable.value, fresh)
        val verified = mutable.value.steeringHeat == enabled &&
            mutable.value.signalFresh("steeringHeat")
        NextLog.i(
            "READBACK",
            "steering requested=" + enabled + " actual=" + mutable.value.steeringHeat + " verified=" + verified
        )
        verified
    }

    suspend fun setAcPower(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val accepted = gateway.setAcPower(enabled)
        if (!accepted) {
            NextLog.w("READBACK", "AC command rejected enabled=" + enabled)
            return@withContext false
        }
        delay(450L)
        val fresh = gateway.readState(mutable.value)
        mutable.value = merge(mutable.value, fresh)
        val verified = mutable.value.acOn == enabled && mutable.value.signalFresh("ac")
        NextLog.i(
            "READBACK",
            "AC requested=" + enabled + " actual=" + mutable.value.acOn + " verified=" + verified
        )
        verified
    }

    private fun merge(old: VehicleState, fresh: VehicleState): VehicleState {
        val now = System.currentTimeMillis()
        val lastProbe = if (fresh.lastProbeMs > 0L) fresh.lastProbeMs else old.lastProbeMs
        val mergedSignals = old.signals.toMutableMap().apply { putAll(fresh.signals) }
        val connected = fresh.connected || (lastProbe > 0L && now - lastProbe <= 2_500L)

        return VehicleState(
            connected = connected,
            sampledAtMs = fresh.sampledAtMs.takeIf { it > 0L } ?: old.sampledAtMs,
            lastProbeMs = lastProbe,
            signals = mergedSignals,

            speedKmh = fresh.speedKmh ?: old.speedKmh,
            gear = fresh.gear ?: old.gear,
            gearRaw = fresh.gearRaw ?: old.gearRaw,
            epbApplied = fresh.epbApplied ?: old.epbApplied,
            epbRaw = fresh.epbRaw ?: old.epbRaw,
            driveMode = fresh.driveMode ?: old.driveMode,
            driveModeRaw = fresh.driveModeRaw ?: old.driveModeRaw,
            regenMode = fresh.regenMode ?: old.regenMode,
            regenRaw = fresh.regenRaw ?: old.regenRaw,
            snowMode = fresh.snowMode ?: old.snowMode,
            snowRaw = fresh.snowRaw ?: old.snowRaw,
            avhRaw = fresh.avhRaw ?: old.avhRaw,
            autoHoldHolding = fresh.autoHoldHolding ?: old.autoHoldHolding,
            brakeDepth = fresh.brakeDepth ?: old.brakeDepth,
            acceleratorDepth = fresh.acceleratorDepth ?: old.acceleratorDepth,
            iccActive = fresh.iccActive ?: old.iccActive,
            tjaRaw = fresh.tjaRaw ?: old.tjaRaw,
            bsdRaw = fresh.bsdRaw ?: old.bsdRaw,
            turnDirection = fresh.turnDirection ?: old.turnDirection,
            turnRaw = fresh.turnRaw ?: old.turnRaw,
            frontLeftCm = fresh.frontLeftCm ?: old.frontLeftCm,
            frontRightCm = fresh.frontRightCm ?: old.frontRightCm,
            driverSeatHeat = fresh.driverSeatHeat ?: old.driverSeatHeat,
            passengerSeatHeat = fresh.passengerSeatHeat ?: old.passengerSeatHeat,
            steeringHeat = fresh.steeringHeat ?: old.steeringHeat,
            acOn = fresh.acOn ?: old.acOn
        )
    }
}
