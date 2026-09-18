package com.byd.dolphin.autoassistant.next.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NextLogger {
    private const val ROOT = "DA_NEXT"
    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics").apply { mkdirs() }
        logFile = File(dir, "DolphinAssistant_Next.log")
        i("BOOT", "logger initialized")
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String) = write("W", tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val fullTag = "$ROOT/$tag"
        when (level) {
            "D" -> Log.d(fullTag, msg, t)
            "I" -> Log.i(fullTag, msg, t)
            "W" -> Log.w(fullTag, msg, t)
            else -> Log.e(fullTag, msg, t)
        }
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val extra = t?.let { "\n" + Log.getStackTraceString(it) }.orEmpty()
            logFile?.appendText(stamp + " " + level + " " + tag + " " + msg + extra + "\n")
        }
    }
}
