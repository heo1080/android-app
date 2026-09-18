package com.byd.dolphin.autoassistant.next.launcher

import com.byd.dolphin.autoassistant.next.core.NextLogger

object LauncherPresenceTracker {
    @Volatile private var lastResumedAtMs: Long = 0L
    @Volatile private var lastPausedAtMs: Long = 0L
    @Volatile private var resumed: Boolean = false

    fun onResumed() {
        resumed = true
        lastResumedAtMs = System.currentTimeMillis()
        NextLogger.i("LAUNCHER_PRESENCE", "resumed at=" + lastResumedAtMs)
    }

    fun onPaused() {
        resumed = false
        lastPausedAtMs = System.currentTimeMillis()
        NextLogger.i("LAUNCHER_PRESENCE", "paused at=" + lastPausedAtMs)
    }

    fun isResumed(): Boolean = resumed
    fun lastResumedAtMs(): Long = lastResumedAtMs
    fun resumedSince(timestampMs: Long): Boolean = lastResumedAtMs >= timestampMs
}
