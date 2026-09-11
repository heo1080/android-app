package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * BYD 돌핀 실내등(InsideLight / InsightLight) 제어 매니저
 * 
 * 시스템 분석 결과:
 * - 클래스: android.hardware.bydauto.setting.BYDAutoSettingDevice
 * - 함수: turnOffInsideLight(int state)
 *   - 2: 전체 실내등 켜기 (InsightLightOnItem 호출값)
 *   - 1: 전체 실내등 끄기 (InsightLightOffItem 호출값)
 * - 도어 연동 함수: setInsideLightDoorState(int state)
 *   - 1: 도어 연동 ON
 *   - 2: 도어 연동 OFF
 * 앱서랍 액티비티와 런처가 지원하는 고정 바로가기에서 같은 검증 API를 사용합니다.
 */
object InsideLightManager {

    private const val TAG = "InsideLightManager"
    private const val PREF_NAME = "dolphin_inside_light_prefs"
    private const val KEY_IS_LIGHT_ON = "key_is_inside_light_on"

    const val PARAM_LIGHT_ON = 2
    const val PARAM_LIGHT_OFF = 1

    /**
     * 전체 실내등 켜기
     */
    fun turnOn(context: Context, showToast: Boolean = true): Boolean {
        val success = invokeBydInsideLight(context, PARAM_LIGHT_ON)
        if (success) saveLightState(context, true)
        DolphinLogger.i(TAG, "전체 실내등 점등(ON) 실행 - 결과: $success")
        if (showToast) {
            Toast.makeText(context, if (success) "실내등 켜짐" else "실내등 명령 실패 — 진단 로그를 확인하세요", Toast.LENGTH_SHORT).show()
        }
        return success
    }

    /**
     * 전체 실내등 끄기
     */
    fun turnOff(context: Context, showToast: Boolean = true): Boolean {
        val success = invokeBydInsideLight(context, PARAM_LIGHT_OFF)
        if (success) saveLightState(context, false)
        DolphinLogger.i(TAG, "전체 실내등 소등(OFF) 실행 - 결과: $success")
        if (showToast) {
            Toast.makeText(context, if (success) "실내등 꺼짐" else "실내등 명령 실패 — 진단 로그를 확인하세요", Toast.LENGTH_SHORT).show()
        }
        return success
    }

    /**
     * 전체 실내등 토글 (현재 켜져있으면 끄고, 꺼져있으면 켬)
     */
    fun toggle(context: Context, showToast: Boolean = true): Boolean {
        val currentState = isLightOn(context)
        return if (currentState) {
            turnOff(context, showToast)
        } else {
            turnOn(context, showToast)
        }
    }

    /**
     * 도어 연동 실내등 설정 (true: 1=연동 ON, false: 2=연동 OFF)
     */
    fun setDoorInterlock(context: Context, enable: Boolean): Boolean {
        return try {
            val stateVal = if (enable) 1 else 2
            val clazz = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, BydPermissionContext.wrap(context))
            val method = clazz.getMethod("setInsideLightDoorState", Int::class.javaPrimitiveType)
            val result = (method.invoke(instance, stateVal) as? Number)?.toInt()
            DolphinLogger.i(TAG, "도어 연동 실내등 설정 완료: $enable (value=$stateVal)")
            result == 0
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "setInsideLightDoorState 호출 실패", e)
            false
        }
    }

    fun isDoorInterlockEnabled(context: Context): Boolean? {
        return try {
            val clazz = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
            val instance = clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, BydPermissionContext.wrap(context))
            val value = (clazz.getMethod("getInsideLightDoorState").invoke(instance) as? Number)?.toInt()
            when (value) {
                1 -> true
                2 -> false
                else -> null
            }
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "getInsideLightDoorState 호출 실패", e.cause ?: e)
            null
        }
    }

    fun toggleDoorInterlock(context: Context): Boolean {
        val next = !(isDoorInterlockEnabled(context) ?: false)
        setDoorInterlock(context, next)
        return next
    }

    /**
     * BYD AutoSettingDevice를 리플렉션으로 호출하여 실내등 점등/소등 실행
     */
    private fun invokeBydInsideLight(context: Context, param: Int): Boolean {
        return try {
            val clazz = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, BydPermissionContext.wrap(context))

            val method = clazz.getMethod("turnOffInsideLight", Int::class.javaPrimitiveType)
            val result = (method.invoke(instance, param) as? Number)?.toInt()
            Log.d(TAG, "BYDAutoSettingDevice.turnOffInsideLight($param) 성공, 반환값: $result")
            result == 0
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "BYDAutoSettingDevice 클래스를 찾을 수 없습니다. (에뮬레이터/비BYD 환경)", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "turnOffInsideLight 호출 중 오류 발생", e)
            false
        }
    }

    fun isLightOn(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_LIGHT_ON, false)
    }

    private fun saveLightState(context: Context, isOn: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_LIGHT_ON, isOn)
            .apply()
    }
}
