package com.byd.dolphin.autoassistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import com.byd.dolphin.autoassistant.core.NextLog
import com.byd.dolphin.autoassistant.core.audio.AlertEngine
import com.byd.dolphin.autoassistant.core.diagnostics.DiagnosticExporter
import com.byd.dolphin.autoassistant.core.event.VehicleEventEngine
import com.byd.dolphin.autoassistant.core.state.VehicleStateStore
import com.byd.dolphin.autoassistant.core.update.UpdateClient
import com.byd.dolphin.autoassistant.ui.DolphinNextApp

class MainActivity : ComponentActivity() {
    private lateinit var stateStore: VehicleStateStore
    private lateinit var alerts: AlertEngine
    private lateinit var events: VehicleEventEngine
    private lateinit var updater: UpdateClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NextLog.init(this)
        stateStore = VehicleStateStore(this)
        alerts = AlertEngine(this)
        events = VehicleEventEngine(stateStore, alerts)
        updater = UpdateClient(this)

        stateStore.start()
        events.start()
        alerts.ensureTtsModel()

        setContent {
            DolphinNextApp(
                stateStore = stateStore,
                alerts = alerts,
                updater = updater,
                onShareDiagnostic = { shareDiagnostic() }
            )
        }

        NextLog.i("BOOT", "DolphinAssistant Next started " + BuildConfig.VERSION_NAME)
    }

    override fun onDestroy() {
        events.stop()
        stateStore.stop()
        alerts.release()
        super.onDestroy()
    }

    private fun shareDiagnostic() {
        val file = DiagnosticExporter.create(this, stateStore.state.value, alerts)
        val uri = FileProvider.getUriForFile(
            this,
            packageName + ".fileprovider",
            file
        )
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "DolphinAssistant Next 진단 공유"))
    }
}
