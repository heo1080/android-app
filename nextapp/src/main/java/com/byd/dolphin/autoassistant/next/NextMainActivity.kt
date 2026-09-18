package com.byd.dolphin.autoassistant.next

import android.os.Bundle
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.byd.dolphin.autoassistant.next.automation.NextRuntimeService
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.launcher.LauncherNavigationBus
import com.byd.dolphin.autoassistant.next.ui.NextApp

class NextMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NextLogger.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        NextRuntime.start(this)
        LauncherNavigationBus.requestHome(intent?.getStringExtra("launcher_reason") ?: "activity_create")
        ContextCompat.startForegroundService(
            this,
            Intent(this, NextRuntimeService::class.java)
        )

        setContent {
            NextApp(
                repository = NextRuntime.repository,
                audio = NextRuntime.audio,
                diagnostics = NextRuntime.diagnostics
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LauncherNavigationBus.requestHome(
            intent.getStringExtra("launcher_reason") ?: "home_or_relaunch"
        )
    }
}
