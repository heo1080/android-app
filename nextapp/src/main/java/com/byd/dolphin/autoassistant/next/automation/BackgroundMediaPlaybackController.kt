package com.byd.dolphin.autoassistant.next.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.hud.NextNavNotificationListener
import java.util.concurrent.ConcurrentHashMap

object BackgroundMediaPlaybackController {
    data class Result(
        val success: Boolean,
        val stage: String,
        val detail: String
    )

    private val main = Handler(Looper.getMainLooper())
    private val browsers = ConcurrentHashMap<String, MediaBrowser>()
    private val controllers = ConcurrentHashMap<String, MediaController>()

    fun prepare(context: Context, packageName: String, callback: (Result) -> Unit = {}) {
        val app = context.applicationContext
        main.post {
            activeController(app, packageName)?.let { controller ->
                controllers[packageName] = controller
                val detail = "active MediaSession ready state=" + playbackState(controller)
                NextLogger.i("MEDIA_BG", packageName + " prepare active-session " + detail)
                callback(Result(true, "ACTIVE_SESSION", detail))
                return@post
            }

            connectMediaBrowser(app, packageName, playWhenConnected = false, callback = callback)
        }
    }

    fun play(context: Context, packageName: String, callback: (Result) -> Unit = {}) {
        val app = context.applicationContext
        main.post {
            controllers[packageName]?.let { controller ->
                runCatching {
                    controller.transportControls.play()
                    verifyAfterDelay(packageName, controller, "CACHED_SESSION", callback)
                }.onFailure { error ->
                    controllers.remove(packageName)
                    NextLogger.e("MEDIA_BG", packageName + " cached play failed", error)
                    playViaDiscovery(app, packageName, callback)
                }
                return@post
            }

            activeController(app, packageName)?.let { controller ->
                controllers[packageName] = controller
                runCatching {
                    controller.transportControls.play()
                    verifyAfterDelay(packageName, controller, "ACTIVE_SESSION", callback)
                }.onFailure { error ->
                    NextLogger.e("MEDIA_BG", packageName + " active-session play failed", error)
                    playViaDiscovery(app, packageName, callback)
                }
                return@post
            }

            playViaDiscovery(app, packageName, callback)
        }
    }

    fun release(packageName: String? = null) {
        main.post {
            if (packageName == null) {
                browsers.values.forEach { runCatching { it.disconnect() } }
                browsers.clear()
                controllers.clear()
                NextLogger.i("MEDIA_BG", "all media browser connections released")
            } else {
                runCatching { browsers.remove(packageName)?.disconnect() }
                controllers.remove(packageName)
                NextLogger.i("MEDIA_BG", packageName + " media browser released")
            }
        }
    }

    private fun playViaDiscovery(
        context: Context,
        packageName: String,
        callback: (Result) -> Unit
    ) {
        val services = findMediaBrowserServices(context, packageName)
        if (services.isNotEmpty()) {
            connectMediaBrowser(context, packageName, playWhenConnected = true, callback = callback)
            return
        }

        val receiverResult = sendExplicitMediaButton(context, packageName)
        NextLogger.i(
            "MEDIA_BG",
            packageName + " explicit media-button success=" + receiverResult.success +
                " detail=" + receiverResult.detail
        )
        callback(receiverResult)
    }

    private fun connectMediaBrowser(
        context: Context,
        packageName: String,
        playWhenConnected: Boolean,
        callback: (Result) -> Unit
    ) {
        val service = findMediaBrowserServices(context, packageName).firstOrNull()
        if (service == null) {
            val detail = "no MediaBrowserService exposed"
            NextLogger.w("MEDIA_BG", packageName + " " + detail)
            callback(Result(false, "MEDIA_BROWSER", detail))
            return
        }

        val existing = browsers[packageName]
        if (existing != null && existing.isConnected) {
            val controller = runCatching { MediaController(context, existing.sessionToken) }.getOrNull()
            if (controller != null) {
                controllers[packageName] = controller
                if (playWhenConnected) {
                    runCatching { controller.transportControls.play() }
                        .onSuccess {
                            verifyAfterDelay(packageName, controller, "MEDIA_BROWSER", callback)
                        }
                        .onFailure {
                            callback(Result(false, "MEDIA_BROWSER", "play failed: " + it.message))
                        }
                } else {
                    callback(Result(true, "MEDIA_BROWSER", "connected service=" + service.flattenToShortString()))
                }
                return
            }
        }

        runCatching { existing?.disconnect() }
        browsers.remove(packageName)

        var browser: MediaBrowser? = null
        val callbackObject = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val connected = browser ?: return
                val controller = runCatching {
                    MediaController(context, connected.sessionToken)
                }.getOrElse { error ->
                    NextLogger.e("MEDIA_BG", packageName + " MediaController create failed", error)
                    callback(Result(false, "MEDIA_BROWSER", "controller create failed: " + error.message))
                    return
                }

                browsers[packageName] = connected
                controllers[packageName] = controller
                val base = "service=" + service.flattenToShortString() + " state=" + playbackState(controller)
                NextLogger.i("MEDIA_BG", packageName + " browser connected " + base)

                if (playWhenConnected) {
                    runCatching { controller.transportControls.play() }
                        .onSuccess {
                            verifyAfterDelay(packageName, controller, "MEDIA_BROWSER", callback)
                        }
                        .onFailure { error ->
                            NextLogger.e("MEDIA_BG", packageName + " browser transport play failed", error)
                            callback(Result(false, "MEDIA_BROWSER", "play failed: " + error.message))
                        }
                } else {
                    callback(Result(true, "MEDIA_BROWSER", base))
                }
            }

