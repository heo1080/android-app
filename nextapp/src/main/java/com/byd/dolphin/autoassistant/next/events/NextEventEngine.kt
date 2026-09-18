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

    fun start() {
        if (job != null) return
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
            audio.emit(NextSettings.EVENT_GEAR, it)
        }
        transition(before.driveMode.value, now.driveMode.value) {
            audio.emit(NextSettings.EVENT_DRIVE, it)
        }
        transition(before.regenMode.value, now.regenMode.value) {
            audio.emit(NextSettings.EVENT_REGEN, if (it == "STANDARD") "스탠다드" else "하이")
        }

        if (before.snowMode.value != now.snowMode.value && now.snowMode.value == true) {
            audio.emit(NextSettings.EVENT_SNOW, "스노우모드")
        }

        val beforeIcc = before.iccActive.value
        val nowIcc = now.iccActive.value
        if (beforeIcc != null && nowIcc != null && beforeIcc != nowIcc) {
            audio.emit(NextSettings.EVENT_ICC, if (nowIcc) "ON" else "OFF")
        }

        processAutoHold(before.autoHoldRaw.value, now.autoHoldRaw.value)
        processBsd(before, now)
    }

    private fun processAutoHold(beforeRaw: Int?, nowRaw: Int?) {
        if (beforeRaw == null || nowRaw == null || beforeRaw == nowRaw) return

        val beforeSwitch = beforeRaw == 1 || beforeRaw == 2
        val nowSwitch = nowRaw == 1 || nowRaw == 2
        if (beforeSwitch != nowSwitch) {
            audio.emit(NextSettings.EVENT_AUTOHOLD_SWITCH, if (nowSwitch) "ON" else "OFF")
        }

        // Physical HOLD uses only the explicit raw=2 path in the clean Next
        // baseline. This intentionally avoids the old pedal fallback that could
        // announce HOLD and RELEASE together during launch.
        val beforeHolding = beforeRaw == 2
        val nowHolding = nowRaw == 2
        if (!beforeHolding && nowHolding) {
            audio.emit(NextSettings.EVENT_AUTOHOLD_HOLD, "체결")
        } else if (beforeHolding && nowRaw == 1) {
            audio.emit(NextSettings.EVENT_AUTOHOLD_HOLD, "해제")
        }
    }

    private fun processBsd(before: VehicleState, now: VehicleState) {
        val beforeBsd = before.bsdRaw.value
        val nowBsd = now.bsdRaw.value
        val direction = now.turn.value
        if (beforeBsd == null || nowBsd == null || beforeBsd == nowBsd) return
        if (direction != "LEFT" && direction != "RIGHT") return

        val timestamp = System.currentTimeMillis()
        if (timestamp - lastBsdAt < 1500L) return
        lastBsdAt = timestamp
        audio.emit(NextSettings.EVENT_BSD, if (direction == "LEFT") "왼쪽" else "오른쪽")
        NextLogger.i("EVENT", "BSD raw " + beforeBsd + " -> " + nowBsd + " direction=" + direction)
    }

    private inline fun <T> transition(before: T?, now: T?, block: (T) -> Unit) {
        if (before != null && now != null && before != now) block(now)
    }
}
