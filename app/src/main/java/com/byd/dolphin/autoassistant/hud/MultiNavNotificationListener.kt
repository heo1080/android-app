package com.byd.dolphin.autoassistant.hud

import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.byd.dolphin.autoassistant.util.DolphinLogger

class MultiNavNotificationListener : NotificationListenerService() {
    private val activeGuidanceNotifications = mutableSetOf<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val notification = sbn.notification ?: return
        val channelId = notification.channelId ?: ""
        if (!NavGuidanceParser.isNavApp(pkg, channelId)) return

        val extras = notification.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty()
        val richText = collectText(extras, notification.tickerText?.toString())
        val keyTypes = extras.keySet().sorted().joinToString(limit = 30) { key ->
            "$key:${extras.get(key)?.javaClass?.simpleName ?: "null"}"
        }
        DolphinLogger.i("MULTI_NAV", "pkg=$pkg channel=$channelId extras=[$keyTypes]")
        DolphinLogger.logNavigationNotification(this, "MULTI_NAV", pkg, title, richText, subText)
        if (NavGuidanceParser.parseAndForward(this, pkg, title, richText, subText)) {
            synchronized(activeGuidanceNotifications) { activeGuidanceNotifications += notificationKey(sbn) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (NavGuidanceParser.isNavApp(pkg)) {
            val shouldClear = synchronized(activeGuidanceNotifications) {
                val removed = activeGuidanceNotifications.remove(notificationKey(sbn))
                removed && activeGuidanceNotifications.isEmpty()
            }
            if (shouldClear) {
                DolphinLogger.i("MULTI_NAV", "활성 길안내 알림이 없어 계기판 TBT 종료")
                NavGuidanceParser.clear(this)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun collectText(extras: Bundle, ticker: String?): String {
        val out = linkedSetOf<String>()
        ticker?.takeIf { it.isNotBlank() }?.let(out::add)
        extras.keySet().forEach { key ->
            when (val value = extras.get(key)) {
                is CharSequence -> value.toString().takeIf { it.isNotBlank() }?.let(out::add)
                is Array<*> -> value.filterIsInstance<CharSequence>().forEach { cs -> if (cs.isNotBlank()) out += cs.toString() }
                is Iterable<*> -> value.filterIsInstance<CharSequence>().forEach { cs -> if (cs.isNotBlank()) out += cs.toString() }
            }
        }
        return out.joinToString(" | ").take(2_000)
    }

    private fun notificationKey(sbn: StatusBarNotification): String = sbn.key
}
