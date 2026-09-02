package com.byd.dolphin.autoassistant.rule.engine

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.byd.dolphin.autoassistant.manager.DefrostManager
import com.byd.dolphin.autoassistant.manager.InsideLightManager
import com.byd.dolphin.autoassistant.rule.model.ActionStep
import com.byd.dolphin.autoassistant.rule.model.ActionType
import com.byd.dolphin.autoassistant.rule.model.RoutineRule
import com.byd.dolphin.autoassistant.rule.model.TriggerType
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 0.x초 정밀 딜레이 및 차량 하드웨어/앱 실행 완전 자동화 실행 엔진
 */
object RuleEngine {

    private const val TAG = "RuleEngine"
    private val scope = CoroutineScope(Dispatchers.Main)

    // 최근 실행 시간 기록 (중복 트리거 방지)
    private val lastRunMap = mutableMapOf<String, Long>()

    fun onVehicleReady(context: Context, currentTemp: Float? = null) {
        DolphinLogger.i(TAG, "Trigger Check: 시동(READY) 감지")
        val rules = RuleStorage.loadRules(context).filter { it.isEnabled && it.trigger.type == TriggerType.ON_READY_POWER }
        rules.forEach { rule ->
            executeRule(context, rule)
        }

        // 온도 트리거도 시동 시점에 함께 평가
        currentTemp?.let { onTemperatureChecked(context, it) }
    }

    fun onTemperatureChecked(context: Context, temp: Float) {
        val rules = RuleStorage.loadRules(context).filter { it.isEnabled && it.trigger.type == TriggerType.TEMPERATURE }
        rules.forEach { rule ->
            val trig = rule.trigger
            val match = when (trig.tempOperator) {
                ">=" -> temp >= trig.tempValue
                "<=" -> temp <= trig.tempValue
                else -> false
            }
            if (match) {
                executeRule(context, rule)
            }
        }
    }

    fun onGearChanged(context: Context, gearStr: String) {
        val rules = RuleStorage.loadRules(context).filter { 
            it.isEnabled && it.trigger.type == TriggerType.GEAR_CHANGED && it.trigger.gearTarget.equals(gearStr, ignoreCase = true) 
        }
        rules.forEach { rule ->
            executeRule(context, rule)
        }
    }

    fun onAcDefrostTriggered(context: Context) {
        val rules = RuleStorage.loadRules(context).filter { it.isEnabled && it.trigger.type == TriggerType.AC_DEFROST_TRIGGERED }
        rules.forEach { rule ->
            executeRule(context, rule)
        }
    }

    fun onAcHeaterTriggered(context: Context) {
        val rules = RuleStorage.loadRules(context).filter { it.isEnabled && it.trigger.type == TriggerType.AC_HEATER_TRIGGERED }
        rules.forEach { rule ->
            executeRule(context, rule)
        }
    }

    /**
     * 규칙의 액션들을 0.x초 단위 지연 시간을 반영하여 순차 실행
     */
    fun executeRule(context: Context, rule: RoutineRule) {
        val now = System.currentTimeMillis()
        val lastRun = lastRunMap[rule.id] ?: 0L
        if (now - lastRun < 5000L) { // 5초 이내 동일 규칙 중복 실행 억제
            return
        }
        lastRunMap[rule.id] = now

        DolphinLogger.i(TAG, "Executing Rule: '${rule.name}' (액션 수: ${rule.actions.size})")

        scope.launch {
            for ((index, action) in rule.actions.withIndex()) {
                executeActionStep(context, action, index + 1)
            }
        }
    }

    private suspend fun executeActionStep(context: Context, action: ActionStep, stepNum: Int) {
        when (action.type) {
            ActionType.DELAY -> {
                // 0.x초 정밀 밀리초 딜레이 (예: 0.1초 = 100ms, 0.5초 = 500ms, 2.3초 = 2300ms)
                val millis = (action.delaySeconds * 1000).toLong().coerceAtLeast(50L)
                DolphinLogger.i(TAG, "Step $stepNum: ${action.delaySeconds}초 정밀 대기 (${millis}ms)")
                delay(millis)
            }

            ActionType.LAUNCH_APP -> {
                if (action.appPackage.isNotEmpty()) {
                    DolphinLogger.i(TAG, "Step $stepNum: 앱 실행 -> ${action.appName} (${action.appPackage})")
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(action.appPackage)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        }
                        if (intent != null) {
                            context.startActivity(intent)
                        } else {
                            Log.w(TAG, "Launch intent not found for ${action.appPackage}")
                        }
                    } catch (e: Exception) {
                        DolphinLogger.e(TAG, "앱 실행 실패: ${action.appPackage}", e)
                    }
                }
            }

