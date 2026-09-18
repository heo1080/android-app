package com.byd.dolphin.autoassistant.next.diagnostics

import android.content.Context
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.next.audio.AudioProbeEvent
import com.byd.dolphin.autoassistant.next.audio.AudioProbePhase
import com.byd.dolphin.autoassistant.next.audio.NextAudioEngine
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.vehicle.Confidence
import com.byd.dolphin.autoassistant.next.vehicle.SignalValue
import com.byd.dolphin.autoassistant.next.vehicle.VehicleRepository
import com.byd.dolphin.autoassistant.next.vehicle.VehicleState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class DiagnosticStatus { PASS, FAIL, NO_SIGNAL, RUNNING }

data class DiagnosticResult(
    val id: String,
    val title: String,
    val status: DiagnosticStatus,
    val detail: String,
    val latencyMs: Long? = null,
    val confidence: Confidence = Confidence.UNKNOWN,
    val timestampMs: Long = System.currentTimeMillis()
)

data class DiagnosticsUiState(
    val running: Boolean = false,
    val sessionId: String? = null,
    val results: List<DiagnosticResult> = emptyList(),
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
    val lastExportName: String? = null
) {
    val passed: Int get() = results.count { it.status == DiagnosticStatus.PASS }
    val total: Int get() = results.size
    val failures: Int get() = results.count {
        it.status == DiagnosticStatus.FAIL || it.status == DiagnosticStatus.NO_SIGNAL
    }
}

