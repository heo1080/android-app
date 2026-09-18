package com.byd.dolphin.autoassistant.next.diagnostics

import com.byd.dolphin.autoassistant.next.audio.AudioProbeEvent
import com.byd.dolphin.autoassistant.next.audio.AudioProbePhase
import com.byd.dolphin.autoassistant.next.audio.NextAudioEngine
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.vehicle.Confidence
import com.byd.dolphin.autoassistant.next.vehicle.VehicleRepository
import com.byd.dolphin.autoassistant.next.vehicle.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.max

enum class DriveObservationStatus {
    OBSERVED,
    NOT_USED,
    NO_SIGNAL,
    ISSUE
}

data class DriveHistoryEvent(
    val timestampMs: Long,
    val type: String,
    val value: String,
    val raw: String? = null,
    val confidence: Confidence = Confidence.UNKNOWN
)

data class DriveObservation(
    val id: String,
    val title: String,
    val status: DriveObservationStatus,
    val detail: String,
    val confidence: Confidence
)

data class RecentDriveSnapshot(
    val startedAtMs: Long,
    val capturedAtMs: Long,
    val observations: List<DriveObservation>,
    val events: List<DriveHistoryEvent>,
    val maxSpeedKph: Double?,
    val liveAudioRequests: Int,
    val liveAudioStarts: Int,
    val slowAudioStarts: Int,
    val audioErrors: Int
) {
    val durationMs: Long get() = (capturedAtMs - startedAtMs).coerceAtLeast(0L)
    val hasIssue: Boolean get() = observations.any { it.status == DriveObservationStatus.ISSUE }
}

