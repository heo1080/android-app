package com.byd.dolphin.autoassistant.hud

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * 2-1 및 3-4 요구사항:
 * 5대 내비게이션(BYD 순정 TMAP, 모바일 TMAP, 네이버지도, 카카오내비, 아이나비 에어) 통합 알림 리스너
 * - HUD T900 (huddata 시각 데이터 + hudaudio 전용 스피커 안내) 동시 전송
 * - 순정 5인치 계기판 디스플레이 TBT 동시 표출
 */
class MultiNavNotificationListener : NotificationListenerService() {

    companion object {
        val SUPPORTED_NAV_PACKAGES = setOf(
            // 1. BYD 순정 내비게이션 (TMAP Auto)
            "com.tmap.auto.byd",
            "com.skt.tmap.byd",
            "com.skt.tmap.auto",
            "com.skt.tmap.oem",
            "com.tmapmobility.tmap.autonavi",
            // 2. 안드로이드 모바일 & 태블릿 TMAP
            "com.skt.tmap.ku",
            "com.skt.skaf.l001mtm091",
            // 3. 네이버지도 (Naver Map)
            "com.nhn.android.nmap",
            // 4. 카카오내비 (Kakao Navi)
            "com.locnall.KimGiSa",
            // 5. 아이나비 에어 (Inavi Air)
            "com.thinkware.inaviair"
        )
    }

    private var lastSpeedLimitAlert = 0
    private var lastAlertDistance = -1

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val channelId = sbn.notification?.channelId ?: ""

        val isNavApp = SUPPORTED_NAV_PACKAGES.contains(pkg) ||
                pkg.contains("tmap", ignoreCase = true) ||
                pkg.contains("kimgisa", ignoreCase = true) ||
                pkg.contains("inavi", ignoreCase = true) ||
                channelId.contains("navi", ignoreCase = true) ||
                channelId == "noti_tmap_drive_content_channel"

        if (isNavApp) {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getString("android.title") ?: ""
            val text = extras.getString("android.text") ?: ""
            val subText = extras.getString("android.subText") ?: ""

            DolphinLogger.i("MULTI_NAV", "알림 수신: pkg=$pkg, title='$title', text='$text', subText='$subText'")
            parseAndForwardGuidance(pkg, title, text, subText)
        }
    }

    private fun parseAndForwardGuidance(pkg: String, title: String, text: String, subText: String) {
        val combined = "$title $text $subText"

        // 1. 남은 턴 거리 파싱 (m / km)
        var turnDistance = 0
        var distanceStr = ""
        val distMatch = Regex("([0-9.]+)\\s*(m|km)", RegexOption.IGNORE_CASE).find(combined)
        if (distMatch != null) {
            val num = distMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = distMatch.groupValues[2].lowercase()
            turnDistance = if (unit == "km") (num * 1000).toInt() else num.toInt()
            distanceStr = distMatch.value
        }

        // 2. 턴 타입 파싱
        var turnType = T900Protocol.TURN_STRAIGHT
        when {
            combined.contains("유턴") -> turnType = T900Protocol.TURN_UTURN
            combined.contains("좌회전") -> turnType = T900Protocol.TURN_LEFT
            combined.contains("우회전") -> turnType = T900Protocol.TURN_RIGHT
            combined.contains("지하차도") -> turnType = T900Protocol.TURN_UNDERPASS
            combined.contains("고가도로") -> turnType = T900Protocol.TURN_OVERPASS
            combined.contains("고속도로 진입") -> turnType = T900Protocol.TURN_HIGHWAY_IN
            combined.contains("출구") || combined.contains("진출") -> turnType = T900Protocol.TURN_HIGHWAY_OUT
        }

        // 3. 과속 제한속도 파싱
        var speedLimit = 0
        val limitMatch = Regex("(?:제한|과속|단속)\\s*([0-9]{2,3})").find(combined)
        if (limitMatch != null) {
            speedLimit = limitMatch.groupValues[1].toIntOrNull() ?: 0
        }

        DolphinLogger.i("MULTI_NAV_PARSED", "[$pkg] turnType=$turnType, dist=${turnDistance}m, limit=${speedLimit}km/h")

        // A. 2-1 요구사항: 순정 5인치 계기판 디스플레이로 TBT 전송
        ClusterMirrorManager.sendTbtToCluster(
            context = this,
            turnType = turnType,
            turnDistanceMeters = turnDistance,
            distanceStr = distanceStr,
            speedLimit = speedLimit,
            nextRoadName = title,
            isNavigating = true
        )

        // B. 3-1 & 3-4 요구사항: T900 HUD로 huddata 시각 데이터 송신
        if (SettingsManager.isHudDataEnabled(this)) {
            val cameraDist = if (speedLimit > 0) turnDistance else 0
            HudDataManager.sendNavigationData(
                context = this,
                currentSpeed = 0,
                speedLimit = speedLimit,
                cameraDistance = cameraDist,
                turnType = turnType,
                turnDistance = turnDistance
            )
        }

        // C. 3-1 요구사항: hudaudio HUD 자체 스피커로 네비게이션 안내 소리만 출력
        if (SettingsManager.isHudAudioEnabled(this)) {
            if (speedLimit > 0 && turnDistance in 1..300 && lastSpeedLimitAlert != speedLimit) {
                lastSpeedLimitAlert = speedLimit
                HudAudioManager.playCameraWarning(this)
            } else if (speedLimit == 0) {
                lastSpeedLimitAlert = 0
            }

            if (turnType != T900Protocol.TURN_STRAIGHT && turnDistance in 200..350 && lastAlertDistance != turnDistance) {
                lastAlertDistance = turnDistance
                HudAudioManager.playTurnChime(this)
            } else if (turnDistance > 400 || turnDistance == 0) {
                lastAlertDistance = -1
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (SUPPORTED_NAV_PACKAGES.contains(pkg) || pkg.contains("tmap", ignoreCase = true)) {
            DolphinLogger.i("MULTI_NAV", "내비 알림 종료 감지: 계기판 및 HUD 상태 초기화")
            ClusterMirrorManager.clearClusterTbt(this)
            lastAlertDistance = -1
            lastSpeedLimitAlert = 0
        }
    }
}