class DolphinDiagnostics(
    context: Context,
    private val repository: VehicleRepository,
    private val audio: NextAudioEngine
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state

    private val probeLock = Any()
    private val probes = ArrayDeque<AudioProbeEvent>()
    private var sessionJob: Job? = null
    @Volatile private var lastVehicleState = VehicleState()

    init {
        scope.launch {
            audio.probeEvents.collect { event ->
                synchronized(probeLock) {
                    probes.addLast(event)
                    while (probes.size > 200) probes.removeFirst()
                }
            }
        }
        scope.launch {
            repository.state.collect { lastVehicleState = it }
        }
    }

    fun runFullDiagnostics() {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch {
            val started = System.currentTimeMillis()
            val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(started))
            _state.value = DiagnosticsUiState(
                running = true,
                sessionId = id,
                startedAtMs = started
            )
            NextLogger.i("DIAGNOSTICS", "session start id=" + id)

            val results = mutableListOf<DiagnosticResult>()
            fun publish(result: DiagnosticResult) {
                results.removeAll { it.id == result.id }
                results.add(result)
                _state.value = _state.value.copy(results = results.toList())
                NextLogger.i(
                    "DIAGNOSTICS",
                    result.id + " " + result.status.name + " " + result.detail
                )
            }

            runTtsAndBeep(publish = ::publish)

            val snapshot = repository.state.value
            publish(signalResult("GEAR", "GEAR", snapshot.gear) { it })
            publish(autoHoldResult(snapshot))
            publish(signalResult("REGEN", "REGEN", snapshot.regenMode) { it })
            publish(signalResult("DRIVE", "DRIVE MODE", snapshot.driveMode) { it })

            val route = latestRouteForSession(started)
            publish(
                if (route != null) {
                    DiagnosticResult(
                        id = "DRIVER_AUDIO",
                        title = "DRIVER AUDIO",
                        status = DiagnosticStatus.PASS,
                        detail = route,
                        confidence = Confidence.VERIFIED
                    )
                } else {
                    DiagnosticResult(
                        id = "DRIVER_AUDIO",
                        title = "DRIVER AUDIO",
                        status = DiagnosticStatus.NO_SIGNAL,
                        detail = "STREAM14 requested · routed device unavailable",
                        confidence = Confidence.BETA
                    )
                }
            )

            val finished = System.currentTimeMillis()
            _state.value = _state.value.copy(
                running = false,
                finishedAtMs = finished,
                results = results.toList()
            )
            NextLogger.i(
                "DIAGNOSTICS",
                "session finish id=" + id +
                    " health=" + results.count { it.status == DiagnosticStatus.PASS } +
                    "/" + results.size
            )
        }
    }

    fun cancel() {
        sessionJob?.cancel()
        sessionJob = null
        _state.value = _state.value.copy(running = false)
    }

    private suspend fun runTtsAndBeep(publish: (DiagnosticResult) -> Unit) {
        val ttsRequest = audio.testTts().first
        publish(
            DiagnosticResult(
                "TTS", "TTS",
                DiagnosticStatus.RUNNING,
                "waiting for AudioTrack start",
                confidence = Confidence.VERIFIED,
                timestampMs = ttsRequest
            )
        )

        val ttsStart = waitProbe("TEST_TTS", AudioProbePhase.START, ttsRequest, 2_000L)
        val ttsError = latestProbe("TEST_TTS", AudioProbePhase.ERROR, ttsRequest)
        if (ttsStart != null) {
            val latency = ttsStart.timestampMs - ttsRequest
            publish(
                DiagnosticResult(
                    "TTS", "TTS",
                    DiagnosticStatus.PASS,
                    latency.toString() + "ms · " + ttsStart.detail,
                    latency,
                    Confidence.VERIFIED,
                    ttsRequest
                )
            )
            delay(700L)
        } else {
            publish(
                DiagnosticResult(
                    "TTS", "TTS",
                    DiagnosticStatus.FAIL,
                    if (ttsError != null) ttsError.detail else "2.0s DELAY / NO START",
                    2_000L,
                    Confidence.VERIFIED,
                    ttsRequest
                )
            )
        }

        val beepRequest = audio.testBeep()
        publish(
            DiagnosticResult(
                "BEEP", "BEEP",
                DiagnosticStatus.RUNNING,
                "waiting for AudioTrack start",
                confidence = Confidence.VERIFIED,
                timestampMs = beepRequest
            )
        )
        val beepStart = waitProbe("TEST_BEEP", AudioProbePhase.START, beepRequest, 1_200L)
        if (beepStart != null) {
            val latency = beepStart.timestampMs - beepRequest
            publish(
                DiagnosticResult(
                    "BEEP", "BEEP",
                    DiagnosticStatus.PASS,
                    latency.toString() + "ms · " + beepStart.detail,
                    latency,
                    Confidence.VERIFIED,
                    beepRequest
                )
            )
        } else {
            publish(
                DiagnosticResult(
                    "BEEP", "BEEP",
                    DiagnosticStatus.FAIL,
                    "1.2s NO START",
                    1_200L,
                    Confidence.VERIFIED,
                    beepRequest
                )
            )
        }

        // Reproduce and detect the historical queue inversion:
        // TTS request -> no start -> beep request -> delayed TTS starts.
        if (ttsStart == null) {
            val delayed = waitProbe("TEST_TTS", AudioProbePhase.START, beepRequest, 1_500L)
            if (delayed != null) {
                publish(
                    DiagnosticResult(
                        "TTS", "TTS",
                        DiagnosticStatus.FAIL,
                        "DELAYED AFTER BEEP · " +
                            (delayed.timestampMs - ttsRequest) + "ms · " + delayed.detail,
                        delayed.timestampMs - ttsRequest,
                        Confidence.VERIFIED,
                        ttsRequest
                    )
                )
            }
        }
    }

    private fun autoHoldResult(state: VehicleState): DiagnosticResult {
        val signal = state.autoHoldRaw
        val raw = signal.value
        if (raw == null || signal.stale) {
            return DiagnosticResult(
                "AUTO_HOLD", "AUTO HOLD",
                DiagnosticStatus.NO_SIGNAL,
                "AVH raw unavailable",
                confidence = Confidence.BETA
            )
        }
        val label = when (raw) {
            0 -> "OFF"
            1 -> "ON / READY"
            2 -> "HOLDING"
            else -> "RAW " + raw
        }
        return DiagnosticResult(
            "AUTO_HOLD", "AUTO HOLD",
            DiagnosticStatus.PASS,
            label + " · raw=" + raw,
            confidence = Confidence.BETA
        )
    }

    private fun <T> signalResult(
        id: String,
        title: String,
        signal: SignalValue<T>,
        display: (T) -> String
    ): DiagnosticResult {
        val value = signal.value
        return if (value == null || signal.stale) {
            DiagnosticResult(
                id, title,
                DiagnosticStatus.NO_SIGNAL,
                "NO SIGNAL",
                confidence = signal.confidence
            )
        } else {
            DiagnosticResult(
                id, title,
                DiagnosticStatus.PASS,
                display(value) +
                    (signal.raw?.let { " · raw=" + it }.orEmpty()),
                confidence = signal.confidence
            )
        }
    }

    private suspend fun waitProbe(
        key: String,
        phase: AudioProbePhase,
        sinceMs: Long,
        timeoutMs: Long
    ): AudioProbeEvent? {
        val until = System.currentTimeMillis() + timeoutMs
        while (scope.isActive && System.currentTimeMillis() < until) {
            latestProbe(key, phase, sinceMs)?.let { return it }
            delay(25L)
        }
        return latestProbe(key, phase, sinceMs)
    }

    private fun latestProbe(
        key: String,
        phase: AudioProbePhase,
        sinceMs: Long
    ): AudioProbeEvent? = synchronized(probeLock) {
        probes.lastOrNull {
            it.probeKey == key &&
                it.phase == phase &&
                it.timestampMs >= sinceMs
        }
    }

    private fun latestRouteForSession(sinceMs: Long): String? = synchronized(probeLock) {
        probes.lastOrNull {
            it.phase == AudioProbePhase.START &&
                it.timestampMs >= sinceMs &&
                it.detail.contains("STREAM14")
        }?.detail
    }

    suspend fun createFailuresZip(): File? = withContext(Dispatchers.IO) {
        val snapshot = _state.value
        val failures = snapshot.results.filter {
            it.status == DiagnosticStatus.FAIL || it.status == DiagnosticStatus.NO_SIGNAL
        }
        if (snapshot.running || snapshot.sessionId == null || failures.isEmpty()) return@withContext null

        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "diagnostics").apply { mkdirs() }
        dir.listFiles()
            ?.filter { it.name.startsWith("FAIL_ONLY_") && it.name.endsWith(".zip") }
            ?.forEach { it.delete() }
        val file = File(
            dir,
            "FAIL_ONLY_" + snapshot.sessionId + ".zip"
        )
        if (file.exists()) file.delete()

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            putText(zip, "summary.json", summaryJson(snapshot).toString(2))
            putText(zip, "vehicle_state.json", vehicleStateJson(lastVehicleState).toString(2))
            putText(zip, "audio_timeline.txt", audioTimeline(snapshot.startedAtMs, snapshot.finishedAtMs))
            putText(zip, "failure_windows.txt", failureWindows(failures))
        }

        _state.value = snapshot.copy(lastExportName = file.name)
        NextLogger.i("DIAGNOSTICS", "failure export=" + file.absolutePath)
        file
    }

    private fun summaryJson(state: DiagnosticsUiState): JSONObject =
        JSONObject().apply {
            put("app_version", BuildConfig.VERSION_NAME)
            put("session_id", state.sessionId)
            put("started_at_ms", state.startedAtMs)
            put("finished_at_ms", state.finishedAtMs)
            put("health_pass", state.passed)
            put("health_total", state.total)
            put("results", JSONArray().apply {
                state.results.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("title", r.title)
                        put("status", r.status.name)
                        put("detail", r.detail)
                        put("latency_ms", r.latencyMs ?: JSONObject.NULL)
                        put("confidence", r.confidence.name)
                        put("timestamp_ms", r.timestampMs)
                    })
                }
            })
        }

    private fun vehicleStateJson(state: VehicleState): JSONObject =
        JSONObject().apply {
            put("gear", signalJson(state.gear))
            put("drive_mode", signalJson(state.driveMode))
            put("regen_mode", signalJson(state.regenMode))
            put("snow_mode", signalJson(state.snowMode))
            put("auto_hold_raw", signalJson(state.autoHoldRaw))
            put("icc", signalJson(state.iccActive))
            put("bsd_raw", signalJson(state.bsdRaw))
            put("turn", signalJson(state.turn))
            put("speed_kph", signalJson(state.speedKph))
            put("brake_depth", signalJson(state.brakeDepth))
            put("accelerator_depth", signalJson(state.acceleratorDepth))
            put("panorama_work", signalJson(state.panoramaWork))
            put("panorama_output", signalJson(state.panoramaOutput))
            put("parking_sensors", JSONArray().apply {
                state.parkingSensors.forEach { s ->
                    put(JSONObject().apply {
                        put("area", s.area)
                        put("label", s.label)
                        put("probe_raw", s.probeStateRaw ?: JSONObject.NULL)
                        put("distance_cm", s.distanceCm ?: JSONObject.NULL)
                        put("confidence", s.confidence.name)
                        put("stale", s.stale)
                    })
                }
            })
        }

    private fun <T> signalJson(signal: SignalValue<T>): JSONObject =
        JSONObject().apply {
            put("value", signal.value?.toString() ?: JSONObject.NULL)
            put("raw", signal.raw ?: JSONObject.NULL)
            put("timestamp_ms", signal.timestampMs)
            put("confidence", signal.confidence.name)
            put("stale", signal.stale)
        }

    private fun audioTimeline(from: Long, to: Long): String {
        val list = synchronized(probeLock) {
            probes.filter { it.timestampMs in from..(to + 2_000L) }
        }
        return buildString {
            list.forEach {
                append(it.timestampMs)
                append(" ")
                append(it.probeKey)
                append(" ")
                append(it.phase.name)
                append(" ")
                append(it.detail)
                append("\n")
            }
        }
    }

    private fun failureWindows(failures: List<DiagnosticResult>): String =
        buildString {
            failures.forEach { failure ->
                append("=== ")
                append(failure.title)
                append(" / ")
                append(failure.status.name)
                append(" / ")
                append(failure.detail)
                append(" ===\n")
                val logs = NextLogger.snapshot(
                    failure.timestampMs - 2_500L,
                    failure.timestampMs + 2_500L
                )
                logs.forEach { e ->
                    append(e.timestampMs)
                    append(" ")
                    append(e.level)
                    append(" ")
                    append(e.tag)
                    append(" ")
                    append(e.message)
                    append("\n")
                }
                append("\n")
            }
        }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
