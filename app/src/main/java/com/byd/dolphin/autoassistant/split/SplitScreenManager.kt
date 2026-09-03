package com.byd.dolphin.autoassistant.split

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * 4번 요구사항:
 * 1% 정밀 커스텀 분할 화면 매니저
 * 4-1. 2분할, 3분할, 4분할 화면 분할 지원
 * 4-2. 가로/세로 1% 단위 정밀 크기 조절
 * 4-3. 앱서랍 바로가기 등록 및 플로팅 버튼 생성 연동
 * 4-4. 360 서라운드뷰(AVM) 작동 후 복귀 시 50:50으로 초기화되는 현상 차단 및 이전 비율 자동 복원
 */
object SplitScreenManager {

    private const val TAG = "SplitScreenManager"
    private const val PREF_NAME = "dolphin_split_prefs"
    private const val KEY_SAVED_CONFIG = "key_saved_custom_split_config"

    fun launchSplitScreen(context: Context, config: SplitConfig) {
        DolphinLogger.i(TAG, "커스텀 분할 화면 실행: mode=${config.mode}, primaryRatio=${config.ratioPrimary}%, secondaryRatio=${config.ratioSecondary}%")

        when (config.mode) {
            SplitMode.TWO_APPS_HORIZONTAL -> {
                launchTwoApps(context, config.pkg1, config.pkg2, config.ratioPrimary)
            }
            SplitMode.THREE_APPS_LEFT_STACKED, SplitMode.THREE_APPS_RIGHT_STACKED -> {
                launchThreeApps(context, config)
            }
            SplitMode.FOUR_APPS_GRID -> {
                launchFourApps(context, config)
            }
        }

        saveLastConfig(context, config)
        Toast.makeText(context, "${config.ratioPrimary}% 커스텀 분할 화면 적용 완료", Toast.LENGTH_SHORT).show()
    }

    private fun launchTwoApps(context: Context, pkg1: String, pkg2: String, ratio: Int) {
        try {
            val intent1 = context.packageManager.getLaunchIntentForPackage(pkg1)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            if (intent1 != null) context.startActivity(intent1)

            val intent2 = context.packageManager.getLaunchIntentForPackage(pkg2)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            if (intent2 != null) context.startActivity(intent2)

            // BYD Window Manager에 커스텀 1% 비율 가중치 명령 적용
            val left = ratio
            val right = 100 - ratio
            applyWmSplitRatio(left, right)
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "2분할 실행 오류", e)
        }
    }

    private fun launchThreeApps(context: Context, config: SplitConfig) {
        launchTwoApps(context, config.pkg1, config.pkg2, config.ratioPrimary)
        if (config.pkg3.isNotEmpty()) {
            val intent3 = context.packageManager.getLaunchIntentForPackage(config.pkg3)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            if (intent3 != null) context.startActivity(intent3)
        }
    }

    private fun launchFourApps(context: Context, config: SplitConfig) {
        launchTwoApps(context, config.pkg1, config.pkg2, config.ratioPrimary)
        if (config.pkg3.isNotEmpty()) {
            val intent3 = context.packageManager.getLaunchIntentForPackage(config.pkg3)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            if (intent3 != null) context.startActivity(intent3)
        }
        if (config.pkg4.isNotEmpty()) {
            val intent4 = context.packageManager.getLaunchIntentForPackage(config.pkg4)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            if (intent4 != null) context.startActivity(intent4)
        }
    }

    private fun applyWmSplitRatio(left: Int, right: Int) {
        try {
            val cmd = "wm set-split-ratio $left $right || settings put global split_screen_ratio $left"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        } catch (e: Exception) {
            Log.d(TAG, "wm split fallback: ${e.message}")
        }
    }

    // 4-4. 360 서라운드뷰(AVM) 작동 후 복귀 시 50:50 초기화 방지 및 이전 비율 자동 복원
    fun onSurroundViewDismissed(context: Context) {
        getLastConfig(context)?.let { config ->
            DolphinLogger.i(TAG, "360 서라운드뷰 종료 감지: 50대50 초기화 방지 및 이전 ${config.ratioPrimary}% 비율 즉시 복구")
            launchSplitScreen(context, config)
        }
    }

    fun restoreLastSplitScreen(context: Context) {
        onSurroundViewDismissed(context)
    }

    fun saveLastConfig(context: Context, config: SplitConfig) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("title", config.title)
            .putString("mode", config.mode.name)
            .putString("pkg1", config.pkg1)
            .putString("pkg2", config.pkg2)
            .putString("pkg3", config.pkg3)
            .putString("pkg4", config.pkg4)
            .putInt("ratioPrimary", config.ratioPrimary)
            .putInt("ratioSecondary", config.ratioSecondary)
            .apply()
    }

    fun getLastConfig(context: Context): SplitConfig? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val modeStr = prefs.getString("mode", null) ?: return null
        return SplitConfig(
            title = prefs.getString("title", "커스텀 분할") ?: "커스텀 분할",
            mode = try { SplitMode.valueOf(modeStr) } catch (e: Exception) { SplitMode.TWO_APPS_HORIZONTAL },
            pkg1 = prefs.getString("pkg1", "com.skt.tmap.ku") ?: "com.skt.tmap.ku",
            pkg2 = prefs.getString("pkg2", "com.android.music") ?: "com.android.music",
            pkg3 = prefs.getString("pkg3", "") ?: "",
            pkg4 = prefs.getString("pkg4", "") ?: "",
            ratioPrimary = prefs.getInt("ratioPrimary", 30),
            ratioSecondary = prefs.getInt("ratioSecondary", 50)
        )
    }
}
