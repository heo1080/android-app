package com.byd.dolphin.autoassistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.byd.dolphin.autoassistant.manager.*
import com.byd.dolphin.autoassistant.split.SplitScreenManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * 돌핀 스마트 어시스턴트 상시 백그라운드 서비스
 * - 0번: 기동 시 자체 ADB 권한 자동 검증
 * - 4번: 360 서라운드뷰 종료 후 1% 커스텀 분할 화면 자동 복원
 * - 5번: 차량 신호 운전석 전용 스피커 안내 (BSD, LDP, 기어, 오토홀드, EPB)
 * - 7번: R/N/D/P 비상등 자동 제어
 * - 8번: 부팅 후 N초 뒤 미디어 자동 재생 및 커스텀 앱 자동 실행
 */
class DolphinService : Service() {

    private lateinit var audioManager: VoiceAndSoundManager
    private lateinit var hazardManager: HazardLightManager
    private val handler = Handler(Looper.getMainLooper())

    private var isBsdActive = false
    private var isTurnSignalOn = false
    private var isCharging = false
    private var previousGear = "P"
    private var previousAutoHoldState: Boolean? = null

    private val vehicleEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val extrasSummary = intent.extras?.let { bundle ->
                bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" }
            } ?: "none"

            DolphinLogger.logIntent(action, extrasSummary)

