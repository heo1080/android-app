package com.byd.dolphin.autoassistant.manager

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import com.byd.dolphin.autoassistant.hud.MultiNavNotificationListener
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlin.math.roundToLong

data class NowPlayingSnapshot(
    val available: Boolean = false,
    val notificationAccess: Boolean = false,
    val source: String = "NONE",
    val packageName: String = "",
    val appName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artwork: Bitmap? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    val playbackState: Int = 0,
    val error: String = ""
)

/**
 * Two-layer media information path:
 * 1) active MediaSession (full metadata + controls)
 * 2) media notification cache captured by MultiNavNotificationListener
 *
 * The second path is important on DiLink builds where getActiveSessions() can
 * be restricted even while the notification listener itself still receives
 * transport notifications.
 */
class NowPlayingManager(context: Context) {
    private val app = context.applicationContext
    private val sessions = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listener = ComponentName(app, MultiNavNotificationListener::class.java)

    fun hasNotificationAccess(): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(
                app.contentResolver,
                "enabled_notification_listeners"
            )
        }.getOrNull().orEmpty()

        return raw.split(':').any { flat ->
            ComponentName.unflattenFromString(flat)?.packageName == app.packageName
        }
    }

    fun snapshot(): NowPlayingSnapshot {
        val access = hasNotificationAccess()

        if (access) {
            try {
                val controller = chooseController(sessions.getActiveSessions(listener))
                if (controller != null) {
                    return controllerSnapshot(app, controller, true, "MEDIA_SESSION")
                }
            } catch (security: SecurityException) {
                DolphinLogger.w(TAG, "MediaSession access denied despite listener setting: ${security.message}")
            } catch (t: Throwable) {
                DolphinLogger.w(TAG, "MediaSession snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        val cached = MediaNotificationCache.snapshot()
        if (cached.available) {
            return cached.copy(notificationAccess = access, source = "MEDIA_NOTIFICATION")
        }

        return NowPlayingSnapshot(
            available = false,
            notificationAccess = access,
            error = if (access) "NO_ACTIVE_MEDIA" else "NOTIFICATION_ACCESS_REQUIRED"
        )
    }

    fun previous(): Boolean = control(KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.transportControls.skipToPrevious() }

    fun next(): Boolean = control(KeyEvent.KEYCODE_MEDIA_NEXT) { it.transportControls.skipToNext() }

    fun playPause(): Boolean {
        val current = snapshot()
        return if (current.playing) {
            control(KeyEvent.KEYCODE_MEDIA_PAUSE) { it.transportControls.pause() }
        } else {
            control(KeyEvent.KEYCODE_MEDIA_PLAY) { it.transportControls.play() }
        }
    }

    fun openNotificationAccessSettings() {
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            DolphinLogger.w(TAG, "Unable to open notification-listener settings: ${it.message}")
        }
    }

    fun openCurrentMediaApp(): Boolean {
        val pkg = snapshot().packageName.takeIf { it.isNotBlank() } ?: return false
        val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            app.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun chooseController(controllers: List<MediaController>): MediaController? {
        if (controllers.isEmpty()) return null
        return controllers
            .sortedWith(
                compareByDescending<MediaController> {
                    when (it.playbackState?.state) {
                        android.media.session.PlaybackState.STATE_PLAYING -> 4
                        android.media.session.PlaybackState.STATE_BUFFERING -> 3
                        android.media.session.PlaybackState.STATE_PAUSED -> 2
                        else -> 1
                    }
                }.thenByDescending { it.metadata != null }
            )
            .firstOrNull()
    }

    private fun control(keyCode: Int, sessionAction: (MediaController) -> Unit): Boolean {
        if (hasNotificationAccess()) {
            try {
                val controller = chooseController(sessions.getActiveSessions(listener))
                if (controller != null) {
                    sessionAction(controller)
                    DolphinLogger.i(TAG, "MediaSession control key=$keyCode pkg=${controller.packageName}")
                    return true
                }
            } catch (t: Throwable) {
                DolphinLogger.w(TAG, "MediaSession control failed key=$keyCode: ${t.message}")
            }
        }

        val pkg = MediaNotificationCache.snapshot().packageName
        if (pkg.isBlank()) return false
        return sendMediaButton(pkg, keyCode)
    }

    private fun sendMediaButton(packageName: String, keyCode: Int): Boolean {
        return runCatching {
            val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(packageName)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            val up = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(packageName)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
            app.sendOrderedBroadcast(down, null)
            app.sendOrderedBroadcast(up, null)
            DolphinLogger.i(TAG, "Media-button fallback key=$keyCode pkg=$packageName")
            true
        }.getOrElse {
            DolphinLogger.w(TAG, "Media-button fallback failed key=$keyCode pkg=$packageName: ${it.message}")
            false
        }
    }

    companion object {
        private const val TAG = "NOW_PLAYING"
    }
}

/**
 * Notification-side fallback. MultiNavNotificationListener forwards every
 * notification here before filtering navigation notifications.
 */
object MediaNotificationCache {
    @Volatile
    private var latest = NowPlayingSnapshot()

    @Volatile
    private var latestKey: String? = null

    fun snapshot(): NowPlayingSnapshot = latest

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras ?: return

        val token = mediaToken(extras)
        if (token != null) {
            val controller = runCatching { MediaController(context, token) }.getOrNull()
            if (controller != null) {
                latest = controllerSnapshot(context, controller, true, "NOTIFICATION_TOKEN")
                latestKey = sbn.key
                return
            }
        }

        if (n.category != Notification.CATEGORY_TRANSPORT) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && artist.isBlank()) return

        latest = NowPlayingSnapshot(
            available = true,
            notificationAccess = true,
            source = "TRANSPORT_NOTIFICATION",
            packageName = sbn.packageName,
            appName = appLabel(context, sbn.packageName),
            title = title,
            artist = artist,
            album = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        )
        latestKey = sbn.key
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (latestKey == sbn.key) {
            latestKey = null
            latest = NowPlayingSnapshot()
        }
    }

    @Suppress("DEPRECATION")
    private fun mediaToken(extras: android.os.Bundle): MediaSession.Token? {
        return runCatching {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }.getOrNull()
    }
}

private fun controllerSnapshot(
    context: Context,
    controller: MediaController,
    access: Boolean,
    source: String
): NowPlayingSnapshot {
    val metadata = controller.metadata
    val state = controller.playbackState

    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
    var position = state?.position?.coerceAtLeast(0L) ?: 0L

    if (
        state?.state == android.media.session.PlaybackState.STATE_PLAYING &&
        state.lastPositionUpdateTime > 0L
    ) {
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
        position += (elapsed * state.playbackSpeed).roundToLong()
    }
    if (duration > 0L) position = position.coerceAtMost(duration)

    val title = firstNonBlank(
        metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    )
    val artist = firstNonBlank(
        metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
        metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    )
    val album = firstNonBlank(
        metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
        metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
    )
    val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    return NowPlayingSnapshot(
        available = metadata != null || state != null,
        notificationAccess = access,
        source = source,
        packageName = controller.packageName,
        appName = appLabel(context, controller.packageName),
        title = title,
        artist = artist,
        album = album,
        artwork = artwork,
        durationMs = duration,
        positionMs = position,
        playing = state?.state == android.media.session.PlaybackState.STATE_PLAYING,
        playbackState = state?.state ?: 0
    )
}

private fun appLabel(context: Context, packageName: String): String {
    return runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}

private fun firstNonBlank(vararg values: String?): String {
    return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
}
