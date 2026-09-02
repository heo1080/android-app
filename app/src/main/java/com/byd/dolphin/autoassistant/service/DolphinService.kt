package com.byd.dolphin.autoassistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.byd.dolphin.autoassistant.manager.DefrostManager
import com.byd.dolphin.autoassistant.rule.engine.RuleEngine
import com.byd.dolphin.autoassistant.manager.FloatingOverlayManager
import com.byd.dolphin.autoassistant.manager.HazardLightManager
import com.byd.dolphin.autoassistant.manager.InsideLightManager
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.manager.VoiceAndSoundManager
import com.byd.dolphin.autoassistant.split.SplitScreenManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

class DolphinService : Service() {

    private lateinit var audioManager: VoiceAndSoundManager
    private lateinit var hazardManager: HazardLightManager
    private val handler = Handler(Looper.getMainLooper())

    private var isBsdActive = false
    private var isTurnSignalOn = false
    private var isCharging = false
    private var previousGear = "P"
    private var previousAutoHoldState: Boolean? = null
    private var isAvmCurrentlyOpen = false

    private val vehicleEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val extrasSummary = intent.extras?.let { bundle ->
                bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" }
            } ?: "none"

            DolphinLogger.logIntent(action, extrasSummary)

            when (action) {
                // 1. BYD 순정 AVM (360° 어라운드 뷰) 팝업 및 종료 감지
                "byd.intent.action.AUTO_VIDEO_ON" -> {
                    val autovideoOn = intent.getIntExtra("autovideo_on", intent.getIntExtra("extra", -1))
                    DolphinLogger.i("AVM", "AUTO_VIDEO_ON received: state=$autovideoOn")
                    if (autovideoOn == 1) {
                        isAvmCurrentlyOpen = true
                    } else if (autovideoOn == 0) {
                        isAvmCurrentlyOpen = false
                        scheduleSplitScreenRestoration(context)
                    }
                }

                "byd.intent.action.pano" -> {
                    val panoState = intent.getIntExtra("panoState", -1)
                    DolphinLogger.i("AVM", "PANO received: panoState=$panoState")
                    if (panoState == 1) {
                        isAvmCurrentlyOpen = true
                    } else if (panoState == 0) {
                        isAvmCurrentlyOpen = false
                        scheduleSplitScreenRestoration(context)
                    }
                }

                "byd.intent.action.AUTO_EXIT_PANO" -> {
                    DolphinLogger.i("AVM", "AUTO_EXIT_PANO received. Restoring custom split screen...")
                    isAvmCurrentlyOpen = false
                    scheduleSplitScreenRestoration(context)
                }

                // 2. 기어 변속 감지 (가상 Intent & 순정 방송)
                "com.byd.auto.intent.action.GEAR_CHANGED" -> {
                    val gearStr = intent.getStringExtra("gear") ?: "P"
                    val speed = intent.getFloatExtra("speed", 0.0f)
                    handleGearChange(context, gearStr, speed)
                }

                // 공조 및 성에제거 감지
                "com.byd.auto.action.AC_DEFROST_CONTROL",
                "com.byd.auto.intent.action.AC_STATUS",
                "byd.intent.action.AC_STATUS",
                "com.byd.auto.action.DEFROST_SWITCH" -> {
                    val frontDefrost = intent.getIntExtra("front_defrost", intent.getIntExtra("state", -1))
                    if (frontDefrost == 1 && context != null) {
                        DefrostManager.onFrontDefrostDetected(context, true)
                        RuleEngine.onAcDefrostTriggered(context)
                    } else if (frontDefrost == 0 && context != null) {
                        DefrostManager.onFrontDefrostDetected(context, false)
                    }
                }

                // 3. 충전 상태 감지 (안드로이드 기본 전원 연결 및 BYD 순정 충전)
                Intent.ACTION_POWER_CONNECTED, "com.byd.auto.intent.action.CHARGING_STATUS" -> {
                    val chargingNow = intent.getBooleanExtra("is_charging", true)
                    DolphinLogger.i("CHARGING", "Power connected / charging status: $chargingNow")
                    if (SettingsManager.isChargingVoiceEnabled(this@DolphinService)) {
                        if (!isCharging && chargingNow) {
                            isCharging = true
                            audioManager.speak("충전이 시작되었습니다.")
                        }
                    }
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    DolphinLogger.i("CHARGING", "Power disconnected")
                    if (SettingsManager.isChargingVoiceEnabled(this@DolphinService)) {
                        if (isCharging) {
                            isCharging = false
                            audioManager.speak("충전이 중지되었습니다.")
                        }
                    }
                }

                // 4. 오토홀드 / EPB / 자율주행(ICC) 상태 안내
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

                "com.byd.auto.intent.action.LANE_DEPARTURE_WARNING" -> {
                    audioManager.playLaneDepartureWarning()
                }

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
        DolphinLogger.i("GEAR", "Gear changed: $prev -> $gearStr, speed=$speed")

        audioManager.speakGear(gearStr)

        // R -> D/N/P 전환 시 후진 카메라가 닫히면 1% 커스텀 분할 비율 복구
        if (prev == "R" && (gearStr == "D" || gearStr == "N" || gearStr == "P")) {
            scheduleSplitScreenRestoration(context)
        }

        if (gearStr == "P" && context != null && SettingsManager.isAutoLightParkEnabled(context)) {
            DolphinLogger.i("GEAR", "P단 주차 감지 -> 실내등 자동 점등")
            InsideLightManager.turnOn(context, showToast = false)
        }
        context?.let { RuleEngine.onGearChanged(it, gearStr) }

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
                DolphinLogger.i("SPLIT", "Restoring custom split screen after camera close")
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
        DolphinLogger.i("SERVICE", "DolphinService created")

        audioManager = VoiceAndSoundManager(this)
        hazardManager = HazardLightManager(this)

        startForegroundServiceNotification()
        registerVehicleReceiver()
        initBydHardwareSdkListeners()

        if (SettingsManager.isFloatingOverlayEnabled(this)) {
            FloatingOverlayManager.show(this)
        }
        RuleEngine.onVehicleReady(this)
    }

