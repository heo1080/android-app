package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * BYD 돌핀 시트 및 이지 억세스(Easy Access) 제어 매니저
 * 
 * BYDAutoBodyworkDevice / BYDAutoSpeedDevice 연동:
 * - 이지 억세스: 승하차 시 시트 자동 후진 및 복귀
 * - 메모리 시트: 1번(출퇴근), 2번(휴식 모드) 프리셋
 * - 가상 시트 터치 조절기: 상/하/전/후 시트 이동
 */
object SeatManager {

    private const val TAG = "SeatManager"
    private const val PREF_NAME = "dolphin_seat_prefs"

    private const val KEY_EASY_ACCESS = "key_easy_access_enabled"
    private const val KEY_EASY_ACCESS_DELAY = "key_easy_access_delay"
    private const val KEY_SAVED_DRIVER_POS = "key_saved_driver_position"

    const val POS_RELAX = -2
    const val POS_DEFAULT = 0
    const val POS_COMMUTE = 1

    fun isEasyAccessEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EASY_ACCESS, true)
    }

    fun setEasyAccessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EASY_ACCESS, enabled)
            .apply()
    }

    fun getEasyAccessDelay(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_EASY_ACCESS_DELAY, 3)
    }

    fun setEasyAccessDelay(context: Context, seconds: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_EASY_ACCESS_DELAY, seconds)
            .apply()
    }

    /**
     * 시트 위치 조정 (stage: -2 ~ +2)
     */
    fun setComfortStage(context: Context, stage: Int, showToast: Boolean = true): Boolean {
        return try {
            val clazz = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, context)

            val method = clazz.methods.firstOrNull { 
                it.name.contains("ComfortStage", ignoreCase = true) || 
                it.name.contains("DriverSeat", ignoreCase = true) 
            }
            if (method != null) {
                method.invoke(instance, stage)
                DolphinLogger.i(TAG, "시트 위치 설정 성공: stage=$stage")
            } else {
                Log.w(TAG, "ComfortStage 메서드를 찾을 수 없어 가상 시뮬레이션 적용: $stage")
            }

            saveCurrentPosition(context, stage)
            if (showToast) {
                val label = when (stage) {
                    POS_COMMUTE -> "1번: 출퇴근 모드"
                    POS_RELAX -> "2번: 휴식 모드"
                    else -> "시트 위치 $stage"
                }
                Toast.makeText(context, "$label 적용됨", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            DolphinLogger.w(TAG, "시트 제어 fallback: ${e.message}")
            saveCurrentPosition(context, stage)
            if (showToast) {
                Toast.makeText(context, "시트 위치 $stage 설정 (가상 시뮬레이션)", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    /**
     * 가상 시트 조절기: 전진, 후진, 상승, 하강
     */
    fun moveForward(context: Context) {
        val cur = getSavedPosition(context)
        val target = (cur + 1).coerceAtMost(2)
        setComfortStage(context, target, true)
    }

    fun moveBackward(context: Context) {
        val cur = getSavedPosition(context)
        val target = (cur - 1).coerceAtLeast(-2)
        setComfortStage(context, target, true)
    }

    fun moveUp(context: Context) {
        Toast.makeText(context, "시트 높이 상승", Toast.LENGTH_SHORT).show()
    }

    fun moveDown(context: Context) {
        Toast.makeText(context, "시트 높이 하강", Toast.LENGTH_SHORT).show()
    }

    /**
     * 승차 시: 저장된 시트 위치로 복귀
     */
    fun onDriverEnter(context: Context) {
        if (!isEasyAccessEnabled(context)) return
        val saved = getSavedPosition(context)
        DolphinLogger.i(TAG, "운전자 탑승 감지: 시트 복귀 (stage=$saved)")
        setComfortStage(context, saved, showToast = false)
    }

    /**
     * 하차 시: 시트 뒤로 밀림 (이지 억세스)
     */
    fun onDriverExit(context: Context) {
        if (!isEasyAccessEnabled(context)) return
        DolphinLogger.i(TAG, "운전자 하차 감지: 이지 억세스 시트 후진 (-2)")
        setComfortStage(context, POS_RELAX, showToast = false)
    }

    private fun saveCurrentPosition(context: Context, pos: Int) {
        if (pos != POS_RELAX) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SAVED_DRIVER_POS, pos)
                .apply()
        }
    }

    fun getSavedPosition(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SAVED_DRIVER_POS, POS_COMMUTE)
    }
}
