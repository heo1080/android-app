package com.byd.dolphin.autoassistant.next.automation

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.integrated.BootAppRule
import com.byd.dolphin.autoassistant.next.integrated.IntegratedSettings
import com.byd.dolphin.autoassistant.next.system.NextAdb

class BootAutomationController(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Runnable>()
    @Volatile private var active = false

    fun onIgnitionOn() {
        cancel()
        active = true
        val rules = IntegratedSettings.bootRules(app)
            .filter { it.enabled && it.packageName.isNotBlank() }
            .sortedBy { it.delaySeconds }
        NextLogger.i("BOOT_AUTO", "rules=" + rules.size)
        rules.forEach { rule ->
            schedule(rule.delaySeconds) {
                if (!active) return@schedule
                launch(rule)
                if (rule.mediaPlay) {
                    schedule(rule.mediaDelaySeconds) {
                        if (active) playMedia(rule)
                    }
                }
            }
        }
    }

    fun onIgnitionOff() {
        active = false
        cancel()
    }

    fun testNow() = onIgnitionOn()

    private fun launch(rule: BootAppRule) {
        val pkg = rule.packageName
        var launched = false
        if (NextAdb.isPortOpen()) {
            launched = NextAdb.shell(
                app,
                "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1"
            ).success
        }
        if (!launched) {
            launched = runCatching {
                val intent = app.packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?: return@runCatching false
                app.startActivity(intent)
                true
            }.getOrDefault(false)
        }
        NextLogger.i("BOOT_AUTO", "launch " + rule.label + " pkg=" + pkg + " ok=" + launched)
    }

    private fun playMedia(rule: BootAppRule) {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
        audio.dispatchMediaKeyEvent(eventDown)
        audio.dispatchMediaKeyEvent(eventUp)
        NextLogger.i("BOOT_AUTO", "media PLAY sent for " + rule.label)
    }

    private fun schedule(seconds: Double, action: () -> Unit) {
        val r = Runnable(action)
        pending += r
        handler.postDelayed(r, (seconds.coerceIn(0.0, 600.0) * 1000.0).toLong())
    }

    private fun cancel() {
        pending.forEach(handler::removeCallbacks)
        pending.clear()
    }
}
