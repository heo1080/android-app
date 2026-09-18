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
                    .onSuccess { mutable.value = it }
                    .onFailure { NextLog.e("STATE", "poll failed", it) }
                delay(300L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun setSeatHeat(seat: Int, level: Int): Boolean = gateway.setSeatHeat(seat, level)
    fun setSteeringHeat(enabled: Boolean): Boolean = gateway.setSteeringHeat(enabled)
    fun setAcPower(enabled: Boolean): Boolean = gateway.setAcPower(enabled)
}
