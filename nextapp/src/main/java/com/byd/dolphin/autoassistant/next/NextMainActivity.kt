package com.byd.dolphin.autoassistant.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.byd.dolphin.autoassistant.next.audio.NextAudioEngine
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.events.NextEventEngine
import com.byd.dolphin.autoassistant.next.ui.NextApp
import com.byd.dolphin.autoassistant.next.vehicle.VehicleRepository

class NextMainActivity : ComponentActivity() {
    private lateinit var repository: VehicleRepository
    private lateinit var audio: NextAudioEngine
    private lateinit var events: NextEventEngine

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
        events = NextEventEngine(repository, audio)
        repository.start()
        events.start()

        setContent {
            NextApp(repository = repository, audio = audio)
        }
    }

    override fun onDestroy() {
        events.stop()
        repository.stop()
        super.onDestroy()
    }
}
