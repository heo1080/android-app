package com.byd.dolphin.autoassistant.core.diagnostics

import android.content.Context
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.core.audio.AlertCatalog
import com.byd.dolphin.autoassistant.core.audio.AlertEngine
import com.byd.dolphin.autoassistant.core.model.VehicleState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticExporter {
    fun create(context: Context, state: VehicleState, alerts: AlertEngine): File {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "DolphinAssistant_Next_Diagnostic_" + stamp + ".txt")
        val live = File(dir, "next-live.log")

        out.bufferedWriter().use { writer ->
            writer.appendLine("DolphinAssistant Next diagnostic")
            writer.appendLine("version=" + BuildConfig.VERSION_NAME + " code=" + BuildConfig.VERSION_CODE)
            writer.appendLine("time=" + System.currentTimeMillis())
            writer.appendLine()
            writer.appendLine("[vehicle]")
            writer.appendLine(state.toString())
            writer.appendLine()
            writer.appendLine("[alerts]")
            AlertCatalog.specs.forEach { spec ->
                writer.appendLine(spec.id + "=" + alerts.repository.getProfile(spec.id))
            }
            writer.appendLine("ttsReady=" + alerts.ttsReady())
            writer.appendLine("ttsDownloading=" + alerts.ttsDownloading())
            writer.appendLine("ttsError=" + alerts.ttsLastError())
            writer.appendLine()
            writer.appendLine("[live-log]")
            if (live.exists()) writer.append(live.readText().takeLast(250_000))
        }
        return out
    }
}
