package com.byd.dolphin.autoassistant.next.permissions

import android.content.ComponentName
import android.content.Context
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.hud.NextNavNotificationListener
import com.byd.dolphin.autoassistant.next.system.NextAdb

data class NotificationAccessResult(
    val enabled: Boolean,
    val changed: Boolean,
    val detail: String
)

object NotificationAccessBridge {
    fun status(context: Context): NotificationAccessResult {
        val app = context.applicationContext
        val component = ComponentName(app, NextNavNotificationListener::class.java)
            .flattenToString()

        if (!NextAdb.isPortOpen()) {
            val frameworkEnabled = runCatching {
                val flat = android.provider.Settings.Secure.getString(
                    app.contentResolver,
                    "enabled_notification_listeners"
                ).orEmpty()
                flat.contains(app.packageName)
            }.getOrDefault(false)

            return NotificationAccessResult(
                frameworkEnabled,
                false,
                if (frameworkEnabled) {
                    "notification listener enabled · local ADB unavailable"
                } else {
                    "notification listener disabled/unknown · local ADB unavailable"
                }
            )
        }

        val secure = NextAdb.shell(
            app,
            "settings get secure enabled_notification_listeners"
        )
        val before = secure.output.contains(app.packageName)

        return NotificationAccessResult(
            before,
            false,
            "component=" + component +
                " enabled=" + before +
                " raw=" + secure.output.replace("\n", " ").take(500)
        )
    }

    fun ensure(context: Context): NotificationAccessResult {
        val app = context.applicationContext
        val component = ComponentName(app, NextNavNotificationListener::class.java)
            .flattenToString()

        val current = status(app)
        if (current.enabled) {
            NextLogger.i("NOTIFICATION_ACCESS", "already enabled " + component)
            return current
        }

        if (!NextAdb.isPortOpen()) {
            NextLogger.w(
                "NOTIFICATION_ACCESS",
                "cannot enable: local ADB unavailable"
            )
            return current
        }

        val commands = listOf(
            "cmd notification allow_listener " + shellQuote(component),
            "cmd notification allow_listener " + shellQuote(component) + " 0"
        )

        var changed = false
        val trace = StringBuilder()
        commands.forEach { cmd ->
            val r = NextAdb.shell(app, cmd)
            trace.append("cmd=")
                .append(cmd)
                .append(" exit=")
                .append(r.exitCode)
                .append(" out=")
                .append(r.output.replace("\n", " ").take(400))
                .append(" msg=")
                .append(r.message)
                .append(" | ")
            if (r.success) changed = true

            val after = status(app)
            if (after.enabled) {
                val result = NotificationAccessResult(
                    true,
                    true,
                    "enabled · " + trace.toString()
                )
                NextLogger.i("NOTIFICATION_ACCESS", result.detail)
                return result
            }
        }

        val after = status(app)
        val result = NotificationAccessResult(
            after.enabled,
            changed && after.enabled,
            "not enabled · " + trace.toString() + " · " + after.detail
        )
        NextLogger.w("NOTIFICATION_ACCESS", result.detail)
        return result
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