class RecentDriveRecorder(
    private val repository: VehicleRepository,
    private val audio: NextAudioEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var vehicleJob: Job? = null
    private var audioJob: Job? = null

    private val lock = Any()
    private var startedAtMs = System.currentTimeMillis()
    private var previous: VehicleState? = null

    private val events = ArrayDeque<DriveHistoryEvent>()
    private val gears = linkedSetOf<String>()
    private val driveModes = linkedSetOf<String>()
    private val regenModes = linkedSetOf<String>()
    private var sawGearSignal = false
    private var sawDriveSignal = false
    private var sawRegenSignal = false
    private var sawAutoHoldSignal = false
    private var sawBsdSignal = false
    private var autoHoldTransitions = 0
    private var autoHoldHoldingCount = 0
    private var bsdLeftCount = 0
    private var bsdRightCount = 0
    private var bsdTransitions = 0
    private var maxSpeedKph: Double? = null

    private val pendingAudio = linkedMapOf<String, ArrayDeque<Long>>()
    private var liveAudioRequests = 0
    private var liveAudioStarts = 0
    private var slowAudioStarts = 0
    private var audioErrors = 0

    fun start() {
        if (vehicleJob != null || audioJob != null) return

        vehicleJob = scope.launch {
            repository.state.collect { current ->
                synchronized(lock) {
                    observeVehicle(current)
                }
            }
        }

        audioJob = scope.launch {
            audio.probeEvents.collect { event ->
                if (!event.probeKey.startsWith("LIVE_")) return@collect
                synchronized(lock) {
                    observeAudio(event)
                }
            }
        }

        NextLogger.i("DRIVE_HISTORY", "recent drive recorder started")
    }

    fun stop() {
        vehicleJob?.cancel()
        audioJob?.cancel()
        vehicleJob = null
        audioJob = null
    }

    fun snapshot(): RecentDriveSnapshot = synchronized(lock) {
        trimOldEvents(System.currentTimeMillis())
        val captured = System.currentTimeMillis()

        val observations = listOf(
            DriveObservation(
                id = "RECENT_GEAR",
                title = "RECENT GEAR",
                status = if (sawGearSignal) DriveObservationStatus.OBSERVED else DriveObservationStatus.NO_SIGNAL,
                detail = if (gears.isEmpty()) "NO SIGNAL" else gears.joinToString(" / "),
                confidence = Confidence.VERIFIED
            ),
            DriveObservation(
                id = "RECENT_AUTO_HOLD",
                title = "RECENT AUTO HOLD",
                status = when {
                    !sawAutoHoldSignal -> DriveObservationStatus.NO_SIGNAL
                    autoHoldTransitions > 0 || autoHoldHoldingCount > 0 -> DriveObservationStatus.OBSERVED
                    else -> DriveObservationStatus.NOT_USED
                },
                detail = when {
                    !sawAutoHoldSignal -> "AVH raw not seen"
                    autoHoldTransitions > 0 || autoHoldHoldingCount > 0 ->
                        "transitions=" + autoHoldTransitions + " · holding=" + autoHoldHoldingCount
                    else -> "signal alive · no hold transition observed"
                },
                confidence = Confidence.BETA
            ),
            DriveObservation(
                id = "RECENT_REGEN",
                title = "RECENT REGEN",
                status = if (sawRegenSignal) DriveObservationStatus.OBSERVED else DriveObservationStatus.NO_SIGNAL,
                detail = if (regenModes.isEmpty()) "NO SIGNAL" else regenModes.joinToString(" / "),
                confidence = Confidence.BETA
            ),
            DriveObservation(
                id = "RECENT_DRIVE_MODE",
                title = "RECENT DRIVE MODE",
                status = if (sawDriveSignal) DriveObservationStatus.OBSERVED else DriveObservationStatus.NO_SIGNAL,
                detail = if (driveModes.isEmpty()) "NO SIGNAL" else driveModes.joinToString(" / "),
                confidence = if (driveModes.contains("NORMAL")) Confidence.BETA else Confidence.VERIFIED
            ),
            DriveObservation(
                id = "RECENT_BSD",
                title = "RECENT BSD",
                status = when {
                    !sawBsdSignal -> DriveObservationStatus.NO_SIGNAL
                    bsdLeftCount + bsdRightCount > 0 -> DriveObservationStatus.OBSERVED
                    else -> DriveObservationStatus.NOT_USED
                },
                detail = when {
                    !sawBsdSignal -> "BSD raw not seen"
                    else -> "LEFT=" + bsdLeftCount + " · RIGHT=" + bsdRightCount +
                        " · raw transitions=" + bsdTransitions
                },
                confidence = Confidence.BETA
            ),
            DriveObservation(
                id = "RECENT_AUDIO",
                title = "RECENT AUDIO",
                status = when {
                    slowAudioStarts > 0 || audioErrors > 0 || liveAudioStarts < liveAudioRequests ->
                        DriveObservationStatus.ISSUE
                    liveAudioRequests > 0 -> DriveObservationStatus.OBSERVED
                    else -> DriveObservationStatus.NOT_USED
                },
                detail = when {
                    slowAudioStarts > 0 || audioErrors > 0 || liveAudioStarts < liveAudioRequests ->
                        "requests=" + liveAudioRequests +
                            " · starts=" + liveAudioStarts +
                            " · missing=" + (liveAudioRequests - liveAudioStarts).coerceAtLeast(0) +
                            " · slow>2s=" + slowAudioStarts +
                            " · errors=" + audioErrors
                    liveAudioRequests > 0 ->
                        "requests=" + liveAudioRequests + " · starts=" + liveAudioStarts
                    else -> "no live alert playback requested"
                },
                confidence = Confidence.VERIFIED
            )
        )

        RecentDriveSnapshot(
            startedAtMs = startedAtMs,
            capturedAtMs = captured,
            observations = observations,
            events = events.toList(),
            maxSpeedKph = maxSpeedKph,
            liveAudioRequests = liveAudioRequests,
            liveAudioStarts = liveAudioStarts,
            slowAudioStarts = slowAudioStarts,
            audioErrors = audioErrors
        )
    }

    fun reset() = synchronized(lock) {
        startedAtMs = System.currentTimeMillis()
        previous = null
        events.clear()
        gears.clear()
        driveModes.clear()
        regenModes.clear()
        sawGearSignal = false
        sawDriveSignal = false
        sawRegenSignal = false
        sawAutoHoldSignal = false
        sawBsdSignal = false
        autoHoldTransitions = 0
        autoHoldHoldingCount = 0
        bsdLeftCount = 0
        bsdRightCount = 0
        bsdTransitions = 0
        maxSpeedKph = null
        pendingAudio.clear()
        liveAudioRequests = 0
        liveAudioStarts = 0
        slowAudioStarts = 0
        audioErrors = 0
        NextLogger.i("DRIVE_HISTORY", "recent drive recorder reset")
    }

    private fun observeVehicle(current: VehicleState) {
        val now = System.currentTimeMillis()
        trimOldEvents(now)

        current.gear.value?.let {
            sawGearSignal = true
            gears += it
        }
        current.driveMode.value?.let {
            sawDriveSignal = true
            driveModes += it
        }
        current.regenMode.value?.let {
            sawRegenSignal = true
            regenModes += it
        }
        if (current.autoHoldRaw.value != null) sawAutoHoldSignal = true
        if (current.bsdRaw.value != null) sawBsdSignal = true
        current.speedKph.value?.let { speed ->
            maxSpeedKph = max(maxSpeedKph ?: speed, speed)
        }

        val before = previous
        if (before != null) {
            if (before.gear.value != current.gear.value && current.gear.value != null) {
                addEvent(
                    now,
                    "GEAR",
                    current.gear.value!!,
                    current.gear.raw?.toString(),
                    current.gear.confidence
                )
            }

            if (before.driveMode.value != current.driveMode.value && current.driveMode.value != null) {
                addEvent(
                    now,
                    "DRIVE_MODE",
                    current.driveMode.value!!,
                    current.driveMode.raw?.toString(),
                    current.driveMode.confidence
                )
            }

            if (before.regenMode.value != current.regenMode.value && current.regenMode.value != null) {
                addEvent(
                    now,
                    "REGEN",
                    current.regenMode.value!!,
                    current.regenMode.raw?.toString(),
                    current.regenMode.confidence
                )
            }

            val beforeAvh = before.autoHoldRaw.value
            val nowAvh = current.autoHoldRaw.value
            if (beforeAvh != null && nowAvh != null && beforeAvh != nowAvh) {
                autoHoldTransitions++
                if (nowAvh == 2) autoHoldHoldingCount++
                addEvent(
                    now,
                    "AUTO_HOLD",
                    "raw " + beforeAvh + " -> " + nowAvh,
                    nowAvh.toString(),
                    Confidence.BETA
                )
            }

            val beforeBsd = before.bsdRaw.value
            val nowBsd = current.bsdRaw.value
            if (beforeBsd != null && nowBsd != null && beforeBsd != nowBsd) {
                bsdTransitions++
                val direction = current.turn.value
                if (direction == "LEFT") bsdLeftCount++
                if (direction == "RIGHT") bsdRightCount++
                addEvent(
                    now,
                    "BSD",
                    (direction ?: "NO TURN") + " · raw " + beforeBsd + " -> " + nowBsd,
                    nowBsd.toString(),
                    Confidence.BETA
                )
            }
        }

        previous = current
    }

    private fun observeAudio(event: AudioProbeEvent) {
        val now = event.timestampMs
        trimOldEvents(now)

        when (event.phase) {
            AudioProbePhase.REQUEST -> {
                liveAudioRequests++
                pendingAudio.getOrPut(event.probeKey) { ArrayDeque() }.addLast(now)
                addEvent(now, "AUDIO_REQUEST", event.probeKey, event.detail, Confidence.VERIFIED)
            }
            AudioProbePhase.START -> {
                liveAudioStarts++
                val queue = pendingAudio[event.probeKey]
                val requestAt = if (queue != null && queue.isNotEmpty()) queue.removeFirst() else null
                val latency = if (requestAt != null) now - requestAt else null
                if (latency != null && latency > 2_000L) slowAudioStarts++
                addEvent(
                    now,
                    "AUDIO_START",
                    event.probeKey + (latency?.let { " · " + it + "ms" } ?: ""),
                    event.detail,
                    Confidence.VERIFIED
                )
            }
            AudioProbePhase.ERROR -> {
                audioErrors++
                addEvent(now, "AUDIO_ERROR", event.probeKey, event.detail, Confidence.VERIFIED)
            }
        }
    }

    private fun addEvent(
        timestampMs: Long,
        type: String,
        value: String,
        raw: String?,
        confidence: Confidence
    ) {
        events.addLast(
            DriveHistoryEvent(
                timestampMs = timestampMs,
                type = type,
                value = value,
                raw = raw,
                confidence = confidence
            )
        )
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    private fun trimOldEvents(now: Long) {
        val cutoff = now - MAX_WINDOW_MS
        while (events.isNotEmpty() && events.first().timestampMs < cutoff) {
            events.removeFirst()
        }
        if (startedAtMs < cutoff) startedAtMs = cutoff
    }

    companion object {
        private const val MAX_EVENTS = 1200
        private const val MAX_WINDOW_MS = 90L * 60L * 1000L
    }
}
