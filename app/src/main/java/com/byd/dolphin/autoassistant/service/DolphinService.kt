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
import com.byd.dolphin.autoassistant.manager.HazardLightManager
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.manager.VoiceAndSoundManager
import com.byd.dolphin.autoassistant.split.SplitScreenManager

class DolphinService : Service() {

    private lateinit var audioManager: VoiceAndSoundManager
    private lateinit var hazardManager: HazardLightManager
    private val handler = Handler(Looper.getMainLooper())

    private var isBsdActive = false
    private var isTurnSignalOn = false
    private var isCharging = false
    private var previousGear = "P"
    private var previousAutoHoldSwitchState: Boolean? = null

    private val vehicleEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                when (action) {
                    "com.byd.auto.intent.action.GEAR_CHANGED" -> {
                        val gearStr = intent.getStringExtra("gear") ?: "P"
                        val speed = intent.getFloatExtra("speed", 0.0f)
                        
                        audioManager.speakGear(gearStr)

                        val prev = previousGear
                        previousGear = gearStr

                        // R -> D/N/P 전환 시 360 카메라가 닫히면 커스텀 분할 비율 자동 복구 (0.6초 딜레이)
                        if (prev == "R" && (gearStr == "D" || gearStr == "N" || gearStr == "P")) {
                            handler.postDelayed({
                                context?.let { SplitScreenManager.restoreLastSplitScreen(it) }
                            }, 600L)
                        }

                        val gear = when (gearStr.uppercase()) {
                            "R" -> HazardLightManager.Gear.R
                            "N" -> HazardLightManager.Gear.N
                            "D" -> HazardLightManager.Gear.D
                            else -> HazardLightManager.Gear.P
                        }
                        hazardManager.onGearChanged(gear, speed)
                    }

                    "com.byd.auto.intent.action.AVM_STATUS" -> {
                        val isAvmOpen = intent.getBooleanExtra("is_open", false)
                        if (!isAvmOpen) {
                            handler.postDelayed({
                                context?.let { SplitScreenManager.restoreLastSplitScreen(it) }
                            }, 500L)
                        }
                    }

                    "com.byd.auto.intent.action.AUTOHOLD_SWITCH_CHANGED", "com.byd.auto.intent.action.AUTOHOLD_FUNCTION_STATUS" -> {
                        val isEnabled = intent.getBooleanExtra("is_enabled", true)
                        if (previousAutoHoldSwitchState != isEnabled) {
                            previousAutoHoldSwitchState = isEnabled
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

                    "com.byd.auto.intent.action.CHARGING_STATUS" -> {
                        val chargingNow = intent.getBooleanExtra("is_charging", false)
                        if (SettingsManager.isChargingVoiceEnabled(this@DolphinService)) {
                            if (!isCharging && chargingNow) {
                                isCharging = true
                                audioManager.speak("충전이 시작되었습니다.")
                            } else if (isCharging && !chargingNow) {
                                isCharging = false
                                audioManager.speak("충전이 중지 되었습니다.")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkBsdWithTurnSignal() {
        if (isBsdActive && isTurnSignalOn) {
            audioManager.playBlindSpotWarning()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = VoiceAndSoundManager(this)
        hazardManager = HazardLightManager(this)

        startForegroundServiceNotification()
        registerVehicleReceiver()
    }

    private fun registerVehicleReceiver() {
        val filter = IntentFilter().apply {
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
            registerReceiver(vehicleEventReceiver, filter, RECEIVER_NOT_EXPORTED)
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
            .setContentTitle("돌핀 자동화 어시스턴트 동작 중")
            .setContentText("1% 커스텀 화면 분할 복구 및 운전석 가이던스 활성화")
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