            override fun onConnectionSuspended() {
                controllers.remove(packageName)
                NextLogger.w("MEDIA_BG", packageName + " MediaBrowser suspended")
            }

            override fun onConnectionFailed() {
                browsers.remove(packageName)
                controllers.remove(packageName)
                NextLogger.w("MEDIA_BG", packageName + " MediaBrowser connection failed")
                val fallback = sendExplicitMediaButton(context, packageName)
                callback(fallback)
            }
        }

        browser = MediaBrowser(context, service, callbackObject, Bundle.EMPTY)
        browsers[packageName] = browser
        runCatching { browser.connect() }
            .onFailure { error ->
                browsers.remove(packageName)
                NextLogger.e("MEDIA_BG", packageName + " MediaBrowser connect exception", error)
                callback(sendExplicitMediaButton(context, packageName))
            }
    }

    private fun activeController(context: Context, packageName: String): MediaController? {
        return runCatching {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = ComponentName(context, NextNavNotificationListener::class.java)
            manager.getActiveSessions(listener).firstOrNull {
                it.packageName == packageName
            }
        }.onFailure { error ->
            NextLogger.w(
                "MEDIA_BG",
                packageName + " active session query unavailable: " +
                    error.javaClass.simpleName + ":" + error.message
            )
        }.getOrNull()
    }

    private fun findMediaBrowserServices(
        context: Context,
        packageName: String
    ): List<ComponentName> {
        val intent = Intent(MediaBrowserServiceInterface).setPackage(packageName)
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                ComponentName(service.packageName, service.name)
            }
            .distinct()
            .also {
                NextLogger.i(
                    "MEDIA_BG",
                    packageName + " MediaBrowserService candidates=" +
                        it.joinToString { component -> component.flattenToShortString() }
                )
            }
    }

    private fun sendExplicitMediaButton(
        context: Context,
        packageName: String
    ): Result {
        val base = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageName)
        @Suppress("DEPRECATION")
        val receivers = context.packageManager.queryBroadcastReceivers(base, 0)
        val receiver = receivers.firstOrNull()?.activityInfo
        if (receiver != null) {
            val component = ComponentName(receiver.packageName, receiver.name)
            return runCatching {
                sendKeyToReceiver(context, component, KeyEvent.ACTION_DOWN)
                sendKeyToReceiver(context, component, KeyEvent.ACTION_UP)
                Result(
                    true,
                    "MEDIA_BUTTON_RECEIVER",
                    "explicit receiver=" + component.flattenToShortString()
                )
            }.getOrElse { error ->
                Result(false, "MEDIA_BUTTON_RECEIVER", error.javaClass.simpleName + ":" + error.message)
            }
        }

        @Suppress("DEPRECATION")
        val services = context.packageManager.queryIntentServices(base, 0)
        val service = services.firstOrNull()?.serviceInfo
        if (service != null) {
            val component = ComponentName(service.packageName, service.name)
            return runCatching {
                startKeyService(context, component, KeyEvent.ACTION_DOWN)
                startKeyService(context, component, KeyEvent.ACTION_UP)
                Result(
                    true,
                    "MEDIA_BUTTON_SERVICE",
                    "explicit service=" + component.flattenToShortString()
                )
            }.getOrElse { error ->
                Result(false, "MEDIA_BUTTON_SERVICE", error.javaClass.simpleName + ":" + error.message)
            }
        }

        return Result(
            false,
            "NO_BACKGROUND_MEDIA_ENDPOINT",
            "no active session, MediaBrowserService, MEDIA_BUTTON receiver or service"
        )
    }

    private fun sendKeyToReceiver(
        context: Context,
        component: ComponentName,
        action: Int
    ) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            this.component = component
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
        }
        context.sendBroadcast(intent)
    }

    private fun startKeyService(
        context: Context,
        component: ComponentName,
        action: Int
    ) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            this.component = component
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
        }
        context.startService(intent)
    }

    private fun verifyAfterDelay(
        packageName: String,
        controller: MediaController,
        stage: String,
        callback: (Result) -> Unit
    ) {
        main.postDelayed({
            val state = playbackState(controller)
            val isPlaying = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            val detail = "play requested state=" + state
            NextLogger.i("MEDIA_BG", packageName + " " + stage + " " + detail)
            callback(Result(true, stage, detail + if (isPlaying) " · PLAYING" else " · requested"))
        }, 650L)
    }

    private fun playbackState(controller: MediaController): String {
        val state = controller.playbackState ?: return "null"
        return state.state.toString() + " pos=" + state.position + " speed=" + state.playbackSpeed
    }

    private const val MediaBrowserServiceInterface = "android.media.browse.MediaBrowserService"
}