            ActionType.MEDIA_CONTROL -> {
                DolphinLogger.i(TAG, "Step $stepNum: 미디어 제어 -> ${action.mediaCmd}")
                sendMediaCommand(context, action.mediaCmd)
            }

            ActionType.AC_CONTROL -> {
                DolphinLogger.i(TAG, "Step $stepNum: 공조 제어 -> 온도: ${action.acTemp}도, 풍량: ${action.acWind}단")
                setVehicleAc(context, action.acTemp, action.acWind)
            }

            ActionType.HEAT_CONTROL -> {
                DolphinLogger.i(TAG, "Step $stepNum: 열선 제어 -> 핸들: ${action.heatSteering}, 운전석: ${action.heatDriverSeat}, 동승석: ${action.heatPassengerSeat}")
                setVehicleHeat(context, action.heatSteering, action.heatDriverSeat, action.heatPassengerSeat)
            }

            ActionType.INSIDE_LIGHT -> {
                DolphinLogger.i(TAG, "Step $stepNum: 전체 실내등 -> ${if (action.lightOn) "ON" else "OFF"}")
                if (action.lightOn) {
                    InsideLightManager.turnOn(context, showToast = false)
                } else {
                    InsideLightManager.turnOff(context, showToast = false)
                }
            }

            ActionType.DEFROST_CONTROL -> {
                DolphinLogger.i(TAG, "Step $stepNum: 통합 성에제거 -> ${if (action.defrostOn) "ON" else "OFF"}")
                if (action.defrostOn) {
                    DefrostManager.turnOn(context, showToast = false)
                } else {
                    DefrostManager.turnOff(context, showToast = false)
                }
            }
        }
    }

    private fun sendMediaCommand(context: Context, cmd: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val keyCode = when (cmd.uppercase()) {
            "PLAY" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }

        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun setVehicleAc(context: Context, temp: Int, windLevel: Int) {
        try {
            val clazz = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, context)

            // 온도 설정 (area 1: 운전석, area 2: 조수석)
            val setTempMethod = clazz.methods.firstOrNull { it.name.equals("setTemprature", ignoreCase = true) || it.name.equals("setTemperature", ignoreCase = true) }
            setTempMethod?.invoke(instance, 1, temp)
            setTempMethod?.invoke(instance, 2, temp)

            // 풍량 설정
            val setWindMethod = clazz.methods.firstOrNull { it.name.contains("WindLevel", ignoreCase = true) }
            setWindMethod?.invoke(instance, windLevel)

            // 에어컨 시작
            val setStartMethod = clazz.methods.firstOrNull { it.name.contains("AcStart", ignoreCase = true) }
            setStartMethod?.invoke(instance, 1)
        } catch (e: Exception) {
            Log.w(TAG, "BYDAutoAcDevice call error: ${e.message}")
        }
    }

    private fun setVehicleHeat(context: Context, steering: Boolean, driverSeat: Boolean, passengerSeat: Boolean) {
        try {
            // 핸들 열선
            if (steering) {
                val settingClazz = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
                val getInstance = settingClazz.getMethod("getInstance", Context::class.java)
                val instance = getInstance.invoke(null, context)
                val method = settingClazz.methods.firstOrNull { it.name.contains("SteeringWheelHeating", ignoreCase = true) }
                method?.invoke(instance, 1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Steering heat call error: ${e.message}")
        }

        try {
            // 시트 열선
            val acClazz = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice")
            val getInstance = acClazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, context)

            val seatHeatMethod = acClazz.methods.firstOrNull { it.name.contains("SeatHeating", ignoreCase = true) }
            if (driverSeat) seatHeatMethod?.invoke(instance, 1, 1)
            if (passengerSeat) seatHeatMethod?.invoke(instance, 2, 1)
        } catch (e: Exception) {
            Log.w(TAG, "Seat heat call error: ${e.message}")
        }
    }
}
