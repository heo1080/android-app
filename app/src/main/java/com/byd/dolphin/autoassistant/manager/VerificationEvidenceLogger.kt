package com.byd.dolphin.autoassistant.manager

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.util.DolphinLogger
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Append-only verification evidence ledger.
 *
 * No vehicle setters are called here. Vehicle preconditions are read-only snapshots
 * and every operator result is bound to an APK/build/vehicle/session fingerprint.
 */
object VerificationEvidenceLogger {
    private const val TAG = "VERIFY_EVENT"
    private const val LEDGER_NAME = "verification_evidence.jsonl"
    private val lock = Any()

    @Volatile private var cachedApkSha256: String? = null

    fun recordObservation(
        context: Context,
        testId: String,
        featureId: String,
        featureState: String,
        requestedOutcome: String,
        note: String
    ): String {
        val app = context.applicationContext
        val correlationId = UUID.randomUUID().toString()
        val status = DiagnosticCaptureManager.getStatus()
        val effectiveOutcome = if (featureState == "BLOCKED" || featureState == "UNSUPPORTED") {
            "NEED_MORE_DATA"
        } else {
            requestedOutcome
        }
        val base = JSONObject().apply {
            put("schema_version", 1)
            put("correlation_id", correlationId)
            put("test_id", testId)
            put("feature_id", featureId)
            put("feature_state", featureState)
            put("wall_time_ms", System.currentTimeMillis())
            put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            put("wall_time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.KOREA).format(Date()))
            put("diagnostic_session_id", status.sessionId ?: JSONObject.NULL)
            put("diagnostic_session_active", status.active)
            put("build", buildFingerprint(app))
            put("preconditions", preconditions(app))
        }

        append(app, JSONObject(base.toString()).put("event", "TEST_START"))
        append(
            app,
            JSONObject(base.toString())
                .put("event", "OPERATOR_RESULT")
                .put("outcome", effectiveOutcome)
                .put("requested_outcome", requestedOutcome)
                .put("note", sanitizeNote(note))
        )
        append(
            app,
            JSONObject(base.toString())
                .put("event", "TEST_END")
                .put("outcome", effectiveOutcome)
        )
        return correlationId
    }

    fun markProblem(context: Context, note: String = "manual") {
        val app = context.applicationContext
        val status = DiagnosticCaptureManager.getStatus()
        val event = JSONObject().apply {
            put("schema_version", 1)
            put("event", "FIELD_PROBLEM_MARKER")
            put("correlation_id", UUID.randomUUID().toString())
            put("wall_time_ms", System.currentTimeMillis())
            put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            put("diagnostic_session_id", status.sessionId ?: JSONObject.NULL)
            put("diagnostic_session_active", status.active)
            put("note", sanitizeNote(note))
            put("build", buildFingerprint(app))
            put("preconditions", preconditions(app))
        }
        append(app, event)
        DiagnosticCaptureManager.addProblemMarker()
    }

    fun copyLedgerTo(context: Context, targetDirectory: File): File? {
        val source = ledgerFile(context.applicationContext)
        if (!source.exists() || source.length() <= 0L) return null
        val target = File(targetDirectory, LEDGER_NAME)
        source.copyTo(target, overwrite = true)
        return target
    }

    fun ledgerFile(context: Context): File {
        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "verification")
        if (!root.exists()) root.mkdirs()
        return File(root, LEDGER_NAME)
    }

    private fun append(context: Context, event: JSONObject) {
        val line = event.toString()
        synchronized(lock) {
            runCatching { ledgerFile(context).appendText(line + "\n") }
                .onFailure { DolphinLogger.e(TAG, "ledger append failed", it) }
        }
        DolphinLogger.i(TAG, line)
    }

    private fun buildFingerprint(context: Context): JSONObject = JSONObject().apply {
        put("package_name", context.packageName)
        put("version_name", BuildConfig.VERSION_NAME)
        put("version_code", BuildConfig.VERSION_CODE)
        put("source_commit", BuildConfig.SOURCE_COMMIT)
        put("workflow_run_id", BuildConfig.BUILD_RUN_ID)
        put("apk_sha256", installedApkSha256(context))
        put("android_build_fingerprint", Build.FINGERPRINT)
        put("android_build_display", Build.DISPLAY)
        put("android_build_id", Build.ID)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("hardware", Build.HARDWARE)
        put("board", Build.BOARD)
        put("sdk", Build.VERSION.SDK_INT)
    }

    private fun preconditions(context: Context): JSONObject {
        val metrics = context.resources.displayMetrics
        val config = context.resources.configuration
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rotation = runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.rotation
        }.getOrDefault(-1)
        val bluetoothEnabled = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled }.getOrNull()

        return JSONObject().apply {
            put("ignition_power_raw", readNoArgInt(context, BODYWORK, "getPowerLevel"))
            put("speed_kmh_raw", readNoArgInt(context, SPEED, "getCurrentSpeed"))
            put("gear_raw", readNoArgInt(context, GEARBOX, "getCurrentGear"))
            put("epb_raw", readNoArgInt(context, GEARBOX, "getEPBState"))
            put("avh_raw", readNoArgInt(context, ADAS, "getAVHState"))
            put("bsd_raw", readNoArgInt(context, ADAS, "getBSDState"))
            put("drive_interface_raw", readNoArgInt(context, INSTRUMENT, "getCurrentDriveInterFace"))
            put("energy_feedback_raw", readNoArgInt(context, INSTRUMENT, "getEnergyFeedback"))
            put("orientation", config.orientation)
            put("rotation", rotation)
            put("density_dpi", metrics.densityDpi)
            put("width_px", metrics.widthPixels)
            put("height_px", metrics.heightPixels)
            put("font_scale", config.fontScale)
            put("audio_mode", audio.mode)
            put("music_active", audio.isMusicActive)
            put("bluetooth_enabled", bluetoothEnabled ?: JSONObject.NULL)
        }
    }

    private fun readNoArgInt(context: Context, className: String, methodName: String): Any {
        return try {
            val clazz = Class.forName(className)
            val instance = clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, BydPermissionContext.wrap(context))
            clazz.getMethod(methodName).invoke(instance) ?: JSONObject.NULL
        } catch (t: Throwable) {
            "ERROR:" + (t.cause ?: t).javaClass.simpleName
        }
    }

    private fun installedApkSha256(context: Context): String {
        cachedApkSha256?.let { return it }
        val digest = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.getOrElse { "ERROR:" + it.javaClass.simpleName }
        cachedApkSha256 = digest
        return digest
    }

    private fun sanitizeNote(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").take(500)

    private const val BODYWORK = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
    private const val SPEED = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    private const val GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
    private const val ADAS = "android.hardware.bydauto.adas.BYDAutoADASDevice"
    private const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
}
