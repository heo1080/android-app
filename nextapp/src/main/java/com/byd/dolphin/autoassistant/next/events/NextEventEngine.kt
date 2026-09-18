package com.byd.dolphin.autoassistant.next.events

import com.byd.dolphin.autoassistant.next.audio.NextAudioEngine
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.settings.NextSettings
import com.byd.dolphin.autoassistant.next.vehicle.VehicleRepository
import com.byd.dolphin.autoassistant.next.vehicle.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NextEventEngine(
    private val repository: VehicleRepository,
    private val audio: NextAudioEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var previous: VehicleState? = null
    private var lastBsdAt = 0L
    private var startedAtMs = 0L

    // Korean Dolphin BETA fallback for physical AutoHold state.
    private var brakePressedWhileStopped = false
    private var inferredHolding = false
    private var inferredHoldAtMs = 0L

    fun start() {
        if (job != null) return
        startedAtMs = System.currentTimeMillis()
        job = scope.launch {
            repository.state.collectLatest { current ->
                val before = previous
                previous = current
                if (before != null) process(before, current)
            }
        }
        NextLogger.i("EVENT", "Next event engine start")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun process(before: VehicleState, now: VehicleState) {
        transition(before.gear.value, now.gear.value) {
            dispatch(NextSettings.EVENT_GEAR, it, "gear")
        }
        transition(before.driveMode.value, now.driveMode.value) {
            dispatch(NextSettings.EVENT_DRIVE, it, "drive")
        }

        // A missing/stale regen getter can recover only after the physical button
        // is pressed. Treat a first fresh value after the startup grace period as
        // a real transition so the button is not silently lost.
        val regenChanged = now.regenMode.value != null &&
            !now.regenMode.stale &&
            (
                before.regenMode.value != now.regenMode.value ||
                    (before.regenMode.stale && now.regenMode.timestampMs > before.regenMode.timestampMs)
            )
        if (regenChanged && System.currentTimeMillis() - startedAtMs > STARTUP_BASELINE_GRACE_MS) {
            val spoken = if (now.regenMode.value == "STANDARD") "스탠다드" else "하이"
            dispatch(
                NextSettings.EVENT_REGEN,
                spoken,
                "regen raw=" + now.regenMode.raw + " before=" + before.regenMode.value +
                    " staleBefore=" + before.regenMode.stale
            )
        }

        val beforeSnow = before.snowMode.value
        val nowSnow = now.snowMode.value
        if (beforeSnow != null && nowSnow != null && beforeSnow != nowSnow) {
            dispatch(NextSettings.EVENT_SNOW, if (nowSnow) "ON" else "OFF", "snow")
        }

        val beforeIcc = before.iccActive.value
        val nowIcc = now.iccActive.value
        if (beforeIcc != null && nowIcc != null && beforeIcc != nowIcc) {
            dispatch(NextSettings.EVENT_ICC, if (nowIcc) "ON" else "OFF", "icc")
        }

        val beforeEpb = before.epbApplied.value
        val nowEpb = now.epbApplied.value
        if (beforeEpb != null && nowEpb != null && beforeEpb != nowEpb) {
            dispatch(NextSettings.EVENT_EPB, if (nowEpb) "ON" else "OFF", "epb raw=" + now.epbApplied.raw)
        }

        processAutoHold(before, now)
        processBsd(before, now)
    }

    private fun processAutoHold(before: VehicleState, now: VehicleState) {
        val beforeRaw = before.autoHoldRaw.value
        val nowRaw = now.autoHoldRaw.value

        if (beforeRaw != null && nowRaw != null && beforeRaw != nowRaw) {
            val beforeSwitch = beforeRaw == 1 || beforeRaw == 2
            val nowSwitch = nowRaw == 1 || nowRaw == 2
            if (beforeSwitch != nowSwitch) {
                dispatch(
                    NextSettings.EVENT_AUTOHOLD_SWITCH,
                    if (nowSwitch) "ON" else "OFF",
                    "AVH raw " + beforeRaw + " -> " + nowRaw
                )
            }

            val beforeHolding = beforeRaw == 2
            val nowHolding = nowRaw == 2
            if (!beforeHolding && nowHolding) {
                inferredHolding = true
                inferredHoldAtMs = System.currentTimeMillis()
                dispatch(NextSettings.EVENT_AUTOHOLD_HOLD, "체결", "explicit AVH raw=2")
            } else if (beforeHolding && nowRaw == 1) {
                inferredHolding = false
                dispatch(NextSettings.EVENT_AUTOHOLD_HOLD, "해제", "explicit AVH raw 2->1")
            }

            if (!nowSwitch) {
                brakePressedWhileStopped = false
                inferredHolding = false
            }
        }

        // BETA fallback for cars where AVH only reports switch 0/1.
        if (nowRaw != 1 || now.gear.value != "D") return
        val speed = now.speedKph.value ?: return
        val brake = now.brakeDepth.value ?: return
        val accel = now.acceleratorDepth.value ?: 0
        val stopped = speed <= 0.3

        if (!inferredHolding && stopped && brake > 0) {
            brakePressedWhileStopped = true
        }

        // Driver releases the brake while the car remains stopped and AVH is on.
        // This is the physical hold point on the raw-0/1 firmware candidate.
        if (
            !inferredHolding &&
            brakePressedWhileStopped &&
            stopped &&
            brake <= 0 &&
            accel <= 0
        ) {
            inferredHolding = true
            inferredHoldAtMs = System.currentTimeMillis()
            brakePressedWhileStopped = false
            dispatch(
                NextSettings.EVENT_AUTOHOLD_HOLD,
                "체결",
                "BETA inferred D+stopped+brake release raw=" + nowRaw
            )
            return
        }

        // Do not allow the old engage+release double announcement. A hold must
        // exist for at least 700 ms before a movement/accelerator release event.
        if (
            inferredHolding &&
            System.currentTimeMillis() - inferredHoldAtMs >= HOLD_MIN_MS &&
            (speed > 0.8 || accel > 0 || now.gear.value != "D")
        ) {
            inferredHolding = false
            brakePressedWhileStopped = false
            dispatch(
                NextSettings.EVENT_AUTOHOLD_HOLD,
                "해제",
                "BETA inferred movement speed=" + speed + " accel=" + accel
            )
        }
    }

    private fun processBsd(before: VehicleState, now: VehicleState) {
        val beforeBsd = before.bsdRaw.value
        val nowBsd = now.bsdRaw.value
        val direction = now.turn.value
        if (beforeBsd == null || nowBsd == null || beforeBsd == nowBsd) return
        if (direction != "LEFT" && direction != "RIGHT") {
            NextLogger.i(
                "EVENT_DETECTED",
                "bsd raw " + beforeBsd + " -> " + nowBsd + " suppressed turn=" + direction
            )
            return
        }

        val timestamp = System.currentTimeMillis()
        if (timestamp - lastBsdAt < 1500L) return
        lastBsdAt = timestamp
        dispatch(
            NextSettings.EVENT_BSD,
            if (direction == "LEFT") "왼쪽" else "오른쪽",
            "BSD raw " + beforeBsd + " -> " + nowBsd + " direction=" + direction
        )
    }

    private fun dispatch(eventKey: String, state: String, detail: String) {
        NextLogger.i(
            "EVENT_DETECTED",
            "key=" + eventKey + " state=" + state + " detail=" + detail
        )
        audio.emit(eventKey, state)
        NextLogger.i("EVENT_DISPATCH", "key=" + eventKey + " state=" + state)
    }

    private inline fun <T> transition(before: T?, now: T?, block: (T) -> Unit) {
        if (before != null && now != null && before != now) block(now)
    }

    companion object {
        private const val STARTUP_BASELINE_GRACE_MS = 2_000L
        private const val HOLD_MIN_MS = 700L
    }
}
