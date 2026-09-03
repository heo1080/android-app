package com.byd.dolphin.autoassistant.hud

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.widget.Toast
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * 2번 요구사항:
 * 미러링 및 계기판 디스플레이 제어 매니저 (test 버전)
 * 2-1. 5대 내비게이션(BYD TMAP, 모바일 TMAP, 네이버지도, 카카오내비, 아이나비 에어) TBT 계기판 표출
 * 2-2. 선택한 앱만 계기판 디스플레이(Display 1)로 화면 보내기
 * 2-3. 메인 디스플레이 화면 계기판 디스플레이로 미러링
 */
object ClusterMirrorManager {

    private const val TAG = "ClusterMirrorManager"

    // BYD 순정 계기판 TBT 브로드캐스트 액션
    private const val ACTION_BYD_CLUSTER_TBT = "com.byd.auto.navi.action.TBT_INFO"
    private const val ACTION_BYD_NAVI_STATUS = "com.byd.auto.intent.action.NAVI_INFO"

    // 2-1. 5대 내비게이션 안내 정보를 BYD 순정 5인치 계기판 디스플레이로 전송
    fun sendTbtToCluster(
        context: Context,
        turnType: Int,
        turnDistanceMeters: Int,
        distanceStr: String,
        speedLimit: Int,
        nextRoadName: String,
        isNavigating: Boolean = true
    ) {
        try {
            val intent = Intent(ACTION_BYD_CLUSTER_TBT).apply {
                putExtra("is_navigating", isNavigating)
                putExtra("turn_type", turnType)
                putExtra("turn_distance", turnDistanceMeters)
                putExtra("turn_distance_str", distanceStr)
                putExtra("speed_limit", speedLimit)
                putExtra("next_road_name", nextRoadName)
            }
            context.sendBroadcast(intent)

            val subIntent = Intent(ACTION_BYD_NAVI_STATUS).apply {
                putExtra("navi_state", if (isNavigating) 1 else 0)
                putExtra("turn_icon", turnType)
                putExtra("distance", turnDistanceMeters)
                putExtra("limit_speed", speedLimit)
            }
            context.sendBroadcast(subIntent)

            DolphinLogger.i(TAG, "[계기판 TBT 전송] turnType=$turnType, dist=${turnDistanceMeters}m, limit=$speedLimit, road=$nextRoadName")
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "계기판 TBT 전송 실패", e)
        }
    }

    fun clearClusterTbt(context: Context) {
        try {
            val intent = Intent(ACTION_BYD_CLUSTER_TBT).apply {
                putExtra("is_navigating", false)
                putExtra("turn_type", 0)
                putExtra("turn_distance", 0)
                putExtra("speed_limit", 0)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "계기판 TBT 클리어 실패", e)
        }
    }

    // 2-2. 선택한 앱을 보조 디스플레이(계기판 5인치, Display ID: 1)로 화면 보내기
    fun launchAppOnClusterDisplay(context: Context, packageName: String): Boolean {
        DolphinLogger.i(TAG, "계기판 디스플레이로 앱 화면 전송 요청: $packageName")
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val displays = dm.displays
            var clusterDisplay: Display? = null

            for (d in displays) {
                if (d.displayId == 1 || d.name.contains("cluster", ignoreCase = true) || d.name.contains("secondary", ignoreCase = true)) {
                    clusterDisplay = d
                    break
                }
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }

            if (launchIntent != null) {
                val targetDisplayId = clusterDisplay?.displayId ?: 1
                val options = ActivityOptions.makeBasic().apply {
                    setLaunchDisplayId(targetDisplayId)
                }
                context.startActivity(launchIntent, options.toBundle())
                Toast.makeText(context, "계기판 디스플레이(Display $targetDisplayId)로 실행했습니다.", Toast.LENGTH_SHORT).show()
                true
            } else {
                // 쉘을 통한 am start fallback
                val cmd = "am start -n $(cmd package resolve-activity --brief $packageName | tail -n 1) --display 1"
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                Toast.makeText(context, "계기판 화면(Display 1)으로 쉘 전송 완료", Toast.LENGTH_SHORT).show()
                true
            }
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "계기판 디스플레이 앱 실행 실패", e)
            Toast.makeText(context, "계기판 화면 전송 오류: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    // 2-3. 메인 디스플레이 화면을 계기판 디스플레이로 미러링 (Presentation 테스트 모드)
    fun toggleMainToClusterMirroring(context: Context, enable: Boolean) {
        DolphinLogger.i(TAG, "메인 화면 -> 계기판 미러링 전환: $enable")
        try {
            val cmd = if (enable) {
                "am broadcast -a com.byd.auto.action.CLUSTER_PROJECTION --ei state 1"
            } else {
                "am broadcast -a com.byd.auto.action.CLUSTER_PROJECTION --ei state 0"
            }
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val msg = if (enable) "계기판 화면 미러링 테스트 시작" else "계기판 화면 미러링 종료"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "계기판 미러링 실패", e)
        }
    }
}