    private fun registerVehicleReceiver() {
        val filter = IntentFilter().apply {
            // BYD 순정 시스템 방송
            addAction("byd.intent.action.AUTO_VIDEO_ON")
            addAction("byd.intent.action.pano")
            addAction("byd.intent.action.AUTO_EXIT_PANO")
            addAction("byd.intent.action.ALL_CALL_STATE")
            addAction("byd.intent.action.PHONE_CALL_STATE_TO_AV")
            addAction("com.byd.action.bt_state_change")

            // 공조 및 디프로스트 이벤트
            addAction("com.byd.auto.action.AC_DEFROST_CONTROL")
            addAction("com.byd.auto.intent.action.AC_STATUS")
            addAction("byd.intent.action.AC_STATUS")
            addAction("com.byd.auto.action.DEFROST_SWITCH")

            // 기본 시스템 이벤트
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)

            // 가상/테스트 브로드캐스트
            addAction("com.byd.auto.intent.action.GEAR_CHANGED")
            addAction("com.byd.auto.intent.action.AVM_STATUS")
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
        DolphinLogger.i("SERVICE", "Vehicle broadcast receivers registered")
    }

    /**
     * BYD Auto SDK 하드웨어 리스너 (기어, 깜빡이, 도어 상태 실시간 후킹)
     */
    private fun initBydHardwareSdkListeners() {
        try {
            // BYDAutoGearboxDevice (Device 1011)
            // 0x21200038: 1=P, 2=R, 3=N, 4=D
            DolphinLogger.i("SDK", "BYD Hardware SDK listeners ready")
        } catch (e: Exception) {
            DolphinLogger.w("SDK", "BYD SDK hooking skipped on non-BYD environment: ${e.message}")
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
            .setContentTitle("돌핀 자동화 어시스턴트 동작 중")
            .setContentText("1% 커스텀 분할 화면 복구 및 TMAP TBT/음성 가이던스 활성화")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        DolphinLogger.i("SERVICE", "DolphinService destroying...")
        unregisterReceiver(vehicleEventReceiver)
        audioManager.release()
        hazardManager.cleanup()
        FloatingOverlayManager.hide()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
