package com.byd.dolphin.autoassistant.hud

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.byd.dolphin.autoassistant.util.DolphinLogger

class MultiNavNotificationListener : NotificationListenerService() {
    private val activeGuidanceNotifications = mutableSetOf<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val channelId = sbn.notification?.channelId ?: ""

        if (NavGuidanceParser.isNavApp(pkg, channelId)) {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            val text = extras.getCharSequence("android.text")?.toString().orEmpty()
            val subText = extras.getCharSequence("android.subText")?.toString().orEmpty()

            DolphinLogger.logNavigationNotification(this, "MULTI_NAV", pkg, title, text, subText)
            if (NavGuidanceParser.parseAndForward(this, pkg, title, text, subText)) {
                synchronized(activeGuidanceNotifications) {
                    activeGuidanceNotifications += notificationKey(sbn)
                }
            }
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

    private fun notificationKey(sbn: StatusBarNotification): String = sbn.key
}
