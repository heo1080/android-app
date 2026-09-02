package com.byd.dolphin.autoassistant.hud

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

class TmapNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val channelId = sbn.notification?.channelId ?: ""

        val isTmap = pkg.contains("tmap", ignoreCase = true) ||
                     pkg.contains("skt", ignoreCase = true) ||
                     pkg == "com.skt.tmap.ku" ||
                     pkg == "com.tmap.auto.byd" ||
                     pkg == "com.skt.tmap.auto" ||
                     pkg == "com.skt.tmap.byd" ||
                     pkg == "com.skt.tmap.oem" ||
                     pkg == "com.tmapmobility.tmap.autonavi" ||
                     channelId == "noti_tmap_drive_content_channel"

        if (isTmap) {
            val extras = sbn.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getString("android.text") ?: ""
            val subText = extras.getString("android.subText") ?: ""

            DolphinLogger.i("TMAP_TBT", "Intercepted: pkg=$pkg, id=${sbn.id}, title='$title', text='$text', subText='$subText'")
            parseAndForwardNavigation(title, text, subText)
        }
    }

    private fun parseAndForwardNavigation(title: String, text: String, subText: String) {
        val combined = "$title $text $subText"

        // 1. 턴 거리 파싱 (m / km)
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

        DolphinLogger.i("TMAP_PARSED", "turnType=$turnType, dist=${turnDistance}m, limit=${speedLimit}km/h")

        // A. 순정 5인치 계기판으로 순정 TBT 전송
        if (SettingsManager.isClusterTbtEnabled(this)) {
            ClusterTbtBridge.sendTbtToCluster(
                context = this,
                turnType = turnType,
                turnDistanceMeters = turnDistance,
                distanceStr = distanceStr,
                speedLimit = speedLimit,
                nextRoadName = title,
                isNavigating = true
            )
        }

        // B. T900 HUD 기기로 전송
        if (SettingsManager.isHudBridgeEnabled(this)) {
            val packet = T900Protocol.buildNavigationFrame(
                currentSpeed = 0,
                speedLimit = speedLimit,
                cameraDistance = if (speedLimit > 0) turnDistance else 0,
                turnType = turnType,
                turnDistance = turnDistance
            )
            T900BluetoothManager.sendPacket(packet)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg.contains("tmap", ignoreCase = true) || pkg == "com.tmap.auto.byd") {
            DolphinLogger.i("TMAP_TBT", "Tmap notification removed: clearing cluster TBT")
            ClusterTbtBridge.clearClusterTbt(this)
        }
    }
}
