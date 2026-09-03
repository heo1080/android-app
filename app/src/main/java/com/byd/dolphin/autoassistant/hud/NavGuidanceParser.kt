package com.byd.dolphin.autoassistant.hud

import android.content.Context
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

object NavGuidanceParser {

    val SUPPORTED_NAV_PACKAGES = setOf(
        "com.tmap.auto.byd",
        "com.skt.tmap.byd",
        "com.skt.tmap.auto",
        "com.skt.tmap.oem",
        "com.tmapmobility.tmap.autonavi",
        "com.skt.tmap.ku",
        "com.skt.skaf.l001mtm091",
        "com.nhn.android.nmap",
        "com.locnall.KimGiSa",
        "com.thinkware.inaviair"
    )

    private var lastSpeedLimitAlert = 0
    private var lastAlertDistance = -1

    fun isNavApp(pkg: String, channelId: String = ""): Boolean {
        return SUPPORTED_NAV_PACKAGES.contains(pkg) ||
                pkg.contains("tmap", ignoreCase = true) ||
                pkg.contains("kimgisa", ignoreCase = true) ||
                pkg.contains("inavi", ignoreCase = true) ||
                channelId.contains("navi", ignoreCase = true) ||
                channelId == "noti_tmap_drive_content_channel"
    }

    fun parseAndForward(context: Context, pkg: String, title: String, text: String, subText: String) {
        val combined = "$title $text $subText"

        var turnDistance = 0
        var distanceStr = ""
        val distMatch = Regex("([0-9.]+)\\s*(m|km)", RegexOption.IGNORE_CASE).find(combined)
        if (distMatch != null) {
            val num = distMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = distMatch.groupValues[2].lowercase()
            turnDistance = if (unit == "km") (num * 1000).toInt() else num.toInt()
            distanceStr = distMatch.value
        }

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

        var speedLimit = 0
        val limitMatch = Regex("(?:제한|과속|단속)\\s*([0-9]{2,3})").find(combined)
        if (limitMatch != null) {
            speedLimit = limitMatch.groupValues[1].toIntOrNull() ?: 0
        }

        DolphinLogger.i("NAV_PARSED", "[$pkg] turnType=$turnType, dist=${turnDistance}m, limit=${speedLimit}km/h")

        
        // 4. 내비게이션 앱의 전방 차량 출발 알림 감지
        if ((combined.contains("앞차") || combined.contains("전방")) && combined.contains("출발")) {
            DolphinLogger.i("NAV_LVDA", "내비게이션 알림에서 전방 차량 출발 감지 -> 음성 출력")
            val voice = com.byd.dolphin.autoassistant.manager.VoiceAndSoundManager(context)
            voice.speakLeadingCarDeparture()
        }

        // 1. 계기판 디스플레이 TBT 전송
        if (SettingsManager.isClusterTbtEnabled(context)) {
            ClusterMirrorManager.sendTbtToCluster(
                context = context,
                turnType = turnType,
                turnDistanceMeters = turnDistance,
                distanceStr = distanceStr,
                speedLimit = speedLimit,
                nextRoadName = title,
                isNavigating = true
            )
        }

        // 2. T900 HUD 시각 데이터 송신
        if (SettingsManager.isHudDataEnabled(context)) {
            val cameraDist = if (speedLimit > 0) turnDistance else 0
            HudDataManager.sendNavigationData(
                context = context,
                currentSpeed = 0,
                speedLimit = speedLimit,
                cameraDistance = cameraDist,
                turnType = turnType,
                turnDistance = turnDistance
            )
        }

        // 3. T900 HUD 오디오 경고음
        if (SettingsManager.isHudAudioEnabled(context)) {
            if (speedLimit > 0 && turnDistance in 1..300 && lastSpeedLimitAlert != speedLimit) {
                lastSpeedLimitAlert = speedLimit
                HudAudioManager.playCameraWarning(context)
            } else if (speedLimit == 0) {
                lastSpeedLimitAlert = 0
            }

            if (turnType != T900Protocol.TURN_STRAIGHT && turnDistance in 200..350 && lastAlertDistance != turnDistance) {
                lastAlertDistance = turnDistance
                HudAudioManager.playTurnChime(context)
            } else if (turnDistance > 400 || turnDistance == 0) {
                lastAlertDistance = -1
            }
        }
    }

    fun clear(context: Context) {
        ClusterMirrorManager.clearClusterTbt(context)
        lastAlertDistance = -1
        lastSpeedLimitAlert = 0
    }
}
