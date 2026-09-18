package com.byd.dolphin.autoassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.byd.dolphin.autoassistant.core.BydReflectionGateway
import com.byd.dolphin.autoassistant.core.VehicleRepository
import com.byd.dolphin.autoassistant.ui.NextApp
import com.byd.dolphin.autoassistant.update.UpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repository: VehicleRepository
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = VehicleRepository(BydReflectionGateway(this))
        pollJob = lifecycleScope.launch { repository.run() }
        setContent {
            NextApp(
                repository = repository,
                onCheckUpdate = { UpdateManager.checkAndPrompt(this, manual = true) }
            )
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }
}
