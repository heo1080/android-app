package com.byd.dolphin.autoassistant.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.view.accessibility.AccessibilityEvent
import com.byd.dolphin.autoassistant.hud.NavGuidanceParser
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * 사용자가 명시적으로 활성화할 수 있는 보조 내비 알림 브릿지입니다.
 * 접근성 이벤트에 Notification 객체가 포함된 경우만 best-effort로 TBT 텍스트를 읽습니다.
 */
class DolphinAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return

        // NotificationListener를 대체한다고 보장할 수 없는 보조 경로입니다.
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (NavGuidanceParser.isNavApp(pkg)) {
                val parcelable = event.parcelableData
                if (parcelable is Notification) {
                    val extras = parcelable.extras
                    val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
                    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                    val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

                    DolphinLogger.logNavigationNotification(
                        this,
                        "ACCESSIBILITY_NAV",
                        pkg,
                        title,
                        text,
                        subText
                    )
                    NavGuidanceParser.parseAndForward(this, pkg, title, text, subText)
                }
            }
        }
    }

    override fun onInterrupt() {
        DolphinLogger.w("ACCESSIBILITY", "접근성 서비스 중단됨")
    }
}
