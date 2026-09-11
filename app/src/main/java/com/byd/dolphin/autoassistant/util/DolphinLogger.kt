package com.byd.dolphin.autoassistant.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.hud.TmapPlusHudBluetoothManager
import com.byd.dolphin.autoassistant.manager.AdbPermissionManager
import com.byd.dolphin.autoassistant.manager.NativeAdbClient
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.split.SplitScreenManager
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object DolphinLogger {
    private const val TAG = "DolphinLogger"
    private const val MAX_MEMORY_LOGS = 2_000
    private const val LOG_FILE_NAME = "DolphinAssistant_v30_Diagnostic.txt"
    private val memoryLogs = ConcurrentLinkedDeque<String>()
    private val writeLock = Any()

    @Volatile
    private var diagnosticEventSink: ((String, String, String) -> Unit)? = null

    @Volatile
    private var rawNavigationLoggingAllowed = false

    @Volatile private var initialized = false
    private var logFile: File? = null

    fun init(context: Context) {
        if (initialized) return
        synchronized(writeLock) {
            if (initialized) return
            val appContext = context.applicationContext
            logFile = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, LOG_FILE_NAME)
            initialized = true

            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                e("CRASH", "FATAL ${thread.name}: ${throwable.message}", throwable)
                exportDiagnosticReport(appContext)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        i("SYSTEM", "Logger initialized version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} sdk=${Build.VERSION.SDK_INT}")
    }

    fun d(tag: String, message: String) { log("DEBUG", tag, message); Log.d("Dolphin_$tag", message) }
    fun i(tag: String, message: String) { log("INFO", tag, message); Log.i("Dolphin_$tag", message) }
    fun w(tag: String, message: String) { log("WARN", tag, message); Log.w("Dolphin_$tag", message) }
    fun e(tag: String, message: String, tr: Throwable? = null) {
        val full = message + (tr?.let { "\n${Log.getStackTraceString(it)}" } ?: "")
        log("ERROR", tag, full)
        Log.e("Dolphin_$tag", full)
    }

    fun logBydEvent(device: String, eventType: String, value: Any?) =
        i("BYD_EVENT", "[$device] type=$eventType value=$value")

    fun logIntent(action: String, extras: String = "") =
        i("INTENT", "action=$action extras=$extras")

    private fun log(level: String, tag: String, message: String) {
        val line = "[${timestamp()}] [$level] [$tag] $message"
        memoryLogs.add(line)
        while (memoryLogs.size > MAX_MEMORY_LOGS) memoryLogs.pollFirst()
        synchronized(writeLock) {
            runCatching {
                logFile?.let { FileWriter(it, true).use { writer -> writer.append(line).append('\n') } }
            }
        }
        runCatching { diagnosticEventSink?.invoke(level, tag, message) }
    }

    fun setDiagnosticEventSink(sink: ((String, String, String) -> Unit)?) {
        diagnosticEventSink = sink
    }

    fun setRawNavigationLoggingAllowed(allowed: Boolean) {
        rawNavigationLoggingAllowed = allowed
    }

    /**
     * 내비게이션 원문은 기본적으로 길이와 해시만 남깁니다. 사용자가 진단 화면에서
     * 명시적으로 허용한 세션에 한해 줄바꿈을 제거한 제한 길이 원문을 기록합니다.
     */
    fun privacySafeNavText(context: Context, value: String): String {
        if (value.isBlank()) return "<empty>"
        if (rawNavigationLoggingAllowed && SettingsManager.isDiagnosticNavTextEnabled(context)) {
            return value.replace(Regex("[\\r\\n\\t]+"), " ").take(500)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "<redacted len=${value.length} sha256=$digest>"
    }

    fun logNavigationNotification(
        context: Context,
        sourceTag: String,
        packageName: String,
        title: String,
        text: String,
        subText: String
    ) {
        i(
            sourceTag,
            "내비 알림 수신: pkg=$packageName, " +
                "title='${privacySafeNavText(context, title)}', " +
                "text='${privacySafeNavText(context, text)}', " +
                "subText='${privacySafeNavText(context, subText)}'"
        )
    }

    fun getRecentLogs(count: Int = 100): List<String> = memoryLogs.toList().takeLast(count)

    fun clearLogs() {
        memoryLogs.clear()
        synchronized(writeLock) { runCatching { logFile?.writeText("") } }
    }

    fun exportDiagnosticReport(context: Context): File {
        init(context)
        val appContext = context.applicationContext
        val target = logFile ?: File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, LOG_FILE_NAME)
        val report = buildString {
            appendLine("====================================================")
            appendLine(" BYD DOLPHIN AUTO ASSISTANT v30 DIAGNOSTIC REPORT")
            appendLine("====================================================")
            appendLine("Generated: ${timestamp()}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Package: ${appContext.packageName}")
            appendLine()
            append(capabilitySnapshot(appContext))
            appendLine()
            appendLine("=== IN-APP EVENTS (${memoryLogs.size}) ===")
            memoryLogs.forEach { appendLine(it) }
            appendLine()
            appendLine("=== FILTERED LOGCAT (up to 500 lines) ===")
            append(captureLogcat())
            appendLine("==================== END ====================")
        }
        synchronized(writeLock) {
            runCatching { target.writeText(report) }
                .onFailure { Log.e(TAG, "Diagnostic write failed", it) }
        }
        i("EXPORT", "Diagnostic report: ${target.absolutePath} bytes=${target.length()}")
        return target
    }

    private fun capabilitySnapshot(context: Context): String = buildString {
        appendLine("=== CAPABILITY / CURRENT STATE ===")
        appendLine("overlay=${AdbPermissionManager.isOverlayGranted(context)}")
        appendLine("secureSettings=${AdbPermissionManager.isSecureSettingsGranted(context)}")
        appendLine("notificationListener=${AdbPermissionManager.isNotificationListenerGranted(context)}")
        appendLine("accessibilityBridge=${isAccessibilityEnabled(context)}")
        appendLine("localAdbPort127.0.0.1:5555=${NativeAdbClient.isPortOpen()}")
        appendLine("hudIdentity=TMAP Plus HUD / T900; Inforatech; R-R-9IT-JARVIS3000")
        appendLine("hudProtocolConfirmed=${SettingsManager.isHudProtocolConfirmed(context)}")
        appendLine("hudSppConnected=${TmapPlusHudBluetoothManager.isHudDataConnected}")
        appendLine("hudAudioConfirmed=${TmapPlusHudBluetoothManager.isHudAudioConnected}")
        appendLine("diagnosticNavTextPreference=${SettingsManager.isDiagnosticNavTextEnabled(context)}")
        appendLine("diagnosticNavTextRawActive=$rawNavigationLoggingAllowed")
        appendLine("diagnosticStructuredCapture=SUPPORTED_15_MIN_JSONL_ZIP")
        appendLine("diagnosticRawBluetoothHci=UNAVAILABLE_ANDROID_SECURITY")
        appendLine("clusterTbtEnabled=${SettingsManager.isClusterTbtEnabled(context)}")
        appendLine("experimentalParkingSensorLvda=${SettingsManager.isExperimentalLvdaEnabled(context)}")
        appendLine("hazardSetter=UNAVAILABLE_COMMANDS_BLOCKED")
        appendLine("splitLast=${SplitScreenManager.getLastConfig(context)}")
        appendLine("bootAuto=${SettingsManager.isBootAutoEnabled(context)} apps=${SettingsManager.getBootAppList(context)}")
        appendLine()
        appendLine("--- BYD permissions ---")
        BYD_PERMISSIONS.forEach { permission -> appendLine("$permission=${permissionState(context, permission)}") }
        appendLine()
        appendLine("--- BYD read-only probes ---")
        appendLine("ignition.powerLevel=${vehicleInt(context, BODYWORK, "getPowerLevel")}")
        appendLine("speed.currentKmh=${vehicleInt(context, SPEED, "getCurrentSpeed")}")
        appendLine("gearbox.currentGear.raw=${vehicleInt(context, GEARBOX, "getCurrentGear")}")
        appendLine("gearbox.epb.raw=${vehicleInt(context, GEARBOX, "getEPBState")}")
        appendLine("charging.work.raw=${vehicleInt(context, CHARGING, "getChargerWorkState")}")
        appendLine("charging.state.raw=${vehicleInt(context, CHARGING, "getChargingState")}")
        appendLine("charging.gun.raw=${vehicleInt(context, CHARGING, "getChargingGunState")}")
        appendLine("adas.avh.raw=${vehicleInt(context, ADAS, "getAVHState")}")
        appendLine("adas.bsd.raw=${vehicleInt(context, ADAS, "getBSDState")}")
        appendLine("adas.laneOffset.raw=${vehicleInt(context, ADAS, "getLaneOffsetState")}")
        appendLine("adas.tja.raw=${vehicleInt(context, ADAS, "getTJAState")}")
        appendLine("instrument.driveInterface.raw=${vehicleInt(context, INSTRUMENT, "getCurrentDriveInterFace")}")
        appendLine("instrument.energyFeedback.raw=${vehicleInt(context, INSTRUMENT, "getEnergyFeedback")}")
        appendLine("seat.driver.raw=${vehicleInt(context, SETTING, "getSeatHeatingState", 1)}")
        appendLine("seat.passenger.raw=${vehicleInt(context, SETTING, "getSeatHeatingState", 2)}")
        appendLine("steeringHeat.raw=${vehicleInt(context, SETTING, "getSteeringWheelHeatingState")}")
        appendLine("insideLightDoor.raw=${vehicleInt(context, SETTING, "getInsideLightDoorState")}")
        appendLine("ac.start.raw=${vehicleInt(context, AC, "getAcStartState")}")
        appendLine("defrost.front.raw=${vehicleInt(context, AC, "getAcDefrostState", 1)}")
        appendLine("defrost.rear.raw=${vehicleInt(context, AC, "getAcDefrostState", 2)}")
        appendLine("hazard.raw=${vehicleInt(context, LIGHT, "getDoubleFlashLightState")}")
        appendLine("radar.frontLeftMid.raw=${vehicleInt(context, RADAR, "getRadarObstacleDistance", 7)}")
        appendLine("radar.frontRightMid.raw=${vehicleInt(context, RADAR, "getRadarObstacleDistance", 8)}")
    }

    private fun vehicleInt(context: Context, className: String, method: String, vararg args: Int): String {
        return try {
            val clazz = Class.forName(className)
            val instance = clazz.getMethod("getInstance", Context::class.java).invoke(null, com.byd.dolphin.autoassistant.manager.BydPermissionContext.wrap(context))
            val types = Array(args.size) { Int::class.javaPrimitiveType!! }
            val value = clazz.getMethod(method, *types).invoke(instance, *args.toTypedArray())
            value?.toString() ?: "null"
        } catch (e: Exception) {
            "ERROR:${(e.cause ?: e).javaClass.simpleName}:${(e.cause ?: e).message}"
        }
    }

    private fun permissionState(context: Context, permission: String): String =
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.contains("${context.packageName}/", ignoreCase = true)
    }

    private fun captureLogcat(): String = buildString {
        try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "500").start()
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.filter { it.contains("Dolphin_") || it.contains("BYDAuto") || it.contains("dolphin.autoassistant") }
                    .take(500)
                    .forEach { appendLine(it) }
            }
        } catch (e: Exception) {
            appendLine("logcat unavailable: ${e.message}")
        }
    }

    private fun timestamp(): String = synchronized(writeLock) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA).format(Date())
    }

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
    private val BYD_PERMISSIONS = listOf(
        "android.permission.BYDAUTO_BODYWORK_GET",
        "android.permission.BYDAUTO_SETTING_GET",
        "android.permission.BYDAUTO_SETTING_SET",
        "android.permission.BYDAUTO_AC_GET",
        "android.permission.BYDAUTO_AC_SET",
        "android.permission.BYDAUTO_LIGHT_GET",
        "android.permission.BYDAUTO_LIGHT_SET",
        "android.permission.BYDAUTO_RADAR_GET",
        "android.permission.BYDAUTO_SPEED_GET",
        "android.permission.BYDAUTO_GEARBOX_GET",
        "android.permission.BYDAUTO_CHARGING_GET",
        "android.permission.BYDAUTO_ADAS_GET",
        "android.permission.BYDAUTO_INSTRUMENT_GET",
        "android.permission.BYDAUTO_INSTRUMENT_SET"
    )
}
