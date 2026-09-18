package com.byd.dolphin.autoassistant.core.event

import com.byd.dolphin.autoassistant.core.NextLog
import com.byd.dolphin.autoassistant.core.audio.AlertCatalog
import com.byd.dolphin.autoassistant.core.audio.AlertEngine
import com.byd.dolphin.autoassistant.core.audio.OutputMode
import com.byd.dolphin.autoassistant.core.model.VehicleState
import com.byd.dolphin.autoassistant.core.state.VehicleStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class VehicleEventEngine(
    private val stateStore: VehicleStateStore,
    private val alerts: AlertEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var previous: VehicleState? = null

    private var holdState: Boolean? = null
    private var holdBrakeSeen = false
    private var lastBsdAt = 0L

    private val acquisition = ArrayDeque<Int>()
    private var leadingLockedCm: Int? = null
    private var leadingDepartSamples = 0
    private var stationarySince = 0L
    private var lastLeadingAt = 0L
    private var wasStationaryForLeading = false

    fun start() {
        if (job != null) return
        job = scope.launch {
            stateStore.state.collect { current ->
                val old = previous
                if (old != null) process(old, current)
                previous = current
            }
        }
        NextLog.i("EVENT", "vehicle event engine started")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun process(old: VehicleState, now: VehicleState) {
        if (old.gear != null && now.gear != null && old.gear != now.gear) {
            alerts.dispatch(AlertCatalog.GEAR, now.gear)
        }
        if (old.driveMode != null && now.driveMode != null && old.driveMode != now.driveMode) {
            alerts.dispatch(AlertCatalog.DRIVE, koreanDrive(now.driveMode))
        }
        if (old.regenMode != null && now.regenMode != null && old.regenMode != now.regenMode) {
            alerts.dispatch(AlertCatalog.REGEN, koreanRegen(now.regenMode))
        }
        if (old.snowMode == false && now.snowMode == true) {
            alerts.dispatch(AlertCatalog.SNOW, "ON")
        }
        if (old.epbApplied != null && now.epbApplied != null && old.epbApplied != now.epbApplied) {
            alerts.dispatch(AlertCatalog.EPB, if (now.epbApplied) "체결" else "해제")
        }
        if (old.iccActive != null && now.iccActive != null && old.iccActive != now.iccActive) {
            alerts.dispatch(AlertCatalog.ICC, if (now.iccActive) "ON" else "OFF")
        }

        processAutoHold(old, now)
        processBsd(old, now)
        processLeading(now)
    }

    private fun processAutoHold(old: VehicleState, now: VehicleState) {
        val beforeSwitch = switchState(old.avhRaw)
        val currentSwitch = switchState(now.avhRaw)
        if (beforeSwitch != null && currentSwitch != null && beforeSwitch != currentSwitch) {
            alerts.dispatch(AlertCatalog.AUTOHOLD_SWITCH, if (currentSwitch) "ON" else "OFF")
        }

        val explicitHold = now.avhRaw == 2
        val switchOn = currentSwitch == true
        val stationaryD = now.gear == "D" && (now.speedKmh ?: Double.NaN) in 0.0..0.3
        val moving = (now.speedKmh ?: 0.0) > 0.5 || (now.acceleratorDepth ?: 0) > 0

        var nextHold = holdState
        if (!switchOn) {
            holdBrakeSeen = false
            if (holdState == true) nextHold = false
        } else if (explicitHold) {
            holdBrakeSeen = false
            nextHold = true
        } else if (holdState == true) {
            if (moving || now.gear != "D") {
                nextHold = false
                holdBrakeSeen = false
            }
        } else {
            if (stationaryD && (now.brakeDepth ?: 0) > 0) {
                holdBrakeSeen = true
            } else if (
                stationaryD &&
                holdBrakeSeen &&
                (now.brakeDepth ?: 0) == 0 &&
                (now.acceleratorDepth ?: 0) == 0
            ) {
                holdBrakeSeen = false
                nextHold = true
            } else if (!stationaryD || moving) {
                holdBrakeSeen = false
            }
        }

        if (holdState != null && nextHold != null && holdState != nextHold) {
            alerts.dispatch(AlertCatalog.AUTOHOLD_HOLD, if (nextHold) "체결" else "해제")
            NextLog.i("EVENT", "AutoHold holding " + holdState + " -> " + nextHold +
                " raw=" + now.avhRaw + " speed=" + now.speedKmh)
        } else if (holdState == null && nextHold == true) {
            alerts.dispatch(AlertCatalog.AUTOHOLD_HOLD, "체결")
            NextLog.i("EVENT", "AutoHold first confirmed hold raw=" + now.avhRaw)
        }
        holdState = nextHold
    }

    private fun processBsd(old: VehicleState, now: VehicleState) {
        val oldRaw = old.bsdRaw ?: return
        val newRaw = now.bsdRaw ?: return
        if (oldRaw == newRaw) return
        val direction = now.turnDirection
        if (direction != "LEFT" && direction != "RIGHT") return
        val time = System.currentTimeMillis()
        if (time - lastBsdAt < 1_500L) return
        lastBsdAt = time
        alerts.dispatch(AlertCatalog.BSD, if (direction == "LEFT") "왼쪽" else "오른쪽")
        NextLog.i("EVENT", "BSD change " + oldRaw + " -> " + newRaw + " turn=" + direction)
    }

    private fun processLeading(now: VehicleState) {
        val profile = alerts.repository.getProfile(AlertCatalog.LEADING)
        if (profile.mode == OutputMode.OFF) {
            resetLeading()
            return
        }

        val speed = now.speedKmh ?: return
        val stationary = speed in 0.0..0.5 && (now.gear == "D" || now.gear == "N")
        val time = System.currentTimeMillis()

        if (stationary && !wasStationaryForLeading) {
            stationarySince = time
            resetLeadingTarget()
        } else if (!stationary && wasStationaryForLeading) {
            resetLeadingTarget()
        }
        wasStationaryForLeading = stationary
        if (!stationary || time - stationarySince < 2_500L || time - lastLeadingAt < 15_000L) return

        val left = now.frontLeftCm
        val right = now.frontRightCm
        val distance = listOfNotNull(left, right)
            .filter { it in 20 until 155 }
            .minOrNull()

        val locked = leadingLockedCm
        if (locked == null) {
            if (distance != null && distance in 20..154) {
                acquisition.addLast(distance)
                while (acquisition.size > 5) acquisition.removeFirst()
                if (acquisition.size == 5 && acquisition.max() - acquisition.min() <= 15) {
                    leadingLockedCm = acquisition.sorted()[acquisition.size / 2]
                    acquisition.clear()
                    NextLog.i("LEADING", "locked baseline=" + leadingLockedCm)
                }
            } else {
                acquisition.clear()
            }
            return
        }

        val clear = left == 155 && right == 155
        val movedAway = distance != null && distance - locked >= 40
        leadingDepartSamples = if (clear || movedAway) leadingDepartSamples + 1 else 0
        if (leadingDepartSamples >= 3) {
            lastLeadingAt = time
            alerts.dispatch(AlertCatalog.LEADING, "출발")
            NextLog.i("LEADING", "departure baseline=" + locked + " left=" + left + " right=" + right)
            resetLeadingTarget()
        }
    }

    private fun resetLeading() {
        wasStationaryForLeading = false
        stationarySince = 0L
        resetLeadingTarget()
    }

    private fun resetLeadingTarget() {
        acquisition.clear()
        leadingLockedCm = null
        leadingDepartSamples = 0
    }

    private fun switchState(raw: Int?): Boolean? = when (raw) {
        0 -> false
        1, 2 -> true
        else -> null
    }

    private fun koreanDrive(value: String): String = when (value) {
        "ECO" -> "에코"
        "NORMAL" -> "노멀"
        "SPORT" -> "스포츠"
        else -> value
    }

    private fun koreanRegen(value: String): String = when (value) {
        "STANDARD" -> "스탠다드"
        "HIGH" -> "하이"
        else -> value
    }
}
