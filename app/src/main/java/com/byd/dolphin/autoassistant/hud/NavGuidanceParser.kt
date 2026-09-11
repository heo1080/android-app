package com.byd.dolphin.autoassistant.hud

import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
    private var lastLeadingCarAlertAt = 0L

    fun isNavApp(pkg: String, channelId: String = ""): Boolean {
        return SUPPORTED_NAV_PACKAGES.contains(pkg) ||
                pkg.contains("tmap", ignoreCase = true) ||
                pkg.contains("kimgisa", ignoreCase = true) ||
                pkg.contains("inavi", ignoreCase = true) ||
                channelId.contains("navi", ignoreCase = true) ||
                channelId == "noti_tmap_drive_content_channel"
    }

    @Synchronized
    fun parseAndForward(
        context: Context,
        pkg: String,
        title: String,
        text: String,
        subText: String
    ): Boolean {
        val combined = "$title $text $subText"

        var turnDistance = 0
        var distanceStr = ""
        val distMatch = Regex(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(km|m)(?!\\s*/?\\s*h)",
            RegexOption.IGNORE_CASE
        ).find(combined)
        if (distMatch != null) {
            val num = distMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = distMatch.groupValues[2].lowercase()
            val meters = if (unit == "km") num * 1_000.0 else num
            turnDistance = meters.coerceIn(0.0, MAX_GUIDANCE_DISTANCE_METERS.toDouble()).toInt()
            distanceStr = distMatch.value
        }

        var turnType = HudSemanticValues.TURN_STRAIGHT
        when {
            combined.contains("유턴") || combined.contains("U턴", true) -> turnType = HudSemanticValues.TURN_UTURN
            listOf("좌회전", "왼쪽", "좌측").any(combined::contains) -> turnType = HudSemanticValues.TURN_LEFT
            listOf("우회전", "오른쪽", "우측").any(combined::contains) -> turnType = HudSemanticValues.TURN_RIGHT
            combined.contains("직진") -> turnType = HudSemanticValues.TURN_FRONT
        }

        var speedLimit = 0
        val limitMatch = Regex("(?:제한(?:속도)?|과속|단속)\\s*[:：]?\\s*([0-9]{2,3})")
            .find(combined)
        if (limitMatch != null) {
            speedLimit = limitMatch.groupValues[1].toIntOrNull() ?: 0
        }

        DolphinLogger.i("NAV_PARSED", "[$pkg] turnType=$turnType, dist=${turnDistance}m, limit=${speedLimit}km/h")

        // 내비게이션 알림의 전방 차량 출발 문구는 앱 내부 수신기로만 전달합니다.
        if ((combined.contains("앞차") || combined.contains("전방")) && combined.contains("출발")) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLeadingCarAlertAt >= LEADING_CAR_DEDUPE_MS) {
                lastLeadingCarAlertAt = now
                DolphinLogger.i("NAV_LVDA", "내비게이션 알림에서 전방 차량 출발 문구 감지")
                context.sendBroadcast(
                    Intent(ACTION_INTERNAL_LEADING_CAR).setPackage(context.packageName)
                )
            }
        }

        val hasGuidance = turnDistance > 0 || listOf("직진", "좌회전", "우회전", "유턴", "왼쪽", "오른쪽", "좌측", "우측")
            .any(combined::contains)
        if (!hasGuidance) {
            DolphinLogger.d("NAV_PARSED", "길안내 필드가 없어 계기판/HUD 전달 생략: pkg=$pkg")
            return false
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

        // 2. 티맵 Plus HUD 시각 데이터 송신(프로토콜 확인 전에는 내부에서 차단)
        if (SettingsManager.isHudBridgeEnabled(context) && SettingsManager.isHudDataEnabled(context)) {
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

        // 3. 티맵 Plus HUD 오디오 경고음(프로토콜 확인 전에는 내부에서 차단)
        if (SettingsManager.isHudBridgeEnabled(context) && SettingsManager.isHudAudioEnabled(context)) {
            if (speedLimit > 0 && turnDistance in 1..300 && lastSpeedLimitAlert != speedLimit) {
                lastSpeedLimitAlert = speedLimit
                HudAudioManager.playCameraWarning(context)
            } else if (speedLimit == 0) {
                lastSpeedLimitAlert = 0
            }

            if (turnType != HudSemanticValues.TURN_STRAIGHT && turnDistance in 200..350 && lastAlertDistance != turnDistance) {
                lastAlertDistance = turnDistance
                HudAudioManager.playTurnChime(context)
            } else if (turnDistance > 400 || turnDistance == 0) {
                lastAlertDistance = -1
            }
        }
        return true
    }

    @Synchronized
    fun clear(context: Context) {
        ClusterMirrorManager.clearClusterTbt(context)
        lastAlertDistance = -1
        lastSpeedLimitAlert = 0
        lastLeadingCarAlertAt = 0L
    }

    const val ACTION_INTERNAL_LEADING_CAR =
        "com.byd.dolphin.autoassistant.action.NAV_LEADING_CAR"
    private const val LEADING_CAR_DEDUPE_MS = 15_000L
    private const val MAX_GUIDANCE_DISTANCE_METERS = 16_777_214
}
