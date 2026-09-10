package com.byd.dolphin.autoassistant.manager

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.hud.TmapPlusHudBluetoothManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 사용자가 앱의 진단 화면에서 명시적으로 시작하는 제한 시간 진단 세션입니다.
 *
 * - 앱 내부 로그를 JSONL로 함께 보존합니다.
 * - 공개 BYD getter만 호출하고 setter는 절대 호출하지 않습니다.
 * - HUD에는 어떤 데이터 패킷도 보내지 않으며 페어링/SDP 정보만 조회합니다.
 * - 전체 Bluetooth 주소, Android ID, 일련번호는 수집하지 않습니다.
 */
object DiagnosticCaptureManager {
    private const val TAG = "DIAGNOSTIC_CAPTURE"
    private const val SESSION_DURATION_MS = 15 * 60 * 1_000L
    private const val SAMPLE_INTERVAL_MS = 2_000L
    private const val MAX_EVENTS_BYTES = 8L * 1024L * 1024L
    private const val MAX_EVENT_MESSAGE_CHARS = 12_000

    private val stateLock = Any()

    @Volatile
    private var currentSession: CaptureSession? = null

    private class CaptureSession(
        val id: String,
        val directory: File,
        val eventsFile: File,
        val startedAtWall: Long,
        val startedAtElapsed: Long,
        val includeRawNavText: Boolean,
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ) {
        val eventLock = Any()
        val previousVehicleState = linkedMapOf<String, String>()

        @Volatile var active = true
        @Volatile var eventLimitReported = false
        @Volatile var stoppedAtWall: Long? = null
        @Volatile var stopReason: String? = null
        @Volatile var bluetoothReceiver: BroadcastReceiver? = null
        @Volatile var initializationJob: Job? = null
        @Volatile var samplingJob: Job? = null
        @Volatile var timeoutJob: Job? = null
    }

    data class CaptureStatus(
        val exists: Boolean,
        val active: Boolean,
        val readyToExport: Boolean,
        val sessionId: String?,
        val elapsedSeconds: Long,
        val remainingSeconds: Long,
        val includeRawNavText: Boolean
    )

    fun getStatus(): CaptureStatus {
        val session = currentSession ?: return CaptureStatus(
            exists = false,
            active = false,
            readyToExport = false,
            sessionId = null,
            elapsedSeconds = 0,
            remainingSeconds = SESSION_DURATION_MS / 1_000L,
            includeRawNavText = false
        )
        val elapsed = (SystemClock.elapsedRealtime() - session.startedAtElapsed).coerceAtLeast(0L)
        return CaptureStatus(
            exists = true,
            active = session.active,
            readyToExport = !session.active,
            sessionId = session.id,
            elapsedSeconds = elapsed / 1_000L,
            remainingSeconds = ((SESSION_DURATION_MS - elapsed).coerceAtLeast(0L)) / 1_000L,
            includeRawNavText = session.includeRawNavText
        )
    }

    /** Returns a user-facing status. Starting a second session never discards an unexported one. */
    fun start(context: Context): String {
        val appContext = context.applicationContext
        synchronized(stateLock) {
            currentSession?.let { session ->
                return if (session.active) {
                    "이미 진단 수집 중입니다. 문제 상황을 재현한 뒤 종료·ZIP 내보내기를 누르세요."
                } else {
                    "이전 진단이 내보내기 대기 중입니다. 먼저 종료·ZIP 내보내기를 눌러주세요."
                }
            }
        }

        val root = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "diagnostics")
        if (!root.exists() && !root.mkdirs()) return "진단 저장 폴더를 만들 수 없습니다."

        val id = fileTimestamp()
        val directory = File(root, "session_$id")
        if (!directory.mkdirs()) return "진단 세션 폴더를 만들 수 없습니다."
        val session = CaptureSession(
            id = id,
            directory = directory,
            eventsFile = File(directory, "events.jsonl"),
            startedAtWall = System.currentTimeMillis(),
            startedAtElapsed = SystemClock.elapsedRealtime(),
            includeRawNavText = SettingsManager.isDiagnosticNavTextEnabled(appContext)
        )

