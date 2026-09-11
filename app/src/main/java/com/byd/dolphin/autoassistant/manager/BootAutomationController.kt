package com.byd.dolphin.autoassistant.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.byd.dolphin.autoassistant.hud.MultiNavNotificationListener
import com.byd.dolphin.autoassistant.util.DolphinLogger

/** Executes the per-app launch and playback plan exactly once per ignition cycle. */
class BootAutomationController(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Runnable>()

    @Volatile
    private var cycleActive = false

    fun onIgnitionOn() {
        cancelPending("새 시동 주기 초기화")
        if (!SettingsManager.isBootAutoEnabled(appContext)) {
            DolphinLogger.i(TAG, "시동 자동 실행이 꺼져 있음")
            return
        }

        cycleActive = true
        val items = SettingsManager.getBootAppList(appContext)
            .filter { it.enabled && it.packageName.isNotBlank() }
            .sortedBy { it.delaySeconds }

        DolphinLogger.i(TAG, "시동 자동 실행 시작: ${items.size}개 앱")
        items.forEach { item ->
            postAt(item.delaySeconds) {
                if (!cycleActive) return@postAt
                launch(item)
                if (item.mediaPlayEnabled) {
                    postAt(item.mediaDelaySeconds) {
                        if (cycleActive) play(item.packageName, item.appName)
                    }
                }
            }
        }
    }

    fun onIgnitionOff() {
        cancelPending("시동 OFF")
    }

    fun destroy() {
        cancelPending("서비스 종료")
    }

    private fun postAt(seconds: Double, action: () -> Unit) {
        val runnable = Runnable(action)
        pending += runnable
        handler.postDelayed(runnable, (seconds.coerceIn(0.0, 600.0) * 1_000.0).toLong())
    }

    private fun launch(item: BootAppItem) {
        val pkg = item.packageName.trim()
        if (!pkg.matches(Regex("[A-Za-z0-9._]+"))) {
            DolphinLogger.w(TAG, "잘못된 패키지명으로 실행 생략: $pkg")
            return
        }

        // BYD Android 10 can silently reject background startActivity(). The local
        // vehicle ADB endpoint is already authorized by this app, so use a shell
        // launcher first; it is not subject to the background-activity restriction.
        if (NativeAdbClient.isPortOpen()) {
            val shell = NativeAdbClient.executeShell(
                appContext,
                "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
            )
            if (shell.success) {
                DolphinLogger.i(TAG, "ADB 앱 실행 성공: ${item.appName}, 시동 +${item.delaySeconds}초")
                return
            }
            DolphinLogger.w(TAG, "ADB 실행 실패, Android API fallback: ${item.appName} (${shell.message})")
        }

        try {
            val intent = appContext.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                DolphinLogger.w(TAG, "실행 인텐트 없음: ${item.appName} ($pkg)")
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            appContext.startActivity(intent)
            DolphinLogger.i(TAG, "Android API 앱 실행 요청: ${item.appName}, 시동 +${item.delaySeconds}초")
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "앱 실행 실패: ${item.appName}", e)
        }
    }

    private fun play(packageName: String, appName: String) {
        try {
            val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = ComponentName(appContext, MultiNavNotificationListener::class.java)
            val controller = manager.getActiveSessions(listener).firstOrNull {
                it.packageName == packageName
            }
            if (controller != null) {
                controller.transportControls.play()
                DolphinLogger.i(TAG, "미디어 세션 재생: $appName")
                return
            }
        } catch (e: Exception) {
            DolphinLogger.w(TAG, "미디어 세션 접근 실패($appName): ${e.message}")
        }

        try {
            val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(packageName)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            }
            val up = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(packageName)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            }
            appContext.sendOrderedBroadcast(down, null)
            appContext.sendOrderedBroadcast(up, null)
            DolphinLogger.i(TAG, "대상 앱 미디어 키 fallback 요청: $appName (수신 확인 불가)")
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "미디어 재생 실패: $appName", e)
        }
    }

    private fun cancelPending(reason: String) {
        cycleActive = false
        pending.forEach(handler::removeCallbacks)
        pending.clear()
        DolphinLogger.i(TAG, "예약 동작 취소: $reason")
    }

    companion object {
        private const val TAG = "BOOT_AUTOMATION"
    }
}
