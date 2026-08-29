package com.byd.dolphin.autoassistant.hud

import android.content.Context
import android.content.Intent
import android.util.Log

object ClusterTbtBridge {

    private const val TAG = "ClusterTbtBridge"

    // BYD 순정 계기판 TBT 표준 브로드캐스트 액션
    private const val ACTION_BYD_CLUSTER_TBT = "com.byd.auto.navi.action.TBT_INFO"
    private const val ACTION_BYD_NAVI_STATUS = "com.byd.auto.intent.action.NAVI_INFO"

    /**
     * TMAP에서 추출한 내비게이션 데이터를 BYD 순정 계기판 내부 프로토콜로 전송
     * 계기판 자체 벡터 화살표, 폰트, 거리 카운터가 순정 그대로 렌더링됨
     */
    fun sendTbtToCluster(
        context: Context,
        turnType: Int,
        turnDistanceMeters: Int,
        distanceStr: String,
        speedLimit: Int,
        nextRoadName: String,
        isNavigating: Boolean = true
    ) {
        val intent = Intent(ACTION_BYD_CLUSTER_TBT).apply {
            putExtra("is_navigating", isNavigating)
            putExtra("turn_type", turnType) // 1:직진, 2:좌회전, 3:우회전, 4:유턴, 7:지하차도, 8:고가, 9:고속도로진입 등
            putExtra("turn_distance", turnDistanceMeters)
            putExtra("turn_distance_str", distanceStr)
            putExtra("speed_limit", speedLimit)
            putExtra("next_road_name", nextRoadName)
        }
        context.sendBroadcast(intent)

        // 보조 인텐트 채널 동시 전송
        val subIntent = Intent(ACTION_BYD_NAVI_STATUS).apply {
            putExtra("navi_state", if (isNavigating) 1 else 0)
            putExtra("turn_icon", turnType)
            putExtra("distance", turnDistanceMeters)
            putExtra("limit_speed", speedLimit)
        }
        context.sendBroadcast(subIntent)

        Log.d(TAG, "Sent TBT to Cluster: turnType=$turnType, dist=${turnDistanceMeters}m, limit=$speedLimit")
    }

    // 내비 종료 시 계기판 TBT 영역 클리어
    fun clearClusterTbt(context: Context) {
        val intent = Intent(ACTION_BYD_CLUSTER_TBT).apply {
            putExtra("is_navigating", false)
            putExtra("turn_type", 0)
            putExtra("turn_distance", 0)
            putExtra("speed_limit", 0)
        }
        context.sendBroadcast(intent)
    }
}
