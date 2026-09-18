package com.byd.dolphin.autoassistant.next.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NextLogger {
    data class Entry(
        val timestampMs: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    private const val ROOT = "DA_NEXT"
    private const val RING_MAX = 2400
    @Volatile private var logFile: File? = null
    private val ring = ArrayDeque<Entry>()
    private val ringLock = Any()

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

    fun snapshot(fromMs: Long, toMs: Long): List<Entry> = synchronized(ringLock) {
        ring.filter { it.timestampMs in fromMs..toMs }
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val fullTag = "$ROOT/$tag"
        val now = System.currentTimeMillis()
        synchronized(ringLock) {
            ring.addLast(Entry(now, level, tag, msg))
            while (ring.size > RING_MAX) ring.removeFirst()
        }
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
