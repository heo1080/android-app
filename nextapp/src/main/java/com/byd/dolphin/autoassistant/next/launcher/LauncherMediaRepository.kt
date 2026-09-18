package com.byd.dolphin.autoassistant.next.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.hud.NextNavNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LauncherMediaState(
    val connected: Boolean = false,
    val packageName: String = "",
    val appLabel: String = "미디어",
    val title: String = "재생 중인 미디어 없음",
    val artist: String = "",
    val album: String = "",
    val artwork: Bitmap? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

class LauncherMediaRepository(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(LauncherMediaState())
    val state: StateFlow<LauncherMediaState> = _state

    @Volatile private var controller: MediaController? = null
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                sample()
                delay(700L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        controller = null
    }

    fun previous() {
        runCatching { controller?.transportControls?.skipToPrevious() }
            .onFailure { NextLogger.e("LAUNCHER_MEDIA", "previous failed", it) }
    }

    fun next() {
        runCatching { controller?.transportControls?.skipToNext() }
            .onFailure { NextLogger.e("LAUNCHER_MEDIA", "next failed", it) }
    }

    fun togglePlay() {
        val c = controller ?: return
        runCatching {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
        }.onFailure { NextLogger.e("LAUNCHER_MEDIA", "toggle failed", it) }
    }

    private fun sample() {
        val controllers = activeSessions()
        val selected = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        if (selected == null) {
            controller = null
            if (_state.value.connected) _state.value = LauncherMediaState()
            return
        }

        controller = selected
        val metadata = selected.metadata
        val playback = selected.playbackState
        val pkg = selected.packageName.orEmpty()
        val label = runCatching {
            val info = app.packageManager.getApplicationInfo(pkg, 0)
            info.loadLabel(app.packageManager).toString()
        }.getOrDefault(pkg.substringAfterLast('.').ifBlank { "미디어" })

        val title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.toString()
            ?: "재생 정보 없음"
        val artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.toString()
            ?: ""
        val album = metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString().orEmpty()
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.takeIf { it > 0L } ?: 0L
        val position = playback?.position?.coerceAtLeast(0L) ?: 0L

        _state.value = LauncherMediaState(
            connected = true,
            packageName = pkg,
            appLabel = label,
            title = title,
            artist = artist,
            album = album,
            artwork = art,
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            positionMs = position,
            durationMs = duration
        )
    }

    private fun activeSessions(): List<MediaController> = runCatching {
        val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listener = ComponentName(app, NextNavNotificationListener::class.java)
        manager.getActiveSessions(listener)
    }.onFailure {
        NextLogger.w(
            "LAUNCHER_MEDIA",
            "active sessions unavailable: " + it.javaClass.simpleName + ":" + it.message
        )
    }.getOrDefault(emptyList())
}