        synchronized(stateLock) {
            if (currentSession != null) return "다른 진단 세션이 이미 준비되었습니다."
            currentSession = session
        }

        runCatching {
            File(directory, "capture_state.json").writeText(
                JSONObject().apply {
                    put("sessionId", id)
                    put("startedAtWall", session.startedAtWall)
                    put("startedAt", isoTimestamp(session.startedAtWall))
                    put("rawNavigationText", session.includeRawNavText)
                    put("plannedDurationSeconds", SESSION_DURATION_MS / 1_000L)
                }.toString(2)
            )
        }

        DolphinLogger.setDiagnosticEventSink { level, source, message ->
            recordAppLog(session, level, source, message)
        }
        DolphinLogger.setRawNavigationLoggingAllowed(session.includeRawNavText)
        appendEvent(
            session,
            category = "capture",
            event = "started",
            data = mapOf(
                "durationSeconds" to SESSION_DURATION_MS / 1_000L,
                "vehicleSampleIntervalMs" to SAMPLE_INTERVAL_MS,
                "includeRawNavText" to session.includeRawNavText,
                "hudModel" to "TMAP Plus HUD / T900",
                "hudPayloadTransmission" to "BLOCKED_PROTOCOL_UNCONFIRMED"
            ),
            force = true
        )
        if (!session.eventsFile.exists()) {
            DolphinLogger.setDiagnosticEventSink(null)
            DolphinLogger.setRawNavigationLoggingAllowed(false)
            synchronized(stateLock) {
                if (currentSession === session) currentSession = null
            }
            return "진단 이벤트 파일을 만들 수 없습니다. 저장 공간과 앱 권한을 확인하세요."
        }

        registerBluetoothReceiver(appContext, session)

        session.initializationJob = session.scope.launch {
            runCatching { writeDeviceSnapshot(appContext, session, requestHudSdp = true) }
                .onFailure { appendFailure(session, "snapshot", "device_snapshot", it) }
            runCatching { writeFrameworkInventory(session) }
                .onFailure { appendFailure(session, "framework", "inventory", it) }
            runCatching { writeInitialVehicleSnapshot(appContext, session) }
                .onFailure { appendFailure(session, "vehicle", "initial_snapshot", it) }
        }

        session.samplingJob = session.scope.launch {
            while (isActive && session.active) {
                sampleVehicleChanges(appContext, session)
                delay(SAMPLE_INTERVAL_MS)
            }
        }

        session.timeoutJob = session.scope.launch {
            delay(SESSION_DURATION_MS)
            finishCapture(appContext, session, "15_minute_limit")
        }

