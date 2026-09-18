package com.byd.dolphin.autoassistant.next

import android.os.Bundle
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.byd.dolphin.autoassistant.next.audio.NextAudioEngine
import com.byd.dolphin.autoassistant.next.automation.NextRuntimeService
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.diagnostics.DolphinDiagnostics
import com.byd.dolphin.autoassistant.next.diagnostics.RecentDriveRecorder
import com.byd.dolphin.autoassistant.next.events.NextEventEngine
import com.byd.dolphin.autoassistant.next.ui.NextApp
import com.byd.dolphin.autoassistant.next.vehicle.VehicleRepository

class NextMainActivity : ComponentActivity() {
    private lateinit var repository: VehicleRepository
    private lateinit var audio: NextAudioEngine
    private lateinit var events: NextEventEngine
    private lateinit var diagnostics: DolphinDiagnostics
    private lateinit var recentDrive: RecentDriveRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NextLogger.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        repository = VehicleRepository(this)
        audio = NextAudioEngine(this)
        audio.warmup()
        ContextCompat.startForegroundService(
            this,
            Intent(this, NextRuntimeService::class.java)
        )
        events = NextEventEngine(repository, audio)
        recentDrive = RecentDriveRecorder(repository, audio)
        diagnostics = DolphinDiagnostics(this, repository, audio, recentDrive)
        repository.start()
        events.start()
        recentDrive.start()

        setContent {
            NextApp(
                repository = repository,
                audio = audio,
                diagnostics = diagnostics
            )
        }
    }

    override fun onDestroy() {
        recentDrive.stop()
        events.stop()
        repository.stop()
        super.onDestroy()
    }
}
