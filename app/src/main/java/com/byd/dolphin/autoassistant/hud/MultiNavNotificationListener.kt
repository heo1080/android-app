package com.byd.dolphin.autoassistant.hud

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.byd.dolphin.autoassistant.util.DolphinLogger

class MultiNavNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val channelId = sbn.notification?.channelId ?: ""

        if (NavGuidanceParser.isNavApp(pkg, channelId)) {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getString("android.title") ?: ""
            val text = extras.getString("android.text") ?: ""
            val subText = extras.getString("android.subText") ?: ""

            DolphinLogger.i("MULTI_NAV", "NotificationListener 수신: pkg=$pkg, title='$title', text='$text'")
            NavGuidanceParser.parseAndForward(this, pkg, title, text, subText)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (NavGuidanceParser.isNavApp(pkg)) {
            DolphinLogger.i("MULTI_NAV", "내비 알림 종료 감지")
            NavGuidanceParser.clear(this)
        }
    }
}