        DolphinLogger.i(TAG, "원터치 진단 세션 시작 id=$id rawNavText=${session.includeRawNavText}")
        return "15분 진단 수집을 시작했습니다. 문제를 재현한 뒤 ZIP 내보내기를 누르세요."
    }

    /** 다음 앱 실행 때 중단된 세션을 내보내기 대기 상태로 복구합니다. */
    fun recoverInterruptedSession(context: Context): Boolean {
        synchronized(stateLock) { if (currentSession != null) return false }
        val appContext = context.applicationContext
        val root = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "diagnostics")
        val directory = root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("session_") }
            .filter { directoryCandidate ->
                val candidateId = directoryCandidate.name.removePrefix("session_")
                val exportedZip = File(root, "DolphinAssistant_v30_DiagnosticSession_$candidateId.zip")
                File(directoryCandidate, "events.jsonl").exists() && !exportedZip.exists()
            }
            .maxByOrNull { it.lastModified() }
            ?: return false

        val state = runCatching { JSONObject(File(directory, "capture_state.json").readText()) }.getOrNull()
        val startedAtWall = state?.optLong("startedAtWall", directory.lastModified())
            ?: directory.lastModified()
        val session = CaptureSession(
            id = state?.optString("sessionId")?.takeIf { it.isNotBlank() }
                ?: directory.name.removePrefix("session_"),
            directory = directory,
            eventsFile = File(directory, "events.jsonl"),
            startedAtWall = startedAtWall,
            startedAtElapsed = SystemClock.elapsedRealtime(),
            includeRawNavText = state?.optBoolean("rawNavigationText", false) ?: false
        ).apply {
            active = false
            stoppedAtWall = System.currentTimeMillis()
            stopReason = "process_restart_recovered"
        }

        synchronized(stateLock) {
            if (currentSession != null) return false
            currentSession = session
        }
        appendEvent(
            session,
            category = "capture",
            event = "interrupted_session_recovered",
            data = mapOf("reason" to "app_process_restarted"),
            force = true
        )

        // 치명적 오류 직후 작성된 이전 프로세스 보고서를 다음 내보내기가 덮기 전에 보존합니다.
        val priorReport = File(
            appContext.getExternalFilesDir(null) ?: appContext.filesDir,
            "DolphinAssistant_v30_Diagnostic.txt"
        )
        if (priorReport.exists() && priorReport.length() > 0L) {
            runCatching {
                priorReport.copyTo(File(directory, "previous_process_diagnostic.txt"), overwrite = true)
            }
        }
        DolphinLogger.i(TAG, "중단된 진단 세션 복구 id=${session.id}; ZIP 내보내기 대기")
        return true
    }

    fun addProblemMarker(): Boolean {
        val session = currentSession ?: return false
        if (!session.active) return false
        appendEvent(
            session,
            category = "user",
            event = "problem_marker",
            data = mapOf("note" to "사용자가 문제 발생 순간 버튼을 누름"),
            force = true
        )
        DolphinLogger.i(TAG, "사용자 문제 발생 시점 표시")
        return true
    }

    suspend fun stopAndCreateBundle(context: Context): File? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val session = currentSession ?: return@withContext null
        try {
            finishCapture(appContext, session, "user_export")

            val pending = listOfNotNull(session.initializationJob)
            pending.joinAll()

            writeSessionSummary(session)
            writeKoreanReadme(session)

            val report = DolphinLogger.exportDiagnosticReport(appContext)
            if (report.exists()) {
                report.copyTo(File(session.directory, "diagnostic_report.txt"), overwrite = true)
            }

            val output = File(
                session.directory.parentFile,
                "DolphinAssistant_v30_DiagnosticSession_${session.id}.zip"
            )
            appendEvent(
                session,
                category = "capture",
                event = "bundle_export_started",
                data = mapOf("fileName" to output.name),
                force = true
            )
            createZip(session.directory, output)

            synchronized(stateLock) {
                if (currentSession === session) currentSession = null
            }
            session.scope.cancel()
            DolphinLogger.i(TAG, "진단 ZIP 생성 완료 file=${output.name} bytes=${output.length()}")
            output.takeIf { it.exists() && it.length() > 0L }
        } catch (error: Throwable) {
            appendFailure(session, "capture", "bundle_export", error)
            DolphinLogger.e(TAG, "진단 ZIP 생성 실패; 원본 세션은 보존됨", error)
            null
        }
    }

    private fun finishCapture(context: Context, session: CaptureSession, reason: String) {
        val shouldFinish = synchronized(stateLock) {
            if (currentSession !== session || !session.active) false
            else {
                session.active = false
                session.stoppedAtWall = System.currentTimeMillis()
                session.stopReason = reason
                true
            }
        }
        if (!shouldFinish) return

        appendEvent(
            session,
            category = "capture",
            event = "stopped",
            data = mapOf("reason" to reason),
            force = true
        )
        DolphinLogger.setDiagnosticEventSink(null)
        DolphinLogger.setRawNavigationLoggingAllowed(false)
        session.samplingJob?.cancel()
        session.timeoutJob?.cancel()
        unregisterBluetoothReceiver(context, session)
        DolphinLogger.i(TAG, "진단 수집 종료 reason=$reason; ZIP 내보내기 대기")
    }

    private fun recordAppLog(
        session: CaptureSession,
        level: String,
        source: String,
        message: String
    ) {
        if (!session.active) return
        appendEvent(
            session,
            category = "app_log",
            event = source,
            data = mapOf("level" to level, "message" to message.take(MAX_EVENT_MESSAGE_CHARS))
        )
    }

    private fun appendFailure(session: CaptureSession, category: String, event: String, error: Throwable) {
        appendEvent(
            session,
            category = category,
            event = "${event}_failed",
            data = mapOf(
                "error" to error.javaClass.simpleName,
                "message" to error.message.orEmpty().take(1_000)
            ),
            force = true
        )
    }

    private fun appendEvent(
        session: CaptureSession,
        category: String,
        event: String,
        data: Map<String, Any?> = emptyMap(),
        force: Boolean = false
    ) {
        if (!force && !session.active) return
        synchronized(session.eventLock) {
            if (session.eventsFile.length() >= MAX_EVENTS_BYTES) {
                if (!session.eventLimitReported) {
                    session.eventLimitReported = true
                    val limit = JSONObject().apply {
                        put("timestamp", isoTimestamp())
                        put("elapsedMs", SystemClock.elapsedRealtime() - session.startedAtElapsed)
                        put("category", "capture")
                        put("event", "event_size_limit_reached")
                        put("data", JSONObject().put("limitBytes", MAX_EVENTS_BYTES))
                    }
                    session.eventsFile.appendText(limit.toString() + "\n")
                }
                return
            }

            val dataObject = JSONObject()
            data.forEach { (key, value) -> dataObject.put(key, jsonCompatible(value)) }
            val line = JSONObject().apply {
                put("timestamp", isoTimestamp())
                put("elapsedMs", SystemClock.elapsedRealtime() - session.startedAtElapsed)
                put("category", category)
                put("event", event)
                put("data", dataObject)
            }
            runCatching { session.eventsFile.appendText(line.toString() + "\n") }
        }
    }

    private fun jsonCompatible(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is String, is Number, is Boolean, is JSONObject, is JSONArray -> value
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, nested) -> put(key.toString(), jsonCompatible(nested)) }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonCompatible(it)) } }
        else -> value.toString()
    }

    private fun registerBluetoothReceiver(context: Context, session: CaptureSession) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val device = bluetoothDevice(intent)
                val data = linkedMapOf<String, Any?>(
                    "action" to action,
                    "deviceName" to safeDeviceName(device),
                    "maskedAddress" to maskedAddress(safeDeviceAddress(device)),
                    "bondState" to safeDeviceBondState(device),
                    "type" to safeDeviceType(device),
                    "uuids" to safeDeviceUuids(device)
                )
                if (intent.hasExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE)) {
                    data["connectionState"] = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                }
                if (intent.hasExtra(BluetoothDevice.EXTRA_BOND_STATE)) {
                    data["broadcastBondState"] = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    data["previousBondState"] = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                }
                appendEvent(session, "bluetooth", action.substringAfterLast('.').lowercase(), data)
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            session.bluetoothReceiver = receiver
            appendEvent(session, "bluetooth", "receiver_registered", force = true)
        }.onFailure { appendFailure(session, "bluetooth", "receiver_registration", it) }
    }

    private fun unregisterBluetoothReceiver(context: Context, session: CaptureSession) {
        session.bluetoothReceiver?.let { receiver -> runCatching { context.unregisterReceiver(receiver) } }
        session.bluetoothReceiver = null
    }

    @Suppress("DEPRECATION")
    private fun bluetoothDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @SuppressLint("MissingPermission")
    private fun writeDeviceSnapshot(context: Context, session: CaptureSession, requestHudSdp: Boolean) {
        val snapshot = JSONObject().apply {
            put("generatedAt", isoTimestamp())
            put("privacy", JSONObject().apply {
                put("androidIdCollected", false)
                put("serialNumberCollected", false)
                put("fullBluetoothAddressCollected", false)
                put("rawNavigationText", session.includeRawNavText)
            })
            put("hudIdentity", JSONObject().apply {
                put("productModel", "TMAP Plus HUD / T900")
                put("manufacturer", "주식회사 인포라텍")
                put("kcRegistration", "R-R-9IT-JARVIS3000")
                put("label", "Connected to TMHP")
                put("protocol", "UNKNOWN_PAYLOAD_TRANSMISSION_BLOCKED")
            })
            put("app", JSONObject().apply {
                put("packageName", context.packageName)
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
            })
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("display", Build.DISPLAY)
                put("androidRelease", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
                put("securityPatch", Build.VERSION.SECURITY_PATCH)
            })
            put("systemProperties", selectedSystemProperties())
            put("permissions", permissionSnapshot(context))
            put("features", JSONObject().apply {
                put("hudSppConnected", TmapPlusHudBluetoothManager.isHudDataConnected)
                put("hudAudioConfirmed", TmapPlusHudBluetoothManager.isHudAudioConnected)
                put("hudProtocolConfirmed", SettingsManager.isHudProtocolConfirmed(context))
                put("clusterTbtEnabled", SettingsManager.isClusterTbtEnabled(context))
                put("experimentalParkingSensorLvda", SettingsManager.isExperimentalLvdaEnabled(context))
                put("localAdbPortOpen", NativeAdbClient.isPortOpen())
            })
            put("bluetooth", bluetoothSnapshot(context, requestHudSdp, session))
        }
        File(session.directory, "device_snapshot.json").writeText(snapshot.toString(2))
        appendEvent(session, "snapshot", "device_snapshot_written", force = true)
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothSnapshot(context: Context, requestHudSdp: Boolean, session: CaptureSession): JSONObject {
        val granted = hasBluetoothConnectPermission(context)
        val result = JSONObject().put("connectPermission", if (granted) "GRANTED" else "DENIED")
        if (!granted) return result.put("bondedDevices", JSONArray())

        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return result.put("adapter", "UNAVAILABLE").put("bondedDevices", JSONArray())
        result.put("adapterEnabled", runCatching { adapter.isEnabled }.getOrNull() ?: JSONObject.NULL)
        result.put("adapterState", runCatching { adapter.state }.getOrNull() ?: JSONObject.NULL)
        val scanGranted = hasBluetoothScanPermission(context)
        result.put("scanPermission", if (scanGranted) "GRANTED" else "DENIED")
        result.put(
            "discoveryActive",
            if (scanGranted) runCatching { adapter.isDiscovering }.getOrNull() ?: JSONObject.NULL
            else JSONObject.NULL
        )

        val devices = JSONArray()
        val bondedDevices = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        bondedDevices.sortedBy { safeDeviceName(it) }.forEach { device ->
            val hudCandidate = looksLikeHud(device)
            var sdpRequested = false
            if (requestHudSdp && hudCandidate) {
                sdpRequested = runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
            }
            devices.put(JSONObject().apply {
                put("name", safeDeviceName(device))
                put("maskedAddress", maskedAddress(safeDeviceAddress(device)))
                put("hudCandidate", hudCandidate)
                put("bondState", safeDeviceBondState(device) ?: JSONObject.NULL)
                put("type", safeDeviceType(device) ?: JSONObject.NULL)
                put("deviceClass", safeDeviceClass(device)?.deviceClass ?: JSONObject.NULL)
                put("majorDeviceClass", safeDeviceClass(device)?.majorDeviceClass ?: JSONObject.NULL)
                put("uuids", JSONArray(safeDeviceUuids(device)))
                put("sdpRefreshRequested", sdpRequested)
            })
            appendEvent(
                session,
                "bluetooth",
                "bonded_device_snapshot",
                mapOf(
                    "name" to safeDeviceName(device),
                    "maskedAddress" to maskedAddress(safeDeviceAddress(device)),
                    "hudCandidate" to hudCandidate,
                    "type" to safeDeviceType(device),
                    "uuids" to safeDeviceUuids(device),
                    "sdpRefreshRequested" to sdpRequested
                ),
                force = true
            )
        }
        return result.put("bondedDevices", devices)
    }

