package com.byd.dolphin.autoassistant.next.diagnostics

import android.content.Context
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.next.audio.AudioProbeEvent
import com.byd.dolphin.autoassistant.next.audio.AudioProbePhase
import com.byd.dolphin.autoassistant.next.audio.NextAudioEngine
import com.byd.dolphin.autoassistant.next.capability.CapabilityRouter
import com.byd.dolphin.autoassistant.next.cluster.TbtCorrelationProbe
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
    val lastExportName: String? = null,
    val autoUploadMessage: String? = null,
    val autoUploadSuccess: Boolean? = null,
    val recentDrive: RecentDriveSnapshot? = null,
    val fullUploadMessage: String? = null,
    val fullUploadSuccess: Boolean? = null
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
    private val audio: NextAudioEngine,
    private val recentDriveRecorder: RecentDriveRecorder
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
                    while (probes.size > 2000) probes.removeFirst()
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
            val driveSnapshot = recentDriveRecorder.snapshot()
            val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(started))
            _state.value = DiagnosticsUiState(
                running = true,
                sessionId = id,
                startedAtMs = started,
                recentDrive = driveSnapshot
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
            publish(signalResult("EPB", "SIDE BRAKE", snapshot.epbApplied) {
                if (it) "ON" else "OFF"
            })
            publish(signalResult("SNOW", "SNOW MODE", snapshot.snowMode) {
                if (it) "ON" else "OFF"
            })
            publish(signalResult("ICC", "ICC", snapshot.iccActive) {
                if (it) "ON" else "OFF"
            })

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
                results = results.toList(),
                recentDrive = driveSnapshot
            )
            NextLogger.i(
                "DIAGNOSTICS",
                "session finish id=" + id +
                    " health=" + results.count { it.status == DiagnosticStatus.PASS } +
                    "/" + results.size
            )

            val hasFailures = results.any {
                it.status == DiagnosticStatus.FAIL ||
                    it.status == DiagnosticStatus.NO_SIGNAL
            }
            val hasRecentDriveIssue = driveSnapshot.hasIssue
            if (hasFailures || hasRecentDriveIssue) {
                val config = DiagnosticUploadManager
                    .enableAutomaticallyWhenConfigured(app)
                val failureZip = createFailuresZip()
                if (failureZip != null && config.complete) {
                    val upload = withContext(Dispatchers.IO) {
                        DiagnosticUploadManager.uploadDiagnosticBundle(
                            app,
                            failureZip
                        )
                    }
                    _state.value = _state.value.copy(
                        autoUploadMessage = upload.message,
                        autoUploadSuccess = upload.success
                    )
                    NextLogger.i(
                        "DIAGNOSTICS",
                        "autoUpload success=" + upload.success +
                            " message=" + upload.message
                    )
                } else {
                    _state.value = _state.value.copy(
                        autoUploadMessage = if (!config.complete) {
                            "GITHUB AUTO UPLOAD NOT CONFIGURED"
                        } else {
                            "FAILURE ZIP NOT CREATED"
                        },
                        autoUploadSuccess = false
                    )
                }
            } else {
                _state.value = _state.value.copy(
                    autoUploadMessage = "NO FAILURE ZIP NEEDED",
                    autoUploadSuccess = true
                )
            }

            recentDriveRecorder.reset()
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

    suspend fun uploadFullTestSession(): DiagnosticUploadManager.UploadResult =
        withContext(Dispatchers.IO) {
            val config = DiagnosticUploadManager.enableAutomaticallyWhenConfigured(app)
            if (!config.complete) {
                val result = DiagnosticUploadManager.UploadResult(
                    false,
                    "GITHUB LOG UPLOAD NOT CONFIGURED"
                )
                _state.value = _state.value.copy(
                    fullUploadMessage = result.message,
                    fullUploadSuccess = false
                )
                return@withContext result
            }

            val file = createFullTestZip()
            val result = DiagnosticUploadManager.uploadDiagnosticBundle(app, file)
            _state.value = _state.value.copy(
                fullUploadMessage = result.message,
                fullUploadSuccess = result.success,
                lastExportName = file.name
            )
            NextLogger.i(
                "DIAGNOSTICS",
                "fullTestUpload success=" + result.success +
                    " message=" + result.message +
                    " file=" + file.name
            )
            result
        }

    private fun createFullTestZip(): File {
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "diagnostics").apply { mkdirs() }
        dir.listFiles()
            ?.filter { it.name.startsWith("FULL_TEST_") && it.name.endsWith(".zip") }
            ?.forEach { it.delete() }

        val file = File(dir, "FULL_TEST_" + id + ".zip")
        val currentState = repository.state.value
        val recent = recentDriveRecorder.snapshot()
        val ui = _state.value

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            putText(
                zip,
                "integrated_summary.json",
                JSONObject().apply {
                    put("app_version", BuildConfig.VERSION_NAME)
                    put("app_version_code", BuildConfig.VERSION_CODE)
                    put("created_at_ms", now)
                    put("diagnostics_mode", "FULL_TEST_SESSION")
                    put("health_pass", ui.passed)
                    put("health_total", ui.total)
                    put("health_failures", ui.failures)
                    put("recent_drive_duration_ms", recent.durationMs)
                    put("recent_drive_issue", recent.hasIssue)
                }.toString(2)
            )
            putText(zip, "vehicle_state.json", vehicleStateJson(currentState).toString(2))
            putText(zip, "recent_drive.json", recentDriveJson(recent).toString(2))
            putText(zip, "recent_drive_events.txt", recentDriveEventsText(recent))
            putText(zip, "audio_timeline_full.txt", fullAudioTimeline())
            putText(zip, "capability_router.json", capabilityJson().toString(2))
            putText(zip, "tbt_correlation.json", tbtCorrelationJson().toString(2))
            putText(zip, "full_integrated_log.txt", fullLogText())
            if (ui.results.isNotEmpty()) {
                putText(zip, "last_health_summary.json", summaryJson(ui).toString(2))
            }
        }
        NextLogger.i("DIAGNOSTICS", "full test ZIP created=" + file.absolutePath)
        return file
    }

    suspend fun createFailuresZip(): File? = withContext(Dispatchers.IO) {
        val snapshot = _state.value
        val failures = snapshot.results.filter {
            it.status == DiagnosticStatus.FAIL || it.status == DiagnosticStatus.NO_SIGNAL
        }
        val driveIssue = snapshot.recentDrive?.hasIssue == true
        if (
            snapshot.running ||
            snapshot.sessionId == null ||
            (failures.isEmpty() && !driveIssue)
        ) return@withContext null

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
            snapshot.recentDrive?.let { recent ->
                putText(zip, "recent_drive.json", recentDriveJson(recent).toString(2))
                putText(zip, "recent_drive_events.txt", recentDriveEventsText(recent))
            }
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
            state.recentDrive?.let { recent ->
                put("recent_drive_duration_ms", recent.durationMs)
                put("recent_drive_issue", recent.hasIssue)
            }
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

    private fun capabilityJson(): JSONObject =
        JSONObject().apply {
            val snapshot = CapabilityRouter.snapshot.value
            put("completed_at_ms", snapshot.completedAtMs)
            put("entries", JSONArray().apply {
                snapshot.entries.values.sortedBy { it.key }.forEach { entry ->
                    put(JSONObject().apply {
                        put("key", entry.key)
                        put("class_name", entry.className)
                        put("access", entry.access.name)
                        put("read_probe", entry.readProbe)
                        put("setter_count", entry.setterCount)
                        put("detail", entry.detail)
                        put("timestamp_ms", entry.timestampMs)
                    })
                }
            })
        }

    private fun tbtCorrelationJson(): JSONObject =
        JSONObject().apply {
            val state = TbtCorrelationProbe.state.value
            put("registered", state.registered)
            put("active", state.active)
            put("started_at_ms", state.startedAtMs)
            put("ends_at_ms", state.endsAtMs)
            put("event_count", state.eventCount)
            put("last_event_at_ms", state.lastEventAtMs)
            put("last_event_summary", state.lastEventSummary)
            put("result", state.result)
            put("receiver_candidates", JSONArray(state.receiverCandidates))
            put("package_candidates", JSONArray(state.packageCandidates))
            put("service_evidence", state.serviceEvidence)
            put("log_evidence", state.logEvidence)
        }

    private fun recentDriveJson(snapshot: RecentDriveSnapshot): JSONObject =
        JSONObject().apply {
            put("started_at_ms", snapshot.startedAtMs)
            put("captured_at_ms", snapshot.capturedAtMs)
            put("duration_ms", snapshot.durationMs)
            put("max_speed_kph", snapshot.maxSpeedKph ?: JSONObject.NULL)
            put("live_audio_requests", snapshot.liveAudioRequests)
            put("live_audio_starts", snapshot.liveAudioStarts)
            put("slow_audio_starts_over_2s", snapshot.slowAudioStarts)
            put("audio_errors", snapshot.audioErrors)
            put("has_issue", snapshot.hasIssue)
            put("observations", JSONArray().apply {
                snapshot.observations.forEach { observation ->
                    put(JSONObject().apply {
                        put("id", observation.id)
                        put("title", observation.title)
                        put("status", observation.status.name)
                        put("detail", observation.detail)
                        put("confidence", observation.confidence.name)
                    })
                }
            })
        }

    private fun recentDriveEventsText(snapshot: RecentDriveSnapshot): String =
        buildString {
            append("session ")
            append(snapshot.startedAtMs)
            append(" -> ")
            append(snapshot.capturedAtMs)
            append("\n")
            snapshot.events.forEach { event ->
                append(event.timestampMs)
                append(" ")
                append(event.type)
                append(" ")
                append(event.value)
                event.raw?.let {
                    append(" raw=")
                    append(it)
                }
                append(" confidence=")
                append(event.confidence.name)
                append("\n")
            }
        }

    private fun vehicleStateJson(state: VehicleState): JSONObject =
        JSONObject().apply {
            put("gear", signalJson(state.gear))
            put("drive_mode", signalJson(state.driveMode))
            put("regen_mode", signalJson(state.regenMode))
            put("snow_mode", signalJson(state.snowMode))
            put("auto_hold_raw", signalJson(state.autoHoldRaw))
            put("epb_applied", signalJson(state.epbApplied))
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

    private fun fullAudioTimeline(): String {
        val list = synchronized(probeLock) { probes.toList() }
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

    private fun fullLogText(): String =
        buildString {
            NextLogger.snapshotAll().forEach { e ->
                append(e.timestampMs)
                append(" ")
                append(e.level)
                append(" ")
                append(e.tag)
                append(" ")
                append(e.message)
                append("\n")
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
