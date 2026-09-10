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
import com.byd.dolphin.autoassistant.hud.NavGuidanceParser
import com.byd.dolphin.autoassistant.manager.*
import com.byd.dolphin.autoassistant.split.SplitScreenManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * 돌핀 스마트 어시스턴트 상시 백그라운드 서비스
 */
class DolphinService : Service() {

    private lateinit var audioManager: VoiceAndSoundManager
    private lateinit var hazardManager: HazardLightManager
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var lvdaEngine: LeadingVehicleDepartureEngine
    private lateinit var bootAutomation: BootAutomationController
    private lateinit var ignitionMonitor: IgnitionMonitor
    private lateinit var telemetryMonitor: VehicleTelemetryMonitor
    private var currentSpeed: Float = 0.0f

    private var previousGear: String? = null
    private val restoreSplitRunnable = Runnable {
        DolphinLogger.i("SPLIT", "카메라 종료 뒤 저장된 2분할 복원 실행")
        SplitScreenManager.restoreLastSplitScreen(applicationContext)
    }

    private val vehicleEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val extrasSummary = intent.extras?.let { bundle ->
                bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" }
            } ?: "none"

            DolphinLogger.logIntent(action, extrasSummary)

            when (action) {
                // 360 서라운드뷰 종료 감지 -> 50:50 초기화 방지 & 커스텀 비율 복원
                "byd.intent.action.AUTO_VIDEO_ON", "byd.intent.action.pano" -> {
                    val autovideoOn = intent.getIntExtra("autovideo_on", intent.getIntExtra("panoState", -1))
                    if (autovideoOn == 0) {
                        scheduleSplitScreenRestoration(context)
                    }
                }
                "byd.intent.action.AUTO_EXIT_PANO" -> {
                    scheduleSplitScreenRestoration(context)
                }

            }
        }
    }

    private val internalEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NavGuidanceParser.ACTION_INTERNAL_LEADING_CAR) {
                DolphinLogger.i("NAV_LVDA", "앱 내부 내비 알림 분석 결과 수신 -> 음성 출력")
                audioManager.speakLeadingCarDeparture()
            } else if (intent?.action == ACTION_INTERNAL_TEST_GEAR) {
                val gear = intent.getStringExtra(EXTRA_TEST_GEAR) ?: return
                DolphinLogger.i("GEAR_TEST", "앱 내부 가상 기어 이벤트: $gear")
                handleGearChange(context, gear, 0f)
            }
        }
    }

    private fun handleGearChange(context: Context?, gearStr: String, speed: Float) {
        val normalized = gearStr.trim().uppercase()
        if (normalized !in setOf("P", "R", "N", "D")) {
            DolphinLogger.w("GEAR", "알 수 없는 기어 값 무시: $gearStr")
            return
        }
        val prev = previousGear
        currentSpeed = speed
        if (prev == null) {
            previousGear = normalized
            DolphinLogger.i("GEAR", "초기 기어 기준값 설정: $normalized, 속도=$speed")
            lvdaEngine.updateVehicleSpeedAndGear(speed, normalized)
            hazardManager.onSpeedChanged(speed)
            return
        }
        if (prev == normalized) {
            lvdaEngine.updateVehicleSpeedAndGear(speed, normalized)
            hazardManager.onSpeedChanged(speed)
            return
        }
        previousGear = normalized
        DolphinLogger.i("GEAR", "기어 변속: $prev -> $normalized, 속도=$speed")

        audioManager.speakGear(normalized)
        lvdaEngine.updateVehicleSpeedAndGear(speed, normalized)
        checkCustomScenarios("GEAR_$normalized")

        if (prev == "R" && normalized in setOf("D", "N", "P")) {
            scheduleSplitScreenRestoration(context)
        }

        val gear = when (normalized) {
            "R" -> HazardLightManager.Gear.R
            "N" -> HazardLightManager.Gear.N
            "D" -> HazardLightManager.Gear.D
            else -> HazardLightManager.Gear.P
        }
        hazardManager.onGearChanged(gear, speed)
    }

    private fun scheduleSplitScreenRestoration(context: Context?) {
        if (context == null) return
        handler.removeCallbacks(restoreSplitRunnable)
        handler.postDelayed(restoreSplitRunnable, 500L)
    }

    private fun checkCustomScenarios(triggerKey: String) {
        val scenarios = SettingsManager.getCustomScenarios(this)
        for (sc in scenarios) {
            if (!sc.isEnabled) continue
            if (sc.triggerType.equals(triggerKey, ignoreCase = true) ||
                (triggerKey == "READY_ON" && sc.triggerType == "READY_ON")) {
                executeScenarioAction(sc)
            }
        }
    }

    private fun executeScenarioAction(sc: CustomScenario) {
        DolphinLogger.i("SCENARIO", "시나리오 실행: ${sc.name} -> ${sc.actionName}")
        when (sc.actionType) {
            "AC_FAN" -> {
                val speed = sc.actionValue.toIntOrNull() ?: 3
                VehicleComfortManager.setAcPower(this, true)
                VehicleComfortManager.setAcFanLevel(this, speed)
            }
            "AC_OFF" -> VehicleComfortManager.setAcPower(this, false)
            "DEFROST_ALL" -> {
                if (sc.actionValue == "0") DefrostManager.turnOff(this, showToast = false)
                else DefrostManager.turnOn(this, showToast = false)
            }
            "DEFROST_REAR" -> DefrostManager.triggerRearDefrost(this, sc.actionValue != "0")
            "SEAT_STAGE" -> {
                DolphinLogger.w("SCENARIO", "검증되지 않은 전동시트 위치 규칙은 안전을 위해 실행하지 않음")
            }
            "DRIVER_SEAT_HEAT" -> {
                VehicleComfortManager.setSeatHeatingLevel(
                    this,
                    VehicleComfortManager.SEAT_DRIVER,
                    sc.actionValue.toIntOrNull()?.coerceIn(0, 2) ?: 1
                )
            }
            "PASSENGER_SEAT_HEAT" -> {
                VehicleComfortManager.setSeatHeatingLevel(
                    this,
                    VehicleComfortManager.SEAT_PASSENGER,
                    sc.actionValue.toIntOrNull()?.coerceIn(0, 2) ?: 1
                )
            }
            "STEERING_HEAT" -> {
                VehicleComfortManager.setSteeringWheelHeating(this, sc.actionValue != "0")
            }
            "LIGHT_ON" -> {
                InsideLightManager.turnOn(this, showToast = false)
            }
            "LIGHT_OFF" -> {
                InsideLightManager.turnOff(this, showToast = false)
            }
            "LIGHT_DOOR" -> InsideLightManager.setDoorInterlock(this, sc.actionValue != "0")
            "WINDOW_CLOSE" -> {
                DolphinLogger.w("SCENARIO", "검증되지 않은 창문 제어 규칙은 안전을 위해 실행하지 않음")
            }
            "LAUNCH_APP" -> {
                packageManager.getLaunchIntentForPackage(sc.actionValue)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }
            else -> DolphinLogger.w("SCENARIO", "지원하지 않거나 미검증인 기존 액션 차단: ${sc.actionType}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        DolphinLogger.init(this)
        DiagnosticCaptureManager.recoverInterruptedSession(this)
        DolphinLogger.i("SERVICE", "DolphinService 생성됨")
        startForegroundServiceNotification()

        audioManager = VoiceAndSoundManager(this)
        hazardManager = HazardLightManager(this)
        bootAutomation = BootAutomationController(this)
        ignitionMonitor = IgnitionMonitor(
            context = this,
            onPowerOn = {
                DolphinLogger.i("IGNITION", "시동 ON 확정: 자동 실행 및 READY_ON 규칙 시작")
                bootAutomation.onIgnitionOn()
                checkCustomScenarios("READY_ON")
            },
            onPowerOff = {
                DolphinLogger.i("IGNITION", "시동 OFF 확정: 예약 동작 취소")
                bootAutomation.onIgnitionOff()
                checkCustomScenarios("READY_OFF")
            }
        )

        lvdaEngine = LeadingVehicleDepartureEngine(this) {
            DolphinLogger.i("LVDA", "전방 주차센서 기반 실험 엔진에서 출발 후보 감지 -> 음성 출력")
            audioManager.speakLeadingCarDeparture()
        }
        lvdaEngine.start()

        telemetryMonitor = VehicleTelemetryMonitor(
            context = this,
            onMotion = { speed, gear ->
                currentSpeed = speed
                lvdaEngine.updateVehicleSpeedAndGear(speed, gear)
                hazardManager.onSpeedChanged(speed)
            },
            onGearChanged = { gear, speed -> handleGearChange(this, gear, speed) },
            onEpbChanged = { applied -> audioManager.speakEpb(applied) },
            onChargingStarted = {
                audioManager.speakChargingStart()
                checkCustomScenarios("CHARGING_ON")
            },
            onChargingEnded = { audioManager.speakChargingEnd() },
            onFrontDefrostChanged = { enabled ->
                DefrostManager.onFrontDefrostDetected(this, enabled)
            }
        )
        telemetryMonitor.start()

        registerVehicleReceiver()
        registerInternalReceiver()
        ignitionMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::ignitionMonitor.isInitialized) ignitionMonitor.probeNow()
        return START_STICKY
    }

    private fun registerVehicleReceiver() {
        val filter = IntentFilter().apply {
            addAction("byd.intent.action.AUTO_VIDEO_ON")
            addAction("byd.intent.action.pano")
            addAction("byd.intent.action.AUTO_EXIT_PANO")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vehicleEventReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(vehicleEventReceiver, filter)
        }
    }

    private fun registerInternalReceiver() {
        val filter = IntentFilter().apply {
            addAction(NavGuidanceParser.ACTION_INTERNAL_LEADING_CAR)
            addAction(ACTION_INTERNAL_TEST_GEAR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                internalEventReceiver,
                filter,
                INTERNAL_PERMISSION,
                null,
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(internalEventReceiver, filter, INTERNAL_PERMISSION, null)
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
            .setContentTitle("돌핀 스마트 어시스턴트 가동 중")
            .setContentText("시동·차량 상태 감지 및 선택한 편의 기능 실행 중")
            .setSmallIcon(com.byd.dolphin.autoassistant.R.drawable.ic_byd_dolphin)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(restoreSplitRunnable)
        runCatching { unregisterReceiver(vehicleEventReceiver) }
        runCatching { unregisterReceiver(internalEventReceiver) }
        if (::audioManager.isInitialized) audioManager.release()
        if (::hazardManager.isInitialized) hazardManager.cleanup()
        if (::lvdaEngine.isInitialized) lvdaEngine.stop()
        if (::telemetryMonitor.isInitialized) telemetryMonitor.stop()
        if (::bootAutomation.isInitialized) bootAutomation.destroy()
        if (::ignitionMonitor.isInitialized) ignitionMonitor.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_INTERNAL_TEST_GEAR =
            "com.byd.dolphin.autoassistant.action.TEST_GEAR"
        const val EXTRA_TEST_GEAR = "test_gear"
        const val INTERNAL_PERMISSION =
            "com.byd.dolphin.autoassistant.permission.INTERNAL_EVENT"
    }
}
