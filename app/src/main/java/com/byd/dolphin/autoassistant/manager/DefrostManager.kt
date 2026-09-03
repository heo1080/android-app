package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * BYD 돌핀 성에 제거 (AC Front & Rear Defrost) 제어 매니저
 * 
 * 1번 퀵패널의 '통합 성에 제거'(앞유리 최대 송풍 + 뒷유리/사이드미러 열선 동시 작동)를
 * 원터치로 수행하고, 순정 앞유리 성에제거 작동 시 뒷유리 열선을 자동 동기화합니다.
 */
object DefrostManager {

    private const val TAG = "DefrostManager"
    private const val PREF_NAME = "dolphin_defrost_prefs"
    private const val KEY_IS_DEFROST_ON = "key_is_defrost_on"

    // BYD 순정 에어컨/디프로스트 브로드캐스트 액션
    private const val ACTION_BYD_AC_DEFROST = "com.byd.auto.action.AC_DEFROST_CONTROL"
    private const val ACTION_BYD_REAR_DEFROST = "com.byd.auto.action.REAR_DEFROST_CONTROL"
    private const val ACTION_BYD_AC_CONTROL = "com.byd.auto.intent.action.AC_CONTROL"

    /**
     * 전·후면 통합 성에 제거 켜기 (앞유리 송풍 + 뒷유리/미러 열선 동시 ON)
     */
    fun turnOn(context: Context, showToast: Boolean = true): Boolean {
        val success = executeDefrostCommand(context, true)
        saveState(context, true)
        DolphinLogger.i(TAG, "통합 성에 제거 점등(ON) 실행 - 결과: $success")
        if (showToast) {
            Toast.makeText(context, "통합 성에 제거 켜짐 (앞유리+뒷유리 열선)", Toast.LENGTH_SHORT).show()
        }
        return success
    }

    /**
     * 전·후면 통합 성에 제거 끄기
     */
    fun turnOff(context: Context, showToast: Boolean = true): Boolean {
        val success = executeDefrostCommand(context, false)
        saveState(context, false)
        DolphinLogger.i(TAG, "통합 성에 제거 소등(OFF) 실행 - 결과: $success")
        if (showToast) {
            Toast.makeText(context, "성에 제거 꺼짐", Toast.LENGTH_SHORT).show()
        }
        return success
    }

    /**
     * 통합 성에 제거 토글 (ON <-> OFF)
     */
    fun toggle(context: Context, showToast: Boolean = true): Boolean {
        val currentState = isDefrostOn(context)
        return if (currentState) {
            turnOff(context, showToast)
        } else {
            turnOn(context, showToast)
        }
    }

    /**
     * 순정 하단바 앞유리 성에제거 동작 감지 시 뒷유리 열선 자동 동기화 (방법 2)
     */
    fun onFrontDefrostDetected(context: Context, isFrontDefrostActive: Boolean) {
        if (!SettingsManager.isAutoDefrostSyncEnabled(context)) return

        DolphinLogger.i(TAG, "순정 앞유리 성에제거 감지 -> 뒷유리 열선 동기화: $isFrontDefrostActive")
        triggerRearDefrost(context, isFrontDefrostActive)
    }

    /**
     * 뒷유리 및 사이드미러 열선 단독 제어
     */
    fun triggerRearDefrost(context: Context, enable: Boolean): Boolean {
        var success = false
        try {
            // 1. BYDAutoAcDevice 리플렉션 호출
            val clazz = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, context)

            // setRearDefrostState 또는 setRearDefrost
            val method = clazz.methods.firstOrNull { it.name.contains("RearDefrost", ignoreCase = true) }
            if (method != null) {
                val param = if (enable) 1 else 0
                method.invoke(instance, param)
                success = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "BYDAutoAcDevice rear defrost call fallback: ${e.message}")
        }

        // 2. 브로드캐스트 전송
        val intent = Intent(ACTION_BYD_REAR_DEFROST).apply {
            putExtra("state", if (enable) 1 else 0)
        }
        context.sendBroadcast(intent)
        return success
    }

    private fun executeDefrostCommand(context: Context, enable: Boolean): Boolean {
        var success = false
        val stateVal = if (enable) 1 else 0

        // 1. BYDAutoAcDevice 하드웨어 제어
        try {
            val clazz = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, context)

            // 앞유리 디프로스트
            val frontMethod = clazz.methods.firstOrNull { 
                it.name.equals("setAcDefrostState", ignoreCase = true) || 
                it.name.equals("setFrontDefrostState", ignoreCase = true) ||
                it.name.contains("Defrost", ignoreCase = true)
            }
            frontMethod?.invoke(instance, stateVal)

            // 뒷유리 디프로스트 동시 실행
            triggerRearDefrost(context, enable)
            success = true
        } catch (e: Exception) {
            Log.w(TAG, "BYDAutoAcDevice reflection error: ${e.message}")
        }

        // 2. 순정 통합 성에제거 인텐트 브로드캐스트 전송
        try {
            val intent = Intent(ACTION_BYD_AC_DEFROST).apply {
                putExtra("state", stateVal)
                putExtra("is_max_defrost", enable)
            }
            context.sendBroadcast(intent)

            val subIntent = Intent(ACTION_BYD_AC_CONTROL).apply {
                putExtra("cmd", "defrost")
                putExtra("state", stateVal)
            }
            context.sendBroadcast(subIntent)
            success = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send defrost broadcast", e)
        }

        return success
    }

    fun isDefrostOn(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_DEFROST_ON, false)
    }

    private fun saveState(context: Context, isOn: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_DEFROST_ON, isOn)
            .apply()
    }
}
