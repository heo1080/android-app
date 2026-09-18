package com.byd.dolphin.autoassistant.next.automation

import android.content.Context
import android.os.Handler
import android.os.Looper
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

                if (rule.mediaPlay) {
                    prepareBackgroundMedia(rule)
                    schedule(rule.mediaDelaySeconds) {
                        if (active) playBackgroundMedia(rule)
                    }
                } else {
                    launchForeground(rule)
                }
            }
        }
    }

    fun onIgnitionOff() {
        active = false
        cancel()
        BackgroundMediaPlaybackController.release()
    }

    fun testNow() = onIgnitionOn()

    private fun launchForeground(rule: BootAppRule) {
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
        NextLogger.i(
            "BOOT_AUTO",
            "foreground launch label=" + rule.label + " pkg=" + pkg + " ok=" + launched
        )
    }

    private fun prepareBackgroundMedia(rule: BootAppRule) {
        NextLogger.i(
            "BOOT_AUTO",
            "media background prepare label=" + rule.label +
                " pkg=" + rule.packageName +
                " playDelay=" + rule.mediaDelaySeconds
        )
        BackgroundMediaPlaybackController.prepare(app, rule.packageName) { result ->
            NextLogger.i(
                "BOOT_AUTO",
                "media prepare label=" + rule.label +
                    " success=" + result.success +
                    " stage=" + result.stage +
                    " detail=" + result.detail
            )
        }
    }

    private fun playBackgroundMedia(rule: BootAppRule) {
        BackgroundMediaPlaybackController.play(app, rule.packageName) { result ->
            NextLogger.i(
                "BOOT_AUTO",
                "media background PLAY label=" + rule.label +
                    " pkg=" + rule.packageName +
                    " success=" + result.success +
                    " stage=" + result.stage +
                    " detail=" + result.detail
            )
        }
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
