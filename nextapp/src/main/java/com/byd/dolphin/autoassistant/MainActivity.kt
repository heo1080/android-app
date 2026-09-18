package com.byd.dolphin.autoassistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import com.byd.dolphin.autoassistant.core.NextLog
import com.byd.dolphin.autoassistant.core.NextRuntime
import com.byd.dolphin.autoassistant.core.diagnostics.DiagnosticExporter
import com.byd.dolphin.autoassistant.core.display.SplitController
import com.byd.dolphin.autoassistant.core.update.UpdateClient
import com.byd.dolphin.autoassistant.service.DolphinNextService
import com.byd.dolphin.autoassistant.ui.DolphinNextApp

class MainActivity : ComponentActivity() {
    private lateinit var updater: UpdateClient
    private lateinit var splitController: SplitController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NextRuntime.ensure(this)
        updater = UpdateClient(this)
        splitController = SplitController(this)
        startVehicleService()

        setContent {
            DolphinNextApp(
                stateStore = NextRuntime.stateStore,
                alerts = NextRuntime.alerts,
                updater = updater,
                splitController = splitController,
                onShareDiagnostic = { shareDiagnostic() }
            )
        }

        NextLog.i("BOOT", "DolphinAssistant Next started " + BuildConfig.VERSION_NAME)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun startVehicleService() {
        val intent = Intent(this, DolphinNextService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }.onFailure {
            NextLog.e("BOOT", "foreground vehicle service start failed", it)
            NextRuntime.start(this)
        }
    }

    private fun shareDiagnostic() {
        val file = DiagnosticExporter.create(this, NextRuntime.stateStore.state.value, NextRuntime.alerts)
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