            when (action) {
                // 4-4. 360 서라운드뷰 및 후진 카메라 종료 감지 -> 50:50 초기화 방지 & 커스텀 비율 복원
                "byd.intent.action.AUTO_VIDEO_ON", "byd.intent.action.pano" -> {
                    val autovideoOn = intent.getIntExtra("autovideo_on", intent.getIntExtra("panoState", -1))
                    if (autovideoOn == 0) {
                        scheduleSplitScreenRestoration(context)
                    }
                }
                "byd.intent.action.AUTO_EXIT_PANO" -> {
                    scheduleSplitScreenRestoration(context)
                }

                // 기어 변속 감지
                "com.byd.auto.intent.action.GEAR_CHANGED" -> {
                    val gearStr = intent.getStringExtra("gear") ?: "P"
                    val speed = intent.getFloatExtra("speed", 0.0f)
                    handleGearChange(context, gearStr, speed)
                }

                // 공조 및 성에제거
                "com.byd.auto.action.AC_DEFROST_CONTROL",
                "com.byd.auto.intent.action.AC_STATUS",
                "byd.intent.action.AC_STATUS",
                "com.byd.auto.action.DEFROST_SWITCH" -> {
                    val frontDefrost = intent.getIntExtra("front_defrost", intent.getIntExtra("state", -1))
                    if (frontDefrost == 1 && context != null) {
                        DefrostManager.onFrontDefrostDetected(context, true)
                    } else if (frontDefrost == 0 && context != null) {
                        DefrostManager.onFrontDefrostDetected(context, false)
                    }
                }

                // 충전 상태
                Intent.ACTION_POWER_CONNECTED, "com.byd.auto.intent.action.CHARGING_STATUS" -> {
                    val chargingNow = intent.getBooleanExtra("is_charging", true)
                    if (SettingsManager.isChargingVoiceEnabled(this@DolphinService) && !isCharging && chargingNow) {
                        isCharging = true
                        audioManager.speakCharging()
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (SettingsManager.isChargingVoiceEnabled(this@DolphinService) && isCharging) {
                        isCharging = false
                        audioManager.speak("충전이 중지되었습니다.")
                    }
                }

                // 오토홀드 / EPB / 자율주행
                "com.byd.auto.intent.action.AUTOHOLD_SWITCH_CHANGED", "com.byd.auto.intent.action.AUTOHOLD_FUNCTION_STATUS" -> {
                    val isEnabled = intent.getBooleanExtra("is_enabled", true)
                    if (previousAutoHoldState != isEnabled) {
                        previousAutoHoldState = isEnabled
                        audioManager.speakAutoHold(isEnabled)
                    }
                }

                "com.byd.auto.intent.action.EPB_STATUS" -> {
                    val isEpbEngaged = intent.getBooleanExtra("is_epb_active", true)
                    audioManager.speakEpb(isEpbEngaged)
                }

                "com.byd.auto.intent.action.ICC_STATUS", "com.byd.auto.intent.action.PILOT_STATUS" -> {
                    val isIccActive = intent.getBooleanExtra("is_icc_active", true)
                    audioManager.speakIcc(isIccActive)
                }

                "com.byd.auto.intent.action.DRIVE_MODE_CHANGED" -> {
                    val mode = intent.getStringExtra("mode") ?: "NORMAL"
                    audioManager.speakDriveMode(mode)
                }

                "com.byd.auto.intent.action.REGEN_MODE_CHANGED" -> {
                    val regen = intent.getStringExtra("regen") ?: "ECO"
                    audioManager.speakRegenMode(regen)
                }

                "com.byd.auto.intent.action.SPEED_CHANGED" -> {
                    val speed = intent.getFloatExtra("speed", 0.0f)
                    hazardManager.onSpeedChanged(speed)
                }

                // 5-3. LDP 차선이탈보조
                "com.byd.auto.intent.action.LANE_DEPARTURE_WARNING" -> {
                    audioManager.playLaneDepartureWarning()
                }

                // 5-2. BSD 사각지대 + 깜박이 연동
                "com.byd.auto.intent.action.BSD_STATUS" -> {
                    isBsdActive = intent.getBooleanExtra("bsd_active", false)
                    checkBsdWithTurnSignal()
                }

                "com.byd.auto.intent.action.TURN_SIGNAL_STATUS" -> {
                    isTurnSignalOn = intent.getBooleanExtra("signal_on", false)
                    checkBsdWithTurnSignal()
                }
            }
        }
    }

    private fun handleGearChange(context: Context?, gearStr: String, speed: Float) {
        val prev = previousGear
        previousGear = gearStr
        DolphinLogger.i("GEAR", "기어 변속: $prev -> $gearStr, 속도=$speed")

        audioManager.speakGear(gearStr)

        // R -> D/N/P 전환 시 후진 카메라 종료 후 분할 화면 복원
        if (prev == "R" && (gearStr == "D" || gearStr == "N" || gearStr == "P")) {
            scheduleSplitScreenRestoration(context)
        }

        val gear = when (gearStr.uppercase()) {
            "R" -> HazardLightManager.Gear.R
            "N" -> HazardLightManager.Gear.N
            "D" -> HazardLightManager.Gear.D
            else -> HazardLightManager.Gear.P
        }
        hazardManager.onGearChanged(gear, speed)
    }

    private fun scheduleSplitScreenRestoration(context: Context?) {
        handler.removeCallbacksAndMessages("RESTORE_SPLIT")
        handler.postDelayed({
            context?.let {
                DolphinLogger.i("SPLIT", "카메라 화면 종료 감지: 1% 커스텀 분할 화면 복구 실행")
                SplitScreenManager.restoreLastSplitScreen(it)
            }
        }, 500L)
    }

    private fun checkBsdWithTurnSignal() {
        if (isBsdActive && isTurnSignalOn) {
            audioManager.playBlindSpotWarning()
        }
    }

    override fun onCreate() {
        super.onCreate()
        DolphinLogger.init(this)
        DolphinLogger.i("SERVICE", "DolphinService 생성됨")

        audioManager = VoiceAndSoundManager(this)
        hazardManager = HazardLightManager(this)

        startForegroundServiceNotification()
        registerVehicleReceiver()

        // 8번 부팅 시 자동 실행 스케줄러 가동
        scheduleBootAutoExecutions()
    }

    // 8번 요구사항: 부팅 후 N초 뒤 미디어 앱 재생 및 커스텀 앱 자동 실행
    private fun scheduleBootAutoExecutions() {
        if (!SettingsManager.isBootAutoEnabled(this)) return

        // 8-1. 미디어 앱 실행 후 N초 뒤 자동 재생
        val mediaPkg = SettingsManager.getBootMediaPkg(this)
        val mediaDelay = SettingsManager.getBootMediaDelay(this)
        handler.postDelayed({
            DolphinLogger.i("BOOT", "부팅 미디어 앱 실행: $mediaPkg")
            packageManager.getLaunchIntentForPackage(mediaPkg)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            // 실행 후 2초 뒤 재생 키 이벤트 전송
            handler.postDelayed({
                triggerMediaPlay()
            }, 2000L)
        }, mediaDelay * 1000L)

        // 8-2. 커스텀 앱 N초 후 자동 실행
        val customPkg = SettingsManager.getBootCustomPkg(this)
        val customDelay = SettingsManager.getBootCustomDelay(this)
        handler.postDelayed({
            DolphinLogger.i("BOOT", "부팅 커스텀 앱 자동 실행: $customPkg")
            packageManager.getLaunchIntentForPackage(customPkg)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }, customDelay * 1000L)
    }

    private fun triggerMediaPlay() {
        try {
            val audioService = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
            audioService.dispatchMediaKeyEvent(down)
            audioService.dispatchMediaKeyEvent(up)
            DolphinLogger.i("BOOT", "미디어 재생 키 이벤트 전송 완료")
        } catch (e: Exception) {
            DolphinLogger.w("BOOT", "미디어 키 이벤트 실패: ${e.message}")
        }
    }

    private fun registerVehicleReceiver() {
        val filter = IntentFilter().apply {
            addAction("byd.intent.action.AUTO_VIDEO_ON")
            addAction("byd.intent.action.pano")
            addAction("byd.intent.action.AUTO_EXIT_PANO")
            addAction("com.byd.auto.action.AC_DEFROST_CONTROL")
            addAction("com.byd.auto.intent.action.AC_STATUS")
            addAction("byd.intent.action.AC_STATUS")
            addAction("com.byd.auto.action.DEFROST_SWITCH")
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction("com.byd.auto.intent.action.GEAR_CHANGED")
            addAction("com.byd.auto.intent.action.AUTOHOLD_SWITCH_CHANGED")
            addAction("com.byd.auto.intent.action.AUTOHOLD_FUNCTION_STATUS")
            addAction("com.byd.auto.intent.action.EPB_STATUS")
            addAction("com.byd.auto.intent.action.ICC_STATUS")
            addAction("com.byd.auto.intent.action.PILOT_STATUS")
            addAction("com.byd.auto.intent.action.DRIVE_MODE_CHANGED")
            addAction("com.byd.auto.intent.action.REGEN_MODE_CHANGED")
            addAction("com.byd.auto.intent.action.SPEED_CHANGED")
            addAction("com.byd.auto.intent.action.LANE_DEPARTURE_WARNING")
            addAction("com.byd.auto.intent.action.BSD_STATUS")
            addAction("com.byd.auto.intent.action.TURN_SIGNAL_STATUS")
            addAction("com.byd.auto.intent.action.CHARGING_STATUS")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vehicleEventReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(vehicleEventReceiver, filter)
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "dolphin_assistant_channel"
        val channelName = "Dolphin Vehicle Automation"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("돌핀 자동화 어시스턴트 가동 중")
            .setContentText("1% 커스텀 분할 화면, HUD & 계기판 TBT, 음성 가이던스 실시간 동작")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(vehicleEventReceiver)
        audioManager.release()
        hazardManager.cleanup()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
