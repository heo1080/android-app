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
 *
 * 주의: BYD 차량 순정 런처는 홈 화면 바로가기 핀 추가를 지원하지 않으므로,
 * 모든 단축 기능은 앱서랍(App Drawer) 전용 액티비티로 등록되어 독립적으로 실행됩니다.
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
        saveLightState(context, true)
        DolphinLogger.i(TAG, "전체 실내등 점등(ON) 실행 - 결과: $success")
        if (showToast) {
            Toast.makeText(context, "실내등 켜짐", Toast.LENGTH_SHORT).show()
        }
        return success
    }

    /**
     * 전체 실내등 끄기
     */
    fun turnOff(context: Context, showToast: Boolean = true): Boolean {
        val success = invokeBydInsideLight(context, PARAM_LIGHT_OFF)
        saveLightState(context, false)
        DolphinLogger.i(TAG, "전체 실내등 소등(OFF) 실행 - 결과: $success")
        if (showToast) {
            Toast.makeText(context, "실내등 꺼짐", Toast.LENGTH_SHORT).show()
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
            val instance = getInstance.invoke(null, context)
            val method = clazz.getMethod("setInsideLightDoorState", Int::class.javaPrimitiveType)
            method.invoke(instance, stateVal)
            DolphinLogger.i(TAG, "도어 연동 실내등 설정 완료: $enable (value=$stateVal)")
            true
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "setInsideLightDoorState 호출 실패", e)
            false
        }
    }

    /**
     * BYD AutoSettingDevice를 리플렉션으로 호출하여 실내등 점등/소등 실행
     */
    private fun invokeBydInsideLight(context: Context, param: Int): Boolean {
        return try {
            val clazz = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, context)

            val method = clazz.getMethod("turnOffInsideLight", Int::class.javaPrimitiveType)
            val result = method.invoke(instance, param)
            Log.d(TAG, "BYDAutoSettingDevice.turnOffInsideLight($param) 성공, 반환값: $result")
            true
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
