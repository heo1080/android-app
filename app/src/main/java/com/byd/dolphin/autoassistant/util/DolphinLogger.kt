package com.byd.dolphin.autoassistant.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

object DolphinLogger {

    private const val TAG = "DolphinLogger"
    private const val MAX_MEMORY_LOGS = 1000
    private const val LOG_FILE_NAME = "DolphinAssistant_DebugLog.txt"

    private val memoryLogs = ConcurrentLinkedDeque<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA)
    private var isInitialized = false
    private var logFile: File? = null

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        try {
            val sdDir = Environment.getExternalStorageDirectory()
            logFile = if (sdDir != null && sdDir.canWrite()) {
                File(sdDir, LOG_FILE_NAME)
            } else {
                File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_FILE_NAME)
            }

            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                e("CRASH", "FATAL EXCEPTION in thread ${thread.name}: ${throwable.message}", throwable)
                exportDiagnosticReport(context)
                defaultHandler?.uncaughtException(thread, throwable)
            }

            i("SYSTEM", "=== DolphinAssistant Logger Initialized (App Version: 1.6.0, SDK: ${Build.VERSION.SDK_INT}) ===")
        } catch (ex: Exception) {
            Log.e(TAG, "Logger init failed", ex)
        }
    }

    fun d(tag: String, message: String) {
        log("DEBUG", tag, message)
        Log.d("Dolphin_$tag", message)
    }

    fun i(tag: String, message: String) {
        log("INFO", tag, message)
        Log.i("Dolphin_$tag", message)
    }

    fun w(tag: String, message: String) {
        log("WARN", tag, message)
        Log.w("Dolphin_$tag", message)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        val stackTrace = tr?.let { "\n" + Log.getStackTraceString(it) } ?: ""
        val fullMsg = message + stackTrace
        log("ERROR", tag, fullMsg)
        Log.e("Dolphin_$tag", fullMsg)
    }

    fun logBydEvent(device: String, eventType: String, value: Any?) {
        i("BYD_EVENT", "[$device] Type: $eventType, Value: $value")
    }

    fun logIntent(action: String, extras: String = "") {
        i("INTENT", "Action: $action, Extras: $extras")
    }

    private fun log(level: String, tag: String, message: String) {
        val timeStr = dateFormat.format(Date())
        val logEntry = "[$timeStr] [$level] [$tag] $message"

        memoryLogs.add(logEntry)
        while (memoryLogs.size > MAX_MEMORY_LOGS) {
            memoryLogs.pollFirst()
        }

        try {
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.append(logEntry).append("\n")
                }
            }
        } catch (ignored: Exception) {}
    }

    fun getRecentLogs(count: Int = 100): List<String> {
        return memoryLogs.toList().takeLast(count)
    }

    fun clearLogs() {
        memoryLogs.clear()
        try {
            logFile?.let { file ->
                if (file.exists()) file.writeText("")
            }
        } catch (ignored: Exception) {}
    }

    fun exportDiagnosticReport(context: Context): File {
        val targetFile = logFile ?: File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_FILE_NAME)

        try {
            val sb = StringBuilder()
            sb.append("====================================================\n")
            sb.append("      BYD DOLPHIN AUTO ASSISTANT DIAGNOSTIC REPORT  \n")
            sb.append("====================================================\n")
            sb.append("Generated At: ${dateFormat.format(Date())}\n")
            sb.append("Device Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
            sb.append("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            sb.append("Package Name: ${context.packageName}\n")
            sb.append("----------------------------------------------------\n\n")

            sb.append("=== [1] RECENT IN-APP EVENT LOGS (${memoryLogs.size} lines) ===\n")
            for (line in memoryLogs) {
                sb.append(line).append("\n")
            }
            sb.append("\n----------------------------------------------------\n\n")

            sb.append("=== [2] SYSTEM LOGCAT SLICE (Last 300 Lines) ===\n")
            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "300"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var count = 0
                reader.forEachLine { line ->
                    if (count < 300) {
                        sb.append(line).append("\n")
                        count++
                    }
                }
            } catch (e: Exception) {
                sb.append("Failed to capture system logcat: ${e.message}\n")
            }
            sb.append("\n==================== END OF REPORT ====================\n")

            targetFile.writeText(sb.toString())
            i("EXPORT", "Diagnostic report written to: ${targetFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostic report", e)
        }
        return targetFile
    }
}