private fun permissionSnapshot(context: Context): JSONArray {
        val result = JSONArray()
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA)
        val names = packageInfo.requestedPermissions.orEmpty()
        val flags = packageInfo.requestedPermissionsFlags.orEmpty()
        names.forEachIndexed { index, permission ->
            val manifestGranted = ((flags.getOrNull(index) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            val runtimeGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            result.put(JSONObject().apply {
                put("name", permission)
                put("manifestGrantedFlag", manifestGranted)
                put("checkSelfPermission", if (runtimeGranted) "GRANTED" else "DENIED")
            })
        }
        return result
    }

    private fun selectedSystemProperties(): JSONObject = JSONObject().apply {
        SAFE_SYSTEM_PROPERTIES.forEach { key -> put(key, readSystemProperty(key)) }
    }

    private fun readSystemProperty(key: String): String = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching "TIMEOUT"
        }
        process.inputStream.bufferedReader().use { it.readText().trim().take(1_000) }
    }.getOrElse { "ERROR:${it.javaClass.simpleName}" }

    private fun writeFrameworkInventory(session: CaptureSession) {
        val target = File(session.directory, "framework_methods.txt")
        target.bufferedWriter().use { writer ->
            writer.appendLine("BYD Dolphin Auto Assistant v30 — runtime framework inventory")
            writer.appendLine("Generated: ${isoTimestamp()}")
            writer.appendLine("Metadata only: no method in this inventory is invoked by this scan.")
            writer.appendLine()
            FRAMEWORK_CLASSES.forEach { className ->
                writer.appendLine("=== $className ===")
                try {
                    val clazz = Class.forName(className)
                    clazz.declaredMethods
                        .filter { Modifier.isPublic(it.modifiers) }
                        .sortedWith(compareBy({ it.name }, { it.parameterTypes.joinToString { type -> type.name } }))
                        .forEach { method ->
                            val params = method.parameterTypes.joinToString(", ") { it.typeName }
                            val staticText = if (Modifier.isStatic(method.modifiers)) "static " else ""
                            writer.appendLine("${staticText}${method.returnType.typeName} ${method.name}($params)")
                        }
                } catch (error: Throwable) {
                    writer.appendLine("UNAVAILABLE: ${error.javaClass.simpleName}: ${error.message}")
                }
                writer.appendLine()
            }
        }
        appendEvent(session, "framework", "method_inventory_written", force = true)
    }

    private fun writeInitialVehicleSnapshot(context: Context, session: CaptureSession) {
        val states = readVehicleStates(context)
        synchronized(session.previousVehicleState) {
            session.previousVehicleState.clear()
            session.previousVehicleState.putAll(states)
        }
        val json = JSONObject()
        states.forEach { (key, value) -> json.put(key, value) }
        File(session.directory, "vehicle_initial_state.json").writeText(
            JSONObject().put("generatedAt", isoTimestamp()).put("values", json).toString(2)
        )
        appendEvent(session, "vehicle", "initial_state", states, force = true)
    }

    private fun sampleVehicleChanges(
        context: Context,
        session: CaptureSession,
        forceAll: Boolean = false,
        source: String = "poll"
    ) {
        if (!session.active && !forceAll) return
        val current = readVehicleStates(context)
        val changes = linkedMapOf<String, Any?>()
        synchronized(session.previousVehicleState) {
            current.forEach { (key, value) ->
                val previous = session.previousVehicleState[key]
                if (forceAll || previous != value) {
                    changes[key] = mapOf("previous" to previous, "current" to value)
                }
            }
            session.previousVehicleState.clear()
            session.previousVehicleState.putAll(current)
        }
        if (changes.isNotEmpty()) {
            appendEvent(session, "vehicle", "state_change_$source", changes, force = forceAll)
        }
    }

    private fun readVehicleStates(context: Context): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        VEHICLE_PROBES.forEach { probe -> result[probe.key] = readVehicleValue(context, probe) }
        return result
    }

    private fun readVehicleValue(context: Context, probe: VehicleProbe): String = try {
        val clazz = Class.forName(probe.className)
        val instance = clazz.getMethod("getInstance", Context::class.java).invoke(null, context)
        val types = Array(probe.arguments.size) { Int::class.javaPrimitiveType!! }
        val values = probe.arguments.map { it as Any }.toTypedArray()
        clazz.getMethod(probe.methodName, *types).invoke(instance, *values)?.toString() ?: "null"
    } catch (error: Throwable) {
        val cause = error.cause ?: error
        "ERROR:${cause.javaClass.simpleName}:${cause.message.orEmpty().take(200)}"
    }

    private fun writeSessionSummary(session: CaptureSession) {
        val stoppedAt = session.stoppedAtWall ?: System.currentTimeMillis()
        val summary = JSONObject().apply {
            put("sessionId", session.id)
            put("startedAt", isoTimestamp(session.startedAtWall))
            put("stoppedAt", isoTimestamp(stoppedAt))
            put("durationSeconds", ((stoppedAt - session.startedAtWall).coerceAtLeast(0L)) / 1_000L)
            put("stopReason", session.stopReason ?: "unknown")
            put("rawNavigationText", session.includeRawNavText)
            put("eventsBytes", session.eventsFile.length())
            put("eventSizeLimitReached", session.eventLimitReported)
            put("hudModel", "TMAP Plus HUD / T900")
        }
        File(session.directory, "session_summary.json").writeText(summary.toString(2))
    }

    private fun writeKoreanReadme(session: CaptureSession) {
        File(session.directory, "README_KO.txt").writeText(
            """
            BYD Dolphin Auto Assistant v30 원터치 진단 묶음

            세션: ${session.id}
            수집 시간: ${isoTimestamp(session.startedAtWall)} ~ ${isoTimestamp(session.stoppedAtWall ?: System.currentTimeMillis())}
            내비 알림 원문: ${if (session.includeRawNavText) "사용자 허용으로 포함" else "미포함(길이와 SHA-256 일부만 기록)"}

            파일 설명
            - events.jsonl: 시간순 구조화 앱/BYD/블루투스 이벤트
            - diagnostic_report.txt: 권한, 기능 상태, 최근 앱 로그, 접근 가능한 logcat
            - device_snapshot.json: 앱/차량 OS/권한/페어링 기기와 HUD UUID 상태
            - framework_methods.txt: 차량 런타임에 실제 존재하는 BYD 공개 메서드 목록
            - vehicle_initial_state.json: 수집 시작 시 읽기 전용 차량 원시값
            - session_summary.json: 수집 시간과 개인정보 옵션

            안전·개인정보
            - TMAP Plus HUD / T900에는 어떤 데이터 패킷도 보내지 않았습니다.
            - BYD setter를 호출하지 않고 getter만 조회했습니다.
            - 전체 Bluetooth MAC, Android ID, 기기 일련번호는 수집하지 않았습니다.

            앱만으로 접근할 수 없는 항목
            - Android Bluetooth HCI 원시 패킷/스누프 파일
            - 다른 앱의 비공개 로그와 데이터
            - root 또는 시스템 서명이 필요한 dumpsys/보호 파일
            - OEM 서명 권한의 강제 승인
            """.trimIndent() + "\n"
        )
    }

    private fun createZip(sourceDirectory: File, output: File) {
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            sourceDirectory.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(sourceDirectory).invariantSeparatorsPath }
                .forEach { file ->
                    val entryName = file.relativeTo(sourceDirectory).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun hasBluetoothConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothScanPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice?): String =
        if (device == null) "unknown" else runCatching { device.name.orEmpty() }.getOrDefault("permission_denied")

    @SuppressLint("MissingPermission")
    private fun safeDeviceUuids(device: BluetoothDevice?): List<String> =
        if (device == null) emptyList()
        else runCatching { device.uuids?.map { it.uuid.toString() }.orEmpty() }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    private fun safeDeviceAddress(device: BluetoothDevice?): String? =
        if (device == null) null else runCatching { device.address }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun safeDeviceBondState(device: BluetoothDevice?): Int? =
        if (device == null) null else runCatching { device.bondState }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun safeDeviceType(device: BluetoothDevice?): Int? =
        if (device == null) null else runCatching { device.type }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun safeDeviceClass(device: BluetoothDevice?) =
        if (device == null) null else runCatching { device.bluetoothClass }.getOrNull()

    private fun maskedAddress(address: String?): String {
        val parts = address.orEmpty().split(':')
        return if (parts.size == 6) "XX:XX:XX:${parts.takeLast(3).joinToString(":")}" else "masked"
    }

    @SuppressLint("MissingPermission")
    private fun looksLikeHud(device: BluetoothDevice): Boolean {
        val name = safeDeviceName(device)
        return name.contains("T900", true) || name.contains("T800", true) ||
            name.contains("HUD", true) || name.contains("TMAP", true) ||
            name.contains("TMHP", true) || name.contains("JARVIS", true) ||
            name.contains("INFORA", true)
    }

    private fun isoTimestamp(epochMillis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(epochMillis))

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private data class VehicleProbe(
        val key: String,
        val className: String,
        val methodName: String,
        val arguments: IntArray = intArrayOf()
    )

    private const val BODYWORK = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
    private const val SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    private const val AC = "android.hardware.bydauto.ac.BYDAutoAcDevice"
    private const val LIGHT = "android.hardware.bydauto.light.BYDAutoLightDevice"
    private const val RADAR = "android.hardware.bydauto.radar.BYDAutoRadarDevice"
    private const val SPEED = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    private const val GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
    private const val CHARGING = "android.hardware.bydauto.charging.BYDAutoChargingDevice"
    private const val ADAS = "android.hardware.bydauto.adas.BYDAutoADASDevice"
    private const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"

    private val FRAMEWORK_CLASSES = listOf(
        BODYWORK, SETTING, AC, LIGHT, RADAR, SPEED, GEARBOX, CHARGING, ADAS, INSTRUMENT
    )

    private val VEHICLE_PROBES = listOf(
        VehicleProbe("ignition.powerLevel", BODYWORK, "getPowerLevel"),
        VehicleProbe("speed.currentKmh", SPEED, "getCurrentSpeed"),
        VehicleProbe("gearbox.currentGear.raw", GEARBOX, "getCurrentGear"),
        VehicleProbe("gearbox.epb.raw", GEARBOX, "getEPBState"),
        VehicleProbe("charging.work.raw", CHARGING, "getChargerWorkState"),
        VehicleProbe("charging.state.raw", CHARGING, "getChargingState"),
        VehicleProbe("adas.avh.raw", ADAS, "getAVHState"),
        VehicleProbe("adas.bsd.raw", ADAS, "getBSDState"),
        VehicleProbe("adas.laneOffset.raw", ADAS, "getLaneOffsetState"),
        VehicleProbe("instrument.driveInterface.raw", INSTRUMENT, "getCurrentDriveInterFace"),
        VehicleProbe("instrument.energyFeedback.raw", INSTRUMENT, "getEnergyFeedback"),
        VehicleProbe("ac.start.raw", AC, "getAcStartState"),
        VehicleProbe("hazard.raw", LIGHT, "getDoubleFlashLightState"),
        VehicleProbe("radar.frontLeftMid.raw", RADAR, "getRadarObstacleDistance", intArrayOf(7)),
        VehicleProbe("radar.frontRightMid.raw", RADAR, "getRadarObstacleDistance", intArrayOf(8))
    )

    private val SAFE_SYSTEM_PROPERTIES = listOf(
        "ro.product.manufacturer",
        "ro.product.brand",
        "ro.product.model",
        "ro.product.device",
        "ro.build.display.id",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.build.version.security_patch"
    )
}
