package com.byd.dolphin.autoassistant.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.view.accessibility.AccessibilityEvent
import com.byd.dolphin.autoassistant.hud.NavGuidanceParser
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * BYD DiLink 순정 보안상 '알림 청취 권한(NotificationListenerService)' 설정 화면이 차단된 경우,
 * 접근성 서비스(AccessibilityService)를 통해 5대 내비게이션의 알림 및 화면 TBT 정보를 100% 가로채어
 * 계기판과 HUD로 연동하는 스마트 우회 브릿지 서비스
 */
class DolphinAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return

        // 1. 알림 변경 감지 (Notification Listener 완전 대체)
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (NavGuidanceParser.isNavApp(pkg)) {
                val parcelable = event.parcelableData
                if (parcelable is Notification) {
                    val extras = parcelable.extras
                    val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
                    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                    val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

                    DolphinLogger.i("ACCESSIBILITY_NAV", "접근성 서비스 알림 수신: pkg=$pkg, title='$title', text='$text'")
                    NavGuidanceParser.parseAndForward(this, pkg, title, text, subText)
                }
            }
        }
    }

    override fun onInterrupt() {
        DolphinLogger.w("ACCESSIBILITY", "접근성 서비스 중단됨")
    }
}
