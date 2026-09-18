package com.byd.dolphin.autoassistant.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NextLog {
    private const val ROOT = "DolphinNext"
    private val lock = Any()
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "diagnostics/next-live.log").apply { parentFile?.mkdirs() }
        i("BOOT", "logger initialized")
    }

    fun d(tag: String, message: String) = write("D", tag, message, null)
    fun i(tag: String, message: String) = write("I", tag, message, null)
    fun w(tag: String, message: String) = write("W", tag, message, null)
    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val full = "[$tag] $message"
        when (level) {
            "E" -> Log.e(ROOT, full, error)
            "W" -> Log.w(ROOT, full, error)
            "D" -> Log.d(ROOT, full)
            else -> Log.i(ROOT, full)
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lock) {
            runCatching {
                file?.appendText("$stamp $level $tag $message\n")
                error?.let { file?.appendText(Log.getStackTraceString(it) + "\n") }
            }
        }
    }
}
