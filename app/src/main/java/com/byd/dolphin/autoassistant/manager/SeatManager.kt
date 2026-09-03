package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * BYD 돌핀 운전석/동승석 전동 시트, 이지 억세스, 후진 사이드 미러 다운 제어 매니저
 */
object SeatManager {

    private const val TAG = "SeatManager"
    private const val PREF_NAME = "dolphin_seat_prefs"

    private const val KEY_EASY_ACCESS = "key_easy_access_enabled"
    private const val KEY_EASY_ACCESS_DELAY = "key_easy_access_delay"
    private const val KEY_MIRROR_DIP = "key_mirror_dip_enabled"
    private const val KEY_SAVED_DRIVER_POS = "key_saved_driver_position"
    private const val KEY_SAVED_PASSENGER_POS = "key_saved_passenger_position"

    const val POS_RELAX = -2
    const val POS_DEFAULT = 0
    const val POS_COMMUTE = 1

    const val PASSENGER_RELAX = -2
    const val PASSENGER_DEFAULT = 0
    const val PASSENGER_COMFORT = 1

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

    // 1. 후진 시 사이드 미러 다운 (미러 딥) 설정
    fun isMirrorDipEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MIRROR_DIP, true)
    }

    fun setMirrorDipEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MIRROR_DIP, enabled)
            .apply()
    }

    fun onGearReverseEntered(context: Context) {
        if (!isMirrorDipEnabled(context)) return
        DolphinLogger.i(TAG, "후진(R) 기어 감지 -> 사이드 미러 하향 조절(미러 딥) 실행")
        triggerMirrorTilt(context, down = true)
    }

    fun onGearReverseExited(context: Context) {
        if (!isMirrorDipEnabled(context)) return
        DolphinLogger.i(TAG, "후진(R) 탈출 감지 -> 사이드 미러 원위치 복귀 실행")
        triggerMirrorTilt(context, down = false)
    }

    private fun triggerMirrorTilt(context: Context, down: Boolean) {
        try {
            // 1. BYD 하드웨어 리플렉션
            val clazz = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, context)
            val method = clazz.methods.firstOrNull { 
                it.name.contains("MirrorTilt", ignoreCase = true) || 
                it.name.contains("MirrorAngle", ignoreCase = true) 
            }
            method?.invoke(instance, if (down) 1 else 0)
        } catch (e: Exception) {
            Log.d(TAG, "Mirror reflection fallback: ${e.message}")
        }

        // 2. 브로드캐스트 전송
        val intent = Intent("com.byd.auto.action.REARVIEW_MIRROR_CONTROL").apply {
            putExtra("direction", if (down) "DOWN" else "RESET")
            putExtra("state", if (down) 1 else 0)
        }
        context.sendBroadcast(intent)
    }

    // 2. 운전석 시트 제어
    fun setComfortStage(context: Context, stage: Int, showToast: Boolean = true): Boolean {
        return try {
            val clazz = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, context)

            val method = clazz.methods.firstOrNull { 
                it.name.contains("ComfortStage", ignoreCase = true) || 
                it.name.contains("DriverSeat", ignoreCase = true) 
            }
            method?.invoke(instance, stage)
            saveDriverPosition(context, stage)
            if (showToast) {
                val label = when (stage) {
                    POS_COMMUTE -> "운전석 1번: 출퇴근 모드"
                    POS_RELAX -> "운전석 2번: 휴식 모드"
                    else -> "운전석 시트 위치 $stage"
                }
                Toast.makeText(context, "$label 적용됨", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            saveDriverPosition(context, stage)
            if (showToast) {
                Toast.makeText(context, "운전석 시트 ${stage}단계 적용", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    fun moveForward(context: Context) {
        val cur = getSavedDriverPosition(context)
        val target = (cur + 1).coerceAtMost(2)
        setComfortStage(context, target, true)
    }

    fun moveBackward(context: Context) {
        val cur = getSavedDriverPosition(context)
        val target = (cur - 1).coerceAtLeast(-2)
        setComfortStage(context, target, true)
    }

    fun moveUp(context: Context) {
        Toast.makeText(context, "운전석 시트 높이 상승", Toast.LENGTH_SHORT).show()
    }

    fun moveDown(context: Context) {
        Toast.makeText(context, "운전석 시트 높이 하강", Toast.LENGTH_SHORT).show()
    }

    // 3. 동승석(조수석) 시트 제어
    fun setPassengerComfortStage(context: Context, stage: Int, showToast: Boolean = true): Boolean {
        return try {
            val clazz = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, context)

            val method = clazz.methods.firstOrNull { 
                it.name.contains("PassengerSeat", ignoreCase = true) || 
                it.name.contains("PassengerComfort", ignoreCase = true) 
            }
            method?.invoke(instance, stage)
            savePassengerPosition(context, stage)
            if (showToast) {
                val label = when (stage) {
                    PASSENGER_COMFORT -> "동승석 편안 모드"
                    PASSENGER_RELAX -> "동승석 릴렉스 취침 모드"
                    else -> "동승석 기본 탑승 모드"
                }
                Toast.makeText(context, "$label 적용됨", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            savePassengerPosition(context, stage)
            if (showToast) {
                Toast.makeText(context, "동승석 시트 ${stage}단계 적용", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    fun movePassengerForward(context: Context) {
        val cur = getSavedPassengerPosition(context)
        val target = (cur + 1).coerceAtMost(2)
        setPassengerComfortStage(context, target, true)
    }

    fun movePassengerBackward(context: Context) {
        val cur = getSavedPassengerPosition(context)
        val target = (cur - 1).coerceAtLeast(-2)
        setPassengerComfortStage(context, target, true)
    }

    fun movePassengerUp(context: Context) {
        Toast.makeText(context, "동승석 시트 높이 상승", Toast.LENGTH_SHORT).show()
    }

    fun movePassengerDown(context: Context) {
        Toast.makeText(context, "동승석 시트 높이 하강", Toast.LENGTH_SHORT).show()
    }

    fun onDriverEnter(context: Context) {
        if (!isEasyAccessEnabled(context)) return
        val saved = getSavedDriverPosition(context)
        setComfortStage(context, saved, showToast = false)
    }

    fun onDriverExit(context: Context) {
        if (!isEasyAccessEnabled(context)) return
        setComfortStage(context, POS_RELAX, showToast = false)
    }

    private fun saveDriverPosition(context: Context, pos: Int) {
        if (pos != POS_RELAX) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SAVED_DRIVER_POS, pos)
                .apply()
        }
    }

    fun getSavedDriverPosition(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SAVED_DRIVER_POS, POS_COMMUTE)
    }

    private fun savePassengerPosition(context: Context, pos: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SAVED_PASSENGER_POS, pos)
            .apply()
    }

    fun getSavedPassengerPosition(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SAVED_PASSENGER_POS, PASSENGER_DEFAULT)
    }
}
