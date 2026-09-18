package com.byd.dolphin.autoassistant.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class VehicleRepository(private val gateway: VehicleGateway) {
    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    suspend fun run() {
        while (coroutineContext.isActive) {
            val fresh = runCatching { gateway.readSnapshot() }.getOrNull()
            if (fresh != null) _state.value = merge(_state.value, fresh)
            delay(500L)
        }
    }

    suspend fun setSeatHeat(seat: Int, level: Int): Boolean =
        gateway.setSeatHeat(seat, level)

    suspend fun setSteeringHeat(enabled: Boolean): Boolean =
        gateway.setSteeringHeat(enabled)

    suspend fun setAcPower(enabled: Boolean): Boolean =
        gateway.setAcPower(enabled)

    private fun merge(old: VehicleState, fresh: VehicleState): VehicleState =
        fresh.copy(
            driverHeat = fresh.driverHeat ?: old.driverHeat,
            passengerHeat = fresh.passengerHeat ?: old.passengerHeat,
            steeringHeat = fresh.steeringHeat ?: old.steeringHeat,
            acOn = fresh.acOn ?: old.acOn,
            driveRaw = fresh.driveRaw ?: old.driveRaw,
            driveLabel = fresh.driveLabel ?: old.driveLabel,
            regenRaw = fresh.regenRaw ?: old.regenRaw,
            autoHoldRaw = fresh.autoHoldRaw ?: old.autoHoldRaw,
            bsdRaw = fresh.bsdRaw ?: old.bsdRaw,
            turnRaw = fresh.turnRaw ?: old.turnRaw,
            speedKph = fresh.speedKph ?: old.speedKph
        )
}
