package com.byd.dolphin.autoassistant.next.launcher

import android.content.Context
import com.byd.dolphin.autoassistant.next.NextRuntime
import com.byd.dolphin.autoassistant.next.core.NextLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LauncherWatchdog(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun startForIgnition() {
        job?.cancel()
        val startedAt = System.currentTimeMillis()
        job = scope.launch {
            NextLogger.i("LAUNCHER_WATCHDOG", "start window=60000ms")

            var attempts = 0
            var elapsed = 0L

            while (isActive && elapsed < WINDOW_MS) {
                delay(CHECK_INTERVAL_MS)
                elapsed = System.currentTimeMillis() - startedAt

                val runtimeOk = NextRuntime.isStarted()
                val resumedSinceStart = LauncherPresenceTracker.resumedSince(startedAt)
                val currentlyResumed = LauncherPresenceTracker.isResumed()

                NextLogger.i(
                    "LAUNCHER_WATCHDOG",
                    "elapsed=" + elapsed +
                        " runtime=" + runtimeOk +
                        " resumedSinceStart=" + resumedSinceStart +
                        " currentlyResumed=" + currentlyResumed +
                        " attempts=" + attempts
                )

                if (runtimeOk && resumedSinceStart) {
                    NextLogger.i("LAUNCHER_WATCHDOG", "PASS · launcher resumed after ignition")
                    break
                }

                val shouldRetry =
                    attempts < MAX_RETRY &&
                        (elapsed >= RETRY_ONE_MS && attempts == 0 ||
                            elapsed >= RETRY_TWO_MS && attempts == 1)

                if (shouldRetry) {
                    attempts++
                    LauncherStartController.showHome(
                        app,
                        "watchdog_retry_" + attempts
                    )
                    NextLogger.w(
                        "LAUNCHER_WATCHDOG",
                        "retry=" + attempts + " elapsed=" + elapsed
                    )
                }
            }

            if (!LauncherPresenceTracker.resumedSince(startedAt)) {
                NextLogger.w(
                    "LAUNCHER_WATCHDOG",
                    "END · launcher never resumed within 60s attempts=" + attempts
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val WINDOW_MS = 60_000L
        private const val CHECK_INTERVAL_MS = 5_000L
        private const val RETRY_ONE_MS = 8_000L
        private const val RETRY_TWO_MS = 25_000L
        private const val MAX_RETRY = 2
    }
}
